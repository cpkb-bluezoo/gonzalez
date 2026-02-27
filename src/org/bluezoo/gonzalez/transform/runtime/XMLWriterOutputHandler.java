/*
 * XMLWriterOutputHandler.java
 * Copyright (C) 2026 Chris Burdess
 *
 * This file is part of Gonzalez, a streaming XML parser.
 * For more information please visit https://www.nongnu.org/gonzalez/
 *
 * Gonzalez is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Gonzalez is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Gonzalez.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.bluezoo.gonzalez.transform.runtime;

import org.bluezoo.gonzalez.IndentConfig;
import org.bluezoo.gonzalez.XMLWriter;
import org.bluezoo.gonzalez.transform.compiler.OutputProperties;
import org.xml.sax.SAXException;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Output handler adapter that uses {@link XMLWriter} for streaming XML output.
 *
 * <p>This class implements {@link OutputHandler} for XSLT output, serving as
 * the bridge between XSLT transformations and Gonzalez's streaming XML writer.
 *
 * <p>It supports deferred element output (attributes can be added after
 * startElement), which is required for XSLT's attribute and namespace output
 * order independence.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class XMLWriterOutputHandler implements OutputHandler {

    private final XMLWriter writer;
    private final OutputProperties outputProperties;
    
    // Character map for XSLT 2.0+ character mapping during serialization
    private Map<Integer, String> characterMappings;

    // Deferred element state for OutputHandler mode
    private String pendingUri;
    private String pendingLocalName;
    private String pendingQName;
    private boolean inPendingElement;
    
    // Buffered attributes for pending element (keyed by {nsUri}localName for duplicate detection)
    private final Map<String, PendingAttribute> pendingAttributes = new LinkedHashMap<String, PendingAttribute>();
    
    // Buffered namespaces for pending element
    private final List<PendingNamespace> pendingNamespaces = new ArrayList<PendingNamespace>();
    
    // Reusable prefix-to-namespace map (for performance)
    private final Map<String, String> prefixToNamespaceMap = new HashMap<String, String>();
    
    // XSLT 2.0 atomic value spacing state
    private boolean atomicValuePending = false;
    private boolean inAttributeContent = false;
    private boolean contentReceived = false;
    private boolean claimedByResultDocument = false;

    /** Buffered attribute. */
    private static class PendingAttribute {
        final String namespaceURI;
        final String localName;
        final String qName;
        String value;
        
        PendingAttribute(String namespaceURI, String localName, String qName, String value) {
            this.namespaceURI = namespaceURI;
            this.localName = localName;
            this.qName = qName;
            this.value = value;
        }
    }
    
    /** Buffered namespace declaration. */
    private static class PendingNamespace {
        final String prefix;
        final String uri;
        
        PendingNamespace(String prefix, String uri) {
            this.prefix = prefix;
            this.uri = uri;
        }
    }

    /**
     * Creates an output handler writing to a byte channel with default properties.
     *
     * @param channel the output channel
     */
    public XMLWriterOutputHandler(WritableByteChannel channel) {
        this(channel, null);
    }

    /**
     * Creates an output handler writing to a byte channel with specified properties.
     *
     * @param channel the output channel
     * @param properties the output properties, or null for defaults
     */
    public XMLWriterOutputHandler(WritableByteChannel channel, OutputProperties properties) {
        this.outputProperties = properties != null ? properties : new OutputProperties();
        
        this.writer = new XMLWriter(channel);
        
        if (outputProperties.isIndent()) {
            writer.setIndentConfig(new IndentConfig(' ', 2));
        }
        
        String encoding = outputProperties.getEncoding();
        Charset charset = (encoding != null) ? Charset.forName(encoding) : StandardCharsets.UTF_8;
        writer.setCharset(charset);
        
        boolean xml11 = "1.1".equals(outputProperties.getVersion());
        writer.setXml11(xml11);
        
        this.inPendingElement = false;
    }

    /**
     * Creates an output handler writing to an output stream with default properties.
     *
     * @param out the output stream
     */
    public XMLWriterOutputHandler(OutputStream out) {
        this(out, null);
    }

    /**
     * Creates an output handler writing to an output stream with specified properties.
     *
     * @param out the output stream
     * @param properties the output properties, or null for defaults
     */
    public XMLWriterOutputHandler(OutputStream out, OutputProperties properties) {
        this(Channels.newChannel(out), properties);
    }

    /**
     * Sets the character mappings for XSLT 2.0+ character mapping during serialization.
     *
     * @param mappings the character-to-string mappings, or null to disable
     */
    public void setCharacterMappings(Map<Integer, String> mappings) {
        this.characterMappings = mappings;
    }
    
    /**
     * Returns the output properties.
     *
     * @return the output properties
     */
    public OutputProperties getOutputProperties() {
        return outputProperties;
    }

    // ========== OutputHandler Implementation ==========

    @Override
    public void startDocument() throws SAXException {
        try {
            if (!outputProperties.isOmitXmlDeclaration() && 
                outputProperties.getMethod() == OutputProperties.Method.XML) {
                String encoding = outputProperties.getEncoding();
                if (encoding == null) {
                    encoding = "UTF-8";
                }
                String standaloneAttr = outputProperties.isStandalone() ? " standalone=\"yes\"" : "";
                writer.writeProcessingInstruction("xml", 
                    "version=\"" + outputProperties.getVersion() + "\" encoding=\"" + encoding + "\"" + standaloneAttr);
            }
        } catch (IOException e) {
            throw new SAXException("Error writing XML declaration", e);
        }
    }

    @Override
    public void endDocument() throws SAXException {
        flushPendingElement();
        try {
            writer.close();
        } catch (IOException e) {
            throw new SAXException("Error closing output", e);
        }
    }

    @Override
    public void startElement(String namespaceURI, String localName, String qName) throws SAXException {
        if (claimedByResultDocument) {
            throw new SAXException("XTDE1490: Cannot write to the principal output URI " +
                "because it has been claimed by xsl:result-document");
        }
        contentReceived = true;
        flushPendingElement();
        
        pendingUri = namespaceURI;
        pendingLocalName = localName;
        pendingQName = qName;
        inPendingElement = true;
        pendingAttributes.clear();
        pendingNamespaces.clear();
    }

    @Override
    public void endElement(String namespaceURI, String localName, String qName) throws SAXException {
        if (inPendingElement) {
            writePendingStartElement();
        }
        try {
            writer.writeEndElement();
        } catch (IOException e) {
            throw new SAXException("Error writing end element", e);
        }
    }

    @Override
    public void attribute(String namespaceURI, String localName, String qName, String value) 
            throws SAXException {
        if (!inPendingElement) {
            throw new SAXException("attribute() called outside of element start");
        }
        
        String key = (namespaceURI != null && !namespaceURI.isEmpty()) 
            ? "{" + namespaceURI + "}" + localName 
            : localName;
        
        PendingAttribute existing = pendingAttributes.get(key);
        if (existing != null) {
            existing.value = value;
        } else {
            pendingAttributes.put(key, new PendingAttribute(namespaceURI, localName, qName, value));
        }
    }

    @Override
    public void namespace(String prefix, String uri) throws SAXException {
        if (!inPendingElement) {
            throw new SAXException("namespace() called outside of element start");
        }
        
        if (OutputHandlerUtils.isXmlPrefix(prefix)) {
            return;
        }
        
        pendingNamespaces.add(new PendingNamespace(prefix, uri));
    }

    @Override
    public void characters(String text) throws SAXException {
        if (claimedByResultDocument) {
            throw new SAXException("XTDE1490: Cannot write to the principal output URI " +
                "because it has been claimed by xsl:result-document");
        }
        contentReceived = true;
        flushPendingElement();
        
        try {
            if (characterMappings == null || characterMappings.isEmpty()) {
                writer.writeCharacters(text);
            } else {
                writeCharactersWithMapping(text);
            }
        } catch (IOException e) {
            throw new SAXException("Error writing characters", e);
        }
    }
    
    /**
     * Writes characters with character mapping applied.
     * Characters with mappings are written raw (unescaped), others are escaped.
     */
    private void writeCharactersWithMapping(String text) throws IOException {
        StringBuilder normalChars = null;
        
        int i = 0;
        while (i < text.length()) {
            int codePoint = text.codePointAt(i);
            String replacement = characterMappings.get(codePoint);
            
            if (replacement != null) {
                if (normalChars != null && normalChars.length() > 0) {
                    writer.writeCharacters(normalChars.toString());
                    normalChars.setLength(0);
                }
                writer.writeRaw(replacement);
            } else {
                if (normalChars == null) {
                    normalChars = new StringBuilder();
                }
                normalChars.appendCodePoint(codePoint);
            }
            i += Character.charCount(codePoint);
        }
        
        if (normalChars != null && normalChars.length() > 0) {
            writer.writeCharacters(normalChars.toString());
        }
    }

    @Override
    public void charactersRaw(String text) throws SAXException {
        flushPendingElement();
        try {
            writer.writeRaw(text);
        } catch (IOException e) {
            throw new SAXException("Error writing raw characters", e);
        }
    }

    @Override
    public void comment(String text) throws SAXException {
        flushPendingElement();
        try {
            writer.writeComment(text);
        } catch (IOException e) {
            throw new SAXException("Error writing comment", e);
        }
    }

    @Override
    public void processingInstruction(String target, String data) throws SAXException {
        flushPendingElement();
        try {
            writer.writeProcessingInstruction(target, data);
        } catch (IOException e) {
            throw new SAXException("Error writing processing instruction", e);
        }
    }

    @Override
    public void flush() throws SAXException {
        flushPendingElement();
        try {
            writer.flush();
        } catch (IOException e) {
            throw new SAXException("Error flushing output", e);
        }
    }

    // ========== Private Helpers ==========

    /**
     * Writes the pending start element if one exists.
     */
    private void flushPendingElement() throws SAXException {
        if (inPendingElement) {
            writePendingStartElement();
            inPendingElement = false;
        }
    }

    /**
     * Writes the pending start element tag with all buffered attributes and namespaces.
     * Performs namespace fixup if there are prefix conflicts.
     */
    private void writePendingStartElement() throws SAXException {
        if (!inPendingElement) {
            return;
        }
        
        try {
            String elementPrefix = OutputHandlerUtils.extractPrefix(pendingQName);
            String actualElementPrefix = elementPrefix;
            String actualQName = pendingQName;
            
            // Namespace fixup: check for conflicts between element prefix and xsl:namespace declarations
            PendingNamespace conflictingNs = null;
            if (elementPrefix != null && !elementPrefix.isEmpty() && !pendingUri.isEmpty()) {
                for (int i = 0; i < pendingNamespaces.size(); i++) {
                    PendingNamespace ns = pendingNamespaces.get(i);
                    if (elementPrefix.equals(ns.prefix) && !pendingUri.equals(ns.uri)) {
                        conflictingNs = ns;
                        break;
                    }
                }
                
                if (conflictingNs != null) {
                    actualElementPrefix = generateUniquePrefix(elementPrefix);
                    actualQName = actualElementPrefix + ":" + pendingLocalName;
                    
                    Iterator<PendingNamespace> it = pendingNamespaces.iterator();
                    while (it.hasNext()) {
                        PendingNamespace ns = it.next();
                        if (elementPrefix.equals(ns.prefix) && pendingUri.equals(ns.uri)) {
                            it.remove();
                        }
                    }
                    
                    pendingNamespaces.add(new PendingNamespace(actualElementPrefix, pendingUri));
                }
            }
            
            // Open the start element
            String prefix = OutputHandlerUtils.extractPrefix(actualQName);
            String effectiveUri = (pendingUri != null) ? pendingUri : "";
            if (prefix != null && !prefix.isEmpty()) {
                writer.writeStartElement(prefix, pendingLocalName, effectiveUri);
            } else if (!effectiveUri.isEmpty()) {
                writer.writeStartElement("", pendingLocalName, effectiveUri);
            } else {
                writer.writeStartElement(pendingLocalName);
            }
            
            // Write namespace declarations
            prefixToNamespaceMap.clear();
            for (int i = 0; i < pendingNamespaces.size(); i++) {
                PendingNamespace ns = pendingNamespaces.get(i);
                String nsPrefix = (ns.prefix != null) ? ns.prefix : "";
                if (nsPrefix.isEmpty()) {
                    writer.writeDefaultNamespace(ns.uri);
                } else {
                    writer.writeNamespace(nsPrefix, ns.uri);
                }
                if (!nsPrefix.isEmpty()) {
                    prefixToNamespaceMap.put(nsPrefix, ns.uri);
                }
            }
            pendingNamespaces.clear();
            
            // Write attributes
            for (PendingAttribute attr : pendingAttributes.values()) {
                String attrPrefix = OutputHandlerUtils.extractPrefix(attr.qName);
                String actualAttrPrefix = attrPrefix;
                
                if (attrPrefix != null && attrPrefix.equals(elementPrefix) && !actualElementPrefix.equals(elementPrefix)) {
                    actualAttrPrefix = actualElementPrefix;
                }
                
                if (actualAttrPrefix != null && !actualAttrPrefix.isEmpty() && 
                    !OutputHandlerUtils.isXmlPrefix(actualAttrPrefix) &&
                    attr.namespaceURI != null && !attr.namespaceURI.isEmpty()) {
                    String existingUri = prefixToNamespaceMap.get(actualAttrPrefix);
                    if (existingUri == null) {
                        writer.writeNamespace(actualAttrPrefix, attr.namespaceURI);
                        prefixToNamespaceMap.put(actualAttrPrefix, attr.namespaceURI);
                    } else if (!existingUri.equals(attr.namespaceURI)) {
                        actualAttrPrefix = generateUniquePrefix(actualAttrPrefix);
                        writer.writeNamespace(actualAttrPrefix, attr.namespaceURI);
                        prefixToNamespaceMap.put(actualAttrPrefix, attr.namespaceURI);
                    }
                }
                
                if (actualAttrPrefix != null && !actualAttrPrefix.isEmpty()) {
                    String nsUri = (attr.namespaceURI != null) ? attr.namespaceURI : "";
                    writer.writeAttribute(actualAttrPrefix, attr.localName, nsUri, attr.value);
                } else {
                    writer.writeAttribute(attr.localName, attr.value);
                }
            }
            pendingAttributes.clear();
            
            inPendingElement = false;
        } catch (IOException e) {
            throw new SAXException("Error writing start element", e);
        }
    }
    
    /**
     * Generates a unique prefix by appending a number suffix.
     */
    private int prefixCounter = 0;
    
    private String generateUniquePrefix(String basePrefix) {
        return basePrefix + "_" + (prefixCounter++);
    }
    
    @Override
    public boolean isAtomicValuePending() {
        return atomicValuePending;
    }
    
    @Override
    public void setAtomicValuePending(boolean pending) {
        this.atomicValuePending = pending;
    }
    
    @Override
    public boolean isInAttributeContent() {
        return inAttributeContent;
    }
    
    @Override
    public void setInAttributeContent(boolean inAttributeContent) {
        this.inAttributeContent = inAttributeContent;
    }
    
    @Override
    public void atomicValue(org.bluezoo.gonzalez.transform.xpath.type.XPathValue value) 
            throws org.xml.sax.SAXException {
        if (value != null) {
            if (atomicValuePending && !inAttributeContent) {
                characters(" ");
            }
            characters(value.asString());
            atomicValuePending = true;
        }
    }

    @Override
    public boolean hasReceivedContent() {
        return contentReceived;
    }

    @Override
    public void markClaimedByResultDocument() {
        claimedByResultDocument = true;
    }

    @Override
    public boolean isClaimedByResultDocument() {
        return claimedByResultDocument;
    }
}

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
import org.bluezoo.gonzalez.transform.xpath.type.XPathArray;
import org.bluezoo.gonzalez.transform.xpath.type.XPathBoolean;
import org.bluezoo.gonzalez.transform.xpath.type.XPathMap;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNodeSet;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNumber;
import org.bluezoo.gonzalez.transform.xpath.type.XPathSequence;
import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;
import org.bluezoo.json.JSONWriter;
import org.xml.sax.SAXException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    // When true, raise SENR0001 if a map is serialized (xml/html/xhtml/text methods)
    private boolean strictXmlSerialization = true;

    // inherit-namespaces="no" tracking
    private boolean pendingInheritCapture = false;
    private List<String> inheritUndeclarePrefixes = null;
    private int inheritDepth = -1;
    private int elementDepth = 0;

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
        final boolean elementPrefix;
        
        PendingNamespace(String prefix, String uri) {
            this(prefix, uri, false);
        }
        
        PendingNamespace(String prefix, String uri, boolean elementPrefix) {
            this.prefix = prefix;
            this.uri = uri;
            this.elementPrefix = elementPrefix;
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
        Charset charset = StandardCharsets.UTF_8;
        if (encoding != null) {
            try {
                charset = Charset.forName(encoding);
            } catch (java.nio.charset.UnsupportedCharsetException e) {
                // SESU0007: fall back to UTF-8 for unsupported encodings
                charset = StandardCharsets.UTF_8;
                outputProperties.setEncoding("UTF-8");
            }
        }
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
     * Sets whether to raise SENR0001 when a map is encountered during serialization.
     * Should be true for xml/html/xhtml/text output methods, false for adaptive/json.
     *
     * @param strict true to raise SENR0001 for maps
     */
    public void setStrictXmlSerialization(boolean strict) {
        this.strictXmlSerialization = strict;
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
        elementDepth++;
        pendingAttributes.clear();
        pendingNamespaces.clear();
        if (inheritUndeclarePrefixes != null) {
            inheritDepth++;
        }
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
        elementDepth--;
        if (inheritUndeclarePrefixes != null) {
            inheritDepth--;
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
        addPendingNamespace(prefix, uri, false);
    }

    @Override
    public void elementPrefixNamespace(String prefix, String uri) throws SAXException {
        addPendingNamespace(prefix, uri, true);
    }

    private void addPendingNamespace(String prefix, String uri, boolean elementPrefix)
            throws SAXException {
        if (!inPendingElement) {
            throw new SAXException("namespace() called outside of element start");
        }
        
        if (OutputHandlerUtils.isXmlPrefix(prefix)) {
            return;
        }
        
        String newPrefix = prefix != null ? prefix : "";
        String newUri = uri != null ? uri : "";
        for (int i = 0; i < pendingNamespaces.size(); i++) {
            PendingNamespace existing = pendingNamespaces.get(i);
            String existingPrefix = existing.prefix != null ? existing.prefix : "";
            if (existingPrefix.equals(newPrefix)) {
                String existingUri = existing.uri != null ? existing.uri : "";
                if (existingUri.equals(newUri)) {
                    return;
                }
            }
        }
        
        pendingNamespaces.add(new PendingNamespace(prefix, uri, elementPrefix));
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

    @Override
    public void setInheritNamespaces(boolean inherit) throws SAXException {
        if (!inherit) {
            pendingInheritCapture = true;
        } else {
            pendingInheritCapture = false;
            inheritUndeclarePrefixes = null;
            inheritDepth = -1;
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
            
            // XTDE0430: check for duplicate prefixes among explicit namespace nodes
            for (int i = 0; i < pendingNamespaces.size(); i++) {
                PendingNamespace ns1 = pendingNamespaces.get(i);
                if (ns1.elementPrefix) {
                    continue;
                }
                String p1 = ns1.prefix != null ? ns1.prefix : "";
                for (int j = i + 1; j < pendingNamespaces.size(); j++) {
                    PendingNamespace ns2 = pendingNamespaces.get(j);
                    if (ns2.elementPrefix) {
                        continue;
                    }
                    String p2 = ns2.prefix != null ? ns2.prefix : "";
                    if (p1.equals(p2)) {
                        String u1 = ns1.uri != null ? ns1.uri : "";
                        String u2 = ns2.uri != null ? ns2.uri : "";
                        if (!u1.equals(u2)) {
                            throw new SAXException("XTDE0430: Two namespace nodes with prefix '"
                                + p1 + "' have different URIs: '" + u1 + "' and '" + u2 + "'");
                        }
                    }
                }
            }

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
            
            // Collect child's declared prefixes before writing (for inherit-namespaces)
            Set<String> childDeclaredPrefixes = null;
            if (inheritUndeclarePrefixes != null && inheritDepth == 1) {
                childDeclaredPrefixes = new HashSet<String>();
                for (int i = 0; i < pendingNamespaces.size(); i++) {
                    PendingNamespace ns = pendingNamespaces.get(i);
                    String nsPrefix = (ns.prefix != null) ? ns.prefix : "";
                    childDeclaredPrefixes.add(nsPrefix);
                }
            }

            // Write namespace declarations (explicit first, then element prefix with fixup)
            prefixToNamespaceMap.clear();
            for (int i = 0; i < pendingNamespaces.size(); i++) {
                PendingNamespace ns = pendingNamespaces.get(i);
                if (ns.elementPrefix) {
                    continue;
                }
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
            for (int i = 0; i < pendingNamespaces.size(); i++) {
                PendingNamespace ns = pendingNamespaces.get(i);
                if (!ns.elementPrefix) {
                    continue;
                }
                String nsPrefix = (ns.prefix != null) ? ns.prefix : "";
                String existingUri = prefixToNamespaceMap.get(nsPrefix);
                if (existingUri != null && existingUri.equals(ns.uri)) {
                    continue;
                }
                if (existingUri != null) {
                    nsPrefix = generateUniquePrefix(nsPrefix);
                }
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

            // Capture parent namespaces for inherit-namespaces="no"
            if (pendingInheritCapture) {
                inheritUndeclarePrefixes = new ArrayList<String>();
                Map<String, String> allBindings = writer.getAllNamespaceBindings();
                for (Map.Entry<String, String> entry : allBindings.entrySet()) {
                    String bindPrefix = entry.getKey();
                    String bindUri = entry.getValue();
                    if (!"xml".equals(bindPrefix) && bindUri != null && !bindUri.isEmpty()) {
                        inheritUndeclarePrefixes.add(bindPrefix);
                    }
                }
                inheritDepth = 0;
                pendingInheritCapture = false;
            }

            // Add undeclarations for inherit-namespaces="no" on direct children
            if (childDeclaredPrefixes != null) {
                for (int i = 0; i < inheritUndeclarePrefixes.size(); i++) {
                    String undeclPrefix = inheritUndeclarePrefixes.get(i);
                    if (!childDeclaredPrefixes.contains(undeclPrefix)) {
                        if (undeclPrefix.isEmpty()) {
                            writer.writeDefaultNamespace("");
                        } else {
                            writer.writeNamespace(undeclPrefix, "");
                        }
                    }
                }
            }
            
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
    public void atomicValue(XPathValue value) throws SAXException {
        if (value == null) {
            return;
        }
        if (value instanceof XPathMap) {
            if (strictXmlSerialization) {
                if (elementDepth > 0) {
                    throw new SAXException("XTDE0450: " +
                        "A result tree cannot contain a function item");
                }
                if (!inAttributeContent) {
                    throw new SAXException(
                        "SENR0001: Cannot serialize a map using this output method");
                }
            }
        }
        if (!strictXmlSerialization && (value instanceof XPathMap || value instanceof XPathArray)) {
            String json = serializeToJson(value);
            if (atomicValuePending && !inAttributeContent) {
                characters(" ");
            }
            characters(json);
            atomicValuePending = true;
            return;
        }
        if (atomicValuePending && !inAttributeContent) {
            characters(" ");
        }
        characters(value.asString());
        atomicValuePending = true;
    }

    /**
     * Serializes an XPath value as JSON using the org.bluezoo.json framework.
     */
    private String serializeToJson(XPathValue value) throws SAXException {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            JSONWriter writer = new JSONWriter(baos);
            writeJsonValue(value, writer);
            writer.close();
            return baos.toString("UTF-8");
        } catch (IOException e) {
            throw new SAXException("Error serializing JSON: " + e.getMessage(), e);
        }
    }

    private void writeJsonValue(XPathValue value, JSONWriter writer) throws IOException {
        if (value instanceof XPathMap) {
            XPathMap map = (XPathMap) value;
            writer.writeStartObject();
            for (Map.Entry<String, XPathValue> entry : map.entries()) {
                writer.writeKey(entry.getKey());
                writeJsonValue(entry.getValue(), writer);
            }
            writer.writeEndObject();
        } else if (value instanceof XPathArray) {
            XPathArray array = (XPathArray) value;
            writer.writeStartArray();
            for (XPathValue member : array.members()) {
                writeJsonValue(member, writer);
            }
            writer.writeEndArray();
        } else if (value instanceof XPathBoolean) {
            writer.writeBoolean(value.asBoolean());
        } else if (value instanceof XPathNumber) {
            double d = value.asNumber();
            long longVal = (long) d;
            if (d == longVal && !Double.isInfinite(d)) {
                writer.writeNumber(longVal);
            } else {
                writer.writeNumber(d);
            }
        } else if (value instanceof XPathNodeSet) {
            XPathNodeSet ns = (XPathNodeSet) value;
            if (ns.isEmpty()) {
                writer.writeNull();
            } else {
                writer.writeString(value.asString());
            }
        } else if (value instanceof XPathSequence) {
            XPathSequence seq = (XPathSequence) value;
            writer.writeStartArray();
            for (XPathValue item : seq) {
                writeJsonValue(item, writer);
            }
            writer.writeEndArray();
        } else {
            writer.writeString(value.asString());
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

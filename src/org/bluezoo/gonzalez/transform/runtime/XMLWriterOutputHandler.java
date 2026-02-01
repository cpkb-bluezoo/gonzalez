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
import org.xml.sax.Attributes;
import org.xml.sax.ContentHandler;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Output handler adapter that uses {@link XMLWriter} for streaming XML output.
 *
 * <p>This class implements both {@link OutputHandler} (for XSLT output) and
 * {@link ContentHandler} (for SAX event sink), allowing it to serve as the
 * bridge between XSLT transformations and Gonzalez's streaming XML writer.
 *
 * <p>As an OutputHandler, it supports deferred element output (attributes can
 * be added after startElement), which is required for XSLT's attribute and
 * namespace output order independence.
 *
 * <p>As a ContentHandler, it can receive SAX events from any SAX source and
 * serialize them to a byte channel or output stream, making it suitable for
 * JAXP StreamResult output.
 *
 * <h2>Example Usage</h2>
 * <pre>{@code
 * // As XSLT OutputHandler
 * XMLWriterOutputHandler handler = new XMLWriterOutputHandler(channel, properties);
 * handler.startDocument();
 * handler.startElement("", "root", "root");
 * handler.attribute("", "id", "id", "1");
 * handler.characters("Hello");
 * handler.endElement("", "root", "root");
 * handler.endDocument();
 *
 * // As SAX ContentHandler (sink)
 * XMLWriterOutputHandler handler = new XMLWriterOutputHandler(outputStream);
 * XMLReader reader = XMLReaderFactory.createXMLReader();
 * reader.setContentHandler(handler);
 * reader.parse(inputSource);
 * }</pre>
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class XMLWriterOutputHandler implements OutputHandler, ContentHandler {

    private final XMLWriter writer;
    private final OutputProperties outputProperties;

    // Deferred element state for OutputHandler mode
    private String pendingUri;
    private String pendingLocalName;
    private String pendingQName;
    private boolean inPendingElement;
    
    // Buffered attributes for pending element (keyed by {nsUri}localName for duplicate detection)
    private final Map<String, PendingAttribute> pendingAttributes = new LinkedHashMap<String, PendingAttribute>();
    
    // Buffered namespaces for pending element
    private final List<PendingNamespace> pendingNamespaces = new ArrayList<PendingNamespace>();

    // Buffered namespace prefixes from startPrefixMapping (for ContentHandler mode)
    private final Deque<PrefixMapping> pendingPrefixes = new ArrayDeque<>();
    
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
        
        IndentConfig indentConfig = null;
        if (outputProperties.isIndent()) {
            indentConfig = new IndentConfig(' ', 2);
        }
        
        // Use the specified encoding, defaulting to UTF-8
        String encoding = outputProperties.getEncoding();
        Charset charset = (encoding != null) ? Charset.forName(encoding) : StandardCharsets.UTF_8;
        
        // Check for XML 1.1 output mode (for proper control character escaping)
        boolean xml11 = "1.1".equals(outputProperties.getVersion());
        
        this.writer = new XMLWriter(channel, 4096, indentConfig, charset, xml11);
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
        // XML declaration is handled by XMLWriter if needed
        if (!outputProperties.isOmitXmlDeclaration() && 
            outputProperties.getMethod() == OutputProperties.Method.XML) {
            try {
                String encoding = outputProperties.getEncoding();
                if (encoding == null) {
                    encoding = "UTF-8";
                }
                String standalone = outputProperties.isStandalone() ? " standalone=\"yes\"" : "";
                writer.writeProcessingInstruction("xml", 
                    "version=\"" + outputProperties.getVersion() + "\" encoding=\"" + encoding + "\"" + standalone);
            } catch (IOException e) {
                throw new SAXException("Error writing XML declaration", e);
            }
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
        flushPendingElement();
        
        // Store element info for deferred output
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
            // Empty element - write start then let XMLWriter handle self-closing
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
        
        // Buffer attribute, using key to detect and merge duplicates
        // Key is {nsUri}localName to handle namespaced attributes correctly
        String key = (namespaceURI != null && !namespaceURI.isEmpty()) 
            ? "{" + namespaceURI + "}" + localName 
            : localName;
        
        PendingAttribute existing = pendingAttributes.get(key);
        if (existing != null) {
            // Update existing attribute value (later values override earlier)
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
        
        // Buffer namespace declaration
        pendingNamespaces.add(new PendingNamespace(prefix, uri));
    }

    @Override
    public void characters(String text) throws SAXException {
        flushPendingElement();
        
        try {
            writer.writeCharacters(text);
        } catch (IOException e) {
            throw new SAXException("Error writing characters", e);
        }
    }

    @Override
    public void charactersRaw(String text) throws SAXException {
        // For disable-output-escaping, write without XML escaping
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

    // ========== ContentHandler Implementation ==========

    @Override
    public void setDocumentLocator(Locator locator) {
        // Locator not needed for serialization
    }

    @Override
    public void startPrefixMapping(String prefix, String uri) throws SAXException {
        // Buffer prefix mappings for the next startElement
        pendingPrefixes.add(new PrefixMapping(prefix, uri));
    }

    @Override
    public void endPrefixMapping(String prefix) throws SAXException {
        // Namespace scope is handled automatically by XMLWriter
    }

    @Override
    public void startElement(String uri, String localName, String qName, Attributes atts) 
            throws SAXException {
        flushPendingElement();
        
        try {
            // Write the start element
            String prefix = extractPrefix(qName);
            writer.writeStartElement(prefix, localName, uri);
            
            // Write any buffered namespace declarations
            while (!pendingPrefixes.isEmpty()) {
                PrefixMapping pm = pendingPrefixes.poll();
                if (pm.prefix == null || pm.prefix.isEmpty()) {
                    writer.writeDefaultNamespace(pm.uri);
                } else {
                    writer.writeNamespace(pm.prefix, pm.uri);
                }
            }
            
            // Write attributes
            for (int i = 0; i < atts.getLength(); i++) {
                String attrPrefix = extractPrefix(atts.getQName(i));
                if (attrPrefix != null && !attrPrefix.isEmpty()) {
                    writer.writeAttribute(attrPrefix, atts.getURI(i), atts.getLocalName(i), atts.getValue(i));
                } else {
                    writer.writeAttribute(atts.getLocalName(i), atts.getValue(i));
                }
            }
        } catch (IOException e) {
            throw new SAXException("Error writing start element", e);
        }
    }

    // Note: endElement(String, String, String) is shared between ContentHandler and OutputHandler
    // The implementation in the OutputHandler section handles both cases.

    @Override
    public void characters(char[] ch, int start, int length) throws SAXException {
        flushPendingElement();
        
        try {
            writer.writeCharacters(ch, start, length);
        } catch (IOException e) {
            throw new SAXException("Error writing characters", e);
        }
    }

    @Override
    public void ignorableWhitespace(char[] ch, int start, int length) throws SAXException {
        // Write as regular characters (or could be discarded based on config)
        characters(ch, start, length);
    }

    // Note: processingInstruction(String, String) is shared between ContentHandler and OutputHandler
    // The implementation in the OutputHandler section handles both cases.

    @Override
    public void skippedEntity(String name) throws SAXException {
        flushPendingElement();
        
        try {
            writer.writeEntityRef(name);
        } catch (IOException e) {
            throw new SAXException("Error writing entity reference", e);
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
            String elementPrefix = extractPrefix(pendingQName);
            String actualElementPrefix = elementPrefix;
            String actualQName = pendingQName;
            
            // Namespace fixup: check for conflicts between element prefix and xsl:namespace declarations
            // A conflict occurs when a namespace declaration uses the same prefix but different URI
            PendingNamespace conflictingNs = null;
            if (elementPrefix != null && !elementPrefix.isEmpty() && !pendingUri.isEmpty()) {
                for (PendingNamespace ns : pendingNamespaces) {
                    if (elementPrefix.equals(ns.prefix) && !pendingUri.equals(ns.uri)) {
                        conflictingNs = ns;
                        break;
                    }
                }
                
                if (conflictingNs != null) {
                    // Conflict! Generate a new prefix for the element
                    actualElementPrefix = generateUniquePrefix(elementPrefix);
                    actualQName = actualElementPrefix + ":" + pendingLocalName;
                    
                    // Remove any existing declaration for the element's namespace with the old prefix
                    // (this is the declaration that was output by the literal result element)
                    pendingNamespaces.removeIf(ns -> 
                        elementPrefix.equals(ns.prefix) && pendingUri.equals(ns.uri));
                    
                    // Add namespace declaration for the element's namespace with new prefix
                    pendingNamespaces.add(new PendingNamespace(actualElementPrefix, pendingUri));
                }
            }
            
            writer.writeStartElement(actualElementPrefix, pendingLocalName, pendingUri);
            
            // Track prefix-to-namespace mappings (built BEFORE clearing pendingNamespaces)
            Map<String, String> prefixToNamespace = new HashMap<>();
            
            // Write buffered namespace declarations and track them
            for (PendingNamespace ns : pendingNamespaces) {
                if (ns.prefix == null || ns.prefix.isEmpty()) {
                    writer.writeDefaultNamespace(ns.uri);
                } else {
                    writer.writeNamespace(ns.prefix, ns.uri);
                    prefixToNamespace.put(ns.prefix, ns.uri);
                }
            }
            pendingNamespaces.clear();
            
            // Write buffered attributes (already deduplicated), applying fixup to attribute prefixes
            for (PendingAttribute attr : pendingAttributes.values()) {
                String attrPrefix = extractPrefix(attr.qName);
                String actualAttrPrefix = attrPrefix;
                String actualAttrQName = attr.qName;
                
                // If the attribute used the same prefix as the element and we renamed it, update the attribute
                if (attrPrefix != null && attrPrefix.equals(elementPrefix) && !actualElementPrefix.equals(elementPrefix)) {
                    actualAttrPrefix = actualElementPrefix;
                    actualAttrQName = actualAttrPrefix + ":" + attr.localName;
                }
                
                // Handle namespaced attributes
                if (actualAttrPrefix != null && !actualAttrPrefix.isEmpty() && 
                    attr.namespaceURI != null && !attr.namespaceURI.isEmpty()) {
                    String existingUri = prefixToNamespace.get(actualAttrPrefix);
                    if (existingUri == null) {
                        // Prefix not declared - add namespace declaration
                        writer.writeNamespace(actualAttrPrefix, attr.namespaceURI);
                        prefixToNamespace.put(actualAttrPrefix, attr.namespaceURI);
                    } else if (!existingUri.equals(attr.namespaceURI)) {
                        // Prefix conflict! Generate a new unique prefix
                        actualAttrPrefix = generateUniquePrefix(actualAttrPrefix);
                        actualAttrQName = actualAttrPrefix + ":" + attr.localName;
                        // Add namespace declaration for the new prefix
                        writer.writeNamespace(actualAttrPrefix, attr.namespaceURI);
                        prefixToNamespace.put(actualAttrPrefix, attr.namespaceURI);
                    }
                }
                
                if (actualAttrPrefix != null && !actualAttrPrefix.isEmpty()) {
                    writer.writeAttribute(actualAttrPrefix, attr.namespaceURI, attr.localName, attr.value);
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
     * Uses a simple counter to ensure uniqueness within the current element.
     */
    private int prefixCounter = 0;
    
    private String generateUniquePrefix(String basePrefix) {
        // Generate prefix like "p_0", "p_1", etc.
        return basePrefix + "_" + (prefixCounter++);
    }

    /**
     * Extracts the prefix from a qualified name.
     *
     * @param qName the qualified name
     * @return the prefix, or null if no prefix
     */
    private static String extractPrefix(String qName) {
        if (qName == null) {
            return null;
        }
        int colon = qName.indexOf(':');
        if (colon > 0) {
            return qName.substring(0, colon);
        }
        return null;
    }

    /**
     * Holds a prefix-to-URI mapping from startPrefixMapping.
     */
    private static final class PrefixMapping {
        final String prefix;
        final String uri;

        PrefixMapping(String prefix, String uri) {
            this.prefix = prefix;
            this.uri = uri;
        }
    }
}

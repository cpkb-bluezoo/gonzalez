/*
 * BufferOutputHandler.java
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

import org.xml.sax.SAXException;
import org.xml.sax.helpers.AttributesImpl;
import java.util.ArrayList;
import java.util.List;

/**
 * An OutputHandler that buffers all output to a SAXEventBuffer.
 *
 * <p>This is useful for capturing the result of executing XSLT nodes
 * (such as user-defined function bodies or variable content) into
 * a Result Tree Fragment (RTF).
 *
 * <p>This handler properly reorders namespace declarations and attributes
 * to comply with SAX event ordering (startPrefixMapping before startElement,
 * attributes included in startElement).
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public class BufferOutputHandler implements OutputHandler {
    
    private final SAXEventBuffer buffer;
    
    // Pending namespace declarations for current element
    private List<String[]> pendingNamespaces = new ArrayList<>();
    
    // Pending attributes for current element
    private AttributesImpl pendingAttributes = new AttributesImpl();
    
    // Pending element info
    private String pendingUri;
    private String pendingLocalName;
    private String pendingQName;
    private boolean hasPendingElement = false;
    
    // Pending type annotation for element
    private String pendingTypeNamespaceURI;
    private String pendingTypeLocalName;
    
    // Pending attribute type annotations (stored by index)
    private List<String[]> pendingAttributeTypes = new ArrayList<>();
    
    /**
     * Creates a new buffer output handler.
     *
     * @param buffer the buffer to write to
     */
    public BufferOutputHandler(SAXEventBuffer buffer) {
        this.buffer = buffer;
    }
    
    /**
     * Returns the underlying buffer.
     *
     * @return the SAX event buffer
     */
    public SAXEventBuffer getBuffer() {
        return buffer;
    }
    
    @Override
    public void startDocument() throws SAXException {
        buffer.startDocument();
    }
    
    @Override
    public void endDocument() throws SAXException {
        flushPendingElement();
        buffer.endDocument();
    }
    
    @Override
    public void startElement(String namespaceURI, String localName, String qName) 
            throws SAXException {
        // Flush any previous pending element first
        flushPendingElement();
        
        // Store this element as pending (we'll emit it when we get content or endElement)
        pendingUri = namespaceURI;
        pendingLocalName = localName;
        pendingQName = qName;
        hasPendingElement = true;
        pendingNamespaces.clear();
        pendingAttributes.clear();
    }
    
    @Override
    public void endElement(String namespaceURI, String localName, String qName) 
            throws SAXException {
        flushPendingElement();
        buffer.endElement(namespaceURI, localName, qName);
        
        // Emit endPrefixMapping for namespaces we declared on the matching start element
        // Note: This is simplified; proper implementation would track nesting
    }
    
    @Override
    public void attribute(String namespaceURI, String localName, String qName, String value) 
            throws SAXException {
        if (hasPendingElement) {
            // Add to pending attributes
            pendingAttributes.addAttribute(
                namespaceURI != null ? namespaceURI : "", 
                localName, 
                qName, 
                "CDATA", 
                value);
        }
    }
    
    @Override
    public void namespace(String prefix, String uri) throws SAXException {
        if (hasPendingElement) {
            // Buffer namespace for emission before startElement
            pendingNamespaces.add(new String[]{prefix, uri});
        } else {
            // If no pending element, emit immediately
            buffer.startPrefixMapping(prefix, uri);
        }
    }
    
    @Override
    public void characters(String text) throws SAXException {
        flushPendingElement();
        if (text != null && !text.isEmpty()) {
            buffer.characters(text.toCharArray(), 0, text.length());
        }
    }
    
    @Override
    public void charactersRaw(String text) throws SAXException {
        // Buffer treats raw the same as escaped for RTF purposes
        flushPendingElement();
        if (text != null && !text.isEmpty()) {
            buffer.characters(text.toCharArray(), 0, text.length());
        }
    }
    
    @Override
    public void comment(String text) throws SAXException {
        flushPendingElement();
        // SAXEventBuffer doesn't support comments directly
        // Comments in RTF are typically not preserved
    }
    
    @Override
    public void processingInstruction(String target, String data) throws SAXException {
        flushPendingElement();
        buffer.processingInstruction(target, data);
    }
    
    @Override
    public void flush() throws SAXException {
        flushPendingElement();
    }
    
    @Override
    public void setElementType(String namespaceURI, String localName) throws SAXException {
        if (hasPendingElement) {
            pendingTypeNamespaceURI = namespaceURI;
            pendingTypeLocalName = localName;
        }
    }
    
    @Override
    public void setAttributeType(String namespaceURI, String localName) throws SAXException {
        if (hasPendingElement && pendingAttributes.getLength() > 0) {
            // Store type for the most recently added attribute
            int attrIndex = pendingAttributes.getLength() - 1;
            // Expand list if needed
            while (pendingAttributeTypes.size() <= attrIndex) {
                pendingAttributeTypes.add(null);
            }
            pendingAttributeTypes.set(attrIndex, new String[]{namespaceURI, localName});
        }
    }
    
    /**
     * Flushes any pending element, emitting namespace declarations first (SAX order).
     * Performs namespace fixup if there are prefix conflicts.
     */
    private void flushPendingElement() throws SAXException {
        if (!hasPendingElement) {
            return;
        }
        
        String elementPrefix = extractPrefix(pendingQName);
        String actualElementPrefix = elementPrefix;
        String actualQName = pendingQName;
        
        // Namespace fixup: check for conflicts between element prefix and xsl:namespace declarations
        // A conflict occurs when a namespace declaration uses the same prefix but different URI
        if (elementPrefix != null && !elementPrefix.isEmpty() && pendingUri != null && !pendingUri.isEmpty()) {
            for (String[] ns : pendingNamespaces) {
                if (elementPrefix.equals(ns[0]) && !pendingUri.equals(ns[1])) {
                    // Conflict! Generate a new prefix for the element
                    actualElementPrefix = generateUniquePrefix(elementPrefix);
                    actualQName = actualElementPrefix + ":" + pendingLocalName;
                    
                    // Remove any existing declaration for the element's namespace with the old prefix
                    pendingNamespaces.removeIf(n -> 
                        elementPrefix.equals(n[0]) && pendingUri.equals(n[1]));
                    
                    // Add namespace declaration for the element's namespace with new prefix
                    pendingNamespaces.add(new String[]{actualElementPrefix, pendingUri});
                    
                    // Also update any attributes that used the old prefix
                    updateAttributePrefixes(elementPrefix, actualElementPrefix);
                    break;
                }
            }
        }
        
        // Emit namespace declarations BEFORE startElement (SAX ordering)
        for (String[] ns : pendingNamespaces) {
            buffer.startPrefixMapping(ns[0], ns[1]);
        }
        
        // Emit startElement with attributes and type annotations (using potentially updated qName)
        buffer.startElementWithTypes(pendingUri, pendingLocalName, actualQName, 
            pendingAttributes, pendingTypeNamespaceURI, pendingTypeLocalName,
            pendingAttributeTypes);
        
        hasPendingElement = false;
        pendingNamespaces.clear();
        pendingAttributes.clear();
        pendingTypeNamespaceURI = null;
        pendingTypeLocalName = null;
        pendingAttributeTypes.clear();
    }
    
    /**
     * Extracts the prefix from a qualified name.
     */
    private static String extractPrefix(String qName) {
        if (qName == null) {
            return null;
        }
        int colon = qName.indexOf(':');
        return colon > 0 ? qName.substring(0, colon) : null;
    }
    
    /**
     * Generates a unique prefix by appending a number suffix.
     */
    private int prefixCounter = 0;
    
    private String generateUniquePrefix(String basePrefix) {
        return basePrefix + "_" + (prefixCounter++);
    }
    
    /**
     * Updates attribute prefixes after an element prefix rename.
     */
    private void updateAttributePrefixes(String oldPrefix, String newPrefix) {
        for (int i = 0; i < pendingAttributes.getLength(); i++) {
            String attrQName = pendingAttributes.getQName(i);
            String attrPrefix = extractPrefix(attrQName);
            if (oldPrefix.equals(attrPrefix)) {
                String attrLocalName = pendingAttributes.getLocalName(i);
                String newQName = newPrefix + ":" + attrLocalName;
                pendingAttributes.setQName(i, newQName);
            }
        }
    }
}

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
    private List<String[]> pendingNamespaces = new ArrayList<>(4);
    
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
    private List<String[]> pendingAttributeTypes = new ArrayList<>(4);
    
    // Element nesting depth (0 = document level)
    private int elementDepth = 0;
    private boolean documentStarted = false;
    
    // XSLT 2.0+ simple content spacing state
    private boolean atomicValuePending = false;
    private boolean textOutputPending = false;
    private boolean inAttributeContent = false;
    private String customItemSeparator = null;
    
    /**
     * Creates a new buffer output handler.
     *
     * @param buffer the buffer to write to
     */
    public BufferOutputHandler(SAXEventBuffer buffer) {
        this.buffer = buffer;
    }
    
    /**
     * Returns the underlying SAX event buffer.
     *
     * @return the SAX event buffer containing captured events
     */
    public SAXEventBuffer getBuffer() {
        return buffer;
    }
    
    @Override
    public void startDocument() throws SAXException {
        documentStarted = true;
        elementDepth = 0;
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
        elementDepth++;
        pendingNamespaces.clear();
        pendingAttributes.clear();
    }
    
    @Override
    public void endElement(String namespaceURI, String localName, String qName) 
            throws SAXException {
        flushPendingElement();
        buffer.endElement(namespaceURI, localName, qName);
        elementDepth--;
    }
    
    @Override
    public void attribute(String namespaceURI, String localName, String qName, String value) 
            throws SAXException {
        if (hasPendingElement) {
            String effectiveUri = OutputHandlerUtils.effectiveUri(namespaceURI);
            int existing = pendingAttributes.getIndex(effectiveUri, localName);
            if (existing >= 0) {
                pendingAttributes.setAttribute(existing, effectiveUri,
                    localName, qName, "CDATA", value);
            } else {
                pendingAttributes.addAttribute(effectiveUri,
                    localName, qName, "CDATA", value);
            }
        } else if (elementDepth == 0) {
            throw new SAXException("XTDE0420: An attribute node (" + localName +
                ") cannot be created as a child of a document node");
        }
    }
    
    @Override
    public void namespace(String prefix, String uri) throws SAXException {
        // Skip the xml prefix - it's implicitly bound and should never be declared
        if (OutputHandlerUtils.isXmlPrefix(prefix)) {
            return;
        }
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
            // Per XSLT 2.0 5.7.1, spaces separate adjacent atomic values only.
            // Text nodes from xsl:value-of etc. do not get a preceding space.
            buffer.characters(text.toCharArray(), 0, text.length());
            atomicValuePending = false;
            textOutputPending = true;
        }
    }
    
    private static final char[] SPACE = {' '};
    
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
        if (text != null) {
            buffer.comment(text.toCharArray(), 0, text.length());
        }
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
        
        String elementPrefix = OutputHandlerUtils.extractPrefix(pendingQName);
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
                    List<String[]> filtered = new ArrayList<String[]>(pendingNamespaces.size());
                    for (String[] n : pendingNamespaces) {
                        boolean isOldElementNs = elementPrefix.equals(n[0]) && pendingUri.equals(n[1]);
                        if (!isOldElementNs) {
                            filtered.add(n);
                        }
                    }
                    pendingNamespaces.clear();
                    pendingNamespaces.addAll(filtered);
                    
                    // Add namespace declaration for the element's namespace with new prefix
                    pendingNamespaces.add(new String[]{actualElementPrefix, pendingUri});
                    
                    // Also update any attributes that used the old prefix
                    updateAttributePrefixes(elementPrefix, actualElementPrefix);
                    break;
                }
            }
        }
        
        // Namespace fixup: resolve conflicting prefixes among namespace declarations.
        // When two declarations use the same prefix but different URIs (e.g. copying
        // attributes from different namespaces that share a prefix), rename the later one
        // and update any attributes that reference the renamed prefix.
        for (int i = 0; i < pendingNamespaces.size(); i++) {
            String[] ns1 = pendingNamespaces.get(i);
            String p1 = (ns1[0] != null) ? ns1[0] : "";
            if (p1.isEmpty()) {
                continue;
            }
            String u1 = (ns1[1] != null) ? ns1[1] : "";
            for (int j = i + 1; j < pendingNamespaces.size(); j++) {
                String[] ns2 = pendingNamespaces.get(j);
                String p2 = (ns2[0] != null) ? ns2[0] : "";
                if (!p1.equals(p2)) {
                    continue;
                }
                String u2 = (ns2[1] != null) ? ns2[1] : "";
                if (u1.equals(u2)) {
                    // Duplicate declaration — remove
                    pendingNamespaces.remove(j);
                    j--;
                    continue;
                }
                // Same prefix, different URI — generate a unique prefix for the later declaration
                String oldPrefix = p2;
                String newPrefix = generateUniquePrefix(oldPrefix);
                ns2[0] = newPrefix;
                // Update attributes that used the old prefix with this namespace URI
                for (int k = 0; k < pendingAttributes.getLength(); k++) {
                    String attrPrefix = OutputHandlerUtils.extractPrefix(pendingAttributes.getQName(k));
                    String attrUri = pendingAttributes.getURI(k);
                    if (oldPrefix.equals(attrPrefix) && u2.equals(attrUri)) {
                        String attrLocal = pendingAttributes.getLocalName(k);
                        pendingAttributes.setQName(k, newPrefix + ":" + attrLocal);
                    }
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
            String attrPrefix = OutputHandlerUtils.extractPrefix(attrQName);
            if (oldPrefix.equals(attrPrefix)) {
                String attrLocalName = pendingAttributes.getLocalName(i);
                String newQName = newPrefix + ":" + attrLocalName;
                pendingAttributes.setQName(i, newQName);
            }
        }
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
            throws SAXException {
        if (value != null) {
            if ((atomicValuePending || textOutputPending)) {
                emitItemSeparator();
            }
            String s = value.asString();
            if (s != null && !s.isEmpty()) {
                flushPendingElement();
                buffer.characters(s.toCharArray(), 0, s.length());
            }
            atomicValuePending = true;
            textOutputPending = false;
        }
    }
    
    /**
     * Sets a custom separator for items in attribute/simple content.
     * When null, uses default behavior (space unless inAttributeContent).
     *
     * @param separator the separator string, or null for default behavior
     */
    public void setCustomItemSeparator(String separator) {
        this.customItemSeparator = separator;
    }
    
    private void emitItemSeparator() throws SAXException {
        if (customItemSeparator != null) {
            if (!customItemSeparator.isEmpty()) {
                flushPendingElement();
                char[] chars = customItemSeparator.toCharArray();
                buffer.characters(chars, 0, chars.length);
            }
        } else if (!inAttributeContent) {
            flushPendingElement();
            buffer.characters(SPACE, 0, 1);
        }
    }
}

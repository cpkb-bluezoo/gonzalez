/*
 * SequenceBuilderOutputHandler.java
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

package org.bluezoo.gonzalez.transform.compiler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import org.xml.sax.SAXException;

import org.bluezoo.gonzalez.transform.runtime.OutputHandler;
import org.bluezoo.gonzalez.transform.runtime.SAXEventBuffer;
import org.bluezoo.gonzalez.transform.xpath.type.NodeType;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNode;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNodeSet;
import org.bluezoo.gonzalez.transform.xpath.type.XPathResultTreeFragment;
import org.bluezoo.gonzalez.transform.xpath.type.XPathSequence;
import org.bluezoo.gonzalez.transform.xpath.type.XPathString;
import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;

/**
 * OutputHandler that builds XPath sequences from XSLT output events.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public class SequenceBuilderOutputHandler implements OutputHandler {
    private final List<XPathValue> items = new ArrayList<>();
    private StringBuilder pendingText = new StringBuilder();
    
    // For building elements, we use a nested buffer approach
    private SAXEventBuffer elementBuffer = null;
    private CompilerBufferOutputHandler elementHandler = null;
    private int elementDepth = 0;
    
    // For building document nodes (xsl:document instruction)
    private SAXEventBuffer documentBuffer = null;
    private CompilerBufferOutputHandler documentHandler = null;
    private int documentDepth = 0;
    
    // For standalone attributes (not inside an element)
    private String pendingAttrUri, pendingAttrLocal, pendingAttrQName, pendingAttrValue;
    private String pendingAttrTypeNs, pendingAttrTypeLocal;
    
    // XSLT 2.0 atomic value spacing state
    private boolean atomicValuePending = false;
    private boolean inAttributeContent = false;
    
    public SequenceBuilderOutputHandler() {
    }
    
    /**
     * Directly adds an XPathValue to the sequence.
     * Used by xsl:document to add document nodes directly.
     */
    public void addItem(XPathValue item) throws SAXException {
        flushPendingText();
        flushPendingAttribute();
        items.add(item);
    }
    
    /**
     * Returns the constructed sequence.
     */
    public XPathValue getSequence() throws SAXException {
        flushPendingText();
        flushPendingAttribute();
        if (items.isEmpty()) {
            return XPathSequence.EMPTY;
        }
        if (items.size() == 1) {
            return items.get(0);
        }
        return new XPathSequence(items);
    }
    
    /**
     * Marks a boundary between sequence items.
     * Call this after each instruction to prevent text nodes from merging.
     */
    public void markItemBoundary() throws SAXException {
        flushPendingText();
        flushPendingAttribute();
        // Reset atomic value pending state so next item doesn't get a space prefix
        atomicValuePending = false;
    }
    
    private void flushPendingText() {
        if (pendingText.length() > 0) {
            // In sequence construction, text becomes a text node item
            items.add(new SequenceTextItem(pendingText.toString()));
            pendingText.setLength(0);
        }
    }
    
    private void flushPendingAttribute() throws SAXException {
        if (pendingAttrLocal != null) {
            // Create a standalone attribute node with type annotation
            items.add(new SequenceAttributeItem(pendingAttrUri, pendingAttrLocal, 
                pendingAttrQName, pendingAttrValue, pendingAttrTypeNs, pendingAttrTypeLocal));
            pendingAttrUri = pendingAttrLocal = pendingAttrQName = pendingAttrValue = null;
            pendingAttrTypeNs = pendingAttrTypeLocal = null;
        }
    }
    
    @Override 
    public void startDocument() throws SAXException {
        // No-op for sequence construction
    }
    
    @Override 
    public void endDocument() throws SAXException {
        flushPendingText();
        flushPendingAttribute();
    }
    
    @Override 
    public void startElement(String uri, String localName, String qName) throws SAXException {
        flushPendingText();
        flushPendingAttribute();
        
        if (elementBuffer == null) {
            // Start a new element subtree
            elementBuffer = new SAXEventBuffer();
            elementHandler = new CompilerBufferOutputHandler(elementBuffer);
        }
        elementHandler.startElement(uri, localName, qName);
        elementDepth++;
    }
    
    @Override 
    public void endElement(String uri, String localName, String qName) throws SAXException {
        if (elementHandler != null) {
            elementHandler.endElement(uri, localName, qName);
            elementDepth--;
            
            if (elementDepth == 0) {
                // Element complete - create node and add to sequence
                elementHandler.flush();
                XPathResultTreeFragment rtf = new XPathResultTreeFragment(elementBuffer, null);
                // Convert RTF to node-set and extract the actual element node (not the root)
                XPathNodeSet nodeSet = rtf.asNodeSet();
                if (nodeSet != null && !nodeSet.isEmpty()) {
                    // The RTF root contains the element as a child - extract it
                    XPathNode root = nodeSet.iterator().next();
                    if (root.getNodeType() == NodeType.ROOT) {
                        // Get the first element child
                        Iterator<XPathNode> children = root.getChildren();
                        while (children.hasNext()) {
                            XPathNode child = children.next();
                            if (child.getNodeType() == NodeType.ELEMENT) {
                                items.add(new XPathNodeSet(Collections.singletonList(child)));
                                break;
                            }
                        }
                    } else {
                        items.add(nodeSet);
                    }
                }
                elementBuffer = null;
                elementHandler = null;
            }
        }
    }
    
    @Override
    public void attribute(String uri, String localName, String qName, String value) throws SAXException {
        if (elementHandler != null && elementDepth > 0) {
            // Attribute inside an element - pass to element builder
            elementHandler.attribute(uri, localName, qName, value);
        } else {
            // Standalone attribute in sequence
            flushPendingText();
            flushPendingAttribute();
            pendingAttrUri = uri;
            pendingAttrLocal = localName;
            pendingAttrQName = qName;
            pendingAttrValue = value;
        }
    }
    
    @Override
    public void namespace(String prefix, String uri) throws SAXException {
        if (elementHandler != null && elementDepth > 0) {
            // Namespace inside an element - pass to element builder
            elementHandler.namespace(prefix, uri);
        } else {
            // Standalone namespace node in sequence
            flushPendingText();
            flushPendingAttribute();
            items.add(new SequenceNamespaceItem(prefix, uri));
        }
    }
    
    @Override
    public void characters(String text) throws SAXException {
        flushPendingAttribute();
        if (elementHandler != null && elementDepth > 0) {
            // Text inside an element
            elementHandler.characters(text);
        } else {
            // Text as a sequence item
            pendingText.append(text);
        }
    }
    
    @Override
    public void charactersRaw(String text) throws SAXException {
        characters(text);
    }
    
    @Override
    public void comment(String text) throws SAXException {
        flushPendingText();
        flushPendingAttribute();
        if (elementHandler != null && elementDepth > 0) {
            elementHandler.comment(text);
        } else {
            // Standalone comment in sequence
            items.add(new SequenceCommentItem(text));
        }
    }
    
    @Override
    public void processingInstruction(String target, String data) throws SAXException {
        flushPendingText();
        flushPendingAttribute();
        if (elementHandler != null && elementDepth > 0) {
            elementHandler.processingInstruction(target, data);
        } else {
            // Standalone PI in sequence
            items.add(new SequencePIItem(target, data));
        }
    }
    
    @Override
    public void flush() throws SAXException {
        if (elementHandler != null) {
            elementHandler.flush();
        }
    }
    
    @Override
    public void itemBoundary() throws SAXException {
        markItemBoundary();
    }
    
    @Override
    public void setElementType(String namespaceURI, String localName) throws SAXException {
        if (elementHandler != null) {
            elementHandler.setElementType(namespaceURI, localName);
        }
    }
    
    @Override
    public void setAttributeType(String namespaceURI, String localName) throws SAXException {
        if (elementHandler != null && elementDepth > 0) {
            elementHandler.setAttributeType(namespaceURI, localName);
        } else if (pendingAttrLocal != null) {
            // Type annotation for pending standalone attribute
            pendingAttrTypeNs = namespaceURI;
            pendingAttrTypeLocal = localName;
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
    public void atomicValue(XPathValue value) throws SAXException {
        flushPendingText();
        flushPendingAttribute();
        if (elementHandler != null && elementDepth > 0) {
            // Inside an element, convert to text
            elementHandler.characters(value.asString());
        } else {
            // Direct atomic value in sequence - preserve the typed value
            items.add(value);
            atomicValuePending = true;
        }
    }
    
    @Override
    public boolean isInAttributeContent() {
        return inAttributeContent;
    }
    
    @Override
    public void setInAttributeContent(boolean inAttributeContent) {
        this.inAttributeContent = inAttributeContent;
    }
}

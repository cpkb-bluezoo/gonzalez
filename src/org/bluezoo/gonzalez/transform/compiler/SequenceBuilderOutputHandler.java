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
    private boolean textPending = false;
    
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
    
    // Base URI context for resolving relative xml:base on detached nodes
    private String contextBaseUri;
    // Pending base URI set by CopyNode for the next element
    private String pendingNodeBaseUri;
    
    public SequenceBuilderOutputHandler() {
    }
    
    public SequenceBuilderOutputHandler(String contextBaseUri) {
        this.contextBaseUri = contextBaseUri;
    }
    
    /**
     * Returns true if we are currently building element content.
     * When true, callers should use the normal output methods (characters,
     * atomicValue) rather than addItem, so values flow into the element.
     */
    public boolean isInsideElement() {
        return elementDepth > 0;
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
        atomicValuePending = false;
    }
    
    private void flushPendingText() {
        if (textPending) {
            items.add(new SequenceTextItem(pendingText.toString()));
            pendingText.setLength(0);
            textPending = false;
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
        flushPendingText();
        flushPendingAttribute();
        if (documentBuffer == null) {
            documentBuffer = new SAXEventBuffer();
            documentHandler = new CompilerBufferOutputHandler(documentBuffer);
        }
        documentHandler.startDocument();
        documentDepth++;
    }
    
    @Override 
    public void endDocument() throws SAXException {
        if (documentDepth > 0 && documentHandler != null) {
            documentHandler.endDocument();
            documentDepth--;
            if (documentDepth == 0) {
                documentHandler.flush();
                XPathResultTreeFragment rtf = new XPathResultTreeFragment(documentBuffer);
                items.add(rtf);
                documentBuffer = null;
                documentHandler = null;
            }
        } else {
            flushPendingText();
            flushPendingAttribute();
        }
    }
    
    @Override 
    public void startElement(String uri, String localName, String qName) throws SAXException {
        
        if (documentDepth > 0 && documentHandler != null) {
            documentHandler.startElement(uri, localName, qName);
            return;
        }
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
        
        if (documentDepth > 0 && documentHandler != null) {
            documentHandler.endElement(uri, localName, qName);
            return;
        }
        if (elementHandler != null) {
            elementHandler.endElement(uri, localName, qName);
            elementDepth--;
            
            if (elementDepth == 0) {
                // Element complete - create node and add to sequence
                elementHandler.flush();
                
                String rtfBase = (pendingNodeBaseUri != null) ? pendingNodeBaseUri : contextBaseUri;
                XPathResultTreeFragment rtf = new XPathResultTreeFragment(elementBuffer, rtfBase);
                // Convert RTF to node-set and extract the actual element node (not the root)
                XPathNodeSet nodeSet = rtf.asNodeSet();
                if (nodeSet != null && !nodeSet.isEmpty()) {
                    // The RTF root contains the element as a child - extract it
                    XPathNode root = nodeSet.iterator().next();
                    if (root.getNodeType() == NodeType.ROOT) {
                        // Get the first element child and detach from RTF root
                        // to make it parentless per XSLT 3.0 sequence semantics
                        Iterator<XPathNode> children = root.getChildren();
                        while (children.hasNext()) {
                            XPathNode child = children.next();
                            if (child.getNodeType() == NodeType.ELEMENT) {
                                XPathResultTreeFragment.detachNode(child);
                                // Preserve base URI for parentless node resolution
                                if (rtfBase != null) {
                                    XPathResultTreeFragment.setNodeBaseURI(child, rtfBase);
                                }
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
                pendingNodeBaseUri = null;
            }
        }
    }
    
    @Override
    public void attribute(String uri, String localName, String qName, String value) throws SAXException {
        if (documentDepth > 0 && documentHandler != null) {
            documentHandler.attribute(uri, localName, qName, value);
            return;
        }
        if (elementHandler != null && elementDepth > 0) {
            // Attribute inside an element - pass to element builder
            elementHandler.attribute(uri, localName, qName, value);
        } else {
            // Standalone attribute in sequence
            flushPendingText();
            flushPendingAttribute();
            // Remove any preceding namespace node that was generated as
            // infrastructure for this attribute's prefix.  The namespace
            // URI is already encoded in the attribute item itself.
            removeTrailingNamespaceForAttribute(uri);
            pendingAttrUri = uri;
            pendingAttrLocal = localName;
            pendingAttrQName = qName;
            pendingAttrValue = value;
        }
    }
    
    private void removeTrailingNamespaceForAttribute(String attrUri) {
        if (attrUri == null || attrUri.isEmpty()) {
            return;
        }
        if (!items.isEmpty()) {
            XPathValue last = items.get(items.size() - 1);
            if (last instanceof SequenceNamespaceItem) {
                SequenceNamespaceItem nsItem = (SequenceNamespaceItem) last;
                if (attrUri.equals(nsItem.getUri())) {
                    items.remove(items.size() - 1);
                }
            }
        }
    }
    
    @Override
    public void namespace(String prefix, String uri) throws SAXException {
        if (documentDepth > 0 && documentHandler != null) {
            documentHandler.namespace(prefix, uri);
            return;
        }
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
        if (documentDepth > 0 && documentHandler != null) {
            documentHandler.characters(text);
            return;
        }
        flushPendingAttribute();
        if (elementHandler != null && elementDepth > 0) {
            elementHandler.characters(text);
        } else {
            pendingText.append(text);
            textPending = true;
        }
    }
    
    @Override
    public void charactersRaw(String text) throws SAXException {
        characters(text);
    }
    
    @Override
    public void comment(String text) throws SAXException {
        if (documentDepth > 0 && documentHandler != null) {
            documentHandler.comment(text);
            return;
        }
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
        if (documentDepth > 0 && documentHandler != null) {
            documentHandler.processingInstruction(target, data);
            return;
        }
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
    
    /**
     * Merges adjacent SequenceTextItem entries in the collected items
     * into single text nodes, as required by XSLT sequence construction
     * rules (adjacent text nodes in a sequence constructor are merged).
     */
    public void mergeAdjacentTextItems() {
        if (items.size() < 2) {
            return;
        }
        List<XPathValue> merged = new ArrayList<>();
        StringBuilder textAccum = null;
        for (XPathValue item : items) {
            if (item instanceof SequenceTextItem) {
                String t = ((SequenceTextItem) item).getStringValue();
                if (t != null && !t.isEmpty()) {
                    if (textAccum == null) {
                        textAccum = new StringBuilder();
                    }
                    textAccum.append(t);
                }
            } else {
                if (textAccum != null) {
                    merged.add(new SequenceTextItem(textAccum.toString()));
                    textAccum = null;
                }
                merged.add(item);
            }
        }
        if (textAccum != null) {
            merged.add(new SequenceTextItem(textAccum.toString()));
        }
        items.clear();
        items.addAll(merged);
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
    public void setNodeBaseURI(String uri) throws SAXException {
        if (elementDepth > 0 && elementHandler != null) {
            elementHandler.setNodeBaseURI(uri);
        } else {
            this.pendingNodeBaseUri = uri;
        }
    }
}

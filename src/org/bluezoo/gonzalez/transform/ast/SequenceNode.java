/*
 * SequenceNode.java
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

package org.bluezoo.gonzalez.transform.ast;

import org.bluezoo.gonzalez.transform.runtime.BufferOutputHandler;
import org.bluezoo.gonzalez.transform.runtime.SAXEventBuffer;
import org.bluezoo.gonzalez.transform.runtime.TransformContext;
import org.bluezoo.gonzalez.transform.runtime.OutputHandler;
import org.bluezoo.gonzalez.transform.xpath.type.XPathResultTreeFragment;
import org.xml.sax.SAXException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A sequence of XSLT nodes executed in order.
 *
 * <p>This is used to represent the contents of template bodies, conditional
 * branches, and other containers that hold multiple child nodes.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class SequenceNode implements XSLTNode {

    /**
     * Empty sequence singleton.
     *
     * <p>This is a shared instance representing an empty sequence with no children.
     * It can be used to avoid creating multiple empty sequence instances.
     */
    public static final SequenceNode EMPTY = new SequenceNode(Collections.emptyList());

    private final List<XSLTNode> children;
    private final StreamingCapability streamingCapability;

    /**
     * Creates a sequence from a list of nodes.
     *
     * @param children the child nodes
     */
    public SequenceNode(List<XSLTNode> children) {
        this.children = children != null ? 
            Collections.unmodifiableList(new ArrayList<>(children)) : 
            Collections.emptyList();
        this.streamingCapability = computeStreamingCapability();
    }

    /**
     * Creates a sequence from a single node.
     *
     * @param child the single child
     * @return the sequence
     */
    public static SequenceNode of(XSLTNode child) {
        if (child == null) {
            return EMPTY;
        }
        return new SequenceNode(Collections.singletonList(child));
    }

    /**
     * Creates a sequence from multiple nodes.
     *
     * @param children the child nodes
     * @return the sequence
     */
    public static SequenceNode of(XSLTNode... children) {
        if (children == null || children.length == 0) {
            return EMPTY;
        }
        List<XSLTNode> list = new ArrayList<>(children.length);
        Collections.addAll(list, children);
        return new SequenceNode(list);
    }

    @Override
    public void execute(TransformContext context, OutputHandler output) throws SAXException {
        // Normal execution
        executeNormal(context, output);
    }
    
    /**
     * Executes this sequence with on-empty/on-non-empty conditional support.
     * 
     * <p>This method is called by element constructors (xsl:element, literal result elements)
     * that need to handle xsl:on-empty and xsl:on-non-empty children. It uses a two-phase
     * approach:
     * 
     * <ol>
     * <li>Phase 1: Execute all children, forwarding attributes/namespaces directly to the 
     *     parent output, while buffering other content. Track which segments are conditional.</li>
     * <li>Phase 2: Replay buffers in order, including conditional content only if the
     *     appropriate condition is met (on-empty if empty, on-non-empty if non-empty)</li>
     * </ol>
     * 
     * @param context the transformation context
     * @param output the output handler (typically the parent element's output)
     * @param hasOnEmptyOrOnNonEmpty whether this sequence contains on-empty/on-non-empty
     * @throws SAXException if an error occurs
     */
    public void executeWithOnEmptySupport(TransformContext context, OutputHandler output, 
                                          boolean hasOnEmptyOrOnNonEmpty) throws SAXException {
        if (!hasOnEmptyOrOnNonEmpty) {
            // No conditionals - just execute normally
            executeNormal(context, output);
            return;
        }
        
        // Phase 1: Execute all children, tracking content type
        // Use a handler that forwards attributes/namespaces to parent but buffers other content
        List<SegmentBuffer> segments = new ArrayList<>();
        boolean hasRegularContent = false;
        boolean attributeOrNamespaceProduced = false;
        
        for (XSLTNode child : children) {
            boolean isOnEmptyNode = isOnEmpty(child);
            boolean isOnNonEmptyNode = isOnNonEmpty(child);
            
            // Execute into a hybrid handler that:
            // 1. Forwards attributes/namespaces to the real output
            // 2. Buffers everything else
            // 3. Tracks what was produced
            SAXEventBuffer buffer = new SAXEventBuffer();
            ContentSplittingHandler splitter = new ContentSplittingHandler(output, buffer);
            child.execute(context, splitter);
            splitter.flush();
            
            // Track whether regular content was produced (either attributes or buffered content)
            if (!isOnEmptyNode && !isOnNonEmptyNode) {
                if (splitter.hasAttributeOrNamespace() || !buffer.isEmpty()) {
                    hasRegularContent = true;
                }
                if (splitter.hasAttributeOrNamespace()) {
                    attributeOrNamespaceProduced = true;
                }
            }
            
            segments.add(new SegmentBuffer(buffer, isOnEmptyNode, isOnNonEmptyNode));
        }
        
        // Phase 2: Replay appropriate buffered segments in order
        // (Attributes/namespaces were already forwarded in Phase 1)
        for (SegmentBuffer segment : segments) {
            boolean shouldOutput;
            
            if (segment.isOnEmpty) {
                // Output on-empty only if there's no regular content
                shouldOutput = !hasRegularContent;
            } else if (segment.isOnNonEmpty) {
                // Output on-non-empty only if there IS regular content
                shouldOutput = hasRegularContent;
            } else {
                // Regular content - always output
                shouldOutput = true;
            }
            
            if (shouldOutput && !segment.buffer.isEmpty()) {
                XPathResultTreeFragment rtf = new XPathResultTreeFragment(segment.buffer);
                rtf.replayToOutput(output);
            }
            output.itemBoundary();
        }
    }
    
    /**
     * Buffer segment with metadata about whether it's conditional content.
     */
    private static class SegmentBuffer {
        final SAXEventBuffer buffer;
        final boolean isOnEmpty;
        final boolean isOnNonEmpty;
        
        SegmentBuffer(SAXEventBuffer buffer, boolean isOnEmpty, boolean isOnNonEmpty) {
            this.buffer = buffer;
            this.isOnEmpty = isOnEmpty;
            this.isOnNonEmpty = isOnNonEmpty;
        }
    }
    
    /**
     * Output handler that splits content between parent (for attributes/namespaces) 
     * and a buffer (for everything else). This is needed for on-empty/on-non-empty
     * because attributes must be attached to the parent element immediately, while
     * other content needs to be buffered to determine if the sequence is empty.
     */
    private static class ContentSplittingHandler implements OutputHandler {
        private final OutputHandler parent;
        private final BufferOutputHandler bufferHandler;
        private boolean hasAttributeOrNamespace = false;
        
        ContentSplittingHandler(OutputHandler parent, SAXEventBuffer buffer) {
            this.parent = parent;
            this.bufferHandler = new BufferOutputHandler(buffer);
        }
        
        boolean hasAttributeOrNamespace() {
            return hasAttributeOrNamespace;
        }
        
        @Override
        public void startDocument() throws SAXException {
            bufferHandler.startDocument();
        }
        
        @Override
        public void endDocument() throws SAXException {
            bufferHandler.endDocument();
        }
        
        @Override
        public void startElement(String namespaceURI, String localName, String qName) throws SAXException {
            bufferHandler.startElement(namespaceURI, localName, qName);
        }
        
        @Override
        public void endElement(String namespaceURI, String localName, String qName) throws SAXException {
            bufferHandler.endElement(namespaceURI, localName, qName);
        }
        
        @Override
        public void attribute(String namespaceURI, String localName, String qName, String value) throws SAXException {
            // Attributes go directly to parent - they belong to the parent element
            hasAttributeOrNamespace = true;
            parent.attribute(namespaceURI, localName, qName, value);
        }
        
        @Override
        public void namespace(String prefix, String uri) throws SAXException {
            // Namespace declarations go directly to parent
            hasAttributeOrNamespace = true;
            parent.namespace(prefix, uri);
        }
        
        @Override
        public void characters(String text) throws SAXException {
            bufferHandler.characters(text);
        }
        
        @Override
        public void charactersRaw(String text) throws SAXException {
            bufferHandler.charactersRaw(text);
        }
        
        @Override
        public void comment(String text) throws SAXException {
            bufferHandler.comment(text);
        }
        
        @Override
        public void processingInstruction(String target, String data) throws SAXException {
            bufferHandler.processingInstruction(target, data);
        }
        
        @Override
        public void flush() throws SAXException {
            bufferHandler.flush();
        }
        
        @Override
        public void setElementType(String namespaceURI, String localName) throws SAXException {
            parent.setElementType(namespaceURI, localName);
        }
        
        @Override
        public void setAttributeType(String namespaceURI, String localName) throws SAXException {
            parent.setAttributeType(namespaceURI, localName);
        }
        
        @Override
        public void setAtomicValuePending(boolean pending) throws SAXException {
            bufferHandler.setAtomicValuePending(pending);
        }
        
        @Override
        public boolean isAtomicValuePending() {
            return bufferHandler.isAtomicValuePending();
        }
        
        @Override
        public void setInAttributeContent(boolean inAttributeContent) throws SAXException {
            bufferHandler.setInAttributeContent(inAttributeContent);
        }
        
        @Override
        public boolean isInAttributeContent() {
            return bufferHandler.isInAttributeContent();
        }
        
        @Override
        public void itemBoundary() throws SAXException {
            bufferHandler.itemBoundary();
        }
    }
    
    /**
     * Normal execution without on-empty/on-non-empty handling.
     */
    private void executeNormal(TransformContext context, OutputHandler output) throws SAXException {
        // When executing a sequence, mark boundaries AFTER each child instruction
        // to prevent text/atomic values from different instructions being merged.
        // This is essential for XSLT 2.0+ sequence construction.
        for (XSLTNode child : children) {
            child.execute(context, output);
            // Mark item boundary after each instruction (no-op for most output handlers)
            output.itemBoundary();
        }
    }
    
    /**
     * Checks if this sequence contains any on-empty or on-non-empty instructions.
     * 
     * @return true if this sequence has conditional content
     */
    public boolean hasOnEmptyOrOnNonEmpty() {
        for (XSLTNode child : children) {
            if (isOnEmpty(child) || isOnNonEmpty(child)) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * Checks if a node is an xsl:on-empty instruction.
     */
    private static boolean isOnEmpty(XSLTNode node) {
        if (node instanceof XSLTInstruction) {
            return "on-empty".equals(((XSLTInstruction) node).getInstructionName());
        }
        return false;
    }
    
    /**
     * Checks if a node is an xsl:on-non-empty instruction.
     */
    private static boolean isOnNonEmpty(XSLTNode node) {
        if (node instanceof XSLTInstruction) {
            return "on-non-empty".equals(((XSLTInstruction) node).getInstructionName());
        }
        return false;
    }
    

    @Override
    public StreamingCapability getStreamingCapability() {
        return streamingCapability;
    }

    /**
     * Computes the streaming capability as the minimum of all children.
     */
    private StreamingCapability computeStreamingCapability() {
        StreamingCapability result = StreamingCapability.FULL;
        for (XSLTNode child : children) {
            StreamingCapability childCap = child.getStreamingCapability();
            if (childCap.ordinal() > result.ordinal()) {
                result = childCap;
            }
        }
        return result;
    }

    /**
     * Returns the child nodes.
     *
     * @return the children (immutable)
     */
    public List<XSLTNode> getChildren() {
        return children;
    }

    /**
     * Returns true if this sequence is empty.
     *
     * @return true if empty
     */
    public boolean isEmpty() {
        return children.isEmpty();
    }

    /**
     * Returns the number of children.
     *
     * @return the size
     */
    public int size() {
        return children.size();
    }

    @Override
    public String toString() {
        return "Sequence[" + children.size() + " children]";
    }

}

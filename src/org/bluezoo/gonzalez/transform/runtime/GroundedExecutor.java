/*
 * GroundedExecutor.java
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

import org.bluezoo.gonzalez.transform.ast.XSLTNode;
import org.bluezoo.gonzalez.transform.xpath.type.NodeType;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNode;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import java.util.ArrayList;
import java.util.List;

/**
 * Executor for grounded (subtree-buffered) transformations.
 *
 * <p>The GroundedExecutor handles templates that require subtree buffering
 * to support reverse axis navigation or other constructs that need the
 * full subtree to be available.
 *
 * <p>Grounded execution flow:
 * <ol>
 *   <li>Buffer current element's complete subtree (all SAX events)</li>
 *   <li>Build a fully navigable BufferedNode tree</li>
 *   <li>Execute the template with full XPath navigation available</li>
 *   <li>Release the buffer</li>
 * </ol>
 *
 * <p>This is used for templates with:
 * <ul>
 *   <li>parent:: axis</li>
 *   <li>ancestor:: or ancestor-or-self:: axis</li>
 *   <li>preceding-sibling:: axis</li>
 *   <li>last() in predicates</li>
 *   <li>xsl:sort (collect then sort)</li>
 * </ul>
 *
 * <p>Memory usage is proportional to subtree size, but the rest of the
 * document can still be streamed.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class GroundedExecutor {

    private final TransformContext parentContext;
    private final OutputHandler output;
    private SAXEventBuffer currentBuffer;
    private boolean buffering;
    private int bufferDepth;

    /**
     * Creates a new grounded executor.
     *
     * @param parentContext the parent transformation context
     * @param output the output handler
     */
    public GroundedExecutor(TransformContext parentContext, OutputHandler output) {
        this.parentContext = parentContext;
        this.output = output;
        this.buffering = false;
        this.bufferDepth = 0;
    }

    /**
     * Executes a template in grounded mode.
     *
     * <p>This buffers the current element's complete subtree, builds a
     * navigable node tree, and then executes the template with full
     * navigation available.
     *
     * @param template the template to execute
     * @param buffer the SAX event buffer containing the subtree
     * @param contextNode the original context node
     * @throws SAXException if execution fails
     */
    public void executeGrounded(XSLTNode template, SAXEventBuffer buffer,
                                 XPathNode contextNode) throws SAXException {
        if (contextNode == null || buffer == null) {
            return;
        }
        
        // Build a fully navigable tree from the buffer
        XPathNode bufferedRoot = buildTreeFromBuffer(buffer);
        XPathNode targetNode = bufferedRoot;
        if (targetNode != null) {
            TransformContext groundedCtx = parentContext.withContextNode(targetNode);
            template.execute(groundedCtx, output);
        } else {
            TransformContext groundedCtx = parentContext.withContextNode(contextNode);
            template.execute(groundedCtx, output);
        }
    }

    /**
     * Executes a template in grounded mode with an existing node.
     * The node should be a BufferedNode for full navigation support.
     *
     * @param template the template to execute
     * @param contextNode the context node (should be BufferedNode for full navigation)
     * @throws SAXException if execution fails
     */
    public void executeGrounded(XSLTNode template, XPathNode contextNode) throws SAXException {
        if (contextNode == null) {
            return;
        }
        
        TransformContext groundedCtx = parentContext.withContextNode(contextNode);
        template.execute(groundedCtx, output);
    }

    /**
     * Starts buffering the current subtree.
     * Called when entering an element that requires grounded execution.
     * Creates a new buffer if not already buffering, otherwise increments depth.
     *
     * @return the buffer to use for capturing SAX events
     */
    public SAXEventBuffer startBuffering() {
        if (!buffering) {
            currentBuffer = new SAXEventBuffer();
            buffering = true;
            bufferDepth = 0;
        }
        bufferDepth++;
        return currentBuffer;
    }

    /**
     * Ends buffering for the current element.
     * Called when exiting an element. Decrements depth and returns true
     * when the original buffering element is reached.
     *
     * @return true if buffering is complete (reached the original element)
     */
    public boolean endBuffering() {
        if (buffering) {
            bufferDepth--;
            if (bufferDepth <= 0) {
                buffering = false;
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the current buffer.
     *
     * @return the buffer, or null if not buffering
     */
    public SAXEventBuffer getCurrentBuffer() {
        return currentBuffer;
    }

    /**
     * Returns true if currently buffering.
     *
     * @return true if buffering
     */
    public boolean isBuffering() {
        return buffering;
    }

    /**
     * Clears the current buffer and resets buffering state.
     * Called when buffering is no longer needed.
     */
    public void clearBuffer() {
        currentBuffer = null;
        buffering = false;
        bufferDepth = 0;
    }

    /**
     * Returns the parent transformation context.
     *
     * @return the parent transformation context
     */
    public TransformContext getParentContext() {
        return parentContext;
    }

    /**
     * Returns the output handler.
     *
     * @return the output handler
     */
    public OutputHandler getOutput() {
        return output;
    }

    @Override
    public String toString() {
        return "GroundedExecutor[buffering=" + buffering + 
               ", depth=" + bufferDepth + "]";
    }

    /**
     * Builds a fully navigable tree from a SAX event buffer.
     *
     * @param buffer the buffer containing SAX events
     * @return the root node of the built tree, or null if buffer is empty
     * @throws SAXException if replay fails
     */
    public static XPathNode buildTreeFromBuffer(SAXEventBuffer buffer) throws SAXException {
        if (buffer == null || buffer.isEmpty()) {
            return null;
        }
        BufferedTreeBuilder builder = new BufferedTreeBuilder();
        buffer.replay(builder);
        return builder.getRoot();
    }

    /**
     * SAX handler that builds a navigable DocumentNode tree from SAX events.
     */
    private static class BufferedTreeBuilder extends DefaultHandler {
        private DocumentLoader.DocumentNode root;
        private DocumentLoader.DocumentNode current;
        private StringBuilder textBuffer = new StringBuilder();
        private int documentOrder = 0;
        private List<String[]> pendingNamespaces = new ArrayList<String[]>();

        XPathNode getRoot() {
            return root;
        }

        @Override
        public void startDocument() {
            root = new DocumentLoader.DocumentNode(NodeType.ROOT, null, null, null, null);
            root.documentOrder = documentOrder++;
            current = root;
        }

        @Override
        public void startPrefixMapping(String prefix, String uri) {
            pendingNamespaces.add(new String[]{prefix, uri});
        }

        @Override
        public void endPrefixMapping(String prefix) {
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes attrs) {
            flushText();
            String prefix = null;
            if (qName != null) {
                int colonIdx = qName.indexOf(':');
                if (colonIdx > 0) {
                    prefix = qName.substring(0, colonIdx);
                }
            }
            DocumentLoader.DocumentNode element = new DocumentLoader.DocumentNode(
                NodeType.ELEMENT, uri, localName, prefix, null);
            element.documentOrder = documentOrder++;
            element.parent = current;
            if (current != null) {
                current.addChild(element);
            }

            for (String[] ns : pendingNamespaces) {
                DocumentLoader.DocumentNode nsNode = new DocumentLoader.DocumentNode(
                    NodeType.NAMESPACE, null, ns[0], null, null);
                nsNode.documentOrder = documentOrder++;
                nsNode.value = ns[1];
                nsNode.parent = element;
                element.addNamespace(nsNode);
            }
            pendingNamespaces.clear();

            for (int i = 0; i < attrs.getLength(); i++) {
                DocumentLoader.DocumentNode attr = new DocumentLoader.DocumentNode(
                    NodeType.ATTRIBUTE, attrs.getURI(i), attrs.getLocalName(i), null, null);
                attr.documentOrder = documentOrder++;
                attr.value = attrs.getValue(i);
                attr.parent = element;
                element.addAttribute(attr);
            }

            current = element;
        }

        @Override
        public void endElement(String uri, String localName, String qName) {
            flushText();
            if (current != null && current.parent != null) {
                current = current.parent;
            }
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            textBuffer.append(ch, start, length);
        }

        @Override
        public void processingInstruction(String target, String data) {
            flushText();
            DocumentLoader.DocumentNode pi = new DocumentLoader.DocumentNode(
                NodeType.PROCESSING_INSTRUCTION, null, target, null, null);
            pi.documentOrder = documentOrder++;
            pi.value = data;
            pi.parent = current;
            if (current != null) {
                current.addChild(pi);
            }
        }

        private void flushText() {
            if (textBuffer.length() > 0) {
                DocumentLoader.DocumentNode text = new DocumentLoader.DocumentNode(
                    NodeType.TEXT, null, null, null, null);
                text.documentOrder = documentOrder++;
                text.value = textBuffer.toString();
                text.parent = current;
                if (current != null) {
                    current.addChild(text);
                }
                textBuffer.setLength(0);
            }
        }
    }
}

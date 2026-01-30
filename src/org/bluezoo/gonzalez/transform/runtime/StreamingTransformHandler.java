/*
 * StreamingTransformHandler.java
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
import org.bluezoo.gonzalez.transform.xpath.type.XPathNode;
import org.xml.sax.Attributes;
import org.xml.sax.ContentHandler;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;

import java.util.HashMap;
import java.util.Map;

/**
 * SAX ContentHandler for streaming transformation.
 *
 * <p>This handler processes SAX events from the Gonzalez parser during
 * xsl:stream execution. It:
 * <ul>
 *   <li>Creates StreamingNode instances for each element</li>
 *   <li>Fires accumulator rules at appropriate times</li>
 *   <li>Executes the streaming body content</li>
 *   <li>Outputs results to the OutputHandler</li>
 * </ul>
 *
 * <p>The handler maintains minimal state to enable memory-efficient
 * processing of large documents.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class StreamingTransformHandler implements ContentHandler {

    private final StreamingContext streamingContext;
    private final XSLTNode body;
    private final OutputHandler output;
    
    private StreamingNode currentNode;
    private long documentOrder;
    private boolean documentStarted;

    /**
     * Creates a new streaming transform handler.
     *
     * @param context the streaming context
     * @param body the body to execute
     * @param output the output handler
     */
    public StreamingTransformHandler(StreamingContext context, XSLTNode body,
                                      OutputHandler output) {
        this.streamingContext = context;
        this.body = body;
        this.output = output;
        this.documentOrder = 0;
        this.documentStarted = false;
    }

    @Override
    public void setDocumentLocator(Locator locator) {
        // Not used in streaming
    }

    @Override
    public void startDocument() throws SAXException {
        documentStarted = true;
        documentOrder = 0;
        
        // Initialize accumulators if configured
        AccumulatorManager mgr = streamingContext.getAccumulatorManager();
        if (mgr != null) {
            mgr.initialize();
        }
    }

    @Override
    public void endDocument() throws SAXException {
        documentStarted = false;
        
        // Reset accumulators
        AccumulatorManager mgr = streamingContext.getAccumulatorManager();
        if (mgr != null) {
            mgr.reset();
        }
    }

    @Override
    public void startPrefixMapping(String prefix, String uri) throws SAXException {
        if (currentNode != null) {
            currentNode.addNamespaceMapping(prefix, uri);
        }
    }

    @Override
    public void endPrefixMapping(String prefix) throws SAXException {
        // Namespace mappings are scoped to elements
    }

    @Override
    public void startElement(String uri, String localName, String qName, 
                            Attributes atts) throws SAXException {
        documentOrder++;
        streamingContext.pushDepth();
        
        // Extract prefix from qName
        String prefix = null;
        int colonIdx = qName.indexOf(':');
        if (colonIdx > 0) {
            prefix = qName.substring(0, colonIdx);
        }
        
        // Collect namespace bindings from parent
        Map<String, String> nsBindings = new HashMap<String, String>();
        if (currentNode != null) {
            nsBindings.putAll(currentNode.getNamespaceBindings());
        }
        
        // Create a new streaming node using factory method
        StreamingNode node = StreamingNode.createElement(
            uri, localName, prefix, atts, nsBindings, currentNode, documentOrder
        );
        
        currentNode = node;
        streamingContext.setCurrentNode(node);
        
        // Fire pre-descent accumulator rules
        AccumulatorManager mgr = streamingContext.getAccumulatorManager();
        if (mgr != null) {
            mgr.notifyStartElement(node);
        }
        
        // Execute body if at document element (depth 1)
        if (streamingContext.getDepth() == 1 && body != null) {
            // Create a transform context for body execution
            TransformContext bodyContext = createBodyContext(node);
            body.execute(bodyContext, output);
        }
    }

    @Override
    public void endElement(String uri, String localName, String qName) throws SAXException {
        // Fire post-descent accumulator rules
        AccumulatorManager mgr = streamingContext.getAccumulatorManager();
        if (mgr != null && currentNode != null) {
            mgr.notifyEndElement(currentNode);
        }
        
        // Move back to parent
        if (currentNode != null) {
            currentNode = currentNode.getParentNode();
        }
        streamingContext.setCurrentNode(currentNode);
        streamingContext.popDepth();
    }

    @Override
    public void characters(char[] ch, int start, int length) throws SAXException {
        if (currentNode != null) {
            currentNode.appendText(new String(ch, start, length));
        }
    }

    @Override
    public void ignorableWhitespace(char[] ch, int start, int length) throws SAXException {
        // Treat as regular characters in streaming mode
        characters(ch, start, length);
    }

    @Override
    public void processingInstruction(String target, String data) throws SAXException {
        documentOrder++;
        // Could create a PI node here if needed
    }

    @Override
    public void skippedEntity(String name) throws SAXException {
        // Not typically relevant in streaming
    }

    /**
     * Creates a transform context for body execution.
     */
    private TransformContext createBodyContext(XPathNode node) {
        TransformContext parent = streamingContext.getParentContext();
        if (parent instanceof BasicTransformContext) {
            BasicTransformContext btc = (BasicTransformContext) parent;
            TransformContext result = btc.withContextNode(node);
            if (result instanceof BasicTransformContext) {
                ((BasicTransformContext) result).setAccumulatorManager(
                    streamingContext.getAccumulatorManager()
                );
            }
            return result;
        }
        return parent.withContextNode(node);
    }

    /**
     * Returns the current streaming node.
     *
     * @return the current node
     */
    public StreamingNode getCurrentNode() {
        return currentNode;
    }

    /**
     * Returns the streaming context.
     *
     * @return the streaming context
     */
    public StreamingContext getStreamingContext() {
        return streamingContext;
    }

}

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

import org.bluezoo.gonzalez.XMLHandler;
import org.bluezoo.gonzalez.transform.ast.XSLTNode;
import org.bluezoo.gonzalez.transform.compiler.CompiledStylesheet;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNode;
import org.xml.sax.Attributes;
import org.xml.sax.ContentHandler;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import java.nio.CharBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * Native/SAX handler for streaming transformation.
 *
 * <p>This handler processes events from the Gonzalez parser during
 * xsl:stream / xsl:source-document streaming execution. It:
 * <ul>
 *   <li>Creates StreamingNode instances for each element</li>
 *   <li>Fires accumulator rules at appropriate times</li>
 *   <li>Executes the streaming body content</li>
 *   <li>Outputs results to the OutputHandler</li>
 * </ul>
 *
 * <p>When driven by Gonzalez {@code Parser.setXMLHandler}, no SAXAdapter is
 * constructed. A SAX {@link ContentHandler} façade remains for foreign readers.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class StreamingTransformHandler implements ContentHandler, XMLHandler {

    private final StreamingContext streamingContext;
    private final XSLTNode body;
    private final OutputHandler output;
    
    private StreamingNode currentNode;
    private StreamingNode documentElement;
    private long documentOrder;
    private boolean documentStarted;
    private boolean hasPendingText;
    private final Map<String, String> reusableNsBindings = new HashMap<String, String>();
    private final Map<String, String> pendingNamespaces = new HashMap<String, String>();
    private final NativeAttributeBuffer nativeAttributes = new NativeAttributeBuffer();
    private String nativeElementQName;
    private String nativePITarget;
    private boolean nativePIDataFirstChunk;
    private final StringBuilder nativePIDataBuffer = new StringBuilder();

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
    public void setLocator(Locator locator) {
        setDocumentLocator(locator);
    }

    @Override
    public void setXml11(boolean xml11) {
    }

    @Override
    public void startDocument() throws SAXException {
        documentStarted = true;
        documentOrder = 0;
        pendingNamespaces.clear();
        
        // Create root node so that document element becomes its child,
        // matching the non-streaming tree where / is the document root
        currentNode = StreamingNode.createRoot();
        streamingContext.setCurrentNode(currentNode);
        
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
        pendingNamespaces.put(prefix != null ? prefix : "", uri != null ? uri : "");
    }

    @Override
    public void endPrefixMapping(String prefix) throws SAXException {
        // Namespace mappings are scoped to elements
    }

    @Override
    public void namespace(String prefix, String uri) throws SAXException {
        pendingNamespaces.put(prefix != null ? prefix : "", uri != null ? uri : "");
    }

    @Override
    public void startElement(String qName) throws SAXException {
        firePendingTextAccumulators();
        nativeElementQName = qName;
        nativeAttributes.clear();
    }

    @Override
    public void startAttribute(String name, String type, boolean declared,
            boolean specified) throws SAXException {
        nativeAttributes.startAttribute(name, type);
    }

    @Override
    public void attributeValueContent(CharBuffer value, boolean end)
            throws SAXException {
        nativeAttributes.attributeValueContent(value, end);
    }

    @Override
    public void endAttributes() throws SAXException {
        documentOrder++;
        streamingContext.pushDepth();

        boolean hadPending = !pendingNamespaces.isEmpty();
        Map<String, String> nsBindings;
        if (!hadPending && currentNode != null) {
            nsBindings = currentNode.getNamespaceBindingsForChild();
        } else {
            reusableNsBindings.clear();
            if (currentNode != null) {
                reusableNsBindings.putAll(currentNode.getNamespaceBindingsForChild());
            }
            reusableNsBindings.putAll(pendingNamespaces);
            nsBindings = reusableNsBindings;
        }
        pendingNamespaces.clear();

        String prefix = NativeExpandedNames.extractPrefix(nativeElementQName);
        String localName = NativeExpandedNames.extractLocalName(nativeElementQName);
        String uri = NativeExpandedNames.resolveNamespaceURI(
                prefix, false, nsBindings);
        nativeAttributes.resolveAndCheckDuplicates(nsBindings);

        StreamingNode node = StreamingNode.createElement(
                uri, localName, prefix, null, nsBindings,
                currentNode, documentOrder);

        int nsCount = node.getNamespaceNodeCount();
        int emittedAttributeCount = 0;
        for (int i = 0; i < nativeAttributes.size(); i++) {
            NativeAttributeBuffer.Attr attr = nativeAttributes.get(i);
            if (NativeExpandedNames.isNamespaceDeclaration(attr.qName)) {
                continue;
            }
            long attrOrder = documentOrder + nsCount + emittedAttributeCount + 1;
            node.addNativeAttribute(attr.uri, attr.localName, attr.prefix,
                    attr.value, attr.type, null, attrOrder);
            emittedAttributeCount++;
        }
        documentOrder += nsCount + emittedAttributeCount;

        finishStartElement(node);
    }

    @Override
    public void startElement(String uri, String localName, String qName, 
                            Attributes atts) throws SAXException {
        firePendingTextAccumulators();
        documentOrder++;
        streamingContext.pushDepth();
        
        // Extract prefix from qName
        String prefix = null;
        int colonIdx = qName.indexOf(':');
        if (colonIdx > 0) {
            prefix = qName.substring(0, colonIdx);
        }
        
        // Collect namespace bindings from parent + pending declarations
        Map<String, String> nsBindings;
        boolean hadPending = !pendingNamespaces.isEmpty();
        if (!hadPending && currentNode != null) {
            nsBindings = currentNode.getNamespaceBindingsForChild();
        } else {
            reusableNsBindings.clear();
            if (currentNode != null) {
                reusableNsBindings.putAll(currentNode.getNamespaceBindingsForChild());
            }
            reusableNsBindings.putAll(pendingNamespaces);
            nsBindings = reusableNsBindings;
        }
        pendingNamespaces.clear();
        
        StreamingNode node = StreamingNode.createElement(
            uri, localName, prefix, atts, nsBindings, currentNode, documentOrder
        );
        documentOrder += node.getNamespaceNodeCount() + atts.getLength();
        finishStartElement(node);
    }

    private void finishStartElement(StreamingNode node) throws SAXException {
        currentNode = node;
        streamingContext.setCurrentNode(node);
        
        // Fire pre-descent accumulator rules
        AccumulatorManager mgr = streamingContext.getAccumulatorManager();
        if (mgr != null) {
            mgr.notifyStartElement(node);
        }
        
        // Remember document element for deferred body execution
        if (streamingContext.getDepth() == 1) {
            documentElement = node;
        }
    }

    @Override
    public void endElement() throws SAXException {
        endElement(null, null, null);
    }

    @Override
    public void endElement(String uri, String localName, String qName) throws SAXException {
        firePendingTextAccumulators();

        // Fire post-descent accumulator rules
        AccumulatorManager mgr = streamingContext.getAccumulatorManager();
        if (mgr != null && currentNode != null) {
            mgr.notifyEndElement(currentNode);
        }
        
        // Execute body at end of document element, when all descendants are available.
        // Use the root node as context, matching non-streaming behaviour where the
        // document root (/) is the context for xsl:source-document body.
        if (streamingContext.getDepth() == 1 && body != null && documentElement != null) {
            XPathNode root = documentElement.getRoot();
            TransformContext bodyContext = createBodyContext(root);
            body.execute(bodyContext, output);
            documentElement = null;
        }
        
        // Move back to parent
        if (currentNode != null) {
            currentNode = currentNode.getParentNode();
        }
        streamingContext.setCurrentNode(currentNode);
        streamingContext.popDepth();
    }

    @Override
    public void characters(CharBuffer text, boolean ignorable, boolean end)
            throws SAXException {
        // Match prior SAX streaming behaviour: ignorable whitespace is kept.
        if (currentNode != null) {
            documentOrder++;
            currentNode.appendText(text.toString(), documentOrder);
            hasPendingText = true;
        }
    }

    @Override
    public void characters(char[] ch, int start, int length) throws SAXException {
        if (currentNode != null) {
            documentOrder++;
            currentNode.appendText(new String(ch, start, length), documentOrder);
            hasPendingText = true;
        }
    }

    @Override
    public void ignorableWhitespace(char[] ch, int start, int length) throws SAXException {
        // Treat as regular characters in streaming mode
        characters(ch, start, length);
    }

    @Override
    public void piTarget(String target) throws SAXException {
        nativePITarget = target;
        nativePIDataFirstChunk = true;
    }

    @Override
    public void piData(CharBuffer data, boolean end) throws SAXException {
        String value;
        if (nativePIDataFirstChunk && end) {
            value = data.toString();
        } else {
            if (nativePIDataFirstChunk) {
                nativePIDataBuffer.setLength(0);
                nativePIDataFirstChunk = false;
            }
            nativePIDataBuffer.append(data);
            if (!end) {
                return;
            }
            value = nativePIDataBuffer.toString();
        }
        documentOrder++;
        if (currentNode != null) {
            StreamingNode.createPI(nativePITarget, value, currentNode, documentOrder);
        }
    }

    @Override
    public void processingInstruction(String target, String data) throws SAXException {
        documentOrder++;
        if (currentNode != null) {
            StreamingNode.createPI(target, data, currentNode, documentOrder);
        }
    }

    @Override
    public void skippedEntity(String name) throws SAXException {
        // Not typically relevant in streaming
    }

    @Override
    public void startComment() {
    }

    @Override
    public void commentData(CharBuffer text, boolean end) {
        // Streaming path historically dropped comments (no LexicalHandler).
    }

    @Override
    public void startCDATA() {
    }

    @Override
    public void endCDATA() {
    }

    @Override
    public void startDTD(String name, String publicId, String systemId) {
    }

    @Override
    public void endDTD() {
    }

    @Override
    public void startEntity(String name) {
    }

    @Override
    public void endEntity(String name) {
    }

    @Override
    public void notationDecl(String name, String publicId, String systemId) {
    }

    @Override
    public void unparsedEntityDecl(String name, String publicId, String systemId,
            String notationName) {
    }

    @Override
    public void saveBuffers() {
    }

    @Override
    public SAXException fatalError(String message) {
        return new SAXParseException(message, null);
    }

    @Override
    public void error(String message) {
    }

    /**
     * Fires accumulator rules on any pending text child nodes of the current element.
     * Called before startElement and endElement to ensure text node accumulators
     * fire at the correct point in document order.
     * Also applies xsl:strip-space rules to remove whitespace-only text nodes.
     */
    private void firePendingTextAccumulators() throws SAXException {
        if (!hasPendingText || currentNode == null) {
            return;
        }
        hasPendingText = false;

        StreamingNode lastChild = currentNode.getLastChild();
        if (lastChild == null || !lastChild.isText()) {
            return;
        }

        if (shouldStripText(lastChild.getStringValue())) {
            currentNode.removeLastChild();
            return;
        }

        AccumulatorManager mgr = streamingContext.getAccumulatorManager();
        if (mgr != null) {
            mgr.notifyTextNode(lastChild);
        }
    }

    /**
     * Checks if whitespace-only text should be stripped based on xsl:strip-space rules.
     */
    private boolean shouldStripText(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
                return false;
            }
        }
        if (currentNode == null || !currentNode.isElement()) {
            return false;
        }
        CompiledStylesheet stylesheet = streamingContext.getStylesheet();
        if (stylesheet == null) {
            return false;
        }
        String nsUri = currentNode.getNamespaceURI();
        String localName = currentNode.getLocalName();
        return stylesheet.shouldStripWhitespace(nsUri, localName);
    }

    /**
     * Creates a transform context for body execution.
     *
     * @param node the context node for the body
     * @return a transform context configured for body execution
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

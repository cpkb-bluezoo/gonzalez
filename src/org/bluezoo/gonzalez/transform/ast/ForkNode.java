/*
 * ForkNode.java
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

import org.bluezoo.gonzalez.transform.runtime.AccumulatorManager;
import org.bluezoo.gonzalez.transform.runtime.BasicTransformContext;
import org.bluezoo.gonzalez.transform.runtime.OutputHandler;
import org.bluezoo.gonzalez.transform.runtime.SAXEventBuffer;
import org.bluezoo.gonzalez.transform.runtime.TransformContext;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.AttributesImpl;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * XSLT 3.0 xsl:fork instruction.
 *
 * <p>The xsl:fork instruction allows multiple independent operations to be
 * performed on the same streaming input. Each branch (xsl:sequence child)
 * sees the same SAX events but can maintain independent accumulator state.
 *
 * <p>Example:
 * <pre>
 * &lt;xsl:fork&gt;
 *   &lt;xsl:sequence&gt;
 *     &lt;!-- Branch 1: Count items --&gt;
 *     &lt;count&gt;&lt;xsl:value-of select="accumulator-after('item-count')"/&gt;&lt;/count&gt;
 *   &lt;/xsl:sequence&gt;
 *   &lt;xsl:sequence&gt;
 *     &lt;!-- Branch 2: Sum prices --&gt;
 *     &lt;total&gt;&lt;xsl:value-of select="accumulator-after('price-total')"/&gt;&lt;/total&gt;
 *   &lt;/xsl:sequence&gt;
 * &lt;/xsl:fork&gt;
 * </pre>
 *
 * <p>Streaming semantics:
 * <ul>
 *   <li>All branches receive the same SAX events</li>
 *   <li>Each branch has its own accumulator state</li>
 *   <li>Outputs are combined in document order</li>
 *   <li>Branches execute concurrently using a dedicated thread pool</li>
 * </ul>
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class ForkNode implements XSLTNode {

    /**
     * Dedicated thread pool for fork branch execution.
     * Uses daemon threads so they don't prevent JVM shutdown.
     */
    private static final ExecutorService FORK_EXECUTOR = Executors.newCachedThreadPool(
        new ThreadFactory() {
            private final AtomicInteger counter = new AtomicInteger(0);
            
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "xsl-fork-" + counter.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        }
    );

    /**
     * Represents a branch within xsl:fork.
     *
     * <p>Each branch corresponds to an xsl:sequence child of xsl:fork.
     * All branches receive the same SAX events but maintain independent
     * accumulator state.
     */
    public static final class ForkBranch {
        private final XSLTNode content;

        /**
         * Creates a new fork branch.
         *
         * @param content the branch content (xsl:sequence body)
         */
        public ForkBranch(XSLTNode content) {
            this.content = content;
        }

        /**
         * Returns the branch content.
         *
         * @return the content node, or null if empty
         */
        public XSLTNode getContent() {
            return content;
        }
    }

    private final List<ForkBranch> branches;

    /**
     * Creates a new fork instruction.
     *
     * @param branches the fork branches (xsl:sequence children)
     */
    public ForkNode(List<ForkBranch> branches) {
        this.branches = branches != null ? new ArrayList<>(branches) : new ArrayList<>();
    }

    /**
     * Returns the fork branches.
     *
     * <p>Each branch corresponds to an xsl:sequence child and will be
     * executed concurrently with the same input events.
     *
     * @return the branches (immutable list)
     */
    public List<ForkBranch> getBranches() {
        return branches;
    }

    @Override
    public void execute(TransformContext context, OutputHandler output) throws SAXException {
        int branchCount = branches.size();
        
        if (branchCount == 0) {
            return;
        }
        
        // Single branch - no parallelism needed
        if (branchCount == 1) {
            ForkBranch branch = branches.get(0);
            if (branch.getContent() != null) {
                branch.getContent().execute(context, output);
            }
            return;
        }
        
        // Multiple branches - execute in parallel
        final SAXEventBuffer[] results = new SAXEventBuffer[branchCount];
        final SAXException[] errors = new SAXException[1];
        final CountDownLatch latch = new CountDownLatch(branchCount);
        
        for (int i = 0; i < branchCount; i++) {
            final int index = i;
            final ForkBranch branch = branches.get(index);
            
            if (branch.getContent() == null) {
                // Empty branch - no work to do
                results[index] = new SAXEventBuffer();
                latch.countDown();
                continue;
            }
            
            // Clone context for this branch
            final TransformContext branchContext = cloneContextForBranch(context);
            
            FORK_EXECUTOR.execute(new Runnable() {
                public void run() {
                    try {
                        SAXEventBuffer buffer = new SAXEventBuffer();
                        BufferOutputHandler bufferHandler = new BufferOutputHandler(buffer);
                        branch.getContent().execute(branchContext, bufferHandler);
                        bufferHandler.flush();
                        results[index] = buffer;
                    } catch (SAXException e) {
                        synchronized (errors) {
                            if (errors[0] == null) {
                                errors[0] = e;
                            }
                        }
                    } catch (Exception e) {
                        synchronized (errors) {
                            if (errors[0] == null) {
                                errors[0] = new SAXException("Error in fork branch " + index, e);
                            }
                        }
                    } finally {
                        latch.countDown();
                    }
                }
            });
        }
        
        // Wait for all branches to complete
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SAXException("Fork execution interrupted", e);
        }
        
        // Check for errors
        if (errors[0] != null) {
            throw errors[0];
        }
        
        // Replay all buffers to output in document order
        for (int i = 0; i < branchCount; i++) {
            if (results[i] != null) {
                results[i].replayContent(new SAXEventAdapter(output));
            }
        }
    }

    /**
     * Creates an independent copy of the context for a branch.
     * Each branch gets its own variable scope and accumulator state.
     */
    private TransformContext cloneContextForBranch(TransformContext context) {
        // Push a new variable scope for isolation
        TransformContext branchContext = context.pushVariableScope();
        
        // Clone accumulator manager if present
        if (branchContext instanceof BasicTransformContext) {
            BasicTransformContext btc = (BasicTransformContext) branchContext;
            AccumulatorManager origManager = btc.getAccumulatorManager();
            if (origManager != null) {
                AccumulatorManager clonedManager = new AccumulatorManager(origManager, branchContext);
                btc.setAccumulatorManager(clonedManager);
            }
        }
        
        return branchContext;
    }

    @Override
    public StreamingCapability getStreamingCapability() {
        // Fork is designed for streaming
        StreamingCapability result = StreamingCapability.FULL;
        for (ForkBranch branch : branches) {
            if (branch.getContent() != null) {
                StreamingCapability branchCap = branch.getContent().getStreamingCapability();
                if (branchCap.ordinal() > result.ordinal()) {
                    result = branchCap;
                }
            }
        }
        return result;
    }

    @Override
    public String toString() {
        return "ForkNode[branches=" + branches.size() + "]";
    }

    /**
     * OutputHandler that buffers to SAXEventBuffer.
     */
    private static class BufferOutputHandler implements OutputHandler {
        private final SAXEventBuffer buffer;
        private boolean inStartTag = false;
        private String pendingUri;
        private String pendingLocalName;
        private String pendingQName;
        private final AttributesImpl pendingAttrs = new AttributesImpl();

        BufferOutputHandler(SAXEventBuffer buffer) {
            this.buffer = buffer;
        }

        public void startDocument() throws SAXException {
            buffer.startDocument();
        }

        public void endDocument() throws SAXException {
            flush();
            buffer.endDocument();
        }

        public void startElement(String uri, String localName, String qName) throws SAXException {
            flush();
            inStartTag = true;
            pendingUri = uri != null ? uri : "";
            pendingLocalName = localName;
            pendingQName = qName != null ? qName : localName;
            pendingAttrs.clear();
        }

        public void endElement(String uri, String localName, String qName) throws SAXException {
            flush();
            buffer.endElement(
                uri != null ? uri : "", 
                localName, 
                qName != null ? qName : localName);
        }

        public void attribute(String uri, String localName, String qName, String value) 
                throws SAXException {
            if (!inStartTag) {
                throw new SAXException("Attribute outside of start tag");
            }
            pendingAttrs.addAttribute(
                uri != null ? uri : "", 
                localName,
                qName != null ? qName : localName, 
                "CDATA", 
                value);
        }

        public void namespace(String prefix, String uri) throws SAXException {
            flush();
            buffer.startPrefixMapping(prefix != null ? prefix : "", uri);
        }

        public void characters(String text) throws SAXException {
            flush();
            if (text != null && !text.isEmpty()) {
                buffer.characters(text.toCharArray(), 0, text.length());
            }
        }

        public void charactersRaw(String text) throws SAXException {
            // Buffer doesn't distinguish raw vs escaped - just store as characters
            characters(text);
        }

        public void comment(String text) throws SAXException {
            flush();
            // SAXEventBuffer doesn't support comments directly - skip
        }

        public void processingInstruction(String target, String data) throws SAXException {
            flush();
            buffer.processingInstruction(target, data);
        }

        public void flush() throws SAXException {
            if (inStartTag) {
                buffer.startElement(pendingUri, pendingLocalName, pendingQName, pendingAttrs);
                inStartTag = false;
                pendingAttrs.clear();
            }
        }
    }

    /**
     * Adapts SAXEventBuffer replay (ContentHandler) to OutputHandler.
     */
    private static class SAXEventAdapter implements org.xml.sax.ContentHandler {
        private final OutputHandler output;

        SAXEventAdapter(OutputHandler output) {
            this.output = output;
        }

        public void setDocumentLocator(org.xml.sax.Locator locator) {
            // Ignored
        }

        public void startDocument() throws SAXException {
            // Don't propagate - we're replaying content only
        }

        public void endDocument() throws SAXException {
            // Don't propagate - we're replaying content only
        }

        public void startPrefixMapping(String prefix, String uri) throws SAXException {
            output.namespace(prefix, uri);
        }

        public void endPrefixMapping(String prefix) throws SAXException {
            // OutputHandler doesn't track this - skip
        }

        public void startElement(String uri, String localName, String qName, Attributes atts) 
                throws SAXException {
            output.startElement(uri, localName, qName);
            for (int i = 0; i < atts.getLength(); i++) {
                output.attribute(
                    atts.getURI(i), 
                    atts.getLocalName(i), 
                    atts.getQName(i), 
                    atts.getValue(i));
            }
        }

        public void endElement(String uri, String localName, String qName) throws SAXException {
            output.endElement(uri, localName, qName);
        }

        public void characters(char[] ch, int start, int length) throws SAXException {
            output.characters(new String(ch, start, length));
        }

        public void ignorableWhitespace(char[] ch, int start, int length) throws SAXException {
            output.characters(new String(ch, start, length));
        }

        public void processingInstruction(String target, String data) throws SAXException {
            output.processingInstruction(target, data);
        }

        public void skippedEntity(String name) throws SAXException {
            // Ignored
        }
    }

}

/*
 * TryNode.java
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.xml.sax.SAXException;

import org.bluezoo.gonzalez.transform.runtime.OutputHandler;
import org.bluezoo.gonzalez.transform.runtime.TransformContext;
import org.bluezoo.gonzalez.transform.xpath.expr.XPathException;
import org.bluezoo.gonzalez.transform.xpath.type.XPathString;
import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;

/**
 * TryNode XSLT instruction.
 *
 * <p>Per XSLT 3.0, the try block output is buffered and only committed
 * if evaluation succeeds. On error, the buffer is discarded and the
 * matching catch block is evaluated instead.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public class TryNode extends XSLTInstruction {
    private final XSLTNode tryContent;
    private final List<CatchNode> catchBlocks;
    private final boolean rollbackOutput;
    
    public TryNode(XSLTNode tryContent, List<CatchNode> catchBlocks) {
        this(tryContent, catchBlocks, true);
    }
    
    public TryNode(XSLTNode tryContent, List<CatchNode> catchBlocks, boolean rollbackOutput) {
        this.tryContent = tryContent;
        this.catchBlocks = catchBlocks != null ? catchBlocks : Collections.emptyList();
        this.rollbackOutput = rollbackOutput;
    }
    
    @Override public String getInstructionName() { return "try"; }
    public XSLTNode getTryContent() { return tryContent; }
    public List<CatchNode> getCatchBlocks() { return catchBlocks; }
    
    @Override
    public void execute(TransformContext context, OutputHandler output) throws SAXException {
        if (!rollbackOutput) {
            executeNoRollback(context, output);
            return;
        }
        RecordingOutputHandler buffer = new RecordingOutputHandler();
        try {
            if (tryContent != null) {
                // Per XSLT 3.0 section 3.12, variables declared in the try
                // body are not in scope within xsl:catch
                TransformContext tryContext = context.pushVariableScope();
                tryContent.execute(tryContext, buffer);
            }
            buffer.replayTo(output);
        } catch (SAXException e) {
            handleError(extractErrorCode(e), context, output, e);
        } catch (RuntimeException e) {
            handleError(extractErrorCode(e), context, output, e);
        }
    }
    
    private void executeNoRollback(TransformContext context, OutputHandler output) throws SAXException {
        TrackingOutputHandler tracker = new TrackingOutputHandler(output);
        try {
            if (tryContent != null) {
                TransformContext tryContext = context.pushVariableScope();
                tryContent.execute(tryContext, tracker);
            }
        } catch (SAXException e) {
            if (tracker.hasOutput()) {
                throw new SAXException("XTDE3530: Cannot recover from error with " +
                    "rollback-output=\"no\" because output has already been written: " +
                    e.getMessage(), e);
            }
            handleError(extractErrorCode(e), context, output, e);
        } catch (RuntimeException e) {
            if (tracker.hasOutput()) {
                throw new SAXException("XTDE3530: Cannot recover from error with " +
                    "rollback-output=\"no\" because output has already been written: " +
                    e.getMessage());
            }
            handleError(extractErrorCode(e), context, output, e);
        }
    }
    
    /**
     * Output handler that delegates to a real handler and tracks whether any
     * content-bearing output events have been written.
     */
    private static class TrackingOutputHandler implements OutputHandler {
        private final OutputHandler delegate;
        private boolean outputWritten = false;
        
        TrackingOutputHandler(OutputHandler delegate) {
            this.delegate = delegate;
        }
        
        boolean hasOutput() {
            return outputWritten;
        }
        
        @Override
        public void startDocument() throws SAXException {
            delegate.startDocument();
        }
        
        @Override
        public void endDocument() throws SAXException {
            delegate.endDocument();
        }
        
        @Override
        public void startElement(String uri, String localName, String qName) throws SAXException {
            outputWritten = true;
            delegate.startElement(uri, localName, qName);
        }
        
        @Override
        public void endElement(String uri, String localName, String qName) throws SAXException {
            delegate.endElement(uri, localName, qName);
        }
        
        @Override
        public void attribute(String uri, String localName, String qName, String value) throws SAXException {
            outputWritten = true;
            delegate.attribute(uri, localName, qName, value);
        }
        
        @Override
        public void namespace(String prefix, String uri) throws SAXException {
            delegate.namespace(prefix, uri);
        }
        
        @Override
        public void characters(String text) throws SAXException {
            if (text != null && !text.isEmpty()) {
                outputWritten = true;
            }
            delegate.characters(text);
        }
        
        @Override
        public void charactersRaw(String text) throws SAXException {
            if (text != null && !text.isEmpty()) {
                outputWritten = true;
            }
            delegate.charactersRaw(text);
        }
        
        @Override
        public void comment(String text) throws SAXException {
            outputWritten = true;
            delegate.comment(text);
        }
        
        @Override
        public void processingInstruction(String target, String data) throws SAXException {
            outputWritten = true;
            delegate.processingInstruction(target, data);
        }
        
        @Override
        public void flush() throws SAXException {
            delegate.flush();
        }
        
        @Override
        public void setElementType(String namespaceURI, String localName) throws SAXException {
            delegate.setElementType(namespaceURI, localName);
        }
        
        @Override
        public void setAttributeType(String namespaceURI, String localName) throws SAXException {
            delegate.setAttributeType(namespaceURI, localName);
        }
        
        @Override
        public void atomicValue(XPathValue value) throws SAXException {
            outputWritten = true;
            delegate.atomicValue(value);
        }
        
        @Override
        public boolean isAtomicValuePending() {
            return delegate.isAtomicValuePending();
        }
        
        @Override
        public void setAtomicValuePending(boolean pending) throws SAXException {
            delegate.setAtomicValuePending(pending);
        }
        
        @Override
        public boolean isInAttributeContent() {
            return delegate.isInAttributeContent();
        }
        
        @Override
        public void setInAttributeContent(boolean inAttr) throws SAXException {
            delegate.setInAttributeContent(inAttr);
        }
    }
    
    /**
     * Extracts the error code from an exception, walking the cause chain.
     * Checks XPathException.getErrorCode() first, then falls back to
     * parsing error codes from exception messages.
     * Returns codes in prefixed form (e.g. "err:FOAR0001") for standard
     * XPath/XSLT error codes so that namespace-aware matching works correctly.
     */
    private String extractErrorCode(Throwable e) {
        Throwable current = e;
        while (current != null) {
            if (current instanceof XPathException) {
                String xpCode = ((XPathException) current).getErrorCode();
                if (xpCode != null && !xpCode.isEmpty()) {
                    return prefixStandardCode(xpCode);
                }
            }
            String code = extractCodeFromMessage(current.getMessage());
            if (code != null) {
                return prefixStandardCode(code);
            }
            Throwable next = null;
            if (current instanceof SAXException) {
                next = ((SAXException) current).getException();
            }
            if (next == null) {
                next = current.getCause();
            }
            if (next == current) {
                break;
            }
            current = next;
        }
        return null;
    }

    /**
     * Adds the "err:" prefix to standard XSLT/XPath error codes (4 letters + 4 digits)
     * since they belong to the http://www.w3.org/2005/xqt-errors namespace.
     * Non-standard codes are returned as-is.
     */
    private String prefixStandardCode(String code) {
        if (code.startsWith("err:") || code.startsWith("Q{")) {
            return code;
        }
        if (isErrorCode(code)) {
            return "err:" + code;
        }
        return code;
    }

    /**
     * Returns the local part of an error code for binding to $err:code.
     * Strips namespace prefixes like "err:" from the code.
     */
    private String errorCodeLocalPart(String errorCode) {
        if (errorCode == null) {
            return "";
        }
        int colonIdx = errorCode.indexOf(':');
        if (colonIdx > 0) {
            return errorCode.substring(colonIdx + 1);
        }
        return errorCode;
    }

    /**
     * Extracts an error code from a single message string.
     * Looks for patterns like "XTDE0540:" or "FORG0001:" anywhere in the message.
     */
    private String extractCodeFromMessage(String message) {
        if (message == null) {
            return null;
        }

        // Look for error codes at the start: "XTDE0540: ..." or "XTDE0540 ..."
        int colonIdx = message.indexOf(':');
        int spaceIdx = message.indexOf(' ');
        int endIdx = -1;

        if (colonIdx > 0 && colonIdx < 12) {
            endIdx = colonIdx;
        } else if (spaceIdx > 0 && spaceIdx < 12) {
            endIdx = spaceIdx;
        }

        if (endIdx > 0) {
            String potential = message.substring(0, endIdx);
            if (isErrorCode(potential)) {
                return potential;
            }
        }

        // Also scan the message for embedded error codes (e.g., "... FORG0001: ...")
        int len = message.length();
        for (int i = 0; i < len - 7; i++) {
            char c = message.charAt(i);
            if (c >= 'A' && c <= 'Z') {
                int end = i + 8;
                if (end <= len) {
                    String candidate = message.substring(i, end);
                    if (isErrorCode(candidate)) {
                        return candidate;
                    }
                }
            }
        }
        return null;
    }

    private boolean isErrorCode(String s) {
        if (s.length() < 8) {
            return false;
        }
        for (int i = 0; i < 4; i++) {
            char c = s.charAt(i);
            if (c < 'A' || c > 'Z') {
                return false;
            }
        }
        for (int i = 4; i < 8; i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }
    
    /**
     * Handles an error by finding a matching catch block.
     */
    private void handleError(String errorCode, TransformContext context, 
                             OutputHandler output, Throwable e) throws SAXException {
        // Find a matching catch block
        for (CatchNode catchBlock : catchBlocks) {
            if (catchBlock.matchesError(errorCode)) {
                // Bind XSLT 3.0 error variables in the err namespace
                TransformContext catchContext = context.pushVariableScope();
                String errNs = "http://www.w3.org/2005/xqt-errors";
                String codeStr = errorCode != null ? errorCode : "";
                String desc = (e.getMessage() != null) ? e.getMessage() : "";
                catchContext.getVariableScope().bind(errNs, "code", new XPathString(codeStr));
                catchContext.getVariableScope().bind(errNs, "description", new XPathString(desc));
                catchContext.getVariableScope().bind(errNs, "value", new XPathString(""));
                catchContext.getVariableScope().bind(errNs, "module", new XPathString(""));
                catchContext.getVariableScope().bind(errNs, "line-number", new XPathString("0"));
                catchContext.getVariableScope().bind(errNs, "column-number", new XPathString("0"));
                catchBlock.execute(catchContext, output);
                return;
            }
        }
        // No matching catch - if there are catch blocks with filters, rethrow
        // If there's a catch-all (empty errors attr), it would have matched
        if (!catchBlocks.isEmpty()) {
            // Check if any catch has no error filter (catch-all)
            boolean hasCatchAll = false;
            for (CatchNode c : catchBlocks) {
                if (c.getErrorCodes() == null || c.getErrorCodes().isEmpty()) {
                    hasCatchAll = true;
                    break;
                }
            }
            if (!hasCatchAll) {
                // No catch-all, rethrow the error
                if (e instanceof SAXException) {
                    throw (SAXException) e;
                } else if (e instanceof RuntimeException) {
                    throw (RuntimeException) e;
                }
            }
        }
        // No catch blocks or only filtered ones that didn't match - swallow silently
    }

    /**
     * OutputHandler that records events for later replay.
     * Used to buffer xsl:try output so it can be discarded on error.
     */
    private static class RecordingOutputHandler implements OutputHandler {

        private final List<Event> events = new ArrayList<>();
        private boolean atomicValuePending = false;
        private boolean inAttributeContent = false;

        void replayTo(OutputHandler target) throws SAXException {
            for (Event event : events) {
                event.replayTo(target);
            }
        }

        @Override
        public void startDocument() throws SAXException {
            events.add(new Event() {
                @Override
                void replayTo(OutputHandler h) throws SAXException {
                    h.startDocument();
                }
            });
        }

        @Override
        public void endDocument() throws SAXException {
            events.add(new Event() {
                @Override
                void replayTo(OutputHandler h) throws SAXException {
                    h.endDocument();
                }
            });
        }

        @Override
        public void startElement(String uri, String localName, String qName)
                throws SAXException {
            final String u = uri;
            final String l = localName;
            final String q = qName;
            events.add(new Event() {
                @Override
                void replayTo(OutputHandler h) throws SAXException {
                    h.startElement(u, l, q);
                }
            });
        }

        @Override
        public void endElement(String uri, String localName, String qName)
                throws SAXException {
            final String u = uri;
            final String l = localName;
            final String q = qName;
            events.add(new Event() {
                @Override
                void replayTo(OutputHandler h) throws SAXException {
                    h.endElement(u, l, q);
                }
            });
        }

        @Override
        public void attribute(String uri, String localName, String qName,
                String value) throws SAXException {
            final String u = uri;
            final String l = localName;
            final String q = qName;
            final String v = value;
            events.add(new Event() {
                @Override
                void replayTo(OutputHandler h) throws SAXException {
                    h.attribute(u, l, q, v);
                }
            });
        }

        @Override
        public void namespace(String prefix, String uri) throws SAXException {
            final String p = prefix;
            final String u = uri;
            events.add(new Event() {
                @Override
                void replayTo(OutputHandler h) throws SAXException {
                    h.namespace(p, u);
                }
            });
        }

        @Override
        public void characters(String text) throws SAXException {
            final String t = text;
            events.add(new Event() {
                @Override
                void replayTo(OutputHandler h) throws SAXException {
                    h.characters(t);
                }
            });
        }

        @Override
        public void charactersRaw(String text) throws SAXException {
            final String t = text;
            events.add(new Event() {
                @Override
                void replayTo(OutputHandler h) throws SAXException {
                    h.charactersRaw(t);
                }
            });
        }

        @Override
        public void comment(String text) throws SAXException {
            final String t = text;
            events.add(new Event() {
                @Override
                void replayTo(OutputHandler h) throws SAXException {
                    h.comment(t);
                }
            });
        }

        @Override
        public void processingInstruction(String target, String data)
                throws SAXException {
            final String tgt = target;
            final String d = data;
            events.add(new Event() {
                @Override
                void replayTo(OutputHandler h) throws SAXException {
                    h.processingInstruction(tgt, d);
                }
            });
        }

        @Override
        public void flush() throws SAXException {
            events.add(new Event() {
                @Override
                void replayTo(OutputHandler h) throws SAXException {
                    h.flush();
                }
            });
        }

        @Override
        public void setElementType(String namespaceURI, String localName)
                throws SAXException {
            final String u = namespaceURI;
            final String l = localName;
            events.add(new Event() {
                @Override
                void replayTo(OutputHandler h) throws SAXException {
                    h.setElementType(u, l);
                }
            });
        }

        @Override
        public void setAttributeType(String namespaceURI, String localName)
                throws SAXException {
            final String u = namespaceURI;
            final String l = localName;
            events.add(new Event() {
                @Override
                void replayTo(OutputHandler h) throws SAXException {
                    h.setAttributeType(u, l);
                }
            });
        }

        @Override
        public void atomicValue(XPathValue value) throws SAXException {
            final XPathValue v = value;
            events.add(new Event() {
                @Override
                void replayTo(OutputHandler h) throws SAXException {
                    h.atomicValue(v);
                }
            });
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
        public void setInAttributeContent(boolean inAttr) {
            this.inAttributeContent = inAttr;
        }

        private abstract static class Event {
            abstract void replayTo(OutputHandler h) throws SAXException;
        }
    }
}

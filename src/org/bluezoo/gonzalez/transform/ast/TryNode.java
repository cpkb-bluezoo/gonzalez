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
    
    public TryNode(XSLTNode tryContent, List<CatchNode> catchBlocks) {
        this.tryContent = tryContent;
        this.catchBlocks = catchBlocks != null ? catchBlocks : Collections.emptyList();
    }
    
    @Override public String getInstructionName() { return "try"; }
    
    @Override
    public void execute(TransformContext context, OutputHandler output) throws SAXException {
        RecordingOutputHandler buffer = new RecordingOutputHandler();
        try {
            if (tryContent != null) {
                tryContent.execute(context, buffer);
            }
            buffer.replayTo(output);
        } catch (SAXException e) {
            handleError(extractErrorCode(e), context, output, e);
        } catch (RuntimeException e) {
            handleError(extractErrorCode(e), context, output, e);
        }
    }
    
    /**
     * Extracts the error code from an exception message.
     * Looks for patterns like "XTDE0540:" or just "XTDE0540" at the start.
     */
    private String extractErrorCode(Throwable e) {
        String message = e.getMessage();
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
            // Validate it looks like an error code (letters + digits)
            if (potential.matches("[A-Z]{4}[0-9]{4}[a-z]*")) {
                return potential;
            }
        }
        return null;
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
                String code = (errorCode != null) ? errorCode : "";
                String desc = (e.getMessage() != null) ? e.getMessage() : "";
                catchContext.getVariableScope().bind(errNs, "code", new XPathString(code));
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

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

import java.util.Collections;
import java.util.List;

import org.xml.sax.SAXException;

import org.bluezoo.gonzalez.transform.runtime.OutputHandler;
import org.bluezoo.gonzalez.transform.runtime.TransformContext;
import org.bluezoo.gonzalez.transform.xpath.type.XPathString;

/**
 * TryNode XSLT instruction.
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
        try {
            if (tryContent != null) {
                tryContent.execute(context, output);
            }
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
}

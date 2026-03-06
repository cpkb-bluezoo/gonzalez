/*
 * CatchNode.java
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

import org.xml.sax.SAXException;

import org.bluezoo.gonzalez.transform.runtime.OutputHandler;
import org.bluezoo.gonzalez.transform.runtime.TransformContext;
import org.bluezoo.gonzalez.transform.xpath.XPathExpression;
import org.bluezoo.gonzalez.transform.xpath.expr.XPathException;
import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;

/**
 * CatchNode XSLT instruction.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public class CatchNode extends XSLTInstruction {
    private final XSLTNode content;
    private final String errorCodes;
    private final XPathExpression selectExpr;
    
    public CatchNode(XSLTNode content, String errorCodes, XPathExpression selectExpr) {
        this.content = content;
        this.errorCodes = errorCodes;
        this.selectExpr = selectExpr;
    }
    
    @Override public String getInstructionName() { return "catch"; }
    
    public XSLTNode getContent() { return content; }
    public String getErrorCodes() { return errorCodes; }
    
    /**
     * Checks if an error code matches this catch block.
     * Error codes are stored in prefixed form (e.g. "err:FOAR0001") for standard
     * XPath/XSLT codes. Unprefixed codes in the errors attribute mean no-namespace
     * and do not match prefixed (err-namespace) error codes.
     *
     * @param errorCode the error code from the exception (e.g. "err:FOAR0001")
     * @return true if this catch should handle the error
     */
    public boolean matchesError(String errorCode) {
        if (errorCodes == null || errorCodes.isEmpty()) {
            return true;
        }
        String[] parts = errorCodes.split("\\s+");
        for (int i = 0; i < parts.length; i++) {
            String filter = parts[i].trim();
            if (filter.isEmpty()) {
                continue;
            }
            if ("*".equals(filter)) {
                return true;
            }
            if (errorCode == null) {
                continue;
            }
            if (filter.startsWith("Q{")) {
                int closeIdx = filter.indexOf('}');
                if (closeIdx > 0) {
                    String uri = filter.substring(2, closeIdx);
                    String local = filter.substring(closeIdx + 1);
                    if (matchesQName(errorCode, uri, local)) {
                        return true;
                    }
                }
                continue;
            }
            int colonIdx = filter.indexOf(':');
            if (colonIdx > 0) {
                String filterLocal = filter.substring(colonIdx + 1);
                String errorLocal = errorCode;
                int errorColonIdx = errorCode.indexOf(':');
                if (errorColonIdx > 0) {
                    errorLocal = errorCode.substring(errorColonIdx + 1);
                }
                if (filterLocal.equals(errorLocal)) {
                    return true;
                }
            } else {
                // Unprefixed filter: only matches unprefixed error codes (no namespace)
                if (errorCode.indexOf(':') < 0 && errorCode.equals(filter)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean matchesQName(String errorCode, String uri, String local) {
        String errNs = "http://www.w3.org/2005/xqt-errors";
        if (errNs.equals(uri)) {
            String errorLocal = errorCode;
            int colonIdx = errorCode.indexOf(':');
            if (colonIdx > 0) {
                errorLocal = errorCode.substring(colonIdx + 1);
            }
            return local.equals(errorLocal);
        }
        return errorCode.equals(local);
    }
    
    @Override
    public void execute(TransformContext context, OutputHandler output) throws SAXException {
        if (selectExpr != null) {
            try {
                XPathValue result = selectExpr.evaluate(context);
                if (result != null) {
                    String str = result.asString();
                    if (str != null && !str.isEmpty()) {
                        output.characters(str);
                    }
                }
            } catch (XPathException e) {
                throw new SAXException(e.getMessage(), e);
            }
        } else if (content != null) {
            content.execute(context, output);
        }
    }
}

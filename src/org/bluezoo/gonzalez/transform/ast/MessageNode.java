/*
 * MessageNode.java
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

import org.bluezoo.gonzalez.transform.compiler.AttributeValueTemplate;
import org.bluezoo.gonzalez.transform.runtime.BufferOutputHandler;
import org.bluezoo.gonzalez.transform.runtime.OutputHandler;
import org.bluezoo.gonzalez.transform.runtime.SAXEventBuffer;
import org.bluezoo.gonzalez.transform.runtime.TransformContext;
import org.bluezoo.gonzalez.transform.xpath.XPathExpression;
import org.bluezoo.gonzalez.transform.xpath.expr.XPathException;
import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;
import javax.xml.transform.ErrorListener;
import javax.xml.transform.TransformerException;

/**
 * MessageNode XSLT instruction.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public class MessageNode extends XSLTInstruction {
    private final SequenceNode content;
    private final XPathExpression selectExpr;
    private final boolean terminateStatic;
    private final AttributeValueTemplate terminateAvt;
    private final String errorCode;
    
    public MessageNode(SequenceNode content, XPathExpression selectExpr, 
               boolean terminateStatic, AttributeValueTemplate terminateAvt, String errorCode) {
        this.content = content;
        this.selectExpr = selectExpr;
        this.terminateStatic = terminateStatic;
        this.terminateAvt = terminateAvt;
        this.errorCode = errorCode;
    }
    
    @Override public String getInstructionName() { return "message"; }
    
    @Override public void execute(TransformContext context, 
                                  OutputHandler output) throws SAXException {
        // Evaluate terminate value
        boolean terminate = terminateStatic;
        if (terminateAvt != null) {
            try {
                String terminateValue = terminateAvt.evaluate(context).trim().toLowerCase();
                if ("yes".equals(terminateValue) || "true".equals(terminateValue) || "1".equals(terminateValue)) {
                    terminate = true;
                } else if ("no".equals(terminateValue) || "false".equals(terminateValue) || "0".equals(terminateValue) || terminateValue.isEmpty()) {
                    terminate = false;
                } else {
                    // XTDE0030: Invalid AVT value for terminate attribute
                    throw new SAXException("XTDE0030: Invalid runtime value for terminate attribute: '" + 
                                          terminateValue + "'. Must evaluate to 'yes' or 'no'");
                }
            } catch (XPathException e) {
                throw new SAXException("Error evaluating terminate AVT: " + e.getMessage(), e);
            }
        }
        
        String message = buildMessage(context);
        
        // Get the error listener from context if available
        ErrorListener errorListener = context.getErrorListener();
        
        if (errorListener != null) {
            // Use ErrorListener for proper message handling
            try {
                String location = getLocationInfo(context);
                TransformerException te = 
                    new TransformerException(message);
                
                if (terminate) {
                    errorListener.fatalError(te);
                } else {
                    errorListener.warning(te);
                }
            } catch (TransformerException e) {
                // ErrorListener may re-throw
                throw new SAXException(e);
            }
        } else {
            // Default: output to stderr
            StringBuilder sb = new StringBuilder();
            if (errorCode != null) {
                sb.append("[");
                sb.append(errorCode);
                sb.append("] ");
            }
            sb.append(message);
            System.err.println("XSLT Message: " + sb.toString());
        }
        
        if (terminate) {
            String terminateMsg = message;
            if (errorCode != null) {
                terminateMsg = "[" + errorCode + "] " + message;
            }
            throw new SAXException("Transformation terminated by xsl:message: " + terminateMsg);
        }
    }
    
    private String buildMessage(TransformContext context) throws SAXException {
        // If select attribute is present, evaluate it
        if (selectExpr != null) {
            try {
                XPathValue result = selectExpr.evaluate(context);
                return result.asString();
            } catch (XPathException e) {
                throw new SAXException("Error evaluating xsl:message select: " + e.getMessage(), e);
            }
        }
        
        // Otherwise, use content
        if (content != null && !content.isEmpty()) {
            SAXEventBuffer buffer = new SAXEventBuffer();
            content.execute(context, new BufferOutputHandler(buffer));
            return buffer.getTextContent();
        }
        
        return "";
    }
    
    private String getLocationInfo(TransformContext context) {
        // Could be enhanced to include actual source location
        return "xsl:message";
    }
}

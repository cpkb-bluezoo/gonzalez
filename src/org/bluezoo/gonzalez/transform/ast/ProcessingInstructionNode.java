/*
 * ProcessingInstructionNode.java
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

/**
 * ProcessingInstructionNode XSLT instruction.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public class ProcessingInstructionNode extends XSLTInstruction {
    private final AttributeValueTemplate nameAvt;
    private final XPathExpression selectExpr;
    private final SequenceNode content;
    public ProcessingInstructionNode(AttributeValueTemplate nameAvt, XPathExpression selectExpr, SequenceNode content) {
        this.nameAvt = nameAvt;
        this.selectExpr = selectExpr;
        this.content = content;
    }
    @Override public String getInstructionName() { return "processing-instruction"; }
    @Override public void execute(TransformContext context, 
                                  OutputHandler output) throws SAXException {
        try {
            String target = nameAvt.evaluate(context);
            
            // XTDE0890: PI target must be a valid NCName and not "xml" (case-insensitive)
            if (!ElementNode.isValidNCName(target)) {
                throw new SAXException("XTDE0890: '" + target +
                    "' is not a valid NCName for xsl:processing-instruction");
            }
            if ("xml".equalsIgnoreCase(target)) {
                throw new SAXException("XTDE0890: Processing instruction target must " +
                    "not be 'xml'");
            }
            
            String data = "";
            
            // select attribute takes precedence over content (XSLT 2.0+)
            if (selectExpr != null) {
                XPathValue result = selectExpr.evaluate(context);
                data = result != null ? result.asString() : "";
            } else if (content != null) {
                SAXEventBuffer buffer = 
                    new SAXEventBuffer();
                content.execute(context, new BufferOutputHandler(buffer));
                data = buffer.getTextContent();
            }
            // Per XSLT spec: insert space in "?>" sequence to ensure well-formed XML
            data = sanitizePIData(data);
            output.processingInstruction(target, data);
        } catch (XPathException e) {
            throw new SAXException("Error in xsl:processing-instruction", e);
        }
    }
    
    /**
     * Sanitizes PI data to ensure well-formed XML output.
     * XML processing instructions cannot contain "?>".
     */
    private static String sanitizePIData(String data) {
        if (data == null || data.isEmpty()) {
            return data;
        }
        // Replace all "?>" with "? >" (insert space between ? and >)
        return data.replace("?>", "? >");
    }
}

/*
 * OnEmptyNode.java
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
 * OnEmptyNode XSLT instruction.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public class OnEmptyNode extends XSLTInstruction {
    private final XSLTNode content;
    private final XPathExpression selectExpr;
    
    public OnEmptyNode(XSLTNode content) {
        this.content = content;
        this.selectExpr = null;
    }
    
    public OnEmptyNode(XPathExpression selectExpr) {
        this.content = null;
        this.selectExpr = selectExpr;
    }
    
    @Override public String getInstructionName() { return "on-empty"; }
    
    @Override
    public void execute(TransformContext context, OutputHandler output) throws SAXException {
        // Note: xsl:on-empty is handled specially by the sequence constructor
        // This execution is for when the sequence is indeed empty
        if (selectExpr != null) {
            try {
                XPathValue result = selectExpr.evaluate(context);
                if (result != null) {
                    String str = result.asString();
                    if (!str.isEmpty()) {
                        output.characters(str);
                    }
                }
            } catch (XPathException e) {
                throw new SAXException("Error evaluating xsl:on-empty select", e);
            }
        } else if (content != null) {
            content.execute(context, output);
        }
    }
}

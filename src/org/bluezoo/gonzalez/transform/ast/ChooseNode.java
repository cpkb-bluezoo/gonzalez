/*
 * ChooseNode.java
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

import java.util.List;

import org.xml.sax.SAXException;

import org.bluezoo.gonzalez.transform.runtime.OutputHandler;
import org.bluezoo.gonzalez.transform.runtime.TransformContext;
import org.bluezoo.gonzalez.transform.xpath.expr.XPathException;

/**
 * ChooseNode XSLT instruction.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public class ChooseNode extends XSLTInstruction {
    private final List<WhenNode> whens;
    private final SequenceNode otherwise;
    public ChooseNode(List<WhenNode> whens, SequenceNode otherwise) {
        this.whens = whens;
        this.otherwise = otherwise;
    }
    @Override public String getInstructionName() { return "choose"; }
    @Override public void execute(TransformContext context, 
                                  OutputHandler output) throws SAXException {
        try {
            for (WhenNode when : whens) {
                if (when.getTestExpr().evaluate(context).asBoolean()) {
                    when.getContent().execute(context, output);
                    return;
                }
            }
            // No when matched - execute otherwise
            if (otherwise != null) {
                otherwise.execute(context, output);
            }
        } catch (XPathException e) {
            throw new SAXException("Error in xsl:choose", e);
        }
    }
}

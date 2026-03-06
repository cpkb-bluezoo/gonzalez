/*
 * CommentNode.java
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
import java.util.List;

import org.xml.sax.SAXException;

import org.bluezoo.gonzalez.transform.compiler.ExpressionHolder;
import org.bluezoo.gonzalez.transform.runtime.BufferOutputHandler;
import org.bluezoo.gonzalez.transform.runtime.ItemCollectorOutputHandler;
import org.bluezoo.gonzalez.transform.runtime.OutputHandler;
import org.bluezoo.gonzalez.transform.runtime.SAXEventBuffer;
import org.bluezoo.gonzalez.transform.runtime.TransformContext;
import org.bluezoo.gonzalez.transform.xpath.XPathExpression;
import org.bluezoo.gonzalez.transform.xpath.expr.XPathException;
import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;

/**
 * CommentNode XSLT instruction.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public class CommentNode extends XSLTInstruction implements ExpressionHolder {
    private final XPathExpression selectExpr;
    private final SequenceNode content;

    public CommentNode(XPathExpression selectExpr, SequenceNode content) {
        this.selectExpr = selectExpr;
        this.content = content;
    }

    @Override public String getInstructionName() { return "comment"; }

    @Override
    public List<XPathExpression> getExpressions() {
        List<XPathExpression> exprs = new ArrayList<XPathExpression>();
        if (selectExpr != null) {
            exprs.add(selectExpr);
        }
        return exprs;
    }

    @Override
    public void execute(TransformContext context,
                        OutputHandler output) throws SAXException {
        try {
            String text;
            if (selectExpr != null) {
                // select attribute: atomize the result and string-join with single space
                XPathValue result = selectExpr.evaluate(context);
                text = result != null ? joinItemsWithSpace(result) : "";
            } else if (content != null) {
                // sequence constructor: collect items and join with space
                ItemCollectorOutputHandler collector = new ItemCollectorOutputHandler();
                content.execute(context, collector);
                text = collector.getContentAsString(" ");
            } else {
                text = "";
            }
            // Per XSLT spec: insert space after any "--" sequence and before trailing "-"
            // to ensure well-formed XML output
            text = sanitizeCommentText(text);
            output.comment(text);
        } catch (XPathException e) {
            throw new SAXException("Error in xsl:comment", e);
        }
    }

    /**
     * Converts an XPathValue result to a string by atomizing items and joining
     * with a single space between adjacent items per XSLT spec for xsl:comment select.
     */
    private static String joinItemsWithSpace(XPathValue result) {
        if (result.isSequence()) {
            org.bluezoo.gonzalez.transform.xpath.type.XPathSequence seq =
                (org.bluezoo.gonzalez.transform.xpath.type.XPathSequence) result;
            StringBuilder sb = new StringBuilder();
            boolean first = true;
            for (XPathValue item : seq.getItems()) {
                String s = item.asString();
                if (s == null || s.isEmpty()) {
                    continue;
                }
                if (!first) {
                    sb.append(' ');
                }
                sb.append(s);
                first = false;
            }
            return sb.toString();
        }
        if (result.isNodeSet()) {
            org.bluezoo.gonzalez.transform.xpath.type.XPathNodeSet ns = result.asNodeSet();
            StringBuilder sb = new StringBuilder();
            boolean first = true;
            for (org.bluezoo.gonzalez.transform.xpath.type.XPathNode node : ns) {
                String s = node.getStringValue();
                if (s == null || s.isEmpty()) {
                    continue;
                }
                if (!first) {
                    sb.append(' ');
                }
                sb.append(s);
                first = false;
            }
            return sb.toString();
        }
        return result.asString();
    }
    
    /**
     * Sanitizes comment text to ensure well-formed XML output.
     * XML comments cannot contain "--" or end with "-".
     */
    private static String sanitizeCommentText(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        // Replace all "--" with "- -" (insert space between adjacent hyphens)
        StringBuilder sb = new StringBuilder();
        char prev = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '-' && prev == '-') {
                sb.append(' '); // Insert space before this hyphen
            }
            sb.append(c);
            prev = c;
        }
        // If comment ends with "-", append a space
        if (sb.length() > 0 && sb.charAt(sb.length() - 1) == '-') {
            sb.append(' ');
        }
        return sb.toString();
    }
}

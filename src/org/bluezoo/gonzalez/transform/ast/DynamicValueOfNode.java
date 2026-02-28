/*
 * DynamicValueOfNode.java
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

import org.bluezoo.gonzalez.transform.compiler.AttributeValueTemplate;
import org.bluezoo.gonzalez.transform.runtime.OutputHandler;
import org.bluezoo.gonzalez.transform.runtime.TransformContext;
import org.bluezoo.gonzalez.transform.xpath.XPathExpression;
import org.bluezoo.gonzalez.transform.xpath.expr.XPathException;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNode;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNodeSet;
import org.bluezoo.gonzalez.transform.xpath.type.XPathSequence;
import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;
import org.xml.sax.SAXException;

import java.util.Iterator;

/**
 * Dynamic xsl:value-of backed by a shadow _select attribute.
 *
 * <p>At runtime, evaluates the shadow AVT to obtain an XPath expression
 * string, compiles it, evaluates it, and outputs the string result.
 * This supports XSLT 3.0 shadow attributes where the select expression
 * is computed dynamically via an AVT.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class DynamicValueOfNode implements XSLTNode {

    private final AttributeValueTemplate selectAvt;
    private final boolean disableEscaping;
    private final String separator;
    private String lastXPathString;
    private XPathExpression lastCompiledExpr;

    public DynamicValueOfNode(AttributeValueTemplate selectAvt, 
                               boolean disableEscaping, String separator) {
        this.selectAvt = selectAvt;
        this.disableEscaping = disableEscaping;
        this.separator = separator;
    }

    @Override
    public void execute(TransformContext context, OutputHandler output) throws SAXException {
        try {
            String xpathString = selectAvt.evaluate(context);
            if (xpathString == null || xpathString.isEmpty()) {
                return;
            }

            // Compile with single-entry cache
            XPathExpression dynamicExpr;
            if (xpathString.equals(lastXPathString)) {
                dynamicExpr = lastCompiledExpr;
            } else {
                try {
                    dynamicExpr = XPathExpression.compile(xpathString, null);
                    lastXPathString = xpathString;
                    lastCompiledExpr = dynamicExpr;
                } catch (Exception e) {
                    throw new SAXException("XPST0003: Invalid XPath in dynamic value-of: " + 
                        e.getMessage(), e);
                }
            }

            XPathValue result = dynamicExpr.evaluate(context);
            if (result == null) {
                return;
            }

            String value = resultToString(result);
            if (value != null && !value.isEmpty()) {
                if (disableEscaping) {
                    output.charactersRaw(value);
                } else {
                    output.characters(value);
                }
            }
        } catch (XPathException e) {
            throw new SAXException("Error in dynamic value-of: " + e.getMessage(), e);
        }
    }

    private String resultToString(XPathValue result) {
        if (result.isNodeSet()) {
            XPathNodeSet nodeSet = result.asNodeSet();
            if (nodeSet.isEmpty()) {
                return "";
            }
            String sep = (separator != null) ? separator : " ";
            StringBuilder sb = new StringBuilder();
            boolean first = true;
            for (XPathNode node : nodeSet) {
                if (!first) {
                    sb.append(sep);
                }
                sb.append(node.getStringValue());
                first = false;
            }
            return sb.toString();
        }
        if (result.isSequence()) {
            XPathSequence seq = (XPathSequence) result;
            if (seq.isEmpty()) {
                return "";
            }
            String sep = (separator != null) ? separator : " ";
            StringBuilder sb = new StringBuilder();
            boolean first = true;
            Iterator<XPathValue> iter = seq.iterator();
            while (iter.hasNext()) {
                if (!first) {
                    sb.append(sep);
                }
                sb.append(iter.next().asString());
                first = false;
            }
            return sb.toString();
        }
        return result.asString();
    }
}

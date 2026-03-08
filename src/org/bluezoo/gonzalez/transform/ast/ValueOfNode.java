/*
 * ValueOfNode.java
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

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import org.xml.sax.SAXException;

import org.bluezoo.gonzalez.transform.ErrorHandlingMode;
import org.bluezoo.gonzalez.transform.compiler.AttributeValueTemplate;
import org.bluezoo.gonzalez.transform.compiler.ExpressionHolder;
import org.bluezoo.gonzalez.transform.runtime.OutputHandler;
import org.bluezoo.gonzalez.transform.runtime.TransformContext;
import org.bluezoo.gonzalez.transform.xpath.XPathExpression;
import org.bluezoo.gonzalez.transform.xpath.expr.XPathException;
import org.bluezoo.gonzalez.transform.xpath.type.NodeType;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNode;
import org.bluezoo.gonzalez.transform.xpath.type.XPathMap;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNodeSet;
import org.bluezoo.gonzalez.transform.xpath.type.XPathSequence;
import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;

/**
 * ValueOfNode XSLT instruction.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public class ValueOfNode extends XSLTInstruction implements ExpressionHolder {
    private final XPathExpression selectExpr;
    private final boolean disableEscaping;
    private final AttributeValueTemplate separatorAvt;  // null means use default
    private final boolean xslt2Plus; // XSLT 2.0+ outputs all items, 1.0 only first
    
    public ValueOfNode(XPathExpression selectExpr, boolean disableEscaping, String separator, boolean xslt2Plus) {
        this.selectExpr = selectExpr;
        this.disableEscaping = disableEscaping;
        this.separatorAvt = separator != null ? AttributeValueTemplate.literal(separator) : null;
        this.xslt2Plus = xslt2Plus;
    }
    
    public ValueOfNode(XPathExpression selectExpr, boolean disableEscaping,
            AttributeValueTemplate separatorAvt, boolean xslt2Plus) {
        this.selectExpr = selectExpr;
        this.disableEscaping = disableEscaping;
        this.separatorAvt = separatorAvt;
        this.xslt2Plus = xslt2Plus;
    }
    
    @Override public String getInstructionName() { return "value-of"; }

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
            // Use instruction's static base URI if set (for xml:base support)
            TransformContext evalContext = (staticBaseURI != null) 
                ? context.withStaticBaseURI(staticBaseURI) 
                : context;
                
            XPathValue result = selectExpr.evaluate(evalContext);
            if (result == null) {
                return;
            }
            
            // FOTY0013: Maps and arrays cannot be atomized
            checkNotFunctionItem(result);
            
            // Evaluate separator AVT at runtime
            String separator = separatorAvt != null ? separatorAvt.evaluate(context) : null;
            
            // Check for atomization errors (XTTE1540 / XTTE3090)
            ErrorHandlingMode errorMode = context.getErrorHandlingMode();
            if (!errorMode.isSilent()) {
                if (result.isSequence()) {
                    XPathSequence seq = (XPathSequence) result;
                    if (!xslt2Plus && separator == null && seq.size() > 1) {
                        String msg = "xsl:value-of in XSLT 1.0 mode has sequence with " + 
                                    seq.size() + " items, only first will be used";
                        if (errorMode.isRecovery()) {
                            System.err.println("Warning [XTTE3090]: " + msg);
                        }
                    }
                }
            }
            
            String value;
            // Explicit separator attribute overrides the first-item rule
            // even in backwards-compatible mode
            boolean outputAllItems = xslt2Plus || separator != null;
            
            if (result.isNodeSet()) {
                XPathNodeSet nodeSet = result.asNodeSet();
                if (nodeSet.isEmpty()) {
                    return;
                }
                if (outputAllItems) {
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
                    value = sb.toString();
                } else {
                    value = nodeSet.iterator().next().getStringValue();
                }
            } else if (result.isSequence()) {
                XPathSequence seq = (XPathSequence) result;
                if (seq.isEmpty()) {
                    return;
                }
                if (outputAllItems) {
                    String sep = (separator != null) ? separator : " ";
                    StringBuilder sb = new StringBuilder();
                    boolean first = true;
                    boolean lastWasTextNode = false;
                    for (XPathValue item : seq.getItems()) {
                        boolean isTextNode = isTextNodeItem(item);
                        // Per XSLT spec: zero-length text nodes are removed
                        if (isTextNode && item.asString().isEmpty()) {
                            continue;
                        }
                        if (isTextNode && lastWasTextNode) {
                            // Adjacent text nodes are merged (no separator between them)
                            sb.append(item.asString());
                        } else {
                            if (!first) {
                                sb.append(sep);
                            }
                            sb.append(item.asString());
                        }
                        first = false;
                        lastWasTextNode = isTextNode;
                    }
                    value = sb.toString();
                } else {
                    // XSLT 1.0: only output first item
                    value = seq.getItems().get(0).asString();
                }
            } else {
                // Single atomic value
                value = result.asString();
            }
            
            if (disableEscaping) {
                output.charactersRaw(value);
            } else {
                output.characters(value);
            }
        } catch (XPathException e) {
            throw new SAXException("XPath evaluation error", e);
        }
    }

    /**
     * Tests whether an XPath value is a text node.
     * Used for merging adjacent text nodes in sequences.
     */
    private boolean isTextNodeItem(XPathValue item) {
        if (item instanceof XPathNode) {
            XPathNode node = (XPathNode) item;
            return node.getNodeType() == NodeType.TEXT;
        }
        if (item instanceof XPathNodeSet) {
            XPathNodeSet ns = (XPathNodeSet) item;
            if (ns.size() == 1) {
                XPathNode node = ns.iterator().next();
                return node.getNodeType() == NodeType.TEXT;
            }
        }
        return false;
    }

    /**
     * FOTY0013: Maps are function items and cannot be atomized.
     * Arrays can be atomized (produces the atomized members) per XPath 3.1.
     */
    private void checkNotFunctionItem(XPathValue result) throws SAXException {
        if (result instanceof XPathMap) {
            throw new SAXException("FOTY0013: " +
                "An item of type map(*) has no string value");
        }
        if (result instanceof XPathSequence) {
            for (XPathValue item : (XPathSequence) result) {
                if (item instanceof XPathMap) {
                    throw new SAXException("FOTY0013: " +
                        "An item of type map(*) has no string value");
                }
            }
        }
    }
}

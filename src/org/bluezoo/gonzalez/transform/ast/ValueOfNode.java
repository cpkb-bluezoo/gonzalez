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

import org.xml.sax.SAXException;

import org.bluezoo.gonzalez.transform.ErrorHandlingMode;
import org.bluezoo.gonzalez.transform.runtime.OutputHandler;
import org.bluezoo.gonzalez.transform.runtime.TransformContext;
import org.bluezoo.gonzalez.transform.xpath.XPathExpression;
import org.bluezoo.gonzalez.transform.xpath.expr.XPathException;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNode;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNodeSet;
import org.bluezoo.gonzalez.transform.xpath.type.XPathSequence;
import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;

/**
 * ValueOfNode XSLT instruction.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public class ValueOfNode extends XSLTInstruction {
    private final XPathExpression selectExpr;
    private final boolean disableEscaping;
    private final String separator;  // null means use default (space for sequences in XSLT 2.0+)
    private final boolean xslt2Plus; // XSLT 2.0+ outputs all items, 1.0 only first
    
    public ValueOfNode(XPathExpression selectExpr, boolean disableEscaping, String separator, boolean xslt2Plus) {
        this.selectExpr = selectExpr;
        this.disableEscaping = disableEscaping;
        this.separator = separator;
        this.xslt2Plus = xslt2Plus;
    }
    
    @Override public String getInstructionName() { return "value-of"; }
    
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
            
            // Check for atomization errors (XTTE1540 / XTTE3090)
            ErrorHandlingMode errorMode = context.getErrorHandlingMode();
            if (!errorMode.isSilent()) {
                // Check if result is a sequence with multiple node items
                // This can cause atomization issues
                if (result.isSequence()) {
                    XPathSequence seq = (XPathSequence) result;
                    // In XSLT 2.0+, sequences are allowed and space-separated
                    // But we should still warn if it's not what was expected
                    if (!xslt2Plus && seq.size() > 1) {
                        String msg = "xsl:value-of in XSLT 1.0 mode has sequence with " + 
                                    seq.size() + " items, only first will be used";
                        if (errorMode.isRecovery()) {
                            System.err.println("Warning [XTTE3090]: " + msg);
                        }
                    }
                }
            }
            
            String value;
            if (result.isNodeSet()) {
                XPathNodeSet nodeSet = result.asNodeSet();
                if (nodeSet.isEmpty()) {
                    return;
                }
                if (xslt2Plus) {
                    // XSLT 2.0+: output all nodes with separator
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
                    // XSLT 1.0: only output first node
                    value = nodeSet.iterator().next().getStringValue();
                }
            } else if (result.isSequence()) {
                // XPath 2.0+ sequence
                XPathSequence seq = (XPathSequence) result;
                if (seq.isEmpty()) {
                    return;
                }
                if (xslt2Plus) {
                    String sep = (separator != null) ? separator : " ";
                    StringBuilder sb = new StringBuilder();
                    boolean first = true;
                    for (XPathValue item : seq.getItems()) {
                        if (!first) {
                            sb.append(sep);
                        }
                        sb.append(item.asString());
                        first = false;
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
            
            // Only output non-empty values to preserve empty element serialization
            if (!value.isEmpty()) {
                if (disableEscaping) {
                    output.charactersRaw(value);
                } else {
                    output.characters(value);
                }
            }
        } catch (XPathException e) {
            throw new SAXException("XPath evaluation error", e);
        }
    }
}

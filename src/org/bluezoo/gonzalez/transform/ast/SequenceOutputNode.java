/*
 * SequenceOutputNode.java
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
import java.util.Collections;

import java.util.List;

import org.xml.sax.SAXException;

import org.bluezoo.gonzalez.transform.compiler.ExpressionHolder;
import org.bluezoo.gonzalez.transform.compiler.SequenceBuilderOutputHandler;
import org.bluezoo.gonzalez.transform.runtime.OutputHandler;
import org.bluezoo.gonzalez.transform.runtime.TransformContext;
import org.bluezoo.gonzalez.transform.xpath.XPathExpression;
import org.bluezoo.gonzalez.transform.xpath.expr.XPathException;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNode;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNodeSet;
import org.bluezoo.gonzalez.transform.xpath.type.XPathResultTreeFragment;
import org.bluezoo.gonzalez.transform.xpath.type.XPathArray;
import org.bluezoo.gonzalez.transform.xpath.type.XPathFunctionItem;
import org.bluezoo.gonzalez.transform.xpath.type.XPathMap;
import org.bluezoo.gonzalez.transform.xpath.type.XPathSequence;
import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;

/**
 * SequenceOutputNode XSLT instruction.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public class SequenceOutputNode extends XSLTInstruction implements ExpressionHolder {
    private final XPathExpression selectExpr;
    public SequenceOutputNode(XPathExpression selectExpr) { this.selectExpr = selectExpr; }
    @Override public String getInstructionName() { return "sequence"; }

    @Override
    public List<XPathExpression> getExpressions() {
        List<XPathExpression> exprs = new ArrayList<XPathExpression>();
        if (selectExpr != null) {
            exprs.add(selectExpr);
        }
        return exprs;
    }
    @Override public void execute(TransformContext context, 
                                  OutputHandler output) throws SAXException {
        try {
            XPathValue result = selectExpr.evaluate(context);
            
            if (result == null) {
                return;
            }
            
            // When building a sequence, add items directly to preserve
            // node identity (xsl:sequence returns nodes by reference).
            // But NOT when inside an element - values must flow into element content.
            if (output instanceof SequenceBuilderOutputHandler) {
                SequenceBuilderOutputHandler seqBuilder = (SequenceBuilderOutputHandler) output;
                if (!seqBuilder.isInsideElement()) {
                    if (result instanceof XPathSequence) {
                        for (XPathValue item : (XPathSequence) result) {
                            seqBuilder.addItem(item);
                        }
                    } else if (result instanceof XPathNodeSet) {
                        for (XPathNode node : ((XPathNodeSet) result).getNodes()) {
                            seqBuilder.addItem(new XPathNodeSet(Collections.singletonList(node)));
                        }
                    } else {
                        seqBuilder.addItem(result);
                    }
                    return;
                }
            }
            
            if (result instanceof XPathResultTreeFragment) {
                // Result tree fragment - replay the buffered events
                XPathResultTreeFragment rtf = (XPathResultTreeFragment) result;
                rtf.replayToOutput(output);
                output.setAtomicValuePending(false);
            } else if (result instanceof XPathSequence) {
                // XPath 2.0+ sequence - output each item separately
                XPathSequence seq = (XPathSequence) result;
                boolean first = true;
                for (XPathValue item : seq) {
                    if (!first) {
                        // Signal item boundary for sequence builders
                        output.itemBoundary();
                    }
                    outputSequenceItem(item, output, first);
                    first = false;
                }
            } else if (result instanceof XPathNode) {
                // Single node item (e.g. SequenceTextItem from variable)
                outputNode((XPathNode) result, output);
                output.setAtomicValuePending(false);
            } else if (result instanceof XPathNodeSet) {
                // For node-sets, output nodes (similar to copy-of for simplicity)
                XPathNodeSet nodeSet = (XPathNodeSet) result;
                boolean first = true;
                for (XPathNode node : nodeSet.getNodes()) {
                    if (!first) {
                        output.itemBoundary();
                    }
                    outputNode(node, output);
                    first = false;
                }
                output.setAtomicValuePending(false);
            } else if (result instanceof XPathArray) {
                flattenArray((XPathArray) result, output);
            } else if (result instanceof XPathFunctionItem) {
                output.atomicValue(result);
            } else {
                // For atomic values (and maps), use atomicValue() which handles spacing
                output.atomicValue(result);
            }
        } catch (XPathException e) {
            throw new SAXException("Error in xsl:sequence", e);
        }
    }
    
    private void outputSequenceItem(XPathValue item, OutputHandler output, boolean first) throws SAXException {
        if (item instanceof XPathNodeSet) {
            XPathNodeSet nodeSet = (XPathNodeSet) item;
            for (XPathNode node : nodeSet.getNodes()) {
                outputNode(node, output);
            }
            output.setAtomicValuePending(false);
        } else if (item instanceof XPathResultTreeFragment) {
            ((XPathResultTreeFragment) item).replayToOutput(output);
            output.setAtomicValuePending(false);
        } else if (item instanceof XPathArray) {
            flattenArray((XPathArray) item, output);
        } else if (item instanceof XPathFunctionItem) {
            output.atomicValue(item);
        } else {
            // Atomic value (and maps) - use atomicValue() which handles spacing
            output.atomicValue(item);
        }
    }
    
    /**
     * Flattens an array recursively, outputting each member.
     * Per XSLT 3.0 spec section 20.1: arrays in content sequences
     * are replaced by their members, recursively.
     */
    private void flattenArray(XPathArray array, OutputHandler output) throws SAXException {
        for (XPathValue member : array.members()) {
            if (member instanceof XPathArray) {
                flattenArray((XPathArray) member, output);
            } else {
                outputSequenceItem(member, output, false);
            }
        }
    }
    
    private void checkForFunctionItems(XPathValue result) throws SAXException {
        if (isFunctionItem(result)) {
            throw new SAXException("XTDE0450: " +
                "A result tree cannot contain a function item");
        }
        if (result instanceof XPathSequence) {
            for (XPathValue item : (XPathSequence) result) {
                if (isFunctionItem(item)) {
                    throw new SAXException("XTDE0450: " +
                        "A result tree cannot contain a function item");
                }
            }
        }
    }
    
    private boolean isFunctionItem(XPathValue value) {
        return value instanceof XPathFunctionItem
            || value instanceof XPathMap
            || value instanceof XPathArray;
    }
    
    private void outputNode(XPathNode node, OutputHandler output) throws SAXException {
        ValueOutputHelper.outputNode(node, output);
    }
}

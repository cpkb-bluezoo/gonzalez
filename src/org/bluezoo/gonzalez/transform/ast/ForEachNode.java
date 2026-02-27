/*
 * ForEachNode.java
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
import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import org.xml.sax.SAXException;

import org.bluezoo.gonzalez.transform.compiler.SortSpec;
import org.bluezoo.gonzalez.transform.runtime.BasicTransformContext;
import org.bluezoo.gonzalez.transform.runtime.OutputHandler;
import org.bluezoo.gonzalez.transform.runtime.TransformContext;
import org.bluezoo.gonzalez.transform.xpath.Collation;
import org.bluezoo.gonzalez.transform.xpath.XPathExpression;
import org.bluezoo.gonzalez.transform.xpath.expr.XPathException;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNode;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNodeSet;
import org.bluezoo.gonzalez.transform.xpath.type.XPathSequence;
import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;

/**
 * ForEachNode XSLT instruction.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public class ForEachNode extends XSLTInstruction {
    private final XPathExpression selectExpr;
    private final List<SortSpec> sorts;
    private final SequenceNode body;
    public ForEachNode(XPathExpression selectExpr, List<SortSpec> sorts, SequenceNode body) {
        this.selectExpr = selectExpr;
        this.sorts = sorts;
        this.body = body;
    }
    @Override public String getInstructionName() { return "for-each"; }
    @Override public void execute(TransformContext context, 
                                  OutputHandler output) throws SAXException {
        try {
            XPathValue result = selectExpr.evaluate(context);
            if (result == null) {
                return;
            }
            
            // Handle node-set (XPath 1.0 style)
            if (result instanceof XPathNodeSet) {
                executeNodeSet((XPathNodeSet) result, context, output);
                return;
            }
            
            // Handle sequence (XPath 2.0+ style)
            if (result instanceof XPathSequence) {
                executeSequence((XPathSequence) result, context, output);
                return;
            }
            
            // Handle single atomic value
            executeAtomicValue(result, context, output);
            
        } catch (XPathException e) {
            throw new SAXException("Error in xsl:for-each", e);
        }
    }
    
    private void executeNodeSet(XPathNodeSet nodeSet, TransformContext context,
                               OutputHandler output) throws SAXException, XPathException {
        List<XPathNode> nodes = new ArrayList<>(nodeSet.getNodes());
        
        // Apply sorting if specified
        if (sorts != null && !sorts.isEmpty()) {
            sortNodesStatic(nodes, sorts, context);
        }
        
        int size = nodes.size();
        int position = 1;
        boolean first = true;
        for (XPathNode node : nodes) {
            // Mark boundary between iterations for sequence construction
            if (!first) {
                output.itemBoundary();
            }
            
            TransformContext iterContext = context.pushVariableScope();
            
            if (iterContext instanceof BasicTransformContext) {
                iterContext = ((BasicTransformContext) iterContext)
                    .withXsltCurrentNode(node).withPositionAndSize(position, size);
            } else {
                iterContext = iterContext.withContextNode(node).withPositionAndSize(position, size);
            }
            body.execute(iterContext, output);
            position++;
            first = false;
        }
    }
    
    private void executeSequence(XPathSequence sequence, TransformContext context,
                                OutputHandler output) throws SAXException, XPathException {
        List<XPathValue> items = sequence.getItems();
        int size = items.size();
        int position = 1;
        boolean first = true;
        
        for (XPathValue item : items) {
            // Mark boundary between iterations for sequence construction
            if (!first) {
                output.itemBoundary();
            }
            
            TransformContext iterContext = context.pushVariableScope();
            
            // For node items, set context node; for atomic values, set context item
            if (item instanceof XPathNode) {
                XPathNode node = (XPathNode) item;
                if (iterContext instanceof BasicTransformContext) {
                    iterContext = ((BasicTransformContext) iterContext)
                        .withXsltCurrentNode(node).withPositionAndSize(position, size);
                } else {
                    iterContext = iterContext.withContextNode(node).withPositionAndSize(position, size);
                }
            } else if (item instanceof XPathNodeSet) {
                // Node-set item (single node wrapped in node-set from sequence construction)
                XPathNodeSet ns = (XPathNodeSet) item;
                Iterator<XPathNode> iter = ns.iterator();
                if (iter.hasNext()) {
                    XPathNode node = iter.next();
                    if (iterContext instanceof BasicTransformContext) {
                        iterContext = ((BasicTransformContext) iterContext)
                            .withXsltCurrentNode(node).withPositionAndSize(position, size);
                    } else {
                        iterContext = iterContext.withContextNode(node).withPositionAndSize(position, size);
                    }
                }
            } else {
                // Atomic value - set as context item
                if (iterContext instanceof BasicTransformContext) {
                    iterContext = ((BasicTransformContext) iterContext)
                        .withContextItem(item).withPositionAndSize(position, size);
                } else {
                    iterContext = iterContext.withPositionAndSize(position, size);
                }
            }
            body.execute(iterContext, output);
            position++;
            first = false;
        }
    }
    
    private void executeAtomicValue(XPathValue value, TransformContext context,
                                   OutputHandler output) throws SAXException, XPathException {
        // Single atomic value - iterate once
        TransformContext iterContext = context.pushVariableScope();
        if (iterContext instanceof BasicTransformContext) {
            iterContext = ((BasicTransformContext) iterContext)
                .withContextItem(value).withPositionAndSize(1, 1);
        } else {
            iterContext = iterContext.withPositionAndSize(1, 1);
        }
        body.execute(iterContext, output);
    }
    
    /**
     * Sorts a list of nodes according to the sort specifications.
     * Made static so it can be shared with ApplyTemplatesNode.
     */
    public static void sortNodesStatic(List<XPathNode> nodes, List<SortSpec> sorts, 
                           TransformContext context) throws XPathException {
        // Pre-evaluate sort spec AVTs (these are constant for the entire sort)
        final int nodeCount = nodes.size();
        final int sortCount = sorts.size();
        final String[] dataTypes = new String[sortCount];
        final String[] orders = new String[sortCount];
        final String[] caseOrders = new String[sortCount];
        final Collation[] collations = new Collation[sortCount];
        
        for (int j = 0; j < sortCount; j++) {
            SortSpec spec = sorts.get(j);
            dataTypes[j] = spec.getDataType(context);
            orders[j] = spec.getOrder(context);
            caseOrders[j] = spec.getCaseOrder(context);
            // Get collation - use explicit collation, or default collation from context
            String collationUri = spec.getCollation(context);
            if (collationUri == null) {
                collationUri = context.getDefaultCollation();
            }
            collations[j] = Collation.forUri(collationUri);
        }
        
        // Pre-compute sort keys for all nodes
        final Object[][] sortKeys = new Object[nodeCount][sortCount];
        
        for (int i = 0; i < nodeCount; i++) {
            XPathNode node = nodes.get(i);
            // Set position/size for sort key evaluation (position is 1-based, original order)
            // IMPORTANT: Use withXsltCurrentNode so current() returns the node being sorted,
            // not some other node from an outer context (bug-2501 fix)
            TransformContext nodeCtx;
            if (context instanceof BasicTransformContext) {
                nodeCtx = ((BasicTransformContext) context)
                    .withXsltCurrentNode(node).withPositionAndSize(i + 1, nodeCount);
            } else {
                nodeCtx = context.withContextNode(node)
                    .withPositionAndSize(i + 1, nodeCount);
            }
            for (int j = 0; j < sortCount; j++) {
                SortSpec spec = sorts.get(j);
                XPathValue val = spec.getSelectExpr().evaluate(nodeCtx);
                if ("number".equals(dataTypes[j])) {
                    sortKeys[i][j] = Double.valueOf(val.asNumber());
                } else {
                    sortKeys[i][j] = val.asString();
                }
            }
        }
        
        // Create index array and sort
        Integer[] indices = new Integer[nodeCount];
        for (int i = 0; i < nodeCount; i++) {
            indices[i] = Integer.valueOf(i);
        }
        
        Arrays.sort(indices, new Comparator<Integer>() {
            public int compare(Integer a, Integer b) {
                for (int j = 0; j < sortCount; j++) {
                    Object keyA = sortKeys[a.intValue()][j];
                    Object keyB = sortKeys[b.intValue()][j];
                    
                    int cmp;
                    if (keyA instanceof Double) {
                        double da = ((Double) keyA).doubleValue();
                        double db = ((Double) keyB).doubleValue();
                        if (Double.isNaN(da) && Double.isNaN(db)) {
                            cmp = 0;
                        } else if (Double.isNaN(da)) {
                            cmp = -1; // NaN sorts first (before all numbers) per XSLT 1.0 spec
                        } else if (Double.isNaN(db)) {
                            cmp = 1;
                        } else {
                            cmp = Double.compare(da, db);
                        }
                    } else {
                        String sa = (String) keyA;
                        String sb = (String) keyB;
                        Collation collation = collations[j];
                        String caseOrder = caseOrders[j];
                        
                        if (caseOrder != null) {
                            // Case-order specified - first compare case-insensitively
                            cmp = sa.compareToIgnoreCase(sb);
                            
                            // If equal ignoring case, apply case-order
                            if (cmp == 0) {
                                // Compare character by character for case differences
                                int len = Math.min(sa.length(), sb.length());
                                for (int k = 0; k < len; k++) {
                                    char ca = sa.charAt(k);
                                    char cb = sb.charAt(k);
                                    if (ca != cb) {
                                        // Characters differ only in case
                                        boolean aIsLower = Character.isLowerCase(ca);
                                        boolean bIsLower = Character.isLowerCase(cb);
                                        if (aIsLower != bIsLower) {
                                            if ("lower-first".equals(caseOrder)) {
                                                cmp = aIsLower ? -1 : 1;
                                            } else {
                                                // upper-first (default)
                                                cmp = aIsLower ? 1 : -1;
                                            }
                                            break;
                                        }
                                    }
                                }
                            }
                        } else {
                            // No case-order - use collation for comparison
                            cmp = collation.compare(sa, sb);
                        }
                    }
                    
                    // Apply order direction
                    if ("descending".equals(orders[j])) {
                        cmp = -cmp;
                    }
                    
                    if (cmp != 0) {
                        return cmp;
                    }
                }
                return 0; // Equal on all sort keys
            }
        });
        
        // Reorder nodes based on sorted indices
        List<XPathNode> sorted = new ArrayList<>(nodeCount);
        for (int i = 0; i < nodeCount; i++) {
            sorted.add(nodes.get(indices[i].intValue()));
        }
        nodes.clear();
        nodes.addAll(sorted);
    }
}

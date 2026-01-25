/*
 * ForEachGroupNode.java
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

import org.bluezoo.gonzalez.transform.runtime.OutputHandler;
import org.bluezoo.gonzalez.transform.runtime.TransformContext;
import org.bluezoo.gonzalez.transform.runtime.GroupingContext;
import org.bluezoo.gonzalez.transform.xpath.XPathExpression;
import org.bluezoo.gonzalez.transform.xpath.expr.XPathException;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNode;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNodeSet;
import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;
import org.xml.sax.SAXException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * XSLT 2.0 xsl:for-each-group instruction.
 *
 * <p>The xsl:for-each-group instruction groups items and processes each group.
 * Grouping methods:
 * <ul>
 *   <li><b>group-by</b> - Groups items by a computed key</li>
 *   <li><b>group-adjacent</b> - Groups adjacent items with same key</li>
 *   <li><b>group-starting-with</b> - Groups starting at pattern match</li>
 *   <li><b>group-ending-with</b> - Groups ending at pattern match</li>
 * </ul>
 *
 * <p>Example:
 * <pre>
 * &lt;xsl:for-each-group select="item" group-by="@category"&gt;
 *   &lt;category name="{current-grouping-key()}"&gt;
 *     &lt;xsl:for-each select="current-group()"&gt;
 *       &lt;item&gt;&lt;xsl:value-of select="."/&gt;&lt;/item&gt;
 *     &lt;/xsl:for-each&gt;
 *   &lt;/category&gt;
 * &lt;/xsl:for-each-group&gt;
 * </pre>
 *
 * <p>Within the body:
 * <ul>
 *   <li><b>current-group()</b> - Returns the items in the current group</li>
 *   <li><b>current-grouping-key()</b> - Returns the grouping key value</li>
 *   <li>Context item is the first item of each group</li>
 * </ul>
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class ForEachGroupNode implements XSLTNode {

    /**
     * Grouping method.
     */
    public enum GroupingMethod {
        GROUP_BY,
        GROUP_ADJACENT,
        GROUP_STARTING_WITH,
        GROUP_ENDING_WITH
    }

    private final XPathExpression select;
    private final XPathExpression groupByExpr;
    private final XPathExpression groupAdjacentExpr;
    // group-starting-with and group-ending-with use patterns (not implemented fully)
    private final GroupingMethod method;
    private final XSLTNode body;

    /**
     * Creates a group-by grouping node.
     */
    public static ForEachGroupNode groupBy(XPathExpression select, XPathExpression groupByExpr,
                                            XSLTNode body) {
        return new ForEachGroupNode(select, groupByExpr, null, GroupingMethod.GROUP_BY, body);
    }

    /**
     * Creates a group-adjacent grouping node.
     */
    public static ForEachGroupNode groupAdjacent(XPathExpression select, 
                                                  XPathExpression groupAdjacentExpr,
                                                  XSLTNode body) {
        return new ForEachGroupNode(select, null, groupAdjacentExpr, 
                                    GroupingMethod.GROUP_ADJACENT, body);
    }

    private ForEachGroupNode(XPathExpression select, XPathExpression groupByExpr,
                              XPathExpression groupAdjacentExpr, GroupingMethod method,
                              XSLTNode body) {
        this.select = select;
        this.groupByExpr = groupByExpr;
        this.groupAdjacentExpr = groupAdjacentExpr;
        this.method = method;
        this.body = body;
    }

    @Override
    public void execute(TransformContext context, OutputHandler output) throws SAXException {
        try {
            // Evaluate select expression
            XPathValue selectResult = select.evaluate(context);
            
            // Get items as a list
            List<XPathNode> items = new ArrayList<>();
            if (selectResult.isNodeSet()) {
                XPathNodeSet ns = selectResult.asNodeSet();
                for (XPathNode node : ns) {
                    items.add(node);
                }
            }
            
            if (items.isEmpty()) {
                return;
            }
            
            // Group items based on method
            Map<String, List<XPathNode>> groups = groupItems(items, context);
            
            // Process each group
            int position = 0;
            int size = groups.size();
            
            for (Map.Entry<String, List<XPathNode>> entry : groups.entrySet()) {
                position++;
                String groupingKey = entry.getKey();
                List<XPathNode> groupItems = entry.getValue();
                
                if (groupItems.isEmpty()) {
                    continue;
                }
                
                // Create grouping context
                GroupingContext groupCtx = new GroupingContext(groupingKey, groupItems);
                
                // Context item is first item of group
                XPathNode firstItem = groupItems.get(0);
                TransformContext groupContext = context.withContextNode(firstItem)
                    .withPositionAndSize(position, size);
                
                // Store grouping context for current-group() and current-grouping-key()
                groupContext.getVariableScope().bind("__current_group__", 
                    new XPathNodeSet(new ArrayList<>(groupItems)));
                groupContext.getVariableScope().bind("__current_grouping_key__",
                    org.bluezoo.gonzalez.transform.xpath.type.XPathString.of(groupingKey));
                
                // Execute body
                if (body != null) {
                    body.execute(groupContext, output);
                }
            }
        } catch (XPathException e) {
            throw new SAXException("Error in xsl:for-each-group: " + e.getMessage(), e);
        }
    }

    /**
     * Groups items based on the grouping method.
     */
    private Map<String, List<XPathNode>> groupItems(List<XPathNode> items, 
                                                      TransformContext context)
            throws XPathException {
        
        switch (method) {
            case GROUP_BY:
                return groupByKey(items, groupByExpr, context);
                
            case GROUP_ADJACENT:
                return groupAdjacent(items, groupAdjacentExpr, context);
                
            default:
                // For now, treat as group-by
                return groupByKey(items, groupByExpr, context);
        }
    }

    /**
     * Groups items by computed key.
     */
    private Map<String, List<XPathNode>> groupByKey(List<XPathNode> items,
                                                      XPathExpression keyExpr,
                                                      TransformContext context)
            throws XPathException {
        
        // Use LinkedHashMap to maintain insertion order
        Map<String, List<XPathNode>> groups = new LinkedHashMap<>();
        
        for (XPathNode item : items) {
            // Evaluate key expression with item as context
            TransformContext itemCtx = context.withContextNode(item);
            XPathValue keyValue = keyExpr.evaluate(itemCtx);
            String key = keyValue.asString();
            
            // Add to group
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(item);
        }
        
        return groups;
    }

    /**
     * Groups adjacent items with same key.
     */
    private Map<String, List<XPathNode>> groupAdjacent(List<XPathNode> items,
                                                         XPathExpression keyExpr,
                                                         TransformContext context)
            throws XPathException {
        
        // Use LinkedHashMap to maintain insertion order
        Map<String, List<XPathNode>> groups = new LinkedHashMap<>();
        
        String currentKey = null;
        String groupKey = null;
        int groupIndex = 0;
        
        for (XPathNode item : items) {
            // Evaluate key expression
            TransformContext itemCtx = context.withContextNode(item);
            XPathValue keyValue = keyExpr.evaluate(itemCtx);
            String key = keyValue.asString();
            
            // If key changes, start new group
            if (!key.equals(currentKey)) {
                groupIndex++;
                groupKey = key + "_" + groupIndex;  // Make unique for adjacent groups
                currentKey = key;
            }
            
            // Add to current group
            groups.computeIfAbsent(groupKey, k -> new ArrayList<>()).add(item);
        }
        
        return groups;
    }

    @Override
    public StreamingCapability getStreamingCapability() {
        // Grouping requires collecting items - grounded mode
        return StreamingCapability.GROUNDED;
    }

    @Override
    public String toString() {
        return "ForEachGroupNode[method=" + method + "]";
    }

}

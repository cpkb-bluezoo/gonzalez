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

import org.bluezoo.gonzalez.transform.compiler.Pattern;
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
     * Grouping method for xsl:for-each-group.
     */
    public enum GroupingMethod {
        /** Groups items by a computed key value. */
        GROUP_BY,
        
        /** Groups adjacent items that have the same key value. */
        GROUP_ADJACENT,
        
        /** Groups items starting with each item that matches a pattern. */
        GROUP_STARTING_WITH,
        
        /** Groups items ending with each item that matches a pattern. */
        GROUP_ENDING_WITH
    }

    private final XPathExpression select;
    private final XPathExpression groupByExpr;
    private final XPathExpression groupAdjacentExpr;
    private final Pattern groupStartingPattern;
    private final Pattern groupEndingPattern;
    private final GroupingMethod method;
    private final XSLTNode body;

    /**
     * Creates a group-by grouping node.
     *
     * <p>Groups items by evaluating the group-by expression for each item
     * and collecting items with the same key value.
     *
     * @param select the expression selecting items to group
     * @param groupByExpr the expression computing the grouping key
     * @param body the body to execute for each group
     * @return a new ForEachGroupNode configured for group-by grouping
     */
    public static ForEachGroupNode groupBy(XPathExpression select, XPathExpression groupByExpr,
                                            XSLTNode body) {
        return new ForEachGroupNode(select, groupByExpr, null, null, null, 
                                    GroupingMethod.GROUP_BY, body);
    }

    /**
     * Creates a group-adjacent grouping node.
     *
     * <p>Groups consecutive items that have the same key value. Unlike
     * group-by, this preserves the order of items and only groups adjacent
     * items with matching keys.
     *
     * @param select the expression selecting items to group
     * @param groupAdjacentExpr the expression computing the grouping key
     * @param body the body to execute for each group
     * @return a new ForEachGroupNode configured for group-adjacent grouping
     */
    public static ForEachGroupNode groupAdjacent(XPathExpression select, 
                                                  XPathExpression groupAdjacentExpr,
                                                  XSLTNode body) {
        return new ForEachGroupNode(select, null, groupAdjacentExpr, null, null, 
                                    GroupingMethod.GROUP_ADJACENT, body);
    }

    /**
     * Creates a group-starting-with grouping node.
     *
     * <p>Groups items starting with each item that matches the pattern.
     * Each group begins at a matching item and continues until the next match
     * (or end of sequence).
     *
     * @param select the expression selecting items to group
     * @param pattern the pattern that identifies group start items
     * @param body the body to execute for each group
     * @return a new ForEachGroupNode configured for group-starting-with grouping
     */
    public static ForEachGroupNode groupStartingWith(XPathExpression select, 
                                                      Pattern pattern,
                                                      XSLTNode body) {
        return new ForEachGroupNode(select, null, null, pattern, null, 
                                    GroupingMethod.GROUP_STARTING_WITH, body);
    }

    /**
     * Creates a group-ending-with grouping node.
     *
     * <p>Groups items ending with each item that matches the pattern.
     * Each group ends at a matching item and begins after the previous match
     * (or start of sequence).
     *
     * @param select the expression selecting items to group
     * @param pattern the pattern that identifies group end items
     * @param body the body to execute for each group
     * @return a new ForEachGroupNode configured for group-ending-with grouping
     */
    public static ForEachGroupNode groupEndingWith(XPathExpression select, 
                                                    Pattern pattern,
                                                    XSLTNode body) {
        return new ForEachGroupNode(select, null, null, null, pattern, 
                                    GroupingMethod.GROUP_ENDING_WITH, body);
    }

    private ForEachGroupNode(XPathExpression select, XPathExpression groupByExpr,
                              XPathExpression groupAdjacentExpr, Pattern startingPattern,
                              Pattern endingPattern, GroupingMethod method,
                              XSLTNode body) {
        this.select = select;
        this.groupByExpr = groupByExpr;
        this.groupAdjacentExpr = groupAdjacentExpr;
        this.groupStartingPattern = startingPattern;
        this.groupEndingPattern = endingPattern;
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
                
            case GROUP_STARTING_WITH:
                return groupStartingWith(items, groupStartingPattern, context);
                
            case GROUP_ENDING_WITH:
                return groupEndingWith(items, groupEndingPattern, context);
                
            default:
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
            List<XPathNode> group = groups.get(key);
            if (group == null) {
                group = new ArrayList<>();
                groups.put(key, group);
            }
            group.add(item);
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
            List<XPathNode> group = groups.get(groupKey);
            if (group == null) {
                group = new ArrayList<>();
                groups.put(groupKey, group);
            }
            group.add(item);
        }
        
        return groups;
    }

    /**
     * Groups items starting with each item that matches the pattern.
     * Each group starts at a matching item and continues until the next match.
     */
    private Map<String, List<XPathNode>> groupStartingWith(List<XPathNode> items,
                                                            Pattern pattern,
                                                            TransformContext context)
            throws XPathException {
        
        Map<String, List<XPathNode>> groups = new LinkedHashMap<>();
        List<XPathNode> currentGroup = null;
        int groupIndex = 0;
        
        for (XPathNode item : items) {
            // Check if this item matches the pattern
            boolean matches = pattern.matches(item, context);
            
            if (matches || currentGroup == null) {
                // Start a new group
                groupIndex++;
                currentGroup = new ArrayList<>();
                groups.put("group_" + groupIndex, currentGroup);
            }
            
            // Add item to current group
            currentGroup.add(item);
        }
        
        return groups;
    }

    /**
     * Groups items ending with each item that matches the pattern.
     * Each group ends at a matching item.
     */
    private Map<String, List<XPathNode>> groupEndingWith(List<XPathNode> items,
                                                          Pattern pattern,
                                                          TransformContext context)
            throws XPathException {
        
        Map<String, List<XPathNode>> groups = new LinkedHashMap<>();
        List<XPathNode> currentGroup = new ArrayList<>();
        int groupIndex = 0;
        
        for (XPathNode item : items) {
            // Add item to current group
            currentGroup.add(item);
            
            // Check if this item matches the pattern (ends the group)
            boolean matches = pattern.matches(item, context);
            
            if (matches) {
                // End the group
                groupIndex++;
                groups.put("group_" + groupIndex, currentGroup);
                currentGroup = new ArrayList<>();
            }
        }
        
        // Add any remaining items as a final group
        if (!currentGroup.isEmpty()) {
            groupIndex++;
            groups.put("group_" + groupIndex, currentGroup);
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

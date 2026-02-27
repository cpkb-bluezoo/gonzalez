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

import org.bluezoo.gonzalez.transform.compiler.AttributeValueTemplate;
import org.bluezoo.gonzalez.transform.compiler.Pattern;
import org.bluezoo.gonzalez.transform.compiler.SortSpec;
import org.bluezoo.gonzalez.transform.runtime.OutputHandler;
import org.bluezoo.gonzalez.transform.runtime.TransformContext;
import org.bluezoo.gonzalez.transform.runtime.GroupingContext;
import org.bluezoo.gonzalez.transform.xpath.Collation;
import org.bluezoo.gonzalez.transform.xpath.XPathExpression;
import org.bluezoo.gonzalez.transform.xpath.expr.XPathException;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNode;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNodeSet;
import org.bluezoo.gonzalez.transform.xpath.type.XPathSequence;
import org.bluezoo.gonzalez.transform.xpath.type.XPathString;
import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;
import org.xml.sax.SAXException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
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
    private final AttributeValueTemplate collationAvt;
    private final List<SortSpec> sorts;

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
        return groupBy(select, groupByExpr, body, null, Collections.emptyList());
    }

    /**
     * Creates a group-by grouping node with collation support.
     *
     * @param select the expression selecting items to group
     * @param groupByExpr the expression computing the grouping key
     * @param body the body to execute for each group
     * @param collationAvt the collation AVT (can be null for default)
     * @return a new ForEachGroupNode configured for group-by grouping
     */
    public static ForEachGroupNode groupBy(XPathExpression select, XPathExpression groupByExpr,
                                            XSLTNode body, AttributeValueTemplate collationAvt) {
        return groupBy(select, groupByExpr, body, collationAvt, Collections.emptyList());
    }

    /**
     * Creates a group-by grouping node with collation and sort support.
     *
     * @param select the expression selecting items to group
     * @param groupByExpr the expression computing the grouping key
     * @param body the body to execute for each group
     * @param collationAvt the collation AVT (can be null for default)
     * @param sorts the sort specifications for ordering groups
     * @return a new ForEachGroupNode configured for group-by grouping
     */
    public static ForEachGroupNode groupBy(XPathExpression select, XPathExpression groupByExpr,
                                            XSLTNode body, AttributeValueTemplate collationAvt,
                                            List<SortSpec> sorts) {
        return new ForEachGroupNode(select, groupByExpr, null, null, null, 
                                    GroupingMethod.GROUP_BY, body, collationAvt, sorts);
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
        return groupAdjacent(select, groupAdjacentExpr, body, null, Collections.emptyList());
    }

    /**
     * Creates a group-adjacent grouping node with collation support.
     *
     * @param select the expression selecting items to group
     * @param groupAdjacentExpr the expression computing the grouping key
     * @param body the body to execute for each group
     * @param collationAvt the collation AVT (can be null for default)
     * @return a new ForEachGroupNode configured for group-adjacent grouping
     */
    public static ForEachGroupNode groupAdjacent(XPathExpression select, 
                                                  XPathExpression groupAdjacentExpr,
                                                  XSLTNode body, AttributeValueTemplate collationAvt) {
        return groupAdjacent(select, groupAdjacentExpr, body, collationAvt, Collections.emptyList());
    }

    /**
     * Creates a group-adjacent grouping node with collation and sort support.
     *
     * @param select the expression selecting items to group
     * @param groupAdjacentExpr the expression computing the grouping key
     * @param body the body to execute for each group
     * @param collationAvt the collation AVT (can be null for default)
     * @param sorts the sort specifications for ordering groups
     * @return a new ForEachGroupNode configured for group-adjacent grouping
     */
    public static ForEachGroupNode groupAdjacent(XPathExpression select, 
                                                  XPathExpression groupAdjacentExpr,
                                                  XSLTNode body, AttributeValueTemplate collationAvt,
                                                  List<SortSpec> sorts) {
        return new ForEachGroupNode(select, null, groupAdjacentExpr, null, null, 
                                    GroupingMethod.GROUP_ADJACENT, body, collationAvt, sorts);
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
        return groupStartingWith(select, pattern, body, Collections.emptyList());
    }

    /**
     * Creates a group-starting-with grouping node with sort support.
     *
     * @param select the expression selecting items to group
     * @param pattern the pattern that identifies group start items
     * @param body the body to execute for each group
     * @param sorts the sort specifications for ordering groups
     * @return a new ForEachGroupNode configured for group-starting-with grouping
     */
    public static ForEachGroupNode groupStartingWith(XPathExpression select, 
                                                      Pattern pattern,
                                                      XSLTNode body, List<SortSpec> sorts) {
        return new ForEachGroupNode(select, null, null, pattern, null, 
                                    GroupingMethod.GROUP_STARTING_WITH, body, null, sorts);
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
        return groupEndingWith(select, pattern, body, Collections.emptyList());
    }

    /**
     * Creates a group-ending-with grouping node with sort support.
     *
     * @param select the expression selecting items to group
     * @param pattern the pattern that identifies group end items
     * @param body the body to execute for each group
     * @param sorts the sort specifications for ordering groups
     * @return a new ForEachGroupNode configured for group-ending-with grouping
     */
    public static ForEachGroupNode groupEndingWith(XPathExpression select, 
                                                    Pattern pattern,
                                                    XSLTNode body, List<SortSpec> sorts) {
        return new ForEachGroupNode(select, null, null, null, pattern, 
                                    GroupingMethod.GROUP_ENDING_WITH, body, null, sorts);
    }

    private ForEachGroupNode(XPathExpression select, XPathExpression groupByExpr,
                              XPathExpression groupAdjacentExpr, Pattern startingPattern,
                              Pattern endingPattern, GroupingMethod method,
                              XSLTNode body, AttributeValueTemplate collationAvt,
                              List<SortSpec> sorts) {
        this.select = select;
        this.groupByExpr = groupByExpr;
        this.groupAdjacentExpr = groupAdjacentExpr;
        this.groupStartingPattern = startingPattern;
        this.groupEndingPattern = endingPattern;
        this.method = method;
        this.body = body;
        this.collationAvt = collationAvt;
        this.sorts = sorts != null ? sorts : Collections.emptyList();
    }

    @Override
    public void execute(TransformContext context, OutputHandler output) throws SAXException {
        try {
            // Evaluate select expression
            XPathValue selectResult = select.evaluate(context);
            
            // Get items as a list - handle both node-sets and sequences
            List<XPathNode> items = new ArrayList<>();
            if (selectResult.isNodeSet()) {
                XPathNodeSet ns = selectResult.asNodeSet();
                for (XPathNode node : ns) {
                    items.add(node);
                }
            } else if (selectResult.isSequence()) {
                // XPath 2.0+ sequence - extract nodes
                XPathSequence sequence = (XPathSequence) selectResult;
                for (XPathValue item : sequence.getItems()) {
                    if (item instanceof XPathNode) {
                        items.add((XPathNode) item);
                    } else if (item instanceof XPathNodeSet) {
                        for (XPathNode node : (XPathNodeSet) item) {
                            items.add(node);
                        }
                    } else if (item.isNodeSet()) {
                        // Handle wrappers
                        XPathNodeSet ns = item.asNodeSet();
                        if (ns != null) {
                            for (XPathNode node : ns) {
                                items.add(node);
                            }
                        }
                    }
                }
            }
            
            if (items.isEmpty()) {
                return;
            }
            
            // Group items based on method
            Map<String, List<XPathNode>> groups = groupItems(items, context);
            
            // Convert to list of group entries for sorting
            List<GroupEntry> groupEntries = new ArrayList<>();
            for (Map.Entry<String, List<XPathNode>> entry : groups.entrySet()) {
                if (!entry.getValue().isEmpty()) {
                    groupEntries.add(new GroupEntry(entry.getKey(), entry.getValue()));
                }
            }
            
            // Apply sorting if specified
            if (!sorts.isEmpty() && !groupEntries.isEmpty()) {
                sortGroups(groupEntries, context);
            }
            
            // Process each group
            int position = 0;
            int size = groupEntries.size();
            
            for (GroupEntry entry : groupEntries) {
                position++;
                String groupingKey = entry.key;
                List<XPathNode> groupItems = entry.items;
                
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
                    XPathString.of(groupingKey));
                
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
     * Helper class to hold a group entry (key + items) for sorting.
     */
    private static class GroupEntry {
        final String key;
        final List<XPathNode> items;
        
        GroupEntry(String key, List<XPathNode> items) {
            this.key = key;
            this.items = items;
        }
    }
    
    /**
     * Sorts groups according to the sort specifications.
     * The sort key is evaluated with the first item of each group as the context.
     */
    private void sortGroups(List<GroupEntry> groups, TransformContext context) throws XPathException {
        final int groupCount = groups.size();
        final int sortCount = sorts.size();
        
        // Pre-evaluate sort spec AVTs
        final String[] dataTypes = new String[sortCount];
        final String[] orders = new String[sortCount];
        final String[] caseOrders = new String[sortCount];
        final Collation[] collations = new Collation[sortCount];
        
        for (int j = 0; j < sortCount; j++) {
            SortSpec spec = sorts.get(j);
            dataTypes[j] = spec.getDataType(context);
            orders[j] = spec.getOrder(context);
            caseOrders[j] = spec.getCaseOrder(context);
            String collationUri = spec.getCollation(context);
            if (collationUri == null) {
                collationUri = context.getDefaultCollation();
            }
            collations[j] = Collation.forUri(collationUri);
        }
        
        // Pre-compute sort keys for all groups (using first item of each group)
        final Object[][] sortKeys = new Object[groupCount][sortCount];
        
        for (int i = 0; i < groupCount; i++) {
            GroupEntry group = groups.get(i);
            XPathNode firstItem = group.items.get(0);
            TransformContext itemCtx = context.withContextNode(firstItem)
                .withPositionAndSize(i + 1, groupCount);
            
            // Set up current-group() and current-grouping-key() for sort key evaluation
            itemCtx.getVariableScope().bind("__current_group__", 
                new XPathNodeSet(new ArrayList<>(group.items)));
            itemCtx.getVariableScope().bind("__current_grouping_key__",
                XPathString.of(group.key));
            
            for (int j = 0; j < sortCount; j++) {
                SortSpec spec = sorts.get(j);
                XPathValue keyValue = spec.getSelectExpr().evaluate(itemCtx);
                String dataType = dataTypes[j];
                
                if ("number".equals(dataType)) {
                    sortKeys[i][j] = keyValue.asNumber();
                } else {
                    sortKeys[i][j] = keyValue.asString();
                }
            }
        }
        
        // Create sort indexes
        Integer[] indexes = new Integer[groupCount];
        for (int i = 0; i < groupCount; i++) {
            indexes[i] = i;
        }
        
        // Sort indexes based on keys
        final Object[][] finalSortKeys = sortKeys;
        final String[] finalDataTypes = dataTypes;
        final String[] finalOrders = orders;
        final String[] finalCaseOrders = caseOrders;
        final Collation[] finalCollations = collations;
        final int finalSortCount = sortCount;
        java.util.Arrays.sort(indexes, new java.util.Comparator<Integer>() {
            @Override
            public int compare(Integer a, Integer b) {
                for (int j = 0; j < finalSortCount; j++) {
                    Object keyA = finalSortKeys[a][j];
                    Object keyB = finalSortKeys[b][j];
                    int cmp = compareKeys(keyA, keyB, finalDataTypes[j], finalOrders[j], 
                                         finalCaseOrders[j], finalCollations[j]);
                    if (cmp != 0) {
                        return cmp;
                    }
                }
                return 0;
            }
        });
        
        // Reorder groups according to sorted indexes
        List<GroupEntry> sorted = new ArrayList<>(groupCount);
        for (int i = 0; i < groupCount; i++) {
            sorted.add(groups.get(indexes[i]));
        }
        groups.clear();
        groups.addAll(sorted);
    }
    
    /**
     * Compares two sort keys.
     */
    private int compareKeys(Object keyA, Object keyB, String dataType, 
                           String order, String caseOrder, Collation collation) {
        int cmp;
        
        if ("number".equals(dataType)) {
            Double numA = keyA instanceof Double ? (Double) keyA : Double.NaN;
            Double numB = keyB instanceof Double ? (Double) keyB : Double.NaN;
            
            // NaN handling: NaN comes last in ascending, first in descending
            boolean aIsNaN = numA.isNaN();
            boolean bIsNaN = numB.isNaN();
            if (aIsNaN && bIsNaN) {
                cmp = 0;
            } else if (aIsNaN) {
                cmp = 1;  // NaN after numbers
            } else if (bIsNaN) {
                cmp = -1;  // numbers before NaN
            } else {
                cmp = numA.compareTo(numB);
            }
        } else {
            String strA = keyA != null ? keyA.toString() : "";
            String strB = keyB != null ? keyB.toString() : "";
            
            // Use collation for comparison
            cmp = collation.compare(strA, strB);
            
            // Apply case-order if needed
            if (cmp == 0 && caseOrder != null) {
                if ("upper-first".equals(caseOrder)) {
                    // Upper case letters should sort before lower case
                    cmp = strA.compareTo(strB);  // Natural Java comparison
                } else if ("lower-first".equals(caseOrder)) {
                    // Lower case letters should sort before upper case
                    cmp = -strA.compareTo(strB);
                }
            }
        }
        
        // Apply descending order if specified
        if ("descending".equals(order)) {
            cmp = -cmp;
        }
        
        return cmp;
    }

    /**
     * Groups items based on the grouping method.
     */
    private Map<String, List<XPathNode>> groupItems(List<XPathNode> items, 
                                                      TransformContext context)
            throws XPathException {
        
        // Get collation for key comparison (used by group-by and group-adjacent)
        Collation collation;
        if (collationAvt != null) {
            String collUri = collationAvt.evaluate(context);
            collation = Collation.forUri(collUri);
        } else {
            String defaultUri = context.getDefaultCollation();
            collation = Collation.forUri(defaultUri != null ? defaultUri : Collation.CODEPOINT_URI);
        }
        
        switch (method) {
            case GROUP_BY:
                return groupByKey(items, groupByExpr, context, collation);
                
            case GROUP_ADJACENT:
                return groupAdjacent(items, groupAdjacentExpr, context, collation);
                
            case GROUP_STARTING_WITH:
                return groupStartingWith(items, groupStartingPattern, context);
                
            case GROUP_ENDING_WITH:
                return groupEndingWith(items, groupEndingPattern, context);
                
            default:
                return groupByKey(items, groupByExpr, context, collation);
        }
    }

    /**
     * Groups items by computed key, using collation for key comparison.
     */
    private Map<String, List<XPathNode>> groupByKey(List<XPathNode> items,
                                                      XPathExpression keyExpr,
                                                      TransformContext context,
                                                      Collation collation)
            throws XPathException {
        
        // Use LinkedHashMap to maintain insertion order of groups
        // We need to track both the canonical key and the original key for each group
        Map<String, List<XPathNode>> groups = new LinkedHashMap<>();
        List<String> groupKeys = new ArrayList<>();  // Track original keys for collation comparison
        
        for (XPathNode item : items) {
            // Evaluate key expression with item as context
            TransformContext itemCtx = context.withContextNode(item);
            XPathValue keyValue = keyExpr.evaluate(itemCtx);
            String key = keyValue.asString();
            
            // Find if there's an existing group with a collation-equal key
            String matchingKey = null;
            for (String existingKey : groupKeys) {
                if (collation.equals(key, existingKey)) {
                    matchingKey = existingKey;
                    break;
                }
            }
            
            if (matchingKey != null) {
                // Add to existing group
                groups.get(matchingKey).add(item);
            } else {
                // Start new group
                groupKeys.add(key);
                List<XPathNode> group = new ArrayList<>();
                group.add(item);
                groups.put(key, group);
            }
        }
        
        return groups;
    }

    /**
     * Groups adjacent items with same key, using collation for key comparison.
     */
    private Map<String, List<XPathNode>> groupAdjacent(List<XPathNode> items,
                                                         XPathExpression keyExpr,
                                                         TransformContext context,
                                                         Collation collation)
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
            
            // If key changes (using collation for comparison), start new group
            boolean keyChanged = (currentKey == null) || !collation.equals(key, currentKey);
            if (keyChanged) {
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

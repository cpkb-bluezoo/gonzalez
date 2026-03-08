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
import org.bluezoo.gonzalez.transform.compiler.CompiledStylesheet;
import org.bluezoo.gonzalez.transform.compiler.ExpressionHolder;
import org.bluezoo.gonzalez.transform.compiler.Pattern;
import org.bluezoo.gonzalez.transform.compiler.SortSpec;
import org.bluezoo.gonzalez.transform.runtime.BasicTransformContext;
import org.bluezoo.gonzalez.transform.runtime.OutputHandler;
import org.bluezoo.gonzalez.transform.runtime.TransformContext;
import org.bluezoo.gonzalez.transform.runtime.GroupingContext;
import org.bluezoo.gonzalez.transform.xpath.Collation;
import org.bluezoo.gonzalez.transform.xpath.XPathExpression;
import org.bluezoo.gonzalez.transform.xpath.expr.XPathException;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNode;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNodeSet;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNumber;
import org.bluezoo.gonzalez.transform.xpath.type.XPathSequence;
import org.bluezoo.gonzalez.transform.xpath.type.XPathQName;
import org.bluezoo.gonzalez.transform.xpath.type.XPathString;
import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;
import org.xml.sax.SAXException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * XSLT 2.0/3.0 xsl:for-each-group instruction.
 *
 * <p>Groups items from the select expression and processes each group.
 * Supports all four grouping methods: group-by, group-adjacent,
 * group-starting-with, and group-ending-with.
 *
 * <p>Items can be nodes or atomic values. Composite keys (XSLT 3.0)
 * are supported when {@code composite="yes"}.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class ForEachGroupNode implements XSLTNode, ExpressionHolder {

    /**
     * Grouping method for xsl:for-each-group.
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
    private final Pattern groupStartingPattern;
    private final Pattern groupEndingPattern;
    private final GroupingMethod method;
    private final XSLTNode body;
    private final AttributeValueTemplate collationAvt;
    private final List<SortSpec> sorts;
    private final boolean composite;

    public static ForEachGroupNode groupBy(XPathExpression select, XPathExpression groupByExpr,
                                            XSLTNode body) {
        return groupBy(select, groupByExpr, body, null, Collections.emptyList(), false);
    }

    public static ForEachGroupNode groupBy(XPathExpression select, XPathExpression groupByExpr,
                                            XSLTNode body, AttributeValueTemplate collationAvt) {
        return groupBy(select, groupByExpr, body, collationAvt, Collections.emptyList(), false);
    }

    public static ForEachGroupNode groupBy(XPathExpression select, XPathExpression groupByExpr,
                                            XSLTNode body, AttributeValueTemplate collationAvt,
                                            List<SortSpec> sorts) {
        return groupBy(select, groupByExpr, body, collationAvt, sorts, false);
    }

    public static ForEachGroupNode groupBy(XPathExpression select, XPathExpression groupByExpr,
                                            XSLTNode body, AttributeValueTemplate collationAvt,
                                            List<SortSpec> sorts, boolean composite) {
        return new ForEachGroupNode(select, groupByExpr, null, null, null,
                                    GroupingMethod.GROUP_BY, body, collationAvt, sorts, composite);
    }

    public static ForEachGroupNode groupAdjacent(XPathExpression select,
                                                  XPathExpression groupAdjacentExpr,
                                                  XSLTNode body) {
        return groupAdjacent(select, groupAdjacentExpr, body, null, Collections.emptyList(), false);
    }

    public static ForEachGroupNode groupAdjacent(XPathExpression select,
                                                  XPathExpression groupAdjacentExpr,
                                                  XSLTNode body, AttributeValueTemplate collationAvt) {
        return groupAdjacent(select, groupAdjacentExpr, body, collationAvt, Collections.emptyList(), false);
    }

    public static ForEachGroupNode groupAdjacent(XPathExpression select,
                                                  XPathExpression groupAdjacentExpr,
                                                  XSLTNode body, AttributeValueTemplate collationAvt,
                                                  List<SortSpec> sorts) {
        return groupAdjacent(select, groupAdjacentExpr, body, collationAvt, sorts, false);
    }

    public static ForEachGroupNode groupAdjacent(XPathExpression select,
                                                  XPathExpression groupAdjacentExpr,
                                                  XSLTNode body, AttributeValueTemplate collationAvt,
                                                  List<SortSpec> sorts, boolean composite) {
        return new ForEachGroupNode(select, null, groupAdjacentExpr, null, null,
                                    GroupingMethod.GROUP_ADJACENT, body, collationAvt, sorts, composite);
    }

    public static ForEachGroupNode groupStartingWith(XPathExpression select,
                                                      Pattern pattern, XSLTNode body) {
        return groupStartingWith(select, pattern, body, Collections.emptyList());
    }

    public static ForEachGroupNode groupStartingWith(XPathExpression select,
                                                      Pattern pattern, XSLTNode body,
                                                      List<SortSpec> sorts) {
        return new ForEachGroupNode(select, null, null, pattern, null,
                                    GroupingMethod.GROUP_STARTING_WITH, body, null, sorts, false);
    }

    public static ForEachGroupNode groupEndingWith(XPathExpression select,
                                                    Pattern pattern, XSLTNode body) {
        return groupEndingWith(select, pattern, body, Collections.emptyList());
    }

    public static ForEachGroupNode groupEndingWith(XPathExpression select,
                                                    Pattern pattern, XSLTNode body,
                                                    List<SortSpec> sorts) {
        return new ForEachGroupNode(select, null, null, null, pattern,
                                    GroupingMethod.GROUP_ENDING_WITH, body, null, sorts, false);
    }

    private ForEachGroupNode(XPathExpression select, XPathExpression groupByExpr,
                              XPathExpression groupAdjacentExpr, Pattern startingPattern,
                              Pattern endingPattern, GroupingMethod method,
                              XSLTNode body, AttributeValueTemplate collationAvt,
                              List<SortSpec> sorts, boolean composite) {
        this.select = select;
        this.groupByExpr = groupByExpr;
        this.groupAdjacentExpr = groupAdjacentExpr;
        this.groupStartingPattern = startingPattern;
        this.groupEndingPattern = endingPattern;
        this.method = method;
        this.body = body;
        this.collationAvt = collationAvt;
        this.sorts = sorts != null ? sorts : Collections.emptyList();
        this.composite = composite;
    }

    public XSLTNode getBody() { return body; }

    /**
     * Returns the select expression.
     *
     * @return the select expression
     */
    public XPathExpression getSelect() { return select; }

    /**
     * Returns the group-by expression, if any.
     *
     * @return the group-by expression, or null
     */
    public XPathExpression getGroupByExpr() { return groupByExpr; }

    /**
     * Returns the grouping method.
     *
     * @return the grouping method
     */
    public GroupingMethod getMethod() { return method; }

    /**
     * Returns the sort specifications, if any.
     *
     * @return the sort list (never null, may be empty)
     */
    public List<SortSpec> getSorts() { return sorts; }

    /**
     * Returns the group-adjacent expression, or null.
     */
    public XPathExpression getGroupAdjacentExpr() { return groupAdjacentExpr; }

    /**
     * Returns the group-starting-with pattern, or null.
     */
    public Pattern getGroupStartingPattern() { return groupStartingPattern; }

    /**
     * Returns the group-ending-with pattern, or null.
     */
    public Pattern getGroupEndingPattern() { return groupEndingPattern; }

    @Override
    public List<XPathExpression> getExpressions() {
        List<XPathExpression> exprs = new ArrayList<XPathExpression>();
        if (select != null) {
            exprs.add(select);
        }
        if (groupByExpr != null) {
            exprs.add(groupByExpr);
        }
        if (groupAdjacentExpr != null) {
            exprs.add(groupAdjacentExpr);
        }
        return exprs;
    }

    @Override
    public void execute(TransformContext context, OutputHandler output) throws SAXException {
        try {
            XPathValue selectResult = select.evaluate(context);

            List<XPathValue> items = extractItems(selectResult);
            if (items.isEmpty()) {
                return;
            }

            List<GroupEntry> groupEntries = groupItems(items, context);

            if (!sorts.isEmpty() && !groupEntries.isEmpty()) {
                sortGroups(groupEntries, context);
            }

            int position = 0;
            int size = groupEntries.size();

            for (GroupEntry entry : groupEntries) {
                position++;
                List<XPathValue> groupItems = entry.items;
                XPathValue firstItem = groupItems.get(0);
                XPathNode firstNode = asNode(firstItem);

                TransformContext groupContext;
                if (firstNode != null) {
                    if (context instanceof BasicTransformContext) {
                        groupContext = ((BasicTransformContext) context)
                            .withContextAndCurrentNodes(firstNode, firstNode)
                            .withPositionAndSize(position, size);
                    } else {
                        groupContext = context.withContextNode(firstNode)
                            .withPositionAndSize(position, size);
                    }
                } else {
                    if (context instanceof BasicTransformContext) {
                        groupContext = ((BasicTransformContext) context)
                            .withContextItem(firstItem)
                            .withPositionAndSize(position, size);
                    } else {
                        groupContext = context.withPositionAndSize(position, size);
                    }
                }

                XPathValue currentGroupValue = buildCurrentGroup(groupItems);
                groupContext.getVariableScope().bind("__current_group__", currentGroupValue);
                if (method == GroupingMethod.GROUP_BY || method == GroupingMethod.GROUP_ADJACENT) {
                    groupContext.getVariableScope().bind("__current_grouping_key__", entry.keyValue);
                    groupContext.getVariableScope().bind("__current_grouping_key_absent__", null);
                } else {
                    groupContext.getVariableScope().bind("__current_grouping_key__", null);
                    groupContext.getVariableScope().bind("__current_grouping_key_absent__",
                                                         XPathString.of("true"));
                }

                if (body != null) {
                    body.execute(groupContext, output);
                }
            }
        } catch (XPathException e) {
            throw new SAXException("Error in xsl:for-each-group: " + e.getMessage(), e);
        }
    }

    /**
     * Wraps an XPathNode as an XPathValue (single-item XPathNodeSet).
     * XPathNode does not extend XPathValue, so this bridge is needed
     * when storing mixed nodes and atomics in List&lt;XPathValue&gt;.
     */
    private XPathValue wrapNode(XPathNode node) {
        if (node instanceof XPathValue) {
            return (XPathValue) node;
        }
        List<XPathNode> single = new ArrayList<>(1);
        single.add(node);
        return new XPathNodeSet(single);
    }

    /**
     * Extracts items from a select expression result, including both nodes
     * and atomic values.
     */
    private List<XPathValue> extractItems(XPathValue selectResult) {
        List<XPathValue> items = new ArrayList<>();
        if (selectResult.isNodeSet()) {
            XPathNodeSet ns = selectResult.asNodeSet();
            for (XPathNode node : ns) {
                items.add(wrapNode(node));
            }
        } else if (selectResult.isSequence()) {
            XPathSequence sequence = (XPathSequence) selectResult;
            for (XPathValue item : sequence.getItems()) {
                if (item instanceof XPathNode) {
                    items.add(item);
                } else if (item instanceof XPathNodeSet) {
                    for (XPathNode node : (XPathNodeSet) item) {
                        items.add(wrapNode(node));
                    }
                } else if (item.isNodeSet()) {
                    XPathNodeSet ns = item.asNodeSet();
                    if (ns != null) {
                        for (XPathNode node : ns) {
                            items.add(wrapNode(node));
                        }
                    }
                } else {
                    items.add(item);
                }
            }
        } else if (selectResult instanceof XPathNode) {
            items.add(selectResult);
        } else {
            items.add(selectResult);
        }
        return items;
    }

    /**
     * Builds the value for current-group(). Returns XPathNodeSet if all items
     * are nodes, otherwise XPathSequence.
     */
    private XPathValue buildCurrentGroup(List<XPathValue> groupItems) {
        List<XPathNode> nodes = new ArrayList<>(groupItems.size());
        for (XPathValue item : groupItems) {
            XPathNode node = asNode(item);
            if (node == null) {
                return new XPathSequence(groupItems);
            }
            nodes.add(node);
        }
        return new XPathNodeSet(nodes);
    }

    /**
     * Tries to extract a single XPathNode from a value. Returns null for
     * atomic values.
     */
    private XPathNode asNode(XPathValue item) {
        if (item instanceof XPathNode) {
            return (XPathNode) item;
        }
        if (item instanceof XPathNodeSet) {
            XPathNodeSet ns = (XPathNodeSet) item;
            if (!ns.isEmpty()) {
                return ns.iterator().next();
            }
        }
        if (item != null && item.isNodeSet()) {
            XPathNodeSet ns = item.asNodeSet();
            if (ns != null && !ns.isEmpty()) {
                return ns.iterator().next();
            }
        }
        return null;
    }

    /**
     * Evaluates the context for a single item with position information.
     */
    private TransformContext itemContext(TransformContext context, XPathValue item,
                                         int position, int size) {
        XPathNode node = asNode(item);
        if (node != null) {
            return context.withContextNode(node)
                .withPositionAndSize(position, size);
        }
        if (context instanceof BasicTransformContext) {
            return ((BasicTransformContext) context)
                .withContextItem(item)
                .withPositionAndSize(position, size);
        }
        return context.withPositionAndSize(position, size);
    }

    private static class GroupEntry {
        final String key;
        final XPathValue keyValue;
        final List<XPathValue> items;

        GroupEntry(String key, XPathValue keyValue, List<XPathValue> items) {
            this.key = key;
            this.keyValue = keyValue;
            this.items = items;
        }
    }

    /**
     * Groups items based on the grouping method.
     */
    private List<GroupEntry> groupItems(List<XPathValue> items, TransformContext context)
            throws XPathException {

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
                if (getProcessorVersion(context) < 3.0) {
                    validateAllNodes(items, "group-starting-with");
                }
                return groupStartingWith(items, groupStartingPattern, context);
            case GROUP_ENDING_WITH:
                if (getProcessorVersion(context) < 3.0) {
                    validateAllNodes(items, "group-ending-with");
                }
                return groupEndingWith(items, groupEndingPattern, context);
            default:
                return groupByKey(items, groupByExpr, context, collation);
        }
    }

    /**
     * XTTE1120: When group-starting-with or group-ending-with is used,
     * every item in select must be a node.
     */
    private static void validateAllNodes(List<XPathValue> items, String attr)
            throws XPathException {
        for (XPathValue item : items) {
            if (!(item instanceof XPathNode) && !item.isNodeSet()) {
                throw new XPathException("XTTE1120",
                        "xsl:for-each-group with " + attr +
                        ": select contains a non-node item (" + item.asString() + ")");
            }
        }
    }

    /**
     * Groups items by computed key. With composite="yes", each key component
     * is compared separately; otherwise the key is stringified.
     */
    private List<GroupEntry> groupByKey(List<XPathValue> items, XPathExpression keyExpr,
                                         TransformContext context, Collation collation)
            throws XPathException {

        List<String> groupKeyStrings = new ArrayList<>();
        List<XPathValue> groupKeyValues = new ArrayList<>();
        List<List<XPathValue>> groupItemLists = new ArrayList<>();

        int itemCount = items.size();
        for (int i = 0; i < itemCount; i++) {
            XPathValue item = items.get(i);
            TransformContext itemCtx = itemContext(context, item, i + 1, itemCount);
            XPathValue keyResult = keyExpr.evaluate(itemCtx);

            if (composite) {
                String compositeKey = compositeKeyString(keyResult);
                addToGroup(groupKeyStrings, groupKeyValues, groupItemLists,
                           compositeKey, keyResult, item, collation);
            } else {
                List<XPathValue> keyValues = flattenToAtomics(keyResult);
                if (keyValues.isEmpty()) {
                    keyValues.add(XPathString.of(""));
                }
                for (XPathValue kv : keyValues) {
                    String key = canonicalKeyString(kv);
                    addToGroup(groupKeyStrings, groupKeyValues, groupItemLists,
                               key, kv, item, collation);
                }
            }
        }

        List<GroupEntry> entries = new ArrayList<>();
        for (int i = 0; i < groupKeyStrings.size(); i++) {
            List<XPathValue> gItems = groupItemLists.get(i);
            if (!gItems.isEmpty()) {
                entries.add(new GroupEntry(groupKeyStrings.get(i),
                                          groupKeyValues.get(i), gItems));
            }
        }
        return entries;
    }

    private void addToGroup(List<String> keyStrings, List<XPathValue> keyValues,
                            List<List<XPathValue>> itemLists,
                            String key, XPathValue keyValue, XPathValue item,
                            Collation collation) {
        for (int j = 0; j < keyValues.size(); j++) {
            if (groupingKeysEqual(keyValue, keyValues.get(j), collation)) {
                itemLists.get(j).add(item);
                return;
            }
        }
        keyStrings.add(key);
        keyValues.add(keyValue);
        List<XPathValue> newGroup = new ArrayList<>();
        newGroup.add(item);
        itemLists.add(newGroup);
    }

    /**
     * Compares two grouping keys using XPath eq semantics with proper numeric
     * type promotion. Per XSLT spec section 15.3.1, two values are equal for
     * grouping if they are both NaN or if comparing them with eq returns true.
     */
    private boolean groupingKeysEqual(XPathValue key1, XPathValue key2,
                                       Collation collation) {
        if (key1 instanceof XPathNumber && key2 instanceof XPathNumber) {
            return numericKeysEqual((XPathNumber) key1, (XPathNumber) key2);
        }
        if (key1 instanceof XPathQName && key2 instanceof XPathQName) {
            return canonicalKeyString(key1).equals(canonicalKeyString(key2));
        }
        String s1 = key1.asString();
        String s2 = key2.asString();
        return collation.equals(s1, s2);
    }

    /**
     * Compares two numeric keys using XPath type promotion:
     * <ul>
     *   <li>If either is xs:double, promote both to double</li>
     *   <li>If either is xs:float, promote both to float</li>
     *   <li>Otherwise compare as double (integer/decimal)</li>
     * </ul>
     * NaN values are grouped together per XSLT spec.
     */
    private boolean numericKeysEqual(XPathNumber n1, XPathNumber n2) {
        double d1 = n1.asNumber();
        double d2 = n2.asNumber();
        if (Double.isNaN(d1) && Double.isNaN(d2)) {
            return true;
        }
        if (Double.isNaN(d1) || Double.isNaN(d2)) {
            return false;
        }

        boolean isDouble1 = isDoubleType(n1);
        boolean isDouble2 = isDoubleType(n2);
        boolean isFloat1 = n1.isFloat();
        boolean isFloat2 = n2.isFloat();

        if (isDouble1 || isDouble2) {
            return d1 == d2;
        }
        if (isFloat1 || isFloat2) {
            float f1 = (float) d1;
            float f2 = (float) d2;
            return f1 == f2;
        }
        return d1 == d2;
    }

    /**
     * Returns true if the number is a double type (neither float, decimal,
     * nor exact integer).
     */
    private boolean isDoubleType(XPathNumber n) {
        return !n.isFloat() && !n.isDecimal() && !n.isExactInteger();
    }

    /**
     * Returns a canonical string for key comparison. QNames use Clark notation
     * ({namespaceURI}localName) so that different prefixes for the same
     * expanded name compare equal.
     */
    private String canonicalKeyString(XPathValue value) {
        if (value instanceof XPathQName) {
            XPathQName qn = (XPathQName) value;
            String ns = qn.getNamespaceURI();
            if (ns != null && !ns.isEmpty()) {
                return "{" + ns + "}" + qn.getLocalName();
            }
            return qn.getLocalName();
        }
        return value.asString();
    }

    /**
     * Flattens a value to a list of atomic values. A sequence becomes its
     * individual items; a node-set atomizes each node to its string value.
     */
    private List<XPathValue> flattenToAtomics(XPathValue value) {
        List<XPathValue> result = new ArrayList<>();
        if (value instanceof XPathSequence) {
            for (XPathValue item : ((XPathSequence) value).getItems()) {
                result.add(item);
            }
        } else if (value.isNodeSet()) {
            XPathNodeSet ns = value.asNodeSet();
            for (XPathNode node : ns) {
                result.add(XPathString.of(node.getStringValue()));
            }
        } else {
            result.add(value);
        }
        return result;
    }

    /**
     * Builds a canonical string representation of a composite key for
     * comparison purposes.
     */
    private String compositeKeyString(XPathValue keyResult) {
        List<XPathValue> components = flattenToAtomics(keyResult);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < components.size(); i++) {
            if (i > 0) {
                sb.append('\u0000');
            }
            sb.append(components.get(i).asString());
        }
        return sb.toString();
    }

    /**
     * Groups adjacent items with same key.
     */
    private List<GroupEntry> groupAdjacent(List<XPathValue> items, XPathExpression keyExpr,
                                            TransformContext context, Collation collation)
            throws XPathException {

        List<GroupEntry> entries = new ArrayList<>();
        String currentKey = null;
        XPathValue currentKeyValue = null;
        List<XPathValue> currentGroup = null;

        int itemCount = items.size();
        for (int i = 0; i < itemCount; i++) {
            XPathValue item = items.get(i);
            TransformContext itemCtx = itemContext(context, item, i + 1, itemCount);
            XPathValue keyResult = keyExpr.evaluate(itemCtx);

            String key;
            if (composite) {
                key = compositeKeyString(keyResult);
            } else {
                // XTTE1100: group-adjacent key must evaluate to a single atomic value
                if (keyResult instanceof XPathSequence) {
                    int count = ((XPathSequence) keyResult).getItems().size();
                    if (count == 0 || count > 1) {
                        throw new XPathException("XTTE1100: The group-adjacent key expression " +
                            "evaluates to a sequence of " + count + " items (expected exactly one)");
                    }
                } else if (keyResult instanceof XPathNodeSet) {
                    int count = ((XPathNodeSet) keyResult).getNodes().size();
                    if (count == 0 || count > 1) {
                        throw new XPathException("XTTE1100: The group-adjacent key expression " +
                            "evaluates to " + count + " nodes (expected exactly one item)");
                    }
                }
                key = canonicalKeyString(keyResult);
            }

            boolean keyChanged = (currentKey == null) || !collation.equals(key, currentKey);
            if (keyChanged) {
                if (currentGroup != null && !currentGroup.isEmpty()) {
                    entries.add(new GroupEntry(currentKey, currentKeyValue, currentGroup));
                }
                currentKey = key;
                currentKeyValue = composite ? keyResult : keyResult;
                currentGroup = new ArrayList<>();
            }

            currentGroup.add(item);
        }

        if (currentGroup != null && !currentGroup.isEmpty()) {
            entries.add(new GroupEntry(currentKey, currentKeyValue, currentGroup));
        }

        return entries;
    }

    /**
     * Groups items starting with each item that matches the pattern.
     */
    private List<GroupEntry> groupStartingWith(List<XPathValue> items, Pattern pattern,
                                                TransformContext context)
            throws XPathException {

        List<GroupEntry> entries = new ArrayList<>();
        List<XPathValue> currentGroup = null;
        int groupIndex = 0;

        for (XPathValue item : items) {
            boolean matches = matchesItem(pattern, item, context);

            if (matches || currentGroup == null) {
                if (currentGroup != null && !currentGroup.isEmpty()) {
                    groupIndex++;
                    entries.add(new GroupEntry("group_" + groupIndex,
                                              XPathString.of("group_" + groupIndex), currentGroup));
                }
                currentGroup = new ArrayList<>();
            }

            currentGroup.add(item);
        }

        if (currentGroup != null && !currentGroup.isEmpty()) {
            groupIndex++;
            entries.add(new GroupEntry("group_" + groupIndex,
                                      XPathString.of("group_" + groupIndex), currentGroup));
        }

        return entries;
    }

    /**
     * Groups items ending with each item that matches the pattern.
     */
    private List<GroupEntry> groupEndingWith(List<XPathValue> items, Pattern pattern,
                                              TransformContext context)
            throws XPathException {

        List<GroupEntry> entries = new ArrayList<>();
        List<XPathValue> currentGroup = new ArrayList<>();
        int groupIndex = 0;

        for (XPathValue item : items) {
            currentGroup.add(item);

            boolean matches = matchesItem(pattern, item, context);

            if (matches) {
                groupIndex++;
                entries.add(new GroupEntry("group_" + groupIndex,
                                          XPathString.of("group_" + groupIndex), currentGroup));
                currentGroup = new ArrayList<>();
            }
        }

        if (!currentGroup.isEmpty()) {
            groupIndex++;
            entries.add(new GroupEntry("group_" + groupIndex,
                                      XPathString.of("group_" + groupIndex), currentGroup));
        }

        return entries;
    }

    /**
     * Tests whether a pattern matches an item, handling both nodes and atomic
     * values (XSLT 3.0 patterns like ".[. instance of xs:string]").
     */
    private boolean matchesItem(Pattern pattern, XPathValue item, TransformContext context)
            throws XPathException {
        XPathNode node = asNode(item);
        if (node != null) {
            return pattern.matches(node, context);
        }
        return pattern.matchesAtomicValue(item, context);
    }

    /**
     * Sorts groups according to the sort specifications.
     */
    private void sortGroups(List<GroupEntry> groups, TransformContext context) throws XPathException {
        final int groupCount = groups.size();
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
            String collationUri = spec.getCollation(context);
            if (collationUri == null) {
                collationUri = context.getDefaultCollation();
            }
            collations[j] = Collation.forUri(collationUri);
        }

        final Object[][] sortKeys = new Object[groupCount][sortCount];

        for (int i = 0; i < groupCount; i++) {
            GroupEntry group = groups.get(i);
            XPathValue firstItem = group.items.get(0);
            TransformContext itemCtx = itemContext(context, firstItem, i + 1, groupCount);

            XPathValue currentGroupValue = buildCurrentGroup(group.items);
            itemCtx.getVariableScope().bind("__current_group__", currentGroupValue);
            if (method == GroupingMethod.GROUP_BY || method == GroupingMethod.GROUP_ADJACENT) {
                itemCtx.getVariableScope().bind("__current_grouping_key__", group.keyValue);
                itemCtx.getVariableScope().bind("__current_grouping_key_absent__", null);
            } else {
                itemCtx.getVariableScope().bind("__current_grouping_key__", null);
                itemCtx.getVariableScope().bind("__current_grouping_key_absent__",
                                                 XPathString.of("true"));
            }

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

        Integer[] indexes = new Integer[groupCount];
        for (int i = 0; i < groupCount; i++) {
            indexes[i] = i;
        }

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

        List<GroupEntry> sorted = new ArrayList<>(groupCount);
        for (int i = 0; i < groupCount; i++) {
            sorted.add(groups.get(indexes[i]));
        }
        groups.clear();
        groups.addAll(sorted);
    }

    private int compareKeys(Object keyA, Object keyB, String dataType,
                           String order, String caseOrder, Collation collation) {
        int cmp;

        if ("number".equals(dataType)) {
            Double numA = keyA instanceof Double ? (Double) keyA : Double.NaN;
            Double numB = keyB instanceof Double ? (Double) keyB : Double.NaN;
            boolean aIsNaN = numA.isNaN();
            boolean bIsNaN = numB.isNaN();
            if (aIsNaN && bIsNaN) {
                cmp = 0;
            } else if (aIsNaN) {
                cmp = 1;
            } else if (bIsNaN) {
                cmp = -1;
            } else {
                cmp = numA.compareTo(numB);
            }
        } else {
            String strA = keyA != null ? keyA.toString() : "";
            String strB = keyB != null ? keyB.toString() : "";
            cmp = collation.compare(strA, strB);
            if (cmp == 0 && caseOrder != null) {
                if ("upper-first".equals(caseOrder)) {
                    cmp = strA.compareTo(strB);
                } else if ("lower-first".equals(caseOrder)) {
                    cmp = -strA.compareTo(strB);
                }
            }
        }

        if ("descending".equals(order)) {
            cmp = -cmp;
        }

        return cmp;
    }

    /**
     * Returns the processor version from the compiled stylesheet. Falls back
     * to the context's XSLT version if the stylesheet is unavailable.
     */
    private double getProcessorVersion(TransformContext context) {
        CompiledStylesheet ss = context.getStylesheet();
        if (ss != null) {
            return ss.getProcessorVersion();
        }
        return context.getXsltVersion();
    }

    private static boolean hasAtomicItems(List<XPathValue> items) {
        for (XPathValue item : items) {
            if (!(item instanceof XPathNode) && !item.isNodeSet()) {
                return true;
            }
        }
        return false;
    }

    /**
     * When group-starting-with or group-ending-with has atomic items,
     * each atomic value forms a singleton group (pattern matching is inapplicable).
     */
    private static List<GroupEntry> groupAtomicSingletons(List<XPathValue> items) {
        List<GroupEntry> groups = new ArrayList<GroupEntry>();
        for (XPathValue item : items) {
            List<XPathValue> group = new ArrayList<XPathValue>();
            group.add(item);
            groups.add(new GroupEntry(item.asString(), item, group));
        }
        return groups;
    }

    @Override
    public StreamingCapability getStreamingCapability() {
        return StreamingCapability.GROUNDED;
    }

    @Override
    public String toString() {
        return "ForEachGroupNode[method=" + method + ", composite=" + composite + "]";
    }

}

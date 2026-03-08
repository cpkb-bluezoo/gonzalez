/*
 * MergeNode.java
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
import org.bluezoo.gonzalez.transform.runtime.BasicTransformContext;
import org.bluezoo.gonzalez.transform.runtime.DocumentLoader;
import org.bluezoo.gonzalez.transform.runtime.OutputHandler;
import org.bluezoo.gonzalez.transform.runtime.TransformContext;
import org.bluezoo.gonzalez.transform.xpath.XPathExpression;
import org.bluezoo.gonzalez.transform.xpath.expr.XPathException;
import org.bluezoo.gonzalez.transform.xpath.type.NodeType;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNode;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNodeSet;
import org.bluezoo.gonzalez.transform.xpath.type.XPathDateTime;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNumber;
import org.bluezoo.gonzalez.transform.xpath.type.XPathSequence;
import org.bluezoo.gonzalez.transform.xpath.type.XPathString;
import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;
import org.xml.sax.SAXException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * XSLT 3.0 xsl:merge instruction.
 *
 * <p>The xsl:merge instruction merges multiple sorted input sequences into a single
 * output sequence, grouping items by their merge keys. This is commonly used for
 * merge-sorting data from multiple sources.
 *
 * <h3>Structure</h3>
 * <pre>
 * &lt;xsl:merge&gt;
 *   &lt;xsl:merge-source name="src1" select="..."&gt;
 *     &lt;xsl:merge-key select="@key" order="ascending"/&gt;
 *   &lt;/xsl:merge-source&gt;
 *   &lt;xsl:merge-source name="src2" select="..."&gt;
 *     &lt;xsl:merge-key select="@key"/&gt;
 *   &lt;/xsl:merge-source&gt;
 *   &lt;xsl:merge-action&gt;
 *     &lt;!-- Process current-merge-group() and current-merge-key() --&gt;
 *   &lt;/xsl:merge-action&gt;
 * &lt;/xsl:merge&gt;
 * </pre>
 *
 * <h3>Available Functions</h3>
 * <p>Within xsl:merge-action:
 * <ul>
 *   <li>{@code current-merge-group()} - Returns all items with the current merge key</li>
 *   <li>{@code current-merge-group('name')} - Returns items from named source</li>
 *   <li>{@code current-merge-key()} - Returns the current merge key value</li>
 * </ul>
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class MergeNode implements XSLTNode, ExpressionHolder {

    private final List<MergeSource> sources;
    private final XSLTNode action;

    /**
     * Creates a new merge instruction.
     *
     * @param sources the merge sources
     * @param action the merge action to execute for each group
     */
    public MergeNode(List<MergeSource> sources, XSLTNode action) {
        this.sources = sources;
        this.action = action;
    }

    @Override
    public List<XPathExpression> getExpressions() {
        List<XPathExpression> exprs = new ArrayList<XPathExpression>();
        for (int i = 0; i < sources.size(); i++) {
            MergeSource src = sources.get(i);
            if (src.select != null) {
                exprs.add(src.select);
            }
            if (src.forEachItem != null) {
                exprs.add(src.forEachItem);
            }
            if (src.forEachSource != null) {
                exprs.add(src.forEachSource);
            }
            for (int j = 0; j < src.keys.size(); j++) {
                MergeKey mk = src.keys.get(j);
                if (mk.select != null) {
                    exprs.add(mk.select);
                }
            }
        }
        return exprs;
    }

    @Override
    public void execute(TransformContext context, OutputHandler output) throws SAXException {
        if (sources.isEmpty() || action == null) {
            return;
        }

        try {
            // Resolve per-key order, data-type, collation, lang AVTs once per merge
            List<ResolvedKeySpec[]> resolvedSpecs = new ArrayList<>();
            for (MergeSource source : sources) {
                ResolvedKeySpec[] specs = new ResolvedKeySpec[source.keys.size()];
                for (int i = 0; i < source.keys.size(); i++) {
                    MergeKey mk = source.keys.get(i);
                    String order = mk.resolveOrder(context);
                    String dataType = mk.resolveDataType(context);
                    String collation = mk.resolveCollation(context);
                    String lang = mk.resolveLang(context);
                    specs[i] = new ResolvedKeySpec(order, dataType, collation, lang);
                }
                resolvedSpecs.add(specs);
            }

            // XTDE2210: all merge-sources must have compatible key specs
            if (resolvedSpecs.size() > 1) {
                ResolvedKeySpec[] firstSpecs = resolvedSpecs.get(0);
                for (int si = 1; si < resolvedSpecs.size(); si++) {
                    ResolvedKeySpec[] otherSpecs = resolvedSpecs.get(si);
                    for (int ki = 0; ki < firstSpecs.length && ki < otherSpecs.length; ki++) {
                        ResolvedKeySpec a = firstSpecs[ki];
                        ResolvedKeySpec b = otherSpecs[ki];
                        String aLang = a.lang != null ? a.lang : "";
                        String bLang = b.lang != null ? b.lang : "";
                        if (!a.order.equals(b.order) || !a.dataType.equals(b.dataType)
                                || !a.collation.equals(b.collation)
                                || !aLang.equals(bLang)) {
                            throw new SAXException("XTDE2210: Incompatible merge key " +
                                "specifications across merge sources (key " + (ki + 1) +
                                ": order='" + a.order + "'/'" + b.order +
                                "', data-type='" + a.dataType + "'/'" + b.dataType +
                                "', collation='" + a.collation + "'/'" + b.collation +
                                "', lang='" + aLang + "'/'" + bLang + "')");
                        }
                    }
                }
            }

            // Use first source's key specs for the comparator
            ResolvedKeySpec[] primarySpecs;
            if (!resolvedSpecs.isEmpty() && resolvedSpecs.get(0).length > 0) {
                primarySpecs = resolvedSpecs.get(0);
            } else {
                primarySpecs = new ResolvedKeySpec[]{
                    new ResolvedKeySpec("ascending", "text", null, null)
                };
            }

            // Collect all items from all sources
            List<MergeItem> allItems = new ArrayList<>();
            for (int si = 0; si < sources.size(); si++) {
                MergeSource source = sources.get(si);
                ResolvedKeySpec[] specs = si < resolvedSpecs.size()
                    ? resolvedSpecs.get(si) : primarySpecs;
                collectItems(source, context, allItems, specs);
            }

            // Sort all items by their composite merge keys
            int keyCount = primarySpecs.length;
            try {
                Collections.sort(allItems, new MergeItemComparator(primarySpecs, keyCount));
            } catch (RuntimeException e) {
                String msg = e.getMessage();
                if (msg != null && msg.startsWith("XTTE2230")) {
                    throw new SAXException(msg);
                }
                throw e;
            }

            // Group items by composite merge key (all key values must match)
            List<MergeGroup> groups = buildGroups(allItems, keyCount);

            // Process each group
            int groupCount = groups.size();
            for (int gi = 0; gi < groupCount; gi++) {
                MergeGroup group = groups.get(gi);
                List<Object> allGroupItems = new ArrayList<>();
                Map<String, List<Object>> itemsBySource = new LinkedHashMap<>();

                for (MergeItem item : group.items) {
                    allGroupItems.add(item.item);
                    String srcKey = item.sourceName != null ? item.sourceName : "";
                    List<Object> sourceList = itemsBySource.get(srcKey);
                    if (sourceList == null) {
                        sourceList = new ArrayList<>();
                        itemsBySource.put(srcKey, sourceList);
                    }
                    sourceList.add(item.item);
                }

                TransformContext groupContext = context.withPositionAndSize(gi + 1, groupCount);

                // Store merge group - use XPathNodeSet if all items are nodes, else XPathSequence
                XPathValue groupValue = toGroupValue(allGroupItems);
                groupContext.getVariableScope().bind("__current_merge_group__", groupValue);

                // Store first key value as the merge key
                String mergeKeyStr = group.items.get(0).keyValues[0];
                groupContext.getVariableScope().bind("__current_merge_key__",
                    new XPathString(mergeKeyStr));

                // Bind known source names so current-merge-group('name') can
                // validate the name (XTDE3490) and return empty for absent sources
                StringBuilder sourceNameList = new StringBuilder();
                for (MergeSource src : sources) {
                    if (src.name != null) {
                        groupContext.getVariableScope().bind(
                            "__current_merge_group_" + src.name + "__",
                            XPathNodeSet.empty());
                        if (sourceNameList.length() > 0) {
                            sourceNameList.append('|');
                        }
                        sourceNameList.append(src.name);
                    }
                }
                groupContext.getVariableScope().bind("__merge_source_names__",
                    new XPathString(sourceNameList.toString()));

                // Store per-source groups (overwrites empty bindings where applicable)
                for (Map.Entry<String, List<Object>> sourceEntry : itemsBySource.entrySet()) {
                    XPathValue sourceGroupValue = toGroupValue(sourceEntry.getValue());
                    groupContext.getVariableScope().bind(
                        "__current_merge_group_" + sourceEntry.getKey() + "__",
                        sourceGroupValue);
                }

                if (groupContext instanceof BasicTransformContext) {
                    ((BasicTransformContext) groupContext).setInsideMergeAction(true);
                }
                action.execute(groupContext, output);
            }

        } catch (XPathException e) {
            throw new SAXException("Error in xsl:merge: " + e.getMessage(), e);
        }
    }

    /**
     * Converts a list of items into a merge group value.
     * Uses XPathSequence to preserve merge-determined ordering rather than
     * XPathNodeSet which reorders by document order.
     */
    private XPathValue toGroupValue(List<Object> items) {
        List<XPathValue> values = new ArrayList<>();
        for (Object item : items) {
            if (item instanceof XPathNode) {
                values.add(new XPathNodeSet(Collections.singletonList((XPathNode) item)));
            } else if (item instanceof XPathValue) {
                values.add((XPathValue) item);
            }
        }
        return new XPathSequence(values);
    }

    /**
     * Groups sorted items by their composite merge key.
     */
    private List<MergeGroup> buildGroups(List<MergeItem> sortedItems, int keyCount) {
        List<MergeGroup> groups = new ArrayList<>();
        MergeGroup currentGroup = null;

        for (MergeItem item : sortedItems) {
            if (currentGroup == null || !keysEqual(currentGroup.items.get(0), item, keyCount)) {
                currentGroup = new MergeGroup();
                groups.add(currentGroup);
            }
            currentGroup.items.add(item);
        }
        return groups;
    }

    private boolean keysEqual(MergeItem a, MergeItem b, int keyCount) {
        int aLen = a.keyValues.length;
        int bLen = b.keyValues.length;
        for (int i = 0; i < keyCount; i++) {
            String aKey = i < aLen ? a.keyValues[i] : "";
            String bKey = i < bLen ? b.keyValues[i] : "";
            if (!aKey.equals(bKey)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Collects items from a merge source, handling for-each-item and for-each-source.
     */
    private void collectItems(MergeSource source, TransformContext context,
            List<MergeItem> items, ResolvedKeySpec[] specs) throws XPathException, SAXException {

        if (source.forEachItem != null) {
            collectItemsForEachItem(source, context, items, specs);
        } else if (source.forEachSource != null) {
            collectItemsForEachSource(source, context, items, specs);
        } else if (source.select != null) {
            collectItemsDirect(source, context, items, specs);
        }
    }

    /**
     * Handles for-each-item: evaluates the expression to get a sequence of items,
     * then for each item sets it as the context and evaluates select.
     */
    private void collectItemsForEachItem(MergeSource source, TransformContext context,
            List<MergeItem> items, ResolvedKeySpec[] specs) throws XPathException, SAXException {

        XPathValue feResult = source.forEachItem.evaluate(context);
        List<Object> feItems = flattenToItems(feResult);

        for (Object feItem : feItems) {
            TransformContext itemCtx;
            if (feItem instanceof XPathNode) {
                if (context instanceof BasicTransformContext) {
                    itemCtx = ((BasicTransformContext) context).withContextNode((XPathNode) feItem);
                } else {
                    itemCtx = context.withContextNode((XPathNode) feItem);
                }
            } else if (feItem instanceof XPathValue) {
                if (context instanceof BasicTransformContext) {
                    itemCtx = ((BasicTransformContext) context).withContextItem((XPathValue) feItem);
                } else {
                    continue;
                }
            } else {
                continue;
            }

            if (source.select != null) {
                collectFromSelect(source, itemCtx, items, specs);
            }
        }
    }

    /**
     * Handles for-each-source: evaluates the expression to get URI strings,
     * loads each document, and evaluates select within that document context.
     */
    private void collectItemsForEachSource(MergeSource source, TransformContext context,
            List<MergeItem> items, ResolvedKeySpec[] specs) throws XPathException, SAXException {

        XPathValue feResult = source.forEachSource.evaluate(context);
        List<Object> uriValues = flattenToItems(feResult);

        List<String> stripSpace = null;
        List<String> preserveSpace = null;
        CompiledStylesheet stylesheet = context.getStylesheet();
        if (stylesheet != null) {
            stripSpace = stylesheet.getStripSpaceElements();
            preserveSpace = stylesheet.getPreserveSpaceElements();
        }

        String baseUri = context.getStaticBaseURI();

        for (Object uriVal : uriValues) {
            String uri = itemToString(uriVal);
            XPathNode docNode = DocumentLoader.loadDocument(uri, baseUri, stripSpace, preserveSpace);
            if (docNode == null) {
                throw new SAXException("FODC0002: Cannot load document at " + uri);
            }

            TransformContext docCtx;
            if (context instanceof BasicTransformContext) {
                docCtx = ((BasicTransformContext) context).withContextNode(docNode);
            } else {
                docCtx = context.withContextNode(docNode);
            }

            if (source.select != null) {
                collectFromSelect(source, docCtx, items, specs);
            }
        }
    }

    /**
     * Direct collection without for-each-item/for-each-source.
     */
    private void collectItemsDirect(MergeSource source, TransformContext context,
            List<MergeItem> items, ResolvedKeySpec[] specs) throws XPathException, SAXException {
        collectFromSelect(source, context, items, specs);
    }

    /**
     * Evaluates the select expression and collects resulting items with their keys.
     */
    private void collectFromSelect(MergeSource source, TransformContext context,
            List<MergeItem> items, ResolvedKeySpec[] specs) throws XPathException, SAXException {

        XPathValue selectResult = source.select.evaluate(context);
        List<Object> selectedItems = flattenToItems(selectResult);

        List<MergeItem> sourceItems = new ArrayList<>();
        for (Object item : selectedItems) {
            Object[] keysResult = evaluateMergeKeys(source, item, context);
            String[] keyValues = (String[]) keysResult[0];
            XPathValue[] rawValues = (XPathValue[]) keysResult[1];
            sourceItems.add(new MergeItem(item, keyValues, rawValues, source.name));
        }

        if (source.sortBeforeMerge && !sourceItems.isEmpty()) {
            int keyCount = specs.length;
            Collections.sort(sourceItems, new MergeItemComparator(specs, keyCount));
        } else if (sourceItems.size() > 1) {
            // XTDE2220: verify input is in the required order when sort-before-merge
            // is not specified. The input must already be correctly sorted.
            MergeItemComparator orderChecker = new MergeItemComparator(specs, specs.length);
            for (int j = 1; j < sourceItems.size(); j++) {
                int c = orderChecker.compare(sourceItems.get(j - 1), sourceItems.get(j));
                if (c > 0) {
                    throw new SAXException("XTDE2220: Input to merge source" +
                        (source.name != null ? " '" + source.name + "'" : "") +
                        " is not in the required order");
                }
            }
        }

        items.addAll(sourceItems);
    }

    /**
     * Flattens an XPathValue into a list of individual items (XPathNode or XPathValue).
     */
    private List<Object> flattenToItems(XPathValue result) {
        if (result == null) {
            return Collections.emptyList();
        }
        List<Object> items = new ArrayList<>();
        if (result instanceof XPathNodeSet) {
            Iterator<XPathNode> iter = ((XPathNodeSet) result).iterator();
            while (iter.hasNext()) {
                items.add(iter.next());
            }
        } else if (result instanceof XPathSequence) {
            for (XPathValue v : (XPathSequence) result) {
                if (v instanceof XPathNodeSet) {
                    Iterator<XPathNode> iter = ((XPathNodeSet) v).iterator();
                    while (iter.hasNext()) {
                        items.add(iter.next());
                    }
                } else if (v instanceof XPathNode) {
                    items.add(v);
                } else {
                    items.add(v);
                }
            }
        } else if (result instanceof XPathNode) {
            items.add(result);
        } else {
            items.add(result);
        }
        return items;
    }

    /**
     * Evaluates all merge keys for a given item (XPathNode or XPathValue).
     * Returns a two-element array: [String[] keyStrings, XPathValue[] rawValues].
     */
    private Object[] evaluateMergeKeys(MergeSource source, Object item,
            TransformContext context) throws XPathException, SAXException {

        if (source.keys.isEmpty()) {
            String sv = itemToString(item);
            return new Object[]{new String[]{sv}, new XPathValue[]{new XPathString(sv)}};
        }

        // Per XSLT 3.0: merge key expressions are evaluated with singleton focus
        // (context item = current item, position = 1, size = 1)
        TransformContext itemCtx;
        if (item instanceof XPathNode) {
            if (context instanceof BasicTransformContext) {
                itemCtx = ((BasicTransformContext) context).withContextNode((XPathNode) item);
            } else {
                itemCtx = context.withContextNode((XPathNode) item);
            }
        } else if (item instanceof XPathValue) {
            if (context instanceof BasicTransformContext) {
                itemCtx = ((BasicTransformContext) context).withContextItem((XPathValue) item);
            } else {
                itemCtx = context;
            }
        } else {
            itemCtx = context;
        }
        itemCtx = itemCtx.withPositionAndSize(1, 1);

        String[] keyValues = new String[source.keys.size()];
        XPathValue[] rawValues = new XPathValue[source.keys.size()];
        for (int i = 0; i < source.keys.size(); i++) {
            MergeKey key = source.keys.get(i);
            XPathValue keyResult;
            if (key.select != null) {
                keyResult = key.select.evaluate(itemCtx);
            } else if (key.body != null) {
                keyResult = evaluateBody(key.body, itemCtx);
            } else {
                keyResult = null;
            }

            // XTTE1020: merge-key must evaluate to a single atomic value
            if (keyResult instanceof XPathNodeSet) {
                XPathNodeSet ns = (XPathNodeSet) keyResult;
                int size = 0;
                java.util.Iterator<XPathNode> it = ns.iterator();
                while (it.hasNext()) {
                    it.next();
                    size++;
                    if (size > 1) {
                        throw new SAXException("XTTE1020: Merge key evaluated to a " +
                            "sequence of more than one item");
                    }
                }
            } else if (keyResult instanceof XPathSequence) {
                XPathSequence seq = (XPathSequence) keyResult;
                java.util.Iterator<XPathValue> it = seq.iterator();
                int size = 0;
                while (it.hasNext()) {
                    it.next();
                    size++;
                    if (size > 1) {
                        throw new SAXException("XTTE1020: Merge key evaluated to a " +
                            "sequence of more than one item");
                    }
                }
            }

            rawValues[i] = keyResult;
            keyValues[i] = keyResult != null ? keyResult.asString() : "";
        }
        return new Object[]{keyValues, rawValues};
    }

    private static final String CODEPOINT_COLLATION =
        "http://www.w3.org/2005/xpath-functions/collation/codepoint";

    private boolean isCodepointCollation(ResolvedKeySpec[] specs) {
        for (ResolvedKeySpec spec : specs) {
            if (!CODEPOINT_COLLATION.equals(spec.collation)) {
                return false;
            }
            if (spec.lang != null && !spec.lang.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private String itemToString(Object item) {
        if (item instanceof XPathNode) {
            return ((XPathNode) item).getStringValue();
        }
        if (item instanceof XPathValue) {
            return ((XPathValue) item).asString();
        }
        return item != null ? item.toString() : "";
    }

    /**
     * Evaluates a merge-key body (sequence constructor) to get its string value.
     */
    private XPathValue evaluateBody(XSLTNode body, TransformContext context)
            throws XPathException, SAXException {
        StringOutputHandler stringOutput = new StringOutputHandler();
        body.execute(context, stringOutput);
        String result = stringOutput.getResult();
        return new XPathString(result);
    }

    @Override
    public StreamingCapability getStreamingCapability() {
        return StreamingCapability.GROUNDED;
    }

    @Override
    public String toString() {
        return "MergeNode[sources=" + sources.size() + "]";
    }

    /**
     * Represents a merge source (xsl:merge-source).
     */
    public static class MergeSource {
        public final String name;
        public final XPathExpression select;
        public final XPathExpression forEachItem;
        public final XPathExpression forEachSource;
        public final boolean sortBeforeMerge;
        public final boolean streamable;
        public final List<MergeKey> keys;

        public MergeSource(String name, XPathExpression select,
                XPathExpression forEachItem, XPathExpression forEachSource,
                boolean sortBeforeMerge, boolean streamable, List<MergeKey> keys) {
            this.name = name;
            this.select = select;
            this.forEachItem = forEachItem;
            this.forEachSource = forEachSource;
            this.sortBeforeMerge = sortBeforeMerge;
            this.streamable = streamable;
            this.keys = keys != null ? keys : Collections.emptyList();
        }
    }

    /**
     * Represents a merge key (xsl:merge-key).
     */
    public static class MergeKey {
        public final XPathExpression select;
        public final XSLTNode body;
        public final AttributeValueTemplate orderAvt;
        public final AttributeValueTemplate langAvt;
        public final AttributeValueTemplate collationAvt;
        public final AttributeValueTemplate dataTypeAvt;

        public MergeKey(XPathExpression select, XSLTNode body,
                AttributeValueTemplate orderAvt, AttributeValueTemplate langAvt,
                AttributeValueTemplate collationAvt, AttributeValueTemplate dataTypeAvt) {
            this.select = select;
            this.body = body;
            this.orderAvt = orderAvt;
            this.langAvt = langAvt;
            this.collationAvt = collationAvt;
            this.dataTypeAvt = dataTypeAvt;
        }

        String resolveOrder(TransformContext context) throws XPathException {
            if (orderAvt == null) {
                return "ascending";
            }
            String val = orderAvt.evaluate(context);
            if (val == null || val.isEmpty()) {
                return "ascending";
            }
            return val;
        }

        String resolveDataType(TransformContext context) throws XPathException {
            if (dataTypeAvt == null) {
                return "text";
            }
            String val = dataTypeAvt.evaluate(context);
            if (val == null || val.isEmpty()) {
                return "text";
            }
            return val;
        }

        String resolveCollation(TransformContext context) throws XPathException {
            if (collationAvt == null) {
                return "http://www.w3.org/2005/xpath-functions/collation/codepoint";
            }
            String val = collationAvt.evaluate(context);
            if (val == null || val.isEmpty()) {
                return "http://www.w3.org/2005/xpath-functions/collation/codepoint";
            }
            return val;
        }

        String resolveLang(TransformContext context) throws XPathException {
            if (langAvt == null) {
                return "";
            }
            String val = langAvt.evaluate(context);
            if (val == null) {
                return "";
            }
            return val;
        }
    }

    /**
     * Resolved key specification with order and data-type evaluated at merge execution time.
     */
    private static class ResolvedKeySpec {
        final String order;
        final String dataType;
        final String collation;
        final String lang;

        ResolvedKeySpec(String order, String dataType, String collation, String lang) {
            this.order = order != null ? order : "ascending";
            this.dataType = dataType != null ? dataType : "text";
            this.collation = collation != null ? collation
                : "http://www.w3.org/2005/xpath-functions/collation/codepoint";
            this.lang = lang != null ? lang : "";
        }
    }

    /**
     * Internal class representing an item with its merge key values.
     * The item is either an XPathNode or an XPathValue.
     */
    private static class MergeItem {
        final Object item;
        final String[] keyValues;
        final XPathValue[] rawKeyValues;
        final String sourceName;

        MergeItem(Object item, String[] keyValues, XPathValue[] rawKeyValues, String sourceName) {
            this.item = item;
            this.keyValues = keyValues;
            this.rawKeyValues = rawKeyValues;
            this.sourceName = sourceName;
        }
    }

    /**
     * A group of items sharing the same merge key.
     */
    private static class MergeGroup {
        final List<MergeItem> items = new ArrayList<>();
    }

    /**
     * Comparator for sorting merge items by composite keys.
     * Supports per-key order direction, numeric comparison, and type-aware comparison.
     */
    private static class MergeItemComparator implements Comparator<MergeItem> {
        private final ResolvedKeySpec[] specs;
        private final int keyCount;

        MergeItemComparator(ResolvedKeySpec[] specs, int keyCount) {
            this.specs = specs;
            this.keyCount = keyCount;
        }

        @Override
        public int compare(MergeItem a, MergeItem b) {
            for (int i = 0; i < keyCount; i++) {
                String aKey = i < a.keyValues.length ? a.keyValues[i] : "";
                String bKey = i < b.keyValues.length ? b.keyValues[i] : "";

                ResolvedKeySpec spec = i < specs.length ? specs[i] : specs[specs.length - 1];
                boolean ascending = "ascending".equals(spec.order);
                boolean numeric = "number".equals(spec.dataType);

                // XTTE2230: check type compatibility across sources
                XPathValue aRaw = getRawValue(a, i);
                XPathValue bRaw = getRawValue(b, i);
                if (aRaw != null && bRaw != null) {
                    checkTypeCompatibility(aRaw, bRaw);
                }

                int cmp;
                if (numeric) {
                    cmp = compareNumeric(aKey, bKey);
                } else {
                    if (aRaw != null && bRaw != null && isNumericType(aRaw) && isNumericType(bRaw)) {
                        cmp = compareNumeric(aKey, bKey);
                    } else {
                        cmp = aKey.compareTo(bKey);
                    }
                }

                if (cmp != 0) {
                    return ascending ? cmp : -cmp;
                }
            }
            return 0;
        }

        private XPathValue getRawValue(MergeItem item, int keyIndex) {
            if (item.rawKeyValues == null) {
                return null;
            }
            if (keyIndex < item.rawKeyValues.length) {
                return item.rawKeyValues[keyIndex];
            }
            return null;
        }

        private boolean isNumericType(XPathValue v) {
            if (v instanceof XPathNumber) {
                return true;
            }
            if (v == null) {
                return false;
            }
            XPathValue.Type t = v.getType();
            return t == XPathValue.Type.NUMBER;
        }

        private int compareNumeric(String aKey, String bKey) {
            double aNum = parseDouble(aKey);
            double bNum = parseDouble(bKey);
            return Double.compare(aNum, bNum);
        }

        private void checkTypeCompatibility(XPathValue a, XPathValue b) {
            boolean aIsNum = isNumericType(a);
            boolean bIsNum = isNumericType(b);
            boolean aIsDateTime = a instanceof XPathDateTime;
            boolean bIsDateTime = b instanceof XPathDateTime;
            // dateTime/date/time types are only comparable with each other
            if (aIsDateTime && !bIsDateTime) {
                throw new RuntimeException("XTTE2230: Merge key values are not " +
                    "comparable: " + a.getClass().getSimpleName() +
                    " vs " + b.getClass().getSimpleName());
            }
            if (bIsDateTime && !aIsDateTime) {
                throw new RuntimeException("XTTE2230: Merge key values are not " +
                    "comparable: " + a.getClass().getSimpleName() +
                    " vs " + b.getClass().getSimpleName());
            }
        }

        private double parseDouble(String s) {
            if (s == null || s.isEmpty()) {
                return Double.NaN;
            }
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException e) {
                return Double.NaN;
            }
        }
    }

    /**
     * Minimal output handler that captures text output as a string.
     * Used for evaluating merge-key body content.
     */
    private static class StringOutputHandler implements OutputHandler {
        private final StringBuilder sb = new StringBuilder();

        @Override
        public void characters(String text) {
            sb.append(text);
        }

        @Override
        public void charactersRaw(String text) {
            sb.append(text);
        }

        @Override
        public void startElement(String uri, String localName, String qName) {}

        @Override
        public void endElement(String uri, String localName, String qName) {}

        @Override
        public void attribute(String uri, String localName, String qName, String value) {}

        @Override
        public void namespace(String prefix, String uri) {}

        @Override
        public void processingInstruction(String target, String data) {}

        @Override
        public void comment(String text) {}

        @Override
        public void startDocument() {}

        @Override
        public void endDocument() {}

        @Override
        public void flush() {}

        String getResult() {
            return sb.toString();
        }
    }

}

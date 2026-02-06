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

import org.bluezoo.gonzalez.transform.runtime.BasicTransformContext;
import org.bluezoo.gonzalez.transform.runtime.OutputHandler;
import org.bluezoo.gonzalez.transform.runtime.TransformContext;
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
public final class MergeNode implements XSLTNode {

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
    public void execute(TransformContext context, OutputHandler output) throws SAXException {
        if (sources.isEmpty() || action == null) {
            return;
        }

        try {
            // Collect all items from all sources with their keys and source names
            List<MergeItem> allItems = new ArrayList<>();
            
            for (MergeSource source : sources) {
                collectItems(source, context, allItems);
            }

            // Sort all items by their merge keys
            MergeSource firstSource = sources.get(0);
            boolean ascending = firstSource.keys.isEmpty() || 
                !"descending".equals(firstSource.keys.get(0).order);
            
            Collections.sort(allItems, new MergeItemComparator(ascending));

            // Group items by merge key and process each group
            Map<String, List<MergeItem>> groups = new LinkedHashMap<>();
            for (MergeItem item : allItems) {
                groups.computeIfAbsent(item.keyValue, k -> new ArrayList<>()).add(item);
            }

            // Process each group
            for (Map.Entry<String, List<MergeItem>> entry : groups.entrySet()) {
                String keyValue = entry.getKey();
                List<MergeItem> groupItems = entry.getValue();
                
                // Build node sets for each source
                List<XPathNode> allNodes = new ArrayList<>();
                Map<String, List<XPathNode>> nodesBySource = new LinkedHashMap<>();
                
                for (MergeItem item : groupItems) {
                    allNodes.add(item.node);
                    nodesBySource.computeIfAbsent(
                        item.sourceName != null ? item.sourceName : "", 
                        k -> new ArrayList<>()
                    ).add(item.node);
                }
                
                // Create context with merge group info via special variables
                TransformContext groupContext = context.withPositionAndSize(1, 1);
                
                // Store merge context for current-merge-group() and current-merge-key()
                groupContext.getVariableScope().bind("__current_merge_group__", 
                    new XPathNodeSet(allNodes));
                groupContext.getVariableScope().bind("__current_merge_key__",
                    new XPathString(keyValue));
                
                // Store per-source groups for current-merge-group('name')
                for (Map.Entry<String, List<XPathNode>> sourceEntry : nodesBySource.entrySet()) {
                    groupContext.getVariableScope().bind(
                        "__current_merge_group_" + sourceEntry.getKey() + "__",
                        new XPathNodeSet(sourceEntry.getValue()));
                }
                
                action.execute(groupContext, output);
            }

        } catch (XPathException e) {
            throw new SAXException("Error in xsl:merge: " + e.getMessage(), e);
        }
    }

    /**
     * Collects items from a merge source.
     */
    private void collectItems(MergeSource source, TransformContext context, 
            List<MergeItem> items) throws XPathException, SAXException {
        
        XPathValue selectResult = source.select.evaluate(context);
        
        // Iterate over selected items
        Iterator<XPathNode> iter;
        if (selectResult instanceof XPathNodeSet) {
            iter = ((XPathNodeSet) selectResult).iterator();
        } else if (selectResult instanceof XPathSequence) {
            List<XPathNode> nodes = new ArrayList<>();
            for (XPathValue v : (XPathSequence) selectResult) {
                if (v instanceof XPathNode) {
                    nodes.add((XPathNode) v);
                } else if (v instanceof XPathNodeSet) {
                    for (XPathNode n : ((XPathNodeSet) v).getNodes()) {
                        nodes.add(n);
                    }
                }
            }
            iter = nodes.iterator();
        } else if (selectResult instanceof XPathNode) {
            iter = Collections.singletonList((XPathNode) selectResult).iterator();
        } else {
            return; // No nodes
        }

        while (iter.hasNext()) {
            XPathNode node = iter.next();
            
            // Evaluate merge key for this item
            String keyValue = evaluateMergeKey(source, node, context);
            
            items.add(new MergeItem(node, keyValue, source.name));
        }
    }

    /**
     * Evaluates the merge key for an item.
     */
    private String evaluateMergeKey(MergeSource source, XPathNode node, 
            TransformContext context) throws XPathException {
        
        if (source.keys.isEmpty()) {
            // No explicit key - use string value of node
            return node.getStringValue();
        }

        // Create context with node as context item
        TransformContext nodeCtx;
        if (context instanceof BasicTransformContext) {
            nodeCtx = ((BasicTransformContext) context).withContextNode(node);
        } else {
            nodeCtx = context.withContextNode(node);
        }

        // Evaluate first key (composite keys would concatenate)
        StringBuilder keyBuilder = new StringBuilder();
        for (MergeKey key : source.keys) {
            if (keyBuilder.length() > 0) {
                keyBuilder.append('\u0000'); // Separator for composite keys
            }
            XPathValue keyResult = key.select.evaluate(nodeCtx);
            keyBuilder.append(keyResult != null ? keyResult.asString() : "");
        }
        
        return keyBuilder.toString();
    }

    @Override
    public StreamingCapability getStreamingCapability() {
        // Merge can support streaming in certain configurations
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
        public final String order;      // "ascending" or "descending"
        public final String lang;
        public final String collation;
        public final String dataType;   // "text" or "number"

        public MergeKey(XPathExpression select, String order, String lang, 
                String collation, String dataType) {
            this.select = select;
            this.order = order != null ? order : "ascending";
            this.lang = lang;
            this.collation = collation;
            this.dataType = dataType != null ? dataType : "text";
        }
    }

    /**
     * Internal class representing an item with its merge key.
     */
    private static class MergeItem {
        final XPathNode node;
        final String keyValue;
        final String sourceName;

        MergeItem(XPathNode node, String keyValue, String sourceName) {
            this.node = node;
            this.keyValue = keyValue;
            this.sourceName = sourceName;
        }
    }

    /**
     * Comparator for sorting merge items by key.
     */
    private static class MergeItemComparator implements Comparator<MergeItem> {
        private final boolean ascending;

        MergeItemComparator(boolean ascending) {
            this.ascending = ascending;
        }

        @Override
        public int compare(MergeItem a, MergeItem b) {
            int cmp = a.keyValue.compareTo(b.keyValue);
            return ascending ? cmp : -cmp;
        }
    }

}

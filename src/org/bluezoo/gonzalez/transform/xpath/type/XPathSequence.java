/*
 * XPathSequence.java
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

package org.bluezoo.gonzalez.transform.xpath.type;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * XPath 2.0/3.1 sequence value.
 *
 * <p>A sequence is an ordered collection of zero or more items. Items can be
 * nodes or atomic values. In XPath 2.0+, all values are conceptually sequences,
 * with singleton values being sequences of length one.
 *
 * <p>Key properties:
 * <ul>
 *   <li>Sequences are ordered (unlike XPath 1.0 node-sets)</li>
 *   <li>Sequences can contain duplicates</li>
 *   <li>Sequences can contain mixed types (nodes and atomic values)</li>
 *   <li>The empty sequence () is a valid sequence</li>
 *   <li>Sequences cannot be nested (flattening occurs automatically)</li>
 * </ul>
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class XPathSequence implements XPathValue, Iterable<XPathValue> {

    /** Empty sequence singleton. */
    public static final XPathSequence EMPTY = new XPathSequence(Collections.emptyList());

    private final List<XPathValue> items;

    /**
     * Creates a new sequence containing the given items.
     * The list is not copied - caller must not modify it after construction.
     *
     * @param items the list of items
     */
    public XPathSequence(List<XPathValue> items) {
        this.items = items;
    }

    /**
     * Returns the empty sequence.
     *
     * @return the empty sequence
     */
    public static XPathSequence empty() {
        return EMPTY;
    }

    /**
     * Creates a sequence containing a single item.
     *
     * @param item the item
     * @return a sequence containing just that item
     */
    public static XPathSequence of(XPathValue item) {
        if (item == null) {
            return EMPTY;
        }
        // Flatten if item is already a sequence
        if (item instanceof XPathSequence) {
            return (XPathSequence) item;
        }
        List<XPathValue> list = new ArrayList<>(1);
        list.add(item);
        return new XPathSequence(list);
    }

    /**
     * Creates a sequence from multiple items.
     * Sequences are automatically flattened.
     *
     * @param items the items
     * @return the sequence
     */
    public static XPathSequence of(XPathValue... items) {
        if (items == null || items.length == 0) {
            return EMPTY;
        }
        List<XPathValue> list = new ArrayList<>(items.length);
        for (XPathValue item : items) {
            if (item instanceof XPathSequence) {
                // Flatten nested sequences
                list.addAll(((XPathSequence) item).items);
            } else if (item != null) {
                list.add(item);
            }
        }
        if (list.isEmpty()) {
            return EMPTY;
        }
        return new XPathSequence(list);
    }

    /**
     * Creates a sequence from a list of items.
     * Sequences are automatically flattened.
     *
     * @param items the items
     * @return the sequence
     */
    public static XPathSequence fromList(List<XPathValue> items) {
        if (items == null || items.isEmpty()) {
            return EMPTY;
        }
        List<XPathValue> list = new ArrayList<>(items.size());
        for (XPathValue item : items) {
            if (item instanceof XPathSequence) {
                // Flatten nested sequences
                list.addAll(((XPathSequence) item).items);
            } else if (item != null) {
                list.add(item);
            }
        }
        if (list.isEmpty()) {
            return EMPTY;
        }
        return new XPathSequence(list);
    }

    /**
     * Creates a sequence from a node-set.
     * Nodes are converted to sequence items in document order.
     *
     * @param nodeSet the node-set
     * @return a sequence containing the nodes
     */
    public static XPathSequence fromNodeSet(XPathNodeSet nodeSet) {
        if (nodeSet == null || nodeSet.isEmpty()) {
            return EMPTY;
        }
        List<XPathValue> list = new ArrayList<>(nodeSet.size());
        for (XPathNode node : nodeSet) {
            list.add(XPathNodeSet.of(node));
        }
        return new XPathSequence(list);
    }

    @Override
    public Type getType() {
        return Type.SEQUENCE;
    }

    @Override
    public String asString() {
        // XPath 2.0: String value is space-separated atomized values
        if (items.isEmpty()) {
            return "";
        }
        if (items.size() == 1) {
            return items.get(0).asString();
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(items.get(i).asString());
        }
        return sb.toString();
    }

    @Override
    public double asNumber() {
        // XPath 2.0: Convert first item to number
        if (items.isEmpty()) {
            return Double.NaN;
        }
        return items.get(0).asNumber();
    }

    @Override
    public boolean asBoolean() {
        // XPath 2.0: Non-empty sequence is truthy
        // But error if first item is not a node - we'll be lenient
        return !items.isEmpty();
    }

    @Override
    public XPathNodeSet asNodeSet() {
        // Convert sequence of nodes to node-set
        List<XPathNode> nodes = new ArrayList<>();
        for (XPathValue item : items) {
            XPathNodeSet ns = item.asNodeSet();
            if (ns != null) {
                for (XPathNode node : ns) {
                    nodes.add(node);
                }
            }
        }
        if (nodes.isEmpty()) {
            return XPathNodeSet.EMPTY;
        }
        return new XPathNodeSet(nodes);
    }

    @Override
    public int sequenceSize() {
        return items.size();
    }

    @Override
    public Iterator<XPathValue> sequenceIterator() {
        return Collections.unmodifiableList(items).iterator();
    }

    /**
     * Returns the number of items in this sequence.
     *
     * @return the size
     */
    public int size() {
        return items.size();
    }

    /**
     * Returns true if this sequence is empty.
     *
     * @return true if empty
     */
    public boolean isEmpty() {
        return items.isEmpty();
    }

    /**
     * Returns the item at the given index (0-based).
     *
     * @param index 0-based index
     * @return the item, or null if index is out of range
     */
    public XPathValue get(int index) {
        if (index < 0 || index >= items.size()) {
            return null;
        }
        return items.get(index);
    }

    /**
     * Returns the first item in the sequence.
     *
     * @return the first item, or null if empty
     */
    public XPathValue first() {
        return items.isEmpty() ? null : items.get(0);
    }

    /**
     * Returns the last item in the sequence.
     *
     * @return the last item, or null if empty
     */
    public XPathValue last() {
        return items.isEmpty() ? null : items.get(items.size() - 1);
    }

    /**
     * Returns an iterator over the items.
     *
     * @return item iterator
     */
    @Override
    public Iterator<XPathValue> iterator() {
        return Collections.unmodifiableList(items).iterator();
    }

    /**
     * Returns a subsequence from startIndex (0-based) to the end.
     *
     * @param startIndex the start index (0-based)
     * @return the subsequence
     */
    public XPathSequence subsequence(int startIndex) {
        if (startIndex >= items.size()) {
            return EMPTY;
        }
        if (startIndex <= 0) {
            return this;
        }
        return new XPathSequence(new ArrayList<>(items.subList(startIndex, items.size())));
    }

    /**
     * Returns a subsequence from startIndex to endIndex (0-based, exclusive).
     *
     * @param startIndex the start index (0-based)
     * @param endIndex the end index (0-based, exclusive)
     * @return the subsequence
     */
    public XPathSequence subsequence(int startIndex, int endIndex) {
        if (startIndex >= items.size() || endIndex <= startIndex) {
            return EMPTY;
        }
        startIndex = Math.max(0, startIndex);
        endIndex = Math.min(items.size(), endIndex);
        return new XPathSequence(new ArrayList<>(items.subList(startIndex, endIndex)));
    }

    /**
     * Concatenates this sequence with another.
     *
     * @param other the other sequence
     * @return the concatenated sequence
     */
    public XPathSequence concat(XPathSequence other) {
        if (this.isEmpty()) {
            return other;
        }
        if (other.isEmpty()) {
            return this;
        }
        List<XPathValue> result = new ArrayList<>(this.items.size() + other.items.size());
        result.addAll(this.items);
        result.addAll(other.items);
        return new XPathSequence(result);
    }

    /**
     * Returns the underlying list of items (for internal use).
     * Caller should not modify the returned list.
     *
     * @return the item list
     */
    public List<XPathValue> getItems() {
        return items;
    }

    /**
     * Atomizes this sequence, converting all items to atomic values.
     *
     * @return a string representing the atomized sequence
     */
    public String atomize() {
        return asString();
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof XPathSequence)) {
            return false;
        }
        XPathSequence other = (XPathSequence) obj;
        return this.items.equals(other.items);
    }

    @Override
    public int hashCode() {
        return items.hashCode();
    }

    @Override
    public String toString() {
        if (items.isEmpty()) {
            return "()";
        }
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(items.get(i));
        }
        sb.append(")");
        return sb.toString();
    }

}

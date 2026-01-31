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
     * @param items the list of items (must not be null)
     */
    public XPathSequence(List<XPathValue> items) {
        this.items = items;
    }

    /**
     * Returns the empty sequence singleton.
     *
     * @return the empty sequence
     */
    public static XPathSequence empty() {
        return EMPTY;
    }

    /**
     * Creates a sequence containing a single item.
     *
     * <p>If the item is already a sequence, it is returned unchanged (no nesting).
     *
     * @param item the item (may be null, in which case returns empty sequence)
     * @return a sequence containing just that item, or empty if item is null
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
     *
     * <p>Sequences are automatically flattened (nested sequences are unwrapped).
     * Null items are ignored.
     *
     * @param items the items (may be null or empty, in which case returns empty sequence)
     * @return the sequence containing the flattened items
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
     *
     * <p>Sequences are automatically flattened (nested sequences are unwrapped).
     * Null items are ignored.
     *
     * @param items the items (may be null or empty, in which case returns empty sequence)
     * @return the sequence containing the flattened items
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
     *
     * <p>Nodes are converted to sequence items in document order. Each node
     * is wrapped in a singleton node-set for the sequence.
     *
     * @param nodeSet the node-set (may be null or empty, in which case returns empty sequence)
     * @return a sequence containing the nodes as items
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

    /**
     * Returns the XPath type of this value.
     *
     * @return {@link Type#SEQUENCE}
     */
    @Override
    public Type getType() {
        return Type.SEQUENCE;
    }

    /**
     * Converts this sequence to a string according to XPath 2.0 rules.
     *
     * <p>The string value is the space-separated concatenation of the atomized
     * values of each item. Empty sequences return empty string.
     *
     * @return the space-separated string representation of all items
     */
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

    /**
     * Converts this sequence to a number according to XPath 2.0 rules.
     *
     * <p>The first item is converted to a number. Empty sequences return NaN.
     *
     * @return the numeric representation of the first item, or NaN if empty
     */
    @Override
    public double asNumber() {
        // XPath 2.0: Convert first item to number
        if (items.isEmpty()) {
            return Double.NaN;
        }
        return items.get(0).asNumber();
    }

    /**
     * Converts this sequence to a boolean according to XPath 2.0 rules.
     *
     * <p>Non-empty sequences are truthy. Empty sequences are falsy.
     *
     * @return true if the sequence is non-empty, false otherwise
     */
    @Override
    public boolean asBoolean() {
        // XPath 2.0: Non-empty sequence is truthy
        // But error if first item is not a node - we'll be lenient
        return !items.isEmpty();
    }

    /**
     * Converts this sequence to a node-set.
     *
     * <p>All items that are node-sets are combined into a single node-set.
     * Non-node items are ignored.
     *
     * @return a node-set containing all nodes from node-set items, or empty if none
     */
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

    /**
     * Returns the number of items in this sequence.
     *
     * @return the size of the sequence
     */
    @Override
    public int sequenceSize() {
        return items.size();
    }

    /**
     * Returns an iterator over the items in this sequence.
     *
     * @return an iterator over XPathValue items
     */
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
     * @param startIndex the start index (0-based, inclusive)
     * @return the subsequence from startIndex to the end, or empty if startIndex is out of range
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
     * @param startIndex the start index (0-based, inclusive)
     * @param endIndex the end index (0-based, exclusive)
     * @return the subsequence from startIndex to endIndex, or empty if indices are invalid
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
     * <p>The result contains all items from this sequence followed by all items
     * from the other sequence.
     *
     * @param other the other sequence (must not be null)
     * @return a new sequence containing the concatenation
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
     * <p>This is equivalent to converting the sequence to a string, which
     * automatically atomizes each item.
     *
     * @return a string representing the atomized sequence
     */
    public String atomize() {
        return asString();
    }

    /**
     * Compares this sequence with another object for equality.
     *
     * <p>Two sequences are equal if they contain the same items in the same order.
     *
     * @param obj the object to compare
     * @return true if the objects are equal
     */
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

    /**
     * Returns a hash code for this sequence.
     *
     * @return the hash code
     */
    @Override
    public int hashCode() {
        return items.hashCode();
    }

    /**
     * Returns a string representation of this sequence.
     *
     * <p>The format is "(item1, item2, ...)" or "()" for empty sequences.
     *
     * @return a string representation
     */
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

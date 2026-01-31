/*
 * XPathNodeSet.java
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
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

/**
 * XPath node-set value.
 *
 * <p>A node-set is an unordered collection of nodes without duplicates.
 * Node identity is determined by document position, not by value equality.
 *
 * <p>Although node-sets are conceptually unordered, operations that depend
 * on order (like string-value) use document order.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class XPathNodeSet implements XPathValue, Iterable<XPathNode> {

    /** Empty node-set singleton. */
    public static final XPathNodeSet EMPTY = new XPathNodeSet(Collections.emptyList());

    /** Comparator for document order. */
    private static final Comparator<XPathNode> DOCUMENT_ORDER = new Comparator<XPathNode>() {
        @Override
        public int compare(XPathNode a, XPathNode b) {
            long orderA = a.getDocumentOrder();
            long orderB = b.getDocumentOrder();
            if (orderA < orderB) {
                return -1;
            } else if (orderA > orderB) {
                return 1;
            }
            return 0;
        }
    };

    private final List<XPathNode> nodes;
    private boolean sorted;

    /**
     * Creates a new node-set containing the given nodes.
     * The list is not copied - caller must not modify it after construction.
     *
     * @param nodes the list of nodes (must not be null)
     */
    public XPathNodeSet(List<XPathNode> nodes) {
        this.nodes = nodes;
        this.sorted = false;
    }

    /**
     * Creates an empty node-set.
     *
     * @return the empty node-set singleton
     */
    public static XPathNodeSet empty() {
        return EMPTY;
    }

    /**
     * Creates a node-set containing a single node.
     *
     * @param node the node (may be null, in which case returns empty node-set)
     * @return a node-set containing just that node, or empty if node is null
     */
    public static XPathNodeSet of(XPathNode node) {
        if (node == null) {
            return EMPTY;
        }
        List<XPathNode> list = new ArrayList<>(1);
        list.add(node);
        XPathNodeSet result = new XPathNodeSet(list);
        result.sorted = true;
        return result;
    }

    /**
     * Creates a node-set from multiple nodes.
     *
     * @param nodes the nodes (may be null or empty, in which case returns empty node-set)
     * @return the node-set containing the given nodes
     */
    public static XPathNodeSet of(XPathNode... nodes) {
        if (nodes == null || nodes.length == 0) {
            return EMPTY;
        }
        List<XPathNode> list = new ArrayList<>(nodes.length);
        Collections.addAll(list, nodes);
        return new XPathNodeSet(list);
    }

    /**
     * Returns the XPath type of this value.
     *
     * @return {@link Type#NODESET}
     */
    @Override
    public Type getType() {
        return Type.NODESET;
    }

    /**
     * Converts this node-set to a string according to XPath 1.0 Section 4.2.
     *
     * <p>A node-set is converted to a string by returning the string-value of
     * the node in the node-set that is first in document order. If the node-set
     * is empty, an empty string is returned.
     *
     * @return the string-value of the first node in document order, or empty string if empty
     */
    @Override
    public String asString() {
        // XPath 1.0 Section 4.2: "A node-set is converted to a string by
        // returning the string-value of the node in the node-set that is
        // first in document order. If the node-set is empty, an empty
        // string is returned."
        if (nodes.isEmpty()) {
            return "";
        }
        ensureSorted();
        return nodes.get(0).getStringValue();
    }

    /**
     * Converts this node-set to a number according to XPath 1.0 Section 4.4.
     *
     * <p>A node-set is first converted to a string as if by a call to the string
     * function and then converted in the same way as a string argument.
     *
     * @return the numeric representation, or NaN if the string-value is not a valid number
     */
    @Override
    public double asNumber() {
        // XPath 1.0 Section 4.4: "a node-set is first converted to a string
        // as if by a call to the string function and then converted in the
        // same way as a string argument"
        String str = asString();
        if (str.isEmpty()) {
            return Double.NaN;
        }
        try {
            return Double.parseDouble(str.trim());
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    /**
     * Converts this node-set to a boolean according to XPath 1.0 Section 4.3.
     *
     * <p>A node-set is true if and only if it is non-empty.
     *
     * @return true if the node-set is non-empty, false otherwise
     */
    @Override
    public boolean asBoolean() {
        // XPath 1.0 Section 4.3: "a node-set is true if and only if it is non-empty"
        return !nodes.isEmpty();
    }

    /**
     * Returns this node-set unchanged.
     *
     * @return this node-set
     */
    @Override
    public XPathNodeSet asNodeSet() {
        return this;
    }

    /**
     * Returns the number of nodes in this set.
     *
     * @return the size
     */
    public int size() {
        return nodes.size();
    }

    /**
     * Returns true if this node-set is empty.
     *
     * @return true if empty
     */
    public boolean isEmpty() {
        return nodes.isEmpty();
    }

    /**
     * Returns the first node in document order.
     *
     * @return the first node, or null if empty
     */
    public XPathNode first() {
        if (nodes.isEmpty()) {
            return null;
        }
        ensureSorted();
        return nodes.get(0);
    }

    /**
     * Returns the last node in document order.
     *
     * @return the last node, or null if empty
     */
    public XPathNode last() {
        if (nodes.isEmpty()) {
            return null;
        }
        ensureSorted();
        return nodes.get(nodes.size() - 1);
    }

    /**
     * Returns the node at the given position (1-based, XPath semantics).
     *
     * @param position 1-based position
     * @return the node, or null if position is out of range
     */
    public XPathNode get(int position) {
        if (position < 1 || position > nodes.size()) {
            return null;
        }
        ensureSorted();
        return nodes.get(position - 1);
    }

    /**
     * Returns an iterator over the nodes in document order.
     *
     * @return node iterator
     */
    @Override
    public Iterator<XPathNode> iterator() {
        ensureSorted();
        return Collections.unmodifiableList(nodes).iterator();
    }

    /**
     * Returns true if this node-set contains the given node.
     *
     * @param node the node to test
     * @return true if the node is in this set
     */
    public boolean contains(XPathNode node) {
        for (XPathNode n : nodes) {
            if (n.isSameNode(node)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the union of this node-set and another.
     *
     * <p>The union contains all nodes from both node-sets, with duplicates removed
     * (based on node identity, not value equality).
     *
     * @param other the other node-set (must not be null)
     * @return a new node-set containing the union
     */
    public XPathNodeSet union(XPathNodeSet other) {
        if (this.isEmpty()) {
            return other;
        }
        if (other.isEmpty()) {
            return this;
        }

        List<XPathNode> result = new ArrayList<>(this.nodes.size() + other.nodes.size());
        result.addAll(this.nodes);

        // Add nodes from other that aren't already in this set
        for (XPathNode node : other.nodes) {
            if (!this.contains(node)) {
                result.add(node);
            }
        }

        return new XPathNodeSet(result);
    }

    /**
     * Returns the intersection of this node-set and another.
     *
     * <p>The intersection contains only nodes that are present in both node-sets
     * (based on node identity).
     *
     * @param other the other node-set (must not be null)
     * @return a new node-set containing the intersection
     */
    public XPathNodeSet intersect(XPathNodeSet other) {
        if (this.isEmpty() || other.isEmpty()) {
            return EMPTY;
        }

        List<XPathNode> result = new ArrayList<>();
        for (XPathNode node : this.nodes) {
            if (other.contains(node)) {
                result.add(node);
            }
        }

        if (result.isEmpty()) {
            return EMPTY;
        }
        return new XPathNodeSet(result);
    }

    /**
     * Returns the difference of this node-set and another (nodes in this but not in other).
     *
     * <p>The difference contains nodes that are in this node-set but not in the other,
     * based on node identity.
     *
     * @param other the other node-set (must not be null)
     * @return a new node-set containing the difference
     */
    public XPathNodeSet except(XPathNodeSet other) {
        if (this.isEmpty()) {
            return EMPTY;
        }
        if (other.isEmpty()) {
            return this;
        }

        List<XPathNode> result = new ArrayList<>();
        for (XPathNode node : this.nodes) {
            if (!other.contains(node)) {
                result.add(node);
            }
        }

        if (result.isEmpty()) {
            return EMPTY;
        }
        return new XPathNodeSet(result);
    }

    /**
     * Ensures the nodes are sorted in document order.
     */
    private void ensureSorted() {
        if (!sorted && nodes.size() > 1) {
            nodes.sort(DOCUMENT_ORDER);
            sorted = true;
        }
    }

    /**
     * Returns the underlying list of nodes (for internal use).
     * Caller should not modify the returned list.
     *
     * @return the node list
     */
    public List<XPathNode> getNodes() {
        ensureSorted();
        return nodes;
    }

    /**
     * Compares this node-set with another object for equality.
     *
     * <p>Two node-sets are equal if they contain the same nodes (by node identity).
     *
     * @param obj the object to compare
     * @return true if the objects are equal
     */
    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof XPathNodeSet)) {
            return false;
        }
        XPathNodeSet other = (XPathNodeSet) obj;
        if (this.size() != other.size()) {
            return false;
        }
        // Two node-sets are equal if they contain the same nodes
        for (XPathNode node : this.nodes) {
            if (!other.contains(node)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns a hash code for this node-set.
     *
     * <p>Note: This implementation uses only the size for hashing, as computing
     * a full hash would require iterating all nodes.
     *
     * @return the hash code
     */
    @Override
    public int hashCode() {
        // Simple hash based on size - full hash would require iterating all nodes
        return nodes.size();
    }

    /**
     * Returns a string representation of this node-set.
     *
     * @return a string in the format "XPathNodeSet[size=N]"
     */
    @Override
    public String toString() {
        return "XPathNodeSet[size=" + nodes.size() + "]";
    }

    /**
     * Returns the number of nodes in this node-set.
     *
     * @return the size of the node-set
     */
    @Override
    public int sequenceSize() {
        return nodes.size();
    }

    /**
     * Returns an iterator over the nodes in this node-set as XPathValue items.
     *
     * <p>Each node is wrapped in a singleton node-set for the iterator.
     *
     * @return an iterator over XPathValue items
     */
    @Override
    public Iterator<XPathValue> sequenceIterator() {
        ensureSorted();
        // Convert nodes to XPathValue iterator
        return new Iterator<XPathValue>() {
            private final Iterator<XPathNode> nodeIter = nodes.iterator();
            
            @Override
            public boolean hasNext() {
                return nodeIter.hasNext();
            }
            
            @Override
            public XPathValue next() {
                return XPathNodeSet.of(nodeIter.next());
            }
        };
    }

}

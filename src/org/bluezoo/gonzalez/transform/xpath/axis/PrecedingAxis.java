/*
 * PrecedingAxis.java
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

package org.bluezoo.gonzalez.transform.xpath.axis;

import org.bluezoo.gonzalez.transform.xpath.type.XPathNode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * The preceding axis.
 *
 * <p>Contains all nodes that appear before the context node in document order,
 * excluding ancestors. This is everything before the opening tag of the
 * context node that is not an ancestor.
 *
 * <p>This is a reverse axis that requires full document buffering for streaming,
 * as all preceding nodes have already been processed.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class PrecedingAxis implements Axis {

    /** Singleton instance. */
    public static final PrecedingAxis INSTANCE = new PrecedingAxis();

    private PrecedingAxis() {}

    /**
     * Returns the name of this axis.
     *
     * @return the axis name "preceding"
     */
    @Override
    public String getName() {
        return "preceding";
    }

    /**
     * Returns true since this is a reverse axis.
     *
     * @return true
     */
    @Override
    public boolean isReverse() {
        return true;
    }

    /**
     * Returns false since this axis requires full document buffering
     * for streaming, as all preceding nodes have already been processed.
     *
     * @return false
     */
    @Override
    public boolean supportsStreaming() {
        return false; // Requires full document buffering
    }

    /**
     * Returns an iterator over all nodes that precede the context node
     * in document order, excluding ancestors.
     *
     * <p>Nodes are returned in reverse document order, meaning the node
     * closest to the context node is returned first.
     *
     * @param contextNode the context node
     * @return iterator over preceding nodes in reverse document order
     */
    @Override
    public Iterator<XPathNode> iterate(XPathNode contextNode) {
        return new PrecedingIterator(contextNode);
    }

    /**
     * Returns the principal node type for this axis.
     *
     * @return {@link PrincipalNodeType#ELEMENT}
     */
    @Override
    public PrincipalNodeType getPrincipalNodeType() {
        return PrincipalNodeType.ELEMENT;
    }

    /**
     * Iterator that traverses preceding nodes in reverse document order.
     *
     * <p>Reverse document order means the node closest to the context node
     * is returned first. This requires collecting all preceding nodes before
     * iteration can begin.
     */
    private static class PrecedingIterator implements Iterator<XPathNode> {
        private final List<XPathNode> nodes = new ArrayList<>();
        private int index;

        /**
         * Creates a new preceding iterator.
         *
         * @param node the context node
         */
        PrecedingIterator(XPathNode node) {
            // Collect all preceding nodes (not ancestors)
            collectPreceding(node);
            // Start at beginning since nodes are already in reverse document order
            index = 0;
        }

        /**
         * Recursively collects all preceding nodes (excluding ancestors)
         * by traversing up the tree and collecting preceding siblings
         * and their subtrees.
         *
         * @param node the node to collect preceding nodes for
         */
        private void collectPreceding(XPathNode node) {
            XPathNode parent = node.getParent();
            if (parent != null) {
                // Collect preceding siblings and their subtrees (closest first)
                XPathNode sibling = node.getPrecedingSibling();
                while (sibling != null) {
                    collectSubtreeReverse(sibling);
                    sibling = sibling.getPrecedingSibling();
                }
                // Recurse to parent (but parent itself is an ancestor, not preceding)
                collectPreceding(parent);
            }
        }

        /**
         * Collects all nodes in the subtree rooted at the given node,
         * adding them in reverse document order (descendants first, then node).
         *
         * @param node the root of the subtree to collect
         */
        private void collectSubtreeReverse(XPathNode node) {
            // For reverse document order: add descendants (closest first), then node
            // Collect children first
            Iterator<XPathNode> children = node.getChildren();
            List<XPathNode> childList = new ArrayList<>();
            while (children.hasNext()) {
                childList.add(children.next());
            }
            // Process children in reverse (last child is closest to context)
            for (int i = childList.size() - 1; i >= 0; i--) {
                collectSubtreeReverse(childList.get(i));
            }
            // Then add this node (comes after its descendants in reverse order)
            nodes.add(node);
        }

        /**
         * Returns true if there are more preceding nodes to iterate.
         *
         * @return true if more preceding nodes exist
         */
        @Override
        public boolean hasNext() {
            return index < nodes.size();
        }

        /**
         * Returns the next preceding node in reverse document order.
         *
         * @return the next preceding node
         */
        @Override
        public XPathNode next() {
            return nodes.get(index++);
        }
    }

}

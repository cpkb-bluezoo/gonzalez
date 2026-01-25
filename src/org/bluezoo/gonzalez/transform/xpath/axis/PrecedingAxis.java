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

    @Override
    public String getName() {
        return "preceding";
    }

    @Override
    public boolean isReverse() {
        return true;
    }

    @Override
    public boolean supportsStreaming() {
        return false; // Requires full document buffering
    }

    @Override
    public Iterator<XPathNode> iterate(XPathNode contextNode) {
        return new PrecedingIterator(contextNode);
    }

    @Override
    public PrincipalNodeType getPrincipalNodeType() {
        return PrincipalNodeType.ELEMENT;
    }

    /**
     * Iterator that traverses preceding nodes in reverse document order.
     * Reverse document order means closest to context node first.
     */
    private static class PrecedingIterator implements Iterator<XPathNode> {
        private final List<XPathNode> nodes = new ArrayList<>();
        private int index;

        PrecedingIterator(XPathNode node) {
            // Collect all preceding nodes (not ancestors)
            collectPreceding(node);
            // Start at beginning since nodes are already in reverse document order
            index = 0;
        }

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

        @Override
        public boolean hasNext() {
            return index < nodes.size();
        }

        @Override
        public XPathNode next() {
            return nodes.get(index++);
        }
    }

}

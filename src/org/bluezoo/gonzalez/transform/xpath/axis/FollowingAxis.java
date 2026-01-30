/*
 * FollowingAxis.java
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

import org.bluezoo.gonzalez.transform.xpath.type.NodeType;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * The following axis.
 *
 * <p>Contains all nodes that appear after the context node in document order,
 * excluding descendants. This is everything after the closing tag of the
 * context node.
 *
 * <p>This is a forward axis but requires special handling in streaming mode
 * as we need to skip descendants.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class FollowingAxis implements Axis {

    /** Singleton instance. */
    public static final FollowingAxis INSTANCE = new FollowingAxis();

    private FollowingAxis() {}

    @Override
    public String getName() {
        return "following";
    }

    @Override
    public boolean isReverse() {
        return false;
    }

    @Override
    public Iterator<XPathNode> iterate(XPathNode contextNode) {
        return new FollowingIterator(contextNode);
    }

    @Override
    public PrincipalNodeType getPrincipalNodeType() {
        return PrincipalNodeType.ELEMENT;
    }

    /**
     * Iterator that traverses following nodes in document order.
     */
    private static class FollowingIterator implements Iterator<XPathNode> {
        private final List<XPathNode> pending = new ArrayList<>();
        private XPathNode next;

        FollowingIterator(XPathNode node) {
            // Special handling for attribute and namespace nodes:
            // For these, "following" includes the children of the parent element
            // (they come after the attribute in document order) plus all nodes
            // that follow the parent element
            if (node.isAttribute() || node.getNodeType() == org.bluezoo.gonzalez.transform.xpath.type.NodeType.NAMESPACE) {
                XPathNode parent = node.getParent();
                if (parent != null) {
                    // Add all children of parent element
                    Iterator<XPathNode> children = parent.getChildren();
                    while (children.hasNext()) {
                        pending.add(children.next());
                    }
                    // Then add following siblings of parent and ancestors
                    XPathNode current = parent;
                    while (current != null) {
                        XPathNode sibling = current.getFollowingSibling();
                        if (sibling != null) {
                            pending.add(sibling);
                            break;
                        }
                        current = current.getParent();
                    }
                }
            } else {
                // For regular nodes, find the first following node by going up to 
                // an ancestor that has a following sibling
                XPathNode current = node;
                while (current != null) {
                    XPathNode sibling = current.getFollowingSibling();
                    if (sibling != null) {
                        pending.add(sibling);
                        break;
                    }
                    current = current.getParent();
                }
            }
            advance();
        }

        private void advance() {
            if (pending.isEmpty()) {
                next = null;
                return;
            }

            next = pending.remove(0);

            // Add children to front of pending (depth-first traversal)
            Iterator<XPathNode> children = next.getChildren();
            List<XPathNode> childList = new ArrayList<>();
            while (children.hasNext()) {
                childList.add(children.next());
            }
            // Insert children at the front
            pending.addAll(0, childList);

            // Add following sibling at the end
            XPathNode sibling = next.getFollowingSibling();
            if (sibling != null) {
                pending.add(sibling);
            }
        }

        @Override
        public boolean hasNext() {
            return next != null;
        }

        @Override
        public XPathNode next() {
            XPathNode result = next;
            advance();
            return result;
        }
    }

}

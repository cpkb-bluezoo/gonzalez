/*
 * DescendantAxis.java
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
 * The descendant axis.
 *
 * <p>Contains the descendants of the context node. A descendant is a child
 * or a child of a child and so on. The descendant axis never contains
 * attribute or namespace nodes.
 *
 * <p>This is a forward axis that supports streaming (descendants are
 * processed after their ancestors).
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class DescendantAxis implements Axis {

    /** Singleton instance. */
    public static final DescendantAxis INSTANCE = new DescendantAxis();

    private DescendantAxis() {}

    @Override
    public String getName() {
        return "descendant";
    }

    @Override
    public boolean isReverse() {
        return false;
    }

    @Override
    public Iterator<XPathNode> iterate(XPathNode contextNode) {
        return new DescendantIterator(contextNode, false);
    }

    @Override
    public PrincipalNodeType getPrincipalNodeType() {
        return PrincipalNodeType.ELEMENT;
    }

    /**
     * Iterator that traverses descendants in document order.
     */
    static class DescendantIterator implements Iterator<XPathNode> {
        private final List<XPathNode> stack = new ArrayList<>();
        private XPathNode next;

        DescendantIterator(XPathNode node, boolean includeSelf) {
            if (includeSelf) {
                next = node;
                // Add children to stack
                addChildren(node);
            } else {
                // Start with children
                addChildren(node);
                advance();
            }
        }

        private void addChildren(XPathNode node) {
            Iterator<XPathNode> children = node.getChildren();
            List<XPathNode> childList = new ArrayList<>();
            while (children.hasNext()) {
                childList.add(children.next());
            }
            // Add in reverse order so first child is on top
            for (int i = childList.size() - 1; i >= 0; i--) {
                stack.add(childList.get(i));
            }
        }

        private void advance() {
            if (stack.isEmpty()) {
                next = null;
            } else {
                next = stack.remove(stack.size() - 1);
                addChildren(next);
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

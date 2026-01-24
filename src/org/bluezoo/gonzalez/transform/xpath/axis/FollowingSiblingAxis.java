/*
 * FollowingSiblingAxis.java
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

import java.util.Iterator;

/**
 * The following-sibling axis.
 *
 * <p>Contains all siblings of the context node that appear after it in
 * document order. If the context node is an attribute or namespace node,
 * this axis is empty.
 *
 * <p>This is a forward axis that can support streaming, though it may
 * require buffering siblings in some scenarios.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class FollowingSiblingAxis implements Axis {

    /** Singleton instance. */
    public static final FollowingSiblingAxis INSTANCE = new FollowingSiblingAxis();

    private FollowingSiblingAxis() {}

    @Override
    public String getName() {
        return "following-sibling";
    }

    @Override
    public boolean isReverse() {
        return false;
    }

    @Override
    public Iterator<XPathNode> iterate(XPathNode contextNode) {
        return new FollowingSiblingIterator(contextNode);
    }

    @Override
    public PrincipalNodeType getPrincipalNodeType() {
        return PrincipalNodeType.ELEMENT;
    }

    /**
     * Iterator that traverses following siblings.
     */
    private static class FollowingSiblingIterator implements Iterator<XPathNode> {
        private XPathNode current;

        FollowingSiblingIterator(XPathNode node) {
            current = node.getFollowingSibling();
        }

        @Override
        public boolean hasNext() {
            return current != null;
        }

        @Override
        public XPathNode next() {
            XPathNode result = current;
            current = current.getFollowingSibling();
            return result;
        }
    }

}

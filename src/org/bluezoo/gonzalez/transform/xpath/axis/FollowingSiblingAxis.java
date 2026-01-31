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

    /**
     * Returns the name of this axis.
     *
     * @return the axis name "following-sibling"
     */
    @Override
    public String getName() {
        return "following-sibling";
    }

    /**
     * Returns false since this is a forward axis.
     *
     * @return false
     */
    @Override
    public boolean isReverse() {
        return false;
    }

    /**
     * Returns an iterator over the following siblings of the context node.
     *
     * <p>Following siblings are all siblings that appear after the context
     * node in document order. If the context node is an attribute or namespace
     * node, this axis is empty.
     *
     * @param contextNode the context node whose following siblings to iterate
     * @return iterator over following sibling nodes in document order
     */
    @Override
    public Iterator<XPathNode> iterate(XPathNode contextNode) {
        return new FollowingSiblingIterator(contextNode);
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
     * Iterator that traverses following siblings in document order.
     */
    private static class FollowingSiblingIterator implements Iterator<XPathNode> {
        private XPathNode current;

        /**
         * Creates a new following sibling iterator.
         *
         * @param node the context node
         */
        FollowingSiblingIterator(XPathNode node) {
            current = node.getFollowingSibling();
        }

        /**
         * Returns true if there are more following siblings to iterate.
         *
         * @return true if more following siblings exist
         */
        @Override
        public boolean hasNext() {
            return current != null;
        }

        /**
         * Returns the next following sibling node and advances to the next sibling.
         *
         * @return the next following sibling node
         */
        @Override
        public XPathNode next() {
            XPathNode result = current;
            current = current.getFollowingSibling();
            return result;
        }
    }

}

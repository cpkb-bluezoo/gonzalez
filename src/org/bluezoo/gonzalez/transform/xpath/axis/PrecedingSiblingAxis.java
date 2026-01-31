/*
 * PrecedingSiblingAxis.java
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
 * The preceding-sibling axis.
 *
 * <p>Contains all siblings of the context node that appear before it in
 * document order. If the context node is an attribute or namespace node,
 * this axis is empty.
 *
 * <p>This is a reverse axis that requires buffering for streaming, as
 * preceding siblings have already been processed.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class PrecedingSiblingAxis implements Axis {

    /** Singleton instance. */
    public static final PrecedingSiblingAxis INSTANCE = new PrecedingSiblingAxis();

    private PrecedingSiblingAxis() {}

    /**
     * Returns the name of this axis.
     *
     * @return the axis name "preceding-sibling"
     */
    @Override
    public String getName() {
        return "preceding-sibling";
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
     * Returns false since this axis requires buffering for streaming,
     * as preceding siblings have already been processed.
     *
     * @return false
     */
    @Override
    public boolean supportsStreaming() {
        return false; // Requires buffering
    }

    /**
     * Returns an iterator over the preceding siblings of the context node.
     *
     * <p>Preceding siblings are all siblings that appear before the context
     * node in document order. They are returned in reverse document order,
     * meaning the sibling closest to the context node is returned first.
     * If the context node is an attribute or namespace node, this axis is empty.
     *
     * @param contextNode the context node whose preceding siblings to iterate
     * @return iterator over preceding sibling nodes in reverse document order
     */
    @Override
    public Iterator<XPathNode> iterate(XPathNode contextNode) {
        return new PrecedingSiblingIterator(contextNode);
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
     * Iterator that traverses preceding siblings in reverse document order.
     *
     * <p>Starts from the immediate preceding sibling and proceeds backward
     * through the sibling chain.
     */
    private static class PrecedingSiblingIterator implements Iterator<XPathNode> {
        private XPathNode current;

        /**
         * Creates a new preceding sibling iterator.
         *
         * @param node the context node
         */
        PrecedingSiblingIterator(XPathNode node) {
            current = node.getPrecedingSibling();
        }

        /**
         * Returns true if there are more preceding siblings to iterate.
         *
         * @return true if more preceding siblings exist
         */
        @Override
        public boolean hasNext() {
            return current != null;
        }

        /**
         * Returns the next preceding sibling node and advances to the previous sibling.
         *
         * @return the next preceding sibling node
         */
        @Override
        public XPathNode next() {
            XPathNode result = current;
            current = current.getPrecedingSibling();
            return result;
        }
    }

}

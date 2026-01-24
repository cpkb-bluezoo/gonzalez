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

    @Override
    public String getName() {
        return "preceding-sibling";
    }

    @Override
    public boolean isReverse() {
        return true;
    }

    @Override
    public boolean supportsStreaming() {
        return false; // Requires buffering
    }

    @Override
    public Iterator<XPathNode> iterate(XPathNode contextNode) {
        return new PrecedingSiblingIterator(contextNode);
    }

    @Override
    public PrincipalNodeType getPrincipalNodeType() {
        return PrincipalNodeType.ELEMENT;
    }

    /**
     * Iterator that traverses preceding siblings in reverse document order.
     */
    private static class PrecedingSiblingIterator implements Iterator<XPathNode> {
        private XPathNode current;

        PrecedingSiblingIterator(XPathNode node) {
            current = node.getPrecedingSibling();
        }

        @Override
        public boolean hasNext() {
            return current != null;
        }

        @Override
        public XPathNode next() {
            XPathNode result = current;
            current = current.getPrecedingSibling();
            return result;
        }
    }

}

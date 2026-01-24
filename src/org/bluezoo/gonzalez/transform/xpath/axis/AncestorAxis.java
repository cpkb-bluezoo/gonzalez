/*
 * AncestorAxis.java
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
 * The ancestor axis.
 *
 * <p>Contains the ancestors of the context node: the parent, the parent's
 * parent, and so on up to and including the root node.
 *
 * <p>This is a reverse axis that yields nodes in reverse document order
 * (from parent towards root). While this normally requires buffering for
 * streaming, ancestors can be tracked in a stack during forward traversal.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class AncestorAxis implements Axis {

    /** Singleton instance. */
    public static final AncestorAxis INSTANCE = new AncestorAxis();

    private AncestorAxis() {}

    @Override
    public String getName() {
        return "ancestor";
    }

    @Override
    public boolean isReverse() {
        return true;
    }

    @Override
    public boolean supportsStreaming() {
        // Ancestors can be supported in streaming by maintaining ancestor stack
        return true;
    }

    @Override
    public Iterator<XPathNode> iterate(XPathNode contextNode) {
        return new AncestorIterator(contextNode, false);
    }

    @Override
    public PrincipalNodeType getPrincipalNodeType() {
        return PrincipalNodeType.ELEMENT;
    }

    /**
     * Iterator that traverses ancestors in reverse document order.
     */
    static class AncestorIterator implements Iterator<XPathNode> {
        private XPathNode current;

        AncestorIterator(XPathNode node, boolean includeSelf) {
            current = includeSelf ? node : node.getParent();
        }

        @Override
        public boolean hasNext() {
            return current != null;
        }

        @Override
        public XPathNode next() {
            XPathNode result = current;
            current = current.getParent();
            return result;
        }
    }

}

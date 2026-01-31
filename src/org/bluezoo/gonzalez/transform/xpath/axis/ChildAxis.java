/*
 * ChildAxis.java
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
 * The child axis.
 *
 * <p>Contains the children of the context node. Children are elements,
 * text nodes, comments, and processing instructions that are directly
 * contained in the context node.
 *
 * <p>This is a forward axis that supports streaming.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class ChildAxis implements Axis {

    /** Singleton instance. */
    public static final ChildAxis INSTANCE = new ChildAxis();

    private ChildAxis() {}

    /**
     * Returns the name of this axis.
     *
     * @return the axis name "child"
     */
    @Override
    public String getName() {
        return "child";
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
     * Returns an iterator over the children of the context node.
     *
     * <p>Children include element nodes, text nodes, comment nodes, and
     * processing instruction nodes that are directly contained within
     * the context node.
     *
     * @param contextNode the context node whose children to iterate
     * @return iterator over child nodes in document order
     */
    @Override
    public Iterator<XPathNode> iterate(XPathNode contextNode) {
        return contextNode.getChildren();
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

}

/*
 * Axis.java
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
 * Interface for XPath axis traversal.
 *
 * <p>Each XPath axis defines a direction of navigation from a context node.
 * Implementations provide iterators that yield nodes along the axis in
 * the appropriate order (document order for forward axes, reverse document
 * order for reverse axes).
 *
 * <p>The 13 XPath axes are:
 * <ul>
 *   <li>Forward axes: child, descendant, following, following-sibling, 
 *       attribute, namespace, self, descendant-or-self</li>
 *   <li>Reverse axes: parent, ancestor, preceding, preceding-sibling, 
 *       ancestor-or-self</li>
 * </ul>
 *
 * <h2>Streaming Considerations</h2>
 * <p>Forward axes can be evaluated during streaming since they only need
 * to look at nodes that come after the current position. Reverse axes
 * require buffering because they need access to nodes that have already
 * been processed.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public interface Axis {

    /**
     * Returns the name of this axis.
     *
     * @return the axis name (e.g., "child", "ancestor")
     */
    String getName();

    /**
     * Returns true if this is a reverse axis.
     *
     * <p>Reverse axes (parent, ancestor, preceding, preceding-sibling, 
     * ancestor-or-self) select nodes in reverse document order and
     * affect the meaning of position() in predicates.
     *
     * @return true for reverse axes
     */
    boolean isReverse();

    /**
     * Returns true if this axis can be evaluated in streaming mode.
     *
     * <p>Forward axes generally support streaming, while reverse axes
     * require the document to be buffered for navigation.
     *
     * @return true if streaming evaluation is possible
     */
    default boolean supportsStreaming() {
        return !isReverse();
    }

    /**
     * Returns an iterator over nodes along this axis from the context node.
     *
     * @param contextNode the starting node
     * @return iterator over nodes along the axis
     */
    Iterator<XPathNode> iterate(XPathNode contextNode);

    /**
     * Returns the principal node type for this axis.
     *
     * <p>The principal node type determines what nodes match a name test:
     * <ul>
     *   <li>For the attribute axis: attribute</li>
     *   <li>For the namespace axis: namespace</li>
     *   <li>For all other axes: element</li>
     * </ul>
     *
     * @return the principal node type
     */
    PrincipalNodeType getPrincipalNodeType();

    /**
     * Enumeration of principal node types for axes.
     */
    enum PrincipalNodeType {
        /** Elements are the principal node type (most axes). */
        ELEMENT,
        /** Attributes are the principal node type (attribute axis). */
        ATTRIBUTE,
        /** Namespace nodes are the principal node type (namespace axis). */
        NAMESPACE
    }

}

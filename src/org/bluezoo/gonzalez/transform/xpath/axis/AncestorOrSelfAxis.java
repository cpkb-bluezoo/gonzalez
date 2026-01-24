/*
 * AncestorOrSelfAxis.java
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
 * The ancestor-or-self axis.
 *
 * <p>Contains the context node and its ancestors. This is equivalent to
 * the union of self and ancestor axes.
 *
 * <p>This is a reverse axis, but can be supported during streaming by
 * maintaining the ancestor stack.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class AncestorOrSelfAxis implements Axis {

    /** Singleton instance. */
    public static final AncestorOrSelfAxis INSTANCE = new AncestorOrSelfAxis();

    private AncestorOrSelfAxis() {}

    @Override
    public String getName() {
        return "ancestor-or-self";
    }

    @Override
    public boolean isReverse() {
        return true;
    }

    @Override
    public boolean supportsStreaming() {
        return true;
    }

    @Override
    public Iterator<XPathNode> iterate(XPathNode contextNode) {
        return new AncestorAxis.AncestorIterator(contextNode, true);
    }

    @Override
    public PrincipalNodeType getPrincipalNodeType() {
        return PrincipalNodeType.ELEMENT;
    }

}

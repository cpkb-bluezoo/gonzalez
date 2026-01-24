/*
 * DescendantOrSelfAxis.java
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
 * The descendant-or-self axis.
 *
 * <p>Contains the context node and its descendants. This is equivalent to
 * the union of self and descendant axes.
 *
 * <p>The abbreviation "//" expands to "/descendant-or-self::node()/".
 *
 * <p>This is a forward axis that supports streaming.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class DescendantOrSelfAxis implements Axis {

    /** Singleton instance. */
    public static final DescendantOrSelfAxis INSTANCE = new DescendantOrSelfAxis();

    private DescendantOrSelfAxis() {}

    @Override
    public String getName() {
        return "descendant-or-self";
    }

    @Override
    public boolean isReverse() {
        return false;
    }

    @Override
    public Iterator<XPathNode> iterate(XPathNode contextNode) {
        return new DescendantAxis.DescendantIterator(contextNode, true);
    }

    @Override
    public PrincipalNodeType getPrincipalNodeType() {
        return PrincipalNodeType.ELEMENT;
    }

}

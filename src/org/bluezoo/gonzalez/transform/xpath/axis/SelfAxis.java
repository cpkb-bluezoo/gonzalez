/*
 * SelfAxis.java
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

import java.util.Collections;
import java.util.Iterator;

/**
 * The self axis.
 *
 * <p>Contains just the context node itself.
 *
 * <p>This is a forward axis that trivially supports streaming.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class SelfAxis implements Axis {

    /** Singleton instance. */
    public static final SelfAxis INSTANCE = new SelfAxis();

    private SelfAxis() {}

    @Override
    public String getName() {
        return "self";
    }

    @Override
    public boolean isReverse() {
        return false;
    }

    @Override
    public Iterator<XPathNode> iterate(XPathNode contextNode) {
        return Collections.singleton(contextNode).iterator();
    }

    @Override
    public PrincipalNodeType getPrincipalNodeType() {
        return PrincipalNodeType.ELEMENT;
    }

}

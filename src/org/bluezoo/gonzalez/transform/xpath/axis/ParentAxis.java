/*
 * ParentAxis.java
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
 * The parent axis.
 *
 * <p>Contains the parent of the context node, if any. The parent of the
 * root node is empty. The parent of attribute and namespace nodes is the
 * element to which they belong.
 *
 * <p>This is a reverse axis. While it only returns a single node, it
 * requires access to previously processed nodes during streaming.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class ParentAxis implements Axis {

    /** Singleton instance. */
    public static final ParentAxis INSTANCE = new ParentAxis();

    private ParentAxis() {}

    @Override
    public String getName() {
        return "parent";
    }

    @Override
    public boolean isReverse() {
        return true;
    }

    @Override
    public boolean supportsStreaming() {
        // Parent axis can be supported in streaming if we maintain
        // the ancestor stack, which is typically done anyway
        return true;
    }

    @Override
    public Iterator<XPathNode> iterate(XPathNode contextNode) {
        XPathNode parent = contextNode.getParent();
        if (parent == null) {
            return Collections.emptyIterator();
        }
        return Collections.singleton(parent).iterator();
    }

    @Override
    public PrincipalNodeType getPrincipalNodeType() {
        return PrincipalNodeType.ELEMENT;
    }

}

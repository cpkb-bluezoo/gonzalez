/*
 * NamespaceAxis.java
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
 * The namespace axis.
 *
 * <p>Contains the namespace nodes of the context node. Only element nodes
 * have namespace nodes. Each in-scope namespace binding is represented as
 * a namespace node.
 *
 * <p>The principal node type for this axis is namespace, so name tests
 * match namespace prefix names.
 *
 * <p>Note: The namespace axis is deprecated in XPath 2.0 but still supported
 * in XSLT 1.0.
 *
 * <p>This is a forward axis that supports streaming (namespace bindings
 * are known when the element start tag is processed).
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class NamespaceAxis implements Axis {

    /** Singleton instance. */
    public static final NamespaceAxis INSTANCE = new NamespaceAxis();

    private NamespaceAxis() {}

    @Override
    public String getName() {
        return "namespace";
    }

    @Override
    public boolean isReverse() {
        return false;
    }

    @Override
    public Iterator<XPathNode> iterate(XPathNode contextNode) {
        return contextNode.getNamespaces();
    }

    @Override
    public PrincipalNodeType getPrincipalNodeType() {
        return PrincipalNodeType.NAMESPACE;
    }

}

/*
 * ElementTest.java
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

package org.bluezoo.gonzalez.transform.compiler;

import org.bluezoo.gonzalez.QName;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNode;

/**
 * Matches element nodes with optional namespace, local name, and type constraints.
 *
 * <p>Field semantics:
 * <ul>
 *   <li>{@code namespaceURI == null} - any namespace (wildcard)
 *   <li>{@code namespaceURI == ""} - no namespace
 *   <li>{@code localName == null} - any local name (wildcard)
 *   <li>{@code type == null} - no type constraint
 * </ul>
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
final class ElementTest implements NodeTest {

    private final String namespaceURI;
    private final String localName;
    private final QName type;

    ElementTest(String namespaceURI, String localName, QName type) {
        this.namespaceURI = namespaceURI;
        this.localName = localName;
        this.type = type;
    }

    @Override
    public boolean matches(XPathNode node) {
        if (!node.isElement()) {
            return false;
        }
        if (localName != null) {
            if (!localName.equals(node.getLocalName())) {
                return false;
            }
        }
        if (namespaceURI != null) {
            String nodeUri = node.getNamespaceURI();
            if (nodeUri == null) {
                nodeUri = "";
            }
            if (!namespaceURI.equals(nodeUri)) {
                return false;
            }
        }
        if (type != null) {
            return NodeTest.matchesTypeConstraint(node, type);
        }
        return true;
    }

    @Override
    public String toString() {
        if (namespaceURI == null && localName == null && type == null) {
            return "*";
        }
        StringBuilder sb = new StringBuilder();
        if (namespaceURI != null && !namespaceURI.isEmpty()) {
            sb.append('{');
            sb.append(namespaceURI);
            sb.append('}');
        }
        if (localName != null) {
            sb.append(localName);
        } else {
            sb.append('*');
        }
        return sb.toString();
    }
}

/*
 * NamespaceNodeTest.java
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

import org.bluezoo.gonzalez.transform.xpath.type.NodeType;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNode;

/**
 * Matches namespace nodes. Corresponds to the {@code namespace-node()} kind test.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
final class NamespaceNodeTest implements NodeTest {

    static final NamespaceNodeTest INSTANCE = new NamespaceNodeTest();

    private final String requiredPrefix;

    private NamespaceNodeTest() {
        this.requiredPrefix = null;
    }

    private NamespaceNodeTest(String requiredPrefix) {
        this.requiredPrefix = requiredPrefix;
    }

    /**
     * Returns a test that matches namespace nodes with the given prefix.
     *
     * @param prefix the namespace prefix to match (local name of the namespace node)
     * @return a node test for the named namespace
     */
    static NamespaceNodeTest named(String prefix) {
        return new NamespaceNodeTest(prefix);
    }

    @Override
    public boolean matches(XPathNode node) {
        if (node.getNodeType() != NodeType.NAMESPACE) {
            return false;
        }
        if (requiredPrefix != null) {
            String localName = node.getLocalName();
            return requiredPrefix.equals(localName);
        }
        return true;
    }

    @Override
    public String toString() {
        if (requiredPrefix != null) {
            return "namespace::" + requiredPrefix;
        }
        return "namespace-node()";
    }
}

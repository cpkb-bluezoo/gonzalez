/*
 * ChildAxisAnyNodeTest.java
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
 * Matches any node reachable on the child axis: elements, text,
 * comments, and processing instructions. Excludes root/document
 * nodes and attribute nodes.
 *
 * <p>Used for {@code node()} in match patterns where the implicit
 * axis is child.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
final class ChildAxisAnyNodeTest implements NodeTest {

    static final ChildAxisAnyNodeTest INSTANCE = new ChildAxisAnyNodeTest();

    private ChildAxisAnyNodeTest() {
    }

    @Override
    public boolean matches(XPathNode node) {
        NodeType type = node.getNodeType();
        return type == NodeType.ELEMENT || type == NodeType.TEXT ||
               type == NodeType.COMMENT ||
               type == NodeType.PROCESSING_INSTRUCTION;
    }

    @Override
    public String toString() {
        return "node()";
    }
}

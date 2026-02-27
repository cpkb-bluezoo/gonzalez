/*
 * PITest.java
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
 * Matches processing instruction nodes.
 * Corresponds to {@code processing-instruction()} or
 * {@code processing-instruction('target')}.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
final class PITest implements NodeTest {

    static final PITest ANY = new PITest(null);

    private final String target;

    PITest(String target) {
        this.target = target;
    }

    @Override
    public boolean matches(XPathNode node) {
        if (node.getNodeType() != NodeType.PROCESSING_INSTRUCTION) {
            return false;
        }
        if (target == null) {
            return true;
        }
        return target.equals(node.getLocalName());
    }

    @Override
    public String toString() {
        if (target == null) {
            return "processing-instruction()";
        }
        return "processing-instruction('" + target + "')";
    }
}

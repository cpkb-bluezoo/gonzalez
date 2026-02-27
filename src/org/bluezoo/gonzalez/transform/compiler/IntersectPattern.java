/*
 * IntersectPattern.java
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

import org.bluezoo.gonzalez.transform.runtime.TransformContext;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNode;

/**
 * XSLT 3.0 intersect pattern (left intersect right).
 * Matches if the node matches both left AND right patterns.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
final class IntersectPattern extends AbstractPattern {

    private final Pattern left;
    private final Pattern right;

    IntersectPattern(String patternStr, Pattern left, Pattern right) {
        super(patternStr, null);
        this.left = left;
        this.right = right;
    }

    @Override
    public boolean matches(XPathNode node, TransformContext context) {
        return left.matches(node, context) && right.matches(node, context);
    }

    @Override
    boolean matchesBase(XPathNode node, TransformContext context,
                        XPathNode targetNode) {
        return left.matches(node, context) && right.matches(node, context);
    }

    @Override
    public double getDefaultPriority() {
        return 0.5;
    }
}

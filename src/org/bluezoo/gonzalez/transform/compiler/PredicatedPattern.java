/*
 * PredicatedPattern.java
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
 * Wraps a pattern with an additional predicate filter.
 * Used for parenthesized patterns with predicates, e.g.
 * {@code (* except doc)[*]}.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
final class PredicatedPattern extends AbstractPattern {

    private final Pattern inner;

    PredicatedPattern(String patternStr, String predicateStr,
                      Pattern inner) {
        super(patternStr, predicateStr);
        this.inner = inner;
    }

    Pattern getInner() {
        return inner;
    }

    @Override
    boolean matchesBase(XPathNode node, TransformContext context,
                        XPathNode targetNode) {
        return inner.matches(node, context);
    }

    @Override
    public double getDefaultPriority() {
        return inner.getDefaultPriority();
    }
}

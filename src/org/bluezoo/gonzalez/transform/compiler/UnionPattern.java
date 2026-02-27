/*
 * UnionPattern.java
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
import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;

/**
 * A union pattern (pattern1 | pattern2 | ...).
 * Matches if any alternative matches.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
final class UnionPattern extends AbstractPattern {

    private final Pattern[] alternatives;

    UnionPattern(String patternStr, Pattern[] alternatives) {
        super(patternStr, null);
        this.alternatives = alternatives;
    }

    @Override
    public boolean matches(XPathNode node, TransformContext context) {
        for (int i = 0; i < alternatives.length; i++) {
            if (alternatives[i].matches(node, context)) {
                return true;
            }
        }
        return false;
    }

    @Override
    boolean matchesBase(XPathNode node, TransformContext context,
                        XPathNode targetNode) {
        for (int i = 0; i < alternatives.length; i++) {
            if (alternatives[i].matches(node, context)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean canMatchAtomicValues() {
        for (int i = 0; i < alternatives.length; i++) {
            if (alternatives[i].canMatchAtomicValues()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean matchesAtomicValue(XPathValue value,
                                      TransformContext context) {
        for (int i = 0; i < alternatives.length; i++) {
            if (alternatives[i].matchesAtomicValue(value, context)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public double getDefaultPriority() {
        double max = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < alternatives.length; i++) {
            double p = alternatives[i].getDefaultPriority();
            if (p > max) {
                max = p;
            }
        }
        return max;
    }
}

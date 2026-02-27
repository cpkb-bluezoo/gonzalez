/*
 * PatternStep.java
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

/**
 * One step in a path pattern, holding a pre-parsed node test, an axis,
 * and an optional predicate string.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
final class PatternStep {

    static final int AXIS_CHILD = 0;
    static final int AXIS_DESCENDANT = 1;
    static final int AXIS_DESCENDANT_OR_SELF = 2;
    static final int AXIS_SELF = 3;
    static final int AXIS_ATTRIBUTE = 4;

    final NodeTest nodeTest;
    final int axis;
    final String predicateStr;

    PatternStep(NodeTest nodeTest, int axis, String predicateStr) {
        this.nodeTest = nodeTest;
        this.axis = axis;
        this.predicateStr = predicateStr;
    }
}

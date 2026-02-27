/*
 * VariablePattern.java
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
import org.bluezoo.gonzalez.transform.xpath.type.XPathNodeSet;
import org.bluezoo.gonzalez.transform.xpath.type.XPathResultTreeFragment;
import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;

/**
 * XSLT 3.0 variable reference pattern ($x, $x/path, $x//path).
 * Pre-parses the variable name and optional trailing path at compile time.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
final class VariablePattern extends AbstractPattern {

    static final int AXIS_NONE = 0;
    static final int AXIS_CHILD = 1;
    static final int AXIS_DESCENDANT = 2;

    private final String varName;
    private final Pattern trailingPattern;
    private final int trailingAxis;

    VariablePattern(String patternStr, String varName,
                    Pattern trailingPattern, int trailingAxis) {
        super(patternStr, null);
        this.varName = varName;
        this.trailingPattern = trailingPattern;
        this.trailingAxis = trailingAxis;
    }

    @Override
    boolean matchesBase(XPathNode node, TransformContext context,
                        XPathNode targetNode) {
        XPathValue varValue;
        try {
            varValue = context.getVariable(null, varName);
        } catch (Exception e) {
            return false;
        }

        if (varValue == null) {
            return false;
        }

        XPathNodeSet varNodes;
        if (varValue instanceof XPathNodeSet) {
            varNodes = (XPathNodeSet) varValue;
        } else if (varValue instanceof XPathResultTreeFragment) {
            varNodes = ((XPathResultTreeFragment) varValue).asNodeSet();
        } else {
            return false;
        }

        if (trailingAxis == AXIS_NONE) {
            for (XPathNode varNode : varNodes) {
                if (node.isSameNode(varNode)) {
                    return true;
                }
            }
            return false;
        }

        if (trailingAxis == AXIS_DESCENDANT) {
            if (!trailingPattern.matches(node, context)) {
                return false;
            }
            XPathNode ancestor = node.getParent();
            while (ancestor != null) {
                for (XPathNode varNode : varNodes) {
                    if (ancestor.isSameNode(varNode)) {
                        return true;
                    }
                }
                ancestor = ancestor.getParent();
            }
            return false;
        }

        if (trailingAxis == AXIS_CHILD) {
            if (!trailingPattern.matches(node, context)) {
                return false;
            }
            XPathNode parent = node.getParent();
            if (parent != null) {
                for (XPathNode varNode : varNodes) {
                    if (parent.isSameNode(varNode)) {
                        return true;
                    }
                }
            }
            return false;
        }

        return false;
    }

    @Override
    public double getDefaultPriority() {
        return 0.5;
    }
}

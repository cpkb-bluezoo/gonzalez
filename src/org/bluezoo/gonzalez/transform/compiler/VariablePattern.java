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

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.bluezoo.gonzalez.transform.runtime.TransformContext;
import org.bluezoo.gonzalez.transform.xpath.expr.Step;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNode;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNodeSet;
import org.bluezoo.gonzalez.transform.xpath.type.XPathResultTreeFragment;
import org.bluezoo.gonzalez.transform.xpath.type.XPathSequence;
import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;

/**
 * XSLT 3.0 variable reference pattern ($x, $x/path, $x//path).
 * Pre-parses the variable name and optional trailing path at compile time.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
final class VariablePattern extends AbstractPattern {

    private final String varName;
    private final Pattern trailingPattern;
    private final Step.Axis trailingAxis;

    VariablePattern(String patternStr, String varName,
                    Pattern trailingPattern, Step.Axis trailingAxis) {
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

        List<XPathNode> varNodes = resolveVariableNodes(varValue);
        if (varNodes.isEmpty()) {
            return false;
        }

        if (trailingAxis == null) {
            for (int i = 0; i < varNodes.size(); i++) {
                if (node.isSameNode(varNodes.get(i))) {
                    return true;
                }
            }
            return false;
        }

        if (trailingAxis == Step.Axis.DESCENDANT) {
            if (!trailingPattern.matches(node, context)) {
                return false;
            }
            XPathNode ancestor = node.getParent();
            while (ancestor != null) {
                for (int i = 0; i < varNodes.size(); i++) {
                    if (ancestor.isSameNode(varNodes.get(i))) {
                        return true;
                    }
                }
                ancestor = ancestor.getParent();
            }
            return false;
        }

        if (trailingAxis == Step.Axis.CHILD) {
            if (!trailingPattern.matches(node, context)) {
                return false;
            }
            XPathNode parent = node.getParent();
            if (parent != null) {
                for (int i = 0; i < varNodes.size(); i++) {
                    if (parent.isSameNode(varNodes.get(i))) {
                        return true;
                    }
                }
            }
            return false;
        }

        return false;
    }

    /**
     * Resolves a variable value to a list of nodes. For RTFs that contain
     * a single document root, expands to the element children so that
     * node identity checks work against individual elements.
     */
    private List<XPathNode> resolveVariableNodes(XPathValue varValue) {
        if (varValue instanceof XPathNodeSet) {
            XPathNodeSet ns = (XPathNodeSet) varValue;
            List<XPathNode> result = new ArrayList<>();
            for (XPathNode n : ns) {
                result.add(n);
            }
            return result;
        }
        if (varValue instanceof XPathSequence) {
            List<XPathNode> result = new ArrayList<>();
            for (XPathValue item : (XPathSequence) varValue) {
                if (item instanceof XPathNode) {
                    result.add((XPathNode) item);
                } else if (item instanceof XPathNodeSet) {
                    for (XPathNode n : (XPathNodeSet) item) {
                        result.add(n);
                    }
                }
            }
            if (!result.isEmpty()) {
                return result;
            }
            // Fall through to try asNodeSet() conversion
            XPathNodeSet ns = varValue.asNodeSet();
            if (ns != null) {
                for (XPathNode n : ns) {
                    result.add(n);
                }
            }
            return result;
        }
        if (varValue instanceof XPathResultTreeFragment) {
            XPathNodeSet ns =
                ((XPathResultTreeFragment) varValue).asNodeSet();
            List<XPathNode> result = new ArrayList<>();
            for (XPathNode n : ns) {
                if (n.isRoot()) {
                    Iterator<XPathNode> children = n.getChildren();
                    while (children.hasNext()) {
                        result.add(children.next());
                    }
                } else {
                    result.add(n);
                }
            }
            return result;
        }
        return new ArrayList<>();
    }

    @Override
    public double getDefaultPriority() {
        return 0.5;
    }
}

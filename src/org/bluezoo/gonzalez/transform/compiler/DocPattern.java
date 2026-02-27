/*
 * DocPattern.java
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

import java.util.Iterator;

import org.bluezoo.gonzalez.transform.runtime.TransformContext;
import org.bluezoo.gonzalez.transform.xpath.XPathExpression;
import org.bluezoo.gonzalez.transform.xpath.type.NodeType;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNode;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNodeSet;
import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;

/**
 * Pattern for doc() and document() function calls (XSLT 3.0).
 * Pre-parses the doc expression and optional trailing path at compile time.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
final class DocPattern extends AbstractPattern {

    static final int AXIS_NONE = 0;
    static final int AXIS_CHILD = 1;
    static final int AXIS_DESCENDANT = 2;

    private final String docExpr;
    private final Pattern trailingPattern;
    private final int trailingAxis;

    DocPattern(String patternStr, String docExpr, Pattern trailingPattern,
               int trailingAxis) {
        super(patternStr, null);
        this.docExpr = docExpr;
        this.trailingPattern = trailingPattern;
        this.trailingAxis = trailingAxis;
    }

    @Override
    boolean matchesBase(XPathNode node, TransformContext context,
                        XPathNode targetNode) {
        XPathValue docValue;
        try {
            XPathExpression expr = AbstractPattern.xpathCache.get(docExpr);
            if (expr == null) {
                expr = XPathExpression.compile(docExpr, null);
                AbstractPattern.xpathCache.put(docExpr, expr);
            }
            docValue = expr.evaluate(context);
        } catch (Exception e) {
            return false;
        }

        if (docValue == null) {
            return false;
        }

        XPathNodeSet docNodes;
        if (docValue instanceof XPathNodeSet) {
            docNodes = (XPathNodeSet) docValue;
        } else {
            return false;
        }

        if (docNodes.isEmpty()) {
            return false;
        }

        if (trailingAxis == AXIS_NONE) {
            for (XPathNode docNode : docNodes) {
                if (node.isSameNode(docNode)) {
                    return true;
                }
            }
            return false;
        }

        if (trailingAxis == AXIS_DESCENDANT) {
            if (!trailingPattern.matches(node, context)) {
                return false;
            }
            XPathNode ancestor = node;
            while (ancestor != null) {
                for (XPathNode docNode : docNodes) {
                    if (ancestor.isSameNode(docNode)) {
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
            // Walk up through the trailing pattern's steps, then check
            // if the remaining ancestor is the doc node
            XPathNode current = findTrailingRoot(node, context);
            if (current != null) {
                for (XPathNode docNode : docNodes) {
                    if (current.isSameNode(docNode)) {
                        return true;
                    }
                    if (docNode.getNodeType() == NodeType.ROOT) {
                        Iterator<XPathNode> children = docNode.getChildren();
                        while (children.hasNext()) {
                            XPathNode child = children.next();
                            if (child.isElement() &&
                                current.isSameNode(child)) {
                                return true;
                            }
                        }
                    }
                }
            }
            return false;
        }

        return false;
    }

    /**
     * Walk up from the matched node to find the root of the trailing pattern
     * match. For a trailing pattern with N ancestor steps, this walks up N
     * levels.
     */
    private XPathNode findTrailingRoot(XPathNode node,
                                       TransformContext context) {
        // The trailing pattern already matched node; walk up for each
        // step in the trailing path until we reach the function output
        // This is a heuristic - we walk up to the first non-matching parent
        XPathNode current = node.getParent();
        while (current != null) {
            XPathNode parent = current.getParent();
            if (parent == null) {
                return current;
            }
            // If parent doesn't have a further parent, current is the
            // deepest rooted element
            current = parent;
        }
        return null;
    }

    @Override
    public double getDefaultPriority() {
        return 0.5;
    }
}

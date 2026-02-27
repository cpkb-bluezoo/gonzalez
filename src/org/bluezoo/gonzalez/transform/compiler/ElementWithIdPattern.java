/*
 * ElementWithIdPattern.java
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
 * Pattern for element-with-id() function calls (XSLT 3.0).
 * Matches elements containing a child element typed as xs:ID.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
final class ElementWithIdPattern extends AbstractPattern {

    static final int AXIS_NONE = 0;
    static final int AXIS_CHILD = 1;
    static final int AXIS_DESCENDANT = 2;

    private final String[] ids;
    private final String docVarName;
    private final Pattern trailingPattern;
    private final int trailingAxis;

    ElementWithIdPattern(String patternStr, String[] ids, String docVarName,
                         Pattern trailingPattern, int trailingAxis) {
        super(patternStr, null);
        this.ids = ids;
        this.docVarName = docVarName;
        this.trailingPattern = trailingPattern;
        this.trailingAxis = trailingAxis;
    }

    @Override
    boolean matchesBase(XPathNode node, TransformContext context,
                        XPathNode targetNode) {
        XPathNode targetDoc = null;
        if (docVarName != null) {
            try {
                XPathValue docValue =
                    context.getVariable(null, docVarName);
                if (docValue instanceof XPathNodeSet) {
                    XPathNodeSet nodes = (XPathNodeSet) docValue;
                    if (!nodes.isEmpty()) {
                        targetDoc = nodes.iterator().next();
                    }
                } else if (docValue instanceof XPathNode) {
                    targetDoc = (XPathNode) docValue;
                } else if (docValue instanceof XPathResultTreeFragment) {
                    XPathResultTreeFragment rtf =
                        (XPathResultTreeFragment) docValue;
                    XPathNodeSet rtfNodes = rtf.asNodeSet();
                    if (!rtfNodes.isEmpty()) {
                        targetDoc = rtfNodes.iterator().next();
                    }
                }
            } catch (Exception e) {
                return false;
            }
        } else {
            targetDoc = node.getRoot();
        }

        if (targetDoc == null) {
            return false;
        }

        XPathNode nodeRoot = node.getRoot();
        XPathNode targetRoot = targetDoc.getRoot();
        if (targetRoot == null) {
            targetRoot = targetDoc;
        }
        if (!nodeRoot.isSameNode(targetRoot)) {
            return false;
        }

        if (trailingAxis == AXIS_NONE) {
            return SchemaUtils.nodeHasIdTypedChild(node, ids, context);
        }

        if (trailingAxis == AXIS_DESCENDANT) {
            if (!trailingPattern.matches(node, context)) {
                return false;
            }
            XPathNode ancestor = node.getParent();
            while (ancestor != null) {
                if (SchemaUtils.nodeHasIdTypedChild(ancestor, ids, context)) {
                    return true;
                }
                ancestor = ancestor.getParent();
            }
            return false;
        }

        if (trailingAxis == AXIS_CHILD) {
            if (!trailingPattern.matches(node, context)) {
                return false;
            }
            // Walk up through trailing steps
            XPathNode idElement = walkUpTrailingSteps(node);
            if (idElement != null && idElement.isElement()) {
                return SchemaUtils.nodeHasIdTypedChild(idElement, ids,
                                                       context);
            }
            return false;
        }

        return false;
    }

    private XPathNode walkUpTrailingSteps(XPathNode node) {
        if (trailingPattern instanceof PathPattern) {
            XPathNode current = node;
            while (current != null) {
                XPathNode parent = current.getParent();
                if (parent != null && parent.isElement()) {
                    return parent;
                }
                current = parent;
            }
            return null;
        }
        return node.getParent();
    }

    @Override
    public double getDefaultPriority() {
        return 0.5;
    }
}

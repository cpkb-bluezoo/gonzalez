/*
 * IdPattern.java
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
import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;

/**
 * Pattern for id() function calls with optional trailing path.
 * Pre-parses the id values, optional document variable, and trailing
 * path/axis at compile time.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
final class IdPattern extends AbstractPattern {

    static final int AXIS_NONE = 0;
    static final int AXIS_CHILD = 1;
    static final int AXIS_DESCENDANT = 2;

    private final String[] ids;
    private final String docVarName;
    private final Pattern trailingPattern;
    private final int trailingAxis;

    IdPattern(String patternStr, String[] ids, String docVarName,
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
        if (!nodeRoot.isSameNode(targetDoc) &&
            !nodeRoot.isSameNode(targetDoc.getRoot())) {
            XPathNode targetRoot = targetDoc.getRoot();
            if (!nodeRoot.isSameNode(targetRoot)) {
                return false;
            }
        }

        if (trailingAxis == AXIS_NONE) {
            return matchesIdElement(node);
        }

        if (trailingAxis == AXIS_DESCENDANT) {
            if (!trailingPattern.matches(node, context)) {
                return false;
            }
            XPathNode ancestor = node.getParent();
            while (ancestor != null) {
                if (matchesIdElement(ancestor)) {
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
            // Walk up through trailing pattern steps to find the id element
            XPathNode idCandidate = walkUpTrailingSteps(node, context);
            if (idCandidate != null && matchesIdElement(idCandidate)) {
                return true;
            }
            return false;
        }

        return false;
    }

    private boolean matchesIdElement(XPathNode element) {
        if (!element.isElement()) {
            return false;
        }
        XPathNode idAttr = element.getAttribute("", "id");
        if (idAttr == null) {
            idAttr = element.getAttribute(
                "http://www.w3.org/XML/1998/namespace", "id");
        }
        if (idAttr == null) {
            return false;
        }
        String nodeId = idAttr.getStringValue();
        for (int i = 0; i < ids.length; i++) {
            if (ids[i].equals(nodeId)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Walk up through the trailing pattern's steps to find the element
     * that should have the id.
     */
    private XPathNode walkUpTrailingSteps(XPathNode node,
                                          TransformContext context) {
        // The trailing pattern already matched. For a child-axis trailing
        // pattern, walk up from the matched node to find the parent
        // element that should have the ID.
        if (trailingPattern instanceof PathPattern) {
            // Multi-step trailing: walk up through each step
            XPathNode current = node;
            // We need the parent of the topmost step
            // For simplicity, just walk up until we find an element with an ID
            while (current != null) {
                XPathNode parent = current.getParent();
                if (parent != null && matchesIdElement(parent)) {
                    return parent;
                }
                current = parent;
            }
            return null;
        }
        // Single-step trailing: parent is the id element
        return node.getParent();
    }

    @Override
    public double getDefaultPriority() {
        return 0.5;
    }
}

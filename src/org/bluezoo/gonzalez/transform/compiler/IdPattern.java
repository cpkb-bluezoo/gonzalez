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

import java.util.ArrayList;
import java.util.List;

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
    private final String idVarName;
    private final String docVarName;
    private final Pattern trailingPattern;
    private final int trailingAxis;

    IdPattern(String patternStr, String[] ids, String docVarName,
              Pattern trailingPattern, int trailingAxis) {
        super(patternStr, null);
        this.ids = ids;
        this.idVarName = null;
        this.docVarName = docVarName;
        this.trailingPattern = trailingPattern;
        this.trailingAxis = trailingAxis;
    }

    IdPattern(String patternStr, String idVarName, String docVarName,
              Pattern trailingPattern, int trailingAxis) {
        super(patternStr, null);
        this.ids = null;
        this.idVarName = idVarName;
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

        String[] resolvedIds = resolveIds(context);
        if (resolvedIds == null || resolvedIds.length == 0) {
            return false;
        }

        if (trailingAxis == AXIS_NONE) {
            return matchesIdElement(node, resolvedIds);
        }

        if (trailingAxis == AXIS_DESCENDANT) {
            if (!trailingPattern.matches(node, context)) {
                return false;
            }
            XPathNode ancestor = node.getParent();
            while (ancestor != null) {
                if (matchesIdElement(ancestor, resolvedIds)) {
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
            XPathNode idCandidate =
                walkUpTrailingSteps(node, context, resolvedIds);
            if (idCandidate != null &&
                matchesIdElement(idCandidate, resolvedIds)) {
                return true;
            }
            return false;
        }

        return false;
    }

    private String[] resolveIds(TransformContext context) {
        if (ids != null) {
            return ids;
        }
        if (idVarName != null) {
            try {
                XPathValue varValue =
                    context.getVariable(null, idVarName);
                if (varValue == null) {
                    return null;
                }
                String strValue = varValue.asString();
                return splitWhitespace(strValue);
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    private static String[] splitWhitespace(String s) {
        String trimmed = s.trim();
        if (trimmed.isEmpty()) {
            return new String[0];
        }
        List<String> parts = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                if (i > start) {
                    parts.add(trimmed.substring(start, i));
                }
                start = i + 1;
            }
        }
        if (start < trimmed.length()) {
            parts.add(trimmed.substring(start));
        }
        return parts.toArray(new String[0]);
    }

    private boolean matchesIdElement(XPathNode element,
                                     String[] resolvedIds) {
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
        for (int i = 0; i < resolvedIds.length; i++) {
            if (resolvedIds[i].equals(nodeId)) {
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
                                          TransformContext context,
                                          String[] resolvedIds) {
        if (trailingPattern instanceof PathPattern) {
            XPathNode current = node;
            while (current != null) {
                XPathNode parent = current.getParent();
                if (parent != null &&
                    matchesIdElement(parent, resolvedIds)) {
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

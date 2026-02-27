/*
 * KeyPattern.java
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
import org.bluezoo.gonzalez.transform.xpath.XPathContext;
import org.bluezoo.gonzalez.transform.xpath.XPathExpression;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNode;
import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;

/**
 * Pattern for key() function calls with optional trailing path (XSLT 1.0+).
 * Pre-parses the key name, key value expression, and trailing path.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
final class KeyPattern extends AbstractPattern {

    static final int AXIS_NONE = 0;
    static final int AXIS_CHILD = 1;
    static final int AXIS_DESCENDANT = 2;

    private final String keyName;
    private final String keyValueExpr;
    private final boolean isVariable;
    private final Pattern trailingPattern;
    private final int trailingAxis;

    KeyPattern(String patternStr, String keyName, String keyValueExpr,
               boolean isVariable, Pattern trailingPattern, int trailingAxis) {
        super(patternStr, null);
        this.keyName = keyName;
        this.keyValueExpr = keyValueExpr;
        this.isVariable = isVariable;
        this.trailingPattern = trailingPattern;
        this.trailingAxis = trailingAxis;
    }

    @Override
    boolean matchesBase(XPathNode node, TransformContext context,
                        XPathNode targetNode) {
        String keyValue;
        if (isVariable) {
            try {
                XPathValue varValue =
                    context.getVariable(null, keyValueExpr);
                keyValue = varValue.asString();
            } catch (Exception e) {
                return false;
            }
        } else {
            keyValue = keyValueExpr;
        }

        CompiledStylesheet stylesheet = context.getStylesheet();
        KeyDefinition keyDef = stylesheet.getKeyDefinition(keyName);
        if (keyDef == null) {
            return false;
        }

        Pattern matchPattern = keyDef.getMatchPattern();
        XPathExpression useExpr = keyDef.getUseExpr();

        XPathNode root = node.getRoot();
        List<XPathNode> keyNodes = new ArrayList<>();
        collectKeyNodes(root, matchPattern, useExpr, keyValue, keyNodes,
                        context);

        if (trailingAxis == AXIS_NONE) {
            for (int i = 0; i < keyNodes.size(); i++) {
                if (node.isSameNode(keyNodes.get(i))) {
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
                for (int i = 0; i < keyNodes.size(); i++) {
                    if (ancestor.isSameNode(keyNodes.get(i))) {
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
                for (int i = 0; i < keyNodes.size(); i++) {
                    if (parent.isSameNode(keyNodes.get(i))) {
                        return true;
                    }
                }
            }
            return false;
        }

        return false;
    }

    private void collectKeyNodes(XPathNode current, Pattern matchPattern,
                                 XPathExpression useExpr, String searchValue,
                                 List<XPathNode> result,
                                 TransformContext context) {
        try {
            if (matchPattern.matches(current, context)) {
                XPathContext nodeContext =
                    context.withContextNode(current);
                XPathValue useValue = useExpr.evaluate(nodeContext);
                String nodeKeyValue = useValue.asString();
                if (searchValue.equals(nodeKeyValue)) {
                    result.add(current);
                }
            }
        } catch (Exception e) {
            // Ignore evaluation errors
        }

        Iterator<XPathNode> children = current.getChildren();
        while (children.hasNext()) {
            collectKeyNodes(children.next(), matchPattern, useExpr,
                            searchValue, result, context);
        }
    }

    @Override
    public double getDefaultPriority() {
        return 0.5;
    }
}

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

import org.bluezoo.gonzalez.transform.ast.SequenceNode;
import org.bluezoo.gonzalez.transform.ast.XSLTNode;
import org.bluezoo.gonzalez.transform.runtime.BasicTransformContext;
import org.bluezoo.gonzalez.transform.runtime.TransformContext;
import org.bluezoo.gonzalez.transform.xpath.XPathContext;
import org.bluezoo.gonzalez.transform.xpath.XPathExpression;
import org.bluezoo.gonzalez.transform.xpath.expr.Step;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNode;
import org.bluezoo.gonzalez.transform.xpath.type.XPathSequence;
import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;

/**
 * Pattern for key() function calls with optional trailing path (XSLT 1.0+).
 * Pre-parses the key name, key value expression, and trailing path.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
final class KeyPattern extends AbstractPattern {

    private final String keyName;
    private final String keyValueExpr;
    private final boolean isVariable;
    private final Pattern trailingPattern;
    private final Step.Axis trailingAxis;

    KeyPattern(String patternStr, String keyName, String keyValueExpr,
               boolean isVariable, Pattern trailingPattern, Step.Axis trailingAxis) {
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
        List<String> keyValues = new ArrayList<>();
        if (isVariable) {
            try {
                XPathValue varValue =
                    context.getVariable(null, keyValueExpr);
                if (varValue instanceof XPathSequence) {
                    XPathSequence seq = (XPathSequence) varValue;
                    for (XPathValue item : seq) {
                        keyValues.add(item.asString());
                    }
                } else {
                    keyValues.add(varValue.asString());
                }
            } catch (Exception e) {
                return false;
            }
        } else {
            keyValues.add(keyValueExpr);
        }

        CompiledStylesheet stylesheet = context.getStylesheet();
        List<KeyDefinition> keyDefs = stylesheet.getKeyDefinitions(keyName);
        if (keyDefs.isEmpty()) {
            return false;
        }

        XPathNode root = node.getRoot();
        List<XPathNode> keyNodes = new ArrayList<>();
        for (int d = 0; d < keyDefs.size(); d++) {
            for (int v = 0; v < keyValues.size(); v++) {
                collectKeyNodes(root, keyDefs.get(d), keyValues.get(v),
                    keyNodes, context);
            }
        }

        if (trailingAxis == null) {
            for (int i = 0; i < keyNodes.size(); i++) {
                if (node.isSameNode(keyNodes.get(i))) {
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
                for (int i = 0; i < keyNodes.size(); i++) {
                    if (ancestor.isSameNode(keyNodes.get(i))) {
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

    private void collectKeyNodes(XPathNode current, KeyDefinition keyDef,
                                 String searchValue, List<XPathNode> result,
                                 TransformContext context) {
        try {
            Pattern matchPattern = keyDef.getMatchPattern();
            if (matchPattern.matches(current, context)) {
                XPathValue useValue;
                XPathExpression useExpr = keyDef.getUseExpr();
                if (useExpr != null) {
                    XPathContext nodeContext = context.withContextNode(current);
                    useValue = useExpr.evaluate(nodeContext);
                } else {
                    SequenceBuilderOutputHandler seqBuilder = new SequenceBuilderOutputHandler();
                    SequenceNode content = keyDef.getContent();
                    BasicTransformContext btx = (BasicTransformContext) context;
                    TransformContext nodeContext = btx.withXsltCurrentNode(current);
                    List<XSLTNode> contentChildren = content.getChildren();
                    for (int i = 0; i < contentChildren.size(); i++) {
                        contentChildren.get(i).execute(nodeContext, seqBuilder);
                        seqBuilder.markItemBoundary();
                    }
                    useValue = seqBuilder.getSequence();
                }
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
            collectKeyNodes(children.next(), keyDef, searchValue, result, context);
        }
    }

    @Override
    public double getDefaultPriority() {
        return 0.5;
    }
}

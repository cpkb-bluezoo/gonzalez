/*
 * AbstractPattern.java
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

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import org.bluezoo.gonzalez.transform.runtime.BasicTransformContext;
import org.bluezoo.gonzalez.transform.runtime.TransformContext;
import org.bluezoo.gonzalez.transform.xpath.XPathExpression;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNode;
import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;

/**
 * Base class for all pattern implementations.
 * Holds the original pattern string, an optional predicate, and provides
 * shared predicate evaluation logic.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
abstract class AbstractPattern implements Pattern {

    private static final double POSITION_COMPARISON_TOLERANCE = 0.0001;
    static final Map<String, XPathExpression> xpathCache = new HashMap<>();

    protected final String patternStr;
    protected final String predicateStr;

    AbstractPattern(String patternStr, String predicateStr) {
        this.patternStr = patternStr;
        this.predicateStr = predicateStr;
    }

    /**
     * Subclass-specific match logic (without predicate evaluation).
     * Called by {@link #matchesWithTarget} before applying the predicate.
     */
    abstract boolean matchesBase(XPathNode node, TransformContext context,
                                 XPathNode targetNode);

    @Override
    public boolean matches(XPathNode node, TransformContext context) {
        return matchesWithTarget(node, context, node);
    }

    boolean matchesWithTarget(XPathNode node, TransformContext context,
                              XPathNode targetNode) {
        if (!matchesBase(node, context, targetNode)) {
            return false;
        }
        if (predicateStr == null) {
            return true;
        }
        return evaluatePredicate(node, context, targetNode);
    }

    protected boolean evaluatePredicate(XPathNode node,
                                        TransformContext context,
                                        XPathNode targetNode) {
        try {
            int position = 1;
            int size = 0;

            XPathNode parent = node.getParent();
            if (parent != null) {
                Iterator<XPathNode> siblings = parent.getChildren();
                boolean foundNode = false;
                while (siblings.hasNext()) {
                    XPathNode sibling = siblings.next();
                    if (matchesBase(sibling, context, targetNode)) {
                        size++;
                        if (!foundNode) {
                            if (sibling == node) {
                                foundNode = true;
                                position = size;
                            }
                        }
                    }
                }
            } else {
                position = 1;
                size = 1;
            }

            TransformContext predContext;
            if (context instanceof BasicTransformContext) {
                BasicTransformContext btc = (BasicTransformContext) context;
                predContext = btc.withContextAndCurrentNodes(node, targetNode)
                    .withPositionAndSize(position, size);
            } else {
                predContext = context.withContextNode(node)
                    .withPositionAndSize(position, size);
            }

            XPathExpression predExpr = xpathCache.get(predicateStr);
            if (predExpr == null) {
                predExpr = XPathExpression.compile(predicateStr, null);
                xpathCache.put(predicateStr, predExpr);
            }
            XPathValue result = predExpr.evaluate(predContext);

            if (result.getType() == XPathValue.Type.NUMBER) {
                double d = result.asNumber();
                return !Double.isNaN(d) &&
                       Math.abs(d - position) < POSITION_COMPARISON_TOLERANCE;
            } else {
                return result.asBoolean();
            }
        } catch (Exception e) {
            Throwable cause = e;
            while (cause != null) {
                if (cause.getMessage() != null &&
                    cause.getMessage().contains("XTDE0640")) {
                    throw new RuntimeException(cause.getMessage(), cause);
                }
                cause = cause.getCause();
            }
            return false;
        }
    }

    @Override
    public String toString() {
        return patternStr;
    }
}

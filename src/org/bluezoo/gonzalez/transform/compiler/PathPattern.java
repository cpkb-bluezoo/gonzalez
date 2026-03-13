/*
 * PathPattern.java
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

import org.bluezoo.gonzalez.transform.runtime.BasicTransformContext;
import org.bluezoo.gonzalez.transform.runtime.TransformContext;
import org.bluezoo.gonzalez.transform.xpath.XPathExpression;
import org.bluezoo.gonzalez.transform.xpath.expr.Step;
import org.bluezoo.gonzalez.transform.xpath.type.NodeType;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNode;
import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;

/**
 * Pattern representing a multi-step path (e.g. a/b, //a/b, /a/b/c).
 * Steps are stored in document order; matching walks from the last step
 * (which must match the candidate node) backwards through parent/ancestor
 * relationships.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
final class PathPattern extends AbstractPattern {

    private final PatternStep[] steps;
    private final boolean isAbsolute;

    /**
     * @param patternStr     the original pattern string
     * @param predicateStr   optional top-level predicate (null if none)
     * @param steps          path steps in document order (first = outermost)
     * @param isAbsolute     true if the pattern starts with "/" (rooted)
     */
    PathPattern(String patternStr, String predicateStr, PatternStep[] steps,
                boolean isAbsolute) {
        super(patternStr, predicateStr);
        this.steps = steps;
        this.isAbsolute = isAbsolute;
    }

    @Override
    public NodeType getMatchableNodeType() {
        if (steps.length > 0) {
            return steps[steps.length - 1].nodeTest.getMatchableNodeType();
        }
        return null;
    }

    PatternStep[] getSteps() {
        return steps;
    }

    @Override
    boolean matchesBase(XPathNode node, TransformContext context,
                        XPathNode targetNode) {
        if (steps.length == 0) {
            return false;
        }

        PatternStep last = steps[steps.length - 1];
        if (!last.nodeTest.matches(node)) {
            return false;
        }

        boolean deferLastPredicate = last.predicateStr != null &&
            steps.length >= 2 &&
            last.axis == Step.Axis.DESCENDANT &&
            last.explicitDescendantAxis;

        if (last.predicateStr != null && !deferLastPredicate) {
            if (!evaluateStepPredicate(node, context, targetNode,
                                       last.predicateStr, last.nodeTest)) {
                return false;
            }
        }

        XPathNode current = node;
        for (int i = steps.length - 2; i >= 0; i--) {
            PatternStep step = steps[i];
            Step.Axis nextAxis = steps[i + 1].axis;

            if (nextAxis == Step.Axis.DESCENDANT ||
                nextAxis == Step.Axis.DESCENDANT_OR_SELF) {
                boolean found = false;
                if (nextAxis != Step.Axis.DESCENDANT_OR_SELF) {
                    current = current.getParent();
                }
                while (current != null) {
                    if (step.nodeTest.matches(current)) {
                        if (step.predicateStr == null ||
                            evaluateStepPredicate(current, context, targetNode,
                                                  step.predicateStr,
                                                  step.nodeTest)) {
                            if (isAbsolute && i == 0) {
                                if (step.axis == Step.Axis.DESCENDANT ||
                                    step.axis == Step.Axis.DESCENDANT_OR_SELF) {
                                    XPathNode r = current.getRoot();
                                    if (r != null && r.isRoot()) {
                                        found = true;
                                        break;
                                    }
                                } else {
                                    XPathNode root = current.getParent();
                                    if (root != null && root.getParent() == null && root.isRoot()) {
                                        found = true;
                                        break;
                                    }
                                }
                            } else {
                                found = true;
                                break;
                            }
                        }
                    }
                    current = current.getParent();
                }
                if (!found) {
                    return false;
                }

                if (deferLastPredicate && i == steps.length - 2) {
                    if (!evaluateDescendantPredicate(node, current,
                            context, targetNode, last.predicateStr,
                            last.nodeTest)) {
                        return false;
                    }
                }
            } else if (nextAxis == Step.Axis.SELF) {
                if (!step.nodeTest.matches(current)) {
                    return false;
                }
                if (step.predicateStr != null &&
                    !evaluateStepPredicate(current, context, targetNode,
                                           step.predicateStr,
                                           step.nodeTest)) {
                    return false;
                }
            } else {
                current = current.getParent();
                if (current == null) {
                    return false;
                }
                if (!step.nodeTest.matches(current)) {
                    return false;
                }
                if (step.predicateStr != null &&
                    !evaluateStepPredicate(current, context, targetNode,
                                           step.predicateStr,
                                           step.nodeTest)) {
                    return false;
                }
            }
        }

        if (isAbsolute) {
            if (steps.length > 0 &&
                (steps[0].axis == Step.Axis.DESCENDANT ||
                 steps[0].axis == Step.Axis.DESCENDANT_OR_SELF)) {
                XPathNode root = current.getRoot();
                return root != null && root.isRoot();
            }
            XPathNode root = current.getParent();
            return root != null && root.getParent() == null && root.isRoot();
        }

        return true;
    }

    /**
     * Evaluates a per-step predicate. The predicate is evaluated with the
     * step node as context, and position/size relative to siblings matching
     * the step's node test.
     */
    private boolean evaluateStepPredicate(XPathNode node,
                                          TransformContext context,
                                          XPathNode targetNode,
                                          String predStr,
                                          NodeTest stepNodeTest) {
        NameTestPattern stepPattern =
            new NameTestPattern(predStr, predStr, stepNodeTest, 0.0);
        return stepPattern.evaluatePredicate(node, context, targetNode);
    }

    /**
     * Evaluates a predicate for a descendant-axis step, computing
     * position/size relative to ALL descendants of the ancestor node
     * that match the node test (not just siblings).
     */
    private boolean evaluateDescendantPredicate(XPathNode node,
                                                XPathNode ancestor,
                                                TransformContext context,
                                                XPathNode targetNode,
                                                String predStr,
                                                NodeTest nodeTest) {
        try {
            int[] posSize = new int[]{0, 0};
            countMatchingDescendants(ancestor, node, nodeTest, posSize);
            int position = posSize[0];
            int size = posSize[1];

            if (position == 0) {
                return false;
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

            XPathExpression predExpr = xpathCache.get(predStr);
            if (predExpr == null) {
                predExpr = XPathExpression.compile(predStr, null);
                xpathCache.put(predStr, predExpr);
            }
            XPathValue result = predExpr.evaluate(predContext);

            if (result.getType() == XPathValue.Type.NUMBER) {
                double d = result.asNumber();
                return !Double.isNaN(d) &&
                       Math.abs(d - position) < 0.0001;
            } else {
                return result.asBoolean();
            }
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Walks all descendants of root in document order, counting those
     * matching nodeTest. Sets posSize[0] to the position of target
     * and posSize[1] to the total count.
     */
    private void countMatchingDescendants(XPathNode root, XPathNode target,
                                          NodeTest nodeTest, int[] posSize) {
        Iterator<XPathNode> children = root.getChildren();
        while (children.hasNext()) {
            XPathNode child = children.next();
            if (nodeTest.matches(child)) {
                posSize[1]++;
                if (child == target || child.isSameNode(target)) {
                    posSize[0] = posSize[1];
                }
            }
            countMatchingDescendants(child, target, nodeTest, posSize);
        }
    }

    @Override
    public double getDefaultPriority() {
        return 0.5;
    }
}

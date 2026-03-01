/*
 * ParenStepPathPattern.java
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
import org.bluezoo.gonzalez.transform.xpath.expr.Step;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNode;

/**
 * Pattern for a parenthesized except or intersect expression within a
 * path, e.g. {@code x/(descendant::a except child::a)}.
 *
 * <p>Unlike simple expansion (which evaluates each arm independently
 * against potentially different ancestors), this evaluates both arms
 * relative to the same ancestor context. This is necessary because
 * {@code x/(A except B)} is NOT semantically equivalent to
 * {@code (x/A except x/B)} in general.
 *
 * <p>Matching algorithm:
 * <ol>
 *   <li>Match the candidate against suffix steps to reach the paren node</li>
 *   <li>For the paren node, find ancestors matching the prefix</li>
 *   <li>For each prefix ancestor, check both arms relative to it</li>
 *   <li>Apply except/intersect: succeed if any ancestor satisfies</li>
 * </ol>
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
final class ParenStepPathPattern extends AbstractPattern {

    private final PatternStep[] prefixSteps;
    private final PatternStep[] suffixSteps;
    private final PatternStep leftStep;
    private final PatternStep rightStep;
    private final boolean isExcept;
    private final Step.Axis parenAxis;
    private final boolean isAbsolute;

    /**
     * @param patternStr   original pattern string
     * @param prefixSteps  steps before the parenthesized group
     * @param suffixSteps  steps after the parenthesized group
     * @param leftStep     left arm extracted as a single step
     * @param rightStep    right arm extracted as a single step
     * @param isExcept     true for except, false for intersect
     * @param parenAxis    axis inherited from the slash before the paren
     * @param isAbsolute   whether the overall path is absolute
     */
    ParenStepPathPattern(String patternStr,
                         PatternStep[] prefixSteps,
                         PatternStep[] suffixSteps,
                         PatternStep leftStep,
                         PatternStep rightStep,
                         boolean isExcept,
                         Step.Axis parenAxis,
                         boolean isAbsolute) {
        super(patternStr, null);
        this.prefixSteps = prefixSteps;
        this.suffixSteps = suffixSteps;
        this.leftStep = leftStep;
        this.rightStep = rightStep;
        this.isExcept = isExcept;
        this.parenAxis = parenAxis;
        this.isAbsolute = isAbsolute;
    }

    @Override
    boolean matchesBase(XPathNode node, TransformContext context,
                        XPathNode targetNode) {
        // Walk suffix steps to find the paren-position node
        XPathNode parenNode = walkSuffix(node);
        if (parenNode == null) {
            return false;
        }

        // The paren node must match the left arm's node test
        if (!leftStep.nodeTest.matches(parenNode)) {
            return false;
        }

        // Walk prefix steps to find ancestor candidates, checking
        // the except/intersect condition relative to each ancestor
        return walkPrefixWithExcept(parenNode, context, targetNode);
    }

    /**
     * Walks the suffix steps from the candidate node toward the
     * parenthesized position. Returns the node at the paren position,
     * or null if suffix matching fails.
     */
    private XPathNode walkSuffix(XPathNode node) {
        XPathNode current = node;
        for (int i = suffixSteps.length - 1; i >= 0; i--) {
            PatternStep step = suffixSteps[i];
            if (!step.nodeTest.matches(current)) {
                return null;
            }
            Step.Axis nextAxis = step.axis;
            if (nextAxis == Step.Axis.DESCENDANT ||
                nextAxis == Step.Axis.DESCENDANT_OR_SELF) {
                current = findAncestor(current, suffixSteps[i].nodeTest);
            } else {
                current = current.getParent();
            }
            if (current == null && i > 0) {
                return null;
            }
        }
        return current;
    }

    /**
     * Walks ancestors from the paren node, checking both arms relative
     * to each candidate ancestor that matches the prefix.
     */
    private boolean walkPrefixWithExcept(XPathNode parenNode,
                                          TransformContext context,
                                          XPathNode targetNode) {
        // Determine the broadest axis from the two arms to know how
        // far we can walk looking for prefix ancestors
        Step.Axis broadAxis = getBroadestAxis(leftStep.axis, rightStep.axis);

        if (broadAxis == Step.Axis.CHILD) {
            // Both arms require direct parent — only one candidate
            XPathNode parent = parenNode.getParent();
            if (parent == null) {
                return false;
            }
            return checkPrefixAndCondition(parent, parenNode,
                                            context, targetNode);
        }

        // DESCENDANT: walk up from paren node checking each ancestor
        XPathNode ancestor = parenNode.getParent();
        while (ancestor != null) {
            if (matchesPrefixFrom(ancestor)) {
                boolean leftOk = axisAllows(leftStep.axis,
                                             parenNode, ancestor);
                boolean rightOk = axisAllows(rightStep.axis,
                                              parenNode, ancestor);
                boolean result;
                if (isExcept) {
                    result = leftOk && !rightOk;
                } else {
                    result = leftOk && rightOk;
                }
                if (result) {
                    return true;
                }
            }
            ancestor = ancestor.getParent();
        }
        return false;
    }

    /**
     * Checks if a specific ancestor satisfies the prefix steps and
     * the except/intersect condition.
     */
    private boolean checkPrefixAndCondition(XPathNode ancestor,
                                             XPathNode parenNode,
                                             TransformContext context,
                                             XPathNode targetNode) {
        if (!matchesPrefixFrom(ancestor)) {
            return false;
        }
        boolean leftOk = axisAllows(leftStep.axis, parenNode, ancestor);
        boolean rightOk = axisAllows(rightStep.axis, parenNode, ancestor);
        if (isExcept) {
            return leftOk && !rightOk;
        }
        return leftOk && rightOk;
    }

    /**
     * Checks if the prefix steps match starting from the given ancestor
     * and walking further up.
     */
    private boolean matchesPrefixFrom(XPathNode startNode) {
        if (prefixSteps.length == 0) {
            if (isAbsolute) {
                XPathNode root = startNode.getRoot();
                return root != null && root.isRoot();
            }
            return true;
        }

        // The last prefix step must match startNode
        PatternStep lastPrefix = prefixSteps[prefixSteps.length - 1];
        if (!lastPrefix.nodeTest.matches(startNode)) {
            return false;
        }

        XPathNode current = startNode;
        for (int i = prefixSteps.length - 2; i >= 0; i--) {
            PatternStep step = prefixSteps[i];
            Step.Axis nextAxis = prefixSteps[i + 1].axis;

            if (nextAxis == Step.Axis.DESCENDANT ||
                nextAxis == Step.Axis.DESCENDANT_OR_SELF) {
                current = findAncestor(current, step.nodeTest);
                if (current == null) {
                    return false;
                }
            } else {
                current = current.getParent();
                if (current == null || !step.nodeTest.matches(current)) {
                    return false;
                }
            }
        }

        if (isAbsolute) {
            XPathNode root = current.getParent();
            return root != null && root.getParent() == null && root.isRoot();
        }
        return true;
    }

    /**
     * Checks whether the given axis relationship holds between a
     * candidate node and an ancestor.
     */
    private boolean axisAllows(Step.Axis axis, XPathNode node,
                                XPathNode ancestor) {
        if (axis == Step.Axis.CHILD) {
            XPathNode parent = node.getParent();
            return parent == ancestor;
        }
        if (axis == Step.Axis.DESCENDANT ||
            axis == Step.Axis.DESCENDANT_OR_SELF) {
            XPathNode current = node.getParent();
            while (current != null) {
                if (current == ancestor) {
                    return true;
                }
                current = current.getParent();
            }
            return false;
        }
        if (axis == Step.Axis.SELF) {
            return node == ancestor;
        }
        return false;
    }

    private Step.Axis getBroadestAxis(Step.Axis a, Step.Axis b) {
        if (a == Step.Axis.DESCENDANT ||
            a == Step.Axis.DESCENDANT_OR_SELF) {
            return a;
        }
        if (b == Step.Axis.DESCENDANT ||
            b == Step.Axis.DESCENDANT_OR_SELF) {
            return b;
        }
        return a;
    }

    private XPathNode findAncestor(XPathNode node, NodeTest test) {
        XPathNode current = node.getParent();
        while (current != null) {
            if (test.matches(current)) {
                return current;
            }
            current = current.getParent();
        }
        return null;
    }

    @Override
    public double getDefaultPriority() {
        return 0.5;
    }
}

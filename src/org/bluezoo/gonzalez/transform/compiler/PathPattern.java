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

import org.bluezoo.gonzalez.transform.runtime.TransformContext;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNode;

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
    boolean matchesBase(XPathNode node, TransformContext context,
                        XPathNode targetNode) {
        if (steps.length == 0) {
            return false;
        }

        // The last step must match the candidate node
        PatternStep last = steps[steps.length - 1];
        if (!last.nodeTest.matches(node)) {
            return false;
        }
        // Evaluate per-step predicate on the candidate
        if (last.predicateStr != null) {
            if (!evaluateStepPredicate(node, context, targetNode,
                                       last.predicateStr, last.nodeTest)) {
                return false;
            }
        }

        // Walk backwards through remaining steps checking ancestor chain
        XPathNode current = node;
        for (int i = steps.length - 2; i >= 0; i--) {
            PatternStep step = steps[i];
            int nextAxis = steps[i + 1].axis;

            if (nextAxis == PatternStep.AXIS_DESCENDANT ||
                nextAxis == PatternStep.AXIS_DESCENDANT_OR_SELF) {
                // Must find a matching ancestor anywhere above current
                boolean found = false;
                if (nextAxis != PatternStep.AXIS_DESCENDANT_OR_SELF) {
                    current = current.getParent();
                }
                while (current != null) {
                    if (step.nodeTest.matches(current)) {
                        if (step.predicateStr == null ||
                            evaluateStepPredicate(current, context, targetNode,
                                                  step.predicateStr,
                                                  step.nodeTest)) {
                            // For absolute patterns at the outermost step, verify
                            // this ancestor is a child of the document root
                            if (isAbsolute && i == 0) {
                                XPathNode root = current.getParent();
                                if (root != null && root.getParent() == null) {
                                    found = true;
                                    break;
                                }
                                // Not under root - keep searching ancestors
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
            } else if (nextAxis == PatternStep.AXIS_SELF) {
                // Self axis - same node must match the step
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
                // CHILD or ATTRIBUTE axis => step up to parent
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

        // If absolute, the outermost matched ancestor's parent must be the
        // document root (i.e., a node with no parent of its own)
        if (isAbsolute) {
            XPathNode root = current.getParent();
            return root != null && root.getParent() == null;
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

    @Override
    public double getDefaultPriority() {
        return 0.5;
    }
}

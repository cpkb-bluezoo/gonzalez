/*
 * StreamabilityAnalyzer.java
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

import org.bluezoo.gonzalez.transform.ast.SequenceNode;
import org.bluezoo.gonzalez.transform.ast.XSLTNode;
import org.bluezoo.gonzalez.transform.runtime.BufferingStrategy;
import org.bluezoo.gonzalez.transform.xpath.XPathExpression;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Analyzes stylesheets for streaming capability.
 *
 * <p>The StreamabilityAnalyzer examines each template, expression, and
 * instruction in a stylesheet to determine:
 *
 * <ul>
 *   <li>Whether it can be evaluated in pure streaming mode</li>
 *   <li>Whether it requires subtree buffering (grounded)</li>
 *   <li>Whether it requires full document buffering</li>
 * </ul>
 *
 * <p>Expression streamability categories (XSLT 3.0):
 *
 * <table border="1">
 *   <caption>Streamability Categories</caption>
 *   <tr><th>Category</th><th>Description</th><th>Example</th></tr>
 *   <tr><td>CONSUMING</td><td>Can only evaluate once (forward)</td><td>child::*, descendant::*</td></tr>
 *   <tr><td>GROUNDED</td><td>Needs current subtree buffered</td><td>parent::*, ancestor::*</td></tr>
 *   <tr><td>FREE_RANGING</td><td>Needs document buffering</td><td>key(), preceding::</td></tr>
 *   <tr><td>MOTIONLESS</td><td>Independent of context</td><td>$var, 'literal'</td></tr>
 * </table>
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class StreamabilityAnalyzer {

    /**
     * Streamability category for an expression.
     *
     * <p>Categories are ordered from most streamable (MOTIONLESS) to least
     * streamable (FREE_RANGING). Higher ordinal values indicate more restrictive
     * buffering requirements.
     *
     * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
     */
    public enum ExpressionStreamability {
        /** Expression is independent of streaming context (constants, variables). */
        MOTIONLESS,
        /** Expression can only be evaluated once (forward axes). */
        CONSUMING,
        /** Expression needs current subtree to be buffered. */
        GROUNDED,
        /** Expression needs full document buffering. */
        FREE_RANGING
    }

    /**
     * Analysis result for a template's streamability.
     *
     * <p>Contains the streamability category and reasons why buffering
     * might be required.
     *
     * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
     */
    public static final class TemplateStreamability {
        private final TemplateRule template;
        private final ExpressionStreamability streamability;
        private final List<String> bufferingReasons;

        /**
         * Creates a template streamability analysis result.
         *
         * @param template the analyzed template
         * @param streamability the streamability category
         * @param bufferingReasons list of reasons why buffering is needed
         */
        public TemplateStreamability(TemplateRule template, ExpressionStreamability streamability,
                                     List<String> bufferingReasons) {
            this.template = template;
            this.streamability = streamability;
            this.bufferingReasons = bufferingReasons;
        }

        /**
         * Returns the analyzed template.
         *
         * @return the template rule
         */
        public TemplateRule getTemplate() {
            return template;
        }

        /**
         * Returns the streamability category.
         *
         * @return the streamability category
         */
        public ExpressionStreamability getStreamability() {
            return streamability;
        }

        /**
         * Returns the list of reasons why buffering is required.
         *
         * @return list of buffering reasons
         */
        public List<String> getBufferingReasons() {
            return bufferingReasons;
        }

        /**
         * Returns true if the template is fully streamable.
         *
         * @return true if MOTIONLESS or CONSUMING
         */
        public boolean isFullyStreamable() {
            return streamability == ExpressionStreamability.MOTIONLESS ||
                   streamability == ExpressionStreamability.CONSUMING;
        }

        /**
         * Returns true if the template requires subtree buffering (grounding).
         *
         * @return true if GROUNDED
         */
        public boolean requiresGrounding() {
            return streamability == ExpressionStreamability.GROUNDED;
        }

        /**
         * Returns true if the template requires full document buffering.
         *
         * @return true if FREE_RANGING
         */
        public boolean requiresDocumentBuffering() {
            return streamability == ExpressionStreamability.FREE_RANGING;
        }
    }

    /**
     * Analysis result for an entire stylesheet's streamability.
     *
     * <p>Contains the overall buffering strategy and per-template analysis.
     *
     * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
     */
    public static final class StylesheetStreamability {
        private final BufferingStrategy overallStrategy;
        private final Map<TemplateRule, TemplateStreamability> templateAnalysis;
        private final List<String> bufferingReasons;

        /**
         * Creates a stylesheet streamability analysis result.
         *
         * @param overallStrategy the overall buffering strategy required
         * @param templateAnalysis map of template to its streamability analysis
         * @param bufferingReasons list of reasons why buffering is needed
         */
        public StylesheetStreamability(BufferingStrategy overallStrategy,
                                        Map<TemplateRule, TemplateStreamability> templateAnalysis,
                                        List<String> bufferingReasons) {
            this.overallStrategy = overallStrategy;
            this.templateAnalysis = templateAnalysis;
            this.bufferingReasons = bufferingReasons;
        }

        /**
         * Returns the overall buffering strategy required.
         *
         * @return the buffering strategy
         */
        public BufferingStrategy getOverallStrategy() {
            return overallStrategy;
        }

        /**
         * Returns the per-template streamability analysis.
         *
         * @return map of template to its analysis
         */
        public Map<TemplateRule, TemplateStreamability> getTemplateAnalysis() {
            return templateAnalysis;
        }

        /**
         * Returns the list of reasons why buffering is required.
         *
         * @return list of buffering reasons
         */
        public List<String> getBufferingReasons() {
            return bufferingReasons;
        }

        /**
         * Returns true if the stylesheet is fully streamable.
         *
         * @return true if no buffering is required
         */
        public boolean isFullyStreamable() {
            return overallStrategy == BufferingStrategy.NONE;
        }

        /**
         * Returns true if the stylesheet is mostly streamable (only subtree buffering).
         *
         * @return true if only GROUNDED buffering is required
         */
        public boolean isMostlyStreamable() {
            return overallStrategy == BufferingStrategy.GROUNDED;
        }
    }

    /**
     * Analyzes a compiled stylesheet for streaming capability.
     *
     * @param stylesheet the compiled stylesheet
     * @return the streamability analysis
     */
    public StylesheetStreamability analyze(CompiledStylesheet stylesheet) {
        Map<TemplateRule, TemplateStreamability> templateAnalysis = new HashMap<>();
        List<String> bufferingReasons = new ArrayList<>();
        BufferingStrategy overallStrategy = BufferingStrategy.NONE;

        // Analyze each template
        for (TemplateRule template : stylesheet.getTemplateRules()) {
            TemplateStreamability ts = analyzeTemplate(template);
            templateAnalysis.put(template, ts);

            // Update overall strategy based on template
            BufferingStrategy templateStrategy = toBufferingStrategy(ts.getStreamability());
            overallStrategy = overallStrategy.combine(templateStrategy);

            if (!ts.isFullyStreamable()) {
                bufferingReasons.addAll(ts.getBufferingReasons());
            }
        }

        // Note: If key() function is used in expressions, it would already
        // be detected by expression analysis as FREE_RANGING

        return new StylesheetStreamability(overallStrategy, templateAnalysis, bufferingReasons);
    }

    /**
     * Analyzes a single template for streaming capability.
     *
     * @param template the template rule
     * @return the template streamability
     */
    public TemplateStreamability analyzeTemplate(TemplateRule template) {
        List<String> reasons = new ArrayList<>();
        ExpressionStreamability streamability = ExpressionStreamability.MOTIONLESS;

        // Analyze the template body
        XSLTNode body = template.getBody();
        if (body != null) {
            ExpressionStreamability bodyStreamability = analyzeNode(body, reasons);
            streamability = combineStreamability(streamability, bodyStreamability);
        }

        // Check match pattern for reverse axes
        Pattern pattern = template.getMatchPattern();
        if (pattern != null && containsReverseAxis(pattern)) {
            reasons.add("Match pattern uses reverse axis");
            streamability = combineStreamability(streamability, ExpressionStreamability.GROUNDED);
        }

        return new TemplateStreamability(template, streamability, reasons);
    }

    /**
     * Analyzes an XSLT node for streamability by collecting all XPath
     * expression strings from the serialized node representation.
     */
    private ExpressionStreamability analyzeNode(XSLTNode node, List<String> reasons) {
        // Collect all expression strings from the node tree
        List<String> expressions = new ArrayList<>();
        collectExpressions(node, expressions, 0);

        ExpressionStreamability result = ExpressionStreamability.MOTIONLESS;
        for (String expr : expressions) {
            ExpressionStreamability es = analyzeExpression(expr, reasons);
            result = combineStreamability(result, es);
            if (result == ExpressionStreamability.FREE_RANGING) {
                break;
            }
        }
        return result;
    }

    /**
     * Recursively collects XPath expression strings from an XSLT node tree
     * using reflection, with depth limiting to prevent infinite recursion.
     */
    private void collectExpressions(XSLTNode node, List<String> expressions, int depth) {
        if (node == null || depth > 50) {
            return;
        }

        // Use reflection to find XPathExpression fields
        Class<?> clazz = node.getClass();
        while (clazz != null && clazz != Object.class) {
            for (Field field : clazz.getDeclaredFields()) {
                if (XPathExpression.class.isAssignableFrom(field.getType())) {
                    field.setAccessible(true);
                    try {
                        XPathExpression expr = (XPathExpression) field.get(node);
                        if (expr != null) {
                            expressions.add(expr.toString());
                        }
                    } catch (Exception e) {
                        // Skip
                    }
                }
            }
            clazz = clazz.getSuperclass();
        }

        // Recurse into SequenceNode children
        if (node instanceof SequenceNode) {
            SequenceNode seq = (SequenceNode) node;
            List<XSLTNode> children = seq.getChildren();
            if (children != null) {
                for (XSLTNode child : children) {
                    collectExpressions(child, expressions, depth + 1);
                }
            }
        }

        // Recurse into XSLTNode fields (body, content, etc.) via reflection
        clazz = node.getClass();
        while (clazz != null && clazz != Object.class) {
            for (Field field : clazz.getDeclaredFields()) {
                Class<?> fieldType = field.getType();
                if (XSLTNode.class.isAssignableFrom(fieldType) && fieldType != node.getClass()) {
                    field.setAccessible(true);
                    try {
                        XSLTNode child = (XSLTNode) field.get(node);
                        if (child != null && child != node) {
                            collectExpressions(child, expressions, depth + 1);
                        }
                    } catch (Exception e) {
                        // Skip
                    }
                }
            }
            clazz = clazz.getSuperclass();
        }
    }

    /**
     * Checks if a pattern contains reverse axes.
     */
    private boolean containsReverseAxis(Pattern pattern) {
        String patternStr = pattern.toString();
        return patternStr.contains("parent::") ||
               patternStr.contains("ancestor::") ||
               patternStr.contains("ancestor-or-self::") ||
               patternStr.contains("preceding::") ||
               patternStr.contains("preceding-sibling::") ||
               patternStr.contains("..");
    }

    /**
     * Analyzes an XPath expression string for streamability.
     *
     * @param expr the expression string
     * @param reasons list to accumulate reasons for non-streaming
     * @return the streamability category
     */
    public ExpressionStreamability analyzeExpression(String expr, List<String> reasons) {
        if (expr == null || expr.isEmpty()) {
            return ExpressionStreamability.MOTIONLESS;
        }

        // Check for forward-only axes (streamable)
        if (containsOnlyForwardAxes(expr)) {
            return ExpressionStreamability.CONSUMING;
        }

        // Check for grounded expressions
        if (containsGroundedConstruct(expr)) {
            reasons.add("Expression uses grounded construct: " + expr);
            return ExpressionStreamability.GROUNDED;
        }

        // Check for free-ranging expressions
        if (containsFreeRangingConstruct(expr)) {
            reasons.add("Expression uses free-ranging construct: " + expr);
            return ExpressionStreamability.FREE_RANGING;
        }

        // Default to consuming (forward)
        return ExpressionStreamability.CONSUMING;
    }

    /**
     * Checks if expression only uses forward axes.
     */
    private boolean containsOnlyForwardAxes(String expr) {
        // These axes are consuming (streamable)
        return !containsGroundedConstruct(expr) && !containsFreeRangingConstruct(expr);
    }

    /**
     * Checks if expression contains grounded constructs.
     * Uses strict axis detection to avoid false positives from
     * strings like ".." in URIs or "id(" in identifiers.
     */
    private boolean containsGroundedConstruct(String expr) {
        return expr.contains("parent::") ||
               expr.contains("ancestor::") ||
               expr.contains("ancestor-or-self::") ||
               expr.contains("preceding-sibling::") ||
               containsParentAbbrev(expr) ||
               containsLastFunction(expr);
    }

    /**
     * Checks if expression contains the abbreviated parent axis (..)
     * without matching single dots (.), URIs, or decimal numbers.
     */
    private boolean containsParentAbbrev(String expr) {
        int idx = 0;
        while (idx < expr.length() - 1) {
            idx = expr.indexOf("..", idx);
            if (idx < 0) {
                return false;
            }
            // Ensure it's not part of a longer sequence like "..."
            if (idx + 2 < expr.length() && expr.charAt(idx + 2) == '.') {
                idx += 3;
                continue;
            }
            // Ensure it's used as an axis step, not inside a string literal
            if (idx > 0) {
                char before = expr.charAt(idx - 1);
                if (before == '\'' || before == '"') {
                    idx += 2;
                    continue;
                }
            }
            return true;
        }
        return false;
    }

    /**
     * Checks if expression contains last() as a function call.
     */
    private boolean containsLastFunction(String expr) {
        int idx = expr.indexOf("last()");
        return idx >= 0;
    }

    /**
     * Checks if expression contains free-ranging constructs.
     */
    private boolean containsFreeRangingConstruct(String expr) {
        return expr.contains("preceding::") ||
               expr.contains("following::") ||
               containsStandaloneKeyCall(expr) ||
               expr.contains("document(");
    }

    /**
     * Checks if expression contains a standalone key() function call,
     * not part of current-grouping-key() or similar.
     */
    private boolean containsStandaloneKeyCall(String expr) {
        int idx = 0;
        while (idx < expr.length()) {
            idx = expr.indexOf("key(", idx);
            if (idx < 0) {
                return false;
            }
            // Ensure "key(" is not preceded by a letter/hyphen (e.g., "grouping-key(")
            if (idx > 0) {
                char before = expr.charAt(idx - 1);
                if (Character.isLetterOrDigit(before) || before == '-' || before == '_') {
                    idx += 4;
                    continue;
                }
            }
            return true;
        }
        return false;
    }

    /**
     * Combines two streamability values, returning the more restrictive.
     */
    private ExpressionStreamability combineStreamability(ExpressionStreamability a,
                                                          ExpressionStreamability b) {
        // Higher ordinal is more restrictive
        return a.ordinal() > b.ordinal() ? a : b;
    }

    /**
     * Converts streamability to buffering strategy.
     */
    private BufferingStrategy toBufferingStrategy(ExpressionStreamability s) {
        switch (s) {
            case MOTIONLESS:
            case CONSUMING:
                return BufferingStrategy.NONE;
            case GROUNDED:
                return BufferingStrategy.GROUNDED;
            case FREE_RANGING:
                return BufferingStrategy.FULL_DOCUMENT;
            default:
                return BufferingStrategy.NONE;
        }
    }

}

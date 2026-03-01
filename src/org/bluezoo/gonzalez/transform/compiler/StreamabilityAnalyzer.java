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

import org.bluezoo.gonzalez.transform.ast.XSLTNode;
import org.bluezoo.gonzalez.transform.runtime.BufferingStrategy;
import org.bluezoo.gonzalez.transform.xpath.XPathExpression;

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
        public TemplateStreamability(TemplateRule template,
                                     ExpressionStreamability streamability,
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
        Map<TemplateRule, TemplateStreamability> templateAnalysis =
            new HashMap<TemplateRule, TemplateStreamability>();
        List<String> bufferingReasons = new ArrayList<String>();
        BufferingStrategy overallStrategy = BufferingStrategy.NONE;

        for (TemplateRule template : stylesheet.getTemplateRules()) {
            TemplateStreamability ts = analyzeTemplate(template);
            templateAnalysis.put(template, ts);

            BufferingStrategy templateStrategy =
                toBufferingStrategy(ts.getStreamability());
            template.setBufferingStrategy(templateStrategy);
            overallStrategy = overallStrategy.combine(templateStrategy);

            if (!ts.isFullyStreamable()) {
                bufferingReasons.addAll(ts.getBufferingReasons());
            }
        }

        return new StylesheetStreamability(overallStrategy, templateAnalysis,
                                            bufferingReasons);
    }

    /**
     * Analyzes a single template for streaming capability.
     *
     * @param template the template rule
     * @return the template streamability
     */
    public TemplateStreamability analyzeTemplate(TemplateRule template) {
        List<String> reasons = new ArrayList<String>();
        ExpressionStreamability streamability =
            ExpressionStreamability.MOTIONLESS;

        // Analyze the template body via the expression interface
        XSLTNode body = template.getBody();
        if (body != null) {
            ExpressionStreamability bodyClass =
                analyzeNode(body, reasons);
            streamability = combineStreamability(streamability, bodyClass);
        }

        // Classify match pattern via AST
        Pattern pattern = template.getMatchPattern();
        if (pattern != null) {
            ExpressionStreamability patClass =
                StreamingClassifier.classifyPattern(pattern);
            if (patClass.ordinal() > ExpressionStreamability.CONSUMING.ordinal()) {
                reasons.add("Match pattern requires " +
                    patClass.name().toLowerCase().replace('_', ' '));
            }
            streamability = combineStreamability(streamability, patClass);
        }

        return new TemplateStreamability(template, streamability, reasons);
    }

    /**
     * Analyzes an XSLT node for streamability by collecting all XPath
     * expressions via the {@link ExpressionHolder} interface and
     * classifying each through the AST.
     */
    private ExpressionStreamability analyzeNode(XSLTNode node,
                                                 List<String> reasons) {
        List<XPathExpression> expressions = new ArrayList<XPathExpression>();
        collectExpressions(node, expressions, 0);

        ExpressionStreamability result = ExpressionStreamability.MOTIONLESS;
        for (int i = 0; i < expressions.size(); i++) {
            XPathExpression xpe = expressions.get(i);
            ExpressionStreamability es =
                StreamingClassifier.classify(xpe);
            if (es.ordinal() > ExpressionStreamability.CONSUMING.ordinal()) {
                reasons.add("Expression requires " +
                    es.name().toLowerCase().replace('_', ' ') + ": " + xpe);
            }
            result = combineStreamability(result, es);
            if (result == ExpressionStreamability.FREE_RANGING) {
                break;
            }
        }
        return result;
    }

    /**
     * Collects XPath expressions from an XSLT node tree using the
     * {@link ExpressionHolder} interface.
     */
    private void collectExpressions(XSLTNode node,
                                     List<XPathExpression> expressions,
                                     int depth) {
        if (node == null || depth > 50) {
            return;
        }

        if (node instanceof ExpressionHolder) {
            List<XPathExpression> nodeExprs =
                ((ExpressionHolder) node).getExpressions();
            if (nodeExprs != null) {
                expressions.addAll(nodeExprs);
            }
        }

        if (node instanceof org.bluezoo.gonzalez.transform.ast.SequenceNode) {
            org.bluezoo.gonzalez.transform.ast.SequenceNode seq =
                (org.bluezoo.gonzalez.transform.ast.SequenceNode) node;
            List<XSLTNode> children = seq.getChildren();
            if (children != null) {
                for (int i = 0; i < children.size(); i++) {
                    collectExpressions(children.get(i), expressions,
                                       depth + 1);
                }
            }
        }
    }

    /**
     * Analyzes an XPath expression for streamability using the AST
     * classifier.
     *
     * @param xpathExpr the compiled expression
     * @param reasons list to accumulate reasons for non-streaming
     * @return the streamability category
     */
    public ExpressionStreamability analyzeExpression(
            XPathExpression xpathExpr, List<String> reasons) {
        return StreamingClassifier.classify(xpathExpr);
    }

    /**
     * Analyzes an XPath expression string for streamability.
     * Falls back to string-based analysis when no AST is available.
     *
     * @param expr the expression string
     * @param reasons list to accumulate reasons for non-streaming
     * @return the streamability category
     */
    public ExpressionStreamability analyzeExpression(String expr,
                                                      List<String> reasons) {
        if (expr == null || expr.isEmpty()) {
            return ExpressionStreamability.MOTIONLESS;
        }

        // Try to parse and classify via AST
        try {
            XPathExpression compiled = XPathExpression.compile(expr);
            return StreamingClassifier.classify(compiled);
        } catch (Exception e) {
            // Parsing failed — fall back to conservative heuristic
        }

        // Fallback: string-based analysis for unparseable expressions
        if (containsGroundedConstruct(expr)) {
            reasons.add("Expression uses grounded construct: " + expr);
            return ExpressionStreamability.GROUNDED;
        }
        if (containsFreeRangingConstruct(expr)) {
            reasons.add("Expression uses free-ranging construct: " + expr);
            return ExpressionStreamability.FREE_RANGING;
        }
        return ExpressionStreamability.CONSUMING;
    }

    /**
     * Combines two streamability values, returning the more restrictive.
     */
    private ExpressionStreamability combineStreamability(
            ExpressionStreamability a, ExpressionStreamability b) {
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

    // ---- String-based fallback heuristics ----

    private boolean containsGroundedConstruct(String expr) {
        return expr.contains("parent::") ||
               expr.contains("ancestor::") ||
               expr.contains("ancestor-or-self::") ||
               expr.contains("preceding-sibling::") ||
               expr.contains("last()");
    }

    private boolean containsFreeRangingConstruct(String expr) {
        return expr.contains("preceding::") ||
               expr.contains("following::") ||
               expr.contains("document(");
    }

}

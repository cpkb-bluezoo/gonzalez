/*
 * InternalAccumulatorFactory.java
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

package org.bluezoo.gonzalez.transform.runtime;

import org.bluezoo.gonzalez.transform.compiler.CompiledStylesheet;
import org.bluezoo.gonzalez.transform.compiler.Pattern;
import org.bluezoo.gonzalez.transform.compiler.TemplateRule;
import org.bluezoo.gonzalez.transform.xpath.XPathExpression;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Factory for creating internal accumulators from XSLT 1.0/2.0 patterns.
 *
 * <p>This factory analyzes stylesheets to identify XSLT 1.0/2.0 constructs
 * that can be converted to internal accumulators for streaming execution.
 *
 * <p>Common patterns that can be converted:
 *
 * <table border="1">
 *   <caption>XSLT 1.0 to Accumulator Mappings</caption>
 *   <tr><th>XSLT 1.0 Pattern</th><th>Internal Accumulator</th></tr>
 *   <tr><td>position()</td><td>Counter accumulator</td></tr>
 *   <tr><td>count(preceding-sibling::item)</td><td>Sibling counter</td></tr>
 *   <tr><td>sum(preceding-sibling::item/@price)</td><td>Running sum</td></tr>
 *   <tr><td>last()</td><td>Requires sibling buffering (not convertible)</td></tr>
 * </table>
 *
 * <p>The factory returns a list of internal accumulators that should be
 * managed by the AccumulatorManager during transformation.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class InternalAccumulatorFactory {

    private static final AtomicLong ID_COUNTER = new AtomicLong(0);

    /**
     * Analyzes a stylesheet and creates internal accumulators for
     * streamable XSLT 1.0/2.0 patterns.
     *
     * @param stylesheet the compiled stylesheet
     * @return list of internal accumulators
     */
    public List<InternalAccumulator> createFor(CompiledStylesheet stylesheet) {
        List<InternalAccumulator> accumulators = new ArrayList<>();

        // Analyze templates for accumulator opportunities
        for (TemplateRule template : stylesheet.getTemplateRules()) {
            List<InternalAccumulator> templateAccumulators = analyzeForAccumulators(template);
            accumulators.addAll(templateAccumulators);
        }

        return accumulators;
    }

    /**
     * Analyzes a single template for accumulator opportunities.
     */
    private List<InternalAccumulator> analyzeForAccumulators(TemplateRule template) {
        List<InternalAccumulator> result = new ArrayList<>();

        // Get the template body and analyze expressions
        // For now, we focus on the match pattern context
        
        Pattern matchPattern = template.getMatchPattern();
        if (matchPattern != null) {
            String patternStr = matchPattern.toString();
            
            // Check if this is a pattern that commonly uses position()
            // For example, templates matching "item" often use position()
            if (isSimpleElementPattern(patternStr)) {
                // Create a position counter for this element type
                InternalAccumulator positionAcc = InternalAccumulator.createCounter(
                    generateId("position", patternStr),
                    matchPattern,
                    null  // Original expression will be linked later
                );
                result.add(positionAcc);
            }
        }

        return result;
    }

    /**
     * Analyzes an expression string and returns accumulators if applicable.
     *
     * @param expression the XPath expression string
     * @param contextPattern the context pattern (what element this is evaluated in)
     * @return list of internal accumulators
     */
    public List<InternalAccumulator> analyzeExpression(String expression, Pattern contextPattern) {
        List<InternalAccumulator> result = new ArrayList<>();

        if (expression == null || expression.isEmpty()) {
            return result;
        }

        // Check for position() - can be replaced with counter accumulator
        if (expression.contains("position()")) {
            result.add(InternalAccumulator.createCounter(
                generateId("position", "context"),
                contextPattern,
                null
            ));
        }

        // Check for count(preceding-sibling::...)
        if (expression.contains("count(preceding-sibling::")) {
            // Extract the node test from preceding-sibling
            String nodeTest = extractNodeTest(expression, "preceding-sibling");
            if (nodeTest != null) {
                Pattern siblingPattern = createSimplePattern(nodeTest);
                result.add(InternalAccumulator.createCounter(
                    generateId("sibling-count", nodeTest),
                    siblingPattern,
                    null
                ));
            }
        }

        // Check for sum(preceding-sibling::.../@attr)
        if (expression.contains("sum(preceding-sibling::")) {
            // This is more complex - we'd need to parse the full expression
            // For now, mark as a candidate but don't fully implement
            String nodeTest = extractNodeTest(expression, "preceding-sibling");
            if (nodeTest != null) {
                Pattern siblingPattern = createSimplePattern(nodeTest);
                result.add(InternalAccumulator.createSum(
                    generateId("sibling-sum", nodeTest),
                    siblingPattern,
                    null,  // Value expression would need to be parsed
                    null
                ));
            }
        }

        return result;
    }

    /**
     * Creates an internal accumulator for position tracking within a select expression.
     *
     * @param selectExpr the select expression being iterated
     * @return a position counter accumulator
     */
    public InternalAccumulator createPositionAccumulator(XPathExpression selectExpr) {
        return InternalAccumulator.createCounter(
            generateId("position", selectExpr != null ? selectExpr.toString() : "default"),
            null,  // Pattern will be inferred from iteration
            selectExpr
        );
    }

    /**
     * Generates a unique synthetic ID for an accumulator.
     */
    private String generateId(String type, String context) {
        return "__" + type + "_" + sanitize(context) + "_" + ID_COUNTER.incrementAndGet();
    }

    /**
     * Sanitizes a string for use in an ID.
     */
    private String sanitize(String s) {
        if (s == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')) {
                sb.append(Character.toLowerCase(c));
            } else {
                sb.append('_');
            }
        }
        return sb.toString();
    }

    /**
     * Checks if a pattern is a simple element match (no predicates).
     */
    private boolean isSimpleElementPattern(String patternStr) {
        if (patternStr == null) {
            return false;
        }
        // Simple patterns don't contain predicates or complex paths
        return !patternStr.contains("[") && 
               !patternStr.contains("/") && 
               !patternStr.contains("|");
    }

    /**
     * Extracts a node test from an axis expression.
     * E.g., from "count(preceding-sibling::item)" extracts "item"
     */
    private String extractNodeTest(String expr, String axis) {
        String search = axis + "::";
        int idx = expr.indexOf(search);
        if (idx < 0) {
            return null;
        }
        
        int start = idx + search.length();
        int end = start;
        while (end < expr.length()) {
            char c = expr.charAt(end);
            if (!Character.isLetterOrDigit(c) && c != ':' && c != '*' && c != '_') {
                break;
            }
            end++;
        }
        
        if (end > start) {
            return expr.substring(start, end);
        }
        return null;
    }

    /**
     * Creates a simple pattern for a node test.
     */
    private Pattern createSimplePattern(String nodeTest) {
        // Return a placeholder pattern
        // In a full implementation, this would parse the node test
        return new Pattern() {
            @Override
            public boolean matches(org.bluezoo.gonzalez.transform.xpath.type.XPathNode node,
                                   TransformContext context) {
                if (node == null) {
                    return false;
                }
                if ("*".equals(nodeTest)) {
                    return node.getNodeType() == org.bluezoo.gonzalez.transform.xpath.type.NodeType.ELEMENT;
                }
                return nodeTest.equals(node.getLocalName());
            }

            @Override
            public double getDefaultPriority() {
                if ("*".equals(nodeTest)) {
                    return -0.5;
                }
                return 0.0;
            }

            @Override
            public String toString() {
                return nodeTest;
            }
        };
    }

}

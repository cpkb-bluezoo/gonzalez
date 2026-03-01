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
import org.bluezoo.gonzalez.transform.compiler.ExpressionHolder;
import org.bluezoo.gonzalez.transform.compiler.Pattern;
import org.bluezoo.gonzalez.transform.compiler.StreamingClassifier;
import org.bluezoo.gonzalez.transform.compiler.StreamabilityAnalyzer.ExpressionStreamability;
import org.bluezoo.gonzalez.transform.compiler.TemplateRule;
import org.bluezoo.gonzalez.transform.ast.XSLTNode;
import org.bluezoo.gonzalez.transform.xpath.XPathExpression;
import org.bluezoo.gonzalez.transform.xpath.expr.Expr;
import org.bluezoo.gonzalez.transform.xpath.expr.FunctionCall;
import org.bluezoo.gonzalez.transform.xpath.expr.LocationPath;
import org.bluezoo.gonzalez.transform.xpath.expr.Step;
import org.bluezoo.gonzalez.transform.xpath.type.NodeType;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNode;

import java.util.ArrayList;
import java.util.List;
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
        List<InternalAccumulator> accumulators = new ArrayList<InternalAccumulator>();

        for (TemplateRule template : stylesheet.getTemplateRules()) {
            List<InternalAccumulator> templateAccs =
                analyzeForAccumulators(template);
            accumulators.addAll(templateAccs);
        }

        return accumulators;
    }

    /**
     * Analyzes a single template for accumulator opportunities using
     * the expression AST.
     */
    private List<InternalAccumulator> analyzeForAccumulators(
            TemplateRule template) {
        List<InternalAccumulator> result = new ArrayList<InternalAccumulator>();

        Pattern matchPattern = template.getMatchPattern();
        if (matchPattern == null) {
            return result;
        }

        XSLTNode body = template.getBody();
        if (body == null) {
            return result;
        }

        // Collect all XPath expressions from the template body
        if (body instanceof ExpressionHolder) {
            List<XPathExpression> expressions =
                ((ExpressionHolder) body).getExpressions();
            for (int i = 0; i < expressions.size(); i++) {
                XPathExpression xpe = expressions.get(i);
                analyzeExprForAccumulators(xpe, matchPattern, result);
            }
        }

        return result;
    }

    /**
     * Analyzes a single XPath expression for accumulator opportunities
     * by walking the AST.
     */
    private void analyzeExprForAccumulators(XPathExpression xpe,
                                             Pattern contextPattern,
                                             List<InternalAccumulator> result) {
        if (xpe == null) {
            return;
        }
        Expr expr = xpe.getCompiledExpr();
        if (expr == null) {
            return;
        }

        // Check for position() calls
        if (expr instanceof FunctionCall) {
            FunctionCall fc = (FunctionCall) expr;
            String name = fc.getLocalName();
            if ("position".equals(name)) {
                result.add(InternalAccumulator.createCounter(
                    generateId("position", contextPattern.toString()),
                    contextPattern,
                    xpe
                ));
            }
        }

        // Check for count(preceding-sibling::*) patterns
        if (expr instanceof FunctionCall) {
            FunctionCall fc = (FunctionCall) expr;
            String name = fc.getLocalName();
            if ("count".equals(name) || "sum".equals(name)) {
                List<Expr> args = fc.getArguments();
                if (args.size() == 1) {
                    Expr arg = args.get(0);
                    if (arg instanceof LocationPath) {
                        LocationPath lp = (LocationPath) arg;
                        List<Step> steps = lp.getSteps();
                        if (steps.size() > 0) {
                            Step first = steps.get(0);
                            Step.Axis axis = first.getAxis();
                            if (axis == Step.Axis.PRECEDING_SIBLING) {
                                if ("count".equals(name)) {
                                    result.add(
                                        InternalAccumulator.createCounter(
                                            generateId("sibling-count",
                                                contextPattern.toString()),
                                            contextPattern,
                                            xpe
                                        ));
                                } else {
                                    result.add(
                                        InternalAccumulator.createSum(
                                            generateId("sibling-sum",
                                                contextPattern.toString()),
                                            contextPattern,
                                            xpe,
                                            null
                                        ));
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Creates an internal accumulator for position tracking within a
     * select expression.
     *
     * @param selectExpr the select expression being iterated
     * @return a position counter accumulator
     */
    public InternalAccumulator createPositionAccumulator(
            XPathExpression selectExpr) {
        String context = selectExpr != null ?
            selectExpr.toString() : "default";
        return InternalAccumulator.createCounter(
            generateId("position", context),
            null,
            selectExpr
        );
    }

    /**
     * Generates a unique synthetic ID for an accumulator.
     */
    private String generateId(String type, String context) {
        String sanitized = sanitize(context);
        long id = ID_COUNTER.incrementAndGet();
        return "__" + type + "_" + sanitized + "_" + id;
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
            boolean isLetter = (c >= 'a' && c <= 'z') ||
                               (c >= 'A' && c <= 'Z');
            boolean isDigit = (c >= '0' && c <= '9');
            if (isLetter || isDigit) {
                sb.append(Character.toLowerCase(c));
            } else {
                sb.append('_');
            }
        }
        return sb.toString();
    }

}

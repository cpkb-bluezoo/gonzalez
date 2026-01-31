/*
 * InternalAccumulator.java
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

import org.bluezoo.gonzalez.transform.compiler.Pattern;
import org.bluezoo.gonzalez.transform.xpath.XPathExpression;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNode;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNumber;
import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;
import org.xml.sax.SAXException;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Internal accumulator for implicit streaming of XSLT 1.0/2.0 patterns.
 *
 * <p>Internal accumulators are synthetic accumulators created by the
 * InternalAccumulatorFactory to enable streaming execution of XSLT 1.0/2.0
 * stylesheets that don't explicitly use XSLT 3.0 accumulator syntax.
 *
 * <p>Common patterns that map to internal accumulators:
 * <ul>
 *   <li><b>position()</b> - Counter incremented on each matching node</li>
 *   <li><b>count(preceding-sibling::*)</b> - Position counter for siblings</li>
 *   <li><b>sum(preceding-sibling::*)</b> - Running total</li>
 * </ul>
 *
 * <p>Internal accumulators are not visible to the stylesheet; they are
 * an implementation detail used to optimize streaming execution.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public class InternalAccumulator {

    /**
     * Type of internal accumulator.
     */
    public enum Type {
        /** Counts matching nodes (position tracking). */
        COUNTER,
        /** Sums numeric values from matching nodes. */
        SUM,
        /** Tracks last value from matching nodes. */
        LAST,
        /** Custom rule-based accumulator. */
        CUSTOM
    }

    private final String syntheticId;
    private final Type type;
    private final Pattern matchPattern;
    private final XPathExpression valueExpression;  // For SUM/CUSTOM types
    private final XPathExpression originalExpression;  // The XSLT 1.0 expression this replaces

    // Runtime state
    private final Deque<XPathValue> valueStack;
    private XPathValue currentValue;

    /**
     * Creates a counter accumulator.
     */
    public static InternalAccumulator createCounter(String id, Pattern matchPattern,
                                                     XPathExpression originalExpr) {
        return new InternalAccumulator(id, Type.COUNTER, matchPattern, null, originalExpr);
    }

    /**
     * Creates a sum accumulator.
     */
    public static InternalAccumulator createSum(String id, Pattern matchPattern,
                                                 XPathExpression valueExpr,
                                                 XPathExpression originalExpr) {
        return new InternalAccumulator(id, Type.SUM, matchPattern, valueExpr, originalExpr);
    }

    /**
     * Creates a custom accumulator.
     */
    public static InternalAccumulator createCustom(String id, Pattern matchPattern,
                                                    XPathExpression valueExpr,
                                                    XPathExpression originalExpr) {
        return new InternalAccumulator(id, Type.CUSTOM, matchPattern, valueExpr, originalExpr);
    }

    private InternalAccumulator(String syntheticId, Type type, Pattern matchPattern,
                                 XPathExpression valueExpression,
                                 XPathExpression originalExpression) {
        this.syntheticId = syntheticId;
        this.type = type;
        this.matchPattern = matchPattern;
        this.valueExpression = valueExpression;
        this.originalExpression = originalExpression;
        this.valueStack = new ArrayDeque<>();
        this.currentValue = XPathNumber.of(0);
    }

    /**
     * Returns the synthetic ID for this accumulator.
     *
     * @return the unique synthetic identifier
     */
    public String getSyntheticId() {
        return syntheticId;
    }

    /**
     * Returns the accumulator type.
     *
     * @return the type (COUNTER, SUM, LAST, or CUSTOM)
     */
    public Type getType() {
        return type;
    }

    /**
     * Returns the match pattern for this accumulator.
     *
     * @return the pattern that determines when to update the accumulator
     */
    public Pattern getMatchPattern() {
        return matchPattern;
    }

    /**
     * Returns the original XSLT 1.0 expression this accumulator replaces.
     *
     * @return the original expression, or null if not applicable
     */
    public XPathExpression getOriginalExpression() {
        return originalExpression;
    }

    /**
     * Returns the current accumulator value.
     *
     * @return the current value
     */
    public XPathValue getCurrentValue() {
        return currentValue;
    }

    /**
     * Initializes or resets the accumulator to its initial state.
     * Clears the value stack and sets current value to zero.
     */
    public void initialize() {
        valueStack.clear();
        currentValue = XPathNumber.of(0);
    }

    /**
     * Called when entering a node (startElement).
     * Pushes current value onto the stack and potentially updates based on match.
     *
     * @param node the node being entered
     * @param context the transformation context
     * @throws SAXException if update fails
     */
    public void notifyStartElement(XPathNode node, TransformContext context) throws SAXException {
        valueStack.push(currentValue);

        if (matchPattern != null && matchPattern.matches(node, context)) {
            updateValue(node, context);
        }
    }

    /**
     * Called when exiting a node (endElement).
     * Pops the value stack to restore the previous accumulator value.
     *
     * @param node the node being exited
     * @param context the transformation context
     */
    public void notifyEndElement(XPathNode node, TransformContext context) {
        if (!valueStack.isEmpty()) {
            valueStack.pop();
        }
    }

    /**
     * Updates the accumulator value based on type.
     */
    private void updateValue(XPathNode node, TransformContext context) throws SAXException {
        switch (type) {
            case COUNTER:
                // Increment counter
                double count = currentValue.asNumber();
                currentValue = XPathNumber.of(count + 1);
                break;

            case SUM:
                // Add value to running sum
                if (valueExpression != null) {
                    try {
                        TransformContext nodeCtx = context.withContextNode(node);
                        XPathValue addValue = valueExpression.evaluate(nodeCtx);
                        double sum = currentValue.asNumber() + addValue.asNumber();
                        currentValue = XPathNumber.of(sum);
                    } catch (Exception e) {
                        throw new SAXException("Error evaluating sum accumulator: " + e.getMessage(), e);
                    }
                }
                break;

            case LAST:
                // Store last value
                if (valueExpression != null) {
                    try {
                        TransformContext nodeCtx = context.withContextNode(node);
                        currentValue = valueExpression.evaluate(nodeCtx);
                    } catch (Exception e) {
                        throw new SAXException("Error evaluating last accumulator: " + e.getMessage(), e);
                    }
                }
                break;

            case CUSTOM:
                // Evaluate custom expression
                if (valueExpression != null) {
                    try {
                        TransformContext nodeCtx = context.withContextNode(node);
                        // Bind $value to current value
                        nodeCtx.getVariableScope().bind("value", currentValue);
                        currentValue = valueExpression.evaluate(nodeCtx);
                    } catch (Exception e) {
                        throw new SAXException("Error evaluating custom accumulator: " + e.getMessage(), e);
                    }
                }
                break;
        }
    }

    @Override
    public String toString() {
        return "InternalAccumulator[" + syntheticId + ", type=" + type + "]";
    }

}

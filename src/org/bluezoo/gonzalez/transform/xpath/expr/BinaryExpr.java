/*
 * BinaryExpr.java
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

package org.bluezoo.gonzalez.transform.xpath.expr;

import org.bluezoo.gonzalez.transform.xpath.XPathContext;
import org.bluezoo.gonzalez.transform.xpath.type.XPathBoolean;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNode;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNodeSet;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNumber;
import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;

import java.util.ArrayList;
import java.util.List;

/**
 * A binary expression (left op right).
 *
 * <p>Handles all XPath binary operators: arithmetic, comparison, logical, and union.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class BinaryExpr implements Expr {

    private final Operator operator;
    private final Expr left;
    private final Expr right;

    /**
     * Creates a binary expression.
     *
     * @param operator the operator
     * @param left the left operand
     * @param right the right operand
     */
    public BinaryExpr(Operator operator, Expr left, Expr right) {
        if (operator == null || left == null || right == null) {
            throw new NullPointerException("Operator and operands cannot be null");
        }
        this.operator = operator;
        this.left = left;
        this.right = right;
    }

    @Override
    public XPathValue evaluate(XPathContext context) throws XPathException {
        switch (operator) {
            // Logical operators (short-circuit evaluation)
            case OR:
                return evaluateOr(context);
            case AND:
                return evaluateAnd(context);

            // Comparison operators
            case EQUALS:
            case NOT_EQUALS:
            case LESS_THAN:
            case LESS_THAN_OR_EQUAL:
            case GREATER_THAN:
            case GREATER_THAN_OR_EQUAL:
                return evaluateComparison(context);

            // Arithmetic operators
            case PLUS:
            case MINUS:
            case MULTIPLY:
            case DIV:
            case MOD:
                return evaluateArithmetic(context);

            // Union operator
            case UNION:
                return evaluateUnion(context);

            default:
                throw new XPathException("Unknown operator: " + operator);
        }
    }

    private XPathValue evaluateOr(XPathContext context) throws XPathException {
        // Short-circuit: if left is true, don't evaluate right
        if (left.evaluate(context).asBoolean()) {
            return XPathBoolean.TRUE;
        }
        return XPathBoolean.of(right.evaluate(context).asBoolean());
    }

    private XPathValue evaluateAnd(XPathContext context) throws XPathException {
        // Short-circuit: if left is false, don't evaluate right
        if (!left.evaluate(context).asBoolean()) {
            return XPathBoolean.FALSE;
        }
        return XPathBoolean.of(right.evaluate(context).asBoolean());
    }

    private XPathValue evaluateComparison(XPathContext context) throws XPathException {
        XPathValue leftVal = left.evaluate(context);
        XPathValue rightVal = right.evaluate(context);

        // XPath 1.0 Section 3.4: Comparisons involving node-sets
        if (leftVal.isNodeSet() || rightVal.isNodeSet()) {
            return evaluateNodeSetComparison(leftVal, rightVal);
        }

        // Both operands are non-node-sets
        return evaluateValueComparison(leftVal, rightVal);
    }

    private XPathValue evaluateNodeSetComparison(XPathValue leftVal, XPathValue rightVal) {
        // XPath 1.0 comparison rules for node-sets:
        // A = B (both node-sets): true if any node in A has same string-value as any node in B
        // A = x (one node-set): true if any node in A has string-value equal to x (converted)

        if (leftVal.isNodeSet() && rightVal.isNodeSet()) {
            XPathNodeSet leftSet = leftVal.asNodeSet();
            XPathNodeSet rightSet = rightVal.asNodeSet();

            for (XPathNode leftNode : leftSet) {
                String leftStr = leftNode.getStringValue();
                for (XPathNode rightNode : rightSet) {
                    if (compareValues(leftStr, rightNode.getStringValue())) {
                        return XPathBoolean.TRUE;
                    }
                }
            }
            return XPathBoolean.FALSE;
        }

        // One is a node-set, one is not
        XPathNodeSet nodeSet;
        XPathValue other;
        boolean reversed;

        if (leftVal.isNodeSet()) {
            nodeSet = leftVal.asNodeSet();
            other = rightVal;
            reversed = false;
        } else {
            nodeSet = rightVal.asNodeSet();
            other = leftVal;
            reversed = true;
        }

        // Determine comparison type based on other operand
        for (XPathNode node : nodeSet) {
            String nodeStr = node.getStringValue();
            boolean match;

            if (other.getType() == XPathValue.Type.NUMBER) {
                // Compare as numbers
                double nodeNum = XPathNumber.of(Double.parseDouble(nodeStr.trim())).asNumber();
                match = compareNumbers(reversed ? other.asNumber() : nodeNum,
                                       reversed ? nodeNum : other.asNumber());
            } else if (other.getType() == XPathValue.Type.BOOLEAN) {
                // Compare as booleans
                match = compareValues(nodeSet.asBoolean(), other.asBoolean());
            } else {
                // Compare as strings
                match = compareValues(reversed ? other.asString() : nodeStr,
                                      reversed ? nodeStr : other.asString());
            }

            if (match) {
                return XPathBoolean.TRUE;
            }
        }

        return XPathBoolean.FALSE;
    }

    private XPathValue evaluateValueComparison(XPathValue leftVal, XPathValue rightVal) {
        // XPath 1.0 Section 3.4 comparison rules:
        // 1. If at least one is boolean, compare as booleans
        // 2. If at least one is number, compare as numbers
        // 3. Otherwise compare as strings

        if (leftVal.getType() == XPathValue.Type.BOOLEAN || 
            rightVal.getType() == XPathValue.Type.BOOLEAN) {
            return XPathBoolean.of(compareValues(leftVal.asBoolean(), rightVal.asBoolean()));
        }

        if (leftVal.getType() == XPathValue.Type.NUMBER || 
            rightVal.getType() == XPathValue.Type.NUMBER) {
            return XPathBoolean.of(compareNumbers(leftVal.asNumber(), rightVal.asNumber()));
        }

        return XPathBoolean.of(compareValues(leftVal.asString(), rightVal.asString()));
    }

    private boolean compareValues(boolean a, boolean b) {
        switch (operator) {
            case EQUALS: return a == b;
            case NOT_EQUALS: return a != b;
            default:
                // Relational operators on booleans: convert to numbers
                return compareNumbers(a ? 1.0 : 0.0, b ? 1.0 : 0.0);
        }
    }

    private boolean compareValues(String a, String b) {
        switch (operator) {
            case EQUALS: return a.equals(b);
            case NOT_EQUALS: return !a.equals(b);
            default:
                // Relational operators on strings: convert to numbers
                return compareNumbers(stringToNumber(a), stringToNumber(b));
        }
    }

    private boolean compareNumbers(double a, double b) {
        switch (operator) {
            case EQUALS: return a == b;
            case NOT_EQUALS: return a != b;
            case LESS_THAN: return a < b;
            case LESS_THAN_OR_EQUAL: return a <= b;
            case GREATER_THAN: return a > b;
            case GREATER_THAN_OR_EQUAL: return a >= b;
            default:
                return false;
        }
    }

    private double stringToNumber(String s) {
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    private XPathValue evaluateArithmetic(XPathContext context) throws XPathException {
        double leftNum = left.evaluate(context).asNumber();
        double rightNum = right.evaluate(context).asNumber();

        double result;
        switch (operator) {
            case PLUS:
                result = leftNum + rightNum;
                break;
            case MINUS:
                result = leftNum - rightNum;
                break;
            case MULTIPLY:
                result = leftNum * rightNum;
                break;
            case DIV:
                result = leftNum / rightNum;
                break;
            case MOD:
                result = leftNum % rightNum;
                break;
            default:
                throw new XPathException("Not an arithmetic operator: " + operator);
        }

        return XPathNumber.of(result);
    }

    private XPathValue evaluateUnion(XPathContext context) throws XPathException {
        XPathValue leftVal = left.evaluate(context);
        XPathValue rightVal = right.evaluate(context);

        // Handle null values as empty node-sets
        if (leftVal == null) {
            leftVal = XPathNodeSet.empty();
        }
        if (rightVal == null) {
            rightVal = XPathNodeSet.empty();
        }

        if (!leftVal.isNodeSet() || !rightVal.isNodeSet()) {
            throw new XPathException("Union operator requires node-set operands");
        }

        return leftVal.asNodeSet().union(rightVal.asNodeSet());
    }

    /**
     * Returns the operator.
     *
     * @return the operator
     */
    public Operator getOperator() {
        return operator;
    }

    /**
     * Returns the left operand.
     *
     * @return the left expression
     */
    public Expr getLeft() {
        return left;
    }

    /**
     * Returns the right operand.
     *
     * @return the right expression
     */
    public Expr getRight() {
        return right;
    }

    @Override
    public String toString() {
        return "(" + left + " " + operator + " " + right + ")";
    }

}

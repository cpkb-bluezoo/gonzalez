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
import org.bluezoo.gonzalez.transform.xpath.type.NodeType;
import org.bluezoo.gonzalez.transform.xpath.type.XPathBoolean;
import org.bluezoo.gonzalez.transform.xpath.type.XPathDateTime;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNode;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNodeSet;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNumber;
import org.bluezoo.gonzalez.transform.xpath.type.XPathSequence;
import org.bluezoo.gonzalez.transform.xpath.type.XPathQName;
import org.bluezoo.gonzalez.transform.xpath.type.XPathString;
import org.bluezoo.gonzalez.transform.xpath.type.XPathAtomicValue;
import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

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
                
            // XPath 2.0 value comparison operators (behave like general comparison for atomics)
            case VALUE_EQUALS:
            case VALUE_NOT_EQUALS:
            case VALUE_LESS_THAN:
            case VALUE_LESS_THAN_OR_EQUAL:
            case VALUE_GREATER_THAN:
            case VALUE_GREATER_THAN_OR_EQUAL:
                return evaluateValueComparison(context);

            // Arithmetic operators
            case PLUS:
            case MINUS:
            case MULTIPLY:
            case DIV:
            case IDIV:
            case MOD:
                return evaluateArithmetic(context);

            // Union operator
            case UNION:
                return evaluateUnion(context);
                
            // XPath 2.0 range operator
            case TO:
                return evaluateRange(context);
                
            // XPath 2.0 node comparison operators
            case NODE_IS:
            case NODE_PRECEDES:
            case NODE_FOLLOWS:
                return evaluateNodeComparison(context);
                
            // XPath 2.0 set operators
            case INTERSECT:
                return evaluateIntersect(context);
            case EXCEPT:
                return evaluateExcept(context);
                
            // XPath 3.0 string concatenation
            case STRING_CONCAT:
                return evaluateStringConcat(context);
                
            // XPath 3.0 simple map operator
            case SIMPLE_MAP:
                return evaluateSimpleMap(context);
                
            // XPath 3.0 arrow operator
            case ARROW:
                return evaluateArrow(context);

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

        // Handle null values - treat as empty string
        if (leftVal == null) {
            leftVal = XPathString.EMPTY;
        }
        if (rightVal == null) {
            rightVal = XPathString.EMPTY;
        }
        
        // Note: We don't do strict type checking for comparisons because XSLT 2.0
        // supports backwards-compatible mode (xsl:version="1.0") at the element level,
        // which is complex to track. Function argument type checking is still done.

        // XPath 2.0: General comparison uses existential semantics
        // If either operand is a sequence, iterate and check if any pair matches
        if (leftVal.isSequence() || rightVal.isSequence()) {
            return evaluateSequenceComparison(leftVal, rightVal);
        }

        // For XPath 2.0+, node-sets (including typed nodes) should use sequence comparison
        // to properly handle list type atomization (e.g., xs:NMTOKENS → sequence of NMTOKEN)
        if (leftVal.isNodeSet() || rightVal.isNodeSet()) {
            // Check if either value might have a list type annotation
            if (hasListTypeAnnotation(leftVal) || hasListTypeAnnotation(rightVal)) {
                return evaluateSequenceComparison(leftVal, rightVal);
            }
            // Standard XPath 1.0 node-set comparison for untyped nodes
            return evaluateNodeSetComparison(leftVal, rightVal);
        }

        // Both operands are non-node-sets
        return evaluateValueComparison(leftVal, rightVal);
    }

    /**
     * Evaluates XPath 2.0 general comparison with sequence semantics.
     * Returns true if any pair of items (one from each operand) satisfies the comparison.
     * For list types (xs:NMTOKENS, xs:IDREFS), nodes are atomized to sequences.
     */
    private XPathValue evaluateSequenceComparison(XPathValue leftVal, XPathValue rightVal) 
            throws XPathException {
        // Atomize both operands to get sequences of atomic values
        List<String> leftAtoms = atomizeToList(leftVal);
        List<String> rightAtoms = atomizeToList(rightVal);
        
        // Compare all pairs
        for (String leftStr : leftAtoms) {
            for (String rightStr : rightAtoms) {
                // Try numeric comparison if both are numeric
                Double leftNum = tryParseNumber(leftStr);
                Double rightNum = tryParseNumber(rightStr);
                
                boolean match;
                if (leftNum != null && rightNum != null) {
                    match = compareNumbers(leftNum, rightNum);
                } else {
                    match = compareValues(leftStr, rightStr);
                }
                
                if (match) {
                    return XPathBoolean.TRUE;
                }
            }
        }
        return XPathBoolean.FALSE;
    }

    /**
     * Atomizes a value to a list of strings.
     * For list types (xs:NMTOKENS, xs:IDREFS), returns multiple values.
     */
    private List<String> atomizeToList(XPathValue value) {
        List<String> result = new java.util.ArrayList<>();
        
        if (value instanceof XPathSequence) {
            XPathSequence seq = (XPathSequence) value;
            for (XPathValue item : seq) {
                atomizeItem(item, result);
            }
        } else {
            atomizeItem(value, result);
        }
        
        return result.isEmpty() ? java.util.Collections.singletonList("") : result;
    }
    
    /**
     * Atomizes a single item, adding its string value(s) to the result list.
     * For nodes with list type annotations, adds multiple values.
     */
    private void atomizeItem(XPathValue value, List<String> result) {
        if (value instanceof XPathNode) {
            XPathNode node = (XPathNode) value;
            String typeLocal = node.getTypeLocalName();
            String typeNs = node.getTypeNamespaceURI();
            
            // For list types, split on whitespace
            if (typeLocal != null && "http://www.w3.org/2001/XMLSchema".equals(typeNs)) {
                if ("NMTOKENS".equals(typeLocal) || "IDREFS".equals(typeLocal) || 
                    "ENTITIES".equals(typeLocal)) {
                    String stringValue = node.getStringValue();
                    if (stringValue != null && !stringValue.trim().isEmpty()) {
                        for (String token : stringValue.trim().split("\\s+")) {
                            result.add(token);
                        }
                        return;
                    }
                }
            }
            result.add(node.getStringValue());
        } else if (value.isNodeSet()) {
            XPathNodeSet ns = value.asNodeSet();
            for (XPathNode node : ns) {
                atomizeItem(new NodeValueWrapper(node), result);
            }
        } else {
            result.add(value.asString());
        }
    }
    
    /** Simple wrapper to treat XPathNode as XPathValue for atomization */
    private static class NodeValueWrapper implements XPathValue, XPathNode {
        private final XPathNode node;
        NodeValueWrapper(XPathNode node) { this.node = node; }
        @Override public Type getType() { return Type.NODESET; }
        @Override public String asString() { return node.getStringValue(); }
        @Override public double asNumber() { 
            try { return Double.parseDouble(node.getStringValue()); } 
            catch (NumberFormatException e) { return Double.NaN; } 
        }
        @Override public boolean asBoolean() { return !node.getStringValue().isEmpty(); }
        @Override public XPathNodeSet asNodeSet() { return new XPathNodeSet(java.util.Collections.singletonList(node)); }
        @Override public NodeType getNodeType() { return node.getNodeType(); }
        @Override public String getNamespaceURI() { return node.getNamespaceURI(); }
        @Override public String getLocalName() { return node.getLocalName(); }
        @Override public String getPrefix() { return node.getPrefix(); }
        @Override public String getStringValue() { return node.getStringValue(); }
        @Override public XPathNode getParent() { return node.getParent(); }
        @Override public Iterator<XPathNode> getChildren() { return node.getChildren(); }
        @Override public Iterator<XPathNode> getAttributes() { return node.getAttributes(); }
        @Override public Iterator<XPathNode> getNamespaces() { return node.getNamespaces(); }
        @Override public XPathNode getFollowingSibling() { return node.getFollowingSibling(); }
        @Override public XPathNode getPrecedingSibling() { return node.getPrecedingSibling(); }
        @Override public long getDocumentOrder() { return node.getDocumentOrder(); }
        @Override public boolean isSameNode(XPathNode other) { return node.isSameNode(other); }
        @Override public XPathNode getRoot() { return node.getRoot(); }
        @Override public boolean isFullyNavigable() { return node.isFullyNavigable(); }
        @Override public String getTypeNamespaceURI() { return node.getTypeNamespaceURI(); }
        @Override public String getTypeLocalName() { return node.getTypeLocalName(); }
    }

    /**
     * Checks if a value might have a list type annotation (xs:NMTOKENS, xs:IDREFS, xs:ENTITIES).
     * These types require special atomization that produces sequences.
     */
    private boolean hasListTypeAnnotation(XPathValue value) {
        if (value instanceof XPathNode) {
            XPathNode node = (XPathNode) value;
            String typeLocal = node.getTypeLocalName();
            String typeNs = node.getTypeNamespaceURI();
            if (typeLocal != null && "http://www.w3.org/2001/XMLSchema".equals(typeNs)) {
                return "NMTOKENS".equals(typeLocal) || "IDREFS".equals(typeLocal) || 
                       "ENTITIES".equals(typeLocal);
            }
        }
        if (value.isNodeSet()) {
            XPathNodeSet ns = value.asNodeSet();
            for (XPathNode node : ns) {
                String typeLocal = node.getTypeLocalName();
                String typeNs = node.getTypeNamespaceURI();
                if (typeLocal != null && "http://www.w3.org/2001/XMLSchema".equals(typeNs)) {
                    if ("NMTOKENS".equals(typeLocal) || "IDREFS".equals(typeLocal) || 
                        "ENTITIES".equals(typeLocal)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Atomizes a value - extracts string value from nodes.
     * @deprecated Use atomizeToList for proper list type handling
     */
    private String atomize(XPathValue value) {
        if (value.isNodeSet()) {
            XPathNodeSet ns = value.asNodeSet();
            if (ns.size() > 0) {
                return ns.iterator().next().getStringValue();
            }
            return "";
        }
        return value.asString();
    }

    /**
     * Tries to parse a string as a number, returns null if not a valid number.
     */
    private Double tryParseNumber(String s) {
        if (s == null || s.isEmpty()) {
            return null;
        }
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
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

    private XPathValue evaluateValueComparison(XPathValue leftVal, XPathValue rightVal) 
            throws XPathException {
        // XPath 2.0: If both are date/time values, use value-based comparison
        if (leftVal instanceof XPathDateTime && rightVal instanceof XPathDateTime) {
            XPathDateTime leftDt = (XPathDateTime) leftVal;
            XPathDateTime rightDt = (XPathDateTime) rightVal;
            try {
                int cmp = leftDt.compareTo(rightDt);
                return XPathBoolean.of(compareDateTimeResult(cmp));
            } catch (IllegalArgumentException e) {
                // Different date/time types - can't compare
                return XPathBoolean.FALSE;
            }
        }
        
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
    
    private boolean compareDateTimeResult(int cmp) {
        switch (operator) {
            case EQUALS:
            case VALUE_EQUALS:
                return cmp == 0;
            case NOT_EQUALS:
            case VALUE_NOT_EQUALS:
                return cmp != 0;
            case LESS_THAN:
            case VALUE_LESS_THAN:
                return cmp < 0;
            case LESS_THAN_OR_EQUAL:
            case VALUE_LESS_THAN_OR_EQUAL:
                return cmp <= 0;
            case GREATER_THAN:
            case VALUE_GREATER_THAN:
                return cmp > 0;
            case GREATER_THAN_OR_EQUAL:
            case VALUE_GREATER_THAN_OR_EQUAL:
                return cmp >= 0;
            default:
                return false;
        }
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
        XPathValue leftVal = left.evaluate(context);
        XPathValue rightVal = right.evaluate(context);
        
        // Handle null values
        if (leftVal == null) {
            leftVal = XPathNumber.of(Double.NaN);
        }
        if (rightVal == null) {
            rightVal = XPathNumber.of(Double.NaN);
        }
        
        // Note: XPTY0004 type checking for arithmetic is deferred because XSLT 2.0/3.0
        // allows element-level xsl:version="1.0" for backwards compatibility, which is
        // complex to track. The XPath 1.0 auto-coercion (string to number) is allowed.
        // Specific error tests that require XPTY0004 detection use version="2.0" and
        // are typically handled by expecting the error at a higher level.
        
        // Check for date/time arithmetic
        if (leftVal instanceof XPathDateTime || rightVal instanceof XPathDateTime) {
            return evaluateDateTimeArithmetic(leftVal, rightVal);
        }
        
        // Standard numeric arithmetic
        double leftNum = leftVal.asNumber();
        double rightNum = rightVal.asNumber();

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
            case IDIV:
                // XPath 2.0 integer division: rounds towards zero (truncates)
                if (rightNum == 0.0) {
                    throw new XPathException("Division by zero in idiv");
                }
                result = Math.floor(leftNum / rightNum);
                break;
            case MOD:
                result = leftNum % rightNum;
                break;
            default:
                throw new XPathException("Not an arithmetic operator: " + operator);
        }

        return XPathNumber.of(result);
    }
    
    private XPathValue evaluateDateTimeArithmetic(XPathValue leftVal, XPathValue rightVal) throws XPathException {
        XPathDateTime left = null;
        XPathDateTime right = null;
        double numVal = Double.NaN;
        boolean numOnRight = false;
        
        // Determine operand types
        if (leftVal instanceof XPathDateTime) {
            left = (XPathDateTime) leftVal;
        } else {
            numVal = leftVal.asNumber();
        }
        
        if (rightVal instanceof XPathDateTime) {
            right = (XPathDateTime) rightVal;
        } else {
            numVal = rightVal.asNumber();
            numOnRight = true;
        }
        
        switch (operator) {
            case PLUS:
                // date/time + duration or duration + duration
                if (left != null && right != null) {
                    return left.add(right);
                }
                throw new XPathException("Cannot add number to date/time");
                
            case MINUS:
                // date/time - duration, date/time - date/time, or duration - duration
                if (left != null && right != null) {
                    return left.subtract(right);
                }
                throw new XPathException("Cannot subtract number from date/time");
                
            case MULTIPLY:
                // duration * number or number * duration
                if (left != null && !Double.isNaN(numVal) && numOnRight) {
                    return left.multiply(numVal);
                } else if (right != null && !Double.isNaN(numVal) && !numOnRight) {
                    return right.multiply(numVal);
                }
                throw new XPathException("Duration multiplication requires a number operand");
                
            case DIV:
                // duration / number or duration / duration
                if (left != null && right != null) {
                    // duration / duration = decimal
                    return XPathNumber.of(left.divideByDuration(right).doubleValue());
                } else if (left != null && !Double.isNaN(numVal)) {
                    return left.divide(numVal);
                }
                throw new XPathException("Cannot divide " + (left != null ? "date/time" : "number") + 
                                        " by " + (right != null ? "date/time" : "number"));
                
            case MOD:
                throw new XPathException("Modulo operation not supported for date/time values");
                
            default:
                throw new XPathException("Unsupported arithmetic operator for date/time: " + operator);
        }
    }

    private XPathValue evaluateUnion(XPathContext context) throws XPathException {
        XPathValue leftVal = left.evaluate(context);
        XPathValue rightVal = right.evaluate(context);

        XPathNodeSet leftSet = toNodeSet(leftVal, "union");
        XPathNodeSet rightSet = toNodeSet(rightVal, "union");

        return leftSet.union(rightSet);
    }

    /**
     * Converts an XPathValue to an XPathNodeSet, extracting nodes from sequences.
     * XPath 2.0+ allows sequences of nodes as operands for set operations.
     */
    private static XPathNodeSet toNodeSet(XPathValue val, String opName) throws XPathException {
        if (val == null) {
            return XPathNodeSet.empty();
        }
        if (val.isNodeSet()) {
            return val.asNodeSet();
        }
        if (val.isSequence()) {
            List<XPathNode> nodes = new ArrayList<>();
            Iterator<XPathValue> it = val.sequenceIterator();
            while (it.hasNext()) {
                XPathValue item = it.next();
                if (item instanceof XPathNode) {
                    nodes.add((XPathNode) item);
                } else if (item.isNodeSet()) {
                    for (XPathNode n : item.asNodeSet()) {
                        nodes.add(n);
                    }
                } else {
                    throw new XPathException("XPTY0004: " + opName +
                        " operator requires node operands, got " + item.getType());
                }
            }
            return new XPathNodeSet(nodes);
        }
        throw new XPathException("XPTY0004: " + opName +
            " operator requires node-set operands, got " + val.getType());
    }

    /**
     * Evaluates an XPath 2.0 value comparison (eq, ne, lt, le, gt, ge).
     * Unlike general comparison, value comparison is type-aware:
     * - Two strings are compared lexicographically
     * - Two numbers are compared numerically
     * - Mixed types: strings stay strings, numbers stay numbers
     */
    private XPathValue evaluateValueComparison(XPathContext context) throws XPathException {
        XPathValue leftVal = left.evaluate(context);
        XPathValue rightVal = right.evaluate(context);
        
        // Handle null values
        if (leftVal == null || rightVal == null) {
            // Null comparisons: eq returns false, ne returns true for null vs non-null
            if (operator == Operator.VALUE_NOT_EQUALS) {
                return XPathBoolean.of(leftVal != rightVal);
            }
            return XPathBoolean.FALSE;
        }
        
        // XPath 2.0 value comparison is type-aware
        // Check the actual types of the operands
        XPathValue.Type leftType = leftVal.getType();
        XPathValue.Type rightType = rightVal.getType();
        
        // If both operands are numbers, use numeric comparison
        if (leftType == XPathValue.Type.NUMBER && rightType == XPathValue.Type.NUMBER) {
            return XPathBoolean.of(compareValuesNumeric(leftVal.asNumber(), rightVal.asNumber()));
        }
        
        // If both operands are strings, use string comparison
        if (leftType == XPathValue.Type.STRING && rightType == XPathValue.Type.STRING) {
            return XPathBoolean.of(compareValuesString(leftVal.asString(), rightVal.asString()));
        }
        
        // QName comparison: equality/inequality only (no ordering)
        if (leftVal instanceof XPathQName && rightVal instanceof XPathQName) {
            boolean equal = leftVal.equals(rightVal);
            if (operator == Operator.VALUE_EQUALS) {
                return XPathBoolean.of(equal);
            }
            if (operator == Operator.VALUE_NOT_EQUALS) {
                return XPathBoolean.of(!equal);
            }
            throw new XPathException("XPTY0004: QName values do not support ordering comparisons");
        }
        
        // Mixed types or other types: try numeric first, fall back to string
        // This provides XPath 1.0-like behavior for compatibility
        double leftNum = leftVal.asNumber();
        double rightNum = rightVal.asNumber();
        
        if (!Double.isNaN(leftNum) && !Double.isNaN(rightNum)) {
            return XPathBoolean.of(compareValuesNumeric(leftNum, rightNum));
        }
        
        // String comparison as fallback
        return XPathBoolean.of(compareValuesString(leftVal.asString(), rightVal.asString()));
    }
    
    private boolean compareValuesNumeric(double a, double b) {
        switch (operator) {
            case VALUE_EQUALS: return a == b;
            case VALUE_NOT_EQUALS: return a != b;
            case VALUE_LESS_THAN: return a < b;
            case VALUE_LESS_THAN_OR_EQUAL: return a <= b;
            case VALUE_GREATER_THAN: return a > b;
            case VALUE_GREATER_THAN_OR_EQUAL: return a >= b;
            default: return false;
        }
    }
    
    private boolean compareValuesString(String a, String b) {
        int cmp = a.compareTo(b);
        switch (operator) {
            case VALUE_EQUALS: return cmp == 0;
            case VALUE_NOT_EQUALS: return cmp != 0;
            case VALUE_LESS_THAN: return cmp < 0;
            case VALUE_LESS_THAN_OR_EQUAL: return cmp <= 0;
            case VALUE_GREATER_THAN: return cmp > 0;
            case VALUE_GREATER_THAN_OR_EQUAL: return cmp >= 0;
            default: return false;
        }
    }

    /**
     * Evaluates an XPath 2.0 range expression (e.g., 1 to 5).
     * Returns a sequence of integers from left to right (inclusive).
     */
    private XPathValue evaluateRange(XPathContext context) throws XPathException {
        int start = (int) left.evaluate(context).asNumber();
        int end = (int) right.evaluate(context).asNumber();
        
        // If start > end, return empty sequence
        if (start > end) {
            return XPathSequence.EMPTY;
        }
        
        // Return a sequence of integers
        List<XPathValue> items = new ArrayList<>();
        for (int i = start; i <= end; i++) {
            items.add(XPathNumber.of(i));
        }
        return XPathSequence.fromList(items);
    }

    /**
     * Evaluates XPath 2.0 node comparison operators (is, <<, >>).
     */
    private XPathValue evaluateNodeComparison(XPathContext context) throws XPathException {
        XPathValue leftVal = left.evaluate(context);
        XPathValue rightVal = right.evaluate(context);
        
        // Node comparisons require single nodes
        XPathNode leftNode = getSingleNode(leftVal);
        XPathNode rightNode = getSingleNode(rightVal);
        
        if (leftNode == null || rightNode == null) {
            // Empty sequence results in empty sequence (which is falsy)
            return XPathBoolean.FALSE;
        }
        
        switch (operator) {
            case NODE_IS:
                // Returns true if both operands are the same node
                return XPathBoolean.of(leftNode == rightNode || leftNode.equals(rightNode));
                
            case NODE_PRECEDES:
                // Returns true if left node precedes right in document order
                return XPathBoolean.of(leftNode.getDocumentOrder() < rightNode.getDocumentOrder());
                
            case NODE_FOLLOWS:
                // Returns true if left node follows right in document order
                return XPathBoolean.of(leftNode.getDocumentOrder() > rightNode.getDocumentOrder());
                
            default:
                throw new XPathException("Unknown node comparison operator: " + operator);
        }
    }
    
    /**
     * Extracts a single node from a value, or null if empty/multiple.
     */
    private XPathNode getSingleNode(XPathValue value) {
        if (value == null) {
            return null;
        }
        
        if (value.isNodeSet()) {
            XPathNodeSet ns = value.asNodeSet();
            if (ns.size() == 1) {
                return ns.iterator().next();
            }
            return null;
        }
        
        // Check if it's a single-item sequence containing a node
        if (value.sequenceSize() == 1) {
            Iterator<XPathValue> iter = value.sequenceIterator();
            if (iter.hasNext()) {
                XPathValue item = iter.next();
                if (item.isNodeSet()) {
                    XPathNodeSet ns = item.asNodeSet();
                    if (ns.size() == 1) {
                        return ns.iterator().next();
                    }
                }
            }
        }
        
        return null;
    }

    /**
     * Evaluates XPath 2.0 intersect operator.
     * Returns the intersection of two node sequences.
     */
    private XPathValue evaluateIntersect(XPathContext context) throws XPathException {
        XPathValue leftVal = left.evaluate(context);
        XPathValue rightVal = right.evaluate(context);
        
        XPathNodeSet leftSet = toNodeSet(leftVal, "intersect");
        XPathNodeSet rightSet = toNodeSet(rightVal, "intersect");
        
        // Build set of right nodes for efficient lookup
        Set<XPathNode> rightNodes = new HashSet<>();
        for (XPathNode node : rightSet) {
            rightNodes.add(node);
        }
        
        // Find nodes in both sets
        List<XPathNode> result = new ArrayList<>();
        for (XPathNode node : leftSet) {
            if (rightNodes.contains(node)) {
                result.add(node);
            }
        }
        
        return new XPathNodeSet(result);
    }

    /**
     * Evaluates XPath 2.0 except operator.
     * Returns nodes in left set that are not in right set.
     */
    private XPathValue evaluateExcept(XPathContext context) throws XPathException {
        XPathValue leftVal = left.evaluate(context);
        XPathValue rightVal = right.evaluate(context);
        
        XPathNodeSet leftSet = toNodeSet(leftVal, "except");
        XPathNodeSet rightSet = toNodeSet(rightVal, "except");
        
        // Build set of right nodes for efficient lookup
        Set<XPathNode> rightNodes = new HashSet<>();
        for (XPathNode node : rightSet) {
            rightNodes.add(node);
        }
        
        // Find nodes in left but not in right
        List<XPathNode> result = new ArrayList<>();
        for (XPathNode node : leftSet) {
            if (!rightNodes.contains(node)) {
                result.add(node);
            }
        }
        
        return new XPathNodeSet(result);
    }

    /**
     * Evaluates XPath 3.0 string concatenation operator (||).
     */
    private XPathValue evaluateStringConcat(XPathContext context) throws XPathException {
        XPathValue leftVal = left.evaluate(context);
        XPathValue rightVal = right.evaluate(context);
        
        String leftStr = leftVal != null ? leftVal.asString() : "";
        String rightStr = rightVal != null ? rightVal.asString() : "";
        
        return XPathString.of(leftStr + rightStr);
    }

    /**
     * Evaluates XPath 3.0 simple map operator (!).
     * For each item in the left operand, evaluates the right operand
     * with that item as context and concatenates the results.
     */
    private XPathValue evaluateSimpleMap(XPathContext context) throws XPathException {
        XPathValue leftVal = left.evaluate(context);
        List<XPathValue> results = new ArrayList<>();
        
        // Iterate over left operand items
        Iterator<XPathValue> iter = leftVal.sequenceIterator();
        int position = 0;
        int size = leftVal.sequenceSize();
        
        while (iter.hasNext()) {
            XPathValue item = iter.next();
            position++;
            
            // Create context with current item
            XPathContext itemContext;
            if (item.isNodeSet()) {
                XPathNodeSet ns = item.asNodeSet();
                if (ns.size() == 1) {
                    itemContext = context.withContextNode(ns.iterator().next())
                        .withPositionAndSize(position, size);
                } else {
                    itemContext = context.withPositionAndSize(position, size);
                }
            } else {
                itemContext = context.withPositionAndSize(position, size);
            }
            
            // Evaluate right expression
            XPathValue result = right.evaluate(itemContext);
            
            // Add to results (flatten sequences)
            if (result.isSequence()) {
                Iterator<XPathValue> resIter = result.sequenceIterator();
                while (resIter.hasNext()) {
                    results.add(resIter.next());
                }
            } else {
                results.add(result);
            }
        }
        
        return XPathSequence.fromList(results);
    }

    /**
     * Evaluates XPath 3.0 arrow operator (=>).
     * expr => func($arg1, $arg2) is equivalent to func(expr, $arg1, $arg2)
     * 
     * Note: This is a simplified implementation. Full arrow operator
     * requires the right operand to be a function call that gets the
     * left operand inserted as first argument.
     */
    private XPathValue evaluateArrow(XPathContext context) throws XPathException {
        XPathValue leftVal = left.evaluate(context);
        
        if (right instanceof FunctionCall) {
            FunctionCall func = (FunctionCall) right;
            // Create new argument list with left value prepended
            List<XPathValue> args = new ArrayList<>();
            args.add(leftVal);
            for (Expr argExpr : func.getArguments()) {
                args.add(argExpr.evaluate(context));
            }
            
            // Resolve prefix to namespace URI (same logic as FunctionCall.evaluate)
            String namespaceURI = func.getResolvedNamespaceURI();
            String prefix = func.getPrefix();
            if (namespaceURI == null && prefix != null && !prefix.isEmpty()) {
                namespaceURI = context.resolveNamespacePrefix(prefix);
                if (namespaceURI == null) {
                    throw new XPathException("Unknown namespace prefix: " + prefix);
                }
            }
            
            return context.getFunctionLibrary()
                .invokeFunction(namespaceURI, func.getLocalName(), args, context);
        }
        
        // Fallback: just return left value (shouldn't happen with proper parsing)
        return leftVal;
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

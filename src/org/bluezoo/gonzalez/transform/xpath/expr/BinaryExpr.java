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

import org.bluezoo.gonzalez.transform.xpath.Collation;
import org.bluezoo.gonzalez.transform.xpath.StaticTypeContext;
import org.bluezoo.gonzalez.transform.xpath.XPathContext;
import org.bluezoo.gonzalez.transform.xpath.type.SequenceType;
import org.bluezoo.gonzalez.transform.xpath.type.NodeType;
import org.bluezoo.gonzalez.transform.xpath.type.XPathBoolean;
import org.bluezoo.gonzalez.transform.xpath.type.XPathDateTime;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNode;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNodeSet;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNumber;
import org.bluezoo.gonzalez.transform.xpath.type.XPathSequence;
import org.bluezoo.gonzalez.transform.xpath.type.XPathQName;
import org.bluezoo.gonzalez.transform.xpath.type.XPathString;
import org.bluezoo.gonzalez.transform.xpath.type.XPathUntypedAtomic;
import org.bluezoo.gonzalez.transform.xpath.type.XPathAtomicValue;
import org.bluezoo.gonzalez.transform.xpath.type.XPathFunctionItem;
import org.bluezoo.gonzalez.transform.xpath.type.XPathMap;
import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;

import java.math.BigDecimal;
import java.math.BigInteger;
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

        if (leftVal instanceof XPathFunctionItem || leftVal instanceof XPathMap
                || leftVal instanceof InlineFunctionItem) {
            throw new XPathException("FOTY0013: Atomization is not defined for function items");
        }
        if (rightVal instanceof XPathFunctionItem || rightVal instanceof XPathMap
                || rightVal instanceof InlineFunctionItem) {
            throw new XPathException("FOTY0013: Atomization is not defined for function items");
        }

        // General comparison with empty sequence always returns false
        // (existential quantification over zero items yields false)
        if (leftVal == null || isEmptySequence(leftVal)) {
            return XPathBoolean.FALSE;
        }
        if (rightVal == null || isEmptySequence(rightVal)) {
            return XPathBoolean.FALSE;
        }
        
        // Note: We don't do strict type checking for comparisons because XSLT 2.0
        // supports backwards-compatible mode (xsl:version="1.0") at the element level,
        // which is complex to track. Function argument type checking is still done.

        // XPath 2.0: General comparison uses existential semantics
        // If either operand is a sequence, iterate and check if any pair matches
        if (leftVal.isSequence() || rightVal.isSequence()) {
            return evaluateSequenceComparison(leftVal, rightVal, context);
        }

        // For XPath 2.0+, node-sets (including typed nodes) should use sequence comparison
        // to properly handle list type atomization (e.g., xs:NMTOKENS → sequence of NMTOKEN)
        if (leftVal.isNodeSet() || rightVal.isNodeSet()) {
            // Check if either value might have a list type annotation
            if (hasListTypeAnnotation(leftVal) || hasListTypeAnnotation(rightVal)) {
                return evaluateSequenceComparison(leftVal, rightVal, context);
            }
            // Standard XPath 1.0 node-set comparison for untyped nodes
            return evaluateNodeSetComparison(leftVal, rightVal, context);
        }

        // Both operands are non-node-sets
        return evaluateValueComparison(leftVal, rightVal, context);
    }

    /**
     * Evaluates XPath 2.0 general comparison with sequence semantics.
     * Returns true if any pair of items (one from each operand) satisfies the comparison.
     * For list types (xs:NMTOKENS, xs:IDREFS), nodes are atomized to sequences.
     *
     * <p>In XPath 2.0+, when one operand is numeric and the other atomizes from
     * a node (xs:untypedAtomic), the untypedAtomic is cast to xs:double.
     * If the cast fails, FORG0001 is raised.
     */
    private XPathValue evaluateSequenceComparison(XPathValue leftVal, XPathValue rightVal,
                                                   XPathContext context)
            throws XPathException {
        boolean isXPath2 = context.getXsltVersion() >= 2.0;
        boolean leftHasNumeric = containsNumericValue(leftVal);
        boolean rightHasNumeric = containsNumericValue(rightVal);

        // XPath 2.0+: xs:string vs numeric is XPTY0004 (only xs:untypedAtomic
        // is promoted). Check before atomizing to strings so type info is preserved.
        if (isXPath2) {
            if (leftHasNumeric && containsTypedString(rightVal)) {
                throw new XPathException(
                    "XPTY0004: Cannot compare xs:string with numeric value");
            }
            if (rightHasNumeric && containsTypedString(leftVal)) {
                throw new XPathException(
                    "XPTY0004: Cannot compare xs:string with numeric value");
            }
        }

        List<String> leftAtoms = atomizeToList(leftVal);
        List<String> rightAtoms = atomizeToList(rightVal);
        
        for (String leftStr : leftAtoms) {
            for (String rightStr : rightAtoms) {
                Double leftNum = tryParseNumber(leftStr);
                Double rightNum = tryParseNumber(rightStr);
                
                if (isXPath2) {
                    if (rightHasNumeric && leftNum == null) {
                        throw new XPathException(
                            "FORG0001: Cannot cast '" + leftStr + "' to xs:double");
                    }
                    if (leftHasNumeric && rightNum == null) {
                        throw new XPathException(
                            "FORG0001: Cannot cast '" + rightStr + "' to xs:double");
                    }
                }

                boolean match;
                if (leftNum != null && rightNum != null) {
                    match = compareNumbers(leftNum, rightNum);
                } else {
                    match = compareValues(leftStr, rightStr, context);
                }
                
                if (match) {
                    return XPathBoolean.TRUE;
                }
            }
        }
        return XPathBoolean.FALSE;
    }

    /**
     * Checks if a value is or contains a numeric (XPathNumber) value.
     */
    private static boolean containsNumericValue(XPathValue val) {
        if (val instanceof XPathNumber) {
            return true;
        }
        if (val instanceof XPathSequence) {
            Iterator<XPathValue> it = val.sequenceIterator();
            while (it.hasNext()) {
                XPathValue item = it.next();
                if (item instanceof XPathNumber) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Checks if a value is or contains an explicitly typed xs:string
     * (as opposed to xs:untypedAtomic which can be promoted in comparisons).
     */
    private static boolean containsTypedString(XPathValue val) {
        if (val instanceof XPathString && !(val instanceof XPathUntypedAtomic)) {
            return true;
        }
        if (val instanceof XPathSequence) {
            Iterator<XPathValue> it = val.sequenceIterator();
            while (it.hasNext()) {
                XPathValue item = it.next();
                if (item instanceof XPathString && !(item instanceof XPathUntypedAtomic)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Checks if a single value is a typed xs:string (not xs:untypedAtomic).
     */
    private static boolean isTypedString(XPathValue val) {
        return val instanceof XPathString && !(val instanceof XPathUntypedAtomic);
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

    private XPathValue evaluateNodeSetComparison(XPathValue leftVal, XPathValue rightVal,
                                                    XPathContext context) throws XPathException {
        // XPath 1.0 comparison rules for node-sets:
        // A = B (both node-sets): true if any node in A has same string-value as any node in B
        // A = x (one node-set): true if any node in A has string-value equal to x (converted)

        if (leftVal.isNodeSet() && rightVal.isNodeSet()) {
            XPathNodeSet leftSet = leftVal.asNodeSet();
            XPathNodeSet rightSet = rightVal.asNodeSet();

            for (XPathNode leftNode : leftSet) {
                String leftStr = leftNode.getStringValue();
                for (XPathNode rightNode : rightSet) {
                    if (compareValues(leftStr, rightNode.getStringValue(), context)) {
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
                // XPath 2.0+: cast failure raises FORG0001
                // XPath 1.0: non-numeric strings become NaN
                Double parsed = tryParseNumber(nodeStr);
                if (parsed == null && context.getXsltVersion() >= 2.0) {
                    throw new XPathException(
                            "FORG0001: Cannot cast '" + nodeStr + "' to xs:double");
                }
                double nodeNum = parsed != null ? parsed : Double.NaN;
                match = compareNumbers(reversed ? other.asNumber() : nodeNum,
                                       reversed ? nodeNum : other.asNumber());
            } else if (other.getType() == XPathValue.Type.BOOLEAN) {
                // Compare as booleans
                match = compareValues(nodeSet.asBoolean(), other.asBoolean());
            } else {
                // Compare as strings
                match = compareValues(reversed ? other.asString() : nodeStr,
                                      reversed ? nodeStr : other.asString(), context);
            }

            if (match) {
                return XPathBoolean.TRUE;
            }
        }

        return XPathBoolean.FALSE;
    }

    private XPathValue evaluateValueComparison(XPathValue leftVal, XPathValue rightVal)
            throws XPathException {
        return evaluateValueComparison(leftVal, rightVal, null);
    }

    private XPathValue evaluateValueComparison(XPathValue leftVal, XPathValue rightVal,
                                                XPathContext context)
            throws XPathException {
        // XPath 2.0: If both are date/time values, use value-based comparison
        if (leftVal instanceof XPathDateTime && rightVal instanceof XPathDateTime) {
            XPathDateTime leftDt = (XPathDateTime) leftVal;
            XPathDateTime rightDt = (XPathDateTime) rightVal;
            if (isOrderingComparison()) {
                checkOrderableDateTime(leftDt);
            }
            try {
                int cmp = leftDt.compareTo(rightDt);
                return XPathBoolean.of(compareDateTimeResult(cmp));
            } catch (IllegalArgumentException e) {
                // Different date/time types - can't compare
                return XPathBoolean.FALSE;
            }
        }
        
        // QName comparison: uses namespace URI + local name equality, not string form
        if (leftVal instanceof XPathQName && rightVal instanceof XPathQName) {
            boolean equal = leftVal.equals(rightVal);
            if (operator == Operator.EQUALS || operator == Operator.VALUE_EQUALS) {
                return XPathBoolean.of(equal);
            }
            if (operator == Operator.NOT_EQUALS || operator == Operator.VALUE_NOT_EQUALS) {
                return XPathBoolean.of(!equal);
            }
            throw new XPathException("XPTY0004: QName values do not support ordering comparisons");
        }
        
        // XPath 2.0+: XPTY0004 when comparing numeric with typed xs:string
        boolean isXPath2 = context != null && context.getXsltVersion() >= 2.0;
        if (isXPath2) {
            boolean leftIsNum = leftVal.getType() == XPathValue.Type.NUMBER;
            boolean rightIsNum = rightVal.getType() == XPathValue.Type.NUMBER;
            boolean leftIsTypedStr = isTypedString(leftVal);
            boolean rightIsTypedStr = isTypedString(rightVal);
            if ((leftIsNum && rightIsTypedStr) || (rightIsNum && leftIsTypedStr)) {
                throw new XPathException(
                    "XPTY0004: Cannot compare xs:string with numeric value");
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
            if (leftVal instanceof XPathNumber && rightVal instanceof XPathNumber) {
                int exactCmp = compareExact((XPathNumber) leftVal, (XPathNumber) rightVal);
                if (exactCmp != Integer.MIN_VALUE) {
                    return XPathBoolean.of(compareIntResult(exactCmp));
                }
            }
            return XPathBoolean.of(compareNumbers(leftVal.asNumber(), rightVal.asNumber()));
        }

        return XPathBoolean.of(compareValues(leftVal.asString(), rightVal.asString(), context));
    }
    
    private boolean isOrderingComparison() {
        switch (operator) {
            case LESS_THAN:
            case LESS_THAN_OR_EQUAL:
            case GREATER_THAN:
            case GREATER_THAN_OR_EQUAL:
            case VALUE_LESS_THAN:
            case VALUE_LESS_THAN_OR_EQUAL:
            case VALUE_GREATER_THAN:
            case VALUE_GREATER_THAN_OR_EQUAL:
                return true;
            default:
                return false;
        }
    }
    
    private void checkOrderableDateTime(XPathDateTime dt) throws XPathException {
        XPathDateTime.DateTimeType dtType = dt.getDateTimeType();
        switch (dtType) {
            case G_YEAR:
            case G_YEAR_MONTH:
            case G_MONTH_DAY:
            case G_DAY:
            case G_MONTH:
                throw new XPathException("XPTY0004: Values of type xs:" + dtType +
                    " do not support ordering comparisons");
            default:
                break;
        }
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
        return compareValues(a, b, (XPathContext) null);
    }

    private boolean compareValues(String a, String b, XPathContext context) {
        if (context != null) {
            String collUri = context.getDefaultCollation();
            if (collUri != null) {
                try {
                    Collation collation = Collation.forUri(collUri);
                    int cmp = collation.compare(a, b);
                    switch (operator) {
                        case EQUALS: return cmp == 0;
                        case NOT_EQUALS: return cmp != 0;
                        case LESS_THAN: return cmp < 0;
                        case LESS_THAN_OR_EQUAL: return cmp <= 0;
                        case GREATER_THAN: return cmp > 0;
                        case GREATER_THAN_OR_EQUAL: return cmp >= 0;
                        default: break;
                    }
                } catch (XPathException e) {
                    // URI was validated at compile time; fall through to default
                }
            }
            // XPath 2.0+: untypedAtomic values compared as strings for all operators
            if (context.getXsltVersion() >= 2.0) {
                int cmp = a.compareTo(b);
                switch (operator) {
                    case EQUALS: return cmp == 0;
                    case NOT_EQUALS: return cmp != 0;
                    case LESS_THAN: return cmp < 0;
                    case LESS_THAN_OR_EQUAL: return cmp <= 0;
                    case GREATER_THAN: return cmp > 0;
                    case GREATER_THAN_OR_EQUAL: return cmp >= 0;
                    default: break;
                }
            }
        }
        switch (operator) {
            case EQUALS: return a.equals(b);
            case NOT_EQUALS: return !a.equals(b);
            default:
                // XPath 1.0: relational operators on strings convert to numbers
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

    /**
     * Compares two XPathNumber values using exact precision when both have exact
     * values (Long, BigInteger, or BigDecimal). Returns Integer.MIN_VALUE if
     * exact comparison is not applicable (e.g., double operands).
     */
    private static int compareExact(XPathNumber left, XPathNumber right) {
        if (left.isExactInteger() && right.isExactInteger()) {
            Long ll = left.toLong();
            Long rl = right.toLong();
            if (ll != null && rl != null) {
                return Long.compare(ll.longValue(), rl.longValue());
            }
            return left.toBigInteger().compareTo(right.toBigInteger());
        }
        if ((left.isExactInteger() || left.isDecimal()) &&
            (right.isExactInteger() || right.isDecimal())) {
            BigDecimal leftBd = toBigDecimal(left);
            BigDecimal rightBd = toBigDecimal(right);
            return leftBd.compareTo(rightBd);
        }
        return Integer.MIN_VALUE;
    }

    /**
     * Applies the current comparison operator to a compareTo result.
     * Works for both general (=, !=, <, etc.) and value (eq, ne, lt, etc.) operators.
     */
    private boolean compareIntResult(int cmp) {
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
        
        // In XPath 2.0+, arithmetic on an empty sequence returns empty
        if (context.getXsltVersion() >= 2.0) {
            if (isEmptySequence(leftVal) || isEmptySequence(rightVal)) {
                return XPathSequence.EMPTY;
            }
        }
        
        // Handle null values (XPath 1.0: empty node-set → NaN)
        if (leftVal == null) {
            leftVal = XPathNumber.of(Double.NaN);
        }
        if (rightVal == null) {
            rightVal = XPathNumber.of(Double.NaN);
        }
        
        // Check for date/time arithmetic
        if (leftVal instanceof XPathDateTime || rightVal instanceof XPathDateTime) {
            return evaluateDateTimeArithmetic(leftVal, rightVal);
        }
        
        // Use exact integer/decimal arithmetic when both operands are exact
        // In XPath 1.0 backward compat mode, div always returns xs:double
        boolean xpath1CompatDiv = context.getXsltVersion() < 2.0 && operator == Operator.DIV;
        if (!xpath1CompatDiv && leftVal instanceof XPathNumber && rightVal instanceof XPathNumber) {
            XPathNumber leftXn = (XPathNumber) leftVal;
            XPathNumber rightXn = (XPathNumber) rightVal;
            if (leftXn.isExactInteger() && rightXn.isExactInteger()) {
                return evaluateIntegerArithmetic(leftXn, rightXn);
            }
            if ((leftXn.isDecimal() || rightXn.isDecimal())
                    && !leftXn.isExplicitDouble() && !rightXn.isExplicitDouble()
                    && !leftXn.isFloat() && !rightXn.isFloat()) {
                BigDecimal leftDec = toBigDecimal(leftXn);
                BigDecimal rightDec = toBigDecimal(rightXn);
                return evaluateDecimalArithmetic(leftDec, rightDec);
            }
        }

        // In XPath 2.0+, integer/decimal division by zero raises FOAR0001
        // In XPath 1.0, all numbers are doubles and follow IEEE 754 (Infinity)
        if (context.getXsltVersion() >= 2.0) {
            if (operator == Operator.DIV || operator == Operator.IDIV ||
                    operator == Operator.MOD) {
                double rv = rightVal.asNumber();
                if (rv == 0.0 && isIntegerOrDecimalOperand(leftVal, rightVal)) {
                    throw new XPathException("FOAR0001: Division by zero");
                }
            }
        }

        // Standard double arithmetic
        double leftNum = leftVal.asNumber();
        double rightNum = rightVal.asNumber();

        // Determine if either operand is an explicit float/double type
        // If so, the result must also be treated as double to prevent
        // subsequent operations from misclassifying it as integer/decimal
        boolean eitherIsDouble = false;
        if (leftVal instanceof XPathNumber && rightVal instanceof XPathNumber) {
            XPathNumber ln = (XPathNumber) leftVal;
            XPathNumber rn = (XPathNumber) rightVal;
            eitherIsDouble = ln.isExplicitDouble() || rn.isExplicitDouble()
                || ln.isFloat() || rn.isFloat();
        }

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
                if (rightNum == 0.0) {
                    throw new XPathException("FOAR0001: Division by zero");
                }
                result = (double) ((long) (leftNum / rightNum));
                break;
            case MOD:
                result = leftNum % rightNum;
                break;
            default:
                throw new XPathException("Not an arithmetic operator: " + operator);
        }

        if (eitherIsDouble || context.getXsltVersion() >= 2.0) {
            return XPathNumber.ofExplicitDouble(result);
        }
        return XPathNumber.of(result);
    }
    
    /**
     * Performs exact integer arithmetic for xs:integer operands.
     * Uses Long fast-path with overflow promotion to BigInteger.
     */
    private XPathValue evaluateIntegerArithmetic(XPathNumber leftXn, XPathNumber rightXn)
            throws XPathException {
        Long leftLong = leftXn.toLong();
        Long rightLong = rightXn.toLong();

        if (leftLong != null && rightLong != null) {
            return evaluateLongArithmetic(leftLong.longValue(), rightLong.longValue());
        }

        BigInteger leftBi = leftXn.toBigInteger();
        BigInteger rightBi = rightXn.toBigInteger();
        return evaluateBigIntegerArithmetic(leftBi, rightBi);
    }

    /**
     * Performs arithmetic on two long values. Promotes to BigInteger on overflow.
     */
    private XPathValue evaluateLongArithmetic(long a, long b) throws XPathException {
        try {
            switch (operator) {
                case PLUS:
                    return XPathNumber.ofInteger(Math.addExact(a, b));
                case MINUS:
                    return XPathNumber.ofInteger(Math.subtractExact(a, b));
                case MULTIPLY:
                    return XPathNumber.ofInteger(Math.multiplyExact(a, b));
                case DIV:
                    if (b == 0) {
                        throw new XPathException("FOAR0001: Division by zero");
                    }
                    BigDecimal leftDec = BigDecimal.valueOf(a);
                    BigDecimal rightDec = BigDecimal.valueOf(b);
                    return evaluateDecimalArithmetic(leftDec, rightDec);
                case IDIV:
                    if (b == 0) {
                        throw new XPathException("FOAR0001: Division by zero");
                    }
                    return XPathNumber.ofInteger(a / b);
                case MOD:
                    if (b == 0) {
                        throw new XPathException("FOAR0001: Division by zero");
                    }
                    return XPathNumber.ofInteger(a % b);
                default:
                    throw new XPathException("Not an arithmetic operator: " + operator);
            }
        } catch (ArithmeticException e) {
            BigInteger leftBi = BigInteger.valueOf(a);
            BigInteger rightBi = BigInteger.valueOf(b);
            return evaluateBigIntegerArithmetic(leftBi, rightBi);
        }
    }

    /**
     * Performs arithmetic on two BigInteger values.
     */
    private XPathValue evaluateBigIntegerArithmetic(BigInteger a, BigInteger b)
            throws XPathException {
        switch (operator) {
            case PLUS:
                return XPathNumber.ofInteger(a.add(b));
            case MINUS:
                return XPathNumber.ofInteger(a.subtract(b));
            case MULTIPLY:
                return XPathNumber.ofInteger(a.multiply(b));
            case DIV:
                if (b.signum() == 0) {
                    throw new XPathException("FOAR0001: Division by zero");
                }
                BigDecimal leftDec = new BigDecimal(a);
                BigDecimal rightDec = new BigDecimal(b);
                return evaluateDecimalArithmetic(leftDec, rightDec);
            case IDIV:
                if (b.signum() == 0) {
                    throw new XPathException("FOAR0001: Division by zero");
                }
                return XPathNumber.ofInteger(a.divide(b));
            case MOD:
                if (b.signum() == 0) {
                    throw new XPathException("FOAR0001: Division by zero");
                }
                return XPathNumber.ofInteger(a.remainder(b));
            default:
                throw new XPathException("Not an arithmetic operator: " + operator);
        }
    }

    /**
     * Converts an XPathNumber to BigDecimal, preserving exact integer or decimal
     * values when available, falling back to double conversion.
     */
    private static BigDecimal toBigDecimal(XPathNumber xn) {
        if (xn.isDecimal()) {
            return xn.getDecimalValue();
        }
        if (xn.isExactInteger()) {
            BigInteger bi = xn.toBigInteger();
            return new BigDecimal(bi);
        }
        return BigDecimal.valueOf(xn.asNumber());
    }

    /**
     * Performs arithmetic using BigDecimal for xs:decimal precision.
     */
    private XPathValue evaluateDecimalArithmetic(BigDecimal left, BigDecimal right)
            throws XPathException {
        BigDecimal result;
        switch (operator) {
            case PLUS:
                result = left.add(right);
                break;
            case MINUS:
                result = left.subtract(right);
                break;
            case MULTIPLY:
                result = left.multiply(right);
                break;
            case DIV:
                if (right.signum() == 0) {
                    throw new XPathException("FOAR0002: Decimal division by zero");
                }
                result = left.divide(right, 20, java.math.RoundingMode.HALF_EVEN);
                result = result.stripTrailingZeros();
                break;
            case IDIV:
                if (right.signum() == 0) {
                    throw new XPathException("FOAR0001: Division by zero");
                }
                result = left.divideToIntegralValue(right);
                break;
            case MOD:
                if (right.signum() == 0) {
                    throw new XPathException("FOAR0002: Decimal division by zero");
                }
                result = left.remainder(right);
                break;
            default:
                throw new XPathException("Not an arithmetic operator: " + operator);
        }
        return new XPathNumber(result);
    }

    /**
     * Checks if both operands are integer or decimal (not float/double).
     * Per XPath 2.0+ spec, division by zero raises FOAR0002 for integer/decimal
     * but returns INF/-INF/NaN for float/double (IEEE 754 semantics).
     * In XPath 1.0, all division follows IEEE 754 (no FOAR0001 error).
     */
    private boolean isIntegerOrDecimalOperand(XPathValue leftVal, XPathValue rightVal) {
        if (leftVal instanceof XPathNumber && rightVal instanceof XPathNumber) {
            XPathNumber leftNum = (XPathNumber) leftVal;
            XPathNumber rightNum = (XPathNumber) rightVal;
            boolean leftIsIntOrDec = (leftNum.isInteger() || leftNum.isDecimal())
                && !leftNum.isFloat() && !leftNum.isExplicitDouble();
            boolean rightIsIntOrDec = (rightNum.isInteger() || rightNum.isDecimal())
                && !rightNum.isFloat() && !rightNum.isExplicitDouble();
            return leftIsIntOrDec && rightIsIntOrDec;
        }
        return false;
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
                // date/time + duration, duration + date/time, or duration + duration
                if (left != null && right != null) {
                    // XPath addition is commutative: duration + date = date + duration
                    if (left.isDuration() && !right.isDuration()) {
                        return right.add(left);
                    }
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

        if (leftVal instanceof XPathFunctionItem || leftVal instanceof XPathMap
                || leftVal instanceof InlineFunctionItem) {
            throw new XPathException("FOTY0013: Atomization is not defined for function items");
        }
        if (rightVal instanceof XPathFunctionItem || rightVal instanceof XPathMap
                || rightVal instanceof InlineFunctionItem) {
            throw new XPathException("FOTY0013: Atomization is not defined for function items");
        }
        
        // XPath 2.0: value comparison with empty operand returns empty sequence
        if (leftVal == null || rightVal == null) {
            return XPathSequence.EMPTY;
        }
        if (isEmptySequenceOrNodeSet(leftVal) || isEmptySequenceOrNodeSet(rightVal)) {
            return XPathSequence.EMPTY;
        }
        
        // XPTY0004: value comparison requires single atomic values, not sequences
        if (leftVal instanceof XPathSequence) {
            int leftSize = ((XPathSequence) leftVal).size();
            if (leftSize > 1) {
                throw new XPathException("XPTY0004: Value comparison requires a single " +
                    "atomic value, but left operand is a sequence of " + leftSize + " items");
            }
            leftVal = ((XPathSequence) leftVal).iterator().next();
        }
        if (rightVal instanceof XPathSequence) {
            int rightSize = ((XPathSequence) rightVal).size();
            if (rightSize > 1) {
                throw new XPathException("XPTY0004: Value comparison requires a single " +
                    "atomic value, but right operand is a sequence of " + rightSize + " items");
            }
            rightVal = ((XPathSequence) rightVal).iterator().next();
        }
        
        // Atomize operands: single nodes become their string value
        // (representing xs:untypedAtomic in a basic XSLT processor)
        if (leftVal instanceof XPathNode) {
            leftVal = XPathString.of(((XPathNode) leftVal).getStringValue());
        }
        if (rightVal instanceof XPathNode) {
            rightVal = XPathString.of(((XPathNode) rightVal).getStringValue());
        }
        if (leftVal.isNodeSet() && !(leftVal instanceof XPathNode)) {
            XPathNodeSet ns = leftVal.asNodeSet();
            if (ns.size() == 1) {
                leftVal = XPathString.of(ns.iterator().next().getStringValue());
            }
        }
        if (rightVal.isNodeSet() && !(rightVal instanceof XPathNode)) {
            XPathNodeSet ns = rightVal.asNodeSet();
            if (ns.size() == 1) {
                rightVal = XPathString.of(ns.iterator().next().getStringValue());
            }
        }
        
        // XPath 2.0 value comparison is type-aware
        // Check the actual types of the operands
        XPathValue.Type leftType = leftVal.getType();
        XPathValue.Type rightType = rightVal.getType();
        
        // If both operands are numbers, use numeric comparison
        if (leftType == XPathValue.Type.NUMBER && rightType == XPathValue.Type.NUMBER) {
            if (leftVal instanceof XPathNumber && rightVal instanceof XPathNumber) {
                int exactCmp = compareExact((XPathNumber) leftVal, (XPathNumber) rightVal);
                if (exactCmp != Integer.MIN_VALUE) {
                    return XPathBoolean.of(compareResult(exactCmp));
                }
            }
            return XPathBoolean.of(compareValuesNumeric(leftVal.asNumber(), rightVal.asNumber()));
        }
        
        // If both operands are strings, use string comparison with collation
        if (leftType == XPathValue.Type.STRING && rightType == XPathValue.Type.STRING) {
            String defaultUri = context.getDefaultCollation();
            if (defaultUri != null) {
                Collation collation = Collation.forUri(defaultUri);
                int cmp = collation.compare(leftVal.asString(), rightVal.asString());
                return XPathBoolean.of(compareResult(cmp));
            }
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
        
        // DateTime comparison
        if (leftVal instanceof XPathDateTime && rightVal instanceof XPathDateTime) {
            XPathDateTime leftDt = (XPathDateTime) leftVal;
            XPathDateTime rightDt = (XPathDateTime) rightVal;
            if (isOrderingComparison()) {
                checkOrderableDateTime(leftDt);
            }
            try {
                int cmp = leftDt.compareTo(rightDt);
                return XPathBoolean.of(compareResult(cmp));
            } catch (IllegalArgumentException e) {
                return XPathBoolean.FALSE;
            }
        }
        
        // XPath 2.0: xs:string vs numeric in a value comparison is a type
        // error (the operator mapping table B.2 has no matching entry).
        // This arises when untypedAtomic (from node atomization) meets
        // a typed numeric value -- the untypedAtomic is cast to xs:double
        // but the resulting xs:double vs xs:integer has no B.2 entry
        // without type promotion, which is a static analysis concept.
        if ((leftType == XPathValue.Type.STRING && rightType == XPathValue.Type.NUMBER)
                || (leftType == XPathValue.Type.NUMBER && rightType == XPathValue.Type.STRING)) {
            throw new XPathException("XPTY0004: Value comparison requires " +
                "operands of compatible types, but got " + leftType +
                " and " + rightType);
        }

        // Other mixed types: try numeric first, fall back to string.
        // Handles cases like xs:anyURI eq xs:string (anyURI promotes to
        // string), xs:boolean comparisons, and other ATOMIC types.
        double leftNum = leftVal.asNumber();
        double rightNum = rightVal.asNumber();

        if (!Double.isNaN(leftNum) && !Double.isNaN(rightNum)) {
            return XPathBoolean.of(compareValuesNumeric(leftNum, rightNum));
        }

        String defaultUri = context.getDefaultCollation();
        if (defaultUri != null) {
            Collation collation = Collation.forUri(defaultUri);
            int cmp = collation.compare(leftVal.asString(), rightVal.asString());
            return XPathBoolean.of(compareResult(cmp));
        }
        return XPathBoolean.of(compareValuesString(leftVal.asString(), rightVal.asString()));
    }

    private boolean compareResult(int cmp) {
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
        
        // Return a sequence of exact integers
        List<XPathValue> items = new ArrayList<>();
        for (int i = start; i <= end; i++) {
            items.add(XPathNumber.ofInteger(i));
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
            // XPath 2.0: node comparison with empty operand returns empty sequence
            return XPathSequence.EMPTY;
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
     * Returns true if the value is an empty sequence or an empty node-set.
     */
    private static boolean isEmptySequence(XPathValue value) {
        if (value == null) {
            return true;
        }
        if (value instanceof XPathSequence) {
            return ((XPathSequence) value).size() == 0;
        }
        if (value.isNodeSet()) {
            return value.asNodeSet().isEmpty();
        }
        return false;
    }

    private static boolean isEmptySequenceOrNodeSet(XPathValue value) {
        if (value instanceof XPathSequence) {
            return ((XPathSequence) value).size() == 0;
        }
        if (value.isNodeSet()) {
            return value.asNodeSet().isEmpty();
        }
        return false;
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
        
        XPathNodeSet.sortByDocumentOrder(result);
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
        
        XPathNodeSet.sortByDocumentOrder(result);
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
            } else if (item instanceof XPathNode) {
                itemContext = context.withContextNode((XPathNode) item)
                    .withPositionAndSize(position, size);
            } else {
                itemContext = context.withContextItem(item)
                    .withPositionAndSize(position, size);
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

    @Override
    public void bindStaticTypes(StaticTypeContext context) {
        left.bindStaticTypes(context);
        right.bindStaticTypes(context);
    }

    @Override
    public SequenceType getStaticType() {
        switch (operator) {
            case AND:
            case OR:
            case EQUALS:
            case NOT_EQUALS:
            case LESS_THAN:
            case LESS_THAN_OR_EQUAL:
            case GREATER_THAN:
            case GREATER_THAN_OR_EQUAL:
            case VALUE_EQUALS:
            case VALUE_NOT_EQUALS:
            case VALUE_LESS_THAN:
            case VALUE_LESS_THAN_OR_EQUAL:
            case VALUE_GREATER_THAN:
            case VALUE_GREATER_THAN_OR_EQUAL:
            case NODE_IS:
            case NODE_PRECEDES:
            case NODE_FOLLOWS:
                return SequenceType.BOOLEAN;

            case PLUS:
            case MINUS:
            case MULTIPLY:
            case DIV:
            case MOD:
                return inferArithmeticType();

            case IDIV:
                return SequenceType.INTEGER;

            case TO:
                return SequenceType.INTEGER_STAR;

            case UNION:
            case INTERSECT:
            case EXCEPT:
                return SequenceType.NODE_STAR;

            case STRING_CONCAT:
                return SequenceType.STRING;

            case SIMPLE_MAP:
            case ARROW:
                return right.getStaticType();

            default:
                return null;
        }
    }

    private SequenceType inferArithmeticType() {
        SequenceType lt = left.getStaticType();
        SequenceType rt = right.getStaticType();
        if (lt == null || rt == null) {
            return null;
        }
        boolean li = isIntegerType(lt);
        boolean ri = isIntegerType(rt);
        if (li && ri) {
            if (operator == Operator.DIV) {
                return SequenceType.DECIMAL;
            }
            return SequenceType.INTEGER;
        }
        boolean ld = isDecimalType(lt);
        boolean rd = isDecimalType(rt);
        if ((li || ld) && (ri || rd)) {
            return SequenceType.DECIMAL;
        }
        if (lt.isNumericType() && rt.isNumericType()) {
            return SequenceType.DOUBLE;
        }
        return null;
    }

    private static boolean isIntegerType(SequenceType t) {
        return t.getItemKind() == SequenceType.ItemKind.ATOMIC
            && "integer".equals(t.getLocalName());
    }

    private static boolean isDecimalType(SequenceType t) {
        return t.getItemKind() == SequenceType.ItemKind.ATOMIC
            && "decimal".equals(t.getLocalName());
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

/*
 * StaticTypeChecker.java
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

package org.bluezoo.gonzalez.transform.xpath;

import org.bluezoo.gonzalez.transform.xpath.expr.BinaryExpr;
import org.bluezoo.gonzalez.transform.xpath.expr.Expr;
import org.bluezoo.gonzalez.transform.xpath.expr.FunctionCall;
import org.bluezoo.gonzalez.transform.xpath.expr.Literal;
import org.bluezoo.gonzalez.transform.xpath.expr.Operator;
import org.bluezoo.gonzalez.transform.xpath.expr.TypeExpr;
import org.bluezoo.gonzalez.transform.xpath.expr.XPathException;
import org.bluezoo.gonzalez.transform.xpath.function.Function;
import org.bluezoo.gonzalez.transform.xpath.type.SequenceType;

import java.util.List;

/**
 * Performs static type checking on a compiled XPath expression tree.
 *
 * <p>The checker walks the expression AST after type binding and validates
 * type compatibility at key points: value comparisons, arithmetic operands,
 * function argument types, and special rules such as the xs:QName literal
 * argument restriction.
 *
 * <p>The level of strictness is controlled by
 * {@link StaticTypeContext#isStrictTypeChecking()}: when strict (pessimistic),
 * expressions that <em>might</em> fail are rejected; when relaxed
 * (optimistic), only provably incompatible types are rejected.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class StaticTypeChecker {

    private StaticTypeChecker() {
    }

    /**
     * Checks an expression tree for static type errors.
     *
     * @param expr the root expression
     * @param ctx the static type context
     * @throws XPathSyntaxException if a type error is detected
     */
    public static void check(Expr expr, StaticTypeContext ctx) throws XPathSyntaxException {
        try {
            walk(expr, ctx);
        } catch (XPathException e) {
            throw new XPathSyntaxException(e.getMessage());
        }
    }

    private static void walk(Expr expr, StaticTypeContext ctx) throws XPathException {
        if (expr == null) {
            return;
        }

        if (expr instanceof BinaryExpr) {
            checkBinaryExpr((BinaryExpr) expr, ctx);
        } else if (expr instanceof FunctionCall) {
            checkFunctionCall((FunctionCall) expr, ctx);
        } else if (expr instanceof TypeExpr) {
            checkTypeExpr((TypeExpr) expr, ctx);
        }
    }

    /**
     * Checks binary expression type rules.
     */
    private static void checkBinaryExpr(BinaryExpr expr, StaticTypeContext ctx)
            throws XPathException {
        Operator op = expr.getOperator();
        walk(expr.getLeft(), ctx);
        walk(expr.getRight(), ctx);

        if (ctx.getXsltVersion() < 2.0) {
            return;
        }

        if (isValueComparison(op) && ctx.isStrictTypeChecking()) {
            checkValueComparisonOperands(expr, ctx);
        }
        if (op.isArithmetic()) {
            checkArithmeticOperands(expr, ctx);
        }
        if (op.isComparison()) {
            checkGeneralComparisonOperands(expr, ctx);
        }
    }

    /**
     * For value comparison operators (eq, ne, lt, le, gt, ge): in pessimistic
     * mode, both operands must have static types compatible with a single
     * atomic value.
     */
    private static void checkValueComparisonOperands(BinaryExpr expr, StaticTypeContext ctx)
            throws XPathException {
        SequenceType leftType = expr.getLeft().getStaticType();
        SequenceType rightType = expr.getRight().getStaticType();
        if (leftType == null || rightType == null) {
            return;
        }

        if (!isAtomicCompatible(leftType) && !isNodeType(leftType)) {
            return;
        }
        if (!isAtomicCompatible(rightType) && !isNodeType(rightType)) {
            return;
        }

        if (isNodeType(leftType) && isNodeType(rightType)) {
            return;
        }

        boolean leftAtomic = isAtomicCompatible(leftType);
        boolean rightAtomic = isAtomicCompatible(rightType);
        if (leftAtomic && rightAtomic) {
            boolean leftNumeric = leftType.isNumericType();
            boolean rightNumeric = rightType.isNumericType();
            boolean leftString = isStringType(leftType);
            boolean rightString = isStringType(rightType);
            if (leftNumeric && rightString) {
                throw new XPathException("XPTY0004: Value comparison type mismatch: " +
                    "left operand is numeric, right is string");
            }
            if (leftString && rightNumeric) {
                throw new XPathException("XPTY0004: Value comparison type mismatch: " +
                    "left operand is string, right is numeric");
            }
        }
    }

    /**
     * XPath 2.0 arithmetic operators require both operands to be numeric
     * (or untypedAtomic, which gets cast to xs:double). A string literal
     * or boolean used as an arithmetic operand is XPTY0004.
     */
    private static void checkArithmeticOperands(BinaryExpr expr, StaticTypeContext ctx)
            throws XPathException {
        SequenceType leftType = expr.getLeft().getStaticType();
        SequenceType rightType = expr.getRight().getStaticType();
        if (leftType == null || rightType == null) {
            return;
        }
        boolean leftNonNumeric = isStrictStringType(leftType) || leftType.isBooleanType();
        boolean rightNonNumeric = isStrictStringType(rightType) || rightType.isBooleanType();

        if (leftType.isNumericType() && rightNonNumeric) {
            throw new XPathException("XPTY0004: Arithmetic operator requires " +
                "numeric operands, right operand is " + rightType);
        }
        if (leftNonNumeric && rightType.isNumericType()) {
            throw new XPathException("XPTY0004: Arithmetic operator requires " +
                "numeric operands, left operand is " + leftType);
        }
        if (leftNonNumeric && rightNonNumeric) {
            throw new XPathException("XPTY0004: Arithmetic operator requires " +
                "numeric operands, both operands are non-numeric");
        }
    }

    /**
     * XPath 2.0 general comparison operators (=, !=, &lt;, etc.) require
     * operands whose atomized values have comparable types. Comparing
     * incompatible type families is XPTY0004.
     *
     * <p>The comparable type families are: numeric, string/anyURI,
     * boolean, date/time, and duration. Cross-family comparison is
     * forbidden (e.g. boolean vs. numeric, boolean vs. string,
     * string vs. numeric).
     */
    private static void checkGeneralComparisonOperands(BinaryExpr expr, StaticTypeContext ctx)
            throws XPathException {
        SequenceType leftType = expr.getLeft().getStaticType();
        SequenceType rightType = expr.getRight().getStaticType();
        if (leftType == null || rightType == null) {
            return;
        }
        boolean leftNumeric = leftType.isNumericType();
        boolean rightNumeric = rightType.isNumericType();
        boolean leftString = isStrictStringType(leftType);
        boolean rightString = isStrictStringType(rightType);
        boolean leftBoolean = leftType.isBooleanType();
        boolean rightBoolean = rightType.isBooleanType();

        if (leftNumeric && rightString) {
            throw new XPathException("XPTY0004: Cannot compare numeric value " +
                "with xs:string using general comparison");
        }
        if (leftString && rightNumeric) {
            throw new XPathException("XPTY0004: Cannot compare xs:string " +
                "with numeric value using general comparison");
        }
        if (leftBoolean && rightNumeric) {
            throw new XPathException("XPTY0004: Cannot compare xs:boolean " +
                "with numeric value using general comparison");
        }
        if (leftNumeric && rightBoolean) {
            throw new XPathException("XPTY0004: Cannot compare numeric value " +
                "with xs:boolean using general comparison");
        }
        if (leftBoolean && rightString) {
            throw new XPathException("XPTY0004: Cannot compare xs:boolean " +
                "with xs:string using general comparison");
        }
        if (leftString && rightBoolean) {
            throw new XPathException("XPTY0004: Cannot compare xs:string " +
                "with xs:boolean using general comparison");
        }
    }

    /**
     * Checks function call for special static rules.
     */
    private static void checkFunctionCall(FunctionCall expr, StaticTypeContext ctx)
            throws XPathException {
        List<Expr> args = expr.getArguments();
        for (Expr arg : args) {
            walk(arg, ctx);
        }

        if (ctx.getProcessorVersion() < 3.0) {
            checkQNameLiteralRule(expr, ctx);
        }
    }

    /**
     * XPath 2.0 rule: xs:QName() constructor argument must be a string literal.
     */
    private static void checkQNameLiteralRule(FunctionCall expr, StaticTypeContext ctx)
            throws XPathException {
        String nsUri = expr.getResolvedNamespaceURI();
        String localName = expr.getLocalName();
        if (!"QName".equals(localName)) {
            return;
        }
        if (nsUri == null || !SequenceType.XS_NAMESPACE.equals(nsUri)) {
            return;
        }
        List<Expr> args = expr.getArguments();
        if (args.size() != 1) {
            return;
        }
        Expr arg = args.get(0);
        if (!(arg instanceof Literal)) {
            throw new XPathException("XPTY0004: xs:QName() constructor requires " +
                "a string literal argument in XPath 2.0");
        }
    }

    /**
     * Checks function argument types against declared parameter types.
     */
    private static void checkFunctionArgumentTypes(FunctionCall expr, StaticTypeContext ctx)
            throws XPathException {
        String nsUri = expr.getResolvedNamespaceURI();
        String localName = expr.getLocalName();
        int arity = expr.getArguments().size();

        Function func = ctx.resolveFunction(nsUri, localName, arity);
        if (func == null) {
            return;
        }
        SequenceType[] paramTypes = func.getParameterSequenceTypes();
        if (paramTypes == null) {
            return;
        }

        List<Expr> args = expr.getArguments();
        for (int i = 0; i < args.size(); i++) {
            SequenceType argType = args.get(i).getStaticType();
            if (argType == null) {
                continue;
            }
            int paramIdx = Math.min(i, paramTypes.length - 1);
            if (paramIdx < 0) {
                continue;
            }
            SequenceType expectedType = paramTypes[paramIdx];
            if (expectedType == null) {
                continue;
            }
            if (ctx.isStrictTypeChecking()) {
                checkTypeCompatible(argType, expectedType, func.getName(), i + 1);
            }
        }
    }

    /**
     * Checks whether an actual type is compatible with an expected type.
     * Raises XPTY0004 only for provably incompatible types.
     *
     * <p>Empty sequences are not rejected because many XPath functions
     * handle them through implicit conversion even when the formal
     * signature declares occurrence=ONE.
     */
    private static void checkTypeCompatible(SequenceType actual, SequenceType expected,
                                            String funcName, int argPos)
            throws XPathException {
        if (actual.getItemKind() == SequenceType.ItemKind.EMPTY) {
            return;
        }
        if (expected.isNumericType() && !actual.isNumericType()
                && isStringType(actual)) {
            throw new XPathException("XPTY0004: Argument " + argPos + " of " +
                funcName + "() requires numeric type, but got " + actual);
        }
    }

    private static void checkTypeExpr(TypeExpr expr, StaticTypeContext ctx)
            throws XPathException {
        walk(expr.getOperand(), ctx);
    }

    private static boolean isValueComparison(Operator op) {
        return op == Operator.VALUE_EQUALS
            || op == Operator.VALUE_NOT_EQUALS
            || op == Operator.VALUE_LESS_THAN
            || op == Operator.VALUE_LESS_THAN_OR_EQUAL
            || op == Operator.VALUE_GREATER_THAN
            || op == Operator.VALUE_GREATER_THAN_OR_EQUAL;
    }

    private static boolean isAtomicCompatible(SequenceType type) {
        SequenceType.ItemKind kind = type.getItemKind();
        return kind == SequenceType.ItemKind.ATOMIC;
    }

    private static boolean isNodeType(SequenceType type) {
        SequenceType.ItemKind kind = type.getItemKind();
        return kind == SequenceType.ItemKind.NODE
            || kind == SequenceType.ItemKind.ELEMENT
            || kind == SequenceType.ItemKind.ATTRIBUTE
            || kind == SequenceType.ItemKind.TEXT
            || kind == SequenceType.ItemKind.DOCUMENT_NODE
            || kind == SequenceType.ItemKind.COMMENT
            || kind == SequenceType.ItemKind.PROCESSING_INSTRUCTION
            || kind == SequenceType.ItemKind.NAMESPACE_NODE;
    }

    private static boolean isStringType(SequenceType type) {
        if (type.getItemKind() != SequenceType.ItemKind.ATOMIC) {
            return false;
        }
        String local = type.getLocalName();
        return "string".equals(local) || "untypedAtomic".equals(local);
    }

    /**
     * Returns true if the type is strictly xs:string (not untypedAtomic).
     * untypedAtomic is excluded because it gets implicitly cast in
     * arithmetic and comparison contexts.
     */
    private static boolean isStrictStringType(SequenceType type) {
        if (type.getItemKind() != SequenceType.ItemKind.ATOMIC) {
            return false;
        }
        return "string".equals(type.getLocalName());
    }
}

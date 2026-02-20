/*
 * XPathParserTest.java
 * Copyright (C) 2025 Chris Burdess
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
import org.bluezoo.gonzalez.transform.xpath.expr.LocationPath;
import org.bluezoo.gonzalez.transform.xpath.expr.Operator;
import org.bluezoo.gonzalez.transform.xpath.expr.Step;
import org.bluezoo.gonzalez.transform.xpath.expr.VariableReference;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

/**
 * Unit tests for the {@link XPathParser}.
 *
 * @author Chris Burdess
 */
public class XPathParserTest {

    // --- Literal expressions ---

    @Test
    public void testLiteralNumber() throws XPathSyntaxException {
        XPathParser parser = new XPathParser("42");
        Expr result = parser.parse();
        assertTrue(result instanceof Literal);
        Literal lit = (Literal) result;
        String strVal = lit.getValue().asString();
        assertEquals("42", strVal);
    }

    @Test
    public void testLiteralString() throws XPathSyntaxException {
        XPathParser parser = new XPathParser("'hello'");
        Expr result = parser.parse();
        assertTrue(result instanceof Literal);
        Literal lit = (Literal) result;
        String strVal = lit.getValue().asString();
        assertEquals("hello", strVal);
    }

    // --- Simple paths ---

    @Test
    public void testPathChildElement() throws XPathSyntaxException {
        XPathParser parser = new XPathParser("child::element");
        Expr result = parser.parse();
        assertTrue(result instanceof LocationPath);
        LocationPath path = (LocationPath) result;
        assertFalse(path.isAbsolute());
        List<Step> steps = path.getSteps();
        assertEquals(1, steps.size());
        Step step = steps.get(0);
        assertEquals(Step.Axis.CHILD, step.getAxis());
        assertEquals("element", step.getLocalName());
    }

    @Test
    public void testPathAbsoluteRoot() throws XPathSyntaxException {
        XPathParser parser = new XPathParser("/root");
        Expr result = parser.parse();
        assertTrue(result instanceof LocationPath);
        LocationPath path = (LocationPath) result;
        assertTrue(path.isAbsolute());
        List<Step> steps = path.getSteps();
        assertEquals(1, steps.size());
        Step step = steps.get(0);
        assertEquals("root", step.getLocalName());
    }

    @Test
    public void testPathDoubleSlashTitle() throws XPathSyntaxException {
        XPathParser parser = new XPathParser("//title");
        Expr result = parser.parse();
        assertTrue(result instanceof LocationPath);
        LocationPath path = (LocationPath) result;
        assertTrue(path.isAbsolute());
        List<Step> steps = path.getSteps();
        assertTrue(steps.size() >= 2);
        Step firstStep = steps.get(0);
        assertEquals(Step.Axis.DESCENDANT_OR_SELF, firstStep.getAxis());
        Step secondStep = steps.get(1);
        assertEquals("title", secondStep.getLocalName());
    }

    // --- Predicates ---

    @Test
    public void testPredicatePositional() throws XPathSyntaxException {
        XPathParser parser = new XPathParser("book[1]");
        Expr result = parser.parse();
        assertTrue(result instanceof LocationPath);
        LocationPath path = (LocationPath) result;
        List<Step> steps = path.getSteps();
        assertEquals(1, steps.size());
        Step step = steps.get(0);
        assertEquals("book", step.getLocalName());
        List<Expr> predicates = step.getPredicates();
        assertEquals(1, predicates.size());
        Expr pred = predicates.get(0);
        assertTrue(pred instanceof Literal);
    }

    @Test
    public void testPredicateAttribute() throws XPathSyntaxException {
        XPathParser parser = new XPathParser("item[@id='test']");
        Expr result = parser.parse();
        assertTrue(result instanceof LocationPath);
        LocationPath path = (LocationPath) result;
        List<Step> steps = path.getSteps();
        assertEquals(1, steps.size());
        Step step = steps.get(0);
        assertEquals("item", step.getLocalName());
        List<Expr> predicates = step.getPredicates();
        assertEquals(1, predicates.size());
        Expr pred = predicates.get(0);
        assertTrue(pred instanceof BinaryExpr);
        BinaryExpr bin = (BinaryExpr) pred;
        assertEquals(Operator.EQUALS, bin.getOperator());
    }

    // --- Binary operators ---

    @Test
    public void testBinaryPlus() throws XPathSyntaxException {
        XPathParser parser = new XPathParser("1 + 2");
        Expr result = parser.parse();
        assertTrue(result instanceof BinaryExpr);
        BinaryExpr bin = (BinaryExpr) result;
        assertEquals(Operator.PLUS, bin.getOperator());
        assertTrue(bin.getLeft() instanceof Literal);
        assertTrue(bin.getRight() instanceof Literal);
    }

    @Test
    public void testBinaryEquals() throws XPathSyntaxException {
        XPathParser parser = new XPathParser("$x = 5");
        Expr result = parser.parse();
        assertTrue(result instanceof BinaryExpr);
        BinaryExpr bin = (BinaryExpr) result;
        assertEquals(Operator.EQUALS, bin.getOperator());
        assertTrue(bin.getLeft() instanceof VariableReference);
        assertTrue(bin.getRight() instanceof Literal);
    }

    @Test
    public void testBinaryAnd() throws XPathSyntaxException {
        XPathParser parser = new XPathParser("$a and $b");
        Expr result = parser.parse();
        assertTrue(result instanceof BinaryExpr);
        BinaryExpr bin = (BinaryExpr) result;
        assertEquals(Operator.AND, bin.getOperator());
        assertTrue(bin.getLeft() instanceof VariableReference);
        assertTrue(bin.getRight() instanceof VariableReference);
    }

    // --- Function calls ---

    @Test
    public void testFunctionCallConcat() throws XPathSyntaxException {
        XPathParser parser = new XPathParser("concat('a', 'b')");
        Expr result = parser.parse();
        assertTrue(result instanceof FunctionCall);
        FunctionCall func = (FunctionCall) result;
        assertEquals("concat", func.getLocalName());
        List<Expr> args = func.getArguments();
        assertEquals(2, args.size());
        assertTrue(args.get(0) instanceof Literal);
        assertTrue(args.get(1) instanceof Literal);
    }

    @Test
    public void testFunctionCallCount() throws XPathSyntaxException {
        XPathParser parser = new XPathParser("count(//item)");
        Expr result = parser.parse();
        assertTrue(result instanceof FunctionCall);
        FunctionCall func = (FunctionCall) result;
        assertEquals("count", func.getLocalName());
        List<Expr> args = func.getArguments();
        assertEquals(1, args.size());
        assertTrue(args.get(0) instanceof LocationPath);
    }

    // --- Union expressions ---

    @Test
    public void testUnionExpression() throws XPathSyntaxException {
        XPathParser parser = new XPathParser("a | b");
        Expr result = parser.parse();
        assertTrue(result instanceof BinaryExpr);
        BinaryExpr bin = (BinaryExpr) result;
        assertEquals(Operator.UNION, bin.getOperator());
        assertTrue(bin.getLeft() instanceof LocationPath);
        assertTrue(bin.getRight() instanceof LocationPath);
    }

    // --- Abbreviated paths ---

    @Test
    public void testAbbreviationDot() throws XPathSyntaxException {
        XPathParser parser = new XPathParser(".");
        Expr result = parser.parse();
        assertTrue(result instanceof LocationPath);
        LocationPath path = (LocationPath) result;
        List<Step> steps = path.getSteps();
        assertEquals(1, steps.size());
        Step step = steps.get(0);
        assertEquals(Step.Axis.SELF, step.getAxis());
    }

    @Test
    public void testAbbreviationDoubleDot() throws XPathSyntaxException {
        XPathParser parser = new XPathParser("..");
        Expr result = parser.parse();
        assertTrue(result instanceof LocationPath);
        LocationPath path = (LocationPath) result;
        List<Step> steps = path.getSteps();
        assertEquals(1, steps.size());
        Step step = steps.get(0);
        assertEquals(Step.Axis.PARENT, step.getAxis());
    }

    @Test
    public void testAbbreviationAt() throws XPathSyntaxException {
        XPathParser parser = new XPathParser("@attr");
        Expr result = parser.parse();
        assertTrue(result instanceof LocationPath);
        LocationPath path = (LocationPath) result;
        List<Step> steps = path.getSteps();
        assertEquals(1, steps.size());
        Step step = steps.get(0);
        assertEquals(Step.Axis.ATTRIBUTE, step.getAxis());
        assertEquals("attr", step.getLocalName());
    }

    // --- Complex expressions ---

    @Test
    public void testComplexPredicate() throws XPathSyntaxException {
        XPathParser parser = new XPathParser("//book[author='Smith' and @year > 2000]");
        Expr result = parser.parse();
        assertTrue(result instanceof LocationPath);
        LocationPath path = (LocationPath) result;
        List<Step> steps = path.getSteps();
        assertTrue(steps.size() >= 2);
        Step bookStep = steps.get(steps.size() - 1);
        assertEquals("book", bookStep.getLocalName());
        List<Expr> predicates = bookStep.getPredicates();
        assertEquals(1, predicates.size());
        Expr pred = predicates.get(0);
        assertTrue(pred instanceof BinaryExpr);
        BinaryExpr andExpr = (BinaryExpr) pred;
        assertEquals(Operator.AND, andExpr.getOperator());
    }

    @Test
    public void testPathWithStep() throws XPathSyntaxException {
        XPathParser parser = new XPathParser("child::section/title");
        Expr result = parser.parse();
        assertTrue(result instanceof LocationPath);
        LocationPath path = (LocationPath) result;
        List<Step> steps = path.getSteps();
        assertEquals(2, steps.size());
        Step firstStep = steps.get(0);
        assertEquals(Step.Axis.CHILD, firstStep.getAxis());
        assertEquals("section", firstStep.getLocalName());
        Step secondStep = steps.get(1);
        assertEquals("title", secondStep.getLocalName());
    }

    // --- Error cases ---

    @Test(expected = XPathSyntaxException.class)
    public void testMalformedUnclosedBracket() throws XPathSyntaxException {
        XPathParser parser = new XPathParser("book[1");
        parser.parse();
    }

    @Test(expected = XPathSyntaxException.class)
    public void testMalformedUnclosedParen() throws XPathSyntaxException {
        XPathParser parser = new XPathParser("concat('a'");
        parser.parse();
    }

    @Test(expected = XPathSyntaxException.class)
    public void testMalformedTrailingOperator() throws XPathSyntaxException {
        XPathParser parser = new XPathParser("1 +");
        parser.parse();
    }

    @Test(expected = XPathSyntaxException.class)
    public void testMalformedLeadingOperator() throws XPathSyntaxException {
        XPathParser parser = new XPathParser("+ 1");
        parser.parse();
    }

    // --- Static helper ---

    @Test
    public void testParseExpressionStatic() throws XPathSyntaxException {
        Expr result = XPathParser.parseExpression("42");
        assertTrue(result instanceof Literal);
    }

    // --- Variable reference ---

    @Test
    public void testVariableReference() throws XPathSyntaxException {
        XPathParser parser = new XPathParser("$x");
        Expr result = parser.parse();
        assertTrue(result instanceof VariableReference);
        VariableReference var = (VariableReference) result;
        assertEquals("x", var.getLocalName());
    }

    // --- Path expression (filter + path) ---

    @Test
    public void testPathExpression() throws XPathSyntaxException {
        XPathParser parser = new XPathParser("//book/title");
        Expr result = parser.parse();
        assertTrue(result instanceof LocationPath);
        LocationPath path = (LocationPath) result;
        List<Step> steps = path.getSteps();
        assertTrue(steps.size() >= 2);
    }

    // --- Absolute path with single slash ---

    @Test
    public void testAbsolutePathSlashOnly() throws XPathSyntaxException {
        XPathParser parser = new XPathParser("/");
        Expr result = parser.parse();
        assertTrue(result instanceof LocationPath);
        LocationPath path = (LocationPath) result;
        assertTrue(path.isAbsolute());
        List<Step> steps = path.getSteps();
        assertEquals(0, steps.size());
    }
}

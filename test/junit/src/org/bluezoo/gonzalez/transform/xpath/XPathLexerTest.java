/*
 * XPathLexerTest.java
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

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for the {@link XPathLexer}.
 *
 * @author Chris Burdess
 */
public class XPathLexerTest {

    // --- Simple tokens: numbers ---

    @Test
    public void testNumberInteger() {
        XPathLexer lexer = new XPathLexer("42");
        XPathToken token = lexer.current();
        String value = lexer.value();
        assertEquals(XPathToken.NUMBER_LITERAL, token);
        assertEquals("42", value);
        lexer.advance();
        assertEquals(XPathToken.EOF, lexer.current());
    }

    @Test
    public void testNumberDecimal() {
        XPathLexer lexer = new XPathLexer("3.14");
        XPathToken token = lexer.current();
        String value = lexer.value();
        assertEquals(XPathToken.NUMBER_LITERAL, token);
        assertEquals("3.14", value);
        lexer.advance();
        assertEquals(XPathToken.EOF, lexer.current());
    }

    // --- Simple tokens: strings ---

    @Test
    public void testStringSingleQuoted() {
        XPathLexer lexer = new XPathLexer("'hello'");
        XPathToken token = lexer.current();
        String value = lexer.value();
        assertEquals(XPathToken.STRING_LITERAL, token);
        assertEquals("hello", value);
        lexer.advance();
        assertEquals(XPathToken.EOF, lexer.current());
    }

    @Test
    public void testStringDoubleQuoted() {
        XPathLexer lexer = new XPathLexer("\"world\"");
        XPathToken token = lexer.current();
        String value = lexer.value();
        assertEquals(XPathToken.STRING_LITERAL, token);
        assertEquals("world", value);
        lexer.advance();
        assertEquals(XPathToken.EOF, lexer.current());
    }

    // --- Simple tokens: operators ---

    @Test
    public void testOperatorPlus() {
        XPathLexer lexer = new XPathLexer("+");
        assertEquals(XPathToken.PLUS, lexer.current());
        assertEquals("+", lexer.value());
    }

    @Test
    public void testOperatorMinus() {
        XPathLexer lexer = new XPathLexer("-");
        assertEquals(XPathToken.MINUS, lexer.current());
        assertEquals("-", lexer.value());
    }

    @Test
    public void testOperatorStar() {
        XPathLexer lexer = new XPathLexer("*");
        assertEquals(XPathToken.STAR, lexer.current());
        assertEquals("*", lexer.value());
    }

    @Test
    public void testOperatorEquals() {
        XPathLexer lexer = new XPathLexer("=");
        assertEquals(XPathToken.EQUALS, lexer.current());
        assertEquals("=", lexer.value());
    }

    @Test
    public void testOperatorNotEquals() {
        XPathLexer lexer = new XPathLexer("!=");
        assertEquals(XPathToken.NOT_EQUALS, lexer.current());
        assertEquals("!=", lexer.value());
    }

    @Test
    public void testOperatorLessThan() {
        XPathLexer lexer = new XPathLexer("<");
        assertEquals(XPathToken.LESS_THAN, lexer.current());
        assertEquals("<", lexer.value());
    }

    @Test
    public void testOperatorGreaterThan() {
        XPathLexer lexer = new XPathLexer(">");
        assertEquals(XPathToken.GREATER_THAN, lexer.current());
        assertEquals(">", lexer.value());
    }

    @Test
    public void testOperatorLessThanOrEqual() {
        XPathLexer lexer = new XPathLexer("<=");
        assertEquals(XPathToken.LESS_THAN_OR_EQUAL, lexer.current());
        assertEquals("<=", lexer.value());
    }

    @Test
    public void testOperatorGreaterThanOrEqual() {
        XPathLexer lexer = new XPathLexer(">=");
        assertEquals(XPathToken.GREATER_THAN_OR_EQUAL, lexer.current());
        assertEquals(">=", lexer.value());
    }

    // --- Names and QNames ---

    @Test
    public void testNameSimple() {
        XPathLexer lexer = new XPathLexer("foo");
        XPathToken token = lexer.current();
        String value = lexer.value();
        assertEquals(XPathToken.NCNAME, token);
        assertEquals("foo", value);
    }

    @Test
    public void testQName() {
        XPathLexer lexer = new XPathLexer("ns:bar");
        XPathToken token = lexer.current();
        String value = lexer.value();
        assertEquals(XPathToken.NCNAME, token);
        assertEquals("ns", value);
        lexer.advance();
        assertEquals(XPathToken.COLON, lexer.current());
        lexer.advance();
        assertEquals(XPathToken.NCNAME, lexer.current());
        assertEquals("bar", lexer.value());
    }

    // --- Axis tokens ---

    @Test
    public void testAxisChild() {
        XPathLexer lexer = new XPathLexer("child::");
        assertEquals(XPathToken.AXIS_CHILD, lexer.current());
        assertEquals("child", lexer.value());
    }

    @Test
    public void testAxisParent() {
        XPathLexer lexer = new XPathLexer("parent::");
        assertEquals(XPathToken.AXIS_PARENT, lexer.current());
        assertEquals("parent", lexer.value());
    }

    @Test
    public void testAxisDescendant() {
        XPathLexer lexer = new XPathLexer("descendant::");
        assertEquals(XPathToken.AXIS_DESCENDANT, lexer.current());
        assertEquals("descendant", lexer.value());
    }

    @Test
    public void testAxisSelf() {
        XPathLexer lexer = new XPathLexer("self::");
        assertEquals(XPathToken.AXIS_SELF, lexer.current());
        assertEquals("self", lexer.value());
    }

    // --- Abbreviated syntax ---

    @Test
    public void testAbbreviationDot() {
        XPathLexer lexer = new XPathLexer(".");
        assertEquals(XPathToken.DOT, lexer.current());
        assertEquals(".", lexer.value());
    }

    @Test
    public void testAbbreviationDoubleDot() {
        XPathLexer lexer = new XPathLexer("..");
        assertEquals(XPathToken.DOUBLE_DOT, lexer.current());
        assertEquals("..", lexer.value());
    }

    @Test
    public void testAbbreviationAt() {
        XPathLexer lexer = new XPathLexer("@");
        assertEquals(XPathToken.AT, lexer.current());
        assertEquals("@", lexer.value());
    }

    @Test
    public void testAbbreviationDoubleSlash() {
        XPathLexer lexer = new XPathLexer("//");
        assertEquals(XPathToken.DOUBLE_SLASH, lexer.current());
        assertEquals("//", lexer.value());
    }

    // --- Functions ---

    @Test
    public void testFunctionConcat() {
        XPathLexer lexer = new XPathLexer("concat(");
        XPathToken nameToken = lexer.current();
        String nameValue = lexer.value();
        assertEquals(XPathToken.NCNAME, nameToken);
        assertEquals("concat", nameValue);
        lexer.advance();
        assertEquals(XPathToken.LPAREN, lexer.current());
    }

    @Test
    public void testFunctionPosition() {
        XPathLexer lexer = new XPathLexer("position(");
        XPathToken nameToken = lexer.current();
        String nameValue = lexer.value();
        assertEquals(XPathToken.NCNAME, nameToken);
        assertEquals("position", nameValue);
        lexer.advance();
        assertEquals(XPathToken.LPAREN, lexer.current());
    }

    // --- Complex expressions ---

    @Test
    public void testComplexExpressionBookPredicate() {
        XPathLexer lexer = new XPathLexer("//book[@price > 10]");
        assertEquals(XPathToken.DOUBLE_SLASH, lexer.current());
        lexer.advance();
        assertEquals(XPathToken.NCNAME, lexer.current());
        assertEquals("book", lexer.value());
        lexer.advance();
        assertEquals(XPathToken.LBRACKET, lexer.current());
        lexer.advance();
        assertEquals(XPathToken.AT, lexer.current());
        lexer.advance();
        assertEquals(XPathToken.NCNAME, lexer.current());
        assertEquals("price", lexer.value());
        lexer.advance();
        assertEquals(XPathToken.GREATER_THAN, lexer.current());
        lexer.advance();
        assertEquals(XPathToken.NUMBER_LITERAL, lexer.current());
        assertEquals("10", lexer.value());
        lexer.advance();
        assertEquals(XPathToken.RBRACKET, lexer.current());
        lexer.advance();
        assertEquals(XPathToken.EOF, lexer.current());
    }

    @Test
    public void testComplexExpressionChildSectionTitle() {
        XPathLexer lexer = new XPathLexer("child::section/title");
        assertEquals(XPathToken.AXIS_CHILD, lexer.current());
        assertEquals("child", lexer.value());
        lexer.advance();
        assertEquals(XPathToken.NCNAME, lexer.current());
        assertEquals("section", lexer.value());
        lexer.advance();
        assertEquals(XPathToken.SLASH, lexer.current());
        lexer.advance();
        assertEquals(XPathToken.NCNAME, lexer.current());
        assertEquals("title", lexer.value());
    }

    // --- Edge cases ---

    @Test
    public void testEmptyExpression() {
        XPathLexer lexer = new XPathLexer("");
        assertEquals(XPathToken.EOF, lexer.current());
        assertFalse(lexer.hasMore());
    }

    @Test
    public void testWhitespaceHandling() {
        XPathLexer lexer = new XPathLexer("  foo  bar  ");
        assertEquals(XPathToken.NCNAME, lexer.current());
        assertEquals("foo", lexer.value());
        lexer.advance();
        assertEquals(XPathToken.NCNAME, lexer.current());
        assertEquals("bar", lexer.value());
        lexer.advance();
        assertEquals(XPathToken.EOF, lexer.current());
    }

    @Test
    public void testWhitespaceBetweenTokens() {
        XPathLexer lexer = new XPathLexer("1 + 2");
        assertEquals(XPathToken.NUMBER_LITERAL, lexer.current());
        assertEquals("1", lexer.value());
        lexer.advance();
        assertEquals(XPathToken.PLUS, lexer.current());
        lexer.advance();
        assertEquals(XPathToken.NUMBER_LITERAL, lexer.current());
        assertEquals("2", lexer.value());
    }

    @Test
    public void testHasMore() {
        XPathLexer lexer = new XPathLexer("x");
        assertTrue(lexer.hasMore());
        lexer.advance();
        assertFalse(lexer.hasMore());
    }

    @Test
    public void testTokenStart() {
        XPathLexer lexer = new XPathLexer("  foo");
        int start = lexer.tokenStart();
        assertEquals(2, start);
    }

    @Test(expected = NullPointerException.class)
    public void testNullExpressionThrows() {
        new XPathLexer(null);
    }

    @Test
    public void testPathOperators() {
        XPathLexer lexer = new XPathLexer("/");
        assertEquals(XPathToken.SLASH, lexer.current());
        lexer.advance();
        assertEquals(XPathToken.EOF, lexer.current());
    }

    @Test
    public void testDelimiters() {
        XPathLexer lexer = new XPathLexer("( ) [ ] ,");
        assertEquals(XPathToken.LPAREN, lexer.current());
        lexer.advance();
        assertEquals(XPathToken.RPAREN, lexer.current());
        lexer.advance();
        assertEquals(XPathToken.LBRACKET, lexer.current());
        lexer.advance();
        assertEquals(XPathToken.RBRACKET, lexer.current());
        lexer.advance();
        assertEquals(XPathToken.COMMA, lexer.current());
    }

    @Test
    public void testVariablePrefix() {
        XPathLexer lexer = new XPathLexer("$x");
        assertEquals(XPathToken.DOLLAR, lexer.current());
        lexer.advance();
        assertEquals(XPathToken.NCNAME, lexer.current());
        assertEquals("x", lexer.value());
    }

    @Test
    public void testUnionOperator() {
        XPathLexer lexer = new XPathLexer("a | b");
        assertEquals(XPathToken.NCNAME, lexer.current());
        assertEquals("a", lexer.value());
        lexer.advance();
        assertEquals(XPathToken.PIPE, lexer.current());
        lexer.advance();
        assertEquals(XPathToken.NCNAME, lexer.current());
        assertEquals("b", lexer.value());
    }
}

/*
 * XPathFunctionTest.java
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

package org.bluezoo.gonzalez.transform.xpath.function;

import org.bluezoo.gonzalez.transform.xpath.BasicXPathContext;
import org.bluezoo.gonzalez.transform.xpath.XPathContext;
import org.bluezoo.gonzalez.transform.xpath.type.NodeType;
import org.bluezoo.gonzalez.transform.xpath.type.XPathBoolean;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNode;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNodeSet;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNumber;
import org.bluezoo.gonzalez.transform.xpath.type.XPathSequence;
import org.bluezoo.gonzalez.transform.xpath.type.XPathString;
import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Unit tests for XPath core functions (string, number, boolean).
 *
 * @author Chris Burdess
 */
public class XPathFunctionTest {

    /**
     * Minimal XPathNode implementation for function tests that require a context node.
     */
    private static class MockXPathNode implements XPathNode {
        private final String stringValue;

        MockXPathNode(String stringValue) {
            this.stringValue = stringValue;
        }

        @Override
        public NodeType getNodeType() {
            return NodeType.TEXT;
        }

        @Override
        public String getNamespaceURI() {
            return null;
        }

        @Override
        public String getLocalName() {
            return null;
        }

        @Override
        public String getPrefix() {
            return null;
        }

        @Override
        public String getStringValue() {
            return stringValue;
        }

        @Override
        public XPathNode getParent() {
            return null;
        }

        @Override
        public Iterator<XPathNode> getChildren() {
            return Collections.<XPathNode>emptyList().iterator();
        }

        @Override
        public Iterator<XPathNode> getAttributes() {
            return Collections.<XPathNode>emptyList().iterator();
        }

        @Override
        public Iterator<XPathNode> getNamespaces() {
            return Collections.<XPathNode>emptyList().iterator();
        }

        @Override
        public XPathNode getFollowingSibling() {
            return null;
        }

        @Override
        public XPathNode getPrecedingSibling() {
            return null;
        }

        @Override
        public long getDocumentOrder() {
            return 0;
        }

        @Override
        public boolean isSameNode(XPathNode other) {
            return this == other;
        }

        @Override
        public XPathNode getRoot() {
            return this;
        }

        @Override
        public boolean isFullyNavigable() {
            return false;
        }
    }

    private XPathContext createContext(String contextStringValue) {
        XPathNode node = new MockXPathNode(contextStringValue);
        return new BasicXPathContext(node);
    }

    private XPathContext createContext() {
        return createContext("default");
    }

    // ========== String Functions ==========

    @Test
    public void testStringLength() throws Exception {
        XPathContext ctx = createContext("hello");
        List<XPathValue> args = new ArrayList<XPathValue>();
        args.add(XPathString.of("hello"));

        XPathValue result = StringFunctions.STRING_LENGTH.evaluate(args, ctx);
        double len = result.asNumber();
        assertEquals(5.0, len, 0.0);
    }

    @Test
    public void testStringLengthEmptyArg() throws Exception {
        XPathContext ctx = createContext();
        List<XPathValue> args = new ArrayList<XPathValue>();
        args.add(XPathString.EMPTY);

        XPathValue result = StringFunctions.STRING_LENGTH.evaluate(args, ctx);
        double len = result.asNumber();
        assertEquals(0.0, len, 0.0);
    }

    @Test
    public void testStringLengthNoArgUsesContext() throws Exception {
        XPathContext ctx = createContext("context-value");
        List<XPathValue> args = new ArrayList<XPathValue>();

        XPathValue result = StringFunctions.STRING_LENGTH.evaluate(args, ctx);
        double len = result.asNumber();
        assertEquals(13.0, len, 0.0);
    }

    @Test
    public void testConcat() throws Exception {
        XPathContext ctx = createContext();
        List<XPathValue> args = new ArrayList<XPathValue>();
        args.add(XPathString.of("Hello"));
        args.add(XPathString.of(", "));
        args.add(XPathString.of("World"));

        XPathValue result = StringFunctions.CONCAT.evaluate(args, ctx);
        String str = result.asString();
        assertEquals("Hello, World", str);
    }

    @Test
    public void testSubstring() throws Exception {
        XPathContext ctx = createContext();
        List<XPathValue> args = new ArrayList<XPathValue>();
        args.add(XPathString.of("Hello"));
        args.add(XPathNumber.of(2));
        args.add(XPathNumber.of(3));

        XPathValue result = StringFunctions.SUBSTRING.evaluate(args, ctx);
        String str = result.asString();
        assertEquals("ell", str);
    }

    @Test
    public void testSubstringNoLength() throws Exception {
        XPathContext ctx = createContext();
        List<XPathValue> args = new ArrayList<XPathValue>();
        args.add(XPathString.of("Hello"));
        args.add(XPathNumber.of(3));

        XPathValue result = StringFunctions.SUBSTRING.evaluate(args, ctx);
        String str = result.asString();
        assertEquals("llo", str);
    }

    @Test
    public void testContains() throws Exception {
        XPathContext ctx = createContext();
        List<XPathValue> args = new ArrayList<XPathValue>();
        args.add(XPathString.of("Hello World"));
        args.add(XPathString.of("World"));

        XPathValue result = StringFunctions.CONTAINS.evaluate(args, ctx);
        boolean b = result.asBoolean();
        assertTrue(b);
    }

    @Test
    public void testContainsFalse() throws Exception {
        XPathContext ctx = createContext();
        List<XPathValue> args = new ArrayList<XPathValue>();
        args.add(XPathString.of("Hello"));
        args.add(XPathString.of("xyz"));

        XPathValue result = StringFunctions.CONTAINS.evaluate(args, ctx);
        boolean b = result.asBoolean();
        assertFalse(b);
    }

    @Test
    public void testStartsWith() throws Exception {
        XPathContext ctx = createContext();
        List<XPathValue> args = new ArrayList<XPathValue>();
        args.add(XPathString.of("Hello World"));
        args.add(XPathString.of("Hello"));

        XPathValue result = StringFunctions.STARTS_WITH.evaluate(args, ctx);
        boolean b = result.asBoolean();
        assertTrue(b);
    }

    @Test
    public void testStartsWithFalse() throws Exception {
        XPathContext ctx = createContext();
        List<XPathValue> args = new ArrayList<XPathValue>();
        args.add(XPathString.of("Hello"));
        args.add(XPathString.of("World"));

        XPathValue result = StringFunctions.STARTS_WITH.evaluate(args, ctx);
        boolean b = result.asBoolean();
        assertFalse(b);
    }

    @Test
    public void testNormalizeSpace() throws Exception {
        XPathContext ctx = createContext();
        List<XPathValue> args = new ArrayList<XPathValue>();
        args.add(XPathString.of("  hello   world  "));

        XPathValue result = StringFunctions.NORMALIZE_SPACE.evaluate(args, ctx);
        String str = result.asString();
        assertEquals("hello world", str);
    }

    @Test
    public void testTranslate() throws Exception {
        XPathContext ctx = createContext();
        List<XPathValue> args = new ArrayList<XPathValue>();
        args.add(XPathString.of("abc"));
        args.add(XPathString.of("abc"));
        args.add(XPathString.of("ABC"));

        XPathValue result = StringFunctions.TRANSLATE.evaluate(args, ctx);
        String str = result.asString();
        assertEquals("ABC", str);
    }

    @Test
    public void testTranslateRemoveChars() throws Exception {
        XPathContext ctx = createContext();
        List<XPathValue> args = new ArrayList<XPathValue>();
        args.add(XPathString.of("abcdef"));
        args.add(XPathString.of("bdf"));
        args.add(XPathString.of(""));

        XPathValue result = StringFunctions.TRANSLATE.evaluate(args, ctx);
        String str = result.asString();
        assertEquals("ace", str);
    }

    @Test
    public void testUpperCase() throws Exception {
        XPathContext ctx = createContext();
        List<XPathValue> args = new ArrayList<XPathValue>();
        args.add(XPathString.of("hello"));

        XPathValue result = StringFunctions.UPPER_CASE.evaluate(args, ctx);
        String str = result.asString();
        assertEquals("HELLO", str);
    }

    @Test
    public void testLowerCase() throws Exception {
        XPathContext ctx = createContext();
        List<XPathValue> args = new ArrayList<XPathValue>();
        args.add(XPathString.of("HELLO"));

        XPathValue result = StringFunctions.LOWER_CASE.evaluate(args, ctx);
        String str = result.asString();
        assertEquals("hello", str);
    }

    @Test
    public void testSubstringBefore() throws Exception {
        XPathContext ctx = createContext();
        List<XPathValue> args = new ArrayList<XPathValue>();
        args.add(XPathString.of("1999/04/01"));
        args.add(XPathString.of("/"));

        XPathValue result = StringFunctions.SUBSTRING_BEFORE.evaluate(args, ctx);
        String str = result.asString();
        assertEquals("1999", str);
    }

    @Test
    public void testSubstringAfter() throws Exception {
        XPathContext ctx = createContext();
        List<XPathValue> args = new ArrayList<XPathValue>();
        args.add(XPathString.of("1999/04/01"));
        args.add(XPathString.of("/"));

        XPathValue result = StringFunctions.SUBSTRING_AFTER.evaluate(args, ctx);
        String str = result.asString();
        assertEquals("04/01", str);
    }

    // ========== Number Functions ==========

    @Test
    public void testNumber() throws Exception {
        XPathContext ctx = createContext();
        List<XPathValue> args = new ArrayList<XPathValue>();
        args.add(XPathString.of("42"));

        XPathValue result = NumberFunctions.NUMBER.evaluate(args, ctx);
        double n = result.asNumber();
        assertEquals(42.0, n, 0.0);
    }

    @Test
    public void testNumberFromNumber() throws Exception {
        XPathContext ctx = createContext();
        List<XPathValue> args = new ArrayList<XPathValue>();
        args.add(XPathNumber.of(3.14));

        XPathValue result = NumberFunctions.NUMBER.evaluate(args, ctx);
        double n = result.asNumber();
        assertEquals(3.14, n, 0.001);
    }

    @Test
    public void testSum() throws Exception {
        XPathContext ctx = createContext();
        List<XPathValue> args = new ArrayList<XPathValue>();
        List<XPathValue> seq = new ArrayList<XPathValue>();
        seq.add(XPathNumber.of(1));
        seq.add(XPathNumber.of(2));
        seq.add(XPathNumber.of(3));
        args.add(new XPathSequence(seq));

        XPathValue result = NumberFunctions.SUM.evaluate(args, ctx);
        double n = result.asNumber();
        assertEquals(6.0, n, 0.0);
    }

    @Test
    public void testFloor() throws Exception {
        XPathContext ctx = createContext();
        List<XPathValue> args = new ArrayList<XPathValue>();
        args.add(XPathNumber.of(3.7));

        XPathValue result = NumberFunctions.FLOOR.evaluate(args, ctx);
        double n = result.asNumber();
        assertEquals(3.0, n, 0.0);
    }

    @Test
    public void testCeiling() throws Exception {
        XPathContext ctx = createContext();
        List<XPathValue> args = new ArrayList<XPathValue>();
        args.add(XPathNumber.of(3.2));

        XPathValue result = NumberFunctions.CEILING.evaluate(args, ctx);
        double n = result.asNumber();
        assertEquals(4.0, n, 0.0);
    }

    @Test
    public void testRound() throws Exception {
        XPathContext ctx = createContext();
        List<XPathValue> args = new ArrayList<XPathValue>();
        args.add(XPathNumber.of(3.5));

        XPathValue result = NumberFunctions.ROUND.evaluate(args, ctx);
        double n = result.asNumber();
        assertEquals(4.0, n, 0.0);
    }

    @Test
    public void testRoundNegative() throws Exception {
        XPathContext ctx = createContext();
        List<XPathValue> args = new ArrayList<XPathValue>();
        args.add(XPathNumber.of(-0.5));

        XPathValue result = NumberFunctions.ROUND.evaluate(args, ctx);
        double n = result.asNumber();
        assertEquals(-0.0, n, 0.0);
    }

    @Test
    public void testAbs() throws Exception {
        XPathContext ctx = createContext();
        List<XPathValue> args = new ArrayList<XPathValue>();
        args.add(XPathNumber.of(-42));

        XPathValue result = NumberFunctions.ABS.evaluate(args, ctx);
        double n = result.asNumber();
        assertEquals(42.0, n, 0.0);
    }

    @Test
    public void testAbsPositive() throws Exception {
        XPathContext ctx = createContext();
        List<XPathValue> args = new ArrayList<XPathValue>();
        args.add(XPathNumber.of(42));

        XPathValue result = NumberFunctions.ABS.evaluate(args, ctx);
        double n = result.asNumber();
        assertEquals(42.0, n, 0.0);
    }

    // ========== Boolean Functions ==========

    @Test
    public void testBoolean() throws Exception {
        XPathContext ctx = createContext();
        List<XPathValue> args = new ArrayList<XPathValue>();
        args.add(XPathBoolean.TRUE);

        XPathValue result = BooleanFunctions.BOOLEAN.evaluate(args, ctx);
        boolean b = result.asBoolean();
        assertTrue(b);
    }

    @Test
    public void testBooleanFromString() throws Exception {
        XPathContext ctx = createContext();
        List<XPathValue> args = new ArrayList<XPathValue>();
        args.add(XPathString.of("x"));

        XPathValue result = BooleanFunctions.BOOLEAN.evaluate(args, ctx);
        boolean b = result.asBoolean();
        assertTrue(b);
    }

    @Test
    public void testBooleanFromEmptyString() throws Exception {
        XPathContext ctx = createContext();
        List<XPathValue> args = new ArrayList<XPathValue>();
        args.add(XPathString.EMPTY);

        XPathValue result = BooleanFunctions.BOOLEAN.evaluate(args, ctx);
        boolean b = result.asBoolean();
        assertFalse(b);
    }

    @Test
    public void testNot() throws Exception {
        XPathContext ctx = createContext();
        List<XPathValue> args = new ArrayList<XPathValue>();
        args.add(XPathBoolean.TRUE);

        XPathValue result = BooleanFunctions.NOT.evaluate(args, ctx);
        boolean b = result.asBoolean();
        assertFalse(b);
    }

    @Test
    public void testNotFalse() throws Exception {
        XPathContext ctx = createContext();
        List<XPathValue> args = new ArrayList<XPathValue>();
        args.add(XPathBoolean.FALSE);

        XPathValue result = BooleanFunctions.NOT.evaluate(args, ctx);
        boolean b = result.asBoolean();
        assertTrue(b);
    }

    @Test
    public void testTrue() throws Exception {
        XPathContext ctx = createContext();
        List<XPathValue> args = new ArrayList<XPathValue>();

        XPathValue result = BooleanFunctions.TRUE.evaluate(args, ctx);
        assertSame(XPathBoolean.TRUE, result);
    }

    @Test
    public void testFalse() throws Exception {
        XPathContext ctx = createContext();
        List<XPathValue> args = new ArrayList<XPathValue>();

        XPathValue result = BooleanFunctions.FALSE.evaluate(args, ctx);
        assertSame(XPathBoolean.FALSE, result);
    }
}

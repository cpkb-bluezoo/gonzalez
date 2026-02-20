/*
 * CollationTest.java
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

import org.bluezoo.gonzalez.transform.xpath.expr.XPathException;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link Collation} - XSLT/XPath string comparison.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public class CollationTest {

    // --- forUri factory method ---

    @Test
    public void testForUriNullReturnsCodepoint() throws XPathException {
        Collation collation = Collation.forUri(null);
        assertNotNull(collation);
        assertEquals(Collation.CODEPOINT_URI, collation.getUri());
    }

    @Test
    public void testForUriEmptyReturnsCodepoint() throws XPathException {
        Collation collation = Collation.forUri("");
        assertNotNull(collation);
        assertEquals(Collation.CODEPOINT_URI, collation.getUri());
    }

    @Test
    public void testForUriCodepointReturnsCodepoint() throws XPathException {
        Collation collation = Collation.forUri(Collation.CODEPOINT_URI);
        assertNotNull(collation);
        assertEquals(Collation.CODEPOINT_URI, collation.getUri());
    }

    @Test
    public void testForUriHtmlAsciiCaseInsensitive() throws XPathException {
        Collation collation = Collation.forUri(Collation.HTML_ASCII_CASE_INSENSITIVE_URI);
        assertNotNull(collation);
        assertEquals(Collation.HTML_ASCII_CASE_INSENSITIVE_URI, collation.getUri());
    }

    @Test
    public void testForUriUcaBase() throws XPathException {
        Collation collation = Collation.forUri(Collation.UCA_BASE_URI);
        assertNotNull(collation);
        assertTrue(collation.getUri().startsWith(Collation.UCA_BASE_URI));
    }

    @Test
    public void testForUriUcaWithStrength() throws XPathException {
        String uri = Collation.UCA_BASE_URI + "?strength=primary";
        Collation collation = Collation.forUri(uri);
        assertNotNull(collation);
        assertEquals(uri, collation.getUri());
    }

    @Test
    public void testForUriUnknownFallsBackToCodepointLike() throws XPathException {
        String unknownUri = "http://example.com/unknown-collation";
        Collation collation = Collation.forUri(unknownUri);
        assertNotNull(collation);
        assertEquals(unknownUri, collation.getUri());
        int result = collation.compare("a", "b");
        assertTrue(result < 0);
    }

    @Test
    public void testForUriCaching() throws XPathException {
        Collation first = Collation.forUri(Collation.HTML_ASCII_CASE_INSENSITIVE_URI);
        Collation second = Collation.forUri(Collation.HTML_ASCII_CASE_INSENSITIVE_URI);
        assertSame(first, second);
    }

    // --- Codepoint collation: case-sensitive Unicode ordering ---

    @Test
    public void testCodepointCompareCaseSensitive() throws XPathException {
        Collation collation = Collation.getCodepointCollation();
        int result = collation.compare("apple", "Apple");
        assertTrue("Lowercase 'a' > uppercase 'A' in Unicode", result > 0);
    }

    @Test
    public void testCodepointCompareOrdering() throws XPathException {
        Collation collation = Collation.getCodepointCollation();
        int ab = collation.compare("a", "b");
        assertTrue(ab < 0);
        int ba = collation.compare("b", "a");
        assertTrue(ba > 0);
        int aa = collation.compare("a", "a");
        assertEquals(0, aa);
    }

    @Test
    public void testCodepointCompareEqualStrings() throws XPathException {
        Collation collation = Collation.getCodepointCollation();
        int result = collation.compare("hello", "hello");
        assertEquals(0, result);
    }

    @Test
    public void testCodepointComparePrefix() throws XPathException {
        Collation collation = Collation.getCodepointCollation();
        int result = collation.compare("abc", "abcd");
        assertTrue(result < 0);
    }

    // --- HTML ASCII case-insensitive collation ---

    @Test
    public void testHtmlAsciiCaseInsensitiveCompareEqual() throws XPathException {
        Collation collation = Collation.forUri(Collation.HTML_ASCII_CASE_INSENSITIVE_URI);
        int result = collation.compare("HELLO", "hello");
        assertEquals(0, result);
    }

    @Test
    public void testHtmlAsciiCaseInsensitiveCompareMixed() throws XPathException {
        Collation collation = Collation.forUri(Collation.HTML_ASCII_CASE_INSENSITIVE_URI);
        int result = collation.compare("Apple", "APPLE");
        assertEquals(0, result);
    }

    @Test
    public void testHtmlAsciiCaseInsensitiveCompareDifferent() throws XPathException {
        Collation collation = Collation.forUri(Collation.HTML_ASCII_CASE_INSENSITIVE_URI);
        int result = collation.compare("apple", "banana");
        assertTrue(result < 0);
    }

    @Test
    public void testHtmlAsciiCaseInsensitiveNonAsciiUnaffected() throws XPathException {
        Collation collation = Collation.forUri(Collation.HTML_ASCII_CASE_INSENSITIVE_URI);
        int result = collation.compare("\u00e4", "\u00c4");
        assertTrue("Non-ASCII chars not case-folded in HTML collation", result != 0);
    }

    // --- equals, startsWith, endsWith, contains ---

    @Test
    public void testEquals() throws XPathException {
        Collation collation = Collation.forUri(Collation.HTML_ASCII_CASE_INSENSITIVE_URI);
        boolean eq = collation.equals("Test", "TEST");
        assertTrue(eq);
    }

    @Test
    public void testStartsWith() throws XPathException {
        Collation collation = Collation.forUri(Collation.HTML_ASCII_CASE_INSENSITIVE_URI);
        boolean result = collation.startsWith("HELLO World", "hello");
        assertTrue(result);
    }

    @Test
    public void testEndsWith() throws XPathException {
        Collation collation = Collation.forUri(Collation.HTML_ASCII_CASE_INSENSITIVE_URI);
        boolean result = collation.endsWith("Hello WORLD", "world");
        assertTrue(result);
    }

    @Test
    public void testContains() throws XPathException {
        Collation collation = Collation.forUri(Collation.HTML_ASCII_CASE_INSENSITIVE_URI);
        boolean result = collation.contains("The Quick Brown Fox", "BROWN");
        assertTrue(result);
    }

    // --- Null handling ---

    @Test
    public void testCompareBothNull() throws XPathException {
        Collation collation = Collation.getCodepointCollation();
        int result = collation.compare(null, null);
        assertEquals(0, result);
    }

    @Test
    public void testCompareFirstNull() throws XPathException {
        Collation collation = Collation.getCodepointCollation();
        int result = collation.compare(null, "a");
        assertTrue(result < 0);
    }

    @Test
    public void testCompareSecondNull() throws XPathException {
        Collation collation = Collation.getCodepointCollation();
        int result = collation.compare("a", null);
        assertTrue(result > 0);
    }
}

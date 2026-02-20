/*
 * XSDUtilsTest.java
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

package org.bluezoo.gonzalez.schema.xsd;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests for XSDUtils static utility methods.
 *
 * @author Chris Burdess
 */
public class XSDUtilsTest {

    @Test
    public void testExtractLocalNameWithPrefix() {
        String qName = "prefix:localName";
        String result = XSDUtils.extractLocalName(qName);
        assertEquals("localName", result);
    }

    @Test
    public void testExtractLocalNameWithoutPrefix() {
        String qName = "localName";
        String result = XSDUtils.extractLocalName(qName);
        assertEquals("localName", result);
    }

    @Test
    public void testExtractPrefixWithPrefix() {
        String qName = "prefix:localName";
        String result = XSDUtils.extractPrefix(qName);
        assertEquals("prefix", result);
    }

    @Test
    public void testExtractPrefixWithoutPrefix() {
        String qName = "localName";
        String result = XSDUtils.extractPrefix(qName);
        assertNull(result);
    }

    @Test
    public void testExtractLocalNameEmptyString() {
        String qName = "";
        String result = XSDUtils.extractLocalName(qName);
        assertEquals("", result);
    }

    @Test
    public void testExtractLocalNameNull() {
        String result = XSDUtils.extractLocalName(null);
        assertNull(result);
    }

    @Test
    public void testExtractPrefixNull() {
        String result = XSDUtils.extractPrefix(null);
        assertNull(result);
    }

    @Test
    public void testExtractLocalNameColonAtStart() {
        String qName = ":localName";
        String result = XSDUtils.extractLocalName(qName);
        assertEquals("localName", result);
    }

    @Test
    public void testExtractPrefixColonAtStart() {
        String qName = ":localName";
        String result = XSDUtils.extractPrefix(qName);
        assertEquals("", result);
    }

    @Test
    public void testExtractLocalNameColonAtEnd() {
        String qName = "prefix:";
        String result = XSDUtils.extractLocalName(qName);
        assertEquals("", result);
    }

    @Test
    public void testExtractPrefixColonAtEnd() {
        String qName = "prefix:";
        String result = XSDUtils.extractPrefix(qName);
        assertEquals("prefix", result);
    }

    @Test
    public void testExtractLocalNameMultipleColons() {
        String qName = "ns:prefix:localName";
        String result = XSDUtils.extractLocalName(qName);
        assertEquals("prefix:localName", result);
    }

    @Test
    public void testExtractPrefixMultipleColons() {
        String qName = "ns:prefix:localName";
        String result = XSDUtils.extractPrefix(qName);
        assertEquals("ns", result);
    }
}

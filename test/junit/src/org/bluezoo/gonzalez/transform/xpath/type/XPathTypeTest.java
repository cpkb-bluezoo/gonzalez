/*
 * XPathTypeTest.java
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

package org.bluezoo.gonzalez.transform.xpath.type;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for XPath value types (XPathString, XPathNumber, XPathBoolean, XPathDateTime).
 *
 * @author Chris Burdess
 */
public class XPathTypeTest {

    // ========== XPathString Tests ==========

    @Test
    public void testXPathStringCreation() {
        XPathString s1 = new XPathString("hello");
        String val = s1.getValue();
        assertEquals("hello", val);

        XPathString s2 = XPathString.of("world");
        String val2 = s2.getValue();
        assertEquals("world", val2);

        XPathString empty = XPathString.EMPTY;
        String emptyVal = empty.getValue();
        assertEquals("", emptyVal);

        XPathString ofEmpty = XPathString.of("");
        assertSame(XPathString.EMPTY, ofEmpty);
    }

    @Test(expected = NullPointerException.class)
    public void testXPathStringNullThrows() {
        new XPathString(null);
    }

    @Test
    public void testXPathStringAsString() {
        XPathString s = new XPathString("test");
        String result = s.asString();
        assertEquals("test", result);

        XPathString empty = XPathString.EMPTY;
        String emptyResult = empty.asString();
        assertEquals("", emptyResult);
    }

    @Test
    public void testXPathStringAsNumberNumeric() {
        XPathString s = new XPathString("42");
        double result = s.asNumber();
        assertEquals(42.0, result, 0.0);

        XPathString s2 = new XPathString("  -3.14  ");
        double result2 = s2.asNumber();
        assertEquals(-3.14, result2, 0.001);

        XPathString s3 = new XPathString("0");
        double result3 = s3.asNumber();
        assertEquals(0.0, result3, 0.0);
    }

    @Test
    public void testXPathStringAsNumberNonNumeric() {
        XPathString s = new XPathString("hello");
        double result = s.asNumber();
        assertTrue(Double.isNaN(result));

        XPathString s2 = new XPathString("");
        double result2 = s2.asNumber();
        assertTrue(Double.isNaN(result2));

        XPathString s3 = new XPathString("abc123");
        double result3 = s3.asNumber();
        assertTrue(Double.isNaN(result3));
    }

    @Test
    public void testXPathStringAsBoolean() {
        XPathString empty = XPathString.EMPTY;
        boolean emptyBool = empty.asBoolean();
        assertFalse(emptyBool);

        XPathString nonEmpty = new XPathString("x");
        boolean nonEmptyBool = nonEmpty.asBoolean();
        assertTrue(nonEmptyBool);

        XPathString spaceOnly = new XPathString("   ");
        boolean spaceBool = spaceOnly.asBoolean();
        assertTrue(spaceBool);
    }

    @Test
    public void testXPathStringEquality() {
        XPathString s1 = new XPathString("hello");
        XPathString s2 = new XPathString("hello");
        XPathString s3 = new XPathString("world");

        assertTrue(s1.equals(s2));
        assertFalse(s1.equals(s3));
        assertTrue(s1.equals(s1));
        assertFalse(s1.equals(null));
        assertFalse(s1.equals("hello"));
    }

    @Test
    public void testXPathStringGetType() {
        XPathString s = new XPathString("x");
        XPathValue.Type type = s.getType();
        assertEquals(XPathValue.Type.STRING, type);
    }

    // ========== XPathNumber Tests ==========

    @Test
    public void testXPathNumberCreation() {
        XPathNumber n1 = new XPathNumber(42);
        double val = n1.getValue();
        assertEquals(42.0, val, 0.0);

        XPathNumber n2 = XPathNumber.of(100.5);
        double val2 = n2.getValue();
        assertEquals(100.5, val2, 0.0);

        XPathNumber zero = XPathNumber.ZERO;
        assertSame(XPathNumber.ZERO, XPathNumber.of(0.0));

        XPathNumber one = XPathNumber.ONE;
        assertSame(XPathNumber.ONE, XPathNumber.of(1.0));
    }

    @Test
    public void testXPathNumberAsNumber() {
        XPathNumber n = new XPathNumber(3.14);
        double result = n.asNumber();
        assertEquals(3.14, result, 0.001);
    }

    @Test
    public void testXPathNumberAsString() {
        XPathNumber n = new XPathNumber(42);
        String result = n.asString();
        assertEquals("42", result);

        XPathNumber n2 = new XPathNumber(3.14);
        String result2 = n2.asString();
        assertEquals("3.14", result2);

        XPathNumber nan = XPathNumber.NaN;
        String nanStr = nan.asString();
        assertEquals("NaN", nanStr);

        XPathNumber posInf = XPathNumber.POSITIVE_INFINITY;
        String posInfStr = posInf.asString();
        assertEquals("Infinity", posInfStr);

        XPathNumber negInf = XPathNumber.NEGATIVE_INFINITY;
        String negInfStr = negInf.asString();
        assertEquals("-Infinity", negInfStr);
    }

    @Test
    public void testXPathNumberAsBoolean() {
        XPathNumber zero = XPathNumber.ZERO;
        boolean zeroBool = zero.asBoolean();
        assertFalse(zeroBool);

        XPathNumber nan = XPathNumber.NaN;
        boolean nanBool = nan.asBoolean();
        assertFalse(nanBool);

        XPathNumber positive = new XPathNumber(1.5);
        boolean posBool = positive.asBoolean();
        assertTrue(posBool);

        XPathNumber negative = new XPathNumber(-1);
        boolean negBool = negative.asBoolean();
        assertTrue(negBool);
    }

    @Test
    public void testXPathNumberNaN() {
        XPathNumber nan = XPathNumber.NaN;
        assertTrue(nan.isNaN());
        assertFalse(nan.isInfinite());
    }

    @Test
    public void testXPathNumberInfinity() {
        XPathNumber posInf = XPathNumber.POSITIVE_INFINITY;
        assertTrue(posInf.isInfinite());
        assertFalse(posInf.isNaN());

        XPathNumber negInf = XPathNumber.NEGATIVE_INFINITY;
        assertTrue(negInf.isInfinite());
    }

    @Test
    public void testXPathNumberEquality() {
        XPathNumber n1 = new XPathNumber(42);
        XPathNumber n2 = new XPathNumber(42);
        XPathNumber n3 = new XPathNumber(43);

        assertTrue(n1.equals(n2));
        assertFalse(n1.equals(n3));

        XPathNumber nan1 = XPathNumber.NaN;
        XPathNumber nan2 = XPathNumber.NaN;
        assertTrue(nan1.equals(nan2));
    }

    @Test
    public void testXPathNumberGetType() {
        XPathNumber n = new XPathNumber(1);
        XPathValue.Type type = n.getType();
        assertEquals(XPathValue.Type.NUMBER, type);
    }

    // ========== XPathBoolean Tests ==========

    @Test
    public void testXPathBooleanConstants() {
        assertSame(XPathBoolean.TRUE, XPathBoolean.of(true));
        assertSame(XPathBoolean.FALSE, XPathBoolean.of(false));
    }

    @Test
    public void testXPathBooleanAsBoolean() {
        boolean trueVal = XPathBoolean.TRUE.asBoolean();
        assertTrue(trueVal);

        boolean falseVal = XPathBoolean.FALSE.asBoolean();
        assertFalse(falseVal);
    }

    @Test
    public void testXPathBooleanAsString() {
        String trueStr = XPathBoolean.TRUE.asString();
        assertEquals("true", trueStr);

        String falseStr = XPathBoolean.FALSE.asString();
        assertEquals("false", falseStr);
    }

    @Test
    public void testXPathBooleanAsNumber() {
        double trueNum = XPathBoolean.TRUE.asNumber();
        assertEquals(1.0, trueNum, 0.0);

        double falseNum = XPathBoolean.FALSE.asNumber();
        assertEquals(0.0, falseNum, 0.0);
    }

    @Test
    public void testXPathBooleanNot() {
        XPathBoolean result = XPathBoolean.TRUE.not();
        assertSame(XPathBoolean.FALSE, result);

        XPathBoolean result2 = XPathBoolean.FALSE.not();
        assertSame(XPathBoolean.TRUE, result2);
    }

    @Test
    public void testXPathBooleanGetType() {
        XPathValue.Type type = XPathBoolean.TRUE.getType();
        assertEquals(XPathValue.Type.BOOLEAN, type);
    }

    // ========== XPathDateTime Tests ==========

    @Test
    public void testXPathDateTimeParseDateTime() throws Exception {
        XPathDateTime dt = XPathDateTime.parseDateTime("2024-03-15T10:30:00Z");
        String str = dt.asString();
        assertEquals("2024-03-15T10:30:00Z", str);

        Integer year = dt.getYear();
        assertEquals(Integer.valueOf(2024), year);

        Integer month = dt.getMonth();
        assertEquals(Integer.valueOf(3), month);

        Integer day = dt.getDay();
        assertEquals(Integer.valueOf(15), day);

        Integer hour = dt.getHour();
        assertEquals(Integer.valueOf(10), hour);

        Integer minute = dt.getMinute();
        assertEquals(Integer.valueOf(30), minute);
    }

    @Test
    public void testXPathDateTimeParseDate() throws Exception {
        XPathDateTime d = XPathDateTime.parseDate("2024-03-15");
        String str = d.asString();
        assertEquals("2024-03-15", str);

        Integer year = d.getYear();
        assertEquals(Integer.valueOf(2024), year);

        Integer month = d.getMonth();
        assertEquals(Integer.valueOf(3), month);

        Integer day = d.getDay();
        assertEquals(Integer.valueOf(15), day);
    }

    @Test
    public void testXPathDateTimeParseTime() throws Exception {
        XPathDateTime t = XPathDateTime.parseTime("10:30:45");
        String str = t.asString();
        assertEquals("10:30:45", str);

        Integer hour = t.getHour();
        assertEquals(Integer.valueOf(10), hour);

        Integer minute = t.getMinute();
        assertEquals(Integer.valueOf(30), minute);
    }

    @Test
    public void testXPathDateTimeComparison() throws Exception {
        XPathDateTime dt1 = XPathDateTime.parseDateTime("2024-01-01T00:00:00Z");
        XPathDateTime dt2 = XPathDateTime.parseDateTime("2024-12-31T23:59:59Z");

        int cmp = dt1.compareTo(dt2);
        assertTrue(cmp < 0);

        int cmp2 = dt2.compareTo(dt1);
        assertTrue(cmp2 > 0);

        XPathDateTime dt3 = XPathDateTime.parseDateTime("2024-01-01T00:00:00Z");
        int cmp3 = dt1.compareTo(dt3);
        assertEquals(0, cmp3);
    }

    @Test
    public void testXPathDateTimeDurationArithmetic() throws Exception {
        XPathDateTime date = XPathDateTime.parseDate("2024-03-15");
        XPathDateTime duration = XPathDateTime.parseDayTimeDuration("P1D");

        XPathDateTime result = date.add(duration);
        String resultStr = result.asString();
        assertEquals("2024-03-16", resultStr);
    }

    @Test
    public void testXPathDateTimeParseDuration() throws Exception {
        XPathDateTime d = XPathDateTime.parseDuration("P1Y2M3DT4H5M6S");
        String str = d.asString();
        assertNotNull(str);
        assertTrue(d.isDuration());
    }

    @Test
    public void testXPathDateTimeAsBoolean() throws Exception {
        XPathDateTime dt = XPathDateTime.parseDateTime("2024-01-01T00:00:00Z");
        boolean result = dt.asBoolean();
        assertTrue(result);
    }

    @Test
    public void testXPathDateTimeAsNumber() throws Exception {
        XPathDateTime dt = XPathDateTime.parseDateTime("2024-01-01T00:00:00Z");
        double result = dt.asNumber();
        assertTrue(Double.isNaN(result));
    }

    @Test
    public void testXPathDateTimeGetType() throws Exception {
        XPathDateTime dt = XPathDateTime.parseDateTime("2024-01-01T00:00:00Z");
        XPathValue.Type type = dt.getType();
        assertEquals(XPathValue.Type.ATOMIC, type);
    }
}

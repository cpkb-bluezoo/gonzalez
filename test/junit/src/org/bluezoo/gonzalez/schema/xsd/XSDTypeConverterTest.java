/*
 * XSDTypeConverterTest.java
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

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests for XSDTypeConverter convert and normalization methods.
 *
 * @author Chris Burdess
 */
public class XSDTypeConverterTest {

    // --- convert: string ---

    @Test
    public void testConvertStringIdentity() {
        String lexicalValue = "hello world";
        Object result = XSDTypeConverter.convert("string", lexicalValue);
        assertTrue(result instanceof String);
        String strResult = (String) result;
        assertEquals("hello world", strResult);
    }

    // --- convert: boolean ---

    @Test
    public void testConvertBooleanTrue() {
        Object result = XSDTypeConverter.convert("boolean", "true");
        assertTrue(result instanceof Boolean);
        Boolean boolResult = (Boolean) result;
        assertTrue(boolResult.booleanValue());
    }

    @Test
    public void testConvertBooleanFalse() {
        Object result = XSDTypeConverter.convert("boolean", "false");
        assertTrue(result instanceof Boolean);
        Boolean boolResult = (Boolean) result;
        assertFalse(boolResult.booleanValue());
    }

    @Test
    public void testConvertBooleanOne() {
        Object result = XSDTypeConverter.convert("boolean", "1");
        assertTrue(result instanceof Boolean);
        Boolean boolResult = (Boolean) result;
        assertTrue(boolResult.booleanValue());
    }

    @Test
    public void testConvertBooleanZero() {
        Object result = XSDTypeConverter.convert("boolean", "0");
        assertTrue(result instanceof Boolean);
        Boolean boolResult = (Boolean) result;
        assertFalse(boolResult.booleanValue());
    }

    // --- convert: integer ---

    @Test
    public void testConvertInteger() {
        Object result = XSDTypeConverter.convert("integer", "12345");
        assertTrue(result instanceof BigInteger);
        BigInteger bigResult = (BigInteger) result;
        assertEquals(new BigInteger("12345"), bigResult);
    }

    @Test
    public void testConvertIntegerNegative() {
        Object result = XSDTypeConverter.convert("integer", "-999");
        assertTrue(result instanceof BigInteger);
        BigInteger bigResult = (BigInteger) result;
        assertEquals(new BigInteger("-999"), bigResult);
    }

    // --- convert: decimal ---

    @Test
    public void testConvertDecimal() {
        Object result = XSDTypeConverter.convert("decimal", "3.14159");
        assertTrue(result instanceof BigDecimal);
        BigDecimal bigResult = (BigDecimal) result;
        assertEquals(new BigDecimal("3.14159"), bigResult);
    }

    // --- convert: float/double with special values ---

    @Test
    public void testConvertFloatInf() {
        Object result = XSDTypeConverter.convert("float", "INF");
        assertTrue(result instanceof Float);
        Float floatResult = (Float) result;
        assertTrue(Float.isInfinite(floatResult.floatValue()));
        assertTrue(floatResult.floatValue() > 0);
    }

    @Test
    public void testConvertFloatNegInf() {
        Object result = XSDTypeConverter.convert("float", "-INF");
        assertTrue(result instanceof Float);
        Float floatResult = (Float) result;
        assertTrue(Float.isInfinite(floatResult.floatValue()));
        assertTrue(floatResult.floatValue() < 0);
    }

    @Test
    public void testConvertFloatNaN() {
        Object result = XSDTypeConverter.convert("float", "NaN");
        assertTrue(result instanceof Float);
        Float floatResult = (Float) result;
        assertTrue(Float.isNaN(floatResult.floatValue()));
    }

    @Test
    public void testConvertDoubleInf() {
        Object result = XSDTypeConverter.convert("double", "INF");
        assertTrue(result instanceof Double);
        Double doubleResult = (Double) result;
        assertTrue(Double.isInfinite(doubleResult.doubleValue()));
        assertTrue(doubleResult.doubleValue() > 0);
    }

    @Test
    public void testConvertDoubleNegInf() {
        Object result = XSDTypeConverter.convert("double", "-INF");
        assertTrue(result instanceof Double);
        Double doubleResult = (Double) result;
        assertTrue(Double.isInfinite(doubleResult.doubleValue()));
        assertTrue(doubleResult.doubleValue() < 0);
    }

    @Test
    public void testConvertDoubleNaN() {
        Object result = XSDTypeConverter.convert("double", "NaN");
        assertTrue(result instanceof Double);
        Double doubleResult = (Double) result;
        assertTrue(Double.isNaN(doubleResult.doubleValue()));
    }

    @Test
    public void testConvertFloatNumeric() {
        Object result = XSDTypeConverter.convert("float", "1.5");
        assertTrue(result instanceof Float);
        Float floatResult = (Float) result;
        assertEquals(1.5f, floatResult.floatValue(), 0.0001f);
    }

    @Test
    public void testConvertDoubleNumeric() {
        Object result = XSDTypeConverter.convert("double", "2.718");
        assertTrue(result instanceof Double);
        Double doubleResult = (Double) result;
        assertEquals(2.718, doubleResult.doubleValue(), 0.0001);
    }

    // --- convert: date ---

    @Test
    public void testConvertDate() {
        Object result = XSDTypeConverter.convert("date", "2025-02-20");
        assertTrue(result instanceof LocalDate);
        LocalDate dateResult = (LocalDate) result;
        assertEquals(2025, dateResult.getYear());
        assertEquals(2, dateResult.getMonthValue());
        assertEquals(20, dateResult.getDayOfMonth());
    }

    // --- convert: dateTime ---

    @Test
    public void testConvertDateTimeLocal() {
        Object result = XSDTypeConverter.convert("dateTime", "2025-02-20T14:30:00");
        assertTrue(result instanceof LocalDateTime);
        LocalDateTime dtResult = (LocalDateTime) result;
        assertEquals(2025, dtResult.getYear());
        assertEquals(2, dtResult.getMonthValue());
        assertEquals(20, dtResult.getDayOfMonth());
        assertEquals(14, dtResult.getHour());
        assertEquals(30, dtResult.getMinute());
    }

    @Test
    public void testConvertDateTimeWithTimezone() {
        Object result = XSDTypeConverter.convert("dateTime", "2025-02-20T14:30:00+01:00");
        assertTrue(result instanceof OffsetDateTime);
        OffsetDateTime dtResult = (OffsetDateTime) result;
        assertEquals(2025, dtResult.getYear());
        assertEquals(2, dtResult.getMonthValue());
        assertEquals(20, dtResult.getDayOfMonth());
    }

    // --- convert: null ---

    @Test
    public void testConvertNull() {
        Object result = XSDTypeConverter.convert("string", null);
        assertNull(result);
    }

    // --- normalizeReplace ---

    @Test
    public void testNormalizeReplaceTabsToSpaces() {
        String value = "a\tb\tc";
        String result = XSDTypeConverter.normalizeReplace(value);
        assertEquals("a b c", result);
    }

    @Test
    public void testNormalizeReplaceNewlinesToSpaces() {
        String value = "a\nb\nc";
        String result = XSDTypeConverter.normalizeReplace(value);
        assertEquals("a b c", result);
    }

    @Test
    public void testNormalizeReplaceCarriageReturnToSpaces() {
        String value = "a\rb\rc";
        String result = XSDTypeConverter.normalizeReplace(value);
        assertEquals("a b c", result);
    }

    @Test
    public void testNormalizeReplaceMixedWhitespace() {
        String value = "hello\tworld\nfoo\rbar";
        String result = XSDTypeConverter.normalizeReplace(value);
        assertEquals("hello world foo bar", result);
    }

    @Test
    public void testNormalizeReplacePreservesSpaces() {
        String value = "a  b   c";
        String result = XSDTypeConverter.normalizeReplace(value);
        assertEquals("a  b   c", result);
    }

    // --- normalizeCollapse ---

    @Test
    public void testNormalizeCollapseMultipleSpaces() {
        String value = "a   b    c";
        String result = XSDTypeConverter.normalizeCollapse(value);
        assertEquals("a b c", result);
    }

    @Test
    public void testNormalizeCollapseTrim() {
        String value = "  hello world  ";
        String result = XSDTypeConverter.normalizeCollapse(value);
        assertEquals("hello world", result);
    }

    @Test
    public void testNormalizeCollapseTabsAndNewlines() {
        String value = "  a\t\n\r  b  ";
        String result = XSDTypeConverter.normalizeCollapse(value);
        assertEquals("a b", result);
    }

    @Test
    public void testNormalizeCollapseEmptyString() {
        String value = "";
        String result = XSDTypeConverter.normalizeCollapse(value);
        assertEquals("", result);
    }

    @Test
    public void testNormalizeCollapseWhitespaceOnly() {
        String value = "   \t\n\r   ";
        String result = XSDTypeConverter.normalizeCollapse(value);
        assertEquals("", result);
    }
}

/*
 * XPathAtomicValue.java
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

package org.bluezoo.gonzalez.transform.xpath.type;

/**
 * XPath 2.0/3.1 untyped atomic value.
 *
 * <p>An untyped atomic value is a string that has no associated schema type.
 * Without XML Schema validation, all atomic values from documents are untyped.
 * Type determination happens dynamically based on context:
 *
 * <ul>
 *   <li>In arithmetic operations, untyped values are cast to xs:double</li>
 *   <li>In string operations, they remain as strings</li>
 *   <li>In comparisons, both operands are promoted to a common type</li>
 * </ul>
 *
 * <p>This class provides the schema-less type handling required for XSLT 3.0
 * streaming without a schema processor.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class XPathAtomicValue implements XPathValue, Comparable<XPathAtomicValue> {

    /** Empty atomic value. */
    public static final XPathAtomicValue EMPTY = new XPathAtomicValue("");

    private final String lexicalValue;
    private Double numericValue;  // Cached numeric conversion
    private Boolean isNumeric;    // Cached numeric check

    /**
     * Creates a new untyped atomic value.
     *
     * @param lexicalValue the string representation
     */
    public XPathAtomicValue(String lexicalValue) {
        this.lexicalValue = lexicalValue != null ? lexicalValue : "";
    }

    /**
     * Creates an atomic value from a string.
     *
     * @param value the string value
     * @return the atomic value
     */
    public static XPathAtomicValue of(String value) {
        if (value == null || value.isEmpty()) {
            return EMPTY;
        }
        return new XPathAtomicValue(value);
    }

    /**
     * Creates an atomic value from a number.
     *
     * @param value the numeric value
     * @return the atomic value
     */
    public static XPathAtomicValue of(double value) {
        XPathAtomicValue atomic = new XPathAtomicValue(formatNumber(value));
        atomic.numericValue = value;
        atomic.isNumeric = true;
        return atomic;
    }

    /**
     * Creates an atomic value from a long integer.
     *
     * @param value the integer value
     * @return the atomic value
     */
    public static XPathAtomicValue of(long value) {
        XPathAtomicValue atomic = new XPathAtomicValue(Long.toString(value));
        atomic.numericValue = (double) value;
        atomic.isNumeric = true;
        return atomic;
    }

    /**
     * Creates an atomic value from a boolean.
     *
     * @param value the boolean value
     * @return the atomic value
     */
    public static XPathAtomicValue of(boolean value) {
        return new XPathAtomicValue(value ? "true" : "false");
    }

    @Override
    public Type getType() {
        return Type.ATOMIC;
    }

    @Override
    public String asString() {
        return lexicalValue;
    }

    @Override
    public double asNumber() {
        if (numericValue != null) {
            return numericValue;
        }
        numericValue = parseNumber(lexicalValue);
        return numericValue;
    }

    @Override
    public boolean asBoolean() {
        // XPath 2.0: Non-empty string is truthy
        return !lexicalValue.isEmpty();
    }

    @Override
    public XPathNodeSet asNodeSet() {
        // Atomic values are not node-sets
        return null;
    }

    /**
     * Returns the lexical (string) value.
     *
     * @return the lexical value
     */
    public String getLexicalValue() {
        return lexicalValue;
    }

    /**
     * Returns true if this value can be interpreted as a number.
     *
     * @return true if numeric
     */
    public boolean isNumericValue() {
        if (isNumeric != null) {
            return isNumeric;
        }
        double num = asNumber();
        isNumeric = !Double.isNaN(num);
        return isNumeric;
    }

    /**
     * Converts this atomic value to an XPathNumber.
     *
     * @return the number value
     */
    public XPathNumber toNumber() {
        return XPathNumber.of(asNumber());
    }

    /**
     * Converts this atomic value to an XPathString.
     *
     * @return the string value
     */
    public XPathString toXPathString() {
        return XPathString.of(lexicalValue);
    }

    /**
     * Compares this atomic value to another.
     * Uses numeric comparison if both values are numeric, otherwise string comparison.
     *
     * @param other the other atomic value
     * @return comparison result
     */
    @Override
    public int compareTo(XPathAtomicValue other) {
        if (this.isNumericValue() && other.isNumericValue()) {
            return Double.compare(this.asNumber(), other.asNumber());
        }
        return this.lexicalValue.compareTo(other.lexicalValue);
    }

    /**
     * Compares this atomic value to any XPathValue.
     * Uses appropriate comparison based on types.
     *
     * @param other the other value
     * @return comparison result
     */
    public int compareToValue(XPathValue other) {
        if (other instanceof XPathAtomicValue) {
            return compareTo((XPathAtomicValue) other);
        }
        if (other instanceof XPathNumber) {
            if (isNumericValue()) {
                return Double.compare(asNumber(), other.asNumber());
            }
        }
        // Default to string comparison
        return lexicalValue.compareTo(other.asString());
    }

    /**
     * Checks equality with another atomic value using XPath 2.0 semantics.
     *
     * @param other the other value
     * @return true if equal
     */
    public boolean valueEquals(XPathAtomicValue other) {
        if (this.isNumericValue() && other.isNumericValue()) {
            return Double.compare(this.asNumber(), other.asNumber()) == 0;
        }
        return this.lexicalValue.equals(other.lexicalValue);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof XPathAtomicValue)) {
            return false;
        }
        return lexicalValue.equals(((XPathAtomicValue) obj).lexicalValue);
    }

    @Override
    public int hashCode() {
        return lexicalValue.hashCode();
    }

    @Override
    public String toString() {
        return "atomic(" + lexicalValue + ")";
    }

    /**
     * Parses a string as a number according to XPath rules.
     */
    private static double parseNumber(String str) {
        if (str == null || str.isEmpty()) {
            return Double.NaN;
        }
        str = str.trim();
        if (str.isEmpty()) {
            return Double.NaN;
        }
        try {
            return Double.parseDouble(str);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    /**
     * Formats a number as a string according to XPath rules.
     */
    private static String formatNumber(double value) {
        if (Double.isNaN(value)) {
            return "NaN";
        }
        if (Double.isInfinite(value)) {
            return value > 0 ? "Infinity" : "-Infinity";
        }
        if (value == Math.floor(value) && !Double.isInfinite(value)) {
            return Long.toString((long) value);
        }
        return Double.toString(value);
    }

}

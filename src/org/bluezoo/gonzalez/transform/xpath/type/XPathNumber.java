/*
 * XPathNumber.java
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

import java.math.BigDecimal;

/**
 * XPath number value.
 *
 * <p>Numbers in XPath are IEEE 754 double-precision 64-bit floating point values.
 * This includes special values NaN (not-a-number), positive infinity, and
 * negative infinity.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class XPathNumber implements XPathValue {

    /** Zero singleton. */
    public static final XPathNumber ZERO = new XPathNumber(0.0);

    /** One singleton. */
    public static final XPathNumber ONE = new XPathNumber(1.0);

    /** NaN (not-a-number) singleton. */
    public static final XPathNumber NaN = new XPathNumber(Double.NaN);

    /** Positive infinity singleton. */
    public static final XPathNumber POSITIVE_INFINITY = new XPathNumber(Double.POSITIVE_INFINITY);

    /** Negative infinity singleton. */
    public static final XPathNumber NEGATIVE_INFINITY = new XPathNumber(Double.NEGATIVE_INFINITY);

    private final double value;

    /**
     * Creates a new XPath number value.
     *
     * @param value the numeric value (may be NaN or infinite)
     */
    public XPathNumber(double value) {
        this.value = value;
    }

    /**
     * Returns an XPath number for the given double value.
     * Returns singletons for common values (0, 1, NaN, infinities).
     *
     * @param value the numeric value
     * @return the XPath number value
     */
    public static XPathNumber of(double value) {
        if (Double.isNaN(value)) {
            return NaN;
        }
        if (value == Double.POSITIVE_INFINITY) {
            return POSITIVE_INFINITY;
        }
        if (value == Double.NEGATIVE_INFINITY) {
            return NEGATIVE_INFINITY;
        }
        if (value == 0.0) {
            return ZERO;
        }
        if (value == 1.0) {
            return ONE;
        }
        return new XPathNumber(value);
    }

    /**
     * Returns the XPath type of this value.
     *
     * @return {@link Type#NUMBER}
     */
    @Override
    public Type getType() {
        return Type.NUMBER;
    }

    @Override
    public String asString() {
        // XPath 1.0 Section 4.2: number to string conversion
        if (Double.isNaN(value)) {
            return "NaN";
        }
        if (value == Double.POSITIVE_INFINITY) {
            return "Infinity";
        }
        if (value == Double.NEGATIVE_INFINITY) {
            return "-Infinity";
        }
        if (value == 0.0) {
            // Handle negative zero
            return "0";
        }

        // XPath requires that if the number is an integer, no decimal point is shown
        if (value == Math.floor(value) && !Double.isInfinite(value)) {
            long longValue = (long) value;
            // Check that conversion is exact
            if (longValue == value) {
                return Long.toString(longValue);
            }
        }

        // Standard decimal representation
        // XPath spec says to use "canonical lexical representation" which is
        // essentially what Double.toString provides, but without scientific notation
        // Convert scientific notation to plain decimal format per XPath spec
        String str = Double.toString(value);

        // Handle scientific notation (e.g., 4.0E-4 -> 0.0004)
        if (str.contains("E") || str.contains("e")) {
            // Use BigDecimal with String constructor to preserve original precision
            // (valueOf would use Double.toString which already has scientific notation)
            BigDecimal bd = new BigDecimal(str);
            str = bd.toPlainString();
        }

        // Remove unnecessary trailing zeros after decimal point
        // but keep at least one digit after the decimal if present
        if (str.contains(".")) {
            while (str.endsWith("0") && !str.endsWith(".0")) {
                str = str.substring(0, str.length() - 1);
            }
        }

        return str;
    }

    /**
     * Returns this number value unchanged.
     *
     * @return the numeric value
     */
    @Override
    public double asNumber() {
        return value;
    }

    /**
     * Converts this number to a boolean according to XPath 1.0 Section 4.3.
     *
     * <p>A number is true if and only if it is neither positive or negative zero nor NaN.
     *
     * @return true if the number is non-zero and not NaN, false otherwise
     */
    @Override
    public boolean asBoolean() {
        // XPath 1.0 Section 4.3: "a number is true if and only if it is
        // neither positive or negative zero nor NaN"
        return value != 0.0 && !Double.isNaN(value);
    }

    /**
     * Numbers cannot be converted to node-sets.
     *
     * @return null (numbers are not node-sets)
     */
    @Override
    public XPathNodeSet asNodeSet() {
        return null;
    }

    /**
     * Returns the underlying double value.
     *
     * @return the numeric value
     */
    public double getValue() {
        return value;
    }

    /**
     * Returns true if this number is NaN.
     *
     * @return true if NaN
     */
    public boolean isNaN() {
        return Double.isNaN(value);
    }

    /**
     * Returns true if this number is infinite (positive or negative).
     *
     * @return true if infinite
     */
    public boolean isInfinite() {
        return Double.isInfinite(value);
    }

    /**
     * Returns true if this number represents an integer value.
     *
     * @return true if the value has no fractional part
     */
    public boolean isInteger() {
        return value == Math.floor(value) && !Double.isInfinite(value) && !Double.isNaN(value);
    }

    /**
     * Checks if this number can be considered an instance of the given XSD numeric type.
     * 
     * <p>This implements XPath 2.0+ type promotion rules:
     * <ul>
     *   <li>xs:integer can be promoted to xs:decimal, xs:float, xs:double</li>
     *   <li>xs:decimal can be promoted to xs:float, xs:double</li>
     *   <li>xs:float can be promoted to xs:double</li>
     * </ul>
     *
     * @param xsTypeName the local name of the XSD type (e.g., "double", "integer", "decimal")
     * @return true if this number matches or can be promoted to that type
     */
    public boolean isInstanceOfNumericType(String xsTypeName) {
        if (xsTypeName == null) {
            return false;
        }
        
        // All numbers are xs:double (XPath 1.0 default)
        // In XPath 2.0+, we allow numbers to match any numeric type via promotion
        switch (xsTypeName) {
            case "double":
            case "float":
            case "decimal":
                // All XPathNumber values can be considered xs:double, xs:float, or xs:decimal
                return true;
                
            case "integer":
            case "int":
            case "long":
            case "short":
            case "byte":
            case "nonNegativeInteger":
            case "nonPositiveInteger":
            case "positiveInteger":
            case "negativeInteger":
            case "unsignedInt":
            case "unsignedLong":
            case "unsignedShort":
            case "unsignedByte":
                // Integer types require no fractional part
                return isInteger();
                
            default:
                return false;
        }
    }

    /**
     * Compares this number with another object for equality.
     *
     * <p>Two numbers are equal if they have the same value. NaN values are
     * considered equal to each other (unlike IEEE 754 where NaN != NaN).
     *
     * @param obj the object to compare
     * @return true if the objects are equal
     */
    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof XPathNumber)) {
            return false;
        }
        double other = ((XPathNumber) obj).value;
        // Handle NaN specially (NaN != NaN in IEEE 754)
        if (Double.isNaN(value) && Double.isNaN(other)) {
            return true;
        }
        return Double.compare(value, other) == 0;
    }

    /**
     * Returns a hash code for this number.
     *
     * @return the hash code
     */
    @Override
    public int hashCode() {
        return Double.hashCode(value);
    }

    /**
     * Returns a string representation of this XPath number.
     *
     * @return a string in the format "XPathNumber[value]"
     */
    @Override
    public String toString() {
        return "XPathNumber[" + asString() + "]";
    }

}

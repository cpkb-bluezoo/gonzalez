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
import java.math.BigInteger;

/**
 * XPath number value.
 *
 * <p>Numbers in XPath are IEEE 754 double-precision 64-bit floating point values.
 * This includes special values NaN (not-a-number), positive infinity, and
 * negative infinity.
 *
 * <p>For exact precision, numbers may also carry an {@code exactValue} field
 * of type {@link Number}: {@link Long} for exact integers within long range,
 * {@link BigInteger} for exact integers outside long range, or
 * {@link BigDecimal} for exact decimal values.
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
    private final boolean isFloat;
    private final boolean isExplicitDouble;
    private final Number exactValue;

    /**
     * Creates a new XPath number value.
     *
     * @param value the numeric value (may be NaN or infinite)
     */
    public XPathNumber(double value) {
        this.value = value;
        this.isFloat = false;
        this.isExplicitDouble = false;
        this.exactValue = null;
    }

    /**
     * Creates a new XPath number value with explicit float flag.
     * When isFloat is true, serialization uses xs:float canonical form.
     *
     * @param value the numeric value
     * @param isFloat true if this represents an xs:float value
     */
    public XPathNumber(double value, boolean isFloat) {
        this.value = value;
        this.isFloat = isFloat;
        this.isExplicitDouble = false;
        this.exactValue = null;
    }

    /**
     * Creates a new XPath number with explicit type flags.
     *
     * @param value the numeric value
     * @param isFloat true if xs:float
     * @param isExplicitDouble true if explicitly constructed via xs:double()
     */
    public XPathNumber(double value, boolean isFloat, boolean isExplicitDouble) {
        this.value = value;
        this.isFloat = isFloat;
        this.isExplicitDouble = isExplicitDouble;
        this.exactValue = null;
    }

    /**
     * Creates an XPath number backed by a BigDecimal for xs:decimal precision.
     *
     * @param decimal the decimal value
     */
    public XPathNumber(BigDecimal decimal) {
        this.value = decimal.doubleValue();
        this.isFloat = false;
        this.isExplicitDouble = false;
        this.exactValue = decimal;
    }

    /**
     * Creates an XPath number backed by a BigInteger for xs:integer precision
     * beyond the range of long.
     *
     * @param bigInt the big integer value
     */
    public XPathNumber(BigInteger bigInt) {
        this.value = bigInt.doubleValue();
        this.isFloat = false;
        this.isExplicitDouble = false;
        this.exactValue = bigInt;
    }

    /**
     * Internal constructor with all fields.
     */
    private XPathNumber(double value, boolean isFloat, boolean isExplicitDouble,
                        Number exactValue) {
        this.value = value;
        this.isFloat = isFloat;
        this.isExplicitDouble = isExplicitDouble;
        this.exactValue = exactValue;
    }

    /**
     * Returns an exact xs:integer XPathNumber for the given long value.
     *
     * @param v the integer value
     * @return an XPathNumber with exact integer representation
     */
    public static XPathNumber ofInteger(long v) {
        return new XPathNumber(v, false, false, Long.valueOf(v));
    }

    /**
     * Returns an exact xs:integer XPathNumber for the given BigInteger value.
     * Demotes to Long if the value fits in long range.
     *
     * @param v the big integer value
     * @return an XPathNumber with exact integer representation
     */
    public static XPathNumber ofInteger(BigInteger v) {
        if (v.bitLength() < 64) {
            long lv = v.longValue();
            return new XPathNumber(lv, false, false, Long.valueOf(lv));
        }
        return new XPathNumber(v);
    }

    /**
     * Returns the BigDecimal representation, or null if not a decimal type.
     */
    public BigDecimal getDecimalValue() {
        if (exactValue instanceof BigDecimal) {
            return (BigDecimal) exactValue;
        }
        return null;
    }

    /**
     * Returns true if this number has exact decimal representation.
     */
    public boolean isDecimal() {
        return exactValue instanceof BigDecimal;
    }

    /**
     * Returns true if this number is backed by an exact integer value
     * (Long or BigInteger).
     */
    public boolean isExactInteger() {
        return exactValue instanceof Long || exactValue instanceof BigInteger;
    }

    /**
     * Returns this number's exact value as a BigInteger.
     * Only valid when {@link #isExactInteger()} returns true.
     *
     * @return the exact integer value, or null if not an exact integer
     */
    public BigInteger toBigInteger() {
        if (exactValue instanceof BigInteger) {
            return (BigInteger) exactValue;
        }
        if (exactValue instanceof Long) {
            return BigInteger.valueOf(((Long) exactValue).longValue());
        }
        return null;
    }

    /**
     * Returns this number's exact value as a Long, or null if it is not
     * an exact integer stored as Long.
     */
    public Long toLong() {
        if (exactValue instanceof Long) {
            return (Long) exactValue;
        }
        return null;
    }

    /**
     * Returns true if this number represents an xs:float value.
     */
    public boolean isFloat() {
        return isFloat;
    }

    /**
     * Returns true if this number was explicitly constructed as xs:double.
     */
    public boolean isExplicitDouble() {
        return isExplicitDouble;
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
            if (Double.doubleToRawLongBits(value) == Long.MIN_VALUE) {
                return new XPathNumber(-0.0);
            }
            return ZERO;
        }
        if (value == 1.0) {
            return ONE;
        }
        return new XPathNumber(value);
    }

    /**
     * Returns an XPath number for the given double value, marked as explicit double.
     * Used when the result of arithmetic on a double-typed operand should retain
     * the double type to prevent misclassification as integer in subsequent operations.
     *
     * @param value the numeric value
     * @return the XPath number value, marked as explicit double
     */
    public static XPathNumber ofExplicitDouble(double value) {
        if (Double.isNaN(value)) {
            return new XPathNumber(Double.NaN, false, true);
        }
        if (value == Double.POSITIVE_INFINITY) {
            return new XPathNumber(Double.POSITIVE_INFINITY, false, true);
        }
        if (value == Double.NEGATIVE_INFINITY) {
            return new XPathNumber(Double.NEGATIVE_INFINITY, false, true);
        }
        return new XPathNumber(value, false, true);
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
        if (exactValue instanceof Long) {
            return Long.toString(((Long) exactValue).longValue());
        }
        if (exactValue instanceof BigInteger) {
            return exactValue.toString();
        }
        if (exactValue instanceof BigDecimal) {
            return formatDecimal((BigDecimal) exactValue);
        }
        if (Double.isNaN(value)) {
            return "NaN";
        }
        if (value == Double.POSITIVE_INFINITY) {
            return "INF";
        }
        if (value == Double.NEGATIVE_INFINITY) {
            return "-INF";
        }
        if (value == 0.0) {
            if (Double.doubleToRawLongBits(value) == Long.MIN_VALUE) {
                return "-0";
            }
            return "0";
        }

        // xs:float uses its own canonical lexical form (scientific notation)
        if (isFloat) {
            String s = Float.toString((float) value);
            if (s.endsWith(".0")) {
                return s.substring(0, s.length() - 2);
            }
            return s;
        }

        // xs:double (explicit) uses XPath 2.0 canonical form:
        // Scientific notation when exponent >= 7 or <= -7, otherwise decimal
        if (isExplicitDouble) {
            return formatDoubleCanonical(value);
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
     * Formats a BigDecimal as the XPath canonical decimal representation.
     */
    private static String formatDecimal(BigDecimal bd) {
        bd = bd.stripTrailingZeros();
        if (bd.scale() <= 0) {
            return bd.toBigInteger().toString();
        }
        return bd.toPlainString();
    }

    /**
     * Formats a double value as XPath 2.0 xs:double canonical lexical representation.
     *
     * <p>Per the XPath 2.0 F&amp;O spec (casting xs:double to xs:string):
     * if the normalized exponent E satisfies -6 &lt;= E &lt; 6 (i.e., |v| in [1E-6, 1E6)),
     * use decimal notation; otherwise use scientific notation.
     */
    private static String formatDoubleCanonical(double v) {
        if (v == 0.0) {
            if (Double.doubleToRawLongBits(v) == Long.MIN_VALUE) {
                return "-0";
            }
            return "0";
        }

        double absV = Math.abs(v);
        boolean useScientific = absV >= 1E6 || absV < 1E-6;

        if (!useScientific) {
            // Decimal notation for |v| in [1E-6, 1E6)
            if (v == Math.floor(v) && !Double.isInfinite(v)) {
                long lv = (long) v;
                if (lv == v) {
                    return Long.toString(lv);
                }
            }
            String s = Double.toString(v);
            if (s.contains("E") || s.contains("e")) {
                BigDecimal bd = new BigDecimal(s);
                s = bd.toPlainString();
            }
            if (s.contains(".")) {
                while (s.endsWith("0") && !s.endsWith(".0")) {
                    s = s.substring(0, s.length() - 1);
                }
                if (s.endsWith(".0")) {
                    s = s.substring(0, s.length() - 2);
                }
            }
            return s;
        }

        // Scientific notation for |v| >= 1E6 or |v| < 1E-6
        String s = Double.toString(v);
        int ePos = s.indexOf('E');
        if (ePos < 0) {
            ePos = s.indexOf('e');
        }

        if (ePos >= 0) {
            // Java already produced scientific notation — clean up
            String mantissa = s.substring(0, ePos);
            String expPart = s.substring(ePos + 1);
            if (mantissa.contains(".")) {
                while (mantissa.endsWith("0") && !mantissa.endsWith(".0")) {
                    mantissa = mantissa.substring(0, mantissa.length() - 1);
                }
            }
            int exp = Integer.parseInt(expPart);
            return mantissa + "E" + exp;
        }

        // Java did not produce scientific notation (values in [1E6, 1E7) range)
        return convertToScientific(s);
    }

    /**
     * Converts a plain decimal string (e.g., "1234567.0") to XPath scientific notation
     * (e.g., "1.234567E6"). Used for values Java doesn't represent in scientific form.
     */
    private static String convertToScientific(String s) {
        boolean negative = s.startsWith("-");
        String plain = negative ? s.substring(1) : s;

        if (plain.endsWith(".0")) {
            plain = plain.substring(0, plain.length() - 2);
        } else if (plain.contains(".")) {
            while (plain.endsWith("0")) {
                plain = plain.substring(0, plain.length() - 1);
            }
            if (plain.endsWith(".")) {
                plain = plain.substring(0, plain.length() - 1);
            }
        }

        int dotPos = plain.indexOf('.');
        String intPart;
        String fracPart;
        if (dotPos >= 0) {
            intPart = plain.substring(0, dotPos);
            fracPart = plain.substring(dotPos + 1);
        } else {
            intPart = plain;
            fracPart = "";
        }

        int exp = intPart.length() - 1;
        String allDigits = intPart + fracPart;
        while (allDigits.length() > 1 && allDigits.endsWith("0")) {
            allDigits = allDigits.substring(0, allDigits.length() - 1);
        }

        String mantissa;
        if (allDigits.length() == 1) {
            mantissa = allDigits + ".0";
        } else {
            mantissa = allDigits.substring(0, 1) + "." + allDigits.substring(1);
        }

        if (negative) {
            return "-" + mantissa + "E" + exp;
        }
        return mantissa + "E" + exp;
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
        if (isExactInteger()) {
            return true;
        }
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
        
        // XPath 2.0+ numeric type promotion rules:
        // - integer → decimal → float → double (promotion allowed)
        // - double → float is NOT allowed (demotion)
        switch (xsTypeName) {
            case "double":
                return true;
                
            case "float":
                // Explicit double values cannot demote to float
                return !isExplicitDouble;
                
            case "decimal":
                // Decimal matches: all finite numeric values
                return !Double.isInfinite(value) && !Double.isNaN(value);
                
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

/*
 * XPathString.java
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
 * XPath string value.
 *
 * <p>A string in XPath is a sequence of Unicode characters.
 * String values are immutable.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class XPathString implements XPathValue {

    /** Empty string singleton. */
    public static final XPathString EMPTY = new XPathString("");

    private final String value;

    /**
     * Creates a new XPath string value.
     *
     * @param value the string value (must not be null)
     * @throws NullPointerException if value is null
     */
    public XPathString(String value) {
        if (value == null) {
            throw new NullPointerException("String value must not be null");
        }
        this.value = value;
    }

    /**
     * Returns an XPath string for the given Java string.
     * Returns the empty string singleton for empty strings.
     *
     * @param value the string value
     * @return the XPath string value
     */
    public static XPathString of(String value) {
        if (value == null || value.isEmpty()) {
            return EMPTY;
        }
        return new XPathString(value);
    }

    /**
     * Returns the XPath type of this value.
     *
     * @return {@link Type#STRING}
     */
    @Override
    public Type getType() {
        return Type.STRING;
    }

    @Override
    public String asString() {
        return value;
    }

    @Override
    public double asNumber() {
        // XPath 1.0 Section 4.4: string to number conversion
        // "a string that consists of optional whitespace followed by an optional
        // minus sign followed by a Number followed by whitespace is converted to
        // the IEEE 754 number that is nearest ... to the mathematical value"
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return Double.NaN;
        }
        try {
            return Double.parseDouble(trimmed);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    /**
     * Converts this string to a boolean according to XPath 1.0 Section 4.3.
     *
     * <p>A string is true if and only if its length is non-zero.
     *
     * @return true if the string is non-empty, false otherwise
     */
    @Override
    public boolean asBoolean() {
        // XPath 1.0 Section 4.3: "a string is true if and only if its length is non-zero"
        return !value.isEmpty();
    }

    /**
     * Strings cannot be converted to node-sets.
     *
     * @return null (strings are not node-sets)
     */
    @Override
    public XPathNodeSet asNodeSet() {
        return null;
    }

    /**
     * Returns the underlying Java string value.
     *
     * @return the string value
     */
    public String getValue() {
        return value;
    }

    /**
     * Compares this string with another object for equality.
     *
     * @param obj the object to compare
     * @return true if the objects are equal
     */
    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof XPathString)) {
            return false;
        }
        return value.equals(((XPathString) obj).value);
    }

    /**
     * Returns a hash code for this string.
     *
     * @return the hash code
     */
    @Override
    public int hashCode() {
        return value.hashCode();
    }

    /**
     * Returns a string representation of this XPath string.
     *
     * @return a string in the format "XPathString[\"value\"]"
     */
    @Override
    public String toString() {
        return "XPathString[\"" + value + "\"]";
    }

}

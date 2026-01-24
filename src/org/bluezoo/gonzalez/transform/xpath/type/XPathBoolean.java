/*
 * XPathBoolean.java
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
 * XPath boolean value.
 *
 * <p>A boolean in XPath can have one of two values: true or false.
 * This class uses singletons for both values.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class XPathBoolean implements XPathValue {

    /** The true singleton. */
    public static final XPathBoolean TRUE = new XPathBoolean(true);

    /** The false singleton. */
    public static final XPathBoolean FALSE = new XPathBoolean(false);

    private final boolean value;

    /**
     * Private constructor - use {@link #of(boolean)} or the singletons.
     */
    private XPathBoolean(boolean value) {
        this.value = value;
    }

    /**
     * Returns the XPath boolean for the given Java boolean.
     *
     * @param value the boolean value
     * @return TRUE or FALSE singleton
     */
    public static XPathBoolean of(boolean value) {
        return value ? TRUE : FALSE;
    }

    /**
     * Returns the XPath boolean for the given XPath value.
     * Applies the boolean conversion rules from XPath 1.0 Section 4.3.
     *
     * @param value the value to convert
     * @return the boolean representation
     */
    public static XPathBoolean of(XPathValue value) {
        return of(value.asBoolean());
    }

    @Override
    public Type getType() {
        return Type.BOOLEAN;
    }

    @Override
    public String asString() {
        // XPath 1.0 Section 4.2: "The boolean false value is converted to
        // the string false. The boolean true value is converted to the string true."
        return value ? "true" : "false";
    }

    @Override
    public double asNumber() {
        // XPath 1.0 Section 4.4: "boolean true is converted to 1;
        // boolean false is converted to 0"
        return value ? 1.0 : 0.0;
    }

    @Override
    public boolean asBoolean() {
        return value;
    }

    @Override
    public XPathNodeSet asNodeSet() {
        return null;
    }

    /**
     * Returns the underlying boolean value.
     *
     * @return the boolean value
     */
    public boolean getValue() {
        return value;
    }

    /**
     * Returns the logical negation of this boolean.
     *
     * @return FALSE if this is TRUE, TRUE if this is FALSE
     */
    public XPathBoolean not() {
        return value ? FALSE : TRUE;
    }

    /**
     * Returns the logical AND of this boolean and another.
     *
     * @param other the other boolean
     * @return the logical AND result
     */
    public XPathBoolean and(XPathBoolean other) {
        return of(this.value && other.value);
    }

    /**
     * Returns the logical OR of this boolean and another.
     *
     * @param other the other boolean
     * @return the logical OR result
     */
    public XPathBoolean or(XPathBoolean other) {
        return of(this.value || other.value);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof XPathBoolean)) {
            return false;
        }
        return value == ((XPathBoolean) obj).value;
    }

    @Override
    public int hashCode() {
        return Boolean.hashCode(value);
    }

    @Override
    public String toString() {
        return "XPathBoolean[" + value + "]";
    }

}

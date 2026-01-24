/*
 * Literal.java
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

package org.bluezoo.gonzalez.transform.xpath.expr;

import org.bluezoo.gonzalez.transform.xpath.XPathContext;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNumber;
import org.bluezoo.gonzalez.transform.xpath.type.XPathString;
import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;

/**
 * A literal value expression (string or number).
 *
 * <p>Literals are constant values that always evaluate to the same result
 * regardless of context.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class Literal implements Expr {

    private final XPathValue value;

    /**
     * Creates a literal expression with the given value.
     *
     * @param value the literal value
     */
    public Literal(XPathValue value) {
        if (value == null) {
            throw new NullPointerException("Literal value cannot be null");
        }
        this.value = value;
    }

    /**
     * Creates a string literal.
     *
     * @param value the string value
     * @return the literal expression
     */
    public static Literal string(String value) {
        return new Literal(XPathString.of(value));
    }

    /**
     * Creates a number literal.
     *
     * @param value the numeric value
     * @return the literal expression
     */
    public static Literal number(double value) {
        return new Literal(XPathNumber.of(value));
    }

    /**
     * Creates a number literal from a string representation.
     *
     * @param value the number as a string
     * @return the literal expression
     */
    public static Literal number(String value) {
        try {
            return new Literal(XPathNumber.of(Double.parseDouble(value)));
        } catch (NumberFormatException e) {
            return new Literal(XPathNumber.NaN);
        }
    }

    @Override
    public XPathValue evaluate(XPathContext context) {
        return value;
    }

    /**
     * Returns the literal value.
     *
     * @return the value
     */
    public XPathValue getValue() {
        return value;
    }

    @Override
    public String toString() {
        if (value instanceof XPathString) {
            // Quote the string
            String s = ((XPathString) value).getValue();
            if (s.contains("'")) {
                return "\"" + s + "\"";
            }
            return "'" + s + "'";
        }
        return value.asString();
    }

}

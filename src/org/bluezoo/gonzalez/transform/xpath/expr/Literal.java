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

import java.math.BigDecimal;
import java.math.BigInteger;

import org.bluezoo.gonzalez.transform.xpath.StaticTypeContext;
import org.bluezoo.gonzalez.transform.xpath.XPathContext;
import org.bluezoo.gonzalez.transform.xpath.type.SequenceType;
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
     * Integer literals are stored with exact precision (Long or BigInteger).
     * Decimal literals are stored as BigDecimal. Literals with exponents
     * are stored as explicit doubles.
     *
     * @param value the number as a string
     * @return the literal expression
     */
    public static Literal number(String value) {
        try {
            boolean hasExponent = value.indexOf('e') >= 0 || value.indexOf('E') >= 0;
            boolean hasDot = value.indexOf('.') >= 0;

            if (hasExponent) {
                double d = Double.parseDouble(value);
                return new Literal(new XPathNumber(d, false, true));
            }

            if (!hasDot) {
                try {
                    long lv = Long.parseLong(value);
                    return new Literal(XPathNumber.ofInteger(lv));
                } catch (NumberFormatException e) {
                    BigInteger bi = new BigInteger(value);
                    return new Literal(XPathNumber.ofInteger(bi));
                }
            }

            BigDecimal bd = new BigDecimal(value);
            return new Literal(new XPathNumber(bd));
        } catch (NumberFormatException e) {
            return new Literal(XPathNumber.NaN);
        }
    }

    @Override
    public XPathValue evaluate(XPathContext context) {
        // XPath 2.0 Section 3.1.1: In backwards compatible mode, a numeric
        // literal is treated as if cast to xs:double. We use of() rather than
        // ofExplicitDouble() so that string conversion follows XPath 1.0 rules.
        if (value instanceof XPathNumber) {
            double ver = context.getXsltVersion();
            if (ver < 2.0) {
                XPathNumber num = (XPathNumber) value;
                if (num.isExactInteger() || num.isDecimal()) {
                    return XPathNumber.of(num.asNumber());
                }
            }
        }
        return value;
    }

    @Override
    public SequenceType getStaticType() {
        if (value instanceof XPathNumber) {
            XPathNumber num = (XPathNumber) value;
            if (num.isExactInteger()) {
                return SequenceType.INTEGER;
            }
            if (num.isDecimal()) {
                return SequenceType.DECIMAL;
            }
            return SequenceType.DOUBLE;
        }
        if (value instanceof XPathString) {
            return SequenceType.STRING;
        }
        return null;
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

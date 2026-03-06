/*
 * UnaryExpr.java
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

import java.math.BigInteger;

import org.bluezoo.gonzalez.transform.xpath.XPathContext;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNumber;
import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;

/**
 * A unary negation expression (-expr).
 *
 * <p>XPath only has one unary operator: negation. The operand is converted
 * to a number and negated.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class UnaryExpr implements Expr {

    private final Expr operand;
    private final int negationCount;

    /**
     * Creates a unary negation expression.
     *
     * @param operand the operand
     */
    public UnaryExpr(Expr operand) {
        this(operand, 1);
    }

    /**
     * Creates a unary expression with multiple negations.
     * Multiple negations (e.g., --x) can be collapsed.
     *
     * @param operand the operand
     * @param negationCount the number of negations
     */
    public UnaryExpr(Expr operand, int negationCount) {
        if (operand == null) {
            throw new NullPointerException("Operand cannot be null");
        }
        this.operand = operand;
        this.negationCount = negationCount;
    }

    @Override
    public XPathValue evaluate(XPathContext context) throws XPathException {
        XPathValue raw = operand.evaluate(context);
        
        // Even number of negations is a no-op
        if (negationCount % 2 == 0) {
            return raw instanceof XPathNumber ? raw : XPathNumber.of(raw.asNumber());
        }
        
        if (raw instanceof XPathNumber) {
            XPathNumber num = (XPathNumber) raw;
            if (num.isExactInteger()) {
                Long lv = num.toLong();
                if (lv != null) {
                    long negated = -lv.longValue();
                    if (lv.longValue() != Long.MIN_VALUE) {
                        return XPathNumber.ofInteger(negated);
                    }
                    return XPathNumber.ofInteger(BigInteger.valueOf(lv.longValue()).negate());
                }
                BigInteger bi = num.toBigInteger();
                return XPathNumber.ofInteger(bi.negate());
            }
            if (num.isDecimal()) {
                return new XPathNumber(num.getDecimalValue().negate());
            }
            double value = -num.asNumber();
            if (num.isFloat() || num.isExplicitDouble()) {
                return new XPathNumber(value, num.isFloat(), num.isExplicitDouble());
            }
            return XPathNumber.of(value);
        }
        return XPathNumber.of(-raw.asNumber());
    }

    /**
     * Returns the operand.
     *
     * @return the operand expression
     */
    public Expr getOperand() {
        return operand;
    }

    /**
     * Returns the number of negations.
     *
     * @return the negation count
     */
    public int getNegationCount() {
        return negationCount;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < negationCount; i++) {
            sb.append('-');
        }
        sb.append(operand);
        return sb.toString();
    }

}

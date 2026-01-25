/*
 * IfExpr.java
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
import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;

/**
 * XPath 2.0 conditional (if/then/else) expression.
 *
 * <p>Syntax: {@code if (test-expr) then then-expr else else-expr}
 *
 * <p>The test expression is evaluated and converted to boolean. If true,
 * the then-expression is evaluated and returned; otherwise the else-expression
 * is evaluated and returned.
 *
 * <p>Example:
 * <pre>
 * if ($x > 0) then "positive" else "non-positive"
 * </pre>
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class IfExpr implements Expr {

    private final Expr condition;
    private final Expr thenExpr;
    private final Expr elseExpr;

    /**
     * Creates a new if expression.
     *
     * @param condition the test condition
     * @param thenExpr the expression to evaluate if condition is true
     * @param elseExpr the expression to evaluate if condition is false
     */
    public IfExpr(Expr condition, Expr thenExpr, Expr elseExpr) {
        this.condition = condition;
        this.thenExpr = thenExpr;
        this.elseExpr = elseExpr;
    }

    @Override
    public XPathValue evaluate(XPathContext context) throws XPathException {
        XPathValue condResult = condition.evaluate(context);
        
        if (condResult.asBoolean()) {
            return thenExpr.evaluate(context);
        } else {
            return elseExpr.evaluate(context);
        }
    }

    /**
     * Returns the condition expression.
     *
     * @return the condition
     */
    public Expr getCondition() {
        return condition;
    }

    /**
     * Returns the then expression.
     *
     * @return the then expression
     */
    public Expr getThenExpr() {
        return thenExpr;
    }

    /**
     * Returns the else expression.
     *
     * @return the else expression
     */
    public Expr getElseExpr() {
        return elseExpr;
    }

    @Override
    public String toString() {
        return "if (" + condition + ") then " + thenExpr + " else " + elseExpr;
    }

}

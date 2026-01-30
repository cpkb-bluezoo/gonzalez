/*
 * LetExpr.java
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

import java.util.ArrayList;
import java.util.List;

/**
 * XPath 3.0 let expression (variable binding).
 *
 * <p>Syntax: {@code let $var := expr return expr}
 *
 * <p>The let expression binds a value to a variable and evaluates the
 * return expression with that binding in scope. Unlike for, let does
 * not iterate - it binds the entire value.
 *
 * <p>Example:
 * <pre>
 * let $x := 5 return $x * 2
 * </pre>
 *
 * <p>Multiple variable bindings can be chained:
 * <pre>
 * let $x := 5, $y := 10 return $x + $y
 * </pre>
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class LetExpr implements Expr {

    /**
     * A variable binding in a let expression.
     */
    public static final class Binding {
        private final String varName;
        private final Expr value;

        /**
         * Creates a new binding.
         *
         * @param varName the variable name (without $)
         * @param value the value expression
         */
        public Binding(String varName, Expr value) {
            this.varName = varName;
            this.value = value;
        }

        public String getVarName() {
            return varName;
        }

        public Expr getValue() {
            return value;
        }

        @Override
        public String toString() {
            return "$" + varName + " := " + value;
        }
    }

    private final List<Binding> bindings;
    private final Expr returnExpr;

    /**
     * Creates a new let expression with a single binding.
     *
     * @param varName the variable name
     * @param value the value expression
     * @param returnExpr the return expression
     */
    public LetExpr(String varName, Expr value, Expr returnExpr) {
        this.bindings = new ArrayList<>();
        this.bindings.add(new Binding(varName, value));
        this.returnExpr = returnExpr;
    }

    /**
     * Creates a new let expression with multiple bindings.
     *
     * @param bindings the variable bindings
     * @param returnExpr the return expression
     */
    public LetExpr(List<Binding> bindings, Expr returnExpr) {
        this.bindings = new ArrayList<>(bindings);
        this.returnExpr = returnExpr;
    }

    @Override
    public XPathValue evaluate(XPathContext context) throws XPathException {
        XPathContext current = context;
        
        // Bind each variable in sequence
        for (Binding binding : bindings) {
            XPathValue value = binding.value.evaluate(current);
            current = current.withVariable(null, binding.varName, value);
        }
        
        // Evaluate return expression with all bindings in scope
        return returnExpr.evaluate(current);
    }

    /**
     * Returns the variable bindings.
     *
     * @return the bindings
     */
    public List<Binding> getBindings() {
        return bindings;
    }

    /**
     * Returns the return expression.
     *
     * @return the return expression
     */
    public Expr getReturnExpr() {
        return returnExpr;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("let ");
        for (int i = 0; i < bindings.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(bindings.get(i));
        }
        sb.append(" return ").append(returnExpr);
        return sb.toString();
    }

}

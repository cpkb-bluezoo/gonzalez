/*
 * ForExpr.java
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
import org.bluezoo.gonzalez.transform.xpath.type.XPathSequence;
import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * XPath 2.0 for expression (FLWOR iteration).
 *
 * <p>Syntax: {@code for $var in expr return expr}
 *
 * <p>The for expression iterates over the items in a sequence, binding
 * each item to a variable, and evaluates the return expression for each.
 * The results are concatenated into a single sequence.
 *
 * <p>Example:
 * <pre>
 * for $x in (1, 2, 3) return $x * 2
 * </pre>
 *
 * <p>Multiple variable bindings can be chained:
 * <pre>
 * for $x in (1, 2), $y in (3, 4) return $x + $y
 * </pre>
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class ForExpr implements Expr {

    /**
     * A variable binding in a for expression.
     */
    public static final class Binding {
        private final String varName;
        private final Expr sequence;

        /**
         * Creates a new binding.
         *
         * @param varName the variable name (without $)
         * @param sequence the sequence expression
         */
        /**
         * Creates a new binding.
         *
         * @param varName the variable name (without $)
         * @param sequence the sequence expression
         */
        public Binding(String varName, Expr sequence) {
            this.varName = varName;
            this.sequence = sequence;
        }

        /**
         * Returns the variable name.
         *
         * @return the variable name (without $)
         */
        public String getVarName() {
            return varName;
        }

        /**
         * Returns the sequence expression.
         *
         * @return the sequence expression
         */
        public Expr getSequence() {
            return sequence;
        }

        @Override
        public String toString() {
            return "$" + varName + " in " + sequence;
        }
    }

    private final List<Binding> bindings;
    private final Expr returnExpr;

    /**
     * Creates a new for expression with a single binding.
     *
     * @param varName the variable name
     * @param sequence the sequence expression
     * @param returnExpr the return expression
     */
    public ForExpr(String varName, Expr sequence, Expr returnExpr) {
        this.bindings = new ArrayList<>();
        this.bindings.add(new Binding(varName, sequence));
        this.returnExpr = returnExpr;
    }

    /**
     * Creates a new for expression with multiple bindings.
     *
     * @param bindings the variable bindings
     * @param returnExpr the return expression
     */
    public ForExpr(List<Binding> bindings, Expr returnExpr) {
        this.bindings = new ArrayList<>(bindings);
        this.returnExpr = returnExpr;
    }

    @Override
    public XPathValue evaluate(XPathContext context) throws XPathException {
        List<XPathValue> results = new ArrayList<>();
        evaluateBindings(context, 0, results);
        return XPathSequence.fromList(results);
    }

    /**
     * Recursively evaluates bindings and collects results.
     */
    private void evaluateBindings(XPathContext context, int bindingIndex, 
                                   List<XPathValue> results) throws XPathException {
        if (bindingIndex >= bindings.size()) {
            // All bindings processed - evaluate return expression
            XPathValue result = returnExpr.evaluate(context);
            // Flatten sequences into results
            if (result.isSequence()) {
                Iterator<XPathValue> iter = result.sequenceIterator();
                while (iter.hasNext()) {
                    results.add(iter.next());
                }
            } else {
                results.add(result);
            }
            return;
        }

        Binding binding = bindings.get(bindingIndex);
        XPathValue seqValue = binding.sequence.evaluate(context);

        // Iterate over sequence items
        Iterator<XPathValue> iter = seqValue.sequenceIterator();
        while (iter.hasNext()) {
            XPathValue item = iter.next();
            
            // Bind variable and recurse
            XPathContext boundContext = context.withVariable(null, binding.varName, item);
            evaluateBindings(boundContext, bindingIndex + 1, results);
        }
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
        StringBuilder sb = new StringBuilder("for ");
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

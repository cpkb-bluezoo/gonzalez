/*
 * QuantifiedExpr.java
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
import org.bluezoo.gonzalez.transform.xpath.type.XPathBoolean;
import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * XPath 2.0 quantified expression (some/every).
 *
 * <p>Syntax:
 * <ul>
 *   <li>{@code some $var in expr satisfies expr}</li>
 *   <li>{@code every $var in expr satisfies expr}</li>
 * </ul>
 *
 * <p>For {@code some}, returns true if at least one item in the sequence
 * satisfies the condition. For {@code every}, returns true if all items
 * satisfy the condition.
 *
 * <p>Examples:
 * <pre>
 * some $x in (1, 2, 3) satisfies $x > 2     (: true :)
 * every $x in (1, 2, 3) satisfies $x > 0    (: true :)
 * </pre>
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class QuantifiedExpr implements Expr {

    /**
     * The quantifier type.
     */
    public enum Quantifier {
        SOME,
        EVERY
    }

    /**
     * A variable binding in a quantified expression.
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

    private final Quantifier quantifier;
    private final List<Binding> bindings;
    private final Expr satisfiesExpr;

    /**
     * Creates a new quantified expression with a single binding.
     *
     * @param quantifier SOME or EVERY
     * @param varName the variable name
     * @param sequence the sequence expression
     * @param satisfiesExpr the condition expression
     */
    public QuantifiedExpr(Quantifier quantifier, String varName, Expr sequence, 
                          Expr satisfiesExpr) {
        this.quantifier = quantifier;
        this.bindings = new ArrayList<>();
        this.bindings.add(new Binding(varName, sequence));
        this.satisfiesExpr = satisfiesExpr;
    }

    /**
     * Creates a new quantified expression with multiple bindings.
     *
     * @param quantifier SOME or EVERY
     * @param bindings the variable bindings
     * @param satisfiesExpr the condition expression
     */
    public QuantifiedExpr(Quantifier quantifier, List<Binding> bindings, 
                          Expr satisfiesExpr) {
        this.quantifier = quantifier;
        this.bindings = new ArrayList<>(bindings);
        this.satisfiesExpr = satisfiesExpr;
    }

    @Override
    public XPathValue evaluate(XPathContext context) throws XPathException {
        boolean result = evaluateBindings(context, 0);
        return XPathBoolean.of(result);
    }

    /**
     * Recursively evaluates bindings and tests the satisfies condition.
     */
    private boolean evaluateBindings(XPathContext context, int bindingIndex) 
            throws XPathException {
        if (bindingIndex >= bindings.size()) {
            // All bindings processed - evaluate satisfies expression
            XPathValue result = satisfiesExpr.evaluate(context);
            return result.asBoolean();
        }

        Binding binding = bindings.get(bindingIndex);
        XPathValue seqValue = binding.sequence.evaluate(context);

        // Iterate over sequence items
        Iterator<XPathValue> iter = seqValue.sequenceIterator();
        
        if (quantifier == Quantifier.SOME) {
            // For 'some': return true if any item satisfies
            while (iter.hasNext()) {
                XPathValue item = iter.next();
                XPathContext boundContext = context.withVariable(null, binding.varName, item);
                if (evaluateBindings(boundContext, bindingIndex + 1)) {
                    return true;
                }
            }
            return false;
        } else {
            // For 'every': return true if all items satisfy
            while (iter.hasNext()) {
                XPathValue item = iter.next();
                XPathContext boundContext = context.withVariable(null, binding.varName, item);
                if (!evaluateBindings(boundContext, bindingIndex + 1)) {
                    return false;
                }
            }
            return true;
        }
    }

    /**
     * Returns the quantifier.
     *
     * @return SOME or EVERY
     */
    public Quantifier getQuantifier() {
        return quantifier;
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
     * Returns the satisfies expression.
     *
     * @return the satisfies expression
     */
    public Expr getSatisfiesExpr() {
        return satisfiesExpr;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(quantifier == Quantifier.SOME ? "some " : "every ");
        for (int i = 0; i < bindings.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(bindings.get(i));
        }
        sb.append(" satisfies ").append(satisfiesExpr);
        return sb.toString();
    }

}

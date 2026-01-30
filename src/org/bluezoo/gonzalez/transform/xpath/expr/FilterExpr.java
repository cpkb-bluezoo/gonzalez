/*
 * FilterExpr.java
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
import org.bluezoo.gonzalez.transform.xpath.type.XPathNode;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNodeSet;
import org.bluezoo.gonzalez.transform.xpath.type.XPathSequence;
import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * A filter expression (primary expression with predicates).
 *
 * <p>A filter expression evaluates a primary expression and then filters
 * the result through one or more predicates.
 *
 * <p>Examples:
 * <ul>
 *   <li>{@code $var[1]} - first item in variable</li>
 *   <li>{@code (//para)[last()]} - last para element in document</li>
 * </ul>
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class FilterExpr implements Expr {

    private final Expr primary;
    private final List<Expr> predicates;

    /**
     * Creates a filter expression.
     *
     * @param primary the primary expression
     * @param predicates the predicate expressions
     */
    public FilterExpr(Expr primary, List<Expr> predicates) {
        if (primary == null) {
            throw new NullPointerException("Primary expression cannot be null");
        }
        this.primary = primary;
        this.predicates = predicates != null ? 
            Collections.unmodifiableList(new ArrayList<>(predicates)) : 
            Collections.emptyList();
    }

    @Override
    public XPathValue evaluate(XPathContext context) throws XPathException {
        XPathValue value = primary.evaluate(context);
        
        if (predicates.isEmpty()) {
            return value;
        }
        
        // Handle node-sets (XPath 1.0 behavior)
        if (value.isNodeSet()) {
            return evaluateNodeSetPredicates(value.asNodeSet(), context);
        }
        
        // XPath 2.0+: Handle sequences and atomic values
        return evaluateSequencePredicates(value, context);
    }
    
    /**
     * Evaluates predicates on a node-set (XPath 1.0 behavior).
     */
    private XPathValue evaluateNodeSetPredicates(XPathNodeSet nodeSet, XPathContext context) 
            throws XPathException {
        List<XPathNode> current = new ArrayList<>(nodeSet.size());
        for (XPathNode node : nodeSet) {
            current.add(node);
        }
        
        // Apply each predicate
        for (Expr predicate : predicates) {
            List<XPathNode> filtered = new ArrayList<>();
            int size = current.size();
            int position = 1;
            
            for (XPathNode node : current) {
                XPathContext predContext = context
                    .withContextNode(node)
                    .withPositionAndSize(position, size);
                
                XPathValue result = predicate.evaluate(predContext);
                
                boolean include;
                if (result.getType() == XPathValue.Type.NUMBER) {
                    include = (result.asNumber() == position);
                } else {
                    include = result.asBoolean();
                }
                
                if (include) {
                    filtered.add(node);
                }
                position++;
            }
            
            current = filtered;
        }
        
        if (current.isEmpty()) {
            return XPathNodeSet.EMPTY;
        }
        return new XPathNodeSet(current);
    }
    
    /**
     * Evaluates predicates on a sequence (XPath 2.0+ behavior).
     * Handles both XPathSequence and singleton atomic values.
     */
    private XPathValue evaluateSequencePredicates(XPathValue value, XPathContext context) 
            throws XPathException {
        // Convert value to a list of items
        List<XPathValue> current = new ArrayList<>();
        if (value instanceof XPathSequence) {
            XPathSequence seq = (XPathSequence) value;
            for (XPathValue item : seq) {
                current.add(item);
            }
        } else {
            // Singleton value - treat as single-item sequence
            current.add(value);
        }
        
        // Apply each predicate
        for (Expr predicate : predicates) {
            List<XPathValue> filtered = new ArrayList<>();
            int size = current.size();
            int position = 1;
            
            for (XPathValue item : current) {
                XPathContext predContext = context
                    .withContextItem(item)
                    .withPositionAndSize(position, size);
                
                XPathValue result = predicate.evaluate(predContext);
                
                boolean include;
                if (result.getType() == XPathValue.Type.NUMBER) {
                    // Numeric predicate: select item at that position
                    double pos = result.asNumber();
                    include = (pos == position);
                } else {
                    // Boolean predicate: filter by truthiness
                    include = result.asBoolean();
                }
                
                if (include) {
                    filtered.add(item);
                }
                position++;
            }
            
            current = filtered;
        }
        
        // Return result
        if (current.isEmpty()) {
            return XPathSequence.EMPTY;
        }
        if (current.size() == 1) {
            return current.get(0);
        }
        return new XPathSequence(current);
    }

    /**
     * Returns the primary expression.
     *
     * @return the primary
     */
    public Expr getPrimary() {
        return primary;
    }

    /**
     * Returns the predicates.
     *
     * @return the predicates (immutable)
     */
    public List<Expr> getPredicates() {
        return predicates;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(primary);
        for (Expr pred : predicates) {
            sb.append('[').append(pred).append(']');
        }
        return sb.toString();
    }

}

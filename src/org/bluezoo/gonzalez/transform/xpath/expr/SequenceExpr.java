/*
 * SequenceExpr.java
 * Copyright (C) 2026 Chris Burdess
 *
 * This file is part of Gonzalez, a streaming XML parser.
 *
 * Gonzalez is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.bluezoo.gonzalez.transform.xpath.expr;

import org.bluezoo.gonzalez.transform.xpath.XPathContext;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNodeSet;
import org.bluezoo.gonzalez.transform.xpath.type.XPathSequence;
import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * A sequence expression representing a list of values.
 * Handles XPath 2.0 sequence constructors: (), (1, 2, 3)
 *
 * <p>In XPath 1.0 context, sequences are converted to node-sets when possible,
 * or the first item is used for scalar operations.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class SequenceExpr implements Expr {

    private final List<Expr> items;

    /**
     * Creates a sequence expression.
     *
     * @param items the sequence items
     */
    public SequenceExpr(List<Expr> items) {
        this.items = items != null ? new ArrayList<>(items) : Collections.emptyList();
    }

    /**
     * Returns the items in this sequence.
     *
     * @return the items (immutable view)
     */
    public List<Expr> getItems() {
        return Collections.unmodifiableList(items);
    }

    /**
     * Returns true if this is an empty sequence.
     *
     * @return true if empty
     */
    public boolean isEmpty() {
        return items.isEmpty();
    }

    @Override
    public XPathValue evaluate(XPathContext context) throws XPathException {
        // Empty sequence returns empty sequence
        if (items.isEmpty()) {
            return XPathSequence.EMPTY;
        }
        
        // Single item - just evaluate it
        if (items.size() == 1) {
            return items.get(0).evaluate(context);
        }
        
        // Multiple items - evaluate each and collect into a sequence
        List<XPathValue> results = new ArrayList<>();
        for (Expr item : items) {
            XPathValue value = item.evaluate(context);
            if (value != null) {
                // Flatten nested sequences
                if (value.isSequence()) {
                    Iterator<XPathValue> iter = value.sequenceIterator();
                    while (iter.hasNext()) {
                        results.add(iter.next());
                    }
                } else {
                    results.add(value);
                }
            }
        }
        
        if (results.isEmpty()) {
            return XPathSequence.EMPTY;
        }
        
        return XPathSequence.fromList(results);
    }

    @Override
    public String toString() {
        if (items.isEmpty()) {
            return "()";
        }
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(items.get(i));
        }
        sb.append(")");
        return sb.toString();
    }
}

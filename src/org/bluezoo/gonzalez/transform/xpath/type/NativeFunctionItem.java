/*
 * NativeFunctionItem.java
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

import java.util.List;

import org.bluezoo.gonzalez.transform.xpath.XPathContext;
import org.bluezoo.gonzalez.transform.xpath.expr.XPathException;

/**
 * A function item backed by native Java code.
 *
 * <p>Unlike {@link XPathFunctionItem} (which delegates to a function library)
 * or {@link org.bluezoo.gonzalez.transform.xpath.expr.InlineFunctionItem}
 * (which evaluates a parsed XPath expression body), this class allows
 * function items to be created programmatically with arbitrary Java logic.
 *
 * <p>Used by {@code random-number-generator()} to produce the {@code next}
 * and {@code permute} function items stored in the returned map.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public abstract class NativeFunctionItem implements XPathValue {

    private final String name;
    private final int arity;

    /**
     * Creates a native function item.
     *
     * @param name a descriptive name for this function
     * @param arity the number of arguments this function expects
     */
    protected NativeFunctionItem(String name, int arity) {
        this.name = name;
        this.arity = arity;
    }

    /**
     * Returns the descriptive name of this function.
     *
     * @return the function name
     */
    public String getName() {
        return name;
    }

    /**
     * Returns the arity (number of parameters) of this function.
     *
     * @return the arity
     */
    public int getArity() {
        return arity;
    }

    /**
     * Invokes this function with the given arguments.
     *
     * @param args the argument values
     * @param context the evaluation context
     * @return the function result
     * @throws XPathException if invocation fails
     */
    public abstract XPathValue invoke(List<XPathValue> args,
            XPathContext context) throws XPathException;

    @Override
    public Type getType() {
        return Type.ATOMIC;
    }

    @Override
    public String asString() {
        return name + "#" + arity;
    }

    @Override
    public double asNumber() {
        return Double.NaN;
    }

    @Override
    public boolean asBoolean() {
        return true;
    }

    @Override
    public XPathNodeSet asNodeSet() {
        return null;
    }

    @Override
    public String toString() {
        return "native-function(" + name + "#" + arity + ")";
    }
}

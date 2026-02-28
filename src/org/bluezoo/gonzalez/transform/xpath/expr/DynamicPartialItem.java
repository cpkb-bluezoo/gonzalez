/*
 * DynamicPartialItem.java
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

import java.util.ArrayList;
import java.util.List;

import org.bluezoo.gonzalez.transform.xpath.XPathContext;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNodeSet;
import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;

/**
 * Partial application of a dynamic function call.
 *
 * <p>Wraps any callable function value (InlineFunctionItem, PartialFunctionItem,
 * XPathFunctionItem, etc.) with bound arguments and placeholder positions.
 * When invoked, fills placeholder positions with the supplied arguments and
 * delegates to the wrapped function.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class DynamicPartialItem implements XPathValue {

    private final XPathValue baseFunction;
    private final int arity;
    private final XPathValue[] boundArgs;
    private final int[] placeholderPositions;
    private final int totalArgs;

    /**
     * Creates a dynamic partial function item.
     *
     * @param baseFunction the wrapped function value
     * @param arity number of unbound placeholder arguments
     * @param boundArgs array of bound argument values (null at placeholder positions)
     * @param placeholderPositions indices of placeholder arguments
     * @param totalArgs total number of arguments in the original call
     */
    public DynamicPartialItem(XPathValue baseFunction, int arity,
            XPathValue[] boundArgs, int[] placeholderPositions, int totalArgs) {
        this.baseFunction = baseFunction;
        this.arity = arity;
        this.boundArgs = boundArgs;
        this.placeholderPositions = placeholderPositions;
        this.totalArgs = totalArgs;
    }

    /**
     * Invokes this partial function with the given placeholder arguments.
     *
     * @param args the arguments to fill placeholder positions
     * @param context the evaluation context
     * @return the function result
     * @throws XPathException if invocation fails
     */
    public XPathValue invoke(List<XPathValue> args, XPathContext context) throws XPathException {
        if (args.size() != arity) {
            throw new XPathException("XPTY0004: Partial function expects " + arity
                + " argument(s), got " + args.size());
        }

        List<XPathValue> fullArgs = new ArrayList<XPathValue>(totalArgs);
        for (int i = 0; i < totalArgs; i++) {
            fullArgs.add(null);
        }

        for (int i = 0; i < totalArgs; i++) {
            if (boundArgs[i] != null) {
                fullArgs.set(i, boundArgs[i]);
            }
        }

        for (int i = 0; i < placeholderPositions.length; i++) {
            fullArgs.set(placeholderPositions[i], args.get(i));
        }

        if (baseFunction instanceof InlineFunctionItem) {
            return ((InlineFunctionItem) baseFunction).invoke(fullArgs, context);
        }
        if (baseFunction instanceof PartialFunctionItem) {
            return ((PartialFunctionItem) baseFunction).invoke(fullArgs, context);
        }
        if (baseFunction instanceof org.bluezoo.gonzalez.transform.xpath.type.XPathFunctionItem) {
            return ((org.bluezoo.gonzalez.transform.xpath.type.XPathFunctionItem) baseFunction)
                    .invoke(fullArgs, context);
        }
        throw new XPathException("XPTY0004: Cannot invoke partial application of non-function item");
    }

    /**
     * Returns the arity (number of placeholder arguments).
     *
     * @return the arity
     */
    public int getArity() {
        return arity;
    }

    @Override
    public Type getType() {
        return Type.ATOMIC;
    }

    @Override
    public String asString() {
        return baseFunction.asString() + "(partial)";
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
        return "dynamic-partial(" + baseFunction + ")";
    }
}

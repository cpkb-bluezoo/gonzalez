/*
 * PartialFunctionItem.java
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
import org.bluezoo.gonzalez.transform.xpath.XPathFunctionLibrary;
import org.bluezoo.gonzalez.transform.xpath.type.XPathFunctionItem;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNodeSet;
import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;

/**
 * XPath 3.1 partial function application result.
 *
 * <p>Created when a function call contains one or more argument placeholders
 * ({@code ?}). The partially applied function captures the bound arguments
 * and returns a new function item whose arity equals the number of placeholders.
 *
 * <p>This class implements the same interface as {@link XPathFunctionItem}
 * and can be used wherever a function item is expected.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class PartialFunctionItem implements XPathValue {

    private final String fullName;
    private final String namespaceURI;
    private final String funcLocalName;
    private final int arity;
    private final XPathFunctionLibrary library;
    private final XPathValue[] boundArgs;
    private final int[] placeholderPositions;
    private final int totalArgs;

    /**
     * Creates a partial function item.
     *
     * @param fullName display name
     * @param namespaceURI namespace URI for function lookup
     * @param funcLocalName local name for function lookup
     * @param arity number of unbound placeholder arguments
     * @param library function library for invocation
     * @param boundArgs array of bound argument values (null at placeholder positions)
     * @param placeholderPositions indices of placeholder arguments
     * @param totalArgs total number of arguments in the original call
     */
    public PartialFunctionItem(String fullName, String namespaceURI, String funcLocalName,
            int arity, XPathFunctionLibrary library, XPathValue[] boundArgs,
            int[] placeholderPositions, int totalArgs) {
        this.fullName = fullName;
        this.namespaceURI = namespaceURI;
        this.funcLocalName = funcLocalName;
        this.arity = arity;
        this.library = library;
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
            XPathValue argVal;
            if (i < args.size()) {
                argVal = args.get(i);
            } else {
                argVal = XPathNodeSet.empty();
            }
            fullArgs.set(placeholderPositions[i], argVal);
        }

        return library.invokeFunction(namespaceURI, funcLocalName, fullArgs, context);
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
        return fullName + "(partial)";
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
        return "partial(" + fullName + ")";
    }
}

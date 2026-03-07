/*
 * XPathFunctionItem.java
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

import org.bluezoo.gonzalez.transform.xpath.XPathContext;
import org.bluezoo.gonzalez.transform.xpath.XPathFunctionLibrary;
import org.bluezoo.gonzalez.transform.xpath.expr.XPathException;

import java.util.List;

/**
 * XPath 3.0 function item value.
 *
 * <p>Represents a function as a first-class value, as produced by named
 * function references ({@code name#arity}) or inline function expressions.
 * Function items can be passed as arguments, stored in variables, and
 * invoked via dynamic function calls.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class XPathFunctionItem implements XPathValue {

    private final String name;
    private final String namespaceURI;
    private final int arity;
    private final XPathFunctionLibrary library;
    private SequenceType[] parameterTypes;
    private SequenceType returnType;

    /**
     * Creates a new function item wrapping a library function.
     *
     * @param name the function name
     * @param arity the function arity
     * @param library the function library for invocation
     */
    public XPathFunctionItem(String name, int arity, XPathFunctionLibrary library) {
        this.name = name;
        this.namespaceURI = null;
        this.arity = arity;
        this.library = library;
    }

    /**
     * Creates a new function item with an explicit namespace URI.
     *
     * @param name the function name (may be prefixed)
     * @param namespaceURI the namespace URI (may be null)
     * @param arity the function arity
     * @param library the function library for invocation
     */
    public XPathFunctionItem(String name, String namespaceURI, int arity, XPathFunctionLibrary library) {
        this.name = name;
        this.namespaceURI = namespaceURI;
        this.arity = arity;
        this.library = library;
    }

    /**
     * Returns the function name.
     *
     * @return the function name
     */
    public String getName() {
        return name;
    }

    /**
     * Returns the function namespace URI.
     *
     * @return the namespace URI, or null
     */
    public String getNamespaceURI() {
        return namespaceURI;
    }

    /**
     * Returns the arity.
     *
     * @return the number of arguments this function expects
     */
    public int getArity() {
        return arity;
    }

    /**
     * Returns the function library used for invocation.
     *
     * @return the function library
     */
    public XPathFunctionLibrary getLibrary() {
        return library;
    }

    /**
     * Returns the declared parameter types, or null if unknown.
     */
    public SequenceType[] getParameterTypes() {
        return parameterTypes;
    }

    /**
     * Returns the declared return type, or null if unknown.
     */
    public SequenceType getReturnType() {
        return returnType;
    }

    /**
     * Sets the declared parameter types and return type for type checking.
     */
    public void setSignature(SequenceType[] parameterTypes, SequenceType returnType) {
        this.parameterTypes = parameterTypes;
        this.returnType = returnType;
    }

    /**
     * Invokes this function with the given arguments.
     *
     * @param args the arguments
     * @param context the evaluation context
     * @return the function result
     * @throws XPathException if invocation fails
     */
    public XPathValue invoke(List<XPathValue> args, XPathContext context) throws XPathException {
        if (args.size() != arity) {
            throw new XPathException("XPTY0004: Function " + name + "#" + arity
                + " expects " + arity + " argument(s), got " + args.size());
        }
        // XTDE1360: dynamic call to current() is a dynamic error
        if ("current".equals(name) && arity == 0) {
            throw new XPathException("XTDE1360",
                "Dynamic call to current() is not allowed");
        }
        // Try with namespace URI first if available
        if (namespaceURI != null && !namespaceURI.isEmpty()) {
            // Extract local name from prefixed name
            int colonIdx = name.indexOf(':');
            String localName = (colonIdx >= 0) ? name.substring(colonIdx + 1) : name;
            return library.invokeFunction(namespaceURI, localName, args, context);
        }
        return library.invokeFunction(null, name, args, context);
    }

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
        return "function(" + name + "#" + arity + ")";
    }
}

/*
 * InlineFunctionItem.java
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

import java.util.List;

import org.bluezoo.gonzalez.transform.xpath.XPathContext;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNodeSet;
import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;

/**
 * Runtime function item produced by an inline function expression.
 *
 * <p>Captures the evaluation context (closure) at creation time so that
 * variables in scope when the function was defined remain accessible
 * during invocation.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class InlineFunctionItem implements XPathValue {

    private final List<String> paramNames;
    private final Expr body;
    private final XPathContext closureContext;

    /**
     * Creates an inline function item.
     *
     * @param paramNames the parameter names (without $)
     * @param body the body expression
     * @param closureContext the context captured at definition time
     */
    InlineFunctionItem(List<String> paramNames, Expr body, XPathContext closureContext) {
        this.paramNames = paramNames;
        this.body = body;
        this.closureContext = closureContext;
    }

    /**
     * Returns the arity (number of parameters) of this function.
     *
     * @return the arity
     */
    public int getArity() {
        return paramNames.size();
    }

    /**
     * Invokes this inline function with the given arguments.
     *
     * @param args the argument values
     * @param callerContext the caller's evaluation context
     * @return the result of evaluating the body
     * @throws XPathException if evaluation fails
     */
    public XPathValue invoke(List<XPathValue> args, XPathContext callerContext) throws XPathException {
        if (args.size() != paramNames.size()) {
            throw new XPathException("XPTY0004: Inline function expects " + paramNames.size()
                + " argument(s), got " + args.size());
        }

        // Start from the closure context (captures variables from definition site)
        XPathContext ctx = closureContext;

        // Bind parameters
        for (int i = 0; i < paramNames.size(); i++) {
            ctx = ctx.withVariable(null, paramNames.get(i), args.get(i));
        }

        return body.evaluate(ctx);
    }

    @Override
    public Type getType() {
        return Type.ATOMIC;
    }

    @Override
    public String asString() {
        return "function#" + paramNames.size();
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
        return "inline-function#" + paramNames.size();
    }
}

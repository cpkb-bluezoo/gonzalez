/*
 * InlineFunctionExpr.java
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
import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;

/**
 * XPath 3.1 inline function expression: {@code function($x, $y) { $x + $y }}.
 *
 * <p>Evaluates to a function item that closes over the current variable
 * bindings. When invoked, the function body is evaluated in a context where
 * the declared parameters are bound to the supplied arguments, and all
 * variables that were in scope at definition time are also available.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class InlineFunctionExpr implements Expr {

    private final List<String> paramNames;
    private final Expr body;

    /**
     * Creates an inline function expression.
     *
     * @param paramNames the parameter variable names (without $)
     * @param body the function body expression
     */
    public InlineFunctionExpr(List<String> paramNames, Expr body) {
        this.paramNames = paramNames;
        this.body = body;
    }

    @Override
    public XPathValue evaluate(XPathContext context) throws XPathException {
        return new InlineFunctionItem(paramNames, body, context);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("function(");
        for (int i = 0; i < paramNames.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append("$");
            sb.append(paramNames.get(i));
        }
        sb.append(") { ... }");
        return sb.toString();
    }
}

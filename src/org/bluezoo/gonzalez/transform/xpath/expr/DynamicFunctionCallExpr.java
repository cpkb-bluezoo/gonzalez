/*
 * DynamicFunctionCallExpr.java
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
import org.bluezoo.gonzalez.transform.xpath.type.XPathArray;
import org.bluezoo.gonzalez.transform.xpath.type.XPathFunctionItem;
import org.bluezoo.gonzalez.transform.xpath.type.XPathMap;
import org.bluezoo.gonzalez.transform.xpath.type.XPathSequence;
import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;

/**
 * An XPath 3.1 dynamic function call: {@code Expr(Args)}.
 *
 * <p>When the base expression evaluates to a map, this performs a map
 * lookup using the first argument as the key (XPath 3.1 Section 3.11.3.1).
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class DynamicFunctionCallExpr implements Expr {

    private final Expr base;
    private final List<Expr> args;

    /**
     * Creates a dynamic function call expression.
     *
     * @param base the expression that evaluates to a function/map/array
     * @param args the argument expressions
     */
    public DynamicFunctionCallExpr(Expr base, List<Expr> args) {
        this.base = base;
        this.args = args;
    }

    @Override
    public XPathValue evaluate(XPathContext context) throws XPathException {
        XPathValue baseValue = base.evaluate(context);
        if (baseValue instanceof InlineFunctionItem) {
            InlineFunctionItem inline = (InlineFunctionItem) baseValue;
            List<XPathValue> evaluatedArgs = new java.util.ArrayList<XPathValue>();
            for (Expr arg : args) {
                evaluatedArgs.add(arg.evaluate(context));
            }
            return inline.invoke(evaluatedArgs, context);
        }
        if (baseValue instanceof PartialFunctionItem) {
            PartialFunctionItem partial = (PartialFunctionItem) baseValue;
            List<XPathValue> evaluatedArgs = new java.util.ArrayList<XPathValue>();
            for (Expr arg : args) {
                evaluatedArgs.add(arg.evaluate(context));
            }
            return partial.invoke(evaluatedArgs, context);
        }
        if (baseValue instanceof XPathFunctionItem) {
            XPathFunctionItem funcItem = (XPathFunctionItem) baseValue;
            List<XPathValue> evaluatedArgs = new java.util.ArrayList<XPathValue>();
            for (Expr arg : args) {
                evaluatedArgs.add(arg.evaluate(context));
            }
            return funcItem.invoke(evaluatedArgs, context);
        }
        if (baseValue instanceof XPathArray) {
            XPathArray array = (XPathArray) baseValue;
            if (args.isEmpty()) {
                throw new XPathException("XPTY0004: Array requires exactly one argument for lookup");
            }
            XPathValue keyVal = args.get(0).evaluate(context);
            int index = (int) keyVal.asNumber();
            if (index >= 1 && index <= array.size()) {
                return array.get(index);
            }
            return XPathSequence.EMPTY;
        }
        if (baseValue instanceof XPathMap) {
            XPathMap map = (XPathMap) baseValue;
            if (args.isEmpty()) {
                throw new XPathException("XPTY0004: Map requires exactly one argument for lookup");
            }
            XPathValue keyVal = args.get(0).evaluate(context);
            String key = keyVal.asString();
            XPathValue result = map.get(key);
            if (result == null) {
                return XPathSequence.EMPTY;
            }
            return result;
        }
        throw new XPathException("XPTY0004: Dynamic function call on non-function item");
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(base);
        sb.append("(");
        for (int i = 0; i < args.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(args.get(i));
        }
        sb.append(")");
        return sb.toString();
    }
}

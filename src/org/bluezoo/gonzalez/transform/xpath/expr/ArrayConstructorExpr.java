/*
 * ArrayConstructorExpr.java
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
import org.bluezoo.gonzalez.transform.xpath.type.XPathArray;
import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;

/**
 * An XPath 3.1 square-bracket array constructor: {@code [expr1, expr2, ...]}.
 *
 * <p>Each member expression is evaluated and becomes a member of the resulting
 * array. Unlike sequences, members are not flattened.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class ArrayConstructorExpr implements Expr {

    private final List<Expr> memberExprs;

    /**
     * Creates an array constructor with the given member expressions.
     *
     * @param memberExprs the expressions for each array member
     */
    public ArrayConstructorExpr(List<Expr> memberExprs) {
        this.memberExprs = new ArrayList<Expr>(memberExprs);
    }

    @Override
    public XPathValue evaluate(XPathContext context) throws XPathException {
        List<XPathValue> members = new ArrayList<XPathValue>(memberExprs.size());
        for (int i = 0; i < memberExprs.size(); i++) {
            XPathValue member = memberExprs.get(i).evaluate(context);
            members.add(member);
        }
        return new XPathArray(members);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        for (int i = 0; i < memberExprs.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(memberExprs.get(i));
        }
        sb.append("]");
        return sb.toString();
    }
}

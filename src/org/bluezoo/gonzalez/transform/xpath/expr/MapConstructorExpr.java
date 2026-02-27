/*
 * MapConstructorExpr.java
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.bluezoo.gonzalez.transform.xpath.XPathContext;
import org.bluezoo.gonzalez.transform.xpath.type.XPathMap;
import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;

/**
 * An XPath 3.1 map constructor expression: {@code map { key : value, ... }}.
 *
 * <p>Each entry consists of a key expression (evaluated to an atomic value)
 * and a value expression (evaluated to an arbitrary XPath value). The map
 * is constructed by evaluating all entries in order.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class MapConstructorExpr implements Expr {

    private final List<Expr> keyExprs;
    private final List<Expr> valueExprs;

    /**
     * Creates a map constructor with the given key-value expression pairs.
     *
     * @param keyExprs the key expressions
     * @param valueExprs the value expressions (same length as keyExprs)
     */
    public MapConstructorExpr(List<Expr> keyExprs, List<Expr> valueExprs) {
        if (keyExprs.size() != valueExprs.size()) {
            throw new IllegalArgumentException("Key and value expression lists must have same size");
        }
        this.keyExprs = new ArrayList<Expr>(keyExprs);
        this.valueExprs = new ArrayList<Expr>(valueExprs);
    }

    @Override
    public XPathValue evaluate(XPathContext context) throws XPathException {
        Map<String, XPathValue> entries = new LinkedHashMap<String, XPathValue>();
        for (int i = 0; i < keyExprs.size(); i++) {
            XPathValue key = keyExprs.get(i).evaluate(context);
            XPathValue value = valueExprs.get(i).evaluate(context);
            String keyStr = key.asString();
            entries.put(keyStr, value);
        }
        return new XPathMap(entries);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("map{");
        for (int i = 0; i < keyExprs.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(keyExprs.get(i));
            sb.append(": ");
            sb.append(valueExprs.get(i));
        }
        sb.append("}");
        return sb.toString();
    }
}

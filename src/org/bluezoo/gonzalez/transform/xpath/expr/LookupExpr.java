/*
 * LookupExpr.java
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
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.bluezoo.gonzalez.transform.xpath.XPathContext;
import org.bluezoo.gonzalez.transform.xpath.type.XPathArray;
import org.bluezoo.gonzalez.transform.xpath.type.XPathMap;
import org.bluezoo.gonzalez.transform.xpath.type.XPathSequence;
import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;

/**
 * An XPath 3.1 lookup expression: {@code Expr ? KeySpecifier}.
 *
 * <p>Supports the following forms:
 * <ul>
 *   <li>{@code $map?key} - lookup by NCName key</li>
 *   <li>{@code $map?*} - return all values</li>
 *   <li>{@code $array?1} - lookup by integer position</li>
 * </ul>
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class LookupExpr implements Expr {

    private final Expr base;
    private final String key;
    private final boolean wildcard;

    /**
     * Creates a lookup expression with a specific key.
     *
     * @param base the base expression
     * @param key the key to look up
     */
    public LookupExpr(Expr base, String key) {
        this.base = base;
        this.key = key;
        this.wildcard = false;
    }

    /**
     * Creates a wildcard lookup expression.
     *
     * @param base the base expression
     * @param wildcard must be true
     */
    public LookupExpr(Expr base, boolean wildcard) {
        this.base = base;
        this.key = null;
        this.wildcard = wildcard;
    }

    @Override
    public XPathValue evaluate(XPathContext context) throws XPathException {
        XPathValue baseValue = base.evaluate(context);
        if (baseValue instanceof XPathArray) {
            XPathArray array = (XPathArray) baseValue;
            if (wildcard) {
                return new XPathSequence(array.members());
            }
            try {
                int index = Integer.parseInt(key);
                if (index >= 1 && index <= array.size()) {
                    return array.get(index);
                }
            } catch (NumberFormatException e) {
                // Non-numeric key on array
            }
            return XPathSequence.EMPTY;
        }
        if (baseValue instanceof XPathMap) {
            XPathMap map = (XPathMap) baseValue;
            if (wildcard) {
                List<XPathValue> values = new ArrayList<XPathValue>();
                for (Map.Entry<String, XPathValue> entry : map.entries()) {
                    values.add(entry.getValue());
                }
                if (values.isEmpty()) {
                    return XPathSequence.EMPTY;
                }
                return new XPathSequence(values);
            }
            XPathValue result = map.get(key);
            if (result == null) {
                return XPathSequence.EMPTY;
            }
            return result;
        }
        if (baseValue instanceof XPathSequence) {
            XPathSequence seq = (XPathSequence) baseValue;
            if (wildcard) {
                return seq;
            }
            try {
                int index = Integer.parseInt(key);
                if (index >= 1 && index <= seq.size()) {
                    Iterator<XPathValue> it = seq.sequenceIterator();
                    int pos = 1;
                    while (it.hasNext()) {
                        XPathValue item = it.next();
                        if (pos == index) {
                            return item;
                        }
                        pos++;
                    }
                }
            } catch (NumberFormatException e) {
                // Key is not numeric - try as string key on sequence items
            }
            // Apply lookup to each item in sequence
            List<XPathValue> results = new ArrayList<XPathValue>();
            Iterator<XPathValue> it = seq.sequenceIterator();
            while (it.hasNext()) {
                XPathValue item = it.next();
                if (item instanceof XPathMap) {
                    XPathValue v = ((XPathMap) item).get(key);
                    if (v != null) {
                        results.add(v);
                    }
                }
            }
            if (results.isEmpty()) {
                return XPathSequence.EMPTY;
            }
            return new XPathSequence(results);
        }
        return XPathSequence.EMPTY;
    }

    @Override
    public String toString() {
        if (wildcard) {
            return base + "?*";
        }
        return base + "?" + key;
    }
}

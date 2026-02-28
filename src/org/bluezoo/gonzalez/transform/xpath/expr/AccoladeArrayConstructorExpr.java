/*
 * AccoladeArrayConstructorExpr.java
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

import org.bluezoo.gonzalez.transform.xpath.XPathContext;
import org.bluezoo.gonzalez.transform.xpath.type.XPathArray;
import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;

/**
 * An XPath 3.1 accolade array constructor: {@code array{ expr }}.
 *
 * <p>Unlike the bracket constructor where each comma-separated
 * expression becomes one member, the accolade constructor evaluates
 * the enclosed expression and each item in the resulting sequence becomes
 * a separate array member.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class AccoladeArrayConstructorExpr implements Expr {

    private final Expr bodyExpr;

    /**
     * Creates an accolade array constructor.
     *
     * @param bodyExpr the enclosed expression whose result items become members
     */
    public AccoladeArrayConstructorExpr(Expr bodyExpr) {
        this.bodyExpr = bodyExpr;
    }

    @Override
    public XPathValue evaluate(XPathContext context) throws XPathException {
        XPathValue result = bodyExpr.evaluate(context);
        List<XPathValue> members = new ArrayList<XPathValue>();
        Iterator<XPathValue> iter = result.sequenceIterator();
        while (iter.hasNext()) {
            members.add(iter.next());
        }
        return new XPathArray(members);
    }

    @Override
    public String toString() {
        return "array{" + bodyExpr + "}";
    }
}

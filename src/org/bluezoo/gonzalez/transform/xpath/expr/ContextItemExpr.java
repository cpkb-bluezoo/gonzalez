/*
 * ContextItemExpr.java
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

import java.util.Collections;

import org.bluezoo.gonzalez.transform.xpath.StaticTypeContext;
import org.bluezoo.gonzalez.transform.xpath.XPathContext;
import org.bluezoo.gonzalez.transform.xpath.type.SequenceType;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNode;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNodeSet;
import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;

/**
 * An XPath expression that returns the context item ({@code .}).
 *
 * <p>Used when the context item appears in a position where it needs to
 * behave as a primary expression (e.g., dynamic function call {@code .('key')}).
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class ContextItemExpr implements Expr {

    private StaticTypeContext typeContext;

    @Override
    public void bindStaticTypes(StaticTypeContext context) {
        this.typeContext = context;
    }

    @Override
    public SequenceType getStaticType() {
        if (typeContext != null) {
            SequenceType cit = typeContext.getContextItemType();
            if (cit != null) {
                return cit;
            }
        }
        return SequenceType.ITEM;
    }

    @Override
    public XPathValue evaluate(XPathContext context) throws XPathException {
        if (context.isContextItemUndefined()) {
            throw new XPathException("XPDY0002: Context item is undefined");
        }
        XPathValue contextItem = context.getContextItem();
        if (contextItem != null) {
            return contextItem;
        }
        XPathNode node = context.getContextNode();
        if (node != null) {
            return new XPathNodeSet(Collections.singletonList(node));
        }
        return null;
    }

    @Override
    public String toString() {
        return ".";
    }
}

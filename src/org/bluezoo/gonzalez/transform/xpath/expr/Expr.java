/*
 * Expr.java
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

import org.bluezoo.gonzalez.transform.xpath.StaticTypeContext;
import org.bluezoo.gonzalez.transform.xpath.XPathContext;
import org.bluezoo.gonzalez.transform.xpath.type.SequenceType;
import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;

/**
 * Base interface for all XPath expression AST nodes.
 *
 * <p>Each expression can be evaluated against an XPath context to produce
 * a value. The expression tree is immutable after construction.
 *
 * <p>Expressions may also carry static type information inferred at compile
 * time. Call {@link #bindStaticTypes(StaticTypeContext)} after parsing to
 * propagate type context, then {@link #getStaticType()} to retrieve the
 * inferred type.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public interface Expr {

    /**
     * Evaluates this expression in the given context.
     *
     * @param context the evaluation context
     * @return the result value
     * @throws XPathException if evaluation fails
     */
    XPathValue evaluate(XPathContext context) throws XPathException;

    /**
     * Returns the statically inferred type of this expression, or null if
     * the type is unknown. A null return disables static type checking at
     * this node.
     *
     * @return the inferred sequence type, or null
     */
    default SequenceType getStaticType() {
        return null;
    }

    /**
     * Propagates static type context down the expression tree.
     * Override in nodes that need the context for type inference
     * (e.g., variable references, function calls).
     *
     * @param context the static type context
     */
    default void bindStaticTypes(StaticTypeContext context) {
    }

    /**
     * Returns a string representation of this expression for debugging.
     *
     * @return the expression as a string
     */
    @Override
    String toString();

}

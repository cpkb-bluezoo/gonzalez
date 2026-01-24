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

import org.bluezoo.gonzalez.transform.xpath.XPathContext;
import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;

/**
 * Base interface for all XPath expression AST nodes.
 *
 * <p>Each expression can be evaluated against an XPath context to produce
 * a value. The expression tree is immutable after construction.
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
     * Returns a string representation of this expression for debugging.
     *
     * @return the expression as a string
     */
    @Override
    String toString();

}

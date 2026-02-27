/*
 * ArgumentPlaceholder.java
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
import org.bluezoo.gonzalez.transform.xpath.type.XPathSequence;
import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;

/**
 * XPath 3.1 argument placeholder ({@code ?}) used in partial function application.
 *
 * <p>When {@code ?} appears as a function argument, the function call becomes
 * a partial application that returns a new function item with the placeholder
 * positions unbound.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class ArgumentPlaceholder implements Expr {

    /** Singleton instance. */
    public static final ArgumentPlaceholder INSTANCE = new ArgumentPlaceholder();

    private ArgumentPlaceholder() {
    }

    @Override
    public XPathValue evaluate(XPathContext context) throws XPathException {
        return XPathSequence.EMPTY;
    }

    @Override
    public String toString() {
        return "?";
    }
}

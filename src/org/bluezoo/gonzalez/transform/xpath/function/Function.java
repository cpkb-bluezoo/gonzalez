/*
 * Function.java
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

package org.bluezoo.gonzalez.transform.xpath.function;

import org.bluezoo.gonzalez.transform.xpath.XPathContext;
import org.bluezoo.gonzalez.transform.xpath.expr.XPathException;
import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;

import java.util.List;

/**
 * Interface for XPath functions.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public interface Function {

    /**
     * Argument type constraint for XPath 2.0+ strict type checking.
     */
    enum ArgType {
        /** Any type allowed (XPath 1.0 compatible) */
        ANY,
        /** Numeric type required (xs:integer, xs:decimal, xs:float, xs:double) */
        NUMERIC,
        /** String type required */
        STRING,
        /** Boolean type required */
        BOOLEAN,
        /** Node-set required */
        NODESET,
        /** Sequence of atomic values */
        SEQUENCE
    }

    /**
     * Returns the function name.
     *
     * @return the local name of the function
     */
    String getName();

    /**
     * Returns the minimum number of arguments.
     *
     * @return minimum argument count
     */
    int getMinArgs();

    /**
     * Returns the maximum number of arguments.
     * Return Integer.MAX_VALUE for unlimited.
     *
     * @return maximum argument count
     */
    int getMaxArgs();

    /**
     * Returns the expected argument types for XPath 2.0+ strict type checking.
     * 
     * <p>Returns null for XPath 1.0 compatible functions that accept any type.
     * The array length should match getMaxArgs(). For variable argument functions,
     * the last type applies to all remaining arguments.
     *
     * @return array of expected argument types, or null for any type
     */
    default ArgType[] getArgumentTypes() {
        return null;  // Default: no strict type checking (XPath 1.0 compatible)
    }

    /**
     * Evaluates the function with the given arguments.
     *
     * @param args the evaluated arguments
     * @param context the evaluation context
     * @return the function result
     * @throws XPathException if evaluation fails
     */
    XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException;

}

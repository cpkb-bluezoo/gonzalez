/*
 * XPathFunctionLibrary.java
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

package org.bluezoo.gonzalez.transform.xpath;

import java.util.List;
import org.bluezoo.gonzalez.transform.xpath.expr.XPathException;
import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;

/**
 * Interface for XPath function libraries.
 *
 * <p>This interface allows looking up and invoking XPath functions by name.
 * The default implementation provides the 27 core XPath 1.0 functions.
 * XSLT adds additional functions (document, key, format-number, etc.).
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public interface XPathFunctionLibrary {

    /**
     * Tests if a function with the given name exists.
     *
     * @param namespaceURI the function namespace URI (null for core functions)
     * @param localName the function local name
     * @return true if the function exists
     */
    boolean hasFunction(String namespaceURI, String localName);

    /**
     * Invokes a function with the given arguments.
     *
     * @param namespaceURI the function namespace URI (null for core functions)
     * @param localName the function local name
     * @param args the function arguments
     * @param context the evaluation context
     * @return the function result
     * @throws XPathException if the function doesn't exist or evaluation fails
     */
    XPathValue invokeFunction(String namespaceURI, String localName,
                              List<XPathValue> args, XPathContext context) throws XPathException;

    /**
     * Returns the expected number of arguments for a function.
     * Returns -1 for variable argument functions.
     *
     * @param namespaceURI the function namespace URI
     * @param localName the function local name
     * @return the argument count, or -1 for variable args
     */
    int getArgumentCount(String namespaceURI, String localName);

}

/*
 * XPathVariableException.java
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

import org.bluezoo.gonzalez.transform.xpath.expr.XPathException;

/**
 * Exception thrown when an XPath variable is not defined.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public class XPathVariableException extends XPathException {

    private static final long serialVersionUID = 1L;

    private final String namespaceURI;
    private final String localName;

    /**
     * Creates a new variable exception.
     *
     * @param namespaceURI the variable namespace URI
     * @param localName the variable local name
     */
    public XPathVariableException(String namespaceURI, String localName) {
        super(formatMessage(namespaceURI, localName));
        this.namespaceURI = namespaceURI;
        this.localName = localName;
    }

    /**
     * Returns the namespace URI of the undefined variable.
     *
     * @return the namespace URI, or null
     */
    public String getNamespaceURI() {
        return namespaceURI;
    }

    /**
     * Returns the local name of the undefined variable.
     *
     * @return the local name
     */
    public String getLocalName() {
        return localName;
    }

    private static String formatMessage(String namespaceURI, String localName) {
        if (namespaceURI == null || namespaceURI.isEmpty()) {
            return "Undefined variable: $" + localName;
        }
        return "Undefined variable: $" + "{" + namespaceURI + "}" + localName;
    }

}

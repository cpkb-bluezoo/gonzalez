/*
 * XPathException.java
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

/**
 * Exception thrown during XPath expression evaluation.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public class XPathException extends Exception {

    private static final long serialVersionUID = 1L;

    /**
     * Creates a new XPath exception.
     *
     * @param message the error message
     */
    public XPathException(String message) {
        super(message);
    }

    /**
     * Creates a new XPath exception with a cause.
     *
     * @param message the error message
     * @param cause the underlying cause
     */
    public XPathException(String message, Throwable cause) {
        super(message, cause);
    }

}

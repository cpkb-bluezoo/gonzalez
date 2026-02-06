/*
 * XPathTypeException.java
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
 * Exception thrown when a type error occurs in XPath/XSLT evaluation.
 *
 * <p>This exception is used for XPTY* (XPath type errors) and XTTE* 
 * (XSLT type errors) error codes, including:
 * <ul>
 * <li>XPTY0004 - Type does not match required type
 * <li>XTTE0505 - Required item type does not match supplied value
 * <li>XTTE0510 - Validation failure
 * <li>XTTE1510 - Element or attribute does not match required type
 * <li>XTTE1555 - Validation error for standalone attribute
 * </ul>
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public class XPathTypeException extends XPathException {

    private static final long serialVersionUID = 1L;

    private final String requiredType;
    private final String actualType;

    /**
     * Creates a new type exception with an error code and message.
     *
     * @param errorCode the error code (e.g., "XPTY0004", "XTTE0505")
     * @param message the error message
     */
    public XPathTypeException(String errorCode, String message) {
        super(errorCode, message);
        this.requiredType = null;
        this.actualType = null;
    }

    /**
     * Creates a new type exception with type information.
     *
     * @param errorCode the error code (e.g., "XPTY0004", "XTTE0505")
     * @param message the error message
     * @param requiredType the required type
     * @param actualType the actual type that was provided
     */
    public XPathTypeException(String errorCode, String message, 
                             String requiredType, String actualType) {
        super(errorCode, message);
        this.requiredType = requiredType;
        this.actualType = actualType;
    }

    /**
     * Creates a new type exception with a cause.
     *
     * @param errorCode the error code (e.g., "XPTY0004", "XTTE0505")
     * @param message the error message
     * @param cause the underlying cause
     */
    public XPathTypeException(String errorCode, String message, Throwable cause) {
        super(errorCode, message, cause);
        this.requiredType = null;
        this.actualType = null;
    }

    /**
     * Returns the required type.
     *
     * @return the required type, or null if not specified
     */
    public String getRequiredType() {
        return requiredType;
    }

    /**
     * Returns the actual type that was provided.
     *
     * @return the actual type, or null if not specified
     */
    public String getActualType() {
        return actualType;
    }

}

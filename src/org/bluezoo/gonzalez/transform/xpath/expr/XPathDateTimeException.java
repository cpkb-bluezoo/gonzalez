/*
 * XPathDateTimeException.java
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
 * Exception thrown when a date/time operation results in an overflow or invalid value.
 *
 * <p>This exception is used for FODT* (Function/Operator Date/Time) error codes:
 * <ul>
 * <li>FODT0001 - Date/time value overflow (e.g., year out of supported range)
 * <li>FODT0002 - Invalid timezone value
 * <li>FODT0003 - Invalid duration value
 * </ul>
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public class XPathDateTimeException extends XPathException {

    private static final long serialVersionUID = 1L;

    private final String value;

    /**
     * Creates a new date/time exception with an error code and message.
     *
     * @param errorCode the error code (e.g., "FODT0001")
     * @param message the error message
     */
    public XPathDateTimeException(String errorCode, String message) {
        super(errorCode, message);
        this.value = null;
    }

    /**
     * Creates a new date/time exception with the problematic value.
     *
     * @param errorCode the error code (e.g., "FODT0001")
     * @param message the error message
     * @param value the value that caused the error
     */
    public XPathDateTimeException(String errorCode, String message, String value) {
        super(errorCode, message);
        this.value = value;
    }

    /**
     * Creates a new date/time exception with a cause.
     *
     * @param errorCode the error code (e.g., "FODT0001")
     * @param message the error message
     * @param cause the underlying cause
     */
    public XPathDateTimeException(String errorCode, String message, Throwable cause) {
        super(errorCode, message, cause);
        this.value = null;
    }

    /**
     * Returns the value that caused the error.
     *
     * @return the problematic value, or null if not specified
     */
    public String getValue() {
        return value;
    }

}

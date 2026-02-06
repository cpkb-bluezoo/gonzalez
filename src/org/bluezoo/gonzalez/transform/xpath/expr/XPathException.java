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
 * <p>Supports XSLT/XPath error codes (XPST*, XPTY*, XTDE*, XTTE*, FODT*, etc.)
 * for W3C conformance test suite compatibility.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public class XPathException extends Exception {

    private static final long serialVersionUID = 1L;

    private final String errorCode;

    /**
     * Creates a new XPath exception.
     *
     * @param message the error message
     */
    public XPathException(String message) {
        super(message);
        this.errorCode = extractErrorCode(message);
    }

    /**
     * Creates a new XPath exception with an explicit error code.
     *
     * @param errorCode the error code (e.g., "XPTY0004", "XTDE0640")
     * @param message the error message
     */
    public XPathException(String errorCode, String message) {
        super(formatMessage(errorCode, message));
        this.errorCode = errorCode;
    }

    /**
     * Creates a new XPath exception with a cause.
     *
     * @param message the error message
     * @param cause the underlying cause
     */
    public XPathException(String message, Throwable cause) {
        super(message, cause);
        this.errorCode = extractErrorCode(message);
    }

    /**
     * Creates a new XPath exception with an explicit error code and cause.
     *
     * @param errorCode the error code (e.g., "XPTY0004", "XTDE0640")
     * @param message the error message
     * @param cause the underlying cause
     */
    public XPathException(String errorCode, String message, Throwable cause) {
        super(formatMessage(errorCode, message), cause);
        this.errorCode = errorCode;
    }

    /**
     * Returns the error code for this exception.
     *
     * @return the error code (e.g., "XPTY0004"), or null if no code was specified
     */
    public String getErrorCode() {
        return errorCode;
    }

    /**
     * Extracts an error code from a message that starts with "CODE: message".
     *
     * @param message the message to parse
     * @return the extracted error code, or null if not found
     */
    private static String extractErrorCode(String message) {
        if (message == null) {
            return null;
        }
        // Match pattern like "XPTY0004: " or "XTDE0640: "
        if (message.length() > 10 && message.charAt(4) == ':' && message.charAt(9) == ':') {
            String potentialCode = message.substring(0, 9);
            if (isValidErrorCode(potentialCode)) {
                return potentialCode;
            }
        }
        return null;
    }

    /**
     * Checks if a string is a valid XSLT/XPath error code.
     *
     * @param code the code to check
     * @return true if valid error code format
     */
    private static boolean isValidErrorCode(String code) {
        if (code == null || code.length() != 9) {
            return false;
        }
        // Check format: 4 letters + 4 digits + optional trailing char
        for (int i = 0; i < 4; i++) {
            if (!Character.isLetter(code.charAt(i))) {
                return false;
            }
        }
        for (int i = 4; i < 8; i++) {
            if (!Character.isDigit(code.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Formats a message with an error code prefix if not already present.
     *
     * @param errorCode the error code
     * @param message the message
     * @return the formatted message
     */
    private static String formatMessage(String errorCode, String message) {
        if (errorCode == null) {
            return message;
        }
        if (message != null && message.startsWith(errorCode + ":")) {
            return message;
        }
        return errorCode + ": " + (message != null ? message : "");
    }

}

/*
 * XPathSyntaxException.java
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

/**
 * Exception thrown when an XPath expression has invalid syntax.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public class XPathSyntaxException extends Exception {

    private static final long serialVersionUID = 1L;

    private final String expression;
    private final int position;

    /**
     * Creates a new XPath syntax exception.
     *
     * @param message the error message
     */
    public XPathSyntaxException(String message) {
        super(message);
        this.expression = null;
        this.position = -1;
    }

    /**
     * Creates a new XPath syntax exception with expression context.
     *
     * @param message the error message
     * @param expression the XPath expression
     * @param position the error position in the expression
     */
    public XPathSyntaxException(String message, String expression, int position) {
        super(formatMessage(message, expression, position));
        this.expression = expression;
        this.position = position;
    }

    /**
     * Creates a new XPath syntax exception with a cause.
     *
     * @param message the error message
     * @param cause the underlying cause
     */
    public XPathSyntaxException(String message, Throwable cause) {
        super(message, cause);
        this.expression = null;
        this.position = -1;
    }

    /**
     * Returns the XPath expression that had the syntax error.
     *
     * @return the expression, or null if not available
     */
    public String getExpression() {
        return expression;
    }

    /**
     * Returns the position in the expression where the error occurred.
     *
     * @return the position, or -1 if not available
     */
    public int getPosition() {
        return position;
    }

    private static String formatMessage(String message, String expression, int position) {
        if (expression == null || position < 0) {
            return message;
        }
        
        StringBuilder sb = new StringBuilder();
        sb.append(message);
        sb.append("\n  Expression: ").append(expression);
        sb.append("\n  Position:   ");
        for (int i = 0; i < position; i++) {
            sb.append(' ');
        }
        sb.append('^');
        
        return sb.toString();
    }

}

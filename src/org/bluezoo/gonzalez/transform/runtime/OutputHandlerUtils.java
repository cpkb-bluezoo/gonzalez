/*
 * OutputHandlerUtils.java
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

package org.bluezoo.gonzalez.transform.runtime;

/**
 * Utility methods for output handlers.
 * Provides common operations for QName processing and XML escaping.
 *
 * @author Chris Burdess
 */
public final class OutputHandlerUtils {

    /**
     * Private constructor to prevent instantiation.
     */
    private OutputHandlerUtils() {
    }

    /**
     * Extracts the prefix from a qualified name.
     * Returns null if the qualified name has no prefix.
     *
     * @param qName the qualified name
     * @return the prefix, or null if no prefix
     */
    public static String extractPrefix(String qName) {
        if (qName == null) {
            return null;
        }
        int colon = qName.indexOf(':');
        if (colon > 0) {
            return qName.substring(0, colon);
        }
        return null;
    }

    /**
     * Checks if a prefix is the reserved XML prefix.
     * The xml prefix is implicitly bound and should not be declared.
     *
     * @param prefix the prefix to check
     * @return true if the prefix is "xml"
     */
    public static boolean isXmlPrefix(String prefix) {
        return "xml".equals(prefix);
    }

    /**
     * Escapes special characters in XML attribute values.
     * Replaces &amp;, &lt;, and &quot; with their entity references.
     *
     * @param s the string to escape
     * @return the escaped string
     */
    public static String escapeXmlAttribute(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&':
                    sb.append("&amp;");
                    break;
                case '<':
                    sb.append("&lt;");
                    break;
                case '"':
                    sb.append("&quot;");
                    break;
                default:
                    sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * Escapes special characters in XML text content.
     * Replaces &amp;, &lt;, and &gt; with their entity references.
     *
     * @param s the string to escape
     * @return the escaped string
     */
    public static String escapeXmlText(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&':
                    sb.append("&amp;");
                    break;
                case '<':
                    sb.append("&lt;");
                    break;
                case '>':
                    sb.append("&gt;");
                    break;
                default:
                    sb.append(c);
            }
        }
        return sb.toString();
    }
}

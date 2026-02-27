/*
 * XSDUtils.java
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

package org.bluezoo.gonzalez.schema.xsd;

import java.util.ArrayList;
import java.util.List;

/**
 * Utility methods for XML Schema processing.
 * Provides common operations for QName parsing and namespace handling.
 *
 * @author Chris Burdess
 */
public final class XSDUtils {

    /**
     * Private constructor to prevent instantiation.
     */
    private XSDUtils() {
    }

    /**
     * Extracts the local name from a qualified name.
     * If the qualified name has no prefix, returns the entire name.
     *
     * @param qName the qualified name
     * @return the local name
     */
    public static String extractLocalName(String qName) {
        if (qName == null) {
            return null;
        }
        int colon = qName.indexOf(':');
        if (colon >= 0) {
            return qName.substring(colon + 1);
        }
        return qName;
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
        if (colon >= 0) {
            return qName.substring(0, colon);
        }
        return null;
    }

    /**
     * Splits a string on XML whitespace without using regex.
     * Equivalent to {@code value.split("\\s+")} but avoids regex overhead.
     *
     * @param value the string to split
     * @return array of non-empty tokens
     */
    public static String[] splitWhitespace(String value) {
        if (value == null || value.isEmpty()) {
            return new String[0];
        }
        List<String> tokens = new ArrayList<>();
        int len = value.length();
        int start = 0;
        while (start < len) {
            // Skip whitespace
            while (start < len && isXmlWhitespace(value.charAt(start))) {
                start++;
            }
            if (start >= len) {
                break;
            }
            // Find end of token
            int end = start;
            while (end < len && !isXmlWhitespace(value.charAt(end))) {
                end++;
            }
            tokens.add(value.substring(start, end));
            start = end;
        }
        return tokens.toArray(new String[tokens.size()]);
    }

    private static boolean isXmlWhitespace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r';
    }
}

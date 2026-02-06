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
}

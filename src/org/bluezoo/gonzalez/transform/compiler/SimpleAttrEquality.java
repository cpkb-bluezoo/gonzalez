/*
 * SimpleAttrEquality.java
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

package org.bluezoo.gonzalez.transform.compiler;

/**
 * A pattern predicate that is exactly {@code @attr = 'literal'} (or
 * {@code 'literal' = @attr} / {@code eq}), used to index and fast-match
 * template rules.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class SimpleAttrEquality {

    private final String namespaceURI;
    private final String localName;
    private final String value;

    SimpleAttrEquality(String namespaceURI, String localName, String value) {
        this.namespaceURI = namespaceURI == null ? "" : namespaceURI;
        this.localName = localName;
        this.value = value;
    }

    /**
     * Returns the attribute namespace URI (empty string for no namespace).
     */
    public String getNamespaceURI() {
        return namespaceURI;
    }

    /**
     * Returns the attribute local name.
     */
    public String getLocalName() {
        return localName;
    }

    /**
     * Returns the compared string literal.
     */
    public String getValue() {
        return value;
    }

    /**
     * Returns a map key for template indexing:
     * {@code namespaceURI + '\0' + localName + '\0' + value}.
     */
    public String indexKey() {
        return namespaceURI + '\0' + localName + '\0' + value;
    }

    /**
     * Builds the same index key for a node attribute.
     */
    public static String indexKey(String namespaceURI, String localName,
                                  String value) {
        String ns = namespaceURI == null ? "" : namespaceURI;
        return ns + '\0' + localName + '\0' + value;
    }
}

/*
 * NativeExpandedNames.java
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

import java.util.Map;

import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

/**
 * Shared expanded-name resolution for Gonzalez-native XMLHandler consumers.
 *
 * <p>NamespaceFilter delivers raw qNames plus {@code namespace(prefix,uri)}
 * events. Resolution must wait until {@code endAttributes()} so declarations
 * anywhere on the start tag bind correctly - the same rule SAXAdapter applies
 * before firing SAX startElement.
 */
public final class NativeExpandedNames {

    private NativeExpandedNames() {
    }

    public static String extractPrefix(String qName) {
        int colon = qName.indexOf(':');
        return colon > 0 ? qName.substring(0, colon) : null;
    }

    public static String extractLocalName(String qName) {
        int colon = qName.indexOf(':');
        return colon > 0 ? qName.substring(colon + 1) : qName;
    }

    public static boolean isNamespaceDeclaration(String qName) {
        return "xmlns".equals(qName) || qName.startsWith("xmlns:");
    }

    public static String resolveNamespaceURI(String prefix, boolean attribute,
            Map<String, String> namespaceBindings) throws SAXException {
        if (prefix == null || prefix.isEmpty()) {
            if (attribute) {
                return "";
            }
            String uri = namespaceBindings.get("");
            return uri != null ? uri : "";
        }
        if ("xml".equals(prefix)) {
            return "http://www.w3.org/XML/1998/namespace";
        }
        String uri = namespaceBindings.get(prefix);
        if (uri == null || uri.isEmpty()) {
            throw new SAXParseException("Unbound namespace prefix: " + prefix, null);
        }
        return uri;
    }
}

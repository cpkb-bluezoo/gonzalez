/*
 * SecureGonzalezParser.java
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

import org.bluezoo.gonzalez.XMLHandler;
import org.bluezoo.gonzalez.XMLParser;

/**
 * Factory for Gonzalez parsers used to load secondary transform documents.
 *
 * <p>Matches the secure defaults DocumentLoader previously applied through
 * the platform SAXParserFactory: namespaces on, and no external entity or
 * external DTD access. These are the native parser's own defaults; they
 * are set explicitly here so that this class states what it relies on.
 */
public final class SecureGonzalezParser {

    private SecureGonzalezParser() {
    }

    public static XMLParser create(XMLHandler handler) {
        XMLParser parser = new XMLParser();
        parser.setNamespaceAware(true);
        parser.setExternalGeneralEntities(false);
        parser.setExternalParameterEntities(false);
        parser.setAccessExternalDTD("");
        parser.setHandler(handler);
        return parser;
    }
}

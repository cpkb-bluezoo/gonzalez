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

import org.bluezoo.gonzalez.Parser;
import org.bluezoo.gonzalez.XMLHandler;
import org.xml.sax.SAXException;

/**
 * Factory for Gonzalez parsers used to load secondary transform documents.
 *
 * <p>Matches the secure defaults DocumentLoader previously applied through
 * the platform SAXParserFactory: namespaces on, secure processing on, and
 * external entities off.
 */
public final class SecureGonzalezParser {

    private SecureGonzalezParser() {
    }

    public static Parser create(XMLHandler handler) throws SAXException {
        Parser parser = new Parser();
        parser.setFeature("http://xml.org/sax/features/namespaces", true);
        parser.setFeature(
                "http://javax.xml.XMLConstants/feature/secure-processing", true);
        parser.setFeature(
                "http://xml.org/sax/features/external-general-entities", false);
        parser.setFeature(
                "http://xml.org/sax/features/external-parameter-entities", false);
        parser.setXMLHandler(handler);
        return parser;
    }
}

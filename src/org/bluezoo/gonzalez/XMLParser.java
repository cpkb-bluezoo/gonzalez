/*
 * XMLParser.java
 * Copyright (C) 2025 Chris Burdess
 *
 * This file is part of Gonzalez, a streaming XML parser.
 * For more information please visit https://www.nongnu.org/gonzalez/
 *
 * Gonzalez is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Gonzalez is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Gonzalez.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.bluezoo.gonzalez;

import javax.nio.CharBuffer;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;

/**
 * XML parser.
 * This is a consumer of a stream of tokens.
 * Each event informs the parser of a token type and the content of the
 * token.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class XMLParser {

    enum State {
        INIT,
        ELEMENT_NAME_OR_PREFIX,
        ELEMENT_NAME,
        ELEMENT_NAME_COMPLETE, // attribute/GT/END_EMPTY_ELEMENT
        ATTRIBUTE_NAME_OR_PREFIX,
        ATTRIBUTE_NAME,
        ATTRIBUTE_NAME_COMPLETE,
        ATTRIBUTE_EQ_COMPLETE,
        ATTRIBUTE_CONTENT,
        ELEMENT_CONTENT,
        PI_TARGET,
        DOCTYPE
    }

    private State state = State.INIT;
    private Locator locator;

    void setLocator(Locator locator) {
        this.locator = locator;
    }

    /**
     * Receive a token.
     * In most cases the token type is all that is important and we don't
     * need to examine the data.
     * For NAME, S, and CDATA token types the data will be relevant and will
     * need to be processed.
     * @param token the token type
     * @param data the token data
     */
    public void receive(Token token, CharBuffer data) throws SAXParseException {
        // TODO
    }

}

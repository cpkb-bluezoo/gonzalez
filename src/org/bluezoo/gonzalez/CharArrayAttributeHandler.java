/*
 * CharArrayAttributeHandler.java
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

package org.bluezoo.gonzalez;

import org.xml.sax.SAXException;

/**
 * An {@link XMLHandler} within this package that can take a chunk of an
 * attribute value as a range of the scanner's buffer, without the scanner
 * first wrapping it in a {@link java.nio.CharBuffer}.
 *
 * <p>This is the same event as {@link XMLHandler#attributeValueContent},
 * with the same meaning of {@code end}, and the same rule that the range is
 * the scanner's own storage, valid only for the duration of the call. It
 * exists so that the handlers inside this package, which sit between the
 * scanner and every SAX consumer, do not cost an object for each attribute
 * value. A handler outside the package, which cannot implement it, gets the
 * {@code CharBuffer} as before.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
interface CharArrayAttributeHandler {

    /**
     * Reports a chunk of the current attribute's value.
     *
     * @param chars the scanner's buffer
     * @param start the index of the first character of the chunk
     * @param length the number of characters in the chunk
     * @param end true if this completes the value
     * @throws SAXException if the handler rejects the value
     */
    void attributeValueChars(char[] chars, int start, int length, boolean end) throws SAXException;
}

/*
 * CharArrayHandler.java
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
 * An {@link XMLHandler} within this package that can take text, and the
 * values of attributes, as ranges of the scanner's buffer, without the
 * scanner first wrapping each in a {@link java.nio.CharBuffer}.
 *
 * <p>These are the same events as {@link XMLHandler#characters} and {@link
 * XMLHandler#attributeValueContent}, with the same meaning of {@code end},
 * and the same rule that the range is the scanner's own storage, valid only
 * for the duration of the call. They exist so that the handlers inside this
 * package, which sit between the scanner and every SAX consumer, do not cost
 * an object for each run of text and each attribute value. A handler outside
 * the package, which cannot implement this, gets the {@code CharBuffer} as
 * before.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
interface CharArrayHandler {

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

    /**
     * Whether this handler wants text and whole attributes as ranges, by
     * {@link #characters} and {@link #attribute}. A filter that would only
     * turn them back into the events that carry a {@code CharBuffer}, for a
     * handler behind it that takes those, says no, so that the scanner
     * reports them as it always has.
     *
     * @return true if those are the better way to report them
     */
    boolean takesCharArrays();

    /**
     * Reports a chunk of character data, as {@link XMLHandler#characters}
     * does.
     *
     * @param chars the scanner's buffer
     * @param start the index of the first character of the chunk
     * @param length the number of characters in the chunk
     * @param ignorable true if it is white space that a DTD says is ignorable
     * @param end true if this completes the run of text
     * @throws SAXException if the handler rejects the text
     */
    void characters(char[] chars, int start, int length, boolean ignorable, boolean end)
            throws SAXException;

    /**
     * Reports a whole attribute at once: the events {@link
     * XMLHandler#startAttribute(String, String, boolean, boolean)} and one
     * chunk, the last, of its value. The scanner uses this when the value is
     * entirely in its buffer and needs nothing done to it (no reference to
     * resolve, no white space to fold, no type to normalise it by), which
     * is nearly every attribute, so that the attribute costs one call
     * and not two, and nothing need be remembered between them. The range
     * is the scanner's own storage, as for {@link #attributeValueChars}.
     *
     * @param name the name of the attribute
     * @param type its declared type, or {@code "CDATA"}
     * @param declared whether it is declared in the DTD
     * @param specified whether it was specified in the document
     * @param chars the scanner's buffer
     * @param start the index of the first character of the value
     * @param length the number of characters in the value
     * @throws SAXException if the handler rejects the attribute
     */
    void attribute(String name, String type, boolean declared, boolean specified,
            char[] chars, int start, int length) throws SAXException;
}

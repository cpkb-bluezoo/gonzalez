/*
 * ByteDecoderTarget.java
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

import java.nio.CharBuffer;

import org.xml.sax.SAXException;

/**
 * The narrow contract {@link ExternalEntityDecoder} needs from whatever
 * consumes its decoded characters - implemented by both {@link Tokenizer}
 * (the old pipeline) and {@link Scanner} (the new one), so a single {@code
 * ExternalEntityDecoder} instance can drive either, chosen by {@link
 * Parser.Pipeline}. Deliberately minimal: {@code ExternalEntityDecoder}'s
 * richer, Tokenizer-specific bookkeeping (location tracking, {@code
 * standalone}/{@code documentVersion} storage, {@code publicId}/{@code
 * systemId} fields) stays as direct {@code Tokenizer}-typed access, guarded
 * by an {@code instanceof} check, rather than being forced into this
 * interface - {@link Scanner} doesn't support any of that yet (a known,
 * documented gap; see its class Javadoc), so widening this interface to
 * cover it would mean either fake no-op methods on {@code Scanner} or a
 * pile of default methods nothing meaningfully implements twice.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
interface ByteDecoderTarget {

    void receive(CharBuffer data) throws SAXException;

    SAXException fatalError(String message) throws SAXException;

    void close() throws SAXException;

    /** Called once the XML/text declaration's version has been resolved
     *  from the raw bytes, before any character reaches this target -
     *  matching the existing {@code Tokenizer.xml11}-field-write timing
     *  exactly, just via a method so {@link Scanner} (whose {@code xml11}-
     *  derived per-character lookup tables need to stay in sync) can react
     *  too. */
    void setXml11(boolean xml11);

}

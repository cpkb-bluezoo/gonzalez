/*
 * Utf16FixtureGenerator.java
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

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Generates UTF-16BE/LE (with or without a byte order mark) byte encodings of
 * an existing UTF-8 XML fixture, for use as a baseline of today's legacy
 * tokenizer's transcode behavior ahead of the byte-native tokenizer rewrite
 * (see BYTE-TOKENIZER.md, Decision 2).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class Utf16FixtureGenerator {

    private static final byte[] BOM_BE = {(byte) 0xFE, (byte) 0xFF};
    private static final byte[] BOM_LE = {(byte) 0xFF, (byte) 0xFE};

    private Utf16FixtureGenerator() {
    }

    /**
     * Converts a UTF-8 XML document to UTF-16 big-endian.
     *
     * @param utf8Source the source document, encoded as UTF-8
     * @param includeBom whether to prepend the UTF-16BE byte order mark
     * @return the same document, encoded as UTF-16BE
     */
    static byte[] toUtf16BE(byte[] utf8Source, boolean includeBom) {
        return convert(utf8Source, StandardCharsets.UTF_16BE, includeBom ? BOM_BE : null);
    }

    /**
     * Converts a UTF-8 XML document to UTF-16 little-endian.
     *
     * @param utf8Source the source document, encoded as UTF-8
     * @param includeBom whether to prepend the UTF-16LE byte order mark
     * @return the same document, encoded as UTF-16LE
     */
    static byte[] toUtf16LE(byte[] utf8Source, boolean includeBom) {
        return convert(utf8Source, StandardCharsets.UTF_16LE, includeBom ? BOM_LE : null);
    }

    private static byte[] convert(byte[] utf8Source, Charset targetCharset, byte[] bom) {
        String text = new String(utf8Source, StandardCharsets.UTF_8);
        text = replaceDeclaredEncoding(text, "UTF-16");
        byte[] body = text.getBytes(targetCharset);
        if (bom == null) {
            return body;
        }
        byte[] result = new byte[bom.length + body.length];
        System.arraycopy(bom, 0, result, 0, bom.length);
        System.arraycopy(body, 0, result, bom.length, body.length);
        return result;
    }

    /**
     * Replaces the value of the encoding pseudo-attribute in a leading XML
     * declaration, if present, with the given encoding name. Uses plain string
     * search rather than java.util.regex, which this project's coding
     * standard forbids.
     *
     * @param text the document text
     * @param newEncoding the replacement encoding name
     * @return the document text with its declared encoding replaced, or
     *         unchanged if there is no XML declaration or encoding attribute
     */
    private static String replaceDeclaredEncoding(String text, String newEncoding) {
        if (!text.startsWith("<?xml")) {
            return text;
        }
        int declEnd = text.indexOf("?>");
        if (declEnd < 0) {
            return text;
        }
        String decl = text.substring(0, declEnd);
        int encAttr = decl.indexOf("encoding");
        if (encAttr < 0) {
            return text;
        }
        int eq = decl.indexOf('=', encAttr);
        if (eq < 0) {
            return text;
        }
        int valueStart = eq + 1;
        while (valueStart < decl.length() && Character.isWhitespace(decl.charAt(valueStart))) {
            valueStart++;
        }
        if (valueStart >= decl.length()) {
            return text;
        }
        char quote = decl.charAt(valueStart);
        if (quote != '"' && quote != '\'') {
            return text;
        }
        int valueEnd = decl.indexOf(quote, valueStart + 1);
        if (valueEnd < 0) {
            return text;
        }
        String newDecl = decl.substring(0, valueStart + 1) + newEncoding + decl.substring(valueEnd);
        return newDecl + text.substring(declEnd);
    }

}

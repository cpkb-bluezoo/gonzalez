/*
 * XMLWriterLongContentTest.java
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
 */

package org.bluezoo.gonzalez;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Content longer than the writer's buffer is written whole and correctly,
 * whatever it is made of. The interesting characters are those outside
 * ASCII, which take more than one byte each and so can reach the end of
 * the buffer part way through.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
// The whole-value write methods are deprecated but still supported, so they stay covered.
@SuppressWarnings("deprecation")
public class XMLWriterLongContentTest {

    /** Several times the writer's default buffer of 4096 bytes. */
    private static final int LONG = 5000;

    /** e-acute, the euro sign and an emoji: two, three and four bytes in
     *  UTF-8, the last a surrogate pair. */
    private static final int[] CODE_POINTS = { 0xE9, 0x20AC, 0x1F600 };

    private static final int TEXT = 0;
    private static final int ATTRIBUTE = 1;
    private static final int NAMESPACE = 2;
    private static final int ENTITY_VALUE = 3;
    private static final int ATTRIBUTE_DEFAULT = 4;

    private static final String[] PLACES = { "text", "attribute value", "namespace name", "entity value",
        "attribute default" };

    @Test
    public void testLongNonAsciiContentInEveryPlace() throws Exception {
        List<Charset> charsets = charsets();
        for (int c = 0; c < charsets.size(); c++) {
            for (int k = 0; k < CODE_POINTS.length; k++) {
                String content = repeat(CODE_POINTS[k], LONG);
                for (int place = 0; place < PLACES.length; place++) {
                    check(charsets.get(c), place, content);
                }
            }
        }
    }

    /**
     * The same, with every alignment of the multi-byte characters against
     * the end of the buffer, and with ASCII among them.
     */
    @Test
    public void testEveryAlignmentAgainstTheBuffer() throws Exception {
        for (int k = 0; k < CODE_POINTS.length; k++) {
            for (int padding = 0; padding < 8; padding++) {
                StringBuilder content = new StringBuilder();
                for (int i = 0; i < padding; i++) {
                    content.append('x');
                }
                content.append(repeat(CODE_POINTS[k], LONG));
                check(StandardCharsets.UTF_8, TEXT, content.toString());
                check(StandardCharsets.UTF_8, ATTRIBUTE, content.toString());
            }
            StringBuilder mixed = new StringBuilder();
            for (int i = 0; i < LONG; i++) {
                mixed.appendCodePoint(CODE_POINTS[k]);
                if (i % 7 == 0) {
                    mixed.append(' ');
                }
                if (i % 11 == 0) {
                    mixed.append("ab");
                }
            }
            check(StandardCharsets.UTF_8, TEXT, mixed.toString());
            check(StandardCharsets.UTF_8, ATTRIBUTE, mixed.toString());
        }
    }

    /** A small buffer meets the end far more often. */
    @Test
    public void testSmallBuffer() throws Exception {
        for (int capacity = 16; capacity <= 64; capacity++) {
            for (int k = 0; k < CODE_POINTS.length; k++) {
                String content = repeat(CODE_POINTS[k], 200);
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                XMLWriter writer = new XMLWriter(new XMLWriter.OutputStreamChannel(out), capacity);
                writer.writeStartElement("r");
                writer.writeAttribute("a", content);
                writer.writeCharacters(content);
                writer.writeEndElement();
                writer.close();
                assertEquals("capacity " + capacity, "<r a=\"" + content + "\">" + content + "</r>",
                        new String(out.toByteArray(), StandardCharsets.UTF_8));
            }
        }
    }

    // ===== Helpers =====

    private static List<Charset> charsets() {
        List<Charset> charsets = new ArrayList<Charset>();
        charsets.add(StandardCharsets.UTF_8);
        charsets.add(StandardCharsets.ISO_8859_1);
        charsets.add(StandardCharsets.US_ASCII);
        charsets.add(StandardCharsets.UTF_16BE);
        charsets.add(StandardCharsets.UTF_16LE);
        // Charsets the writer has no special case for.
        String[] others = { "windows-1252", "ISO-8859-15", "UTF-32BE" };
        for (int i = 0; i < others.length; i++) {
            if (Charset.isSupported(others[i])) {
                charsets.add(Charset.forName(others[i]));
            }
        }
        return charsets;
    }

    private static String repeat(int codePoint, int count) {
        StringBuilder builder = new StringBuilder(count * 2);
        for (int i = 0; i < count; i++) {
            builder.appendCodePoint(codePoint);
        }
        return builder.toString();
    }

    /**
     * Writes {@code content} in the given place, in one call, and checks
     * that what comes out is the content, with character references for
     * whatever the charset cannot represent.
     */
    private static void check(Charset charset, int place, String content) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        XMLWriter writer = new XMLWriter(out);
        writer.setCharset(charset);
        String before;
        String after;
        switch (place) {
            case TEXT:
                writer.writeStartElement("r");
                writer.writeCharacters(content);
                writer.writeEndElement();
                before = "<r>";
                after = "</r>";
                break;
            case ATTRIBUTE:
                writer.writeStartElement("r");
                writer.writeAttribute("a", content);
                writer.writeEndElement();
                before = "<r a=\"";
                after = "\"/>";
                break;
            case NAMESPACE:
                writer.writeStartElement("r");
                writer.writeNamespace("p", content);
                writer.writeEndElement();
                before = "<r xmlns:p=\"";
                after = "\"/>";
                break;
            case ENTITY_VALUE:
                writer.writeStartDTD("r", null, null);
                writer.writeInternalEntityDecl("e", content);
                writer.writeEndDTD();
                before = "<!DOCTYPE r [\n  <!ENTITY e \"";
                after = "\">\n]>";
                break;
            default:
                writer.writeStartDTD("r", null, null);
                writer.writeAttributeDecl("r", "a", "CDATA", null, content);
                writer.writeEndDTD();
                before = "<!DOCTYPE r [\n  <!ATTLIST r a CDATA \"";
                after = "\">\n]>";
                break;
        }
        writer.close();
        String written = new String(out.toByteArray(), charset);
        if (written.length() > 0 && written.charAt(0) == (char) 0xFEFF) {
            written = written.substring(1);
        }
        String label = PLACES[place] + " in " + charset + " of U+"
                + Integer.toHexString(content.codePointAt(content.length() - 2)).toUpperCase();
        assertEquals(label, before + represent(content, charset) + after, written);
    }

    /** The content as it should appear in the charset: itself where it
     *  can be encoded, a decimal character reference where it cannot. */
    private static String represent(String content, Charset charset) {
        CharsetEncoder encoder = charset.newEncoder();
        StringBuilder builder = new StringBuilder(content.length());
        for (int i = 0; i < content.length(); ) {
            int codePoint = content.codePointAt(i);
            int count = Character.charCount(codePoint);
            if (codePoint < 0x80 || encoder.canEncode(content.substring(i, i + count))) {
                builder.appendCodePoint(codePoint);
            } else {
                builder.append("&#").append(codePoint).append(';');
            }
            i += count;
        }
        return builder.toString();
    }

}

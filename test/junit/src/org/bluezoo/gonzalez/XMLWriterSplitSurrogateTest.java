/*
 * XMLWriterSplitSurrogateTest.java
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
import java.nio.charset.StandardCharsets;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

/**
 * Character data may be written in as many calls as the caller likes, and
 * where one call ends is up to the caller: a SAX source is free to end a
 * {@code characters()} event between the two halves of a surrogate pair.
 * The pair is still one character and must be written as one.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class XMLWriterSplitSurrogateTest {

    /** A character outside the Basic Multilingual Plane: two chars. */
    private static final String EMOJI = new String(Character.toChars(0x1F600));

    private static final String TEXT = "a" + EMOJI + EMOJI + "b" + (char) 0xE9 + EMOJI;

    private static final Charset[] CHARSETS = { StandardCharsets.UTF_8, StandardCharsets.UTF_16BE,
        StandardCharsets.ISO_8859_1, StandardCharsets.US_ASCII };

    private static final int STRING = 0;
    private static final int ARRAY = 1;
    /** One array holding everything, each call given part of it: what a
     *  parser passing on its own buffer does. */
    private static final int SHARED_ARRAY = 2;

    @Test
    public void testPairSplitAcrossTwoCalls() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        XMLWriter writer = new XMLWriter(out);
        writer.writeStartElement("r");
        writer.writeCharacters(EMOJI.substring(0, 1));
        writer.writeCharacters(EMOJI.substring(1));
        writer.writeEndElement();
        writer.close();
        assertEquals("<r>" + EMOJI + "</r>", new String(out.toByteArray(), StandardCharsets.UTF_8));
        assertArrayEquals(("<r>" + EMOJI + "</r>").getBytes(StandardCharsets.UTF_8), out.toByteArray());
    }

    @Test
    public void testTextSplitAnywhereIsWrittenTheSame() throws Exception {
        for (int c = 0; c < CHARSETS.length; c++) {
            for (int form = STRING; form <= SHARED_ARRAY; form++) {
                byte[] whole = write(CHARSETS[c], form, false, TEXT, TEXT.length());
                for (int split = 1; split < TEXT.length(); split++) {
                    assertArrayEquals(CHARSETS[c] + " form " + form + " split at " + split, whole,
                            write(CHARSETS[c], form, false, TEXT, split));
                }
            }
        }
    }

    @Test
    public void testCDATASplitAnywhereIsWrittenTheSame() throws Exception {
        for (int c = 0; c < CHARSETS.length; c++) {
            for (int form = STRING; form <= SHARED_ARRAY; form++) {
                byte[] whole = write(CHARSETS[c], form, true, TEXT, TEXT.length());
                for (int split = 1; split < TEXT.length(); split++) {
                    assertArrayEquals(CHARSETS[c] + " form " + form + " split at " + split, whole,
                            write(CHARSETS[c], form, true, TEXT, split));
                }
            }
        }
    }

    @Test
    public void testOneCharacterAtATime() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        XMLWriter writer = new XMLWriter(out);
        writer.writeStartElement("r");
        for (int i = 0; i < TEXT.length(); i++) {
            writer.writeCharacters(TEXT.substring(i, i + 1));
        }
        writer.writeEndElement();
        writer.close();
        assertEquals("<r>" + TEXT + "</r>", new String(out.toByteArray(), StandardCharsets.UTF_8));
    }

    /** Sending what has been written so far does not come between the
     *  two halves. */
    @Test
    public void testFlushBetweenTheHalves() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        XMLWriter writer = new XMLWriter(out);
        writer.writeStartElement("r");
        writer.writeCharacters("a" + EMOJI.substring(0, 1));
        writer.flush();
        assertEquals("<r>a", new String(out.toByteArray(), StandardCharsets.UTF_8));
        writer.writeCharacters(EMOJI.substring(1) + "b");
        writer.writeEndElement();
        writer.close();
        assertEquals("<r>a" + EMOJI + "b</r>", new String(out.toByteArray(), StandardCharsets.UTF_8));
    }

    /**
     * A high surrogate with nothing to pair with is not a character, and
     * splitting the text does not change what is made of it: it comes out
     * where it was, as it would have in one call.
     */
    @Test
    public void testUnpairedSurrogateIsWrittenWhereItWas() throws Exception {
        String high = EMOJI.substring(0, 1);
        String[] texts = { "a" + high + "b", "a" + high, high };
        for (int t = 0; t < texts.length; t++) {
            String text = texts[t];
            for (int form = STRING; form <= SHARED_ARRAY; form++) {
                byte[] whole = write(StandardCharsets.UTF_8, form, false, text, text.length());
                for (int split = 1; split < text.length(); split++) {
                    assertArrayEquals("form " + form + " split at " + split, whole,
                            write(StandardCharsets.UTF_8, form, false, text, split));
                }
            }
        }
        // Followed by markup and not by more text.
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        XMLWriter writer = new XMLWriter(out);
        writer.writeStartElement("r");
        writer.writeCharacters("a" + high);
        writer.writeStartElement("s");
        writer.writeEndElement();
        writer.writeCharacters(EMOJI.substring(1));
        writer.writeEndElement();
        writer.close();
        String written = new String(out.toByteArray(), StandardCharsets.ISO_8859_1);
        assertEquals("The element comes after the first half and before the second: " + written,
                true, written.indexOf("<s/>") > written.indexOf('a') + 1
                        && written.indexOf("<s/>") + 4 < written.indexOf("</r>"));
    }

    // ===== Helpers =====

    /**
     * Writes {@code text} as the content of an element, in two calls
     * divided at {@code split} (or in one, if that is its length).
     */
    private static byte[] write(Charset charset, int form, boolean cdata, String text, int split)
            throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        XMLWriter writer = new XMLWriter(out);
        writer.setCharset(charset);
        writer.writeStartElement("r");
        if (cdata) {
            writer.writeStartCDATA();
        }
        writePart(writer, form, text, 0, split);
        if (split < text.length()) {
            writePart(writer, form, text, split, text.length());
        }
        if (cdata) {
            writer.writeEndCDATA();
        }
        writer.writeEndElement();
        writer.close();
        return out.toByteArray();
    }

    private static void writePart(XMLWriter writer, int form, String text, int start, int end) throws Exception {
        switch (form) {
            case STRING:
                writer.writeCharacters(text.substring(start, end));
                break;
            case ARRAY:
                writer.writeCharacters(text.substring(start, end).toCharArray(), 0, end - start);
                break;
            default:
                writer.writeCharacters(text.toCharArray(), start, end - start);
                break;
        }
    }

}

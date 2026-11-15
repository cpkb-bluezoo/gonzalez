/*
 * TagChunkingTest.java
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

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;
import org.xml.sax.SAXException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * White space inside a tag is consumed as it is scanned, and the scanner
 * keeps only where in the tag it has got to. These tests split tags at
 * every position, and in every pair of positions, to check that the place
 * is kept correctly: the same events, and for a malformed tag the same
 * error, however the input is divided.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class TagChunkingTest {

    private static final String[] WELL_FORMED = {
        "<r a=\"1\"/>",
        "<r  a = \"1\"  b\t=\n'2' ></r >",
        "<r\n\ta\n\t=\n\t\"1\"\n/>",
        "<r a=\"\" b=''><s  /><t c = \"x&amp;y\"\t>text</t\n></r\t\n >",
        "<r xmlns = \"urn:d\"  xmlns:p\t= 'urn:p' p:a = \"1\"><p:s  p:b= '2'/></r>",
        "<r   >  <s   ></s   >  </r   >",
        "<r a=\"1\"\n>t</r>",
    };

    private static final String[] MALFORMED = {
        "<r a=\"1\"b=\"2\"/>",
        "<r a=\"1\" b=\"2\"c=\"3\"/>",
        "<r a = \"1\" a = \"2\"/>",
        "<r a \"1\"/>",
        "<r a = 1/>",
        "<r a = />",
        "<r a/>",
        "<r a  >",
        "<r / >",
        "<r  /x>",
        "<r></r  x>",
        "<r></s  >",
        "<r></r  ",
        "<r a = ",
        "<r a ",
        "<r  ",
        "<r><s></r  ></s>",
    };

    @Test
    public void testSameEventsAtEverySplit() throws Exception {
        for (int d = 0; d < WELL_FORMED.length; d++) {
            byte[] bytes = WELL_FORMED[d].getBytes(StandardCharsets.UTF_8);
            List<String> whole = parse(bytes, new int[0]);
            assertTrue("Expected a complete parse of " + WELL_FORMED[d] + ", was " + whole,
                    whole.contains("endDocument"));
            for (int i = 1; i < bytes.length; i++) {
                assertEquals("split at " + i + " of " + WELL_FORMED[d], whole, parse(bytes, new int[] { i }));
                for (int j = i + 1; j < bytes.length; j++) {
                    assertEquals("split at " + i + " and " + j + " of " + WELL_FORMED[d], whole,
                            parse(bytes, new int[] { i, j }));
                }
            }
            // And one byte at a time.
            assertEquals("bytewise " + WELL_FORMED[d], whole, parse(bytes, everyByte(bytes.length)));
        }
    }

    @Test
    public void testSameErrorAtEverySplit() throws Exception {
        for (int d = 0; d < MALFORMED.length; d++) {
            byte[] bytes = MALFORMED[d].getBytes(StandardCharsets.UTF_8);
            String whole = errorOf(bytes, new int[0]);
            for (int i = 1; i < bytes.length; i++) {
                assertEquals("split at " + i + " of " + MALFORMED[d], whole, errorOf(bytes, new int[] { i }));
                for (int j = i + 1; j < bytes.length; j++) {
                    assertEquals("split at " + i + " and " + j + " of " + MALFORMED[d], whole,
                            errorOf(bytes, new int[] { i, j }));
                }
            }
            assertEquals("bytewise " + MALFORMED[d], whole, errorOf(bytes, everyByte(bytes.length)));
        }
    }

    @Test
    public void testExpectedErrors() throws Exception {
        assertError("<r a=\"1\"b=\"2\"/>", "White space is required between attributes");
        assertError("<r a = \"1\" a = \"2\"/>", "Duplicate attribute: a");
        assertError("<r a \"1\"/>", "Expected '=' after attribute name");
        assertError("<r a = 1/>", "Expected quoted attribute value");
        assertError("<r></r  x>", "Malformed end tag");
        assertError("<r></s  >", "Mismatched end tag");
        assertError("<r></r  ", "Document ended unexpectedly");
        assertError("<r a = ", "Document ended unexpectedly");
    }

    /**
     * An end tag is not the end of the element until its '>' has been
     * seen, however long that takes to arrive.
     */
    @Test
    public void testEndElementWaitsForTheTagToEnd() throws Exception {
        RecordingXMLHandler handler = new RecordingXMLHandler();
        XMLParser parser = new XMLParser();
        parser.setHandler(handler);
        ByteBuffer buffer = ByteBuffer.allocate(64);
        send(parser, buffer, "<r></r  ");
        assertEquals("[startDocument, startElement:r, endAttributes]", handler.getEvents().toString());
        send(parser, buffer, "   ");
        assertEquals("[startDocument, startElement:r, endAttributes]", handler.getEvents().toString());
        send(parser, buffer, ">");
        assertEquals("[startDocument, startElement:r, endAttributes, endElement]", handler.getEvents().toString());
        parser.close();
    }

    // ===== Helpers =====

    private static int[] everyByte(int length) {
        int[] splits = new int[length - 1];
        for (int i = 0; i < splits.length; i++) {
            splits[i] = i + 1;
        }
        return splits;
    }

    private static void assertError(String xml, String expected) {
        String message = errorOf(xml.getBytes(StandardCharsets.UTF_8), new int[0]);
        assertTrue("Expected '" + expected + "' for " + xml + ", was: " + message, message.contains(expected));
    }

    private static List<String> parse(byte[] bytes, int[] splits) throws SAXException {
        RecordingXMLHandler handler = new RecordingXMLHandler();
        XMLParser parser = new XMLParser();
        parser.setHandler(handler);
        feed(parser, bytes, splits);
        parser.close();
        return new ArrayList<String>(handler.getEvents());
    }

    /** Returns the message of the error the document is rejected with. */
    private static String errorOf(byte[] bytes, int[] splits) {
        XMLParser parser = new XMLParser();
        parser.setHandler(new RecordingXMLHandler());
        try {
            feed(parser, bytes, splits);
            parser.close();
        } catch (SAXException e) {
            return String.valueOf(e.getMessage());
        }
        fail("Expected SAXException for: " + new String(bytes, StandardCharsets.UTF_8));
        return null;
    }

    /** Feeds {@code bytes} in the pieces that {@code splits} divides them
     *  into, keeping to the read/flip/receive/compact contract. */
    private static void feed(XMLParser parser, byte[] bytes, int[] splits) throws SAXException {
        ByteBuffer buffer = ByteBuffer.allocate(bytes.length + 8);
        int offset = 0;
        for (int i = 0; i <= splits.length; i++) {
            int end = (i < splits.length) ? splits[i] : bytes.length;
            buffer.put(bytes, offset, end - offset);
            offset = end;
            buffer.flip();
            parser.receive(buffer);
            buffer.compact();
        }
    }

    private static void send(XMLParser parser, ByteBuffer buffer, String text) throws SAXException {
        buffer.put(text.getBytes(StandardCharsets.UTF_8));
        buffer.flip();
        parser.receive(buffer);
        buffer.compact();
    }

}

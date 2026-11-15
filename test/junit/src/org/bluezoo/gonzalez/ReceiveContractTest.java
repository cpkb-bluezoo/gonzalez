/*
 * ReceiveContractTest.java
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

import org.junit.Test;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The buffer belongs to the caller. The parser may leave the last few
 * bytes of it unconsumed (an incomplete character), and the caller must
 * then present those bytes again, at the start of the next buffer. A
 * caller that clears or replaces the buffer instead loses them, and the
 * parser says so, because the alternative is a document silently missing
 * some of its bytes.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ReceiveContractTest {

    /** "caf" then the two bytes of e-acute in UTF-8. */
    private static final byte[] CAFE = { 'c', 'a', 'f', (byte) 0xC3, (byte) 0xA9 };

    private static final String CAFE_TEXT = "caf" + (char) 0xE9;

    @Test
    public void testUnconsumedBytesPresentedAgainAreAccepted() throws Exception {
        RecordingXMLHandler handler = new RecordingXMLHandler();
        XMLParser parser = new XMLParser();
        parser.setHandler(handler);
        ByteBuffer buffer = ByteBuffer.allocate(64);

        buffer.put(bytes("<r>")).put(CAFE, 0, 4);
        buffer.flip();
        parser.receive(buffer);
        assertEquals("The first byte of the character waits for the second", 1, buffer.remaining());
        buffer.compact();

        buffer.put(CAFE, 4, 1).put(bytes("</r>"));
        buffer.flip();
        parser.receive(buffer);
        assertEquals(0, buffer.remaining());
        parser.close();
        assertTrue("Was: " + handler.getEvents(), handler.getEvents().contains("characters:" + CAFE_TEXT));
    }

    @Test
    public void testReplacingTheBufferIsRejected() throws Exception {
        XMLParser parser = new XMLParser();
        parser.setHandler(new RecordingXMLHandler());
        ByteBuffer first = ByteBuffer.allocate(64);
        first.put(bytes("<r>")).put(CAFE, 0, 4);
        first.flip();
        parser.receive(first);
        assertEquals(1, first.remaining());

        // A new buffer holding only what came next: the unconsumed byte
        // has been dropped.
        ByteBuffer second = ByteBuffer.allocate(64);
        second.put(CAFE, 4, 1).put(bytes("</r>"));
        second.flip();
        assertNotPresentedAgain(parser, second);
    }

    @Test
    public void testClearingTheBufferIsRejected() throws Exception {
        XMLParser parser = new XMLParser();
        parser.setHandler(new RecordingXMLHandler());
        ByteBuffer buffer = ByteBuffer.allocate(64);
        buffer.put(bytes("<r>")).put(CAFE, 0, 4);
        buffer.flip();
        parser.receive(buffer);

        // clear() where compact() was needed. The next bytes happen to be
        // as many as were left, but they are not the same bytes.
        buffer.clear();
        buffer.put(CAFE, 4, 1);
        buffer.flip();
        assertNotPresentedAgain(parser, buffer);
    }

    @Test
    public void testAnEmptyBufferIsRejectedWhileBytesAreOutstanding() throws Exception {
        XMLParser parser = new XMLParser();
        parser.setHandler(new RecordingXMLHandler());
        ByteBuffer buffer = ByteBuffer.allocate(64);
        buffer.put(bytes("<r>")).put(CAFE, 0, 4);
        buffer.flip();
        parser.receive(buffer);
        assertNotPresentedAgain(parser, ByteBuffer.allocate(0));
    }

    @Test
    public void testAnIncompleteStartOfDocumentMustBePresentedAgain() throws Exception {
        // One byte is not enough to rule out a byte order mark.
        XMLParser parser = new XMLParser();
        parser.setHandler(new RecordingXMLHandler());
        ByteBuffer first = ByteBuffer.wrap(bytes("<"));
        parser.receive(first);
        assertEquals(1, first.remaining());
        assertNotPresentedAgain(parser, ByteBuffer.wrap(bytes("r/>")));
    }

    @Test
    public void testTheSameBytesAgainWithNothingNewAreAllowed() throws Exception {
        RecordingXMLHandler handler = new RecordingXMLHandler();
        XMLParser parser = new XMLParser();
        parser.setHandler(handler);
        ByteBuffer buffer = ByteBuffer.allocate(64);
        buffer.put(bytes("<r>")).put(CAFE, 0, 4);
        buffer.flip();
        parser.receive(buffer);
        buffer.compact();

        // Nothing has arrived since. Pointless, but not wrong.
        buffer.flip();
        parser.receive(buffer);
        assertEquals(1, buffer.remaining());
        buffer.compact();

        buffer.put(CAFE, 4, 1).put(bytes("</r>"));
        buffer.flip();
        parser.receive(buffer);
        parser.close();
        assertTrue("Was: " + handler.getEvents(), handler.getEvents().contains("characters:" + CAFE_TEXT));
    }

    @Test
    public void testResetForgetsWhatWasOutstanding() throws Exception {
        RecordingXMLHandler handler = new RecordingXMLHandler();
        XMLParser parser = new XMLParser();
        parser.setHandler(handler);
        ByteBuffer buffer = ByteBuffer.allocate(64);
        buffer.put(bytes("<r>")).put(CAFE, 0, 4);
        buffer.flip();
        parser.receive(buffer);
        assertEquals(1, buffer.remaining());

        parser.reset();
        handler.clear();
        parser.receive(ByteBuffer.wrap(bytes("<s/>")));
        parser.close();
        assertTrue("Was: " + handler.getEvents(), handler.getEvents().contains("startElement:s"));
    }

    @Test
    public void testClosingWithBytesOutstandingIsNotWellFormed() throws Exception {
        XMLParser parser = new XMLParser();
        parser.setHandler(new RecordingXMLHandler());
        ByteBuffer buffer = ByteBuffer.allocate(64);
        buffer.put(bytes("<r>")).put(CAFE, 0, 4);
        buffer.flip();
        parser.receive(buffer);
        try {
            parser.close();
            fail("Expected SAXException: the document ends inside a character");
        } catch (SAXException e) {
            // expected
        }
    }

    @Test
    public void testTheSaxParserChecksToo() throws Exception {
        Parser parser = new Parser();
        parser.setContentHandler(new DefaultHandler());
        ByteBuffer first = ByteBuffer.allocate(64);
        first.put(bytes("<r>")).put(CAFE, 0, 4);
        first.flip();
        parser.receive(first);
        ByteBuffer second = ByteBuffer.allocate(64);
        second.put(CAFE, 4, 1).put(bytes("</r>"));
        second.flip();
        try {
            parser.receive(second);
            fail("Expected IllegalStateException");
        } catch (IllegalStateException e) {
            assertTrue("Was: " + e.getMessage(), e.getMessage().contains("unconsumed"));
        }
    }

    // ===== Helpers =====

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static void assertNotPresentedAgain(XMLParser parser, ByteBuffer data) throws SAXException {
        try {
            parser.receive(data);
            fail("Expected IllegalStateException: unconsumed bytes were not presented again");
        } catch (IllegalStateException e) {
            assertTrue("Was: " + e.getMessage(), e.getMessage().contains("unconsumed"));
        }
    }

}

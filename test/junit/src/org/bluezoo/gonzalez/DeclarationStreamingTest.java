/*
 * DeclarationStreamingTest.java
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

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The XML declaration is read before the encoding is known, straight from
 * the caller's bytes. These tests hold the parser to the same rule there
 * as everywhere else: it consumes what it is given as it goes, so that a
 * caller's buffer never has to hold the whole declaration, and the
 * outcome does not depend on how the bytes were divided.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DeclarationStreamingTest {

    /** The most the parser may leave unconsumed: an incomplete character
     *  or byte order mark. */
    private static final int MAX_LEFTOVER = 3;

    private static final List<String> ROOT_ONLY =
            Arrays.asList("startDocument", "startElement:r", "endAttributes", "endElement", "endDocument");

    // ===== A declaration larger than the blocking buffers =====

    @Test(timeout = 20000)
    public void testDeclarationLargerThanStreamBuffer() throws Exception {
        RecordingSaxHandler sink = new RecordingSaxHandler();
        Parser parser = new Parser();
        parser.setContentHandler(sink);
        parser.parse(new InputSource(new ByteArrayInputStream(paddedDeclaration(40000))));
        assertEquals(Arrays.asList("startDocument()", "startElement(,r,r,[])", "endElement(,r,r)",
                "endDocument()"), sink.getEvents());
    }

    @Test(timeout = 20000)
    public void testDeclarationLargerThanChannelBuffer() throws Exception {
        RecordingXMLHandler handler = new RecordingXMLHandler();
        XMLParser parser = new XMLParser();
        parser.setHandler(handler);
        parser.parse(Channels.newChannel(new ByteArrayInputStream(paddedDeclaration(40000))));
        assertEquals(ROOT_ONLY, handler.getEvents());
    }

    @Test(timeout = 20000)
    public void testDeclarationLargerThanCallerBuffer() throws Exception {
        RecordingXMLHandler handler = new RecordingXMLHandler();
        XMLParser parser = new XMLParser();
        parser.setHandler(handler);
        feed(parser, paddedDeclaration(5000), 16, 16);
        parser.close();
        assertEquals(ROOT_ONLY, handler.getEvents());
    }

    // ===== The declaration takes effect however it arrives =====

    @Test
    public void testDeclarationHonouredThroughATinyBuffer() throws Exception {
        // The e-acute is one byte in ISO-8859-1 and would be malformed UTF-8.
        String cafe = "caf" + (char) 0xE9;
        byte[] bytes = ("<?xml version=\"1.1\" encoding=\"ISO-8859-1\" standalone=\"yes\"?><r>" + cafe + "</r>")
                .getBytes(StandardCharsets.ISO_8859_1);
        for (int chunk = 1; chunk <= 8; chunk++) {
            RecordingXMLHandler handler = new RecordingXMLHandler();
            XMLParser parser = new XMLParser();
            parser.setHandler(handler);
            feed(parser, bytes, chunk, 8);
            parser.close();
            assertTrue("chunk " + chunk + ": standalone", parser.isStandalone());
            assertTrue("chunk " + chunk + ": expected the decoded text in " + handler.getEvents(),
                    handler.getEvents().contains("characters:" + cafe));
        }
    }

    @Test
    public void testSameEventsAtEveryChunkSize() throws Exception {
        String[] documents = {
            "<r a=\"1\">t</r>",
            "<?xml version=\"1.0\"?><r a=\"1\">t</r>",
            "<?xml version='1.0' encoding='UTF-8'?>\n<r a=\"1\">t</r>",
            "<?xml  version = \"1.0\"\n encoding = \"UTF-8\"\tstandalone = 'no' ?><r a=\"1\">t</r>",
            "<?xml version=\"1.0\" standalone=\"yes\"?><r a=\"1\">t</r>",
            // Not declarations: these must reach the handler untouched.
            "<?xml-stylesheet href=\"a.xsl\"?><r a=\"1\">t</r>",
            "<?xmlfoo bar?><r a=\"1\">t</r>",
            "<?x y?><r a=\"1\">t</r>",
            "<!-- c --><r a=\"1\">t</r>",
        };
        Charset[] charsets = { StandardCharsets.UTF_8, StandardCharsets.UTF_16 };
        for (int d = 0; d < documents.length; d++) {
            for (int c = 0; c < charsets.length; c++) {
                // An encoding declaration must agree with the bytes.
                String document = documents[d];
                if (charsets[c] != StandardCharsets.UTF_8) {
                    document = document.replace("UTF-8", "UTF-16");
                }
                byte[] bytes = document.getBytes(charsets[c]);
                List<String> whole = parse(bytes, bytes.length, bytes.length);
                assertTrue("Expected a root element for " + document + ", was " + whole,
                        whole.contains("startElement:r"));
                for (int chunk = 1; chunk < bytes.length; chunk++) {
                    assertEquals(charsets[c] + " chunk " + chunk + " of " + document, whole,
                            parse(bytes, chunk, chunk + MAX_LEFTOVER));
                }
            }
        }
    }

    // ===== Something that begins like a declaration but is not one =====

    @Test
    public void testMalformedDeclarationSameErrorAtEveryChunkSize() throws Exception {
        String[] documents = {
            "<?xml?><r/>",
            "<?xml ?><r/>",
            "<?xml version=\"1.0\" bad?><r/>",
            "<?xml version=\"2.0\"?><r/>",
            "<?xml version='1.0\"?><r/>",
            "<?xml version=\"1.0\"encoding=\"UTF-8\"?><r/>",
            "<?xml encoding=\"UTF-8\"?><r/>",
            "<?xml version=\"1.0\" standalone=\"maybe\"?><r/>",
            "<?xml version=\"1.0\" standalone=\"yes\" encoding=\"UTF-8\"?><r/>",
            "<?xml version=\"1.0\" encoding=\"UTF-8\" encoding=\"UTF-8\"?><r/>",
        };
        for (int d = 0; d < documents.length; d++) {
            byte[] bytes = documents[d].getBytes(StandardCharsets.UTF_8);
            String whole = errorOf(bytes, bytes.length, bytes.length);
            assertTrue("Expected the reserved target error for " + documents[d] + ", was: " + whole,
                    whole.contains("reserved"));
            for (int chunk = 1; chunk < bytes.length; chunk++) {
                assertEquals("chunk " + chunk + " of " + documents[d], whole,
                        errorOf(bytes, chunk, chunk + MAX_LEFTOVER));
            }
        }
    }

    @Test
    public void testTruncatedDeclarationIsNotWellFormed() throws Exception {
        String[] documents = { "<", "<?", "<?xm", "<?xml", "<?xml ", "<?xml version", "<?xml version=\"1.0\"",
            "<?xml version=\"1.0\"?" };
        for (int d = 0; d < documents.length; d++) {
            byte[] bytes = documents[d].getBytes(StandardCharsets.UTF_8);
            for (int chunk = 1; chunk <= bytes.length; chunk++) {
                // errorOf() fails the test if the document is accepted.
                errorOf(bytes, chunk, chunk + MAX_LEFTOVER);
            }
        }
    }

    // ===== Values of unusual length =====

    @Test
    public void testVersionWithManyDigitsIsAccepted() throws Exception {
        // VersionNum is '1.' followed by any number of digits.
        StringBuilder sb = new StringBuilder("<?xml version=\"1.");
        for (int i = 0; i < 5000; i++) {
            sb.append('0');
        }
        sb.append("\"?><r/>");
        byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);
        assertEquals(ROOT_ONLY, parse(bytes, 16, 16));
    }

    @Test
    public void testVersionWithManyDigitsMustStillBeANumber() throws Exception {
        StringBuilder sb = new StringBuilder("<?xml version=\"1.");
        for (int i = 0; i < 5000; i++) {
            sb.append('0');
        }
        sb.append("x\"?><r/>");
        byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);
        assertTrue(errorOf(bytes, 16, 16).contains("reserved"));
    }

    @Test
    public void testOverlongEncodingNameIsRejected() throws Exception {
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"x");
        for (int i = 0; i < 5000; i++) {
            sb.append('y');
        }
        sb.append("\"?><r/>");
        byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);
        String message = errorOf(bytes, 16, 16);
        assertTrue("Was: " + message, message.contains("Invalid or unsupported encoding"));
        assertTrue("The message must not carry the whole name: " + message.length(), message.length() < 1000);
    }

    // ===== The blocking loops escalate when nothing is consumed =====

    @Test
    public void testMakeRoomKeepsUnconsumedBytes() throws Exception {
        ByteBuffer full = ByteBuffer.allocate(8);
        full.put(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8 });
        assertFalse(full.hasRemaining());

        ByteBuffer bigger = XMLParser.makeRoom(full);
        assertTrue("Expected more capacity, was " + bigger.capacity(), bigger.capacity() > 8);
        assertEquals("Still in write mode, after the kept bytes", 8, bigger.position());
        assertEquals(bigger.capacity(), bigger.limit());
        for (int i = 0; i < 8; i++) {
            assertEquals(i + 1, bigger.get(i));
        }
    }

    @Test
    public void testMakeRoomGivesUpEventually() throws Exception {
        ByteBuffer buffer = ByteBuffer.allocate(8);
        buffer.position(buffer.limit());
        try {
            for (int i = 0; i < 64; i++) {
                buffer = XMLParser.makeRoom(buffer);
                buffer.position(buffer.limit());
            }
            fail("Expected SAXException once the buffer could grow no further");
        } catch (SAXException e) {
            assertTrue("Was: " + e.getMessage(), e.getMessage().contains("no progress"));
        }
    }

    // ===== Helpers =====

    /** A declaration made long with {@code padding} spaces, then a root. */
    private static byte[] paddedDeclaration(int padding) {
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\"");
        for (int i = 0; i < padding; i++) {
            sb.append(' ');
        }
        sb.append("?><r/>");
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static List<String> parse(byte[] bytes, int chunk, int capacity) throws SAXException {
        RecordingXMLHandler handler = new RecordingXMLHandler();
        XMLParser parser = new XMLParser();
        parser.setHandler(handler);
        feed(parser, bytes, chunk, capacity);
        parser.close();
        return new ArrayList<String>(handler.getEvents());
    }

    /** Returns the message of the error the document is rejected with. */
    private static String errorOf(byte[] bytes, int chunk, int capacity) {
        XMLParser parser = new XMLParser();
        parser.setHandler(new RecordingXMLHandler());
        try {
            feed(parser, bytes, chunk, capacity);
            parser.close();
        } catch (SAXException e) {
            return String.valueOf(e.getMessage());
        }
        fail("Expected SAXException (chunk " + chunk + ") for: " + new String(bytes, StandardCharsets.UTF_8));
        return null;
    }

    /**
     * Feeds {@code bytes} in pieces of at most {@code chunk} bytes through
     * a buffer of {@code capacity} bytes, as a caller reading from a
     * socket would: read, flip, receive, compact. Checks after every call
     * that the parser consumed all but an incomplete character, which is
     * what lets a buffer this small work at all.
     */
    private static void feed(XMLParser parser, byte[] bytes, int chunk, int capacity) throws SAXException {
        ByteBuffer buffer = ByteBuffer.allocate(capacity);
        int offset = 0;
        while (offset < bytes.length) {
            int n = Math.min(Math.min(chunk, bytes.length - offset), buffer.remaining());
            if (n == 0) {
                fail("No progress: the parser left " + buffer.position() + " bytes unconsumed in a buffer of "
                        + capacity);
            }
            buffer.put(bytes, offset, n);
            offset += n;
            buffer.flip();
            parser.receive(buffer);
            if (buffer.remaining() > MAX_LEFTOVER) {
                fail("The parser left " + buffer.remaining() + " bytes unconsumed at offset " + offset);
            }
            buffer.compact();
        }
    }

}

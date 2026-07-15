/*
 * ParserEncodingTest.java
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

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;

import org.junit.Test;
import org.xml.sax.InputSource;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

import static org.junit.Assert.*;

/**
 * Tests for character decoding: the ASCII fast path in ExternalEntityDecoder,
 * and correct (non-hanging) handling of malformed or truncated byte input.
 *
 * <p>Truncated-input tests use a JUnit timeout: a document ending mid-character
 * previously caused decodeAndTokenize()'s inner loop, and Parser's outer
 * read loop, to spin forever retrying the same undecodable trailing bytes -
 * a real hang/DoS risk on untrusted input, not merely a wrong result. A
 * regression here must fail the test outright rather than hang the build.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ParserEncodingTest {

    private static class TextCollector extends DefaultHandler {
        final StringBuilder text = new StringBuilder();

        @Override
        public void characters(char[] ch, int start, int length) {
            text.append(ch, start, length);
        }
    }

    private String parseAndCollectText(byte[] bytes) throws Exception {
        Parser parser = new Parser();
        TextCollector handler = new TextCollector();
        parser.setContentHandler(handler);
        parser.parse(new InputSource(new ByteArrayInputStream(bytes)));
        return handler.text.toString();
    }

    // ========== ASCII fast path correctness ==========

    @Test
    public void testPlainAsciiUtf8() throws Exception {
        String text = parseAndCollectText(
                "<?xml version=\"1.0\"?><doc>Hello, ASCII world! 0123456789</doc>".getBytes("UTF-8"));
        assertEquals("Hello, ASCII world! 0123456789", text);
    }

    @Test
    public void testMixedAsciiAndMultibyteUtf8() throws Exception {
        String text = parseAndCollectText(
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?><doc>café 日本語 back to ascii</doc>"
                        .getBytes("UTF-8"));
        assertEquals("café 日本語 back to ascii", text);
    }

    @Test
    public void testExplicitUsAscii() throws Exception {
        String text = parseAndCollectText(
                "<?xml version=\"1.0\" encoding=\"US-ASCII\"?><doc>plain ascii text</doc>".getBytes("US-ASCII"));
        assertEquals("plain ascii text", text);
    }

    @Test
    public void testExplicitIso88591HighByteContent() throws Exception {
        // High-byte (>=0x80) Latin-1 characters must still fall through to the
        // real decoder correctly - the fast path only ever widens bytes <0x80.
        String text = parseAndCollectText(
                "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?><doc>naïve café</doc>".getBytes("ISO-8859-1"));
        assertEquals("naïve café", text);
    }

    @Test
    public void testDirectByteBufferBypassesArrayFastPath() throws Exception {
        // A direct (non-array-backed) ByteBuffer can't be widened by the fast
        // path at all; it must gracefully no-op and fall through to the normal
        // decoder rather than error or behave incorrectly.
        byte[] bytes = "<?xml version=\"1.0\"?><doc>direct café 日本語</doc>".getBytes("UTF-8");
        ByteBuffer direct = ByteBuffer.allocateDirect(bytes.length);
        direct.put(bytes);
        direct.flip();

        Parser parser = new Parser();
        TextCollector handler = new TextCollector();
        parser.setContentHandler(handler);
        parser.receive(direct);
        parser.close();

        assertEquals("direct café 日本語", handler.text.toString());
    }

    // ========== Malformed / truncated input must error, never hang ==========

    @Test(timeout = 5000)
    public void testLoneContinuationByteRejected() throws Exception {
        byte[] prefix = "<?xml version=\"1.0\"?><doc>".getBytes("UTF-8");
        byte[] suffix = "</doc>".getBytes("UTF-8");
        byte[] malformed = new byte[prefix.length + 1 + suffix.length];
        System.arraycopy(prefix, 0, malformed, 0, prefix.length);
        malformed[prefix.length] = (byte) 0x80; // lone continuation byte: invalid UTF-8
        System.arraycopy(suffix, 0, malformed, prefix.length + 1, suffix.length);

        Parser parser = new Parser();
        parser.setContentHandler(new DefaultHandler());
        try {
            parser.parse(new InputSource(new ByteArrayInputStream(malformed)));
            fail("Expected a malformed-byte-sequence error");
        } catch (SAXParseException e) {
            assertTrue(e.getMessage().contains("Malformed"));
        }
    }

    @Test(timeout = 5000)
    public void testTruncatedMultibyteSequenceAtEofViaInputSource() throws Exception {
        // Document ends mid-character: a 2-byte UTF-8 sequence's lead byte with
        // no continuation byte ever following. Previously hung forever.
        byte[] prefix = "<?xml version=\"1.0\"?><doc>".getBytes("UTF-8");
        byte[] truncated = new byte[prefix.length + 1];
        System.arraycopy(prefix, 0, truncated, 0, prefix.length);
        truncated[prefix.length] = (byte) 0xC3; // start of a 2-byte sequence, missing continuation

        Parser parser = new Parser();
        parser.setContentHandler(new DefaultHandler());
        try {
            parser.parse(new InputSource(new ByteArrayInputStream(truncated)));
            fail("Expected an end-of-input truncation error");
        } catch (SAXParseException e) {
            assertTrue("Error should mention incomplete/end of input, was: " + e.getMessage(),
                    e.getMessage().contains("end of"));
        }
    }

    @Test(timeout = 5000)
    public void testTruncatedMultibyteSequenceAtEofViaChannel() throws Exception {
        byte[] prefix = "<?xml version=\"1.0\"?><doc>".getBytes("UTF-8");
        byte[] truncated = new byte[prefix.length + 1];
        System.arraycopy(prefix, 0, truncated, 0, prefix.length);
        truncated[prefix.length] = (byte) 0xC3;

        Parser parser = new Parser();
        parser.setContentHandler(new DefaultHandler());
        ReadableByteChannel channel = Channels.newChannel(new ByteArrayInputStream(truncated));
        try {
            parser.parse(channel);
            fail("Expected an end-of-input truncation error");
        } catch (SAXParseException e) {
            assertTrue("Error should mention incomplete/end of input, was: " + e.getMessage(),
                    e.getMessage().contains("end of"));
        }
    }

    @Test(timeout = 5000)
    public void testCompleteDocumentEndingInMultibyteCharacterParsesFine() throws Exception {
        // Sanity check that the fix didn't make well-formed multi-byte content
        // ending right at EOF fail: the LAST character of content is itself a
        // multi-byte sequence, fully present, followed only by closing markup.
        String text = parseAndCollectText(
                "<?xml version=\"1.0\"?><doc>café</doc>".getBytes("UTF-8"));
        assertEquals("café", text);
    }
}

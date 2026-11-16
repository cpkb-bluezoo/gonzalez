/*
 * BufferSizingTest.java
 * Copyright (C) 2026 Chris Burdess
 */
package org.bluezoo.gonzalez;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import org.junit.Test;
import org.xml.sax.InputSource;
import org.xml.sax.helpers.DefaultHandler;

import static org.junit.Assert.assertEquals;

/**
 * The parser decodes in pieces of up to 32K characters and reads a stream
 * in pieces of up to 32K bytes, and a document may arrive in any pieces at
 * all. Whatever size a document is, and however it arrives, the text
 * reported must be the text it holds: these documents are sized to fall
 * either side of every size a buffer has.
 */
public class BufferSizingTest {

    /** Sizes, in characters of content, around the sizes buffers take. */
    private static final int[] SIZES = {
        0, 1, 7, 500, 1000, 1023, 1024, 1025, 2047, 2048, 2049, 4090, 4095,
        4096, 4097, 8191, 8192, 8193, 16383, 16384, 16385, 32760, 32767,
        32768, 32769, 65535, 65536, 65537, 100000, 300000
    };

    private static String content(int length, boolean multibyte, boolean crlf) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            if (crlf && i % 40 == 39) {
                // Counts as two characters here and one in the result;
                // the length is made up with ordinary ones below.
                sb.append("\r\n");
                i++;
            } else if (multibyte && i % 3 == 0) {
                sb.append((char) 0x00e9);
            } else if (multibyte && i % 17 == 0) {
                sb.append((char) 0x3042);
            } else {
                sb.append((char) ('a' + (i % 26)));
            }
        }
        return sb.toString();
    }

    private static String normalised(String text) {
        return text.replace("\r\n", "\n");
    }

    private static byte[] document(String text, Charset charset) {
        String declaration = "<?xml version=\"1.0\" encoding=\"" + charset.name() + "\"?>";
        return (declaration + "<r a=\"" + text.substring(0, Math.min(10, text.length()))
                + "\">" + text + "</r>").getBytes(charset);
    }

    private static final class Collector extends DefaultHandler {
        final StringBuilder text = new StringBuilder();
        String attribute;

        @Override
        public void startElement(String uri, String localName, String qName,
                org.xml.sax.Attributes attributes) {
            attribute = attributes.getValue("a");
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            text.append(ch, start, length);
        }
    }

    private static void check(String name, String text, byte[] bytes, int chunk) throws Exception {
        String expected = normalised(text);

        Collector viaStream = new Collector();
        Parser parser = new Parser();
        parser.setContentHandler(viaStream);
        InputStream in = new ByteArrayInputStream(bytes);
        if (chunk > 0) {
            in = new ChunkedInputStream(in, chunk);
        }
        parser.parse(new InputSource(in));
        assertEquals(name + " via stream, chunk " + chunk, expected, viaStream.text.toString());
        assertEquals(name + " attribute", normalised(text.substring(0, Math.min(10, text.length()))),
                viaStream.attribute);

        Collector viaReceive = new Collector();
        Parser pushed = new Parser();
        pushed.setContentHandler(viaReceive);
        int slice = chunk > 0 ? chunk : bytes.length;
        ByteBuffer buffer = ByteBuffer.allocate(slice + 8);
        int offset = 0;
        while (offset < bytes.length) {
            int n = Math.min(slice, bytes.length - offset);
            buffer.put(bytes, offset, n);
            offset += n;
            buffer.flip();
            pushed.receive(buffer);
            buffer.compact();
        }
        pushed.close();
        assertEquals(name + " via receive, slice " + slice, expected, viaReceive.text.toString());
    }

    @Test
    public void asciiAtEverySize() throws Exception {
        for (int size : SIZES) {
            String text = content(size, false, false);
            check("ascii " + size, text, document(text, StandardCharsets.UTF_8), 0);
        }
    }

    @Test
    public void asciiArrivingInPieces() throws Exception {
        for (int size : new int[] { 1000, 4097, 33000 }) {
            String text = content(size, false, false);
            byte[] bytes = document(text, StandardCharsets.UTF_8);
            for (int chunk : new int[] { 1, 13, 1000, 1024, 4096, 4097, 8192, 40000 }) {
                check("ascii " + size, text, bytes, chunk);
            }
        }
    }

    @Test
    public void multibyteAtEverySize() throws Exception {
        for (int size : SIZES) {
            String text = content(size, true, false);
            check("utf-8 " + size, text, document(text, StandardCharsets.UTF_8), 0);
        }
    }

    @Test
    public void multibyteArrivingInPieces() throws Exception {
        for (int size : new int[] { 1000, 4097, 33000 }) {
            String text = content(size, true, false);
            byte[] bytes = document(text, StandardCharsets.UTF_8);
            for (int chunk : new int[] { 1, 2, 3, 13, 1000, 1023, 4095, 4097 }) {
                check("utf-8 " + size, text, bytes, chunk);
            }
        }
    }

    @Test
    public void utf16AtEverySize() throws Exception {
        for (int size : SIZES) {
            String text = content(size, true, false);
            check("utf-16 " + size, text, document(text, StandardCharsets.UTF_16), 0);
        }
    }

    @Test
    public void lineEndsAcrossEveryBoundary() throws Exception {
        for (int size : SIZES) {
            String text = content(size, false, true);
            byte[] bytes = document(text, StandardCharsets.UTF_8);
            check("crlf " + size, text, bytes, 0);
            check("crlf " + size, text, bytes, 1024);
            check("crlf " + size, text, bytes, 4096);
        }
    }

    @Test
    public void aParserReusedForSmallThenLargeThenSmall() throws Exception {
        Parser parser = new Parser();
        for (int size : new int[] { 10, 50000, 10, 5000, 100000, 3 }) {
            String text = content(size, true, false);
            Collector collector = new Collector();
            parser.reset();
            parser.setContentHandler(collector);
            parser.parse(new InputSource(
                    new ByteArrayInputStream(document(text, StandardCharsets.UTF_8))));
            assertEquals("reused parser, size " + size, text, collector.text.toString());
        }
    }
}

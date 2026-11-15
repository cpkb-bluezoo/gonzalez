/*
 * TruncatedDocumentTest.java
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
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * A document that stops part way through a construct is not well-formed,
 * wherever it stops: before the root element, inside it, or after it.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class TruncatedDocumentTest {

    /** Unfinished markup following a complete root element. */
    private static final String[] TRUNCATED_AFTER_ROOT = {
        "<a/><",
        "<a/><!",
        "<a/><!-",
        "<a/><!--",
        "<a/><!-- x",
        "<a/><!-- x -",
        "<a/><!-- x --",
        "<a/><?",
        "<a/><?p",
        "<a/><?pi ",
        "<a/><?pi data",
        "<a/><?pi data?",
        "<a/><b",
        "<a/></",
        "<a/></a",
        "<a>t</a>\n<!-- x",
        "<a>t</a><!-- one --><!-- two",
    };

    @Test
    public void testTruncatedMarkupAfterRootIsRejected() throws Exception {
        for (int i = 0; i < TRUNCATED_AFTER_ROOT.length; i++) {
            String xml = TRUNCATED_AFTER_ROOT[i];
            byte[] bytes = xml.getBytes(StandardCharsets.UTF_8);
            for (int chunk = 1; chunk <= bytes.length; chunk++) {
                assertRejected(xml, bytes, chunk);
            }
        }
    }

    @Test
    public void testTruncatedMarkupAfterRootIsRejectedThroughSax() throws Exception {
        for (int i = 0; i < TRUNCATED_AFTER_ROOT.length; i++) {
            Parser parser = new Parser();
            parser.setContentHandler(new DefaultHandler());
            try {
                parser.parse(new InputSource(new ByteArrayInputStream(
                        TRUNCATED_AFTER_ROOT[i].getBytes(StandardCharsets.UTF_8))));
                fail("Expected SAXException for: " + TRUNCATED_AFTER_ROOT[i]);
            } catch (SAXException e) {
                // expected
            }
        }
    }

    @Test
    public void testCompleteMarkupAfterRootIsStillAccepted() throws Exception {
        assertEquals(Arrays.asList("startDocument", "startElement:a", "endAttributes", "endElement",
                "comment: x ", "pi:pi=data", "endDocument"), parse("<a/>\n<!-- x --><?pi data?>\n\n"));
        assertEquals(Arrays.asList("startDocument", "startElement:a", "endAttributes", "endElement",
                "endDocument"), parse("<a/> \t\n"));
    }

    /**
     * Every prefix of a document is rejected, except the few that happen
     * to be complete documents themselves.
     */
    @Test
    public void testOnlyCompletePrefixesAreAccepted() throws Exception {
        String root = "<a b=\"1\">t<c/>&amp;<![CDATA[d]]></a>";
        String withComment = root + "<!--c-->";
        String document = withComment + "<?p d?>";
        byte[] bytes = document.getBytes(StandardCharsets.UTF_8);
        for (int length = 0; length <= bytes.length; length++) {
            byte[] prefix = Arrays.copyOf(bytes, length);
            String xml = document.substring(0, length);
            boolean complete = (length == root.length() || length == withComment.length()
                    || length == document.length());
            if (complete) {
                assertTrue("Expected to be accepted: " + xml, parse(xml).contains("endDocument"));
            } else {
                assertRejected(xml, prefix, Math.max(1, length));
                assertRejected(xml, prefix, 1);
            }
        }
    }

    // ===== Helpers =====

    private static List<String> parse(String xml) throws Exception {
        RecordingXMLHandler handler = new RecordingXMLHandler();
        XMLParser parser = new XMLParser();
        parser.setHandler(handler);
        parser.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        return handler.getEvents();
    }

    private static void assertRejected(String xml, byte[] bytes, int chunk) {
        RecordingXMLHandler handler = new RecordingXMLHandler();
        XMLParser parser = new XMLParser();
        parser.setHandler(handler);
        try {
            ByteBuffer buffer = ByteBuffer.allocate(chunk + 8);
            int offset = 0;
            while (offset < bytes.length) {
                int n = Math.min(chunk, bytes.length - offset);
                buffer.put(bytes, offset, n);
                offset += n;
                buffer.flip();
                parser.receive(buffer);
                buffer.compact();
            }
            parser.close();
        } catch (SAXException e) {
            return;
        }
        fail("Expected SAXException (chunk " + chunk + ") for: " + xml + " - events: " + handler.getEvents());
    }

}

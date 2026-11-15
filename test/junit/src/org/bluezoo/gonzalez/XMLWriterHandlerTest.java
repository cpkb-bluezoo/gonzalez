/*
 * XMLWriterHandlerTest.java
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
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;
import org.xml.sax.SAXException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * A parser feeding a writer through {@link XMLWriterHandler} copies a
 * document: what is written parses to the same events as what was read,
 * however the input arrives, and neither side holds more than its buffer
 * however large any part of the document is.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class XMLWriterHandlerTest {

    private static final String EMOJI = new String(Character.toChars(0x1F600));

    private static final String[] DOCUMENTS = {
        "<r/>",
        "<r>text</r>",
        "<r a=\"1\" b='two'><s c=\"\"/>tail</r>",
        "<r a=\"&lt;&amp;&quot;&apos;&gt; &#9;&#10;&#13; x\">&lt;&amp;&gt; \"quoted\" 'single'</r>",
        "<!-- before --><?before the root?><r><!-- in - side --><?pi?><?target with data ?></r>"
                + "<!-- after --><?after?>",
        "<r><![CDATA[<not> &markup; ]] > ]]>and<![CDATA[]]></r>",
        "<r a=\"caf" + (char) 0xE9 + " " + EMOJI + "\">" + (char) 0x20AC + EMOJI + "&#x1F600;&#233;</r>",
        "<r>\n  <s>one</s>\n  <s>two</s>\n</r>",
        "<p:r xmlns:p=\"urn:p\" xmlns=\"urn:d\" p:a=\"1\"><s xmlns=\"\" xmlns:q=\"urn:q\" q:b=\"2\"/><p:t/></p:r>",
        "<r xml:lang=\"en\" xml:space=\"preserve\">  kept  </r>",
        "<!DOCTYPE r [\n<!ELEMENT r (s*)>\n<!ELEMENT s (#PCDATA)>\n<!ATTLIST s a CDATA \"dflt\">\n"
                + "<!ENTITY e \"entity text\">\n<!-- in the subset -->\n]>\n<r><s>&e;</s><s a=\"given\">x</s></r>",
    };

    // ===== What is written =====

    @Test
    public void testWrittenAsRead() throws Exception {
        assertEquals("<r a=\"1\" b=\"two\"><s c=\"\"/>tail</r>", copy("<r a=\"1\" b='two'><s c=\"\"/>tail</r>"));
        assertEquals("<r>&lt;&amp;&gt; \"quoted\"</r>", copy("<r>&lt;&amp;&gt; \"quoted\"</r>"));
        assertEquals("<!-- before --><r><!-- in - side --><?pi?><?target with data ?></r><!-- after -->",
                copy("<!-- before --><r><!-- in - side --><?pi?><?target with data ?></r><!-- after -->"));
        assertEquals("<r><![CDATA[<not> &markup;]]>and</r>", copy("<r><![CDATA[<not> &markup;]]>and</r>"));
        assertEquals("<r>\n  <s>one</s>\n</r>", copy("<r>\n  <s>one</s>\n</r>"));
    }

    @Test
    public void testAttributeValueIsWrittenAsNormalised() throws Exception {
        // A literal tab or newline in a value is a space to the parser;
        // one given as a reference is itself, and is written as one.
        assertEquals("<r a=\"x  y&#9;&#10;z\"/>", copy("<r a='x\t\ny&#9;&#10;z'/>"));
    }

    @Test
    public void testDefaultedAttributeIsWrittenOut() throws Exception {
        String written = copy("<!DOCTYPE r [<!ATTLIST r a CDATA \"dflt\">]><r/>");
        assertTrue("Was: " + written, written.endsWith("<r a=\"dflt\"/>"));
        assertTrue("Was: " + written, written.contains("<!ATTLIST r a CDATA \"dflt\">"));
    }

    @Test
    public void testIgnorableWhitespaceIsWritten() throws Exception {
        String written = copy("<!DOCTYPE r [<!ELEMENT r (s*)><!ELEMENT s EMPTY>]><r>\n  <s/>\n</r>");
        assertTrue("Was: " + written, written.endsWith("<r>\n  <s/>\n</r>"));
    }

    // ===== Namespaces =====

    @Test
    public void testNamespaceDeclarationsEitherWay() throws Exception {
        String xml = "<p:r a=\"1\" xmlns:p=\"urn:p\" xmlns=\"urn:d\"><p:s xmlns:q=\"urn:q\" q:b=\"2\"/></p:r>";
        // As namespace events, and as the attributes they are when the
        // parser is not namespace aware: the same document either way.
        assertEquals(xml, copy(xml, true, false));
        assertEquals(xml, copy(xml, false, false));
    }

    @Test
    public void testDeclarationReportedBothWaysIsWrittenOnce() throws Exception {
        String xml = "<p:r xmlns:p=\"urn:p\" xmlns=\"urn:d\" a=\"1\"><s/></p:r>";
        assertEquals(xml, copy(xml, true, true));
    }

    @Test
    public void testRepeatedDeclarationIsLeftOut() throws Exception {
        assertEquals("<r xmlns:p=\"urn:p\"><p:s/><p:t xmlns:p=\"urn:other\"/></r>",
                copy("<r xmlns:p=\"urn:p\"><p:s xmlns:p=\"urn:p\"/><p:t xmlns:p=\"urn:other\"/></r>", true, false));
    }

    // ===== The copy is the same document =====

    @Test
    public void testCopyParsesToTheSameEvents() throws Exception {
        for (int d = 0; d < DOCUMENTS.length; d++) {
            for (int aware = 0; aware < 2; aware++) {
                String written = copy(DOCUMENTS[d], aware == 1, false);
                assertEquals("document " + d + " written as " + written, events(DOCUMENTS[d], aware == 1),
                        events(written, aware == 1));
                // And a copy of the copy is the copy.
                assertEquals("document " + d, written, copy(written, aware == 1, false));
            }
        }
    }

    @Test
    public void testSameOutputHoweverTheInputArrives() throws Exception {
        for (int d = 0; d < DOCUMENTS.length; d++) {
            byte[] bytes = DOCUMENTS[d].getBytes(StandardCharsets.UTF_8);
            String whole = copy(bytes, bytes.length, true);
            int[] chunks = { 1, 2, 3, 7, 16 };
            for (int c = 0; c < chunks.length; c++) {
                assertEquals("document " + d + " in pieces of " + chunks[c], whole, copy(bytes, chunks[c], true));
            }
        }
    }

    @Test
    public void testOtherCharsets() throws Exception {
        String xml = "<r a=\"caf" + (char) 0xE9 + EMOJI + "\"><!--" + (char) 0xE9 + "-->" + (char) 0x20AC + "</r>";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        XMLWriter writer = new XMLWriter(out);
        writer.setCharset(StandardCharsets.ISO_8859_1);
        XMLParser parser = new XMLParser();
        parser.setHandler(new XMLWriterHandler(writer));
        parser.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_16)));
        // What ISO-8859-1 cannot hold is written as a reference.
        assertEquals("<r a=\"caf" + (char) 0xE9 + "&#128512;\"><!--" + (char) 0xE9 + "-->&#8364;</r>",
                new String(out.toByteArray(), StandardCharsets.ISO_8859_1));
    }

    // ===== Streaming =====

    /**
     * A megabyte of each kind of content passes from input to output, a
     * kilobyte at a time, with the parser's scan buffer and the writer's
     * buffer both staying the size they started.
     */
    @Test(timeout = 60000)
    public void testCopiesInConstantMemory() throws Exception {
        StringBuilder builder = new StringBuilder();
        while (builder.length() < 1000000) {
            builder.append("abcdefghij klmnopqrst uvwxyz ").append((char) 0xE9).append(EMOJI).append(' ');
        }
        String large = builder.toString();
        String[][] around = {
            { "<r a=\"", "\"/>" },
            { "<r>", "</r>" },
            { "<r><!--", "--></r>" },
            { "<r><?p ", "?></r>" },
            { "<r><![CDATA[", "]]></r>" },
        };
        for (int k = 0; k < around.length; k++) {
            String xml = around[k][0] + large + around[k][1];
            byte[] bytes = xml.getBytes(StandardCharsets.UTF_8);
            CountingChannel channel = new CountingChannel();
            XMLWriter writer = new XMLWriter(channel);
            int writerCapacity = writer.bufferCapacity();
            XMLParser parser = new XMLParser();
            parser.setHandler(new XMLWriterHandler(writer));
            ByteBuffer buffer = ByteBuffer.allocate(1024 + 8);
            int scanCapacity = 0;
            int offset = 0;
            while (offset < bytes.length) {
                int n = Math.min(1024, bytes.length - offset);
                buffer.put(bytes, offset, n);
                offset += n;
                buffer.flip();
                parser.receive(buffer);
                buffer.compact();
                if (scanCapacity == 0) {
                    scanCapacity = parser.scanBufferCapacity();
                }
                assertEquals("scan buffer after " + offset + " bytes of case " + k, scanCapacity,
                        parser.scanBufferCapacity());
                assertEquals("writer buffer after " + offset + " bytes of case " + k, writerCapacity,
                        writer.bufferCapacity());
                // What has gone in has, to within the two buffers, come out.
                assertTrue("case " + k + ": " + channel.total + " bytes out for " + offset + " in",
                        channel.total >= offset - 4 * (scanCapacity + writerCapacity));
            }
            parser.close();
            assertEquals("case " + k, bytes.length, channel.total);
            assertTrue("case " + k + ": largest write " + channel.largest, channel.largest <= writerCapacity);
        }
    }

    // ===== Failure =====

    @Test
    public void testWriterFailureStopsTheParse() throws Exception {
        final IOException failure = new IOException("disk full");
        WritableByteChannel channel = new WritableByteChannel() {
            @Override
            public int write(ByteBuffer src) throws IOException {
                throw failure;
            }

            @Override
            public boolean isOpen() {
                return true;
            }

            @Override
            public void close() {
            }
        };
        XMLParser parser = new XMLParser();
        parser.setHandler(new XMLWriterHandler(new XMLWriter(channel)));
        try {
            parser.parse(new ByteArrayInputStream("<r>text</r>".getBytes(StandardCharsets.UTF_8)));
            fail("Expected SAXException");
        } catch (SAXException e) {
            assertSame(failure, e.getCause());
        }
    }

    @Test
    public void testMalformedInputIsReportedWithItsDocument() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        XMLParser parser = new XMLParser();
        parser.setSystemId("urn:test:document");
        XMLWriter writer = new XMLWriter(out);
        parser.setHandler(new XMLWriterHandler(writer));
        try {
            parser.parse(new ByteArrayInputStream("<r><s>text</t></r>".getBytes(StandardCharsets.UTF_8)));
            fail("Expected SAXException");
        } catch (org.xml.sax.SAXParseException e) {
            assertEquals("urn:test:document", e.getSystemId());
        }
        // What was well-formed before the error has been written, and
        // is the caller's to send on or not.
        writer.flush();
        assertEquals("<r><s>text", new String(out.toByteArray(), StandardCharsets.UTF_8));
    }

    // ===== Helpers =====

    private static String copy(String xml) throws Exception {
        return copy(xml, true, false);
    }

    private static String copy(String xml, boolean namespaceAware, boolean namespacePrefixes) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        XMLWriter writer = new XMLWriter(out);
        XMLParser parser = new XMLParser();
        parser.setNamespaceAware(namespaceAware);
        parser.setNamespacePrefixes(namespacePrefixes);
        parser.setHandler(new XMLWriterHandler(writer));
        parser.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    /** Copies a document given to the parser in pieces of a fixed size. */
    private static String copy(byte[] bytes, int chunk, boolean namespaceAware) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        XMLWriter writer = new XMLWriter(out);
        XMLParser parser = new XMLParser();
        parser.setNamespaceAware(namespaceAware);
        parser.setHandler(new XMLWriterHandler(writer));
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
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static List<String> events(String xml, boolean namespaceAware) throws Exception {
        RecordingXMLHandler handler = new RecordingXMLHandler();
        XMLParser parser = new XMLParser();
        parser.setNamespaceAware(namespaceAware);
        parser.setHandler(handler);
        parser.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        // An entity reference is written as what it stands for, so the
        // copy has the same content without the entity's boundaries.
        List<String> events = new ArrayList<String>();
        List<String> recorded = handler.getEvents();
        for (int i = 0; i < recorded.size(); i++) {
            String event = recorded.get(i);
            if (!event.startsWith("startEntity:") && !event.startsWith("endEntity:")) {
                events.add(event);
            }
        }
        return events;
    }

    /** Counts what it is given and remembers the largest piece. */
    private static class CountingChannel implements WritableByteChannel {

        long total;
        int largest;

        @Override
        public int write(ByteBuffer src) {
            int n = src.remaining();
            total += n;
            largest = Math.max(largest, n);
            src.position(src.limit());
            return n;
        }

        @Override
        public boolean isOpen() {
            return true;
        }

        @Override
        public void close() {
        }
    }

}

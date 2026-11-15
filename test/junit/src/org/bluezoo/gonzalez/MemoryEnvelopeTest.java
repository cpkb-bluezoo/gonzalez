/*
 * MemoryEnvelopeTest.java
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
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;

import org.junit.Test;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/**
 * Elements, attributes, text, comments and processing instructions are
 * parsed in constant memory: however large any one of them is, the
 * scanner never holds more than the piece it was last given plus, at
 * most, one name.
 *
 * <p>Each test feeds a document in which one construct is made a million
 * characters long, a kilobyte at a time, and checks after every call that
 * the scan buffer is still the size it started at. Nothing here is a
 * timing test, but a buffer that does not grow is also one that is never
 * rescanned from its start, which is what made these inputs quadratic.
 * It also checks that nothing was gathered up elsewhere: no piece of
 * content reaches the handler larger than that buffer.
 *
 * <p>The guarantee rests on the name length limit, which is left at its
 * default here. DTDs are outside it.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class MemoryEnvelopeTest {

    /** The size of the construct under test, in characters. */
    private static final int LARGE = 1000000;

    /** How much the parser is given at a time, in bytes. */
    private static final int CHUNK = 1024;

    // ===== White space inside tags =====

    @Test(timeout = 60000)
    public void testWhitespaceBeforeAttribute() throws Exception {
        Counts counts = parse("<r", spaces(LARGE), "a=\"1\"/>");
        assertEquals(1, counts.elements);
        assertEquals("a=1;", counts.attributes.toString());
    }

    @Test(timeout = 60000)
    public void testWhitespaceBetweenAttributes() throws Exception {
        Counts counts = parse("<r a=\"1\"", spaces(LARGE), "b=\"2\"/>");
        assertEquals("a=1;b=2;", counts.attributes.toString());
    }

    @Test(timeout = 60000)
    public void testWhitespaceAfterAttributeName() throws Exception {
        Counts counts = parse("<r a", spaces(LARGE), "=\"1\"/>");
        assertEquals("a=1;", counts.attributes.toString());
    }

    @Test(timeout = 60000)
    public void testWhitespaceAfterEquals() throws Exception {
        Counts counts = parse("<r a=", spaces(LARGE), "\"1\"/>");
        assertEquals("a=1;", counts.attributes.toString());
    }

    @Test(timeout = 60000)
    public void testWhitespaceBeforeTagEnd() throws Exception {
        Counts counts = parse("<r a=\"1\"", spaces(LARGE), ">t</r>");
        assertEquals("a=1;", counts.attributes.toString());
        assertEquals(1, counts.text);
    }

    @Test(timeout = 60000)
    public void testWhitespaceBeforeEmptyTagEnd() throws Exception {
        Counts counts = parse("<r", spaces(LARGE), "/>");
        assertEquals(1, counts.elements);
        assertEquals(1, counts.endElements);
    }

    @Test(timeout = 60000)
    public void testWhitespaceInEndTag() throws Exception {
        Counts counts = parse("<r>t</r", spaces(LARGE), ">");
        assertEquals(1, counts.endElements);
    }

    @Test(timeout = 60000)
    public void testMixedWhitespaceInTags() throws Exception {
        StringBuilder ws = new StringBuilder(LARGE);
        for (int i = 0; i < LARGE / 4; i++) {
            ws.append(" \t\n ");
        }
        Counts counts = parse("<r", ws.toString(), "a" + ws + "=" + ws + "'1'" + ws + "></r" + ws + ">");
        assertEquals("a=1;", counts.attributes.toString());
        assertEquals(1, counts.endElements);
    }

    // ===== Constructs that are streamed =====

    @Test(timeout = 60000)
    public void testText() throws Exception {
        Counts counts = parse("<r>", letters(LARGE), "</r>");
        assertEquals(LARGE, counts.text);
    }

    @Test(timeout = 60000)
    public void testAttributeValue() throws Exception {
        Counts counts = parse("<r a=\"", letters(LARGE), "\"/>");
        assertEquals(LARGE, counts.attributeValue);
    }

    @Test(timeout = 60000)
    public void testXmlSpaceAttributeValue() throws Exception {
        // The parser checks this attribute's value itself, which is no
        // reason to hold all of it. The value is wrong, but only a
        // validity error, which this handler lets pass.
        Counts counts = parse("<r xml:space=\"", letters(LARGE), "\"/>");
        assertEquals(LARGE, counts.attributeValue);
        assertEquals(1, counts.errors);
    }

    @Test(timeout = 60000)
    public void testComment() throws Exception {
        Counts counts = parse("<r><!--", letters(LARGE), "--></r>");
        assertEquals(LARGE, counts.comment);
    }

    @Test(timeout = 60000)
    public void testProcessingInstruction() throws Exception {
        Counts counts = parse("<r><?target ", letters(LARGE), "?></r>");
        assertEquals(LARGE, counts.piData);
    }

    @Test(timeout = 60000)
    public void testWhitespaceBeforeProcessingInstructionData() throws Exception {
        // It separates the target from the data and is part of neither.
        Counts counts = parse("<r><?target", spaces(LARGE), "data?></r>");
        assertEquals(4, counts.piData);
    }

    @Test(timeout = 60000)
    public void testCDATASection() throws Exception {
        Counts counts = parse("<r><![CDATA[", letters(LARGE), "]]></r>");
        assertEquals(LARGE, counts.text);
    }

    @Test(timeout = 60000)
    public void testCharacterReferences() throws Exception {
        StringBuilder refs = new StringBuilder(LARGE);
        for (int i = 0; i < LARGE / 10; i++) {
            refs.append("&#65;&amp;");
        }
        Counts counts = parse("<r a=\"" + refs + "\">", refs.toString(), "</r>");
        assertEquals(LARGE / 5, counts.text);
        assertEquals(LARGE / 5, counts.attributeValue);
    }

    @Test(timeout = 60000)
    public void testWhitespaceOutsideTheRootElement() throws Exception {
        Counts counts = parse(spaces(LARGE) + "<r/>", spaces(LARGE), "<!-- c -->" + spaces(LARGE));
        assertEquals(1, counts.elements);
    }

    // ===== Many small constructs =====

    @Test(timeout = 60000)
    public void testManyElementsAndAttributes() throws Exception {
        StringBuilder body = new StringBuilder(LARGE + 64);
        int elements = 0;
        while (body.length() < LARGE) {
            body.append("<item id=\"").append(elements).append("\" kind='x'>text</item>\n");
            elements++;
        }
        Counts counts = parse("<r>", body.toString(), "</r>");
        assertEquals(elements + 1, counts.elements);
        assertEquals(elements + 1, counts.endElements);
    }

    @Test(timeout = 60000)
    public void testNamesAtTheLengthLimit() throws Exception {
        // The longest thing the scanner ever has to hold whole.
        String name = letters(4096);
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            body.append('<').append(name).append(' ').append(name).append("=\"v\">t</").append(name).append('>');
        }
        Counts counts = parse("<r>", body.toString(), "</r>");
        assertEquals(101, counts.elements);
        assertEquals(101, counts.endElements);
    }

    // ===== Helpers =====

    private static String spaces(int length) {
        char[] chars = new char[length];
        java.util.Arrays.fill(chars, ' ');
        return new String(chars);
    }

    private static String letters(int length) {
        char[] chars = new char[length];
        for (int i = 0; i < length; i++) {
            chars[i] = (char) ('a' + (i % 26));
        }
        return new String(chars);
    }

    /**
     * Parses the concatenation of the three parts a kilobyte at a time,
     * failing if the scan buffer ever grows.
     */
    private static Counts parse(String before, String large, String after) throws SAXException {
        byte[] bytes = (before + large + after).getBytes(StandardCharsets.UTF_8);
        Counts counts = new Counts();
        XMLParser parser = new XMLParser();
        parser.setHandler(counts);
        ByteBuffer buffer = ByteBuffer.allocate(CHUNK + 8);
        int initialCapacity = 0;
        int offset = 0;
        while (offset < bytes.length) {
            int n = Math.min(CHUNK, bytes.length - offset);
            buffer.put(bytes, offset, n);
            offset += n;
            buffer.flip();
            parser.receive(buffer);
            buffer.compact();
            int capacity = parser.scanBufferCapacity();
            if (initialCapacity == 0) {
                initialCapacity = capacity;
            } else if (capacity != initialCapacity) {
                fail("The scan buffer grew from " + initialCapacity + " to " + capacity
                        + " characters after " + offset + " bytes");
            }
        }
        parser.close();
        if (counts.largestChunk > initialCapacity) {
            fail("The handler was given " + counts.largestChunk + " characters in one piece; the scan buffer is "
                    + initialCapacity);
        }
        return counts;
    }

    /** Counts what is reported, keeping only what is small. */
    private static class Counts implements XMLHandler {

        int elements;
        int endElements;
        int text;
        int attributeValue;
        int comment;
        int piData;
        int errors;
        int largestChunk;
        final StringBuilder attributes = new StringBuilder();
        private boolean keepValue;

        @Override
        public void setLocator(Locator locator) {
        }

        @Override
        public void setXml11(boolean xml11) {
        }

        @Override
        public void startDocument() {
        }

        @Override
        public void endDocument() {
        }

        @Override
        public void startElement(String qName) {
            elements++;
        }

        @Override
        public void namespace(String prefix, String uri) {
        }

        @Override
        public void startAttribute(String name, String type, boolean declared, boolean specified) {
            // Only short names are of interest; the long ones are counted.
            keepValue = name.length() < 16;
            if (keepValue) {
                attributes.append(name).append('=');
            }
        }

        @Override
        public void attributeValueContent(CharBuffer value, boolean end) {
            attributeValue += value.remaining();
            largestChunk = Math.max(largestChunk, value.remaining());
            if (keepValue) {
                if (attributes.length() < 64) {
                    attributes.append(value);
                }
                if (end) {
                    attributes.append(';');
                }
            }
        }

        @Override
        public void endAttributes() {
        }

        @Override
        public void characters(CharBuffer chunk, boolean ignorable, boolean end) {
            text += chunk.remaining();
            largestChunk = Math.max(largestChunk, chunk.remaining());
        }

        @Override
        public void endElement() {
            endElements++;
        }

        @Override
        public void startComment() {
        }

        @Override
        public void commentData(CharBuffer chunk, boolean end) {
            comment += chunk.remaining();
            largestChunk = Math.max(largestChunk, chunk.remaining());
        }

        @Override
        public void startCDATA() {
        }

        @Override
        public void endCDATA() {
        }

        @Override
        public void startDTD(String name, String publicId, String systemId) {
        }

        @Override
        public void endDTD() {
        }

        @Override
        public void startEntity(String name) {
        }

        @Override
        public void endEntity(String name) {
        }

        @Override
        public void notationDecl(String name, String publicId, String systemId) {
        }

        @Override
        public void unparsedEntityDecl(String name, String publicId, String systemId, String notationName) {
        }

        @Override
        public void piTarget(String target) {
        }

        @Override
        public void piData(CharBuffer chunk, boolean end) {
            piData += chunk.remaining();
            largestChunk = Math.max(largestChunk, chunk.remaining());
        }

        @Override
        public void saveBuffers() {
        }

        @Override
        public SAXException fatalError(String message) {
            return new SAXException(message);
        }

        @Override
        public void error(String message) {
            errors++;
        }
    }

}

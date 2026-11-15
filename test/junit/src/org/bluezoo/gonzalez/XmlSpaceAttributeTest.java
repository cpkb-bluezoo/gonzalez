/*
 * XmlSpaceAttributeTest.java
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
import java.util.Arrays;
import java.util.List;

import org.junit.Test;
import org.xml.sax.SAXException;

import static org.junit.Assert.assertEquals;

/**
 * The value of {@code xml:space} must be "default" or "preserve" (XML
 * 2.10). The parser checks that itself, with or without a DTD, and
 * reports anything else as a recoverable error; the value is passed on
 * unchanged either way.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class XmlSpaceAttributeTest {

    private static final String ERROR =
            "error:The \"xml:space\" attribute's value must be \"default\" or \"preserve\", not \"";

    @Test
    public void testLegalValues() throws Exception {
        assertEquals(events("attribute:xml:space=default"), parseAtEverySplit("<r xml:space=\"default\"/>"));
        assertEquals(events("attribute:xml:space=preserve"), parseAtEverySplit("<r xml:space='preserve'/>"));
    }

    @Test
    public void testIllegalValuesAreReportedAndPassedOn() throws Exception {
        String[] values = { "", "x", "Default", "defaul", "defaultx", "preserved", "default ", " preserve",
            "pre&#115;erve" };
        for (int i = 0; i < values.length; i++) {
            // A reference is reported as what it stands for.
            String reported = values[i].replace("&#115;", "s");
            boolean legal = reported.equals("preserve");
            List<String> expected = legal
                    ? events("attribute:xml:space=" + reported)
                    : events(ERROR + reported + "\".", "attribute:xml:space=" + reported);
            assertEquals("value [" + values[i] + "]", expected,
                    parseAtEverySplit("<r xml:space=\"" + values[i] + "\"/>"));
        }
    }

    @Test
    public void testLongIllegalValueIsQuotedInPart() throws Exception {
        StringBuilder value = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            value.append((char) ('a' + (i % 26)));
        }
        List<String> expected = events(ERROR + value.substring(0, 32) + "...\".",
                "attribute:xml:space=" + value);
        assertEquals(expected, parseAtEverySplit("<r xml:space=\"" + value + "\"/>"));
    }

    @Test
    public void testAValueThatOnlyBeginsLegallyIsStillIllegal() throws Exception {
        // Longer than is kept for the error message, and legal up to there.
        String value = "preserve" + "                                        ";
        List<String> expected = events(ERROR + value.substring(0, 32) + "...\".",
                "attribute:xml:space=" + value);
        assertEquals(expected, parseAtEverySplit("<r xml:space=\"" + value + "\"/>"));
    }

    @Test
    public void testOtherAttributesAreUnaffected() throws Exception {
        assertEquals(Arrays.asList("startDocument", "startElement:r", "attribute:a=1",
                "attribute:xml:space=default", "attribute:space=anything", "attribute:xml:lang=en",
                "endAttributes", "endElement", "endDocument"),
                parseAtEverySplit("<r a=\"1\" xml:space=\"default\" space=\"anything\" xml:lang=\"en\"/>"));
    }

    // ===== Helpers =====

    /** The events of a document that is one empty element {@code r}
     *  whose attribute events (and any error before them) are given. */
    private static List<String> events(String... attributeEvents) {
        List<String> events = new ArrayList<String>();
        events.add("startDocument");
        events.add("startElement:r");
        events.addAll(Arrays.asList(attributeEvents));
        events.add("endAttributes");
        events.add("endElement");
        events.add("endDocument");
        return events;
    }

    /** Parses the document whole and split at every position, checking
     *  that the events are always the same, and returns them. */
    private static List<String> parseAtEverySplit(String xml) throws SAXException {
        byte[] bytes = xml.getBytes(StandardCharsets.UTF_8);
        List<String> whole = parse(bytes, bytes.length);
        for (int split = 1; split < bytes.length; split++) {
            assertEquals("split at " + split + " of " + xml, whole, parse(bytes, split));
        }
        return whole;
    }

    private static List<String> parse(byte[] bytes, int split) throws SAXException {
        RecordingXMLHandler handler = new RecordingXMLHandler();
        XMLParser parser = new XMLParser();
        parser.setHandler(handler);
        ByteBuffer buffer = ByteBuffer.allocate(bytes.length + 8);
        buffer.put(bytes, 0, split);
        buffer.flip();
        parser.receive(buffer);
        buffer.compact();
        if (split < bytes.length) {
            buffer.put(bytes, split, bytes.length - split);
            buffer.flip();
            parser.receive(buffer);
            buffer.compact();
        }
        parser.close();
        return new ArrayList<String>(handler.getEvents());
    }

}

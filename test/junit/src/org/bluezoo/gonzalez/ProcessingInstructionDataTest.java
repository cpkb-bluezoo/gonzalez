/*
 * ProcessingInstructionDataTest.java
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
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The white space between a processing instruction's target and its data
 * separates the two and belongs to neither (XML 2.6, production 16: the
 * {@code S} is as much white space as there is). The data begins at the
 * first character after it, however much of it there was and however the
 * input was divided.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ProcessingInstructionDataTest {

    /** Each processing instruction, and the data it should be reported with. */
    private static final String[][] CASES = {
        { "<?t d?>", "d" },
        { "<?t   d?>", "d" },
        { "<?t\td?>", "d" },
        { "<?t \t\n \n\td?>", "d" },
        { "<?t\n\n\nd e  f ?>", "d e  f " },
        { "<?t?>", "" },
        { "<?t ?>", "" },
        { "<?t    ?>", "" },
        { "<?t \n\t ?>", "" },
        { "<?t   ? ?>", "? " },
        { "<?t   ??>", "?" },
        { "<?t  a=\"1\"  b='2' ?>", "a=\"1\"  b='2' " },
    };

    @Test
    public void testSeparatorIsNotPartOfTheData() throws Exception {
        for (int i = 0; i < CASES.length; i++) {
            assertEquals(CASES[i][0], "pi:t=" + CASES[i][1], piEvent(parse("<r>" + CASES[i][0] + "</r>", 0)));
        }
    }

    @Test
    public void testOutsideTheRootElement() throws Exception {
        for (int i = 0; i < CASES.length; i++) {
            assertEquals(CASES[i][0], "pi:t=" + CASES[i][1], piEvent(parse(CASES[i][0] + "<r/>", 0)));
            assertEquals(CASES[i][0], "pi:t=" + CASES[i][1], piEvent(parse("<r/>" + CASES[i][0], 0)));
        }
    }

    @Test
    public void testInTheInternalSubset() throws Exception {
        for (int i = 0; i < CASES.length; i++) {
            assertEquals(CASES[i][0], "pi:t=" + CASES[i][1],
                    piEvent(parse("<!DOCTYPE r [" + CASES[i][0] + "]><r/>", 0)));
        }
    }

    @Test
    public void testSameHoweverTheInputIsDivided() throws Exception {
        for (int i = 0; i < CASES.length; i++) {
            String xml = "<r>" + CASES[i][0] + "</r>";
            String subset = "<!DOCTYPE r [" + CASES[i][0] + "]><r/>";
            List<String> whole = parse(xml, 0);
            List<String> wholeSubset = parse(subset, 0);
            for (int chunk = 1; chunk <= 5; chunk++) {
                assertEquals(CASES[i][0] + " in pieces of " + chunk, whole, parse(xml, chunk));
                assertEquals(CASES[i][0] + " in the subset in pieces of " + chunk, wholeSubset,
                        parse(subset, chunk));
            }
        }
    }

    @Test
    public void testThroughSax() throws Exception {
        for (int i = 0; i < CASES.length; i++) {
            final List<String> seen = new ArrayList<String>();
            Parser parser = new Parser();
            parser.setContentHandler(new DefaultHandler() {
                @Override
                public void processingInstruction(String target, String data) {
                    seen.add(target + "=" + data);
                }
            });
            parser.parse(new InputSource(new ByteArrayInputStream(
                    ("<r>" + CASES[i][0] + "</r>").getBytes(StandardCharsets.UTF_8))));
            assertEquals(CASES[i][0], "[t=" + CASES[i][1] + "]", seen.toString());
        }
    }

    @Test
    public void testWhitespaceIsStillRequired() throws Exception {
        String[] malformed = { "<r><?t+d?></r>", "<r><?t\"d\"?></r>" };
        for (int i = 0; i < malformed.length; i++) {
            try {
                parse(malformed[i], 0);
                fail("Expected SAXException for " + malformed[i]);
            } catch (SAXException e) {
                assertTrue("Was: " + e.getMessage(), e.getMessage().contains("White space is required"));
            }
        }
    }

    @Test
    public void testDocumentEndingInTheSeparatorIsNotWellFormed() throws Exception {
        String[] truncated = { "<r/><?t", "<r/><?t ", "<r/><?t   ", "<r><?t   " };
        for (int i = 0; i < truncated.length; i++) {
            for (int chunk = 0; chunk <= 2; chunk++) {
                try {
                    parse(truncated[i], chunk);
                    fail("Expected SAXException for " + truncated[i]);
                } catch (SAXException e) {
                    // expected
                }
            }
        }
    }

    // ===== Helpers =====

    /** The one processing instruction event among those recorded. */
    private static String piEvent(List<String> events) {
        String found = null;
        for (int i = 0; i < events.size(); i++) {
            if (events.get(i).startsWith("pi:")) {
                assertEquals("More than one processing instruction in " + events, null, found);
                found = events.get(i);
            }
        }
        return found;
    }

    /** Parses a document whole, or in pieces of the given size if that
     *  is not zero. */
    private static List<String> parse(String xml, int chunk) throws SAXException {
        byte[] bytes = xml.getBytes(StandardCharsets.UTF_8);
        if (chunk == 0) {
            chunk = bytes.length;
        }
        RecordingXMLHandler handler = new RecordingXMLHandler();
        XMLParser parser = new XMLParser();
        parser.setHandler(handler);
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
        return new ArrayList<String>(handler.getEvents());
    }

}

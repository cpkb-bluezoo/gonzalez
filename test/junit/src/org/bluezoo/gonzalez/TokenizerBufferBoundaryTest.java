/*
 * TokenizerBufferBoundaryTest.java
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

import org.junit.Test;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

import static org.junit.Assert.*;

/**
 * Regression tests for a Tokenizer bug where multi-character keyword lookahead
 * ("CDATA[" after "&lt;![", "OCTYPE" after "&lt;!D") that straddles the
 * ExternalEntityDecoder's internal 32768-character buffer refill boundary left
 * the tokenizer's line/column tracking permanently desynchronized, eventually
 * causing catchUpLocation() to compute a negative array index and throw
 * ArrayIndexOutOfBoundsException.
 *
 * <p>The fix tracks tokenStartCharPosition alongside tokenStartPos and rolls
 * charPosition back in lockstep whenever a token is abandoned mid-recognition
 * for reprocessing, so the underflow path no longer skips the location
 * catch-up that every other exit path performs. These tests sweep a range of
 * filler lengths so that the multi-character keyword lands at or near the
 * buffer boundary regardless of small differences in exactly how many
 * characters precede it, since the failure is sensitive to the precise byte
 * offset at which the lookahead sequence starts.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class TokenizerBufferBoundaryTest {

    /**
     * The ExternalEntityDecoder's internal char buffer capacity (see
     * ExternalEntityDecoder.MAX_CHAR_BUFFER). The bug requires a multi-character
     * keyword lookahead to straddle a multiple of this value.
     */
    private static final int BUFFER_SIZE = 32768;

    /**
     * How far on either side of the buffer boundary to sweep the filler length,
     * to reliably land the lookahead sequence across the exact underflow-triggering
     * offset regardless of a few characters of uncertainty in the surrounding markup.
     */
    private static final int SWEEP_RADIUS = 20;

    private static String repeat(char c, int count) {
        StringBuilder sb = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            sb.append(c);
        }
        return sb.toString();
    }

    private static class TextCollector extends DefaultHandler {
        final StringBuilder text = new StringBuilder();

        @Override
        public void characters(char[] ch, int start, int length) {
            text.append(ch, start, length);
        }
    }

    private void parse(String xml, DefaultHandler handler) throws Exception {
        Parser parser = new Parser();
        parser.setContentHandler(handler);
        ByteArrayInputStream bais = new ByteArrayInputStream(xml.getBytes("UTF-8"));
        parser.parse(new InputSource(bais));
    }

    // --- CDATA section "CDATA[" lookahead straddling the buffer boundary ---

    @Test
    public void testCDATASectionStraddlingBufferBoundary() throws Exception {
        for (int filler = BUFFER_SIZE - SWEEP_RADIUS; filler <= BUFFER_SIZE + SWEEP_RADIUS; filler++) {
            String xml = "<doc>" + repeat('x', filler) + "<![CDATA[hello world]]></doc>";
            TextCollector handler = new TextCollector();
            try {
                parse(xml, handler);
            } catch (Exception e) {
                fail("Filler length " + filler + " threw " + e + " (should parse cleanly)");
            }
            assertTrue("Filler length " + filler + ": expected CDATA content in collected text",
                    handler.text.toString().contains("hello world"));
        }
    }

    // --- DOCTYPE "OCTYPE" lookahead straddling the buffer boundary ---
    // Plain text isn't allowed in the prolog, so a single long comment is used
    // as filler instead of repeated characters.

    @Test
    public void testDoctypeStraddlingBufferBoundary() throws Exception {
        for (int filler = BUFFER_SIZE - SWEEP_RADIUS; filler <= BUFFER_SIZE + SWEEP_RADIUS; filler++) {
            String xml = "<!--" + repeat('x', filler) + "--><!DOCTYPE doc [<!ELEMENT doc ANY>]><doc/>";
            TextCollector handler = new TextCollector();
            try {
                parse(xml, handler);
            } catch (Exception e) {
                fail("Filler length " + filler + " threw " + e + " (should parse cleanly)");
            }
        }
    }

    // --- Line number accuracy across a buffer-boundary straddle ---

    @Test
    public void testLineNumberAccurateAfterBufferBoundaryStraddle() throws Exception {
        // Place a CDATA section straddling the boundary, then a well-formedness
        // error (element name starting with a digit) a known number of lines later.
        // The reported line number must exactly match, proving location tracking
        // wasn't left desynchronized by the straddle.
        int filler = BUFFER_SIZE - 3;
        String prefix = "<doc>" + repeat('x', filler) + "<![CDATA[hello]]>\n";
        int prefixLines = 1;
        for (int i = 0; i < prefix.length(); i++) {
            if (prefix.charAt(i) == '\n') {
                prefixLines++;
            }
        }
        String xml = prefix + "<1bad/>\n</doc>";

        DefaultHandler handler = new DefaultHandler();
        try {
            parse(xml, handler);
            fail("Expected a well-formedness error for '<1bad/>'");
        } catch (SAXParseException e) {
            assertEquals("Reported line number should match the actual error location",
                    prefixLines, e.getLineNumber());
        }
    }
}

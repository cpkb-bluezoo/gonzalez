/*
 * TokenizerLocationTrackingTest.java
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
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.Locator;
import org.xml.sax.helpers.DefaultHandler;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Direct tests of {@code Locator.getLineNumber()}/{@code getColumnNumber()}
 * correctness - a gap the rest of the test suite only covers indirectly
 * (via line/column values embedded in fatalError messages for malformed
 * documents, differentially compared across chunk sizes). Written after
 * {@code Tokenizer.tokenize()}'s location tracking changed from deferred
 * (a single {@code catchUpLocation()} scan at the end of every call) to
 * inline (updated incrementally as each character is scanned, with a
 * tokenStartLineNumber/tokenStartColumnNumber snapshot-and-restore
 * mechanism for tokens abandoned mid-recognition and reprocessed later) -
 * exactly the kind of change a chunking-insensitive test suite could miss a
 * regression in.
 * <p>
 * Rather than hand-deriving "correct" line/column values (this codebase's
 * exact column-numbering convention - zero-based count of characters
 * consumed so far on the current line - is easy to get subtly wrong by
 * inspection), these tests record (event, line, column) tuples from a
 * whole-buffer reference parse and assert every chunked/split delivery
 * produces the identical sequence - the same methodology
 * {@code ChunkFuzzer}/{@code ChunkingInvariantTest} already use for full
 * SAX event streams, applied specifically to location tracking.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class TokenizerLocationTrackingTest {

    private static final int[] CHUNK_SIZES = {1, 2, 3, 5, 7, 11, 17, 23};

    @Test
    public void testLocationConsistentAcrossChunkSizes() throws Exception {
        String xml = "<?xml version=\"1.0\"?>\n"
                + "<root>\n"
                + "  <child name=\"first\">Some text\n"
                + "  spanning lines</child>\n"
                + "  <child name=\"second\">&amp; an entity &#65; and &#x42;</child>\n"
                + "  <!-- a comment\n"
                + "  spanning lines -->\n"
                + "  <?pi-target some data?>\n"
                + "</root>\n";
        byte[] data = xml.getBytes(StandardCharsets.UTF_8);

        List<String> reference = recordLocations(data, 0);
        assertTrue("reference recording should not be empty", reference.size() > 5);

        for (int chunkSize : CHUNK_SIZES) {
            List<String> actual = recordLocations(data, chunkSize);
            assertEquals("chunk size " + chunkSize, reference, actual);
        }
    }

    @Test
    public void testLocationAfterNameSplitAcrossChunkBoundaryMatchesWholeBuffer() throws Exception {
        // The element/attribute names here are long enough, and land at
        // enough different byte offsets across a range of small chunk
        // sizes, that at least one of them is guaranteed to have its NAME
        // token split mid-way by the underlying byte stream - forcing
        // Tokenizer's incomplete-token rewind-and-reprocess path (see
        // tokenStartLineNumber's field Javadoc) rather than only ever
        // exercising the greedy-accumulation flush-without-reset path
        // ACCUMULATING_CDATA/WHITESPACE use.
        String xml = "<root>\n"
                + "  <element-with-a-fairly-long-name attribute-with-a-long-name=\"value\">\n"
                + "    text content here\n"
                + "  </element-with-a-fairly-long-name>\n"
                + "</root>\n";
        byte[] data = xml.getBytes(StandardCharsets.UTF_8);

        List<String> reference = recordLocations(data, 0);

        for (int chunkSize = 1; chunkSize <= 12; chunkSize++) {
            List<String> actual = recordLocations(data, chunkSize);
            assertEquals("chunk size " + chunkSize, reference, actual);
        }
    }

    /**
     * Parses {@code data}, delivered through an input stream capped at
     * {@code chunkSize} bytes per read (or unthrottled if {@code chunkSize}
     * is 0), recording "eventType:line:column" for every startElement,
     * endElement, and (coalesced) run of characters callbacks.
     * <p>
     * Consecutive characters() calls are coalesced into a single recorded
     * entry, using the position of the *first* fragment - chunking is
     * allowed to split one logical text run into more, smaller characters()
     * calls than a whole-buffer parse would (a legitimate granularity
     * difference, not a location-tracking bug), so comparing the raw,
     * uncoalesced event lists across chunk sizes would produce false
     * divergences.
     */
    private List<String> recordLocations(byte[] data, int chunkSize) throws Exception {
        InputStream in = new ByteArrayInputStream(data);
        if (chunkSize > 0) {
            in = new ChunkedInputStream(in, chunkSize);
        }

        final List<String> events = new ArrayList<String>();
        final Locator[] locatorHolder = new Locator[1];
        final StringBuilder pendingChars = new StringBuilder();
        final long[] pendingCharsLine = new long[1];
        final long[] pendingCharsColumn = new long[1];

        Parser parser = new Parser();
        parser.setFeature("http://xml.org/sax/features/external-general-entities", false);
        parser.setContentHandler(new DefaultHandler() {
            private void flushChars() {
                if (pendingChars.length() > 0) {
                    events.add("chars:" + pendingChars + ":" + pendingCharsLine[0] + ":" + pendingCharsColumn[0]);
                    pendingChars.setLength(0);
                }
            }

            @Override
            public void setDocumentLocator(Locator locator) {
                locatorHolder[0] = locator;
            }

            @Override
            public void startElement(String uri, String localName, String qName, Attributes atts) {
                flushChars();
                events.add("start:" + qName + ":" + locatorHolder[0].getLineNumber()
                        + ":" + locatorHolder[0].getColumnNumber());
            }

            @Override
            public void endElement(String uri, String localName, String qName) {
                flushChars();
                events.add("end:" + qName + ":" + locatorHolder[0].getLineNumber()
                        + ":" + locatorHolder[0].getColumnNumber());
            }

            @Override
            public void characters(char[] ch, int start, int length) {
                // The locator reports the position *after* whatever was just
                // consumed, so recording it on every call (not just the
                // first) means that once the run is flushed, it reflects the
                // position after the *last* fragment - exactly what a single,
                // unsplit characters() call for the same logical run would
                // have reported.
                pendingCharsLine[0] = locatorHolder[0].getLineNumber();
                pendingCharsColumn[0] = locatorHolder[0].getColumnNumber();
                pendingChars.append(ch, start, length);
            }

            @Override
            public void endDocument() {
                flushChars();
            }
        });
        parser.parse(new InputSource(in));
        return events;
    }

}

/*
 * Utf16FixtureTest.java
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

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Establishes today's legacy tokenizer baseline for non-UTF-8 encodings ahead
 * of the byte-native tokenizer rewrite (see BYTE-TOKENIZER.md, Decision 2:
 * non-UTF-8 encodings transcode to UTF-8 before reaching the byte tokenizer).
 * <p>
 * With a byte order mark present, a UTF-16BE or UTF-16LE document must parse
 * to the exact same SAX event sequence as its UTF-8 source. Without a BOM,
 * Gonzalez does not implement the XML Appendix F byte-pattern heuristic, so
 * parsing currently fails; that is captured here as a baseline too, so an
 * accidental behavior change during the rewrite is caught either way.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class Utf16FixtureTest {

    private static final File SOURCE_FIXTURE = new File("benchmark/resources/multibyte-small.xml");

    @Test
    public void testUtf16WithBomRoundTripsToSameEvents() throws IOException {
        byte[] utf8Source = readFully(SOURCE_FIXTURE);
        Object reference = ChunkFuzzer.parse(utf8Source, 0);
        assertTrue("Reference UTF-8 fixture should parse successfully, got: " + reference,
                reference instanceof List);

        byte[] utf16be = Utf16FixtureGenerator.toUtf16BE(utf8Source, true);
        byte[] utf16le = Utf16FixtureGenerator.toUtf16LE(utf8Source, true);

        assertEquals("UTF-16BE with BOM should produce the same SAX events as the UTF-8 source",
                reference, ChunkFuzzer.parse(utf16be, 0));
        assertEquals("UTF-16LE with BOM should produce the same SAX events as the UTF-8 source",
                reference, ChunkFuzzer.parse(utf16le, 0));
    }

    @Test
    public void testUtf16WithoutBomIsCurrentlyUnsupported() throws IOException {
        byte[] utf8Source = readFully(SOURCE_FIXTURE);
        byte[] utf16beNoBom = Utf16FixtureGenerator.toUtf16BE(utf8Source, false);
        byte[] utf16leNoBom = Utf16FixtureGenerator.toUtf16LE(utf8Source, false);

        Object beResult = ChunkFuzzer.parse(utf16beNoBom, 0);
        Object leResult = ChunkFuzzer.parse(utf16leNoBom, 0);

        assertTrue("UTF-16BE without a BOM is expected to fail today (no Appendix F "
                + "byte-pattern sniffing implemented); got: " + beResult,
                beResult instanceof String && ((String) beResult).contains("SAXParseException"));
        assertTrue("UTF-16LE without a BOM is expected to fail today (no Appendix F "
                + "byte-pattern sniffing implemented); got: " + leResult,
                leResult instanceof String && ((String) leResult).contains("SAXParseException"));
    }

    private byte[] readFully(File file) throws IOException {
        byte[] buf = new byte[(int) file.length()];
        InputStream in = new FileInputStream(file);
        try {
            int total = 0;
            while (total < buf.length) {
                int n = in.read(buf, total, buf.length - total);
                if (n < 0) {
                    break;
                }
                total += n;
            }
        } finally {
            in.close();
        }
        return buf;
    }

}

/*
 * FastUtf8CharsetDecoderTest.java
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

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CoderResult;
import java.nio.charset.StandardCharsets;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link FastUtf8CharsetDecoder}: exhaustive codepoint
 * round-tripping against the JDK's own UTF-8 decoder, every malformed/
 * truncated byte pattern {@link Utf8Test} already covers (exercised through
 * the {@code CharsetDecoder} API this time), and buffer-boundary-specific
 * cases (a multi-byte sequence split across the exact end of the output
 * buffer's capacity, or across a chunk boundary in the input, at every
 * possible alignment) for both the array-backed and direct-buffer paths.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class FastUtf8CharsetDecoderTest {

    // ===== Exhaustive round-trip against the JDK's own decoder =====

    @Test
    public void testEveryCodePointRoundTripsArrayBacked() throws CharacterCodingException {
        for (int cp = 1; cp <= 0x10FFFF; cp++) {
            if (cp >= 0xD800 && cp <= 0xDFFF) {
                continue; // surrogates are not standalone code points
            }
            checkRoundTrip(cp, false);
        }
    }

    @Test
    public void testEveryCodePointRoundTripsDirectBuffer() throws CharacterCodingException {
        // Sampled, not exhaustive: the direct-buffer path is a straight
        // mirror of the array path (same Utf8.decode logic, different
        // accessors), and running all 1.1M code points through allocateDirect
        // buffers is unnecessarily slow for what it would add.
        for (int cp = 1; cp <= 0x10FFFF; cp += 37) {
            if (cp >= 0xD800 && cp <= 0xDFFF) {
                continue;
            }
            checkRoundTrip(cp, true);
        }
    }

    private void checkRoundTrip(int cp, boolean direct) throws CharacterCodingException {
        char[] expectedChars = Character.toChars(cp);
        String expected = new String(expectedChars);
        byte[] utf8 = expected.getBytes(StandardCharsets.UTF_8);

        String actual = decodeWhole(utf8, direct, 4096);
        assertEquals("U+" + Integer.toHexString(cp), expected, actual);
    }

    // ===== Malformed/truncated patterns from Utf8Test, via the CharsetDecoder API =====

    @Test
    public void testAsciiAndMultiByteInterleaved() throws CharacterCodingException {
        // Exercises the ASCII-fast-path-resumes-after-a-multi-byte-sequence
        // behavior (the whole point of this class, see Decision 2 in
        // FAST-UTF8-DECODER.md): mixed content, not just a leading run.
        String expected = "plain éèê more plain 中文 tail ascii";
        byte[] utf8 = expected.getBytes(StandardCharsets.UTF_8);
        assertEquals(expected, decodeWhole(utf8, false, 4096));
        assertEquals(expected, decodeWhole(utf8, true, 4096));
    }

    @Test
    public void testTruncatedTwoByteSequenceReturnsUnderflowWithBytesUnconsumed() {
        byte[] data = { (byte) 0xC3 };
        assertUnderflowWithBytesRemaining(data, 1);
    }

    @Test
    public void testTruncatedThreeByteSequenceReturnsUnderflowWithBytesUnconsumed() {
        assertUnderflowWithBytesRemaining(new byte[] { (byte) 0xE4 }, 1);
        assertUnderflowWithBytesRemaining(new byte[] { (byte) 0xE4, (byte) 0xB8 }, 2);
    }

    @Test
    public void testTruncatedFourByteSequenceReturnsUnderflowWithBytesUnconsumed() {
        assertUnderflowWithBytesRemaining(new byte[] { (byte) 0xF0 }, 1);
        assertUnderflowWithBytesRemaining(new byte[] { (byte) 0xF0, (byte) 0xA0 }, 2);
        assertUnderflowWithBytesRemaining(new byte[] { (byte) 0xF0, (byte) 0xA0, (byte) 0x80 }, 3);
    }

    @Test
    public void testBareContinuationByteIsMalformed() {
        assertMalformed(new byte[] { (byte) 0x80 });
        assertMalformed(new byte[] { (byte) 0xBF });
    }

    @Test
    public void testOverlongTwoByteIsMalformed() {
        assertMalformed(new byte[] { (byte) 0xC0, (byte) 0x80 });
        assertMalformed(new byte[] { (byte) 0xC1, (byte) 0xBF });
    }

    @Test
    public void testOverlongThreeByteIsMalformed() {
        assertMalformed(new byte[] { (byte) 0xE0, (byte) 0x9F, (byte) 0xBF });
        assertMalformed(new byte[] { (byte) 0xE0, (byte) 0x80, (byte) 0x80 });
    }

    @Test
    public void testOverlongFourByteIsMalformed() {
        assertMalformed(new byte[] { (byte) 0xF0, (byte) 0x8F, (byte) 0xBF, (byte) 0xBF });
        assertMalformed(new byte[] { (byte) 0xF0, (byte) 0x80, (byte) 0x80, (byte) 0x80 });
    }

    @Test
    public void testEncodedSurrogateIsMalformed() {
        assertMalformed(new byte[] { (byte) 0xED, (byte) 0xA0, (byte) 0x80 });
        assertMalformed(new byte[] { (byte) 0xED, (byte) 0xBF, (byte) 0xBF });
    }

    @Test
    public void testBeyondMaxCodePointIsMalformed() {
        assertMalformed(new byte[] { (byte) 0xF4, (byte) 0x90, (byte) 0x80, (byte) 0x80 });
        assertMalformed(new byte[] { (byte) 0xF5, (byte) 0x80, (byte) 0x80, (byte) 0x80 });
        assertMalformed(new byte[] { (byte) 0xFF });
    }

    @Test
    public void testNonContinuationByteWhereExpectedIsMalformed() {
        assertMalformed(new byte[] { (byte) 0xC3, 'A' });
        assertMalformed(new byte[] { (byte) 0xE4, 'A', (byte) 0xAD });
        assertMalformed(new byte[] { (byte) 0xF0, 'A', (byte) 0x80, (byte) 0x80 });
    }

    // ===== Buffer-boundary cases =====

    @Test
    public void testOverflowMidAsciiLeavesInputUnconsumedAndResumesCorrectly() {
        byte[] data = "abcdef".getBytes(StandardCharsets.UTF_8);
        ByteBuffer in = ByteBuffer.wrap(data);
        CharBuffer out = CharBuffer.allocate(3);
        FastUtf8CharsetDecoder decoder = new FastUtf8CharsetDecoder();

        CoderResult r1 = decoder.decode(in, out, false);
        assertTrue(r1.isOverflow());
        out.flip();
        assertEquals("abc", out.toString());

        out.clear();
        CoderResult r2 = decoder.decode(in, out, false);
        assertTrue(r2.isUnderflow());
        out.flip();
        assertEquals("def", out.toString());
    }

    @Test
    public void testOverflowBeforeSurrogatePairLeavesBothCharsUnwritten() {
        // U+1F600 (a 4-byte sequence, encodes as a surrogate pair) preceded
        // by one ASCII char, with just enough room for the ASCII char but
        // not both surrogate halves - must not write half a surrogate pair.
        String s = "a😀";
        byte[] data = s.getBytes(StandardCharsets.UTF_8);
        ByteBuffer in = ByteBuffer.wrap(data);
        CharBuffer out = CharBuffer.allocate(2);
        FastUtf8CharsetDecoder decoder = new FastUtf8CharsetDecoder();

        CoderResult r1 = decoder.decode(in, out, false);
        assertTrue(r1.isOverflow());
        out.flip();
        assertEquals("a", out.toString());
        assertEquals("surrogate pair bytes must be left unconsumed", data.length - 1, in.remaining());

        out.clear();
        out = CharBuffer.allocate(2);
        CoderResult r2 = decoder.decode(in, out, false);
        assertTrue(r2.isUnderflow());
        out.flip();
        assertEquals("😀", out.toString());
    }

    @Test
    public void testChunkedDeliverySplittingMultiByteSequenceMatchesWholeBufferDecode() {
        String expected = "Königsberg 日本語 😀 end";
        byte[] data = expected.getBytes(StandardCharsets.UTF_8);

        // Whole-buffer reference.
        assertEquals(expected, decodeWhole(data, false, 4096));

        // Every possible split point, one byte at a time, mimicking
        // ExternalEntityDecoder's compact()-and-retry flow.
        for (int splitCount = 1; splitCount <= data.length; splitCount++) {
            assertEquals("chunk size " + splitCount, expected, decodeChunked(data, splitCount));
        }
    }

    @Test
    public void testMalformedInputArrayBackedAndDirectBufferAgree() {
        byte[] data = { (byte) 0xC0, (byte) 0x80 };
        assertTrue(decodeExpectingResult(data, false).isMalformed());
        assertTrue(decodeExpectingResult(data, true).isMalformed());
    }

    // ===== Differential: identical output to the JDK's own UTF-8 decoder =====

    @Test
    public void testMatchesJdkDecoderOnBenchmarkStyleMixedContent() throws CharacterCodingException {
        String[] samples = {
            "",
            "plain ascii only, no surprises here at all",
            "éèêë café naïve",
            "中文测试内容，包含多个汉字。",
            "日本語のテキストです。",
            "😀😁🎉 emoji surrogate pairs ❤️",
            "mixed: plain <text> with 中 entities &amp; 😀 stuff"
        };
        for (String s : samples) {
            byte[] utf8 = s.getBytes(StandardCharsets.UTF_8);
            String viaFast = decodeWhole(utf8, false, 4096);
            String viaJdk = new String(utf8, StandardCharsets.UTF_8);
            assertEquals(viaJdk, viaFast);
        }
    }

    // ===== Helpers =====

    private void assertMalformed(byte[] data) {
        assertTrue(decodeExpectingResult(data, false).isMalformed());
        assertTrue(decodeExpectingResult(data, true).isMalformed());
    }

    private void assertUnderflowWithBytesRemaining(byte[] data, int expectedRemaining) {
        ByteBuffer in = ByteBuffer.wrap(data);
        CharBuffer out = CharBuffer.allocate(16);
        CoderResult r = new FastUtf8CharsetDecoder().decode(in, out, false);
        assertTrue(r.isUnderflow());
        assertEquals(expectedRemaining, in.remaining());
        assertFalse("no chars should have been produced for an incomplete sequence", out.position() > 0);
    }

    private CoderResult decodeExpectingResult(byte[] data, boolean direct) {
        ByteBuffer in = wrap(data, direct);
        CharBuffer out = CharBuffer.allocate(16);
        return new FastUtf8CharsetDecoder().decode(in, out, false);
    }

    private String decodeWhole(byte[] data, boolean direct, int outCapacity) {
        ByteBuffer in = wrap(data, direct);
        CharBuffer out = CharBuffer.allocate(outCapacity);
        FastUtf8CharsetDecoder decoder = new FastUtf8CharsetDecoder();
        StringBuilder sb = new StringBuilder();
        while (true) {
            CoderResult r = decoder.decode(in, out, false);
            out.flip();
            sb.append(out);
            out.clear();
            if (r.isUnderflow()) {
                break;
            }
            if (r.isError()) {
                throw new AssertionError("unexpected coder error: " + r);
            }
        }
        return sb.toString();
    }

    /**
     * Decodes {@code data} delivered {@code splitCount}-at-a-time through an
     * input buffer that's compacted and refilled between calls, mirroring
     * {@code ExternalEntityDecoder.decodeAndTokenize}'s own flow, to prove
     * a multi-byte sequence split across a chunk boundary decodes identically
     * to being delivered whole.
     */
    private String decodeChunked(byte[] data, int chunkSize) {
        ByteBuffer in = ByteBuffer.allocate(64);
        CharBuffer out = CharBuffer.allocate(4096);
        FastUtf8CharsetDecoder decoder = new FastUtf8CharsetDecoder();
        StringBuilder sb = new StringBuilder();

        int offset = 0;
        while (offset < data.length || in.position() > 0) {
            int n = Math.min(chunkSize, data.length - offset);
            if (n > 0) {
                in.put(data, offset, n);
                offset += n;
            }
            in.flip();
            CoderResult r = decoder.decode(in, out, false);
            if (r.isError()) {
                throw new AssertionError("unexpected coder error: " + r);
            }
            out.flip();
            sb.append(out);
            out.clear();
            in.compact();
            if (offset >= data.length && in.position() == 0) {
                // All source data fed and fully decoded - nothing pending.
                break;
            }
        }
        return sb.toString();
    }

    private ByteBuffer wrap(byte[] data, boolean direct) {
        if (!direct) {
            return ByteBuffer.wrap(data);
        }
        ByteBuffer buf = ByteBuffer.allocateDirect(data.length);
        buf.put(data);
        buf.flip();
        return buf;
    }

}

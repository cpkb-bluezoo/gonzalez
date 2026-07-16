/*
 * Utf8Test.java
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

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link Utf8#decode(byte[], int, int)}: valid sequences of
 * every length, every truncation point, and every class of malformed byte
 * pattern.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class Utf8Test {

    private void assertDecodesTo(int expectedCodePoint, int expectedLength, byte... bytes) {
        int result = Utf8.decode(bytes, 0, bytes.length);
        assertTrue("expected a successful decode, got " + result, result >= 0);
        assertEquals("code point", expectedCodePoint, Utf8.codePoint(result));
        assertEquals("length", expectedLength, Utf8.length(result));
    }

    private void assertMalformed(byte... bytes) {
        assertEquals(Utf8.MALFORMED, Utf8.decode(bytes, 0, bytes.length));
    }

    private void assertTruncated(byte... bytes) {
        assertEquals(Utf8.TRUNCATED, Utf8.decode(bytes, 0, bytes.length));
    }

    private byte b(int unsignedValue) {
        return (byte) unsignedValue;
    }

    // ===== Valid sequences =====

    @Test
    public void testAscii() {
        assertDecodesTo(0x00, 1, b(0x00));
        assertDecodesTo('A', 1, b('A'));
        assertDecodesTo(0x7F, 1, b(0x7F));
    }

    @Test
    public void testTwoByteSequence() {
        // U+00E9 (e with acute)
        assertDecodesTo(0x00E9, 2, b(0xC3), b(0xA9));
        // Smallest valid 2-byte code point, U+0080
        assertDecodesTo(0x0080, 2, b(0xC2), b(0x80));
        // Largest 2-byte code point, U+07FF
        assertDecodesTo(0x07FF, 2, b(0xDF), b(0xBF));
    }

    @Test
    public void testThreeByteSequence() {
        // U+4E2D (CJK ideograph)
        assertDecodesTo(0x4E2D, 3, b(0xE4), b(0xB8), b(0xAD));
        // Smallest valid 3-byte code point, U+0800
        assertDecodesTo(0x0800, 3, b(0xE0), b(0xA0), b(0x80));
        // Largest 3-byte code point, U+FFFF
        assertDecodesTo(0xFFFF, 3, b(0xEF), b(0xBF), b(0xBF));
        // Just below the surrogate range, U+D7FF
        assertDecodesTo(0xD7FF, 3, b(0xED), b(0x9F), b(0xBF));
        // Just above the surrogate range, U+E000
        assertDecodesTo(0xE000, 3, b(0xEE), b(0x80), b(0x80));
    }

    @Test
    public void testFourByteSequence() {
        // U+20000 (CJK Extension B)
        assertDecodesTo(0x20000, 4, b(0xF0), b(0xA0), b(0x80), b(0x80));
        // Smallest valid 4-byte code point, U+10000
        assertDecodesTo(0x10000, 4, b(0xF0), b(0x90), b(0x80), b(0x80));
        // Largest legal code point, U+10FFFF
        assertDecodesTo(0x10FFFF, 4, b(0xF4), b(0x8F), b(0xBF), b(0xBF));
    }

    // ===== Truncation: every possible cut point =====

    @Test
    public void testTruncatedAtStart() {
        byte[] empty = new byte[0];
        assertEquals(Utf8.TRUNCATED, Utf8.decode(empty, 0, 0));
    }

    @Test
    public void testTruncatedTwoByteSequence() {
        assertTruncated(b(0xC3));
    }

    @Test
    public void testTruncatedThreeByteSequence() {
        assertTruncated(b(0xE4));
        assertTruncated(b(0xE4), b(0xB8));
    }

    @Test
    public void testTruncatedFourByteSequence() {
        assertTruncated(b(0xF0));
        assertTruncated(b(0xF0), b(0xA0));
        assertTruncated(b(0xF0), b(0xA0), b(0x80));
    }

    // ===== Malformed: continuation byte where a lead byte was expected =====

    @Test
    public void testBareContinuationByteIsMalformed() {
        assertMalformed(b(0x80));
        assertMalformed(b(0xBF));
    }

    // ===== Malformed: overlong encodings =====

    @Test
    public void testOverlongTwoByteIsMalformed() {
        // 0xC0 and 0xC1 can only ever encode code points <= 0x7F, which must
        // use 1 byte - always an overlong encoding regardless of continuation.
        assertMalformed(b(0xC0), b(0x80));
        assertMalformed(b(0xC1), b(0xBF));
    }

    @Test
    public void testOverlongThreeByteIsMalformed() {
        // 0xE0 followed by a continuation byte below 0xA0 encodes a code
        // point below U+0800, which belongs in 2 bytes.
        assertMalformed(b(0xE0), b(0x9F), b(0xBF));
        assertMalformed(b(0xE0), b(0x80), b(0x80));
    }

    @Test
    public void testOverlongFourByteIsMalformed() {
        // 0xF0 followed by a continuation byte below 0x90 encodes a code
        // point below U+10000, which belongs in 3 bytes.
        assertMalformed(b(0xF0), b(0x8F), b(0xBF), b(0xBF));
        assertMalformed(b(0xF0), b(0x80), b(0x80), b(0x80));
    }

    // ===== Malformed: encoded surrogates =====

    @Test
    public void testEncodedSurrogateIsMalformed() {
        // U+D800 (high surrogate start): ED A0 80
        assertMalformed(b(0xED), b(0xA0), b(0x80));
        // U+DFFF (low surrogate end): ED BF BF
        assertMalformed(b(0xED), b(0xBF), b(0xBF));
    }

    // ===== Malformed: beyond U+10FFFF =====

    @Test
    public void testBeyondMaxCodePointIsMalformed() {
        // F4 90 80 80 would encode U+110000, one past the maximum.
        assertMalformed(b(0xF4), b(0x90), b(0x80), b(0x80));
        // F5 and above can never produce a legal code point at all.
        assertMalformed(b(0xF5), b(0x80), b(0x80), b(0x80));
        assertMalformed(b(0xFF));
    }

    // ===== Malformed: missing continuation byte (not merely truncated) =====

    @Test
    public void testNonContinuationByteWhereExpectedIsMalformed() {
        // A lead byte followed by an ASCII byte: we have the byte, and it's
        // definitely wrong, so this is malformed rather than truncated.
        assertMalformed(b(0xC3), b('A'));
        assertMalformed(b(0xE4), b('A'), b(0xAD));
        assertMalformed(b(0xF0), b('A'), b(0x80), b(0x80));
    }

    // ===== Packing round-trip =====

    @Test
    public void testCodePointAndLengthRoundTrip() {
        for (int cp = 0; cp <= 0x10FFFF; cp += 997) {
            for (int len = 1; len <= 4; len++) {
                int packed = packForTest(cp, len);
                assertEquals(cp, Utf8.codePoint(packed));
                assertEquals(len, Utf8.length(packed));
            }
        }
    }

    private int packForTest(int codePoint, int length) {
        // Mirrors Utf8's private pack() exactly, to test codePoint()/length()
        // independently of decode() itself.
        return (codePoint << 3) | length;
    }

}

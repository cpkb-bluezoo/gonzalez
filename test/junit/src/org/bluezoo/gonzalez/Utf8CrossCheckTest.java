/*
 * Utf8CrossCheckTest.java
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

import java.nio.charset.StandardCharsets;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Exhaustively checks, for every Unicode code point U+0001-U+10FFFF, that:
 * <ul>
 * <li>{@link Utf8#decode(byte[], int, int)} round-trips the code point
 *     JDK's own UTF-8 encoder produces for it, consuming exactly as many
 *     bytes as that encoder emitted; and</li>
 * <li>{@link CharClass#classifyCodePoint(int, MiniState, boolean, boolean)}
 *     agrees with the existing char-based {@link CharClass#classify} below
 *     U+10000, where there is no surrogate-half ambiguity, and matches the
 *     documented NameStartChar range exactly at or above U+10000, where the
 *     byte path is more precise than the char path's surrogate-half
 *     approximation (see {@code classifyUnicodeCodePoint}'s Javadoc).</li>
 * </ul>
 * This is the baseline the byte-native tokenizer's classification must never
 * silently drift from.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class Utf8CrossCheckTest {

    @Test
    public void testEveryCodePointRoundTripsAndClassifiesConsistently() {
        for (int cp = 1; cp <= 0x10FFFF; cp++) {
            if (cp >= 0xD800 && cp <= 0xDFFF) {
                continue; // surrogates are not standalone code points
            }
            checkRoundTrip(cp);
            checkClassification(cp);
        }
    }

    private void checkRoundTrip(int cp) {
        char[] chars = Character.toChars(cp);
        String s = new String(chars);
        byte[] expectedBytes = s.getBytes(StandardCharsets.UTF_8);

        int result = Utf8.decode(expectedBytes, 0, expectedBytes.length);
        assertTrue("U+" + Integer.toHexString(cp) + " should decode successfully, got " + result,
                result != Utf8.MALFORMED && result != Utf8.TRUNCATED);
        assertEquals("U+" + Integer.toHexString(cp) + " code point mismatch",
                cp, Utf8.codePoint(result));
        assertEquals("U+" + Integer.toHexString(cp) + " length mismatch",
                expectedBytes.length, Utf8.length(result));
    }

    private void checkClassification(int cp) {
        CharClass actual = CharClass.classifyCodePoint(cp, MiniState.READY, false, false);
        if (cp < 0x10000) {
            CharClass expected = CharClass.classify((char) cp, TokenizerState.CONTENT, MiniState.READY, false, false);
            assertEquals("U+" + Integer.toHexString(cp) + " classification mismatch",
                    expected, actual);
        } else {
            CharClass expected = (cp <= 0xEFFFF) ? CharClass.NAME_START_CHAR : CharClass.CHAR_DATA;
            assertEquals("U+" + Integer.toHexString(cp) + " classification mismatch",
                    expected, actual);
        }
    }

    /**
     * Confirms the known, documented divergence directly. The char-based
     * path's high-surrogate check is already narrowed to 0xD800-0xDB7F
     * (exactly the high surrogates for U+10000-U+EFFFF), so it correctly
     * rejects the high surrogate of a Supplementary Private Use Area code
     * point like U+F0000. Its low-surrogate check has no such discriminator
     * though - a low surrogate alone cannot reveal which high surrogate it
     * was paired with - so it unconditionally accepts every low surrogate as
     * NAME_START_CHAR, including this one, even though U+F0000 as a whole
     * code point is not a NameStartChar. The byte path, seeing the whole
     * code point at once, has no such gap.
     */
    @Test
    public void testBytePathIsMorePreciseThanSurrogateHalfApproximationInPrivateUseSupplementaryRange() {
        int puaCodePoint = 0xF0000;
        char[] chars = Character.toChars(puaCodePoint);

        CharClass highSurrogateClass =
                CharClass.classify(chars[0], TokenizerState.CONTENT, MiniState.READY, false, false);
        CharClass lowSurrogateClass =
                CharClass.classify(chars[1], TokenizerState.CONTENT, MiniState.READY, false, false);
        assertEquals(CharClass.CHAR_DATA, highSurrogateClass);
        assertEquals(CharClass.NAME_START_CHAR, lowSurrogateClass);

        CharClass byteClass = CharClass.classifyCodePoint(puaCodePoint, MiniState.READY, false, false);
        assertEquals(CharClass.CHAR_DATA, byteClass);
    }

}

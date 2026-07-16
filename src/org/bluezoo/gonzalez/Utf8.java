/*
 * Utf8.java
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

/**
 * Decodes single UTF-8 byte sequences without allocation, distinguishing a
 * sequence that is merely incomplete (more bytes may still arrive) from one
 * that is conclusively invalid (no amount of additional input can fix it).
 * <p>
 * This is the byte-native tokenizer's replacement for {@code CharsetDecoder}:
 * where the legacy tokenizer receives already-decoded {@code char}s, the
 * byte-native tokenizer decodes UTF-8 sequences itself as part of scanning
 * for structural bytes (see BYTE-TOKENIZER.md, Decision 4).
 * <p>
 * {@link #decode(byte[], int, int)} returns a single {@code int} packing both
 * the decoded code point and the number of bytes consumed, to avoid
 * allocating a result object on every call; {@link #MALFORMED} and
 * {@link #TRUNCATED} are negative sentinels that can never collide with a
 * valid packed result, since a decoded code point is always non-negative.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class Utf8 {

    /**
     * The byte at the given position cannot start, or does not continue, a
     * valid UTF-8 sequence: an unexpected continuation byte, an overlong
     * encoding, a UTF-8-encoded surrogate, or a code point beyond U+10FFFF.
     * This is a fatal, well-formedness error - no amount of additional input
     * changes the outcome.
     */
    static final int MALFORMED = -1;

    /**
     * Not enough bytes remain between {@code pos} and {@code limit} to
     * determine the full sequence yet; the caller should retry once more
     * input has arrived.
     */
    static final int TRUNCATED = -2;

    private Utf8() {
    }

    /**
     * Decodes the single UTF-8 code point starting at {@code buf[pos]}.
     *
     * @param buf the byte array to read from
     * @param pos the position of the sequence's first byte
     * @param limit the exclusive upper bound of valid data in {@code buf}
     * @return a value packing the decoded code point and its length in
     *         bytes (see {@link #codePoint(int)} and {@link #length(int)}),
     *         or {@link #MALFORMED} or {@link #TRUNCATED}
     */
    static int decode(byte[] buf, int pos, int limit) {
        if (pos >= limit) {
            return TRUNCATED;
        }
        int b0 = buf[pos] & 0xFF;

        if (b0 < 0x80) {
            return pack(b0, 1);
        }
        if (b0 < 0xC2) {
            // 0x80-0xBF: a continuation byte where a lead byte was expected.
            // 0xC0-0xC1: can only ever produce an overlong 2-byte encoding
            // of a code point that belongs in 1 byte. Both always malformed.
            return MALFORMED;
        }
        if (b0 < 0xE0) {
            return decodeTwoByte(buf, pos, limit, b0);
        }
        if (b0 < 0xF0) {
            return decodeThreeByte(buf, pos, limit, b0);
        }
        if (b0 < 0xF5) {
            return decodeFourByte(buf, pos, limit, b0);
        }
        // 0xF5-0xFF always encode a code point beyond U+10FFFF, regardless
        // of what follows - no need to wait for more bytes to know that.
        return MALFORMED;
    }

    private static int decodeTwoByte(byte[] buf, int pos, int limit, int b0) {
        if (pos + 1 >= limit) {
            return TRUNCATED;
        }
        int b1 = buf[pos + 1] & 0xFF;
        if (!isContinuation(b1)) {
            return MALFORMED;
        }
        int codePoint = ((b0 & 0x1F) << 6) | (b1 & 0x3F);
        return pack(codePoint, 2);
    }

    private static int decodeThreeByte(byte[] buf, int pos, int limit, int b0) {
        if (pos + 1 >= limit) {
            return TRUNCATED;
        }
        int b1 = buf[pos + 1] & 0xFF;
        if (!isContinuation(b1)) {
            return MALFORMED;
        }
        if (b0 == 0xE0 && b1 < 0xA0) {
            // Overlong: this range of code points fits in 2 bytes.
            return MALFORMED;
        }
        if (b0 == 0xED && b1 >= 0xA0) {
            // U+D800-U+DFFF: surrogates are never legal in UTF-8.
            return MALFORMED;
        }
        if (pos + 2 >= limit) {
            return TRUNCATED;
        }
        int b2 = buf[pos + 2] & 0xFF;
        if (!isContinuation(b2)) {
            return MALFORMED;
        }
        int codePoint = ((b0 & 0x0F) << 12) | ((b1 & 0x3F) << 6) | (b2 & 0x3F);
        return pack(codePoint, 3);
    }

    private static int decodeFourByte(byte[] buf, int pos, int limit, int b0) {
        if (pos + 1 >= limit) {
            return TRUNCATED;
        }
        int b1 = buf[pos + 1] & 0xFF;
        if (!isContinuation(b1)) {
            return MALFORMED;
        }
        if (b0 == 0xF0 && b1 < 0x90) {
            // Overlong: this range of code points fits in 3 bytes.
            return MALFORMED;
        }
        if (b0 == 0xF4 && b1 >= 0x90) {
            // Would encode a code point beyond U+10FFFF.
            return MALFORMED;
        }
        if (pos + 2 >= limit) {
            return TRUNCATED;
        }
        int b2 = buf[pos + 2] & 0xFF;
        if (!isContinuation(b2)) {
            return MALFORMED;
        }
        if (pos + 3 >= limit) {
            return TRUNCATED;
        }
        int b3 = buf[pos + 3] & 0xFF;
        if (!isContinuation(b3)) {
            return MALFORMED;
        }
        int codePoint = ((b0 & 0x07) << 18) | ((b1 & 0x3F) << 12) | ((b2 & 0x3F) << 6) | (b3 & 0x3F);
        return pack(codePoint, 4);
    }

    private static boolean isContinuation(int b) {
        return (b & 0xC0) == 0x80;
    }

    private static int pack(int codePoint, int length) {
        return (codePoint << 3) | length;
    }

    /**
     * Extracts the decoded code point from a value returned by
     * {@link #decode(byte[], int, int)}. Only valid when that value was not
     * {@link #MALFORMED} or {@link #TRUNCATED}.
     *
     * @param packedResult the value returned by {@link #decode}
     * @return the decoded code point
     */
    static int codePoint(int packedResult) {
        return packedResult >>> 3;
    }

    /**
     * Extracts the sequence length in bytes (1-4) from a value returned by
     * {@link #decode(byte[], int, int)}. Only valid when that value was not
     * {@link #MALFORMED} or {@link #TRUNCATED}.
     *
     * @param packedResult the value returned by {@link #decode}
     * @return the number of bytes the sequence occupied
     */
    static int length(int packedResult) {
        return packedResult & 0x7;
    }

}

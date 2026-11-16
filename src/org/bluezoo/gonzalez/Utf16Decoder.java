/*
 * Utf16Decoder.java
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
import java.nio.ByteOrder;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;

/**
 * Decodes UTF-16 of a known byte order, in bulk.
 * <p>
 * UTF-16 is the same as Java's own {@code char}s apart from the order of the
 * two bytes, so the characters of a stretch of input can be copied out of it
 * with one call that swaps the bytes in place ({@link
 * java.nio.CharBuffer#get(char[], int, int)} on a view of the input), and
 * what is left to do is to check that every surrogate has its partner. The
 * JDK's decoder instead takes two bytes at a time through the buffers'
 * accessors, which on a document of Japanese text took about half the time
 * the whole parse took.
 * <p>
 * It reports what the JDK's decoder reports, and for the same input: an
 * unpaired surrogate is malformed, a high surrogate that ends the input
 * waits for the rest of its pair, and a byte left over at the end of the
 * input is not consumed. It does not look for a byte order mark: the caller
 * has already read one, and chosen the byte order by it.
 */
final class Utf16Decoder extends CharsetDecoder {

    private final boolean bigEndian;

    /** The JDK's decoder, for the output buffer that has no array. */
    private CharsetDecoder fallback;

    /**
     * @param charset {@link java.nio.charset.StandardCharsets#UTF_16BE} or
     *        {@link java.nio.charset.StandardCharsets#UTF_16LE}
     */
    Utf16Decoder(Charset charset, boolean bigEndian) {
        super(charset, 0.5f, 1.0f);
        this.bigEndian = bigEndian;
    }

    @Override
    protected CoderResult decodeLoop(ByteBuffer in, CharBuffer out) {
        if (!out.hasArray() || out.isReadOnly()) {
            return fallbackDecode(in, out);
        }
        char[] dst = out.array();
        ByteOrder order = bigEndian ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN;
        while (true) {
            int pairs = in.remaining() >> 1;
            int room = out.remaining();
            int n = Math.min(pairs, room);
            if (n == 0) {
                return pairs == 0 ? CoderResult.UNDERFLOW : CoderResult.OVERFLOW;
            }
            int start = out.arrayOffset() + out.position();
            int end = start + n;

            // Copy all n characters, whatever they are; what is accepted of
            // them is decided below, and the input and output positions are
            // not moved until it is.
            ByteBuffer view = in.duplicate();
            view.limit(in.position() + 2 * n);
            view.order(order);
            view.asCharBuffer().get(dst, start, n);

            int i = start;
            while (true) {
                int j = i;
                while (j < end && (dst[j] & 0xF800) != 0xD800) {
                    j++;
                }
                if (j == end) {
                    break;
                }
                char c = dst[j];
                if (c >= 0xDC00) {
                    // A low surrogate with no high one before it.
                    return accept(in, out, j - start, CoderResult.malformedForLength(2));
                }
                if (j + 1 == end) {
                    // A high surrogate whose partner is not here yet: wait
                    // for it, or for room to put it.
                    return accept(in, out, j - start,
                            pairs > n ? CoderResult.OVERFLOW : CoderResult.UNDERFLOW);
                }
                char next = dst[j + 1];
                if (next < 0xDC00 || next > 0xDFFF) {
                    // The JDK counts both characters as the malformed input.
                    return accept(in, out, j - start, CoderResult.malformedForLength(4));
                }
                i = j + 2;
            }
            in.position(in.position() + 2 * n);
            out.position(out.position() + n);
        }
    }

    /** Takes the first {@code chars} characters decoded, and returns {@code result}. */
    private static CoderResult accept(ByteBuffer in, CharBuffer out, int chars, CoderResult result) {
        in.position(in.position() + 2 * chars);
        out.position(out.position() + chars);
        return result;
    }

    private CoderResult fallbackDecode(ByteBuffer in, CharBuffer out) {
        if (fallback == null) {
            fallback = charset().newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
        }
        return fallback.decode(in, out, false);
    }

    @Override
    protected void implReset() {
        if (fallback != null) {
            fallback.reset();
        }
    }
}

/*
 * Utf8LongRuns.java
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
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * Decodes UTF-8 that has few characters in it that are not ASCII: the runs of
 * ASCII between them it widens as described here, and what is not in a run it
 * leaves to {@link Utf8Decoder}.
 * <p>
 * {@link Utf8Decoder} widens a run of ASCII with a loop, a byte to a
 * character, which is not slow but is not as fast as the JDK's ISO-8859-1
 * decoder, which widens bytes without looking at what they are with a
 * routine of the JVM's that no application code can call. Calling that
 * decoder costs as much as copying thirty bytes or so, so it is for runs
 * that are longer, and a run has to be measured first, a word at a time, so
 * that it is known to be ASCII. Where non-ASCII characters are few, which
 * is judged from a sample of the input, the runs between them are long, and
 * this is two to three times as fast as the loop. Where they are not, the
 * loop is faster and this does nothing.
 * <p>
 * This is a decoder of its own, and not a branch of {@link Utf8Decoder}'s
 * loop, for the sake of the loop that decoder has for what is not sparse: a
 * method as big as that is compiled differently for what else it holds,
 * and the speed of that loop on French or German text fell by a sixth when
 * it held this. The caller chooses between them, for each piece of input,
 * with {@link #isSparse}.
 * <p>
 * It decodes what is well-formed and not near the end of the input or the
 * output, and gives the rest to {@link Utf8Decoder}, which reports what is
 * not well-formed.
 */
final class Utf8LongRuns extends CharsetDecoder {

    /** The high bit of every byte of a word: set in a byte that is not ASCII. */
    private static final long HIGH_BITS = 0x8080808080808080L;

    /**
     * The length of a run of ASCII that is copied a byte at a time before it
     * is taken to be a long one, and the length below which a run that is
     * long enough to be measured is copied that way after all.
     */
    private static final int SHORT_RUN = 16;

    /** How many bytes of input are sampled to judge whether its runs of ASCII are long. */
    private static final int SAMPLE = 256;

    /**
     * How many bytes of a sample may be other than ASCII for the runs of ASCII
     * to be taken as long: seven in 256 bytes is about three characters of two
     * bytes, one in eighty or so, from where widening a run is faster than a
     * loop.
     */
    private static final int SPARSE = 6;

    /**
     * How far short runs count against going on: three for each, and one in
     * favour of each that is long, until this many. The sample may not show
     * what the text is like further on.
     */
    private static final int GIVE_UP = 12;

    /** The JDK's ISO-8859-1 decoder, which widens a run of ASCII. */
    private final CharsetDecoder widener = StandardCharsets.ISO_8859_1.newDecoder();

    /** Decodes what is well-formed in the start of the input, and is not near its end. */
    private final Utf8Decoder rest = new Utf8Decoder();

    Utf8LongRuns() {
        super(StandardCharsets.UTF_8, 1.0f, 3.0f);
        rest.onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
    }

    @Override
    protected CoderResult decodeLoop(ByteBuffer in, CharBuffer out) {
        if (in.hasArray() && out.hasArray() && !out.isReadOnly()) {
            widenRuns(in, out);
        }
        return rest.decode(in, out, false);
    }

    @Override
    protected void implReset() {
        rest.reset();
    }

    /**
     * Decodes what it can of the start of {@code in} into {@code out}, moving
     * the position of each past what it has done.
     */
    private void widenRuns(ByteBuffer in, CharBuffer out) {
        int base = in.arrayOffset();
        byte[] src = in.array();
        int sp = base + in.position();
        int sl = base + in.limit();
        char[] dst = out.array();
        int dbase = out.arrayOffset();
        int dp = dbase + out.position();
        int dl = dbase + out.limit();

        // Reads up to four bytes and writes up to two characters without
        // asking whether there is room, as the fast loop of Utf8Decoder
        // does, which is why it stops short of the ends.
        int safeSl = sl - 3;
        int safeDl = dl - 1;
        int shortRuns = 0;
        while (sp < safeSl && dp < safeDl && shortRuns < GIVE_UP) {
            int b1 = src[sp];
            if (b1 >= 0) {
                int room = Math.min(safeSl, sp + (safeDl - dp)) - sp;
                int n = copyAscii(src, sp, dst, dp, Math.min(room, SHORT_RUN));
                sp += n;
                dp += n;
                if (n < SHORT_RUN) {
                    shortRuns += 3;
                } else if (room > SHORT_RUN) {
                    n = runLength(in, src, sp, sp - base, room - SHORT_RUN);
                    if (n >= SHORT_RUN) {
                        int limit = in.limit();
                        in.limit(sp - base + n).position(sp - base);
                        out.position(dp - dbase);
                        widener.decode(in, out, false);
                        in.limit(limit);
                    } else {
                        copyAscii(src, sp, dst, dp, n);
                    }
                    sp += n;
                    dp += n;
                    if (shortRuns > 0) {
                        shortRuns--;
                    }
                }
            } else if (b1 < -32) {
                int b2 = src[sp + 1];
                if (b1 < -62 || !isContinuation(b2)) {
                    break;
                }
                dst[dp++] = (char) (((b1 & 0x1F) << 6) | (b2 & 0x3F));
                sp += 2;
            } else if (b1 < -16) {
                int b2 = src[sp + 1];
                int b3 = src[sp + 2];
                if (!isContinuation(b2) || !isContinuation(b3)) {
                    break;
                }
                int c = ((b1 & 0x0F) << 12) | ((b2 & 0x3F) << 6) | (b3 & 0x3F);
                if (c < 0x800 || (c & 0xF800) == 0xD800) {
                    break;
                }
                dst[dp++] = (char) c;
                sp += 3;
            } else {
                if (b1 > -12 || dp + 2 > dl) {
                    break;
                }
                int b2 = src[sp + 1];
                int b3 = src[sp + 2];
                int b4 = src[sp + 3];
                if (!isContinuation(b2) || !isContinuation(b3) || !isContinuation(b4)) {
                    break;
                }
                int cp = ((b1 & 0x07) << 18) | ((b2 & 0x3F) << 12) | ((b3 & 0x3F) << 6) | (b4 & 0x3F);
                if (cp < 0x10000 || cp > 0x10FFFF) {
                    break;
                }
                dst[dp++] = (char) ((cp >>> 10) + 0xD7C0);
                dst[dp++] = (char) ((cp & 0x3FF) + 0xDC00);
                sp += 4;
            }
        }
        in.position(sp - base);
        out.position(dp - dbase);
    }

    /**
     * Whether the input in {@code in} has few bytes that are not ASCII, as
     * judged from the {@link #SAMPLE} bytes of it at its position, so that
     * this is the decoder for it. Words are read through the buffer, which
     * does not disturb its position, whatever its byte order: only how many
     * of the bytes have their high bit set is asked.
     */
    static boolean isSparse(ByteBuffer in) {
        if (!in.hasArray() || in.remaining() < SAMPLE) {
            return false;
        }
        int count = 0;
        for (int i = in.position(), end = i + SAMPLE; i < end; i += 8) {
            count += Long.bitCount(in.getLong(i) & HIGH_BITS);
        }
        return count <= SPARSE;
    }

    /**
     * The length of the run of ASCII at {@code sp}, which is {@code max}
     * bytes or fewer, found a word at a time; {@code offset} is where that
     * is in the buffer.
     */
    private static int runLength(ByteBuffer in, byte[] src, int sp, int offset, int max) {
        int n = 0;
        while (max - n >= 64) {
            long words = in.getLong(offset + n) | in.getLong(offset + n + 8)
                    | in.getLong(offset + n + 16) | in.getLong(offset + n + 24)
                    | in.getLong(offset + n + 32) | in.getLong(offset + n + 40)
                    | in.getLong(offset + n + 48) | in.getLong(offset + n + 56);
            if ((words & HIGH_BITS) != 0) {
                break;
            }
            n += 64;
        }
        while (max - n >= 8 && (in.getLong(offset + n) & HIGH_BITS) == 0) {
            n += 8;
        }
        while (n < max && src[sp + n] >= 0) {
            n++;
        }
        return n;
    }

    /**
     * Copies the bytes at {@code sp} to characters at {@code dp} up to the first
     * that is not ASCII, or {@code max} of them, and returns how many it did.
     */
    private static int copyAscii(byte[] src, int sp, char[] dst, int dp, int max) {
        int n = 0;
        while (n < max) {
            int b = src[sp + n];
            if (b < 0) {
                break;
            }
            dst[dp + n] = (char) b;
            n++;
        }
        return n;
    }

    private static boolean isContinuation(int b) {
        return (b & 0xC0) == 0x80;
    }
}

/*
 * Utf8Decoder.java
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
 * Decodes UTF-8 that has non-ASCII characters in it.
 * <p>
 * The JDK's decoder is fastest on ASCII, which it widens with a routine of
 * the JVM's own that no application code can call, and which it calls once
 * per call to {@code decode}: from the first byte that is not ASCII it
 * decodes the rest of its input one character at a time, ASCII included,
 * and so a document that is nearly all ASCII with a few accented characters
 * is decoded at a fraction of the speed of one that has none. This decoder
 * does the rest at a speed that does not depend on how the characters are
 * mixed, and is for input that the caller knows has a byte at or above 0x80.
 * Input that is all ASCII is better left to the JDK's decoder, which is four
 * times as fast on it.
 * <p>
 * It decodes according to Table 3-7 of the Unicode Standard (overlong forms,
 * surrogates and code points beyond U+10FFFF are malformed), and reports what
 * the JDK's decoder reports for the same input: the same characters, the same
 * length of malformed input, and the same waiting for the rest of a
 * sequence that the input ends in the middle of.
 */
final class Utf8Decoder extends CharsetDecoder {

    /** The JDK's decoder, for buffers that have no array. */
    private CharsetDecoder fallback;

    Utf8Decoder() {
        super(StandardCharsets.UTF_8, 1.0f, 3.0f);
    }

    private static boolean isContinuation(int b) {
        return (b & 0xC0) == 0x80;
    }

    /** True if {@code b2} cannot follow the lead byte {@code b1} of a three-byte sequence. */
    private static boolean isBadSecondOfThree(int b1, int b2) {
        return (b1 == (byte) 0xE0 && (b2 & 0xE0) == 0x80) || !isContinuation(b2);
    }

    /** True if {@code b2} cannot follow the lead byte {@code b1} of a four-byte sequence. */
    private static boolean isBadSecondOfFour(int b1, int b2) {
        int second = b2 & 0xFF;
        return (b1 == (byte) 0xF0 && (second < 0x90 || second > 0xBF))
                || (b1 == (byte) 0xF4 && (second & 0xF0) != 0x80)
                || !isContinuation(b2);
    }

    @Override
    protected CoderResult decodeLoop(ByteBuffer in, CharBuffer out) {
        if (!in.hasArray() || !out.hasArray() || out.isReadOnly()) {
            return fallbackDecode(in, out);
        }
        byte[] src = in.array();
        int base = in.arrayOffset();
        int sp = base + in.position();
        int sl = base + in.limit();
        char[] dst = out.array();
        int dbase = out.arrayOffset();
        int dp = dbase + out.position();
        int dl = dbase + out.limit();

        // The fast loop. It reads up to four bytes and writes up to two
        // characters without asking whether there is room for them, which is
        // why it stops short of the ends, and it stops at anything that is
        // not a well-formed sequence, for the careful loop below to deal with.
        int safeSl = sl - 3;
        int safeDl = dl - 1;
        fast:
        while (sp < safeSl && dp < safeDl) {
            int b1 = src[sp];
            if (b1 >= 0) {
                int end = Math.min(safeSl, sp + (safeDl - dp));
                while (sp < end) {
                    int b = src[sp];
                    if (b < 0) {
                        break;
                    }
                    dst[dp++] = (char) b;
                    sp++;
                }
            } else if (b1 < -32) {
                int b2 = src[sp + 1];
                if (b1 < -62 || !isContinuation(b2)) {
                    break fast;
                }
                dst[dp++] = (char) (((b1 & 0x1F) << 6) | (b2 & 0x3F));
                sp += 2;
            } else if (b1 < -16) {
                int b2 = src[sp + 1];
                int b3 = src[sp + 2];
                if (!isContinuation(b2) || !isContinuation(b3)) {
                    break fast;
                }
                int c = ((b1 & 0x0F) << 12) | ((b2 & 0x3F) << 6) | (b3 & 0x3F);
                if (c < 0x800 || (c & 0xF800) == 0xD800) {
                    break fast;
                }
                dst[dp++] = (char) c;
                sp += 3;
            } else {
                if (b1 > -12 || dp + 2 > dl) {
                    break fast;
                }
                int b2 = src[sp + 1];
                int b3 = src[sp + 2];
                int b4 = src[sp + 3];
                if (!isContinuation(b2) || !isContinuation(b3) || !isContinuation(b4)) {
                    break fast;
                }
                int cp = ((b1 & 0x07) << 18) | ((b2 & 0x3F) << 12) | ((b3 & 0x3F) << 6) | (b4 & 0x3F);
                if (cp < 0x10000 || cp > 0x10FFFF) {
                    break fast;
                }
                dst[dp++] = (char) ((cp >>> 10) + 0xD7C0);
                dst[dp++] = (char) ((cp & 0x3FF) + 0xDC00);
                sp += 4;
            }
        }

        // The careful loop: what is near the end of the input or the output,
        // and whatever the fast loop stopped at.
        while (sp < sl) {
            int b1 = src[sp];
            if (b1 >= 0) {
                if (dp >= dl) {
                    return finish(in, out, base, dbase, sp, dp, CoderResult.OVERFLOW);
                }
                dst[dp++] = (char) b1;
                sp++;
            } else if (b1 >= -62 && b1 < -32) {
                // C2..DF, then 80..BF
                if (sl - sp < 2) {
                    return finish(in, out, base, dbase, sp, dp, CoderResult.UNDERFLOW);
                }
                if (dp >= dl) {
                    return finish(in, out, base, dbase, sp, dp, CoderResult.OVERFLOW);
                }
                int b2 = src[sp + 1];
                if (!isContinuation(b2)) {
                    return finish(in, out, base, dbase, sp, dp, CoderResult.malformedForLength(1));
                }
                dst[dp++] = (char) (((b1 & 0x1F) << 6) | (b2 & 0x3F));
                sp += 2;
            } else if (b1 >= -32 && b1 < -16) {
                // E0..EF, then two of 80..BF (A0..BF after E0, 80..9F after ED)
                if (sl - sp < 3 || dp >= dl) {
                    // Short of input or of room; but what is there may already
                    // be wrong, and is then reported now and not later.
                    if (sl - sp > 1 && isBadSecondOfThree(b1, src[sp + 1])) {
                        return finish(in, out, base, dbase, sp, dp, CoderResult.malformedForLength(1));
                    }
                    return finish(in, out, base, dbase, sp, dp,
                            sl - sp < 3 ? CoderResult.UNDERFLOW : CoderResult.OVERFLOW);
                }
                int b2 = src[sp + 1];
                int b3 = src[sp + 2];
                boolean overlong = b1 == (byte) 0xE0 && (b2 & 0xE0) == 0x80;
                if (overlong || !isContinuation(b2) || !isContinuation(b3)) {
                    int length = (overlong || !isContinuation(b2)) ? 1 : 2;
                    return finish(in, out, base, dbase, sp, dp, CoderResult.malformedForLength(length));
                }
                int c = ((b1 & 0x0F) << 12) | ((b2 & 0x3F) << 6) | (b3 & 0x3F);
                if ((c & 0xF800) == 0xD800) {
                    return finish(in, out, base, dbase, sp, dp, CoderResult.malformedForLength(3));
                }
                dst[dp++] = (char) c;
                sp += 3;
            } else if (b1 >= -16 && b1 < -8) {
                // F0..F7, then three of 80..BF; the result must be U+10000..U+10FFFF
                if (sl - sp < 4 || dl - dp < 2) {
                    if (b1 > (byte) 0xF4 || (sl - sp > 1 && isBadSecondOfFour(b1, src[sp + 1]))) {
                        return finish(in, out, base, dbase, sp, dp, CoderResult.malformedForLength(1));
                    }
                    if (sl - sp > 2 && !isContinuation(src[sp + 2])) {
                        return finish(in, out, base, dbase, sp, dp, CoderResult.malformedForLength(2));
                    }
                    return finish(in, out, base, dbase, sp, dp,
                            sl - sp < 4 ? CoderResult.UNDERFLOW : CoderResult.OVERFLOW);
                }
                int b2 = src[sp + 1];
                int b3 = src[sp + 2];
                int b4 = src[sp + 3];
                int cp = ((b1 & 0x07) << 18) | ((b2 & 0x3F) << 12) | ((b3 & 0x3F) << 6) | (b4 & 0x3F);
                if (!isContinuation(b2) || !isContinuation(b3) || !isContinuation(b4)
                        || cp < 0x10000 || cp > 0x10FFFF) {
                    int length;
                    if (b1 > (byte) 0xF4 || !isContinuation(b2)
                            || (b1 == (byte) 0xF0 && ((b2 & 0xFF) < 0x90 || (b2 & 0xFF) > 0xBF))
                            || (b1 == (byte) 0xF4 && (b2 & 0xF0) != 0x80)) {
                        length = 1;
                    } else if (!isContinuation(b3)) {
                        length = 2;
                    } else {
                        length = 3;
                    }
                    return finish(in, out, base, dbase, sp, dp, CoderResult.malformedForLength(length));
                }
                dst[dp++] = (char) ((cp >>> 10) + 0xD7C0);
                dst[dp++] = (char) ((cp & 0x3FF) + 0xDC00);
                sp += 4;
            } else {
                // 80..C1 and F8..FF are not the start of anything
                return finish(in, out, base, dbase, sp, dp, CoderResult.malformedForLength(1));
            }
        }
        return finish(in, out, base, dbase, sp, dp, CoderResult.UNDERFLOW);
    }

    private static CoderResult finish(ByteBuffer in, CharBuffer out, int base, int dbase,
            int sp, int dp, CoderResult result) {
        in.position(sp - base);
        out.position(dp - dbase);
        return result;
    }

    private CoderResult fallbackDecode(ByteBuffer in, CharBuffer out) {
        if (fallback == null) {
            fallback = StandardCharsets.UTF_8.newDecoder()
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

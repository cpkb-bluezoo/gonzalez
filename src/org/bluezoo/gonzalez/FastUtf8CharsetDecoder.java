/*
 * FastUtf8CharsetDecoder.java
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
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.StandardCharsets;

/**
 * A hand-written, allocation-free replacement for the JDK's generic UTF-8
 * {@code CharsetDecoder}, used by {@code ExternalEntityDecoder} for the
 * UTF-8/US-ASCII case (see FAST-UTF8-DECODER.md).
 * <p>
 * A genuine drop-in replacement, not a parallel API: this is a real {@code
 * CharsetDecoder} subclass, constructed in place of {@code
 * charset.newDecoder()} and otherwise used identically - {@code
 * ExternalEntityDecoder}'s decode loop, error handling, and buffer
 * compaction are completely unchanged. The only method a subclass must
 * implement is {@link #decodeLoop(ByteBuffer, CharBuffer)}; the inherited
 * {@code decode(ByteBuffer, CharBuffer, boolean)} wrapper calls it once per
 * invocation and returns immediately given {@code CodingErrorAction.REPORT}
 * (what {@code ExternalEntityDecoder} always configures), so none of the
 * wrapper's bookkeeping scales with the amount of data decoded - all of
 * that happens here, in {@code decodeLoop}, once per chunk rather than
 * once per byte.
 * <p>
 * The decoder is stateless across calls: an incomplete trailing multi-byte
 * sequence is simply left unconsumed (the input buffer's position is not
 * advanced past it) and re-presented whole on the next {@code decodeLoop}
 * call, exactly like the JDK's own UTF-8 decoder and exactly matching
 * {@code ExternalEntityDecoder}'s existing {@code compact()}-and-retry
 * flow. This means the inherited {@code implFlush}/{@code implReset}
 * no-op defaults are already correct and do not need overriding.
 * <p>
 * Unlike the JDK's generic decoder - and unlike this codebase's own
 * previous {@code widenAsciiRun} ASCII-prefix trick - the fast path here
 * keeps running after a multi-byte sequence instead of falling back
 * permanently to a slow path for the remainder of the buffer: widen a run
 * of ASCII bytes directly, decode exactly one multi-byte sequence inline
 * via {@link Utf8#decode} when a lead byte is seen, then go straight back
 * to widening. Content that interleaves ASCII and multi-byte text
 * throughout - most real-world non-English documents - only pays decode
 * cost for the actual multi-byte bytes, not for the whole tail of the
 * buffer.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class FastUtf8CharsetDecoder extends CharsetDecoder {

    FastUtf8CharsetDecoder() {
        super(StandardCharsets.UTF_8, 1.0f, 1.0f);
    }

    @Override
    protected CoderResult decodeLoop(ByteBuffer in, CharBuffer out) {
        if (in.hasArray() && out.hasArray()) {
            return decodeArray(in, out);
        }
        return decodeBuffer(in, out);
    }

    /**
     * Fast path: both buffers expose a directly-accessible backing array,
     * so decoding proceeds via raw array indexing rather than the
     * bounds-checked, virtual-dispatch {@code get(int)}/{@code put(int,
     * char)} accessors.
     */
    private CoderResult decodeArray(ByteBuffer in, CharBuffer out) {
        byte[] src = in.array();
        int srcBase = in.arrayOffset();
        int pos = srcBase + in.position();
        int limit = srcBase + in.limit();

        char[] dst = out.array();
        int dstBase = out.arrayOffset();
        int outPos = dstBase + out.position();
        int outLimit = dstBase + out.limit();

        while (pos < limit) {
            int b0 = src[pos] & 0xFF;

            if (b0 < 0x80) {
                if (outPos >= outLimit) {
                    in.position(pos - srcBase);
                    out.position(outPos - dstBase);
                    return CoderResult.OVERFLOW;
                }
                dst[outPos++] = (char) b0;
                pos++;
                continue;
            }

            int decoded = Utf8.decode(src, pos, limit);
            if (decoded == Utf8.TRUNCATED) {
                in.position(pos - srcBase);
                out.position(outPos - dstBase);
                return CoderResult.UNDERFLOW;
            }
            if (decoded == Utf8.MALFORMED) {
                in.position(pos - srcBase);
                out.position(outPos - dstBase);
                return CoderResult.malformedForLength(1);
            }

            int codePoint = Utf8.codePoint(decoded);
            int seqLen = Utf8.length(decoded);
            int charsNeeded = (codePoint <= 0xFFFF) ? 1 : 2;
            if (outLimit - outPos < charsNeeded) {
                in.position(pos - srcBase);
                out.position(outPos - dstBase);
                return CoderResult.OVERFLOW;
            }
            if (codePoint <= 0xFFFF) {
                dst[outPos++] = (char) codePoint;
            } else {
                int adjusted = codePoint - 0x10000;
                dst[outPos++] = (char) (0xD800 + (adjusted >> 10));
                dst[outPos++] = (char) (0xDC00 + (adjusted & 0x3FF));
            }
            pos += seqLen;
        }

        in.position(pos - srcBase);
        out.position(outPos - dstBase);
        return CoderResult.UNDERFLOW;
    }

    /**
     * Fallback path for a non-array-backed {@code ByteBuffer}/{@code
     * CharBuffer} (e.g. a direct buffer): identical logic to {@link
     * #decodeArray}, using the buffers' absolute {@code get(int)}/{@code
     * put(int, char)} accessors instead of raw arrays.
     */
    private CoderResult decodeBuffer(ByteBuffer in, CharBuffer out) {
        int pos = in.position();
        int limit = in.limit();
        int outPos = out.position();
        int outLimit = out.limit();

        while (pos < limit) {
            int b0 = in.get(pos) & 0xFF;

            if (b0 < 0x80) {
                if (outPos >= outLimit) {
                    in.position(pos);
                    out.position(outPos);
                    return CoderResult.OVERFLOW;
                }
                out.put(outPos++, (char) b0);
                pos++;
                continue;
            }

            int decoded = Utf8.decode(in, pos, limit);
            if (decoded == Utf8.TRUNCATED) {
                in.position(pos);
                out.position(outPos);
                return CoderResult.UNDERFLOW;
            }
            if (decoded == Utf8.MALFORMED) {
                in.position(pos);
                out.position(outPos);
                return CoderResult.malformedForLength(1);
            }

            int codePoint = Utf8.codePoint(decoded);
            int seqLen = Utf8.length(decoded);
            int charsNeeded = (codePoint <= 0xFFFF) ? 1 : 2;
            if (outLimit - outPos < charsNeeded) {
                in.position(pos);
                out.position(outPos);
                return CoderResult.OVERFLOW;
            }
            if (codePoint <= 0xFFFF) {
                out.put(outPos++, (char) codePoint);
            } else {
                int adjusted = codePoint - 0x10000;
                out.put(outPos++, (char) (0xD800 + (adjusted >> 10)));
                out.put(outPos++, (char) (0xDC00 + (adjusted & 0x3FF)));
            }
            pos += seqLen;
        }

        in.position(pos);
        out.position(outPos);
        return CoderResult.UNDERFLOW;
    }

}

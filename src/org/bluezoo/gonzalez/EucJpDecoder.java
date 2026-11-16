/*
 * EucJpDecoder.java
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
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;

/**
 * Decodes EUC-JP with tables.
 * <p>
 * The JDK's EUC-JP decoder looks each character up through several layers of
 * method calls, and on a document of Japanese text that was almost two thirds
 * of the time the whole parse took. This decoder looks the common
 * sequences up directly in three tables: JIS X 0208 (two bytes, both from
 * A1 to FE), JIS X 0212 (the byte 8F and two from A1 to FE) and half-width
 * katakana (8E and a byte from A1 to DF).
 * <p>
 * The tables are not written out here. They are made, once, by asking the
 * JDK's own decoder what each sequence is, so that the characters are always
 * those of the JDK the parser is running on. And only what is regular is
 * decoded here: at the first sequence that is not in a table (one that is not
 * assigned a character, or not complete, or is where the output buffer ends)
 * the rest of the input is given to the JDK's decoder, so that every error and
 * every wait for more input is the JDK's, and is reported as it is.
 */
final class EucJpDecoder extends CharsetDecoder {

    /** The first and last bytes of the ranges of the two-byte sets, and their size. */
    private static final int FIRST = 0xA1;
    private static final int LAST = 0xFE;
    private static final int SPAN = LAST - FIRST + 1;

    /** The characters, by code, or zero where there is none. */
    private static final class Tables {
        /**
         * The two-byte sets, by the two bytes as they are written, (first &lt;&lt; 8
         * | second): JIS X 0208 for the first byte A1 to FE, and the half-width
         * katakana, for 8E. Every other entry is zero, so that one look-up says
         * whether there is a character, whatever the bytes are.
         */
        final char[] twoByte = new char[0x10000];
        /** JIS X 0212, by ((first - A1) * 94 + (second - A1)), for 8F first second. */
        final char[] jis0212 = new char[SPAN * SPAN];
    }

    /** The tables, made when first wanted; null if they could not be made. */
    private static Tables shared;
    private static boolean sharedTried;

    private static synchronized Tables tables(Charset charset) {
        if (!sharedTried) {
            sharedTried = true;
            shared = makeTables(charset);
        }
        return shared;
    }

    /**
     * Asks the JDK's decoder for what every sequence is. Each sequence is
     * decoded to exactly one character, which is U+FFFD where it has none, so
     * that the characters of all of them can be had in one call.
     */
    private static Tables makeTables(Charset charset) {
        try {
            Tables t = new Tables();
            CharsetDecoder decoder = charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE);
            int pairs = SPAN * SPAN;

            byte[] in = new byte[2 * pairs];
            for (int i = 0; i < pairs; i++) {
                in[2 * i] = (byte) (FIRST + i / SPAN);
                in[2 * i + 1] = (byte) (FIRST + i % SPAN);
            }
            char[] jis0208 = new char[pairs];
            if (!fill(decoder, in, jis0208, pairs)) {
                return null;
            }
            for (int i = 0; i < pairs; i++) {
                t.twoByte[((FIRST + i / SPAN) << 8) | (FIRST + i % SPAN)] = jis0208[i];
            }

            in = new byte[3 * pairs];
            for (int i = 0; i < pairs; i++) {
                in[3 * i] = (byte) 0x8F;
                in[3 * i + 1] = (byte) (FIRST + i / SPAN);
                in[3 * i + 2] = (byte) (FIRST + i % SPAN);
            }
            if (!fill(decoder, in, t.jis0212, pairs)) {
                return null;
            }

            in = new byte[2 * SPAN];
            for (int i = 0; i < SPAN; i++) {
                in[2 * i] = (byte) 0x8E;
                in[2 * i + 1] = (byte) (FIRST + i);
            }
            char[] kana = new char[SPAN];
            if (!fill(decoder, in, kana, SPAN)) {
                return null;
            }
            for (int i = 0; i < SPAN; i++) {
                t.twoByte[(0x8E << 8) | (FIRST + i)] = kana[i];
            }
            return t;
        } catch (RuntimeException | java.nio.charset.CharacterCodingException e) {
            return null;
        }
    }

    private static boolean fill(CharsetDecoder decoder, byte[] in, char[] table, int count)
            throws java.nio.charset.CharacterCodingException {
        CharBuffer out = decoder.decode(ByteBuffer.wrap(in));
        if (out.remaining() != count) {
            return false;
        }
        for (int i = 0; i < count; i++) {
            char c = out.get(i);
            table[i] = c == '\ufffd' ? 0 : c;
        }
        return true;
    }

    private final Tables tables;
    private final CharsetDecoder jdk;

    EucJpDecoder(Charset charset) {
        super(charset, 0.5f, 1.0f);
        this.tables = tables(charset);
        this.jdk = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
    }

    @Override
    protected CoderResult decodeLoop(ByteBuffer in, CharBuffer out) {
        if (tables != null && in.hasArray() && out.hasArray() && !out.isReadOnly()) {
            fast(in, out);
        }
        return jdk.decode(in, out, false);
    }

    /**
     * Decodes what is regular, from the start of the input, and leaves the
     * positions of both buffers at the first thing that is not.
     */
    private void fast(ByteBuffer in, CharBuffer out) {
        byte[] src = in.array();
        int base = in.arrayOffset();
        int sp = base + in.position();
        int sl = base + in.limit();
        char[] dst = out.array();
        int dbase = out.arrayOffset();
        int dp = dbase + out.position();
        int dl = dbase + out.limit();
        char[] twoByte = tables.twoByte;
        char[] jis0212 = tables.jis0212;

        // Up to three bytes are read for each character, so stop when fewer
        // are left; the JDK's decoder takes what is at the end.
        int safeSl = sl - 2;
        while (sp < safeSl && dp < dl) {
            int b1 = src[sp];
            if (b1 >= 0) {
                int end = Math.min(safeSl, sp + (dl - dp));
                while (sp < end) {
                    int b = src[sp];
                    if (b < 0) {
                        break;
                    }
                    dst[dp++] = (char) b;
                    sp++;
                }
                continue;
            }
            char c = twoByte[((b1 & 0xFF) << 8) | (src[sp + 1] & 0xFF)];
            if (c != 0) {
                sp += 2;
            } else if (b1 == (byte) 0x8F) {
                int second = (src[sp + 1] & 0xFF) - FIRST;
                int third = (src[sp + 2] & 0xFF) - FIRST;
                if (second < 0 || second >= SPAN || third < 0 || third >= SPAN) {
                    break;
                }
                c = jis0212[second * SPAN + third];
                if (c == 0) {
                    break;
                }
                sp += 3;
            } else {
                break;
            }
            dst[dp++] = c;
        }
        in.position(sp - base);
        out.position(dp - dbase);
    }

    @Override
    protected void implReset() {
        jdk.reset();
    }
}

/*
 * PackedName.java
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

import java.nio.CharBuffer;

/**
 * Zero-allocation-on-hit interning pool for short, name-like strings
 * (element/attribute names, PI targets), using quad-packed primitive
 * comparison instead of {@link InternedStringPool}'s per-character
 * hash-then-compare loop.
 * <p>
 * Structurally similar to {@link InternedStringPool} (a sparse array, linear
 * probing up to {@link #MAX_PROBES}, and the same {@link CharBuffer}-window
 * calling convention) - the difference is entirely in how a candidate is
 * hashed and compared. Up to 12 characters of a name are packed 4-per-
 * {@code long} into {@code q0}/{@code q1}/{@code q2} (a {@code char} is 16
 * bits, so 4 fit exactly into one 64-bit long); comparing a candidate
 * against a stored entry is then up to three primitive {@code long}
 * comparisons, regardless of the name's length, rather than a per-character
 * loop. This mirrors aalto-xml's {@code PName1}/{@code PName2}/{@code PName3}
 * technique, char-based rather than byte-based per this project's decision
 * (works the same way for char, just packs half as densely - 2 chars/int
 * equivalent if it used int, or 4 chars/long as done here).
 * <p>
 * Names longer than 12 characters still get O(1) rejection on a mismatched
 * prefix (the packed quads cover the first 12 characters), but a match on
 * those quads plus a rolling hash of the remainder is not guaranteed exact -
 * a final character-range comparison against the stored String resolves the
 * rare case of two different long names sharing both their first-12-chars
 * packing and their remainder hash.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class PackedName {

    private static final int MAX_PROBES = 4;
    private static final int DENSE_CHARS = 12;

    private final long[] q0;
    private final long[] q1;
    private final long[] q2;
    private final int[] lengths;
    private final String[] values;
    private final int mask;

    PackedName() {
        this(512);
    }

    PackedName(int initialCapacity) {
        int capacity = nextPowerOfTwo(Math.max(256, initialCapacity));
        this.mask = capacity - 1;
        this.q0 = new long[capacity];
        this.q1 = new long[capacity];
        this.q2 = new long[capacity];
        this.lengths = new int[capacity];
        this.values = new String[capacity];
    }

    /**
     * Interns a name from a CharBuffer window (position to limit), returning
     * a canonical String instance. Zero-allocation on a cache hit.
     *
     * @param buffer the CharBuffer containing the name (uses position/limit as window)
     * @return the interned String
     */
    String intern(CharBuffer buffer) {
        if (buffer.hasArray() && !buffer.isReadOnly()) {
            char[] array = buffer.array();
            int start = buffer.arrayOffset() + buffer.position();
            return internRange(array, start, buffer.remaining());
        }
        int len = buffer.remaining();
        int pos = buffer.position();
        char[] copy = new char[len];
        for (int i = 0; i < len; i++) {
            copy[i] = buffer.get(pos + i);
        }
        return internRange(copy, 0, len);
    }

    /**
     * Interns a name directly from a char-array range - same contract as
     * {@link #intern(CharBuffer)} minus the window unwrapping, for callers
     * that already hold the backing array (the {@link Scanner} hot paths,
     * which would otherwise allocate a throwaway {@code CharBuffer.wrap}
     * per name just to pass the range in).
     */
    String internRange(char[] buf, int start, int len) {
        long p0 = 0;
        long p1 = 0;
        long p2 = 0;
        int dense = Math.min(len, DENSE_CHARS);
        for (int i = 0; i < dense; i++) {
            char c = buf[start + i];
            if (i < 4) {
                p0 = (p0 << 16) | c;
            } else if (i < 8) {
                p1 = (p1 << 16) | c;
            } else {
                p2 = (p2 << 16) | c;
            }
        }
        int hash = (int) (p0 * 31 + p1 * 17 + p2 * 7) + len;
        if (len > DENSE_CHARS) {
            for (int i = DENSE_CHARS; i < len; i++) {
                hash = hash * 31 + buf[start + i];
            }
        }

        int index = hash & mask;
        for (int probe = 0; probe < MAX_PROBES; probe++) {
            int slot = (index + probe) & mask;
            String candidate = values[slot];
            if (candidate == null) {
                String newString = new String(buf, start, len);
                q0[slot] = p0;
                q1[slot] = p1;
                q2[slot] = p2;
                lengths[slot] = len;
                values[slot] = newString;
                return newString;
            }
            if (lengths[slot] == len && q0[slot] == p0 && q1[slot] == p1 && q2[slot] == p2) {
                if (len <= DENSE_CHARS || rangeEquals(buf, start, len, candidate)) {
                    return candidate;
                }
                // Quads + remainder hash collided but the actual content
                // differs (only possible for names > 12 chars) - keep probing.
            }
        }
        // Exceeded probe limit - create without caching to avoid polluting
        // the pool with an entry that will never be found again anyway.
        return new String(buf, start, len);
    }

    private static boolean rangeEquals(char[] buf, int start, int len, String s) {
        for (int i = 0; i < len; i++) {
            if (buf[start + i] != s.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private static int nextPowerOfTwo(int n) {
        if ((n & (n - 1)) == 0) {
            return n;
        }
        n--;
        n |= n >> 1;
        n |= n >> 2;
        n |= n >> 4;
        n |= n >> 8;
        n |= n >> 16;
        n++;
        return n;
    }

}

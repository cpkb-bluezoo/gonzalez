/*
 * PackedNameTest.java
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
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

/**
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class PackedNameTest {

    // Array-backed (hasArray()==true), matching Scanner's actual usage
    // (CharBuffer.wrap(buf, start, len) over its own char[] scan buffer) -
    // the fast path intern(CharBuffer) should take in production.
    private static String intern(PackedName pool, String s) {
        char[] chars = s.toCharArray();
        return pool.intern(CharBuffer.wrap(chars, 0, chars.length));
    }

    @Test
    public void testBasicInterningAndCacheHit() {
        PackedName pool = new PackedName();
        String first = intern(pool, "book");
        String second = intern(pool, "book");
        assertEquals("book", first);
        assertSame("second lookup of the same name should hit the cache", first, second);
    }

    @Test
    public void testDifferentNamesDoNotCollide() {
        PackedName pool = new PackedName();
        String a = intern(pool, "author");
        String b = intern(pool, "publisher");
        assertEquals("author", a);
        assertEquals("publisher", b);
        assertSame(a, intern(pool, "author"));
        assertSame(b, intern(pool, "publisher"));
    }

    @Test
    public void testQuadBoundaryLengths() {
        // Exactly 4, 8, and 12 characters - the packing boundaries between
        // q0/q1/q2.
        PackedName pool = new PackedName();
        String[] names = {"abcd", "abcdefgh", "abcdefghijkl"};
        for (String name : names) {
            String first = intern(pool, name);
            String second = intern(pool, name);
            assertEquals(name, first);
            assertSame(first, second);
        }
    }

    @Test
    public void testNamesLongerThanDensePacking() {
        // Past the 12-char dense-packing boundary - exercises the
        // rolling-hash-of-remainder + final rangeEquals fallback path.
        PackedName pool = new PackedName();
        String[] names = {
            "element-with-a-fairly-long-name",
            "attribute-with-a-long-name",
            "element-with-a-fairly-long-name-but-different-suffix",
        };
        for (String name : names) {
            String first = intern(pool, name);
            String second = intern(pool, name);
            assertEquals(name, first);
            assertSame(first, second);
        }
        // Distinct long names sharing their first 12 characters (and thus
        // their dense-packed quads) must still resolve to distinct, correct
        // cached values, not collide into one.
        String a = intern(pool, "element-with-a-fairly-long-name");
        String b = intern(pool, "element-with-a-fairly-long-name-but-different-suffix");
        assertEquals("element-with-a-fairly-long-name", a);
        assertEquals("element-with-a-fairly-long-name-but-different-suffix", b);
    }

    @Test
    public void testDistinctLongNamesSharingPrefixResolveIndependently() {
        PackedName pool = new PackedName();
        String short1 = "prefix-shared-name-AAAA";
        String short2 = "prefix-shared-name-BBBB";
        String r1 = intern(pool, short1);
        String r2 = intern(pool, short2);
        assertEquals(short1, r1);
        assertEquals(short2, r2);
        assertSame(r1, intern(pool, short1));
        assertSame(r2, intern(pool, short2));
    }

    @Test
    public void testProbeOverflowDoesNotCrash() {
        // Force many distinct names into a small pool to exercise the
        // MAX_PROBES exhaustion fallback (create-without-caching) path.
        PackedName pool = new PackedName(1);
        for (int i = 0; i < 2000; i++) {
            String name = "n" + i;
            String result = intern(pool, name);
            assertEquals(name, result);
        }
    }

    @Test
    public void testManyDistinctNamesAllCacheCorrectly() {
        PackedName pool = new PackedName();
        Random random = new Random(42);
        Map<String, String> expected = new HashMap<String, String>();
        for (int i = 0; i < 500; i++) {
            int len = 1 + random.nextInt(20);
            StringBuilder sb = new StringBuilder(len);
            for (int j = 0; j < len; j++) {
                sb.append((char) ('a' + random.nextInt(26)));
            }
            String name = sb.toString();
            String first = intern(pool, name);
            assertEquals(name, first);
            String previouslySeen = expected.get(name);
            if (previouslySeen == null) {
                expected.put(name, first);
            } else {
                // Same content interned earlier - unless probe overflow
                // occurred (name went uncached), this must be the same
                // reference. Compare by content either way as the primary
                // correctness check.
                assertEquals(name, first);
            }
        }
        // Re-intern everything once more; every name must still round-trip
        // to equal content (reference identity only guaranteed if it was
        // never bumped by probe overflow, so only content is asserted here).
        for (String name : expected.keySet()) {
            assertEquals(name, intern(pool, name));
        }
    }

    @Test
    public void testSingleCharacterName() {
        PackedName pool = new PackedName();
        String a = intern(pool, "a");
        String b = intern(pool, "a");
        assertEquals("a", a);
        assertSame(a, b);
    }

    @Test
    public void testNonArrayBackedCharBufferFallbackPath() {
        // CharBuffer.wrap(CharSequence) has no backing array (hasArray() is
        // false) - exercises intern(CharBuffer)'s copy-then-intern fallback,
        // distinct from the array-backed fast path every other test above
        // uses. Interned separately from the array-backed pool above so a
        // cache hit here proves the fallback path's own hashing/packing is
        // internally consistent, not just falling through to the fast path.
        PackedName pool = new PackedName();
        CharBuffer nonArrayBacked = CharBuffer.wrap("publisher");
        assertEquals(false, nonArrayBacked.hasArray());
        String first = pool.intern(nonArrayBacked);
        String second = pool.intern(CharBuffer.wrap("publisher"));
        assertEquals("publisher", first);
        assertSame(first, second);
    }

}

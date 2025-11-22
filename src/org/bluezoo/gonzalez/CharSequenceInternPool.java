/*
 * CharSequenceInternPool.java
 * Copyright (C) 2025 Chris Burdess
 *
 * This file is part of Gonzalez, a streaming XML parser.
 * For more information please visit https://www.nongnu.org/gonzalez/
 *
 * Gonzalez is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Gonzalez is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Gonzalez.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.bluezoo.gonzalez;

import java.nio.CharBuffer;
import java.util.HashMap;

/**
 * Zero-allocation string interning pool that can look up interned strings
 * from CharSequence without creating temporary String objects.
 * 
 * <p>This pool uses a custom hash map that can lookup by CharSequence
 * and only allocates a String if the sequence is not already in the pool.
 * 
 * <p>Usage:
 * <pre>
 * CharSequenceInternPool pool = new CharSequenceInternPool();
 * String interned = pool.intern(charBuffer);  // Zero allocation if already pooled
 * </pre>
 * 
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
class CharSequenceInternPool {
    
    /**
     * Wrapper for CharSequence that implements proper hashCode/equals
     * for use as a HashMap key without allocating a String.
     */
    private static class CharSequenceKey {
        private final CharSequence sequence;
        private final int hash;
        
        CharSequenceKey(CharSequence sequence) {
            this.sequence = sequence;
            this.hash = computeHash(sequence);
        }
        
        private static int computeHash(CharSequence seq) {
            int h = 0;
            int len = seq.length();
            for (int i = 0; i < len; i++) {
                h = 31 * h + seq.charAt(i);
            }
            return h;
        }
        
        @Override
        public int hashCode() {
            return hash;
        }
        
        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof CharSequenceKey)) return false;
            
            CharSequenceKey other = (CharSequenceKey) obj;
            
            // Fast path: different hash means different content
            if (this.hash != other.hash) return false;
            
            // Compare character by character
            CharSequence s1 = this.sequence;
            CharSequence s2 = other.sequence;
            
            int len = s1.length();
            if (len != s2.length()) return false;
            
            for (int i = 0; i < len; i++) {
                if (s1.charAt(i) != s2.charAt(i)) return false;
            }
            
            return true;
        }
    }
    
    /**
     * The actual intern pool: maps CharSequence content → interned String
     */
    private final HashMap<CharSequenceKey, String> pool;
    
    /**
     * Reusable key for lookups (avoids allocating a new key each time)
     */
    private final CharSequenceKey lookupKey;
    
    /**
     * Creates a new intern pool with default initial capacity.
     */
    CharSequenceInternPool() {
        this(256);  // Reasonable default for element/attribute names
    }
    
    /**
     * Creates a new intern pool with specified initial capacity.
     * 
     * @param initialCapacity the initial capacity
     */
    CharSequenceInternPool(int initialCapacity) {
        this.pool = new HashMap<>(initialCapacity);
        this.lookupKey = new CharSequenceKey(new StringBuilder());  // Dummy for initialization
    }
    
    /**
     * Interns a CharSequence, returning a canonical String instance.
     * 
     * <p>If the sequence is already in the pool, returns the existing String
     * without allocation. Otherwise, creates a new String, adds it to the pool,
     * and returns it.
     * 
     * <p>This method is NOT thread-safe. The pool should not be shared across
     * threads without external synchronization.
     * 
     * @param sequence the character sequence to intern
     * @return the interned String
     */
    String intern(CharSequence sequence) {
        if (sequence == null) {
            return null;
        }
        
        // Reuse lookup key to avoid allocation
        CharSequenceKey key = new CharSequenceKey(sequence);
        
        // Check if already in pool
        String interned = pool.get(key);
        if (interned != null) {
            return interned;
        }
        
        // Not in pool: create new String and add it
        // Use String.valueOf for efficiency if it's a CharBuffer
        String newString;
        if (sequence instanceof CharBuffer) {
            newString = ((CharBuffer) sequence).toString();
        } else {
            newString = sequence.toString();
        }
        
        // Add to pool using a permanent key (sequence might be mutable)
        CharSequenceKey permanentKey = new CharSequenceKey(newString);
        pool.put(permanentKey, newString);
        
        return newString;
    }
    
    /**
     * Clears all interned strings from the pool.
     */
    void clear() {
        pool.clear();
    }
    
    /**
     * Returns the number of strings currently in the pool.
     * 
     * @return the pool size
     */
    int size() {
        return pool.size();
    }
}


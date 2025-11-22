/*
 * QNamePool.java
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

import java.util.HashMap;

/**
 * Pool for reusing QName objects to avoid allocation overhead.
 * 
 * <p>Since QName objects are immutable and used extensively as HashMap keys
 * in SAXAttributes, pooling them reduces both allocation and hash computation overhead.
 * 
 * <p>This pool is NOT thread-safe and should not be shared across threads.
 * 
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
class QNamePool {
    
    /**
     * Pool of canonical QName instances.
     * Key is a QName, value is the same QName (for interning).
     */
    private final HashMap<QName, QName> pool;
    
    /**
     * Creates a new QName pool with default initial capacity.
     */
    QNamePool() {
        this(128); // Reasonable default for element/attribute names
    }
    
    /**
     * Creates a new QName pool with specified initial capacity.
     * 
     * @param initialCapacity the initial capacity
     */
    QNamePool(int initialCapacity) {
        this.pool = new HashMap<>(initialCapacity);
    }
    
    /**
     * Gets a QName from the pool, creating and caching it if not present.
     * 
     * <p>All input strings (uri, localName, qName) should already be interned
     * for maximum efficiency.
     * 
     * @param uri the namespace URI (never null, use "" for no namespace)
     * @param localName the local name (never null)
     * @param qName the qualified name (never null)
     * @return the canonical QName instance
     */
    QName get(String uri, String localName, String qName) {
        // Create temporary QName for lookup
        QName key = new QName(uri, localName, qName);
        
        // Check if already in pool
        QName pooled = pool.get(key);
        if (pooled != null) {
            return pooled;
        }
        
        // Not in pool: add it
        pool.put(key, key);
        return key;
    }
    
    /**
     * Clears all QNames from the pool.
     */
    void clear() {
        pool.clear();
    }
    
    /**
     * Returns the number of QNames currently in the pool.
     * 
     * @return the pool size
     */
    int size() {
        return pool.size();
    }
}


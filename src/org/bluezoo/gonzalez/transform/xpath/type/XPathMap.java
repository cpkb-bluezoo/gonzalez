/*
 * XPathMap.java
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

package org.bluezoo.gonzalez.transform.xpath.type;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An XPath 3.1 map value.
 *
 * <p>Maps are immutable collections of key-value pairs where keys are atomic
 * values and values are arbitrary XPath sequences. Keys are compared by
 * their string representation.
 *
 * <p>In XPath 3.1, maps are also functions: calling a map with a key argument
 * returns the associated value (equivalent to {@code map:get}).
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class XPathMap implements XPathValue {

    /** An empty map. */
    public static final XPathMap EMPTY = new XPathMap(new LinkedHashMap<String, XPathValue>());

    private final Map<String, XPathValue> entries;

    /**
     * Creates a map from the given entries.
     *
     * @param entries the map entries (keys are string representations of atomic keys)
     */
    public XPathMap(Map<String, XPathValue> entries) {
        this.entries = new LinkedHashMap<String, XPathValue>(entries);
    }

    /**
     * Returns the value associated with the given key, or null if absent.
     *
     * @param key the key (as a string)
     * @return the associated value, or null
     */
    public XPathValue get(String key) {
        return entries.get(key);
    }

    /**
     * Returns true if the map contains the given key.
     *
     * @param key the key (as a string)
     * @return true if the key is present
     */
    public boolean containsKey(String key) {
        return entries.containsKey(key);
    }

    /**
     * Returns the number of entries in this map.
     *
     * @return the size
     */
    public int size() {
        return entries.size();
    }

    /**
     * Returns the keys of this map as a list of string values.
     *
     * @return the keys
     */
    public List<XPathValue> keys() {
        List<XPathValue> result = new ArrayList<XPathValue>(entries.size());
        for (String key : entries.keySet()) {
            result.add(XPathString.of(key));
        }
        return result;
    }

    /**
     * Returns a new map with the given key-value pair added or replaced.
     *
     * @param key the key
     * @param value the value
     * @return a new map
     */
    public XPathMap put(String key, XPathValue value) {
        Map<String, XPathValue> newEntries = new LinkedHashMap<String, XPathValue>(entries);
        newEntries.put(key, value);
        return new XPathMap(newEntries);
    }

    /**
     * Returns a new map with the given key removed.
     *
     * @param key the key to remove
     * @return a new map
     */
    public XPathMap remove(String key) {
        Map<String, XPathValue> newEntries = new LinkedHashMap<String, XPathValue>(entries);
        newEntries.remove(key);
        return new XPathMap(newEntries);
    }

    /**
     * Returns the underlying entries for iteration.
     *
     * @return the entry set
     */
    public Iterable<Map.Entry<String, XPathValue>> entries() {
        return entries.entrySet();
    }

    @Override
    public Type getType() {
        return Type.MAP;
    }

    @Override
    public String asString() {
        // XPath 3.1: string value of a map is not defined; return empty string
        return "";
    }

    @Override
    public double asNumber() {
        return Double.NaN;
    }

    @Override
    public boolean asBoolean() {
        // Maps are truthy (they are function items)
        return true;
    }

    @Override
    public XPathNodeSet asNodeSet() {
        return XPathNodeSet.EMPTY;
    }

    @Override
    public Iterator<XPathValue> sequenceIterator() {
        List<XPathValue> self = new ArrayList<XPathValue>(1);
        self.add(this);
        return self.iterator();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("map{");
        boolean first = true;
        for (Map.Entry<String, XPathValue> entry : entries.entrySet()) {
            if (!first) {
                sb.append(", ");
            }
            sb.append("'");
            sb.append(entry.getKey());
            sb.append("': ");
            sb.append(entry.getValue());
            first = false;
        }
        sb.append("}");
        return sb.toString();
    }
}

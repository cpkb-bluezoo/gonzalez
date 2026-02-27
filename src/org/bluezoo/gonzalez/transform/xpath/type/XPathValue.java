/*
 * XPathValue.java
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

import java.util.Collections;
import java.util.Iterator;

/**
 * Base interface for all XPath value types.
 *
 * <p>XPath 1.0 defines four data types:
 * <ul>
 *   <li><b>string</b> - a sequence of Unicode characters</li>
 *   <li><b>number</b> - IEEE 754 double-precision floating point</li>
 *   <li><b>boolean</b> - true or false</li>
 *   <li><b>node-set</b> - an unordered collection of nodes without duplicates</li>
 * </ul>
 *
 * <p>XPath 2.0/3.1 extends this with:
 * <ul>
 *   <li><b>sequence</b> - an ordered collection of items (nodes or atomic values)</li>
 *   <li><b>atomic</b> - untyped atomic value (without schema type information)</li>
 * </ul>
 *
 * <p>Each XPath value can be converted to any other type according to the
 * type coercion rules defined in XPath 1.0 Section 4.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public interface XPathValue {

    /**
     * Enumeration of XPath value types.
     */
    enum Type {
        STRING,
        NUMBER,
        BOOLEAN,
        NODESET,
        /** XPath 2.0+ sequence type. */
        SEQUENCE,
        /** XPath 2.0+ untyped atomic value. */
        ATOMIC,
        /** XPath 3.1 map type. */
        MAP,
        /** XPath 3.1 array type. */
        ARRAY
    }

    /**
     * Returns the type of this value.
     *
     * @return the XPath type
     */
    Type getType();

    /**
     * Converts this value to a string according to XPath 1.0 Section 4.2.
     *
     * <p>Conversion rules:
     * <ul>
     *   <li>string → unchanged</li>
     *   <li>number → "NaN", "Infinity", "-Infinity", or decimal representation</li>
     *   <li>boolean → "true" or "false"</li>
     *   <li>node-set → string-value of first node in document order, or "" if empty</li>
     * </ul>
     *
     * @return the string representation
     */
    String asString();

    /**
     * Converts this value to a number according to XPath 1.0 Section 4.4.
     *
     * <p>Conversion rules:
     * <ul>
     *   <li>number → unchanged</li>
     *   <li>string → parsed as IEEE 754 number, or NaN if invalid</li>
     *   <li>boolean → 1 if true, 0 if false</li>
     *   <li>node-set → convert string-value to number</li>
     * </ul>
     *
     * @return the numeric representation
     */
    double asNumber();

    /**
     * Converts this value to a boolean according to XPath 1.0 Section 4.3.
     *
     * <p>Conversion rules:
     * <ul>
     *   <li>boolean → unchanged</li>
     *   <li>number → false if NaN or zero, true otherwise</li>
     *   <li>string → false if empty, true otherwise</li>
     *   <li>node-set → false if empty, true otherwise</li>
     * </ul>
     *
     * @return the boolean representation
     */
    boolean asBoolean();

    /**
     * Returns this value as a node-set if it is one.
     *
     * @return the node-set, or null if this is not a node-set value
     */
    XPathNodeSet asNodeSet();

    /**
     * Returns true if this value is a node-set.
     *
     * @return true if this is a node-set value
     */
    default boolean isNodeSet() {
        return getType() == Type.NODESET;
    }

    /**
     * Returns true if this value is a sequence (XPath 2.0+).
     *
     * @return true if this is a sequence value
     */
    default boolean isSequence() {
        return getType() == Type.SEQUENCE;
    }

    /**
     * Returns true if this value is an atomic value (XPath 2.0+).
     *
     * @return true if this is an atomic value
     */
    default boolean isAtomic() {
        return getType() == Type.ATOMIC;
    }

    /**
     * Returns the number of items in this value when treated as a sequence.
     * For XPath 1.0 types, this returns 1 (except node-set returns its size).
     *
     * @return the sequence length
     */
    default int sequenceSize() {
        if (isNodeSet()) {
            XPathNodeSet ns = asNodeSet();
            return ns != null ? ns.size() : 0;
        }
        return 1;
    }

    /**
     * Returns this value as a sequence iterator.
     * For singleton values, returns an iterator over just this value.
     *
     * @return an iterator over the items in this value
     */
    default Iterator<XPathValue> sequenceIterator() {
        return Collections.singletonList(this).iterator();
    }

}

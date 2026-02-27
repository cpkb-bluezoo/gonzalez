/*
 * XPathArray.java
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
import java.util.List;

/**
 * An XPath 3.1 array value.
 *
 * <p>An array is an ordered collection of members, where each member is an
 * arbitrary XPath value. Unlike sequences, arrays can be nested and their
 * members are not flattened.
 *
 * <p>In XPath 3.1, arrays are also functions: calling an array with an
 * integer argument returns the member at that position (1-based).
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class XPathArray implements XPathValue {

    /** An empty array. */
    public static final XPathArray EMPTY = new XPathArray(new ArrayList<XPathValue>());

    private final List<XPathValue> members;

    /**
     * Creates an array from the given members.
     *
     * @param members the array members (must not be null)
     */
    public XPathArray(List<XPathValue> members) {
        this.members = new ArrayList<XPathValue>(members);
    }

    /**
     * Returns the member at the given 1-based index.
     *
     * @param index the 1-based index
     * @return the member value
     * @throws IndexOutOfBoundsException if index is out of range
     */
    public XPathValue get(int index) {
        return members.get(index - 1);
    }

    /**
     * Returns the number of members in this array.
     *
     * @return the size
     */
    public int size() {
        return members.size();
    }

    /**
     * Returns the members as a list.
     *
     * @return the member list (unmodifiable view)
     */
    public List<XPathValue> members() {
        return java.util.Collections.unmodifiableList(members);
    }

    @Override
    public Type getType() {
        return Type.ARRAY;
    }

    @Override
    public String asString() {
        return "";
    }

    @Override
    public double asNumber() {
        return Double.NaN;
    }

    @Override
    public boolean asBoolean() {
        return true;
    }

    @Override
    public XPathNodeSet asNodeSet() {
        return XPathNodeSet.EMPTY;
    }

    @Override
    public int sequenceSize() {
        return members.size();
    }

    @Override
    public Iterator<XPathValue> sequenceIterator() {
        return members.iterator();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        for (int i = 0; i < members.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(members.get(i));
        }
        sb.append("]");
        return sb.toString();
    }
}

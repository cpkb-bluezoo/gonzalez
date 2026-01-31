/*
 * XPathAnyURI.java
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

/**
 * XPath 2.0 xs:anyURI atomic value.
 *
 * <p>Represents a URI value. This is a subtype of xs:string that is specifically
 * typed as xs:anyURI, allowing correct type matching for {@code instance of xs:anyURI}.
 *
 * <p>Per XPath 2.0/3.1 specification, xs:anyURI is an atomic type derived from
 * xs:anyAtomicType. Values of this type represent URIs according to RFC 3986.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class XPathAnyURI implements XPathValue, Comparable<XPathAnyURI> {

    /** Empty URI value. */
    public static final XPathAnyURI EMPTY = new XPathAnyURI("");

    private final String uri;

    /**
     * Creates a new xs:anyURI value.
     *
     * @param uri the URI string (null is treated as empty string)
     */
    public XPathAnyURI(String uri) {
        this.uri = uri != null ? uri : "";
    }

    /**
     * Creates an xs:anyURI value from a string.
     *
     * @param uri the URI string
     * @return the anyURI value
     */
    public static XPathAnyURI of(String uri) {
        if (uri == null || uri.isEmpty()) {
            return EMPTY;
        }
        return new XPathAnyURI(uri);
    }

    /**
     * Returns the URI string value.
     *
     * @return the URI
     */
    public String getUri() {
        return uri;
    }

    /**
     * Returns the XPath type of this value.
     *
     * @return {@link Type#ATOMIC}
     */
    @Override
    public Type getType() {
        return Type.ATOMIC;
    }

    /**
     * Returns the atomic type name for this value.
     *
     * @return "anyURI"
     */
    public String getAtomicTypeName() {
        return "anyURI";
    }

    /**
     * Returns the URI string value.
     *
     * @return the URI string
     */
    @Override
    public String asString() {
        return uri;
    }

    /**
     * URIs cannot be converted to numbers.
     *
     * @return NaN (URIs are not numeric)
     */
    @Override
    public double asNumber() {
        // URIs are not numeric
        return Double.NaN;
    }

    /**
     * Converts this URI to a boolean.
     *
     * <p>Non-empty URIs are truthy; empty URIs are falsy.
     *
     * @return true if the URI is non-empty, false otherwise
     */
    @Override
    public boolean asBoolean() {
        // Non-empty URI is truthy
        return !uri.isEmpty();
    }

    /**
     * URIs cannot be converted to node-sets.
     *
     * @return null (URIs are not node-sets)
     */
    @Override
    public XPathNodeSet asNodeSet() {
        return null;
    }

    /**
     * Compares this URI with another.
     *
     * <p>Comparison is done lexicographically on the URI strings.
     *
     * @param other the other URI (must not be null)
     * @return negative if this is less than other, zero if equal, positive if greater
     */
    @Override
    public int compareTo(XPathAnyURI other) {
        return this.uri.compareTo(other.uri);
    }

    /**
     * Compares this URI with another object for equality.
     *
     * <p>URIs are equal if they have the same string value. Also compares equal
     * to XPathString values with the same string content.
     *
     * @param obj the object to compare
     * @return true if the objects are equal
     */
    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj instanceof XPathAnyURI) {
            return uri.equals(((XPathAnyURI) obj).uri);
        }
        if (obj instanceof XPathString) {
            return uri.equals(((XPathString) obj).asString());
        }
        return false;
    }

    /**
     * Returns a hash code for this URI.
     *
     * @return the hash code
     */
    @Override
    public int hashCode() {
        return uri.hashCode();
    }

    /**
     * Returns a string representation of this URI.
     *
     * @return a string in the format "xs:anyURI(uri)"
     */
    @Override
    public String toString() {
        return "xs:anyURI(" + uri + ")";
    }
}

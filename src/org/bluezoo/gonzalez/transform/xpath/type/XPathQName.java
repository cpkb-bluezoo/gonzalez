/*
 * XPathQName.java
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
 * XPath 2.0 xs:QName atomic value.
 *
 * <p>Represents a qualified name value consisting of an optional namespace URI,
 * optional prefix, and a required local name. This is used for element and
 * attribute names in XPath 2.0+.
 *
 * <p>Per XPath 2.0/3.1 specification, xs:QName is an atomic type that represents
 * a qualified name as defined by XML Namespaces.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class XPathQName implements XPathValue, Comparable<XPathQName> {

    /**
     * Callback interface for resolving namespace prefixes to URIs.
     */
    public interface NamespaceResolver {
        /**
         * Resolves a namespace prefix to its URI.
         *
         * @param prefix the namespace prefix
         * @return the namespace URI, or null if not found
         */
        String resolve(String prefix);
    }

    private final String namespaceURI;
    private final String prefix;
    private final String localName;

    /**
     * Creates a new xs:QName value.
     *
     * @param namespaceURI the namespace URI (may be null or empty for no namespace)
     * @param prefix the prefix (may be null or empty)
     * @param localName the local name (required)
     */
    public XPathQName(String namespaceURI, String prefix, String localName) {
        this.namespaceURI = namespaceURI != null ? namespaceURI : "";
        this.prefix = prefix != null ? prefix : "";
        this.localName = localName != null ? localName : "";
    }

    /**
     * Creates an xs:QName value with just a local name (no namespace).
     *
     * @param localName the local name
     * @return the QName value
     */
    public static XPathQName of(String localName) {
        return new XPathQName("", "", localName);
    }

    /**
     * Creates an xs:QName value with namespace URI and local name.
     *
     * @param namespaceURI the namespace URI
     * @param localName the local name
     * @return the QName value
     */
    public static XPathQName of(String namespaceURI, String localName) {
        return new XPathQName(namespaceURI, "", localName);
    }

    /**
     * Creates an xs:QName value with namespace URI, prefix, and local name.
     *
     * @param namespaceURI the namespace URI
     * @param prefix the prefix
     * @param localName the local name
     * @return the QName value
     */
    public static XPathQName of(String namespaceURI, String prefix, String localName) {
        return new XPathQName(namespaceURI, prefix, localName);
    }

    /**
     * Parses a QName from a string in the form "prefix:localName" or "localName".
     *
     * @param qname the QName string
     * @param namespaceResolver function to resolve prefix to namespace URI (may be null)
     * @return the QName value
     */
    public static XPathQName parse(String qname, NamespaceResolver namespaceResolver) {
        if (qname == null || qname.isEmpty()) {
            return new XPathQName("", "", "");
        }
        int colonPos = qname.indexOf(':');
        if (colonPos > 0) {
            String prefix = qname.substring(0, colonPos);
            String localName = qname.substring(colonPos + 1);
            String namespaceURI = "";
            if (namespaceResolver != null) {
                namespaceURI = namespaceResolver.resolve(prefix);
                if (namespaceURI == null) {
                    namespaceURI = "";
                }
            }
            return new XPathQName(namespaceURI, prefix, localName);
        } else {
            return new XPathQName("", "", qname);
        }
    }

    /**
     * Returns the namespace URI.
     *
     * @return the namespace URI (never null, may be empty)
     */
    public String getNamespaceURI() {
        return namespaceURI;
    }

    /**
     * Returns the prefix.
     *
     * @return the prefix (never null, may be empty)
     */
    public String getPrefix() {
        return prefix;
    }

    /**
     * Returns the local name.
     *
     * @return the local name (never null)
     */
    public String getLocalName() {
        return localName;
    }

    @Override
    public Type getType() {
        return Type.ATOMIC;
    }

    /**
     * Returns the atomic type name for this value.
     *
     * @return "QName"
     */
    public String getAtomicTypeName() {
        return "QName";
    }

    @Override
    public String asString() {
        // Return the lexical representation
        if (prefix != null && !prefix.isEmpty()) {
            return prefix + ":" + localName;
        }
        return localName;
    }

    @Override
    public double asNumber() {
        // QNames are not numeric
        return Double.NaN;
    }

    @Override
    public boolean asBoolean() {
        // Non-empty QName is truthy
        return localName != null && !localName.isEmpty();
    }

    @Override
    public XPathNodeSet asNodeSet() {
        return null;
    }

    @Override
    public int compareTo(XPathQName other) {
        // QNames are compared by namespace URI and local name
        int nsCompare = this.namespaceURI.compareTo(other.namespaceURI);
        if (nsCompare != 0) {
            return nsCompare;
        }
        return this.localName.compareTo(other.localName);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj instanceof XPathQName) {
            XPathQName other = (XPathQName) obj;
            // Two QNames are equal if they have the same namespace URI and local name
            // (prefix is not considered for equality)
            return namespaceURI.equals(other.namespaceURI) && 
                   localName.equals(other.localName);
        }
        return false;
    }

    @Override
    public int hashCode() {
        return 31 * namespaceURI.hashCode() + localName.hashCode();
    }

    @Override
    public String toString() {
        if (namespaceURI.isEmpty()) {
            return "xs:QName(" + localName + ")";
        }
        return "xs:QName({" + namespaceURI + "}" + localName + ")";
    }
}

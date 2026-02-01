/*
 * XPathTypedAtomic.java
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
 * A typed atomic value that carries schema type information.
 *
 * <p>When nodes have type annotations (e.g., from xsl:type attribute), the
 * {@code data()} function atomizes them to typed atomic values. This allows
 * {@code instance of xs:integer} to return true for values that came from
 * xs:integer-typed elements.
 *
 * <p>The typed atomic value stores:
 * <ul>
 *   <li>The canonical lexical value</li>
 *   <li>The type namespace URI (e.g., http://www.w3.org/2001/XMLSchema)</li>
 *   <li>The type local name (e.g., "integer", "string", "ID")</li>
 * </ul>
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public class XPathTypedAtomic implements XPathValue {

    /** XSD namespace URI */
    public static final String XS_NAMESPACE = "http://www.w3.org/2001/XMLSchema";

    private final String lexicalValue;
    private final String typeNamespaceURI;
    private final String typeLocalName;
    private Double numericCache;

    /**
     * Creates a typed atomic value.
     *
     * @param lexicalValue the canonical lexical value
     * @param typeNamespaceURI the type namespace URI
     * @param typeLocalName the type local name
     */
    public XPathTypedAtomic(String lexicalValue, String typeNamespaceURI, String typeLocalName) {
        this.lexicalValue = lexicalValue != null ? lexicalValue : "";
        this.typeNamespaceURI = typeNamespaceURI;
        this.typeLocalName = typeLocalName;
    }

    /**
     * Creates a typed atomic value with XSD type.
     *
     * @param lexicalValue the canonical lexical value
     * @param typeLocalName the type local name (e.g., "integer", "string")
     * @return the typed atomic value
     */
    public static XPathTypedAtomic xsd(String lexicalValue, String typeLocalName) {
        return new XPathTypedAtomic(lexicalValue, XS_NAMESPACE, typeLocalName);
    }

    /**
     * Creates a typed atomic value from a node with type annotation.
     *
     * <p>For list types (xs:NMTOKENS, xs:IDREFS, xs:ENTITIES), this returns
     * a sequence of typed atomic values, one for each whitespace-separated token.
     *
     * @param stringValue the string value of the node
     * @param typeNamespaceURI the type namespace from the node's annotation
     * @param typeLocalName the type local name from the node's annotation
     * @return the typed atomic value, sequence of values for list types, 
     *         or an untyped XPathString if no type annotation
     */
    public static XPathValue fromNode(String stringValue, String typeNamespaceURI, String typeLocalName) {
        if (typeLocalName == null) {
            // No type annotation - return untyped
            return XPathString.of(stringValue);
        }
        
        // Check for list types that should atomize to sequences
        if (XS_NAMESPACE.equals(typeNamespaceURI)) {
            String itemTypeName = getListItemType(typeLocalName);
            if (itemTypeName != null) {
                // Split on whitespace and return sequence of typed atoms
                return atomizeListType(stringValue, itemTypeName);
            }
        }
        
        return new XPathTypedAtomic(stringValue, typeNamespaceURI, typeLocalName);
    }
    
    /**
     * Returns the item type for a list type, or null if not a list type.
     */
    private static String getListItemType(String listTypeName) {
        switch (listTypeName) {
            case "NMTOKENS": return "NMTOKEN";
            case "IDREFS": return "IDREF";
            case "ENTITIES": return "ENTITY";
            default: return null;
        }
    }
    
    /**
     * Atomizes a list type value to a sequence of typed atoms.
     */
    private static XPathValue atomizeListType(String value, String itemTypeName) {
        if (value == null || value.trim().isEmpty()) {
            return XPathSequence.EMPTY;
        }
        
        String[] tokens = value.trim().split("\\s+");
        if (tokens.length == 0) {
            return XPathSequence.EMPTY;
        }
        if (tokens.length == 1) {
            return new XPathTypedAtomic(tokens[0], XS_NAMESPACE, itemTypeName);
        }
        
        java.util.List<XPathValue> items = new java.util.ArrayList<>(tokens.length);
        for (String token : tokens) {
            items.add(new XPathTypedAtomic(token, XS_NAMESPACE, itemTypeName));
        }
        return new XPathSequence(items);
    }

    /**
     * Returns the type namespace URI.
     *
     * @return the type namespace URI
     */
    public String getTypeNamespaceURI() {
        return typeNamespaceURI;
    }

    /**
     * Returns the type local name.
     *
     * @return the type local name
     */
    public String getTypeLocalName() {
        return typeLocalName;
    }

    /**
     * Returns true if this value has an XSD type.
     *
     * @return true if type namespace is XSD
     */
    public boolean isXSDType() {
        return XS_NAMESPACE.equals(typeNamespaceURI);
    }

    /**
     * Returns true if this value is of a numeric XSD type.
     *
     * @return true if the type is numeric
     */
    public boolean isNumericType() {
        if (!isXSDType() || typeLocalName == null) {
            return false;
        }
        switch (typeLocalName) {
            case "integer":
            case "int":
            case "long":
            case "short":
            case "byte":
            case "decimal":
            case "float":
            case "double":
            case "nonPositiveInteger":
            case "negativeInteger":
            case "nonNegativeInteger":
            case "positiveInteger":
            case "unsignedLong":
            case "unsignedInt":
            case "unsignedShort":
            case "unsignedByte":
                return true;
            default:
                return false;
        }
    }

    /**
     * Returns true if this value is of an integer XSD type.
     *
     * @return true if the type is in the integer hierarchy
     */
    public boolean isIntegerType() {
        if (!isXSDType() || typeLocalName == null) {
            return false;
        }
        switch (typeLocalName) {
            case "integer":
            case "int":
            case "long":
            case "short":
            case "byte":
            case "nonPositiveInteger":
            case "negativeInteger":
            case "nonNegativeInteger":
            case "positiveInteger":
            case "unsignedLong":
            case "unsignedInt":
            case "unsignedShort":
            case "unsignedByte":
                return true;
            default:
                return false;
        }
    }

    /**
     * Checks if this typed value is an instance of the specified XSD type.
     *
     * @param expectedTypeLocalName the expected type local name (e.g., "integer")
     * @return true if this value is an instance of the expected type
     */
    public boolean isInstanceOf(String expectedTypeLocalName) {
        return isInstanceOf(XS_NAMESPACE, expectedTypeLocalName);
    }

    /**
     * Checks if this typed value is an instance of the specified type.
     *
     * @param expectedNamespaceURI the expected type namespace URI
     * @param expectedTypeLocalName the expected type local name
     * @return true if this value is an instance of the expected type
     */
    public boolean isInstanceOf(String expectedNamespaceURI, String expectedTypeLocalName) {
        if (typeLocalName == null) {
            return false;
        }

        // Exact match
        if (expectedTypeLocalName.equals(typeLocalName)) {
            String ns = typeNamespaceURI != null ? typeNamespaceURI : "";
            String expectedNs = expectedNamespaceURI != null ? expectedNamespaceURI : "";
            if (ns.equals(expectedNs)) {
                return true;
            }
        }

        // Check XSD type hierarchy
        if (XS_NAMESPACE.equals(typeNamespaceURI) && XS_NAMESPACE.equals(expectedNamespaceURI)) {
            return isSubtypeOf(typeLocalName, expectedTypeLocalName);
        }

        return false;
    }

    /**
     * Checks if actualType is a subtype of expectedType in the XSD type hierarchy.
     */
    private static boolean isSubtypeOf(String actualType, String expectedType) {
        if (actualType.equals(expectedType)) {
            return true;
        }

        // anyAtomicType matches all atomic types
        if ("anyAtomicType".equals(expectedType) || "anySimpleType".equals(expectedType)) {
            return true;
        }

        // Integer hierarchy: integer > long > int > short > byte
        // Also: integer > nonPositiveInteger > negativeInteger
        //       integer > nonNegativeInteger > positiveInteger, unsignedLong, etc.
        if ("integer".equals(expectedType)) {
            return isInIntegerHierarchy(actualType);
        }

        // decimal > integer > all integer subtypes
        if ("decimal".equals(expectedType)) {
            return "integer".equals(actualType) || isInIntegerHierarchy(actualType);
        }

        // String hierarchy: string > normalizedString > token > ...
        if ("string".equals(expectedType)) {
            return isInStringHierarchy(actualType);
        }
        if ("normalizedString".equals(expectedType)) {
            return isNormalizedStringSubtype(actualType);
        }
        if ("token".equals(expectedType)) {
            return isTokenSubtype(actualType);
        }
        if ("Name".equals(expectedType)) {
            return isNameSubtype(actualType);
        }
        if ("NCName".equals(expectedType)) {
            return isNCNameSubtype(actualType);
        }

        return false;
    }

    private static boolean isInIntegerHierarchy(String type) {
        switch (type) {
            case "integer":
            case "nonPositiveInteger":
            case "negativeInteger":
            case "long":
            case "int":
            case "short":
            case "byte":
            case "nonNegativeInteger":
            case "unsignedLong":
            case "unsignedInt":
            case "unsignedShort":
            case "unsignedByte":
            case "positiveInteger":
                return true;
            default:
                return false;
        }
    }

    private static boolean isInStringHierarchy(String type) {
        return "string".equals(type) || isNormalizedStringSubtype(type);
    }

    private static boolean isNormalizedStringSubtype(String type) {
        return "normalizedString".equals(type) || isTokenSubtype(type);
    }

    private static boolean isTokenSubtype(String type) {
        switch (type) {
            case "token":
            case "language":
            case "NMTOKEN":
            case "NMTOKENS":
                return true;
            default:
                return isNameSubtype(type);
        }
    }

    private static boolean isNameSubtype(String type) {
        return "Name".equals(type) || isNCNameSubtype(type);
    }

    private static boolean isNCNameSubtype(String type) {
        switch (type) {
            case "NCName":
            case "ID":
            case "IDREF":
            case "IDREFS":
            case "ENTITY":
            case "ENTITIES":
                return true;
            default:
                return false;
        }
    }

    @Override
    public Type getType() {
        return Type.ATOMIC;
    }

    @Override
    public String asString() {
        return lexicalValue;
    }

    @Override
    public double asNumber() {
        if (numericCache != null) {
            return numericCache;
        }
        try {
            numericCache = Double.parseDouble(lexicalValue.trim());
        } catch (NumberFormatException e) {
            numericCache = Double.NaN;
        }
        return numericCache;
    }

    @Override
    public boolean asBoolean() {
        // Boolean type-specific logic
        if (isXSDType() && "boolean".equals(typeLocalName)) {
            return "true".equals(lexicalValue) || "1".equals(lexicalValue);
        }
        // General rule: non-empty is true
        return !lexicalValue.isEmpty();
    }

    @Override
    public XPathNodeSet asNodeSet() {
        return null;
    }

    @Override
    public String toString() {
        if (typeLocalName != null) {
            String prefix = isXSDType() ? "xs:" : "{" + typeNamespaceURI + "}";
            return prefix + typeLocalName + "(" + lexicalValue + ")";
        }
        return "atomic(" + lexicalValue + ")";
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof XPathTypedAtomic)) {
            return false;
        }
        XPathTypedAtomic other = (XPathTypedAtomic) obj;
        return lexicalValue.equals(other.lexicalValue) &&
               (typeNamespaceURI == null ? other.typeNamespaceURI == null : 
                   typeNamespaceURI.equals(other.typeNamespaceURI)) &&
               (typeLocalName == null ? other.typeLocalName == null : 
                   typeLocalName.equals(other.typeLocalName));
    }

    @Override
    public int hashCode() {
        int result = lexicalValue.hashCode();
        result = 31 * result + (typeNamespaceURI != null ? typeNamespaceURI.hashCode() : 0);
        result = 31 * result + (typeLocalName != null ? typeLocalName.hashCode() : 0);
        return result;
    }
}

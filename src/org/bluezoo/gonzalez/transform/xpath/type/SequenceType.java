/*
 * SequenceType.java
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
 * Represents an XPath 2.0 sequence type.
 *
 * <p>A sequence type specifies what kind of values are allowed:
 * <ul>
 *   <li>Item types: item(), node(), element(), attribute(), text(), etc.</li>
 *   <li>Atomic types: xs:integer, xs:string, xs:boolean, etc.</li>
 *   <li>Occurrence indicators: ? (zero-or-one), * (zero-or-more), + (one-or-more)</li>
 * </ul>
 *
 * <p>Examples:
 * <ul>
 *   <li>{@code xs:integer} - exactly one integer</li>
 *   <li>{@code xs:string?} - zero or one string</li>
 *   <li>{@code node()*} - zero or more nodes</li>
 *   <li>{@code element()+} - one or more elements</li>
 *   <li>{@code empty-sequence()} - empty sequence</li>
 * </ul>
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public class SequenceType {
    
    /** XSD namespace URI for built-in types */
    public static final String XS_NAMESPACE = "http://www.w3.org/2001/XMLSchema";
    
    /**
     * Occurrence indicator for sequence types.
     */
    public enum Occurrence {
        /** Exactly one (default) */
        ONE,
        /** Zero or one (?) */
        ZERO_OR_ONE,
        /** Zero or more (*) */
        ZERO_OR_MORE,
        /** One or more (+) */
        ONE_OR_MORE
    }
    
    /**
     * Item type category.
     */
    public enum ItemKind {
        /** Any item: item() */
        ITEM,
        /** Any node: node() */
        NODE,
        /** Element node: element() or element(name) */
        ELEMENT,
        /** Attribute node: attribute() or attribute(name) */
        ATTRIBUTE,
        /** Text node: text() */
        TEXT,
        /** Comment node: comment() */
        COMMENT,
        /** Processing instruction: processing-instruction() */
        PROCESSING_INSTRUCTION,
        /** Document node: document-node() */
        DOCUMENT_NODE,
        /** Schema element: schema-element(name) */
        SCHEMA_ELEMENT,
        /** Schema attribute: schema-attribute(name) */
        SCHEMA_ATTRIBUTE,
        /** Atomic type (xs:integer, xs:string, etc.) */
        ATOMIC,
        /** Empty sequence */
        EMPTY
    }
    
    private final ItemKind itemKind;
    private final String namespaceURI;  // For atomic types or named element/attribute
    private final String localName;      // For atomic types or named element/attribute
    private final String typeName;       // For element(*, type) or attribute(*, type)
    private final Occurrence occurrence;
    
    // Common predefined types
    public static final SequenceType ITEM = new SequenceType(ItemKind.ITEM, null, null, null, Occurrence.ONE);
    public static final SequenceType ITEM_STAR = new SequenceType(ItemKind.ITEM, null, null, null, Occurrence.ZERO_OR_MORE);
    public static final SequenceType NODE = new SequenceType(ItemKind.NODE, null, null, null, Occurrence.ONE);
    public static final SequenceType NODE_STAR = new SequenceType(ItemKind.NODE, null, null, null, Occurrence.ZERO_OR_MORE);
    public static final SequenceType EMPTY = new SequenceType(ItemKind.EMPTY, null, null, null, Occurrence.ONE);
    
    // Common atomic types
    public static final SequenceType STRING = atomic(XS_NAMESPACE, "string", Occurrence.ONE);
    public static final SequenceType INTEGER = atomic(XS_NAMESPACE, "integer", Occurrence.ONE);
    public static final SequenceType DECIMAL = atomic(XS_NAMESPACE, "decimal", Occurrence.ONE);
    public static final SequenceType DOUBLE = atomic(XS_NAMESPACE, "double", Occurrence.ONE);
    public static final SequenceType BOOLEAN = atomic(XS_NAMESPACE, "boolean", Occurrence.ONE);
    public static final SequenceType DATE = atomic(XS_NAMESPACE, "date", Occurrence.ONE);
    public static final SequenceType DATETIME = atomic(XS_NAMESPACE, "dateTime", Occurrence.ONE);
    
    /**
     * Creates a sequence type.
     */
    public SequenceType(ItemKind itemKind, String namespaceURI, String localName, 
                        String typeName, Occurrence occurrence) {
        this.itemKind = itemKind;
        this.namespaceURI = namespaceURI;
        this.localName = localName;
        this.typeName = typeName;
        this.occurrence = occurrence;
    }
    
    /**
     * Creates an atomic type.
     */
    public static SequenceType atomic(String namespaceURI, String localName, Occurrence occurrence) {
        return new SequenceType(ItemKind.ATOMIC, namespaceURI, localName, null, occurrence);
    }
    
    /**
     * Creates an element type.
     */
    public static SequenceType element(String namespaceURI, String localName, Occurrence occurrence) {
        return new SequenceType(ItemKind.ELEMENT, namespaceURI, localName, null, occurrence);
    }
    
    /**
     * Creates an attribute type.
     */
    public static SequenceType attribute(String namespaceURI, String localName, Occurrence occurrence) {
        return new SequenceType(ItemKind.ATTRIBUTE, namespaceURI, localName, null, occurrence);
    }
    
    /**
     * Returns a copy with a different occurrence indicator.
     */
    public SequenceType withOccurrence(Occurrence occ) {
        return new SequenceType(itemKind, namespaceURI, localName, typeName, occ);
    }
    
    public ItemKind getItemKind() {
        return itemKind;
    }
    
    public String getNamespaceURI() {
        return namespaceURI;
    }
    
    public String getLocalName() {
        return localName;
    }
    
    public String getTypeName() {
        return typeName;
    }
    
    public Occurrence getOccurrence() {
        return occurrence;
    }
    
    /**
     * Returns true if this type allows empty sequences.
     */
    public boolean allowsEmpty() {
        return occurrence == Occurrence.ZERO_OR_ONE || 
               occurrence == Occurrence.ZERO_OR_MORE ||
               itemKind == ItemKind.EMPTY;
    }
    
    /**
     * Returns true if this type allows multiple items.
     */
    public boolean allowsMany() {
        return occurrence == Occurrence.ZERO_OR_MORE || 
               occurrence == Occurrence.ONE_OR_MORE;
    }
    
    /**
     * Checks if a value matches this sequence type.
     *
     * @param value the value to check
     * @return true if the value matches this type
     */
    public boolean matches(XPathValue value) {
        if (value == null) {
            return allowsEmpty();
        }
        
        // Get item count
        int count = getItemCount(value);
        
        // Check occurrence
        if (count == 0) {
            // Empty sequence matches if type allows empty (?, *)
            return allowsEmpty();
        }
        if (count > 1 && !allowsMany()) {
            return false;
        }
        
        // Empty sequence type only matches empty
        if (itemKind == ItemKind.EMPTY) {
            return count == 0;
        }
        
        // Check item types
        switch (itemKind) {
            case ITEM:
                return true;  // Any item matches
                
            case NODE:
                return value instanceof XPathNodeSet;
                
            case ATOMIC:
                return matchesAtomicType(value);
                
            case ELEMENT:
                return matchesElementType(value);
                
            case ATTRIBUTE:
                return matchesAttributeType(value);
                
            case TEXT:
            case COMMENT:
            case PROCESSING_INSTRUCTION:
            case DOCUMENT_NODE:
                return value instanceof XPathNodeSet;  // Simplified
                
            default:
                return true;
        }
    }
    
    /**
     * Checks if a value matches element(name?, type?).
     */
    private boolean matchesElementType(XPathValue value) {
        if (!(value instanceof XPathNodeSet)) {
            return false;
        }
        
        XPathNodeSet nodeSet = (XPathNodeSet) value;
        for (XPathNode node : nodeSet) {
            if (!matchesSingleElement(node)) {
                return false;
            }
        }
        return true;
    }
    
    /**
     * Checks if a single node matches element(name?, type?).
     */
    private boolean matchesSingleElement(XPathNode node) {
        if (node.getNodeType() != NodeType.ELEMENT) {
            return false;
        }
        
        // Check element name if specified (and not wildcard)
        if (localName != null && !"*".equals(localName)) {
            if (!localName.equals(node.getLocalName())) {
                return false;
            }
            // Also check namespace if localName is specified
            String nodeNs = node.getNamespaceURI();
            if (nodeNs == null) {
                nodeNs = "";
            }
            String typeNs = namespaceURI != null ? namespaceURI : "";
            if (!typeNs.equals(nodeNs)) {
                return false;
            }
        }
        
        // Check type annotation if specified (typeName field from element(*, type))
        if (typeName != null) {
            if (!matchesTypeAnnotation(node, typeName)) {
                return false;
            }
        }
        
        return true;
    }
    
    /**
     * Checks if a value matches attribute(name?, type?).
     */
    private boolean matchesAttributeType(XPathValue value) {
        if (!(value instanceof XPathNodeSet)) {
            return false;
        }
        
        XPathNodeSet nodeSet = (XPathNodeSet) value;
        for (XPathNode node : nodeSet) {
            if (!matchesSingleAttribute(node)) {
                return false;
            }
        }
        return true;
    }
    
    /**
     * Checks if a single node matches attribute(name?, type?).
     */
    private boolean matchesSingleAttribute(XPathNode node) {
        if (node.getNodeType() != NodeType.ATTRIBUTE) {
            return false;
        }
        
        // Check attribute name if specified (and not wildcard)
        if (localName != null && !"*".equals(localName)) {
            if (!localName.equals(node.getLocalName())) {
                return false;
            }
            // Also check namespace if localName is specified
            String nodeNs = node.getNamespaceURI();
            if (nodeNs == null) {
                nodeNs = "";
            }
            String typeNs = namespaceURI != null ? namespaceURI : "";
            if (!typeNs.equals(nodeNs)) {
                return false;
            }
        }
        
        // Check type annotation if specified (typeName field from attribute(*, type))
        if (typeName != null) {
            if (!matchesTypeAnnotation(node, typeName)) {
                return false;
            }
        }
        
        return true;
    }
    
    /**
     * Checks if a node's type annotation matches the expected type.
     * The type name can be prefixed (e.g., "xs:string") or in Clark notation.
     * Supports XSD type hierarchy: xs:ID matches xs:NCName, xs:Name, xs:token, xs:string.
     */
    private boolean matchesTypeAnnotation(XPathNode node, String expectedType) {
        String nodeTypeNs = node.getTypeNamespaceURI();
        String nodeTypeLocal = node.getTypeLocalName();
        
        // If node has no type annotation, it doesn't match a type test
        if (nodeTypeLocal == null) {
            return false;
        }
        
        // Parse the expected type (may be prefixed or Clark notation)
        String expectedNs = null;
        String expectedLocal = expectedType;
        
        if (expectedType.startsWith("{")) {
            // Clark notation: {uri}localName
            int closeIndex = expectedType.indexOf('}');
            if (closeIndex > 0) {
                expectedNs = expectedType.substring(1, closeIndex);
                expectedLocal = expectedType.substring(closeIndex + 1);
            }
        } else if (expectedType.contains(":")) {
            // Prefixed: prefix:localName - we stored the resolved namespace in namespaceURI
            int colonIndex = expectedType.indexOf(':');
            String prefix = expectedType.substring(0, colonIndex);
            expectedLocal = expectedType.substring(colonIndex + 1);
            // Assume XS namespace for xs: prefix
            if ("xs".equals(prefix) || "xsd".equals(prefix)) {
                expectedNs = XS_NAMESPACE;
            }
        } else {
            // Unprefixed - assume XS namespace
            expectedNs = XS_NAMESPACE;
        }
        
        // Normalize namespaces
        if (nodeTypeNs == null) {
            nodeTypeNs = "";
        }
        if (expectedNs == null) {
            expectedNs = "";
        }
        
        // Exact match
        if (expectedNs.equals(nodeTypeNs) && expectedLocal.equals(nodeTypeLocal)) {
            return true;
        }
        
        // Check type hierarchy for XSD built-in types
        if (XS_NAMESPACE.equals(nodeTypeNs) && XS_NAMESPACE.equals(expectedNs)) {
            return isTypeSubtypeOf(nodeTypeLocal, expectedLocal);
        }
        
        return false;
    }
    
    /**
     * XSD built-in type hierarchy.
     * Returns true if actualType is the same as or a subtype of expectedType.
     */
    private static boolean isTypeSubtypeOf(String actualType, String expectedType) {
        if (actualType.equals(expectedType)) {
            return true;
        }
        
        // anyAtomicType is the root of all atomic types
        if ("anyAtomicType".equals(expectedType) || "anySimpleType".equals(expectedType)) {
            return true;
        }
        
        // String type hierarchy: string > normalizedString > token > language/NMTOKEN/Name > NCName > ID/IDREF/ENTITY
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
        
        // Integer type hierarchy: integer > nonPositiveInteger/nonNegativeInteger > ...
        if ("integer".equals(expectedType)) {
            return isInIntegerHierarchy(actualType);
        }
        if ("decimal".equals(expectedType)) {
            // integer is a subtype of decimal
            return "integer".equals(actualType) || isInIntegerHierarchy(actualType);
        }
        
        // Duration type hierarchy
        if ("duration".equals(expectedType)) {
            return "yearMonthDuration".equals(actualType) || "dayTimeDuration".equals(actualType);
        }
        
        return false;
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
    
    /**
     * Gets the number of items in a value.
     */
    private int getItemCount(XPathValue value) {
        if (value instanceof XPathNodeSet) {
            return ((XPathNodeSet) value).size();
        }
        if (value instanceof XPathSequence) {
            return ((XPathSequence) value).size();
        }
        // Atomic values count as 1
        return 1;
    }
    
    private boolean matchesAtomicType(XPathValue value) {
        if (localName == null) {
            return true;
        }
        
        // Check common atomic types
        switch (localName) {
            case "string":
            case "normalizedString":
            case "token":
            case "language":
            case "NMTOKEN":
            case "Name":
            case "NCName":
            case "ID":
            case "IDREF":
            case "ENTITY":
            case "anyURI":
                return value instanceof XPathAnyURI || 
                       value instanceof XPathString || value instanceof XPathAtomicValue;
                
            case "integer":
            case "int":
            case "long":
            case "short":
            case "byte":
            case "nonNegativeInteger":
            case "nonPositiveInteger":
            case "positiveInteger":
            case "negativeInteger":
            case "unsignedInt":
            case "unsignedLong":
            case "unsignedShort":
            case "unsignedByte":
            case "decimal":
            case "float":
            case "double":
                return value instanceof XPathNumber ||
                       (value instanceof XPathAtomicValue && ((XPathAtomicValue) value).isNumericValue());
                
            case "boolean":
                return value instanceof XPathBoolean;
                
            case "date":
                return value instanceof XPathDateTime && 
                       ((XPathDateTime) value).getDateTimeType() == XPathDateTime.DateTimeType.DATE;
                
            case "dateTime":
                return value instanceof XPathDateTime && 
                       ((XPathDateTime) value).getDateTimeType() == XPathDateTime.DateTimeType.DATE_TIME;
                
            case "time":
                return value instanceof XPathDateTime && 
                       ((XPathDateTime) value).getDateTimeType() == XPathDateTime.DateTimeType.TIME;
                
            case "duration":
                return value instanceof XPathDateTime && 
                       (((XPathDateTime) value).getDateTimeType() == XPathDateTime.DateTimeType.DURATION ||
                        ((XPathDateTime) value).getDateTimeType() == XPathDateTime.DateTimeType.YEAR_MONTH_DURATION ||
                        ((XPathDateTime) value).getDateTimeType() == XPathDateTime.DateTimeType.DAY_TIME_DURATION);
                
            case "yearMonthDuration":
                return value instanceof XPathDateTime && 
                       ((XPathDateTime) value).getDateTimeType() == XPathDateTime.DateTimeType.YEAR_MONTH_DURATION;
                
            case "dayTimeDuration":
                return value instanceof XPathDateTime && 
                       ((XPathDateTime) value).getDateTimeType() == XPathDateTime.DateTimeType.DAY_TIME_DURATION;
                
            case "gYear":
                return value instanceof XPathDateTime && 
                       ((XPathDateTime) value).getDateTimeType() == XPathDateTime.DateTimeType.G_YEAR;
            case "gMonth":
                return value instanceof XPathDateTime && 
                       ((XPathDateTime) value).getDateTimeType() == XPathDateTime.DateTimeType.G_MONTH;
            case "gDay":
                return value instanceof XPathDateTime && 
                       ((XPathDateTime) value).getDateTimeType() == XPathDateTime.DateTimeType.G_DAY;
            case "gYearMonth":
                return value instanceof XPathDateTime && 
                       ((XPathDateTime) value).getDateTimeType() == XPathDateTime.DateTimeType.G_YEAR_MONTH;
            case "gMonthDay":
                return value instanceof XPathDateTime && 
                       ((XPathDateTime) value).getDateTimeType() == XPathDateTime.DateTimeType.G_MONTH_DAY;
                
            case "hexBinary":
            case "base64Binary":
                // Binary types - match strings for now
                return value instanceof XPathString || value instanceof XPathAtomicValue;
                
            case "QName":
            case "NOTATION":
                return value instanceof XPathString || value instanceof XPathAtomicValue;
                
            case "untypedAtomic":
            case "anyAtomicType":
                // These match any atomic value
                return value instanceof XPathAtomicValue || 
                       value instanceof XPathString ||
                       value instanceof XPathNumber ||
                       value instanceof XPathBoolean ||
                       value instanceof XPathDateTime;
                
            default:
                // For unknown types, be strict - only match XPathAtomicValue or matching DateTime
                // This prevents incorrect matches like numbers matching xs:date
                return value instanceof XPathAtomicValue;
        }
    }
    
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        
        switch (itemKind) {
            case EMPTY:
                return "empty-sequence()";
            case ITEM:
                sb.append("item()");
                break;
            case NODE:
                sb.append("node()");
                break;
            case ELEMENT:
                sb.append("element(");
                if (localName != null) {
                    sb.append(localName);
                }
                sb.append(")");
                break;
            case ATTRIBUTE:
                sb.append("attribute(");
                if (localName != null) {
                    sb.append(localName);
                }
                sb.append(")");
                break;
            case TEXT:
                sb.append("text()");
                break;
            case COMMENT:
                sb.append("comment()");
                break;
            case PROCESSING_INSTRUCTION:
                sb.append("processing-instruction()");
                break;
            case DOCUMENT_NODE:
                sb.append("document-node()");
                break;
            case SCHEMA_ELEMENT:
                sb.append("schema-element(").append(localName).append(")");
                break;
            case SCHEMA_ATTRIBUTE:
                sb.append("schema-attribute(").append(localName).append(")");
                break;
            case ATOMIC:
                if (namespaceURI != null && namespaceURI.equals(XS_NAMESPACE)) {
                    sb.append("xs:").append(localName);
                } else if (namespaceURI != null) {
                    sb.append("{").append(namespaceURI).append("}").append(localName);
                } else {
                    sb.append(localName);
                }
                break;
        }
        
        switch (occurrence) {
            case ZERO_OR_ONE:
                sb.append("?");
                break;
            case ZERO_OR_MORE:
                sb.append("*");
                break;
            case ONE_OR_MORE:
                sb.append("+");
                break;
            default:
                // ONE - no indicator
                break;
        }
        
        return sb.toString();
    }
}

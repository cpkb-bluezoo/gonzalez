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

import org.bluezoo.gonzalez.QName;
import org.bluezoo.gonzalez.schema.xsd.XSDSchema;
import org.bluezoo.gonzalez.schema.xsd.XSDSimpleType;

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
        /** Namespace node: namespace-node() */
        NAMESPACE_NODE,
        /** Schema element: schema-element(name) */
        SCHEMA_ELEMENT,
        /** Schema attribute: schema-attribute(name) */
        SCHEMA_ATTRIBUTE,
        /** Atomic type (xs:integer, xs:string, etc.) */
        ATOMIC,
        /** Empty sequence */
        EMPTY,
        /** XPath 3.1 map type: map(*) or map(K, V) */
        MAP,
        /** XPath 3.1 array type: array(*) or array(T) */
        ARRAY
    }
    
    private final ItemKind itemKind;
    private final String namespaceURI;  // For atomic types or named element/attribute
    private final String localName;      // For atomic types or named element/attribute
    private final QName typeName;        // Resolved type for element(*, type) or attribute(*, type)
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
     *
     * @param itemKind the kind of item (ITEM, NODE, ELEMENT, ATOMIC, etc.)
     * @param namespaceURI the namespace URI for atomic types or named element/attribute (may be null)
     * @param localName the local name for atomic types or named element/attribute (may be null)
     * @param typeName the resolved type for element(*, type) or attribute(*, type) (may be null)
     * @param occurrence the occurrence indicator (ONE, ZERO_OR_ONE, ZERO_OR_MORE, ONE_OR_MORE)
     */
    public SequenceType(ItemKind itemKind, String namespaceURI, String localName, 
                        QName typeName, Occurrence occurrence) {
        this.itemKind = itemKind;
        this.namespaceURI = namespaceURI;
        this.localName = localName;
        this.typeName = typeName;
        this.occurrence = occurrence;
    }
    
    /**
     * Creates an atomic type sequence type.
     *
     * @param namespaceURI the namespace URI of the atomic type (e.g., XS_NAMESPACE for xs:string)
     * @param localName the local name of the atomic type (e.g., "string", "integer")
     * @param occurrence the occurrence indicator
     * @return a sequence type for the atomic type
     */
    public static SequenceType atomic(String namespaceURI, String localName, Occurrence occurrence) {
        return new SequenceType(ItemKind.ATOMIC, namespaceURI, localName, null, occurrence);
    }
    
    /**
     * Creates an element type sequence type.
     *
     * @param namespaceURI the namespace URI of the element (may be null for any namespace)
     * @param localName the local name of the element (may be null for any element)
     * @param occurrence the occurrence indicator
     * @return a sequence type for the element type
     */
    public static SequenceType element(String namespaceURI, String localName, Occurrence occurrence) {
        return new SequenceType(ItemKind.ELEMENT, namespaceURI, localName, null, occurrence);
    }
    
    /**
     * Creates an attribute type sequence type.
     *
     * @param namespaceURI the namespace URI of the attribute (may be null for any namespace)
     * @param localName the local name of the attribute (may be null for any attribute)
     * @param occurrence the occurrence indicator
     * @return a sequence type for the attribute type
     */
    public static SequenceType attribute(String namespaceURI, String localName, Occurrence occurrence) {
        return new SequenceType(ItemKind.ATTRIBUTE, namespaceURI, localName, null, occurrence);
    }
    
    /**
     * Creates a schema-element type sequence type.
     *
     * <p>The schema-element() test matches elements by schema declaration name,
     * including all elements in the substitution group of the named element.
     *
     * <p>Example: {@code schema-element(book)} matches {@code <book>} and any
     * element that has {@code substitutionGroup="book"}.
     *
     * @param namespaceURI the namespace URI of the schema element
     * @param localName the local name of the schema element declaration
     * @param occurrence the occurrence indicator
     * @return a sequence type for the schema-element type
     */
    public static SequenceType schemaElement(String namespaceURI, String localName, Occurrence occurrence) {
        return new SequenceType(ItemKind.SCHEMA_ELEMENT, namespaceURI, localName, null, occurrence);
    }
    
    /**
     * Creates a schema-attribute type sequence type.
     *
     * <p>The schema-attribute() test matches attributes by schema declaration name,
     * including type information from the schema.
     *
     * @param namespaceURI the namespace URI of the schema attribute
     * @param localName the local name of the schema attribute declaration
     * @param occurrence the occurrence indicator
     * @return a sequence type for the schema-attribute type
     */
    public static SequenceType schemaAttribute(String namespaceURI, String localName, Occurrence occurrence) {
        return new SequenceType(ItemKind.SCHEMA_ATTRIBUTE, namespaceURI, localName, null, occurrence);
    }
    
    /**
     * Strips XPath comments (: ... :) from a string.
     * XPath comments can appear in 'as' attribute values and should be ignored.
     * 
     * @param str the string to process
     * @return the string with XPath comments removed
     */
    private static String stripXPathComments(String str) {
        if (str == null || !str.contains("(:")) {
            return str;
        }
        
        StringBuilder result = new StringBuilder();
        int i = 0;
        while (i < str.length()) {
            // Check for comment start
            if (i < str.length() - 1 && str.charAt(i) == '(' && str.charAt(i + 1) == ':') {
                // Find comment end
                int commentEnd = str.indexOf(":)", i + 2);
                if (commentEnd >= 0) {
                    // Skip the entire comment including (:  and  :)
                    i = commentEnd + 2;
                    continue;
                } else {
                    // Unclosed comment - just copy the rest
                    result.append(str.substring(i));
                    break;
                }
            }
            result.append(str.charAt(i));
            i++;
        }
        
        return result.toString().trim();
    }
    
    /**
     * Returns a copy of this sequence type with a different occurrence indicator.
     *
     * @param occ the new occurrence indicator
     * @return a new sequence type with the same item kind but different occurrence
     */
    public SequenceType withOccurrence(Occurrence occ) {
        return new SequenceType(itemKind, namespaceURI, localName, typeName, occ);
    }
    
    /**
     * Returns the item kind of this sequence type.
     *
     * @return the item kind (ITEM, NODE, ELEMENT, ATOMIC, etc.)
     */
    public ItemKind getItemKind() {
        return itemKind;
    }
    
    /**
     * Returns the namespace URI for atomic types or named element/attribute.
     *
     * @return the namespace URI, or null if not applicable
     */
    public String getNamespaceURI() {
        return namespaceURI;
    }
    
    /**
     * Returns the local name for atomic types or named element/attribute.
     *
     * @return the local name, or null if not applicable
     */
    public String getLocalName() {
        return localName;
    }
    
    /**
     * Returns the resolved type for element(*, type) or attribute(*, type).
     *
     * @return the type as a QName, or null if not applicable
     */
    public QName getTypeName() {
        return typeName;
    }
    
    /**
     * Returns the occurrence indicator.
     *
     * @return the occurrence (ONE, ZERO_OR_ONE, ZERO_OR_MORE, ONE_OR_MORE)
     */
    public Occurrence getOccurrence() {
        return occurrence;
    }
    
    /**
     * Returns true if this type allows empty sequences.
     *
     * <p>Types with occurrence indicators ? (ZERO_OR_ONE) or * (ZERO_OR_MORE)
     * allow empty sequences, as does the empty-sequence() type.
     *
     * @return true if empty sequences are allowed
     */
    public boolean allowsEmpty() {
        return occurrence == Occurrence.ZERO_OR_ONE || 
               occurrence == Occurrence.ZERO_OR_MORE ||
               itemKind == ItemKind.EMPTY;
    }
    
    /**
     * Returns true if this type allows multiple items.
     *
     * <p>Types with occurrence indicators * (ZERO_OR_MORE) or + (ONE_OR_MORE)
     * allow multiple items.
     *
     * @return true if multiple items are allowed
     */
    public boolean allowsMany() {
        return occurrence == Occurrence.ZERO_OR_MORE || 
               occurrence == Occurrence.ONE_OR_MORE;
    }
    
    /**
     * Checks if a value matches this sequence type.
     *
     * <p>This performs type checking according to XPath 2.0/3.1 rules:
     * <ul>
     *   <li>Checks the number of items matches the occurrence indicator</li>
     *   <li>Checks each item matches the item type</li>
     *   <li>For atomic types, checks the value is of the correct type</li>
     *   <li>For element/attribute types, checks name and optionally type annotation</li>
     * </ul>
     *
     * @param value the value to check (may be null, treated as empty sequence)
     * @return true if the value matches this sequence type
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
                // Check for node-set, result tree fragments, and sequences containing nodes
                if (value instanceof XPathNodeSet) {
                    return true;
                }
                if (value instanceof XPathResultTreeFragment) {
                    return true;  // Result tree fragments are nodes
                }
                if (value instanceof XPathSequence) {
                    // Check if sequence contains only nodes
                    XPathSequence seq = (XPathSequence) value;
                    for (XPathValue item : seq) {
                        if (!(item instanceof XPathNode) && !(item instanceof XPathNodeSet) && !(item instanceof XPathResultTreeFragment)) {
                            return false;
                        }
                    }
                    return true;
                }
                return false;
                
            case ATOMIC:
                return matchesAtomicType(value);
                
            case ELEMENT:
                return matchesElementType(value);
                
            case ATTRIBUTE:
                return matchesAttributeType(value);
                
            case SCHEMA_ELEMENT:
                return matchesSchemaElementType(value, SchemaContext.NONE);
                
            case SCHEMA_ATTRIBUTE:
                return matchesSchemaAttributeType(value);
                
            case TEXT:
            case COMMENT:
            case PROCESSING_INSTRUCTION:
            case DOCUMENT_NODE:
                return value instanceof XPathNodeSet;  // Simplified

            case MAP:
                return value instanceof XPathMap;

            case ARRAY:
                return value instanceof XPathSequence;

            default:
                return true;
        }
    }
    
    /**
     * Checks if a value matches this sequence type with schema context for substitution groups.
     *
     * <p>This overload enables full substitution group checking for schema-element()
     * when a schema context is available.
     *
     * @param value the value to check
     * @param schemaContext schema context for substitution group checking, or null
     * @return true if the value matches this sequence type
     */
    public boolean matches(XPathValue value, SchemaContext schemaContext) {
        // Delegate to matchesWithSchema for schema-aware checking
        return matchesWithSchema(value, schemaContext);
    }
    
    /**
     * Internal method that performs matching with optional schema context.
     */
    private boolean matchesWithSchema(XPathValue value, SchemaContext schemaContext) {
        if (value == null) {
            return allowsEmpty();
        }
        
        // Get item count
        int count = getItemCount(value);
        
        // Check occurrence
        if (count == 0) {
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
                return true;
                
            case NODE:
                // Check for node-set, result tree fragments, and sequences containing nodes
                if (value instanceof XPathNodeSet) {
                    return true;
                }
                if (value instanceof XPathResultTreeFragment) {
                    return true;  // Result tree fragments are nodes
                }
                if (value instanceof XPathSequence) {
                    // Check if sequence contains only nodes
                    XPathSequence seq = (XPathSequence) value;
                    for (XPathValue item : seq) {
                        if (!(item instanceof XPathNode) && !(item instanceof XPathNodeSet) && !(item instanceof XPathResultTreeFragment)) {
                            return false;
                        }
                    }
                    return true;
                }
                return false;
                
            case ATOMIC:
                return matchesAtomicType(value);
                
            case ELEMENT:
                return matchesElementType(value);
                
            case ATTRIBUTE:
                return matchesAttributeType(value);
                
            case SCHEMA_ELEMENT:
                return matchesSchemaElementType(value, schemaContext);
                
            case SCHEMA_ATTRIBUTE:
                return matchesSchemaAttributeType(value);
                
            case TEXT:
            case COMMENT:
            case PROCESSING_INSTRUCTION:
            case DOCUMENT_NODE:
                return value instanceof XPathNodeSet;

            case MAP:
                return value instanceof XPathMap;

            case ARRAY:
                return value instanceof XPathSequence;

            default:
                return true;
        }
    }
    
    /**
     * Checks if a value matches element(name?, type?).
     */
    private boolean matchesElementType(XPathValue value) {
        // Handle sequences - check if all items are elements
        if (value instanceof XPathSequence) {
            XPathSequence seq = (XPathSequence) value;
            for (XPathValue item : seq) {
                if (item instanceof XPathNodeSet) {
                    XPathNodeSet nodeSet = (XPathNodeSet) item;
                    for (XPathNode node : nodeSet) {
                        if (!matchesSingleElement(node)) {
                            return false;
                        }
                    }
                } else if (item instanceof XPathNode) {
                    // Direct node item (e.g., SequenceTextItem, SequenceAttributeItem)
                    if (!matchesSingleElement((XPathNode) item)) {
                        return false;
                    }
                } else if (item instanceof XPathResultTreeFragment) {
                    // Result tree fragment - convert to node-set and check
                    XPathResultTreeFragment rtf = (XPathResultTreeFragment) item;
                    XPathNodeSet nodeSet = rtf.asNodeSet();
                    if (nodeSet == null || nodeSet.isEmpty()) {
                        return false;
                    }
                    for (XPathNode node : nodeSet) {
                        if (!matchesSingleElement(node)) {
                            return false;
                        }
                    }
                } else {
                    return false;  // Non-node item in sequence
                }
            }
            return true;
        }
        
        // Handle result tree fragment
        if (value instanceof XPathResultTreeFragment) {
            XPathResultTreeFragment rtf = (XPathResultTreeFragment) value;
            XPathNodeSet nodeSet = rtf.asNodeSet();
            if (nodeSet == null || nodeSet.isEmpty()) {
                return false;
            }
            for (XPathNode node : nodeSet) {
                if (!matchesSingleElement(node)) {
                    return false;
                }
            }
            return true;
        }
        
        // Handle direct XPathNode item (e.g., single element from variable)
        if (value instanceof XPathNode) {
            return matchesSingleElement((XPathNode) value);
        }
        
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
        // Handle sequences - check if all items are attributes
        if (value instanceof XPathSequence) {
            XPathSequence seq = (XPathSequence) value;
            for (XPathValue item : seq) {
                if (item instanceof XPathNodeSet) {
                    XPathNodeSet nodeSet = (XPathNodeSet) item;
                    for (XPathNode node : nodeSet) {
                        if (!matchesSingleAttribute(node)) {
                            return false;
                        }
                    }
                } else if (item instanceof XPathNode) {
                    // Direct node item (e.g., SequenceAttributeItem)
                    if (!matchesSingleAttribute((XPathNode) item)) {
                        return false;
                    }
                } else {
                    return false;  // Non-node item in sequence
                }
            }
            return true;
        }
        
        // Handle direct XPathNode item (e.g., SequenceAttributeItem from variable with single attribute)
        if (value instanceof XPathNode) {
            return matchesSingleAttribute((XPathNode) value);
        }
        
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
     * Checks if a value matches schema-element(name).
     *
     * <p>The schema-element() test matches:
     * <ul>
     *   <li>Elements with the given name</li>
     *   <li>Elements in the substitution group of the named element</li>
     * </ul>
     *
     * @param schemaContext schema context for substitution group lookup, or null
     */
    private boolean matchesSchemaElementType(XPathValue value, SchemaContext schemaContext) {
        // Handle sequences - check if all items are matching elements
        if (value instanceof XPathSequence) {
            XPathSequence seq = (XPathSequence) value;
            for (XPathValue item : seq) {
                if (item instanceof XPathNodeSet) {
                    XPathNodeSet nodeSet = (XPathNodeSet) item;
                    for (XPathNode node : nodeSet) {
                        if (!matchesSingleSchemaElement(node, schemaContext)) {
                            return false;
                        }
                    }
                } else if (item instanceof XPathNode) {
                    if (!matchesSingleSchemaElement((XPathNode) item, schemaContext)) {
                        return false;
                    }
                } else {
                    return false;  // Non-node item
                }
            }
            return true;
        }
        
        if (!(value instanceof XPathNodeSet)) {
            return false;
        }
        
        XPathNodeSet nodeSet = (XPathNodeSet) value;
        for (XPathNode node : nodeSet) {
            if (!matchesSingleSchemaElement(node, schemaContext)) {
                return false;
            }
        }
        return true;
    }
    
    /**
     * Checks if a single node matches schema-element(name).
     * @param schemaContext schema context for substitution group lookup, or null
     */
    private boolean matchesSingleSchemaElement(XPathNode node, SchemaContext schemaContext) {
        if (node.getNodeType() != NodeType.ELEMENT) {
            return false;
        }
        
        // Check element name if specified
        if (localName != null && !"*".equals(localName)) {
            String nodeLocalName = node.getLocalName();
            String nodeNs = node.getNamespaceURI();
            if (nodeNs == null) {
                nodeNs = "";
            }
            String expectedNs = namespaceURI != null ? namespaceURI : "";
            
            // First check exact name match
            if (localName.equals(nodeLocalName) && expectedNs.equals(nodeNs)) {
                return true;  // Direct match
            }
            
            // Check substitution group if schema context is available
            if (schemaContext != null) {
                XSDSchema schema = schemaContext.getSchema(expectedNs);
                if (schema != null) {
                    // Get all members of the substitution group (including head element)
                    java.util.List<org.bluezoo.gonzalez.schema.xsd.XSDElement> members = 
                        schema.getSubstitutionGroupMembers(localName);
                    
                    // Check if the node's name matches any substitution group member
                    for (org.bluezoo.gonzalez.schema.xsd.XSDElement member : members) {
                        if (nodeLocalName.equals(member.getName())) {
                            // Also verify namespace matches
                            String memberNs = member.getNamespaceURI();
                            if (memberNs == null) {
                                memberNs = "";
                            }
                            if (nodeNs.equals(memberNs)) {
                                return true;  // Substitution group member match
                            }
                        }
                    }
                }
            }
            
            // No match found
            return false;
        }
        
        return true;
    }
    
    /**
     * Checks if a value matches schema-attribute(name).
     *
     * <p>The schema-attribute() test matches attributes by their schema declaration,
     * including type information from the schema.
     */
    private boolean matchesSchemaAttributeType(XPathValue value) {
        // For now, schema-attribute(name) matches like attribute(name)
        // Full implementation would look up the attribute declaration and check types
        
        // Handle sequences
        if (value instanceof XPathSequence) {
            XPathSequence seq = (XPathSequence) value;
            for (XPathValue item : seq) {
                if (item instanceof XPathNodeSet) {
                    XPathNodeSet nodeSet = (XPathNodeSet) item;
                    for (XPathNode node : nodeSet) {
                        if (!matchesSingleSchemaAttribute(node)) {
                            return false;
                        }
                    }
                } else if (item instanceof XPathNode) {
                    if (!matchesSingleSchemaAttribute((XPathNode) item)) {
                        return false;
                    }
                } else {
                    return false;
                }
            }
            return true;
        }
        
        if (!(value instanceof XPathNodeSet)) {
            return false;
        }
        
        XPathNodeSet nodeSet = (XPathNodeSet) value;
        for (XPathNode node : nodeSet) {
            if (!matchesSingleSchemaAttribute(node)) {
                return false;
            }
        }
        return true;
    }
    
    /**
     * Checks if a single node matches schema-attribute(name).
     */
    private boolean matchesSingleSchemaAttribute(XPathNode node) {
        if (node.getNodeType() != NodeType.ATTRIBUTE) {
            return false;
        }
        
        // Check attribute name if specified
        if (localName != null && !"*".equals(localName)) {
            if (!localName.equals(node.getLocalName())) {
                return false;
            }
            
            // Check namespace
            String nodeNs = node.getNamespaceURI();
            if (nodeNs == null) {
                nodeNs = "";
            }
            String expectedNs = namespaceURI != null ? namespaceURI : "";
            if (!expectedNs.equals(nodeNs)) {
                return false;
            }
        }
        
        // TODO: Check schema type annotation
        // Would need: schema.getAttribute(localName).getType()
        
        return true;
    }
    
    /**
     * Checks if a node's type annotation matches the expected type.
     * Supports XSD type hierarchy: xs:ID matches xs:NCName, xs:Name, xs:token, xs:string.
     *
     * @param node the node to check
     * @param expectedType the resolved type as a QName
     */
    private boolean matchesTypeAnnotation(XPathNode node, QName expectedType) {
        String expectedNs = expectedType.getURI();
        String expectedLocal = expectedType.getLocalName();

        // xs:untyped / xs:untypedAtomic match nodes WITHOUT type annotations
        boolean expectsUntyped = XS_NAMESPACE.equals(expectedNs) &&
            ("untyped".equals(expectedLocal) || "untypedAtomic".equals(expectedLocal));

        String nodeTypeLocal = node.getTypeLocalName();
        if (nodeTypeLocal == null) {
            return expectsUntyped;
        }

        String nodeTypeNs = node.getTypeNamespaceURI();
        if (nodeTypeNs == null) {
            nodeTypeNs = "";
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
     * 
     * <p>This method now delegates to XSDSimpleType for comprehensive type hierarchy checking,
     * with a fallback to hardcoded checks for backwards compatibility.
     */
    private static boolean isTypeSubtypeOf(String actualType, String expectedType) {
        if (actualType.equals(expectedType)) {
            return true;
        }
        
        // Try using XSDSimpleType for comprehensive hierarchy checking
        XSDSimpleType actual = XSDSimpleType.getBuiltInType(actualType);
        XSDSimpleType expected = XSDSimpleType.getBuiltInType(expectedType);
        
        if (actual != null && expected != null) {
            return actual.isSubtypeOf(expected);
        }
        
        // Fallback: hardcoded type hierarchy checks
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
        
        // Handle sequences - check each item matches
        if (value instanceof XPathSequence) {
            XPathSequence seq = (XPathSequence) value;
            for (XPathValue item : seq) {
                if (!matchesSingleAtomicItem(item)) {
                    return false;
                }
            }
            return true;
        }
        
        return matchesSingleAtomicItem(value);
    }
    
    private boolean matchesSingleAtomicItem(XPathValue value) {
        if (localName == null) {
            return true;
        }
        
        // Check if value is a typed atomic from data() function
        if (value instanceof XPathTypedAtomic) {
            XPathTypedAtomic typed = (XPathTypedAtomic) value;
            String expectedNs = namespaceURI != null ? namespaceURI : XS_NAMESPACE;
            return typed.isInstanceOf(expectedNs, localName);
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
                // For numeric types, check if the value is numeric and matches the type
                if (value instanceof XPathNumber) {
                    // Use XPathNumber's type checking method
                    return ((XPathNumber) value).isInstanceOfNumericType(localName);
                }
                return value instanceof XPathAtomicValue && ((XPathAtomicValue) value).isNumericValue();
                
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
                return value instanceof XPathQName ||
                       value instanceof XPathString || value instanceof XPathAtomicValue;
                
            case "untypedAtomic":
            case "anyAtomicType":
                // These match any atomic value
                return value instanceof XPathAtomicValue || 
                       value instanceof XPathTypedAtomic ||
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
    
    /**
     * Parses a sequence type from an XSLT 'as' attribute value.
     *
     * <p>Supported formats include:
     * <ul>
     *   <li>Atomic types: xs:integer, xs:string, xs:boolean, etc.</li>
     *   <li>Node types: node(), element(), attribute(), text(), comment(), processing-instruction(), document-node()</li>
     *   <li>Named element/attribute: element(name), attribute(name)</li>
     *   <li>Schema types: schema-element(name), schema-attribute(name)</li>
     *   <li>Empty sequence: empty-sequence()</li>
     *   <li>Any item: item()</li>
     *   <li>Occurrence indicators: ?, *, + (with optional space before)</li>
     * </ul>
     *
     * @param asType the 'as' attribute value to parse
     * @param namespaceResolver a function to resolve namespace prefixes (may be null for built-in types)
     * @return the parsed SequenceType, or null if the format is not recognized
     */
    public static SequenceType parse(String asType, java.util.function.Function<String, String> namespaceResolver) {
        if (asType == null || asType.isEmpty()) {
            return null;
        }
        
        String type = asType.trim();
        
        // Strip XPath comments (: ... :) from the type string
        // This is valid in XPath expressions but should be ignored in SequenceType parsing
        type = stripXPathComments(type);
        
        // Parse occurrence indicator (at end, with optional space)
        Occurrence occ = Occurrence.ONE;
        if (type.endsWith("?")) {
            occ = Occurrence.ZERO_OR_ONE;
            type = type.substring(0, type.length() - 1).trim();
        } else if (type.endsWith("*")) {
            occ = Occurrence.ZERO_OR_MORE;
            type = type.substring(0, type.length() - 1).trim();
        } else if (type.endsWith("+")) {
            occ = Occurrence.ONE_OR_MORE;
            type = type.substring(0, type.length() - 1).trim();
        }
        
        // Parse empty-sequence()
        if (type.equals("empty-sequence()")) {
            return EMPTY;
        }
        
        // Parse item()
        if (type.equals("item()")) {
            return new SequenceType(ItemKind.ITEM, null, null, null, occ);
        }
        
        // Parse node types: node(), element(), attribute(), text(), comment(), processing-instruction(), document-node()
        if (type.equals("node()")) {
            return new SequenceType(ItemKind.NODE, null, null, null, occ);
        }
        if (type.equals("text()")) {
            return new SequenceType(ItemKind.TEXT, null, null, null, occ);
        }
        if (type.equals("comment()")) {
            return new SequenceType(ItemKind.COMMENT, null, null, null, occ);
        }
        if (type.equals("processing-instruction()") || type.startsWith("processing-instruction(")) {
            String piName = null;
            if (type.startsWith("processing-instruction(") && type.endsWith(")")) {
                String inner = type.substring("processing-instruction(".length(), type.length() - 1).trim();
                if (!inner.isEmpty()) {
                    piName = inner;
                }
            }
            return new SequenceType(ItemKind.PROCESSING_INSTRUCTION, null, piName, null, occ);
        }
        if (type.equals("document-node()") || type.startsWith("document-node(")) {
            // Parse document-node() or document-node(element(...))
            if (type.startsWith("document-node(") && type.endsWith(")") && !type.equals("document-node()")) {
                String inner = type.substring("document-node(".length(), type.length() - 1).trim();
                // Check if it contains element(...) test
                if (inner.startsWith("element(")) {
                    // Parse the nested element test
                    // For now, we accept it but don't fully parse the nested type
                    // This allows document-node(element(name, type)) to compile
                    // Full matching would require recursive parsing
                }
            }
            return new SequenceType(ItemKind.DOCUMENT_NODE, null, null, null, occ);
        }
        
        // Parse element() or element(name) or element(name, type) or element(*, type)
        if (type.equals("element()") || type.startsWith("element(")) {
            String localName = null;
            String nsUri = null;
            QName typeName = null;
            if (type.startsWith("element(") && type.endsWith(")")) {
                String inner = type.substring("element(".length(), type.length() - 1).trim();
                if (!inner.isEmpty() && !inner.equals("*")) {
                    int commaIdx = inner.indexOf(',');
                    if (commaIdx >= 0) {
                        String namePart = inner.substring(0, commaIdx).trim();
                        String typeStr = inner.substring(commaIdx + 1).trim();
                        typeName = resolveQName(typeStr, namespaceResolver);
                        if (!namePart.equals("*")) {
                            String[] resolved = resolvePrefixedName(namePart, namespaceResolver);
                            nsUri = resolved[0];
                            localName = resolved[1];
                        }
                    } else {
                        String[] resolved = resolvePrefixedName(inner, namespaceResolver);
                        nsUri = resolved[0];
                        localName = resolved[1];
                    }
                }
            }
            return new SequenceType(ItemKind.ELEMENT, nsUri, localName, typeName, occ);
        }
        
        // Parse attribute() or attribute(name) or attribute(name, type) or attribute(*, type)
        if (type.equals("attribute()") || type.startsWith("attribute(")) {
            String localName = null;
            String nsUri = null;
            QName typeName = null;
            if (type.startsWith("attribute(") && type.endsWith(")")) {
                String inner = type.substring("attribute(".length(), type.length() - 1).trim();
                if (!inner.isEmpty() && !inner.equals("*")) {
                    int commaIdx = inner.indexOf(',');
                    if (commaIdx >= 0) {
                        String namePart = inner.substring(0, commaIdx).trim();
                        String typeStr = inner.substring(commaIdx + 1).trim();
                        typeName = resolveQName(typeStr, namespaceResolver);
                        if (!namePart.equals("*")) {
                            String[] resolved = resolvePrefixedName(namePart, namespaceResolver);
                            nsUri = resolved[0];
                            localName = resolved[1];
                        }
                    } else {
                        String[] resolved = resolvePrefixedName(inner, namespaceResolver);
                        nsUri = resolved[0];
                        localName = resolved[1];
                    }
                }
            }
            return new SequenceType(ItemKind.ATTRIBUTE, nsUri, localName, typeName, occ);
        }
        
        // Parse schema-element(name)
        if (type.startsWith("schema-element(") && type.endsWith(")")) {
            String name = type.substring("schema-element(".length(), type.length() - 1).trim();
            return new SequenceType(ItemKind.SCHEMA_ELEMENT, null, name, null, occ);
        }
        
        // Parse schema-attribute(name)
        if (type.startsWith("schema-attribute(") && type.endsWith(")")) {
            String name = type.substring("schema-attribute(".length(), type.length() - 1).trim();
            return new SequenceType(ItemKind.SCHEMA_ATTRIBUTE, null, name, null, occ);
        }

        // Parse map(*) or map(K, V)
        if (type.equals("map(*)") || type.startsWith("map(")) {
            return new SequenceType(ItemKind.MAP, null, null, null, occ);
        }

        // Parse array(*) or array(T)
        if (type.equals("array(*)") || type.startsWith("array(")) {
            return new SequenceType(ItemKind.ARRAY, null, null, null, occ);
        }
        
        // Parse atomic type (e.g., xs:integer, xs:string)
        String[] resolved = resolvePrefixedName(type, namespaceResolver);
        String nsUri = resolved[0];
        String localName = resolved[1];

        if (nsUri == null && !type.contains(":")) {
            // No prefix - assume xs: namespace for common types
            if (isBuiltInAtomicType(type)) {
                nsUri = XS_NAMESPACE;
            }
        }

        return new SequenceType(ItemKind.ATOMIC, nsUri, localName, null, occ);
    }
    
    /**
     * Resolves a prefixed name (prefix:local) to [namespaceURI, localName]
     * using the provided namespace resolver.
     */
    private static String[] resolvePrefixedName(String name,
            java.util.function.Function<String, String> namespaceResolver) {
        int colonIdx = name.indexOf(':');
        if (colonIdx <= 0) {
            return new String[] { null, name };
        }
        String prefix = name.substring(0, colonIdx);
        String local = name.substring(colonIdx + 1);
        String uri = null;
        if (namespaceResolver != null) {
            uri = namespaceResolver.apply(prefix);
        }
        if (uri == null) {
            if ("xs".equals(prefix) || "xsd".equals(prefix)) {
                uri = XS_NAMESPACE;
            }
        }
        return new String[] { uri, local };
    }

    /**
     * Resolves a prefixed type name to a QName with namespace URI and local name.
     */
    private static QName resolveQName(String name,
            java.util.function.Function<String, String> namespaceResolver) {
        String[] resolved = resolvePrefixedName(name, namespaceResolver);
        String uri = resolved[0] != null ? resolved[0] : "";
        return new QName(uri, resolved[1], name);
    }

    /**
     * Checks if a type name is a built-in XSD atomic type.
     */
    private static boolean isBuiltInAtomicType(String name) {
        switch (name) {
            case "string":
            case "boolean":
            case "decimal":
            case "float":
            case "double":
            case "duration":
            case "dateTime":
            case "time":
            case "date":
            case "gYearMonth":
            case "gYear":
            case "gMonthDay":
            case "gDay":
            case "gMonth":
            case "hexBinary":
            case "base64Binary":
            case "anyURI":
            case "QName":
            case "NOTATION":
            case "normalizedString":
            case "token":
            case "language":
            case "NMTOKEN":
            case "NMTOKENS":
            case "Name":
            case "NCName":
            case "ID":
            case "IDREF":
            case "IDREFS":
            case "ENTITY":
            case "ENTITIES":
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
            case "yearMonthDuration":
            case "dayTimeDuration":
            case "dateTimeStamp":
            case "untypedAtomic":
            case "anyAtomicType":
            case "anySimpleType":
                return true;
            default:
                return false;
        }
    }
    
    /**
     * Generates a human-readable error message for type mismatch.
     *
     * @param value the actual value
     * @param variableName the variable name (may be null)
     * @return an error message describing the type mismatch
     */
    public String getTypeMismatchMessage(XPathValue value, String variableName) {
        StringBuilder sb = new StringBuilder();
        
        if (variableName != null) {
            sb.append("Variable $").append(variableName).append(": ");
        }
        
        sb.append("Required type is ").append(toString());
        sb.append("; supplied value ");
        
        if (value == null) {
            sb.append("is empty sequence");
        } else {
            int count = getItemCount(value);
            if (count == 0) {
                sb.append("is empty sequence");
            } else if (count == 1) {
                sb.append("has type ").append(getValueTypeName(value));
            } else {
                sb.append("is a sequence of ").append(count).append(" items");
            }
        }
        
        return sb.toString();
    }
    
    /**
     * Gets a human-readable type name for a value.
     */
    private String getValueTypeName(XPathValue value) {
        if (value instanceof XPathString) {
            return "xs:string";
        } else if (value instanceof XPathNumber) {
            return "xs:double";
        } else if (value instanceof XPathBoolean) {
            return "xs:boolean";
        } else if (value instanceof XPathDateTime) {
            XPathDateTime dt = (XPathDateTime) value;
            switch (dt.getDateTimeType()) {
                case DATE: return "xs:date";
                case TIME: return "xs:time";
                case DATE_TIME: return "xs:dateTime";
                case DURATION: return "xs:duration";
                case YEAR_MONTH_DURATION: return "xs:yearMonthDuration";
                case DAY_TIME_DURATION: return "xs:dayTimeDuration";
                default: return "xs:dateTime";
            }
        } else if (value instanceof XPathNodeSet) {
            XPathNodeSet ns = (XPathNodeSet) value;
            if (ns.size() == 1) {
                XPathNode node = ns.iterator().next();
                switch (node.getNodeType()) {
                    case ELEMENT: return "element()";
                    case ATTRIBUTE: return "attribute()";
                    case TEXT: return "text()";
                    case COMMENT: return "comment()";
                    case PROCESSING_INSTRUCTION: return "processing-instruction()";
                    case ROOT: return "document-node()";
                    default: return "node()";
                }
            }
            return "node()+";
        } else if (value instanceof XPathSequence) {
            return "item()+";
        }
        return "item()";
    }
    
    /**
     * Returns a string representation of this sequence type.
     *
     * <p>The format matches XPath 2.0/3.1 syntax, e.g., "xs:string", "element()*",
     * "node()?", "empty-sequence()".
     *
     * @return a string representation of the sequence type
     */
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

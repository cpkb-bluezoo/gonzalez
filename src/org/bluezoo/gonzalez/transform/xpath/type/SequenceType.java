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

import java.util.Iterator;

import org.bluezoo.gonzalez.QName;
import org.bluezoo.gonzalez.schema.xsd.XSDSchema;
import org.bluezoo.gonzalez.schema.xsd.XSDSimpleType;
import org.bluezoo.gonzalez.transform.runtime.OutputHandlerUtils;

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
        ARRAY,
        /** XPath 3.1 function type: function(*) or function(T1, T2) as R */
        FUNCTION
    }
    
    private final ItemKind itemKind;
    private final String namespaceURI;  // For atomic types or named element/attribute
    private final String localName;      // For atomic types or named element/attribute
    private final QName typeName;        // Resolved type for element(*, type) or attribute(*, type)
    private final Occurrence occurrence;
    private final SequenceType[] parameterTypes;  // For FUNCTION: parameter types (null for function(*))
    private final SequenceType returnType;        // For FUNCTION: return type (null for function(*))
    
    // Common predefined types
    public static final SequenceType ITEM = new SequenceType(ItemKind.ITEM, null, null, null, Occurrence.ONE);
    public static final SequenceType ITEM_STAR = new SequenceType(ItemKind.ITEM, null, null, null, Occurrence.ZERO_OR_MORE);
    public static final SequenceType NODE = new SequenceType(ItemKind.NODE, null, null, null, Occurrence.ONE);
    public static final SequenceType NODE_STAR = new SequenceType(ItemKind.NODE, null, null, null, Occurrence.ZERO_OR_MORE);
    public static final SequenceType EMPTY = new SequenceType(ItemKind.EMPTY, null, null, null, Occurrence.ONE);
    
    // Common atomic types
    public static final SequenceType STRING = atomic(XS_NAMESPACE, "string", Occurrence.ONE);
    public static final SequenceType STRING_STAR = atomic(XS_NAMESPACE, "string", Occurrence.ZERO_OR_MORE);
    public static final SequenceType STRING_Q = atomic(XS_NAMESPACE, "string", Occurrence.ZERO_OR_ONE);
    public static final SequenceType INTEGER = atomic(XS_NAMESPACE, "integer", Occurrence.ONE);
    public static final SequenceType INTEGER_STAR = atomic(XS_NAMESPACE, "integer", Occurrence.ZERO_OR_MORE);
    public static final SequenceType INTEGER_Q = atomic(XS_NAMESPACE, "integer", Occurrence.ZERO_OR_ONE);
    public static final SequenceType DECIMAL = atomic(XS_NAMESPACE, "decimal", Occurrence.ONE);
    public static final SequenceType DECIMAL_Q = atomic(XS_NAMESPACE, "decimal", Occurrence.ZERO_OR_ONE);
    public static final SequenceType DOUBLE = atomic(XS_NAMESPACE, "double", Occurrence.ONE);
    public static final SequenceType DOUBLE_Q = atomic(XS_NAMESPACE, "double", Occurrence.ZERO_OR_ONE);
    public static final SequenceType FLOAT = atomic(XS_NAMESPACE, "float", Occurrence.ONE);
    public static final SequenceType BOOLEAN = atomic(XS_NAMESPACE, "boolean", Occurrence.ONE);
    public static final SequenceType BOOLEAN_Q = atomic(XS_NAMESPACE, "boolean", Occurrence.ZERO_OR_ONE);
    public static final SequenceType DATE = atomic(XS_NAMESPACE, "date", Occurrence.ONE);
    public static final SequenceType DATE_Q = atomic(XS_NAMESPACE, "date", Occurrence.ZERO_OR_ONE);
    public static final SequenceType DATETIME = atomic(XS_NAMESPACE, "dateTime", Occurrence.ONE);
    public static final SequenceType DATETIME_Q = atomic(XS_NAMESPACE, "dateTime", Occurrence.ZERO_OR_ONE);
    public static final SequenceType TIME = atomic(XS_NAMESPACE, "time", Occurrence.ONE);
    public static final SequenceType TIME_Q = atomic(XS_NAMESPACE, "time", Occurrence.ZERO_OR_ONE);
    public static final SequenceType DURATION = atomic(XS_NAMESPACE, "duration", Occurrence.ONE);
    public static final SequenceType DURATION_Q = atomic(XS_NAMESPACE, "duration", Occurrence.ZERO_OR_ONE);
    public static final SequenceType ANYURI = atomic(XS_NAMESPACE, "anyURI", Occurrence.ONE);
    public static final SequenceType ANYURI_Q = atomic(XS_NAMESPACE, "anyURI", Occurrence.ZERO_OR_ONE);
    public static final SequenceType QNAME = atomic(XS_NAMESPACE, "QName", Occurrence.ONE);
    public static final SequenceType QNAME_Q = atomic(XS_NAMESPACE, "QName", Occurrence.ZERO_OR_ONE);
    public static final SequenceType UNTYPED_ATOMIC = atomic(XS_NAMESPACE, "untypedAtomic", Occurrence.ONE);
    public static final SequenceType ANY_ATOMIC = atomic(XS_NAMESPACE, "anyAtomicType", Occurrence.ONE);
    public static final SequenceType ANY_ATOMIC_Q = atomic(XS_NAMESPACE, "anyAtomicType", Occurrence.ZERO_OR_ONE);
    public static final SequenceType ANY_ATOMIC_STAR = atomic(XS_NAMESPACE, "anyAtomicType", Occurrence.ZERO_OR_MORE);
    public static final SequenceType NUMERIC = atomic(XS_NAMESPACE, "numeric", Occurrence.ONE);
    public static final SequenceType NUMERIC_Q = atomic(XS_NAMESPACE, "numeric", Occurrence.ZERO_OR_ONE);
    public static final SequenceType ITEM_Q = new SequenceType(ItemKind.ITEM, null, null, null, Occurrence.ZERO_OR_ONE);
    public static final SequenceType ITEM_PLUS = new SequenceType(ItemKind.ITEM, null, null, null, Occurrence.ONE_OR_MORE);
    public static final SequenceType NODE_Q = new SequenceType(ItemKind.NODE, null, null, null, Occurrence.ZERO_OR_ONE);
    public static final SequenceType NODE_PLUS = new SequenceType(ItemKind.NODE, null, null, null, Occurrence.ONE_OR_MORE);
    public static final SequenceType ELEMENT_STAR = new SequenceType(ItemKind.ELEMENT, null, null, null, Occurrence.ZERO_OR_MORE);
    public static final SequenceType ELEMENT_Q = new SequenceType(ItemKind.ELEMENT, null, null, null, Occurrence.ZERO_OR_ONE);
    public static final SequenceType ATTRIBUTE_STAR = new SequenceType(ItemKind.ATTRIBUTE, null, null, null, Occurrence.ZERO_OR_MORE);
    public static final SequenceType DOCUMENT_NODE = new SequenceType(ItemKind.DOCUMENT_NODE, null, null, null, Occurrence.ONE);
    public static final SequenceType DOCUMENT_NODE_Q = new SequenceType(ItemKind.DOCUMENT_NODE, null, null, null, Occurrence.ZERO_OR_ONE);
    public static final SequenceType TEXT_NODE = new SequenceType(ItemKind.TEXT, null, null, null, Occurrence.ONE);
    public static final SequenceType COMMENT_NODE = new SequenceType(ItemKind.COMMENT, null, null, null, Occurrence.ONE);
    public static final SequenceType PI_NODE = new SequenceType(ItemKind.PROCESSING_INSTRUCTION, null, null, null, Occurrence.ONE);
    public static final SequenceType YM_DURATION = atomic(XS_NAMESPACE, "yearMonthDuration", Occurrence.ONE);
    public static final SequenceType YM_DURATION_Q = atomic(XS_NAMESPACE, "yearMonthDuration", Occurrence.ZERO_OR_ONE);
    public static final SequenceType DT_DURATION = atomic(XS_NAMESPACE, "dayTimeDuration", Occurrence.ONE);
    public static final SequenceType DT_DURATION_Q = atomic(XS_NAMESPACE, "dayTimeDuration", Occurrence.ZERO_OR_ONE);
    public static final SequenceType HEX_BINARY = atomic(XS_NAMESPACE, "hexBinary", Occurrence.ONE);
    public static final SequenceType HEX_BINARY_Q = atomic(XS_NAMESPACE, "hexBinary", Occurrence.ZERO_OR_ONE);
    public static final SequenceType BASE64_BINARY = atomic(XS_NAMESPACE, "base64Binary", Occurrence.ONE);
    public static final SequenceType BASE64_BINARY_Q = atomic(XS_NAMESPACE, "base64Binary", Occurrence.ZERO_OR_ONE);
    
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
        this.parameterTypes = null;
        this.returnType = null;
    }

    private SequenceType(ItemKind itemKind, SequenceType[] parameterTypes,
                         SequenceType returnType, Occurrence occurrence) {
        this.itemKind = itemKind;
        this.namespaceURI = null;
        this.localName = null;
        this.typeName = null;
        this.occurrence = occurrence;
        this.parameterTypes = parameterTypes;
        this.returnType = returnType;
    }

    /**
     * Creates a function type: function(T1, T2, ...) as R.
     * Use null parameterTypes and returnType for function(*).
     *
     * @param parameterTypes parameter types (null for function(*))
     * @param returnType return type (null for function(*))
     * @param occurrence the occurrence indicator
     */
    public static SequenceType functionType(SequenceType[] parameterTypes,
                                            SequenceType returnType, Occurrence occurrence) {
        return new SequenceType(ItemKind.FUNCTION, parameterTypes, returnType, occurrence);
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
     * Returns a copy of this type with a different occurrence indicator.
     *
     * @param occ the new occurrence
     * @return a sequence type identical to this except for occurrence
     */
    public SequenceType withOccurrence(Occurrence occ) {
        if (occ == this.occurrence) {
            return this;
        }
        if (parameterTypes != null || returnType != null) {
            SequenceType ft = new SequenceType(itemKind, parameterTypes, returnType, occ);
            return ft;
        }
        return new SequenceType(itemKind, namespaceURI, localName, typeName, occ);
    }

    /**
     * Returns true if this type represents an atomic numeric type
     * (integer, decimal, float, double, or the abstract numeric type).
     *
     * @return true if numeric
     */
    public boolean isNumericType() {
        if (itemKind != ItemKind.ATOMIC) {
            return false;
        }
        if (localName == null) {
            return false;
        }
        return "integer".equals(localName)
            || "decimal".equals(localName)
            || "float".equals(localName)
            || "double".equals(localName)
            || "numeric".equals(localName);
    }

    /**
     * Returns true if this type represents xs:boolean.
     *
     * @return true if boolean
     */
    public boolean isBooleanType() {
        if (itemKind != ItemKind.ATOMIC) {
            return false;
        }
        return "boolean".equals(localName);
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
    /**
     * Normalizes whitespace before parentheses in type strings,
     * e.g. "element ()" becomes "element()", "element ( name )" becomes "element(name)".
     */
    private static String normalizeTypeWhitespace(String str) {
        if (str == null || !str.contains("(")) {
            return str;
        }
        StringBuilder sb = new StringBuilder();
        int len = str.length();
        for (int i = 0; i < len; i++) {
            char ch = str.charAt(i);
            if (ch == ' ' || ch == '\t') {
                int next = i + 1;
                while (next < len && (str.charAt(next) == ' ' || str.charAt(next) == '\t')) {
                    next++;
                }
                if (next < len && str.charAt(next) == '(') {
                    continue;
                }
                sb.append(ch);
            } else if (ch == '(' || ch == ')') {
                sb.append(ch);
                int next = i + 1;
                while (next < len && (str.charAt(next) == ' ' || str.charAt(next) == '\t')) {
                    next++;
                }
                if (next > i + 1) {
                    i = next - 1;
                }
            } else {
                sb.append(ch);
            }
        }
        return sb.toString();
    }

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
     * Returns the parameter types for function types.
     *
     * @return array of parameter types, or null for function(*)
     */
    public SequenceType[] getParameterTypes() {
        return parameterTypes;
    }

    /**
     * Returns the return type for function types.
     *
     * @return the return type, or null for function(*)
     */
    public SequenceType getReturnType() {
        return returnType;
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
                if (value instanceof XPathNode || value instanceof XPathNodeSet) {
                    return true;
                }
                if (value instanceof XPathResultTreeFragment) {
                    return true;
                }
                if (value instanceof XPathSequence) {
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
            case NAMESPACE_NODE:
                return matchesNodeKind(value);

            case MAP:
                return matchesMapType(value);

            case ARRAY:
                return matchesArrayType(value);

            case FUNCTION:
                return matchesFunctionType(value);

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
                if (value instanceof XPathNode || value instanceof XPathNodeSet) {
                    return true;
                }
                if (value instanceof XPathResultTreeFragment) {
                    return true;
                }
                if (value instanceof XPathSequence) {
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
            case NAMESPACE_NODE:
                return matchesNodeKind(value);

            case MAP:
                return matchesMapType(value);

            case ARRAY:
                return matchesArrayType(value);

            case FUNCTION:
                return matchesFunctionType(value);

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
            String typeNs = OutputHandlerUtils.effectiveUri(namespaceURI);
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
            String typeNs = OutputHandlerUtils.effectiveUri(namespaceURI);
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
            String expectedNs = OutputHandlerUtils.effectiveUri(namespaceURI);
            
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
            String expectedNs = OutputHandlerUtils.effectiveUri(namespaceURI);
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

        // xs:anyType matches everything
        if (XS_NAMESPACE.equals(expectedNs) && "anyType".equals(expectedLocal)) {
            return true;
        }

        String nodeTypeLocal = node.getTypeLocalName();
        if (nodeTypeLocal == null) {
            // Node has no explicit type annotation.
            // Per XPath: unvalidated elements have type xs:untyped,
            // unvalidated attributes have type xs:untypedAtomic.
            boolean isAttribute = (node.getNodeType() == NodeType.ATTRIBUTE);
            String implicitType = isAttribute ? "untypedAtomic" : "untyped";

            if (!XS_NAMESPACE.equals(expectedNs)) {
                return false;
            }
            if (expectedLocal.equals(implicitType)) {
                return true;
            }
            // Check if the implicit type is a subtype of the expected type
            return isTypeSubtypeOf(implicitType, expectedLocal);
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
        // anyType is the root of ALL types (including xs:untyped)
        if ("anyType".equals(expectedType)) {
            return true;
        }
        // anyAtomicType and anySimpleType are the roots of all atomic/simple types,
        // but NOT xs:untyped which is a complex type deriving from xs:anyType
        if ("anyAtomicType".equals(expectedType) || "anySimpleType".equals(expectedType)) {
            return !"untyped".equals(actualType);
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
    
    /**
     * Checks if a value matches this function type.
     * For function(*): any function item matches.
     * For function(T1, T2, ...) as R: arity must match, each test parameter type
     * must be subtype of the actual parameter type (contravariant), and the actual
     * return type must be subtype of the test return type (covariant).
     */
    private boolean matchesFunctionType(XPathValue value) {
        if (value instanceof XPathSequence) {
            XPathSequence seq = (XPathSequence) value;
            if (seq.size() != 1) {
                return false;
            }
            return matchesFunctionType(seq.iterator().next());
        }
        int arity;
        SequenceType[] actualParamTypes = null;
        SequenceType actualReturnType = null;
        if (value instanceof XPathFunctionItem) {
            XPathFunctionItem funcItem = (XPathFunctionItem) value;
            arity = funcItem.getArity();
            actualParamTypes = funcItem.getParameterTypes();
            actualReturnType = funcItem.getReturnType();
        } else if (value instanceof org.bluezoo.gonzalez.transform.xpath.expr.InlineFunctionItem) {
            arity = ((org.bluezoo.gonzalez.transform.xpath.expr.InlineFunctionItem) value).getArity();
        } else {
            return false;
        }
        if (parameterTypes == null) {
            return true;
        }
        if (arity != parameterTypes.length) {
            return false;
        }
        if (actualParamTypes == null || actualReturnType == null) {
            return true;
        }
        for (int i = 0; i < parameterTypes.length; i++) {
            if (!isSubtypeOf(parameterTypes[i], actualParamTypes[i])) {
                return false;
            }
        }
        if (returnType != null) {
            if (!isSubtypeOf(actualReturnType, returnType)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Checks if type A is a subtype of type B according to XPath 3.1 rules.
     * This covers occurrence compatibility, atomic type hierarchy, and node type hierarchy.
     */
    static boolean isSubtypeOf(SequenceType a, SequenceType b) {
        if (a == null || b == null) {
            return true;
        }
        if (b.itemKind == ItemKind.ITEM) {
            return occurrenceSubtype(a.occurrence, b.occurrence);
        }
        if (!occurrenceSubtype(a.occurrence, b.occurrence)) {
            return false;
        }
        if (a.itemKind == b.itemKind) {
            if (a.itemKind == ItemKind.ATOMIC) {
                return isAtomicSubtype(a.localName, b.localName);
            }
            if (a.itemKind == ItemKind.ELEMENT) {
                return isElementSubtype(a, b);
            }
            if (a.itemKind == ItemKind.FUNCTION) {
                return isFunctionSubtype(a, b);
            }
            return true;
        }
        if (b.itemKind == ItemKind.NODE) {
            return a.itemKind == ItemKind.ELEMENT || a.itemKind == ItemKind.ATTRIBUTE
                || a.itemKind == ItemKind.TEXT || a.itemKind == ItemKind.COMMENT
                || a.itemKind == ItemKind.PROCESSING_INSTRUCTION
                || a.itemKind == ItemKind.DOCUMENT_NODE || a.itemKind == ItemKind.NAMESPACE_NODE;
        }
        return false;
    }

    /**
     * Checks if occurrence A is compatible as a subtype of occurrence B.
     * ONE is the most restrictive; ZERO_OR_MORE is the least.
     */
    private static boolean occurrenceSubtype(Occurrence a, Occurrence b) {
        if (a == b) {
            return true;
        }
        if (b == Occurrence.ZERO_OR_MORE) {
            return true;
        }
        if (b == Occurrence.ZERO_OR_ONE) {
            return a == Occurrence.ONE;
        }
        if (b == Occurrence.ONE_OR_MORE) {
            return a == Occurrence.ONE;
        }
        return false;
    }

    /**
     * Checks if atomic type A is a subtype of atomic type B using XSD hierarchy.
     */
    private static boolean isAtomicSubtype(String aLocal, String bLocal) {
        if (aLocal == null || bLocal == null) {
            return true;
        }
        if (aLocal.equals(bLocal)) {
            return true;
        }
        if ("anyAtomicType".equals(bLocal)) {
            return true;
        }
        if (isIntegerDerived(aLocal) && isIntegerOrAncestor(bLocal)) {
            return getIntegerRank(aLocal) <= getIntegerRank(bLocal);
        }
        if ("float".equals(aLocal) && "double".equals(bLocal)) {
            return true;
        }
        if ("NCName".equals(aLocal) && ("Name".equals(bLocal) || "token".equals(bLocal)
                || "normalizedString".equals(bLocal) || "string".equals(bLocal))) {
            return true;
        }
        if ("Name".equals(aLocal) && ("token".equals(bLocal)
                || "normalizedString".equals(bLocal) || "string".equals(bLocal))) {
            return true;
        }
        if ("token".equals(aLocal) && ("normalizedString".equals(bLocal)
                || "string".equals(bLocal))) {
            return true;
        }
        if ("normalizedString".equals(aLocal) && "string".equals(bLocal)) {
            return true;
        }
        return false;
    }

    private static boolean isIntegerDerived(String name) {
        return "byte".equals(name) || "short".equals(name) || "int".equals(name)
                || "long".equals(name) || "integer".equals(name) || "decimal".equals(name)
                || "nonNegativeInteger".equals(name) || "positiveInteger".equals(name)
                || "nonPositiveInteger".equals(name) || "negativeInteger".equals(name)
                || "unsignedByte".equals(name) || "unsignedShort".equals(name)
                || "unsignedInt".equals(name) || "unsignedLong".equals(name);
    }

    private static boolean isIntegerOrAncestor(String name) {
        return isIntegerDerived(name) || "double".equals(name) || "float".equals(name)
                || "anyAtomicType".equals(name);
    }

    private static int getIntegerRank(String name) {
        if ("byte".equals(name)) { return 1; }
        if ("short".equals(name)) { return 2; }
        if ("int".equals(name)) { return 3; }
        if ("long".equals(name)) { return 4; }
        if ("integer".equals(name)) { return 5; }
        if ("decimal".equals(name)) { return 6; }
        if ("nonNegativeInteger".equals(name) || "positiveInteger".equals(name)
                || "nonPositiveInteger".equals(name) || "negativeInteger".equals(name)) { return 5; }
        if ("unsignedByte".equals(name)) { return 1; }
        if ("unsignedShort".equals(name)) { return 2; }
        if ("unsignedInt".equals(name)) { return 3; }
        if ("unsignedLong".equals(name)) { return 4; }
        if ("double".equals(name)) { return 7; }
        if ("float".equals(name)) { return 7; }
        if ("anyAtomicType".equals(name)) { return 10; }
        return 10;
    }

    /**
     * Checks if element type A is a subtype of element type B.
     */
    private static boolean isElementSubtype(SequenceType a, SequenceType b) {
        if (b.localName != null && a.localName != null && !a.localName.equals(b.localName)) {
            return false;
        }
        if (b.typeName != null) {
            if (a.typeName == null) {
                return false;
            }
            String aTypeLocal = a.typeName.getLocalName();
            String bTypeLocal = b.typeName.getLocalName();
            if (!aTypeLocal.equals(bTypeLocal)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Checks if function type A is a subtype of function type B.
     */
    private static boolean isFunctionSubtype(SequenceType a, SequenceType b) {
        if (b.parameterTypes == null) {
            return true;
        }
        if (a.parameterTypes == null) {
            return true;
        }
        if (a.parameterTypes.length != b.parameterTypes.length) {
            return false;
        }
        for (int i = 0; i < a.parameterTypes.length; i++) {
            if (!isSubtypeOf(b.parameterTypes[i], a.parameterTypes[i])) {
                return false;
            }
        }
        if (b.returnType != null && a.returnType != null) {
            if (!isSubtypeOf(a.returnType, b.returnType)) {
                return false;
            }
        }
        return true;
    }

    private boolean matchesMapType(XPathValue value) {
        if (value instanceof XPathMap) {
            return true;
        }
        if (value instanceof XPathSequence) {
            XPathSequence seq = (XPathSequence) value;
            for (XPathValue item : seq) {
                if (!(item instanceof XPathMap)) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    private boolean matchesArrayType(XPathValue value) {
        if (value instanceof XPathArray) {
            return true;
        }
        if (value instanceof XPathSequence) {
            XPathSequence seq = (XPathSequence) value;
            for (XPathValue item : seq) {
                if (!(item instanceof XPathArray)) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    private boolean matchesNodeKind(XPathValue value) {
        if (value instanceof XPathResultTreeFragment) {
            if (itemKind == ItemKind.NODE) {
                return true;
            }
            if (itemKind == ItemKind.DOCUMENT_NODE) {
                if (localName != null) {
                    // document-node(element(...)) — check the document element
                    XPathResultTreeFragment rtf = (XPathResultTreeFragment) value;
                    XPathNodeSet ns = rtf.asNodeSet();
                    if (ns != null && !ns.isEmpty()) {
                        XPathNode root = ns.iterator().next();
                        if (root.getNodeType() == NodeType.ROOT) {
                            return matchesDocumentElement(root);
                        }
                    }
                    return false;
                }
                return true;
            }
            return false;
        }
        if (value instanceof XPathNode) {
            return matchesSingleNodeKind((XPathNode) value);
        }
        if (value instanceof XPathNodeSet) {
            XPathNodeSet ns = (XPathNodeSet) value;
            for (XPathNode node : ns) {
                if (!matchesSingleNodeKind(node)) {
                    return false;
                }
            }
            return true;
        }
        if (value instanceof XPathSequence) {
            for (XPathValue item : (XPathSequence) value) {
                if (item instanceof XPathResultTreeFragment) {
                    if (itemKind == ItemKind.NODE) {
                        // any node kind matches
                    } else if (itemKind == ItemKind.DOCUMENT_NODE) {
                        if (localName != null) {
                            XPathResultTreeFragment seqRtf =
                                (XPathResultTreeFragment) item;
                            XPathNodeSet seqNs = seqRtf.asNodeSet();
                            boolean docMatch = false;
                            if (seqNs != null && !seqNs.isEmpty()) {
                                XPathNode seqRoot = seqNs.iterator().next();
                                if (seqRoot.getNodeType() == NodeType.ROOT) {
                                    docMatch = matchesDocumentElement(seqRoot);
                                }
                            }
                            if (!docMatch) {
                                return false;
                            }
                        }
                    } else {
                        return false;
                    }
                } else if (item instanceof XPathNode) {
                    if (!matchesSingleNodeKind((XPathNode) item)) {
                        return false;
                    }
                } else if (item instanceof XPathNodeSet) {
                    XPathNodeSet ns2 = (XPathNodeSet) item;
                    for (XPathNode node : ns2) {
                        if (!matchesSingleNodeKind(node)) {
                            return false;
                        }
                    }
                } else {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    private boolean matchesSingleNodeKind(XPathNode node) {
        switch (itemKind) {
            case NODE:
                return true;
            case ELEMENT:
                if (node.getNodeType() != NodeType.ELEMENT) {
                    return false;
                }
                if (localName != null && !localName.equals(node.getLocalName())) {
                    return false;
                }
                return true;
            case ATTRIBUTE:
                if (node.getNodeType() != NodeType.ATTRIBUTE) {
                    return false;
                }
                if (localName != null && !localName.equals(node.getLocalName())) {
                    return false;
                }
                return true;
            case TEXT:
                return node.getNodeType() == NodeType.TEXT;
            case COMMENT:
                return node.getNodeType() == NodeType.COMMENT;
            case PROCESSING_INSTRUCTION:
                return node.getNodeType() == NodeType.PROCESSING_INSTRUCTION;
            case DOCUMENT_NODE:
                if (node.getNodeType() != NodeType.ROOT) {
                    return false;
                }
                // If an inner element test is specified, check document element
                if (localName != null) {
                    return matchesDocumentElement(node);
                }
                return true;
            case NAMESPACE_NODE:
                return node.getNodeType() == NodeType.NAMESPACE;
            default:
                return true;
        }
    }
    
    /**
     * Checks if the document element of a ROOT node matches the inner
     * element test from document-node(element(name, type)).
     * The localName field holds the element name (or "*" for wildcard).
     */
    private boolean matchesDocumentElement(XPathNode docNode) {
        Iterator<XPathNode> children = docNode.getChildren();
        XPathNode docElem = null;
        while (children.hasNext()) {
            XPathNode child = children.next();
            if (child.getNodeType() == NodeType.ELEMENT) {
                docElem = child;
                break;
            }
        }
        if (docElem == null) {
            return false;
        }
        if (!"*".equals(localName) && !localName.equals(docElem.getLocalName())) {
            return false;
        }
        if (namespaceURI != null && !namespaceURI.isEmpty()) {
            String elemNs = docElem.getNamespaceURI();
            if (elemNs == null) {
                elemNs = "";
            }
            if (!namespaceURI.equals(elemNs)) {
                return false;
            }
        }
        return true;
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
                // xs:string and subtypes: match XPathString but NOT XPathUntypedAtomic
                return (value instanceof XPathString && !(value instanceof XPathUntypedAtomic)) ||
                       value instanceof XPathAtomicValue;
                
            case "anyURI":
                return value instanceof XPathAnyURI || value instanceof XPathAtomicValue;
                
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
                return value instanceof XPathQName || value instanceof XPathAtomicValue;
                
            case "untypedAtomic":
                return value instanceof XPathUntypedAtomic || value instanceof XPathAtomicValue;
                
            case "anyAtomicType":
                // anyAtomicType matches any atomic value
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
        
        // Normalize whitespace before parentheses: "element (" -> "element("
        type = normalizeTypeWhitespace(type);
        
        // Parse occurrence indicator (at end, with optional space)
        Occurrence occ = Occurrence.ONE;
        if (type.endsWith("?") && !endsInsideParens(type)) {
            occ = Occurrence.ZERO_OR_ONE;
            type = type.substring(0, type.length() - 1).trim();
        } else if (type.endsWith("*") && !endsInsideParens(type)) {
            occ = Occurrence.ZERO_OR_MORE;
            type = type.substring(0, type.length() - 1).trim();
        } else if (type.endsWith("+") && !endsInsideParens(type)) {
            occ = Occurrence.ONE_OR_MORE;
            type = type.substring(0, type.length() - 1).trim();
        }
        
        // Strip grouping parentheses: (function(...) as R) -> function(...) as R
        if (type.startsWith("(") && type.endsWith(")")) {
            int matchPos = findMatchingParen(type, 0);
            if (matchPos == type.length() - 1) {
                String inner = type.substring(1, type.length() - 1).trim();
                SequenceType innerType = parse(inner, namespaceResolver);
                if (innerType != null) {
                    if (occ != Occurrence.ONE) {
                        return innerType.withOccurrence(occ);
                    }
                    return innerType;
                }
            }
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
            String docElemLocal = null;
            String docElemNs = null;
            // Parse document-node() or document-node(element(...))
            if (type.startsWith("document-node(") && type.endsWith(")") && !type.equals("document-node()")) {
                // Strip outer document-node( ... ) — the closing paren is matched
                // by the last paren of the string
                String inner = type.substring("document-node(".length(), type.length() - 1).trim();
                if (inner.startsWith("element(") && inner.endsWith(")")) {
                    String elemInner = inner.substring("element(".length(), inner.length() - 1).trim();
                    if (!elemInner.isEmpty() && !elemInner.equals("*")) {
                        int commaIdx = elemInner.indexOf(',');
                        String namePart;
                        if (commaIdx >= 0) {
                            namePart = elemInner.substring(0, commaIdx).trim();
                        } else {
                            namePart = elemInner;
                        }
                        if (!namePart.equals("*")) {
                            String[] resolved = resolvePrefixedName(namePart, namespaceResolver);
                            docElemNs = resolved[0];
                            docElemLocal = resolved[1];
                        }
                    }
                } else if (inner.startsWith("schema-element(") && inner.endsWith(")")) {
                    String schemaInner = inner.substring("schema-element(".length(), inner.length() - 1).trim();
                    if (!schemaInner.isEmpty()) {
                        String[] resolved = resolvePrefixedName(schemaInner, namespaceResolver);
                        docElemNs = resolved[0];
                        docElemLocal = resolved[1];
                    }
                }
            }
            return new SequenceType(ItemKind.DOCUMENT_NODE, docElemNs, docElemLocal, null, occ);
        }
        if (type.equals("namespace-node()")) {
            return new SequenceType(ItemKind.NAMESPACE_NODE, null, null, null, occ);
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

        // Parse function(*) or function(T1, T2, ...) as R
        if (type.equals("function(*)")) {
            return functionType(null, null, occ);
        }
        if (type.startsWith("function(")) {
            return parseFunctionType(type, occ, namespaceResolver);
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
    /**
     * Parses a function type string like "function(xs:integer, xs:string) as xs:boolean".
     * Handles nested parentheses for complex types.
     */
    private static SequenceType parseFunctionType(String type, Occurrence occ,
            java.util.function.Function<String, String> namespaceResolver) {
        int openParen = type.indexOf('(');
        int closeParen = findMatchingParen(type, openParen);
        if (closeParen < 0) {
            return functionType(null, null, occ);
        }
        String inner = type.substring(openParen + 1, closeParen).trim();
        String afterParen = type.substring(closeParen + 1).trim();

        SequenceType returnType = null;
        if (afterParen.startsWith("as ") || afterParen.startsWith("as\t")) {
            String retStr = afterParen.substring(3).trim();
            returnType = parse(retStr, namespaceResolver);
        }

        if (inner.equals("*")) {
            return functionType(null, null, occ);
        }
        if (inner.isEmpty()) {
            SequenceType[] paramTypes = new SequenceType[0];
            return functionType(paramTypes, returnType, occ);
        }

        java.util.List paramList = new java.util.ArrayList();
        int start = 0;
        int depth = 0;
        for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (c == ',' && depth == 0) {
                String paramStr = inner.substring(start, i).trim();
                SequenceType pt = parse(paramStr, namespaceResolver);
                if (pt != null) {
                    paramList.add(pt);
                }
                start = i + 1;
            }
        }
        String lastParam = inner.substring(start).trim();
        if (!lastParam.isEmpty()) {
            SequenceType pt = parse(lastParam, namespaceResolver);
            if (pt != null) {
                paramList.add(pt);
            }
        }
        SequenceType[] paramTypes = new SequenceType[paramList.size()];
        paramList.toArray(paramTypes);
        return functionType(paramTypes, returnType, occ);
    }

    /**
     * Checks if the last character of a type string is inside parentheses.
     * Used to distinguish occurrence indicators from type content like map(*).
     */
    private static boolean endsInsideParens(String str) {
        int depth = 0;
        int lastIdx = str.length() - 1;
        for (int i = 0; i < lastIdx; i++) {
            if (str.charAt(i) == '(') {
                depth++;
            } else if (str.charAt(i) == ')') {
                depth--;
            }
        }
        return depth > 0;
    }

    private static int findMatchingParen(String str, int openPos) {
        int depth = 0;
        for (int i = openPos; i < str.length(); i++) {
            if (str.charAt(i) == '(') {
                depth++;
            } else if (str.charAt(i) == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

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

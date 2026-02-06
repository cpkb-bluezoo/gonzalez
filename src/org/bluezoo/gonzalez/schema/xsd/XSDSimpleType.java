/*
 * XSDSimpleType.java
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

package org.bluezoo.gonzalez.schema.xsd;

import java.math.BigDecimal;
import java.net.URI;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Represents an XSD simple type definition.
 *
 * <p>Simple types can be:
 * <ul>
 *   <li>Built-in types (string, integer, boolean, etc.)</li>
 *   <li>Restrictions of other simple types (with facets)</li>
 *   <li>Lists of simple types</li>
 *   <li>Unions of simple types</li>
 * </ul>
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public class XSDSimpleType extends XSDType {
    
    /** Resource path for type hierarchy configuration */
    private static final String TYPE_HIERARCHY_RESOURCE = "/META-INF/xsd-type-hierarchy.properties";
    
    private static final Map<String, XSDSimpleType> BUILT_IN_TYPE_MAP = new HashMap<>();
    
    static {
        // Create the root type first (anyType has no base)
        BUILT_IN_TYPE_MAP.put("anyType", new XSDSimpleType("anyType", XSDSchema.XSD_NAMESPACE, null));
        
        // Load type hierarchy from resource file
        loadTypeHierarchy();
    }
    
    /**
     * Loads the built-in type hierarchy from a properties resource file.
     * 
     * <p>The properties file format is: type=baseType
     * <p>This allows the type hierarchy to be maintained separately from code.
     */
    private static void loadTypeHierarchy() {
        java.util.Properties props = new java.util.Properties();
        try (java.io.InputStream is = XSDSimpleType.class.getResourceAsStream(TYPE_HIERARCHY_RESOURCE)) {
            if (is != null) {
                props.load(is);
                
                // First pass: create all types
                for (String typeName : props.stringPropertyNames()) {
                    if (!BUILT_IN_TYPE_MAP.containsKey(typeName)) {
                        BUILT_IN_TYPE_MAP.put(typeName, 
                            new XSDSimpleType(typeName, XSDSchema.XSD_NAMESPACE, null));
                    }
                    // Also ensure base type exists
                    String baseName = props.getProperty(typeName);
                    if (baseName != null && !BUILT_IN_TYPE_MAP.containsKey(baseName)) {
                        BUILT_IN_TYPE_MAP.put(baseName, 
                            new XSDSimpleType(baseName, XSDSchema.XSD_NAMESPACE, null));
                    }
                }
                
                // Second pass: set up base type relationships
                for (String typeName : props.stringPropertyNames()) {
                    String baseName = props.getProperty(typeName);
                    if (baseName != null) {
                        XSDSimpleType derived = BUILT_IN_TYPE_MAP.get(typeName);
                        XSDSimpleType base = BUILT_IN_TYPE_MAP.get(baseName);
                        if (derived != null && base != null) {
                            derived.baseType = base;
                        }
                    }
                }
            } else {
                // Fallback: create minimal set of types if resource not found
                System.err.println("Warning: XSD type hierarchy resource not found: " + TYPE_HIERARCHY_RESOURCE);
                createFallbackTypes();
            }
        } catch (java.io.IOException e) {
            System.err.println("Warning: Failed to load XSD type hierarchy: " + e.getMessage());
            createFallbackTypes();
        }
    }
    
    /**
     * Creates a minimal fallback set of built-in types if the resource file cannot be loaded.
     */
    private static void createFallbackTypes() {
        // Minimal set of commonly used types
        String[] basics = {"anySimpleType", "anyAtomicType", "string", "boolean", 
            "decimal", "integer", "int", "long", "float", "double", 
            "date", "dateTime", "time", "duration",
            "normalizedString", "token", "NCName", "ID", "IDREF",
            "untypedAtomic"};
        for (String name : basics) {
            if (!BUILT_IN_TYPE_MAP.containsKey(name)) {
                BUILT_IN_TYPE_MAP.put(name, new XSDSimpleType(name, XSDSchema.XSD_NAMESPACE, null));
            }
        }
    }
    
    /**
     * Variety of simple type.
     */
    public enum Variety {
        ATOMIC,
        LIST,
        UNION
    }
    
    private final Variety variety;
    private XSDSimpleType baseType;  // Mutable for late binding during parsing
    private final XSDSimpleType itemType;  // For LIST variety
    private final List<XSDSimpleType> memberTypes;  // For UNION variety
    
    // Facets
    private Integer minLength;
    private Integer maxLength;
    private Integer length;
    private String pattern;
    private Set<String> enumeration;
    private String minInclusive;
    private String maxInclusive;
    private String minExclusive;
    private String maxExclusive;
    private Integer totalDigits;
    private Integer fractionDigits;
    private WhitespaceHandling whitespace = WhitespaceHandling.PRESERVE;
    
    // Compiled pattern cache for performance
    private Pattern compiledPattern;
    
    /**
     * Whitespace handling modes for simple types.
     *
     * <p>This enum defines how whitespace characters are normalized during
     * validation and value conversion, as specified by the whitespace facet.
     */
    public enum WhitespaceHandling {
        /**
         * Preserve whitespace.
         *
         * <p>No whitespace normalization is performed. All whitespace
         * characters (space, tab, newline, carriage return) are preserved
         * as-is.
         */
        PRESERVE,
        
        /**
         * Replace whitespace.
         *
         * <p>All whitespace characters (tab, newline, carriage return) are
         * replaced with spaces, but multiple spaces are not collapsed.
         */
        REPLACE,
        
        /**
         * Collapse whitespace.
         *
         * <p>All whitespace characters are replaced with spaces, multiple
         * spaces are collapsed to single spaces, and leading/trailing
         * whitespace is trimmed.
         */
        COLLAPSE
    }
    
    /**
     * Creates an atomic simple type.
     *
     * <p>Atomic types represent single values, optionally derived from
     * a base type with facets (restrictions).
     *
     * @param name the type name (local part), or null for anonymous types
     * @param namespaceURI the type namespace URI
     * @param baseType the base type from which this type is derived, or null for built-in types
     */
    public XSDSimpleType(String name, String namespaceURI, XSDSimpleType baseType) {
        super(name, namespaceURI);
        this.variety = Variety.ATOMIC;
        this.baseType = baseType;
        this.itemType = null;
        this.memberTypes = null;
    }
    
    /**
     * Creates a list simple type.
     *
     * <p>List types represent sequences of atomic values separated by whitespace.
     * The itemType specifies the type of each item in the list.
     *
     * @param name the type name (local part)
     * @param namespaceURI the type namespace URI
     * @param itemType the type of each item in the list
     * @return a new list simple type
     */
    public static XSDSimpleType createList(String name, String namespaceURI, XSDSimpleType itemType) {
        return new XSDSimpleType(name, namespaceURI, Variety.LIST, null, itemType, null);
    }
    
    /**
     * Creates a union simple type.
     *
     * <p>Union types represent values that can be one of several member types.
     * The value is validated against each member type in order until one matches.
     *
     * @param name the type name (local part)
     * @param namespaceURI the type namespace URI
     * @param memberTypes the list of member types (must not be null or empty)
     * @return a new union simple type
     */
    public static XSDSimpleType createUnion(String name, String namespaceURI, List<XSDSimpleType> memberTypes) {
        return new XSDSimpleType(name, namespaceURI, Variety.UNION, null, null, memberTypes);
    }
    
    private XSDSimpleType(String name, String namespaceURI, Variety variety,
            XSDSimpleType baseType, XSDSimpleType itemType, List<XSDSimpleType> memberTypes) {
        super(name, namespaceURI);
        this.variety = variety;
        this.baseType = baseType;
        this.itemType = itemType;
        this.memberTypes = memberTypes != null ? Collections.unmodifiableList(memberTypes) : null;
    }
    
    /**
     * Gets a built-in XSD type by name.
     *
     * <p>Built-in types are defined in XSD Part 2 and include primitive types
     * (string, boolean, decimal, etc.) and derived types (integer, token, etc.).
     *
     * @param name the built-in type name (e.g., "string", "integer", "date")
     * @return the built-in type, or null if not a built-in type name
     */
    public static XSDSimpleType getBuiltInType(String name) {
        return BUILT_IN_TYPE_MAP.get(name);
    }
    
    /**
     * Returns all built-in type names.
     *
     * @return set of built-in type names
     */
    public static java.util.Set<String> getBuiltInTypeNames() {
        return BUILT_IN_TYPE_MAP.keySet();
    }
    
    /**
     * Returns true if this is the built-in xs:ID type.
     *
     * @return true if this is xs:ID, false otherwise
     */
    public boolean isIdType() {
        return "ID".equals(getName()) && XSDSchema.XSD_NAMESPACE.equals(getNamespaceURI());
    }
    
    /**
     * Returns true if this type is derived from xs:ID.
     *
     * <p>This includes xs:ID itself and any types derived from it by restriction.
     * This is used to identify ID attributes for uniqueness validation.
     *
     * @return true if derived from xs:ID, false otherwise
     */
    public boolean isDerivedFromId() {
        if (isIdType()) return true;
        if (baseType != null) {
            return baseType.isDerivedFromId();
        }
        return false;
    }
    
    /**
     * Checks if this type is the same as or derived from the specified type.
     *
     * <p>This follows the XSD type derivation hierarchy. A type is considered
     * derived from another if it is the same type, or if its base type chain
     * includes the target type.
     *
     * @param other the type to check derivation from
     * @return true if this type is the same as or derives from the other type
     */
    public boolean isDerivedFrom(XSDSimpleType other) {
        if (other == null) {
            return false;
        }
        // Same type
        if (this == other) {
            return true;
        }
        // Same name and namespace
        if (getName() != null && getName().equals(other.getName()) &&
            getNamespaceURI() != null && getNamespaceURI().equals(other.getNamespaceURI())) {
            return true;
        }
        // Check base type chain
        if (baseType != null) {
            return baseType.isDerivedFrom(other);
        }
        return false;
    }
    
    /**
     * Checks if this type is the same as or derived from a type with the given name.
     *
     * @param namespaceURI the target type namespace (null for XSD namespace)
     * @param localName the target type local name
     * @return true if this type is the same as or derives from the named type
     */
    public boolean isDerivedFrom(String namespaceURI, String localName) {
        if (localName == null) {
            return false;
        }
        // Normalize namespace - null means XSD namespace for built-in types
        String targetNs = namespaceURI != null ? namespaceURI : XSDSchema.XSD_NAMESPACE;
        
        // Check this type
        if (localName.equals(getName()) && targetNs.equals(getNamespaceURI())) {
            return true;
        }
        // Check base type chain
        if (baseType != null) {
            return baseType.isDerivedFrom(namespaceURI, localName);
        }
        return false;
    }
    
    /**
     * Returns true, indicating this is a simple type.
     *
     * @return true
     */
    @Override
    public boolean isSimpleType() {
        return true;
    }
    
    /**
     * Returns the variety of this simple type.
     *
     * @return the variety (ATOMIC, LIST, or UNION)
     */
    public Variety getVariety() {
        return variety;
    }
    
    /**
     * Returns the effective variety of this type.
     *
     * <p>For derived types (restrictions), this follows the base type chain
     * to find the actual variety. A restriction of a LIST type is still
     * effectively a LIST type.
     *
     * @return the effective variety
     */
    public Variety getEffectiveVariety() {
        if (variety != Variety.ATOMIC) {
            return variety;
        }
        if (baseType != null) {
            return baseType.getEffectiveVariety();
        }
        return variety;
    }
    
    /**
     * Returns the effective item type for list types.
     *
     * <p>For derived types (restrictions of a list), this follows the base
     * type chain to find the actual item type.
     *
     * @return the effective item type, or null if not a list type
     */
    public XSDSimpleType getEffectiveItemType() {
        if (itemType != null) {
            return itemType;
        }
        if (baseType != null) {
            return baseType.getEffectiveItemType();
        }
        return null;
    }
    
    /**
     * Returns the base type from which this type is derived.
     *
     * <p>For atomic types, this is the type being restricted. For list and
     * union types, this is typically null.
     *
     * @return the base type, or null if not derived
     */
    public XSDSimpleType getBaseType() {
        return baseType;
    }
    
    /**
     * Sets the base type. Used during schema parsing for late binding.
     *
     * <p>This method is used when parsing schemas where type references
     * may appear before the type definition. The base type is resolved
     * after all types have been parsed.
     *
     * @param baseType the base type from which this type is derived
     */
    public void setBaseType(XSDSimpleType baseType) {
        this.baseType = baseType;
    }
    
    /**
     * Returns the item type for list types.
     *
     * <p>For list varieties, this returns the type of each item in the list.
     * For other varieties, returns null.
     *
     * @return the item type, or null if not a list type
     */
    public XSDSimpleType getItemType() {
        return itemType;
    }
    
    /**
     * Returns the member types for union types.
     *
     * <p>For union varieties, this returns the list of member types. For
     * other varieties, returns null.
     *
     * @return the member types, or null if not a union type
     */
    public List<XSDSimpleType> getMemberTypes() {
        return memberTypes;
    }
    
    /**
     * Checks if this type is a subtype of (derived from) another type.
     *
     * <p>This walks up the type hierarchy to check if this type is derived
     * from the specified type. For example:
     * <ul>
     *   <li>xs:ID is a subtype of xs:NCName, xs:Name, xs:token, xs:string</li>
     *   <li>xs:int is a subtype of xs:long, xs:integer, xs:decimal</li>
     * </ul>
     *
     * <p>A type is considered a subtype of itself.
     *
     * @param otherType the potential base type
     * @return true if this type is derived from otherType (or is equal to it)
     */
    public boolean isSubtypeOf(XSDSimpleType otherType) {
        if (otherType == null) {
            return false;
        }
        
        // Check equality (a type is a subtype of itself)
        if (this == otherType) {
            return true;
        }
        
        // Check by namespace and name
        if (this.getNamespaceURI() != null && this.getNamespaceURI().equals(otherType.getNamespaceURI()) &&
            this.getName() != null && this.getName().equals(otherType.getName())) {
            return true;
        }
        
        // Walk up the base type chain
        XSDSimpleType current = this.baseType;
        while (current != null) {
            if (current == otherType) {
                return true;
            }
            if (current.getNamespaceURI() != null && current.getNamespaceURI().equals(otherType.getNamespaceURI()) &&
                current.getName() != null && current.getName().equals(otherType.getName())) {
                return true;
            }
            current = current.baseType;
        }
        
        return false;
    }
    
    /**
     * Returns the primitive type at the root of this type's hierarchy.
     *
     * <p>For built-in XSD types, this walks up the base type chain until
     * reaching a primitive type (one of the 19 XSD primitive types like
     * xs:string, xs:integer, xs:boolean, etc.) or anyAtomicType.
     *
     * <p>Examples:
     * <ul>
     *   <li>xs:ID → xs:string</li>
     *   <li>xs:int → xs:decimal</li>
     *   <li>xs:positiveInteger → xs:decimal</li>
     * </ul>
     *
     * @return the primitive type, or this type if it is already primitive
     */
    public XSDSimpleType getPrimitiveType() {
        // Primitive types according to XML Schema Part 2
        if (isPrimitive()) {
            return this;
        }
        
        // Walk up the base type chain
        XSDSimpleType current = this.baseType;
        while (current != null) {
            if (current.isPrimitive()) {
                return current;
            }
            current = current.baseType;
        }
        
        // If no primitive found, return anyAtomicType as a fallback
        return BUILT_IN_TYPE_MAP.getOrDefault("anyAtomicType", this);
    }
    
    /**
     * Checks if this type is one of the 19 XSD primitive types or anyAtomicType.
     *
     * @return true if this is a primitive type
     */
    private boolean isPrimitive() {
        if (!isBuiltIn()) {
            return false;
        }
        
        String typeName = getName();
        if (typeName == null) {
            return false;
        }
        
        // XSD primitive types + XPath/XDM abstract types
        switch (typeName) {
            case "anyAtomicType":
            case "anySimpleType":
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
            case "untypedAtomic":
                return true;
            default:
                return false;
        }
    }
    
    // Facet getters and setters
    
    /**
     * Returns the minLength facet value.
     *
     * @return the minimum length, or null if not set
     */
    public Integer getMinLength() { return minLength; }
    
    /**
     * Sets the minLength facet value.
     *
     * @param value the minimum length constraint
     */
    public void setMinLength(Integer value) { this.minLength = value; }
    
    /**
     * Returns the maxLength facet value.
     *
     * @return the maximum length, or null if not set
     */
    public Integer getMaxLength() { return maxLength; }
    
    /**
     * Sets the maxLength facet value.
     *
     * @param value the maximum length constraint
     */
    public void setMaxLength(Integer value) { this.maxLength = value; }
    
    /**
     * Returns the length facet value.
     *
     * @return the exact length, or null if not set
     */
    public Integer getLength() { return length; }
    
    /**
     * Sets the length facet value.
     *
     * @param value the exact length constraint
     */
    public void setLength(Integer value) { this.length = value; }
    
    /**
     * Returns the pattern facet value (regular expression).
     *
     * @return the pattern, or null if not set
     */
    public String getPattern() { return pattern; }
    
    /**
     * Sets the pattern facet value.
     *
     * @param value the regular expression pattern
     */
    public void setPattern(String value) {
        this.pattern = value;
        this.compiledPattern = null;
    }
    
    /**
     * Returns the enumeration facet values.
     *
     * @return the set of allowed values, or null if not set
     */
    public Set<String> getEnumeration() { return enumeration; }
    
    /**
     * Sets the enumeration facet values.
     *
     * @param value the set of allowed values
     */
    public void setEnumeration(Set<String> value) { this.enumeration = value; }
    
    /**
     * Adds a value to the enumeration facet.
     *
     * @param value the enumeration value to add
     */
    public void addEnumeration(String value) {
        if (enumeration == null) {
            enumeration = new HashSet<>();
        }
        enumeration.add(value);
    }
    
    /**
     * Returns the minInclusive facet value.
     *
     * @return the minimum inclusive value (as string), or null if not set
     */
    public String getMinInclusive() { return minInclusive; }
    
    /**
     * Sets the minInclusive facet value.
     *
     * @param value the minimum inclusive value (as string)
     */
    public void setMinInclusive(String value) { this.minInclusive = value; }
    
    /**
     * Returns the maxInclusive facet value.
     *
     * @return the maximum inclusive value (as string), or null if not set
     */
    public String getMaxInclusive() { return maxInclusive; }
    
    /**
     * Sets the maxInclusive facet value.
     *
     * @param value the maximum inclusive value (as string)
     */
    public void setMaxInclusive(String value) { this.maxInclusive = value; }
    
    /**
     * Returns the minExclusive facet value.
     *
     * @return the minimum exclusive value (as string), or null if not set
     */
    public String getMinExclusive() { return minExclusive; }
    
    /**
     * Sets the minExclusive facet value.
     *
     * @param value the minimum exclusive value (as string)
     */
    public void setMinExclusive(String value) { this.minExclusive = value; }
    
    /**
     * Returns the maxExclusive facet value.
     *
     * @return the maximum exclusive value (as string), or null if not set
     */
    public String getMaxExclusive() { return maxExclusive; }
    
    /**
     * Sets the maxExclusive facet value.
     *
     * @param value the maximum exclusive value (as string)
     */
    public void setMaxExclusive(String value) { this.maxExclusive = value; }
    
    /**
     * Returns the totalDigits facet value.
     *
     * @return the total number of digits, or null if not set
     */
    public Integer getTotalDigits() { return totalDigits; }
    
    /**
     * Sets the totalDigits facet value.
     *
     * @param value the total number of digits constraint
     */
    public void setTotalDigits(Integer value) { this.totalDigits = value; }
    
    /**
     * Returns the fractionDigits facet value.
     *
     * @return the number of fraction digits, or null if not set
     */
    public Integer getFractionDigits() { return fractionDigits; }
    
    /**
     * Sets the fractionDigits facet value.
     *
     * @param value the number of fraction digits constraint
     */
    public void setFractionDigits(Integer value) { this.fractionDigits = value; }
    
    /**
     * Returns the whitespace facet value.
     *
     * @return the whitespace handling mode, never null (defaults to PRESERVE)
     */
    public WhitespaceHandling getWhitespace() { return whitespace; }
    
    /**
     * Sets the whitespace facet value.
     *
     * @param value the whitespace handling mode
     */
    public void setWhitespace(WhitespaceHandling value) { this.whitespace = value; }
    
    /**
     * Validates a lexical value against this type.
     *
     * <p>This method checks the value against all applicable facets:
     * <ul>
     *   <li>Whitespace normalization (preserve, replace, collapse)</li>
     *   <li>Length constraints (length, minLength, maxLength)</li>
     *   <li>Pattern matching</li>
     *   <li>Enumeration membership</li>
     *   <li>Numeric range constraints (min/max inclusive/exclusive)</li>
     *   <li>Digit constraints (totalDigits, fractionDigits)</li>
     *   <li>Base type validation</li>
     * </ul>
     *
     * <p>For derived types, validation is performed against the base type
     * first, then against the facets of this type.
     *
     * @param value the lexical value to validate
     * @return null if valid, a descriptive error message if invalid
     */
    public String validate(String value) {
        if (value == null) {
            return "Value cannot be null";
        }
        
        // Apply whitespace normalization
        String normalized = normalizeWhitespace(value);
        
        // Get effective variety (follows base type chain for derived types)
        Variety effectiveVariety = getEffectiveVariety();
        
        // Handle LIST variety specially - length facets apply to item count
        if (effectiveVariety == Variety.LIST) {
            return validateList(normalized);
        }
        
        // Handle UNION variety - try each member type
        if (effectiveVariety == Variety.UNION) {
            return validateUnion(normalized);
        }
        
        // ATOMIC variety - check length facets on string length
        if (length != null && normalized.length() != length) {
            return "Value length " + normalized.length() + " does not match required length " + length;
        }
        if (minLength != null && normalized.length() < minLength) {
            return "Value length " + normalized.length() + " is less than minLength " + minLength;
        }
        if (maxLength != null && normalized.length() > maxLength) {
            return "Value length " + normalized.length() + " exceeds maxLength " + maxLength;
        }
        
        // Check pattern
        Pattern compiledPat = getCompiledPattern();
        if (compiledPat != null && !compiledPat.matcher(normalized).matches()) {
            return "Value does not match pattern: " + pattern;
        }
        
        // Check enumeration
        if (enumeration != null && !enumeration.contains(normalized)) {
            return "Value is not in enumeration: " + enumeration;
        }
        
        // Type-specific validation
        String typeError = validateType(normalized);
        if (typeError != null) {
            return typeError;
        }
        
        // Check numeric range facets
        String rangeError = validateNumericRange(normalized);
        if (rangeError != null) {
            return rangeError;
        }
        
        return null;
    }
    
    /**
     * Validates a value against a LIST variety type.
     *
     * <p>For list types:
     * <ul>
     *   <li>The value is split by whitespace into items</li>
     *   <li>Each item is validated against the itemType</li>
     *   <li>Length facets apply to the item count, not string length</li>
     * </ul>
     *
     * @param value the normalized value
     * @return null if valid, error message if invalid
     */
    private String validateList(String value) {
        // Split by whitespace - empty string means empty list
        String[] items;
        if (value.isEmpty()) {
            items = new String[0];
        } else {
            items = value.split("\\s+");
        }
        int itemCount = items.length;
        
        // Check length facets on item count
        if (length != null && itemCount != length) {
            return "List has " + itemCount + " items but requires exactly " + length;
        }
        if (minLength != null && itemCount < minLength) {
            return "List has " + itemCount + " items but requires at least " + minLength;
        }
        if (maxLength != null && itemCount > maxLength) {
            return "List has " + itemCount + " items but allows at most " + maxLength;
        }
        
        // Validate each item against the effective item type
        XSDSimpleType effectiveItemType = getEffectiveItemType();
        if (effectiveItemType != null) {
            for (int i = 0; i < items.length; i++) {
                String itemError = effectiveItemType.validate(items[i]);
                if (itemError != null) {
                    return "List item " + (i + 1) + " invalid: " + itemError;
                }
            }
        }
        
        // Check pattern on the whole value
        Pattern compiledPat = getCompiledPattern();
        if (compiledPat != null && !compiledPat.matcher(value).matches()) {
            return "List value does not match pattern: " + pattern;
        }
        
        // Check enumeration on the whole value
        if (enumeration != null && !enumeration.contains(value)) {
            return "List value is not in enumeration";
        }
        
        return null;
    }
    
    /**
     * Validates a value against a UNION variety type.
     *
     * <p>The value is valid if it's valid against any member type.
     *
     * @param value the normalized value
     * @return null if valid, error message if invalid
     */
    private String validateUnion(String value) {
        if (memberTypes == null || memberTypes.isEmpty()) {
            return "Union type has no member types";
        }
        
        // Try each member type
        StringBuilder errors = new StringBuilder();
        for (XSDSimpleType memberType : memberTypes) {
            String error = memberType.validate(value);
            if (error == null) {
                return null;  // Valid against this member
            }
            if (errors.length() > 0) {
                errors.append("; ");
            }
            errors.append(memberType.getName());
            errors.append(": ");
            errors.append(error);
        }
        
        return "Value does not match any union member type: " + errors;
    }
    
    /**
     * Validates numeric range facets (minInclusive, maxInclusive, minExclusive, maxExclusive).
     * Also checks inherited facets from base types.
     */
    private String validateNumericRange(String value) {
        // Only validate range for numeric types
        if (!isNumericType()) {
            return null;
        }
        
        try {
            BigDecimal numValue = new BigDecimal(value.trim());
            
            // Get effective facets (this type's facet or inherited from base)
            String effectiveMinInclusive = getEffectiveMinInclusive();
            String effectiveMaxInclusive = getEffectiveMaxInclusive();
            String effectiveMinExclusive = getEffectiveMinExclusive();
            String effectiveMaxExclusive = getEffectiveMaxExclusive();
            Integer effectiveTotalDigits = getEffectiveTotalDigits();
            Integer effectiveFractionDigits = getEffectiveFractionDigits();
            
            if (effectiveMinInclusive != null) {
                BigDecimal min = new BigDecimal(effectiveMinInclusive);
                if (numValue.compareTo(min) < 0) {
                    return "Value " + value + " is less than minInclusive " + effectiveMinInclusive;
                }
            }
            
            if (effectiveMaxInclusive != null) {
                BigDecimal max = new BigDecimal(effectiveMaxInclusive);
                if (numValue.compareTo(max) > 0) {
                    return "Value " + value + " is greater than maxInclusive " + effectiveMaxInclusive;
                }
            }
            
            if (effectiveMinExclusive != null) {
                BigDecimal min = new BigDecimal(effectiveMinExclusive);
                if (numValue.compareTo(min) <= 0) {
                    return "Value " + value + " is not greater than minExclusive " + effectiveMinExclusive;
                }
            }
            
            if (effectiveMaxExclusive != null) {
                BigDecimal max = new BigDecimal(effectiveMaxExclusive);
                if (numValue.compareTo(max) >= 0) {
                    return "Value " + value + " is not less than maxExclusive " + effectiveMaxExclusive;
                }
            }
            
            // Check totalDigits
            if (effectiveTotalDigits != null) {
                String normalized = numValue.abs().stripTrailingZeros().toPlainString().replace(".", "");
                if (normalized.length() > effectiveTotalDigits) {
                    return "Value " + value + " has more than " + effectiveTotalDigits + " total digits";
                }
            }
            
            // Check fractionDigits
            if (effectiveFractionDigits != null) {
                int scale = numValue.scale();
                if (scale > effectiveFractionDigits) {
                    return "Value " + value + " has more than " + effectiveFractionDigits + " fraction digits";
                }
            }
            
        } catch (NumberFormatException e) {
            // Not a numeric value - skip range checks
        }
        
        return null;
    }
    
    // Effective facet getters - returns local facet or inherited from base type
    
    private String getEffectiveMinInclusive() {
        if (minInclusive != null) {
            return minInclusive;
        }
        return baseType != null ? baseType.getEffectiveMinInclusive() : null;
    }
    
    private String getEffectiveMaxInclusive() {
        if (maxInclusive != null) {
            return maxInclusive;
        }
        return baseType != null ? baseType.getEffectiveMaxInclusive() : null;
    }
    
    private String getEffectiveMinExclusive() {
        if (minExclusive != null) {
            return minExclusive;
        }
        return baseType != null ? baseType.getEffectiveMinExclusive() : null;
    }
    
    private String getEffectiveMaxExclusive() {
        if (maxExclusive != null) {
            return maxExclusive;
        }
        return baseType != null ? baseType.getEffectiveMaxExclusive() : null;
    }
    
    private Integer getEffectiveTotalDigits() {
        if (totalDigits != null) {
            return totalDigits;
        }
        return baseType != null ? baseType.getEffectiveTotalDigits() : null;
    }
    
    private Integer getEffectiveFractionDigits() {
        if (fractionDigits != null) {
            return fractionDigits;
        }
        return baseType != null ? baseType.getEffectiveFractionDigits() : null;
    }
    
    /**
     * Checks if this type is numeric (integer, decimal, float, double, or derivations).
     */
    private boolean isNumericType() {
        // Check if any range facets are set (indicates this is meant for numeric types)
        if (minInclusive != null || maxInclusive != null || 
            minExclusive != null || maxExclusive != null ||
            totalDigits != null || fractionDigits != null) {
            return true;
        }
        
        // Check the type name
        String typeName = getName();
        if (typeName != null) {
            switch (typeName) {
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
            }
        }
        
        // Check base type
        if (baseType != null) {
            return baseType.isNumericType();
        }
        
        return false;
    }
    
    /**
     * Gets the compiled pattern for this type, compiling it if necessary.
     * This avoids repeatedly compiling the same pattern during validation.
     *
     * @return the compiled pattern, or null if no pattern facet is set
     */
    private Pattern getCompiledPattern() {
        if (pattern == null) {
            return null;
        }
        if (compiledPattern == null) {
            compiledPattern = Pattern.compile(pattern);
        }
        return compiledPattern;
    }
    
    private String normalizeWhitespace(String value) {
        switch (whitespace) {
            case REPLACE:
                return XSDTypeConverter.normalizeReplace(value);
            case COLLAPSE:
                return XSDTypeConverter.normalizeCollapse(value);
            default:
                return value;
        }
    }
    
    private String validateType(String value) {
        String typeName = getName();
        if (!isBuiltIn()) {
            // For derived types, validate against base
            if (baseType != null) {
                return baseType.validate(value);
            }
            return null;
        }
        
        try {
            switch (typeName) {
                case "boolean":
                    if (!"true".equals(value) && !"false".equals(value) && 
                        !"1".equals(value) && !"0".equals(value)) {
                        return "Invalid boolean: " + value;
                    }
                    break;
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
                    Long.parseLong(value);
                    break;
                case "decimal":
                    new BigDecimal(value);
                    break;
                case "float":
                    if (!"INF".equals(value) && !"-INF".equals(value) && !"NaN".equals(value)) {
                        Float.parseFloat(value);
                    }
                    break;
                case "double":
                    if (!"INF".equals(value) && !"-INF".equals(value) && !"NaN".equals(value)) {
                        Double.parseDouble(value);
                    }
                    break;
                case "anyURI":
                    new URI(value);
                    break;
                // String-based types don't need validation
                case "string":
                case "normalizedString":
                case "token":
                case "language":
                case "Name":
                case "NCName":
                case "ID":
                case "IDREF":
                case "IDREFS":
                case "NMTOKEN":
                case "NMTOKENS":
                case "ENTITY":
                case "ENTITIES":
                    break;
                // Date/time types - basic format check
                case "dateTime":
                case "date":
                case "time":
                case "gYear":
                case "gYearMonth":
                case "gMonth":
                case "gMonthDay":
                case "gDay":
                case "duration":
                    // Just accept for now - full validation is complex
                    break;
            }
        } catch (Exception e) {
            return "Invalid " + typeName + ": " + value + " (" + e.getMessage() + ")";
        }
        
        return null; // Valid
    }
    
    @Override
    public String toString() {
        return "XSDSimpleType[" + super.toString() + ", variety=" + variety + "]";
    }
}

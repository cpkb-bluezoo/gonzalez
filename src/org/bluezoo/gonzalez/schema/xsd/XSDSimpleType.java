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
    
    /** All XSD Part 2 built-in type names */
    public static final String[] BUILT_IN_TYPES = {
        // Primitive types
        "string", "boolean", "decimal", "float", "double",
        "duration", "dateTime", "time", "date",
        "gYearMonth", "gYear", "gMonthDay", "gDay", "gMonth",
        "hexBinary", "base64Binary", "anyURI", "QName", "NOTATION",
        // Derived types
        "normalizedString", "token", "language", "NMTOKEN", "NMTOKENS",
        "Name", "NCName", "ID", "IDREF", "IDREFS", "ENTITY", "ENTITIES",
        "integer", "nonPositiveInteger", "negativeInteger",
        "long", "int", "short", "byte",
        "nonNegativeInteger", "unsignedLong", "unsignedInt", "unsignedShort", "unsignedByte",
        "positiveInteger",
        // Special
        "anyType", "anySimpleType"
    };
    
    private static final Map<String, XSDSimpleType> BUILT_IN_TYPE_MAP = new HashMap<>();
    
    static {
        for (String typeName : BUILT_IN_TYPES) {
            BUILT_IN_TYPE_MAP.put(typeName, new XSDSimpleType(typeName, XSDSchema.XSD_NAMESPACE, null));
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
    
    /**
     * Whitespace handling modes.
     */
    public enum WhitespaceHandling {
        PRESERVE,
        REPLACE,
        COLLAPSE
    }
    
    /**
     * Creates an atomic simple type.
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
     */
    public static XSDSimpleType createList(String name, String namespaceURI, XSDSimpleType itemType) {
        return new XSDSimpleType(name, namespaceURI, Variety.LIST, null, itemType, null);
    }
    
    /**
     * Creates a union simple type.
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
     * Gets a built-in type by name.
     */
    public static XSDSimpleType getBuiltInType(String name) {
        return BUILT_IN_TYPE_MAP.get(name);
    }
    
    /**
     * Returns true if this is a built-in ID type.
     */
    public boolean isIdType() {
        return "ID".equals(getName()) && XSDSchema.XSD_NAMESPACE.equals(getNamespaceURI());
    }
    
    /**
     * Returns true if this is derived from xs:ID.
     */
    public boolean isDerivedFromId() {
        if (isIdType()) return true;
        if (baseType != null) {
            return baseType.isDerivedFromId();
        }
        return false;
    }
    
    @Override
    public boolean isSimpleType() {
        return true;
    }
    
    public Variety getVariety() {
        return variety;
    }
    
    public XSDSimpleType getBaseType() {
        return baseType;
    }
    
    /**
     * Sets the base type. Used during schema parsing for late binding.
     */
    public void setBaseType(XSDSimpleType baseType) {
        this.baseType = baseType;
    }
    
    public XSDSimpleType getItemType() {
        return itemType;
    }
    
    public List<XSDSimpleType> getMemberTypes() {
        return memberTypes;
    }
    
    // Facet getters and setters
    
    public Integer getMinLength() { return minLength; }
    public void setMinLength(Integer value) { this.minLength = value; }
    
    public Integer getMaxLength() { return maxLength; }
    public void setMaxLength(Integer value) { this.maxLength = value; }
    
    public Integer getLength() { return length; }
    public void setLength(Integer value) { this.length = value; }
    
    public String getPattern() { return pattern; }
    public void setPattern(String value) { this.pattern = value; }
    
    public Set<String> getEnumeration() { return enumeration; }
    public void setEnumeration(Set<String> value) { this.enumeration = value; }
    public void addEnumeration(String value) {
        if (enumeration == null) {
            enumeration = new HashSet<>();
        }
        enumeration.add(value);
    }
    
    public String getMinInclusive() { return minInclusive; }
    public void setMinInclusive(String value) { this.minInclusive = value; }
    
    public String getMaxInclusive() { return maxInclusive; }
    public void setMaxInclusive(String value) { this.maxInclusive = value; }
    
    public String getMinExclusive() { return minExclusive; }
    public void setMinExclusive(String value) { this.minExclusive = value; }
    
    public String getMaxExclusive() { return maxExclusive; }
    public void setMaxExclusive(String value) { this.maxExclusive = value; }
    
    public Integer getTotalDigits() { return totalDigits; }
    public void setTotalDigits(Integer value) { this.totalDigits = value; }
    
    public Integer getFractionDigits() { return fractionDigits; }
    public void setFractionDigits(Integer value) { this.fractionDigits = value; }
    
    public WhitespaceHandling getWhitespace() { return whitespace; }
    public void setWhitespace(WhitespaceHandling value) { this.whitespace = value; }
    
    /**
     * Validates a lexical value against this type.
     *
     * @param value the lexical value to validate
     * @return null if valid, error message if invalid
     */
    public String validate(String value) {
        if (value == null) {
            return "Value cannot be null";
        }
        
        // Apply whitespace normalization
        String normalized = normalizeWhitespace(value);
        
        // Check length facets
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
        if (pattern != null && !normalized.matches(pattern)) {
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
    
    private String normalizeWhitespace(String value) {
        switch (whitespace) {
            case REPLACE:
                return value.replaceAll("[\t\n\r]", " ");
            case COLLAPSE:
                return value.replaceAll("[\t\n\r]", " ").replaceAll(" +", " ").trim();
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

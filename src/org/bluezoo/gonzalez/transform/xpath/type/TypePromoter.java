/*
 * TypePromoter.java
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

import org.bluezoo.gonzalez.schema.xsd.XSDSimpleType;

/**
 * Handles XPath/XSLT type promotion rules.
 *
 * <p>Type promotion allows values of one type to be automatically converted
 * to another type in certain contexts. The main promotion rules are:
 *
 * <h3>Numeric Promotion</h3>
 * <p>Numeric types can be promoted up the following hierarchy:
 * <pre>
 * xs:integer → xs:decimal → xs:float → xs:double
 * </pre>
 *
 * <h3>String-like Promotion</h3>
 * <p>In XPath 2.0+, xs:anyURI can be promoted to xs:string in contexts
 * where a string is expected.
 *
 * <h3>Subtype Substitution</h3>
 * <p>Any derived type can be used where its base type is expected.
 * For example, xs:ID can be used where xs:NCName, xs:Name, xs:token,
 * or xs:string is expected. This is handled by {@link XSDSimpleType#isSubtypeOf}.
 *
 * <p>Type promotion is used in:
 * <ul>
 *   <li>Function parameter matching</li>
 *   <li>Arithmetic and comparison operators</li>
 *   <li>The {@code instance of} operator</li>
 *   <li>Variable/parameter type checking</li>
 * </ul>
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 * @see SequenceType
 * @see XSDSimpleType
 */
public class TypePromoter {
    
    /**
     * XSD namespace URI constant.
     */
    private static final String XS_NAMESPACE = "http://www.w3.org/2001/XMLSchema";
    
    /**
     * Checks if a value of the actual type can be promoted to the target type.
     *
     * <p>This includes:
     * <ul>
     *   <li>Numeric promotion (integer → decimal → float → double)</li>
     *   <li>anyURI → string promotion</li>
     *   <li>Subtype to base type (via type hierarchy)</li>
     * </ul>
     *
     * @param actualTypeNs the namespace URI of the actual type
     * @param actualTypeLocal the local name of the actual type
     * @param targetTypeNs the namespace URI of the target type
     * @param targetTypeLocal the local name of the target type
     * @return true if the actual type can be promoted to the target type
     */
    public static boolean canPromoteTo(String actualTypeNs, String actualTypeLocal,
                                       String targetTypeNs, String targetTypeLocal) {
        // Exact match - no promotion needed
        if (typesEqual(actualTypeNs, actualTypeLocal, targetTypeNs, targetTypeLocal)) {
            return true;
        }
        
        // Only handle XSD types
        if (!XS_NAMESPACE.equals(actualTypeNs) || !XS_NAMESPACE.equals(targetTypeNs)) {
            return false;
        }
        
        // Check subtype relationship (handles type hierarchy)
        if (isSubtype(actualTypeLocal, targetTypeLocal)) {
            return true;
        }
        
        // Check numeric promotion
        if (canPromoteNumeric(actualTypeLocal, targetTypeLocal)) {
            return true;
        }
        
        // Check string-like promotion (anyURI → string)
        if ("anyURI".equals(actualTypeLocal) && "string".equals(targetTypeLocal)) {
            return true;
        }
        
        return false;
    }
    
    /**
     * Checks if two types are equal (same namespace and local name).
     */
    private static boolean typesEqual(String ns1, String local1, String ns2, String local2) {
        String n1 = ns1 != null ? ns1 : "";
        String n2 = ns2 != null ? ns2 : "";
        return n1.equals(n2) && local1 != null && local1.equals(local2);
    }
    
    /**
     * Checks if actualType is a subtype of targetType using the type hierarchy.
     *
     * <p>This delegates to XSDSimpleType if available, with a fallback to
     * basic hardcoded checks.
     */
    private static boolean isSubtype(String actualType, String targetType) {
        XSDSimpleType actual = XSDSimpleType.getBuiltInType(actualType);
        XSDSimpleType target = XSDSimpleType.getBuiltInType(targetType);
        
        if (actual != null && target != null) {
            return actual.isSubtypeOf(target);
        }
        
        // Basic fallback checks for common cases
        if ("anyAtomicType".equals(targetType) || "anySimpleType".equals(targetType)) {
            return true;
        }
        
        return false;
    }
    
    /**
     * Checks if a numeric type can be promoted to another numeric type.
     *
     * <p>Numeric promotion chain:
     * <pre>
     * xs:integer → xs:decimal → xs:float → xs:double
     * </pre>
     *
     * <p>All integer-derived types (int, long, short, byte, positiveInteger, etc.)
     * can be promoted to decimal, float, or double.
     *
     * @param actualType the actual numeric type
     * @param targetType the target numeric type
     * @return true if promotion is allowed
     */
    private static boolean canPromoteNumeric(String actualType, String targetType) {
        if (actualType == null || targetType == null) {
            return false;
        }
        
        // Get the promotion levels
        int actualLevel = getNumericPromotionLevel(actualType);
        int targetLevel = getNumericPromotionLevel(targetType);
        
        // No promotion if not numeric types
        if (actualLevel == -1 || targetLevel == -1) {
            return false;
        }
        
        // Can promote if target level is higher
        return actualLevel <= targetLevel;
    }
    
    /**
     * Returns the numeric promotion level for a type.
     *
     * <p>Levels:
     * <ul>
     *   <li>0: integer and all integer-derived types</li>
     *   <li>1: decimal</li>
     *   <li>2: float</li>
     *   <li>3: double</li>
     *   <li>-1: not a numeric type</li>
     * </ul>
     *
     * @param typeName the type name
     * @return the promotion level, or -1 if not numeric
     */
    private static int getNumericPromotionLevel(String typeName) {
        switch (typeName) {
            // Level 0: integer types
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
                return 0;
            
            // Level 1: decimal
            case "decimal":
                return 1;
            
            // Level 2: float
            case "float":
                return 2;
            
            // Level 3: double
            case "double":
                return 3;
            
            default:
                return -1;  // Not a numeric type
        }
    }
    
    /**
     * Checks if a type is a numeric type that participates in numeric promotion.
     *
     * @param typeNs the type namespace URI
     * @param typeLocal the type local name
     * @return true if it's a promotable numeric type
     */
    public static boolean isNumericType(String typeNs, String typeLocal) {
        if (!XS_NAMESPACE.equals(typeNs)) {
            return false;
        }
        return getNumericPromotionLevel(typeLocal) >= 0;
    }
    
    /**
     * Gets the common type that two numeric types can be promoted to.
     *
     * <p>This is useful for binary arithmetic operators where both operands
     * need to be promoted to a common type.
     *
     * @param type1Ns namespace URI of first type
     * @param type1Local local name of first type
     * @param type2Ns namespace URI of second type
     * @param type2Local local name of second type
     * @return the common type local name, or null if not both numeric
     */
    public static String getCommonNumericType(String type1Ns, String type1Local,
                                              String type2Ns, String type2Local) {
        if (!XS_NAMESPACE.equals(type1Ns) || !XS_NAMESPACE.equals(type2Ns)) {
            return null;
        }
        
        int level1 = getNumericPromotionLevel(type1Local);
        int level2 = getNumericPromotionLevel(type2Local);
        
        if (level1 == -1 || level2 == -1) {
            return null;
        }
        
        // Return the higher level type
        int maxLevel = Math.max(level1, level2);
        switch (maxLevel) {
            case 0: return "integer";
            case 1: return "decimal";
            case 2: return "float";
            case 3: return "double";
            default: return null;
        }
    }
}

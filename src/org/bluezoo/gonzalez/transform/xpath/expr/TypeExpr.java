/*
 * TypeExpr.java
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

package org.bluezoo.gonzalez.transform.xpath.expr;

import org.bluezoo.gonzalez.schema.xsd.XSDSimpleType;
import org.bluezoo.gonzalez.transform.xpath.XPathContext;
import org.bluezoo.gonzalez.transform.xpath.type.*;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * Expression for XPath 2.0 type operations.
 *
 * <p>Supports four type operations:
 * <ul>
 *   <li>{@code instance of} - tests if value is instance of type</li>
 *   <li>{@code cast as} - casts value to type</li>
 *   <li>{@code castable as} - tests if value can be cast to type</li>
 *   <li>{@code treat as} - asserts value is of type (no runtime conversion)</li>
 * </ul>
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public class TypeExpr implements Expr {
    
    /**
     * Type operation kind.
     */
    public enum Kind {
        INSTANCE_OF,
        CAST_AS,
        CASTABLE_AS,
        TREAT_AS
    }
    
    private final Kind kind;
    private final Expr operand;
    private final SequenceType targetType;
    
    /**
     * Creates a type expression.
     *
     * @param kind the operation kind
     * @param operand the operand expression
     * @param targetType the target sequence type
     */
    public TypeExpr(Kind kind, Expr operand, SequenceType targetType) {
        this.kind = kind;
        this.operand = operand;
        this.targetType = targetType;
    }
    
    /**
     * Returns the type operation kind.
     *
     * @return INSTANCE_OF, CAST_AS, CASTABLE_AS, or TREAT_AS
     */
    public Kind getKind() {
        return kind;
    }
    
    /**
     * Returns the operand expression.
     *
     * @return the expression to test or cast
     */
    public Expr getOperand() {
        return operand;
    }
    
    /**
     * Returns the target sequence type.
     *
     * @return the target type for the operation
     */
    public SequenceType getTargetType() {
        return targetType;
    }
    
    @Override
    public XPathValue evaluate(XPathContext context) throws XPathException {
        XPathValue value = operand.evaluate(context);
        
        switch (kind) {
            case INSTANCE_OF:
                return evaluateInstanceOf(value, context);
                
            case CAST_AS:
                return evaluateCastAs(value, context);
                
            case CASTABLE_AS:
                return evaluateCastableAs(value, context);
                
            case TREAT_AS:
                return evaluateTreatAs(value, context);
                
            default:
                throw new XPathException("Unknown type operation: " + kind);
        }
    }
    
    /**
     * Evaluates 'instance of' - returns boolean.
     */
    private XPathValue evaluateInstanceOf(XPathValue value, XPathContext context) {
        boolean matches = targetType.matches(value);
        if (matches && isUserDefinedType()) {
            // For user-defined types, also validate against facets
            matches = validateAgainstSchemaType(value, context);
        }
        return XPathBoolean.of(matches);
    }
    
    /**
     * Evaluates 'cast as' - converts value to target type.
     */
    private XPathValue evaluateCastAs(XPathValue value, XPathContext context) throws XPathException {
        // Handle empty sequence
        if (value == null || isEmpty(value)) {
            if (targetType.allowsEmpty()) {
                return XPathSequence.empty();
            }
            throw new XPathException("Cannot cast empty sequence to " + targetType);
        }
        
        // Get the atomic value to cast
        String stringValue = value.asString();
        
        // Cast to target type
        return castToType(stringValue, targetType, context);
    }
    
    /**
     * Evaluates 'castable as' - returns boolean.
     */
    private XPathValue evaluateCastableAs(XPathValue value, XPathContext context) {
        try {
            // Handle empty sequence
            if (value == null || isEmpty(value)) {
                return XPathBoolean.of(targetType.allowsEmpty());
            }
            
            // Try the cast
            String stringValue = value.asString();
            castToType(stringValue, targetType, context);
            return XPathBoolean.TRUE;
        } catch (Exception e) {
            return XPathBoolean.FALSE;
        }
    }
    
    /**
     * Checks if the target type is a user-defined schema type (not built-in xs:).
     */
    private boolean isUserDefinedType() {
        if (targetType.getItemKind() != SequenceType.ItemKind.ATOMIC) {
            return false;
        }
        String ns = targetType.getNamespaceURI();
        return ns != null && !SequenceType.XS_NAMESPACE.equals(ns);
    }
    
    /**
     * Validates a value against a user-defined schema type's facets.
     */
    private boolean validateAgainstSchemaType(XPathValue value, XPathContext context) {
        if (context == null) {
            return true;  // No context to look up types
        }
        
        XSDSimpleType schemaType = context.getSchemaType(
            targetType.getNamespaceURI(), targetType.getLocalName());
        
        if (schemaType == null) {
            return true;  // Type not found, assume valid
        }
        
        String stringValue = value.asString();
        return schemaType.validate(stringValue) == null;  // null means valid
    }
    
    /**
     * Checks if a value is empty.
     */
    private boolean isEmpty(XPathValue value) {
        if (value instanceof XPathNodeSet) {
            return ((XPathNodeSet) value).size() == 0;
        }
        if (value instanceof XPathSequence) {
            return ((XPathSequence) value).size() == 0;
        }
        return false;
    }
    
    /**
     * Evaluates 'treat as' - asserts type without conversion.
     */
    private XPathValue evaluateTreatAs(XPathValue value, XPathContext context) throws XPathException {
        if (!targetType.matches(value)) {
            throw new XPathException("Value does not match type " + targetType + 
                " in 'treat as' expression");
        }
        // For user-defined types, also validate against facets
        if (isUserDefinedType() && !validateAgainstSchemaType(value, context)) {
            throw new XPathException("Value does not match type " + targetType + 
                " in 'treat as' expression");
        }
        return value;
    }
    
    /**
     * Casts a string value to the target type.
     */
    private XPathValue castToType(String value, SequenceType type, XPathContext context) throws XPathException {
        if (type.getItemKind() != SequenceType.ItemKind.ATOMIC) {
            throw new XPathException("Cannot cast to non-atomic type: " + type);
        }
        
        String typeName = type.getLocalName();
        String typeNs = type.getNamespaceURI();
        
        if (typeName == null) {
            throw new XPathException("Cannot cast to unspecified atomic type");
        }
        
        // Check for user-defined schema types
        if (typeNs != null && !SequenceType.XS_NAMESPACE.equals(typeNs)) {
            return castToSchemaType(value, typeNs, typeName, context);
        }
        
        // Built-in XSD types
        try {
            switch (typeName) {
                case "string":
                case "normalizedString":
                case "token":
                case "language":
                case "Name":
                case "NCName":
                case "NMTOKEN":
                case "ID":
                case "IDREF":
                case "ENTITY":
                    return new XPathString(value);
                    
                case "boolean":
                    if ("true".equals(value) || "1".equals(value)) {
                        return XPathBoolean.TRUE;
                    } else if ("false".equals(value) || "0".equals(value)) {
                        return XPathBoolean.FALSE;
                    }
                    throw new XPathException("Cannot cast '" + value + "' to xs:boolean");
                    
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
                    // Parse and return as XPathNumber (integer)
                    long intVal = Long.parseLong(value.trim());
                    return new XPathNumber(intVal);
                    
                case "decimal":
                    BigDecimal decVal = new BigDecimal(value.trim());
                    return new XPathNumber(decVal.doubleValue());
                    
                case "float":
                    if ("INF".equals(value)) {
                        return new XPathNumber(Float.POSITIVE_INFINITY);
                    } else if ("-INF".equals(value)) {
                        return new XPathNumber(Float.NEGATIVE_INFINITY);
                    } else if ("NaN".equals(value)) {
                        return new XPathNumber(Float.NaN);
                    }
                    return new XPathNumber(Float.parseFloat(value.trim()));
                    
                case "double":
                    if ("INF".equals(value)) {
                        return new XPathNumber(Double.POSITIVE_INFINITY);
                    } else if ("-INF".equals(value)) {
                        return new XPathNumber(Double.NEGATIVE_INFINITY);
                    } else if ("NaN".equals(value)) {
                        return new XPathNumber(Double.NaN);
                    }
                    return new XPathNumber(Double.parseDouble(value.trim()));
                    
                case "date":
                case "dateTime":
                case "time":
                case "duration":
                case "gYear":
                case "gYearMonth":
                case "gMonth":
                case "gMonthDay":
                case "gDay":
                    // Return as string for now - proper date handling would need more work
                    return new XPathString(value);
                    
                case "anyURI":
                case "QName":
                case "untypedAtomic":
                    return new XPathString(value);
                    
                default:
                    // Unknown type - return as string
                    return new XPathString(value);
            }
        } catch (NumberFormatException e) {
            throw new XPathException("Cannot cast '" + value + "' to xs:" + typeName + 
                ": " + e.getMessage());
        }
    }
    
    /**
     * Casts a value to a user-defined schema type.
     */
    private XPathValue castToSchemaType(String value, String namespaceURI, 
            String typeName, XPathContext context) throws XPathException {
        
        if (context == null) {
            throw new XPathException("No context available to look up type " + 
                "{" + namespaceURI + "}" + typeName);
        }
        
        XSDSimpleType schemaType = context.getSchemaType(namespaceURI, typeName);
        
        if (schemaType == null) {
            throw new XPathException("Unknown type: {" + namespaceURI + "}" + typeName);
        }
        
        // Validate against the schema type's facets
        String validationError = schemaType.validate(value);
        if (validationError != null) {
            throw new XPathException("Value '" + value + "' is not valid for type " +
                "{" + namespaceURI + "}" + typeName + ": " + validationError);
        }
        
        // Cast to the base type
        return castToBaseType(value, schemaType);
    }
    
    /**
     * Casts a value to the base type of a schema type.
     */
    private XPathValue castToBaseType(String value, XSDSimpleType schemaType) throws XPathException {
        // Walk up the type hierarchy to find the primitive base type
        XSDSimpleType type = schemaType;
        String primitiveTypeName = "string";  // Default
        
        while (type != null) {
            String name = type.getName();
            if (name != null && isPrimitiveTypeName(name)) {
                primitiveTypeName = name;
                break;
            }
            type = type.getBaseType();
        }
        
        // Cast to appropriate XPath value based on base type
        try {
            switch (primitiveTypeName) {
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
                    return new XPathNumber(Long.parseLong(value.trim()));
                    
                case "decimal":
                    return new XPathNumber(new BigDecimal(value.trim()).doubleValue());
                    
                case "float":
                case "double":
                    return new XPathNumber(Double.parseDouble(value.trim()));
                    
                case "boolean":
                    if ("true".equals(value) || "1".equals(value)) {
                        return XPathBoolean.TRUE;
                    } else if ("false".equals(value) || "0".equals(value)) {
                        return XPathBoolean.FALSE;
                    }
                    throw new XPathException("Cannot cast '" + value + "' to boolean");
                    
                default:
                    return new XPathString(value);
            }
        } catch (NumberFormatException e) {
            throw new XPathException("Cannot cast '" + value + "' to " + primitiveTypeName + 
                ": " + e.getMessage());
        }
    }
    
    /**
     * Checks if a type name is a primitive XSD type.
     */
    private boolean isPrimitiveTypeName(String name) {
        switch (name) {
            case "string":
            case "boolean":
            case "decimal":
            case "float":
            case "double":
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
            case "date":
            case "dateTime":
            case "time":
            case "duration":
            case "anyURI":
            case "QName":
                return true;
            default:
                return false;
        }
    }
    
    @Override
    public String toString() {
        switch (kind) {
            case INSTANCE_OF:
                return operand + " instance of " + targetType;
            case CAST_AS:
                return operand + " cast as " + targetType;
            case CASTABLE_AS:
                return operand + " castable as " + targetType;
            case TREAT_AS:
                return operand + " treat as " + targetType;
            default:
                return operand + " " + kind + " " + targetType;
        }
    }
}

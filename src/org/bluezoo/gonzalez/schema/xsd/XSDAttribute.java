/*
 * XSDAttribute.java
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

/**
 * Represents an XSD attribute declaration.
 *
 * <p>An attribute declaration specifies:
 * <ul>
 *   <li>The attribute name</li>
 *   <li>The attribute type (always a simple type)</li>
 *   <li>Use (required, optional, prohibited)</li>
 *   <li>Default or fixed value</li>
 * </ul>
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public class XSDAttribute {
    
    /**
     * Attribute use mode.
     *
     * <p>This enum defines whether an attribute must be present, is optional,
     * or is prohibited in instance documents.
     */
    public enum Use {
        /**
         * Required attribute.
         *
         * <p>The attribute must be present in instance documents.
         */
        REQUIRED,
        
        /**
         * Optional attribute.
         *
         * <p>The attribute may be present or absent in instance documents.
         * If absent and a default value is defined, the default is used.
         */
        OPTIONAL,
        
        /**
         * Prohibited attribute.
         *
         * <p>The attribute must not be present in instance documents.
         * Used in derived types to prohibit attributes inherited from base types.
         */
        PROHIBITED
    }
    
    private final String name;
    private final String namespaceURI;
    private XSDSimpleType type;
    private String typeName;  // For forward references
    
    private Use use = Use.OPTIONAL;
    private String defaultValue;
    private String fixedValue;
    
    // Form (qualified/unqualified) - if null, use schema's attributeFormDefault
    private String form;
    
    /**
     * Creates an attribute declaration.
     *
     * @param name the attribute name
     * @param namespaceURI the attribute namespace (usually null for local attributes)
     */
    public XSDAttribute(String name, String namespaceURI) {
        this.name = name;
        this.namespaceURI = namespaceURI;
    }
    
    /**
     * Returns the attribute name.
     *
     * @return the local name of this attribute
     */
    public String getName() {
        return name;
    }
    
    /**
     * Returns the namespace URI.
     *
     * @return the namespace URI, or null for local attributes
     */
    public String getNamespaceURI() {
        return namespaceURI;
    }
    
    /**
     * Returns the attribute's simple type.
     *
     * @return the type, or null if not yet resolved
     */
    public XSDSimpleType getType() {
        return type;
    }
    
    /**
     * Sets the attribute's simple type.
     *
     * @param type the type to set
     */
    public void setType(XSDSimpleType type) {
        this.type = type;
    }
    
    /**
     * Returns the type name for forward references.
     *
     * @return the type name before resolution, or null
     */
    public String getTypeName() {
        return typeName;
    }
    
    /**
     * Sets the type name for forward references.
     *
     * @param typeName the type name to resolve later
     */
    public void setTypeName(String typeName) {
        this.typeName = typeName;
    }
    
    /**
     * Returns the attribute usage constraint.
     *
     * @return the use (OPTIONAL, REQUIRED, or PROHIBITED)
     */
    public Use getUse() {
        return use;
    }
    
    /**
     * Sets the attribute usage constraint.
     *
     * @param use the use constraint
     */
    public void setUse(Use use) {
        this.use = use;
    }
    
    /**
     * Returns true if this attribute is required.
     *
     * @return true if use is REQUIRED
     */
    public boolean isRequired() {
        return use == Use.REQUIRED;
    }
    
    /**
     * Returns true if this attribute is prohibited.
     *
     * @return true if use is PROHIBITED
     */
    public boolean isProhibited() {
        return use == Use.PROHIBITED;
    }
    
    /**
     * Returns the default value for this attribute.
     *
     * @return the default value, or null if none
     */
    public String getDefaultValue() {
        return defaultValue;
    }
    
    /**
     * Sets the default value for this attribute.
     *
     * @param defaultValue the default value to use when attribute is omitted
     */
    public void setDefaultValue(String defaultValue) {
        this.defaultValue = defaultValue;
    }
    
    /**
     * Returns the fixed value for this attribute.
     *
     * @return the fixed value, or null if none
     */
    public String getFixedValue() {
        return fixedValue;
    }
    
    /**
     * Sets the fixed value for this attribute.
     *
     * @param fixedValue the required value for this attribute
     */
    public void setFixedValue(String fixedValue) {
        this.fixedValue = fixedValue;
    }
    
    /**
     * Returns the form (qualified/unqualified) for this attribute.
     *
     * @return "qualified" or "unqualified", or null to use schema default
     */
    public String getForm() {
        return form;
    }
    
    /**
     * Sets the form for this attribute.
     *
     * @param form "qualified" or "unqualified"
     */
    public void setForm(String form) {
        this.form = form;
    }
    
    /**
     * Returns true if this attribute has xs:ID type.
     *
     * @return true if the type is derived from xs:ID
     */
    public boolean isIdAttribute() {
        return type != null && type.isDerivedFromId();
    }
    
    /**
     * Validates an attribute value.
     *
     * @param value the attribute value
     * @return null if valid, error message if invalid
     */
    public String validate(String value) {
        // Check fixed value
        if (fixedValue != null && !fixedValue.equals(value)) {
            return "Attribute " + name + " must have fixed value: " + fixedValue;
        }
        
        // Validate against type
        if (type != null) {
            return type.validate(value);
        }
        
        return null; // Valid
    }
    
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("XSDAttribute[");
        if (namespaceURI != null) {
            sb.append("{").append(namespaceURI).append("}");
        }
        sb.append(name);
        if (type != null) {
            sb.append(" : ").append(type.getName());
        } else if (typeName != null) {
            sb.append(" : ").append(typeName);
        }
        if (use == Use.REQUIRED) {
            sb.append(" REQUIRED");
        }
        sb.append("]");
        return sb.toString();
    }
}

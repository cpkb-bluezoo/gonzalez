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
     */
    public enum Use {
        REQUIRED,
        OPTIONAL,
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
    
    public String getName() {
        return name;
    }
    
    public String getNamespaceURI() {
        return namespaceURI;
    }
    
    public XSDSimpleType getType() {
        return type;
    }
    
    public void setType(XSDSimpleType type) {
        this.type = type;
    }
    
    public String getTypeName() {
        return typeName;
    }
    
    public void setTypeName(String typeName) {
        this.typeName = typeName;
    }
    
    public Use getUse() {
        return use;
    }
    
    public void setUse(Use use) {
        this.use = use;
    }
    
    public boolean isRequired() {
        return use == Use.REQUIRED;
    }
    
    public boolean isProhibited() {
        return use == Use.PROHIBITED;
    }
    
    public String getDefaultValue() {
        return defaultValue;
    }
    
    public void setDefaultValue(String defaultValue) {
        this.defaultValue = defaultValue;
    }
    
    public String getFixedValue() {
        return fixedValue;
    }
    
    public void setFixedValue(String fixedValue) {
        this.fixedValue = fixedValue;
    }
    
    public String getForm() {
        return form;
    }
    
    public void setForm(String form) {
        this.form = form;
    }
    
    /**
     * Returns true if this attribute has xs:ID type.
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

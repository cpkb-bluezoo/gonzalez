/*
 * XSDElement.java
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
 * Represents an XSD element declaration.
 *
 * <p>An element declaration specifies:
 * <ul>
 *   <li>The element name</li>
 *   <li>The element type (simple or complex)</li>
 *   <li>Occurrence constraints (minOccurs, maxOccurs)</li>
 *   <li>Default or fixed value</li>
 *   <li>Whether the element is nillable</li>
 * </ul>
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public class XSDElement {
    
    private final String name;
    private final String namespaceURI;
    private XSDType type;
    private String typeName;  // For forward references
    
    private int minOccurs = 1;
    private int maxOccurs = 1;  // -1 = unbounded
    
    private String defaultValue;
    private String fixedValue;
    private boolean nillable = false;
    private boolean isAbstract = false;
    
    // Form (qualified/unqualified) - if null, use schema's elementFormDefault
    private String form;
    
    /**
     * Creates an element declaration.
     *
     * @param name the element name
     * @param namespaceURI the element namespace
     */
    public XSDElement(String name, String namespaceURI) {
        this.name = name;
        this.namespaceURI = namespaceURI;
    }
    
    public String getName() {
        return name;
    }
    
    public String getNamespaceURI() {
        return namespaceURI;
    }
    
    public XSDType getType() {
        return type;
    }
    
    public void setType(XSDType type) {
        this.type = type;
    }
    
    public String getTypeName() {
        return typeName;
    }
    
    public void setTypeName(String typeName) {
        this.typeName = typeName;
    }
    
    public int getMinOccurs() {
        return minOccurs;
    }
    
    public void setMinOccurs(int minOccurs) {
        this.minOccurs = minOccurs;
    }
    
    public int getMaxOccurs() {
        return maxOccurs;
    }
    
    public void setMaxOccurs(int maxOccurs) {
        this.maxOccurs = maxOccurs;
    }
    
    public boolean isOptional() {
        return minOccurs == 0;
    }
    
    public boolean isRepeatable() {
        return maxOccurs != 1;
    }
    
    public boolean isUnbounded() {
        return maxOccurs == -1;
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
    
    public boolean isNillable() {
        return nillable;
    }
    
    public void setNillable(boolean nillable) {
        this.nillable = nillable;
    }
    
    public boolean isAbstract() {
        return isAbstract;
    }
    
    public void setAbstract(boolean isAbstract) {
        this.isAbstract = isAbstract;
    }
    
    public String getForm() {
        return form;
    }
    
    public void setForm(String form) {
        this.form = form;
    }
    
    /**
     * Returns true if this element has a simple type.
     */
    public boolean hasSimpleType() {
        return type != null && type.isSimpleType();
    }
    
    /**
     * Returns true if this element has a complex type.
     */
    public boolean hasComplexType() {
        return type != null && type.isComplexType();
    }
    
    /**
     * Validates the element's text content.
     *
     * @param value the text content
     * @return null if valid, error message if invalid
     */
    public String validateContent(String value) {
        // Check fixed value
        if (fixedValue != null && !fixedValue.equals(value)) {
            return "Element " + name + " must have fixed value: " + fixedValue;
        }
        
        // Validate against type
        if (type instanceof XSDSimpleType) {
            return ((XSDSimpleType) type).validate(value);
        }
        
        if (type instanceof XSDComplexType) {
            XSDComplexType ct = (XSDComplexType) type;
            if (ct.getContentType() == XSDComplexType.ContentType.SIMPLE) {
                XSDSimpleType simpleContent = ct.getSimpleContentType();
                if (simpleContent != null) {
                    return simpleContent.validate(value);
                }
            }
        }
        
        return null; // Valid or no type to check against
    }
    
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("XSDElement[");
        if (namespaceURI != null) {
            sb.append("{").append(namespaceURI).append("}");
        }
        sb.append(name);
        if (type != null) {
            sb.append(" : ").append(type.getName());
        } else if (typeName != null) {
            sb.append(" : ").append(typeName);
        }
        sb.append("]");
        return sb.toString();
    }
}

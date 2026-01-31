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
    
    /**
     * Returns the element name.
     *
     * @return the local name of this element
     */
    public String getName() {
        return name;
    }
    
    /**
     * Returns the namespace URI.
     *
     * @return the namespace URI, or null if in no namespace
     */
    public String getNamespaceURI() {
        return namespaceURI;
    }
    
    /**
     * Returns the element's type.
     *
     * @return the type (simple or complex), or null if not yet resolved
     */
    public XSDType getType() {
        return type;
    }
    
    /**
     * Sets the element's type.
     *
     * @param type the type to set
     */
    public void setType(XSDType type) {
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
     * Returns the minimum occurrence count.
     *
     * @return minOccurs value (default is 1)
     */
    public int getMinOccurs() {
        return minOccurs;
    }
    
    /**
     * Sets the minimum occurrence count.
     *
     * @param minOccurs the minimum occurrences (0 or greater)
     */
    public void setMinOccurs(int minOccurs) {
        this.minOccurs = minOccurs;
    }
    
    /**
     * Returns the maximum occurrence count.
     *
     * @return maxOccurs value (default is 1, -1 means unbounded)
     */
    public int getMaxOccurs() {
        return maxOccurs;
    }
    
    /**
     * Sets the maximum occurrence count.
     *
     * @param maxOccurs the maximum occurrences (-1 for unbounded)
     */
    public void setMaxOccurs(int maxOccurs) {
        this.maxOccurs = maxOccurs;
    }
    
    /**
     * Returns true if this element is optional (minOccurs = 0).
     *
     * @return true if the element may be omitted
     */
    public boolean isOptional() {
        return minOccurs == 0;
    }
    
    /**
     * Returns true if this element can occur more than once.
     *
     * @return true if maxOccurs is not 1
     */
    public boolean isRepeatable() {
        return maxOccurs != 1;
    }
    
    /**
     * Returns true if this element can occur any number of times.
     *
     * @return true if maxOccurs is unbounded (-1)
     */
    public boolean isUnbounded() {
        return maxOccurs == -1;
    }
    
    /**
     * Returns the default value for this element.
     *
     * @return the default value, or null if none
     */
    public String getDefaultValue() {
        return defaultValue;
    }
    
    /**
     * Sets the default value for this element.
     *
     * @param defaultValue the default value to use when element is empty
     */
    public void setDefaultValue(String defaultValue) {
        this.defaultValue = defaultValue;
    }
    
    /**
     * Returns the fixed value for this element.
     *
     * @return the fixed value, or null if none
     */
    public String getFixedValue() {
        return fixedValue;
    }
    
    /**
     * Sets the fixed value for this element.
     *
     * @param fixedValue the required value for this element
     */
    public void setFixedValue(String fixedValue) {
        this.fixedValue = fixedValue;
    }
    
    /**
     * Returns true if this element is nillable (can have xsi:nil="true").
     *
     * @return true if nillable
     */
    public boolean isNillable() {
        return nillable;
    }
    
    /**
     * Sets whether this element is nillable.
     *
     * @param nillable true to allow xsi:nil="true"
     */
    public void setNillable(boolean nillable) {
        this.nillable = nillable;
    }
    
    /**
     * Returns true if this element is abstract.
     *
     * @return true if abstract (cannot appear directly in instance documents)
     */
    public boolean isAbstract() {
        return isAbstract;
    }
    
    /**
     * Sets whether this element is abstract.
     *
     * @param isAbstract true if element is abstract
     */
    public void setAbstract(boolean isAbstract) {
        this.isAbstract = isAbstract;
    }
    
    /**
     * Returns the form (qualified/unqualified) for this element.
     *
     * @return "qualified" or "unqualified", or null to use schema default
     */
    public String getForm() {
        return form;
    }
    
    /**
     * Sets the form for this element.
     *
     * @param form "qualified" or "unqualified"
     */
    public void setForm(String form) {
        this.form = form;
    }
    
    /**
     * Returns true if this element has a simple type.
     *
     * @return true if the type is a simple type
     */
    public boolean hasSimpleType() {
        return type != null && type.isSimpleType();
    }
    
    /**
     * Returns true if this element has a complex type.
     *
     * @return true if the type is a complex type
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

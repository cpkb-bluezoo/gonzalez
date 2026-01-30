/*
 * XSDSchema.java
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

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Represents an XML Schema (XSD) document.
 *
 * <p>Contains the schema's target namespace and all global element,
 * attribute, and type declarations.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public class XSDSchema {
    
    /** XML Schema namespace URI */
    public static final String XSD_NAMESPACE = "http://www.w3.org/2001/XMLSchema";
    
    /** XML Schema Instance namespace URI */
    public static final String XSI_NAMESPACE = "http://www.w3.org/2001/XMLSchema-instance";
    
    private final String targetNamespace;
    private final Map<String, XSDElement> globalElements = new HashMap<>();
    private final Map<String, XSDAttribute> globalAttributes = new HashMap<>();
    private final Map<String, XSDType> types = new HashMap<>();
    
    // Schema-level settings
    private String elementFormDefault = "unqualified";
    private String attributeFormDefault = "unqualified";
    
    /**
     * Creates a schema with the given target namespace.
     *
     * @param targetNamespace the target namespace URI, or null for no namespace
     */
    public XSDSchema(String targetNamespace) {
        this.targetNamespace = targetNamespace;
        registerBuiltInTypes();
    }
    
    /**
     * Registers XSD built-in types.
     */
    private void registerBuiltInTypes() {
        // Register all XSD Part 2 built-in types
        for (String typeName : XSDSimpleType.BUILT_IN_TYPES) {
            types.put(typeName, XSDSimpleType.getBuiltInType(typeName));
        }
    }
    
    /**
     * Returns the target namespace.
     */
    public String getTargetNamespace() {
        return targetNamespace;
    }
    
    /**
     * Returns the element form default.
     */
    public String getElementFormDefault() {
        return elementFormDefault;
    }
    
    /**
     * Sets the element form default.
     */
    public void setElementFormDefault(String value) {
        this.elementFormDefault = value;
    }
    
    /**
     * Returns the attribute form default.
     */
    public String getAttributeFormDefault() {
        return attributeFormDefault;
    }
    
    /**
     * Sets the attribute form default.
     */
    public void setAttributeFormDefault(String value) {
        this.attributeFormDefault = value;
    }
    
    /**
     * Adds a global element declaration.
     */
    public void addElement(XSDElement element) {
        globalElements.put(element.getName(), element);
    }
    
    /**
     * Gets a global element by name.
     */
    public XSDElement getElement(String name) {
        return globalElements.get(name);
    }
    
    /**
     * Returns all global elements.
     */
    public Map<String, XSDElement> getElements() {
        return Collections.unmodifiableMap(globalElements);
    }
    
    /**
     * Adds a global attribute declaration.
     */
    public void addAttribute(XSDAttribute attribute) {
        globalAttributes.put(attribute.getName(), attribute);
    }
    
    /**
     * Gets a global attribute by name.
     */
    public XSDAttribute getAttribute(String name) {
        return globalAttributes.get(name);
    }
    
    /**
     * Returns all global attributes.
     */
    public Map<String, XSDAttribute> getAttributes() {
        return Collections.unmodifiableMap(globalAttributes);
    }
    
    /**
     * Adds a named type (simple or complex).
     */
    public void addType(String name, XSDType type) {
        types.put(name, type);
    }
    
    /**
     * Gets a type by name.
     */
    public XSDType getType(String name) {
        return types.get(name);
    }
    
    /**
     * Returns all types.
     */
    public Map<String, XSDType> getTypes() {
        return Collections.unmodifiableMap(types);
    }
    
    /**
     * Resolves an element declaration for the given namespace and local name.
     * Takes into account elementFormDefault.
     *
     * @param namespaceURI the element namespace
     * @param localName the element local name
     * @return the element declaration, or null if not found
     */
    public XSDElement resolveElement(String namespaceURI, String localName) {
        // Check if namespace matches target namespace
        boolean namespaceMatches = (targetNamespace == null && (namespaceURI == null || namespaceURI.isEmpty()))
            || (targetNamespace != null && targetNamespace.equals(namespaceURI));
        
        if (namespaceMatches) {
            return globalElements.get(localName);
        }
        return null;
    }
    
    @Override
    public String toString() {
        return "XSDSchema[targetNamespace=" + targetNamespace + 
               ", elements=" + globalElements.size() +
               ", types=" + types.size() + "]";
    }
}

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
    
    /**
     * XML Schema namespace URI.
     *
     * <p>This is the namespace URI for XML Schema definitions (xs: prefix).
     * All XSD built-in types and schema elements belong to this namespace.
     */
    public static final String XSD_NAMESPACE = "http://www.w3.org/2001/XMLSchema";
    
    /**
     * XML Schema Instance namespace URI.
     *
     * <p>This is the namespace URI for XML Schema Instance attributes (xsi: prefix),
     * such as xsi:type, xsi:nil, xsi:schemaLocation, etc.
     */
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
     * <p>The schema is initialized with all XSD Part 2 built-in types registered.
     *
     * @param targetNamespace the target namespace URI, or null for no namespace
     */
    public XSDSchema(String targetNamespace) {
        this.targetNamespace = targetNamespace;
        registerBuiltInTypes();
    }
    
    /**
     * Registers XSD built-in types.
     *
     * <p>This method is called during construction to make all XSD Part 2
     * built-in types available for reference.
     */
    private void registerBuiltInTypes() {
        // Register all XSD Part 2 built-in types
        for (String typeName : XSDSimpleType.BUILT_IN_TYPES) {
            types.put(typeName, XSDSimpleType.getBuiltInType(typeName));
        }
    }
    
    /**
     * Returns the target namespace of this schema.
     *
     * @return the target namespace URI, or null for no namespace schemas
     */
    public String getTargetNamespace() {
        return targetNamespace;
    }
    
    /**
     * Returns the element form default.
     *
     * <p>This determines whether local element declarations are qualified
     * by default. Can be "qualified" or "unqualified".
     *
     * @return the element form default (defaults to "unqualified")
     */
    public String getElementFormDefault() {
        return elementFormDefault;
    }
    
    /**
     * Sets the element form default.
     *
     * @param value "qualified" or "unqualified"
     */
    public void setElementFormDefault(String value) {
        this.elementFormDefault = value;
    }
    
    /**
     * Returns the attribute form default.
     *
     * <p>This determines whether local attribute declarations are qualified
     * by default. Can be "qualified" or "unqualified".
     *
     * @return the attribute form default (defaults to "unqualified")
     */
    public String getAttributeFormDefault() {
        return attributeFormDefault;
    }
    
    /**
     * Sets the attribute form default.
     *
     * @param value "qualified" or "unqualified"
     */
    public void setAttributeFormDefault(String value) {
        this.attributeFormDefault = value;
    }
    
    /**
     * Adds a global element declaration to this schema.
     *
     * <p>Global elements can be referenced from anywhere in the schema and
     * can serve as root elements in instance documents.
     *
     * @param element the global element declaration
     */
    public void addElement(XSDElement element) {
        globalElements.put(element.getName(), element);
    }
    
    /**
     * Gets a global element declaration by name.
     *
     * @param name the element name (local part)
     * @return the element declaration, or null if not found
     */
    public XSDElement getElement(String name) {
        return globalElements.get(name);
    }
    
    /**
     * Returns all global element declarations.
     *
     * @return an unmodifiable map of element names to declarations
     */
    public Map<String, XSDElement> getElements() {
        return Collections.unmodifiableMap(globalElements);
    }
    
    /**
     * Adds a global attribute declaration to this schema.
     *
     * <p>Global attributes can be referenced from complex type definitions.
     *
     * @param attribute the global attribute declaration
     */
    public void addAttribute(XSDAttribute attribute) {
        globalAttributes.put(attribute.getName(), attribute);
    }
    
    /**
     * Gets a global attribute declaration by name.
     *
     * @param name the attribute name (local part)
     * @return the attribute declaration, or null if not found
     */
    public XSDAttribute getAttribute(String name) {
        return globalAttributes.get(name);
    }
    
    /**
     * Returns all global attribute declarations.
     *
     * @return an unmodifiable map of attribute names to declarations
     */
    public Map<String, XSDAttribute> getAttributes() {
        return Collections.unmodifiableMap(globalAttributes);
    }
    
    /**
     * Adds a named type (simple or complex) to this schema.
     *
     * <p>Named types can be referenced from element and attribute declarations.
     *
     * @param name the type name (local part)
     * @param type the type definition
     */
    public void addType(String name, XSDType type) {
        types.put(name, type);
    }
    
    /**
     * Gets a type definition by name.
     *
     * <p>This searches both user-defined types and built-in types.
     *
     * @param name the type name (local part)
     * @return the type definition, or null if not found
     */
    public XSDType getType(String name) {
        return types.get(name);
    }
    
    /**
     * Returns all type definitions (including built-in types).
     *
     * @return an unmodifiable map of type names to definitions
     */
    public Map<String, XSDType> getTypes() {
        return Collections.unmodifiableMap(types);
    }
    
    /**
     * Resolves an element declaration for the given namespace and local name.
     *
     * <p>This method takes into account the elementFormDefault setting to
     * determine whether the element should be qualified. It searches for
     * global elements in the target namespace.
     *
     * @param namespaceURI the element namespace URI (may be null)
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

/*
 * XSDType.java
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
 * Base class for XSD type definitions.
 *
 * <p>XSD has two kinds of types:
 * <ul>
 *   <li>{@link XSDSimpleType} - for atomic values, lists, or unions</li>
 *   <li>{@link XSDComplexType} - for elements with attributes and/or child elements</li>
 * </ul>
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public abstract class XSDType {
    
    private final String name;
    private final String namespaceURI;
    
    /**
     * Creates a type with the given name.
     *
     * @param name the type name (local part)
     * @param namespaceURI the type namespace URI
     */
    protected XSDType(String name, String namespaceURI) {
        this.name = name;
        this.namespaceURI = namespaceURI;
    }
    
    /**
     * Returns the type name (local part).
     */
    public String getName() {
        return name;
    }
    
    /**
     * Returns the type namespace URI.
     */
    public String getNamespaceURI() {
        return namespaceURI;
    }
    
    /**
     * Returns true if this is a simple type.
     */
    public abstract boolean isSimpleType();
    
    /**
     * Returns true if this is a complex type.
     */
    public boolean isComplexType() {
        return !isSimpleType();
    }
    
    /**
     * Returns true if this is a built-in XSD type.
     */
    public boolean isBuiltIn() {
        return XSDSchema.XSD_NAMESPACE.equals(namespaceURI);
    }
    
    @Override
    public String toString() {
        if (namespaceURI != null) {
            return "{" + namespaceURI + "}" + name;
        }
        return name;
    }
}

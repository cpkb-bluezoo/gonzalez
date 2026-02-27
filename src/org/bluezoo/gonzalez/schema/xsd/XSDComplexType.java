/*
 * XSDComplexType.java
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Represents an XSD complex type definition.
 *
 * <p>Complex types can have:
 * <ul>
 *   <li>Simple content (text with attributes)</li>
 *   <li>Complex content (child elements, possibly with attributes)</li>
 *   <li>Mixed content (text and elements interleaved)</li>
 *   <li>Empty content (attributes only)</li>
 * </ul>
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public class XSDComplexType extends XSDType {
    
    /**
     * Content type of the complex type.
     *
     * <p>This enum represents the different kinds of content that a complex
     * type can have, determining what is allowed inside elements of this type.
     */
    public enum ContentType {
        /**
         * Element-only content.
         *
         * <p>Only child elements are allowed, no text content (except whitespace).
         */
        ELEMENT_ONLY,
        
        /**
         * Mixed content (text and elements).
         *
         * <p>Both text content and child elements are allowed, interleaved.
         */
        MIXED,
        
        /**
         * Simple content (text only, with attributes).
         *
         * <p>Only text content is allowed, constrained by a simple type.
         * Attributes are also allowed.
         */
        SIMPLE,
        
        /**
         * Empty content (attributes only).
         *
         * <p>No content is allowed, only attributes.
         */
        EMPTY
    }
    
    private ContentType contentType = ContentType.ELEMENT_ONLY;
    private XSDType baseType;
    private boolean mixed = false;
    
    // Attribute declarations
    private final Map<String, XSDAttribute> attributes = new LinkedHashMap<>();
    private Map<String, XSDAttribute> cachedMergedAttributes;
    
    // Content model (simplified: just a list of allowed child elements)
    private final List<XSDParticle> particles = new ArrayList<>();
    
    // For simple content
    private XSDSimpleType simpleContentType;
    
    /**
     * Creates a complex type with the given name.
     *
     * @param name the type name (local part), or null for anonymous types
     * @param namespaceURI the type namespace URI
     */
    public XSDComplexType(String name, String namespaceURI) {
        super(name, namespaceURI);
    }
    
    /**
     * Returns false, indicating this is a complex type.
     *
     * @return false
     */
    @Override
    public boolean isSimpleType() {
        return false;
    }
    
    /**
     * Returns the content type of this complex type.
     *
     * @return the content type, never null
     */
    public ContentType getContentType() {
        return contentType;
    }
    
    /**
     * Sets the content type of this complex type.
     *
     * @param contentType the content type to set
     */
    public void setContentType(ContentType contentType) {
        this.contentType = contentType;
    }
    
    /**
     * Returns the base type from which this type is derived.
     *
     * <p>Complex types can be derived by restriction or extension from
     * another complex type. This method returns the base type, or null
     * if this is not a derived type.
     *
     * @return the base type, or null if not derived
     */
    public XSDType getBaseType() {
        return baseType;
    }
    
    /**
     * Sets the base type for type derivation.
     *
     * @param baseType the base type from which this type is derived
     */
    public void setBaseType(XSDType baseType) {
        this.baseType = baseType;
    }
    
    /**
     * Returns whether this type allows mixed content (text and elements).
     *
     * @return true if mixed content is allowed, false otherwise
     */
    public boolean isMixed() {
        return mixed;
    }
    
    /**
     * Sets whether this type allows mixed content.
     *
     * <p>Setting mixed to true automatically sets the content type
     * to {@link ContentType#MIXED}.
     *
     * @param mixed true to allow mixed content, false otherwise
     */
    public void setMixed(boolean mixed) {
        this.mixed = mixed;
        if (mixed) {
            this.contentType = ContentType.MIXED;
        }
    }
    
    /**
     * Returns the simple type for simple content.
     *
     * <p>For complex types with simple content (text with attributes),
     * this returns the simple type that constrains the text content.
     *
     * @return the simple content type, or null if not simple content
     */
    public XSDSimpleType getSimpleContentType() {
        return simpleContentType;
    }
    
    /**
     * Sets the simple type for simple content.
     *
     * <p>Setting a simple content type automatically sets the content type
     * to {@link ContentType#SIMPLE}.
     *
     * @param type the simple type for the text content
     */
    public void setSimpleContentType(XSDSimpleType type) {
        this.simpleContentType = type;
        this.contentType = ContentType.SIMPLE;
    }
    
    /**
     * Adds an attribute declaration to this complex type.
     *
     * <p>If an attribute with the same name already exists, it is replaced.
     * This allows derived types to override inherited attributes.
     *
     * @param attribute the attribute declaration to add
     */
    public void addAttribute(XSDAttribute attribute) {
        attributes.put(attribute.getName(), attribute);
    }
    
    /**
     * Gets an attribute declaration by name, including inherited attributes.
     *
     * <p>This method searches for the attribute in this type's declarations
     * first, then recursively searches base types if not found.
     *
     * @param name the attribute name (local part)
     * @return the attribute declaration, or null if not found
     */
    public XSDAttribute getAttribute(String name) {
        XSDAttribute attr = attributes.get(name);
        if (attr != null) {
            return attr;
        }
        // Check base type
        if (baseType instanceof XSDComplexType) {
            return ((XSDComplexType) baseType).getAttribute(name);
        }
        return null;
    }
    
    /**
     * Returns all attribute declarations, including inherited ones.
     * 
     * <p>Attributes from base types are collected first, then overlaid with
     * attributes from this type (allowing derived types to override).
     */
    public Map<String, XSDAttribute> getAttributes() {
        // If no base type, just return local attributes
        if (baseType == null || !(baseType instanceof XSDComplexType)) {
            return Collections.unmodifiableMap(attributes);
        }
        
        // Return cached merged attributes if available
        if (cachedMergedAttributes != null) {
            return cachedMergedAttributes;
        }
        
        // Collect inherited attributes first, then overlay with local ones
        Map<String, XSDAttribute> allAttrs = new LinkedHashMap<>();
        allAttrs.putAll(((XSDComplexType) baseType).getAttributes());
        allAttrs.putAll(attributes);
        cachedMergedAttributes = Collections.unmodifiableMap(allAttrs);
        return cachedMergedAttributes;
    }
    
    /**
     * Adds a particle to the content model.
     *
     * <p>Particles define the allowed child elements and their structure.
     * Common particles include:
     * <ul>
     *   <li>Element particles - specific element declarations</li>
     *   <li>Sequence particles - elements in order</li>
     *   <li>Choice particles - one of several alternatives</li>
     *   <li>All particles - elements in any order</li>
     *   <li>Any particles - wildcard elements</li>
     * </ul>
     *
     * @param particle the particle to add to the content model
     */
    public void addParticle(XSDParticle particle) {
        particles.add(particle);
    }
    
    /**
     * Returns the content model particles.
     *
     * <p>The returned list is unmodifiable. Particles define the structure
     * and constraints for child elements.
     *
     * @return an unmodifiable list of content model particles
     */
    public List<XSDParticle> getParticles() {
        return Collections.unmodifiableList(particles);
    }
    
    /**
     * Checks if an element with the given name is allowed as a child.
     *
     * <p>This method checks the content model to determine if the specified
     * element is allowed. For empty or simple content types, no child elements
     * are allowed.
     *
     * @param namespaceURI the element namespace URI (may be null)
     * @param localName the element local name
     * @return true if the element is allowed by the content model, false otherwise
     */
    public boolean allowsElement(String namespaceURI, String localName) {
        if (contentType == ContentType.EMPTY || contentType == ContentType.SIMPLE) {
            return false;
        }
        
        // Check particles for matching element
        for (XSDParticle particle : particles) {
            if (particle.allowsElement(namespaceURI, localName)) {
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * Returns the element declaration for a child element.
     *
     * <p>Searches the content model particles to find the declaration for
     * the specified element. This is useful for obtaining type information
     * and validation constraints for child elements.
     *
     * @param namespaceURI the element namespace URI (may be null)
     * @param localName the element local name
     * @return the element declaration, or null if not found in the content model
     */
    public XSDElement getChildElement(String namespaceURI, String localName) {
        for (XSDParticle particle : particles) {
            XSDElement element = particle.getElement(namespaceURI, localName);
            if (element != null) {
                return element;
            }
        }
        return null;
    }
    
    @Override
    public String toString() {
        return "XSDComplexType[" + super.toString() + ", content=" + contentType + 
               ", attributes=" + attributes.size() + "]";
    }
}

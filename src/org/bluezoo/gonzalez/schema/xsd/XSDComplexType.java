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
     */
    public enum ContentType {
        /** Element-only content */
        ELEMENT_ONLY,
        /** Mixed content (text and elements) */
        MIXED,
        /** Simple content (text only, with attributes) */
        SIMPLE,
        /** Empty (attributes only) */
        EMPTY
    }
    
    private ContentType contentType = ContentType.ELEMENT_ONLY;
    private XSDType baseType;
    private boolean mixed = false;
    
    // Attribute declarations
    private final Map<String, XSDAttribute> attributes = new LinkedHashMap<>();
    
    // Content model (simplified: just a list of allowed child elements)
    private final List<XSDParticle> particles = new ArrayList<>();
    
    // For simple content
    private XSDSimpleType simpleContentType;
    
    /**
     * Creates a complex type with the given name.
     */
    public XSDComplexType(String name, String namespaceURI) {
        super(name, namespaceURI);
    }
    
    @Override
    public boolean isSimpleType() {
        return false;
    }
    
    public ContentType getContentType() {
        return contentType;
    }
    
    public void setContentType(ContentType contentType) {
        this.contentType = contentType;
    }
    
    public XSDType getBaseType() {
        return baseType;
    }
    
    public void setBaseType(XSDType baseType) {
        this.baseType = baseType;
    }
    
    public boolean isMixed() {
        return mixed;
    }
    
    public void setMixed(boolean mixed) {
        this.mixed = mixed;
        if (mixed) {
            this.contentType = ContentType.MIXED;
        }
    }
    
    public XSDSimpleType getSimpleContentType() {
        return simpleContentType;
    }
    
    public void setSimpleContentType(XSDSimpleType type) {
        this.simpleContentType = type;
        this.contentType = ContentType.SIMPLE;
    }
    
    /**
     * Adds an attribute declaration.
     */
    public void addAttribute(XSDAttribute attribute) {
        attributes.put(attribute.getName(), attribute);
    }
    
    /**
     * Gets an attribute by name, including inherited attributes.
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
        
        // Collect inherited attributes first, then overlay with local ones
        Map<String, XSDAttribute> allAttrs = new LinkedHashMap<>();
        allAttrs.putAll(((XSDComplexType) baseType).getAttributes());
        allAttrs.putAll(attributes);
        return Collections.unmodifiableMap(allAttrs);
    }
    
    /**
     * Adds a particle (element reference, sequence, choice, etc.) to the content model.
     */
    public void addParticle(XSDParticle particle) {
        particles.add(particle);
    }
    
    /**
     * Returns the content model particles.
     */
    public List<XSDParticle> getParticles() {
        return Collections.unmodifiableList(particles);
    }
    
    /**
     * Checks if an element with the given name is allowed as a child.
     *
     * @param namespaceURI the element namespace
     * @param localName the element local name
     * @return true if the element is allowed
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
     * Returns the type declaration for a child element.
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

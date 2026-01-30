/*
 * XSDParticle.java
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
import java.util.List;

/**
 * Represents a particle in an XSD content model.
 *
 * <p>A particle is either:
 * <ul>
 *   <li>An element declaration</li>
 *   <li>A model group (sequence, choice, all)</li>
 *   <li>A wildcard (any)</li>
 * </ul>
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public class XSDParticle {
    
    /**
     * Kind of particle.
     */
    public enum Kind {
        ELEMENT,
        SEQUENCE,
        CHOICE,
        ALL,
        ANY
    }
    
    private final Kind kind;
    private XSDElement element;  // For ELEMENT kind
    private final List<XSDParticle> children = new ArrayList<>();  // For model groups
    
    private int minOccurs = 1;
    private int maxOccurs = 1;  // -1 = unbounded
    
    // For ANY wildcard
    private String namespaceConstraint = "##any";
    private String processContents = "strict";
    
    /**
     * Creates an element particle.
     */
    public static XSDParticle element(XSDElement element) {
        XSDParticle p = new XSDParticle(Kind.ELEMENT);
        p.element = element;
        p.minOccurs = element.getMinOccurs();
        p.maxOccurs = element.getMaxOccurs();
        return p;
    }
    
    /**
     * Creates a sequence particle.
     */
    public static XSDParticle sequence() {
        return new XSDParticle(Kind.SEQUENCE);
    }
    
    /**
     * Creates a choice particle.
     */
    public static XSDParticle choice() {
        return new XSDParticle(Kind.CHOICE);
    }
    
    /**
     * Creates an all particle.
     */
    public static XSDParticle all() {
        return new XSDParticle(Kind.ALL);
    }
    
    /**
     * Creates an any (wildcard) particle.
     */
    public static XSDParticle any(String namespace, String processContents) {
        XSDParticle p = new XSDParticle(Kind.ANY);
        p.namespaceConstraint = namespace != null ? namespace : "##any";
        p.processContents = processContents != null ? processContents : "strict";
        return p;
    }
    
    private XSDParticle(Kind kind) {
        this.kind = kind;
    }
    
    public Kind getKind() {
        return kind;
    }
    
    public XSDElement getElement() {
        return element;
    }
    
    public List<XSDParticle> getChildren() {
        return Collections.unmodifiableList(children);
    }
    
    public void addChild(XSDParticle child) {
        children.add(child);
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
    
    public String getNamespaceConstraint() {
        return namespaceConstraint;
    }
    
    public String getProcessContents() {
        return processContents;
    }
    
    /**
     * Checks if an element with the given name is allowed by this particle.
     */
    public boolean allowsElement(String namespaceURI, String localName) {
        switch (kind) {
            case ELEMENT:
                return matchesElement(element, namespaceURI, localName);
                
            case SEQUENCE:
            case CHOICE:
            case ALL:
                for (XSDParticle child : children) {
                    if (child.allowsElement(namespaceURI, localName)) {
                        return true;
                    }
                }
                return false;
                
            case ANY:
                return matchesWildcard(namespaceURI);
                
            default:
                return false;
        }
    }
    
    /**
     * Gets the element declaration for a matching element.
     */
    public XSDElement getElement(String namespaceURI, String localName) {
        switch (kind) {
            case ELEMENT:
                if (matchesElement(element, namespaceURI, localName)) {
                    return element;
                }
                return null;
                
            case SEQUENCE:
            case CHOICE:
            case ALL:
                for (XSDParticle child : children) {
                    XSDElement e = child.getElement(namespaceURI, localName);
                    if (e != null) {
                        return e;
                    }
                }
                return null;
                
            default:
                return null;
        }
    }
    
    private boolean matchesElement(XSDElement elem, String namespaceURI, String localName) {
        if (!elem.getName().equals(localName)) {
            return false;
        }
        String elemNs = elem.getNamespaceURI();
        if (elemNs == null || elemNs.isEmpty()) {
            return namespaceURI == null || namespaceURI.isEmpty();
        }
        return elemNs.equals(namespaceURI);
    }
    
    private boolean matchesWildcard(String namespaceURI) {
        switch (namespaceConstraint) {
            case "##any":
                return true;
            case "##other":
                // Match any namespace except the target namespace
                // (simplified - would need schema reference)
                return namespaceURI != null && !namespaceURI.isEmpty();
            case "##local":
                return namespaceURI == null || namespaceURI.isEmpty();
            case "##targetNamespace":
                // Would need schema reference
                return true;
            default:
                // Space-separated list of namespace URIs
                if (namespaceURI == null) {
                    namespaceURI = "";
                }
                for (String ns : namespaceConstraint.split("\\s+")) {
                    if (ns.equals(namespaceURI)) return true;
                }
                return false;
        }
    }
    
    @Override
    public String toString() {
        switch (kind) {
            case ELEMENT:
                return "element(" + element.getName() + ")";
            case SEQUENCE:
                return "sequence" + children;
            case CHOICE:
                return "choice" + children;
            case ALL:
                return "all" + children;
            case ANY:
                return "any(" + namespaceConstraint + ")";
            default:
                return kind.toString();
        }
    }
}

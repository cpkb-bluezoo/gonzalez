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
     * Kind of particle in a content model.
     *
     * <p>This enum identifies the type of particle, which determines how
     * it constrains element content.
     */
    public enum Kind {
        /**
         * Element particle.
         *
         * <p>Represents a reference to a specific element declaration.
         */
        ELEMENT,
        
        /**
         * Sequence particle.
         *
         * <p>Requires child particles to appear in order.
         */
        SEQUENCE,
        
        /**
         * Choice particle.
         *
         * <p>Allows one of the child particles to appear.
         */
        CHOICE,
        
        /**
         * All particle.
         *
         * <p>Allows child particles to appear in any order, each at most once
         * (unless maxOccurs > 1).
         */
        ALL,
        
        /**
         * Any (wildcard) particle.
         *
         * <p>Allows elements from specified namespaces to appear.
         */
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
     *
     * <p>An element particle represents a reference to an element declaration
     * in the content model. The occurrence constraints (minOccurs, maxOccurs)
     * are copied from the element declaration.
     *
     * @param element the element declaration
     * @return a new element particle
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
     *
     * <p>A sequence particle requires its children to appear in order.
     *
     * @return a new sequence particle
     */
    public static XSDParticle sequence() {
        return new XSDParticle(Kind.SEQUENCE);
    }
    
    /**
     * Creates a choice particle.
     *
     * <p>A choice particle allows one of its children to appear.
     *
     * @return a new choice particle
     */
    public static XSDParticle choice() {
        return new XSDParticle(Kind.CHOICE);
    }
    
    /**
     * Creates an all particle.
     *
     * <p>An all particle allows its children to appear in any order,
     * but each child can appear at most once (unless maxOccurs > 1).
     *
     * @return a new all particle
     */
    public static XSDParticle all() {
        return new XSDParticle(Kind.ALL);
    }
    
    /**
     * Creates an any (wildcard) particle.
     *
     * <p>An any particle allows elements from specified namespaces to appear.
     * The namespace constraint can be:
     * <ul>
     *   <li>"##any" - any namespace</li>
     *   <li>"##other" - any namespace except target namespace</li>
     *   <li>"##local" - unqualified elements only</li>
     *   <li>"##targetNamespace" - target namespace only</li>
     *   <li>Space-separated list of namespace URIs</li>
     * </ul>
     *
     * @param namespace the namespace constraint (may be null for "##any")
     * @param processContents how to process contents ("strict", "lax", or "skip")
     * @return a new any particle
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
    
    /**
     * Returns the kind of particle.
     *
     * @return the particle kind
     */
    public Kind getKind() {
        return kind;
    }
    
    /**
     * Returns the element declaration for element particles.
     *
     * @return the element declaration, or null if not an element particle
     */
    public XSDElement getElement() {
        return element;
    }
    
    /**
     * Returns the child particles for model groups (sequence, choice, all).
     *
     * @return an unmodifiable list of child particles
     */
    public List<XSDParticle> getChildren() {
        return Collections.unmodifiableList(children);
    }
    
    /**
     * Adds a child particle to a model group.
     *
     * <p>This method is only meaningful for sequence, choice, and all particles.
     *
     * @param child the child particle to add
     */
    public void addChild(XSDParticle child) {
        children.add(child);
    }
    
    /**
     * Returns the minimum occurrence count.
     *
     * @return the minimum occurrences (default is 1)
     */
    public int getMinOccurs() {
        return minOccurs;
    }
    
    /**
     * Sets the minimum occurrence count.
     *
     * @param minOccurs the minimum occurrences (must be >= 0)
     */
    public void setMinOccurs(int minOccurs) {
        this.minOccurs = minOccurs;
    }
    
    /**
     * Returns the maximum occurrence count.
     *
     * @return the maximum occurrences (-1 means unbounded, default is 1)
     */
    public int getMaxOccurs() {
        return maxOccurs;
    }
    
    /**
     * Sets the maximum occurrence count.
     *
     * @param maxOccurs the maximum occurrences (-1 for unbounded, must be >= minOccurs)
     */
    public void setMaxOccurs(int maxOccurs) {
        this.maxOccurs = maxOccurs;
    }
    
    /**
     * Returns the namespace constraint for any particles.
     *
     * @return the namespace constraint ("##any", "##other", "##local", "##targetNamespace", or space-separated URIs)
     */
    public String getNamespaceConstraint() {
        return namespaceConstraint;
    }
    
    /**
     * Returns the processContents value for any particles.
     *
     * @return "strict", "lax", or "skip"
     */
    public String getProcessContents() {
        return processContents;
    }
    
    /**
     * Checks if an element with the given name is allowed by this particle.
     *
     * <p>For element particles, checks if the name matches. For model groups,
     * recursively checks children. For any particles, checks namespace constraints.
     *
     * @param namespaceURI the element namespace URI (may be null)
     * @param localName the element local name
     * @return true if the element is allowed, false otherwise
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
     *
     * <p>Searches this particle and its children (for model groups) to find
     * the element declaration that matches the specified name.
     *
     * @param namespaceURI the element namespace URI (may be null)
     * @param localName the element local name
     * @return the matching element declaration, or null if not found
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

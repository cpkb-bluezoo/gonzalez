/*
 * AttributeSet.java
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

package org.bluezoo.gonzalez.transform.compiler;

import org.bluezoo.gonzalez.transform.ast.SequenceNode;
import org.bluezoo.gonzalez.transform.ast.XSLTNode;
import org.bluezoo.gonzalez.transform.runtime.OutputHandler;
import org.bluezoo.gonzalez.transform.runtime.TransformContext;
import org.xml.sax.SAXException;

import java.util.*;

/**
 * A named attribute set (xsl:attribute-set).
 *
 * <p>Attribute sets define reusable groups of attributes that can be
 * applied to literal result elements via xsl:use-attribute-sets.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class AttributeSet {

    private final String name;
    private final List<String> useAttributeSets;
    private final SequenceNode attributes;
    private final ComponentVisibility visibility;  // XSLT 3.0 package visibility

    /**
     * Creates an attribute set.
     *
     * @param name the attribute set name
     * @param useAttributeSets names of other attribute sets to include
     * @param attributes the attribute instructions
     */
    public AttributeSet(String name, List<String> useAttributeSets, SequenceNode attributes) {
        this(name, useAttributeSets, attributes, ComponentVisibility.PUBLIC);
    }

    /**
     * Creates an attribute set with visibility.
     *
     * @param name the attribute set name
     * @param useAttributeSets names of other attribute sets to include
     * @param attributes the attribute instructions
     * @param visibility the package visibility (XSLT 3.0)
     */
    public AttributeSet(String name, List<String> useAttributeSets, SequenceNode attributes,
                       ComponentVisibility visibility) {
        this.name = name;
        this.useAttributeSets = useAttributeSets != null ? 
            Collections.unmodifiableList(new ArrayList<>(useAttributeSets)) : 
            Collections.emptyList();
        this.attributes = attributes != null ? attributes : SequenceNode.EMPTY;
        this.visibility = visibility != null ? visibility : ComponentVisibility.PUBLIC;
    }

    /**
     * Returns the attribute set name.
     *
     * @return the name
     */
    public String getName() {
        return name;
    }

    /**
     * Returns the names of included attribute sets.
     *
     * @return the use-attribute-sets names (immutable)
     */
    public List<String> getUseAttributeSets() {
        return useAttributeSets;
    }

    /**
     * Returns the attribute instructions.
     *
     * @return the attributes
     */
    public SequenceNode getAttributes() {
        return attributes;
    }

    /**
     * Returns the package visibility (XSLT 3.0).
     *
     * @return the visibility, never null (defaults to PUBLIC)
     */
    public ComponentVisibility getVisibility() {
        return visibility;
    }

    /**
     * Creates a copy of this attribute set with a different visibility.
     *
     * @param newVisibility the new visibility
     * @return a new AttributeSet with the specified visibility
     */
    public AttributeSet withVisibility(ComponentVisibility newVisibility) {
        return new AttributeSet(name, useAttributeSets, attributes, newVisibility);
    }

    /**
     * Merges this attribute set with another.
     * The other set's attributes take precedence for conflicts.
     * Used when multiple xsl:attribute-set declarations have the same name.
     *
     * @param other the other attribute set (later definition)
     * @return a new merged attribute set
     */
    public AttributeSet mergeWith(AttributeSet other) {
        // Merge use-attribute-sets
        List<String> mergedUseSets = new ArrayList<>(this.useAttributeSets);
        for (String set : other.useAttributeSets) {
            if (!mergedUseSets.contains(set)) {
                mergedUseSets.add(set);
            }
        }
        
        // Merge attributes: this first, then other (other overrides for conflicts)
        List<org.bluezoo.gonzalez.transform.ast.XSLTNode> mergedNodes = new ArrayList<>();
        if (this.attributes != null) {
            mergedNodes.addAll(this.attributes.getChildren());
        }
        if (other.attributes != null) {
            mergedNodes.addAll(other.attributes.getChildren());
        }
        SequenceNode mergedAttrs = new SequenceNode(mergedNodes);
        
        // Later visibility takes precedence
        ComponentVisibility mergedVisibility = other.visibility != null ? 
            other.visibility : this.visibility;
        return new AttributeSet(name, mergedUseSets, mergedAttrs, mergedVisibility);
    }

    /**
     * Applies this attribute set to the current element being constructed.
     * First applies any included attribute sets, then the attributes defined
     * directly in this set.
     *
     * <p>Per XSLT spec, attribute sets are evaluated with only top-level 
     * variables and parameters in scope, not local variables from the 
     * invoking template.
     *
     * @param context the transform context
     * @param output the output handler
     * @throws SAXException if an error occurs
     */
    public void apply(TransformContext context, OutputHandler output) throws SAXException {
        // Create a context with only global variables visible
        // This ensures attribute sets don't see local variables
        TransformContext globalContext = context.withGlobalVariablesOnly();
        
        // First, apply included attribute sets (recursively)
        if (!useAttributeSets.isEmpty()) {
            CompiledStylesheet stylesheet = context.getStylesheet();
            for (String includedName : useAttributeSets) {
                AttributeSet included = stylesheet.getAttributeSet(includedName);
                if (included != null) {
                    included.apply(globalContext, output);
                }
            }
        }
        
        // Then apply this set's attributes (can override included ones)
        if (attributes != null) {
            attributes.execute(globalContext, output);
        }
    }

    @Override
    public String toString() {
        return "attribute-set " + name;
    }

}

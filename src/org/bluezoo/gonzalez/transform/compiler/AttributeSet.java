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
 * <p>When multiple xsl:attribute-set elements share the same name, they
 * are merged into a single attribute set with multiple definitions.
 * Each definition is evaluated in document order: first its inherited
 * attribute sets (use-attribute-sets), then its own attribute instructions.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class AttributeSet {

    private final String name;
    private final List<Definition> definitions;
    private final ComponentVisibility visibility;

    /**
     * A single xsl:attribute-set definition with its own use-attribute-sets
     * and attribute instructions.
     */
    private static final class Definition {
        final List<String> useAttributeSets;
        final SequenceNode attributes;

        Definition(List<String> useAttributeSets, SequenceNode attributes) {
            this.useAttributeSets = useAttributeSets != null ?
                Collections.unmodifiableList(new ArrayList<>(useAttributeSets)) :
                Collections.emptyList();
            this.attributes = attributes != null ? attributes : SequenceNode.EMPTY;
        }
    }

    /**
     * Creates an attribute set with a single definition.
     *
     * @param name the attribute set name
     * @param useAttributeSets names of other attribute sets to include
     * @param attributes the attribute instructions
     */
    public AttributeSet(String name, List<String> useAttributeSets, SequenceNode attributes) {
        this(name, useAttributeSets, attributes, ComponentVisibility.PUBLIC);
    }

    /**
     * Creates an attribute set with a single definition and visibility.
     *
     * @param name the attribute set name
     * @param useAttributeSets names of other attribute sets to include
     * @param attributes the attribute instructions
     * @param visibility the package visibility (XSLT 3.0)
     */
    public AttributeSet(String name, List<String> useAttributeSets, SequenceNode attributes,
                       ComponentVisibility visibility) {
        this.name = name;
        List<Definition> defs = new ArrayList<>();
        defs.add(new Definition(useAttributeSets, attributes));
        this.definitions = Collections.unmodifiableList(defs);
        this.visibility = visibility != null ? visibility : ComponentVisibility.PUBLIC;
    }

    private AttributeSet(String name, List<Definition> definitions, ComponentVisibility visibility) {
        this.name = name;
        this.definitions = Collections.unmodifiableList(new ArrayList<>(definitions));
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
     * Returns all use-attribute-sets names from all definitions.
     *
     * @return the combined use-attribute-sets names (for validation)
     */
    public List<String> getUseAttributeSets() {
        List<String> allSets = new ArrayList<>();
        for (Definition def : definitions) {
            for (String set : def.useAttributeSets) {
                if (!allSets.contains(set)) {
                    allSets.add(set);
                }
            }
        }
        return allSets;
    }

    /**
     * Returns the combined attribute instructions from all definitions.
     *
     * @return the attributes
     */
    public SequenceNode getAttributes() {
        if (definitions.size() == 1) {
            return definitions.get(0).attributes;
        }
        List<XSLTNode> allNodes = new ArrayList<>();
        for (Definition def : definitions) {
            if (def.attributes != null) {
                allNodes.addAll(def.attributes.getChildren());
            }
        }
        return new SequenceNode(allNodes);
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
        return new AttributeSet(name, definitions, newVisibility);
    }

    /**
     * Merges this attribute set with another having the same name.
     * Preserves definition order so that each definition's inherited sets
     * and body attributes are evaluated in the correct sequence.
     *
     * @param other the other attribute set (later definition)
     * @return a new merged attribute set
     */
    public AttributeSet mergeWith(AttributeSet other) {
        List<Definition> mergedDefs = new ArrayList<>(this.definitions);
        mergedDefs.addAll(other.definitions);
        ComponentVisibility mergedVisibility = other.visibility != null ?
            other.visibility : this.visibility;
        return new AttributeSet(name, mergedDefs, mergedVisibility);
    }

    /**
     * Applies this attribute set to the current element being constructed.
     * Each definition is evaluated in order: first its inherited attribute
     * sets, then its own attribute instructions.
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
        TransformContext globalContext = context.withGlobalVariablesOnly();
        CompiledStylesheet stylesheet = context.getStylesheet();

        for (Definition def : definitions) {
            // First, apply this definition's inherited attribute sets
            for (String includedName : def.useAttributeSets) {
                AttributeSet included = stylesheet.getAttributeSet(includedName);
                if (included != null) {
                    included.apply(globalContext, output);
                }
            }

            // Then apply this definition's own attributes (can override inherited)
            if (def.attributes != null) {
                def.attributes.execute(globalContext, output);
            }
        }
    }

    @Override
    public String toString() {
        return "attribute-set " + name;
    }

}

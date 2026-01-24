/*
 * CompiledStylesheet.java
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

import org.bluezoo.gonzalez.transform.ast.XSLTNode.StreamingCapability;

import java.util.*;

/**
 * A compiled XSLT stylesheet.
 *
 * <p>This class represents a fully compiled stylesheet ready for execution.
 * It contains:
 * <ul>
 *   <li>Template rules indexed by pattern and mode</li>
 *   <li>Named templates</li>
 *   <li>Global variables and parameters</li>
 *   <li>Attribute sets</li>
 *   <li>Output properties</li>
 *   <li>Key definitions</li>
 *   <li>Namespace aliases</li>
 *   <li>Whitespace stripping rules</li>
 * </ul>
 *
 * <p>Compiled stylesheets are thread-safe and reusable.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class CompiledStylesheet {

    private final List<TemplateRule> templateRules;
    private final Map<String, TemplateRule> namedTemplates;
    private final List<GlobalVariable> globalVariables;
    private final Map<String, AttributeSet> attributeSets;
    private final OutputProperties outputProperties;
    private final Map<String, KeyDefinition> keyDefinitions;
    private final Map<String, String> namespaceAliases;
    private final List<String> stripSpaceElements;
    private final List<String> preserveSpaceElements;
    private final StreamingCapability streamingCapability;

    /**
     * Builder for creating compiled stylesheets.
     */
    public static class Builder {
        private final List<TemplateRule> templateRules = new ArrayList<>();
        private final Map<String, TemplateRule> namedTemplates = new HashMap<>();
        private final List<GlobalVariable> globalVariables = new ArrayList<>();
        private final Map<String, AttributeSet> attributeSets = new HashMap<>();
        private OutputProperties outputProperties = new OutputProperties();
        private final Map<String, KeyDefinition> keyDefinitions = new HashMap<>();
        private final Map<String, String> namespaceAliases = new HashMap<>();
        private final List<String> stripSpaceElements = new ArrayList<>();
        private final List<String> preserveSpaceElements = new ArrayList<>();

        public Builder addTemplateRule(TemplateRule rule) {
            templateRules.add(rule);
            if (rule.getName() != null) {
                namedTemplates.put(rule.getName(), rule);
            }
            return this;
        }

        public Builder addGlobalVariable(GlobalVariable variable) {
            globalVariables.add(variable);
            return this;
        }

        public Builder addAttributeSet(AttributeSet attributeSet) {
            attributeSets.put(attributeSet.getName(), attributeSet);
            return this;
        }

        public Builder setOutputProperties(OutputProperties props) {
            this.outputProperties = props;
            return this;
        }

        public Builder addKeyDefinition(KeyDefinition key) {
            keyDefinitions.put(key.getName(), key);
            return this;
        }

        public Builder addNamespaceAlias(String stylesheetPrefix, String resultPrefix) {
            namespaceAliases.put(stylesheetPrefix, resultPrefix);
            return this;
        }

        public Builder addStripSpaceElement(String element) {
            stripSpaceElements.add(element);
            return this;
        }

        public Builder addPreserveSpaceElement(String element) {
            preserveSpaceElements.add(element);
            return this;
        }

        /**
         * Merges an imported stylesheet into this builder.
         * 
         * <p>For imports, the imported stylesheet has lower precedence, so its
         * templates are added but won't override existing ones with the same pattern.
         *
         * @param imported the imported stylesheet
         * @param isImport true for xsl:import, false for xsl:include
         * @return this builder
         */
        public Builder merge(CompiledStylesheet imported, boolean isImport) {
            // Add template rules - for imports these have lower precedence
            // For includes they have the same precedence as the including stylesheet
            for (TemplateRule rule : imported.getTemplateRules()) {
                templateRules.add(rule);
                if (rule.getName() != null) {
                    // For named templates, first definition wins (higher precedence)
                    if (!namedTemplates.containsKey(rule.getName())) {
                        namedTemplates.put(rule.getName(), rule);
                    }
                }
            }
            
            // Add global variables - first definition wins
            for (GlobalVariable var : imported.getGlobalVariables()) {
                boolean exists = false;
                for (GlobalVariable existing : globalVariables) {
                    if (existing.getName().equals(var.getName())) {
                        exists = true;
                        break;
                    }
                }
                if (!exists) {
                    globalVariables.add(var);
                }
            }
            
            // Merge attribute sets - combine attributes, importing sheet's go first
            for (Map.Entry<String, AttributeSet> entry : imported.attributeSets.entrySet()) {
                String name = entry.getKey();
                if (!attributeSets.containsKey(name)) {
                    attributeSets.put(name, entry.getValue());
                }
                // TODO: For includes, should merge attribute set contents
            }
            
            // Merge output properties - importing stylesheet values take precedence
            if (isImport) {
                // For imports, the imported values are lower precedence
                OutputProperties merged = new OutputProperties();
                merged.merge(imported.getOutputProperties());
                merged.merge(this.outputProperties);
                this.outputProperties = merged;
            } else {
                // For includes, just merge (last value wins)
                this.outputProperties.merge(imported.getOutputProperties());
            }
            
            // Add key definitions - first definition wins
            for (Map.Entry<String, KeyDefinition> entry : imported.keyDefinitions.entrySet()) {
                if (!keyDefinitions.containsKey(entry.getKey())) {
                    keyDefinitions.put(entry.getKey(), entry.getValue());
                }
            }
            
            // Add namespace aliases - first definition wins
            for (Map.Entry<String, String> entry : imported.getNamespaceAliases().entrySet()) {
                if (!namespaceAliases.containsKey(entry.getKey())) {
                    namespaceAliases.put(entry.getKey(), entry.getValue());
                }
            }
            
            // Add whitespace handling rules
            for (String element : imported.getStripSpaceElements()) {
                if (!stripSpaceElements.contains(element)) {
                    stripSpaceElements.add(element);
                }
            }
            for (String element : imported.getPreserveSpaceElements()) {
                if (!preserveSpaceElements.contains(element)) {
                    preserveSpaceElements.add(element);
                }
            }
            
            return this;
        }

        public CompiledStylesheet build() {
            return new CompiledStylesheet(this);
        }
    }

    private CompiledStylesheet(Builder builder) {
        this.templateRules = Collections.unmodifiableList(new ArrayList<>(builder.templateRules));
        this.namedTemplates = Collections.unmodifiableMap(new HashMap<>(builder.namedTemplates));
        this.globalVariables = Collections.unmodifiableList(new ArrayList<>(builder.globalVariables));
        this.attributeSets = Collections.unmodifiableMap(new HashMap<>(builder.attributeSets));
        this.outputProperties = builder.outputProperties;
        this.keyDefinitions = Collections.unmodifiableMap(new HashMap<>(builder.keyDefinitions));
        this.namespaceAliases = Collections.unmodifiableMap(new HashMap<>(builder.namespaceAliases));
        this.stripSpaceElements = Collections.unmodifiableList(new ArrayList<>(builder.stripSpaceElements));
        this.preserveSpaceElements = Collections.unmodifiableList(new ArrayList<>(builder.preserveSpaceElements));
        this.streamingCapability = computeStreamingCapability();
    }

    private StreamingCapability computeStreamingCapability() {
        StreamingCapability result = StreamingCapability.FULL;
        for (TemplateRule rule : templateRules) {
            StreamingCapability ruleCap = rule.getStreamingCapability();
            if (ruleCap.ordinal() > result.ordinal()) {
                result = ruleCap;
            }
        }
        return result;
    }

    /**
     * Returns all template rules.
     *
     * @return the template rules (immutable)
     */
    public List<TemplateRule> getTemplateRules() {
        return templateRules;
    }

    /**
     * Returns a named template.
     *
     * @param name the template name
     * @return the template, or null if not found
     */
    public TemplateRule getNamedTemplate(String name) {
        return namedTemplates.get(name);
    }

    /**
     * Returns all global variables.
     *
     * @return the global variables (immutable)
     */
    public List<GlobalVariable> getGlobalVariables() {
        return globalVariables;
    }

    /**
     * Returns an attribute set.
     *
     * @param name the attribute set name
     * @return the attribute set, or null if not found
     */
    public AttributeSet getAttributeSet(String name) {
        return attributeSets.get(name);
    }

    /**
     * Returns the output properties.
     *
     * @return the output properties
     */
    public OutputProperties getOutputProperties() {
        return outputProperties;
    }

    /**
     * Returns a key definition.
     *
     * @param name the key name
     * @return the key definition, or null if not found
     */
    public KeyDefinition getKeyDefinition(String name) {
        return keyDefinitions.get(name);
    }

    /**
     * Returns namespace aliases.
     *
     * @return map of stylesheet prefix to result prefix (immutable)
     */
    public Map<String, String> getNamespaceAliases() {
        return namespaceAliases;
    }

    /**
     * Returns the streaming capability of this stylesheet.
     *
     * @return the streaming capability
     */
    public StreamingCapability getStreamingCapability() {
        return streamingCapability;
    }

    /**
     * Returns the list of elements for which whitespace should be stripped.
     *
     * @return the strip-space elements (immutable)
     */
    public List<String> getStripSpaceElements() {
        return stripSpaceElements;
    }

    /**
     * Returns the list of elements for which whitespace should be preserved.
     *
     * @return the preserve-space elements (immutable)
     */
    public List<String> getPreserveSpaceElements() {
        return preserveSpaceElements;
    }

    /**
     * Returns true if whitespace should be stripped for the given element.
     *
     * @param namespaceURI the element namespace
     * @param localName the element local name
     * @return true if whitespace should be stripped
     */
    public boolean shouldStripWhitespace(String namespaceURI, String localName) {
        // TODO: Implement proper pattern matching
        String qName = localName;
        return stripSpaceElements.contains(qName) || stripSpaceElements.contains("*");
    }

    /**
     * Creates a new builder.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

}

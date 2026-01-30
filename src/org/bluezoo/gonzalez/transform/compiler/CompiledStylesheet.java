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

import org.bluezoo.gonzalez.schema.xsd.XSDSchema;
import org.bluezoo.gonzalez.schema.xsd.XSDSimpleType;
import org.bluezoo.gonzalez.schema.xsd.XSDType;
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
    private final Map<String, NamespaceAlias> namespaceAliases;  // keyed by stylesheet URI
    private final List<String> stripSpaceElements;
    private final List<String> preserveSpaceElements;
    private final StreamingCapability streamingCapability;
    private final Map<String, DecimalFormatInfo> decimalFormats;
    private final Map<String, AccumulatorDefinition> accumulators;
    private final Map<String, ModeDeclaration> modeDeclarations;
    private final Map<String, String> namespaceBindings;  // prefix -> URI from stylesheet
    private final Set<String> excludedNamespaceURIs;  // namespace URIs to exclude from output
    private final Map<String, UserFunction> userFunctions;  // keyed by namespace#localName#arity
    private final Map<String, XSDSchema> importedSchemas;  // namespace URI -> schema
    private final StylesheetCompiler.ValidationMode defaultValidation;  // from default-validation attr
    private final String baseURI;  // static base URI of the stylesheet
    private final double version;  // XSLT version (1.0, 2.0, 3.0)

    /**
     * Stores decimal format configuration for format-number().
     */
    public static class DecimalFormatInfo {
        public final String name;
        public final char decimalSeparator;
        public final char groupingSeparator;
        public final String infinity;
        public final char minusSign;
        public final String nan;
        public final char percent;
        public final char perMille;
        public final char zeroDigit;
        public final char digit;
        public final char patternSeparator;
        
        public DecimalFormatInfo(String name, String decimalSeparator, String groupingSeparator,
                String infinity, String minusSign, String nan, String percent,
                String perMille, String zeroDigit, String digit, String patternSeparator) {
            this.name = name;
            this.decimalSeparator = firstChar(decimalSeparator, '.');
            this.groupingSeparator = firstChar(groupingSeparator, ',');
            this.infinity = infinity != null ? infinity : "Infinity";
            this.minusSign = firstChar(minusSign, '-');
            this.nan = nan != null ? nan : "NaN";
            this.percent = firstChar(percent, '%');
            this.perMille = firstChar(perMille, '\u2030');
            this.zeroDigit = firstChar(zeroDigit, '0');
            this.digit = firstChar(digit, '#');
            this.patternSeparator = firstChar(patternSeparator, ';');
        }
        
        private static char firstChar(String s, char defaultChar) {
            return (s != null && s.length() > 0) ? s.charAt(0) : defaultChar;
        }
    }

    /**
     * Stores namespace alias information for xsl:namespace-alias.
     *
     * <p>Per XSLT 1.0 spec section 7.1.1, namespace aliasing maps a stylesheet
     * namespace URI to a result namespace URI. This allows generating elements
     * in the XSLT namespace (or any other) without the XSLT processor treating
     * them as instructions.
     */
    public static class NamespaceAlias {
        /** The result namespace URI to use in output. */
        public final String resultUri;
        /** The suggested prefix to use in output (may be empty for default namespace). */
        public final String resultPrefix;
        
        public NamespaceAlias(String resultUri, String resultPrefix) {
            this.resultUri = resultUri != null ? resultUri : "";
            this.resultPrefix = resultPrefix != null ? resultPrefix : "";
        }
        
        @Override
        public String toString() {
            return "NamespaceAlias[" + resultPrefix + " -> " + resultUri + "]";
        }
    }

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
        private final Map<String, NamespaceAlias> namespaceAliases = new HashMap<>();  // keyed by stylesheet URI
        private final List<String> stripSpaceElements = new ArrayList<>();
        private final List<String> preserveSpaceElements = new ArrayList<>();
        private final Map<String, DecimalFormatInfo> decimalFormats = new HashMap<>();
        private final Map<String, AccumulatorDefinition> accumulators = new HashMap<>();
        private final Map<String, ModeDeclaration> modeDeclarations = new HashMap<>();
        private final Map<String, String> namespaceBindings = new HashMap<>();
        private final Set<String> excludedNamespaceURIs = new HashSet<>();
        private final Map<String, UserFunction> userFunctions = new HashMap<>();
        private final Map<String, XSDSchema> importedSchemas = new HashMap<>();
        private StylesheetCompiler.ValidationMode defaultValidation = StylesheetCompiler.ValidationMode.STRIP;
        private String baseURI;
        private double version = 1.0;

        public Builder setBaseURI(String uri) {
            this.baseURI = uri;
            return this;
        }

        public Builder setVersion(double version) {
            this.version = version;
            return this;
        }

        public Builder addTemplateRule(TemplateRule rule) {
            templateRules.add(rule);
            if (rule.getName() != null) {
                namedTemplates.put(rule.getName(), rule);
            }
            return this;
        }

        public Builder addGlobalVariable(GlobalVariable variable) {
            // Check for existing variable with same name - newer definition wins
            // (higher import precedence stylesheets add their variables later)
            for (int i = 0; i < globalVariables.size(); i++) {
                GlobalVariable existing = globalVariables.get(i);
                boolean sameNs = (existing.getNamespaceURI() == null && variable.getNamespaceURI() == null) ||
                                (existing.getNamespaceURI() != null && existing.getNamespaceURI().equals(variable.getNamespaceURI()));
                if (sameNs && existing.getLocalName().equals(variable.getLocalName())) {
                    // Replace existing with new definition (higher precedence)
                    globalVariables.set(i, variable);
                    return this;
                }
            }
            globalVariables.add(variable);
            return this;
        }

        public Builder addAttributeSet(AttributeSet attributeSet) {
            // Per XSLT 1.0: Multiple attribute sets with the same name are MERGED.
            // Later definitions take precedence for conflicting attribute names.
            // We store all definitions and merge them when retrieving.
            String name = attributeSet.getName();
            AttributeSet existing = attributeSets.get(name);
            if (existing != null) {
                // Merge: existing attributes first, then new ones (new takes precedence)
                attributeSet = existing.mergeWith(attributeSet);
            }
            attributeSets.put(name, attributeSet);
            return this;
        }

        public Builder setOutputProperties(OutputProperties props) {
            this.outputProperties = props;
            return this;
        }

        public Builder addKeyDefinition(KeyDefinition key) {
            // Use expanded name (Clark notation) for lookup
            keyDefinitions.put(key.getExpandedName(), key);
            return this;
        }

        /**
         * Adds a namespace alias mapping.
         *
         * @param stylesheetUri the namespace URI in the stylesheet (to be replaced)
         * @param resultUri the namespace URI for the result (replacement)
         * @param resultPrefix the prefix to use in the result
         * @return this builder
         */
        public Builder addNamespaceAlias(String stylesheetUri, String resultUri, String resultPrefix) {
            namespaceAliases.put(stylesheetUri, new NamespaceAlias(resultUri, resultPrefix));
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
         * Adds a namespace binding from the stylesheet.
         *
         * @param prefix the namespace prefix
         * @param uri the namespace URI
         * @return this builder
         */
        public Builder addNamespaceBinding(String prefix, String uri) {
            namespaceBindings.put(prefix, uri);
            return this;
        }
        
        /**
         * Adds a namespace URI to the exclude-result-prefixes set.
         *
         * @param uri the namespace URI to exclude from output
         * @return this builder
         */
        public Builder addExcludedNamespaceURI(String uri) {
            if (uri != null && !uri.isEmpty()) {
                excludedNamespaceURIs.add(uri);
            }
            return this;
        }
        
        public Builder addUserFunction(UserFunction function) {
            // Key by namespace + localname + arity for overloading support
            userFunctions.put(function.getKey(), function);
            return this;
        }
        
        /**
         * Adds an imported schema for schema-aware processing.
         *
         * @param schema the XSD schema to import
         * @return this builder
         */
        public Builder addImportedSchema(XSDSchema schema) {
            if (schema != null) {
                String ns = schema.getTargetNamespace();
                importedSchemas.put(ns != null ? ns : "", schema);
            }
            return this;
        }
        
        public Builder addDecimalFormat(String name, String decimalSeparator, String groupingSeparator,
                String infinity, String minusSign, String nan, String percent,
                String perMille, String zeroDigit, String digit, String patternSeparator) {
            String key = name != null ? name : "";
            decimalFormats.put(key, new DecimalFormatInfo(name, decimalSeparator, groupingSeparator,
                infinity, minusSign, nan, percent, perMille, zeroDigit, digit, patternSeparator));
            return this;
        }

        /**
         * Adds an accumulator definition.
         *
         * @param accumulator the accumulator
         * @return this builder
         */
        public Builder addAccumulator(AccumulatorDefinition accumulator) {
            accumulators.put(accumulator.getName(), accumulator);
            return this;
        }

        /**
         * Adds a mode declaration.
         *
         * @param mode the mode declaration
         * @return this builder
         */
        public Builder addModeDeclaration(ModeDeclaration mode) {
            String key = mode.getName() != null ? mode.getName() : "#default";
            modeDeclarations.put(key, mode);
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
                    // Compare by namespace URI and local name
                    boolean sameNs = (existing.getNamespaceURI() == null && var.getNamespaceURI() == null) ||
                                    (existing.getNamespaceURI() != null && existing.getNamespaceURI().equals(var.getNamespaceURI()));
                    if (sameNs && existing.getLocalName().equals(var.getLocalName())) {
                        exists = true;
                        break;
                    }
                }
                if (!exists) {
                    globalVariables.add(var);
                }
            }
            
            // Merge attribute sets - later definition wins
            // Since imports are processed in order (first import first, last import last),
            // and later imports have higher precedence, letting later merges override
            // gives us correct import precedence behavior
            for (Map.Entry<String, AttributeSet> entry : imported.attributeSets.entrySet()) {
                String name = entry.getKey();
                attributeSets.put(name, entry.getValue());
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
            for (Map.Entry<String, NamespaceAlias> entry : imported.getNamespaceAliases().entrySet()) {
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
            
            // Add accumulators - first definition wins
            for (Map.Entry<String, AccumulatorDefinition> entry : imported.accumulators.entrySet()) {
                if (!accumulators.containsKey(entry.getKey())) {
                    accumulators.put(entry.getKey(), entry.getValue());
                }
            }
            
            // Add mode declarations - first definition wins
            for (Map.Entry<String, ModeDeclaration> entry : imported.modeDeclarations.entrySet()) {
                if (!modeDeclarations.containsKey(entry.getKey())) {
                    modeDeclarations.put(entry.getKey(), entry.getValue());
                }
            }
            
            // Add decimal formats - first definition wins
            for (Map.Entry<String, DecimalFormatInfo> entry : imported.decimalFormats.entrySet()) {
                if (!decimalFormats.containsKey(entry.getKey())) {
                    decimalFormats.put(entry.getKey(), entry.getValue());
                }
            }
            
            return this;
        }

        public Builder setDefaultValidation(StylesheetCompiler.ValidationMode mode) {
            this.defaultValidation = mode;
            return this;
        }

        public CompiledStylesheet build() throws javax.xml.transform.TransformerConfigurationException {
            // Validate attribute-set references (XTSE0710)
            for (AttributeSet attrSet : attributeSets.values()) {
                if (attrSet.getUseAttributeSets() != null) {
                    for (String refName : attrSet.getUseAttributeSets()) {
                        if (!attributeSets.containsKey(refName)) {
                            throw new javax.xml.transform.TransformerConfigurationException(
                                "Attribute-set '" + attrSet.getName() + 
                                "' references undefined attribute-set '" + refName + "'");
                        }
                    }
                }
            }
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
        this.decimalFormats = Collections.unmodifiableMap(new HashMap<>(builder.decimalFormats));
        this.accumulators = Collections.unmodifiableMap(new HashMap<>(builder.accumulators));
        this.modeDeclarations = Collections.unmodifiableMap(new HashMap<>(builder.modeDeclarations));
        this.namespaceBindings = Collections.unmodifiableMap(new HashMap<>(builder.namespaceBindings));
        this.excludedNamespaceURIs = Collections.unmodifiableSet(new HashSet<>(builder.excludedNamespaceURIs));
        this.userFunctions = Collections.unmodifiableMap(new HashMap<>(builder.userFunctions));
        this.importedSchemas = Collections.unmodifiableMap(new HashMap<>(builder.importedSchemas));
        this.defaultValidation = builder.defaultValidation;
        this.baseURI = builder.baseURI;
        this.version = builder.version;
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
     * Returns the base URI of the stylesheet.
     *
     * <p>This is typically the URI from which the stylesheet was loaded,
     * potentially modified by xml:base attributes.
     *
     * @return the stylesheet base URI, or null if not set
     */
    public String getBaseURI() {
        return baseURI;
    }

    /**
     * Returns the XSLT version of this stylesheet.
     *
     * @return the version (1.0, 2.0, or 3.0)
     */
    public double getVersion() {
        return version;
    }

    /**
     * Returns a decimal format.
     *
     * @param name the format name, or null for the default format
     * @return the decimal format, or null if not found
     */
    public DecimalFormatInfo getDecimalFormat(String name) {
        return decimalFormats.get(name != null ? name : "");
    }

    /**
     * Returns namespace aliases.
     *
     * @return map of stylesheet namespace URI to NamespaceAlias (immutable)
     */
    public Map<String, NamespaceAlias> getNamespaceAliases() {
        return namespaceAliases;
    }
    
    /**
     * Gets the namespace alias for a stylesheet namespace URI.
     *
     * @param stylesheetUri the namespace URI in the stylesheet
     * @return the alias, or null if no alias defined
     */
    public NamespaceAlias getNamespaceAlias(String stylesheetUri) {
        return namespaceAliases.get(stylesheetUri != null ? stylesheetUri : "");
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
     * Returns an accumulator definition.
     *
     * @param name the accumulator name
     * @return the accumulator, or null if not found
     */
    public AccumulatorDefinition getAccumulator(String name) {
        return accumulators.get(name);
    }

    /**
     * Returns all accumulator definitions.
     *
     * @return map of name to accumulator (immutable)
     */
    public Map<String, AccumulatorDefinition> getAccumulators() {
        return accumulators;
    }

    /**
     * Returns a mode declaration.
     *
     * @param name the mode name, or null for default mode
     * @return the mode declaration, or null if not found
     */
    public ModeDeclaration getModeDeclaration(String name) {
        String key = name != null ? name : "#default";
        return modeDeclarations.get(key);
    }

    /**
     * Returns all mode declarations.
     *
     * @return map of name to mode declaration (immutable)
     */
    public Map<String, ModeDeclaration> getModeDeclarations() {
        return modeDeclarations;
    }

    /**
     * Returns the namespace bindings from the stylesheet.
     * These are used to resolve namespace prefixes in variable references.
     *
     * @return map of prefix to namespace URI (immutable)
     */
    public Map<String, String> getNamespaceBindings() {
        return namespaceBindings;
    }

    /**
     * Resolves a namespace prefix using the stylesheet bindings.
     *
     * @param prefix the namespace prefix
     * @return the namespace URI, or null if not found
     */
    public String resolveNamespacePrefix(String prefix) {
        return namespaceBindings.get(prefix);
    }

    /**
     * Returns the set of namespace URIs excluded from result output.
     * These are URIs from prefixes listed in exclude-result-prefixes.
     *
     * @return set of excluded namespace URIs (immutable)
     */
    public Set<String> getExcludedNamespaceURIs() {
        return excludedNamespaceURIs;
    }

    /**
     * Checks if a namespace URI should be excluded from result output.
     *
     * @param uri the namespace URI to check
     * @return true if the URI should be excluded
     */
    public boolean isExcludedNamespace(String uri) {
        return uri != null && excludedNamespaceURIs.contains(uri);
    }

    /**
     * Returns all user-defined functions.
     *
     * @return immutable map of functions keyed by namespace#localName#arity
     */
    public Map<String, UserFunction> getUserFunctions() {
        return userFunctions;
    }

    /**
     * Gets a user-defined function by namespace, name, and arity.
     *
     * @param namespaceURI the function namespace URI
     * @param localName the function local name
     * @param arity the number of arguments
     * @return the function, or null if not found
     */
    public UserFunction getUserFunction(String namespaceURI, String localName, int arity) {
        String key = namespaceURI + "#" + localName + "#" + arity;
        return userFunctions.get(key);
    }

    /**
     * Tests if a user-defined function exists.
     *
     * @param namespaceURI the function namespace URI
     * @param localName the function local name
     * @param arity the number of arguments
     * @return true if the function exists
     */
    public boolean hasUserFunction(String namespaceURI, String localName, int arity) {
        return getUserFunction(namespaceURI, localName, arity) != null;
    }

    /**
     * Returns all imported schemas.
     *
     * @return immutable map of schemas keyed by namespace URI
     */
    public Map<String, XSDSchema> getImportedSchemas() {
        return importedSchemas;
    }

    /**
     * Gets an imported schema by namespace URI.
     *
     * @param namespaceURI the schema namespace URI
     * @return the schema, or null if not found
     */
    public XSDSchema getImportedSchema(String namespaceURI) {
        return importedSchemas.get(namespaceURI != null ? namespaceURI : "");
    }

    /**
     * Looks up a type from imported schemas.
     *
     * @param namespaceURI the type namespace URI
     * @param localName the type local name
     * @return the type definition, or null if not found
     */
    public XSDType getImportedType(String namespaceURI, String localName) {
        XSDSchema schema = getImportedSchema(namespaceURI);
        if (schema != null) {
            return schema.getType(localName);
        }
        return null;
    }

    /**
     * Looks up a simple type from imported schemas.
     *
     * @param namespaceURI the type namespace URI
     * @param localName the type local name
     * @return the simple type, or null if not found or not a simple type
     */
    public XSDSimpleType getImportedSimpleType(String namespaceURI, String localName) {
        XSDType type = getImportedType(namespaceURI, localName);
        if (type instanceof XSDSimpleType) {
            return (XSDSimpleType) type;
        }
        return null;
    }

    /**
     * Returns the default validation mode for the stylesheet.
     *
     * <p>This is set by the default-validation attribute on xsl:stylesheet.
     * It applies to instructions that construct elements or attributes
     * when they don't have an explicit validation attribute.
     *
     * @return the default validation mode (defaults to STRIP)
     */
    public StylesheetCompiler.ValidationMode getDefaultValidation() {
        return defaultValidation;
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

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
import org.bluezoo.gonzalez.transform.ValidationMode;

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
    private final List<SpaceDeclaration> stripSpaceDeclarations;
    private final List<SpaceDeclaration> preserveSpaceDeclarations;
    // Legacy lists for backward compatibility
    private final List<String> stripSpaceElements;
    private final List<String> preserveSpaceElements;
    
    /**
     * Represents a strip-space or preserve-space declaration with import precedence.
     */
    public static class SpaceDeclaration {
        public final String pattern;
        public final int importPrecedence;
        
        public SpaceDeclaration(String pattern, int importPrecedence) {
            this.pattern = pattern;
            this.importPrecedence = importPrecedence;
        }
        
        /**
         * Returns the priority/specificity of this pattern.
         * Specific patterns (e.g., "foo" or "{uri}foo") have priority 0.
         * Wildcard patterns (e.g., "*" or "{uri}*") have priority -0.5.
         */
        public double getPriority() {
            if ("*".equals(pattern)) {
                return -0.5;
            }
            if (pattern.endsWith("}*")) {
                return -0.5;
            }
            return 0.0;
        }
    }
    private final StreamingCapability streamingCapability;
    private final Map<String, DecimalFormatInfo> decimalFormats;
    private final Map<String, CharacterMap> characterMaps;
    private final Map<String, AccumulatorDefinition> accumulators;
    private final Map<String, ModeDeclaration> modeDeclarations;
    private final Map<String, String> namespaceBindings;  // prefix -> URI from stylesheet
    private final Set<String> excludedNamespaceURIs;  // namespace URIs to exclude from output
    private final Map<String, UserFunction> userFunctions;  // keyed by namespace#localName#arity
    private final Map<String, XSDSchema> importedSchemas;  // namespace URI -> schema
    private final ValidationMode defaultValidation;  // from default-validation attr
    private final String baseURI;  // static base URI of the stylesheet
    private final double version;  // XSLT version (1.0, 2.0, 3.0)
    private final String defaultCollation;  // default collation URI (XSLT 2.0+)
    private final String defaultMode;  // XSLT 3.0 default-mode from stylesheet element
    
    // XSLT 3.0 global context item declaration
    private String globalContextItemType;  // as attribute
    private String globalContextItemUse;   // use attribute ("required", "optional", "absent")
    
    // XSLT 3.0 package information
    private final String packageName;  // package name URI
    private final String packageVersion;  // package version string
    
    // Streamability analysis (set after build)
    private volatile StreamabilityAnalyzer.StylesheetStreamability streamabilityAnalysis;

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
     * Stores character map configuration for xsl:character-map (XSLT 2.0+).
     *
     * <p>A character map defines a set of character-to-string mappings that are
     * applied during serialization. Each mapping replaces a single character with
     * a string (which may include markup).
     */
    public static class CharacterMap {
        private final String name;
        private final Map<Integer, String> mappings;  // Unicode code points to strings
        private final List<String> useCharacterMaps;  // names of referenced character maps
        
        /**
         * Creates a new character map.
         *
         * @param name the name of the character map
         */
        public CharacterMap(String name) {
            this.name = name;
            this.mappings = new HashMap<>();
            this.useCharacterMaps = new ArrayList<>();
        }
        
        /**
         * Creates a character map with the given mappings.
         *
         * @param name the name of the character map
         * @param mappings the code point-to-string mappings
         * @param useCharacterMaps names of other character maps to include
         */
        public CharacterMap(String name, Map<Integer, String> mappings, List<String> useCharacterMaps) {
            this.name = name;
            this.mappings = new HashMap<>(mappings);
            this.useCharacterMaps = new ArrayList<>(useCharacterMaps);
        }
        
        /**
         * Adds a character mapping.
         *
         * @param codePoint the Unicode code point to replace
         * @param replacement the replacement string
         */
        public void addMapping(int codePoint, String replacement) {
            mappings.put(codePoint, replacement);
        }
        
        /**
         * Adds a reference to another character map.
         *
         * @param mapName the name of the referenced character map
         */
        public void addUseCharacterMap(String mapName) {
            useCharacterMaps.add(mapName);
        }
        
        public String getName() { return name; }
        public Map<Integer, String> getMappings() { return Collections.unmodifiableMap(mappings); }
        public List<String> getUseCharacterMaps() { return Collections.unmodifiableList(useCharacterMaps); }
        
        /**
         * Applies this character map to a string.
         * Note: This does NOT recursively apply referenced character maps.
         *
         * @param input the input string
         * @return the string with character mappings applied
         */
        public String apply(String input) {
            if (mappings.isEmpty()) {
                return input;
            }
            StringBuilder result = new StringBuilder(input.length());
            int i = 0;
            while (i < input.length()) {
                int codePoint = input.codePointAt(i);
                String replacement = mappings.get(codePoint);
                if (replacement != null) {
                    result.append(replacement);
                } else {
                    result.appendCodePoint(codePoint);
                }
                i += Character.charCount(codePoint);
            }
            return result.toString();
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
        
        /**
         * Creates a namespace alias.
         *
         * @param resultUri the result namespace URI (replacement)
         * @param resultPrefix the prefix to use in output
         */
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
     *
     * <p>The builder pattern is used to construct CompiledStylesheet instances
     * incrementally during stylesheet compilation. Methods return the builder
     * instance for method chaining.
     *
     * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
     */
    public static class Builder {
        private final List<TemplateRule> templateRules = new ArrayList<>();
        private final Map<String, TemplateRule> namedTemplates = new HashMap<>();
        private final List<GlobalVariable> globalVariables = new ArrayList<>();
        private final Map<String, AttributeSet> attributeSets = new HashMap<>();
        private OutputProperties outputProperties = new OutputProperties();
        private final Map<String, KeyDefinition> keyDefinitions = new HashMap<>();
        private final Map<String, NamespaceAlias> namespaceAliases = new HashMap<>();  // keyed by stylesheet URI
        private final List<SpaceDeclaration> stripSpaceDeclarations = new ArrayList<>();
        private final List<SpaceDeclaration> preserveSpaceDeclarations = new ArrayList<>();
        private int currentImportPrecedence = 0;  // Tracks current import precedence level
        private final Map<String, DecimalFormatInfo> decimalFormats = new HashMap<>();
        private final Map<String, CharacterMap> characterMaps = new HashMap<>();
        private final Map<String, AccumulatorDefinition> accumulators = new HashMap<>();
        private final Map<String, ModeDeclaration> modeDeclarations = new HashMap<>();
        private final Map<String, String> namespaceBindings = new HashMap<>();
        private final Set<String> attributeSetReferences = new HashSet<>();  // All use-attribute-sets references
        private final Set<String> excludedNamespaceURIs = new HashSet<>();
        private final Map<String, UserFunction> userFunctions = new HashMap<>();
        private final Map<String, XSDSchema> importedSchemas = new HashMap<>();
        // Tracks precedences from included stylesheets that need to be updated to final precedence
        private final Set<Integer> pendingIncludePrecedences = new HashSet<>();
        private ValidationMode defaultValidation = ValidationMode.STRIP;
        private String baseURI;
        private double version = 1.0;
        private String defaultCollation;  // XSLT 2.0+ default-collation
        private String globalContextItemType;  // XSLT 3.0
        private String globalContextItemUse;   // XSLT 3.0
        private String packageName;  // XSLT 3.0 package name
        private String packageVersion;  // XSLT 3.0 package version
        private String defaultMode;  // XSLT 3.0 default-mode from stylesheet element

        /**
         * Sets the base URI of the stylesheet.
         *
         * @param uri the base URI
         * @return this builder
         */
        public Builder setBaseURI(String uri) {
            this.baseURI = uri;
            return this;
        }

        /**
         * Sets the XSLT version.
         *
         * @param version the version (1.0, 2.0, or 3.0)
         * @return this builder
         */
        public Builder setVersion(double version) {
            this.version = version;
            return this;
        }

        /**
         * Sets the default collation URI (XSLT 2.0+).
         *
         * @param collation the collation URI
         * @return this builder
         */
        public Builder setDefaultCollation(String collation) {
            this.defaultCollation = collation;
            return this;
        }

        /**
         * Sets the global context item type (XSLT 3.0).
         *
         * @param type the expected type
         * @return this builder
         */
        public Builder setGlobalContextItemType(String type) {
            this.globalContextItemType = type;
            return this;
        }

        /**
         * Sets the global context item use (XSLT 3.0).
         *
         * @param use "required", "optional", or "absent"
         * @return this builder
         */
        public Builder setGlobalContextItemUse(String use) {
            this.globalContextItemUse = use;
            return this;
        }

        /**
         * Sets the package name (XSLT 3.0).
         *
         * @param name the package name (a URI)
         * @return this builder
         */
        public Builder setPackageName(String name) {
            this.packageName = name;
            return this;
        }

        /**
         * Sets the package version (XSLT 3.0).
         *
         * @param version the package version string
         * @return this builder
         */
        public Builder setPackageVersion(String version) {
            this.packageVersion = version;
            return this;
        }

        /**
         * Sets the default-mode from the stylesheet element (XSLT 3.0).
         *
         * @param mode the default mode name
         * @return this builder
         */
        public Builder setDefaultMode(String mode) {
            this.defaultMode = mode;
            return this;
        }

        /**
         * Gets the package name.
         *
         * @return the package name, or null
         */
        public String getPackageName() {
            return packageName;
        }

        /**
         * Gets the package version.
         *
         * @return the package version, or null
         */
        public String getPackageVersion() {
            return packageVersion;
        }

        /**
         * Adds a template rule to the stylesheet.
         *
         * @param rule the template rule
         * @return this builder
         */
        public Builder addTemplateRule(TemplateRule rule) {
            templateRules.add(rule);
            if (rule.getName() != null) {
                namedTemplates.put(rule.getName(), rule);
            }
            return this;
        }

        /**
         * Adds a global variable or parameter.
         * If a variable with the same name already exists, it is replaced
         * (higher import precedence stylesheets add their variables later).
         *
         * @param variable the global variable
         * @return this builder
         */
        public Builder addGlobalVariable(GlobalVariable variable) throws javax.xml.transform.TransformerConfigurationException {
            // Check for existing variable with same name
            for (int i = 0; i < globalVariables.size(); i++) {
                GlobalVariable existing = globalVariables.get(i);
                boolean sameNs = (existing.getNamespaceURI() == null && variable.getNamespaceURI() == null) ||
                                (existing.getNamespaceURI() != null && existing.getNamespaceURI().equals(variable.getNamespaceURI()));
                if (sameNs && existing.getLocalName().equals(variable.getLocalName())) {
                    // XTSE0630: Duplicate variable at same import precedence is an error
                    if (existing.getImportPrecedence() == variable.getImportPrecedence()) {
                        throw new javax.xml.transform.TransformerConfigurationException(
                            "XTSE0630: Duplicate global " + 
                            (variable.isParam() ? "parameter" : "variable") + 
                            " '" + variable.getName() + "' with same import precedence");
                    }
                    // Higher precedence wins - only replace if new variable has higher precedence
                    if (variable.getImportPrecedence() > existing.getImportPrecedence()) {
                        globalVariables.set(i, variable);
                    }
                    return this;
                }
            }
            globalVariables.add(variable);
            return this;
        }

        /**
         * Adds an attribute set to the stylesheet.
         * Per XSLT 1.0: Multiple attribute sets with the same name are merged.
         * Later definitions take precedence for conflicting attribute names.
         *
         * @param attributeSet the attribute set
         * @return this builder
         */
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

        /**
         * Sets the output properties for the stylesheet.
         *
         * @param props the output properties
         * @return this builder
         */
        public Builder setOutputProperties(OutputProperties props) {
            this.outputProperties = props;
            return this;
        }

        /**
         * Adds a key definition to the stylesheet.
         *
         * @param key the key definition
         * @return this builder
         */
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
            stripSpaceDeclarations.add(new SpaceDeclaration(element, currentImportPrecedence));
            return this;
        }

        public Builder addPreserveSpaceElement(String element) {
            preserveSpaceDeclarations.add(new SpaceDeclaration(element, currentImportPrecedence));
            return this;
        }
        
        /**
         * Sets the current import precedence level for subsequent declarations.
         * Higher values indicate higher precedence (main stylesheet over imported).
         */
        public Builder setImportPrecedence(int precedence) {
            this.currentImportPrecedence = precedence;
            return this;
        }
        
        /**
         * Gets the current import precedence level.
         */
        public int getImportPrecedence() {
            return currentImportPrecedence;
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
        
        /**
         * Adds a user-defined function to the stylesheet.
         *
         * @param function the user-defined function
         * @return this builder
         */
        public Builder addUserFunction(UserFunction function)
                throws javax.xml.transform.TransformerConfigurationException {
            String key = function.getKey();
            UserFunction existing = userFunctions.get(key);
            if (existing != null
                    && existing.getImportPrecedence() == function.getImportPrecedence()) {
                throw new javax.xml.transform.TransformerConfigurationException(
                    "XTSE0770: Duplicate function '" + function.getLocalName() +
                    "' with arity " + function.getArity() +
                    " at the same import precedence");
            }
            userFunctions.put(key, function);
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
        
        /**
         * Adds a decimal format definition.
         *
         * @param name the format name, or null for default
         * @param decimalSeparator the decimal separator string
         * @param groupingSeparator the grouping separator string
         * @param infinity the infinity string
         * @param minusSign the minus sign string
         * @param nan the NaN string
         * @param percent the percent string
         * @param perMille the per-mille string
         * @param zeroDigit the zero digit string
         * @param digit the digit placeholder string
         * @param patternSeparator the pattern separator string
         * @return this builder
         */
        public Builder addDecimalFormat(String name, String decimalSeparator, String groupingSeparator,
                String infinity, String minusSign, String nan, String percent,
                String perMille, String zeroDigit, String digit, String patternSeparator) {
            String key = name != null ? name : "";
            decimalFormats.put(key, new DecimalFormatInfo(name, decimalSeparator, groupingSeparator,
                infinity, minusSign, nan, percent, perMille, zeroDigit, digit, patternSeparator));
            return this;
        }

        /**
         * Adds a character map definition.
         *
         * @param characterMap the character map
         * @return this builder
         */
        public Builder addCharacterMap(CharacterMap characterMap) {
            String key = characterMap.getName() != null ? characterMap.getName() : "";
            characterMaps.put(key, characterMap);
            return this;
        }

        /**
         * Checks if a character map with the given name exists.
         *
         * @param name the character map name
         * @return true if a character map with this name exists
         */
        public boolean hasCharacterMap(String name) {
            String key = name != null ? name : "";
            return characterMaps.containsKey(key);
        }

        /**
         * Registers attribute set references from instructions (xsl:copy, xsl:element, etc).
         * These references will be validated during build().
         *
         * @param names whitespace-separated list of attribute set names
         */
        public void registerAttributeSetReferences(String names) {
            if (names == null || names.isEmpty()) {
                return;
            }
            for (String name : names.trim().split("\\s+")) {
                if (!name.isEmpty()) {
                    attributeSetReferences.add(name);
                }
            }
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
            for (TemplateRule rule : imported.getTemplateRules()) {
                templateRules.add(rule);
                if (rule.getName() != null) {
                    // For named templates, higher import precedence wins.
                    // If same precedence, later declaration wins.
                    TemplateRule existing = namedTemplates.get(rule.getName());
                    if (existing == null) {
                        namedTemplates.put(rule.getName(), rule);
                    } else if (rule.getImportPrecedence() > existing.getImportPrecedence()) {
                        // New rule has higher precedence - it wins
                        namedTemplates.put(rule.getName(), rule);
                    } else if (rule.getImportPrecedence() == existing.getImportPrecedence() &&
                               rule.getDeclarationIndex() > existing.getDeclarationIndex()) {
                        // Same precedence but later declaration - it wins
                        namedTemplates.put(rule.getName(), rule);
                    }
                    // Otherwise keep existing (it has higher or equal precedence)
                }
            }
            
            // Continue with rest of merge...
            mergeNonTemplates(imported, isImport);
            
            return this;
        }
        
        /**
         * Merges an included stylesheet, updating template precedences to match the including stylesheet.
         * 
         * <p>For includes, templates from the included stylesheet should have the SAME precedence
         * as the including stylesheet. This method creates new TemplateRule instances with the
         * correct precedence for templates that belong to the included stylesheet itself
         * (not its imports).
         *
         * @param included the included stylesheet
         * @param includingPrecedence the precedence of the including stylesheet
         * @return this builder
         */
        public Builder mergeInclude(CompiledStylesheet included, int includingPrecedence) {
            // Find the maximum precedence in the included stylesheet - that's its "own" precedence
            int includedOwnPrecedence = -1;
            for (TemplateRule rule : included.getTemplateRules()) {
                if (rule.getImportPrecedence() > includedOwnPrecedence) {
                    includedOwnPrecedence = rule.getImportPrecedence();
                }
            }
            
            // Add template rules with updated precedence
            // Templates with the included stylesheet's own precedence get updated to the including precedence
            // Templates from imports (lower precedence) keep their original precedence
            for (TemplateRule rule : included.getTemplateRules()) {
                TemplateRule adjusted = rule;
                // Update precedence for templates that are at the included stylesheet's own level
                if (rule.getImportPrecedence() == includedOwnPrecedence) {
                    adjusted = new TemplateRule(
                        rule.getMatchPattern(),
                        rule.getName(),
                        rule.getMode(),
                        rule.getPriority(),
                        includingPrecedence,  // Use including stylesheet's precedence
                        rule.getDeclarationIndex(),
                        rule.getParameters(),
                        rule.getBody(),
                        rule.getAsType()      // Preserve as attribute for type validation
                    );
                }
                templateRules.add(adjusted);
                if (adjusted.getName() != null) {
                    // For named templates, higher import precedence wins
                    TemplateRule existing = namedTemplates.get(adjusted.getName());
                    if (existing == null) {
                        namedTemplates.put(adjusted.getName(), adjusted);
                    } else if (adjusted.getImportPrecedence() > existing.getImportPrecedence()) {
                        namedTemplates.put(adjusted.getName(), adjusted);
                    } else if (adjusted.getImportPrecedence() == existing.getImportPrecedence() &&
                               adjusted.getDeclarationIndex() > existing.getDeclarationIndex()) {
                        namedTemplates.put(adjusted.getName(), adjusted);
                    }
                }
            }
            
            // Merge non-template components
            mergeNonTemplates(included, false);  // false = include, not import
            
            return this;
        }
        
        /**
         * Merges an included stylesheet without updating precedences yet.
         * 
         * <p>Templates from the included stylesheet are added with their current precedences.
         * They will be updated to the including stylesheet's precedence later by
         * {@link #finalizePrecedence(int)}.
         *
         * @param included the included stylesheet
         * @return this builder
         */
        public Builder mergeIncludePending(CompiledStylesheet included) {
            // Find the maximum precedence in the included stylesheet - that's its "own" precedence
            int includedOwnPrecedence = -1;
            for (TemplateRule rule : included.getTemplateRules()) {
                if (rule.getImportPrecedence() > includedOwnPrecedence) {
                    includedOwnPrecedence = rule.getImportPrecedence();
                }
            }
            
            // Mark this precedence for later update
            pendingIncludePrecedences.add(includedOwnPrecedence);
            
            // Add template rules as-is (precedences will be updated later)
            for (TemplateRule rule : included.getTemplateRules()) {
                templateRules.add(rule);
                if (rule.getName() != null) {
                    // For named templates, higher import precedence wins
                    TemplateRule existing = namedTemplates.get(rule.getName());
                    if (existing == null) {
                        namedTemplates.put(rule.getName(), rule);
                    } else if (rule.getImportPrecedence() > existing.getImportPrecedence()) {
                        namedTemplates.put(rule.getName(), rule);
                    } else if (rule.getImportPrecedence() == existing.getImportPrecedence() &&
                               rule.getDeclarationIndex() > existing.getDeclarationIndex()) {
                        namedTemplates.put(rule.getName(), rule);
                    }
                }
            }
            
            // Merge non-template components
            mergeNonTemplates(included, false);  // false = include, not import
            
            return this;
        }
        
        /**
         * Merges non-template components from another stylesheet.
         *
         * @param imported the imported/included stylesheet
         * @param isImport true for xsl:import, false for xsl:include
         */
        private void mergeNonTemplates(CompiledStylesheet imported, boolean isImport) {
            // Add global variables - higher import precedence wins
            for (GlobalVariable var : imported.getGlobalVariables()) {
                boolean found = false;
                for (int i = 0; i < globalVariables.size(); i++) {
                    GlobalVariable existing = globalVariables.get(i);
                    boolean sameNs = (existing.getNamespaceURI() == null && var.getNamespaceURI() == null) ||
                                    (existing.getNamespaceURI() != null && existing.getNamespaceURI().equals(var.getNamespaceURI()));
                    if (sameNs && existing.getLocalName().equals(var.getLocalName())) {
                        found = true;
                        if (var.getImportPrecedence() > existing.getImportPrecedence()) {
                            globalVariables.set(i, var);
                        }
                        break;
                    }
                }
                if (!found) {
                    globalVariables.add(var);
                }
            }
            
            // Merge attribute sets - must properly merge, not replace
            // Per XSLT spec: Multiple attribute sets with the same name are merged.
            // Later definitions (higher precedence) override for conflicting attribute names,
            // but non-conflicting attributes from both sets are included.
            for (Map.Entry<String, AttributeSet> entry : imported.attributeSets.entrySet()) {
                String name = entry.getKey();
                AttributeSet existing = attributeSets.get(name);
                if (existing != null) {
                    // Merge: existing first, then imported (imported takes precedence for conflicts)
                    // Since imports are processed in order, later imports override earlier ones.
                    // In mergeWith(other), "other" takes precedence for conflicts.
                    attributeSets.put(name, existing.mergeWith(entry.getValue()));
                } else {
                    attributeSets.put(name, entry.getValue());
                }
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
            
            // Add whitespace handling rules with proper precedence
            // Imported declarations have lower precedence than existing ones
            for (SpaceDeclaration decl : imported.getStripSpaceDeclarations()) {
                // Add with lower precedence (imported declarations)
                stripSpaceDeclarations.add(new SpaceDeclaration(decl.pattern, decl.importPrecedence - 1));
            }
            for (SpaceDeclaration decl : imported.getPreserveSpaceDeclarations()) {
                // Add with lower precedence (imported declarations)
                preserveSpaceDeclarations.add(new SpaceDeclaration(decl.pattern, decl.importPrecedence - 1));
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
            
            // Add character maps - first definition wins
            for (Map.Entry<String, CharacterMap> entry : imported.characterMaps.entrySet()) {
                if (!characterMaps.containsKey(entry.getKey())) {
                    characterMaps.put(entry.getKey(), entry.getValue());
                }
            }
        }

        /**
         * Sets the default validation mode for the stylesheet.
         *
         * @param mode the validation mode
         * @return this builder
         */
        public Builder setDefaultValidation(ValidationMode mode) {
            this.defaultValidation = mode;
            return this;
        }

        /**
         * Finalizes template precedences, updating:
         * <ul>
         *   <li>Templates with precedence -1 (main stylesheet's own templates)</li>
         *   <li>Templates with pending include precedences (included stylesheets)</li>
         * </ul>
         * 
         * <p>This is called after all includes have been processed to ensure
         * all templates that should share the main stylesheet's precedence get the
         * correct (high) value.
         *
         * @param finalPrecedence the final import precedence for this stylesheet
         * @return this builder
         */
        public Builder finalizePrecedence(int finalPrecedence) {
            for (int i = 0; i < templateRules.size(); i++) {
                TemplateRule rule = templateRules.get(i);
                // Update templates that need the final precedence:
                // - Templates with -1 (main stylesheet's templates compiled before precedence was assigned)
                // - Templates from included stylesheets (their "own" precedence marked in pendingIncludePrecedences)
                if (rule.getImportPrecedence() == -1 || pendingIncludePrecedences.contains(rule.getImportPrecedence())) {
                    TemplateRule adjusted = new TemplateRule(
                        rule.getMatchPattern(),
                        rule.getName(),
                        rule.getMode(),
                        rule.getPriority(),
                        finalPrecedence,
                        rule.getDeclarationIndex(),
                        rule.getParameters(),
                        rule.getBody(),
                        rule.getAsType()      // Preserve as attribute for type validation
                    );
                    templateRules.set(i, adjusted);
                    if (adjusted.getName() != null) {
                        namedTemplates.put(adjusted.getName(), adjusted);
                    }
                }
            }
            pendingIncludePrecedences.clear();
            return this;
        }

        /**
         * Builds the compiled stylesheet.
         * Validates attribute-set references and other constraints.
         *
         * @return the compiled stylesheet
         * @throws javax.xml.transform.TransformerConfigurationException if validation fails
         */
        public CompiledStylesheet build() throws javax.xml.transform.TransformerConfigurationException {
            return build(true);
        }

        /**
         * Builds the compiled stylesheet, optionally skipping cross-reference validation.
         * Sub-stylesheets (imported/included) skip validation since references may
         * target definitions in sibling or parent stylesheets.
         *
         * @param validateReferences true to validate attribute-set and character-map references
         * @return the compiled stylesheet
         * @throws javax.xml.transform.TransformerConfigurationException if validation fails
         */
        public CompiledStylesheet build(boolean validateReferences) throws javax.xml.transform.TransformerConfigurationException {
            if (validateReferences) {
                // Validate attribute-set references (XTSE0710)
                // Check references from attribute-set declarations
                for (AttributeSet attrSet : attributeSets.values()) {
                    if (attrSet.getUseAttributeSets() != null) {
                        for (String refName : attrSet.getUseAttributeSets()) {
                            if (!attributeSets.containsKey(refName)) {
                                throw new javax.xml.transform.TransformerConfigurationException(
                                    "XTSE0710: Attribute-set '" + attrSet.getName() + 
                                    "' references undefined attribute-set '" + refName + "'");
                            }
                        }
                    }
                }
                // Check references from instructions (xsl:copy, xsl:element, literal result elements)
                for (String refName : attributeSetReferences) {
                    if (!attributeSets.containsKey(refName)) {
                        throw new javax.xml.transform.TransformerConfigurationException(
                            "XTSE0710: use-attribute-sets references undefined attribute-set '" + refName + "'");
                    }
                }
            
                // Validate character-map references (XTSE1590)
                for (String mapName : outputProperties.getUseCharacterMaps()) {
                    if (!characterMaps.containsKey(mapName)) {
                        throw new javax.xml.transform.TransformerConfigurationException(
                            "XTSE1590: use-character-maps in xsl:output references undefined character-map '" + mapName + "'");
                    }
                }
                for (CharacterMap charMap : characterMaps.values()) {
                    for (String refName : charMap.getUseCharacterMaps()) {
                        if (!characterMaps.containsKey(refName)) {
                            throw new javax.xml.transform.TransformerConfigurationException(
                                "XTSE1590: character-map '" + charMap.getName() + 
                                "' references undefined character-map '" + refName + "'");
                        }
                    }
                }
            
                // Validate character-map circular references (XTSE1600)
                for (CharacterMap charMap : characterMaps.values()) {
                    Set<String> visited = new HashSet<>();
                    if (hasCircularReference(charMap.getName(), visited)) {
                        throw new javax.xml.transform.TransformerConfigurationException(
                            "XTSE1600: Circular reference in character-map '" + charMap.getName() + "'");
                    }
                }
            }
            
            // Validate duplicate named templates with same import precedence (XTSE0660)
            Map<String, List<TemplateRule>> namedTemplatesByName = new HashMap<>();
            for (TemplateRule rule : templateRules) {
                if (rule.getName() != null) {
                    namedTemplatesByName.computeIfAbsent(rule.getName(), k -> new ArrayList<>()).add(rule);
                }
            }
            for (Map.Entry<String, List<TemplateRule>> entry : namedTemplatesByName.entrySet()) {
                List<TemplateRule> templates = entry.getValue();
                if (templates.size() > 1) {
                    // Check for duplicates at the same precedence level
                    int maxPrecedence = 0;
                    for (TemplateRule t : templates) {
                        if (t.getImportPrecedence() > maxPrecedence) {
                            maxPrecedence = t.getImportPrecedence();
                        }
                    }
                    int countAtMaxPrecedence = 0;
                    for (TemplateRule t : templates) {
                        if (t.getImportPrecedence() == maxPrecedence) {
                            countAtMaxPrecedence++;
                        }
                    }
                    if (countAtMaxPrecedence > 1) {
                        throw new javax.xml.transform.TransformerConfigurationException(
                            "XTSE0660: Duplicate named template '" + entry.getKey() + 
                            "' with same import precedence");
                    }
                }
            }
            
            return new CompiledStylesheet(this);
        }
        
        /**
         * Checks for circular references in character maps.
         */
        private boolean hasCircularReference(String mapName, Set<String> visited) {
            if (visited.contains(mapName)) {
                return true;  // Circular reference detected
            }
            CharacterMap charMap = characterMaps.get(mapName);
            if (charMap == null) {
                return false;  // Reference not found (handled by XTSE1590 check)
            }
            visited.add(mapName);
            for (String refName : charMap.getUseCharacterMaps()) {
                if (hasCircularReference(refName, new HashSet<>(visited))) {
                    return true;
                }
            }
            return false;
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
        this.stripSpaceDeclarations = Collections.unmodifiableList(new ArrayList<>(builder.stripSpaceDeclarations));
        this.preserveSpaceDeclarations = Collections.unmodifiableList(new ArrayList<>(builder.preserveSpaceDeclarations));
        // Build legacy lists for backward compatibility
        List<String> stripPatterns = new ArrayList<>();
        for (SpaceDeclaration decl : builder.stripSpaceDeclarations) {
            if (!stripPatterns.contains(decl.pattern)) {
                stripPatterns.add(decl.pattern);
            }
        }
        this.stripSpaceElements = Collections.unmodifiableList(stripPatterns);
        List<String> preservePatterns = new ArrayList<>();
        for (SpaceDeclaration decl : builder.preserveSpaceDeclarations) {
            if (!preservePatterns.contains(decl.pattern)) {
                preservePatterns.add(decl.pattern);
            }
        }
        this.preserveSpaceElements = Collections.unmodifiableList(preservePatterns);
        this.decimalFormats = Collections.unmodifiableMap(new HashMap<>(builder.decimalFormats));
        this.characterMaps = Collections.unmodifiableMap(new HashMap<>(builder.characterMaps));
        this.accumulators = Collections.unmodifiableMap(new HashMap<>(builder.accumulators));
        this.modeDeclarations = Collections.unmodifiableMap(new HashMap<>(builder.modeDeclarations));
        this.namespaceBindings = Collections.unmodifiableMap(new HashMap<>(builder.namespaceBindings));
        this.excludedNamespaceURIs = Collections.unmodifiableSet(new HashSet<>(builder.excludedNamespaceURIs));
        this.userFunctions = Collections.unmodifiableMap(new HashMap<>(builder.userFunctions));
        this.importedSchemas = Collections.unmodifiableMap(new HashMap<>(builder.importedSchemas));
        this.defaultValidation = builder.defaultValidation;
        this.baseURI = builder.baseURI;
        this.version = builder.version;
        this.defaultCollation = builder.defaultCollation;
        this.globalContextItemType = builder.globalContextItemType;
        this.globalContextItemUse = builder.globalContextItemUse;
        this.packageName = builder.packageName;
        this.packageVersion = builder.packageVersion;
        this.defaultMode = builder.defaultMode;
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
     * Returns all required parameters (XSLT 2.0+).
     * These are global parameters with required="yes" that must have values
     * supplied during transformation.
     *
     * @return list of required parameter names (immutable)
     */
    public List<GlobalVariable> getRequiredParameters() {
        List<GlobalVariable> required = new ArrayList<>();
        for (GlobalVariable var : globalVariables) {
            if (var.isParam() && var.isRequired()) {
                required.add(var);
            }
        }
        return Collections.unmodifiableList(required);
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
     * Returns all attribute sets.
     *
     * @return immutable map of attribute set name to attribute set
     */
    public Map<String, AttributeSet> getAttributeSets() {
        return Collections.unmodifiableMap(attributeSets);
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
     * Returns the stylesheet-level default-mode (XSLT 3.0).
     *
     * @return the default mode name, or null for unnamed mode
     */
    public String getDefaultMode() {
        return defaultMode;
    }

    /**
     * Returns the package name (XSLT 3.0).
     *
     * @return the package name URI, or null if not a package
     */
    public String getPackageName() {
        return packageName;
    }

    /**
     * Returns the package version (XSLT 3.0).
     *
     * @return the package version string, or null if not a package
     */
    public String getPackageVersion() {
        return packageVersion;
    }

    /**
     * Returns the default collation URI (XSLT 2.0+).
     *
     * <p>The default collation is used when no explicit collation is specified
     * for operations like xsl:sort, string comparison functions, and value/general
     * comparisons.
     *
     * @return the default collation URI, or null for Unicode codepoint collation
     */
    public String getDefaultCollation() {
        return defaultCollation;
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
     * Returns a character map by name.
     *
     * @param name the name (or empty string for unnamed)
     * @return the character map, or null if not found
     */
    public CharacterMap getCharacterMap(String name) {
        return characterMaps.get(name != null ? name : "");
    }

    /**
     * Returns all character maps.
     *
     * @return map of name to CharacterMap (immutable)
     */
    public Map<String, CharacterMap> getCharacterMaps() {
        return characterMaps;
    }

    /**
     * Applies all the specified character maps to a string.
     * Resolves use-character-maps references recursively.
     *
     * @param input the input string
     * @param mapNames the names of character maps to apply
     * @return the string with all character mappings applied
     */
    public String applyCharacterMaps(String input, List<String> mapNames) {
        if (mapNames == null || mapNames.isEmpty()) {
            return input;
        }
        
        // Build combined mapping from all character maps
        Map<Integer, String> combinedMappings = new HashMap<>();
        Set<String> visited = new HashSet<>();
        for (String mapName : mapNames) {
            collectMappings(mapName, combinedMappings, visited);
        }
        
        if (combinedMappings.isEmpty()) {
            return input;
        }
        
        // Apply mappings (code point aware for supplementary characters)
        StringBuilder result = new StringBuilder(input.length());
        int i = 0;
        while (i < input.length()) {
            int codePoint = input.codePointAt(i);
            String replacement = combinedMappings.get(codePoint);
            if (replacement != null) {
                result.append(replacement);
            } else {
                result.appendCodePoint(codePoint);
            }
            i += Character.charCount(codePoint);
        }
        return result.toString();
    }
    
    /**
     * Recursively collects mappings from a character map and its referenced maps.
     */
    private void collectMappings(String mapName, Map<Integer, String> mappings, Set<String> visited) {
        if (visited.contains(mapName)) {
            return;  // Avoid circular references
        }
        visited.add(mapName);
        
        CharacterMap map = characterMaps.get(mapName);
        if (map == null) {
            return;
        }
        
        // First, process referenced character maps (lower precedence)
        for (String refName : map.getUseCharacterMaps()) {
            collectMappings(refName, mappings, visited);
        }
        
        // Then add this map's mappings (higher precedence - overwrites)
        mappings.putAll(map.getMappings());
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
     * Sets the streamability analysis results.
     *
     * @param analysis the analysis results
     */
    public void setStreamabilityAnalysis(StreamabilityAnalyzer.StylesheetStreamability analysis) {
        this.streamabilityAnalysis = analysis;
    }

    /**
     * Returns the streamability analysis results.
     *
     * @return the analysis results, or null if not yet analyzed
     */
    public StreamabilityAnalyzer.StylesheetStreamability getStreamabilityAnalysis() {
        return streamabilityAnalysis;
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
     * Returns the strip-space declarations with import precedence information.
     *
     * @return the strip-space declarations (immutable)
     */
    public List<SpaceDeclaration> getStripSpaceDeclarations() {
        return stripSpaceDeclarations;
    }
    
    /**
     * Returns the preserve-space declarations with import precedence information.
     *
     * @return the preserve-space declarations (immutable)
     */
    public List<SpaceDeclaration> getPreserveSpaceDeclarations() {
        return preserveSpaceDeclarations;
    }

    /**
     * Returns true if whitespace should be stripped for the given element.
     * Uses import precedence and priority to resolve conflicts.
     *
     * @param namespaceURI the element namespace
     * @param localName the element local name
     * @return true if whitespace should be stripped
     */
    public boolean shouldStripWhitespace(String namespaceURI, String localName) {
        // Find the highest-precedence matching strip declaration
        SpaceDeclaration bestStrip = null;
        for (SpaceDeclaration decl : stripSpaceDeclarations) {
            if (matchesSpacePattern(decl.pattern, namespaceURI, localName)) {
                if (bestStrip == null || 
                    decl.importPrecedence > bestStrip.importPrecedence ||
                    (decl.importPrecedence == bestStrip.importPrecedence && 
                     decl.getPriority() > bestStrip.getPriority())) {
                    bestStrip = decl;
                }
            }
        }
        
        // Find the highest-precedence matching preserve declaration
        SpaceDeclaration bestPreserve = null;
        for (SpaceDeclaration decl : preserveSpaceDeclarations) {
            if (matchesSpacePattern(decl.pattern, namespaceURI, localName)) {
                if (bestPreserve == null || 
                    decl.importPrecedence > bestPreserve.importPrecedence ||
                    (decl.importPrecedence == bestPreserve.importPrecedence && 
                     decl.getPriority() > bestPreserve.getPriority())) {
                    bestPreserve = decl;
                }
            }
        }
        
        // If no matches, default is to preserve (not strip)
        if (bestStrip == null) {
            return false;
        }
        if (bestPreserve == null) {
            return true;
        }
        
        // Both match - compare by import precedence
        if (bestStrip.importPrecedence > bestPreserve.importPrecedence) {
            return true;  // Strip has higher precedence
        }
        if (bestPreserve.importPrecedence > bestStrip.importPrecedence) {
            return false;  // Preserve has higher precedence
        }
        
        // Same import precedence - compare by priority (specificity)
        if (bestStrip.getPriority() > bestPreserve.getPriority()) {
            return true;  // Strip is more specific
        }
        if (bestPreserve.getPriority() > bestStrip.getPriority()) {
            return false;  // Preserve is more specific
        }
        
        // Equal precedence and priority - preserve wins (safer default)
        return false;
    }
    
    /**
     * Checks if an element matches a strip-space/preserve-space pattern.
     */
    private boolean matchesSpacePattern(String pattern, String namespaceURI, String localName) {
        if ("*".equals(pattern)) {
            return true;
        }
        if (pattern.startsWith("{")) {
            // Clark notation: {uri}localname, {uri}*, or {*}localname
            int closeBrace = pattern.indexOf('}');
            if (closeBrace > 0) {
                String patternUri = pattern.substring(1, closeBrace);
                String patternLocal = pattern.substring(closeBrace + 1);
                
                // Check namespace match (unless * meaning any namespace)
                if (!"*".equals(patternUri)) {
                    String elemUri = namespaceURI != null ? namespaceURI : "";
                    if (!patternUri.equals(elemUri)) {
                        return false;
                    }
                }
                
                // Check local name match
                return "*".equals(patternLocal) || patternLocal.equals(localName);
            }
        }
        // Simple local name - matches elements in no namespace
        if (namespaceURI != null && !namespaceURI.isEmpty()) {
            return false;
        }
        return pattern.equals(localName);
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
    public ValidationMode getDefaultValidation() {
        return defaultValidation;
    }

    // ========================================================================
    // XSLT 3.0 Global Context Item
    // ========================================================================

    /**
     * Sets the global context item type (from xsl:global-context-item as attribute).
     *
     * @param type the expected type (e.g., "element()")
     */
    public void setGlobalContextItemType(String type) {
        this.globalContextItemType = type;
    }

    /**
     * Returns the global context item type.
     *
     * @return the type, or null if not declared
     */
    public String getGlobalContextItemType() {
        return globalContextItemType;
    }

    /**
     * Sets the global context item use (from xsl:global-context-item use attribute).
     *
     * @param use "required", "optional", or "absent"
     */
    public void setGlobalContextItemUse(String use) {
        this.globalContextItemUse = use;
    }

    /**
     * Returns the global context item use.
     *
     * @return "required", "optional", "absent", or null (defaults to "optional")
     */
    public String getGlobalContextItemUse() {
        return globalContextItemUse;
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

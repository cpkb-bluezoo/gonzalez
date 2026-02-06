/*
 * CompiledPackage.java
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

import java.util.*;

/**
 * A compiled XSLT 3.0 package.
 *
 * <p>Packages are the unit of modularity in XSLT 3.0. A package wraps a 
 * {@link CompiledStylesheet} with package-specific metadata including:
 * <ul>
 *   <li>Package name (a URI that uniquely identifies the package)</li>
 *   <li>Package version (for version matching during package resolution)</li>
 *   <li>Component visibility (controlling what is exported)</li>
 *   <li>Dependencies on other packages</li>
 * </ul>
 *
 * <p>Packages support component visibility, allowing selective export
 * of templates, functions, variables, attribute sets, and modes.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 * @see <a href="https://www.w3.org/TR/xslt-30/#packages-and-modules">XSLT 3.0 Packages</a>
 */
public final class CompiledPackage {

    private final String packageName;
    private final String packageVersion;
    private final CompiledStylesheet stylesheet;
    private final List<PackageDependency> dependencies;
    
    // Override visibility maps (component identifier -> visibility)
    // Used when xsl:expose modifies default visibility
    private final Map<String, ComponentVisibility> templateVisibility;
    private final Map<String, ComponentVisibility> functionVisibility;
    private final Map<String, ComponentVisibility> variableVisibility;
    private final Map<String, ComponentVisibility> attributeSetVisibility;
    private final Map<String, ComponentVisibility> modeVisibility;

    /**
     * Creates a compiled package.
     *
     * @param packageName the package name URI
     * @param packageVersion the package version string
     * @param stylesheet the compiled stylesheet contents
     * @param dependencies the package dependencies
     */
    public CompiledPackage(String packageName, String packageVersion,
                          CompiledStylesheet stylesheet,
                          List<PackageDependency> dependencies) {
        this.packageName = packageName;
        this.packageVersion = packageVersion;
        this.stylesheet = stylesheet;
        this.dependencies = dependencies != null ? 
            Collections.unmodifiableList(new ArrayList<>(dependencies)) :
            Collections.emptyList();
        this.templateVisibility = new HashMap<>();
        this.functionVisibility = new HashMap<>();
        this.variableVisibility = new HashMap<>();
        this.attributeSetVisibility = new HashMap<>();
        this.modeVisibility = new HashMap<>();
    }

    /**
     * Private constructor for builder.
     */
    private CompiledPackage(Builder builder) {
        this.packageName = builder.packageName;
        this.packageVersion = builder.packageVersion;
        this.stylesheet = builder.stylesheet;
        this.dependencies = Collections.unmodifiableList(new ArrayList<>(builder.dependencies));
        this.templateVisibility = new HashMap<>(builder.templateVisibility);
        this.functionVisibility = new HashMap<>(builder.functionVisibility);
        this.variableVisibility = new HashMap<>(builder.variableVisibility);
        this.attributeSetVisibility = new HashMap<>(builder.attributeSetVisibility);
        this.modeVisibility = new HashMap<>(builder.modeVisibility);
    }

    /**
     * Returns the package name (a URI).
     *
     * @return the package name
     */
    public String getPackageName() {
        return packageName;
    }

    /**
     * Returns the package version.
     *
     * @return the version string
     */
    public String getPackageVersion() {
        return packageVersion;
    }

    /**
     * Returns the underlying compiled stylesheet.
     *
     * @return the stylesheet
     */
    public CompiledStylesheet getStylesheet() {
        return stylesheet;
    }

    /**
     * Returns the package dependencies.
     *
     * @return immutable list of dependencies
     */
    public List<PackageDependency> getDependencies() {
        return dependencies;
    }

    // ==================== Component Access Methods ====================

    /**
     * Returns all public templates (templates with PUBLIC, FINAL, or ABSTRACT visibility).
     *
     * @return list of accessible templates
     */
    public List<TemplateRule> getPublicTemplates() {
        List<TemplateRule> result = new ArrayList<>();
        for (TemplateRule t : stylesheet.getTemplateRules()) {
            if (getTemplateVisibility(t).isAccessible()) {
                result.add(t);
            }
        }
        return result;
    }

    /**
     * Returns all public named templates.
     *
     * @return map of name to template for accessible named templates
     */
    public Map<String, TemplateRule> getPublicNamedTemplates() {
        Map<String, TemplateRule> result = new HashMap<>();
        for (TemplateRule template : stylesheet.getTemplateRules()) {
            String name = template.getName();
            if (name != null && getTemplateVisibility(template).isAccessible()) {
                result.put(name, template);
            }
        }
        return result;
    }

    /**
     * Returns all public user-defined functions.
     *
     * @return list of accessible functions
     */
    public List<UserFunction> getPublicFunctions() {
        List<UserFunction> result = new ArrayList<>();
        for (UserFunction f : stylesheet.getUserFunctions().values()) {
            if (getFunctionVisibility(f).isAccessible()) {
                result.add(f);
            }
        }
        return result;
    }

    /**
     * Returns all public global variables and parameters.
     *
     * @return list of accessible global variables
     */
    public List<GlobalVariable> getPublicVariables() {
        List<GlobalVariable> result = new ArrayList<>();
        for (GlobalVariable v : stylesheet.getGlobalVariables()) {
            if (getVariableVisibility(v).isAccessible()) {
                result.add(v);
            }
        }
        return result;
    }

    /**
     * Returns all public attribute sets.
     *
     * @return list of accessible attribute sets
     */
    public List<AttributeSet> getPublicAttributeSets() {
        List<AttributeSet> result = new ArrayList<>();
        for (AttributeSet a : stylesheet.getAttributeSets().values()) {
            if (getAttributeSetVisibility(a).isAccessible()) {
                result.add(a);
            }
        }
        return result;
    }

    /**
     * Returns all public modes.
     *
     * @return list of accessible mode declarations
     */
    public List<ModeDeclaration> getPublicModes() {
        List<ModeDeclaration> result = new ArrayList<>();
        for (ModeDeclaration m : stylesheet.getModeDeclarations().values()) {
            if (getModeVisibility(m).isAccessible()) {
                result.add(m);
            }
        }
        return result;
    }

    // ==================== Visibility Retrieval ====================

    /**
     * Gets the effective visibility for a template.
     *
     * @param template the template
     * @return the visibility
     */
    public ComponentVisibility getTemplateVisibility(TemplateRule template) {
        String key = getTemplateKey(template);
        ComponentVisibility override = templateVisibility.get(key);
        if (override != null) {
            return override;
        }
        return template.getVisibility();
    }

    /**
     * Gets the effective visibility for a function.
     *
     * @param function the function
     * @return the visibility
     */
    public ComponentVisibility getFunctionVisibility(UserFunction function) {
        String key = function.getKey();
        ComponentVisibility override = functionVisibility.get(key);
        if (override != null) {
            return override;
        }
        return function.getVisibility();
    }

    /**
     * Gets the effective visibility for a global variable.
     *
     * @param variable the variable
     * @return the visibility
     */
    public ComponentVisibility getVariableVisibility(GlobalVariable variable) {
        String key = variable.getExpandedName();
        ComponentVisibility override = variableVisibility.get(key);
        if (override != null) {
            return override;
        }
        return variable.getVisibility();
    }

    /**
     * Gets the effective visibility for an attribute set.
     *
     * @param attributeSet the attribute set
     * @return the visibility
     */
    public ComponentVisibility getAttributeSetVisibility(AttributeSet attributeSet) {
        String key = attributeSet.getName();
        ComponentVisibility override = attributeSetVisibility.get(key);
        if (override != null) {
            return override;
        }
        return attributeSet.getVisibility();
    }

    /**
     * Gets the effective visibility for a mode.
     *
     * @param mode the mode declaration
     * @return the visibility
     */
    public ComponentVisibility getModeVisibility(ModeDeclaration mode) {
        String key = mode.getName() != null ? mode.getName() : "#default";
        ComponentVisibility override = modeVisibility.get(key);
        if (override != null) {
            return override;
        }
        return mode.getComponentVisibility();
    }

    // ==================== Abstract Component Checking ====================

    /**
     * Returns all abstract templates that must be overridden.
     *
     * @return list of abstract templates
     */
    public List<TemplateRule> getAbstractTemplates() {
        List<TemplateRule> result = new ArrayList<>();
        for (TemplateRule t : stylesheet.getTemplateRules()) {
            if (getTemplateVisibility(t) == ComponentVisibility.ABSTRACT) {
                result.add(t);
            }
        }
        return result;
    }

    /**
     * Returns all abstract functions that must be overridden.
     *
     * @return list of abstract functions
     */
    public List<UserFunction> getAbstractFunctions() {
        List<UserFunction> result = new ArrayList<>();
        for (UserFunction f : stylesheet.getUserFunctions().values()) {
            if (getFunctionVisibility(f) == ComponentVisibility.ABSTRACT) {
                result.add(f);
            }
        }
        return result;
    }

    /**
     * Returns all abstract variables that must be overridden.
     *
     * @return list of abstract variables
     */
    public List<GlobalVariable> getAbstractVariables() {
        List<GlobalVariable> result = new ArrayList<>();
        for (GlobalVariable v : stylesheet.getGlobalVariables()) {
            if (getVariableVisibility(v) == ComponentVisibility.ABSTRACT) {
                result.add(v);
            }
        }
        return result;
    }

    /**
     * Returns true if this package has any abstract components that require
     * implementation before use.
     *
     * @return true if has abstract components
     */
    public boolean hasAbstractComponents() {
        return !getAbstractTemplates().isEmpty() ||
               !getAbstractFunctions().isEmpty() ||
               !getAbstractVariables().isEmpty();
    }

    // ==================== Helper Methods ====================

    /**
     * Creates a unique key for a template (for visibility lookups).
     */
    private static String getTemplateKey(TemplateRule template) {
        if (template.getName() != null) {
            return "name:" + template.getName();
        } else if (template.getMatchPattern() != null) {
            String mode = template.getMode() != null ? template.getMode() : "#default";
            return "match:" + template.getMatchPattern() + ":mode:" + mode;
        }
        return "unknown";
    }

    @Override
    public String toString() {
        return "CompiledPackage[name=" + packageName + 
               ", version=" + packageVersion + "]";
    }

    // ==================== Dependency ====================

    /**
     * Represents a dependency on another package.
     */
    public static class PackageDependency {
        private final String packageName;
        private final String versionConstraint;
        private final CompiledPackage resolvedPackage;
        private final List<AcceptDeclaration> acceptDeclarations;
        private final List<OverrideDeclaration> overrideDeclarations;

        /**
         * Creates a package dependency.
         *
         * @param packageName the package name
         * @param versionConstraint the version constraint (e.g., "1.0", "*")
         */
        public PackageDependency(String packageName, String versionConstraint) {
            this(packageName, versionConstraint, null, 
                 Collections.emptyList(), Collections.emptyList());
        }

        /**
         * Creates a package dependency with resolved package.
         *
         * @param packageName the package name
         * @param versionConstraint the version constraint
         * @param resolvedPackage the resolved package (may be null if not yet resolved)
         * @param acceptDeclarations the accept declarations filtering components
         * @param overrideDeclarations the override declarations
         */
        public PackageDependency(String packageName, String versionConstraint,
                                CompiledPackage resolvedPackage,
                                List<AcceptDeclaration> acceptDeclarations,
                                List<OverrideDeclaration> overrideDeclarations) {
            this.packageName = packageName;
            this.versionConstraint = versionConstraint != null ? versionConstraint : "*";
            this.resolvedPackage = resolvedPackage;
            this.acceptDeclarations = acceptDeclarations != null ?
                Collections.unmodifiableList(new ArrayList<>(acceptDeclarations)) :
                Collections.emptyList();
            this.overrideDeclarations = overrideDeclarations != null ?
                Collections.unmodifiableList(new ArrayList<>(overrideDeclarations)) :
                Collections.emptyList();
        }

        public String getPackageName() { return packageName; }
        public String getVersionConstraint() { return versionConstraint; }
        public CompiledPackage getResolvedPackage() { return resolvedPackage; }
        public List<AcceptDeclaration> getAcceptDeclarations() { return acceptDeclarations; }
        public List<OverrideDeclaration> getOverrideDeclarations() { return overrideDeclarations; }

        /**
         * Returns a new dependency with the resolved package set.
         *
         * @param pkg the resolved package
         * @return new dependency with the package
         */
        public PackageDependency withResolvedPackage(CompiledPackage pkg) {
            return new PackageDependency(packageName, versionConstraint, pkg,
                                         acceptDeclarations, overrideDeclarations);
        }
    }

    // ==================== Builder ====================

    /**
     * Builder for constructing CompiledPackage instances.
     */
    public static class Builder {
        private String packageName;
        private String packageVersion;
        private CompiledStylesheet stylesheet;
        private final List<PackageDependency> dependencies = new ArrayList<>();
        private final Map<String, ComponentVisibility> templateVisibility = new HashMap<>();
        private final Map<String, ComponentVisibility> functionVisibility = new HashMap<>();
        private final Map<String, ComponentVisibility> variableVisibility = new HashMap<>();
        private final Map<String, ComponentVisibility> attributeSetVisibility = new HashMap<>();
        private final Map<String, ComponentVisibility> modeVisibility = new HashMap<>();

        /**
         * Sets the package name.
         *
         * @param name the package name URI
         * @return this builder
         */
        public Builder setPackageName(String name) {
            this.packageName = name;
            return this;
        }

        /**
         * Sets the package version.
         *
         * @param version the version string
         * @return this builder
         */
        public Builder setPackageVersion(String version) {
            this.packageVersion = version;
            return this;
        }

        /**
         * Sets the underlying stylesheet.
         *
         * @param stylesheet the compiled stylesheet
         * @return this builder
         */
        public Builder setStylesheet(CompiledStylesheet stylesheet) {
            this.stylesheet = stylesheet;
            return this;
        }

        /**
         * Adds a package dependency.
         *
         * @param dependency the dependency
         * @return this builder
         */
        public Builder addDependency(PackageDependency dependency) {
            dependencies.add(dependency);
            return this;
        }

        /**
         * Sets visibility for a named template.
         *
         * @param templateName the template name
         * @param visibility the visibility
         * @return this builder
         */
        public Builder setTemplateVisibility(String templateName, ComponentVisibility visibility) {
            templateVisibility.put("name:" + templateName, visibility);
            return this;
        }

        /**
         * Sets visibility for a match template.
         *
         * @param matchPattern the match pattern
         * @param mode the mode (null for default)
         * @param visibility the visibility
         * @return this builder
         */
        public Builder setMatchTemplateVisibility(String matchPattern, String mode, ComponentVisibility visibility) {
            String m = mode != null ? mode : "#default";
            templateVisibility.put("match:" + matchPattern + ":mode:" + m, visibility);
            return this;
        }

        /**
         * Sets visibility for a function.
         *
         * @param functionKey the function key (namespace#localName#arity)
         * @param visibility the visibility
         * @return this builder
         */
        public Builder setFunctionVisibility(String functionKey, ComponentVisibility visibility) {
            functionVisibility.put(functionKey, visibility);
            return this;
        }

        /**
         * Sets visibility for a global variable.
         *
         * @param expandedName the variable's expanded name
         * @param visibility the visibility
         * @return this builder
         */
        public Builder setVariableVisibility(String expandedName, ComponentVisibility visibility) {
            variableVisibility.put(expandedName, visibility);
            return this;
        }

        /**
         * Sets visibility for an attribute set.
         *
         * @param name the attribute set name
         * @param visibility the visibility
         * @return this builder
         */
        public Builder setAttributeSetVisibility(String name, ComponentVisibility visibility) {
            attributeSetVisibility.put(name, visibility);
            return this;
        }

        /**
         * Sets visibility for a mode.
         *
         * @param modeName the mode name (or "#default")
         * @param visibility the visibility
         * @return this builder
         */
        public Builder setModeVisibility(String modeName, ComponentVisibility visibility) {
            modeVisibility.put(modeName, visibility);
            return this;
        }

        /**
         * Builds the compiled package.
         *
         * @return the new CompiledPackage
         * @throws IllegalStateException if stylesheet is not set
         */
        public CompiledPackage build() {
            if (stylesheet == null) {
                throw new IllegalStateException("Stylesheet is required");
            }
            return new CompiledPackage(this);
        }
    }
}

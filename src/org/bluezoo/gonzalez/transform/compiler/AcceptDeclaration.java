/*
 * AcceptDeclaration.java
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

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Represents an xsl:accept declaration within xsl:use-package.
 *
 * <p>The xsl:accept declaration filters which components are imported from
 * a used package and what visibility they have in the using stylesheet.
 * It can be used to:
 * <ul>
 *   <li>Hide components (visibility="hidden")</li>
 *   <li>Make public components private</li>
 *   <li>Make components final to prevent further overriding</li>
 * </ul>
 *
 * <p>Example:
 * <pre>
 * &lt;xsl:accept component="function" names="*:internal-*" visibility="hidden"/&gt;
 * </pre>
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 * @see <a href="https://www.w3.org/TR/xslt-30/#element-accept">XSLT 3.0 xsl:accept</a>
 */
public final class AcceptDeclaration {

    /**
     * Component types that can be selected.
     */
    public enum ComponentType {
        TEMPLATE("template"),
        FUNCTION("function"),
        VARIABLE("variable"),
        ATTRIBUTE_SET("attribute-set"),
        MODE("mode"),
        ALL("*");

        private final String value;

        ComponentType(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }

        /**
         * Parses a component type from an attribute value.
         *
         * @param value the attribute value
         * @return the component type, or null if invalid
         */
        public static ComponentType parse(String value) {
            if (value == null) {
                return null;
            }
            switch (value.toLowerCase()) {
                case "template": return TEMPLATE;
                case "function": return FUNCTION;
                case "variable": return VARIABLE;
                case "attribute-set": return ATTRIBUTE_SET;
                case "mode": return MODE;
                case "*": return ALL;
                default: return null;
            }
        }
    }

    private final ComponentType componentType;
    private final String namesPattern;  // EQName pattern list (whitespace-separated)
    private final ComponentVisibility visibility;
    private transient Pattern[] compiledPatterns;  // Lazily compiled regex patterns

    /**
     * Creates an accept declaration.
     *
     * @param componentType the type of component to match
     * @param namesPattern the names pattern (EQName wildcards, whitespace-separated)
     * @param visibility the visibility to apply to matching components
     */
    public AcceptDeclaration(ComponentType componentType, String namesPattern,
                            ComponentVisibility visibility) {
        this.componentType = componentType;
        this.namesPattern = namesPattern;
        this.visibility = visibility;
    }

    /**
     * Returns the component type selector.
     *
     * @return the component type
     */
    public ComponentType getComponentType() {
        return componentType;
    }

    /**
     * Returns the names pattern.
     *
     * @return the pattern string
     */
    public String getNamesPattern() {
        return namesPattern;
    }

    /**
     * Returns the visibility to apply.
     *
     * @return the visibility
     */
    public ComponentVisibility getVisibility() {
        return visibility;
    }

    /**
     * Checks if this declaration applies to the given component type.
     *
     * @param type the component type to check
     * @return true if this declaration applies
     */
    public boolean matchesType(ComponentType type) {
        return componentType == ComponentType.ALL || componentType == type;
    }

    /**
     * Checks if this declaration matches a component name.
     *
     * @param componentName the component name to check (may include namespace)
     * @return true if the name matches the pattern
     */
    public boolean matchesName(String componentName) {
        if (namesPattern == null || namesPattern.isEmpty() || "*".equals(namesPattern)) {
            return true;
        }
        
        Pattern[] patterns = getCompiledPatterns();
        for (Pattern pattern : patterns) {
            if (pattern.matcher(componentName).matches()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Compiles the names pattern to regex patterns for matching.
     * Supports:
     * - * (matches any local name)
     * - prefix:* (matches any local name in namespace)
     * - *:localname (matches localname in any namespace)
     * - prefix:localname (exact match)
     */
    private Pattern[] getCompiledPatterns() {
        if (compiledPatterns == null) {
            String[] parts = namesPattern.split("\\s+");
            compiledPatterns = new Pattern[parts.length];
            for (int i = 0; i < parts.length; i++) {
                compiledPatterns[i] = compileNamePattern(parts[i]);
            }
        }
        return compiledPatterns;
    }

    private static final Map<String, Pattern> namePatternCache = new HashMap<>();

    /**
     * Compiles a single EQName pattern to a regex pattern.
     */
    private static Pattern compileNamePattern(String namePattern) {
        Pattern cached = namePatternCache.get(namePattern);
        if (cached != null) {
            return cached;
        }
        // Handle wildcards in the pattern
        String step1 = namePattern.replace(".", "\\.");
        String step2 = step1.replace("{", "\\{");
        String step3 = step2.replace("}", "\\}");
        String regex = step3.replace("*", ".*");

        cached = Pattern.compile("^" + regex + "$");
        namePatternCache.put(namePattern, cached);
        return cached;
    }

    @Override
    public String toString() {
        return "accept[component=" + componentType.getValue() + 
               ", names=" + namesPattern + 
               ", visibility=" + visibility + "]";
    }
}

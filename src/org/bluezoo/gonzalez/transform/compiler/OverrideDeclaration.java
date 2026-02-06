/*
 * OverrideDeclaration.java
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

import org.bluezoo.gonzalez.transform.ast.XSLTNode;

/**
 * Represents an xsl:override declaration within xsl:use-package.
 *
 * <p>The xsl:override element contains replacement definitions for components
 * from a used package. It can replace:
 * <ul>
 *   <li>Templates (xsl:template)</li>
 *   <li>Functions (xsl:function)</li>
 *   <li>Variables (xsl:variable)</li>
 *   <li>Parameters (xsl:param)</li>
 *   <li>Attribute sets (xsl:attribute-set)</li>
 * </ul>
 *
 * <p>Constraints:
 * <ul>
 *   <li>Can only override components with visibility PUBLIC or ABSTRACT</li>
 *   <li>Cannot override components with visibility FINAL (error XTSE3005)</li>
 *   <li>ABSTRACT components MUST be overridden (error XTSE3010)</li>
 *   <li>Override signature must be compatible with original</li>
 * </ul>
 *
 * <p>Example:
 * <pre>
 * &lt;xsl:override&gt;
 *   &lt;xsl:template name="format-date"&gt;
 *     &lt;!-- Replacement implementation --&gt;
 *   &lt;/xsl:template&gt;
 * &lt;/xsl:override&gt;
 * </pre>
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 * @see <a href="https://www.w3.org/TR/xslt-30/#element-override">XSLT 3.0 xsl:override</a>
 */
public final class OverrideDeclaration {

    /**
     * Type of component being overridden.
     */
    public enum OverrideType {
        TEMPLATE,
        FUNCTION,
        VARIABLE,
        PARAM,
        ATTRIBUTE_SET
    }

    private final OverrideType type;
    private final String name;           // Component name (or match pattern for templates)
    private final String mode;           // Template mode (for match templates)
    private final TemplateRule overrideTemplate;     // For template overrides
    private final UserFunction overrideFunction;     // For function overrides
    private final GlobalVariable overrideVariable;   // For variable/param overrides
    private final AttributeSet overrideAttributeSet; // For attribute-set overrides

    /**
     * Creates a template override.
     *
     * @param template the replacement template
     * @return the override declaration
     */
    public static OverrideDeclaration forTemplate(TemplateRule template) {
        return new OverrideDeclaration(OverrideType.TEMPLATE, 
            template.getName() != null ? template.getName() : 
                (template.getMatchPattern() != null ? template.getMatchPattern().toString() : null),
            template.getMode(),
            template, null, null, null);
    }

    /**
     * Creates a function override.
     *
     * @param function the replacement function
     * @return the override declaration
     */
    public static OverrideDeclaration forFunction(UserFunction function) {
        return new OverrideDeclaration(OverrideType.FUNCTION,
            "{" + function.getNamespaceURI() + "}" + function.getLocalName() + "#" + function.getArity(),
            null, null, function, null, null);
    }

    /**
     * Creates a variable override.
     *
     * @param variable the replacement variable
     * @return the override declaration
     */
    public static OverrideDeclaration forVariable(GlobalVariable variable) {
        OverrideType type = variable.isParam() ? OverrideType.PARAM : OverrideType.VARIABLE;
        return new OverrideDeclaration(type, variable.getExpandedName(),
            null, null, null, variable, null);
    }

    /**
     * Creates an attribute set override.
     *
     * @param attributeSet the replacement attribute set
     * @return the override declaration
     */
    public static OverrideDeclaration forAttributeSet(AttributeSet attributeSet) {
        return new OverrideDeclaration(OverrideType.ATTRIBUTE_SET, attributeSet.getName(),
            null, null, null, null, attributeSet);
    }

    private OverrideDeclaration(OverrideType type, String name, String mode,
                               TemplateRule template, UserFunction function,
                               GlobalVariable variable, AttributeSet attributeSet) {
        this.type = type;
        this.name = name;
        this.mode = mode;
        this.overrideTemplate = template;
        this.overrideFunction = function;
        this.overrideVariable = variable;
        this.overrideAttributeSet = attributeSet;
    }

    /**
     * Returns the type of component being overridden.
     *
     * @return the override type
     */
    public OverrideType getType() {
        return type;
    }

    /**
     * Returns the component name or identifier.
     *
     * @return the name
     */
    public String getName() {
        return name;
    }

    /**
     * Returns the template mode (for template overrides).
     *
     * @return the mode, or null
     */
    public String getMode() {
        return mode;
    }

    /**
     * Returns the override template (for TEMPLATE type).
     *
     * @return the template, or null
     */
    public TemplateRule getOverrideTemplate() {
        return overrideTemplate;
    }

    /**
     * Returns the override function (for FUNCTION type).
     *
     * @return the function, or null
     */
    public UserFunction getOverrideFunction() {
        return overrideFunction;
    }

    /**
     * Returns the override variable (for VARIABLE/PARAM type).
     *
     * @return the variable, or null
     */
    public GlobalVariable getOverrideVariable() {
        return overrideVariable;
    }

    /**
     * Returns the override attribute set (for ATTRIBUTE_SET type).
     *
     * @return the attribute set, or null
     */
    public AttributeSet getOverrideAttributeSet() {
        return overrideAttributeSet;
    }

    /**
     * Creates a key for looking up the original component being overridden.
     *
     * @return the lookup key
     */
    public String getOriginalComponentKey() {
        switch (type) {
            case TEMPLATE:
                if (mode != null) {
                    return name + "#" + mode;
                }
                return name;
            case FUNCTION:
            case VARIABLE:
            case PARAM:
            case ATTRIBUTE_SET:
                return name;
            default:
                return name;
        }
    }

    @Override
    public String toString() {
        return "override[type=" + type + ", name=" + name + 
               (mode != null ? ", mode=" + mode : "") + "]";
    }
}

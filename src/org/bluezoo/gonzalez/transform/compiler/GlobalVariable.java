/*
 * GlobalVariable.java
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

import org.bluezoo.gonzalez.QName;
import org.bluezoo.gonzalez.transform.ast.SequenceNode;
import org.bluezoo.gonzalez.transform.xpath.XPathExpression;
import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;

/**
 * A global variable or parameter (xsl:variable or xsl:param at top level).
 *
 * <p>Variable names in XSLT can be QNames with namespace prefixes.
 * The name is stored as a QName with resolved namespace URI.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class GlobalVariable {

    private final QName name;
    private final boolean isParam;
    private final XPathExpression selectExpr;
    private final SequenceNode content;
    private final XPathValue staticValue;  // Pre-computed value for static variables (XSLT 3.0)
    private final int importPrecedence;    // Import precedence for XTSE0630 detection
    private final String asType;           // XSLT 2.0 type annotation (e.g., "element()*")
    private final ComponentVisibility visibility;  // XSLT 3.0 package visibility
    private final boolean required;         // XSLT 2.0+ required="yes" for parameters (XTDE0050)

    /**
     * Creates a global variable or parameter.
     *
     * @param name the variable name as a QName
     * @param isParam true for parameter, false for variable
     * @param selectExpr the select expression (may be null)
     * @param content the content (may be null)
     */
    public GlobalVariable(QName name, boolean isParam, 
                         XPathExpression selectExpr, SequenceNode content) {
        this(name, isParam, selectExpr, content, 0, null);
    }

    /**
     * Creates a global variable or parameter with import precedence.
     *
     * @param name the variable name as a QName
     * @param isParam true for parameter, false for variable
     * @param selectExpr the select expression (may be null)
     * @param content the content (may be null)
     * @param importPrecedence the import precedence level
     */
    public GlobalVariable(QName name, boolean isParam, 
                         XPathExpression selectExpr, SequenceNode content,
                         int importPrecedence) {
        this(name, isParam, selectExpr, content, importPrecedence, null);
    }

    /**
     * Creates a global variable or parameter with import precedence and type annotation.
     *
     * @param name the variable name as a QName
     * @param isParam true for parameter, false for variable
     * @param selectExpr the select expression (may be null)
     * @param content the content (may be null)
     * @param importPrecedence the import precedence level
     * @param asType the type annotation (may be null)
     */
    public GlobalVariable(QName name, boolean isParam, 
                         XPathExpression selectExpr, SequenceNode content,
                         int importPrecedence, String asType) {
        this(name, isParam, selectExpr, content, importPrecedence, asType, ComponentVisibility.PUBLIC);
    }

    /**
     * Creates a global variable or parameter with all options including visibility.
     *
     * @param name the variable name as a QName
     * @param isParam true for parameter, false for variable
     * @param selectExpr the select expression (may be null)
     * @param content the content (may be null)
     * @param importPrecedence the import precedence level
     * @param asType the type annotation (may be null)
     * @param visibility the package visibility (XSLT 3.0)
     */
    public GlobalVariable(QName name, boolean isParam, 
                         XPathExpression selectExpr, SequenceNode content,
                         int importPrecedence, String asType,
                         ComponentVisibility visibility) {
        this(name, isParam, selectExpr, content, importPrecedence, asType, visibility, false);
    }

    /**
     * Creates a global variable or parameter with all options including visibility and required flag.
     *
     * @param name the variable name as a QName
     * @param isParam true for parameter, false for variable
     * @param selectExpr the select expression (may be null)
     * @param content the content (may be null)
     * @param importPrecedence the import precedence level
     * @param asType the type annotation (may be null)
     * @param visibility the package visibility (XSLT 3.0)
     * @param required true if this parameter requires a value (XTDE0050)
     */
    public GlobalVariable(QName name, boolean isParam, 
                         XPathExpression selectExpr, SequenceNode content,
                         int importPrecedence, String asType,
                         ComponentVisibility visibility, boolean required) {
        this.name = name;
        this.isParam = isParam;
        this.selectExpr = selectExpr;
        this.content = content;
        this.staticValue = null;
        this.importPrecedence = importPrecedence;
        this.asType = asType;
        this.visibility = visibility != null ? visibility : ComponentVisibility.PUBLIC;
        this.required = required && isParam;  // Only params can be required
    }

    /**
     * Creates a static global variable with a pre-computed value.
     *
     * @param name the variable name as a QName
     * @param isParam true for parameter, false for variable
     * @param staticValue the pre-computed value
     */
    public GlobalVariable(QName name, boolean isParam, XPathValue staticValue) {
        this(name, isParam, staticValue, 0);
    }

    /**
     * Creates a static global variable with a pre-computed value and import precedence.
     *
     * @param name the variable name as a QName
     * @param isParam true for parameter, false for variable
     * @param staticValue the pre-computed value
     * @param importPrecedence the import precedence level
     */
    public GlobalVariable(QName name, boolean isParam, XPathValue staticValue, int importPrecedence) {
        this(name, isParam, staticValue, importPrecedence, ComponentVisibility.PUBLIC);
    }

    /**
     * Creates a static global variable with a pre-computed value, import precedence, and visibility.
     *
     * @param name the variable name as a QName
     * @param isParam true for parameter, false for variable
     * @param staticValue the pre-computed value
     * @param importPrecedence the import precedence level
     * @param visibility the package visibility (XSLT 3.0)
     */
    public GlobalVariable(QName name, boolean isParam, XPathValue staticValue, 
                         int importPrecedence, ComponentVisibility visibility) {
        this.name = name;
        this.isParam = isParam;
        this.selectExpr = null;
        this.content = null;
        this.staticValue = staticValue;
        this.importPrecedence = importPrecedence;
        this.asType = null;
        this.visibility = visibility != null ? visibility : ComponentVisibility.PUBLIC;
        this.required = false;  // Static variables cannot be required
    }

    /**
     * Returns the variable name as a QName.
     *
     * @return the QName
     */
    public QName getQName() {
        return name;
    }

    /**
     * Returns the namespace URI.
     *
     * @return the namespace URI, or empty string if no namespace
     */
    public String getNamespaceURI() {
        return name.getURI();
    }

    /**
     * Returns the local name.
     *
     * @return the local name
     */
    public String getLocalName() {
        return name.getLocalName();
    }

    /**
     * Returns the expanded name in Clark notation {uri}localName.
     * Used for variable lookup and matching.
     *
     * @return the expanded name
     */
    public String getExpandedName() {
        return name.toString();
    }
    
    /**
     * Returns the full name (for display/debugging).
     * Returns the local name, or {uri}localName if namespaced.
     *
     * @return the display name
     */
    public String getName() {
        return name.toString();
    }

    /**
     * Returns true if this is a parameter.
     *
     * @return true for parameter, false for variable
     */
    public boolean isParam() {
        return isParam;
    }

    /**
     * Returns the select expression.
     *
     * @return the expression, or null
     */
    public XPathExpression getSelectExpr() {
        return selectExpr;
    }

    /**
     * Returns the content.
     *
     * @return the content, or null
     */
    public SequenceNode getContent() {
        return content;
    }

    /**
     * Returns the pre-computed static value (for static variables).
     *
     * @return the static value, or null if not a static variable
     */
    public XPathValue getStaticValue() {
        return staticValue;
    }

    /**
     * Returns true if this is a static variable.
     *
     * @return true if static value is set
     */
    public boolean isStatic() {
        return staticValue != null;
    }

    /**
     * Returns the import precedence level.
     *
     * @return the import precedence (higher values = higher precedence)
     */
    public int getImportPrecedence() {
        return importPrecedence;
    }

    /**
     * Returns the type annotation (e.g., "element()*", "processing-instruction()").
     *
     * @return the as type, or null if not specified
     */
    public String getAsType() {
        return asType;
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
     * Returns true if this parameter is required (XSLT 2.0+).
     * A required parameter must have a value supplied at transformation time.
     *
     * @return true if this is a required parameter
     */
    public boolean isRequired() {
        return required;
    }

    /**
     * Creates a copy of this global variable with a different visibility.
     *
     * @param newVisibility the new visibility
     * @return a new GlobalVariable with the specified visibility
     */
    public GlobalVariable withVisibility(ComponentVisibility newVisibility) {
        if (staticValue != null) {
            return new GlobalVariable(name, isParam, staticValue, importPrecedence, newVisibility);
        } else {
            return new GlobalVariable(name, isParam, selectExpr, content, 
                                      importPrecedence, asType, newVisibility, required);
        }
    }

    /**
     * Returns true if the as type indicates a sequence type (contains *, +, or ?).
     *
     * @return true if sequence type
     */
    public boolean isSequenceType() {
        if (asType == null) {
            return false;
        }
        return asType.contains("*") || asType.contains("+") || asType.contains("?");
    }

    /**
     * Returns true if the as type indicates a single node type.
     *
     * @return true if single node type
     */
    public boolean isSingleNodeType() {
        if (asType == null) {
            return false;
        }
        String type = asType.trim();
        if (type.contains("*") || type.contains("+") || type.contains("?")) {
            return false;
        }
        return type.startsWith("element(") || type.startsWith("node(") || 
               type.startsWith("attribute(") || type.startsWith("document-node(") ||
               type.startsWith("text(") || type.startsWith("comment(") ||
               type.startsWith("processing-instruction(");
    }

    @Override
    public String toString() {
        return (isParam ? "param " : "variable ") + getName();
    }

}

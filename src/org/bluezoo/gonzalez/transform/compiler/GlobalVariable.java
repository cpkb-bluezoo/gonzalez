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
        this.name = name;
        this.isParam = isParam;
        this.selectExpr = selectExpr;
        this.content = content;
        this.staticValue = null;
    }

    /**
     * Creates a static global variable with a pre-computed value.
     *
     * @param name the variable name as a QName
     * @param isParam true for parameter, false for variable
     * @param staticValue the pre-computed value
     */
    public GlobalVariable(QName name, boolean isParam, XPathValue staticValue) {
        this.name = name;
        this.isParam = isParam;
        this.selectExpr = null;
        this.content = null;
        this.staticValue = staticValue;
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

    @Override
    public String toString() {
        return (isParam ? "param " : "variable ") + getName();
    }

}

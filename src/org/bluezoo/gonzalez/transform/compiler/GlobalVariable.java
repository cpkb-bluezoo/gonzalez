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

import org.bluezoo.gonzalez.transform.ast.SequenceNode;
import org.bluezoo.gonzalez.transform.xpath.XPathExpression;

/**
 * A global variable or parameter (xsl:variable or xsl:param at top level).
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class GlobalVariable {

    private final String name;
    private final boolean isParam;
    private final XPathExpression selectExpr;
    private final SequenceNode content;

    /**
     * Creates a global variable or parameter.
     *
     * @param name the variable name
     * @param isParam true for parameter, false for variable
     * @param selectExpr the select expression (may be null)
     * @param content the content (may be null)
     */
    public GlobalVariable(String name, boolean isParam, XPathExpression selectExpr, SequenceNode content) {
        this.name = name;
        this.isParam = isParam;
        this.selectExpr = selectExpr;
        this.content = content;
    }

    /**
     * Returns the variable name.
     *
     * @return the name
     */
    public String getName() {
        return name;
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

    @Override
    public String toString() {
        return (isParam ? "param " : "variable ") + name;
    }

}

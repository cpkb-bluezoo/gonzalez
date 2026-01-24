/*
 * TemplateParameter.java
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
 * A template parameter (xsl:param).
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class TemplateParameter {

    private final String name;
    private final XPathExpression selectExpr;
    private final SequenceNode defaultContent;

    /**
     * Creates a template parameter.
     *
     * @param name the parameter name
     * @param selectExpr the select expression (may be null)
     * @param defaultContent the default content (may be null)
     */
    public TemplateParameter(String name, XPathExpression selectExpr, SequenceNode defaultContent) {
        this.name = name;
        this.selectExpr = selectExpr;
        this.defaultContent = defaultContent;
    }

    /**
     * Returns the parameter name.
     *
     * @return the name
     */
    public String getName() {
        return name;
    }

    /**
     * Returns the select expression.
     *
     * @return the expression, or null if content-specified
     */
    public XPathExpression getSelectExpr() {
        return selectExpr;
    }

    /**
     * Returns the default content.
     *
     * @return the content, or null if select-specified
     */
    public SequenceNode getDefaultContent() {
        return defaultContent;
    }

    /**
     * Returns true if this parameter has a default value.
     *
     * @return true if has default
     */
    public boolean hasDefault() {
        return selectExpr != null || (defaultContent != null && !defaultContent.isEmpty());
    }

    @Override
    public String toString() {
        return "param " + name;
    }

}

/*
 * ExpressionHolder.java
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

import java.util.List;

import org.bluezoo.gonzalez.transform.xpath.XPathExpression;

/**
 * Interface for XSLT AST nodes that contain XPath expressions.
 *
 * <p>Used by {@link StreamabilityAnalyzer} to collect all XPath
 * expressions from a node tree for streaming classification, replacing
 * the fragile reflection-based approach.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public interface ExpressionHolder {

    /**
     * Returns all XPath expressions used by this XSLT instruction.
     * Does not recurse into child nodes; the caller handles recursion.
     *
     * @return list of expressions (never null, may be empty)
     */
    List<XPathExpression> getExpressions();

}

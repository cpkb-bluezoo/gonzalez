/*
 * package-info.java
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

/**
 * XPath expression AST nodes.
 *
 * <p>This package contains the abstract syntax tree nodes that represent
 * parsed XPath expressions. Each node implements {@link org.bluezoo.gonzalez.transform.xpath.expr.Expr}
 * and can be evaluated against an {@link org.bluezoo.gonzalez.transform.xpath.XPathContext}.
 *
 * <h2>Expression Types</h2>
 * <ul>
 *   <li>{@link org.bluezoo.gonzalez.transform.xpath.expr.Literal} - string and number literals</li>
 *   <li>{@link org.bluezoo.gonzalez.transform.xpath.expr.VariableReference} - variable references ($name)</li>
 *   <li>{@link org.bluezoo.gonzalez.transform.xpath.expr.BinaryExpr} - binary operations</li>
 *   <li>{@link org.bluezoo.gonzalez.transform.xpath.expr.UnaryExpr} - unary negation</li>
 *   <li>{@link org.bluezoo.gonzalez.transform.xpath.expr.FunctionCall} - function invocations</li>
 *   <li>{@link org.bluezoo.gonzalez.transform.xpath.expr.LocationPath} - location paths</li>
 *   <li>{@link org.bluezoo.gonzalez.transform.xpath.expr.FilterExpr} - filter expressions</li>
 *   <li>{@link org.bluezoo.gonzalez.transform.xpath.expr.PathExpr} - path expressions</li>
 * </ul>
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
package org.bluezoo.gonzalez.transform.xpath.expr;

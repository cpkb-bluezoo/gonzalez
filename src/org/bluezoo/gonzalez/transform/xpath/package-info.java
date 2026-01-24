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
 * XPath 1.0 expression parser and evaluator.
 *
 * <p>This package provides a complete XPath 1.0 implementation with
 * streaming-aware evaluation. The parser uses an iterative Pratt
 * (operator precedence) algorithm for predictable resource usage.
 *
 * <h2>Main Components</h2>
 * <ul>
 *   <li>{@code XPathLexer} - tokenizes XPath expressions</li>
 *   <li>{@code XPathParser} - parses tokens into AST using Pratt algorithm</li>
 *   <li>{@code XPathContext} - evaluation context (current node, variables)</li>
 *   <li>{@code XPathExpression} - compiled expression interface</li>
 * </ul>
 *
 * <h2>Sub-packages</h2>
 * <ul>
 *   <li>{@code type} - XPath data types (string, number, boolean, node-set)</li>
 *   <li>{@code expr} - expression AST nodes</li>
 *   <li>{@code axis} - XPath axis implementations</li>
 *   <li>{@code function} - core function library</li>
 *   <li>{@code pattern} - XSLT match pattern support</li>
 * </ul>
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
package org.bluezoo.gonzalez.transform.xpath;

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
 * XSLT Abstract Syntax Tree nodes.
 *
 * <p>This package contains the AST representation of compiled XSLT stylesheets.
 * Each AST node is both a data structure (representing the parsed instruction)
 * and an executor (able to perform the transformation).
 *
 * <h2>Core Classes</h2>
 * <ul>
 *   <li>{@link org.bluezoo.gonzalez.transform.ast.XSLTNode} - base interface for all nodes</li>
 *   <li>{@link org.bluezoo.gonzalez.transform.ast.XSLTInstruction} - base for XSLT instructions</li>
 *   <li>{@link org.bluezoo.gonzalez.transform.ast.SequenceNode} - ordered sequence of nodes</li>
 *   <li>{@link org.bluezoo.gonzalez.transform.ast.LiteralResultElement} - non-XSLT elements</li>
 *   <li>{@link org.bluezoo.gonzalez.transform.ast.LiteralText} - text content</li>
 * </ul>
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
package org.bluezoo.gonzalez.transform.ast;

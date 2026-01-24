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
 * XPath 1.0 function library.
 *
 * <p>This package contains implementations of all 27 core XPath 1.0 functions:
 *
 * <h2>Node-set Functions</h2>
 * <ul>
 *   <li>{@code last()} - returns context size</li>
 *   <li>{@code position()} - returns context position</li>
 *   <li>{@code count(node-set)} - returns number of nodes</li>
 *   <li>{@code id(object)} - selects elements by ID</li>
 *   <li>{@code local-name(node-set?)} - returns local name</li>
 *   <li>{@code namespace-uri(node-set?)} - returns namespace URI</li>
 *   <li>{@code name(node-set?)} - returns qualified name</li>
 * </ul>
 *
 * <h2>String Functions</h2>
 * <ul>
 *   <li>{@code string(object?)} - converts to string</li>
 *   <li>{@code concat(string, string, string*)} - concatenates</li>
 *   <li>{@code starts-with(string, string)} - tests prefix</li>
 *   <li>{@code contains(string, string)} - tests substring</li>
 *   <li>{@code substring-before(string, string)}</li>
 *   <li>{@code substring-after(string, string)}</li>
 *   <li>{@code substring(string, number, number?)}</li>
 *   <li>{@code string-length(string?)}</li>
 *   <li>{@code normalize-space(string?)}</li>
 *   <li>{@code translate(string, string, string)}</li>
 * </ul>
 *
 * <h2>Boolean Functions</h2>
 * <ul>
 *   <li>{@code boolean(object)} - converts to boolean</li>
 *   <li>{@code not(boolean)} - logical negation</li>
 *   <li>{@code true()} - returns true</li>
 *   <li>{@code false()} - returns false</li>
 *   <li>{@code lang(string)} - tests xml:lang</li>
 * </ul>
 *
 * <h2>Number Functions</h2>
 * <ul>
 *   <li>{@code number(object?)} - converts to number</li>
 *   <li>{@code sum(node-set)} - sum of values</li>
 *   <li>{@code floor(number)} - floor</li>
 *   <li>{@code ceiling(number)} - ceiling</li>
 *   <li>{@code round(number)} - round to nearest integer</li>
 * </ul>
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
package org.bluezoo.gonzalez.transform.xpath.function;

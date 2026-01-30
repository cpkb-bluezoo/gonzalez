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
 * Schema validation interfaces for Gonzalez.
 *
 * <p>This package provides a unified interface for accessing post-schema-validation
 * information (PSVI) from multiple schema languages:
 *
 * <ul>
 *   <li>XML Schema (XSD) - full support including complex types</li>
 *   <li>Relax NG - simple type support via XSD Part 2 datatypes</li>
 *   <li>DTD - attribute types (ID, IDREF, etc.)</li>
 * </ul>
 *
 * <p>The core interface is {@link PSVIProvider}, which can be queried during
 * SAX {@code startElement} and {@code endElement} callbacks to retrieve type
 * information for the current element and its attributes.
 *
 * <p>Simple type information is represented by {@link TypedValue}, which provides:
 * <ul>
 *   <li>Datatype name and namespace (typically XSD Part 2 types)</li>
 *   <li>Typed Java value</li>
 *   <li>Original lexical representation</li>
 * </ul>
 *
 * <p>Schema language-specific implementations are in subpackages:
 * <ul>
 *   <li>{@code org.bluezoo.gonzalez.schema.xsd} - XML Schema implementation</li>
 * </ul>
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
package org.bluezoo.gonzalez.schema;

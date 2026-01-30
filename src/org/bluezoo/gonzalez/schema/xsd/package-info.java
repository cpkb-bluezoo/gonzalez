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
 * XML Schema (XSD) implementation of the Gonzalez schema validation interfaces.
 *
 * <p>This package provides a minimal but functional XSD validator:
 *
 * <h2>Schema Model</h2>
 * <ul>
 *   <li>{@link XSDSchema} - container for schema declarations</li>
 *   <li>{@link XSDElement} - element declarations</li>
 *   <li>{@link XSDAttribute} - attribute declarations</li>
 *   <li>{@link XSDType} - base class for types</li>
 *   <li>{@link XSDSimpleType} - simple types with built-in types and facets</li>
 *   <li>{@link XSDComplexType} - complex types with content models</li>
 *   <li>{@link XSDParticle} - content model particles (sequence, choice, all)</li>
 * </ul>
 *
 * <h2>Schema Parsing</h2>
 * <ul>
 *   <li>{@link XSDSchemaParser} - parses .xsd documents into the model</li>
 * </ul>
 *
 * <h2>Validation</h2>
 * <ul>
 *   <li>{@link XSDValidator} - SAX filter implementing {@link org.bluezoo.gonzalez.schema.PSVIProvider}</li>
 *   <li>{@link XSDTypedValue} - typed value implementation</li>
 *   <li>{@link XSDTypeConverter} - lexical to typed value conversion</li>
 * </ul>
 *
 * <h2>Supported XSD Features</h2>
 * <ul>
 *   <li>Global element and attribute declarations</li>
 *   <li>Named and anonymous simple/complex types</li>
 *   <li>All XSD Part 2 built-in datatypes</li>
 *   <li>Facets: enumeration, pattern, min/maxLength, min/max Inclusive/Exclusive</li>
 *   <li>Content models: sequence, choice, all, any</li>
 *   <li>xsi:type, xsi:nil, xsi:schemaLocation support</li>
 *   <li>ID/IDREF/IDREFS uniqueness and reference validation</li>
 * </ul>
 *
 * <h2>Usage Example</h2>
 * <pre>
 * // Parse schema
 * XSDSchema schema = new XSDSchemaParser().parse(schemaInputStream);
 *
 * // Create validating filter
 * XSDValidator validator = new XSDValidator(schema);
 * validator.setParent(xmlReader);
 * validator.setContentHandler(myHandler);
 *
 * // Parse and validate
 * validator.parse(documentInputStream);
 *
 * // Check results
 * if (!validator.isValid()) {
 *     for (String error : validator.getValidationErrors()) {
 *         System.err.println(error);
 *     }
 * }
 * </pre>
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
package org.bluezoo.gonzalez.schema.xsd;

/*
 * ValidationSource.java
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

package org.bluezoo.gonzalez.schema;

/**
 * The schema language used for validation.
 *
 * <p>This enum identifies which schema language was used to validate
 * an XML item. The validation source is provided through the
 * {@link PSVIProvider#getValidationSource()} method during SAX parsing.
 *
 * <p>Different schema languages provide different levels of type
 * information:
 * <ul>
 *   <li>DTD provides basic attribute types</li>
 *   <li>XSD provides full type system with simple and complex types</li>
 *   <li>Relax NG provides types when using XSD datatype library</li>
 *   <li>Schematron provides validation rules but no type information</li>
 * </ul>
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 * @see PSVIProvider#getValidationSource()
 */
public enum ValidationSource {

    /**
     * No schema validation was performed.
     *
     * <p>This indicates that the item was not validated against any schema.
     * Type information is not available.
     */
    NONE,

    /**
     * Validation was performed using a DTD (Document Type Definition).
     *
     * <p>DTD validation provides basic attribute type information:
     * <ul>
     *   <li>ID, IDREF, IDREFS - for identity constraints</li>
     *   <li>NMTOKEN, NMTOKENS - for name tokens</li>
     *   <li>ENTITY, ENTITIES - for entity references</li>
     *   <li>NOTATION - for notation references</li>
     *   <li>Enumerated types - for attribute value lists</li>
     * </ul>
     *
     * <p>DTD does not provide typed values or complex type information.
     */
    DTD,

    /**
     * Validation was performed using XML Schema (XSD).
     *
     * <p>XSD validation provides comprehensive type information:
     * <ul>
     *   <li>Simple types with facets (length, pattern, enumeration, etc.)</li>
     *   <li>Complex types with content models (sequence, choice, all)</li>
     *   <li>Type derivation (restriction, extension)</li>
     *   <li>Typed values converted to Java objects</li>
     *   <li>Full PSVI (Post-Schema-Validation Infoset) information</li>
     * </ul>
     */
    XSD,

    /**
     * Validation was performed using Relax NG.
     *
     * <p>Relax NG validation provides type information when the schema
     * uses the XSD Part 2 datatype library. This allows typed values
     * to be available similar to XSD validation.
     *
     * <p>Relax NG does not provide complex type information or content
     * model validation in the same way as XSD.
     */
    RELAX_NG,

    /**
     * Validation was performed using Schematron.
     *
     * <p>Schematron validation provides validity status based on
     * assertion rules, but does not provide type annotations or
     * typed values. Only validity status (VALID/INVALID) is available.
     */
    SCHEMATRON
}

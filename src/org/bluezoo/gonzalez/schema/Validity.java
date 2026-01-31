/*
 * Validity.java
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
 * Validation status of an element or attribute.
 *
 * <p>This enum represents the result of schema validation for a particular
 * XML item (element or attribute). The status is provided through the
 * {@link PSVIProvider#getValidity()} method during SAX parsing.
 *
 * <p>The validation status reflects whether the item conforms to the
 * constraints defined in the schema (DTD, XSD, etc.). When multiple
 * validators are active, the most restrictive status is returned
 * (INVALID takes precedence over VALID).
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 * @see PSVIProvider#getValidity()
 */
public enum Validity {

    /**
     * The item has not been validated.
     *
     * <p>This status indicates that no schema validation was performed
     * for this item. This occurs when:
     * <ul>
     *   <li>No schema validation is enabled</li>
     *   <li>The schema does not declare or constrain this item</li>
     *   <li>The item is outside the scope of validation</li>
     * </ul>
     */
    NOT_KNOWN,

    /**
     * The item has been validated and is valid according to the schema.
     *
     * <p>This status indicates that the item conforms to all applicable
     * schema constraints, including:
     * <ul>
     *   <li>Type constraints (for attributes and simple content)</li>
     *   <li>Content model constraints (for complex types)</li>
     *   <li>Occurrence constraints (minOccurs, maxOccurs)</li>
     *   <li>Facet constraints (length, pattern, enumeration, etc.)</li>
     * </ul>
     */
    VALID,

    /**
     * The item has been validated and is invalid according to the schema.
     *
     * <p>This status indicates that the item violates one or more schema
     * constraints. Validation errors are typically reported through the
     * SAX {@link org.xml.sax.ErrorHandler} interface.
     */
    INVALID
}

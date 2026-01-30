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
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public enum ValidationSource {

    /**
     * No schema validation was performed.
     */
    NONE,

    /**
     * Validation was performed using a DTD (Document Type Definition).
     * Provides attribute types: ID, IDREF, IDREFS, NMTOKEN, NMTOKENS,
     * ENTITY, ENTITIES, NOTATION, and enumerated types.
     */
    DTD,

    /**
     * Validation was performed using XML Schema (XSD).
     * Provides full type information including simple types, complex types,
     * and derived types.
     */
    XSD,

    /**
     * Validation was performed using Relax NG.
     * Provides simple type information when XSD Part 2 datatypes are used.
     */
    RELAX_NG,

    /**
     * Validation was performed using Schematron.
     * Provides validity status only (no type annotations).
     */
    SCHEMATRON
}

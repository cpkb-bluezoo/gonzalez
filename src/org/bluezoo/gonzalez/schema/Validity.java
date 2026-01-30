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
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public enum Validity {

    /**
     * The item has not been validated.
     * This is the status when no schema validation is in effect,
     * or when the schema does not constrain this item.
     */
    NOT_KNOWN,

    /**
     * The item has been validated and is valid according to the schema.
     */
    VALID,

    /**
     * The item has been validated and is invalid according to the schema.
     */
    INVALID
}

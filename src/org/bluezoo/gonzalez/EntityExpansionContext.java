/*
 * EntityExpansionContext.java
 * Copyright (C) 2025 Chris Burdess
 *
 * This file is part of Gonzalez, a streaming XML parser.
 * For more information please visit https://www.nongnu.org/gonzalez/
 *
 * Gonzalez is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Gonzalez is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Gonzalez.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.bluezoo.gonzalez;

/**
 * Entity expansion contexts as defined in XML 1.0 specification section 4.4.
 * Different contexts have different rules for entity expansion:
 * 
 * <ul>
 * <li><b>CONTENT</b>: Reference in element content (&amp;name;)
 *     - External parsed entities: Allowed (requires async resolution)
 *     - Unparsed entities: Forbidden
 * </li>
 * <li><b>ATTRIBUTE_VALUE</b>: Reference in attribute value (attr="&amp;name;")
 *     - External parsed entities: Forbidden
 *     - Unparsed entities: Forbidden
 * </li>
 * <li><b>ENTITY_ATTRIBUTE_VALUE</b>: Attribute of type ENTITY in attribute value
 *     - References unparsed entity name (no & or ;)
 *     - Must be declared unparsed entity
 * </li>
 * <li><b>ENTITY_VALUE</b>: Reference in entity value (&lt;!ENTITY x "&amp;y;"&gt;)
 *     - External parsed entities: Forbidden
 *     - General entity refs: Bypassed (stored as GeneralEntityReference for later)
 *     - Parameter entity refs: Immediately included
 * </li>
 * <li><b>DTD</b>: Reference in DTD markup
 *     - Only parameter entity refs allowed
 *     - External parameter entities: Allowed (requires async resolution)
 * </li>
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public enum EntityExpansionContext {
    
    /**
     * Reference in element content.
     * External parsed entities are allowed (requires async resolution).
     * Unparsed entities are forbidden.
     */
    CONTENT,
    
    /**
     * Reference in attribute value.
     * External parsed entities are forbidden.
     * Unparsed entities are forbidden.
     */
    ATTRIBUTE_VALUE,
    
    /**
     * Attribute value of type ENTITY.
     * Value is an unparsed entity name (not a reference, no &amp; or ;).
     * Must refer to a declared unparsed entity.
     */
    ENTITY_ATTRIBUTE_VALUE,
    
    /**
     * Reference in entity value (inside &lt;!ENTITY declaration).
     * General entity refs are bypassed (stored for later expansion).
     * Parameter entity refs are immediately included.
     * External entities are forbidden.
     */
    ENTITY_VALUE,
    
    /**
     * Reference in DTD markup.
     * Only parameter entity refs are processed.
     * External parameter entities are allowed (requires async resolution).
     */
    DTD
}


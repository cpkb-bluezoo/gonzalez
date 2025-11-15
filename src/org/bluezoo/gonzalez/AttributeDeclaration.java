/*
 * AttributeDeclaration.java
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
 * Represents an attribute declaration from the DTD.
 *
 * <p>Attribute declarations define the allowed attributes for an element,
 * their types, default values, and whether they are required, implied, or fixed.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public class AttributeDeclaration {

    /**
     * The attribute name.
     */
    public String name;

    /**
     * The attribute type (CDATA, ID, IDREF, IDREFS, ENTITY, ENTITIES,
     * NMTOKEN, NMTOKENS, NOTATION, or an enumeration).
     */
    public String type;

    /**
     * The default value mode: #REQUIRED, #IMPLIED, #FIXED, or null for default value.
     */
    public String mode;

    /**
     * The default value, or null if not specified or if mode is #REQUIRED or #IMPLIED.
     */
    public String defaultValue;

    /**
     * Returns true if this attribute is required.
     */
    public boolean isRequired() {
        return "#REQUIRED".equals(mode);
    }

    /**
     * Returns true if this attribute has a default value.
     */
    public boolean hasDefault() {
        return defaultValue != null && mode == null;
    }

    /**
     * Returns true if this attribute has a fixed value.
     */
    public boolean isFixed() {
        return "#FIXED".equals(mode);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(name).append(" ").append(type);
        if (mode != null) {
            sb.append(" ").append(mode);
        }
        if (defaultValue != null) {
            sb.append(" \"").append(defaultValue).append("\"");
        }
        return sb.toString();
    }
}


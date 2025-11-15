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
 * <p>This class is immutable and optimized for memory efficiency:
 * - Uses string interning for common type values
 * - Shares default value strings where possible
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class AttributeDeclaration {

    /**
     * The attribute name.
     */
    public final String name;

    /**
     * The attribute type (CDATA, ID, IDREF, IDREFS, ENTITY, ENTITIES,
     * NMTOKEN, NMTOKENS, NOTATION, or an enumeration).
     */
    public final String type;

    /**
     * The default value mode: #REQUIRED, #IMPLIED, #FIXED, or null for default value.
     */
    public final String mode;

    /**
     * The default value, or null if not specified or if mode is #REQUIRED or #IMPLIED.
     */
    public final String defaultValue;

    /**
     * Creates a new attribute declaration.
     *
     * @param name the attribute name
     * @param type the attribute type
     * @param mode the default value mode (#REQUIRED, #IMPLIED, #FIXED, or null)
     * @param defaultValue the default value (null if not specified)
     */
    public AttributeDeclaration(String name, String type, String mode, String defaultValue) {
        this.name = name;
        // Intern common type strings to save memory
        this.type = internType(type);
        this.mode = mode;
        this.defaultValue = defaultValue;
    }

    /**
     * Interns common attribute type strings to reduce memory usage.
     */
    private static String internType(String type) {
        // Common types - use constants to share memory
        switch (type) {
            case "CDATA": return "CDATA";
            case "ID": return "ID";
            case "IDREF": return "IDREF";
            case "IDREFS": return "IDREFS";
            case "ENTITY": return "ENTITY";
            case "ENTITIES": return "ENTITIES";
            case "NMTOKEN": return "NMTOKEN";
            case "NMTOKENS": return "NMTOKENS";
            case "NOTATION": return "NOTATION";
            default: return type; // Enumeration or other
        }
    }

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


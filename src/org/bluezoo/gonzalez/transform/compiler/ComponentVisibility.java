/*
 * ComponentVisibility.java
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

package org.bluezoo.gonzalez.transform.compiler;

/**
 * Visibility levels for XSLT 3.0 package components.
 *
 * <p>In the XSLT 3.0 package system, each component (template, function,
 * variable, attribute-set, mode) has a visibility that determines how it
 * can be accessed and overridden by using packages.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 * @see <a href="https://www.w3.org/TR/xslt-30/#packages-and-modules">XSLT 3.0 Packages</a>
 */
public enum ComponentVisibility {

    /**
     * Component is accessible to using packages and can be overridden.
     * This is the default visibility for most components.
     */
    PUBLIC,

    /**
     * Component is internal to the package only.
     * Not visible to using packages.
     */
    PRIVATE,

    /**
     * Component is accessible to using packages but cannot be overridden.
     * Attempting to override a final component is a static error (XTSE3005).
     */
    FINAL,

    /**
     * Component must be implemented (overridden) by using packages.
     * A package with unimplemented abstract components cannot be used
     * directly as a stylesheet. (XTSE3010)
     */
    ABSTRACT,

    /**
     * Component has been explicitly hidden via xsl:accept.
     * This is used when importing from a package to exclude certain components.
     */
    HIDDEN;

    /**
     * Returns true if this visibility allows the component to be accessed
     * from outside the package.
     *
     * @return true if publicly accessible
     */
    public boolean isAccessible() {
        return this == PUBLIC || this == FINAL || this == ABSTRACT;
    }

    /**
     * Returns true if this visibility allows the component to be overridden.
     *
     * @return true if can be overridden
     */
    public boolean isOverridable() {
        return this == PUBLIC || this == ABSTRACT;
    }

    /**
     * Returns true if this component must be overridden before the package
     * can be used as a complete stylesheet.
     *
     * @return true if override is required
     */
    public boolean requiresOverride() {
        return this == ABSTRACT;
    }

    /**
     * Parses a visibility string from XSLT attributes.
     *
     * @param value the attribute value
     * @return the visibility, or null if value is null/empty
     * @throws IllegalArgumentException if value is not a valid visibility
     */
    public static ComponentVisibility parse(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        switch (value.trim().toLowerCase()) {
            case "public":
                return PUBLIC;
            case "private":
                return PRIVATE;
            case "final":
                return FINAL;
            case "abstract":
                return ABSTRACT;
            case "hidden":
                return HIDDEN;
            default:
                throw new IllegalArgumentException(
                    "Invalid visibility: " + value + 
                    " (expected: public, private, final, abstract, or hidden)");
        }
    }

    /**
     * Returns the XSLT attribute value for this visibility.
     *
     * @return the attribute value string
     */
    public String toAttributeValue() {
        return name().toLowerCase();
    }
}

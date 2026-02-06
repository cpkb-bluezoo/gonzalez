/*
 * ValidationMode.java
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

package org.bluezoo.gonzalez.transform;

/**
 * Validation modes for XSLT schema-aware processing.
 *
 * <p>The validation attribute controls how schema validation is applied
 * and whether type annotations are preserved, created, or removed.
 *
 * <h3>Validation Modes:</h3>
 * <ul>
 *   <li><b>STRICT</b> - Perform strict schema validation. The element/attribute
 *       must have a schema declaration. If validation fails, it's an error.</li>
 *   <li><b>LAX</b> - Perform lax validation. If a schema declaration exists,
 *       validate against it. If not, skip validation for that element.</li>
 *   <li><b>PRESERVE</b> - Copy existing type annotations from source to result,
 *       but don't perform new validation. If source has no annotations, result
 *       has xs:untyped/xs:untypedAtomic.</li>
 *   <li><b>STRIP</b> - Remove all type annotations. Result elements have type
 *       xs:untyped, attributes have xs:untypedAtomic.</li>
 * </ul>
 *
 * <h3>Usage in XSLT:</h3>
 * <pre>
 * &lt;xsl:copy-of select="$node" validation="strict"/&gt;
 * &lt;xsl:copy validation="preserve"&gt;...&lt;/xsl:copy&gt;
 * &lt;xsl:element name="foo" validation="strip"&gt;...&lt;/xsl:element&gt;
 * &lt;xsl:result-document href="out.xml" validation="lax"&gt;...&lt;/xsl:result-document&gt;
 * </pre>
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public enum ValidationMode {
    
    /**
     * Strict validation - must have schema declaration, validation required.
     * If validation fails, it's a dynamic error (XTTE1510/XTTE1512).
     */
    STRICT("strict"),
    
    /**
     * Lax validation - validate if schema declaration exists, skip if not.
     */
    LAX("lax"),
    
    /**
     * Preserve existing type annotations from source nodes.
     * No new validation is performed.
     */
    PRESERVE("preserve"),
    
    /**
     * Strip all type annotations - result is xs:untyped/xs:untypedAtomic.
     */
    STRIP("strip");
    
    private final String name;
    
    ValidationMode(String name) {
        this.name = name;
    }
    
    /**
     * Gets the string representation of this validation mode.
     */
    public String getName() {
        return name;
    }
    
    /**
     * Parses a validation mode from a string value.
     *
     * @param value the validation attribute value (e.g., "strict", "lax", "preserve", "strip")
     * @return the corresponding ValidationMode
     * @throws IllegalArgumentException if the value is not a valid validation mode
     */
    public static ValidationMode parse(String value) {
        if (value == null || value.isEmpty()) {
            return null; // No validation specified
        }
        
        switch (value.toLowerCase()) {
            case "strict":
                return STRICT;
            case "lax":
                return LAX;
            case "preserve":
                return PRESERVE;
            case "strip":
                return STRIP;
            default:
                throw new IllegalArgumentException(
                    "Invalid validation mode: '" + value + 
                    "'. Must be 'strict', 'lax', 'preserve', or 'strip'.");
        }
    }
    
    /**
     * Checks if this validation mode performs actual schema validation.
     *
     * @return true for STRICT and LAX, false for PRESERVE and STRIP
     */
    public boolean performsValidation() {
        return this == STRICT || this == LAX;
    }
    
    /**
     * Checks if this validation mode preserves existing type annotations.
     *
     * @return true for PRESERVE, false for STRICT, LAX, and STRIP
     */
    public boolean preservesAnnotations() {
        return this == PRESERVE;
    }
    
    /**
     * Checks if this validation mode strips type annotations.
     *
     * @return true for STRIP, false for others
     */
    public boolean stripsAnnotations() {
        return this == STRIP;
    }
    
    @Override
    public String toString() {
        return name;
    }
}

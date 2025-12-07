/*
 * ElementValidationContext.java
 * Copyright (C) 2025 Chris Burdess
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

package org.bluezoo.gonzalez;

/**
 * Tracks validation state for a single element in the document tree.
 * Combines the element name and its content model validator in a single object
 * to maintain stack consistency.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
class ElementValidationContext {
    /**
     * The element name (for both well-formedness and validation error reporting).
     */
    final String elementName;
    
    /**
     * The content model validator for this element (may be null if validation is disabled).
     */
    final ContentModelValidator validator;
    
    /**
     * The entity expansion depth at which this element was opened.
     * Used to enforce WFC: Parsed Entity - elements opened within an entity must be closed within that entity.
     */
    final int entityExpansionDepth;
    
    /**
     * Creates a new element validation context.
     * 
     * @param elementName the element name
     * @param validator the content model validator (may be null)
     * @param entityExpansionDepth the entity expansion depth when this element was opened
     */
    ElementValidationContext(String elementName, ContentModelValidator validator, int entityExpansionDepth) {
        this.elementName = elementName;
        this.validator = validator;
        this.entityExpansionDepth = entityExpansionDepth;
    }
    
    @Override
    public String toString() {
        return "ElementValidationContext{elementName='" + elementName + 
               "', validator=" + (validator != null ? "present" : "null") + "}";
    }
}


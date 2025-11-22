/*
 * LazyNormalizedValue.java
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
 * Wrapper for an attribute value that delays normalization and String allocation
 * until the value is actually accessed via getValue().
 * 
 * <p>This allows the attribute value to remain in a StringBuilder during parsing,
 * avoiding unnecessary String allocation if the handler never calls getValue().
 * 
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
class LazyNormalizedValue {
    private final StringBuilder rawValue;
    private final String elementName;
    private final String attributeName;
    private String normalized;  // Cached normalized value
    
    LazyNormalizedValue(StringBuilder rawValue, String elementName, String attributeName) {
        this.rawValue = rawValue;
        this.elementName = elementName;
        this.attributeName = attributeName;
    }
    
    /**
     * Gets the normalized String value, performing normalization on first access.
     * 
     * @param normalizer the normalization function
     * @return the normalized String
     */
    String getValue(AttributeValueNormalizer normalizer) {
        if (normalized == null) {
            String raw = rawValue.toString();
            normalized = normalizer.normalize(raw, elementName, attributeName);
        }
        return normalized;
    }
    
    /**
     * Interface for attribute value normalization.
     */
    interface AttributeValueNormalizer {
        String normalize(String rawValue, String elementName, String attributeName);
    }
}


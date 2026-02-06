/*
 * Pattern.java
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

import org.bluezoo.gonzalez.transform.runtime.TransformContext;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNode;
import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;

/**
 * An XSLT match pattern.
 *
 * <p>Patterns are a subset of XPath expressions used in match attributes.
 * Unlike full XPath expressions, patterns are evaluated "bottom-up" - they
 * test whether a given node matches the pattern.
 *
 * <p>XSLT patterns include:
 * <ul>
 *   <li>Location path patterns (e.g., chapter/para)</li>
 *   <li>ID patterns (id("foo"))</li>
 *   <li>Key patterns (key("name", "value"))</li>
 *   <li>Union patterns (pattern | pattern)</li>
 *   <li>Atomic value patterns (XSLT 3.0: .[predicate])</li>
 * </ul>
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public interface Pattern {

    /**
     * Tests if the given node matches this pattern.
     *
     * @param node the node to test
     * @param context the transformation context
     * @return true if the node matches
     */
    boolean matches(XPathNode node, TransformContext context);
    
    /**
     * Tests if the given atomic value matches this pattern (XSLT 3.0).
     * Patterns like ".[. instance of xs:integer]" can match atomic values.
     *
     * @param value the atomic value to test
     * @param context the transformation context
     * @return true if the value matches
     */
    default boolean matchesAtomicValue(XPathValue value, TransformContext context) {
        return false;  // Default: patterns don't match atomic values
    }
    
    /**
     * Returns true if this pattern can match atomic values (XSLT 3.0).
     * Patterns starting with "." followed by a predicate can match atomic values.
     *
     * @return true if this pattern can match atomic values
     */
    default boolean canMatchAtomicValues() {
        return false;  // Default: patterns only match nodes
    }

    /**
     * Returns the default priority for this pattern.
     *
     * <p>Default priorities are:
     * <ul>
     *   <li>-0.5 for node() and *</li>
     *   <li>-0.25 for NCName:*</li>
     *   <li>0.0 for QName and node type tests</li>
     *   <li>0.5 for patterns with predicates</li>
     * </ul>
     *
     * @return the default priority
     */
    double getDefaultPriority();

    /**
     * Returns the pattern string for display.
     *
     * @return the pattern string
     */
    @Override
    String toString();

}

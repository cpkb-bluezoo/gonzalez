/*
 * StaticTypeContext.java
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

package org.bluezoo.gonzalez.transform.xpath;

import org.bluezoo.gonzalez.transform.xpath.function.Function;
import org.bluezoo.gonzalez.transform.xpath.type.SequenceType;

/**
 * Provides compile-time type information for static type analysis.
 *
 * <p>This context is threaded through the expression tree after parsing
 * to enable type inference and static type checking. It supplies variable
 * types (from {@code as} attributes), function signatures, the stylesheet
 * version, and the strictness configuration.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public interface StaticTypeContext {

    /**
     * Returns the declared type of a variable, or null if unknown.
     *
     * @param nsUri the variable namespace URI (may be null)
     * @param localName the variable local name
     * @return the declared sequence type, or null
     */
    SequenceType getVariableType(String nsUri, String localName);

    /**
     * Returns the XSLT version for this compilation context.
     *
     * @return the version (e.g., 1.0, 2.0, 3.0)
     */
    double getXsltVersion();

    /**
     * Returns whether strict (pessimistic) type checking is enabled.
     * When true, expressions that <em>might</em> fail are rejected.
     * When false, only provably incompatible types are rejected.
     *
     * @return true for pessimistic checking, false for optimistic
     */
    boolean isStrictTypeChecking();

    /**
     * Resolves a function by namespace URI and local name, returning the
     * {@link Function} object that carries static type annotations.
     * Returns null if the function is unknown.
     *
     * @param nsUri the function namespace URI (may be null)
     * @param localName the function local name
     * @param arity the number of arguments
     * @return the function, or null
     */
    Function resolveFunction(String nsUri, String localName, int arity);

    /**
     * Returns the maximum processor version supported. This may differ
     * from the stylesheet version (e.g. an XSLT 3.0 processor running
     * a 2.0 stylesheet).
     *
     * @return the processor version (e.g., 3.0)
     */
    default double getProcessorVersion() {
        return getXsltVersion();
    }

    /**
     * Returns the declared type of the context item, or null if unknown.
     *
     * @return the context item type, or null
     */
    SequenceType getContextItemType();
}

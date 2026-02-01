/*
 * UserFunction.java
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.bluezoo.gonzalez.transform.ast.XSLTNode;

/**
 * Represents an XSLT 2.0+ user-defined function (xsl:function).
 *
 * <p>User-defined functions can be called from XPath expressions using
 * their namespace-prefixed name. They receive parameters and return
 * a value computed by executing their body.
 *
 * <p>Example:
 * <pre>
 * &lt;xsl:function name="my:double"&gt;
 *   &lt;xsl:param name="n"/&gt;
 *   &lt;xsl:sequence select="$n * 2"/&gt;
 * &lt;/xsl:function&gt;
 * </pre>
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class UserFunction {

    private final String namespaceURI;
    private final String localName;
    private final List<FunctionParameter> parameters;
    private final XSLTNode body;
    private final String asType; // Optional return type (as="...")
    private final int importPrecedence;
    private final boolean cached; // XSLT 3.0 cache="yes" attribute

    /**
     * Creates a new user-defined function.
     *
     * @param namespaceURI the function namespace URI (required)
     * @param localName the function local name (required)
     * @param parameters the function parameters
     * @param body the function body
     * @param asType optional return type declaration
     * @param importPrecedence the import precedence for conflict resolution
     */
    public UserFunction(String namespaceURI, String localName,
                        List<FunctionParameter> parameters, XSLTNode body,
                        String asType, int importPrecedence) {
        this(namespaceURI, localName, parameters, body, asType, importPrecedence, false);
    }

    /**
     * Creates a new user-defined function with caching option.
     *
     * @param namespaceURI the function namespace URI (required)
     * @param localName the function local name (required)
     * @param parameters the function parameters
     * @param body the function body
     * @param asType optional return type declaration
     * @param importPrecedence the import precedence for conflict resolution
     * @param cached whether to cache function results (XSLT 3.0)
     */
    public UserFunction(String namespaceURI, String localName,
                        List<FunctionParameter> parameters, XSLTNode body,
                        String asType, int importPrecedence, boolean cached) {
        if (namespaceURI == null || namespaceURI.isEmpty()) {
            throw new IllegalArgumentException(
                "User-defined functions must be in a non-null namespace");
        }
        if (localName == null || localName.isEmpty()) {
            throw new IllegalArgumentException("Function name cannot be empty");
        }
        this.namespaceURI = namespaceURI;
        this.localName = localName;
        this.parameters = parameters != null 
            ? Collections.unmodifiableList(new ArrayList<>(parameters))
            : Collections.emptyList();
        this.body = body;
        this.asType = asType;
        this.importPrecedence = importPrecedence;
        this.cached = cached;
    }

    /**
     * Returns the function namespace URI.
     *
     * @return the namespace URI
     */
    public String getNamespaceURI() {
        return namespaceURI;
    }

    /**
     * Returns the function local name.
     *
     * @return the local name
     */
    public String getLocalName() {
        return localName;
    }

    /**
     * Returns the function parameters.
     *
     * @return immutable list of parameters
     */
    public List<FunctionParameter> getParameters() {
        return parameters;
    }

    /**
     * Returns the function body.
     *
     * @return the body node
     */
    public XSLTNode getBody() {
        return body;
    }

    /**
     * Returns the declared return type.
     *
     * @return the type string, or null if not declared
     */
    public String getAsType() {
        return asType;
    }

    /**
     * Returns the import precedence.
     *
     * @return the precedence value (higher = takes priority)
     */
    public int getImportPrecedence() {
        return importPrecedence;
    }

    /**
     * Returns whether this function should cache results (XSLT 3.0).
     *
     * @return true if caching is enabled
     */
    public boolean isCached() {
        return cached;
    }

    /**
     * Returns the number of parameters.
     *
     * @return the parameter count
     */
    public int getArity() {
        return parameters.size();
    }

    /**
     * Returns a unique key for this function (namespace + localname + arity).
     *
     * @return the function key
     */
    public String getKey() {
        return namespaceURI + "#" + localName + "#" + getArity();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("function ");
        sb.append("{");
        sb.append(namespaceURI);
        sb.append("}");
        sb.append(localName);
        sb.append("(");
        for (int i = 0; i < parameters.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(parameters.get(i));
        }
        sb.append(")");
        if (asType != null) {
            sb.append(" as ");
            sb.append(asType);
        }
        return sb.toString();
    }

    /**
     * Represents a function parameter.
     *
     * <p>Function parameters define the signature of a user-defined function.
     * Each parameter has a name and an optional type declaration.
     *
     * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
     */
    public static final class FunctionParameter {
        private final String name;
        private final String asType;

        /**
         * Creates a function parameter.
         *
         * @param name the parameter name
         * @param asType the optional type declaration (as attribute)
         */
        public FunctionParameter(String name, String asType) {
            this.name = name;
            this.asType = asType;
        }

        /**
         * Returns the parameter name.
         *
         * @return the name
         */
        public String getName() {
            return name;
        }

        /**
         * Returns the type declaration.
         *
         * @return the type string, or null if not declared
         */
        public String getAsType() {
            return asType;
        }

        @Override
        public String toString() {
            if (asType != null) {
                return "$" + name + " as " + asType;
            }
            return "$" + name;
        }
    }
}

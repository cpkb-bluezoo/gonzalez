/*
 * VariableScope.java
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

package org.bluezoo.gonzalez.transform.runtime;

import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * Manages variable bindings during XSLT transformation.
 *
 * <p>Variables in XSLT are lexically scoped and immutable. This class
 * provides a stack-based scope management where each template instantiation
 * or xsl:for-each creates a new scope.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class VariableScope {

    /**
     * A single scope level with variable bindings.
     */
    private static class Scope {
        final Map<String, XPathValue> variables = new HashMap<>();
        final Scope parent;

        Scope(Scope parent) {
            this.parent = parent;
        }
    }

    private Scope currentScope;

    /**
     * Creates a new variable scope with an empty root scope.
     */
    public VariableScope() {
        this.currentScope = new Scope(null);
    }

    /**
     * Creates a variable scope as a child of another.
     *
     * @param parent the parent scope
     */
    private VariableScope(Scope parentScope) {
        this.currentScope = new Scope(parentScope);
    }

    /**
     * Pushes a new scope level.
     * Creates a new VariableScope that is a child of this one.
     *
     * @return a new VariableScope with a pushed scope level
     */
    public VariableScope push() {
        return new VariableScope(currentScope);
    }

    /**
     * Returns a scope with only global (root-level) variables visible.
     * Used for attribute sets which should only see top-level variables.
     *
     * @return a new VariableScope that only sees the root scope
     */
    public VariableScope globalOnly() {
        // Find the root scope
        Scope root = currentScope;
        while (root.parent != null) {
            root = root.parent;
        }
        // Create a scope that starts fresh from root
        return new VariableScope(root);
    }

    /**
     * Binds a variable in the current scope.
     * Variables are immutable once bound - rebinding overwrites the value.
     *
     * @param name the variable name (local name only, no namespace)
     * @param value the variable value
     */
    public void bind(String name, XPathValue value) {
        bind(null, name, value);
    }

    /**
     * Binds a variable with namespace in the current scope.
     * Variables are immutable once bound - rebinding overwrites the value.
     *
     * @param namespaceURI the namespace URI (may be null for no namespace)
     * @param localName the local name
     * @param value the variable value
     */
    public void bind(String namespaceURI, String localName, XPathValue value) {
        String key = makeKey(namespaceURI, localName);
        currentScope.variables.put(key, value);
    }

    /**
     * Looks up a variable value.
     * Searches from the current scope up to the root scope.
     *
     * @param name the variable name (local name only)
     * @return the variable value, or null if not bound
     */
    public XPathValue lookup(String name) {
        return lookup(null, name);
    }

    /**
     * Looks up a variable value with namespace.
     * Searches from the current scope up to the root scope.
     *
     * @param namespaceURI the namespace URI (may be null for no namespace)
     * @param localName the local name
     * @return the variable value, or null if not bound
     */
    public XPathValue lookup(String namespaceURI, String localName) {
        String key = makeKey(namespaceURI, localName);
        
        // Search from current scope up to root
        Scope scope = currentScope;
        while (scope != null) {
            XPathValue value = scope.variables.get(key);
            if (value != null) {
                return value;
            }
            scope = scope.parent;
        }
        
        return null;
    }

    /**
     * Returns true if a variable is bound in any scope.
     *
     * @param name the variable name (local name only)
     * @return true if the variable is bound
     */
    public boolean isBound(String name) {
        return lookup(name) != null;
    }

    /**
     * Returns true if a variable with namespace is bound in any scope.
     *
     * @param namespaceURI the namespace URI (may be null for no namespace)
     * @param localName the local name
     * @return true if the variable is bound
     */
    public boolean isBound(String namespaceURI, String localName) {
        return lookup(namespaceURI, localName) != null;
    }

    /**
     * Creates a key for the variable map.
     */
    private static String makeKey(String namespaceURI, String localName) {
        if (namespaceURI == null || namespaceURI.isEmpty()) {
            return localName;
        }
        return "{" + namespaceURI + "}" + localName;
    }

}

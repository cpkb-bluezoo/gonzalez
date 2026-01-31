/*
 * ExtensionRegistry.java
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

package org.bluezoo.gonzalez.transform.extension;

import java.util.*;

/**
 * Registry for XSLT extension functions and elements.
 *
 * <p>Extensions are registered by namespace URI and can be looked up
 * during stylesheet compilation and execution.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class ExtensionRegistry {

    private final Map<String, ExtensionFunction> functions = new HashMap<>();
    private final Map<String, ExtensionElement> elements = new HashMap<>();

    /**
     * Creates a new empty registry.
     *
     * <p>The registry initially contains no registered extension functions
     * or elements. Use {@link #registerFunction(ExtensionFunction)} and
     * {@link #registerElement(ExtensionElement)} to add extensions.
     */
    public ExtensionRegistry() {
    }

    /**
     * Registers an extension function.
     *
     * <p>If a function with the same namespace URI and local name is already
     * registered, it will be replaced with the new function.
     *
     * @param function the function to register, must not be null
     */
    public void registerFunction(ExtensionFunction function) {
        String key = makeKey(function.getNamespaceURI(), function.getLocalName());
        functions.put(key, function);
    }

    /**
     * Unregisters an extension function.
     *
     * <p>If no function with the given namespace URI and local name is
     * registered, this method has no effect.
     *
     * @param namespaceURI the function namespace URI, may be null or empty
     * @param localName the function name, must not be null
     */
    public void unregisterFunction(String namespaceURI, String localName) {
        functions.remove(makeKey(namespaceURI, localName));
    }

    /**
     * Returns an extension function.
     *
     * @param namespaceURI the function namespace URI, may be null or empty
     * @param localName the function name, must not be null
     * @return the function, or null if not registered
     */
    public ExtensionFunction getFunction(String namespaceURI, String localName) {
        return functions.get(makeKey(namespaceURI, localName));
    }

    /**
     * Returns true if a function is registered.
     *
     * @param namespaceURI the function namespace URI, may be null or empty
     * @param localName the function name, must not be null
     * @return true if a function with the given namespace URI and local name
     *         is registered, false otherwise
     */
    public boolean hasFunction(String namespaceURI, String localName) {
        return functions.containsKey(makeKey(namespaceURI, localName));
    }

    /**
     * Registers an extension element.
     *
     * <p>If an element with the same namespace URI and local name is already
     * registered, it will be replaced with the new element.
     *
     * @param element the element to register, must not be null
     */
    public void registerElement(ExtensionElement element) {
        String key = makeKey(element.getNamespaceURI(), element.getLocalName());
        elements.put(key, element);
    }

    /**
     * Unregisters an extension element.
     *
     * <p>If no element with the given namespace URI and local name is
     * registered, this method has no effect.
     *
     * @param namespaceURI the element namespace URI, may be null or empty
     * @param localName the element name, must not be null
     */
    public void unregisterElement(String namespaceURI, String localName) {
        elements.remove(makeKey(namespaceURI, localName));
    }

    /**
     * Returns an extension element.
     *
     * @param namespaceURI the element namespace URI, may be null or empty
     * @param localName the element name, must not be null
     * @return the element, or null if not registered
     */
    public ExtensionElement getElement(String namespaceURI, String localName) {
        return elements.get(makeKey(namespaceURI, localName));
    }

    /**
     * Returns true if an element is registered.
     *
     * @param namespaceURI the element namespace URI, may be null or empty
     * @param localName the element name, must not be null
     * @return true if an element with the given namespace URI and local name
     *         is registered, false otherwise
     */
    public boolean hasElement(String namespaceURI, String localName) {
        return elements.containsKey(makeKey(namespaceURI, localName));
    }

    /**
     * Returns true if the given namespace has any registered extensions.
     *
     * <p>This checks both extension functions and extension elements for
     * the specified namespace URI.
     *
     * @param namespaceURI the namespace URI to check, must not be null
     * @return true if any extensions (functions or elements) are registered
     *         for the given namespace, false otherwise
     */
    public boolean isExtensionNamespace(String namespaceURI) {
        String prefix = "{" + namespaceURI + "}";
        for (String key : functions.keySet()) {
            if (key.startsWith(prefix)) return true;
        }
        for (String key : elements.keySet()) {
            if (key.startsWith(prefix)) return true;
        }
        return false;
    }

    /**
     * Returns all registered function names.
     *
     * <p>The returned set is unmodifiable and contains keys in the format
     * "{namespaceURI}localName". Functions in the default namespace (null or
     * empty namespace URI) are represented as just the local name without
     * the curly braces.
     *
     * @return an unmodifiable set of function name keys, never null
     */
    public Set<String> getFunctionNames() {
        return Collections.unmodifiableSet(functions.keySet());
    }

    /**
     * Returns all registered element names.
     *
     * <p>The returned set is unmodifiable and contains keys in the format
     * "{namespaceURI}localName". Elements in the default namespace (null or
     * empty namespace URI) are represented as just the local name without
     * the curly braces.
     *
     * @return an unmodifiable set of element name keys, never null
     */
    public Set<String> getElementNames() {
        return Collections.unmodifiableSet(elements.keySet());
    }

    private static String makeKey(String namespaceURI, String localName) {
        if (namespaceURI == null || namespaceURI.isEmpty()) {
            return localName;
        }
        return "{" + namespaceURI + "}" + localName;
    }

}

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
     */
    public ExtensionRegistry() {
    }

    /**
     * Registers an extension function.
     *
     * @param function the function to register
     */
    public void registerFunction(ExtensionFunction function) {
        String key = makeKey(function.getNamespaceURI(), function.getLocalName());
        functions.put(key, function);
    }

    /**
     * Unregisters an extension function.
     *
     * @param namespaceURI the function namespace
     * @param localName the function name
     */
    public void unregisterFunction(String namespaceURI, String localName) {
        functions.remove(makeKey(namespaceURI, localName));
    }

    /**
     * Returns an extension function.
     *
     * @param namespaceURI the function namespace
     * @param localName the function name
     * @return the function, or null if not registered
     */
    public ExtensionFunction getFunction(String namespaceURI, String localName) {
        return functions.get(makeKey(namespaceURI, localName));
    }

    /**
     * Returns true if a function is registered.
     *
     * @param namespaceURI the function namespace
     * @param localName the function name
     * @return true if registered
     */
    public boolean hasFunction(String namespaceURI, String localName) {
        return functions.containsKey(makeKey(namespaceURI, localName));
    }

    /**
     * Registers an extension element.
     *
     * @param element the element to register
     */
    public void registerElement(ExtensionElement element) {
        String key = makeKey(element.getNamespaceURI(), element.getLocalName());
        elements.put(key, element);
    }

    /**
     * Unregisters an extension element.
     *
     * @param namespaceURI the element namespace
     * @param localName the element name
     */
    public void unregisterElement(String namespaceURI, String localName) {
        elements.remove(makeKey(namespaceURI, localName));
    }

    /**
     * Returns an extension element.
     *
     * @param namespaceURI the element namespace
     * @param localName the element name
     * @return the element, or null if not registered
     */
    public ExtensionElement getElement(String namespaceURI, String localName) {
        return elements.get(makeKey(namespaceURI, localName));
    }

    /**
     * Returns true if an element is registered.
     *
     * @param namespaceURI the element namespace
     * @param localName the element name
     * @return true if registered
     */
    public boolean hasElement(String namespaceURI, String localName) {
        return elements.containsKey(makeKey(namespaceURI, localName));
    }

    /**
     * Returns true if the given namespace has any registered extensions.
     *
     * @param namespaceURI the namespace
     * @return true if any extensions are registered
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
     * @return set of "{namespace}localName" keys
     */
    public Set<String> getFunctionNames() {
        return Collections.unmodifiableSet(functions.keySet());
    }

    /**
     * Returns all registered element names.
     *
     * @return set of "{namespace}localName" keys
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

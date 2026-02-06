/*
 * BasicXPathContext.java
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

import org.bluezoo.gonzalez.transform.xpath.function.CoreFunctionLibrary;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNode;
import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;

import java.util.HashMap;
import java.util.Map;

/**
 * Basic implementation of XPathContext.
 *
 * <p>This provides a simple, standalone XPath context for expression evaluation.
 * For XSLT transformation, a more sophisticated context is used that integrates
 * with the XSLT runtime.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public class BasicXPathContext implements XPathContext {

    // Thread-local StringBuilder for efficient variable key construction
    private static final ThreadLocal<StringBuilder> KEY_BUILDER = new ThreadLocal<StringBuilder>() {
        @Override
        protected StringBuilder initialValue() {
            return new StringBuilder(64);
        }
    };

    private final XPathNode contextNode;
    private final int contextPosition;
    private final int contextSize;
    private final Map<String, XPathValue> variables;
    private final Map<String, String> namespaces;
    private final XPathFunctionLibrary functionLibrary;

    /**
     * Creates a basic context with just a context node.
     *
     * @param contextNode the context node
     */
    public BasicXPathContext(XPathNode contextNode) {
        this(contextNode, 1, 1);
    }

    /**
     * Creates a basic context with position and size.
     *
     * @param contextNode the context node
     * @param position the context position (1-based)
     * @param size the context size
     */
    public BasicXPathContext(XPathNode contextNode, int position, int size) {
        this(contextNode, position, size, new HashMap<>(), new HashMap<>(), CoreFunctionLibrary.INSTANCE);
    }

    /**
     * Full constructor for creating derived contexts.
     *
     * @param contextNode the context node
     * @param position the context position (1-based)
     * @param size the context size
     * @param variables the variable bindings map
     * @param namespaces the namespace prefix bindings map
     * @param functionLibrary the function library to use
     */
    private BasicXPathContext(XPathNode contextNode, int position, int size,
                              Map<String, XPathValue> variables,
                              Map<String, String> namespaces,
                              XPathFunctionLibrary functionLibrary) {
        this.contextNode = contextNode;
        this.contextPosition = position;
        this.contextSize = size;
        this.variables = variables;
        this.namespaces = namespaces;
        this.functionLibrary = functionLibrary;
    }

    @Override
    public XPathNode getContextNode() {
        return contextNode;
    }

    @Override
    public int getContextPosition() {
        return contextPosition;
    }

    @Override
    public int getContextSize() {
        return contextSize;
    }

    @Override
    public XPathValue getVariable(String namespaceURI, String localName) throws XPathVariableException {
        String key = makeVariableKey(namespaceURI, localName);
        XPathValue value = variables.get(key);
        if (value == null) {
            throw new XPathVariableException(namespaceURI, localName);
        }
        return value;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String resolveNamespacePrefix(String prefix) {
        return namespaces.get(prefix);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public XPathFunctionLibrary getFunctionLibrary() {
        return functionLibrary;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public XPathContext withContextNode(XPathNode node) {
        return new BasicXPathContext(node, contextPosition, contextSize,
            variables, namespaces, functionLibrary);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public XPathContext withPositionAndSize(int position, int size) {
        return new BasicXPathContext(contextNode, position, size,
            variables, namespaces, functionLibrary);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public XPathContext withVariable(String namespaceURI, String localName, XPathValue value) {
        Map<String, XPathValue> newVars = new HashMap<>(variables);
        newVars.put(makeVariableKey(namespaceURI, localName), value);
        return new BasicXPathContext(contextNode, contextPosition, contextSize,
            newVars, namespaces, functionLibrary);
    }

    /**
     * Sets a variable value.
     *
     * @param localName the variable name
     * @param value the variable value
     */
    public void setVariable(String localName, XPathValue value) {
        setVariable(null, localName, value);
    }

    /**
     * Sets a variable value with namespace.
     *
     * @param namespaceURI the namespace URI
     * @param localName the local name
     * @param value the variable value
     */
    public void setVariable(String namespaceURI, String localName, XPathValue value) {
        variables.put(makeVariableKey(namespaceURI, localName), value);
    }

    /**
     * Binds a namespace prefix.
     *
     * @param prefix the prefix
     * @param uri the namespace URI
     */
    public void setNamespace(String prefix, String uri) {
        namespaces.put(prefix, uri);
    }

    /**
     * Creates a variable key for the map.
     * 
     * <p>The key format is "{namespaceURI}localName" if a namespace is provided,
     * or just "localName" if no namespace is provided.
     *
     * @param namespaceURI the variable namespace URI (may be null)
     * @param localName the variable local name
     * @return the variable key string
     */
    private static String makeVariableKey(String namespaceURI, String localName) {
        if (namespaceURI == null || namespaceURI.isEmpty()) {
            return localName;
        }
        StringBuilder sb = KEY_BUILDER.get();
        sb.setLength(0);
        sb.append('{');
        sb.append(namespaceURI);
        sb.append('}');
        sb.append(localName);
        return sb.toString();
    }

}

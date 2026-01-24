/*
 * XPathContext.java
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

import org.bluezoo.gonzalez.transform.xpath.type.XPathNode;
import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;

/**
 * Context for XPath expression evaluation.
 *
 * <p>The evaluation context provides:
 * <ul>
 *   <li>The context node (current node being processed)</li>
 *   <li>The context position (1-based position in current node list)</li>
 *   <li>The context size (total nodes in current node list)</li>
 *   <li>Variable bindings</li>
 *   <li>Function library access</li>
 *   <li>Namespace prefix mappings</li>
 * </ul>
 *
 * <p>The context is typically created by the XSLT runtime and passed to
 * XPath expressions during evaluation.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public interface XPathContext {

    /**
     * Returns the context node.
     *
     * @return the current context node
     */
    XPathNode getContextNode();

    /**
     * Returns the context position (1-based).
     *
     * @return the position in the current node list
     */
    int getContextPosition();

    /**
     * Returns the context size.
     *
     * @return the total number of nodes in the current node list
     */
    int getContextSize();

    /**
     * Returns the value of a variable.
     *
     * @param namespaceURI the variable namespace URI (may be null)
     * @param localName the variable local name
     * @return the variable value
     * @throws XPathVariableException if the variable is not defined
     */
    XPathValue getVariable(String namespaceURI, String localName) throws XPathVariableException;

    /**
     * Resolves a namespace prefix to a URI.
     *
     * @param prefix the namespace prefix
     * @return the namespace URI, or null if not bound
     */
    String resolveNamespacePrefix(String prefix);

    /**
     * Returns the function library for function calls.
     *
     * @return the function library
     */
    XPathFunctionLibrary getFunctionLibrary();

    /**
     * Creates a new context with a different context node.
     *
     * @param node the new context node
     * @return a new context with the given node
     */
    XPathContext withContextNode(XPathNode node);

    /**
     * Creates a new context with different position and size.
     *
     * @param position the new position (1-based)
     * @param size the new size
     * @return a new context with the given position/size
     */
    XPathContext withPositionAndSize(int position, int size);

}

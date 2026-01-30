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

import org.bluezoo.gonzalez.schema.xsd.XSDSimpleType;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNode;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNodeSet;
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
     * Returns the context item (XPath 2.0+).
     * 
     * <p>In XPath 2.0+, the context item can be any item, not just a node.
     * If this returns null, getContextNode() should be used instead.
     *
     * @return the context item, or null if context is a node
     */
    default XPathValue getContextItem() {
        return null;
    }
    
    /**
     * Returns the XSLT current node for the current() function.
     * 
     * <p>In XSLT, current() returns the node that was the context node
     * at the start of the outermost XPath expression evaluation. This is
     * different from the context node, which changes during predicate
     * evaluation and other nested expressions.
     *
     * @return the XSLT current node, or the context node if not in XSLT context
     */
    default XPathNode getXsltCurrentNode() {
        return getContextNode();
    }

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
     * Creates a new context with a different context item (XPath 2.0+).
     *
     * <p>This allows non-node items to be used as the context item
     * for predicate evaluation in sequences.
     *
     * @param item the new context item
     * @return a new context with the given item
     */
    default XPathContext withContextItem(XPathValue item) {
        // Default: if item is a node, use withContextNode
        if (item != null && item.isNodeSet()) {
            XPathNodeSet ns = item.asNodeSet();
            if (!ns.isEmpty()) {
                return withContextNode(ns.iterator().next());
            }
        }
        return this;
    }

    /**
     * Creates a new context with different position and size.
     *
     * @param position the new position (1-based)
     * @param size the new size
     * @return a new context with the given position/size
     */
    XPathContext withPositionAndSize(int position, int size);

    /**
     * Creates a new context with an additional variable binding.
     *
     * @param namespaceURI the variable namespace URI (may be null)
     * @param localName the variable local name
     * @param value the variable value
     * @return a new context with the variable bound
     */
    XPathContext withVariable(String namespaceURI, String localName, XPathValue value);

    /**
     * Returns the accumulator-before value for the named accumulator.
     * This is the value before processing the current node.
     *
     * @param name the accumulator name
     * @return the accumulator value, or null if not found
     */
    default XPathValue getAccumulatorBefore(String name) {
        return null;
    }

    /**
     * Returns the accumulator-after value for the named accumulator.
     * This is the value after processing the current node.
     *
     * @param name the accumulator name
     * @return the accumulator value, or null if not found
     */
    default XPathValue getAccumulatorAfter(String name) {
        return null;
    }

    /**
     * Looks up a schema type by namespace and local name.
     *
     * <p>This is used for schema-aware type operations like
     * {@code cast as} and {@code castable as} with user-defined types.
     *
     * @param namespaceURI the type namespace URI
     * @param localName the type local name
     * @return the schema type, or null if not found
     */
    default XSDSimpleType getSchemaType(String namespaceURI, String localName) {
        return null;
    }

    /**
     * Returns the static base URI from the expression context.
     *
     * <p>In XSLT, this is typically the base URI of the stylesheet element
     * containing the expression, which can be set via xml:base attributes.
     *
     * @return the static base URI, or null if not set
     */
    default String getStaticBaseURI() {
        return null;
    }

    /**
     * Returns the XSLT version of the stylesheet.
     *
     * <p>This is used by system-property('xsl:version') to return the
     * effective version of the stylesheet being executed.
     *
     * @return the XSLT version (e.g., 1.0, 2.0, 3.0), or 1.0 as default
     */
    default double getXsltVersion() {
        return 1.0;
    }

}

/*
 * XPathNode.java
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

package org.bluezoo.gonzalez.transform.xpath.type;

import java.util.Iterator;

/**
 * Represents a node in the XPath data model.
 *
 * <p>The XPath data model views an XML document as a tree of nodes.
 * This interface abstracts over both streaming nodes (limited navigation)
 * and buffered nodes (full navigation capability).
 *
 * <p>Node identity is important in XPath: two node references are the same
 * node if they refer to the same position in the document tree. The
 * {@link #isSameNode(XPathNode)} method tests for node identity.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public interface XPathNode {

    /**
     * Returns the type of this node.
     *
     * @return the node type
     */
    NodeType getNodeType();

    /**
     * Returns the namespace URI of this node's expanded-name.
     *
     * <p>For elements and attributes, this is the namespace URI from the
     * element/attribute declaration or xmlns binding.
     * For namespace nodes, this is always null.
     * For other node types, this returns null.
     *
     * @return the namespace URI, or null if the node has no namespace
     */
    String getNamespaceURI();

    /**
     * Returns the local part of this node's expanded-name.
     *
     * <p>For elements and attributes, this is the local name.
     * For namespace nodes, this is the namespace prefix (or empty for default).
     * For processing instructions, this is the target.
     * For other node types, this returns null.
     *
     * @return the local name, or null
     */
    String getLocalName();

    /**
     * Returns the prefix of this node's qualified name.
     *
     * <p>This returns the namespace prefix used in the original document.
     * May be null or empty even if the node has a namespace URI.
     *
     * @return the prefix, or null/empty
     */
    String getPrefix();

    /**
     * Returns the string-value of this node as defined by XPath 1.0 Section 5.
     *
     * <p>The string-value depends on the node type:
     * <ul>
     *   <li>root/element: concatenation of all descendant text nodes</li>
     *   <li>text: the character data</li>
     *   <li>attribute: the attribute value (normalized)</li>
     *   <li>namespace: the namespace URI</li>
     *   <li>processing-instruction: the PI content (after target)</li>
     *   <li>comment: the comment text</li>
     * </ul>
     *
     * @return the string-value (never null)
     */
    String getStringValue();

    /**
     * Returns the parent of this node.
     *
     * <p>All nodes except the root have a parent. The parent of the root
     * node is null. For attribute and namespace nodes, the parent is the
     * element to which they belong (even though they are not children).
     *
     * @return the parent node, or null for the root node
     */
    XPathNode getParent();

    /**
     * Returns an iterator over this node's children in document order.
     *
     * <p>Only root and element nodes have children. For other node types,
     * this returns an empty iterator.
     *
     * <p>The children do NOT include attribute or namespace nodes.
     *
     * @return iterator over child nodes
     */
    Iterator<XPathNode> getChildren();

    /**
     * Returns an iterator over this node's attributes.
     *
     * <p>Only element nodes have attributes. For other node types,
     * this returns an empty iterator.
     *
     * @return iterator over attribute nodes
     */
    Iterator<XPathNode> getAttributes();

    /**
     * Returns an iterator over namespace nodes in scope for this element.
     *
     * <p>Only element nodes have namespace nodes. For other node types,
     * this returns an empty iterator.
     *
     * <p>This includes inherited namespace declarations from ancestor elements
     * as well as the implicit xml namespace binding.
     *
     * @return iterator over namespace nodes
     */
    Iterator<XPathNode> getNamespaces();

    /**
     * Returns the following sibling of this node, if any.
     *
     * <p>Attribute and namespace nodes do not have siblings and will
     * return null.
     *
     * @return the next sibling, or null
     */
    XPathNode getFollowingSibling();

    /**
     * Returns the preceding sibling of this node, if any.
     *
     * <p>This method may not be available for streaming nodes.
     * Attribute and namespace nodes do not have siblings and will return null.
     *
     * @return the previous sibling, or null
     * @throws UnsupportedOperationException if not available in streaming mode
     */
    XPathNode getPrecedingSibling();

    /**
     * Returns a unique identifier for this node within its document.
     *
     * <p>This is used for document order comparisons and node identity.
     * The format is implementation-defined but must be consistent within
     * a document.
     *
     * @return a unique identifier for this node
     */
    long getDocumentOrder();

    /**
     * Tests whether this node and another refer to the same node.
     *
     * @param other the other node
     * @return true if they are the same node
     */
    boolean isSameNode(XPathNode other);

    /**
     * Returns the root node of the document containing this node.
     *
     * @return the root node
     */
    XPathNode getRoot();

    /**
     * Returns true if this node supports full navigation (buffered).
     *
     * <p>Streaming nodes have limited navigation capability - typically
     * only forward axes are available. Buffered nodes support all axes.
     *
     * @return true if full navigation is available
     */
    boolean isFullyNavigable();

    /**
     * Returns a specific attribute by expanded-name.
     *
     * @param namespaceURI the namespace URI (empty string for no namespace)
     * @param localName the local name
     * @return the attribute node, or null if not found
     */
    default XPathNode getAttribute(String namespaceURI, String localName) {
        Iterator<XPathNode> attrs = getAttributes();
        while (attrs.hasNext()) {
            XPathNode attr = attrs.next();
            String attrNs = attr.getNamespaceURI();
            if (attrNs == null) {
                attrNs = "";
            }
            if (attrNs.equals(namespaceURI) && localName.equals(attr.getLocalName())) {
                return attr;
            }
        }
        return null;
    }

    /**
     * Returns true if this is a root node.
     *
     * @return true if this is the document root
     */
    default boolean isRoot() {
        return getNodeType() == NodeType.ROOT;
    }

    /**
     * Returns true if this is an element node.
     *
     * @return true if this is an element
     */
    default boolean isElement() {
        return getNodeType() == NodeType.ELEMENT;
    }

    /**
     * Returns true if this is a text node.
     *
     * @return true if this is a text node
     */
    default boolean isText() {
        return getNodeType() == NodeType.TEXT;
    }

    /**
     * Returns true if this is an attribute node.
     *
     * @return true if this is an attribute
     */
    default boolean isAttribute() {
        return getNodeType() == NodeType.ATTRIBUTE;
    }

    /**
     * Returns the type annotation namespace URI for this node.
     *
     * <p>For schema-aware processing, elements and attributes may have
     * type annotations from xsl:type or validation.
     *
     * @return the type namespace URI, or null if not annotated
     */
    default String getTypeNamespaceURI() {
        return null;
    }

    /**
     * Returns the type annotation local name for this node.
     *
     * <p>For schema-aware processing, elements and attributes may have
     * type annotations from xsl:type or validation.
     *
     * @return the type local name, or null if not annotated
     */
    default String getTypeLocalName() {
        return null;
    }

    /**
     * Returns true if this node has a type annotation.
     *
     * @return true if the node has a type annotation
     */
    default boolean hasTypeAnnotation() {
        return getTypeLocalName() != null;
    }

}

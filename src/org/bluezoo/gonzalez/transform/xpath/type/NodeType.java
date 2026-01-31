/*
 * NodeType.java
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

/**
 * Enumeration of XPath node types.
 *
 * <p>XPath 1.0 defines seven types of nodes:
 * <ul>
 *   <li>root nodes (document)</li>
 *   <li>element nodes</li>
 *   <li>text nodes</li>
 *   <li>attribute nodes</li>
 *   <li>namespace nodes</li>
 *   <li>processing instruction nodes</li>
 *   <li>comment nodes</li>
 * </ul>
 *
 * <p>The node type affects how the node participates in XPath operations
 * such as axis navigation, pattern matching, and string-value computation.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public enum NodeType {

    /**
     * The root node of a document.
     * There is exactly one root node for each document.
     * The root node is the parent of the document element and any
     * processing instructions or comments that occur before or after it.
     */
    ROOT("node()"),

    /**
     * An element node.
     * Element nodes have an expanded-name (namespace URI + local name),
     * may have attributes and namespace nodes, and contain child nodes.
     */
    ELEMENT("element()"),

    /**
     * A text node.
     * Text nodes contain character data from the document.
     * Adjacent text nodes are always merged, and text nodes never
     * have sibling text nodes.
     */
    TEXT("text()"),

    /**
     * An attribute node.
     * Attribute nodes have an expanded-name and a string value.
     * Each element has a set of attribute nodes, but attributes are
     * not children of elements.
     */
    ATTRIBUTE("attribute()"),

    /**
     * A namespace node.
     * Namespace nodes represent namespace bindings in scope for an element.
     * Like attributes, namespace nodes are not children of elements.
     */
    NAMESPACE("namespace()"),

    /**
     * A processing instruction node.
     * Processing instruction nodes have a target (name) and a string value
     * (the content excluding the target and whitespace).
     */
    PROCESSING_INSTRUCTION("processing-instruction()"),

    /**
     * A comment node.
     * Comment nodes contain the text of an XML comment (excluding
     * the delimiters &lt;!-- and --&gt;).
     */
    COMMENT("comment()");

    private final String nodeTest;

    NodeType(String nodeTest) {
        this.nodeTest = nodeTest;
    }

    /**
     * Returns the XPath node test syntax for this node type.
     *
     * <p>For example, ELEMENT returns "element()", TEXT returns "text()".
     *
     * @return the node test string (e.g., "element()", "text()")
     */
    public String getNodeTest() {
        return nodeTest;
    }

    /**
     * Returns true if this node type can have children.
     *
     * <p>Only root and element nodes can have children. Attribute and namespace
     * nodes are not considered children.
     *
     * @return true for ROOT and ELEMENT, false otherwise
     */
    public boolean canHaveChildren() {
        return this == ROOT || this == ELEMENT;
    }

    /**
     * Returns true if this node type has an expanded-name.
     *
     * <p>An expanded-name consists of a namespace URI and a local name.
     * Elements, attributes, namespace nodes, and processing instructions have
     * expanded-names.
     *
     * @return true for ELEMENT, ATTRIBUTE, NAMESPACE, and PROCESSING_INSTRUCTION
     */
    public boolean hasExpandedName() {
        return this == ELEMENT || this == ATTRIBUTE || 
               this == NAMESPACE || this == PROCESSING_INSTRUCTION;
    }

    /**
     * Returns true if this is a principal node type for an axis.
     *
     * <p>Elements are principal for most axes (child, descendant, etc.),
     * attributes for the attribute axis, and namespaces for the namespace axis.
     * Principal node types determine which nodes are selected by an axis.
     *
     * @return true for ELEMENT, ATTRIBUTE, and NAMESPACE
     */
    public boolean isPrincipal() {
        return this == ELEMENT || this == ATTRIBUTE || this == NAMESPACE;
    }

}

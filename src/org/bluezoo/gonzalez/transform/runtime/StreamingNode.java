/*
 * StreamingNode.java
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

import org.bluezoo.gonzalez.transform.xpath.type.NodeType;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNode;
import org.xml.sax.Attributes;

import java.util.*;

/**
 * XPathNode implementation for streaming transformation.
 *
 * <p>This node implementation supports the subset of XPath navigation
 * available during streaming. It maintains references to ancestors
 * and attributes, but children are processed on-the-fly.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public class StreamingNode implements XPathNode {

    private final NodeType nodeType;
    private final String namespaceURI;
    private final String localName;
    private final String prefix;
    private final String stringValue;
    private final StreamingNode parent;
    private final List<StreamingNode> attributes;
    private final Map<String, String> namespaceBindings;
    private final long documentOrder;
    private final String attributeType;  // DTD attribute type (ID, IDREF, CDATA, etc.)

    // For element nodes - track children as they're added
    private final List<StreamingNode> children;
    private StreamingNode followingSibling;
    private StreamingNode precedingSibling;

    /**
     * Creates a root node.
     */
    public static StreamingNode createRoot() {
        return new StreamingNode(NodeType.ROOT, null, null, null, null, null, 
            Collections.emptyMap(), 0);
    }

    /**
     * Creates an element node.
     */
    public static StreamingNode createElement(String namespaceURI, String localName, 
            String prefix, Attributes atts, Map<String, String> namespaceBindings,
            StreamingNode parent, long documentOrder) {
        
        StreamingNode element = new StreamingNode(NodeType.ELEMENT, namespaceURI, localName,
            prefix, null, parent, namespaceBindings, documentOrder);
        
        // Create attribute nodes - capture DTD attribute type for ID lookup
        if (atts != null) {
            for (int i = 0; i < atts.getLength(); i++) {
                String attrType = atts.getType(i);  // DTD type: ID, IDREF, CDATA, etc.
                StreamingNode attr = new StreamingNode(
                    NodeType.ATTRIBUTE,
                    atts.getURI(i),
                    atts.getLocalName(i),
                    extractPrefix(atts.getQName(i)),
                    atts.getValue(i),
                    element,
                    Collections.emptyMap(),
                    documentOrder + i + 1,
                    attrType
                );
                element.attributes.add(attr);
            }
        }
        
        return element;
    }

    /**
     * Creates a text node.
     */
    public static StreamingNode createText(String text, StreamingNode parent, long documentOrder) {
        return new StreamingNode(NodeType.TEXT, null, null, null, text, parent,
            Collections.emptyMap(), documentOrder);
    }

    /**
     * Creates a comment node.
     */
    public static StreamingNode createComment(String text, StreamingNode parent, long documentOrder) {
        return new StreamingNode(NodeType.COMMENT, null, null, null, text, parent,
            Collections.emptyMap(), documentOrder);
    }

    /**
     * Creates a processing instruction node.
     */
    public static StreamingNode createPI(String target, String data, StreamingNode parent, 
            long documentOrder) {
        return new StreamingNode(NodeType.PROCESSING_INSTRUCTION, null, target, null, data,
            parent, Collections.emptyMap(), documentOrder);
    }

    private StreamingNode(NodeType nodeType, String namespaceURI, String localName,
            String prefix, String stringValue, StreamingNode parent,
            Map<String, String> namespaceBindings, long documentOrder) {
        this(nodeType, namespaceURI, localName, prefix, stringValue, parent, 
             namespaceBindings, documentOrder, null);
    }
    
    private StreamingNode(NodeType nodeType, String namespaceURI, String localName,
            String prefix, String stringValue, StreamingNode parent,
            Map<String, String> namespaceBindings, long documentOrder, String attributeType) {
        this.nodeType = nodeType;
        this.namespaceURI = namespaceURI != null && !namespaceURI.isEmpty() ? namespaceURI : null;
        this.localName = localName;
        this.prefix = prefix;
        this.stringValue = stringValue;
        this.parent = parent;
        this.namespaceBindings = new HashMap<>(namespaceBindings);
        this.documentOrder = documentOrder;
        this.attributeType = attributeType;
        this.attributes = new ArrayList<>();
        this.children = new ArrayList<>();
        
        // Link to parent's children
        if (parent != null && nodeType != NodeType.ATTRIBUTE && nodeType != NodeType.NAMESPACE) {
            if (!parent.children.isEmpty()) {
                StreamingNode prevSibling = parent.children.get(parent.children.size() - 1);
                prevSibling.followingSibling = this;
                this.precedingSibling = prevSibling;
            }
            parent.children.add(this);
        }
    }

    private static String extractPrefix(String qName) {
        int colon = qName.indexOf(':');
        return colon > 0 ? qName.substring(0, colon) : null;
    }

    @Override
    public NodeType getNodeType() {
        return nodeType;
    }

    @Override
    public String getNamespaceURI() {
        return namespaceURI;
    }

    @Override
    public String getLocalName() {
        return localName;
    }

    @Override
    public String getPrefix() {
        return prefix;
    }

    @Override
    public String getStringValue() {
        if (stringValue != null) {
            return stringValue;
        }
        // For element/root, concatenate descendant text
        if (nodeType == NodeType.ELEMENT || nodeType == NodeType.ROOT) {
            StringBuilder sb = new StringBuilder();
            appendDescendantText(sb);
            return sb.toString();
        }
        return "";
    }

    private void appendDescendantText(StringBuilder sb) {
        for (StreamingNode child : children) {
            if (child.nodeType == NodeType.TEXT) {
                sb.append(child.stringValue);
            } else if (child.nodeType == NodeType.ELEMENT) {
                child.appendDescendantText(sb);
            }
        }
    }

    @Override
    public XPathNode getParent() {
        return parent;
    }

    @Override
    public Iterator<XPathNode> getChildren() {
        return new ArrayList<XPathNode>(children).iterator();
    }

    @Override
    public Iterator<XPathNode> getAttributes() {
        return new ArrayList<XPathNode>(attributes).iterator();
    }

    @Override
    public Iterator<XPathNode> getNamespaces() {
        List<XPathNode> nsNodes = new ArrayList<>();
        for (Map.Entry<String, String> entry : namespaceBindings.entrySet()) {
            nsNodes.add(new NamespaceNode(entry.getKey(), entry.getValue(), this));
        }
        return nsNodes.iterator();
    }

    @Override
    public XPathNode getFollowingSibling() {
        return followingSibling;
    }

    @Override
    public XPathNode getPrecedingSibling() {
        return precedingSibling;
    }

    @Override
    public long getDocumentOrder() {
        return documentOrder;
    }

    @Override
    public boolean isSameNode(XPathNode other) {
        if (other == this) {
            return true;
        }
        if (!(other instanceof StreamingNode)) return false;
        return documentOrder == ((StreamingNode) other).documentOrder;
    }

    @Override
    public XPathNode getRoot() {
        XPathNode node = this;
        while (node.getParent() != null) {
            node = node.getParent();
        }
        return node;
    }

    @Override
    public boolean isFullyNavigable() {
        // Streaming nodes become fully navigable once their subtree is complete
        return true;
    }

    /**
     * Returns the DTD attribute type (ID, IDREF, CDATA, etc.) for attribute nodes.
     *
     * @return the attribute type, or null if not an attribute or type unknown
     */
    public String getAttributeType() {
        return attributeType;
    }
    
    /**
     * Checks if this attribute node has DTD type ID.
     *
     * @return true if this is an ID attribute
     */
    public boolean isIdAttribute() {
        return "ID".equals(attributeType);
    }

    /**
     * Returns the namespace bindings in scope for this element.
     */
    public Map<String, String> getNamespaceBindings() {
        return Collections.unmodifiableMap(namespaceBindings);
    }

    /**
     * Looks up a namespace URI by prefix.
     */
    public String lookupNamespaceURI(String prefix) {
        if (prefix == null) prefix = "";
        String uri = namespaceBindings.get(prefix);
        if (uri != null) {
            return uri;
        }
        if (parent != null) {
            return parent.lookupNamespaceURI(prefix);
        }
        return null;
    }

    @Override
    public String toString() {
        switch (nodeType) {
            case ROOT: return "/";
            case ELEMENT: return "<" + (prefix != null ? prefix + ":" : "") + localName + ">";
            case TEXT: return "text('" + stringValue.substring(0, Math.min(20, stringValue.length())) + "')";
            case ATTRIBUTE: return "@" + localName + "='" + stringValue + "'";
            case COMMENT: return "comment()";
            case PROCESSING_INSTRUCTION: return "pi(" + localName + ")";
            default: return nodeType.toString();
        }
    }

    /**
     * Returns the parent as a StreamingNode.
     *
     * @return the parent node
     */
    public StreamingNode getParentNode() {
        return parent;
    }

    /**
     * Adds a child node to this element.
     *
     * @param child the child to add
     */
    public void addChild(StreamingNode child) {
        if (!children.isEmpty()) {
            StreamingNode prevSibling = children.get(children.size() - 1);
            prevSibling.followingSibling = child;
            // Note: child.precedingSibling is set in constructor
        }
        children.add(child);
    }

    /**
     * Adds a namespace mapping to this element.
     *
     * @param prefix the namespace prefix
     * @param uri the namespace URI
     */
    public void addNamespaceMapping(String prefix, String uri) {
        namespaceBindings.put(prefix != null ? prefix : "", uri);
    }

    /**
     * Appends text to this element's text content.
     * Creates or updates a text child node.
     *
     * @param text the text to append
     */
    public void appendText(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        // Check if last child is a text node that we can append to
        if (!children.isEmpty()) {
            StreamingNode lastChild = children.get(children.size() - 1);
            if (lastChild.nodeType == NodeType.TEXT) {
                // Create a new text node with combined content
                // (stringValue is final, so we need to create new node)
                StreamingNode combined = createText(
                    lastChild.stringValue + text, 
                    this, 
                    lastChild.documentOrder
                );
                children.set(children.size() - 1, combined);
                return;
            }
        }
        // Add new text node
        createText(text, this, documentOrder);
    }

    /**
     * Namespace node implementation.
     */
    private static class NamespaceNode implements XPathNode {
        private final String prefix;
        private final String uri;
        private final StreamingNode parent;

        NamespaceNode(String prefix, String uri, StreamingNode parent) {
            this.prefix = prefix;
            this.uri = uri;
            this.parent = parent;
        }

        @Override public NodeType getNodeType() { return NodeType.NAMESPACE; }
        @Override public String getNamespaceURI() { return null; }
        @Override public String getLocalName() { return prefix; }
        @Override public String getPrefix() { return null; }
        @Override public String getStringValue() { return uri; }
        @Override public XPathNode getParent() { return parent; }
        @Override public Iterator<XPathNode> getChildren() { return Collections.emptyIterator(); }
        @Override public Iterator<XPathNode> getAttributes() { return Collections.emptyIterator(); }
        @Override public Iterator<XPathNode> getNamespaces() { return Collections.emptyIterator(); }
        @Override public XPathNode getFollowingSibling() { return null; }
        @Override public XPathNode getPrecedingSibling() { return null; }
        @Override public long getDocumentOrder() { return parent.getDocumentOrder(); }
        @Override public boolean isSameNode(XPathNode other) { 
            return other instanceof NamespaceNode && 
                   prefix.equals(((NamespaceNode)other).prefix) &&
                   parent.isSameNode(((NamespaceNode)other).parent);
        }
        @Override public XPathNode getRoot() { return parent.getRoot(); }
        @Override public boolean isFullyNavigable() { return true; }
    }

}

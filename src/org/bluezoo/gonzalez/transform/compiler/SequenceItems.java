/*
 * SequenceItems.java
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

import java.util.Collections;
import java.util.Iterator;

import org.bluezoo.gonzalez.transform.xpath.type.NodeType;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNode;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNodeSet;
import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;

class SequenceAttributeItem implements XPathValue, XPathNode {
    private final String namespaceURI;
    private final String localName;
    private final String qName;
    private final String value;
    private final String typeNamespaceURI;
    private final String typeLocalName;
    
    SequenceAttributeItem(String namespaceURI, String localName, String qName, String value) {
        this(namespaceURI, localName, qName, value, null, null);
    }
    
    SequenceAttributeItem(String namespaceURI, String localName, String qName, String value,
                         String typeNamespaceURI, String typeLocalName) {
        this.namespaceURI = namespaceURI;
        this.localName = localName;
        this.qName = qName;
        this.value = value;
        this.typeNamespaceURI = typeNamespaceURI;
        this.typeLocalName = typeLocalName;
    }
    
    @Override public Type getType() { return Type.NODESET; } // It's a node
    @Override public String asString() { return value != null ? value : ""; }
    @Override public double asNumber() { 
        try { return Double.parseDouble(value); } 
        catch (NumberFormatException e) { return Double.NaN; } 
    }
    @Override public boolean asBoolean() { return value != null && !value.isEmpty(); }
    @Override public XPathNodeSet asNodeSet() { 
        return new XPathNodeSet(Collections.singletonList(this)); 
    }
    
    // XPathNode implementation
    @Override public NodeType getNodeType() { return NodeType.ATTRIBUTE; }
    @Override public String getNamespaceURI() { return namespaceURI; }
    @Override public String getLocalName() { return localName; }
    @Override public String getPrefix() { 
        int colon = qName != null ? qName.indexOf(':') : -1;
        return colon > 0 ? qName.substring(0, colon) : null;
    }
    @Override public String getStringValue() { return value != null ? value : ""; }
    @Override public XPathNode getParent() { return null; }
    @Override public Iterator<XPathNode> getChildren() { return Collections.emptyIterator(); }
    @Override public Iterator<XPathNode> getAttributes() { return Collections.emptyIterator(); }
    @Override public Iterator<XPathNode> getNamespaces() { return Collections.emptyIterator(); }
    @Override public XPathNode getFollowingSibling() { return null; }
    @Override public XPathNode getPrecedingSibling() { return null; }
    @Override public long getDocumentOrder() { return 0; }
    @Override public boolean isSameNode(XPathNode other) { return this == other; }
    @Override public XPathNode getRoot() { return this; }
    @Override public boolean isFullyNavigable() { return false; }
    
    // Type annotation methods
    @Override public String getTypeNamespaceURI() { return typeNamespaceURI; }
    @Override public String getTypeLocalName() { return typeLocalName; }
    
    public String getQName() { return qName; }
    public String getValue() { return value; }
    
    @Override public String toString() { 
        if (typeLocalName != null) {
            return "attribute(" + qName + "=" + value + " [" + typeLocalName + "])";
        }
        return "attribute(" + qName + "=" + value + ")"; 
    }
}

class SequenceTextItem implements XPathValue, XPathNode {
    private final String text;
    
    SequenceTextItem(String text) {
        this.text = text;
    }
    
    @Override public Type getType() { return Type.NODESET; }
    @Override public String asString() { return text != null ? text : ""; }
    @Override public double asNumber() { 
        try { return Double.parseDouble(text); } 
        catch (NumberFormatException e) { return Double.NaN; } 
    }
    @Override public boolean asBoolean() { return text != null && !text.isEmpty(); }
    @Override public XPathNodeSet asNodeSet() { 
        return new XPathNodeSet(Collections.singletonList(this)); 
    }
    
    // XPathNode implementation
    @Override public NodeType getNodeType() { return NodeType.TEXT; }
    @Override public String getNamespaceURI() { return null; }
    @Override public String getLocalName() { return null; }
    @Override public String getPrefix() { return null; }
    @Override public String getStringValue() { return text != null ? text : ""; }
    @Override public XPathNode getParent() { return null; }
    @Override public Iterator<XPathNode> getChildren() { return Collections.emptyIterator(); }
    @Override public Iterator<XPathNode> getAttributes() { return Collections.emptyIterator(); }
    @Override public Iterator<XPathNode> getNamespaces() { return Collections.emptyIterator(); }
    @Override public XPathNode getFollowingSibling() { return null; }
    @Override public XPathNode getPrecedingSibling() { return null; }
    @Override public long getDocumentOrder() { return 0; }
    @Override public boolean isSameNode(XPathNode other) { return this == other; }
    @Override public XPathNode getRoot() { return this; }
    @Override public boolean isFullyNavigable() { return false; }
    @Override public String getTypeNamespaceURI() { return null; }
    @Override public String getTypeLocalName() { return null; }
    
    public String getText() { return text; }
    
    @Override public String toString() { return "text(" + text + ")"; }
}

class SequenceCommentItem implements XPathValue, XPathNode {
    private final String text;
    
    SequenceCommentItem(String text) {
        this.text = text;
    }
    
    @Override public Type getType() { return Type.NODESET; }
    @Override public String asString() { return text != null ? text : ""; }
    @Override public double asNumber() { 
        try { return Double.parseDouble(text); } 
        catch (NumberFormatException e) { return Double.NaN; } 
    }
    @Override public boolean asBoolean() { return text != null && !text.isEmpty(); }
    @Override public XPathNodeSet asNodeSet() { 
        return new XPathNodeSet(Collections.singletonList(this)); 
    }
    
    // XPathNode implementation
    @Override public NodeType getNodeType() { return NodeType.COMMENT; }
    @Override public String getNamespaceURI() { return null; }
    @Override public String getLocalName() { return null; }
    @Override public String getPrefix() { return null; }
    @Override public String getStringValue() { return text != null ? text : ""; }
    @Override public XPathNode getParent() { return null; }
    @Override public Iterator<XPathNode> getChildren() { return Collections.emptyIterator(); }
    @Override public Iterator<XPathNode> getAttributes() { return Collections.emptyIterator(); }
    @Override public Iterator<XPathNode> getNamespaces() { return Collections.emptyIterator(); }
    @Override public XPathNode getFollowingSibling() { return null; }
    @Override public XPathNode getPrecedingSibling() { return null; }
    @Override public long getDocumentOrder() { return 0; }
    @Override public boolean isSameNode(XPathNode other) { return this == other; }
    @Override public XPathNode getRoot() { return this; }
    @Override public boolean isFullyNavigable() { return false; }
    @Override public String getTypeNamespaceURI() { return null; }
    @Override public String getTypeLocalName() { return null; }
    
    public String getText() { return text; }
    
    @Override public String toString() { return "comment(" + text + ")"; }
}

class SequencePIItem implements XPathValue, XPathNode {
    private final String target;
    private final String data;
    
    SequencePIItem(String target, String data) {
        this.target = target;
        this.data = data;
    }
    
    @Override public Type getType() { return Type.NODESET; }
    @Override public String asString() { return data != null ? data : ""; }
    @Override public double asNumber() { 
        try { return Double.parseDouble(data); } 
        catch (NumberFormatException e) { return Double.NaN; } 
    }
    @Override public boolean asBoolean() { return data != null && !data.isEmpty(); }
    @Override public XPathNodeSet asNodeSet() { 
        return new XPathNodeSet(Collections.singletonList(this)); 
    }
    
    // XPathNode implementation
    @Override public NodeType getNodeType() { return NodeType.PROCESSING_INSTRUCTION; }
    @Override public String getNamespaceURI() { return null; }
    @Override public String getLocalName() { return target; }
    @Override public String getPrefix() { return null; }
    @Override public String getStringValue() { return data != null ? data : ""; }
    @Override public XPathNode getParent() { return null; }
    @Override public Iterator<XPathNode> getChildren() { return Collections.emptyIterator(); }
    @Override public Iterator<XPathNode> getAttributes() { return Collections.emptyIterator(); }
    @Override public Iterator<XPathNode> getNamespaces() { return Collections.emptyIterator(); }
    @Override public XPathNode getFollowingSibling() { return null; }
    @Override public XPathNode getPrecedingSibling() { return null; }
    @Override public long getDocumentOrder() { return 0; }
    @Override public boolean isSameNode(XPathNode other) { return this == other; }
    @Override public XPathNode getRoot() { return this; }
    @Override public boolean isFullyNavigable() { return false; }
    @Override public String getTypeNamespaceURI() { return null; }
    @Override public String getTypeLocalName() { return null; }
    
    public String getTarget() { return target; }
    public String getData() { return data; }
    
    @Override public String toString() { return "processing-instruction(" + target + ", " + data + ")"; }
}

class SequenceNamespaceItem implements XPathValue, XPathNode {
    private final String prefix;  // The namespace prefix (local name of namespace node)
    private final String uri;     // The namespace URI (string value)
    
    SequenceNamespaceItem(String prefix, String uri) {
        this.prefix = prefix != null ? prefix : "";
        this.uri = uri != null ? uri : "";
    }
    
    @Override public Type getType() { return Type.NODESET; } // It's a node
    @Override public String asString() { return uri; }
    @Override public double asNumber() { 
        try { return Double.parseDouble(uri); } 
        catch (NumberFormatException e) { return Double.NaN; } 
    }
    @Override public boolean asBoolean() { return !uri.isEmpty(); }
    @Override public XPathNodeSet asNodeSet() { 
        return new XPathNodeSet(Collections.singletonList(this)); 
    }
    
    // XPathNode implementation
    @Override public NodeType getNodeType() { return NodeType.NAMESPACE; }
    @Override public String getNamespaceURI() { return null; } // Namespace nodes have no namespace
    @Override public String getLocalName() { return prefix; }  // Local name is the prefix
    @Override public String getPrefix() { return null; }       // Namespace nodes have no prefix
    @Override public String getStringValue() { return uri; }   // String value is the URI
    @Override public XPathNode getParent() { return null; }
    @Override public Iterator<XPathNode> getChildren() { return Collections.emptyIterator(); }
    @Override public Iterator<XPathNode> getAttributes() { return Collections.emptyIterator(); }
    @Override public Iterator<XPathNode> getNamespaces() { return Collections.emptyIterator(); }
    @Override public XPathNode getFollowingSibling() { return null; }
    @Override public XPathNode getPrecedingSibling() { return null; }
    @Override public long getDocumentOrder() { return 0; }
    @Override public boolean isSameNode(XPathNode other) { return this == other; }
    @Override public XPathNode getRoot() { return this; }
    @Override public boolean isFullyNavigable() { return false; }
    @Override public String getTypeNamespaceURI() { return null; }
    @Override public String getTypeLocalName() { return null; }
    
    public String getNsPrefix() { return prefix; }
    public String getUri() { return uri; }
    
    @Override public String toString() { 
        return "namespace(" + (prefix.isEmpty() ? "#default" : prefix) + "=" + uri + ")"; 
    }
}


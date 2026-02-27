/*
 * StringContextNode.java
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

package org.bluezoo.gonzalez.transform.ast;

import java.util.Collections;
import java.util.Iterator;

import org.bluezoo.gonzalez.transform.xpath.type.NodeType;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNode;

/**
 * StringContextNode XPath node.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public class StringContextNode implements XPathNode {
    private final String value;
    
    public StringContextNode(String value) {
        this.value = value;
    }
    
    @Override public NodeType getNodeType() { return NodeType.TEXT; }
    @Override public String getLocalName() { return null; }
    @Override public String getNamespaceURI() { return null; }
    @Override public String getPrefix() { return null; }
    @Override public String getStringValue() { return value; }
    @Override public XPathNode getParent() { return null; }
    @Override public Iterator<XPathNode> getChildren() { 
        return Collections.emptyIterator(); 
    }
    @Override public Iterator<XPathNode> getAttributes() { 
        return Collections.emptyIterator(); 
    }
    @Override public Iterator<XPathNode> getNamespaces() { 
        return Collections.emptyIterator(); 
    }
    @Override public XPathNode getFollowingSibling() { return null; }
    @Override public XPathNode getPrecedingSibling() { return null; }
    @Override public long getDocumentOrder() { return 0; }
    @Override public boolean isSameNode(XPathNode other) { return this == other; }
    @Override public XPathNode getRoot() { return this; }
    @Override public boolean isFullyNavigable() { return false; }
}

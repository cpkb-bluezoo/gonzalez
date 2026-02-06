/*
 * NodeSetFunctions.java
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

package org.bluezoo.gonzalez.transform.xpath.function;

import org.bluezoo.gonzalez.transform.xpath.XPathContext;
import org.bluezoo.gonzalez.transform.xpath.expr.XPathException;
import org.bluezoo.gonzalez.transform.xpath.type.*;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * XPath 1.0 node-set functions.
 *
 * <ul>
 *   <li>last() - returns the context size</li>
 *   <li>position() - returns the context position</li>
 *   <li>count(node-set) - returns the number of nodes</li>
 *   <li>id(object) - selects elements by ID</li>
 *   <li>local-name(node-set?) - returns the local name</li>
 *   <li>namespace-uri(node-set?) - returns the namespace URI</li>
 *   <li>name(node-set?) - returns the qualified name</li>
 * </ul>
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class NodeSetFunctions {

    private NodeSetFunctions() {}

    /**
     * XPath 1.0 last() function.
     * 
     * <p>Returns the context size (number of nodes in the current node-set).
     * 
     * <p>Signature: last() → number
     * 
     * @see <a href="https://www.w3.org/TR/xpath/#function-last">XPath 1.0 last()</a>
     */
    public static final Function LAST = new Function() {
        @Override public String getName() { return "last"; }
        @Override public int getMinArgs() { return 0; }
        @Override public int getMaxArgs() { return 0; }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) {
            return XPathNumber.of(context.getContextSize());
        }
    };

    /**
     * XPath 1.0 position() function.
     * 
     * <p>Returns the context position (1-based index of the current node in the node-set).
     * 
     * <p>Signature: position() → number
     * 
     * @see <a href="https://www.w3.org/TR/xpath/#function-position">XPath 1.0 position()</a>
     */
    public static final Function POSITION = new Function() {
        @Override public String getName() { return "position"; }
        @Override public int getMinArgs() { return 0; }
        @Override public int getMaxArgs() { return 0; }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) {
            return XPathNumber.of(context.getContextPosition());
        }
    };

    /**
     * XPath 1.0 count() function.
     * 
     * <p>Returns the number of nodes in the node-set.
     * 
     * <p>Signature: count(node-set) → number
     * 
     * @see <a href="https://www.w3.org/TR/xpath/#function-count">XPath 1.0 count()</a>
     */
    public static final Function COUNT = new Function() {
        @Override public String getName() { return "count"; }
        @Override public int getMinArgs() { return 1; }
        @Override public int getMaxArgs() { return 1; }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            XPathValue arg = args.get(0);
            if (!arg.isNodeSet()) {
                throw new XPathException("count() requires a node-set argument");
            }
            return XPathNumber.of(arg.asNodeSet().size());
        }
    };

    /**
     * XPath 1.0 id() function (XSLT 2.0+ allows second argument).
     * 
     * <p>Returns a node-set containing elements with IDs matching the string values
     * in the first argument. IDs are found via DTD-declared ID attributes, xml:id,
     * or plain "id" attributes. The second argument (XSLT 2.0+) specifies the document
     * to search; if omitted, uses the context document.
     * 
     * <p>Signature: id(object, node?) → node-set
     * 
     * @see <a href="https://www.w3.org/TR/xpath/#function-id">XPath 1.0 id()</a>
     */
    public static final Function ID = new Function() {
        @Override public String getName() { return "id"; }
        @Override public int getMinArgs() { return 1; }
        @Override public int getMaxArgs() { return 2; }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            // Collect all IDs to search for
            List<String> idValues = new ArrayList<>();
            XPathValue arg = args.get(0);
            
            if (arg.isNodeSet()) {
                // For node-set, get string value of each node and split on whitespace
                for (XPathNode node : ((XPathNodeSet) arg).getNodes()) {
                    splitIds(node.getStringValue(), idValues);
                }
            } else {
                // For other types, convert to string and split on whitespace
                splitIds(arg.asString(), idValues);
            }
            
            if (idValues.isEmpty()) {
                return XPathNodeSet.EMPTY;
            }
            
            // Get document root - use second argument if provided (XSLT 2.0+)
            XPathNode root = null;
            if (args.size() > 1 && args.get(1) != null) {
                XPathValue docArg = args.get(1);
                if (docArg instanceof XPathResultTreeFragment) {
                    // RTF - convert to node set and get root
                    XPathNodeSet rtfNodes = ((XPathResultTreeFragment) docArg).asNodeSet();
                    if (rtfNodes != null && !rtfNodes.isEmpty()) {
                        root = rtfNodes.first().getRoot();
                    }
                } else if (docArg.isNodeSet()) {
                    XPathNodeSet docNodes = (XPathNodeSet) docArg;
                    if (!docNodes.isEmpty()) {
                        root = docNodes.first().getRoot();
                    }
                }
            }
            
            if (root == null) {
                // Default to context document
                XPathNode contextNode = context.getContextNode();
                if (contextNode == null) {
                    return XPathNodeSet.EMPTY;
                }
                root = contextNode.getRoot();
            }
            
            if (root == null) {
                return XPathNodeSet.EMPTY;
            }
            
            // Search for elements with matching IDs
            List<XPathNode> result = new ArrayList<>();
            collectElementsById(root, idValues, result, context);
            
            return new XPathNodeSet(result);
        }
        
        private void splitIds(String s, List<String> ids) {
            if (s == null || s.isEmpty()) return;
            int start = -1;
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                boolean isWhitespace = (c == ' ' || c == '\t' || c == '\n' || c == '\r');
                if (isWhitespace) {
                    if (start >= 0) {
                        ids.add(s.substring(start, i));
                        start = -1;
                    }
                } else {
                    if (start < 0) {
                        start = i;
                    }
                }
            }
            if (start >= 0) {
                ids.add(s.substring(start));
            }
        }
        
        private void collectElementsById(XPathNode node, List<String> idValues, 
                List<XPathNode> result, XPathContext context) {
            if (node.isElement()) {
                // Check for DTD-declared ID attributes and common ID attributes
                String idAttr = getIdAttribute(node, context);
                if (idAttr != null) {
                    for (String searchId : idValues) {
                        if (searchId.equals(idAttr)) {
                            // Avoid duplicates
                            addIfNotPresent(result, node);
                            break;
                        }
                    }
                }
                
                // Also check if this element itself is ID-typed (XSLT 2.0+)
                // ID-typed elements have their text content as the ID value
                if (node.hasTypeAnnotation()) {
                    String typeLocal = node.getTypeLocalName();
                    String typeNs = node.getTypeNamespaceURI();
                    if (context != null && context.isIdDerivedType(typeNs, typeLocal)) {
                        String elementId = node.getStringValue().trim();
                        for (String searchId : idValues) {
                            if (searchId.equals(elementId)) {
                                addIfNotPresent(result, node);
                                break;
                            }
                        }
                    }
                }
            }
            
            // Recurse into children
            Iterator<XPathNode> children = node.getChildren();
            while (children.hasNext()) {
                collectElementsById(children.next(), idValues, result, context);
            }
        }
        
        private void addIfNotPresent(List<XPathNode> result, XPathNode node) {
            for (XPathNode existing : result) {
                if (existing.isSameNode(node)) {
                    return;
                }
            }
            result.add(node);
        }
        
        private String getIdAttribute(XPathNode element, XPathContext context) {
            // First priority: Check for DTD-declared ID attributes
            Iterator<XPathNode> attrs = element.getAttributes();
            while (attrs.hasNext()) {
                XPathNode attr = attrs.next();
                // Check if this attribute has DTD type ID (StreamingNode specific)
                if (attr instanceof org.bluezoo.gonzalez.transform.runtime.StreamingNode) {
                    org.bluezoo.gonzalez.transform.runtime.StreamingNode sn = 
                        (org.bluezoo.gonzalez.transform.runtime.StreamingNode) attr;
                    if (sn.isIdAttribute()) {
                        return attr.getStringValue();
                    }
                }
            }
            
            // Second priority: Schema-typed ID attributes (XSLT 2.0+)
            // Check for attributes whose type annotation is xs:ID or derives from xs:ID
            attrs = element.getAttributes();
            while (attrs.hasNext()) {
                XPathNode attr = attrs.next();
                if (attr.hasTypeAnnotation()) {
                    String typeLocal = attr.getTypeLocalName();
                    String typeNs = attr.getTypeNamespaceURI();
                    // Use context to check ID derivation (handles both built-in and user-defined types)
                    if (context != null && context.isIdDerivedType(typeNs, typeLocal)) {
                        return attr.getStringValue();
                    }
                }
            }
            
            // Third priority: xml:id (XML standard)
            attrs = element.getAttributes();
            while (attrs.hasNext()) {
                XPathNode attr = attrs.next();
                String name = attr.getLocalName();
                String uri = attr.getNamespaceURI();
                
                if ("id".equals(name) && "http://www.w3.org/XML/1998/namespace".equals(uri)) {
                    return attr.getStringValue();
                }
            }
            
            // Fourth priority: Check for plain "id" or "ID" attribute (fallback)
            attrs = element.getAttributes();
            while (attrs.hasNext()) {
                XPathNode attr = attrs.next();
                String name = attr.getLocalName();
                String uri = attr.getNamespaceURI();
                
                if ("id".equals(name) && (uri == null || uri.isEmpty())) {
                    return attr.getStringValue();
                }
            }
            
            return null;
        }
    };

    /**
     * XPath 1.0 local-name() function.
     * 
     * <p>Returns the local name (without namespace prefix) of the first node in the
     * node-set, or the context node if no argument is provided.
     * 
     * <p>Signature: local-name(node-set?) → string
     * 
     * @see <a href="https://www.w3.org/TR/xpath/#function-local-name">XPath 1.0 local-name()</a>
     */
    public static final Function LOCAL_NAME = new Function() {
        @Override public String getName() { return "local-name"; }
        @Override public int getMinArgs() { return 0; }
        @Override public int getMaxArgs() { return 1; }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            XPathNode node;
            if (args.isEmpty()) {
                node = context.getContextNode();
            } else {
                XPathValue arg = args.get(0);
                if (!arg.isNodeSet()) {
                    throw new XPathException("local-name() requires a node-set argument");
                }
                XPathNodeSet nodeSet = arg.asNodeSet();
                if (nodeSet.isEmpty()) {
                    return XPathString.EMPTY;
                }
                node = nodeSet.first();
            }
            
            String localName = node.getLocalName();
            return XPathString.of(localName != null ? localName : "");
        }
    };

    /**
     * XPath 1.0 namespace-uri() function.
     * 
     * <p>Returns the namespace URI of the first node in the node-set, or the context
     * node if no argument is provided. Returns empty string if the node has no namespace.
     * 
     * <p>Signature: namespace-uri(node-set?) → string
     * 
     * @see <a href="https://www.w3.org/TR/xpath/#function-namespace-uri">XPath 1.0 namespace-uri()</a>
     */
    public static final Function NAMESPACE_URI = new Function() {
        @Override public String getName() { return "namespace-uri"; }
        @Override public int getMinArgs() { return 0; }
        @Override public int getMaxArgs() { return 1; }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            XPathNode node;
            if (args.isEmpty()) {
                node = context.getContextNode();
            } else {
                XPathValue arg = args.get(0);
                if (!arg.isNodeSet()) {
                    throw new XPathException("namespace-uri() requires a node-set argument");
                }
                XPathNodeSet nodeSet = arg.asNodeSet();
                if (nodeSet.isEmpty()) {
                    return XPathString.EMPTY;
                }
                node = nodeSet.first();
            }
            
            String nsUri = node.getNamespaceURI();
            return XPathString.of(nsUri != null ? nsUri : "");
        }
    };

    /**
     * XPath 1.0 name() function.
     * 
     * <p>Returns the qualified name (prefix:localName) of the first node in the
     * node-set, or the context node if no argument is provided.
     * 
     * <p>Signature: name(node-set?) → string
     * 
     * @see <a href="https://www.w3.org/TR/xpath/#function-name">XPath 1.0 name()</a>
     */
    public static final Function NAME = new Function() {
        @Override public String getName() { return "name"; }
        @Override public int getMinArgs() { return 0; }
        @Override public int getMaxArgs() { return 1; }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            XPathNode node;
            if (args.isEmpty()) {
                node = context.getContextNode();
            } else {
                XPathValue arg = args.get(0);
                if (!arg.isNodeSet()) {
                    throw new XPathException("name() requires a node-set argument");
                }
                XPathNodeSet nodeSet = arg.asNodeSet();
                if (nodeSet.isEmpty()) {
                    return XPathString.EMPTY;
                }
                node = nodeSet.first();
            }
            
            // Build qualified name from prefix and local name
            String prefix = node.getPrefix();
            String localName = node.getLocalName();
            
            if (localName == null) {
                return XPathString.EMPTY;
            }
            
            if (prefix != null && !prefix.isEmpty()) {
                return XPathString.of(prefix + ":" + localName);
            }
            return XPathString.of(localName);
        }
    };

    /**
     * XPath 1.0 idref() function.
     * 
     * <p>Returns elements that have IDREF attributes matching the given ID values.
     * 
     * <p>Signature: idref(object) → node-set
     * <p>Signature: idref(object, node) → node-set
     * 
     * <p>Note: This function requires DTD validation to identify IDREF attributes.
     * Without DTD information, it searches for common IDREF attribute names.
     * 
     * @see <a href="https://www.w3.org/TR/xpath-functions/#func-idref">XPath idref()</a>
     */
    public static final Function IDREF = new Function() {
        @Override public String getName() { return "idref"; }
        @Override public int getMinArgs() { return 1; }
        @Override public int getMaxArgs() { return 2; }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            // Get the ID values to search for
            java.util.Set<String> idValues = new java.util.HashSet<>();
            XPathValue arg = args.get(0);
            if (arg instanceof XPathNodeSet) {
                for (XPathNode n : (XPathNodeSet) arg) {
                    addIdValues(idValues, n.getStringValue());
                }
            } else if (arg instanceof XPathSequence) {
                for (XPathValue v : (XPathSequence) arg) {
                    addIdValues(idValues, v.asString());
                }
            } else {
                addIdValues(idValues, arg.asString());
            }
            
            if (idValues.isEmpty()) {
                return XPathNodeSet.EMPTY;
            }
            
            // Get the document to search
            XPathNode docNode;
            if (args.size() > 1) {
                XPathValue nodeArg = args.get(1);
                if (nodeArg instanceof XPathNode) {
                    docNode = ((XPathNode) nodeArg).getRoot();
                } else if (nodeArg instanceof XPathNodeSet) {
                    Iterator<XPathNode> iter = ((XPathNodeSet) nodeArg).iterator();
                    if (iter.hasNext()) {
                        docNode = iter.next().getRoot();
                    } else {
                        return XPathNodeSet.EMPTY;
                    }
                } else {
                    return XPathNodeSet.EMPTY;
                }
            } else {
                docNode = context.getContextNode().getRoot();
            }
            
            // Search for elements with IDREF/IDREFS attributes matching the IDs
            List<XPathNode> result = new ArrayList<>();
            findIdrefElements(docNode, idValues, result);
            
            return new XPathNodeSet(result);
        }
        
        private void addIdValues(java.util.Set<String> idValues, String value) {
            if (value != null) {
                // Split on whitespace for IDREFS
                for (String id : value.trim().split("\\s+")) {
                    if (!id.isEmpty()) {
                        idValues.add(id);
                    }
                }
            }
        }
        
        private void findIdrefElements(XPathNode node, java.util.Set<String> idValues, List<XPathNode> result) {
            if (node == null) {
                return;
            }
            
            NodeType type = node.getNodeType();
            if (type == NodeType.ELEMENT || type == NodeType.ROOT) {
                // Check attributes for IDREF values
                if (type == NodeType.ELEMENT) {
                    Iterator<XPathNode> attrs = node.getAttributes();
                    if (attrs != null) {
                        while (attrs.hasNext()) {
                            XPathNode attr = attrs.next();
                            String attrValue = attr.getStringValue();
                            // Check if this attribute value contains any of our ID values
                            // (for IDREFS, split on whitespace)
                            if (attrValue != null) {
                                for (String part : attrValue.trim().split("\\s+")) {
                                    if (idValues.contains(part)) {
                                        // Found a match - add this element (not the attribute)
                                        if (!result.contains(node)) {
                                            result.add(node);
                                        }
                                        break;
                                    }
                                }
                            }
                        }
                    }
                }
                
                // Recurse into children
                Iterator<XPathNode> children = node.getChildren();
                if (children != null) {
                    while (children.hasNext()) {
                        findIdrefElements(children.next(), idValues, result);
                    }
                }
            }
        }
    };

    /**
     * Returns all node-set functions (XPath 1.0 and 2.0).
     *
     * @return array of all node-set function implementations
     */
    public static Function[] getAll() {
        return new Function[] { LAST, POSITION, COUNT, ID, IDREF, LOCAL_NAME, NAMESPACE_URI, NAME };
    }

}

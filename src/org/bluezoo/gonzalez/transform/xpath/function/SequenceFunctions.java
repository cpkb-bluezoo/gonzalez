/*
 * SequenceFunctions.java
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

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * XPath 2.0/3.0 sequence functions.
 *
 * <p>Implements:
 * <ul>
 *   <li>empty, exists - sequence emptiness tests</li>
 *   <li>subsequence - extract a subsequence</li>
 *   <li>distinct-values - unique values</li>
 *   <li>reverse - reverse sequence order</li>
 *   <li>insert-before, remove - sequence manipulation</li>
 *   <li>index-of - find position of value</li>
 *   <li>head, tail - first/rest of sequence</li>
 * </ul>
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class SequenceFunctions {

    private SequenceFunctions() {}

    /**
     * Returns all sequence functions.
     */
    public static List<Function> getAll() {
        List<Function> functions = new ArrayList<>();
        functions.add(EMPTY);
        functions.add(EXISTS);
        functions.add(SUBSEQUENCE);
        functions.add(DISTINCT_VALUES);
        functions.add(REVERSE);
        functions.add(INSERT_BEFORE);
        functions.add(REMOVE);
        functions.add(INDEX_OF);
        functions.add(HEAD);
        functions.add(TAIL);
        functions.add(COUNT);
        functions.add(UNORDERED);
        functions.add(ZERO_OR_ONE);
        functions.add(ONE_OR_MORE);
        functions.add(EXACTLY_ONE);
        functions.add(RESOLVE_URI);
        functions.add(BASE_URI);
        functions.add(STATIC_BASE_URI);
        functions.add(DOCUMENT_URI);
        functions.add(DEEP_EQUAL);
        // QName functions
        functions.add(QNAME);
        functions.add(LOCAL_NAME_FROM_QNAME);
        functions.add(NAMESPACE_URI_FROM_QNAME);
        functions.add(PREFIX_FROM_QNAME);
        functions.add(IN_SCOPE_PREFIXES);
        functions.add(NAMESPACE_URI_FOR_PREFIX);
        functions.add(NODE_NAME);
        functions.add(RESOLVE_QNAME);
        return functions;
    }

    /** empty(sequence) - returns true if sequence is empty */
    public static final Function EMPTY = new Function() {
        @Override public String getName() { return "empty"; }
        @Override public int getMinArgs() { return 1; }
        @Override public int getMaxArgs() { return 1; }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            XPathValue arg = args.get(0);
            return XPathBoolean.of(isEmpty(arg));
        }
    };

    /** exists(sequence) - returns true if sequence is not empty */
    public static final Function EXISTS = new Function() {
        @Override public String getName() { return "exists"; }
        @Override public int getMinArgs() { return 1; }
        @Override public int getMaxArgs() { return 1; }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            XPathValue arg = args.get(0);
            return XPathBoolean.of(!isEmpty(arg));
        }
    };

    /** subsequence(sequence, startingLoc, length?) - extract subsequence */
    public static final Function SUBSEQUENCE = new Function() {
        @Override public String getName() { return "subsequence"; }
        @Override public int getMinArgs() { return 2; }
        @Override public int getMaxArgs() { return 3; }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            XPathValue seq = args.get(0);
            double startLoc = args.get(1).asNumber();
            
            // Handle NaN or negative infinity
            if (Double.isNaN(startLoc)) {
                return XPathSequence.EMPTY;
            }
            
            // Round starting location
            int start = (int) Math.round(startLoc);
            
            int length = Integer.MAX_VALUE;
            if (args.size() > 2) {
                double lenArg = args.get(2).asNumber();
                if (Double.isNaN(lenArg)) {
                    return XPathSequence.EMPTY;
                }
                length = (int) Math.round(lenArg);
            }
            
            // Convert to 0-based index (XPath uses 1-based)
            int startIndex = start - 1;
            
            List<XPathValue> items = toList(seq);
            List<XPathValue> result = new ArrayList<>();
            
            for (int i = 0; i < items.size(); i++) {
                if (i >= startIndex && result.size() < length) {
                    result.add(items.get(i));
                }
            }
            
            if (result.isEmpty()) {
                return XPathSequence.EMPTY;
            } else if (result.size() == 1) {
                return result.get(0);
            } else {
                return new XPathSequence(result);
            }
        }
    };

    /** distinct-values(sequence, collation?) - unique values */
    public static final Function DISTINCT_VALUES = new Function() {
        @Override public String getName() { return "distinct-values"; }
        @Override public int getMinArgs() { return 1; }
        @Override public int getMaxArgs() { return 2; }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            XPathValue seq = args.get(0);
            List<XPathValue> items = toList(seq);
            
            Set<String> seen = new HashSet<>();
            List<XPathValue> result = new ArrayList<>();
            
            for (XPathValue item : items) {
                String key = item.asString();
                if (!seen.contains(key)) {
                    seen.add(key);
                    result.add(item);
                }
            }
            
            if (result.isEmpty()) {
                return XPathSequence.EMPTY;
            } else if (result.size() == 1) {
                return result.get(0);
            } else {
                return new XPathSequence(result);
            }
        }
    };

    /** reverse(sequence) - reverse order */
    public static final Function REVERSE = new Function() {
        @Override public String getName() { return "reverse"; }
        @Override public int getMinArgs() { return 1; }
        @Override public int getMaxArgs() { return 1; }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            XPathValue seq = args.get(0);
            List<XPathValue> items = toList(seq);
            
            List<XPathValue> result = new ArrayList<>();
            for (int i = items.size() - 1; i >= 0; i--) {
                result.add(items.get(i));
            }
            
            if (result.isEmpty()) {
                return XPathSequence.EMPTY;
            } else if (result.size() == 1) {
                return result.get(0);
            } else {
                return new XPathSequence(result);
            }
        }
    };

    /** insert-before(sequence, position, items) - insert items */
    public static final Function INSERT_BEFORE = new Function() {
        @Override public String getName() { return "insert-before"; }
        @Override public int getMinArgs() { return 3; }
        @Override public int getMaxArgs() { return 3; }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            List<XPathValue> target = toList(args.get(0));
            int position = (int) Math.round(args.get(1).asNumber());
            List<XPathValue> inserts = toList(args.get(2));
            
            // XPath positions are 1-based
            int insertIndex = Math.max(0, Math.min(position - 1, target.size()));
            
            List<XPathValue> result = new ArrayList<>();
            result.addAll(target.subList(0, insertIndex));
            result.addAll(inserts);
            result.addAll(target.subList(insertIndex, target.size()));
            
            if (result.isEmpty()) {
                return XPathSequence.EMPTY;
            } else if (result.size() == 1) {
                return result.get(0);
            } else {
                return new XPathSequence(result);
            }
        }
    };

    /** remove(sequence, position) - remove item at position */
    public static final Function REMOVE = new Function() {
        @Override public String getName() { return "remove"; }
        @Override public int getMinArgs() { return 2; }
        @Override public int getMaxArgs() { return 2; }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            List<XPathValue> items = toList(args.get(0));
            int position = (int) Math.round(args.get(1).asNumber());
            
            // XPath positions are 1-based
            int removeIndex = position - 1;
            
            List<XPathValue> result = new ArrayList<>();
            for (int i = 0; i < items.size(); i++) {
                if (i != removeIndex) {
                    result.add(items.get(i));
                }
            }
            
            if (result.isEmpty()) {
                return XPathSequence.EMPTY;
            } else if (result.size() == 1) {
                return result.get(0);
            } else {
                return new XPathSequence(result);
            }
        }
    };

    /** index-of(sequence, search, collation?) - find positions of value */
    public static final Function INDEX_OF = new Function() {
        @Override public String getName() { return "index-of"; }
        @Override public int getMinArgs() { return 2; }
        @Override public int getMaxArgs() { return 3; }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            List<XPathValue> items = toList(args.get(0));
            String search = args.get(1).asString();
            
            List<XPathValue> result = new ArrayList<>();
            for (int i = 0; i < items.size(); i++) {
                if (search.equals(items.get(i).asString())) {
                    result.add(XPathNumber.of(i + 1));  // 1-based
                }
            }
            
            if (result.isEmpty()) {
                return XPathSequence.EMPTY;
            } else if (result.size() == 1) {
                return result.get(0);
            } else {
                return new XPathSequence(result);
            }
        }
    };

    /** head(sequence) - first item of sequence */
    public static final Function HEAD = new Function() {
        @Override public String getName() { return "head"; }
        @Override public int getMinArgs() { return 1; }
        @Override public int getMaxArgs() { return 1; }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            List<XPathValue> items = toList(args.get(0));
            if (items.isEmpty()) {
                return XPathSequence.EMPTY;
            }
            return items.get(0);
        }
    };

    /** tail(sequence) - all items except first */
    public static final Function TAIL = new Function() {
        @Override public String getName() { return "tail"; }
        @Override public int getMinArgs() { return 1; }
        @Override public int getMaxArgs() { return 1; }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            List<XPathValue> items = toList(args.get(0));
            if (items.size() <= 1) {
                return XPathSequence.EMPTY;
            }
            return new XPathSequence(items.subList(1, items.size()));
        }
    };

    /** count(sequence) - number of items (also in XPath 1.0 for node-sets) */
    public static final Function COUNT = new Function() {
        @Override public String getName() { return "count"; }
        @Override public int getMinArgs() { return 1; }
        @Override public int getMaxArgs() { return 1; }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            XPathValue arg = args.get(0);
            return XPathNumber.of(getSize(arg));
        }
    };

    /** unordered(sequence) - return sequence in implementation-dependent order */
    public static final Function UNORDERED = new Function() {
        @Override public String getName() { return "unordered"; }
        @Override public int getMinArgs() { return 1; }
        @Override public int getMaxArgs() { return 1; }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            return args.get(0);  // Just return as-is
        }
    };

    /** zero-or-one(sequence) - error if more than one item */
    public static final Function ZERO_OR_ONE = new Function() {
        @Override public String getName() { return "zero-or-one"; }
        @Override public int getMinArgs() { return 1; }
        @Override public int getMaxArgs() { return 1; }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            int size = getSize(args.get(0));
            if (size > 1) {
                throw new XPathException("FORG0003: zero-or-one called with sequence of " + size + " items");
            }
            return args.get(0);
        }
    };

    /** one-or-more(sequence) - error if empty */
    public static final Function ONE_OR_MORE = new Function() {
        @Override public String getName() { return "one-or-more"; }
        @Override public int getMinArgs() { return 1; }
        @Override public int getMaxArgs() { return 1; }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            int size = getSize(args.get(0));
            if (size == 0) {
                throw new XPathException("FORG0004: one-or-more called with empty sequence");
            }
            return args.get(0);
        }
    };

    /** exactly-one(sequence) - error if not exactly one item */
    public static final Function EXACTLY_ONE = new Function() {
        @Override public String getName() { return "exactly-one"; }
        @Override public int getMinArgs() { return 1; }
        @Override public int getMaxArgs() { return 1; }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            int size = getSize(args.get(0));
            if (size != 1) {
                throw new XPathException("FORG0005: exactly-one called with sequence of " + size + " items");
            }
            return args.get(0);
        }
    };

    // ========== URI Functions (XPath 2.0) ==========

    /** resolve-uri($relative) or resolve-uri($relative, $base) - resolves a relative URI against a base */
    public static final Function RESOLVE_URI = new Function() {
        @Override public String getName() { return "resolve-uri"; }
        @Override public int getMinArgs() { return 1; }
        @Override public int getMaxArgs() { return 2; }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            XPathValue relArg = args.get(0);
            
            // Handle empty sequence
            if (relArg == null || (relArg instanceof XPathSequence && ((XPathSequence) relArg).isEmpty())) {
                return XPathSequence.EMPTY;
            }
            
            String relative = relArg.asString();
            
            // If relative is empty string, return base URI
            if (relative.isEmpty()) {
                if (args.size() > 1) {
                    return XPathAnyURI.of(args.get(1).asString());
                }
                // Return static base URI if available
                String staticBase = context.getStaticBaseURI();
                return staticBase != null ? XPathAnyURI.of(staticBase) : XPathAnyURI.of("");
            }
            
            try {
                URI relativeUri = new URI(relative);
                
                // If relative is already absolute, return it unchanged
                if (relativeUri.isAbsolute()) {
                    return XPathAnyURI.of(relative);
                }
                
                // Get base URI
                String base;
                if (args.size() > 1) {
                    XPathValue baseArg = args.get(1);
                    if (baseArg == null || (baseArg instanceof XPathSequence && ((XPathSequence) baseArg).isEmpty())) {
                        throw new XPathException("FONS0005: Base URI is empty");
                    }
                    base = baseArg.asString();
                } else {
                    // Use static base URI
                    base = context.getStaticBaseURI();
                    if (base == null || base.isEmpty()) {
                        throw new XPathException("FONS0005: No base URI available");
                    }
                }
                
                URI baseUri = new URI(base);
                URI resolved = baseUri.resolve(relativeUri);
                return XPathAnyURI.of(resolved.toString());
                
            } catch (URISyntaxException e) {
                throw new XPathException("FORG0002: Invalid URI: " + e.getMessage());
            }
        }
    };

    /** base-uri() or base-uri($node) - returns the base URI of a node */
    public static final Function BASE_URI = new Function() {
        @Override public String getName() { return "base-uri"; }
        @Override public int getMinArgs() { return 0; }
        @Override public int getMaxArgs() { return 1; }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            XPathNode node;
            
            if (args.isEmpty()) {
                // Use context node
                node = context.getContextNode();
                if (node == null) {
                    throw new XPathException("XPDY0002: No context node for base-uri()");
                }
            } else {
                XPathValue arg = args.get(0);
                if (arg == null || (arg instanceof XPathSequence && ((XPathSequence) arg).isEmpty())) {
                    return XPathSequence.EMPTY;
                }
                if (!arg.isNodeSet()) {
                    throw new XPathException("XPTY0004: Argument to base-uri must be a node");
                }
                XPathNodeSet ns = (XPathNodeSet) arg;
                if (ns.isEmpty()) {
                    return XPathSequence.EMPTY;
                }
                node = ns.first();
            }
            
            // Get base URI from node (traverse up if needed)
            String baseUri = getNodeBaseUri(node);
            if (baseUri == null || baseUri.isEmpty()) {
                return XPathSequence.EMPTY;
            }
            return XPathAnyURI.of(baseUri);
        }
        
        private String getNodeBaseUri(XPathNode node) {
            // For documents loaded via doc(), the base URI is stored
            // For other nodes, we need to find xml:base or use document URI
            // Walk up the tree looking for xml:base attribute
            XPathNode current = node;
            while (current != null) {
                // Check if the node implements XPathNodeWithBaseURI
                if (current instanceof XPathNodeWithBaseURI) {
                    String baseUri = ((XPathNodeWithBaseURI) current).getBaseURI();
                    if (baseUri != null && !baseUri.isEmpty()) {
                        return baseUri;
                    }
                }
                
                if (current.isElement()) {
                    // Check for xml:base attribute
                    XPathNode xmlBase = current.getAttribute("http://www.w3.org/XML/1998/namespace", "base");
                    if (xmlBase != null) {
                        return xmlBase.getStringValue();
                    }
                }
                if (current.getNodeType() == NodeType.ROOT) {
                    // Reached document root - check for stored base URI
                    break;
                }
                current = current.getParent();
            }
            return null;
        }
    };

    /** static-base-uri() - returns the static base URI from the stylesheet */
    public static final Function STATIC_BASE_URI = new Function() {
        @Override public String getName() { return "static-base-uri"; }
        @Override public int getMinArgs() { return 0; }
        @Override public int getMaxArgs() { return 0; }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            String baseUri = context.getStaticBaseURI();
            if (baseUri == null || baseUri.isEmpty()) {
                return XPathSequence.EMPTY;
            }
            return XPathAnyURI.of(baseUri);
        }
    };

    /** document-uri() or document-uri($node) - returns the document URI of a node */
    public static final Function DOCUMENT_URI = new Function() {
        @Override public String getName() { return "document-uri"; }
        @Override public int getMinArgs() { return 0; }
        @Override public int getMaxArgs() { return 1; }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            XPathNode node;
            
            if (args.isEmpty()) {
                // Use context node
                node = context.getContextNode();
                if (node == null) {
                    throw new XPathException("XPDY0002: No context node for document-uri()");
                }
            } else {
                XPathValue arg = args.get(0);
                if (arg == null || (arg instanceof XPathSequence && ((XPathSequence) arg).isEmpty())) {
                    return XPathSequence.EMPTY;
                }
                if (!arg.isNodeSet()) {
                    throw new XPathException("XPTY0004: Argument to document-uri must be a node");
                }
                XPathNodeSet ns = (XPathNodeSet) arg;
                if (ns.isEmpty()) {
                    return XPathSequence.EMPTY;
                }
                node = ns.first();
            }
            
            // document-uri() only returns a value for document nodes
            if (node.getNodeType() != NodeType.ROOT) {
                return XPathSequence.EMPTY;
            }
            
            // Get document URI - for documents loaded via doc(), this is stored
            // For constructed documents (RTFs), there is no document URI
            if (node instanceof XPathNodeWithBaseURI) {
                String docUri = ((XPathNodeWithBaseURI) node).getDocumentURI();
                if (docUri != null && !docUri.isEmpty()) {
                    return XPathAnyURI.of(docUri);
                }
            }
            return XPathSequence.EMPTY;
        }
    };

    /** deep-equal(seq1, seq2, collation?) - compares two sequences for deep equality */
    public static final Function DEEP_EQUAL = new Function() {
        @Override public String getName() { return "deep-equal"; }
        @Override public int getMinArgs() { return 2; }
        @Override public int getMaxArgs() { return 3; }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            XPathValue seq1 = args.get(0);
            XPathValue seq2 = args.get(1);
            // Collation argument (optional) is ignored for now
            
            return XPathBoolean.of(deepEqual(seq1, seq2));
        }
        
        private boolean deepEqual(XPathValue v1, XPathValue v2) {
            // Handle null/empty
            if (v1 == null && v2 == null) {
                return true;
            }
            if (v1 == null || v2 == null) {
                return false;
            }
            
            // Convert to lists for comparison
            List<XPathValue> list1 = toItems(v1);
            List<XPathValue> list2 = toItems(v2);
            
            // Check sizes match
            if (list1.size() != list2.size()) return false;
            
            // Compare each pair
            for (int i = 0; i < list1.size(); i++) {
                if (!itemsDeepEqual(list1.get(i), list2.get(i))) {
                    return false;
                }
            }
            return true;
        }
        
        private List<XPathValue> toItems(XPathValue value) {
            List<XPathValue> result = new ArrayList<>();
            if (value instanceof XPathSequence) {
                for (XPathValue item : (XPathSequence) value) {
                    result.add(item);
                }
            } else if (value instanceof XPathNodeSet) {
                for (XPathNode node : (XPathNodeSet) value) {
                    result.add(new SingleNodeValue(node));
                }
            } else {
                result.add(value);
            }
            return result;
        }
        
        private boolean itemsDeepEqual(XPathValue item1, XPathValue item2) {
            // Both nodes
            XPathNode node1 = getNode(item1);
            XPathNode node2 = getNode(item2);
            
            if (node1 != null && node2 != null) {
                return nodesDeepEqual(node1, node2);
            }
            if (node1 != null || node2 != null) {
                return false; // One is node, one isn't
            }
            
            // Both atomic values - compare by string value
            // (For a full implementation, would need type-aware comparison)
            return item1.asString().equals(item2.asString());
        }
        
        private XPathNode getNode(XPathValue value) {
            if (value instanceof XPathNodeSet) {
                XPathNodeSet ns = (XPathNodeSet) value;
                if (ns.size() == 1) {
                    return ns.first();
                }
            }
            if (value instanceof SingleNodeValue) {
                return ((SingleNodeValue) value).node;
            }
            return null;
        }
        
        private boolean nodesDeepEqual(XPathNode n1, XPathNode n2) {
            // Different node types
            if (n1.getNodeType() != n2.getNodeType()) {
                return false;
            }
            
            switch (n1.getNodeType()) {
                case ELEMENT:
                    // Same name and namespace
                    if (!n1.getLocalName().equals(n2.getLocalName())) {
                        return false;
                    }
                    String ns1 = n1.getNamespaceURI();
                    String ns2 = n2.getNamespaceURI();
                    if (ns1 == null && ns2 != null) {
                        return false;
                    }
                    if (ns1 != null && !ns1.equals(ns2)) {
                        return false;
                    }
                    
                    // Same attributes (ignoring order)
                    Iterator<XPathNode> attrs1 = n1.getAttributes();
                    Iterator<XPathNode> attrs2 = n2.getAttributes();
                    Map<String, String> attrMap1 = new HashMap<>();
                    Map<String, String> attrMap2 = new HashMap<>();
                    while (attrs1.hasNext()) {
                        XPathNode attr = attrs1.next();
                        String key = "{" + (attr.getNamespaceURI() != null ? attr.getNamespaceURI() : "") + "}" + attr.getLocalName();
                        attrMap1.put(key, attr.getStringValue());
                    }
                    while (attrs2.hasNext()) {
                        XPathNode attr = attrs2.next();
                        String key = "{" + (attr.getNamespaceURI() != null ? attr.getNamespaceURI() : "") + "}" + attr.getLocalName();
                        attrMap2.put(key, attr.getStringValue());
                    }
                    if (!attrMap1.equals(attrMap2)) return false;
                    
                    // Same children (in order)
                    Iterator<XPathNode> children1 = n1.getChildren();
                    Iterator<XPathNode> children2 = n2.getChildren();
                    while (children1.hasNext() && children2.hasNext()) {
                        if (!nodesDeepEqual(children1.next(), children2.next())) {
                            return false;
                        }
                    }
                    // Both should be exhausted
                    return !children1.hasNext() && !children2.hasNext();
                    
                case NAMESPACE:
                    // Namespace nodes are equal if they have the same prefix and same URI
                    String localName1 = n1.getLocalName() != null ? n1.getLocalName() : "";
                    String localName2 = n2.getLocalName() != null ? n2.getLocalName() : "";
                    if (!localName1.equals(localName2)) return false;
                    // Compare namespace URIs
                    return n1.getStringValue().equals(n2.getStringValue());
                    
                case ATTRIBUTE:
                    // Attributes are equal if same expanded-name and same value
                    if (!n1.getLocalName().equals(n2.getLocalName())) return false;
                    String attrNs1 = n1.getNamespaceURI();
                    String attrNs2 = n2.getNamespaceURI();
                    if (attrNs1 == null) {
                        attrNs1 = "";
                    }
                    if (attrNs2 == null) {
                        attrNs2 = "";
                    }
                    if (!attrNs1.equals(attrNs2)) {
                        return false;
                    }
                    return n1.getStringValue().equals(n2.getStringValue());
                    
                case TEXT:
                case COMMENT:
                    // Compare by string value
                    return n1.getStringValue().equals(n2.getStringValue());
                    
                case PROCESSING_INSTRUCTION:
                    // PIs are equal if same target and same content
                    if (!n1.getLocalName().equals(n2.getLocalName())) return false;
                    return n1.getStringValue().equals(n2.getStringValue());
                    
                case ROOT:
                    // Compare document children
                    Iterator<XPathNode> docChildren1 = n1.getChildren();
                    Iterator<XPathNode> docChildren2 = n2.getChildren();
                    while (docChildren1.hasNext() && docChildren2.hasNext()) {
                        if (!nodesDeepEqual(docChildren1.next(), docChildren2.next())) {
                            return false;
                        }
                    }
                    return !docChildren1.hasNext() && !docChildren2.hasNext();
                    
                default:
                    return n1.getStringValue().equals(n2.getStringValue());
            }
        }
        
    };

    // ========== QName Functions ==========

    /** QName(namespace, localname) - construct a QName value */
    public static final Function QNAME = new Function() {
        @Override public String getName() { return "QName"; }
        @Override public int getMinArgs() { return 2; }
        @Override public int getMaxArgs() { return 2; }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            String namespaceURI = args.get(0) != null ? args.get(0).asString() : "";
            String lexicalQName = args.get(1).asString();
            
            // Parse prefix:localName
            int colonPos = lexicalQName.indexOf(':');
            String prefix = "";
            String localName = lexicalQName;
            if (colonPos > 0) {
                prefix = lexicalQName.substring(0, colonPos);
                localName = lexicalQName.substring(colonPos + 1);
            }
            
            return XPathQName.of(namespaceURI, prefix, localName);
        }
    };

    /** local-name-from-QName(qname) - extract local name from QName */
    public static final Function LOCAL_NAME_FROM_QNAME = new Function() {
        @Override public String getName() { return "local-name-from-QName"; }
        @Override public int getMinArgs() { return 1; }
        @Override public int getMaxArgs() { return 1; }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            XPathValue arg = args.get(0);
            if (arg == null || isEmpty(arg)) {
                return XPathSequence.EMPTY;
            }
            
            XPathQName qname = toQName(arg, context);
            if (qname == null) {
                return XPathSequence.EMPTY;
            }
            return XPathString.of(qname.getLocalName());
        }
    };

    /** namespace-uri-from-QName(qname) - extract namespace URI from QName */
    public static final Function NAMESPACE_URI_FROM_QNAME = new Function() {
        @Override public String getName() { return "namespace-uri-from-QName"; }
        @Override public int getMinArgs() { return 1; }
        @Override public int getMaxArgs() { return 1; }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            XPathValue arg = args.get(0);
            if (arg == null || isEmpty(arg)) {
                return XPathSequence.EMPTY;
            }
            
            XPathQName qname = toQName(arg, context);
            if (qname == null) {
                return XPathSequence.EMPTY;
            }
            String uri = qname.getNamespaceURI();
            if (uri == null || uri.isEmpty()) {
                return XPathSequence.EMPTY;
            }
            return XPathAnyURI.of(uri);
        }
    };

    /** prefix-from-QName(qname) - extract prefix from QName */
    public static final Function PREFIX_FROM_QNAME = new Function() {
        @Override public String getName() { return "prefix-from-QName"; }
        @Override public int getMinArgs() { return 1; }
        @Override public int getMaxArgs() { return 1; }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            XPathValue arg = args.get(0);
            if (arg == null || isEmpty(arg)) {
                return XPathSequence.EMPTY;
            }
            
            XPathQName qname = toQName(arg, context);
            if (qname == null) {
                return XPathSequence.EMPTY;
            }
            String prefix = qname.getPrefix();
            if (prefix == null || prefix.isEmpty()) {
                return XPathSequence.EMPTY;
            }
            return XPathString.of(prefix);
        }
    };

    /** in-scope-prefixes(element) - returns all namespace prefixes in scope */
    public static final Function IN_SCOPE_PREFIXES = new Function() {
        @Override public String getName() { return "in-scope-prefixes"; }
        @Override public int getMinArgs() { return 1; }
        @Override public int getMaxArgs() { return 1; }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            XPathValue arg = args.get(0);
            if (arg == null || !arg.isNodeSet()) {
                throw new XPathException("XPTY0004: in-scope-prefixes requires an element argument");
            }
            
            XPathNodeSet nodeSet = (XPathNodeSet) arg;
            if (nodeSet.isEmpty()) {
                throw new XPathException("XPTY0004: in-scope-prefixes requires an element argument");
            }
            
            XPathNode node = nodeSet.first();
            if (node.getNodeType() != NodeType.ELEMENT) {
                throw new XPathException("XPTY0004: in-scope-prefixes requires an element argument");
            }
            
            // Collect all in-scope namespace prefixes
            List<XPathValue> prefixes = new ArrayList<>();
            
            // xml prefix is always in scope
            prefixes.add(XPathString.of("xml"));
            
            // Collect namespace prefixes from this element and ancestors
            XPathNode current = node;
            Set<String> seen = new HashSet<>();
            seen.add("xml");
            
            while (current != null) {
                Iterator<XPathNode> nsNodes = current.getNamespaces();
                if (nsNodes != null) {
                    while (nsNodes.hasNext()) {
                        XPathNode nsNode = nsNodes.next();
                        String prefix = nsNode.getLocalName();
                        if (prefix == null) {
                            prefix = "";
                        }
                        if (!seen.contains(prefix)) {
                            seen.add(prefix);
                            prefixes.add(XPathString.of(prefix));
                        }
                    }
                }
                current = current.getParent();
            }
            
            return new XPathSequence(prefixes);
        }
    };

    /** namespace-uri-for-prefix(prefix, element) - returns namespace URI for a prefix */
    public static final Function NAMESPACE_URI_FOR_PREFIX = new Function() {
        @Override public String getName() { return "namespace-uri-for-prefix"; }
        @Override public int getMinArgs() { return 2; }
        @Override public int getMaxArgs() { return 2; }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            String prefix = args.get(0) != null ? args.get(0).asString() : "";
            
            XPathValue elementArg = args.get(1);
            if (elementArg == null || !elementArg.isNodeSet()) {
                throw new XPathException("XPTY0004: namespace-uri-for-prefix requires an element argument");
            }
            
            XPathNodeSet nodeSet = (XPathNodeSet) elementArg;
            if (nodeSet.isEmpty()) {
                throw new XPathException("XPTY0004: namespace-uri-for-prefix requires an element argument");
            }
            
            XPathNode element = nodeSet.first();
            if (element.getNodeType() != NodeType.ELEMENT) {
                throw new XPathException("XPTY0004: namespace-uri-for-prefix requires an element argument");
            }
            
            // Special case: xml prefix
            if ("xml".equals(prefix)) {
                return XPathAnyURI.of("http://www.w3.org/XML/1998/namespace");
            }
            
            // Search for the namespace binding
            XPathNode current = element;
            while (current != null) {
                Iterator<XPathNode> nsNodes = current.getNamespaces();
                if (nsNodes != null) {
                    while (nsNodes.hasNext()) {
                        XPathNode nsNode = nsNodes.next();
                        String nsPrefix = nsNode.getLocalName();
                        if (nsPrefix == null) {
                            nsPrefix = "";
                        }
                        if (prefix.equals(nsPrefix)) {
                            String uri = nsNode.getStringValue();
                            if (uri != null && !uri.isEmpty()) {
                                return XPathAnyURI.of(uri);
                            }
                            // Empty URI means undeclared prefix
                            return XPathSequence.EMPTY;
                        }
                    }
                }
                current = current.getParent();
            }
            
            // Prefix not found
            return XPathSequence.EMPTY;
        }
    };

    /** node-name(node?) - returns the QName of a node */
    public static final Function NODE_NAME = new Function() {
        @Override public String getName() { return "node-name"; }
        @Override public int getMinArgs() { return 0; }
        @Override public int getMaxArgs() { return 1; }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            XPathNode node;
            
            if (args.isEmpty() || args.get(0) == null) {
                // Use context node
                node = context.getContextNode();
            } else {
                XPathValue arg = args.get(0);
                if (arg instanceof XPathSequence && ((XPathSequence) arg).isEmpty()) {
                    return XPathSequence.EMPTY;
                }
                if (!arg.isNodeSet()) {
                    throw new XPathException("XPTY0004: node-name requires a node argument");
                }
                XPathNodeSet ns = (XPathNodeSet) arg;
                if (ns.isEmpty()) {
                    return XPathSequence.EMPTY;
                }
                node = ns.first();
            }
            
            if (node == null) {
                return XPathSequence.EMPTY;
            }
            
            // Only named nodes return a QName
            NodeType nodeType = node.getNodeType();
            if (nodeType == NodeType.ELEMENT || nodeType == NodeType.ATTRIBUTE) {
                String namespaceURI = node.getNamespaceURI();
                String prefix = node.getPrefix();
                String localName = node.getLocalName();
                return XPathQName.of(namespaceURI != null ? namespaceURI : "", 
                                    prefix != null ? prefix : "", 
                                    localName);
            } else if (nodeType == NodeType.PROCESSING_INSTRUCTION) {
                // PI has a target name in no namespace
                return XPathQName.of("", "", node.getLocalName());
            } else if (nodeType == NodeType.NAMESPACE) {
                // Namespace node name is the prefix
                String prefix = node.getLocalName();
                return XPathQName.of("", "", prefix != null ? prefix : "");
            }
            
            // Other node types (text, comment, document) have no name
            return XPathSequence.EMPTY;
        }
    };

    /** resolve-QName(qname, element) - resolves a lexical QName using in-scope namespaces */
    public static final Function RESOLVE_QNAME = new Function() {
        @Override public String getName() { return "resolve-QName"; }
        @Override public int getMinArgs() { return 2; }
        @Override public int getMaxArgs() { return 2; }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            XPathValue qnameArg = args.get(0);
            if (qnameArg == null || isEmpty(qnameArg)) {
                return XPathSequence.EMPTY;
            }
            
            String lexicalQName = qnameArg.asString();
            if (lexicalQName == null || lexicalQName.isEmpty()) {
                return XPathSequence.EMPTY;
            }
            
            XPathValue elementArg = args.get(1);
            if (elementArg == null || !elementArg.isNodeSet()) {
                throw new XPathException("XPTY0004: resolve-QName requires an element argument");
            }
            
            XPathNodeSet nodeSet = (XPathNodeSet) elementArg;
            if (nodeSet.isEmpty()) {
                throw new XPathException("XPTY0004: resolve-QName requires an element argument");
            }
            
            XPathNode element = nodeSet.first();
            if (element.getNodeType() != NodeType.ELEMENT) {
                throw new XPathException("XPTY0004: resolve-QName requires an element argument");
            }
            
            // Parse the lexical QName
            int colonPos = lexicalQName.indexOf(':');
            String prefix = "";
            String localName = lexicalQName;
            if (colonPos > 0) {
                prefix = lexicalQName.substring(0, colonPos);
                localName = lexicalQName.substring(colonPos + 1);
            }
            
            // Resolve namespace URI
            String namespaceURI = "";
            if (!prefix.isEmpty()) {
                // Look up prefix in element's in-scope namespaces
                XPathNode current = element;
                while (current != null) {
                    Iterator<XPathNode> nsNodes = current.getNamespaces();
                    if (nsNodes != null) {
                        while (nsNodes.hasNext()) {
                            XPathNode nsNode = nsNodes.next();
                            String nsPrefix = nsNode.getLocalName();
                            if (nsPrefix == null) {
                            nsPrefix = "";
                        }
                            if (prefix.equals(nsPrefix)) {
                                namespaceURI = nsNode.getStringValue();
                                if (namespaceURI == null) {
                                    namespaceURI = "";
                                }
                                break;
                            }
                        }
                    }
                    if (!namespaceURI.isEmpty()) break;
                    current = current.getParent();
                }
                
                if (namespaceURI.isEmpty()) {
                    throw new XPathException("FONS0004: Prefix '" + prefix + "' is not bound to a namespace");
                }
            } else {
                // No prefix - use default namespace (empty prefix)
                XPathNode current = element;
                while (current != null) {
                    Iterator<XPathNode> nsNodes = current.getNamespaces();
                    if (nsNodes != null) {
                        while (nsNodes.hasNext()) {
                            XPathNode nsNode = nsNodes.next();
                            String nsPrefix = nsNode.getLocalName();
                            if (nsPrefix == null || nsPrefix.isEmpty()) {
                                namespaceURI = nsNode.getStringValue();
                                if (namespaceURI == null) {
                                    namespaceURI = "";
                                }
                                break;
                            }
                        }
                    }
                    if (!namespaceURI.isEmpty()) break;
                    current = current.getParent();
                }
            }
            
            return XPathQName.of(namespaceURI, prefix, localName);
        }
    };

    // Helper: convert value to XPathQName
    private static XPathQName toQName(XPathValue value, XPathContext context) {
        if (value instanceof XPathQName) {
            return (XPathQName) value;
        }
        // Try to parse as string
        String str = value.asString();
        if (str == null || str.isEmpty()) {
            return null;
        }
        // Parse with context namespace resolver
        return XPathQName.parse(str, new XPathQName.NamespaceResolver() {
            @Override
            public String resolve(String prefix) {
                if (context != null) {
                    return context.resolveNamespacePrefix(prefix);
                }
                return null;
            }
        });
    }

    // Helper class to wrap a single node as an XPathValue (used by deep-equal)
    private static class SingleNodeValue implements XPathValue {
        final XPathNode node;
        SingleNodeValue(XPathNode node) { this.node = node; }
        @Override public Type getType() { return Type.NODESET; }
        @Override public boolean isNodeSet() { return true; }
        @Override public boolean asBoolean() { return true; }
        @Override public double asNumber() { 
            try { return Double.parseDouble(node.getStringValue()); }
            catch (Exception e) { return Double.NaN; }
        }
        @Override public String asString() { return node.getStringValue(); }
        @Override public XPathNodeSet asNodeSet() { 
            return new XPathNodeSet(Collections.singletonList(node)); 
        }
    }

    // ========== Helper methods ==========

    private static boolean isEmpty(XPathValue value) {
        if (value == null) {
            return true;
        }
        if (value instanceof XPathSequence) {
            return ((XPathSequence) value).isEmpty();
        }
        if (value instanceof XPathNodeSet) {
            return ((XPathNodeSet) value).isEmpty();
        }
        return false;
    }

    private static int getSize(XPathValue value) {
        if (value == null) {
            return 0;
        }
        if (value instanceof XPathSequence) {
            return ((XPathSequence) value).size();
        }
        if (value instanceof XPathNodeSet) {
            return ((XPathNodeSet) value).size();
        }
        return 1;  // Single atomic value
    }

    private static List<XPathValue> toList(XPathValue value) {
        List<XPathValue> result = new ArrayList<>();
        if (value == null) {
            return result;
        }
        if (value instanceof XPathSequence) {
            XPathSequence seq = (XPathSequence) value;
            for (XPathValue item : seq) {
                result.add(item);
            }
        } else if (value instanceof XPathNodeSet) {
            XPathNodeSet nodeSet = (XPathNodeSet) value;
            for (XPathNode node : nodeSet) {
                result.add(XPathString.of(node.getStringValue()));
            }
        } else {
            result.add(value);
        }
        return result;
    }
}

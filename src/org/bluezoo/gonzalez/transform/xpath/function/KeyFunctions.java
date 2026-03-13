/*
 * KeyFunctions.java
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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.xml.sax.SAXException;

import org.bluezoo.gonzalez.transform.ast.SequenceNode;
import org.bluezoo.gonzalez.transform.ast.XSLTNode;
import org.bluezoo.gonzalez.transform.compiler.CompiledStylesheet;
import org.bluezoo.gonzalez.transform.compiler.KeyDefinition;
import org.bluezoo.gonzalez.transform.compiler.Pattern;
import org.bluezoo.gonzalez.transform.compiler.SequenceBuilderOutputHandler;
import org.bluezoo.gonzalez.transform.runtime.BasicTransformContext;
import org.bluezoo.gonzalez.transform.runtime.TransformContext;
import org.bluezoo.gonzalez.transform.xpath.Collation;
import org.bluezoo.gonzalez.transform.xpath.XPathContext;
import org.bluezoo.gonzalez.transform.xpath.XPathExpression;
import org.bluezoo.gonzalez.transform.xpath.expr.XPathException;
import org.bluezoo.gonzalez.transform.xpath.type.NodeType;
import org.bluezoo.gonzalez.transform.xpath.type.XPathDateTime;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNode;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNodeSet;
import org.bluezoo.gonzalez.transform.xpath.type.XPathSequence;
import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;

/**
 * XSLT key() function implementation.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
final class KeyFunctions {

    private KeyFunctions() {
    }

    static Function key() {
        return new KeyFunction();
    }

    /**
     * XSLT key() function.
     *
     * <p>Returns nodes matching a key definition. The key name is resolved to a namespace URI
     * if it contains a prefix. Searches the document tree for nodes matching the key pattern
     * and use expression.
     *
     * <p>Signature: key(string, object) → node-set
     *
     * @see <a href="https://www.w3.org/TR/xslt/#function-key">XSLT 1.0 key()</a>
     * @see <a href="https://www.w3.org/TR/xslt20/#function-key">XSLT 2.0 key()</a>
     */
    private static class KeyFunction implements Function {
        @Override
        public String getName() { return "key"; }

        @Override
        public int getMinArgs() { return 2; }

        @Override
        public int getMaxArgs() { return 3; }  // XSLT 2.0+: third arg is target document node

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            String keyName = args.get(0).asString();
            XPathValue keyValue = args.get(1);

            if (!(context instanceof TransformContext)) {
                return XPathNodeSet.empty();
            }
            TransformContext txContext = (TransformContext) context;
            CompiledStylesheet stylesheet = txContext.getStylesheet();

            if (!isValidQName(keyName)) {
                throw new XPathException("XTDE1260: key name is not a valid QName: '" + keyName + "'");
            }

            String expandedName = expandKeyName(keyName, context);

            List<KeyDefinition> keyDefs = stylesheet.getKeyDefinitions(expandedName);
            if (keyDefs.isEmpty()) {
                throw new XPathException("XTDE1260: No xsl:key declaration with name '" + keyName + "'");
            }
            boolean isComposite = keyDefs.get(0).isComposite();

            // Determine the target document root.
            // Third argument (XSLT 2.0+) specifies a node; the key search
            // is restricted to the subtree rooted at that node.
            XPathNode root;
            XPathNode topNode = null;
            if (args.size() > 2) {
                XPathValue topArg = args.get(2);
                if (topArg instanceof XPathNode) {
                    topNode = (XPathNode) topArg;
                } else if (topArg instanceof XPathNodeSet) {
                    List<XPathNode> nodes = ((XPathNodeSet) topArg).getNodes();
                    if (!nodes.isEmpty()) {
                        topNode = nodes.get(0);
                    }
                } else if (topArg.isNodeSet()) {
                    XPathNodeSet ns = topArg.asNodeSet();
                    if (ns != null && !ns.isEmpty()) {
                        topNode = ns.iterator().next();
                    }
                }
                if (topNode == null) {
                    throw new XPathException("XTDE1270: Third argument to key() is not a node");
                }
                root = topNode.getRoot();
                // XTDE1270: root must be a document node
                if (root == null || root.getNodeType() != NodeType.ROOT) {
                    throw new XPathException("XTDE1270: The root of the tree containing the " +
                        "third argument to key() is not a document node");
                }
            } else {
                XPathNode contextNode = context.getContextNode();
                if (contextNode == null) {
                    throw new XPathException("XTDE1270: key() with two arguments requires " +
                        "a context node, but the context item is absent");
                }
                root = contextNode.getRoot();
                // XTDE1270: root must be a document node
                if (root == null || root.getNodeType() != NodeType.ROOT) {
                    throw new XPathException("XTDE1270: The root of the tree containing the " +
                        "context node is not a document node");
                }
            }

            // Collect search values from the second argument
            // NaN values never match (NaN ne NaN per XPath spec)
            boolean isXPath2 = txContext.getXsltVersion() >= 2.0;
            List<String> searchValues = new ArrayList<String>();
            if (isComposite) {
                String compositeKey = buildCompositeSearchKey(keyValue);
                searchValues.add(compositeKey);
            } else if (keyValue instanceof XPathNodeSet) {
                for (XPathNode node : ((XPathNodeSet) keyValue).getNodes()) {
                    searchValues.add(node.getStringValue());
                }
            } else if (keyValue instanceof XPathSequence) {
                for (XPathValue item : (XPathSequence) keyValue) {
                    String canonical = canonicalKeyValue(item, isXPath2);
                    if (canonical != null) {
                        searchValues.add(canonical);
                    }
                }
            } else {
                String canonical = canonicalKeyValue(keyValue, isXPath2);
                if (canonical != null) {
                    searchValues.add(canonical);
                }
            }

            // Resolve the collation for key comparison.
            // The xsl:key collation attribute takes precedence over the default collation.
            String keyCollUri = keyDefs.get(0).getCollation();
            if (keyCollUri == null) {
                keyCollUri = txContext.getDefaultCollation();
            }
            final Collation collation = Collation.forUri(
                    keyCollUri != null ? keyCollUri : Collation.CODEPOINT_URI);

            // Look up (or build) the cached key index for this key + document + collation
            Map<String, List<XPathNode>> index = null;
            String collUri = keyCollUri != null ? keyCollUri : Collation.CODEPOINT_URI;
            String cacheKey = expandedName + "\0" + System.identityHashCode(root)
                    + "\0" + collUri;

            if (txContext instanceof BasicTransformContext) {
                BasicTransformContext btx = (BasicTransformContext) txContext;
                index = btx.getKeyIndex(cacheKey);

                if (index == null) {
                    if (btx.isKeyBeingEvaluated(expandedName)) {
                        throw new XPathException("XTDE0640: Circular reference in key: " + keyName);
                    }
                    btx.startKeyEvaluation(expandedName);
                    try {
                        index = buildKeyIndex(root, keyDefs, collation, btx);
                    } finally {
                        btx.endKeyEvaluation(expandedName);
                    }
                    btx.putKeyIndex(cacheKey, index);
                }
            } else {
                index = buildKeyIndex(root, keyDefs, collation, txContext);
            }

            // Look up matching nodes from the index
            List<XPathNode> result = new ArrayList<XPathNode>();
            for (int i = 0; i < searchValues.size(); i++) {
                String sv = searchValues.get(i);
                List<XPathNode> matches = index.get(sv);
                if (matches != null) {
                    for (int j = 0; j < matches.size(); j++) {
                        XPathNode matched = matches.get(j);
                        if (topNode != null && !isDescendantOrSelf(topNode, matched)) {
                            continue;
                        }
                        boolean dup = false;
                        for (int k = 0; k < result.size(); k++) {
                            if (result.get(k).isSameNode(matched)) {
                                dup = true;
                                break;
                            }
                        }
                        if (!dup) {
                            result.add(matched);
                        }
                    }
                }
            }

            return new XPathNodeSet(result);
        }

        /**
         * Builds a complete key index for the given key definitions and document.
         * Walks the entire document tree once per definition, evaluating the match
         * pattern and use expression/content, and returns a map from key value
         * strings to matching nodes. Multiple definitions with the same name are
         * all active (XSLT 2.0+).
         */
        private Map<String, List<XPathNode>> buildKeyIndex(XPathNode root,
                List<KeyDefinition> keyDefs, final Collation collation,
                TransformContext context) throws XPathException {
            Comparator<String> comparator = new Comparator<String>() {
                @Override
                public int compare(String a, String b) {
                    return collation.compare(a, b);
                }
            };
            Map<String, List<XPathNode>> index = new TreeMap<String, List<XPathNode>>(comparator);
            for (int d = 0; d < keyDefs.size(); d++) {
                KeyDefinition keyDef = keyDefs.get(d);
                indexNode(root, keyDef, index, context);
            }
            return index;
        }

        /**
         * Recursively indexes a node and its descendants for a key definition.
         */
        private void indexNode(XPathNode node, KeyDefinition keyDef,
                Map<String, List<XPathNode>> index,
                TransformContext context) throws XPathException {
            BasicTransformContext btx = (BasicTransformContext) context;
            Pattern matchPattern = keyDef.getMatchPattern();

            if (matchPattern.matches(node, context)) {
                addToIndex(node, keyDef, index, btx);
            }

            if (node.isElement()) {
                Iterator<XPathNode> attrs = node.getAttributes();
                while (attrs.hasNext()) {
                    XPathNode attr = attrs.next();
                    if (matchPattern.matches(attr, context)) {
                        addToIndex(attr, keyDef, index, btx);
                    }
                }
            }

            Iterator<XPathNode> children = node.getChildren();
            while (children.hasNext()) {
                indexNode(children.next(), keyDef, index, context);
            }
        }

        /**
         * Evaluates the use expression or content constructors for a matched node
         * and adds entries to the index.
         */
        private void addToIndex(XPathNode node, KeyDefinition keyDef,
                Map<String, List<XPathNode>> index, BasicTransformContext btx)
                throws XPathException {
            XPathContext nodeContext = btx.withXsltCurrentNode(node);
            XPathValue useValue;
            XPathExpression useExpr = keyDef.getUseExpr();
            if (useExpr != null) {
                useValue = useExpr.evaluate(nodeContext);
            } else {
                try {
                    SequenceBuilderOutputHandler seqBuilder = new SequenceBuilderOutputHandler();
                    SequenceNode content = keyDef.getContent();
                    List<XSLTNode> contentChildren = content.getChildren();
                    for (int i = 0; i < contentChildren.size(); i++) {
                        contentChildren.get(i).execute(btx.withXsltCurrentNode(node), seqBuilder);
                        seqBuilder.markItemBoundary();
                    }
                    useValue = seqBuilder.getSequence();
                } catch (SAXException e) {
                    throw new XPathException(e.getMessage(), e);
                }
            }

            boolean isXPath2 = btx.getXsltVersion() >= 2.0;
            if (keyDef.isComposite()) {
                String compositeKey = buildCompositeUseKey(useValue);
                addNodeToIndex(node, compositeKey, index);
            } else if (useValue instanceof XPathNodeSet) {
                for (XPathNode n : ((XPathNodeSet) useValue).getNodes()) {
                    addNodeToIndex(node, n.getStringValue(), index);
                }
            } else if (useValue instanceof XPathSequence) {
                for (XPathValue item : (XPathSequence) useValue) {
                    String canonical = canonicalKeyValue(item, isXPath2);
                    if (canonical != null) {
                        addNodeToIndex(node, canonical, index);
                    }
                }
            } else {
                String canonical = canonicalKeyValue(useValue, isXPath2);
                if (canonical != null) {
                    addNodeToIndex(node, canonical, index);
                }
            }
        }

        private boolean isNaN(XPathValue value) {
            if (value.getType() == XPathValue.Type.NUMBER) {
                return Double.isNaN(value.asNumber());
            }
            return false;
        }

        /**
         * Returns a canonical key string for typed comparison in XSLT 2.0+.
         * Uses type prefixes so that different primitive types never match
         * (e.g., number 4 vs string "4"). DateTimes are normalized to UTC
         * for timezone-aware equality.
         * In XSLT 1.0 mode, returns plain string form (current behavior).
         */
        private String canonicalKeyValue(XPathValue value, boolean isXPath2) {
            if (!isXPath2) {
                if (isNaN(value)) {
                    return null;
                }
                return value.asString();
            }
            if (value instanceof XPathDateTime) {
                XPathDateTime dt = (XPathDateTime) value;
                if (!dt.isDuration()) {
                    try {
                        XPathDateTime normalized = dt.adjustToTimezone(0);
                        return "T\0" + normalized.asString();
                    } catch (XPathException e) {
                        return "T\0" + dt.asString();
                    }
                }
                return "T\0" + dt.asString();
            }
            if (value.getType() == XPathValue.Type.NUMBER) {
                double d = value.asNumber();
                if (Double.isNaN(d)) {
                    return null;
                }
                return "N\0" + d;
            }
            return value.asString();
        }

        private boolean isDescendantOrSelf(XPathNode ancestor, XPathNode node) {
            XPathNode current = node;
            while (current != null) {
                if (current.isSameNode(ancestor)) {
                    return true;
                }
                current = current.getParent();
            }
            return false;
        }

        private void addNodeToIndex(XPathNode node, String keyVal,
                Map<String, List<XPathNode>> index) {
            List<XPathNode> list = index.get(keyVal);
            if (list == null) {
                list = new ArrayList<XPathNode>();
                index.put(keyVal, list);
            }
            list.add(node);
        }

        /**
         * Builds a composite key string from the use expression result.
         * Concatenates sequence items with a null-byte separator.
         */
        private String buildCompositeUseKey(XPathValue value) {
            StringBuilder sb = new StringBuilder();
            if (value instanceof XPathSequence) {
                boolean first = true;
                for (XPathValue item : (XPathSequence) value) {
                    if (!first) {
                        sb.append('\0');
                    }
                    sb.append(item.asString());
                    first = false;
                }
            } else if (value instanceof XPathNodeSet) {
                boolean first = true;
                for (XPathNode n : ((XPathNodeSet) value).getNodes()) {
                    if (!first) {
                        sb.append('\0');
                    }
                    sb.append(n.getStringValue());
                    first = false;
                }
            } else {
                sb.append(value.asString());
            }
            return sb.toString();
        }

        /**
         * Builds a composite search key from the second argument to key().
         * For composite keys, the search value is a sequence treated as a tuple.
         */
        private String buildCompositeSearchKey(XPathValue keyValue) {
            StringBuilder sb = new StringBuilder();
            if (keyValue instanceof XPathSequence) {
                boolean first = true;
                for (XPathValue item : (XPathSequence) keyValue) {
                    if (!first) {
                        sb.append('\0');
                    }
                    sb.append(item.asString());
                    first = false;
                }
            } else if (keyValue instanceof XPathNodeSet) {
                boolean first = true;
                for (XPathNode n : ((XPathNodeSet) keyValue).getNodes()) {
                    if (!first) {
                        sb.append('\0');
                    }
                    sb.append(n.getStringValue());
                    first = false;
                }
            } else {
                sb.append(keyValue.asString());
            }
            return sb.toString();
        }

        /**
         * Expands a key name to Clark notation {uri}localname.
         * This resolves namespace prefixes using the XPath context.
         */
        private String expandKeyName(String keyName, XPathContext context)
                throws XPathException {
            if (keyName == null) {
                return null;
            }
            int colon = keyName.indexOf(':');
            if (colon > 0) {
                String prefix = keyName.substring(0, colon);
                String localPart = keyName.substring(colon + 1);
                String uri = context.resolveNamespacePrefix(prefix);
                if (uri == null || uri.isEmpty()) {
                    throw new XPathException("XTDE1260: No namespace declaration in scope " +
                        "for prefix '" + prefix + "' in key name '" + keyName + "'");
                }
                return "{" + uri + "}" + localPart;
            }
            return keyName;
        }

        private static boolean isValidQName(String name) {
            if (name == null || name.isEmpty()) {
                return false;
            }
            int colon = name.indexOf(':');
            if (colon == 0 || colon == name.length() - 1) {
                return false;
            }
            if (colon > 0) {
                return isValidNCName(name.substring(0, colon))
                    && isValidNCName(name.substring(colon + 1));
            }
            return isValidNCName(name);
        }

        private static boolean isValidNCName(String name) {
            if (name == null || name.isEmpty()) {
                return false;
            }
            char first = name.charAt(0);
            if (!isNameStartChar(first)) {
                return false;
            }
            for (int i = 1; i < name.length(); i++) {
                if (!isNameChar(name.charAt(i))) {
                    return false;
                }
            }
            return true;
        }

        private static boolean isNameStartChar(char c) {
            return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_';
        }

        private static boolean isNameChar(char c) {
            return isNameStartChar(c) || (c >= '0' && c <= '9') || c == '-' || c == '.';
        }
    }
}

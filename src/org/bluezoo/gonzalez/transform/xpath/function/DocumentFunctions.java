/*
 * DocumentFunctions.java
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
import java.util.Collections;
import java.util.List;

import java.net.URI;

import org.bluezoo.gonzalez.transform.compiler.CompiledStylesheet;
import org.bluezoo.gonzalez.transform.runtime.DocumentLoader;
import org.bluezoo.gonzalez.transform.runtime.TransformContext;
import org.bluezoo.gonzalez.transform.xpath.XPathContext;
import org.bluezoo.gonzalez.transform.xpath.expr.XPathException;
import org.bluezoo.gonzalez.transform.xpath.type.NodeType;
import org.bluezoo.gonzalez.transform.xpath.type.XPathBoolean;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNode;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNodeSet;
import org.bluezoo.gonzalez.transform.xpath.type.XPathSequence;
import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNodeWithBaseURI;

/**
 * XSLT/XPath document functions (document, doc, doc-available).
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
final class DocumentFunctions {

    private DocumentFunctions() {
    }

    static Function document() {
        return new DocumentFunction();
    }

    static Function doc() {
        return new DocFunction();
    }

    static Function docAvailable() {
        return new DocAvailableFunction();
    }

    /**
     * XSLT document() function.
     *
     * <p>Loads an external XML document from a URI. If the URI is empty, returns the
     * stylesheet document. Results are cached so the same URI always returns the same document.
     *
     * <p>Signature: document(object, node-set?) → node-set
     *
     * @see <a href="https://www.w3.org/TR/xslt/#function-document">XSLT 1.0 document()</a>
     */
    private static class DocumentFunction implements Function {
        @Override
        public String getName() { return "document"; }

        @Override
        public int getMinArgs() { return 1; }

        @Override
        public int getMaxArgs() { return 2; }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            XPathValue uriArg = args.get(0);

            // Handle empty argument
            if (uriArg == null) {
                return XPathNodeSet.empty();
            }

            // Get base URI from second argument if provided
            boolean hasExplicitBase = (args.size() > 1 && args.get(1) != null);
            String baseUri = null;
            if (hasExplicitBase) {
                XPathValue baseArg = args.get(1);
                if (baseArg.isNodeSet()) {
                    XPathNodeSet baseNodes = baseArg.asNodeSet();
                    if (!baseNodes.isEmpty()) {
                        XPathNode baseNode = baseNodes.first();
                        if (baseNode instanceof XPathNodeWithBaseURI) {
                            baseUri = ((XPathNodeWithBaseURI) baseNode).getBaseURI();
                        }
                        if (baseUri == null || baseUri.isEmpty()) {
                            baseUri = getDocumentNodeBaseUri(baseNode);
                        }
                        // XTDE1162: second argument node has no base URI
                        if (baseUri == null || baseUri.isEmpty()) {
                            throw new XPathException("XTDE1162: No base " +
                                "URI available on the node supplied as " +
                                "the second argument to document()");
                        }
                    }
                } else if (baseArg instanceof XPathNode) {
                    XPathNode baseNode = (XPathNode) baseArg;
                    if (baseNode instanceof XPathNodeWithBaseURI) {
                        baseUri = ((XPathNodeWithBaseURI) baseNode).getBaseURI();
                    }
                    if (baseUri == null || baseUri.isEmpty()) {
                        baseUri = getDocumentNodeBaseUri(baseNode);
                    }
                    if (baseUri == null || baseUri.isEmpty()) {
                        throw new XPathException("XTDE1162: No base " +
                            "URI available on the node supplied as " +
                            "the second argument to document()");
                    }
                } else {
                    baseUri = baseArg.asString();
                }
            }

            // Fall back to static base URI (used for string arguments and as last resort)
            if (baseUri == null || baseUri.isEmpty()) {
                baseUri = context.getStaticBaseURI();
            }

            // Handle node-set argument (document each node's string value)
            if (uriArg.isNodeSet()) {
                XPathNodeSet uriNodes = uriArg.asNodeSet();
                List<XPathNode> results = new ArrayList<>();
                for (XPathNode node : uriNodes) {
                    String uri = node.getStringValue();
                    // Per XSLT spec: when document() has one node-set argument,
                    // the base URI for resolving each node's string value is the
                    // base URI of that node.
                    String nodeBaseUri;
                    if (!hasExplicitBase) {
                        nodeBaseUri = getDocumentNodeBaseUri(node);
                        if (nodeBaseUri == null || nodeBaseUri.isEmpty()) {
                            // XTDE1162: no base URI for relative URI
                            if (!isAbsoluteUri(uri)) {
                                throw new XPathException("XTDE1162: No base " +
                                    "URI available to resolve relative URI '" +
                                    uri + "' in document()");
                            }
                            nodeBaseUri = baseUri;
                        }
                    } else {
                        nodeBaseUri = baseUri;
                    }
                    // Get strip-space rules from stylesheet if available
                    List<String> stripSpace = null;
                    List<String> preserveSpace = null;
                    if (context instanceof TransformContext) {
                        CompiledStylesheet stylesheet = ((TransformContext) context).getStylesheet();
                        if (stylesheet != null) {
                            stripSpace = stylesheet.getStripSpaceElements();
                            preserveSpace = stylesheet.getPreserveSpaceElements();
                        }
                    }
                    XPathNode doc = DocumentLoader.loadDocument(uri, nodeBaseUri, stripSpace, preserveSpace);
                    if (doc != null) {
                        results.add(doc);
                    }
                }
                return new XPathNodeSet(results);
            }

            // Handle string argument
            String uri = uriArg.asString();
            if (uri.isEmpty()) {
                // Empty string returns the stylesheet document containing the document() call
                // Per XSLT spec, document('') returns the document node of the stylesheet module
                String stylesheetUri = context.getStaticBaseURI();
                if (stylesheetUri != null && !stylesheetUri.isEmpty()) {
                    // Don't apply strip-space to the stylesheet document itself
                    XPathNode doc = DocumentLoader.loadDocument(stylesheetUri, null, null, null);
                    if (doc != null) {
                        return new XPathNodeSet(Collections.singletonList(doc));
                    }
                }
                // Fallback: return empty if stylesheet URI not available
                return XPathNodeSet.empty();
            }

            // Get strip-space rules from stylesheet if available
            List<String> stripSpace = null;
            List<String> preserveSpace = null;
            if (context instanceof TransformContext) {
                CompiledStylesheet stylesheet = ((TransformContext) context).getStylesheet();
                if (stylesheet != null) {
                    stripSpace = stylesheet.getStripSpaceElements();
                    preserveSpace = stylesheet.getPreserveSpaceElements();
                }
            }

            XPathNode doc = DocumentLoader.loadDocument(uri, baseUri, stripSpace, preserveSpace);
            if (doc != null) {
                return new XPathNodeSet(Collections.singletonList(doc));
            }
            return XPathNodeSet.empty();
        }

        /**
         * Gets the base URI for a node by walking up to the document root.
         * For nodes from documents loaded via doc()/document(), the document
         * root stores the absolute URI of the loaded document.
         */
        private static boolean isAbsoluteUri(String uri) {
            if (uri == null || uri.isEmpty()) {
                return false;
            }
            int colonPos = uri.indexOf(':');
            if (colonPos <= 0) {
                return false;
            }
            for (int i = 0; i < colonPos; i++) {
                char c = uri.charAt(i);
                if (!Character.isLetterOrDigit(c) && c != '+' && c != '-' && c != '.') {
                    return false;
                }
            }
            return true;
        }

        private String getDocumentNodeBaseUri(XPathNode node) {
            if (node instanceof XPathNodeWithBaseURI) {
                String uri = ((XPathNodeWithBaseURI) node).getBaseURI();
                if (uri != null && !uri.isEmpty()) {
                    return uri;
                }
            }
            XPathNode current = node.getParent();
            while (current != null) {
                if (current instanceof XPathNodeWithBaseURI) {
                    String uri = ((XPathNodeWithBaseURI) current).getBaseURI();
                    if (uri != null && !uri.isEmpty()) {
                        return uri;
                    }
                }
                current = current.getParent();
            }
            return null;
        }
    }

    /**
     * XPath 2.0 doc() function.
     *
     * <p>Loads an external XML document from a URI. Unlike document(), this function
     * throws an error if the document cannot be loaded.
     *
     * <p>Signature: doc(string?) → document-node?
     *
     * @see <a href="https://www.w3.org/TR/xpath-functions-30/#func-doc">XPath 3.0 doc()</a>
     */
    private static class DocFunction implements Function {
        @Override
        public String getName() { return "doc"; }

        @Override
        public int getMinArgs() { return 1; }

        @Override
        public int getMaxArgs() { return 1; }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            XPathValue uriArg = args.get(0);

            // doc(string?) - empty sequence returns empty sequence
            if (uriArg == null
                    || (uriArg instanceof XPathSequence && ((XPathSequence)uriArg).isEmpty())
                    || (uriArg.isNodeSet() && uriArg.asNodeSet().isEmpty())) {
                return XPathSequence.EMPTY;
            }

            String uri = uriArg.asString();

            // Get base URI from static context (stylesheet base URI)
            String baseUri = context.getStaticBaseURI();

            if (uri.isEmpty()) {
                // Empty string returns the stylesheet document containing the doc() call
                // Per XSLT spec, doc('') behaves like document('') - returns the stylesheet module
                if (baseUri != null && !baseUri.isEmpty()) {
                    // Don't apply strip-space to the stylesheet document itself
                    XPathNode doc = DocumentLoader.loadDocument(baseUri, null, null, null);
                    if (doc != null) {
                        return new XPathNodeSet(Collections.singletonList(doc));
                    }
                }
                throw new XPathException("FODC0002: Cannot determine stylesheet document for doc('')");
            }

            // Get strip-space rules from stylesheet if available
            List<String> stripSpace = null;
            List<String> preserveSpace = null;
            if (context instanceof TransformContext) {
                CompiledStylesheet stylesheet = ((TransformContext) context).getStylesheet();
                if (stylesheet != null) {
                    stripSpace = stylesheet.getStripSpaceElements();
                    preserveSpace = stylesheet.getPreserveSpaceElements();
                }
            }

            // Resolve to absolute URI and check if it matches a document already
            // in the current context (e.g., the source document). Per the XPath
            // spec, doc() must return the same document node identity for the same
            // URI.
            String absoluteUri = null;
            try {
                if (baseUri != null && !baseUri.isEmpty()) {
                    URI baseUriObj = new URI(baseUri);
                    URI resolved = baseUriObj.resolve(uri);
                    absoluteUri = resolved.toString();
                } else {
                    URI uriObj = new URI(uri);
                    absoluteUri = uriObj.toString();
                }
            } catch (Exception e) {
                // Fall through to DocumentLoader which does its own resolution
            }

            if (absoluteUri != null) {
                XPathNode contextNode = context.getContextNode();
                if (contextNode != null) {
                    XPathNode root = contextNode.getRoot();
                    if (root != null && root.getNodeType() == NodeType.ROOT
                            && root instanceof XPathNodeWithBaseURI) {
                        String rootDocUri = ((XPathNodeWithBaseURI) root).getDocumentURI();
                        if (absoluteUri.equals(rootDocUri)) {
                            return new XPathNodeSet(Collections.singletonList(root));
                        }
                    }
                }
            }

            XPathNode doc = DocumentLoader.loadDocument(uri, baseUri, stripSpace, preserveSpace);
            if (doc == null) {
                throw new XPathException("FODC0002: Cannot retrieve document at " + uri);
            }
            return new XPathNodeSet(Collections.singletonList(doc));
        }
    }

    /**
     * Implements the XPath 2.0+ doc-available() function.
     *
     * <p>Returns true if a document at the specified URI can be loaded.
     * Returns false (rather than throwing an error) if the document cannot be loaded.
     *
     * <p>Signature: doc-available(string?) → boolean
     *
     * @see <a href="https://www.w3.org/TR/xpath-functions-30/#func-doc-available">XPath 3.0 doc-available()</a>
     */
    private static class DocAvailableFunction implements Function {
        @Override
        public String getName() { return "doc-available"; }

        @Override
        public int getMinArgs() { return 1; }

        @Override
        public int getMaxArgs() { return 1; }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            XPathValue uriArg = args.get(0);

            // Handle empty sequence - returns false
            if (uriArg == null || (uriArg instanceof XPathSequence && ((XPathSequence)uriArg).isEmpty())) {
                return XPathBoolean.FALSE;
            }

            String uri = uriArg.asString();

            // Empty string is a special case - check if stylesheet document is available
            if (uri.isEmpty()) {
                String baseUri = context.getStaticBaseURI();
                return XPathBoolean.of(baseUri != null && !baseUri.isEmpty());
            }

            // Get base URI from static context
            String baseUri = context.getStaticBaseURI();

            // Try to load the document - catch any errors
            XPathNode doc = DocumentLoader.loadDocument(uri, baseUri, null, null);
            return XPathBoolean.of(doc != null);
        }
    }
}

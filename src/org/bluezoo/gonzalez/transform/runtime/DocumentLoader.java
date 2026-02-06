/*
 * DocumentLoader.java
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
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Utility class for loading XML documents into navigable node trees.
 *
 * <h3>When to Use This Class</h3>
 * <p>This class builds a <b>complete in-memory tree</b> of the document. Use it only when:
 * <ul>
 *   <li>Full XPath navigation is required (parent/preceding/sibling axes)</li>
 *   <li>Multiple passes over the document are needed</li>
 *   <li>The document() or doc() XPath functions are called</li>
 *   <li>xsl:source-document with streamable="no" is used</li>
 * </ul>
 *
 * <h3>Prefer Streaming When Possible</h3>
 * <p>For large documents or memory-constrained environments, prefer streaming alternatives:
 * <ul>
 *   <li>{@link org.bluezoo.gonzalez.transform.ast.StreamNode xsl:stream} - Explicit streaming</li>
 *   <li>{@link org.bluezoo.gonzalez.transform.ast.SourceDocumentNode xsl:source-document} 
 *       with streamable="yes" (the Gonzalez default)</li>
 * </ul>
 *
 * <p>Documents loaded by this class are cached by their absolute URI (plus strip-space 
 * configuration) so that multiple requests for the same document return the same node tree.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class DocumentLoader {

    // Cache for loaded documents - keyed by absolute URI + strip-space rules
    private static final Map<String, XPathNode> documentCache = new ConcurrentHashMap<>();

    private DocumentLoader() {
        // Utility class - no instantiation
    }

    /**
     * Loads an XML document from a URI and returns its document node.
     * Results are cached so the same URI always returns the same document.
     *
     * @param uri the document URI (may be relative)
     * @param baseUri the base URI for resolving relative URIs (may be null)
     * @param stripSpace element patterns for strip-space (or null)
     * @param preserveSpace element patterns for preserve-space (or null)
     * @return the document node, or null if loading fails
     */
    public static XPathNode loadDocument(String uri, String baseUri,
            List<String> stripSpace, List<String> preserveSpace) {
        try {
            // Resolve the URI against the base URI
            URI resolved;
            if (baseUri != null && !baseUri.isEmpty()) {
                URI base = new URI(baseUri);
                resolved = base.resolve(uri);
            } else {
                resolved = new URI(uri);
            }
            
            String absoluteUri = resolved.toString();
            
            // Build cache key including strip-space rules
            String cacheKey = buildCacheKey(absoluteUri, stripSpace, preserveSpace);
            
            // Check cache
            XPathNode cached = documentCache.get(cacheKey);
            if (cached != null) {
                return cached;
            }
            
            // Parse the document
            URL url = resolved.toURL();
            SAXParserFactory factory = SAXParserFactory.newInstance();
            factory.setNamespaceAware(true);
            SAXParser parser = factory.newSAXParser();
            
            DocumentTreeBuilder builder = new DocumentTreeBuilder(absoluteUri, stripSpace, preserveSpace);
            try (InputStream in = url.openStream()) {
                InputSource source = new InputSource(in);
                source.setSystemId(absoluteUri);
                parser.parse(source, builder);
            }
            
            XPathNode root = builder.getRoot();
            
            // Cache the result
            if (root != null) {
                documentCache.put(cacheKey, root);
            }
            
            return root;
        } catch (Exception e) {
            // Return null on any error (caller decides whether to throw)
            return null;
        }
    }

    /**
     * Loads an XML document, throwing an exception if loading fails.
     *
     * @param uri the document URI (may be relative)
     * @param baseUri the base URI for resolving relative URIs (may be null)
     * @param stripSpace element patterns for strip-space (or null)
     * @param preserveSpace element patterns for preserve-space (or null)
     * @return the document node
     * @throws SAXException if the document cannot be loaded
     */
    public static XPathNode loadDocumentOrThrow(String uri, String baseUri,
            List<String> stripSpace, List<String> preserveSpace) throws SAXException {
        XPathNode doc = loadDocument(uri, baseUri, stripSpace, preserveSpace);
        if (doc == null) {
            throw new SAXException("FODC0002: Cannot retrieve document at " + uri);
        }
        return doc;
    }

    /**
     * Resolves a URI against a base URI.
     *
     * @param uri the URI to resolve
     * @param baseUri the base URI (may be null)
     * @return the resolved absolute URI
     * @throws SAXException if the URI is invalid
     */
    public static String resolveUri(String uri, String baseUri) throws SAXException {
        try {
            if (baseUri != null && !baseUri.isEmpty()) {
                URI base = new URI(baseUri);
                return base.resolve(uri).toString();
            }
            return new URI(uri).toString();
        } catch (Exception e) {
            throw new SAXException("FODC0002: Invalid URI: " + uri, e);
        }
    }

    /**
     * Clears the document cache.
     * Useful for testing or when memory needs to be freed.
     */
    public static void clearCache() {
        documentCache.clear();
    }

    private static String buildCacheKey(String absoluteUri, List<String> stripSpace, List<String> preserveSpace) {
        StringBuilder key = new StringBuilder(absoluteUri);
        if (stripSpace != null && !stripSpace.isEmpty()) {
            key.append("#strip=").append(stripSpace.hashCode());
        }
        if (preserveSpace != null && !preserveSpace.isEmpty()) {
            key.append("#preserve=").append(preserveSpace.hashCode());
        }
        return key.toString();
    }

    /**
     * SAX handler that builds a navigable node tree from a parsed XML document.
     */
    private static class DocumentTreeBuilder extends DefaultHandler {
        private final String baseUri;
        private final List<String> stripSpace;
        private final List<String> preserveSpace;
        private DocumentNode root;
        private DocumentNode current;
        private StringBuilder textBuffer = new StringBuilder();
        private int documentOrder = 0;
        private List<String[]> pendingNamespaces = new ArrayList<>();
        
        DocumentTreeBuilder(String baseUri, List<String> stripSpace, List<String> preserveSpace) {
            this.baseUri = baseUri;
            this.stripSpace = stripSpace;
            this.preserveSpace = preserveSpace;
        }
        
        XPathNode getRoot() {
            return root;
        }
        
        @Override
        public void startDocument() {
            root = new DocumentNode(NodeType.ROOT, null, null, null, baseUri);
            root.documentOrder = documentOrder++;
            current = root;
        }
        
        @Override
        public void startPrefixMapping(String prefix, String uri) {
            pendingNamespaces.add(new String[]{prefix, uri});
        }
        
        @Override
        public void endPrefixMapping(String prefix) {
            // Nothing needed
        }
        
        @Override
        public void startElement(String uri, String localName, String qName, Attributes attrs) {
            flushText();
            DocumentNode element = new DocumentNode(NodeType.ELEMENT, uri, localName, 
                qName.contains(":") ? qName.substring(0, qName.indexOf(':')) : null, baseUri);
            element.documentOrder = documentOrder++;
            element.parent = current;
            if (current != null) {
                current.addChild(element);
            }
            
            // Add namespace nodes from pending prefix mappings
            for (String[] ns : pendingNamespaces) {
                DocumentNode nsNode = new DocumentNode(NodeType.NAMESPACE, null, ns[0], null, baseUri);
                nsNode.documentOrder = documentOrder++;
                nsNode.value = ns[1];
                nsNode.parent = element;
                element.addNamespace(nsNode);
            }
            pendingNamespaces.clear();
            
            // Add attributes
            for (int i = 0; i < attrs.getLength(); i++) {
                DocumentNode attr = new DocumentNode(NodeType.ATTRIBUTE, 
                    attrs.getURI(i), attrs.getLocalName(i), null, baseUri);
                attr.documentOrder = documentOrder++;
                attr.value = attrs.getValue(i);
                attr.parent = element;
                element.addAttribute(attr);
            }
            
            current = element;
        }
        
        @Override
        public void endElement(String uri, String localName, String qName) {
            flushText();
            if (current != null && current.parent != null) {
                current = current.parent;
            }
        }
        
        @Override
        public void characters(char[] ch, int start, int length) {
            textBuffer.append(ch, start, length);
        }
        
        @Override
        public void processingInstruction(String target, String data) {
            flushText();
            DocumentNode pi = new DocumentNode(NodeType.PROCESSING_INSTRUCTION, null, target, null, baseUri);
            pi.documentOrder = documentOrder++;
            pi.value = data != null ? data : "";
            pi.parent = current;
            if (current != null) {
                current.addChild(pi);
            }
        }
        
        // Note: comment() is not from DefaultHandler - it's from LexicalHandler
        // This is called manually if LexicalHandler is implemented
        public void comment(char[] ch, int start, int length) {
            flushText();
            DocumentNode comment = new DocumentNode(NodeType.COMMENT, null, null, null, baseUri);
            comment.documentOrder = documentOrder++;
            comment.value = new String(ch, start, length);
            comment.parent = current;
            if (current != null) {
                current.addChild(comment);
            }
        }
        
        private void flushText() {
            if (textBuffer.length() > 0) {
                String text = textBuffer.toString();
                textBuffer.setLength(0);
                
                // Apply strip-space rules
                if (shouldStripText(text)) {
                    return;
                }
                
                DocumentNode textNode = new DocumentNode(NodeType.TEXT, null, null, null, baseUri);
                textNode.documentOrder = documentOrder++;
                textNode.value = text;
                textNode.parent = current;
                if (current != null) {
                    current.addChild(textNode);
                }
            }
        }
        
        private boolean shouldStripText(String text) {
            // Only consider stripping whitespace-only text
            if (!isWhitespace(text)) {
                return false;
            }
            
            // Get current element name for matching
            if (current == null || current.type != NodeType.ELEMENT) {
                return false;
            }
            
            String elementName = current.localName;
            String elementUri = current.namespaceURI;
            
            // Check preserve-space first (takes precedence)
            if (preserveSpace != null) {
                for (String pattern : preserveSpace) {
                    if (matchesPattern(elementUri, elementName, pattern)) {
                        return false; // Preserve this whitespace
                    }
                }
            }
            
            // Check strip-space
            if (stripSpace != null) {
                for (String pattern : stripSpace) {
                    if (matchesPattern(elementUri, elementName, pattern)) {
                        return true; // Strip this whitespace
                    }
                }
            }
            
            return false; // Default: preserve
        }
        
        private boolean isWhitespace(String text) {
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
                    return false;
                }
            }
            return true;
        }
        
        private boolean matchesPattern(String uri, String localName, String pattern) {
            if ("*".equals(pattern)) {
                return true;
            }
            // Simple name matching (TODO: support prefix:* patterns)
            return pattern.equals(localName);
        }
    }

    /**
     * Internal node class for building document trees.
     */
    public static class DocumentNode implements XPathNode {
        final NodeType type;
        final String namespaceURI;
        final String localName;
        final String prefix;
        final String baseUri;
        String value;
        DocumentNode parent;
        int documentOrder;
        private final List<DocumentNode> children = new ArrayList<>();
        private final List<DocumentNode> attributes = new ArrayList<>();
        private final List<DocumentNode> namespaces = new ArrayList<>();
        
        DocumentNode(NodeType type, String namespaceURI, String localName, String prefix, String baseUri) {
            this.type = type;
            this.namespaceURI = namespaceURI;
            this.localName = localName;
            this.prefix = prefix;
            this.baseUri = baseUri;
        }
        
        void addChild(DocumentNode child) {
            children.add(child);
        }
        
        void addAttribute(DocumentNode attr) {
            attributes.add(attr);
        }
        
        void addNamespace(DocumentNode ns) {
            namespaces.add(ns);
        }
        
        @Override public NodeType getNodeType() { return type; }
        @Override public String getNamespaceURI() { return namespaceURI; }
        @Override public String getLocalName() { return localName; }
        @Override public String getPrefix() { return prefix; }
        
        @Override 
        public String getStringValue() {
            if (type == NodeType.ROOT || type == NodeType.ELEMENT) {
                StringBuilder sb = new StringBuilder();
                appendTextContent(sb);
                return sb.toString();
            }
            return value != null ? value : "";
        }
        
        private void appendTextContent(StringBuilder sb) {
            if (type == NodeType.TEXT) {
                sb.append(value);
            } else {
                for (DocumentNode child : children) {
                    child.appendTextContent(sb);
                }
            }
        }
        
        @Override public XPathNode getParent() { return parent; }
        
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
            // Return namespaces declared on this element and inherited from ancestors
            List<XPathNode> result = new ArrayList<>();
            collectNamespaces(result, new ArrayList<>());
            return result.iterator();
        }
        
        private void collectNamespaces(List<XPathNode> result, List<String> seenPrefixes) {
            for (DocumentNode ns : namespaces) {
                String nsPrefix = ns.localName != null ? ns.localName : "";
                if (!seenPrefixes.contains(nsPrefix)) {
                    result.add(ns);
                    seenPrefixes.add(nsPrefix);
                }
            }
            if (parent != null && parent.type == NodeType.ELEMENT) {
                parent.collectNamespaces(result, seenPrefixes);
            }
        }
        
        @Override
        public XPathNode getFollowingSibling() {
            if (parent == null) {
                return null;
            }
            int index = parent.children.indexOf(this);
            if (index >= 0 && index < parent.children.size() - 1) {
                return parent.children.get(index + 1);
            }
            return null;
        }
        
        @Override
        public XPathNode getPrecedingSibling() {
            if (parent == null) {
                return null;
            }
            int index = parent.children.indexOf(this);
            if (index > 0) {
                return parent.children.get(index - 1);
            }
            return null;
        }
        
        @Override
        public long getDocumentOrder() {
            return documentOrder;
        }
        
        @Override
        public boolean isSameNode(XPathNode other) {
            return this == other;
        }
        
        @Override
        public XPathNode getRoot() {
            DocumentNode n = this;
            while (n.parent != null) {
                n = n.parent;
            }
            return n;
        }
        
        @Override
        public boolean isFullyNavigable() {
            return true;
        }

        @Override
        public boolean isAttribute() {
            return type == NodeType.ATTRIBUTE;
        }

        @Override
        public String getTypeNamespaceURI() {
            return null; // No type annotation
        }

        @Override
        public String getTypeLocalName() {
            return null; // No type annotation
        }
    }
}

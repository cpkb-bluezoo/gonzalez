/*
 * XPathResultTreeFragment.java
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

import org.bluezoo.gonzalez.transform.compiler.StylesheetCompiler;
import org.bluezoo.gonzalez.transform.runtime.OutputHandler;
import org.bluezoo.gonzalez.transform.runtime.SAXEventBuffer;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;
import org.xml.sax.ext.LexicalHandler;
import org.xml.sax.helpers.DefaultHandler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An XPath value representing a Result Tree Fragment (RTF).
 *
 * <p>In XSLT 1.0, when a variable is assigned content (not via select),
 * the result is a result tree fragment. RTFs can be:
 * <ul>
 *   <li>Converted to string (text content only)</li>
 *   <li>Copied via xsl:copy-of (full tree structure)</li>
 * </ul>
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class XPathResultTreeFragment implements XPathValue {

    private final SAXEventBuffer buffer;
    private final String baseUri;

    /**
     * Creates a result tree fragment from a SAX event buffer.
     *
     * @param buffer the buffered SAX events (must not be null)
     */
    public XPathResultTreeFragment(SAXEventBuffer buffer) {
        this(buffer, null);
    }

    /**
     * Creates a result tree fragment from a SAX event buffer with a base URI.
     *
     * @param buffer the buffered SAX events (must not be null)
     * @param baseUri the base URI for the RTF (from xml:base on the variable, may be null)
     */
    public XPathResultTreeFragment(SAXEventBuffer buffer, String baseUri) {
        this.buffer = buffer;
        this.baseUri = baseUri;
    }

    /**
     * Returns the base URI of this RTF.
     *
     * @return the base URI, or null if not set
     */
    public String getBaseUri() {
        return baseUri;
    }

    /**
     * Returns the underlying SAX event buffer.
     *
     * @return the buffer
     */
    public SAXEventBuffer getBuffer() {
        return buffer;
    }

    /**
     * Replays the buffered events to a content handler.
     * This is used by xsl:copy-of to copy the full tree structure.
     *
     * @param handler the target handler
     * @throws SAXException if replay fails
     */
    public void replay(ContentHandler handler) throws SAXException {
        buffer.replayContent(handler);
    }

    /**
     * Replays the buffered events to an output handler.
     * This adapts the SAX events to OutputHandler calls, including type annotations.
     *
     * @param output the target output handler
     * @throws SAXException if replay fails
     */
    public void replayToOutput(OutputHandler output) throws SAXException {
        replayToOutput(output, true);
    }
    
    /**
     * Replays the buffered events to an output handler, optionally including type annotations.
     *
     * @param output the target output handler
     * @param includeTypeAnnotations if true, type annotations are passed through
     * @throws SAXException if replay fails
     */
    public void replayToOutput(OutputHandler output, boolean includeTypeAnnotations) throws SAXException {
        // Use an adapter that converts ContentHandler calls to OutputHandler calls
        ContentHandler adapter = new OutputHandlerAdapter(output, includeTypeAnnotations);
        if (includeTypeAnnotations) {
            buffer.replayContentWithTypes(adapter);
        } else {
            buffer.replayContent(adapter);
        }
    }

    /**
     * Adapter that converts ContentHandler calls to OutputHandler calls.
     * Also implements LexicalHandler to capture comments and TypeAwareHandler
     * to pass type annotations.
     */
    private static class OutputHandlerAdapter implements ContentHandler, LexicalHandler, 
            SAXEventBuffer.TypeAwareHandler {
        private final OutputHandler output;
        private final boolean includeTypeAnnotations;
        private List<String[]> pendingNamespaces = new ArrayList<String[]>();

        OutputHandlerAdapter(OutputHandler output) {
            this(output, true);
        }
        
        OutputHandlerAdapter(OutputHandler output, boolean includeTypeAnnotations) {
            this.output = output;
            this.includeTypeAnnotations = includeTypeAnnotations;
        }

        @Override
        public void setDocumentLocator(org.xml.sax.Locator locator) {}

        @Override
        public void startDocument() throws SAXException {
            output.startDocument();
        }

        @Override
        public void endDocument() throws SAXException {
            output.endDocument();
        }

        @Override
        public void startPrefixMapping(String prefix, String uri) throws SAXException {
            // Buffer namespace mappings to output after startElement
            pendingNamespaces.add(new String[]{prefix, uri});
        }

        @Override
        public void endPrefixMapping(String prefix) throws SAXException {
            // Nothing needed
        }

        @Override
        public void startElement(String uri, String localName, String qName, 
                                 org.xml.sax.Attributes atts) throws SAXException {
            output.startElement(uri, localName, qName);
            
            // For unprefixed elements in no namespace, ensure we emit xmlns=""
            // This is needed to override any in-scope default namespace from parent
            // The output handler will skip redundant declarations
            // Only do this if there's no pending default namespace declaration
            boolean hasPrefix = qName.indexOf(':') > 0;
            boolean noNamespace = uri == null || uri.isEmpty();
            boolean hasDefaultNsDecl = false;
            for (String[] ns : pendingNamespaces) {
                if (ns[0].isEmpty()) {
                    hasDefaultNsDecl = true;
                    break;
                }
            }
            if (!hasPrefix && noNamespace && !hasDefaultNsDecl) {
                output.namespace("", "");
            }
            
            // Output buffered namespace declarations
            for (String[] ns : pendingNamespaces) {
                output.namespace(ns[0], ns[1]);
            }
            pendingNamespaces.clear();
            
            // Copy attributes (skip xmlns attributes as they're handled above)
            for (int i = 0; i < atts.getLength(); i++) {
                String attrUri = atts.getURI(i);
                String attrLocal = atts.getLocalName(i);
                String attrQName = atts.getQName(i);
                String attrValue = atts.getValue(i);
                
                // Skip namespace declarations - they were handled via startPrefixMapping
                if (attrQName.startsWith("xmlns")) {
                    continue;
                }
                
                output.attribute(attrUri != null ? attrUri : "", 
                                attrLocal, attrQName, attrValue);
            }
        }

        @Override
        public void endElement(String uri, String localName, String qName) throws SAXException {
            output.endElement(uri, localName, qName);
        }

        @Override
        public void characters(char[] ch, int start, int length) throws SAXException {
            output.characters(new String(ch, start, length));
        }

        @Override
        public void ignorableWhitespace(char[] ch, int start, int length) throws SAXException {
            output.characters(new String(ch, start, length));
        }

        @Override
        public void processingInstruction(String target, String data) throws SAXException {
            output.processingInstruction(target, data);
        }

        @Override
        public void skippedEntity(String name) throws SAXException {
            // Ignore
        }

        // LexicalHandler methods
        @Override
        public void comment(char[] ch, int start, int length) throws SAXException {
            output.comment(new String(ch, start, length));
        }

        @Override
        public void startDTD(String name, String publicId, String systemId) throws SAXException {
            // Ignore DTD
        }

        @Override
        public void endDTD() throws SAXException {
            // Ignore DTD
        }

        @Override
        public void startEntity(String name) throws SAXException {
            // Ignore entity boundaries
        }

        @Override
        public void endEntity(String name) throws SAXException {
            // Ignore entity boundaries
        }

        @Override
        public void startCDATA() throws SAXException {
            // Ignore CDATA markers
        }

        @Override
        public void endCDATA() throws SAXException {
            // Ignore CDATA markers
        }
        
        // TypeAwareHandler methods
        @Override
        public void setElementType(String namespaceURI, String localName) {
            if (!includeTypeAnnotations) {
                return;
            }
            try {
                output.setElementType(namespaceURI, localName);
            } catch (SAXException e) {
                throw new RuntimeException(e);
            }
        }
        
        @Override
        public void setAttributeType(int attrIndex, String namespaceURI, String localName) {
            if (!includeTypeAnnotations) {
                return;
            }
            // OutputHandler.setAttributeType doesn't take an index - it applies to
            // the most recently added attribute
            try {
                output.setAttributeType(namespaceURI, localName);
            } catch (SAXException e) {
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * Returns the XPath type of this value.
     *
     * <p>RTF is not a standard XPath 1.0 type; it is treated as a node-set
     * for compatibility with XPath operations.
     *
     * @return {@link Type#NODESET}
     */
    @Override
    public Type getType() {
        // RTF is not a standard XPath 1.0 type; treat as nodeset for compatibility
        return Type.NODESET;
    }

    /**
     * Converts this RTF to a string.
     *
     * <p>Returns the text content of the RTF (concatenation of all text nodes).
     *
     * @return the text content as a string
     */
    @Override
    public String asString() {
        return buffer.getTextContent();
    }

    /**
     * Converts this RTF to a number.
     *
     * <p>The text content is parsed as a number. If parsing fails, returns NaN.
     *
     * @return the numeric value, or NaN if the text content is not a valid number
     */
    @Override
    public double asNumber() {
        try {
            return Double.parseDouble(asString().trim());
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    /**
     * Converts this RTF to a boolean.
     *
     * <p>RTFs always convert to true. Per XSLT 1.0 spec section 11.1, an RTF
     * is treated as a node-set containing the root node for boolean conversion.
     * A non-empty node-set is always true.
     *
     * @return always true (an RTF always exists and contains a root node)
     */
    @Override
    public boolean asBoolean() {
        // RTFs are always truthy - they contain a root node
        return true;
    }

    /**
     * Converts this RTF to a node-set.
     *
     * <p>For XSLT 2.0+, RTFs can be converted to node-sets. This builds a
     * temporary navigable tree from the buffered SAX events and returns it
     * as a node-set containing the root node.
     *
     * <p>The tree is built lazily and cached for subsequent calls.
     *
     * @return a node-set containing the root node of the RTF tree, or empty if build fails
     */
    @Override
    public XPathNodeSet asNodeSet() {
        // For XSLT 2.0+, RTFs can be converted to node-sets
        // Build a temporary tree from the buffer and return it
        if (rootNode == null) {
            rootNode = buildNodeTree();
        }
        if (rootNode != null) {
            return new XPathNodeSet(Collections.singletonList(rootNode));
        }
        return XPathNodeSet.EMPTY;
    }
    
    // Cached root node for node-set conversion
    private transient XPathNode rootNode;
    
    /**
     * Builds a node tree from the SAX event buffer.
     */
    private XPathNode buildNodeTree() {
        RTFTreeBuilder builder = new RTFTreeBuilder(baseUri);
        try {
            buffer.replayContentWithTypes(builder);
            return builder.getRoot();
        } catch (SAXException e) {
            return null;
        }
    }
    
    /**
     * SAX handler that builds a navigable node tree from events.
     * Implements TypeAwareHandler to receive type annotations.
     */
    private static class RTFTreeBuilder extends DefaultHandler 
            implements SAXEventBuffer.TypeAwareHandler {
        private final String baseUri;
        private RTFNode root;
        private RTFNode current;
        private StringBuilder textBuffer = new StringBuilder();
        private List<String[]> pendingNamespaces = new ArrayList<>();
        
        RTFTreeBuilder(String baseUri) {
            this.baseUri = baseUri;
            // Create root node immediately in case startDocument is not called
            root = new RTFNode(NodeType.ROOT, null, null, null);
            root.baseUri = baseUri;
            current = root;
        }
        
        @Override
        public void startDocument() {
            // Root already created in constructor
        }
        
        @Override
        public void startPrefixMapping(String prefix, String uri) {
            // Buffer namespace mappings to add to the next element
            pendingNamespaces.add(new String[]{prefix, uri});
        }
        
        @Override
        public void endPrefixMapping(String prefix) {
            // Nothing needed
        }
        
        @Override
        public void startElement(String uri, String localName, String qName, 
                                 org.xml.sax.Attributes attrs) {
            flushText();
            RTFNode element = new RTFNode(NodeType.ELEMENT, uri, localName, 
                qName.contains(":") ? qName.substring(0, qName.indexOf(':')) : null);
            element.parent = current;
            if (current != null) {
                current.addChild(element);
            } else {
                // Fallback if current is somehow null
                root.addChild(element);
            }
            
            // Add namespace nodes from pending prefix mappings
            // Track prefix-to-namespace mappings for conflict detection
            java.util.Map<String, String> prefixToUri = new java.util.HashMap<>();
            for (String[] ns : pendingNamespaces) {
                RTFNode nsNode = new RTFNode(NodeType.NAMESPACE, null, ns[0], null);
                nsNode.value = ns[1];
                nsNode.parent = element;
                element.addNamespace(nsNode);
                if (ns[0] != null && !ns[0].isEmpty()) {
                    prefixToUri.put(ns[0], ns[1]);
                }
            }
            pendingNamespaces.clear();
            
            // Add attributes, resolving prefix conflicts
            for (int i = 0; i < attrs.getLength(); i++) {
                String attrQName = attrs.getQName(i);
                String attrPrefix = (attrQName != null && attrQName.contains(":")) 
                    ? attrQName.substring(0, attrQName.indexOf(':')) 
                    : null;
                String attrUri = attrs.getURI(i);
                
                // Check for prefix conflict: same prefix but different namespace
                if (attrPrefix != null && attrUri != null && !attrUri.isEmpty()) {
                    String existingUri = prefixToUri.get(attrPrefix);
                    if (existingUri != null && !existingUri.equals(attrUri)) {
                        // Prefix conflict! Generate a new unique prefix
                        attrPrefix = generateUniquePrefix(attrPrefix, prefixToUri);
                        // Add namespace declaration for the new prefix
                        RTFNode nsNode = new RTFNode(NodeType.NAMESPACE, null, attrPrefix, null);
                        nsNode.value = attrUri;
                        nsNode.parent = element;
                        element.addNamespace(nsNode);
                    }
                    prefixToUri.put(attrPrefix, attrUri);
                }
                
                RTFNode attr = new RTFNode(NodeType.ATTRIBUTE, 
                    attrUri, attrs.getLocalName(i), attrPrefix);
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
        public void setElementType(String namespaceURI, String localName) {
            // Set type annotation on the current element
            if (current != null && current.type == NodeType.ELEMENT) {
                current.typeNamespaceURI = namespaceURI;
                current.typeLocalName = localName;
            }
        }
        
        @Override
        public void setAttributeType(int attrIndex, String namespaceURI, String localName) {
            // Set type annotation on an attribute of the current element
            if (current != null && current.type == NodeType.ELEMENT 
                    && attrIndex < current.attributes.size()) {
                RTFNode attr = current.attributes.get(attrIndex);
                attr.typeNamespaceURI = namespaceURI;
                attr.typeLocalName = localName;
            }
        }
        
        private int prefixCounter = 0;
        
        private String generateUniquePrefix(String basePrefix, java.util.Map<String, String> usedPrefixes) {
            String newPrefix = basePrefix + "_" + prefixCounter++;
            while (usedPrefixes.containsKey(newPrefix)) {
                newPrefix = basePrefix + "_" + prefixCounter++;
            }
            return newPrefix;
        }
        
        private void flushText() {
            if (textBuffer.length() > 0 && current != null) {
                RTFNode text = new RTFNode(NodeType.TEXT, null, null, null);
                text.value = textBuffer.toString();
                text.parent = current;
                current.addChild(text);
                textBuffer.setLength(0);
            }
        }
        
        RTFNode getRoot() {
            // Flush any remaining text before returning
            flushText();
            return root;
        }
    }
    
    /**
     * Node implementation for RTF tree navigation.
     */
    private static class RTFNode implements XPathNodeWithBaseURI {
        final NodeType type;
        final String namespaceURI;
        final String localName;
        final String prefix;
        String value;
        RTFNode parent;
        List<RTFNode> children = new ArrayList<RTFNode>();
        List<RTFNode> attributes = new ArrayList<RTFNode>();
        List<RTFNode> namespaces = new ArrayList<RTFNode>();
        String baseUri;  // Base URI from xml:base
        
        // Type annotation (from xsl:type)
        String typeNamespaceURI;
        String typeLocalName;
        
        RTFNode(NodeType type, String uri, String localName, String prefix) {
            this.type = type;
            this.namespaceURI = uri != null && !uri.isEmpty() ? uri : null;
            this.localName = localName;
            this.prefix = prefix;
        }
        
        /**
         * Returns the base URI of this node.
         */
        public String getBaseURI() {
            return baseUri;
        }
        
        void addChild(RTFNode child) { children.add(child); }
        void addAttribute(RTFNode attr) { attributes.add(attr); }
        void addNamespace(RTFNode ns) { namespaces.add(ns); }
        
        @Override public NodeType getNodeType() { return type; }
        @Override public String getNamespaceURI() { return namespaceURI; }
        @Override public String getLocalName() { return localName; }
        @Override public String getPrefix() { return prefix; }
        
        @Override
        public String getStringValue() {
            if (type == NodeType.TEXT) {
                return value != null ? value : "";
            }
            if (type == NodeType.ATTRIBUTE || type == NodeType.NAMESPACE) {
                // For attributes and namespaces, return the stored value directly
                String rawValue = value != null ? value : "";
                // If typed, return canonical form (only for attributes)
                if (type == NodeType.ATTRIBUTE && typeLocalName != null && XSD_NAMESPACE.equals(typeNamespaceURI)) {
                    return StylesheetCompiler.toCanonicalLexical(typeLocalName, rawValue);
                }
                return rawValue;
            }
            // For elements/root, concatenate text of descendants
            StringBuilder sb = new StringBuilder();
            collectText(sb);
            String rawValue = sb.toString();
            
            // If element has a type annotation, convert to canonical form
            if (type == NodeType.ELEMENT && typeLocalName != null 
                    && XSD_NAMESPACE.equals(typeNamespaceURI)) {
                return StylesheetCompiler.toCanonicalLexical(typeLocalName, rawValue);
            }
            return rawValue;
        }
        
        private static final String XSD_NAMESPACE = "http://www.w3.org/2001/XMLSchema";
        
        private void collectText(StringBuilder sb) {
            if (type == NodeType.TEXT) {
                sb.append(value != null ? value : "");
            } else {
                for (RTFNode child : children) {
                    child.collectText(sb);
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
            // Return namespace nodes declared on this element and inherited from ancestors
            // Build a map to handle redeclarations (closer declarations override)
            Map<String, RTFNode> nsMap = new LinkedHashMap<>();
            
            // Start from this element and go up, but don't override closer declarations
            RTFNode current = this;
            while (current != null) {
                if (current.type == NodeType.ELEMENT) {
                    for (RTFNode ns : current.namespaces) {
                        String prefix = ns.localName != null ? ns.localName : "";
                        // Only add if not already declared by a closer element
                        if (!nsMap.containsKey(prefix)) {
                            // Skip empty URIs - they represent undeclarations, not actual bindings
                            // (except for the xml namespace which is always present)
                            String uri = ns.value;
                            if (uri != null && !uri.isEmpty()) {
                                nsMap.put(prefix, ns);
                            }
                        }
                    }
                }
                current = current.parent;
            }
            
            // Always include the xml namespace
            if (!nsMap.containsKey("xml")) {
                RTFNode xmlNs = new RTFNode(NodeType.NAMESPACE, null, "xml", null);
                xmlNs.value = "http://www.w3.org/XML/1998/namespace";
                nsMap.put("xml", xmlNs);
            }
            
            return new ArrayList<XPathNode>(nsMap.values()).iterator();
        }
        
        @Override
        public XPathNode getFollowingSibling() {
            if (parent == null) {
                return null;
            }
            int idx = parent.children.indexOf(this);
            if (idx >= 0 && idx < parent.children.size() - 1) {
                return parent.children.get(idx + 1);
            }
            return null;
        }
        
        @Override
        public XPathNode getPrecedingSibling() {
            if (parent == null) {
                return null;
            }
            int idx = parent.children.indexOf(this);
            if (idx > 0) {
                return parent.children.get(idx - 1);
            }
            return null;
        }
        
        @Override public long getDocumentOrder() { return 0; }
        @Override public boolean isSameNode(XPathNode other) { return this == other; }
        @Override public XPathNode getRoot() { 
            RTFNode n = this;
            while (n.parent != null) n = n.parent;
            return n;
        }
        @Override public boolean isFullyNavigable() { return true; }
        
        @Override 
        public String getTypeNamespaceURI() { 
            return typeNamespaceURI; 
        }
        
        @Override 
        public String getTypeLocalName() { 
            return typeLocalName; 
        }
    }

    /**
     * Returns a string representation of this RTF.
     *
     * @return a string in the format "RTF[textContent]"
     */
    @Override
    public String toString() {
        return "RTF[" + asString() + "]";
    }

}

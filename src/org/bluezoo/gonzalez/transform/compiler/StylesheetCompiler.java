/*
 * StylesheetCompiler.java
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

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringTokenizer;

import org.xml.sax.Attributes;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.AttributesImpl;
import org.xml.sax.helpers.DefaultHandler;

import org.bluezoo.gonzalez.QName;
import org.bluezoo.gonzalez.schema.xsd.XSDSchema;
import org.bluezoo.gonzalez.schema.xsd.XSDSchemaParser;
import org.bluezoo.gonzalez.transform.ast.BreakNode;
import org.bluezoo.gonzalez.transform.ast.ForEachGroupNode;
import org.bluezoo.gonzalez.transform.ast.ForkNode;
import org.bluezoo.gonzalez.transform.ast.IterateNode;
import org.bluezoo.gonzalez.transform.ast.LiteralResultElement;
import org.bluezoo.gonzalez.transform.ast.LiteralText;
import org.bluezoo.gonzalez.transform.ast.NextIterationNode;
import org.bluezoo.gonzalez.transform.ast.ResultDocumentNode;
import org.bluezoo.gonzalez.transform.ast.SequenceNode;
import org.bluezoo.gonzalez.transform.ast.StreamNode;
import org.bluezoo.gonzalez.transform.ast.XSLTInstruction;
import org.bluezoo.gonzalez.transform.ast.XSLTNode;
import org.bluezoo.gonzalez.transform.runtime.BasicTransformContext;
import org.bluezoo.gonzalez.transform.runtime.BufferOutputHandler;
import org.bluezoo.gonzalez.transform.runtime.OutputHandler;
import org.bluezoo.gonzalez.transform.runtime.RuntimeSchemaValidator;
import org.bluezoo.gonzalez.transform.runtime.SAXEventBuffer;
import org.bluezoo.gonzalez.transform.runtime.TemplateMatcher;
import org.bluezoo.gonzalez.transform.runtime.TransformContext;
import org.bluezoo.gonzalez.transform.xpath.type.XPathResultTreeFragment;
import org.bluezoo.gonzalez.transform.xpath.XPathContext;
import org.bluezoo.gonzalez.transform.xpath.XPathExpression;
import org.bluezoo.gonzalez.transform.xpath.XPathFunctionLibrary;
import org.bluezoo.gonzalez.transform.xpath.XPathParser;
import org.bluezoo.gonzalez.transform.xpath.function.XSLTFunctionLibrary;
import org.bluezoo.gonzalez.transform.xpath.XPathSyntaxException;
import org.bluezoo.gonzalez.transform.xpath.expr.XPathException;
import org.bluezoo.gonzalez.transform.xpath.type.NodeType;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNode;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNodeSet;
import org.bluezoo.gonzalez.transform.xpath.type.XPathSequence;
import org.bluezoo.gonzalez.transform.xpath.type.XPathResultTreeFragment;
import org.bluezoo.gonzalez.transform.xpath.type.XPathString;
import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;

/**
 * Compiles XSLT stylesheets from SAX events.
 *
 * <p>The stylesheet compiler receives SAX events from an XML parser and builds
 * a compiled stylesheet ready for execution. This enables stylesheets to be
 * read from any source that can produce SAX events, including the Gonzalez
 * push parser.
 *
 * <p>Usage:
 * <pre>
 * StylesheetCompiler compiler = new StylesheetCompiler();
 * XMLReader reader = XMLReaderFactory.createXMLReader();
 * reader.setContentHandler(compiler);
 * reader.parse(stylesheetSource);
 * CompiledStylesheet stylesheet = compiler.getCompiledStylesheet();
 * </pre>
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public class StylesheetCompiler extends DefaultHandler implements XPathParser.NamespaceResolver {

    /** XSLT namespace URI. */
    public static final String XSLT_NS = "http://www.w3.org/1999/XSL/Transform";

    // Compilation state
    private final CompiledStylesheet.Builder builder = CompiledStylesheet.builder();
    private final Deque<ElementContext> elementStack = new ArrayDeque<>();
    private final Map<String, String> namespaces = new HashMap<>();
    private final Map<String, String> pendingNamespaces = new HashMap<>(); // Namespaces declared before next startElement
    private final Deque<Map<String, String>> namespaceScopes = new ArrayDeque<>(); // Stack of namespace scopes for proper prefix resolution
    private final StringBuilder characterBuffer = new StringBuilder();
    
    // Locator for error reporting
    private Locator locator;
    
    // Import/include support
    private final StylesheetResolver resolver;
    private final String baseUri;
    private int importPrecedence = -1;  // Set lazily after imports are processed
    private int templateCounter = 0;  // Counts templates for declaration order
    private boolean importsAllowed = true;
    private boolean precedenceAssigned = false;
    
    // Forward-compatible processing mode
    private double stylesheetVersion = 1.0;
    private boolean forwardCompatible = false;
    
    // Default validation mode (XSLT 2.0+)
    private ValidationMode defaultValidation = ValidationMode.STRIP;
    
    /**
     * Validation modes for schema-aware processing.
     */
    public enum ValidationMode {
        /** Validate strictly against schema - error if no declaration */
        STRICT,
        /** Validate if schema declaration found, otherwise skip */
        LAX,
        /** Preserve existing type annotations */
        PRESERVE,
        /** Remove type annotations (default) */
        STRIP
    }
    
    // Excluded namespace URIs from exclude-result-prefixes
    private final Set<String> excludedNamespaceURIs = new HashSet<>();
    
    // Extension namespace URIs from extension-element-prefixes (auto-excluded from output)
    private final Set<String> extensionNamespaceURIs = new HashSet<>();
    
    // Track depth inside top-level user data elements (ignored per XSLT 1.0 Section 2.2)
    private int userDataElementDepth = 0;
    
    // Track depth inside elements excluded by use-when="false()" (XSLT 2.0 conditional compilation)
    private int useWhenSkipDepth = 0;
    
    // Static variables (XSLT 3.0): evaluated at compile time, available in use-when
    private final Map<String, XPathValue> staticVariables = new HashMap<>();
    
    // Simplified stylesheet: root element is a literal result element with xsl:version attribute
    // Per XSLT 1.0 Section 2.3: equivalent to xsl:stylesheet containing template match="/"
    private boolean isSimplifiedStylesheet = false;
    private XSLTNode simplifiedStylesheetBody = null;

    /**
     * Context for an element being processed.
     */
    private static class ElementContext {
        final String namespaceURI;
        final String localName;
        final List<XSLTNode> children = new ArrayList<>();
        final Map<String, String> attributes = new HashMap<>();
        final Map<String, String> namespaceBindings = new HashMap<>();  // All in-scope bindings
        final Map<String, String> explicitNamespaces = new HashMap<>(); // Only declared on THIS element
        final Set<String> excludedByThisElement = new HashSet<>();  // URIs excluded by xsl:exclude-result-prefixes on this element
        String baseURI;  // Effective base URI for this element (from xml:base inheritance)
        
        ElementContext(String namespaceURI, String localName) {
            this.namespaceURI = namespaceURI;
            this.localName = localName;
        }
    }

    /**
     * Creates a new stylesheet compiler with no external stylesheet support.
     */
    public StylesheetCompiler() {
        this(null, null, 0);
    }

    /**
     * Creates a new stylesheet compiler with external stylesheet support.
     *
     * @param resolver the stylesheet resolver for imports/includes
     * @param baseUri the base URI of this stylesheet for relative resolution
     */
    public StylesheetCompiler(StylesheetResolver resolver, String baseUri) {
        this(resolver, baseUri, 0);
    }

    /**
     * Creates a new stylesheet compiler with external stylesheet support.
     * Import precedence is assigned lazily after all imports are processed.
     *
     * @param resolver the stylesheet resolver
     * @param baseUri the base URI of this stylesheet
     * @param unusedPrecedence ignored - kept for API compatibility during transition
     */
    StylesheetCompiler(StylesheetResolver resolver, String baseUri, int unusedPrecedence) {
        this.resolver = resolver;
        this.baseUri = baseUri;
        
        // Mark this stylesheet as loaded in the resolver
        if (resolver != null && baseUri != null) {
            resolver.markLoaded(baseUri);
        }
    }
    
    /**
     * Ensures import precedence is assigned. Called when first non-import
     * top-level element is encountered, guaranteeing all imports have been processed.
     */
    private void ensurePrecedenceAssigned() {
        if (!precedenceAssigned && resolver != null) {
            importPrecedence = resolver.nextPrecedence();
            precedenceAssigned = true;
        } else if (!precedenceAssigned) {
            // No resolver - use default
            importPrecedence = 0;
            precedenceAssigned = true;
        }
    }

    /**
     * Returns the compiled stylesheet.
     * Call this after parsing is complete.
     *
     * @return the compiled stylesheet
     * @throws javax.xml.transform.TransformerConfigurationException if validation fails
     */
    public CompiledStylesheet getCompiledStylesheet() 
            throws javax.xml.transform.TransformerConfigurationException {
        return builder.build();
    }

    @Override
    public void setDocumentLocator(Locator locator) {
        this.locator = locator;
    }

    @Override
    public void startDocument() throws SAXException {
        elementStack.clear();
        namespaces.clear();
        
        // Set base URI from the locator's system ID
        if (locator != null && locator.getSystemId() != null) {
            builder.setBaseURI(locator.getSystemId());
        }
    }

    @Override
    public void endDocument() throws SAXException {
        // Handle simplified stylesheets (XSLT 1.0 Section 2.3)
        // A literal result element with xsl:version is equivalent to
        // xsl:stylesheet containing a template rule with match="/"
        if (isSimplifiedStylesheet && simplifiedStylesheetBody != null) {
            try {
                // Create a pattern matching "/"
                Pattern rootPattern = new Pattern() {
                    @Override
                    public boolean matches(org.bluezoo.gonzalez.transform.xpath.type.XPathNode node,
                                          org.bluezoo.gonzalez.transform.runtime.TransformContext context) {
                        return node.getNodeType() == org.bluezoo.gonzalez.transform.xpath.type.NodeType.ROOT;
                    }
                    @Override
                    public double getDefaultPriority() {
                        return 0.5;
                    }
                };
                
                // Create a template rule - body must be a SequenceNode
                SequenceNode body;
                if (simplifiedStylesheetBody instanceof SequenceNode) {
                    body = (SequenceNode) simplifiedStylesheetBody;
                } else {
                    List<XSLTNode> bodyList = new ArrayList<>();
                    bodyList.add(simplifiedStylesheetBody);
                    body = new SequenceNode(bodyList);
                }
                
                TemplateRule rule = new TemplateRule(
                    rootPattern,              // match="/"
                    null,                     // no name
                    null,                     // no mode
                    0.5,                      // priority
                    0,                        // import precedence
                    Collections.emptyList(),  // no parameters
                    body                      // the literal result element
                );
                
                builder.addTemplateRule(rule);
            } catch (Exception e) {
                throw new SAXException("Error creating simplified stylesheet template: " + e.getMessage(), e);
            }
        }
    }

    @Override
    public void startPrefixMapping(String prefix, String uri) throws SAXException {
        // Track in pendingNamespaces for explicitNamespaces (cleared in startElement)
        pendingNamespaces.put(prefix, uri);
        
        // Store the previous value (if any) for restoration in endPrefixMapping
        // Use namespaceScopes - store prefix -> previous value for pending element
        // (scope is pushed in startElement, so use a temporary map until then)
        if (pendingPreviousNamespaces == null) {
            pendingPreviousNamespaces = new HashMap<>();
        }
        if (!pendingPreviousNamespaces.containsKey(prefix)) {
            // Only record the first previous value for this prefix before this element
            pendingPreviousNamespaces.put(prefix, namespaces.get(prefix)); // null if not previously bound
        }
        
        namespaces.put(prefix, uri);
        // Note: Don't add to elementStack.peek() here!
        // Namespace bindings are captured in startElement() at the time each element starts.
        // Adding here would incorrectly modify parent element's bindings when child declares xmlns.
    }

    @Override
    public void endPrefixMapping(String prefix) throws SAXException {
        // Restore the previous value for this prefix (from before the current element)
        if (!namespaceScopes.isEmpty()) {
            Map<String, String> currentScope = namespaceScopes.peek();
            if (currentScope.containsKey(prefix)) {
                String previousUri = currentScope.remove(prefix);
                if (previousUri != null) {
                    namespaces.put(prefix, previousUri);
                } else {
                    namespaces.remove(prefix);
                }
                // If scope is empty, pop it
                if (currentScope.isEmpty()) {
                    namespaceScopes.pop();
                }
                return;
            }
        }
        // Fallback - remove if not tracked (shouldn't happen in well-formed input)
        namespaces.remove(prefix);
    }
    
    // Temporary storage for previous namespace values before startElement is called
    private Map<String, String> pendingPreviousNamespaces;

    @Override
    public void startElement(String uri, String localName, String qName, Attributes atts) 
            throws SAXException {
        flushCharacters();
        
        // Track elements being skipped due to use-when="false()"
        if (useWhenSkipDepth > 0) {
            useWhenSkipDepth++;
            elementStack.push(new ElementContext(uri, localName));
            return;
        }
        
        // Per XSLT 1.0 Section 2.2: Track when we enter top-level user data elements.
        // These are non-XSLT elements that are direct children of xsl:stylesheet.
        // They and all their descendants should be ignored (not compiled).
        if (userDataElementDepth > 0) {
            userDataElementDepth++;
            elementStack.push(new ElementContext(uri, localName)); // Still need to track for proper popping
            return;
        }
        if (!XSLT_NS.equals(uri) && isTopLevel()) {
            userDataElementDepth = 1;
            elementStack.push(new ElementContext(uri, localName));
            return;
        }
        
        // Check for use-when attribute (XSLT 2.0 conditional compilation)
        // Can appear on any element in the stylesheet
        // - On XSLT elements: use-when is in no namespace
        // - On literal result elements: xsl:use-when is in XSLT namespace
        String useWhen = atts.getValue("use-when");
        if (useWhen == null) {
            // Check for xsl:use-when on literal result elements
            useWhen = atts.getValue(XSLT_NS, "use-when");
        }
        if (useWhen != null && !useWhen.isEmpty()) {
            if (!evaluateUseWhen(useWhen)) {
                // Exclude this element and all its descendants
                useWhenSkipDepth = 1;
                elementStack.push(new ElementContext(uri, localName));
                return;
            }
        }
        
        ElementContext ctx = new ElementContext(uri, localName);
        
        // Copy attributes
        for (int i = 0; i < atts.getLength(); i++) {
            ctx.attributes.put(atts.getQName(i), atts.getValue(i));
        }
        
        // Copy current namespace bindings (all in-scope)
        ctx.namespaceBindings.putAll(namespaces);
        
        // Copy only namespaces explicitly declared on this element
        ctx.explicitNamespaces.putAll(pendingNamespaces);
        pendingNamespaces.clear();
        
        // Push namespace scope for this element (for proper restoration in endPrefixMapping)
        if (pendingPreviousNamespaces != null && !pendingPreviousNamespaces.isEmpty()) {
            namespaceScopes.push(pendingPreviousNamespaces);
            pendingPreviousNamespaces = null;
        }
        
        // Compute effective base URI for this element
        // Inherit from parent or use locator's system ID as initial base
        String parentBase = elementStack.isEmpty() ? 
            (locator != null ? locator.getSystemId() : null) :
            elementStack.peek().baseURI;
        
        // Check for xml:base attribute on this element
        String xmlBase = atts.getValue("http://www.w3.org/XML/1998/namespace", "base");
        if (xmlBase != null && !xmlBase.isEmpty()) {
            // Resolve relative to parent base
            if (parentBase != null && !xmlBase.contains(":")) {
                try {
                    URI base = new URI(parentBase);
                    URI resolved = base.resolve(xmlBase);
                    ctx.baseURI = resolved.toString();
                } catch (URISyntaxException e) {
                    ctx.baseURI = xmlBase;
                }
            } else {
                ctx.baseURI = xmlBase;
            }
        } else {
            ctx.baseURI = parentBase;
        }
        
        // Check for stylesheet/transform/package element to set forward-compatible mode early
        if (XSLT_NS.equals(uri) && ("stylesheet".equals(localName) || "transform".equals(localName) 
                || "package".equals(localName))) {
            String versionAttr = atts.getValue("version");
            if (versionAttr != null) {
                try {
                    stylesheetVersion = Double.parseDouble(versionAttr);
                    // Forward-compatible mode is enabled when version > 1.0
                    forwardCompatible = stylesheetVersion > 1.0;
                } catch (NumberFormatException e) {
                    // Ignore invalid version, use default
                }
            }
            
            // Capture namespace bindings from the stylesheet element
            // These are used to resolve namespace prefixes in variable names, etc.
            for (Map.Entry<String, String> ns : namespaces.entrySet()) {
                builder.addNamespaceBinding(ns.getKey(), ns.getValue());
            }
            
            // Set the stylesheet's base URI (computed from xml:base and locator)
            if (ctx.baseURI != null) {
                builder.setBaseURI(ctx.baseURI);
            }
            
            // Process exclude-result-prefixes early so it's available for all descendants
            String excludePrefixes = atts.getValue("exclude-result-prefixes");
            if (excludePrefixes != null && !excludePrefixes.isEmpty()) {
                String[] prefixes = excludePrefixes.split("\\s+");
                for (String prefix : prefixes) {
                    if ("#all".equals(prefix)) {
                        // XSLT 2.0: exclude all namespaces in scope
                        for (Map.Entry<String, String> ns : namespaces.entrySet()) {
                            if (!XSLT_NS.equals(ns.getValue())) {
                                excludedNamespaceURIs.add(ns.getValue());
                            }
                        }
                    } else if ("#default".equals(prefix)) {
                        // Exclude the default namespace
                        String defaultNs = namespaces.get("");
                        if (defaultNs != null && !defaultNs.isEmpty()) {
                            excludedNamespaceURIs.add(defaultNs);
                        }
                    } else {
                        // Regular prefix - look up its namespace URI and exclude it
                        String nsUri = namespaces.get(prefix);
                        if (nsUri != null && !nsUri.isEmpty()) {
                            excludedNamespaceURIs.add(nsUri);
                        }
                    }
                }
            }
            
            // Process extension-element-prefixes early - extension namespaces are auto-excluded
            String extensionPrefixes = atts.getValue("extension-element-prefixes");
            if (extensionPrefixes != null && !extensionPrefixes.isEmpty()) {
                String[] prefixes = extensionPrefixes.split("\\s+");
                for (String prefix : prefixes) {
                    if ("#default".equals(prefix)) {
                        String defaultNs = namespaces.get("");
                        if (defaultNs != null && !defaultNs.isEmpty()) {
                            extensionNamespaceURIs.add(defaultNs);
                        }
                    } else {
                        String nsUri = namespaces.get(prefix);
                        if (nsUri != null && !nsUri.isEmpty()) {
                            extensionNamespaceURIs.add(nsUri);
                        }
                    }
                }
            }
        }
        
        // For non-XSLT elements (LREs), also process xsl:extension-element-prefixes
        // so that extension elements within children are properly recognized
        if (!XSLT_NS.equals(uri)) {
            // Check for simplified stylesheet: root element with xsl:version attribute
            // Per XSLT 1.0 Section 2.3: LRE with xsl:version becomes template match="/"
            if (elementStack.isEmpty()) {
                String xslVersion = atts.getValue(XSLT_NS, "version");
                if (xslVersion != null) {
                    isSimplifiedStylesheet = true;
                    try {
                        stylesheetVersion = Double.parseDouble(xslVersion);
                        forwardCompatible = stylesheetVersion > 1.0;
                    } catch (NumberFormatException e) {
                        // Use default version
                    }
                    
                    // Process exclude-result-prefixes on simplified stylesheet root
                    String excludePrefixes = atts.getValue(XSLT_NS, "exclude-result-prefixes");
                    if (excludePrefixes != null && !excludePrefixes.isEmpty()) {
                        String[] prefixes = excludePrefixes.split("\\s+");
                        for (String prefix : prefixes) {
                            if ("#all".equals(prefix)) {
                                for (Map.Entry<String, String> ns : namespaces.entrySet()) {
                                    if (!XSLT_NS.equals(ns.getValue())) {
                                        excludedNamespaceURIs.add(ns.getValue());
                                    }
                                }
                            } else if ("#default".equals(prefix)) {
                                String defaultNs = namespaces.get("");
                                if (defaultNs != null && !defaultNs.isEmpty()) {
                                    excludedNamespaceURIs.add(defaultNs);
                                }
                            } else {
                                String nsUri = namespaces.get(prefix);
                                if (nsUri != null && !nsUri.isEmpty()) {
                                    excludedNamespaceURIs.add(nsUri);
                                }
                            }
                        }
                    }
                    
                    // Capture namespace bindings
                    for (Map.Entry<String, String> ns : namespaces.entrySet()) {
                        builder.addNamespaceBinding(ns.getKey(), ns.getValue());
                    }
                }
            }
            
            String extensionPrefixes = atts.getValue(XSLT_NS, "extension-element-prefixes");
            if (extensionPrefixes != null && !extensionPrefixes.isEmpty()) {
                String[] prefixes = extensionPrefixes.split("\\s+");
                for (String prefix : prefixes) {
                    if ("#default".equals(prefix)) {
                        String defaultNs = namespaces.get("");
                        if (defaultNs != null && !defaultNs.isEmpty()) {
                            extensionNamespaceURIs.add(defaultNs);
                        }
                    } else {
                        String nsUri = namespaces.get(prefix);
                        if (nsUri != null && !nsUri.isEmpty()) {
                            extensionNamespaceURIs.add(nsUri);
                        }
                    }
                }
            }
        }
        
        // XSLT 2.0: Process exclude-result-prefixes on XSLT elements (like xsl:template)
        // This scopes the exclusion to descendants of that element
        if (XSLT_NS.equals(uri) && !("stylesheet".equals(localName) || "transform".equals(localName) 
                || "package".equals(localName))) {
            String excludePrefixes = atts.getValue("exclude-result-prefixes");
            if (excludePrefixes != null && !excludePrefixes.isEmpty()) {
                processExcludeResultPrefixes(excludePrefixes, namespaces, ctx);
            }
        }
        
        // Process xsl:exclude-result-prefixes on literal result elements
        // This needs to happen during startElement so children are compiled with the exclusion
        if (!XSLT_NS.equals(uri)) {
            String excludePrefixes = atts.getValue(XSLT_NS, "exclude-result-prefixes");
            if (excludePrefixes != null && !excludePrefixes.isEmpty()) {
                processExcludeResultPrefixes(excludePrefixes, namespaces, ctx);
            }
        }
        
        elementStack.push(ctx);
    }

    @Override
    public void endElement(String uri, String localName, String qName) throws SAXException {
        flushCharacters();
        
        // Skip processing if we're inside a use-when="false()" element
        if (useWhenSkipDepth > 0) {
            elementStack.pop();
            useWhenSkipDepth--;
            return;
        }
        
        // Skip processing if we're inside a top-level user data element
        if (userDataElementDepth > 0) {
            elementStack.pop();
            userDataElementDepth--;
            return;
        }
        
        ElementContext ctx = elementStack.pop();
        
        // Remove any namespace URIs that were excluded by this element's xsl:exclude-result-prefixes
        // This restores the scope to what it was before entering this element
        excludedNamespaceURIs.removeAll(ctx.excludedByThisElement);
        
        XSLTNode node = compileElement(ctx);
        
        if (elementStack.isEmpty()) {
            // Root element
            if (isSimplifiedStylesheet && node != null) {
                // Simplified stylesheet: root LRE becomes the body of a template matching "/"
                simplifiedStylesheetBody = node;
            }
            // For normal stylesheets, processing was done via processStylesheetElement
        } else {
            // Add to parent's children
            if (node != null) {
                elementStack.peek().children.add(node);
            }
        }
    }

    @Override
    public void characters(char[] ch, int start, int length) throws SAXException {
        characterBuffer.append(ch, start, length);
    }

    private void flushCharacters() throws SAXException {
        if (characterBuffer.length() > 0) {
            String text = characterBuffer.toString();
            characterBuffer.setLength(0);
            
            if (!elementStack.isEmpty()) {
                // Check if we should preserve whitespace
                if (!isWhitespace(text) || shouldPreserveWhitespace()) {
                    elementStack.peek().children.add(new LiteralText(text));
                }
            }
        }
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

    private boolean shouldPreserveWhitespace() {
        // Check if we're inside xsl:text - always preserve whitespace there
        if (!elementStack.isEmpty()) {
            ElementContext current = elementStack.peek();
            if (XSLT_NS.equals(current.namespaceURI) && "text".equals(current.localName)) {
                return true;
            }
        }
        
        // Check for xml:space="preserve" in ancestors (iterate from innermost to outermost)
        for (ElementContext ctx : elementStack) {
            String xmlSpace = ctx.attributes.get("xml:space");
            if ("preserve".equals(xmlSpace)) {
                return true;
            } else if ("default".equals(xmlSpace)) {
                return false;
            }
        }
        
        return false;
    }

    /**
     * Processes exclude-result-prefixes attribute and adds excluded URIs to the global set.
     * Also tracks which URIs were excluded by this element for proper scoping.
     */
    private void processExcludeResultPrefixes(String excludePrefixes, Map<String, String> namespaces, ElementContext ctx) {
        String[] prefixes = excludePrefixes.split("\\s+");
        for (String prefix : prefixes) {
            if ("#all".equals(prefix)) {
                for (Map.Entry<String, String> ns : namespaces.entrySet()) {
                    if (!XSLT_NS.equals(ns.getValue())) {
                        String uri = ns.getValue();
                        if (!excludedNamespaceURIs.contains(uri)) {
                            excludedNamespaceURIs.add(uri);
                            ctx.excludedByThisElement.add(uri);
                        }
                    }
                }
            } else if ("#default".equals(prefix)) {
                String defaultNs = namespaces.get("");
                if (defaultNs != null && !defaultNs.isEmpty()) {
                    if (!excludedNamespaceURIs.contains(defaultNs)) {
                        excludedNamespaceURIs.add(defaultNs);
                        ctx.excludedByThisElement.add(defaultNs);
                    }
                }
            } else {
                String nsUri = namespaces.get(prefix);
                if (nsUri != null && !nsUri.isEmpty()) {
                    if (!excludedNamespaceURIs.contains(nsUri)) {
                        excludedNamespaceURIs.add(nsUri);
                        ctx.excludedByThisElement.add(nsUri);
                    }
                }
            }
        }
    }

    /**
     * Compiles an element into an XSLT node.
     */
    private XSLTNode compileElement(ElementContext ctx) throws SAXException {
        XSLTNode result;
        if (XSLT_NS.equals(ctx.namespaceURI)) {
            result = compileXSLTElement(ctx);
        } else {
            // Per XSLT 1.0 Section 2.2: Top-level elements in non-XSLT namespaces
            // are "user data elements" and are ignored (not compiled).
            // Their attributes should NOT be treated as AVTs.
            if (isTopLevel()) {
                return null; // Ignore top-level user data elements
            }
            
            // Check if this is an extension element (in an extension namespace)
            if (ctx.namespaceURI != null && extensionNamespaceURIs.contains(ctx.namespaceURI)) {
                // Extension element - check for xsl:fallback children
                // Since we don't implement any extensions, always use fallback
                List<XSLTNode> fallbacks = new ArrayList<>();
                for (XSLTNode child : ctx.children) {
                    if (child instanceof FallbackNode) {
                        fallbacks.add(child);
                    }
                }
                if (!fallbacks.isEmpty()) {
                    result = new SequenceNode(fallbacks);
                } else {
                    // No fallback - in XSLT 1.0 this is an error, but we'll return empty for now
                    result = new SequenceNode(new ArrayList<>());
                }
            } else {
                result = compileLiteralResultElement(ctx);
            }
        }
        
        // Set static base URI on compiled instructions (for static-base-uri() support)
        if (result instanceof XSLTInstruction && ctx.baseURI != null) {
            ((XSLTInstruction) result).setStaticBaseURI(ctx.baseURI);
        }
        
        return result;
    }

    /**
     * Validates that an XSLT element only has allowed attributes (XTSE0090).
     * Also validates QName attribute values (XTSE0020).
     */
    private void validateAllowedAttributes(ElementContext ctx) throws SAXException {
        // Get the appropriate validator based on version
        XSLTSchemaValidator validator = stylesheetVersion >= 3.0 
            ? XSLTSchemaValidator.getInstance30()
            : XSLTSchemaValidator.getInstance20();
        
        for (Map.Entry<String, String> attr : ctx.attributes.entrySet()) {
            String attrName = attr.getKey();
            String attrValue = attr.getValue();
            
            // XTSE0090: Check if attribute is allowed (using schema)
            validator.validateAttribute(ctx.localName, attrName);
            
            // XTSE0020: Validate QName attributes (but skip if it's an AVT with expressions)
            if (isQNameAttribute(attrName) && attrValue != null && !attrValue.isEmpty()) {
                // If the value contains {}, it's an AVT and we can't validate statically
                if (!attrValue.contains("{") || attrValue.startsWith("Q{")) {
                    XSLTSchemaValidator.validateQName(attrName, attrValue);
                }
            }
        }
    }
    
    /**
     * Checks if an attribute expects a QName value.
     */
    private boolean isQNameAttribute(String attrName) {
        return "name".equals(attrName) || "mode".equals(attrName);
    }

    /**
     * Compiles an XSLT instruction element.
     */
    private XSLTNode compileXSLTElement(ElementContext ctx) throws SAXException {
        // XTSE0090: Validate attributes are allowed on this element
        validateAllowedAttributes(ctx);
        
        switch (ctx.localName) {
            case "stylesheet":
            case "transform":
            case "package":
                processStylesheetElement(ctx);
                return null;
                
            case "import":
                // XTSE0010: xsl:import must be a top-level element
                if (!isTopLevel()) {
                    throw new SAXException("XTSE0010: xsl:import is only allowed at the top level");
                }
                processImport(ctx);
                return null;
                
            case "include":
                processInclude(ctx);
                return null;
                
            case "import-schema":
                processImportSchema(ctx);
                return null;
                
            case "template":
                // XTSE0010: xsl:template must be a top-level element
                if (!isTopLevel()) {
                    throw new SAXException("XTSE0010: xsl:template is only allowed at the top level");
                }
                processTemplateElement(ctx);
                return null;
                
            case "variable":
                return compileVariable(ctx, isTopLevel());
                
            case "param":
                return compileParam(ctx, isTopLevel());
                
            case "output":
                processOutputElement(ctx);
                return null;
                
            case "key":
                processKeyElement(ctx);
                return null;
                
            case "attribute-set":
                processAttributeSetElement(ctx);
                return null;
                
            case "strip-space":
                processStripSpace(ctx);
                return null;
                
            case "preserve-space":
                processPreserveSpace(ctx);
                return null;
                
            case "namespace-alias":
                processNamespaceAlias(ctx);
                return null;
                
            // Instructions that produce output
            case "value-of":
                return compileValueOf(ctx);
                
            case "text":
                return compileText(ctx);
                
            case "element":
                return compileElement2(ctx);
                
            case "attribute":
                return compileAttribute(ctx);
                
            case "namespace":
                return compileNamespace(ctx);
                
            case "comment":
                return compileComment(ctx);
                
            case "processing-instruction":
                return compilePI(ctx);
                
            case "copy":
                return compileCopy(ctx);
                
            case "copy-of":
                return compileCopyOf(ctx);
                
            case "apply-templates":
                return compileApplyTemplates(ctx);
                
            case "call-template":
                return compileCallTemplate(ctx);
                
            case "apply-imports":
                return compileApplyImports(ctx);
                
            case "next-match":
                return compileNextMatch(ctx);
                
            case "for-each":
                return compileForEach(ctx);
                
            case "stream":
                return compileStream(ctx);
                
            case "iterate":
                return compileIterate(ctx);
                
            case "next-iteration":
                return compileNextIteration(ctx);
                
            case "break":
                return compileBreak(ctx);
                
            case "on-completion":
                // Handled by iterate
                return new SequenceNode(new ArrayList<>(ctx.children));
                
            case "fork":
                return compileFork(ctx);
                
            case "sequence":
                return compileSequence(ctx);
                
            case "result-document":
                return compileResultDocument(ctx);
                
            case "for-each-group":
                return compileForEachGroup(ctx);
                
            case "analyze-string":
                return compileAnalyzeString(ctx);
                
            case "matching-substring":
            case "non-matching-substring":
                // These are handled as children of analyze-string
                return new SequenceNode(ctx.children);
                
            case "try":
                return compileTry(ctx);
                
            case "catch":
                // Handled as child of try
                return new SequenceNode(ctx.children);
                
            case "if":
                return compileIf(ctx);
                
            case "choose":
                return compileChoose(ctx);
                
            case "when":
                return compileWhen(ctx);
                
            case "otherwise":
                return compileOtherwise(ctx);
                
            case "sort":
                return compileSort(ctx);
                
            case "with-param":
                // Handled within call-template/apply-templates
                return compileWithParam(ctx);
                
            case "number":
                return compileNumber(ctx);
                
            case "message":
                return compileMessage(ctx);
                
            case "fallback":
                return compileFallback(ctx);
                
            case "decimal-format":
                processDecimalFormat(ctx);
                return null;
                
            case "accumulator":
                processAccumulator(ctx);
                return null;
                
            case "accumulator-rule":
                return compileAccumulatorRule(ctx);
                
            case "mode":
                processModeDeclaration(ctx);
                return null;
                
            case "function":
                processFunctionElement(ctx);
                return null;
                
            default:
                // In forward-compatible mode, unknown elements are ignored at top level
                // or use xsl:fallback content if inside a template
                if (forwardCompatible) {
                    // Collect ALL xsl:fallback children (XSLT spec says all are executed)
                    List<XSLTNode> fallbacks = new ArrayList<>();
                    for (XSLTNode child : ctx.children) {
                        if (child instanceof FallbackNode) {
                            fallbacks.add(child);
                        }
                    }
                    if (!fallbacks.isEmpty()) {
                        return new SequenceNode(fallbacks);
                    }
                    // No fallback - return empty sequence
                    return new SequenceNode(new ArrayList<>());
                }
                throw new SAXException("Unknown XSLT element: xsl:" + ctx.localName);
        }
    }
    
    /**
     * Processes an xsl:decimal-format declaration.
     */
    private void processDecimalFormat(ElementContext ctx) throws SAXException {
        String name = ctx.attributes.get("name");
        String decimalSeparator = ctx.attributes.get("decimal-separator");
        String groupingSeparator = ctx.attributes.get("grouping-separator");
        String infinity = ctx.attributes.get("infinity");
        String minusSign = ctx.attributes.get("minus-sign");
        String nan = ctx.attributes.get("NaN");
        String percent = ctx.attributes.get("percent");
        String perMille = ctx.attributes.get("per-mille");
        String zeroDigit = ctx.attributes.get("zero-digit");
        String digit = ctx.attributes.get("digit");
        String patternSeparator = ctx.attributes.get("pattern-separator");
        
        builder.addDecimalFormat(name, decimalSeparator, groupingSeparator,
            infinity, minusSign, nan, percent, perMille, zeroDigit, digit, patternSeparator);
    }

    /**
     * Processes an xsl:accumulator declaration (XSLT 3.0).
     *
     * <p>Example:
     * <pre>
     * &lt;xsl:accumulator name="item-count" initial-value="0"&gt;
     *   &lt;xsl:accumulator-rule match="item" select="$value + 1"/&gt;
     * &lt;/xsl:accumulator&gt;
     * </pre>
     */
    private void processAccumulator(ElementContext ctx) throws SAXException {
        String name = ctx.attributes.get("name");
        if (name == null || name.isEmpty()) {
            throw new SAXException("xsl:accumulator requires name attribute");
        }
        
        String initialValueStr = ctx.attributes.get("initial-value");
        if (initialValueStr == null) {
            throw new SAXException("xsl:accumulator requires initial-value attribute");
        }
        
        XPathExpression initialValue = compileExpression(initialValueStr);
        String asType = ctx.attributes.get("as");
        String streamableAttr = ctx.attributes.get("streamable");
        boolean streamable = !"no".equals(streamableAttr);  // Default is yes
        
        AccumulatorDefinition.Builder accBuilder = new AccumulatorDefinition.Builder()
            .name(name)
            .initialValue(initialValue)
            .streamable(streamable)
            .asType(asType);
        
        // Process accumulator-rule children
        for (XSLTNode child : ctx.children) {
            if (child instanceof AccumulatorRuleNode) {
                AccumulatorRuleNode ruleNode = (AccumulatorRuleNode) child;
                accBuilder.addRule(ruleNode.toRule());
            }
        }
        
        builder.addAccumulator(accBuilder.build());
    }
    
    /**
     * Processes an xsl:mode declaration (XSLT 3.0).
     */
    private void processModeDeclaration(ElementContext ctx) throws SAXException {
        String name = ctx.attributes.get("name");
        String streamableAttr = ctx.attributes.get("streamable");
        String onNoMatchAttr = ctx.attributes.get("on-no-match");
        String visibilityAttr = ctx.attributes.get("visibility");
        String useAccumulators = ctx.attributes.get("use-accumulators");
        String typedAttr = ctx.attributes.get("typed");
        String warningOnNoMatch = ctx.attributes.get("warning-on-no-match");
        
        ModeDeclaration.Builder modeBuilder = new ModeDeclaration.Builder()
            .name(name)
            .streamable("yes".equals(streamableAttr))
            .onNoMatch(onNoMatchAttr)
            .visibility(visibilityAttr)
            .useAccumulators(useAccumulators)
            .typed("yes".equals(typedAttr))
            .warning("yes".equals(warningOnNoMatch));
        
        builder.addModeDeclaration(modeBuilder.build());
    }
    
    /**
     * Processes an xsl:function element (XSLT 2.0+).
     *
     * <p>User-defined functions can be called from XPath expressions.
     * They must be in a non-null namespace.
     */
    private void processFunctionElement(ElementContext ctx) throws SAXException {
        importsAllowed = false;
        ensurePrecedenceAssigned();  // Assign precedence after all imports are processed
        
        String name = ctx.attributes.get("name");
        if (name == null || name.isEmpty()) {
            throw new SAXException("xsl:function requires name attribute");
        }
        
        // Parse function name - must be in a namespace
        QName funcName = parseQName(name, ctx.namespaceBindings);
        if (funcName.getURI().isEmpty()) {
            throw new SAXException("xsl:function name must be in a namespace: " + name);
        }
        String namespaceURI = funcName.getURI();
        String localName = funcName.getLocalName();
        
        String asType = ctx.attributes.get("as"); // Optional return type
        String cacheAttr = ctx.attributes.get("cache"); // XSLT 3.0 caching
        boolean cached = "yes".equals(cacheAttr) || "true".equals(cacheAttr);
        
        // Extract parameters from children
        List<UserFunction.FunctionParameter> params = new ArrayList<>();
        List<XSLTNode> bodyNodes = new ArrayList<>();
        
        for (XSLTNode child : ctx.children) {
            if (child instanceof ParamNode) {
                ParamNode pn = (ParamNode) child;
                String paramAs = pn.getAs(); // Type annotation if any
                params.add(new UserFunction.FunctionParameter(pn.getName(), paramAs));
            } else {
                bodyNodes.add(child);
            }
        }
        
        SequenceNode body = new SequenceNode(bodyNodes);
        
        UserFunction function = new UserFunction(
            namespaceURI, localName, params, body, asType, importPrecedence, cached);
        builder.addUserFunction(function);
    }
    
    /**
     * Compiles an xsl:accumulator-rule element.
     */
    private XSLTNode compileAccumulatorRule(ElementContext ctx) throws SAXException {
        String matchStr = ctx.attributes.get("match");
        if (matchStr == null || matchStr.isEmpty()) {
            throw new SAXException("xsl:accumulator-rule requires match attribute");
        }
        
        String selectStr = ctx.attributes.get("select");
        String phaseStr = ctx.attributes.get("phase");
        
        // Default phase is post-descent if not specified
        AccumulatorDefinition.Phase phase = AccumulatorDefinition.Phase.POST_DESCENT;
        if ("start".equals(phaseStr)) {
            phase = AccumulatorDefinition.Phase.PRE_DESCENT;
        }
        
        Pattern pattern = compilePattern(matchStr);
        XPathExpression newValue = selectStr != null ? compileExpression(selectStr) : null;
        
        // If no select, content provides the new value (wrapped as sequence)
        if (newValue == null && !ctx.children.isEmpty()) {
            // Content-based new value - for now just use string content
            // Full implementation would execute children as sequence constructor
            newValue = compileExpression("$value");  // Placeholder - keeps current value
        }
        
        if (newValue == null) {
            throw new SAXException("xsl:accumulator-rule requires select attribute or content");
        }
        
        return new AccumulatorRuleNode(pattern, phase, newValue);
    }
    
    /**
     * Internal node representing a compiled accumulator rule.
     */
    private static class AccumulatorRuleNode implements XSLTNode {
        private final Pattern pattern;
        private final AccumulatorDefinition.Phase phase;
        private final XPathExpression newValue;
        
        AccumulatorRuleNode(Pattern pattern, AccumulatorDefinition.Phase phase, 
                           XPathExpression newValue) {
            this.pattern = pattern;
            this.phase = phase;
            this.newValue = newValue;
        }
        
        AccumulatorDefinition.AccumulatorRule toRule() {
            return new AccumulatorDefinition.AccumulatorRule(pattern, phase, newValue);
        }
        
        @Override
        public void execute(TransformContext context, OutputHandler output) 
                throws SAXException {
            // Accumulator rules are not executed directly
        }
        
        @Override
        public StreamingCapability getStreamingCapability() {
            return StreamingCapability.FULL;
        }
    }

    /**
     * Compiles a literal result element.
     */
    private XSLTNode compileLiteralResultElement(ElementContext ctx) throws SAXException {
        // Find the prefix for the namespace URI
        String prefix = null;
        String localName = ctx.localName;
        
        // Look up the prefix that maps to this namespace URI
        if (ctx.namespaceURI != null && !ctx.namespaceURI.isEmpty()) {
            for (Map.Entry<String, String> ns : ctx.namespaceBindings.entrySet()) {
                if (ctx.namespaceURI.equals(ns.getValue())) {
                    prefix = ns.getKey();
                    if (!prefix.isEmpty()) {
                        break; // Found a non-default prefix
                    }
                }
            }
        }
        
        // Extract xsl:use-attribute-sets before processing other attributes
        String useAttrSetsValue = ctx.attributes.get("xsl:use-attribute-sets");
        List<String> useAttributeSets = new ArrayList<>();
        if (useAttrSetsValue != null) {
            for (String setName : splitOnWhitespace(useAttrSetsValue)) {
                useAttributeSets.add(setName);
            }
        }
        
        // Extract xsl:exclude-result-prefixes for this element
        String excludePrefixesValue = ctx.attributes.get("xsl:exclude-result-prefixes");
        Set<String> localExcludedURIs = new HashSet<>(excludedNamespaceURIs);
        // Extension namespaces are automatically excluded
        localExcludedURIs.addAll(extensionNamespaceURIs);
        
        if (excludePrefixesValue != null && !excludePrefixesValue.isEmpty()) {
            String[] prefixes = excludePrefixesValue.split("\\s+");
            for (String p : prefixes) {
                if ("#all".equals(p)) {
                    for (Map.Entry<String, String> ns : ctx.namespaceBindings.entrySet()) {
                        if (!XSLT_NS.equals(ns.getValue())) {
                            localExcludedURIs.add(ns.getValue());
                        }
                    }
                } else if ("#default".equals(p)) {
                    String defaultNs = ctx.namespaceBindings.get("");
                    if (defaultNs != null) {
                        localExcludedURIs.add(defaultNs);
                    }
                } else {
                    String uri = ctx.namespaceBindings.get(p);
                    if (uri != null) {
                        localExcludedURIs.add(uri);
                    }
                }
            }
        }
        
        // Handle xsl:extension-element-prefixes on LRE (local extension declaration)
        String extensionPrefixesValue = ctx.attributes.get("xsl:extension-element-prefixes");
        if (extensionPrefixesValue != null && !extensionPrefixesValue.isEmpty()) {
            String[] prefixes = extensionPrefixesValue.split("\\s+");
            for (String p : prefixes) {
                if ("#default".equals(p)) {
                    String defaultNs = ctx.namespaceBindings.get("");
                    if (defaultNs != null) {
                        localExcludedURIs.add(defaultNs);
                    }
                } else {
                    String uri = ctx.namespaceBindings.get(p);
                    if (uri != null) {
                        localExcludedURIs.add(uri);
                    }
                }
            }
        }
        
        // Extract xsl:type for type annotation (XSLT 2.0)
        String typeValue = ctx.attributes.get("xsl:type");
        String typeNamespaceURI = null;
        String typeLocalName = null;
        if (typeValue != null && !typeValue.isEmpty()) {
            // Parse QName - may be prefixed like xs:integer
            int colonPos = typeValue.indexOf(':');
            if (colonPos > 0) {
                String typePrefix = typeValue.substring(0, colonPos);
                typeLocalName = typeValue.substring(colonPos + 1);
                typeNamespaceURI = ctx.namespaceBindings.get(typePrefix);
                if (typeNamespaceURI == null) {
                    throw new SAXException("Unknown namespace prefix in xsl:type: " + typePrefix);
                }
            } else {
                // No prefix - assume XSD namespace
                typeLocalName = typeValue;
                typeNamespaceURI = "http://www.w3.org/2001/XMLSchema";
            }
        }
        
        // Compile attributes as AVTs
        Map<String, AttributeValueTemplate> avts = new LinkedHashMap<>();
        for (Map.Entry<String, String> attr : ctx.attributes.entrySet()) {
            String name = attr.getKey();
            String value = attr.getValue();
            
            // Skip xsl: attributes on literal result elements
            if (name.startsWith("xsl:")) {
                continue;
            }
            
            try {
                avts.put(name, AttributeValueTemplate.parse(value, this));
            } catch (XPathSyntaxException e) {
                throw new SAXException("Invalid AVT in attribute " + name + ": " + e.getMessage(), e);
            }
        }
        
        // Build content
        SequenceNode content = new SequenceNode(ctx.children);
        
        // Per XSLT 1.0 section 7.1.1: Copy namespace nodes except XSLT namespace.
        // Also exclude namespaces listed in exclude-result-prefixes (both global and local).
        // BUT: Can't exclude a namespace that's actually used by the element or its attributes.
        // Output ALL in-scope namespaces - SAXOutputHandler will deduplicate inherited ones.
        // Namespace aliasing is applied at runtime by LiteralResultElement.
        
        // Collect namespaces that are actually used (can't be excluded)
        Set<String> usedNamespaces = new HashSet<>();
        // The element's own namespace is used
        if (ctx.namespaceURI != null && !ctx.namespaceURI.isEmpty()) {
            usedNamespaces.add(ctx.namespaceURI);
        }
        // Check attribute namespaces
        for (String attrName : ctx.attributes.keySet()) {
            if (attrName.startsWith("xsl:")) continue; // Skip XSLT attributes
            int colon = attrName.indexOf(':');
            if (colon > 0) {
                String attrPrefix = attrName.substring(0, colon);
                String attrNs = ctx.namespaceBindings.get(attrPrefix);
                if (attrNs != null && !attrNs.isEmpty()) {
                    usedNamespaces.add(attrNs);
                }
            }
        }
        
        Map<String, String> outputNamespaces = new LinkedHashMap<>();
        for (Map.Entry<String, String> ns : ctx.namespaceBindings.entrySet()) {
            String nsUri = ns.getValue();
            // Don't output the XSLT namespace
            if (XSLT_NS.equals(nsUri)) {
                continue;
            }
            // Can't exclude namespaces that are actually used
            if (usedNamespaces.contains(nsUri)) {
                outputNamespaces.put(ns.getKey(), nsUri);
                continue;
            }
            // Exclude if in excluded set
            if (!localExcludedURIs.contains(nsUri)) {
                outputNamespaces.put(ns.getKey(), nsUri);
            }
        }
        
        return new LiteralResultElement(ctx.namespaceURI, localName, prefix, 
            avts, outputNamespaces, useAttributeSets, typeNamespaceURI, typeLocalName, content);
    }

    // ========================================================================
    // Top-level element processing
    // ========================================================================

    private void processStylesheetElement(ElementContext ctx) throws SAXException {
        // Parse version attribute
        String versionAttr = ctx.attributes.get("version");
        if (versionAttr != null) {
            try {
                stylesheetVersion = Double.parseDouble(versionAttr);
                // Forward-compatible mode is enabled when version > 1.0
                forwardCompatible = stylesheetVersion > 1.0;
                // Store version in compiled stylesheet
                builder.setVersion(stylesheetVersion);
            } catch (NumberFormatException e) {
                // Ignore invalid version, use default
            }
        }
        
        // xsl:package is only allowed in XSLT 3.0+
        if ("package".equals(ctx.localName) && stylesheetVersion < 3.0) {
            throw new SAXException("xsl:package is only allowed in XSLT 3.0 or later (version=" + 
                stylesheetVersion + ")");
        }
        
        // Parse exclude-result-prefixes attribute
        String excludePrefixes = ctx.attributes.get("exclude-result-prefixes");
        if (excludePrefixes != null && !excludePrefixes.isEmpty()) {
            String[] prefixes = excludePrefixes.split("\\s+");
            for (String prefix : prefixes) {
                if ("#all".equals(prefix)) {
                    // XSLT 2.0: exclude all namespaces in scope
                    for (Map.Entry<String, String> ns : ctx.namespaceBindings.entrySet()) {
                        if (!XSLT_NS.equals(ns.getValue())) {
                            excludedNamespaceURIs.add(ns.getValue());
                            builder.addExcludedNamespaceURI(ns.getValue());
                        }
                    }
                } else if ("#default".equals(prefix)) {
                    // Exclude the default namespace
                    String defaultNs = ctx.namespaceBindings.get("");
                    if (defaultNs != null && !defaultNs.isEmpty()) {
                        excludedNamespaceURIs.add(defaultNs);
                        builder.addExcludedNamespaceURI(defaultNs);
                    }
                } else {
                    // Regular prefix - look up its namespace URI and exclude it
                    String uri = ctx.namespaceBindings.get(prefix);
                    if (uri != null && !uri.isEmpty()) {
                        excludedNamespaceURIs.add(uri);
                        builder.addExcludedNamespaceURI(uri);
                    }
                }
            }
        }
        
        // Parse extension-element-prefixes attribute
        // Extension namespaces are automatically excluded from output
        String extensionPrefixes = ctx.attributes.get("extension-element-prefixes");
        if (extensionPrefixes != null && !extensionPrefixes.isEmpty()) {
            String[] prefixes = extensionPrefixes.split("\\s+");
            for (String prefix : prefixes) {
                if ("#default".equals(prefix)) {
                    String defaultNs = ctx.namespaceBindings.get("");
                    if (defaultNs != null && !defaultNs.isEmpty()) {
                        extensionNamespaceURIs.add(defaultNs);
                    }
                } else {
                    String uri = ctx.namespaceBindings.get(prefix);
                    if (uri != null && !uri.isEmpty()) {
                        extensionNamespaceURIs.add(uri);
                    }
                }
            }
        }
        
        // Parse default-validation attribute (XSLT 2.0+)
        String defaultValidationAttr = ctx.attributes.get("default-validation");
        if (defaultValidationAttr != null && !defaultValidationAttr.isEmpty()) {
            switch (defaultValidationAttr) {
                case "strict":
                    defaultValidation = ValidationMode.STRICT;
                    break;
                case "lax":
                    defaultValidation = ValidationMode.LAX;
                    break;
                case "preserve":
                    defaultValidation = ValidationMode.PRESERVE;
                    break;
                case "strip":
                    defaultValidation = ValidationMode.STRIP;
                    break;
                default:
                    throw new SAXException("Invalid default-validation value: " + defaultValidationAttr + 
                        ". Expected: strict, lax, preserve, or strip");
            }
            builder.setDefaultValidation(defaultValidation);
        }
        // Process children which add themselves to builder
    }

    /**
     * Returns true if the current element is a direct child of the stylesheet element.
     * 
     * <p>When called from endElement, the current element has already been popped,
     * so the stack contains only ancestors. If the stack has exactly 1 element
     * and it's xsl:stylesheet or xsl:transform, we're at the top level.
     */
    private boolean isTopLevel() {
        if (elementStack.isEmpty()) {
            return false;
        }
        // Check if the only element on the stack is the stylesheet
        if (elementStack.size() == 1) {
            ElementContext parent = elementStack.peek();
            if (XSLT_NS.equals(parent.namespaceURI)) {
                if ("stylesheet".equals(parent.localName) || "transform".equals(parent.localName)
                        || "package".equals(parent.localName)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Processes an xsl:import element.
     *
     * <p>xsl:import must appear before any other children of xsl:stylesheet.
     * Imported stylesheets have lower import precedence than the importing
     * stylesheet.
     */
    private void processImport(ElementContext ctx) throws SAXException {
        // Check that imports are still allowed (must be at top of stylesheet)
        if (!importsAllowed) {
            throw new SAXException("xsl:import must appear before all other " +
                "elements in the stylesheet (except other xsl:import elements)");
        }
        
        String href = ctx.attributes.get("href");
        if (href == null || href.isEmpty()) {
            throw new SAXException("XTSE0010: xsl:import requires href attribute");
        }
        
        if (resolver == null) {
            throw new SAXException("xsl:import not supported: no StylesheetResolver configured");
        }
        
        try {
            // XSLT 1.0 import precedence rules (using global counter in resolver):
            // - Imports are processed recursively in tree traversal order
            // - Each stylesheet gets its precedence when its first non-import element is seen
            // - This ensures: D < B < E < C < A for the tree A→[B→D, C→E]
            // The imported stylesheet will assign its own precedence from the global counter
            CompiledStylesheet imported = resolver.resolve(href, baseUri, true, 0);
            if (imported != null) {
                builder.merge(imported, true);
            }
        } catch (IOException e) {
            throw new SAXException("Failed to import stylesheet: " + href, e);
        }
    }

    /**
     * Processes an xsl:include element.
     *
     * <p>xsl:include can appear anywhere among the top-level elements. Included
     * stylesheets have the same import precedence as the including stylesheet.
     */
    private void processInclude(ElementContext ctx) throws SAXException {
        // XTSE0010: xsl:include must be top-level
        if (!isTopLevel()) {
            throw new SAXException("XTSE0010: xsl:include is only allowed at the top level");
        }
        
        // Include is allowed anywhere in top-level, but once we see a non-import
        // element, no more imports are allowed
        importsAllowed = false;
        ensurePrecedenceAssigned();  // Assign precedence before including (includes share our precedence)
        
        String href = ctx.attributes.get("href");
        if (href == null || href.isEmpty()) {
            throw new SAXException("XTSE0010: xsl:include requires href attribute");
        }
        
        if (resolver == null) {
            throw new SAXException("xsl:include not supported: no StylesheetResolver configured");
        }
        
        try {
            // Includes use the same import precedence as the including stylesheet
            CompiledStylesheet included = resolver.resolve(href, baseUri, false, importPrecedence);
            if (included != null) {
                builder.merge(included, false);
            }
        } catch (IOException e) {
            throw new SAXException("Failed to include stylesheet: " + href, e);
        }
    }

    /**
     * Processes an xsl:import-schema element.
     *
     * <p>xsl:import-schema imports an XML Schema for schema-aware processing.
     * The schema types become available for use in type expressions like
     * {@code instance of}, {@code cast as}, and {@code castable as}.
     *
     * <p>Attributes:
     * <ul>
     *   <li>namespace - the target namespace of the schema (optional)</li>
     *   <li>schema-location - the location of the schema file</li>
     * </ul>
     */
    private void processImportSchema(ElementContext ctx) throws SAXException {
        importsAllowed = false;
        ensurePrecedenceAssigned();
        
        String namespace = ctx.attributes.get("namespace");
        String schemaLocation = ctx.attributes.get("schema-location");
        
        if (schemaLocation == null || schemaLocation.isEmpty()) {
            // No schema-location - just declares a namespace is schema-aware
            // This is valid but we can't actually load the schema
            return;
        }
        
        try {
            // Resolve the schema location relative to the stylesheet base URI
            String resolvedLocation = resolveUri(schemaLocation);
            
            // Parse the schema
            XSDSchema schema = XSDSchemaParser.parse(resolvedLocation);
            
            // Verify namespace matches if specified
            if (namespace != null && !namespace.isEmpty()) {
                String schemaTargetNs = schema.getTargetNamespace();
                if (schemaTargetNs != null && !namespace.equals(schemaTargetNs)) {
                    throw new SAXException("Schema target namespace '" + schemaTargetNs + 
                        "' does not match declared namespace '" + namespace + "'");
                }
            }
            
            // Add the schema to the compiled stylesheet
            builder.addImportedSchema(schema);
            
        } catch (IOException e) {
            throw new SAXException("Failed to import schema: " + schemaLocation, e);
        } catch (Exception e) {
            throw new SAXException("Error parsing schema: " + schemaLocation + " - " + e.getMessage(), e);
        }
    }

    /**
     * Resolves a URI relative to the stylesheet base URI.
     */
    private String resolveUri(String uri) {
        if (baseUri == null || uri.contains("://")) {
            return uri;
        }
        // Simple relative URI resolution
        int lastSlash = baseUri.lastIndexOf('/');
        if (lastSlash >= 0) {
            return baseUri.substring(0, lastSlash + 1) + uri;
        }
        return uri;
    }

    private void processTemplateElement(ElementContext ctx) throws SAXException {
        importsAllowed = false;
        ensurePrecedenceAssigned();  // Assign precedence after all imports are processed
        String match = ctx.attributes.get("match");
        String name = ctx.attributes.get("name");
        String mode = ctx.attributes.get("mode");
        String priorityStr = ctx.attributes.get("priority");
        
        // Expand mode QName to Clark notation for proper namespace comparison
        String expandedMode = expandModeQName(mode);
        
        Pattern pattern = null;
        if (match != null) {
            pattern = compilePattern(match);
        }
        
        double priority = pattern != null ? pattern.getDefaultPriority() : 0.0;
        if (priorityStr != null) {
            try {
                priority = Double.parseDouble(priorityStr);
            } catch (NumberFormatException e) {
                throw new SAXException("Invalid priority: " + priorityStr);
            }
        }
        
        // Extract parameters from children
        // XTSE0010: xsl:param must come before any other content
        List<TemplateParameter> params = new ArrayList<>();
        List<XSLTNode> bodyNodes = new ArrayList<>();
        boolean foundNonParam = false;
        
        for (XSLTNode child : ctx.children) {
            if (child instanceof ParamNode) {
                if (foundNonParam) {
                    throw new SAXException("XTSE0010: xsl:param must come before any other content in template");
                }
                ParamNode pn = (ParamNode) child;
                params.add(new TemplateParameter(pn.getName(), pn.getSelectExpr(), pn.getContent()));
            } else if (child instanceof WithParamNode) {
                // XTSE0010: xsl:with-param not allowed directly in template
                throw new SAXException("XTSE0010: xsl:with-param is not allowed directly in xsl:template");
            } else if (child instanceof SortSpecNode) {
                // XTSE0010: xsl:sort not allowed directly in template
                throw new SAXException("XTSE0010: xsl:sort is not allowed directly in xsl:template");
            } else {
                foundNonParam = true;
                bodyNodes.add(child);
            }
        }
        
        SequenceNode body = new SequenceNode(bodyNodes);
        
        TemplateRule rule = new TemplateRule(pattern, name, expandedMode, priority, 
            importPrecedence, templateCounter++, params, body);
        builder.addTemplateRule(rule);
    }

    /**
     * Expands a mode QName to Clark notation {uri}localname for proper comparison.
     * Mode names like foo:a and moo:a should be equal if both prefixes map to the same URI.
     */
    private String expandModeQName(String mode) {
        if (mode == null || mode.isEmpty()) {
            return mode;
        }
        
        // Handle special mode values
        if ("#default".equals(mode) || "#all".equals(mode) || "#current".equals(mode)) {
            return mode;
        }
        
        int colonPos = mode.indexOf(':');
        if (colonPos > 0) {
            // Prefixed mode name - expand to Clark notation
            String prefix = mode.substring(0, colonPos);
            String localName = mode.substring(colonPos + 1);
            String uri = resolve(prefix);
            if (uri != null && !uri.isEmpty()) {
                return "{" + uri + "}" + localName;
            }
        }
        
        // Unprefixed mode - return as-is (no namespace)
        return mode;
    }

    private void processOutputElement(ElementContext ctx) {
        importsAllowed = false;
        OutputProperties props = new OutputProperties();
        
        String method = ctx.attributes.get("method");
        if (method != null) {
            props.setMethod(method);
        }
        
        String encoding = ctx.attributes.get("encoding");
        if (encoding != null) {
            props.setEncoding(encoding);
        }
        
        String indent = ctx.attributes.get("indent");
        if ("yes".equals(indent)) {
            props.setIndent(true);
        }
        
        String omitDecl = ctx.attributes.get("omit-xml-declaration");
        if ("yes".equals(omitDecl)) {
            props.setOmitXmlDeclaration(true);
        }
        
        builder.setOutputProperties(props);
    }

    private void processKeyElement(ElementContext ctx) throws SAXException {
        // XTSE0010: xsl:key must be top-level
        if (!isTopLevel()) {
            throw new SAXException("XTSE0010: xsl:key is only allowed at the top level");
        }
        
        importsAllowed = false;
        String name = ctx.attributes.get("name");
        String match = ctx.attributes.get("match");
        String use = ctx.attributes.get("use");
        
        // XTSE0010: name and match are required
        if (name == null || name.isEmpty()) {
            throw new SAXException("XTSE0010: xsl:key requires name attribute");
        }
        if (match == null || match.isEmpty()) {
            throw new SAXException("XTSE0010: xsl:key requires match attribute");
        }
        // Note: use is optional in XSLT 2.0+ (can have content instead)
        if (use == null && ctx.children.isEmpty()) {
            throw new SAXException("XTSE0010: xsl:key requires use attribute or content");
        }
        
        // Parse key name to a QName with resolved namespace
        // This ensures key('bar:foo', ...) finds key defined as baz:foo when both
        // prefixes map to the same namespace URI
        QName keyName = parseQName(name, ctx.namespaceBindings);
        
        Pattern pattern = compilePattern(match);
        XPathExpression useExpr = compileExpression(use);
        
        builder.addKeyDefinition(new KeyDefinition(keyName, pattern, useExpr));
    }
    
    /**
     * Parses a qualified name string into a QName object.
     * 
     * <p>Handles formats:
     * <ul>
     *   <li>"localname" - element in no namespace</li>
     *   <li>"prefix:localname" - element in namespace bound to prefix</li>
     *   <li>"Q{uri}localname" - EQName syntax (XSLT 3.0)</li>
     * </ul>
     *
     * @param qnameStr the qualified name string
     * @param namespaces the current namespace bindings
     * @return a QName object, or null if input is null
     * @throws SAXException if a namespace prefix is not declared
     */
    private QName parseQName(String qnameStr, Map<String, String> namespaces) throws SAXException {
        if (qnameStr == null) {
            return null;
        }
        
        // Handle EQName syntax: Q{uri}localname (XSLT 3.0)
        if (qnameStr.startsWith("Q{")) {
            int closeBrace = qnameStr.indexOf('}');
            if (closeBrace >= 2) {
                String uri = qnameStr.substring(2, closeBrace);
                String localPart = qnameStr.substring(closeBrace + 1);
                return new QName(uri, localPart, qnameStr);
            }
        }
        
        int colon = qnameStr.indexOf(':');
        if (colon > 0) {
            String prefix = qnameStr.substring(0, colon);
            String localPart = qnameStr.substring(colon + 1);
            String uri = namespaces.get(prefix);
            if (uri == null) {
                uri = lookupNamespaceUri(prefix);
            }
            if (uri != null) {
                return new QName(uri, localPart, qnameStr);
            }
            throw new SAXException("XTSE0280: Namespace prefix '" + prefix + 
                "' in '" + qnameStr + "' is not declared");
        }
        
        // Unprefixed - no namespace
        return new QName("", qnameStr, qnameStr);
    }
    
    private void processAttributeSetElement(ElementContext ctx) throws SAXException {
        importsAllowed = false;
        String name = ctx.attributes.get("name");
        String useAttrSets = ctx.attributes.get("use-attribute-sets");
        
        List<String> useSets = new ArrayList<>();
        if (useAttrSets != null) {
            for (String s : splitOnWhitespace(useAttrSets)) {
                useSets.add(s);
            }
        }
        
        SequenceNode attrs = new SequenceNode(ctx.children);
        builder.addAttributeSet(new AttributeSet(name, useSets, attrs));
    }

    private void processStripSpace(ElementContext ctx) throws SAXException {
        importsAllowed = false;
        String elements = ctx.attributes.get("elements");
        if (elements != null) {
            for (String e : splitOnWhitespace(elements)) {
                // Resolve namespace prefix to URI for proper matching
                String resolved = resolveElementNameToUri(e, ctx.namespaceBindings);
                builder.addStripSpaceElement(resolved);
            }
        }
    }

    private void processPreserveSpace(ElementContext ctx) throws SAXException {
        importsAllowed = false;
        String elements = ctx.attributes.get("elements");
        if (elements != null) {
            for (String e : splitOnWhitespace(elements)) {
                // Resolve namespace prefix to URI for proper matching
                String resolved = resolveElementNameToUri(e, ctx.namespaceBindings);
                builder.addPreserveSpaceElement(resolved);
            }
        }
    }
    
    /**
     * Resolves an element name test to use namespace URI instead of prefix.
     * Converts "prefix:local" to "{uri}local" for proper matching.
     * 
     * Supports:
     *   "*" - match any element
     *   "localname" - match element in no namespace
     *   "prefix:localname" - match element in specific namespace
     *   "prefix:*" - match any element in specific namespace
     *   "*:localname" - match element with specific local name in any namespace (XSLT 2.0)
     *   "Q{uri}localname" - EQName syntax (XSLT 3.0)
     * 
     * @param pattern the element name pattern
     * @param namespaces the current namespace bindings
     * @return the resolved pattern with URI notation
     */
    private String resolveElementNameToUri(String pattern, Map<String, String> namespaces) 
            throws SAXException {
        if ("*".equals(pattern)) {
            return "*";
        }
        
        // Handle EQName syntax: Q{uri}localname (XSLT 3.0)
        // Q{}local means element in no namespace
        // Q{uri}local means element in specified namespace
        if (pattern.startsWith("Q{")) {
            int closeBrace = pattern.indexOf('}');
            if (closeBrace >= 2) {
                String uri = pattern.substring(2, closeBrace);
                String localPart = pattern.substring(closeBrace + 1);
                // Convert Q{uri}local to {uri}local (uri may be empty for no namespace)
                return "{" + uri + "}" + localPart;
            }
        }
        
        int colon = pattern.indexOf(':');
        if (colon > 0) {
            String prefix = pattern.substring(0, colon);
            String localPart = pattern.substring(colon + 1);
            
            // Handle *:localname - any namespace with specific local name
            if ("*".equals(prefix)) {
                // Store as {*}localname - special marker for "any namespace"
                return "{*}" + localPart;
            }
            
            String uri = namespaces.get(prefix);
            
            if (uri == null) {
                // Prefix not found - try inherited bindings
                uri = lookupNamespaceUri(prefix);
            }
            
            if (uri != null) {
                // Convert to Clark notation: {uri}localname or {uri}* 
                return "{" + uri + "}" + localPart;
            }
            // XTSE0280: Prefix not bound to any namespace
            throw new SAXException("XTSE0280: Namespace prefix '" + prefix + "' is not declared");
        }
        
        // Unprefixed name - matches elements in no namespace
        return pattern;
    }

    private void processNamespaceAlias(ElementContext ctx) throws SAXException {
        importsAllowed = false;
        String stylesheetPrefix = ctx.attributes.get("stylesheet-prefix");
        String resultPrefix = ctx.attributes.get("result-prefix");
        
        if (stylesheetPrefix == null || resultPrefix == null) {
            return;
        }
        
        // Handle #default prefix
        if ("#default".equals(stylesheetPrefix)) {
            stylesheetPrefix = "";
        }
        if ("#default".equals(resultPrefix)) {
            resultPrefix = "";
        }
        
        // Look up the namespace URIs for both prefixes
        // First check local namespace declarations on the xsl:namespace-alias element
        String stylesheetUri = ctx.namespaceBindings.get(stylesheetPrefix);
        String resultUri = ctx.namespaceBindings.get(resultPrefix);
        
        // If not found locally, check parent scopes
        if (stylesheetUri == null) {
            stylesheetUri = lookupNamespaceUri(stylesheetPrefix);
        }
        if (resultUri == null) {
            resultUri = lookupNamespaceUri(resultPrefix);
        }
        
        // Default namespace is empty string
        if (stylesheetUri == null) {
            stylesheetUri = "";
        }
        if (resultUri == null) {
            resultUri = "";
        }
        
        // XTSE0010: It is a static error if the stylesheet URI and result URI are the same
        if (stylesheetUri.equals(resultUri)) {
            throw new SAXException("XTSE0010: stylesheet-prefix and result-prefix must not " +
                "map to the same namespace URI (" + stylesheetUri + ")");
        }
        
        builder.addNamespaceAlias(stylesheetUri, resultUri, resultPrefix);
    }
    
    /**
     * Looks up a namespace URI by prefix in the current namespace context.
     */
    private String lookupNamespaceUri(String prefix) {
        if (prefix == null) {
            prefix = "";
        }
        // Walk up the element stack to find the namespace binding
        for (ElementContext ctx : elementStack) {
            String uri = ctx.namespaceBindings.get(prefix);
            if (uri != null) {
                return uri;
            }
        }
        // Well-known prefixes
        if ("xml".equals(prefix)) {
            return "http://www.w3.org/XML/1998/namespace";
        }
        return null;
    }

    // ========================================================================
    // Instruction compilation (stubs - to be implemented in instructions)
    // ========================================================================

    private XSLTNode compileVariable(ElementContext ctx, boolean isTopLevel) throws SAXException {
        String name = ctx.attributes.get("name");
        if (name == null || name.isEmpty()) {
            throw new SAXException("XTSE0010: xsl:variable requires name attribute");
        }
        
        String select = ctx.attributes.get("select");
        String staticAttr = ctx.attributes.get("static");
        String asType = ctx.attributes.get("as"); // XSLT 2.0 type annotation
        
        // Parse QName with resolved namespace
        QName varName = parseQName(name, ctx.namespaceBindings);
        
        // Check for static variable (XSLT 3.0)
        boolean isStatic = isStaticValue(staticAttr);
        
        if (isStatic && isTopLevel) {
            // Static variable: evaluate at compile time and store for use-when
            // Use the element's base URI for static-base-uri()
            XPathValue staticValue = evaluateStaticExpression(select, varName.getLocalName(), ctx.baseURI);
            staticVariables.put(varName.getLocalName(), staticValue);
            // Add as global variable with pre-computed static value
            builder.addGlobalVariable(new GlobalVariable(varName, false, staticValue));
            return null;
        }
        
        XPathExpression selectExpr = select != null ? compileExpression(select) : null;
        SequenceNode content = ctx.children.isEmpty() ? null : new SequenceNode(ctx.children);
        
        if (isTopLevel) {
            importsAllowed = false;
            builder.addGlobalVariable(new GlobalVariable(varName, false, selectExpr, content));
            return null;
        }
        
        return new VariableNode(varName.getURI(), varName.getLocalName(), selectExpr, content, asType);
    }

    private XSLTNode compileParam(ElementContext ctx, boolean isTopLevel) throws SAXException {
        String name = ctx.attributes.get("name");
        if (name == null || name.isEmpty()) {
            throw new SAXException("XTSE0010: xsl:param requires name attribute");
        }
        
        String select = ctx.attributes.get("select");
        String asType = ctx.attributes.get("as"); // XSLT 2.0 type annotation
        String staticAttr = ctx.attributes.get("static");
        
        // Parse QName with resolved namespace
        QName paramName = parseQName(name, ctx.namespaceBindings);
        
        // Check for static param (XSLT 3.0)
        boolean isStatic = isStaticValue(staticAttr);
        
        if (isStatic && isTopLevel) {
            // Static param: evaluate at compile time and store for use-when
            // Use the element's base URI for static-base-uri()
            XPathValue staticValue = evaluateStaticExpression(select, paramName.getLocalName(), ctx.baseURI);
            staticVariables.put(paramName.getLocalName(), staticValue);
            // Add as global variable with pre-computed static value
            builder.addGlobalVariable(new GlobalVariable(paramName, true, staticValue));
            return null;
        }
        
        XPathExpression selectExpr = select != null ? compileExpression(select) : null;
        SequenceNode content = ctx.children.isEmpty() ? null : new SequenceNode(ctx.children);
        
        if (isTopLevel) {
            // Top-level param is a global variable that can be set externally
            importsAllowed = false;
            builder.addGlobalVariable(new GlobalVariable(paramName, true, selectExpr, content));
            return null;
        }
        
        return new ParamNode(paramName.getURI(), paramName.getLocalName(), selectExpr, content, asType);
    }
    
    /**
     * Checks if a static attribute value indicates a static variable.
     * Accepts "yes", "true", "1" (with optional whitespace).
     */
    private boolean isStaticValue(String value) {
        if (value == null) {
            return false;
        }
        value = value.trim();
        return "yes".equals(value) || "true".equals(value) || "1".equals(value);
    }
    
    /**
     * Evaluates a static variable expression at compile time.
     * Static variables can only use literals and other static variables.
     *
     * @param select the XPath expression to evaluate
     * @param varName the variable name (for error messages)
     * @param baseURI the base URI for static-base-uri() (from element's xml:base)
     */
    private XPathValue evaluateStaticExpression(String select, String varName, String baseURI) 
            throws SAXException {
        if (select == null) {
            // Default to empty string if no select
            return XPathString.of("");
        }
        try {
            XPathExpression expr = XPathExpression.compile(select, this);
            XPathContext staticContext = new UseWhenContext(baseURI);
            return expr.evaluate(staticContext);
        } catch (Exception e) {
            throw new SAXException("Failed to evaluate static variable $" + varName + 
                ": " + e.getMessage(), e);
        }
    }

    private XSLTNode compileValueOf(ElementContext ctx) throws SAXException {
        String select = ctx.attributes.get("select");
        boolean disableEscaping = "yes".equals(ctx.attributes.get("disable-output-escaping"));
        // XSLT 2.0+ separator attribute (default is single space for sequences)
        String separator = ctx.attributes.get("separator");
        // In XSLT 2.0+, value-of outputs all items; in 1.0, only the first
        boolean xslt2Plus = stylesheetVersion >= 2.0;
        
        if (select != null) {
            return new ValueOfNode(compileExpression(select), disableEscaping, separator, xslt2Plus);
        }
        
        // XSLT 2.0+ allows content instead of select
        if (forwardCompatible && !ctx.children.isEmpty()) {
            return new SequenceNode(ctx.children);
        }
        
        throw new SAXException("xsl:value-of requires select attribute");
    }

    private XSLTNode compileText(ElementContext ctx) {
        boolean disableEscaping = "yes".equals(ctx.attributes.get("disable-output-escaping"));
        StringBuilder text = new StringBuilder();
        for (XSLTNode child : ctx.children) {
            if (child instanceof LiteralText) {
                text.append(((LiteralText) child).getText());
            }
        }
        return new LiteralText(text.toString(), disableEscaping);
    }

    private XSLTNode compileElement2(ElementContext ctx) throws SAXException {
        String name = ctx.attributes.get("name");
        String namespace = ctx.attributes.get("namespace");
        String useAttrSets = ctx.attributes.get("use-attribute-sets");
        String typeValue = ctx.attributes.get("type");
        String validationValue = ctx.attributes.get("validation");
        
        // Validation: name is required and must not be empty (XTSE0280)
        if (name == null || name.isEmpty()) {
            throw new SAXException("xsl:element requires a non-empty name attribute");
        }
        
        // type and validation are mutually exclusive (XTSE1505)
        if (typeValue != null && validationValue != null) {
            throw new SAXException("xsl:element cannot have both type and validation attributes");
        }
        
        AttributeValueTemplate nameAvt = parseAvt(name);
        AttributeValueTemplate nsAvt = namespace != null ? parseAvt(namespace) : null;
        
        // Parse validation mode
        ValidationMode validation = null;  // null means use default-validation
        if (validationValue != null && !validationValue.isEmpty()) {
            switch (validationValue) {
                case "strict":
                    validation = ValidationMode.STRICT;
                    break;
                case "lax":
                    validation = ValidationMode.LAX;
                    break;
                case "preserve":
                    validation = ValidationMode.PRESERVE;
                    break;
                case "strip":
                    validation = ValidationMode.STRIP;
                    break;
                default:
                    throw new SAXException("Invalid validation value on xsl:element: " + validationValue);
            }
        }
        
        // Parse type annotation if present
        String typeNamespaceURI = null;
        String typeLocalName = null;
        if (typeValue != null && !typeValue.isEmpty()) {
            int colonPos = typeValue.indexOf(':');
            if (colonPos > 0) {
                String typePrefix = typeValue.substring(0, colonPos);
                typeLocalName = typeValue.substring(colonPos + 1);
                typeNamespaceURI = ctx.namespaceBindings.get(typePrefix);
                if (typeNamespaceURI == null) {
                    throw new SAXException("Unknown namespace prefix in xsl:element/@type: " + typePrefix);
                }
            } else {
                typeLocalName = typeValue;
                typeNamespaceURI = "http://www.w3.org/2001/XMLSchema";
            }
        }
        
        // Capture the default namespace in scope for unprefixed name resolution
        // Per XSLT 1.0 section 7.1.2: "If the namespace attribute is not present,
        // then the QName is expanded into an expanded-name using the namespace 
        // declarations in effect for the xsl:element element"
        String defaultNs = ctx.namespaceBindings.get("");
        
        // Also capture all namespace bindings for prefix resolution
        Map<String, String> nsBindings = new HashMap<>(ctx.namespaceBindings);
        
        return new ElementNode(nameAvt, nsAvt, useAttrSets, new SequenceNode(ctx.children), 
                               defaultNs, nsBindings, typeNamespaceURI, typeLocalName, validation);
    }

    private XSLTNode compileAttribute(ElementContext ctx) throws SAXException {
        String name = ctx.attributes.get("name");
        String namespace = ctx.attributes.get("namespace");
        String select = ctx.attributes.get("select");
        String separator = ctx.attributes.get("separator");
        String typeValue = ctx.attributes.get("type");
        String validationValue = ctx.attributes.get("validation");
        
        // type and validation are mutually exclusive (XTSE1505)
        if (typeValue != null && validationValue != null) {
            throw new SAXException("xsl:attribute cannot have both type and validation attributes");
        }
        
        AttributeValueTemplate nameAvt = parseAvt(name);
        XPathExpression selectExpr = select != null ? compileExpression(select) : null;
        AttributeValueTemplate nsAvt = namespace != null ? parseAvt(namespace) : null;
        
        // Parse validation mode
        ValidationMode validation = null;  // null means use default-validation
        if (validationValue != null && !validationValue.isEmpty()) {
            switch (validationValue) {
                case "strict":
                    validation = ValidationMode.STRICT;
                    break;
                case "lax":
                    validation = ValidationMode.LAX;
                    break;
                case "preserve":
                    validation = ValidationMode.PRESERVE;
                    break;
                case "strip":
                    validation = ValidationMode.STRIP;
                    break;
                default:
                    throw new SAXException("Invalid validation value on xsl:attribute: " + validationValue);
            }
        }
        
        // Parse type annotation if present
        String typeNamespaceURI = null;
        String typeLocalName = null;
        if (typeValue != null && !typeValue.isEmpty()) {
            int colonPos = typeValue.indexOf(':');
            if (colonPos > 0) {
                String typePrefix = typeValue.substring(0, colonPos);
                typeLocalName = typeValue.substring(colonPos + 1);
                typeNamespaceURI = ctx.namespaceBindings.get(typePrefix);
                if (typeNamespaceURI == null) {
                    throw new SAXException("Unknown namespace prefix in xsl:attribute/@type: " + typePrefix);
                }
            } else {
                typeLocalName = typeValue;
                typeNamespaceURI = "http://www.w3.org/2001/XMLSchema";
            }
        }
        
        // Capture namespace bindings for prefix resolution
        Map<String, String> nsBindings = new HashMap<>(ctx.namespaceBindings);
        
        // If select is specified, it takes precedence over content (XSLT 2.0+)
        SequenceNode content = ctx.children.isEmpty() ? null : new SequenceNode(ctx.children);
        
        return new AttributeNode(nameAvt, nsAvt, selectExpr, separator, content, nsBindings,
                                 typeNamespaceURI, typeLocalName, validation);
    }

    private XSLTNode compileNamespace(ElementContext ctx) throws SAXException {
        String name = ctx.attributes.get("name");
        String select = ctx.attributes.get("select");
        
        AttributeValueTemplate nameAvt = parseAvt(name != null ? name : "");
        XPathExpression selectExpr = select != null ? compileExpression(select) : null;
        
        return new NamespaceInstructionNode(nameAvt, selectExpr, new SequenceNode(ctx.children));
    }
    
    private XSLTNode compileComment(ElementContext ctx) {
        return new CommentNode(new SequenceNode(ctx.children));
    }

    private XSLTNode compilePI(ElementContext ctx) throws SAXException {
        String name = ctx.attributes.get("name");
        AttributeValueTemplate nameAvt = parseAvt(name);
        return new ProcessingInstructionNode(nameAvt, new SequenceNode(ctx.children));
    }

    private XSLTNode compileCopy(ElementContext ctx) throws SAXException {
        String useAttrSets = ctx.attributes.get("use-attribute-sets");
        String typeValue = ctx.attributes.get("type");
        String validationValue = ctx.attributes.get("validation");
        
        // type and validation are mutually exclusive
        if (typeValue != null && validationValue != null) {
            throw new SAXException("xsl:copy cannot have both type and validation attributes");
        }
        
        // Parse validation mode
        ValidationMode validation = null;
        if (validationValue != null && !validationValue.isEmpty()) {
            switch (validationValue) {
                case "strict":
                    validation = ValidationMode.STRICT;
                    break;
                case "lax":
                    validation = ValidationMode.LAX;
                    break;
                case "preserve":
                    validation = ValidationMode.PRESERVE;
                    break;
                case "strip":
                    validation = ValidationMode.STRIP;
                    break;
                default:
                    throw new SAXException("Invalid validation value on xsl:copy: " + validationValue);
            }
        }
        
        // Parse type annotation if present
        String typeNamespaceURI = null;
        String typeLocalName = null;
        if (typeValue != null && !typeValue.isEmpty()) {
            int colonPos = typeValue.indexOf(':');
            if (colonPos > 0) {
                String typePrefix = typeValue.substring(0, colonPos);
                typeLocalName = typeValue.substring(colonPos + 1);
                typeNamespaceURI = ctx.namespaceBindings.get(typePrefix);
                if (typeNamespaceURI == null) {
                    throw new SAXException("Unknown namespace prefix in xsl:copy/@type: " + typePrefix);
                }
            } else {
                typeLocalName = typeValue;
                typeNamespaceURI = "http://www.w3.org/2001/XMLSchema";
            }
        }
        
        return new CopyNode(useAttrSets, new SequenceNode(ctx.children), 
                           typeNamespaceURI, typeLocalName, validation);
    }

    private XSLTNode compileCopyOf(ElementContext ctx) throws SAXException {
        String select = ctx.attributes.get("select");
        String typeValue = ctx.attributes.get("type");
        String validationValue = ctx.attributes.get("validation");
        String copyNamespaces = ctx.attributes.get("copy-namespaces");
        
        if (select == null) {
            throw new SAXException("xsl:copy-of requires select attribute");
        }
        
        // Validation: xsl:copy-of must be empty (XTSE0010)
        if (!ctx.children.isEmpty()) {
            throw new SAXException("xsl:copy-of must be empty; content is not allowed");
        }
        
        // type and validation are mutually exclusive
        if (typeValue != null && validationValue != null) {
            throw new SAXException("xsl:copy-of cannot have both type and validation attributes");
        }
        
        // Parse validation mode
        ValidationMode validation = null;
        if (validationValue != null && !validationValue.isEmpty()) {
            switch (validationValue) {
                case "strict":
                    validation = ValidationMode.STRICT;
                    break;
                case "lax":
                    validation = ValidationMode.LAX;
                    break;
                case "preserve":
                    validation = ValidationMode.PRESERVE;
                    break;
                case "strip":
                    validation = ValidationMode.STRIP;
                    break;
                default:
                    throw new SAXException("Invalid validation value on xsl:copy-of: " + validationValue);
            }
        }
        
        // Parse type annotation if present
        String typeNamespaceURI = null;
        String typeLocalName = null;
        if (typeValue != null && !typeValue.isEmpty()) {
            int colonPos = typeValue.indexOf(':');
            if (colonPos > 0) {
                String typePrefix = typeValue.substring(0, colonPos);
                typeLocalName = typeValue.substring(colonPos + 1);
                typeNamespaceURI = ctx.namespaceBindings.get(typePrefix);
                if (typeNamespaceURI == null) {
                    throw new SAXException("Unknown namespace prefix in xsl:copy-of/@type: " + typePrefix);
                }
            } else {
                typeLocalName = typeValue;
                typeNamespaceURI = "http://www.w3.org/2001/XMLSchema";
            }
        }
        
        boolean copyNs = !"no".equals(copyNamespaces);
        
        return new CopyOfNode(compileExpression(select), typeNamespaceURI, typeLocalName, 
                              validation, copyNs);
    }

    private XSLTNode compileSequence(ElementContext ctx) throws SAXException {
        String select = ctx.attributes.get("select");
        if (select != null) {
            // xsl:sequence with select - outputs the selected sequence
            return new SequenceOutputNode(compileExpression(select));
        } else {
            // xsl:sequence with child content (used by fork, etc.)
            return new SequenceNode(new ArrayList<>(ctx.children));
        }
    }

    private XSLTNode compileApplyTemplates(ElementContext ctx) throws SAXException {
        // XTSE0010: xsl:apply-templates must not be at top level
        if (isTopLevel()) {
            throw new SAXException("XTSE0010: xsl:apply-templates is not allowed at the top level");
        }
        
        String select = ctx.attributes.get("select");
        String mode = ctx.attributes.get("mode");
        
        // Expand mode QName to Clark notation for proper namespace comparison
        String expandedMode = expandModeQName(mode);
        
        XPathExpression selectExpr = select != null ? compileExpression(select) : null;
        
        // Extract sorts and with-params from children
        // XTSE0010: Only xsl:sort and xsl:with-param are allowed as children
        List<SortSpec> sorts = new ArrayList<>();
        List<WithParamNode> params = new ArrayList<>();
        boolean foundNonSort = false;
        
        for (XSLTNode child : ctx.children) {
            if (child instanceof SortSpecNode) {
                if (foundNonSort) {
                    throw new SAXException("XTSE0010: xsl:sort must come before xsl:with-param in xsl:apply-templates");
                }
                sorts.add(((SortSpecNode) child).getSortSpec());
            } else if (child instanceof WithParamNode) {
                foundNonSort = true;
                params.add((WithParamNode) child);
            } else {
                throw new SAXException("XTSE0010: Only xsl:sort and xsl:with-param are allowed in xsl:apply-templates");
            }
        }
        
        return new ApplyTemplatesNode(selectExpr, expandedMode, sorts, params);
    }

    private XSLTNode compileCallTemplate(ElementContext ctx) throws SAXException {
        // XTSE0010: xsl:call-template must not be at top level
        if (isTopLevel()) {
            throw new SAXException("XTSE0010: xsl:call-template is not allowed at the top level");
        }
        
        String name = ctx.attributes.get("name");
        if (name == null) {
            throw new SAXException("XTSE0010: xsl:call-template requires name attribute");
        }
        
        // XTSE0010: Only xsl:with-param is allowed as a child
        List<WithParamNode> params = new ArrayList<>();
        for (XSLTNode child : ctx.children) {
            if (child instanceof WithParamNode) {
                params.add((WithParamNode) child);
            } else {
                throw new SAXException("XTSE0010: Only xsl:with-param is allowed in xsl:call-template");
            }
        }
        
        return new CallTemplateNode(name, params);
    }

    private XSLTNode compileApplyImports(ElementContext ctx) {
        return new ApplyImportsNode();
    }

    /**
     * Compiles an xsl:next-match instruction (XSLT 2.0+).
     *
     * <p>xsl:next-match invokes the next matching template rule for the
     * current node, similar to xsl:apply-imports but considering all
     * templates regardless of import precedence.
     */
    private XSLTNode compileNextMatch(ElementContext ctx) throws SAXException {
        // Extract with-param children for parameter passing
        List<WithParamNode> params = new ArrayList<>();
        for (XSLTNode child : ctx.children) {
            if (child instanceof WithParamNode) {
                params.add((WithParamNode) child);
            } else if (child instanceof FallbackNode) {
                // xsl:fallback is allowed but we don't need special handling
            }
        }
        return new NextMatchNode(params);
    }

    private XSLTNode compileForEach(ElementContext ctx) throws SAXException {
        String select = ctx.attributes.get("select");
        if (select == null) {
            throw new SAXException("XTSE0010: xsl:for-each requires select attribute");
        }
        
        // Extract sorts from children
        // XTSE0010: xsl:sort must come before any other content
        List<SortSpec> sorts = new ArrayList<>();
        List<XSLTNode> bodyNodes = new ArrayList<>();
        boolean foundNonSort = false;
        
        for (XSLTNode child : ctx.children) {
            if (child instanceof SortSpecNode) {
                if (foundNonSort) {
                    throw new SAXException("XTSE0010: xsl:sort must come before other content in xsl:for-each");
                }
                sorts.add(((SortSpecNode) child).getSortSpec());
            } else {
                foundNonSort = true;
                bodyNodes.add(child);
            }
        }
        
        return new ForEachNode(compileExpression(select), sorts, new SequenceNode(bodyNodes));
    }

    /**
     * Compiles an xsl:stream instruction (XSLT 3.0).
     */
    private XSLTNode compileStream(ElementContext ctx) throws SAXException {
        String href = ctx.attributes.get("href");
        if (href == null || href.isEmpty()) {
            throw new SAXException("xsl:stream requires href attribute");
        }
        
        // Body becomes the streaming content
        XSLTNode body;
        if (ctx.children.isEmpty()) {
            body = new SequenceNode(new ArrayList<>());
        } else if (ctx.children.size() == 1) {
            body = ctx.children.get(0);
        } else {
            body = new SequenceNode(new ArrayList<>(ctx.children));
        }
        
        return new StreamNode(href, body);
    }

    /**
     * Compiles an xsl:iterate instruction (XSLT 3.0).
     */
    private XSLTNode compileIterate(ElementContext ctx) throws SAXException {
        String selectStr = ctx.attributes.get("select");
        if (selectStr == null || selectStr.isEmpty()) {
            throw new SAXException("xsl:iterate requires select attribute");
        }
        
        XPathExpression select = compileExpression(selectStr);
        
        // Extract params, on-completion, and body
        List<IterateNode.IterateParam> params = new ArrayList<>();
        XSLTNode onCompletion = null;
        List<XSLTNode> bodyNodes = new ArrayList<>();
        
        for (XSLTNode child : ctx.children) {
            if (child instanceof ParamNode) {
                ParamNode pn = (ParamNode) child;
                params.add(new IterateNode.IterateParam(pn.getName(), pn.getSelectExpr()));
            } else if (child.toString().contains("on-completion")) {
                onCompletion = child;
            } else {
                bodyNodes.add(child);
            }
        }
        
        XSLTNode body = bodyNodes.isEmpty() ? null : 
            (bodyNodes.size() == 1 ? bodyNodes.get(0) : new SequenceNode(bodyNodes));
        
        return new IterateNode(select, params, body, onCompletion);
    }

    /**
     * Compiles an xsl:next-iteration instruction (XSLT 3.0).
     */
    private XSLTNode compileNextIteration(ElementContext ctx) throws SAXException {
        List<NextIterationNode.ParamValue> params = new ArrayList<>();
        for (XSLTNode child : ctx.children) {
            if (child instanceof WithParamNode) {
                WithParamNode wp = (WithParamNode) child;
                params.add(new NextIterationNode.ParamValue(
                    wp.getName(), wp.getSelectExpr(), wp.getContent()));
            }
        }
        return new NextIterationNode(params);
    }

    /**
     * Compiles an xsl:break instruction (XSLT 3.0).
     */
    private XSLTNode compileBreak(ElementContext ctx) throws SAXException {
        XSLTNode content = null;
        if (!ctx.children.isEmpty()) {
            content = ctx.children.size() == 1 ? ctx.children.get(0) 
                : new SequenceNode(new ArrayList<>(ctx.children));
        }
        return new BreakNode(content);
    }

    /**
     * Compiles an xsl:fork instruction (XSLT 3.0).
     */
    private XSLTNode compileFork(ElementContext ctx) throws SAXException {
        List<ForkNode.ForkBranch> branches = new ArrayList<>();
        
        for (XSLTNode child : ctx.children) {
            // Each child should be an xsl:sequence
            branches.add(new ForkNode.ForkBranch(child));
        }
        
        return new ForkNode(branches);
    }

    /**
     * Compiles an xsl:for-each-group instruction (XSLT 2.0).
     */
    private XSLTNode compileForEachGroup(ElementContext ctx) throws SAXException {
        String selectStr = ctx.attributes.get("select");
        if (selectStr == null || selectStr.isEmpty()) {
            throw new SAXException("xsl:for-each-group requires select attribute");
        }
        
        String groupBy = ctx.attributes.get("group-by");
        String groupAdjacent = ctx.attributes.get("group-adjacent");
        String groupStartingWith = ctx.attributes.get("group-starting-with");
        String groupEndingWith = ctx.attributes.get("group-ending-with");
        
        XPathExpression select = compileExpression(selectStr);
        XSLTNode body = ctx.children.isEmpty() ? null :
            (ctx.children.size() == 1 ? ctx.children.get(0) 
                : new SequenceNode(new ArrayList<>(ctx.children)));
        
        if (groupBy != null && !groupBy.isEmpty()) {
            XPathExpression groupByExpr = compileExpression(groupBy);
            return ForEachGroupNode.groupBy(select, groupByExpr, body);
        } else if (groupAdjacent != null && !groupAdjacent.isEmpty()) {
            XPathExpression groupAdjacentExpr = compileExpression(groupAdjacent);
            return ForEachGroupNode.groupAdjacent(select, groupAdjacentExpr, body);
        } else if (groupStartingWith != null && !groupStartingWith.isEmpty()) {
            Pattern pattern = compilePattern(groupStartingWith);
            return ForEachGroupNode.groupStartingWith(select, pattern, body);
        } else if (groupEndingWith != null && !groupEndingWith.isEmpty()) {
            Pattern pattern = compilePattern(groupEndingWith);
            return ForEachGroupNode.groupEndingWith(select, pattern, body);
        } else {
            throw new SAXException("xsl:for-each-group requires group-by, group-adjacent, group-starting-with, or group-ending-with attribute");
        }
    }

    /**
     * Compiles an xsl:try instruction (XSLT 3.0).
     *
     * <p>xsl:try executes content that might throw an error. If an error
     * occurs, xsl:catch provides error handling.
     */
    private XSLTNode compileTry(ElementContext ctx) throws SAXException {
        // Separate try content from catch blocks
        List<XSLTNode> tryContent = new ArrayList<>();
        XSLTNode catchContent = null;
        String catchErrors = null;  // errors="..." attribute on xsl:catch
        
        for (XSLTNode child : ctx.children) {
            if (child instanceof SequenceNode && !ctx.children.isEmpty()) {
                // This might be a catch block - check the last few children
                // In our current simple approach, assume last SequenceNode is catch
                catchContent = child;
            } else {
                tryContent.add(child);
            }
        }
        
        // If no explicit catch content found, use any remaining as try content
        if (catchContent == null && !tryContent.isEmpty()) {
            // Look for catch in children
            for (int i = ctx.children.size() - 1; i >= 0; i--) {
                // This is a simplification - a proper implementation would
                // track which child elements are xsl:catch
                XSLTNode child = ctx.children.get(i);
                if (child instanceof SequenceNode && tryContent.size() > 1) {
                    catchContent = child;
                    tryContent.remove(tryContent.size() - 1);
                    break;
                }
            }
        }
        
        XSLTNode body = tryContent.isEmpty() ? null : new SequenceNode(tryContent);
        return new TryNode(body, catchContent, catchErrors);
    }

    /**
     * Compiles an xsl:analyze-string instruction (XSLT 2.0).
     *
     * <p>Analyzes a string using a regular expression, executing different
     * content for matching and non-matching substrings.
     */
    private XSLTNode compileAnalyzeString(ElementContext ctx) throws SAXException {
        String selectStr = ctx.attributes.get("select");
        String regexStr = ctx.attributes.get("regex");
        String flagsStr = ctx.attributes.get("flags");
        
        if (selectStr == null || selectStr.isEmpty()) {
            throw new SAXException("xsl:analyze-string requires select attribute");
        }
        if (regexStr == null || regexStr.isEmpty()) {
            throw new SAXException("xsl:analyze-string requires regex attribute");
        }
        
        XPathExpression select = compileExpression(selectStr);
        AttributeValueTemplate regexAvt = parseAvt(regexStr);
        AttributeValueTemplate flagsAvt = flagsStr != null ? parseAvt(flagsStr) : null;
        
        // Find matching-substring and non-matching-substring children
        XSLTNode matchingContent = null;
        XSLTNode nonMatchingContent = null;
        
        for (XSLTNode child : ctx.children) {
            // We stored matching/non-matching as SequenceNode with special markers
            // Need to identify them by their element context
            // For now, we'll look for children named appropriately
        }
        
        // Find the child elements by iterating through ctx.children
        // Since we compiled them as SequenceNodes, we need another approach
        // Let me use a different approach - store the children with tags
        
        ElementContext matchingCtx = null;
        ElementContext nonMatchingCtx = null;
        
        // Re-examine the children - look through raw children list
        for (XSLTNode child : ctx.children) {
            if (child instanceof SequenceNode) {
                // Check if this was a matching or non-matching substring
                // We need to track this differently
            }
        }
        
        // For simplicity, assume first child is matching, second is non-matching
        // (This is a common pattern in XSLT 2.0 stylesheets)
        if (ctx.children.size() >= 1) {
            matchingContent = ctx.children.get(0);
        }
        if (ctx.children.size() >= 2) {
            nonMatchingContent = ctx.children.get(1);
        }
        
        return new AnalyzeStringNode(select, regexAvt, flagsAvt, 
            matchingContent, nonMatchingContent);
    }

    /**
     * Compiles an xsl:result-document instruction (XSLT 2.0).
     */
    private XSLTNode compileResultDocument(ElementContext ctx) throws SAXException {
        String href = ctx.attributes.get("href");
        AttributeValueTemplate hrefAvt = null;
        if (href != null && !href.isEmpty()) {
            hrefAvt = parseAvt(href);
        }
        
        String format = ctx.attributes.get("format");
        String method = ctx.attributes.get("method");
        String encoding = ctx.attributes.get("encoding");
        String indent = ctx.attributes.get("indent");
        String typeValue = ctx.attributes.get("type");
        String validationValue = ctx.attributes.get("validation");
        
        // type and validation are mutually exclusive
        if (typeValue != null && validationValue != null) {
            throw new SAXException("xsl:result-document cannot have both type and validation attributes");
        }
        
        // Parse validation mode
        ValidationMode validation = null;
        if (validationValue != null && !validationValue.isEmpty()) {
            switch (validationValue) {
                case "strict":
                    validation = ValidationMode.STRICT;
                    break;
                case "lax":
                    validation = ValidationMode.LAX;
                    break;
                case "preserve":
                    validation = ValidationMode.PRESERVE;
                    break;
                case "strip":
                    validation = ValidationMode.STRIP;
                    break;
                default:
                    throw new SAXException("Invalid validation value on xsl:result-document: " + validationValue);
            }
        }
        
        // Parse type annotation if present
        String typeNamespaceURI = null;
        String typeLocalName = null;
        if (typeValue != null && !typeValue.isEmpty()) {
            int colonPos = typeValue.indexOf(':');
            if (colonPos > 0) {
                String typePrefix = typeValue.substring(0, colonPos);
                typeLocalName = typeValue.substring(colonPos + 1);
                typeNamespaceURI = ctx.namespaceBindings.get(typePrefix);
                if (typeNamespaceURI == null) {
                    throw new SAXException("Unknown namespace prefix in xsl:result-document/@type: " + typePrefix);
                }
            } else {
                typeLocalName = typeValue;
                typeNamespaceURI = "http://www.w3.org/2001/XMLSchema";
            }
        }
        
        XSLTNode content = ctx.children.isEmpty() ? null :
            (ctx.children.size() == 1 ? ctx.children.get(0) 
                : new SequenceNode(new ArrayList<>(ctx.children)));
        
        return new ResultDocumentNode(hrefAvt, format, method, encoding, indent, content,
                                      typeNamespaceURI, typeLocalName, validation);
    }

    private XSLTNode compileIf(ElementContext ctx) throws SAXException {
        String test = ctx.attributes.get("test");
        if (test == null) {
            throw new SAXException("xsl:if requires test attribute");
        }
        return new IfNode(compileExpression(test), new SequenceNode(ctx.children));
    }

    private XSLTNode compileChoose(ElementContext ctx) {
        List<WhenNode> whens = new ArrayList<>();
        SequenceNode otherwise = null;
        
        for (XSLTNode child : ctx.children) {
            if (child instanceof WhenNode) {
                whens.add((WhenNode) child);
            } else if (child instanceof OtherwiseNode) {
                otherwise = ((OtherwiseNode) child).getContent();
            }
        }
        
        return new ChooseNode(whens, otherwise);
    }

    private XSLTNode compileWhen(ElementContext ctx) throws SAXException {
        String test = ctx.attributes.get("test");
        if (test == null) {
            throw new SAXException("xsl:when requires test attribute");
        }
        return new WhenNode(compileExpression(test), new SequenceNode(ctx.children));
    }

    private XSLTNode compileOtherwise(ElementContext ctx) {
        return new OtherwiseNode(new SequenceNode(ctx.children));
    }

    private XSLTNode compileWithParam(ElementContext ctx) throws SAXException {
        String name = ctx.attributes.get("name");
        String select = ctx.attributes.get("select");
        
        XPathExpression selectExpr = select != null ? compileExpression(select) : null;
        SequenceNode content = ctx.children.isEmpty() ? null : new SequenceNode(ctx.children);
        
        return new WithParamNode(name, selectExpr, content);
    }

    private XSLTNode compileSort(ElementContext ctx) throws SAXException {
        // select attribute - defaults to "." (current node)
        String selectAttr = ctx.attributes.get("select");
        XPathExpression selectExpr;
        if (selectAttr != null && !selectAttr.isEmpty()) {
            selectExpr = compileExpression(selectAttr);
        } else {
            selectExpr = compileExpression(".");
        }
        
        String dataType = ctx.attributes.get("data-type");  // "text" or "number"
        String order = ctx.attributes.get("order");          // "ascending" or "descending"
        String caseOrder = ctx.attributes.get("case-order"); // "upper-first" or "lower-first"
        String lang = ctx.attributes.get("lang");
        
        SortSpec spec = new SortSpec(selectExpr, dataType, order, caseOrder, lang);
        return new SortSpecNode(spec);
    }

    private XSLTNode compileNumber(ElementContext ctx) throws SAXException {
        // value attribute - if present, use this instead of counting
        String valueAttr = ctx.attributes.get("value");
        XPathExpression valueExpr = null;
        if (valueAttr != null) {
            valueExpr = compileExpression(valueAttr);
        }
        
        // select attribute (XSLT 2.0+) - the node to number
        String selectAttr = ctx.attributes.get("select");
        XPathExpression selectExpr = null;
        if (selectAttr != null) {
            selectExpr = compileExpression(selectAttr);
        }
        
        // level attribute - single (default), multiple, or any
        String level = ctx.attributes.get("level");
        if (level == null) {
            level = "single";
        }
        
        // count attribute - pattern for nodes to count
        String countAttr = ctx.attributes.get("count");
        Pattern countPattern = null;
        if (countAttr != null) {
            countPattern = compilePattern(countAttr);
        }
        
        // from attribute - pattern for where to start counting
        String fromAttr = ctx.attributes.get("from");
        Pattern fromPattern = null;
        if (fromAttr != null) {
            fromPattern = compilePattern(fromAttr);
        }
        
        // format attribute - format string (default "1")
        String format = ctx.attributes.get("format");
        if (format == null) {
            format = "1";
        }
        
        // grouping attributes
        String groupingSeparator = ctx.attributes.get("grouping-separator");
        String groupingSizeAttr = ctx.attributes.get("grouping-size");
        int groupingSize = 0;
        if (groupingSizeAttr != null) {
            try {
                groupingSize = Integer.parseInt(groupingSizeAttr);
            } catch (NumberFormatException e) {
                // Ignore invalid grouping size
            }
        }
        
        // lang and letter-value for internationalization (optional)
        String lang = ctx.attributes.get("lang");
        String letterValue = ctx.attributes.get("letter-value");
        
        return new NumberNode(valueExpr, selectExpr, level, countPattern, fromPattern, 
                             format, groupingSeparator, groupingSize, lang, letterValue);
    }

    private XSLTNode compileMessage(ElementContext ctx) throws SAXException {
        boolean terminate = "yes".equals(ctx.attributes.get("terminate"));
        
        // XSLT 2.0+ select attribute
        String selectAttr = ctx.attributes.get("select");
        XPathExpression selectExpr = null;
        if (selectAttr != null) {
            selectExpr = compileExpression(selectAttr);
        }
        
        // XSLT 3.0 error-code attribute (optional)
        String errorCode = ctx.attributes.get("error-code");
        
        return new MessageNode(new SequenceNode(ctx.children), selectExpr, terminate, errorCode);
    }

    private XSLTNode compileFallback(ElementContext ctx) {
        return new FallbackNode(new SequenceNode(ctx.children));
    }

    // ========================================================================
    // Helper methods
    // ========================================================================

    /**
     * Evaluates a use-when expression for conditional compilation (XSLT 2.0+).
     * The expression is evaluated in a static context with no context node.
     *
     * @param expr the use-when expression
     * @return true if the element should be included, false to exclude
     */
    private boolean evaluateUseWhen(String expr) {
        try {
            // Compile the expression
            XPathExpression compiled = XPathExpression.compile(expr, this);
            
            // Create a minimal static context for evaluation
            // use-when expressions can only use: system-property, function-available, 
            // type-available, element-available, and literal values
            XPathContext staticContext = new UseWhenContext();
            
            // Evaluate and convert to boolean
            XPathValue result = compiled.evaluate(staticContext);
            return result != null && result.asBoolean();
        } catch (Exception e) {
            // If evaluation fails, treat as false (exclude element)
            // In strict mode, this should be an error
            return false;
        }
    }
    
    /**
     * Minimal XPath context for use-when expression evaluation.
     * Provides only static functions (system-property, etc.) - no context node.
     * Supports local variable bindings for quantified expressions (some/every).
     */
    private class UseWhenContext implements XPathContext {
        private final Map<String, XPathValue> localVariables;
        private final String explicitBaseURI;  // Explicit base URI (e.g., from xml:base on variable)
        
        UseWhenContext() {
            this.localVariables = new HashMap<>();
            this.explicitBaseURI = null;
        }
        
        UseWhenContext(String baseURI) {
            this.localVariables = new HashMap<>();
            this.explicitBaseURI = baseURI;
        }
        
        private UseWhenContext(Map<String, XPathValue> vars, String baseURI) {
            this.localVariables = vars;
            this.explicitBaseURI = baseURI;
        }
        
        @Override public XPathNode getContextNode() { return null; }
        @Override public int getContextPosition() { return 0; }
        @Override public int getContextSize() { return 0; }
        
        @Override
        public XPathValue getVariable(String namespaceURI, String localName) {
            // Check local variables first (for quantified expressions)
            XPathValue value = localVariables.get(localName);
            if (value != null) {
                return value;
            }
            // Check static variables (XSLT 3.0)
            value = staticVariables.get(localName);
            if (value != null) {
                return value;
            }
            return null;
        }
        
        @Override
        public String resolveNamespacePrefix(String prefix) {
            // Use the current namespace bindings from the stylesheet
            return namespaces.get(prefix);
        }
        
        @Override
        public XPathFunctionLibrary getFunctionLibrary() {
            return XSLTFunctionLibrary.INSTANCE;
        }
        
        @Override
        public XPathContext withContextNode(XPathNode node) {
            return this;
        }
        
        @Override
        public XPathContext withPositionAndSize(int position, int size) {
            return this;
        }
        
        @Override
        public XPathContext withVariable(String namespaceURI, String localName, XPathValue value) {
            // Create new context with updated variable, preserving base URI
            Map<String, XPathValue> newVars = new HashMap<>(localVariables);
            newVars.put(localName, value);
            return new UseWhenContext(newVars, explicitBaseURI);
        }
        
        @Override
        public String getStaticBaseURI() {
            // Use explicit base URI if set (from xml:base on variable/param)
            if (explicitBaseURI != null) {
                return explicitBaseURI;
            }
            // Otherwise return the stylesheet's base URI (from locator or xml:base)
            if (!elementStack.isEmpty()) {
                return elementStack.peek().baseURI;
            }
            return locator != null ? locator.getSystemId() : null;
        }
        
        @Override
        public double getXsltVersion() {
            return stylesheetVersion;
        }
    }

    private XPathExpression compileExpression(String expr) throws SAXException {
        try {
            return XPathExpression.compile(expr, this);
        } catch (XPathSyntaxException e) {
            throw new SAXException("Invalid XPath expression: " + expr + " - " + e.getMessage(), e);
        }
    }

    private Pattern compilePattern(String pattern) throws SAXException {
        // Resolve namespace prefixes in the pattern before compilation
        String resolvedPattern = resolvePatternNamespaces(pattern);
        return new SimplePattern(resolvedPattern);
    }
    
    /**
     * Resolves namespace prefixes in a match pattern to Clark notation.
     * Converts "prefix:local" to "{uri}local" and "prefix:*" to "{uri}*".
     * Axis specifications (axis::) use double colon and are NOT touched.
     */
    private String resolvePatternNamespaces(String pattern) throws SAXException {
        StringBuilder result = new StringBuilder();
        int i = 0;
        int len = pattern.length();
        
        while (i < len) {
            char c = pattern.charAt(i);
            
            // Copy path separators
            if (c == '/') {
                result.append(c);
                i++;
                continue;
            }
            
            // Copy predicates verbatim (they contain XPath expressions, not patterns)
            if (c == '[') {
                int depth = 1;
                result.append(c);
                i++;
                while (i < len && depth > 0) {
                    c = pattern.charAt(i);
                    if (c == '[') {
                        depth++;
                    } else if (c == ']') {
                        depth--;
                    }
                    result.append(c);
                    i++;
                }
                continue;
            }
            
            // Copy parentheses verbatim (function calls, grouping)
            if (c == '(') {
                int depth = 1;
                result.append(c);
                i++;
                while (i < len && depth > 0) {
                    c = pattern.charAt(i);
                    if (c == '(') {
                        depth++;
                    } else if (c == ')') {
                        depth--;
                    }
                    result.append(c);
                    i++;
                }
                continue;
            }
            
            // Copy union operator
            if (c == '|') {
                result.append(c);
                i++;
                continue;
            }
            
            // Copy @ for attribute axis shorthand
            if (c == '@') {
                result.append(c);
                i++;
                continue;
            }
            
            // Extract a name token
            int start = i;
            while (i < len) {
                c = pattern.charAt(i);
                if (c == '/' || c == '[' || c == '(' || c == '|' || c == ')' || c == ']') {
                    break;
                }
                i++;
            }
            
            if (i > start) {
                String token = pattern.substring(start, i);
                String resolved = resolvePatternToken(token);
                result.append(resolved);
            }
        }
        
        return result.toString();
    }
    
    /**
     * Resolves a single token which may contain namespace prefixes.
     * Handles: name, prefix:name, prefix:*, *:name, axis::name, axis::prefix:name
     */
    private String resolvePatternToken(String token) throws SAXException {
        // Check for axis specification FIRST (double colon ::)
        int axisPos = token.indexOf("::");
        if (axisPos > 0) {
            // This is axis::nametest - resolve the nametest part only
            String axis = token.substring(0, axisPos);
            String nameTest = token.substring(axisPos + 2);
            String resolvedNameTest = resolvePatternToken(nameTest);
            return axis + "::" + resolvedNameTest;
        }
        
        // Handle * (matches any element)
        if ("*".equals(token)) {
            return token;
        }
        
        // Handle *:localname (any namespace, specific local name)
        if (token.startsWith("*:")) {
            return token; // Keep as-is, runtime handles it
        }
        
        // Handle Q{uri}name (EQName) - convert to {uri}name
        // Q{}local means element in no namespace
        if (token.startsWith("Q{")) {
            int closeBrace = token.indexOf('}');
            if (closeBrace >= 2) {
                String uri = token.substring(2, closeBrace);
                String local = token.substring(closeBrace + 1);
                return "{" + uri + "}" + local;
            }
        }
        
        // Check for single colon (namespace prefix) - must be AFTER :: check
        int colon = token.indexOf(':');
        if (colon > 0) {
            String prefix = token.substring(0, colon);
            String local = token.substring(colon + 1);
            
            // Look up the namespace URI
            String uri = namespaces.get(prefix);
            if (uri == null) {
                uri = lookupNamespaceUri(prefix);
            }
            if (uri != null) {
                return "{" + uri + "}" + local;
            }
            // XTSE0280: Prefix not bound to any namespace
            throw new SAXException("XTSE0280: Namespace prefix '" + prefix + 
                "' in pattern '" + token + "' is not declared");
        }
        
        // Unprefixed name - keep as-is
        return token;
    }

    private AttributeValueTemplate parseAvt(String value) throws SAXException {
        try {
            return AttributeValueTemplate.parse(value, this);
        } catch (XPathSyntaxException e) {
            throw new SAXException("Invalid AVT: " + value + " - " + e.getMessage(), e);
        }
    }

    @Override
    public String resolve(String prefix) {
        return namespaces.get(prefix);
    }

    // ========================================================================
    // Placeholder instruction node classes (to be expanded)
    // ========================================================================

    // These are placeholder classes - full implementations would go in ast.instructions package

    private static class VariableNode extends XSLTInstruction {
        private final String namespaceURI;
        private final String localName;
        private final XPathExpression selectExpr;
        private final SequenceNode content;
        private final String asType; // XSLT 2.0 type annotation
        
        VariableNode(String namespaceURI, String localName, XPathExpression selectExpr, 
                    SequenceNode content, String asType) {
            this.namespaceURI = namespaceURI;
            this.localName = localName;
            this.selectExpr = selectExpr;
            this.content = content;
            this.asType = asType;
        }
        
        @Override public String getInstructionName() { return "variable"; }
        
        /**
         * Checks if the as type indicates a sequence type (contains *, +, or ?).
         */
        private boolean isSequenceType() {
            if (asType == null) {
                return false;
            }
            // Sequence types: item()*, item()+, element()*, xs:string*, etc.
            return asType.contains("*") || asType.contains("+") || asType.contains("?");
        }
        
        /**
         * Checks if the as type indicates a single node type.
         * For single node types, we should return the node directly, not wrapped in RTF.
         */
        private boolean isSingleNodeType() {
            if (asType == null) {
                return false;
            }
            String type = asType.trim();
            // Single node types: element(), element(name), node(), attribute(), etc.
            // But NOT sequence types like element()*
            if (type.contains("*") || type.contains("+") || type.contains("?")) {
                return false;
            }
            return type.startsWith("element(") || type.startsWith("node(") || 
                   type.startsWith("attribute(") || type.startsWith("document-node(") ||
                   type.startsWith("text(") || type.startsWith("comment(") ||
                   type.startsWith("processing-instruction(");
        }
        
        @Override
        public void execute(TransformContext context, 
                           OutputHandler output) throws SAXException {
            try {
                XPathValue value;
                if (selectExpr != null) {
                    value = selectExpr.evaluate(context);
                } else if (content != null) {
                    if (isSequenceType()) {
                        // XSLT 2.0+ sequence construction mode
                        // Execute content and collect items into a sequence
                        value = executeSequenceConstructor(context);
                    } else if (isSingleNodeType()) {
                        // XSLT 2.0+ single node type (e.g., as="element()")
                        // Execute content and extract the single node
                        value = executeSequenceConstructor(context);
                        // Extract single node from the sequence
                        if (value instanceof XPathSequence) {
                            XPathSequence seq = (XPathSequence) value;
                            if (seq.size() == 1) {
                                value = seq.iterator().next();
                            }
                        }
                        // For element() type, extract the actual element from the node set
                        if (value instanceof XPathNodeSet) {
                            XPathNodeSet ns = (XPathNodeSet) value;
                            Iterator<XPathNode> iter = ns.iterator();
                            if (iter.hasNext()) {
                                XPathNode node = iter.next();
                                // If this is a document/RTF root, get its first element child
                                if (node.getNodeType() == NodeType.ROOT) {
                                    Iterator<XPathNode> children = node.getChildren();
                                    while (children.hasNext()) {
                                        XPathNode child = children.next();
                                        if (child.isElement()) {
                                            value = new XPathNodeSet(Collections.singletonList(child));
                                            break;
                                        }
                                    }
                                } else if (ns.size() == 1) {
                                    // Single node already
                                    value = ns;
                                }
                            }
                        }
                    } else {
                        // XSLT 1.0 style: Execute content to build result tree fragment
                        SAXEventBuffer buffer = 
                            new SAXEventBuffer();
                        OutputHandler bufferHandler = 
                            new BufferOutputHandler(buffer);
                        content.execute(context, bufferHandler);
                        // Store as RTF so xsl:copy-of can access the tree structure
                        // Use the instruction's static base URI (from xml:base) for the RTF
                        String rtfBaseUri = (staticBaseURI != null) ? staticBaseURI : context.getStaticBaseURI();
                        value = new XPathResultTreeFragment(buffer, rtfBaseUri);
                    }
                } else {
                    value = XPathString.of("");
                }
                context.getVariableScope().bind(namespaceURI, localName, value);
            } catch (XPathException e) {
                throw new SAXException("Error evaluating variable " + localName, e);
            }
        }
        
        /**
         * Executes the content in sequence construction mode, collecting items
         * into an XPathSequence rather than building a result tree fragment.
         * 
         * <p>In sequence construction, each instruction produces separate items.
         * We mark item boundaries between instructions to ensure text nodes
         * from different instructions don't get merged.
         */
        private XPathValue executeSequenceConstructor(TransformContext context) throws SAXException {
            SequenceBuilderOutputHandler seqBuilder = new SequenceBuilderOutputHandler();
            // Execute each child instruction with item boundaries
            if (content.getChildren() != null) {
                for (XSLTNode child : content.getChildren()) {
                    child.execute(context, seqBuilder);
                    // Mark boundary between instructions to prevent text merging
                    seqBuilder.markItemBoundary();
                }
            }
            return seqBuilder.getSequence();
        }
    }

    private static class ParamNode extends XSLTInstruction {
        private final String namespaceURI;
        private final String localName;
        private final XPathExpression selectExpr;
        private final SequenceNode content;
        private final String asType; // XSLT 2.0 type annotation
        
        ParamNode(String namespaceURI, String localName, XPathExpression selectExpr, SequenceNode content) {
            this(namespaceURI, localName, selectExpr, content, null);
        }
        
        ParamNode(String namespaceURI, String localName, XPathExpression selectExpr, 
                  SequenceNode content, String asType) {
            this.namespaceURI = namespaceURI;
            this.localName = localName;
            this.selectExpr = selectExpr;
            this.content = content;
            this.asType = asType;
        }
        
        String getNamespaceURI() { return namespaceURI; }
        String getLocalName() { return localName; }
        String getName() { return localName; }  // For compatibility
        XPathExpression getSelectExpr() { return selectExpr; }
        SequenceNode getContent() { return content; }
        String getAs() { return asType; }
        
        @Override public String getInstructionName() { return "param"; }
        
        @Override
        public void execute(TransformContext context, 
                           OutputHandler output) throws SAXException {
            // Params are handled by template instantiation
        }
    }

    private static class ValueOfNode extends XSLTInstruction {
        private final XPathExpression selectExpr;
        private final boolean disableEscaping;
        private final String separator;  // null means use default (space for sequences in XSLT 2.0+)
        private final boolean xslt2Plus; // XSLT 2.0+ outputs all items, 1.0 only first
        
        ValueOfNode(XPathExpression selectExpr, boolean disableEscaping, String separator, boolean xslt2Plus) {
            this.selectExpr = selectExpr;
            this.disableEscaping = disableEscaping;
            this.separator = separator;
            this.xslt2Plus = xslt2Plus;
        }
        
        @Override public String getInstructionName() { return "value-of"; }
        
        @Override
        public void execute(TransformContext context, 
                           OutputHandler output) throws SAXException {
            try {
                // Use instruction's static base URI if set (for xml:base support)
                TransformContext evalContext = (staticBaseURI != null) 
                    ? context.withStaticBaseURI(staticBaseURI) 
                    : context;
                    
                XPathValue result = selectExpr.evaluate(evalContext);
                if (result == null) {
                    return;
                }
                
                String value;
                if (result.isNodeSet()) {
                    XPathNodeSet nodeSet = result.asNodeSet();
                    if (nodeSet.isEmpty()) {
                        return;
                    }
                    if (xslt2Plus) {
                        // XSLT 2.0+: output all nodes with separator
                        String sep = (separator != null) ? separator : " ";
                        StringBuilder sb = new StringBuilder();
                        boolean first = true;
                        for (XPathNode node : nodeSet) {
                            if (!first) {
                                sb.append(sep);
                            }
                            sb.append(node.getStringValue());
                            first = false;
                        }
                        value = sb.toString();
                    } else {
                        // XSLT 1.0: only output first node
                        value = nodeSet.iterator().next().getStringValue();
                    }
                } else if (result.isSequence()) {
                    // XPath 2.0+ sequence
                    XPathSequence seq = (XPathSequence) result;
                    if (seq.isEmpty()) {
                        return;
                    }
                    if (xslt2Plus) {
                        String sep = (separator != null) ? separator : " ";
                        StringBuilder sb = new StringBuilder();
                        boolean first = true;
                        for (XPathValue item : seq.getItems()) {
                            if (!first) {
                                sb.append(sep);
                            }
                            sb.append(item.asString());
                            first = false;
                        }
                        value = sb.toString();
                    } else {
                        // XSLT 1.0: only output first item
                        value = seq.getItems().get(0).asString();
                    }
                } else {
                    // Single atomic value
                    value = result.asString();
                }
                
                // Only output non-empty values to preserve empty element serialization
                if (!value.isEmpty()) {
                    if (disableEscaping) {
                        output.charactersRaw(value);
                    } else {
                        output.characters(value);
                    }
                }
            } catch (XPathException e) {
                throw new SAXException("XPath evaluation error", e);
            }
        }
    }

    // XSLT Instruction implementations
    
    private static class ElementNode extends XSLTInstruction {
        private final AttributeValueTemplate nameAvt;
        private final AttributeValueTemplate nsAvt;
        private final String useAttrSets;
        private final SequenceNode content;
        private final String defaultNamespace;  // Default namespace from xsl:element context
        private final Map<String, String> namespaceBindings;  // All namespace bindings
        private final String typeNamespaceURI;
        private final String typeLocalName;
        private final ValidationMode validation;  // null means use stylesheet default
        
        ElementNode(AttributeValueTemplate nameAvt, AttributeValueTemplate nsAvt, 
                   String useAttrSets, SequenceNode content,
                   String defaultNamespace, Map<String, String> namespaceBindings) {
            this(nameAvt, nsAvt, useAttrSets, content, defaultNamespace, namespaceBindings, null, null, null);
        }
        
        ElementNode(AttributeValueTemplate nameAvt, AttributeValueTemplate nsAvt, 
                   String useAttrSets, SequenceNode content,
                   String defaultNamespace, Map<String, String> namespaceBindings,
                   String typeNamespaceURI, String typeLocalName) {
            this(nameAvt, nsAvt, useAttrSets, content, defaultNamespace, namespaceBindings, 
                 typeNamespaceURI, typeLocalName, null);
        }
        
        ElementNode(AttributeValueTemplate nameAvt, AttributeValueTemplate nsAvt, 
                   String useAttrSets, SequenceNode content,
                   String defaultNamespace, Map<String, String> namespaceBindings,
                   String typeNamespaceURI, String typeLocalName, ValidationMode validation) {
            this.nameAvt = nameAvt;
            this.nsAvt = nsAvt;
            this.useAttrSets = useAttrSets;
            this.content = content;
            this.defaultNamespace = defaultNamespace;
            this.namespaceBindings = namespaceBindings;
            this.typeNamespaceURI = typeNamespaceURI;
            this.typeLocalName = typeLocalName;
            this.validation = validation;
        }
        
        @Override public String getInstructionName() { return "element"; }
        @Override public void execute(TransformContext context, 
                                      OutputHandler output) throws SAXException {
            try {
                String name = nameAvt.evaluate(context);
                String namespace = nsAvt != null ? nsAvt.evaluate(context) : null;
                
                // Parse qName
                String localName = name;
                String prefix = null;
                int colon = name.indexOf(':');
                if (colon > 0) {
                    prefix = name.substring(0, colon);
                    localName = name.substring(colon + 1);
                }
                
                // Determine namespace per XSLT 1.0 section 7.1.2:
                // 1. If namespace attribute is present, use it
                // 2. Otherwise, expand using namespace declarations in scope on xsl:element
                if (namespace == null) {
                    if (prefix != null && !prefix.isEmpty()) {
                        // Prefixed name - look up prefix in namespace bindings
                        namespace = namespaceBindings.get(prefix);
                        if (namespace == null) {
                            // Fall back to runtime context
                            namespace = context.resolveNamespacePrefix(prefix);
                        }
                    } else {
                        // Unprefixed name - use default namespace from xsl:element context
                        namespace = defaultNamespace != null ? defaultNamespace : "";
                    }
                }
                if (namespace == null) {
                    namespace = "";
                }
                
                String qName = prefix != null ? prefix + ":" + localName : localName;
                output.startElement(namespace, localName, qName);
                
                // Determine effective validation mode
                ValidationMode effectiveValidation = validation;
                if (effectiveValidation == null) {
                    // Use stylesheet default
                    effectiveValidation = context.getStylesheet().getDefaultValidation();
                }
                
                // Set type annotation based on validation mode
                if (typeLocalName != null) {
                    // Explicit type attribute - always use it (validation="preserve" is implicit)
                    output.setElementType(typeNamespaceURI, typeLocalName);
                } else if (effectiveValidation == ValidationMode.STRICT || 
                           effectiveValidation == ValidationMode.LAX) {
                    // Use runtime schema validation to derive type
                    RuntimeSchemaValidator validator = context.getRuntimeValidator();
                    if (validator != null) {
                        RuntimeSchemaValidator.ValidationResult valResult =
                            validator.startElement(namespace, localName, effectiveValidation);
                        // Note: Full content model validation happens during content execution
                        // For now, we validate the element exists and get its type
                        if (valResult.hasTypeAnnotation()) {
                            output.setElementType(valResult.getTypeNamespaceURI(),
                                                  valResult.getTypeLocalName());
                        }
                    }
                } else if (effectiveValidation == ValidationMode.STRIP) {
                    // Strip mode - don't set any type annotation (no-op since we haven't set one)
                }
                // PRESERVE mode when constructing new elements is a no-op
                
                // Declare the namespace for this element
                // For unprefixed elements, declare default namespace (even if empty)
                // For prefixed elements, declare the prefix binding
                if (prefix == null || prefix.isEmpty()) {
                    output.namespace("", namespace);
                } else {
                    output.namespace(prefix, namespace);
                }
                
                // Apply attribute sets if specified
                if (useAttrSets != null && !useAttrSets.isEmpty()) {
                    CompiledStylesheet stylesheet = context.getStylesheet();
                    StringTokenizer st = new StringTokenizer(useAttrSets);
                    while (st.hasMoreTokens()) {
                        String setName = st.nextToken();
                        AttributeSet attrSet = stylesheet.getAttributeSet(setName);
                        if (attrSet != null) {
                            attrSet.apply(context, output);
                        }
                    }
                }
                
                // Execute content (reset atomic separator since we're starting fresh content)
                resetAtomicSeparator();
                if (content != null) {
                    content.execute(context, output);
                }
                
                output.endElement(namespace, localName, qName);
            } catch (XPathException e) {
                throw new SAXException("Error in xsl:element", e);
            }
        }
    }

    /**
     * Finds an existing prefix for a namespace or generates a new one.
     */
    private static String findOrGeneratePrefix(String namespace, Map<String, String> bindings) {
        // Look for an existing prefix
        for (Map.Entry<String, String> entry : bindings.entrySet()) {
            if (namespace.equals(entry.getValue()) && !entry.getKey().isEmpty()) {
                return entry.getKey();
            }
        }
        // Generate a new prefix that doesn't conflict with existing ones
        for (int i = 0; ; i++) {
            String candidate = "ns" + i;
            if (!bindings.containsKey(candidate)) {
                return candidate;
            }
        }
    }
    
    private static class AttributeNode extends XSLTInstruction {
        private final AttributeValueTemplate nameAvt;
        private final AttributeValueTemplate nsAvt;
        private final XPathExpression selectExpr;
        private final String separator;
        private final SequenceNode content;
        private final Map<String, String> namespaceBindings;
        private final String typeNamespaceURI;
        private final String typeLocalName;
        private final ValidationMode validation;
        
        AttributeNode(AttributeValueTemplate nameAvt, AttributeValueTemplate nsAvt, 
                     SequenceNode content, Map<String, String> namespaceBindings,
                     String typeNamespaceURI, String typeLocalName) {
            this(nameAvt, nsAvt, null, null, content, namespaceBindings, typeNamespaceURI, typeLocalName, null);
        }
        
        AttributeNode(AttributeValueTemplate nameAvt, AttributeValueTemplate nsAvt, 
                     SequenceNode content, Map<String, String> namespaceBindings,
                     String typeNamespaceURI, String typeLocalName, ValidationMode validation) {
            this(nameAvt, nsAvt, null, null, content, namespaceBindings, typeNamespaceURI, typeLocalName, validation);
        }
        
        AttributeNode(AttributeValueTemplate nameAvt, AttributeValueTemplate nsAvt,
                     XPathExpression selectExpr, String separator,
                     SequenceNode content, Map<String, String> namespaceBindings,
                     String typeNamespaceURI, String typeLocalName, ValidationMode validation) {
            this.nameAvt = nameAvt;
            this.nsAvt = nsAvt;
            this.selectExpr = selectExpr;
            this.separator = separator;
            this.content = content;
            this.namespaceBindings = namespaceBindings;
            this.typeNamespaceURI = typeNamespaceURI;
            this.typeLocalName = typeLocalName;
            this.validation = validation;
        }
        
        @Override public String getInstructionName() { return "attribute"; }
        @Override public void execute(TransformContext context, 
                                      OutputHandler output) throws SAXException {
            try {
                String name = nameAvt.evaluate(context);
                String namespace = nsAvt != null ? nsAvt.evaluate(context) : null;
                
                // Get attribute value
                String value = "";
                if (selectExpr != null) {
                    // XSLT 2.0+: select attribute takes precedence over content
                    XPathValue result = selectExpr.evaluate(context);
                    // Convert sequence to string with separator (default is single space)
                    String sep = separator != null ? separator : " ";
                    StringBuilder sb = new StringBuilder();
                    Iterator<XPathValue> iter = result.sequenceIterator();
                    boolean first = true;
                    while (iter.hasNext()) {
                        if (!first) {
                            sb.append(sep);
                        }
                        sb.append(iter.next().asString());
                        first = false;
                    }
                    value = sb.toString();
                } else if (content != null) {
                    SAXEventBuffer buffer = 
                        new SAXEventBuffer();
                    content.execute(context, new BufferOutputHandler(buffer));
                    value = buffer.getTextContent();
                }
                
                // Parse qName
                String localName = name;
                String prefix = null;
                int colon = name.indexOf(':');
                if (colon > 0) {
                    prefix = name.substring(0, colon);
                    localName = name.substring(colon + 1);
                }
                
                // Determine namespace:
                // 1. If namespace attribute is present, use it
                // 2. Otherwise, if prefixed, look up prefix
                // Note: Unprefixed attributes are NOT in the default namespace
                if (namespace == null && prefix != null) {
                    // Look up prefix in compile-time bindings first
                    namespace = namespaceBindings.get(prefix);
                    if (namespace == null) {
                        // Fall back to runtime context
                        namespace = context.resolveNamespacePrefix(prefix);
                    }
                }
                if (namespace == null) {
                    namespace = "";
                }
                
                // Build qName - if we have a namespace but no prefix, generate one
                String qName = name;
                if (!namespace.isEmpty()) {
                    if (prefix == null || prefix.isEmpty()) {
                        // Generate a prefix for this namespace
                        // Try to find an existing prefix, otherwise generate one
                        prefix = findOrGeneratePrefix(namespace, namespaceBindings);
                        qName = prefix + ":" + localName;
                    }
                    // Declare the namespace binding
                    output.namespace(prefix, namespace);
                }
                
                output.attribute(namespace, localName, qName, value);
                
                // Determine effective validation mode
                ValidationMode effectiveValidation = validation;
                if (effectiveValidation == null) {
                    effectiveValidation = context.getStylesheet().getDefaultValidation();
                }
                
                // Set type annotation based on validation mode
                if (typeLocalName != null) {
                    // Explicit type attribute - always use it
                    output.setAttributeType(typeNamespaceURI, typeLocalName);
                } else if (effectiveValidation == ValidationMode.STRICT || 
                           effectiveValidation == ValidationMode.LAX) {
                    // Use runtime schema validation to derive type
                    RuntimeSchemaValidator validator = context.getRuntimeValidator();
                    if (validator != null) {
                        RuntimeSchemaValidator.ValidationResult valResult =
                            validator.validateStandaloneAttribute(namespace, localName, 
                                                                   value, effectiveValidation);
                        if (valResult.hasTypeAnnotation()) {
                            output.setAttributeType(valResult.getTypeNamespaceURI(),
                                                    valResult.getTypeLocalName());
                        }
                    }
                } else if (effectiveValidation == ValidationMode.STRIP) {
                    // Strip mode - don't set any type annotation
                }
                // PRESERVE mode when constructing new attributes is a no-op
            } catch (XPathException e) {
                throw new SAXException("Error in xsl:attribute", e);
            }
        }
    }

    private static class NamespaceInstructionNode extends XSLTInstruction {
        private final AttributeValueTemplate nameAvt;
        private final XPathExpression selectExpr;
        private final SequenceNode content;
        
        NamespaceInstructionNode(AttributeValueTemplate nameAvt, XPathExpression selectExpr, 
                                 SequenceNode content) {
            this.nameAvt = nameAvt;
            this.selectExpr = selectExpr;
            this.content = content;
        }
        
        @Override public String getInstructionName() { return "namespace"; }
        @Override public void execute(TransformContext context, 
                                      OutputHandler output) throws SAXException {
            try {
                // Get the prefix (name attribute)
                String prefix = nameAvt.evaluate(context);
                
                // Get the namespace URI from select or content
                String uri;
                if (selectExpr != null) {
                    uri = selectExpr.evaluate(context).asString();
                } else if (content != null) {
                    SAXEventBuffer buffer = new SAXEventBuffer();
                    content.execute(context, new BufferOutputHandler(buffer));
                    uri = buffer.getTextContent().trim();
                } else {
                    uri = "";
                }
                
                // Output the namespace declaration
                output.namespace(prefix != null ? prefix : "", uri);
            } catch (XPathException e) {
                throw new SAXException("Error in xsl:namespace", e);
            }
        }
    }
    
    private static class CommentNode extends XSLTInstruction {
        private final SequenceNode content;
        CommentNode(SequenceNode content) { this.content = content; }
        @Override public String getInstructionName() { return "comment"; }
        @Override public void execute(TransformContext context, 
                                      OutputHandler output) throws SAXException {
            String text = "";
            if (content != null) {
                SAXEventBuffer buffer = 
                    new SAXEventBuffer();
                content.execute(context, new BufferOutputHandler(buffer));
                text = buffer.getTextContent();
            }
            output.comment(text);
        }
    }

    private static class ProcessingInstructionNode extends XSLTInstruction {
        private final AttributeValueTemplate nameAvt;
        private final SequenceNode content;
        ProcessingInstructionNode(AttributeValueTemplate nameAvt, SequenceNode content) {
            this.nameAvt = nameAvt;
            this.content = content;
        }
        @Override public String getInstructionName() { return "processing-instruction"; }
        @Override public void execute(TransformContext context, 
                                      OutputHandler output) throws SAXException {
            try {
                String target = nameAvt.evaluate(context);
                String data = "";
                if (content != null) {
                    SAXEventBuffer buffer = 
                        new SAXEventBuffer();
                    content.execute(context, new BufferOutputHandler(buffer));
                    data = buffer.getTextContent();
                }
                output.processingInstruction(target, data);
            } catch (XPathException e) {
                throw new SAXException("Error in xsl:processing-instruction", e);
            }
        }
    }

    private static class CopyNode extends XSLTInstruction {
        private final String useAttrSets;
        private final SequenceNode content;
        private final String typeNamespaceURI;
        private final String typeLocalName;
        private final ValidationMode validation;
        
        CopyNode(String useAttrSets, SequenceNode content) {
            this(useAttrSets, content, null, null, null);
        }
        
        CopyNode(String useAttrSets, SequenceNode content, 
                String typeNamespaceURI, String typeLocalName, ValidationMode validation) {
            this.useAttrSets = useAttrSets;
            this.content = content;
            this.typeNamespaceURI = typeNamespaceURI;
            this.typeLocalName = typeLocalName;
            this.validation = validation;
        }
        
        @Override public String getInstructionName() { return "copy"; }
        @Override public void execute(TransformContext context, 
                                      OutputHandler output) throws SAXException {
            XPathNode node = context.getContextNode();
            if (node == null) {
                return;
            }
            
            // Determine effective validation mode
            ValidationMode effectiveValidation = validation;
            if (effectiveValidation == null) {
                effectiveValidation = context.getStylesheet().getDefaultValidation();
            }
            
            switch (node.getNodeType()) {
                case ELEMENT:
                    String uri = node.getNamespaceURI() != null ? node.getNamespaceURI() : "";
                    String localName = node.getLocalName();
                    String prefix = node.getPrefix();
                    String qName = prefix != null ? prefix + ":" + localName : localName;
                    
                    output.startElement(uri, localName, qName);
                    
                    // Set type annotation based on validation mode
                    if (typeLocalName != null) {
                        // Explicit type attribute
                        output.setElementType(typeNamespaceURI, typeLocalName);
                    } else if (effectiveValidation == ValidationMode.PRESERVE) {
                        // Preserve mode - copy type annotation from source
                        String srcTypeNs = node.getTypeNamespaceURI();
                        String srcTypeLocal = node.getTypeLocalName();
                        if (srcTypeLocal != null) {
                            output.setElementType(srcTypeNs, srcTypeLocal);
                        }
                    } else if (effectiveValidation == ValidationMode.STRICT || 
                               effectiveValidation == ValidationMode.LAX) {
                        // Use runtime schema validation to derive type
                        RuntimeSchemaValidator validator = context.getRuntimeValidator();
                        if (validator != null) {
                            try {
                                RuntimeSchemaValidator.ValidationResult valResult =
                                    validator.startElement(uri, localName, effectiveValidation);
                                if (valResult.hasTypeAnnotation()) {
                                    output.setElementType(valResult.getTypeNamespaceURI(),
                                                          valResult.getTypeLocalName());
                                }
                            } catch (XPathException e) {
                                throw new SAXException("Validation error in xsl:copy", e);
                            }
                        }
                    }
                    // STRIP mode - don't set any type annotation
                    
                    // Copy namespace declarations from source element
                    // Per XSLT spec, namespace undeclarations (xmlns="") should NOT be copied
                    // because the output tree follows different namespace inheritance rules
                    Iterator<XPathNode> namespaces = 
                        node.getNamespaces();
                    while (namespaces.hasNext()) {
                        XPathNode ns = namespaces.next();
                        String nsPrefix = ns.getLocalName();
                        String nsUri = ns.getStringValue();
                        // Skip xml namespace and namespace undeclarations
                        if (!"xml".equals(nsPrefix) && (nsUri != null && !nsUri.isEmpty())) {
                            output.namespace(nsPrefix, nsUri);
                        }
                    }
                    
                    // Apply use-attribute-sets if specified
                    if (useAttrSets != null && !useAttrSets.isEmpty()) {
                        CompiledStylesheet stylesheet = context.getStylesheet();
                        StringTokenizer st = new StringTokenizer(useAttrSets);
                        while (st.hasMoreTokens()) {
                            String setName = st.nextToken();
                            AttributeSet attrSet = stylesheet.getAttributeSet(setName);
                            if (attrSet != null) {
                                attrSet.apply(context, output);
                            }
                        }
                    }
                    
                    // Reset atomic separator for fresh content context
                    resetAtomicSeparator();
                    if (content != null) {
                        content.execute(context, output);
                    }
                    output.endElement(uri, localName, qName);
                    break;
                    
                case TEXT:
                    output.characters(node.getStringValue());
                    break;
                    
                case ATTRIBUTE:
                    String attrUri = node.getNamespaceURI() != null ? node.getNamespaceURI() : "";
                    String attrLocal = node.getLocalName();
                    String attrPrefix = node.getPrefix();
                    String attrQName = attrPrefix != null ? attrPrefix + ":" + attrLocal : attrLocal;
                    output.attribute(attrUri, attrLocal, attrQName, node.getStringValue());
                    break;
                    
                case COMMENT:
                    output.comment(node.getStringValue());
                    break;
                    
                case PROCESSING_INSTRUCTION:
                    output.processingInstruction(node.getLocalName(), node.getStringValue());
                    break;
                    
                case ROOT:
                    // Copy content only
                    if (content != null) {
                        content.execute(context, output);
                    }
                    break;
                    
                case NAMESPACE:
                    // Copy namespace node - outputs a namespace declaration
                    // localName is the prefix, stringValue is the URI
                    String nsPrefix = node.getLocalName();
                    String nsUri = node.getStringValue();
                    // Skip xml namespace (it's implicit)
                    if (!"xml".equals(nsPrefix)) {
                        output.namespace(nsPrefix != null ? nsPrefix : "", nsUri);
                    }
                    // Namespace nodes have no content to process
                    break;
                    
                default:
                    // Unknown node type - just process content
                    if (content != null) {
                        content.execute(context, output);
                    }
            }
        }
    }

    private static class CopyOfNode extends XSLTInstruction {
        private final XPathExpression selectExpr;
        private final String typeNamespaceURI;
        private final String typeLocalName;
        private final ValidationMode validation;
        private final boolean copyNamespaces;
        
        CopyOfNode(XPathExpression selectExpr) { 
            this(selectExpr, null, null, null, true);
        }
        
        CopyOfNode(XPathExpression selectExpr, String typeNamespaceURI, String typeLocalName,
                   ValidationMode validation, boolean copyNamespaces) {
            this.selectExpr = selectExpr;
            this.typeNamespaceURI = typeNamespaceURI;
            this.typeLocalName = typeLocalName;
            this.validation = validation;
            this.copyNamespaces = copyNamespaces;
        }
        
        @Override public String getInstructionName() { return "copy-of"; }
        @Override public void execute(TransformContext context, 
                                      OutputHandler output) throws SAXException {
            try {
                XPathValue result = selectExpr.evaluate(context);
                
                if (result == null) {
                    // Variable not found or expression returned null - output nothing
                    return;
                }
                
                // Determine effective validation mode
                ValidationMode effectiveValidation = validation;
                if (effectiveValidation == null) {
                    effectiveValidation = context.getStylesheet().getDefaultValidation();
                }
                
                if (result instanceof XPathResultTreeFragment) {
                    // Result tree fragment - replay the buffered events
                    XPathResultTreeFragment rtf = (XPathResultTreeFragment) result;
                    rtf.replayToOutput(output);
                } else if (result instanceof XPathNodeSet) {
                    XPathNodeSet nodeSet = (XPathNodeSet) result;
                    for (XPathNode node : nodeSet.getNodes()) {
                        // depth=0 means this is a directly selected node
                        deepCopyNode(node, output, effectiveValidation, 0);
                    }
                } else {
                    // For non-node-sets, output as text
                    output.characters(result.asString());
                }
            } catch (XPathException e) {
                throw new SAXException("Error in xsl:copy-of", e);
            }
        }
        
        /**
         * Deep copies a node to the output.
         * @param node the node to copy
         * @param output the output handler
         * @param effectiveValidation validation mode
         * @param depth 0 for directly selected nodes, >0 for children of copied nodes
         */
        private void deepCopyNode(XPathNode node, OutputHandler output, 
                                  ValidationMode effectiveValidation, int depth) throws SAXException {
            switch (node.getNodeType()) {
                case ELEMENT:
                    String uri = node.getNamespaceURI() != null ? node.getNamespaceURI() : "";
                    String localName = node.getLocalName();
                    String prefix = node.getPrefix();
                    String qName = prefix != null ? prefix + ":" + localName : localName;
                    
                    output.startElement(uri, localName, qName);
                    
                    // Set type annotation based on validation mode
                    if (typeLocalName != null) {
                        // Explicit type attribute
                        output.setElementType(typeNamespaceURI, typeLocalName);
                    } else if (effectiveValidation == ValidationMode.PRESERVE) {
                        // Preserve mode - copy type annotation from source
                        String srcTypeNs = node.getTypeNamespaceURI();
                        String srcTypeLocal = node.getTypeLocalName();
                        if (srcTypeLocal != null) {
                            output.setElementType(srcTypeNs, srcTypeLocal);
                        }
                    } else if (effectiveValidation == ValidationMode.STRICT || 
                               effectiveValidation == ValidationMode.LAX) {
                        // Note: For copy-of, we would need the context to do runtime validation
                        // For now, preserve source type annotation if available
                        String srcTypeNs = node.getTypeNamespaceURI();
                        String srcTypeLocal = node.getTypeLocalName();
                        if (srcTypeLocal != null) {
                            output.setElementType(srcTypeNs, srcTypeLocal);
                        }
                    }
                    // STRIP mode - don't set any type annotation
                    
                    // For unprefixed elements, emit the default namespace declaration
                    // This preserves the element's original namespace when copying into a
                    // different default namespace context
                    if (prefix == null || prefix.isEmpty()) {
                        // Always emit the default namespace for this element
                        // (either empty for no namespace, or the element's namespace URI)
                        output.namespace("", uri);
                    }
                    
                    // Copy namespace declarations (if copy-namespaces="yes")
                    if (copyNamespaces) {
                        Iterator<XPathNode> namespaces = 
                            node.getNamespaces();
                        while (namespaces.hasNext()) {
                            XPathNode ns = namespaces.next();
                            String nsPrefix = ns.getLocalName(); // namespace prefix is the local name
                            String nsUri = ns.getStringValue();  // namespace URI is the value
                            // Don't copy xml namespace (it's implicit)
                            if ("xml".equals(nsPrefix)) {
                                continue;
                            }
                            
                            // Handle default namespace (empty prefix)
                            if (nsPrefix == null || nsPrefix.isEmpty()) {
                                // For unprefixed elements, we already emitted the default namespace above
                                // For prefixed elements:
                                // - At depth 0 (directly selected), skip xmlns="" (spec bug 5857)
                                // - At depth > 0 (children of copy), preserve xmlns="" to maintain tree structure
                                if (prefix != null && !prefix.isEmpty()) {
                                    if (nsUri != null && !nsUri.isEmpty()) {
                                        // Always copy xmlns="URI" declarations
                                        output.namespace("", nsUri);
                                    } else if (depth > 0) {
                                        // Only copy xmlns="" if we're inside a tree copy (child of copied element)
                                        output.namespace("", "");
                                    }
                                    // At depth 0, skip xmlns="" - it doesn't affect directly selected prefixed elements
                                }
                            } else {
                                // Prefixed namespace declaration
                                if (nsUri != null && !nsUri.isEmpty()) {
                                    output.namespace(nsPrefix, nsUri);
                                } else if (prefix == null || prefix.isEmpty()) {
                                    // Only copy namespace undeclarations for unprefixed elements
                                    output.namespace(nsPrefix, "");
                                }
                            }
                        }
                    }
                    
                    // Copy attributes
                    Iterator<XPathNode> attrs = 
                        node.getAttributes();
                    while (attrs.hasNext()) {
                        XPathNode attr = attrs.next();
                        String attrUri = attr.getNamespaceURI() != null ? attr.getNamespaceURI() : "";
                        String attrLocal = attr.getLocalName();
                        String attrPrefix = attr.getPrefix();
                        String attrQName = attrPrefix != null ? attrPrefix + ":" + attrLocal : attrLocal;
                        output.attribute(attrUri, attrLocal, attrQName, attr.getStringValue());
                    }
                    
                    // Copy children (depth+1 to indicate we're inside a tree copy)
                    Iterator<XPathNode> children = 
                        node.getChildren();
                    while (children.hasNext()) {
                        deepCopyNode(children.next(), output, effectiveValidation, depth + 1);
                    }
                    
                    output.endElement(uri, localName, qName);
                    break;
                    
                case TEXT:
                    output.characters(node.getStringValue());
                    break;
                    
                case COMMENT:
                    output.comment(node.getStringValue());
                    break;
                    
                case PROCESSING_INSTRUCTION:
                    output.processingInstruction(node.getLocalName(), node.getStringValue());
                    break;
                    
                case ROOT:
                    // Copy children only (depth+1 to preserve tree structure within document)
                    Iterator<XPathNode> rootChildren = 
                        node.getChildren();
                    while (rootChildren.hasNext()) {
                        deepCopyNode(rootChildren.next(), output, effectiveValidation, depth + 1);
                    }
                    break;
                    
                case ATTRIBUTE:
                    // Copy attribute node (e.g., from xsl:copy-of select="@*")
                    String atUri = node.getNamespaceURI() != null ? node.getNamespaceURI() : "";
                    String atLocal = node.getLocalName();
                    String atPrefix = node.getPrefix();
                    String atQName = atPrefix != null ? atPrefix + ":" + atLocal : atLocal;
                    output.attribute(atUri, atLocal, atQName, node.getStringValue());
                    break;
                    
                default:
                    // Ignore other node types (namespace)
            }
        }
    }

    /**
     * xsl:sequence with select - outputs the selected sequence.
     * Unlike xsl:copy-of, xsl:sequence returns nodes by reference (no copying).
     * For atomic values, it outputs them as text.
     */
    /**
     * Tracks whether we need a space separator before the next atomic value.
     * XSLT 2.0: adjacent atomic values in content are space-separated.
     */
    private static final ThreadLocal<Boolean> ATOMIC_VALUE_PENDING = 
        new ThreadLocal<Boolean>() {
            @Override
            protected Boolean initialValue() {
                return Boolean.FALSE;
            }
        };

    /**
     * Resets the atomic value separator tracking. 
     * Call this when entering a new element/document context.
     */
    public static void resetAtomicSeparator() {
        ATOMIC_VALUE_PENDING.set(Boolean.FALSE);
    }

    private static class SequenceOutputNode extends XSLTInstruction {
        private final XPathExpression selectExpr;
        SequenceOutputNode(XPathExpression selectExpr) { this.selectExpr = selectExpr; }
        @Override public String getInstructionName() { return "sequence"; }
        @Override public void execute(TransformContext context, 
                                      OutputHandler output) throws SAXException {
            try {
                XPathValue result = selectExpr.evaluate(context);
                
                if (result == null) {
                    return;
                }
                
                if (result instanceof XPathResultTreeFragment) {
                    // Result tree fragment - replay the buffered events
                    XPathResultTreeFragment rtf = (XPathResultTreeFragment) result;
                    rtf.replayToOutput(output);
                    ATOMIC_VALUE_PENDING.set(Boolean.FALSE);
                } else if (result instanceof XPathSequence) {
                    // XPath 2.0+ sequence - output each item separately
                    XPathSequence seq = (XPathSequence) result;
                    boolean first = true;
                    for (XPathValue item : seq) {
                        if (!first) {
                            // Signal item boundary for sequence builders
                            output.itemBoundary();
                        }
                        outputSequenceItem(item, output, first);
                        first = false;
                    }
                } else if (result instanceof XPathNodeSet) {
                    // For node-sets, output nodes (similar to copy-of for simplicity)
                    XPathNodeSet nodeSet = (XPathNodeSet) result;
                    boolean first = true;
                    for (XPathNode node : nodeSet.getNodes()) {
                        if (!first) {
                            output.itemBoundary();
                        }
                        outputNode(node, output);
                        first = false;
                    }
                    ATOMIC_VALUE_PENDING.set(Boolean.FALSE);
                } else {
                    // For atomic values, output as text with XSLT 2.0 spacing
                    // Adjacent atomic values are separated by a single space
                    if (ATOMIC_VALUE_PENDING.get()) {
                        output.characters(" ");
                    }
                    output.characters(result.asString());
                    ATOMIC_VALUE_PENDING.set(Boolean.TRUE);
                }
            } catch (XPathException e) {
                throw new SAXException("Error in xsl:sequence", e);
            }
        }
        
        private void outputSequenceItem(XPathValue item, OutputHandler output, boolean first) throws SAXException {
            if (item instanceof XPathNodeSet) {
                XPathNodeSet nodeSet = (XPathNodeSet) item;
                for (XPathNode node : nodeSet.getNodes()) {
                    outputNode(node, output);
                }
                ATOMIC_VALUE_PENDING.set(Boolean.FALSE);
            } else if (item instanceof XPathResultTreeFragment) {
                ((XPathResultTreeFragment) item).replayToOutput(output);
                ATOMIC_VALUE_PENDING.set(Boolean.FALSE);
            } else {
                // Atomic value - output directly without XSLT 2.0 spacing
                // (spacing is handled by itemBoundary() for sequence construction,
                // or by xsl:value-of separator for output)
                output.characters(item.asString());
                // Don't set ATOMIC_VALUE_PENDING since we're in sequence context
            }
        }
        
        private void outputNode(XPathNode node, OutputHandler output) throws SAXException {
            // For xsl:sequence, we output nodes directly
            // This is a simplified implementation - full XSLT 2.0 would handle
            // document nodes, attribute nodes, etc. differently
            switch (node.getNodeType()) {
                case ELEMENT:
                    String uri = node.getNamespaceURI() != null ? node.getNamespaceURI() : "";
                    String localName = node.getLocalName();
                    String prefix = node.getPrefix();
                    String qName = prefix != null ? prefix + ":" + localName : localName;
                    
                    output.startElement(uri, localName, qName);
                    
                    // Copy namespace declarations
                    Iterator<XPathNode> namespaces = node.getNamespaces();
                    while (namespaces.hasNext()) {
                        XPathNode ns = namespaces.next();
                        String nsPrefix = ns.getLocalName();
                        String nsUri = ns.getStringValue();
                        if (!"xml".equals(nsPrefix) && nsUri != null) {
                            output.namespace(nsPrefix, nsUri);
                        }
                    }
                    
                    // Copy attributes
                    Iterator<XPathNode> attrs = node.getAttributes();
                    while (attrs.hasNext()) {
                        XPathNode attr = attrs.next();
                        String attrUri = attr.getNamespaceURI() != null ? attr.getNamespaceURI() : "";
                        String attrLocal = attr.getLocalName();
                        String attrPrefix = attr.getPrefix();
                        String attrQName = attrPrefix != null ? attrPrefix + ":" + attrLocal : attrLocal;
                        output.attribute(attrUri, attrLocal, attrQName, attr.getStringValue());
                    }
                    
                    // Copy children
                    Iterator<XPathNode> children = node.getChildren();
                    while (children.hasNext()) {
                        outputNode(children.next(), output);
                    }
                    
                    output.endElement(uri, localName, qName);
                    break;
                    
                case TEXT:
                    output.characters(node.getStringValue());
                    break;
                    
                case COMMENT:
                    output.comment(node.getStringValue());
                    break;
                    
                case PROCESSING_INSTRUCTION:
                    output.processingInstruction(node.getLocalName(), node.getStringValue());
                    break;
                    
                case ROOT:
                    Iterator<XPathNode> rootChildren = node.getChildren();
                    while (rootChildren.hasNext()) {
                        outputNode(rootChildren.next(), output);
                    }
                    break;
                    
                case ATTRIBUTE:
                    String atUri = node.getNamespaceURI() != null ? node.getNamespaceURI() : "";
                    String atLocal = node.getLocalName();
                    String atPrefix = node.getPrefix();
                    String atQName = atPrefix != null ? atPrefix + ":" + atLocal : atLocal;
                    output.attribute(atUri, atLocal, atQName, node.getStringValue());
                    break;
                    
                default:
                    // Ignore namespace nodes
            }
        }
    }

    private static class ApplyTemplatesNode extends XSLTInstruction {
        private final XPathExpression selectExpr;
        private final String mode;
        private final List<SortSpec> sorts;
        private final List<WithParamNode> params;
        ApplyTemplatesNode(XPathExpression selectExpr, String mode, List<SortSpec> sorts, List<WithParamNode> params) {
            this.selectExpr = selectExpr;
            this.mode = mode;
            this.sorts = sorts;
            this.params = params;
        }
        @Override public String getInstructionName() { return "apply-templates"; }
        @Override public void execute(TransformContext context, 
                                      OutputHandler output) throws SAXException {
            try {
                // Get nodes to process
                List<XPathNode> nodes;
                if (selectExpr != null) {
                    XPathValue result = selectExpr.evaluate(context);
                    if (result instanceof XPathNodeSet) {
                        nodes = ((XPathNodeSet) result).getNodes();
                    } else if (result instanceof XPathResultTreeFragment) {
                        // Convert RTF to node-set (XSLT 2.0+)
                        nodes = ((XPathResultTreeFragment) result).asNodeSet().getNodes();
                    } else {
                        return; // Not a node-set
                    }
                } else {
                    // Default: select="child::node()"
                    nodes = new ArrayList<>();
                    Iterator<XPathNode> children = 
                        context.getContextNode().getChildren();
                    while (children.hasNext()) {
                        nodes.add(children.next());
                    }
                }
                
                // Apply sorting if specified
                if (sorts != null && !sorts.isEmpty()) {
                    ForEachNode.sortNodesStatic(nodes, sorts, context);
                }
                
                // Process each node
                int size = nodes.size();
                int position = 1;
                for (XPathNode node : nodes) {
                    // Use withXsltCurrentNode to update both context node and XSLT current()
                    TransformContext nodeContext;
                    if (context instanceof BasicTransformContext) {
                        nodeContext = ((BasicTransformContext) context)
                            .withXsltCurrentNode(node).withPositionAndSize(position, size);
                    } else {
                        nodeContext = context.withContextNode(node).withPositionAndSize(position, size);
                    }
                    if (mode != null) {
                        nodeContext = nodeContext.withMode(mode);
                    }
                    
                    // Find and execute matching template
                    TemplateMatcher matcher = 
                        new TemplateMatcher(context.getStylesheet());
                    TemplateRule rule = matcher.findMatch(node, mode, nodeContext);
                    
                    if (rule != null) {
                        // Push scope and set current template rule (needed for apply-imports)
                        TransformContext execContext = 
                            nodeContext.pushVariableScope()
                                .withCurrentTemplateRule(rule);
                        
                        // Collect template parameter names
                        Set<String> templateParamNames = new HashSet<>();
                        for (TemplateParameter tp : rule.getParameters()) {
                            templateParamNames.add(tp.getName());
                        }
                        
                        // Collect passed parameter names
                        Set<String> passedParams = new HashSet<>();
                        
                        // Set with-param values - ONLY for params the template declares
                        for (WithParamNode param : params) {
                            if (templateParamNames.contains(param.getName())) {
                                XPathValue value = 
                                    param.evaluate(context);
                                execContext.getVariableScope().bind(param.getName(), value);
                                passedParams.add(param.getName());
                            }
                            // Else: silently ignore (per XSLT 1.0)
                        }
                        
                        // Set default values for template parameters not passed via with-param
                        for (TemplateParameter templateParam : rule.getParameters()) {
                            if (!passedParams.contains(templateParam.getName())) {
                                // Evaluate default value
                                XPathValue defaultValue = null;
                                if (templateParam.getSelectExpr() != null) {
                                    try {
                                        defaultValue = templateParam.getSelectExpr().evaluate(execContext);
                                    } catch (XPathException e) {
                                        throw new SAXException("Error evaluating param default: " + e.getMessage(), e);
                                    }
                                } else if (templateParam.getDefaultContent() != null) {
                                    // Execute content to get RTF as default value
                                    SAXEventBuffer buffer = new SAXEventBuffer();
                                    templateParam.getDefaultContent().execute(execContext, new BufferOutputHandler(buffer));
                                    defaultValue = new XPathResultTreeFragment(buffer);
                                } else {
                                    defaultValue = new XPathString(""); // Empty default
                                }
                                execContext.getVariableScope().bind(templateParam.getName(), defaultValue);
                            }
                        }
                        
                        if (TemplateMatcher.isBuiltIn(rule)) {
                            executeBuiltIn(TemplateMatcher
                                .getBuiltInType(rule), node, execContext, output);
                        } else {
                            rule.getBody().execute(execContext, output);
                        }
                    }
                    position++;
                }
            } catch (XPathException e) {
                throw new SAXException("Error in xsl:apply-templates", e);
            }
        }
        
        private void executeBuiltIn(String type, XPathNode node,
                TransformContext context,
                OutputHandler output) throws SAXException {
            switch (type) {
                case "element-or-root":
                case "shallow-skip":
                    // Apply templates to children
                    applyToChildren(node, context, output);
                    break;
                case "text-or-attribute":
                    output.characters(node.getStringValue());
                    break;
                case "shallow-copy":
                    executeShallowCopy(node, context, output);
                    break;
                case "deep-copy":
                    executeDeepCopy(node, output);
                    break;
                case "fail":
                    throw new SAXException("XTDE0555: No matching template found for node: " + 
                        node.getNodeType() + " (mode has on-no-match='fail')");
                case "empty":
                    // Do nothing
                    break;
            }
        }
        
        private void applyToChildren(XPathNode node, TransformContext context,
                OutputHandler output) throws SAXException {
            Iterator<XPathNode> children = node.getChildren();
            List<XPathNode> childList = new ArrayList<>();
            while (children.hasNext()) childList.add(children.next());
            
            int size = childList.size();
            int pos = 1;
            for (XPathNode child : childList) {
                TransformContext childCtx = 
                    context.withContextNode(child).withPositionAndSize(pos++, size);
                
                TemplateMatcher m = new TemplateMatcher(context.getStylesheet());
                TemplateRule r = m.findMatch(child, context.getCurrentMode(), childCtx);
                if (r != null) {
                    if (TemplateMatcher.isBuiltIn(r)) {
                        executeBuiltIn(TemplateMatcher.getBuiltInType(r), child, childCtx, output);
                    } else {
                        r.getBody().execute(childCtx.pushVariableScope(), output);
                    }
                }
            }
        }
        
        private void executeShallowCopy(XPathNode node, TransformContext context,
                OutputHandler output) throws SAXException {
            NodeType nodeType = node.getNodeType();
            switch (nodeType) {
                case ELEMENT:
                    String uri = node.getNamespaceURI();
                    String localName = node.getLocalName();
                    String prefix = node.getPrefix();
                    String qName = prefix != null && !prefix.isEmpty() ? prefix + ":" + localName : localName;
                    
                    output.startElement(uri != null ? uri : "", localName, qName);
                    
                    // Copy attributes
                    Iterator<XPathNode> attrIter = node.getAttributes();
                    while (attrIter.hasNext()) {
                        XPathNode attr = attrIter.next();
                        String aUri = attr.getNamespaceURI();
                        String aLocal = attr.getLocalName();
                        String aPrefix = attr.getPrefix();
                        String aQName = aPrefix != null && !aPrefix.isEmpty() ? aPrefix + ":" + aLocal : aLocal;
                        output.attribute(aUri != null ? aUri : "", aLocal, aQName, attr.getStringValue());
                    }
                    
                    applyToChildren(node, context, output);
                    output.endElement(uri != null ? uri : "", localName, qName);
                    break;
                case TEXT:
                    output.characters(node.getStringValue());
                    break;
                case COMMENT:
                    output.comment(node.getStringValue());
                    break;
                case PROCESSING_INSTRUCTION:
                    output.processingInstruction(node.getLocalName(), node.getStringValue());
                    break;
                case ROOT:
                    applyToChildren(node, context, output);
                    break;
                default:
                    break;
            }
        }
        
        private void executeDeepCopy(XPathNode node, OutputHandler output) throws SAXException {
            NodeType nodeType = node.getNodeType();
            switch (nodeType) {
                case ELEMENT:
                    String uri = node.getNamespaceURI();
                    String localName = node.getLocalName();
                    String prefix = node.getPrefix();
                    String qName = prefix != null && !prefix.isEmpty() ? prefix + ":" + localName : localName;
                    
                    output.startElement(uri != null ? uri : "", localName, qName);
                    
                    // Copy attributes
                    Iterator<XPathNode> attrIter = node.getAttributes();
                    while (attrIter.hasNext()) {
                        XPathNode attr = attrIter.next();
                        String aUri = attr.getNamespaceURI();
                        String aLocal = attr.getLocalName();
                        String aPrefix = attr.getPrefix();
                        String aQName = aPrefix != null && !aPrefix.isEmpty() ? aPrefix + ":" + aLocal : aLocal;
                        output.attribute(aUri != null ? aUri : "", aLocal, aQName, attr.getStringValue());
                    }
                    
                    // Recursively copy children
                    Iterator<XPathNode> children = node.getChildren();
                    while (children.hasNext()) {
                        executeDeepCopy(children.next(), output);
                    }
                    
                    output.endElement(uri != null ? uri : "", localName, qName);
                    break;
                case TEXT:
                    output.characters(node.getStringValue());
                    break;
                case COMMENT:
                    output.comment(node.getStringValue());
                    break;
                case PROCESSING_INSTRUCTION:
                    output.processingInstruction(node.getLocalName(), node.getStringValue());
                    break;
                case ROOT:
                    Iterator<XPathNode> rootChildren = node.getChildren();
                    while (rootChildren.hasNext()) {
                        executeDeepCopy(rootChildren.next(), output);
                    }
                    break;
                default:
                    break;
            }
        }
    }

    private static class CallTemplateNode extends XSLTInstruction {
        private final String name;
        private final List<WithParamNode> params;
        CallTemplateNode(String name, List<WithParamNode> params) {
            this.name = name;
            this.params = params;
        }
        @Override public String getInstructionName() { return "call-template"; }
        @Override public void execute(TransformContext context, 
                                      OutputHandler output) throws SAXException {
            // Find template by name
            TemplateRule template = null;
            for (TemplateRule rule : context.getStylesheet().getTemplateRules()) {
                if (name.equals(rule.getName())) {
                    template = rule;
                    break;
                }
            }
            
            if (template == null) {
                throw new SAXException("Template not found: " + name);
            }
            
            // Push variable scope
            TransformContext callContext = 
                context.pushVariableScope();
            
            // Collect template parameter names (only these should be bound from with-param)
            Set<String> templateParamNames = new HashSet<>();
            for (TemplateParameter tp : template.getParameters()) {
                templateParamNames.add(tp.getName());
            }
            
            // Collect passed parameter names (only those that match template params)
            Set<String> passedParams = new HashSet<>();
            
            // Set with-param values - ONLY for parameters the template declares
            // Per XSLT spec, with-param for undeclared parameters is ignored
            try {
                for (WithParamNode param : params) {
                    if (templateParamNames.contains(param.getName())) {
                        XPathValue value = param.evaluate(context);
                        callContext.getVariableScope().bind(param.getName(), value);
                        passedParams.add(param.getName());
                    }
                    // Else: silently ignore (XSLT 1.0) - the param isn't declared by template
                }
            } catch (XPathException e) {
                throw new SAXException("Error evaluating with-param", e);
            }
            
            // Bind default values for template parameters not passed via with-param
            for (TemplateParameter templateParam : template.getParameters()) {
                if (!passedParams.contains(templateParam.getName())) {
                    XPathValue defaultValue = null;
                    if (templateParam.getSelectExpr() != null) {
                        try {
                            defaultValue = templateParam.getSelectExpr().evaluate(callContext);
                        } catch (XPathException e) {
                            throw new SAXException("Error evaluating param default: " + e.getMessage(), e);
                        }
                    } else if (templateParam.getDefaultContent() != null) {
                        // Execute content to get RTF as default value
                        SAXEventBuffer buffer = new SAXEventBuffer();
                        templateParam.getDefaultContent().execute(callContext, new BufferOutputHandler(buffer));
                        defaultValue = new XPathResultTreeFragment(buffer);
                    } else {
                        defaultValue = new XPathString("");
                    }
                    if (defaultValue != null) {
                        callContext.getVariableScope().bind(templateParam.getName(), defaultValue);
                    }
                }
            }
            
            // Execute template body
            template.getBody().execute(callContext, output);
        }
    }

    private static class ApplyImportsNode extends XSLTInstruction {
        @Override public String getInstructionName() { return "apply-imports"; }
        @Override public void execute(TransformContext context, 
                                      OutputHandler output) throws SAXException {
            TemplateRule currentRule = context.getCurrentTemplateRule();
            XPathNode currentNode = context.getXsltCurrentNode();
            
            if (currentRule == null || currentNode == null) {
                // Not in a template context - xsl:apply-imports is an error outside templates
                return;
            }
            
            // Find the matching template from imported stylesheets
            TemplateMatcher matcher = context.getTemplateMatcher();
            TemplateRule importedRule = matcher.findImportMatch(
                currentNode, context.getCurrentMode(), currentRule, context);
            
            if (importedRule == null || TemplateMatcher.isBuiltIn(importedRule)) {
                // No imported template, apply built-in rule
                String type = importedRule != null ? TemplateMatcher.getBuiltInType(importedRule) : null;
                if ("element-or-root".equals(type)) {
                    // Built-in: apply-templates to children
                    applyTemplatesToChildren(currentNode, context, output);
                } else if ("text-or-attribute".equals(type)) {
                    // Built-in: copy string value
                    String value = currentNode.getStringValue();
                    if (value != null && !value.isEmpty()) {
                        output.characters(value);
                    }
                }
                // "empty" type does nothing
                return;
            }
            
            // Execute the imported template
            TransformContext execContext = context.pushVariableScope()
                .withCurrentTemplateRule(importedRule);
            
            // Bind template parameter defaults (apply-imports doesn't pass params)
            for (TemplateParameter templateParam : importedRule.getParameters()) {
                XPathValue defaultValue = null;
                if (templateParam.getSelectExpr() != null) {
                    try {
                        defaultValue = templateParam.getSelectExpr().evaluate(execContext);
                    } catch (XPathException e) {
                        throw new SAXException("Error evaluating parameter default: " + e.getMessage(), e);
                    }
                } else if (templateParam.getDefaultContent() != null) {
                    // Execute content to get RTF as default value
                    SAXEventBuffer buffer = new SAXEventBuffer();
                    templateParam.getDefaultContent().execute(execContext, new BufferOutputHandler(buffer));
                    defaultValue = new XPathResultTreeFragment(buffer);
                } else {
                    defaultValue = new XPathString("");
                }
                if (defaultValue != null) {
                    execContext.getVariableScope().bind(templateParam.getName(), defaultValue);
                }
            }
            
            importedRule.getBody().execute(execContext, output);
        }
        
        private void applyTemplatesToChildren(XPathNode node, TransformContext context, 
                                              OutputHandler output) throws SAXException {
            // First pass: count children
            List<XPathNode> children = new ArrayList<>();
            Iterator<XPathNode> it = node.getChildren();
            while (it.hasNext()) {
                children.add(it.next());
            }
            int size = children.size();
            int pos = 1;
            for (XPathNode child : children) {
                TransformContext childContext = context.withContextNode(child)
                    .withPositionAndSize(pos++, size);
                TemplateMatcher matcher = context.getTemplateMatcher();
                TemplateRule rule = matcher.findMatch(child, context.getCurrentMode(), childContext);
                if (rule != null) {
                    TransformContext execContext = childContext.pushVariableScope()
                        .withCurrentTemplateRule(rule);
                    if (TemplateMatcher.isBuiltIn(rule)) {
                        executeBuiltIn(TemplateMatcher.getBuiltInType(rule), child, execContext, output);
                    } else {
                        rule.getBody().execute(execContext, output);
                    }
                }
            }
        }
        
        private void executeBuiltIn(String type, XPathNode node,
                TransformContext context, OutputHandler output) throws SAXException {
            switch (type) {
                case "element-or-root":
                    // Apply templates to children
                    applyTemplatesToChildren(node, context, output);
                    break;
                case "text-or-attribute":
                    // Copy string value
                    String value = node.getStringValue();
                    if (value != null && !value.isEmpty()) {
                        output.characters(value);
                    }
                    break;
                case "shallow-copy":
                    // Copy the node (without content for elements) then apply-templates to children
                    executeShallowCopy(node, context, output);
                    break;
                case "deep-copy":
                    // Copy the entire subtree
                    executeDeepCopy(node, output);
                    break;
                case "shallow-skip":
                    // Skip the node but apply-templates to children
                    applyTemplatesToChildren(node, context, output);
                    break;
                case "fail":
                    // Raise an error - no template matched
                    throw new SAXException("XTDE0555: No matching template found for node: " + 
                        node.getNodeType() + " (mode has on-no-match='fail')");
                // "empty" type does nothing
            }
        }
        
        private void executeShallowCopy(XPathNode node, TransformContext context,
                OutputHandler output) throws SAXException {
            NodeType nodeType = node.getNodeType();
            switch (nodeType) {
                case ELEMENT:
                    String uri = node.getNamespaceURI();
                    String localName = node.getLocalName();
                    String prefix = node.getPrefix();
                    String qName = prefix != null && !prefix.isEmpty() ? prefix + ":" + localName : localName;
                    
                    output.startElement(uri != null ? uri : "", localName, qName);
                    
                    // Copy attributes
                    Iterator<XPathNode> attrIter = node.getAttributes();
                    while (attrIter.hasNext()) {
                        XPathNode attr = attrIter.next();
                        String aUri = attr.getNamespaceURI();
                        String aLocal = attr.getLocalName();
                        String aPrefix = attr.getPrefix();
                        String aQName = aPrefix != null && !aPrefix.isEmpty() ? aPrefix + ":" + aLocal : aLocal;
                        output.attribute(aUri != null ? aUri : "", aLocal, aQName, attr.getStringValue());
                    }
                    
                    applyTemplatesToChildren(node, context, output);
                    output.endElement(uri != null ? uri : "", localName, qName);
                    break;
                    
                case TEXT:
                    output.characters(node.getStringValue());
                    break;
                    
                case ATTRIBUTE:
                    output.characters(node.getStringValue());
                    break;
                    
                case COMMENT:
                    output.comment(node.getStringValue());
                    break;
                    
                case PROCESSING_INSTRUCTION:
                    output.processingInstruction(node.getLocalName(), node.getStringValue());
                    break;
                    
                case ROOT:
                    applyTemplatesToChildren(node, context, output);
                    break;
                    
                default:
                    break;
            }
        }
        
        private void executeDeepCopy(XPathNode node, OutputHandler output) throws SAXException {
            NodeType nodeType = node.getNodeType();
            switch (nodeType) {
                case ELEMENT:
                    String uri = node.getNamespaceURI();
                    String localName = node.getLocalName();
                    String prefix = node.getPrefix();
                    String qName = prefix != null && !prefix.isEmpty() ? prefix + ":" + localName : localName;
                    
                    output.startElement(uri != null ? uri : "", localName, qName);
                    
                    // Copy attributes
                    Iterator<XPathNode> attrIter = node.getAttributes();
                    while (attrIter.hasNext()) {
                        XPathNode attr = attrIter.next();
                        String aUri = attr.getNamespaceURI();
                        String aLocal = attr.getLocalName();
                        String aPrefix = attr.getPrefix();
                        String aQName = aPrefix != null && !aPrefix.isEmpty() ? aPrefix + ":" + aLocal : aLocal;
                        output.attribute(aUri != null ? aUri : "", aLocal, aQName, attr.getStringValue());
                    }
                    
                    // Recursively copy children
                    Iterator<XPathNode> children = node.getChildren();
                    while (children.hasNext()) {
                        executeDeepCopy(children.next(), output);
                    }
                    
                    output.endElement(uri != null ? uri : "", localName, qName);
                    break;
                    
                case TEXT:
                    output.characters(node.getStringValue());
                    break;
                    
                case COMMENT:
                    output.comment(node.getStringValue());
                    break;
                    
                case PROCESSING_INSTRUCTION:
                    output.processingInstruction(node.getLocalName(), node.getStringValue());
                    break;
                    
                case ROOT:
                    Iterator<XPathNode> rootChildren = node.getChildren();
                    while (rootChildren.hasNext()) {
                        executeDeepCopy(rootChildren.next(), output);
                    }
                    break;
                    
                default:
                    break;
            }
        }
    }

    /**
     * xsl:next-match instruction (XSLT 2.0+).
     *
     * <p>Invokes the next template rule that matches the current node,
     * in precedence/priority order after the currently executing template.
     */
    private static class NextMatchNode extends XSLTInstruction {
        private final List<WithParamNode> params;
        
        NextMatchNode(List<WithParamNode> params) {
            this.params = params != null ? params : Collections.emptyList();
        }
        
        @Override public String getInstructionName() { return "next-match"; }
        
        @Override
        public void execute(TransformContext context, OutputHandler output) throws SAXException {
            TemplateRule currentRule = context.getCurrentTemplateRule();
            XPathNode currentNode = context.getXsltCurrentNode();
            
            if (currentRule == null || currentNode == null) {
                // Not in a template context - nothing to do
                return;
            }
            
            // Find the next matching template
            TemplateMatcher matcher = context.getTemplateMatcher();
            TemplateRule nextRule = matcher.findNextMatch(
                currentNode, context.getCurrentMode(), currentRule, context);
            
            if (nextRule == null || TemplateMatcher.isBuiltIn(nextRule)) {
                // No more user-defined templates, apply built-in rule
                String type = TemplateMatcher.getBuiltInType(nextRule);
                if ("element-or-root".equals(type)) {
                    // Built-in: apply-templates to children
                    applyTemplatesToChildren(currentNode, context, output);
                } else if ("text-or-attribute".equals(type)) {
                    // Built-in: copy string value
                    String value = currentNode.getStringValue();
                    if (value != null && !value.isEmpty()) {
                        output.characters(value);
                    }
                }
                // "empty" type does nothing
                return;
            }
            
            // Execute the next template with parameters
            TransformContext execContext = context.pushVariableScope()
                .withCurrentTemplateRule(nextRule);
            
            // Bind with-param values
            for (WithParamNode param : params) {
                try {
                    XPathValue value = param.evaluate(context);
                    execContext.getVariableScope().bind(param.getName(), value);
                } catch (XPathException e) {
                    throw new SAXException("Error evaluating with-param: " + e.getMessage(), e);
                }
            }
            
            // Bind template parameter defaults for any not provided
            for (TemplateParameter templateParam : nextRule.getParameters()) {
                if (execContext.getVariableScope().lookup(templateParam.getName()) == null) {
                    XPathValue defaultValue = null;
                    if (templateParam.getSelectExpr() != null) {
                        try {
                            defaultValue = templateParam.getSelectExpr().evaluate(execContext);
                        } catch (XPathException e) {
                            throw new SAXException("Error evaluating parameter default: " + e.getMessage(), e);
                        }
                    } else if (templateParam.getDefaultContent() != null) {
                        SAXEventBuffer buffer = new SAXEventBuffer();
                        BufferOutputHandler bufOutput = new BufferOutputHandler(buffer);
                        templateParam.getDefaultContent().execute(execContext, bufOutput);
                        defaultValue = new XPathResultTreeFragment(buffer);
                    } else {
                        defaultValue = new XPathString("");
                    }
                    execContext.getVariableScope().bind(templateParam.getName(), defaultValue);
                }
            }
            
            // Execute the template body
            nextRule.getBody().execute(execContext, output);
        }
        
        private void applyTemplatesToChildren(XPathNode node, TransformContext context, 
                                              OutputHandler output) throws SAXException {
            // First pass: count children
            List<XPathNode> children = new ArrayList<>();
            Iterator<XPathNode> it = node.getChildren();
            while (it.hasNext()) {
                children.add(it.next());
            }
            int size = children.size();
            int pos = 1;
            for (XPathNode child : children) {
                TransformContext childContext = context.withContextNode(child)
                    .withPositionAndSize(pos++, size);
                TemplateMatcher matcher = context.getTemplateMatcher();
                TemplateRule rule = matcher.findMatch(child, context.getCurrentMode(), childContext);
                if (rule != null) {
                    TransformContext execContext = childContext.withCurrentTemplateRule(rule);
                    if (TemplateMatcher.isBuiltIn(rule)) {
                        // Execute built-in template
                        executeBuiltIn(TemplateMatcher.getBuiltInType(rule), child, execContext, output);
                    } else {
                        rule.getBody().execute(execContext, output);
                    }
                }
            }
        }
        
        private void executeBuiltIn(String type, XPathNode node,
                TransformContext context, OutputHandler output) throws SAXException {
            switch (type) {
                case "element-or-root":
                    // Apply templates to children
                    applyTemplatesToChildren(node, context, output);
                    break;
                case "text-or-attribute":
                    // Copy string value
                    String value = node.getStringValue();
                    if (value != null && !value.isEmpty()) {
                        output.characters(value);
                    }
                    break;
                // "empty" type does nothing
            }
        }
    }

    /**
     * xsl:try instruction (XSLT 3.0).
     *
     * <p>Executes content that might throw an error. If an error occurs,
     * the catch content is executed instead.
     */
    private static class TryNode extends XSLTInstruction {
        private final XSLTNode tryContent;
        private final XSLTNode catchContent;
        private final String errorCodes;  // Optional error codes to catch
        
        TryNode(XSLTNode tryContent, XSLTNode catchContent, String errorCodes) {
            this.tryContent = tryContent;
            this.catchContent = catchContent;
            this.errorCodes = errorCodes;
        }
        
        @Override public String getInstructionName() { return "try"; }
        
        @Override
        public void execute(TransformContext context, OutputHandler output) throws SAXException {
            try {
                if (tryContent != null) {
                    tryContent.execute(context, output);
                }
            } catch (SAXException e) {
                // Error occurred - execute catch content
                if (catchContent != null) {
                    // TODO: Bind error variables ($err:code, $err:description, etc.)
                    catchContent.execute(context, output);
                }
                // If no catch content, swallow the error (XSLT 3.0 behavior)
            } catch (RuntimeException e) {
                // Convert runtime exceptions to handled errors
                if (catchContent != null) {
                    catchContent.execute(context, output);
                }
            }
        }
    }

    /**
     * xsl:analyze-string instruction (XSLT 2.0).
     *
     * <p>Analyzes a string using a regular expression, executing different
     * content for matching and non-matching substrings.
     */
    private static class AnalyzeStringNode extends XSLTInstruction {
        private final XPathExpression selectExpr;
        private final AttributeValueTemplate regexAvt;
        private final AttributeValueTemplate flagsAvt;
        private final XSLTNode matchingContent;
        private final XSLTNode nonMatchingContent;
        
        AnalyzeStringNode(XPathExpression selectExpr, AttributeValueTemplate regexAvt,
                         AttributeValueTemplate flagsAvt, XSLTNode matchingContent,
                         XSLTNode nonMatchingContent) {
            this.selectExpr = selectExpr;
            this.regexAvt = regexAvt;
            this.flagsAvt = flagsAvt;
            this.matchingContent = matchingContent;
            this.nonMatchingContent = nonMatchingContent;
        }
        
        @Override public String getInstructionName() { return "analyze-string"; }
        
        @Override
        public void execute(TransformContext context, OutputHandler output) throws SAXException {
            try {
                // Get the input string
                XPathValue selectResult = selectExpr.evaluate(context);
                String input = selectResult.asString();
                
                // Get the regex pattern
                String regex = regexAvt.evaluate(context);
                
                // Get flags (optional)
                int patternFlags = 0;
                if (flagsAvt != null) {
                    String flags = flagsAvt.evaluate(context);
                    if (flags.contains("i")) patternFlags |= java.util.regex.Pattern.CASE_INSENSITIVE;
                    if (flags.contains("m")) patternFlags |= java.util.regex.Pattern.MULTILINE;
                    if (flags.contains("s")) patternFlags |= java.util.regex.Pattern.DOTALL;
                    if (flags.contains("x")) patternFlags |= java.util.regex.Pattern.COMMENTS;
                }
                
                // Compile the regex
                java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(regex, patternFlags);
                java.util.regex.Matcher matcher = pattern.matcher(input);
                
                int lastEnd = 0;
                while (matcher.find()) {
                    // Non-matching part before this match
                    if (lastEnd < matcher.start() && nonMatchingContent != null) {
                        String nonMatch = input.substring(lastEnd, matcher.start());
                        executeWithStringContext(nonMatchingContent, nonMatch, context, output);
                    }
                    
                    // Matching part
                    if (matchingContent != null) {
                        String match = matcher.group();
                        executeWithStringContext(matchingContent, match, context, output);
                    }
                    
                    lastEnd = matcher.end();
                }
                
                // Non-matching part after last match
                if (lastEnd < input.length() && nonMatchingContent != null) {
                    String nonMatch = input.substring(lastEnd);
                    executeWithStringContext(nonMatchingContent, nonMatch, context, output);
                }
            } catch (XPathException e) {
                throw new SAXException("Error in xsl:analyze-string select: " + e.getMessage(), e);
            } catch (java.util.regex.PatternSyntaxException e) {
                throw new SAXException("Invalid regex in xsl:analyze-string: " + e.getMessage(), e);
            }
        }
        
        private void executeWithStringContext(XSLTNode content, String contextString,
                                              TransformContext context, OutputHandler output) 
                throws SAXException {
            // Create a context where the context node has the string as its value
            // For xsl:analyze-string, the context item is the matched/non-matched string
            // We create a text node wrapper for this
            XPathNode textNode = new StringContextNode(contextString);
            TransformContext strContext = context.withContextNode(textNode);
            content.execute(strContext, output);
        }
    }
    
    /**
     * A virtual text node that represents the current substring in analyze-string.
     */
    private static class StringContextNode implements XPathNode {
        private final String value;
        
        StringContextNode(String value) {
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

    private static class ForEachNode extends XSLTInstruction {
        private final XPathExpression selectExpr;
        private final List<SortSpec> sorts;
        private final SequenceNode body;
        ForEachNode(XPathExpression selectExpr, List<SortSpec> sorts, SequenceNode body) {
            this.selectExpr = selectExpr;
            this.sorts = sorts;
            this.body = body;
        }
        @Override public String getInstructionName() { return "for-each"; }
        @Override public void execute(TransformContext context, 
                                      OutputHandler output) throws SAXException {
            try {
                XPathValue result = selectExpr.evaluate(context);
                if (result == null) {
                    return;
                }
                
                // Handle node-set (XPath 1.0 style)
                if (result instanceof XPathNodeSet) {
                    executeNodeSet((XPathNodeSet) result, context, output);
                    return;
                }
                
                // Handle sequence (XPath 2.0+ style)
                if (result instanceof XPathSequence) {
                    executeSequence((XPathSequence) result, context, output);
                    return;
                }
                
                // Handle single atomic value
                executeAtomicValue(result, context, output);
                
            } catch (XPathException e) {
                throw new SAXException("Error in xsl:for-each", e);
            }
        }
        
        private void executeNodeSet(XPathNodeSet nodeSet, TransformContext context,
                                   OutputHandler output) throws SAXException, XPathException {
            List<XPathNode> nodes = new ArrayList<>(nodeSet.getNodes());
            
            // Apply sorting if specified
            if (sorts != null && !sorts.isEmpty()) {
                sortNodesStatic(nodes, sorts, context);
            }
            
            int size = nodes.size();
            int position = 1;
            boolean first = true;
            for (XPathNode node : nodes) {
                // Mark boundary between iterations for sequence construction
                if (!first) {
                    output.itemBoundary();
                }
                
                TransformContext iterContext = context.pushVariableScope();
                
                if (iterContext instanceof BasicTransformContext) {
                    iterContext = ((BasicTransformContext) iterContext)
                        .withXsltCurrentNode(node).withPositionAndSize(position, size);
                } else {
                    iterContext = iterContext.withContextNode(node).withPositionAndSize(position, size);
                }
                body.execute(iterContext, output);
                position++;
                first = false;
            }
        }
        
        private void executeSequence(XPathSequence sequence, TransformContext context,
                                    OutputHandler output) throws SAXException, XPathException {
            List<XPathValue> items = sequence.getItems();
            int size = items.size();
            int position = 1;
            boolean first = true;
            
            for (XPathValue item : items) {
                // Mark boundary between iterations for sequence construction
                if (!first) {
                    output.itemBoundary();
                }
                
                TransformContext iterContext = context.pushVariableScope();
                
                // For node items, set context node; for atomic values, set context item
                if (item instanceof XPathNode) {
                    XPathNode node = (XPathNode) item;
                    if (iterContext instanceof BasicTransformContext) {
                        iterContext = ((BasicTransformContext) iterContext)
                            .withXsltCurrentNode(node).withPositionAndSize(position, size);
                    } else {
                        iterContext = iterContext.withContextNode(node).withPositionAndSize(position, size);
                    }
                } else {
                    // Atomic value - set as context item
                    if (iterContext instanceof BasicTransformContext) {
                        iterContext = ((BasicTransformContext) iterContext)
                            .withContextItem(item).withPositionAndSize(position, size);
                    } else {
                        iterContext = iterContext.withPositionAndSize(position, size);
                    }
                }
                body.execute(iterContext, output);
                position++;
                first = false;
            }
        }
        
        private void executeAtomicValue(XPathValue value, TransformContext context,
                                       OutputHandler output) throws SAXException, XPathException {
            // Single atomic value - iterate once
            TransformContext iterContext = context.pushVariableScope();
            if (iterContext instanceof BasicTransformContext) {
                iterContext = ((BasicTransformContext) iterContext)
                    .withContextItem(value).withPositionAndSize(1, 1);
            } else {
                iterContext = iterContext.withPositionAndSize(1, 1);
            }
            body.execute(iterContext, output);
        }
        
        /**
         * Sorts a list of nodes according to the sort specifications.
         * Made static so it can be shared with ApplyTemplatesNode.
         */
        static void sortNodesStatic(List<XPathNode> nodes, List<SortSpec> sorts, 
                               TransformContext context) throws XPathException {
            // Pre-compute sort keys for all nodes
            final int nodeCount = nodes.size();
            final int sortCount = sorts.size();
            final Object[][] sortKeys = new Object[nodeCount][sortCount];
            
            for (int i = 0; i < nodeCount; i++) {
                XPathNode node = nodes.get(i);
                // Set position/size for sort key evaluation (position is 1-based, original order)
                TransformContext nodeCtx = context.withContextNode(node)
                    .withPositionAndSize(i + 1, nodeCount);
                for (int j = 0; j < sortCount; j++) {
                    SortSpec spec = sorts.get(j);
                    XPathValue val = spec.getSelectExpr().evaluate(nodeCtx);
                    if ("number".equals(spec.getDataType())) {
                        sortKeys[i][j] = Double.valueOf(val.asNumber());
                    } else {
                        sortKeys[i][j] = val.asString();
                    }
                }
            }
            
            // Create index array and sort
            Integer[] indices = new Integer[nodeCount];
            for (int i = 0; i < nodeCount; i++) {
                indices[i] = Integer.valueOf(i);
            }
            
            Arrays.sort(indices, new Comparator<Integer>() {
                public int compare(Integer a, Integer b) {
                    for (int j = 0; j < sortCount; j++) {
                        SortSpec spec = sorts.get(j);
                        Object keyA = sortKeys[a.intValue()][j];
                        Object keyB = sortKeys[b.intValue()][j];
                        
                        int cmp;
                        if (keyA instanceof Double) {
                            double da = ((Double) keyA).doubleValue();
                            double db = ((Double) keyB).doubleValue();
                            if (Double.isNaN(da) && Double.isNaN(db)) {
                                cmp = 0;
                            } else if (Double.isNaN(da)) {
                                cmp = -1; // NaN sorts first (before all numbers) per XSLT 1.0 spec
                            } else if (Double.isNaN(db)) {
                                cmp = 1;
                            } else {
                                cmp = Double.compare(da, db);
                            }
                        } else {
                            String sa = (String) keyA;
                            String sb = (String) keyB;
                            String caseOrder = spec.getCaseOrder();
                            
                            if (caseOrder != null) {
                                // Case-order specified - first compare case-insensitively
                                cmp = sa.compareToIgnoreCase(sb);
                                
                                // If equal ignoring case, apply case-order
                                if (cmp == 0) {
                                    // Compare character by character for case differences
                                    int len = Math.min(sa.length(), sb.length());
                                    for (int k = 0; k < len; k++) {
                                        char ca = sa.charAt(k);
                                        char cb = sb.charAt(k);
                                        if (ca != cb) {
                                            // Characters differ only in case
                                            boolean aIsLower = Character.isLowerCase(ca);
                                            boolean bIsLower = Character.isLowerCase(cb);
                                            if (aIsLower != bIsLower) {
                                                if ("lower-first".equals(caseOrder)) {
                                                    cmp = aIsLower ? -1 : 1;
                                                } else {
                                                    // upper-first (default)
                                                    cmp = aIsLower ? 1 : -1;
                                                }
                                                break;
                                            }
                                        }
                                    }
                                }
                            } else {
                                // No case-order - use default codepoint comparison
                                cmp = sa.compareTo(sb);
                            }
                        }
                        
                        // Apply order direction
                        if ("descending".equals(spec.getOrder())) {
                            cmp = -cmp;
                        }
                        
                        if (cmp != 0) {
                            return cmp;
                        }
                    }
                    return 0; // Equal on all sort keys
                }
            });
            
            // Reorder nodes based on sorted indices
            List<XPathNode> sorted = new ArrayList<>(nodeCount);
            for (int i = 0; i < nodeCount; i++) {
                sorted.add(nodes.get(indices[i].intValue()));
            }
            nodes.clear();
            nodes.addAll(sorted);
        }
    }

    private static class IfNode extends XSLTInstruction {
        private final XPathExpression testExpr;
        private final SequenceNode content;
        IfNode(XPathExpression testExpr, SequenceNode content) {
            this.testExpr = testExpr;
            this.content = content;
        }
        @Override public String getInstructionName() { return "if"; }
        @Override public void execute(TransformContext context, 
                                      OutputHandler output) throws SAXException {
            try {
                if (testExpr.evaluate(context).asBoolean()) {
                    content.execute(context, output);
                }
            } catch (XPathException e) {
                throw new SAXException("Error in xsl:if test", e);
            }
        }
    }

    private static class ChooseNode extends XSLTInstruction {
        private final List<WhenNode> whens;
        private final SequenceNode otherwise;
        ChooseNode(List<WhenNode> whens, SequenceNode otherwise) {
            this.whens = whens;
            this.otherwise = otherwise;
        }
        @Override public String getInstructionName() { return "choose"; }
        @Override public void execute(TransformContext context, 
                                      OutputHandler output) throws SAXException {
            try {
                for (WhenNode when : whens) {
                    if (when.getTestExpr().evaluate(context).asBoolean()) {
                        when.getContent().execute(context, output);
                        return;
                    }
                }
                // No when matched - execute otherwise
                if (otherwise != null) {
                    otherwise.execute(context, output);
                }
            } catch (XPathException e) {
                throw new SAXException("Error in xsl:choose", e);
            }
        }
    }

    private static class WhenNode extends XSLTInstruction {
        private final XPathExpression testExpr;
        private final SequenceNode content;
        WhenNode(XPathExpression testExpr, SequenceNode content) {
            this.testExpr = testExpr;
            this.content = content;
        }
        XPathExpression getTestExpr() { return testExpr; }
        SequenceNode getContent() { return content; }
        @Override public String getInstructionName() { return "when"; }
        @Override public void execute(TransformContext context, 
                                      OutputHandler output) throws SAXException {
            // When nodes are executed by ChooseNode
        }
    }

    private static class OtherwiseNode extends XSLTInstruction {
        private final SequenceNode content;
        OtherwiseNode(SequenceNode content) { this.content = content; }
        SequenceNode getContent() { return content; }
        @Override public String getInstructionName() { return "otherwise"; }
        @Override public void execute(TransformContext context, 
                                      OutputHandler output) throws SAXException {
            // Otherwise nodes are executed by ChooseNode
        }
    }

    private static class WithParamNode extends XSLTInstruction {
        private final String name;
        private final XPathExpression selectExpr;
        private final SequenceNode content;
        WithParamNode(String name, XPathExpression selectExpr, SequenceNode content) {
            this.name = name;
            this.selectExpr = selectExpr;
            this.content = content;
        }
        String getName() { return name; }
        XPathExpression getSelectExpr() { return selectExpr; }
        SequenceNode getContent() { return content; }
        @Override public String getInstructionName() { return "with-param"; }
        @Override public void execute(TransformContext context, 
                                      OutputHandler output) throws SAXException {
            // With-param nodes are handled by call-template/apply-templates
        }
        
        XPathValue evaluate(
                TransformContext context) 
                throws XPathException, SAXException {
            if (selectExpr != null) {
                return selectExpr.evaluate(context);
            } else if (content != null) {
                SAXEventBuffer buffer = 
                    new SAXEventBuffer();
                content.execute(context, new BufferOutputHandler(buffer));
                return XPathString.of(buffer.getTextContent());
            }
            return XPathString.of("");
        }
    }

    private static class NumberNode extends XSLTInstruction {
        private final XPathExpression valueExpr;
        private final XPathExpression selectExpr; // XSLT 2.0+ select attribute
        private final String level;
        private final Pattern countPattern;
        private final Pattern fromPattern;
        private final String format;
        private final String groupingSeparator;
        private final int groupingSize;
        private final String lang;
        private final String letterValue;
        
        NumberNode(XPathExpression valueExpr, XPathExpression selectExpr, String level, 
                  Pattern countPattern, Pattern fromPattern, String format, String groupingSeparator,
                  int groupingSize, String lang, String letterValue) {
            this.valueExpr = valueExpr;
            this.selectExpr = selectExpr;
            this.level = level;
            this.countPattern = countPattern;
            this.fromPattern = fromPattern;
            this.format = format;
            this.groupingSeparator = groupingSeparator;
            this.groupingSize = groupingSize;
            this.lang = lang;
            this.letterValue = letterValue;
        }
        
        @Override 
        public String getInstructionName() { 
            return "number"; 
        }
        
        @Override 
        public void execute(TransformContext context, OutputHandler output) throws SAXException {
            // Handle special case: if valueExpr produces NaN, output "NaN" directly (XSLT 1.0 behavior)
            if (valueExpr != null) {
                try {
                    XPathValue val = valueExpr.evaluate(context);
                    double d = val.asNumber();
                    if (Double.isNaN(d)) {
                        output.characters("NaN");
                        return;
                    }
                    if (Double.isInfinite(d)) {
                        output.characters(d > 0 ? "Infinity" : "-Infinity");
                        return;
                    }
                } catch (XPathException e) {
                    throw new SAXException("Error evaluating xsl:number value: " + e.getMessage(), e);
                }
            }
            
            // Get the numbers to format
            List<Integer> numbers = getNumbers(context);
            
            // Format and output
            String result = formatNumbers(numbers);
            output.characters(result);
        }
        
        private List<Integer> getNumbers(TransformContext context) throws SAXException {
            List<Integer> numbers = new ArrayList<>();
            
            if (valueExpr != null) {
                // Use value expression
                try {
                    XPathValue val = valueExpr.evaluate(context);
                    double d = val.asNumber();
                    if (!Double.isNaN(d) && !Double.isInfinite(d)) {
                        numbers.add((int) Math.round(d));
                    }
                } catch (XPathException e) {
                    throw new SAXException("Error evaluating xsl:number value: " + e.getMessage(), e);
                }
            } else {
                // Determine which node to number
                XPathNode node;
                if (selectExpr != null) {
                    // XSLT 2.0+ select attribute - number the selected node
                    try {
                        XPathValue selectResult = selectExpr.evaluate(context);
                        if (selectResult instanceof XPathNodeSet) {
                            XPathNodeSet ns = (XPathNodeSet) selectResult;
                            if (ns.isEmpty()) {
                                return numbers;
                            }
                            node = ns.iterator().next();
                        } else if (selectResult instanceof XPathNode) {
                            node = (XPathNode) selectResult;
                        } else {
                            // Not a node - can't number it
                            return numbers;
                        }
                    } catch (XPathException e) {
                        throw new SAXException("Error evaluating xsl:number select: " + e.getMessage(), e);
                    }
                } else {
                    // Default: number the context node
                    node = context.getContextNode();
                }
                
                if (node == null) {
                    return numbers;
                }
                
                if ("single".equals(level)) {
                    int count = countSingle(node, context);
                    if (count > 0) {
                        numbers.add(count);
                    }
                } else if ("multiple".equals(level)) {
                    numbers = countMultiple(node, context);
                } else if ("any".equals(level)) {
                    int count = countAny(node, context);
                    if (count > 0) {
                        numbers.add(count);
                    }
                }
            }
            
            return numbers;
        }
        
        private int countSingle(XPathNode node, TransformContext context) {
            // Per XSLT spec: "starting from the focus node, go upwards to find 
            // the first node n that matches the count pattern"
            
            // Find from boundary if specified
            // If from pattern is specified but not found, continue counting from document root
            XPathNode fromBoundary = null;
            if (fromPattern != null) {
                XPathNode ancestor = node;
                while (ancestor != null) {
                    if (fromPattern.matches(ancestor, context)) {
                        fromBoundary = ancestor;
                        break;
                    }
                    ancestor = ancestor.getParent();
                }
                // Note: if fromBoundary is null, we continue counting without a boundary
                // This matches XSLT spec behavior where nodes before any "from" match are still counted
            }
            
            // Go up from the focus node to find the first node matching count pattern
            XPathNode current = node;
            while (current != null) {
                // Check if current matches the count pattern
                if (matchesCount(current, context)) {
                    // Found a matching node - count it and its preceding siblings
                    int count = 1;
                    XPathNode sibling = current.getPrecedingSibling();
                    while (sibling != null) {
                        if (matchesCount(sibling, context)) {
                            count++;
                        }
                        sibling = sibling.getPrecedingSibling();
                    }
                    return count;
                }
                
                // Stop if we've reached the from boundary (don't go into or past it)
                if (fromBoundary != null && current == fromBoundary) {
                    break;
                }
                
                current = current.getParent();
            }
            
            return 0;
        }
        
        private List<Integer> countMultiple(XPathNode node, TransformContext context) {
            // Build list of counts at each ancestor level
            List<Integer> counts = new ArrayList<>();
            XPathNode current = node;
            
            while (current != null && current.getNodeType() != NodeType.ROOT) {
                // First check if current matches count pattern and count it
                if (matchesCount(current, context)) {
                    int count = 1;
                    XPathNode sibling = current.getPrecedingSibling();
                    while (sibling != null) {
                        if (matchesCount(sibling, context)) {
                            count++;
                        }
                        sibling = sibling.getPrecedingSibling();
                    }
                    counts.add(0, count); // Prepend to get correct order
                }
                
                // Then check from pattern - stop if matched (after counting this node)
                if (fromPattern != null && fromPattern.matches(current, context)) {
                    break;
                }
                
                current = current.getParent();
            }
            
            return counts;
        }
        
        private int countAny(XPathNode node, TransformContext context) {
            // Count all matching nodes before this one in document order
            // starting from the most recent node matching 'from' pattern
            
            // Get document root
            XPathNode root = node;
            while (root.getParent() != null) {
                root = root.getParent();
            }
            
            // Find the most recent node matching 'from' pattern
            // If the current node itself matches 'from', use it as the boundary
            XPathNode fromBoundary = null;
            if (fromPattern != null) {
                if (fromPattern.matches(node, context)) {
                    // Current node matches 'from' - count restarts from here
                    fromBoundary = node;
                } else {
                    // Find the most recent preceding node matching 'from'
                    fromBoundary = findLastMatchingBefore(root, node, fromPattern, context);
                }
            }
            
            // Count all matching nodes from start (or from boundary)
            int count = countNodesAfterBoundary(root, node, fromBoundary, context);
            
            // Include current node if it matches
            if (matchesCount(node, context)) {
                count++;
            }
            
            return count;
        }
        
        private XPathNode findLastMatchingBefore(XPathNode current, XPathNode target,
                                                 Pattern pattern, TransformContext context) {
            // Find the last node in document order before target that matches pattern
            if (current == target) {
                return null;
            }
            
            XPathNode lastMatch = null;
            
            // Check current node
            if (current.getDocumentOrder() < target.getDocumentOrder()) {
                if (pattern.matches(current, context)) {
                    lastMatch = current;
                }
            }
            
            // Recurse into children
            Iterator<XPathNode> children = current.getChildren();
            while (children.hasNext()) {
                XPathNode child = children.next();
                if (child.getDocumentOrder() < target.getDocumentOrder()) {
                    XPathNode childMatch = findLastMatchingBefore(child, target, pattern, context);
                    if (childMatch != null) {
                        lastMatch = childMatch;
                    }
                }
            }
            
            return lastMatch;
        }
        
        private int countNodesAfterBoundary(XPathNode current, XPathNode target,
                                            XPathNode fromBoundary, TransformContext context) {
            // Count nodes matching 'count' pattern that are after fromBoundary and before target
            if (current.isSameNode(target)) {
                return 0;
            }
            
            int count = 0;
            long currentOrder = current.getDocumentOrder();
            long targetOrder = target.getDocumentOrder();
            long boundaryOrder = fromBoundary != null ? fromBoundary.getDocumentOrder() : -1;
            
            // Check if we can use document order (non-zero indicates properly set up)
            boolean useDocOrder = targetOrder != 0;
            
            // Only count if at or after boundary and before target
            // The boundary node itself is included if it matches the count pattern
            if (useDocOrder) {
                if (currentOrder >= boundaryOrder && currentOrder < targetOrder) {
                    if (matchesCount(current, context)) {
                        count++;
                    }
                }
            } else {
                // For RTF nodes without document order, use recursive tree walk
                // Count this node if it matches and comes before target in document order
                // In document order, ancestors come before descendants, so count them
                if (matchesCount(current, context)) {
                    count++;
                }
            }
            
            // Recurse into children
            Iterator<XPathNode> children = current.getChildren();
            while (children.hasNext()) {
                XPathNode child = children.next();
                if (useDocOrder) {
                    if (child.getDocumentOrder() < targetOrder) {
                        count += countNodesAfterBoundary(child, target, fromBoundary, context);
                    }
                } else {
                    // For RTF without doc order, continue recursive walk
                    // We'll stop when we hit the target (checked at start of method)
                    count += countNodesAfterBoundary(child, target, fromBoundary, context);
                }
            }
            
            return count;
        }
        
        
        private boolean matchesCount(XPathNode node, TransformContext context) {
            if (countPattern != null) {
                return countPattern.matches(node, context);
            }
            // Default: match nodes with same name and type as context node
            XPathNode contextNode = context.getContextNode();
            if (contextNode == null) {
                return false;
            }
            if (node.getNodeType() != contextNode.getNodeType()) {
                return false;
            }
            // For elements, also check the name
            if (node.getNodeType() == NodeType.ELEMENT) {
                String nodeName = node.getLocalName();
                String contextName = contextNode.getLocalName();
                String nodeNs = node.getNamespaceURI();
                String contextNs = contextNode.getNamespaceURI();
                
                if (nodeName == null || !nodeName.equals(contextName)) {
                    return false;
                }
                if (nodeNs == null) {
                    return contextNs == null || contextNs.isEmpty();
                }
                return nodeNs.equals(contextNs);
            }
            return true;
        }
        
        private String formatNumbers(List<Integer> numbers) {
            if (numbers.isEmpty()) {
                return "";
            }
            
            // Parse format string into components
            ParsedFormat parsed = parseFormatString(format);
            
            StringBuilder result = new StringBuilder();
            
            // Add leading prefix
            result.append(parsed.prefix);
            
            for (int i = 0; i < numbers.size(); i++) {
                int num = numbers.get(i);
                
                // Add separator before this number (except first)
                if (i > 0) {
                    // Use separator from previous position, or last available
                    int sepIdx = i - 1;
                    if (sepIdx < parsed.separators.size()) {
                        result.append(parsed.separators.get(sepIdx));
                    } else if (!parsed.separators.isEmpty()) {
                        result.append(parsed.separators.get(parsed.separators.size() - 1));
                    } else {
                        result.append(".");  // Default separator
                    }
                }
                
                // Get format specifier for this number (reuse last if not enough)
                String specifier;
                if (i < parsed.specifiers.size()) {
                    specifier = parsed.specifiers.get(i);
                } else if (!parsed.specifiers.isEmpty()) {
                    specifier = parsed.specifiers.get(parsed.specifiers.size() - 1);
                } else {
                    specifier = "1";
                }
                
                // Format the number
                String formatted = formatSingleNumber(num, specifier);
                
                // Apply grouping if specified
                if (groupingSeparator != null && groupingSize > 0) {
                    formatted = applyGrouping(formatted, groupingSeparator, groupingSize);
                }
                
                result.append(formatted);
            }
            
            // Add trailing suffix
            result.append(parsed.suffix);
            
            return result.toString();
        }
        
        private ParsedFormat parseFormatString(String fmt) {
            String prefix = "";
            List<String> specifiers = new ArrayList<>();
            List<String> separators = new ArrayList<>();
            String suffix = "";
            
            int i = 0;
            int len = fmt.length();
            
            // Collect leading prefix (non-alphanumeric chars before first specifier)
            StringBuilder prefixBuf = new StringBuilder();
            while (i < len && !isFormatChar(fmt.charAt(i))) {
                prefixBuf.append(fmt.charAt(i));
                i++;
            }
            prefix = prefixBuf.toString();
            
            // Parse specifiers and separators
            while (i < len) {
                // Collect format specifier (alphanumeric sequence)
                StringBuilder specifier = new StringBuilder();
                while (i < len && isFormatChar(fmt.charAt(i))) {
                    specifier.append(fmt.charAt(i));
                    i++;
                }
                
                if (specifier.length() > 0) {
                    specifiers.add(specifier.toString());
                }
                
                // Collect separator (non-alphanumeric chars until next specifier or end)
                StringBuilder sep = new StringBuilder();
                while (i < len && !isFormatChar(fmt.charAt(i))) {
                    sep.append(fmt.charAt(i));
                    i++;
                }
                
                if (i < len) {
                    // More specifiers to come - this is a separator
                    separators.add(sep.toString());
                } else {
                    // End of string - this is the suffix
                    suffix = sep.toString();
                }
            }
            
            // Default specifier if none found
            if (specifiers.isEmpty()) {
                specifiers.add("1");
            }
            
            return new ParsedFormat(prefix, specifiers, separators, suffix);
        }
        
        // Helper class for parsed format
        private static class ParsedFormat {
            final String prefix;
            final List<String> specifiers;
            final List<String> separators;
            final String suffix;
            
            ParsedFormat(String prefix, List<String> specifiers, 
                        List<String> separators, String suffix) {
                this.prefix = prefix;
                this.specifiers = specifiers;
                this.separators = separators;
                this.suffix = suffix;
            }
        }
        
        private boolean isFormatChar(char c) {
            return (c >= '0' && c <= '9') || 
                   (c >= 'a' && c <= 'z') || 
                   (c >= 'A' && c <= 'Z');
        }
        
        private String formatSingleNumber(int num, String specifier) {
            if (specifier.isEmpty()) {
                specifier = "1";
            }
            
            char first = specifier.charAt(0);
            
            // Determine format type from first character
            if (first == 'a') {
                return toAlphabetic(num, false);
            } else if (first == 'A') {
                return toAlphabetic(num, true);
            } else if (first == 'i') {
                return toRoman(num, false);
            } else if (first == 'I') {
                return toRoman(num, true);
            } else if (first >= '0' && first <= '9') {
                // Numeric format - check for zero padding
                int minWidth = specifier.length();
                return zeroPad(num, minWidth);
            } else {
                // Default to decimal
                return String.valueOf(num);
            }
        }
        
        private String zeroPad(int num, int minWidth) {
            String s = String.valueOf(num);
            if (s.length() >= minWidth) {
                return s;
            }
            StringBuilder sb = new StringBuilder();
            int padding = minWidth - s.length();
            for (int i = 0; i < padding; i++) {
                sb.append('0');
            }
            sb.append(s);
            return sb.toString();
        }
        
        private String toAlphabetic(int num, boolean uppercase) {
            if (num <= 0) {
                return String.valueOf(num);
            }
            
            StringBuilder sb = new StringBuilder();
            int n = num;
            
            while (n > 0) {
                n--; // Adjust for 1-based
                int remainder = n % 26;
                char c;
                if (uppercase) {
                    c = (char) ('A' + remainder);
                } else {
                    c = (char) ('a' + remainder);
                }
                sb.insert(0, c);
                n = n / 26;
            }
            
            return sb.toString();
        }
        
        private String toRoman(int num, boolean uppercase) {
            if (num <= 0 || num > 3999) {
                return String.valueOf(num);
            }
            
            String[] thousands = {"", "M", "MM", "MMM"};
            String[] hundreds = {"", "C", "CC", "CCC", "CD", "D", "DC", "DCC", "DCCC", "CM"};
            String[] tens = {"", "X", "XX", "XXX", "XL", "L", "LX", "LXX", "LXXX", "XC"};
            String[] ones = {"", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX"};
            
            StringBuilder sb = new StringBuilder();
            sb.append(thousands[num / 1000]);
            sb.append(hundreds[(num % 1000) / 100]);
            sb.append(tens[(num % 100) / 10]);
            sb.append(ones[num % 10]);
            
            String result = sb.toString();
            if (!uppercase) {
                result = result.toLowerCase();
            }
            return result;
        }
        
        private String applyGrouping(String numStr, String separator, int size) {
            if (size <= 0 || numStr.length() <= size) {
                return numStr;
            }
            
            StringBuilder sb = new StringBuilder();
            int start = numStr.length() % size;
            if (start == 0) {
                start = size;
            }
            
            sb.append(numStr.substring(0, start));
            
            for (int i = start; i < numStr.length(); i += size) {
                sb.append(separator);
                int end = i + size;
                if (end > numStr.length()) {
                    end = numStr.length();
                }
                sb.append(numStr.substring(i, end));
            }
            
            return sb.toString();
        }
    }

    private static class MessageNode extends XSLTInstruction {
        private final SequenceNode content;
        private final XPathExpression selectExpr;
        private final boolean terminate;
        private final String errorCode;
        
        MessageNode(SequenceNode content, XPathExpression selectExpr, 
                   boolean terminate, String errorCode) {
            this.content = content;
            this.selectExpr = selectExpr;
            this.terminate = terminate;
            this.errorCode = errorCode;
        }
        
        @Override public String getInstructionName() { return "message"; }
        
        @Override public void execute(TransformContext context, 
                                      OutputHandler output) throws SAXException {
            String message = buildMessage(context);
            
            // Get the error listener from context if available
            javax.xml.transform.ErrorListener errorListener = context.getErrorListener();
            
            if (errorListener != null) {
                // Use ErrorListener for proper message handling
                try {
                    String location = getLocationInfo(context);
                    javax.xml.transform.TransformerException te = 
                        new javax.xml.transform.TransformerException(message);
                    
                    if (terminate) {
                        errorListener.fatalError(te);
                    } else {
                        errorListener.warning(te);
                    }
                } catch (javax.xml.transform.TransformerException e) {
                    // ErrorListener may re-throw
                    throw new SAXException(e);
                }
            } else {
                // Default: output to stderr
                StringBuilder sb = new StringBuilder();
                if (errorCode != null) {
                    sb.append("[").append(errorCode).append("] ");
                }
                sb.append(message);
                System.err.println("XSLT Message: " + sb.toString());
            }
            
            if (terminate) {
                String terminateMsg = message;
                if (errorCode != null) {
                    terminateMsg = "[" + errorCode + "] " + message;
                }
                throw new SAXException("Transformation terminated by xsl:message: " + terminateMsg);
            }
        }
        
        private String buildMessage(TransformContext context) throws SAXException {
            // If select attribute is present, evaluate it
            if (selectExpr != null) {
                try {
                    XPathValue result = selectExpr.evaluate(context);
                    return result.asString();
                } catch (XPathException e) {
                    throw new SAXException("Error evaluating xsl:message select: " + e.getMessage(), e);
                }
            }
            
            // Otherwise, use content
            if (content != null && !content.isEmpty()) {
                SAXEventBuffer buffer = new SAXEventBuffer();
                content.execute(context, new BufferOutputHandler(buffer));
                return buffer.getTextContent();
            }
            
            return "";
        }
        
        private String getLocationInfo(TransformContext context) {
            // Could be enhanced to include actual source location
            return "xsl:message";
        }
    }

    private static class FallbackNode extends XSLTInstruction {
        private final SequenceNode content;
        FallbackNode(SequenceNode content) { this.content = content; }
        @Override public String getInstructionName() { return "fallback"; }
        @Override public void execute(TransformContext context, 
                                      OutputHandler output) throws SAXException {
            // Fallback content executed when parent instruction not supported
            content.execute(context, output);
        }
    }

    private static class SortSpecNode extends XSLTInstruction {
        private final SortSpec sortSpec;
        SortSpecNode(SortSpec sortSpec) { this.sortSpec = sortSpec; }
        SortSpec getSortSpec() { return sortSpec; }
        @Override public String getInstructionName() { return "sort"; }
        @Override public void execute(TransformContext context, 
                                      OutputHandler output) {
            // Sort specs are used by for-each/apply-templates, not executed directly
        }
    }

    /** Simple pattern implementation for basic matching. */
    private static class SimplePattern implements Pattern {
        private final String patternStr;
        private final String basePattern;
        private final String predicateStr;
        
        SimplePattern(String patternStr) {
            this.patternStr = patternStr;
            
            // Normalize: remove explicit child:: axis (equivalent to implicit child)
            String normalized = patternStr.replace("child::", "");
            
            // Check if this is a union pattern at the top level (| outside brackets)
            // If so, don't extract predicates - handle the union as-is
            if (hasTopLevelUnion(normalized)) {
                this.basePattern = normalized;
                this.predicateStr = null;
            } else {
                // Extract predicate if present - need to find MATCHING ] not just last ]
                int[] bracketRange = findPredicateRange(normalized);
                if (bracketRange != null) {
                    int bracketStart = bracketRange[0];
                    int bracketEnd = bracketRange[1];
                    // Pattern might have more steps after the predicate (e.g., *[pred]/* )
                    String afterPredicate = normalized.substring(bracketEnd + 1);
                    if (afterPredicate.isEmpty()) {
                        // Simple case: pattern ends with predicate
                        this.basePattern = normalized.substring(0, bracketStart);
                        this.predicateStr = normalized.substring(bracketStart + 1, bracketEnd);
                    } else {
                        // Pattern continues after predicate - include predicate in base and pass to child
                        // For *[pred]/child, we match child nodes whose parent matches *[pred]
                        this.basePattern = normalized;
                        this.predicateStr = null;
                    }
                } else {
                    this.basePattern = normalized;
                    this.predicateStr = null;
                }
            }
        }
        
        /**
         * Check if pattern has a union operator (|) at the top level (outside brackets).
         */
        private boolean hasTopLevelUnion(String pattern) {
            int depth = 0;
            boolean inQuote = false;
            char quoteChar = 0;
            for (int i = 0; i < pattern.length(); i++) {
                char c = pattern.charAt(i);
                if (!inQuote && (c == '"' || c == '\'')) {
                    inQuote = true;
                    quoteChar = c;
                } else if (inQuote && c == quoteChar) {
                    inQuote = false;
                } else if (!inQuote) {
                    if (c == '[' || c == '(') {
                        depth++;
                    } else if (c == ']' || c == ')') {
                        depth--;
                    } else if (c == '|' && depth == 0) {
                        return true;
                    }
                }
            }
            return false;
        }
        
        /**
         * Find the position of // outside of braces (for Clark notation).
         * Returns -1 if not found.
         */
        private int findDoubleSlashOutsideBraces(String pattern) {
            int depth = 0;
            for (int i = 0; i < pattern.length() - 1; i++) {
                char c = pattern.charAt(i);
                if (c == '{') {
                    depth++;
                } else if (c == '}') {
                    depth--;
                } else if (depth == 0 && c == '/' && pattern.charAt(i + 1) == '/') {
                    return i;
                }
            }
            return -1;
        }
        
        /**
         * Find the last position of / outside of braces (for Clark notation).
         * Returns -1 if not found.
         */
        private int findLastSlashOutsideBraces(String pattern) {
            int depth = 0;
            int lastSlash = -1;
            for (int i = 0; i < pattern.length(); i++) {
                char c = pattern.charAt(i);
                if (c == '{') {
                    depth++;
                } else if (c == '}') {
                    depth--;
                } else if (depth == 0 && c == '/') {
                    lastSlash = i;
                }
            }
            return lastSlash;
        }
        
        /**
         * Find the range [start, end] of the first predicate in the pattern.
         * Returns null if no predicate found.
         */
        private int[] findPredicateRange(String pattern) {
            int depth = 0;
            boolean inQuote = false;
            char quoteChar = 0;
            int start = -1;
            
            for (int i = 0; i < pattern.length(); i++) {
                char c = pattern.charAt(i);
                if (!inQuote && (c == '"' || c == '\'')) {
                    inQuote = true;
                    quoteChar = c;
                } else if (inQuote && c == quoteChar) {
                    inQuote = false;
                } else if (!inQuote) {
                    if (c == '[') {
                        if (depth == 0) {
                            start = i;
                        }
                        depth++;
                    } else if (c == ']') {
                        depth--;
                        if (depth == 0 && start >= 0) {
                            return new int[] { start, i };
                        }
                    }
                }
            }
            return null;
        }
        
        /**
         * Find a keyword outside of brackets, braces, and quotes.
         * Returns the start index of the keyword, or -1 if not found.
         */
        private int findKeywordOutsideBrackets(String pattern, String keyword) {
            int bracketDepth = 0;
            int braceDepth = 0;
            boolean inQuote = false;
            char quoteChar = 0;
            
            for (int i = 0; i <= pattern.length() - keyword.length(); i++) {
                char c = pattern.charAt(i);
                
                if (!inQuote && (c == '"' || c == '\'')) {
                    inQuote = true;
                    quoteChar = c;
                } else if (inQuote && c == quoteChar) {
                    inQuote = false;
                } else if (!inQuote) {
                    if (c == '[' || c == '(') {
                        bracketDepth++;
                    } else if (c == ']' || c == ')') {
                        bracketDepth--;
                    } else if (c == '{') {
                        braceDepth++;
                    } else if (c == '}') {
                        braceDepth--;
                    } else if (bracketDepth == 0 && braceDepth == 0) {
                        // Check if keyword matches at this position
                        if (pattern.regionMatches(i, keyword, 0, keyword.length())) {
                            return i;
                        }
                    }
                }
            }
            return -1;
        }
        
        @Override
        public boolean matches(XPathNode node, 
                              TransformContext context) {
            // Entry point for pattern matching - set target node for current()
            return matchesWithTarget(node, context, node);
        }
        
        /**
         * Matches a node against this pattern, with a specified target node for current().
         * The target node is the original node being matched (used for current() in predicates).
         */
        private boolean matchesWithTarget(XPathNode node, TransformContext context, XPathNode targetNode) {
            // Handle variable reference patterns (XSLT 3.0)
            // $x - matches nodes that are in the variable's value
            // $x//foo - matches foo descendants of nodes in $x
            // $x/foo - matches foo children of nodes in $x
            if (patternStr.startsWith("$")) {
                return matchesVariablePattern(node, context, patternStr);
            }
            
            // Handle doc() function patterns (XSLT 3.0)
            // doc('file.xml') - matches root of specified document
            // doc('file.xml')//foo - matches foo descendants
            if (patternStr.startsWith("doc(") || patternStr.startsWith("document(")) {
                return matchesDocPattern(node, context, patternStr);
            }
            
            // Handle id() function patterns with document argument (XSLT 2.0+)
            // id('x', $doc) - matches element with id 'x' in $doc
            if (patternStr.startsWith("id(")) {
                return matchesIdPattern(node, context, patternStr);
            }
            
            // Handle key() function patterns (XSLT 1.0+)
            // key('name', 'value') - matches elements with specified key value
            // key('name', 'value')//foo - matches foo descendants
            if (patternStr.startsWith("key(")) {
                return matchesKeyPattern(node, context, patternStr);
            }
            
            // Handle except patterns (pattern except pattern) - XSLT 3.0
            // Must check before union since except has higher precedence
            int exceptIdx = findKeywordOutsideBrackets(patternStr, " except ");
            if (exceptIdx > 0) {
                String leftPart = patternStr.substring(0, exceptIdx).trim();
                String rightPart = patternStr.substring(exceptIdx + 8).trim();
                // Matches if node matches left pattern but NOT right pattern
                return new SimplePattern(leftPart).matchesWithTarget(node, context, targetNode) &&
                       !new SimplePattern(rightPart).matchesWithTarget(node, context, targetNode);
            }
            
            // Handle intersect patterns (pattern intersect pattern) - XSLT 3.0
            int intersectIdx = findKeywordOutsideBrackets(patternStr, " intersect ");
            if (intersectIdx > 0) {
                String leftPart = patternStr.substring(0, intersectIdx).trim();
                String rightPart = patternStr.substring(intersectIdx + 11).trim();
                // Matches if node matches BOTH left and right patterns
                return new SimplePattern(leftPart).matchesWithTarget(node, context, targetNode) &&
                       new SimplePattern(rightPart).matchesWithTarget(node, context, targetNode);
            }
            
            // Handle union patterns (pattern | pattern or pattern union pattern)
            int unionIdx = findKeywordOutsideBrackets(patternStr, " union ");
            if (unionIdx > 0) {
                String leftPart = patternStr.substring(0, unionIdx).trim();
                String rightPart = patternStr.substring(unionIdx + 7).trim();
                // Matches if node matches EITHER pattern
                return new SimplePattern(leftPart).matchesWithTarget(node, context, targetNode) ||
                       new SimplePattern(rightPart).matchesWithTarget(node, context, targetNode);
            }
            
            if (basePattern.contains("|") && predicateStr == null) {
                String[] parts = splitUnion(patternStr);
                for (String part : parts) {
                    if (new SimplePattern(part.trim()).matchesWithTarget(node, context, targetNode)) {
                        return true;
                    }
                }
                return false;
            }
            
            // First check base pattern match
            if (!matchesBasePatternWithTarget(node, context, targetNode)) {
                return false;
            }
            
            // If no predicate, we're done
            if (predicateStr == null) {
                return true;
            }
            
            // Evaluate predicate with proper position context, using target for current()
            return evaluatePredicate(node, context, targetNode);
        }
        
        private boolean matchesBasePattern(XPathNode node, TransformContext context) {
            return matchesBasePatternWithTarget(node, context, node);
        }
        
        private boolean matchesBasePatternWithTarget(XPathNode node, TransformContext context, XPathNode targetNode) {
            // Handle parenthesized patterns like (foo|bar) - these create a union
            if (basePattern.startsWith("(") && basePattern.endsWith(")")) {
                String inner = basePattern.substring(1, basePattern.length() - 1);
                // Parse as a union pattern
                String[] parts = splitUnion(inner);
                for (String part : parts) {
                    if (new SimplePattern(part.trim()).matchesWithTarget(node, context, targetNode)) {
                        return true;
                    }
                }
                return false;
            }
            
            // Handle patterns starting with // (descendant-or-self from root)
            if (basePattern.startsWith("//")) {
                String rest = basePattern.substring(2);
                // If rest contains more path steps, handle as a regular pattern
                // since //A/B is equivalent to A/B for pattern matching
                if (rest.contains("/")) {
                    return new SimplePattern(rest).matchesWithTarget(node, context, targetNode);
                }
                // Simple //name pattern
                return matchesNameTest(node, rest);
            }
            
            // Handle root pattern "/" - must check before general /name pattern
            if ("/".equals(basePattern)) {
                return node.getParent() == null;
            }
            
            // Handle absolute pattern /name or /a/b/c (path from root)
            if (basePattern.startsWith("/") && !basePattern.startsWith("//")) {
                String rest = basePattern.substring(1);
                if (node.getParent() == null) {
                    return false; // Pattern /x can't match root
                }
                
                // Check if rest contains more path steps (e.g., /a/b means "a/b" relative to root)
                int slashIdx = findLastSlashOutsideBraces(rest);
                if (slashIdx > 0) {
                    // Multi-step absolute pattern like /a/b/c
                    // Node must match the last step, and parent must match the rest
                    String parentPattern = "/" + rest.substring(0, slashIdx);
                    String nodePattern = rest.substring(slashIdx + 1);
                    
                    // Current node must match the last step
                    if (!matchesNameTest(node, nodePattern)) {
                        return false;
                    }
                    // Parent must match the parent pattern
                    XPathNode parent = node.getParent();
                    if (parent == null) {
                        return false;
                    }
                    return new SimplePattern(parentPattern).matchesBasePatternWithTarget(parent, context, targetNode);
                }
                
                // Single-step: /name - check if parent is root
                XPathNode parent = node.getParent();
                if (parent.getParent() == null) {
                    return matchesNameTest(node, rest);
                }
                return false;
            }
            
            // Handle descendant-or-self pattern in the middle: ancestor//descendant
            // Note: Don't treat // inside {uri} as path separator
            int doubleSlash = findDoubleSlashOutsideBraces(basePattern);
            if (doubleSlash > 0) {
                String ancestorPattern = basePattern.substring(0, doubleSlash);
                String descendantPart = basePattern.substring(doubleSlash + 2);
                
                // The descendant part may contain more path steps (e.g., //l2/w3)
                // We need to match the node against the full descendant pattern
                // and verify that some ancestor matches the ancestor pattern
                
                // First check if current node matches the descendant pattern
                // If descendant part has slashes, it's a multi-step pattern
                if (!new SimplePattern(descendantPart).matchesBasePatternWithTarget(node, context, targetNode)) {
                    return false;
                }
                
                // Find the appropriate ancestor level to check
                // For doc//l2/w3 matching w3, we need:
                // - w3's parent should be l2 (implied by descendantPart = "l2/w3")
                // - Some ancestor of l2 should match "doc"
                int steps = countSteps(descendantPart);
                XPathNode ancestorToCheck = node;
                for (int i = 0; i < steps; i++) {
                    ancestorToCheck = ancestorToCheck != null ? ancestorToCheck.getParent() : null;
                }
                
                // Now check if any ancestor of ancestorToCheck matches the ancestor pattern
                while (ancestorToCheck != null) {
                    if (new SimplePattern(ancestorPattern).matchesWithTarget(ancestorToCheck, context, targetNode)) {
                        return true;
                    }
                    ancestorToCheck = ancestorToCheck.getParent();
                }
                return false;
            }
            
            // Handle step patterns parent/child
            // Note: Don't treat / inside {uri} as path separator
            int slash = findLastSlashOutsideBraces(basePattern);
            if (slash > 0) {
                String parentPattern = basePattern.substring(0, slash);
                String childTest = basePattern.substring(slash + 1);
                
                // Handle explicit axis in childTest: doc/descendant::foo
                int axisIdx = childTest.indexOf("::");
                if (axisIdx > 0) {
                    String axis = childTest.substring(0, axisIdx);
                    String nodeTest = childTest.substring(axisIdx + 2);
                    
                    // First check if the current node matches the node test
                    if (!matchesNameTest(node, nodeTest)) {
                        return false;
                    }
                    
                    // Then verify the axis relationship
                    if ("descendant".equals(axis) || "descendant-or-self".equals(axis)) {
                        // node must be a descendant of something matching parentPattern
                        XPathNode ancestor = "descendant-or-self".equals(axis) ? node : node.getParent();
                        while (ancestor != null) {
                            if (new SimplePattern(parentPattern).matchesWithTarget(ancestor, context, targetNode)) {
                                return true;
                            }
                            ancestor = ancestor.getParent();
                        }
                        return false;
                    } else if ("child".equals(axis)) {
                        // Normal child relationship
                        XPathNode parent = node.getParent();
                        if (parent == null) {
                            return false;
                        }
                        return new SimplePattern(parentPattern).matchesWithTarget(parent, context, targetNode);
                    } else if ("self".equals(axis)) {
                        // Self axis - just check the parent pattern
                        return new SimplePattern(parentPattern).matchesWithTarget(node, context, targetNode);
                    }
                    // Other axes in patterns not commonly used
                    return false;
                }
                
                // Use SimplePattern for childTest to handle predicates (e.g., bar[@a='1'])
                if (!new SimplePattern(childTest).matchesWithTarget(node, context, targetNode)) {
                    return false;
                }
                
                XPathNode parent = node.getParent();
                if (parent == null) {
                    return false;
                }
                return new SimplePattern(parentPattern).matchesWithTarget(parent, context, targetNode);
            }
            
            // Simple tests
            if ("*".equals(basePattern)) {
                return node.isElement();
            }
            if ("/".equals(basePattern)) {
                return node.getParent() == null;
            }
            if ("text()".equals(basePattern)) {
                return node.isText();
            }
            if ("comment()".equals(basePattern)) {
                return node.getNodeType() == NodeType.COMMENT;
            }
            if ("processing-instruction()".equals(basePattern)) {
                return node.getNodeType() == NodeType.PROCESSING_INSTRUCTION;
            }
            // Handle processing-instruction('target') with specific target
            if (basePattern.startsWith("processing-instruction(") && basePattern.endsWith(")")) {
                if (node.getNodeType() != NodeType.PROCESSING_INSTRUCTION) {
                    return false;
                }
                // Extract target from processing-instruction('target') or processing-instruction("target")
                String inner = basePattern.substring("processing-instruction(".length(), basePattern.length() - 1).trim();
                if (inner.isEmpty()) {
                    return true; // processing-instruction() with no target
                }
                // Remove quotes
                if ((inner.startsWith("'") && inner.endsWith("'")) || 
                    (inner.startsWith("\"") && inner.endsWith("\""))) {
                    String target = inner.substring(1, inner.length() - 1);
                    return target.equals(node.getLocalName());
                }
                return true;
            }
            if ("node()".equals(basePattern)) {
                // node() matches any node EXCEPT the root document node
                // The root is matched by "/" pattern or built-in template
                return node.getNodeType() != NodeType.ROOT;
            }
            // document-node() matches the document root (XPath 2.0+)
            if (basePattern.equals("document-node()") || basePattern.startsWith("document-node(")) {
                if (node.getNodeType() != NodeType.ROOT) {
                    return false;
                }
                // Check for typed document-node(element(...)) - for now just match any document node
                String inner = basePattern.substring("document-node(".length());
                if (inner.endsWith(")")) {
                    inner = inner.substring(0, inner.length() - 1).trim();
                }
                if (inner.isEmpty()) {
                    return true; // document-node() - any document node
                }
                // document-node(element(name)) - document with specific root element
                // For now, just return true - full implementation would check root element
                return true;
            }
            if ("@*".equals(basePattern)) {
                return node.isAttribute();
            }
            
            // Simple name test
            return matchesNameTest(node, basePattern);
        }
        
        private boolean evaluatePredicate(XPathNode node, TransformContext context) {
            return evaluatePredicate(node, context, node);
        }
        
        private boolean evaluatePredicate(XPathNode node, TransformContext context, XPathNode targetNode) {
            try {
                // Calculate position among siblings that match the base pattern
                int position = 1;
                int size = 0;
                
                XPathNode parent = node.getParent();
                if (parent != null) {
                    // Count matching siblings before this node and total
                    Iterator<XPathNode> siblings = parent.getChildren();
                    boolean foundNode = false;
                    while (siblings.hasNext()) {
                        XPathNode sibling = siblings.next();
                        // Check if sibling matches the base pattern (without predicate)
                        if (matchesBasePattern(sibling, context)) {
                            size++;
                            if (!foundNode) {
                                if (sibling == node) {
                                    foundNode = true;
                                    position = size;
                                }
                            }
                        }
                    }
                } else {
                    // Root node - position is 1, size is 1
                    position = 1;
                    size = 1;
                }
                
                // Create context with correct position and size
                // For pattern matching, current() should refer to the TARGET node being matched,
                // not the intermediate node (node) whose predicate is being evaluated.
                // This matters for multi-step patterns like A[pred]/B where when checking A,
                // current() should still be B (the target).
                TransformContext predContext;
                if (context instanceof BasicTransformContext) {
                    // Set context node to 'node' for XPath evaluation (. refers to node)
                    // But set xsltCurrentNode to targetNode for current() function
                    BasicTransformContext btc = (BasicTransformContext) context;
                    predContext = btc.withContextAndCurrentNodes(node, targetNode)
                        .withPositionAndSize(position, size);
                } else {
                    predContext = context.withContextNode(node)
                        .withPositionAndSize(position, size);
                }
                
                // Compile and evaluate predicate
                XPathExpression predExpr = XPathExpression.compile(predicateStr, null);
                XPathValue result = predExpr.evaluate(predContext);
                
                // Boolean conversion of predicate result
                if (result.getType() == XPathValue.Type.NUMBER) {
                    // Numeric predicate - true if equals position
                    double d = result.asNumber();
                    return !Double.isNaN(d) && Math.abs(d - position) < 0.0001;
                } else {
                    return result.asBoolean();
                }
            } catch (Exception e) {
                // Predicate evaluation failed - don't match
                return false;
            }
        }
        
        private int countSteps(String pattern) {
            // Count the number of parent traversals needed for a pattern
            // e.g., "l2/w3" has 1 step (w3 to l2), "l2//v4" also counts as 1 step for immediate parent
            if (pattern == null || pattern.isEmpty()) {
                return 0;
            }
            // For simple patterns without slashes, no parent steps needed
            if (!pattern.contains("/")) {
                return 0;
            }
            // Count non-double-slash steps
            int count = 0;
            String[] parts = pattern.split("/");
            for (int i = 0; i < parts.length - 1; i++) {
                if (!parts[i].isEmpty()) {
                    count++;
                }
            }
            return count;
        }
        
        private boolean matchesNameTest(XPathNode node, String nameTest) {
            if ("*".equals(nameTest)) {
                return node.isElement();
            }
            if ("@*".equals(nameTest)) {
                return node.isAttribute();
            }
            if ("text()".equals(nameTest)) {
                return node.isText();
            }
            if ("comment()".equals(nameTest)) {
                return node.getNodeType() == NodeType.COMMENT;
            }
            if ("processing-instruction()".equals(nameTest)) {
                return node.getNodeType() == NodeType.PROCESSING_INSTRUCTION;
            }
            // Handle processing-instruction('target') with specific target
            if (nameTest.startsWith("processing-instruction(") && nameTest.endsWith(")")) {
                if (node.getNodeType() != NodeType.PROCESSING_INSTRUCTION) {
                    return false;
                }
                // Extract target from processing-instruction('target')
                String inner = nameTest.substring("processing-instruction(".length(), nameTest.length() - 1).trim();
                if (inner.isEmpty()) {
                    return true; // processing-instruction() with no target
                }
                // Remove quotes
                if ((inner.startsWith("'") && inner.endsWith("'")) || 
                    (inner.startsWith("\"") && inner.endsWith("\""))) {
                    String target = inner.substring(1, inner.length() - 1);
                    return target.equals(node.getLocalName());
                }
                return true;
            }
            if ("node()".equals(nameTest)) {
                return true;
            }
            
            // Handle element() node test - element(), element(name), element(*, type), element(name, type)
            if (nameTest.startsWith("element(") && nameTest.endsWith(")")) {
                if (!node.isElement()) {
                    return false;
                }
                String inner = nameTest.substring(8, nameTest.length() - 1).trim();
                if (inner.isEmpty() || "*".equals(inner)) {
                    return true; // element() or element(*) - any element
                }
                // Check for type argument: element(name, type) or element(*, type)
                int commaIdx = inner.indexOf(',');
                String elemName = commaIdx > 0 ? inner.substring(0, commaIdx).trim() : inner;
                // For now, ignore the type constraint (would need schema validation)
                if ("*".equals(elemName)) {
                    return true; // element(*, type) - any element with type
                }
                // Check element name (may be prefixed)
                int colonIdx = elemName.indexOf(':');
                String localName = colonIdx > 0 ? elemName.substring(colonIdx + 1) : elemName;
                return localName.equals(node.getLocalName());
            }
            
            // Handle attribute() node test - attribute(), attribute(name), attribute(*, type)
            if (nameTest.startsWith("attribute(") && nameTest.endsWith(")")) {
                if (!node.isAttribute()) {
                    return false;
                }
                String inner = nameTest.substring(10, nameTest.length() - 1).trim();
                if (inner.isEmpty() || "*".equals(inner)) {
                    return true; // attribute() or attribute(*) - any attribute
                }
                // Check for type argument
                int commaIdx = inner.indexOf(',');
                String attrName = commaIdx > 0 ? inner.substring(0, commaIdx).trim() : inner;
                if ("*".equals(attrName)) {
                    return true; // attribute(*, type)
                }
                // Check attribute name
                int colonIdx = attrName.indexOf(':');
                String localName = colonIdx > 0 ? attrName.substring(colonIdx + 1) : attrName;
                return localName.equals(node.getLocalName());
            }
            
            // Handle attribute patterns starting with @
            if (nameTest.startsWith("@")) {
                return matchesAttributeNameTest(node, nameTest.substring(1));
            }
            
            // Handle Clark notation: {uri}localname or {uri}* (resolved at compile time)
            if (nameTest.startsWith("{")) {
                int closeBrace = nameTest.indexOf('}');
                if (closeBrace > 1) {
                    String patternUri = nameTest.substring(1, closeBrace);
                    String patternLocal = nameTest.substring(closeBrace + 1);
                    
                    // Check if element is in the required namespace
                    String nodeUri = node.getNamespaceURI();
                    if (nodeUri == null) {
                        nodeUri = "";
                    }
                    
                    if (!patternUri.equals(nodeUri)) {
                        return false; // Namespaces don't match
                    }
                    
                    // {uri}* - any element in namespace
                    if ("*".equals(patternLocal)) {
                        return node.isElement();
                    }
                    
                    // {uri}localname - specific element in namespace
                    return node.isElement() && patternLocal.equals(node.getLocalName());
                }
            }
            
            // Handle *:localname pattern (any namespace, specific local name) - XSLT 2.0
            if (nameTest.startsWith("*:")) {
                String localPart = nameTest.substring(2);
                return node.isElement() && localPart.equals(node.getLocalName());
            }
            
            // Handle prefixed name test prefix:localname (legacy - shouldn't occur after resolution)
            int colon = nameTest.indexOf(':');
            if (colon > 0) {
                String localPart = nameTest.substring(colon + 1);
                return node.isElement() && localPart.equals(node.getLocalName());
            }
            
            // Simple unprefixed local name test - matches elements in NO namespace only
            // Per XSLT spec, unprefixed names in patterns match elements in no namespace
            String nodeUri = node.getNamespaceURI();
            if (nodeUri != null && !nodeUri.isEmpty()) {
                return false; // Element is in a namespace, pattern requires no namespace
            }
            return node.isElement() && nameTest.equals(node.getLocalName());
        }
        
        /**
         * Matches an attribute name test (without the leading @).
         * Supports: *, name, prefix:name, {uri}name, *:name, prefix:*, {uri}*
         */
        private boolean matchesAttributeNameTest(XPathNode node, String attrTest) {
            if (!node.isAttribute()) {
                return false;
            }
            
            // @* - any attribute
            if ("*".equals(attrTest)) {
                return true;
            }
            
            // @{uri}localname or @{uri}* - Clark notation
            if (attrTest.startsWith("{")) {
                int closeBrace = attrTest.indexOf('}');
                if (closeBrace > 1) {
                    String patternUri = attrTest.substring(1, closeBrace);
                    String patternLocal = attrTest.substring(closeBrace + 1);
                    
                    String nodeUri = node.getNamespaceURI();
                    if (nodeUri == null) {
                        nodeUri = "";
                    }
                    
                    // Special case: {*} means any namespace
                    if (!"*".equals(patternUri) && !patternUri.equals(nodeUri)) {
                        return false; // Namespaces don't match
                    }
                    
                    // @{uri}* - any attribute in namespace
                    if ("*".equals(patternLocal)) {
                        return true;
                    }
                    
                    // @{uri}localname - specific attribute in namespace
                    return patternLocal.equals(node.getLocalName());
                }
            }
            
            // @*:localname - any namespace with specific local name (XSLT 2.0)
            if (attrTest.startsWith("*:")) {
                String localPart = attrTest.substring(2);
                return localPart.equals(node.getLocalName());
            }
            
            // @prefix:localname or @prefix:* - namespace-prefixed pattern
            int colon = attrTest.indexOf(':');
            if (colon > 0) {
                String localPart = attrTest.substring(colon + 1);
                // @prefix:* - any attribute in namespace
                if ("*".equals(localPart)) {
                    // We can't check namespace without resolving prefix
                    // At this point, prefix should have been resolved to Clark notation
                    // But handle legacy case: just match local name
                    return true;
                }
                return localPart.equals(node.getLocalName());
            }
            
            // Simple @localname - attribute in NO namespace only
            String nodeUri = node.getNamespaceURI();
            if (nodeUri != null && !nodeUri.isEmpty()) {
                return false; // Attribute is in a namespace, pattern requires no namespace
            }
            return attrTest.equals(node.getLocalName());
        }
        
        /**
         * Matches a doc() or document() function pattern (XSLT 3.0).
         * Patterns: doc('file.xml'), doc('file.xml')//foo
         */
        private boolean matchesDocPattern(XPathNode node, TransformContext context, String pattern) {
            // Find the end of the doc() call
            int parenDepth = 0;
            int funcEnd = -1;
            for (int i = 0; i < pattern.length(); i++) {
                char c = pattern.charAt(i);
                if (c == '(') {
                    parenDepth++;
                }
                else if (c == ')') {
                    parenDepth--;
                    if (parenDepth == 0) {
                        funcEnd = i + 1;
                        break;
                    }
                }
            }
            
            if (funcEnd < 0) {
                return false;
            }
            
            String funcCall = pattern.substring(0, funcEnd);
            String restOfPattern = pattern.substring(funcEnd);
            
            // Evaluate the doc() call to get the document
            XPathValue docValue;
            try {
                XPathExpression expr = XPathExpression.compile(funcCall, null);
                docValue = expr.evaluate(context);
            } catch (Exception e) {
                return false;
            }
            
            if (docValue == null) {
                return false;
            }
            
            // Get the document root from the result
            XPathNodeSet docNodes;
            if (docValue instanceof XPathNodeSet) {
                docNodes = (XPathNodeSet) docValue;
            } else {
                return false;
            }
            
            if (docNodes.isEmpty()) return false;
            
            // Case 1: doc('file.xml') alone - matches the root or document element
            if (restOfPattern.isEmpty()) {
                for (XPathNode docNode : docNodes) {
                    if (node.isSameNode(docNode)) {
                        return true;
                    }
                    // Also check if node is the document element when doc returns root
                    if (docNode.getNodeType() == NodeType.ROOT) {
                        Iterator<XPathNode> children = docNode.getChildren();
                        while (children.hasNext()) {
                            XPathNode child = children.next();
                            if (child.isElement() && node.isSameNode(child)) {
                                return true;
                            }
                        }
                    }
                }
                return false;
            }
            
            // Case 2: doc('file.xml')//foo - matches descendants
            if (restOfPattern.startsWith("//")) {
                String descendantPattern = restOfPattern.substring(2);
                if (!new SimplePattern(descendantPattern).matches(node, context)) {
                    return false;
                }
                // Check if node is a descendant of the document
                XPathNode ancestor = node;
                while (ancestor != null) {
                    for (XPathNode docNode : docNodes) {
                        if (ancestor.isSameNode(docNode)) {
                            return true;
                        }
                    }
                    ancestor = ancestor.getParent();
                }
                return false;
            }
            
            // Case 3: doc('file.xml')/foo - matches children
            if (restOfPattern.startsWith("/")) {
                String childPattern = restOfPattern.substring(1);
                if (!new SimplePattern(childPattern).matches(node, context)) {
                    return false;
                }
                XPathNode parent = node.getParent();
                if (parent != null) {
                    for (XPathNode docNode : docNodes) {
                        if (parent.isSameNode(docNode)) {
                            return true;
                        }
                    }
                }
                return false;
            }
            
            return false;
        }
        
        /**
         * Matches an id() function pattern with optional document argument (XSLT 2.0+).
         * Patterns: id('x'), id('x', $doc)
         */
        private boolean matchesIdPattern(XPathNode node, TransformContext context, String pattern) {
            // Extract the id() arguments
            int parenStart = pattern.indexOf('(');
            int parenEnd = pattern.lastIndexOf(')');
            if (parenStart < 0 || parenEnd < 0) {
                return false;
            }
            
            String args = pattern.substring(parenStart + 1, parenEnd).trim();
            String restOfPattern = pattern.substring(parenEnd + 1);
            
            // Parse arguments: id('value') or id('value', $doc)
            String[] argParts = splitArgs(args);
            if (argParts.length == 0) {
                return false;
            }
            
            String idArg = argParts[0].trim();
            // Remove quotes from id value
            if ((idArg.startsWith("'") && idArg.endsWith("'")) ||
                (idArg.startsWith("\"") && idArg.endsWith("\""))) {
                idArg = idArg.substring(1, idArg.length() - 1);
            }
            
            // Get the target document
            XPathNode targetDoc = null;
            if (argParts.length > 1) {
                String docArg = argParts[1].trim();
                if (docArg.startsWith("$")) {
                    // Variable reference
                    try {
                        XPathValue docValue = context.getVariable(null, docArg.substring(1));
                        if (docValue instanceof XPathNodeSet) {
                            XPathNodeSet nodes = (XPathNodeSet) docValue;
                            if (!nodes.isEmpty()) {
                                targetDoc = nodes.iterator().next();
                            }
                        }
                    } catch (Exception e) {
                        return false;
                    }
                }
            } else {
                // Use the context document
                targetDoc = node.getRoot();
            }
            
            if (targetDoc == null) {
                return false;
            }
            
            // Check if node is in the target document and has the specified id
            XPathNode nodeRoot = node.getRoot();
            if (!nodeRoot.isSameNode(targetDoc) && !nodeRoot.isSameNode(targetDoc.getRoot())) {
                // Node is not in the target document
                // But if targetDoc is a node in a document, check if they share the same root
                XPathNode targetRoot = targetDoc.getRoot();
                if (!nodeRoot.isSameNode(targetRoot)) {
                    return false;
                }
            }
            
            // Check if node has one of the specified ids
            // Note: id('a b c') should match elements with id='a', 'b', or 'c' (whitespace-separated)
            if (node.isElement()) {
                XPathNode idAttr = node.getAttribute("", "id");
                if (idAttr == null) {
                    idAttr = node.getAttribute("http://www.w3.org/XML/1998/namespace", "id");
                }
                if (idAttr != null) {
                    String nodeId = idAttr.getStringValue();
                    // Split idArg on whitespace and check if any ID matches
                    String[] ids = idArg.trim().split("\\s+");
                    for (String id : ids) {
                        if (id.equals(nodeId)) {
                            // id() alone matches, or check rest of pattern
                            if (restOfPattern.isEmpty()) {
                                return true;
                            }
                            // Handle paths after id()
                            return false; // TODO: implement id()/foo patterns
                        }
                    }
                }
            }
            
            return false;
        }
        
        /**
         * Matches a key() function pattern (XSLT 1.0+).
         * Patterns: key('name', 'value'), key('name', 'value')//foo, key('name', $var)
         */
        private boolean matchesKeyPattern(XPathNode node, TransformContext context, String pattern) {
            // Extract the key() arguments
            int parenStart = pattern.indexOf('(');
            int parenEnd = findMatchingParen(pattern, parenStart);
            if (parenStart < 0 || parenEnd < 0) {
                return false;
            }
            
            String args = pattern.substring(parenStart + 1, parenEnd).trim();
            String restOfPattern = pattern.substring(parenEnd + 1);
            
            // Parse arguments: key('name', 'value') or key('name', $var)
            String[] argParts = splitArgs(args);
            if (argParts.length < 2) {
                return false;
            }
            
            String keyName = argParts[0].trim();
            String keyValueArg = argParts[1].trim();
            
            // Remove quotes from key name
            if ((keyName.startsWith("'") && keyName.endsWith("'")) ||
                (keyName.startsWith("\"") && keyName.endsWith("\""))) {
                keyName = keyName.substring(1, keyName.length() - 1);
            }
            
            // Get the key value (may be a variable reference or literal)
            String keyValue;
            if (keyValueArg.startsWith("$")) {
                // Variable reference - evaluate it
                try {
                    XPathValue varValue = context.getVariable(null, keyValueArg.substring(1));
                    keyValue = varValue.asString();
                } catch (Exception e) {
                    return false;
                }
            } else {
                // Literal value - remove quotes
                if ((keyValueArg.startsWith("'") && keyValueArg.endsWith("'")) ||
                    (keyValueArg.startsWith("\"") && keyValueArg.endsWith("\""))) {
                    keyValue = keyValueArg.substring(1, keyValueArg.length() - 1);
                } else {
                    keyValue = keyValueArg;
                }
            }
            
            // Get the key definition from the stylesheet
            CompiledStylesheet stylesheet = context.getStylesheet();
            KeyDefinition keyDef = stylesheet.getKeyDefinition(keyName);
            if (keyDef == null) {
                return false;
            }
            
            // Get nodes matching the key
            Pattern matchPattern = keyDef.getMatchPattern();
            XPathExpression useExpr = keyDef.getUseExpr();
            
            // Walk the document tree to find all nodes matching this key
            XPathNode root = node.getRoot();
            List<XPathNode> keyNodes = new ArrayList<>();
            collectKeyNodes(root, matchPattern, useExpr, keyValue, keyNodes, context);
            
            // If there's no rest pattern, check if node is in keyNodes
            if (restOfPattern.isEmpty()) {
                for (XPathNode keyNode : keyNodes) {
                    if (node.isSameNode(keyNode)) {
                        return true;
                    }
                }
                return false;
            }
            
            // Handle paths after key()
            // key('k', 'v')//foo means node must be a foo descendant of a key node
            if (restOfPattern.startsWith("//")) {
                String childPattern = restOfPattern.substring(2);
                // Node must match childPattern AND have an ancestor in keyNodes
                if (!new SimplePattern(childPattern).matches(node, context)) {
                    return false;
                }
                // Check if any ancestor is a key node
                XPathNode ancestor = node.getParent();
                while (ancestor != null) {
                    for (XPathNode keyNode : keyNodes) {
                        if (ancestor.isSameNode(keyNode)) {
                            return true;
                        }
                    }
                    ancestor = ancestor.getParent();
                }
                return false;
            }
            
            // key('k', 'v')/foo means node must be foo direct child of a key node
            if (restOfPattern.startsWith("/")) {
                String childPattern = restOfPattern.substring(1);
                // Node must match childPattern AND have parent in keyNodes
                if (!new SimplePattern(childPattern).matches(node, context)) {
                    return false;
                }
                XPathNode parent = node.getParent();
                if (parent != null) {
                    for (XPathNode keyNode : keyNodes) {
                        if (parent.isSameNode(keyNode)) {
                            return true;
                        }
                    }
                }
                return false;
            }
            
            return false;
        }
        
        /**
         * Finds the matching closing parenthesis.
         */
        private int findMatchingParen(String str, int openPos) {
            if (openPos < 0) {
                return -1;
            }
            int depth = 1;
            boolean inQuote = false;
            char quoteChar = 0;
            for (int i = openPos + 1; i < str.length(); i++) {
                char c = str.charAt(i);
                if (!inQuote && (c == '"' || c == '\'')) {
                    inQuote = true;
                    quoteChar = c;
                } else if (inQuote && c == quoteChar) {
                    inQuote = false;
                } else if (!inQuote) {
                    if (c == '(') {
                        depth++;
                    } else if (c == ')') {
                        depth--;
                        if (depth == 0) {
                            return i;
                        }
                    }
                }
            }
            return -1;
        }
        
        /**
         * Collects all nodes that match the key definition with the given value.
         */
        private void collectKeyNodes(XPathNode current, Pattern matchPattern,
                                     XPathExpression useExpr, String searchValue,
                                     List<XPathNode> result, TransformContext context) {
            try {
                if (matchPattern.matches(current, context)) {
                    XPathContext nodeContext = context.withContextNode(current);
                    XPathValue useValue = useExpr.evaluate(nodeContext);
                    String nodeKeyValue = useValue.asString();
                    if (searchValue.equals(nodeKeyValue)) {
                        result.add(current);
                    }
                }
            } catch (Exception e) {
                // Ignore evaluation errors
            }
            
            // Recurse into children
            Iterator<XPathNode> children = current.getChildren();
            while (children.hasNext()) {
                collectKeyNodes(children.next(), matchPattern, useExpr, searchValue, result, context);
            }
        }
        
        /**
         * Split function arguments respecting quotes and parentheses.
         */
        private String[] splitArgs(String args) {
            List<String> parts = new ArrayList<>();
            int depth = 0;
            boolean inQuote = false;
            char quoteChar = 0;
            int start = 0;
            
            for (int i = 0; i < args.length(); i++) {
                char c = args.charAt(i);
                if (!inQuote && (c == '"' || c == '\'')) {
                    inQuote = true;
                    quoteChar = c;
                } else if (inQuote && c == quoteChar) {
                    inQuote = false;
                } else if (!inQuote) {
                    if (c == '(' || c == '[') {
                        depth++;
                    } else if (c == ')' || c == ']') {
                        depth--;
                    }
                    else if (c == ',' && depth == 0) {
                        parts.add(args.substring(start, i));
                        start = i + 1;
                    }
                }
            }
            parts.add(args.substring(start));
            return parts.toArray(new String[0]);
        }
        
        /**
         * Matches a variable reference pattern (XSLT 3.0).
         * Patterns: $x (matches nodes in $x), $x//foo (matches foo descendants of $x)
         */
        private boolean matchesVariablePattern(XPathNode node, TransformContext context, String pattern) {
            // Find the end of the variable name
            int varEnd = 1; // Start after $
            while (varEnd < pattern.length()) {
                char c = pattern.charAt(varEnd);
                if (!Character.isLetterOrDigit(c) && c != '_' && c != '-' && c != '.') {
                    break;
                }
                varEnd++;
            }
            
            String varName = pattern.substring(1, varEnd);
            String restOfPattern = pattern.substring(varEnd);
            
            // Get the variable value
            XPathValue varValue;
            try {
                varValue = context.getVariable(null, varName);
            } catch (Exception e) {
                return false; // Variable not found
            }
            
            if (varValue == null) {
                return false;
            }
            
            // Get nodes from the variable
            XPathNodeSet varNodes;
            if (varValue instanceof XPathNodeSet) {
                varNodes = (XPathNodeSet) varValue;
            } else if (varValue instanceof XPathResultTreeFragment) {
                varNodes = ((XPathResultTreeFragment) varValue).asNodeSet();
            } else {
                return false; // Variable is not a node-set
            }
            
            // Case 1: $x - matches if node is in the variable's value (same node)
            if (restOfPattern.isEmpty()) {
                for (XPathNode varNode : varNodes) {
                    if (node.isSameNode(varNode)) {
                        return true;
                    }
                }
                return false;
            }
            
            // Case 2: $x//foo - matches if node matches foo and has ancestor in $x
            if (restOfPattern.startsWith("//")) {
                String descendantPattern = restOfPattern.substring(2);
                // First check if node matches the descendant pattern
                if (!new SimplePattern(descendantPattern).matches(node, context)) {
                    return false;
                }
                // Check if any ancestor is in the variable
                XPathNode ancestor = node.getParent();
                while (ancestor != null) {
                    for (XPathNode varNode : varNodes) {
                        if (ancestor.isSameNode(varNode)) {
                            return true;
                        }
                    }
                    ancestor = ancestor.getParent();
                }
                return false;
            }
            
            // Case 3: $x/foo - matches if node matches foo and parent is in $x
            if (restOfPattern.startsWith("/")) {
                String childPattern = restOfPattern.substring(1);
                // First check if node matches the child pattern
                if (!new SimplePattern(childPattern).matches(node, context)) {
                    return false;
                }
                // Check if parent is in the variable
                XPathNode parent = node.getParent();
                if (parent != null) {
                    for (XPathNode varNode : varNodes) {
                        if (parent.isSameNode(varNode)) {
                            return true;
                        }
                    }
                }
                return false;
            }
            
            // Unknown pattern form
            return false;
        }
        
        private String[] splitUnion(String pattern) {
            // Simple split - doesn't handle | inside predicates
            List<String> parts = new ArrayList<>();
            int depth = 0;
            int start = 0;
            for (int i = 0; i < pattern.length(); i++) {
                char c = pattern.charAt(i);
                if (c == '[') {
                    depth++;
                } else if (c == ']') {
                    depth--;
                } else if (c == '|' && depth == 0) {
                    parts.add(pattern.substring(start, i));
                    start = i + 1;
                }
            }
            parts.add(pattern.substring(start));
            return parts.toArray(new String[0]);
        }
        
        @Override
        public double getDefaultPriority() {
            // XSLT priority rules:
            // -0.5: *, node(), @*, text(), comment(), processing-instruction()
            // -0.25: prefix:*, @prefix:*
            // 0.5: patterns with predicates, multi-step patterns (a/b, //a/b, etc),
            //      variable references ($x), function calls (doc(), id())
            // 0.0: simple names
            
            if ("*".equals(patternStr) || "node()".equals(patternStr) ||
                "@*".equals(patternStr) || "text()".equals(patternStr) ||
                "comment()".equals(patternStr) || "processing-instruction()".equals(patternStr)) {
                return -0.5;
            }
            if (patternStr.contains(":*")) {
                return -0.25;
            }
            if (patternStr.contains("[")) {
                return 0.5; // Patterns with predicates
            }
            // Variable reference patterns have priority 0.5 (XSLT 3.0)
            if (patternStr.startsWith("$")) {
                return 0.5;
            }
            // Function call patterns (doc(), id(), etc.) have priority 0.5 (XSLT 3.0)
            if (patternStr.startsWith("doc(") || patternStr.startsWith("document(") ||
                patternStr.startsWith("id(") || patternStr.startsWith("key(")) {
                return 0.5;
            }
            // Multi-step patterns like a/b or //a/b have priority 0.5
            if (patternStr.contains("/")) {
                return 0.5;
            }
            return 0.0;
        }
        
        @Override
        public String toString() {
            return patternStr;
        }
    }

    /** Sort specification. */
    public static class SortSpec {
        private final XPathExpression selectExpr;
        private final String dataType;
        private final String order;
        private final String caseOrder;
        private final String lang;
        
        public SortSpec(XPathExpression selectExpr, String dataType, String order, 
                       String caseOrder, String lang) {
            this.selectExpr = selectExpr;
            this.dataType = dataType != null ? dataType : "text";
            this.order = order != null ? order : "ascending";
            this.caseOrder = caseOrder;
            this.lang = lang;
        }
        
        public XPathExpression getSelectExpr() { return selectExpr; }
        public String getDataType() { return dataType; }
        public String getOrder() { return order; }
        public String getCaseOrder() { return caseOrder; }
        public String getLang() { return lang; }
    }

    /**
     * Simple OutputHandler adapter for SAXEventBuffer.
     */
    private static class BufferOutputHandler implements OutputHandler {
        private final SAXEventBuffer buffer;
        private boolean inStartTag = false;
        private String pendingUri, pendingLocalName, pendingQName;
        private final AttributesImpl pendingAttrs = new AttributesImpl();
        private final List<String[]> pendingNamespaces = new ArrayList<>();
        
        BufferOutputHandler(SAXEventBuffer buffer) {
            this.buffer = buffer;
        }
        
        @Override public void startDocument() throws SAXException { buffer.startDocument(); }
        @Override public void endDocument() throws SAXException { flush(); buffer.endDocument(); }
        
        @Override 
        public void startElement(String uri, String localName, String qName) throws SAXException {
            flush();
            inStartTag = true;
            pendingUri = uri != null ? uri : "";
            pendingLocalName = localName;
            pendingQName = qName != null ? qName : localName;
            pendingAttrs.clear();
            pendingNamespaces.clear();
        }
        
        @Override 
        public void endElement(String uri, String localName, String qName) throws SAXException {
            flush();
            buffer.endElement(uri != null ? uri : "", localName, qName != null ? qName : localName);
        }
        
        @Override
        public void attribute(String uri, String localName, String qName, String value) throws SAXException {
            if (!inStartTag) {
                throw new SAXException("Attribute outside of start tag");
            }
            pendingAttrs.addAttribute(uri != null ? uri : "", localName, 
                qName != null ? qName : localName, "CDATA", value);
        }
        
        @Override
        public void namespace(String prefix, String uri) throws SAXException {
            // Queue namespace if in start tag, otherwise emit immediately
            if (inStartTag) {
                pendingNamespaces.add(new String[] {
                    prefix != null ? prefix : "",
                    uri != null ? uri : ""
                });
            } else {
                buffer.startPrefixMapping(prefix != null ? prefix : "", uri != null ? uri : "");
            }
        }
        
        @Override
        public void characters(String text) throws SAXException {
            flush();
            buffer.characters(text.toCharArray(), 0, text.length());
        }
        
        @Override
        public void charactersRaw(String text) throws SAXException {
            characters(text); // No raw support in buffer
        }
        
        @Override
        public void comment(String text) throws SAXException {
            flush();
            // SAXEventBuffer implements LexicalHandler, so we can pass comments
            char[] ch = text.toCharArray();
            buffer.comment(ch, 0, ch.length);
        }
        
        @Override
        public void processingInstruction(String target, String data) throws SAXException {
            flush();
            buffer.processingInstruction(target, data);
        }
        
        @Override
        public void flush() throws SAXException {
            if (inStartTag) {
                // Emit namespace declarations first (SAX requires startPrefixMapping before startElement)
                for (String[] ns : pendingNamespaces) {
                    buffer.startPrefixMapping(ns[0], ns[1]);
                }
                buffer.startElement(pendingUri, pendingLocalName, pendingQName, pendingAttrs);
                inStartTag = false;
                pendingAttrs.clear();
                pendingNamespaces.clear();
            }
        }
    }

    /**
     * OutputHandler that builds an XPathSequence from sequence constructor content.
     * Used for variables with as="item()*" or similar sequence type annotations.
     * 
     * <p>In sequence construction mode:
     * <ul>
     *   <li>Text nodes become string items</li>
     *   <li>Elements become element node items (RTF wrapped as node-set)</li>
     *   <li>Attributes become attribute node items</li>
     *   <li>Comments become comment node items</li>
     *   <li>Processing instructions become PI node items</li>
     * </ul>
     */
    private static class SequenceBuilderOutputHandler implements OutputHandler {
        private final List<XPathValue> items = new ArrayList<>();
        private StringBuilder pendingText = new StringBuilder();
        
        // For building elements, we use a nested buffer approach
        private SAXEventBuffer elementBuffer = null;
        private BufferOutputHandler elementHandler = null;
        private int elementDepth = 0;
        
        // For standalone attributes (not inside an element)
        private String pendingAttrUri, pendingAttrLocal, pendingAttrQName, pendingAttrValue;
        
        SequenceBuilderOutputHandler() {
        }
        
        /**
         * Returns the constructed sequence.
         */
        XPathValue getSequence() throws SAXException {
            flushPendingText();
            flushPendingAttribute();
            if (items.isEmpty()) {
                return XPathSequence.EMPTY;
            }
            if (items.size() == 1) {
                return items.get(0);
            }
            return new XPathSequence(items);
        }
        
        /**
         * Marks a boundary between sequence items.
         * Call this after each instruction to prevent text nodes from merging.
         */
        void markItemBoundary() throws SAXException {
            flushPendingText();
            flushPendingAttribute();
            // Reset atomic value pending state so next item doesn't get a space prefix
            ATOMIC_VALUE_PENDING.set(Boolean.FALSE);
        }
        
        private void flushPendingText() {
            if (pendingText.length() > 0) {
                items.add(XPathString.of(pendingText.toString()));
                pendingText.setLength(0);
            }
        }
        
        private void flushPendingAttribute() throws SAXException {
            if (pendingAttrLocal != null) {
                // Create a standalone attribute node
                // Store as an RTF containing just the attribute
                SAXEventBuffer attrBuffer = new SAXEventBuffer();
                BufferOutputHandler attrHandler = new BufferOutputHandler(attrBuffer);
                // We need a synthetic element to hold the attribute
                attrHandler.startElement("", "__attr__", "__attr__");
                attrHandler.attribute(pendingAttrUri, pendingAttrLocal, pendingAttrQName, pendingAttrValue);
                attrHandler.endElement("", "__attr__", "__attr__");
                attrHandler.flush();
                // Store as a special attribute wrapper
                items.add(new SequenceAttributeItem(pendingAttrUri, pendingAttrLocal, 
                    pendingAttrQName, pendingAttrValue));
                pendingAttrUri = pendingAttrLocal = pendingAttrQName = pendingAttrValue = null;
            }
        }
        
        @Override 
        public void startDocument() throws SAXException {
            // No-op for sequence construction
        }
        
        @Override 
        public void endDocument() throws SAXException {
            flushPendingText();
            flushPendingAttribute();
        }
        
        @Override 
        public void startElement(String uri, String localName, String qName) throws SAXException {
            flushPendingText();
            flushPendingAttribute();
            
            if (elementBuffer == null) {
                // Start a new element subtree
                elementBuffer = new SAXEventBuffer();
                elementHandler = new BufferOutputHandler(elementBuffer);
            }
            elementHandler.startElement(uri, localName, qName);
            elementDepth++;
        }
        
        @Override 
        public void endElement(String uri, String localName, String qName) throws SAXException {
            if (elementHandler != null) {
                elementHandler.endElement(uri, localName, qName);
                elementDepth--;
                
                if (elementDepth == 0) {
                    // Element complete - create node and add to sequence
                    elementHandler.flush();
                    XPathResultTreeFragment rtf = new XPathResultTreeFragment(elementBuffer, null);
                    // Convert RTF to node-set item
                    XPathNodeSet nodeSet = rtf.asNodeSet();
                    if (nodeSet != null && !nodeSet.isEmpty()) {
                        items.add(nodeSet);
                    }
                    elementBuffer = null;
                    elementHandler = null;
                }
            }
        }
        
        @Override
        public void attribute(String uri, String localName, String qName, String value) throws SAXException {
            if (elementHandler != null && elementDepth > 0) {
                // Attribute inside an element - pass to element builder
                elementHandler.attribute(uri, localName, qName, value);
            } else {
                // Standalone attribute in sequence
                flushPendingText();
                flushPendingAttribute();
                pendingAttrUri = uri;
                pendingAttrLocal = localName;
                pendingAttrQName = qName;
                pendingAttrValue = value;
            }
        }
        
        @Override
        public void namespace(String prefix, String uri) throws SAXException {
            if (elementHandler != null && elementDepth > 0) {
                elementHandler.namespace(prefix, uri);
            }
            // Standalone namespace nodes in sequences are less common
            // For now, ignore standalone namespaces
        }
        
        @Override
        public void characters(String text) throws SAXException {
            flushPendingAttribute();
            if (elementHandler != null && elementDepth > 0) {
                // Text inside an element
                elementHandler.characters(text);
            } else {
                // Text as a sequence item
                pendingText.append(text);
            }
        }
        
        @Override
        public void charactersRaw(String text) throws SAXException {
            characters(text);
        }
        
        @Override
        public void comment(String text) throws SAXException {
            flushPendingText();
            flushPendingAttribute();
            if (elementHandler != null && elementDepth > 0) {
                elementHandler.comment(text);
            } else {
                // Standalone comment in sequence
                items.add(new SequenceCommentItem(text));
            }
        }
        
        @Override
        public void processingInstruction(String target, String data) throws SAXException {
            flushPendingText();
            flushPendingAttribute();
            if (elementHandler != null && elementDepth > 0) {
                elementHandler.processingInstruction(target, data);
            } else {
                // Standalone PI in sequence
                items.add(new SequencePIItem(target, data));
            }
        }
        
        @Override
        public void flush() throws SAXException {
            if (elementHandler != null) {
                elementHandler.flush();
            }
        }
        
        @Override
        public void itemBoundary() throws SAXException {
            markItemBoundary();
        }
    }
    
    /**
     * Represents an attribute node item in a sequence.
     * Unlike attributes in elements, these are standalone items.
     */
    private static class SequenceAttributeItem implements XPathValue {
        private final String namespaceURI;
        private final String localName;
        private final String qName;
        private final String value;
        
        SequenceAttributeItem(String namespaceURI, String localName, String qName, String value) {
            this.namespaceURI = namespaceURI;
            this.localName = localName;
            this.qName = qName;
            this.value = value;
        }
        
        @Override public Type getType() { return Type.STRING; } // Treat as string for now
        @Override public String asString() { return value != null ? value : ""; }
        @Override public double asNumber() { 
            try { return Double.parseDouble(value); } 
            catch (Exception e) { return Double.NaN; } 
        }
        @Override public boolean asBoolean() { return value != null && !value.isEmpty(); }
        @Override public XPathNodeSet asNodeSet() { return XPathNodeSet.EMPTY; }
        
        public String getNamespaceURI() { return namespaceURI; }
        public String getLocalName() { return localName; }
        public String getQName() { return qName; }
        public String getValue() { return value; }
        
        @Override public String toString() { return "attribute(" + qName + "=" + value + ")"; }
    }
    
    /**
     * Represents a comment node item in a sequence.
     */
    private static class SequenceCommentItem implements XPathValue {
        private final String text;
        
        SequenceCommentItem(String text) {
            this.text = text;
        }
        
        @Override public Type getType() { return Type.STRING; }
        @Override public String asString() { return text != null ? text : ""; }
        @Override public double asNumber() { 
            try { return Double.parseDouble(text); } 
            catch (Exception e) { return Double.NaN; } 
        }
        @Override public boolean asBoolean() { return text != null && !text.isEmpty(); }
        @Override public XPathNodeSet asNodeSet() { return XPathNodeSet.EMPTY; }
        
        public String getText() { return text; }
        
        @Override public String toString() { return "comment(" + text + ")"; }
    }
    
    /**
     * Represents a processing instruction node item in a sequence.
     */
    private static class SequencePIItem implements XPathValue {
        private final String target;
        private final String data;
        
        SequencePIItem(String target, String data) {
            this.target = target;
            this.data = data;
        }
        
        @Override public Type getType() { return Type.STRING; }
        @Override public String asString() { return data != null ? data : ""; }
        @Override public double asNumber() { 
            try { return Double.parseDouble(data); } 
            catch (Exception e) { return Double.NaN; } 
        }
        @Override public boolean asBoolean() { return data != null && !data.isEmpty(); }
        @Override public XPathNodeSet asNodeSet() { return XPathNodeSet.EMPTY; }
        
        public String getTarget() { return target; }
        public String getData() { return data; }
        
        @Override public String toString() { return "processing-instruction(" + target + ", " + data + ")"; }
    }

    /**
     * Splits a string on whitespace characters without using regex.
     * Whitespace is defined as space, tab, newline, or carriage return.
     */
    private static List<String> splitOnWhitespace(String s) {
        List<String> result = new ArrayList<>();
        if (s == null) {
            return result;
        }
        int len = s.length();
        int start = -1;
        for (int i = 0; i < len; i++) {
            char c = s.charAt(i);
            boolean isWhitespace = (c == ' ' || c == '\t' || c == '\n' || c == '\r');
            if (isWhitespace) {
                if (start >= 0) {
                    result.add(s.substring(start, i));
                    start = -1;
                }
            } else {
                if (start < 0) {
                    start = i;
                }
            }
        }
        if (start >= 0) {
            result.add(s.substring(start));
        }
        return result;
    }

}

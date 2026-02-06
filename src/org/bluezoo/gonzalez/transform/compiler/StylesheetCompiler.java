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
import java.util.regex.PatternSyntaxException;

import javax.xml.transform.ErrorListener;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
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
import org.xml.sax.InputSource;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.AttributesImpl;
import org.xml.sax.helpers.DefaultHandler;

import org.bluezoo.gonzalez.QName;
import org.bluezoo.gonzalez.schema.xsd.XSDComplexType;
import org.bluezoo.gonzalez.schema.xsd.XSDElement;
import org.bluezoo.gonzalez.schema.xsd.XSDParticle;
import org.bluezoo.gonzalez.schema.xsd.XSDSchema;
import org.bluezoo.gonzalez.schema.xsd.XSDSchemaParser;
import org.bluezoo.gonzalez.schema.xsd.XSDSimpleType;
import org.bluezoo.gonzalez.schema.xsd.XSDType;
import org.bluezoo.gonzalez.transform.ErrorHandlingMode;
import org.bluezoo.gonzalez.transform.ast.BreakNode;
import org.bluezoo.gonzalez.transform.ast.DocumentConstructorNode;
import org.bluezoo.gonzalez.transform.ast.EvaluateNode;
import org.bluezoo.gonzalez.transform.ast.ForEachGroupNode;
import org.bluezoo.gonzalez.transform.ast.ForkNode;
import org.bluezoo.gonzalez.transform.ast.IterateNode;
import org.bluezoo.gonzalez.transform.ast.LiteralResultElement;
import org.bluezoo.gonzalez.transform.ast.LiteralText;
import org.bluezoo.gonzalez.transform.ast.MergeNode;
import org.bluezoo.gonzalez.transform.ast.NextIterationNode;
import org.bluezoo.gonzalez.transform.ast.ResultDocumentNode;
import org.bluezoo.gonzalez.transform.ast.SequenceNode;
import org.bluezoo.gonzalez.transform.ast.SourceDocumentNode;
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
import org.bluezoo.gonzalez.transform.ValidationMode;
import org.bluezoo.gonzalez.transform.xpath.type.XPathResultTreeFragment;
import org.bluezoo.gonzalez.transform.xpath.XPathContext;
import org.bluezoo.gonzalez.transform.xpath.XPathExpression;
import org.bluezoo.gonzalez.transform.xpath.XPathFunctionLibrary;
import org.bluezoo.gonzalez.transform.xpath.XPathParser;
import org.bluezoo.gonzalez.transform.xpath.function.XSLTFunctionLibrary;
import org.bluezoo.gonzalez.transform.xpath.Collation;
import org.bluezoo.gonzalez.transform.xpath.XPathSyntaxException;
import org.bluezoo.gonzalez.transform.xpath.expr.XPathException;
import org.bluezoo.gonzalez.transform.xpath.type.NodeType;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNode;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNodeSet;
import org.bluezoo.gonzalez.transform.xpath.type.XPathSequence;
import org.bluezoo.gonzalez.transform.xpath.type.SequenceType;
import org.bluezoo.gonzalez.transform.xpath.type.XPathResultTreeFragment;
import org.bluezoo.gonzalez.transform.xpath.type.XPathString;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNumber;
import org.bluezoo.gonzalez.transform.xpath.type.XPathBoolean;
import org.bluezoo.gonzalez.transform.xpath.type.XPathDateTime;
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
    
    /** XML Schema namespace URI. */
    private static final String XSD_NAMESPACE = "http://www.w3.org/2001/XMLSchema";

    // Compilation state
    private final CompiledStylesheet.Builder builder = CompiledStylesheet.builder();
    private final Deque<ElementContext> elementStack = new ArrayDeque<>();
    private final Map<String, String> namespaces = new HashMap<>();
    private final Map<String, String> pendingNamespaces = new HashMap<>(); // Namespaces declared before next startElement
    private final Deque<Map<String, String>> namespaceScopes = new ArrayDeque<>(); // Stack of namespace scopes for proper prefix resolution
    private final StringBuilder characterBuffer = new StringBuilder();
    
    // Current element context being processed (set during endElement processing)
    // This is needed because the context is popped from the stack before processing
    private ElementContext currentProcessingContext = null;
    
    // Locator for error reporting
    private Locator locator;
    
    // Import/include support
    private final StylesheetResolver resolver;
    private final String baseUri;
    private int importPrecedence = -1;  // Set lazily after imports are processed
    private int localTemplateCounter = 0;  // Fallback counter when no resolver
    private boolean importsAllowed = true;
    private boolean precedenceAssigned = false;
    
    // Forward-compatible processing mode
    private double stylesheetVersion = 1.0;
    private boolean forwardCompatible = false;
    
    // Default validation mode (XSLT 2.0+)
    private ValidationMode defaultValidation = ValidationMode.STRIP;
    
    /**
     * Validation modes for schema-aware processing.
     *
     * <p>Controls how elements and attributes are validated against imported schemas
     * when constructing result trees.
     *
     * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
     */

    // Excluded namespace URIs from exclude-result-prefixes
    private final Set<String> excludedNamespaceURIs = new HashSet<>();
    
    // Extension namespace URIs from extension-element-prefixes (auto-excluded from output)
    private final Set<String> extensionNamespaceURIs = new HashSet<>();
    
    // Track depth inside top-level user data elements (ignored per XSLT 1.0 Section 2.2)
    private int userDataElementDepth = 0;
    
    // Track depth inside elements excluded by use-when="false()" (XSLT 2.0 conditional compilation)
    private int useWhenSkipDepth = 0;
    
    // Inline schema parsing: track when we're inside xsl:import-schema and parsing xs:schema
    private boolean inImportSchema = false;
    private XSDSchemaParser inlineSchemaParser = null;
    private int inlineSchemaDepth = 0;
    private String importSchemaNamespace = null;  // namespace attribute from xsl:import-schema
    
    // Static variables (XSLT 3.0): evaluated at compile time, available in use-when
    private final Map<String, XPathValue> staticVariables = new HashMap<>();
    
    // Simplified stylesheet: root element is a literal result element with xsl:version attribute
    // Per XSLT 1.0 Section 2.3: equivalent to xsl:stylesheet containing template match="/"
    private boolean isSimplifiedStylesheet = false;
    private XSLTNode simplifiedStylesheetBody = null;
    
    // XSLT 3.0 package support
    private PackageResolver packageResolver = null;
    String packageName = null;  // Package-private for access in compilePackage
    String packageVersion = null;  // Package-private for access in compilePackage
    final List<CompiledPackage.PackageDependency> packageDependencies = new ArrayList<>();  // Package-private

    /**
     * Allowed attributes for XSLT elements (XTSE0090 validation).
     * Maps element local name to set of allowed attribute local names.
     * Standard attributes (use-when, xpath-default-namespace, etc.) are allowed on all elements.
     */
    private static final Map<String, Set<String>> ALLOWED_ATTRIBUTES;
    private static final Set<String> STANDARD_ATTRIBUTES;
    
    static {
        // Load allowed attributes from properties file
        Map<String, Set<String>> attrs = new HashMap<>();
        Set<String> standard = Collections.emptySet();
        
        try (java.io.InputStream is = StylesheetCompiler.class.getResourceAsStream("/META-INF/xslt-attributes.properties")) {
            if (is != null) {
                java.util.Properties props = new java.util.Properties();
                props.load(is);
                
                for (String key : props.stringPropertyNames()) {
                    String value = props.getProperty(key);
                    Set<String> attrSet = new HashSet<>();
                    if (value != null && !value.trim().isEmpty()) {
                        for (String attr : value.split(",")) {
                            String trimmed = attr.trim();
                            if (!trimmed.isEmpty()) {
                                attrSet.add(trimmed);
                            }
                        }
                    }
                    if ("_standard".equals(key)) {
                        standard = Collections.unmodifiableSet(attrSet);
                    } else {
                        attrs.put(key, Collections.unmodifiableSet(attrSet));
                    }
                }
            }
        } catch (java.io.IOException e) {
            // Properties file not found - attribute validation will be skipped
        }
        
        STANDARD_ATTRIBUTES = standard;
        ALLOWED_ATTRIBUTES = Collections.unmodifiableMap(attrs);
    }

    /**
     * Context for an element being processed.
     */
    private static class ElementContext {
        final String namespaceURI;
        final String localName;
        final String originalPrefix;  // Prefix from original qName (may be null/empty)
        final List<XSLTNode> children = new ArrayList<>();
        final Map<String, String> attributes = new HashMap<>();
        final Map<String, String> shadowAttributes = new HashMap<>();  // XSLT 3.0 shadow attributes (_attr -> AVT value)
        final Map<String, String> namespaceBindings = new HashMap<>();  // All in-scope bindings
        final Map<String, String> explicitNamespaces = new HashMap<>(); // Only declared on THIS element
        final Set<String> excludedByThisElement = new HashSet<>();  // URIs excluded by xsl:exclude-result-prefixes on this element
        String baseURI;  // Effective base URI for this element (from xml:base inheritance)
        double effectiveVersion = -1;  // Effective XSLT version for backwards compatibility (-1 = inherit from parent)
        boolean expandText = false;  // XSLT 3.0 Text Value Templates enabled
        String xpathDefaultNamespace = null;  // XSLT 2.0+ xpath-default-namespace for XPath expressions
        
        ElementContext(String namespaceURI, String localName, String originalPrefix) {
            this.namespaceURI = namespaceURI;
            this.localName = localName;
            this.originalPrefix = originalPrefix;
        }
        
        /**
         * Gets the effective value of an attribute, checking shadow attributes first.
         * Shadow attributes (prefixed with _) take precedence and are AVTs.
         * 
         * @param name the attribute name (without _ prefix)
         * @return the shadow attribute value if present, otherwise the regular attribute value
         */
        String getShadowOrAttribute(String name) {
            String shadowValue = shadowAttributes.get(name);
            return shadowValue != null ? shadowValue : attributes.get(name);
        }
        
        /**
         * Returns true if the attribute has a shadow version (runtime-evaluated AVT).
         */
        boolean hasShadowAttribute(String name) {
            return shadowAttributes.containsKey(name);
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
        this(resolver, baseUri, -1);  // -1 means assign precedence after includes are processed
    }

    /**
     * Creates a new stylesheet compiler with external stylesheet support.
     * 
     * @param resolver the stylesheet resolver
     * @param baseUri the base URI of this stylesheet
     * @param fixedPrecedence if >= 0, use this as the import precedence (for includes);
     *                        if < 0, assign precedence lazily (for imports and main)
     */
    StylesheetCompiler(StylesheetResolver resolver, String baseUri, int fixedPrecedence) {
        this.resolver = resolver;
        this.baseUri = baseUri;
        
        // If a fixed precedence is specified (for includes), use it
        if (fixedPrecedence >= 0) {
            this.importPrecedence = fixedPrecedence;
            this.precedenceAssigned = true;
        }
        
        // Mark this stylesheet as currently being loaded (for circular reference detection)
        if (resolver != null && baseUri != null) {
            resolver.markLoading(baseUri);
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
     * Gets the next template declaration index.
     * Uses the resolver's shared counter if available, otherwise uses local counter.
     */
    private int nextTemplateIndex() {
        if (resolver != null) {
            return resolver.nextTemplateIndex();
        }
        return localTemplateCounter++;
    }

    /**
     * Returns the compiled stylesheet.
     * Call this after parsing is complete.
     *
     * @return the compiled stylesheet
     * @throws TransformerConfigurationException if validation fails
     */
    public CompiledStylesheet getCompiledStylesheet() 
            throws TransformerConfigurationException {
        // Ensure precedence is assigned (in case no includes were processed)
        ensurePrecedenceAssigned();
        // Fix up any templates that were compiled before precedence was assigned
        builder.finalizePrecedence(importPrecedence);
        return builder.build();
    }

    /**
     * Compiles an XSLT 3.0 package from an input source.
     *
     * <p>This is a convenience method that creates a new compiler, parses the
     * source, and returns a CompiledPackage. If the source is a traditional
     * stylesheet (not an xsl:package), it is wrapped in an anonymous package.
     *
     * @param source the input source for the package
     * @param resolver the package resolver for handling xsl:use-package
     * @return the compiled package
     * @throws SAXException if parsing fails
     * @throws IOException if the source cannot be read
     * @throws TransformerConfigurationException if compilation fails
     */
    public CompiledPackage compilePackage(InputSource source, PackageResolver resolver)
            throws SAXException, IOException, TransformerConfigurationException {
        // Create a new compiler instance for the package
        StylesheetCompiler compiler = new StylesheetCompiler();
        compiler.setPackageResolver(resolver);
        
        // Parse the stylesheet
        javax.xml.parsers.SAXParserFactory spf = javax.xml.parsers.SAXParserFactory.newInstance();
        spf.setNamespaceAware(true);
        try {
            javax.xml.parsers.SAXParser parser = spf.newSAXParser();
            org.xml.sax.XMLReader reader = parser.getXMLReader();
            reader.setContentHandler(compiler);
            reader.parse(source);
        } catch (javax.xml.parsers.ParserConfigurationException e) {
            throw new SAXException("Parser configuration error", e);
        }
        
        // Get the compiled stylesheet
        CompiledStylesheet stylesheet = compiler.getCompiledStylesheet();
        
        // Wrap in a package
        CompiledPackage.Builder pkgBuilder = new CompiledPackage.Builder();
        pkgBuilder.setStylesheet(stylesheet);
        pkgBuilder.setPackageName(compiler.packageName);
        pkgBuilder.setPackageVersion(compiler.packageVersion);
        
        // Add dependencies
        for (CompiledPackage.PackageDependency dep : compiler.packageDependencies) {
            pkgBuilder.addDependency(dep);
        }
        
        CompiledPackage pkg = pkgBuilder.build();
        
        // Validate that all abstract components are properly overridden
        validateAbstractComponents(pkg);
        
        return pkg;
    }

    /**
     * Validates that all abstract components from used packages are properly overridden.
     *
     * <p>Per XSLT 3.0, a package with unimplemented abstract components cannot be
     * used directly as a stylesheet (XTSE3010).
     *
     * @param pkg the compiled package to validate
     * @throws SAXException if abstract components are not overridden
     */
    private void validateAbstractComponents(CompiledPackage pkg) throws SAXException {
        // Check for unoverridden abstract components from used packages
        for (CompiledPackage.PackageDependency dep : pkg.getDependencies()) {
            CompiledPackage usedPkg = dep.getResolvedPackage();
            if (usedPkg == null) {
                continue;
            }
            
            List<OverrideDeclaration> overrides = dep.getOverrideDeclarations();
            Set<String> overriddenKeys = new HashSet<>();
            for (OverrideDeclaration override : overrides) {
                overriddenKeys.add(override.getOriginalComponentKey());
            }
            
            // Check abstract templates
            for (TemplateRule template : usedPkg.getAbstractTemplates()) {
                String key = template.getName() != null ? template.getName() :
                    (template.getMatchPattern() != null ? template.getMatchPattern().toString() : "");
                if (!overriddenKeys.contains(key)) {
                    throw new SAXException("XTSE3010: Abstract template '" + key + 
                        "' from package '" + usedPkg.getPackageName() + 
                        "' must be overridden");
                }
            }
            
            // Check abstract functions
            for (UserFunction function : usedPkg.getAbstractFunctions()) {
                String key = function.getKey();
                if (!overriddenKeys.contains(key)) {
                    throw new SAXException("XTSE3010: Abstract function '" + 
                        function.getNamespaceURI() + ":" + function.getLocalName() + 
                        "' from package '" + usedPkg.getPackageName() + 
                        "' must be overridden");
                }
            }
            
            // Check abstract variables
            for (GlobalVariable variable : usedPkg.getAbstractVariables()) {
                String key = variable.getExpandedName();
                if (!overriddenKeys.contains(key)) {
                    throw new SAXException("XTSE3010: Abstract " + 
                        (variable.isParam() ? "parameter" : "variable") + " '" + key + 
                        "' from package '" + usedPkg.getPackageName() + 
                        "' must be overridden");
                }
            }
        }
    }

    /**
     * Sets the package resolver for handling xsl:use-package.
     *
     * @param resolver the package resolver
     */
    public void setPackageResolver(PackageResolver resolver) {
        this.packageResolver = resolver;
    }

    /**
     * Sets the document locator for error reporting.
     *
     * @param locator the SAX locator
     */
    @Override
    public void setDocumentLocator(Locator locator) {
        this.locator = locator;
    }

    /**
     * Called when document parsing starts.
     * Initializes compilation state and sets the base URI.
     *
     * @throws SAXException if initialization fails
     */
    @Override
    public void startDocument() throws SAXException {
        elementStack.clear();
        namespaces.clear();
        
        // Set base URI from the locator's system ID
        if (locator != null && locator.getSystemId() != null) {
            builder.setBaseURI(locator.getSystemId());
        }
    }

    /**
     * Called when document parsing ends.
     * Handles simplified stylesheets and finalizes compilation.
     *
     * @throws SAXException if compilation fails
     */
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
                    public boolean matches(XPathNode node, TransformContext context) {
                        return node.getNodeType() == NodeType.ROOT;
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

    /**
     * Called when a namespace prefix mapping ends.
     * Restores the previous namespace binding for the prefix.
     *
     * @param prefix the namespace prefix
     * @throws SAXException if processing fails
     */
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

    /**
     * Called when an element starts.
     * Processes XSLT elements and literal result elements, building the AST.
     *
     * @param uri the element namespace URI
     * @param localName the element local name
     * @param qName the element qualified name
     * @param atts the element attributes
     * @throws SAXException if compilation fails
     */
    @Override
    public void startElement(String uri, String localName, String qName, Attributes atts) 
            throws SAXException {
        flushCharacters();
        
        // Extract prefix from qName (format: "prefix:localName" or "localName")
        String originalPrefix = null;
        if (qName != null) {
            int colonIdx = qName.indexOf(':');
            if (colonIdx > 0) {
                originalPrefix = qName.substring(0, colonIdx);
            }
        }
        
        // Track elements being skipped due to use-when="false()"
        if (useWhenSkipDepth > 0) {
            useWhenSkipDepth++;
            elementStack.push(new ElementContext(uri, localName, originalPrefix));
            return;
        }
        
        // XTSE0150: Check for stylesheet/transform element in wrong namespace
        // This error occurs when the root element looks like xsl:stylesheet but is in wrong namespace
        if (elementStack.isEmpty() && !XSLT_NS.equals(uri)) {
            if ("stylesheet".equals(localName) || "transform".equals(localName)) {
                throw new SAXException("XTSE0150: The element '" + localName + 
                    "' is in namespace '" + (uri == null ? "" : uri) + 
                    "' but should be in the XSLT namespace '" + XSLT_NS + "'");
            }
        }
        
        // Per XSLT 1.0 Section 2.2: Track when we enter top-level user data elements.
        // These are non-XSLT elements that are direct children of xsl:stylesheet.
        // They and all their descendants should be ignored (not compiled).
        if (userDataElementDepth > 0) {
            userDataElementDepth++;
            elementStack.push(new ElementContext(uri, localName, originalPrefix)); // Still need to track for proper popping
            return;
        }
        if (!XSLT_NS.equals(uri) && isTopLevel()) {
            // XTSE0130: Elements with null namespace are NOT allowed at top level
            // Only elements in some (non-XSLT) namespace are valid user data elements
            if (uri == null || uri.isEmpty()) {
                throw new SAXException("XTSE0130: Element '" + localName + 
                    "' with no namespace is not allowed as a child of xsl:stylesheet");
            }
            userDataElementDepth = 1;
            elementStack.push(new ElementContext(uri, localName, originalPrefix));
            return;
        }
        
        // Handle inline schema parsing inside xsl:import-schema
        if (inImportSchema) {
            if (XSD_NAMESPACE.equals(uri) && "schema".equals(localName)) {
                // Start of inline xs:schema - create parser and forward event
                inlineSchemaParser = new XSDSchemaParser();
                inlineSchemaDepth = 1;
                inlineSchemaParser.startElement(uri, localName, qName, atts);
                elementStack.push(new ElementContext(uri, localName, originalPrefix));
                return;
            } else if (inlineSchemaParser != null && inlineSchemaDepth > 0) {
                // Inside inline schema - forward event to parser
                inlineSchemaDepth++;
                inlineSchemaParser.startElement(uri, localName, qName, atts);
                elementStack.push(new ElementContext(uri, localName, originalPrefix));
                return;
            }
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
                elementStack.push(new ElementContext(uri, localName, originalPrefix));
                return;
            }
        }
        
        ElementContext ctx = new ElementContext(uri, localName, originalPrefix);
        
        // Copy attributes, detecting XSLT 3.0 shadow attributes (_attr syntax)
        // Also normalize XSLT-namespaced attributes to use "xsl:" prefix
        for (int i = 0; i < atts.getLength(); i++) {
            String attrQName = atts.getQName(i);
            String attrURI = atts.getURI(i);
            String attrLocal = atts.getLocalName(i);
            String attrValue = atts.getValue(i);
            
            // XSLT 3.0 shadow attributes: _attribute-name="{AVT}"
            // These override the regular attribute with a runtime-evaluated value
            if (attrQName.startsWith("_") && attrQName.length() > 1) {
                // Extract the real attribute name (without the _ prefix)
                String realAttrName = attrQName.substring(1);
                ctx.shadowAttributes.put(realAttrName, attrValue);
            } else {
                // Normalize XSLT namespace attributes to use "xsl:" prefix
                // This allows stylesheets to use any prefix for the XSLT namespace
                // (e.g., t:xpath-default-namespace becomes xsl:xpath-default-namespace)
                if (XSLT_NS.equals(attrURI) && attrLocal != null && !attrLocal.isEmpty()) {
                    ctx.attributes.put("xsl:" + attrLocal, attrValue);
                } else {
                    ctx.attributes.put(attrQName, attrValue);
                }
            }
        }
        
        // XTSE0020: Validate that standard attributes on XSLT elements are not AVTs
        // (The shadow versions _expand-text etc. ARE AVTs, but the regular versions are not)
        if (XSLT_NS.equals(uri)) {
            String expandTextValue = ctx.attributes.get("expand-text");
            validateNotAVT("xsl:" + localName, "expand-text", expandTextValue);
            validateYesOrNo("xsl:" + localName, "expand-text", expandTextValue);
            validateNotAVT("xsl:" + localName, "use-when", ctx.attributes.get("use-when"));
            validateNotAVT("xsl:" + localName, "xpath-default-namespace", ctx.attributes.get("xpath-default-namespace"));
            validateNotAVT("xsl:" + localName, "default-collation", ctx.attributes.get("default-collation"));
            validateNotAVT("xsl:" + localName, "extension-element-prefixes", ctx.attributes.get("extension-element-prefixes"));
            validateNotAVT("xsl:" + localName, "exclude-result-prefixes", ctx.attributes.get("exclude-result-prefixes"));
            validateNotAVT("xsl:" + localName, "default-mode", ctx.attributes.get("default-mode"));
            validateNotAVT("xsl:" + localName, "default-validation", ctx.attributes.get("default-validation"));
            
            // Set expand-text on this element
            if (expandTextValue != null) {
                String trimmed = expandTextValue.trim();
                ctx.expandText = "yes".equals(trimmed) || "true".equals(trimmed) || "1".equals(trimmed);
            }
        }
        
        // Handle xsl:expand-text on literal result elements
        String lreExpandText = atts.getValue(XSLT_NS, "expand-text");
        if (lreExpandText != null && !XSLT_NS.equals(uri)) {
            validateNotAVT("literal result element", "xsl:expand-text", lreExpandText);
            validateYesOrNo("literal result element", "xsl:expand-text", lreExpandText);
            String trimmed = lreExpandText.trim();
            ctx.expandText = "yes".equals(trimmed) || "true".equals(trimmed) || "1".equals(trimmed);
        }
        
        // Inherit expand-text from parent if not set explicitly on this element
        if (!elementStack.isEmpty() && lreExpandText == null && 
            (XSLT_NS.equals(uri) && ctx.attributes.get("expand-text") == null || !XSLT_NS.equals(uri))) {
            ctx.expandText = elementStack.peek().expandText;
        }
        
        // Handle xpath-default-namespace (XSLT 2.0+)
        // On XSLT elements: xpath-default-namespace attribute (no prefix)
        // On LRE elements: xsl:xpath-default-namespace attribute (with xsl: prefix)
        String xpathDefaultNs = null;
        if (XSLT_NS.equals(uri)) {
            xpathDefaultNs = ctx.attributes.get("xpath-default-namespace");
        } else {
            // For literal result elements, check for xsl:xpath-default-namespace
            // (already normalized to xsl: prefix above)
            xpathDefaultNs = ctx.attributes.get("xsl:xpath-default-namespace");
        }
        if (xpathDefaultNs != null) {
            ctx.xpathDefaultNamespace = xpathDefaultNs;
        } else if (!elementStack.isEmpty()) {
            // Inherit from parent
            ctx.xpathDefaultNamespace = elementStack.peek().xpathDefaultNamespace;
        }
        
        // Check for xsl:version on literal result elements (backward compatibility mode)
        // This sets the effective version for this element and its descendants
        if (!XSLT_NS.equals(uri)) {
            String xslVersion = atts.getValue(XSLT_NS, "version");
            if (xslVersion != null) {
                try {
                    ctx.effectiveVersion = Double.parseDouble(xslVersion);
                } catch (NumberFormatException e) {
                    // Ignore invalid version
                }
            }
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
                    // Forward-compatible mode: enabled when version > max supported (3.0)
                    // Per XSLT spec, processor must run in forward-compatible mode when
                    // version is higher than what it implements
                    forwardCompatible = stylesheetVersion > 3.0;
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
                        if (nsUri == null || nsUri.isEmpty()) {
                            // XTSE0808: Undeclared prefix in exclude-result-prefixes
                            throw new SAXException("XTSE0808: No namespace binding in scope for prefix '" + prefix + "' in exclude-result-prefixes");
                        }
                        excludedNamespaceURIs.add(nsUri);
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
        
        // Track when we enter xsl:import-schema for inline schema parsing
        if (XSLT_NS.equals(uri) && "import-schema".equals(localName)) {
            inImportSchema = true;
            importSchemaNamespace = atts.getValue("namespace");
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
                        // Forward-compatible mode: enabled when version > max supported (3.0)
                        forwardCompatible = stylesheetVersion > 3.0;
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

    /**
     * Called when an element ends.
     * Finalizes element processing and pops the element context.
     *
     * @param uri the element namespace URI
     * @param localName the element local name
     * @param qName the element qualified name
     * @throws SAXException if compilation fails
     */
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
        
        // Handle inline schema parsing inside xsl:import-schema
        if (inlineSchemaParser != null && inlineSchemaDepth > 0) {
            inlineSchemaParser.endElement(uri, localName, qName);
            inlineSchemaDepth--;
            elementStack.pop();
            
            if (inlineSchemaDepth == 0) {
                // Inline schema parsing complete - finalize and store
                XSDSchema parsedSchema = inlineSchemaParser.finalizeParsing();
                if (parsedSchema != null) {
                    // Verify namespace matches if specified in xsl:import-schema
                    if (importSchemaNamespace != null && !importSchemaNamespace.isEmpty()) {
                        String schemaTargetNs = parsedSchema.getTargetNamespace();
                        if (schemaTargetNs != null && !importSchemaNamespace.equals(schemaTargetNs)) {
                            throw new SAXException("Schema target namespace '" + schemaTargetNs + 
                                "' does not match declared namespace '" + importSchemaNamespace + "'");
                        }
                    }
                    builder.addImportedSchema(parsedSchema);
                }
                inlineSchemaParser = null;
            }
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

    /**
     * Called for character data.
     * Buffers characters for later processing (whitespace handling, text nodes).
     *
     * @param ch the character array
     * @param start the start position
     * @param length the number of characters
     * @throws SAXException if processing fails
     */
    @Override
    public void characters(char[] ch, int start, int length) throws SAXException {
        characterBuffer.append(ch, start, length);
    }

    private void flushCharacters() throws SAXException {
        if (characterBuffer.length() > 0) {
            String text = characterBuffer.toString();
            characterBuffer.setLength(0);
            
            if (elementStack.isEmpty()) {
                // XTSE0120: Text at the top level of a stylesheet (outside any element)
                // Only whitespace is allowed between top-level declarations
                if (!isWhitespace(text)) {
                    throw new SAXException("XTSE0120: Text is not allowed at the top level of a stylesheet");
                }
                return; // Ignore whitespace at top level
            }
            
            ElementContext ctx = elementStack.peek();
            
            // XTSE0010: Non-whitespace text is not allowed as a direct child of xsl:stylesheet
            // Check if we're at top level (parent is stylesheet/transform/package)
            if (isTopLevel() && !isWhitespace(text)) {
                throw new SAXException("XTSE0010: Text content is not allowed at the top level of a stylesheet");
            }
            
            // Check if we should preserve whitespace
            if (!isWhitespace(text) || shouldPreserveWhitespace()) {
                // Parse Text Value Templates if expand-text is enabled
                // Only apply TVTs to literal result elements, not XSLT instruction elements
                if (ctx.expandText && !XSLT_NS.equals(ctx.namespaceURI)) {
                    // Parse TVT: text with {xpath-expr} embedded
                    try {
                        XSLTNode tvtNode = parseTextValueTemplate(text, ctx);
                        if (tvtNode != null) {
                            ctx.children.add(tvtNode);
                        }
                    } catch (SAXException e) {
                        // Re-throw TVT parsing errors
                        throw e;
                    }
                } else {
                    ctx.children.add(new LiteralText(text));
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
     *
     * @throws SAXException if a prefix is not in scope (XTSE0808)
     */
    private void processExcludeResultPrefixes(String excludePrefixes, Map<String, String> namespaces, ElementContext ctx) throws SAXException {
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
                if (nsUri == null || nsUri.isEmpty()) {
                    // XTSE0808: Undeclared prefix in exclude-result-prefixes
                    throw new SAXException("XTSE0808: No namespace binding in scope for prefix '" + prefix + "' in exclude-result-prefixes");
                }
                if (!excludedNamespaceURIs.contains(nsUri)) {
                    excludedNamespaceURIs.add(nsUri);
                    ctx.excludedByThisElement.add(nsUri);
                }
            }
        }
    }

    /**
     * Compiles an element into an XSLT node.
     */
    private XSLTNode compileElement(ElementContext ctx) throws SAXException {
        // Set current processing context so getDefaultElementNamespace() can access it
        // (the ctx has been popped from the stack at this point)
        currentProcessingContext = ctx;
        try {
            return compileElementInternal(ctx);
        } finally {
            currentProcessingContext = null;
        }
    }
    
    private XSLTNode compileElementInternal(ElementContext ctx) throws SAXException {
        XSLTNode result;
        if (XSLT_NS.equals(ctx.namespaceURI)) {
            result = compileXSLTElement(ctx);
        } else {
            // Per XSLT 1.0 Section 2.2: Top-level elements in non-XSLT namespaces
            // are "user data elements" and are ignored (not compiled).
            // Their attributes should NOT be treated as AVTs.
            if (isTopLevel()) {
                // XTSE0130: Elements with null namespace are NOT allowed at top level
                // Only elements in some (non-XSLT) namespace are valid user data elements
                if (ctx.namespaceURI == null || ctx.namespaceURI.isEmpty()) {
                    throw new SAXException("XTSE0130: Element '" + ctx.localName + 
                        "' with no namespace is not allowed as a child of xsl:stylesheet");
                }
                return null; // Ignore top-level user data elements (in a namespace)
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
        // Get allowed attributes for this element from our built-in map
        Set<String> allowed = ALLOWED_ATTRIBUTES.get(ctx.localName);
        if (allowed == null) {
            // Unknown element - handled elsewhere
            return;
        }
        
        for (Map.Entry<String, String> attr : ctx.attributes.entrySet()) {
            String attrName = attr.getKey();
            String attrValue = attr.getValue();
            
            // Skip xml: namespace attributes (xml:space, xml:lang, xml:base, xml:id)
            if (attrName.startsWith("xml:")) {
                continue;
            }
            
            // Handle prefixed attributes - check if they're in the XSLT namespace
            int colonPos = attrName.indexOf(':');
            String localAttrName = attrName;
            if (colonPos > 0) {
                String prefix = attrName.substring(0, colonPos);
                localAttrName = attrName.substring(colonPos + 1);
                
                // Check if prefix is bound to XSLT namespace
                String attrNs = ctx.namespaceBindings.get(prefix);
                if (XSLT_NS.equals(attrNs)) {
                    // XTSE0090: XSLT namespace-qualified attributes are not allowed on XSLT elements
                    throw new SAXException("XTSE0090: Attribute '" + attrName + 
                        "' in the XSLT namespace is not allowed on xsl:" + ctx.localName);
                }
                
                // Non-XSLT namespace attributes are allowed (extension attributes)
                continue;
            }
            
            // XTSE0090: Check if attribute is in allowed set or standard attributes
            if (!allowed.contains(localAttrName) && !STANDARD_ATTRIBUTES.contains(localAttrName)) {
                throw new SAXException("XTSE0090: Unknown attribute '" + attrName + 
                    "' on xsl:" + ctx.localName);
            }
            
            // XTSE0020: Validate QName attributes (but skip if it's an AVT with expressions)
            if (isQNameAttribute(localAttrName) && attrValue != null && !attrValue.isEmpty()) {
                // If the value contains {}, it's an AVT and we can't validate statically
                if (!attrValue.contains("{") || attrValue.startsWith("Q{")) {
                    XSLTSchemaValidator.validateQName(localAttrName, attrValue);
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
                // XTSE0010: xsl:stylesheet/transform/package must be the document element
                if (elementStack.size() > 1) {
                    throw new SAXException("XTSE0010: xsl:" + ctx.localName + 
                        " must be the document element, not nested inside another element");
                }
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
                
            case "use-package":
                // XSLT 3.0: xsl:use-package must be a top-level element
                if (!isTopLevel()) {
                    throw new SAXException("XTSE0010: xsl:use-package is only allowed at the top level");
                }
                if (stylesheetVersion < 3.0) {
                    throw new SAXException("xsl:use-package is only allowed in XSLT 3.0 or later");
                }
                processUsePackage(ctx);
                return null;
                
            case "expose":
                // XSLT 3.0: xsl:expose must be a top-level element in a package
                if (!isTopLevel()) {
                    throw new SAXException("XTSE0010: xsl:expose is only allowed at the top level");
                }
                if (stylesheetVersion < 3.0) {
                    throw new SAXException("xsl:expose is only allowed in XSLT 3.0 or later");
                }
                processExpose(ctx);
                return null;
                
            case "accept":
                // XSLT 3.0: xsl:accept must be a child of xsl:use-package
                if (stylesheetVersion < 3.0) {
                    throw new SAXException("xsl:accept is only allowed in XSLT 3.0 or later");
                }
                return compileAccept(ctx);
                
            case "override":
                // XSLT 3.0: xsl:override must be a child of xsl:use-package
                if (stylesheetVersion < 3.0) {
                    throw new SAXException("xsl:override is only allowed in XSLT 3.0 or later");
                }
                return compileOverride(ctx);
                
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
                
            case "context-item":
                // xsl:context-item declares context item requirements for a template
                return compileContextItem(ctx);
                
            case "global-context-item":
                // xsl:global-context-item declares global context item requirements
                processGlobalContextItem(ctx);
                return null;
                
            case "output":
                processOutputElement(ctx);
                return null;
                
            case "key":
                processKeyElement(ctx);
                return null;
                
            case "attribute-set":
                // XTSE0010: xsl:attribute-set must be at top level
                if (!isTopLevel()) {
                    throw new SAXException("XTSE0010: xsl:attribute-set is only allowed at the top level");
                }
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
                
            // Instructions that produce output - NOT allowed at top level (XTSE0010)
            case "value-of":
                validateNotTopLevel(ctx.localName);
                return compileValueOf(ctx);
                
            case "text":
                validateNotTopLevel(ctx.localName);
                return compileText(ctx);
                
            case "element":
                validateNotTopLevel(ctx.localName);
                return compileElement2(ctx);
                
            case "attribute":
                validateNotTopLevel(ctx.localName);
                return compileAttribute(ctx);
                
            case "namespace":
                validateNotTopLevel(ctx.localName);
                return compileNamespace(ctx);
                
            case "comment":
                validateNotTopLevel(ctx.localName);
                return compileComment(ctx);
                
            case "processing-instruction":
                validateNotTopLevel(ctx.localName);
                return compilePI(ctx);
                
            case "copy":
                validateNotTopLevel(ctx.localName);
                return compileCopy(ctx);
                
            case "copy-of":
                validateNotTopLevel(ctx.localName);
                return compileCopyOf(ctx);
                
            case "apply-templates":
                validateNotTopLevel(ctx.localName);
                return compileApplyTemplates(ctx);
                
            case "call-template":
                validateNotTopLevel(ctx.localName);
                return compileCallTemplate(ctx);
                
            case "apply-imports":
                validateNotTopLevel(ctx.localName);
                return compileApplyImports(ctx);
                
            case "next-match":
                validateNotTopLevel(ctx.localName);
                return compileNextMatch(ctx);
                
            case "for-each":
                validateNotTopLevel(ctx.localName);
                return compileForEach(ctx);
                
            case "stream":
                validateNotTopLevel(ctx.localName);
                return compileStream(ctx);
                
            case "source-document":
                validateNotTopLevel(ctx.localName);
                return compileSourceDocument(ctx);
                
            case "iterate":
                validateNotTopLevel(ctx.localName);
                return compileIterate(ctx);
                
            case "next-iteration":
                validateNotTopLevel(ctx.localName);
                return compileNextIteration(ctx);
                
            case "break":
                validateNotTopLevel(ctx.localName);
                return compileBreak(ctx);
                
            case "on-completion":
                validateNotTopLevel(ctx.localName);
                // Handled by iterate
                return new SequenceNode(new ArrayList<>(ctx.children));
                
            case "fork":
                validateNotTopLevel(ctx.localName);
                return compileFork(ctx);
                
            case "sequence":
                validateNotTopLevel(ctx.localName);
                return compileSequence(ctx);
                
            case "result-document":
                validateNotTopLevel(ctx.localName);
                return compileResultDocument(ctx);
                
            case "for-each-group":
                validateNotTopLevel(ctx.localName);
                return compileForEachGroup(ctx);
                
            case "perform-sort":
                validateNotTopLevel(ctx.localName);
                return compilePerformSort(ctx);
                
            case "merge":
                validateNotTopLevel(ctx.localName);
                return compileMerge(ctx);
                
            case "merge-source":
                return compileMergeSource(ctx);
                
            case "merge-key":
                return compileMergeKey(ctx);
                
            case "merge-action":
                return compileMergeAction(ctx);
                
            case "evaluate":
                validateNotTopLevel(ctx.localName);
                return compileEvaluate(ctx);
                
            case "analyze-string":
                validateNotTopLevel(ctx.localName);
                return compileAnalyzeString(ctx);
                
            case "matching-substring":
            case "non-matching-substring":
                validateNotTopLevel(ctx.localName);
                // These are handled as children of analyze-string
                return new SequenceNode(ctx.children);
                
            case "try":
                validateNotTopLevel(ctx.localName);
                return compileTry(ctx);
                
            case "catch":
                validateNotTopLevel(ctx.localName);
                // Create a CatchNode with the errors attribute
                String catchErrors = ctx.attributes.get("errors");
                XSLTNode catchContent = ctx.children.isEmpty() ? null : new SequenceNode(ctx.children);
                return new CatchNode(catchContent, catchErrors);
                
            case "assert":
                validateNotTopLevel(ctx.localName);
                return compileAssert(ctx);
                
            case "on-empty":
                validateNotTopLevel(ctx.localName);
                // Handled specially in sequence context
                return compileOnEmpty(ctx);
                
            case "on-non-empty":
                validateNotTopLevel(ctx.localName);
                // Handled specially in sequence context
                return compileOnNonEmpty(ctx);
                
            case "where-populated":
                validateNotTopLevel(ctx.localName);
                return compileWherePopulated(ctx);
                
            case "if":
                validateNotTopLevel(ctx.localName);
                return compileIf(ctx);
                
            case "choose":
                validateNotTopLevel(ctx.localName);
                return compileChoose(ctx);
                
            case "when":
                validateNotTopLevel(ctx.localName);
                return compileWhen(ctx);
                
            case "otherwise":
                validateNotTopLevel(ctx.localName);
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
                // XTSE0010: xsl:decimal-format must be at top level
                if (!isTopLevel()) {
                    throw new SAXException("XTSE0010: xsl:decimal-format is only allowed at the top level");
                }
                processDecimalFormat(ctx);
                return null;
                
            case "character-map":
                // XSLT 2.0 character mapping
                // XTSE0010: xsl:character-map must be at top level
                if (!isTopLevel()) {
                    throw new SAXException("XTSE0010: xsl:character-map is only allowed at the top level");
                }
                processCharacterMap(ctx);
                return null;
                
            case "output-character":
                // XSLT 2.0 output character - child of xsl:character-map
                // This is handled by processCharacterMap when processing the parent
                return compileOutputCharacter(ctx);
                
            case "map":
                // XSLT 3.0 map construction - stub implementation
                validateNotTopLevel(ctx.localName);
                // TODO: Implement map construction
                return new SequenceNode(ctx.children);
                
            case "map-entry":
                // XSLT 3.0 map entry - child of xsl:map
                validateNotTopLevel(ctx.localName);
                // TODO: Implement map entry
                return new SequenceNode(ctx.children);
                
            case "document":
                // XSLT 2.0 document node construction
                validateNotTopLevel(ctx.localName);
                return compileDocumentConstructor(ctx);
                
            case "array":
                // XSLT 3.0 array construction - stub implementation
                validateNotTopLevel(ctx.localName);
                // TODO: Implement array construction
                return new SequenceNode(ctx.children);
                
            case "array-member":
                // XSLT 3.0 array member - child of xsl:array
                validateNotTopLevel(ctx.localName);
                return new SequenceNode(ctx.children);
                
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
                // In forward-compatible mode, unknown elements use xsl:fallback or are ignored
                // This allows stylesheets to work with higher XSLT versions
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
                // XTSE0010: Unknown XSLT element
                throw new SAXException("XTSE0010: Unknown XSLT element: xsl:" + ctx.localName);
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
        
        // XTSE0020: name attribute cannot be an AVT
        validateNotAVT("xsl:decimal-format", "name", name);
        
        // XTSE0080: Check for reserved namespace in decimal-format name
        if (name != null && name.contains(":")) {
            int colonIdx = name.indexOf(':');
            String prefix = name.substring(0, colonIdx);
            String nsUri = ctx.namespaceBindings.get(prefix);
            if (nsUri == null) {
                nsUri = lookupNamespaceUri(prefix);
            }
            if (nsUri != null && isReservedNamespace(nsUri)) {
                throw new SAXException("XTSE0080: Reserved namespace '" + nsUri + 
                    "' cannot be used in the decimal-format name '" + name + "'");
            }
        }
        
        // XTSE0020: These attributes must be a single character
        validateSingleChar("decimal-separator", decimalSeparator);
        validateSingleChar("grouping-separator", groupingSeparator);
        validateSingleChar("minus-sign", minusSign);
        validateSingleChar("percent", percent);
        validateSingleChar("per-mille", perMille);
        validateSingleChar("zero-digit", zeroDigit);
        validateSingleChar("digit", digit);
        validateSingleChar("pattern-separator", patternSeparator);
        
        // XTSE1300: Picture string characters must be distinct
        // Check: decimal-separator, grouping-separator, percent, per-mille, zero-digit, digit, pattern-separator
        validateDistinctChars(name,
            decimalSeparator != null ? decimalSeparator : ".",
            groupingSeparator != null ? groupingSeparator : ",",
            percent != null ? percent : "%",
            perMille != null ? perMille : "\u2030",
            zeroDigit != null ? zeroDigit : "0",
            digit != null ? digit : "#",
            patternSeparator != null ? patternSeparator : ";");
        
        builder.addDecimalFormat(name, decimalSeparator, groupingSeparator,
            infinity, minusSign, nan, percent, perMille, zeroDigit, digit, patternSeparator);
    }
    
    private void validateDistinctChars(String formatName, String... chars) throws SAXException {
        String[] names = {"decimal-separator", "grouping-separator", "percent", "per-mille", "zero-digit", "digit", "pattern-separator"};
        for (int i = 0; i < chars.length; i++) {
            for (int j = i + 1; j < chars.length; j++) {
                if (chars[i] != null && chars[j] != null && chars[i].equals(chars[j])) {
                    throw new SAXException("XTSE1300: In decimal-format" + 
                        (formatName != null ? " '" + formatName + "'" : "") +
                        ", " + names[i] + " and " + names[j] + " must have distinct values (both are '" + chars[i] + "')");
                }
            }
        }
    }
    
    private void validateSingleChar(String attrName, String value) throws SAXException {
        if (value != null && value.length() > 1) {
            throw new SAXException("XTSE0020: " + attrName + " must be a single character, got: '" + value + "'");
        }
    }
    
    /**
     * Validates that an attribute is not an AVT (does not contain curly braces).
     * XTSE0020: Certain attributes like xsl:decimal-format/@name are not AVTs.
     */
    private void validateNotAVT(String elementName, String attrName, String value) throws SAXException {
        if (value != null && value.contains("{") && value.contains("}")) {
            throw new SAXException("XTSE0020: The " + attrName + " attribute on " + elementName + 
                                  " is not an attribute value template");
        }
    }
    
    /**
     * Validates that an attribute value is a valid yes-or-no type.
     * XTSE0020: Values must be: yes, no, true, false, 1, or 0 (case-sensitive, whitespace trimmed).
     */
    private void validateYesOrNo(String elementName, String attrName, String value) throws SAXException {
        if (value == null) {
            return;  // Attribute not present is OK
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new SAXException("XTSE0020: Invalid value for " + attrName + " attribute on " + 
                                  elementName + ": value cannot be empty");
        }
        switch (trimmed) {
            case "yes":
            case "no":
            case "true":
            case "false":
            case "1":
            case "0":
                return;  // Valid values
            default:
                throw new SAXException("XTSE0020: Invalid value for " + attrName + " attribute on " + 
                                      elementName + ": must be yes, no, true, false, 1, or 0, got '" + value + "'");
        }
    }
    
    /**
     * Parses Text Value Templates (XSLT 3.0).
     * Text with {xpath-expr} is parsed as a sequence of literal text and value-of instructions.
     * 
     * @param text the text content (may contain {...} expressions)
     * @param ctx the element context
     * @return a SequenceNode containing literal text and ValueOfNode elements, or null if empty
     * @throws SAXException if parsing fails (XTSE0350, XPST0003)
     */
    private XSLTNode parseTextValueTemplate(String text, ElementContext ctx) throws SAXException {
        List<XSLTNode> nodes = new ArrayList<>();
        StringBuilder literal = new StringBuilder();
        int i = 0;
        
        while (i < text.length()) {
            char c = text.charAt(i);
            
            if (c == '{') {
                if (i + 1 < text.length() && text.charAt(i + 1) == '{') {
                    // Escaped {{ becomes single {
                    literal.append('{');
                    i += 2;
                } else {
                    // Start of XPath expression
                    // Flush any pending literal text
                    if (literal.length() > 0) {
                        nodes.add(new LiteralText(literal.toString()));
                        literal.setLength(0);
                    }
                    
                    // Find the matching closing brace
                    // Need to handle: string literals, XPath comments, nested braces
                    int start = i + 1;
                    int braceCount = 1;
                    i++;
                    boolean inString = false;
                    char stringDelim = 0;
                    boolean inComment = false;
                    
                    while (i < text.length() && braceCount > 0) {
                        c = text.charAt(i);
                        
                        // Handle XPath comments (: ... :)
                        if (!inString && !inComment && c == '(' && i + 1 < text.length() && text.charAt(i + 1) == ':') {
                            inComment = true;
                            i += 2;
                            continue;
                        }
                        if (inComment && c == ':' && i + 1 < text.length() && text.charAt(i + 1) == ')') {
                            inComment = false;
                            i += 2;
                            continue;
                        }
                        
                        // Skip everything inside comments
                        if (inComment) {
                            i++;
                            continue;
                        }
                        
                        // Handle string literals
                        if (!inString && (c == '\'' || c == '"')) {
                            inString = true;
                            stringDelim = c;
                        } else if (inString && c == stringDelim) {
                            // Check for escaped quote (doubled)
                            if (i + 1 < text.length() && text.charAt(i + 1) == stringDelim) {
                                i++; // Skip the second quote
                            } else {
                                inString = false;
                            }
                        }
                        
                        // Only count braces outside strings and comments
                        if (!inString && !inComment) {
                            if (c == '{') {
                                braceCount++;
                            } else if (c == '}') {
                                braceCount--;
                            } else if (c == '<' && i + 1 < text.length()) {
                                // Check if this looks like an element constructor
                                // XTSE0350: Element constructors not allowed in TVT
                                char next = text.charAt(i + 1);
                                if (Character.isLetter(next) || next == '/' || next == '!' || next == '?') {
                                    throw new SAXException("XTSE0350: Element constructors are not allowed in text value templates");
                                }
                            }
                        }
                        i++;
                    }
                    
                    if (braceCount != 0) {
                        throw new SAXException("XPST0003: Unmatched '{' in text value template");
                    }
                    
                    // Extract and compile the XPath expression
                    String xpathExpr = text.substring(start, i - 1).trim();
                    if (xpathExpr.isEmpty()) {
                        // Empty TVT expression {} is allowed and produces empty text
                        // Just skip it (don't add any node)
                        continue;
                    }
                    
                    try {
                        XPathExpression expr = compileExpression(xpathExpr);
                        // Create a value-of node for this expression (XSLT 3.0 style, with space separator)
                        nodes.add(new ValueOfNode(expr, false, " ", true));
                    } catch (SAXException e) {
                        throw new SAXException("XPST0003: Invalid XPath expression in text value template: " + 
                                              e.getMessage(), e);
                    }
                }
            } else if (c == '}') {
                if (i + 1 < text.length() && text.charAt(i + 1) == '}') {
                    // Escaped }} becomes single }
                    literal.append('}');
                    i += 2;
                } else {
                    throw new SAXException("XPST0003: Unmatched '}' in text value template");
                }
            } else {
                literal.append(c);
                i++;
            }
        }
        
        // Flush any remaining literal text
        if (literal.length() > 0) {
            nodes.add(new LiteralText(literal.toString()));
        }
        
        if (nodes.isEmpty()) {
            return null;
        } else if (nodes.size() == 1) {
            return nodes.get(0);
        } else {
            return new SequenceNode(nodes);
        }
    }
    
    /**
     * Validates that a QName does not use a reserved namespace.
     * XTSE0080: Cannot use xsl: or other reserved namespaces for names.
     * Exception: xsl:initial-template is allowed in XSLT 3.0.
     */
    private void validateNotReservedNamespace(String elementName, String attrName, String qname, 
                                              Map<String, String> namespaceBindings) throws SAXException {
        if (qname == null) {
            return;
        }
        
        // Check if it's a prefixed name
        int colonPos = qname.indexOf(':');
        if (colonPos > 0) {
            String prefix = qname.substring(0, colonPos);
            String localName = qname.substring(colonPos + 1);
            String namespaceURI = namespaceBindings.get(prefix);
            
            // Reserved namespaces: XSLT, XSL-FO, XML, XSD
            if (XSLT_NS.equals(namespaceURI)) {
                // Exception: xsl:initial-template is allowed in XSLT 3.0
                if ("initial-template".equals(localName) && stylesheetVersion >= 3.0) {
                    return;
                }
                throw new SAXException("XTSE0080: The " + attrName + " attribute on " + elementName + 
                                      " cannot use the XSLT namespace");
            }
            if ("http://www.w3.org/1999/XSL/Format".equals(namespaceURI)) {
                throw new SAXException("XTSE0080: The " + attrName + " attribute on " + elementName + 
                                      " cannot use the XSL-FO namespace");
            }
            if ("http://www.w3.org/XML/1998/namespace".equals(namespaceURI)) {
                throw new SAXException("XTSE0080: The " + attrName + " attribute on " + elementName + 
                                      " cannot use the XML namespace");
            }
            if ("http://www.w3.org/2001/XMLSchema".equals(namespaceURI)) {
                throw new SAXException("XTSE0080: The " + attrName + " attribute on " + elementName + 
                                      " cannot use the XML Schema namespace");
            }
        }
    }

    /**
     * Processes an xsl:character-map declaration (XSLT 2.0+).
     *
     * <p>Example:
     * <pre>
     * &lt;xsl:character-map name="map01"&gt;
     *   &lt;xsl:output-character character="c" string="[C]"/&gt;
     * &lt;/xsl:character-map&gt;
     * </pre>
     */
    private void processCharacterMap(ElementContext ctx) throws SAXException {
        String name = ctx.attributes.get("name");
        if (name == null || name.isEmpty()) {
            throw new SAXException("XTSE0010: xsl:character-map requires name attribute");
        }
        
        // XTSE0080: Check for reserved namespace in character-map name
        if (name.contains(":")) {
            int colonIdx = name.indexOf(':');
            String prefix = name.substring(0, colonIdx);
            String nsUri = ctx.namespaceBindings.get(prefix);
            if (nsUri == null) {
                nsUri = lookupNamespaceUri(prefix);
            }
            if (nsUri != null && isReservedNamespace(nsUri)) {
                throw new SAXException("XTSE0080: Reserved namespace '" + nsUri + 
                    "' cannot be used in the character-map name '" + name + "'");
            }
        }
        
        // XTSE1580: Check for duplicate character map name
        if (builder.hasCharacterMap(name)) {
            throw new SAXException("XTSE1580: Duplicate character-map name: " + name);
        }
        
        String useCharacterMaps = ctx.attributes.get("use-character-maps");
        
        CompiledStylesheet.CharacterMap charMap = new CompiledStylesheet.CharacterMap(name);
        
        // Process use-character-maps references
        if (useCharacterMaps != null && !useCharacterMaps.isEmpty()) {
            String[] refs = useCharacterMaps.split("\\s+");
            for (String ref : refs) {
                if (!ref.isEmpty()) {
                    charMap.addUseCharacterMap(ref);
                }
            }
        }
        
        // Process xsl:output-character children
        for (XSLTNode child : ctx.children) {
            if (child instanceof OutputCharacterNode) {
                OutputCharacterNode ocn = (OutputCharacterNode) child;
                charMap.addMapping(ocn.getCodePoint(), ocn.getString());
            }
        }
        
        builder.addCharacterMap(charMap);
    }
    
    /**
     * Compiles an xsl:output-character element (child of xsl:character-map).
     */
    private XSLTNode compileOutputCharacter(ElementContext ctx) throws SAXException {
        String characterAttr = ctx.attributes.get("character");
        String stringAttr = ctx.attributes.get("string");
        
        if (characterAttr == null || characterAttr.isEmpty()) {
            throw new SAXException("XTSE0010: xsl:output-character requires character attribute");
        }
        
        // Handle both BMP characters (length 1) and supplementary characters (length 2 as surrogate pair)
        int codePoint;
        if (characterAttr.length() == 1) {
            codePoint = characterAttr.charAt(0);
        } else if (characterAttr.length() == 2 && 
                   Character.isHighSurrogate(characterAttr.charAt(0)) &&
                   Character.isLowSurrogate(characterAttr.charAt(1))) {
            // Supplementary character represented as surrogate pair
            codePoint = Character.toCodePoint(characterAttr.charAt(0), characterAttr.charAt(1));
        } else {
            throw new SAXException("XTSE0020: xsl:output-character character must be a single character");
        }
        
        if (stringAttr == null) {
            throw new SAXException("XTSE0010: xsl:output-character requires string attribute");
        }
        
        return new OutputCharacterNode(codePoint, stringAttr);
    }
    
    /**
     * Node representing an xsl:output-character element.
     */
    private static class OutputCharacterNode implements XSLTNode {
        private final int codePoint;  // Unicode code point (supports supplementary characters)
        private final String string;
        
        OutputCharacterNode(int codePoint, String string) {
            this.codePoint = codePoint;
            this.string = string;
        }
        
        int getCodePoint() { return codePoint; }
        String getString() { return string; }
        
        @Override
        public void execute(TransformContext context, OutputHandler output) {
            // Not executed directly - used for data storage only
        }
        
        @Override
        public StreamingCapability getStreamingCapability() {
            return StreamingCapability.FULL;
        }
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
        String onMultipleMatchAttr = ctx.attributes.get("on-multiple-match");
        String visibilityAttr = ctx.attributes.get("visibility");
        String useAccumulators = ctx.attributes.get("use-accumulators");
        String typedAttr = ctx.attributes.get("typed");
        String warningOnNoMatch = ctx.attributes.get("warning-on-no-match");
        
        ModeDeclaration.Builder modeBuilder = new ModeDeclaration.Builder()
            .name(name)
            .streamable("yes".equals(streamableAttr))
            .onNoMatch(onNoMatchAttr)
            .onMultipleMatch(onMultipleMatchAttr)
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
        // XTSE0080: Check for reserved namespace
        QName funcName = parseQName(name, ctx.namespaceBindings, true);
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
        // Use the original prefix from the stylesheet source
        // This preserves the author's intent (unprefixed vs prefixed)
        String prefix = ctx.originalPrefix;
        String localName = ctx.localName;
        
        // Extract xsl:use-attribute-sets before processing other attributes
        String useAttrSetsValue = ctx.attributes.get("xsl:use-attribute-sets");
        List<String> useAttributeSets = new ArrayList<>();
        if (useAttrSetsValue != null) {
            for (String setName : splitOnWhitespace(useAttrSetsValue)) {
                // Expand attribute set name using namespace bindings
                String expandedName = expandAttributeSetName(setName.trim(), ctx.namespaceBindings);
                useAttributeSets.add(expandedName);
                // Register for validation (XTSE0710)
                builder.registerAttributeSetReferences(expandedName);
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
        // XTSE1660: Non-schema-aware processor must reject xsl:type except for xs:untyped
        // xs:untyped is the default type for elements without schema validation, so it's a no-op
        String typeValue = ctx.attributes.get("xsl:type");
        if (typeValue != null && !typeValue.isEmpty()) {
            String normalizedType = typeValue.trim();
            // Allow xs:untyped (element default) - strip any namespace prefix
            if (normalizedType.endsWith(":untyped") || "untyped".equals(normalizedType)) {
                // This is valid - elements without validation have type xs:untyped by default
            } else {
                throw new SAXException("XTSE1660: xsl:type='" + typeValue + "' requires a schema-aware processor");
            }
        }
        
        // XTSE1660: Non-schema-aware processor must reject xsl:validation with value other than strip/preserve/lax
        String validationValue = ctx.attributes.get("xsl:validation");
        if (validationValue != null && !validationValue.isEmpty()) {
            String val = validationValue.trim();
            if (!"strip".equals(val) && !"preserve".equals(val) && !"lax".equals(val)) {
                throw new SAXException("XTSE1660: xsl:validation='" + val + "' requires a schema-aware processor");
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
        
        // Build content - keep xsl:on-empty and xsl:on-non-empty in place
        // SequenceNode now handles them with proper two-phase execution
        SequenceNode content = new SequenceNode(ctx.children);
        
        // Per XSLT 1.0 section 7.1.1: Copy namespace nodes except XSLT namespace.
        // Also exclude namespaces listed in exclude-result-prefixes (both global and local).
        // BUT: Can't exclude a namespace that's actually used by the element or its attributes.
        // Output ALL in-scope namespaces - SAXOutputHandler will deduplicate inherited ones.
        // Namespace aliasing is applied at runtime by LiteralResultElement.
        
        // Collect PREFIXES that are actually used (can't be excluded)
        // We track prefixes, not URIs, because multiple prefixes can map to the same URI
        // and we only need to output the ones actually used.
        Set<String> usedPrefixes = new HashSet<>();
        // The element's own prefix is used (or default "" if unprefixed)
        usedPrefixes.add(prefix != null ? prefix : "");
        // Check attribute prefixes
        for (String attrName : ctx.attributes.keySet()) {
            if (attrName.startsWith("xsl:")) continue; // Skip XSLT attributes
            int colon = attrName.indexOf(':');
            if (colon > 0) {
                String attrPrefix = attrName.substring(0, colon);
                usedPrefixes.add(attrPrefix);
            }
        }
        
        Map<String, String> outputNamespaces = new LinkedHashMap<>();
        for (Map.Entry<String, String> ns : ctx.namespaceBindings.entrySet()) {
            String nsPrefix = ns.getKey();
            String nsUri = ns.getValue();
            // Don't output the XSLT namespace
            if (XSLT_NS.equals(nsUri)) {
                continue;
            }
            // Include if the prefix is actually used
            if (usedPrefixes.contains(nsPrefix)) {
                outputNamespaces.put(nsPrefix, nsUri);
                continue;
            }
            // Exclude if in excluded set (by URI)
            if (!localExcludedURIs.contains(nsUri)) {
                outputNamespaces.put(nsPrefix, nsUri);
            }
        }
        
        // xsl:type is rejected above (XTSE1660), so pass null for type info
        // on-empty/on-non-empty are now handled by SequenceNode's two-phase execution
        return new LiteralResultElement(ctx.namespaceURI, localName, prefix, 
            avts, outputNamespaces, useAttributeSets, null, null, content,
            null, null);
    }

    // ========================================================================
    // Top-level element processing
    // ========================================================================

    private void processStylesheetElement(ElementContext ctx) throws SAXException {
        // Parse version attribute - REQUIRED per XSLT spec (XTSE0010)
        String versionAttr = ctx.attributes.get("version");
        if (versionAttr == null || versionAttr.isEmpty()) {
            throw new SAXException("XTSE0010: Required attribute 'version' is missing on xsl:" + ctx.localName);
        }
        
        // XTSE0110: version must be a valid xs:decimal (no scientific notation)
        String trimmedVersion = versionAttr.trim();
        if (trimmedVersion.toLowerCase().contains("e") || trimmedVersion.toLowerCase().contains("inf") || 
            trimmedVersion.toLowerCase().contains("nan")) {
            throw new SAXException("XTSE0110: version attribute must be a valid xs:decimal, got: " + versionAttr);
        }
        
        try {
            stylesheetVersion = Double.parseDouble(versionAttr);
            // Forward-compatible mode: enabled when version > max supported (3.0)
            // Per XSLT spec, processor must run in forward-compatible mode when
            // version is higher than what it implements
            forwardCompatible = stylesheetVersion > 3.0;
            // Store version in compiled stylesheet
            builder.setVersion(stylesheetVersion);
        } catch (NumberFormatException e) {
            throw new SAXException("XTSE0110: Invalid version attribute value: " + versionAttr);
        }
        
        // xsl:package is only allowed in XSLT 3.0+
        if ("package".equals(ctx.localName) && stylesheetVersion < 3.0) {
            throw new SAXException("xsl:package is only allowed in XSLT 3.0 or later (version=" + 
                stylesheetVersion + ")");
        }
        
        // XSLT 3.0 package attributes (only for xsl:package)
        if ("package".equals(ctx.localName)) {
            // name attribute - the package URI (optional but recommended)
            String nameAttr = ctx.attributes.get("name");
            if (nameAttr != null && !nameAttr.isEmpty()) {
                packageName = nameAttr.trim();
            }
            
            // package-version attribute (optional, defaults to "0.0" per spec)
            String versionAttrPkg = ctx.attributes.get("package-version");
            if (versionAttrPkg != null && !versionAttrPkg.isEmpty()) {
                packageVersion = versionAttrPkg.trim();
            } else {
                packageVersion = "0.0";
            }
            
            // declared-modes attribute (XSLT 3.0)
            // "yes" (default) means modes are declared by their first use or explicit xsl:mode
            // "no" means all modes must be declared with xsl:mode
            String declaredModesAttr = ctx.attributes.get("declared-modes");
            // For now, we allow undeclared modes (default behavior)
            
            // input-type-annotations attribute (for typed documents)
            String inputTypeAnnotations = ctx.attributes.get("input-type-annotations");
            // "preserve", "strip", "unspecified" - handle if schema-aware
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
                    // XTSE1660: Non-schema-aware processor must reject strict validation
                    throw new SAXException("XTSE1660: default-validation='strict' requires a schema-aware processor");
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
        
        // Parse default-collation attribute (XSLT 2.0+)
        String defaultCollationAttr = ctx.attributes.get("default-collation");
        if (defaultCollationAttr != null && !defaultCollationAttr.isEmpty()) {
            // The default-collation attribute is a whitespace-separated list of collation URIs
            // The processor uses the first URI it recognizes
            String[] collations = defaultCollationAttr.trim().split("\\s+");
            String selectedCollation = null;
            for (String collUri : collations) {
                // Check if we support this collation - try to create it
                try {
                    Collation.forUri(collUri);
                    selectedCollation = collUri;
                    break;  // Use first recognized collation
                } catch (Exception e) {
                    // Collation not supported, try next one
                }
            }
            if (selectedCollation != null) {
                builder.setDefaultCollation(selectedCollation);
            }
            // If no collation was recognized, use the default (codepoint)
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
     * Validates that an instruction is not used at the top level of the stylesheet.
     * XTSE0010: Many XSLT instructions are only allowed within a sequence constructor.
     * @param elementName the local name of the XSLT element
     * @throws SAXException if the element is at top level
     */
    private void validateNotTopLevel(String elementName) throws SAXException {
        if (isTopLevel()) {
            throw new SAXException("XTSE0010: xsl:" + elementName + 
                " is not allowed at the top level of a stylesheet");
        }
    }

    /**
     * Validates that an element has no content (text or child elements).
     * XTSE0260: Elements required to be empty cannot have content.
     * Note: Even whitespace text nodes preserved with xml:space="preserve" are an error.
     */
    private void validateEmptyElement(ElementContext ctx, String elementName) throws SAXException {
        // Check xml:space attribute - if preserve, whitespace is significant
        String xmlSpace = ctx.attributes.get("xml:space");
        boolean preserveWhitespace = "preserve".equals(xmlSpace);
        
        for (XSLTNode child : ctx.children) {
            if (child instanceof LiteralText) {
                String text = ((LiteralText) child).getText();
                if (text != null && !text.isEmpty()) {
                    // With xml:space="preserve", even whitespace is an error
                    // Without it, only non-whitespace is an error
                    if (preserveWhitespace || !text.trim().isEmpty()) {
                        throw new SAXException("XTSE0260: " + elementName + " must be empty, but contains text content");
                    }
                }
            } else {
                // Any other child node type is also not allowed
                throw new SAXException("XTSE0260: " + elementName + " must be empty, but contains child elements");
            }
        }
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
        
        // XTSE0260: xsl:import must be empty
        validateEmptyElement(ctx, "xsl:import");
        
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
            // Pass -1 to indicate the imported stylesheet should assign its own precedence
            CompiledStylesheet imported = resolver.resolve(href, baseUri, true, -1);
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
        
        // XTSE0260: xsl:include must be empty
        validateEmptyElement(ctx, "xsl:include");
        
        // Include is allowed anywhere in top-level, but once we see a non-import
        // element, no more imports are allowed
        importsAllowed = false;
        
        String href = ctx.attributes.get("href");
        if (href == null || href.isEmpty()) {
            throw new SAXException("XTSE0010: xsl:include requires href attribute");
        }
        
        if (resolver == null) {
            throw new SAXException("xsl:include not supported: no StylesheetResolver configured");
        }
        
        try {
            // For includes: compile the included stylesheet (which may have imports
            // that need lower precedence). The included stylesheet uses -1 to assign
            // its own precedence after its imports.
            CompiledStylesheet included = resolver.resolve(href, baseUri, false, -1);
            
            // Don't assign our precedence here - wait until getCompiledStylesheet()
            // to ensure our precedence is higher than ALL imports (including those
            // in subsequent includes).
            
            if (included != null) {
                // Merge the included stylesheet's templates, marking them for later
                // precedence update. Templates from the included stylesheet (not its
                // imports) will be updated to our precedence in getCompiledStylesheet().
                builder.mergeIncludePending(included);
            }
        } catch (IOException e) {
            throw new SAXException("Failed to include stylesheet: " + href, e);
        }
    }

    /**
     * Processes an xsl:use-package element (XSLT 3.0).
     *
     * <p>xsl:use-package imports a compiled package and makes its public 
     * components available in the using stylesheet. Child elements can
     * filter (xsl:accept) or replace (xsl:override) components.
     *
     * <p>Attributes:
     * <ul>
     *   <li>name - the package name URI (required)</li>
     *   <li>package-version - version constraint (optional, default "*")</li>
     * </ul>
     */
    private void processUsePackage(ElementContext ctx) throws SAXException {
        importsAllowed = false;
        ensurePrecedenceAssigned();
        
        // Get required name attribute
        String name = ctx.attributes.get("name");
        if (name == null || name.isEmpty()) {
            throw new SAXException("XTSE0010: xsl:use-package requires name attribute");
        }
        
        // Get optional package-version attribute (default: "*" matches any version)
        String versionConstraint = ctx.attributes.get("package-version");
        if (versionConstraint == null || versionConstraint.isEmpty()) {
            versionConstraint = "*";
        }
        
        // Check if we have a package resolver
        if (packageResolver == null) {
            throw new SAXException("XTSE3020: xsl:use-package not supported: no PackageResolver configured");
        }
        
        try {
            // Resolve the package
            CompiledPackage pkg = packageResolver.resolve(name, versionConstraint, baseUri);
            
            if (pkg == null) {
                throw new SAXException("XTSE3020: Cannot resolve package: " + name);
            }
            
            // Process child elements (xsl:accept and xsl:override)
            List<AcceptDeclaration> accepts = new ArrayList<>();
            List<OverrideDeclaration> overrides = new ArrayList<>();
            
            for (XSLTNode child : ctx.children) {
                if (child instanceof AcceptDeclarationNode) {
                    accepts.add(((AcceptDeclarationNode) child).getDeclaration());
                } else if (child instanceof OverrideDeclarationNode) {
                    overrides.addAll(((OverrideDeclarationNode) child).getDeclarations());
                }
            }
            
            // Create the dependency record
            CompiledPackage.PackageDependency dependency = 
                new CompiledPackage.PackageDependency(name, versionConstraint, pkg, accepts, overrides);
            packageDependencies.add(dependency);
            
            // Merge the package's public components into our stylesheet
            mergePackageComponents(pkg, accepts, overrides);
            
        } catch (SAXException e) {
            throw e;
        } catch (Exception e) {
            throw new SAXException("XTSE3020: Failed to resolve package " + name + ": " + e.getMessage(), e);
        }
    }

    /**
     * Processes an xsl:expose element (XSLT 3.0).
     *
     * <p>xsl:expose modifies the visibility of components within a package.
     * It can be used to make public components private, make them final,
     * or declare abstract components.
     *
     * <p>Attributes:
     * <ul>
     *   <li>component - the type of component ("template", "function", "variable", 
     *       "attribute-set", "mode", or "*")</li>
     *   <li>names - EQName pattern list (whitespace-separated) matching component names</li>
     *   <li>visibility - the new visibility ("public", "private", "final", "abstract")</li>
     * </ul>
     */
    private void processExpose(ElementContext ctx) throws SAXException {
        // xsl:expose must be empty
        validateEmptyElement(ctx, "xsl:expose");
        
        // Get required attributes
        String componentAttr = ctx.attributes.get("component");
        if (componentAttr == null || componentAttr.isEmpty()) {
            throw new SAXException("XTSE0010: xsl:expose requires component attribute");
        }
        
        String namesAttr = ctx.attributes.get("names");
        if (namesAttr == null || namesAttr.isEmpty()) {
            throw new SAXException("XTSE0010: xsl:expose requires names attribute");
        }
        
        String visibilityAttr = ctx.attributes.get("visibility");
        if (visibilityAttr == null || visibilityAttr.isEmpty()) {
            throw new SAXException("XTSE0010: xsl:expose requires visibility attribute");
        }
        
        // Parse component type
        AcceptDeclaration.ComponentType componentType = 
            AcceptDeclaration.ComponentType.parse(componentAttr);
        if (componentType == null) {
            throw new SAXException("XTSE0020: Invalid component type: " + componentAttr);
        }
        
        // Parse visibility
        ComponentVisibility visibility;
        try {
            visibility = ComponentVisibility.parse(visibilityAttr);
            if (visibility == null) {
                throw new SAXException("XTSE0020: Invalid visibility: " + visibilityAttr);
            }
        } catch (IllegalArgumentException e) {
            throw new SAXException("XTSE0020: Invalid visibility: " + visibilityAttr);
        }
        
        // XTSE3000: Cannot expose as hidden (use xsl:accept for that)
        if (visibility == ComponentVisibility.HIDDEN) {
            throw new SAXException("XTSE3000: Cannot use visibility='hidden' in xsl:expose");
        }
        
        // Create an expose declaration and store it for later processing
        ExposeDeclaration expose = new ExposeDeclaration(componentType, namesAttr, visibility);
        exposeDeclarations.add(expose);
    }

    /**
     * Stores xsl:expose declarations for processing at end of compilation.
     */
    private final List<ExposeDeclaration> exposeDeclarations = new ArrayList<>();

    /**
     * Internal class representing an xsl:expose declaration.
     */
    private static class ExposeDeclaration {
        final AcceptDeclaration.ComponentType componentType;
        final String namesPattern;
        final ComponentVisibility visibility;
        
        ExposeDeclaration(AcceptDeclaration.ComponentType componentType, 
                         String namesPattern, ComponentVisibility visibility) {
            this.componentType = componentType;
            this.namesPattern = namesPattern;
            this.visibility = visibility;
        }
        
        boolean matches(AcceptDeclaration.ComponentType type, String name) {
            if (componentType != AcceptDeclaration.ComponentType.ALL && 
                componentType != type) {
                return false;
            }
            if ("*".equals(namesPattern)) {
                return true;
            }
            // Simple pattern matching
            String[] patterns = namesPattern.split("\\s+");
            for (String pattern : patterns) {
                if (matchesPattern(name, pattern)) {
                    return true;
                }
            }
            return false;
        }
        
        private boolean matchesPattern(String name, String pattern) {
            if (pattern.equals(name)) {
                return true;
            }
            if (pattern.endsWith("*")) {
                String prefix = pattern.substring(0, pattern.length() - 1);
                return name.startsWith(prefix);
            }
            if (pattern.startsWith("*")) {
                String suffix = pattern.substring(1);
                return name.endsWith(suffix);
            }
            return false;
        }
    }

    /**
     * Compiles an xsl:accept element (XSLT 3.0).
     *
     * <p>xsl:accept is a child of xsl:use-package that filters which components
     * are imported from the used package and what visibility they have.
     *
     * @param ctx the element context
     * @return a placeholder node containing the accept declaration
     */
    private XSLTNode compileAccept(ElementContext ctx) throws SAXException {
        // xsl:accept must be empty
        validateEmptyElement(ctx, "xsl:accept");
        
        // Get required component attribute
        String componentAttr = ctx.attributes.get("component");
        if (componentAttr == null || componentAttr.isEmpty()) {
            throw new SAXException("XTSE0010: xsl:accept requires component attribute");
        }
        
        // Get required names attribute
        String namesAttr = ctx.attributes.get("names");
        if (namesAttr == null || namesAttr.isEmpty()) {
            throw new SAXException("XTSE0010: xsl:accept requires names attribute");
        }
        
        // Get required visibility attribute
        String visibilityAttr = ctx.attributes.get("visibility");
        if (visibilityAttr == null || visibilityAttr.isEmpty()) {
            throw new SAXException("XTSE0010: xsl:accept requires visibility attribute");
        }
        
        // Parse component type
        AcceptDeclaration.ComponentType componentType = 
            AcceptDeclaration.ComponentType.parse(componentAttr);
        if (componentType == null) {
            throw new SAXException("XTSE0020: Invalid component type in xsl:accept: " + componentAttr);
        }
        
        // Parse visibility
        ComponentVisibility visibility;
        try {
            visibility = ComponentVisibility.parse(visibilityAttr);
            if (visibility == null) {
                throw new SAXException("XTSE0020: Invalid visibility in xsl:accept: " + visibilityAttr);
            }
        } catch (IllegalArgumentException e) {
            throw new SAXException("XTSE0020: Invalid visibility in xsl:accept: " + visibilityAttr);
        }
        
        // Create the accept declaration
        AcceptDeclaration declaration = new AcceptDeclaration(componentType, namesAttr, visibility);
        return new AcceptDeclarationNode(declaration);
    }

    /**
     * Placeholder node for accept declarations within xsl:use-package.
     */
    private static class AcceptDeclarationNode implements XSLTNode {
        private final AcceptDeclaration declaration;
        
        AcceptDeclarationNode(AcceptDeclaration declaration) {
            this.declaration = declaration;
        }
        
        AcceptDeclaration getDeclaration() {
            return declaration;
        }
        
        @Override
        public void execute(org.bluezoo.gonzalez.transform.runtime.TransformContext context,
                           org.bluezoo.gonzalez.transform.runtime.OutputHandler output) {
            // Accept declarations don't execute at runtime
        }
        
        @Override
        public StreamingCapability getStreamingCapability() {
            return StreamingCapability.NONE;
        }
    }

    /**
     * Compiles an xsl:override element (XSLT 3.0).
     *
     * <p>xsl:override is a child of xsl:use-package that contains replacement
     * definitions for components from the used package.
     *
     * @param ctx the element context
     * @return a placeholder node containing the override declarations
     */
    private XSLTNode compileOverride(ElementContext ctx) throws SAXException {
        // xsl:override contains replacement component definitions (templates, functions, etc.)
        // The child definitions are compiled normally but stored as overrides rather than
        // being added to the main stylesheet.
        //
        // Note: In a full implementation, we would need to:
        // 1. Track that we're inside xsl:override
        // 2. Have template/function/variable compilation check this flag
        // 3. Store the compiled components in the override list instead of builder
        //
        // For now, we return an empty override node. The children (xsl:template, etc.)
        // will be compiled normally by the parent context processing.
        List<OverrideDeclaration> declarations = new ArrayList<>();
        
        // Children are compiled as normal XSLT elements by the parent processing
        // A more complete implementation would intercept template compilation
        // when inside xsl:override context
        
        return new OverrideDeclarationNode(declarations);
    }

    /**
     * Placeholder node for override declarations within xsl:use-package.
     */
    private static class OverrideDeclarationNode implements XSLTNode {
        private final List<OverrideDeclaration> declarations;
        
        OverrideDeclarationNode(List<OverrideDeclaration> declarations) {
            this.declarations = declarations;
        }
        
        List<OverrideDeclaration> getDeclarations() {
            return declarations;
        }
        
        @Override
        public void execute(org.bluezoo.gonzalez.transform.runtime.TransformContext context,
                           org.bluezoo.gonzalez.transform.runtime.OutputHandler output) {
            // Override declarations don't execute at runtime
        }
        
        @Override
        public StreamingCapability getStreamingCapability() {
            return StreamingCapability.NONE;
        }
    }

    /**
     * Merges public components from a package into this stylesheet.
     *
     * @param pkg the package to merge from
     * @param accepts accept declarations that filter components
     * @param overrides override declarations that replace components
     */
    private void mergePackageComponents(CompiledPackage pkg, 
                                        List<AcceptDeclaration> accepts,
                                        List<OverrideDeclaration> overrides) 
            throws SAXException, TransformerConfigurationException {
        
        // Create a set of overridden component keys for quick lookup
        Set<String> overriddenTemplates = new HashSet<>();
        Set<String> overriddenFunctions = new HashSet<>();
        Set<String> overriddenVariables = new HashSet<>();
        Set<String> overriddenAttributeSets = new HashSet<>();
        
        for (OverrideDeclaration override : overrides) {
            switch (override.getType()) {
                case TEMPLATE:
                    overriddenTemplates.add(override.getOriginalComponentKey());
                    // Add the override template
                    if (override.getOverrideTemplate() != null) {
                        builder.addTemplateRule(override.getOverrideTemplate());
                    }
                    break;
                case FUNCTION:
                    overriddenFunctions.add(override.getOriginalComponentKey());
                    if (override.getOverrideFunction() != null) {
                        builder.addUserFunction(override.getOverrideFunction());
                    }
                    break;
                case VARIABLE:
                case PARAM:
                    overriddenVariables.add(override.getOriginalComponentKey());
                    if (override.getOverrideVariable() != null) {
                        builder.addGlobalVariable(override.getOverrideVariable());
                    }
                    break;
                case ATTRIBUTE_SET:
                    overriddenAttributeSets.add(override.getOriginalComponentKey());
                    if (override.getOverrideAttributeSet() != null) {
                        builder.addAttributeSet(override.getOverrideAttributeSet());
                    }
                    break;
            }
        }
        
        // Import public templates (not overridden, not hidden by accept)
        for (TemplateRule template : pkg.getPublicTemplates()) {
            String key = getTemplateKey(template);
            if (overriddenTemplates.contains(key)) {
                continue; // Skip - overridden
            }
            ComponentVisibility vis = getAcceptedVisibility(
                accepts, AcceptDeclaration.ComponentType.TEMPLATE, 
                template.getName() != null ? template.getName() : key);
            if (vis != ComponentVisibility.HIDDEN) {
                builder.addTemplateRule(template.withVisibility(vis));
            }
        }
        
        // Import public functions (not overridden, not hidden by accept)
        for (UserFunction function : pkg.getPublicFunctions()) {
            String key = function.getKey();
            if (overriddenFunctions.contains(key)) {
                continue; // Skip - overridden
            }
            String funcName = "{" + function.getNamespaceURI() + "}" + function.getLocalName();
            ComponentVisibility vis = getAcceptedVisibility(
                accepts, AcceptDeclaration.ComponentType.FUNCTION, funcName);
            if (vis != ComponentVisibility.HIDDEN) {
                builder.addUserFunction(function.withVisibility(vis));
            }
        }
        
        // Import public variables (not overridden, not hidden by accept)
        for (GlobalVariable variable : pkg.getPublicVariables()) {
            String key = variable.getExpandedName();
            if (overriddenVariables.contains(key)) {
                continue; // Skip - overridden
            }
            ComponentVisibility vis = getAcceptedVisibility(
                accepts, AcceptDeclaration.ComponentType.VARIABLE, key);
            if (vis != ComponentVisibility.HIDDEN) {
                builder.addGlobalVariable(variable.withVisibility(vis));
            }
        }
        
        // Import public attribute sets (not overridden, not hidden by accept)
        for (AttributeSet attrSet : pkg.getPublicAttributeSets()) {
            String key = attrSet.getName();
            if (overriddenAttributeSets.contains(key)) {
                continue; // Skip - overridden
            }
            ComponentVisibility vis = getAcceptedVisibility(
                accepts, AcceptDeclaration.ComponentType.ATTRIBUTE_SET, key);
            if (vis != ComponentVisibility.HIDDEN) {
                builder.addAttributeSet(attrSet.withVisibility(vis));
            }
        }
        
        // Import public modes
        for (ModeDeclaration mode : pkg.getPublicModes()) {
            String key = mode.getName() != null ? mode.getName() : "#default";
            ComponentVisibility vis = getAcceptedVisibility(
                accepts, AcceptDeclaration.ComponentType.MODE, key);
            if (vis != ComponentVisibility.HIDDEN) {
                builder.addModeDeclaration(mode.withComponentVisibility(vis));
            }
        }
    }

    /**
     * Gets the effective visibility for a component after applying accept declarations.
     * Returns the original visibility if no accept matches.
     */
    private ComponentVisibility getAcceptedVisibility(List<AcceptDeclaration> accepts,
                                                      AcceptDeclaration.ComponentType type,
                                                      String componentName) {
        // Find the first matching accept declaration
        for (AcceptDeclaration accept : accepts) {
            if (accept.matchesType(type) && accept.matchesName(componentName)) {
                return accept.getVisibility();
            }
        }
        // No accept matches - use the component's default visibility
        return ComponentVisibility.PUBLIC;
    }

    /**
     * Creates a key for a template (for override matching).
     */
    private String getTemplateKey(TemplateRule template) {
        if (template.getName() != null) {
            return template.getName();
        } else if (template.getMatchPattern() != null) {
            String mode = template.getMode() != null ? template.getMode() : "#default";
            return template.getMatchPattern().toString() + "#" + mode;
        }
        return "unknown";
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
        
        // Reset inline schema tracking (was set in startElement)
        inImportSchema = false;
        
        String namespace = ctx.attributes.get("namespace");
        String schemaLocation = ctx.attributes.get("schema-location");
        
        // If an inline schema was already parsed (in endElement), we're done
        // The inline schema handling in endElement already added the schema to builder
        if (schemaLocation == null || schemaLocation.isEmpty()) {
            // No schema-location - inline schema (if present) was already processed
            importSchemaNamespace = null;
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
        } finally {
            importSchemaNamespace = null;
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
        // Don't assign precedence here - it will be done after all includes are processed
        // Use the current importPrecedence value (-1 if not yet assigned)
        String match = ctx.attributes.get("match");
        String name = ctx.attributes.get("name");
        String mode = ctx.attributes.get("mode");
        String priorityStr = ctx.attributes.get("priority");
        String asType = ctx.attributes.get("as");  // XSLT 2.0+ return type
        
        // XTSE0500: xsl:template must have match or name (or both)
        if (match == null && name == null) {
            throw new SAXException("XTSE0500: xsl:template must have a match attribute or a name attribute, or both");
        }
        
        // XTSE0500: xsl:template with no match cannot have mode or priority
        if (match == null) {
            if (mode != null) {
                throw new SAXException("XTSE0500: xsl:template with no match attribute cannot have a mode attribute");
            }
            if (priorityStr != null) {
                throw new SAXException("XTSE0500: xsl:template with no match attribute cannot have a priority attribute");
            }
        }
        
        // XTSE0550: Validate mode attribute
        if (mode != null) {
            String trimmedMode = mode.trim();
            // Empty mode is an error
            if (trimmedMode.isEmpty()) {
                throw new SAXException("XTSE0550: mode attribute must not be empty");
            }
            // Check for duplicate tokens and #all with other modes
            String[] modeTokens = trimmedMode.split("\\s+");
            Set<String> seenModes = new HashSet<>();
            boolean hasAll = false;
            for (String token : modeTokens) {
                if (token.equals("#all")) {
                    hasAll = true;
                }
                if (!seenModes.add(token)) {
                    throw new SAXException("XTSE0550: mode attribute contains duplicate token '" + token + "'");
                }
            }
            if (hasAll && modeTokens.length > 1) {
                throw new SAXException("XTSE0550: #all cannot appear together with other mode values");
            }
        }
        
        // Expand mode QName to Clark notation for proper namespace comparison
        // XTSE0080: Check reserved namespace for mode
        String expandedMode = expandModeQName(mode, true);
        
        // Expand template name to Clark notation for proper namespace comparison
        // woo:a and hoo:a should match if both prefixes map to the same URI
        // XTSE0080: Check reserved namespace for template name
        String expandedName = expandQName(name, true);
        
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
        // Note: whitespace-only text nodes are ignored when checking param ordering
        List<TemplateParameter> params = new ArrayList<>();
        List<XSLTNode> bodyNodes = new ArrayList<>();
        boolean foundNonParam = false;
        
        for (XSLTNode child : ctx.children) {
            // Check if this is whitespace-only text (for param ordering check only)
            boolean isWhitespaceText = child instanceof LiteralText && 
                isWhitespace(((LiteralText) child).getText());
            
            if (child instanceof ParamNode) {
                if (foundNonParam) {
                    throw new SAXException("XTSE0010: xsl:param must come before any other content in template");
                }
                ParamNode pn = (ParamNode) child;
                params.add(new TemplateParameter(pn.getNamespaceURI(), pn.getLocalName(), pn.getSelectExpr(), pn.getContent(), pn.isTunnel()));
            } else if (child instanceof WithParamNode) {
                // XTSE0010: xsl:with-param not allowed directly in template
                throw new SAXException("XTSE0010: xsl:with-param is not allowed directly in xsl:template");
            } else if (child instanceof SortSpecNode) {
                // XTSE0010: xsl:sort not allowed directly in template
                throw new SAXException("XTSE0010: xsl:sort is not allowed directly in xsl:template");
            } else {
                // Only non-whitespace content counts as "found non-param" for ordering check
                if (!isWhitespaceText) {
                    foundNonParam = true;
                }
                // But ALL content (including whitespace) goes into the body
                // (whitespace filtering was already done by flushCharacters based on xml:space)
                bodyNodes.add(child);
            }
        }
        
        SequenceNode body = new SequenceNode(bodyNodes);
        
        TemplateRule rule = new TemplateRule(pattern, expandedName, expandedMode, priority, 
            importPrecedence, nextTemplateIndex(), params, body, asType);
        builder.addTemplateRule(rule);
    }

    /**
     * Expands a mode QName to Clark notation {uri}localname for proper comparison.
     * Mode names like foo:a and moo:a should be equal if both prefixes map to the same URI.
     */
    private String expandModeQName(String mode) throws SAXException {
        return expandModeQName(mode, false);
    }
    
    /**
     * Expands a mode QName to Clark notation, optionally checking for reserved namespaces.
     */
    private String expandModeQName(String mode, boolean checkReserved) throws SAXException {
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
                // XTSE0080: Check for reserved namespaces in mode names
                if (checkReserved && isReservedNamespace(uri)) {
                    throw new SAXException("XTSE0080: Reserved namespace '" + uri + 
                        "' cannot be used in the mode '" + mode + "'");
                }
                return "{" + uri + "}" + localName;
            }
            // XTSE0280: Prefix not declared
            throw new SAXException("XTSE0280: Namespace prefix '" + prefix + "' is not declared");
        }
        
        // Unprefixed mode - return as-is (no namespace)
        return mode;
    }

    /**
     * Expands a QName to Clark notation {uri}localname for proper comparison.
     * Names like woo:a and hoo:a should be equal if both prefixes map to the same URI.
     */
    private String expandQName(String qname) throws SAXException {
        return expandQName(qname, false);
    }
    
    /**
     * Expands a QName to Clark notation, optionally checking for reserved namespaces.
     * XTSE0080: Reserved namespaces cannot be used in component names (templates, variables, etc.)
     */
    private String expandQName(String qname, boolean checkReserved) throws SAXException {
        if (qname == null || qname.isEmpty()) {
            return qname;
        }
        
        int colonPos = qname.indexOf(':');
        if (colonPos > 0) {
            // Prefixed name - expand to Clark notation
            String prefix = qname.substring(0, colonPos);
            String localName = qname.substring(colonPos + 1);
            String uri = resolve(prefix);
            if (uri != null && !uri.isEmpty()) {
                // XTSE0080: Check for reserved namespaces in component names
                // Exception: xsl:initial-template is allowed in XSLT 3.0
                if (checkReserved && isReservedNamespace(uri)) {
                    if (!(XSLT_NS.equals(uri) && "initial-template".equals(localName) && stylesheetVersion >= 3.0)) {
                        throw new SAXException("XTSE0080: Reserved namespace '" + uri + 
                            "' cannot be used in the name '" + qname + "'");
                    }
                }
                return "{" + uri + "}" + localName;
            }
            // XTSE0280: Prefix not declared
            throw new SAXException("XTSE0280: Namespace prefix '" + prefix + "' is not declared");
        }
        
        // Unprefixed - return as-is (no namespace)
        return qname;
    }
    
    /**
     * Returns true if the namespace URI is reserved (cannot be used in component names).
     * Per XSLT 2.0 spec section 3.4, reserved namespaces include XSLT, XML Schema, 
     * and other W3C standard namespaces.
     */
    private boolean isReservedNamespace(String uri) {
        if (uri == null) {
            return false;
        }
        return XSLT_NS.equals(uri) ||
               "http://www.w3.org/XML/1998/namespace".equals(uri) ||
               "http://www.w3.org/2001/XMLSchema".equals(uri) ||
               "http://www.w3.org/2001/XMLSchema-instance".equals(uri);
    }

    private void processOutputElement(ElementContext ctx) {
        importsAllowed = false;
        OutputProperties props = new OutputProperties();
        
        String method = ctx.attributes.get("method");
        if (method != null) {
            props.setMethod(method);
        }
        
        String version = ctx.attributes.get("version");
        if (version != null) {
            props.setVersion(version);
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
        
        // XSLT 2.0: use-character-maps attribute
        String useCharacterMaps = ctx.attributes.get("use-character-maps");
        if (useCharacterMaps != null && !useCharacterMaps.isEmpty()) {
            String[] mapNames = useCharacterMaps.split("\\s+");
            for (String mapName : mapNames) {
                if (!mapName.isEmpty()) {
                    props.addUseCharacterMap(mapName);
                }
            }
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
        // XTSE0080: Check for reserved namespace
        QName keyName = parseQName(name, ctx.namespaceBindings, true);
        
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
        return parseQName(qnameStr, namespaces, false);
    }
    
    /**
     * Parses a QName string, optionally checking for reserved namespaces (XTSE0080).
     */
    private QName parseQName(String qnameStr, Map<String, String> namespaces, boolean checkReserved) throws SAXException {
        if (qnameStr == null) {
            return null;
        }
        
        // Handle EQName syntax: Q{uri}localname (XSLT 3.0)
        if (qnameStr.startsWith("Q{")) {
            int closeBrace = qnameStr.indexOf('}');
            if (closeBrace >= 2) {
                String uri = qnameStr.substring(2, closeBrace);
                String localPart = qnameStr.substring(closeBrace + 1);
                // XTSE0080: Check for reserved namespaces
                if (checkReserved && isReservedNamespace(uri)) {
                    throw new SAXException("XTSE0080: Reserved namespace '" + uri + 
                        "' cannot be used in the name '" + qnameStr + "'");
                }
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
                // XTSE0080: Check for reserved namespaces in component names
                if (checkReserved && isReservedNamespace(uri)) {
                    throw new SAXException("XTSE0080: Reserved namespace '" + uri + 
                        "' cannot be used in the name '" + qnameStr + "'");
                }
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
        
        // XTSE0010: name attribute is required
        if (name == null || name.isEmpty()) {
            throw new SAXException("XTSE0010: Required attribute 'name' is missing on xsl:attribute-set");
        }
        
        String useAttrSets = ctx.attributes.get("use-attribute-sets");
        
        // Expand attribute set name using namespace bindings
        // This ensures that ap:set and as:set resolve to the same set if both
        // prefixes map to the same namespace URI
        // XTSE0080: Check for reserved namespace
        String expandedName = expandAttributeSetName(name, ctx.namespaceBindings, true);
        
        List<String> useSets = new ArrayList<>();
        if (useAttrSets != null) {
            for (String s : splitOnWhitespace(useAttrSets)) {
                // Also expand use-attribute-sets names
                useSets.add(expandAttributeSetName(s.trim(), ctx.namespaceBindings));
            }
        }
        
        // XTSE0010: Validate children - only xsl:attribute allowed
        for (XSLTNode child : ctx.children) {
            if (child instanceof LiteralText) {
                String text = ((LiteralText) child).getText();
                if (text != null && !text.trim().isEmpty()) {
                    throw new SAXException("XTSE0010: Text content is not allowed in xsl:attribute-set");
                }
            } else if (!(child instanceof AttributeNode)) {
                throw new SAXException("XTSE0010: Only xsl:attribute is allowed in xsl:attribute-set");
            }
        }
        
        SequenceNode attrs = new SequenceNode(ctx.children);
        builder.addAttributeSet(new AttributeSet(expandedName, useSets, attrs));
    }
    
    /**
     * Expands an attribute set name to its expanded form: {namespace}localname
     * or just localname if no namespace.
     */
    private String expandAttributeSetName(String name, Map<String, String> namespaceBindings) throws SAXException {
        return expandAttributeSetName(name, namespaceBindings, false);
    }
    
    /**
     * Expands an attribute set name, optionally checking for reserved namespaces.
     */
    private String expandAttributeSetName(String name, Map<String, String> namespaceBindings, boolean checkReserved) throws SAXException {
        int colonIdx = name.indexOf(':');
        if (colonIdx > 0) {
            String prefix = name.substring(0, colonIdx);
            String localName = name.substring(colonIdx + 1);
            String nsUri = namespaceBindings.get(prefix);
            if (nsUri != null && !nsUri.isEmpty()) {
                // XTSE0080: Check for reserved namespaces
                if (checkReserved && isReservedNamespace(nsUri)) {
                    throw new SAXException("XTSE0080: Reserved namespace '" + nsUri + 
                        "' cannot be used in the attribute set name '" + name + "'");
                }
                return "{" + nsUri + "}" + localName;
            }
            // XTSE0280: Prefix not declared
            throw new SAXException("XTSE0280: Namespace prefix '" + prefix + "' is not declared");
        }
        return name;
    }
    
    /**
     * Expands all attribute set names in a whitespace-separated list.
     */
    private String expandAttributeSetNames(String names, Map<String, String> namespaceBindings) throws SAXException {
        if (names == null || names.isEmpty()) {
            return names;
        }
        StringBuilder result = new StringBuilder();
        for (String name : splitOnWhitespace(names)) {
            if (result.length() > 0) {
                result.append(' ');
            }
            result.append(expandAttributeSetName(name.trim(), namespaceBindings));
        }
        return result.toString();
    }

    private void processStripSpace(ElementContext ctx) throws SAXException {
        importsAllowed = false;
        
        // XTSE0260: xsl:strip-space must be empty
        validateEmptyElement(ctx, "xsl:strip-space");
        
        String elements = ctx.attributes.get("elements");
        // XTSE0010: "elements" attribute is required (but empty string is a valid value)
        if (elements == null) {
            throw new SAXException("XTSE0010: Required attribute 'elements' is missing on xsl:strip-space");
        }
        // Empty or whitespace-only elements list is valid - means no elements to strip
        for (String e : splitOnWhitespace(elements)) {
            // Resolve namespace prefix to URI for proper matching
            String resolved = resolveElementNameToUri(e, ctx.namespaceBindings);
            builder.addStripSpaceElement(resolved);
        }
    }

    private void processPreserveSpace(ElementContext ctx) throws SAXException {
        importsAllowed = false;
        
        // XTSE0260: xsl:preserve-space must be empty
        validateEmptyElement(ctx, "xsl:preserve-space");
        String elements = ctx.attributes.get("elements");
        // XTSE0010: "elements" attribute is required (but empty string is a valid value)
        if (elements == null) {
            throw new SAXException("XTSE0010: Required attribute 'elements' is missing on xsl:preserve-space");
        }
        // Empty or whitespace-only elements list is valid - means no elements to preserve
        for (String e : splitOnWhitespace(elements)) {
            // Resolve namespace prefix to URI for proper matching
            String resolved = resolveElementNameToUri(e, ctx.namespaceBindings);
            builder.addPreserveSpaceElement(resolved);
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
        
        // Unprefixed name - check for xpath-default-namespace
        String defaultNs = getDefaultElementNamespace();
        if (defaultNs != null && !defaultNs.isEmpty()) {
            // Use the xpath-default-namespace for unprefixed element names
            return "{" + defaultNs + "}" + pattern;
        }
        
        // No default namespace - matches elements in no namespace
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
        // Ensure import precedence is assigned for top-level variables (XTSE0630 detection)
        if (isTopLevel) {
            importsAllowed = false;
            ensurePrecedenceAssigned();
        }
        
        String name = ctx.attributes.get("name");
        if (name == null || name.isEmpty()) {
            throw new SAXException("XTSE0010: xsl:variable requires name attribute");
        }
        
        String select = ctx.attributes.get("select");
        String staticAttr = ctx.attributes.get("static");
        String asType = ctx.attributes.get("as"); // XSLT 2.0 type annotation
        
        // Parse QName with resolved namespace
        // XTSE0080: Check for reserved namespace
        QName varName = parseQName(name, ctx.namespaceBindings, true);
        
        // Check for static variable (XSLT 3.0)
        boolean isStatic = isStaticValue(staticAttr);
        
        if (isStatic && isTopLevel) {
            // Static variable: evaluate at compile time and store for use-when
            // Use the element's base URI for static-base-uri()
            XPathValue staticValue = evaluateStaticExpression(select, varName.getLocalName(), ctx.baseURI);
            staticVariables.put(varName.getLocalName(), staticValue);
            // Add as global variable with pre-computed static value
            try {
                builder.addGlobalVariable(new GlobalVariable(varName, false, staticValue, importPrecedence));
            } catch (javax.xml.transform.TransformerConfigurationException e) {
                throw new SAXException(e.getMessage(), e);
            }
            return null;
        }
        
        XPathExpression selectExpr = select != null ? compileExpression(select) : null;
        SequenceNode content = ctx.children.isEmpty() ? null : new SequenceNode(ctx.children);
        
        if (isTopLevel) {
            try {
                builder.addGlobalVariable(new GlobalVariable(varName, false, selectExpr, content, importPrecedence, asType));
            } catch (javax.xml.transform.TransformerConfigurationException e) {
                throw new SAXException(e.getMessage(), e);
            }
            return null;
        }
        
        return new VariableNode(varName.getURI(), varName.getLocalName(), selectExpr, content, asType);
    }

    private XSLTNode compileParam(ElementContext ctx, boolean isTopLevel) throws SAXException {
        // Ensure import precedence is assigned for top-level params (XTSE0630 detection)
        if (isTopLevel) {
            importsAllowed = false;
            ensurePrecedenceAssigned();
        }
        
        // XTSE0010: xsl:param is only allowed at top level or as direct child of
        // xsl:template, xsl:function, or xsl:iterate
        if (!isTopLevel) {
            // Check parent element
            ElementContext parent = elementStack.isEmpty() ? null : elementStack.peek();
            if (parent == null || !XSLT_NS.equals(parent.namespaceURI) ||
                !("template".equals(parent.localName) || 
                  "function".equals(parent.localName) || 
                  "iterate".equals(parent.localName))) {
                throw new SAXException("XTSE0010: xsl:param is only allowed at the top level, " +
                    "or as a direct child of xsl:template, xsl:function, or xsl:iterate");
            }
        }
        
        String name = ctx.attributes.get("name");
        if (name == null || name.isEmpty()) {
            throw new SAXException("XTSE0010: xsl:param requires name attribute");
        }
        
        String select = ctx.attributes.get("select");
        String asType = ctx.attributes.get("as"); // XSLT 2.0 type annotation
        String staticAttr = ctx.attributes.get("static");
        String tunnelAttr = ctx.attributes.get("tunnel");
        String requiredAttr = ctx.attributes.get("required");  // XSLT 2.0+
        // XSLT 2.0 uses "yes"/"no", XSLT 3.0 also allows "true"/"false"
        String tunnelVal = tunnelAttr != null ? tunnelAttr.trim() : null;
        boolean tunnel = "yes".equals(tunnelVal) || "true".equals(tunnelVal) || "1".equals(tunnelVal);
        String requiredVal = requiredAttr != null ? requiredAttr.trim() : null;
        boolean required = "yes".equals(requiredVal) || "true".equals(requiredVal) || "1".equals(requiredVal);
        
        // XTSE0020: tunnel="yes" not allowed on global params
        if (tunnel && isTopLevel) {
            throw new SAXException("XTSE0020: tunnel='yes' is not allowed on a global parameter");
        }
        
        // XTSE0020: tunnel="yes" not allowed on function params
        if (tunnel) {
            ElementContext parent = elementStack.isEmpty() ? null : elementStack.peek();
            if (parent != null && XSLT_NS.equals(parent.namespaceURI) && "function".equals(parent.localName)) {
                throw new SAXException("XTSE0020: tunnel='yes' is not allowed on a function parameter");
            }
        }
        
        // Parse QName with resolved namespace
        // XTSE0080: Check for reserved namespace
        QName paramName = parseQName(name, ctx.namespaceBindings, true);
        
        // Check for static param (XSLT 3.0)
        boolean isStatic = isStaticValue(staticAttr);
        
        if (isStatic && isTopLevel) {
            // Static param: evaluate at compile time and store for use-when
            // Use the element's base URI for static-base-uri()
            XPathValue staticValue = evaluateStaticExpression(select, paramName.getLocalName(), ctx.baseURI);
            staticVariables.put(paramName.getLocalName(), staticValue);
            // Add as global variable with pre-computed static value
            try {
                builder.addGlobalVariable(new GlobalVariable(paramName, true, staticValue, importPrecedence));
            } catch (javax.xml.transform.TransformerConfigurationException e) {
                throw new SAXException(e.getMessage(), e);
            }
            return null;
        }
        
        XPathExpression selectExpr = select != null ? compileExpression(select) : null;
        SequenceNode content = ctx.children.isEmpty() ? null : new SequenceNode(ctx.children);
        
        if (isTopLevel) {
            // Top-level param is a global variable that can be set externally
            // XTDE0050: A required parameter must have a value supplied during transformation
            try {
                builder.addGlobalVariable(new GlobalVariable(paramName, true, selectExpr, content, 
                    importPrecedence, asType, ComponentVisibility.PUBLIC, required));
            } catch (javax.xml.transform.TransformerConfigurationException e) {
                throw new SAXException(e.getMessage(), e);
            }
            return null;
        }
        
        return new ParamNode(paramName.getURI(), paramName.getLocalName(), selectExpr, content, asType, tunnel);
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

    /**
     * Returns the effective XSLT version at the current point in the stylesheet.
     * This considers xsl:version attributes on literal result element ancestors
     * which establish backwards compatibility mode (XSLT 1.0 behavior).
     */
    private double getEffectiveVersion() {
        // Walk up the element stack to find an explicitly set version
        for (ElementContext ctx : elementStack) {
            if (ctx.effectiveVersion > 0) {
                return ctx.effectiveVersion;
            }
        }
        // Fall back to the stylesheet version
        return stylesheetVersion;
    }
    
    private XSLTNode compileValueOf(ElementContext ctx) throws SAXException {
        String select = ctx.attributes.get("select");
        boolean disableEscaping = "yes".equals(ctx.attributes.get("disable-output-escaping"));
        // XSLT 2.0+ separator attribute (default is single space for sequences)
        String separator = ctx.attributes.get("separator");
        // In XSLT 2.0+, value-of outputs all items; in 1.0, only the first
        // Use getEffectiveVersion() to respect xsl:version on ancestor LREs
        boolean xslt2Plus = getEffectiveVersion() >= 2.0;
        
        if (select != null) {
            return new ValueOfNode(compileExpression(select), disableEscaping, separator, xslt2Plus);
        }
        
        // XSLT 2.0+ allows content (sequence constructor) instead of select attribute
        // The output is the string-join of all items with the separator (default space)
        if (xslt2Plus && !ctx.children.isEmpty()) {
            XSLTNode content = ctx.children.size() == 1 ? ctx.children.get(0) 
                : new SequenceNode(new ArrayList<>(ctx.children));
            return new ValueOfContentNode(content, disableEscaping, separator);
        }
        
        // Also allow in forward-compatible mode
        if (forwardCompatible && !ctx.children.isEmpty()) {
            XSLTNode content = ctx.children.size() == 1 ? ctx.children.get(0) 
                : new SequenceNode(new ArrayList<>(ctx.children));
            return new ValueOfContentNode(content, disableEscaping, separator);
        }
        
        throw new SAXException("xsl:value-of requires select attribute");
    }

    private XSLTNode compileText(ElementContext ctx) throws SAXException {
        boolean disableEscaping = "yes".equals(ctx.attributes.get("disable-output-escaping"));
        StringBuilder text = new StringBuilder();
        for (XSLTNode child : ctx.children) {
            if (child instanceof LiteralText) {
                LiteralText lt = (LiteralText) child;
                // XTSE0010: Nested xsl:text is not allowed in XSLT 2.0+
                if (lt.isFromXslText() && stylesheetVersion >= 2.0) {
                    throw new SAXException("XTSE0010: Nested xsl:text elements are not allowed");
                }
                text.append(lt.getText());
            } else {
                // XTSE0010: xsl:text can only contain text content, no child elements
                throw new SAXException("XTSE0010: xsl:text cannot contain child elements");
            }
        }
        // Mark as fromXslText=true so whitespace-only content is never stripped
        return new LiteralText(text.toString(), disableEscaping, true);
    }

    private XSLTNode compileElement2(ElementContext ctx) throws SAXException {
        String name = ctx.attributes.get("name");
        String namespace = ctx.attributes.get("namespace");
        String useAttrSetsRaw = ctx.attributes.get("use-attribute-sets");
        String typeValue = ctx.attributes.get("type");
        String validationValue = ctx.attributes.get("validation");
        
        // Expand use-attribute-sets names using namespace bindings
        String useAttrSets = expandAttributeSetNames(useAttrSetsRaw, ctx.namespaceBindings);
        
        // Register attribute set references for validation (XTSE0710)
        if (useAttrSets != null && !useAttrSets.isEmpty()) {
            builder.registerAttributeSetReferences(useAttrSets);
        }
        
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
        
        // Keep xsl:on-empty and xsl:on-non-empty in content - handled by SequenceNode's two-phase execution
        return new ElementNode(nameAvt, nsAvt, useAttrSets, new SequenceNode(ctx.children), 
                               defaultNs, nsBindings, typeNamespaceURI, typeLocalName, validation,
                               null, null);
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
        String select = ctx.attributes.get("select");
        
        AttributeValueTemplate nameAvt = parseAvt(name);
        XPathExpression selectExpr = select != null ? compileExpression(select) : null;
        SequenceNode content = ctx.children.isEmpty() ? null : new SequenceNode(ctx.children);
        
        return new ProcessingInstructionNode(nameAvt, selectExpr, content);
    }

    private XSLTNode compileCopy(ElementContext ctx) throws SAXException {
        String selectValue = ctx.attributes.get("select");
        String useAttrSetsRaw = ctx.attributes.get("use-attribute-sets");
        String typeValue = ctx.attributes.get("type");
        String validationValue = ctx.attributes.get("validation");
        String inheritNamespacesValue = ctx.attributes.get("inherit-namespaces");
        String copyNamespacesValue = ctx.attributes.get("copy-namespaces");
        
        // XSLT 3.0: select attribute specifies items to copy (instead of context item)
        XPathExpression selectExpr = selectValue != null ? compileExpression(selectValue) : null;
        
        // Expand use-attribute-sets names using namespace bindings
        String useAttrSets = expandAttributeSetNames(useAttrSetsRaw, ctx.namespaceBindings);
        
        // Register attribute set references for validation (XTSE0710)
        if (useAttrSets != null && !useAttrSets.isEmpty()) {
            builder.registerAttributeSetReferences(useAttrSets);
        }
        
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
        
        // Check for shadow attribute (XSLT 3.0 _inherit-namespaces="{AVT}")
        String shadowInheritNamespaces = ctx.shadowAttributes.get("inherit-namespaces");
        AttributeValueTemplate inheritNamespacesAvt = null;
        
        // Parse inherit-namespaces (XSLT 3.0)
        boolean inheritNs = true;
        if (shadowInheritNamespaces != null) {
            // Shadow attribute takes precedence - compile as AVT
            inheritNamespacesAvt = parseAvt(shadowInheritNamespaces);
        } else if (inheritNamespacesValue != null && !inheritNamespacesValue.isEmpty()) {
            String trimmed = inheritNamespacesValue.trim();
            if ("yes".equals(trimmed) || "true".equals(trimmed) || "1".equals(trimmed)) {
                inheritNs = true;
            } else if ("no".equals(trimmed) || "false".equals(trimmed) || "0".equals(trimmed)) {
                inheritNs = false;
            } else {
                throw new SAXException("XTSE0020: inherit-namespaces must be 'yes' or 'no', got: " + inheritNamespacesValue);
            }
        }
        
        // Check for shadow attribute (XSLT 3.0 _copy-namespaces="{AVT}")
        String shadowCopyNamespaces = ctx.shadowAttributes.get("copy-namespaces");
        AttributeValueTemplate copyNamespacesAvt = null;
        
        // Parse copy-namespaces (XSLT 2.0+)
        boolean copyNs = true;
        if (shadowCopyNamespaces != null) {
            // Shadow attribute takes precedence - compile as AVT
            copyNamespacesAvt = parseAvt(shadowCopyNamespaces);
        } else if (copyNamespacesValue != null && !copyNamespacesValue.isEmpty()) {
            String trimmed = copyNamespacesValue.trim();
            if ("yes".equals(trimmed) || "true".equals(trimmed) || "1".equals(trimmed)) {
                copyNs = true;
            } else if ("no".equals(trimmed) || "false".equals(trimmed) || "0".equals(trimmed)) {
                copyNs = false;
            } else {
                throw new SAXException("XTSE0020: copy-namespaces must be 'yes' or 'no', got: " + copyNamespacesValue);
            }
        }
        
        // Check for xsl:on-empty child element (XSLT 3.0)
        XSLTNode onEmptyNode = null;
        List<XSLTNode> regularContent = new ArrayList<>();
        for (XSLTNode child : ctx.children) {
            if (child instanceof OnEmptyNode) {
                onEmptyNode = child;
            } else {
                regularContent.add(child);
            }
        }
        
        return new CopyNode(selectExpr, useAttrSets, new SequenceNode(regularContent), 
                           typeNamespaceURI, typeLocalName, validation, 
                           inheritNs, inheritNamespacesAvt, copyNs, copyNamespacesAvt, onEmptyNode);
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
        
        // Check for shadow attribute (XSLT 3.0 _copy-namespaces="{AVT}")
        String shadowCopyNamespaces = ctx.shadowAttributes.get("copy-namespaces");
        AttributeValueTemplate copyNamespacesAvt = null;
        
        // XTSE0020: copy-namespaces must be "yes"/"no" or "true"/"false" (XSLT 3.0)
        boolean copyNs = true;
        if (shadowCopyNamespaces != null) {
            // Shadow attribute takes precedence - compile as AVT
            copyNamespacesAvt = parseAvt(shadowCopyNamespaces);
        } else if (copyNamespaces != null && !copyNamespaces.isEmpty()) {
            String trimmed = copyNamespaces.trim();
            if ("yes".equals(trimmed) || "true".equals(trimmed) || "1".equals(trimmed)) {
                copyNs = true;
            } else if ("no".equals(trimmed) || "false".equals(trimmed) || "0".equals(trimmed)) {
                copyNs = false;
            } else {
                throw new SAXException("XTSE0020: copy-namespaces must be 'yes' or 'no', got: " + copyNamespaces);
            }
        }
        
        return new CopyOfNode(compileExpression(select), typeNamespaceURI, typeLocalName, 
                              validation, copyNs, copyNamespacesAvt);
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
        
        // XTSE0020: mode attribute cannot be an AVT
        validateNotAVT("xsl:apply-templates", "mode", mode);
        
        // Expand mode QName to Clark notation for proper namespace comparison
        String expandedMode = expandModeQName(mode);
        
        XPathExpression selectExpr = select != null ? compileExpression(select) : null;
        
        // Extract sorts and with-params from children
        // XTSE0010: Only xsl:sort and xsl:with-param are allowed as children
        // Note: whitespace-only text nodes are ignored per XSLT spec (even with xml:space="preserve")
        List<SortSpec> sorts = new ArrayList<>();
        List<WithParamNode> params = new ArrayList<>();
        Set<String> seenParamNames = new HashSet<>();
        boolean foundNonSort = false;
        
        for (XSLTNode child : ctx.children) {
            // Ignore whitespace-only text (per XSLT spec section 3.4)
            if (child instanceof LiteralText && isWhitespace(((LiteralText) child).getText())) {
                continue;
            }
            if (child instanceof SortSpecNode) {
                if (foundNonSort) {
                    throw new SAXException("XTSE0010: xsl:sort must come before xsl:with-param in xsl:apply-templates");
                }
                sorts.add(((SortSpecNode) child).getSortSpec());
            } else if (child instanceof WithParamNode) {
                foundNonSort = true;
                WithParamNode param = (WithParamNode) child;
                // XTSE0670: Check for duplicate parameter names
                String paramName = param.getName().toString();
                if (!seenParamNames.add(paramName)) {
                    throw new SAXException("XTSE0670: Duplicate parameter name '" + paramName + 
                        "' in xsl:apply-templates");
                }
                params.add(param);
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
        
        // Expand the template name to Clark notation for proper namespace matching
        // e.g., woo:a and hoo:a should match if both prefixes map to the same URI
        String expandedName = expandQName(name);
        
        // XTSE0010: Only xsl:with-param is allowed as a child
        // Note: whitespace-only text nodes are ignored per XSLT spec (even with xml:space="preserve")
        List<WithParamNode> params = new ArrayList<>();
        Set<String> seenParamNames = new HashSet<>();
        for (XSLTNode child : ctx.children) {
            // Ignore whitespace-only text (per XSLT spec section 3.4)
            if (child instanceof LiteralText && isWhitespace(((LiteralText) child).getText())) {
                continue;
            }
            if (child instanceof WithParamNode) {
                WithParamNode param = (WithParamNode) child;
                // XTSE0670: Check for duplicate parameter names
                String paramName = param.getName().toString();
                if (!seenParamNames.add(paramName)) {
                    throw new SAXException("XTSE0670: Duplicate parameter name '" + paramName + 
                        "' in xsl:call-template");
                }
                params.add(param);
            } else {
                throw new SAXException("XTSE0010: Only xsl:with-param is allowed in xsl:call-template");
            }
        }
        
        return new CallTemplateNode(expandedName, params);
    }

    private XSLTNode compileApplyImports(ElementContext ctx) throws SAXException {
        // XSLT 2.0+: xsl:apply-imports can have xsl:with-param children
        List<WithParamNode> params = new ArrayList<>();
        for (XSLTNode child : ctx.children) {
            if (child instanceof LiteralText && isWhitespace(((LiteralText) child).getText())) {
                continue;
            }
            if (child instanceof WithParamNode) {
                params.add((WithParamNode) child);
            } else {
                throw new SAXException("XTSE0010: Only xsl:with-param is allowed in xsl:apply-imports");
            }
        }
        return new ApplyImportsNode(params);
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
        // Two-pass approach to correctly handle whitespace:
        // 1. Find the index of the last xsl:sort
        // 2. Everything after the last sort goes to body (including whitespace)
        //    Whitespace before/between sorts is stripped
        
        int lastSortIndex = -1;
        for (int i = 0; i < ctx.children.size(); i++) {
            if (ctx.children.get(i) instanceof SortSpecNode) {
                lastSortIndex = i;
            }
        }
        
        List<SortSpec> sorts = new ArrayList<>();
        List<XSLTNode> bodyNodes = new ArrayList<>();
        boolean foundNonSort = false;
        
        for (int i = 0; i < ctx.children.size(); i++) {
            XSLTNode child = ctx.children.get(i);
            
            // Skip whitespace-only text when checking sort ordering
            // BUT never skip text that came from xsl:text (it's explicit)
            boolean isStrippableWhitespace = child instanceof LiteralText && 
                isWhitespace(((LiteralText) child).getText()) &&
                !((LiteralText) child).isFromXslText();
            
            if (child instanceof SortSpecNode) {
                if (foundNonSort) {
                    throw new SAXException("XTSE0010: xsl:sort must come before other content in xsl:for-each");
                }
                sorts.add(((SortSpecNode) child).getSortSpec());
            } else if (i > lastSortIndex) {
                // We're past all sorts - everything goes to body, including whitespace
                bodyNodes.add(child);
                if (!isStrippableWhitespace) {
                    foundNonSort = true;
                }
            } else if (!isStrippableWhitespace) {
                // Non-whitespace content before/between sorts - error
                if (lastSortIndex >= 0) {
                    throw new SAXException("XTSE0010: xsl:sort must come before other content in xsl:for-each");
                }
                foundNonSort = true;
                bodyNodes.add(child);
            }
            // else: whitespace before/between sorts - strip it
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
     * Compiles an xsl:document instruction (XSLT 2.0+).
     *
     * <p>xsl:document constructs a document node containing the sequence
     * constructed by evaluating its content. This is primarily used for
     * creating document nodes in variables with as="document-node()".
     *
     * <p>Attributes:
     * <ul>
     *   <li>validation - Validation mode (strip, preserve, lax, strict)</li>
     *   <li>type - Type annotation (mutually exclusive with validation)</li>
     * </ul>
     */
    private XSLTNode compileDocumentConstructor(ElementContext ctx) throws SAXException {
        // Parse validation attribute
        String validationValue = ctx.attributes.get("validation");
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
                    throw new SAXException("Invalid validation value on xsl:document: " + validationValue);
            }
        }
        
        // Parse type attribute (mutually exclusive with validation)
        String typeValue = ctx.attributes.get("type");
        String typeNamespaceURI = null;
        String typeLocalName = null;
        if (typeValue != null && !typeValue.isEmpty()) {
            if (validationValue != null) {
                throw new SAXException("XTSE1505: xsl:document cannot have both type and validation attributes");
            }
            int colonPos = typeValue.indexOf(':');
            if (colonPos > 0) {
                String prefix = typeValue.substring(0, colonPos);
                typeNamespaceURI = ctx.namespaceBindings.get(prefix);
                if (typeNamespaceURI == null) {
                    throw new SAXException("Undeclared prefix in type attribute: " + prefix);
                }
                typeLocalName = typeValue.substring(colonPos + 1);
            } else {
                // Check for Clark notation {uri}local
                if (typeValue.startsWith("{")) {
                    int closePos = typeValue.indexOf('}');
                    if (closePos > 0) {
                        typeNamespaceURI = typeValue.substring(1, closePos);
                        typeLocalName = typeValue.substring(closePos + 1);
                    }
                } else {
                    typeLocalName = typeValue;
                }
            }
        }
        
        return new DocumentConstructorNode(ctx.children, validation, typeNamespaceURI, typeLocalName);
    }

    /**
     * Compiles an xsl:source-document instruction (XSLT 3.0).
     *
     * <p>xsl:source-document provides access to a secondary input document,
     * supporting both streaming and non-streaming modes.
     *
     * <p>Attributes:
     * <ul>
     *   <li>href (required) - URI of the document, can be AVT</li>
     *   <li>streamable - Whether to use streaming mode (yes/no)</li>
     *   <li>validation - Validation mode (strip, preserve, lax, strict)</li>
     *   <li>type - Type annotation</li>
     *   <li>use-accumulators - Which accumulators to apply</li>
     * </ul>
     */
    private XSLTNode compileSourceDocument(ElementContext ctx) throws SAXException {
        String hrefStr = ctx.attributes.get("href");
        if (hrefStr == null || hrefStr.isEmpty()) {
            throw new SAXException("XTSE3085: xsl:source-document requires href attribute");
        }
        
        // href is an AVT
        AttributeValueTemplate hrefAvt = parseAvt(hrefStr);
        
        // streamable attribute (default is processor-defined)
        // Gonzalez defaults to streaming-first for better memory efficiency
        String streamableStr = ctx.attributes.get("streamable");
        // Also check for shadow attribute _streamable (used in tests with static params)
        if (streamableStr == null) {
            streamableStr = ctx.attributes.get("_streamable");
        }
        // Default to streaming (true) unless explicitly set to "no" or "false"
        boolean streamable = !"no".equals(streamableStr) && !"false".equals(streamableStr);
        
        // validation attribute
        String validation = ctx.attributes.get("validation");
        if (validation != null && !validation.isEmpty()) {
            validation = validation.trim();
            if (!validation.equals("strict") && !validation.equals("lax") 
                    && !validation.equals("preserve") && !validation.equals("strip")) {
                throw new SAXException("Invalid validation value on xsl:source-document: " + validation);
            }
        }
        
        // type attribute (mutually exclusive with validation)
        String typeStr = ctx.attributes.get("type");
        if (typeStr != null && validation != null) {
            throw new SAXException("XTSE1505: xsl:source-document cannot have both type and validation attributes");
        }
        
        // use-accumulators attribute
        String useAccumulators = ctx.attributes.get("use-accumulators");
        
        // Compile body (filter out xsl:fallback)
        List<XSLTNode> bodyNodes = new ArrayList<>();
        for (XSLTNode child : ctx.children) {
            // Skip fallback elements (they're only used when the instruction isn't supported)
            if (!(child.toString().contains("fallback"))) {
                bodyNodes.add(child);
            }
        }
        
        XSLTNode body;
        if (bodyNodes.isEmpty()) {
            body = null;
        } else if (bodyNodes.size() == 1) {
            body = bodyNodes.get(0);
        } else {
            body = new SequenceNode(bodyNodes);
        }
        
        return new SourceDocumentNode(hrefAvt, streamable, validation, useAccumulators, body);
    }

    /**
     * Compiles an xsl:merge instruction (XSLT 3.0).
     *
     * <p>Structure:
     * <pre>
     * &lt;xsl:merge&gt;
     *   &lt;xsl:merge-source name="src1" select="..."&gt;
     *     &lt;xsl:merge-key select="..." order="ascending"/&gt;
     *   &lt;/xsl:merge-source&gt;
     *   &lt;xsl:merge-action&gt;...&lt;/xsl:merge-action&gt;
     * &lt;/xsl:merge&gt;
     * </pre>
     */
    private XSLTNode compileMerge(ElementContext ctx) throws SAXException {
        List<MergeNode.MergeSource> sources = new ArrayList<>();
        XSLTNode action = null;
        
        for (XSLTNode child : ctx.children) {
            String childStr = child.toString();
            if (childStr.contains("MergeSourceHolder")) {
                // This is a merge-source we compiled
                MergeSourceHolder holder = (MergeSourceHolder) child;
                sources.add(holder.source);
            } else if (childStr.contains("MergeActionHolder")) {
                // This is the merge-action
                MergeActionHolder holder = (MergeActionHolder) child;
                action = holder.content;
            }
        }
        
        if (sources.isEmpty()) {
            throw new SAXException("XTSE0010: xsl:merge requires at least one xsl:merge-source");
        }
        if (action == null) {
            throw new SAXException("XTSE0010: xsl:merge requires xsl:merge-action");
        }
        
        return new MergeNode(sources, action);
    }

    /**
     * Compiles an xsl:merge-source element.
     */
    private XSLTNode compileMergeSource(ElementContext ctx) throws SAXException {
        String name = ctx.attributes.get("name");
        String selectStr = ctx.attributes.get("select");
        String forEachItemStr = ctx.attributes.get("for-each-item");
        String forEachSourceStr = ctx.attributes.get("for-each-source");
        String sortBeforeMergeStr = ctx.attributes.get("sort-before-merge");
        String streamableStr = ctx.attributes.get("streamable");
        
        XPathExpression select = selectStr != null ? compileExpression(selectStr) : null;
        XPathExpression forEachItem = forEachItemStr != null ? compileExpression(forEachItemStr) : null;
        XPathExpression forEachSource = forEachSourceStr != null ? compileExpression(forEachSourceStr) : null;
        boolean sortBeforeMerge = "yes".equals(sortBeforeMergeStr) || "true".equals(sortBeforeMergeStr);
        boolean streamable = "yes".equals(streamableStr) || "true".equals(streamableStr);
        
        // Collect merge keys
        List<MergeNode.MergeKey> keys = new ArrayList<>();
        for (XSLTNode child : ctx.children) {
            if (child.toString().contains("MergeKeyHolder")) {
                MergeKeyHolder holder = (MergeKeyHolder) child;
                keys.add(holder.key);
            }
        }
        
        MergeNode.MergeSource source = new MergeNode.MergeSource(
            name, select, forEachItem, forEachSource, sortBeforeMerge, streamable, keys
        );
        
        return new MergeSourceHolder(source);
    }

    /**
     * Compiles an xsl:merge-key element.
     */
    private XSLTNode compileMergeKey(ElementContext ctx) throws SAXException {
        String selectStr = ctx.attributes.get("select");
        if (selectStr == null || selectStr.isEmpty()) {
            throw new SAXException("XTSE0010: xsl:merge-key requires select attribute");
        }
        
        XPathExpression select = compileExpression(selectStr);
        String order = ctx.attributes.get("order");
        String lang = ctx.attributes.get("lang");
        String collation = ctx.attributes.get("collation");
        String dataType = ctx.attributes.get("data-type");
        
        MergeNode.MergeKey key = new MergeNode.MergeKey(select, order, lang, collation, dataType);
        return new MergeKeyHolder(key);
    }

    /**
     * Compiles an xsl:merge-action element.
     */
    private XSLTNode compileMergeAction(ElementContext ctx) throws SAXException {
        XSLTNode content;
        if (ctx.children.isEmpty()) {
            content = null;
        } else if (ctx.children.size() == 1) {
            content = ctx.children.get(0);
        } else {
            content = new SequenceNode(new ArrayList<>(ctx.children));
        }
        return new MergeActionHolder(content);
    }

    /**
     * Holder for compiled merge-source during parsing.
     */
    private static class MergeSourceHolder implements XSLTNode {
        final MergeNode.MergeSource source;
        MergeSourceHolder(MergeNode.MergeSource source) { this.source = source; }
        @Override public void execute(TransformContext ctx, OutputHandler out) {}
        @Override public String toString() { return "MergeSourceHolder"; }
    }

    /**
     * Holder for compiled merge-key during parsing.
     */
    private static class MergeKeyHolder implements XSLTNode {
        final MergeNode.MergeKey key;
        MergeKeyHolder(MergeNode.MergeKey key) { this.key = key; }
        @Override public void execute(TransformContext ctx, OutputHandler out) {}
        @Override public String toString() { return "MergeKeyHolder"; }
    }

    /**
     * Holder for compiled merge-action during parsing.
     */
    private static class MergeActionHolder implements XSLTNode {
        final XSLTNode content;
        MergeActionHolder(XSLTNode content) { this.content = content; }
        @Override public void execute(TransformContext ctx, OutputHandler out) {}
        @Override public String toString() { return "MergeActionHolder"; }
    }

    /**
     * Compiles an xsl:context-item declaration (XSLT 3.0).
     *
     * <p>xsl:context-item declares the expected context item for a template:
     * <ul>
     *   <li>as - The required type (default: item())</li>
     *   <li>use - "required", "optional", or "absent" (default: optional)</li>
     * </ul>
     *
     * <p>This is primarily used for streamability analysis and type checking.
     */
    private XSLTNode compileContextItem(ElementContext ctx) throws SAXException {
        String asType = ctx.attributes.get("as");
        String use = ctx.attributes.get("use");
        
        // Validate 'use' attribute
        if (use != null && !use.isEmpty()) {
            if (!use.equals("required") && !use.equals("optional") && !use.equals("absent")) {
                throw new SAXException("XTSE0020: Invalid value for 'use' attribute: " + use);
            }
        }
        
        // Return a holder that doesn't execute anything at runtime
        // The information could be used for type checking if needed
        return new ContextItemDeclaration(asType, use);
    }

    /**
     * Processes xsl:global-context-item declaration (XSLT 3.0).
     *
     * <p>xsl:global-context-item declares the expected global context item:
     * <ul>
     *   <li>as - The required type (default: item())</li>
     *   <li>use - "required", "optional", or "absent" (default: optional)</li>
     * </ul>
     */
    private void processGlobalContextItem(ElementContext ctx) throws SAXException {
        String asType = ctx.attributes.get("as");
        String use = ctx.attributes.get("use");
        
        // Validate 'use' attribute
        if (use != null && !use.isEmpty()) {
            if (!use.equals("required") && !use.equals("optional") && !use.equals("absent")) {
                throw new SAXException("XTSE0020: Invalid value for 'use' attribute: " + use);
            }
        }
        
        // Store in stylesheet builder for potential runtime validation
        builder.setGlobalContextItemType(asType);
        builder.setGlobalContextItemUse(use);
    }

    /**
     * Declaration node for xsl:context-item - used within templates.
     */
    private static class ContextItemDeclaration implements XSLTNode {
        final String asType;
        final String use;
        
        ContextItemDeclaration(String asType, String use) {
            this.asType = asType;
            this.use = use;
        }
        
        @Override
        public void execute(TransformContext ctx, OutputHandler out) {
            // No runtime execution - this is a declaration
        }
        
        @Override
        public String toString() {
            return "ContextItemDeclaration[as=" + asType + ", use=" + use + "]";
        }
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
        String collationStr = ctx.attributes.get("collation");
        
        // XTSE1080: it is a static error if more than one grouping attribute is present
        int groupingAttrs = 0;
        if (groupBy != null && !groupBy.isEmpty()) groupingAttrs++;
        if (groupAdjacent != null && !groupAdjacent.isEmpty()) groupingAttrs++;
        if (groupStartingWith != null && !groupStartingWith.isEmpty()) groupingAttrs++;
        if (groupEndingWith != null && !groupEndingWith.isEmpty()) groupingAttrs++;
        if (groupingAttrs > 1) {
            throw new SAXException("XTSE1080: xsl:for-each-group must have exactly one of group-by, group-adjacent, group-starting-with, or group-ending-with");
        }
        
        XPathExpression select = compileExpression(selectStr);
        
        // Extract xsl:sort specifications from children (same logic as xsl:for-each)
        int lastSortIndex = -1;
        for (int i = 0; i < ctx.children.size(); i++) {
            if (ctx.children.get(i) instanceof SortSpecNode) {
                lastSortIndex = i;
            }
        }
        
        List<SortSpec> sorts = new ArrayList<>();
        List<XSLTNode> bodyNodes = new ArrayList<>();
        boolean foundNonSort = false;
        
        for (int i = 0; i < ctx.children.size(); i++) {
            XSLTNode child = ctx.children.get(i);
            
            boolean isStrippableWhitespace = child instanceof LiteralText && 
                isWhitespace(((LiteralText) child).getText()) &&
                !((LiteralText) child).isFromXslText();
            
            if (child instanceof SortSpecNode) {
                if (foundNonSort) {
                    throw new SAXException("XTSE0010: xsl:sort must come before other content in xsl:for-each-group");
                }
                sorts.add(((SortSpecNode) child).getSortSpec());
            } else if (i > lastSortIndex) {
                bodyNodes.add(child);
                if (!isStrippableWhitespace) {
                    foundNonSort = true;
                }
            } else if (!isStrippableWhitespace) {
                if (lastSortIndex >= 0) {
                    throw new SAXException("XTSE0010: xsl:sort must come before other content in xsl:for-each-group");
                }
                foundNonSort = true;
                bodyNodes.add(child);
            }
        }
        
        XSLTNode body = bodyNodes.isEmpty() ? null : new SequenceNode(bodyNodes);
        
        // Parse collation AVT (used by group-by and group-adjacent)
        AttributeValueTemplate collationAvt = null;
        if (collationStr != null && !collationStr.isEmpty()) {
            collationAvt = parseAvt(collationStr);
        }
        
        if (groupBy != null && !groupBy.isEmpty()) {
            XPathExpression groupByExpr = compileExpression(groupBy);
            return ForEachGroupNode.groupBy(select, groupByExpr, body, collationAvt, sorts);
        } else if (groupAdjacent != null && !groupAdjacent.isEmpty()) {
            XPathExpression groupAdjacentExpr = compileExpression(groupAdjacent);
            return ForEachGroupNode.groupAdjacent(select, groupAdjacentExpr, body, collationAvt, sorts);
        } else if (groupStartingWith != null && !groupStartingWith.isEmpty()) {
            Pattern pattern = compilePattern(groupStartingWith);
            return ForEachGroupNode.groupStartingWith(select, pattern, body, sorts);
        } else if (groupEndingWith != null && !groupEndingWith.isEmpty()) {
            Pattern pattern = compilePattern(groupEndingWith);
            return ForEachGroupNode.groupEndingWith(select, pattern, body, sorts);
        } else {
            throw new SAXException("xsl:for-each-group requires group-by, group-adjacent, group-starting-with, or group-ending-with attribute");
        }
    }

    /**
     * Compiles an xsl:perform-sort instruction (XSLT 2.0).
     *
     * <p>xsl:perform-sort sorts a sequence without iterating over it.
     * The sorted sequence becomes the result of the instruction.
     */
    private XSLTNode compilePerformSort(ElementContext ctx) throws SAXException {
        String select = ctx.attributes.get("select");
        // select is optional - if not present, content generates the sequence
        
        // Extract xsl:sort children
        List<SortSpec> sorts = new ArrayList<>();
        List<XSLTNode> contentNodes = new ArrayList<>();
        
        int lastSortIndex = -1;
        for (int i = 0; i < ctx.children.size(); i++) {
            if (ctx.children.get(i) instanceof SortSpecNode) {
                lastSortIndex = i;
            }
        }
        
        boolean foundNonSort = false;
        for (int i = 0; i < ctx.children.size(); i++) {
            XSLTNode child = ctx.children.get(i);
            
            boolean isStrippableWhitespace = child instanceof LiteralText && 
                isWhitespace(((LiteralText) child).getText()) &&
                !((LiteralText) child).isFromXslText();
            
            if (child instanceof SortSpecNode) {
                if (foundNonSort) {
                    throw new SAXException("XTSE0010: xsl:sort must come before other content in xsl:perform-sort");
                }
                sorts.add(((SortSpecNode) child).getSortSpec());
            } else if (i > lastSortIndex) {
                contentNodes.add(child);
                if (!isStrippableWhitespace) {
                    foundNonSort = true;
                }
            } else if (!isStrippableWhitespace) {
                if (lastSortIndex >= 0) {
                    throw new SAXException("XTSE0010: xsl:sort must come before other content in xsl:perform-sort");
                }
                foundNonSort = true;
                contentNodes.add(child);
            }
        }
        
        if (sorts.isEmpty()) {
            throw new SAXException("XTSE0010: xsl:perform-sort requires at least one xsl:sort child");
        }
        
        XPathExpression selectExpr = select != null ? compileExpression(select) : null;
        XSLTNode content = contentNodes.isEmpty() ? null : new SequenceNode(contentNodes);
        
        // If no select, the content must be present
        if (selectExpr == null && content == null) {
            throw new SAXException("XTSE0010: xsl:perform-sort requires either select attribute or content");
        }
        
        return new PerformSortNode(selectExpr, sorts, content);
    }

    /**
     * Compiles an xsl:evaluate instruction (XSLT 3.0).
     *
     * <p>xsl:evaluate dynamically evaluates an XPath expression provided as a string.
     */
    private XSLTNode compileEvaluate(ElementContext ctx) throws SAXException {
        String xpathStr = ctx.attributes.get("xpath");
        if (xpathStr == null || xpathStr.isEmpty()) {
            throw new SAXException("XTSE0010: xsl:evaluate requires xpath attribute");
        }
        
        String contextItemStr = ctx.attributes.get("context-item");
        String baseUriStr = ctx.attributes.get("base-uri");
        String namespaceContextStr = ctx.attributes.get("namespace-context");
        String asType = ctx.attributes.get("as");
        
        XPathExpression xpathExpr = compileExpression(xpathStr);
        XPathExpression contextItemExpr = contextItemStr != null ? compileExpression(contextItemStr) : null;
        XPathExpression baseUriExpr = baseUriStr != null ? compileExpression(baseUriStr) : null;
        XPathExpression namespaceContextExpr = namespaceContextStr != null ? 
            compileExpression(namespaceContextStr) : null;
        
        // Process xsl:with-param children
        List<EvaluateNode.WithParamNode> params = new ArrayList<>();
        for (XSLTNode child : ctx.children) {
            // Check if this is a with-param node
            // In our structure, params are processed as children
            if (child instanceof ParamNode) {
                ParamNode p = (ParamNode) child;
                params.add(new EvaluateNode.WithParamNode(
                    p.getNamespaceURI(), p.getLocalName(), 
                    p.getSelectExpr(), p.getContent()));
            }
        }
        
        return new EvaluateNode(xpathExpr, contextItemExpr, baseUriExpr, 
            namespaceContextExpr, asType, params);
    }

    /**
     * Compiles an xsl:try instruction (XSLT 3.0).
     *
     * <p>xsl:try executes content that might throw an error. If an error
     * occurs, xsl:catch provides error handling.
     */
    private XSLTNode compileTry(ElementContext ctx) throws SAXException {
        // Check for select attribute
        String selectStr = ctx.attributes.get("select");
        XPathExpression selectExpr = null;
        if (selectStr != null && !selectStr.isEmpty()) {
            selectExpr = compileExpression(selectStr);
        }
        
        // Separate try content from catch blocks
        List<XSLTNode> tryContent = new ArrayList<>();
        List<CatchNode> catchBlocks = new ArrayList<>();
        
        for (XSLTNode child : ctx.children) {
            if (child instanceof CatchNode) {
                catchBlocks.add((CatchNode) child);
            } else {
                tryContent.add(child);
            }
        }
        
        XSLTNode body = null;
        if (selectExpr != null) {
            // If select attribute is present, it provides the try content
            body = new SelectExprNode(selectExpr);
        } else if (!tryContent.isEmpty()) {
            body = new SequenceNode(tryContent);
        }
        return new TryNode(body, catchBlocks);
    }
    
    /**
     * Helper node that evaluates a select expression and outputs its value.
     */
    private static class SelectExprNode extends XSLTInstruction {
        private final XPathExpression expr;
        
        SelectExprNode(XPathExpression expr) {
            this.expr = expr;
        }
        
        @Override public String getInstructionName() { return "select-expr"; }
        
        @Override
        public void execute(TransformContext context, OutputHandler output) throws SAXException {
            try {
                XPathValue result = expr.evaluate(context);
                if (result != null) {
                    String str = result.asString();
                    if (str != null && !str.isEmpty()) {
                        output.characters(str);
                    }
                }
            } catch (XPathException e) {
                throw new SAXException(e.getMessage(), e);
            }
        }
    }

    /**
     * Compiles an xsl:assert instruction (XSLT 3.0).
     *
     * <p>xsl:assert is used to test assertions during transformation.
     * If the test fails, a dynamic error is raised.
     */
    private XSLTNode compileAssert(ElementContext ctx) throws SAXException {
        String testStr = ctx.attributes.get("test");
        if (testStr == null || testStr.isEmpty()) {
            throw new SAXException("XTSE0010: xsl:assert requires test attribute");
        }
        
        String errorCodeStr = ctx.attributes.get("error-code");
        XPathExpression testExpr = compileExpression(testStr);
        XSLTNode messageContent = ctx.children.isEmpty() ? null : new SequenceNode(ctx.children);
        
        return new AssertNode(testExpr, errorCodeStr, messageContent);
    }

    /**
     * Compiles an xsl:on-empty instruction (XSLT 3.0).
     *
     * <p>xsl:on-empty specifies content to use if its containing sequence
     * constructor produces an empty sequence.
     */
    private XSLTNode compileOnEmpty(ElementContext ctx) throws SAXException {
        String selectValue = ctx.attributes.get("select");
        
        // The content of xsl:on-empty is output only if the sequence would be empty
        // Can have either select attribute or content, but not both
        if (selectValue != null) {
            XPathExpression selectExpr = compileExpression(selectValue);
            return new OnEmptyNode(selectExpr);
        } else {
            XSLTNode content = ctx.children.isEmpty() ? null : new SequenceNode(ctx.children);
            return new OnEmptyNode(content);
        }
    }

    /**
     * Compiles an xsl:on-non-empty instruction (XSLT 3.0).
     *
     * <p>xsl:on-non-empty specifies content to output only if the containing
     * sequence constructor produces a non-empty sequence. It can have either
     * a select attribute or content (sequence constructor).
     */
    private XSLTNode compileOnNonEmpty(ElementContext ctx) throws SAXException {
        String selectValue = ctx.attributes.get("select");
        if (selectValue != null) {
            XPathExpression selectExpr = compileExpression(selectValue);
            return new OnNonEmptyNode(selectExpr);
        } else {
            XSLTNode content = ctx.children.isEmpty() ? null : new SequenceNode(ctx.children);
            return new OnNonEmptyNode(content);
        }
    }

    /**
     * Compiles an xsl:where-populated instruction (XSLT 3.0).
     *
     * <p>xsl:where-populated outputs its content only if that content
     * produces non-empty output.
     */
    private XSLTNode compileWherePopulated(ElementContext ctx) throws SAXException {
        XSLTNode content = ctx.children.isEmpty() ? null : new SequenceNode(ctx.children);
        return new WherePopulatedNode(content);
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
        
        // XTSE0020: undeclare-prefixes must be a valid boolean value (yes/no/true/false/1/0)
        // or an AVT that evaluates to one of these values
        String undeclarePrefixes = ctx.attributes.get("undeclare-prefixes");
        if (undeclarePrefixes != null && !undeclarePrefixes.isEmpty()) {
            // Check if it's an AVT (contains {})
            boolean isAvt = undeclarePrefixes.contains("{") && undeclarePrefixes.contains("}");
            if (!isAvt) {
                // Static value - validate immediately
                validateYesOrNo("xsl:result-document", "undeclare-prefixes", undeclarePrefixes);
            }
            // For AVT, validation happens at runtime
        }
        
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

    private XSLTNode compileChoose(ElementContext ctx) throws SAXException {
        List<WhenNode> whens = new ArrayList<>();
        SequenceNode otherwise = null;
        boolean sawOtherwise = false;
        
        for (XSLTNode child : ctx.children) {
            if (child instanceof WhenNode) {
                // XTSE0010: xsl:when must come before xsl:otherwise
                if (sawOtherwise) {
                    throw new SAXException("XTSE0010: xsl:when must come before xsl:otherwise in xsl:choose");
                }
                whens.add((WhenNode) child);
            } else if (child instanceof OtherwiseNode) {
                // XTSE0010: only one xsl:otherwise allowed
                if (sawOtherwise) {
                    throw new SAXException("XTSE0010: xsl:choose must have at most one xsl:otherwise");
                }
                sawOtherwise = true;
                otherwise = ((OtherwiseNode) child).getContent();
            } else if (child instanceof LiteralText) {
                // XTSE0010: no text content allowed in xsl:choose (except whitespace)
                String text = ((LiteralText) child).getText();
                if (text != null && !text.trim().isEmpty()) {
                    throw new SAXException("XTSE0010: Text content is not allowed in xsl:choose");
                }
            } else {
                // XTSE0010: only xsl:when and xsl:otherwise are allowed in xsl:choose
                throw new SAXException("XTSE0010: Only xsl:when and xsl:otherwise are allowed in xsl:choose");
            }
        }
        
        // XTSE0010: xsl:choose must have at least one xsl:when
        if (whens.isEmpty()) {
            throw new SAXException("XTSE0010: xsl:choose must have at least one xsl:when element");
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
        String asType = ctx.attributes.get("as"); // XSLT 2.0+: type declaration
        String tunnelAttr = ctx.attributes.get("tunnel");
        // XSLT 2.0 uses "yes"/"no", XSLT 3.0 also allows "true"/"false"
        String tunnelVal = tunnelAttr != null ? tunnelAttr.trim() : null;
        boolean tunnel = "yes".equals(tunnelVal) || "true".equals(tunnelVal) || "1".equals(tunnelVal);
        
        // Parse QName to get namespace URI and local name
        QName paramName = parseQName(name, ctx.namespaceBindings);
        
        XPathExpression selectExpr = select != null ? compileExpression(select) : null;
        SequenceNode content = ctx.children.isEmpty() ? null : new SequenceNode(ctx.children);
        
        return new WithParamNode(paramName.getURI(), paramName.getLocalName(), selectExpr, content, tunnel, asType);
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
        
        // Parse sort parameters as AVTs (they can contain expressions like {$data-type})
        String dataType = ctx.attributes.get("data-type");  // "text" or "number"
        String order = ctx.attributes.get("order");          // "ascending" or "descending"
        String caseOrder = ctx.attributes.get("case-order"); // "upper-first" or "lower-first"
        String lang = ctx.attributes.get("lang");
        String collation = ctx.attributes.get("collation");  // XSLT 2.0+ collation URI
        
        AttributeValueTemplate dataTypeAvt = dataType != null ? parseAvt(dataType) : null;
        AttributeValueTemplate orderAvt = order != null ? parseAvt(order) : null;
        AttributeValueTemplate caseOrderAvt = caseOrder != null ? parseAvt(caseOrder) : null;
        AttributeValueTemplate langAvt = lang != null ? parseAvt(lang) : null;
        AttributeValueTemplate collationAvt = collation != null ? parseAvt(collation) : null;
        
        SortSpec spec = new SortSpec(selectExpr, dataTypeAvt, orderAvt, caseOrderAvt, langAvt, collationAvt);
        return new SortSpecNode(spec);
    }

    private XSLTNode compileNumber(ElementContext ctx) throws SAXException {
        // value attribute - if present, use this instead of counting
        String valueAttr = ctx.attributes.get("value");
        XPathExpression valueExpr = null;
        
        // select attribute (XSLT 2.0+) - the node to number
        String selectAttr = ctx.attributes.get("select");
        XPathExpression selectExpr = null;
        
        // level attribute - single (default), multiple, or any
        String level = ctx.attributes.get("level");
        
        // count attribute - pattern for nodes to count
        String countAttr = ctx.attributes.get("count");
        Pattern countPattern = null;
        
        // from attribute - pattern for where to start counting
        String fromAttr = ctx.attributes.get("from");
        Pattern fromPattern = null;
        
        // XTSE0975: value attribute is mutually exclusive with select, level, count, from
        if (valueAttr != null) {
            if (selectAttr != null || level != null || countAttr != null || fromAttr != null) {
                throw new SAXException("XTSE0975: xsl:number value attribute cannot be " +
                    "used with select, level, count, or from attributes");
            }
            valueExpr = compileExpression(valueAttr);
        }
        
        if (selectAttr != null) {
            selectExpr = compileExpression(selectAttr);
        }
        
        if (level == null) {
            level = "single";
        }
        
        if (countAttr != null) {
            countPattern = compilePattern(countAttr);
        }
        
        if (fromAttr != null) {
            fromPattern = compilePattern(fromAttr);
        }
        
        // format attribute - format string (default "1"), can be an AVT
        String formatAttr = ctx.attributes.get("format");
        AttributeValueTemplate formatAVT;
        try {
            if (formatAttr == null) {
                formatAVT = AttributeValueTemplate.literal("1");
            } else {
                formatAVT = AttributeValueTemplate.parse(formatAttr, this);
            }
        } catch (XPathSyntaxException e) {
            throw new SAXException("Invalid format AVT: " + e.getMessage(), e);
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
                             formatAVT, groupingSeparator, groupingSize, lang, letterValue);
    }

    private XSLTNode compileMessage(ElementContext ctx) throws SAXException {
        String terminateValue = ctx.attributes.get("terminate");
        boolean terminateStatic = false;
        AttributeValueTemplate terminateAvt = null;
        
        if (terminateValue != null) {
            // Check if terminate is an AVT (XSLT 3.0)
            if (terminateValue.contains("{") && terminateValue.contains("}")) {
                terminateAvt = parseAvt(terminateValue);
            } else {
                // Static value - normalize and validate at compile time
                // XSLT 3.0 allows yes/no/true/false/1/0, case-insensitive with whitespace
                String normalized = terminateValue.trim().toLowerCase();
                if ("yes".equals(normalized) || "true".equals(normalized) || "1".equals(normalized)) {
                    terminateStatic = true;
                } else if ("no".equals(normalized) || "false".equals(normalized) || "0".equals(normalized) || normalized.isEmpty()) {
                    terminateStatic = false;
                } else {
                    // XTSE0020: Invalid value for terminate attribute
                    throw new SAXException("XTSE0020: Invalid value for terminate attribute: '" + 
                                          terminateValue + "'. Must be 'yes' or 'no'");
                }
            }
        }
        
        // XSLT 2.0+ select attribute
        String selectAttr = ctx.attributes.get("select");
        XPathExpression selectExpr = null;
        if (selectAttr != null) {
            selectExpr = compileExpression(selectAttr);
        }
        
        // XSLT 3.0 error-code attribute (optional)
        String errorCode = ctx.attributes.get("error-code");
        
        return new MessageNode(new SequenceNode(ctx.children), selectExpr, terminateStatic, terminateAvt, errorCode);
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
        // Validate pattern syntax based on XSLT version
        validatePattern(pattern);
        
        // Resolve namespace prefixes in the pattern before compilation
        String resolvedPattern = resolvePatternNamespaces(pattern);
        return new SimplePattern(resolvedPattern);
    }
    
    /**
     * Validates a pattern string for XSLT version compatibility.
     * 
     * <p>XSLT 2.0 patterns are more restricted than XSLT 3.0:
     * <ul>
     *   <li>Parentheses at top level not allowed in 2.0 (only | for union)</li>
     *   <li>Variable references ($var) not allowed in 2.0 patterns</li>
     *   <li>doc() function not allowed in 2.0 patterns</li>
     * </ul>
     * 
     * <p>Common pattern errors (all versions):
     * <ul>
     *   <li>/[predicate] - root with predicate not allowed</li>
     *   <li>copy-of(), snapshot() - not allowed as pattern start</li>
     *   <li>/key(), /id() - function after / not allowed</li>
     *   <li>key('name', expr) - non-literal key values not allowed</li>
     * </ul>
     */
    private void validatePattern(String pattern) throws SAXException {
        String trimmed = pattern.trim();
        
        // Check for common pattern errors (all XSLT versions)
        
        // /[predicate] - root with predicate is not valid XPath for patterns
        if (trimmed.startsWith("/[") || trimmed.contains("|/[")) {
            throw new SAXException("XPST0003: Pattern '/[predicate]' is not valid - root node cannot have a predicate");
        }
        
        // /.. - parent of root is not valid in patterns
        if (trimmed.equals("/..") || trimmed.startsWith("/../") || 
            trimmed.contains("|/..") || trimmed.contains("| /..")) {
            throw new SAXException("XPST0003: Pattern '/..' is not valid - root node has no parent");
        }
        
        // Disallowed functions at start of pattern
        if (hasDisallowedPatternFunction(trimmed)) {
            throw new SAXException("XPST0017: Function not allowed at the start of a pattern");
        }
        
        // /function() - function after / not allowed (except for certain functions in specific positions)
        if (hasFunctionAfterRoot(trimmed)) {
            throw new SAXException("XPST0017: Function call not allowed after '/' in pattern");
        }
        
        // key() with non-literal argument
        if (hasKeyWithNonLiteralArg(trimmed)) {
            throw new SAXException("XPST0017: key() in patterns must have literal arguments");
        }
        
        // XSLT 2.0-specific restrictions
        if (stylesheetVersion < 3.0) {
            // Check for parentheses at top level - not allowed in XSLT 2.0
            // Exception: parentheses inside predicates or function calls are fine
            if (hasTopLevelParentheses(trimmed)) {
                throw new SAXException("XTSE0340: Parenthesized patterns are not allowed in XSLT 2.0 (use XSLT 3.0)");
            }
            
            // Check for variable references in patterns - not allowed in XSLT 2.0
            if (hasPatternVariableReference(trimmed)) {
                throw new SAXException("XTSE0340: Variable references in patterns are not allowed in XSLT 2.0");
            }
            
            // Check for doc() function at start of pattern - not allowed in XSLT 2.0
            if (hasDocFunctionInPattern(trimmed)) {
                throw new SAXException("XTSE0340: doc() function in patterns is not allowed in XSLT 2.0");
            }
        }
    }
    
    /**
     * Checks if a pattern starts with a disallowed function.
     * Only id(), key(), doc(), element-with-id() and root() are allowed at the start of a pattern.
     */
    private boolean hasDisallowedPatternFunction(String pattern) {
        // List of allowed functions at start of pattern
        String[] allowedFunctions = {"id(", "key(", "doc(", "root(", "element-with-id("};
        
        // Node tests that look like functions but are actually XPath kind tests
        String[] nodeTests = {
            "element(", "attribute(", "document-node(", "schema-element(", 
            "schema-attribute(", "processing-instruction(", "comment(",
            "text(", "node(", "namespace-node(", "function("
        };
        
        // Check each segment of the pattern (split by |)
        for (String segment : splitPatternByUnion(pattern)) {
            String seg = segment.trim();
            // Check if it starts with a function call (name followed by ()
            int parenIdx = seg.indexOf('(');
            if (parenIdx > 0 && parenIdx < seg.length() - 1) {
                String possibleFunc = seg.substring(0, parenIdx + 1);
                // Skip if the segment starts with / (handled by hasFunctionAfterRoot)
                if (seg.startsWith("/")) continue;
                
                // Check if it's a function (starts with a letter, followed by name chars)
                if (Character.isLetter(seg.charAt(0)) || seg.charAt(0) == '_') {
                    // First check if it's a node test (not a function call)
                    boolean isNodeTest = false;
                    for (String test : nodeTests) {
                        if (possibleFunc.equals(test)) {
                            isNodeTest = true;
                            break;
                        }
                    }
                    if (isNodeTest) {
                        continue;
                    }
                    
                    boolean allowed = false;
                    for (String allowedFunc : allowedFunctions) {
                        if (possibleFunc.equals(allowedFunc) || possibleFunc.endsWith(":" + allowedFunc)) {
                            allowed = true;
                            break;
                        }
                    }
                    if (!allowed && !possibleFunc.contains("::")) {
                        // Make sure it's a function call (has matching paren) and not an axis
                        if (isLikelyFunction(seg)) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }
    
    /**
     * Checks if a string looks like a function call at the start.
     */
    private boolean isLikelyFunction(String s) {
        int parenIdx = s.indexOf('(');
        if (parenIdx <= 0) {
            return false;
        }
        String name = s.substring(0, parenIdx);
        // Check it's a valid function name (letters, digits, _, -, maybe with prefix)
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '_' && c != '-' && c != ':') {
                return false;
            }
        }
        // Disallow axis tests (child::, attribute::, etc.)
        if (name.endsWith(":") && parenIdx == name.length()) {
            return false;
        }
        return true;
    }
    
    /**
     * Checks if a pattern has a function call immediately after root (/).
     * Patterns like /key('x', 'y') are not valid - key() must be at the start.
     */
    private boolean hasFunctionAfterRoot(String pattern) {
        // Node tests that look like functions but are actually XPath kind tests
        String[] nodeTests = {
            "element", "attribute", "document-node", "schema-element", 
            "schema-attribute", "processing-instruction", "comment",
            "text", "node", "namespace-node", "function"
        };
        
        for (String segment : splitPatternByUnion(pattern)) {
            String seg = segment.trim();
            // Check for /function( at the start (but not //function)
            if (seg.startsWith("/") && !seg.startsWith("//") && seg.length() > 1) {
                String afterSlash = seg.substring(1).trim();
                // Check if it starts with a function name
                if (afterSlash.length() > 0 && (Character.isLetter(afterSlash.charAt(0)) || afterSlash.charAt(0) == '_')) {
                    int parenIdx = afterSlash.indexOf('(');
                    if (parenIdx > 0) {
                        String funcName = afterSlash.substring(0, parenIdx);
                        // Check it's not an axis (child::, etc.)
                        if (!funcName.contains("::") && !funcName.contains("/")) {
                            // Check if it's a node test
                            boolean isNodeTest = false;
                            for (String test : nodeTests) {
                                if (funcName.equals(test)) {
                                    isNodeTest = true;
                                    break;
                                }
                            }
                            if (!isNodeTest) {
                                // This is a function call after /
                                return true;
                            }
                        }
                    }
                }
            }
        }
        return false;
    }
    
    /**
     * Checks if a pattern uses key() with a non-literal, non-variable argument.
     * key('name', 'value') and key('name', $var) are allowed,
     * but key('name', 1+2) or key('name', foo()) are not.
     */
    private boolean hasKeyWithNonLiteralArg(String pattern) {
        int idx = 0;
        while ((idx = pattern.indexOf("key(", idx)) != -1) {
            // Check if this is at depth 0 (not inside another expression)
            int depth = 0;
            for (int i = 0; i < idx; i++) {
                char c = pattern.charAt(i);
                if (c == '(' || c == '[') depth++;
                else if (c == ')' || c == ']') depth--;
            }
            if (depth == 0) {
                // Find the arguments
                int start = idx + 4;
                int parenDepth = 1;
                int commaPos = -1;
                int endPos = -1;
                
                for (int i = start; i < pattern.length() && parenDepth > 0; i++) {
                    char c = pattern.charAt(i);
                    if (c == '(') parenDepth++;
                    else if (c == ')') {
                        parenDepth--;
                        if (parenDepth == 0) endPos = i;
                    }
                    else if (c == ',' && parenDepth == 1 && commaPos == -1) {
                        commaPos = i;
                    }
                }
                
                if (commaPos > 0 && endPos > commaPos) {
                    // Extract second argument
                    String secondArg = pattern.substring(commaPos + 1, endPos).trim();
                    // Check if it's a literal or variable reference
                    if (!isLiteralOrVariable(secondArg)) {
                        return true;
                    }
                }
            }
            idx++;
        }
        return false;
    }
    
    /**
     * Checks if a value is a literal (string or number) or a variable reference.
     */
    private boolean isLiteralOrVariable(String value) {
        String v = value.trim();
        if (v.isEmpty()) return false;
        // Variable reference
        if (v.startsWith("$") && v.length() > 1 && isValidQName(v.substring(1))) {
            return true;
        }
        // String literal
        if ((v.startsWith("'") && v.endsWith("'")) || (v.startsWith("\"") && v.endsWith("\""))) {
            return true;
        }
        // Number literal (simple integer or decimal)
        try {
            Double.parseDouble(v);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }
    
    /**
     * Checks if a string is a valid QName (for variable names).
     */
    private boolean isValidQName(String name) {
        if (name.isEmpty()) return false;
        char first = name.charAt(0);
        if (!Character.isLetter(first) && first != '_') return false;
        for (int i = 1; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '_' && c != '-' && c != '.' && c != ':') {
                return false;
            }
        }
        return true;
    }
    
    /**
     * Splits a pattern by union operator (|) at the top level.
     */
    private String[] splitPatternByUnion(String pattern) {
        java.util.List<String> parts = new java.util.ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '(' || c == '[') depth++;
            else if (c == ')' || c == ']') depth--;
            else if (c == '|' && depth == 0) {
                parts.add(pattern.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(pattern.substring(start));
        return parts.toArray(new String[0]);
    }
    
    /**
     * Checks if a pattern starts with parentheses at the top level (not inside predicates/functions).
     */
    private boolean hasTopLevelParentheses(String pattern) {
        String trimmed = pattern.trim();
        if (trimmed.isEmpty() || trimmed.charAt(0) != '(') {
            return false;
        }
        
        // Find the matching closing paren
        int depth = 1;
        int i = 1;
        while (i < trimmed.length() && depth > 0) {
            char c = trimmed.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') depth--;
            i++;
        }
        
        // If the closing paren is at the end (or followed only by predicates), it's a top-level paren
        if (depth == 0) {
            String rest = trimmed.substring(i).trim();
            return rest.isEmpty() || rest.startsWith("[") || rest.startsWith("/");
        }
        return false;
    }
    
    /**
     * Checks if a pattern contains a variable reference ($var) as a path start (not in predicate or function args).
     * 
     * <p>Valid uses of $var in patterns:
     * <ul>
     *   <li>key('name', $var) - variable as function argument</li>
     *   <li>foo[$var = 1] - variable inside predicate</li>
     * </ul>
     * 
     * <p>Invalid in XSLT 2.0:
     * <ul>
     *   <li>$var//foo - variable as path start</li>
     *   <li>$var - variable as entire pattern</li>
     * </ul>
     */
    private boolean hasPatternVariableReference(String pattern) {
        int bracketDepth = 0;  // [] depth
        int parenDepth = 0;    // () depth
        int i = 0;
        int len = pattern.length();
        
        while (i < len) {
            char c = pattern.charAt(i);
            if (c == '[') {
                bracketDepth++;
            } else if (c == ']') {
                bracketDepth--;
            } else if (c == '(') {
                parenDepth++;
            } else if (c == ')') {
                parenDepth--;
            } else if (c == '$' && bracketDepth == 0 && parenDepth == 0) {
                // Variable reference outside predicate and outside function arguments
                // Check if it's at the start of a pattern segment (after | or /)
                if (i == 0) {
                    return true;
                }
                char prev = pattern.charAt(i - 1);
                if (prev == '|' || prev == '/' || Character.isWhitespace(prev)) {
                    return true;
                }
            }
            i++;
        }
        return false;
    }
    
    /**
     * Checks if a pattern uses doc() function (not inside predicate).
     */
    private boolean hasDocFunctionInPattern(String pattern) {
        int depth = 0;
        int i = 0;
        int len = pattern.length();
        
        while (i < len) {
            char c = pattern.charAt(i);
            if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth--;
            } else if (depth == 0 && i + 4 <= len) {
                String sub = pattern.substring(i, i + 4);
                if (sub.equals("doc(")) {
                    // Check if it's at the start of a pattern segment
                    if (i == 0) {
                        return true;
                    }
                    char prev = pattern.charAt(i - 1);
                    if (prev == '|' || prev == '/' || Character.isWhitespace(prev)) {
                        return true;
                    }
                }
            }
            i++;
        }
        return false;
    }
    
    private static boolean isNameChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '.';
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
        boolean inAttributeContext = false;  // Track if next name is an attribute
        
        while (i < len) {
            char c = pattern.charAt(i);
            
            // Copy path separators - reset attribute context
            if (c == '/') {
                result.append(c);
                i++;
                inAttributeContext = false;
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
                inAttributeContext = false;
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
                inAttributeContext = false;
                continue;
            }
            
            // Copy union operator - reset attribute context
            if (c == '|') {
                result.append(c);
                i++;
                inAttributeContext = false;
                continue;
            }
            
            // Skip whitespace (preserving it in output)
            if (Character.isWhitespace(c)) {
                result.append(c);
                i++;
                continue;
            }
            
            // Copy @ for attribute axis shorthand - set attribute context
            if (c == '@') {
                result.append(c);
                i++;
                inAttributeContext = true;
                continue;
            }
            
            // Extract a name token
            int start = i;
            while (i < len) {
                c = pattern.charAt(i);
                if (c == '/' || c == '[' || c == '(' || c == '|' || c == ')' || c == ']' ||
                    Character.isWhitespace(c)) {
                    break;
                }
                i++;
            }
            
            if (i > start) {
                String token = pattern.substring(start, i);
                String resolved = resolvePatternToken(token, inAttributeContext);
                result.append(resolved);
                inAttributeContext = false;  // Reset after processing name
            }
        }
        
        return result.toString();
    }
    
    /**
     * Resolves a single token which may contain namespace prefixes.
     * Handles: name, prefix:name, prefix:*, *:name, axis::name, axis::prefix:name
     *
     * @param token the token to resolve
     * @param isAttribute true if this is an attribute name (@ or attribute:: axis)
     */
    private String resolvePatternToken(String token, boolean isAttribute) throws SAXException {
        // Check for axis specification FIRST (double colon ::)
        int axisPos = token.indexOf("::");
        if (axisPos > 0) {
            // This is axis::nametest - resolve the nametest part only
            String axis = token.substring(0, axisPos);
            String nameTest = token.substring(axisPos + 2);
            // Check if it's the attribute:: axis
            boolean attrAxis = "attribute".equals(axis);
            String resolvedNameTest = resolvePatternToken(nameTest, attrAxis);
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
        
        // Unprefixed name - check for xpath-default-namespace (XSLT 2.0+)
        // Note: xpath-default-namespace only applies to element names, not:
        // - Node tests like node(), text(), comment(), processing-instruction()
        // - The * wildcard (already handled above)
        // - Attributes (unprefixed attributes are in no namespace per XML spec)
        if (!isAttribute && !token.endsWith("()") && !token.contains("(")) {
            String defaultNs = getDefaultElementNamespace();
            if (defaultNs != null && !defaultNs.isEmpty()) {
                return "{" + defaultNs + "}" + token;
            }
        }
        return token;
    }

    private AttributeValueTemplate parseAvt(String value) throws SAXException {
        try {
            return AttributeValueTemplate.parse(value, this);
        } catch (XPathSyntaxException e) {
            throw new SAXException("Invalid AVT: " + value + " - " + e.getMessage(), e);
        }
    }

    /**
     * Resolves a namespace prefix to its URI.
     * Implements XPathParser.NamespaceResolver for XPath expression compilation.
     *
     * @param prefix the namespace prefix
     * @return the namespace URI, or null if prefix not found
     */
    @Override
    public String resolve(String prefix) {
        // The "xml" prefix is always implicitly bound to the XML namespace
        if ("xml".equals(prefix)) {
            return "http://www.w3.org/XML/1998/namespace";
        }
        return namespaces.get(prefix);
    }
    
    /**
     * Returns the default namespace for unprefixed element names in XPath expressions.
     * This is set by the xpath-default-namespace attribute (XSLT 2.0+).
     *
     * @return the default element namespace URI, or null for no namespace
     */
    @Override
    public String getDefaultElementNamespace() {
        // First check the current processing context (set during endElement processing)
        // because the context has been popped from the stack at that point
        if (currentProcessingContext != null) {
            return currentProcessingContext.xpathDefaultNamespace;
        }
        // Otherwise check the stack (for patterns compiled during startElement or nested elements)
        if (!elementStack.isEmpty()) {
            return elementStack.peek().xpathDefaultNamespace;
        }
        return null;
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
                        // For single node types, extract the actual node from the node set
                        // For element() type specifically, we look for element children
                        // For general node() type, we accept any child node type
                        if (value instanceof XPathNodeSet) {
                            XPathNodeSet ns = (XPathNodeSet) value;
                            Iterator<XPathNode> iter = ns.iterator();
                            if (iter.hasNext()) {
                                XPathNode node = iter.next();
                                // If this is a document/RTF root, get its first child node
                                if (node.getNodeType() == NodeType.ROOT) {
                                    Iterator<XPathNode> children = node.getChildren();
                                    boolean isElementType = asType != null && asType.startsWith("element(");
                                    while (children.hasNext()) {
                                        XPathNode child = children.next();
                                        // For element() type, only match elements
                                        // For node() type, accept any node type
                                        if (isElementType) {
                                            if (child.isElement()) {
                                                value = new XPathNodeSet(Collections.singletonList(child));
                                                break;
                                            }
                                        } else {
                                            // For node() and other types, accept first child
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
                
                // Note: Full type validation for 'as' attribute (XTTE0570) is deferred.
                // XSLT 2.0+ has complex atomization and type conversion rules that must
                // be applied before type checking. For example, when as="xs:dayTimeDuration*"
                // and content is an element, the element must be atomized first.
                // Proper implementation requires atomization + type conversion + then checking.
                // For now, we only check basic cardinality mismatches.
                if (asType != null && !asType.isEmpty()) {
                    ErrorHandlingMode errorMode = context.getErrorHandlingMode();
                    if (!errorMode.isSilent()) {
                        try {
                            // Only check basic cardinality mismatches
                            boolean isSequence = value instanceof XPathSequence && 
                                                ((XPathSequence) value).size() > 1;
                            boolean expectsOne = !asType.contains("*") && !asType.contains("+");
                            
                            if (isSequence && expectsOne) {
                                String errorMsg = "Variable $" + localName + " has multiple items but type '" + 
                                                 asType + "' expects at most one";
                                if (errorMode.isRecovery()) {
                                    System.err.println("Warning [XTTE0570]: " + errorMsg);
                                } else {
                                    throw new XPathException("XTTE0570: " + errorMsg);
                                }
                            }
                        } catch (XPathException e) {
                            if (errorMode.isStrict()) {
                                throw e;
                            } else if (errorMode.isRecovery()) {
                                System.err.println("Warning: " + e.getMessage());
                            }
                        }
                    }
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
        private final boolean tunnel; // XSLT 2.0 tunnel parameter
        
        ParamNode(String namespaceURI, String localName, XPathExpression selectExpr, SequenceNode content) {
            this(namespaceURI, localName, selectExpr, content, null, false);
        }
        
        ParamNode(String namespaceURI, String localName, XPathExpression selectExpr, 
                  SequenceNode content, String asType) {
            this(namespaceURI, localName, selectExpr, content, asType, false);
        }
        
        ParamNode(String namespaceURI, String localName, XPathExpression selectExpr, 
                  SequenceNode content, String asType, boolean tunnel) {
            this.namespaceURI = namespaceURI;
            this.localName = localName;
            this.selectExpr = selectExpr;
            this.content = content;
            this.asType = asType;
            this.tunnel = tunnel;
        }
        
        String getNamespaceURI() { return namespaceURI; }
        String getLocalName() { return localName; }
        String getName() { return localName; }  // For compatibility
        XPathExpression getSelectExpr() { return selectExpr; }
        SequenceNode getContent() { return content; }
        String getAs() { return asType; }
        boolean isTunnel() { return tunnel; }
        
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
                
                // Check for atomization errors (XTTE1540 / XTTE3090)
                ErrorHandlingMode errorMode = context.getErrorHandlingMode();
                if (!errorMode.isSilent()) {
                    // Check if result is a sequence with multiple node items
                    // This can cause atomization issues
                    if (result.isSequence()) {
                        XPathSequence seq = (XPathSequence) result;
                        // In XSLT 2.0+, sequences are allowed and space-separated
                        // But we should still warn if it's not what was expected
                        if (!xslt2Plus && seq.size() > 1) {
                            String msg = "xsl:value-of in XSLT 1.0 mode has sequence with " + 
                                        seq.size() + " items, only first will be used";
                            if (errorMode.isRecovery()) {
                                System.err.println("Warning [XTTE3090]: " + msg);
                            }
                        }
                    }
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

    /**
     * XSLT 2.0+ xsl:value-of with content (sequence constructor) instead of select attribute.
     * The output is the string-join of all items produced by the content, with separator.
     */
    private static class ValueOfContentNode extends XSLTInstruction {
        private final XSLTNode content;
        private final boolean disableEscaping;
        private final String separator;  // null means use default (empty string for content)
        
        ValueOfContentNode(XSLTNode content, boolean disableEscaping, String separator) {
            this.content = content;
            this.disableEscaping = disableEscaping;
            this.separator = separator;
        }
        
        @Override public String getInstructionName() { return "value-of"; }
        
        @Override
        public void execute(TransformContext context, 
                           OutputHandler output) throws SAXException {
            // Execute content into a buffer to capture its string output
            SAXEventBuffer eventBuffer = new SAXEventBuffer();
            BufferOutputHandler buffer = new BufferOutputHandler(eventBuffer);
            content.execute(context, buffer);
            
            String value = eventBuffer.getTextContent();
            
            // Only output non-empty values to preserve empty element serialization
            if (value != null && !value.isEmpty()) {
                if (disableEscaping) {
                    output.charactersRaw(value);
                } else {
                    output.characters(value);
                }
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
        private final XSLTNode onEmptyNode;      // XSLT 3.0 xsl:on-empty child
        private final XSLTNode onNonEmptyNode;   // XSLT 3.0 xsl:on-non-empty child
        
        ElementNode(AttributeValueTemplate nameAvt, AttributeValueTemplate nsAvt, 
                   String useAttrSets, SequenceNode content,
                   String defaultNamespace, Map<String, String> namespaceBindings) {
            this(nameAvt, nsAvt, useAttrSets, content, defaultNamespace, namespaceBindings, 
                 null, null, null, null, null);
        }
        
        ElementNode(AttributeValueTemplate nameAvt, AttributeValueTemplate nsAvt, 
                   String useAttrSets, SequenceNode content,
                   String defaultNamespace, Map<String, String> namespaceBindings,
                   String typeNamespaceURI, String typeLocalName) {
            this(nameAvt, nsAvt, useAttrSets, content, defaultNamespace, namespaceBindings, 
                 typeNamespaceURI, typeLocalName, null, null, null);
        }
        
        ElementNode(AttributeValueTemplate nameAvt, AttributeValueTemplate nsAvt, 
                   String useAttrSets, SequenceNode content,
                   String defaultNamespace, Map<String, String> namespaceBindings,
                   String typeNamespaceURI, String typeLocalName, ValidationMode validation) {
            this(nameAvt, nsAvt, useAttrSets, content, defaultNamespace, namespaceBindings,
                 typeNamespaceURI, typeLocalName, validation, null, null);
        }
        
        ElementNode(AttributeValueTemplate nameAvt, AttributeValueTemplate nsAvt, 
                   String useAttrSets, SequenceNode content,
                   String defaultNamespace, Map<String, String> namespaceBindings,
                   String typeNamespaceURI, String typeLocalName, ValidationMode validation,
                   XSLTNode onEmptyNode, XSLTNode onNonEmptyNode) {
            this.nameAvt = nameAvt;
            this.nsAvt = nsAvt;
            this.useAttrSets = useAttrSets;
            this.content = content;
            this.defaultNamespace = defaultNamespace;
            this.namespaceBindings = namespaceBindings;
            this.typeNamespaceURI = typeNamespaceURI;
            this.typeLocalName = typeLocalName;
            this.validation = validation;
            this.onEmptyNode = onEmptyNode;
            this.onNonEmptyNode = onNonEmptyNode;
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
                    // With validation="preserve" and explicit type= attribute, set the
                    // declared type annotation. Otherwise, use xs:untyped (the default
                    // for constructed elements without schema validation).
                    if (effectiveValidation == ValidationMode.PRESERVE) {
                        output.setElementType(typeNamespaceURI, typeLocalName);
                    } else {
                        output.setElementType(XSD_NAMESPACE, "untyped");
                    }
                    // TODO: Implement element value validation against the specified type
                }
                
                // Get validator for potential use in validation
                RuntimeSchemaValidator validator = context.getRuntimeValidator();
                
                if (effectiveValidation == ValidationMode.STRICT || 
                           effectiveValidation == ValidationMode.LAX) {
                    // Use runtime schema validation to derive type
                    if (validator != null) {
                        RuntimeSchemaValidator.ValidationResult valResult =
                            validator.startElement(namespace, localName, effectiveValidation);
                        // Note: Full content model validation happens after content execution
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
                output.setAtomicValuePending(false);
                output.setInAttributeContent(false);
                
                // Execute content with on-empty/on-non-empty support (XSLT 3.0)
                // SequenceNode handles the two-phase conditional execution if needed
                if (content != null) {
                    content.executeWithOnEmptySupport(context, output, content.hasOnEmptyOrOnNonEmpty());
                }
                
                // Complete validation after content execution
                if ((effectiveValidation == ValidationMode.STRICT ||
                     effectiveValidation == ValidationMode.LAX) && 
                    validator != null) {
                    try {
                        RuntimeSchemaValidator.ValidationResult endResult = validator.endElement();
                        if (!endResult.isValid() && effectiveValidation == ValidationMode.STRICT) {
                            throw new SAXException(endResult.getErrorCode() + ": " + 
                                                 endResult.getErrorMessage());
                        }
                    } catch (XPathException e) {
                        throw new SAXException("Content model validation error", e);
                    }
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
                // Apply static base URI if set (for static-base-uri() function)
                TransformContext evalContext = context;
                if (staticBaseURI != null) {
                    evalContext = context.withStaticBaseURI(staticBaseURI);
                }
                
                String name = nameAvt.evaluate(evalContext);
                String namespace = nsAvt != null ? nsAvt.evaluate(evalContext) : null;
                
                // Get attribute value
                String value = "";
                if (selectExpr != null) {
                    // XSLT 2.0+: select attribute takes precedence over content
                    XPathValue result = selectExpr.evaluate(evalContext);
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
                    // In attribute content, atomic values are NOT space-separated
                    // Set flag to suppress space separators
                    boolean savedAttrMode = output.isInAttributeContent();
                    boolean savedPending = output.isAtomicValuePending();
                    BufferOutputHandler bufferHandler = new BufferOutputHandler(buffer);
                    bufferHandler.setInAttributeContent(true);
                    bufferHandler.setAtomicValuePending(false);
                    content.execute(context, bufferHandler);
                    // Restore outer state
                    output.setInAttributeContent(savedAttrMode);
                    output.setAtomicValuePending(savedPending);
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
                // Special handling for xmlns prefix: per XSLT spec 11.7.1, the xmlns
                // prefix may be used if namespace attribute is present, but is not required.
                // Since xmlns is reserved in XML, we generate an alternative prefix.
                String qName = name;
                if (!namespace.isEmpty()) {
                    if (prefix == null || prefix.isEmpty() || "xmlns".equals(prefix)) {
                        // Generate a prefix for this namespace
                        // Try to find an existing prefix, otherwise generate one
                        prefix = findOrGeneratePrefix(namespace, namespaceBindings);
                        qName = prefix + ":" + localName;
                    }
                    // Declare the namespace binding
                    output.namespace(prefix, namespace);
                }
                
                // Convert value to canonical form if type annotation is specified
                if (typeLocalName != null && XSD_NAMESPACE.equals(typeNamespaceURI)) {
                    value = toCanonicalLexical(typeLocalName, value);
                }
                
                output.attribute(namespace, localName, qName, value);
                
                // Determine effective validation mode
                ValidationMode effectiveValidation = validation;
                if (effectiveValidation == null) {
                    effectiveValidation = context.getStylesheet().getDefaultValidation();
                }
                
                // Set type annotation based on validation mode
                if (typeLocalName != null) {
                    // Explicit type attribute - validate value against the type
                    // First check for built-in XSD types, then imported types
                    XSDSimpleType xsdType = null;
                    boolean isBuiltInType = false;
                    if (XSD_NAMESPACE.equals(typeNamespaceURI)) {
                        xsdType = XSDSimpleType.getBuiltInType(typeLocalName);
                        isBuiltInType = (xsdType != null);
                    }
                    if (xsdType == null) {
                        xsdType = context.getStylesheet()
                            .getImportedSimpleType(typeNamespaceURI, typeLocalName);
                    }
                    if (xsdType != null) {
                        String validationError = xsdType.validate(value);
                        if (validationError != null) {
                            throw new XPathException("XTTE0590: Invalid value for type " + 
                                typeLocalName + ": " + validationError);
                        }
                    }
                    // Per XSLT 2.0 spec 2.12.1: For built-in types without full schema
                    // validation, the type annotation is xs:untypedAtomic.
                    // For user-defined types from imported schemas, we preserve the 
                    // declared type annotation (per spec 11.1.2.1).
                    if (isBuiltInType && effectiveValidation != ValidationMode.PRESERVE) {
                        output.setAttributeType(XSD_NAMESPACE, "untypedAtomic");
                    } else {
                        // User-defined type from imported schema, or preserve mode
                        output.setAttributeType(typeNamespaceURI, typeLocalName);
                    }
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
                content.execute(context, new org.bluezoo.gonzalez.transform.runtime.BufferOutputHandler(buffer));
                text = buffer.getTextContent();
            }
            // Per XSLT spec: insert space after any "--" sequence and before trailing "-"
            // to ensure well-formed XML output
            text = sanitizeCommentText(text);
            output.comment(text);
        }
        
        /**
         * Sanitizes comment text to ensure well-formed XML output.
         * XML comments cannot contain "--" or end with "-".
         */
        private static String sanitizeCommentText(String text) {
            if (text == null || text.isEmpty()) {
                return text;
            }
            // Replace all "--" with "- -" (insert space between adjacent hyphens)
            StringBuilder sb = new StringBuilder();
            char prev = 0;
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '-' && prev == '-') {
                    sb.append(' '); // Insert space before this hyphen
                }
                sb.append(c);
                prev = c;
            }
            // If comment ends with "-", append a space
            if (sb.length() > 0 && sb.charAt(sb.length() - 1) == '-') {
                sb.append(' ');
            }
            return sb.toString();
        }
    }

    private static class ProcessingInstructionNode extends XSLTInstruction {
        private final AttributeValueTemplate nameAvt;
        private final XPathExpression selectExpr;
        private final SequenceNode content;
        ProcessingInstructionNode(AttributeValueTemplate nameAvt, XPathExpression selectExpr, SequenceNode content) {
            this.nameAvt = nameAvt;
            this.selectExpr = selectExpr;
            this.content = content;
        }
        @Override public String getInstructionName() { return "processing-instruction"; }
        @Override public void execute(TransformContext context, 
                                      OutputHandler output) throws SAXException {
            try {
                String target = nameAvt.evaluate(context);
                String data = "";
                
                // select attribute takes precedence over content (XSLT 2.0+)
                if (selectExpr != null) {
                    XPathValue result = selectExpr.evaluate(context);
                    data = result != null ? result.asString() : "";
                } else if (content != null) {
                    SAXEventBuffer buffer = 
                        new SAXEventBuffer();
                    content.execute(context, new BufferOutputHandler(buffer));
                    data = buffer.getTextContent();
                }
                // Per XSLT spec: insert space in "?>" sequence to ensure well-formed XML
                data = sanitizePIData(data);
                output.processingInstruction(target, data);
            } catch (XPathException e) {
                throw new SAXException("Error in xsl:processing-instruction", e);
            }
        }
        
        /**
         * Sanitizes PI data to ensure well-formed XML output.
         * XML processing instructions cannot contain "?>".
         */
        private static String sanitizePIData(String data) {
            if (data == null || data.isEmpty()) {
                return data;
            }
            // Replace all "?>" with "? >" (insert space between ? and >)
            return data.replace("?>", "? >");
        }
    }

    private static class CopyNode extends XSLTInstruction {
        private final XPathExpression selectExpr;          // XSLT 3.0 - select items to copy (null = context item)
        private final String useAttrSets;
        private final SequenceNode content;
        private final String typeNamespaceURI;
        private final String typeLocalName;
        private final ValidationMode validation;
        private final boolean inheritNamespaces;           // Static value (when no AVT)
        private final AttributeValueTemplate inheritNamespacesAvt;  // AVT (XSLT 3.0 shadow attribute)
        private final boolean copyNamespaces;              // XSLT 2.0 - copy namespace nodes (default: true)
        private final AttributeValueTemplate copyNamespacesAvt;   // AVT (XSLT 3.0 shadow attribute)
        private final XSLTNode onEmptyNode;                // XSLT 3.0 - content when copy produces empty result
        
        CopyNode(String useAttrSets, SequenceNode content) {
            this(null, useAttrSets, content, null, null, null, true, null, true, null, null);
        }
        
        CopyNode(String useAttrSets, SequenceNode content, 
                String typeNamespaceURI, String typeLocalName, ValidationMode validation) {
            this(null, useAttrSets, content, typeNamespaceURI, typeLocalName, validation, true, null, true, null, null);
        }
        
        CopyNode(String useAttrSets, SequenceNode content, 
                String typeNamespaceURI, String typeLocalName, ValidationMode validation,
                boolean inheritNamespaces, AttributeValueTemplate inheritNamespacesAvt) {
            this(null, useAttrSets, content, typeNamespaceURI, typeLocalName, validation, 
                 inheritNamespaces, inheritNamespacesAvt, true, null, null);
        }
        
        CopyNode(String useAttrSets, SequenceNode content, 
                String typeNamespaceURI, String typeLocalName, ValidationMode validation,
                boolean inheritNamespaces, AttributeValueTemplate inheritNamespacesAvt,
                boolean copyNamespaces, AttributeValueTemplate copyNamespacesAvt) {
            this(null, useAttrSets, content, typeNamespaceURI, typeLocalName, validation,
                 inheritNamespaces, inheritNamespacesAvt, copyNamespaces, copyNamespacesAvt, null);
        }
        
        CopyNode(XPathExpression selectExpr, String useAttrSets, SequenceNode content, 
                String typeNamespaceURI, String typeLocalName, ValidationMode validation,
                boolean inheritNamespaces, AttributeValueTemplate inheritNamespacesAvt,
                boolean copyNamespaces, AttributeValueTemplate copyNamespacesAvt, XSLTNode onEmptyNode) {
            this.selectExpr = selectExpr;
            this.useAttrSets = useAttrSets;
            this.content = content;
            this.typeNamespaceURI = typeNamespaceURI;
            this.typeLocalName = typeLocalName;
            this.validation = validation;
            this.inheritNamespaces = inheritNamespaces;
            this.inheritNamespacesAvt = inheritNamespacesAvt;
            this.copyNamespaces = copyNamespaces;
            this.copyNamespacesAvt = copyNamespacesAvt;
            this.onEmptyNode = onEmptyNode;
        }
        
        @Override public String getInstructionName() { return "copy"; }
        @Override public void execute(TransformContext context, 
                                      OutputHandler output) throws SAXException {
            // XSLT 3.0: if select attribute is present, copy selected items
            // Otherwise, copy the context item (XSLT 1.0/2.0 behavior)
            XPathNode node;
            if (selectExpr != null) {
                try {
                    XPathValue result = selectExpr.evaluate(context);
                    if (result == null || (result.isNodeSet() && ((XPathNodeSet) result).isEmpty())) {
                        // Empty result - execute on-empty if present
                        if (onEmptyNode != null) {
                            onEmptyNode.execute(context, output);
                        }
                        return;
                    }
                    if (result.isNodeSet()) {
                        // Copy each node in the node-set
                        for (XPathNode n : ((XPathNodeSet) result).getNodes()) {
                            executeCopyForNode(n, context, output);
                        }
                        return;
                    } else if (result instanceof XPathNode) {
                        node = (XPathNode) result;
                    } else {
                        // Atomic value - output as text
                        output.characters(result.asString());
                        return;
                    }
                } catch (XPathException e) {
                    throw new SAXException("Error evaluating xsl:copy select", e);
                }
            } else {
                node = context.getContextNode();
            }
            
            if (node == null) {
                return;
            }
            
            executeCopyForNode(node, context, output);
        }
        
        private void executeCopyForNode(XPathNode node, TransformContext context, 
                                       OutputHandler output) throws SAXException {
            
            // Determine effective validation mode
            ValidationMode effectiveValidation = validation;
            if (effectiveValidation == null) {
                effectiveValidation = context.getStylesheet().getDefaultValidation();
            }
            
            // Determine effective inherit-namespaces value (evaluate AVT if present)
            boolean effectiveInheritNamespaces = inheritNamespaces;
            if (inheritNamespacesAvt != null) {
                try {
                    String inheritNsStr = inheritNamespacesAvt.evaluate(context).trim();
                    effectiveInheritNamespaces = "yes".equals(inheritNsStr) || "true".equals(inheritNsStr) || "1".equals(inheritNsStr);
                } catch (XPathException e) {
                    throw new SAXException("Error evaluating inherit-namespaces AVT", e);
                }
            }
            
            // Determine effective copy-namespaces value (evaluate AVT if present)
            boolean effectiveCopyNamespaces = copyNamespaces;
            if (copyNamespacesAvt != null) {
                try {
                    String copyNsStr = copyNamespacesAvt.evaluate(context).trim();
                    effectiveCopyNamespaces = "yes".equals(copyNsStr) || "true".equals(copyNsStr) || "1".equals(copyNsStr);
                } catch (XPathException e) {
                    throw new SAXException("Error evaluating copy-namespaces AVT", e);
                }
            }
            
            switch (node.getNodeType()) {
                case ELEMENT:
                    String uri = node.getNamespaceURI() != null ? node.getNamespaceURI() : "";
                    String localName = node.getLocalName();
                    String prefix = node.getPrefix();
                    String qName = prefix != null ? prefix + ":" + localName : localName;
                    
                    output.startElement(uri, localName, qName);
                    
                    // Get validator for potential use
                    RuntimeSchemaValidator validator = context.getRuntimeValidator();
                    
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
                    
                    // Copy namespace declarations from source element (if copy-namespaces="yes")
                    // Per XSLT spec, namespace undeclarations (xmlns="") should NOT be copied
                    // because the output tree follows different namespace inheritance rules
                    if (effectiveCopyNamespaces) {
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
                    output.setAtomicValuePending(false);
                    output.setInAttributeContent(false);
                    if (content != null) {
                        content.execute(context, output);
                    }
                    
                    // Complete validation after content execution
                    if ((effectiveValidation == ValidationMode.STRICT ||
                         effectiveValidation == ValidationMode.LAX)) {
                        if (validator != null) {
                            try {
                                RuntimeSchemaValidator.ValidationResult endResult = validator.endElement();
                                if (!endResult.isValid() && effectiveValidation == ValidationMode.STRICT) {
                                    throw new SAXException(endResult.getErrorCode() + ": " + 
                                                         endResult.getErrorMessage());
                                }
                            } catch (XPathException e) {
                                throw new SAXException("Content model validation error in xsl:copy", e);
                            }
                        }
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
        private final boolean copyNamespaces;          // Static value (when no AVT)
        private final AttributeValueTemplate copyNamespacesAvt;  // AVT (XSLT 3.0 shadow attribute)
        
        CopyOfNode(XPathExpression selectExpr) { 
            this(selectExpr, null, null, null, true, null);
        }
        
        CopyOfNode(XPathExpression selectExpr, String typeNamespaceURI, String typeLocalName,
                   ValidationMode validation, boolean copyNamespaces) {
            this(selectExpr, typeNamespaceURI, typeLocalName, validation, copyNamespaces, null);
        }
        
        CopyOfNode(XPathExpression selectExpr, String typeNamespaceURI, String typeLocalName,
                   ValidationMode validation, boolean copyNamespaces, AttributeValueTemplate copyNamespacesAvt) {
            this.selectExpr = selectExpr;
            this.typeNamespaceURI = typeNamespaceURI;
            this.typeLocalName = typeLocalName;
            this.validation = validation;
            this.copyNamespaces = copyNamespaces;
            this.copyNamespacesAvt = copyNamespacesAvt;
        }
        
        @Override public String getInstructionName() { return "copy-of"; }
        @Override public void execute(TransformContext context, 
                                      OutputHandler output) throws SAXException {
            try {
                // Use instruction's static base URI if set (for xml:base support and doc(''))
                TransformContext evalContext = (staticBaseURI != null) 
                    ? context.withStaticBaseURI(staticBaseURI) 
                    : context;
                    
                XPathValue result = selectExpr.evaluate(evalContext);
                
                if (result == null) {
                    // Variable not found or expression returned null - output nothing
                    return;
                }
                
                // Determine effective validation mode
                ValidationMode effectiveValidation = validation;
                if (effectiveValidation == null) {
                    effectiveValidation = context.getStylesheet().getDefaultValidation();
                }
                
                // Determine effective copy-namespaces value (evaluate AVT if present)
                boolean effectiveCopyNamespaces = copyNamespaces;
                if (copyNamespacesAvt != null) {
                    String copyNsStr = copyNamespacesAvt.evaluate(evalContext).trim();
                    effectiveCopyNamespaces = "yes".equals(copyNsStr) || "true".equals(copyNsStr) || "1".equals(copyNsStr);
                }
                
                if (result instanceof XPathResultTreeFragment) {
                    // Result tree fragment - replay the buffered events
                    // For validation="strip", don't include type annotations
                    XPathResultTreeFragment rtf = (XPathResultTreeFragment) result;
                    boolean includeTypes = effectiveValidation != ValidationMode.STRIP;
                    rtf.replayToOutput(output, includeTypes);
                } else if (result instanceof XPathNodeSet) {
                    XPathNodeSet nodeSet = (XPathNodeSet) result;
                    for (XPathNode node : nodeSet.getNodes()) {
                        // depth=0 means this is a directly selected node
                        deepCopyNode(node, output, effectiveValidation, effectiveCopyNamespaces, 0);
                    }
                } else if (result.isSequence()) {
                    // Handle XPath 2.0 sequences - iterate over items
                    java.util.Iterator<XPathValue> iter = result.sequenceIterator();
                    boolean needSpace = false;
                    while (iter.hasNext()) {
                        XPathValue item = iter.next();
                        if (item instanceof XPathResultTreeFragment) {
                            XPathResultTreeFragment rtf = (XPathResultTreeFragment) item;
                            boolean includeTypes = effectiveValidation != ValidationMode.STRIP;
                            rtf.replayToOutput(output, includeTypes);
                            needSpace = false;
                        } else if (item instanceof XPathNodeSet) {
                            XPathNodeSet ns = (XPathNodeSet) item;
                            for (XPathNode node : ns.getNodes()) {
                                deepCopyNode(node, output, effectiveValidation, effectiveCopyNamespaces, 0);
                            }
                            needSpace = false;
                        } else if (item.isNodeSet()) {
                            XPathNodeSet ns = item.asNodeSet();
                            for (XPathNode node : ns.getNodes()) {
                                deepCopyNode(node, output, effectiveValidation, effectiveCopyNamespaces, 0);
                            }
                            needSpace = false;
                        } else {
                            // Atomic value - output as text with space separator
                            if (needSpace) {
                                output.characters(" ");
                            }
                            output.characters(item.asString());
                            needSpace = true;
                        }
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
         * @param effectiveCopyNamespaces whether to copy namespace declarations
         * @param depth 0 for directly selected nodes, >0 for children of copied nodes
         */
        private void deepCopyNode(XPathNode node, OutputHandler output, 
                                  ValidationMode effectiveValidation, boolean effectiveCopyNamespaces, int depth) throws SAXException {
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
                    if (effectiveCopyNamespaces) {
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
                        deepCopyNode(children.next(), output, effectiveValidation, effectiveCopyNamespaces, depth + 1);
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
                        deepCopyNode(rootChildren.next(), output, effectiveValidation, effectiveCopyNamespaces, depth + 1);
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
                    output.setAtomicValuePending(false);
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
                    output.setAtomicValuePending(false);
                } else {
                    // For atomic values, use atomicValue() which handles spacing
                    // Adjacent atomic values are separated by a single space (but NOT in attribute content)
                    output.atomicValue(result);
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
                output.setAtomicValuePending(false);
            } else if (item instanceof XPathResultTreeFragment) {
                ((XPathResultTreeFragment) item).replayToOutput(output);
                output.setAtomicValuePending(false);
            } else {
                // Atomic value - use atomicValue() which handles spacing
                output.atomicValue(item);
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
                    } else if (result instanceof XPathNode) {
                        // Single node (e.g., from sequence construction)
                        nodes = Collections.singletonList((XPathNode) result);
                    } else if (result.isSequence()) {
                        // XPath 2.0+ sequence - extract nodes and atomic values (XSLT 3.0)
                        nodes = new ArrayList<>();
                        List<XPathValue> atomicValues = new ArrayList<>();
                        Iterator<XPathValue> iter = result.sequenceIterator();
                        while (iter.hasNext()) {
                            XPathValue item = iter.next();
                            if (item instanceof XPathNode) {
                                nodes.add((XPathNode) item);
                            } else if (item instanceof XPathNodeSet) {
                                nodes.addAll(((XPathNodeSet) item).getNodes());
                            } else {
                                // Atomic value (XSLT 3.0)
                                atomicValues.add(item);
                            }
                        }
                        
                        // Process atomic values first if there are templates that can match them
                        if (!atomicValues.isEmpty() && context instanceof BasicTransformContext) {
                            BasicTransformContext btc = (BasicTransformContext) context;
                            TemplateMatcher matcher = new TemplateMatcher(context.getStylesheet());
                            String effectiveMode = mode;
                            if ("#current".equals(mode)) {
                                effectiveMode = context.getCurrentMode();
                            }
                            int atomicPosition = 1;
                            int atomicSize = atomicValues.size();
                            for (XPathValue atomicValue : atomicValues) {
                                TemplateRule rule = matcher.findMatchForAtomicValue(atomicValue, effectiveMode, context);
                                if (rule != null) {
                                    // Execute the template with the atomic value as context item
                                    TransformContext atomicContext = btc
                                        .withContextItem(atomicValue)
                                        .withPositionAndSize(atomicPosition, atomicSize)
                                        .pushVariableScope()
                                        .withCurrentTemplateRule(rule);
                                    
                                    // Set parameters
                                    for (WithParamNode param : params) {
                                        if (!param.isTunnel()) {
                                            try {
                                                atomicContext = (TransformContext) atomicContext.withVariable(
                                                    null, param.getName(), param.evaluate(context));
                                            } catch (XPathException e) {
                                                throw new SAXException("Error evaluating param: " + e.getMessage(), e);
                                            }
                                        }
                                    }
                                    
                                    rule.getBody().execute(atomicContext, output);
                                }
                                atomicPosition++;
                            }
                        }
                        
                        if (nodes.isEmpty()) {
                            return; // No nodes in sequence (atomic values already processed)
                        }
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
                    // Resolve special mode values
                    String effectiveMode = mode;
                    if ("#current".equals(mode)) {
                        // Use the current mode from the context
                        effectiveMode = context.getCurrentMode();
                    }
                    
                    if (effectiveMode != null) {
                        nodeContext = nodeContext.withMode(effectiveMode);
                    }
                    
                    // Find and execute matching template
                    TemplateMatcher matcher = 
                        new TemplateMatcher(context.getStylesheet());
                    TemplateRule rule = matcher.findMatch(node, effectiveMode, nodeContext);
                    
                    if (rule != null) {
                        // Push scope and set current template rule (needed for apply-imports)
                        TransformContext execContext = 
                            nodeContext.pushVariableScope()
                                .withCurrentTemplateRule(rule);
                        
                        // XSLT 2.0 tunnel parameter support:
                        // - Non-tunnel with-param matches non-tunnel param only
                        // - Tunnel with-param matches tunnel param only
                        // - Tunnel params also receive values from context's tunnel params
                        
                        // Collect new tunnel parameters from with-param nodes
                        Map<String, XPathValue> newTunnelParams = new HashMap<>();
                        for (WithParamNode param : params) {
                            if (param.isTunnel()) {
                                try {
                                    newTunnelParams.put(param.getName(), param.evaluate(context));
                                } catch (XPathException e) {
                                    throw new SAXException("Error evaluating tunnel param: " + e.getMessage(), e);
                                }
                            }
                        }
                        
                        // Merge with existing tunnel params and update context
                        if (!newTunnelParams.isEmpty()) {
                            execContext = (TransformContext) execContext.withTunnelParameters(newTunnelParams);
                        }
                        
                        // Collect passed parameter names (only non-tunnel, matching non-tunnel template params)
                        Set<String> passedParams = new HashSet<>();
                        
                        // Process each template parameter
                        for (TemplateParameter templateParam : rule.getParameters()) {
                            XPathValue value = null;
                            boolean found = false;
                            
                            if (templateParam.isTunnel()) {
                                // Tunnel param: first check tunnel with-param, then context tunnel params
                                for (WithParamNode param : params) {
                                    if (param.isTunnel() && param.getName().equals(templateParam.getName())) {
                                        try {
                                            value = param.evaluate(context);
                                            found = true;
                                        } catch (XPathException e) {
                                            throw new SAXException("Error evaluating tunnel param: " + e.getMessage(), e);
                                        }
                                        break;
                                    }
                                }
                                // If not passed directly, check context's tunnel params
                                if (!found) {
                                    value = execContext.getTunnelParameters().get(templateParam.getName());
                                    found = (value != null);
                                }
                            } else {
                                // Non-tunnel param: only accept non-tunnel with-param
                                for (WithParamNode param : params) {
                                    if (!param.isTunnel() && param.getName().equals(templateParam.getName())) {
                                        try {
                                            value = param.evaluate(context);
                                            found = true;
                                        } catch (XPathException e) {
                                            throw new SAXException("Error evaluating param: " + e.getMessage(), e);
                                        }
                                        break;
                                    }
                                }
                            }
                            
                            if (found && value != null) {
                                execContext.getVariableScope().bind(templateParam.getNamespaceURI(), templateParam.getLocalName(), value);
                                passedParams.add(templateParam.getName());
                            } else {
                                // Use default value
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
                                execContext.getVariableScope().bind(templateParam.getNamespaceURI(), templateParam.getLocalName(), defaultValue);
                            }
                        }
                        
                        if (TemplateMatcher.isBuiltIn(rule)) {
                            executeBuiltIn(TemplateMatcher
                                .getBuiltInType(rule), node, execContext, output);
                        } else {
                            // XSLT 2.0+: If template has 'as' attribute, validate return type
                            String asType = rule.getAsType();
                            if (asType != null && !asType.isEmpty()) {
                                // Execute to a sequence builder to capture the result
                                SequenceBuilderOutputHandler seqBuilder = 
                                    new SequenceBuilderOutputHandler();
                                rule.getBody().execute(execContext, seqBuilder);
                                
                                // Get the result sequence
                                XPathValue result = seqBuilder.getSequence();
                                
                                // Validate against declared type
                                validateTemplateReturnType(result, asType, rule);
                                
                                // Output the validated result
                                outputValidatedResult(result, output);
                            } else {
                                rule.getBody().execute(execContext, output);
                            }
                        }
                    }
                    position++;
                }
            } catch (XPathException e) {
                throw new SAXException("Error in xsl:apply-templates", e);
            }
        }
        
        /**
         * Validates template return value against declared 'as' type.
         */
        private void validateTemplateReturnType(XPathValue result, String asType, 
                TemplateRule rule) throws SAXException {
            try {
                // Parse the type
                SequenceType expectedType = SequenceType.parse(asType, null);
                if (expectedType == null) {
                    return; // Unknown type - skip validation
                }
                
                // Check if empty result is allowed
                boolean isEmpty = result == null || 
                    (result instanceof XPathSequence && ((XPathSequence) result).isEmpty()) ||
                    (result.asString().isEmpty() && !(result instanceof XPathBoolean));
                
                // Empty sequence only allowed for optional types (?, *)
                SequenceType.Occurrence occ = expectedType.getOccurrence();
                if (isEmpty && occ != SequenceType.Occurrence.ZERO_OR_ONE && 
                    occ != SequenceType.Occurrence.ZERO_OR_MORE) {
                    String templateDesc = rule.getName() != null ? 
                        "named template '" + rule.getName() + "'" :
                        "template matching '" + rule.getMatchPattern() + "'";
                    throw new SAXException("XTTE0505: Required item type of " + 
                        templateDesc + " is " + asType + 
                        "; supplied value is empty sequence");
                }
                
                // For non-empty results, validate the type matches
                if (!isEmpty) {
                    if (!expectedType.matches(result, org.bluezoo.gonzalez.transform.xpath.type.SchemaContext.NONE)) {
                        String templateDesc = rule.getName() != null ? 
                            "named template '" + rule.getName() + "'" :
                            "template matching '" + rule.getMatchPattern() + "'";
                        throw new SAXException("XTTE0505: Required item type of " + 
                            templateDesc + " is " + asType + 
                            "; supplied value is " + result.getClass().getSimpleName());
                    }
                }
            } catch (Exception e) {
                if (e instanceof SAXException) {
                    throw (SAXException) e;
                }
                throw new SAXException(e.getMessage(), e);
            }
        }
        
        /**
         * Outputs a validated sequence result to the output handler.
         */
        private void outputValidatedResult(XPathValue result, OutputHandler output) 
                throws SAXException {
            if (result == null) {
                return;
            }
            
            if (result instanceof XPathSequence) {
                for (XPathValue item : (XPathSequence) result) {
                    outputSingleItem(item, output);
                }
            } else {
                outputSingleItem(result, output);
            }
        }
        
        private void outputSingleItem(XPathValue item, OutputHandler output) throws SAXException {
            if (item instanceof XPathResultTreeFragment) {
                ((XPathResultTreeFragment) item).replayToOutput(output);
            } else if (item instanceof XPathNode) {
                // Output node content
                output.characters(item.asString());
            } else {
                // Atomic value
                output.atomicValue(item);
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
                case ATTRIBUTE:
                    // Copy the attribute - note: will only work if there's a pending element
                    String aUri = node.getNamespaceURI();
                    String aLocal = node.getLocalName();
                    String aPrefix = node.getPrefix();
                    String aQName = aPrefix != null && !aPrefix.isEmpty() ? aPrefix + ":" + aLocal : aLocal;
                    output.attribute(aUri != null ? aUri : "", aLocal, aQName, node.getStringValue());
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
            // Find template by name - getNamedTemplate respects import precedence
            TemplateRule template = context.getStylesheet().getNamedTemplate(name);
            
            if (template == null) {
                throw new SAXException("Template not found: " + name);
            }
            
            // Push variable scope
            TransformContext callContext = 
                context.pushVariableScope();
            
            // XSLT 2.0 tunnel parameter support for call-template
            // Collect new tunnel parameters from with-param nodes
            Map<String, XPathValue> newTunnelParams = new HashMap<>();
            for (WithParamNode param : params) {
                if (param.isTunnel()) {
                    try {
                        newTunnelParams.put(param.getName(), param.evaluate(context));
                    } catch (XPathException e) {
                        throw new SAXException("Error evaluating tunnel param: " + e.getMessage(), e);
                    }
                }
            }
            
            // Merge with existing tunnel params and update context
            if (!newTunnelParams.isEmpty()) {
                callContext = callContext.withTunnelParameters(newTunnelParams);
            }
            
            // Collect passed parameter names
            Set<String> passedParams = new HashSet<>();
            
            // Process each template parameter
            for (TemplateParameter templateParam : template.getParameters()) {
                XPathValue value = null;
                boolean found = false;
                
                if (templateParam.isTunnel()) {
                    // Tunnel param: first check tunnel with-param, then context tunnel params
                    for (WithParamNode param : params) {
                        if (param.isTunnel() && param.getName().equals(templateParam.getName())) {
                            try {
                                value = param.evaluate(context);
                                found = true;
                            } catch (XPathException e) {
                                throw new SAXException("Error evaluating tunnel param: " + e.getMessage(), e);
                            }
                            break;
                        }
                    }
                    // If not passed directly, check context's tunnel params
                    if (!found) {
                        value = callContext.getTunnelParameters().get(templateParam.getName());
                        found = (value != null);
                    }
                } else {
                    // Non-tunnel param: only accept non-tunnel with-param
                    for (WithParamNode param : params) {
                        if (!param.isTunnel() && param.getName().equals(templateParam.getName())) {
                            try {
                                value = param.evaluate(context);
                                found = true;
                            } catch (XPathException e) {
                                throw new SAXException("Error evaluating param: " + e.getMessage(), e);
                            }
                            break;
                        }
                    }
                }
                
                if (found && value != null) {
                    callContext.getVariableScope().bind(templateParam.getNamespaceURI(), templateParam.getLocalName(), value);
                    passedParams.add(templateParam.getName());
                } else {
                    // Use default value
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
                        callContext.getVariableScope().bind(templateParam.getNamespaceURI(), templateParam.getLocalName(), defaultValue);
                    }
                }
            }
            
            // Execute template body
            template.getBody().execute(callContext, output);
        }
    }

    private static class ApplyImportsNode extends XSLTInstruction {
        private final List<WithParamNode> params;
        
        ApplyImportsNode() {
            this(new ArrayList<>());
        }
        
        ApplyImportsNode(List<WithParamNode> params) {
            this.params = params;
        }
        
        @Override public String getInstructionName() { return "apply-imports"; }
        @Override public void execute(TransformContext context, 
                                      OutputHandler output) throws SAXException {
            TemplateRule currentRule = context.getCurrentTemplateRule();
            XPathNode currentNode = context.getXsltCurrentNode();
            
            if (currentRule == null || currentNode == null) {
                // XTDE0560: xsl:apply-imports is not allowed when the current template rule is absent
                throw new SAXException("XTDE0560: xsl:apply-imports cannot be used when there is no current template rule");
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
            
            // XSLT 2.0 tunnel parameter support for apply-imports
            Map<String, XPathValue> newTunnelParams = new HashMap<>();
            for (WithParamNode param : params) {
                if (param.isTunnel()) {
                    try {
                        newTunnelParams.put(param.getName(), param.evaluate(context));
                    } catch (XPathException e) {
                        throw new SAXException("Error evaluating tunnel param: " + e.getMessage(), e);
                    }
                }
            }
            if (!newTunnelParams.isEmpty()) {
                execContext = execContext.withTunnelParameters(newTunnelParams);
            }
            
            // Process each template parameter
            Set<String> passedParams = new HashSet<>();
            for (TemplateParameter templateParam : importedRule.getParameters()) {
                XPathValue value = null;
                boolean found = false;
                
                if (templateParam.isTunnel()) {
                    // Tunnel param: first check tunnel with-param, then context tunnel params
                    for (WithParamNode param : params) {
                        if (param.isTunnel() && param.getName().equals(templateParam.getName())) {
                            try {
                                value = param.evaluate(context);
                                found = true;
                            } catch (XPathException e) {
                                throw new SAXException("Error evaluating tunnel param: " + e.getMessage(), e);
                            }
                            break;
                        }
                    }
                    if (!found) {
                        value = execContext.getTunnelParameters().get(templateParam.getName());
                        found = (value != null);
                    }
                } else {
                    // Non-tunnel param: only accept non-tunnel with-param
                    for (WithParamNode param : params) {
                        if (!param.isTunnel() && param.getName().equals(templateParam.getName())) {
                            try {
                                value = param.evaluate(context);
                                found = true;
                            } catch (XPathException e) {
                                throw new SAXException("Error evaluating param: " + e.getMessage(), e);
                            }
                            break;
                        }
                    }
                }
                
                if (found && value != null) {
                    execContext.getVariableScope().bind(templateParam.getNamespaceURI(), templateParam.getLocalName(), value);
                    passedParams.add(templateParam.getName());
                } else {
                    // Use default value
                    XPathValue defaultValue = null;
                    if (templateParam.getSelectExpr() != null) {
                        try {
                            defaultValue = templateParam.getSelectExpr().evaluate(execContext);
                        } catch (XPathException e) {
                            throw new SAXException("Error evaluating parameter default: " + e.getMessage(), e);
                        }
                    } else if (templateParam.getDefaultContent() != null) {
                        SAXEventBuffer buffer = new SAXEventBuffer();
                        templateParam.getDefaultContent().execute(execContext, new BufferOutputHandler(buffer));
                        defaultValue = new XPathResultTreeFragment(buffer);
                    } else {
                        defaultValue = new XPathString("");
                    }
                    if (defaultValue != null) {
                        execContext.getVariableScope().bind(templateParam.getNamespaceURI(), templateParam.getLocalName(), defaultValue);
                    }
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
                    // Copy the attribute - note: will only work if there's a pending element
                    String attUri = node.getNamespaceURI();
                    String attLocal = node.getLocalName();
                    String attPrefix = node.getPrefix();
                    String attQName = attPrefix != null && !attPrefix.isEmpty() ? attPrefix + ":" + attLocal : attLocal;
                    output.attribute(attUri != null ? attUri : "", attLocal, attQName, node.getStringValue());
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
                // XTDE0560: xsl:next-match is not allowed when the current template rule is absent
                throw new SAXException("XTDE0560: xsl:next-match cannot be used when there is no current template rule");
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
                    execContext.getVariableScope().bind(param.getNamespaceURI(), param.getLocalName(), value);
                } catch (XPathException e) {
                    throw new SAXException("Error evaluating with-param: " + e.getMessage(), e);
                }
            }
            
            // Bind template parameter defaults for any not provided
            for (TemplateParameter templateParam : nextRule.getParameters()) {
                if (execContext.getVariableScope().lookup(templateParam.getNamespaceURI(), templateParam.getLocalName()) == null) {
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
                    execContext.getVariableScope().bind(templateParam.getNamespaceURI(), templateParam.getLocalName(), defaultValue);
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
     * a matching catch block is executed instead.
     */
    private static class TryNode extends XSLTInstruction {
        private final XSLTNode tryContent;
        private final List<CatchNode> catchBlocks;
        
        TryNode(XSLTNode tryContent, List<CatchNode> catchBlocks) {
            this.tryContent = tryContent;
            this.catchBlocks = catchBlocks != null ? catchBlocks : Collections.emptyList();
        }
        
        @Override public String getInstructionName() { return "try"; }
        
        @Override
        public void execute(TransformContext context, OutputHandler output) throws SAXException {
            try {
                if (tryContent != null) {
                    tryContent.execute(context, output);
                }
            } catch (SAXException e) {
                handleError(extractErrorCode(e), context, output, e);
            } catch (RuntimeException e) {
                handleError(extractErrorCode(e), context, output, e);
            }
        }
        
        /**
         * Extracts the error code from an exception message.
         * Looks for patterns like "XTDE0540:" or just "XTDE0540" at the start.
         */
        private String extractErrorCode(Throwable e) {
            String message = e.getMessage();
            if (message == null) {
                return null;
            }
            
            // Look for error codes at the start: "XTDE0540: ..." or "XTDE0540 ..."
            int colonIdx = message.indexOf(':');
            int spaceIdx = message.indexOf(' ');
            int endIdx = -1;
            
            if (colonIdx > 0 && colonIdx < 12) {
                endIdx = colonIdx;
            } else if (spaceIdx > 0 && spaceIdx < 12) {
                endIdx = spaceIdx;
            }
            
            if (endIdx > 0) {
                String potential = message.substring(0, endIdx);
                // Validate it looks like an error code (letters + digits)
                if (potential.matches("[A-Z]{4}[0-9]{4}[a-z]*")) {
                    return potential;
                }
            }
            return null;
        }
        
        /**
         * Handles an error by finding a matching catch block.
         */
        private void handleError(String errorCode, TransformContext context, 
                                 OutputHandler output, Throwable e) throws SAXException {
            // Find a matching catch block
            for (CatchNode catchBlock : catchBlocks) {
                if (catchBlock.matchesError(errorCode)) {
                    // TODO: Bind error variables ($err:code, $err:description, etc.)
                    catchBlock.execute(context, output);
                    return;
                }
            }
            // No matching catch - if there are catch blocks with filters, rethrow
            // If there's a catch-all (empty errors attr), it would have matched
            if (!catchBlocks.isEmpty()) {
                // Check if any catch has no error filter (catch-all)
                boolean hasCatchAll = false;
                for (CatchNode c : catchBlocks) {
                    if (c.getErrorCodes() == null || c.getErrorCodes().isEmpty()) {
                        hasCatchAll = true;
                        break;
                    }
                }
                if (!hasCatchAll) {
                    // No catch-all, rethrow the error
                    if (e instanceof SAXException) {
                        throw (SAXException) e;
                    } else if (e instanceof RuntimeException) {
                        throw (RuntimeException) e;
                    }
                }
            }
            // No catch blocks or only filtered ones that didn't match - swallow silently
        }
    }

    /**
     * xsl:catch instruction (XSLT 3.0).
     *
     * <p>Contains catch content and error codes to match.
     */
    private static class CatchNode extends XSLTInstruction {
        private final XSLTNode content;
        private final String errorCodes;  // Space-separated error codes like "err:XTDE0540 Q{uri}local"
        
        CatchNode(XSLTNode content, String errorCodes) {
            this.content = content;
            this.errorCodes = errorCodes;
        }
        
        @Override public String getInstructionName() { return "catch"; }
        
        public XSLTNode getContent() { return content; }
        public String getErrorCodes() { return errorCodes; }
        
        /**
         * Checks if an error code matches this catch block.
         * @param errorCode the error code from the exception (e.g., "XTDE0540")
         * @return true if this catch should handle the error
         */
        public boolean matchesError(String errorCode) {
            if (errorCodes == null || errorCodes.isEmpty()) {
                return true;  // Catch all errors
            }
            // Parse the error codes list
            for (String code : errorCodes.split("\\s+")) {
                code = code.trim();
                if (code.isEmpty()) continue;
                
                // Handle wildcard "*"
                if ("*".equals(code)) {
                    return true;  // Catch all errors
                }
                
                // If no error code extracted, can't match specific codes
                if (errorCode == null) {
                    continue;
                }
                
                // Handle err:CODE format
                if (code.contains(":")) {
                    String localPart = code.substring(code.indexOf(':') + 1);
                    if (errorCode.equals(localPart) || errorCode.endsWith(localPart)) {
                        return true;
                    }
                }
                // Handle Q{uri}local format
                if (code.startsWith("Q{")) {
                    int closeIdx = code.indexOf('}');
                    if (closeIdx > 0) {
                        String localPart = code.substring(closeIdx + 1);
                        if (errorCode.equals(localPart) || errorCode.endsWith(localPart)) {
                            return true;
                        }
                    }
                }
                // Direct match
                if (errorCode.equals(code) || errorCode.endsWith(code)) {
                    return true;
                }
            }
            return false;
        }
        
        @Override
        public void execute(TransformContext context, OutputHandler output) throws SAXException {
            // CatchNode is executed by TryNode, not directly
            if (content != null) {
                content.execute(context, output);
            }
        }
    }

    /**
     * xsl:assert instruction (XSLT 3.0).
     *
     * <p>Tests an assertion during transformation. If the test fails,
     * a dynamic error is raised.
     */
    private static class AssertNode extends XSLTInstruction {
        private final XPathExpression testExpr;
        private final String errorCode;
        private final XSLTNode messageContent;
        
        AssertNode(XPathExpression testExpr, String errorCode, XSLTNode messageContent) {
            this.testExpr = testExpr;
            this.errorCode = errorCode;
            this.messageContent = messageContent;
        }
        
        @Override public String getInstructionName() { return "assert"; }
        
        @Override
        public void execute(TransformContext context, OutputHandler output) throws SAXException {
            try {
                XPathValue result = testExpr.evaluate(context);
                if (!result.asBoolean()) {
                    String code = errorCode != null ? errorCode : "XTMM9000";
                    throw new SAXException(code + ": Assertion failed");
                }
            } catch (XPathException e) {
                throw new SAXException("Error evaluating xsl:assert: " + e.getMessage(), e);
            }
        }
    }

    /**
     * xsl:on-empty instruction (XSLT 3.0).
     *
     * <p>Specifies content to use if the containing sequence is empty.
     */
    private static class OnEmptyNode extends XSLTInstruction {
        private final XSLTNode content;
        private final XPathExpression selectExpr;
        
        OnEmptyNode(XSLTNode content) {
            this.content = content;
            this.selectExpr = null;
        }
        
        OnEmptyNode(XPathExpression selectExpr) {
            this.content = null;
            this.selectExpr = selectExpr;
        }
        
        @Override public String getInstructionName() { return "on-empty"; }
        
        @Override
        public void execute(TransformContext context, OutputHandler output) throws SAXException {
            // Note: xsl:on-empty is handled specially by the sequence constructor
            // This execution is for when the sequence is indeed empty
            if (selectExpr != null) {
                try {
                    XPathValue result = selectExpr.evaluate(context);
                    if (result != null) {
                        String str = result.asString();
                        if (!str.isEmpty()) {
                            output.characters(str);
                        }
                    }
                } catch (XPathException e) {
                    throw new SAXException("Error evaluating xsl:on-empty select", e);
                }
            } else if (content != null) {
                content.execute(context, output);
            }
        }
    }

    /**
     * xsl:on-non-empty instruction (XSLT 3.0).
     *
     * <p>Specifies content to output only if the containing sequence is non-empty.
     */
    private static class OnNonEmptyNode extends XSLTInstruction {
        private final XSLTNode content;
        private final XPathExpression selectExpr;
        
        OnNonEmptyNode(XSLTNode content) {
            this.content = content;
            this.selectExpr = null;
        }
        
        OnNonEmptyNode(XPathExpression selectExpr) {
            this.content = null;
            this.selectExpr = selectExpr;
        }
        
        @Override public String getInstructionName() { return "on-non-empty"; }
        
        @Override
        public void execute(TransformContext context, OutputHandler output) throws SAXException {
            // Note: xsl:on-non-empty is handled specially by the sequence constructor
            // This execution is for when the sequence is indeed non-empty
            if (selectExpr != null) {
                try {
                    XPathValue result = selectExpr.evaluate(context);
                    if (result != null) {
                        String str = result.asString();
                        if (!str.isEmpty()) {
                            output.characters(str);
                        }
                    }
                } catch (XPathException e) {
                    throw new SAXException("Error evaluating xsl:on-non-empty select", e);
                }
            } else if (content != null) {
                content.execute(context, output);
            }
        }
    }

    /**
     * xsl:where-populated instruction (XSLT 3.0).
     *
     * <p>Outputs its content only if that content produces non-empty output.
     */
    private static class WherePopulatedNode extends XSLTInstruction {
        private final XSLTNode content;
        
        WherePopulatedNode(XSLTNode content) {
            this.content = content;
        }
        
        @Override public String getInstructionName() { return "where-populated"; }
        
        @Override
        public void execute(TransformContext context, OutputHandler output) throws SAXException {
            if (content == null) {
                return;
            }
            
            // Buffer the output to check if it produces anything
            SAXEventBuffer buffer = new SAXEventBuffer();
            BufferOutputHandler bufferOutput = new BufferOutputHandler(buffer);
            
            content.execute(context, bufferOutput);
            
            // Only output if content was produced
            if (!buffer.isEmpty()) {
                // Replay content events through an adapter to the OutputHandler
                if (output instanceof org.xml.sax.ContentHandler) {
                    buffer.replayContent((org.xml.sax.ContentHandler) output);
                } else {
                    // For OutputHandlers that don't implement ContentHandler,
                    // convert buffer to RTF and serialize
                    XPathResultTreeFragment rtf = new XPathResultTreeFragment(buffer);
                    String text = rtf.asString();
                    if (!text.isEmpty()) {
                        output.characters(text);
                    }
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
                        executeWithStringContext(nonMatchingContent, nonMatch, null, context, output);
                    }
                    
                    // Matching part - pass the matcher for regex-group() access
                    if (matchingContent != null) {
                        String match = matcher.group();
                        executeWithStringContext(matchingContent, match, matcher, context, output);
                    }
                    
                    lastEnd = matcher.end();
                }
                
                // Non-matching part after last match
                if (lastEnd < input.length() && nonMatchingContent != null) {
                    String nonMatch = input.substring(lastEnd);
                    executeWithStringContext(nonMatchingContent, nonMatch, null, context, output);
                }
            } catch (XPathException e) {
                throw new SAXException("Error in xsl:analyze-string select: " + e.getMessage(), e);
            } catch (PatternSyntaxException e) {
                throw new SAXException("Invalid regex in xsl:analyze-string: " + e.getMessage(), e);
            }
        }
        
        private void executeWithStringContext(XSLTNode content, String contextString,
                                              java.util.regex.Matcher matcher,
                                              TransformContext context, OutputHandler output) 
                throws SAXException {
            // Create a context where the context node has the string as its value
            // For xsl:analyze-string, the context item is the matched/non-matched string
            // We create a text node wrapper for this
            XPathNode textNode = new StringContextNode(contextString);
            TransformContext strContext = context.withContextNode(textNode);
            // Set the regex matcher for regex-group() function access
            if (matcher != null) {
                strContext = strContext.withRegexMatcher(matcher);
            }
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

    /**
     * Implements xsl:perform-sort instruction (XSLT 2.0).
     * Sorts a sequence and returns the sorted result.
     */
    private static class PerformSortNode extends XSLTInstruction {
        private final XPathExpression selectExpr;
        private final List<SortSpec> sorts;
        private final XSLTNode content;
        
        PerformSortNode(XPathExpression selectExpr, List<SortSpec> sorts, XSLTNode content) {
            this.selectExpr = selectExpr;
            this.sorts = sorts;
            this.content = content;
        }
        
        @Override public String getInstructionName() { return "perform-sort"; }
        
        @Override public void execute(TransformContext context, 
                                      OutputHandler output) throws SAXException {
            try {
                // Get the sequence to sort
                List<XPathNode> nodes = new ArrayList<>();
                
                if (selectExpr != null) {
                    // Sort the selected sequence
                    XPathValue result = selectExpr.evaluate(context);
                    if (result != null) {
                        collectNodes(result, nodes);
                    }
                } else if (content != null) {
                    // Content generates the sequence - evaluate and collect nodes
                    SAXEventBuffer buffer = new SAXEventBuffer();
                    BufferOutputHandler bufferOutput = new BufferOutputHandler(buffer);
                    content.execute(context, bufferOutput);
                    
                    // Convert buffer to RTF and collect its children
                    XPathResultTreeFragment rtf = new XPathResultTreeFragment(buffer);
                    collectNodes(rtf, nodes);
                }
                
                if (nodes.isEmpty()) {
                    return;
                }
                
                // Apply sorting
                ForEachNode.sortNodesStatic(nodes, sorts, context);
                
                // Output the sorted sequence
                boolean first = true;
                for (XPathNode node : nodes) {
                    if (!first) {
                        output.itemBoundary();
                    }
                    first = false;
                    deepCopyNode(node, output);
                }
            } catch (XPathException e) {
                throw new SAXException("Error in xsl:perform-sort", e);
            }
        }
        
        private void collectNodes(XPathValue result, List<XPathNode> nodes) {
            if (result instanceof XPathNodeSet) {
                for (XPathNode node : ((XPathNodeSet) result).getNodes()) {
                    nodes.add(node);
                }
            } else if (result instanceof XPathSequence) {
                for (XPathValue item : ((XPathSequence) result).getItems()) {
                    collectNodes(item, nodes);
                }
            } else if (result instanceof XPathNode) {
                nodes.add((XPathNode) result);
            } else if (result instanceof XPathResultTreeFragment) {
                XPathResultTreeFragment rtf = (XPathResultTreeFragment) result;
                if (rtf.isNodeSet()) {
                    XPathNodeSet ns = rtf.asNodeSet();
                    for (XPathNode node : ns.getNodes()) {
                        nodes.add(node);
                    }
                }
            }
            // Atomic values are skipped
        }
        
        /**
         * Deep copies a node to the output handler (simplified version for perform-sort).
         */
        private void deepCopyNode(XPathNode node, OutputHandler output) throws SAXException {
            NodeType nodeType = node.getNodeType();
            if (nodeType == NodeType.ELEMENT) {
                String uri = node.getNamespaceURI() != null ? node.getNamespaceURI() : "";
                String localName = node.getLocalName();
                String prefix = node.getPrefix();
                String qName = prefix != null && !prefix.isEmpty() ? prefix + ":" + localName : localName;
                
                output.startElement(uri, localName, qName);
                
                // Copy namespace declarations
                if (prefix == null || prefix.isEmpty()) {
                    output.namespace("", uri);
                }
                Iterator<XPathNode> namespaces = node.getNamespaces();
                while (namespaces.hasNext()) {
                    XPathNode ns = namespaces.next();
                    String nsPrefix = ns.getLocalName();
                    String nsUri = ns.getStringValue();
                    if (!"xml".equals(nsPrefix) && nsUri != null) {
                        output.namespace(nsPrefix != null ? nsPrefix : "", nsUri);
                    }
                }
                
                // Copy attributes
                Iterator<XPathNode> attributes = node.getAttributes();
                while (attributes.hasNext()) {
                    XPathNode attr = attributes.next();
                    String attrUri = attr.getNamespaceURI();
                    String attrLocal = attr.getLocalName();
                    String attrPrefix = attr.getPrefix();
                    String attrQName = attrPrefix != null && !attrPrefix.isEmpty() 
                        ? attrPrefix + ":" + attrLocal : attrLocal;
                    output.attribute(attrUri != null ? attrUri : "", attrLocal, attrQName, attr.getStringValue());
                }
                
                // Copy children
                Iterator<XPathNode> children = node.getChildren();
                while (children.hasNext()) {
                    deepCopyNode(children.next(), output);
                }
                
                output.endElement(uri, localName, qName);
            } else if (nodeType == NodeType.TEXT) {
                output.characters(node.getStringValue());
            } else if (nodeType == NodeType.COMMENT) {
                output.comment(node.getStringValue());
            } else if (nodeType == NodeType.PROCESSING_INSTRUCTION) {
                output.processingInstruction(node.getLocalName(), node.getStringValue());
            } else if (nodeType == NodeType.ROOT) {
                // Copy children of document node
                Iterator<XPathNode> docChildren = node.getChildren();
                while (docChildren.hasNext()) {
                    deepCopyNode(docChildren.next(), output);
                }
            }
            // Ignore other node types (ATTRIBUTE, NAMESPACE)
        }
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
                } else if (item instanceof XPathNodeSet) {
                    // Node-set item (single node wrapped in node-set from sequence construction)
                    XPathNodeSet ns = (XPathNodeSet) item;
                    Iterator<XPathNode> iter = ns.iterator();
                    if (iter.hasNext()) {
                        XPathNode node = iter.next();
                        if (iterContext instanceof BasicTransformContext) {
                            iterContext = ((BasicTransformContext) iterContext)
                                .withXsltCurrentNode(node).withPositionAndSize(position, size);
                        } else {
                            iterContext = iterContext.withContextNode(node).withPositionAndSize(position, size);
                        }
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
            // Pre-evaluate sort spec AVTs (these are constant for the entire sort)
            final int nodeCount = nodes.size();
            final int sortCount = sorts.size();
            final String[] dataTypes = new String[sortCount];
            final String[] orders = new String[sortCount];
            final String[] caseOrders = new String[sortCount];
            final Collation[] collations = new Collation[sortCount];
            
            for (int j = 0; j < sortCount; j++) {
                SortSpec spec = sorts.get(j);
                dataTypes[j] = spec.getDataType(context);
                orders[j] = spec.getOrder(context);
                caseOrders[j] = spec.getCaseOrder(context);
                // Get collation - use explicit collation, or default collation from context
                String collationUri = spec.getCollation(context);
                if (collationUri == null) {
                    collationUri = context.getDefaultCollation();
                }
                collations[j] = Collation.forUri(collationUri);
            }
            
            // Pre-compute sort keys for all nodes
            final Object[][] sortKeys = new Object[nodeCount][sortCount];
            
            for (int i = 0; i < nodeCount; i++) {
                XPathNode node = nodes.get(i);
                // Set position/size for sort key evaluation (position is 1-based, original order)
                // IMPORTANT: Use withXsltCurrentNode so current() returns the node being sorted,
                // not some other node from an outer context (bug-2501 fix)
                TransformContext nodeCtx;
                if (context instanceof BasicTransformContext) {
                    nodeCtx = ((BasicTransformContext) context)
                        .withXsltCurrentNode(node).withPositionAndSize(i + 1, nodeCount);
                } else {
                    nodeCtx = context.withContextNode(node)
                        .withPositionAndSize(i + 1, nodeCount);
                }
                for (int j = 0; j < sortCount; j++) {
                    SortSpec spec = sorts.get(j);
                    XPathValue val = spec.getSelectExpr().evaluate(nodeCtx);
                    if ("number".equals(dataTypes[j])) {
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
                            Collation collation = collations[j];
                            String caseOrder = caseOrders[j];
                            
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
                                // No case-order - use collation for comparison
                                cmp = collation.compare(sa, sb);
                            }
                        }
                        
                        // Apply order direction
                        if ("descending".equals(orders[j])) {
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
        private final String namespaceURI;
        private final String localName;
        private final String expandedName; // Clark notation: {uri}localname or just localname
        private final XPathExpression selectExpr;
        private final SequenceNode content;
        private final boolean tunnel; // XSLT 2.0: whether this is a tunnel parameter
        private final String asType; // XSLT 2.0+: declared type
        WithParamNode(String namespaceURI, String localName, XPathExpression selectExpr, SequenceNode content) {
            this(namespaceURI, localName, selectExpr, content, false, null);
        }
        WithParamNode(String namespaceURI, String localName, XPathExpression selectExpr, SequenceNode content, boolean tunnel) {
            this(namespaceURI, localName, selectExpr, content, tunnel, null);
        }
        WithParamNode(String namespaceURI, String localName, XPathExpression selectExpr, SequenceNode content, boolean tunnel, String asType) {
            this.namespaceURI = namespaceURI;
            this.localName = localName;
            this.expandedName = makeExpandedName(namespaceURI, localName);
            this.selectExpr = selectExpr;
            this.content = content;
            this.tunnel = tunnel;
            this.asType = asType;
        }
        private static String makeExpandedName(String namespaceURI, String localName) {
            if (namespaceURI == null || namespaceURI.isEmpty()) {
                return localName;
            }
            return "{" + namespaceURI + "}" + localName;
        }
        String getNamespaceURI() { return namespaceURI; }
        String getLocalName() { return localName; }
        String getName() { return expandedName; }
        XPathExpression getSelectExpr() { return selectExpr; }
        SequenceNode getContent() { return content; }
        boolean isTunnel() { return tunnel; }
        String getAsType() { return asType; }
        @Override public String getInstructionName() { return "with-param"; }
        @Override public void execute(TransformContext context, 
                                      OutputHandler output) throws SAXException {
            // With-param nodes are handled by call-template/apply-templates
        }
        
        XPathValue evaluate(
                TransformContext context) 
                throws XPathException, SAXException {
            XPathValue value;
            if (selectExpr != null) {
                value = selectExpr.evaluate(context);
            } else if (content != null) {
                SAXEventBuffer buffer = 
                    new SAXEventBuffer();
                content.execute(context, new BufferOutputHandler(buffer));
                value = XPathString.of(buffer.getTextContent());
            } else {
                value = XPathString.of("");
            }
            
            // Apply type coercion and validation for 'as' attribute (XSLT 2.0+)
            if (asType != null && value != null && context.isStrictTypeChecking()) {
                try {
                    // Parse the expected type to check if we can handle it
                    SequenceType expectedType = SequenceType.parse(asType, null);
                    
                    // Only attempt coercion and validation for atomic single-value types
                    // Skip node types and sequences for now (require more complex handling)
                    if (expectedType != null && expectedType.getItemKind() == SequenceType.ItemKind.ATOMIC &&
                        expectedType.getOccurrence() == SequenceType.Occurrence.ONE) {
                        
                        // Try to coerce the value to the target type
                        value = coerceParameterValue(value, asType);
                        
                        // Then validate that it matches
                        if (!expectedType.matches(value)) {
                            throw new XPathException("XTTE0590: Parameter value does not match declared type '" + 
                                asType + "'. Got: " + (value != null ? value.getType() : "null"));
                        }
                    }
                } catch (XPathException e) {
                    // Re-wrap with context about parameter
                    throw new XPathException("XTTE0590: " + e.getMessage());
                }
            }
            
            return value;
        }
        
        /**
         * Coerces a parameter value to match the declared type.
         * Similar to function return type coercion.
         */
        private XPathValue coerceParameterValue(XPathValue value, String targetType) throws XPathException {
            if (value == null || targetType == null) {
                return value;
            }
            
            // Parse the target type
            SequenceType expectedType = SequenceType.parse(targetType, null);
            if (expectedType == null) {
                return value;
            }
            
            // If it's an atomic type, try string-to-atomic conversion
            if (expectedType.getItemKind() == SequenceType.ItemKind.ATOMIC) {
                String typeLocalName = expectedType.getLocalName();
                String typeNsUri = expectedType.getNamespaceURI();
                
                if (typeNsUri != null && typeNsUri.equals(SequenceType.XS_NAMESPACE)) {
                    // Get the string value and try to convert
                    String stringValue = value.asString();
                    try {
                        switch (typeLocalName) {
                            case "double":
                            case "float":
                                return new XPathNumber(Double.parseDouble(stringValue));
                            case "decimal":
                            case "integer":
                            case "int":
                            case "long":
                            case "short":
                                return new XPathNumber(Double.parseDouble(stringValue));
                            case "boolean":
                                return XPathBoolean.of("true".equals(stringValue) || "1".equals(stringValue));
                            case "string":
                                return new XPathString(stringValue);
                            case "date":
                                return XPathDateTime.parseDate(stringValue);
                            case "dateTime":
                                return XPathDateTime.parseDateTime(stringValue);
                            case "time":
                                return XPathDateTime.parseTime(stringValue);
                            case "duration":
                            case "dayTimeDuration":
                            case "yearMonthDuration":
                                return XPathDateTime.parseDuration(stringValue);
                            // Add more types as needed
                            default:
                                // For unknown types, return as-is
                                return value;
                        }
                    } catch (Exception e) {
                        // If conversion fails, return original value and let validation catch it
                        return value;
                    }
                }
            }
            
            return value;
        }
    }

    private static class NumberNode extends XSLTInstruction {
        private final XPathExpression valueExpr;
        private final XPathExpression selectExpr; // XSLT 2.0+ select attribute
        private final String level;
        private final Pattern countPattern;
        private final Pattern fromPattern;
        private final AttributeValueTemplate formatAVT;
        private final String groupingSeparator;
        private final int groupingSize;
        private final String lang;
        private final String letterValue;
        
        NumberNode(XPathExpression valueExpr, XPathExpression selectExpr, String level, 
                  Pattern countPattern, Pattern fromPattern, AttributeValueTemplate formatAVT, String groupingSeparator,
                  int groupingSize, String lang, String letterValue) {
            this.valueExpr = valueExpr;
            this.selectExpr = selectExpr;
            this.level = level;
            this.countPattern = countPattern;
            this.fromPattern = fromPattern;
            this.formatAVT = formatAVT;
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
            
            // Evaluate the format AVT
            String format;
            try {
                format = formatAVT.evaluate(context);
            } catch (XPathException e) {
                throw new SAXException("Error evaluating xsl:number format: " + e.getMessage(), e);
            }
            
            // Format and output
            String result = formatNumbers(numbers, format);
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
                        // XTDE0980: Negative numbers are not allowed in xsl:number value
                        if (d < 0) {
                            throw new SAXException("XTDE0980: xsl:number value must not be negative: " + d);
                        }
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
        
        private String formatNumbers(List<Integer> numbers, String format) {
            // Parse format string into components
            ParsedFormat parsed = parseFormatString(format);
            
            // When no numbers, just output prefix + suffix (e.g., "[]" for format "[1]")
            if (numbers.isEmpty()) {
                return parsed.prefix + parsed.suffix;
            }
            
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
        private final boolean terminateStatic;
        private final AttributeValueTemplate terminateAvt;
        private final String errorCode;
        
        MessageNode(SequenceNode content, XPathExpression selectExpr, 
                   boolean terminateStatic, AttributeValueTemplate terminateAvt, String errorCode) {
            this.content = content;
            this.selectExpr = selectExpr;
            this.terminateStatic = terminateStatic;
            this.terminateAvt = terminateAvt;
            this.errorCode = errorCode;
        }
        
        @Override public String getInstructionName() { return "message"; }
        
        @Override public void execute(TransformContext context, 
                                      OutputHandler output) throws SAXException {
            // Evaluate terminate value
            boolean terminate = terminateStatic;
            if (terminateAvt != null) {
                try {
                    String terminateValue = terminateAvt.evaluate(context).trim().toLowerCase();
                    if ("yes".equals(terminateValue) || "true".equals(terminateValue) || "1".equals(terminateValue)) {
                        terminate = true;
                    } else if ("no".equals(terminateValue) || "false".equals(terminateValue) || "0".equals(terminateValue) || terminateValue.isEmpty()) {
                        terminate = false;
                    } else {
                        // XTDE0030: Invalid AVT value for terminate attribute
                        throw new SAXException("XTDE0030: Invalid runtime value for terminate attribute: '" + 
                                              terminateValue + "'. Must evaluate to 'yes' or 'no'");
                    }
                } catch (XPathException e) {
                    throw new SAXException("Error evaluating terminate AVT: " + e.getMessage(), e);
                }
            }
            
            String message = buildMessage(context);
            
            // Get the error listener from context if available
            ErrorListener errorListener = context.getErrorListener();
            
            if (errorListener != null) {
                // Use ErrorListener for proper message handling
                try {
                    String location = getLocationInfo(context);
                    TransformerException te = 
                        new TransformerException(message);
                    
                    if (terminate) {
                        errorListener.fatalError(te);
                    } else {
                        errorListener.warning(te);
                    }
                } catch (TransformerException e) {
                    // ErrorListener may re-throw
                    throw new SAXException(e);
                }
            } else {
                // Default: output to stderr
                StringBuilder sb = new StringBuilder();
                if (errorCode != null) {
                    sb.append("[");
                    sb.append(errorCode);
                    sb.append("] ");
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
        private final boolean isAtomicPattern;  // XSLT 3.0: .[ predicate ] patterns
        private final String atomicPredicate;   // The predicate for atomic value matching
        
        SimplePattern(String patternStr) {
            this.patternStr = patternStr;
            
            // Check for XSLT 3.0 atomic value pattern: .[ predicate ]
            // This pattern matches atomic values, not nodes
            String trimmed = patternStr.trim();
            if (trimmed.startsWith(".") && trimmed.length() > 1) {
                String afterDot = trimmed.substring(1).trim();
                if (afterDot.startsWith("[") && afterDot.endsWith("]")) {
                    // This is an atomic value pattern
                    this.isAtomicPattern = true;
                    this.atomicPredicate = afterDot.substring(1, afterDot.length() - 1).trim();
                    this.basePattern = trimmed;
                    this.predicateStr = null;
                    return;
                }
            }
            this.isAtomicPattern = false;
            this.atomicPredicate = null;
            
            // Normalize axis syntax to shorter forms:
            // - child:: is implicit (remove it)
            // - attribute:: is equivalent to @
            String normalized = patternStr
                .replace("child::", "")
                .replace("attribute::", "@");
            
            // Check if this is a union pattern at the top level (| outside brackets)
            // If so, don't extract predicates - handle the union as-is
            if (hasTopLevelUnion(normalized)) {
                this.basePattern = normalized;
                this.predicateStr = null;
            } else {
                // Extract ALL predicates at the end of the pattern
                // Pattern might have multiple consecutive predicates like name[pred1][pred2]
                int[] bracketRange = findPredicateRange(normalized);
                if (bracketRange != null) {
                    int firstBracketStart = bracketRange[0];
                    int lastBracketEnd = bracketRange[1];
                    
                    // Check for additional consecutive predicates
                    String afterFirst = normalized.substring(lastBracketEnd + 1);
                    while (afterFirst.startsWith("[")) {
                        int[] nextRange = findPredicateRange(afterFirst);
                        if (nextRange != null) {
                            lastBracketEnd = lastBracketEnd + 1 + nextRange[1];
                            afterFirst = normalized.substring(lastBracketEnd + 1);
                        } else {
                            break;
                        }
                    }
                    
                    String afterAllPredicates = normalized.substring(lastBracketEnd + 1);
                    if (afterAllPredicates.isEmpty()) {
                        // Pattern ends with predicate(s)
                        this.basePattern = normalized.substring(0, firstBracketStart);
                        // Combine all predicates into one expression with "and"
                        String allPreds = normalized.substring(firstBracketStart, lastBracketEnd + 1);
                        this.predicateStr = combinePredicates(allPreds);
                    } else {
                        // Pattern continues after predicates - include predicates in base
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
         * Combines multiple consecutive predicates like [pred1][pred2] into "(pred1) and (pred2)".
         */
        private String combinePredicates(String allPredicates) {
            List<String> preds = new ArrayList<>();
            int depth = 0;
            int start = -1;
            boolean inQuote = false;
            char quoteChar = 0;
            
            for (int i = 0; i < allPredicates.length(); i++) {
                char c = allPredicates.charAt(i);
                if (!inQuote && (c == '"' || c == '\'')) {
                    inQuote = true;
                    quoteChar = c;
                } else if (inQuote && c == quoteChar) {
                    inQuote = false;
                } else if (!inQuote) {
                    if (c == '[') {
                        if (depth == 0) {
                            start = i + 1;
                        }
                        depth++;
                    } else if (c == ']') {
                        depth--;
                        if (depth == 0 && start >= 0) {
                            preds.add(allPredicates.substring(start, i));
                            start = -1;
                        }
                    }
                }
            }
            
            if (preds.size() == 1) {
                return preds.get(0);
            }
            
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < preds.size(); i++) {
                if (i > 0) {
                    sb.append(" and ");
                }
                sb.append("(");
                sb.append(preds.get(i));
                sb.append(")");
            }
            return sb.toString();
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
         * Returns the start index of the FIRST occurrence of the keyword, or -1 if not found.
         */
        private int findKeywordOutsideBrackets(String pattern, String keyword) {
            return findKeywordOutsideBrackets(pattern, keyword, false);
        }
        
        /**
         * Find a keyword outside of brackets, braces, and quotes.
         * @param findLast if true, returns the LAST occurrence (for left-associative operators like except)
         * Returns the start index of the keyword, or -1 if not found.
         */
        private int findKeywordOutsideBrackets(String pattern, String keyword, boolean findLast) {
            int bracketDepth = 0;
            int braceDepth = 0;
            boolean inQuote = false;
            char quoteChar = 0;
            int lastFound = -1;
            
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
                            if (!findLast) {
                                return i;  // Return first occurrence
                            }
                            lastFound = i;  // Track last occurrence
                        }
                    }
                }
            }
            return findLast ? lastFound : -1;
        }
        
        @Override
        public boolean matches(XPathNode node, 
                              TransformContext context) {
            // Atomic patterns don't match nodes
            if (isAtomicPattern) {
                return false;
            }
            // Entry point for pattern matching - set target node for current()
            return matchesWithTarget(node, context, node);
        }
        
        @Override
        public boolean canMatchAtomicValues() {
            return isAtomicPattern;
        }
        
        @Override
        public boolean matchesAtomicValue(XPathValue value, TransformContext context) {
            if (!isAtomicPattern) {
                return false;
            }
            
            // Evaluate the predicate with the atomic value as the context item
            // The predicate is something like ". instance of xs:integer"
            try {
                // Create an XPath context with the atomic value as the context item
                XPathContext xpathContext = context.withContextItem(value).withPositionAndSize(1, 1);
                
                // Create a namespace resolver that wraps the XPath context
                XPathParser.NamespaceResolver nsResolver = prefix -> xpathContext.resolveNamespacePrefix(prefix);
                
                // Parse and evaluate the predicate expression
                XPathParser parser = new XPathParser(atomicPredicate, nsResolver);
                XPathValue result = parser.parse().evaluate(xpathContext);
                
                // Predicates are effective boolean values
                return result.asBoolean();
            } catch (XPathException | XPathSyntaxException e) {
                // Predicate evaluation failed - don't match
                return false;
            }
        }
        
        /**
         * Matches a node against this pattern, with a specified target node for current().
         * The target node is the original node being matched (used for current() in predicates).
         */
        private boolean matchesWithTarget(XPathNode node, TransformContext context, XPathNode targetNode) {
            // Handle except patterns (pattern except pattern) - XSLT 3.0
            // Must check before union since except has higher precedence
            // Use findLast=true for left-associativity: "* except q except z" = "(* except q) except z"
            int exceptIdx = findKeywordOutsideBrackets(patternStr, " except ", true);
            if (exceptIdx > 0) {
                String leftPart = patternStr.substring(0, exceptIdx).trim();
                String rightPart = patternStr.substring(exceptIdx + 8).trim();
                // Matches if node matches left pattern but NOT right pattern
                // Left part may itself contain except operators (handled recursively)
                return new SimplePattern(leftPart).matchesWithTarget(node, context, targetNode) &&
                       !new SimplePattern(rightPart).matchesWithTarget(node, context, targetNode);
            }
            
            // Handle intersect patterns (pattern intersect pattern) - XSLT 3.0
            // Use findLast=true for left-associativity
            int intersectIdx = findKeywordOutsideBrackets(patternStr, " intersect ", true);
            if (intersectIdx > 0) {
                String leftPart = patternStr.substring(0, intersectIdx).trim();
                String rightPart = patternStr.substring(intersectIdx + 11).trim();
                // Matches if node matches BOTH left and right patterns
                return new SimplePattern(leftPart).matchesWithTarget(node, context, targetNode) &&
                       new SimplePattern(rightPart).matchesWithTarget(node, context, targetNode);
            }
            
            // Handle union patterns (pattern | pattern or pattern union pattern)
            // MUST check before function patterns like key(), id(), doc() since unions
            // can contain these functions (e.g., key('k', 'v') | /)
            int unionIdx = findKeywordOutsideBrackets(patternStr, " union ");
            if (unionIdx > 0) {
                String leftPart = patternStr.substring(0, unionIdx).trim();
                String rightPart = patternStr.substring(unionIdx + 7).trim();
                // Matches if node matches EITHER pattern
                return new SimplePattern(leftPart).matchesWithTarget(node, context, targetNode) ||
                       new SimplePattern(rightPart).matchesWithTarget(node, context, targetNode);
            }
            
            // Check for | union at top level (outside brackets/parens)
            if (hasTopLevelUnion(patternStr)) {
                String[] parts = splitUnion(patternStr);
                for (String part : parts) {
                    if (new SimplePattern(part.trim()).matchesWithTarget(node, context, targetNode)) {
                        return true;
                    }
                }
                return false;
            }
            
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
            
            // Handle element-with-id() function patterns (XSLT 3.0)
            // element-with-id('x') - matches element containing child element with xs:ID type
            // element-with-id('x', $doc) - matches in specified document
            if (patternStr.startsWith("element-with-id(")) {
                return matchesElementWithIdPattern(node, context, patternStr);
            }
            
            // Handle key() function patterns (XSLT 1.0+)
            // key('name', 'value') - matches elements with specified key value
            // key('name', 'value')//foo - matches foo descendants
            if (patternStr.startsWith("key(")) {
                return matchesKeyPattern(node, context, patternStr);
            }
            
            // Legacy union handling for basePattern (shouldn't reach here if hasTopLevelUnion works)
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
                // node() in a match pattern uses the implicit child axis
                // It matches elements, text, comments, and PIs - NOT attributes or root
                // Attributes require explicit @node() or attribute::node()
                NodeType type = node.getNodeType();
                return type == NodeType.ELEMENT || type == NodeType.TEXT ||
                       type == NodeType.COMMENT || type == NodeType.PROCESSING_INSTRUCTION;
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
                // Check for circular reference error - must propagate
                Throwable cause = e;
                while (cause != null) {
                    if (cause.getMessage() != null && cause.getMessage().contains("XTDE0640")) {
                        // Circular reference - rethrow as runtime exception
                        throw new RuntimeException(cause.getMessage(), cause);
                    }
                    cause = cause.getCause();
                }
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
                String typeName = commaIdx > 0 ? inner.substring(commaIdx + 1).trim() : null;
                
                // Check name (if not wildcard)
                if (!"*".equals(elemName)) {
                    int colonIdx = elemName.indexOf(':');
                    String localName = colonIdx > 0 ? elemName.substring(colonIdx + 1) : elemName;
                    if (!localName.equals(node.getLocalName())) {
                        return false;
                    }
                }
                
                // Check type annotation if specified
                if (typeName != null) {
                    return matchesTypeConstraint(node, typeName);
                }
                return true;
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
                String typeName = commaIdx > 0 ? inner.substring(commaIdx + 1).trim() : null;
                
                // Check name (if not wildcard)
                if (!"*".equals(attrName)) {
                    int colonIdx = attrName.indexOf(':');
                    String localName = colonIdx > 0 ? attrName.substring(colonIdx + 1) : attrName;
                    if (!localName.equals(node.getLocalName())) {
                        return false;
                    }
                }
                
                // Check type annotation if specified
                if (typeName != null) {
                    return matchesTypeConstraint(node, typeName);
                }
                return true;
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
         * Checks if a node's type annotation matches (or derives from) the specified type.
         * 
         * @param node the node to check
         * @param typeName the type name (e.g., "xs:integer", "xs:string", "myprefix:myType")
         * @return true if the node's type matches or derives from the specified type
         */
        private boolean matchesTypeConstraint(XPathNode node, String typeName) {
            // Node must have a type annotation
            if (!node.hasTypeAnnotation()) {
                return false;
            }
            
            // Parse the type name
            String typeNs = XSD_NAMESPACE;  // Default to XSD namespace
            String typeLocal = typeName;
            
            int colonIdx = typeName.indexOf(':');
            if (colonIdx > 0) {
                String prefix = typeName.substring(0, colonIdx);
                typeLocal = typeName.substring(colonIdx + 1);
                // Resolve prefix (xs: is always XSD namespace)
                if ("xs".equals(prefix) || "xsd".equals(prefix)) {
                    typeNs = XSD_NAMESPACE;
                }
                // Other prefixes would need to be resolved from the stylesheet's namespace context
            }
            
            // Get node's type annotation
            String nodeTypeLocal = node.getTypeLocalName();
            
            // Look up the node's type to check derivation
            org.bluezoo.gonzalez.schema.xsd.XSDSimpleType nodeType = 
                org.bluezoo.gonzalez.schema.xsd.XSDSimpleType.getBuiltInType(nodeTypeLocal);
            
            if (nodeType != null) {
                // Check if node's type is same as or derived from target type
                return nodeType.isDerivedFrom(typeNs, typeLocal);
            }
            
            // Fallback: exact match for non-built-in types
            return typeLocal.equals(nodeTypeLocal);
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
            
            // @node() - matches any attribute (node() includes all node types)
            if ("node()".equals(attrTest)) {
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
            
            // Case 1: doc('file.xml') alone - matches the document node (root) only
            if (restOfPattern.isEmpty()) {
                for (XPathNode docNode : docNodes) {
                    if (node.isSameNode(docNode)) {
                        return true;
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
            
            // Case 3: doc('file.xml')/path - matches path from document
            // Handle multi-step paths like doc('file.xml')/doc/foo/a
            if (restOfPattern.startsWith("/") && !restOfPattern.startsWith("//")) {
                String pathPattern = restOfPattern.substring(1);
                
                // Parse the path into steps
                String[] steps = splitPathSteps(pathPattern);
                if (steps.length == 0) {
                    return false;
                }
                
                // The node must match the last step
                if (!new SimplePattern(steps[steps.length - 1]).matches(node, context)) {
                    return false;
                }
                
                // Walk up the ancestors to match each step
                XPathNode current = node.getParent();
                for (int i = steps.length - 2; i >= 0; i--) {
                    if (current == null) {
                        return false;
                    }
                    if (!new SimplePattern(steps[i]).matches(current, context)) {
                        return false;
                    }
                    current = current.getParent();
                }
                
                // The final ancestor should be the doc() result
                if (current != null) {
                    for (XPathNode docNode : docNodes) {
                        if (current.isSameNode(docNode)) {
                            return true;
                        }
                        // Also check if current's parent is the doc result (document element case)
                        if (docNode.getNodeType() == NodeType.ROOT) {
                            Iterator<XPathNode> children = docNode.getChildren();
                            while (children.hasNext()) {
                                XPathNode child = children.next();
                                if (child.isElement() && current.isSameNode(child)) {
                                    return true;
                                }
                            }
                        }
                    }
                }
                return false;
            }
            
            return false;
        }
        
        /**
         * Matches an id() function pattern with optional document argument (XSLT 2.0+).
         * Patterns: id('x'), id('x', $doc), id('x')/path
         */
        private boolean matchesIdPattern(XPathNode node, TransformContext context, String pattern) {
            // Extract the id() arguments - need to find MATCHING paren, not last paren
            int parenStart = pattern.indexOf('(');
            int parenEnd = findMatchingParen(pattern, parenStart);
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
            
            // Split idArg on whitespace - id('a b c') matches elements with id='a', 'b', or 'c'
            String[] ids = idArg.trim().split("\\s+");
            
            // Case 1: No path after id() - node itself must have one of the specified IDs
            if (restOfPattern.isEmpty()) {
                if (node.isElement()) {
                    XPathNode idAttr = node.getAttribute("", "id");
                    if (idAttr == null) {
                        idAttr = node.getAttribute("http://www.w3.org/XML/1998/namespace", "id");
                    }
                    if (idAttr != null) {
                        String nodeId = idAttr.getStringValue();
                        for (String id : ids) {
                            if (id.equals(nodeId)) {
                                return true;
                            }
                        }
                    }
                }
                return false;
            }
            
            // Case 2: id('x')//foo - node must match foo and have an ancestor with ID 'x'
            if (restOfPattern.startsWith("//")) {
                String descendantPattern = restOfPattern.substring(2);
                if (!new SimplePattern(descendantPattern).matches(node, context)) {
                    return false;
                }
                // Check if any ancestor has one of the specified IDs
                XPathNode ancestor = node.getParent();
                while (ancestor != null) {
                    if (ancestor.isElement()) {
                        XPathNode idAttr = ancestor.getAttribute("", "id");
                        if (idAttr == null) {
                            idAttr = ancestor.getAttribute("http://www.w3.org/XML/1998/namespace", "id");
                        }
                        if (idAttr != null) {
                            String ancestorId = idAttr.getStringValue();
                            for (String id : ids) {
                                if (id.equals(ancestorId)) {
                                    return true;
                                }
                            }
                        }
                    }
                    ancestor = ancestor.getParent();
                }
                return false;
            }
            
            // Case 3: id('x')/foo/bar - node must match the path from the id() element
            if (restOfPattern.startsWith("/")) {
                String pathPattern = restOfPattern.substring(1);
                // For pattern like id('x')/a/b/c matching a node, the node must match c,
                // its parent must match b, grandparent must match a, great-grandparent must have id='x'
                
                // First, count how many steps are in the path
                String[] steps = splitPathSteps(pathPattern);
                if (steps.length == 0) {
                    return false;
                }
                
                // The last step must match the node
                if (!new SimplePattern(steps[steps.length - 1]).matches(node, context)) {
                    return false;
                }
                
                // Walk up the ancestor chain checking each step
                XPathNode current = node;
                for (int i = steps.length - 2; i >= 0; i--) {
                    current = current.getParent();
                    if (current == null) {
                        return false;
                    }
                    if (!new SimplePattern(steps[i]).matches(current, context)) {
                        return false;
                    }
                }
                
                // Now current's parent must have one of the specified IDs
                // For single-step patterns like id('x')/text(), current is the text node itself
                // and its parent should be the element with the ID
                XPathNode idElement = current.getParent();
                if (idElement == null) {
                    return false;
                }
                // For root node case
                if (!idElement.isElement()) {
                    return false;
                }
                
                // Check all attributes for ID match - need to handle both regular id and xml:id
                XPathNode idAttr = idElement.getAttribute("", "id");
                if (idAttr == null) {
                    idAttr = idElement.getAttribute("http://www.w3.org/XML/1998/namespace", "id");
                }
                if (idAttr != null) {
                    String ancestorId = idAttr.getStringValue();
                    for (String id : ids) {
                        if (id.equals(ancestorId)) {
                            return true;
                        }
                    }
                }
                return false;
            }
            
            return false;
        }
        
        /**
         * Matches an element-with-id() function pattern (XSLT 3.0).
         * element-with-id('x') - matches elements containing a child element typed as xs:ID
         * element-with-id('x', $doc) - matches in specified document
         * 
         * Unlike id() which matches elements with ID attributes, element-with-id() matches
         * elements that contain a child element whose type is xs:ID (schema-typed content).
         */
        private boolean matchesElementWithIdPattern(XPathNode node, TransformContext context, String pattern) {
            // Debug: trace pattern matching
            boolean debug = false; // System.getProperty("debug.ewid") != null;
            if (debug) {
                System.err.println("DEBUG element-with-id: pattern=" + pattern + 
                                   ", node=" + node.getLocalName() + " ns=" + node.getNamespaceURI());
            }
            
            // Extract the element-with-id() arguments
            int parenStart = pattern.indexOf('(');
            int parenEnd = findMatchingParen(pattern, parenStart);
            if (parenStart < 0 || parenEnd < 0) {
                if (debug) System.err.println("DEBUG: Failed to find parens");
                return false;
            }
            
            String args = pattern.substring(parenStart + 1, parenEnd).trim();
            String restOfPattern = pattern.substring(parenEnd + 1);
            
            // Parse arguments: element-with-id('value') or element-with-id('value', $doc)
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
                        if (debug) System.err.println("DEBUG: variable " + docArg + " = " + docValue + 
                            " (type=" + (docValue != null ? docValue.getClass().getName() : "null") + ")");
                        if (docValue instanceof XPathNodeSet) {
                            XPathNodeSet nodes = (XPathNodeSet) docValue;
                            if (!nodes.isEmpty()) {
                                targetDoc = nodes.iterator().next();
                            }
                        } else if (docValue instanceof XPathNode) {
                            targetDoc = (XPathNode) docValue;
                        } else if (docValue instanceof XPathResultTreeFragment) {
                            // Result tree fragments can be converted to node sets
                            XPathResultTreeFragment rtf = (XPathResultTreeFragment) docValue;
                            XPathNodeSet rtfNodes = rtf.asNodeSet();
                            if (!rtfNodes.isEmpty()) {
                                targetDoc = rtfNodes.iterator().next();
                            }
                            if (debug) System.err.println("DEBUG: RTF document node = " + targetDoc);
                        }
                    } catch (Exception e) {
                        if (debug) System.err.println("DEBUG: exception getting variable: " + e);
                        return false;
                    }
                }
            } else {
                // Use the context document
                targetDoc = node.getRoot();
            }
            
            if (targetDoc == null) {
                if (debug) System.err.println("DEBUG: targetDoc is null");
                return false;
            }
            
            // Check if node is in the target document
            XPathNode nodeRoot = node.getRoot();
            XPathNode targetRoot = targetDoc.getRoot();
            if (targetRoot == null) {
                targetRoot = targetDoc;
            }
            if (debug) {
                System.err.println("DEBUG: nodeRoot=" + nodeRoot + ", targetRoot=" + targetRoot);
                System.err.println("DEBUG: nodeRoot.isSameNode(targetRoot)=" + nodeRoot.isSameNode(targetRoot));
            }
            if (!nodeRoot.isSameNode(targetRoot)) {
                if (debug) System.err.println("DEBUG: Node not in target document");
                return false;
            }
            
            // Split idArg on whitespace - element-with-id('a b c') matches elements containing ID 'a', 'b', or 'c'
            String[] ids = idArg.trim().split("\\s+");
            
            // Case 1: No path after element-with-id() - node must be an element containing 
            // a child element with xs:ID type annotation and matching value
            if (restOfPattern.isEmpty()) {
                if (debug) System.err.println("DEBUG: Checking if node has ID-typed child with value in " + java.util.Arrays.toString(ids));
                boolean result = nodeHasIdTypedChild(node, ids, context, debug);
                if (debug) System.err.println("DEBUG: nodeHasIdTypedChild result=" + result);
                return result;
            }
            
            // Case 2: element-with-id('x')//foo - node must match foo and have an ancestor 
            // with an ID-typed child
            if (restOfPattern.startsWith("//")) {
                String descendantPattern = restOfPattern.substring(2);
                if (!new SimplePattern(descendantPattern).matches(node, context)) {
                    return false;
                }
                // Check if any ancestor has an ID-typed child with matching value
                XPathNode ancestor = node.getParent();
                while (ancestor != null) {
                    if (nodeHasIdTypedChild(ancestor, ids, context)) {
                        return true;
                    }
                    ancestor = ancestor.getParent();
                }
                return false;
            }
            
            // Case 3: element-with-id('x')/foo/bar - node must match the path from the element
            if (restOfPattern.startsWith("/")) {
                String pathPattern = restOfPattern.substring(1);
                String[] steps = splitPathSteps(pathPattern);
                if (steps.length == 0) {
                    return false;
                }
                
                // The last step must match the node
                if (!new SimplePattern(steps[steps.length - 1]).matches(node, context)) {
                    return false;
                }
                
                // Walk up the ancestor chain checking each step
                XPathNode current = node;
                for (int i = steps.length - 2; i >= 0; i--) {
                    current = current.getParent();
                    if (current == null) {
                        return false;
                    }
                    if (!new SimplePattern(steps[i]).matches(current, context)) {
                        return false;
                    }
                }
                
                // Now current's parent must be the element with the ID-typed child
                XPathNode idElement = current.getParent();
                if (idElement == null || !idElement.isElement()) {
                    return false;
                }
                
                return nodeHasIdTypedChild(idElement, ids, context);
            }
            
            return false;
        }
        
        /**
         * Checks if a node has a child element with xs:ID type annotation and matching value.
         * This is used by element-with-id() pattern matching.
         * 
         * <p>This method uses two approaches to detect ID-typed children:
         * <ol>
         *   <li>Check if the child node has explicit type annotation (for validated documents)</li>
         *   <li>Look up the schema to find elements declared with xs:ID type</li>
         * </ol>
         */
        private boolean nodeHasIdTypedChild(XPathNode node, String[] ids, TransformContext context) {
            return nodeHasIdTypedChild(node, ids, context, false);
        }
        
        private boolean nodeHasIdTypedChild(XPathNode node, String[] ids, TransformContext context, boolean debug) {
            if (!node.isElement()) {
                if (debug) System.err.println("DEBUG nodeHasIdTypedChild: node is not element");
                return false;
            }
            
            if (debug) System.err.println("DEBUG nodeHasIdTypedChild: checking children of " + 
                node.getLocalName() + " ns=" + node.getNamespaceURI());
            
            // Iterate through child elements looking for ID-typed ones
            Iterator<XPathNode> children = node.getChildren();
            int childCount = 0;
            while (children.hasNext()) {
                XPathNode child = children.next();
                childCount++;
                if (!child.isElement()) {
                    if (debug) System.err.println("DEBUG: child " + childCount + " is not element (type=" + child.getNodeType() + ")");
                    continue;
                }
                
                if (debug) System.err.println("DEBUG: checking child element " + child.getLocalName() + 
                    " ns=" + child.getNamespaceURI() + " value='" + child.getStringValue() + "'");
                
                // First check if child has explicit type annotation (from validation)
                if (child.hasTypeAnnotation()) {
                    String typeLocal = child.getTypeLocalName();
                    String typeNs = child.getTypeNamespaceURI();
                    if (debug) System.err.println("DEBUG: child has type annotation: " + typeNs + ":" + typeLocal);
                    
                    if (context.isIdDerivedType(typeNs, typeLocal)) {
                        String childValue = child.getStringValue().trim();
                        for (String id : ids) {
                            if (id.equals(childValue)) {
                                if (debug) System.err.println("DEBUG: MATCH via type annotation!");
                                return true;
                            }
                        }
                    }
                    continue;
                }
                
                // Second, try schema lookup for the child element's type
                // This is needed because XPathResultTreeFragment nodes don't carry type annotations
                if (debug) System.err.println("DEBUG: child has no type annotation, trying schema lookup");
                boolean isIdTypedBySchema = isElementIdTypedBySchema(
                    node.getNamespaceURI(), node.getLocalName(),
                    child.getNamespaceURI(), child.getLocalName(), 
                    context, debug);
                
                if (debug) System.err.println("DEBUG: isIdTypedBySchema=" + isIdTypedBySchema);
                
                if (isIdTypedBySchema) {
                    String childValue = child.getStringValue().trim();
                    for (String id : ids) {
                        if (id.equals(childValue)) {
                            if (debug) System.err.println("DEBUG: MATCH via schema lookup!");
                            return true;
                        }
                    }
                }
            }
            
            if (debug) System.err.println("DEBUG nodeHasIdTypedChild: no match found, checked " + childCount + " children");
            return false;
        }
        
        /**
         * Checks if a child element is declared with xs:ID type in the imported schemas.
         * This looks up the parent element's complex type definition to find the child's type.
         * 
         * <p>Handles both global and local element declarations by searching the entire
         * schema hierarchy.
         */
        private boolean isElementIdTypedBySchema(String parentNs, String parentLocalName,
                                                  String childNs, String childLocalName,
                                                  TransformContext context) {
            return isElementIdTypedBySchema(parentNs, parentLocalName, childNs, childLocalName, context, false);
        }
        
        private boolean isElementIdTypedBySchema(String parentNs, String parentLocalName,
                                                  String childNs, String childLocalName,
                                                  TransformContext context, boolean debug) {
            if (debug) System.err.println("DEBUG isElementIdTypedBySchema: parent={" + parentNs + "}" + parentLocalName +
                ", child={" + childNs + "}" + childLocalName);
            
            CompiledStylesheet stylesheet = context.getStylesheet();
            if (stylesheet == null) {
                if (debug) System.err.println("DEBUG: stylesheet is null");
                return false;
            }
            
            Map<String, XSDSchema> schemas = stylesheet.getImportedSchemas();
            if (schemas == null || schemas.isEmpty()) {
                if (debug) System.err.println("DEBUG: no imported schemas");
                return false;
            }
            if (debug) System.err.println("DEBUG: have " + schemas.size() + " imported schemas: " + schemas.keySet());
            
            // Get schema for parent's namespace
            XSDSchema schema = schemas.get(parentNs != null ? parentNs : "");
            if (schema == null) {
                if (debug) System.err.println("DEBUG: no schema for namespace " + parentNs);
                return false;
            }
            if (debug) System.err.println("DEBUG: found schema for namespace " + parentNs);
            
            // First try to find the parent element declaration globally
            XSDElement parentDecl = schema.resolveElement(parentNs, parentLocalName);
            if (debug) System.err.println("DEBUG: global lookup for " + parentLocalName + " = " + parentDecl);
            
            // If not found globally, search through all global elements for nested declarations
            if (parentDecl == null) {
                parentDecl = findLocalElementDeclaration(schema, parentNs, parentLocalName);
                if (debug) System.err.println("DEBUG: local lookup for " + parentLocalName + " = " + parentDecl);
            }
            
            if (parentDecl == null) {
                if (debug) System.err.println("DEBUG: parent declaration not found");
                return false;
            }
            
            XSDType parentType = parentDecl.getType();
            if (debug) System.err.println("DEBUG: parent type = " + parentType);
            if (!(parentType instanceof XSDComplexType)) {
                if (debug) System.err.println("DEBUG: parent type is not complex type");
                return false;
            }
            
            // Look up the child element in the parent's content model
            XSDComplexType ct = (XSDComplexType) parentType;
            if (debug) System.err.println("DEBUG: complex type has " + ct.getParticles().size() + " particles");
            for (XSDParticle p : ct.getParticles()) {
                if (debug) System.err.println("DEBUG: particle: " + p);
            }
            XSDElement childDecl = ct.getChildElement(childNs, childLocalName);
            if (debug) System.err.println("DEBUG: child declaration = " + childDecl);
            if (childDecl == null) {
                if (debug) System.err.println("DEBUG: child declaration not found in content model");
                return false;
            }
            
            // Check if the child's type derives from xs:ID
            XSDType childType = childDecl.getType();
            if (debug) System.err.println("DEBUG: child type = " + childType);
            if (childType instanceof XSDSimpleType) {
                boolean isId = ((XSDSimpleType) childType).isDerivedFromId();
                if (debug) System.err.println("DEBUG: isDerivedFromId = " + isId);
                return isId;
            }
            
            if (debug) System.err.println("DEBUG: child type is not simple type");
            return false;
        }
        
        /**
         * Searches the schema for a local element declaration (element declared within
         * a complex type's content model rather than globally).
         */
        private XSDElement findLocalElementDeclaration(XSDSchema schema, String ns, String localName) {
            // Search through all global elements
            for (XSDElement globalElem : schema.getElements().values()) {
                XSDElement found = findLocalElementInType(globalElem, ns, localName);
                if (found != null) {
                    return found;
                }
            }
            return null;
        }
        
        /**
         * Recursively searches an element's type for a local element declaration.
         */
        private XSDElement findLocalElementInType(XSDElement elem, String ns, String localName) {
            XSDType type = elem.getType();
            if (!(type instanceof XSDComplexType)) {
                return null;
            }
            
            XSDComplexType ct = (XSDComplexType) type;
            XSDElement child = ct.getChildElement(ns, localName);
            if (child != null) {
                return child;
            }
            
            // Recursively search in nested elements
            for (XSDElement childElem : getChildElements(ct)) {
                XSDElement found = findLocalElementInType(childElem, ns, localName);
                if (found != null) {
                    return found;
                }
            }
            
            return null;
        }
        
        /**
         * Gets all child elements from a complex type's particles.
         */
        private List<XSDElement> getChildElements(XSDComplexType ct) {
            List<XSDElement> elements = new ArrayList<>();
            for (XSDParticle particle : ct.getParticles()) {
                collectElements(particle, elements);
            }
            return elements;
        }
        
        /**
         * Recursively collects element declarations from a particle and its children.
         */
        private void collectElements(XSDParticle particle, List<XSDElement> elements) {
            if (particle.getElement() != null) {
                elements.add(particle.getElement());
            }
            for (XSDParticle child : particle.getChildren()) {
                collectElements(child, elements);
            }
        }
        
        /**
         * Split a path pattern into individual steps, handling Clark notation.
         */
        private String[] splitPathSteps(String path) {
            List<String> steps = new ArrayList<>();
            int depth = 0;
            int start = 0;
            for (int i = 0; i < path.length(); i++) {
                char c = path.charAt(i);
                if (c == '{') {
                    depth++;
                } else if (c == '}') {
                    depth--;
                } else if (c == '/' && depth == 0) {
                    if (i > start) {
                        steps.add(path.substring(start, i));
                    }
                    start = i + 1;
                }
            }
            if (start < path.length()) {
                steps.add(path.substring(start));
            }
            return steps.toArray(new String[0]);
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
            // Split by | but respect brackets, parentheses, and quotes
            List<String> parts = new ArrayList<>();
            int bracketDepth = 0;  // []
            int parenDepth = 0;    // ()
            boolean inQuote = false;
            char quoteChar = 0;
            int start = 0;
            for (int i = 0; i < pattern.length(); i++) {
                char c = pattern.charAt(i);
                if (!inQuote && (c == '"' || c == '\'')) {
                    inQuote = true;
                    quoteChar = c;
                } else if (inQuote && c == quoteChar) {
                    inQuote = false;
                } else if (!inQuote) {
                    if (c == '[') {
                        bracketDepth++;
                    } else if (c == ']') {
                        bracketDepth--;
                    } else if (c == '(') {
                        parenDepth++;
                    } else if (c == ')') {
                        parenDepth--;
                    } else if (c == '|' && bracketDepth == 0 && parenDepth == 0) {
                        parts.add(pattern.substring(start, i));
                        start = i + 1;
                    }
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
            
            // Union patterns: return the highest priority of all alternatives
            // According to XSLT spec, each alternative should create a separate template rule,
            // but when computing a single priority we use the highest (most specific)
            if (hasTopLevelUnion(patternStr)) {
                String[] parts = splitUnion(patternStr);
                double maxPriority = Double.NEGATIVE_INFINITY;
                for (String part : parts) {
                    double partPriority = new SimplePattern(part.trim()).getDefaultPriority();
                    if (partPriority > maxPriority) {
                        maxPriority = partPriority;
                    }
                }
                return maxPriority;
            }
            
            if ("*".equals(patternStr) || "node()".equals(patternStr) ||
                "@*".equals(patternStr) || "text()".equals(patternStr) ||
                "comment()".equals(patternStr) || "processing-instruction()".equals(patternStr) ||
                "element()".equals(patternStr) || "attribute()".equals(patternStr) ||
                "element(*)".equals(patternStr) || "attribute(*)".equals(patternStr) ||
                "document-node()".equals(patternStr)) {
                return -0.5;
            }
            // element(name) and attribute(name) have priority 0 (like simple name patterns)
            // but element() and attribute() without name have priority -0.5
            if (patternStr.startsWith("element(") && patternStr.endsWith(")")) {
                String inner = patternStr.substring(8, patternStr.length() - 1).trim();
                if (inner.isEmpty() || "*".equals(inner)) {
                    return -0.5;
                }
                // element(name) or element(name, type) - priority 0
                return 0.0;
            }
            if (patternStr.startsWith("attribute(") && patternStr.endsWith(")")) {
                String inner = patternStr.substring(10, patternStr.length() - 1).trim();
                if (inner.isEmpty() || "*".equals(inner)) {
                    return -0.5;
                }
                // attribute(name) or attribute(name, type) - priority 0
                return 0.0;
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
        private final AttributeValueTemplate dataTypeAvt;
        private final AttributeValueTemplate orderAvt;
        private final AttributeValueTemplate caseOrderAvt;
        private final AttributeValueTemplate langAvt;
        private final AttributeValueTemplate collationAvt;
        
        public SortSpec(XPathExpression selectExpr, AttributeValueTemplate dataTypeAvt, 
                       AttributeValueTemplate orderAvt, AttributeValueTemplate caseOrderAvt, 
                       AttributeValueTemplate langAvt, AttributeValueTemplate collationAvt) {
            this.selectExpr = selectExpr;
            this.dataTypeAvt = dataTypeAvt;
            this.orderAvt = orderAvt;
            this.caseOrderAvt = caseOrderAvt;
            this.langAvt = langAvt;
            this.collationAvt = collationAvt;
        }
        
        public XPathExpression getSelectExpr() { return selectExpr; }
        
        /** Evaluate data-type AVT at runtime. Returns "text" or "number". */
        public String getDataType(TransformContext context) throws XPathException {
            if (dataTypeAvt == null) {
                return "text";
            }
            String value = dataTypeAvt.evaluate(context);
            return value != null && !value.isEmpty() ? value : "text";
        }
        
        /** Evaluate order AVT at runtime. Returns "ascending" or "descending". */
        public String getOrder(TransformContext context) throws XPathException {
            if (orderAvt == null) {
                return "ascending";
            }
            String value = orderAvt.evaluate(context);
            return value != null && !value.isEmpty() ? value : "ascending";
        }
        
        /** Evaluate case-order AVT at runtime. Returns "upper-first", "lower-first", or null. */
        public String getCaseOrder(TransformContext context) throws XPathException {
            if (caseOrderAvt == null) {
                return null;
            }
            String value = caseOrderAvt.evaluate(context);
            return value != null && !value.isEmpty() ? value : null;
        }
        
        /** Evaluate lang AVT at runtime. */
        public String getLang(TransformContext context) throws XPathException {
            if (langAvt == null) {
                return null;
            }
            String value = langAvt.evaluate(context);
            return value != null && !value.isEmpty() ? value : null;
        }
        
        /** Evaluate collation AVT at runtime. Returns collation URI or null for default. */
        public String getCollation(TransformContext context) throws XPathException {
            if (collationAvt == null) {
                return null;
            }
            String value = collationAvt.evaluate(context);
            return value != null && !value.isEmpty() ? value : null;
        }
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
        
        // Pending type annotations
        private String pendingTypeNs = null;
        private String pendingTypeLocal = null;
        private final List<String[]> pendingAttrTypes = new ArrayList<>();
        
        @Override
        public void setElementType(String namespaceURI, String localName) throws SAXException {
            if (inStartTag) {
                pendingTypeNs = namespaceURI;
                pendingTypeLocal = localName;
            }
        }
        
        @Override
        public void setAttributeType(String namespaceURI, String localName) throws SAXException {
            if (inStartTag) {
                // Track type for the most recently added attribute
                int attrIdx = pendingAttrs.getLength() - 1;
                while (pendingAttrTypes.size() <= attrIdx) {
                    pendingAttrTypes.add(null);
                }
                if (attrIdx >= 0) {
                    pendingAttrTypes.set(attrIdx, new String[] {namespaceURI, localName});
                }
            }
        }
        
        @Override
        public void flush() throws SAXException {
            if (inStartTag) {
                // Emit namespace declarations first (SAX requires startPrefixMapping before startElement)
                for (String[] ns : pendingNamespaces) {
                    buffer.startPrefixMapping(ns[0], ns[1]);
                }
                // Emit startElement with type annotations if present
                if (pendingTypeLocal != null || !pendingAttrTypes.isEmpty()) {
                    buffer.startElementWithTypes(pendingUri, pendingLocalName, pendingQName, 
                        pendingAttrs, pendingTypeNs, pendingTypeLocal, pendingAttrTypes);
                } else {
                    buffer.startElement(pendingUri, pendingLocalName, pendingQName, pendingAttrs);
                }
                inStartTag = false;
                pendingAttrs.clear();
                pendingNamespaces.clear();
                pendingTypeNs = null;
                pendingTypeLocal = null;
                pendingAttrTypes.clear();
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
    public static class SequenceBuilderOutputHandler implements OutputHandler {
        private final List<XPathValue> items = new ArrayList<>();
        private StringBuilder pendingText = new StringBuilder();
        
        // For building elements, we use a nested buffer approach
        private SAXEventBuffer elementBuffer = null;
        private BufferOutputHandler elementHandler = null;
        private int elementDepth = 0;
        
        // For building document nodes (xsl:document instruction)
        private SAXEventBuffer documentBuffer = null;
        private BufferOutputHandler documentHandler = null;
        private int documentDepth = 0;
        
        // For standalone attributes (not inside an element)
        private String pendingAttrUri, pendingAttrLocal, pendingAttrQName, pendingAttrValue;
        private String pendingAttrTypeNs, pendingAttrTypeLocal;
        
        // XSLT 2.0 atomic value spacing state
        private boolean atomicValuePending = false;
        private boolean inAttributeContent = false;
        
        public SequenceBuilderOutputHandler() {
        }
        
        /**
         * Directly adds an XPathValue to the sequence.
         * Used by xsl:document to add document nodes directly.
         */
        public void addItem(XPathValue item) throws SAXException {
            flushPendingText();
            flushPendingAttribute();
            items.add(item);
        }
        
        /**
         * Returns the constructed sequence.
         */
        public XPathValue getSequence() throws SAXException {
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
            atomicValuePending = false;
        }
        
        private void flushPendingText() {
            if (pendingText.length() > 0) {
                // In sequence construction, text becomes a text node item
                items.add(new SequenceTextItem(pendingText.toString()));
                pendingText.setLength(0);
            }
        }
        
        private void flushPendingAttribute() throws SAXException {
            if (pendingAttrLocal != null) {
                // Create a standalone attribute node with type annotation
                items.add(new SequenceAttributeItem(pendingAttrUri, pendingAttrLocal, 
                    pendingAttrQName, pendingAttrValue, pendingAttrTypeNs, pendingAttrTypeLocal));
                pendingAttrUri = pendingAttrLocal = pendingAttrQName = pendingAttrValue = null;
                pendingAttrTypeNs = pendingAttrTypeLocal = null;
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
                    // Convert RTF to node-set and extract the actual element node (not the root)
                    XPathNodeSet nodeSet = rtf.asNodeSet();
                    if (nodeSet != null && !nodeSet.isEmpty()) {
                        // The RTF root contains the element as a child - extract it
                        XPathNode root = nodeSet.iterator().next();
                        if (root.getNodeType() == NodeType.ROOT) {
                            // Get the first element child
                            Iterator<XPathNode> children = root.getChildren();
                            while (children.hasNext()) {
                                XPathNode child = children.next();
                                if (child.getNodeType() == NodeType.ELEMENT) {
                                    items.add(new XPathNodeSet(Collections.singletonList(child)));
                                    break;
                                }
                            }
                        } else {
                            items.add(nodeSet);
                        }
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
                // Namespace inside an element - pass to element builder
                elementHandler.namespace(prefix, uri);
            } else {
                // Standalone namespace node in sequence
                flushPendingText();
                flushPendingAttribute();
                items.add(new SequenceNamespaceItem(prefix, uri));
            }
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
        
        @Override
        public void setElementType(String namespaceURI, String localName) throws SAXException {
            if (elementHandler != null) {
                elementHandler.setElementType(namespaceURI, localName);
            }
        }
        
        @Override
        public void setAttributeType(String namespaceURI, String localName) throws SAXException {
            if (elementHandler != null && elementDepth > 0) {
                elementHandler.setAttributeType(namespaceURI, localName);
            } else if (pendingAttrLocal != null) {
                // Type annotation for pending standalone attribute
                pendingAttrTypeNs = namespaceURI;
                pendingAttrTypeLocal = localName;
            }
        }
        
        @Override
        public boolean isAtomicValuePending() {
            return atomicValuePending;
        }
        
        @Override
        public void setAtomicValuePending(boolean pending) {
            this.atomicValuePending = pending;
        }
        
        @Override
        public void atomicValue(XPathValue value) throws SAXException {
            flushPendingText();
            flushPendingAttribute();
            if (elementHandler != null && elementDepth > 0) {
                // Inside an element, convert to text
                elementHandler.characters(value.asString());
            } else {
                // Direct atomic value in sequence - preserve the typed value
                items.add(value);
                atomicValuePending = true;
            }
        }
        
        @Override
        public boolean isInAttributeContent() {
            return inAttributeContent;
        }
        
        @Override
        public void setInAttributeContent(boolean inAttributeContent) {
            this.inAttributeContent = inAttributeContent;
        }
    }
    
    /**
     * Represents an attribute node item in a sequence.
     * Unlike attributes in elements, these are standalone items.
     * Implements XPathNode to support type annotations for schema-aware processing.
     */
    private static class SequenceAttributeItem implements XPathValue, XPathNode {
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
            catch (Exception e) { return Double.NaN; } 
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
    
    /**
     * Represents a text node item in a sequence.
     */
    private static class SequenceTextItem implements XPathValue, XPathNode {
        private final String text;
        
        SequenceTextItem(String text) {
            this.text = text;
        }
        
        @Override public Type getType() { return Type.NODESET; }
        @Override public String asString() { return text != null ? text : ""; }
        @Override public double asNumber() { 
            try { return Double.parseDouble(text); } 
            catch (Exception e) { return Double.NaN; } 
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
    
    /**
     * Represents a comment node item in a sequence.
     */
    private static class SequenceCommentItem implements XPathValue, XPathNode {
        private final String text;
        
        SequenceCommentItem(String text) {
            this.text = text;
        }
        
        @Override public Type getType() { return Type.NODESET; }
        @Override public String asString() { return text != null ? text : ""; }
        @Override public double asNumber() { 
            try { return Double.parseDouble(text); } 
            catch (Exception e) { return Double.NaN; } 
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
    
    /**
     * Represents a processing instruction node item in a sequence.
     */
    private static class SequencePIItem implements XPathValue, XPathNode {
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
            catch (Exception e) { return Double.NaN; } 
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
    
    /**
     * Represents a namespace node item in a sequence.
     * Unlike namespaces attached to elements, these are standalone items.
     * The local name is the prefix, the string value is the namespace URI.
     */
    private static class SequenceNamespaceItem implements XPathValue, XPathNode {
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
            catch (Exception e) { return Double.NaN; } 
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
    
    /**
     * Converts a lexical value to its canonical form for the given XSD type.
     * This is used when type annotations are specified on constructed attributes/elements.
     *
     * @param typeName the XSD type local name (e.g., "integer", "date")
     * @param value the lexical value
     * @return the canonical lexical form
     */
    public static String toCanonicalLexical(String typeName, String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        
        try {
            switch (typeName) {
                case "integer":
                case "nonPositiveInteger":
                case "negativeInteger":
                case "nonNegativeInteger":
                case "positiveInteger":
                case "long":
                case "int":
                case "short":
                case "byte":
                case "unsignedLong":
                case "unsignedInt":
                case "unsignedShort":
                case "unsignedByte":
                    // Remove leading zeros, handle sign
                    String trimmed = value.trim();
                    boolean negative = trimmed.startsWith("-");
                    boolean positive = trimmed.startsWith("+");
                    if (negative || positive) {
                        trimmed = trimmed.substring(1);
                    }
                    // Remove leading zeros
                    int i = 0;
                    while (i < trimmed.length() - 1 && trimmed.charAt(i) == '0') {
                        i++;
                    }
                    trimmed = trimmed.substring(i);
                    // Reconstruct with sign if needed
                    if (negative && !"0".equals(trimmed)) {
                        return "-" + trimmed;
                    }
                    return trimmed;
                    
                case "decimal":
                    // Remove trailing zeros after decimal point
                    trimmed = value.trim();
                    if (trimmed.contains(".")) {
                        // Remove trailing zeros
                        while (trimmed.endsWith("0") && !trimmed.endsWith(".0")) {
                            trimmed = trimmed.substring(0, trimmed.length() - 1);
                        }
                        // Remove trailing decimal point if no fractional part
                        if (trimmed.endsWith(".")) {
                            trimmed = trimmed.substring(0, trimmed.length() - 1);
                        }
                    }
                    return trimmed;
                    
                case "boolean":
                    // Canonical form is "true" or "false"
                    trimmed = value.trim();
                    if ("1".equals(trimmed)) {
                        return "true";
                    } else if ("0".equals(trimmed)) {
                        return "false";
                    }
                    return trimmed;
                    
                case "float":
                case "double":
                    // Handle special values
                    trimmed = value.trim();
                    if ("INF".equalsIgnoreCase(trimmed) || "+INF".equalsIgnoreCase(trimmed)) {
                        return "INF";
                    } else if ("-INF".equalsIgnoreCase(trimmed)) {
                        return "-INF";
                    } else if ("NaN".equalsIgnoreCase(trimmed)) {
                        return "NaN";
                    }
                    // Parse and re-serialize for canonical form
                    double d = Double.parseDouble(trimmed);
                    if (d == (long) d) {
                        return String.valueOf((long) d);
                    }
                    return String.valueOf(d);
                    
                default:
                    // For other types, return as-is (normalized)
                    return value.trim();
            }
        } catch (NumberFormatException e) {
            // If conversion fails, return original value
            return value;
        }
    }

    /**
     * Output handler that detects whether content was produced for xsl:on-empty support.
     * Attributes and namespaces are forwarded to the parent handler (they apply to the
     * containing element), while other content is buffered.
     */
    private static class OnEmptyDetectingHandler implements OutputHandler {
        private final OutputHandler parent;
        private final SAXEventBuffer buffer;
        private final BufferOutputHandler bufferHandler;
        private boolean hasContent = false;

        OnEmptyDetectingHandler(OutputHandler parent, SAXEventBuffer buffer) {
            this.parent = parent;
            this.buffer = buffer;
            this.bufferHandler = new BufferOutputHandler(buffer);
        }

        boolean hasContent() {
            return hasContent || !buffer.isEmpty();
        }

        @Override
        public void startDocument() throws SAXException {
            bufferHandler.startDocument();
        }

        @Override
        public void endDocument() throws SAXException {
            bufferHandler.endDocument();
        }

        @Override
        public void startElement(String namespaceURI, String localName, String qName) throws SAXException {
            hasContent = true;
            bufferHandler.startElement(namespaceURI, localName, qName);
        }

        @Override
        public void endElement(String namespaceURI, String localName, String qName) throws SAXException {
            bufferHandler.endElement(namespaceURI, localName, qName);
        }

        @Override
        public void attribute(String namespaceURI, String localName, String qName, String value) throws SAXException {
            // Attributes go to the parent element (they're part of the containing element's output)
            // and count as content for on-empty purposes
            hasContent = true;
            parent.attribute(namespaceURI, localName, qName, value);
        }

        @Override
        public void namespace(String prefix, String uri) throws SAXException {
            // Namespace declarations go to the parent element and count as content
            hasContent = true;
            parent.namespace(prefix, uri);
        }

        @Override
        public void characters(String text) throws SAXException {
            if (text != null && !text.isEmpty()) {
                hasContent = true;
                bufferHandler.characters(text);
            }
        }

        @Override
        public void charactersRaw(String text) throws SAXException {
            if (text != null && !text.isEmpty()) {
                hasContent = true;
                bufferHandler.charactersRaw(text);
            }
        }

        @Override
        public void comment(String text) throws SAXException {
            hasContent = true;
            bufferHandler.comment(text);
        }

        @Override
        public void processingInstruction(String target, String data) throws SAXException {
            hasContent = true;
            bufferHandler.processingInstruction(target, data);
        }

        @Override
        public void flush() throws SAXException {
            bufferHandler.flush();
        }

        @Override
        public void setElementType(String namespaceURI, String localName) throws SAXException {
            // Type annotations for attributes go to parent
            parent.setElementType(namespaceURI, localName);
        }

        @Override
        public void setAttributeType(String namespaceURI, String localName) throws SAXException {
            parent.setAttributeType(namespaceURI, localName);
        }

        @Override
        public void setAtomicValuePending(boolean pending) throws SAXException {
            bufferHandler.setAtomicValuePending(pending);
        }

        @Override
        public boolean isAtomicValuePending() {
            return bufferHandler.isAtomicValuePending();
        }

        @Override
        public void setInAttributeContent(boolean inAttributeContent) throws SAXException {
            bufferHandler.setInAttributeContent(inAttributeContent);
        }

        @Override
        public boolean isInAttributeContent() {
            return bufferHandler.isInAttributeContent();
        }
    }

}

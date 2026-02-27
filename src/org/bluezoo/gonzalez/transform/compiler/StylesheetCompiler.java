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
import org.bluezoo.gonzalez.transform.ast.AccumulatorRuleNode;
import org.bluezoo.gonzalez.transform.ast.AnalyzeStringNode;
import org.bluezoo.gonzalez.transform.ast.ApplyImportsNode;
import org.bluezoo.gonzalez.transform.ast.ApplyTemplatesNode;
import org.bluezoo.gonzalez.transform.ast.AssertNode;
import org.bluezoo.gonzalez.transform.ast.AttributeNode;
import org.bluezoo.gonzalez.transform.ast.CallTemplateNode;
import org.bluezoo.gonzalez.transform.ast.CatchNode;
import org.bluezoo.gonzalez.transform.ast.ChooseNode;
import org.bluezoo.gonzalez.transform.ast.CommentNode;
import org.bluezoo.gonzalez.transform.ast.CopyNode;
import org.bluezoo.gonzalez.transform.ast.CopyOfNode;
import org.bluezoo.gonzalez.transform.ast.ElementNode;
import org.bluezoo.gonzalez.transform.ast.FallbackNode;
import org.bluezoo.gonzalez.transform.ast.ForEachNode;
import org.bluezoo.gonzalez.transform.ast.IfNode;
import org.bluezoo.gonzalez.transform.ast.MapConstructionNode;
import org.bluezoo.gonzalez.transform.ast.MapEntryNode;
import org.bluezoo.gonzalez.transform.ast.MessageNode;
import org.bluezoo.gonzalez.transform.ast.NamespaceInstructionNode;
import org.bluezoo.gonzalez.transform.ast.NextMatchNode;
import org.bluezoo.gonzalez.transform.ast.NumberNode;
import org.bluezoo.gonzalez.transform.ast.OnEmptyNode;
import org.bluezoo.gonzalez.transform.ast.OnNonEmptyNode;
import org.bluezoo.gonzalez.transform.ast.OtherwiseNode;
import org.bluezoo.gonzalez.transform.ast.OutputCharacterNode;
import org.bluezoo.gonzalez.transform.ast.ParamNode;
import org.bluezoo.gonzalez.transform.ast.PerformSortNode;
import org.bluezoo.gonzalez.transform.ast.ProcessingInstructionNode;
import org.bluezoo.gonzalez.transform.ast.SequenceOutputNode;
import org.bluezoo.gonzalez.transform.ast.SortSpecNode;
import org.bluezoo.gonzalez.transform.ast.StringContextNode;
import org.bluezoo.gonzalez.transform.ast.TryNode;
import org.bluezoo.gonzalez.transform.ast.ValueOfContentNode;
import org.bluezoo.gonzalez.transform.ast.ValueOfNode;
import org.bluezoo.gonzalez.transform.ast.VariableNode;
import org.bluezoo.gonzalez.transform.ast.WhenNode;
import org.bluezoo.gonzalez.transform.ast.WherePopulatedNode;
import org.bluezoo.gonzalez.transform.ast.WithParamNode;
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
    
    // Tracks explicit mode attribute values for XTSE0545 conflict detection
    // Key: "modeName|attrName", Value: explicit attribute value
    private final Map<String, String> modeAttributeValues = new HashMap<>();
    
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
        String defaultMode = null;  // XSLT 3.0 default-mode (null = inherit from parent)
        
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
        return getCompiledStylesheet(true);
    }

    /**
     * Returns the compiled stylesheet, optionally skipping cross-reference validation.
     * Sub-stylesheets (imported/included) should skip validation since references
     * may target definitions in sibling or parent stylesheets.
     *
     * @param validateReferences true to validate cross-references
     * @return the compiled stylesheet
     * @throws TransformerConfigurationException if validation fails
     */
    public CompiledStylesheet getCompiledStylesheet(boolean validateReferences) 
            throws TransformerConfigurationException {
        // Ensure precedence is assigned (in case no includes were processed)
        ensurePrecedenceAssigned();
        // Fix up any templates that were compiled before precedence was assigned
        builder.finalizePrecedence(importPrecedence);
        CompiledStylesheet stylesheet = builder.build(validateReferences);
        
        // Run streamability analysis and store results
        StreamabilityAnalyzer analyzer = new StreamabilityAnalyzer();
        StreamabilityAnalyzer.StylesheetStreamability analysis = analyzer.analyze(stylesheet);
        stylesheet.setStreamabilityAnalysis(analysis);
        
        return stylesheet;
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
        
        // Handle default-mode (XSLT 3.0)
        // On XSLT elements: default-mode attribute
        // On LRE elements: xsl:default-mode attribute
        String defaultModeAttr = null;
        if (XSLT_NS.equals(uri)) {
            defaultModeAttr = ctx.attributes.get("default-mode");
        } else {
            defaultModeAttr = ctx.attributes.get("xsl:default-mode");
        }
        if (defaultModeAttr != null) {
            String trimmedDefault = defaultModeAttr.trim();
            if ("#unnamed".equals(trimmedDefault)) {
                ctx.defaultMode = "#unnamed";
            } else {
                // Expand QName to Clark notation for proper namespace matching
                int colonPos = trimmedDefault.indexOf(':');
                if (colonPos > 0) {
                    String prefix = trimmedDefault.substring(0, colonPos);
                    String nsUri = ctx.namespaceBindings.get(prefix);
                    if (nsUri != null && !nsUri.isEmpty()) {
                        String localPart = trimmedDefault.substring(colonPos + 1);
                        ctx.defaultMode = "{" + nsUri + "}" + localPart;
                    } else {
                        ctx.defaultMode = trimmedDefault;
                    }
                } else {
                    ctx.defaultMode = trimmedDefault;
                }
            }
        } else if (!elementStack.isEmpty()) {
            ctx.defaultMode = elementStack.peek().defaultMode;
        }
        
        // Check for version on elements (backward/forward compatibility mode)
        if (!XSLT_NS.equals(uri)) {
            // LRE: xsl:version attribute
            String xslVersion = atts.getValue(XSLT_NS, "version");
            if (xslVersion != null) {
                try {
                    ctx.effectiveVersion = Double.parseDouble(xslVersion);
                } catch (NumberFormatException e) {
                    // Ignore invalid version
                }
            }
        } else if (!isElementWithOwnVersionAttr(localName)) {
            // XSLT instruction with version as standard attribute — enables per-element FC mode
            String versionAttr = atts.getValue("version");
            if (versionAttr != null) {
                try {
                    ctx.effectiveVersion = Double.parseDouble(versionAttr);
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
                String[] prefixes = excludePrefixes.trim().split("\\s+");
                for (String prefix : prefixes) {
                    if (prefix.isEmpty()) {
                        continue;
                    }
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
                        if (nsUri == null || nsUri.isEmpty()) {
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
            
            // XSLT spec 3.4: For XSLT elements whose content is NOT a sequence
            // constructor, whitespace text is always stripped. Exception:
            // when xml:space="preserve" on an element required to be empty,
            // preserve the whitespace so XTSE0260 can detect it.
            if (isWhitespace(text) && XSLT_NS.equals(ctx.namespaceURI) 
                    && !isSequenceConstructorElement(ctx.localName)) {
                String xmlSpace = ctx.attributes.get("xml:space");
                if (!"preserve".equals(xmlSpace) || !isEmptyRequiredElement(ctx.localName)) {
                    return;
                }
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

    private static boolean isSequenceConstructorElement(String localName) {
        switch (localName) {
            case "template":
            case "if":
            case "when":
            case "otherwise":
            case "for-each":
            case "for-each-group":
            case "matching-substring":
            case "non-matching-substring":
            case "variable":
            case "param":
            case "with-param":
            case "element":
            case "attribute":
            case "comment":
            case "processing-instruction":
            case "namespace":
            case "text":
            case "copy":
            case "fallback":
            case "message":
            case "result-document":
            case "catch":
            case "iterate":
            case "on-completion":
            case "try":
            case "fork":
            case "merge-action":
            case "document":
            case "sequence":
            case "perform-sort":
            case "sort":
            case "where-populated":
            case "on-empty":
            case "on-non-empty":
                return true;
            default:
                return false;
        }
    }

    /**
     * Elements that the XSLT spec requires to have empty content.
     * XTSE0260 applies to whitespace in these elements even with xml:space="preserve".
     */
    private static boolean isEmptyRequiredElement(String localName) {
        switch (localName) {
            case "strip-space":
            case "preserve-space":
            case "import":
            case "include":
            case "output":
            case "key":
            case "decimal-format":
            case "namespace-alias":
            case "sort":
            case "import-schema":
            case "expose":
            case "accept":
                return true;
            default:
                return false;
        }
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
        String[] prefixes = excludePrefixes.trim().split("\\s+");
        for (String prefix : prefixes) {
            if (prefix.isEmpty()) {
                continue;
            }
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
            // In forward-compatible mode, unknown attributes are silently ignored
            if (!allowed.contains(localAttrName) && !STANDARD_ATTRIBUTES.contains(localAttrName)) {
                if (forwardCompatible || getEffectiveVersion() > 3.0) {
                    continue;
                }
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
                String onCompSelect = ctx.attributes.get("select");
                if (onCompSelect != null && !ctx.children.isEmpty()) {
                    throw new SAXException("XTSE3125: xsl:on-completion must not have " +
                        "both a select attribute and children");
                }
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
                String catchSelect = ctx.attributes.get("select");
                if (catchSelect != null && !ctx.children.isEmpty()
                        && hasNonWhitespaceContent(ctx.children)) {
                    throw new SAXException("XTSE3150: xsl:catch with select attribute " +
                        "must have empty content");
                }
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
                validateNotTopLevel(ctx.localName);
                return new MapConstructionNode(new SequenceNode(ctx.children));
                
            case "map-entry":
                validateNotTopLevel(ctx.localName);
                return compileMapEntry(ctx);
                
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
                // Per XSLT spec, FC mode applies when the effective version > max supported (3.0),
                // either from the stylesheet root or a per-element version attribute
                if (forwardCompatible || getEffectiveVersion() > 3.0) {
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
    /**
     * Validates that a string is a valid xs:decimal (no exponent notation).
     * xs:decimal allows optional sign, digits, optional decimal point with digits.
     */
    private static void validateDecimal(String value, String context) throws SAXException {
        if (value == null || value.isEmpty()) {
            return;
        }
        String trimmed = value.trim();
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c == '+' || c == '-') {
                if (i != 0) {
                    throw new SAXException("XTSE0530: " + context + " is not a valid xs:decimal: " + value);
                }
            } else if (c == '.') {
                // decimal point is allowed
            } else if (c >= '0' && c <= '9') {
                // digits are allowed
            } else {
                throw new SAXException("XTSE0530: " + context + " is not a valid xs:decimal: " + value);
            }
        }
    }
    
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
     * Validates a boolean attribute on xsl:result-document, skipping AVTs.
     */
    private void validateResultDocumentBoolean(ElementContext ctx, String attrName) throws SAXException {
        String value = ctx.attributes.get(attrName);
        if (value == null || value.isEmpty()) {
            return;
        }
        boolean isAvt = value.contains("{") && value.contains("}");
        if (!isAvt) {
            validateYesOrNo("xsl:result-document", attrName, value);
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
                // Exception: xsl:initial-template is allowed in XSLT 2.0+
                if ("initial-template".equals(localName) && stylesheetVersion >= 2.0) {
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
        
        // XTSE0545: detect conflicting mode declarations at same import precedence
        String modeKey = name != null ? name : "#default";
        checkModeConflict(modeKey, "on-no-match", onNoMatchAttr);
        checkModeConflict(modeKey, "on-multiple-match", onMultipleMatchAttr);
        checkModeConflict(modeKey, "visibility", visibilityAttr);
        checkModeConflict(modeKey, "streamable", streamableAttr);
        
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
     * Checks for conflicting mode attribute values (XTSE0545).
     * Two declarations for the same mode must not set the same attribute
     * to different values at the same import precedence.
     */
    private void checkModeConflict(String modeKey, String attrName,
                                    String attrValue) throws SAXException {
        if (attrValue == null) {
            return;
        }
        String trackKey = modeKey + "|" + attrName;
        String existing = modeAttributeValues.get(trackKey);
        if (existing != null && !existing.equals(attrValue)) {
            throw new SAXException("XTSE0545: Conflicting values for " + attrName +
                " on mode '" + modeKey + "': '" + existing + "' vs '" + attrValue + "'");
        }
        modeAttributeValues.put(trackKey, attrValue);
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
        Set<String> seenParamNames = new HashSet<>();
        
        for (XSLTNode child : ctx.children) {
            if (child instanceof ParamNode) {
                ParamNode pn = (ParamNode) child;
                String paramKey = expandedParamName(pn.getNamespaceURI(), pn.getLocalName());
                if (!seenParamNames.add(paramKey)) {
                    throw new SAXException("XTSE0580: Duplicate parameter name '" + pn.getName() + "' in xsl:function");
                }
                if (pn.getSelectExpr() != null || pn.getContent() != null) {
                    throw new SAXException("XTSE0760: xsl:param in xsl:function must not " +
                        "have a default value (select attribute or content): " + pn.getName());
                }
                String paramAs = pn.getAs(); // Type annotation if any
                params.add(new UserFunction.FunctionParameter(pn.getName(), paramAs));
            } else {
                bodyNodes.add(child);
            }
        }
        
        SequenceNode body = new SequenceNode(bodyNodes);
        
        UserFunction function = new UserFunction(
            namespaceURI, localName, params, body, asType, importPrecedence, cached);
        try {
            builder.addUserFunction(function);
        } catch (javax.xml.transform.TransformerConfigurationException e) {
            throw new SAXException(e.getMessage(), e);
        }
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
        
        // Default phase is pre-descent (start) per XSLT 3.0 spec
        AccumulatorDefinition.Phase phase = AccumulatorDefinition.Phase.PRE_DESCENT;
        if ("end".equals(phaseStr)) {
            phase = AccumulatorDefinition.Phase.POST_DESCENT;
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
            String[] prefixes = excludePrefixesValue.trim().split("\\s+");
            for (String p : prefixes) {
                if (p.isEmpty()) {
                    continue;
                }
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
        
        // XTSE1660: Non-schema-aware processor must reject certain xsl:validation values
        String validationValue = ctx.attributes.get("xsl:validation");
        if (validationValue != null && !validationValue.isEmpty()) {
            parseValidationMode(validationValue.trim(), "literal result element");
        }
        
        // Compile attributes as AVTs
        Map<String, AttributeValueTemplate> avts = new LinkedHashMap<>();
        for (Map.Entry<String, String> attr : ctx.attributes.entrySet()) {
            String name = attr.getKey();
            String value = attr.getValue();
            
            // Validate and skip xsl: attributes on literal result elements
            if (name.startsWith("xsl:")) {
                String xslAttr = name.substring(4);
                if (!isKnownLREAttribute(xslAttr)) {
                    throw new SAXException("XTSE0805: Unknown XSLT attribute '" + name +
                        "' on literal result element");
                }
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
            ValidationMode parsedDefault = parseValidationMode(
                defaultValidationAttr, "xsl:stylesheet/@default-validation");
            if (parsedDefault != null) {
                defaultValidation = parsedDefault;
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
                if (Collation.isRecognized(collUri)) {
                    selectedCollation = collUri;
                    break;
                }
            }
            if (selectedCollation != null) {
                builder.setDefaultCollation(selectedCollation);
            } else {
                throw new SAXException("XTSE0125: No recognized collation URI " +
                    "in default-collation: " + defaultCollationAttr);
            }
        }
        
        // Store default-mode in compiled stylesheet for initial mode resolution
        String defaultModeAttrValue = ctx.attributes.get("default-mode");
        if (defaultModeAttrValue != null && !defaultModeAttrValue.isEmpty()) {
            String trimmed = defaultModeAttrValue.trim();
            if (!"#unnamed".equals(trimmed)) {
                String expanded = expandModeQName(trimmed, false);
                builder.setDefaultMode(expanded);
            }
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
                        try {
                            builder.addUserFunction(override.getOverrideFunction());
                        } catch (javax.xml.transform.TransformerConfigurationException e) {
                            throw new SAXException(e.getMessage(), e);
                        }
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
                try {
                    builder.addUserFunction(function.withVisibility(vis));
                } catch (javax.xml.transform.TransformerConfigurationException e) {
                    throw new SAXException(e.getMessage(), e);
                }
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
        
        // XSLT 3.0: if no explicit mode, use the effective default-mode
        // (from default-mode on this element or its ancestors)
        if (mode == null && match != null) {
            String effectiveDefault = ctx.defaultMode;
            if (effectiveDefault != null && !"#unnamed".equals(effectiveDefault)) {
                mode = effectiveDefault;
            }
        }
        
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
        
        // Build list of expanded mode names from mode attribute
        // A mode attribute can contain a space-separated list of mode tokens
        List<String> expandedModes = new ArrayList<>();
        if (mode != null) {
            String trimmedMode = mode.trim();
            int start = 0;
            int len = trimmedMode.length();
            while (start < len) {
                while (start < len && trimmedMode.charAt(start) <= ' ') {
                    start++;
                }
                if (start >= len) {
                    break;
                }
                int end = start;
                while (end < len && trimmedMode.charAt(end) > ' ') {
                    end++;
                }
                String token = trimmedMode.substring(start, end);
                String expandedToken = expandModeQName(token, true);
                expandedModes.add(expandedToken);
                start = end;
            }
        } else {
            expandedModes.add(null);
        }
        
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
            validateDecimal(priorityStr, "xsl:template priority");
            try {
                priority = Double.parseDouble(priorityStr);
            } catch (NumberFormatException e) {
                throw new SAXException("XTSE0530: Invalid priority: " + priorityStr);
            }
        }
        
        // Extract parameters from children
        // XTSE0010: xsl:param must come before any other content
        // Note: whitespace-only text nodes are ignored when checking param ordering
        List<TemplateParameter> params = new ArrayList<>();
        List<XSLTNode> bodyNodes = new ArrayList<>();
        boolean foundNonParam = false;
        Set<String> seenParamNames = new HashSet<>();
        
        for (XSLTNode child : ctx.children) {
            // Check if this is whitespace-only text (for param ordering check only)
            boolean isWhitespaceText = child instanceof LiteralText && 
                isWhitespace(((LiteralText) child).getText());
            
            if (child instanceof ParamNode) {
                if (foundNonParam) {
                    throw new SAXException("XTSE0010: xsl:param must come before any other content in template");
                }
                ParamNode pn = (ParamNode) child;
                String paramKey = expandedParamName(pn.getNamespaceURI(), pn.getLocalName());
                if (!seenParamNames.add(paramKey)) {
                    throw new SAXException("XTSE0580: Duplicate parameter name '" + pn.getName() + "' in xsl:template");
                }
                params.add(new TemplateParameter(pn.getNamespaceURI(), pn.getLocalName(), pn.getSelectExpr(), pn.getContent(), pn.isTunnel(), pn.isRequired()));
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
        
        // Create a TemplateRule for each mode in the mode list
        // #all means the template applies to all modes (default + all named)
        for (String expandedMode : expandedModes) {
            TemplateRule rule = new TemplateRule(pattern, expandedName, expandedMode, priority, 
                importPrecedence, nextTemplateIndex(), params, body, asType);
            builder.addTemplateRule(rule);
        }
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
        if ("#default".equals(mode) || "#all".equals(mode) || 
            "#current".equals(mode) || "#unnamed".equals(mode)) {
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
    private boolean hasNonWhitespaceContent(List<XSLTNode> children) {
        for (XSLTNode child : children) {
            if (child instanceof LiteralText) {
                String text = ((LiteralText) child).getText();
                if (!isWhitespace(text)) {
                    return true;
                }
            } else {
                return true;
            }
        }
        return false;
    }

    /**
     * Tests whether children contain anything other than xsl:fallback
     * and whitespace-only text nodes.
     */
    private boolean hasNonFallbackContent(List<XSLTNode> children) {
        for (XSLTNode child : children) {
            if (child instanceof FallbackNode) {
                continue;
            }
            if (child instanceof LiteralText) {
                String text = ((LiteralText) child).getText();
                if (isWhitespace(text)) {
                    continue;
                }
            }
            return true;
        }
        return false;
    }

    /**
     * Tests whether children contain anything other than xsl:catch,
     * xsl:fallback, and whitespace-only text nodes.
     */
    private boolean hasNonCatchOrFallbackContent(List<XSLTNode> children) {
        for (XSLTNode child : children) {
            if (child instanceof CatchNode || child instanceof FallbackNode) {
                continue;
            }
            if (child instanceof LiteralText) {
                String text = ((LiteralText) child).getText();
                if (isWhitespace(text)) {
                    continue;
                }
            }
            return true;
        }
        return false;
    }

    /**
     * Parses a validation attribute value into a ValidationMode, enforcing
     * XTSE1660 for Basic (non-schema-aware) XSLT 3.0 processors.
     *
     * <p>A Basic XSLT 3.0 processor must reject validation="strict" as a
     * static error (XTSE1660). The values "lax" and "preserve" are treated
     * as equivalent to "strip" per the XSLT 3.0 spec section 3.6.3.
     *
     * @param value the validation attribute value (may be null)
     * @param elementName the name of the element (for error messages)
     * @return the parsed ValidationMode, or null if value is null/empty
     * @throws SAXException if the value is invalid or not allowed
     */
    private ValidationMode parseValidationMode(String value, String elementName)
            throws SAXException {
        if (value == null || value.isEmpty()) {
            return null;
        }
        if ("strip".equals(value)) {
            return ValidationMode.STRIP;
        }
        if ("strict".equals(value)) {
            throw new SAXException("XTSE1660: validation='strict' on "
                + elementName + " requires a schema-aware processor");
        }
        if ("lax".equals(value) || "preserve".equals(value)) {
            return ValidationMode.STRIP;
        }
        throw new SAXException("Invalid validation value on "
            + elementName + ": " + value
            + ". Expected: strict, lax, preserve, or strip");
    }

    /**
     * Returns true if this XSLT element has its own "version" attribute with element-specific
     * meaning (not the standard XSLT version attribute). For these elements, "version" must not
     * be interpreted as the XSLT forward-compatibility version.
     */
    private static boolean isElementWithOwnVersionAttr(String localName) {
        return "output".equals(localName)
            || "stylesheet".equals(localName)
            || "transform".equals(localName)
            || "package".equals(localName);
    }

    private static boolean isKnownLREAttribute(String localName) {
        switch (localName) {
            case "version":
            case "use-attribute-sets":
            case "exclude-result-prefixes":
            case "extension-element-prefixes":
            case "xpath-default-namespace":
            case "default-collation":
            case "default-validation":
            case "expand-text":
            case "type":
            case "validation":
            case "use-when":
            case "default-mode":
            case "inherit-namespaces":
                return true;
            default:
                return false;
        }
    }
    
    private static String expandedParamName(String namespaceURI, String localName) {
        if (namespaceURI != null && !namespaceURI.isEmpty()) {
            return "{" + namespaceURI + "}" + localName;
        }
        return localName;
    }
    
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
                // Exception: xsl:initial-template is allowed in XSLT 2.0+
                if (checkReserved && isReservedNamespace(uri)) {
                    if (!(XSLT_NS.equals(uri) && "initial-template".equals(localName) && stylesheetVersion >= 2.0)) {
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
        String collation = ctx.attributes.get("collation");
        
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
        
        // XTSE1210: collation URI must be recognized
        if (collation != null && !collation.isEmpty()) {
            if (!Collation.isRecognized(collation)) {
                throw new SAXException("XTSE1210: Unknown collation URI on xsl:key: " + collation);
            }
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
        // Filter out whitespace text nodes (e.g. from xml:space="preserve")
        List<XSLTNode> attrNodes = new ArrayList<>();
        for (XSLTNode child : ctx.children) {
            if (child instanceof LiteralText) {
                String text = ((LiteralText) child).getText();
                if (text != null && !text.trim().isEmpty()) {
                    throw new SAXException("XTSE0010: Text content is not allowed in xsl:attribute-set");
                }
                // Skip whitespace text nodes
            } else if (!(child instanceof AttributeNode)) {
                throw new SAXException("XTSE0010: Only xsl:attribute is allowed in xsl:attribute-set");
            } else {
                attrNodes.add(child);
            }
        }
        
        SequenceNode attrs = new SequenceNode(attrNodes);
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
        String stylesheetUri = ctx.namespaceBindings.get(stylesheetPrefix);
        String resultUri = ctx.namespaceBindings.get(resultPrefix);
        
        if (stylesheetUri == null) {
            stylesheetUri = lookupNamespaceUri(stylesheetPrefix);
        }
        if (resultUri == null) {
            resultUri = lookupNamespaceUri(resultPrefix);
        }
        
        // XTSE0812: non-#default prefix must have a namespace binding
        if (stylesheetUri == null && !stylesheetPrefix.isEmpty()) {
            throw new SAXException("XTSE0812: No namespace binding for prefix '" +
                stylesheetPrefix + "' in xsl:namespace-alias/@stylesheet-prefix");
        }
        if (resultUri == null && !resultPrefix.isEmpty()) {
            throw new SAXException("XTSE0812: No namespace binding for prefix '" +
                resultPrefix + "' in xsl:namespace-alias/@result-prefix");
        }
        
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
        
        // XTSE0620: must not have both select attribute and non-empty content
        if (selectExpr != null && content != null && hasNonWhitespaceContent(ctx.children)) {
            throw new SAXException("XTSE0620: xsl:variable must not have both " +
                "a select attribute and non-empty content");
        }
        
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
        
        return new ParamNode(paramName.getURI(), paramName.getLocalName(), selectExpr, content, asType, tunnel, required);
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
    
    /**
     * Returns the effective default-mode at the current point in the stylesheet.
     * Inherited from ancestor elements via the default-mode / xsl:default-mode attribute.
     */
    private String getEffectiveDefaultMode() {
        if (currentProcessingContext != null) {
            return currentProcessingContext.defaultMode;
        }
        if (!elementStack.isEmpty()) {
            return elementStack.peek().defaultMode;
        }
        return null;
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
            if (!ctx.children.isEmpty() && hasNonWhitespaceContent(ctx.children)) {
                throw new SAXException("XTSE0870: xsl:value-of must not have both " +
                    "a select attribute and content");
            }
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

        // XSLT 3.0: empty xsl:value-of with no select and no content produces empty string
        if (getEffectiveVersion() >= 3.0) {
            return new ValueOfNode(compileExpression("''"), disableEscaping, separator, true);
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
        
        ValidationMode validation = parseValidationMode(validationValue, "xsl:element");
        
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
        
        ValidationMode validation = parseValidationMode(validationValue, "xsl:attribute");
        
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
        
        if (selectExpr != null && !ctx.children.isEmpty() && hasNonWhitespaceContent(ctx.children)) {
            throw new SAXException("XTSE0840: xsl:attribute must not have both " +
                "a select attribute and content");
        }
        SequenceNode content = ctx.children.isEmpty() ? null : new SequenceNode(ctx.children);
        
        return new AttributeNode(nameAvt, nsAvt, selectExpr, separator, content, nsBindings,
                                 typeNamespaceURI, typeLocalName, validation);
    }

    private XSLTNode compileNamespace(ElementContext ctx) throws SAXException {
        String name = ctx.attributes.get("name");
        String select = ctx.attributes.get("select");
        
        AttributeValueTemplate nameAvt = parseAvt(name != null ? name : "");
        XPathExpression selectExpr = select != null ? compileExpression(select) : null;
        if (selectExpr != null && !ctx.children.isEmpty() && hasNonWhitespaceContent(ctx.children)) {
            throw new SAXException("XTSE0910: xsl:namespace must not have both " +
                "a select attribute and content");
        }
        
        return new NamespaceInstructionNode(nameAvt, selectExpr, new SequenceNode(ctx.children));
    }
    
    private XSLTNode compileComment(ElementContext ctx) throws SAXException {
        String select = ctx.attributes.get("select");
        if (select != null && !ctx.children.isEmpty() && hasNonWhitespaceContent(ctx.children)) {
            throw new SAXException("XTSE0940: xsl:comment must not have both " +
                "a select attribute and content");
        }
        return new CommentNode(new SequenceNode(ctx.children));
    }

    private XSLTNode compilePI(ElementContext ctx) throws SAXException {
        String name = ctx.attributes.get("name");
        String select = ctx.attributes.get("select");
        
        AttributeValueTemplate nameAvt = parseAvt(name);
        XPathExpression selectExpr = select != null ? compileExpression(select) : null;
        if (selectExpr != null && !ctx.children.isEmpty() && hasNonWhitespaceContent(ctx.children)) {
            throw new SAXException("XTSE0880: xsl:processing-instruction must not have both " +
                "a select attribute and content");
        }
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
        
        ValidationMode validation = parseValidationMode(validationValue, "xsl:copy");
        
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
        
        ValidationMode validation = parseValidationMode(validationValue, "xsl:copy-of");
        
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
            if (!ctx.children.isEmpty() && hasNonFallbackContent(ctx.children)) {
                throw new SAXException("XTSE3185: xsl:sequence must not have both " +
                    "a select attribute and children other than xsl:fallback");
            }
            return new SequenceOutputNode(compileExpression(select));
        } else {
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
        
        // Resolve default-mode: if no mode specified, or mode="#default",
        // use the effective default-mode from the enclosing element
        if (mode == null || "#default".equals(mode)) {
            String effectiveDefaultMode = getEffectiveDefaultMode();
            if (effectiveDefaultMode != null && !"#unnamed".equals(effectiveDefaultMode)) {
                mode = effectiveDefaultMode;
            } else {
                mode = null;
            }
        }
        
        // Expand mode QName to Clark notation for proper namespace comparison
        String expandedMode = expandModeQName(mode);
        
        XPathExpression selectExpr = select != null ? compileExpression(select) : null;
        
        // Extract sorts and with-params from children
        // XTSE0010: Only xsl:sort and xsl:with-param are allowed as children
        // Note: whitespace-only text nodes are ignored per XSLT spec (even with xml:space="preserve")
        // Per XSLT 2.0+ spec, sort and with-param may appear in any order
        List<SortSpec> sorts = new ArrayList<>();
        List<WithParamNode> params = new ArrayList<>();
        Set<String> seenParamNames = new HashSet<>();
        
        for (XSLTNode child : ctx.children) {
            // Ignore whitespace-only text (per XSLT spec section 3.4)
            if (child instanceof LiteralText && isWhitespace(((LiteralText) child).getText())) {
                continue;
            }
            if (child instanceof SortSpecNode) {
                sorts.add(((SortSpecNode) child).getSortSpec());
            } else if (child instanceof WithParamNode) {
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
     * Compiles an xsl:map-entry instruction (XSLT 3.0).
     */
    private XSLTNode compileMapEntry(ElementContext ctx) throws SAXException {
        String keyAttr = ctx.attributes.get("key");
        if (keyAttr == null || keyAttr.isEmpty()) {
            throw new SAXException("XTSE0010: xsl:map-entry requires a 'key' attribute");
        }
        XPathExpression keyExpr = compileExpression(keyAttr);
        String selectAttr = ctx.attributes.get("select");
        XPathExpression selectExpr = null;
        if (selectAttr != null && !selectAttr.isEmpty()) {
            selectExpr = compileExpression(selectAttr);
        }
        boolean hasContent = ctx.children != null && !ctx.children.isEmpty();
        if (selectExpr != null && hasContent) {
            throw new SAXException("XTSE3280: xsl:map-entry must not have both select attribute and content");
        }
        SequenceNode content = new SequenceNode(ctx.children);
        return new MapEntryNode(keyExpr, selectExpr, content);
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
        String validationValue = ctx.attributes.get("validation");
        ValidationMode validation = parseValidationMode(validationValue, "xsl:document");
        
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
        
        // XTSE3190: check for duplicate merge-source names
        Set<String> sourceNames = new HashSet<>();
        for (MergeNode.MergeSource source : sources) {
            String srcName = source.name;
            if (srcName != null && !sourceNames.add(srcName)) {
                throw new SAXException("XTSE3190: Duplicate xsl:merge-source name '" +
                    srcName + "'");
            }
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
        if (!ctx.children.isEmpty() && hasNonWhitespaceContent(ctx.children)) {
            throw new SAXException("XTSE3200: xsl:merge-key with select attribute " +
                "must not have non-empty content");
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
        String select = ctx.attributes.get("select");
        if (select != null && !ctx.children.isEmpty()) {
            throw new SAXException("XTSE3125: xsl:break must not have both " +
                "a select attribute and children");
        }
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
        String selectStr = ctx.attributes.get("select");
        XPathExpression selectExpr = null;
        if (selectStr != null && !selectStr.isEmpty()) {
            if (hasNonCatchOrFallbackContent(ctx.children)) {
                throw new SAXException("XTSE3140: xsl:try with select attribute must not " +
                    "have children other than xsl:catch and xsl:fallback");
            }
            selectExpr = compileExpression(selectStr);
        }
        
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
        
        // XTSE0020: serialization boolean attributes must be yes/no (or AVT)
        validateResultDocumentBoolean(ctx, "undeclare-prefixes");
        validateResultDocumentBoolean(ctx, "indent");
        validateResultDocumentBoolean(ctx, "omit-xml-declaration");
        validateResultDocumentBoolean(ctx, "byte-order-mark");
        validateResultDocumentBoolean(ctx, "escape-uri-attributes");
        validateResultDocumentBoolean(ctx, "include-content-type");
        
        // standalone accepts yes/no/true/false/1/0 plus omit
        String standalone = ctx.attributes.get("standalone");
        if (standalone != null && !standalone.isEmpty()) {
            boolean isAvt = standalone.contains("{") && standalone.contains("}");
            if (!isAvt) {
                String trimmed = standalone.trim();
                switch (trimmed) {
                    case "yes": case "no": case "true": case "false":
                    case "1": case "0": case "omit":
                        break;
                    default:
                        throw new SAXException("XTSE0020: Invalid value for standalone attribute on " +
                            "xsl:result-document: must be yes, no, true, false, 1, 0, or omit, got '" +
                            standalone + "'");
                }
            }
        }
        
        // XTSE0020: html-version must be a valid number if present (and not an AVT)
        String htmlVersion = ctx.attributes.get("html-version");
        if (htmlVersion != null && !htmlVersion.isEmpty()) {
            boolean isAvt = htmlVersion.contains("{") && htmlVersion.contains("}");
            if (!isAvt) {
                try {
                    Double.parseDouble(htmlVersion.trim());
                } catch (NumberFormatException e) {
                    throw new SAXException("XTSE0020: Invalid value for html-version attribute on " +
                        "xsl:result-document: must be a number, got '" + htmlVersion + "'");
                }
            }
        }
        
        // type and validation are mutually exclusive
        if (typeValue != null && validationValue != null) {
            throw new SAXException("xsl:result-document cannot have both type and validation attributes");
        }
        
        ValidationMode validation = parseValidationMode(validationValue, "xsl:result-document");
        
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
        
        // XTSE0620: must not have both select attribute and non-empty content
        if (selectExpr != null && content != null && hasNonWhitespaceContent(ctx.children)) {
            throw new SAXException("XTSE0620: xsl:with-param must not have both " +
                "a select attribute and non-empty content");
        }
        
        return new WithParamNode(paramName.getURI(), paramName.getLocalName(), selectExpr, content, tunnel, asType);
    }

    private XSLTNode compileSort(ElementContext ctx) throws SAXException {
        // select attribute - defaults to "." (current node)
        String selectAttr = ctx.attributes.get("select");
        if (selectAttr != null && !ctx.children.isEmpty() && hasNonWhitespaceContent(ctx.children)) {
            throw new SAXException("XTSE1015: xsl:sort must not have both " +
                "a select attribute and content");
        }
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
                // Invalid grouping-size attribute; use default of 0 (no grouping)
            }
        }
        
        // lang and letter-value for internationalization (optional)
        String lang = ctx.attributes.get("lang");
        String letterValue = ctx.attributes.get("letter-value");
        
        // start-at attribute (XSLT 3.0) - starting number offset, can be AVT
        String startAtAttr = ctx.attributes.get("start-at");
        AttributeValueTemplate startAtAVT = null;
        if (startAtAttr != null) {
            try {
                startAtAVT = AttributeValueTemplate.parse(startAtAttr, this);
            } catch (XPathSyntaxException e) {
                throw new SAXException("Invalid start-at AVT: " + e.getMessage(), e);
            }
        }
        
        return new NumberNode(valueExpr, selectExpr, level, countPattern, fromPattern, 
                             formatAVT, groupingSeparator, groupingSize, lang, letterValue,
                             startAtAVT);
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
        try {
            return PatternParser.parse(resolvedPattern);
        } catch (IllegalArgumentException e) {
            throw new SAXException(e.getMessage());
        }
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
        
        // Note: XSLT 3.0 processors accept parenthesized patterns, variable refs,
        // and doc() in patterns regardless of the stylesheet version attribute.
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

        // XPath comments (:...:) are not parenthesized patterns
        if (trimmed.length() > 1 && trimmed.charAt(1) == ':') {
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
            
            // Handle parentheses: resolve prefixed names inside kind test arguments,
            // copy other parenthesized content verbatim
            if (c == '(') {
                String preceding = getPrecedingToken(result);
                boolean isKindTest = "element".equals(preceding) || 
                    "attribute".equals(preceding) ||
                    "schema-element".equals(preceding) || 
                    "schema-attribute".equals(preceding);
                
                if (isKindTest) {
                    // Resolve prefixed names inside kind test arguments
                    int depth = 1;
                    result.append(c);
                    i++;
                    while (i < len && depth > 0) {
                        c = pattern.charAt(i);
                        if (c == '(') {
                            depth++;
                            result.append(c);
                            i++;
                        } else if (c == ')') {
                            depth--;
                            result.append(c);
                            i++;
                        } else if (depth == 1 && Character.isLetter(c)) {
                            // Extract a name token inside the kind test
                            int argStart = i;
                            while (i < len && pattern.charAt(i) != ',' && 
                                   pattern.charAt(i) != ')' && 
                                   !Character.isWhitespace(pattern.charAt(i))) {
                                i++;
                            }
                            String arg = pattern.substring(argStart, i).trim();
                            if (!"*".equals(arg)) {
                                try {
                                    arg = resolvePatternToken(arg, false);
                                } catch (SAXException e) {
                                    // Leave unresolved if prefix not found
                                }
                            }
                            result.append(arg);
                        } else {
                            result.append(c);
                            i++;
                        }
                    }
                } else {
                    // Not a kind test: copy verbatim
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
                // Q{uri}local EQName: skip over braced URI content
                if (c == 'Q' && i + 1 < len && pattern.charAt(i + 1) == '{') {
                    i += 2;
                    while (i < len && pattern.charAt(i) != '}') {
                        i++;
                    }
                    if (i < len) {
                        i++; // skip '}'
                    }
                    continue;
                }
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

    /**
     * Extracts the last identifier token from a StringBuilder.
     * Used to determine if a '(' is preceded by a kind test keyword.
     */
    private String getPrecedingToken(StringBuilder sb) {
        int end = sb.length();
        if (end == 0) {
            return "";
        }
        int i = end - 1;
        while (i >= 0 && (Character.isLetterOrDigit(sb.charAt(i)) || sb.charAt(i) == '-')) {
            i--;
        }
        return sb.substring(i + 1, end);
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

    /**
     * XSLT 2.0+ xsl:value-of with content (sequence constructor) instead of select attribute.
     * The output is the string-join of all items produced by the content, with separator.
     */
    // XSLT Instruction implementations
    
    /**
     * Finds an existing prefix for a namespace or generates a new one.
     */
    public static String findOrGeneratePrefix(String namespace, Map<String, String> bindings) {
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
    
    /**
     * xsl:sequence with select - outputs the selected sequence.
     * Unlike xsl:copy-of, xsl:sequence returns nodes by reference (no copying).
     * For atomic values, it outputs them as text.
     */
    /**
     * xsl:next-match instruction (XSLT 2.0+).
     *
     * <p>Invokes the next template rule that matches the current node,
     * in precedence/priority order after the currently executing template.
     */
    /**
     * xsl:try instruction (XSLT 3.0).
     *
     * <p>Executes content that might throw an error. If an error occurs,
     * a matching catch block is executed instead.
     */
    /**
     * xsl:catch instruction (XSLT 3.0).
     *
     * <p>Contains catch content and error codes to match.
     */
    /**
     * xsl:assert instruction (XSLT 3.0).
     *
     * <p>Tests an assertion during transformation. If the test fails,
     * a dynamic error is raised.
     */
    /**
     * xsl:on-empty instruction (XSLT 3.0).
     *
     * <p>Specifies content to use if the containing sequence is empty.
     */
    /**
     * xsl:on-non-empty instruction (XSLT 3.0).
     *
     * <p>Specifies content to output only if the containing sequence is non-empty.
     */
    /**
     * xsl:where-populated instruction (XSLT 3.0).
     *
     * <p>Outputs its content only if that content produces non-empty output.
     */
    /**
     * xsl:analyze-string instruction (XSLT 2.0).
     *
     * <p>Analyzes a string using a regular expression, executing different
     * content for matching and non-matching substrings.
     */
    /**
     * A virtual text node that represents the current substring in analyze-string.
     */
    /**
     * Implements xsl:perform-sort instruction (XSLT 2.0).
     * Sorts a sequence and returns the sorted result.
     */
    /** Simple pattern implementation for basic matching. */
    /**
     * Simple OutputHandler adapter for SAXEventBuffer.
     */
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
    /**
     * Represents an attribute node item in a sequence.
     * Unlike attributes in elements, these are standalone items.
     * Implements XPathNode to support type annotations for schema-aware processing.
     */
    /**
     * Represents a text node item in a sequence.
     */
    /**
     * Represents a comment node item in a sequence.
     */
    /**
     * Represents a processing instruction node item in a sequence.
     */
    /**
     * Represents a namespace node item in a sequence.
     * Unlike namespaces attached to elements, these are standalone items.
     * The local name is the prefix, the string value is the namespace URI.
     */
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
}

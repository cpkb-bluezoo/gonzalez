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
import java.util.TreeSet;
import java.util.regex.PatternSyntaxException;

import javax.xml.transform.ErrorListener;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;

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
import org.bluezoo.gonzalez.transform.ast.DynamicValueOfNode;
import org.bluezoo.gonzalez.transform.ast.EvaluateNode;
import org.bluezoo.gonzalez.transform.ast.ForEachGroupNode;
import org.bluezoo.gonzalez.transform.ast.ForkNode;
import org.bluezoo.gonzalez.transform.ast.IterateNode;
import org.bluezoo.gonzalez.transform.ast.LiteralResultElement;
import org.bluezoo.gonzalez.transform.ast.LiteralText;
import org.bluezoo.gonzalez.transform.ast.MergeNode;
import org.bluezoo.gonzalez.transform.ast.NextIterationNode;
import org.bluezoo.gonzalez.transform.ast.ResultDocumentNode;
import org.bluezoo.gonzalez.transform.ast.CollationScopeNode;
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
import org.bluezoo.gonzalez.transform.ast.OnCompletionNode;
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
import org.bluezoo.gonzalez.transform.xpath.XPathContext;
import org.bluezoo.gonzalez.transform.xpath.XPathExpression;
import org.bluezoo.gonzalez.transform.xpath.XPathFunctionLibrary;
import org.bluezoo.gonzalez.transform.xpath.XPathParser;
import org.bluezoo.gonzalez.transform.xpath.function.CoreFunctionLibrary;
import org.bluezoo.gonzalez.transform.xpath.function.XSLTFunctionLibrary;
import org.bluezoo.gonzalez.transform.xpath.Collation;
import org.bluezoo.gonzalez.transform.xpath.StaticTypeContext;
import org.bluezoo.gonzalez.transform.xpath.XPathSyntaxException;
import org.bluezoo.gonzalez.transform.xpath.function.Function;
import org.bluezoo.gonzalez.transform.xpath.expr.BinaryExpr;
import org.bluezoo.gonzalez.transform.xpath.expr.Operator;
import org.bluezoo.gonzalez.transform.xpath.expr.ContextItemExpr;
import org.bluezoo.gonzalez.transform.xpath.expr.Expr;
import org.bluezoo.gonzalez.transform.xpath.expr.FilterExpr;
import org.bluezoo.gonzalez.transform.xpath.expr.FunctionCall;
import org.bluezoo.gonzalez.transform.xpath.expr.LocationPath;
import org.bluezoo.gonzalez.transform.xpath.expr.PathExpr;
import org.bluezoo.gonzalez.transform.xpath.expr.Step;
import org.bluezoo.gonzalez.transform.xpath.expr.TypeExpr;
import org.bluezoo.gonzalez.transform.xpath.expr.ForExpr;
import org.bluezoo.gonzalez.transform.xpath.expr.IfExpr;
import org.bluezoo.gonzalez.transform.xpath.expr.LetExpr;
import org.bluezoo.gonzalez.transform.xpath.expr.Literal;
import org.bluezoo.gonzalez.transform.xpath.expr.SequenceExpr;
import org.bluezoo.gonzalez.transform.xpath.expr.UnaryExpr;
import org.bluezoo.gonzalez.transform.xpath.expr.VariableReference;
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

    // Compilation state (package-private for DeclarationCompiler)
    final CompiledStylesheet.Builder builder = CompiledStylesheet.builder();
    final Deque<ElementContext> elementStack = new ArrayDeque<>();
    private final Map<String, String> namespaces = new HashMap<>();
    private final Map<String, String> pendingNamespaces = new HashMap<>(); // Namespaces declared before next startElement
    private final Deque<Map<String, String>> namespaceScopes = new ArrayDeque<>(); // Stack of namespace scopes for proper prefix resolution
    final StringBuilder characterBuffer = new StringBuilder();
    
    // Current element context being processed (set during endElement processing)
    // This is needed because the context is popped from the stack before processing
    private ElementContext currentProcessingContext = null;
    
    // Locator for error reporting
    private Locator locator;
    
    // Import/include support (package-private for DeclarationCompiler)
    final StylesheetResolver resolver;
    final String baseUri;
    int importPrecedence = -1;  // Set lazily after imports are processed
    int minImportedPrecedence = -1;  // Lowest precedence from imported modules
    private int localTemplateCounter = 0;  // Fallback counter when no resolver
    boolean importsAllowed = true;
    boolean precedenceAssigned = false;
    boolean isPrincipalStylesheet = true;
    
    // Tracks explicit mode attribute values for XTSE0545 conflict detection
    // Key: "modeName|attrName", Value: explicit attribute value (package-private for DeclarationCompiler)
    final Map<String, String> modeAttributeValues = new HashMap<>();
    
    // Deferred pattern predicate validations (resolved after all xsl:function are registered)
    private final List<String[]> deferredPatternValidations = new ArrayList<>();
    
    // Tracks whether the current template being compiled has a match/name pattern
    // Used by xsl:context-item to detect invalid use="absent" in match-only templates
    boolean currentTemplateHasMatch = false;
    boolean currentTemplateHasName = false;

    // Collects override declarations from templates compiled inside xsl:override (package-private for DeclarationCompiler)
    List<OverrideDeclaration> pendingOverrideDeclarations;

    // Forward-compatible processing mode (package-private for DeclarationCompiler)
    double stylesheetVersion = 1.0;
    boolean forwardCompatible = false;

    // Maximum XSLT version the processor should advertise.
    // When set below 3.0, XSLT 3.0 instructions like xsl:try are
    // treated as unknown, enabling xsl:fallback processing.
    double maxProcessorVersion = 3.0;

    // Static type checking strictness (default: pessimistic)
    private boolean strictTypeChecking = true;

    // Streaming fallback (§19.10): fall back to non-streaming instead of
    // raising XTSE3430 for non-streamable content in streamable modes.
    boolean streamingFallback = false;

    // Default validation mode (XSLT 2.0+) (package-private for DeclarationCompiler)
    ValidationMode defaultValidation = ValidationMode.STRIP;
    
    // Tracks input-type-annotations across modules for XTSE0265 detection
    private String inputTypeAnnotationsValue = null;
    private boolean inputTypeAnnotationsSet = false;
    
    /**
     * Validation modes for schema-aware processing.
     *
     * <p>Controls how elements and attributes are validated against imported schemas
     * when constructing result trees.
     *
     * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
     */

    // Excluded namespace URIs from exclude-result-prefixes (package-private for DeclarationCompiler)
    final Set<String> excludedNamespaceURIs = new HashSet<>();

    // Extension namespace URIs from extension-element-prefixes (package-private for DeclarationCompiler)
    final Set<String> extensionNamespaceURIs = new HashSet<>();
    
    // Track depth inside top-level user data elements (ignored per XSLT 1.0 Section 2.2)
    private int userDataElementDepth = 0;
    
    // Track depth inside elements excluded by use-when="false()" (XSLT 2.0 conditional compilation)
    private int useWhenSkipDepth = 0;
    
    // Inline schema parsing (package-private for DeclarationCompiler)
    boolean inImportSchema = false;
    private XSDSchemaParser inlineSchemaParser = null;
    private int inlineSchemaDepth = 0;
    String importSchemaNamespace = null;  // namespace attribute from xsl:import-schema

    // Static variables (XSLT 3.0): evaluated at compile time, available in use-when (package-private for DeclarationCompiler)
    // For included modules, this map may be shared with the parent compiler.
    Map<String, XPathValue> staticVariables = new HashMap<>();

    // External static parameter overrides (set before compilation)
    final Map<String, String> staticParameterOverrides = new HashMap<>();

    // Track static declarations for XTSE3450 conflict detection (package-private for DeclarationCompiler)
    final Map<String, Object[]> staticDeclarationInfo = new HashMap<>();
    
    // Simplified stylesheet: root element is a literal result element with xsl:version attribute
    // Per XSLT 1.0 Section 2.3: equivalent to xsl:stylesheet containing template match="/"
    private boolean isSimplifiedStylesheet = false;
    private XSLTNode simplifiedStylesheetBody = null;
    
    // XSLT 3.0 package support (package-private for DeclarationCompiler)
    PackageResolver packageResolver = null;
    String packageName = null;  // Package-private for access in compilePackage
    String packageVersion = null;  // Package-private for access in compilePackage
    final List<CompiledPackage.PackageDependency> packageDependencies = new ArrayList<>();  // Package-private
    boolean declaredModesEnabled = false;
    final Set<String> usedModeNames = new HashSet<>();

    /**
     * Context for an element being processed.
     * Package-private for access from DeclarationCompiler.
     */
    static class ElementContext {
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
        String locatorSystemId;  // Locator system ID when this element started (for entity detection)
        int lineNumber = -1;  // Line number of this element in the source
        int columnNumber = -1;  // Column number of this element in the source
        double effectiveVersion = -1;  // Effective XSLT version for backwards compatibility (-1 = inherit from parent)
        boolean expandText = false;  // XSLT 3.0 Text Value Templates enabled
        String xpathDefaultNamespace = null;  // XSLT 2.0+ xpath-default-namespace for XPath expressions
        String defaultCollation = null;  // XSLT 2.0+ default-collation for comparisons
        String defaultMode = null;  // XSLT 3.0 default-mode (null = inherit from parent)
        final Set<String> childElementNames = new HashSet<>();  // Local names of XSLT child elements
        final List<String> childElementNameList = new ArrayList<>();  // Ordered child element names
        
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
        this.isPrincipalStylesheet = true;
    }

    /**
     * Creates a new stylesheet compiler with external stylesheet support.
     *
     * @param resolver the stylesheet resolver for imports/includes
     * @param baseUri the base URI of this stylesheet for relative resolution
     */
    public StylesheetCompiler(StylesheetResolver resolver, String baseUri) {
        this(resolver, baseUri, -1);  // -1 means assign precedence after includes are processed
        this.isPrincipalStylesheet = true;
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
        this.isPrincipalStylesheet = false;
        
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
    void ensurePrecedenceAssigned() {
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
    int nextTemplateIndex() {
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
        // Get a fresh final precedence that is guaranteed higher than any
        // imports compiled within included stylesheets (which may have gotten
        // higher counter values than our initial importPrecedence)
        int finalPrecedence = importPrecedence;
        if (resolver != null) {
            finalPrecedence = resolver.nextPrecedence();
        }
        builder.finalizePrecedence(importPrecedence, finalPrecedence, minImportedPrecedence);
        // XTSE3350: check for unresolved duplicate accumulator names
        // Only validate at the principal stylesheet level, not in imported modules
        if (isPrincipalStylesheet) {
            try {
                builder.validateAccumulatorNames();
            } catch (SAXException e) {
                throw new TransformerConfigurationException(e.getMessage(), e);
            }
        }
        CompiledStylesheet stylesheet = builder.build(validateReferences);
        
        // XTSE0730: Validate that streamable attribute sets only reference
        // other streamable attribute sets via use-attribute-sets
        try {
            for (Map.Entry<String, AttributeSet> entry : stylesheet.getAttributeSets().entrySet()) {
                AttributeSet attrSet = entry.getValue();
                if (attrSet.isStreamable()) {
                    for (String refName : attrSet.getUseAttributeSets()) {
                        AttributeSet referenced = stylesheet.getAttributeSet(refName);
                        if (referenced != null && !referenced.isStreamable()) {
                            throw new SAXException("XTSE0730: Streamable attribute set '"
                                + entry.getKey() + "' references attribute set '" + refName
                                + "' which is not declared streamable");
                        }
                    }
                }
            }
        } catch (SAXException e) {
            throw new TransformerConfigurationException(e.getMessage(), e);
        }
        
        // Run streamability analysis and store results
        StreamabilityAnalyzer analyzer = new StreamabilityAnalyzer();
        StreamabilityAnalyzer.StylesheetStreamability analysis = analyzer.analyze(stylesheet);
        stylesheet.setStreamabilityAnalysis(analysis);

        // XTSE3430: Validate that templates in streamable modes are streamable.
        // With streaming fallback (§19.10), failing modes are downgraded to
        // non-streamable instead of raising errors.
        try {
            List<String> fallbackModes =
                StreamabilityValidator.validateStreamableModes(
                    stylesheet, analysis, analyzer, streamingFallback);
            for (int i = 0; i < fallbackModes.size(); i++) {
                stylesheet.downgradeMode(fallbackModes.get(i));
            }
        } catch (SAXException e) {
            throw new TransformerConfigurationException(e.getMessage(), e);
        }
        
        // XTSE3085: Validate declared-modes (xsl:package with declared-modes="yes")
        try {
            DeclarationCompiler.validateDeclaredModes(this, stylesheet);
        } catch (SAXException e) {
            throw new TransformerConfigurationException(e.getMessage(), e);
        }
        
        // XTSE3080: An executable package must not have abstract components
        try {
            DeclarationCompiler.validateNoAbstractComponents(this, stylesheet);
        } catch (SAXException e) {
            throw new TransformerConfigurationException(e.getMessage(), e);
        }
        
        // XTSE3010/XTSE3020: Validate xsl:expose declarations
        try {
            DeclarationCompiler.validateExposeDeclarations(this, stylesheet);
        } catch (SAXException e) {
            throw new TransformerConfigurationException(e.getMessage(), e);
        }
        
        // Store package resolver for fn:transform() access
        if (packageResolver != null) {
            stylesheet.setPackageResolver(packageResolver);
        }
        
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
        DeclarationCompiler.validateAbstractComponents(pkg);
        
        return pkg;
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
     * Sets an external static parameter override. When the compiler encounters
     * an xsl:param with static="yes" and a matching name, this value is used
     * instead of the default select expression.
     *
     * @param name the parameter local name
     * @param value the string value to use
     */
    public void setStaticParameter(String name, String value) {
        staticParameterOverrides.put(name, value);
    }

    /**
     * Sets the maximum XSLT version this processor should advertise.
     * Instructions introduced after this version (e.g. xsl:try at 3.0) are
     * treated as unknown, so xsl:fallback children execute instead.
     */
    public void setMaxProcessorVersion(double version) {
        this.maxProcessorVersion = version;
    }

    /**
     * Sets whether strict (pessimistic) static type checking is enabled.
     *
     * @param strict true for pessimistic checking
     */
    public void setStrictTypeChecking(boolean strict) {
        this.strictTypeChecking = strict;
    }

    /**
     * Enables streaming fallback mode (XSLT 3.0 §19.10).
     * When enabled, non-streamable content in streamable modes is
     * downgraded to non-streaming instead of raising XTSE3430.
     *
     * @param fallback true to enable streaming fallback
     */
    public void setStreamingFallback(boolean fallback) {
        this.streamingFallback = fallback;
    }

    /**
     * Replaces this compiler's static variable map with a shared map from the
     * parent compiler. Used for xsl:include so that static variables defined
     * before the include are visible in the included module, and variables
     * defined in the included module are visible after the include point.
     *
     * @param sharedMap the parent compiler's static variable map
     */
    void setSharedStaticVariables(Map<String, XPathValue> sharedMap) {
        this.staticVariables = sharedMap;
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
        
        // Validate deferred pattern predicates now that all xsl:function
        // declarations have been registered (handles forward references)
        PatternValidator.validateDeferredPatternPredicates(this);
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
        
        // Also record in the builder so runtime functions (e.g. format-number 3rd arg)
        // can resolve prefixes declared on any element, not just the stylesheet root.
        if (builder != null && prefix != null && !prefix.isEmpty() && uri != null) {
            builder.addNamespaceBinding(prefix, uri);
        }
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
        CompilerUtils.flushCharacters(this);
        
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
                // XTSE1650: non-schema-aware processor must reject xsl:import-schema
                // with inline schema (we only support external schema-location references)
                throw new SAXException("XTSE1650: xsl:import-schema with inline schema " +
                    "is not supported by this non-schema-aware XSLT processor");
            } else if (inlineSchemaParser != null && inlineSchemaDepth > 0) {
                // XTSE1650: should not reach here (inline schema already rejected above)
                // but handle gracefully
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
        // - On XSLT elements: use-when is a no-namespace attribute
        // - On literal result elements: xsl:use-when must be in the XSLT namespace
        // - Per XSLT 3.0 §3.8: use-when has no effect on xsl:stylesheet/xsl:transform
        // A no-namespace use-when on an LRE is just a literal attribute, not xsl:use-when
        boolean isXsltElement = XSLT_NS.equals(uri);
        boolean isRootStylesheetElement = isPrincipalStylesheet && isXsltElement
            && elementStack.isEmpty()
            && ("stylesheet".equals(localName) || "transform".equals(localName));
        if (!isRootStylesheetElement) {
            String useWhen = null;
            if (isXsltElement) {
                // XSLT element: use-when is in no namespace (standard attribute)
                useWhen = atts.getValue("", "use-when");
                if (useWhen == null) {
                    useWhen = atts.getValue("use-when");
                }
                // XSLT 3.0: check for shadow attribute _use-when (static AVT)
                if (useWhen == null) {
                    String shadowUseWhen = atts.getValue("", "_use-when");
                    if (shadowUseWhen == null) {
                        shadowUseWhen = atts.getValue("_use-when");
                    }
                    if (shadowUseWhen != null && !shadowUseWhen.isEmpty()) {
                        useWhen = resolveStaticAVTString(shadowUseWhen, "use-when");
                    }
                }
            } else {
                // Literal result element: only xsl:use-when (in XSLT namespace) counts
                useWhen = atts.getValue(XSLT_NS, "use-when");
            }
            if (useWhen != null && !useWhen.isEmpty()) {
                // Per spec, the static context for use-when includes
                // xpath-default-namespace from this element itself.
                // Push a temporary context so getDefaultElementNamespace()
                // sees the current element's attribute.
                String xdns = isXsltElement
                    ? atts.getValue("xpath-default-namespace")
                    : atts.getValue(XSLT_NS, "xpath-default-namespace");
                ElementContext tempCtx = new ElementContext(uri, localName, originalPrefix);
                tempCtx.xpathDefaultNamespace = xdns;
                if (tempCtx.xpathDefaultNamespace == null && !elementStack.isEmpty()) {
                    tempCtx.xpathDefaultNamespace = elementStack.peek().xpathDefaultNamespace;
                }
                // Inherit base URI from parent for static-base-uri()
                tempCtx.baseURI = elementStack.isEmpty()
                    ? (locator != null ? locator.getSystemId() : null)
                    : elementStack.peek().baseURI;
                elementStack.push(tempCtx);
                boolean include = false;
                try {
                    include = evaluateUseWhen(useWhen);
                } catch (SAXException e) {
                    // Per XSLT 3.0 §3.8: in forwards-compatible mode, errors
                    // in use-when on unknown XSLT elements are tolerable since
                    // the element will be ignored anyway
                    if (!(isXsltElement && forwardCompatible)) {
                        throw e;
                    }
                } finally {
                    elementStack.pop();
                }
                if (!include) {
                    // Exclude this element and all its descendants
                    useWhenSkipDepth = 1;
                    elementStack.push(new ElementContext(uri, localName, originalPrefix));
                    return;
                }
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
            // These override the regular attribute with a runtime-evaluated value.
            // Shadow attributes only apply to XSLT instruction elements,
            // not to literal result elements where _-prefixed names are normal attributes.
            if (XSLT_NS.equals(uri) && attrQName.startsWith("_") && attrQName.length() > 1) {
                // Extract the real attribute name (without the _ prefix)
                String realAttrName = attrQName.substring(1);
                ctx.shadowAttributes.put(realAttrName, attrValue);
            } else {
                // Normalize XSLT namespace attributes to use "xsl:" prefix
                // This allows stylesheets to use any prefix for the XSLT namespace
                // (e.g., t:xpath-default-namespace becomes xsl:xpath-default-namespace)
                if (XSLT_NS.equals(attrURI) && attrLocal != null && !attrLocal.isEmpty()) {
                    // XTSE0090: On an XSLT element, standard attributes must appear
                    // without a namespace. Having them in the XSLT namespace is an error.
                    if (XSLT_NS.equals(uri) && CompilerUtils.STANDARD_ATTRIBUTES.contains(attrLocal)) {
                        throw new SAXException("XTSE0090: Standard attribute '" + attrLocal +
                            "' must not be namespace-qualified on XSLT element xsl:" + localName);
                    }
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
                ctx.expandText = parseYesOrNo(expandTextValue);
            }
        }
        
        // Handle xsl:expand-text on literal result elements
        String lreExpandText = atts.getValue(XSLT_NS, "expand-text");
        if (lreExpandText != null && !XSLT_NS.equals(uri)) {
            validateNotAVT("literal result element", "xsl:expand-text", lreExpandText);
            validateYesOrNo("literal result element", "xsl:expand-text", lreExpandText);
            ctx.expandText = parseYesOrNo(lreExpandText);
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
        
        // Handle default-collation (XSLT 2.0+)
        // On XSLT elements: default-collation attribute
        // On LRE elements: xsl:default-collation attribute
        String defaultCollAttr = null;
        if (XSLT_NS.equals(uri)) {
            defaultCollAttr = ctx.attributes.get("default-collation");
        } else {
            defaultCollAttr = ctx.attributes.get("xsl:default-collation");
        }
        if (defaultCollAttr != null && !defaultCollAttr.isEmpty()) {
            String[] collUris = defaultCollAttr.trim().split("\\s+");
            for (String collUri : collUris) {
                if (Collation.isRecognized(collUri)) {
                    ctx.defaultCollation = collUri;
                    break;
                }
            }
        } else if (!elementStack.isEmpty()) {
            ctx.defaultCollation = elementStack.peek().defaultCollation;
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
                } else if (!CompilerUtils.isElementWithOwnVersionAttr(localName)) {
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
        
        // Compute effective base URI for this element.
        // During external entity expansion the SAX locator reports the
        // entity's system ID. If it changed since the parent element
        // started, we are inside an entity and should use the entity URI
        // as the base (rather than inheriting from the parent).
        String currentLocatorSysId = (locator != null) ? locator.getSystemId() : null;
        String parentBase;
        if (elementStack.isEmpty()) {
            parentBase = currentLocatorSysId;
        } else {
            parentBase = elementStack.peek().baseURI;
            String parentLocatorSysId = elementStack.peek().locatorSystemId;
            if (currentLocatorSysId != null
                    && !currentLocatorSysId.equals(parentLocatorSysId)) {
                parentBase = currentLocatorSysId;
            }
        }
        
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
        ctx.locatorSystemId = currentLocatorSysId;
        if (locator != null) {
            ctx.lineNumber = locator.getLineNumber();
            ctx.columnNumber = locator.getColumnNumber();
        }
        
        // Check for stylesheet/transform/package element to set forward-compatible mode early
        if (XSLT_NS.equals(uri) && "package".equals(localName)) {
            String nameAttr = atts.getValue("name");
            if (nameAttr != null && !nameAttr.isEmpty()) {
                packageName = nameAttr.trim();
            }
        }
        if (XSLT_NS.equals(uri) && ("stylesheet".equals(localName) || "transform".equals(localName) 
                || "package".equals(localName))) {
            String versionAttr = atts.getValue("version");
            if (versionAttr == null && ctx.shadowAttributes.containsKey("version")) {
                try {
                    versionAttr = resolveStaticShadowAttribute(ctx, "version");
                } catch (SAXException e) {
                    // Cannot resolve early, defer to processStylesheetElement
                }
            }
            if (versionAttr != null) {
                try {
                    if (!PatternValidator.isValidXsDecimal(versionAttr.trim())) {
                        throw new SAXException("XTSE0110: The version attribute " +
                            "must be a valid xs:decimal: '" + versionAttr + "'");
                    }
                    stylesheetVersion = Double.parseDouble(versionAttr);
                    forwardCompatible = stylesheetVersion > 3.0;
                } catch (NumberFormatException e) {
                    throw new SAXException("XTSE0110: The version attribute " +
                        "must be a valid xs:decimal: '" + versionAttr + "'");
                }
            }
            
            // XTSE0265: track input-type-annotations early (before includes are processed)
            String ita = atts.getValue("input-type-annotations");
            if (ita != null && !ita.isEmpty() && !"unspecified".equals(ita)) {
                builder.setInputTypeAnnotations(ita);
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
                        } else {
                            throw new SAXException("XTSE0809: #default used in " +
                                "exclude-result-prefixes but no default namespace is declared");
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
                            CompilerUtils.checkNotReservedExtensionNamespace(defaultNs);
                            extensionNamespaceURIs.add(defaultNs);
                        }
                    } else {
                        String nsUri = namespaces.get(prefix);
                        if (nsUri == null || nsUri.isEmpty()) {
                            throw new SAXException("XTSE1430: No namespace binding " +
                                "in scope for prefix '" + prefix + "' in extension-element-prefixes");
                        }
                        CompilerUtils.checkNotReservedExtensionNamespace(nsUri);
                        extensionNamespaceURIs.add(nsUri);
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
                        forwardCompatible = stylesheetVersion > 3.0;
                        builder.setVersion(stylesheetVersion);
                        builder.setProcessorVersion(maxProcessorVersion);
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
                } else {
                    throw new SAXException("XTSE0150: The document is not a " +
                        "stylesheet (the root element <" + localName +
                        "> is not xsl:stylesheet, xsl:transform, or a " +
                        "literal result element with an xsl:version attribute)");
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
                        if (nsUri == null || nsUri.isEmpty()) {
                            throw new SAXException("XTSE1430: No namespace binding " +
                                "in scope for prefix '" + prefix + "' in extension-element-prefixes");
                        }
                        extensionNamespaceURIs.add(nsUri);
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
                CompilerUtils.processExcludeResultPrefixes(this, excludePrefixes, namespaces, ctx);
            }
        }
        
        // Process xsl:exclude-result-prefixes on literal result elements
        // This needs to happen during startElement so children are compiled with the exclusion
        if (!XSLT_NS.equals(uri)) {
            String excludePrefixes = atts.getValue(XSLT_NS, "exclude-result-prefixes");
            if (excludePrefixes != null && !excludePrefixes.isEmpty()) {
                CompilerUtils.processExcludeResultPrefixes(this, excludePrefixes, namespaces, ctx);
            }
        }
        
        // Track child element names in parent for sibling order validation.
        // This is done at startElement time so all sibling names are visible
        // when any sibling's endElement (and compile) fires.
        if (!elementStack.isEmpty()) {
            ElementContext parentCtx = elementStack.peek();
            if (XSLT_NS.equals(uri)) {
                parentCtx.childElementNames.add(ctx.localName);
                parentCtx.childElementNameList.add(ctx.localName);
            } else {
                parentCtx.childElementNameList.add("#LRE");
            }
        }
        
        // Set currentTemplateHasMatch/Name early so xsl:context-item children
        // can check them during their compilation (before template endElement)
        if (isXsltElement && "template".equals(localName)) {
            String matchAttr = atts.getValue("match");
            String nameAttr = atts.getValue("name");
            currentTemplateHasMatch = (matchAttr != null);
            currentTemplateHasName = (nameAttr != null);
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
        CompilerUtils.flushCharacters(this);
        
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
        
        CompilerUtils.validateBreakNextIterationPosition(this, ctx);
        
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
                ElementContext parentCtx = elementStack.peek();
                // XTSE0010: xsl:on-empty must be the last instruction
                CompilerUtils.validateOnEmptyOrdering(this, parentCtx, node);
                parentCtx.children.add(node);
            }
            // Note: child element name tracking is done in startElement()
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

    boolean isWhitespace(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
                return false;
            }
        }
        return true;
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
            result = compileXSLTElementWithFCRecovery(ctx);
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
                    final String extElementName = ctx.localName;
                    final String extNsUri = ctx.namespaceURI;
                    result = new XSLTNode() {
                        @Override
                        public void execute(TransformContext context,
                                OutputHandler output) throws SAXException {
                            throw new SAXException("XTDE1450: No xsl:fallback was found " +
                                "for the extension instruction {" + extNsUri + "}" +
                                extElementName);
                        }
                    };
                }
            } else {
                result = DeclarationCompiler.compileLiteralResultElement(this, ctx);
            }
        }
        
        // Set static base URI on compiled instructions (for static-base-uri() support)
        if (result instanceof XSLTInstruction && ctx.baseURI != null) {
            ((XSLTInstruction) result).setStaticBaseURI(ctx.baseURI);
        }
        
        // Wrap in CollationScopeNode if this element has a different default-collation
        // than its parent, so it takes effect at runtime
        if (result != null && ctx.defaultCollation != null) {
            String parentCollation = null;
            if (!elementStack.isEmpty()) {
                parentCollation = elementStack.peek().defaultCollation;
            }
            if (parentCollation == null && builder != null) {
                parentCollation = builder.getDefaultCollation();
            }
            if (!ctx.defaultCollation.equals(parentCollation)) {
                result = new CollationScopeNode(ctx.defaultCollation, result);
            }
        }
        
        return result;
    }

    /**
     * Compiles an XSLT element with forward-compatible error recovery.
     * Per XSLT 3.0 section 3.8, when the effective version of an element
     * is greater than the processor supports, static errors are suppressed
     * and fallback behavior is used.
     */
    private XSLTNode compileXSLTElementWithFCRecovery(ElementContext ctx) throws SAXException {
        boolean elementFC = isElementForwardCompatible(ctx);
        if (!elementFC) {
            return compileXSLTElement(ctx);
        }
        try {
            return compileXSLTElement(ctx);
        } catch (Exception e) {
            // XTSE0340: invalid match pattern must be reported even in FC mode.
            // Match patterns are validated at compile time on declarations (xsl:template),
            // not deferred as runtime fallback behavior.
            String msg = e.getMessage();
            if (msg != null && msg.contains("XTSE0340")) {
                if (e instanceof SAXException) {
                    throw (SAXException) e;
                }
                throw new SAXException(msg, e);
            }
            // In forward-compatible mode, recover from other static errors
            List<XSLTNode> fallbacks = new ArrayList<>();
            for (XSLTNode child : ctx.children) {
                if (child instanceof FallbackNode) {
                    fallbacks.add(child);
                }
            }
            if (!fallbacks.isEmpty()) {
                return new SequenceNode(fallbacks);
            }
            // At top level or with no fallback: silently ignore
            return null;
        }
    }

    /**
     * Checks if an element is in forward-compatible mode, considering
     * the global stylesheet version, per-element version attributes,
     * and inherited version from parent elements on the stack.
     */
    boolean isElementForwardCompatible(ElementContext ctx) {
        if (forwardCompatible) {
            return true;
        }
        double elemVer = ctx.effectiveVersion > 0
            ? ctx.effectiveVersion : getEffectiveVersion();
        if (elemVer > 3.0) {
            return true;
        }
        // Check inherited version from ancestors still on the element stack
        // (the current element has been popped, but its parent may still be there)
        if (getEffectiveVersion() > 3.0) {
            return true;
        }
        return false;
    }

    /**
     * Compiles an XSLT instruction element.
     */
    private XSLTNode compileXSLTElement(ElementContext ctx) throws SAXException {
        // XTSE0090: Validate attributes are allowed on this element
        CompilerUtils.validateAllowedAttributes(this, ctx);
        
        // Per XSLT spec, xsl:fallback is silently ignored inside instructions that
        // accept a sequence constructor as content. For instructions with restricted
        // content models (apply-imports, apply-templates, call-template, choose, etc.),
        // xsl:fallback is NOT permitted and should trigger XTSE0010 from validation.
        // Extract fallback for the default case (unknown elements in FC mode need fallback).
        List<XSLTNode> fallbackNodes = new ArrayList<>();
        if (!CompilerUtils.hasRestrictedContentModel(ctx.localName)) {
            Iterator<XSLTNode> fbIter = ctx.children.iterator();
            while (fbIter.hasNext()) {
                XSLTNode child = fbIter.next();
                if (child instanceof FallbackNode) {
                    fallbackNodes.add(child);
                    fbIter.remove();
                }
            }
        }
        
        switch (ctx.localName) {
            case "stylesheet":
            case "transform":
            case "package":
                // XTSE0010: xsl:stylesheet/transform/package must be the document element
                if (elementStack.size() > 1) {
                    throw new SAXException("XTSE0010: xsl:" + ctx.localName + 
                        " must be the document element, not nested inside another element");
                }
                DeclarationCompiler.processStylesheetElement(this, ctx);
                return null;
                
            case "import":
                // XTSE0010: xsl:import must be a top-level element
                if (!isTopLevel()) {
                    throw new SAXException("XTSE0010: xsl:import is only allowed at the top level");
                }
                DeclarationCompiler.processImport(this, ctx);
                return null;
                
            case "include":
                DeclarationCompiler.processInclude(this, ctx);
                return null;
                
            case "use-package":
                // XSLT 3.0: xsl:use-package must be a top-level element
                if (!isTopLevel()) {
                    throw new SAXException("XTSE0010: xsl:use-package is only allowed at the top level");
                }
                if (stylesheetVersion < 3.0) {
                    throw new SAXException("xsl:use-package is only allowed in XSLT 3.0 or later");
                }
                DeclarationCompiler.processUsePackage(this, ctx);
                return null;
                
            case "expose":
                // XSLT 3.0: xsl:expose must be a top-level element in a package
                if (!isTopLevel()) {
                    throw new SAXException("XTSE0010: xsl:expose is only allowed at the top level");
                }
                if (stylesheetVersion < 3.0) {
                    throw new SAXException("xsl:expose is only allowed in XSLT 3.0 or later");
                }
                // Per XSLT 3.0 §3.8: ignore xsl:expose with a higher version
                // attribute (forwards compatibility) to avoid XTSE3010 for
                // visibility rules that may differ in future versions
                if (isElementForwardCompatible(ctx)) {
                    return null;
                }
                DeclarationCompiler.processExpose(this, ctx);
                return null;
                
            case "accept":
                // XSLT 3.0: xsl:accept must be a child of xsl:use-package
                if (stylesheetVersion < 3.0) {
                    throw new SAXException("xsl:accept is only allowed in XSLT 3.0 or later");
                }
                return DeclarationCompiler.compileAccept(this, ctx);
                
            case "override":
                // XSLT 3.0: xsl:override must be a child of xsl:use-package
                if (stylesheetVersion < 3.0) {
                    throw new SAXException("xsl:override is only allowed in XSLT 3.0 or later");
                }
                return DeclarationCompiler.compileOverride(this, ctx);
                
            case "import-schema":
                DeclarationCompiler.processImportSchema(this, ctx);
                return null;
                
            case "template":
                // XTSE0010: xsl:template must be a top-level element
                // Also allowed inside xsl:override (within xsl:use-package)
                if (!isTopLevel() && !isInsideOverride()) {
                    throw new SAXException("XTSE0010: xsl:template is only allowed at the top level");
                }
                DeclarationCompiler.processTemplateElement(this, ctx);
                return null;
                
            case "variable":
                return InstructionCompiler.compileVariable(this, ctx,
                    isTopLevel() || isOverrideDirectChild());
                
            case "param":
                return InstructionCompiler.compileParam(this, ctx,
                    isTopLevel() || isOverrideDirectChild());
                
            case "context-item":
                return InstructionCompiler.compileContextItem(this, ctx);
                
            case "global-context-item":
                InstructionCompiler.processGlobalContextItem(this, ctx);
                return null;
                
            case "output":
                DeclarationCompiler.processOutputElement(this, ctx);
                return null;
                
            case "key":
                DeclarationCompiler.processKeyElement(this, ctx);
                return null;
                
            case "attribute-set":
                if (!isTopLevel() && !isInsideOverride()) {
                    throw new SAXException("XTSE0010: xsl:attribute-set is only allowed at the top level");
                }
                DeclarationCompiler.processAttributeSetElement(this, ctx);
                return null;
                
            case "strip-space":
                DeclarationCompiler.processStripSpace(this, ctx);
                return null;
                
            case "preserve-space":
                DeclarationCompiler.processPreserveSpace(this, ctx);
                return null;
                
            case "namespace-alias":
                DeclarationCompiler.processNamespaceAlias(this, ctx);
                return null;
                
            // Instructions that produce output - NOT allowed at top level (XTSE0010)
            case "value-of":
                CompilerUtils.validateNotTopLevel(this, ctx.localName);
                return InstructionCompiler.compileValueOf(this, ctx);
                
            case "text":
                CompilerUtils.validateNotTopLevel(this, ctx.localName);
                return InstructionCompiler.compileText(this, ctx);
                
            case "element":
                CompilerUtils.validateNotTopLevel(this, ctx.localName);
                return InstructionCompiler.compileElement2(this, ctx);
                
            case "attribute":
                CompilerUtils.validateNotTopLevel(this, ctx.localName);
                return InstructionCompiler.compileAttribute(this, ctx);
                
            case "namespace":
                CompilerUtils.validateNotTopLevel(this, ctx.localName);
                return InstructionCompiler.compileNamespace(this, ctx);
                
            case "comment":
                CompilerUtils.validateNotTopLevel(this, ctx.localName);
                return InstructionCompiler.compileComment(this, ctx);
                
            case "processing-instruction":
                CompilerUtils.validateNotTopLevel(this, ctx.localName);
                return InstructionCompiler.compilePI(this, ctx);
                
            case "copy":
                CompilerUtils.validateNotTopLevel(this, ctx.localName);
                return InstructionCompiler.compileCopy(this, ctx);
                
            case "copy-of":
                CompilerUtils.validateNotTopLevel(this, ctx.localName);
                return InstructionCompiler.compileCopyOf(this, ctx);
                
            case "apply-templates":
                CompilerUtils.validateNotTopLevel(this, ctx.localName);
                return InstructionCompiler.compileApplyTemplates(this, ctx);
                
            case "call-template":
                CompilerUtils.validateNotTopLevel(this, ctx.localName);
                return InstructionCompiler.compileCallTemplate(this, ctx);
                
            case "apply-imports":
                CompilerUtils.validateNotTopLevel(this, ctx.localName);
                return InstructionCompiler.compileApplyImports(this, ctx);
                
            case "next-match":
                CompilerUtils.validateNotTopLevel(this, ctx.localName);
                return InstructionCompiler.compileNextMatch(this, ctx);
                
            case "for-each":
                CompilerUtils.validateNotTopLevel(this, ctx.localName);
                return InstructionCompiler.compileForEach(this, ctx);
                
            case "stream":
                CompilerUtils.validateNotTopLevel(this, ctx.localName);
                return InstructionCompiler.compileStream(this, ctx);
                
            case "source-document":
                CompilerUtils.validateNotTopLevel(this, ctx.localName);
                return InstructionCompiler.compileSourceDocument(this, ctx);
                
            case "iterate":
                CompilerUtils.validateNotTopLevel(this, ctx.localName);
                return InstructionCompiler.compileIterate(this, ctx);
                
            case "next-iteration":
                CompilerUtils.validateNotTopLevel(this, ctx.localName);
                CompilerUtils.validateLexicallyInIterate(this, "xsl:next-iteration");
                return InstructionCompiler.compileNextIteration(this, ctx);
                
            case "break":
                CompilerUtils.validateNotTopLevel(this, ctx.localName);
                CompilerUtils.validateLexicallyInIterate(this, "xsl:break");
                return InstructionCompiler.compileBreak(this, ctx);
                
            case "on-completion":
                CompilerUtils.validateNotTopLevel(this, ctx.localName);
                CompilerUtils.validateDirectChildOfIterate(this, "xsl:on-completion");
                String onCompSelect = ctx.attributes.get("select");
                if (onCompSelect != null && !ctx.children.isEmpty()) {
                    throw new SAXException("XTSE3125: xsl:on-completion must not have " +
                        "both a select attribute and children");
                }
                if (onCompSelect != null) {
                    XPathExpression onCompExpr = compileExpression(onCompSelect);
                    return new OnCompletionNode(onCompExpr);
                }
                return new OnCompletionNode(new SequenceNode(new ArrayList<>(ctx.children)));
                
            case "fork":
                CompilerUtils.validateNotTopLevel(this, ctx.localName);
                return InstructionCompiler.compileFork(this, ctx);
                
            case "sequence":
                CompilerUtils.validateNotTopLevel(this, ctx.localName);
                return InstructionCompiler.compileSequence(this, ctx);
                
            case "result-document":
                CompilerUtils.validateNotTopLevel(this, ctx.localName);
                return InstructionCompiler.compileResultDocument(this, ctx);
                
            case "for-each-group":
                CompilerUtils.validateNotTopLevel(this, ctx.localName);
                return InstructionCompiler.compileForEachGroup(this, ctx);
                
            case "perform-sort":
                CompilerUtils.validateNotTopLevel(this, ctx.localName);
                return InstructionCompiler.compilePerformSort(this, ctx);
                
            case "merge":
                CompilerUtils.validateNotTopLevel(this, ctx.localName);
                return InstructionCompiler.compileMerge(this, ctx);
                
            case "merge-source":
                return InstructionCompiler.compileMergeSource(this, ctx);
                
            case "merge-key":
                return InstructionCompiler.compileMergeKey(this, ctx);
                
            case "merge-action":
                return InstructionCompiler.compileMergeAction(this, ctx);
                
            case "evaluate":
                CompilerUtils.validateNotTopLevel(this, ctx.localName);
                return InstructionCompiler.compileEvaluate(this, ctx);
                
            case "analyze-string":
                CompilerUtils.validateNotTopLevel(this, ctx.localName);
                return InstructionCompiler.compileAnalyzeString(this, ctx);
                
            case "matching-substring":
            case "non-matching-substring":
                CompilerUtils.validateNotTopLevel(this, ctx.localName);
                // These are handled as children of analyze-string
                return new SequenceNode(ctx.children);
                
            case "try": {
                if (maxProcessorVersion < 3.0) {
                    return InstructionCompiler.compileUnknownXSLTInstruction(this, ctx, fallbackNodes);
                }
                CompilerUtils.validateNotTopLevel(this, ctx.localName);
                return InstructionCompiler.compileTry(this, ctx);
            }
            case "catch": {
                if (maxProcessorVersion < 3.0) {
                    return InstructionCompiler.compileUnknownXSLTInstruction(this, ctx, fallbackNodes);
                }
            }
                CompilerUtils.validateNotTopLevel(this, ctx.localName);
                String catchSelect = ctx.attributes.get("select");
                XPathExpression catchSelectExpr = null;
                if (catchSelect != null && !catchSelect.isEmpty()) {
                    if (!ctx.children.isEmpty()
                            && hasNonWhitespaceContent(ctx.children)) {
                        throw new SAXException("XTSE3150: xsl:catch with select attribute " +
                            "must have empty content");
                    }
                    catchSelectExpr = compileExpression(catchSelect);
                }
                String catchErrors = ctx.attributes.get("errors");
                XSLTNode catchContent = ctx.children.isEmpty() ? null : new SequenceNode(ctx.children);
                return new CatchNode(catchContent, catchErrors, catchSelectExpr);
                
            case "assert":
                CompilerUtils.validateNotTopLevel(this, ctx.localName);
                return InstructionCompiler.compileAssert(this, ctx);
                
            case "on-empty":
                CompilerUtils.validateNotTopLevel(this, ctx.localName);
                return InstructionCompiler.compileOnEmpty(this, ctx);
                
            case "on-non-empty":
                CompilerUtils.validateNotTopLevel(this, ctx.localName);
                return InstructionCompiler.compileOnNonEmpty(this, ctx);
                
            case "where-populated":
                CompilerUtils.validateNotTopLevel(this, ctx.localName);
                return InstructionCompiler.compileWherePopulated(this, ctx);
                
            case "if":
                CompilerUtils.validateNotTopLevel(this, ctx.localName);
                return InstructionCompiler.compileIf(this, ctx);
                
            case "choose":
                CompilerUtils.validateNotTopLevel(this, ctx.localName);
                return InstructionCompiler.compileChoose(this, ctx);
                
            case "when":
                CompilerUtils.validateNotTopLevel(this, ctx.localName);
                return InstructionCompiler.compileWhen(this, ctx);
                
            case "otherwise":
                CompilerUtils.validateNotTopLevel(this, ctx.localName);
                return InstructionCompiler.compileOtherwise(this, ctx);
                
            case "sort":
                return InstructionCompiler.compileSort(this, ctx);
                
            case "with-param":
                return InstructionCompiler.compileWithParam(this, ctx);
                
            case "number":
                return InstructionCompiler.compileNumber(this, ctx);
                
            case "message":
                return InstructionCompiler.compileMessage(this, ctx);
                
            case "fallback":
                return InstructionCompiler.compileFallback(this, ctx);
                
            case "decimal-format":
                // XTSE0010: xsl:decimal-format must be at top level
                if (!isTopLevel()) {
                    throw new SAXException("XTSE0010: xsl:decimal-format is only allowed at the top level");
                }
                DeclarationCompiler.processDecimalFormat(this, ctx);
                return null;
                
            case "character-map":
                // XSLT 2.0 character mapping
                // XTSE0010: xsl:character-map must be at top level
                if (!isTopLevel()) {
                    throw new SAXException("XTSE0010: xsl:character-map is only allowed at the top level");
                }
                DeclarationCompiler.processCharacterMap(this, ctx);
                return null;
                
            case "output-character":
                // XSLT 2.0 output character - child of xsl:character-map
                // This is handled by processCharacterMap when processing the parent
                return DeclarationCompiler.compileOutputCharacter(this, ctx);
                
            case "map":
                CompilerUtils.validateNotTopLevel(this, ctx.localName);
                return new MapConstructionNode(new SequenceNode(ctx.children));
                
            case "map-entry":
                CompilerUtils.validateNotTopLevel(this, ctx.localName);
                return InstructionCompiler.compileMapEntry(this, ctx);
                
            case "document":
                CompilerUtils.validateNotTopLevel(this, ctx.localName);
                return InstructionCompiler.compileDocumentConstructor(this, ctx);
                
            case "array":
                // XSLT 3.0 array construction - stub implementation
                CompilerUtils.validateNotTopLevel(this, ctx.localName);
                // TODO: Implement array construction
                return new SequenceNode(ctx.children);
                
            case "array-member":
                // XSLT 3.0 array member - child of xsl:array
                CompilerUtils.validateNotTopLevel(this, ctx.localName);
                return new SequenceNode(ctx.children);
                
            case "accumulator":
                DeclarationCompiler.processAccumulator(this, ctx);
                return null;
                
            case "accumulator-rule":
                return DeclarationCompiler.compileAccumulatorRule(this, ctx);
                
            case "mode":
                DeclarationCompiler.processModeDeclaration(this, ctx);
                return null;
                
            case "function":
                DeclarationCompiler.processFunctionElement(this, ctx);
                return null;
                
            default:
                return InstructionCompiler.compileUnknownXSLTInstruction(this, ctx, fallbackNodes);
        }
    }
    
    void validateNotAVT(String elementName, String attrName, String value) throws SAXException {
        if (value == null) {
            return;
        }
        String trimmed = value.trim();
        // Q{uri}local EQName syntax uses braces but is not an AVT
        if (trimmed.startsWith("Q{") || trimmed.contains(" Q{")) {
            return;
        }
        if (trimmed.contains("{") && trimmed.contains("}")) {
            throw new SAXException("XTSE0020: The " + attrName + " attribute on " + elementName + 
                                  " is not an attribute value template");
        }
    }
    
    /**
     * Parses an attribute value as a yes-or-no boolean.
     * Returns true for yes/true/1, false for anything else (including null).
     * Callers should validate the value first if invalid input should be rejected.
     */
    boolean parseYesOrNo(String value) {
        if (value == null) {
            return false;
        }
        String trimmed = value.trim();
        return "yes".equals(trimmed) || "true".equals(trimmed) || "1".equals(trimmed);
    }

    /**
     * Validates that an attribute value is a valid yes-or-no type.
     * XTSE0020: Values must be: yes, no, true, false, 1, or 0 (case-sensitive, whitespace trimmed).
     */
    void validateYesOrNo(String elementName, String attrName, String value) throws SAXException {
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
     * Checks whether a global variable's select expression references the
     * variable being defined. A global variable is not in scope within its
     * own declaration (XPST0008).
     */
    void checkSelfReference(XPathExpression selectExpr, String varLocalName) throws SAXException {
        String exprStr = selectExpr.getExpressionString();
        if (exprStr == null) {
            return;
        }
        String varRef = "$" + varLocalName;
        int idx = exprStr.indexOf(varRef);
        while (idx >= 0) {
            int afterIdx = idx + varRef.length();
            if (afterIdx >= exprStr.length()) {
                throw new SAXException("XPST0008: Variable $" + varLocalName +
                    " is not in scope within its own declaration");
            }
            char afterChar = exprStr.charAt(afterIdx);
            if (!Character.isLetterOrDigit(afterChar) && afterChar != '_' && afterChar != '-'
                    && afterChar != '.') {
                throw new SAXException("XPST0008: Variable $" + varLocalName +
                    " is not in scope within its own declaration");
            }
            idx = exprStr.indexOf(varRef, afterIdx);
        }
    }

    /**
     * Validates that a function type in an 'as' attribute has a return type
     * when parameter types are specified. Per the XPath grammar, TypedFunctionTest
     * requires "function(ParamTypes) as ReturnType".
     */
    void validateFunctionTypeAs(String asType) throws SAXException {
        if (asType == null) {
            return;
        }
        String trimmed = asType.trim();
        if (!trimmed.startsWith("function(")) {
            return;
        }
        int openParen = trimmed.indexOf('(');
        int depth = 0;
        int closeParen = -1;
        for (int i = openParen; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    closeParen = i;
                    break;
                }
            }
        }
        if (closeParen < 0) {
            return;
        }
        String inner = trimmed.substring(openParen + 1, closeParen).trim();
        if (inner.equals("*") || inner.isEmpty()) {
            return;
        }
        String afterParen = trimmed.substring(closeParen + 1).trim();
        if (!afterParen.startsWith("as ") && !afterParen.startsWith("as\t")) {
            throw new SAXException("XPST0003: Function type with parameter types " +
                "requires a return type: " + asType);
        }
    }

    /**
     * Validates a boolean attribute on xsl:output (no AVTs on xsl:output).
     * XSLT 2.0: accepts "yes" or "no" only.
     * XSLT 3.0: accepts "yes", "no", "true", "false", "1", "0" (case-sensitive).
     */
    void validateOutputBoolean(ElementContext ctx, String attrName) throws SAXException {
        String value = ctx.attributes.get(attrName);
        if (value == null || value.isEmpty()) {
            return;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return;
        }
        if ("yes".equals(trimmed) || "no".equals(trimmed)) {
            return;
        }
        if (stylesheetVersion >= 3.0) {
            if ("true".equals(trimmed) || "false".equals(trimmed) ||
                    "1".equals(trimmed) || "0".equals(trimmed)) {
                return;
            }
        }
        throw new SAXException("XTSE0020: Invalid value for " + attrName +
            " attribute on xsl:output: got '" + value + "'");
    }

    /**
     * Checks if a character is a valid XML PubidChar per the XML specification.
     * PubidChar ::= #x20 | #xD | #xA | [a-zA-Z0-9] | [-'()+,./:=?;!*#@$_%]
     */
    boolean isPubidChar(char c) {
        if (c == 0x20 || c == 0x0D || c == 0x0A) {
            return true;
        }
        if (c >= 'a' && c <= 'z') {
            return true;
        }
        if (c >= 'A' && c <= 'Z') {
            return true;
        }
        if (c >= '0' && c <= '9') {
            return true;
        }
        return c == '-' || c == '\'' || c == '(' || c == ')' || c == '+' ||
               c == ',' || c == '.' || c == '/' || c == ':' || c == '=' ||
               c == '?' || c == ';' || c == '!' || c == '*' || c == '#' ||
               c == '@' || c == '$' || c == '_' || c == '%';
    }

    /**
     * Returns true if the current element is a direct child of the stylesheet element.
     * 
     * <p>When called from endElement, the current element has already been popped,
     * so the stack contains only ancestors. If the stack has exactly 1 element
     * and it's xsl:stylesheet or xsl:transform, we're at the top level.
     */
    boolean isTopLevel() {
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
     * Checks if the current element is inside an xsl:override context.
     * xsl:override children (template, function, variable, etc.) should be
     * treated as top-level declarations for compilation purposes.
     */
    boolean isInsideOverride() {
        for (ElementContext ancestor : elementStack) {
            if (XSLT_NS.equals(ancestor.namespaceURI)
                    && "override".equals(ancestor.localName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks if the current element is a direct child of xsl:override.
     * Only direct children (variable, param) should be treated as top-level;
     * elements nested inside xsl:function or xsl:template within the override
     * are local declarations.
     */
    boolean isOverrideDirectChild() {
        if (elementStack.isEmpty()) {
            return false;
        }
        ElementContext parent = elementStack.peek();
        return XSLT_NS.equals(parent.namespaceURI)
                && "override".equals(parent.localName);
    }

    /**
     * Validates that an element has no content (text or child elements).
     * XTSE0260: Elements required to be empty cannot have content.
     * Note: Even whitespace text nodes preserved with xml:space="preserve" are an error.
     */
    void validateEmptyElement(ElementContext ctx, String elementName) throws SAXException {
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
     * Stores xsl:expose declarations for processing at end of compilation.
     * Package-private for DeclarationCompiler.
     */
    final List<DeclarationCompiler.ExposeDeclaration> exposeDeclarations = new ArrayList<>();

    /**
     * Returns the effective base URI for resolving relative references.
     * During external entity expansion the SAX locator reports the
     * entity's system ID, which may differ from the stylesheet's own
     * base URI.  If the locator provides a system ID we use it;
     * otherwise we fall back to the static {@code baseUri}.
     */
    String getEffectiveBaseUri() {
        if (locator != null) {
            String sysId = locator.getSystemId();
            if (sysId != null) {
                return sysId;
            }
        }
        return baseUri;
    }

    /**
     * Resolves a URI relative to the stylesheet base URI.
     */
    String resolveUri(String uri) {
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

    /**
     * Expands a mode QName to Clark notation {uri}localname for proper comparison.
     * Mode names like foo:a and moo:a should be equal if both prefixes map to the same URI.
     */
    String expandModeQName(String mode) throws SAXException {
        return expandModeQName(mode, false);
    }
    
    /**
     * Expands a mode QName to Clark notation, optionally checking for reserved namespaces.
     */
    String expandModeQName(String mode, boolean checkReserved) throws SAXException {
        if (mode == null || mode.isEmpty()) {
            return mode;
        }
        
        String trimmed = mode.trim();
        
        // Handle special mode values
        if ("#default".equals(trimmed) || "#all".equals(trimmed) || 
            "#current".equals(trimmed) || "#unnamed".equals(trimmed)) {
            return trimmed;
        }
        
        // Handle EQName syntax: Q{uri}localname (XSLT 3.0)
        if (trimmed.startsWith("Q{")) {
            int closeBrace = trimmed.indexOf('}');
            if (closeBrace >= 2) {
                String uri = trimmed.substring(2, closeBrace);
                String localPart = trimmed.substring(closeBrace + 1);
                if (checkReserved && isReservedNamespace(uri)) {
                    throw new SAXException("XTSE0080: Reserved namespace '" + uri + 
                        "' cannot be used in mode name '" + trimmed + "'");
                }
                return "{" + uri + "}" + localPart;
            }
        }
        
        int colon = trimmed.indexOf(':');
        if (colon > 0) {
            String prefix = trimmed.substring(0, colon);
            String localPart = trimmed.substring(colon + 1);
            String uri = resolve(prefix);
            if (uri != null) {
                if (checkReserved && isReservedNamespace(uri)) {
                    throw new SAXException("XTSE0080: Reserved namespace '" + uri + 
                        "' cannot be used in mode name '" + trimmed + "'");
                }
                return "{" + uri + "}" + localPart;
            }
            throw new SAXException("XTSE0280: Namespace prefix '" + prefix + 
                "' in mode '" + trimmed + "' is not declared");
        }
        
        return trimmed;
    }

    boolean hasNonWhitespaceContent(List<XSLTNode> children) {
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
     * Returns a copy of children with xsl:fallback elements removed.
     * Per XSLT spec, xsl:fallback is only activated for unknown instructions.
     */
    List<XSLTNode> filterFallback(List<XSLTNode> children) {
        List<XSLTNode> result = new ArrayList<>(children.size());
        for (XSLTNode child : children) {
            if (!(child instanceof FallbackNode)) {
                result.add(child);
            }
        }
        return result;
    }

    /**
     * Tests whether children contain anything other than xsl:fallback
     * and whitespace-only text nodes.
     */
    boolean hasNonFallbackContent(List<XSLTNode> children) {
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
    boolean hasNonCatchOrFallbackContent(List<XSLTNode> children) {
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
     * XTSE1660 for Basic (non-schema-aware) processors.
     *
     * <p>validation="strict" always requires a schema-aware processor.
     * For XSLT 3.0 processors, "lax" and "preserve" are treated as "strip"
     * (section 3.6.3). For XSLT 2.0, all three are static errors.
     *
     * @param value the validation attribute value (may be null)
     * @param elementName the name of the element (for error messages)
     * @return the parsed ValidationMode, or null if value is null/empty
     * @throws SAXException if the value is invalid or not allowed
     */
    ValidationMode parseValidationMode(String value, String elementName)
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
            double effectiveProcessorVersion = maxProcessorVersion > 0
                    ? maxProcessorVersion : 3.0;
            if (effectiveProcessorVersion < 3.0) {
                throw new SAXException("XTSE1660: validation='" + value
                    + "' on " + elementName
                    + " requires a schema-aware processor");
            }
            return ValidationMode.STRIP;
        }
        throw new SAXException("Invalid validation value on "
            + elementName + ": " + value
            + ". Expected: strict, lax, preserve, or strip");
    }

    static boolean isKnownLREAttribute(String localName) {
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
    
    static String expandedParamName(String namespaceURI, String localName) {
        if (namespaceURI != null && !namespaceURI.isEmpty()) {
            return "{" + namespaceURI + "}" + localName;
        }
        return localName;
    }
    
    String expandQName(String qname) throws SAXException {
        return expandQName(qname, false);
    }
    
    /**
     * Expands a QName to Clark notation, optionally checking for reserved namespaces.
     * XTSE0080: Reserved namespaces cannot be used in component names (templates, variables, etc.)
     */
    String expandQName(String qname, boolean checkReserved) throws SAXException {
        if (qname == null || qname.isEmpty()) {
            return qname;
        }
        
        // Trim whitespace (names may have surrounding spaces)
        String trimmedQname = qname.trim();
        
        // Handle EQName syntax: Q{uri}localname (XSLT 3.0)
        if (trimmedQname.startsWith("Q{")) {
            int closeBrace = trimmedQname.indexOf('}');
            if (closeBrace >= 2) {
                String uri = trimmedQname.substring(2, closeBrace);
                String localPart = trimmedQname.substring(closeBrace + 1);
                if (checkReserved && isReservedNamespace(uri)) {
                    if (!(XSLT_NS.equals(uri) && "initial-template".equals(localPart))) {
                        throw new SAXException("XTSE0080: Reserved namespace '" + uri + 
                            "' cannot be used in the name '" + qname + "'");
                    }
                }
                if (uri.isEmpty()) {
                    return localPart;
                }
                return "{" + uri + "}" + localPart;
            }
        }
        
        int colonPos = trimmedQname.indexOf(':');
        if (colonPos > 0) {
            // Prefixed name - expand to Clark notation
            String prefix = trimmedQname.substring(0, colonPos);
            String localName = trimmedQname.substring(colonPos + 1);
            String uri = resolve(prefix);
            if (uri != null && !uri.isEmpty()) {
                // XTSE0080: Check for reserved namespaces in component names
                // Exception: xsl:initial-template is always allowed (system-defined name)
                if (checkReserved && isReservedNamespace(uri)) {
                    if (!(XSLT_NS.equals(uri) && "initial-template".equals(localName))) {
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
        return trimmedQname;
    }
    
    /**
     * Returns true if the namespace URI is reserved (cannot be used in component names).
     * Per XSLT 2.0 spec section 3.4, reserved namespaces include XSLT, XML Schema, 
     * and other W3C standard namespaces.
     */
    boolean isReservedNamespace(String uri) {
        if (uri == null) {
            return false;
        }
        return XSLT_NS.equals(uri) ||
               "http://www.w3.org/XML/1998/namespace".equals(uri) ||
               "http://www.w3.org/2001/XMLSchema".equals(uri) ||
               "http://www.w3.org/2001/XMLSchema-instance".equals(uri) ||
               "http://www.w3.org/2005/xpath-functions".equals(uri) ||
               "http://www.w3.org/2005/xpath-functions/map".equals(uri) ||
               "http://www.w3.org/2005/xpath-functions/array".equals(uri) ||
               "http://www.w3.org/2005/xpath-functions/math".equals(uri);
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
    QName parseQName(String qnameStr, Map<String, String> namespaces) throws SAXException {
        return parseQName(qnameStr, namespaces, false);
    }
    
    /**
     * Parses a QName string, optionally checking for reserved namespaces (XTSE0080).
     */
    QName parseQName(String qnameStr, Map<String, String> namespaces, boolean checkReserved) throws SAXException {
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
    
    /**
     * Expands an attribute set name to its expanded form: {namespace}localname
     * or just localname if no namespace.
     */
    String expandAttributeSetName(String name, Map<String, String> namespaceBindings) throws SAXException {
        return expandAttributeSetName(name, namespaceBindings, false);
    }
    
    /**
     * Expands an attribute set name, optionally checking for reserved namespaces.
     */
    String expandAttributeSetName(String name, Map<String, String> namespaceBindings, boolean checkReserved) throws SAXException {
        // Handle EQName syntax: Q{uri}localname (XSLT 3.0)
        if (name.startsWith("Q{")) {
            int closeBrace = name.indexOf('}');
            if (closeBrace >= 2) {
                String uri = name.substring(2, closeBrace);
                String localPart = name.substring(closeBrace + 1);
                if (checkReserved && isReservedNamespace(uri)) {
                    throw new SAXException("XTSE0080: Reserved namespace '" + uri + 
                        "' cannot be used in the attribute set name '" + name + "'");
                }
                return "{" + uri + "}" + localPart;
            }
        }
        
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
    String expandAttributeSetNames(String names, Map<String, String> namespaceBindings) throws SAXException {
        if (names == null || names.isEmpty()) {
            return names;
        }
        StringBuilder result = new StringBuilder();
        for (String name : PatternValidator.splitOnWhitespace(names)) {
            if (result.length() > 0) {
                result.append(' ');
            }
            result.append(expandAttributeSetName(name.trim(), namespaceBindings));
        }
        return result.toString();
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
    String resolveElementNameToUri(String pattern, Map<String, String> namespaces) 
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
                if (uri.isEmpty()) {
                    return localPart;
                }
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

    /**
     * Resolves a namespace prefix for pattern compilation.
     * Checks namespaces map first, then element stack bindings.
     */
    String resolveNamespaceForPrefix(String prefix) {
        String uri = namespaces.get(prefix);
        if (uri == null) {
            uri = lookupNamespaceUri(prefix);
        }
        return uri;
    }

    /**
     * Returns the list of deferred pattern predicate validations.
     * Used by PatternValidator during stylesheet compilation.
     */
    List<String[]> getDeferredPatternValidations() {
        return deferredPatternValidations;
    }

    /**
     * Looks up a namespace URI by prefix in the current namespace context.
     */
    String lookupNamespaceUri(String prefix) {
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

    /**
     * Resolves a static AVT string by expanding {@code {expr}} placeholders
     * using static variables.
     *
     * @param avtValue the AVT string containing {@code {expr}} placeholders
     * @param attrName the attribute name (for error messages)
     * @return the resolved string
     */
    String resolveStaticAVTString(String avtValue, String attrName)
            throws SAXException {
        StringBuilder result = new StringBuilder();
        int len = avtValue.length();
        int i = 0;
        while (i < len) {
            int braceStart = avtValue.indexOf('{', i);
            if (braceStart < 0) {
                result.append(avtValue.substring(i));
                break;
            }
            if (braceStart + 1 < len && avtValue.charAt(braceStart + 1) == '{') {
                result.append(avtValue.substring(i, braceStart + 1));
                i = braceStart + 2;
                continue;
            }
            result.append(avtValue.substring(i, braceStart));
            int braceEnd = avtValue.indexOf('}', braceStart + 1);
            if (braceEnd < 0) {
                throw new SAXException("Unterminated AVT in shadow attribute _" + attrName);
            }
            String expr = avtValue.substring(braceStart + 1, braceEnd);
            XPathValue val = evaluateStaticExpression(expr, attrName, null);
            result.append(val.asString());
            i = braceEnd + 1;
        }
        return result.toString();
    }

    /**
     * Resolves a shadow attribute value at compile time using static parameters.
     * Shadow attributes use AVT syntax like {@code _name="{$param}"} where
     * {@code $param} is a static parameter. Falls back to the regular attribute
     * if no shadow attribute is present.
     *
     * @param ctx the element context
     * @param attrName the attribute name (without _ prefix)
     * @return the resolved value, or the regular attribute value, or null
     */
    String resolveStaticShadowAttribute(ElementContext ctx, String attrName) 
            throws SAXException {
        String shadowValue = ctx.shadowAttributes.get(attrName);
        if (shadowValue == null) {
            return ctx.attributes.get(attrName);
        }
        return resolveStaticAVTString(shadowValue, attrName);
    }

    XPathValue evaluateStaticExpression(String select, String varName, String baseURI) 
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
     * Checks for XTSE3450: conflicting static variable/parameter declarations
     * across import precedences.
     *
     * <p>When a higher-precedence declaration arrives for a name already registered
     * at lower precedence, the declarations must be consistent (same kind and value).
     * When a lower-precedence declaration arrives after a higher-precedence one,
     * it is silently ignored.
     */
    void checkStaticDeclarationConflict(String localName, boolean isParam,
            String valueStr, int prec) throws SAXException {
        Object[] existing = staticDeclarationInfo.get(localName);
        if (existing == null) {
            staticDeclarationInfo.put(localName, new Object[]{
                Boolean.valueOf(isParam), valueStr, Integer.valueOf(prec)});
            return;
        }
        boolean existIsParam = ((Boolean) existing[0]).booleanValue();
        String existValue = (String) existing[1];
        int existPrec = ((Integer) existing[2]).intValue();
        if (prec < existPrec) {
            return;
        }
        boolean kindMatch = (isParam == existIsParam);
        boolean valueMatch = (valueStr == null && existValue == null)
            || (valueStr != null && valueStr.equals(existValue));
        if (!kindMatch || !valueMatch) {
            String newKind = isParam ? "parameter" : "variable";
            String existKind = existIsParam ? "parameter" : "variable";
            throw new SAXException("XTSE3450: Conflicting static declarations for $" +
                localName + ": " + existKind + " (import precedence " + existPrec +
                ") vs " + newKind + " (import precedence " + prec + ")");
        }
        // Consistent: update tracking to new (higher or equal) precedence declaration
        staticDeclarationInfo.put(localName, new Object[]{
            Boolean.valueOf(isParam), valueStr, Integer.valueOf(prec)});
    }

    /**
     * Returns the effective XSLT version at the current point in the stylesheet.
     * This considers xsl:version attributes on literal result element ancestors
     * which establish backwards compatibility mode (XSLT 1.0 behavior).
     */
    double getEffectiveVersion() {
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
    String getEffectiveDefaultMode() {
        if (currentProcessingContext != null) {
            return currentProcessingContext.defaultMode;
        }
        if (!elementStack.isEmpty()) {
            return elementStack.peek().defaultMode;
        }
        return null;
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
    private boolean evaluateUseWhen(String expr) throws SAXException {
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
        } catch (XPathSyntaxException e) {
            throw new SAXException("XPST0003: Static error in use-when expression: " + 
                expr + " - " + e.getMessage(), e);
        } catch (XPathException e) {
            String msg = e.getMessage();
            // Propagate known error codes
            if (msg != null && (msg.startsWith("XPDY") || msg.startsWith("XPST")
                    || msg.startsWith("FODC") || msg.startsWith("XPTY")
                    || msg.startsWith("XTSE") || msg.startsWith("FORG"))) {
                throw new SAXException(msg, e);
            }
            // Dynamic errors from use-when context (e.g., no context node)
            throw new SAXException("Error in use-when expression: " + 
                expr + " - " + msg, e);
        } catch (Exception e) {
            throw new SAXException("Error in use-when expression: " + 
                expr + " - " + e.getMessage(), e);
        }
    }
    
    /**
     * Minimal XPath context for use-when and static variable evaluation.
     * Provides static functions and variable bindings. Supports context item
     * switching for path expressions and simple map operators within static
     * variable evaluation (e.g., parse-xml(...)//e).
     */
    private class UseWhenContext implements XPathContext {
        private final Map<String, XPathValue> localVariables;
        private final String explicitBaseURI;
        private final XPathNode contextNode;
        private final XPathValue contextItem;
        private final int contextPosition;
        private final int contextSize;
        
        UseWhenContext() {
            this.localVariables = new HashMap<>();
            this.explicitBaseURI = null;
            this.contextNode = null;
            this.contextItem = null;
            this.contextPosition = 0;
            this.contextSize = 0;
        }
        
        UseWhenContext(String baseURI) {
            this.localVariables = new HashMap<>();
            this.explicitBaseURI = baseURI;
            this.contextNode = null;
            this.contextItem = null;
            this.contextPosition = 0;
            this.contextSize = 0;
        }
        
        private UseWhenContext(Map<String, XPathValue> vars, String baseURI,
                XPathNode node, XPathValue item, int pos, int sz) {
            this.localVariables = vars;
            this.explicitBaseURI = baseURI;
            this.contextNode = node;
            this.contextItem = item;
            this.contextPosition = pos;
            this.contextSize = sz;
        }
        
        @Override
        public XPathNode getContextNode() { return contextNode; }
        
        @Override
        public XPathValue getContextItem() { return contextItem; }
        
        @Override
        public int getContextPosition() { return contextPosition; }
        
        @Override
        public int getContextSize() { return contextSize; }
        
        @Override
        public boolean isContextItemUndefined() {
            return contextNode == null && contextItem == null;
        }
        
        @Override
        public XPathValue getVariable(String namespaceURI, String localName) {
            XPathValue value = localVariables.get(localName);
            if (value != null) {
                return value;
            }
            value = staticVariables.get(localName);
            if (value != null) {
                return value;
            }
            throw new RuntimeException("XPST0008: Variable $" + localName +
                " is not available in a use-when expression (only static variables are accessible)");
        }
        
        @Override
        public String resolveNamespacePrefix(String prefix) {
            String uri = namespaces.get(prefix);
            if (uri == null && "xs".equals(prefix)) {
                return XSD_NAMESPACE;
            }
            return uri;
        }
        
        @Override
        public XPathFunctionLibrary getFunctionLibrary() {
            if (maxProcessorVersion < 3.0) {
                return UseWhenFunctionLibrary.XSLT20;
            }
            return UseWhenFunctionLibrary.XSLT30;
        }
        
        @Override
        public XPathContext withContextNode(XPathNode node) {
            return new UseWhenContext(localVariables, explicitBaseURI,
                node, null, contextPosition, contextSize);
        }
        
        @Override
        public XPathContext withContextItem(XPathValue item) {
            if (item != null && item.isNodeSet()) {
                XPathNodeSet ns = item.asNodeSet();
                if (!ns.isEmpty()) {
                    return withContextNode(ns.iterator().next());
                }
            }
            return new UseWhenContext(localVariables, explicitBaseURI,
                null, item, contextPosition, contextSize);
        }
        
        @Override
        public XPathContext withPositionAndSize(int position, int size) {
            return new UseWhenContext(localVariables, explicitBaseURI,
                contextNode, contextItem, position, size);
        }
        
        @Override
        public XPathContext withVariable(String namespaceURI, String localName, XPathValue value) {
            Map<String, XPathValue> newVars = new HashMap<>(localVariables);
            newVars.put(localName, value);
            return new UseWhenContext(newVars, explicitBaseURI,
                contextNode, contextItem, contextPosition, contextSize);
        }
        
        @Override
        public String getStaticBaseURI() {
            if (explicitBaseURI != null) {
                return explicitBaseURI;
            }
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

    /**
     * Restricted function library for XSLT 2.0 use-when expressions.
     *
     * <p>Per XSLT 2.0 section 3.8, only core XPath 2.0 functions plus
     * system-property, function-available, type-available, and element-available
     * are available. doc() returns empty sequence and doc-available() returns false.
     */
    private static class UseWhenFunctionLibrary implements XPathFunctionLibrary {

        static final UseWhenFunctionLibrary XSLT20 = new UseWhenFunctionLibrary();

        /**
         * XSLT 3.0 static expression function library.
         * Delegates to the full XSLT function library but blocks functions
         * that require runtime context (current-output-uri, current-group, etc.).
         * Per XSLT 3.0 section 20.3.8, current-output-uri() is not available
         * in static expressions.
         */
        static final XPathFunctionLibrary XSLT30 = new StaticExpressionLibrary30();

        private static final Set<String> ALLOWED_XSLT_FUNCTIONS = new HashSet<String>();
        static {
            ALLOWED_XSLT_FUNCTIONS.add("system-property");
            ALLOWED_XSLT_FUNCTIONS.add("function-available");
            ALLOWED_XSLT_FUNCTIONS.add("type-available");
            ALLOWED_XSLT_FUNCTIONS.add("element-available");
        }

        @Override
        public boolean hasFunction(String namespaceURI, String localName) {
            if (namespaceURI == null || namespaceURI.isEmpty()) {
                if (CoreFunctionLibrary.INSTANCE.hasFunction(null, localName)) {
                    return true;
                }
                if (ALLOWED_XSLT_FUNCTIONS.contains(localName)) {
                    return true;
                }
                if ("doc-available".equals(localName)) {
                    return true;
                }
                return false;
            }
            return CoreFunctionLibrary.INSTANCE.hasFunction(namespaceURI, localName);
        }

        @Override
        public XPathValue invokeFunction(String namespaceURI, String localName,
                List<XPathValue> args, XPathContext context) throws XPathException {
            if (namespaceURI == null || namespaceURI.isEmpty()) {
                // doc-available() always returns false in 2.0 use-when
                if ("doc-available".equals(localName)) {
                    return XPathBoolean.FALSE;
                }
                if (ALLOWED_XSLT_FUNCTIONS.contains(localName)) {
                    return XSLTFunctionLibrary.INSTANCE.invokeFunction(
                        namespaceURI, localName, args, context);
                }
                if (CoreFunctionLibrary.INSTANCE.hasFunction(null, localName)) {
                    return CoreFunctionLibrary.INSTANCE.invokeFunction(
                        namespaceURI, localName, args, context);
                }
            } else {
                if (CoreFunctionLibrary.INSTANCE.hasFunction(namespaceURI, localName)) {
                    return CoreFunctionLibrary.INSTANCE.invokeFunction(
                        namespaceURI, localName, args, context);
                }
            }
            throw new XPathException("XPST0017: Function " + localName +
                " is not available in use-when expressions (XSLT 2.0)");
        }

        @Override
        public int getArgumentCount(String namespaceURI, String localName) {
            if (ALLOWED_XSLT_FUNCTIONS.contains(localName)) {
                return XSLTFunctionLibrary.INSTANCE.getArgumentCount(namespaceURI, localName);
            }
            return CoreFunctionLibrary.INSTANCE.getArgumentCount(namespaceURI, localName);
        }
    }

    /**
     * Function library for XSLT 3.0 static expressions.
     * Delegates to the full XSLT function library but rejects functions
     * that depend on runtime transformation context.
     */
    private static class StaticExpressionLibrary30 implements XPathFunctionLibrary {

        private static final Set<String> DISALLOWED_FUNCTIONS = new HashSet<String>();
        static {
            DISALLOWED_FUNCTIONS.add("current");
            DISALLOWED_FUNCTIONS.add("current-group");
            DISALLOWED_FUNCTIONS.add("current-grouping-key");
            DISALLOWED_FUNCTIONS.add("current-merge-group");
            DISALLOWED_FUNCTIONS.add("current-merge-key");
            DISALLOWED_FUNCTIONS.add("current-output-uri");
            DISALLOWED_FUNCTIONS.add("key");
            DISALLOWED_FUNCTIONS.add("regex-group");
            DISALLOWED_FUNCTIONS.add("accumulator-before");
            DISALLOWED_FUNCTIONS.add("accumulator-after");
        }

        @Override
        public boolean hasFunction(String namespaceURI, String localName) {
            if ((namespaceURI == null || namespaceURI.isEmpty())
                    && DISALLOWED_FUNCTIONS.contains(localName)) {
                return false;
            }
            return XSLTFunctionLibrary.INSTANCE.hasFunction(namespaceURI, localName);
        }

        @Override
        public XPathValue invokeFunction(String namespaceURI, String localName,
                List<XPathValue> args, XPathContext context) throws XPathException {
            if ((namespaceURI == null || namespaceURI.isEmpty())
                    && DISALLOWED_FUNCTIONS.contains(localName)) {
                throw new XPathException("XPST0017: Function " + localName +
                    "() is not available in static expressions");
            }
            return XSLTFunctionLibrary.INSTANCE.invokeFunction(
                namespaceURI, localName, args, context);
        }

        @Override
        public int getArgumentCount(String namespaceURI, String localName) {
            return XSLTFunctionLibrary.INSTANCE.getArgumentCount(namespaceURI, localName);
        }
    }

    XPathExpression compileExpression(String expr) throws SAXException {
        try {
            StaticTypeContext typeCtx = buildStaticTypeContext();
            return XPathExpression.compile(expr, this, typeCtx);
        } catch (XPathSyntaxException e) {
            throw new SAXException("Invalid XPath expression: " + expr + " - " + e.getMessage(), e);
        }
    }

    StaticTypeContext buildStaticTypeContext() {
        return new CompilerStaticTypeContext(-1);
    }

    StaticTypeContext buildStaticTypeContext(double overrideVersion) {
        return new CompilerStaticTypeContext(overrideVersion);
    }

    /**
     * StaticTypeContext implementation that resolves variable types from
     * in-scope declarations and function types from the function library.
     */
    private class CompilerStaticTypeContext implements StaticTypeContext {

        private final double versionOverride;

        CompilerStaticTypeContext(double versionOverride) {
            this.versionOverride = versionOverride;
        }

        @Override
        public SequenceType getVariableType(String nsUri, String localName) {
            return null;
        }

        @Override
        public double getXsltVersion() {
            if (versionOverride > 0) {
                return versionOverride;
            }
            return getEffectiveVersion();
        }

        @Override
        public double getProcessorVersion() {
            return maxProcessorVersion;
        }

        @Override
        public boolean isStrictTypeChecking() {
            return strictTypeChecking;
        }

        @Override
        public Function resolveFunction(String nsUri, String localName, int arity) {
            return XSLTFunctionLibrary.INSTANCE.getFunction(nsUri, localName, arity);
        }

        @Override
        public SequenceType getContextItemType() {
            return SequenceType.NODE;
        }
    }

    Pattern compilePattern(String pattern) throws SAXException {
        return PatternValidator.compilePattern(this, pattern);
    }

    AttributeValueTemplate parseAvt(String value) throws SAXException {
        try {
            StaticTypeContext typeCtx = buildStaticTypeContext();
            return AttributeValueTemplate.parse(value, this, typeCtx);
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
        if ("xml".equals(prefix)) {
            return "http://www.w3.org/XML/1998/namespace";
        }
        String uri = namespaces.get(prefix);
        if (uri == null && "xs".equals(prefix)) {
            return XSD_NAMESPACE;
        }
        return uri;
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
        return PatternValidator.findOrGeneratePrefix(namespace, bindings);
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
    List<String> splitOnWhitespace(String s) {
        return PatternValidator.splitOnWhitespace(s);
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
        return PatternValidator.toCanonicalLexical(typeName, value);
    }

    /**
     * Output handler that detects whether content was produced for xsl:on-empty support.
     * Attributes and namespaces are forwarded to the parent handler (they apply to the
     * containing element), while other content is buffered.
     */
}

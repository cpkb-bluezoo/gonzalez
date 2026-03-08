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
import java.io.InputStream;
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
import java.util.Properties;
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
    private int minImportedPrecedence = -1;  // Lowest precedence from imported modules
    private int localTemplateCounter = 0;  // Fallback counter when no resolver
    private boolean importsAllowed = true;
    private boolean precedenceAssigned = false;
    private boolean isPrincipalStylesheet = true;
    
    // Tracks explicit mode attribute values for XTSE0545 conflict detection
    // Key: "modeName|attrName", Value: explicit attribute value
    private final Map<String, String> modeAttributeValues = new HashMap<>();
    
    // Deferred pattern predicate validations (resolved after all xsl:function are registered)
    private final List<String[]> deferredPatternValidations = new ArrayList<>();
    
    // Tracks whether the current template being compiled has a match/name pattern
    // Used by xsl:context-item to detect invalid use="absent" in match-only templates
    private boolean currentTemplateHasMatch = false;
    private boolean currentTemplateHasName = false;

    // Collects override declarations from templates compiled inside xsl:override
    private List<OverrideDeclaration> pendingOverrideDeclarations;
    
    // Forward-compatible processing mode
    private double stylesheetVersion = 1.0;
    private boolean forwardCompatible = false;
    
    // Maximum XSLT version the processor should advertise.
    // When set below 3.0, XSLT 3.0 instructions like xsl:try are
    // treated as unknown, enabling xsl:fallback processing.
    private double maxProcessorVersion = 3.0;

    // Static type checking strictness (default: pessimistic)
    private boolean strictTypeChecking = true;
    
    // Default validation mode (XSLT 2.0+)
    private ValidationMode defaultValidation = ValidationMode.STRIP;
    
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
    
    // Static variables (XSLT 3.0): evaluated at compile time, available in use-when.
    // For included modules, this map may be shared with the parent compiler.
    private Map<String, XPathValue> staticVariables = new HashMap<>();

    // External static parameter overrides (set before compilation)
    private final Map<String, String> staticParameterOverrides = new HashMap<>();
    
    // Track static declarations for XTSE3450 conflict detection across import precedences.
    // Key: variable/param local name; Value: Object[]{Boolean isParam, String select, Integer prec}
    private final Map<String, Object[]> staticDeclarationInfo = new HashMap<>();
    
    // Simplified stylesheet: root element is a literal result element with xsl:version attribute
    // Per XSLT 1.0 Section 2.3: equivalent to xsl:stylesheet containing template match="/"
    private boolean isSimplifiedStylesheet = false;
    private XSLTNode simplifiedStylesheetBody = null;
    
    // XSLT 3.0 package support
    private PackageResolver packageResolver = null;
    String packageName = null;  // Package-private for access in compilePackage
    String packageVersion = null;  // Package-private for access in compilePackage
    final List<CompiledPackage.PackageDependency> packageDependencies = new ArrayList<>();  // Package-private
    private boolean declaredModesEnabled = false;
    private final Set<String> usedModeNames = new HashSet<>();

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
        
        try (InputStream is = StylesheetCompiler.class.getResourceAsStream("/META-INF/xslt-attributes.properties")) {
            if (is != null) {
                Properties props = new Properties();
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
        } catch (IOException e) {
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
        
        // Run streamability analysis and store results
        StreamabilityAnalyzer analyzer = new StreamabilityAnalyzer();
        StreamabilityAnalyzer.StylesheetStreamability analysis = analyzer.analyze(stylesheet);
        stylesheet.setStreamabilityAnalysis(analysis);

        // XTSE3430: Validate that templates in streamable modes are streamable
        try {
            validateStreamableModes(stylesheet, analysis, analyzer);
        } catch (SAXException e) {
            throw new TransformerConfigurationException(e.getMessage(), e);
        }
        
        // XTSE3085: Validate declared-modes (xsl:package with declared-modes="yes")
        try {
            validateDeclaredModes(stylesheet);
        } catch (SAXException e) {
            throw new TransformerConfigurationException(e.getMessage(), e);
        }
        
        // XTSE3080: An executable package must not have abstract components
        try {
            validateNoAbstractComponents(stylesheet);
        } catch (SAXException e) {
            throw new TransformerConfigurationException(e.getMessage(), e);
        }
        
        // XTSE3010/XTSE3020: Validate xsl:expose declarations
        try {
            validateExposeDeclarations(stylesheet);
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
     * XTSE3085: Validates that all used modes are explicitly declared when declared-modes is enabled.
     */
    private void validateDeclaredModes(CompiledStylesheet stylesheet) throws SAXException {
        if (!declaredModesEnabled) {
            return;
        }
        Map<String, ModeDeclaration> declaredModes = stylesheet.getModeDeclarations();
        for (String usedMode : usedModeNames) {
            if (!declaredModes.containsKey(usedMode)) {
                String displayName = "#default".equals(usedMode) ? "the unnamed mode" : "mode '" + usedMode + "'";
                throw new SAXException("XTSE3085: " + displayName
                    + " is used but not declared (declared-modes is enabled)");
            }
        }
    }

    /**
     * XTSE3080: An executable (top-level) package must not contain abstract components.
     * Abstract components can only appear in library packages intended to be used
     * via xsl:use-package with overrides.
     */
    private void validateNoAbstractComponents(CompiledStylesheet stylesheet) throws SAXException {
        if (!isPrincipalStylesheet) {
            return;
        }
        for (TemplateRule rule : stylesheet.getTemplateRules()) {
            if (rule.getVisibility() == ComponentVisibility.ABSTRACT) {
                String name = rule.getName();
                if (name == null) {
                    name = "(match template)";
                }
                throw new SAXException("XTSE3080: Abstract template '" + name +
                    "' found in executable package");
            }
        }
        for (UserFunction func : stylesheet.getUserFunctions().values()) {
            if (func.getVisibility() == ComponentVisibility.ABSTRACT) {
                throw new SAXException("XTSE3080: Abstract function '" +
                    func.getLocalName() + "' found in executable package");
            }
        }
    }

    /**
     * XTSE3010/XTSE3020: Validates xsl:expose declarations against the stylesheet components.
     *
     * <p>XTSE3010: The exposed visibility must be compatible with the component's
     * potential visibility (e.g., cannot make a private component public).
     *
     * <p>XTSE3020: A non-wildcard name in xsl:expose must match at least one component.
     */
    private void validateExposeDeclarations(CompiledStylesheet stylesheet) throws SAXException {
        if (exposeDeclarations.isEmpty()) {
            return;
        }
        
        Set<String> templateNames = new HashSet<>();
        for (TemplateRule rule : stylesheet.getTemplateRules()) {
            if (rule.getName() != null) {
                templateNames.add(rule.getName());
            }
        }
        
        Set<String> functionNames = new HashSet<>(stylesheet.getUserFunctions().keySet());
        
        for (ExposeDeclaration expose : exposeDeclarations) {
            String namesPattern = expose.namesPattern;
            boolean isWildcard = "*".equals(namesPattern);
            
            if (!isWildcard) {
                boolean matched = false;
                String[] names = namesPattern.trim().split("\\s+");
                for (String name : names) {
                    if ("*".equals(name)) {
                        matched = true;
                        continue;
                    }
                    boolean nameMatched = false;
                    if (expose.componentType == AcceptDeclaration.ComponentType.TEMPLATE
                            || expose.componentType == AcceptDeclaration.ComponentType.ALL) {
                        if (templateNames.contains(name)) {
                            nameMatched = true;
                        }
                    }
                    if (expose.componentType == AcceptDeclaration.ComponentType.FUNCTION
                            || expose.componentType == AcceptDeclaration.ComponentType.ALL) {
                        if (functionNames.contains(name)) {
                            nameMatched = true;
                        }
                    }
                    if (nameMatched) {
                        matched = true;
                        validateExposeCompatibility(stylesheet, expose.componentType, 
                            name, expose.visibility);
                    }
                }
                if (!matched) {
                    throw new SAXException("XTSE3020: xsl:expose matches no " +
                        expose.componentType + " components with names '" + namesPattern + "'");
                }
            }
        }
    }

    /**
     * XTSE3010: Validates that the exposed visibility is compatible with the
     * component's declared (potential) visibility.
     */
    private void validateExposeCompatibility(CompiledStylesheet stylesheet,
            AcceptDeclaration.ComponentType componentType, String name,
            ComponentVisibility exposed) throws SAXException {
        ComponentVisibility declared = null;
        if (componentType == AcceptDeclaration.ComponentType.TEMPLATE
                || componentType == AcceptDeclaration.ComponentType.ALL) {
            for (TemplateRule rule : stylesheet.getTemplateRules()) {
                if (name.equals(rule.getName())) {
                    declared = rule.getVisibility();
                    break;
                }
            }
        }
        if (declared == null) {
            return;
        }
        if (declared == ComponentVisibility.PRIVATE && exposed == ComponentVisibility.PUBLIC) {
            throw new SAXException("XTSE3010: Cannot expose private template '" + name +
                "' as public");
        }
        if (declared == ComponentVisibility.PRIVATE && exposed == ComponentVisibility.FINAL) {
            throw new SAXException("XTSE3010: Cannot expose private template '" + name +
                "' as final");
        }
        if (declared == ComponentVisibility.PRIVATE && exposed == ComponentVisibility.ABSTRACT) {
            throw new SAXException("XTSE3010: Cannot expose private template '" + name +
                "' as abstract");
        }
    }

    /**
     * XTSE3430: Validates that all templates in streamable modes are streamable.
     * Checks for FREE_RANGING expressions, crawling (descendant axis) patterns,
     * xsl:number, non-motionless match patterns, and backwards-compatible instructions.
     */
    private void validateStreamableModes(CompiledStylesheet stylesheet,
                                          StreamabilityAnalyzer.StylesheetStreamability analysis,
                                          StreamabilityAnalyzer analyzer)
            throws SAXException {
        Map<String, ModeDeclaration> modes = stylesheet.getModeDeclarations();
        Map<TemplateRule, StreamabilityAnalyzer.TemplateStreamability> templateAnalysis =
            analysis.getTemplateAnalysis();

        for (Map.Entry<String, ModeDeclaration> modeEntry : modes.entrySet()) {
            ModeDeclaration mode = modeEntry.getValue();
            if (!mode.isStreamable()) {
                continue;
            }
            String modeName = mode.getName();

            // Check every template in this streamable mode
            for (Map.Entry<TemplateRule, StreamabilityAnalyzer.TemplateStreamability> entry
                    : templateAnalysis.entrySet()) {
                TemplateRule template = entry.getKey();
                String templateMode = template.getMode();

                // Match default mode (null) to "#default" key, or named modes
                boolean inThisMode;
                if (modeName == null || "#default".equals(modeName)) {
                    inThisMode = (templateMode == null || "#default".equals(templateMode));
                } else {
                    inThisMode = modeName.equals(templateMode);
                }

                if (!inThisMode) {
                    continue;
                }

                StreamabilityAnalyzer.TemplateStreamability ts = entry.getValue();
                if (ts.requiresDocumentBuffering()) {
                    throwStreamabilityError(template, modeName,
                        ts.getBufferingReasons());
                }

                XSLTNode body = template.getBody();

                // XTSE3430: Template in streamable mode must not use
                // current-group() (si-fork-116) — it is not available
                // outside xsl:for-each-group scope in streaming.
                if (body != null) {
                    int cgCalls = countCurrentGroupCalls(body, 0);
                    if (cgCalls > 0) {
                        throw new SAXException("XTSE3430: Template in " +
                            "streamable mode uses current-group() which " +
                            "is not available in this context");
                    }
                }

                // XTSE3430: Fork-specific streaming constraint validation
                if (body != null) {
                    validateForkStreamability(body, 0);
                }

                // XTSE3430: Crawling instructions — apply-templates or
                // for-each with descendant-axis select expressions that
                // are not wrapped in outermost()/innermost() create
                // non-streamable crawling patterns (XSLT 3.0 section 19)
                if (body != null) {
                    if (containsCrawlingInstruction(body, 0)) {
                        throw new SAXException(
                            "XTSE3430: Template in streamable mode " +
                            "uses a crawling instruction (descendant " +
                            "axis in apply-templates or for-each)");
                    }
                }

                // XTSE3430: xsl:number requires sibling counting
                if (body != null) {
                    if (containsNumberInstruction(body, 0)) {
                        throw new SAXException(
                            "XTSE3430: xsl:number in streamable mode " +
                            "is not streamable (requires sibling access)");
                    }
                }

                // XTSE3430: Validate for-each-group within streaming
                // templates for non-motionless keys, patterns, and
                // current-group() usage violations
                if (body != null) {
                    validateStreamingForEachGroup(body, 0);
                }

                // XTSE3430: Match patterns in streamable modes must be
                // motionless — predicates that depend on position, last(),
                // or string value are not motionless
                Pattern matchPat = template.getMatchPattern();
                if (matchPat != null) {
                    if (hasNonMotionlessPredicate(matchPat)) {
                        throw new SAXException(
                            "XTSE3430: Match pattern '" + matchPat +
                            "' in streamable mode is not motionless");
                    }
                }

                // XTSE3430: Instructions in backwards-compatible mode
                // (version="1.0") are roaming and free-ranging (section 3.9.1)
                if (body != null) {
                    if (containsBackwardsCompatInstruction(body, 0)) {
                        throw new SAXException(
                            "XTSE3430: Template in streamable mode " +
                            "contains an instruction in backwards-" +
                            "compatible mode (version=\"1.0\"), which " +
                            "is roaming and free-ranging");
                    }
                }
            }
        }
    }

    /**
     * Throws a formatted XTSE3430 error for a non-streamable template.
     */
    private static void throwStreamabilityError(TemplateRule template,
                                                 String modeName,
                                                 List<String> reasons)
            throws SAXException {
        StringBuilder sb = new StringBuilder();
        sb.append("XTSE3430: Template");
        String matchStr = template.getMatchPattern() != null
            ? template.getMatchPattern().toString() : null;
        if (matchStr != null) {
            sb.append(" matching '");
            sb.append(matchStr);
            sb.append("'");
        }
        String tName = template.getName();
        if (tName != null) {
            sb.append(" named '");
            sb.append(tName);
            sb.append("'");
        }
        sb.append(" in streamable mode");
        if (modeName != null && !"#default".equals(modeName)) {
            sb.append(" '");
            sb.append(modeName);
            sb.append("'");
        }
        sb.append(" is not streamable");
        if (!reasons.isEmpty()) {
            sb.append(": ");
            sb.append(reasons.get(0));
        }
        throw new SAXException(sb.toString());
    }

    /**
     * Checks whether an XSLT node tree contains an xsl:number instruction.
     */
    private static boolean containsNumberInstruction(XSLTNode node, int depth) {
        if (node == null || depth > 50) {
            return false;
        }
        if (node instanceof NumberNode) {
            return true;
        }
        if (node instanceof SequenceNode) {
            SequenceNode seq = (SequenceNode) node;
            List<XSLTNode> children = seq.getChildren();
            if (children != null) {
                for (int i = 0; i < children.size(); i++) {
                    if (containsNumberInstruction(children.get(i), depth + 1)) {
                        return true;
                    }
                }
            }
        }
        if (node instanceof CopyNode) {
            XSLTNode content =
                ((CopyNode) node).getContent();
            if (containsNumberInstruction(content, depth + 1)) {
                return true;
            }
        }
        if (node instanceof ForEachNode) {
            XSLTNode body =
                ((ForEachNode) node).getBody();
            if (containsNumberInstruction(body, depth + 1)) {
                return true;
            }
        }
        if (node instanceof IfNode) {
            XSLTNode content =
                ((IfNode) node).getContent();
            if (containsNumberInstruction(content, depth + 1)) {
                return true;
            }
        }
        if (node instanceof ChooseNode) {
            ChooseNode choose = (ChooseNode) node;
            for (WhenNode when : choose.getWhens()) {
                XSLTNode wContent = when.getContent();
                if (containsNumberInstruction(wContent, depth + 1)) {
                    return true;
                }
            }
            XSLTNode ow = choose.getOtherwise();
            if (containsNumberInstruction(ow, depth + 1)) {
                return true;
            }
        }
        if (node instanceof ElementNode) {
            XSLTNode content =
                ((ElementNode) node).getContent();
            if (containsNumberInstruction(content, depth + 1)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks whether a match pattern has a predicate that is not motionless.
     * Patterns with positional, last(), or string-value-dependent predicates
     * are not motionless and thus not valid in streamable modes.
     * Type checks and ancestor attribute access are considered motionless.
     */
    private static boolean hasNonMotionlessPredicate(Pattern pat) {
        if (pat == null) {
            return false;
        }
        if (pat instanceof PredicatedPattern) {
            return true;
        }
        if (pat instanceof UnionPattern) {
            UnionPattern up = (UnionPattern) pat;
            Pattern[] alts = up.getAlternatives();
            for (int i = 0; i < alts.length; i++) {
                if (hasNonMotionlessPredicate(alts[i])) {
                    return true;
                }
            }
            return false;
        }
        if (pat instanceof IntersectPattern) {
            IntersectPattern ip = (IntersectPattern) pat;
            return hasNonMotionlessPredicate(ip.getLeft())
                || hasNonMotionlessPredicate(ip.getRight());
        }
        if (pat instanceof ExceptPattern) {
            ExceptPattern ep = (ExceptPattern) pat;
            return hasNonMotionlessPredicate(ep.getLeft())
                || hasNonMotionlessPredicate(ep.getRight());
        }

        if (pat instanceof AtomicPattern) {
            AtomicPattern ap = (AtomicPattern) pat;
            List<String> preds = ap.getPredicates();
            if (preds != null) {
                for (int i = 0; i < preds.size(); i++) {
                    String p = preds.get(i);
                    boolean nm = isNonMotionlessPredicateStr(p);
                    if (nm) {
                        return true;
                    }
                }
            }
            return false;
        }

        if (pat instanceof AbstractPattern) {
            AbstractPattern ap = (AbstractPattern) pat;
            String predStr = ap.getPredicateStr();
            if (predStr != null && isNonMotionlessPredicateStr(predStr)) {
                return true;
            }
        }

        if (pat instanceof PathPattern) {
            PatternStep[] steps = ((PathPattern) pat).getSteps();
            if (steps != null) {
                for (int i = 0; i < steps.length; i++) {
                    String stepPred = steps[i].predicateStr;
                    if (stepPred != null) {
                        boolean atomicNode = isAtomicContentNodeTest(
                            steps[i].nodeTest);
                        if (isNonMotionlessPredicateStr(stepPred,
                                atomicNode)) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    /**
     * Checks whether a predicate string represents a non-motionless
     * predicate for streaming pattern matching.
     */
    private static boolean isNonMotionlessPredicateStr(String predStr) {
        return isNonMotionlessPredicateStr(predStr, false);
    }

    /**
     * Checks whether a predicate string represents a non-motionless
     * predicate for streaming pattern matching.
     *
     * @param predStr the predicate expression string
     * @param atomicContentNode true if the step matches text, comment, PI,
     *        or attribute nodes whose string value requires no descendant
     *        traversal
     */
    private static boolean isNonMotionlessPredicateStr(String predStr,
            boolean atomicContentNode) {
        if (predStr == null) {
            return false;
        }
        String trimmed = predStr.trim();
        if (trimmed.isEmpty()) {
            return false;
        }

        // Numeric literal → positional predicate → non-motionless
        try {
            Double.parseDouble(trimmed);
            return true;
        } catch (NumberFormatException e) {
            // continue
        }

        try {
            XPathExpression predExpr = XPathExpression.compile(trimmed);
            Expr compiled = predExpr.getCompiledExpr();

            // Type checks (instance of, treat as) are motionless
            if (compiled instanceof TypeExpr) {
                return false;
            }

            // For text, comment, PI, and attribute nodes, accessing the
            // string value of self (.) is motionless because these nodes
            // have no descendant content to traverse
            if (!atomicContentNode && accessesStringValueOfSelf(compiled)) {
                return true;
            }

            StreamabilityAnalyzer.ExpressionStreamability es =
                StreamingClassifier.classify(predExpr);

            if (es == StreamabilityAnalyzer.ExpressionStreamability.MOTIONLESS) {
                return false;
            }

            // GROUNDED from ancestor/parent attribute access is motionless
            // in streaming (ancestor stack is maintained)
            if (es == StreamabilityAnalyzer.ExpressionStreamability.GROUNDED) {
                if (isAncestorAttributeOnly(compiled)) {
                    return false;
                }
            }

            return true;
        } catch (Exception e) {
            return true;
        }
    }

    /**
     * Checks whether a compiled expression accesses the string value
     * of the context item (self::node() / "."). In a streaming match
     * pattern predicate, this is non-motionless because atomizing the
     * matched node requires consuming its descendant text content.
     */
    private static boolean accessesStringValueOfSelf(Expr expr) {
        if (expr == null) {
            return false;
        }
        if (expr instanceof FunctionCall) {
            FunctionCall fc = (FunctionCall) expr;
            String name = fc.getLocalName();
            List<Expr> args = fc.getArguments();
            for (int i = 0; i < args.size(); i++) {
                Expr arg = args.get(i);
                if (isSelfReference(arg) && atomizesArgument(name)) {
                    return true;
                }
                if (accessesStringValueOfSelf(arg)) {
                    return true;
                }
            }
            return false;
        }
        if (expr instanceof BinaryExpr) {
            BinaryExpr be = (BinaryExpr) expr;
            if (isSelfReference(be.getLeft()) ||
                isSelfReference(be.getRight())) {
                return true;
            }
            return accessesStringValueOfSelf(be.getLeft()) ||
                   accessesStringValueOfSelf(be.getRight());
        }
        if (expr instanceof FilterExpr) {
            FilterExpr fe = (FilterExpr) expr;
            return accessesStringValueOfSelf(fe.getPrimary());
        }
        if (expr instanceof PathExpr) {
            PathExpr pe = (PathExpr) expr;
            return accessesStringValueOfSelf(pe.getFilter()) ||
                   accessesStringValueOfSelf(pe.getPath());
        }
        return false;
    }

    /**
     * Returns true if the expression is a reference to the context item
     * ({@code .} or {@code self::node()}).
     */
    private static boolean isSelfReference(Expr expr) {
        if (expr instanceof ContextItemExpr) {
            return true;
        }
        if (expr instanceof LocationPath) {
            LocationPath lp = (LocationPath) expr;
            List<Step> steps = lp.getSteps();
            if (steps.size() == 1) {
                Step step = steps.get(0);
                if (step.getAxis() == Step.Axis.SELF) {
                    return true;
                }
            }
        }
        // current() in a pattern predicate refers to the context item
        if (expr instanceof FunctionCall) {
            FunctionCall fc = (FunctionCall) expr;
            if ("current".equals(fc.getLocalName())) {
                List<Expr> args = fc.getArguments();
                if (args == null || args.isEmpty()) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Returns true if the node test matches a node type whose string value
     * is intrinsic (no descendant traversal required): text, comment,
     * processing-instruction, or attribute nodes.
     */
    private static boolean isAtomicContentNodeTest(NodeTest test) {
        return test instanceof TextTest
            || test instanceof CommentTest
            || test instanceof PITest
            || test instanceof AttributeTest;
    }

    /**
     * Returns true if the named function atomizes (accesses string/numeric
     * value of) its arguments. Functions that only inspect node identity
     * or structural properties return false.
     */
    private static boolean atomizesArgument(String functionName) {
        if ("name".equals(functionName) ||
            "local-name".equals(functionName) ||
            "namespace-uri".equals(functionName) ||
            "node-name".equals(functionName) ||
            "nilled".equals(functionName) ||
            "count".equals(functionName) ||
            "empty".equals(functionName) ||
            "exists".equals(functionName) ||
            "boolean".equals(functionName) ||
            "not".equals(functionName) ||
            "generate-id".equals(functionName) ||
            "has-children".equals(functionName) ||
            "deep-equal".equals(functionName)) {
            return false;
        }
        return true;
    }

    /**
     * Checks whether a GROUNDED expression only accesses ancestor attributes,
     * which are available in a streaming context (maintained on a stack).
     * Returns true only if the expression actually uses a parent/ancestor
     * axis to reach attributes, and has no other source of grounded-ness
     * (like last(), root(), etc.).
     */
    private static boolean isAncestorAttributeOnly(Expr expr) {
        if (expr == null) {
            return false;
        }
        return hasAncestorAxisStep(expr)
            && allPathsUseAncestorAttributeOnly(expr);
    }

    private static boolean hasAncestorAxisStep(Expr expr) {
        if (expr == null) {
            return false;
        }
        if (expr instanceof LocationPath) {
            LocationPath lp = (LocationPath) expr;
            List<Step> steps = lp.getSteps();
            for (int i = 0; i < steps.size(); i++) {
                Step.Axis axis = steps.get(i).getAxis();
                if (axis == Step.Axis.PARENT || axis == Step.Axis.ANCESTOR
                        || axis == Step.Axis.ANCESTOR_OR_SELF) {
                    return true;
                }
            }
        }
        if (expr instanceof PathExpr) {
            PathExpr pe = (PathExpr) expr;
            return hasAncestorAxisStep(pe.getFilter())
                || hasAncestorAxisStep(pe.getPath());
        }
        if (expr instanceof BinaryExpr) {
            BinaryExpr be = (BinaryExpr) expr;
            return hasAncestorAxisStep(be.getLeft())
                || hasAncestorAxisStep(be.getRight());
        }
        if (expr instanceof FunctionCall) {
            FunctionCall fc = (FunctionCall) expr;
            List<Expr> args = fc.getArguments();
            for (int i = 0; i < args.size(); i++) {
                if (hasAncestorAxisStep(args.get(i))) {
                    return true;
                }
            }
        }
        if (expr instanceof FilterExpr) {
            FilterExpr fe = (FilterExpr) expr;
            return hasAncestorAxisStep(fe.getPrimary());
        }
        return false;
    }

    private static boolean allPathsUseAncestorAttributeOnly(Expr expr) {
        if (expr == null) {
            return true;
        }
        if (expr instanceof Literal) {
            return true;
        }
        if (expr instanceof VariableReference) {
            return true;
        }
        if (expr instanceof LocationPath) {
            LocationPath lp = (LocationPath) expr;
            List<Step> steps = lp.getSteps();
            for (int i = 0; i < steps.size(); i++) {
                Step.Axis axis = steps.get(i).getAxis();
                if (axis == Step.Axis.PARENT || axis == Step.Axis.ANCESTOR
                        || axis == Step.Axis.ANCESTOR_OR_SELF
                        || axis == Step.Axis.ATTRIBUTE
                        || axis == Step.Axis.SELF
                        || axis == Step.Axis.NAMESPACE) {
                    continue;
                }
                return false;
            }
            return true;
        }
        if (expr instanceof PathExpr) {
            PathExpr pe = (PathExpr) expr;
            return allPathsUseAncestorAttributeOnly(pe.getFilter())
                && allPathsUseAncestorAttributeOnly(pe.getPath());
        }
        if (expr instanceof BinaryExpr) {
            BinaryExpr be = (BinaryExpr) expr;
            return allPathsUseAncestorAttributeOnly(be.getLeft())
                && allPathsUseAncestorAttributeOnly(be.getRight());
        }
        if (expr instanceof FunctionCall) {
            FunctionCall fc = (FunctionCall) expr;
            List<Expr> args = fc.getArguments();
            for (int i = 0; i < args.size(); i++) {
                if (!allPathsUseAncestorAttributeOnly(args.get(i))) {
                    return false;
                }
            }
            return true;
        }
        if (expr instanceof FilterExpr) {
            FilterExpr fe = (FilterExpr) expr;
            if (!allPathsUseAncestorAttributeOnly(fe.getPrimary())) {
                return false;
            }
            List<Expr> preds = fe.getPredicates();
            for (int i = 0; i < preds.size(); i++) {
                if (!allPathsUseAncestorAttributeOnly(preds.get(i))) {
                    return false;
                }
            }
            return true;
        }
        return true;
    }

    /**
     * Checks whether an XSLT node tree contains a crawling instruction:
     * apply-templates or for-each with a descendant-axis select expression
     * not wrapped in outermost()/innermost(). Also detects FLWOR
     * expressions with descendant-axis bindings.
     */
    private static boolean containsCrawlingInstruction(XSLTNode node,
                                                        int depth) {
        if (node == null || depth > 50) {
            return false;
        }
        if (node instanceof ApplyTemplatesNode) {
            ApplyTemplatesNode at = (ApplyTemplatesNode) node;
            List<XPathExpression> exprs = at.getExpressions();
            if (exprs != null && !exprs.isEmpty()) {
                XPathExpression selectExpr = exprs.get(0);
                if (isDirectCrawlingSelect(selectExpr)) {
                    return true;
                }
            }
        }
        if (node instanceof ForEachNode) {
            ForEachNode fe = (ForEachNode) node;
            List<XPathExpression> exprs = fe.getExpressions();
            if (exprs != null && !exprs.isEmpty()) {
                XPathExpression selectExpr = exprs.get(0);
                if (isDirectCrawlingSelect(selectExpr)) {
                    XSLTNode body = fe.getBody();
                    if (bodyHasConsumingExpression(body)) {
                        return true;
                    }
                }
            }
        }
        // Check ExpressionHolder for FLWOR with descendant axis in bindings
        if (node instanceof ExpressionHolder) {
            List<XPathExpression> exprs =
                ((ExpressionHolder) node).getExpressions();
            if (exprs != null) {
                for (int i = 0; i < exprs.size(); i++) {
                    XPathExpression xpe = exprs.get(i);
                    if (xpe != null && containsCrawlingFlwor(
                            xpe.getCompiledExpr())) {
                        return true;
                    }
                }
            }
        }
        // Recurse into children
        if (node instanceof SequenceNode) {
            List<XSLTNode> children =
                ((SequenceNode) node).getChildren();
            if (children != null) {
                for (int i = 0; i < children.size(); i++) {
                    if (containsCrawlingInstruction(children.get(i),
                                                    depth + 1)) {
                        return true;
                    }
                }
            }
        }
        if (node instanceof CopyNode) {
            XSLTNode content =
                ((CopyNode) node).getContent();
            if (containsCrawlingInstruction(content, depth + 1)) {
                return true;
            }
        }
        if (node instanceof IfNode) {
            XSLTNode content =
                ((IfNode) node).getContent();
            if (containsCrawlingInstruction(content, depth + 1)) {
                return true;
            }
        }
        if (node instanceof ChooseNode) {
            ChooseNode choose = (ChooseNode) node;
            for (WhenNode when : choose.getWhens()) {
                if (containsCrawlingInstruction(when.getContent(), depth + 1)) {
                    return true;
                }
            }
            if (containsCrawlingInstruction(choose.getOtherwise(), depth + 1)) {
                return true;
            }
        }
        if (node instanceof ElementNode) {
            XSLTNode content =
                ((ElementNode) node).getContent();
            if (containsCrawlingInstruction(content, depth + 1)) {
                return true;
            }
        }
        if (node instanceof VariableNode) {
            XSLTNode content =
                ((VariableNode) node).getContent();
            if (containsCrawlingInstruction(content, depth + 1)) {
                return true;
            }
        }
        if (node instanceof LiteralResultElement) {
            XSLTNode content =
                ((LiteralResultElement) node).getContent();
            if (containsCrawlingInstruction(content, depth + 1)) {
                return true;
            }
        }
        if (node instanceof ForEachNode) {
            XSLTNode body =
                ((ForEachNode) node).getBody();
            if (containsCrawlingInstruction(body, depth + 1)) {
                return true;
            }
        }
        if (node instanceof AttributeNode) {
            XSLTNode content =
                ((AttributeNode) node).getContent();
            if (containsCrawlingInstruction(content, depth + 1)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks whether a select expression directly uses descendant axis,
     * not wrapped inside outermost()/innermost() or aggregate functions.
     */
    private static boolean isDirectCrawlingSelect(XPathExpression xpathExpr) {
        if (xpathExpr == null) {
            return false;
        }
        return isDirectCrawling(xpathExpr.getCompiledExpr());
    }

    private static boolean isDirectCrawling(Expr expr) {
        if (expr == null) {
            return false;
        }
        if (expr instanceof LocationPath) {
            LocationPath lp = (LocationPath) expr;
            List<Step> steps = lp.getSteps();
            for (int i = 0; i < steps.size(); i++) {
                Step.Axis axis = steps.get(i).getAxis();
                if (axis == Step.Axis.DESCENDANT
                        || axis == Step.Axis.DESCENDANT_OR_SELF) {
                    return true;
                }
            }
            return false;
        }
        if (expr instanceof PathExpr) {
            PathExpr pe = (PathExpr) expr;
            return isDirectCrawling(pe.getFilter())
                || isDirectCrawling(pe.getPath());
        }
        if (expr instanceof FilterExpr) {
            return isDirectCrawling(((FilterExpr) expr).getPrimary());
        }
        if (expr instanceof ContextItemExpr) {
            return false;
        }
        // Do NOT recurse into FunctionCall arguments —
        // outermost(.//x), count(.//x), etc. are safe
        return false;
    }

    /**
     * Checks whether a for-each body has consuming expressions
     * (child axis navigation), which combined with a crawling select
     * creates a non-streamable pattern.
     */
    private static boolean bodyHasConsumingExpression(XSLTNode body) {
        return bodyHasConsumingExpr(body, 0);
    }

    private static boolean bodyHasConsumingExpr(XSLTNode node, int depth) {
        if (node == null || depth > 50) {
            return false;
        }
        if (node instanceof ExpressionHolder) {
            List<XPathExpression> exprs =
                ((ExpressionHolder) node).getExpressions();
            if (exprs != null) {
                for (int i = 0; i < exprs.size(); i++) {
                    XPathExpression xpe = exprs.get(i);
                    if (xpe != null) {
                        StreamabilityAnalyzer.ExpressionStreamability es =
                            StreamingClassifier.classify(xpe);
                        if (es.ordinal() >
                                StreamabilityAnalyzer.ExpressionStreamability.MOTIONLESS.ordinal()) {
                            return true;
                        }
                    }
                }
            }
        }
        if (node instanceof SequenceNode) {
            List<XSLTNode> children =
                ((SequenceNode) node).getChildren();
            if (children != null) {
                for (int i = 0; i < children.size(); i++) {
                    if (bodyHasConsumingExpr(children.get(i), depth + 1)) {
                        return true;
                    }
                }
            }
        }
        if (node instanceof LiteralResultElement) {
            XSLTNode content = ((LiteralResultElement) node).getContent();
            if (bodyHasConsumingExpr(content, depth + 1)) {
                return true;
            }
        }
        if (node instanceof ElementNode) {
            XSLTNode content =
                ((ElementNode) node).getContent();
            if (bodyHasConsumingExpr(content, depth + 1)) {
                return true;
            }
        }
        if (node instanceof CopyNode) {
            XSLTNode content =
                ((CopyNode) node).getContent();
            if (bodyHasConsumingExpr(content, depth + 1)) {
                return true;
            }
        }
        if (node instanceof IfNode) {
            XSLTNode content =
                ((IfNode) node).getContent();
            if (bodyHasConsumingExpr(content, depth + 1)) {
                return true;
            }
        }
        if (node instanceof ChooseNode) {
            ChooseNode choose = (ChooseNode) node;
            for (WhenNode when : choose.getWhens()) {
                if (bodyHasConsumingExpr(when.getContent(), depth + 1)) {
                    return true;
                }
            }
            if (bodyHasConsumingExpr(choose.getOtherwise(), depth + 1)) {
                return true;
            }
        }
        if (node instanceof AttributeNode) {
            XSLTNode content =
                ((AttributeNode) node).getContent();
            if (bodyHasConsumingExpr(content, depth + 1)) {
                return true;
            }
        }
        if (node instanceof ForEachNode) {
            XSLTNode body =
                ((ForEachNode) node).getBody();
            if (bodyHasConsumingExpr(body, depth + 1)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks whether an XPath expression tree contains a FLWOR for-expression
     * with descendant axis in its binding. Such expressions create crawling
     * patterns: for $x in .//y return $x/z
     */
    private static boolean containsCrawlingFlwor(Expr expr) {
        if (expr == null) {
            return false;
        }
        if (expr instanceof ForExpr) {
            ForExpr fe = (ForExpr) expr;
            List<ForExpr.Binding> bindings = fe.getBindings();
            for (int i = 0; i < bindings.size(); i++) {
                Expr seq = bindings.get(i).getSequence();
                if (StreamingClassifier.containsDescendantAxis(seq)) {
                    return true;
                }
            }
        }
        // Recurse into sub-expressions
        if (expr instanceof BinaryExpr) {
            BinaryExpr be = (BinaryExpr) expr;
            return containsCrawlingFlwor(be.getLeft())
                || containsCrawlingFlwor(be.getRight());
        }
        if (expr instanceof FunctionCall) {
            FunctionCall fc = (FunctionCall) expr;
            List<Expr> args = fc.getArguments();
            for (int i = 0; i < args.size(); i++) {
                if (containsCrawlingFlwor(args.get(i))) {
                    return true;
                }
            }
        }
        if (expr instanceof FilterExpr) {
            FilterExpr fe = (FilterExpr) expr;
            if (containsCrawlingFlwor(fe.getPrimary())) {
                return true;
            }
            List<Expr> preds = fe.getPredicates();
            for (int i = 0; i < preds.size(); i++) {
                if (containsCrawlingFlwor(preds.get(i))) {
                    return true;
                }
            }
        }
        if (expr instanceof PathExpr) {
            PathExpr pe = (PathExpr) expr;
            return containsCrawlingFlwor(pe.getFilter())
                || containsCrawlingFlwor(pe.getPath());
        }
        return false;
    }

    /**
     * Checks whether an XSLT node tree contains any instruction compiled
     * in backwards-compatible mode (version="1.0"). Per XSLT 3.0 section
     * 3.9.1, such instructions are roaming and free-ranging.
     */
    private static boolean containsBackwardsCompatInstruction(XSLTNode node,
                                                               int depth) {
        if (node == null || depth > 50) {
            return false;
        }
        if (node instanceof ApplyTemplatesNode) {
            if (((ApplyTemplatesNode) node).isBackwardsCompatible()) {
                return true;
            }
        }
        if (node instanceof SequenceNode) {
            SequenceNode seq = (SequenceNode) node;
            List<XSLTNode> children = seq.getChildren();
            if (children != null) {
                for (int i = 0; i < children.size(); i++) {
                    if (containsBackwardsCompatInstruction(children.get(i),
                                                           depth + 1)) {
                        return true;
                    }
                }
            }
        }
        if (node instanceof CopyNode) {
            XSLTNode content =
                ((CopyNode) node).getContent();
            if (containsBackwardsCompatInstruction(content, depth + 1)) {
                return true;
            }
        }
        if (node instanceof ForEachNode) {
            XSLTNode body =
                ((ForEachNode) node).getBody();
            if (containsBackwardsCompatInstruction(body, depth + 1)) {
                return true;
            }
        }
        if (node instanceof IfNode) {
            XSLTNode content =
                ((IfNode) node).getContent();
            if (containsBackwardsCompatInstruction(content, depth + 1)) {
                return true;
            }
        }
        if (node instanceof ChooseNode) {
            ChooseNode choose = (ChooseNode) node;
            for (WhenNode when : choose.getWhens()) {
                XSLTNode wContent = when.getContent();
                if (containsBackwardsCompatInstruction(wContent, depth + 1)) {
                    return true;
                }
            }
            XSLTNode ow = choose.getOtherwise();
            if (containsBackwardsCompatInstruction(ow, depth + 1)) {
                return true;
            }
        }
        if (node instanceof ElementNode) {
            XSLTNode content =
                ((ElementNode) node).getContent();
            if (containsBackwardsCompatInstruction(content, depth + 1)) {
                return true;
            }
        }
        return false;
    }

    /**
     * XTSE3430: Validates xsl:fork streamability constraints within a
     * streaming context body. Walks the AST looking for ForkNode instances
     * and checks each branch for streaming violations.
     *
     * @param node the AST node to validate
     * @param depth recursion depth guard
     * @throws SAXException if a streamability violation is found
     */
    private void validateForkStreamability(XSLTNode node, int depth)
            throws SAXException {
        if (node == null || depth > 50) {
            return;
        }

        if (node instanceof ForkNode) {
            ForkNode fork = (ForkNode) node;
            validateForkBranches(fork);
        }

        // Recurse into child nodes
        if (node instanceof SequenceNode) {
            List<XSLTNode> children = ((SequenceNode) node).getChildren();
            if (children != null) {
                for (int i = 0; i < children.size(); i++) {
                    validateForkStreamability(children.get(i), depth + 1);
                }
            }
        }
        if (node instanceof ForEachNode) {
            validateForkStreamability(((ForEachNode) node).getBody(),
                                     depth + 1);
        }
        if (node instanceof IfNode) {
            validateForkStreamability(((IfNode) node).getContent(),
                                     depth + 1);
        }
        if (node instanceof ChooseNode) {
            ChooseNode choose = (ChooseNode) node;
            for (WhenNode when : choose.getWhens()) {
                validateForkStreamability(when, depth + 1);
            }
            validateForkStreamability(choose.getOtherwise(), depth + 1);
        }
        if (node instanceof WhenNode) {
            validateForkStreamability(((WhenNode) node).getContent(),
                                     depth + 1);
        }
        if (node instanceof VariableNode) {
            validateForkStreamability(((VariableNode) node).getContent(),
                                     depth + 1);
        }
        if (node instanceof CopyNode) {
            validateForkStreamability(((CopyNode) node).getContent(),
                                     depth + 1);
        }
        if (node instanceof LiteralResultElement) {
            validateForkStreamability(
                ((LiteralResultElement) node).getContent(), depth + 1);
        }
        if (node instanceof ElementNode) {
            validateForkStreamability(((ElementNode) node).getContent(),
                                     depth + 1);
        }
        if (node instanceof TryNode) {
            TryNode tryNode = (TryNode) node;
            validateForkStreamability(tryNode.getTryContent(), depth + 1);
            for (CatchNode catchNode : tryNode.getCatchBlocks()) {
                validateForkStreamability(catchNode, depth + 1);
            }
        }
        if (node instanceof CatchNode) {
            validateForkStreamability(((CatchNode) node).getContent(),
                                     depth + 1);
        }
    }

    /**
     * Validates the branches of a ForkNode for streaming compliance.
     * Checks for:
     * <ul>
     *   <li>Fork prongs returning streamed nodes (si-fork-901)</li>
     *   <li>Fork prongs with multiple consuming operands (si-fork-902)</li>
     *   <li>For-each-group in fork with streaming violations</li>
     * </ul>
     */
    private void validateForkBranches(ForkNode fork) throws SAXException {
        List<ForkNode.ForkBranch> branches = fork.getBranches();

        // Count branches that output streamed nodes directly.
        // Multiple consuming branches are fine (that's the purpose of fork),
        // but multiple branches that RETURN raw streamed nodes are not.
        int streamedNodeBranches = 0;
        for (int i = 0; i < branches.size(); i++) {
            ForkNode.ForkBranch branch = branches.get(i);
            XSLTNode content = branch.getContent();
            if (content == null) {
                continue;
            }

            if (branchReturnsStreamedNodes(content)) {
                streamedNodeBranches++;
            }

            // Check for multiple consuming operands in a single
            // expression (si-fork-902)
            validateBranchOperands(content);

            // Check for ForEachGroupNode within fork branches
            validateForkForEachGroup(content, 0);
        }

        if (streamedNodeBranches > 1) {
            throw new SAXException("XTSE3430: xsl:fork is not streamable:" +
                " multiple prongs return streamed nodes");
        }
    }

    /**
     * Returns true if a fork branch outputs streamed nodes directly.
     * This happens when a SequenceOutputNode has a select expression
     * that evaluates to nodes (LocationPath/PathExpr) and is consuming.
     * Expressions wrapped in grounding functions (string(), number(),
     * sum(), etc.) return atomic values and are fine.
     */
    private boolean branchReturnsStreamedNodes(XSLTNode node) {
        if (node instanceof SequenceOutputNode) {
            return isStreamedNodeReturning((SequenceOutputNode) node);
        }
        return false;
    }

    /**
     * Returns true if a SequenceOutputNode returns consuming streamed
     * nodes. The expression returns nodes (not grounded values) when its
     * top-level is a LocationPath, PathExpr, or ContextItemExpr.
     */
    private boolean isStreamedNodeReturning(SequenceOutputNode son) {
        List<XPathExpression> exprs = son.getExpressions();
        for (int i = 0; i < exprs.size(); i++) {
            XPathExpression xpe = exprs.get(i);
            Expr compiled = xpe.getCompiledExpr();
            if (compiled instanceof LocationPath
                    || compiled instanceof PathExpr
                    || compiled instanceof ContextItemExpr) {
                StreamabilityAnalyzer.ExpressionStreamability es =
                    StreamingClassifier.classify(xpe);
                if (es == StreamabilityAnalyzer.ExpressionStreamability
                        .CONSUMING
                        || es == StreamabilityAnalyzer.ExpressionStreamability
                        .GROUNDED) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Checks for multiple consuming operands within a single expression
     * in a fork branch (si-fork-902: {@code TITLE||PRICE}).
     */
    private void validateBranchOperands(XSLTNode node) throws SAXException {
        List<XSLTNode> toCheck = new ArrayList<XSLTNode>();
        if (node instanceof SequenceNode) {
            List<XSLTNode> children = ((SequenceNode) node).getChildren();
            if (children != null) {
                toCheck.addAll(children);
            }
        } else {
            toCheck.add(node);
        }

        for (int i = 0; i < toCheck.size(); i++) {
            XSLTNode child = toCheck.get(i);
            if (child instanceof SequenceOutputNode) {
                List<XPathExpression> exprs =
                    ((ExpressionHolder) child).getExpressions();
                for (int j = 0; j < exprs.size(); j++) {
                    int ops = StreamingClassifier.countConsumingOperands(
                        exprs.get(j));
                    if (ops > 1) {
                        throw new SAXException("XTSE3430: xsl:fork " +
                            "prong is not streamable: expression has " +
                            "multiple consuming operands");
                    }
                }
            }
        }
    }

    /**
     * Validates xsl:for-each-group within a fork branch for streaming
     * constraints (si-fork-951 through si-fork-957).
     */
    private void validateForkForEachGroup(XSLTNode node, int depth)
            throws SAXException {
        if (node == null || depth > 50) {
            return;
        }

        if (node instanceof ForEachGroupNode) {
            ForEachGroupNode feg = (ForEachGroupNode) node;

            // (si-fork-955, 956, 957): Crawling population (descendant axis)
            // This also catches non-motionless group-by when the population
            // is crawling, since descendant-axis select implies the group-by
            // operates on streamed nodes.
            XPathExpression selectExpr = feg.getSelect();
            if (StreamingClassifier.containsDescendantAxis(selectExpr)) {
                throw new SAXException("XTSE3430: xsl:for-each-group in " +
                    "xsl:fork is not streamable: population uses " +
                    "descendant axis (crawling)");
            }

            // (si-fork-953): Sorted groups
            List<SortSpec> sorts = feg.getSorts();
            if (sorts != null && !sorts.isEmpty()) {
                throw new SAXException("XTSE3430: xsl:for-each-group in " +
                    "xsl:fork is not streamable: sorted groups require " +
                    "buffering");
            }

            // Check the body for current-group() usage violations
            validateForkGroupBody(feg.getBody(), 0);
        }

        // Recurse into child nodes to find nested ForEachGroupNode
        if (node instanceof SequenceNode) {
            List<XSLTNode> children = ((SequenceNode) node).getChildren();
            if (children != null) {
                for (int i = 0; i < children.size(); i++) {
                    validateForkForEachGroup(children.get(i), depth + 1);
                }
            }
        }
    }

    /**
     * Validates the body of a for-each-group within a fork for
     * current-group() usage violations.
     *
     * <p>In a streaming fork, current-group() may only be consumed once.
     * Multiple uses, mixing with context-item consumption, or use in
     * higher-order operands all violate streamability.
     */
    private void validateForkGroupBody(XSLTNode node, int depth)
            throws SAXException {
        if (node == null || depth > 50) {
            return;
        }

        int totalCurrentGroupCalls =
            countCurrentGroupCalls(node, 0);

        // (si-fork-951, 952, 954): Multiple current-group() usage
        if (totalCurrentGroupCalls > 1) {
            throw new SAXException("XTSE3430: xsl:for-each-group in " +
                "xsl:fork is not streamable: current-group() is used " +
                "more than once");
        }

        // (si-fork-954): current-group() mixed with context-item
        // down-selection. If there is one current-group() call AND a
        // consuming expression that does not involve current-group(),
        // both compete for the streamed input.
        if (totalCurrentGroupCalls >= 1) {
            boolean hasOtherConsuming =
                hasNonGroupConsuming(node, 0);
            if (hasOtherConsuming) {
                throw new SAXException("XTSE3430: xsl:for-each-group " +
                    "in xsl:fork is not streamable: current-group() " +
                    "mixed with context item down-selection");
            }
        }

        // (si-fork-952): current-group() with multiple consuming
        // down-selections, e.g. current-group()/(AUTHOR||TITLE)
        if (hasMultipleConsumingFromGroup(node, 0)) {
            throw new SAXException("XTSE3430: xsl:for-each-group in " +
                "xsl:fork is not streamable: multiple down-selections " +
                "from current-group()");
        }

        // (si-fork-956): current-group() in higher-order operand
        // (predicate or nested for-each body)
        if (hasCurrentGroupInHigherOrder(node, 0)) {
            throw new SAXException("XTSE3430: xsl:for-each-group in " +
                "xsl:fork is not streamable: current-group() used in " +
                "higher-order operand");
        }
    }

    /**
     * XTSE3430: Validates xsl:for-each-group nodes within a streaming
     * context for streamability violations.
     *
     * <p>Only checks patterns that are clearly non-streamable:
     * <ul>
     *   <li>Two current-group() calls in the same expression</li>
     *   <li>Climbing from current-group() (parent/ancestor axis)</li>
     *   <li>current-group() in nested xsl:source-document or
     *       xsl:copy</li>
     *   <li>Non-motionless pattern predicate when the population
     *       is not grounded</li>
     *   <li>Non-motionless key when the population is not grounded
     *       and the key navigates to child text</li>
     * </ul>
     */
    private void validateStreamingForEachGroup(XSLTNode node, int depth)
            throws SAXException {
        if (node == null || depth > 50) {
            return;
        }

        if (node instanceof ForEachGroupNode) {
            ForEachGroupNode feg = (ForEachGroupNode) node;
            boolean groundedPopulation = isGroundedPopulation(feg);

            // Only validate patterns/keys when population is NOT
            // grounded (copy-of makes items fully available)
            if (!groundedPopulation) {
                // Non-motionless pattern for group-starting-with
                // when population is child elements (not text nodes)
                Pattern startPat = feg.getGroupStartingPattern();
                if (startPat != null) {
                    String patStr = startPat.toString();
                    boolean matchesText = patStr.startsWith("text()");
                    if (!matchesText) {
                        if (hasNonMotionlessPredicate(startPat)) {
                            throw new SAXException("XTSE3430: Streaming " +
                                "xsl:for-each-group group-starting-with " +
                                "pattern '" + patStr +
                                "' is not motionless");
                        }
                        // position()/last() in pattern predicates are
                        // non-streamable (require sibling counting)
                        if (patternUsesPosition(startPat)) {
                            throw new SAXException("XTSE3430: Streaming " +
                                "xsl:for-each-group group-starting-with " +
                                "pattern '" + patStr +
                                "' uses position()/last() which " +
                                "requires sibling counting");
                        }
                    }
                }

                // Non-motionless key for group-adjacent (only flag
                // path expressions with slash, e.g. "PRICE/text()")
                XPathExpression adjExpr = feg.getGroupAdjacentExpr();
                if (adjExpr != null) {
                    String keyStr = adjExpr.getExpressionString();
                    if (keyStr != null && keyStr.contains("/")) {
                        if (!keyStr.trim().startsWith("@")) {
                            throw new SAXException("XTSE3430: Streaming " +
                                "xsl:for-each-group group-adjacent " +
                                "key '" + keyStr + "' is not motionless");
                        }
                    }
                }
            }

            // Body checks
            XSLTNode body = feg.getBody();
            if (body != null) {
                // Two current-group() in same expression
                // (only when population is NOT grounded)
                if (!groundedPopulation) {
                    if (hasMultipleCurrentGroupInSameExpr(body, 0)) {
                        throw new SAXException("XTSE3430: Streaming " +
                            "xsl:for-each-group body references " +
                            "current-group() multiple times in the " +
                            "same expression");
                    }
                }

                // current-group() in nested source-document or
                // context-changing xsl:copy (only when population is
                // NOT grounded, since grounded items are safe anywhere)
                if (!groundedPopulation) {
                    if (containsCurrentGroupRef(body, 0)) {
                        if (hasCurrentGroupInNestedContext(body, 0)) {
                            throw new SAXException("XTSE3430: Streaming " +
                                "xsl:for-each-group uses " +
                                "current-group() inside a nested " +
                                "streaming or context-changing " +
                                "instruction");
                        }
                    }
                }
            }
        }

        // Recurse into child nodes
        if (node instanceof SequenceNode) {
            List<XSLTNode> children = ((SequenceNode) node).getChildren();
            if (children != null) {
                for (int i = 0; i < children.size(); i++) {
                    validateStreamingForEachGroup(children.get(i),
                        depth + 1);
                }
            }
        }
        if (node instanceof ForEachNode) {
            validateStreamingForEachGroup(
                ((ForEachNode) node).getBody(), depth + 1);
        }
        if (node instanceof IfNode) {
            validateStreamingForEachGroup(
                ((IfNode) node).getContent(), depth + 1);
        }
        if (node instanceof ChooseNode) {
            ChooseNode choose = (ChooseNode) node;
            for (WhenNode when : choose.getWhens()) {
                validateStreamingForEachGroup(
                    when.getContent(), depth + 1);
            }
            validateStreamingForEachGroup(
                choose.getOtherwise(), depth + 1);
        }
        if (node instanceof LiteralResultElement) {
            validateStreamingForEachGroup(
                ((LiteralResultElement) node).getContent(), depth + 1);
        }
        if (node instanceof CopyNode) {
            validateStreamingForEachGroup(
                ((CopyNode) node).getContent(), depth + 1);
        }
        if (node instanceof ElementNode) {
            validateStreamingForEachGroup(
                ((ElementNode) node).getContent(), depth + 1);
        }
        if (node instanceof VariableNode) {
            validateStreamingForEachGroup(
                ((VariableNode) node).getContent(), depth + 1);
        }
        if (node instanceof ResultDocumentNode) {
            validateStreamingForEachGroup(
                ((ResultDocumentNode) node).getContent(), depth + 1);
        }
    }

    /**
     * Checks if a pattern uses position() or last() in a predicate.
     * In streaming group-starting-with/group-ending-with, these
     * require sibling counting which is not available.
     */
    private static boolean patternUsesPosition(Pattern pat) {
        if (pat == null) {
            return false;
        }
        String patStr = pat.toString();
        return patStr.contains("position()") ||
            patStr.contains("last()");
    }

    /**
     * Checks if the population of a for-each-group is grounded
     * (uses copy-of() or similar).
     */
    private boolean isGroundedPopulation(ForEachGroupNode feg) {
        XPathExpression sel = feg.getSelect();
        if (sel == null) {
            return false;
        }
        String s = sel.getExpressionString();
        return s != null && s.contains("copy-of(");
    }

    /**
     * Checks if any single expression in the body has 2+ current-group()
     * calls where at least one is not grounded (not inside copy-of).
     */
    private boolean hasMultipleCurrentGroupInSameExpr(XSLTNode node,
            int depth) {
        if (node == null || depth > 50) {
            return false;
        }
        if (node instanceof ExpressionHolder) {
            List<XPathExpression> exprs =
                ((ExpressionHolder) node).getExpressions();
            for (XPathExpression expr : exprs) {
                if (expr != null) {
                    String s = expr.getExpressionString();
                    if (s != null) {
                        int total = countOccurrences(s,
                            "current-group()");
                        if (total > 1) {
                            // If all occurrences are grounded (inside
                            // copy-of()), double consumption is OK
                            if (!allCurrentGroupGrounded(s)) {
                                return true;
                            }
                        }
                    }
                }
            }
        }
        if (node instanceof SequenceNode) {
            List<XSLTNode> children = ((SequenceNode) node).getChildren();
            if (children != null) {
                for (int i = 0; i < children.size(); i++) {
                    if (hasMultipleCurrentGroupInSameExpr(
                            children.get(i), depth + 1)) {
                        return true;
                    }
                }
            }
        }
        if (node instanceof ForEachNode) {
            return hasMultipleCurrentGroupInSameExpr(
                ((ForEachNode) node).getBody(), depth + 1);
        }
        if (node instanceof IfNode) {
            return hasMultipleCurrentGroupInSameExpr(
                ((IfNode) node).getContent(), depth + 1);
        }
        if (node instanceof ChooseNode) {
            ChooseNode choose = (ChooseNode) node;
            for (WhenNode when : choose.getWhens()) {
                if (hasMultipleCurrentGroupInSameExpr(
                        when.getContent(), depth + 1)) {
                    return true;
                }
            }
            return hasMultipleCurrentGroupInSameExpr(
                choose.getOtherwise(), depth + 1);
        }
        if (node instanceof LiteralResultElement) {
            return hasMultipleCurrentGroupInSameExpr(
                ((LiteralResultElement) node).getContent(), depth + 1);
        }
        if (node instanceof ElementNode) {
            return hasMultipleCurrentGroupInSameExpr(
                ((ElementNode) node).getContent(), depth + 1);
        }
        if (node instanceof CopyNode) {
            return hasMultipleCurrentGroupInSameExpr(
                ((CopyNode) node).getContent(), depth + 1);
        }
        if (node instanceof VariableNode) {
            return hasMultipleCurrentGroupInSameExpr(
                ((VariableNode) node).getContent(), depth + 1);
        }
        return false;
    }

    /**
     * Checks if all current-group() references in an expression are
     * inside a copy-of() call (making them grounded). Uses parenthesis
     * depth tracking.
     */
    private static boolean allCurrentGroupGrounded(String expr) {
        String target = "current-group()";
        int idx = expr.indexOf(target);
        while (idx >= 0) {
            // Check if this occurrence is inside a copy-of() call
            // by searching backwards for "copy-of(" and verifying
            // the parenthesis depth
            if (!isInsideCopyOf(expr, idx)) {
                return false;
            }
            idx = expr.indexOf(target, idx + target.length());
        }
        return true;
    }

    /**
     * Checks if a position in the expression is inside a copy-of() call
     * by looking at the nesting of copy-of( calls before this position.
     */
    private static boolean isInsideCopyOf(String expr, int pos) {
        // Walk backwards from pos looking for "copy-of(" at any
        // nesting level. Track parenthesis depth to know if we're
        // still inside the copy-of call.
        int parenDepth = 0;
        for (int i = pos - 1; i >= 0; i--) {
            char c = expr.charAt(i);
            if (c == ')') {
                parenDepth++;
            } else if (c == '(') {
                if (parenDepth > 0) {
                    parenDepth--;
                } else {
                    // We're at an open paren that matches
                    // Check if preceded by "copy-of"
                    String before = expr.substring(
                        Math.max(0, i - 7), i).trim();
                    if (before.endsWith("copy-of")) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Counts non-overlapping occurrences of a substring.
     */
    private static int countOccurrences(String str, String sub) {
        int count = 0;
        int idx = str.indexOf(sub);
        while (idx >= 0) {
            count++;
            idx = str.indexOf(sub, idx + sub.length());
        }
        return count;
    }

    /**
     * Checks if any expression in the subtree references current-group().
     * Unlike countCurrentGroupCalls, this method recurses into ALL
     * node types including CopyNode, ResultDocumentNode, etc.
     */
    private boolean containsCurrentGroupRef(XSLTNode node, int depth) {
        if (node == null || depth > 50) {
            return false;
        }
        if (node instanceof ExpressionHolder) {
            List<XPathExpression> exprs =
                ((ExpressionHolder) node).getExpressions();
            if (exprs != null) {
                for (int i = 0; i < exprs.size(); i++) {
                    int calls = StreamingClassifier.countFunctionCalls(
                        exprs.get(i), "current-group");
                    if (calls > 0) {
                        return true;
                    }
                }
            }
        }
        if (node instanceof LiteralResultElement) {
            LiteralResultElement lre = (LiteralResultElement) node;
            Map<String, AttributeValueTemplate> attrs = lre.getAttributes();
            if (attrs != null) {
                List<XPathExpression> avtExprs =
                    new ArrayList<XPathExpression>();
                for (AttributeValueTemplate avt : attrs.values()) {
                    avt.collectExpressions(avtExprs);
                }
                for (int i = 0; i < avtExprs.size(); i++) {
                    int calls = StreamingClassifier.countFunctionCalls(
                        avtExprs.get(i), "current-group");
                    if (calls > 0) {
                        return true;
                    }
                }
            }
            if (containsCurrentGroupRef(lre.getContent(), depth + 1)) {
                return true;
            }
        }
        if (node instanceof SequenceNode) {
            List<XSLTNode> children =
                ((SequenceNode) node).getChildren();
            if (children != null) {
                for (int i = 0; i < children.size(); i++) {
                    if (containsCurrentGroupRef(children.get(i),
                            depth + 1)) {
                        return true;
                    }
                }
            }
        }
        if (node instanceof ForEachNode) {
            return containsCurrentGroupRef(
                ((ForEachNode) node).getBody(), depth + 1);
        }
        if (node instanceof IfNode) {
            return containsCurrentGroupRef(
                ((IfNode) node).getContent(), depth + 1);
        }
        if (node instanceof ChooseNode) {
            ChooseNode choose = (ChooseNode) node;
            for (WhenNode when : choose.getWhens()) {
                if (containsCurrentGroupRef(
                        when.getContent(), depth + 1)) {
                    return true;
                }
            }
            return containsCurrentGroupRef(
                choose.getOtherwise(), depth + 1);
        }
        if (node instanceof CopyNode) {
            return containsCurrentGroupRef(
                ((CopyNode) node).getContent(), depth + 1);
        }
        if (node instanceof ElementNode) {
            return containsCurrentGroupRef(
                ((ElementNode) node).getContent(), depth + 1);
        }
        if (node instanceof VariableNode) {
            return containsCurrentGroupRef(
                ((VariableNode) node).getContent(), depth + 1);
        }
        if (node instanceof ResultDocumentNode) {
            return containsCurrentGroupRef(
                ((ResultDocumentNode) node).getContent(), depth + 1);
        }
        if (node instanceof SourceDocumentNode) {
            return containsCurrentGroupRef(
                ((SourceDocumentNode) node).getContent(), depth + 1);
        }
        return false;
    }

    /**
     * Checks if a for-each-group body has current-group() inside a nested
     * xsl:source-document or xsl:copy instruction (higher-order context).
     */
    private boolean hasCurrentGroupInNestedContext(XSLTNode node,
            int depth) {
        if (node == null || depth > 50) {
            return false;
        }

        // current-group() inside nested xsl:source-document
        if (node instanceof SourceDocumentNode) {
            return containsCurrentGroupRef(
                ((SourceDocumentNode) node).getContent(), 0);
        }

        // current-group() inside xsl:copy with select (context change)
        if (node instanceof CopyNode) {
            CopyNode copy = (CopyNode) node;
            // Only flag when xsl:copy has a select attribute, which
            // changes the context item (higher-order operand)
            List<XPathExpression> copyExprs = copy.getExpressions();
            boolean hasSelect = !copyExprs.isEmpty();
            if (hasSelect) {
                return containsCurrentGroupRef(copy.getContent(), 0);
            }
            // Without select, recurse normally
            return hasCurrentGroupInNestedContext(
                copy.getContent(), depth + 1);
        }

        // Recurse
        if (node instanceof SequenceNode) {
            List<XSLTNode> children = ((SequenceNode) node).getChildren();
            if (children != null) {
                for (int i = 0; i < children.size(); i++) {
                    if (hasCurrentGroupInNestedContext(children.get(i),
                            depth + 1)) {
                        return true;
                    }
                }
            }
        }
        if (node instanceof ForEachNode) {
            return hasCurrentGroupInNestedContext(
                ((ForEachNode) node).getBody(), depth + 1);
        }
        if (node instanceof IfNode) {
            return hasCurrentGroupInNestedContext(
                ((IfNode) node).getContent(), depth + 1);
        }
        if (node instanceof ChooseNode) {
            ChooseNode choose = (ChooseNode) node;
            for (WhenNode when : choose.getWhens()) {
                if (hasCurrentGroupInNestedContext(
                        when.getContent(), depth + 1)) {
                    return true;
                }
            }
            return hasCurrentGroupInNestedContext(
                choose.getOtherwise(), depth + 1);
        }
        if (node instanceof LiteralResultElement) {
            return hasCurrentGroupInNestedContext(
                ((LiteralResultElement) node).getContent(), depth + 1);
        }
        if (node instanceof ElementNode) {
            return hasCurrentGroupInNestedContext(
                ((ElementNode) node).getContent(), depth + 1);
        }
        if (node instanceof VariableNode) {
            return hasCurrentGroupInNestedContext(
                ((VariableNode) node).getContent(), depth + 1);
        }
        if (node instanceof ResultDocumentNode) {
            XSLTNode rdContent =
                ((ResultDocumentNode) node).getContent();
            return hasCurrentGroupInNestedContext(rdContent, depth + 1);
        }
        // Don't recurse into nested for-each-group (it has its own scope)
        return false;
    }

    /**
     * XTSE3430: Validates that calls to user functions with declared
     * streamability within a streaming body have compliant arguments.
     *
     * <p>For shallow-descent functions called within streaming contexts:
     * <ul>
     *   <li>The first argument must not use parent/ancestor axis
     *       (climbing)</li>
     *   <li>Second and subsequent arguments must not be consuming
     *       (e.g. child axis wildcard)</li>
     * </ul>
     */
    private void validateStreamableFunctionCallSites(XSLTNode node,
            int depth) throws SAXException {
        if (node == null || depth > 50) {
            return;
        }
        if (node instanceof ExpressionHolder) {
            List<XPathExpression> exprs =
                ((ExpressionHolder) node).getExpressions();
            if (exprs != null) {
                for (int i = 0; i < exprs.size(); i++) {
                    XPathExpression xpe = exprs.get(i);
                    if (xpe != null && xpe.getCompiledExpr() != null) {
                        checkExprForStreamableFunctionCalls(
                            xpe.getCompiledExpr());
                    }
                }
            }
        }
        if (node instanceof SequenceNode) {
            List<XSLTNode> children = ((SequenceNode) node).getChildren();
            if (children != null) {
                for (int i = 0; i < children.size(); i++) {
                    validateStreamableFunctionCallSites(
                        children.get(i), depth + 1);
                }
            }
        }
        if (node instanceof ForEachNode) {
            XSLTNode body =
                ((ForEachNode) node).getBody();
            validateStreamableFunctionCallSites(body, depth + 1);
        }
        if (node instanceof IfNode) {
            XSLTNode content =
                ((IfNode) node).getContent();
            validateStreamableFunctionCallSites(content, depth + 1);
        }
        if (node instanceof ChooseNode) {
            ChooseNode choose = (ChooseNode) node;
            for (WhenNode when : choose.getWhens()) {
                validateStreamableFunctionCallSites(when.getContent(), depth + 1);
            }
            validateStreamableFunctionCallSites(choose.getOtherwise(), depth + 1);
        }
        if (node instanceof CopyNode) {
            XSLTNode content =
                ((CopyNode) node).getContent();
            validateStreamableFunctionCallSites(content, depth + 1);
        }
        if (node instanceof ElementNode) {
            XSLTNode content =
                ((ElementNode) node).getContent();
            validateStreamableFunctionCallSites(content, depth + 1);
        }
        if (node instanceof LiteralResultElement) {
            XSLTNode content =
                ((LiteralResultElement) node).getContent();
            validateStreamableFunctionCallSites(content, depth + 1);
        }
        if (node instanceof VariableNode) {
            XSLTNode content =
                ((VariableNode) node).getContent();
            validateStreamableFunctionCallSites(content, depth + 1);
        }
    }

    /**
     * Walks an expression tree looking for calls to streamable user
     * functions, validating their arguments for streaming compliance.
     */
    private void checkExprForStreamableFunctionCalls(Expr expr)
            throws SAXException {
        if (expr == null) {
            return;
        }
        if (expr instanceof FunctionCall) {
            FunctionCall fc = (FunctionCall) expr;
            String nsUri = fc.getResolvedNamespaceURI();
            String localName = fc.getLocalName();
            if (nsUri != null && !nsUri.isEmpty()) {
                List<Expr> args = fc.getArguments();
                int arity = args.size();
                if (builder.hasUserFunction(nsUri, localName)) {
                    UserFunction uf = findUserFunction(nsUri, localName,
                        arity);
                    if (uf != null && uf.getStreamability() != null &&
                            !"unclassified".equals(uf.getStreamability())) {
                        validateStreamableFunctionArgs(fc, uf);
                    }
                }
            }
            // Also check arguments recursively
            List<Expr> args = fc.getArguments();
            for (int i = 0; i < args.size(); i++) {
                checkExprForStreamableFunctionCalls(args.get(i));
            }
            return;
        }
        // Recurse into sub-expressions
        if (expr instanceof BinaryExpr) {
            BinaryExpr be = (BinaryExpr) expr;
            checkExprForStreamableFunctionCalls(be.getLeft());
            checkExprForStreamableFunctionCalls(be.getRight());
        } else if (expr instanceof UnaryExpr) {
            checkExprForStreamableFunctionCalls(
                ((UnaryExpr) expr).getOperand());
        } else if (expr instanceof PathExpr) {
            PathExpr pe = (PathExpr) expr;
            checkExprForStreamableFunctionCalls(pe.getFilter());
            checkExprForStreamableFunctionCalls(pe.getPath());
        } else if (expr instanceof FilterExpr) {
            FilterExpr fe = (FilterExpr) expr;
            checkExprForStreamableFunctionCalls(fe.getPrimary());
            List<Expr> preds = fe.getPredicates();
            for (int i = 0; i < preds.size(); i++) {
                checkExprForStreamableFunctionCalls(preds.get(i));
            }
        } else if (expr instanceof IfExpr) {
            IfExpr ie = (IfExpr) expr;
            checkExprForStreamableFunctionCalls(ie.getCondition());
            checkExprForStreamableFunctionCalls(ie.getThenExpr());
            checkExprForStreamableFunctionCalls(ie.getElseExpr());
        } else if (expr instanceof SequenceExpr) {
            List<Expr> items = ((SequenceExpr) expr).getItems();
            for (int i = 0; i < items.size(); i++) {
                checkExprForStreamableFunctionCalls(items.get(i));
            }
        } else if (expr instanceof ForExpr) {
            ForExpr fe = (ForExpr) expr;
            List<ForExpr.Binding> bindings = fe.getBindings();
            for (int i = 0; i < bindings.size(); i++) {
                checkExprForStreamableFunctionCalls(
                    bindings.get(i).getSequence());
            }
            checkExprForStreamableFunctionCalls(fe.getReturnExpr());
        } else if (expr instanceof LetExpr) {
            LetExpr le = (LetExpr) expr;
            List<LetExpr.Binding> bindings = le.getBindings();
            for (int i = 0; i < bindings.size(); i++) {
                checkExprForStreamableFunctionCalls(
                    bindings.get(i).getValue());
            }
            checkExprForStreamableFunctionCalls(le.getReturnExpr());
        }
    }

    /**
     * Finds a UserFunction by namespace, local name, and arity.
     */
    private UserFunction findUserFunction(String nsUri, String localName,
            int arity) {
        String key = nsUri + "#" + localName + "#" + arity;
        Map<String, UserFunction> functions = builder.getUserFunctions();
        return functions.get(key);
    }

    /**
     * Validates that the arguments to a streamable function call comply
     * with streaming rules.
     */
    private void validateStreamableFunctionArgs(FunctionCall fc,
            UserFunction uf) throws SAXException {
        List<Expr> args = fc.getArguments();
        String funcName = fc.getPrefix() != null
            ? fc.getPrefix() + ":" + fc.getLocalName()
            : fc.getLocalName();

        // First argument must not use parent/ancestor axis (climbing)
        if (!args.isEmpty()) {
            Expr firstArg = args.get(0);
            if (containsClimbingAxis(firstArg)) {
                throw new SAXException("XTSE3430: Call to " +
                    "streamable function '" + funcName +
                    "' has a climbing first argument (parent/ancestor " +
                    "axis) in streaming context");
            }
            // For absorbing functions, first argument must not use
            // descendant axis (crawling)
            if (containsCrawlingAxis(firstArg)) {
                throw new SAXException("XTSE3430: Call to " +
                    "streamable function '" + funcName +
                    "' has a crawling argument (descendant axis) " +
                    "in streaming context");
            }
        }

        // Second and subsequent arguments must not be consuming
        // (child axis navigation that consumes streamed nodes)
        for (int i = 1; i < args.size(); i++) {
            Expr arg = args.get(i);
            if (isConsumingExpression(arg)) {
                throw new SAXException("XTSE3430: Call to " +
                    "streamable function '" + funcName +
                    "' has a consuming argument in position " + (i + 1) +
                    " in streaming context");
            }
        }
    }

    /**
     * Checks if an expression contains a parent or ancestor axis step
     * (climbing expression in streaming terminology).
     */
    private boolean containsClimbingAxis(Expr expr) {
        if (expr == null) {
            return false;
        }
        if (expr instanceof LocationPath) {
            LocationPath lp = (LocationPath) expr;
            List<Step> steps = lp.getSteps();
            for (int i = 0; i < steps.size(); i++) {
                Step.Axis axis = steps.get(i).getAxis();
                if (axis == Step.Axis.PARENT ||
                        axis == Step.Axis.ANCESTOR ||
                        axis == Step.Axis.ANCESTOR_OR_SELF) {
                    return true;
                }
            }
        }
        if (expr instanceof PathExpr) {
            PathExpr pe = (PathExpr) expr;
            boolean result = containsClimbingAxis(pe.getFilter());
            if (!result) {
                result = containsClimbingAxis(pe.getPath());
            }
            return result;
        }
        return false;
    }

    /**
     * Checks if an expression contains a descendant or descendant-or-self
     * axis step (crawling expression in streaming terminology).
     */
    private boolean containsCrawlingAxis(Expr expr) {
        if (expr == null) {
            return false;
        }
        if (expr instanceof LocationPath) {
            LocationPath lp = (LocationPath) expr;
            List<Step> steps = lp.getSteps();
            for (int i = 0; i < steps.size(); i++) {
                Step.Axis axis = steps.get(i).getAxis();
                if (axis == Step.Axis.DESCENDANT ||
                        axis == Step.Axis.DESCENDANT_OR_SELF) {
                    return true;
                }
            }
        }
        if (expr instanceof PathExpr) {
            PathExpr pe = (PathExpr) expr;
            boolean result = containsCrawlingAxis(pe.getFilter());
            if (!result) {
                result = containsCrawlingAxis(pe.getPath());
            }
            return result;
        }
        return false;
    }

    /**
     * Checks if an expression is a consuming expression (navigates into
     * child nodes) in a streaming context. Wildcard child selections
     * like {@code *} are consuming.
     */
    private boolean isConsumingExpression(Expr expr) {
        if (expr == null) {
            return false;
        }
        if (expr instanceof LocationPath) {
            LocationPath lp = (LocationPath) expr;
            List<Step> steps = lp.getSteps();
            for (int i = 0; i < steps.size(); i++) {
                Step.Axis axis = steps.get(i).getAxis();
                if (axis == Step.Axis.CHILD ||
                        axis == Step.Axis.DESCENDANT ||
                        axis == Step.Axis.DESCENDANT_OR_SELF) {
                    return true;
                }
            }
        }
        if (expr instanceof PathExpr) {
            PathExpr pe = (PathExpr) expr;
            boolean result = isConsumingExpression(pe.getFilter());
            if (!result) {
                result = isConsumingExpression(pe.getPath());
            }
            return result;
        }
        return false;
    }

    /**
     * Counts the total number of current-group() calls in all expressions
     * within a node tree.
     */
    private int countCurrentGroupCalls(XSLTNode node, int depth) {
        if (node == null || depth > 50) {
            return 0;
        }
        int count = 0;
        if (node instanceof ExpressionHolder) {
            List<XPathExpression> exprs =
                ((ExpressionHolder) node).getExpressions();
            if (exprs != null) {
                for (int i = 0; i < exprs.size(); i++) {
                    count += StreamingClassifier.countFunctionCalls(
                        exprs.get(i), "current-group");
                }
            }
        }
        if (node instanceof SequenceNode) {
            List<XSLTNode> children = ((SequenceNode) node).getChildren();
            if (children != null) {
                for (int i = 0; i < children.size(); i++) {
                    count += countCurrentGroupCalls(children.get(i),
                                                    depth + 1);
                }
            }
        }
        if (node instanceof ForEachNode) {
            count += countCurrentGroupCalls(((ForEachNode) node).getBody(),
                                            depth + 1);
        }
        if (node instanceof IfNode) {
            count += countCurrentGroupCalls(((IfNode) node).getContent(),
                                            depth + 1);
        }
        if (node instanceof LiteralResultElement) {
            LiteralResultElement lre = (LiteralResultElement) node;
            // Count current-group() in AVT attribute values
            Map<String, AttributeValueTemplate> attrs = lre.getAttributes();
            if (attrs != null) {
                List<XPathExpression> avtExprs =
                    new ArrayList<XPathExpression>();
                for (AttributeValueTemplate avt : attrs.values()) {
                    avt.collectExpressions(avtExprs);
                }
                for (int i = 0; i < avtExprs.size(); i++) {
                    count += StreamingClassifier.countFunctionCalls(
                        avtExprs.get(i), "current-group");
                }
            }
            count += countCurrentGroupCalls(lre.getContent(), depth + 1);
        }
        if (node instanceof ElementNode) {
            count += countCurrentGroupCalls(
                ((ElementNode) node).getContent(), depth + 1);
        }
        if (node instanceof VariableNode) {
            count += countCurrentGroupCalls(
                ((VariableNode) node).getContent(), depth + 1);
        }
        if (node instanceof TryNode) {
            TryNode tryNode = (TryNode) node;
            count += countCurrentGroupCalls(tryNode.getTryContent(),
                                            depth + 1);
            for (CatchNode catchNode : tryNode.getCatchBlocks()) {
                count += countCurrentGroupCalls(catchNode, depth + 1);
            }
        }
        if (node instanceof CatchNode) {
            count += countCurrentGroupCalls(
                ((CatchNode) node).getContent(), depth + 1);
        }
        if (node instanceof ChooseNode) {
            ChooseNode choose = (ChooseNode) node;
            for (WhenNode when : choose.getWhens()) {
                count += countCurrentGroupCalls(when, depth + 1);
            }
            count += countCurrentGroupCalls(choose.getOtherwise(),
                                            depth + 1);
        }
        if (node instanceof WhenNode) {
            count += countCurrentGroupCalls(
                ((WhenNode) node).getContent(), depth + 1);
        }
        return count;
    }

    /**
     * Checks whether the node tree contains consuming expressions that
     * navigate axis steps (LocationPath/PathExpr) independently of
     * current-group(). Only actual axis navigation expressions compete
     * for streamed input; functions like current-grouping-key() are
     * consuming but do not navigate children.
     */
    private boolean hasNonGroupConsuming(XSLTNode node, int depth) {
        if (node == null || depth > 50) {
            return false;
        }
        if (node instanceof ExpressionHolder) {
            List<XPathExpression> exprs =
                ((ExpressionHolder) node).getExpressions();
            if (exprs != null) {
                for (int i = 0; i < exprs.size(); i++) {
                    if (isAxisConsumingWithoutGroup(exprs.get(i))) {
                        return true;
                    }
                }
            }
        }
        if (node instanceof SequenceNode) {
            List<XSLTNode> children = ((SequenceNode) node).getChildren();
            if (children != null) {
                for (int i = 0; i < children.size(); i++) {
                    if (hasNonGroupConsuming(children.get(i), depth + 1)) {
                        return true;
                    }
                }
            }
        }
        if (node instanceof LiteralResultElement) {
            LiteralResultElement lre = (LiteralResultElement) node;
            Map<String, AttributeValueTemplate> attrs = lre.getAttributes();
            if (attrs != null) {
                List<XPathExpression> avtExprs =
                    new ArrayList<XPathExpression>();
                for (AttributeValueTemplate avt : attrs.values()) {
                    avt.collectExpressions(avtExprs);
                }
                for (int i = 0; i < avtExprs.size(); i++) {
                    if (isAxisConsumingWithoutGroup(avtExprs.get(i))) {
                        return true;
                    }
                }
            }
            return hasNonGroupConsuming(lre.getContent(), depth + 1);
        }
        if (node instanceof ElementNode) {
            return hasNonGroupConsuming(
                ((ElementNode) node).getContent(), depth + 1);
        }
        return false;
    }

    /**
     * Returns true if the expression navigates child/descendant axes
     * (consuming) without going through current-group(). Functions like
     * current-grouping-key() are excluded because they don't navigate.
     */
    private boolean isAxisConsumingWithoutGroup(XPathExpression expr) {
        int cgCalls = StreamingClassifier.countFunctionCalls(
            expr, "current-group");
        if (cgCalls > 0) {
            return false;
        }
        Expr compiled = expr.getCompiledExpr();
        if (compiled instanceof LocationPath
                || compiled instanceof PathExpr
                || compiled instanceof ContextItemExpr) {
            StreamabilityAnalyzer.ExpressionStreamability es =
                StreamingClassifier.classify(expr);
            return es == StreamabilityAnalyzer.ExpressionStreamability
                    .CONSUMING;
        }
        return false;
    }

    /**
     * Detects expressions like {@code current-group()/(AUTHOR||TITLE)}
     * where the path from current-group() has multiple consuming
     * down-selections. This requires reading group items multiple times.
     */
    private boolean hasMultipleConsumingFromGroup(XSLTNode node, int depth) {
        if (node == null || depth > 50) {
            return false;
        }
        if (node instanceof ExpressionHolder) {
            List<XPathExpression> exprs =
                ((ExpressionHolder) node).getExpressions();
            if (exprs != null) {
                for (int i = 0; i < exprs.size(); i++) {
                    if (hasMultiConsumingGroupPath(
                            exprs.get(i).getCompiledExpr())) {
                        return true;
                    }
                }
            }
        }
        if (node instanceof SequenceNode) {
            List<XSLTNode> children = ((SequenceNode) node).getChildren();
            if (children != null) {
                for (int i = 0; i < children.size(); i++) {
                    if (hasMultipleConsumingFromGroup(children.get(i),
                                                      depth + 1)) {
                        return true;
                    }
                }
            }
        }
        if (node instanceof LiteralResultElement) {
            return hasMultipleConsumingFromGroup(
                ((LiteralResultElement) node).getContent(), depth + 1);
        }
        return false;
    }

    /**
     * Checks if an expression is a path from current-group() that has
     * multiple consuming operands in its relative path (e.g.,
     * {@code current-group()/(A||B)}).
     */
    private boolean hasMultiConsumingGroupPath(Expr expr) {
        if (expr == null) {
            return false;
        }
        if (expr instanceof PathExpr) {
            PathExpr pe = (PathExpr) expr;
            int cgCalls = StreamingClassifier.countFunctionCalls(
                pe.getFilter(), "current-group");
            if (cgCalls > 0) {
                LocationPath path = pe.getPath();
                if (path != null) {
                    // Check step expressions for multi-consuming patterns
                    // e.g. current-group()/(AUTHOR||TITLE) where the step
                    // is an EXPR step containing a BinaryExpr
                    List<Step> steps = path.getSteps();
                    for (int i = 0; i < steps.size(); i++) {
                        Step step = steps.get(i);
                        Expr stepExpr = step.getStepExpr();
                        if (stepExpr != null) {
                            int ops = StreamingClassifier
                                .countConsumingOperands(stepExpr);
                            if (ops > 1) {
                                return true;
                            }
                        }
                    }
                    // Also check the path itself
                    int ops = StreamingClassifier.countConsumingOperands(
                        path);
                    if (ops > 1) {
                        return true;
                    }
                }
            }
        }
        if (expr instanceof BinaryExpr) {
            BinaryExpr be = (BinaryExpr) expr;
            return hasMultiConsumingGroupPath(be.getLeft())
                || hasMultiConsumingGroupPath(be.getRight());
        }
        return false;
    }

    /**
     * Detects current-group() used in a higher-order context, such as a
     * predicate on current-group() or inside a nested xsl:for-each body.
     * This pattern requires repeated access to group items and is not
     * streamable.
     */
    private boolean hasCurrentGroupInHigherOrder(XSLTNode node, int depth) {
        if (node == null || depth > 50) {
            return false;
        }

        // current-group() inside a nested xsl:for-each body
        if (node instanceof ForEachNode) {
            int cgInBody = countCurrentGroupCalls(
                ((ForEachNode) node).getBody(), 0);
            if (cgInBody > 0) {
                return true;
            }
        }

        // current-group() with a predicate: current-group()[$p]
        if (node instanceof ExpressionHolder) {
            List<XPathExpression> exprs =
                ((ExpressionHolder) node).getExpressions();
            if (exprs != null) {
                for (int i = 0; i < exprs.size(); i++) {
                    if (hasCurrentGroupWithPredicate(
                            exprs.get(i).getCompiledExpr())) {
                        return true;
                    }
                }
            }
        }

        if (node instanceof SequenceNode) {
            List<XSLTNode> children = ((SequenceNode) node).getChildren();
            if (children != null) {
                for (int i = 0; i < children.size(); i++) {
                    if (hasCurrentGroupInHigherOrder(children.get(i),
                                                     depth + 1)) {
                        return true;
                    }
                }
            }
        }
        if (node instanceof LiteralResultElement) {
            return hasCurrentGroupInHigherOrder(
                ((LiteralResultElement) node).getContent(), depth + 1);
        }
        if (node instanceof VariableNode) {
            return hasCurrentGroupInHigherOrder(
                ((VariableNode) node).getContent(), depth + 1);
        }
        return false;
    }

    /**
     * Checks whether an expression tree contains current-group() used
     * with a predicate, e.g. {@code current-group()[$p]}.
     */
    private boolean hasCurrentGroupWithPredicate(Expr expr) {
        if (expr == null) {
            return false;
        }
        if (expr instanceof FilterExpr) {
            FilterExpr fe = (FilterExpr) expr;
            Expr primary = fe.getPrimary();
            if (primary instanceof FunctionCall) {
                FunctionCall fc = (FunctionCall) primary;
                if ("current-group".equals(fc.getLocalName())) {
                    List<Expr> predicates = fe.getPredicates();
                    if (predicates != null && !predicates.isEmpty()) {
                        return true;
                    }
                }
            }
        }
        if (expr instanceof PathExpr) {
            PathExpr pe = (PathExpr) expr;
            return hasCurrentGroupWithPredicate(pe.getFilter())
                || hasCurrentGroupWithPredicate(pe.getPath());
        }
        if (expr instanceof BinaryExpr) {
            BinaryExpr be = (BinaryExpr) expr;
            return hasCurrentGroupWithPredicate(be.getLeft())
                || hasCurrentGroupWithPredicate(be.getRight());
        }
        return false;
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
        validateDeferredPatternPredicates();
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
                boolean include;
                try {
                    include = evaluateUseWhen(useWhen);
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
                    // XTSE0090: On an XSLT element, standard attributes must appear
                    // without a namespace. Having them in the XSLT namespace is an error.
                    if (XSLT_NS.equals(uri) && STANDARD_ATTRIBUTES.contains(attrLocal)) {
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
                    if (!isValidXsDecimal(versionAttr.trim())) {
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
                            checkNotReservedExtensionNamespace(defaultNs);
                            extensionNamespaceURIs.add(defaultNs);
                        }
                    } else {
                        String nsUri = namespaces.get(prefix);
                        if (nsUri == null || nsUri.isEmpty()) {
                            throw new SAXException("XTSE1430: No namespace binding " +
                                "in scope for prefix '" + prefix + "' in extension-element-prefixes");
                        }
                        checkNotReservedExtensionNamespace(nsUri);
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
        
        validateBreakNextIterationPosition(ctx);
        
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
                validateOnEmptyOrdering(parentCtx, node);
                parentCtx.children.add(node);
            }
            // Note: child element name tracking is done in startElement()
        }
    }

    /**
     * XTSE0010: Validates that nothing follows xsl:on-empty in a sequence
     * constructor. Also validates that xsl:on-empty comes after
     * xsl:on-non-empty if both are present.
     */
    private void validateOnEmptyOrdering(ElementContext parentCtx,
                                          XSLTNode newNode) throws SAXException {
        boolean parentHasOnEmpty = false;
        for (int i = 0; i < parentCtx.children.size(); i++) {
            if (parentCtx.children.get(i) instanceof OnEmptyNode) {
                parentHasOnEmpty = true;
                break;
            }
        }
        if (parentHasOnEmpty) {
            if (!(newNode instanceof OnEmptyNode)) {
                throw new SAXException("XTSE0010: xsl:on-empty must be the "
                    + "last instruction in a sequence constructor");
            }
        }
        if (newNode instanceof OnNonEmptyNode) {
            if (parentHasOnEmpty) {
                throw new SAXException("XTSE0010: xsl:on-non-empty must come "
                    + "before xsl:on-empty in a sequence constructor");
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
                // XTSE0010: text after xsl:on-empty
                for (int i = 0; i < ctx.children.size(); i++) {
                    if (ctx.children.get(i) instanceof OnEmptyNode) {
                        throw new SAXException("XTSE0010: xsl:on-empty must be the "
                            + "last instruction in a sequence constructor");
                    }
                }
                // Parse Text Value Templates if expand-text is enabled
                // XSLT 3.0: TVTs apply to all text in sequence constructors
                if (ctx.expandText) {
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
            case "function":
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
                } else {
                    throw new SAXException("XTSE0809: #default used in " +
                        "exclude-result-prefixes but no default namespace is declared");
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
                result = compileLiteralResultElement(ctx);
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
                    if (isElementForwardCompatible(ctx)) {
                        continue;
                    }
                    throw new SAXException("XTSE0090: Attribute '" + attrName + 
                        "' in the XSLT namespace is not allowed on xsl:" + ctx.localName);
                }
                
                // Non-XSLT namespace attributes are allowed (extension attributes)
                continue;
            }
            
            // XTSE0090: Check if attribute is in allowed set or standard attributes
            // In forward-compatible mode, unknown attributes are silently ignored
            if (!allowed.contains(localAttrName) && !STANDARD_ATTRIBUTES.contains(localAttrName)) {
                if (isElementForwardCompatible(ctx)) {
                    continue;
                }
                throw new SAXException("XTSE0090: Unknown attribute '" + attrName + 
                    "' on xsl:" + ctx.localName);
            }
            
            // XTSE0020: Validate QName attributes (but skip if it's an AVT with expressions)
            // The 'name' attribute on xsl:package and xsl:use-package is a URI, not a QName
            if (isQNameAttribute(localAttrName, ctx.localName) && attrValue != null && !attrValue.isEmpty()) {
                // If the value contains {}, it's an AVT and we can't validate statically
                if (!attrValue.contains("{") || attrValue.startsWith("Q{")) {
                    XSLTSchemaValidator.validateQName(localAttrName, attrValue);
                }
            }
        }
    }
    
    /**
     * Checks if an attribute expects a QName value.
     * The 'name' attribute on xsl:package and xsl:use-package is a URI, not a QName.
     */
    private boolean isQNameAttribute(String attrName, String elementName) {
        if ("name".equals(attrName)) {
            return !"package".equals(elementName) && !"use-package".equals(elementName);
        }
        return "mode".equals(attrName);
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
    private boolean isElementForwardCompatible(ElementContext ctx) {
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
        validateAllowedAttributes(ctx);
        
        // Per XSLT spec, xsl:fallback is silently ignored inside instructions that
        // accept a sequence constructor as content. For instructions with restricted
        // content models (apply-imports, apply-templates, call-template, choose, etc.),
        // xsl:fallback is NOT permitted and should trigger XTSE0010 from validation.
        // Extract fallback for the default case (unknown elements in FC mode need fallback).
        List<XSLTNode> fallbackNodes = new ArrayList<>();
        if (!hasRestrictedContentModel(ctx.localName)) {
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
                // Also allowed inside xsl:override (within xsl:use-package)
                if (!isTopLevel() && !isInsideOverride()) {
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
                validateLexicallyInIterate("xsl:next-iteration");
                return compileNextIteration(ctx);
                
            case "break":
                validateNotTopLevel(ctx.localName);
                validateLexicallyInIterate("xsl:break");
                return compileBreak(ctx);
                
            case "on-completion":
                validateNotTopLevel(ctx.localName);
                validateDirectChildOfIterate("xsl:on-completion");
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
                
            case "try": {
                if (maxProcessorVersion < 3.0) {
                    return compileUnknownXSLTInstruction(ctx, fallbackNodes);
                }
                validateNotTopLevel(ctx.localName);
                return compileTry(ctx);
            }
            case "catch": {
                if (maxProcessorVersion < 3.0) {
                    return compileUnknownXSLTInstruction(ctx, fallbackNodes);
                }
            }
                validateNotTopLevel(ctx.localName);
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
                return compileUnknownXSLTInstruction(ctx, fallbackNodes);
        }
    }
    
    /**
     * Handles an unknown XSLT instruction element.
     * In forward-compatible mode, uses xsl:fallback if present, otherwise
     * defers the error to runtime. Without FC mode, raises XTSE0010.
     */
    private XSLTNode compileUnknownXSLTInstruction(ElementContext ctx, 
            List<XSLTNode> fallbackNodes) throws SAXException {
        boolean effectiveFC = forwardCompatible || isElementForwardCompatible(ctx)
            || (maxProcessorVersion > 0 && stylesheetVersion > maxProcessorVersion);
        // Element-level version higher than processor version also triggers FC
        // for unknown instructions (e.g., xsl:try version="3.0" in XSLT 2.0)
        if (!effectiveFC && maxProcessorVersion > 0) {
            double elemVer = ctx.effectiveVersion > 0
                ? ctx.effectiveVersion : getEffectiveVersion();
            if (elemVer > maxProcessorVersion) {
                effectiveFC = true;
            }
        }
        if (effectiveFC) {
            if (!fallbackNodes.isEmpty()) {
                return new SequenceNode(fallbackNodes);
            }
            final String unknownName = ctx.localName;
            return new XSLTNode() {
                @Override
                public void execute(TransformContext context,
                        OutputHandler output) throws SAXException {
                    throw new SAXException("XTDE1450: Unknown XSLT instruction " +
                        "xsl:" + unknownName + " (no xsl:fallback)");
                }
            };
        }
        throw new SAXException("XTSE0010: Unknown XSLT element: xsl:" + ctx.localName);
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
        String exponentSeparator = ctx.attributes.get("exponent-separator");
        
        // XTSE0090: exponent-separator is XSLT 3.0+ only
        if (exponentSeparator != null && maxProcessorVersion < 3.0) {
            throw new SAXException("XTSE0090: Attribute 'exponent-separator' " +
                "on xsl:decimal-format is not allowed in XSLT " + maxProcessorVersion);
        }
        
        // XTSE0020: name attribute cannot be an AVT
        validateNotAVT("xsl:decimal-format", "name", name);
        
        // Resolve QName prefix to expanded name
        if (name != null && name.contains(":")) {
            int colonIdx = name.indexOf(':');
            String prefix = name.substring(0, colonIdx);
            String localName = name.substring(colonIdx + 1);
            String nsUri = ctx.namespaceBindings.get(prefix);
            if (nsUri == null) {
                nsUri = lookupNamespaceUri(prefix);
            }
            // XTSE0080: Check for reserved namespace
            if (nsUri != null && isReservedNamespace(nsUri)) {
                throw new SAXException("XTSE0080: Reserved namespace '" + nsUri + 
                    "' cannot be used in the decimal-format name '" + name + "'");
            }
            // Store using expanded QName for namespace-aware lookup
            if (nsUri != null) {
                name = "{" + nsUri + "}" + localName;
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
        
        // XTSE1295: zero-digit must be a Unicode character with numeric value zero
        if (zeroDigit != null && zeroDigit.length() == 1) {
            int numericValue = Character.getNumericValue(zeroDigit.charAt(0));
            if (numericValue != 0) {
                throw new SAXException("XTSE1295: zero-digit character '" + zeroDigit +
                    "' does not have numeric value zero");
            }
        }
        
        builder.addDecimalFormat(name, decimalSeparator, groupingSeparator,
            infinity, minusSign, nan, percent, perMille, zeroDigit, digit, patternSeparator,
            exponentSeparator);
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
        if (value != null && value.codePointCount(0, value.length()) > 1) {
            throw new SAXException("XTSE0020: " + attrName + " must be a single character, got: '" + value + "'");
        }
    }
    
    /**
     * Validates that an attribute is not an AVT (does not contain accolades).
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

    /**
     * Checks that a namespace URI is not a reserved namespace for extension-element-prefixes.
     * Per XSLT spec XTSE0085: reserved namespaces cannot be used as extension namespace URIs.
     */
    private void checkNotReservedExtensionNamespace(String nsUri) throws SAXException {
        if (XSLT_NS.equals(nsUri)) {
            throw new SAXException("XTSE0085: The XSLT namespace cannot be used " +
                "as an extension namespace URI");
        }
        if (XSD_NAMESPACE.equals(nsUri)) {
            throw new SAXException("XTSE0085: The XML Schema namespace cannot be used " +
                "as an extension namespace URI");
        }
        if ("http://www.w3.org/XML/1998/namespace".equals(nsUri)) {
            throw new SAXException("XTSE0085: The XML namespace cannot be used " +
                "as an extension namespace URI");
        }
    }

    private void validateNotAVT(String elementName, String attrName, String value) throws SAXException {
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
     * Checks whether a global variable's select expression references the
     * variable being defined. A global variable is not in scope within its
     * own declaration (XPST0008).
     */
    private void checkSelfReference(XPathExpression selectExpr, String varLocalName) throws SAXException {
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
    private void validateFunctionTypeAs(String asType) throws SAXException {
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
    private void validateOutputBoolean(ElementContext ctx, String attrName) throws SAXException {
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
    private boolean isPubidChar(char c) {
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
                    
                    // Find the matching closing accolade
                    // Need to handle: string literals, XPath comments, nested accolades
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
                    if (xpathExpr.isEmpty() || AttributeValueTemplate.isEmptyExpression(xpathExpr)) {
                        // Empty TVT expression {} or comment-only is allowed
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
                // Exception: xsl:initial-template is always allowed (system-defined name)
                if ("initial-template".equals(localName)) {
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
        String rawName = ctx.attributes.get("name");
        if (rawName == null || rawName.isEmpty()) {
            throw new SAXException("XTSE0010: xsl:character-map requires name attribute");
        }
        
        // XTSE0080: Check for reserved namespace in character-map name
        if (rawName.contains(":")) {
            int colonIdx = rawName.indexOf(':');
            String prefix = rawName.substring(0, colonIdx);
            String nsUri = ctx.namespaceBindings.get(prefix);
            if (nsUri == null) {
                nsUri = lookupNamespaceUri(prefix);
            }
            if (nsUri != null && isReservedNamespace(nsUri)) {
                throw new SAXException("XTSE0080: Reserved namespace '" + nsUri + 
                    "' cannot be used in the character-map name '" + rawName + "'");
            }
        }
        
        String name = expandQName(rawName.trim());
        
        // XTSE1580 only applies at the same import precedence.
        // Allow redefinition: last definition wins within same stylesheet module,
        // and mergeNonTemplates handles "first wins" for imports.
        
        String useCharacterMaps = ctx.attributes.get("use-character-maps");
        
        CompiledStylesheet.CharacterMap charMap = new CompiledStylesheet.CharacterMap(name);
        
        // Process use-character-maps references (expand QNames)
        if (useCharacterMaps != null && !useCharacterMaps.isEmpty()) {
            String[] refs = useCharacterMaps.split("\\s+");
            for (String ref : refs) {
                if (!ref.isEmpty()) {
                    String expandedRef = expandQName(ref.trim());
                    charMap.addUseCharacterMap(expandedRef);
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
        String name = resolveStaticShadowAttribute(ctx, "name");
        if (name == null || name.isEmpty()) {
            throw new SAXException("xsl:accumulator requires name attribute");
        }
        ensurePrecedenceAssigned();
        
        
        String initialValueStr = resolveStaticShadowAttribute(ctx, "initial-value");
        if (initialValueStr == null) {
            throw new SAXException("xsl:accumulator requires initial-value attribute");
        }
        
        XPathExpression initialValue = compileExpression(initialValueStr);
        String asType = ctx.attributes.get("as");
        String streamableAttr = resolveStaticShadowAttribute(ctx, "streamable");
        if (streamableAttr != null && !streamableAttr.isEmpty()) {
            validateYesOrNo("xsl:accumulator", "streamable", streamableAttr);
        }
        boolean streamable;
        boolean explicitlyStreamable = false;
        if (streamableAttr == null) {
            streamable = true;
        } else {
            String sv = streamableAttr.trim();
            streamable = "yes".equals(sv) || "true".equals(sv) || "1".equals(sv);
            explicitlyStreamable = streamable;
        }
        
        String expandedName = expandQName(name.trim());
        
        AccumulatorDefinition.Builder accBuilder = new AccumulatorDefinition.Builder()
            .name(name)
            .expandedName(expandedName)
            .initialValue(initialValue)
            .streamable(streamable)
            .asType(asType)
            .importPrecedence(importPrecedence);
        
        // Process accumulator-rule children
        int ruleCount = 0;
        for (XSLTNode child : ctx.children) {
            if (child instanceof AccumulatorRuleNode) {
                AccumulatorRuleNode ruleNode = (AccumulatorRuleNode) child;
                accBuilder.addRule(ruleNode.toRule());
                ruleCount++;
            }
        }
        
        // XTSE0010: accumulator must have at least one accumulator-rule
        if (ruleCount == 0) {
            throw new SAXException("XTSE0010: xsl:accumulator must have at least one xsl:accumulator-rule child");
        }

        // XTSE3430: streamable accumulator rule patterns must be motionless
        if (explicitlyStreamable) {
            for (XSLTNode child : ctx.children) {
                if (child instanceof AccumulatorRuleNode) {
                    AccumulatorRuleNode ruleNode = (AccumulatorRuleNode) child;
                    Pattern rulePat = ruleNode.getPattern();
                    if (rulePat != null && hasNonMotionlessPredicate(rulePat)) {
                        throw new SAXException(
                            "XTSE3430: Accumulator rule pattern '" + rulePat +
                            "' in streamable accumulator '" + name +
                            "' is not motionless");
                    }
                }
            }
        }
        
        builder.addAccumulator(accBuilder.build());
    }
    
    /**
     * Processes an xsl:mode declaration (XSLT 3.0).
     */
    private void processModeDeclaration(ElementContext ctx) throws SAXException {
        // XTSE0010: xsl:mode must be empty
        validateEmptyElement(ctx, "xsl:mode");
        
        String name = ctx.attributes.get("name");
        String streamableAttr = resolveStaticShadowAttribute(ctx, "streamable");
        String onNoMatchAttr = ctx.attributes.get("on-no-match");
        String onMultipleMatchAttr = ctx.attributes.get("on-multiple-match");
        String visibilityAttr = ctx.attributes.get("visibility");
        String useAccumulators = ctx.attributes.get("use-accumulators");
        String typedAttr = ctx.attributes.get("typed");
        String warningOnNoMatch = ctx.attributes.get("warning-on-no-match");
        String warningOnMultipleMatch = ctx.attributes.get("warning-on-multiple-match");
        
        // XTSE0020: validate boolean attributes
        validateYesOrNo("xsl:mode", "streamable", streamableAttr);
        validateYesOrNo("xsl:mode", "typed", typedAttr);
        validateYesOrNo("xsl:mode", "warning-on-no-match", warningOnNoMatch);
        validateYesOrNo("xsl:mode", "warning-on-multiple-match", warningOnMultipleMatch);
        
        // XTSE0020: unnamed mode cannot be public or final
        if (name == null && visibilityAttr != null) {
            String vis = visibilityAttr.trim();
            if ("public".equals(vis) || "final".equals(vis)) {
                throw new SAXException("XTSE0020: The unnamed mode cannot have visibility='" +
                    vis + "'");
            }
        }
        
        // XTSE0545: detect conflicting mode declarations at same import precedence
        String modeKey = name != null ? name : "#default";
        checkModeConflict(modeKey, "on-no-match", onNoMatchAttr);
        checkModeConflict(modeKey, "on-multiple-match", onMultipleMatchAttr);
        checkModeConflict(modeKey, "visibility", visibilityAttr);
        checkModeConflict(modeKey, "streamable", streamableAttr);
        if (useAccumulators != null) {
            checkModeConflict(modeKey, "use-accumulators",
                expandAccumulatorNames(useAccumulators));
        }
        
        boolean isStreamable = "yes".equals(streamableAttr)
            || "true".equals(streamableAttr)
            || "1".equals(streamableAttr);
        ModeDeclaration.Builder modeBuilder = new ModeDeclaration.Builder()
            .name(name)
            .onNoMatch(onNoMatchAttr)
            .onMultipleMatch(onMultipleMatchAttr)
            .visibility(visibilityAttr)
            .useAccumulators(useAccumulators)
            .typed("yes".equals(typedAttr))
            .warning("yes".equals(warningOnNoMatch));
        if (streamableAttr != null) {
            modeBuilder.streamable(isStreamable);
        }
        if (useAccumulators != null) {
            modeBuilder.expandedUseAccumulators(expandAccumulatorNames(useAccumulators));
        }
        
        builder.addModeDeclaration(modeBuilder.build());
    }
    
    /**
     * Checks for conflicting mode attribute values (XTSE0545).
     * Two declarations for the same mode must not set the same attribute
     * to different values at the same import precedence.
     */
    private String expandAccumulatorNames(String names) throws SAXException {
        String trimmed = names.trim();
        if ("#all".equals(trimmed)) {
            return trimmed;
        }
        String[] tokens = trimmed.split("\\s+");
        TreeSet<String> expanded = new TreeSet<String>();
        for (String token : tokens) {
            expanded.add(expandQName(token));
        }
        StringBuilder sb = new StringBuilder();
        for (String name : expanded) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(name);
        }
        return sb.toString();
    }

    private void checkModeConflict(String modeKey, String attrName,
                                    String attrValue) throws SAXException {
        if (attrValue == null) {
            return;
        }
        String trackKey = modeKey + "|" + attrName;
        String existing = modeAttributeValues.get(trackKey);
        if (existing != null && !existing.equals(attrValue)) {
            String message = "XTSE0545: Conflicting values for " + attrName +
                " on mode '" + modeKey + "': '" + existing + "' vs '" + attrValue + "'";
            builder.recordModeConflict(modeKey, attrName, message);
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
        if (!isTopLevel()) {
            throw new SAXException("XTSE0010: xsl:function must be a top-level element");
        }
        importsAllowed = false;
        ensurePrecedenceAssigned();  // Assign precedence after all imports are processed
        
        // Check shadow attribute first (_name="{...}"), then regular name
        String name = resolveStaticShadowAttribute(ctx, "name");
        if (name == null || name.isEmpty()) {
            name = ctx.attributes.get("name");
        }
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
        String visibilityAttr = resolveStaticShadowAttribute(ctx, "visibility");
        String cacheAttr = ctx.attributes.get("cache");
        String overrideExtAttr = ctx.attributes.get("override-extension-function");
        String overrideAttr = ctx.attributes.get("override");
        String newEachTimeAttr = ctx.attributes.get("new-each-time");
        String identitySensitiveAttr = ctx.attributes.get("identity-sensitive");
        validateYesOrNo("xsl:function", "cache", cacheAttr);
        validateYesOrNo("xsl:function", "override-extension-function", overrideExtAttr);
        validateYesOrNo("xsl:function", "override", overrideAttr);
        // new-each-time allows yes/no/true/false/1/0/maybe
        if (newEachTimeAttr != null) {
            String net = newEachTimeAttr.trim();
            if (!"yes".equals(net) && !"no".equals(net) && !"true".equals(net) &&
                    !"false".equals(net) && !"1".equals(net) && !"0".equals(net) &&
                    !"maybe".equals(net)) {
                throw new SAXException("XTSE0020: Invalid value for new-each-time attribute " +
                    "on xsl:function: must be yes, no, maybe, true, false, 1, or 0, got '" +
                    newEachTimeAttr + "'");
            }
        }
        validateYesOrNo("xsl:function", "identity-sensitive", identitySensitiveAttr);
        if (overrideAttr != null && overrideExtAttr != null) {
            String ov = overrideAttr.trim();
            boolean overrideVal = "yes".equals(ov) || "true".equals(ov) || "1".equals(ov);
            String oev = overrideExtAttr.trim();
            boolean overrideExtVal = "yes".equals(oev) || "true".equals(oev) || "1".equals(oev);
            if (overrideVal != overrideExtVal) {
                throw new SAXException("XTSE0020: xsl:function has conflicting values for " +
                    "'override' and 'override-extension-function' attributes");
            }
        }
        boolean cached = "yes".equals(cacheAttr) || "true".equals(cacheAttr);
        ComponentVisibility funcVisibility = ComponentVisibility.PRIVATE;
        if (visibilityAttr != null && !visibilityAttr.isEmpty()) {
            try {
                funcVisibility = ComponentVisibility.valueOf(visibilityAttr.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new SAXException("XTSE0020: Invalid visibility value for xsl:function: " +
                    visibilityAttr);
            }
        }
        
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
                params.add(new UserFunction.FunctionParameter(pn.getNamespaceURI(), pn.getLocalName(), paramAs));
            } else if (child instanceof ContextItemDeclaration) {
                throw new SAXException("XTSE0010: xsl:context-item is not allowed " +
                    "in xsl:function");
            } else {
                bodyNodes.add(child);
            }
        }
        
        SequenceNode body = new SequenceNode(bodyNodes);
        
        // XSLT 3.0: Validate streamability attribute
        String streamabilityAttr = ctx.attributes.get("streamability");
        String streamability = null;
        if (streamabilityAttr != null && !streamabilityAttr.isEmpty()) {
            streamability = streamabilityAttr.trim();
            if (!"unclassified".equals(streamability)) {
                // XTSE3155: Streamable function must have at least one parameter
                if (params.isEmpty()) {
                    throw new SAXException("XTSE3155: xsl:function with " +
                        "streamability='" + streamability + "' must have " +
                        "at least one parameter");
                }
                if ("shallow-descent".equals(streamability) ||
                        "deep-descent".equals(streamability)) {
                    validateDescentFunction(params, bodyNodes, name);
                }
                if ("absorbing".equals(streamability)) {
                    validateAbsorbingFunction(params, bodyNodes, name);
                }
            }
        }

        UserFunction function = new UserFunction(
            namespaceURI, localName, params, body, asType, importPrecedence, cached, funcVisibility);
        if (streamability != null) {
            function.setStreamability(streamability);
        }
        try {
            builder.addUserFunction(function);
        } catch (javax.xml.transform.TransformerConfigurationException e) {
            throw new SAXException(e.getMessage(), e);
        }
    }
    
    /**
     * XTSE3430: Validates constraints on a shallow-descent or deep-descent
     * streamable function declaration.
     *
     * <p>For shallow-descent functions, the spec requires:
     * <ul>
     *   <li>The first parameter must be declared as node()</li>
     *   <li>The body must not use descendant axis (crawling)</li>
     *   <li>The body must produce nodes, not atomic values (striding)</li>
     *   <li>The body must not reference the first parameter multiple
     *       times (consuming)</li>
     * </ul>
     */
    private void validateDescentFunction(
            List<UserFunction.FunctionParameter> params,
            List<XSLTNode> bodyNodes, String funcName) throws SAXException {
        // First parameter must be typed as a single node
        UserFunction.FunctionParameter firstParam = params.get(0);
        String firstParamType = firstParam.getAsType();
        if (!isSingleNodeType(firstParamType)) {
            throw new SAXException("XTSE3430: First parameter of " +
                "shallow-descent function '" + funcName + "' must be " +
                "declared as a single node type (e.g. node(), element())");
        }

        // Check body expressions for streaming violations
        for (XSLTNode node : bodyNodes) {
            validateDescentFunctionBody(node, funcName, firstParam.getName(), 0);
        }
    }

    /**
     * Recursively checks a shallow-descent function body for streaming
     * violations: descendant axis usage, atomic-returning expressions,
     * and multi-access patterns.
     */
    private void validateDescentFunctionBody(XSLTNode node, String funcName,
            String firstParamName, int depth) throws SAXException {
        if (node == null || depth > 30) {
            return;
        }
        if (node instanceof ExpressionHolder) {
            List<XPathExpression> exprs =
                ((ExpressionHolder) node).getExpressions();
            for (XPathExpression expr : exprs) {
                String exprStr = expr.toString();

                // Body must not use descendant axis (crawling)
                if (containsDescendantAxis(exprStr)) {
                    throw new SAXException("XTSE3430: Body of " +
                        "shallow-descent function '" + funcName +
                        "' uses descendant axis (crawling expression)");
                }

                // Body must return nodes, not atomic values
                if (returnsAtomicValue(exprStr, firstParamName)) {
                    throw new SAXException("XTSE3430: Body of " +
                        "shallow-descent function '" + funcName +
                        "' returns an atomic value (non-striding result)");
                }

                // Body must not access the first parameter multiple times
                if (hasNonConsumingAccess(exprStr, firstParamName)) {
                    throw new SAXException("XTSE3430: Body of " +
                        "shallow-descent function '" + funcName +
                        "' has a non-consuming access pattern");
                }
            }
        }
        // Recurse into child nodes
        if (node instanceof SequenceNode) {
            for (XSLTNode child : ((SequenceNode) node).getChildren()) {
                validateDescentFunctionBody(child, funcName,
                    firstParamName, depth + 1);
            }
        }
    }

    /**
     * XTSE3430: Validates constraints on an absorbing streamable function.
     *
     * <p>For absorbing functions:
     * <ul>
     *   <li>If the first parameter is a sequence type, the body must not
     *       have multiple references to it (each reference consumes the
     *       stream)</li>
     *   <li>The body must not return the parameter node directly
     *       (non-grounded return)</li>
     *   <li>The body must not use the path() function (requires
     *       ancestor access)</li>
     * </ul>
     */
    private void validateAbsorbingFunction(
            List<UserFunction.FunctionParameter> params,
            List<XSLTNode> bodyNodes, String funcName) throws SAXException {
        UserFunction.FunctionParameter firstParam = params.get(0);
        String paramName = firstParam.getName();
        String paramType = firstParam.getAsType();

        // Collect all expression strings from the body
        List<String> exprStrings = new ArrayList<>();
        collectExpressionStrings(bodyNodes, exprStrings, 0);

        // Check for path() function in body (non-streamable)
        for (String exprStr : exprStrings) {
            if (containsPathFunctionCall(exprStr)) {
                throw new SAXException("XTSE3430: Body of absorbing " +
                    "function '" + funcName + "' uses the path() " +
                    "function which requires ancestor access");
            }
        }

        // Check for non-grounded return (returning the parameter directly
        // from an xsl:sequence instruction)
        if (hasNonGroundedSequenceReturn(bodyNodes, paramName, 0)) {
            throw new SAXException("XTSE3430: Body of absorbing " +
                "function '" + funcName + "' can return the " +
                "streaming parameter directly (non-grounded result)");
        }

        // For sequence parameters (node()*, element()*), multiple
        // references to the parameter are not allowed because the
        // stream cannot be rewound.
        boolean isSequenceParam = paramType != null &&
            (paramType.trim().endsWith("*") || paramType.trim().endsWith("+"));
        if (isSequenceParam) {
            int totalRefs = 0;
            for (String exprStr : exprStrings) {
                totalRefs += countParamReferences(exprStr, paramName);
            }
            if (totalRefs > 1) {
                throw new SAXException("XTSE3430: Body of absorbing " +
                    "function '" + funcName + "' has multiple " +
                    "references to sequence parameter '$" + paramName +
                    "' (stream cannot be rewound)");
            }

            // A single reference with a variable-based predicate inside
            // a loop (e.g. "for $i in ... return ...$param[$i]...")
            // accesses the sequence multiple times.
            for (String exprStr : exprStrings) {
                if (hasLoopBasedPredicateAccess(exprStr, paramName)) {
                    throw new SAXException("XTSE3430: Body of absorbing " +
                        "function '" + funcName + "' has a consuming " +
                        "reference to parameter '$" + paramName +
                        "' in a loop");
                }
            }
        }
    }

    /**
     * Collects all expression strings from a list of XSLT nodes,
     * recursing into all container node types.
     */
    private void collectExpressionStrings(List<XSLTNode> nodes,
            List<String> result, int depth) {
        if (depth > 30) {
            return;
        }
        for (XSLTNode node : nodes) {
            collectNodeExpressionStrings(node, result, depth);
        }
    }

    /**
     * Collects expression strings from a single XSLT node and its
     * children, recursing into all container node types.
     */
    private void collectNodeExpressionStrings(XSLTNode node,
            List<String> result, int depth) {
        if (node == null || depth > 30) {
            return;
        }
        if (node instanceof ExpressionHolder) {
            List<XPathExpression> exprs =
                ((ExpressionHolder) node).getExpressions();
            for (XPathExpression expr : exprs) {
                if (expr != null) {
                    result.add(expr.getExpressionString());
                }
            }
        }
        if (node instanceof SequenceNode) {
            List<XSLTNode> children = ((SequenceNode) node).getChildren();
            for (int i = 0; i < children.size(); i++) {
                collectNodeExpressionStrings(children.get(i), result,
                    depth + 1);
            }
        }
        if (node instanceof CopyNode) {
            XSLTNode content =
                ((CopyNode) node).getContent();
            collectNodeExpressionStrings(content, result, depth + 1);
        }
        if (node instanceof ElementNode) {
            XSLTNode content =
                ((ElementNode) node).getContent();
            collectNodeExpressionStrings(content, result, depth + 1);
        }
        if (node instanceof LiteralResultElement) {
            XSLTNode content =
                ((LiteralResultElement) node).getContent();
            collectNodeExpressionStrings(content, result, depth + 1);
        }
        if (node instanceof ForEachNode) {
            XSLTNode body =
                ((ForEachNode) node).getBody();
            collectNodeExpressionStrings(body, result, depth + 1);
        }
        if (node instanceof IfNode) {
            XSLTNode content =
                ((IfNode) node).getContent();
            collectNodeExpressionStrings(content, result, depth + 1);
        }
        if (node instanceof ChooseNode) {
            ChooseNode choose = (ChooseNode) node;
            for (WhenNode when : choose.getWhens()) {
                collectNodeExpressionStrings(when.getContent(), result,
                    depth + 1);
            }
            collectNodeExpressionStrings(choose.getOtherwise(), result,
                depth + 1);
        }
        if (node instanceof VariableNode) {
            XSLTNode content =
                ((VariableNode) node).getContent();
            collectNodeExpressionStrings(content, result, depth + 1);
        }
    }

    /**
     * Checks if an expression string calls the path() function.
     */
    private boolean containsPathFunctionCall(String expr) {
        int idx = expr.indexOf("path(");
        while (idx >= 0) {
            if (idx == 0 || !Character.isLetterOrDigit(expr.charAt(idx - 1))) {
                return true;
            }
            idx = expr.indexOf("path(", idx + 1);
        }
        return false;
    }

    /**
     * Checks the function body for xsl:sequence instructions that can
     * return the streaming parameter node directly (non-grounded).
     * Only xsl:sequence is checked — expressions in xsl:apply-templates,
     * xsl:copy, etc. are not direct returns.
     */
    private boolean hasNonGroundedSequenceReturn(List<XSLTNode> nodes,
            String paramName, int depth) {
        if (depth > 30) {
            return false;
        }
        for (XSLTNode node : nodes) {
            if (node instanceof SequenceOutputNode) {
                List<XPathExpression> exprs =
                    ((ExpressionHolder) node).getExpressions();
                for (XPathExpression expr : exprs) {
                    if (expr != null) {
                        String exprStr = expr.getExpressionString();
                        if (hasNonGroundedReturn(exprStr, paramName)) {
                            return true;
                        }
                    }
                }
            }
            if (node instanceof SequenceNode) {
                boolean result = hasNonGroundedSequenceReturn(
                    ((SequenceNode) node).getChildren(), paramName,
                    depth + 1);
                if (result) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Checks if an expression string can return the parameter directly.
     * Detects "else $param" at the end of an if-expression branch or
     * "$param" as the entire expression.
     */
    private boolean hasNonGroundedReturn(String expr, String paramName) {
        String paramRef = "$" + paramName;
        String trimmed = expr.trim();
        // Strip trailing close parens (if-expression nesting)
        while (trimmed.endsWith(")")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
        }
        if (trimmed.endsWith("else " + paramRef)) {
            return true;
        }
        if (trimmed.equals(paramRef)) {
            return true;
        }
        return false;
    }

    /**
     * Counts the number of references to a parameter in an expression.
     */
    private int countParamReferences(String expr, String paramName) {
        String paramRef = "$" + paramName;
        int count = 0;
        int idx = expr.indexOf(paramRef);
        while (idx >= 0) {
            int afterIdx = idx + paramRef.length();
            if (afterIdx >= expr.length() ||
                    (!Character.isLetterOrDigit(expr.charAt(afterIdx)) &&
                     expr.charAt(afterIdx) != '_' &&
                     expr.charAt(afterIdx) != '-')) {
                count++;
            }
            idx = expr.indexOf(paramRef, idx + 1);
        }
        return count;
    }

    /**
     * Checks if the parameter is accessed with a variable-based predicate
     * inside a loop construct (e.g. "for $i in 1 to 3 return ...$param[$i]").
     */
    private boolean hasLoopBasedPredicateAccess(String expr, String paramName) {
        String paramRef = "$" + paramName;
        boolean hasForLoop = expr.contains("for $") && expr.contains("return");
        if (!hasForLoop) {
            return false;
        }
        // Check for $param[$...] pattern (predicate with variable)
        int idx = expr.indexOf(paramRef);
        while (idx >= 0) {
            int afterIdx = idx + paramRef.length();
            if (afterIdx < expr.length() && expr.charAt(afterIdx) == '[') {
                int closeBracket = expr.indexOf(']', afterIdx);
                if (closeBracket > afterIdx) {
                    String predicate = expr.substring(afterIdx + 1, closeBracket);
                    if (predicate.contains("$")) {
                        return true;
                    }
                }
            }
            idx = expr.indexOf(paramRef, idx + 1);
        }
        return false;
    }

    /**
     * Returns true if the type string represents a single node type
     * (node(), element(), element(name), document-node(), etc.)
     * without occurrence indicators.
     */
    private boolean isSingleNodeType(String typeStr) {
        if (typeStr == null) {
            return false;
        }
        String t = typeStr.trim();
        if (t.endsWith("*") || t.endsWith("+") || t.endsWith("?")) {
            return false;
        }
        return t.startsWith("node(") || t.startsWith("element(") ||
            t.startsWith("document-node(") || t.startsWith("text(") ||
            t.startsWith("comment(") || t.startsWith("attribute(") ||
            t.startsWith("processing-instruction(") ||
            t.startsWith("namespace-node(");
    }

    /**
     * Heuristic check for expressions that return atomic values rather
     * than nodes, where an aggregate function is applied directly to the
     * first parameter (e.g. sum($n), count($n)).
     */
    private boolean returnsAtomicValue(String expr, String paramName) {
        String paramRef = "$" + paramName;
        String[] atomicFunctions = {
            "sum(", "count(", "avg(", "min(", "max(",
            "string-length(", "number("
        };
        for (String func : atomicFunctions) {
            String pattern = func + paramRef;
            int idx = expr.indexOf(pattern);
            if (idx >= 0) {
                int afterIdx = idx + pattern.length();
                if (afterIdx >= expr.length()) {
                    return true;
                }
                char ch = expr.charAt(afterIdx);
                if (!Character.isLetterOrDigit(ch) && ch != '_' && ch != '-') {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Checks if an expression contains a non-consuming access pattern
     * such as the simple map operator (!) referencing the first parameter,
     * which would cause the node to be accessed multiple times.
     */
    private boolean hasNonConsumingAccess(String expr, String paramName) {
        String paramRef = "$" + paramName;
        int bangIdx = expr.indexOf('!');
        if (bangIdx < 0) {
            return false;
        }
        // Check if right side of ! references the parameter
        // Pattern: "... ! $param..." where left side is multi-valued
        String rightSide = expr.substring(bangIdx + 1).trim();
        if (rightSide.startsWith(paramRef)) {
            return true;
        }
        return false;
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
        
        if (newValue == null && !ctx.children.isEmpty()) {
            XSLTNode body = new SequenceNode(ctx.children);
            return new AccumulatorRuleNode(pattern, phase, body);
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
        
        // XTSE1660: Non-schema-aware processor must reject any xsl:type attribute
        String typeValue = ctx.attributes.get("xsl:type");
        if (typeValue != null && !typeValue.isEmpty()) {
            throw new SAXException("XTSE1660: xsl:type='" + typeValue +
                "' requires a schema-aware processor");
        }
        
        // XTSE1660: Non-schema-aware processor must reject certain xsl:validation values
        String validationValue = ctx.attributes.get("xsl:validation");
        if (validationValue != null && !validationValue.isEmpty()) {
            parseValidationMode(validationValue.trim(), "literal result element");
        }
        
        // Compile attributes as AVTs
        // Check if we're in backward-compat mode (version 1.0)
        double lreEffectiveVer = ctx.effectiveVersion > 0 ? ctx.effectiveVersion : getEffectiveVersion();
        boolean lreBackwardsCompat = lreEffectiveVer < 2.0;
        Map<String, AttributeValueTemplate> avts = new LinkedHashMap<>();
        for (Map.Entry<String, String> attr : ctx.attributes.entrySet()) {
            String name = attr.getKey();
            String value = attr.getValue();
            
            // Validate and skip xsl: attributes on literal result elements
            if (name.startsWith("xsl:")) {
                String xslAttr = name.substring(4);
                if (!isKnownLREAttribute(xslAttr)) {
                    if (isElementForwardCompatible(ctx)) {
                        continue;
                    }
                    throw new SAXException("XTSE0805: Unknown XSLT attribute '" + name +
                        "' on literal result element");
                }
                continue;
            }
            
            try {
                StaticTypeContext typeCtx = buildStaticTypeContext(lreEffectiveVer);
                AttributeValueTemplate avt = AttributeValueTemplate.parse(value, this, typeCtx);
                if (lreBackwardsCompat) {
                    avt.setBackwardsCompatible(true);
                }
                avts.put(name, avt);
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
        
        // Collect attribute prefixes (these must always have namespace declarations)
        Set<String> attributePrefixes = new HashSet<>();
        for (String attrName : ctx.attributes.keySet()) {
            if (attrName.startsWith("xsl:")) continue;
            int colon = attrName.indexOf(':');
            if (colon > 0) {
                attributePrefixes.add(attrName.substring(0, colon));
            }
        }
        
        Map<String, String> outputNamespaces = new LinkedHashMap<>();
        for (Map.Entry<String, String> ns : ctx.namespaceBindings.entrySet()) {
            String nsPrefix = ns.getKey();
            String nsUri = ns.getValue();
            if (XSLT_NS.equals(nsUri)) {
                continue;
            }
            // Attribute prefixes must always be declared
            if (attributePrefixes.contains(nsPrefix)) {
                outputNamespaces.put(nsPrefix, nsUri);
                continue;
            }
            // Exclude if in excluded set (by URI)
            if (localExcludedURIs.contains(nsUri)) {
                continue;
            }
            outputNamespaces.put(nsPrefix, nsUri);
        }
        
        // Parse xsl:inherit-namespaces (XSLT 2.0+)
        boolean inheritNs = true;
        String inheritNsValue = ctx.attributes.get("xsl:inherit-namespaces");
        if (inheritNsValue != null && !inheritNsValue.isEmpty()) {
            String trimmed = inheritNsValue.trim();
            if ("yes".equals(trimmed) || "true".equals(trimmed) || "1".equals(trimmed)) {
                inheritNs = true;
            } else if ("no".equals(trimmed) || "false".equals(trimmed) || "0".equals(trimmed)) {
                inheritNs = false;
            } else {
                throw new SAXException("XTSE0020: xsl:inherit-namespaces must be 'yes' or 'no', got: "
                    + inheritNsValue);
            }
        }
        
        // xsl:type is rejected above (XTSE1660), so pass null for type info
        // on-empty/on-non-empty are now handled by SequenceNode's two-phase execution
        return new LiteralResultElement(ctx.namespaceURI, localName, prefix, 
            avts, outputNamespaces, useAttributeSets, null, null, content,
            null, null, inheritNs);
    }

    // ========================================================================
    // Top-level element processing
    // ========================================================================

    private void processStylesheetElement(ElementContext ctx) throws SAXException {
        // Parse version attribute - REQUIRED per XSLT spec (XTSE0010)
        // Check shadow attribute (_version="{AVT}") first, then regular attribute
        String versionAttr = resolveStaticShadowAttribute(ctx, "version");
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
            builder.setProcessorVersion(maxProcessorVersion);
        } catch (NumberFormatException e) {
            throw new SAXException("XTSE0110: Invalid version attribute value: " + versionAttr);
        }
        
        // xsl:package is an XSLT 3.0 element. If the declared version is < 3.0,
        // reject it since pre-3.0 processors don't recognize xsl:package.
        if ("package".equals(ctx.localName) && stylesheetVersion < 3.0) {
            throw new SAXException("XTSE0010: xsl:package is only allowed in XSLT 3.0 or later (version="
                + versionAttr + ")");
        }
        
        // XSLT 3.0 package attributes (only for xsl:package)
        if ("package".equals(ctx.localName)) {
            // name attribute - the package URI (optional but recommended)
            String nameAttr = ctx.attributes.get("name");
            if (nameAttr != null && !nameAttr.isEmpty()) {
                packageName = nameAttr.trim();
            }
            
            // package-version attribute (optional, defaults to "0.0" per spec)
            // Check shadow attribute (_package-version="{AVT}") first
            String versionAttrPkg;
            if (ctx.shadowAttributes.containsKey("package-version")) {
                try {
                    versionAttrPkg = resolveStaticShadowAttribute(ctx, "package-version");
                } catch (SAXException e) {
                    // Check root cause: NullPointerException means the shadow attribute
                    // uses functions not available in static context (e.g., doc(''))
                    Throwable root = e;
                    while (root.getCause() != null) {
                        root = root.getCause();
                    }
                    if (root instanceof NullPointerException) {
                        versionAttrPkg = ctx.attributes.get("package-version");
                    } else {
                        throw e;
                    }
                }
            } else {
                versionAttrPkg = ctx.attributes.get("package-version");
            }
            if (versionAttrPkg != null) {
                String trimmedPkgVer = versionAttrPkg.trim();
                if (trimmedPkgVer.isEmpty()) {
                    throw new SAXException("XTSE0020: package-version must not be empty");
                }
                validatePackageVersion(trimmedPkgVer);
                packageVersion = trimmedPkgVer;
            } else {
                packageVersion = "0.0";
            }
            
            // declared-modes attribute (XSLT 3.0)
            // When "yes" (default for xsl:package), all modes must be explicitly declared
            // with xsl:mode. Using an undeclared mode is XTSE3085.
            String declaredModesAttr = ctx.attributes.get("declared-modes");
            if (declaredModesAttr != null) {
                String trimmed = declaredModesAttr.trim();
                declaredModesEnabled = "yes".equals(trimmed) || "1".equals(trimmed)
                    || "true".equals(trimmed);
            } else {
                declaredModesEnabled = true;
            }
            
        }
        
        // XTSE0265: track input-type-annotations for cross-module conflict detection
        String inputTypeAnnotations = ctx.attributes.get("input-type-annotations");
        if (inputTypeAnnotations != null && !inputTypeAnnotations.isEmpty()
                && !"unspecified".equals(inputTypeAnnotations)) {
            builder.setInputTypeAnnotations(inputTypeAnnotations);
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
     * Checks if the current element is inside an xsl:override context.
     * xsl:override children (template, function, variable, etc.) should be
     * treated as top-level declarations for compilation purposes.
     */
    private boolean isInsideOverride() {
        for (ElementContext ancestor : elementStack) {
            if (XSLT_NS.equals(ancestor.namespaceURI)
                    && "override".equals(ancestor.localName)) {
                return true;
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
     * Validates that the given instruction is the last XSLT element child
     * in its parent's sequence constructor.
     */
    /**
     * Validates that xsl:break and xsl:next-iteration are the last instruction
     * in their parent's sequence constructor (XTSE3120).
     * Called after all children of an element have been compiled,
     * so the complete children list is available.
     */
    private void validateBreakNextIterationPosition(ElementContext ctx)
            throws SAXException {
        List<XSLTNode> children = ctx.children;
        if (children.isEmpty()) {
            return;
        }
        for (int i = 0; i < children.size(); i++) {
            XSLTNode child = children.get(i);
            boolean isBreak = (child instanceof BreakNode);
            boolean isNextIteration = (child instanceof NextIterationNode);
            if (!isBreak && !isNextIteration) {
                continue;
            }
            for (int j = i + 1; j < children.size(); j++) {
                XSLTNode next = children.get(j);
                if (next instanceof LiteralText) {
                    continue;
                }
                if (next instanceof FallbackNode) {
                    continue;
                }
                if (next instanceof CatchNode) {
                    continue;
                }
                if (next instanceof OnCompletionNode) {
                    continue;
                }
                String instruction = isBreak ?
                    "xsl:break" : "xsl:next-iteration";
                throw new SAXException("XTSE3120: " + instruction +
                    " must be the last instruction in its " +
                    "sequence constructor");
            }
        }
    }

    /**
     * Validates that the current element is a direct child of xsl:iterate.
     */
    private void validateDirectChildOfIterate(String instruction) throws SAXException {
        if (!elementStack.isEmpty()) {
            ElementContext parent = elementStack.peek();
            if (XSLT_NS.equals(parent.namespaceURI) && "iterate".equals(parent.localName)) {
                return;
            }
        }
        throw new SAXException("XTSE0010: " + instruction +
            " must be a direct child of xsl:iterate");
    }

    /**
     * Validates that the current element is lexically inside an xsl:iterate,
     * with only permitted intermediate elements (conditionals, etc.) in between.
     * Elements like xsl:for-each, xsl:for-each-group, literal result elements,
     * xsl:template, and xsl:function break the lexical scope.
     */
    private void validateLexicallyInIterate(String instruction) throws SAXException {
        for (ElementContext ancestor : elementStack) {
            if (XSLT_NS.equals(ancestor.namespaceURI)) {
                String name = ancestor.localName;
                if ("iterate".equals(name)) {
                    return;
                }
                if ("if".equals(name) || "choose".equals(name)
                    || "when".equals(name) || "otherwise".equals(name)
                    || "try".equals(name) || "catch".equals(name)
                    || "where-populated".equals(name)) {
                    continue;
                }
                throw new SAXException("XTSE3120: " + instruction +
                    " is not allowed here; it must be lexically within xsl:iterate");
            } else {
                throw new SAXException("XTSE3120: " + instruction +
                    " is not allowed inside a literal result element within xsl:iterate");
            }
        }
        throw new SAXException("XTSE3120: " + instruction +
            " is not allowed outside xsl:iterate");
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
        // In XSLT 1.0/2.0, imports must appear before other top-level elements.
        // XSLT 3.0 removed this restriction (section 3.10.2).
        if (!importsAllowed && stylesheetVersion < 3.0) {
            throw new SAXException("xsl:import must appear before all other " +
                "elements in the stylesheet (except other xsl:import elements)");
        }
        
        // XTSE0260: xsl:import must be empty
        validateEmptyElement(ctx, "xsl:import");
        
        String href = resolveStaticShadowAttribute(ctx, "href");
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
            CompiledStylesheet imported = resolver.resolve(href, getEffectiveBaseUri(), true, -1);
            if (imported != null) {
                // Track the minimum precedence from imported modules for apply-imports scoping
                for (TemplateRule tr : imported.getTemplateRules()) {
                    int prec = tr.getImportPrecedence();
                    if (minImportedPrecedence < 0 || prec < minImportedPrecedence) {
                        minImportedPrecedence = prec;
                    }
                }
                // XSLT 3.0 allows imports after other declarations. In that case,
                // the imported module has a higher counter value but should have
                // LOWER effective precedence. Defer replacement so finalizePrecedence
                // can promote the importing module's declarations to win.
                boolean lateImport = precedenceAssigned;
                builder.merge(imported, true, lateImport);
                // Propagate static variables from imported module into parent scope
                for (GlobalVariable gv : imported.getGlobalVariables()) {
                    if (gv.isStatic()) {
                        String ln = gv.getLocalName();
                        if (!staticVariables.containsKey(ln)) {
                            staticVariables.put(ln, gv.getStaticValue());
                        }
                        // Register for XTSE3450 conflict detection (only if not already
                        // registered at a higher precedence from the importing stylesheet)
                        if (!staticDeclarationInfo.containsKey(ln)) {
                            String selStr = gv.getStaticValue() != null
                                ? gv.getStaticValue().asString() : null;
                            staticDeclarationInfo.put(ln, new Object[]{
                                Boolean.valueOf(gv.isParam()), selStr,
                                Integer.valueOf(gv.getImportPrecedence())});
                        }
                    }
                }
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
        
        String href = resolveStaticShadowAttribute(ctx, "href");
        if (href == null || href.isEmpty()) {
            throw new SAXException("XTSE0010: xsl:include requires href attribute");
        }
        
        if (resolver == null) {
            throw new SAXException("xsl:include not supported: no StylesheetResolver configured");
        }
        
        try {
            // Share static variables with included module (XSLT 3.0 §3.8)
            resolver.setSharedStaticVariables(staticVariables);
            
            // For includes: compile the included stylesheet (which may have imports
            // that need lower precedence). The included stylesheet uses -1 to assign
            // its own precedence after its imports.
            CompiledStylesheet included = resolver.resolve(href, getEffectiveBaseUri(), false, -1);
            
            // Don't assign our precedence here - wait until getCompiledStylesheet()
            // to ensure our precedence is higher than ALL imports (including those
            // in subsequent includes).
            
            if (included != null) {
                // Merge the included stylesheet's templates, marking them for later
                // precedence update. Templates from the included stylesheet (not its
                // imports) will be updated to our precedence in getCompiledStylesheet().
                builder.mergeIncludePending(included);
                
                // XTSE0265: check for deferred input-type-annotations conflict
                String itaConflict = builder.getInputTypeAnnotationsConflict();
                if (itaConflict != null) {
                    throw new SAXException(itaConflict);
                }
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
        public void execute(TransformContext context, OutputHandler output) {
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
        // Collect the override declarations that were accumulated during
        // child element compilation (processTemplateElement checks
        // isInsideOverride() and adds to pendingOverrideDeclarations).
        List<OverrideDeclaration> declarations;
        if (pendingOverrideDeclarations != null && !pendingOverrideDeclarations.isEmpty()) {
            declarations = new ArrayList<>(pendingOverrideDeclarations);
            pendingOverrideDeclarations = null;
        } else {
            declarations = new ArrayList<>();
        }
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
        public void execute(TransformContext context, OutputHandler output) {
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
        
        // Ensure our own import precedence is assigned so we know the baseline
        ensurePrecedenceAssigned();
        
        // Compute precedence remapping for imported templates.
        // Templates from the used package must have lower import precedence
        // than the using stylesheet's own templates (including overrides),
        // so that overrides win over non-overridden templates when both match.
        int maxPkgPrec = 0;
        for (TemplateRule t : pkg.getStylesheet().getTemplateRules()) {
            int prec = t.getImportPrecedence();
            if (prec > maxPkgPrec) {
                maxPkgPrec = prec;
            }
        }
        int pkgPrecOffset = importPrecedence - maxPkgPrec - 1;
        
        // Create a set of overridden component keys for quick lookup
        Set<String> overriddenTemplates = new HashSet<String>();
        Set<String> overriddenFunctions = new HashSet<String>();
        Set<String> overriddenVariables = new HashSet<String>();
        Set<String> overriddenAttributeSets = new HashSet<String>();
        boolean addedXslOriginal = false;
        
        for (OverrideDeclaration override : overrides) {
            switch (override.getType()) {
                case TEMPLATE:
                    overriddenTemplates.add(override.getOriginalComponentKey());
                    // Override template already added by processTemplateElement
                    break;
                case FUNCTION:
                    overriddenFunctions.add(override.getOriginalComponentKey());
                    // Override function already added by processFunction
                    break;
                case VARIABLE:
                case PARAM:
                    overriddenVariables.add(override.getOriginalComponentKey());
                    // Override variable already added by processVariable
                    break;
                case ATTRIBUTE_SET:
                    overriddenAttributeSets.add(override.getOriginalComponentKey());
                    // Override attribute set already added by processAttributeSet
                    break;
            }
        }
        
        // Build a map of all templates in the package for override validation.
        // Index by both name and match-pattern+mode since overrides can
        // identify the base template by either mechanism.
        Map<String, TemplateRule> allNamedTemplates = new HashMap<String, TemplateRule>();
        for (TemplateRule t : pkg.getStylesheet().getTemplateRules()) {
            if (t.getName() != null) {
                allNamedTemplates.put(t.getName(), t);
            }
            if (t.getMatchPattern() != null) {
                String tMode = t.getMode() != null ? t.getMode() : "#default";
                String patternKey = t.getMatchPattern().toString() + "#" + tMode;
                allNamedTemplates.put(patternKey, t);
            }
        }
        
        // Validate overrides: XTSE3058 (match) and XTSE3060 (visibility).
        // Match-only templates that don't match are added as new templates
        // (per XSLT 3.0 spec section 3.6.6), not errors.
        Set<String> newOverrideTemplates = new HashSet<String>();
        if (!overriddenTemplates.isEmpty()) {
            for (String overriddenKey : overriddenTemplates) {
                TemplateRule orig = allNamedTemplates.get(overriddenKey);
                if (orig == null) {
                    // Named templates must match (XTSE3058); match-only
                    // templates that don't match are added as new templates
                    boolean isNamedKey = !overriddenKey.contains("#");
                    if (isNamedKey) {
                        throw new SAXException("XTSE3058: Override template '" +
                            overriddenKey +
                            "' does not match any component in package '" +
                            pkg.getPackageName() + "'");
                    }
                    newOverrideTemplates.add(overriddenKey);
                    continue;
                }
                String acceptName = orig.getName() != null
                        ? orig.getName() : overriddenKey;
                ComponentVisibility vis = getEffectiveTemplateVisibility(
                        pkg, orig, accepts, acceptName);
                if (!vis.isAccessible()) {
                    throw new SAXException("XTSE3060: Cannot override template '" +
                        overriddenKey + "' because its visibility is " + vis);
                }
                // XTSE3070: Check signature compatibility (return type)
                for (OverrideDeclaration od : overrides) {
                    if (od.getType() == OverrideDeclaration.OverrideType.TEMPLATE
                            && overriddenKey.equals(od.getOriginalComponentKey())) {
                        TemplateRule overrideRule = od.getOverrideTemplate();
                        if (overrideRule != null && orig.getAsType() != null
                                && overrideRule.getAsType() != null) {
                            String origAs = orig.getAsType().trim();
                            String overrideAs = overrideRule.getAsType().trim();
                            if (!origAs.equals(overrideAs)) {
                                throw new SAXException("XTSE3070: Override template '" +
                                    overriddenKey +
                                    "' has incompatible return type '" +
                                    overrideAs + "' (original is '" + origAs + "')");
                            }
                        }
                    }
                }
            }
        }
        // Remove new-addition templates from the overridden set so they
        // are not skipped during the import phase
        overriddenTemplates.removeAll(newOverrideTemplates);
        
        // XTSE3030/XTSE3040/XTSE3051: Validate accept declarations
        Set<String> allComponentNames = new HashSet<String>(allNamedTemplates.keySet());
        for (UserFunction f : pkg.getStylesheet().getUserFunctions().values()) {
            allComponentNames.add("{" + f.getNamespaceURI() + "}" + f.getLocalName());
        }
        for (ModeDeclaration m : pkg.getStylesheet().getModeDeclarations().values()) {
            String modeName = m.getName() != null ? m.getName() : "#default";
            allComponentNames.add(modeName);
        }
        
        for (AcceptDeclaration accept : accepts) {
            String namesAttr = accept.getNamesPattern();
            if (namesAttr == null || "*".equals(namesAttr.trim())) {
                continue;
            }
            String[] names = namesAttr.trim().split("\\s+");
            boolean anyMatched = false;
            for (String name : names) {
                if ("*".equals(name) || name.endsWith(":*")) {
                    anyMatched = true;
                    continue;
                }
                // *:localname — wildcard namespace, specific local name
                if (name.startsWith("*:")) {
                    String localPart = name.substring(2);
                    for (String compName : allComponentNames) {
                        int braceClose = compName.lastIndexOf('}');
                        String compLocal = braceClose >= 0
                                ? compName.substring(braceClose + 1)
                                : compName;
                        if (localPart.equals(compLocal)) {
                            anyMatched = true;
                            break;
                        }
                    }
                    continue;
                }
                boolean found = allComponentNames.contains(name);
                if (found) {
                    anyMatched = true;
                    // XTSE3040: Check visibility compatibility
                    TemplateRule tmpl = allNamedTemplates.get(name);
                    if (tmpl != null) {
                        ComponentVisibility origVis = tmpl.getVisibility();
                        ComponentVisibility acceptVis = accept.getVisibility();
                        if (origVis == ComponentVisibility.PRIVATE
                                && acceptVis != ComponentVisibility.HIDDEN) {
                            throw new SAXException("XTSE3040: Cannot accept private " +
                                "template '" + name + "' with visibility '" + acceptVis + "'");
                        }
                    }
                    // XTSE3051: Check if name matches an override in the same xsl:use-package
                    if (overriddenTemplates.contains(name)
                            || overriddenFunctions.contains(name)) {
                        throw new SAXException("XTSE3051: xsl:accept name '" + name +
                            "' matches a component declared in xsl:override");
                    }
                }
            }
            if (!anyMatched) {
                throw new SAXException("XTSE3030: xsl:accept matches no components " +
                    "in package '" + pkg.getPackageName() + "' with names '" + namesAttr + "'");
            }
        }

        // Import templates: iterate ALL templates from the package and
        // determine effective visibility using accepts then package defaults.
        // Imported templates get remapped import precedence so they are lower
        // than the using stylesheet's own templates (including overrides).
        CompiledStylesheet pkgStylesheet = pkg.getStylesheet();
        for (TemplateRule template : pkgStylesheet.getTemplateRules()) {
            String key = getTemplateKey(template);
            String acceptName = template.getName() != null
                    ? template.getName() : key;
            ComponentVisibility pkgVis = pkg.getTemplateVisibility(template);
            // Private components in the used package are not visible to the
            // using package and cannot be matched by xsl:accept (XTSE3040)
            if (!pkgVis.isAccessible()) {
                continue;
            }
            ComponentVisibility vis = getEffectiveTemplateVisibility(
                    pkg, template, accepts, acceptName);
            if (vis == ComponentVisibility.HIDDEN) {
                continue;
            }
            // Skip internal xsl:original templates from prior overrides
            String xslOriginal = "{" + XSLT_NS + "}original";
            if (xslOriginal.equals(template.getName())) {
                continue;
            }
            // Check if overridden by name, pattern+mode, or name+mode
            boolean isOverridden = overriddenTemplates.contains(key);
            if (!isOverridden && template.getMatchPattern() != null) {
                String tMode = template.getMode() != null
                        ? template.getMode() : "#default";
                String patternKey = template.getMatchPattern().toString()
                        + "#" + tMode;
                isOverridden = overriddenTemplates.contains(patternKey);
            }
            if (!isOverridden && template.getName() != null
                    && template.getMode() != null) {
                String nameMode = template.getName() + "#" + template.getMode();
                isOverridden = overriddenTemplates.contains(nameMode);
            }
            if (isOverridden) {
                String origName = "{" + XSLT_NS + "}original";
                boolean alreadyOriginal = origName.equals(template.getName());
                int remappedPrec = pkgPrecOffset + template.getImportPrecedence();
                if (!addedXslOriginal && !alreadyOriginal) {
                    // Keep as named xsl:original for xsl:call-template access
                    TemplateRule origTemplate = new TemplateRule(
                        null, origName,
                        template.getMode(), template.getPriority(),
                        remappedPrec,
                        template.getDeclarationIndex(),
                        template.getParameters(),
                        template.getBody(),
                        template.getAsType(),
                        template.getVisibility());
                    origTemplate.setEffectiveVersion(template.getEffectiveVersion());
                    origTemplate.setDefiningStylesheet(pkgStylesheet);
                    builder.addTemplateRule(origTemplate);
                    addedXslOriginal = true;
                }
                // Also keep the original with its match pattern at lower
                // precedence so xsl:next-match can find it
                if (template.getMatchPattern() != null) {
                    TemplateRule matchable = template.withImportPrecedence(remappedPrec)
                            .withVisibility(vis);
                    matchable.setDefiningStylesheet(pkgStylesheet);
                    builder.addTemplateRule(matchable);
                }
                continue;
            }
            if (vis != ComponentVisibility.HIDDEN) {
                int remappedPrec = pkgPrecOffset + template.getImportPrecedence();
                TemplateRule imported = template.withImportPrecedence(remappedPrec)
                        .withVisibility(vis);
                imported.setDefiningStylesheet(pkgStylesheet);
                builder.addTemplateRule(imported);
            }
        }
        
        // Import functions: iterate ALL functions, use accepts then package
        // visibility to determine effective visibility.
        // Set defining stylesheet so cross-package calls can resolve private deps.
        for (UserFunction function : pkgStylesheet.getUserFunctions().values()) {
            String key = function.getKey();
            if (overriddenFunctions.contains(key)) {
                continue;
            }
            String funcName = "{" + function.getNamespaceURI() + "}" + function.getLocalName();
            ComponentVisibility funcPkgVis = pkg.getFunctionVisibility(function);
            // Private components in the used package are not visible to the
            // using package and cannot be matched by xsl:accept (XTSE3040)
            if (!funcPkgVis.isAccessible()) {
                continue;
            }
            ComponentVisibility vis = funcPkgVis;
            for (AcceptDeclaration accept : accepts) {
                if (accept.matchesType(AcceptDeclaration.ComponentType.FUNCTION)
                        && accept.matchesName(funcName)) {
                    vis = accept.getVisibility();
                    break;
                }
            }
            if (vis == ComponentVisibility.HIDDEN) {
                continue;
            }
            try {
                UserFunction imported = function.withVisibility(vis);
                imported.setDefiningStylesheet(pkgStylesheet);
                builder.addUserFunction(imported);
            } catch (javax.xml.transform.TransformerConfigurationException e) {
                throw new SAXException(e.getMessage(), e);
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
     * Returns true if any xsl:accept declaration explicitly matches the
     * given component type and name. Used to distinguish "accepted as private"
     * (should be imported) from "inherently private" (should not be imported).
     */
    private boolean isExplicitlyAccepted(AcceptDeclaration.ComponentType type,
            String name, List<AcceptDeclaration> accepts) {
        for (AcceptDeclaration accept : accepts) {
            if (accept.matchesType(type) && accept.matchesName(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the effective visibility of a template for override validation.
     * Checks xsl:accept declarations first (which can elevate a private
     * component to public), falling back to the package's own visibility.
     */
    private ComponentVisibility getEffectiveTemplateVisibility(
            CompiledPackage pkg, TemplateRule template,
            List<AcceptDeclaration> accepts, String acceptName) {
        for (AcceptDeclaration accept : accepts) {
            if (accept.matchesType(AcceptDeclaration.ComponentType.TEMPLATE)
                    && accept.matchesName(acceptName)) {
                return accept.getVisibility();
            }
        }
        return pkg.getTemplateVisibility(template);
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
        
        inImportSchema = false;
        
        String namespace = ctx.attributes.get("namespace");
        String schemaLocation = ctx.attributes.get("schema-location");
        
        if (schemaLocation == null || schemaLocation.isEmpty()) {
            importSchemaNamespace = null;
            return;
        }
        
        try {
            String resolvedLocation = resolveUri(schemaLocation);
            XSDSchema schema = XSDSchemaParser.parse(resolvedLocation);
            
            if (namespace != null && !namespace.isEmpty()) {
                String schemaTargetNs = schema.getTargetNamespace();
                if (schemaTargetNs != null && !namespace.equals(schemaTargetNs)) {
                    throw new SAXException("Schema target namespace '" + schemaTargetNs + 
                        "' does not match declared namespace '" + namespace + "'");
                }
            }
            
            builder.addImportedSchema(schema);
            
        } catch (IOException e) {
            // Schema not found - silently continue (best effort for Basic processor)
        } catch (Exception e) {
            // Schema parse error - silently continue
        } finally {
            importSchemaNamespace = null;
        }
    }

    /**
     * Returns the effective base URI for resolving relative references.
     * During external entity expansion the SAX locator reports the
     * entity's system ID, which may differ from the stylesheet's own
     * base URI.  If the locator provides a system ID we use it;
     * otherwise we fall back to the static {@code baseUri}.
     */
    private String getEffectiveBaseUri() {
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
        String match = resolveStaticShadowAttribute(ctx, "match");
        String name = ctx.attributes.get("name");
        String mode = ctx.attributes.get("mode");
        String priorityStr = ctx.attributes.get("priority");
        String asType = ctx.attributes.get("as");  // XSLT 2.0+ return type
        
        // Pre-parse the 'as' type with namespace resolution while bindings are available
        SequenceType parsedAsType = null;
        if (asType != null && !asType.trim().isEmpty()) {
            final Map<String, String> nsBindings = ctx.namespaceBindings;
            parsedAsType = SequenceType.parse(asType.trim(), new java.util.function.Function<String, String>() {
                @Override
                public String apply(String prefix) {
                    return nsBindings.get(prefix);
                }
            });
        }
        
        // Track whether this template has a match pattern (for xsl:context-item validation)
        boolean savedHasMatch = currentTemplateHasMatch;
        currentTemplateHasMatch = (match != null);
        
        // XSLT 3.0: visibility attribute for package components
        // Default is PRIVATE within xsl:package, PUBLIC for a stylesheet.
        // Override templates inherit the original's visibility (public/abstract),
        // so they default to PUBLIC within xsl:override.
        String visibilityAttr = resolveStaticShadowAttribute(ctx, "visibility");
        ComponentVisibility visibility;
        if (isInsideOverride()) {
            visibility = ComponentVisibility.PUBLIC;
        } else if (packageName != null) {
            visibility = ComponentVisibility.PRIVATE;
        } else {
            visibility = ComponentVisibility.PUBLIC;
        }
        if (visibilityAttr != null && !visibilityAttr.isEmpty()) {
            try {
                visibility = ComponentVisibility.parse(visibilityAttr);
            } catch (IllegalArgumentException e) {
                throw new SAXException("XTSE0020: Invalid visibility value: " + visibilityAttr);
            }
        }
        
        // XSLT 3.0: if no explicit mode, use the effective default-mode
        // (from default-mode on this element or its ancestors)
        if (mode == null && match != null) {
            String effectiveDefault = ctx.defaultMode;
            if (effectiveDefault != null && !"#unnamed".equals(effectiveDefault)) {
                mode = effectiveDefault;
            }
        }
        
        // Per XSLT 3.0 spec 3.5.3.1: if no explicit visibility attribute,
        // inherit visibility from the declared mode
        if (visibilityAttr == null && packageName != null && !isInsideOverride()
                && match != null) {
            String effectiveMode = mode;
            if (effectiveMode == null) {
                effectiveMode = ctx.defaultMode;
            }
            if (effectiveMode != null) {
                ModeDeclaration modeDecl = builder.getModeDeclaration(effectiveMode);
                if (modeDecl != null) {
                    ComponentVisibility modeVis = modeDecl.getComponentVisibility();
                    if (modeVis != null) {
                        visibility = modeVis;
                    }
                }
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
                if (!"#all".equals(token)) {
                    usedModeNames.add(expandedToken != null ? expandedToken : "#default");
                }
                start = end;
            }
        } else {
            expandedModes.add(null);
            if (match != null) {
                usedModeNames.add("#default");
            }
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
        boolean foundContextItem = false;
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
                params.add(new TemplateParameter(pn.getNamespaceURI(), pn.getLocalName(), pn.getSelectExpr(), pn.getContent(), pn.isTunnel(), pn.isRequired(), pn.getAs()));
            } else if (child instanceof WithParamNode) {
                // XTSE0010: xsl:with-param not allowed directly in template
                throw new SAXException("XTSE0010: xsl:with-param is not allowed directly in xsl:template");
            } else if (child instanceof SortSpecNode) {
                // XTSE0010: xsl:sort not allowed directly in template
                throw new SAXException("XTSE0010: xsl:sort is not allowed directly in xsl:template");
            } else if (child instanceof ContextItemDeclaration) {
                // XTSE0010: xsl:context-item must be before params and body content
                if (foundNonParam || !params.isEmpty()) {
                    throw new SAXException("XTSE0010: xsl:context-item must appear before " +
                        "xsl:param and any other content in xsl:template");
                }
                if (foundContextItem) {
                    throw new SAXException("XTSE0010: Duplicate xsl:context-item in xsl:template");
                }
                foundContextItem = true;
                bodyNodes.add(child);
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
        
        XSLTNode body = new SequenceNode(bodyNodes);
        
        // Wrap body in collation scope if template has element-level default-collation
        if (ctx.defaultCollation != null) {
            String parentCollation = builder.getDefaultCollation();
            if (!ctx.defaultCollation.equals(parentCollation)) {
                body = new CollationScopeNode(ctx.defaultCollation, body);
            }
        }
        
        // Per-element effective version for backward compatibility mode
        double templateVersion = ctx.effectiveVersion > 0
            ? ctx.effectiveVersion : getEffectiveVersion();
        
        // Expand union patterns into separate template rules (per XSLT spec)
        // Each alternative gets its own default priority.
        // Only expand when no explicit priority is given — when explicit,
        // all alternatives share the same priority and are treated as one rule.
        boolean inOverride = isInsideOverride();
        if (inOverride && pendingOverrideDeclarations == null) {
            pendingOverrideDeclarations = new ArrayList<>();
        }
        
        // XTSE3440: Template rules in xsl:override must not use #all, #unnamed,
        // or #default (when default mode is the unnamed mode), or omit mode
        // (when the default mode is the unnamed mode).
        if (inOverride && match != null) {
            if (mode != null) {
                String trimmedMode = mode.trim();
                String[] modeTokens = trimmedMode.split("\\s+");
                for (String token : modeTokens) {
                    if ("#all".equals(token) || "#unnamed".equals(token)) {
                        throw new SAXException("XTSE3440: Template rule in xsl:override " +
                            "must not use mode='" + token + "'");
                    }
                    if ("#default".equals(token)) {
                        String effectiveDefault = ctx.defaultMode;
                        if (effectiveDefault == null || "#unnamed".equals(effectiveDefault)) {
                            throw new SAXException("XTSE3440: Template rule in xsl:override " +
                                "must not use mode='#default' when the default mode is unnamed");
                        }
                    }
                }
            } else {
                String effectiveDefault = ctx.defaultMode;
                if (effectiveDefault == null || "#unnamed".equals(effectiveDefault)) {
                    throw new SAXException("XTSE3440: Template rule in xsl:override " +
                        "must not omit the mode attribute when the default mode is unnamed");
                }
            }
        }

        if (pattern instanceof UnionPattern && priorityStr == null) {
            Pattern[] alternatives =
                ((UnionPattern) pattern).getAlternatives();
            for (String expandedMode : expandedModes) {
                for (int pi = 0; pi < alternatives.length; pi++) {
                    Pattern p = alternatives[pi];
                    double rulePriority = p.getDefaultPriority();
                    // Only first alternative gets the template name
                    String ruleName = (pi == 0) ? expandedName : null;
                    TemplateRule rule = new TemplateRule(p, ruleName,
                        expandedMode, rulePriority, importPrecedence,
                        nextTemplateIndex(), params, body, asType,
                        visibility);
                    rule.setParsedAsType(parsedAsType);
                    rule.setEffectiveVersion(templateVersion);
                    builder.addTemplateRule(rule);
                    if (inOverride && pi == 0) {
                        pendingOverrideDeclarations.add(
                            OverrideDeclaration.forTemplate(rule));
                    }
                }
            }
        } else {
            for (String expandedMode : expandedModes) {
                TemplateRule rule = new TemplateRule(pattern, expandedName,
                    expandedMode, priority, importPrecedence,
                    nextTemplateIndex(), params, body, asType, visibility);
                rule.setParsedAsType(parsedAsType);
                rule.setEffectiveVersion(templateVersion);
                builder.addTemplateRule(rule);
                if (inOverride) {
                    pendingOverrideDeclarations.add(
                        OverrideDeclaration.forTemplate(rule));
                }
            }
        }
        currentTemplateHasMatch = savedHasMatch;
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
                        "' cannot be used in the mode '" + mode + "'");
                }
                if (uri.isEmpty()) {
                    return localPart;
                }
                return "{" + uri + "}" + localPart;
            }
        }
        
        int colonPos = trimmed.indexOf(':');
        if (colonPos > 0) {
            String prefix = trimmed.substring(0, colonPos);
            String localName = trimmed.substring(colonPos + 1);
            String uri = resolve(prefix);
            if (uri != null && !uri.isEmpty()) {
                if (checkReserved && isReservedNamespace(uri)) {
                    throw new SAXException("XTSE0080: Reserved namespace '" + uri + 
                        "' cannot be used in the mode '" + mode + "'");
                }
                return "{" + uri + "}" + localName;
            }
            throw new SAXException("XTSE0280: Namespace prefix '" + prefix + "' is not declared");
        }
        
        return trimmed;
    }

    /**
     * Expands a QName to Clark notation {uri}localname for proper comparison.
     * Names like woo:a and hoo:a should be equal if both prefixes map to the same URI.
     */
    /**
     * Returns true if the given expression streamability is fully streamable
     * (MOTIONLESS or CONSUMING). GROUNDED and FREE_RANGING expressions are
     * not allowed in streamable contexts per XSLT 3.0 section 19.
     */
    private static boolean isFullyStreamable(
            StreamabilityAnalyzer.ExpressionStreamability streamability) {
        return streamability == StreamabilityAnalyzer.ExpressionStreamability.MOTIONLESS
            || streamability == StreamabilityAnalyzer.ExpressionStreamability.CONSUMING;
    }

    /**
     * Checks if an XPath expression string contains a descendant or
     * descendant-or-self axis (indicating a crawling/non-motionless expression).
     * Used for streamability analysis of xsl:merge-source select expressions.
     */
    private boolean containsDescendantAxis(String expr) {
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        int len = expr.length();
        for (int i = 0; i < len; i++) {
            char c = expr.charAt(i);
            if (c == '\'' && !inDoubleQuote) {
                inSingleQuote = !inSingleQuote;
            } else if (c == '"' && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote;
            } else if (!inSingleQuote && !inDoubleQuote) {
                if (c == '/' && i + 1 < len && expr.charAt(i + 1) == '/') {
                    return true;
                }
                if (c == 'd' && expr.startsWith("descendant::", i)) {
                    return true;
                }
                if (c == 'd' && expr.startsWith("descendant-or-self::", i)) {
                    return true;
                }
            }
        }
        return false;
    }

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
     * Returns a copy of children with xsl:fallback elements removed.
     * Per XSLT spec, xsl:fallback is only activated for unknown instructions.
     */
    private List<XSLTNode> filterFallback(List<XSLTNode> children) {
        List<XSLTNode> result = new ArrayList<>(children.size());
        for (XSLTNode child : children) {
            if (!(child instanceof FallbackNode)) {
                result.add(child);
            }
        }
        return result;
    }

    /**
     * Returns true if the named XSLT instruction has a restricted content model
     * where xsl:fallback is NOT permitted. These instructions only allow specific
     * child elements (not arbitrary sequence constructors).
     */
    private static boolean hasRestrictedContentModel(String localName) {
        switch (localName) {
            case "apply-imports":
            case "apply-templates":
            case "call-template":
            case "choose":
            case "stylesheet":
            case "transform":
            case "package":
            case "number":
            case "sort":
            case "merge":
            case "merge-source":
            case "accumulator":
                return true;
            default:
                return false;
        }
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
    private boolean isReservedNamespace(String uri) {
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

    private void processOutputElement(ElementContext ctx) throws SAXException {
        importsAllowed = false;
        
        String method = ctx.attributes.get("method");
        if (method != null) {
            method = method.trim();
            // XTSE1570: method must be xml, html, xhtml, text, or a valid prefixed QName
            if (!method.isEmpty()) {
                int colonIdx = method.indexOf(':');
                if (colonIdx < 0) {
                    if (!"xml".equals(method) && !"html".equals(method) &&
                            !"xhtml".equals(method) && !"text".equals(method) &&
                            !"json".equals(method) && !"adaptive".equals(method)) {
                        throw new SAXException("XTSE1570: Invalid output method '" + method +
                            "'. Must be xml, html, xhtml, text, or a prefixed QName");
                    }
                } else {
                    // Validate as a QName: must have exactly one colon, with non-empty parts
                    String prefix = method.substring(0, colonIdx);
                    String localPart = method.substring(colonIdx + 1);
                    if (prefix.isEmpty() || localPart.isEmpty() || localPart.contains(":")) {
                        throw new SAXException("XTSE1570: Invalid output method '" + method +
                            "'. Not a valid QName");
                    }
                }
            }
        }
        
        String rawName = ctx.attributes.get("name");
        String name = null;
        if (rawName != null && !rawName.isEmpty()) {
            name = expandQName(rawName.trim());
        }
        
        // XTSE0020: validate boolean attributes (must be yes/no/true/false/1/0)
        validateOutputBoolean(ctx, "byte-order-mark");
        validateOutputBoolean(ctx, "escape-uri-attributes");
        validateOutputBoolean(ctx, "include-content-type");
        validateOutputBoolean(ctx, "indent");
        validateOutputBoolean(ctx, "omit-xml-declaration");
        validateOutputBoolean(ctx, "undeclare-prefixes");
        
        // standalone accepts "yes"/"no"/"omit" (and "true"/"false"/"1"/"0" in 3.0)
        String standalone = ctx.attributes.get("standalone");
        if (standalone != null && !standalone.isEmpty()) {
            String trimmed = standalone.trim();
            if (!trimmed.isEmpty() && !"yes".equals(trimmed) && !"no".equals(trimmed) && !"omit".equals(trimmed)) {
                if (stylesheetVersion < 3.0 || (!"true".equals(trimmed) && !"false".equals(trimmed) &&
                        !"1".equals(trimmed) && !"0".equals(trimmed))) {
                    throw new SAXException("XTSE0020: Invalid value for standalone attribute on xsl:output: got '" +
                        standalone + "'");
                }
            }
        }
        
        // XTSE0020: html-version must be a valid number
        String htmlVersion = ctx.attributes.get("html-version");
        if (htmlVersion != null && !htmlVersion.isEmpty()) {
            String hv = htmlVersion.trim();
            if (!hv.isEmpty()) {
                try {
                    Double.parseDouble(hv);
                } catch (NumberFormatException e) {
                    throw new SAXException("XTSE0020: Invalid value for html-version on xsl:output: '" +
                        htmlVersion + "' is not a valid number");
                }
            }
        }
        
        // SEPM0016: doctype-public must contain only valid PubidChars
        String doctypePublic = ctx.attributes.get("doctype-public");
        if (doctypePublic != null && !doctypePublic.isEmpty()) {
            for (int i = 0; i < doctypePublic.length(); i++) {
                char c = doctypePublic.charAt(i);
                if (!isPubidChar(c)) {
                    throw new SAXException("SEPM0016: Invalid character in doctype-public: '" +
                        doctypePublic + "' (character '" + c + "' at position " + i + ")");
                }
            }
        }
        
        // XTSE1560: merge output attributes, detecting conflicts
        String[] attrNames = {"method", "version", "encoding", "indent",
            "omit-xml-declaration", "standalone", "doctype-public", "doctype-system",
            "media-type", "byte-order-mark", "normalization-form",
            "parameter-document", "html-version"};
        for (String attrName : attrNames) {
            String val = ctx.attributes.get(attrName);
            if (val != null) {
                builder.mergeOutputAttribute(name, attrName, val);
            }
        }
        // Ensure named output definitions are registered even with no standard attrs
        if (name != null && !name.isEmpty()) {
            builder.registerOutputDefinition(name);
        }
        
        // Build the actual output properties from merged attributes
        OutputProperties props = new OutputProperties();
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
        String useCharacterMaps = ctx.attributes.get("use-character-maps");
        if (useCharacterMaps != null && !useCharacterMaps.isEmpty()) {
            String[] mapNames = useCharacterMaps.split("\\s+");
            for (String mapName : mapNames) {
                if (!mapName.isEmpty()) {
                    String expandedMap = expandQName(mapName.trim());
                    props.addUseCharacterMap(expandedMap);
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
        // XTSE1205: xsl:key must not have both use attribute and content
        if (use != null && !ctx.children.isEmpty() && hasNonWhitespaceContent(ctx.children)) {
            throw new SAXException("XTSE1205: xsl:key must not have both a use attribute and content");
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
        XPathExpression useExpr = null;
        SequenceNode content = null;
        if (use != null) {
            useExpr = compileExpression(use);
        } else {
            content = new SequenceNode(ctx.children);
        }
        
        String compositeStr = ctx.attributes.get("composite");
        validateYesOrNo("xsl:key", "composite", compositeStr);
        boolean composite = "yes".equals(compositeStr) || "1".equals(compositeStr)
                || "true".equals(compositeStr);
        
        // Resolve effective collation: explicit attribute, then default-collation in scope
        String keyCollation = null;
        if (collation != null && !collation.isEmpty()) {
            keyCollation = collation;
        } else if (ctx.defaultCollation != null) {
            keyCollation = ctx.defaultCollation;
        } else if (builder.getDefaultCollation() != null) {
            keyCollation = builder.getDefaultCollation();
        }
        builder.addKeyDefinition(new KeyDefinition(keyName, pattern, useExpr, content,
                composite, keyCollation));
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
        
        if (name == null || name.isEmpty()) {
            throw new SAXException("XTSE0010: Required attribute 'name' is missing on xsl:attribute-set");
        }
        
        String streamableAttr = ctx.attributes.get("streamable");
        if (streamableAttr != null && !streamableAttr.isEmpty()) {
            validateYesOrNo("xsl:attribute-set", "streamable", streamableAttr);
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
        String streamableTrimmed = streamableAttr != null ? streamableAttr.trim() : null;
        boolean isStreamable = "yes".equals(streamableTrimmed) ||
            "true".equals(streamableTrimmed) || "1".equals(streamableTrimmed);

        // XTSE3430: If declared streamable, body must be motionless
        // (attribute sets have no streaming context to consume from)
        if (isStreamable) {
            StreamabilityAnalyzer attrAnalyzer = new StreamabilityAnalyzer();
            List<String> reasons = new ArrayList<String>();
            StreamabilityAnalyzer.ExpressionStreamability bodyClass =
                attrAnalyzer.analyzeNode(attrs, reasons);
            if (bodyClass != StreamabilityAnalyzer.ExpressionStreamability
                    .MOTIONLESS) {
                StringBuilder sb = new StringBuilder();
                sb.append("XTSE3430: Attribute set '");
                sb.append(name);
                sb.append("' is declared streamable but its body is not");
                if (!reasons.isEmpty()) {
                    sb.append(": ");
                    sb.append(reasons.get(0));
                }
                throw new SAXException(sb.toString());
            }
        }

        builder.addAttributeSet(new AttributeSet(expandedName, useSets,
                                                  attrs, ComponentVisibility.PUBLIC,
                                                  isStreamable));
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
        
        if (stylesheetPrefix == null) {
            throw new SAXException("XTSE0010: xsl:namespace-alias requires stylesheet-prefix attribute");
        }
        if (resultPrefix == null) {
            throw new SAXException("XTSE0010: xsl:namespace-alias requires result-prefix attribute");
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
        
        // XTSE0810: conflict detection - only raise for same import precedence
        ensurePrecedenceAssigned();
        CompiledStylesheet.NamespaceAlias existing = builder.getNamespaceAlias(stylesheetUri);
        if (existing != null && !existing.resultUri.equals(resultUri)) {
            if (existing.importPrecedence == importPrecedence) {
                throw new SAXException("XTSE0810: Conflicting namespace-alias " +
                    "declarations for namespace '" + stylesheetUri +
                    "': result URIs '" + existing.resultUri + "' and '" + resultUri + "'");
            }
            if (existing.importPrecedence < importPrecedence) {
                builder.addNamespaceAlias(stylesheetUri, resultUri, resultPrefix, importPrecedence);
            }
        } else {
            builder.addNamespaceAlias(stylesheetUri, resultUri, resultPrefix, importPrecedence);
        }
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
        
        String select = resolveStaticShadowAttribute(ctx, "select");
        String staticAttr = ctx.attributes.get("static");
        String asType = ctx.attributes.get("as"); // XSLT 2.0 type annotation
        String visibilityAttr = ctx.attributes.get("visibility");
        validateYesOrNo("xsl:variable", "static", staticAttr);
        
        // Parse QName with resolved namespace
        // XTSE0080: Check for reserved namespace
        QName varName = parseQName(name, ctx.namespaceBindings, true);
        
        // Check for static variable (XSLT 3.0)
        boolean isStatic = isStaticValue(staticAttr);
        
        // XTSE0020: static="yes" combined with visibility is not allowed
        if (isStatic && visibilityAttr != null) {
            throw new SAXException("XTSE0020: static='yes' must not be combined " +
                "with visibility attribute on xsl:variable");
        }
        
        if (isStatic && isTopLevel) {
            // XTSE0620: static variable must not have both select and content
            if (select != null && !ctx.children.isEmpty() && hasNonWhitespaceContent(ctx.children)) {
                throw new SAXException("XTSE0620: static xsl:variable must not have both " +
                    "a select attribute and non-empty content");
            }
            // Static variable: evaluate at compile time and store for use-when
            // Use the element's base URI for static-base-uri()
            XPathValue staticValue = evaluateStaticExpression(select, varName.getLocalName(), ctx.baseURI);
            // XTSE3450: check for conflicting static declarations across import precedences
            checkStaticDeclarationConflict(varName.getLocalName(), false, 
                staticValue != null ? staticValue.asString() : null, importPrecedence);
            staticVariables.put(varName.getLocalName(), staticValue);
            // Add as global variable with pre-computed static value
            try {
                builder.addGlobalVariable(new GlobalVariable(varName, false, staticValue, importPrecedence));
            } catch (javax.xml.transform.TransformerConfigurationException e) {
                throw new SAXException(e.getMessage(), e);
            }
            return null;
        }
        
        validateFunctionTypeAs(asType);
        
        XPathExpression selectExpr = select != null ? compileExpression(select) : null;
        SequenceNode content = ctx.children.isEmpty() ? null : new SequenceNode(ctx.children);
        
        // XTSE0620: must not have both select attribute and non-empty content
        if (selectExpr != null && content != null && hasNonWhitespaceContent(ctx.children)) {
            throw new SAXException("XTSE0620: xsl:variable must not have both " +
                "a select attribute and non-empty content");
        }
        
        if (isTopLevel) {
            if (selectExpr != null) {
                checkSelfReference(selectExpr, varName.getLocalName());
            }
            try {
                builder.addGlobalVariable(new GlobalVariable(varName, false, selectExpr, content, 
                    importPrecedence, asType, ComponentVisibility.PUBLIC, false, ctx.baseURI));
            } catch (javax.xml.transform.TransformerConfigurationException e) {
                throw new SAXException(e.getMessage(), e);
            }
            return null;
        }
        
        // Pre-parse 'as' type with namespace resolution while bindings are available
        SequenceType parsedAsType = null;
        if (asType != null && !asType.trim().isEmpty()) {
            final Map<String, String> nsBindings = ctx.namespaceBindings;
            parsedAsType = SequenceType.parse(asType.trim(), new java.util.function.Function<String, String>() {
                @Override
                public String apply(String prefix) {
                    return nsBindings.get(prefix);
                }
            });
        }
        return new VariableNode(varName.getURI(), varName.getLocalName(), selectExpr, content, asType, parsedAsType);
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
        validateYesOrNo("xsl:param", "tunnel", tunnelAttr);
        validateYesOrNo("xsl:param", "required", requiredAttr);
        validateYesOrNo("xsl:param", "static", staticAttr);
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
        
        // XTSE0090: required attribute on function params not allowed in XSLT 2.0
        if (requiredAttr != null && maxProcessorVersion < 3.0) {
            ElementContext parent = elementStack.isEmpty() ? null : elementStack.peek();
            if (parent != null && XSLT_NS.equals(parent.namespaceURI)
                    && "function".equals(parent.localName)) {
                throw new SAXException("XTSE0090: required attribute is not allowed " +
                    "on xsl:param inside xsl:function in XSLT " + maxProcessorVersion);
            }
        }
        
        // Function params are always required; required='no' is not allowed (XSLT 3.0)
        if (requiredAttr != null && !required && stylesheetVersion >= 3.0) {
            ElementContext parent = elementStack.isEmpty() ? null : elementStack.peek();
            if (parent != null && XSLT_NS.equals(parent.namespaceURI)
                    && "function".equals(parent.localName)) {
                throw new SAXException("XTSE0020: required='no' " +
                    "is not allowed on xsl:param inside xsl:function");
            }
        }
        
        // XTSE0010: required param must not have select attribute or content
        if (required && select != null) {
            throw new SAXException("XTSE0010: A required parameter must not have a select attribute: " + name);
        }
        if (required && !ctx.children.isEmpty() && hasNonWhitespaceContent(ctx.children)) {
            throw new SAXException("XTSE0010: A required parameter must not have content: " + name);
        }
        
        // XTSE0010: static param must not have content (sequence constructor)
        boolean isStaticCheck = isStaticValue(staticAttr);
        if (isStaticCheck && !ctx.children.isEmpty() && hasNonWhitespaceContent(ctx.children)) {
            throw new SAXException("XTSE0010: A static parameter must not have content (use select attribute): " + name);
        }
        
        // XTSE0020: static="yes" is only allowed on global params
        if (isStaticCheck && !isTopLevel) {
            throw new SAXException("XTSE0020: static='yes' is not allowed on " +
                "a non-global parameter: " + name);
        }
        
        // Parse QName with resolved namespace
        // XTSE0080: Check for reserved namespace
        QName paramName = parseQName(name, ctx.namespaceBindings, true);
        
        // Check for static param (XSLT 3.0)
        boolean isStatic = isStaticValue(staticAttr);
        
        if (isStatic && isTopLevel) {
            // Static param: evaluate at compile time and store for use-when
            // Check for external override first
            String overrideSelect = staticParameterOverrides.get(paramName.getLocalName());
            if (overrideSelect == null) {
                overrideSelect = staticParameterOverrides.get(name);
            }
            String effectiveSelect = overrideSelect != null ? overrideSelect : select;
            // XTDE0050: required static param must have a value
            if (required && effectiveSelect == null) {
                throw new SAXException("XTDE0050: Required static parameter $" +
                    paramName.getLocalName() + " has no value");
            }
            // XTDE0700: mandatory type with no value (empty sequence doesn't match)
            if (effectiveSelect == null && asType != null) {
                String trimmedType = asType.trim();
                boolean optional = trimmedType.endsWith("?") || trimmedType.endsWith("*");
                if (!optional) {
                    throw new SAXException("XTDE0700: Static parameter $" +
                        paramName.getLocalName() + " has type " + asType +
                        " but no value was supplied");
                }
            }
            XPathValue staticValue = evaluateStaticExpression(effectiveSelect,
                    paramName.getLocalName(), ctx.baseURI);
            // XTSE3450: check for conflicting static declarations across import precedences
            checkStaticDeclarationConflict(paramName.getLocalName(), true,
                staticValue != null ? staticValue.asString() : null, importPrecedence);
            staticVariables.put(paramName.getLocalName(), staticValue);
            // Add as global variable with pre-computed static value
            try {
                builder.addGlobalVariable(new GlobalVariable(paramName, true, staticValue, importPrecedence));
            } catch (javax.xml.transform.TransformerConfigurationException e) {
                throw new SAXException(e.getMessage(), e);
            }
            return null;
        }
        
        validateFunctionTypeAs(asType);
        
        XPathExpression selectExpr = select != null ? compileExpression(select) : null;
        SequenceNode content = ctx.children.isEmpty() ? null : new SequenceNode(ctx.children);
        
        if (isTopLevel) {
            // Top-level param is a global variable that can be set externally
            // XTDE0050: A required parameter must have a value supplied during transformation
            try {
                builder.addGlobalVariable(new GlobalVariable(paramName, true, selectExpr, content, 
                    importPrecedence, asType, ComponentVisibility.PUBLIC, required, ctx.baseURI));
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
    private String resolveStaticShadowAttribute(ElementContext ctx, String attrName) 
            throws SAXException {
        String shadowValue = ctx.shadowAttributes.get(attrName);
        if (shadowValue == null) {
            return ctx.attributes.get(attrName);
        }
        StringBuilder result = new StringBuilder();
        int len = shadowValue.length();
        int i = 0;
        while (i < len) {
            int braceStart = shadowValue.indexOf('{', i);
            if (braceStart < 0) {
                result.append(shadowValue.substring(i));
                break;
            }
            if (braceStart + 1 < len && shadowValue.charAt(braceStart + 1) == '{') {
                result.append(shadowValue.substring(i, braceStart + 1));
                i = braceStart + 2;
                continue;
            }
            result.append(shadowValue.substring(i, braceStart));
            int braceEnd = shadowValue.indexOf('}', braceStart + 1);
            if (braceEnd < 0) {
                throw new SAXException("Unterminated AVT in shadow attribute _" + attrName);
            }
            String expr = shadowValue.substring(braceStart + 1, braceEnd);
            XPathValue val = evaluateStaticExpression(expr, attrName, null);
            result.append(val.asString());
            i = braceEnd + 1;
        }
        return result.toString();
    }

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
     * Checks for XTSE3450: conflicting static variable/parameter declarations
     * across import precedences.
     *
     * <p>When a higher-precedence declaration arrives for a name already registered
     * at lower precedence, the declarations must be consistent (same kind and value).
     * When a lower-precedence declaration arrives after a higher-precedence one,
     * it is silently ignored.
     */
    private void checkStaticDeclarationConflict(String localName, boolean isParam,
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
     * Validates a package-version string against the XSLT 3.0 grammar:
     * PackageVersion ::= NumericVersion NamePart?
     * NumericVersion ::= DecimalDigits ('.' DecimalDigits)*
     * NamePart       ::= '-' NCName ('-' NCName)*
     * DecimalDigits  ::= [0-9]+
     */
    private void validatePackageVersion(String version) throws SAXException {
        int len = version.length();
        int i = 0;
        
        // Must start with a digit
        if (i >= len || version.charAt(i) < '0' || version.charAt(i) > '9') {
            throw new SAXException("XTSE0020: Invalid package-version '" + version + 
                "': must start with a digit");
        }
        
        // Parse first group of digits
        while (i < len && version.charAt(i) >= '0' && version.charAt(i) <= '9') {
            i++;
        }
        
        // Parse additional '.DecimalDigits' groups
        while (i < len && version.charAt(i) == '.') {
            i++;
            if (i >= len || version.charAt(i) < '0' || version.charAt(i) > '9') {
                throw new SAXException("XTSE0020: Invalid package-version '" + version + 
                    "': digit expected after '.'");
            }
            while (i < len && version.charAt(i) >= '0' && version.charAt(i) <= '9') {
                i++;
            }
        }
        
        // Optional NamePart: '-' NCName ('-' NCName)*
        while (i < len && version.charAt(i) == '-') {
            i++;
            if (i >= len) {
                throw new SAXException("XTSE0020: Invalid package-version '" + version + 
                    "': name expected after '-'");
            }
            int cp = Character.codePointAt(version, i);
            if (!isNCNameStartCodePoint(cp)) {
                throw new SAXException("XTSE0020: Invalid package-version '" + version + 
                    "': invalid name start character after '-'");
            }
            i += Character.charCount(cp);
            while (i < len) {
                cp = Character.codePointAt(version, i);
                if (cp == '-') {
                    break;
                }
                if (!isNCNameCodePoint(cp)) {
                    throw new SAXException("XTSE0020: Invalid package-version '" + version + 
                        "': invalid character in name part");
                }
                i += Character.charCount(cp);
            }
        }
        
        if (i < len) {
            throw new SAXException("XTSE0020: Invalid package-version '" + version + 
                "': unexpected character at position " + i);
        }
    }
    
    /**
     * Tests whether a Unicode code point is a valid NCName start character.
     * NCNameStartChar per XML Namespaces spec (NameStartChar minus ':').
     */
    private static boolean isNCNameStartCodePoint(int cp) {
        return (cp >= 'A' && cp <= 'Z') || cp == '_' || (cp >= 'a' && cp <= 'z') ||
               (cp >= 0xC0 && cp <= 0xD6) || (cp >= 0xD8 && cp <= 0xF6) ||
               (cp >= 0xF8 && cp <= 0x2FF) || (cp >= 0x370 && cp <= 0x37D) ||
               (cp >= 0x37F && cp <= 0x1FFF) || (cp >= 0x200C && cp <= 0x200D) ||
               (cp >= 0x2070 && cp <= 0x218F) || (cp >= 0x2C00 && cp <= 0x2FEF) ||
               (cp >= 0x3001 && cp <= 0xD7FF) || (cp >= 0xF900 && cp <= 0xFDCF) ||
               (cp >= 0xFDF0 && cp <= 0xFFFD) || (cp >= 0x10000 && cp <= 0xEFFFF);
    }
    
    /**
     * Tests whether a Unicode code point is a valid NCName character.
     * NCNameChar per XML Namespaces spec (NameChar minus ':').
     */
    private static boolean isNCNameCodePoint(int cp) {
        return isNCNameStartCodePoint(cp) || cp == '-' || cp == '.' ||
               (cp >= '0' && cp <= '9') || cp == 0xB7 ||
               (cp >= 0x0300 && cp <= 0x036F) || (cp >= 0x203F && cp <= 0x2040);
    }
    
    /**
     * Validates that a string is a valid xs:decimal (digits with optional
     * decimal point, optional leading sign, no exponent notation).
     */
    private static boolean isValidXsDecimal(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        int start = 0;
        if (s.charAt(0) == '+' || s.charAt(0) == '-') {
            start = 1;
        }
        if (start >= s.length()) {
            return false;
        }
        boolean hasDigit = false;
        boolean hasDot = false;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') {
                hasDigit = true;
            } else if (c == '.' && !hasDot) {
                hasDot = true;
            } else {
                return false;
            }
        }
        return hasDigit;
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
        String doeAttr = ctx.attributes.get("disable-output-escaping");
        validateYesOrNo("xsl:value-of", "disable-output-escaping", doeAttr);
        boolean disableEscaping = "yes".equals(doeAttr);
        // XSLT 2.0+ separator attribute (AVT, default is single space for sequences)
        String separatorStr = ctx.attributes.get("separator");
        AttributeValueTemplate separatorAvt = null;
        if (separatorStr != null) {
            try {
                separatorAvt = AttributeValueTemplate.parse(separatorStr, this,
                        buildStaticTypeContext());
            } catch (XPathSyntaxException e) {
                throw new SAXException("Invalid AVT in separator: " + e.getMessage(), e);
            }
        }
        // Element-level effective version controls runtime behavior (BC mode):
        // version="1.0" means take only first item from xsl:value-of.
        // Stylesheet-level version controls syntax: content is allowed in 2.0+.
        double effectiveVer = ctx.effectiveVersion > 0 ? ctx.effectiveVersion : getEffectiveVersion();
        boolean xslt2Plus = effectiveVer >= 2.0;
        boolean stylesheetIs2Plus = stylesheetVersion >= 2.0;
        
        // XSLT 3.0: shadow attribute _select takes precedence
        if (ctx.hasShadowAttribute("select")) {
            String shadowSelect = ctx.shadowAttributes.get("select");
            AttributeValueTemplate selectAvt = parseAvt(shadowSelect);
            return new DynamicValueOfNode(
                selectAvt, disableEscaping, separatorStr);
        }
        
        if (select != null) {
            if (!ctx.children.isEmpty() && hasNonWhitespaceContent(ctx.children)) {
                throw new SAXException("XTSE0870: xsl:value-of must not have both " +
                    "a select attribute and content");
            }
            return new ValueOfNode(compileExpression(select), disableEscaping,
                separatorAvt, xslt2Plus);
        }
        
        // XSLT 2.0+ allows content (sequence constructor) instead of select attribute.
        // Use stylesheet version for syntax: version="1.0" on the element enables
        // BC mode behavior but does not prevent using 2.0+ syntax features.
        if (stylesheetIs2Plus && !ctx.children.isEmpty()) {
            List<XSLTNode> contentNodes = filterFallback(ctx.children);
            if (!contentNodes.isEmpty()) {
                XSLTNode content = contentNodes.size() == 1 ? contentNodes.get(0) 
                    : new SequenceNode(contentNodes);
                return new ValueOfContentNode(content, disableEscaping, separatorStr);
            }
        }
        
        // Also allow in forward-compatible mode
        if (forwardCompatible && !ctx.children.isEmpty()) {
            List<XSLTNode> contentNodes = filterFallback(ctx.children);
            if (!contentNodes.isEmpty()) {
                XSLTNode content = contentNodes.size() == 1 ? contentNodes.get(0) 
                    : new SequenceNode(contentNodes);
                return new ValueOfContentNode(content, disableEscaping, separatorStr);
            }
        }

        // XSLT 2.0 and earlier: xsl:value-of requires select or content
        if (maxProcessorVersion < 3.0) {
            throw new SAXException("XTSE0870: xsl:value-of must have either " +
                "a select attribute or a sequence constructor");
        }
        // XSLT 3.0: empty xsl:value-of with no select and no content produces empty string
        return new ValueOfNode(compileExpression("''"), disableEscaping,
            separatorAvt, true);
    }

    private XSLTNode compileText(ElementContext ctx) throws SAXException {
        String doeTextAttr = ctx.attributes.get("disable-output-escaping");
        validateYesOrNo("xsl:text", "disable-output-escaping", doeTextAttr);
        boolean disableEscaping = "yes".equals(doeTextAttr);

        // XSLT 3.0: when expand-text is active, xsl:text content may contain
        // TVT nodes (ValueOfNode) alongside LiteralText.
        // Any other instruction type inside xsl:text is a static error (XTSE0010).
        boolean hasTVT = false;
        for (XSLTNode child : ctx.children) {
            if (child instanceof LiteralText) {
                continue;
            }
            if (child instanceof ValueOfNode) {
                hasTVT = true;
                continue;
            }
            // SequenceNode wrapping TVT parts (literal + expression + literal)
            if (child instanceof SequenceNode) {
                hasTVT = true;
                continue;
            }
            // Any other instruction type inside xsl:text is not allowed
            throw new SAXException("XTSE0010: The content of xsl:text must be text only; " +
                "XSLT instructions are not allowed inside xsl:text");
        }

        if (hasTVT) {
            // xsl:text with TVT content always produces a text node, even if empty.
            // Wrap in ValueOfContentNode to ensure characters() is called.
            XSLTNode contentNode;
            if (ctx.children.size() == 1) {
                contentNode = ctx.children.get(0);
            } else {
                contentNode = new SequenceNode(new ArrayList<>(ctx.children));
            }
            return new ValueOfContentNode(contentNode, disableEscaping, null);
        }

        StringBuilder text = new StringBuilder();
        for (XSLTNode child : ctx.children) {
            LiteralText lt = (LiteralText) child;
            // XTSE0010: Nested xsl:text is not allowed in XSLT 2.0+
            if (lt.isFromXslText() && stylesheetVersion >= 2.0) {
                throw new SAXException("XTSE0010: Nested xsl:text elements are not allowed");
            }
            text.append(lt.getText());
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
        String inheritNsValue = ctx.attributes.get("inherit-namespaces");
        
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
        
        // XTSE1660: type attribute requires a schema-aware processor
        if (typeValue != null && !typeValue.isEmpty()) {
            throw new SAXException("XTSE1660: xsl:element/@type requires a schema-aware processor");
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
        
        // Parse inherit-namespaces (XSLT 2.0+)
        boolean inheritNs = true;
        if (inheritNsValue != null && !inheritNsValue.isEmpty()) {
            String trimmed = inheritNsValue.trim();
            if ("yes".equals(trimmed) || "true".equals(trimmed) || "1".equals(trimmed)) {
                inheritNs = true;
            } else if ("no".equals(trimmed) || "false".equals(trimmed) || "0".equals(trimmed)) {
                inheritNs = false;
            } else {
                throw new SAXException("XTSE0020: inherit-namespaces must be 'yes' or 'no', got: "
                    + inheritNsValue);
            }
        }
        
        // Keep xsl:on-empty and xsl:on-non-empty in content - handled by SequenceNode's two-phase execution
        return new ElementNode(nameAvt, nsAvt, useAttrSets, new SequenceNode(ctx.children), 
                               defaultNs, nsBindings, typeNamespaceURI, typeLocalName, validation,
                               null, null, inheritNs);
    }

    private XSLTNode compileAttribute(ElementContext ctx) throws SAXException {
        String name = ctx.attributes.get("name");
        if (name == null || name.isEmpty()) {
            throw new SAXException("XTSE0010: Required attribute 'name' is missing on xsl:attribute");
        }
        String namespace = ctx.attributes.get("namespace");
        String select = ctx.attributes.get("select");
        String separatorRaw = ctx.attributes.get("separator");
        AttributeValueTemplate separatorAttrAvt = null;
        if (separatorRaw != null) {
            try {
                separatorAttrAvt = AttributeValueTemplate.parse(separatorRaw, this,
                        buildStaticTypeContext());
            } catch (XPathSyntaxException e) {
                throw new SAXException("Invalid AVT in separator: " + e.getMessage(), e);
            }
        }
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
        
        return new AttributeNode(nameAvt, nsAvt, selectExpr, separatorAttrAvt, content, nsBindings,
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
        XPathExpression selectExpr = select != null ? compileExpression(select) : null;
        SequenceNode content = ctx.children.isEmpty() ? null : new SequenceNode(ctx.children);
        return new CommentNode(selectExpr, content);
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
        // XTTE0945: xsl:copy without select requires a context item.
        // Inside xsl:function there is no context item, so this is a static error
        // unless a context-setting instruction (for-each, for-each-group, iterate)
        // appears between the copy and the function.
        String selectValue = ctx.attributes.get("select");
        if (selectValue == null) {
            for (ElementContext ancestor : elementStack) {
                String ancestorLocal = ancestor.localName;
                if (!XSLT_NS.equals(ancestor.namespaceURI)) {
                    continue;
                }
                if ("for-each".equals(ancestorLocal) ||
                        "for-each-group".equals(ancestorLocal) ||
                        "iterate".equals(ancestorLocal)) {
                    break;
                }
                if ("function".equals(ancestorLocal)) {
                    throw new SAXException("XTTE0945: xsl:copy with no select attribute " +
                        "is not allowed inside xsl:function (no context item)");
                }
            }
        }
        
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
        String copyAccumulatorsAttr = resolveStaticShadowAttribute(ctx, "copy-accumulators");
        
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
        
        boolean copyAccumulators = "yes".equals(copyAccumulatorsAttr)
                || "true".equals(copyAccumulatorsAttr)
                || "1".equals(copyAccumulatorsAttr);

        return new CopyOfNode(compileExpression(select), typeNamespaceURI, typeLocalName, 
                              validation, copyNs, copyNamespacesAvt, copyAccumulators);
    }

    private XSLTNode compileSequence(ElementContext ctx) throws SAXException {
        String select = ctx.attributes.get("select");
        // xsl:sequence exists in both 2.0 and 3.0 but with different rules.
        // A 3.0 processor allows content and optional select even for
        // version="2.0" stylesheets. Only enforce 2.0 rules when the
        // processor version is explicitly constrained to < 3.0.
        boolean xslt3Plus = maxProcessorVersion >= 3.0;
        if (select != null) {
            if (!ctx.children.isEmpty() && hasNonFallbackContent(ctx.children)) {
                throw new SAXException("XTSE3185: xsl:sequence must not have both " +
                    "a select attribute and children other than xsl:fallback");
            }
            return new SequenceOutputNode(compileExpression(select));
        } else if (xslt3Plus) {
            // XSLT 3.0: xsl:sequence without select — content is a sequence constructor
            return new SequenceNode(new ArrayList<>(ctx.children));
        } else {
            // XSLT 2.0: select attribute is required
            boolean hasContent = !ctx.children.isEmpty() && hasNonFallbackContent(ctx.children);
            if (hasContent) {
                throw new SAXException("XTSE0010: xsl:sequence must not have content " +
                    "in XSLT 2.0 (select attribute is required)");
            }
            throw new SAXException("XTSE0010: xsl:sequence requires a select attribute " +
                "in XSLT 2.0");
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
        
        // Track mode usage for declared-modes validation (skip #current since it's runtime)
        if (mode != null && !"#current".equals(mode)) {
            usedModeNames.add(expandedMode != null ? expandedMode : "#default");
        } else if (mode == null) {
            usedModeNames.add("#default");
        }
        
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
        
        validateSortStable(sorts);
        boolean backCompat = ctx.effectiveVersion > 0
            && ctx.effectiveVersion < 2.0;
        return new ApplyTemplatesNode(selectExpr, expandedMode, sorts, params,
                                      backCompat);
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
        // XTSE3460: xsl:apply-imports is not allowed in a template within xsl:override
        if (isInsideOverride()) {
            throw new SAXException("XTSE3460: xsl:apply-imports is not allowed in a " +
                "template declared within xsl:override (use xsl:next-match instead)");
        }
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
        
        validateSortStable(sorts);
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

    /**
     * Recursively collects all attribute set names referenced via
     * use-attribute-sets in the given node tree.
     */
    private static void collectAttributeSetReferences(
            XSLTNode node, List<String> refs, int depth) {
        if (node == null || depth > 50) {
            return;
        }

        if (node instanceof CopyNode) {
            String uas = ((CopyNode) node).getUseAttributeSetsString();
            if (uas != null && !uas.isEmpty()) {
                StringTokenizer st = new StringTokenizer(uas);
                while (st.hasMoreTokens()) {
                    refs.add(st.nextToken());
                }
            }
            collectAttributeSetReferences(
                ((CopyNode) node).getContent(), refs, depth + 1);
        }
        if (node instanceof ElementNode) {
            String uas = ((ElementNode) node).getUseAttributeSetsString();
            if (uas != null && !uas.isEmpty()) {
                StringTokenizer st = new StringTokenizer(uas);
                while (st.hasMoreTokens()) {
                    refs.add(st.nextToken());
                }
            }
            collectAttributeSetReferences(
                ((ElementNode) node).getContent(), refs, depth + 1);
        }
        if (node instanceof LiteralResultElement) {
            List<String> uas =
                ((LiteralResultElement) node).getUseAttributeSets();
            if (uas != null) {
                refs.addAll(uas);
            }
            collectAttributeSetReferences(
                ((LiteralResultElement) node).getContent(), refs,
                depth + 1);
        }
        if (node instanceof SequenceNode) {
            SequenceNode seq = (SequenceNode) node;
            List<XSLTNode> children = seq.getChildren();
            if (children != null) {
                for (int i = 0; i < children.size(); i++) {
                    collectAttributeSetReferences(
                        children.get(i), refs, depth + 1);
                }
            }
        }
        if (node instanceof ForEachNode) {
            collectAttributeSetReferences(
                ((ForEachNode) node).getBody(), refs, depth + 1);
        }
        if (node instanceof ForEachGroupNode) {
            collectAttributeSetReferences(
                ((ForEachGroupNode) node).getBody(), refs, depth + 1);
        }
        if (node instanceof IfNode) {
            collectAttributeSetReferences(
                ((IfNode) node).getContent(), refs, depth + 1);
        }
        if (node instanceof ChooseNode) {
            ChooseNode choose = (ChooseNode) node;
            for (WhenNode when : choose.getWhens()) {
                collectAttributeSetReferences(when, refs, depth + 1);
            }
            collectAttributeSetReferences(
                choose.getOtherwise(), refs, depth + 1);
        }
        if (node instanceof WhenNode) {
            collectAttributeSetReferences(
                ((WhenNode) node).getContent(), refs, depth + 1);
        }
        if (node instanceof ForkNode) {
            ForkNode fork = (ForkNode) node;
            List<ForkNode.ForkBranch> branches = fork.getBranches();
            for (int i = 0; i < branches.size(); i++) {
                ForkNode.ForkBranch branch = branches.get(i);
                if (branch.getContent() != null) {
                    collectAttributeSetReferences(
                        branch.getContent(), refs, depth + 1);
                }
            }
        }
    }

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
        
        // XTSE3430: Validate streamable body
        if (streamable && body != null) {
            StreamabilityAnalyzer bodyAnalyzer = new StreamabilityAnalyzer();
            List<String> reasons = new ArrayList<String>();
            StreamabilityAnalyzer.ExpressionStreamability bodyStreamability =
                bodyAnalyzer.analyzeNode(body, reasons);
            boolean freeRanging = (bodyStreamability ==
                StreamabilityAnalyzer.ExpressionStreamability.FREE_RANGING);
            if (freeRanging) {
                StringBuilder sb = new StringBuilder();
                sb.append("XTSE3430: Body of xsl:source-document");
                sb.append(" with streamable='yes' is not streamable");
                if (!reasons.isEmpty()) {
                    sb.append(": ");
                    sb.append(reasons.get(0));
                }
                throw new SAXException(sb.toString());
            }

            // XTSE3430: Validate that use-attribute-sets in the streaming
            // body only reference streamable attribute sets.
            List<String> attrSetRefs = new ArrayList<String>();
            collectAttributeSetReferences(body, attrSetRefs, 0);
            for (int i = 0; i < attrSetRefs.size(); i++) {
                String refName = attrSetRefs.get(i);
                AttributeSet attrSet = builder.getAttributeSet(refName);
                if (attrSet != null && !attrSet.isStreamable()) {
                    throw new SAXException(
                        "XTSE3430: Attribute set '" + refName +
                        "' used in xsl:source-document streamable='yes'" +
                        " body is not declared streamable");
                }
            }

            // XTSE3430: Validate fork-specific streaming constraints
            validateForkStreamability(body, 0);

            // XTSE3430: Validate calls to streamable functions
            validateStreamableFunctionCallSites(body, 0);

            // XTSE3430: Validate for-each-group streaming constraints
            validateStreamingForEachGroup(body, 0);
        }

        SourceDocumentNode result = new SourceDocumentNode(hrefAvt, streamable, validation, useAccumulators, body);
        if (ctx.baseURI != null) {
            result.setStaticBaseURI(ctx.baseURI);
        }
        return result;
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
        int actionCount = 0;
        boolean seenAction = false;
        boolean seenFallbackBeforeAction = false;
        
        for (XSLTNode child : ctx.children) {
            String childStr = child.toString();
            if (childStr.contains("MergeSourceHolder")) {
                MergeSourceHolder holder = (MergeSourceHolder) child;
                sources.add(holder.source);
            } else if (childStr.contains("MergeActionHolder")) {
                MergeActionHolder holder = (MergeActionHolder) child;
                action = holder.content;
                actionCount++;
                seenAction = true;
            } else if (child instanceof FallbackNode) {
                if (!seenAction) {
                    seenFallbackBeforeAction = true;
                }
            }
        }
        
        // XTSE0010: xsl:merge must have exactly one xsl:merge-action
        if (actionCount > 1) {
            throw new SAXException("XTSE0010: xsl:merge must not have more than one xsl:merge-action");
        }
        if (sources.isEmpty()) {
            throw new SAXException("XTSE0010: xsl:merge requires at least one xsl:merge-source");
        }
        if (action == null) {
            throw new SAXException("XTSE0010: xsl:merge requires xsl:merge-action");
        }
        
        // XTSE0010: xsl:fallback must come after xsl:merge-action
        if (seenFallbackBeforeAction) {
            throw new SAXException("XTSE0010: xsl:fallback in xsl:merge must follow xsl:merge-action");
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
        
        // XTSE2200: all merge-sources must have same number of merge-keys
        if (sources.size() > 1) {
            int firstKeyCount = sources.get(0).keys.size();
            for (int i = 1; i < sources.size(); i++) {
                int keyCount = sources.get(i).keys.size();
                if (keyCount != firstKeyCount) {
                    throw new SAXException("XTSE2200: All xsl:merge-source elements must have " +
                        "the same number of xsl:merge-key children");
                }
            }
        }
        
        return new MergeNode(sources, action);
    }

    /**
     * Compiles an xsl:merge-source element.
     */
    private XSLTNode compileMergeSource(ElementContext ctx) throws SAXException {
        String name = ctx.attributes.get("name");
        
        // Support shadow attribute _select for runtime-evaluated select expressions
        String selectStr = resolveStaticShadowAttribute(ctx, "select");
        
        String forEachItemStr = ctx.attributes.get("for-each-item");
        String forEachSourceStr = ctx.attributes.get("for-each-source");
        String sortBeforeMergeStr = ctx.attributes.get("sort-before-merge");
        
        // Support shadow attribute _streamable
        String streamableStr = resolveStaticShadowAttribute(ctx, "streamable");
        
        // Trim whitespace from boolean-like attributes
        if (streamableStr != null) {
            streamableStr = streamableStr.trim();
        }
        if (sortBeforeMergeStr != null) {
            sortBeforeMergeStr = sortBeforeMergeStr.trim();
        }
        
        // XTSE0020: validate streamable attribute value
        if (streamableStr != null && !streamableStr.isEmpty()) {
            if (!"yes".equals(streamableStr) && !"no".equals(streamableStr)
                && !"true".equals(streamableStr) && !"false".equals(streamableStr)
                && !"1".equals(streamableStr) && !"0".equals(streamableStr)) {
                throw new SAXException("XTSE0020: Invalid value for streamable attribute: '"
                    + streamableStr + "' (must be yes/no/true/false)");
            }
        }
        
        // XTSE0020: validate sort-before-merge attribute value
        if (sortBeforeMergeStr != null && !sortBeforeMergeStr.isEmpty()) {
            if (!"yes".equals(sortBeforeMergeStr) && !"no".equals(sortBeforeMergeStr)
                && !"true".equals(sortBeforeMergeStr) && !"false".equals(sortBeforeMergeStr)
                && !"1".equals(sortBeforeMergeStr) && !"0".equals(sortBeforeMergeStr)) {
                throw new SAXException("XTSE0020: Invalid value for sort-before-merge attribute: '"
                    + sortBeforeMergeStr + "' (must be yes/no/true/false)");
            }
        }
        
        boolean sortBeforeMerge = "yes".equals(sortBeforeMergeStr) || "true".equals(sortBeforeMergeStr)
            || "1".equals(sortBeforeMergeStr);
        boolean streamable = "yes".equals(streamableStr) || "true".equals(streamableStr);
        
        // XTSE0010: xsl:merge-source must be a child of xsl:merge
        if (!elementStack.isEmpty()) {
            ElementContext parent = elementStack.peek();
            if (!"merge".equals(parent.localName) || !XSLT_NS.equals(parent.namespaceURI)) {
                throw new SAXException("XTSE0010: xsl:merge-source is not allowed here " +
                    "(must be a child of xsl:merge)");
            }
        } else {
            throw new SAXException("XTSE0010: xsl:merge-source is not allowed at the top level");
        }
        
        // XTSE0010: merge-source must have select or for-each-item or for-each-source
        if (selectStr == null && forEachItemStr == null && forEachSourceStr == null) {
            throw new SAXException("XTSE0010: xsl:merge-source must have a select attribute, " +
                "for-each-item attribute, or for-each-source attribute");
        }
        
        // XTSE3195: cannot specify both for-each-item and for-each-source
        if (forEachItemStr != null && forEachSourceStr != null) {
            throw new SAXException("XTSE3195: xsl:merge-source must not have both " +
                "for-each-item and for-each-source attributes");
        }
        
        // XTSE3430: streamable with sort-before-merge is not allowed
        if (streamable && sortBeforeMerge) {
            throw new SAXException("XTSE3430: xsl:merge-source with streamable='yes' " +
                "must not specify sort-before-merge='yes'");
        }
        
        // XTSE3430: streamable with crawling (descendant axis) select is not allowed
        if (streamable && selectStr != null && containsDescendantAxis(selectStr)) {
            throw new SAXException("XTSE3430: xsl:merge-source with streamable='yes' " +
                "has a select expression that is not motionless (uses descendant axis)");
        }
        
        // XTSE1650: validation and type attributes require a schema-aware processor
        String validationStr = ctx.attributes.get("validation");
        String typeStr = ctx.attributes.get("type");
        if (validationStr != null || typeStr != null) {
            throw new SAXException("XTSE1650: xsl:merge-source attributes 'validation' and 'type' " +
                "require a schema-aware XSLT processor");
        }
        
        XPathExpression select = selectStr != null ? compileExpression(selectStr) : null;
        XPathExpression forEachItem = forEachItemStr != null ? compileExpression(forEachItemStr) : null;
        XPathExpression forEachSource = forEachSourceStr != null ? compileExpression(forEachSourceStr) : null;
        
        // Collect merge keys and check for invalid children
        List<MergeNode.MergeKey> keys = new ArrayList<>();
        boolean hasNonKeyContent = false;
        for (XSLTNode child : ctx.children) {
            String childStr = child.toString();
            if (childStr.contains("MergeKeyHolder")) {
                MergeKeyHolder holder = (MergeKeyHolder) child;
                keys.add(holder.key);
            } else if (hasNonWhitespaceContent(Collections.singletonList(child))) {
                hasNonKeyContent = true;
            }
        }
        
        // XTSE0010: merge-source with select must not have non-merge-key content
        if (selectStr != null && hasNonKeyContent) {
            throw new SAXException("XTSE0010: xsl:merge-source with select attribute " +
                "must not have content other than xsl:merge-key");
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
        
        XPathExpression select = null;
        XSLTNode body = null;
        
        if (selectStr != null && !selectStr.isEmpty()) {
            // select attribute present
            if (!ctx.children.isEmpty() && hasNonWhitespaceContent(ctx.children)) {
                throw new SAXException("XTSE3200: xsl:merge-key with select attribute " +
                    "must not have non-empty content");
            }
            select = compileExpression(selectStr);
        } else {
            // No select attribute - use body as sequence constructor
            if (ctx.children.isEmpty()) {
                throw new SAXException("XTSE0010: xsl:merge-key requires either select attribute or content");
            }
            if (ctx.children.size() == 1) {
                body = ctx.children.get(0);
            } else {
                body = new SequenceNode(new ArrayList<>(ctx.children));
            }
        }
        
        String orderStr = ctx.attributes.get("order");
        String langStr = ctx.attributes.get("lang");
        String collationStr = ctx.attributes.get("collation");
        String dataTypeStr = ctx.attributes.get("data-type");
        
        AttributeValueTemplate orderAvt = orderStr != null ? parseAvt(orderStr) : null;
        AttributeValueTemplate langAvt = langStr != null ? parseAvt(langStr) : null;
        AttributeValueTemplate collationAvt = collationStr != null ? parseAvt(collationStr) : null;
        AttributeValueTemplate dataTypeAvt = dataTypeStr != null ? parseAvt(dataTypeStr) : null;
        
        MergeNode.MergeKey key = new MergeNode.MergeKey(select, body, orderAvt, langAvt, collationAvt, dataTypeAvt);
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
        // XTSE0010: xsl:context-item must be a direct child of xsl:template
        ElementContext parent = elementStack.peek();
        if (parent == null || !"template".equals(parent.localName) ||
                !XSLT_NS.equals(parent.namespaceURI)) {
            throw new SAXException("XTSE0010: xsl:context-item is only allowed as a " +
                "direct child of xsl:template");
        }
        
        // XTSE0090: select attribute is not allowed on xsl:context-item
        String selectAttr = ctx.attributes.get("select");
        if (selectAttr != null) {
            throw new SAXException("XTSE0090: xsl:context-item does not allow a 'select' attribute");
        }
        
        String asType = ctx.attributes.get("as");
        String use = ctx.attributes.get("use");
        if (use != null) {
            use = use.trim();
        }
        
        // Validate 'use' attribute
        if (use != null && !use.isEmpty()) {
            if (!use.equals("required") && !use.equals("optional") && !use.equals("absent")) {
                throw new SAXException("XTSE0020: Invalid value for 'use' attribute: " + use);
            }
        }
        
        // XTSE3088: use="absent" and as are mutually exclusive
        if ("absent".equals(use) && asType != null && !asType.trim().isEmpty()) {
            throw new SAXException("XTSE3088: xsl:context-item cannot specify both use=\"absent\" and an 'as' type");
        }
        
        // XTSE0020: use="absent" requires a name attribute; if template only
        // has match (no name), use="absent" is not permitted
        if ("absent".equals(use) && currentTemplateHasMatch && !currentTemplateHasName) {
            throw new SAXException("XTSE0020: use=\"absent\" is not allowed in a "
                + "template rule that has a match attribute but no name attribute");
        }
        
        // Validate the 'as' type by parsing it via SequenceType
        SequenceType parsedType = null;
        if (asType != null && !asType.trim().isEmpty()) {
            final Map<String, String> nsBindings = ctx.namespaceBindings;
            parsedType = SequenceType.parse(asType.trim(), new java.util.function.Function<String, String>() {
                @Override
                public String apply(String prefix) {
                    return nsBindings.get(prefix);
                }
            });
            
            if (parsedType != null) {
                // XTSE0020: Occurrence indicator not allowed in xsl:context-item/@as
                // The context item is always a single item, not a sequence
                if (parsedType.getOccurrence() != SequenceType.Occurrence.ONE) {
                    throw new SAXException("XTSE0020: Occurrence indicator not allowed in xsl:context-item/@as: " + asType);
                }
                
                // XTSE0020/XPST0051: Unknown or user-defined atomic type
                // For atomic types with a non-xs namespace, this requires schema-awareness
                if (parsedType.getItemKind() == SequenceType.ItemKind.ATOMIC) {
                    String ns = parsedType.getNamespaceURI();
                    if (ns != null && !SequenceType.XS_NAMESPACE.equals(ns)) {
                        throw new SAXException("XTSE0020: Unknown atomic type '" + asType.trim()
                            + "' in xsl:context-item/@as (XPST0051: schema type not imported)");
                    }
                }
                
                // XTSE0020/XPST0008: Unknown type used in element/attribute type test
                // typeName holds the content type for element(*, type) patterns
                if (parsedType.getTypeName() != null) {
                    String ns = parsedType.getTypeName().getURI();
                    if (ns != null && !SequenceType.XS_NAMESPACE.equals(ns)) {
                        throw new SAXException("XTSE0020: Unknown schema type '" + asType.trim()
                            + "' in xsl:context-item/@as (XPST0008: schema type not available)");
                    }
                }
            }
        }
        
        return new ContextItemDeclaration(asType, use, parsedType);
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
        if (use != null) {
            use = use.trim();
        }
        
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
     * Performs runtime validation of context item type and presence.
     */
    private static class ContextItemDeclaration implements XSLTNode {
        final String asType;
        final String use;
        final SequenceType parsedType;
        
        ContextItemDeclaration(String asType, String use, SequenceType parsedType) {
            this.asType = asType;
            this.use = use;
            this.parsedType = parsedType;
        }
        
        @Override
        public void execute(TransformContext ctx, OutputHandler out) throws org.xml.sax.SAXException {
            // Determine the current context item
            XPathNode contextNode = ctx.getContextNode();
            XPathValue contextItem = ctx.getContextItem();
            
            // XTTE3090: use="required" but no context item supplied.
            // The context item is considered absent when both contextItem and contextNode are null.
            boolean hasContextItem = (contextNode != null || contextItem != null);
            if ("required".equals(use) && !hasContextItem) {
                throw new org.xml.sax.SAXException("XTTE3090: Context item is required but none was supplied");
            }
            
            // use="absent": mark the context item as undefined for this template
            // Any access to '.' inside the template body will raise XPDY0002
            if ("absent".equals(use)) {
                if (ctx instanceof BasicTransformContext) {
                    ((BasicTransformContext) ctx).setContextItemUndefined(true);
                }
                return;
            }
            
            // XTTE0590: Type check context item against declared type.
            if (parsedType != null) {
                XPathValue itemToCheck = contextItem;
                // When contextItem is null but contextNode exists, the context
                // was inherited (e.g. from call-template). Wrap the node for
                // type checking only when the declared type expects a node type.
                if (itemToCheck == null && contextNode != null
                        && !"optional".equals(use)) {
                    // contextItem is null but contextNode exists: the context
                    // was inherited (e.g. from call-template). Wrap the node
                    // for type checking. Skip for use="optional" since the
                    // inherited node is not an explicit context item.
                    List<XPathNode> nodes = new ArrayList<XPathNode>();
                    nodes.add(contextNode);
                    itemToCheck = new XPathNodeSet(nodes);
                }
                if (itemToCheck != null && !parsedType.matches(itemToCheck)) {
                    throw new org.xml.sax.SAXException(
                        "XTTE0590: Context item does not match declared type '" + asType + "'");
                }
            }
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
        
        List<IterateNode.IterateParam> params = new ArrayList<>();
        Set<String> paramNames = new HashSet<>();
        XSLTNode onCompletion = null;
        List<XSLTNode> bodyNodes = new ArrayList<>();
        boolean seenBody = false;
        
        for (int i = 0; i < ctx.childElementNameList.size(); i++) {
            String childName = ctx.childElementNameList.get(i);
            if ("on-completion".equals(childName) && seenBody) {
                throw new SAXException("XTSE0010: xsl:on-completion must appear " +
                    "before the body of xsl:iterate");
            }
            if (!"param".equals(childName) && !"on-completion".equals(childName)
                    && !"fallback".equals(childName)) {
                seenBody = true;
            }
        }
        
        for (XSLTNode child : ctx.children) {
            if (child instanceof ParamNode) {
                ParamNode pn = (ParamNode) child;
                String pname = pn.getName();
                if (!paramNames.add(pname)) {
                    throw new SAXException("XTSE0580: Duplicate parameter name '" +
                        pname + "' in xsl:iterate");
                }
                // XTSE3520: implicitly mandatory iterate param
                boolean hasDefault = (pn.getSelectExpr() != null) ||
                    (pn.getContent() != null && !pn.getContent().isEmpty());
                if (!hasDefault && pn.getAs() != null) {
                    String asStr = pn.getAs().trim();
                    boolean allowsEmpty = asStr.endsWith("?") ||
                        asStr.endsWith("*") ||
                        "empty-sequence()".equals(asStr);
                    if (!allowsEmpty) {
                        throw new SAXException("XTSE3520: Parameter '" + pname +
                            "' in xsl:iterate is implicitly mandatory " +
                            "(has as=\"" + pn.getAs() + "\" but no default value)");
                    }
                }
                params.add(new IterateNode.IterateParam(pname, pn.getSelectExpr(), pn.getAs()));
            } else if (child instanceof OnCompletionNode) {
                onCompletion = child;
            } else if (child instanceof FallbackNode) {
                // xsl:fallback is permitted but ignored for supported instructions
            } else {
                bodyNodes.add(child);
            }
        }
        
        // XTSE3120: xsl:break and xsl:next-iteration must be in tail position
        validateBreakTailPosition(bodyNodes);
        
        XSLTNode body = bodyNodes.isEmpty() ? null : 
            (bodyNodes.size() == 1 ? bodyNodes.get(0) : new SequenceNode(bodyNodes));
        
        return new IterateNode(select, params, body, onCompletion);
    }
    
    /**
     * Validates that xsl:break and xsl:next-iteration appear only in tail
     * position within the body of xsl:iterate. An instruction is in tail
     * position if it is the last instruction, or if it is inside an
     * xsl:if/xsl:choose that is itself in tail position.
     */
    private void validateBreakTailPosition(List<XSLTNode> nodes) throws SAXException {
        for (int i = 0; i < nodes.size(); i++) {
            XSLTNode node = nodes.get(i);
            boolean isTail = (i == nodes.size() - 1);
            
            if (node instanceof BreakNode || node instanceof NextIterationNode) {
                if (!isTail) {
                    throw new SAXException("XTSE3120: xsl:break and " +
                        "xsl:next-iteration must appear in tail position " +
                        "within xsl:iterate");
                }
            } else if (isTail) {
                validateBreakTailInContainer(node);
            } else {
                if (containsBreakOrNextIteration(node)) {
                    throw new SAXException("XTSE3120: xsl:break and " +
                        "xsl:next-iteration must appear in tail position " +
                        "within xsl:iterate");
                }
            }
        }
    }
    
    /**
     * For a node in tail position, recursively validates that any
     * xsl:break/xsl:next-iteration within it are also in tail position
     * within their own sequence constructors.
     */
    private void validateBreakTailInContainer(XSLTNode node) throws SAXException {
        if (node instanceof IfNode) {
            IfNode ifNode = (IfNode) node;
            SequenceNode content = ifNode.getContent();
            if (content != null) {
                validateBreakTailPosition(content.getChildren());
            }
        } else if (node instanceof ChooseNode) {
            ChooseNode choose = (ChooseNode) node;
            for (WhenNode when : choose.getWhens()) {
                SequenceNode content = when.getContent();
                if (content != null) {
                    validateBreakTailPosition(content.getChildren());
                }
            }
            SequenceNode otherwise = choose.getOtherwise();
            if (otherwise != null) {
                validateBreakTailPosition(otherwise.getChildren());
            }
        } else if (node instanceof SequenceNode) {
            validateBreakTailPosition(((SequenceNode) node).getChildren());
        } else {
            if (containsBreakOrNextIteration(node)) {
                throw new SAXException("XTSE3120: xsl:break and " +
                    "xsl:next-iteration must appear in tail position " +
                    "within xsl:iterate");
            }
        }
    }
    
    /**
     * Checks whether a node tree contains xsl:break or xsl:next-iteration.
     */
    private boolean containsBreakOrNextIteration(XSLTNode node) {
        if (node instanceof BreakNode || node instanceof NextIterationNode) {
            return true;
        }
        if (node instanceof IfNode) {
            SequenceNode content = ((IfNode) node).getContent();
            if (content != null) {
                for (XSLTNode child : content.getChildren()) {
                    if (containsBreakOrNextIteration(child)) {
                        return true;
                    }
                }
            }
        } else if (node instanceof ChooseNode) {
            ChooseNode choose = (ChooseNode) node;
            for (WhenNode when : choose.getWhens()) {
                SequenceNode content = when.getContent();
                if (content != null) {
                    for (XSLTNode child : content.getChildren()) {
                        if (containsBreakOrNextIteration(child)) {
                            return true;
                        }
                    }
                }
            }
            SequenceNode otherwise = choose.getOtherwise();
            if (otherwise != null) {
                for (XSLTNode child : otherwise.getChildren()) {
                    if (containsBreakOrNextIteration(child)) {
                        return true;
                    }
                }
            }
        } else if (node instanceof SequenceNode) {
            for (XSLTNode child : ((SequenceNode) node).getChildren()) {
                if (containsBreakOrNextIteration(child)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Compiles an xsl:next-iteration instruction (XSLT 3.0).
     */
    private XSLTNode compileNextIteration(ElementContext ctx) throws SAXException {
        List<NextIterationNode.ParamValue> params = new ArrayList<>();
        Set<String> withParamNames = new HashSet<>();
        for (XSLTNode child : ctx.children) {
            if (child instanceof WithParamNode) {
                WithParamNode wp = (WithParamNode) child;
                String wpName = wp.getName();
                if (!withParamNames.add(wpName)) {
                    throw new SAXException("XTSE0670: Duplicate xsl:with-param name '" +
                        wpName + "' in xsl:next-iteration");
                }
                params.add(new NextIterationNode.ParamValue(
                    wpName, wp.getSelectExpr(), wp.getContent()));
            }
        }
        
        // XTSE3130: validate param names are declared in the enclosing xsl:iterate
        Set<String> iterateParamNames = findEnclosingIterateParamNames();
        if (iterateParamNames != null) {
            for (String wpName : withParamNames) {
                if (!iterateParamNames.contains(wpName)) {
                    throw new SAXException("XTSE3130: Parameter '" + wpName +
                        "' in xsl:next-iteration is not declared in the enclosing xsl:iterate");
                }
            }
        }
        
        return new NextIterationNode(params);
    }

    /**
     * Finds the parameter names declared in the enclosing xsl:iterate.
     */
    private Set<String> findEnclosingIterateParamNames() {
        for (ElementContext ancestor : elementStack) {
            if (XSLT_NS.equals(ancestor.namespaceURI)
                    && "iterate".equals(ancestor.localName)) {
                Set<String> names = new HashSet<>();
                for (XSLTNode child : ancestor.children) {
                    if (child instanceof ParamNode) {
                        names.add(((ParamNode) child).getName());
                    }
                }
                return names;
            }
        }
        return null;
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
        
        // XTSE1090: collation and composite are only allowed with group-by or group-adjacent
        boolean hasGroupBy = groupBy != null && !groupBy.isEmpty();
        boolean hasGroupAdjacent = groupAdjacent != null && !groupAdjacent.isEmpty();
        if (collationStr != null && !collationStr.isEmpty() && !hasGroupBy && !hasGroupAdjacent) {
            throw new SAXException("XTSE1090: collation attribute on xsl:for-each-group " +
                "is only allowed when group-by or group-adjacent is specified");
        }
        String compositeStr = ctx.attributes.get("composite");
        if (compositeStr != null && !hasGroupBy && !hasGroupAdjacent) {
            throw new SAXException("XTSE1090: composite attribute on xsl:for-each-group " +
                "is only allowed when group-by or group-adjacent is specified");
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
        validateYesOrNo("xsl:for-each-group", "composite", compositeStr);
        boolean isComposite = "yes".equals(compositeStr) || "1".equals(compositeStr) || "true".equals(compositeStr);
        validateSortStable(sorts);

        if (groupBy != null && !groupBy.isEmpty()) {
            XPathExpression groupByExpr = compileExpression(groupBy);
            return ForEachGroupNode.groupBy(select, groupByExpr, body, collationAvt, sorts, isComposite);
        } else if (groupAdjacent != null && !groupAdjacent.isEmpty()) {
            XPathExpression groupAdjacentExpr = compileExpression(groupAdjacent);
            return ForEachGroupNode.groupAdjacent(select, groupAdjacentExpr, body, collationAvt, sorts, isComposite);
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
        validateSortStable(sorts);
        
        XPathExpression selectExpr = select != null ? compileExpression(select) : null;
        XSLTNode content = contentNodes.isEmpty() ? null : new SequenceNode(contentNodes);
        
        // XTSE1040: if select is present, only xsl:sort and xsl:fallback are allowed
        if (selectExpr != null && content != null) {
            for (XSLTNode node : contentNodes) {
                if (node instanceof LiteralText && isWhitespace(((LiteralText) node).getText())) {
                    continue;
                }
                if (!(node instanceof FallbackNode)) {
                    throw new SAXException("XTSE1040: xsl:perform-sort with select attribute " +
                        "must not contain content other than xsl:sort and xsl:fallback");
                }
            }
        }
        
        // If no select and no content, the result is an empty sequence
        if (selectExpr == null && content == null) {
            selectExpr = compileExpression("()");
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
        String withParamsStr = ctx.attributes.get("with-params");
        String asType = ctx.attributes.get("as");
        
        XPathExpression xpathExpr = compileExpression(xpathStr);
        XPathExpression contextItemExpr = contextItemStr != null ? compileExpression(contextItemStr) : null;
        // base-uri is an AVT per XSLT 3.0 spec, not an XPath expression
        AttributeValueTemplate baseUriAvt = baseUriStr != null ? parseAvt(baseUriStr) : null;
        XPathExpression namespaceContextExpr = namespaceContextStr != null ? 
            compileExpression(namespaceContextStr) : null;
        XPathExpression withParamsExpr = withParamsStr != null ? compileExpression(withParamsStr) : null;
        
        // Process xsl:with-param children
        List<EvaluateNode.WithParamNode> params = new ArrayList<>();
        for (XSLTNode child : ctx.children) {
            if (child instanceof WithParamNode) {
                WithParamNode wp = (WithParamNode) child;
                params.add(new EvaluateNode.WithParamNode(
                    wp.getNamespaceURI(), wp.getLocalName(), 
                    wp.getSelectExpr(), wp.getContent(),
                    wp.getAsType()));
            }
        }
        
        return new EvaluateNode(xpathExpr, contextItemExpr, baseUriAvt, 
            namespaceContextExpr, withParamsExpr, asType, params);
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
            } else if (child instanceof FallbackNode) {
                // xsl:fallback inside a supported instruction is skipped
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
        String rollbackOutput = ctx.attributes.get("rollback-output");
        boolean rollback = !"no".equals(rollbackOutput);
        return new TryNode(body, catchBlocks, rollback);
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
                if (result == null) {
                    return;
                }
                if (output instanceof SequenceBuilderOutputHandler) {
                    SequenceBuilderOutputHandler seqBuilder =
                        (SequenceBuilderOutputHandler) output;
                    if (!seqBuilder.isInsideElement()) {
                        if (result instanceof XPathSequence) {
                            for (XPathValue item : (XPathSequence) result) {
                                seqBuilder.addItem(item);
                            }
                        } else if (result instanceof XPathNodeSet) {
                            for (XPathNode node : ((XPathNodeSet) result).getNodes()) {
                                seqBuilder.addItem(
                                    new XPathNodeSet(Collections.singletonList(node)));
                            }
                        } else {
                            seqBuilder.addItem(result);
                        }
                        return;
                    }
                }
                output.atomicValue(result);
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
        if (regexStr == null) {
            throw new SAXException("xsl:analyze-string requires regex attribute");
        }
        // Empty regex is an error in XSLT 2.0 (XTDE1150) but allowed in 3.0
        if (regexStr.isEmpty() && stylesheetVersion < 3.0) {
            throw new SAXException("XTDE1150: The regex attribute of xsl:analyze-string must not " +
                "be a zero-length string");
        }
        
        // XTSE1130: must have at least one of matching-substring or non-matching-substring
        boolean hasMatching = ctx.childElementNames.contains("matching-substring");
        boolean hasNonMatching = ctx.childElementNames.contains("non-matching-substring");
        if (!hasMatching && !hasNonMatching) {
            throw new SAXException("XTSE1130: xsl:analyze-string must contain at least one of " +
                "xsl:matching-substring or xsl:non-matching-substring");
        }
        
        // XTSE0010: validate child order
        // matching-substring must precede non-matching-substring, fallback must be last
        validateAnalyzeStringChildOrder(ctx);
        
        XPathExpression select = compileExpression(selectStr);
        AttributeValueTemplate regexAvt = parseAvt(regexStr);
        AttributeValueTemplate flagsAvt = flagsStr != null ? parseAvt(flagsStr) : null;
        
        // Map children to their element names using the ordered list
        XSLTNode matchingContent = null;
        XSLTNode nonMatchingContent = null;
        
        int childIdx = 0;
        for (int i = 0; i < ctx.childElementNameList.size(); i++) {
            String childName = ctx.childElementNameList.get(i);
            if ("matching-substring".equals(childName)) {
                if (childIdx < ctx.children.size()) {
                    matchingContent = ctx.children.get(childIdx);
                }
                childIdx++;
            } else if ("non-matching-substring".equals(childName)) {
                if (childIdx < ctx.children.size()) {
                    nonMatchingContent = ctx.children.get(childIdx);
                }
                childIdx++;
            } else if ("fallback".equals(childName)) {
                childIdx++;
            }
        }
        
        return new AnalyzeStringNode(select, regexAvt, flagsAvt, 
            matchingContent, nonMatchingContent);
    }
    
    private void validateAnalyzeStringChildOrder(ElementContext ctx) throws SAXException {
        boolean seenNonMatching = false;
        boolean seenFallback = false;
        for (String childName : ctx.childElementNameList) {
            if ("matching-substring".equals(childName)) {
                if (seenNonMatching) {
                    throw new SAXException("XTSE0010: xsl:matching-substring must precede " +
                        "xsl:non-matching-substring in xsl:analyze-string");
                }
                if (seenFallback) {
                    throw new SAXException("XTSE0010: xsl:matching-substring must precede " +
                        "xsl:fallback in xsl:analyze-string");
                }
            } else if ("non-matching-substring".equals(childName)) {
                seenNonMatching = true;
                if (seenFallback) {
                    throw new SAXException("XTSE0010: xsl:non-matching-substring must precede " +
                        "xsl:fallback in xsl:analyze-string");
                }
            } else if ("fallback".equals(childName)) {
                seenFallback = true;
            }
        }
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
        
        String formatStr = ctx.attributes.get("format");
        AttributeValueTemplate formatAvt = null;
        Map<String, String> formatNsBindings = null;
        if (formatStr != null && !formatStr.isEmpty()) {
            boolean isAvt = formatStr.contains("{") && formatStr.contains("}");
            if (!isAvt) {
                String expandedFormat = expandQName(formatStr.trim());
                if (!builder.hasOutputDefinition(expandedFormat)) {
                    throw new SAXException("XTSE1460: The format attribute of " +
                        "xsl:result-document references an unknown output definition '" +
                        formatStr + "'");
                }
            }
            formatAvt = parseAvt(formatStr);
            formatNsBindings = new HashMap<>(ctx.namespaceBindings);
        }
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
        
        return new ResultDocumentNode(hrefAvt, formatAvt, formatNsBindings,
                                      method, encoding, indent, content,
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
            // Unwrap CollationScopeNode to access the inner WhenNode/OtherwiseNode
            XSLTNode unwrapped = child;
            if (child instanceof CollationScopeNode) {
                unwrapped = ((CollationScopeNode) child).getBody();
            }
            if (unwrapped instanceof WhenNode) {
                if (sawOtherwise) {
                    throw new SAXException("XTSE0010: xsl:when must come before xsl:otherwise in xsl:choose");
                }
                // Preserve collation scope: move it inside the WhenNode body
                if (child instanceof CollationScopeNode) {
                    WhenNode when = (WhenNode) unwrapped;
                    CollationScopeNode cs = (CollationScopeNode) child;
                    List<XSLTNode> wrappedChildren = new ArrayList<>();
                    wrappedChildren.add(new CollationScopeNode(
                        cs.getCollationUri(), when.getContent()));
                    whens.add(new WhenNode(when.getTestExpr(), new SequenceNode(wrappedChildren)));
                } else {
                    whens.add((WhenNode) unwrapped);
                }
            } else if (unwrapped instanceof OtherwiseNode) {
                if (sawOtherwise) {
                    throw new SAXException("XTSE0010: xsl:choose must have at most one xsl:otherwise");
                }
                sawOtherwise = true;
                otherwise = ((OtherwiseNode) unwrapped).getContent();
            } else if (child instanceof LiteralText) {
                String text = ((LiteralText) child).getText();
                if (text != null && !text.trim().isEmpty()) {
                    throw new SAXException("XTSE0010: Text content is not allowed in xsl:choose");
                }
            } else {
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
        validateYesOrNo("xsl:with-param", "tunnel", tunnelAttr);
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
        XSLTNode sortBody = null;
        if (selectAttr != null && !selectAttr.isEmpty()) {
            selectExpr = compileExpression(selectAttr);
        } else if (!ctx.children.isEmpty() && hasNonWhitespaceContent(ctx.children)) {
            selectExpr = null;
            sortBody = new SequenceNode(new ArrayList<>(ctx.children));
        } else {
            selectExpr = compileExpression(".");
        }
        
        // Parse sort parameters as AVTs (they can contain expressions like {$data-type})
        String dataType = ctx.attributes.get("data-type");  // "text" or "number"
        String order = ctx.attributes.get("order");          // "ascending" or "descending"
        String caseOrder = ctx.attributes.get("case-order"); // "upper-first" or "lower-first"
        String lang = ctx.attributes.get("lang");
        String collation = ctx.attributes.get("collation");  // XSLT 2.0+ collation URI
        String stable = ctx.attributes.get("stable");
        if (stable != null && !stable.isEmpty()) {
            String stableTrimmed = stable.trim();
            if (!stableTrimmed.isEmpty() && !stableTrimmed.contains("{")) {
                if (!"yes".equals(stableTrimmed) && !"no".equals(stableTrimmed) &&
                        !"true".equals(stableTrimmed) && !"false".equals(stableTrimmed) &&
                        !"1".equals(stableTrimmed) && !"0".equals(stableTrimmed)) {
                    throw new SAXException("XTSE0020: Invalid value for 'stable' attribute " +
                        "on xsl:sort: must be yes or no, got '" + stable + "'");
                }
            }
        }
        
        AttributeValueTemplate dataTypeAvt = dataType != null ? parseAvt(dataType) : null;
        AttributeValueTemplate orderAvt = order != null ? parseAvt(order) : null;
        AttributeValueTemplate caseOrderAvt = caseOrder != null ? parseAvt(caseOrder) : null;
        AttributeValueTemplate langAvt = lang != null ? parseAvt(lang) : null;
        AttributeValueTemplate collationAvt = collation != null ? parseAvt(collation) : null;
        
        SortSpec spec = new SortSpec(selectExpr, sortBody, dataTypeAvt, orderAvt, caseOrderAvt, langAvt, collationAvt);
        spec.setHasStable(stable != null);
        return new SortSpecNode(spec);
    }

    /**
     * Validates XTSE1017: stable attribute is only allowed on the first xsl:sort.
     */
    private static void validateSortStable(List<SortSpec> sorts) throws SAXException {
        for (int i = 1; i < sorts.size(); i++) {
            if (sorts.get(i).hasStable()) {
                throw new SAXException("XTSE1017: The stable attribute is only " +
                    "allowed on the first xsl:sort element");
            }
        }
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
                formatAVT = AttributeValueTemplate.parse(formatAttr, this,
                        buildStaticTypeContext());
            }
        } catch (XPathSyntaxException e) {
            throw new SAXException("Invalid format AVT: " + e.getMessage(), e);
        }
        
        // grouping attributes (can be AVTs in XSLT 2.0+)
        String groupingSepAttr = ctx.attributes.get("grouping-separator");
        AttributeValueTemplate groupingSepAVT = null;
        if (groupingSepAttr != null) {
            try {
                groupingSepAVT = AttributeValueTemplate.parse(groupingSepAttr, this,
                        buildStaticTypeContext());
            } catch (XPathSyntaxException e) {
                throw new SAXException("Invalid grouping-separator AVT: " + e.getMessage(), e);
            }
        }
        String groupingSizeAttr = ctx.attributes.get("grouping-size");
        AttributeValueTemplate groupingSizeAVT = null;
        if (groupingSizeAttr != null) {
            try {
                groupingSizeAVT = AttributeValueTemplate.parse(groupingSizeAttr, this,
                        buildStaticTypeContext());
            } catch (XPathSyntaxException e) {
                throw new SAXException("Invalid grouping-size AVT: " + e.getMessage(), e);
            }
        }
        
        // lang and letter-value for internationalization (optional, can be AVTs)
        String langAttr = ctx.attributes.get("lang");
        AttributeValueTemplate langAVT = null;
        if (langAttr != null) {
            try {
                langAVT = AttributeValueTemplate.parse(langAttr, this,
                        buildStaticTypeContext());
            } catch (XPathSyntaxException e) {
                throw new SAXException("Invalid lang AVT: " + e.getMessage(), e);
            }
        }
        String letterValue = ctx.attributes.get("letter-value");
        String ordinalAttr = ctx.attributes.get("ordinal");
        AttributeValueTemplate ordinalAVT = null;
        if (ordinalAttr != null) {
            try {
                ordinalAVT = AttributeValueTemplate.parse(ordinalAttr, this,
                        buildStaticTypeContext());
            } catch (XPathSyntaxException e) {
                throw new SAXException("Invalid ordinal AVT: " + e.getMessage(), e);
            }
        }
        
        // start-at attribute (XSLT 3.0) - starting number offset, can be AVT
        String startAtAttr = ctx.attributes.get("start-at");
        AttributeValueTemplate startAtAVT = null;
        if (startAtAttr != null) {
            try {
                startAtAVT = AttributeValueTemplate.parse(startAtAttr, this,
                        buildStaticTypeContext());
            } catch (XPathSyntaxException e) {
                throw new SAXException("Invalid start-at AVT: " + e.getMessage(), e);
            }
        }
        
        boolean backwardsCompatible = getEffectiveVersion() < 2.0;
        return new NumberNode(valueExpr, selectExpr, level, countPattern, fromPattern, 
                             formatAVT, groupingSepAVT, groupingSizeAVT, langAVT, letterValue,
                             ordinalAVT, startAtAVT, backwardsCompatible);
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
                // Static value - validate at compile time (case-sensitive)
                validateYesOrNo("xsl:message", "terminate", terminateValue);
                String trimmed = terminateValue.trim();
                if ("yes".equals(trimmed) || "true".equals(trimmed) || "1".equals(trimmed)) {
                    terminateStatic = true;
                } else {
                    terminateStatic = false;
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
            return namespaces.get(prefix);
        }
        
        @Override
        public XPathFunctionLibrary getFunctionLibrary() {
            // Use maxProcessorVersion alone: a 3.0 processor allows the
            // full function library even for version="2.0" stylesheets.
            // The restricted library only applies when the processor is
            // explicitly constrained to < 3.0.
            if (maxProcessorVersion < 3.0) {
                return UseWhenFunctionLibrary.XSLT20;
            }
            return XSLTFunctionLibrary.INSTANCE;
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

    private XPathExpression compileExpression(String expr) throws SAXException {
        try {
            StaticTypeContext typeCtx = buildStaticTypeContext();
            return XPathExpression.compile(expr, this, typeCtx);
        } catch (XPathSyntaxException e) {
            throw new SAXException("Invalid XPath expression: " + expr + " - " + e.getMessage(), e);
        }
    }

    private StaticTypeContext buildStaticTypeContext() {
        return new CompilerStaticTypeContext(-1);
    }

    private StaticTypeContext buildStaticTypeContext(double overrideVersion) {
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

    private Pattern compilePattern(String pattern) throws SAXException {
        // Validate pattern syntax based on XSLT version
        validatePattern(pattern);
        
        // XTSE1060: current-group() is not allowed in patterns
        if (pattern != null && pattern.contains("current-group()")) {
            throw new SAXException("XTSE1060: current-group() is not allowed in a pattern");
        }
        // XTSE1070: current-grouping-key() is not allowed in patterns
        if (pattern != null && pattern.contains("current-grouping-key()")) {
            throw new SAXException("XTSE1070: current-grouping-key() is not allowed in a pattern");
        }
        // XTSE3470: current-merge-group() is not allowed in patterns
        if (pattern != null && pattern.contains("current-merge-group()")) {
            throw new SAXException("XTSE3470: current-merge-group() is not " +
                "allowed in a pattern");
        }
        // XTSE3500: current-merge-key() is not allowed in patterns
        if (pattern != null && pattern.contains("current-merge-key()")) {
            throw new SAXException("XTSE3500: current-merge-key() is not " +
                "allowed in a pattern");
        }
        
        // Resolve namespace prefixes in the pattern before compilation
        String resolvedPattern = resolvePatternNamespaces(pattern);
        try {
            // Per XSLT 3.0 §5.5.3: "A pattern is always interpreted according
            // to the rules of the host language version", i.e. the processor's
            // version, not the stylesheet's version attribute.
            double patternVersion = 3.0;
            if (maxProcessorVersion > 0 && maxProcessorVersion < patternVersion) {
                patternVersion = maxProcessorVersion;
            }
            Pattern result = PatternParser.parse(resolvedPattern, patternVersion);
            deferPatternPredicateValidation(result, pattern);
            return result;
        } catch (IllegalArgumentException e) {
            throw new SAXException(e.getMessage());
        }
    }

    /**
     * Validates all predicate expressions within a parsed pattern by
     * attempting to resolve namespace-prefixed function calls.
     * Functions in non-standard namespaces must be declared as
     * xsl:function or known extensions; otherwise XPST0017.
     */
    private void deferPatternPredicateValidation(Pattern pat, String patternStr)
            throws SAXException {
        List<String> preds = new ArrayList<String>();
        collectPredicates(pat, preds);
        for (int i = 0; i < preds.size(); i++) {
            String pred = preds.get(i);
            // Resolve function namespace URIs now (while prefix mappings are in scope)
            List<String[]> funcRefs = resolveFunctionReferences(pred);
            for (int j = 0; j < funcRefs.size(); j++) {
                String[] ref = funcRefs.get(j);
                // ref[0] = nsUri, ref[1] = localName, ref[2] = patternStr
                deferredPatternValidations.add(new String[]{ref[0], ref[1], patternStr});
            }
        }
    }

    private List<String[]> resolveFunctionReferences(String pred)
            throws SAXException {
        List<String[]> refs = new ArrayList<String[]>();
        int len = pred.length();
        int i = 0;
        while (i < len) {
            char c = pred.charAt(i);
            if (c == '\'' || c == '"') {
                i++;
                while (i < len && pred.charAt(i) != c) {
                    i++;
                }
                i++;
                continue;
            }
            if (c == ':' && i + 1 < len && pred.charAt(i + 1) != ':') {
                int j = i + 1;
                while (j < len && Character.isWhitespace(pred.charAt(j))) {
                    j++;
                }
                if (j < len && Character.isLetter(pred.charAt(j))) {
                    int localStart = j;
                    int k = j;
                    while (k < len && (Character.isLetterOrDigit(pred.charAt(k)) ||
                           pred.charAt(k) == '-' || pred.charAt(k) == '_')) {
                        k++;
                    }
                    String localName = pred.substring(localStart, k);
                    while (k < len && Character.isWhitespace(pred.charAt(k))) {
                        k++;
                    }
                    if (k < len && pred.charAt(k) == '(') {
                        int prefStart = i - 1;
                        while (prefStart >= 0 &&
                               (Character.isLetterOrDigit(pred.charAt(prefStart)) ||
                                pred.charAt(prefStart) == '-' ||
                                pred.charAt(prefStart) == '_')) {
                            prefStart--;
                        }
                        prefStart++;
                        String prefix = pred.substring(prefStart, i);
                        if (!prefix.isEmpty() &&
                            !"xs".equals(prefix) &&
                            !"fn".equals(prefix) &&
                            !"math".equals(prefix) &&
                            !"map".equals(prefix) &&
                            !"array".equals(prefix)) {
                            String nsUri = lookupNamespaceUri(prefix);
                            if (nsUri == null) {
                                throw new SAXException(
                                    "XPST0017: Unknown namespace prefix '" + prefix +
                                    "' in pattern function call");
                            }
                            refs.add(new String[]{nsUri, localName});
                        }
                    }
                }
            }
            i++;
        }
        return refs;
    }

    private void validateDeferredPatternPredicates() throws SAXException {
        for (int i = 0; i < deferredPatternValidations.size(); i++) {
            String[] entry = deferredPatternValidations.get(i);
            String nsUri = entry[0];
            String localName = entry[1];
            String patternStr = entry[2];
            if (!isKnownFunction(nsUri, localName)) {
                throw new SAXException(
                    "XPST0017: Unknown function in pattern: " +
                    patternStr);
            }
        }
        deferredPatternValidations.clear();
    }

    /**
     * Scans a predicate expression for namespace-prefixed function calls.
     * Returns the first undeclared function name, or null if all are known.
     * A function is considered undeclared if its namespace prefix maps to
     * a URI with no declared xsl:function matching that local name.
     */
    private String findUndeclaredFunction(String pred) {
        int len = pred.length();
        int i = 0;
        while (i < len) {
            char c = pred.charAt(i);
            if (c == '\'' || c == '"') {
                i++;
                while (i < len && pred.charAt(i) != c) {
                    i++;
                }
                i++;
                continue;
            }
            if (c == ':' && i + 1 < len && pred.charAt(i + 1) != ':') {
                int j = i + 1;
                while (j < len && Character.isWhitespace(pred.charAt(j))) {
                    j++;
                }
                if (j < len && Character.isLetter(pred.charAt(j))) {
                    int localStart = j;
                    int k = j;
                    while (k < len && (Character.isLetterOrDigit(pred.charAt(k)) ||
                           pred.charAt(k) == '-' || pred.charAt(k) == '_')) {
                        k++;
                    }
                    String localName = pred.substring(localStart, k);
                    while (k < len && Character.isWhitespace(pred.charAt(k))) {
                        k++;
                    }
                    if (k < len && pred.charAt(k) == '(') {
                        int prefStart = i - 1;
                        while (prefStart >= 0 &&
                               (Character.isLetterOrDigit(pred.charAt(prefStart)) ||
                                pred.charAt(prefStart) == '-' ||
                                pred.charAt(prefStart) == '_')) {
                            prefStart--;
                        }
                        prefStart++;
                        String prefix = pred.substring(prefStart, i);
                        if (!prefix.isEmpty() &&
                            !"xs".equals(prefix) &&
                            !"fn".equals(prefix) &&
                            !"math".equals(prefix) &&
                            !"map".equals(prefix) &&
                            !"array".equals(prefix)) {
                            String nsUri = lookupNamespaceUri(prefix);
                            if (nsUri == null) {
                                return prefix + ":" + localName;
                            }
                            if (!isKnownFunction(nsUri, localName)) {
                                return prefix + ":" + localName;
                            }
                        }
                    }
                }
            }
            i++;
        }
        return null;
    }

    /**
     * Checks if a function with the given namespace URI and local name
     * is known (declared as xsl:function in the stylesheet).
     */
    private boolean isKnownFunction(String nsUri, String localName) {
        return builder.hasUserFunction(nsUri, localName);
    }

    private void collectPredicates(Pattern pat, List<String> preds) {
        if (pat instanceof AbstractPattern) {
            String predStr = ((AbstractPattern) pat).getPredicateStr();
            if (predStr != null) {
                preds.add(predStr);
            }
        }
        if (pat instanceof AtomicPattern) {
            List<String> atomicPreds =
                ((AtomicPattern) pat).getPredicates();
            preds.addAll(atomicPreds);
        }
        if (pat instanceof UnionPattern) {
            Pattern[] alts = ((UnionPattern) pat).getAlternatives();
            for (int i = 0; i < alts.length; i++) {
                collectPredicates(alts[i], preds);
            }
        }
        if (pat instanceof PredicatedPattern) {
            collectPredicates(((PredicatedPattern) pat).getInner(), preds);
        }
        if (pat instanceof IntersectPattern) {
            collectPredicates(((IntersectPattern) pat).getLeft(), preds);
            collectPredicates(((IntersectPattern) pat).getRight(), preds);
        }
        if (pat instanceof ExceptPattern) {
            collectPredicates(((ExceptPattern) pat).getLeft(), preds);
            collectPredicates(((ExceptPattern) pat).getRight(), preds);
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
    /**
     * Checks if a pattern contains a variable reference ($name) outside
     * of quoted strings. Used to reject XSLT 3.0 variable patterns in
     * XSLT 2.0 stylesheets.
     */
    private static boolean containsVariableRef(String pattern) {
        boolean inQuote = false;
        char quoteChar = 0;
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (!inQuote && (c == '\'' || c == '"')) {
                inQuote = true;
                quoteChar = c;
            } else if (inQuote && c == quoteChar) {
                inQuote = false;
            } else if (!inQuote && c == '$') {
                return true;
            }
        }
        return false;
    }

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
        List<String> parts = new ArrayList<String>();
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
                // If followed by '(', this is a kind test or function call -
                // don't apply xpath-default-namespace
                boolean followedByParen = (i < len && pattern.charAt(i) == '(');
                if (followedByParen) {
                    result.append(token);
                } else {
                    String resolved = resolvePatternToken(token, inAttributeContext);
                    result.append(resolved);
                }
                inAttributeContext = false;
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

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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.xml.sax.Attributes;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.AttributesImpl;
import org.xml.sax.helpers.DefaultHandler;

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
import org.bluezoo.gonzalez.transform.runtime.OutputHandler;
import org.bluezoo.gonzalez.transform.runtime.SAXEventBuffer;
import org.bluezoo.gonzalez.transform.runtime.TemplateMatcher;
import org.bluezoo.gonzalez.transform.runtime.TransformContext;
import org.bluezoo.gonzalez.transform.xpath.XPathExpression;
import org.bluezoo.gonzalez.transform.xpath.XPathParser;
import org.bluezoo.gonzalez.transform.xpath.XPathSyntaxException;
import org.bluezoo.gonzalez.transform.xpath.expr.XPathException;
import org.bluezoo.gonzalez.transform.xpath.type.NodeType;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNode;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNodeSet;
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
    private final StringBuilder characterBuffer = new StringBuilder();
    
    // Locator for error reporting
    private Locator locator;
    
    // Import/include support
    private final StylesheetResolver resolver;
    private final String baseUri;
    private int importPrecedence;
    private int importCounter = 0;  // Counts imports in this stylesheet
    private int templateCounter = 0;  // Counts templates for declaration order
    private boolean importsAllowed = true;
    
    // Forward-compatible processing mode
    private double stylesheetVersion = 1.0;
    private boolean forwardCompatible = false;
    
    // Track depth inside top-level user data elements (ignored per XSLT 1.0 Section 2.2)
    private int userDataElementDepth = 0;

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
     * Creates a new stylesheet compiler with external stylesheet support and
     * specified import precedence (used internally for imported stylesheets).
     *
     * @param resolver the stylesheet resolver
     * @param baseUri the base URI of this stylesheet
     * @param importPrecedence the import precedence for templates in this stylesheet
     */
    StylesheetCompiler(StylesheetResolver resolver, String baseUri, int importPrecedence) {
        this.resolver = resolver;
        this.baseUri = baseUri;
        this.importPrecedence = importPrecedence;
        
        // Mark this stylesheet as loaded in the resolver
        if (resolver != null && baseUri != null) {
            resolver.markLoaded(baseUri);
        }
    }

    /**
     * Returns the compiled stylesheet.
     * Call this after parsing is complete.
     *
     * @return the compiled stylesheet
     */
    public CompiledStylesheet getCompiledStylesheet() {
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
    }

    @Override
    public void endDocument() throws SAXException {
        // Compilation complete
    }

    @Override
    public void startPrefixMapping(String prefix, String uri) throws SAXException {
        namespaces.put(prefix, uri);
        pendingNamespaces.put(prefix, uri);  // Track as pending for next element
        if (!elementStack.isEmpty()) {
            elementStack.peek().namespaceBindings.put(prefix, uri);
        }
    }

    @Override
    public void endPrefixMapping(String prefix) throws SAXException {
        namespaces.remove(prefix);
    }

    @Override
    public void startElement(String uri, String localName, String qName, Attributes atts) 
            throws SAXException {
        flushCharacters();
        
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
        }
        
        elementStack.push(ctx);
    }

    @Override
    public void endElement(String uri, String localName, String qName) throws SAXException {
        flushCharacters();
        
        // Skip processing if we're inside a top-level user data element
        if (userDataElementDepth > 0) {
            elementStack.pop();
            userDataElementDepth--;
            return;
        }
        
        ElementContext ctx = elementStack.pop();
        XSLTNode node = compileElement(ctx);
        
        if (elementStack.isEmpty()) {
            // Root element - should be xsl:stylesheet or xsl:transform
            if (node != null) {
                // Stylesheet was compiled via processStylesheetElement
            }
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
     * Compiles an element into an XSLT node.
     */
    private XSLTNode compileElement(ElementContext ctx) throws SAXException {
        if (XSLT_NS.equals(ctx.namespaceURI)) {
            return compileXSLTElement(ctx);
        } else {
            // Per XSLT 1.0 Section 2.2: Top-level elements in non-XSLT namespaces
            // are "user data elements" and are ignored (not compiled).
            // Their attributes should NOT be treated as AVTs.
            if (isTopLevel()) {
                return null; // Ignore top-level user data elements
            }
            return compileLiteralResultElement(ctx);
        }
    }

    /**
     * Compiles an XSLT instruction element.
     */
    private XSLTNode compileXSLTElement(ElementContext ctx) throws SAXException {
        switch (ctx.localName) {
            case "stylesheet":
            case "transform":
            case "package":
                processStylesheetElement(ctx);
                return null;
                
            case "import":
                processImport(ctx);
                return null;
                
            case "include":
                processInclude(ctx);
                return null;
                
            case "template":
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
                // Used by fork - compile as sequence
                return new SequenceNode(new ArrayList<>(ctx.children));
                
            case "result-document":
                return compileResultDocument(ctx);
                
            case "for-each-group":
                return compileForEachGroup(ctx);
                
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
                
            default:
                // In forward-compatible mode, unknown elements are ignored at top level
                // or use xsl:fallback content if inside a template
                if (forwardCompatible) {
                    // Check for xsl:fallback children
                    for (XSLTNode child : ctx.children) {
                        if (child instanceof FallbackNode) {
                            return child;
                        }
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
        // Parse prefix from qName
        String prefix = null;
        String localName = ctx.localName;
        
        // Extract xsl:use-attribute-sets before processing other attributes
        String useAttrSetsValue = ctx.attributes.get("xsl:use-attribute-sets");
        List<String> useAttributeSets = new ArrayList<>();
        if (useAttrSetsValue != null) {
            for (String setName : splitOnWhitespace(useAttrSetsValue)) {
                useAttributeSets.add(setName);
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
                avts.put(name, AttributeValueTemplate.parse(value));
            } catch (XPathSyntaxException e) {
                throw new SAXException("Invalid AVT in attribute " + name + ": " + e.getMessage(), e);
            }
        }
        
        // Build content
        SequenceNode content = new SequenceNode(ctx.children);
        
        // Per XSLT 1.0 section 7.1.1: Copy namespace nodes except XSLT namespace.
        // Output ALL in-scope namespaces - SAXOutputHandler will deduplicate inherited ones.
        // Namespace aliasing is applied at runtime by LiteralResultElement.
        Map<String, String> outputNamespaces = new LinkedHashMap<>();
        for (Map.Entry<String, String> ns : ctx.namespaceBindings.entrySet()) {
            String nsUri = ns.getValue();
            // Don't output the XSLT namespace
            if (!XSLT_NS.equals(nsUri)) {
                outputNamespaces.put(ns.getKey(), nsUri);
            }
        }
        
        return new LiteralResultElement(ctx.namespaceURI, localName, prefix, 
            avts, outputNamespaces, useAttributeSets, content);
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
            } catch (NumberFormatException e) {
                // Ignore invalid version, use default
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
            throw new SAXException("xsl:import requires an href attribute");
        }
        
        if (resolver == null) {
            throw new SAXException("xsl:import not supported: no StylesheetResolver configured");
        }
        
        try {
            // XSLT 1.0 import precedence rules:
            // 1. Imported stylesheets have lower precedence than the importing stylesheet
            // 2. Later imports have HIGHER precedence than earlier imports
            // So: first import gets lowest precedence, last import gets highest (but still < importer)
            //
            // Use a scheme where:
            // - Importer has precedence P (e.g., 0 for main stylesheet)
            // - First import gets P - 1000 + 0 = P - 1000
            // - Second import gets P - 1000 + 1 = P - 999
            // This ensures: all imports < importer, and later imports > earlier imports
            int importedPrecedence = importPrecedence - 1000 + importCounter;
            importCounter++;
            
            CompiledStylesheet imported = resolver.resolve(href, baseUri, true, importedPrecedence);
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
        // Include is allowed anywhere in top-level, but once we see a non-import
        // element, no more imports are allowed
        importsAllowed = false;
        
        String href = ctx.attributes.get("href");
        if (href == null || href.isEmpty()) {
            throw new SAXException("xsl:include requires an href attribute");
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

    private void processTemplateElement(ElementContext ctx) throws SAXException {
        importsAllowed = false;
        String match = ctx.attributes.get("match");
        String name = ctx.attributes.get("name");
        String mode = ctx.attributes.get("mode");
        String priorityStr = ctx.attributes.get("priority");
        
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
        List<TemplateParameter> params = new ArrayList<>();
        List<XSLTNode> bodyNodes = new ArrayList<>();
        
        for (XSLTNode child : ctx.children) {
            if (child instanceof ParamNode) {
                ParamNode pn = (ParamNode) child;
                params.add(new TemplateParameter(pn.getName(), pn.getSelectExpr(), pn.getContent()));
            } else {
                bodyNodes.add(child);
            }
        }
        
        SequenceNode body = new SequenceNode(bodyNodes);
        
        TemplateRule rule = new TemplateRule(pattern, name, mode, priority, 
            importPrecedence, templateCounter++, params, body);
        builder.addTemplateRule(rule);
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
        importsAllowed = false;
        String name = ctx.attributes.get("name");
        String match = ctx.attributes.get("match");
        String use = ctx.attributes.get("use");
        
        if (name == null || match == null || use == null) {
            throw new SAXException("xsl:key requires name, match, and use attributes");
        }
        
        Pattern pattern = compilePattern(match);
        XPathExpression useExpr = compileExpression(use);
        
        builder.addKeyDefinition(new KeyDefinition(name, pattern, useExpr));
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

    private void processStripSpace(ElementContext ctx) {
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

    private void processPreserveSpace(ElementContext ctx) {
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
     * @param pattern the element name pattern (e.g., "foo:bar", "*", "bar")
     * @param namespaces the current namespace bindings
     * @return the resolved pattern with URI notation
     */
    private String resolveElementNameToUri(String pattern, Map<String, String> namespaces) {
        if ("*".equals(pattern)) {
            return "*";
        }
        
        int colon = pattern.indexOf(':');
        if (colon > 0) {
            String prefix = pattern.substring(0, colon);
            String localPart = pattern.substring(colon + 1);
            String uri = namespaces.get(prefix);
            
            if (uri == null) {
                // Prefix not found - try inherited bindings
                uri = lookupNamespaceUri(prefix);
            }
            
            if (uri != null) {
                // Convert to Clark notation: {uri}localname or {uri}* 
                return "{" + uri + "}" + localPart;
            }
            // If prefix can't be resolved, keep original
        }
        
        // Unprefixed name - matches elements in no namespace
        return pattern;
    }

    private void processNamespaceAlias(ElementContext ctx) {
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
        String select = ctx.attributes.get("select");
        
        XPathExpression selectExpr = select != null ? compileExpression(select) : null;
        SequenceNode content = ctx.children.isEmpty() ? null : new SequenceNode(ctx.children);
        
        if (isTopLevel) {
            importsAllowed = false;
            builder.addGlobalVariable(new GlobalVariable(name, false, selectExpr, content));
            return null;
        }
        
        return new VariableNode(name, selectExpr, content);
    }

    private XSLTNode compileParam(ElementContext ctx, boolean isTopLevel) throws SAXException {
        String name = ctx.attributes.get("name");
        String select = ctx.attributes.get("select");
        
        XPathExpression selectExpr = select != null ? compileExpression(select) : null;
        SequenceNode content = ctx.children.isEmpty() ? null : new SequenceNode(ctx.children);
        
        if (isTopLevel) {
            // Top-level param is a global variable that can be set externally
            importsAllowed = false;
            builder.addGlobalVariable(new GlobalVariable(name, true, selectExpr, content));
            return null;
        }
        
        return new ParamNode(name, selectExpr, content);
    }

    private XSLTNode compileValueOf(ElementContext ctx) throws SAXException {
        String select = ctx.attributes.get("select");
        boolean disableEscaping = "yes".equals(ctx.attributes.get("disable-output-escaping"));
        
        if (select != null) {
            return new ValueOfNode(compileExpression(select), disableEscaping);
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
        
        AttributeValueTemplate nameAvt = parseAvt(name);
        AttributeValueTemplate nsAvt = namespace != null ? parseAvt(namespace) : null;
        
        return new ElementNode(nameAvt, nsAvt, useAttrSets, new SequenceNode(ctx.children));
    }

    private XSLTNode compileAttribute(ElementContext ctx) throws SAXException {
        String name = ctx.attributes.get("name");
        String namespace = ctx.attributes.get("namespace");
        
        AttributeValueTemplate nameAvt = parseAvt(name);
        AttributeValueTemplate nsAvt = namespace != null ? parseAvt(namespace) : null;
        
        return new AttributeNode(nameAvt, nsAvt, new SequenceNode(ctx.children));
    }

    private XSLTNode compileComment(ElementContext ctx) {
        return new CommentNode(new SequenceNode(ctx.children));
    }

    private XSLTNode compilePI(ElementContext ctx) throws SAXException {
        String name = ctx.attributes.get("name");
        AttributeValueTemplate nameAvt = parseAvt(name);
        return new ProcessingInstructionNode(nameAvt, new SequenceNode(ctx.children));
    }

    private XSLTNode compileCopy(ElementContext ctx) {
        String useAttrSets = ctx.attributes.get("use-attribute-sets");
        return new CopyNode(useAttrSets, new SequenceNode(ctx.children));
    }

    private XSLTNode compileCopyOf(ElementContext ctx) throws SAXException {
        String select = ctx.attributes.get("select");
        if (select == null) {
            throw new SAXException("xsl:copy-of requires select attribute");
        }
        return new CopyOfNode(compileExpression(select));
    }

    private XSLTNode compileApplyTemplates(ElementContext ctx) throws SAXException {
        String select = ctx.attributes.get("select");
        String mode = ctx.attributes.get("mode");
        
        XPathExpression selectExpr = select != null ? compileExpression(select) : null;
        
        // Extract sorts and with-params from children
        List<SortSpec> sorts = new ArrayList<>();
        List<WithParamNode> params = new ArrayList<>();
        
        for (XSLTNode child : ctx.children) {
            if (child instanceof SortSpecNode) {
                sorts.add(((SortSpecNode) child).getSortSpec());
            } else if (child instanceof WithParamNode) {
                params.add((WithParamNode) child);
            }
        }
        
        return new ApplyTemplatesNode(selectExpr, mode, sorts, params);
    }

    private XSLTNode compileCallTemplate(ElementContext ctx) throws SAXException {
        String name = ctx.attributes.get("name");
        if (name == null) {
            throw new SAXException("xsl:call-template requires name attribute");
        }
        
        List<WithParamNode> params = new ArrayList<>();
        for (XSLTNode child : ctx.children) {
            if (child instanceof WithParamNode) {
                params.add((WithParamNode) child);
            }
        }
        
        return new CallTemplateNode(name, params);
    }

    private XSLTNode compileApplyImports(ElementContext ctx) {
        return new ApplyImportsNode();
    }

    private XSLTNode compileForEach(ElementContext ctx) throws SAXException {
        String select = ctx.attributes.get("select");
        if (select == null) {
            throw new SAXException("xsl:for-each requires select attribute");
        }
        
        // Extract sorts from children
        List<SortSpec> sorts = new ArrayList<>();
        List<XSLTNode> bodyNodes = new ArrayList<>();
        
        for (XSLTNode child : ctx.children) {
            if (child instanceof SortSpecNode) {
                sorts.add(((SortSpecNode) child).getSortSpec());
            } else {
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
        } else {
            throw new SAXException("xsl:for-each-group requires group-by or group-adjacent attribute");
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
        
        String format = ctx.attributes.get("format");
        String method = ctx.attributes.get("method");
        String encoding = ctx.attributes.get("encoding");
        String indent = ctx.attributes.get("indent");
        
        XSLTNode content = ctx.children.isEmpty() ? null :
            (ctx.children.size() == 1 ? ctx.children.get(0) 
                : new SequenceNode(new ArrayList<>(ctx.children)));
        
        return new ResultDocumentNode(hrefAvt, format, method, encoding, indent, content);
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
        
        return new NumberNode(valueExpr, level, countPattern, fromPattern, 
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

    private XPathExpression compileExpression(String expr) throws SAXException {
        try {
            return XPathExpression.compile(expr, this);
        } catch (XPathSyntaxException e) {
            throw new SAXException("Invalid XPath expression: " + expr + " - " + e.getMessage(), e);
        }
    }

    private Pattern compilePattern(String pattern) throws SAXException {
        // Simplified pattern compilation - full implementation in Pattern classes
        return new SimplePattern(pattern);
    }

    private AttributeValueTemplate parseAvt(String value) throws SAXException {
        try {
            return AttributeValueTemplate.parse(value);
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
        private final String name;
        private final XPathExpression selectExpr;
        private final SequenceNode content;
        
        VariableNode(String name, XPathExpression selectExpr, SequenceNode content) {
            this.name = name;
            this.selectExpr = selectExpr;
            this.content = content;
        }
        
        @Override public String getInstructionName() { return "variable"; }
        
        @Override
        public void execute(TransformContext context, 
                           OutputHandler output) throws SAXException {
            try {
                XPathValue value;
                if (selectExpr != null) {
                    value = selectExpr.evaluate(context);
                } else if (content != null) {
                    // Execute content to build result tree fragment
                    SAXEventBuffer buffer = 
                        new SAXEventBuffer();
                    OutputHandler bufferHandler = 
                        new BufferOutputHandler(buffer);
                    content.execute(context, bufferHandler);
                    // Store as RTF so xsl:copy-of can access the tree structure
                    value = new XPathResultTreeFragment(buffer);
                } else {
                    value = XPathString.of("");
                }
                context.getVariableScope().bind(name, value);
            } catch (XPathException e) {
                throw new SAXException("Error evaluating variable " + name, e);
            }
        }
    }

    private static class ParamNode extends XSLTInstruction {
        private final String name;
        private final XPathExpression selectExpr;
        private final SequenceNode content;
        
        ParamNode(String name, XPathExpression selectExpr, SequenceNode content) {
            this.name = name;
            this.selectExpr = selectExpr;
            this.content = content;
        }
        
        String getName() { return name; }
        XPathExpression getSelectExpr() { return selectExpr; }
        SequenceNode getContent() { return content; }
        
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
        
        ValueOfNode(XPathExpression selectExpr, boolean disableEscaping) {
            this.selectExpr = selectExpr;
            this.disableEscaping = disableEscaping;
        }
        
        @Override public String getInstructionName() { return "value-of"; }
        
        @Override
        public void execute(TransformContext context, 
                           OutputHandler output) throws SAXException {
            try {
                XPathValue result = selectExpr.evaluate(context);
                String value = (result != null) ? result.asString() : "";
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
        
        ElementNode(AttributeValueTemplate nameAvt, AttributeValueTemplate nsAvt, 
                   String useAttrSets, SequenceNode content) {
            this.nameAvt = nameAvt;
            this.nsAvt = nsAvt;
            this.useAttrSets = useAttrSets;
            this.content = content;
        }
        
        @Override public String getInstructionName() { return "element"; }
        @Override public void execute(TransformContext context, 
                                      OutputHandler output) throws SAXException {
            try {
                String name = nameAvt.evaluate(context);
                String namespace = nsAvt != null ? nsAvt.evaluate(context) : "";
                
                // Parse qName
                String localName = name;
                String prefix = null;
                int colon = name.indexOf(':');
                if (colon > 0) {
                    prefix = name.substring(0, colon);
                    localName = name.substring(colon + 1);
                    if (namespace.isEmpty()) {
                        namespace = context.resolveNamespacePrefix(prefix);
                    }
                }
                
                String qName = prefix != null ? prefix + ":" + localName : localName;
                output.startElement(namespace != null ? namespace : "", localName, qName);
                
                // Apply attribute sets if specified
                if (useAttrSets != null && !useAttrSets.isEmpty()) {
                    CompiledStylesheet stylesheet = context.getStylesheet();
                    java.util.StringTokenizer st = new java.util.StringTokenizer(useAttrSets);
                    while (st.hasMoreTokens()) {
                        String setName = st.nextToken();
                        AttributeSet attrSet = stylesheet.getAttributeSet(setName);
                        if (attrSet != null) {
                            attrSet.apply(context, output);
                        }
                    }
                }
                
                // Execute content
                if (content != null) {
                    content.execute(context, output);
                }
                
                output.endElement(namespace != null ? namespace : "", localName, qName);
            } catch (XPathException e) {
                throw new SAXException("Error in xsl:element", e);
            }
        }
    }

    private static class AttributeNode extends XSLTInstruction {
        private final AttributeValueTemplate nameAvt;
        private final AttributeValueTemplate nsAvt;
        private final SequenceNode content;
        
        AttributeNode(AttributeValueTemplate nameAvt, AttributeValueTemplate nsAvt, SequenceNode content) {
            this.nameAvt = nameAvt;
            this.nsAvt = nsAvt;
            this.content = content;
        }
        
        @Override public String getInstructionName() { return "attribute"; }
        @Override public void execute(TransformContext context, 
                                      OutputHandler output) throws SAXException {
            try {
                String name = nameAvt.evaluate(context);
                String namespace = nsAvt != null ? nsAvt.evaluate(context) : "";
                
                // Get attribute value from content
                String value = "";
                if (content != null) {
                    SAXEventBuffer buffer = 
                        new SAXEventBuffer();
                    content.execute(context, new BufferOutputHandler(buffer));
                    value = buffer.getTextContent();
                }
                
                // Parse qName
                String localName = name;
                int colon = name.indexOf(':');
                if (colon > 0) {
                    String prefix = name.substring(0, colon);
                    localName = name.substring(colon + 1);
                    if (namespace.isEmpty()) {
                        namespace = context.resolveNamespacePrefix(prefix);
                    }
                }
                
                output.attribute(namespace != null ? namespace : "", localName, name, value);
            } catch (XPathException e) {
                throw new SAXException("Error in xsl:attribute", e);
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
        CopyNode(String useAttrSets, SequenceNode content) {
            this.useAttrSets = useAttrSets;
            this.content = content;
        }
        @Override public String getInstructionName() { return "copy"; }
        @Override public void execute(TransformContext context, 
                                      OutputHandler output) throws SAXException {
            XPathNode node = context.getContextNode();
            if (node == null) {
                return;
            }
            
            switch (node.getNodeType()) {
                case ELEMENT:
                    String uri = node.getNamespaceURI() != null ? node.getNamespaceURI() : "";
                    String localName = node.getLocalName();
                    String prefix = node.getPrefix();
                    String qName = prefix != null ? prefix + ":" + localName : localName;
                    
                    output.startElement(uri, localName, qName);
                    
                    // Copy namespace declarations from source element
                    java.util.Iterator<XPathNode> namespaces = 
                        node.getNamespaces();
                    while (namespaces.hasNext()) {
                        XPathNode ns = namespaces.next();
                        String nsPrefix = ns.getLocalName();
                        String nsUri = ns.getStringValue();
                        if (!"xml".equals(nsPrefix)) {
                            output.namespace(nsPrefix, nsUri);
                        }
                    }
                    
                    // Apply use-attribute-sets if specified
                    if (useAttrSets != null && !useAttrSets.isEmpty()) {
                        CompiledStylesheet stylesheet = context.getStylesheet();
                        java.util.StringTokenizer st = new java.util.StringTokenizer(useAttrSets);
                        while (st.hasMoreTokens()) {
                            String setName = st.nextToken();
                            AttributeSet attrSet = stylesheet.getAttributeSet(setName);
                            if (attrSet != null) {
                                attrSet.apply(context, output);
                            }
                        }
                    }
                    
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
                    
                default:
                    // Namespace nodes - just process content
                    if (content != null) {
                        content.execute(context, output);
                    }
            }
        }
    }

    private static class CopyOfNode extends XSLTInstruction {
        private final XPathExpression selectExpr;
        CopyOfNode(XPathExpression selectExpr) { this.selectExpr = selectExpr; }
        @Override public String getInstructionName() { return "copy-of"; }
        @Override public void execute(TransformContext context, 
                                      OutputHandler output) throws SAXException {
            try {
                XPathValue result = selectExpr.evaluate(context);
                
                if (result == null) {
                    // Variable not found or expression returned null - output nothing
                    return;
                }
                
                if (result instanceof XPathResultTreeFragment) {
                    // Result tree fragment - replay the buffered events
                    XPathResultTreeFragment rtf = (XPathResultTreeFragment) result;
                    rtf.replayToOutput(output);
                } else if (result instanceof XPathNodeSet) {
                    XPathNodeSet nodeSet = (XPathNodeSet) result;
                    for (XPathNode node : nodeSet.getNodes()) {
                        deepCopyNode(node, output);
                    }
                } else {
                    // For non-node-sets, output as text
                    output.characters(result.asString());
                }
            } catch (XPathException e) {
                throw new SAXException("Error in xsl:copy-of", e);
            }
        }
        
        private void deepCopyNode(XPathNode node, 
                                  OutputHandler output) throws SAXException {
            switch (node.getNodeType()) {
                case ELEMENT:
                    String uri = node.getNamespaceURI() != null ? node.getNamespaceURI() : "";
                    String localName = node.getLocalName();
                    String prefix = node.getPrefix();
                    String qName = prefix != null ? prefix + ":" + localName : localName;
                    
                    output.startElement(uri, localName, qName);
                    
                    // Copy namespace declarations
                    java.util.Iterator<XPathNode> namespaces = 
                        node.getNamespaces();
                    while (namespaces.hasNext()) {
                        XPathNode ns = namespaces.next();
                        String nsPrefix = ns.getLocalName(); // namespace prefix is the local name
                        String nsUri = ns.getStringValue();  // namespace URI is the value
                        // Don't copy xml namespace (it's implicit)
                        if (!"xml".equals(nsPrefix)) {
                            output.namespace(nsPrefix, nsUri);
                        }
                    }
                    
                    // Copy attributes
                    java.util.Iterator<XPathNode> attrs = 
                        node.getAttributes();
                    while (attrs.hasNext()) {
                        XPathNode attr = attrs.next();
                        String attrUri = attr.getNamespaceURI() != null ? attr.getNamespaceURI() : "";
                        String attrLocal = attr.getLocalName();
                        String attrPrefix = attr.getPrefix();
                        String attrQName = attrPrefix != null ? attrPrefix + ":" + attrLocal : attrLocal;
                        output.attribute(attrUri, attrLocal, attrQName, attr.getStringValue());
                    }
                    
                    // Copy children
                    java.util.Iterator<XPathNode> children = 
                        node.getChildren();
                    while (children.hasNext()) {
                        deepCopyNode(children.next(), output);
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
                    // Copy children only
                    java.util.Iterator<XPathNode> rootChildren = 
                        node.getChildren();
                    while (rootChildren.hasNext()) {
                        deepCopyNode(rootChildren.next(), output);
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
                    } else {
                        return; // Not a node-set
                    }
                } else {
                    // Default: select="child::node()"
                    nodes = new ArrayList<>();
                    java.util.Iterator<XPathNode> children = 
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
                        // Push scope and execute
                        TransformContext execContext = 
                            nodeContext.pushVariableScope();
                        
                        // Set with-param values
                        for (WithParamNode param : params) {
                            XPathValue value = 
                                param.evaluate(context);
                            execContext.getVariableScope().bind(param.getName(), value);
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
                    // Apply templates to children
                    java.util.Iterator<XPathNode> children = 
                        node.getChildren();
                    List<XPathNode> childList = new ArrayList<>();
                    while (children.hasNext()) childList.add(children.next());
                    
                    int size = childList.size();
                    int pos = 1;
                    for (XPathNode child : childList) {
                        TransformContext childCtx = 
                            context.withContextNode(child).withPositionAndSize(pos++, size);
                        
                        TemplateMatcher m = 
                            new TemplateMatcher(context.getStylesheet());
                        TemplateRule r = m.findMatch(child, context.getCurrentMode(), childCtx);
                        if (r != null) {
                            if (TemplateMatcher.isBuiltIn(r)) {
                                executeBuiltIn(TemplateMatcher
                                    .getBuiltInType(r), child, childCtx, output);
                            } else {
                                r.getBody().execute(childCtx.pushVariableScope(), output);
                            }
                        }
                    }
                    break;
                case "text-or-attribute":
                    output.characters(node.getStringValue());
                    break;
                case "empty":
                    // Do nothing
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
            
            // Set with-param values
            try {
                for (WithParamNode param : params) {
                    XPathValue value = param.evaluate(context);
                    callContext.getVariableScope().bind(param.getName(), value);
                }
            } catch (XPathException e) {
                throw new SAXException("Error evaluating with-param", e);
            }
            
            // Execute template body
            template.getBody().execute(callContext, output);
        }
    }

    private static class ApplyImportsNode extends XSLTInstruction {
        @Override public String getInstructionName() { return "apply-imports"; }
        @Override public void execute(TransformContext context, 
                                      OutputHandler output) throws SAXException {
            // TODO: Implement import precedence handling
            // For now, just process with default templates
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
                if (!(result instanceof XPathNodeSet)) {
                    return; // Not a node-set
                }
                
                List<XPathNode> nodes = new ArrayList<>(
                    ((XPathNodeSet) result).getNodes());
                
                // Apply sorting if specified
                if (sorts != null && !sorts.isEmpty()) {
                    sortNodesStatic(nodes, sorts, context);
                }
                
                int size = nodes.size();
                int position = 1;
                for (XPathNode node : nodes) {
                    // Use withXsltCurrentNode to update both context node and XSLT current()
                    TransformContext iterContext;
                    if (context instanceof BasicTransformContext) {
                        iterContext = ((BasicTransformContext) context)
                            .withXsltCurrentNode(node).withPositionAndSize(position, size);
                    } else {
                        iterContext = context.withContextNode(node).withPositionAndSize(position, size);
                    }
                    body.execute(iterContext, output);
                    position++;
                }
            } catch (XPathException e) {
                throw new SAXException("Error in xsl:for-each", e);
            }
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
            
            java.util.Arrays.sort(indices, new java.util.Comparator<Integer>() {
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
                            cmp = sa.compareTo(sb);
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
        private final String level;
        private final Pattern countPattern;
        private final Pattern fromPattern;
        private final String format;
        private final String groupingSeparator;
        private final int groupingSize;
        private final String lang;
        private final String letterValue;
        
        NumberNode(XPathExpression valueExpr, String level, Pattern countPattern,
                  Pattern fromPattern, String format, String groupingSeparator,
                  int groupingSize, String lang, String letterValue) {
            this.valueExpr = valueExpr;
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
                // Count nodes based on level
                XPathNode node = context.getContextNode();
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
            // Count preceding siblings (plus self) that match the count pattern
            int count = 0;
            
            // If we have a from pattern, find the nearest ancestor matching it first
            if (fromPattern != null) {
                XPathNode ancestor = node.getParent();
                boolean foundFrom = false;
                while (ancestor != null) {
                    if (fromPattern.matches(ancestor, context)) {
                        foundFrom = true;
                        break;
                    }
                    ancestor = ancestor.getParent();
                }
                if (!foundFrom) {
                    return 0;
                }
            }
            
            // Count this node if it matches
            if (matchesCount(node, context)) {
                count = 1;
                
                // Count preceding siblings that match
                XPathNode sibling = node.getPrecedingSibling();
                while (sibling != null) {
                    if (matchesCount(sibling, context)) {
                        count++;
                    }
                    sibling = sibling.getPrecedingSibling();
                }
            }
            
            return count;
        }
        
        private List<Integer> countMultiple(XPathNode node, TransformContext context) {
            // Build list of counts at each ancestor level
            List<Integer> counts = new ArrayList<>();
            XPathNode current = node;
            
            while (current != null && current.getNodeType() != NodeType.ROOT) {
                // Check from pattern - stop if matched
                if (fromPattern != null && fromPattern.matches(current, context)) {
                    break;
                }
                
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
                
                current = current.getParent();
            }
            
            return counts;
        }
        
        private int countAny(XPathNode node, TransformContext context) {
            // Count all matching nodes before this one in document order
            int count = 0;
            
            // Find the from boundary
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
            }
            
            // Count all matching nodes from start (or from boundary)
            XPathNode root = node;
            while (root.getParent() != null) {
                root = root.getParent();
            }
            
            count = countNodesBeforeInDocument(root, node, fromBoundary, context);
            
            // Include current node if it matches
            if (matchesCount(node, context)) {
                count++;
            }
            
            return count;
        }
        
        private int countNodesBeforeInDocument(XPathNode current, XPathNode target, 
                                              XPathNode fromBoundary, TransformContext context) {
            if (current == target) {
                return 0;
            }
            
            int count = 0;
            
            // Check if we've passed the from boundary
            if (fromBoundary != null && current == fromBoundary) {
                fromBoundary = null; // Start counting from here
            }
            
            // Count this node if after from boundary and matches
            if (fromBoundary == null && matchesCount(current, context)) {
                count++;
            }
            
            // Recurse into children
            Iterator<XPathNode> children = current.getChildren();
            while (children.hasNext()) {
                XPathNode child = children.next();
                if (isNodeBefore(child, target)) {
                    count += countNodesBeforeInDocument(child, target, fromBoundary, context);
                } else if (child == target) {
                    return count;
                }
            }
            
            return count;
        }
        
        private boolean isNodeBefore(XPathNode a, XPathNode b) {
            // Simple check based on document order
            return a.getDocumentOrder() < b.getDocumentOrder();
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
            // Extract predicate if present
            int bracketStart = findPredicateStart(patternStr);
            if (bracketStart >= 0) {
                this.basePattern = patternStr.substring(0, bracketStart);
                this.predicateStr = patternStr.substring(bracketStart + 1, patternStr.length() - 1);
            } else {
                this.basePattern = patternStr;
                this.predicateStr = null;
            }
        }
        
        private int findPredicateStart(String pattern) {
            // Find the first '[' that's part of a predicate (not inside quotes)
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
                    if (c == '[') {
                        if (depth == 0) return i;
                        depth++;
                    } else if (c == ']') {
                        depth--;
                    }
                }
            }
            return -1;
        }
        
        @Override
        public boolean matches(XPathNode node, 
                              TransformContext context) {
            // Handle union patterns (pattern | pattern)
            if (basePattern.contains("|") && predicateStr == null) {
                String[] parts = splitUnion(patternStr);
                for (String part : parts) {
                    if (new SimplePattern(part.trim()).matches(node, context)) {
                        return true;
                    }
                }
                return false;
            }
            
            // First check base pattern match
            if (!matchesBasePattern(node, context)) {
                return false;
            }
            
            // If no predicate, we're done
            if (predicateStr == null) {
                return true;
            }
            
            // Evaluate predicate with proper position context
            return evaluatePredicate(node, context);
        }
        
        private boolean matchesBasePattern(XPathNode node, TransformContext context) {
            // Handle descendant-or-self pattern //name
            if (basePattern.startsWith("//")) {
                String nameTest = basePattern.substring(2);
                return matchesNameTest(node, nameTest);
            }
            
            // Handle root pattern "/" - must check before general /name pattern
            if ("/".equals(basePattern)) {
                return node.getParent() == null;
            }
            
            // Handle absolute pattern /name (child of root)
            if (basePattern.startsWith("/") && !basePattern.startsWith("//")) {
                String rest = basePattern.substring(1);
                if (node.getParent() == null) {
                    return false; // Pattern /x can't match root
                }
                XPathNode parent = node.getParent();
                // Check if parent is root
                if (parent.getParent() == null) {
                    return matchesNameTest(node, rest);
                }
                return false;
            }
            
            // Handle step patterns parent/child
            int slash = basePattern.lastIndexOf('/');
            if (slash > 0) {
                String parentPattern = basePattern.substring(0, slash);
                String childTest = basePattern.substring(slash + 1);
                
                if (!matchesNameTest(node, childTest)) {
                    return false;
                }
                
                XPathNode parent = node.getParent();
                if (parent == null) {
                    return false;
                }
                return new SimplePattern(parentPattern).matches(parent, context);
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
            if ("node()".equals(basePattern)) {
                // node() matches any node EXCEPT the root document node
                // The root is matched by "/" pattern or built-in template
                return node.getNodeType() != NodeType.ROOT;
            }
            if ("@*".equals(basePattern)) {
                return node.isAttribute();
            }
            
            // Simple name test
            return matchesNameTest(node, basePattern);
        }
        
        private boolean evaluatePredicate(XPathNode node, TransformContext context) {
            try {
                // Calculate position among siblings that match the base pattern
                int position = 1;
                int size = 0;
                
                XPathNode parent = node.getParent();
                if (parent != null) {
                    // Count matching siblings before this node and total
                    java.util.Iterator<XPathNode> siblings = parent.getChildren();
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
                TransformContext predContext = context.withContextNode(node)
                    .withPositionAndSize(position, size);
                
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
            if ("node()".equals(nameTest)) {
                return true;
            }
            
            // Handle namespace:* prefix test
            if (nameTest.endsWith(":*")) {
                String prefix = nameTest.substring(0, nameTest.length() - 2);
                // Would need namespace resolution here - simplified
                return node.isElement();
            }
            
            // Handle prefixed name test prefix:localname
            int colon = nameTest.indexOf(':');
            if (colon > 0) {
                String localPart = nameTest.substring(colon + 1);
                return node.isElement() && localPart.equals(node.getLocalName());
            }
            
            // Simple local name test
            return node.isElement() && nameTest.equals(node.getLocalName());
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
            if ("*".equals(patternStr) || "node()".equals(patternStr)) {
                return -0.5;
            }
            if (patternStr.contains(":*")) {
                return -0.25;
            }
            if (patternStr.contains("[")) {
                return 0.5; // Patterns with predicates
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
            flush();
            buffer.startPrefixMapping(prefix != null ? prefix : "", uri);
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
            // SAXEventBuffer doesn't support comments directly
        }
        
        @Override
        public void processingInstruction(String target, String data) throws SAXException {
            flush();
            buffer.processingInstruction(target, data);
        }
        
        @Override
        public void flush() throws SAXException {
            if (inStartTag) {
                buffer.startElement(pendingUri, pendingLocalName, pendingQName, pendingAttrs);
                inStartTag = false;
                pendingAttrs.clear();
            }
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

}

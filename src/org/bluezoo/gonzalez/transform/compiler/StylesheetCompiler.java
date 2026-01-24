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

import org.bluezoo.gonzalez.transform.ast.LiteralResultElement;
import org.bluezoo.gonzalez.transform.ast.LiteralText;
import org.bluezoo.gonzalez.transform.ast.SequenceNode;
import org.bluezoo.gonzalez.transform.ast.XSLTInstruction;
import org.bluezoo.gonzalez.transform.ast.XSLTNode;
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
    private final StringBuilder characterBuffer = new StringBuilder();
    
    // Locator for error reporting
    private Locator locator;
    
    // Import/include support
    private final StylesheetResolver resolver;
    private final String baseUri;
    private int importPrecedence;
    private boolean importsAllowed = true;

    /**
     * Context for an element being processed.
     */
    private static class ElementContext {
        final String namespaceURI;
        final String localName;
        final List<XSLTNode> children = new ArrayList<>();
        final Map<String, String> attributes = new HashMap<>();
        final Map<String, String> namespaceBindings = new HashMap<>();
        
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
        
        ElementContext ctx = new ElementContext(uri, localName);
        
        // Copy attributes
        for (int i = 0; i < atts.getLength(); i++) {
            ctx.attributes.put(atts.getQName(i), atts.getValue(i));
        }
        
        // Copy current namespace bindings
        ctx.namespaceBindings.putAll(namespaces);
        
        elementStack.push(ctx);
    }

    @Override
    public void endElement(String uri, String localName, String qName) throws SAXException {
        flushCharacters();
        
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
        // Check for xml:space="preserve" in ancestors
        // Simplified implementation
        return false;
    }

    /**
     * Compiles an element into an XSLT node.
     */
    private XSLTNode compileElement(ElementContext ctx) throws SAXException {
        if (XSLT_NS.equals(ctx.namespaceURI)) {
            return compileXSLTElement(ctx);
        } else {
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
                
            case "if":
                return compileIf(ctx);
                
            case "choose":
                return compileChoose(ctx);
                
            case "when":
                return compileWhen(ctx);
                
            case "otherwise":
                return compileOtherwise(ctx);
                
            case "sort":
                // Sort is handled within for-each/apply-templates
                return null;
                
            case "with-param":
                // Handled within call-template/apply-templates
                return compileWithParam(ctx);
                
            case "number":
                return compileNumber(ctx);
                
            case "message":
                return compileMessage(ctx);
                
            case "fallback":
                return compileFallback(ctx);
                
            default:
                throw new SAXException("Unknown XSLT element: xsl:" + ctx.localName);
        }
    }

    /**
     * Compiles a literal result element.
     */
    private XSLTNode compileLiteralResultElement(ElementContext ctx) throws SAXException {
        // Parse prefix from qName
        String prefix = null;
        String localName = ctx.localName;
        
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
        
        // Filter out XSLT namespace and extension namespaces from output
        Map<String, String> outputNamespaces = new LinkedHashMap<>();
        for (Map.Entry<String, String> ns : ctx.namespaceBindings.entrySet()) {
            String nsUri = ns.getValue();
            // Don't output the XSLT namespace
            if (!XSLT_NS.equals(nsUri)) {
                outputNamespaces.put(ns.getKey(), nsUri);
            }
        }
        
        return new LiteralResultElement(ctx.namespaceURI, localName, prefix, 
            avts, outputNamespaces, content);
    }

    // ========================================================================
    // Top-level element processing
    // ========================================================================

    private void processStylesheetElement(ElementContext ctx) throws SAXException {
        // Process children which add themselves to builder
    }

    /**
     * Returns true if the current element is a direct child of the stylesheet element.
     */
    private boolean isTopLevel() {
        if (elementStack.size() < 2) {
            return false;
        }
        // Check if parent is xsl:stylesheet or xsl:transform
        Iterator<ElementContext> iter = elementStack.iterator();
        iter.next(); // Skip current element (already popped when this is called from endElement)
        // Actually the stack has the parent at the top after we pop the current
        // Let me reconsider - when we call compileElement from endElement, 
        // the current element has already been popped, so the stack contains ancestors
        // So if the stack has exactly 1 element and it's the stylesheet, we're at top level
        if (elementStack.size() == 1) {
            ElementContext parent = elementStack.peek();
            if (XSLT_NS.equals(parent.namespaceURI)) {
                if ("stylesheet".equals(parent.localName) || "transform".equals(parent.localName)) {
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
            // Imports get a lower import precedence (decremented before use)
            int importedPrecedence = importPrecedence - 1;
            importPrecedence = importedPrecedence;
            
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
            importPrecedence, params, body);
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
            for (String s : useAttrSets.split("\\s+")) {
                if (!s.isEmpty()) {
                    useSets.add(s);
                }
            }
        }
        
        SequenceNode attrs = new SequenceNode(ctx.children);
        builder.addAttributeSet(new AttributeSet(name, useSets, attrs));
    }

    private void processStripSpace(ElementContext ctx) {
        importsAllowed = false;
        String elements = ctx.attributes.get("elements");
        if (elements != null) {
            for (String e : elements.split("\\s+")) {
                if (!e.isEmpty()) {
                    builder.addStripSpaceElement(e);
                }
            }
        }
    }

    private void processPreserveSpace(ElementContext ctx) {
        importsAllowed = false;
        String elements = ctx.attributes.get("elements");
        if (elements != null) {
            for (String e : elements.split("\\s+")) {
                if (!e.isEmpty()) {
                    builder.addPreserveSpaceElement(e);
                }
            }
        }
    }

    private void processNamespaceAlias(ElementContext ctx) {
        importsAllowed = false;
        String stylesheetPrefix = ctx.attributes.get("stylesheet-prefix");
        String resultPrefix = ctx.attributes.get("result-prefix");
        if (stylesheetPrefix != null && resultPrefix != null) {
            builder.addNamespaceAlias(stylesheetPrefix, resultPrefix);
        }
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
        if (select == null) {
            throw new SAXException("xsl:value-of requires select attribute");
        }
        
        boolean disableEscaping = "yes".equals(ctx.attributes.get("disable-output-escaping"));
        
        return new ValueOfNode(compileExpression(select), disableEscaping);
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

    private XSLTNode compileNumber(ElementContext ctx) throws SAXException {
        // Simplified xsl:number implementation
        return new NumberNode(ctx.attributes);
    }

    private XSLTNode compileMessage(ElementContext ctx) {
        boolean terminate = "yes".equals(ctx.attributes.get("terminate"));
        return new MessageNode(new SequenceNode(ctx.children), terminate);
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
                    value = XPathString.of(buffer.getTextContent());
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
                String value = selectExpr.evaluate(context).asString();
                if (disableEscaping) {
                    output.charactersRaw(value);
                } else {
                    output.characters(value);
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
                // TODO: Apply use-attribute-sets
                
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
                    // TODO: Apply use-attribute-sets
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
                
                if (result instanceof XPathNodeSet) {
                    XPathNodeSet nodeSet = 
                        (XPathNodeSet) result;
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
                    
                default:
                    // Ignore other node types
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
                
                // TODO: Apply sorting if specified
                
                // Process each node
                int size = nodes.size();
                int position = 1;
                for (XPathNode node : nodes) {
                    TransformContext nodeContext = 
                        context.withContextNode(node).withPositionAndSize(position, size);
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
                
                List<XPathNode> nodes = 
                    ((XPathNodeSet) result).getNodes();
                
                // TODO: Apply sorting if specified
                
                int size = nodes.size();
                int position = 1;
                for (XPathNode node : nodes) {
                    TransformContext iterContext = 
                        context.withContextNode(node).withPositionAndSize(position, size);
                    body.execute(iterContext, output);
                    position++;
                }
            } catch (XPathException e) {
                throw new SAXException("Error in xsl:for-each", e);
            }
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
        private final Map<String, String> attributes;
        NumberNode(Map<String, String> attributes) { this.attributes = new HashMap<>(attributes); }
        @Override public String getInstructionName() { return "number"; }
        @Override public void execute(TransformContext context, 
                                      OutputHandler output) throws SAXException {
            // Simplified xsl:number - just output position
            // Full implementation would handle level, count, from, format, etc.
            int position = context.getContextPosition();
            output.characters(String.valueOf(position));
        }
    }

    private static class MessageNode extends XSLTInstruction {
        private final SequenceNode content;
        private final boolean terminate;
        MessageNode(SequenceNode content, boolean terminate) {
            this.content = content;
            this.terminate = terminate;
        }
        @Override public String getInstructionName() { return "message"; }
        @Override public void execute(TransformContext context, 
                                      OutputHandler output) throws SAXException {
            String message = "";
            if (content != null) {
                SAXEventBuffer buffer = 
                    new SAXEventBuffer();
                content.execute(context, new BufferOutputHandler(buffer));
                message = buffer.getTextContent();
            }
            
            // Output to stderr
            System.err.println("XSLT Message: " + message);
            
            if (terminate) {
                throw new SAXException("Transformation terminated by xsl:message: " + message);
            }
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
        
        SimplePattern(String patternStr) {
            this.patternStr = patternStr;
        }
        
        @Override
        public boolean matches(XPathNode node, 
                              TransformContext context) {
            // Handle union patterns (pattern | pattern)
            if (patternStr.contains("|")) {
                String[] parts = splitUnion(patternStr);
                for (String part : parts) {
                    if (new SimplePattern(part.trim()).matches(node, context)) {
                        return true;
                    }
                }
                return false;
            }
            
            // Handle descendant-or-self pattern //name
            if (patternStr.startsWith("//")) {
                String nameTest = patternStr.substring(2);
                return matchesNameTest(node, nameTest);
            }
            
            // Handle absolute pattern /name (child of root)
            if (patternStr.startsWith("/") && !patternStr.startsWith("//")) {
                String rest = patternStr.substring(1);
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
            int slash = patternStr.lastIndexOf('/');
            if (slash > 0) {
                String parentPattern = patternStr.substring(0, slash);
                String childTest = patternStr.substring(slash + 1);
                
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
            if ("*".equals(patternStr)) {
                return node.isElement();
            }
            if ("/".equals(patternStr)) {
                return node.getParent() == null;
            }
            if ("text()".equals(patternStr)) {
                return node.isText();
            }
            if ("comment()".equals(patternStr)) {
                return node.getNodeType() == NodeType.COMMENT;
            }
            if ("processing-instruction()".equals(patternStr)) {
                return node.getNodeType() == NodeType.PROCESSING_INSTRUCTION;
            }
            if ("node()".equals(patternStr)) {
                return true;
            }
            if ("@*".equals(patternStr)) {
                return node.isAttribute();
            }
            
            // Simple name test
            return matchesNameTest(node, patternStr);
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

}

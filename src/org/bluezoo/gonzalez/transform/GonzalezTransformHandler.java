/*
 * GonzalezTransformHandler.java
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

package org.bluezoo.gonzalez.transform;

import org.bluezoo.gonzalez.schema.PSVIProvider;
import org.bluezoo.gonzalez.transform.ast.XSLTNode;
import org.bluezoo.gonzalez.transform.compiler.CompiledStylesheet;
import org.bluezoo.gonzalez.transform.compiler.GlobalVariable;
import org.bluezoo.gonzalez.transform.compiler.TemplateParameter;
import org.bluezoo.gonzalez.transform.compiler.TemplateRule;
import org.bluezoo.gonzalez.transform.xpath.XPathVariableException;
import org.bluezoo.gonzalez.transform.xpath.expr.XPathException;
import org.bluezoo.gonzalez.transform.runtime.*;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNode;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNodeSet;
import org.bluezoo.gonzalez.transform.xpath.type.XPathResultTreeFragment;
import org.bluezoo.gonzalez.transform.xpath.type.XPathString;
import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;
import org.xml.sax.*;
import org.xml.sax.ext.LexicalHandler;
import org.xml.sax.helpers.AttributesImpl;
import org.xml.sax.helpers.DefaultHandler;

import java.util.*;

/**
 * SAX handler that performs the actual XSLT transformation.
 *
 * <p>This handler receives SAX events from the source document and executes
 * the XSLT transformation, sending output to the result handler.
 *
 * <p>The transformation operates in two phases:
 * <ol>
 *   <li>Build phase: SAX events are used to construct an in-memory node tree</li>
 *   <li>Transform phase: After endDocument(), templates are applied to the tree</li>
 * </ol>
 *
 * <p>This two-phase approach is necessary because XSLT can access nodes in any
 * order (preceding-sibling, ancestor, etc.). Future optimization could use
 * streaming for simpler stylesheets that only use forward axes.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public class GonzalezTransformHandler extends DefaultHandler 
        implements LexicalHandler {

    /** The compiled XSLT stylesheet. */
    private final CompiledStylesheet stylesheet;
    
    /** Transformation parameters keyed by name. */
    private final Map<String, Object> parameters;
    
    /** The output handler for transformation results. */
    private final ContentHandler outputHandler;
    
    /** The template matcher for finding matching templates. */
    private final TemplateMatcher matcher;
    
    /** The error listener for reporting transformation errors. */
    private final javax.xml.transform.ErrorListener errorListener;
    
    /** Initial template name for XSLT 2.0+ initial-template support. */
    private String initialTemplate;
    
    /** PSVIProvider for accessing schema type information (DTD/XSD types). */
    private PSVIProvider psviProvider;

    // Document building state
    private StreamingNode root;
    private StreamingNode currentNode;
    private long documentOrderCounter = 0;
    private Map<String, String> pendingNamespaces = new HashMap<>();
    private StringBuilder textBuffer = new StringBuilder();

    /**
     * Creates a transform handler.
     *
     * @param stylesheet the compiled stylesheet
     * @param parameters transformation parameters
     * @param outputHandler the output target
     */
    public GonzalezTransformHandler(CompiledStylesheet stylesheet, 
                                    Map<String, Object> parameters,
                                    ContentHandler outputHandler) {
        this(stylesheet, parameters, outputHandler, null);
    }

    /**
     * Creates a transform handler with an error listener.
     *
     * @param stylesheet the compiled stylesheet
     * @param parameters transformation parameters
     * @param outputHandler the output target
     * @param errorListener the error listener for xsl:message and errors
     */
    public GonzalezTransformHandler(CompiledStylesheet stylesheet, 
                                    Map<String, Object> parameters,
                                    ContentHandler outputHandler,
                                    javax.xml.transform.ErrorListener errorListener) {
        this.stylesheet = stylesheet;
        this.parameters = parameters;
        this.outputHandler = outputHandler;
        this.matcher = new TemplateMatcher(stylesheet);
        this.errorListener = errorListener;
    }

    /**
     * Sets the initial template name for XSLT 2.0+ initial-template support.
     * If set, the transformation will start by calling this named template
     * instead of applying templates to the document root.
     *
     * @param name the name of the initial template to call
     */
    public void setInitialTemplate(String name) {
        this.initialTemplate = name;
    }
    
    /**
     * Sets the PSVIProvider for accessing schema type information.
     *
     * <p>When set, the transformer will use the PSVIProvider to retrieve
     * DTD or XSD type information for elements and attributes during
     * document building. This enables features like the {@code id()} function
     * to properly identify ID-typed attributes.
     *
     * <p>Typically, this is set to the XMLReader (if it implements PSVIProvider)
     * before parsing begins.
     *
     * @param provider the PSVIProvider, or null to use SAX Attributes directly
     */
    public void setPSVIProvider(PSVIProvider provider) {
        this.psviProvider = provider;
    }

    @Override
    public void startDocument() throws SAXException {
        // Initialize the document root
        root = StreamingNode.createRoot();
        currentNode = root;
        documentOrderCounter = 1;
        pendingNamespaces.clear();
        textBuffer.setLength(0);
    }

    @Override
    public void endDocument() throws SAXException {
        // Flush any pending text
        flushTextBuffer();
        
        // Now execute the transformation
        try {
            executeTransformation();
        } catch (Exception e) {
            throw new SAXException("Transformation error", e);
        }
    }

    @Override
    public void startPrefixMapping(String prefix, String uri) throws SAXException {
        pendingNamespaces.put(prefix != null ? prefix : "", uri);
    }

    @Override
    public void endPrefixMapping(String prefix) throws SAXException {
        // Namespace scoping is handled by the node tree
    }

    @Override
    public void startElement(String uri, String localName, String qName, Attributes atts) 
            throws SAXException {
        flushTextBuffer();
        
        // Extract prefix from qName
        String prefix = null;
        int colon = qName.indexOf(':');
        if (colon > 0) {
            prefix = qName.substring(0, colon);
        }
        
        // Build namespace bindings (inherit from parent + pending)
        Map<String, String> nsBindings = new HashMap<>();
        if (currentNode instanceof StreamingNode) {
            nsBindings.putAll(((StreamingNode) currentNode).getNamespaceBindings());
        }
        nsBindings.putAll(pendingNamespaces);
        pendingNamespaces.clear();
        
        // Create element node - pass PSVIProvider for type information if available
        StreamingNode element = StreamingNode.createElement(
            uri, localName, prefix, atts, nsBindings, currentNode, documentOrderCounter, psviProvider);
        documentOrderCounter += atts.getLength() + 1;
        
        currentNode = element;
    }

    @Override
    public void endElement(String uri, String localName, String qName) throws SAXException {
        flushTextBuffer();
        
        // Move back to parent
        currentNode = (StreamingNode) currentNode.getParent();
    }

    @Override
    public void characters(char[] ch, int start, int length) throws SAXException {
        textBuffer.append(ch, start, length);
    }

    @Override
    public void ignorableWhitespace(char[] ch, int start, int length) throws SAXException {
        // Check strip-space settings
        if (!shouldStripSpace()) {
            textBuffer.append(ch, start, length);
        }
    }

    @Override
    public void processingInstruction(String target, String data) throws SAXException {
        flushTextBuffer();
        StreamingNode.createPI(target, data, currentNode, documentOrderCounter++);
    }

    // LexicalHandler methods
    
    @Override
    public void comment(char[] ch, int start, int length) throws SAXException {
        flushTextBuffer();
        String text = new String(ch, start, length);
        StreamingNode.createComment(text, currentNode, documentOrderCounter++);
    }
    
    @Override
    public void startDTD(String name, String publicId, String systemId) throws SAXException {
        // Not needed for XSLT processing
    }
    
    @Override
    public void endDTD() throws SAXException {
        // Not needed for XSLT processing
    }
    
    @Override
    public void startEntity(String name) throws SAXException {
        // Not needed for XSLT processing
    }
    
    @Override
    public void endEntity(String name) throws SAXException {
        // Not needed for XSLT processing
    }
    
    @Override
    public void startCDATA() throws SAXException {
        // CDATA sections are treated as text
    }
    
    @Override
    public void endCDATA() throws SAXException {
        // CDATA sections are treated as text
    }

    private void flushTextBuffer() {
        if (textBuffer.length() > 0) {
            String text = textBuffer.toString();
            textBuffer.setLength(0);
            
            // Check if whitespace should be stripped
            if (shouldStripSpace() && isWhitespaceOnly(text)) {
                return;
            }
            
            StreamingNode.createText(text, currentNode, documentOrderCounter++);
        }
    }

    private boolean shouldStripSpace() {
        // Check stylesheet's strip-space declarations
        if (currentNode == null || !currentNode.isElement()) {
            return false;
        }
        
        String localName = currentNode.getLocalName();
        String namespaceURI = currentNode.getNamespaceURI();
        
        // Check preserve-space first (it takes precedence)
        for (String pattern : stylesheet.getPreserveSpaceElements()) {
            if (matchesElementPattern(pattern, localName, namespaceURI)) {
                return false;
            }
        }
        
        // Check strip-space
        for (String pattern : stylesheet.getStripSpaceElements()) {
            if (matchesElementPattern(pattern, localName, namespaceURI)) {
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * Checks if an element matches a strip-space/preserve-space pattern.
     * Patterns can be: *, {uri}*, {uri}localname, {*}localname, localname
     * 
     * The patterns use Clark notation {uri}localname for namespace-qualified names.
     * Special pattern {*}localname matches elements with that local name in any namespace.
     */
    private boolean matchesElementPattern(String pattern, String localName, String namespaceURI) {
        if ("*".equals(pattern)) {
            return true;
        }
        
        // Check for Clark notation: {uri}localname or {*}localname
        if (pattern.startsWith("{")) {
            int closeBrace = pattern.indexOf('}');
            if (closeBrace > 1) {
                String patternUri = pattern.substring(1, closeBrace);
                String patternLocal = pattern.substring(closeBrace + 1);
                
                // {*}localname - any namespace with specific local name (XSLT 2.0 *:localname)
                if ("*".equals(patternUri)) {
                    return patternLocal.equals(localName);
                }
                
                // Check namespace match
                // Empty patternUri means "no namespace" (from Q{}local)
                String effectiveNodeUri = namespaceURI == null ? "" : namespaceURI;
                if (!patternUri.equals(effectiveNodeUri)) {
                    return false; // Namespaces don't match
                }
                
                // {uri}* - match any element in namespace
                if ("*".equals(patternLocal)) {
                    return true;
                }
                
                // {uri}localname - match specific element
                return patternLocal.equals(localName);
            }
        }
        
        // Handle legacy prefix:localname (shouldn't happen after compile-time resolution)
        int colon = pattern.indexOf(':');
        if (colon > 0) {
            String localPart = pattern.substring(colon + 1);
            // prefix:* - match any element in any namespace
            if ("*".equals(localPart)) {
                return namespaceURI != null && !namespaceURI.isEmpty();
            }
            // prefix:localname - match specific element (namespace not checked)
            return localPart.equals(localName);
        }
        
        // Simple localname match (elements in no namespace or default namespace)
        // Per XSLT, unprefixed names match elements in no namespace
        if (namespaceURI != null && !namespaceURI.isEmpty()) {
            return false;
        }
        return pattern.equals(localName);
    }

    private boolean isWhitespaceOnly(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
                return false;
            }
        }
        return true;
    }

    // ========================================================================
    // Transformation Execution
    // ========================================================================

    private void executeTransformation() throws SAXException {
        // Create the output handler wrapper
        OutputHandler output = wrapOutputHandler(outputHandler);
        
        // Create initial context
        BasicTransformContext context = new BasicTransformContext(
            stylesheet, root, matcher, output, errorListener);
        
        // Initialize global variables and parameters
        initializeGlobals(context);
        
        // Start output document
        output.startDocument();
        
        // Check for initial template (XSLT 2.0+ feature)
        if (initialTemplate != null) {
            // Call the named template directly
            TemplateRule template = stylesheet.getNamedTemplate(initialTemplate);
            if (template == null) {
                throw new SAXException("XTDE0040: Initial template '" + initialTemplate + "' not found");
            }
            // Execute the named template with document root as context node
            TransformContext templateContext = context.pushVariableScope();
            XSLTNode body = template.getBody();
            if (body != null) {
                body.execute(templateContext, output);
            }
        } else {
            // Apply templates to the root node (standard behavior)
            applyTemplates(root, null, context, output);
        }
        
        // End output document
        output.endDocument();
    }

    private void initializeGlobals(BasicTransformContext context) throws SAXException {
        // Set transformation parameters
        for (Map.Entry<String, Object> entry : parameters.entrySet()) {
            XPathValue value;
            if (entry.getValue() instanceof XPathValue) {
                value = (XPathValue) entry.getValue();
            } else {
                value = XPathString.of(String.valueOf(entry.getValue()));
            }
            context.setVariable(entry.getKey(), value);
        }
        
        // Evaluate global variables with forward reference support
        // Use multi-pass: keep evaluating until all done or no progress (circular reference)
        List<GlobalVariable> allVars = stylesheet.getGlobalVariables();
        Set<String> evaluated = new HashSet<String>();
        Set<String> beingEvaluated = new HashSet<String>();
        
        // Mark parameters that were already set from external parameters
        for (GlobalVariable var : allVars) {
            String key = makeVarKey(var);
            if (var.isParam() && context.getVariable(var.getNamespaceURI(), var.getLocalName()) != null) {
                evaluated.add(key);
            }
        }
        
        // Multi-pass evaluation with circular reference detection
        boolean progress = true;
        while (progress) {
            progress = false;
            for (GlobalVariable var : allVars) {
                String key = makeVarKey(var);
                if (evaluated.contains(key)) {
                    continue;
                }
                
                // Try to evaluate this variable
                try {
                    beingEvaluated.add(key);
                    XPathValue value = evaluateGlobalVariable(var, context, beingEvaluated, evaluated);
                    beingEvaluated.remove(key);
                    
                    if (value != null) {
                        context.setVariable(var.getNamespaceURI(), var.getLocalName(), value);
                        evaluated.add(key);
                        progress = true;
                    }
                } catch (CircularReferenceException e) {
                    throw new SAXException("XTDE0640: Circular reference in variable: " + var.getName());
                } catch (Exception e) {
                    // Variable references an unevaluated variable - try again later
                    beingEvaluated.remove(key);
                }
            }
        }
        
        // Check if any variables remain unevaluated
        for (GlobalVariable var : allVars) {
            String key = makeVarKey(var);
            if (!evaluated.contains(key)) {
                throw new SAXException("XTDE0640: Circular reference detected involving variable: " + var.getName());
            }
        }
    }
    
    private String makeVarKey(GlobalVariable var) {
        if (var.getNamespaceURI() != null && !var.getNamespaceURI().isEmpty()) {
            return "{" + var.getNamespaceURI() + "}" + var.getLocalName();
        }
        return var.getLocalName();
    }
    
    private XPathValue evaluateGlobalVariable(GlobalVariable var, BasicTransformContext context,
            Set<String> beingEvaluated, Set<String> evaluated) throws Exception {
        try {
            // Check for static variable with pre-computed value (XSLT 3.0)
            if (var.isStatic()) {
                return var.getStaticValue();
            }
            if (var.getSelectExpr() != null) {
                return var.getSelectExpr().evaluate(context);
            } else if (var.getContent() != null) {
                // Execute content and capture as result tree fragment
                SAXEventBuffer buffer = new SAXEventBuffer();
                BufferOutputHandler bufferOutput = new BufferOutputHandler(buffer);
                var.getContent().execute(context, bufferOutput);
                return new XPathResultTreeFragment(buffer);
            } else {
                return XPathString.of("");
            }
        } catch (XPathVariableException e) {
            // Variable not yet available - check if it's being evaluated (circular)
            String refName = e.getVariableName();
            if (beingEvaluated.contains(refName)) {
                throw new CircularReferenceException(refName);
            }
            throw e;
        } catch (Exception e) {
            // Check if the root cause is a variable exception (may be wrapped)
            Throwable cause = e;
            while (cause != null) {
                if (cause instanceof XPathVariableException) {
                    String refName = ((XPathVariableException) cause).getVariableName();
                    if (beingEvaluated.contains(refName)) {
                        throw new CircularReferenceException(refName);
                    }
                    throw e;
                }
                cause = cause.getCause();
            }
            throw e;
        }
    }
    
    private static class CircularReferenceException extends Exception {
        CircularReferenceException(String varName) {
            super("Circular reference: " + varName);
        }
    }

    /**
     * Applies templates to a node.
     */
    private void applyTemplates(XPathNode node, String mode, 
            BasicTransformContext context, OutputHandler output) throws SAXException {
        
        // Use withXsltCurrentNode to set both context node and XSLT current() node
        TransformContext nodeContext = context.withXsltCurrentNode(node);
        if (mode != null) {
            nodeContext = ((BasicTransformContext) nodeContext).withMode(mode);
        }
        
        TemplateRule rule = matcher.findMatch(node, mode, nodeContext);
        
        if (rule != null) {
            executeTemplate(rule, node, (BasicTransformContext) nodeContext, output);
        }
    }

    /**
     * Executes a template rule on a node.
     */
    private void executeTemplate(TemplateRule rule, XPathNode node,
            BasicTransformContext context, OutputHandler output) throws SAXException {
        
        // Check for built-in rules
        if (TemplateMatcher.isBuiltIn(rule)) {
            executeBuiltInTemplate(TemplateMatcher.getBuiltInType(rule), node, context, output);
            return;
        }
        
        // Push variable scope for template and set current template rule (needed for apply-imports)
        TransformContext templateContext = context.pushVariableScope()
            .withCurrentTemplateRule(rule);
        
        // Bind template parameter defaults
        for (TemplateParameter templateParam : rule.getParameters()) {
            XPathValue defaultValue = null;
            if (templateParam.getSelectExpr() != null) {
                try {
                    defaultValue = templateParam.getSelectExpr().evaluate(templateContext);
                } catch (XPathException e) {
                    throw new SAXException("Error evaluating param default: " + e.getMessage(), e);
                }
            } else if (templateParam.getDefaultContent() != null) {
                // Execute content to get RTF as default value
                SAXEventBuffer buffer = new SAXEventBuffer();
                BufferOutputHandler bufferOutput = new BufferOutputHandler(buffer);
                templateParam.getDefaultContent().execute(templateContext, bufferOutput);
                defaultValue = new XPathResultTreeFragment(buffer);
            } else {
                defaultValue = XPathString.of("");
            }
            templateContext.getVariableScope().bind(templateParam.getName(), defaultValue);
        }
        
        // Execute template body
        XSLTNode body = rule.getBody();
        if (body != null) {
            body.execute(templateContext, output);
        }
    }

    /**
     * Executes a built-in template rule.
     */
    private void executeBuiltInTemplate(String type, XPathNode node,
            BasicTransformContext context, OutputHandler output) throws SAXException {
        
        switch (type) {
            case "element-or-root":
                // Apply templates to children
                applyTemplatesToChildren(node, context.getCurrentMode(), context, output);
                break;
                
            case "text-or-attribute":
                // Output the string value
                String value = node.getStringValue();
                if (value != null && !value.isEmpty()) {
                    output.characters(value);
                }
                break;
                
            case "empty":
                // Do nothing
                break;
        }
    }

    /**
     * Applies templates to all children of a node.
     */
    private void applyTemplatesToChildren(XPathNode node, String mode,
            BasicTransformContext context, OutputHandler output) throws SAXException {
        
        Iterator<XPathNode> children = node.getChildren();
        List<XPathNode> childList = new ArrayList<>();
        while (children.hasNext()) {
            childList.add(children.next());
        }
        
        int size = childList.size();
        int position = 1;
        
        for (XPathNode child : childList) {
            BasicTransformContext childContext = (BasicTransformContext) 
                context.withContextNode(child).withPositionAndSize(position, size);
            applyTemplates(child, mode, childContext, output);
            position++;
        }
    }

    /**
     * Applies templates to a node-set (for xsl:apply-templates with select).
     *
     * @param nodes the node-set to process
     * @param mode the template mode, or null for default mode
     * @param context the transformation context
     * @param output the output handler
     * @throws SAXException if a transformation error occurs
     */
    public void applyTemplatesToNodeSet(XPathNodeSet nodes, String mode,
            BasicTransformContext context, OutputHandler output) throws SAXException {
        
        List<XPathNode> nodeList = nodes.getNodes();
        int size = nodeList.size();
        int position = 1;
        
        for (XPathNode node : nodeList) {
            BasicTransformContext nodeContext = (BasicTransformContext)
                context.withContextNode(node).withPositionAndSize(position, size);
            applyTemplates(node, mode, nodeContext, output);
            position++;
        }
    }

    /**
     * Wraps a ContentHandler as an OutputHandler.
     */
    private OutputHandler wrapOutputHandler(ContentHandler handler) {
        if (handler instanceof OutputHandler) {
            return (OutputHandler) handler;
        }
        return new ContentHandlerOutputAdapter(handler);
    }

    /**
     * Adapter to use ContentHandler as OutputHandler.
     *
     * <p>This adapter bridges between the OutputHandler interface (which has
     * deferred start tag support) and the simpler ContentHandler interface.
     */
    private static class ContentHandlerOutputAdapter implements OutputHandler {
        private final ContentHandler handler;
        
        // Deferred start tag state
        private boolean inStartTag = false;
        private String pendingUri;
        private String pendingLocalName;
        private String pendingQName;
        private final AttributesImpl pendingAttrs = new AttributesImpl();
        // Pending namespace declarations for the current element
        private final List<String[]> pendingNamespaces = new ArrayList<String[]>();
        
        ContentHandlerOutputAdapter(ContentHandler handler) {
            this.handler = handler;
        }
        
        @Override
        public void startDocument() throws SAXException {
            handler.startDocument();
        }
        
        @Override
        public void endDocument() throws SAXException {
            flush();
            handler.endDocument();
        }
        
        @Override
        public void startElement(String uri, String localName, String qName) throws SAXException {
            flush();
            // Defer the start tag
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
            handler.endElement(uri != null ? uri : "", localName, qName != null ? qName : localName);
        }
        
        @Override
        public void attribute(String uri, String localName, String qName, String value) 
                throws SAXException {
            if (!inStartTag) {
                throw new SAXException("Cannot add attribute outside of start tag");
            }
            pendingAttrs.addAttribute(
                uri != null ? uri : "", 
                localName, 
                qName != null ? qName : localName, 
                "CDATA", 
                value);
        }
        
        @Override
        public void namespace(String prefix, String uri) throws SAXException {
            // If we're inside a deferred start tag, queue the namespace declaration
            // Otherwise emit it immediately
            if (inStartTag) {
                pendingNamespaces.add(new String[] { 
                    prefix != null ? prefix : "", 
                    uri != null ? uri : "" 
                });
            } else {
                handler.startPrefixMapping(prefix != null ? prefix : "", uri != null ? uri : "");
            }
        }
        
        @Override
        public void characters(String text) throws SAXException {
            flush();
            char[] ch = text.toCharArray();
            handler.characters(ch, 0, ch.length);
        }
        
        @Override
        public void charactersRaw(String text) throws SAXException {
            // No raw character support in basic ContentHandler
            characters(text);
        }
        
        @Override
        public void comment(String text) throws SAXException {
            flush();
            // ContentHandler doesn't have comment() - would need LexicalHandler
            if (handler instanceof LexicalHandler) {
                char[] ch = text.toCharArray();
                ((LexicalHandler) handler).comment(ch, 0, ch.length);
            }
        }
        
        @Override
        public void processingInstruction(String target, String data) throws SAXException {
            flush();
            handler.processingInstruction(target, data);
        }
        
        @Override
        public void flush() throws SAXException {
            if (inStartTag) {
                // Emit namespace declarations first (SAX requires startPrefixMapping before startElement)
                for (String[] ns : pendingNamespaces) {
                    handler.startPrefixMapping(ns[0], ns[1]);
                }
                handler.startElement(pendingUri, pendingLocalName, pendingQName, pendingAttrs);
                inStartTag = false;
                pendingAttrs.clear();
                pendingNamespaces.clear();
            }
        }
    }

}

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
import org.bluezoo.gonzalez.transform.compiler.StylesheetCompiler;
import org.bluezoo.gonzalez.transform.compiler.TemplateParameter;
import org.bluezoo.gonzalez.transform.compiler.TemplateRule;
import org.bluezoo.gonzalez.transform.xpath.XPathExpression;
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
import org.bluezoo.gonzalez.transform.compiler.SequenceBuilderOutputHandler;

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
    private final OutputHandler outputHandler;
    
    /** The template matcher for finding matching templates. */
    private final TemplateMatcher matcher;
    
    /** The error listener for reporting transformation errors. */
    private final javax.xml.transform.ErrorListener errorListener;
    
    /** Initial template name for XSLT 2.0+ initial-template support. */
    private String initialTemplate;
    
    /** The initial mode for apply-templates. */
    private String initialMode;

    /** XPath expression to select the initial context node from the source tree. */
    private String initialContextSelect;
    
    /** PSVIProvider for accessing schema type information (DTD/XSD types). */
    private PSVIProvider psviProvider;

    // Document building state
    private StreamingNode root;
    private StreamingNode currentNode;
    private long documentOrderCounter = 0;
    private Map<String, String> pendingNamespaces = new HashMap<>();
    private final Map<String, String> reusableNsBindings = new HashMap<>();
    private StringBuilder textBuffer = new StringBuilder();
    private Locator documentLocator;

    /**
     * Creates a transform handler.
     *
     * @param stylesheet the compiled stylesheet
     * @param parameters transformation parameters
     * @param outputHandler the output target
     */
    public GonzalezTransformHandler(CompiledStylesheet stylesheet, 
                                    Map<String, Object> parameters,
                                    OutputHandler outputHandler) {
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
                                    OutputHandler outputHandler,
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
     * Sets the initial mode for the transformation.
     *
     * @param mode the initial mode name
     */
    public void setInitialMode(String mode) {
        this.initialMode = mode;
    }

    /**
     * Sets an XPath expression to select the initial context node from the
     * source tree. Used when the source specifies a select attribute.
     *
     * @param xpath the XPath expression to evaluate against the source tree
     */
    public void setInitialContextSelect(String xpath) {
        this.initialContextSelect = xpath;
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
    public void setDocumentLocator(Locator locator) {
        this.documentLocator = locator;
    }
    
    @Override
    public void startDocument() throws SAXException {
        // Initialize the document root with the base URI from the locator
        String baseURI = documentLocator != null ? documentLocator.getSystemId() : null;
        root = StreamingNode.createRoot(baseURI);
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
        
        // Build namespace bindings (inherit from parent + pending, reuse map)
        reusableNsBindings.clear();
        if (currentNode instanceof StreamingNode) {
            reusableNsBindings.putAll(((StreamingNode) currentNode).getNamespaceBindings());
        }
        reusableNsBindings.putAll(pendingNamespaces);
        pendingNamespaces.clear();
        
        // Create element node - pass PSVIProvider for type information if available
        StreamingNode element = StreamingNode.createElement(
            uri, localName, prefix, atts, reusableNsBindings, currentNode, documentOrderCounter, psviProvider);
        // Account for element + namespace nodes + attribute nodes
        int nsCount = element.getNamespaceNodeCount();
        documentOrderCounter += nsCount + atts.getLength() + 1;
        
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
        // Ignorable whitespace (from DTD element-only content) is not included
        // in the XSLT source tree per the XML information set specification
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
        
        // Use CompiledStylesheet's method which handles import precedence correctly
        return stylesheet.shouldStripWhitespace(namespaceURI, localName);
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
        OutputHandler output = outputHandler;
        
        // Determine initial context node (may be overridden by initialContextSelect)
        XPathNode contextNode = root;
        if (initialContextSelect != null) {
            try {
                BasicTransformContext selectCtx = new BasicTransformContext(
                    stylesheet, root, matcher, output, errorListener);
                XPathExpression selectExpr = XPathExpression.compile(initialContextSelect, null);
                XPathValue selectResult = selectExpr.evaluate(selectCtx);
                if (selectResult instanceof XPathNodeSet) {
                    XPathNodeSet ns = (XPathNodeSet) selectResult;
                    if (ns.isEmpty()) {
                        throw new SAXException("XPDY0002: Initial context select returned empty node-set");
                    }
                    contextNode = ns.getNodes().get(0);
                } else {
                    throw new SAXException("XPDY0002: Initial context select did not return a node");
                }
            } catch (SAXException e) {
                throw e;
            } catch (Exception e) {
                throw new SAXException("XPDY0002: Error evaluating initial context select: " + e.getMessage(), e);
            }
        }

        // Create initial context (principal output is now set via constructor)
        BasicTransformContext context = new BasicTransformContext(
            stylesheet, contextNode, matcher, output, errorListener);
        
        // Wire accumulators for the main document
        if (!stylesheet.getAccumulators().isEmpty()) {
            AccumulatorManager mgr = new AccumulatorManager(stylesheet, context);
            context.setAccumulatorManager(mgr);
            mgr.initialize();
        }
        
        // Initialize global variables and parameters
        initializeGlobals(context);
        
        // Start output document
        output.startDocument();
        
        // XSLT 3.0: if no initial mode specified, use the stylesheet's default-mode
        if (initialMode == null) {
            String stylesheetDefaultMode = stylesheet.getDefaultMode();
            if (stylesheetDefaultMode != null) {
                initialMode = stylesheetDefaultMode;
            }
        }
        
        // Validate initial mode if specified (XTDE0045)
        // #unnamed and #default refer to the built-in default mode, always valid
        if (initialMode != null
                && !"#unnamed".equals(initialMode)
                && !"#default".equals(initialMode)) {
            boolean modeDeclared = false;
            // Check template rules
            for (TemplateRule rule : stylesheet.getTemplateRules()) {
                String ruleMode = rule.getMode();
                if (initialMode.equals(ruleMode)) {
                    modeDeclared = true;
                    break;
                }
            }
            // Also check xsl:mode declarations (XSLT 3.0 allows declared modes with no templates)
            if (!modeDeclared && stylesheet.getModeDeclaration(initialMode) != null) {
                modeDeclared = true;
            }
            if (!modeDeclared) {
                throw new SAXException("XTDE0045: Initial mode '" + initialMode +
                    "' is not declared in the stylesheet");
            }
        }

        // Check for initial template (XSLT 2.0+ feature)
        if (initialTemplate != null) {
                // Call the named template directly
                TemplateRule template = stylesheet.getNamedTemplate(initialTemplate);
                if (template == null) {
                    throw new SAXException("XTDE0040: Initial template '" + initialTemplate + "' not found");
                }
                // XTDE0700: initial template must not have required parameters
                for (TemplateParameter templateParam : template.getParameters()) {
                    if (templateParam.isRequired()) {
                        throw new SAXException("XTDE0700: Initial template parameter $" +
                            templateParam.getLocalName() + " is required but no value was supplied");
                    }
                }
                // Execute the named template with document root as context node
                TransformContext templateContext = context.pushVariableScope();
                XSLTNode body = template.getBody();
                if (body != null) {
                    body.execute(templateContext, output);
                }
            } else {
                // Check for xsl:initial-template (XSLT 3.0 feature)
                // The template name "xsl:initial-template" in the XSLT namespace is special
                // Try both the prefixed form and the expanded Clark notation form
                TemplateRule xslInitialTemplate = stylesheet.getNamedTemplate("xsl:initial-template");
                if (xslInitialTemplate == null) {
                    xslInitialTemplate = stylesheet.getNamedTemplate("{http://www.w3.org/1999/XSL/Transform}initial-template");
                }
                
                if (xslInitialTemplate != null) {
                    // xsl:initial-template exists - invoke it with document root as context
                    TransformContext templateContext = context.pushVariableScope();
                    XSLTNode body = xslInitialTemplate.getBody();
                    if (body != null) {
                        body.execute(templateContext, output);
                    }
                } else {
                    // Apply templates to the root node
                    applyTemplates(root, initialMode, context, output);
                }
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
        
        // Note: XTDE0050 (required parameter validation) is not implemented because
        // the test framework doesn't support passing external stylesheet parameters.
        // The getRequiredParameters() method exists for future use.
        
        // Evaluate global variables with forward reference support
        // Use multi-pass: keep evaluating until all done or no progress (circular reference)
        List<GlobalVariable> allVars = stylesheet.getGlobalVariables();
        Set<String> evaluated = new HashSet<String>();
        Set<String> beingEvaluated = new HashSet<String>();
        
        // Mark parameters that were already set from external parameters
        for (GlobalVariable var : allVars) {
            String key = makeVarKey(var);
            if (var.isParam()) {
                try {
                    if (context.getVariable(var.getNamespaceURI(), var.getLocalName()) != null) {
                        evaluated.add(key);
                    }
                } catch (XPathVariableException e) {
                    // Not yet set, will be evaluated below
                }
            }
        }
        
        // Multi-pass evaluation with circular reference detection
        // Enable strict mode to throw exceptions for undefined variables
        context.setThrowOnUndefinedVariable(true);
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
                    // Track this variable as being evaluated (at context level for key() detection)
                    beingEvaluated.add(key);
                    context.startVariableEvaluation(key);
                    XPathValue value = evaluateGlobalVariable(var, context, beingEvaluated, evaluated);
                    context.endVariableEvaluation(key);
                    beingEvaluated.remove(key);
                    
                    if (value != null) {
                        context.setVariable(var.getNamespaceURI(), var.getLocalName(), value);
                        evaluated.add(key);
                        progress = true;
                    }
                } catch (CircularReferenceException e) {
                    throw new SAXException("XTDE0640: Circular reference in variable: " + var.getName());
                } catch (XPathVariableException e) {
                    // Check if this is a circular reference error
                    if (e.getMessage() != null && e.getMessage().contains("XTDE0640")) {
                        throw new SAXException(e.getMessage());
                    }
                    // Variable references an unevaluated variable - try again later
                    context.endVariableEvaluation(key);
                    beingEvaluated.remove(key);
                } catch (Exception e) {
                    // Check if the root cause is a circular reference or undefined variable
                    Throwable cause = e;
                    boolean isUndefinedVar = false;
                    while (cause != null) {
                        if (cause instanceof XPathVariableException) {
                            String msg = cause.getMessage();
                            if (msg != null && msg.contains("XTDE0640")) {
                                throw new SAXException(msg);
                            }
                            // This is an undefined variable - might be evaluated later
                            isUndefinedVar = true;
                        }
                        cause = cause.getCause();
                    }
                    // If not a variable error, this is a real problem - rethrow
                    if (!isUndefinedVar) {
                        throw new SAXException("Error evaluating variable: " + var.getName(), e);
                    }
                    // Variable references an unevaluated variable - try again later
                    context.endVariableEvaluation(key);
                    beingEvaluated.remove(key);
                }
            }
        }
        // Disable strict mode after initialization
        context.setThrowOnUndefinedVariable(false);
        
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
                // Check if this is a sequence or single node type
                if (var.isSequenceType() || var.isSingleNodeType()) {
                    // Use sequence construction to properly capture nodes
                    SequenceBuilderOutputHandler seqBuilder = 
                        new SequenceBuilderOutputHandler();
                    var.getContent().execute(context, seqBuilder);
                    return seqBuilder.getSequence();
                }
                
                // Execute content and capture as result tree fragment (default)
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
        
        // Fire pre-descent accumulator rules
        AccumulatorManager mgr = context.getAccumulatorManager();
        if (mgr != null) {
            mgr.notifyStartElement(node);
        }
        
        // Use withXsltCurrentNode to set both context node and XSLT current() node
        TransformContext nodeContext = context.withXsltCurrentNode(node);
        if (mode != null) {
            nodeContext = ((BasicTransformContext) nodeContext).withMode(mode);
        }
        
        TemplateRule rule = matcher.findMatch(node, mode, nodeContext);
        
        if (rule != null) {
            executeTemplate(rule, node, (BasicTransformContext) nodeContext, output);
        }
        
        // Fire post-descent accumulator rules
        if (mgr != null) {
            mgr.notifyEndElement(node);
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
            // XTDE0700: required parameter must be supplied by caller
            if (templateParam.isRequired()) {
                throw new SAXException("XTDE0700: Template parameter $" +
                    templateParam.getLocalName() + " is required but no value was supplied");
            }
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
                String textValue = node.getStringValue();
                if (textValue != null && !textValue.isEmpty()) {
                    output.characters(textValue);
                }
                break;
                
            case "shallow-skip":
                // Process children but don't copy the node itself
                if (node.isElement() || node.isRoot()) {
                    applyTemplatesToChildren(node, context.getCurrentMode(), context, output);
                }
                break;
                
            case "shallow-copy": {
                // Copy the node but process children via apply-templates
                if (node.isElement()) {
                    String nsUri = node.getNamespaceURI();
                    String localName = node.getLocalName();
                    String prefix = node.getPrefix();
                    String qName = (prefix != null && !prefix.isEmpty()) 
                        ? prefix + ":" + localName : localName;
                    output.startElement(nsUri != null ? nsUri : "", localName, qName);
                    // Copy attributes
                    java.util.Iterator<XPathNode> attrIter = node.getAttributes();
                    while (attrIter.hasNext()) {
                        XPathNode attr = attrIter.next();
                        String aUri = attr.getNamespaceURI();
                        String aLocal = attr.getLocalName();
                        String aPrefix = attr.getPrefix();
                        String aQName = (aPrefix != null && !aPrefix.isEmpty())
                            ? aPrefix + ":" + aLocal : aLocal;
                        output.attribute(aUri != null ? aUri : "", aLocal, aQName, attr.getStringValue());
                    }
                    applyTemplatesToChildren(node, context.getCurrentMode(), context, output);
                    output.endElement(nsUri != null ? nsUri : "", localName, qName);
                } else if (node.isText()) {
                    String txt = node.getStringValue();
                    if (txt != null) {
                        output.characters(txt);
                    }
                } else if (node.isRoot()) {
                    applyTemplatesToChildren(node, context.getCurrentMode(), context, output);
                }
                break;
            }
                
            case "deep-copy":
                // Copy the entire subtree
                copyNodeDeep(node, output);
                break;
                
            case "empty":
                break;
            case "fail":
                throw new SAXException("XTDE0555: No matching template found for node: " +
                    node.getNodeType() + " (mode has on-no-match='fail')");

        }
    }

    /**
     * Deep copies a node and all its descendants to the output.
     */
    private void copyNodeDeep(XPathNode node, OutputHandler output) throws SAXException {
        if (node.isElement()) {
            String nsUri = node.getNamespaceURI();
            String localName = node.getLocalName();
            String prefix = node.getPrefix();
            String qName = (prefix != null && !prefix.isEmpty()) 
                ? prefix + ":" + localName : localName;
            output.startElement(nsUri != null ? nsUri : "", localName, qName);
            Iterator<XPathNode> children = node.getChildren();
            while (children.hasNext()) {
                copyNodeDeep(children.next(), output);
            }
            output.endElement(nsUri != null ? nsUri : "", localName, qName);
        } else if (node.isText()) {
            String text = node.getStringValue();
            if (text != null) {
                output.characters(text);
            }
        } else if (node.isRoot()) {
            Iterator<XPathNode> children = node.getChildren();
            while (children.hasNext()) {
                copyNodeDeep(children.next(), output);
            }
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
     * Adapter to use ContentHandler as OutputHandler.
     *
     * <p>This adapter bridges between the OutputHandler interface (which has
     * deferred start tag support) and the simpler ContentHandler interface.
     */
    static class ContentHandlerOutputAdapter implements OutputHandler {
        private final ContentHandler handler;
        
        // Deferred start tag state
        private boolean inStartTag = false;
        private String pendingUri;
        private String pendingLocalName;
        private String pendingQName;
        private final AttributesImpl pendingAttrs = new AttributesImpl();
        // Pending namespace declarations for the current element
        private final List<String[]> pendingNamespaces = new ArrayList<String[]>();
        
        // XSLT 2.0 atomic value spacing state
        private boolean atomicValuePending = false;
        private boolean inAttributeContent = false;
        private boolean contentReceived = false;
        
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
            contentReceived = true;
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
            contentReceived = true;
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
        
        @Override
        public boolean isAtomicValuePending() {
            return atomicValuePending;
        }
        
        @Override
        public void setAtomicValuePending(boolean pending) {
            this.atomicValuePending = pending;
        }
        
        @Override
        public boolean isInAttributeContent() {
            return inAttributeContent;
        }
        
        @Override
        public void setInAttributeContent(boolean inAttribute) {
            this.inAttributeContent = inAttribute;
        }
        
        @Override
        public void atomicValue(org.bluezoo.gonzalez.transform.xpath.type.XPathValue value) 
                throws SAXException {
            if (value != null) {
                // In element content, add space between adjacent atomic values (XSLT 2.0+)
                // But NOT in attribute content
                if (atomicValuePending && !inAttributeContent) {
                    characters(" ");
                }
                characters(value.asString());
                atomicValuePending = true;
            }
        }

        @Override
        public boolean hasReceivedContent() {
            return contentReceived;
        }
    }

}

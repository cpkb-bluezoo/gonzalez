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
import org.bluezoo.gonzalez.transform.compiler.ComponentVisibility;
import org.bluezoo.gonzalez.transform.compiler.GlobalVariable;
import org.bluezoo.gonzalez.transform.compiler.ModeDeclaration;
import org.bluezoo.gonzalez.transform.compiler.StylesheetCompiler;
import org.bluezoo.gonzalez.transform.compiler.TemplateParameter;
import org.bluezoo.gonzalez.transform.compiler.TemplateRule;
import org.bluezoo.gonzalez.transform.compiler.UserFunction;
import org.bluezoo.gonzalez.transform.xpath.XPathExpression;
import org.bluezoo.gonzalez.transform.xpath.XPathVariableException;
import org.bluezoo.gonzalez.transform.xpath.expr.XPathException;
import org.bluezoo.gonzalez.transform.runtime.OutputHandlerUtils;
import org.bluezoo.gonzalez.transform.runtime.*;
import org.bluezoo.gonzalez.transform.xpath.type.NodeType;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNode;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNodeSet;
import org.bluezoo.gonzalez.transform.xpath.type.XPathResultTreeFragment;
import org.bluezoo.gonzalez.transform.xpath.type.SchemaContext;
import org.bluezoo.gonzalez.transform.xpath.type.SequenceType;
import org.bluezoo.gonzalez.transform.xpath.type.XPathSequence;
import org.bluezoo.gonzalez.transform.xpath.type.XPathBoolean;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNumber;
import org.bluezoo.gonzalez.transform.xpath.type.XPathString;
import org.bluezoo.gonzalez.transform.xpath.type.XPathUntypedAtomic;
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
    private List<GonzalezTransformer.InitialTemplateParam> initialTemplateParams;
    
    private boolean hasInitialContextItem = true;
    
    /** The initial mode for apply-templates. */
    private String initialMode;
    private String initialModeSelect;
    private boolean hasMatchSelection = true;

    /** Initial function for XSLT 3.0 initial-function support. */
    private String initialFunctionNsUri;
    private String initialFunctionLocalName;
    private List<String> initialFunctionParams;

    /** XPath expression to select the initial context node from the source tree. */
    private String initialContextSelect;
    
    /** Registered fn:collection() mappings (URI → list of nodes). */
    private Map<String, List<XPathNode>> collections;

    /** Registered fn:uri-collection() mappings (URI → list of URI strings). */
    private Map<String, List<String>> collectionUris;

    /** Resource URIs declared available by the test environment. */
    private List<String> availableResourceUris;
    
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

    // Buffer for unparsed entity declarations received before startDocument()
    private List<String[]> pendingUnparsedEntities;

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
     * Sets whether a real initial context item is available.
     * When false, accessing the focus in the initial template raises XPDY0002.
     *
     * @param has true if an initial context item was provided
     */
    public void setHasInitialContextItem(boolean has) {
        this.hasInitialContextItem = has;
    }

    /**
     * Sets the parameters to be passed to the initial template.
     *
     * @param params the initial template parameters
     */
    public void setInitialTemplateParams(
            List<GonzalezTransformer.InitialTemplateParam> params) {
        this.initialTemplateParams = params;
    }
    
    /**
     * Sets the initial function for XSLT 3.0 support.
     *
     * @param nsUri the function namespace URI
     * @param localName the function local name
     * @param paramSelects XPath expressions for each parameter
     */
    public void setInitialFunction(String nsUri, String localName,
                                   List<String> paramSelects) {
        this.initialFunctionNsUri = nsUri;
        this.initialFunctionLocalName = localName;
        this.initialFunctionParams = paramSelects;
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
     * Sets whether an initial match selection (source document) was provided.
     * When false and an initial mode is specified, XTDE0044 is raised.
     *
     * @param has true if a real source document is provided
     */
    public void setHasMatchSelection(boolean has) {
        this.hasMatchSelection = has;
    }

    /**
     * Sets an XPath expression for the initial match selection.
     *
     * @param xpath the XPath expression
     */
    public void setInitialModeSelect(String xpath) {
        this.initialModeSelect = xpath;
    }

    /**
     * Registers a named collection for the fn:collection() function.
     *
     * @param uri the collection URI
     * @param nodes the list of nodes in the collection
     */
    public void setCollection(String uri, List<XPathNode> nodes) {
        if (collections == null) {
            collections = new HashMap<>();
        }
        collections.put(uri, nodes);
    }

    /**
     * Registers URI strings for a named collection (fn:uri-collection).
     *
     * @param uri the collection URI
     * @param uris the list of document URIs in the collection
     */
    public void setCollectionUris(String uri, List<String> uris) {
        if (collectionUris == null) {
            collectionUris = new HashMap<>();
        }
        collectionUris.put(uri, uris);
    }

    /**
     * Registers resource URIs declared available by the test environment.
     * These URIs will be reported as available by unparsed-text-available()
     * without requiring actual network access.
     *
     * @param uris the list of available resource URIs
     */
    public void setAvailableResourceUris(List<String> uris) {
        this.availableResourceUris = uris;
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

        if (pendingUnparsedEntities != null) {
            for (String[] entity : pendingUnparsedEntities) {
                root.addUnparsedEntity(entity[0], entity[1], entity[2], entity[3]);
            }
            pendingUnparsedEntities = null;
        }
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
        
        // Track base URI from external entity expansion
        setEntityBaseURIIfNeeded(element);
        
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
        StreamingNode pi = StreamingNode.createPI(target, data, currentNode, documentOrderCounter++);
        setEntityBaseURIIfNeeded(pi);
    }

    // LexicalHandler methods
    
    @Override
    public void comment(char[] ch, int start, int length) throws SAXException {
        flushTextBuffer();
        String text = new String(ch, start, length);
        StreamingNode.createComment(text, currentNode, documentOrderCounter++);
    }
    
    /**
     * Records an unparsed entity declaration from the DTD so that
     * unparsed-entity-uri() and unparsed-entity-public-id() can look it up.
     */
    public void unparsedEntityDecl(String name, String publicId,
                                   String systemId, String notationName) {
        if (root != null) {
            root.addUnparsedEntity(name, publicId, systemId, notationName);
        } else {
            if (pendingUnparsedEntities == null) {
                pendingUnparsedEntities = new ArrayList<>();
            }
            pendingUnparsedEntities.add(new String[] { name, publicId, systemId, notationName });
        }
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

    /**
     * Sets the entity base URI on a node if the current locator's system ID
     * differs from the document root's base URI. This happens when the node
     * originates from an expanded external entity.
     */
    private void setEntityBaseURIIfNeeded(StreamingNode node) {
        if (documentLocator == null || root == null) {
            return;
        }
        String currentSystemId = documentLocator.getSystemId();
        if (currentSystemId == null) {
            return;
        }
        String docBaseURI = root.getBaseURI();
        if (!currentSystemId.equals(docBaseURI)) {
            node.setEntityBaseURI(currentSystemId);
        }
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
        
        // Register collections for fn:collection() and fn:uri-collection()
        if (collections != null) {
            for (Map.Entry<String, List<XPathNode>> entry : collections.entrySet()) {
                context.setCollection(entry.getKey(), entry.getValue());
            }
        }
        if (collectionUris != null) {
            for (Map.Entry<String, List<String>> entry : collectionUris.entrySet()) {
                context.setCollectionUris(entry.getKey(), entry.getValue());
            }
        }

        if (availableResourceUris != null) {
            for (String resUri : availableResourceUris) {
                context.addAvailableResourceUri(resUri);
            }
        }
        
        // Wire accumulators for the main document (manager created now,
        // but initialization deferred until globals are available)
        AccumulatorManager accMgr = null;
        if (!stylesheet.getAccumulators().isEmpty()) {
            accMgr = new AccumulatorManager(stylesheet, context);
            context.setAccumulatorManager(accMgr);
        }
        
        // XSLT 3.0 xsl:global-context-item enforcement
        String globalContextUse = stylesheet.getGlobalContextItemUse();
        if ("required".equals(globalContextUse) && !hasInitialContextItem) {
            throw new SAXException("XTDE3086: A global context item is required "
                + "(xsl:global-context-item use=\"required\") but none was supplied");
        }
        if ("absent".equals(globalContextUse)) {
            context.setContextItemUndefined(true);
        }

        // XSLT 3.0 §9.5: when using initial-template invocation with no
        // source document, the context item is absent for global variables.
        if (initialTemplate != null && !hasInitialContextItem) {
            context.setContextItemUndefined(true);
        }
        if (initialTemplate == null && !hasInitialContextItem) {
            TemplateRule xslInit =
                stylesheet.getNamedTemplate("xsl:initial-template");
            if (xslInit == null) {
                xslInit = stylesheet.getNamedTemplate(
                    "{http://www.w3.org/1999/XSL/Transform}initial-template");
            }
            if (xslInit != null) {
                context.setContextItemUndefined(true);
            }
        }
        
        // Initialize global variables and parameters
        initializeGlobals(context);
        
        // Initialize and pre-traverse accumulators after globals are available,
        // since initial-value expressions and rules may reference global variables
        if (accMgr != null) {
            accMgr.initialize();
            accMgr.preTraverseDocument(contextNode);
        }
        
        // Start output document
        output.startDocument();
        
        // XTDE0047: specifying both initial mode and initial template is an error
        if (initialMode != null && initialTemplate != null) {
            throw new SAXException("XTDE0047: Both initial-mode and " +
                "initial-template were specified; only one is allowed");
        }

        // XTDE0044: initial mode specified but no initial match selection
        if (initialMode != null && !hasMatchSelection) {
            throw new SAXException("XTDE0044: Initial mode '" + initialMode +
                "' was specified but no initial match selection was supplied");
        }

        // XSLT 3.0: if no initial mode specified and not invoking by named template,
        // use the stylesheet's default-mode for modal processing.
        // Skip when the stylesheet has xsl:initial-template, since named template
        // invocation does not use the initial mode.
        if (initialMode == null && initialTemplate == null
                && initialFunctionLocalName == null) {
            boolean hasXslInitialTemplate =
                stylesheet.getNamedTemplate("xsl:initial-template") != null
                || stylesheet.getNamedTemplate(
                    "{http://www.w3.org/1999/XSL/Transform}initial-template") != null;
            if (!hasXslInitialTemplate) {
                String stylesheetDefaultMode = stylesheet.getDefaultMode();
                if (stylesheetDefaultMode != null) {
                    initialMode = stylesheetDefaultMode;
                }
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
            // XTDE0045: private mode cannot be used as initial mode
            ModeDeclaration modeDecl = stylesheet.getModeDeclaration(initialMode);
            if (modeDecl != null) {
                ModeDeclaration.Visibility vis = modeDecl.getVisibility();
                if (vis == ModeDeclaration.Visibility.PRIVATE) {
                    throw new SAXException("XTDE0045: Initial mode '" + initialMode +
                        "' has private visibility and cannot be used as an initial mode");
                }
            }
        }

        // Check for initial function (XSLT 3.0 feature)
        if (initialFunctionLocalName != null) {
            String nsUri = initialFunctionNsUri != null ? initialFunctionNsUri : "";
            int arity = initialFunctionParams != null ? initialFunctionParams.size() : 0;
            UserFunction func = stylesheet.getUserFunction(nsUri, initialFunctionLocalName, arity);
            if (func == null) {
                throw new SAXException("XTDE0041: Initial function {" + nsUri + "}" +
                    initialFunctionLocalName + "#" + arity + " not found");
            }
            // Check visibility - private functions cannot be called as initial function
            if (func.getVisibility() == ComponentVisibility.PRIVATE) {
                throw new SAXException("XTDE0041: Initial function {" + nsUri + "}" +
                    initialFunctionLocalName + " has private visibility");
            }
            // Evaluate parameter expressions
            List<XPathValue> argValues = new ArrayList<>();
            if (initialFunctionParams != null) {
                for (String paramExpr : initialFunctionParams) {
                    try {
                        XPathExpression expr = XPathExpression.compile(paramExpr, null);
                        XPathValue val = expr.evaluate(context);
                        argValues.add(val);
                    } catch (Exception e) {
                        throw new SAXException("Error evaluating initial-function parameter: " +
                            paramExpr + " - " + e.getMessage(), e);
                    }
                }
            }
            // Create function execution context and bind parameters
            TransformContext funcContext = context.pushVariableScope()
                .withContextNode(null).withNoTunnelParameters();
            if (funcContext instanceof BasicTransformContext) {
                ((BasicTransformContext) funcContext).setContextItemUndefined(true);
            }
            List<UserFunction.FunctionParameter> params = func.getParameters();
            for (int i = 0; i < params.size() && i < argValues.size(); i++) {
                XPathValue argVal = argValues.get(i);
                String paramAsType = params.get(i).getAsType();
                if (paramAsType != null) {
                    SequenceType expectedType = SequenceType.parse(paramAsType, null);
                    if (expectedType != null && !expectedType.matches(argVal)) {
                        throw new SAXException("XPTY0004: Initial function parameter $" +
                            params.get(i).getLocalName() + ": required type is " +
                            paramAsType + ", supplied value does not match");
                    }
                }
                funcContext.getVariableScope().bind(params.get(i).getNamespaceURI(), params.get(i).getLocalName(), argVal);
            }
            // Execute the function body directly to the output
            XSLTNode body = func.getBody();
            if (body != null) {
                body.execute(funcContext, output);
            }
        } else if (initialTemplate != null) {
                // Call the named template directly
                TemplateRule template = stylesheet.getNamedTemplate(initialTemplate);
                if (template == null) {
                    throw new SAXException("XTDE0040: Initial template '" + initialTemplate + "' not found");
                }
                // XTDE0060: XSLT 2.0 does not support passing tunnel params
                // to the initial template
                double procVersion = stylesheet.getProcessorVersion();
                if (procVersion > 0 && procVersion < 3.0
                        && initialTemplateParams != null) {
                    for (GonzalezTransformer.InitialTemplateParam itp : initialTemplateParams) {
                        if (itp.isTunnel()) {
                            throw new SAXException(
                                "XTDE0060: Tunnel parameters cannot be " +
                                "passed to the initial template in XSLT " +
                                procVersion);
                        }
                    }
                }
                // XTDE0700: initial template must not have required parameters
                // (unless values are supplied via initial template params)
                for (TemplateParameter templateParam : template.getParameters()) {
                    if (templateParam.isRequired()
                            && !hasSuppliedInitialParam(templateParam)) {
                        throw new SAXException("XTDE0700: Initial template parameter $" +
                            templateParam.getLocalName() + " is required but no value was supplied");
                    }
                }
                // Execute the named initial template
                TransformContext templateContext = context.pushVariableScope();
                // XPDY0002: when no initial context item was provided,
                // accessing the focus inside the template is a dynamic error
                if (!hasInitialContextItem) {
                    templateContext = templateContext.withContextNode(null);
                    ((BasicTransformContext) templateContext).setContextItemUndefined(true);
                }
                // If the template also has a match pattern, invoke as if matched
                // (XSLT 3.0 §2.4: current template rule is set)
                if (template.getMatchPattern() != null) {
                    templateContext = templateContext.withCurrentTemplateRule(template);
                }
                templateContext = bindInitialTemplateParams(template, templateContext);
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
                    TransformContext templateContext = context.pushVariableScope();
                    if (!hasInitialContextItem) {
                        templateContext = templateContext.withContextNode(null);
                        ((BasicTransformContext) templateContext)
                            .setContextItemUndefined(true);
                    }
                    if (xslInitialTemplate.getMatchPattern() != null) {
                        templateContext = templateContext.withCurrentTemplateRule(xslInitialTemplate);
                    }
                    templateContext = bindInitialTemplateParams(xslInitialTemplate, templateContext);
                    XSLTNode body = xslInitialTemplate.getBody();
                    if (body != null) {
                        body.execute(templateContext, output);
                    }
                } else if (initialModeSelect != null) {
                    applyTemplatesWithInitialSelect(
                        contextNode, initialMode, initialModeSelect, context, output);
                } else if (initialTemplateParams != null && !initialTemplateParams.isEmpty()
                        && initialMode != null) {
                    applyTemplatesWithInitialModeParams(
                        contextNode, initialMode, context, output);
                } else {
                    applyTemplates(contextNode, initialMode, context, output);
                }
            }
            
            // End output document
            output.endDocument();
    }

    /**
     * Checks whether a supplied initial template param matches the given
     * template parameter (by namespace URI, local name, and tunnel flag).
     */
    private boolean hasSuppliedInitialParam(TemplateParameter templateParam) {
        if (initialTemplateParams == null) {
            return false;
        }
        String paramNsUri = templateParam.getNamespaceURI();
        if (paramNsUri == null) {
            paramNsUri = "";
        }
        for (GonzalezTransformer.InitialTemplateParam itp : initialTemplateParams) {
            String itpNsUri = itp.getNsUri();
            if (itpNsUri == null) {
                itpNsUri = "";
            }
            if (itpNsUri.equals(paramNsUri)
                    && itp.getLocalName().equals(templateParam.getLocalName())
                    && itp.isTunnel() == templateParam.isTunnel()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Binds template parameters for initial-template invocation.
     * If initial template params were supplied, uses them for matching
     * parameters; otherwise falls back to default values.
     * Tunnel params are set on the context so they propagate through
     * subsequent template calls.
     */
    private TransformContext bindInitialTemplateParams(TemplateRule template,
                                           TransformContext templateContext)
        throws SAXException
    {
        // Set tunnel parameters from supplied initial template params
        if (initialTemplateParams != null) {
            Map<String, XPathValue> tunnelParams = new HashMap<String, XPathValue>();
            for (GonzalezTransformer.InitialTemplateParam itp : initialTemplateParams) {
                if (itp.isTunnel()) {
                    String nsUri = itp.getNsUri();
                    if (nsUri == null) {
                        nsUri = "";
                    }
                    String expanded;
                    if (nsUri.isEmpty()) {
                        expanded = itp.getLocalName();
                    } else {
                        expanded = "{" + nsUri + "}" + itp.getLocalName();
                    }
                    try {
                        XPathExpression expr = XPathExpression.compile(
                            itp.getSelectExpr(), null);
                        XPathValue value = expr.evaluate(templateContext);
                        tunnelParams.put(expanded, value);
                    } catch (Exception e) {
                        throw new SAXException(
                            "Error evaluating initial template param: " +
                            e.getMessage(), e);
                    }
                }
            }
            if (!tunnelParams.isEmpty()) {
                templateContext = templateContext.withTunnelParameters(tunnelParams);
            }
        }

        for (TemplateParameter templateParam : template.getParameters()) {
            XPathValue value = null;
            boolean supplied = false;

            // Check for a supplied initial template param
            if (initialTemplateParams != null) {
                String paramNsUri = templateParam.getNamespaceURI();
                if (paramNsUri == null) {
                    paramNsUri = "";
                }
                for (GonzalezTransformer.InitialTemplateParam itp : initialTemplateParams) {
                    String itpNsUri = itp.getNsUri();
                    if (itpNsUri == null) {
                        itpNsUri = "";
                    }
                    if (itpNsUri.equals(paramNsUri)
                            && itp.getLocalName().equals(templateParam.getLocalName())
                            && itp.isTunnel() == templateParam.isTunnel()) {
                        try {
                            XPathExpression expr = XPathExpression.compile(
                                itp.getSelectExpr(), null);
                            value = expr.evaluate(templateContext);
                            supplied = true;
                        } catch (Exception e) {
                            throw new SAXException(
                                "Error evaluating initial template param: " +
                                e.getMessage(), e);
                        }
                        break;
                    }
                }
            }

            // For tunnel params not directly supplied, check context tunnel params
            if (!supplied && templateParam.isTunnel()) {
                value = templateContext.getTunnelParameters().get(
                    templateParam.getName());
                supplied = (value != null);
            }

            // Fall back to default value
            if (!supplied) {
                if (templateParam.getSelectExpr() != null) {
                    try {
                        value = templateParam.getSelectExpr().evaluate(templateContext);
                    } catch (XPathException e) {
                        throw new SAXException("Error evaluating param default: " +
                            e.getMessage(), e);
                    }
                } else {
                    value = templateParam.evaluateDefaultContent(templateContext);
                }
            }

            if (value != null) {
                try {
                    value = templateParam.coerceDefaultValue(value);
                } catch (XPathException e) {
                    throw new SAXException("Error coercing parameter default: " +
                        e.getMessage(), e);
                }
                templateContext.getVariableScope().bind(
                    templateParam.getNamespaceURI(),
                    templateParam.getLocalName(),
                    value);
            }
        }
        return templateContext;
    }

    /**
     * Applies templates to the initial node with initial-mode params.
     * Finds the matching template, then binds the initial-mode params
     * to the template's declared parameters (instead of using defaults).
     */
    private void applyTemplatesWithInitialModeParams(XPathNode node,
            String mode, BasicTransformContext context,
            OutputHandler output) throws SAXException
    {
        AccumulatorManager accMgr = context.getAccumulatorManager();
        if (accMgr != null) {
            accMgr.notifyStartElement(node);
        }

        TransformContext nodeContext = context.withXsltCurrentNode(node);
        nodeContext = ((BasicTransformContext) nodeContext).withMode(mode);

        TemplateRule rule = matcher.findMatch(node, mode, nodeContext);
        if (rule != null && !TemplateMatcher.isBuiltIn(rule)) {
            TransformContext execContext = nodeContext.pushVariableScope()
                .withCurrentTemplateRule(rule);

            // Evaluate supplied param values
            Map<String, XPathValue> suppliedNonTunnel =
                new HashMap<String, XPathValue>();
            Map<String, XPathValue> suppliedTunnel =
                new HashMap<String, XPathValue>();
            for (GonzalezTransformer.InitialTemplateParam itp
                    : initialTemplateParams) {
                String nsUri = itp.getNsUri();
                if (nsUri == null) {
                    nsUri = "";
                }
                String expanded;
                if (nsUri.isEmpty()) {
                    expanded = itp.getLocalName();
                } else {
                    expanded = "{" + nsUri + "}" + itp.getLocalName();
                }
                XPathValue value;
                try {
                    XPathExpression expr = XPathExpression.compile(
                        itp.getSelectExpr(), null);
                    value = expr.evaluate(execContext);
                } catch (Exception e) {
                    throw new SAXException(
                        "Error evaluating initial mode param: " +
                        e.getMessage(), e);
                }
                if (itp.isTunnel()) {
                    suppliedTunnel.put(expanded, value);
                } else {
                    suppliedNonTunnel.put(expanded, value);
                }
            }

            if (!suppliedTunnel.isEmpty()) {
                execContext = execContext.withTunnelParameters(suppliedTunnel);
            }

            // Bind template params: use supplied values where available,
            // fall back to defaults otherwise.
            for (TemplateParameter templateParam : rule.getParameters()) {
                XPathValue value = null;
                boolean found = false;
                String paramName = templateParam.getName();

                if (templateParam.isTunnel()) {
                    value = suppliedTunnel.get(paramName);
                    if (value == null) {
                        value = execContext.getTunnelParameters().get(paramName);
                    }
                    found = (value != null);
                } else {
                    value = suppliedNonTunnel.get(paramName);
                    found = (value != null);
                }

                if (!found) {
                    if (templateParam.isRequired()) {
                        throw new SAXException("XTDE0700: Template parameter $" +
                            templateParam.getLocalName() +
                            " is required but no value was supplied");
                    }
                    if (templateParam.getSelectExpr() != null) {
                        try {
                            value = templateParam.getSelectExpr()
                                .evaluate(execContext);
                        } catch (XPathException e) {
                            throw new SAXException(
                                "Error evaluating param default: " +
                                e.getMessage(), e);
                        }
                    } else if (templateParam.getDefaultContent() != null) {
                        SAXEventBuffer buffer = new SAXEventBuffer();
                        BufferOutputHandler bufferOutput =
                            new BufferOutputHandler(buffer);
                        templateParam.getDefaultContent()
                            .execute(execContext, bufferOutput);
                        value = new XPathResultTreeFragment(buffer);
                    }
                }

                if (value != null) {
                    try {
                        value = templateParam.coerceDefaultValue(value);
                    } catch (XPathException e) {
                        throw new SAXException(
                            "Error coercing parameter: " +
                            e.getMessage(), e);
                    }
                    execContext.getVariableScope().bind(
                        templateParam.getNamespaceURI(),
                        templateParam.getLocalName(),
                        value);
                }
            }

            rule.getBody().execute(execContext, output);
        } else if (rule != null) {
            executeBuiltInTemplate(
                TemplateMatcher.getBuiltInType(rule), node,
                (BasicTransformContext) nodeContext, output);
        }

        if (accMgr != null) {
            accMgr.notifyEndElement(node);
        }
    }

    private void initializeGlobals(BasicTransformContext context) throws SAXException {
        // Set transformation parameters
        for (Map.Entry<String, Object> entry : parameters.entrySet()) {
            XPathValue value;
            Object raw = entry.getValue();
            if (raw instanceof XPathValue) {
                value = (XPathValue) raw;
            } else if (raw instanceof Number) {
                value = new XPathNumber(((Number) raw).doubleValue());
            } else if (raw instanceof Boolean) {
                value = XPathBoolean.of(((Boolean) raw).booleanValue());
            } else {
                value = XPathString.of(String.valueOf(raw));
            }
            context.setVariable(entry.getKey(), value);
        }
        
        // XTDE0050: required parameters must have a value supplied externally
        List<GlobalVariable> allGlobals = stylesheet.getGlobalVariables();
        for (GlobalVariable var : allGlobals) {
            if (var.isRequired()) {
                String paramName = var.getLocalName();
                boolean supplied = false;
                for (String key : parameters.keySet()) {
                    int braceClose = key.indexOf('}');
                    String localPart = braceClose >= 0 ? key.substring(braceClose + 1) : key;
                    if (localPart.equals(paramName)) {
                        supplied = true;
                        break;
                    }
                }
                if (!supplied) {
                    throw new SAXException("XTDE0050: No value supplied for required parameter: $" + paramName);
                }
            }
        }
        
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
                    boolean isUndefinedVar = containsUndefinedVariableException(e);
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
        
        // Pre-evaluate global variables for package stylesheets.
        // Templates/functions imported from packages may reference their own
        // package-private globals which aren't in the principal stylesheet.
        initializePackageGlobals(context);
    }
    
    private void initializePackageGlobals(BasicTransformContext context)
            throws SAXException {
        // Collect all unique package stylesheets transitively referenced by
        // templates/functions through the definingStylesheet chain
        Set<CompiledStylesheet> pkgStylesheets = new HashSet<CompiledStylesheet>();
        collectPackageStylesheets(stylesheet, pkgStylesheets);
        if (pkgStylesheets.isEmpty()) {
            return;
        }
        
        Map<CompiledStylesheet, Map<String, XPathValue>> allPkgGlobals =
            new HashMap<CompiledStylesheet, Map<String, XPathValue>>();
        
        for (CompiledStylesheet pkgSheet : pkgStylesheets) {
            Map<String, XPathValue> pkgVars = new HashMap<String, XPathValue>();
            List<GlobalVariable> pkgGlobals = pkgSheet.getGlobalVariables();
            
            // Multi-pass evaluation like the principal globals
            Set<String> pkgEvaluated = new HashSet<String>();
            BasicTransformContext pkgContext = (BasicTransformContext)
                context.withStylesheet(pkgSheet);
            
            boolean pkgProgress = true;
            while (pkgProgress) {
                pkgProgress = false;
                for (GlobalVariable var : pkgGlobals) {
                    String key = makeVarKey(var);
                    if (pkgEvaluated.contains(key)) {
                        continue;
                    }
                    try {
                        XPathValue value = evaluateGlobalVariable(
                            var, pkgContext, new HashSet<String>(), pkgEvaluated);
                        if (value != null) {
                            pkgVars.put(key, value);
                            pkgContext.setVariable(
                                var.getNamespaceURI(), var.getLocalName(), value);
                            pkgEvaluated.add(key);
                            pkgProgress = true;
                        }
                    } catch (Exception e) {
                        // Variable depends on another not yet evaluated
                        boolean isUndefined = containsUndefinedVariableException(e);
                        if (!isUndefined) {
                            throw new SAXException(
                                "Error evaluating package variable: " +
                                var.getName(), e);
                        }
                    }
                }
            }
            
            if (!pkgVars.isEmpty()) {
                allPkgGlobals.put(pkgSheet, pkgVars);
            }
        }
        
        if (!allPkgGlobals.isEmpty()) {
            context.setPackageGlobalVariables(allPkgGlobals);
        }
    }
    
    private void collectPackageStylesheets(CompiledStylesheet sheet,
            Set<CompiledStylesheet> collected) {
        for (TemplateRule rule : sheet.getTemplateRules()) {
            CompiledStylesheet ds = rule.getDefiningStylesheet();
            if (ds != null && ds != stylesheet && collected.add(ds)) {
                collectPackageStylesheets(ds, collected);
            }
        }
        for (UserFunction func : sheet.getUserFunctions().values()) {
            CompiledStylesheet ds = func.getDefiningStylesheet();
            if (ds != null && ds != stylesheet && collected.add(ds)) {
                collectPackageStylesheets(ds, collected);
            }
        }
    }
    
    private String makeVarKey(GlobalVariable var) {
        if (var.getNamespaceURI() != null && !var.getNamespaceURI().isEmpty()) {
            return "{" + var.getNamespaceURI() + "}" + var.getLocalName();
        }
        return var.getLocalName();
    }
    
    /**
     * Checks whether an exception (or any exception in its cause chain)
     * indicates an undefined variable that may be resolved on a later pass.
     * Walks both getCause() and SAXException.getException() chains.
     */
    private boolean containsUndefinedVariableException(Throwable t) {
        Set<Throwable> visited = new HashSet<Throwable>();
        while (t != null && visited.add(t)) {
            if (t instanceof XPathVariableException) {
                String msg = t.getMessage();
                if (msg != null && msg.contains("XTDE0640")) {
                    return false;
                }
                return true;
            }
            if (t.getMessage() != null && t.getMessage().contains("XPST0008")) {
                return true;
            }
            Throwable next = t.getCause();
            if (next == null && t instanceof org.xml.sax.SAXException) {
                next = ((org.xml.sax.SAXException) t).getException();
            }
            t = next;
        }
        return false;
    }
    
    private XPathValue evaluateGlobalVariable(GlobalVariable var, BasicTransformContext context,
            Set<String> beingEvaluated, Set<String> evaluated) throws Exception {
        try {
            XPathValue value;
            // Check for static variable with pre-computed value (XSLT 3.0)
            if (var.isStatic()) {
                return var.getStaticValue();
            }
            if (var.getSelectExpr() != null) {
                value = var.getSelectExpr().evaluate(context);
            } else if (var.getContent() != null) {
                // Check if this is a sequence or single node type
                if (var.isSequenceType() || var.isSingleNodeType()) {
                    // Use sequence construction to properly capture nodes
                    String varBaseUri = var.getBaseUri();
                    if (varBaseUri == null) {
                        varBaseUri = context.getStaticBaseURI();
                    }
                    SequenceBuilderOutputHandler seqBuilder = 
                        new SequenceBuilderOutputHandler(varBaseUri);
                    var.getContent().execute(context, seqBuilder);
                    value = seqBuilder.getSequence();
                } else {
                    // Execute content and capture as result tree fragment (default)
                    SAXEventBuffer buffer = new SAXEventBuffer();
                    BufferOutputHandler bufferOutput = new BufferOutputHandler(buffer);
                    var.getContent().execute(context, bufferOutput);
                    String varBaseUri = var.getBaseUri();
                    if (varBaseUri == null) {
                        varBaseUri = context.getStaticBaseURI();
                    }
                    value = new XPathResultTreeFragment(buffer, varBaseUri);
                }
            } else {
                return XPathString.of("");
            }
            return coerceGlobalValue(value, var.getAsType());
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
    
    /**
     * Coerces a global variable value to match the declared 'as' type.
     * Atomizes nodes and casts xs:untypedAtomic to the target atomic type
     * per XSLT function conversion rules.
     */
    private XPathValue coerceGlobalValue(XPathValue value, String asType) throws Exception {
        if (asType == null || value == null) {
            return value;
        }
        SequenceType parsedType = SequenceType.parse(asType, null);
        if (parsedType == null) {
            return value;
        }
        SequenceType.ItemKind kind = parsedType.getItemKind();
        if (kind != SequenceType.ItemKind.ATOMIC) {
            return value;
        }
        String targetLocal = parsedType.getLocalName();
        if (targetLocal == null) {
            return value;
        }
        if (value instanceof XPathSequence) {
            boolean needsCoercion = false;
            for (XPathValue item : (XPathSequence) value) {
                if (item instanceof XPathNode || item.isNodeSet()
                        || item instanceof XPathResultTreeFragment
                        || item instanceof XPathUntypedAtomic) {
                    needsCoercion = true;
                    break;
                }
            }
            if (!needsCoercion) {
                return value;
            }
            List<XPathValue> coerced = new ArrayList<XPathValue>();
            for (XPathValue item : (XPathSequence) value) {
                if (item instanceof XPathNode || item.isNodeSet()
                        || item instanceof XPathResultTreeFragment
                        || item instanceof XPathUntypedAtomic) {
                    String text = item.asString();
                    coerced.add(TemplateParameter.castStringToAtomicType(text, targetLocal));
                } else {
                    coerced.add(item);
                }
            }
            if (coerced.size() == 1) {
                return coerced.get(0);
            }
            return new XPathSequence(coerced);
        }
        if (value.isNodeSet()) {
            XPathNodeSet ns = value.asNodeSet();
            if (ns.isEmpty()) {
                if (parsedType.allowsEmpty()) {
                    return XPathSequence.EMPTY;
                }
                return value;
            }
            String text = value.asString();
            return TemplateParameter.castStringToAtomicType(text, targetLocal);
        }
        if (value instanceof XPathUntypedAtomic
                || value instanceof XPathResultTreeFragment) {
            String text = value.asString();
            return TemplateParameter.castStringToAtomicType(text, targetLocal);
        }
        return value;
    }

    private static class CircularReferenceException extends Exception {
        CircularReferenceException(String varName) {
            super("Circular reference: " + varName);
        }
    }

    /**
     * Evaluates the initial-mode select expression and applies templates
     * to each item in the resulting sequence. The global context item
     * remains the document root for variable evaluation.
     */
    private void applyTemplatesWithInitialSelect(XPathNode globalContext,
            String mode, String selectXPath,
            BasicTransformContext context, OutputHandler output) throws SAXException {
        try {
            XPathExpression selectExpr;
            try {
                selectExpr = XPathExpression.compile(selectXPath, null);
            } catch (Exception e) {
                throw new SAXException("Error compiling initial-mode select: "
                    + e.getMessage(), e);
            }
            XPathValue selectResult = selectExpr.evaluate(context);

            // Collect nodes and atomic values separately
            List<XPathNode> nodeItems = new ArrayList<>();
            List<XPathValue> atomicItems = new ArrayList<>();
            // Track items in order: true=node, false=atomic
            List<Object> orderedItems = new ArrayList<>();

            if (selectResult instanceof XPathSequence) {
                XPathSequence seq = (XPathSequence) selectResult;
                for (int i = 0; i < seq.size(); i++) {
                    XPathValue val = seq.get(i);
                    if (val instanceof XPathNode) {
                        orderedItems.add(val);
                    } else {
                        orderedItems.add(val);
                    }
                }
            } else if (selectResult instanceof XPathNodeSet) {
                XPathNodeSet ns = (XPathNodeSet) selectResult;
                for (XPathNode n : ns.getNodes()) {
                    orderedItems.add(n);
                }
            } else if (selectResult.isNodeSet()) {
                XPathNodeSet ns = selectResult.asNodeSet();
                for (XPathNode n : ns.getNodes()) {
                    orderedItems.add(n);
                }
            } else {
                orderedItems.add(selectResult);
            }

            int size = orderedItems.size();
            int position = 1;
            for (Object item : orderedItems) {
                if (item instanceof XPathNode) {
                    XPathNode node = (XPathNode) item;
                    BasicTransformContext nodeCtx = (BasicTransformContext) context
                        .withXsltCurrentNode(node)
                        .withPositionAndSize(position, size);
                    if (mode != null) {
                        nodeCtx = (BasicTransformContext) nodeCtx.withMode(mode);
                    }
                    TemplateRule rule = matcher.findMatch(node, mode, nodeCtx);
                    if (rule != null) {
                        executeTemplate(rule, node, nodeCtx, output);
                    }
                } else {
                    XPathValue val = (XPathValue) item;
                    BasicTransformContext atomicCtx = context.withContextItem(val);
                    atomicCtx.setXsltCurrentItem(val);
                    atomicCtx = (BasicTransformContext) atomicCtx
                        .withPositionAndSize(position, size);
                    if (mode != null) {
                        atomicCtx = (BasicTransformContext) atomicCtx.withMode(mode);
                    }
                    TemplateRule rule = matcher.findMatchForAtomicValue(
                        val, mode, atomicCtx);
                    if (rule != null) {
                        TransformContext execCtx = atomicCtx.pushVariableScope()
                            .withCurrentTemplateRule(rule);
                        rule.getBody().execute(execCtx, output);
                    }
                }
                position++;
            }
        } catch (XPathException e) {
            throw new SAXException("Error evaluating initial-mode select: "
                + e.getMessage(), e);
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
                String paramAsType = templateParam.getAsType();
                boolean needsSequenceBuilder = paramAsType != null &&
                    (paramAsType.startsWith("attribute(") ||
                     paramAsType.startsWith("node("));
                if (needsSequenceBuilder) {
                    // Types that can include standalone attribute nodes need
                    // SequenceBuilderOutputHandler (BufferOutputHandler rejects
                    // attributes at document level with XTDE0420)
                    String baseUri = context.getStaticBaseURI();
                    SequenceBuilderOutputHandler seqBuilder =
                        new SequenceBuilderOutputHandler(baseUri);
                    templateParam.getDefaultContent().execute(templateContext, seqBuilder);
                    defaultValue = seqBuilder.getSequence();
                } else {
                    // Execute content to get RTF as default value
                    SAXEventBuffer buffer = new SAXEventBuffer();
                    BufferOutputHandler bufferOutput = new BufferOutputHandler(buffer);
                    templateParam.getDefaultContent().execute(templateContext, bufferOutput);
                    defaultValue = new XPathResultTreeFragment(buffer);
                }
            } else {
                defaultValue = XPathString.of("");
            }
            try {
                defaultValue = templateParam.coerceDefaultValue(defaultValue);
            } catch (XPathException e) {
                throw new SAXException("Error coercing parameter default: " + e.getMessage(), e);
            }
            templateContext.getVariableScope().bind(templateParam.getName(), defaultValue);
        }
        
        // Execute template body, with optional return type validation
        XSLTNode body = rule.getBody();
        if (body != null) {
            // If the template requires grounded execution, run through
            // GroundedExecutor so that reverse-axis navigation works.
            BufferingStrategy strategy = rule.getBufferingStrategy();
            if (strategy == BufferingStrategy.GROUNDED && node.isFullyNavigable() == false) {
                executeGroundedTemplate(body, node, templateContext, output, rule);
                return;
            }

            String asType = rule.getAsType();
            if (asType != null && !asType.isEmpty()) {
                SequenceBuilderOutputHandler seqBuilder = new SequenceBuilderOutputHandler();
                body.execute(templateContext, seqBuilder);
                XPathValue result = seqBuilder.getSequence();
                
                SequenceType expectedType = rule.getParsedAsType();
                if (expectedType == null) {
                    expectedType = SequenceType.parse(asType, null);
                }
                if (expectedType != null && !expectedType.matches(result, SchemaContext.NONE)) {
                    String templateDesc = rule.getName() != null ?
                        "named template '" + rule.getName() + "'" :
                        "template matching '" + rule.getMatchPattern() + "'";
                    throw new SAXException("XTTE0505: Required item type of " +
                        templateDesc + " is " + asType +
                        "; supplied value does not match");
                }
                
                outputValidatedResult(result, output);
            } else {
                body.execute(templateContext, output);
            }
        }
    }

    /**
     * Executes a template body through the GroundedExecutor, which buffers
     * the context node's subtree to allow reverse-axis navigation.
     */
    private void executeGroundedTemplate(XSLTNode body, XPathNode node,
            TransformContext templateContext, OutputHandler output,
            TemplateRule rule) throws SAXException {
        GroundedExecutor grounded = new GroundedExecutor(templateContext, output);
        grounded.executeGrounded(body, node);
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
        if (output instanceof SequenceBuilderOutputHandler) {
            SequenceBuilderOutputHandler seqBuilder = (SequenceBuilderOutputHandler) output;
            if (!seqBuilder.isInsideElement()) {
                if (item instanceof XPathNodeSet) {
                    for (XPathNode node : ((XPathNodeSet) item).getNodes()) {
                        seqBuilder.addItem(new XPathNodeSet(Collections.singletonList(node)));
                    }
                } else {
                    seqBuilder.addItem(item);
                }
                return;
            }
        }
        if (item instanceof XPathResultTreeFragment) {
            ((XPathResultTreeFragment) item).replayToOutput(output);
        } else if (item instanceof XPathNodeSet) {
            for (XPathNode node : ((XPathNodeSet) item).getNodes()) {
                serializeNode(node, output);
            }
        } else if (item instanceof XPathNode) {
            serializeNode((XPathNode) item, output);
        } else {
            output.characters(item.asString());
        }
    }

    private void serializeNode(XPathNode node, OutputHandler output) throws SAXException {
        switch (node.getNodeType()) {
            case ELEMENT: {
                String uri = node.getNamespaceURI();
                String localName = node.getLocalName();
                String prefix = node.getPrefix();
                String qName = OutputHandlerUtils.buildQName(prefix, localName);
                if (uri == null) {
                    uri = "";
                }
                output.startElement(uri, localName, qName);
                // Declare the element's own namespace binding
                if (prefix != null && !prefix.isEmpty()) {
                    output.namespace(prefix, uri);
                } else {
                    output.namespace("", uri);
                }
                Iterator<XPathNode> attrs = node.getAttributes();
                while (attrs.hasNext()) {
                    XPathNode attr = attrs.next();
                    String aUri = attr.getNamespaceURI();
                    String aLocal = attr.getLocalName();
                    String aPrefix = attr.getPrefix();
                    if (aUri == null) {
                        aUri = "";
                    }
                    // Declare attribute namespace if prefixed
                    if (aPrefix != null && !aPrefix.isEmpty() && !aUri.isEmpty()) {
                        output.namespace(aPrefix, aUri);
                    }
                    String aQName = (aPrefix != null && !aPrefix.isEmpty())
                        ? aPrefix + ":" + aLocal : aLocal;
                    output.attribute(aUri, aLocal, aQName, attr.getStringValue());
                }
                Iterator<XPathNode> children = node.getChildren();
                while (children.hasNext()) {
                    serializeNode(children.next(), output);
                }
                output.endElement(uri, localName, qName);
                break;
            }
            case TEXT: {
                String text = node.getStringValue();
                if (text != null) {
                    output.characters(text);
                }
                break;
            }
            case ROOT: {
                Iterator<XPathNode> children = node.getChildren();
                while (children.hasNext()) {
                    serializeNode(children.next(), output);
                }
                break;
            }
            default:
                output.characters(node.getStringValue());
                break;
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
                if (node.isElement()) {
                    applyTemplatesToAttributes(node, context.getCurrentMode(), context, output);
                    applyTemplatesToChildren(node, context.getCurrentMode(), context, output);
                } else if (node.isRoot()) {
                    applyTemplatesToChildren(node, context.getCurrentMode(), context, output);
                }
                break;
                
            case "shallow-copy": {
                if (node.isElement()) {
                    String nsUri = node.getNamespaceURI();
                    String localName = node.getLocalName();
                    String prefix = node.getPrefix();
                    String qName = OutputHandlerUtils.buildQName(prefix, localName);
                    String effectiveNsUri = OutputHandlerUtils.effectiveUri(nsUri);
                    output.startElement(effectiveNsUri, localName, qName);
                    applyTemplatesToAttributes(node, context.getCurrentMode(), context, output);
                    applyTemplatesToChildren(node, context.getCurrentMode(), context, output);
                    output.endElement(effectiveNsUri, localName, qName);
                } else if (node.isText()) {
                    String txt = node.getStringValue();
                    if (txt != null) {
                        output.characters(txt);
                    }
                } else if (node.isRoot()) {
                    applyTemplatesToChildren(node, context.getCurrentMode(), context, output);
                } else if (node.isAttribute()) {
                    String aUri = node.getNamespaceURI();
                    String aLocal = node.getLocalName();
                    String aPrefix = node.getPrefix();
                    String aQName = OutputHandlerUtils.buildQName(aPrefix, aLocal);
                    String aEffectiveUri = OutputHandlerUtils.effectiveUri(aUri);
                    output.attribute(aEffectiveUri, aLocal, aQName, node.getStringValue());
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
            case "typed-fail":
                throw new SAXException("XTTE3100: Node " + node.getLocalName() +
                    " is untyped, but mode has typed='yes'");

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
            String qName = OutputHandlerUtils.buildQName(prefix, localName);
            String effectiveNsUri = OutputHandlerUtils.effectiveUri(nsUri);
            output.startElement(effectiveNsUri, localName, qName);
            Iterator<XPathNode> children = node.getChildren();
            while (children.hasNext()) {
                copyNodeDeep(children.next(), output);
            }
            output.endElement(effectiveNsUri, localName, qName);
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
     * Applies templates to the attribute nodes of an element.
     * Used by shallow-copy and shallow-skip built-in template rules
     * per XSLT 3.0 spec section 6.7.
     */
    private void applyTemplatesToAttributes(XPathNode node, String mode,
            BasicTransformContext context, OutputHandler output) throws SAXException {
        
        Iterator<XPathNode> attrs = node.getAttributes();
        List<XPathNode> attrList = new ArrayList<>();
        while (attrs.hasNext()) {
            attrList.add(attrs.next());
        }
        
        int size = attrList.size();
        int position = 1;
        
        for (XPathNode attr : attrList) {
            BasicTransformContext attrContext = (BasicTransformContext)
                context.withContextNode(attr).withPositionAndSize(position, size);
            applyTemplates(attr, mode, attrContext, output);
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
            pendingUri = OutputHandlerUtils.effectiveUri(uri);
            pendingLocalName = localName;
            pendingQName = OutputHandlerUtils.effectiveQName(qName, localName);
            pendingAttrs.clear();
            pendingNamespaces.clear();
        }
        
        @Override
        public void endElement(String uri, String localName, String qName) throws SAXException {
            flush();
            String effectiveUri = OutputHandlerUtils.effectiveUri(uri);
            String effectiveQName = OutputHandlerUtils.effectiveQName(qName, localName);
            handler.endElement(effectiveUri, localName, effectiveQName);
        }
        
        @Override
        public void attribute(String uri, String localName, String qName, String value) 
                throws SAXException {
            if (!inStartTag) {
                throw new SAXException("Cannot add attribute outside of start tag");
            }
            String effectiveUri = OutputHandlerUtils.effectiveUri(uri);
            String effectiveQName = OutputHandlerUtils.effectiveQName(qName, localName);
            int existing = pendingAttrs.getIndex(effectiveUri, localName);
            if (existing >= 0) {
                pendingAttrs.setAttribute(existing, effectiveUri, localName,
                    effectiveQName, "CDATA", value);
            } else {
                pendingAttrs.addAttribute(effectiveUri, localName,
                    effectiveQName, "CDATA", value);
            }
        }
        
        @Override
        public void namespace(String prefix, String uri) throws SAXException {
            // If we're inside a deferred start tag, queue the namespace declaration
            // Otherwise emit it immediately
            if (inStartTag) {
                String effectivePrefix = OutputHandlerUtils.effectiveUri(prefix);
                String effectiveUri = OutputHandlerUtils.effectiveUri(uri);
                pendingNamespaces.add(new String[] { effectivePrefix, effectiveUri });
            } else {
                String effectivePrefix = OutputHandlerUtils.effectiveUri(prefix);
                String effectiveUri = OutputHandlerUtils.effectiveUri(uri);
                handler.startPrefixMapping(effectivePrefix, effectiveUri);
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

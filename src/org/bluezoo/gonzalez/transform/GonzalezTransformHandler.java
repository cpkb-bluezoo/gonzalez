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

import org.bluezoo.gonzalez.transform.ast.XSLTNode;
import org.bluezoo.gonzalez.transform.compiler.CompiledStylesheet;
import org.bluezoo.gonzalez.transform.compiler.GlobalVariable;
import org.bluezoo.gonzalez.transform.compiler.TemplateRule;
import org.bluezoo.gonzalez.transform.runtime.*;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNode;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNodeSet;
import org.bluezoo.gonzalez.transform.xpath.type.XPathResultTreeFragment;
import org.bluezoo.gonzalez.transform.xpath.type.XPathString;
import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;
import org.xml.sax.*;
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
        implements org.xml.sax.ext.LexicalHandler {

    private final CompiledStylesheet stylesheet;
    private final Map<String, Object> parameters;
    private final ContentHandler outputHandler;
    private final TemplateMatcher matcher;
    private final javax.xml.transform.ErrorListener errorListener;

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
        
        // Create element node
        StreamingNode element = StreamingNode.createElement(
            uri, localName, prefix, atts, nsBindings, currentNode, documentOrderCounter);
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
     * Patterns can be: *, {uri}*, {uri}localname, localname
     * 
     * The patterns use Clark notation {uri}localname for namespace-qualified names.
     */
    private boolean matchesElementPattern(String pattern, String localName, String namespaceURI) {
        if ("*".equals(pattern)) {
            return true;
        }
        
        // Check for Clark notation: {uri}localname
        if (pattern.startsWith("{")) {
            int closeBrace = pattern.indexOf('}');
            if (closeBrace > 1) {
                String patternUri = pattern.substring(1, closeBrace);
                String patternLocal = pattern.substring(closeBrace + 1);
                
                // Check namespace match
                if (namespaceURI == null || namespaceURI.isEmpty()) {
                    return false; // Pattern has namespace, element doesn't
                }
                if (!patternUri.equals(namespaceURI)) {
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
        
        // Apply templates to the root node
        applyTemplates(root, null, context, output);
        
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
        
        // Evaluate global variables
        for (GlobalVariable var : stylesheet.getGlobalVariables()) {
            try {
                XPathValue value;
                if (var.getSelectExpr() != null) {
                    value = var.getSelectExpr().evaluate(context);
                } else if (var.getContent() != null) {
                    // Execute content and capture as result tree fragment
                    SAXEventBuffer buffer = new SAXEventBuffer();
                    OutputHandler bufferOutput = wrapOutputHandler(buffer);
                    var.getContent().execute(context, bufferOutput);
                    // Store as RTF so xsl:copy-of can access the tree structure
                    value = new XPathResultTreeFragment(buffer);
                } else {
                    value = XPathString.of("");
                }
                
                // Don't override parameters
                if (!var.isParam() || context.getVariable(null, var.getName()) == null) {
                    context.setVariable(var.getName(), value);
                }
            } catch (Exception e) {
                throw new SAXException("Error evaluating global variable: " + var.getName(), e);
            }
        }
    }

    /**
     * Applies templates to a node.
     */
    private void applyTemplates(XPathNode node, String mode, 
            BasicTransformContext context, OutputHandler output) throws SAXException {
        
        TransformContext nodeContext = context.withContextNode(node);
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
        
        // Push variable scope for template
        TransformContext templateContext = context.pushVariableScope();
        
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
        private final org.xml.sax.helpers.AttributesImpl pendingAttrs = 
            new org.xml.sax.helpers.AttributesImpl();
        
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
            flush();
            handler.startPrefixMapping(prefix != null ? prefix : "", uri);
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
            if (handler instanceof org.xml.sax.ext.LexicalHandler) {
                char[] ch = text.toCharArray();
                ((org.xml.sax.ext.LexicalHandler) handler).comment(ch, 0, ch.length);
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
                handler.startElement(pendingUri, pendingLocalName, pendingQName, pendingAttrs);
                inStartTag = false;
                pendingAttrs.clear();
            }
        }
    }

}

/*
 * CopyNode.java
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

package org.bluezoo.gonzalez.transform.ast;

import java.net.URI;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.StringTokenizer;

import org.xml.sax.SAXException;

import org.bluezoo.gonzalez.transform.ValidationMode;
import org.bluezoo.gonzalez.transform.compiler.AttributeSet;
import org.bluezoo.gonzalez.transform.compiler.ExpressionHolder;
import org.bluezoo.gonzalez.transform.compiler.AttributeValueTemplate;
import org.bluezoo.gonzalez.transform.compiler.CompiledStylesheet;
import org.bluezoo.gonzalez.transform.compiler.SequenceBuilderOutputHandler;
import org.bluezoo.gonzalez.transform.runtime.BufferOutputHandler;
import org.bluezoo.gonzalez.transform.runtime.OutputHandlerUtils;
import org.bluezoo.gonzalez.transform.runtime.OutputHandler;
import org.bluezoo.gonzalez.transform.runtime.RuntimeSchemaValidator;
import org.bluezoo.gonzalez.transform.runtime.SAXEventBuffer;
import org.bluezoo.gonzalez.transform.runtime.TransformContext;
import org.bluezoo.gonzalez.transform.xpath.type.XPathResultTreeFragment;
import org.bluezoo.gonzalez.transform.xpath.XPathExpression;
import org.bluezoo.gonzalez.transform.xpath.expr.XPathException;
import org.bluezoo.gonzalez.transform.xpath.type.NodeType;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNode;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNodeSet;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNodeWithBaseURI;
import org.bluezoo.gonzalez.transform.xpath.type.XPathSequence;
import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;

/**
 * CopyNode XSLT instruction.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public class CopyNode extends XSLTInstruction implements ExpressionHolder {
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
    
    public CopyNode(String useAttrSets, SequenceNode content) {
        this(null, useAttrSets, content, null, null, null, true, null, true, null, null);
    }
    
    public CopyNode(String useAttrSets, SequenceNode content, 
            String typeNamespaceURI, String typeLocalName, ValidationMode validation) {
        this(null, useAttrSets, content, typeNamespaceURI, typeLocalName, validation, true, null, true, null, null);
    }
    
    public CopyNode(String useAttrSets, SequenceNode content, 
            String typeNamespaceURI, String typeLocalName, ValidationMode validation,
            boolean inheritNamespaces, AttributeValueTemplate inheritNamespacesAvt) {
        this(null, useAttrSets, content, typeNamespaceURI, typeLocalName, validation, 
             inheritNamespaces, inheritNamespacesAvt, true, null, null);
    }
    
    public CopyNode(String useAttrSets, SequenceNode content, 
            String typeNamespaceURI, String typeLocalName, ValidationMode validation,
            boolean inheritNamespaces, AttributeValueTemplate inheritNamespacesAvt,
            boolean copyNamespaces, AttributeValueTemplate copyNamespacesAvt) {
        this(null, useAttrSets, content, typeNamespaceURI, typeLocalName, validation,
             inheritNamespaces, inheritNamespacesAvt, copyNamespaces, copyNamespacesAvt, null);
    }
    
    public CopyNode(XPathExpression selectExpr, String useAttrSets, SequenceNode content, 
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
    public SequenceNode getContent() { return content; }
    public String getUseAttributeSetsString() { return useAttrSets; }

    @Override
    public List<XPathExpression> getExpressions() {
        List<XPathExpression> exprs = new ArrayList<XPathExpression>();
        if (selectExpr != null) {
            exprs.add(selectExpr);
        }
        return exprs;
    }

    @Override public void execute(TransformContext context, 
                                  OutputHandler output) throws SAXException {
        // XSLT 3.0: if select attribute is present, copy selected items
        // Otherwise, copy the context item (XSLT 1.0/2.0 behavior)
        XPathNode node;
        if (selectExpr != null) {
            try {
                XPathValue result = selectExpr.evaluate(context);
                if (result == null || (result.isNodeSet() && result.asNodeSet().isEmpty())) {
                    // Empty result - execute on-empty if present
                    if (onEmptyNode != null) {
                        onEmptyNode.execute(context, output);
                    }
                    return;
                }
                // XTTE3180: xsl:copy select must return at most one item
                if (result.isNodeSet()) {
                    List<XPathNode> nodes = result.asNodeSet().getNodes();
                    if (nodes.size() > 1) {
                        throw new SAXException("XTTE3180: xsl:copy select " +
                            "must return a single item, but got " +
                            nodes.size() + " items");
                    }
                    if (nodes.isEmpty()) {
                        if (onEmptyNode != null) {
                            onEmptyNode.execute(context, output);
                        }
                        return;
                    }
                    node = nodes.get(0);
                } else if (result instanceof XPathSequence) {
                    XPathSequence seq = (XPathSequence) result;
                    if (seq.size() > 1) {
                        throw new SAXException("XTTE3180: xsl:copy select " +
                            "must return a single item, but got " +
                            seq.size() + " items");
                    }
                    if (seq.isEmpty()) {
                        if (onEmptyNode != null) {
                            onEmptyNode.execute(context, output);
                        }
                        return;
                    }
                    XPathValue item = seq.get(0);
                    if (item instanceof XPathNode) {
                        node = (XPathNode) item;
                    } else {
                        output.characters(item.asString());
                        return;
                    }
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
            // XSLT 3.0 §11.9.1: if context item is an atomic value,
            // output it as text and do not evaluate the body
            XPathValue contextItem = context.getContextItem();
            if (contextItem != null && !(contextItem instanceof XPathNode)
                    && !contextItem.isNodeSet()) {
                output.characters(contextItem.asString());
                return;
            }
            // XTTE0945: xsl:copy without select when context is absent
            if (context.isContextItemUndefined()) {
                throw new SAXException("XTTE0945: xsl:copy requires a " +
                    "context item, but the context item is absent");
            }
            node = context.getContextNode();
        }
        
        if (node == null) {
            // XTTE0945: in XSLT 3.0, xsl:copy with no context item is an error
            if (selectExpr == null && context.getContextItem() == null) {
                double procVersion = context.getStylesheet()
                    .getProcessorVersion();
                if (procVersion >= 3.0) {
                    throw new SAXException("XTTE0945: xsl:copy requires " +
                        "a context item, but the context item is absent");
                }
            }
            return;
        }
        
        // XSLT 3.0 §11.9.1: when select is present, current template rule becomes null
        TransformContext effectiveContext = context;
        if (selectExpr != null) {
            effectiveContext = context.withCurrentTemplateRule(null);
        }
        executeCopyForNode(node, effectiveContext, output);
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
                String uri = OutputHandlerUtils.effectiveUri(node.getNamespaceURI());
                String localName = node.getLocalName();
                String prefix = node.getPrefix();
                String qName = OutputHandlerUtils.buildQName(prefix, localName);
                
                // Per XSLT spec, the base URI of a shallow copy is the base URI
                // of the original node. Store it as metadata for parentless copies.
                String origBaseUri = computeNodeBaseUri(node);
                if (origBaseUri != null && !origBaseUri.isEmpty()) {
                    output.setNodeBaseURI(origBaseUri);
                }
                
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
                if (!effectiveInheritNamespaces) {
                    output.setInheritNamespaces(false);
                }
                try {
                    if (content != null) {
                        // XSLT 3.0 §11.9.1: when select is present, the
                        // content is evaluated with a singleton focus on
                        // the selected item (position=1, last=1).
                        if (selectExpr != null) {
                            TransformContext focusCtx = context.withContextNode(node);
                            focusCtx = focusCtx.withPositionAndSize(1, 1);
                            content.execute(focusCtx, output);
                        } else {
                            content.execute(context, output);
                        }
                    }
                } finally {
                    if (!effectiveInheritNamespaces) {
                        output.setInheritNamespaces(true);
                    }
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
                String attrUri = OutputHandlerUtils.effectiveUri(node.getNamespaceURI());
                String attrLocal = node.getLocalName();
                String attrPrefix = node.getPrefix();
                String attrQName = OutputHandlerUtils.buildQName(attrPrefix, attrLocal);
                if (attrPrefix != null && !attrPrefix.isEmpty() && !attrUri.isEmpty()) {
                    output.namespace(attrPrefix, attrUri);
                }
                output.attribute(attrUri, attrLocal, attrQName, node.getStringValue());
                break;
                
            case COMMENT:
                output.comment(node.getStringValue());
                break;
                
            case PROCESSING_INSTRUCTION:
                output.processingInstruction(node.getLocalName(), node.getStringValue());
                break;
                
            case ROOT:
                // XSLT 3.0 §11.9.1: xsl:copy of a document node creates a
                // new document node whose content is the sequence constructor.
                // Buffer content, then dispatch: if the output is a sequence
                // builder, add a document node item; otherwise replay children
                // directly (without the document wrapper).
                SAXEventBuffer docBuffer = new SAXEventBuffer();
                BufferOutputHandler bufOut = new BufferOutputHandler(docBuffer);
                bufOut.startDocument();
                if (content != null) {
                    TransformContext docCtx = context;
                    if (selectExpr != null) {
                        docCtx = context.withContextNode(node);
                        docCtx = docCtx.withPositionAndSize(1, 1);
                    }
                    content.execute(docCtx,
                        new DocumentContentOutputHandler(bufOut));
                }
                bufOut.endDocument();
                // If body produced no content and on-empty is present,
                // use on-empty fallback instead.
                if (onEmptyNode != null && !docBuffer.hasNonEmptyContent()) {
                    onEmptyNode.execute(context, output);
                } else {
                    XPathResultTreeFragment rtf =
                        new XPathResultTreeFragment(docBuffer);
                    if (output instanceof SequenceBuilderOutputHandler) {
                        ((SequenceBuilderOutputHandler) output).addItem(rtf);
                    } else {
                        rtf.replayToOutput(output, true);
                    }
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
    
    /**
     * Output handler wrapper that rejects attributes and namespace nodes
     * at the document level (XTDE0420). These are not valid children of
     * a document node.
     */
    private static class DocumentContentOutputHandler implements OutputHandler {
        private final OutputHandler delegate;
        private int elementDepth = 0;
        
        DocumentContentOutputHandler(OutputHandler delegate) {
            this.delegate = delegate;
        }
        
        @Override
        public void startDocument() throws SAXException {
            delegate.startDocument();
        }
        
        @Override
        public void endDocument() throws SAXException {
            delegate.endDocument();
        }
        
        @Override
        public void startElement(String namespaceURI, String localName, String qName)
                throws SAXException {
            elementDepth++;
            delegate.startElement(namespaceURI, localName, qName);
        }
        
        @Override
        public void endElement(String namespaceURI, String localName, String qName)
                throws SAXException {
            delegate.endElement(namespaceURI, localName, qName);
            elementDepth--;
        }
        
        @Override
        public void attribute(String namespaceURI, String localName,
                String qName, String value) throws SAXException {
            if (elementDepth == 0) {
                throw new SAXException("XTDE0420: Cannot add attribute '" +
                    localName + "' to a document node");
            }
            delegate.attribute(namespaceURI, localName, qName, value);
        }
        
        @Override
        public void namespace(String prefix, String uri) throws SAXException {
            if (elementDepth == 0) {
                throw new SAXException("XTDE0420: Cannot add namespace node " +
                    "to a document node");
            }
            delegate.namespace(prefix, uri);
        }
        
        @Override
        public void characters(String text) throws SAXException {
            delegate.characters(text);
        }
        
        @Override
        public void charactersRaw(String text) throws SAXException {
            delegate.charactersRaw(text);
        }
        
        @Override
        public void comment(String text) throws SAXException {
            delegate.comment(text);
        }
        
        @Override
        public void processingInstruction(String target, String data)
                throws SAXException {
            delegate.processingInstruction(target, data);
        }
        
        @Override
        public void flush() throws SAXException {
            delegate.flush();
        }
        
        @Override
        public void setElementType(String namespaceURI, String localName)
                throws SAXException {
            delegate.setElementType(namespaceURI, localName);
        }
        
        @Override
        public void setAttributeType(String namespaceURI, String localName)
                throws SAXException {
            delegate.setAttributeType(namespaceURI, localName);
        }
        
        @Override
        public void setAtomicValuePending(boolean pending) throws SAXException {
            delegate.setAtomicValuePending(pending);
        }
        
        @Override
        public boolean isAtomicValuePending() {
            return delegate.isAtomicValuePending();
        }
        
        @Override
        public void setInAttributeContent(boolean inAttributeContent)
                throws SAXException {
            delegate.setInAttributeContent(inAttributeContent);
        }
        
        @Override
        public boolean isInAttributeContent() {
            return delegate.isInAttributeContent();
        }
        
        @Override
        public void itemBoundary() throws SAXException {
            delegate.itemBoundary();
        }
    }

    /**
     * Computes the base URI of a node by walking its xml:base chain.
     * Used to preserve the original's base URI on shallow copies.
     */
    private static String computeNodeBaseUri(XPathNode node) {
        String resolved = null;
        XPathNode current = node;
        while (current != null) {
            if (current.isElement()) {
                XPathNode xmlBase = current.getAttribute(
                    "http://www.w3.org/XML/1998/namespace", "base");
                if (xmlBase == null) {
                    xmlBase = current.getAttribute("", "xml:base");
                }
                if (xmlBase != null) {
                    String localBase = xmlBase.getStringValue();
                    if (localBase != null && !localBase.isEmpty()) {
                        if (isAbsoluteUri(localBase)) {
                            if (resolved != null) {
                                return resolveUri(resolved, localBase);
                            }
                            return localBase;
                        }
                        if (resolved == null) {
                            resolved = localBase;
                        } else {
                            resolved = resolveUri(resolved, localBase);
                        }
                    }
                }
            }
            if (current instanceof XPathNodeWithBaseURI) {
                String storedBase = ((XPathNodeWithBaseURI) current).getBaseURI();
                if (storedBase != null && !storedBase.isEmpty()) {
                    if (resolved != null) {
                        return resolveUri(resolved, storedBase);
                    }
                    return storedBase;
                }
            }
            if (current.getNodeType() == NodeType.ROOT) {
                break;
            }
            current = current.getParent();
        }
        return resolved;
    }

    private static boolean isAbsoluteUri(String uri) {
        int len = uri.length();
        for (int i = 0; i < len; i++) {
            char c = uri.charAt(i);
            if (c == ':') {
                return i > 0;
            }
            if (c == '/' || c == '?' || c == '#') {
                return false;
            }
            if (i == 0 && !Character.isLetter(c)) {
                return false;
            }
            if (i > 0 && !Character.isLetterOrDigit(c) && c != '+' && c != '-' && c != '.') {
                return false;
            }
        }
        return false;
    }

    private static String resolveUri(String relative, String base) {
        if (relative == null || relative.isEmpty()) {
            return base;
        }
        if (isAbsoluteUri(relative)) {
            return relative;
        }
        try {
            java.net.URI baseURI = new java.net.URI(base);
            java.net.URI resolved = baseURI.resolve(relative);
            return resolved.toString();
        } catch (java.net.URISyntaxException e) {
            if (base.endsWith("/")) {
                return base + relative;
            }
            int lastSlash = base.lastIndexOf('/');
            if (lastSlash >= 0) {
                return base.substring(0, lastSlash + 1) + relative;
            }
            return relative;
        }
    }
}

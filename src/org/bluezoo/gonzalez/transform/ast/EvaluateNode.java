/*
 * EvaluateNode.java
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

import org.bluezoo.gonzalez.transform.runtime.BasicTransformContext;
import org.bluezoo.gonzalez.transform.runtime.OutputHandler;
import org.bluezoo.gonzalez.transform.runtime.TransformContext;
import org.bluezoo.gonzalez.transform.xpath.XPathExpression;
import org.bluezoo.gonzalez.transform.xpath.XPathContext;
import org.bluezoo.gonzalez.transform.xpath.expr.XPathException;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNode;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNodeSet;
import org.bluezoo.gonzalez.transform.xpath.type.XPathString;
import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;
import org.xml.sax.SAXException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * XSLT 3.0 xsl:evaluate instruction.
 *
 * <p>The xsl:evaluate instruction dynamically evaluates an XPath expression
 * that is provided as a string at runtime. This enables powerful dynamic
 * query capabilities but cannot be used in streaming mode.
 *
 * <p>Example:
 * <pre>
 * &lt;xsl:evaluate xpath="concat('/', $path)"/&gt;
 * 
 * &lt;xsl:evaluate xpath="$expr" context-item="."&gt;
 *   &lt;xsl:with-param name="x" select="5"/&gt;
 * &lt;/xsl:evaluate&gt;
 * </pre>
 *
 * <p>Key attributes:
 * <ul>
 *   <li><b>xpath</b> (required) - Expression returning the XPath to evaluate</li>
 *   <li><b>as</b> - Expected return type</li>
 *   <li><b>base-uri</b> - Base URI for the dynamic expression</li>
 *   <li><b>context-item</b> - Context item for evaluation</li>
 *   <li><b>namespace-context</b> - Node providing namespace bindings</li>
 *   <li><b>schema-aware</b> - Whether evaluation is schema-aware</li>
 *   <li><b>with-params</b> - Variable bindings from xsl:with-param children</li>
 * </ul>
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class EvaluateNode implements XSLTNode {

    private final XPathExpression xpathExpr;        // Expression for xpath attribute
    private final XPathExpression contextItemExpr;  // Expression for context-item
    private final XPathExpression baseUriExpr;      // Expression for base-uri
    private final XPathExpression namespaceContextExpr; // Expression for namespace-context
    private final String asType;                    // Expected return type
    private final List<WithParamNode> params;       // xsl:with-param children
    private String lastXPathString;                  // Single-entry compile cache
    private XPathExpression lastCompiledExpr;

    /**
     * Represents an xsl:with-param child of xsl:evaluate.
     */
    public static class WithParamNode {
        private final String namespaceURI;
        private final String localName;
        private final XPathExpression select;
        private final XSLTNode content;

        public WithParamNode(String namespaceURI, String localName, 
                            XPathExpression select, XSLTNode content) {
            this.namespaceURI = namespaceURI;
            this.localName = localName;
            this.select = select;
            this.content = content;
        }

        public String getNamespaceURI() { return namespaceURI; }
        public String getLocalName() { return localName; }
        public XPathExpression getSelect() { return select; }
        public XSLTNode getContent() { return content; }
    }

    /**
     * Creates an xsl:evaluate node.
     *
     * @param xpathExpr expression for the xpath attribute (required)
     * @param contextItemExpr expression for context-item attribute (may be null)
     * @param baseUriExpr expression for base-uri attribute (may be null)
     * @param namespaceContextExpr expression for namespace-context (may be null)
     * @param asType expected return type (may be null)
     * @param params list of xsl:with-param nodes (may be empty)
     */
    public EvaluateNode(XPathExpression xpathExpr, XPathExpression contextItemExpr,
                       XPathExpression baseUriExpr, XPathExpression namespaceContextExpr,
                       String asType, List<WithParamNode> params) {
        this.xpathExpr = xpathExpr;
        this.contextItemExpr = contextItemExpr;
        this.baseUriExpr = baseUriExpr;
        this.namespaceContextExpr = namespaceContextExpr;
        this.asType = asType;
        this.params = params;
    }

    @Override
    public void execute(TransformContext context, OutputHandler output) throws SAXException {
        try {
            // Evaluate the xpath attribute to get the XPath expression string
            XPathValue xpathValue = xpathExpr.evaluate(context);
            String xpathString = xpathValue.asString();
            
            if (xpathString == null || xpathString.isEmpty()) {
                // Empty expression - return empty sequence
                return;
            }
            
            // Compile the dynamic expression (cached for repeated calls)
            XPathExpression dynamicExpr;
            if (xpathString.equals(lastXPathString)) {
                dynamicExpr = lastCompiledExpr;
            } else {
                try {
                    dynamicExpr = XPathExpression.compile(xpathString, null);
                    lastXPathString = xpathString;
                    lastCompiledExpr = dynamicExpr;
                } catch (Exception e) {
                    throw new SAXException("XPST0003: Invalid XPath in xsl:evaluate: " + 
                        e.getMessage(), e);
                }
            }
            
            // Determine the evaluation context
            TransformContext evalContext = context;
            
            // Handle context-item
            if (contextItemExpr != null) {
                XPathValue ctxValue = contextItemExpr.evaluate(context);
                if (ctxValue != null) {
                    if (ctxValue instanceof XPathNode) {
                        evalContext = evalContext.withContextNode((XPathNode) ctxValue);
                    } else if (ctxValue.isNodeSet()) {
                        XPathNodeSet ns = ctxValue.asNodeSet();
                        if (!ns.isEmpty()) {
                            evalContext = evalContext.withContextNode(ns.iterator().next());
                        }
                    }
                    // For atomic values, set as context item (XPath 2.0+)
                    if (evalContext instanceof BasicTransformContext) {
                        evalContext = ((BasicTransformContext) evalContext).withContextItem(ctxValue);
                    }
                }
            }
            
            // Handle base-uri
            if (baseUriExpr != null) {
                XPathValue baseValue = baseUriExpr.evaluate(context);
                String baseUri = baseValue.asString();
                if (baseUri != null && !baseUri.isEmpty()) {
                    evalContext = evalContext.withStaticBaseURI(baseUri);
                }
            }
            
            // Evaluate xsl:with-param children and add to context
            if (params != null && !params.isEmpty()) {
                for (WithParamNode param : params) {
                    XPathValue paramValue;
                    if (param.getSelect() != null) {
                        paramValue = param.getSelect().evaluate(context);
                    } else if (param.getContent() != null) {
                        // Execute content and capture as RTF
                        org.bluezoo.gonzalez.transform.runtime.SAXEventBuffer buffer = 
                            new org.bluezoo.gonzalez.transform.runtime.SAXEventBuffer();
                        org.bluezoo.gonzalez.transform.runtime.BufferOutputHandler bufOut =
                            new org.bluezoo.gonzalez.transform.runtime.BufferOutputHandler(buffer);
                        param.getContent().execute(context, bufOut);
                        paramValue = new org.bluezoo.gonzalez.transform.xpath.type.XPathResultTreeFragment(buffer);
                    } else {
                        paramValue = XPathString.of("");
                    }
                    
                    evalContext = (TransformContext) evalContext.withVariable(
                        param.getNamespaceURI(), param.getLocalName(), paramValue);
                }
            }
            
            // Evaluate the dynamic expression
            XPathValue result = dynamicExpr.evaluate(evalContext);
            
            // Output the result
            outputResult(result, output);
            
        } catch (XPathException e) {
            throw new SAXException("Error in xsl:evaluate: " + e.getMessage(), e);
        }
    }
    
    /**
     * Outputs the result of evaluation to the output handler.
     */
    private void outputResult(XPathValue result, OutputHandler output) throws SAXException {
        if (result == null) {
            return;
        }
        
        if (result.isNodeSet()) {
            // Copy nodes to output
            XPathNodeSet ns = result.asNodeSet();
            for (XPathNode node : ns) {
                copyNodeToOutput(node, output);
            }
        } else {
            // Output atomic value as text
            String text = result.asString();
            if (text != null && !text.isEmpty()) {
                output.characters(text);
            }
        }
    }
    
    /**
     * Copies a node to the output handler.
     */
    private void copyNodeToOutput(XPathNode node, OutputHandler output) throws SAXException {
        org.bluezoo.gonzalez.transform.xpath.type.NodeType nodeType = node.getNodeType();
        
        if (nodeType == org.bluezoo.gonzalez.transform.xpath.type.NodeType.ELEMENT) {
            // Copy element and its contents
            String uri = node.getNamespaceURI();
            String local = node.getLocalName();
            String prefix = node.getPrefix();
            String qName = (prefix != null && !prefix.isEmpty()) ? prefix + ":" + local : local;
            
            output.startElement(uri != null ? uri : "", local, qName);
            
            // Copy attributes
            java.util.Iterator<XPathNode> attrIter = node.getAttributes();
            while (attrIter.hasNext()) {
                XPathNode attr = attrIter.next();
                String attrPrefix = attr.getPrefix();
                String attrLocal = attr.getLocalName();
                String attrQName = (attrPrefix != null && !attrPrefix.isEmpty()) ? 
                    attrPrefix + ":" + attrLocal : attrLocal;
                output.attribute(
                    attr.getNamespaceURI() != null ? attr.getNamespaceURI() : "",
                    attrLocal,
                    attrQName,
                    attr.getStringValue()
                );
            }
            
            // Copy children
            java.util.Iterator<XPathNode> children = node.getChildren();
            while (children.hasNext()) {
                copyNodeToOutput(children.next(), output);
            }
            
            output.endElement(uri != null ? uri : "", local, qName);
        } else if (nodeType == org.bluezoo.gonzalez.transform.xpath.type.NodeType.TEXT) {
            String text = node.getStringValue();
            if (text != null && !text.isEmpty()) {
                output.characters(text);
            }
        } else if (nodeType == org.bluezoo.gonzalez.transform.xpath.type.NodeType.COMMENT) {
            output.comment(node.getStringValue());
        } else if (nodeType == org.bluezoo.gonzalez.transform.xpath.type.NodeType.PROCESSING_INSTRUCTION) {
            output.processingInstruction(node.getLocalName(), node.getStringValue());
        } else if (nodeType == org.bluezoo.gonzalez.transform.xpath.type.NodeType.ROOT) {
            // Copy document children
            java.util.Iterator<XPathNode> docChildren = node.getChildren();
            while (docChildren.hasNext()) {
                copyNodeToOutput(docChildren.next(), output);
            }
        }
        // ATTRIBUTE and NAMESPACE nodes are skipped (handled as part of element)
    }

    @Override
    public StreamingCapability getStreamingCapability() {
        // xsl:evaluate cannot stream - requires full access to context
        return StreamingCapability.NONE;
    }

    @Override
    public String toString() {
        return "EvaluateNode[xpath=" + xpathExpr + "]";
    }

}

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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.xml.sax.SAXException;

import org.bluezoo.gonzalez.transform.compiler.AttributeValueTemplate;
import org.bluezoo.gonzalez.transform.compiler.ExpressionHolder;
import org.bluezoo.gonzalez.transform.compiler.SequenceBuilderOutputHandler;
import org.bluezoo.gonzalez.transform.runtime.BasicTransformContext;
import org.bluezoo.gonzalez.transform.runtime.OutputHandler;
import org.bluezoo.gonzalez.transform.runtime.TransformContext;
import org.bluezoo.gonzalez.transform.xpath.XPathExpression;
import org.bluezoo.gonzalez.transform.xpath.XPathContext;
import org.bluezoo.gonzalez.transform.xpath.XPathParser;
import org.bluezoo.gonzalez.transform.xpath.expr.XPathException;
import org.bluezoo.gonzalez.transform.xpath.type.NodeType;
import org.bluezoo.gonzalez.transform.xpath.type.XPathMap;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNode;
import org.bluezoo.gonzalez.transform.xpath.type.XPathQName;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNodeSet;
import org.bluezoo.gonzalez.transform.xpath.type.XPathResultTreeFragment;
import org.bluezoo.gonzalez.transform.xpath.type.XPathSequence;
import org.bluezoo.gonzalez.transform.xpath.type.XPathString;
import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;

/**
 * XSLT 3.0 xsl:evaluate instruction.
 *
 * <p>Dynamically evaluates an XPath expression provided as a string at runtime.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class EvaluateNode implements XSLTNode, ExpressionHolder {

    private final XPathExpression xpathExpr;
    private final XPathExpression contextItemExpr;
    private final AttributeValueTemplate baseUriAvt;
    private final XPathExpression namespaceContextExpr;
    private final XPathExpression withParamsExpr;
    private final String asType;
    private final List<WithParamNode> params;
    private String lastXPathString;
    private XPathExpression lastCompiledExpr;

    /**
     * Represents an xsl:with-param child of xsl:evaluate.
     */
    public static class WithParamNode {
        private final String namespaceURI;
        private final String localName;
        private final XPathExpression select;
        private final XSLTNode content;
        private final String asType;

        public WithParamNode(String namespaceURI, String localName, 
                            XPathExpression select, XSLTNode content) {
            this(namespaceURI, localName, select, content, null);
        }

        public WithParamNode(String namespaceURI, String localName, 
                            XPathExpression select, XSLTNode content,
                            String asType) {
            this.namespaceURI = namespaceURI;
            this.localName = localName;
            this.select = select;
            this.content = content;
            this.asType = asType;
        }

        public String getNamespaceURI() { return namespaceURI; }
        public String getLocalName() { return localName; }
        public XPathExpression getSelect() { return select; }
        public XSLTNode getContent() { return content; }
        public String getAsType() { return asType; }
    }

    /**
     * Creates an xsl:evaluate node.
     *
     * @param xpathExpr expression for the xpath attribute (required)
     * @param contextItemExpr expression for context-item attribute (may be null)
     * @param baseUriAvt AVT for base-uri attribute (may be null)
     * @param namespaceContextExpr expression for namespace-context (may be null)
     * @param withParamsExpr expression for with-params attribute (may be null)
     * @param asType expected return type (may be null)
     * @param params list of xsl:with-param nodes (may be empty)
     */
    public EvaluateNode(XPathExpression xpathExpr, XPathExpression contextItemExpr,
                       AttributeValueTemplate baseUriAvt, XPathExpression namespaceContextExpr,
                       XPathExpression withParamsExpr, String asType,
                       List<WithParamNode> params) {
        this.xpathExpr = xpathExpr;
        this.contextItemExpr = contextItemExpr;
        this.baseUriAvt = baseUriAvt;
        this.namespaceContextExpr = namespaceContextExpr;
        this.withParamsExpr = withParamsExpr;
        this.asType = asType;
        this.params = params;
    }

    @Override
    public List<XPathExpression> getExpressions() {
        List<XPathExpression> exprs = new ArrayList<XPathExpression>();
        if (xpathExpr != null) {
            exprs.add(xpathExpr);
        }
        if (contextItemExpr != null) {
            exprs.add(contextItemExpr);
        }
        if (namespaceContextExpr != null) {
            exprs.add(namespaceContextExpr);
        }
        if (withParamsExpr != null) {
            exprs.add(withParamsExpr);
        }
        return exprs;
    }

    @Override
    public void execute(TransformContext context, OutputHandler output) throws SAXException {
        try {
            XPathValue xpathValue = xpathExpr.evaluate(context);
            String xpathString = xpathValue.asString();
            
            if (xpathString == null || xpathString.isEmpty()) {
                return;
            }
            
            // Check for disallowed functions (XTDE3160)
            String exprLower = xpathString.toLowerCase();
            if (containsDisallowedFunction(exprLower)) {
                throw new SAXException("XTDE3160: Expression in xsl:evaluate " +
                    "contains a disallowed function call: " + xpathString);
            }
            
            // Extract namespace-context before compiling (XTTE3170)
            XPathParser.NamespaceResolver nsResolver = null;
            if (namespaceContextExpr != null) {
                XPathNode nsContextNode = resolveNamespaceContextNode(context);
                if (nsContextNode != null) {
                    nsResolver = buildNamespaceResolver(nsContextNode);
                }
            }
            
            // Compile the dynamic expression with namespace context
            XPathExpression dynamicExpr;
            String cacheKey = xpathString + (nsResolver != null ? "#ns" : "");
            if (xpathString.equals(lastXPathString) && nsResolver == null) {
                dynamicExpr = lastCompiledExpr;
            } else {
                try {
                    dynamicExpr = XPathExpression.compile(xpathString, nsResolver);
                    if (nsResolver == null) {
                        lastXPathString = xpathString;
                        lastCompiledExpr = dynamicExpr;
                    }
                } catch (Exception e) {
                    throw new SAXException("XPST0003: Invalid XPath in xsl:evaluate: " + 
                        e.getMessage(), e);
                }
            }
            
            TransformContext evalContext = context;
            
            // Handle context-item
            // Per XSLT 3.0 26.6: when context-item is absent, the context item
            // for the dynamically evaluated expression is absent (undefined)
            if (contextItemExpr != null) {
                XPathValue ctxValue = contextItemExpr.evaluate(context);
                evalContext = applyContextItem(evalContext, ctxValue);
            } else {
                // No context-item attribute: context is absent for xsl:evaluate
                evalContext = evalContext.withContextNode(null);
                if (evalContext instanceof BasicTransformContext) {
                    ((BasicTransformContext) evalContext).setContextItemUndefined(true);
                }
            }
            
            // Handle base-uri (AVT, evaluates to string)
            if (baseUriAvt != null) {
                String baseUri = baseUriAvt.evaluate(context);
                if (baseUri != null && !baseUri.isEmpty()) {
                    evalContext = evalContext.withStaticBaseURI(baseUri);
                }
            }
            
            // Bind variables from xsl:with-param children first
            if (params != null && !params.isEmpty()) {
                for (WithParamNode param : params) {
                    XPathValue paramValue;
                    if (param.getSelect() != null) {
                        paramValue = param.getSelect().evaluate(context);
                    } else if (param.getContent() != null) {
                        org.bluezoo.gonzalez.transform.runtime.SAXEventBuffer buffer = 
                            new org.bluezoo.gonzalez.transform.runtime.SAXEventBuffer();
                        org.bluezoo.gonzalez.transform.runtime.BufferOutputHandler bufOut =
                            new org.bluezoo.gonzalez.transform.runtime.BufferOutputHandler(buffer);
                        param.getContent().execute(context, bufOut);
                        paramValue = new XPathResultTreeFragment(buffer);
                    } else {
                        paramValue = XPathString.of("");
                    }
                    
                    // XTTE0590: validate against declared 'as' type
                    String paramAsType = param.getAsType();
                    if (paramAsType != null && !paramAsType.isEmpty()
                            && paramValue != null) {
                        validateParamType(paramValue, paramAsType,
                            param.getLocalName());
                    }
                    
                    evalContext = (TransformContext) evalContext.withVariable(
                        param.getNamespaceURI(), param.getLocalName(), paramValue);
                }
            }
            
            // Bind variables from with-params attribute (map expression)
            // Per XSLT 3.0 spec, with-params map values override xsl:with-param children
            if (withParamsExpr != null) {
                XPathValue wpValue = withParamsExpr.evaluate(context);
                if (wpValue instanceof XPathMap) {
                    XPathMap map = (XPathMap) wpValue;
                    // XTTE3165: keys in the with-params map must be xs:QName
                    for (Map.Entry<String, XPathValue> entry : map.entries()) {
                        String key = entry.getKey();
                        XPathValue typedKey = map.getTypedKey(key);
                        if (typedKey != null && !(typedKey instanceof XPathQName)) {
                            throw new SAXException("XTTE3165: Keys in " +
                                "with-params map must be xs:QName values, " +
                                "got " + typedKey.getClass().getSimpleName() +
                                " for key '" + key + "'");
                        }
                        XPathValue val = entry.getValue();
                        evalContext = (TransformContext) evalContext.withVariable(null, key, val);
                    }
                }
            }
            
            // Mark context as dynamic evaluation (restricts private function access)
            if (evalContext instanceof BasicTransformContext) {
                ((BasicTransformContext) evalContext).setDynamicEvaluation(true);
            }
            
            // Evaluate the dynamic expression
            XPathValue result = dynamicExpr.evaluate(evalContext);
            
            // Validate result type against 'as' attribute if present
            if (asType != null && !asType.isEmpty() && result != null) {
                validateResultType(result, asType);
            }
            
            // Output the result, preserving typed values
            outputResult(result, output);
            
        } catch (XPathException e) {
            throw new SAXException("Error in xsl:evaluate: " + e.getMessage(), e);
        }
    }
    
    /**
     * Applies the context-item to the evaluation context.
     * Validates per XSLT 3.0: XPDY0002 if absent, XTTE3210 if multi-item.
     */
    private TransformContext applyContextItem(TransformContext evalContext, 
                                              XPathValue ctxValue) throws SAXException {
        if (ctxValue == null) {
            throw new SAXException("XPDY0002: Context item for xsl:evaluate is absent");
        }

        // Check for empty sequence: context-item="()" or expression returning nothing
        if (ctxValue instanceof XPathSequence) {
            XPathSequence seq = (XPathSequence) ctxValue;
            if (seq.isEmpty()) {
                throw new SAXException("XPDY0002: Context item for xsl:evaluate is an empty sequence");
            }
            if (seq.size() > 1) {
                throw new SAXException("XTTE3210: Context item for xsl:evaluate " +
                    "must be a single item, got sequence of " + seq.size() + " items");
            }
            ctxValue = seq.iterator().next();
        }

        if (ctxValue.isNodeSet()) {
            XPathNodeSet ns = ctxValue.asNodeSet();
            if (ns.isEmpty()) {
                throw new SAXException("XPDY0002: Context item for xsl:evaluate is an empty sequence");
            }
            if (ns.size() > 1) {
                throw new SAXException("XTTE3210: Context item for xsl:evaluate " +
                    "must be a single item, got node-set of " + ns.size() + " nodes");
            }
            evalContext = evalContext.withContextNode(ns.iterator().next());
        } else if (ctxValue instanceof XPathNode) {
            evalContext = evalContext.withContextNode((XPathNode) ctxValue);
        }

        if (evalContext instanceof BasicTransformContext) {
            evalContext = ((BasicTransformContext) evalContext).withContextItem(ctxValue);
        }
        return evalContext;
    }
    
    /**
     * Validates the result type against the 'as' attribute.
     * Raises XPTY0004 if the result clearly does not match the declared type.
     * Nodes pass string-type checks because they atomize to xs:untypedAtomic.
     */
    private void validateResultType(XPathValue result, String type) throws SAXException {
        if ("xs:string".equals(type)) {
            // Nodes atomize to xs:untypedAtomic which is promotable to xs:string
            if (result instanceof XPathNode || result.isNodeSet() 
                    || result instanceof XPathString
                    || result instanceof XPathResultTreeFragment) {
                return;
            }
            throw new SAXException("XPTY0004: Required item type of xsl:evaluate result " +
                "is xs:string; supplied value is " + result.getClass().getSimpleName());
        }
    }

    /**
     * Validates a parameter value against the declared 'as' type.
     * Raises XTTE0590 if incompatible.
     */
    private void validateParamType(XPathValue value, String asType,
            String paramName) throws SAXException {
        String type = asType.trim();
        String baseType = type;
        if (baseType.endsWith("*") || baseType.endsWith("+")
                || baseType.endsWith("?")) {
            baseType = baseType.substring(0, baseType.length() - 1).trim();
        }
        if (baseType.startsWith("xs:")) {
            baseType = baseType.substring(3);
        }
        boolean isNumericType = "integer".equals(baseType) || "int".equals(baseType)
            || "long".equals(baseType) || "short".equals(baseType)
            || "decimal".equals(baseType) || "double".equals(baseType)
            || "float".equals(baseType);
        if (isNumericType && value instanceof XPathString) {
            String strVal = value.asString();
            try {
                Long.parseLong(strVal.trim());
            } catch (NumberFormatException e) {
                throw new SAXException("XTTE0590: Value of parameter $"
                    + paramName + " does not match declared type "
                    + asType + ": got '" + strVal + "'");
            }
        }
    }

    /**
     * Resolves the namespace-context expression to a single XPathNode.
     */
    private XPathNode resolveNamespaceContextNode(TransformContext context) 
            throws SAXException, XPathException {
        XPathValue nsCtxValue = namespaceContextExpr.evaluate(context);
        if (nsCtxValue instanceof XPathSequence) {
            XPathSequence seq = (XPathSequence) nsCtxValue;
            if (seq.size() != 1) {
                throw new SAXException("XTTE3170: namespace-context " +
                    "of xsl:evaluate must be a single node, " +
                    "got " + seq.size() + " items");
            }
            nsCtxValue = seq.get(0);
        }
        if (nsCtxValue != null && nsCtxValue.isNodeSet()) {
            XPathNodeSet ns = nsCtxValue.asNodeSet();
            if (ns.size() != 1) {
                throw new SAXException("XTTE3170: namespace-context " +
                    "of xsl:evaluate must be a single node, " +
                    "got " + ns.size() + " nodes");
            }
            return ns.iterator().next();
        }
        if (nsCtxValue instanceof XPathNode) {
            return (XPathNode) nsCtxValue;
        }
        if (nsCtxValue != null) {
            throw new SAXException("XTTE3170: namespace-context " +
                "of xsl:evaluate must be a node");
        }
        return null;
    }

    /**
     * Builds a NamespaceResolver from the in-scope namespaces of a node.
     */
    private XPathParser.NamespaceResolver buildNamespaceResolver(
            final XPathNode nsNode) {
        final Map<String, String> namespaces = new HashMap<String, String>();
        Iterator<XPathNode> nsIter = nsNode.getNamespaces();
        while (nsIter.hasNext()) {
            XPathNode ns = nsIter.next();
            String prefix = ns.getLocalName();
            String uri = ns.getStringValue();
            if (prefix != null && uri != null) {
                namespaces.put(prefix, uri);
            }
        }
        return new XPathParser.NamespaceResolver() {
            @Override
            public String resolve(String prefix) {
                return namespaces.get(prefix);
            }
            @Override
            public String getDefaultElementNamespace() {
                String defaultNs = namespaces.get("");
                return defaultNs;
            }
        };
    }

    /**
     * Checks for functions disallowed in xsl:evaluate (XTDE3160).
     */
    private boolean containsDisallowedFunction(String exprLower) {
        if (exprLower.contains("current(") || exprLower.contains("current-group(")
                || exprLower.contains("current-grouping-key(")
                || exprLower.contains("current-merge-group(")
                || exprLower.contains("current-merge-key(")) {
            return true;
        }
        if (exprLower.contains("document(") || exprLower.contains("doc(")
                || exprLower.contains("doc-available(")
                || exprLower.contains("collection(")
                || exprLower.contains("uri-collection(")) {
            return true;
        }
        if (exprLower.contains("unparsed-text(") || exprLower.contains("unparsed-text-lines(")
                || exprLower.contains("unparsed-text-available(")) {
            return true;
        }
        if (exprLower.contains("system-property(") || exprLower.contains("key(")
                || exprLower.contains("regex-group(")
                || exprLower.contains("accumulator-before(")
                || exprLower.contains("accumulator-after(")
                || exprLower.contains("unparsed-entity-uri(")
                || exprLower.contains("unparsed-entity-public-id(")
                || exprLower.contains("available-environment-variables(")
                || exprLower.contains("environment-variable(")) {
            return true;
        }
        return false;
    }
    
    /**
     * Outputs the evaluation result, preserving typed values.
     * Follows the same pattern as SequenceOutputNode for proper type handling.
     */
    private void outputResult(XPathValue result, OutputHandler output) throws SAXException {
        if (result == null) {
            return;
        }
        
        // When building a sequence, add items directly to preserve type identity
        if (output instanceof SequenceBuilderOutputHandler) {
            SequenceBuilderOutputHandler seqBuilder = (SequenceBuilderOutputHandler) output;
            if (!seqBuilder.isInsideElement()) {
                if (result instanceof XPathSequence) {
                    for (XPathValue item : (XPathSequence) result) {
                        seqBuilder.addItem(item);
                    }
                } else if (result instanceof XPathNodeSet) {
                    for (XPathNode node : ((XPathNodeSet) result).getNodes()) {
                        seqBuilder.addItem(new XPathNodeSet(Collections.singletonList(node)));
                    }
                } else {
                    seqBuilder.addItem(result);
                }
                return;
            }
        }
        
        if (result instanceof XPathResultTreeFragment) {
            ((XPathResultTreeFragment) result).replayToOutput(output);
            output.setAtomicValuePending(false);
        } else if (result instanceof XPathSequence) {
            XPathSequence seq = (XPathSequence) result;
            boolean first = true;
            for (XPathValue item : seq) {
                if (!first) {
                    output.itemBoundary();
                }
                outputSequenceItem(item, output);
                first = false;
            }
        } else if (result instanceof XPathNode) {
            outputNode((XPathNode) result, output);
            output.setAtomicValuePending(false);
        } else if (result.isNodeSet()) {
            XPathNodeSet nodeSet = result.asNodeSet();
            boolean first = true;
            for (XPathNode node : nodeSet) {
                if (!first) {
                    output.itemBoundary();
                }
                outputNode(node, output);
                first = false;
            }
            output.setAtomicValuePending(false);
        } else {
            output.atomicValue(result);
        }
    }
    
    private void outputSequenceItem(XPathValue item, OutputHandler output) throws SAXException {
        if (item instanceof XPathNodeSet) {
            for (XPathNode node : ((XPathNodeSet) item).getNodes()) {
                outputNode(node, output);
            }
            output.setAtomicValuePending(false);
        } else if (item instanceof XPathResultTreeFragment) {
            ((XPathResultTreeFragment) item).replayToOutput(output);
            output.setAtomicValuePending(false);
        } else if (item instanceof XPathNode) {
            outputNode((XPathNode) item, output);
            output.setAtomicValuePending(false);
        } else {
            output.atomicValue(item);
        }
    }
    
    /**
     * Outputs a single node to the output handler.
     */
    private void outputNode(XPathNode node, OutputHandler output) throws SAXException {
        switch (node.getNodeType()) {
            case ELEMENT:
                String uri = node.getNamespaceURI() != null ? node.getNamespaceURI() : "";
                String localName = node.getLocalName();
                String prefix = node.getPrefix();
                String qName = (prefix != null && !prefix.isEmpty()) 
                    ? prefix + ":" + localName : localName;
                
                output.startElement(uri, localName, qName);
                
                Iterator<XPathNode> namespaces = node.getNamespaces();
                while (namespaces.hasNext()) {
                    XPathNode ns = namespaces.next();
                    String nsPrefix = ns.getLocalName();
                    String nsUri = ns.getStringValue();
                    if (!"xml".equals(nsPrefix) && nsUri != null) {
                        output.namespace(nsPrefix, nsUri);
                    }
                }
                
                Iterator<XPathNode> attrs = node.getAttributes();
                while (attrs.hasNext()) {
                    XPathNode attr = attrs.next();
                    String attrUri = attr.getNamespaceURI() != null ? attr.getNamespaceURI() : "";
                    String attrLocal = attr.getLocalName();
                    String attrPrefix = attr.getPrefix();
                    String attrQName = (attrPrefix != null && !attrPrefix.isEmpty()) 
                        ? attrPrefix + ":" + attrLocal : attrLocal;
                    output.attribute(attrUri, attrLocal, attrQName, attr.getStringValue());
                }
                
                Iterator<XPathNode> children = node.getChildren();
                while (children.hasNext()) {
                    outputNode(children.next(), output);
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
                Iterator<XPathNode> rootChildren = node.getChildren();
                while (rootChildren.hasNext()) {
                    outputNode(rootChildren.next(), output);
                }
                break;
                
            case ATTRIBUTE:
                String atUri = node.getNamespaceURI() != null ? node.getNamespaceURI() : "";
                String atLocal = node.getLocalName();
                String atPrefix = node.getPrefix();
                String atQName = (atPrefix != null && !atPrefix.isEmpty()) 
                    ? atPrefix + ":" + atLocal : atLocal;
                output.attribute(atUri, atLocal, atQName, node.getStringValue());
                break;
                
            case NAMESPACE:
                output.namespace(node.getLocalName(), node.getStringValue());
                break;
                
            default:
                break;
        }
    }

    @Override
    public StreamingCapability getStreamingCapability() {
        return StreamingCapability.NONE;
    }

    @Override
    public String toString() {
        return "EvaluateNode[xpath=" + xpathExpr + "]";
    }

}

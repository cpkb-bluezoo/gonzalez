/*
 * NextMatchNode.java
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
import java.util.Iterator;
import java.util.List;

import org.xml.sax.SAXException;

import org.bluezoo.gonzalez.transform.runtime.BasicTransformContext;
import org.bluezoo.gonzalez.transform.runtime.OutputHandlerUtils;
import org.bluezoo.gonzalez.transform.runtime.BufferOutputHandler;
import org.bluezoo.gonzalez.transform.runtime.OutputHandler;
import org.bluezoo.gonzalez.transform.runtime.SAXEventBuffer;
import org.bluezoo.gonzalez.transform.runtime.TemplateMatcher;
import org.bluezoo.gonzalez.transform.runtime.TransformContext;
import org.bluezoo.gonzalez.transform.xpath.expr.XPathException;
import org.bluezoo.gonzalez.transform.xpath.type.NodeType;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNode;
import org.bluezoo.gonzalez.transform.xpath.type.XPathResultTreeFragment;
import org.bluezoo.gonzalez.transform.xpath.type.XPathString;
import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;
import org.bluezoo.gonzalez.transform.compiler.TemplateParameter;
import org.bluezoo.gonzalez.transform.compiler.TemplateRule;

/**
 * NextMatchNode XSLT instruction.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public class NextMatchNode extends XSLTInstruction {
    private final List<WithParamNode> params;
    
    public NextMatchNode(List<WithParamNode> params) {
        this.params = params != null ? params : Collections.emptyList();
    }
    
    @Override public String getInstructionName() { return "next-match"; }
    
    @Override
    public void execute(TransformContext context, OutputHandler output) throws SAXException {
        TemplateRule currentRule = context.getCurrentTemplateRule();
        if (currentRule == null) {
            throw new SAXException("XTDE0560: xsl:next-match cannot be used when there is no current template rule");
        }
        
        // XTDE0560: context item must not be absent
        if (context.isContextItemUndefined()) {
            throw new SAXException("XTDE0560: xsl:next-match cannot be used when the context item is absent");
        }
        
        // XSLT 3.0: check if context item is atomic (non-node)
        if (context instanceof BasicTransformContext) {
            XPathValue contextItem = ((BasicTransformContext) context).getContextItem();
            if (contextItem != null && !(contextItem instanceof XPathNode) && !contextItem.isNodeSet()) {
                executeNextMatchForAtomic(contextItem, currentRule, context, output);
                return;
            }
        }
        
        XPathNode currentNode = context.getXsltCurrentNode();
        if (currentNode == null) {
            throw new SAXException("XTDE0560: xsl:next-match cannot be used when there is no current template rule");
        }
        
        // Find the next matching template
        TemplateMatcher matcher = context.getTemplateMatcher();
        TemplateRule nextRule = matcher.findNextMatch(
            currentNode, context.getCurrentMode(), currentRule, context);
        
        if (nextRule == null || TemplateMatcher.isBuiltIn(nextRule)) {
            java.util.Map<String, XPathValue> paramValues = evaluateParams(context);
            
            // Merge tunnel params from with-param nodes into context
            TransformContext builtInContext = context;
            java.util.Map<String, XPathValue> newTunnelParams = new java.util.HashMap<>();
            for (WithParamNode param : params) {
                if (param.isTunnel()) {
                    try {
                        newTunnelParams.put(param.getName(), param.evaluate(context));
                    } catch (XPathException e) {
                        throw new SAXException("Error evaluating tunnel param: " + e.getMessage(), e);
                    }
                }
            }
            if (!newTunnelParams.isEmpty()) {
                builtInContext = builtInContext.withTunnelParameters(newTunnelParams);
            }
            
            String type = nextRule != null ? TemplateMatcher.getBuiltInType(nextRule) : null;
            if (type == null) {
                if (currentNode.isElement() || currentNode.getParent() == null) {
                    applyTemplatesToChildren(currentNode, builtInContext, output, paramValues);
                } else if (currentNode.isText() || currentNode.isAttribute()) {
                    String value = currentNode.getStringValue();
                    if (value != null && !value.isEmpty()) {
                        output.characters(value);
                    }
                }
            } else {
                executeBuiltIn(type, currentNode, builtInContext, output, paramValues);
            }
            return;
        }
        
        // Execute the next template with parameters
        TransformContext execContext = context.pushVariableScope()
            .withCurrentTemplateRule(nextRule);
        
        // Collect and merge tunnel parameters into context
        java.util.Map<String, XPathValue> newTunnelParams = new java.util.HashMap<>();
        for (WithParamNode param : params) {
            if (param.isTunnel()) {
                try {
                    newTunnelParams.put(param.getName(), param.evaluate(context));
                } catch (XPathException e) {
                    throw new SAXException("Error evaluating tunnel param: " + e.getMessage(), e);
                }
            }
        }
        if (!newTunnelParams.isEmpty()) {
            execContext = execContext.withTunnelParameters(newTunnelParams);
        }
        
        // Process each template parameter
        for (TemplateParameter templateParam : nextRule.getParameters()) {
            XPathValue value = null;
            boolean found = false;
            
            if (templateParam.isTunnel()) {
                // Tunnel param: check tunnel with-params, then context tunnel params
                for (WithParamNode param : params) {
                    if (param.isTunnel() && param.getName().equals(templateParam.getName())) {
                        try {
                            value = param.evaluate(context);
                            found = true;
                        } catch (XPathException e) {
                            throw new SAXException("Error evaluating tunnel param: " + e.getMessage(), e);
                        }
                        break;
                    }
                }
                if (!found) {
                    value = execContext.getTunnelParameters().get(templateParam.getName());
                    found = (value != null);
                }
            } else {
                // Non-tunnel param: only accept non-tunnel with-params
                for (WithParamNode param : params) {
                    if (!param.isTunnel() && param.getName().equals(templateParam.getName())) {
                        try {
                            value = param.evaluate(context);
                            found = true;
                        } catch (XPathException e) {
                            throw new SAXException("Error evaluating with-param: " + e.getMessage(), e);
                        }
                        break;
                    }
                }
            }
            
            if (!found) {
                // Use default value
                if (templateParam.getSelectExpr() != null) {
                    try {
                        value = templateParam.getSelectExpr().evaluate(execContext);
                    } catch (XPathException e) {
                        throw new SAXException("Error evaluating parameter default: " + e.getMessage(), e);
                    }
                } else {
                    value = templateParam.evaluateDefaultContent(execContext);
                }
                try {
                    value = templateParam.coerceDefaultValue(value);
                } catch (XPathException e) {
                    throw new SAXException("Error coercing parameter default: " + e.getMessage(), e);
                }
            }
            
            execContext.getVariableScope().bind(
                templateParam.getNamespaceURI(), templateParam.getLocalName(), value);
        }
        
        // Bind non-tunnel with-params that don't match any template parameter
        for (WithParamNode param : params) {
            if (!param.isTunnel()) {
                String ns = param.getNamespaceURI();
                String ln = param.getLocalName();
                if (execContext.getVariableScope().lookup(ns, ln) == null) {
                    try {
                        XPathValue value = param.evaluate(context);
                        execContext.getVariableScope().bind(ns, ln, value);
                    } catch (XPathException e) {
                        throw new SAXException("Error evaluating with-param: " + e.getMessage(), e);
                    }
                }
            }
        }
        
        // Execute the template body
        nextRule.getBody().execute(execContext, output);
    }
    
    private void executeNextMatchForAtomic(XPathValue atomicValue, TemplateRule currentRule,
            TransformContext context, OutputHandler output) throws SAXException {
        TemplateMatcher matcher = context.getTemplateMatcher();
        TemplateRule nextRule = matcher.findNextMatchForAtomicValue(
            atomicValue, context.getCurrentMode(), currentRule, context);
        
        if (nextRule == null) {
            // Built-in template for atomic values: output string value
            String sv = atomicValue.asString();
            if (sv != null && !sv.isEmpty()) {
                output.characters(sv);
            }
            return;
        }
        
        TransformContext execContext = context.pushVariableScope()
            .withCurrentTemplateRule(nextRule);
        
        for (WithParamNode param : params) {
            try {
                XPathValue value = param.evaluate(context);
                execContext.getVariableScope().bind(
                    param.getNamespaceURI(), param.getLocalName(), value);
            } catch (XPathException e) {
                throw new SAXException("Error evaluating with-param: " + e.getMessage(), e);
            }
        }
        
        for (TemplateParameter templateParam : nextRule.getParameters()) {
            if (execContext.getVariableScope().lookup(
                    templateParam.getNamespaceURI(), templateParam.getLocalName()) == null) {
                XPathValue defaultValue = null;
                if (templateParam.getSelectExpr() != null) {
                    try {
                        defaultValue = templateParam.getSelectExpr().evaluate(execContext);
                    } catch (XPathException e) {
                        throw new SAXException(
                            "Error evaluating parameter default: " + e.getMessage(), e);
                    }
                } else {
                    defaultValue = templateParam.evaluateDefaultContent(execContext);
                }
                try {
                    defaultValue = templateParam.coerceDefaultValue(defaultValue);
                } catch (XPathException e) {
                    throw new SAXException(
                        "Error coercing parameter default: " + e.getMessage(), e);
                }
                execContext.getVariableScope().bind(
                    templateParam.getNamespaceURI(), templateParam.getLocalName(), defaultValue);
            }
        }
        
        nextRule.getBody().execute(execContext, output);
    }
    
    private java.util.Map<String, XPathValue> evaluateParams(TransformContext context)
            throws SAXException {
        java.util.Map<String, XPathValue> paramValues = new java.util.HashMap<>();
        for (WithParamNode param : params) {
            if (param.isTunnel()) {
                continue;
            }
            try {
                XPathValue value = param.evaluate(context);
                paramValues.put(param.getLocalName(), value);
            } catch (XPathException e) {
                throw new SAXException("Error evaluating with-param: " + e.getMessage(), e);
            }
        }
        return paramValues;
    }
    
    private void bindParamsToTemplate(TemplateRule rule, TransformContext execContext,
            java.util.Map<String, XPathValue> paramValues) throws SAXException {
        for (TemplateParameter templateParam : rule.getParameters()) {
            XPathValue value = null;
            boolean found = false;
            
            if (templateParam.isTunnel()) {
                // Tunnel param: check tunnel with-params, then context tunnel params
                for (WithParamNode param : params) {
                    if (param.isTunnel() && param.getName().equals(templateParam.getName())) {
                        try {
                            value = param.evaluate(execContext);
                            found = true;
                        } catch (XPathException e) {
                            throw new SAXException("Error evaluating tunnel param: " + e.getMessage(), e);
                        }
                        break;
                    }
                }
                if (!found) {
                    value = execContext.getTunnelParameters().get(templateParam.getName());
                    found = (value != null);
                }
            } else {
                // Non-tunnel param: check paramValues by local name
                String paramName = templateParam.getLocalName();
                if (paramValues.containsKey(paramName)) {
                    value = paramValues.get(paramName);
                    found = true;
                }
            }
            
            if (!found) {
                if (templateParam.getSelectExpr() != null) {
                    try {
                        value = templateParam.getSelectExpr().evaluate(execContext);
                    } catch (XPathException e) {
                        throw new SAXException(
                            "Error evaluating parameter default: " + e.getMessage(), e);
                    }
                } else {
                    value = templateParam.evaluateDefaultContent(execContext);
                }
                try {
                    value = templateParam.coerceDefaultValue(value);
                } catch (XPathException e) {
                    throw new SAXException(
                        "Error coercing parameter default: " + e.getMessage(), e);
                }
            }
            
            execContext.getVariableScope().bind(
                templateParam.getNamespaceURI(), templateParam.getLocalName(), value);
        }
    }
    
    private void applyTemplatesToAttributes(XPathNode node, TransformContext context,
                                            OutputHandler output) throws SAXException {
        List<XPathNode> attrs = new ArrayList<>();
        Iterator<XPathNode> it = node.getAttributes();
        while (it.hasNext()) {
            attrs.add(it.next());
        }
        int size = attrs.size();
        int pos = 1;
        for (XPathNode attr : attrs) {
            TransformContext attrContext = context.withContextNode(attr)
                .withPositionAndSize(pos++, size);
            TemplateMatcher matcher = context.getTemplateMatcher();
            TemplateRule rule = matcher.findMatch(attr, context.getCurrentMode(), attrContext);
            if (rule != null) {
                TransformContext execContext = attrContext.withCurrentTemplateRule(rule);
                if (TemplateMatcher.isBuiltIn(rule)) {
                    executeBuiltIn(TemplateMatcher.getBuiltInType(rule), attr, execContext, output);
                } else {
                    rule.getBody().execute(execContext, output);
                }
            }
        }
    }

    private void applyTemplatesToChildren(XPathNode node, TransformContext context, 
                                          OutputHandler output) throws SAXException {
        applyTemplatesToChildren(node, context, output, java.util.Collections.emptyMap());
    }
    
    private void applyTemplatesToChildren(XPathNode node, TransformContext context, 
            OutputHandler output, java.util.Map<String, XPathValue> paramValues)
            throws SAXException {
        List<XPathNode> children = new ArrayList<>();
        Iterator<XPathNode> it = node.getChildren();
        while (it.hasNext()) {
            children.add(it.next());
        }
        int size = children.size();
        int pos = 1;
        for (XPathNode child : children) {
            TransformContext childContext = context.withContextNode(child)
                .withPositionAndSize(pos++, size);
            TemplateMatcher matcher = context.getTemplateMatcher();
            TemplateRule rule = matcher.findMatch(child, context.getCurrentMode(), childContext);
            if (rule != null) {
                TransformContext execContext = childContext.pushVariableScope()
                    .withCurrentTemplateRule(rule);
                if (TemplateMatcher.isBuiltIn(rule)) {
                    executeBuiltIn(TemplateMatcher.getBuiltInType(rule), child, execContext, output,
                        paramValues);
                } else {
                    bindParamsToTemplate(rule, execContext, paramValues);
                    rule.getBody().execute(execContext, output);
                }
            }
        }
    }
    
    private void executeBuiltIn(String type, XPathNode node,
            TransformContext context, OutputHandler output) throws SAXException {
        executeBuiltIn(type, node, context, output, java.util.Collections.emptyMap());
    }
    
    private void executeBuiltIn(String type, XPathNode node,
            TransformContext context, OutputHandler output,
            java.util.Map<String, XPathValue> paramValues) throws SAXException {
        switch (type) {
            case "element-or-root":
                applyTemplatesToChildren(node, context, output, paramValues);
                break;
            case "shallow-skip":
                if (node.isElement()) {
                    applyTemplatesToAttributes(node, context, output);
                }
                applyTemplatesToChildren(node, context, output, paramValues);
                break;
            case "text-or-attribute":
            case "text-only-copy":
                String value = node.getStringValue();
                if (value != null && !value.isEmpty()) {
                    output.characters(value);
                }
                break;
            case "shallow-copy":
                executeShallowCopy(node, context, output);
                break;
            case "deep-copy":
                executeDeepCopy(node, output);
                break;
            case "fail":
                throw new SAXException("XTDE0555: No matching template found for node: " +
                    node.getNodeType() + " (mode has on-no-match='fail')");
            case "empty":
                break;
        }
    }
    
    private void executeShallowCopy(XPathNode node, TransformContext context,
            OutputHandler output) throws SAXException {
        NodeType nodeType = node.getNodeType();
        switch (nodeType) {
            case ELEMENT:
                String uri = node.getNamespaceURI();
                String effectiveUri = OutputHandlerUtils.effectiveUri(uri);
                String localName = node.getLocalName();
                String prefix = node.getPrefix();
                String qName = OutputHandlerUtils.buildQName(prefix, localName);
                output.startElement(effectiveUri, localName, qName);
                Iterator<XPathNode> namespaces = node.getNamespaces();
                while (namespaces.hasNext()) {
                    XPathNode ns = namespaces.next();
                    String nsPrefix = ns.getLocalName();
                    String nsUri = ns.getStringValue();
                    if (!"xml".equals(nsPrefix) && nsUri != null && !nsUri.isEmpty()) {
                        output.namespace(nsPrefix, nsUri);
                    }
                }
                applyTemplatesToAttributes(node, context, output);
                applyTemplatesToChildren(node, context, output);
                output.endElement(effectiveUri, localName, qName);
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
            case ATTRIBUTE:
                String atUri = OutputHandlerUtils.effectiveUri(node.getNamespaceURI());
                String atLocal = node.getLocalName();
                String atPrefix = node.getPrefix();
                String atQName = OutputHandlerUtils.buildQName(atPrefix, atLocal);
                output.attribute(atUri, atLocal, atQName, node.getStringValue());
                break;
            case ROOT:
                applyTemplatesToChildren(node, context, output);
                break;
            default:
                break;
        }
    }
    
    private void executeDeepCopy(XPathNode node, OutputHandler output) throws SAXException {
        ValueOutputHelper.deepCopyNode(node, output);
    }
}

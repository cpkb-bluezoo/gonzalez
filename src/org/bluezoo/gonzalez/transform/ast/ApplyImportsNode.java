/*
 * ApplyImportsNode.java
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.xml.sax.SAXException;

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
 * ApplyImportsNode XSLT instruction.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public class ApplyImportsNode extends XSLTInstruction {
    private final List<WithParamNode> params;
    
    public ApplyImportsNode() {
        this(new ArrayList<>());
    }
    
    public ApplyImportsNode(List<WithParamNode> params) {
        this.params = params;
    }
    
    @Override public String getInstructionName() { return "apply-imports"; }
    @Override public void execute(TransformContext context, 
                                  OutputHandler output) throws SAXException {
        TemplateRule currentRule = context.getCurrentTemplateRule();
        XPathNode currentNode = context.getXsltCurrentNode();
        
        if (currentRule == null || currentNode == null) {
            // XTDE0560: xsl:apply-imports is not allowed when the current template rule is absent
            throw new SAXException("XTDE0560: xsl:apply-imports cannot be used when there is no current template rule");
        }
        
        // Find the matching template from imported stylesheets
        TemplateMatcher matcher = context.getTemplateMatcher();
        TemplateRule importedRule = matcher.findImportMatch(
            currentNode, context.getCurrentMode(), currentRule, context);
        
        if (importedRule == null || TemplateMatcher.isBuiltIn(importedRule)) {
            String type = importedRule != null ? TemplateMatcher.getBuiltInType(importedRule) : null;
            if (type != null) {
                executeBuiltIn(type, currentNode, context, output);
            } else {
                if (currentNode.isElement() || currentNode.getParent() == null) {
                    applyTemplatesToChildren(currentNode, context, output);
                } else if (currentNode.isText() || currentNode.isAttribute()) {
                    String value = currentNode.getStringValue();
                    if (value != null && !value.isEmpty()) {
                        output.characters(value);
                    }
                }
            }
            return;
        }
        
        // Execute the imported template
        TransformContext execContext = context.pushVariableScope()
            .withCurrentTemplateRule(importedRule);
        
        // XSLT 2.0 tunnel parameter support for apply-imports
        Map<String, XPathValue> newTunnelParams = new HashMap<>();
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
        Set<String> passedParams = new HashSet<>();
        for (TemplateParameter templateParam : importedRule.getParameters()) {
            XPathValue value = null;
            boolean found = false;
            
            if (templateParam.isTunnel()) {
                // Tunnel param: first check tunnel with-param, then context tunnel params
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
                // Non-tunnel param: only accept non-tunnel with-param
                for (WithParamNode param : params) {
                    if (!param.isTunnel() && param.getName().equals(templateParam.getName())) {
                        try {
                            value = param.evaluate(context);
                            found = true;
                        } catch (XPathException e) {
                            throw new SAXException("Error evaluating param: " + e.getMessage(), e);
                        }
                        break;
                    }
                }
            }
            
            if (found && value != null) {
                execContext.getVariableScope().bind(templateParam.getNamespaceURI(), templateParam.getLocalName(), value);
                passedParams.add(templateParam.getName());
            } else {
                // Use default value
                XPathValue defaultValue = null;
                if (templateParam.getSelectExpr() != null) {
                    try {
                        defaultValue = templateParam.getSelectExpr().evaluate(execContext);
                    } catch (XPathException e) {
                        throw new SAXException("Error evaluating parameter default: " + e.getMessage(), e);
                    }
                } else if (templateParam.getDefaultContent() != null) {
                    SAXEventBuffer buffer = new SAXEventBuffer();
                    templateParam.getDefaultContent().execute(execContext, new BufferOutputHandler(buffer));
                    defaultValue = new XPathResultTreeFragment(buffer);
                } else {
                    defaultValue = new XPathString("");
                }
                if (defaultValue != null) {
                    execContext.getVariableScope().bind(templateParam.getNamespaceURI(), templateParam.getLocalName(), defaultValue);
                }
            }
        }
        
        importedRule.getBody().execute(execContext, output);
    }
    
    private void applyTemplatesToChildren(XPathNode node, TransformContext context, 
                                          OutputHandler output) throws SAXException {
        // First pass: count children
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
                    executeBuiltIn(TemplateMatcher.getBuiltInType(rule), child, execContext, output);
                } else {
                    rule.getBody().execute(execContext, output);
                }
            }
        }
    }
    
    private void executeBuiltIn(String type, XPathNode node,
            TransformContext context, OutputHandler output) throws SAXException {
        switch (type) {
            case "element-or-root":
                // Apply templates to children
                applyTemplatesToChildren(node, context, output);
                break;
            case "text-or-attribute":
            case "text-only-copy":
                String value = node.getStringValue();
                if (value != null && !value.isEmpty()) {
                    output.characters(value);
                }
                break;
            case "shallow-copy":
                // Copy the node (without content for elements) then apply-templates to children
                executeShallowCopy(node, context, output);
                break;
            case "deep-copy":
                // Copy the entire subtree
                executeDeepCopy(node, output);
                break;
            case "shallow-skip":
                // Skip the node but apply-templates to children
                applyTemplatesToChildren(node, context, output);
                break;
            case "fail":
                // Raise an error - no template matched
                throw new SAXException("XTDE0555: No matching template found for node: " + 
                    node.getNodeType() + " (mode has on-no-match='fail')");
            // "empty" type does nothing
        }
    }
    
    private void executeShallowCopy(XPathNode node, TransformContext context,
            OutputHandler output) throws SAXException {
        NodeType nodeType = node.getNodeType();
        switch (nodeType) {
            case ELEMENT:
                String uri = node.getNamespaceURI();
                String localName = node.getLocalName();
                String prefix = node.getPrefix();
                String qName = prefix != null && !prefix.isEmpty() ? prefix + ":" + localName : localName;
                
                output.startElement(uri != null ? uri : "", localName, qName);
                
                // Copy attributes
                Iterator<XPathNode> attrIter = node.getAttributes();
                while (attrIter.hasNext()) {
                    XPathNode attr = attrIter.next();
                    String aUri = attr.getNamespaceURI();
                    String aLocal = attr.getLocalName();
                    String aPrefix = attr.getPrefix();
                    String aQName = aPrefix != null && !aPrefix.isEmpty() ? aPrefix + ":" + aLocal : aLocal;
                    output.attribute(aUri != null ? aUri : "", aLocal, aQName, attr.getStringValue());
                }
                
                applyTemplatesToChildren(node, context, output);
                output.endElement(uri != null ? uri : "", localName, qName);
                break;
                
            case TEXT:
                output.characters(node.getStringValue());
                break;
                
            case ATTRIBUTE:
                // Copy the attribute - note: will only work if there's a pending element
                String attUri = node.getNamespaceURI();
                String attLocal = node.getLocalName();
                String attPrefix = node.getPrefix();
                String attQName = attPrefix != null && !attPrefix.isEmpty() ? attPrefix + ":" + attLocal : attLocal;
                output.attribute(attUri != null ? attUri : "", attLocal, attQName, node.getStringValue());
                break;
                
            case COMMENT:
                output.comment(node.getStringValue());
                break;
                
            case PROCESSING_INSTRUCTION:
                output.processingInstruction(node.getLocalName(), node.getStringValue());
                break;
                
            case ROOT:
                applyTemplatesToChildren(node, context, output);
                break;
                
            default:
                break;
        }
    }
    
    private void executeDeepCopy(XPathNode node, OutputHandler output) throws SAXException {
        NodeType nodeType = node.getNodeType();
        switch (nodeType) {
            case ELEMENT:
                String uri = node.getNamespaceURI();
                String localName = node.getLocalName();
                String prefix = node.getPrefix();
                String qName = prefix != null && !prefix.isEmpty() ? prefix + ":" + localName : localName;
                
                output.startElement(uri != null ? uri : "", localName, qName);
                
                // Copy attributes
                Iterator<XPathNode> attrIter = node.getAttributes();
                while (attrIter.hasNext()) {
                    XPathNode attr = attrIter.next();
                    String aUri = attr.getNamespaceURI();
                    String aLocal = attr.getLocalName();
                    String aPrefix = attr.getPrefix();
                    String aQName = aPrefix != null && !aPrefix.isEmpty() ? aPrefix + ":" + aLocal : aLocal;
                    output.attribute(aUri != null ? aUri : "", aLocal, aQName, attr.getStringValue());
                }
                
                // Recursively copy children
                Iterator<XPathNode> children = node.getChildren();
                while (children.hasNext()) {
                    executeDeepCopy(children.next(), output);
                }
                
                output.endElement(uri != null ? uri : "", localName, qName);
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
                    executeDeepCopy(rootChildren.next(), output);
                }
                break;
                
            default:
                break;
        }
    }
}

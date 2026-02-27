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
        XPathNode currentNode = context.getXsltCurrentNode();
        
        if (currentRule == null || currentNode == null) {
            // XTDE0560: xsl:next-match is not allowed when the current template rule is absent
            throw new SAXException("XTDE0560: xsl:next-match cannot be used when there is no current template rule");
        }
        
        // Find the next matching template
        TemplateMatcher matcher = context.getTemplateMatcher();
        TemplateRule nextRule = matcher.findNextMatch(
            currentNode, context.getCurrentMode(), currentRule, context);
        
        if (nextRule == null || TemplateMatcher.isBuiltIn(nextRule)) {
            String type = nextRule != null ? TemplateMatcher.getBuiltInType(nextRule) : null;
            if (type == null) {
                if (currentNode.isElement() || currentNode.getParent() == null) {
                    applyTemplatesToChildren(currentNode, context, output);
                } else if (currentNode.isText() || currentNode.isAttribute()) {
                    String value = currentNode.getStringValue();
                    if (value != null && !value.isEmpty()) {
                        output.characters(value);
                    }
                }
            } else {
                executeBuiltIn(type, currentNode, context, output);
            }
            return;
        }
        
        // Execute the next template with parameters
        TransformContext execContext = context.pushVariableScope()
            .withCurrentTemplateRule(nextRule);
        
        // Bind with-param values
        for (WithParamNode param : params) {
            try {
                XPathValue value = param.evaluate(context);
                execContext.getVariableScope().bind(param.getNamespaceURI(), param.getLocalName(), value);
            } catch (XPathException e) {
                throw new SAXException("Error evaluating with-param: " + e.getMessage(), e);
            }
        }
        
        // Bind template parameter defaults for any not provided
        for (TemplateParameter templateParam : nextRule.getParameters()) {
            if (execContext.getVariableScope().lookup(templateParam.getNamespaceURI(), templateParam.getLocalName()) == null) {
                XPathValue defaultValue = null;
                if (templateParam.getSelectExpr() != null) {
                    try {
                        defaultValue = templateParam.getSelectExpr().evaluate(execContext);
                    } catch (XPathException e) {
                        throw new SAXException("Error evaluating parameter default: " + e.getMessage(), e);
                    }
                } else if (templateParam.getDefaultContent() != null) {
                    SAXEventBuffer buffer = new SAXEventBuffer();
                    BufferOutputHandler bufOutput = new BufferOutputHandler(buffer);
                    templateParam.getDefaultContent().execute(execContext, bufOutput);
                    defaultValue = new XPathResultTreeFragment(buffer);
                } else {
                    defaultValue = new XPathString("");
                }
                execContext.getVariableScope().bind(templateParam.getNamespaceURI(), templateParam.getLocalName(), defaultValue);
            }
        }
        
        // Execute the template body
        nextRule.getBody().execute(execContext, output);
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
                TransformContext execContext = childContext.withCurrentTemplateRule(rule);
                if (TemplateMatcher.isBuiltIn(rule)) {
                    // Execute built-in template
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
            case "shallow-skip":
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
                String localName = node.getLocalName();
                String prefix = node.getPrefix();
                String qName = prefix != null && !prefix.isEmpty() ? prefix + ":" + localName : localName;
                output.startElement(uri != null ? uri : "", localName, qName);
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
            case COMMENT:
                output.comment(node.getStringValue());
                break;
            case PROCESSING_INSTRUCTION:
                output.processingInstruction(node.getLocalName(), node.getStringValue());
                break;
            case ATTRIBUTE:
                String atUri = node.getNamespaceURI();
                String atLocal = node.getLocalName();
                String atPrefix = node.getPrefix();
                String atQName = atPrefix != null && !atPrefix.isEmpty() ? atPrefix + ":" + atLocal : atLocal;
                output.attribute(atUri != null ? atUri : "", atLocal, atQName, node.getStringValue());
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
                Iterator<XPathNode> attrIter = node.getAttributes();
                while (attrIter.hasNext()) {
                    XPathNode attr = attrIter.next();
                    String aUri = attr.getNamespaceURI();
                    String aLocal = attr.getLocalName();
                    String aPrefix = attr.getPrefix();
                    String aQName = aPrefix != null && !aPrefix.isEmpty() ? aPrefix + ":" + aLocal : aLocal;
                    output.attribute(aUri != null ? aUri : "", aLocal, aQName, attr.getStringValue());
                }
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

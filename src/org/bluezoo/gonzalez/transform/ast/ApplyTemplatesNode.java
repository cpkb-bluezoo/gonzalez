/*
 * ApplyTemplatesNode.java
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
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.xml.sax.SAXException;

import org.bluezoo.gonzalez.transform.compiler.SortSpec;
import org.bluezoo.gonzalez.transform.compiler.SequenceBuilderOutputHandler;
import org.bluezoo.gonzalez.transform.runtime.AccumulatorManager;
import org.bluezoo.gonzalez.transform.runtime.BasicTransformContext;
import org.bluezoo.gonzalez.transform.runtime.BufferOutputHandler;
import org.bluezoo.gonzalez.transform.runtime.OutputHandler;
import org.bluezoo.gonzalez.transform.runtime.SAXEventBuffer;
import org.bluezoo.gonzalez.transform.runtime.TemplateMatcher;
import org.bluezoo.gonzalez.transform.runtime.TransformContext;
import org.bluezoo.gonzalez.transform.xpath.XPathExpression;
import org.bluezoo.gonzalez.transform.xpath.expr.XPathException;
import org.bluezoo.gonzalez.transform.xpath.type.NodeType;
import org.bluezoo.gonzalez.transform.xpath.type.SequenceType;
import org.bluezoo.gonzalez.transform.xpath.type.XPathBoolean;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNode;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNodeSet;
import org.bluezoo.gonzalez.transform.xpath.type.XPathResultTreeFragment;
import org.bluezoo.gonzalez.transform.xpath.type.XPathSequence;
import org.bluezoo.gonzalez.transform.xpath.type.XPathString;
import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;
import org.bluezoo.gonzalez.transform.compiler.TemplateParameter;
import org.bluezoo.gonzalez.transform.compiler.TemplateRule;

/**
 * ApplyTemplatesNode XSLT instruction.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public class ApplyTemplatesNode extends XSLTInstruction {
    private final XPathExpression selectExpr;
    private final String mode;
    private final List<SortSpec> sorts;
    private final List<WithParamNode> params;
    public ApplyTemplatesNode(XPathExpression selectExpr, String mode, List<SortSpec> sorts, List<WithParamNode> params) {
        this.selectExpr = selectExpr;
        this.mode = mode;
        this.sorts = sorts;
        this.params = params;
    }
    @Override public String getInstructionName() { return "apply-templates"; }
    @Override public void execute(TransformContext context, 
                                  OutputHandler output) throws SAXException {
        try {
            // Get nodes to process
            List<XPathNode> nodes;
            if (selectExpr != null) {
                XPathValue result = selectExpr.evaluate(context);
                if (result instanceof XPathNodeSet) {
                    nodes = ((XPathNodeSet) result).getNodes();
                } else if (result instanceof XPathResultTreeFragment) {
                    // Convert RTF to node-set (XSLT 2.0+)
                    nodes = ((XPathResultTreeFragment) result).asNodeSet().getNodes();
                } else if (result instanceof XPathNode) {
                    // Single node (e.g., from sequence construction)
                    nodes = Collections.singletonList((XPathNode) result);
                } else if (result.isSequence()) {
                    // XPath 2.0+ sequence - extract nodes and atomic values (XSLT 3.0)
                    nodes = new ArrayList<>();
                    List<XPathValue> atomicValues = new ArrayList<>();
                    Iterator<XPathValue> iter = result.sequenceIterator();
                    while (iter.hasNext()) {
                        XPathValue item = iter.next();
                        if (item instanceof XPathNode) {
                            nodes.add((XPathNode) item);
                        } else if (item instanceof XPathNodeSet) {
                            nodes.addAll(((XPathNodeSet) item).getNodes());
                        } else {
                            // Atomic value (XSLT 3.0)
                            atomicValues.add(item);
                        }
                    }
                    
                    // Process atomic values first if there are templates that can match them
                    if (!atomicValues.isEmpty() && context instanceof BasicTransformContext) {
                        BasicTransformContext btc = (BasicTransformContext) context;
                        TemplateMatcher matcher = context.getTemplateMatcher();
                        String effectiveMode = mode;
                        if ("#current".equals(mode)) {
                            effectiveMode = context.getCurrentMode();
                        }
                        int atomicPosition = 1;
                        int atomicSize = atomicValues.size();
                        for (XPathValue atomicValue : atomicValues) {
                            TemplateRule rule = matcher.findMatchForAtomicValue(atomicValue, effectiveMode, context);
                            if (rule != null) {
                                // Execute the template with the atomic value as context item
                                TransformContext atomicContext = btc
                                    .withContextItem(atomicValue)
                                    .withPositionAndSize(atomicPosition, atomicSize)
                                    .pushVariableScope()
                                    .withCurrentTemplateRule(rule);
                                
                                // Set parameters
                                for (WithParamNode param : params) {
                                    if (!param.isTunnel()) {
                                        try {
                                            atomicContext = (TransformContext) atomicContext.withVariable(
                                                null, param.getName(), param.evaluate(context));
                                        } catch (XPathException e) {
                                            throw new SAXException("Error evaluating param: " + e.getMessage(), e);
                                        }
                                    }
                                }
                                
                                rule.getBody().execute(atomicContext, output);
                            }
                            atomicPosition++;
                        }
                    }
                    
                    if (nodes.isEmpty()) {
                        return; // No nodes in sequence (atomic values already processed)
                    }
                } else {
                    // XTTE0520: select must evaluate to node()*
                    throw new SAXException("XTTE0520: The select expression of xsl:apply-templates" +
                        " must return nodes, but got an atomic value: " + result);
                }
            } else {
                // Default: select="child::node()"
                nodes = new ArrayList<>();
                Iterator<XPathNode> children = 
                    context.getContextNode().getChildren();
                while (children.hasNext()) {
                    nodes.add(children.next());
                }
            }
            
            // Apply sorting if specified
            if (sorts != null && !sorts.isEmpty()) {
                ForEachNode.sortNodesStatic(nodes, sorts, context);
            }
            
            // Process each node
            int size = nodes.size();
            int position = 1;
            for (XPathNode node : nodes) {
                // Use withXsltCurrentNode to update both context node and XSLT current()
                TransformContext nodeContext;
                if (context instanceof BasicTransformContext) {
                    nodeContext = ((BasicTransformContext) context)
                        .withXsltCurrentNode(node).withPositionAndSize(position, size);
                } else {
                    nodeContext = context.withContextNode(node).withPositionAndSize(position, size);
                }
                // Resolve special mode values
                String effectiveMode = mode;
                if ("#current".equals(mode)) {
                    // Use the current mode from the context
                    effectiveMode = context.getCurrentMode();
                }
                
                if (effectiveMode != null) {
                    nodeContext = nodeContext.withMode(effectiveMode);
                }
                
                // Fire pre-descent accumulator rules
                AccumulatorManager accMgr = null;
                if (nodeContext instanceof BasicTransformContext) {
                    accMgr = ((BasicTransformContext) nodeContext).getAccumulatorManager();
                }
                if (accMgr != null) {
                    accMgr.notifyStartElement(node);
                }
                
                // Find and execute matching template
                TemplateMatcher matcher = context.getTemplateMatcher();
                TemplateRule rule = matcher.findMatch(node, effectiveMode, nodeContext);
                
                if (rule != null) {
                    // Push scope and set current template rule (needed for apply-imports)
                    TransformContext execContext = 
                        nodeContext.pushVariableScope()
                            .withCurrentTemplateRule(rule);
                    
                    // XSLT 2.0 tunnel parameter support:
                    // - Non-tunnel with-param matches non-tunnel param only
                    // - Tunnel with-param matches tunnel param only
                    // - Tunnel params also receive values from context's tunnel params
                    
                    // Collect new tunnel parameters from with-param nodes
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
                    
                    // Merge with existing tunnel params and update context
                    if (!newTunnelParams.isEmpty()) {
                        execContext = (TransformContext) execContext.withTunnelParameters(newTunnelParams);
                    }
                    
                    // Collect passed parameter names (only non-tunnel, matching non-tunnel template params)
                    Set<String> passedParams = new HashSet<>();
                    
                    // Process each template parameter
                    for (TemplateParameter templateParam : rule.getParameters()) {
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
                            // If not passed directly, check context's tunnel params
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
                            // XTDE0700: required parameter must be supplied
                            if (templateParam.isRequired()) {
                                throw new SAXException("XTDE0700: Template parameter $" +
                                    templateParam.getLocalName() + " is required but no value was supplied");
                            }
                            // Use default value
                            XPathValue defaultValue = null;
                            if (templateParam.getSelectExpr() != null) {
                                try {
                                    defaultValue = templateParam.getSelectExpr().evaluate(execContext);
                                } catch (XPathException e) {
                                    throw new SAXException("Error evaluating param default: " + e.getMessage(), e);
                                }
                            } else if (templateParam.getDefaultContent() != null) {
                                // Execute content to get RTF as default value
                                SAXEventBuffer buffer = new SAXEventBuffer();
                                templateParam.getDefaultContent().execute(execContext, new BufferOutputHandler(buffer));
                                defaultValue = new XPathResultTreeFragment(buffer);
                            } else {
                                defaultValue = new XPathString(""); // Empty default
                            }
                            execContext.getVariableScope().bind(templateParam.getNamespaceURI(), templateParam.getLocalName(), defaultValue);
                        }
                    }
                    
                    if (TemplateMatcher.isBuiltIn(rule)) {
                        executeBuiltIn(TemplateMatcher
                            .getBuiltInType(rule), node, execContext, output);
                    } else {
                        // XSLT 2.0+: If template has 'as' attribute, validate return type
                        String asType = rule.getAsType();
                        if (asType != null && !asType.isEmpty()) {
                            // Execute to a sequence builder to capture the result
                            SequenceBuilderOutputHandler seqBuilder = 
                                new SequenceBuilderOutputHandler();
                            rule.getBody().execute(execContext, seqBuilder);
                            
                            // Get the result sequence
                            XPathValue result = seqBuilder.getSequence();
                            
                            // Validate against declared type
                            validateTemplateReturnType(result, asType, rule);
                            
                            // Output the validated result
                            outputValidatedResult(result, output);
                        } else {
                            rule.getBody().execute(execContext, output);
                        }
                    }
                }
                
                // Fire post-descent accumulator rules
                if (accMgr != null) {
                    accMgr.notifyEndElement(node);
                }
                position++;
            }
        } catch (XPathException e) {
            throw new SAXException("Error in xsl:apply-templates", e);
        }
    }
    
    /**
     * Validates template return value against declared 'as' type.
     */
    private void validateTemplateReturnType(XPathValue result, String asType, 
            TemplateRule rule) throws SAXException {
        try {
            // Parse the type
            SequenceType expectedType = SequenceType.parse(asType, null);
            if (expectedType == null) {
                return; // Unknown type - skip validation
            }
            
            // Check if empty result is allowed
            boolean isEmpty = result == null || 
                (result instanceof XPathSequence && ((XPathSequence) result).isEmpty()) ||
                (result.asString().isEmpty() && !(result instanceof XPathBoolean));
            
            // Empty sequence only allowed for optional types (?, *)
            SequenceType.Occurrence occ = expectedType.getOccurrence();
            if (isEmpty && occ != SequenceType.Occurrence.ZERO_OR_ONE && 
                occ != SequenceType.Occurrence.ZERO_OR_MORE) {
                String templateDesc = rule.getName() != null ? 
                    "named template '" + rule.getName() + "'" :
                    "template matching '" + rule.getMatchPattern() + "'";
                throw new SAXException("XTTE0505: Required item type of " + 
                    templateDesc + " is " + asType + 
                    "; supplied value is empty sequence");
            }
            
            // For non-empty results, validate the type matches
            if (!isEmpty) {
                if (!expectedType.matches(result, org.bluezoo.gonzalez.transform.xpath.type.SchemaContext.NONE)) {
                    String templateDesc = rule.getName() != null ? 
                        "named template '" + rule.getName() + "'" :
                        "template matching '" + rule.getMatchPattern() + "'";
                    throw new SAXException("XTTE0505: Required item type of " + 
                        templateDesc + " is " + asType + 
                        "; supplied value is " + result.getClass().getSimpleName());
                }
            }
        } catch (Exception e) {
            if (e instanceof SAXException) {
                throw (SAXException) e;
            }
            throw new SAXException(e.getMessage(), e);
        }
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
        if (item instanceof XPathResultTreeFragment) {
            ((XPathResultTreeFragment) item).replayToOutput(output);
        } else if (item instanceof XPathNode) {
            // Output node content
            output.characters(item.asString());
        } else {
            // Atomic value
            output.atomicValue(item);
        }
    }
    
    private void executeBuiltIn(String type, XPathNode node,
            TransformContext context,
            OutputHandler output) throws SAXException {
        switch (type) {
            case "element-or-root":
            case "shallow-skip":
                // Apply templates to children
                applyToChildren(node, context, output);
                break;
            case "text-or-attribute":
                output.characters(node.getStringValue());
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
                // Do nothing
                break;
        }
    }
    
    private void applyToChildren(XPathNode node, TransformContext context,
            OutputHandler output) throws SAXException {
        Iterator<XPathNode> children = node.getChildren();
        List<XPathNode> childList = new ArrayList<>();
        while (children.hasNext()) childList.add(children.next());
        
        AccumulatorManager accMgr = null;
        if (context instanceof BasicTransformContext) {
            accMgr = ((BasicTransformContext) context).getAccumulatorManager();
        }
        
        int size = childList.size();
        int pos = 1;
        for (XPathNode child : childList) {
            TransformContext childCtx;
            if (context instanceof BasicTransformContext) {
                childCtx = ((BasicTransformContext) context)
                    .withXsltCurrentNode(child).withPositionAndSize(pos++, size);
            } else {
                childCtx = context.withContextNode(child).withPositionAndSize(pos++, size);
            }
            
            if (accMgr != null) {
                accMgr.notifyStartElement(child);
            }
            
            TemplateMatcher m = context.getTemplateMatcher();
            TemplateRule r = m.findMatch(child, context.getCurrentMode(), childCtx);
            if (r != null) {
                if (TemplateMatcher.isBuiltIn(r)) {
                    executeBuiltIn(TemplateMatcher.getBuiltInType(r), child, childCtx, output);
                } else {
                    TransformContext execCtx = childCtx.pushVariableScope()
                        .withCurrentTemplateRule(r);
                    r.getBody().execute(execCtx, output);
                }
            }
            
            if (accMgr != null) {
                accMgr.notifyEndElement(child);
            }
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
                
                applyToChildren(node, context, output);
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
                // Copy the attribute - note: will only work if there's a pending element
                String aUri = node.getNamespaceURI();
                String aLocal = node.getLocalName();
                String aPrefix = node.getPrefix();
                String aQName = aPrefix != null && !aPrefix.isEmpty() ? aPrefix + ":" + aLocal : aLocal;
                output.attribute(aUri != null ? aUri : "", aLocal, aQName, node.getStringValue());
                break;
            case ROOT:
                applyToChildren(node, context, output);
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

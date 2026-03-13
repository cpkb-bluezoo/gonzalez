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

import org.bluezoo.gonzalez.transform.compiler.ExpressionHolder;
import org.bluezoo.gonzalez.transform.compiler.SortSpec;
import org.bluezoo.gonzalez.transform.compiler.SequenceBuilderOutputHandler;
import org.bluezoo.gonzalez.transform.runtime.AccumulatorManager;
import org.bluezoo.gonzalez.transform.runtime.OutputHandlerUtils;
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
import org.bluezoo.gonzalez.transform.xpath.type.XPathArray;
import org.bluezoo.gonzalez.transform.xpath.type.XPathMap;
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
public class ApplyTemplatesNode extends XSLTInstruction implements ExpressionHolder {
    private final XPathExpression selectExpr;
    private final String mode;
    private final List<SortSpec> sorts;
    private final List<WithParamNode> params;
    private final boolean backwardsCompatible;
    public ApplyTemplatesNode(XPathExpression selectExpr, String mode, List<SortSpec> sorts, List<WithParamNode> params) {
        this(selectExpr, mode, sorts, params, false);
    }
    public ApplyTemplatesNode(XPathExpression selectExpr, String mode,
                              List<SortSpec> sorts, List<WithParamNode> params,
                              boolean backwardsCompatible) {
        this.selectExpr = selectExpr;
        this.mode = mode;
        this.sorts = sorts;
        this.params = params;
        this.backwardsCompatible = backwardsCompatible;
    }
    public boolean isBackwardsCompatible() {
        return backwardsCompatible;
    }
    @Override public String getInstructionName() { return "apply-templates"; }

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
                        } else if (item instanceof XPathResultTreeFragment) {
                            nodes.addAll(((XPathResultTreeFragment) item).asNodeSet().getNodes());
                        } else {
                            // Atomic value (XSLT 3.0)
                            atomicValues.add(item);
                        }
                    }
                    
                    // Process atomic values (XSLT 3.0 only)
                    if (!atomicValues.isEmpty() &&
                            context.getStylesheet().getProcessorVersion() < 3.0) {
                        throw new SAXException("XTTE0520: The select expression of xsl:apply-templates" +
                            " must return nodes, but sequence contains atomic value(s)");
                    }
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
                } else if (result instanceof XPathArray || result instanceof XPathMap) {
                    // XSLT 3.0: arrays and maps are function items that templates can match
                    nodes = new ArrayList<>();
                    if (context instanceof BasicTransformContext) {
                        BasicTransformContext btc = (BasicTransformContext) context;
                        TemplateMatcher matcher = context.getTemplateMatcher();
                        String effectiveMode = mode;
                        if ("#current".equals(mode)) {
                            effectiveMode = context.getCurrentMode();
                        }
                        TemplateRule rule = matcher.findMatchForAtomicValue(result, effectiveMode, context);
                        if (rule != null) {
                            TransformContext itemContext = btc
                                .withContextItem(result)
                                .withPositionAndSize(1, 1)
                                .pushVariableScope()
                                .withCurrentTemplateRule(rule);
                            for (WithParamNode param : params) {
                                if (!param.isTunnel()) {
                                    try {
                                        itemContext = (TransformContext) itemContext.withVariable(
                                            null, param.getName(), param.evaluate(context));
                                    } catch (XPathException e) {
                                        throw new SAXException("Error evaluating param: " + e.getMessage(), e);
                                    }
                                }
                            }
                            rule.getBody().execute(itemContext, output);
                        }
                    }
                    return;
                } else {
                    // Single atomic value - find a matching template (XSLT 3.0)
                    if (context.getStylesheet().getProcessorVersion() >= 3.0 &&
                            context instanceof BasicTransformContext) {
                        BasicTransformContext btc = (BasicTransformContext) context;
                        TemplateMatcher matcher = context.getTemplateMatcher();
                        String effectiveMode = mode;
                        if ("#current".equals(mode)) {
                            effectiveMode = context.getCurrentMode();
                        }
                        TemplateRule rule = matcher.findMatchForAtomicValue(
                            result, effectiveMode, context);
                        if (rule != null) {
                            TransformContext itemContext = btc
                                .withContextItem(result)
                                .withPositionAndSize(1, 1)
                                .pushVariableScope()
                                .withCurrentTemplateRule(rule);
                            for (WithParamNode param : params) {
                                if (!param.isTunnel()) {
                                    try {
                                        itemContext = (TransformContext) itemContext.withVariable(
                                            null, param.getName(), param.evaluate(context));
                                    } catch (XPathException e) {
                                        throw new SAXException(
                                            "Error evaluating param: " + e.getMessage(), e);
                                    }
                                }
                            }
                            rule.getBody().execute(itemContext, output);
                        }
                        return;
                    }
                    // XSLT 2.0: select must evaluate to node()*
                    throw new SAXException("XTTE0520: The select expression of xsl:apply-templates" +
                        " must return nodes, but got an atomic value: " + result);
                }
            } else {
                // Default: select="child::node()"
                // XTTE0510: context item must be a node when select is absent
                XPathNode contextNode = context.getContextNode();
                if (contextNode == null) {
                    throw new SAXException("XTTE0510: An xsl:apply-templates instruction " +
                        "with no select attribute requires the context item to be a node");
                }
                if (context instanceof BasicTransformContext) {
                    XPathValue ci = ((BasicTransformContext) context).getContextItem();
                    if (ci != null && !(ci instanceof XPathNode) && !ci.isNodeSet()) {
                        throw new SAXException("XTTE0510: An xsl:apply-templates instruction " +
                            "with no select attribute requires the context item to be a node");
                    }
                }
                nodes = new ArrayList<>();
                Iterator<XPathNode> children = 
                    contextNode.getChildren();
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
                        .withXsltCurrentNodeAndContextItem(node).withPositionAndSize(position, size);
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
                            // XTTE0590: validate supplied value against declared type
                            try {
                                value = templateParam.validateValue(value, "XTTE0590");
                            } catch (XPathException e) {
                                throw new SAXException(e.getMessage(), e);
                            }
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
                            } else {
                                defaultValue = templateParam.evaluateDefaultContent(execContext);
                            }
                            try {
                                defaultValue = templateParam.coerceDefaultValue(defaultValue);
                            } catch (XPathException e) {
                                throw new SAXException("Error coercing parameter default: " + e.getMessage(), e);
                            }
                            // XTTE0600: validate default value against declared type
                            try {
                                defaultValue = templateParam.validateValue(defaultValue, "XTTE0600");
                            } catch (XPathException e) {
                                throw new SAXException(e.getMessage(), e);
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
            // Use pre-parsed type with namespace resolution, fall back to runtime parse
            SequenceType expectedType = rule.getParsedAsType();
            if (expectedType == null) {
                expectedType = SequenceType.parse(asType, null);
            }
            if (expectedType == null) {
                return; // Unknown type - skip validation
            }
            
            // Check if empty result is allowed
            boolean isEmpty = result == null || 
                (result instanceof XPathSequence && ((XPathSequence) result).isEmpty()) ||
                (result.asString().isEmpty() && !(result instanceof XPathBoolean));
            
            // Empty sequence only allowed for optional types (?, *) or empty-sequence()
            if (isEmpty && !expectedType.allowsEmpty()) {
                String templateDesc = rule.getName() != null ? 
                    "named template '" + rule.getName() + "'" :
                    "template matching '" + rule.getMatchPattern() + "'";
                throw new SAXException("XTTE0505: Required item type of " + 
                    templateDesc + " is " + asType + 
                    "; supplied value is empty sequence");
            }
            
            // For non-empty results, validate the type matches.
            // Per XSLT spec, function conversion rules apply:
            //   1. Atomize nodes to xs:untypedAtomic
            //   2. Cast xs:untypedAtomic to the target type if needed
            if (!isEmpty) {
                XPathValue checkVal = result;
                if (expectedType.getItemKind() == SequenceType.ItemKind.ATOMIC && 
                    (result instanceof XPathNode || result instanceof XPathNodeSet)) {
                    // Text nodes atomize to xs:untypedAtomic per XDM
                    checkVal = new org.bluezoo.gonzalez.transform.xpath.type.XPathUntypedAtomic(result.asString());
                }
                if (!expectedType.matches(checkVal, org.bluezoo.gonzalez.transform.xpath.type.SchemaContext.NONE)) {
                    // Function conversion: try casting xs:untypedAtomic to target type
                    if (checkVal instanceof org.bluezoo.gonzalez.transform.xpath.type.XPathUntypedAtomic) {
                        XPathValue converted = coerceAtomicForAs(checkVal.asString(), expectedType);
                        if (converted != null && expectedType.matches(converted, org.bluezoo.gonzalez.transform.xpath.type.SchemaContext.NONE)) {
                            checkVal = converted;
                        } else {
                            String templateDesc = rule.getName() != null ? 
                                "named template '" + rule.getName() + "'" :
                                "template matching '" + rule.getMatchPattern() + "'";
                            throw new SAXException("XTTE0505: Required item type of " + 
                                templateDesc + " is " + asType + 
                                "; supplied value is " + result.getClass().getSimpleName());
                        }
                    } else {
                        String templateDesc = rule.getName() != null ? 
                            "named template '" + rule.getName() + "'" :
                            "template matching '" + rule.getMatchPattern() + "'";
                        throw new SAXException("XTTE0505: Required item type of " + 
                            templateDesc + " is " + asType + 
                            "; supplied value is " + result.getClass().getSimpleName());
                    }
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
     * Applies function conversion rules: casts a string value to the target
     * atomic type specified by the SequenceType.  Returns null if the cast
     * is not possible (e.g. "hello" to xs:double).
     */
    private XPathValue coerceAtomicForAs(String text, SequenceType expectedType) {
        String targetLocal = expectedType.getLocalName();
        if (targetLocal == null) {
            return null;
        }
        switch (targetLocal) {
            case "string":
            case "normalizedString":
            case "token":
            case "language":
            case "NMTOKEN":
            case "Name":
            case "NCName":
            case "ID":
            case "IDREF":
            case "ENTITY":
                return new org.bluezoo.gonzalez.transform.xpath.type.XPathString(text);
            case "double":
            case "float":
            case "decimal":
                try {
                    return new org.bluezoo.gonzalez.transform.xpath.type.XPathNumber(Double.parseDouble(text));
                } catch (NumberFormatException e) {
                    return null;
                }
            case "integer":
            case "int":
            case "long":
            case "short":
            case "nonNegativeInteger":
            case "positiveInteger":
                try {
                    return org.bluezoo.gonzalez.transform.xpath.type.XPathNumber.ofInteger(Long.parseLong(text));
                } catch (NumberFormatException e) {
                    return null;
                }
            case "boolean":
                if ("true".equals(text) || "1".equals(text)) {
                    return org.bluezoo.gonzalez.transform.xpath.type.XPathBoolean.TRUE;
                } else if ("false".equals(text) || "0".equals(text)) {
                    return org.bluezoo.gonzalez.transform.xpath.type.XPathBoolean.FALSE;
                }
                return null;
            case "anyAtomicType":
                return new org.bluezoo.gonzalez.transform.xpath.type.XPathUntypedAtomic(text);
            default:
                return null;
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
            output.atomicValue(item);
        }
    }
    
    private void serializeNode(XPathNode node, OutputHandler output) throws SAXException {
        switch (node.getNodeType()) {
            case ELEMENT: {
                String uri = OutputHandlerUtils.effectiveUri(node.getNamespaceURI());
                String localName = node.getLocalName();
                String prefix = node.getPrefix();
                String qName = OutputHandlerUtils.buildQName(prefix, localName);
                output.startElement(uri, localName, qName);
                if (prefix != null && !prefix.isEmpty()) {
                    output.namespace(prefix, uri);
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
            case COMMENT: {
                output.comment(node.getStringValue());
                break;
            }
            case PROCESSING_INSTRUCTION: {
                String target = node.getLocalName();
                String data = node.getStringValue();
                output.processingInstruction(target, data != null ? data : "");
                break;
            }
            case ROOT: {
                Iterator<XPathNode> children = node.getChildren();
                while (children.hasNext()) {
                    serializeNode(children.next(), output);
                }
                break;
            }
            case ATTRIBUTE: {
                String nsUri = node.getNamespaceURI();
                String localName = node.getLocalName();
                String prefix = node.getPrefix();
                String qName = (prefix != null && !prefix.isEmpty())
                    ? prefix + ":" + localName : localName;
                output.attribute(nsUri != null ? nsUri : "", localName, qName,
                    node.getStringValue());
                break;
            }
            default:
                output.characters(node.getStringValue());
                break;
        }
    }
    
    private void executeBuiltIn(String type, XPathNode node,
            TransformContext context,
            OutputHandler output) throws SAXException {
        switch (type) {
            case "element-or-root":
                applyToChildren(node, context, output);
                break;
            case "shallow-skip":
                if (node.isElement()) {
                    applyToAttributes(node, context, output);
                }
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
            case "typed-fail":
                throw new SAXException("XTTE3100: Node " + node.getLocalName() +
                    " is untyped, but mode has typed='yes'");
            case "empty":
                // Do nothing
                break;
        }
    }
    
    private void applyToAttributes(XPathNode node, TransformContext context,
            OutputHandler output) throws SAXException {
        Iterator<XPathNode> attrs = node.getAttributes();
        List<XPathNode> attrList = new ArrayList<>();
        while (attrs.hasNext()) {
            attrList.add(attrs.next());
        }
        
        int size = attrList.size();
        int pos = 1;
        for (XPathNode attr : attrList) {
            TransformContext attrCtx;
            if (context instanceof BasicTransformContext) {
                attrCtx = ((BasicTransformContext) context)
                    .withXsltCurrentNode(attr).withPositionAndSize(pos++, size);
            } else {
                attrCtx = context.withContextNode(attr).withPositionAndSize(pos++, size);
            }
            
            TemplateMatcher m = context.getTemplateMatcher();
            TemplateRule r = m.findMatch(attr, context.getCurrentMode(), attrCtx);
            if (r != null) {
                if (TemplateMatcher.isBuiltIn(r)) {
                    executeBuiltIn(TemplateMatcher.getBuiltInType(r), attr, attrCtx, output);
                } else {
                    TransformContext execCtx = attrCtx.pushVariableScope()
                        .withCurrentTemplateRule(r);
                    bindTemplateParams(r, execCtx, context, output);
                    r.getBody().execute(execCtx, output);
                }
            }
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
                    bindTemplateParams(r, execCtx, context, output);
                    r.getBody().execute(execCtx, output);
                }
            }
            
            if (accMgr != null) {
                accMgr.notifyEndElement(child);
            }
        }
    }
    
    /**
     * Binds tunnel and non-tunnel parameters from the original xsl:apply-templates
     * to a matched template's declared parameters. Called when a user template is
     * reached through the built-in template chain.
     */
    private void bindTemplateParams(TemplateRule rule, TransformContext execCtx,
            TransformContext callerCtx, OutputHandler output) throws SAXException {
        for (TemplateParameter templateParam : rule.getParameters()) {
            XPathValue value = null;
            boolean found = false;
            
            if (templateParam.isTunnel()) {
                // Tunnel param: check with-param tunnel params, then context tunnel params
                for (WithParamNode param : params) {
                    if (param.isTunnel() && param.getName().equals(templateParam.getName())) {
                        try {
                            value = param.evaluate(callerCtx);
                            found = true;
                        } catch (XPathException e) {
                            throw new SAXException("Error evaluating tunnel param: " + e.getMessage(), e);
                        }
                        break;
                    }
                }
                if (!found) {
                    value = execCtx.getTunnelParameters().get(templateParam.getName());
                    found = (value != null);
                }
            } else {
                // Non-tunnel param: match with non-tunnel with-param
                for (WithParamNode param : params) {
                    if (!param.isTunnel() && param.getName().equals(templateParam.getName())) {
                        try {
                            value = param.evaluate(callerCtx);
                            found = true;
                        } catch (XPathException e) {
                            throw new SAXException("Error evaluating param: " + e.getMessage(), e);
                        }
                        break;
                    }
                }
            }
            
            if (found && value != null) {
                try {
                    value = templateParam.validateValue(value, "XTTE0590");
                } catch (XPathException e) {
                    throw new SAXException(e.getMessage(), e);
                }
                execCtx.getVariableScope().bind(
                    templateParam.getNamespaceURI(), templateParam.getLocalName(), value);
            } else if (templateParam.isRequired()) {
                throw new SAXException("XTDE0700: Template parameter $" +
                    templateParam.getLocalName() + " is required but no value was supplied");
            } else {
                XPathValue defaultValue = null;
                if (templateParam.getSelectExpr() != null) {
                    try {
                        defaultValue = templateParam.getSelectExpr().evaluate(execCtx);
                    } catch (XPathException e) {
                        throw new SAXException("Error evaluating param default: " + e.getMessage(), e);
                    }
                } else {
                    defaultValue = templateParam.evaluateDefaultContent(execCtx);
                }
                try {
                    defaultValue = templateParam.validateValue(defaultValue, "XTTE0600");
                } catch (XPathException e) {
                    throw new SAXException(e.getMessage(), e);
                }
                execCtx.getVariableScope().bind(
                    templateParam.getNamespaceURI(), templateParam.getLocalName(), defaultValue);
            }
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
                applyToAttributes(node, context, output);
                applyToChildren(node, context, output);
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
        ValueOutputHelper.deepCopyNode(node, output);
    }
}

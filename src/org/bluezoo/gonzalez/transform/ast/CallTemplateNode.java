/*
 * CallTemplateNode.java
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

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.xml.sax.SAXException;

import org.bluezoo.gonzalez.transform.compiler.SequenceBuilderOutputHandler;
import org.bluezoo.gonzalez.transform.compiler.TemplateParameter;
import org.bluezoo.gonzalez.transform.compiler.TemplateRule;
import org.bluezoo.gonzalez.transform.runtime.BufferOutputHandler;
import org.bluezoo.gonzalez.transform.runtime.OutputHandler;
import org.bluezoo.gonzalez.transform.runtime.SAXEventBuffer;
import org.bluezoo.gonzalez.transform.runtime.TransformContext;
import org.bluezoo.gonzalez.transform.xpath.expr.XPathException;
import org.bluezoo.gonzalez.transform.xpath.type.SchemaContext;
import org.bluezoo.gonzalez.transform.xpath.type.SequenceType;
import org.bluezoo.gonzalez.transform.xpath.type.XPathBoolean;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNode;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNodeSet;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNumber;
import org.bluezoo.gonzalez.transform.xpath.type.XPathResultTreeFragment;
import org.bluezoo.gonzalez.transform.xpath.type.XPathSequence;
import org.bluezoo.gonzalez.transform.xpath.type.XPathString;
import org.bluezoo.gonzalez.transform.xpath.type.XPathUntypedAtomic;
import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;

/**
 * CallTemplateNode XSLT instruction.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public class CallTemplateNode extends XSLTInstruction {
    private final String name;
    private final List<WithParamNode> params;
    public CallTemplateNode(String name, List<WithParamNode> params) {
        this.name = name;
        this.params = params;
    }
    @Override public String getInstructionName() { return "call-template"; }
    @Override public void execute(TransformContext context, 
                                  OutputHandler output) throws SAXException {
        // Find template by name - getNamedTemplate respects import precedence
        TemplateRule template = context.getStylesheet().getNamedTemplate(name);
        
        if (template == null) {
            throw new SAXException("Template not found: " + name);
        }
        
        // Push variable scope
        TransformContext callContext = 
            context.pushVariableScope();
        
        // XSLT 2.0 tunnel parameter support for call-template
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
            callContext = callContext.withTunnelParameters(newTunnelParams);
        }
        
        // Collect passed parameter names
        Set<String> passedParams = new HashSet<>();
        
        // Process each template parameter
        for (TemplateParameter templateParam : template.getParameters()) {
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
                    value = callContext.getTunnelParameters().get(templateParam.getName());
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
                // XSLT 2.0: coerce xs:untypedAtomic to the declared parameter type
                String paramAsType = templateParam.getAsType();
                if (paramAsType != null && value instanceof XPathUntypedAtomic) {
                    value = coerceUntypedAtomic((XPathUntypedAtomic) value, paramAsType);
                }
                // XSLT 2.0: also coerce RTF (from xsl:with-param content) and strings
                // when the parameter expects an atomic numeric type.
                // RTF from text content is treated as untypedAtomic per spec.
                if (paramAsType != null && !(value instanceof XPathUntypedAtomic)) {
                    SequenceType expectedType = SequenceType.parse(paramAsType, null);
                    if (expectedType != null
                        && expectedType.getItemKind() == SequenceType.ItemKind.ATOMIC
                        && !expectedType.matches(value)) {
                        String stringValue = value.asString();
                        XPathValue coerced = coerceUntypedAtomic(
                            new XPathUntypedAtomic(stringValue), paramAsType);
                        if (expectedType.matches(coerced)) {
                            value = coerced;
                        }
                    }
                }
                // XTTE0570: validate value against declared parameter type
                if (paramAsType != null) {
                    SequenceType expectedType = SequenceType.parse(paramAsType, null);
                    if (expectedType != null && !expectedType.matches(value)) {
                        throw new SAXException("XTTE0570: Parameter $" + 
                            templateParam.getLocalName() + ": required type is " + 
                            paramAsType + ", supplied value does not match");
                    }
                }
                callContext.getVariableScope().bind(templateParam.getNamespaceURI(), templateParam.getLocalName(), value);
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
                        defaultValue = templateParam.getSelectExpr().evaluate(callContext);
                    } catch (XPathException e) {
                        throw new SAXException("Error evaluating param default: " + e.getMessage(), e);
                    }
                } else if (templateParam.getDefaultContent() != null) {
                    // Execute content to get RTF as default value
                    SAXEventBuffer buffer = new SAXEventBuffer();
                    templateParam.getDefaultContent().execute(callContext, new BufferOutputHandler(buffer));
                    defaultValue = new XPathResultTreeFragment(buffer);
                } else {
                    defaultValue = new XPathString("");
                }
                if (defaultValue != null) {
                    callContext.getVariableScope().bind(templateParam.getNamespaceURI(), templateParam.getLocalName(), defaultValue);
                }
            }
        }
        
        // XTSE0680: Check for non-tunnel with-param that doesn't match any template parameter
        // Only in XSLT 2.0+ (XSLT 1.0 allows extra params to be silently ignored)
        if (context.getStylesheet().getVersion() >= 2.0) {
            for (WithParamNode param : params) {
                if (!param.isTunnel() && !passedParams.contains(param.getName())) {
                    throw new SAXException("XTSE0680: Non-tunnel parameter $" +
                        param.getName() + " does not match any parameter in template '" +
                        name + "'");
                }
            }
        }
        
        // Execute template body, validating return type if 'as' is declared
        String asType = template.getAsType();
        if (asType != null && !asType.isEmpty()) {
            SequenceType expectedType = template.getParsedAsType();
            if (expectedType == null) {
                expectedType = SequenceType.parse(asType, null);
            }
            if (expectedType != null
                    && expectedType.getItemKind() == SequenceType.ItemKind.ATOMIC
                    && expectedType.getOccurrence() == SequenceType.Occurrence.ONE
                    && isValidatableAtomicType(expectedType.getLocalName())) {
                // Capture output for atomic type validation
                SequenceBuilderOutputHandler seqBuilder =
                    new SequenceBuilderOutputHandler();
                template.getBody().execute(callContext, seqBuilder);
                XPathValue result = seqBuilder.getSequence();
                validateAtomicReturnType(result, expectedType, asType);
                // Replay validated result to actual output
                replayResult(result, output);
            } else {
                template.getBody().execute(callContext, output);
            }
        } else {
            template.getBody().execute(callContext, output);
        }
    }

    /**
     * Validates an atomic return type for call-template.
     * Only validates types that we can fully check (string, numeric, boolean).
     * Throws XTTE0505 if the value cannot be converted.
     */
    private void validateAtomicReturnType(XPathValue result,
            SequenceType expectedType, String asType) throws SAXException {
        String targetLocal = expectedType.getLocalName();
        if (!isValidatableAtomicType(targetLocal)) {
            return;
        }
        boolean isEmpty = result == null
            || (result instanceof XPathSequence && ((XPathSequence) result).isEmpty())
            || (result.asString().isEmpty() && !(result instanceof XPathBoolean));
        if (isEmpty && !expectedType.allowsEmpty()) {
            throw new SAXException("XTTE0505: Required item type of named template '"
                + name + "' is " + asType + "; supplied value is empty sequence");
        }
        if (!isEmpty) {
            XPathValue checkVal = result;
            if (result instanceof XPathNode || result instanceof XPathNodeSet) {
                checkVal = new XPathUntypedAtomic(result.asString());
            }
            if (!expectedType.matches(checkVal, SchemaContext.NONE)) {
                XPathValue converted = coerceAtomicForReturn(checkVal.asString(), expectedType);
                if (converted == null || !expectedType.matches(converted, SchemaContext.NONE)) {
                    throw new SAXException("XTTE0505: Required item type of named template '"
                        + name + "' is " + asType
                        + "; supplied value cannot be converted");
                }
            }
        }
    }

    private boolean isValidatableAtomicType(String localName) {
        if (localName == null) {
            return false;
        }
        switch (localName) {
            case "string":
            case "double":
            case "float":
            case "decimal":
            case "integer":
            case "int":
            case "long":
            case "boolean":
            case "untypedAtomic":
            case "anyAtomicType":
                return true;
            default:
                return false;
        }
    }

    /**
     * Casts a string to the target atomic type.
     * Returns null if the cast fails for a known type, or
     * returns the original XPathUntypedAtomic for unknown types
     * (to avoid false XTTE0505 for date/time and other types).
     */
    private XPathValue coerceAtomicForReturn(String text, SequenceType expectedType) {
        String targetLocal = expectedType.getLocalName();
        if (targetLocal == null) {
            return new XPathUntypedAtomic(text);
        }
        switch (targetLocal) {
            case "string":
                return new XPathString(text);
            case "double":
            case "float":
            case "decimal":
                if ("INF".equals(text)) {
                    return new XPathNumber(Double.POSITIVE_INFINITY);
                } else if ("-INF".equals(text)) {
                    return new XPathNumber(Double.NEGATIVE_INFINITY);
                } else if ("NaN".equals(text)) {
                    return new XPathNumber(Double.NaN);
                }
                try {
                    return new XPathNumber(Double.parseDouble(text));
                } catch (NumberFormatException e) {
                    return null;
                }
            case "integer":
            case "int":
            case "long":
                try {
                    return XPathNumber.ofInteger(Long.parseLong(text));
                } catch (NumberFormatException e) {
                    return null;
                }
            case "boolean":
                if ("true".equals(text) || "1".equals(text)) {
                    return XPathBoolean.TRUE;
                } else if ("false".equals(text) || "0".equals(text)) {
                    return XPathBoolean.FALSE;
                }
                return null;
            case "untypedAtomic":
            case "anyAtomicType":
                return new XPathUntypedAtomic(text);
            default:
                return null;
        }
    }

    /**
     * Replays a captured sequence result to the output handler.
     */
    private void replayResult(XPathValue result, OutputHandler output)
            throws SAXException {
        if (result == null) {
            return;
        }
        if (result instanceof XPathSequence) {
            for (XPathValue item : (XPathSequence) result) {
                replaySingleItem(item, output);
            }
        } else {
            replaySingleItem(result, output);
        }
    }

    private void replaySingleItem(XPathValue item, OutputHandler output)
            throws SAXException {
        if (item instanceof XPathResultTreeFragment) {
            ((XPathResultTreeFragment) item).replayToOutput(output);
        } else if (item instanceof XPathNode) {
            output.characters(item.asString());
        } else {
            output.atomicValue(item);
        }
    }

    /**
     * Coerces an xs:untypedAtomic value to the declared parameter type.
     * Per XSLT 2.0 spec, untypedAtomic values are automatically coerced
     * to the declared type of the parameter they are bound to.
     */
    private XPathValue coerceUntypedAtomic(XPathUntypedAtomic value, String asType) {
        if (asType == null) {
            return value;
        }
        String typeName = asType;
        int colonPos = asType.indexOf(':');
        if (colonPos >= 0) {
            typeName = asType.substring(colonPos + 1);
        }
        String stringValue = value.asString();
        switch (typeName) {
            case "string":
                return new XPathString(stringValue);
            case "double":
            case "float":
            case "decimal":
                try {
                    return new XPathNumber(Double.parseDouble(stringValue));
                } catch (NumberFormatException e) {
                    return value;
                }
            case "integer":
            case "int":
            case "long":
                try {
                    try {
                        return XPathNumber.ofInteger(Long.parseLong(stringValue));
                    } catch (NumberFormatException e2) {
                        return XPathNumber.ofInteger(new java.math.BigInteger(stringValue));
                    }
                } catch (NumberFormatException e) {
                    return value;
                }
            default:
                return value;
        }
    }
}

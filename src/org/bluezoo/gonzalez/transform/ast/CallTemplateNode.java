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

import org.bluezoo.gonzalez.transform.compiler.TemplateParameter;
import org.bluezoo.gonzalez.transform.compiler.TemplateRule;
import org.bluezoo.gonzalez.transform.runtime.BufferOutputHandler;
import org.bluezoo.gonzalez.transform.runtime.OutputHandler;
import org.bluezoo.gonzalez.transform.runtime.SAXEventBuffer;
import org.bluezoo.gonzalez.transform.runtime.TransformContext;
import org.bluezoo.gonzalez.transform.xpath.expr.XPathException;
import org.bluezoo.gonzalez.transform.xpath.type.XPathResultTreeFragment;
import org.bluezoo.gonzalez.transform.xpath.type.XPathString;
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
        
        // Execute template body
        template.getBody().execute(callContext, output);
    }
}

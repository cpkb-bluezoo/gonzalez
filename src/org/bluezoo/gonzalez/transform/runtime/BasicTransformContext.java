/*
 * BasicTransformContext.java
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

package org.bluezoo.gonzalez.transform.runtime;

import org.bluezoo.gonzalez.transform.compiler.CompiledStylesheet;
import org.bluezoo.gonzalez.transform.xpath.XPathExpression;
import org.bluezoo.gonzalez.transform.xpath.XPathFunctionLibrary;
import org.bluezoo.gonzalez.transform.xpath.expr.XPathException;
import org.bluezoo.gonzalez.transform.xpath.function.XSLTFunctionLibrary;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNode;
import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;

import java.util.Map;

/**
 * Basic implementation of TransformContext.
 *
 * <p>This context provides the runtime state for XSLT transformation,
 * including the current node, position, variables, and stylesheet access.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public class BasicTransformContext implements TransformContext {

    private final CompiledStylesheet stylesheet;
    private final XPathNode contextNode;
    private final int position;
    private final int size;
    private final String currentMode;
    private final VariableScope variableScope;
    private final XPathFunctionLibrary functionLibrary;
    private final TemplateMatcher templateMatcher;
    private final OutputHandler outputHandler;

    /**
     * Creates a new transform context.
     */
    public BasicTransformContext(CompiledStylesheet stylesheet, XPathNode contextNode,
            TemplateMatcher matcher, OutputHandler outputHandler) {
        this(stylesheet, contextNode, 1, 1, null, new VariableScope(), 
             XSLTFunctionLibrary.INSTANCE, matcher, outputHandler);
    }

    private BasicTransformContext(CompiledStylesheet stylesheet, XPathNode contextNode,
            int position, int size, String currentMode, VariableScope variableScope,
            XPathFunctionLibrary functionLibrary, TemplateMatcher matcher,
            OutputHandler outputHandler) {
        this.stylesheet = stylesheet;
        this.contextNode = contextNode;
        this.position = position;
        this.size = size;
        this.currentMode = currentMode;
        this.variableScope = variableScope;
        this.functionLibrary = functionLibrary;
        this.templateMatcher = matcher;
        this.outputHandler = outputHandler;
    }

    @Override
    public CompiledStylesheet getStylesheet() {
        return stylesheet;
    }

    @Override
    public String getCurrentMode() {
        return currentMode;
    }

    @Override
    public VariableScope getVariableScope() {
        return variableScope;
    }

    @Override
    public TransformContext pushVariableScope() {
        return new BasicTransformContext(stylesheet, contextNode, position, size,
            currentMode, variableScope.push(), functionLibrary, templateMatcher, outputHandler);
    }

    @Override
    public TransformContext withMode(String mode) {
        return new BasicTransformContext(stylesheet, contextNode, position, size,
            mode, variableScope, functionLibrary, templateMatcher, outputHandler);
    }

    @Override
    public TransformContext withContextNode(XPathNode node) {
        return new BasicTransformContext(stylesheet, node, position, size,
            currentMode, variableScope, functionLibrary, templateMatcher, outputHandler);
    }

    @Override
    public TransformContext withPositionAndSize(int position, int size) {
        return new BasicTransformContext(stylesheet, contextNode, position, size,
            currentMode, variableScope, functionLibrary, templateMatcher, outputHandler);
    }

    @Override
    public XPathValue evaluateXPath(XPathExpression expression) throws XPathException {
        return expression.evaluate(this);
    }

    // XPathContext methods

    @Override
    public XPathNode getContextNode() {
        return contextNode;
    }

    @Override
    public int getContextPosition() {
        return position;
    }

    @Override
    public int getContextSize() {
        return size;
    }

    @Override
    public XPathValue getVariable(String namespaceURI, String localName) {
        return variableScope.lookup(namespaceURI, localName);
    }

    @Override
    public XPathFunctionLibrary getFunctionLibrary() {
        return functionLibrary;
    }

    @Override
    public String resolveNamespacePrefix(String prefix) {
        if (contextNode instanceof StreamingNode) {
            return ((StreamingNode) contextNode).lookupNamespaceURI(prefix);
        }
        return null;
    }

    /**
     * Returns the template matcher.
     */
    public TemplateMatcher getTemplateMatcher() {
        return templateMatcher;
    }

    /**
     * Returns the output handler.
     */
    public OutputHandler getOutputHandler() {
        return outputHandler;
    }

    /**
     * Sets a variable in the current scope.
     */
    public void setVariable(String name, XPathValue value) {
        variableScope.bind(name, value);
    }

    /**
     * Sets a parameter value (checks if already bound).
     */
    public void setParameter(String name, XPathValue value) {
        // Parameters take passed value if provided, otherwise default
        if (variableScope.lookup(name) == null) {
            variableScope.bind(name, value);
        }
    }

}

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

import org.bluezoo.gonzalez.schema.xsd.XSDSimpleType;
import org.bluezoo.gonzalez.transform.compiler.CompiledStylesheet;
import org.bluezoo.gonzalez.transform.compiler.TemplateRule;
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
    private final XPathNode xsltCurrentNode;  // The XSLT current() node
    private final XPathValue contextItem;     // For atomic context items (XPath 2.0+)
    private final int position;
    private final int size;
    private final String currentMode;
    private final VariableScope variableScope;
    private final XPathFunctionLibrary functionLibrary;
    private final TemplateMatcher templateMatcher;
    private final OutputHandler outputHandler;
    private AccumulatorManager accumulatorManager;
    private final javax.xml.transform.ErrorListener errorListener;
    private final TemplateRule currentTemplateRule;  // For xsl:next-match
    private final String staticBaseURI;  // Override for static-base-uri() (from xml:base)

    /**
     * Creates a new transform context.
     */
    public BasicTransformContext(CompiledStylesheet stylesheet, XPathNode contextNode,
            TemplateMatcher matcher, OutputHandler outputHandler) {
        this(stylesheet, contextNode, contextNode, null, 1, 1, null, new VariableScope(), 
             XSLTFunctionLibrary.INSTANCE, matcher, outputHandler, null, null, null, null);
    }

    /**
     * Creates a new transform context with an error listener.
     */
    public BasicTransformContext(CompiledStylesheet stylesheet, XPathNode contextNode,
            TemplateMatcher matcher, OutputHandler outputHandler,
            javax.xml.transform.ErrorListener errorListener) {
        this(stylesheet, contextNode, contextNode, null, 1, 1, null, new VariableScope(), 
             XSLTFunctionLibrary.INSTANCE, matcher, outputHandler, null, errorListener, null, null);
    }

    private BasicTransformContext(CompiledStylesheet stylesheet, XPathNode contextNode,
            XPathNode xsltCurrentNode, XPathValue contextItem, int position, int size, String currentMode, 
            VariableScope variableScope, XPathFunctionLibrary functionLibrary, 
            TemplateMatcher matcher, OutputHandler outputHandler, 
            AccumulatorManager accumulatorManager, javax.xml.transform.ErrorListener errorListener,
            TemplateRule currentTemplateRule, String staticBaseURI) {
        this.stylesheet = stylesheet;
        this.contextNode = contextNode;
        this.xsltCurrentNode = xsltCurrentNode;
        this.contextItem = contextItem;
        this.position = position;
        this.size = size;
        this.currentMode = currentMode;
        this.variableScope = variableScope;
        this.functionLibrary = functionLibrary;
        this.templateMatcher = matcher;
        this.outputHandler = outputHandler;
        this.accumulatorManager = accumulatorManager;
        this.errorListener = errorListener;
        this.currentTemplateRule = currentTemplateRule;
        this.staticBaseURI = staticBaseURI;
    }

    @Override
    public CompiledStylesheet getStylesheet() {
        return stylesheet;
    }

    @Override
    public String getStaticBaseURI() {
        // Use instruction-level override if set, otherwise use stylesheet base URI
        if (staticBaseURI != null) {
            return staticBaseURI;
        }
        return stylesheet != null ? stylesheet.getBaseURI() : null;
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
        return new BasicTransformContext(stylesheet, contextNode, xsltCurrentNode, contextItem, position, size,
            currentMode, variableScope.push(), functionLibrary, templateMatcher, 
            outputHandler, accumulatorManager, errorListener, currentTemplateRule, staticBaseURI);
    }

    @Override
    public TransformContext withMode(String mode) {
        return new BasicTransformContext(stylesheet, contextNode, xsltCurrentNode, contextItem, position, size,
            mode, variableScope, functionLibrary, templateMatcher, 
            outputHandler, accumulatorManager, errorListener, currentTemplateRule, staticBaseURI);
    }

    @Override
    public TransformContext withContextNode(XPathNode node) {
        // Note: When changing context node for XPath evaluation (e.g., in predicates),
        // the XSLT current node stays the same. Use withXsltCurrentNode to update it.
        return new BasicTransformContext(stylesheet, node, xsltCurrentNode, null, position, size,
            currentMode, variableScope, functionLibrary, templateMatcher, 
            outputHandler, accumulatorManager, errorListener, currentTemplateRule, staticBaseURI);
    }
    
    /**
     * Creates a new context with a new XSLT current node.
     * This should be used when entering a new template or for-each iteration.
     */
    public TransformContext withXsltCurrentNode(XPathNode node) {
        return new BasicTransformContext(stylesheet, node, node, null, position, size,
            currentMode, variableScope, functionLibrary, templateMatcher, 
            outputHandler, accumulatorManager, errorListener, currentTemplateRule, staticBaseURI);
    }
    
    /**
     * Creates a new context with separate context node and XSLT current node.
     * This is used when evaluating predicates in patterns where the context node
     * changes during path traversal but current() should still refer to the 
     * original target node being matched.
     * 
     * @param contextNode the node to use as XPath context (.)
     * @param xsltCurrent the node to return from current()
     * @return a new context with the specified nodes
     */
    public BasicTransformContext withContextAndCurrentNodes(XPathNode contextNode, XPathNode xsltCurrent) {
        return new BasicTransformContext(stylesheet, contextNode, xsltCurrent, null, position, size,
            currentMode, variableScope, functionLibrary, templateMatcher, 
            outputHandler, accumulatorManager, errorListener, currentTemplateRule, staticBaseURI);
    }
    
    /**
     * Creates a new context with a context item (for atomic values).
     * This is used in for-each loops over atomic sequences.
     */
    public BasicTransformContext withContextItem(XPathValue item) {
        // If item is a node, also set it as context node
        XPathNode node = (item instanceof XPathNode) ? (XPathNode) item : contextNode;
        return new BasicTransformContext(stylesheet, node, xsltCurrentNode, item, position, size,
            currentMode, variableScope, functionLibrary, templateMatcher, 
            outputHandler, accumulatorManager, errorListener, currentTemplateRule, staticBaseURI);
    }
    
    /**
     * Returns the context item (atomic value or node).
     * Returns null if there is no context item (only context node).
     */
    public XPathValue getContextItem() {
        return contextItem;
    }

    @Override
    public TransformContext withPositionAndSize(int position, int size) {
        return new BasicTransformContext(stylesheet, contextNode, xsltCurrentNode, contextItem, position, size,
            currentMode, variableScope, functionLibrary, templateMatcher, 
            outputHandler, accumulatorManager, errorListener, currentTemplateRule, staticBaseURI);
    }

    @Override
    public TransformContext withVariable(String namespaceURI, String localName, XPathValue value) {
        // Create new variable scope with the binding
        VariableScope newScope = variableScope.push();
        newScope.bind(localName, value);
        return new BasicTransformContext(stylesheet, contextNode, xsltCurrentNode, contextItem, position, size,
            currentMode, newScope, functionLibrary, templateMatcher, 
            outputHandler, accumulatorManager, errorListener, currentTemplateRule, staticBaseURI);
    }

    @Override
    public TemplateRule getCurrentTemplateRule() {
        return currentTemplateRule;
    }

    @Override
    public TransformContext withCurrentTemplateRule(TemplateRule rule) {
        return new BasicTransformContext(stylesheet, contextNode, xsltCurrentNode, contextItem, position, size,
            currentMode, variableScope, functionLibrary, templateMatcher, 
            outputHandler, accumulatorManager, errorListener, rule, staticBaseURI);
    }

    @Override
    public TransformContext withStaticBaseURI(String baseURI) {
        return new BasicTransformContext(stylesheet, contextNode, xsltCurrentNode, contextItem, position, size,
            currentMode, variableScope, functionLibrary, templateMatcher, 
            outputHandler, accumulatorManager, errorListener, currentTemplateRule, baseURI);
    }

    @Override
    public TemplateMatcher getTemplateMatcher() {
        return templateMatcher;
    }

    @Override
    public javax.xml.transform.ErrorListener getErrorListener() {
        return errorListener;
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
    public XPathNode getXsltCurrentNode() {
        return xsltCurrentNode != null ? xsltCurrentNode : contextNode;
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
        // First, check the stylesheet's namespace bindings
        // This is needed for namespace-prefixed variable names ($ns:var)
        if (stylesheet != null) {
            String uri = stylesheet.resolveNamespacePrefix(prefix);
            if (uri != null) {
                return uri;
            }
        }
        // Fall back to the source document's namespace bindings
        if (contextNode instanceof StreamingNode) {
            return ((StreamingNode) contextNode).lookupNamespaceURI(prefix);
        }
        return null;
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
     * Sets a variable with namespace in the current scope.
     */
    public void setVariable(String namespaceURI, String localName, XPathValue value) {
        variableScope.bind(namespaceURI, localName, value);
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

    /**
     * Returns the accumulator manager.
     *
     * @return the accumulator manager, or null if not set
     */
    public AccumulatorManager getAccumulatorManager() {
        return accumulatorManager;
    }

    /**
     * Sets the accumulator manager.
     *
     * @param accumulatorManager the accumulator manager
     */
    public void setAccumulatorManager(AccumulatorManager accumulatorManager) {
        this.accumulatorManager = accumulatorManager;
    }

    @Override
    public XPathValue getAccumulatorBefore(String name) {
        if (accumulatorManager != null) {
            return accumulatorManager.getAccumulatorBefore(name);
        }
        return null;
    }

    @Override
    public XPathValue getAccumulatorAfter(String name) {
        if (accumulatorManager != null) {
            return accumulatorManager.getAccumulatorAfter(name);
        }
        return null;
    }

    @Override
    public XSDSimpleType getSchemaType(String namespaceURI, String localName) {
        if (stylesheet != null) {
            return stylesheet.getImportedSimpleType(namespaceURI, localName);
        }
        return null;
    }

    @Override
    public double getXsltVersion() {
        if (stylesheet != null) {
            return stylesheet.getVersion();
        }
        return 1.0;
    }

}

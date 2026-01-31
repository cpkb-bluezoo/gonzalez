/*
 * StreamingContext.java
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
import org.bluezoo.gonzalez.transform.xpath.XPathContext;
import org.bluezoo.gonzalez.transform.xpath.XPathFunctionLibrary;
import org.bluezoo.gonzalez.transform.xpath.function.XSLTFunctionLibrary;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNode;
import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;

/**
 * Context for streaming transformation execution.
 *
 * <p>StreamingContext is used within xsl:stream to provide a streaming-specific
 * execution environment. It inherits from the parent transformation context
 * but enforces streaming constraints.
 *
 * <p>Key characteristics:
 * <ul>
 *   <li>Only forward-only access to document nodes</li>
 *   <li>Accumulator state management</li>
 *   <li>No document buffering by default</li>
 *   <li>Context node changes as events are processed</li>
 * </ul>
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class StreamingContext {

    private final CompiledStylesheet stylesheet;
    private final VariableScope variableScope;
    private final TransformContext parentContext;
    private AccumulatorManager accumulatorManager;
    private XPathNode currentNode;
    private int depth;
    private BufferingStrategy bufferingStrategy;

    /**
     * Creates a new streaming context.
     *
     * @param stylesheet the compiled stylesheet
     * @param variableScope the variable scope
     * @param parentContext the parent transformation context
     */
    public StreamingContext(CompiledStylesheet stylesheet, VariableScope variableScope,
                            TransformContext parentContext) {
        this.stylesheet = stylesheet;
        this.variableScope = variableScope;
        this.parentContext = parentContext;
        this.depth = 0;
        this.bufferingStrategy = BufferingStrategy.NONE;
    }

    /**
     * Returns the compiled stylesheet.
     *
     * @return the stylesheet
     */
    public CompiledStylesheet getStylesheet() {
        return stylesheet;
    }

    /**
     * Returns the variable scope.
     *
     * @return the variable scope
     */
    public VariableScope getVariableScope() {
        return variableScope;
    }

    /**
     * Returns the parent transformation context.
     *
     * @return the parent context
     */
    public TransformContext getParentContext() {
        return parentContext;
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

    /**
     * Returns the current streaming node.
     *
     * @return the current node
     */
    public XPathNode getCurrentNode() {
        return currentNode;
    }

    /**
     * Sets the current streaming node.
     *
     * @param node the current node
     */
    public void setCurrentNode(XPathNode node) {
        this.currentNode = node;
    }

    /**
     * Returns the current element depth.
     *
     * @return the depth
     */
    public int getDepth() {
        return depth;
    }

    /**
     * Increments the element depth (called on startElement).
     * Used to track nesting level during streaming traversal.
     */
    public void pushDepth() {
        depth++;
    }

    /**
     * Decrements the element depth (called on endElement).
     * Used to track nesting level during streaming traversal.
     */
    public void popDepth() {
        depth--;
    }

    /**
     * Returns the buffering strategy.
     *
     * @return the buffering strategy
     */
    public BufferingStrategy getBufferingStrategy() {
        return bufferingStrategy;
    }

    /**
     * Sets the buffering strategy.
     *
     * @param strategy the buffering strategy
     */
    public void setBufferingStrategy(BufferingStrategy strategy) {
        this.bufferingStrategy = strategy;
    }

    /**
     * Creates an XPath context for expression evaluation in streaming mode.
     * The context provides access to the current streaming node and variable scope.
     *
     * @return an XPath context configured for streaming evaluation
     */
    public XPathContext createXPathContext() {
        return new StreamingXPathContext();
    }

    /**
     * Internal XPath context implementation for streaming.
     */
    private class StreamingXPathContext implements XPathContext {
        
        @Override
        public XPathNode getContextNode() {
            return currentNode;
        }

        @Override
        public int getContextPosition() {
            return 1;  // Position is managed via accumulators in streaming
        }

        @Override
        public int getContextSize() {
            return 1;  // Size is not known in pure streaming
        }

        @Override
        public XPathValue getVariable(String namespaceURI, String localName) {
            return variableScope.lookup(namespaceURI, localName);
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
            if (currentNode instanceof StreamingNode) {
                return ((StreamingNode) currentNode).lookupNamespaceURI(prefix);
            }
            return null;
        }

        @Override
        public XPathFunctionLibrary getFunctionLibrary() {
            return XSLTFunctionLibrary.INSTANCE;
        }

        @Override
        public XPathContext withContextNode(XPathNode node) {
            setCurrentNode(node);
            return this;
        }

        @Override
        public XPathContext withPositionAndSize(int position, int size) {
            // Position and size are managed differently in streaming
            return this;
        }

        @Override
        public XPathContext withVariable(String namespaceURI, String localName, XPathValue value) {
            // Bind variable in the parent context's variable scope
            if (variableScope != null) {
                variableScope.bind(localName, value);
            }
            return this;
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
    }

    @Override
    public String toString() {
        return "StreamingContext[depth=" + depth + 
               ", buffering=" + bufferingStrategy + "]";
    }

}

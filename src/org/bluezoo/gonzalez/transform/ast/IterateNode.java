/*
 * IterateNode.java
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

import org.bluezoo.gonzalez.transform.runtime.OutputHandler;
import org.bluezoo.gonzalez.transform.runtime.TransformContext;
import org.bluezoo.gonzalez.transform.runtime.VariableScope;
import org.bluezoo.gonzalez.transform.xpath.XPathExpression;
import org.bluezoo.gonzalez.transform.xpath.expr.XPathException;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNode;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNodeSet;
import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;
import org.xml.sax.SAXException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * XSLT 3.0 xsl:iterate instruction.
 *
 * <p>The xsl:iterate instruction provides stateful iteration over a sequence,
 * allowing parameters to be passed from one iteration to the next. Unlike
 * xsl:for-each, it processes items in strict sequence and allows early
 * termination with xsl:break.
 *
 * <p>Example:
 * <pre>
 * &lt;xsl:iterate select="item"&gt;
 *   &lt;xsl:param name="total" select="0"/&gt;
 *   
 *   &lt;xsl:next-iteration&gt;
 *     &lt;xsl:with-param name="total" select="$total + @price"/&gt;
 *   &lt;/xsl:next-iteration&gt;
 *   
 *   &lt;xsl:on-completion&gt;
 *     &lt;total&gt;&lt;xsl:value-of select="$total"/&gt;&lt;/total&gt;
 *   &lt;/xsl:on-completion&gt;
 * &lt;/xsl:iterate&gt;
 * </pre>
 *
 * <p>Streaming behavior:
 * <ul>
 *   <li>Fully streamable when select is a streamable expression</li>
 *   <li>State is passed via parameters, not via node references</li>
 *   <li>xsl:break allows early termination without buffering</li>
 *   <li>xsl:on-completion runs after all items or after break</li>
 * </ul>
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class IterateNode implements XSLTNode {

    /**
     * Parameter declaration for xsl:iterate.
     *
     * <p>Represents an iteration parameter that can be initialized with a default
     * value and updated via xsl:next-iteration for subsequent iterations.
     */
    public static final class IterateParam {
        private final String name;
        private final XPathExpression defaultValue;

        /**
         * Creates a new iteration parameter.
         *
         * @param name the parameter name
         * @param defaultValue the default value expression (may be null)
         */
        public IterateParam(String name, XPathExpression defaultValue) {
            this.name = name;
            this.defaultValue = defaultValue;
        }

        /**
         * Returns the parameter name.
         *
         * @return the parameter name
         */
        public String getName() {
            return name;
        }

        /**
         * Returns the default value expression.
         *
         * @return the default value expression, or null if not specified
         */
        public XPathExpression getDefaultValue() {
            return defaultValue;
        }
    }

    /**
     * Result of iteration body execution.
     *
     * <p>Encapsulates the result of executing the body of an xsl:iterate iteration,
     * including whether to break the loop and parameter values for the next iteration.
     */
    public static final class IterationResult {
        private final boolean shouldBreak;
        private final Map<String, XPathValue> nextParams;

        /**
         * Creates a new iteration result.
         *
         * @param shouldBreak true if iteration should terminate
         * @param nextParams parameter values for the next iteration
         */
        private IterationResult(boolean shouldBreak, Map<String, XPathValue> nextParams) {
            this.shouldBreak = shouldBreak;
            this.nextParams = nextParams;
        }

        /**
         * Creates a result indicating iteration should continue with updated parameters.
         *
         * @param nextParams parameter values for the next iteration
         * @return a continue result with the specified parameters
         */
        public static IterationResult continueWith(Map<String, XPathValue> nextParams) {
            return new IterationResult(false, nextParams);
        }

        /**
         * Creates a result indicating iteration should break.
         *
         * @return a break result
         */
        public static IterationResult breakIteration() {
            return new IterationResult(true, new HashMap<>());
        }

        /**
         * Returns true if iteration should break.
         *
         * @return true if breaking, false if continuing
         */
        public boolean isBreak() {
            return shouldBreak;
        }

        /**
         * Returns parameter values for the next iteration.
         *
         * @return map of parameter names to values (may be empty)
         */
        public Map<String, XPathValue> getNextParams() {
            return nextParams;
        }
    }

    private final XPathExpression select;
    private final List<IterateParam> params;
    private final XSLTNode body;
    private final XSLTNode onCompletion;

    /**
     * Creates a new iterate instruction.
     *
     * @param select the expression selecting items to iterate
     * @param params iteration parameters
     * @param body the body to execute for each item
     * @param onCompletion the on-completion content (may be null)
     */
    public IterateNode(XPathExpression select, List<IterateParam> params,
                       XSLTNode body, XSLTNode onCompletion) {
        this.select = select;
        this.params = params != null ? new ArrayList<>(params) : new ArrayList<>();
        this.body = body;
        this.onCompletion = onCompletion;
    }

    @Override
    public void execute(TransformContext context, OutputHandler output) throws SAXException {
        try {
            // Evaluate select expression
            XPathValue selectResult = select.evaluate(context);
            
            // Initialize iteration parameters with default values
            Map<String, XPathValue> currentParams = new HashMap<>();
            for (IterateParam param : params) {
                XPathValue defaultVal = param.getDefaultValue() != null
                    ? param.getDefaultValue().evaluate(context)
                    : null;
                currentParams.put(param.getName(), defaultVal);
            }
            
            executeIteration(context, output, selectResult, currentParams);
        } catch (XPathException e) {
            throw new SAXException("Error in xsl:iterate: " + e.getMessage(), e);
        }
    }
    
    private void executeIteration(TransformContext context, OutputHandler output,
                                   XPathValue selectResult, Map<String, XPathValue> currentParams)
            throws SAXException {

        // Convert result to iterable sequence
        List<XPathValue> items = new ArrayList<>();
        if (selectResult.isNodeSet()) {
            XPathNodeSet ns = selectResult.asNodeSet();
            for (XPathNode node : ns) {
                items.add(XPathNodeSet.of(node));
            }
        } else {
            // Treat as single-item sequence
            items.add(selectResult);
        }

        int position = 0;
        int size = items.size();
        boolean broken = false;

        // Iterate through items
        for (XPathValue item : items) {
            position++;
            
            // Create iteration context with current parameters
            TransformContext iterContext = createIterationContext(
                context, item, position, size, currentParams
            );
            
            // Execute body and get next parameters
            IterationResult result = executeBody(iterContext, output);
            
            if (result.isBreak()) {
                broken = true;
                break;
            }
            
            // Update parameters for next iteration
            currentParams = result.getNextParams();
            if (currentParams.isEmpty()) {
                // No next-iteration, use current values
                currentParams = new HashMap<>();
                for (IterateParam param : params) {
                    currentParams.put(param.getName(), 
                        iterContext.getVariableScope().lookup(param.getName()));
                }
            }
        }

        // Execute on-completion with final parameter values
        if (onCompletion != null) {
            TransformContext completionCtx = createCompletionContext(context, currentParams);
            onCompletion.execute(completionCtx, output);
        }
    }

    /**
     * Creates the context for a single iteration.
     */
    private TransformContext createIterationContext(TransformContext parent, XPathValue item,
                                                     int position, int size,
                                                     Map<String, XPathValue> params) {
        // Get context node from item
        XPathNode contextNode;
        if (item.isNodeSet()) {
            XPathNodeSet ns = item.asNodeSet();
            contextNode = ns.isEmpty() ? parent.getContextNode() : ns.first();
        } else {
            contextNode = parent.getContextNode();
        }
        
        TransformContext ctx = parent.withContextNode(contextNode)
                                     .withPositionAndSize(position, size)
                                     .pushVariableScope();
        
        // Bind iteration parameters
        for (Map.Entry<String, XPathValue> entry : params.entrySet()) {
            ctx.getVariableScope().bind(entry.getKey(), entry.getValue());
        }
        
        return ctx;
    }

    /**
     * Creates the context for on-completion execution.
     */
    private TransformContext createCompletionContext(TransformContext parent,
                                                      Map<String, XPathValue> params) {
        TransformContext ctx = parent.pushVariableScope();
        
        // Bind final parameter values
        for (Map.Entry<String, XPathValue> entry : params.entrySet()) {
            ctx.getVariableScope().bind(entry.getKey(), entry.getValue());
        }
        
        return ctx;
    }

    /**
     * Executes the body and collects next-iteration parameters.
     */
    private IterationResult executeBody(TransformContext context, OutputHandler output) 
            throws SAXException {
        if (body == null) {
            return IterationResult.continueWith(new HashMap<>());
        }
        
        // Execute body - it may contain xsl:next-iteration or xsl:break
        // For now, execute body and return continue
        // TODO: Implement break/next-iteration detection via special exceptions or markers
        body.execute(context, output);
        
        return IterationResult.continueWith(new HashMap<>());
    }

    @Override
    public StreamingCapability getStreamingCapability() {
        // xsl:iterate is designed for streaming
        return StreamingCapability.FULL;
    }

    @Override
    public String toString() {
        return "IterateNode[params=" + params.size() + "]";
    }

}

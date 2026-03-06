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

import org.bluezoo.gonzalez.transform.compiler.ExpressionHolder;
import org.bluezoo.gonzalez.transform.runtime.BasicTransformContext;
import org.bluezoo.gonzalez.transform.runtime.OutputHandler;
import org.bluezoo.gonzalez.transform.runtime.TransformContext;
import org.bluezoo.gonzalez.transform.runtime.VariableScope;
import org.bluezoo.gonzalez.transform.xpath.XPathExpression;
import org.bluezoo.gonzalez.transform.xpath.expr.XPathException;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNode;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNodeSet;
import org.bluezoo.gonzalez.transform.xpath.type.XPathResultTreeFragment;
import org.bluezoo.gonzalez.transform.xpath.type.XPathSequence;
import org.bluezoo.gonzalez.transform.xpath.type.XPathString;
import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;
import org.xml.sax.SAXException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
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
public final class IterateNode implements XSLTNode, ExpressionHolder {

    /**
     * Parameter declaration for xsl:iterate.
     *
     * <p>Represents an iteration parameter that can be initialized with a default
     * value and updated via xsl:next-iteration for subsequent iterations.
     */
    public static final class IterateParam {
        private final String name;
        private final XPathExpression defaultValue;
        private final String asType;

        /**
         * Creates a new iteration parameter.
         *
         * @param name the parameter name
         * @param defaultValue the default value expression (may be null)
         */
        public IterateParam(String name, XPathExpression defaultValue) {
            this(name, defaultValue, null);
        }

        /**
         * Creates a new iteration parameter with type annotation.
         *
         * @param name the parameter name
         * @param defaultValue the default value expression (may be null)
         * @param asType the declared type (may be null)
         */
        public IterateParam(String name, XPathExpression defaultValue, String asType) {
            this.name = name;
            this.defaultValue = defaultValue;
            this.asType = asType;
        }

        public String getName() {
            return name;
        }

        public XPathExpression getDefaultValue() {
            return defaultValue;
        }

        public String getAsType() {
            return asType;
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

    public XSLTNode getBody() { return body; }
    public XSLTNode getOnCompletion() { return onCompletion; }

    @Override
    public List<XPathExpression> getExpressions() {
        List<XPathExpression> exprs = new ArrayList<XPathExpression>();
        if (select != null) {
            exprs.add(select);
        }
        return exprs;
    }

    @Override
    public void execute(TransformContext context, OutputHandler output) throws SAXException {
        try {
            // Evaluate select expression
            XPathValue selectResult = select.evaluate(context);
            
            Map<String, XPathValue> currentParams = new HashMap<>();
            for (IterateParam param : params) {
                XPathValue defaultVal;
                if (param.getDefaultValue() != null) {
                    defaultVal = param.getDefaultValue().evaluate(context);
                } else {
                    defaultVal = XPathNodeSet.EMPTY;
                }
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

        List<XPathValue> items = flattenToItems(selectResult);

        int position = 0;
        int size = items.size();
        boolean broken = false;

        for (XPathValue item : items) {
            position++;
            
            TransformContext iterContext = createIterationContext(
                context, item, position, size, currentParams
            );
            
            IterationResult result = executeBody(iterContext, output);
            
            if (result.isBreak()) {
                broken = true;
                break;
            }
            
            Map<String, XPathValue> nextParams = result.getNextParams();
            if (nextParams.isEmpty()) {
                currentParams = lookupCurrentParams(iterContext);
            } else {
                currentParams = mergeParams(nextParams, iterContext);
            }
        }

        if (onCompletion != null && !broken) {
            TransformContext completionCtx = createCompletionContext(context, currentParams);
            onCompletion.execute(completionCtx, output);
        }
    }

    /**
     * Flattens the select result into a list of individual items for iteration.
     */
    private List<XPathValue> flattenToItems(XPathValue selectResult) {
        List<XPathValue> items = new ArrayList<>();
        if (selectResult instanceof XPathSequence) {
            XPathSequence seq = (XPathSequence) selectResult;
            for (XPathValue item : seq) {
                items.add(item);
            }
        } else if (selectResult.isNodeSet()) {
            XPathNodeSet ns = selectResult.asNodeSet();
            for (XPathNode node : ns) {
                items.add(XPathNodeSet.of(node));
            }
        } else {
            items.add(selectResult);
        }
        return items;
    }

    /**
     * Looks up all current parameter values from the iteration context scope.
     * Used when no xsl:next-iteration was encountered.
     */
    private Map<String, XPathValue> lookupCurrentParams(TransformContext iterContext) {
        Map<String, XPathValue> result = new HashMap<>();
        for (IterateParam param : params) {
            result.put(param.getName(),
                iterContext.getVariableScope().lookup(param.getName()));
        }
        return result;
    }

    /**
     * Merges next-iteration params with current values for any params not
     * mentioned in xsl:next-iteration (they retain their previous value).
     * Applies type coercion based on the declared parameter type.
     */
    private Map<String, XPathValue> mergeParams(Map<String, XPathValue> nextParams,
                                                 TransformContext iterContext) {
        Map<String, XPathValue> merged = new HashMap<>();
        for (IterateParam param : params) {
            String name = param.getName();
            XPathValue val;
            if (nextParams.containsKey(name)) {
                val = coerceParamValue(nextParams.get(name), param.getAsType());
            } else {
                val = iterContext.getVariableScope().lookup(name);
            }
            merged.put(name, val);
        }
        return merged;
    }

    /**
     * Coerces a value to match the declared parameter type.
     * For example, coerces an RTF to its string value for as="xs:string".
     */
    private XPathValue coerceParamValue(XPathValue value, String asType) {
        if (asType == null || value == null) {
            return value;
        }
        String type = asType.trim();
        if (type.startsWith("xs:")) {
            type = type.substring(3);
        }
        if ("string".equals(type)) {
            if (value instanceof XPathString) {
                return value;
            }
            return new XPathString(value.asString());
        }
        return value;
    }

    /**
     * Creates the context for a single iteration.
     */
    private TransformContext createIterationContext(TransformContext parent, XPathValue item,
                                                     int position, int size,
                                                     Map<String, XPathValue> iterParams) {
        TransformContext ctx;
        if (item instanceof XPathNode) {
            XPathNode node = (XPathNode) item;
            ctx = parent.withContextNode(node)
                        .withPositionAndSize(position, size)
                        .pushVariableScope();
        } else if (item.isNodeSet()) {
            XPathNodeSet ns = item.asNodeSet();
            Iterator<XPathNode> iter = ns.iterator();
            if (iter.hasNext()) {
                XPathNode node = iter.next();
                ctx = parent.withContextNode(node)
                            .withPositionAndSize(position, size)
                            .pushVariableScope();
            } else {
                ctx = parent.withPositionAndSize(position, size)
                            .pushVariableScope();
            }
        } else {
            if (parent instanceof BasicTransformContext) {
                ctx = ((BasicTransformContext) parent).withContextItem(item)
                            .withPositionAndSize(position, size)
                            .pushVariableScope();
            } else {
                ctx = parent.withPositionAndSize(position, size)
                            .pushVariableScope();
            }
        }
        
        for (Map.Entry<String, XPathValue> entry : iterParams.entrySet()) {
            ctx.getVariableScope().bind(entry.getKey(), entry.getValue());
        }
        
        return ctx;
    }

    /**
     * Creates the context for on-completion execution.
     * Per the XSLT 3.0 spec, the context item, position, and size are
     * all undefined within xsl:on-completion.
     */
    private TransformContext createCompletionContext(TransformContext parent,
                                                      Map<String, XPathValue> completionParams) {
        TransformContext ctx = parent.withContextNode(null)
                                     .pushVariableScope();
        if (ctx instanceof BasicTransformContext) {
            ((BasicTransformContext) ctx).setContextItemUndefined(true);
        }
        
        for (Map.Entry<String, XPathValue> entry : completionParams.entrySet()) {
            ctx.getVariableScope().bind(entry.getKey(), entry.getValue());
        }
        
        return ctx;
    }

    /**
     * Executes the body and collects next-iteration parameters.
     *
     * <p>The body may contain xsl:next-iteration or xsl:break instructions,
     * which communicate back to this method via signal exceptions. A
     * NextIterationSignal carries updated parameter values for the next
     * iteration, while a BreakSignal indicates early termination.
     */
    private IterationResult executeBody(TransformContext context, OutputHandler output) 
            throws SAXException {
        if (body == null) {
            return IterationResult.continueWith(new HashMap<>());
        }
        
        try {
            body.execute(context, output);
        } catch (NextIterationNode.NextIterationSignal signal) {
            return IterationResult.continueWith(signal.getParams());
        } catch (BreakNode.BreakSignal signal) {
            return IterationResult.breakIteration();
        }
        
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

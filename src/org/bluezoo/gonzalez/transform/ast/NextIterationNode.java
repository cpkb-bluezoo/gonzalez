/*
 * NextIterationNode.java
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
import org.bluezoo.gonzalez.transform.xpath.XPathExpression;
import org.bluezoo.gonzalez.transform.xpath.expr.XPathException;
import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;
import org.xml.sax.SAXException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * XSLT 3.0 xsl:next-iteration instruction.
 *
 * <p>Used within xsl:iterate to pass updated parameter values to the next
 * iteration. Each xsl:with-param specifies the value for a parameter.
 *
 * <p>Example:
 * <pre>
 * &lt;xsl:next-iteration&gt;
 *   &lt;xsl:with-param name="total" select="$total + @price"/&gt;
 * &lt;/xsl:next-iteration&gt;
 * </pre>
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class NextIterationNode implements XSLTNode {

    /**
     * Represents a parameter value for next-iteration.
     *
     * <p>Encapsulates a parameter value specified via xsl:with-param within
     * xsl:next-iteration. The value can be provided either via the select
     * attribute or as element content.
     */
    public static final class ParamValue {
        private final String name;
        private final XPathExpression select;
        private final XSLTNode content;

        /**
         * Creates a new parameter value.
         *
         * @param name the parameter name
         * @param select the select expression (may be null if using content)
         * @param content the content node (may be null if using select)
         */
        public ParamValue(String name, XPathExpression select, XSLTNode content) {
            this.name = name;
            this.select = select;
            this.content = content;
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
         * Evaluates the parameter value.
         *
         * <p>If a select expression is provided, it is evaluated. Otherwise,
         * the content is evaluated as a result tree fragment.
         *
         * @param context the transformation context
         * @return the evaluated parameter value
         * @throws SAXException if an error occurs during evaluation
         */
        public XPathValue evaluate(TransformContext context) throws SAXException {
            try {
                if (select != null) {
                    return select.evaluate(context);
                }
                // TODO: Evaluate content as RTF
                return null;
            } catch (XPathException e) {
                throw new SAXException("Error evaluating xsl:with-param: " + e.getMessage(), e);
            }
        }
    }

    private final List<ParamValue> params;

    /**
     * Creates a new next-iteration instruction.
     *
     * @param params the parameter values for the next iteration
     */
    public NextIterationNode(List<ParamValue> params) {
        this.params = params != null ? new ArrayList<>(params) : new ArrayList<>();
    }

    /**
     * Returns the parameter values.
     *
     * @return the param values
     */
    public List<ParamValue> getParams() {
        return params;
    }

    @Override
    public void execute(TransformContext context, OutputHandler output) throws SAXException {
        // Evaluate all parameters
        Map<String, XPathValue> values = new HashMap<>();
        for (ParamValue param : params) {
            values.put(param.getName(), param.evaluate(context));
        }
        
        // Signal to enclosing xsl:iterate
        throw new NextIterationSignal(values);
    }

    @Override
    public StreamingCapability getStreamingCapability() {
        return StreamingCapability.FULL;
    }

    @Override
    public String toString() {
        return "NextIterationNode[params=" + params.size() + "]";
    }

    /**
     * Signal exception used to communicate next-iteration to the iterate loop.
     *
     * <p>This exception is thrown by xsl:next-iteration to signal to the
     * enclosing xsl:iterate that iteration should continue with updated
     * parameter values. The exception is caught by the iterate loop handler.
     */
    public static class NextIterationSignal extends SAXException {
        private final Map<String, XPathValue> params;

        /**
         * Creates a new next-iteration signal.
         *
         * @param params the parameter values for the next iteration
         */
        public NextIterationSignal(Map<String, XPathValue> params) {
            super("next-iteration");
            this.params = params;
        }

        /**
         * Returns the parameter values for the next iteration.
         *
         * @return map of parameter names to values
         */
        public Map<String, XPathValue> getParams() {
            return params;
        }
    }

}

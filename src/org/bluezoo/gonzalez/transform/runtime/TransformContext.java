/*
 * TransformContext.java
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
import org.bluezoo.gonzalez.transform.xpath.XPathContext;
import org.bluezoo.gonzalez.transform.xpath.XPathExpression;
import org.bluezoo.gonzalez.transform.xpath.expr.XPathException;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNode;
import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;

/**
 * Context for XSLT transformation execution.
 *
 * <p>The transform context extends XPath context with XSLT-specific features:
 * <ul>
 *   <li>Access to the compiled stylesheet</li>
 *   <li>Current template rule and mode</li>
 *   <li>Variable scope management</li>
 *   <li>Parameter passing</li>
 *   <li>Output state</li>
 * </ul>
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public interface TransformContext extends XPathContext {

    /**
     * Returns the compiled stylesheet.
     *
     * @return the stylesheet
     */
    CompiledStylesheet getStylesheet();

    /**
     * Returns the current template mode.
     *
     * @return the mode name, or null for default mode
     */
    String getCurrentMode();

    /**
     * Returns the variable scope for binding and lookup.
     *
     * @return the variable scope
     */
    VariableScope getVariableScope();

    /**
     * Creates a new context with pushed variable scope.
     *
     * @return a new context with fresh variable scope
     */
    TransformContext pushVariableScope();

    /**
     * Creates a new context with the specified mode.
     *
     * @param mode the template mode
     * @return a new context with the mode
     */
    TransformContext withMode(String mode);

    /**
     * Creates a new context with the specified context node.
     *
     * @param node the context node
     * @return a new context
     */
    @Override
    TransformContext withContextNode(XPathNode node);

    /**
     * Creates a new context with the specified position and size.
     *
     * @param position the context position
     * @param size the context size
     * @return a new context
     */
    @Override
    TransformContext withPositionAndSize(int position, int size);

    /**
     * Evaluates an XPath expression in this context.
     *
     * @param expression the compiled expression
     * @return the result
     * @throws XPathException if evaluation fails
     */
    XPathValue evaluateXPath(XPathExpression expression) throws XPathException;

    /**
     * Returns the error listener for this transformation, if any.
     *
     * <p>The error listener receives warnings and errors from xsl:message
     * and other error reporting mechanisms.
     *
     * @return the error listener, or null if none is set
     */
    javax.xml.transform.ErrorListener getErrorListener();

    /**
     * Returns the currently executing template rule, if any.
     *
     * <p>This is used by xsl:next-match to find the next matching template.
     *
     * @return the current template rule, or null if not in a template
     */
    org.bluezoo.gonzalez.transform.compiler.TemplateRule getCurrentTemplateRule();

    /**
     * Creates a new context with the specified current template rule.
     *
     * @param rule the template rule being executed
     * @return a new context
     */
    TransformContext withCurrentTemplateRule(org.bluezoo.gonzalez.transform.compiler.TemplateRule rule);

    /**
     * Returns the template matcher for finding template rules.
     *
     * @return the template matcher
     */
    TemplateMatcher getTemplateMatcher();

    /**
     * Returns the runtime schema validator for output validation.
     *
     * <p>The validator is used when validation="strict" or validation="lax"
     * is specified on xsl:element, xsl:copy, xsl:copy-of, or literal result elements.
     *
     * @return the runtime validator, or null if not available
     */
    RuntimeSchemaValidator getRuntimeValidator();

    /**
     * Creates a new context with the specified static base URI.
     *
     * <p>This is used when executing instructions that have an xml:base
     * attribute, so that static-base-uri() returns the correct value.
     *
     * @param baseURI the static base URI for the instruction
     * @return a new context with the base URI
     */
    TransformContext withStaticBaseURI(String baseURI);

}

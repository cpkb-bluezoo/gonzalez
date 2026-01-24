/*
 * XPathExpression.java
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

package org.bluezoo.gonzalez.transform.xpath;

import org.bluezoo.gonzalez.transform.xpath.expr.Expr;
import org.bluezoo.gonzalez.transform.xpath.expr.XPathException;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNode;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNodeSet;
import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;

/**
 * A compiled XPath expression.
 *
 * <p>This class wraps a parsed XPath expression AST and provides a convenient
 * API for evaluation. Compiled expressions are reusable and thread-safe for
 * evaluation (though not for modification).
 *
 * <p>Example usage:
 * <pre>
 * XPathExpression expr = XPathExpression.compile("//book[@isbn]");
 * XPathNodeSet books = expr.evaluateAsNodeSet(documentRoot);
 * </pre>
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class XPathExpression {

    private final String expressionString;
    private final Expr compiledExpr;

    /**
     * Creates a compiled expression (private - use compile()).
     */
    private XPathExpression(String expressionString, Expr compiledExpr) {
        this.expressionString = expressionString;
        this.compiledExpr = compiledExpr;
    }

    /**
     * Compiles an XPath expression.
     *
     * @param expression the XPath expression string
     * @return the compiled expression
     * @throws XPathSyntaxException if the expression is invalid
     */
    public static XPathExpression compile(String expression) throws XPathSyntaxException {
        return compile(expression, null);
    }

    /**
     * Compiles an XPath expression with a namespace resolver.
     *
     * @param expression the XPath expression string
     * @param namespaceResolver resolver for namespace prefixes
     * @return the compiled expression
     * @throws XPathSyntaxException if the expression is invalid
     */
    public static XPathExpression compile(String expression, 
                                          XPathParser.NamespaceResolver namespaceResolver) 
            throws XPathSyntaxException {
        XPathParser parser = new XPathParser(expression, namespaceResolver);
        Expr expr = parser.parse();
        return new XPathExpression(expression, expr);
    }

    /**
     * Evaluates the expression against a context node.
     *
     * @param contextNode the context node
     * @return the result value
     * @throws XPathException if evaluation fails
     */
    public XPathValue evaluate(XPathNode contextNode) throws XPathException {
        XPathContext context = new BasicXPathContext(contextNode);
        return compiledExpr.evaluate(context);
    }

    /**
     * Evaluates the expression with a full context.
     *
     * @param context the evaluation context
     * @return the result value
     * @throws XPathException if evaluation fails
     */
    public XPathValue evaluate(XPathContext context) throws XPathException {
        return compiledExpr.evaluate(context);
    }

    /**
     * Evaluates the expression and returns the result as a string.
     *
     * @param contextNode the context node
     * @return the result as a string
     * @throws XPathException if evaluation fails
     */
    public String evaluateAsString(XPathNode contextNode) throws XPathException {
        return evaluate(contextNode).asString();
    }

    /**
     * Evaluates the expression and returns the result as a number.
     *
     * @param contextNode the context node
     * @return the result as a number
     * @throws XPathException if evaluation fails
     */
    public double evaluateAsNumber(XPathNode contextNode) throws XPathException {
        return evaluate(contextNode).asNumber();
    }

    /**
     * Evaluates the expression and returns the result as a boolean.
     *
     * @param contextNode the context node
     * @return the result as a boolean
     * @throws XPathException if evaluation fails
     */
    public boolean evaluateAsBoolean(XPathNode contextNode) throws XPathException {
        return evaluate(contextNode).asBoolean();
    }

    /**
     * Evaluates the expression and returns the result as a node-set.
     *
     * @param contextNode the context node
     * @return the result as a node-set
     * @throws XPathException if evaluation fails or result is not a node-set
     */
    public XPathNodeSet evaluateAsNodeSet(XPathNode contextNode) throws XPathException {
        XPathValue result = evaluate(contextNode);
        if (!result.isNodeSet()) {
            throw new XPathException("Expression result is not a node-set: " + expressionString);
        }
        return result.asNodeSet();
    }

    /**
     * Returns the original expression string.
     *
     * @return the expression string
     */
    public String getExpressionString() {
        return expressionString;
    }

    /**
     * Returns the compiled expression AST (for internal use).
     *
     * @return the expression AST
     */
    Expr getCompiledExpr() {
        return compiledExpr;
    }

    @Override
    public String toString() {
        return "XPathExpression[" + expressionString + "]";
    }

}

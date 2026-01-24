/*
 * XPathParser.java
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

import org.bluezoo.gonzalez.transform.xpath.expr.*;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * XPath 1.0 expression parser using the Pratt (operator precedence) algorithm.
 *
 * <p>This parser uses explicit stacks instead of recursion for predictable
 * resource usage and consistency with Gonzalez's state-machine design.
 *
 * <p>The parser handles:
 * <ul>
 *   <li>Location paths (absolute and relative)</li>
 *   <li>Binary operators (arithmetic, comparison, logical, union)</li>
 *   <li>Unary negation</li>
 *   <li>Function calls</li>
 *   <li>Variable references</li>
 *   <li>Parenthesized expressions</li>
 *   <li>Predicates</li>
 * </ul>
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class XPathParser {

    private final XPathLexer lexer;
    private final NamespaceResolver namespaceResolver;

    // Stacks for Pratt parsing
    private final Deque<Expr> exprStack = new ArrayDeque<>();
    private final Deque<StackEntry> opStack = new ArrayDeque<>();

    /**
     * Interface for resolving namespace prefixes during parsing.
     */
    @FunctionalInterface
    public interface NamespaceResolver {
        /**
         * Resolves a namespace prefix to a URI.
         *
         * @param prefix the prefix
         * @return the namespace URI, or null if not found
         */
        String resolve(String prefix);
    }

    /**
     * Entry on the operator stack.
     */
    private static class StackEntry {
        final Operator operator;
        final int precedence;

        StackEntry(Operator operator, int precedence) {
            this.operator = operator;
            this.precedence = precedence;
        }
    }

    /**
     * Creates a parser for the given expression.
     *
     * @param expression the XPath expression
     */
    public XPathParser(String expression) {
        this(expression, null);
    }

    /**
     * Creates a parser with a namespace resolver.
     *
     * @param expression the XPath expression
     * @param namespaceResolver resolver for namespace prefixes
     */
    public XPathParser(String expression, NamespaceResolver namespaceResolver) {
        this.lexer = new XPathLexer(expression);
        this.namespaceResolver = namespaceResolver;
    }

    /**
     * Parses the expression and returns the AST.
     *
     * @return the parsed expression
     * @throws XPathSyntaxException if the expression has invalid syntax
     */
    public Expr parse() throws XPathSyntaxException {
        exprStack.clear();
        opStack.clear();

        Expr result = parseExpr();

        if (lexer.current() != XPathToken.EOF) {
            throw new XPathSyntaxException("Unexpected token: " + lexer.current(),
                lexer.getExpression(), lexer.tokenStart());
        }

        return result;
    }

    /**
     * Parses an expression using the Pratt algorithm.
     */
    private Expr parseExpr() throws XPathSyntaxException {
        return parseOrExpr();
    }

    /**
     * Parses using operator precedence (Pratt-style).
     */
    private Expr parseOrExpr() throws XPathSyntaxException {
        // Parse the first operand
        exprStack.push(parseUnaryExpr());

        // Process operators
        while (true) {
            Operator op = Operator.fromToken(lexer.current());
            if (op == null) {
                break;
            }

            int prec = op.getPrecedence();

            // Reduce operators with higher or equal precedence
            while (!opStack.isEmpty() && opStack.peek().precedence >= prec) {
                reduce();
            }

            // Push the new operator
            opStack.push(new StackEntry(op, prec));
            lexer.advance();

            // Parse the next operand
            exprStack.push(parseUnaryExpr());
        }

        // Reduce all remaining operators
        while (!opStack.isEmpty()) {
            reduce();
        }

        return exprStack.pop();
    }

    /**
     * Reduces the top operator on the stack.
     */
    private void reduce() {
        StackEntry entry = opStack.pop();
        Expr right = exprStack.pop();
        Expr left = exprStack.pop();
        exprStack.push(new BinaryExpr(entry.operator, left, right));
    }

    /**
     * Parses a unary expression (handles leading minus signs).
     */
    private Expr parseUnaryExpr() throws XPathSyntaxException {
        int negationCount = 0;
        while (lexer.current() == XPathToken.MINUS) {
            negationCount++;
            lexer.advance();
        }

        Expr expr = parsePathExpr();

        if (negationCount > 0) {
            expr = new UnaryExpr(expr, negationCount);
        }

        return expr;
    }

    /**
     * Parses a path expression (filter expression optionally followed by location path).
     */
    private Expr parsePathExpr() throws XPathSyntaxException {
        XPathToken token = lexer.current();

        // Check if this starts a location path
        if (token == XPathToken.SLASH || token == XPathToken.DOUBLE_SLASH) {
            return parseLocationPath();
        }

        if (token.canStartStep()) {
            // Could be a location path or a filter expression
            // Try to determine which based on context
            if (isLocationPathStart()) {
                return parseLocationPath();
            }
        }

        // Parse as filter expression
        Expr filter = parseFilterExpr();

        // Check for following path
        if (lexer.current() == XPathToken.SLASH || lexer.current() == XPathToken.DOUBLE_SLASH) {
            LocationPath path = parseRelativeLocationPath();
            return new PathExpr(filter, path);
        }

        return filter;
    }

    /**
     * Determines if current position starts a location path vs primary expression.
     */
    private boolean isLocationPathStart() {
        XPathToken token = lexer.current();

        // These always start steps, not primary expressions
        if (token == XPathToken.DOT || token == XPathToken.DOUBLE_DOT || token == XPathToken.AT) {
            return true;
        }

        // Axis specifiers always start steps
        if (token.isAxis()) {
            return true;
        }

        // NCName could be element name or function name
        // It's a function call if followed by '('
        if (token == XPathToken.NCNAME) {
            XPathToken next = lexer.peek();
            if (next == XPathToken.LPAREN) {
                // Check if it's a node type test (which is part of a step)
                String name = lexer.value();
                if ("node".equals(name) || "text".equals(name) || 
                    "comment".equals(name) || "processing-instruction".equals(name)) {
                    return true;
                }
                // Otherwise it's a function call
                return false;
            }
            // No paren follows - it's an element name (step)
            return true;
        }

        // Node type tests
        if (token.isNodeType()) {
            return true;
        }

        // Wildcard
        if (token == XPathToken.STAR) {
            return true;
        }

        return false;
    }

    /**
     * Parses a filter expression (primary with optional predicates).
     */
    private Expr parseFilterExpr() throws XPathSyntaxException {
        Expr primary = parsePrimaryExpr();

        // Parse predicates
        List<Expr> predicates = null;
        while (lexer.current() == XPathToken.LBRACKET) {
            if (predicates == null) {
                predicates = new ArrayList<>();
            }
            predicates.add(parsePredicate());
        }

        if (predicates != null && !predicates.isEmpty()) {
            return new FilterExpr(primary, predicates);
        }

        return primary;
    }

    /**
     * Parses a primary expression (literal, variable, function call, or parenthesized).
     */
    private Expr parsePrimaryExpr() throws XPathSyntaxException {
        XPathToken token = lexer.current();

        switch (token) {
            case STRING_LITERAL:
                String strVal = lexer.value();
                lexer.advance();
                return Literal.string(strVal);

            case NUMBER_LITERAL:
                String numVal = lexer.value();
                lexer.advance();
                return Literal.number(numVal);

            case DOLLAR:
                return parseVariableReference();

            case LPAREN:
                return parseParenthesizedExpr();

            case NCNAME:
                // Could be a function call - check for '('
                if (lexer.peek() == XPathToken.LPAREN) {
                    return parseFunctionCall();
                }
                // Fall through - not a primary expression
                break;

            default:
                break;
        }

        throw new XPathSyntaxException("Expected primary expression, found: " + token,
            lexer.getExpression(), lexer.tokenStart());
    }

    /**
     * Parses a variable reference ($name).
     */
    private Expr parseVariableReference() throws XPathSyntaxException {
        lexer.expect(XPathToken.DOLLAR);

        if (lexer.current() != XPathToken.NCNAME) {
            throw new XPathSyntaxException("Expected variable name",
                lexer.getExpression(), lexer.tokenStart());
        }

        String name = lexer.value();
        lexer.advance();

        // Check for prefix:localname
        if (lexer.current() == XPathToken.COLON) {
            lexer.advance();
            if (lexer.current() != XPathToken.NCNAME) {
                throw new XPathSyntaxException("Expected local name after prefix",
                    lexer.getExpression(), lexer.tokenStart());
            }
            String localName = lexer.value();
            lexer.advance();
            return new VariableReference(name, localName);
        }

        return new VariableReference(name);
    }

    /**
     * Parses a parenthesized expression.
     */
    private Expr parseParenthesizedExpr() throws XPathSyntaxException {
        lexer.expect(XPathToken.LPAREN);
        Expr expr = parseExpr();
        lexer.expect(XPathToken.RPAREN);
        return expr;
    }

    /**
     * Parses a function call.
     */
    private Expr parseFunctionCall() throws XPathSyntaxException {
        String prefix = null;
        String localName = lexer.value();
        lexer.advance();

        // Check for prefix
        if (lexer.current() == XPathToken.COLON) {
            prefix = localName;
            lexer.advance();
            if (lexer.current() != XPathToken.NCNAME) {
                throw new XPathSyntaxException("Expected function name after prefix",
                    lexer.getExpression(), lexer.tokenStart());
            }
            localName = lexer.value();
            lexer.advance();
        }

        lexer.expect(XPathToken.LPAREN);

        List<Expr> args = new ArrayList<>();
        if (lexer.current() != XPathToken.RPAREN) {
            args.add(parseExpr());
            while (lexer.current() == XPathToken.COMMA) {
                lexer.advance();
                args.add(parseExpr());
            }
        }

        lexer.expect(XPathToken.RPAREN);

        return new FunctionCall(prefix, localName, args);
    }

    /**
     * Parses a predicate ([expr]).
     */
    private Expr parsePredicate() throws XPathSyntaxException {
        lexer.expect(XPathToken.LBRACKET);
        Expr expr = parseExpr();
        lexer.expect(XPathToken.RBRACKET);
        return expr;
    }

    /**
     * Parses a location path (absolute or relative).
     */
    private Expr parseLocationPath() throws XPathSyntaxException {
        boolean absolute = false;
        List<Step> steps = new ArrayList<>();

        // Check for absolute path
        if (lexer.current() == XPathToken.SLASH) {
            absolute = true;
            lexer.advance();

            // Just "/" selects the root
            if (!canStartStep()) {
                return new LocationPath(true, steps);
            }
        } else if (lexer.current() == XPathToken.DOUBLE_SLASH) {
            absolute = true;
            lexer.advance();

            // "//" is abbreviation for /descendant-or-self::node()/
            steps.add(new Step(Step.Axis.DESCENDANT_OR_SELF, Step.NodeTestType.NODE));
        }

        // Parse relative location path
        steps.addAll(parseRelativeSteps());

        return new LocationPath(absolute, steps);
    }

    /**
     * Parses a relative location path (returns the path itself, not wrapped).
     */
    private LocationPath parseRelativeLocationPath() throws XPathSyntaxException {
        List<Step> steps = new ArrayList<>();

        if (lexer.current() == XPathToken.SLASH) {
            lexer.advance();
        } else if (lexer.current() == XPathToken.DOUBLE_SLASH) {
            lexer.advance();
            steps.add(new Step(Step.Axis.DESCENDANT_OR_SELF, Step.NodeTestType.NODE));
        }

        steps.addAll(parseRelativeSteps());
        return new LocationPath(false, steps);
    }

    /**
     * Parses the steps of a relative location path.
     */
    private List<Step> parseRelativeSteps() throws XPathSyntaxException {
        List<Step> steps = new ArrayList<>();

        // First step
        steps.add(parseStep());

        // Additional steps
        while (lexer.current() == XPathToken.SLASH || lexer.current() == XPathToken.DOUBLE_SLASH) {
            if (lexer.current() == XPathToken.DOUBLE_SLASH) {
                lexer.advance();
                steps.add(new Step(Step.Axis.DESCENDANT_OR_SELF, Step.NodeTestType.NODE));
            } else {
                lexer.advance();
            }
            steps.add(parseStep());
        }

        return steps;
    }

    /**
     * Parses a single step.
     */
    private Step parseStep() throws XPathSyntaxException {
        // Handle abbreviations first
        if (lexer.current() == XPathToken.DOT) {
            lexer.advance();
            return new Step(Step.Axis.SELF, Step.NodeTestType.NODE);
        }

        if (lexer.current() == XPathToken.DOUBLE_DOT) {
            lexer.advance();
            return new Step(Step.Axis.PARENT, Step.NodeTestType.NODE);
        }

        // Determine axis
        Step.Axis axis = Step.Axis.CHILD; // default

        if (lexer.current() == XPathToken.AT) {
            axis = Step.Axis.ATTRIBUTE;
            lexer.advance();
        } else if (lexer.current().isAxis()) {
            axis = tokenToAxis(lexer.current());
            lexer.advance();
            // The :: was already consumed by the lexer
        }

        // Parse node test
        Step step = parseNodeTest(axis);

        // Parse predicates
        List<Expr> predicates = new ArrayList<>();
        while (lexer.current() == XPathToken.LBRACKET) {
            predicates.add(parsePredicate());
        }

        if (!predicates.isEmpty()) {
            step = step.withPredicates(predicates);
        }

        return step;
    }

    /**
     * Parses a node test.
     */
    private Step parseNodeTest(Step.Axis axis) throws XPathSyntaxException {
        XPathToken token = lexer.current();

        // Wildcard
        if (token == XPathToken.STAR) {
            lexer.advance();
            return Step.wildcard(axis);
        }

        // Node type test
        if (token.isNodeType()) {
            Step.NodeTestType nodeTestType;
            switch (token) {
                case NODE_TYPE_NODE:
                    nodeTestType = Step.NodeTestType.NODE;
                    break;
                case NODE_TYPE_TEXT:
                    nodeTestType = Step.NodeTestType.TEXT;
                    break;
                case NODE_TYPE_COMMENT:
                    nodeTestType = Step.NodeTestType.COMMENT;
                    break;
                case NODE_TYPE_PI:
                    nodeTestType = Step.NodeTestType.PROCESSING_INSTRUCTION;
                    break;
                default:
                    throw new XPathSyntaxException("Unexpected node type: " + token,
                        lexer.getExpression(), lexer.tokenStart());
            }

            lexer.advance();
            lexer.expect(XPathToken.LPAREN);

            // processing-instruction() can have an optional literal argument
            String piTarget = null;
            if (nodeTestType == Step.NodeTestType.PROCESSING_INSTRUCTION &&
                lexer.current() == XPathToken.STRING_LITERAL) {
                piTarget = lexer.value();
                lexer.advance();
            }

            lexer.expect(XPathToken.RPAREN);

            if (piTarget != null) {
                return Step.processingInstruction(axis, piTarget);
            }
            return new Step(axis, nodeTestType);
        }

        // Name test
        if (token == XPathToken.NCNAME) {
            String name = lexer.value();
            lexer.advance();

            // Check for prefix:localname or prefix:*
            if (lexer.current() == XPathToken.COLON) {
                lexer.advance();

                if (lexer.current() == XPathToken.STAR) {
                    // prefix:* - namespace wildcard
                    lexer.advance();
                    String nsUri = resolvePrefix(name);
                    return Step.namespaceWildcard(axis, nsUri);
                }

                if (lexer.current() == XPathToken.NCNAME) {
                    String localName = lexer.value();
                    lexer.advance();
                    String nsUri = resolvePrefix(name);
                    return new Step(axis, nsUri, localName);
                }

                throw new XPathSyntaxException("Expected local name or * after prefix",
                    lexer.getExpression(), lexer.tokenStart());
            }

            // Simple name
            return new Step(axis, name);
        }

        throw new XPathSyntaxException("Expected node test, found: " + token,
            lexer.getExpression(), lexer.tokenStart());
    }

    /**
     * Checks if the current token can start a step.
     */
    private boolean canStartStep() {
        return lexer.current().canStartStep();
    }

    /**
     * Converts an axis token to the Axis enum.
     */
    private Step.Axis tokenToAxis(XPathToken token) throws XPathSyntaxException {
        switch (token) {
            case AXIS_ANCESTOR: return Step.Axis.ANCESTOR;
            case AXIS_ANCESTOR_OR_SELF: return Step.Axis.ANCESTOR_OR_SELF;
            case AXIS_ATTRIBUTE: return Step.Axis.ATTRIBUTE;
            case AXIS_CHILD: return Step.Axis.CHILD;
            case AXIS_DESCENDANT: return Step.Axis.DESCENDANT;
            case AXIS_DESCENDANT_OR_SELF: return Step.Axis.DESCENDANT_OR_SELF;
            case AXIS_FOLLOWING: return Step.Axis.FOLLOWING;
            case AXIS_FOLLOWING_SIBLING: return Step.Axis.FOLLOWING_SIBLING;
            case AXIS_NAMESPACE: return Step.Axis.NAMESPACE;
            case AXIS_PARENT: return Step.Axis.PARENT;
            case AXIS_PRECEDING: return Step.Axis.PRECEDING;
            case AXIS_PRECEDING_SIBLING: return Step.Axis.PRECEDING_SIBLING;
            case AXIS_SELF: return Step.Axis.SELF;
            default:
                throw new XPathSyntaxException("Not an axis token: " + token,
                    lexer.getExpression(), lexer.tokenStart());
        }
    }

    /**
     * Resolves a namespace prefix.
     */
    private String resolvePrefix(String prefix) throws XPathSyntaxException {
        if (namespaceResolver != null) {
            String uri = namespaceResolver.resolve(prefix);
            if (uri != null) {
                return uri;
            }
        }
        // Return the prefix itself if no resolver - will be resolved at runtime
        return prefix;
    }

    /**
     * Static helper to parse an expression string.
     *
     * @param expression the XPath expression
     * @return the parsed expression
     * @throws XPathSyntaxException if syntax is invalid
     */
    public static Expr parseExpression(String expression) throws XPathSyntaxException {
        return new XPathParser(expression).parse();
    }

}

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

import org.bluezoo.gonzalez.transform.xpath.expr.BinaryExpr;
import org.bluezoo.gonzalez.transform.xpath.expr.Expr;
import org.bluezoo.gonzalez.transform.xpath.expr.FilterExpr;
import org.bluezoo.gonzalez.transform.xpath.expr.ForExpr;
import org.bluezoo.gonzalez.transform.xpath.expr.FunctionCall;
import org.bluezoo.gonzalez.transform.xpath.expr.IfExpr;
import org.bluezoo.gonzalez.transform.xpath.expr.LetExpr;
import org.bluezoo.gonzalez.transform.xpath.expr.Literal;
import org.bluezoo.gonzalez.transform.xpath.expr.LocationPath;
import org.bluezoo.gonzalez.transform.xpath.expr.Operator;
import org.bluezoo.gonzalez.transform.xpath.expr.PathExpr;
import org.bluezoo.gonzalez.transform.xpath.expr.QuantifiedExpr;
import org.bluezoo.gonzalez.transform.xpath.expr.SequenceExpr;
import org.bluezoo.gonzalez.transform.xpath.expr.Step;
import org.bluezoo.gonzalez.transform.xpath.expr.UnaryExpr;
import org.bluezoo.gonzalez.transform.xpath.expr.VariableReference;
import org.bluezoo.gonzalez.transform.xpath.expr.XPathException;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * XPath 1.0 expression parser using the Pratt (operator precedence) algorithm.
 *
 * <p>This parser uses explicit stacks and state machines instead of recursion
 * for predictable resource usage and consistency with Gonzalez's design.
 * All nested expression parsing (function arguments, predicates, parenthesized
 * expressions, sequences) is handled by pushing/popping parsing contexts.
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

    // ========================================================================
    // State Machine Types
    // ========================================================================

    /**
     * Types of nested parsing contexts.
     */
    private enum ContextType {
        TOP_LEVEL,           // Top-level expression
        FUNCTION_ARG,        // Function argument (between , and , or ))
        PREDICATE,           // Predicate expression (between [ and ])
        PARENTHESIZED,       // Parenthesized expression (after (, before ))
        SEQUENCE             // Sequence item (between ( and ), with commas)
    }

    /**
     * Parsing states within a context.
     */
    private enum ParseState {
        NEED_OPERAND,        // Need to parse an operand (unary check + path expr)
        HAVE_OPERAND,        // Have operand, looking for operator or context end
        BUILDING_FUNCTION,   // Building a function call, need to start args
        BUILDING_FILTER,     // Building a filter expression, need to start predicate
        PATH_CONTINUATION    // After filter/primary, checking for path continuation
    }

    /**
     * Entry on the operator stack for Pratt parsing.
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
     * Parsing context for managing nested expression parsing without recursion.
     */
    private static class ParseContext {
        final ContextType type;
        ParseState state;
        
        // Pratt parsing stacks (local to each context)
        final Deque<Expr> exprStack;
        final Deque<StackEntry> opStack;
        
        // Accumulated items (for function args, sequences)
        List<Expr> items;
        
        // Separate list for predicate items (to avoid mixing with function args)
        List<Expr> predicateItems;
        
        // Unary negation count
        int negationCount;
        
        // Function call construction
        String functionPrefix;
        String functionName;
        
        // Filter expression construction (primary expr waiting for predicates)
        Expr filterBase;
        
        // True if this is a nested function call within another expression
        boolean nestedInExpression;
        
        ParseContext(ContextType type) {
            this.type = type;
            this.state = ParseState.NEED_OPERAND;
            this.exprStack = new ArrayDeque<>();
            this.opStack = new ArrayDeque<>();
        }
    }

    // ========================================================================
    // Construction
    // ========================================================================

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

    // ========================================================================
    // Main Parsing Entry Point
    // ========================================================================

    /**
     * Parses the expression and returns the AST.
     * This is the main iterative parsing loop - no recursive calls.
     *
     * @return the parsed expression
     * @throws XPathSyntaxException if the expression has invalid syntax
     */
    public Expr parse() throws XPathSyntaxException {
        Deque<ParseContext> contextStack = new ArrayDeque<>();
        ParseContext ctx = new ParseContext(ContextType.TOP_LEVEL);
        Expr finalResult = null;

        // Main iterative parsing loop
        mainLoop:
        while (true) {
            switch (ctx.state) {
                case NEED_OPERAND:
                    ctx = parseOperand(ctx, contextStack);
                    break;

                case HAVE_OPERAND:
                    ParseResult parseResult = processOperatorOrEnd(ctx, contextStack);
                    if (parseResult.done) {
                        // Parsing complete
                        finalResult = parseResult.result;
                        break mainLoop;
                    }
                    ctx = parseResult.context;
                    break;

                case BUILDING_FUNCTION:
                    ctx = startFunctionArgs(ctx, contextStack);
                    break;

                case BUILDING_FILTER:
                    ctx = startPredicate(ctx, contextStack);
                    break;

                case PATH_CONTINUATION:
                    ctx = handlePathContinuation(ctx);
                    break;

                default:
                    throw new IllegalStateException("Unknown parse state: " + ctx.state);
            }
        }

        if (lexer.current() != XPathToken.EOF) {
            throw new XPathSyntaxException("Unexpected token: " + lexer.current(),
                lexer.getExpression(), lexer.tokenStart());
        }

        return finalResult;
    }
    
    /**
     * Result of processOperatorOrEnd - either continues with new context or is done.
     */
    private static class ParseResult {
        final boolean done;
        final Expr result;
        final ParseContext context;
        
        private ParseResult(ParseContext context) {
            this.done = false;
            this.result = null;
            this.context = context;
        }
        
        private ParseResult(Expr result) {
            this.done = true;
            this.result = result;
            this.context = null;
        }
        
        static ParseResult continueWith(ParseContext ctx) {
            return new ParseResult(ctx);
        }
        
        static ParseResult finished(Expr result) {
            return new ParseResult(result);
        }
    }

    // ========================================================================
    // State Handlers
    // ========================================================================

    /**
     * Parses an operand (unary negation + path expression).
     * May push new contexts for function args, predicates, or parenthesized expressions.
     */
    private ParseContext parseOperand(ParseContext ctx, Deque<ParseContext> contextStack) 
            throws XPathSyntaxException {
        // Count unary negations
        ctx.negationCount = 0;
        while (lexer.current() == XPathToken.MINUS) {
            ctx.negationCount++;
            lexer.advance();
        }

        // Parse path expression (which may involve function calls, predicates, etc.)
        return parsePathExpr(ctx, contextStack);
    }

    /**
     * Parses a path expression iteratively.
     */
    private ParseContext parsePathExpr(ParseContext ctx, Deque<ParseContext> contextStack) 
            throws XPathSyntaxException {
        XPathToken token = lexer.current();

        // Check if this starts a location path
        if (token == XPathToken.SLASH || token == XPathToken.DOUBLE_SLASH) {
            Expr path = parseLocationPath();
            finishOperand(ctx, path);
            return ctx;
        }

        if (token.canStartStep() && isLocationPathStart()) {
            Expr path = parseLocationPath();
            finishOperand(ctx, path);
            return ctx;
        }

        // Parse as filter expression (primary with optional predicates)
        return parseFilterExpr(ctx, contextStack);
    }

    /**
     * Parses a filter expression (primary with optional predicates).
     * May push contexts for function args or predicates.
     */
    private ParseContext parseFilterExpr(ParseContext ctx, Deque<ParseContext> contextStack) 
            throws XPathSyntaxException {
        // Parse primary expression
        return parsePrimaryExpr(ctx, contextStack);
    }

    /**
     * Parses a primary expression.
     * May push contexts for function args or parenthesized expressions.
     */
    private ParseContext parsePrimaryExpr(ParseContext ctx, Deque<ParseContext> contextStack) 
            throws XPathSyntaxException {
        XPathToken token = lexer.current();

        switch (token) {
            case STRING_LITERAL: {
                String strVal = lexer.value();
                lexer.advance();
                Expr lit = Literal.string(strVal);
                ctx.filterBase = lit;
                ctx.state = ParseState.PATH_CONTINUATION;
                return ctx;
            }

            case NUMBER_LITERAL: {
                String numVal = lexer.value();
                lexer.advance();
                Expr lit = Literal.number(numVal);
                ctx.filterBase = lit;
                ctx.state = ParseState.PATH_CONTINUATION;
                return ctx;
            }

            case DOLLAR: {
                Expr var = parseVariableReference();
                ctx.filterBase = var;
                ctx.state = ParseState.PATH_CONTINUATION;
                return ctx;
            }

            case LPAREN:
                return startParenthesizedExpr(ctx, contextStack);

            // XPath 2.0/3.0 expression keywords
            case IF: {
                Expr ifExpr = parseIfExpr();
                ctx.filterBase = ifExpr;
                ctx.state = ParseState.PATH_CONTINUATION;
                return ctx;
            }

            case FOR: {
                Expr forExpr = parseForExpr();
                ctx.filterBase = forExpr;
                ctx.state = ParseState.PATH_CONTINUATION;
                return ctx;
            }

            case LET: {
                Expr letExpr = parseLetExpr();
                ctx.filterBase = letExpr;
                ctx.state = ParseState.PATH_CONTINUATION;
                return ctx;
            }

            case SOME: {
                Expr someExpr = parseQuantifiedExpr(QuantifiedExpr.Quantifier.SOME);
                ctx.filterBase = someExpr;
                ctx.state = ParseState.PATH_CONTINUATION;
                return ctx;
            }

            case EVERY: {
                Expr everyExpr = parseQuantifiedExpr(QuantifiedExpr.Quantifier.EVERY);
                ctx.filterBase = everyExpr;
                ctx.state = ParseState.PATH_CONTINUATION;
                return ctx;
            }

            case NCNAME:
                // Could be a function call
                XPathToken next = lexer.peek();
                if (next == XPathToken.LPAREN) {
                    return startFunctionCall(ctx, contextStack);
                }
                if (next == XPathToken.COLON && isPrefixedFunctionCall()) {
                    return startFunctionCall(ctx, contextStack);
                }
                // Fall through - not a primary expression
                break;

            default:
                break;
        }

        throw new XPathSyntaxException("Expected primary expression, found: " + token,
            lexer.getExpression(), lexer.tokenStart());
    }

    // ========================================================================
    // XPath 2.0/3.0 Expression Parsing
    // ========================================================================

    /**
     * Parses an if expression: if (test) then expr else expr
     */
    private Expr parseIfExpr() throws XPathSyntaxException {
        lexer.expect(XPathToken.IF);
        lexer.expect(XPathToken.LPAREN);
        
        // Parse condition
        Expr condition = parseSubExpression();
        
        lexer.expect(XPathToken.RPAREN);
        lexer.expect(XPathToken.THEN);
        
        // Parse then branch
        Expr thenExpr = parseSubExpression();
        
        lexer.expect(XPathToken.ELSE);
        
        // Parse else branch
        Expr elseExpr = parseSubExpression();
        
        return new IfExpr(condition, thenExpr, elseExpr);
    }

    /**
     * Parses a for expression: for $var in expr (, $var in expr)* return expr
     */
    private Expr parseForExpr() throws XPathSyntaxException {
        lexer.expect(XPathToken.FOR);
        
        List<ForExpr.Binding> bindings = new ArrayList<>();
        
        do {
            // Parse $varname
            lexer.expect(XPathToken.DOLLAR);
            if (lexer.current() != XPathToken.NCNAME) {
                throw new XPathSyntaxException("Expected variable name",
                    lexer.getExpression(), lexer.tokenStart());
            }
            String varName = lexer.value();
            lexer.advance();
            
            lexer.expect(XPathToken.IN);
            
            // Parse sequence expression
            Expr sequence = parseSubExpression();
            
            bindings.add(new ForExpr.Binding(varName, sequence));
            
        } while (lexer.match(XPathToken.COMMA));
        
        lexer.expect(XPathToken.RETURN);
        
        // Parse return expression
        Expr returnExpr = parseSubExpression();
        
        return new ForExpr(bindings, returnExpr);
    }

    /**
     * Parses a let expression: let $var := expr (, $var := expr)* return expr
     */
    private Expr parseLetExpr() throws XPathSyntaxException {
        lexer.expect(XPathToken.LET);
        
        List<LetExpr.Binding> bindings = new ArrayList<>();
        
        do {
            // Parse $varname
            lexer.expect(XPathToken.DOLLAR);
            if (lexer.current() != XPathToken.NCNAME) {
                throw new XPathSyntaxException("Expected variable name",
                    lexer.getExpression(), lexer.tokenStart());
            }
            String varName = lexer.value();
            lexer.advance();
            
            lexer.expect(XPathToken.ASSIGN);
            
            // Parse value expression
            Expr value = parseSubExpression();
            
            bindings.add(new LetExpr.Binding(varName, value));
            
        } while (lexer.match(XPathToken.COMMA));
        
        lexer.expect(XPathToken.RETURN);
        
        // Parse return expression
        Expr returnExpr = parseSubExpression();
        
        return new LetExpr(bindings, returnExpr);
    }

    /**
     * Parses a quantified expression: some/every $var in expr (, $var in expr)* satisfies expr
     */
    private Expr parseQuantifiedExpr(QuantifiedExpr.Quantifier quantifier) 
            throws XPathSyntaxException {
        lexer.advance(); // consume SOME or EVERY
        
        List<QuantifiedExpr.Binding> bindings = new ArrayList<>();
        
        do {
            // Parse $varname
            lexer.expect(XPathToken.DOLLAR);
            if (lexer.current() != XPathToken.NCNAME) {
                throw new XPathSyntaxException("Expected variable name",
                    lexer.getExpression(), lexer.tokenStart());
            }
            String varName = lexer.value();
            lexer.advance();
            
            lexer.expect(XPathToken.IN);
            
            // Parse sequence expression
            Expr sequence = parseSubExpression();
            
            bindings.add(new QuantifiedExpr.Binding(varName, sequence));
            
        } while (lexer.match(XPathToken.COMMA));
        
        lexer.expect(XPathToken.SATISFIES);
        
        // Parse satisfies expression
        Expr satisfiesExpr = parseSubExpression();
        
        return new QuantifiedExpr(quantifier, bindings, satisfiesExpr);
    }

    /**
     * Parses a sub-expression (complete expression within a larger construct).
     * Uses a fresh parse context.
     */
    private Expr parseSubExpression() throws XPathSyntaxException {
        Deque<ParseContext> contextStack = new ArrayDeque<>();
        ParseContext ctx = new ParseContext(ContextType.TOP_LEVEL);

        while (true) {
            switch (ctx.state) {
                case NEED_OPERAND:
                    ctx = parseOperand(ctx, contextStack);
                    break;

                case HAVE_OPERAND:
                    // Check for terminating tokens that end a sub-expression
                    XPathToken t = lexer.current();
                    if (t == XPathToken.RPAREN || t == XPathToken.RBRACKET ||
                        t == XPathToken.COMMA || t == XPathToken.THEN ||
                        t == XPathToken.ELSE || t == XPathToken.RETURN ||
                        t == XPathToken.IN || t == XPathToken.SATISFIES ||
                        t == XPathToken.EOF) {
                        // Reduce all remaining operators
                        while (!ctx.opStack.isEmpty()) {
                            reduce(ctx);
                        }
                        return ctx.exprStack.pop();
                    }
                    
                    ParseResult parseResult = processOperatorOrEnd(ctx, contextStack);
                    if (parseResult.done) {
                        return parseResult.result;
                    }
                    ctx = parseResult.context;
                    break;

                case BUILDING_FUNCTION:
                    ctx = startFunctionArgs(ctx, contextStack);
                    break;

                case BUILDING_FILTER:
                    ctx = startPredicate(ctx, contextStack);
                    break;

                case PATH_CONTINUATION:
                    ctx = handlePathContinuation(ctx);
                    break;

                default:
                    throw new IllegalStateException("Unknown parse state: " + ctx.state);
            }
        }
    }

    /**
     * Handles the PATH_CONTINUATION state - checks for predicates and path continuation.
     */
    private ParseContext handlePathContinuation(ParseContext ctx) throws XPathSyntaxException {
        // Check for predicates
        if (lexer.current() == XPathToken.LBRACKET) {
            // Use a separate list for predicates to avoid mixing with function arguments
            ctx.predicateItems = new ArrayList<>();
            ctx.state = ParseState.BUILDING_FILTER;
            return ctx;
        }

        // Check for path continuation
        if (lexer.current() == XPathToken.SLASH || lexer.current() == XPathToken.DOUBLE_SLASH) {
            LocationPath path = parseRelativeLocationPath();
            Expr pathExpr = new PathExpr(ctx.filterBase, path);
            finishOperand(ctx, pathExpr);
            return ctx;
        }

        // No predicates or path - filter base is the operand
        finishOperand(ctx, ctx.filterBase);
        return ctx;
    }

    /**
     * Finishes an operand by applying unary negation and updating state.
     */
    private void finishOperand(ParseContext ctx, Expr operand) {
        if (ctx.negationCount > 0) {
            operand = new UnaryExpr(operand, ctx.negationCount);
            ctx.negationCount = 0;
        }
        ctx.exprStack.push(operand);
        ctx.state = ParseState.HAVE_OPERAND;
    }

    /**
     * Processes operator or context end after having an operand.
     * Returns ParseResult indicating whether parsing is complete.
     */
    private ParseResult processOperatorOrEnd(ParseContext ctx, Deque<ParseContext> contextStack) 
            throws XPathSyntaxException {
        Operator op = Operator.fromToken(lexer.current());

        if (op != null) {
            // We have an operator - use Pratt parsing
            int prec = op.getPrecedence();
            while (!ctx.opStack.isEmpty() && ctx.opStack.peek().precedence >= prec) {
                reduce(ctx);
            }
            ctx.opStack.push(new StackEntry(op, prec));
            lexer.advance();
            ctx.state = ParseState.NEED_OPERAND;
            return ParseResult.continueWith(ctx);
        }

        // No operator - reduce remaining operators and complete this context
        while (!ctx.opStack.isEmpty()) {
            reduce(ctx);
        }

        Expr result = ctx.exprStack.pop();

        // Handle context completion based on type
        switch (ctx.type) {
            case TOP_LEVEL:
                return ParseResult.finished(result);

            case FUNCTION_ARG:
                return ParseResult.continueWith(completeFunctionArg(ctx, contextStack, result));

            case PREDICATE:
                return ParseResult.continueWith(completePredicate(ctx, contextStack, result));

            case PARENTHESIZED:
                return ParseResult.continueWith(completeParenthesized(ctx, contextStack, result));

            case SEQUENCE:
                return ParseResult.continueWith(completeSequenceItem(ctx, contextStack, result));

            default:
                throw new IllegalStateException("Unknown context type: " + ctx.type);
        }
    }

    // ========================================================================
    // Context Starters
    // ========================================================================

    /**
     * Starts parsing function call arguments.
     * 
     * <p>When parsing a nested function call (e.g., string(number(.)) inside contains()),
     * we must create a new context for the nested function rather than reusing the
     * current argument context, to avoid overwriting the parent function's argument list.
     */
    private ParseContext startFunctionCall(ParseContext ctx, Deque<ParseContext> contextStack) 
            throws XPathSyntaxException {
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

        // Check for empty argument list
        if (lexer.current() == XPathToken.RPAREN) {
            lexer.advance();
            Expr func = new FunctionCall(prefix, localName, new ArrayList<>());
            ctx.filterBase = func;
            ctx.state = ParseState.PATH_CONTINUATION;
            return ctx;
        }

        // For nested function calls (when ctx is already parsing an argument),
        // we need to push the current context and create a new one for this function
        if (ctx.type == ContextType.FUNCTION_ARG || ctx.type == ContextType.PREDICATE 
                || ctx.type == ContextType.PARENTHESIZED || ctx.type == ContextType.SEQUENCE) {
            // Save current context - we'll return to it after the function is complete
            contextStack.push(ctx);
            
            // Create new context for this function call
            ParseContext funcCtx = new ParseContext(ContextType.TOP_LEVEL);
            funcCtx.functionPrefix = prefix;
            funcCtx.functionName = localName;
            funcCtx.items = new ArrayList<>();
            funcCtx.state = ParseState.BUILDING_FUNCTION;
            funcCtx.nestedInExpression = true;  // Mark as nested
            return funcCtx;
        }

        // Top-level function call - use current context
        ctx.functionPrefix = prefix;
        ctx.functionName = localName;
        ctx.items = new ArrayList<>();
        ctx.state = ParseState.BUILDING_FUNCTION;
        return ctx;
    }

    /**
     * Starts parsing function arguments (pushes new context).
     */
    private ParseContext startFunctionArgs(ParseContext ctx, Deque<ParseContext> contextStack) 
            throws XPathSyntaxException {
        contextStack.push(ctx);
        ParseContext argCtx = new ParseContext(ContextType.FUNCTION_ARG);
        argCtx.items = ctx.items;
        return argCtx;
    }

    /**
     * Starts parsing a predicate (pushes new context).
     */
    private ParseContext startPredicate(ParseContext ctx, Deque<ParseContext> contextStack) 
            throws XPathSyntaxException {
        lexer.expect(XPathToken.LBRACKET);
        contextStack.push(ctx);
        ParseContext predCtx = new ParseContext(ContextType.PREDICATE);
        // Use predicateItems for predicates, separate from function args
        predCtx.predicateItems = ctx.predicateItems;
        return predCtx;
    }

    /**
     * Starts parsing a parenthesized expression or sequence.
     */
    private ParseContext startParenthesizedExpr(ParseContext ctx, Deque<ParseContext> contextStack) 
            throws XPathSyntaxException {
        lexer.expect(XPathToken.LPAREN);

        // Check for empty sequence ()
        if (lexer.current() == XPathToken.RPAREN) {
            lexer.advance();
            Expr empty = new SequenceExpr(new ArrayList<>());
            ctx.filterBase = empty;
            ctx.state = ParseState.PATH_CONTINUATION;
            return ctx;
        }

        // Push current context and start parsing the expression
        contextStack.push(ctx);
        ParseContext parenCtx = new ParseContext(ContextType.PARENTHESIZED);
        return parenCtx;
    }

    // ========================================================================
    // Context Completers
    // ========================================================================

    /**
     * Completes a function argument.
     */
    private ParseContext completeFunctionArg(ParseContext ctx, Deque<ParseContext> contextStack, Expr result) 
            throws XPathSyntaxException {
        ctx.items.add(result);

        // Check for more arguments
        if (lexer.current() == XPathToken.COMMA) {
            lexer.advance();
            // Continue with next argument in a fresh context
            ParseContext argCtx = new ParseContext(ContextType.FUNCTION_ARG);
            argCtx.items = ctx.items;
            return argCtx;
        }

        // End of arguments
        lexer.expect(XPathToken.RPAREN);

        // Pop back to parent context
        ParseContext parent = contextStack.pop();
        Expr func = new FunctionCall(parent.functionPrefix, parent.functionName, ctx.items);
        
        // Check if this was a nested function call within another expression
        if (parent.nestedInExpression) {
            // Pop the original expression context and return the function as its operand
            ParseContext exprCtx = contextStack.pop();
            exprCtx.filterBase = func;
            exprCtx.state = ParseState.PATH_CONTINUATION;
            return exprCtx;
        }
        
        parent.filterBase = func;
        parent.state = ParseState.PATH_CONTINUATION;
        return parent;
    }

    /**
     * Completes a predicate.
     */
    private ParseContext completePredicate(ParseContext ctx, Deque<ParseContext> contextStack, Expr result) 
            throws XPathSyntaxException {
        ctx.predicateItems.add(result);
        lexer.expect(XPathToken.RBRACKET);

        // Pop back to parent context
        ParseContext parent = contextStack.pop();

        // Check for more predicates
        if (lexer.current() == XPathToken.LBRACKET) {
            parent.state = ParseState.BUILDING_FILTER;
            return parent;
        }

        // Create filter expression using predicateItems
        Expr filter = new FilterExpr(parent.filterBase, ctx.predicateItems);
        parent.filterBase = filter;
        parent.state = ParseState.PATH_CONTINUATION;
        return parent;
    }

    /**
     * Completes a parenthesized expression.
     */
    private ParseContext completeParenthesized(ParseContext ctx, Deque<ParseContext> contextStack, Expr result) 
            throws XPathSyntaxException {
        // Check for comma - if present, it's a sequence
        if (lexer.current() == XPathToken.COMMA) {
            ctx.items = new ArrayList<>();
            ctx.items.add(result);
            lexer.advance();
            // Convert to sequence context
            ParseContext seqCtx = new ParseContext(ContextType.SEQUENCE);
            seqCtx.items = ctx.items;
            return seqCtx;
        }

        lexer.expect(XPathToken.RPAREN);

        // Pop back to parent context
        ParseContext parent = contextStack.pop();
        parent.filterBase = result;
        parent.state = ParseState.PATH_CONTINUATION;
        return parent;
    }

    /**
     * Completes a sequence item.
     */
    private ParseContext completeSequenceItem(ParseContext ctx, Deque<ParseContext> contextStack, Expr result) 
            throws XPathSyntaxException {
        ctx.items.add(result);

        // Check for more items
        if (lexer.current() == XPathToken.COMMA) {
            lexer.advance();
            ParseContext seqCtx = new ParseContext(ContextType.SEQUENCE);
            seqCtx.items = ctx.items;
            return seqCtx;
        }

        lexer.expect(XPathToken.RPAREN);

        // Pop back to parent context
        ParseContext parent = contextStack.pop();
        Expr seq = new SequenceExpr(ctx.items);
        parent.filterBase = seq;
        parent.state = ParseState.PATH_CONTINUATION;
        return parent;
    }

    // ========================================================================
    // Helper Methods
    // ========================================================================

    /**
     * Reduces the top operator on the context's stacks.
     */
    private void reduce(ParseContext ctx) {
        StackEntry entry = ctx.opStack.pop();
        Expr right = ctx.exprStack.pop();
        Expr left = ctx.exprStack.pop();
        ctx.exprStack.push(new BinaryExpr(entry.operator, left, right));
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
            // Check for prefixed function call: prefix:name(
            if (next == XPathToken.COLON) {
                if (isPrefixedFunctionCall()) {
                    return false;  // It's a function call, not a location path
                }
                return true;
            }
            // No paren or colon follows - it's an element name (step)
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
     * Checks if the current position starts a prefixed function call: prefix:name(
     */
    private boolean isPrefixedFunctionCall() {
        // Save lexer state
        int savedPos = lexer.getPosition();
        XPathToken savedCurrent = lexer.current();
        String savedValue = lexer.value();
        int savedStart = lexer.tokenStart();
        
        try {
            if (lexer.current() != XPathToken.NCNAME) {
                return false;
            }
            lexer.advance();
            
            if (lexer.current() != XPathToken.COLON) {
                return false;
            }
            lexer.advance();
            
            if (lexer.current() != XPathToken.NCNAME) {
                return false;
            }
            lexer.advance();
            
            return lexer.current() == XPathToken.LPAREN;
        } finally {
            lexer.restore(savedPos, savedCurrent, savedValue, savedStart);
        }
    }

    /**
     * Parses a variable reference ($name). This is leaf-level, no nested expressions.
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
     * Parses a location path (absolute or relative). This is leaf-level for expressions.
     * Predicates within steps are parsed inline since they're step-local.
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
            steps.add(new Step(Step.Axis.DESCENDANT_OR_SELF, Step.NodeTestType.NODE));
        }

        // Parse relative location path steps
        parseRelativeSteps(steps);

        return new LocationPath(absolute, steps);
    }

    /**
     * Parses a relative location path (returns the path itself).
     */
    private LocationPath parseRelativeLocationPath() throws XPathSyntaxException {
        List<Step> steps = new ArrayList<>();

        if (lexer.current() == XPathToken.SLASH) {
            lexer.advance();
        } else if (lexer.current() == XPathToken.DOUBLE_SLASH) {
            lexer.advance();
            steps.add(new Step(Step.Axis.DESCENDANT_OR_SELF, Step.NodeTestType.NODE));
        }

        parseRelativeSteps(steps);
        return new LocationPath(false, steps);
    }

    /**
     * Parses the steps of a relative location path.
     * Predicates are parsed inline using a local iterative approach.
     */
    private void parseRelativeSteps(List<Step> steps) throws XPathSyntaxException {
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
    }

    /**
     * Parses a single step with its predicates.
     * Predicates within steps use a local iterative approach since they're step-local.
     */
    private Step parseStep() throws XPathSyntaxException {
        // Handle abbreviations first
        if (lexer.current() == XPathToken.DOT) {
            lexer.advance();
            Step step = new Step(Step.Axis.SELF, Step.NodeTestType.NODE);
            return parseStepPredicates(step);
        }

        if (lexer.current() == XPathToken.DOUBLE_DOT) {
            lexer.advance();
            Step step = new Step(Step.Axis.PARENT, Step.NodeTestType.NODE);
            return parseStepPredicates(step);
        }

        // Determine axis
        Step.Axis axis = Step.Axis.CHILD;

        if (lexer.current() == XPathToken.AT) {
            axis = Step.Axis.ATTRIBUTE;
            lexer.advance();
        } else if (lexer.current().isAxis()) {
            axis = tokenToAxis(lexer.current());
            lexer.advance();
        }

        // Parse node test
        Step step = parseNodeTest(axis);

        // Parse predicates
        return parseStepPredicates(step);
    }

    /**
     * Parses predicates for a step using iterative approach.
     * Step predicates don't need the full context stack since they can't
     * contain function calls with arguments that would nest further.
     * Actually, they CAN contain arbitrary expressions, so we need to use
     * a local iterative sub-parser here.
     */
    private Step parseStepPredicates(Step step) throws XPathSyntaxException {
        List<Expr> predicates = null;
        
        while (lexer.current() == XPathToken.LBRACKET) {
            if (predicates == null) {
                predicates = new ArrayList<>();
            }
            lexer.advance(); // consume [
            
            // Parse predicate expression using iterative approach
            Expr predExpr = parsePredicateExpr();
            predicates.add(predExpr);
            
            lexer.expect(XPathToken.RBRACKET);
        }

        if (predicates != null && !predicates.isEmpty()) {
            return step.withPredicates(predicates);
        }

        return step;
    }

    /**
     * Parses a predicate expression iteratively.
     * This creates a fresh parsing context and runs to completion.
     */
    private Expr parsePredicateExpr() throws XPathSyntaxException {
        // Create a fresh parse context for the predicate
        Deque<ParseContext> contextStack = new ArrayDeque<>();
        ParseContext ctx = new ParseContext(ContextType.TOP_LEVEL);

        // Run the iterative parsing loop
        while (true) {
            switch (ctx.state) {
                case NEED_OPERAND:
                    ctx = parseOperand(ctx, contextStack);
                    break;

                case HAVE_OPERAND:
                    ParseResult parseResult = processOperatorOrEnd(ctx, contextStack);
                    if (parseResult.done) {
                        // We're done - return the result
                        // Note: don't consume RBRACKET, caller does that
                        return parseResult.result;
                    }
                    ctx = parseResult.context;
                    break;

                case BUILDING_FUNCTION:
                    ctx = startFunctionArgs(ctx, contextStack);
                    break;

                case BUILDING_FILTER:
                    ctx = startPredicate(ctx, contextStack);
                    break;

                case PATH_CONTINUATION:
                    ctx = handlePathContinuation(ctx);
                    break;

                default:
                    throw new IllegalStateException("Unknown parse state: " + ctx.state);
            }
        }
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

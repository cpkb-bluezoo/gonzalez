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

import org.bluezoo.gonzalez.QName;
import org.bluezoo.gonzalez.transform.xpath.expr.BinaryExpr;
import org.bluezoo.gonzalez.transform.xpath.expr.ContextItemExpr;
import org.bluezoo.gonzalez.transform.xpath.expr.DynamicFunctionCallExpr;
import org.bluezoo.gonzalez.transform.xpath.expr.Expr;
import org.bluezoo.gonzalez.transform.xpath.expr.FilterExpr;
import org.bluezoo.gonzalez.transform.xpath.expr.ForExpr;
import org.bluezoo.gonzalez.transform.xpath.expr.FunctionCall;
import org.bluezoo.gonzalez.transform.xpath.expr.IfExpr;
import org.bluezoo.gonzalez.transform.xpath.expr.LetExpr;
import org.bluezoo.gonzalez.transform.xpath.expr.Literal;
import org.bluezoo.gonzalez.transform.xpath.expr.LocationPath;
import org.bluezoo.gonzalez.transform.xpath.expr.ArgumentPlaceholder;
import org.bluezoo.gonzalez.transform.xpath.expr.InlineFunctionExpr;
import org.bluezoo.gonzalez.transform.xpath.expr.ArrayConstructorExpr;
import org.bluezoo.gonzalez.transform.xpath.expr.LookupExpr;
import org.bluezoo.gonzalez.transform.xpath.expr.MapConstructorExpr;
import org.bluezoo.gonzalez.transform.xpath.expr.NamedFunctionRefExpr;
import org.bluezoo.gonzalez.transform.xpath.expr.Operator;
import org.bluezoo.gonzalez.transform.xpath.expr.PathExpr;
import org.bluezoo.gonzalez.transform.xpath.expr.QuantifiedExpr;
import org.bluezoo.gonzalez.transform.xpath.expr.SequenceExpr;
import org.bluezoo.gonzalez.transform.xpath.expr.Step;
import org.bluezoo.gonzalez.transform.xpath.expr.TypeExpr;
import org.bluezoo.gonzalez.transform.xpath.expr.UnaryExpr;
import org.bluezoo.gonzalez.transform.xpath.expr.VariableReference;
import org.bluezoo.gonzalez.transform.xpath.expr.XPathException;
import org.bluezoo.gonzalez.transform.xpath.type.SequenceType;

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
    public interface NamespaceResolver {
        /**
         * Resolves a namespace prefix to a URI.
         *
         * @param prefix the prefix
         * @return the namespace URI, or null if not found
         */
        String resolve(String prefix);
        
        /**
         * Returns the default namespace for unprefixed element names in XPath expressions.
         * This is set by the xpath-default-namespace attribute in XSLT 2.0+.
         *
         * @return the default element namespace URI, or null for no namespace
         */
        default String getDefaultElementNamespace() {
            return null;
        }
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
        String functionResolvedURI;
        
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
        // Handle unary operators: minus (negate) and plus (no-op)
        ctx.negationCount = 0;
        while (lexer.current() == XPathToken.MINUS || lexer.current() == XPathToken.PLUS) {
            if (lexer.current() == XPathToken.MINUS) {
                ctx.negationCount++;
            }
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

            case DOT: {
                // .(args) - context item used as a function
                lexer.advance();
                ctx.filterBase = new ContextItemExpr();
                ctx.state = ParseState.PATH_CONTINUATION;
                return ctx;
            }

            case LBRACKET: {
                // XPath 3.1 square-bracket array constructor: [expr1, expr2, ...]
                Expr arrayExpr = parseArrayConstructor();
                ctx.filterBase = arrayExpr;
                ctx.state = ParseState.PATH_CONTINUATION;
                return ctx;
            }

            case QUESTION: {
                // Check what follows '?' to disambiguate:
                // ?* or ?name or ?N → unary lookup (shorthand for .?...)
                // ? alone (followed by , or ) or end) → argument placeholder
                XPathToken afterQ = lexer.peek();
                if (afterQ == XPathToken.STAR || afterQ == XPathToken.NCNAME
                        || afterQ == XPathToken.NUMBER_LITERAL || isNCNameOrKeyword()) {
                    Expr lookupExpr = parseLookup(new ContextItemExpr());
                    ctx.filterBase = lookupExpr;
                    ctx.state = ParseState.PATH_CONTINUATION;
                    return ctx;
                }
                // Argument placeholder for partial application
                lexer.advance(); // consume '?'
                ctx.filterBase = ArgumentPlaceholder.INSTANCE;
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
                // Could be inline function, function call, map/array constructor, or named function ref
                XPathToken next = lexer.peek();
                if (next == XPathToken.LPAREN && "function".equals(lexer.value())) {
                    Expr inlineFunc = parseInlineFunctionExpr();
                    ctx.filterBase = inlineFunc;
                    ctx.state = ParseState.PATH_CONTINUATION;
                    return ctx;
                }
                if (next == XPathToken.LBRACE && "map".equals(lexer.value())) {
                    Expr mapExpr = parseMapConstructor();
                    ctx.filterBase = mapExpr;
                    ctx.state = ParseState.PATH_CONTINUATION;
                    return ctx;
                }
                if (next == XPathToken.LBRACE && "array".equals(lexer.value())) {
                    Expr arrayExpr = parseCurlyArrayConstructor();
                    ctx.filterBase = arrayExpr;
                    ctx.state = ParseState.PATH_CONTINUATION;
                    return ctx;
                }
                if (next == XPathToken.HASH) {
                    Expr funcRef = parseNamedFunctionRef();
                    ctx.filterBase = funcRef;
                    ctx.state = ParseState.PATH_CONTINUATION;
                    return ctx;
                }
                if (next == XPathToken.LPAREN) {
                    return startFunctionCall(ctx, contextStack);
                }
                if (next == XPathToken.COLON) {
                    if (isPrefixedFunctionRef()) {
                        Expr funcRef = parseNamedFunctionRef();
                        ctx.filterBase = funcRef;
                        ctx.state = ParseState.PATH_CONTINUATION;
                        return ctx;
                    }
                    if (isPrefixedFunctionCall()) {
                        return startFunctionCall(ctx, contextStack);
                    }
                }
                // Fall through - not a primary expression
                break;

            case URIQNAME: {
                // XPath 3.0 URIQualifiedName: Q{uri}local
                // Could be function call Q{uri}local(...) or function ref Q{uri}local#N
                XPathToken uriNext = lexer.peek();
                if (uriNext == XPathToken.LPAREN) {
                    return startURIQualifiedFunctionCall(ctx, contextStack);
                }
                if (uriNext == XPathToken.HASH) {
                    Expr funcRef = parseURIQualifiedFunctionRef();
                    ctx.filterBase = funcRef;
                    ctx.state = ParseState.PATH_CONTINUATION;
                    return ctx;
                }
                // Fall through - not a primary expression
                break;
            }

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
        // Use expectKeyword for THEN/ELSE since they may be tokenized as NCNAME
        // when following certain expressions (e.g., function calls, predicates)
        lexer.expectKeyword(XPathToken.THEN, "then");
        
        // Parse then branch
        Expr thenExpr = parseSubExpression();
        
        // ELSE may be tokenized as NCNAME when following literals like 'then "value"'
        lexer.expectKeyword(XPathToken.ELSE, "else");
        
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
     * Parses an XPath 3.1 inline function expression:
     * {@code function($p1, $p2) { body }} or
     * {@code function($p1 as xs:string) as xs:integer { body }}.
     * Type annotations are parsed and ignored.
     */
    private Expr parseInlineFunctionExpr() throws XPathSyntaxException {
        lexer.expect(XPathToken.NCNAME); // consume 'function'
        lexer.expect(XPathToken.LPAREN);

        List<String> paramNames = new ArrayList<String>();

        if (lexer.current() != XPathToken.RPAREN) {
            // Parse first parameter
            lexer.expect(XPathToken.DOLLAR);
            String paramName = lexer.value();
            lexer.expect(XPathToken.NCNAME);
            paramNames.add(paramName);

            // Skip optional type annotation: "as <SequenceType>"
            skipParamTypeAnnotation();

            // Parse additional parameters
            while (lexer.match(XPathToken.COMMA)) {
                lexer.expect(XPathToken.DOLLAR);
                paramName = lexer.value();
                lexer.expect(XPathToken.NCNAME);
                paramNames.add(paramName);
                skipParamTypeAnnotation();
            }
        }

        lexer.expect(XPathToken.RPAREN);

        // Skip optional return type annotation: "as <SequenceType>"
        if (lexer.current() == XPathToken.NCNAME && "as".equals(lexer.value())) {
            lexer.advance(); // consume 'as'
            skipSequenceType();
        }

        lexer.expect(XPathToken.LBRACE);

        Expr body;
        if (lexer.current() == XPathToken.RBRACE) {
            body = Literal.string("");
        } else {
            body = parseSubExpression();
        }

        lexer.expect(XPathToken.RBRACE);

        return new InlineFunctionExpr(paramNames, body);
    }

    /**
     * Skips an optional "as SequenceType" annotation on a function parameter.
     */
    private void skipParamTypeAnnotation() throws XPathSyntaxException {
        if (lexer.current() == XPathToken.NCNAME && "as".equals(lexer.value())) {
            lexer.advance(); // consume 'as'
            skipSequenceType();
        }
    }

    /**
     * Skips a sequence type specification (e.g. xs:string, xs:integer*, item()+, etc.)
     * Used to consume type annotations that we parse but do not enforce.
     */
    private void skipSequenceType() throws XPathSyntaxException {
        // Handle "empty-sequence()"
        if (lexer.current() == XPathToken.NCNAME && "empty-sequence".equals(lexer.value())) {
            lexer.advance();
            if (lexer.current() == XPathToken.LPAREN) {
                lexer.advance();
                lexer.expect(XPathToken.RPAREN);
            }
            return;
        }

        // Skip the item type: NCName (possibly prefixed), or node()/item()/element() etc.
        if (lexer.current() == XPathToken.NCNAME) {
            lexer.advance(); // consume first name
            if (lexer.match(XPathToken.COLON)) {
                // Prefixed type like xs:string
                if (lexer.current() == XPathToken.NCNAME) {
                    lexer.advance();
                }
            }
            // Handle function type: function(...) as ...
            if (lexer.current() == XPathToken.LPAREN) {
                int depth = 1;
                lexer.advance();
                while (depth > 0 && lexer.current() != XPathToken.EOF) {
                    if (lexer.current() == XPathToken.LPAREN) {
                        depth++;
                    } else if (lexer.current() == XPathToken.RPAREN) {
                        depth--;
                    }
                    lexer.advance();
                }
            }
        } else if (lexer.current() == XPathToken.URIQNAME) {
            lexer.advance();
        }

        // Skip occurrence indicator: ?, *, +
        if (lexer.current() == XPathToken.QUESTION || lexer.current() == XPathToken.STAR) {
            lexer.advance();
        } else if (lexer.current() == XPathToken.PLUS) {
            lexer.advance();
        }
    }

    /**
     * Parses an XPath 3.1 map constructor: map { key : value, ... }
     */
    private Expr parseMapConstructor() throws XPathSyntaxException {
        lexer.expect(XPathToken.NCNAME);
        lexer.expect(XPathToken.LBRACE);

        List<Expr> keyExprs = new ArrayList<Expr>();
        List<Expr> valueExprs = new ArrayList<Expr>();

        if (lexer.current() != XPathToken.RBRACE) {
            Expr key = parseSubExpression();
            lexer.expect(XPathToken.COLON);
            Expr value = parseSubExpression();
            keyExprs.add(key);
            valueExprs.add(value);

            while (lexer.match(XPathToken.COMMA)) {
                key = parseSubExpression();
                lexer.expect(XPathToken.COLON);
                value = parseSubExpression();
                keyExprs.add(key);
                valueExprs.add(value);
            }
        }

        lexer.expect(XPathToken.RBRACE);
        return new MapConstructorExpr(keyExprs, valueExprs);
    }

    /**
     * Parses an XPath 3.1 square-bracket array constructor: [expr1, expr2, ...]
     */
    private Expr parseArrayConstructor() throws XPathSyntaxException {
        lexer.expect(XPathToken.LBRACKET);

        List<Expr> members = new ArrayList<>();

        if (lexer.current() != XPathToken.RBRACKET) {
            members.add(parseSubExpression());

            while (lexer.match(XPathToken.COMMA)) {
                members.add(parseSubExpression());
            }
        }

        lexer.expect(XPathToken.RBRACKET);
        return new ArrayConstructorExpr(members);
    }

    /**
     * Parses an XPath 3.1 curly-brace array constructor: array { expr1, expr2, ... }
     * Unlike [a, b, c] which creates one member per expression, array{} wraps
     * the single expression result into array members.
     */
    private Expr parseCurlyArrayConstructor() throws XPathSyntaxException {
        lexer.expect(XPathToken.NCNAME); // consume 'array'
        lexer.expect(XPathToken.LBRACE);

        List<Expr> members = new ArrayList<>();

        if (lexer.current() != XPathToken.RBRACE) {
            members.add(parseSubExpression());

            while (lexer.match(XPathToken.COMMA)) {
                members.add(parseSubExpression());
            }
        }

        lexer.expect(XPathToken.RBRACE);
        return new ArrayConstructorExpr(members);
    }

    /**
     * Checks if the base expression is in a valid context for dynamic function call.
     * XPath 3.1: PostfixExpr ::= PrimaryExpr (Predicate | ArgumentList | Lookup)*
     * Any primary/postfix expression can be followed by (args).
     */
    private boolean isDynamicCallContext(Expr base) {
        if (base instanceof Literal) {
            return false;
        }
        return true;
    }

    /**
     * Parses a dynamic function call: base(args).
     */
    private Expr parseDynamicFunctionCall(Expr base) throws XPathSyntaxException {
        lexer.expect(XPathToken.LPAREN);
        List<Expr> args = new ArrayList<Expr>();
        if (lexer.current() != XPathToken.RPAREN) {
            args.add(parseSubExpression());
            while (lexer.match(XPathToken.COMMA)) {
                args.add(parseSubExpression());
            }
        }
        lexer.expect(XPathToken.RPAREN);
        return new DynamicFunctionCallExpr(base, args);
    }

    /**
     * Parses a lookup specifier after '?': key name, wildcard, or integer.
     */
    private Expr parseLookup(Expr base) throws XPathSyntaxException {
        lexer.expect(XPathToken.QUESTION);
        XPathToken t = lexer.current();
        if (t == XPathToken.STAR) {
            lexer.advance();
            return new LookupExpr(base, true);
        }
        if (t == XPathToken.NCNAME || isNCNameOrKeyword()) {
            String key = lexer.value();
            lexer.advance();
            return new LookupExpr(base, key);
        }
        if (t == XPathToken.NUMBER_LITERAL) {
            String key = lexer.value();
            lexer.advance();
            return new LookupExpr(base, key);
        }
        throw new XPathSyntaxException("Expected key specifier after '?'",
            lexer.getExpression(), lexer.tokenStart());
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
                    // Note: We only terminate on COMMA at the top-level; inside parenthesized
                    // expressions, comma is handled by the context machinery to build sequences
                    XPathToken t = lexer.current();
                    boolean isTerminator = false;
                    
                    if (t == XPathToken.THEN || t == XPathToken.ELSE || 
                        t == XPathToken.RETURN || t == XPathToken.IN || 
                        t == XPathToken.SATISFIES || t == XPathToken.EOF) {
                        // These always terminate
                        isTerminator = true;
                    } else if (t == XPathToken.RPAREN || t == XPathToken.RBRACKET || 
                               t == XPathToken.COMMA) {
                        // These only terminate at top-level (when context stack is empty)
                        // Inside parenthesized/sequence contexts, these are handled by
                        // processOperatorOrEnd -> completeParenthesized/completeSequenceItem
                        isTerminator = contextStack.isEmpty();
                    }
                    
                    if (isTerminator) {
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
            ctx.predicateItems = new ArrayList<>();
            ctx.state = ParseState.BUILDING_FILTER;
            return ctx;
        }

        // Check for lookup operator (XPath 3.1): expr?key, expr?*, expr?N
        if (lexer.current() == XPathToken.QUESTION) {
            Expr lookupExpr = parseLookup(ctx.filterBase);
            ctx.filterBase = lookupExpr;
            return ctx;
        }

        // Check for dynamic function call (XPath 3.1): expr(args)
        if (lexer.current() == XPathToken.LPAREN && isDynamicCallContext(ctx.filterBase)) {
            Expr callExpr = parseDynamicFunctionCall(ctx.filterBase);
            ctx.filterBase = callExpr;
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
        
        // Check for type expression keywords first
        TypeExpr.Kind typeKind = getTypeExprKind(lexer.current());
        if (typeKind != null) {
            // Reduce any higher-precedence operators first
            Operator typeOp = getTypeOperator(typeKind);
            int prec = typeOp.getPrecedence();
            while (!ctx.opStack.isEmpty() && ctx.opStack.peek().precedence >= prec) {
                reduce(ctx);
            }
            
            // Parse the type expression
            Expr operand = ctx.exprStack.pop();
            Expr typeExpr = parseTypeExpr(typeKind, operand);
            ctx.exprStack.push(typeExpr);
            ctx.state = ParseState.HAVE_OPERAND;
            return ParseResult.continueWith(ctx);
        }
        
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
                // Check for comma - top-level sequence expression (XPath 2.0+)
                if (lexer.current() == XPathToken.COMMA) {
                    List<Expr> items = new ArrayList<Expr>();
                    items.add(result);
                    lexer.advance();
                    // Create a sequence context (no parent on stack = top-level sequence)
                    ParseContext seqCtx = new ParseContext(ContextType.SEQUENCE);
                    seqCtx.items = items;
                    return ParseResult.continueWith(seqCtx);
                }
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

        // Resolve namespace prefix at compile time if possible
        String resolvedURI = null;
        if (prefix != null && namespaceResolver != null) {
            resolvedURI = namespaceResolver.resolve(prefix);
        }

        // Check for empty argument list
        if (lexer.current() == XPathToken.RPAREN) {
            lexer.advance();
            Expr func = new FunctionCall(prefix, localName, resolvedURI, new ArrayList<>());
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
            funcCtx.functionResolvedURI = resolvedURI;
            funcCtx.items = new ArrayList<>();
            funcCtx.state = ParseState.BUILDING_FUNCTION;
            funcCtx.nestedInExpression = true;  // Mark as nested
            return funcCtx;
        }

        // Top-level function call - use current context
        ctx.functionPrefix = prefix;
        ctx.functionName = localName;
        ctx.functionResolvedURI = resolvedURI;
        ctx.items = new ArrayList<>();
        ctx.state = ParseState.BUILDING_FUNCTION;
        return ctx;
    }

    /**
     * Starts a function call from a URIQualifiedName token: Q{uri}local(args).
     * The value is in Clark notation "{uri}local".
     */
    private ParseContext startURIQualifiedFunctionCall(ParseContext ctx,
            Deque<ParseContext> contextStack) throws XPathSyntaxException {
        String clark = lexer.value();
        lexer.advance(); // consume URIQNAME

        // Parse Clark notation: {uri}local
        int closeBrace = clark.indexOf('}');
        String uri = clark.substring(1, closeBrace);
        String localName = clark.substring(closeBrace + 1);

        lexer.expect(XPathToken.LPAREN);

        // Check for empty argument list
        if (lexer.current() == XPathToken.RPAREN) {
            lexer.advance();
            Expr func = new FunctionCall(null, localName, uri, new ArrayList<>());
            ctx.filterBase = func;
            ctx.state = ParseState.PATH_CONTINUATION;
            return ctx;
        }

        if (ctx.type == ContextType.FUNCTION_ARG || ctx.type == ContextType.PREDICATE
                || ctx.type == ContextType.PARENTHESIZED || ctx.type == ContextType.SEQUENCE) {
            contextStack.push(ctx);
            ParseContext funcCtx = new ParseContext(ContextType.TOP_LEVEL);
            funcCtx.functionPrefix = null;
            funcCtx.functionName = localName;
            funcCtx.functionResolvedURI = uri;
            funcCtx.items = new ArrayList<>();
            funcCtx.state = ParseState.BUILDING_FUNCTION;
            funcCtx.nestedInExpression = true;
            return funcCtx;
        }

        ctx.functionPrefix = null;
        ctx.functionName = localName;
        ctx.functionResolvedURI = uri;
        ctx.items = new ArrayList<>();
        ctx.state = ParseState.BUILDING_FUNCTION;
        return ctx;
    }

    /**
     * Parses a function reference from a URIQualifiedName: Q{uri}local#arity.
     */
    private Expr parseURIQualifiedFunctionRef() throws XPathSyntaxException {
        String clark = lexer.value();
        lexer.advance(); // consume URIQNAME

        int closeBrace = clark.indexOf('}');
        String uri = clark.substring(1, closeBrace);
        String localName = clark.substring(closeBrace + 1);

        lexer.expect(XPathToken.HASH);
        String arityStr = lexer.value();
        lexer.expect(XPathToken.NUMBER_LITERAL);
        int arity = Integer.parseInt(arityStr);

        return new NamedFunctionRefExpr(null, localName, uri, arity);
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
        Expr func = new FunctionCall(parent.functionPrefix, parent.functionName,
            parent.functionResolvedURI, ctx.items);
        
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

        // Check if this is a top-level sequence (no parent context)
        if (contextStack.isEmpty()) {
            // Top-level sequence - return finished with SequenceExpr
            Expr seq = new SequenceExpr(ctx.items);
            // Create a new TOP_LEVEL context with the sequence as result
            ParseContext topCtx = new ParseContext(ContextType.TOP_LEVEL);
            topCtx.exprStack.push(seq);
            topCtx.state = ParseState.HAVE_OPERAND;
            return topCtx;
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
        // Exception: .(args) is a dynamic function call, not a step
        if (token == XPathToken.DOT) {
            XPathToken next = lexer.peek();
            return next != XPathToken.LPAREN;
        }
        if (token == XPathToken.DOUBLE_DOT || token == XPathToken.AT) {
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
            // Check for prefixed function call or reference: prefix:name( or prefix:name#N
            if (next == XPathToken.COLON) {
                if (isPrefixedFunctionCall() || isPrefixedFunctionRef()) {
                    return false;
                }
                return true;
            }
            // name#arity is a function reference, not a step
            if (next == XPathToken.HASH) {
                return false;
            }
            // map{...} and array{...} are constructors, not steps
            if (next == XPathToken.LBRACE &&
                    ("map".equals(lexer.value()) || "array".equals(lexer.value()))) {
                return false;
            }
            // No paren or colon follows - it's an element name (step)
            return true;
        }

        // URIQualifiedName: Q{uri}local — function call or reference, not a step
        if (token == XPathToken.URIQNAME) {
            XPathToken next = lexer.peek();
            if (next == XPathToken.LPAREN || next == XPathToken.HASH) {
                return false;
            }
            return true;
        }

        // Node type tests
        if (token.isNodeType()) {
            return true;
        }
        
        // XPath 2.0/3.0 kind tests: element(), attribute(), document-node(), namespace-node(), schema-element(), schema-attribute()
        if (token == XPathToken.ELEMENT || token == XPathToken.ATTRIBUTE ||
            token == XPathToken.DOCUMENT_NODE || token == XPathToken.NAMESPACE_NODE ||
            token == XPathToken.SCHEMA_ELEMENT || token == XPathToken.SCHEMA_ATTRIBUTE) {
            // These are kind tests when followed by (
            if (lexer.peek() == XPathToken.LPAREN) {
                return true;
            }
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
     * Checks if the current position starts a prefixed function reference: prefix:name#arity.
     * Used when an NCNAME is followed by COLON and we need to disambiguate between
     * prefix:name(args) and prefix:name#arity.
     */
    private boolean isPrefixedFunctionRef() {
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
            
            return lexer.current() == XPathToken.HASH;
        } finally {
            lexer.restore(savedPos, savedCurrent, savedValue, savedStart);
        }
    }

    /**
     * Parses a named function reference: name#arity or prefix:name#arity.
     * Returns a NamedFunctionRefExpr that evaluates to a function item.
     */
    private Expr parseNamedFunctionRef() throws XPathSyntaxException {
        String prefix = null;
        String localName = lexer.value();
        lexer.advance();
        
        // Check for prefix:localname pattern
        if (lexer.current() == XPathToken.COLON) {
            prefix = localName;
            lexer.advance();
            localName = lexer.value();
            lexer.advance();
        }
        
        // Consume the '#'
        lexer.expect(XPathToken.HASH);
        
        // Parse the arity (integer literal)
        if (lexer.current() != XPathToken.NUMBER_LITERAL) {
            throw new XPathSyntaxException("Expected integer arity after '#'",
                lexer.getExpression(), lexer.tokenStart());
        }
        int arity = (int) Double.parseDouble(lexer.value());
        lexer.advance();
        
        return new NamedFunctionRefExpr(prefix, localName, arity);
    }

    /**
     * Parses a variable reference ($name). This is leaf-level, no nested expressions.
     */
    private Expr parseVariableReference() throws XPathSyntaxException {
        lexer.expect(XPathToken.DOLLAR);

        // Variable names can be any NCName, including keywords like 'in', 'and', 'or', etc.
        if (!isNCNameOrKeyword()) {
            throw new XPathSyntaxException("Expected variable name",
                lexer.getExpression(), lexer.tokenStart());
        }

        String name = lexer.value();
        lexer.advance();

        // Check for prefix:localname - only consume colon if followed by a valid local name
        if (lexer.current() == XPathToken.COLON) {
            XPathToken afterColon = lexer.peek();
            if (afterColon == XPathToken.NCNAME || isKeywordToken(afterColon)) {
                lexer.advance();
                String localName = lexer.value();
                lexer.advance();
                String resolvedUri = null;
                if (namespaceResolver != null) {
                    resolvedUri = namespaceResolver.resolve(name);
                }
                return new VariableReference(name, localName, resolvedUri);
            }
        }

        return new VariableReference(name);
    }
    
    /**
     * Checks if the current token can be used as an NCName (including keywords).
     * In XPath, keywords are context-sensitive and can be used as names in contexts
     * where a name is expected (like variable names).
     */
    private boolean isNCNameOrKeyword() {
        XPathToken t = lexer.current();
        if (t == XPathToken.NCNAME) {
            return true;
        }
        // Keywords that can also be used as NCNames
        switch (t) {
            case AND: case OR: case DIV: case MOD:
            case IF: case THEN: case ELSE:
            case FOR: case LET: case RETURN: case IN:
            case SOME: case EVERY: case SATISFIES:
            case INSTANCE: case OF: case AS:
            case TREAT: case CASTABLE: case CAST:
            case INTERSECT: case EXCEPT:
            case TO: case EQ: case NE: case LT: case LE: case GT: case GE:
            case IS: case PRECEDES: case FOLLOWS:
                return true;
            default:
                return false;
        }
    }

    /**
     * Checks if the given token type is a keyword that can be used as an NCName.
     */
    private boolean isKeywordToken(XPathToken t) {
        switch (t) {
            case AND: case OR: case DIV: case MOD:
            case IF: case THEN: case ELSE:
            case FOR: case LET: case RETURN: case IN:
            case SOME: case EVERY: case SATISFIES:
            case INSTANCE: case OF: case AS:
            case TREAT: case CASTABLE: case CAST:
            case INTERSECT: case EXCEPT:
            case TO: case EQ: case NE: case LT: case LE: case GT: case GE:
            case IS: case PRECEDES: case FOLLOWS:
                return true;
            default:
                return false;
        }
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

        // XPath 3.0: Parenthesized expression as a step (simple mapping operator)
        // e.g., /(if (...) then . else foo) or /(1 to 10)
        if (lexer.current() == XPathToken.LPAREN) {
            lexer.advance();
            Expr expr = parseSubExpression();
            lexer.expect(XPathToken.RPAREN);
            Step step = Step.expression(expr);
            return parseStepPredicates(step);
        }

        // Check for function call as a step (e.g., $tree/id('A004') or path/xs:date(.))
        // A function call step is name followed by '(' - not an axis specifier
        if (lexer.current() == XPathToken.NCNAME && lexer.peek() == XPathToken.LPAREN) {
            Expr funcExpr = parseFunctionCallAsStep();
            Step step = Step.expression(funcExpr);
            return parseStepPredicates(step);
        }
        // Prefixed function call as step (e.g., path/xs:date(.))
        if (lexer.current() == XPathToken.NCNAME && lexer.peek() == XPathToken.COLON
                && isPrefixedFunctionCall()) {
            Expr funcExpr = parsePrefixedFunctionCallAsStep();
            Step step = Step.expression(funcExpr);
            return parseStepPredicates(step);
        }
        // URIQualifiedName function call as step (e.g., path/Q{f}attribute(.))
        if (lexer.current() == XPathToken.URIQNAME && lexer.peek() == XPathToken.LPAREN) {
            Expr funcExpr = parseURIQualifiedFunctionCallAsStep();
            Step step = Step.expression(funcExpr);
            return parseStepPredicates(step);
        }
        
        // Check for XPath 2.0 kind tests as steps: element(), attribute(), document-node()
        // When these appear without an explicit axis, use appropriate default axis:
        // - attribute() uses ATTRIBUTE axis
        // - element(), document-node() use CHILD axis
        if ((lexer.current() == XPathToken.ELEMENT || lexer.current() == XPathToken.ATTRIBUTE ||
             lexer.current() == XPathToken.DOCUMENT_NODE || lexer.current() == XPathToken.NAMESPACE_NODE) && lexer.peek() == XPathToken.LPAREN) {
            // attribute() kind test uses attribute axis by default
            Step.Axis defaultAxis = (lexer.current() == XPathToken.ATTRIBUTE) 
                ? Step.Axis.ATTRIBUTE : Step.Axis.CHILD;
            Step step = parseNodeTest(defaultAxis);
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
     * Parses a function call when it appears as a step in a path expression.
     * e.g., $tree/id('A004') - the id('A004') part
     */
    private Expr parseFunctionCallAsStep() throws XPathSyntaxException {
        String funcName = lexer.value();
        lexer.advance(); // consume function name
        lexer.advance(); // consume LPAREN
        
        // Parse arguments
        List<Expr> args = new ArrayList<>();
        if (lexer.current() != XPathToken.RPAREN) {
            args.add(parseSubExpression());
            while (lexer.current() == XPathToken.COMMA) {
                lexer.advance();
                args.add(parseSubExpression());
            }
        }
        lexer.expect(XPathToken.RPAREN);
        
        return new FunctionCall(null, funcName, args);
    }

    /**
     * Parses a prefixed function call as a step in a path expression.
     * e.g., /BOOKLIST/BOOKS/ITEM/PUB-DATE/xs:date(.)
     */
    private Expr parsePrefixedFunctionCallAsStep() throws XPathSyntaxException {
        String prefix = lexer.value();
        lexer.advance(); // consume prefix
        lexer.advance(); // consume COLON
        String localName = lexer.value();
        lexer.advance(); // consume local name
        lexer.advance(); // consume LPAREN

        // Resolve namespace prefix at compile time
        String resolvedURI = null;
        if (namespaceResolver != null) {
            resolvedURI = namespaceResolver.resolve(prefix);
        }

        // Parse arguments
        List<Expr> args = new ArrayList<>();
        if (lexer.current() != XPathToken.RPAREN) {
            args.add(parseSubExpression());
            while (lexer.current() == XPathToken.COMMA) {
                lexer.advance();
                args.add(parseSubExpression());
            }
        }
        lexer.expect(XPathToken.RPAREN);

        return new FunctionCall(prefix, localName, resolvedURI, args);
    }

    /**
     * Parses a URIQualifiedName function call as a path step.
     * e.g., path/Q{f}attribute('x', string(.))
     */
    private Expr parseURIQualifiedFunctionCallAsStep() throws XPathSyntaxException {
        String clark = lexer.value();
        lexer.advance(); // consume URIQNAME
        lexer.advance(); // consume LPAREN

        int closeBrace = clark.indexOf('}');
        String uri = clark.substring(1, closeBrace);
        String localName = clark.substring(closeBrace + 1);

        List<Expr> args = new ArrayList<>();
        if (lexer.current() != XPathToken.RPAREN) {
            args.add(parseSubExpression());
            while (lexer.current() == XPathToken.COMMA) {
                lexer.advance();
                args.add(parseSubExpression());
            }
        }
        lexer.expect(XPathToken.RPAREN);

        return new FunctionCall(null, localName, uri, args);
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

        // Wildcard or any-namespace wildcard (*:localname)
        if (token == XPathToken.STAR) {
            lexer.advance();
            // XPath 2.0: Check for *:localname (any namespace with specific local name)
            if (lexer.current() == XPathToken.COLON) {
                lexer.advance();
                if (lexer.current() == XPathToken.NCNAME) {
                    String localName = lexer.value();
                    lexer.advance();
                    return Step.anyNamespace(axis, localName);
                }
                throw new XPathSyntaxException("Expected local name after *:",
                    lexer.getExpression(), lexer.tokenStart());
            }
            return Step.wildcard(axis);
        }

        // Node type test (XPath 1.0)
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
                // XTSE0340: PI names cannot contain colons (Namespaces REC section 6)
                if (piTarget != null && piTarget.contains(":")) {
                    throw new XPathSyntaxException(
                        "XTSE0340: Processing instruction name cannot contain a colon: " + piTarget,
                        lexer.getExpression(), lexer.tokenStart());
                }
                lexer.advance();
            }

            lexer.expect(XPathToken.RPAREN);

            if (piTarget != null) {
                return Step.processingInstruction(axis, piTarget);
            }
            return new Step(axis, nodeTestType);
        }
        
        // XPath 2.0/3.0 kind tests: element(), attribute(), document-node(), namespace-node(), schema-element(), schema-attribute()
        if (token == XPathToken.ELEMENT || token == XPathToken.ATTRIBUTE || 
            token == XPathToken.DOCUMENT_NODE || token == XPathToken.NAMESPACE_NODE ||
            token == XPathToken.SCHEMA_ELEMENT || token == XPathToken.SCHEMA_ATTRIBUTE) {
            Step.NodeTestType nodeTestType;
            switch (token) {
                case ELEMENT:
                    nodeTestType = Step.NodeTestType.ELEMENT;
                    break;
                case ATTRIBUTE:
                    nodeTestType = Step.NodeTestType.ATTRIBUTE;
                    break;
                case DOCUMENT_NODE:
                    nodeTestType = Step.NodeTestType.DOCUMENT_NODE;
                    break;
                case NAMESPACE_NODE:
                    nodeTestType = Step.NodeTestType.NAMESPACE_NODE;
                    break;
                case SCHEMA_ELEMENT:
                    nodeTestType = Step.NodeTestType.SCHEMA_ELEMENT;
                    break;
                case SCHEMA_ATTRIBUTE:
                    nodeTestType = Step.NodeTestType.SCHEMA_ATTRIBUTE;
                    break;
                default:
                    throw new XPathSyntaxException("Unexpected kind test: " + token,
                        lexer.getExpression(), lexer.tokenStart());
            }
            
            lexer.advance();
            lexer.expect(XPathToken.LPAREN);
            
            // Check for optional name argument: element(name) or element(name, type)
            String elemName = null;
            String typeNamespaceURI = null;
            String typeLocalName = null;
            if (lexer.current() == XPathToken.NCNAME || lexer.current() == XPathToken.STAR) {
                if (lexer.current() == XPathToken.STAR) {
                    elemName = "*";
                } else {
                    elemName = lexer.value();
                }
                lexer.advance();
                
                // Check for prefix
                if (lexer.current() == XPathToken.COLON) {
                    lexer.advance();
                    if (lexer.current() == XPathToken.NCNAME) {
                        elemName = elemName + ":" + lexer.value();
                        lexer.advance();
                    }
                }
                
                // Check for type argument: element(name, type) or attribute(name, type)
                if (lexer.current() == XPathToken.COMMA) {
                    lexer.advance();
                    if (lexer.current() == XPathToken.NCNAME) {
                        String typePrefix = lexer.value();
                        lexer.advance();
                        // Check for prefix:localname
                        if (lexer.current() == XPathToken.COLON) {
                            lexer.advance();
                            if (lexer.current() == XPathToken.NCNAME) {
                                String typeLocal = lexer.value();
                                lexer.advance();
                                // Resolve the prefix to namespace
                                typeNamespaceURI = resolvePrefix(typePrefix);
                                typeLocalName = typeLocal;
                            }
                        } else {
                            // Unprefixed type - assume xs namespace
                            typeNamespaceURI = "http://www.w3.org/2001/XMLSchema";
                            typeLocalName = typePrefix;
                        }
                    }
                }
            }
            
            lexer.expect(XPathToken.RPAREN);
            
            // Return appropriate step with resolved type namespace
            if (elemName != null || typeLocalName != null) {
                return new Step(axis, nodeTestType, elemName, typeNamespaceURI, typeLocalName);
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

            // Simple name (unprefixed)
            // Check if we have a default element namespace (from xpath-default-namespace)
            // Note: xpath-default-namespace only applies to element names, not attribute names
            // (attributes without a prefix are always in no namespace per XML spec)
            if (axis != Step.Axis.ATTRIBUTE) {
                String defaultNs = namespaceResolver != null ? 
                    namespaceResolver.getDefaultElementNamespace() : null;
                if (defaultNs != null && !defaultNs.isEmpty()) {
                    // Use the default element namespace for this unprefixed name
                    return new Step(axis, defaultNs, name);
                }
            }
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
        // The "xml" prefix is always implicitly bound to the XML namespace
        if ("xml".equals(prefix)) {
            return "http://www.w3.org/XML/1998/namespace";
        }
        if (namespaceResolver != null) {
            String uri = namespaceResolver.resolve(prefix);
            if (uri != null) {
                return uri;
            }
        }
        // XPST0081: undeclared namespace prefix
        if (namespaceResolver != null) {
            throw new XPathSyntaxException(
                "XPST0081: No namespace declared for prefix '" + prefix + "'",
                lexer.getExpression(), lexer.tokenStart());
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

    // ========================================================================
    // Type Expression Parsing
    // ========================================================================
    
    /**
     * Returns the type expression kind for the current token, or null if not a type keyword.
     */
    private TypeExpr.Kind getTypeExprKind(XPathToken token) {
        switch (token) {
            case INSTANCE: return TypeExpr.Kind.INSTANCE_OF;
            case CAST: return TypeExpr.Kind.CAST_AS;
            case CASTABLE: return TypeExpr.Kind.CASTABLE_AS;
            case TREAT: return TypeExpr.Kind.TREAT_AS;
            default: return null;
        }
    }
    
    /**
     * Returns the operator for a type expression kind.
     */
    private Operator getTypeOperator(TypeExpr.Kind kind) {
        switch (kind) {
            case INSTANCE_OF: return Operator.INSTANCE_OF;
            case CAST_AS: return Operator.CAST_AS;
            case CASTABLE_AS: return Operator.CASTABLE_AS;
            case TREAT_AS: return Operator.TREAT_AS;
            default: throw new IllegalArgumentException("Unknown type kind: " + kind);
        }
    }
    
    /**
     * Parses a type expression after the operand and type keyword have been identified.
     */
    private Expr parseTypeExpr(TypeExpr.Kind kind, Expr operand) throws XPathSyntaxException {
        // Advance past the first keyword (instance, cast, castable, treat)
        lexer.advance();
        
        // Expect 'of' for instance, 'as' for cast/castable/treat
        if (kind == TypeExpr.Kind.INSTANCE_OF) {
            if (lexer.current() != XPathToken.OF) {
                throw new XPathSyntaxException("Expected 'of' after 'instance'",
                    lexer.getExpression(), lexer.tokenStart());
            }
        } else {
            if (lexer.current() != XPathToken.AS) {
                throw new XPathSyntaxException("Expected 'as' after '" + 
                    kind.name().toLowerCase().replace("_as", "") + "'",
                    lexer.getExpression(), lexer.tokenStart());
            }
        }
        lexer.advance();
        
        // Parse the sequence type
        SequenceType seqType = parseSequenceType();
        
        return new TypeExpr(kind, operand, seqType);
    }
    
    /**
     * Parses a sequence type.
     * 
     * <p>Handles:
     * <ul>
     *   <li>empty-sequence()</li>
     *   <li>item()</li>
     *   <li>node(), element(), attribute(), text(), comment(), processing-instruction()</li>
     *   <li>document-node()</li>
     *   <li>schema-element(name), schema-attribute(name)</li>
     *   <li>QName (atomic type reference like xs:integer)</li>
     * </ul>
     * Plus occurrence indicators: ?, *, +
     */
    private SequenceType parseSequenceType() throws XPathSyntaxException {
        SequenceType.ItemKind itemKind;
        String namespaceURI = null;
        String localName = null;
        QName typeName = null;  // For element(name, type) or attribute(name, type)
        
        XPathToken token = lexer.current();
        
        switch (token) {
            case EMPTY_SEQUENCE:
                lexer.advance();
                expectToken(XPathToken.LPAREN, "(");
                expectToken(XPathToken.RPAREN, ")");
                return SequenceType.EMPTY;
                
            case ITEM:
                itemKind = SequenceType.ItemKind.ITEM;
                lexer.advance();
                expectToken(XPathToken.LPAREN, "(");
                expectToken(XPathToken.RPAREN, ")");
                break;
                
            case NODE_TYPE_NODE:
                itemKind = SequenceType.ItemKind.NODE;
                lexer.advance();
                expectToken(XPathToken.LPAREN, "(");
                expectToken(XPathToken.RPAREN, ")");
                break;
                
            case NODE_TYPE_TEXT:
                itemKind = SequenceType.ItemKind.TEXT;
                lexer.advance();
                expectToken(XPathToken.LPAREN, "(");
                expectToken(XPathToken.RPAREN, ")");
                break;
                
            case NODE_TYPE_COMMENT:
                itemKind = SequenceType.ItemKind.COMMENT;
                lexer.advance();
                expectToken(XPathToken.LPAREN, "(");
                expectToken(XPathToken.RPAREN, ")");
                break;
                
            case NODE_TYPE_PI:
                itemKind = SequenceType.ItemKind.PROCESSING_INSTRUCTION;
                lexer.advance();
                expectToken(XPathToken.LPAREN, "(");
                // Optional PI target name
                if (lexer.current() == XPathToken.NCNAME || lexer.current() == XPathToken.STRING_LITERAL) {
                    localName = lexer.value();
                    lexer.advance();
                }
                expectToken(XPathToken.RPAREN, ")");
                break;
                
            case ELEMENT:
                itemKind = SequenceType.ItemKind.ELEMENT;
                lexer.advance();
                expectToken(XPathToken.LPAREN, "(");
                // Optional element name and type: element(), element(name), element(*, type), element(name, type)
                if (lexer.current() != XPathToken.RPAREN) {
                    if (lexer.current() == XPathToken.STAR) {
                        lexer.advance();
                    } else {
                        // Parse element name with namespace resolution
                        String[] elemQName = parseAtomicTypeName();
                        namespaceURI = elemQName[0];
                        localName = elemQName[1];
                    }
                    // Optional type argument after comma
                    if (lexer.current() == XPathToken.COMMA) {
                        lexer.advance();
                        String[] typeQName = parseAtomicTypeName();
                        String typeUri = typeQName[0] != null ? typeQName[0] : "";
                        typeName = new QName(typeUri, typeQName[1], typeQName[1]);
                        // Optional '?' for nillable types (e.g., xs:untyped?)
                        if (lexer.current() == XPathToken.QUESTION) {
                            lexer.advance();
                        }
                    }
                }
                expectToken(XPathToken.RPAREN, ")");
                break;
                
            case ATTRIBUTE:
                itemKind = SequenceType.ItemKind.ATTRIBUTE;
                lexer.advance();
                expectToken(XPathToken.LPAREN, "(");
                // Optional attribute name and type: attribute(), attribute(name), attribute(*, type), attribute(name, type)
                if (lexer.current() != XPathToken.RPAREN) {
                    if (lexer.current() == XPathToken.STAR) {
                        lexer.advance();
                    } else {
                        // Parse attribute name with namespace resolution
                        String[] attrQName = parseAtomicTypeName();
                        namespaceURI = attrQName[0];
                        localName = attrQName[1];
                    }
                    // Optional type argument after comma
                    if (lexer.current() == XPathToken.COMMA) {
                        lexer.advance();
                        String[] typeQName = parseAtomicTypeName();
                        String typeUri = typeQName[0] != null ? typeQName[0] : "";
                        typeName = new QName(typeUri, typeQName[1], typeQName[1]);
                        // Optional '?' for nillable types
                        if (lexer.current() == XPathToken.QUESTION) {
                            lexer.advance();
                        }
                    }
                }
                expectToken(XPathToken.RPAREN, ")");
                break;
                
            case DOCUMENT_NODE:
                itemKind = SequenceType.ItemKind.DOCUMENT_NODE;
                lexer.advance();
                expectToken(XPathToken.LPAREN, "(");
                // Could have element() inside, but we simplify
                expectToken(XPathToken.RPAREN, ")");
                break;
                
            case NAMESPACE_NODE:
                itemKind = SequenceType.ItemKind.NAMESPACE_NODE;
                lexer.advance();
                expectToken(XPathToken.LPAREN, "(");
                expectToken(XPathToken.RPAREN, ")");
                break;
                
            case SCHEMA_ELEMENT:
                itemKind = SequenceType.ItemKind.SCHEMA_ELEMENT;
                lexer.advance();
                expectToken(XPathToken.LPAREN, "(");
                {
                    String[] schemaElemQName = parseAtomicTypeName();
                    namespaceURI = schemaElemQName[0];
                    localName = schemaElemQName[1];
                }
                expectToken(XPathToken.RPAREN, ")");
                break;
                
            case SCHEMA_ATTRIBUTE:
                itemKind = SequenceType.ItemKind.SCHEMA_ATTRIBUTE;
                lexer.advance();
                expectToken(XPathToken.LPAREN, "(");
                {
                    String[] schemaAttrQName = parseAtomicTypeName();
                    namespaceURI = schemaAttrQName[0];
                    localName = schemaAttrQName[1];
                }
                expectToken(XPathToken.RPAREN, ")");
                break;
                
            case NCNAME:
                // Check for map(...) or array(...) type tests
                if ("map".equals(lexer.value()) && lexer.peek() == XPathToken.LPAREN) {
                    itemKind = SequenceType.ItemKind.MAP;
                    lexer.advance();
                    expectToken(XPathToken.LPAREN, "(");
                    // Skip contents: map(*) or map(K, V)
                    skipParenthesizedContent();
                    break;
                }
                if ("array".equals(lexer.value()) && lexer.peek() == XPathToken.LPAREN) {
                    itemKind = SequenceType.ItemKind.ARRAY;
                    lexer.advance();
                    expectToken(XPathToken.LPAREN, "(");
                    skipParenthesizedContent();
                    break;
                }
                // Fall through to atomic type
            case PREFIX:
                // Atomic type reference (e.g., xs:integer, xs:string)
                itemKind = SequenceType.ItemKind.ATOMIC;
                String[] qname = parseAtomicTypeName();
                namespaceURI = qname[0];
                localName = qname[1];
                break;
                
            default:
                throw new XPathSyntaxException("Expected sequence type, found: " + token,
                    lexer.getExpression(), lexer.tokenStart());
        }
        
        // Parse occurrence indicator
        SequenceType.Occurrence occurrence = SequenceType.Occurrence.ONE;
        switch (lexer.current()) {
            case QUESTION:
                occurrence = SequenceType.Occurrence.ZERO_OR_ONE;
                lexer.advance();
                break;
            case STAR:
            case STAR_MULTIPLY:  // Also accept * as occurrence indicator even if lexer thought it was multiplication
                occurrence = SequenceType.Occurrence.ZERO_OR_MORE;
                lexer.advance();
                break;
            case PLUS:
                occurrence = SequenceType.Occurrence.ONE_OR_MORE;
                lexer.advance();
                break;
            default:
                // No occurrence indicator
                break;
        }
        
        return new SequenceType(itemKind, namespaceURI, localName, typeName, occurrence);
    }
    
    /**
     * Parses a QName for use in type declarations.
     */
    private String parseQNameForType() throws XPathSyntaxException {
        if (lexer.current() != XPathToken.NCNAME) {
            throw new XPathSyntaxException("Expected QName",
                lexer.getExpression(), lexer.tokenStart());
        }
        
        StringBuilder qname = new StringBuilder();
        qname.append(lexer.value());
        lexer.advance();
        
        // Check for colon (prefix:localname)
        if (lexer.current() == XPathToken.COLON) {
            qname.append(":");
            lexer.advance();
            if (lexer.current() == XPathToken.NCNAME) {
                qname.append(lexer.value());
                lexer.advance();
            }
        }
        
        return qname.toString();
    }
    
    /**
     * Parses an atomic type name (QName), returning [namespaceURI, localName].
     * Handles both "prefix:local" and plain "local" names.
     */
    private String[] parseAtomicTypeName() throws XPathSyntaxException {
        String namespaceURI = null;
        String localName;
        
        if (lexer.current() != XPathToken.NCNAME) {
            throw new XPathSyntaxException("Expected type name",
                lexer.getExpression(), lexer.tokenStart());
        }
        
        String firstPart = lexer.value();
        lexer.advance();
        
        // Check for colon (prefix:localname)
        if (lexer.current() == XPathToken.COLON) {
            lexer.advance();
            
            // Resolve prefix to namespace URI
            String prefix = firstPart;
            if (namespaceResolver != null) {
                namespaceURI = namespaceResolver.resolve(prefix);
            }
            if (namespaceURI == null) {
                // Fallback for common XSD prefixes when no resolver available
                if ("xs".equals(prefix) || "xsd".equals(prefix)) {
                    namespaceURI = SequenceType.XS_NAMESPACE;
                }
            }
            
            if (lexer.current() != XPathToken.NCNAME) {
                throw new XPathSyntaxException("Expected local name after prefix '" + prefix + ":'",
                    lexer.getExpression(), lexer.tokenStart());
            }
            localName = lexer.value();
            lexer.advance();
        } else {
            // No prefix - just a local name
            localName = firstPart;
        }
        
        return new String[] { namespaceURI, localName };
    }
    
    /**
     * Expects and consumes a specific token.
     */
    private void expectToken(XPathToken expected, String description) throws XPathSyntaxException {
        if (lexer.current() != expected) {
            throw new XPathSyntaxException("Expected '" + description + "' but found: " + lexer.current(),
                lexer.getExpression(), lexer.tokenStart());
        }
        lexer.advance();
    }

    /**
     * Skips tokens until a matching closing parenthesis is found.
     * Assumes the opening parenthesis has already been consumed.
     */
    private void skipParenthesizedContent() throws XPathSyntaxException {
        int depth = 1;
        while (depth > 0) {
            XPathToken t = lexer.current();
            if (t == XPathToken.EOF) {
                throw new XPathSyntaxException("Unexpected end of expression in type test",
                    lexer.getExpression(), lexer.tokenStart());
            }
            if (t == XPathToken.LPAREN) {
                depth++;
            } else if (t == XPathToken.RPAREN) {
                depth--;
            }
            lexer.advance();
        }
    }

}

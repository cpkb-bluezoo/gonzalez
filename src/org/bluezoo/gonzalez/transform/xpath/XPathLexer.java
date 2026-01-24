/*
 * XPathLexer.java
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

/**
 * Lexical analyzer (tokenizer) for XPath 1.0 expressions.
 *
 * <p>The lexer breaks an XPath expression string into a stream of tokens.
 * It handles:
 * <ul>
 *   <li>String and number literals</li>
 *   <li>NCNames and QNames</li>
 *   <li>Operators (arithmetic, comparison, logical, union)</li>
 *   <li>Axis specifiers</li>
 *   <li>Node type tests</li>
 *   <li>Path operators and abbreviations</li>
 *   <li>Variable references</li>
 *   <li>Whitespace handling</li>
 * </ul>
 *
 * <p>The lexer is context-sensitive for certain tokens. For example, '*' can be
 * the multiplication operator or a name test wildcard depending on context.
 * The lexer tracks this context to return the appropriate token type.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class XPathLexer {

    private final String expression;
    private final int length;
    private int position;

    // Current token information
    private XPathToken currentToken;
    private String currentValue;
    private int tokenStart;

    // Context tracking for disambiguation
    private XPathToken previousToken;

    /**
     * Creates a new lexer for the given XPath expression.
     *
     * @param expression the XPath expression to tokenize
     */
    public XPathLexer(String expression) {
        if (expression == null) {
            throw new NullPointerException("Expression cannot be null");
        }
        this.expression = expression;
        this.length = expression.length();
        this.position = 0;
        this.previousToken = null;
        
        // Prime the first token
        advance();
    }

    /**
     * Returns the current token type.
     *
     * @return the current token
     */
    public XPathToken current() {
        return currentToken;
    }

    /**
     * Returns the string value of the current token.
     * For literals, this is the literal value.
     * For names, this is the name.
     * For operators and delimiters, this is the operator string.
     *
     * @return the token value
     */
    public String value() {
        return currentValue;
    }

    /**
     * Returns the start position of the current token in the expression.
     *
     * @return the token start position
     */
    public int tokenStart() {
        return tokenStart;
    }

    /**
     * Returns true if there are more tokens.
     *
     * @return true if not at EOF
     */
    public boolean hasMore() {
        return currentToken != XPathToken.EOF;
    }

    /**
     * Advances to the next token and returns it.
     *
     * @return the new current token
     */
    public XPathToken advance() {
        previousToken = currentToken;
        skipWhitespace();
        tokenStart = position;

        if (position >= length) {
            currentToken = XPathToken.EOF;
            currentValue = "";
            return currentToken;
        }

        char c = expression.charAt(position);

        // String literal
        if (c == '"' || c == '\'') {
            return scanStringLiteral(c);
        }

        // Number literal
        if (isDigit(c) || (c == '.' && position + 1 < length && isDigit(expression.charAt(position + 1)))) {
            return scanNumberLiteral();
        }

        // Operators and delimiters
        switch (c) {
            case '(':
                position++;
                currentToken = XPathToken.LPAREN;
                currentValue = "(";
                return currentToken;

            case ')':
                position++;
                currentToken = XPathToken.RPAREN;
                currentValue = ")";
                return currentToken;

            case '[':
                position++;
                currentToken = XPathToken.LBRACKET;
                currentValue = "[";
                return currentToken;

            case ']':
                position++;
                currentToken = XPathToken.RBRACKET;
                currentValue = "]";
                return currentToken;

            case ',':
                position++;
                currentToken = XPathToken.COMMA;
                currentValue = ",";
                return currentToken;

            case '@':
                position++;
                currentToken = XPathToken.AT;
                currentValue = "@";
                return currentToken;

            case '$':
                position++;
                currentToken = XPathToken.DOLLAR;
                currentValue = "$";
                return currentToken;

            case '+':
                position++;
                currentToken = XPathToken.PLUS;
                currentValue = "+";
                return currentToken;

            case '-':
                position++;
                currentToken = XPathToken.MINUS;
                currentValue = "-";
                return currentToken;

            case '|':
                position++;
                currentToken = XPathToken.PIPE;
                currentValue = "|";
                return currentToken;

            case '=':
                position++;
                currentToken = XPathToken.EQUALS;
                currentValue = "=";
                return currentToken;

            case '!':
                if (position + 1 < length && expression.charAt(position + 1) == '=') {
                    position += 2;
                    currentToken = XPathToken.NOT_EQUALS;
                    currentValue = "!=";
                    return currentToken;
                }
                // '!' alone is not valid in XPath
                position++;
                currentToken = XPathToken.ERROR;
                currentValue = "!";
                return currentToken;

            case '<':
                if (position + 1 < length && expression.charAt(position + 1) == '=') {
                    position += 2;
                    currentToken = XPathToken.LESS_THAN_OR_EQUAL;
                    currentValue = "<=";
                    return currentToken;
                }
                position++;
                currentToken = XPathToken.LESS_THAN;
                currentValue = "<";
                return currentToken;

            case '>':
                if (position + 1 < length && expression.charAt(position + 1) == '=') {
                    position += 2;
                    currentToken = XPathToken.GREATER_THAN_OR_EQUAL;
                    currentValue = ">=";
                    return currentToken;
                }
                position++;
                currentToken = XPathToken.GREATER_THAN;
                currentValue = ">";
                return currentToken;

            case '/':
                if (position + 1 < length && expression.charAt(position + 1) == '/') {
                    position += 2;
                    currentToken = XPathToken.DOUBLE_SLASH;
                    currentValue = "//";
                    return currentToken;
                }
                position++;
                currentToken = XPathToken.SLASH;
                currentValue = "/";
                return currentToken;

            case '.':
                if (position + 1 < length && expression.charAt(position + 1) == '.') {
                    position += 2;
                    currentToken = XPathToken.DOUBLE_DOT;
                    currentValue = "..";
                    return currentToken;
                }
                position++;
                currentToken = XPathToken.DOT;
                currentValue = ".";
                return currentToken;

            case ':':
                if (position + 1 < length && expression.charAt(position + 1) == ':') {
                    position += 2;
                    currentToken = XPathToken.DOUBLE_COLON;
                    currentValue = "::";
                    return currentToken;
                }
                position++;
                currentToken = XPathToken.COLON;
                currentValue = ":";
                return currentToken;

            case '*':
                // '*' is either multiplication or a wildcard name test
                // It's multiplication if preceded by certain tokens
                if (isMultiplicationContext()) {
                    position++;
                    currentToken = XPathToken.STAR;
                    currentValue = "*";
                    return currentToken;
                }
                position++;
                currentToken = XPathToken.STAR;
                currentValue = "*";
                return currentToken;
        }

        // NCName (identifier)
        if (isNameStartChar(c)) {
            return scanName();
        }

        // Unrecognized character
        position++;
        currentToken = XPathToken.ERROR;
        currentValue = String.valueOf(c);
        return currentToken;
    }

    /**
     * Peeks at the next token without advancing.
     *
     * @return the next token type
     */
    public XPathToken peek() {
        // Save state
        int savedPos = position;
        XPathToken savedCurrent = currentToken;
        String savedValue = currentValue;
        int savedStart = tokenStart;
        XPathToken savedPrevious = previousToken;

        // Advance
        advance();
        XPathToken next = currentToken;

        // Restore state
        position = savedPos;
        currentToken = savedCurrent;
        currentValue = savedValue;
        tokenStart = savedStart;
        previousToken = savedPrevious;

        return next;
    }

    /**
     * Expects the current token to be of the given type and advances.
     *
     * @param expected the expected token type
     * @throws XPathSyntaxException if the current token doesn't match
     */
    public void expect(XPathToken expected) throws XPathSyntaxException {
        if (currentToken != expected) {
            throw new XPathSyntaxException(
                "Expected " + expected + " but found " + currentToken + 
                " at position " + tokenStart);
        }
        advance();
    }

    /**
     * Checks if the current token matches and advances if so.
     *
     * @param token the token to match
     * @return true if matched and advanced
     */
    public boolean match(XPathToken token) {
        if (currentToken == token) {
            advance();
            return true;
        }
        return false;
    }

    // ========================================================================
    // Private scanning methods
    // ========================================================================

    private void skipWhitespace() {
        while (position < length) {
            char c = expression.charAt(position);
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
                position++;
            } else {
                break;
            }
        }
    }

    private XPathToken scanStringLiteral(char quote) {
        position++; // skip opening quote
        int start = position;
        
        while (position < length && expression.charAt(position) != quote) {
            position++;
        }
        
        if (position >= length) {
            currentToken = XPathToken.ERROR;
            currentValue = "Unterminated string literal";
            return currentToken;
        }
        
        currentValue = expression.substring(start, position);
        position++; // skip closing quote
        currentToken = XPathToken.STRING_LITERAL;
        return currentToken;
    }

    private XPathToken scanNumberLiteral() {
        int start = position;
        
        // Integer part
        while (position < length && isDigit(expression.charAt(position))) {
            position++;
        }
        
        // Decimal part
        if (position < length && expression.charAt(position) == '.') {
            position++;
            while (position < length && isDigit(expression.charAt(position))) {
                position++;
            }
        }
        
        currentValue = expression.substring(start, position);
        currentToken = XPathToken.NUMBER_LITERAL;
        return currentToken;
    }

    private XPathToken scanName() {
        int start = position;
        
        // First character already validated as name start
        position++;
        
        // Rest of name
        while (position < length && isNameChar(expression.charAt(position))) {
            position++;
        }
        
        String name = expression.substring(start, position);
        
        // Check for axis specifier (name followed by ::)
        skipWhitespace();
        if (position + 1 < length && expression.charAt(position) == ':' && 
            expression.charAt(position + 1) == ':') {
            XPathToken axisToken = getAxisToken(name);
            if (axisToken != null) {
                position += 2; // consume ::
                currentToken = axisToken;
                currentValue = name;
                return currentToken;
            }
        }
        
        // Check for node type test (name followed by '(')
        int savedPos = position;
        skipWhitespace();
        if (position < length && expression.charAt(position) == '(') {
            XPathToken nodeTypeToken = getNodeTypeToken(name);
            if (nodeTypeToken != null) {
                currentToken = nodeTypeToken;
                currentValue = name;
                return currentToken;
            }
        }
        position = savedPos; // restore position if not a node type
        
        // Check for operators that look like names
        XPathToken operatorToken = getOperatorToken(name);
        if (operatorToken != null && isOperatorContext()) {
            currentToken = operatorToken;
            currentValue = name;
            return currentToken;
        }
        
        // Regular NCName
        currentToken = XPathToken.NCNAME;
        currentValue = name;
        return currentToken;
    }

    private XPathToken getAxisToken(String name) {
        switch (name) {
            case "ancestor": return XPathToken.AXIS_ANCESTOR;
            case "ancestor-or-self": return XPathToken.AXIS_ANCESTOR_OR_SELF;
            case "attribute": return XPathToken.AXIS_ATTRIBUTE;
            case "child": return XPathToken.AXIS_CHILD;
            case "descendant": return XPathToken.AXIS_DESCENDANT;
            case "descendant-or-self": return XPathToken.AXIS_DESCENDANT_OR_SELF;
            case "following": return XPathToken.AXIS_FOLLOWING;
            case "following-sibling": return XPathToken.AXIS_FOLLOWING_SIBLING;
            case "namespace": return XPathToken.AXIS_NAMESPACE;
            case "parent": return XPathToken.AXIS_PARENT;
            case "preceding": return XPathToken.AXIS_PRECEDING;
            case "preceding-sibling": return XPathToken.AXIS_PRECEDING_SIBLING;
            case "self": return XPathToken.AXIS_SELF;
            default: return null;
        }
    }

    private XPathToken getNodeTypeToken(String name) {
        switch (name) {
            case "comment": return XPathToken.NODE_TYPE_COMMENT;
            case "text": return XPathToken.NODE_TYPE_TEXT;
            case "processing-instruction": return XPathToken.NODE_TYPE_PI;
            case "node": return XPathToken.NODE_TYPE_NODE;
            default: return null;
        }
    }

    private XPathToken getOperatorToken(String name) {
        switch (name) {
            case "and": return XPathToken.AND;
            case "or": return XPathToken.OR;
            case "mod": return XPathToken.MOD;
            case "div": return XPathToken.DIV;
            default: return null;
        }
    }

    /**
     * Determines if the current context expects an operator.
     * Used to disambiguate 'and', 'or', 'div', 'mod' from names.
     */
    private boolean isOperatorContext() {
        // An operator is expected after these tokens
        if (previousToken == null) {
            return false;
        }
        switch (previousToken) {
            case RPAREN:
            case RBRACKET:
            case NCNAME:
            case STAR:
            case STRING_LITERAL:
            case NUMBER_LITERAL:
            case NODE_TYPE_COMMENT:
            case NODE_TYPE_TEXT:
            case NODE_TYPE_PI:
            case NODE_TYPE_NODE:
                return true;
            default:
                return false;
        }
    }

    /**
     * Determines if '*' should be treated as multiplication vs name test.
     * Similar logic to isOperatorContext.
     */
    private boolean isMultiplicationContext() {
        return isOperatorContext();
    }

    // ========================================================================
    // Character classification
    // ========================================================================

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isNameStartChar(char c) {
        // XML NCName start characters (simplified)
        return (c >= 'A' && c <= 'Z') ||
               (c >= 'a' && c <= 'z') ||
               c == '_' ||
               (c >= 0xC0 && c <= 0xD6) ||
               (c >= 0xD8 && c <= 0xF6) ||
               (c >= 0xF8 && c <= 0x2FF) ||
               (c >= 0x370 && c <= 0x37D) ||
               (c >= 0x37F && c <= 0x1FFF) ||
               (c >= 0x200C && c <= 0x200D) ||
               (c >= 0x2070 && c <= 0x218F) ||
               (c >= 0x2C00 && c <= 0x2FEF) ||
               (c >= 0x3001 && c <= 0xD7FF) ||
               (c >= 0xF900 && c <= 0xFDCF) ||
               (c >= 0xFDF0 && c <= 0xFFFD);
    }

    private static boolean isNameChar(char c) {
        return isNameStartChar(c) ||
               c == '-' ||
               c == '.' ||
               isDigit(c) ||
               c == 0xB7 ||
               (c >= 0x0300 && c <= 0x036F) ||
               (c >= 0x203F && c <= 0x2040);
    }

    /**
     * Returns the original expression being tokenized.
     *
     * @return the expression string
     */
    public String getExpression() {
        return expression;
    }

    /**
     * Returns the current position in the expression.
     *
     * @return the position
     */
    public int getPosition() {
        return position;
    }

}

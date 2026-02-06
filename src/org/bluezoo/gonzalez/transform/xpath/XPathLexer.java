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
        skipWhitespaceAndComments();
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

            case '?':
                position++;
                currentToken = XPathToken.QUESTION;
                currentValue = "?";
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
                if (position + 1 < length && expression.charAt(position + 1) == '|') {
                    position += 2;
                    currentToken = XPathToken.CONCAT;
                    currentValue = "||";
                    return currentToken;
                }
                position++;
                currentToken = XPathToken.PIPE;
                currentValue = "|";
                return currentToken;

            case '!':
                if (position + 1 < length && expression.charAt(position + 1) == '=') {
                    position += 2;
                    currentToken = XPathToken.NOT_EQUALS;
                    currentValue = "!=";
                    return currentToken;
                }
                // '!' is the simple map operator in XPath 3.0
                position++;
                currentToken = XPathToken.BANG;
                currentValue = "!";
                return currentToken;

            case '<':
                if (position + 1 < length && expression.charAt(position + 1) == '=') {
                    position += 2;
                    currentToken = XPathToken.LESS_THAN_OR_EQUAL;
                    currentValue = "<=";
                    return currentToken;
                }
                if (position + 1 < length && expression.charAt(position + 1) == '<') {
                    position += 2;
                    currentToken = XPathToken.PRECEDES;
                    currentValue = "<<";
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
                if (position + 1 < length && expression.charAt(position + 1) == '>') {
                    position += 2;
                    currentToken = XPathToken.FOLLOWS;
                    currentValue = ">>";
                    return currentToken;
                }
                position++;
                currentToken = XPathToken.GREATER_THAN;
                currentValue = ">";
                return currentToken;
            
            case '=':
                if (position + 1 < length && expression.charAt(position + 1) == '>') {
                    position += 2;
                    currentToken = XPathToken.ARROW;
                    currentValue = "=>";
                    return currentToken;
                }
                position++;
                currentToken = XPathToken.EQUALS;
                currentValue = "=";
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
                if (position + 1 < length && expression.charAt(position + 1) == '=') {
                    position += 2;
                    currentToken = XPathToken.ASSIGN;
                    currentValue = ":=";
                    return currentToken;
                }
                position++;
                currentToken = XPathToken.COLON;
                currentValue = ":";
                return currentToken;

            case '*':
                // '*' is either multiplication or a wildcard name test
                // It's multiplication if preceded by certain tokens
                position++;
                if (isMultiplicationContext()) {
                    currentToken = XPathToken.STAR_MULTIPLY;
                } else {
                    currentToken = XPathToken.STAR;
                }
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
     * Expects the current token to be a keyword, accepting either the keyword token
     * or an NCNAME with the keyword's name (for cases where the keyword follows
     * a literal and wasn't recognized as a keyword by the lexer).
     *
     * @param expected the expected keyword token
     * @param keywordName the keyword name as a string (e.g., "else", "then")
     * @throws XPathSyntaxException if the current token doesn't match
     */
    public void expectKeyword(XPathToken expected, String keywordName) throws XPathSyntaxException {
        if (currentToken == expected) {
            advance();
        } else if (currentToken == XPathToken.NCNAME && keywordName.equals(currentValue)) {
            advance();
        } else {
            throw new XPathSyntaxException(
                "Expected " + expected + " but found " + currentToken + 
                " at position " + tokenStart);
        }
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

    /**
     * Skips whitespace and XPath comments.
     * XPath 2.0+ comments are delimited by (: and :) and can be nested.
     */
    private void skipWhitespaceAndComments() {
        while (position < length) {
            char c = expression.charAt(position);
            
            // Skip whitespace
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
                position++;
                continue;
            }
            
            // Check for XPath comment start (:
            if (c == '(' && position + 1 < length && expression.charAt(position + 1) == ':') {
                skipComment();
                continue;
            }
            
            // No more whitespace or comments
            break;
        }
    }
    
    /**
     * Skips an XPath comment starting at current position.
     * Assumes position is at '(' and next char is ':'.
     * XPath comments can be nested, so we track the nesting depth.
     */
    private void skipComment() {
        int depth = 0;
        
        while (position < length - 1) {
            char c = expression.charAt(position);
            char next = expression.charAt(position + 1);
            
            // Check for nested comment start (:
            if (c == '(' && next == ':') {
                depth++;
                position += 2;
                continue;
            }
            
            // Check for comment end :)
            if (c == ':' && next == ')') {
                depth--;
                position += 2;
                if (depth == 0) {
                    // Exited all nested comments
                    return;
                }
                continue;
            }
            
            // Regular character inside comment
            position++;
        }
        
        // If we get here, we have an unclosed comment
        // For now, just consume the rest of the expression
        position = length;
    }
    
    /**
     * Skips whitespace only (legacy method for potential internal use).
     */
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
        
        // Scientific notation (e.g., 1.5e10, 2E-5, 3e+2)
        if (position < length) {
            char c = expression.charAt(position);
            if (c == 'e' || c == 'E') {
                int expStart = position;
                position++;
                
                // Optional sign
                if (position < length) {
                    char signChar = expression.charAt(position);
                    if (signChar == '+' || signChar == '-') {
                        position++;
                    }
                }
                
                // Exponent digits (required)
                int digitStart = position;
                while (position < length && isDigit(expression.charAt(position))) {
                    position++;
                }
                
                // If no digits after 'e', it's not scientific notation - backtrack
                if (position == digitStart) {
                    position = expStart;
                }
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
        
        // Check for XPath 2.0/3.0 expression keywords
        XPathToken keywordToken = getKeywordToken(name);
        if (keywordToken != null && isKeywordContext()) {
            currentToken = keywordToken;
            currentValue = name;
            return currentToken;
        }
        
        // Regular NCName
        currentToken = XPathToken.NCNAME;
        currentValue = name;
        return currentToken;
    }
    
    /**
     * Determines if the current context expects an expression keyword.
     * Keywords like 'if', 'for', 'let', 'some', 'every' start new expressions.
     * Keywords like 'then', 'else', 'return' follow conditions/expressions.
     * 
     * Note: Keywords are NOT recognized after '/' or '//' because in path expressions
     * like '/in/foo', the 'in' should be treated as an element name, not the keyword.
     */
    private boolean isKeywordContext() {
        // Keywords are expected where a new expression can begin,
        // or after a condition (RPAREN for 'then'/'else' after 'if')
        // or after type keywords (INSTANCE, CAST, CASTABLE, TREAT for 'of'/'as')
        // or after complete expressions (NUMBER, STRING_LITERAL for 'return' in 'for $i in 1 to 5 return')
        if (previousToken == null) {
            return true;
        }
        switch (previousToken) {
            case LPAREN:
            case LBRACKET:
            case RPAREN:    // For 'then' after 'if (condition)'
            case RBRACKET:  // For 'else' after predicate like 'then foo[1]'
            // NOT DOT or DOUBLE_DOT - after '.' or '..', next is typically step, not keyword
            case COMMA:
            case PLUS:
            case MINUS:
            case STAR:
            case EQUALS:
            case NOT_EQUALS:
            case LESS_THAN:
            case LESS_THAN_OR_EQUAL:
            case GREATER_THAN:
            case GREATER_THAN_OR_EQUAL:
            case AND:
            case OR:
            case PIPE:
            // NOT SLASH or DOUBLE_SLASH - after '/' or '//', we expect a path step (name or node test)
            // Keywords like 'in', 'if', 'for' are NOT valid path steps, they're NCNames here
            case RETURN:
            case THEN:
            case ELSE:
            case SATISFIES:
            case ASSIGN:
            case IN:
            case BANG:
            case CONCAT:
            case ARROW:
            // Type expression keywords - 'of' follows 'instance', 'as' follows 'cast'/'castable'/'treat'
            case INSTANCE:
            case CAST:
            case CASTABLE:
            case TREAT:
            case OF:        // For sequence type after 'instance of'
            case AS:        // For sequence type after 'cast as', etc.
            case NCNAME:    // For 'in' after variable name in for/some/every $var in ...
            case DOLLAR:    // For keywords that can appear after $
            // Keywords can follow complete expressions (for 'return' after sequence in 'for $i in expr return')
            case NUMBER_LITERAL:// For 'return' after 'for $i in 1 to 5 return'
            case STRING_LITERAL:// For 'return' after 'for $i in ("a","b") return'
            case TO:            // For 'return' after range (when no end value specified yet)
                return true;
            default:
                return false;
        }
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
            // XPath 2.0/3.0 sequence type keywords (only valid when followed by '(')
            case "empty-sequence": return XPathToken.EMPTY_SEQUENCE;
            case "item": return XPathToken.ITEM;
            case "element": return XPathToken.ELEMENT;
            case "attribute": return XPathToken.ATTRIBUTE;
            case "schema-element": return XPathToken.SCHEMA_ELEMENT;
            case "schema-attribute": return XPathToken.SCHEMA_ATTRIBUTE;
            case "document-node": return XPathToken.DOCUMENT_NODE;
            default: return null;
        }
    }

    private XPathToken getOperatorToken(String name) {
        switch (name) {
            case "and": return XPathToken.AND;
            case "or": return XPathToken.OR;
            case "mod": return XPathToken.MOD;
            case "div": return XPathToken.DIV;
            case "idiv": return XPathToken.IDIV;  // XPath 2.0 integer division
            case "to": return XPathToken.TO;
            case "eq": return XPathToken.EQ;
            case "ne": return XPathToken.NE;
            case "lt": return XPathToken.LT;
            case "le": return XPathToken.LE;
            case "gt": return XPathToken.GT;
            case "ge": return XPathToken.GE;
            // XPath 2.0 node comparison
            case "is": return XPathToken.IS;
            // XPath 2.0 set operators
            case "union": return XPathToken.UNION;
            case "intersect": return XPathToken.INTERSECT;
            case "except": return XPathToken.EXCEPT;
            // XPath 2.0 type expression keywords (appear after expressions)
            case "instance": return XPathToken.INSTANCE;
            case "cast": return XPathToken.CAST;
            case "castable": return XPathToken.CASTABLE;
            case "treat": return XPathToken.TREAT;
            default: return null;
        }
    }

    /**
     * Returns the keyword token for XPath 2.0/3.0 expression keywords.
     * These are not operators but structural keywords.
     * Note: Sequence type keywords (item, element, attribute, etc.) are handled
     * in getNodeTypeToken() since they're only valid when followed by '('.
     */
    private XPathToken getKeywordToken(String name) {
        switch (name) {
            case "if": return XPathToken.IF;
            case "then": return XPathToken.THEN;
            case "else": return XPathToken.ELSE;
            case "for": return XPathToken.FOR;
            case "let": return XPathToken.LET;
            case "return": return XPathToken.RETURN;
            case "in": return XPathToken.IN;
            case "some": return XPathToken.SOME;
            case "every": return XPathToken.EVERY;
            case "satisfies": return XPathToken.SATISFIES;
            // XPath 2.0 type expression keywords
            case "of": return XPathToken.OF;
            case "as": return XPathToken.AS;
            default: return null;
        }
    }

    /**
     * Determines if the current context expects an operator.
     * Used to disambiguate 'and', 'or', 'div', 'mod', 'intersect', 'except' from names.
     */
    private boolean isOperatorContext() {
        // An operator is expected after these tokens (complete expressions)
        if (previousToken == null) {
            return false;
        }
        switch (previousToken) {
            case RPAREN:
            case RBRACKET:
            case NCNAME:
            case STRING_LITERAL:
            case NUMBER_LITERAL:
            case NODE_TYPE_COMMENT:
            case NODE_TYPE_TEXT:
            case NODE_TYPE_PI:
            case NODE_TYPE_NODE:
            case DOT:         // '.' represents an expression (context node)
            case DOUBLE_DOT:  // '..' represents an expression (parent node)
            case STAR:        // '*' as name test (wildcard) - operators can follow
                return true;
            // Note: STAR_MULTIPLY is NOT included - after multiplication, operands follow, not operators
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

    /**
     * Restores the lexer state.
     * Used for lookahead operations that need to revert the lexer to a previous state.
     *
     * @param pos the position to restore
     * @param token the current token to restore
     * @param value the current value to restore
     * @param start the token start position to restore
     */
    public void restore(int pos, XPathToken token, String value, int start) {
        this.position = pos;
        this.currentToken = token;
        this.currentValue = value;
        this.tokenStart = start;
    }

}

/*
 * XPathToken.java
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
 * Token types for XPath 1.0 lexical analysis.
 *
 * <p>The XPath lexer produces a stream of these tokens from an XPath expression
 * string. The tokens correspond to the terminal symbols in the XPath grammar.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public enum XPathToken {

    // Literals
    /** A string literal (single or double quoted). */
    STRING_LITERAL,
    
    /** A numeric literal (integer or decimal). */
    NUMBER_LITERAL,

    // Names and identifiers
    /** An NCName (non-colonized name). */
    NCNAME,
    
    /** A QName prefix (NCName followed by colon). */
    PREFIX,

    /** XPath 3.0 URIQualifiedName: Q{uri}local. Value is "{uri}local" (Clark notation). */
    URIQNAME,
    
    /** The wildcard '*' used in name tests. */
    STAR,
    
    /** The '*' used as multiplication operator. */
    STAR_MULTIPLY,

    // Axis specifiers (recognized by name + ::)
    /** ancestor:: axis. */
    AXIS_ANCESTOR,
    /** ancestor-or-self:: axis. */
    AXIS_ANCESTOR_OR_SELF,
    /** attribute:: axis. */
    AXIS_ATTRIBUTE,
    /** child:: axis. */
    AXIS_CHILD,
    /** descendant:: axis. */
    AXIS_DESCENDANT,
    /** descendant-or-self:: axis. */
    AXIS_DESCENDANT_OR_SELF,
    /** following:: axis. */
    AXIS_FOLLOWING,
    /** following-sibling:: axis. */
    AXIS_FOLLOWING_SIBLING,
    /** namespace:: axis. */
    AXIS_NAMESPACE,
    /** parent:: axis. */
    AXIS_PARENT,
    /** preceding:: axis. */
    AXIS_PRECEDING,
    /** preceding-sibling:: axis. */
    AXIS_PRECEDING_SIBLING,
    /** self:: axis. */
    AXIS_SELF,

    // Node type tests
    /** comment() node test. */
    NODE_TYPE_COMMENT,
    /** text() node test. */
    NODE_TYPE_TEXT,
    /** processing-instruction() node test. */
    NODE_TYPE_PI,
    /** node() node test. */
    NODE_TYPE_NODE,

    // Operators
    /** The 'and' operator. */
    AND,
    /** The 'or' operator. */
    OR,
    /** The 'mod' operator. */
    MOD,
    IDIV,  // XPath 2.0 integer division
    /** The 'div' operator. */
    DIV,
    /** The 'to' range operator (XPath 2.0). */
    TO,
    
    // XPath 2.0 value comparison operators
    /** The 'eq' value equals operator. */
    EQ,
    /** The 'ne' value not-equals operator. */
    NE,
    /** The 'lt' value less-than operator. */
    LT,
    /** The 'le' value less-than-or-equal operator. */
    LE,
    /** The 'gt' value greater-than operator. */
    GT,
    /** The 'ge' value greater-than-or-equal operator. */
    GE,
    
    // XPath 2.0 node comparison operators
    /** The 'is' node identity operator. */
    IS,
    /** The '&lt;&lt;' node precedes operator. */
    PRECEDES,
    /** The '&gt;&gt;' node follows operator. */
    FOLLOWS,
    
    // XPath 2.0 set operators
    /** The 'intersect' set intersection operator. */
    INTERSECT,
    /** The 'except' set difference operator. */
    EXCEPT,
    
    // XPath 2.0 expression keywords
    /** The 'if' keyword. */
    IF,
    /** The 'then' keyword. */
    THEN,
    /** The 'else' keyword. */
    ELSE,
    /** The 'for' keyword. */
    FOR,
    /** The 'let' keyword (XPath 3.0). */
    LET,
    /** The 'return' keyword. */
    RETURN,
    /** The 'in' keyword. */
    IN,
    /** The 'some' keyword. */
    SOME,
    /** The 'every' keyword. */
    EVERY,
    /** The 'satisfies' keyword. */
    SATISFIES,
    /** The ':=' assignment operator. */
    ASSIGN,
    
    // XPath 2.0 type expression keywords
    /** The 'instance' keyword (part of 'instance of'). */
    INSTANCE,
    /** The 'of' keyword (part of 'instance of'). */
    OF,
    /** The 'cast' keyword (part of 'cast as'). */
    CAST,
    /** The 'as' keyword (part of 'cast as', 'treat as', 'castable as'). */
    AS,
    /** The 'castable' keyword (part of 'castable as'). */
    CASTABLE,
    /** The 'treat' keyword (part of 'treat as'). */
    TREAT,
    
    // XPath 2.0 sequence type keywords
    /** The 'empty-sequence' keyword. */
    EMPTY_SEQUENCE,
    /** The 'item' keyword (for item() type). */
    ITEM,
    /** The 'element' keyword (for element() type). */
    ELEMENT,
    /** The 'attribute' keyword (for attribute() type). */
    ATTRIBUTE,
    /** The 'schema-element' keyword. */
    SCHEMA_ELEMENT,
    /** The 'schema-attribute' keyword. */
    SCHEMA_ATTRIBUTE,
    /** The 'document-node' keyword. */
    DOCUMENT_NODE,
    /** The 'namespace-node' keyword. */
    NAMESPACE_NODE,
    
    // XPath 2.0 occurrence indicators
    /** The '?' occurrence indicator (zero-or-one). */
    QUESTION,
    
    // XPath 3.0 operators
    /** The '||' string concatenation operator. */
    CONCAT,
    /** The '!' simple map operator. */
    BANG,
    /** The '=>' arrow operator. */
    ARROW,
    /** The '#' named function reference operator (XPath 3.0). */
    HASH,
    
    /** The '=' operator. */
    EQUALS,
    /** The '!=' operator. */
    NOT_EQUALS,
    /** The '&lt;' operator. */
    LESS_THAN,
    /** The '&lt;=' operator. */
    LESS_THAN_OR_EQUAL,
    /** The '&gt;' operator. */
    GREATER_THAN,
    /** The '&gt;=' operator. */
    GREATER_THAN_OR_EQUAL,
    
    /** The '+' operator. */
    PLUS,
    /** The '-' operator. */
    MINUS,
    
    /** The '|' union operator. */
    PIPE,
    
    /** The 'union' keyword (synonym for |). */
    UNION,

    // Path operators
    /** The '/' path separator. */
    SLASH,
    /** The '//' abbreviated descendant-or-self. */
    DOUBLE_SLASH,
    /** The '.' current node abbreviation. */
    DOT,
    /** The '..' parent abbreviation. */
    DOUBLE_DOT,
    /** The '@' attribute axis abbreviation. */
    AT,
    /** The '::' axis separator. */
    DOUBLE_COLON,
    /** The ':' namespace separator in QNames. */
    COLON,

    // Grouping and delimiters
    /** The '(' left parenthesis. */
    LPAREN,
    /** The ')' right parenthesis. */
    RPAREN,
    /** The '[' left bracket (predicate start). */
    LBRACKET,
    /** The ']' right bracket (predicate end). */
    RBRACKET,
    /** The ',' comma (function argument separator). */
    COMMA,
    /** The '{' left accolade (XPath 3.1 map/array constructor). */
    LBRACE,
    /** The '}' right accolade (XPath 3.1 map/array constructor). */
    RBRACE,

    // Variable reference
    /** The '$' variable prefix. */
    DOLLAR,

    // End of expression
    /** End of input. */
    EOF,

    // Error token
    /** Unrecognized character. */
    ERROR;

    /**
     * Returns true if this is an operator token.
     *
     * @return true for operators
     */
    public boolean isOperator() {
        switch (this) {
            case AND:
            case OR:
            case MOD:
            case DIV:
            case EQUALS:
            case NOT_EQUALS:
            case LESS_THAN:
            case LESS_THAN_OR_EQUAL:
            case GREATER_THAN:
            case GREATER_THAN_OR_EQUAL:
            case PLUS:
            case MINUS:
            case PIPE:
            case UNION:
            case INTERSECT:
            case EXCEPT:
                return true;
            default:
                return false;
        }
    }

    /**
     * Returns true if this is an axis token.
     *
     * @return true for axis specifiers
     */
    public boolean isAxis() {
        switch (this) {
            case AXIS_ANCESTOR:
            case AXIS_ANCESTOR_OR_SELF:
            case AXIS_ATTRIBUTE:
            case AXIS_CHILD:
            case AXIS_DESCENDANT:
            case AXIS_DESCENDANT_OR_SELF:
            case AXIS_FOLLOWING:
            case AXIS_FOLLOWING_SIBLING:
            case AXIS_NAMESPACE:
            case AXIS_PARENT:
            case AXIS_PRECEDING:
            case AXIS_PRECEDING_SIBLING:
            case AXIS_SELF:
                return true;
            default:
                return false;
        }
    }

    /**
     * Returns true if this is a node type test token.
     *
     * @return true for node type tests
     */
    public boolean isNodeType() {
        switch (this) {
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
     * Returns true if this token can start a relative location path.
     *
     * @return true if valid path start
     */
    public boolean canStartStep() {
        switch (this) {
            case NCNAME:
            case URIQNAME:
            case STAR:
            case AT:
            case DOT:
            case DOUBLE_DOT:
            case NODE_TYPE_COMMENT:
            case NODE_TYPE_TEXT:
            case NODE_TYPE_PI:
            case NODE_TYPE_NODE:
            // XPath 2.0 kind tests
            case ELEMENT:
            case ATTRIBUTE:
            case DOCUMENT_NODE:
            case NAMESPACE_NODE:
            case SCHEMA_ELEMENT:
            case SCHEMA_ATTRIBUTE:
                return true;
            default:
                return isAxis();
        }
    }

}

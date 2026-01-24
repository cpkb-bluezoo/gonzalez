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
    
    /** The wildcard '*' used in name tests. */
    STAR,

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
    /** The 'div' operator. */
    DIV,
    
    /** The '=' operator. */
    EQUALS,
    /** The '!=' operator. */
    NOT_EQUALS,
    /** The '<' operator. */
    LESS_THAN,
    /** The '<=' operator. */
    LESS_THAN_OR_EQUAL,
    /** The '>' operator. */
    GREATER_THAN,
    /** The '>=' operator. */
    GREATER_THAN_OR_EQUAL,
    
    /** The '+' operator. */
    PLUS,
    /** The '-' operator. */
    MINUS,
    
    /** The '|' union operator. */
    PIPE,

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
            case STAR:
            case AT:
            case DOT:
            case DOUBLE_DOT:
            case NODE_TYPE_COMMENT:
            case NODE_TYPE_TEXT:
            case NODE_TYPE_PI:
            case NODE_TYPE_NODE:
                return true;
            default:
                return isAxis();
        }
    }

}

/*
 * Operator.java
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

package org.bluezoo.gonzalez.transform.xpath.expr;

import org.bluezoo.gonzalez.transform.xpath.XPathToken;

/**
 * XPath binary operators with their precedence levels.
 *
 * <p>Precedence levels (higher number = higher precedence):
 * <ol>
 *   <li>or (1)</li>
 *   <li>and (2)</li>
 *   <li>=, != (3)</li>
 *   <li>&lt;, &gt;, &lt;=, &gt;= (4)</li>
 *   <li>+, - (5)</li>
 *   <li>*, div, mod (6)</li>
 *   <li>| union (7)</li>
 * </ol>
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public enum Operator {

    // Logical operators (lowest precedence)
    OR("or", 1),
    AND("and", 2),

    // Equality operators
    EQUALS("=", 3),
    NOT_EQUALS("!=", 3),

    // Relational operators
    LESS_THAN("<", 4),
    LESS_THAN_OR_EQUAL("<=", 4),
    GREATER_THAN(">", 4),
    GREATER_THAN_OR_EQUAL(">=", 4),

    // Additive operators
    PLUS("+", 5),
    MINUS("-", 5),

    // Multiplicative operators
    MULTIPLY("*", 6),
    DIV("div", 6),
    MOD("mod", 6),

    // Union operator (highest binary precedence)
    UNION("|", 7),
    
    // XPath 2.0 range operator (between additive and multiplicative)
    TO("to", 5),
    
    // XPath 2.0 value comparison operators (same precedence as general comparison)
    VALUE_EQUALS("eq", 3),
    VALUE_NOT_EQUALS("ne", 3),
    VALUE_LESS_THAN("lt", 4),
    VALUE_LESS_THAN_OR_EQUAL("le", 4),
    VALUE_GREATER_THAN("gt", 4),
    VALUE_GREATER_THAN_OR_EQUAL("ge", 4),
    
    // XPath 2.0 node comparison operators (same precedence as value comparison)
    NODE_IS("is", 3),
    NODE_PRECEDES("<<", 3),
    NODE_FOLLOWS(">>", 3),
    
    // XPath 2.0 set operators (between union and path)
    INTERSECT("intersect", 8),
    EXCEPT("except", 8),
    
    // XPath 2.0 type operators (in order of precedence)
    INSTANCE_OF("instance of", 9),
    TREAT_AS("treat as", 10),
    CASTABLE_AS("castable as", 11),
    CAST_AS("cast as", 12),
    
    // XPath 3.0 string concatenation (between additive and comparison)
    STRING_CONCAT("||", 4),
    
    // XPath 3.0 simple map operator (highest precedence)
    SIMPLE_MAP("!", 13),
    
    // XPath 3.0 arrow operator (very high precedence, right-associative)
    ARROW("=>", 14);

    private final String symbol;
    private final int precedence;

    Operator(String symbol, int precedence) {
        this.symbol = symbol;
        this.precedence = precedence;
    }

    /**
     * Returns the operator symbol.
     *
     * @return the symbol
     */
    public String getSymbol() {
        return symbol;
    }

    /**
     * Returns the operator precedence (higher = binds tighter).
     *
     * @return the precedence level
     */
    public int getPrecedence() {
        return precedence;
    }

    /**
     * Returns true if this is a comparison operator.
     *
     * @return true for =, !=, &lt;, &lt;=, &gt;, &gt;=
     */
    public boolean isComparison() {
        return this == EQUALS || this == NOT_EQUALS ||
               this == LESS_THAN || this == LESS_THAN_OR_EQUAL ||
               this == GREATER_THAN || this == GREATER_THAN_OR_EQUAL;
    }

    /**
     * Returns true if this is an arithmetic operator.
     *
     * @return true for +, -, *, div, mod
     */
    public boolean isArithmetic() {
        return this == PLUS || this == MINUS ||
               this == MULTIPLY || this == DIV || this == MOD;
    }

    /**
     * Returns true if this is a logical operator.
     *
     * @return true for and, or
     */
    public boolean isLogical() {
        return this == AND || this == OR;
    }

    /**
     * Converts an XPath token to an operator.
     *
     * @param token the token
     * @return the operator, or null if not an operator token
     */
    public static Operator fromToken(XPathToken token) {
        switch (token) {
            case OR: return OR;
            case AND: return AND;
            case EQUALS: return EQUALS;
            case NOT_EQUALS: return NOT_EQUALS;
            case LESS_THAN: return LESS_THAN;
            case LESS_THAN_OR_EQUAL: return LESS_THAN_OR_EQUAL;
            case GREATER_THAN: return GREATER_THAN;
            case GREATER_THAN_OR_EQUAL: return GREATER_THAN_OR_EQUAL;
            case PLUS: return PLUS;
            case MINUS: return MINUS;
            case STAR: return MULTIPLY;
            case DIV: return DIV;
            case MOD: return MOD;
            case PIPE: return UNION;
            case UNION: return UNION;  // 'union' keyword (synonym for |)
            case TO: return TO;
            case EQ: return VALUE_EQUALS;
            case NE: return VALUE_NOT_EQUALS;
            case LT: return VALUE_LESS_THAN;
            case LE: return VALUE_LESS_THAN_OR_EQUAL;
            case GT: return VALUE_GREATER_THAN;
            case GE: return VALUE_GREATER_THAN_OR_EQUAL;
            // XPath 2.0 node comparison
            case IS: return NODE_IS;
            case PRECEDES: return NODE_PRECEDES;
            case FOLLOWS: return NODE_FOLLOWS;
            // XPath 2.0 set operators
            case INTERSECT: return INTERSECT;
            case EXCEPT: return EXCEPT;
            // XPath 3.0 operators
            case CONCAT: return STRING_CONCAT;
            case BANG: return SIMPLE_MAP;
            case ARROW: return ARROW;
            default: return null;
        }
    }
    
    /**
     * Returns true if this is an XPath 2.0 node comparison operator.
     *
     * @return true for is, &lt;&lt;, &gt;&gt;
     */
    public boolean isNodeComparison() {
        return this == NODE_IS || this == NODE_PRECEDES || this == NODE_FOLLOWS;
    }
    
    /**
     * Returns true if this is an XPath 2.0 set operator.
     *
     * @return true for intersect, except
     */
    public boolean isSetOperator() {
        return this == INTERSECT || this == EXCEPT || this == UNION;
    }

    @Override
    public String toString() {
        return symbol;
    }

}

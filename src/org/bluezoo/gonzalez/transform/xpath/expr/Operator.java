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
    UNION("|", 7);

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
     * @return true for =, !=, <, <=, >, >=
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
            default: return null;
        }
    }

    @Override
    public String toString() {
        return symbol;
    }

}

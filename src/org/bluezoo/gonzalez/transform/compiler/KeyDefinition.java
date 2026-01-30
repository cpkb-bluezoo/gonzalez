/*
 * KeyDefinition.java
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

package org.bluezoo.gonzalez.transform.compiler;

import org.bluezoo.gonzalez.QName;
import org.bluezoo.gonzalez.transform.xpath.XPathExpression;

/**
 * A key definition (xsl:key).
 *
 * <p>Keys provide indexed access to nodes, enabling efficient lookup
 * via the key() function.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class KeyDefinition {

    private final QName name;
    private final Pattern matchPattern;
    private final XPathExpression useExpr;

    /**
     * Creates a key definition.
     *
     * @param name the key name (as a QName with resolved namespace)
     * @param matchPattern the match pattern
     * @param useExpr the use expression
     */
    public KeyDefinition(QName name, Pattern matchPattern, XPathExpression useExpr) {
        this.name = name;
        this.matchPattern = matchPattern;
        this.useExpr = useExpr;
    }

    /**
     * Returns the key name as a QName.
     *
     * @return the name
     */
    public QName getName() {
        return name;
    }
    
    /**
     * Returns the expanded key name in Clark notation {uri}local.
     * This is used for lookup matching since key() calls also expand their names.
     *
     * @return the expanded name string
     */
    public String getExpandedName() {
        return name.toString();
    }

    /**
     * Returns the match pattern.
     *
     * @return the pattern
     */
    public Pattern getMatchPattern() {
        return matchPattern;
    }

    /**
     * Returns the use expression.
     *
     * @return the expression
     */
    public XPathExpression getUseExpr() {
        return useExpr;
    }

    @Override
    public String toString() {
        return "key " + name;
    }

}

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
import org.bluezoo.gonzalez.transform.ast.SequenceNode;
import org.bluezoo.gonzalez.transform.xpath.XPathExpression;

/**
 * A key definition (xsl:key).
 *
 * <p>Keys provide indexed access to nodes, enabling efficient lookup
 * via the key() function. A key definition has either a {@code use}
 * expression or content constructors (XSLT 2.0+), but not both.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class KeyDefinition {

    private final QName name;
    private final Pattern matchPattern;
    private final XPathExpression useExpr;
    private final SequenceNode content;
    private final boolean composite;
    private final String collation;

    /**
     * Creates a key definition with a use expression.
     *
     * @param name the key name (as a QName with resolved namespace)
     * @param matchPattern the match pattern
     * @param useExpr the use expression
     */
    public KeyDefinition(QName name, Pattern matchPattern, XPathExpression useExpr) {
        this(name, matchPattern, useExpr, null, false, null);
    }

    /**
     * Creates a key definition with optional content constructors and composite flag.
     *
     * @param name the key name (as a QName with resolved namespace)
     * @param matchPattern the match pattern
     * @param useExpr the use expression (null if content constructors are used)
     * @param content the content constructors (null if use expression is provided)
     * @param composite true if this is a composite key (XSLT 3.0)
     */
    public KeyDefinition(QName name, Pattern matchPattern, XPathExpression useExpr,
                         SequenceNode content, boolean composite) {
        this(name, matchPattern, useExpr, content, composite, null);
    }

    /**
     * Creates a key definition with all attributes including collation.
     *
     * @param name the key name (as a QName with resolved namespace)
     * @param matchPattern the match pattern
     * @param useExpr the use expression (null if content constructors are used)
     * @param content the content constructors (null if use expression is provided)
     * @param composite true if this is a composite key (XSLT 3.0)
     * @param collation the collation URI for key comparisons, or null for default
     */
    public KeyDefinition(QName name, Pattern matchPattern, XPathExpression useExpr,
                         SequenceNode content, boolean composite, String collation) {
        this.name = name;
        this.matchPattern = matchPattern;
        this.useExpr = useExpr;
        this.content = content;
        this.composite = composite;
        this.collation = collation;
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
     * Returns the use expression, or null if content constructors are used.
     *
     * @return the expression, or null
     */
    public XPathExpression getUseExpr() {
        return useExpr;
    }

    /**
     * Returns the content constructors, or null if a use expression is provided.
     *
     * @return the content sequence node, or null
     */
    public SequenceNode getContent() {
        return content;
    }

    /**
     * Returns whether this is a composite key (XSLT 3.0).
     * When true, the key value is a sequence treated as a tuple for matching.
     *
     * @return true if composite
     */
    public boolean isComposite() {
        return composite;
    }

    /**
     * Returns the collation URI for this key, or null if using the default collation.
     *
     * @return the collation URI, or null
     */
    public String getCollation() {
        return collation;
    }

    @Override
    public String toString() {
        return "key " + name;
    }

}

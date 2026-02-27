/*
 * VariableReference.java
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

import org.bluezoo.gonzalez.transform.xpath.XPathContext;
import org.bluezoo.gonzalez.transform.xpath.XPathVariableException;
import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;

/**
 * A variable reference expression ($name).
 *
 * <p>Variable references look up a variable in the evaluation context and
 * return its value. The variable name may be a QName with a namespace prefix.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class VariableReference implements Expr {

    private final String prefix;
    private final String localName;
    private final String resolvedNamespaceURI;

    /**
     * Creates a variable reference with no namespace prefix.
     *
     * @param localName the variable name
     */
    public VariableReference(String localName) {
        this(null, localName, null);
    }

    /**
     * Creates a variable reference with a namespace prefix.
     *
     * @param prefix the namespace prefix (may be null)
     * @param localName the variable local name
     */
    public VariableReference(String prefix, String localName) {
        this(prefix, localName, null);
    }

    /**
     * Creates a variable reference with a pre-resolved namespace URI.
     *
     * @param prefix the namespace prefix (may be null)
     * @param localName the variable local name
     * @param resolvedNamespaceURI the pre-resolved namespace URI (may be null)
     */
    public VariableReference(String prefix, String localName, String resolvedNamespaceURI) {
        if (localName == null || localName.isEmpty()) {
            throw new IllegalArgumentException("Variable name cannot be null or empty");
        }
        this.prefix = prefix;
        this.localName = localName;
        this.resolvedNamespaceURI = resolvedNamespaceURI;
    }

    @Override
    public XPathValue evaluate(XPathContext context) throws XPathException {
        String namespaceURI = resolvedNamespaceURI;
        if (namespaceURI == null && prefix != null && !prefix.isEmpty()) {
            namespaceURI = context.resolveNamespacePrefix(prefix);
            if (namespaceURI == null) {
                throw new XPathException("Unknown namespace prefix: " + prefix);
            }
        }
        
        try {
            return context.getVariable(namespaceURI, localName);
        } catch (XPathVariableException e) {
            throw new XPathException("Undefined variable: $" + 
                (prefix != null ? prefix + ":" : "") + localName, e);
        }
    }

    /**
     * Returns the namespace prefix.
     *
     * @return the prefix, or null if none
     */
    public String getPrefix() {
        return prefix;
    }

    /**
     * Returns the variable local name.
     *
     * @return the local name
     */
    public String getLocalName() {
        return localName;
    }

    @Override
    public String toString() {
        if (prefix != null && !prefix.isEmpty()) {
            return "$" + prefix + ":" + localName;
        }
        return "$" + localName;
    }

}

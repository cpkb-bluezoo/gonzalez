/*
 * NamedFunctionRefExpr.java
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
import org.bluezoo.gonzalez.transform.xpath.XPathFunctionLibrary;
import org.bluezoo.gonzalez.transform.xpath.type.XPathFunctionItem;
import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;

/**
 * XPath 3.0 named function reference: {@code name#arity}.
 *
 * <p>Evaluates to a function item that can be passed around as a value
 * and invoked via dynamic function calls. The function is resolved at
 * evaluation time from the function library.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class NamedFunctionRefExpr implements Expr {

    private final String prefix;
    private final String localName;
    private final String resolvedURI;
    private final int arity;

    /**
     * Creates a named function reference expression.
     *
     * @param prefix the namespace prefix (may be null)
     * @param localName the function local name
     * @param arity the expected arity
     */
    public NamedFunctionRefExpr(String prefix, String localName, int arity) {
        this.prefix = prefix;
        this.localName = localName;
        this.resolvedURI = null;
        this.arity = arity;
    }

    /**
     * Creates a named function reference with a resolved namespace URI.
     *
     * @param prefix the namespace prefix (may be null)
     * @param localName the function local name
     * @param resolvedURI the resolved namespace URI (may be null)
     * @param arity the expected arity
     */
    public NamedFunctionRefExpr(String prefix, String localName, String resolvedURI, int arity) {
        this.prefix = prefix;
        this.localName = localName;
        this.resolvedURI = resolvedURI;
        this.arity = arity;
    }

    @Override
    public XPathValue evaluate(XPathContext context) throws XPathException {
        String fullName;
        if (prefix != null && !prefix.isEmpty()) {
            fullName = prefix + ":" + localName;
        } else {
            fullName = localName;
        }
        
        XPathFunctionLibrary library = context.getFunctionLibrary();
        if (library == null) {
            throw new XPathException("XPST0017: No function library available for " + fullName + "#" + arity);
        }

        // Determine the effective namespace URI for lookup
        String effectiveURI = resolvedURI;
        if (effectiveURI == null && prefix != null && !prefix.isEmpty()) {
            effectiveURI = context.resolveNamespacePrefix(prefix);
        }
        
        // Check built-in functions first (use fullName for unprefixed)
        if (library.hasFunction(effectiveURI, localName)) {
            return new XPathFunctionItem(fullName, effectiveURI, arity, library);
        }
        if (library.hasFunction(null, fullName)) {
            return new XPathFunctionItem(fullName, effectiveURI, arity, library);
        }
        
        // For namespaced functions, the function library may resolve user-defined
        // functions at invoke time — return a function item that will do that
        if (effectiveURI != null && !effectiveURI.isEmpty()) {
            return new XPathFunctionItem(fullName, effectiveURI, arity, library);
        }
        
        throw new XPathException("XPST0017: Unknown function: " + fullName + "#" + arity);
    }

    @Override
    public String toString() {
        if (prefix != null && !prefix.isEmpty()) {
            return prefix + ":" + localName + "#" + arity;
        }
        return localName + "#" + arity;
    }
}

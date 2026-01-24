/*
 * FunctionCall.java
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
import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A function call expression.
 *
 * <p>Function calls consist of a function name and a list of argument expressions.
 * The function is looked up in the function library and invoked with the
 * evaluated arguments.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class FunctionCall implements Expr {

    private final String prefix;
    private final String localName;
    private final List<Expr> arguments;

    /**
     * Creates a function call with no namespace prefix.
     *
     * @param localName the function name
     * @param arguments the argument expressions
     */
    public FunctionCall(String localName, List<Expr> arguments) {
        this(null, localName, arguments);
    }

    /**
     * Creates a function call with a namespace prefix.
     *
     * @param prefix the namespace prefix (may be null)
     * @param localName the function local name
     * @param arguments the argument expressions
     */
    public FunctionCall(String prefix, String localName, List<Expr> arguments) {
        if (localName == null || localName.isEmpty()) {
            throw new IllegalArgumentException("Function name cannot be null or empty");
        }
        this.prefix = prefix;
        this.localName = localName;
        this.arguments = arguments != null ? 
            Collections.unmodifiableList(new ArrayList<>(arguments)) : 
            Collections.emptyList();
    }

    @Override
    public XPathValue evaluate(XPathContext context) throws XPathException {
        // Resolve namespace prefix
        String namespaceURI = null;
        if (prefix != null && !prefix.isEmpty()) {
            namespaceURI = context.resolveNamespacePrefix(prefix);
            if (namespaceURI == null) {
                throw new XPathException("Unknown namespace prefix: " + prefix);
            }
        }

        // Get the function library
        XPathFunctionLibrary library = context.getFunctionLibrary();
        if (library == null) {
            throw new XPathException("No function library available");
        }

        // Check if function exists
        if (!library.hasFunction(namespaceURI, localName)) {
            throw new XPathException("Unknown function: " + 
                (prefix != null ? prefix + ":" : "") + localName);
        }

        // Evaluate arguments
        List<XPathValue> argValues = new ArrayList<>(arguments.size());
        for (Expr arg : arguments) {
            argValues.add(arg.evaluate(context));
        }

        // Invoke the function
        return library.invokeFunction(namespaceURI, localName, argValues, context);
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
     * Returns the function local name.
     *
     * @return the local name
     */
    public String getLocalName() {
        return localName;
    }

    /**
     * Returns the argument expressions.
     *
     * @return the arguments (immutable)
     */
    public List<Expr> getArguments() {
        return arguments;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        if (prefix != null && !prefix.isEmpty()) {
            sb.append(prefix).append(':');
        }
        sb.append(localName).append('(');
        
        boolean first = true;
        for (Expr arg : arguments) {
            if (!first) {
                sb.append(", ");
            }
            sb.append(arg);
            first = false;
        }
        
        sb.append(')');
        return sb.toString();
    }

}

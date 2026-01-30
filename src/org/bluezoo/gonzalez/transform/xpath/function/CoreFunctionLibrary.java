/*
 * CoreFunctionLibrary.java
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

package org.bluezoo.gonzalez.transform.xpath.function;

import org.bluezoo.gonzalez.transform.xpath.XPathContext;
import org.bluezoo.gonzalez.transform.xpath.XPathFunctionLibrary;
import org.bluezoo.gonzalez.transform.xpath.expr.XPathException;
import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Implementation of the XPath 1.0 core function library.
 *
 * <p>Contains all 27 core functions defined in XPath 1.0:
 * <ul>
 *   <li>Node-set functions: last, position, count, id, local-name, namespace-uri, name</li>
 *   <li>String functions: string, concat, starts-with, contains, substring-before,
 *       substring-after, substring, string-length, normalize-space, translate</li>
 *   <li>Boolean functions: boolean, not, true, false, lang</li>
 *   <li>Number functions: number, sum, floor, ceiling, round</li>
 * </ul>
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class CoreFunctionLibrary implements XPathFunctionLibrary {

    /** Singleton instance. */
    public static final CoreFunctionLibrary INSTANCE = new CoreFunctionLibrary();

    private final Map<String, Function> functions;

    private CoreFunctionLibrary() {
        Map<String, Function> map = new HashMap<>();
        
        // Register all functions
        for (Function f : NodeSetFunctions.getAll()) {
            map.put(f.getName(), f);
        }
        for (Function f : StringFunctions.getAll()) {
            map.put(f.getName(), f);
        }
        for (Function f : BooleanFunctions.getAll()) {
            map.put(f.getName(), f);
        }
        for (Function f : NumberFunctions.getAll()) {
            map.put(f.getName(), f);
        }
        // XPath 2.0/3.0 date/time functions
        for (Function f : DateTimeFunctions.getAll()) {
            map.put(f.getName(), f);
        }
        // XPath 2.0/3.0 sequence functions
        for (Function f : SequenceFunctions.getAll()) {
            map.put(f.getName(), f);
        }
        
        this.functions = Collections.unmodifiableMap(map);
    }

    @Override
    public boolean hasFunction(String namespaceURI, String localName) {
        // Core functions have no namespace
        if (namespaceURI != null && !namespaceURI.isEmpty()) {
            return false;
        }
        return functions.containsKey(localName);
    }

    @Override
    public XPathValue invokeFunction(String namespaceURI, String localName,
                                     List<XPathValue> args, XPathContext context) throws XPathException {
        if (namespaceURI != null && !namespaceURI.isEmpty()) {
            throw new XPathException("Unknown function: {" + namespaceURI + "}" + localName);
        }
        
        Function function = functions.get(localName);
        if (function == null) {
            throw new XPathException("Unknown function: " + localName);
        }
        
        // Validate argument count
        int argCount = args.size();
        if (argCount < function.getMinArgs()) {
            throw new XPathException("Function " + localName + " requires at least " +
                function.getMinArgs() + " argument(s), got " + argCount);
        }
        if (argCount > function.getMaxArgs()) {
            throw new XPathException("Function " + localName + " accepts at most " +
                function.getMaxArgs() + " argument(s), got " + argCount);
        }
        
        return function.evaluate(args, context);
    }

    @Override
    public int getArgumentCount(String namespaceURI, String localName) {
        if (namespaceURI != null && !namespaceURI.isEmpty()) {
            return -1;
        }
        Function function = functions.get(localName);
        if (function == null) {
            return -1;
        }
        // Return -1 for variable argument functions
        if (function.getMaxArgs() == Integer.MAX_VALUE) {
            return -1;
        }
        // If min != max, return -1 (optional args)
        if (function.getMinArgs() != function.getMaxArgs()) {
            return -1;
        }
        return function.getMinArgs();
    }

    /**
     * Returns the function with the given name.
     *
     * @param name the function name
     * @return the function, or null if not found
     */
    public Function getFunction(String name) {
        return functions.get(name);
    }

    /**
     * Returns all registered functions.
     *
     * @return map of function name to function (unmodifiable)
     */
    public Map<String, Function> getAllFunctions() {
        return functions;
    }

}

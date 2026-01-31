/*
 * BooleanFunctions.java
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
import org.bluezoo.gonzalez.transform.xpath.expr.XPathException;
import org.bluezoo.gonzalez.transform.xpath.type.XPathBoolean;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNode;
import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;

import java.util.List;

/**
 * XPath 1.0 boolean functions.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class BooleanFunctions {

    private BooleanFunctions() {}

    /**
     * XPath 1.0 boolean() function.
     * 
     * <p>Converts the argument to a boolean value.
     * 
     * <p>Signature: boolean(object) → boolean
     * 
     * @see <a href="https://www.w3.org/TR/xpath/#function-boolean">XPath 1.0 boolean()</a>
     */
    public static final Function BOOLEAN = new Function() {
        @Override public String getName() { return "boolean"; }
        @Override public int getMinArgs() { return 1; }
        @Override public int getMaxArgs() { return 1; }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) {
            return XPathBoolean.of(args.get(0).asBoolean());
        }
    };

    /**
     * XPath 1.0 not() function.
     * 
     * <p>Returns the logical negation of the boolean argument.
     * 
     * <p>Signature: not(boolean) → boolean
     * 
     * @see <a href="https://www.w3.org/TR/xpath/#function-not">XPath 1.0 not()</a>
     */
    public static final Function NOT = new Function() {
        @Override public String getName() { return "not"; }
        @Override public int getMinArgs() { return 1; }
        @Override public int getMaxArgs() { return 1; }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) {
            return XPathBoolean.of(!args.get(0).asBoolean());
        }
    };

    /**
     * XPath 1.0 true() function.
     * 
     * <p>Returns the boolean value true.
     * 
     * <p>Signature: true() → boolean
     * 
     * @see <a href="https://www.w3.org/TR/xpath/#function-true">XPath 1.0 true()</a>
     */
    public static final Function TRUE = new Function() {
        @Override public String getName() { return "true"; }
        @Override public int getMinArgs() { return 0; }
        @Override public int getMaxArgs() { return 0; }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) {
            return XPathBoolean.TRUE;
        }
    };

    /**
     * XPath 1.0 false() function.
     * 
     * <p>Returns the boolean value false.
     * 
     * <p>Signature: false() → boolean
     * 
     * @see <a href="https://www.w3.org/TR/xpath/#function-false">XPath 1.0 false()</a>
     */
    public static final Function FALSE = new Function() {
        @Override public String getName() { return "false"; }
        @Override public int getMinArgs() { return 0; }
        @Override public int getMaxArgs() { return 0; }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) {
            return XPathBoolean.FALSE;
        }
    };

    /**
     * XPath 1.0 lang() function.
     * 
     * <p>Returns true if the context node's xml:lang attribute matches the argument,
     * or if a parent element's xml:lang matches. Language matching is case-insensitive
     * and supports language subtags (e.g., "en" matches "en-US").
     * 
     * <p>Signature: lang(string) → boolean
     * 
     * @see <a href="https://www.w3.org/TR/xpath/#function-lang">XPath 1.0 lang()</a>
     */
    public static final Function LANG = new Function() {
        @Override public String getName() { return "lang"; }
        @Override public int getMinArgs() { return 1; }
        @Override public int getMaxArgs() { return 1; }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            String testLang = args.get(0).asString().toLowerCase();
            
            // Walk up the tree looking for xml:lang attribute
            XPathNode node = context.getContextNode();
            while (node != null) {
                if (node.isElement()) {
                    XPathNode langAttr = node.getAttribute("http://www.w3.org/XML/1998/namespace", "lang");
                    if (langAttr != null) {
                        String lang = langAttr.getStringValue().toLowerCase();
                        // Match if equal or if lang starts with testLang followed by '-'
                        if (lang.equals(testLang) || lang.startsWith(testLang + "-")) {
                            return XPathBoolean.TRUE;
                        }
                        return XPathBoolean.FALSE;
                    }
                }
                node = node.getParent();
            }
            
            return XPathBoolean.FALSE;
        }
    };

    /**
     * Returns all boolean functions (XPath 1.0).
     *
     * @return array of all boolean function implementations
     */
    public static Function[] getAll() {
        return new Function[] { BOOLEAN, NOT, TRUE, FALSE, LANG };
    }

}

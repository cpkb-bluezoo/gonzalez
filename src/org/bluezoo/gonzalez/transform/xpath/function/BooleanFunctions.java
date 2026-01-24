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

    /** boolean(object) - converts to boolean */
    public static final Function BOOLEAN = new Function() {
        @Override public String getName() { return "boolean"; }
        @Override public int getMinArgs() { return 1; }
        @Override public int getMaxArgs() { return 1; }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) {
            return XPathBoolean.of(args.get(0).asBoolean());
        }
    };

    /** not(boolean) - logical negation */
    public static final Function NOT = new Function() {
        @Override public String getName() { return "not"; }
        @Override public int getMinArgs() { return 1; }
        @Override public int getMaxArgs() { return 1; }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) {
            return XPathBoolean.of(!args.get(0).asBoolean());
        }
    };

    /** true() - returns true */
    public static final Function TRUE = new Function() {
        @Override public String getName() { return "true"; }
        @Override public int getMinArgs() { return 0; }
        @Override public int getMaxArgs() { return 0; }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) {
            return XPathBoolean.TRUE;
        }
    };

    /** false() - returns false */
    public static final Function FALSE = new Function() {
        @Override public String getName() { return "false"; }
        @Override public int getMinArgs() { return 0; }
        @Override public int getMaxArgs() { return 0; }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) {
            return XPathBoolean.FALSE;
        }
    };

    /** lang(string) - tests xml:lang */
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

    /** Returns all boolean functions. */
    public static Function[] getAll() {
        return new Function[] { BOOLEAN, NOT, TRUE, FALSE, LANG };
    }

}

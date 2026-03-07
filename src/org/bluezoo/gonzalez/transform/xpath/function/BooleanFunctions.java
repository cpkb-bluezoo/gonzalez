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
import org.bluezoo.gonzalez.transform.xpath.type.SequenceType;
import org.bluezoo.gonzalez.transform.xpath.type.XPathAnyURI;
import org.bluezoo.gonzalez.transform.xpath.type.XPathAtomicValue;
import org.bluezoo.gonzalez.transform.xpath.type.XPathBoolean;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNode;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNodeSet;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNumber;
import org.bluezoo.gonzalez.transform.xpath.type.XPathResultTreeFragment;
import org.bluezoo.gonzalez.transform.xpath.type.XPathSequence;
import org.bluezoo.gonzalez.transform.xpath.type.XPathString;
import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;

import static org.bluezoo.gonzalez.transform.xpath.function.TypeAnnotatedFunction.typed;

import java.util.List;

/**
 * XPath 1.0 boolean functions.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class BooleanFunctions {

    private BooleanFunctions() {}

    /**
     * XPath boolean() function.
     * 
     * <p>Computes the effective boolean value (EBV) of the argument,
     * raising FORG0006 for types without a defined EBV.
     * 
     * <p>Signature: boolean(item()*) → boolean
     * 
     * @see <a href="https://www.w3.org/TR/xpath-functions-31/#func-boolean">fn:boolean</a>
     */
    public static final Function BOOLEAN = new Function() {
        @Override public String getName() { return "boolean"; }
        @Override public int getMinArgs() { return 1; }
        @Override public int getMaxArgs() { return 1; }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context)
                throws XPathException {
            return XPathBoolean.of(effectiveBooleanValue(args.get(0)));
        }
    };

    /**
     * XPath not() function.
     * 
     * <p>Returns the negation of the effective boolean value of the argument.
     * 
     * <p>Signature: not(item()*) → boolean
     * 
     * @see <a href="https://www.w3.org/TR/xpath-functions-31/#func-not">fn:not</a>
     */
    public static final Function NOT = new Function() {
        @Override public String getName() { return "not"; }
        @Override public int getMinArgs() { return 1; }
        @Override public int getMaxArgs() { return 1; }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context)
                throws XPathException {
            return XPathBoolean.of(!effectiveBooleanValue(args.get(0)));
        }
    };

    /**
     * Computes the effective boolean value per XPath 2.0+ Section 2.4.3.
     *
     * @param arg the value to evaluate
     * @return the effective boolean value
     * @throws XPathException FORG0006 if the value has no defined EBV
     */
    static boolean effectiveBooleanValue(XPathValue arg) throws XPathException {
        if (arg == null) {
            return false;
        }

        if (arg instanceof XPathSequence) {
            XPathSequence seq = (XPathSequence) arg;
            if (seq.isEmpty()) {
                return false;
            }
            XPathValue first = seq.first();
            if (first.isNodeSet()) {
                return true;
            }
            if (seq.size() > 1) {
                throw new XPathException("FORG0006: Effective boolean value " +
                        "is not defined for a sequence of two or more items " +
                        "starting with an atomic value");
            }
            return singletonEBV(first);
        }

        if (arg.isNodeSet()) {
            return !arg.asNodeSet().isEmpty();
        }

        return singletonEBV(arg);
    }

    /**
     * Computes the EBV of a single (non-sequence, non-nodeset) item.
     */
    private static boolean singletonEBV(XPathValue item) throws XPathException {
        if (item instanceof XPathBoolean) {
            return ((XPathBoolean) item).getValue();
        }
        if (item instanceof XPathString) {
            return !item.asString().isEmpty();
        }
        if (item instanceof XPathNumber) {
            double d = item.asNumber();
            return d != 0.0 && !Double.isNaN(d);
        }
        if (item instanceof XPathAnyURI) {
            return !item.asString().isEmpty();
        }
        if (item instanceof XPathAtomicValue) {
            return !item.asString().isEmpty();
        }
        if (item instanceof XPathResultTreeFragment) {
            return true;
        }
        if (item.isNodeSet()) {
            return !item.asNodeSet().isEmpty();
        }
        throw new XPathException("FORG0006: Effective boolean value " +
                "is not defined for a value of type " + item.getClass().getSimpleName());
    }

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
     * XPath lang() function.
     * 
     * <p>Returns true if the specified node's xml:lang attribute matches the argument,
     * or if a parent element's xml:lang matches. Language matching is case-insensitive
     * and supports language subtags (e.g., "en" matches "en-US").
     * 
     * <p>XPath 1.0 signature: lang(string) → boolean
     * <p>XPath 2.0+ signature: lang(string, node?) → boolean
     */
    public static final Function LANG = new Function() {
        @Override public String getName() { return "lang"; }
        @Override public int getMinArgs() { return 1; }
        @Override public int getMaxArgs() { return 2; }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            String testLang = args.get(0).asString().toLowerCase();
            
            XPathNode node;
            if (args.size() >= 2) {
                XPathValue arg2 = args.get(1);
                if (arg2.isNodeSet()) {
                    XPathNodeSet ns = arg2.asNodeSet();
                    if (ns.isEmpty()) {
                        return XPathBoolean.FALSE;
                    }
                    node = ns.iterator().next();
                } else if (arg2 instanceof XPathNode) {
                    node = (XPathNode) arg2;
                } else {
                    throw new XPathException("XPTY0004: Second argument to lang() must be a node");
                }
            } else {
                node = context.getContextNode();
            }
            
            while (node != null) {
                if (node.isElement()) {
                    XPathNode langAttr = node.getAttribute("http://www.w3.org/XML/1998/namespace", "lang");
                    if (langAttr != null) {
                        String lang = langAttr.getStringValue().toLowerCase();
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
        SequenceType B = SequenceType.BOOLEAN;
        SequenceType IS = SequenceType.ITEM_STAR;
        SequenceType S = SequenceType.STRING;
        SequenceType NQ = SequenceType.NODE_Q;
        return new Function[] {
            typed(BOOLEAN, B, IS),
            typed(NOT,     B, IS),
            typed(TRUE,    B),
            typed(FALSE,   B),
            typed(LANG,    B, S, NQ),
        };
    }

}

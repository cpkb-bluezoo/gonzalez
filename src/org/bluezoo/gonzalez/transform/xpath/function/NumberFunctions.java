/*
 * NumberFunctions.java
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
import org.bluezoo.gonzalez.transform.xpath.type.XPathNode;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNodeSet;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNumber;
import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;

import java.util.List;

/**
 * XPath 1.0 number functions.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class NumberFunctions {

    private NumberFunctions() {}

    /** number(object?) - converts to number */
    public static final Function NUMBER = new Function() {
        @Override public String getName() { return "number"; }
        @Override public int getMinArgs() { return 0; }
        @Override public int getMaxArgs() { return 1; }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) {
            if (args.isEmpty()) {
                // Convert context node's string-value to number
                String str = context.getContextNode().getStringValue();
                try {
                    return XPathNumber.of(Double.parseDouble(str.trim()));
                } catch (NumberFormatException e) {
                    return XPathNumber.NaN;
                }
            }
            return XPathNumber.of(args.get(0).asNumber());
        }
    };

    /** sum(node-set) - sum of node string-values as numbers */
    public static final Function SUM = new Function() {
        @Override public String getName() { return "sum"; }
        @Override public int getMinArgs() { return 1; }
        @Override public int getMaxArgs() { return 1; }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            XPathValue arg = args.get(0);
            if (!arg.isNodeSet()) {
                throw new XPathException("sum() requires a node-set argument");
            }
            
            XPathNodeSet nodeSet = arg.asNodeSet();
            double sum = 0;
            
            for (XPathNode node : nodeSet) {
                String str = node.getStringValue().trim();
                try {
                    sum += Double.parseDouble(str);
                } catch (NumberFormatException e) {
                    return XPathNumber.NaN;
                }
            }
            
            return XPathNumber.of(sum);
        }
    };

    /** floor(number) - largest integer not greater than argument */
    public static final Function FLOOR = new Function() {
        @Override public String getName() { return "floor"; }
        @Override public int getMinArgs() { return 1; }
        @Override public int getMaxArgs() { return 1; }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) {
            double value = args.get(0).asNumber();
            return XPathNumber.of(Math.floor(value));
        }
    };

    /** ceiling(number) - smallest integer not less than argument */
    public static final Function CEILING = new Function() {
        @Override public String getName() { return "ceiling"; }
        @Override public int getMinArgs() { return 1; }
        @Override public int getMaxArgs() { return 1; }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) {
            double value = args.get(0).asNumber();
            return XPathNumber.of(Math.ceil(value));
        }
    };

    /** round(number) - round to nearest integer */
    public static final Function ROUND = new Function() {
        @Override public String getName() { return "round"; }
        @Override public int getMinArgs() { return 1; }
        @Override public int getMaxArgs() { return 1; }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) {
            double value = args.get(0).asNumber();
            
            // XPath 1.0 specifies round-half-to-positive-infinity
            // Math.round uses round-half-up which is the same for positive numbers
            // but differs for negative (e.g., -0.5 should round to 0, not -1)
            if (Double.isNaN(value) || Double.isInfinite(value)) {
                return XPathNumber.of(value);
            }
            
            // Handle negative numbers specially for XPath semantics
            if (value < 0 && value > -0.5) {
                return XPathNumber.of(-0.0);
            }
            
            return XPathNumber.of(Math.floor(value + 0.5));
        }
    };

    /** Returns all number functions. */
    public static Function[] getAll() {
        return new Function[] { NUMBER, SUM, FLOOR, CEILING, ROUND };
    }

}

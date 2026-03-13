/*
 * MathFunctions.java
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

import java.util.List;

import org.bluezoo.gonzalez.transform.xpath.expr.XPathException;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNumber;
import org.bluezoo.gonzalez.transform.xpath.type.XPathSequence;
import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;

/**
 * XPath 3.0 math namespace functions (math:sin, math:cos, etc.).
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
final class MathFunctions {

    private MathFunctions() {
    }

    static XPathValue invoke(String localName, List<XPathValue> args) throws XPathException {
        switch (localName) {
            case "pi":
                return XPathNumber.of(Math.PI);
            case "e":
                return XPathNumber.of(Math.E);
            case "sqrt":
                checkArgs(localName, args, 1);
                return XPathNumber.of(Math.sqrt(args.get(0).asNumber()));
            case "sin":
                checkArgs(localName, args, 1);
                return XPathNumber.of(Math.sin(args.get(0).asNumber()));
            case "cos":
                checkArgs(localName, args, 1);
                return XPathNumber.of(Math.cos(args.get(0).asNumber()));
            case "tan":
                checkArgs(localName, args, 1);
                return XPathNumber.of(Math.tan(args.get(0).asNumber()));
            case "asin":
                checkArgs(localName, args, 1);
                return XPathNumber.of(Math.asin(args.get(0).asNumber()));
            case "acos":
                checkArgs(localName, args, 1);
                return XPathNumber.of(Math.acos(args.get(0).asNumber()));
            case "atan":
                checkArgs(localName, args, 1);
                return XPathNumber.of(Math.atan(args.get(0).asNumber()));
            case "atan2":
                checkArgs(localName, args, 2);
                return XPathNumber.of(Math.atan2(args.get(0).asNumber(), args.get(1).asNumber()));
            case "exp":
                checkArgs(localName, args, 1);
                return XPathNumber.of(Math.exp(args.get(0).asNumber()));
            case "exp10":
                checkArgs(localName, args, 1);
                return XPathNumber.of(Math.pow(10, args.get(0).asNumber()));
            case "log":
                checkArgs(localName, args, 1);
                return XPathNumber.of(Math.log(args.get(0).asNumber()));
            case "log10":
                checkArgs(localName, args, 1);
                return XPathNumber.of(Math.log10(args.get(0).asNumber()));
            case "pow":
            case "power":
                checkArgs(localName, args, 2);
                return XPathNumber.of(Math.pow(args.get(0).asNumber(), args.get(1).asNumber()));
            default:
                throw new XPathException("Unknown math function: math:" + localName);
        }
    }

    private static void checkArgs(String func, List<XPathValue> args, int required) throws XPathException {
        if (args.size() < required) {
            throw new XPathException("math:" + func + " requires " + required + " argument(s)");
        }
        if (args.get(0) == null || 
            (args.get(0) instanceof XPathSequence && ((XPathSequence)args.get(0)).isEmpty())) {
            // Return empty sequence for empty input
        }
    }

}

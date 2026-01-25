/*
 * StringFunctions.java
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

import org.bluezoo.gonzalez.transform.xpath.XPathContext;
import org.bluezoo.gonzalez.transform.xpath.expr.XPathException;
import org.bluezoo.gonzalez.transform.xpath.type.XPathBoolean;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNumber;
import org.bluezoo.gonzalez.transform.xpath.type.XPathString;
import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;

/**
 * XPath 1.0 string functions.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class StringFunctions {

    private StringFunctions() {}

    /** string(object?) - converts to string */
    public static final Function STRING = new Function() {
        @Override public String getName() { return "string"; }
        @Override public int getMinArgs() { return 0; }
        @Override public int getMaxArgs() { return 1; }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) {
            if (args.isEmpty()) {
                // Convert context node to string
                return XPathString.of(context.getContextNode().getStringValue());
            }
            return XPathString.of(args.get(0).asString());
        }
    };

    /** concat(string, string, string*) - concatenates strings */
    public static final Function CONCAT = new Function() {
        @Override public String getName() { return "concat"; }
        @Override public int getMinArgs() { return 2; }
        @Override public int getMaxArgs() { return Integer.MAX_VALUE; }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) {
            StringBuilder sb = new StringBuilder();
            for (XPathValue arg : args) {
                sb.append(arg.asString());
            }
            return XPathString.of(sb.toString());
        }
    };

    /** starts-with(string, string) - tests prefix */
    public static final Function STARTS_WITH = new Function() {
        @Override public String getName() { return "starts-with"; }
        @Override public int getMinArgs() { return 2; }
        @Override public int getMaxArgs() { return 2; }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) {
            String str = args.get(0).asString();
            String prefix = args.get(1).asString();
            return XPathBoolean.of(str.startsWith(prefix));
        }
    };

    /** contains(string, string) - tests substring */
    public static final Function CONTAINS = new Function() {
        @Override public String getName() { return "contains"; }
        @Override public int getMinArgs() { return 2; }
        @Override public int getMaxArgs() { return 2; }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) {
            String str = args.get(0).asString();
            String substr = args.get(1).asString();
            return XPathBoolean.of(str.contains(substr));
        }
    };

    /** substring-before(string, string) - substring before match */
    public static final Function SUBSTRING_BEFORE = new Function() {
        @Override public String getName() { return "substring-before"; }
        @Override public int getMinArgs() { return 2; }
        @Override public int getMaxArgs() { return 2; }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) {
            String str = args.get(0).asString();
            String substr = args.get(1).asString();
            int index = str.indexOf(substr);
            if (index < 0) {
                return XPathString.EMPTY;
            }
            return XPathString.of(str.substring(0, index));
        }
    };

    /** substring-after(string, string) - substring after match */
    public static final Function SUBSTRING_AFTER = new Function() {
        @Override public String getName() { return "substring-after"; }
        @Override public int getMinArgs() { return 2; }
        @Override public int getMaxArgs() { return 2; }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) {
            String str = args.get(0).asString();
            String substr = args.get(1).asString();
            int index = str.indexOf(substr);
            if (index < 0) {
                return XPathString.EMPTY;
            }
            return XPathString.of(str.substring(index + substr.length()));
        }
    };

    /** substring(string, number, number?) - substring by position */
    public static final Function SUBSTRING = new Function() {
        @Override public String getName() { return "substring"; }
        @Override public int getMinArgs() { return 2; }
        @Override public int getMaxArgs() { return 3; }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) {
            String str = args.get(0).asString();
            double startPos = args.get(1).asNumber();
            
            // Handle NaN start position
            if (Double.isNaN(startPos)) {
                return XPathString.EMPTY;
            }
            
            // XPath spec for substring(string, startPos, length?):
            // Returns characters at position P where:
            //   P >= round(startPos) AND P < round(startPos) + round(length)
            // Positions are 1-based.
            
            // Round start position (XPath uses round(), not floor())
            long roundedStart = Math.round(startPos);
            
            // Determine the ending position (exclusive, 1-based)
            long roundedEnd;
            if (args.size() > 2) {
                double length = args.get(2).asNumber();
                if (Double.isNaN(length)) {
                    return XPathString.EMPTY;
                }
                if (Double.isInfinite(length)) {
                    if (length > 0) {
                        // Positive infinity - go to end of string
                        roundedEnd = str.length() + 1;
                    } else {
                        // Negative infinity - nothing to return
                        return XPathString.EMPTY;
                    }
                } else {
                    long roundedLength = Math.round(length);
                    if (roundedLength <= 0) {
                        return XPathString.EMPTY;
                    }
                    roundedEnd = roundedStart + roundedLength;
                }
            } else {
                // No length means to end of string
                roundedEnd = str.length() + 1;
            }
            
            // Clamp to valid range [1, length+1] for positions
            // First position we want (1-based)
            long firstPos = Math.max(roundedStart, 1);
            // Last position + 1 (exclusive, 1-based)
            long lastPosExcl = Math.min(roundedEnd, str.length() + 1);
            
            // Convert to 0-based Java indices
            int start = (int) (firstPos - 1);
            int end = (int) (lastPosExcl - 1);
            
            if (start >= str.length() || end <= start) {
                return XPathString.EMPTY;
            }
            
            return XPathString.of(str.substring(start, end));
        }
    };

    /** string-length(string?) - length in characters */
    public static final Function STRING_LENGTH = new Function() {
        @Override public String getName() { return "string-length"; }
        @Override public int getMinArgs() { return 0; }
        @Override public int getMaxArgs() { return 1; }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) {
            String str;
            if (args.isEmpty()) {
                str = context.getContextNode().getStringValue();
            } else {
                str = args.get(0).asString();
            }
            return XPathNumber.of(str.length());
        }
    };

    /** normalize-space(string?) - whitespace normalization */
    public static final Function NORMALIZE_SPACE = new Function() {
        @Override public String getName() { return "normalize-space"; }
        @Override public int getMinArgs() { return 0; }
        @Override public int getMaxArgs() { return 1; }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) {
            String str;
            if (args.isEmpty()) {
                str = context.getContextNode().getStringValue();
            } else {
                str = args.get(0).asString();
            }
            // Normalize: strip leading/trailing, collapse internal whitespace (no regex)
            String trimmed = str.trim();
            StringBuilder result = new StringBuilder();
            boolean lastWasWhitespace = false;
            for (int i = 0; i < trimmed.length(); i++) {
                char c = trimmed.charAt(i);
                boolean isWhitespace = (c == ' ' || c == '\t' || c == '\n' || c == '\r');
                if (isWhitespace) {
                    if (!lastWasWhitespace) {
                        result.append(' ');
                        lastWasWhitespace = true;
                    }
                } else {
                    result.append(c);
                    lastWasWhitespace = false;
                }
            }
            return XPathString.of(result.toString());
        }
    };

    /** translate(string, string, string) - character replacement */
    public static final Function TRANSLATE = new Function() {
        @Override public String getName() { return "translate"; }
        @Override public int getMinArgs() { return 3; }
        @Override public int getMaxArgs() { return 3; }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) {
            String str = args.get(0).asString();
            String from = args.get(1).asString();
            String to = args.get(2).asString();
            
            StringBuilder result = new StringBuilder(str.length());
            for (int i = 0; i < str.length(); i++) {
                char c = str.charAt(i);
                int index = from.indexOf(c);
                if (index < 0) {
                    // Character not in 'from' - keep it
                    result.append(c);
                } else if (index < to.length()) {
                    // Replace with corresponding character in 'to'
                    result.append(to.charAt(index));
                }
                // If index >= to.length(), character is removed
            }
            
            return XPathString.of(result.toString());
        }
    };

    /** Returns all string functions. */
    public static Function[] getAll() {
        return new Function[] {
            STRING, CONCAT, STARTS_WITH, CONTAINS, SUBSTRING_BEFORE,
            SUBSTRING_AFTER, SUBSTRING, STRING_LENGTH, NORMALIZE_SPACE, TRANSLATE
        };
    }

}

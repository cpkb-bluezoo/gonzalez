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

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.bluezoo.gonzalez.transform.xpath.XPathContext;
import org.bluezoo.gonzalez.transform.xpath.expr.XPathException;
import org.bluezoo.gonzalez.transform.xpath.type.XPathBoolean;
import org.bluezoo.gonzalez.transform.xpath.type.XPathDateTime;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNode;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNodeSet;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNumber;
import org.bluezoo.gonzalez.transform.xpath.type.XPathSequence;
import org.bluezoo.gonzalez.transform.xpath.type.XPathString;
import org.bluezoo.gonzalez.transform.xpath.type.XPathTypedAtomic;
import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;

/**
 * XPath 1.0 string functions.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class StringFunctions {

    private StringFunctions() {}

    /**
     * XPath 1.0 string() function.
     * 
     * <p>Converts the argument (or context node if no argument) to a string.
     * 
     * <p>Signature: string(object?) → string
     * 
     * @see <a href="https://www.w3.org/TR/xpath/#function-string">XPath 1.0 string()</a>
     */
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

    /**
     * XPath 1.0 concat() function.
     * 
     * <p>Concatenates two or more strings together.
     * 
     * <p>Signature: concat(string, string, string*) → string
     * 
     * @see <a href="https://www.w3.org/TR/xpath/#function-concat">XPath 1.0 concat()</a>
     */
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

    /**
     * XPath 1.0 starts-with() function.
     * 
     * <p>Returns true if the first string starts with the second string.
     * 
     * <p>Signature: starts-with(string, string) → boolean
     * 
     * @see <a href="https://www.w3.org/TR/xpath/#function-starts-with">XPath 1.0 starts-with()</a>
     */
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

    /**
     * XPath 1.0 contains() function.
     * 
     * <p>Returns true if the first string contains the second string as a substring.
     * 
     * <p>Signature: contains(string, string) → boolean
     * 
     * @see <a href="https://www.w3.org/TR/xpath/#function-contains">XPath 1.0 contains()</a>
     */
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

    /**
     * XPath 1.0 substring-before() function.
     * 
     * <p>Returns the substring of the first argument that precedes the first occurrence
     * of the second argument, or empty string if not found.
     * 
     * <p>Signature: substring-before(string, string) → string
     * 
     * @see <a href="https://www.w3.org/TR/xpath/#function-substring-before">XPath 1.0 substring-before()</a>
     */
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

    /**
     * XPath 1.0 substring-after() function.
     * 
     * <p>Returns the substring of the first argument that follows the first occurrence
     * of the second argument, or empty string if not found.
     * 
     * <p>Signature: substring-after(string, string) → string
     * 
     * @see <a href="https://www.w3.org/TR/xpath/#function-substring-after">XPath 1.0 substring-after()</a>
     */
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

    /**
     * XPath 1.0 substring() function.
     * 
     * <p>Returns the substring of the first argument starting at the position specified
     * by the second argument, with optional length specified by the third argument.
     * Positions are 1-based. If length is omitted, returns to end of string.
     * 
     * <p>Signature: substring(string, number, number?) → string
     * 
     * @see <a href="https://www.w3.org/TR/xpath/#function-substring">XPath 1.0 substring()</a>
     */
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
            // Note: Must compute end position in floating point before rounding,
            // because -Infinity + Infinity = NaN per IEEE 754
            
            double roundedStart = Math.round(startPos);
            double roundedEnd;
            
            if (args.size() > 2) {
                double length = args.get(2).asNumber();
                double roundedLength = Math.round(length);
                // Compute end position in floating point (handles infinities correctly)
                roundedEnd = roundedStart + roundedLength;
            } else {
                // No length means to end of string (positive infinity)
                roundedEnd = Double.POSITIVE_INFINITY;
            }
            
            // If either bound is NaN (e.g., -Inf + Inf), return empty
            if (Double.isNaN(roundedStart) || Double.isNaN(roundedEnd)) {
                return XPathString.EMPTY;
            }
            
            // If end is negative infinity or start is positive infinity, return empty
            if (roundedEnd == Double.NEGATIVE_INFINITY || 
                roundedStart == Double.POSITIVE_INFINITY) {
                return XPathString.EMPTY;
            }
            
            // Clamp to valid range [1, length+1] for positions
            // First position we want (1-based)
            long firstPos = (roundedStart == Double.NEGATIVE_INFINITY) ? 1 : 
                Math.max((long) roundedStart, 1);
            // Last position + 1 (exclusive, 1-based)
            long lastPosExcl = (roundedEnd == Double.POSITIVE_INFINITY) ? 
                str.length() + 1 : Math.min((long) roundedEnd, str.length() + 1);
            
            // Convert to 0-based Java indices
            int start = (int) (firstPos - 1);
            int end = (int) (lastPosExcl - 1);
            
            if (start >= str.length() || end <= start) {
                return XPathString.EMPTY;
            }
            
            return XPathString.of(str.substring(start, end));
        }
    };

    /**
     * XPath 1.0 string-length() function.
     * 
     * <p>Returns the number of characters in the string (or context node if no argument).
     * 
     * <p>Signature: string-length(string?) → number
     * 
     * @see <a href="https://www.w3.org/TR/xpath/#function-string-length">XPath 1.0 string-length()</a>
     */
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

    /**
     * XPath 1.0 normalize-space() function.
     * 
     * <p>Strips leading and trailing whitespace, and collapses sequences of whitespace
     * characters to single spaces.
     * 
     * <p>Signature: normalize-space(string?) → string
     * 
     * @see <a href="https://www.w3.org/TR/xpath/#function-normalize-space">XPath 1.0 normalize-space()</a>
     */
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

    /**
     * XPath 1.0 translate() function.
     * 
     * <p>Replaces characters in the first string: each character found in the second string
     * is replaced by the corresponding character in the third string. Characters in the first
     * string that appear in the second but have no corresponding character in the third
     * (because the third string is shorter) are removed.
     * 
     * <p>Signature: translate(string, string, string) → string
     * 
     * @see <a href="https://www.w3.org/TR/xpath/#function-translate">XPath 1.0 translate()</a>
     */
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

    /**
     * XPath 2.0 data() function.
     * 
     * <p>Atomizes the argument, returning the typed value of nodes. For sequences,
     * returns a sequence of atomized values. If a node has a type annotation,
     * the result is a typed atomic value that can be checked with {@code instance of}.
     * 
     * <p>Signature: data(item()*) → atomic*
     * 
     * @see <a href="https://www.w3.org/TR/xpath20/#function-data">XPath 2.0 data()</a>
     */
    public static final Function DATA = new Function() {
        @Override public String getName() { return "data"; }
        @Override public int getMinArgs() { return 0; }
        @Override public int getMaxArgs() { return 1; }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) {
            if (args.isEmpty()) {
                // Atomize context node
                XPathNode node = context.getContextNode();
                return atomizeNode(node);
            }
            // Atomize the argument - returns typed value for typed nodes
            // For sequences, return sequence of atomized values
            XPathValue arg = args.get(0);
            
            // If it's already a sequence, atomize each item
            if (arg.isSequence()) {
                XPathSequence seq = (XPathSequence) arg;
                if (seq.isEmpty()) {
                    return XPathSequence.EMPTY;
                }
                if (seq.size() == 1) {
                    return atomize(seq.get(0));
                }
                List<XPathValue> atomized = new ArrayList<>(seq.size());
                for (XPathValue item : seq) {
                    atomized.add(atomize(item));
                }
                return new XPathSequence(atomized);
            }
            
            // Single value - atomize it
            return atomize(arg);
        }
        
        private XPathValue atomize(XPathValue value) {
            if (value == null) {
                return XPathString.of("");
            }
            if (value.isNodeSet()) {
                XPathNodeSet nodeSet = value.asNodeSet();
                if (nodeSet.isEmpty()) {
                    return XPathString.of("");
                }
                // Return first node's typed value for single node
                if (nodeSet.size() == 1) {
                    return atomizeNode(nodeSet.iterator().next());
                }
                // For multiple nodes, return sequence of typed values
                List<XPathValue> values = new ArrayList<>(nodeSet.size());
                for (XPathNode node : nodeSet) {
                    values.add(atomizeNode(node));
                }
                return new XPathSequence(values);
            }
            // Already an atomic value - return as-is
            if (value instanceof XPathTypedAtomic || value instanceof XPathNumber ||
                value instanceof XPathBoolean || value instanceof XPathDateTime) {
                return value;
            }
            // String/atomic value - return as string
            return XPathString.of(value.asString());
        }
        
        /**
         * Atomizes a single node, returning a typed value if the node has a type annotation.
         */
        private XPathValue atomizeNode(XPathNode node) {
            String stringValue = node.getStringValue();
            String typeNs = node.getTypeNamespaceURI();
            String typeLocal = node.getTypeLocalName();
            
            // If node has a type annotation, return a typed atomic value
            if (typeLocal != null) {
                return XPathTypedAtomic.fromNode(stringValue, typeNs, typeLocal);
            }
            
            // No type annotation - return untyped string
            return XPathString.of(stringValue);
        }
    };

    // ========== XPath 2.0/3.0 String Functions ==========

    /**
     * XPath 2.0 string-join() function.
     * 
     * <p>Concatenates the string values of items in a sequence, separated by a separator string.
     * 
     * <p>Signature: string-join(item()*, string) → string
     * 
     * @see <a href="https://www.w3.org/TR/xpath20/#function-string-join">XPath 2.0 string-join()</a>
     */
    public static final Function STRING_JOIN = new Function() {
        @Override public String getName() { return "string-join"; }
        @Override public int getMinArgs() { return 1; }
        @Override public int getMaxArgs() { return 2; }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            XPathValue seq = args.get(0);
            String separator = args.size() > 1 ? args.get(1).asString() : "";
            
            List<String> strings = toStringList(seq);
            return XPathString.of(String.join(separator, strings));
        }
    };

    /**
     * XPath 2.0 upper-case() function.
     * 
     * <p>Converts a string to upper case.
     * 
     * <p>Signature: upper-case(string?) → string
     * 
     * @see <a href="https://www.w3.org/TR/xpath20/#function-upper-case">XPath 2.0 upper-case()</a>
     */
    public static final Function UPPER_CASE = new Function() {
        @Override public String getName() { return "upper-case"; }
        @Override public int getMinArgs() { return 1; }
        @Override public int getMaxArgs() { return 1; }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            String str = args.get(0).asString();
            return XPathString.of(str.toUpperCase());
        }
    };

    /**
     * XPath 2.0 lower-case() function.
     * 
     * <p>Converts a string to lower case.
     * 
     * <p>Signature: lower-case(string?) → string
     * 
     * @see <a href="https://www.w3.org/TR/xpath20/#function-lower-case">XPath 2.0 lower-case()</a>
     */
    public static final Function LOWER_CASE = new Function() {
        @Override public String getName() { return "lower-case"; }
        @Override public int getMinArgs() { return 1; }
        @Override public int getMaxArgs() { return 1; }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            String str = args.get(0).asString();
            return XPathString.of(str.toLowerCase());
        }
    };

    /**
     * XPath 2.0 ends-with() function.
     * 
     * <p>Returns true if the first string ends with the second string.
     * 
     * <p>Signature: ends-with(string?, string?, string?) → boolean
     * 
     * @see <a href="https://www.w3.org/TR/xpath20/#function-ends-with">XPath 2.0 ends-with()</a>
     */
    public static final Function ENDS_WITH = new Function() {
        @Override public String getName() { return "ends-with"; }
        @Override public int getMinArgs() { return 2; }
        @Override public int getMaxArgs() { return 3; }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            String str = args.get(0).asString();
            String suffix = args.get(1).asString();
            return XPathBoolean.of(str.endsWith(suffix));
        }
    };

    /**
     * XPath 2.0 matches() function.
     * 
     * <p>Returns true if the input string matches the regular expression pattern.
     * Optional flags control matching behavior (i=case-insensitive, m=multiline, s=dotall, x=comments).
     * 
     * <p>Signature: matches(string?, string, string?) → boolean
     * 
     * @see <a href="https://www.w3.org/TR/xpath20/#function-matches">XPath 2.0 matches()</a>
     */
    public static final Function MATCHES = new Function() {
        @Override public String getName() { return "matches"; }
        @Override public int getMinArgs() { return 2; }
        @Override public int getMaxArgs() { return 3; }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            String input = args.get(0).asString();
            String pattern = args.get(1).asString();
            String flags = args.size() > 2 ? args.get(2).asString() : "";
            
            try {
                int javaFlags = parseRegexFlags(flags);
                Pattern p = Pattern.compile(pattern, javaFlags);
                return XPathBoolean.of(p.matcher(input).find());
            } catch (PatternSyntaxException e) {
                throw new XPathException("Invalid regular expression: " + pattern, e);
            }
        }
    };

    /**
     * XPath 2.0 replace() function.
     * 
     * <p>Replaces substrings that match a regular expression pattern with a replacement string.
     * The replacement string can contain $1, $2, etc. to refer to captured groups.
     * 
     * <p>Signature: replace(string?, string, string, string?) → string
     * 
     * @see <a href="https://www.w3.org/TR/xpath20/#function-replace">XPath 2.0 replace()</a>
     */
    public static final Function REPLACE = new Function() {
        @Override public String getName() { return "replace"; }
        @Override public int getMinArgs() { return 3; }
        @Override public int getMaxArgs() { return 4; }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            String input = args.get(0).asString();
            String pattern = args.get(1).asString();
            String replacement = args.get(2).asString();
            String flags = args.size() > 3 ? args.get(3).asString() : "";
            
            try {
                int javaFlags = parseRegexFlags(flags);
                Pattern p = Pattern.compile(pattern, javaFlags);
                // XPath uses $1, $2 for groups but Java also uses that, so should be compatible
                return XPathString.of(p.matcher(input).replaceAll(replacement));
            } catch (PatternSyntaxException e) {
                throw new XPathException("Invalid regular expression: " + pattern, e);
            }
        }
    };

    /**
     * XPath 2.0 tokenize() function.
     * 
     * <p>Splits a string into a sequence of tokens by matching against a regular expression pattern.
     * If no pattern is provided, splits on whitespace.
     * 
     * <p>Signature: tokenize(string?, string?, string?) → string*
     * 
     * @see <a href="https://www.w3.org/TR/xpath20/#function-tokenize">XPath 2.0 tokenize()</a>
     */
    public static final Function TOKENIZE = new Function() {
        @Override public String getName() { return "tokenize"; }
        @Override public int getMinArgs() { return 1; }
        @Override public int getMaxArgs() { return 3; }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            String input = args.get(0).asString();
            
            // XPath 3.1: tokenize with 1 argument splits on whitespace
            if (args.size() == 1) {
                String[] tokens = input.trim().split("\\s+");
                List<XPathValue> result = new ArrayList<>();
                for (String token : tokens) {
                    if (!token.isEmpty()) {
                        result.add(XPathString.of(token));
                    }
                }
                return result.isEmpty() ? XPathSequence.EMPTY : new XPathSequence(result);
            }
            
            String pattern = args.get(1).asString();
            String flags = args.size() > 2 ? args.get(2).asString() : "";
            
            try {
                int javaFlags = parseRegexFlags(flags);
                Pattern p = Pattern.compile(pattern, javaFlags);
                String[] tokens = p.split(input, -1);  // -1 to keep trailing empty strings
                
                List<XPathValue> result = new ArrayList<>();
                for (String token : tokens) {
                    result.add(XPathString.of(token));
                }
                return result.isEmpty() ? XPathSequence.EMPTY : new XPathSequence(result);
            } catch (PatternSyntaxException e) {
                throw new XPathException("Invalid regular expression: " + pattern, e);
            }
        }
    };

    /**
     * XPath 2.0 compare() function.
     * 
     * <p>Compares two strings and returns -1, 0, or 1 if the first is less than, equal to,
     * or greater than the second. Returns empty sequence if either argument is empty.
     * 
     * <p>Signature: compare(string?, string?, string?) → integer?
     * 
     * @see <a href="https://www.w3.org/TR/xpath20/#function-compare">XPath 2.0 compare()</a>
     */
    public static final Function COMPARE = new Function() {
        @Override public String getName() { return "compare"; }
        @Override public int getMinArgs() { return 2; }
        @Override public int getMaxArgs() { return 3; }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            XPathValue arg1 = args.get(0);
            XPathValue arg2 = args.get(1);
            
            // Handle empty sequence
            if (isEmpty(arg1) || isEmpty(arg2)) {
                return XPathSequence.EMPTY;
            }
            
            String str1 = arg1.asString();
            String str2 = arg2.asString();
            
            int cmp = str1.compareTo(str2);
            if (cmp < 0) {
                return XPathNumber.of(-1);
            }
            if (cmp > 0) {
                return XPathNumber.of(1);
            }
            return XPathNumber.of(0);
        }
    };

    /**
     * XPath 2.0 codepoints-to-string() function.
     * 
     * <p>Converts a sequence of Unicode codepoints to a string.
     * 
     * <p>Signature: codepoints-to-string(integer*) → string
     * 
     * @see <a href="https://www.w3.org/TR/xpath20/#function-codepoints-to-string">XPath 2.0 codepoints-to-string()</a>
     */
    public static final Function CODEPOINTS_TO_STRING = new Function() {
        @Override public String getName() { return "codepoints-to-string"; }
        @Override public int getMinArgs() { return 1; }
        @Override public int getMaxArgs() { return 1; }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            List<Integer> codepoints = toIntList(args.get(0));
            StringBuilder sb = new StringBuilder();
            for (int cp : codepoints) {
                sb.appendCodePoint(cp);
            }
            return XPathString.of(sb.toString());
        }
    };

    /**
     * XPath 2.0 string-to-codepoints() function.
     * 
     * <p>Converts a string to a sequence of Unicode codepoints.
     * 
     * <p>Signature: string-to-codepoints(string?) → integer*
     * 
     * @see <a href="https://www.w3.org/TR/xpath20/#function-string-to-codepoints">XPath 2.0 string-to-codepoints()</a>
     */
    public static final Function STRING_TO_CODEPOINTS = new Function() {
        @Override public String getName() { return "string-to-codepoints"; }
        @Override public int getMinArgs() { return 1; }
        @Override public int getMaxArgs() { return 1; }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            String str = args.get(0).asString();
            if (str.isEmpty()) {
                return XPathSequence.EMPTY;
            }
            
            List<XPathValue> result = new ArrayList<>();
            for (int i = 0; i < str.length(); ) {
                int cp = str.codePointAt(i);
                result.add(XPathNumber.of(cp));
                i += Character.charCount(cp);
            }
            return new XPathSequence(result);
        }
    };

    /**
     * XPath 2.0 encode-for-uri() function.
     * 
     * <p>Percent-encodes a string for use as a URI component.
     * 
     * <p>Signature: encode-for-uri(string?) → string
     * 
     * @see <a href="https://www.w3.org/TR/xpath20/#function-encode-for-uri">XPath 2.0 encode-for-uri()</a>
     */
    public static final Function ENCODE_FOR_URI = new Function() {
        @Override public String getName() { return "encode-for-uri"; }
        @Override public int getMinArgs() { return 1; }
        @Override public int getMaxArgs() { return 1; }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            String str = args.get(0).asString();
            try {
                return XPathString.of(URLEncoder.encode(str, "UTF-8")
                    .replace("+", "%20"));  // space should be %20, not +
            } catch (UnsupportedEncodingException e) {
                return XPathString.of(str);
            }
        }
    };

    /**
     * XPath 2.0 iri-to-uri() function.
     * 
     * <p>Converts an IRI (Internationalized Resource Identifier) to a URI by percent-encoding
     * non-ASCII characters.
     * 
     * <p>Signature: iri-to-uri(string?) → string
     * 
     * @see <a href="https://www.w3.org/TR/xpath20/#function-iri-to-uri">XPath 2.0 iri-to-uri()</a>
     */
    public static final Function IRI_TO_URI = new Function() {
        @Override public String getName() { return "iri-to-uri"; }
        @Override public int getMinArgs() { return 1; }
        @Override public int getMaxArgs() { return 1; }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            // Simplified: just return as-is for now
            return XPathString.of(args.get(0).asString());
        }
    };

    /**
     * XPath 2.0 escape-html-uri() function.
     * 
     * <p>Escapes non-ASCII characters in a string for use in HTML href attributes.
     * 
     * <p>Signature: escape-html-uri(string?) → string
     * 
     * @see <a href="https://www.w3.org/TR/xpath20/#function-escape-html-uri">XPath 2.0 escape-html-uri()</a>
     */
    public static final Function ESCAPE_HTML_URI = new Function() {
        @Override public String getName() { return "escape-html-uri"; }
        @Override public int getMinArgs() { return 1; }
        @Override public int getMaxArgs() { return 1; }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            String str = args.get(0).asString();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < str.length(); i++) {
                char c = str.charAt(i);
                if (c > 0x7F) {
                    // Encode non-ASCII characters
                    sb.append(String.format("%%%02X", (int) c));
                } else {
                    sb.append(c);
                }
            }
            return XPathString.of(sb.toString());
        }
    };

    // ========== Helper methods ==========

    private static int parseRegexFlags(String flags) {
        int result = 0;
        for (char c : flags.toCharArray()) {
            switch (c) {
                case 'i': result |= Pattern.CASE_INSENSITIVE; break;
                case 'm': result |= Pattern.MULTILINE; break;
                case 's': result |= Pattern.DOTALL; break;
                case 'x': result |= Pattern.COMMENTS; break;
                // 'q' for literal mode not directly supported in Java
            }
        }
        return result;
    }

    private static boolean isEmpty(XPathValue value) {
        if (value == null) {
            return true;
        }
        if (value instanceof XPathSequence) {
            return ((XPathSequence) value).isEmpty();
        }
        return false;
    }

    private static List<String> toStringList(XPathValue value) {
        List<String> result = new ArrayList<>();
        if (value == null) {
            return result;
        }
        if (value instanceof XPathSequence) {
            XPathSequence seq = (XPathSequence) value;
            for (XPathValue item : seq) {
                addStringValues(item, result);
            }
        } else if (value instanceof XPathNodeSet) {
            XPathNodeSet nodeSet = (XPathNodeSet) value;
            for (XPathNode node : nodeSet) {
                // Atomize node - for list types, this produces multiple values
                XPathValue atomized = atomizeNodeForStringList(node);
                addStringValues(atomized, result);
            }
        } else if (value instanceof XPathNode) {
            // Direct node (e.g., SequenceAttributeItem)
            XPathNode node = (XPathNode) value;
            XPathValue atomized = atomizeNodeForStringList(node);
            addStringValues(atomized, result);
        } else {
            addStringValues(value, result);
        }
        return result;
    }
    
    private static void addStringValues(XPathValue value, List<String> result) {
        if (value instanceof XPathSequence) {
            XPathSequence seq = (XPathSequence) value;
            for (XPathValue item : seq) {
                result.add(item.asString());
            }
        } else {
            result.add(value.asString());
        }
    }
    
    private static XPathValue atomizeNodeForStringList(XPathNode node) {
        String typeNs = node.getTypeNamespaceURI();
        String typeLocal = node.getTypeLocalName();
        String stringValue = node.getStringValue();
        
        // For list types, atomize to sequence
        if (typeLocal != null) {
            return XPathTypedAtomic.fromNode(stringValue, typeNs, typeLocal);
        }
        return XPathString.of(stringValue);
    }

    private static List<Integer> toIntList(XPathValue value) {
        List<Integer> result = new ArrayList<>();
        if (value == null) {
            return result;
        }
        if (value instanceof XPathSequence) {
            XPathSequence seq = (XPathSequence) value;
            for (XPathValue item : seq) {
                result.add((int) item.asNumber());
            }
        } else if (value instanceof XPathNodeSet) {
            XPathNodeSet nodeSet = (XPathNodeSet) value;
            for (XPathNode node : nodeSet) {
                try {
                    result.add(Integer.parseInt(node.getStringValue().trim()));
                } catch (NumberFormatException e) {
                    // Skip invalid numbers
                }
            }
        } else {
            result.add((int) value.asNumber());
        }
        return result;
    }

    /**
     * Returns all string functions (XPath 1.0 and 2.0/3.0).
     *
     * @return array of all string function implementations
     */
    public static Function[] getAll() {
        return new Function[] {
            STRING, CONCAT, STARTS_WITH, CONTAINS, SUBSTRING_BEFORE,
            SUBSTRING_AFTER, SUBSTRING, STRING_LENGTH, NORMALIZE_SPACE, TRANSLATE,
            DATA, STRING_JOIN, UPPER_CASE, LOWER_CASE, ENDS_WITH, MATCHES, REPLACE,
            TOKENIZE, COMPARE, CODEPOINTS_TO_STRING, STRING_TO_CODEPOINTS,
            ENCODE_FOR_URI, IRI_TO_URI, ESCAPE_HTML_URI
        };
    }

}

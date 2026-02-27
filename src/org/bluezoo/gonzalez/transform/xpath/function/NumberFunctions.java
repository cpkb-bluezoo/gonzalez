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

import org.bluezoo.gonzalez.transform.xpath.Collation;
import org.bluezoo.gonzalez.transform.xpath.XPathContext;
import org.bluezoo.gonzalez.transform.xpath.expr.XPathException;
import org.bluezoo.gonzalez.transform.xpath.type.XPathDateTime;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNode;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNodeSet;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNumber;
import org.bluezoo.gonzalez.transform.xpath.type.XPathSequence;
import org.bluezoo.gonzalez.transform.xpath.type.XPathString;
import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * XPath 1.0 number functions.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class NumberFunctions {

    private NumberFunctions() {}

    /**
     * XPath 1.0 number() function.
     * 
     * <p>Converts the argument (or context node if no argument) to a number.
     * 
     * <p>Signature: number(object?) → number
     * 
     * @see <a href="https://www.w3.org/TR/xpath/#function-number">XPath 1.0 number()</a>
     */
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

    /**
     * XPath 1.0/2.0 sum() function.
     * 
     * <p>Returns the sum of numeric values.
     * 
     * <p>XPath 1.0 signature: sum(node-set) → number
     * <p>XPath 2.0 signature: sum($arg as xs:anyAtomicType*) → xs:anyAtomicType
     * <p>XPath 2.0 signature: sum($arg as xs:anyAtomicType*, $zero as xs:anyAtomicType?) → xs:anyAtomicType?
     * 
     * @see <a href="https://www.w3.org/TR/xpath/#function-sum">XPath 1.0 sum()</a>
     * @see <a href="https://www.w3.org/TR/xpath-functions/#func-sum">XPath 2.0 sum()</a>
     */
    public static final Function SUM = new Function() {
        @Override public String getName() { return "sum"; }
        @Override public int getMinArgs() { return 1; }
        @Override public int getMaxArgs() { return 2; }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            XPathValue arg = args.get(0);
            
            // XPath 2.0: second argument is the zero value (default 0)
            double zeroValue = 0;
            if (args.size() > 1) {
                zeroValue = args.get(1).asNumber();
            }
            
            // Handle node-set (XPath 1.0 style)
            if (arg.isNodeSet()) {
                XPathNodeSet nodeSet = arg.asNodeSet();
                if (nodeSet.isEmpty()) {
                    return XPathNumber.of(zeroValue);
                }
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
            
            // Handle sequence (XPath 2.0 style)
            if (arg.isSequence()) {
                java.util.Iterator<XPathValue> iter = arg.sequenceIterator();
                if (!iter.hasNext()) {
                    return XPathNumber.of(zeroValue);
                }
                double sum = 0;
                while (iter.hasNext()) {
                    XPathValue item = iter.next();
                    double num = item.asNumber();
                    if (Double.isNaN(num)) {
                        return XPathNumber.NaN;
                    }
                    sum += num;
                }
                return XPathNumber.of(sum);
            }
            
            // Single value
            return XPathNumber.of(arg.asNumber());
        }
    };

    /**
     * XPath 1.0 floor() function.
     * 
     * <p>Returns the largest integer not greater than the argument.
     * 
     * <p>Signature: floor(number) → number
     * 
     * @see <a href="https://www.w3.org/TR/xpath/#function-floor">XPath 1.0 floor()</a>
     */
    public static final Function FLOOR = new Function() {
        @Override public String getName() { return "floor"; }
        @Override public int getMinArgs() { return 1; }
        @Override public int getMaxArgs() { return 1; }
        @Override public ArgType[] getArgumentTypes() { return new ArgType[] { ArgType.NUMERIC }; }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) {
            double value = args.get(0).asNumber();
            return XPathNumber.of(Math.floor(value));
        }
    };

    /**
     * XPath 1.0 ceiling() function.
     * 
     * <p>Returns the smallest integer not less than the argument.
     * 
     * <p>Signature: ceiling(number) → number
     * 
     * @see <a href="https://www.w3.org/TR/xpath/#function-ceiling">XPath 1.0 ceiling()</a>
     */
    public static final Function CEILING = new Function() {
        @Override public String getName() { return "ceiling"; }
        @Override public int getMinArgs() { return 1; }
        @Override public int getMaxArgs() { return 1; }
        @Override public ArgType[] getArgumentTypes() { return new ArgType[] { ArgType.NUMERIC }; }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) {
            double value = args.get(0).asNumber();
            return XPathNumber.of(Math.ceil(value));
        }
    };

    /**
     * XPath 1.0 round() function.
     * 
     * <p>Rounds the argument to the nearest integer. Uses round-half-to-positive-infinity
     * semantics (e.g., -0.5 rounds to -0.0, not -1).
     * 
     * <p>Signature: round(number) → number
     * 
     * @see <a href="https://www.w3.org/TR/xpath/#function-round">XPath 1.0 round()</a>
     */
    public static final Function ROUND = new Function() {
        @Override public String getName() { return "round"; }
        @Override public int getMinArgs() { return 1; }
        @Override public int getMaxArgs() { return 2; }
        @Override public ArgType[] getArgumentTypes() { return new ArgType[] { ArgType.NUMERIC, ArgType.NUMERIC }; }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) {
            double value = args.get(0).asNumber();
            
            if (Double.isNaN(value) || Double.isInfinite(value)) {
                return XPathNumber.of(value);
            }

            // XPath 3.0: round($value, $precision) — round to N decimal places
            if (args.size() >= 2) {
                int precision = (int) args.get(1).asNumber();
                double factor = Math.pow(10.0, precision);
                double scaled = value * factor;
                // Round half to even for negative, half away from zero for positive
                double rounded = Math.floor(scaled + 0.5);
                return XPathNumber.of(rounded / factor);
            }

            // XPath 1.0: round-half-to-positive-infinity
            if (value < 0 && value > -0.5) {
                return XPathNumber.of(-0.0);
            }
            
            return XPathNumber.of(Math.floor(value + 0.5));
        }
    };

    // ========== XPath 2.0/3.0 Number Functions ==========
    
    /**
     * XPath 2.0 abs() function.
     * 
     * <p>Returns the absolute value of a number.
     * 
     * <p>Signature: abs(numeric?) → numeric?
     * 
     * @see <a href="https://www.w3.org/TR/xpath20/#function-abs">XPath 2.0 abs()</a>
     */
    public static final Function ABS = new Function() {
        @Override public String getName() { return "abs"; }
        @Override public int getMinArgs() { return 1; }
        @Override public int getMaxArgs() { return 1; }
        @Override public ArgType[] getArgumentTypes() { return new ArgType[] { ArgType.NUMERIC }; }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            XPathValue arg = args.get(0);
            if (arg == null || (arg instanceof XPathSequence && ((XPathSequence)arg).isEmpty())) {
                return XPathSequence.EMPTY;
            }
            double value = arg.asNumber();
            return XPathNumber.of(Math.abs(value));
        }
    };
    
    /**
     * XPath 2.0 round-half-to-even() function.
     * 
     * <p>Rounds a number to the nearest value with the specified precision, using
     * round-half-to-even (banker's rounding) semantics.
     * 
     * <p>Signature: round-half-to-even(numeric?, integer?) → numeric?
     * 
     * @see <a href="https://www.w3.org/TR/xpath20/#function-round-half-to-even">XPath 2.0 round-half-to-even()</a>
     */
    public static final Function ROUND_HALF_TO_EVEN = new Function() {
        @Override public String getName() { return "round-half-to-even"; }
        @Override public int getMinArgs() { return 1; }
        @Override public int getMaxArgs() { return 2; }
        @Override public ArgType[] getArgumentTypes() { return new ArgType[] { ArgType.NUMERIC, ArgType.NUMERIC }; }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            XPathValue arg = args.get(0);
            if (arg == null || (arg instanceof XPathSequence && ((XPathSequence)arg).isEmpty())) {
                return XPathSequence.EMPTY;
            }
            
            double value = arg.asNumber();
            if (Double.isNaN(value) || Double.isInfinite(value)) {
                return XPathNumber.of(value);
            }
            
            int precision = 0;
            if (args.size() > 1) {
                precision = (int) args.get(1).asNumber();
            }
            
            BigDecimal bd = BigDecimal.valueOf(value);
            bd = bd.setScale(precision, RoundingMode.HALF_EVEN);
            return XPathNumber.of(bd.doubleValue());
        }
    };
    
    /**
     * XPath 2.0 min() function.
     * 
     * <p>Returns the minimum value from a sequence of comparable items.
     * Supports both numeric comparison and string comparison with collation.
     * 
     * <p>Signature: min(item()*, collation?) → atomic?
     * 
     * @see <a href="https://www.w3.org/TR/xpath20/#function-min">XPath 2.0 min()</a>
     */
    public static final Function MIN = new Function() {
        @Override public String getName() { return "min"; }
        @Override public int getMinArgs() { return 1; }
        @Override public int getMaxArgs() { return 2; }  // Can have collation

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            List<XPathValue> items = getValues(args.get(0));
            if (items.isEmpty()) {
                return XPathSequence.EMPTY;
            }
            
            // Check if all values are strings (for collation-based comparison)
            boolean allStrings = true;
            boolean allDateTimes = true;
            for (XPathValue v : items) {
                if (!(v instanceof XPathString)) {
                    allStrings = false;
                }
                if (!(v instanceof XPathDateTime)) {
                    allDateTimes = false;
                }
            }
            
            if (allStrings) {
                // Use collation for string comparison
                Collation collation;
                if (args.size() > 1) {
                    String collUri = args.get(1).asString();
                    collation = Collation.forUri(collUri);
                } else {
                    String defaultUri = context.getDefaultCollation();
                    collation = Collation.forUri(defaultUri != null ? defaultUri : Collation.CODEPOINT_URI);
                }
                
                XPathValue minValue = items.get(0);
                for (int i = 1; i < items.size(); i++) {
                    XPathValue current = items.get(i);
                    if (collation.compare(current.asString(), minValue.asString()) < 0) {
                        minValue = current;
                    }
                }
                return minValue;
            } else if (allDateTimes) {
                // Date/time/duration comparison using compareTo
                XPathDateTime minValue = (XPathDateTime) items.get(0);
                for (int i = 1; i < items.size(); i++) {
                    XPathDateTime current = (XPathDateTime) items.get(i);
                    if (current.compareTo(minValue) < 0) {
                        minValue = current;
                    }
                }
                return minValue;
            } else {
                // Numeric comparison
                double min = Double.POSITIVE_INFINITY;
                for (XPathValue item : items) {
                    double v = item.asNumber();
                    if (Double.isNaN(v)) {
                        return XPathNumber.NaN;
                    }
                    if (v < min) {
                        min = v;
                    }
                }
                return XPathNumber.of(min);
            }
        }
    };
    
    /**
     * XPath 2.0 max() function.
     * 
     * <p>Returns the maximum value from a sequence of comparable items.
     * Supports both numeric comparison and string comparison with collation.
     * 
     * <p>Signature: max(item()*, collation?) → atomic?
     * 
     * @see <a href="https://www.w3.org/TR/xpath20/#function-max">XPath 2.0 max()</a>
     */
    public static final Function MAX = new Function() {
        @Override public String getName() { return "max"; }
        @Override public int getMinArgs() { return 1; }
        @Override public int getMaxArgs() { return 2; }  // Can have collation

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            List<XPathValue> items = getValues(args.get(0));
            if (items.isEmpty()) {
                return XPathSequence.EMPTY;
            }
            
            // Check if all values are strings (for collation-based comparison)
            boolean allStrings = true;
            boolean allDateTimes = true;
            for (XPathValue v : items) {
                if (!(v instanceof XPathString)) {
                    allStrings = false;
                }
                if (!(v instanceof XPathDateTime)) {
                    allDateTimes = false;
                }
            }
            
            if (allStrings) {
                // Use collation for string comparison
                Collation collation;
                if (args.size() > 1) {
                    String collUri = args.get(1).asString();
                    collation = Collation.forUri(collUri);
                } else {
                    String defaultUri = context.getDefaultCollation();
                    collation = Collation.forUri(defaultUri != null ? defaultUri : Collation.CODEPOINT_URI);
                }
                
                XPathValue maxValue = items.get(0);
                for (int i = 1; i < items.size(); i++) {
                    XPathValue current = items.get(i);
                    if (collation.compare(current.asString(), maxValue.asString()) > 0) {
                        maxValue = current;
                    }
                }
                return maxValue;
            } else if (allDateTimes) {
                // Date/time/duration comparison using compareTo
                XPathDateTime maxValue = (XPathDateTime) items.get(0);
                for (int i = 1; i < items.size(); i++) {
                    XPathDateTime current = (XPathDateTime) items.get(i);
                    if (current.compareTo(maxValue) > 0) {
                        maxValue = current;
                    }
                }
                return maxValue;
            } else {
                // Numeric comparison
                double max = Double.NEGATIVE_INFINITY;
                for (XPathValue item : items) {
                    double v = item.asNumber();
                    if (Double.isNaN(v)) {
                        return XPathNumber.NaN;
                    }
                    if (v > max) {
                        max = v;
                    }
                }
                return XPathNumber.of(max);
            }
        }
    };
    
    /**
     * XPath 2.0 avg() function.
     * 
     * <p>Returns the average (arithmetic mean) of a sequence of numeric values.
     * 
     * <p>Signature: avg(numeric*) → numeric?
     * 
     * @see <a href="https://www.w3.org/TR/xpath20/#function-avg">XPath 2.0 avg()</a>
     */
    public static final Function AVG = new Function() {
        @Override public String getName() { return "avg"; }
        @Override public int getMinArgs() { return 1; }
        @Override public int getMaxArgs() { return 1; }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            List<Double> values = getNumericValues(args.get(0));
            if (values.isEmpty()) {
                return XPathSequence.EMPTY;
            }
            
            double sum = 0;
            for (Double v : values) {
                if (Double.isNaN(v)) {
                    return XPathNumber.NaN;
                }
                sum += v;
            }
            return XPathNumber.of(sum / values.size());
        }
    };
    
    /**
     * XPath 2.0 format-integer() function.
     * 
     * <p>Formats an integer according to a picture string. Supports decimal, alphabetic,
     * Roman numeral, and word formats.
     * 
     * <p>Signature: format-integer(integer?, string, string?) → string
     * 
     * @see <a href="https://www.w3.org/TR/xpath-functions-30/#func-format-integer">XPath 3.0 format-integer()</a>
     */
    public static final Function FORMAT_INTEGER = new Function() {
        @Override public String getName() { return "format-integer"; }
        @Override public int getMinArgs() { return 2; }
        @Override public int getMaxArgs() { return 3; }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            XPathValue arg = args.get(0);
            if (arg == null || (arg instanceof XPathSequence && ((XPathSequence)arg).isEmpty())) {
                return XPathString.EMPTY;
            }
            
            long value = (long) arg.asNumber();
            String picture = args.get(1).asString();
            String language = args.size() > 2 ? args.get(2).asString() : "en";
            
            return XPathString.of(formatInteger(value, picture, language));
        }
        
        private String formatInteger(long value, String picture, String language) throws XPathException {
            if (picture.isEmpty()) {
                throw new XPathException("Empty picture string in format-integer");
            }
            
            // Parse picture string: [prefix]primary-format[suffix]
            // Primary formats: 1, 01, A, a, I, i, W, w, Ww, etc.
            String prefix = "";
            String suffix = "";
            String format = picture;
            
            // Simple implementation - handle common formats
            boolean negative = value < 0;
            long absValue = Math.abs(value);
            String result;
            
            if (format.equals("1") || format.matches("0+1?")) {
                // Decimal with optional leading zeros
                int minWidth = format.length();
                result = String.format("%0" + minWidth + "d", absValue);
            } else if (format.equals("A")) {
                // Upper-case letters: A, B, C, ... Z, AA, AB, ...
                result = toAlphabetic(absValue, true);
            } else if (format.equals("a")) {
                // Lower-case letters: a, b, c, ... z, aa, ab, ...
                result = toAlphabetic(absValue, false);
            } else if (format.equals("I")) {
                // Upper-case Roman numerals
                result = toRoman(absValue, true);
            } else if (format.equals("i")) {
                // Lower-case Roman numerals
                result = toRoman(absValue, false);
            } else if (format.equals("W") || format.equals("w") || format.equals("Ww")) {
                // Words (English)
                result = toWords(absValue, format);
            } else if (format.matches("\\d+")) {
                // Numeric with minimum width
                int minWidth = format.length();
                result = String.format("%0" + minWidth + "d", absValue);
            } else if (format.contains(",") || format.contains("#")) {
                // Decimal format with grouping separator
                result = formatDecimalWithGrouping(absValue, format);
            } else {
                // Default: decimal
                result = Long.toString(absValue);
            }
            
            if (negative) {
                result = "-" + result;
            }
            
            return prefix + result + suffix;
        }
        
        private String toAlphabetic(long value, boolean upperCase) {
            if (value <= 0) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            while (value > 0) {
                value--;  // Convert to 0-based
                int digit = (int)(value % 26);
                char c = (char)((upperCase ? 'A' : 'a') + digit);
                sb.insert(0, c);
                value /= 26;
            }
            return sb.toString();
        }
        
        private static final int MAX_ROMAN_NUMERAL = 3999;

        private String toRoman(long value, boolean upperCase) {
            if (value <= 0 || value > MAX_ROMAN_NUMERAL) {
                return Long.toString(value);  // Out of range for Roman numerals
            }
            
            String[] thousands = {"", "M", "MM", "MMM"};
            String[] hundreds = {"", "C", "CC", "CCC", "CD", "D", "DC", "DCC", "DCCC", "CM"};
            String[] tens = {"", "X", "XX", "XXX", "XL", "L", "LX", "LXX", "LXXX", "XC"};
            String[] ones = {"", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX"};
            
            String result = thousands[(int)(value / 1000)] +
                           hundreds[(int)((value % 1000) / 100)] +
                           tens[(int)((value % 100) / 10)] +
                           ones[(int)(value % 10)];
            
            return upperCase ? result : result.toLowerCase();
        }
        
        /**
         * Formats a decimal number with grouping separator.
         * Picture format: [#]*[,][0]+
         * # = optional digit
         * , = grouping separator (default every 3 digits)
         * 0 = mandatory digit
         */
        private String formatDecimalWithGrouping(long value, String format) {
            // Parse the format to extract grouping separator and minimum digits
            char groupingSeparator = ',';
            int groupingSize = 3;
            int minDigits = 0;
            
            // Find the grouping separator position and minimum digit count
            int sepIndex = format.indexOf(',');
            if (sepIndex >= 0) {
                // Count digits after the separator to determine grouping size
                String afterSep = format.substring(sepIndex + 1);
                int digitsAfterSep = afterSep.replace("#", "").length();
                if (digitsAfterSep > 0) {
                    groupingSize = digitsAfterSep;
                }
            }
            
            // Count minimum required digits (0s in the format)
            for (char c : format.toCharArray()) {
                if (c == '0') {
                    minDigits++;
                }
            }
            
            // Format the number with minimum digits
            String numStr = Long.toString(value);
            while (numStr.length() < minDigits) {
                numStr = "0" + numStr;
            }
            
            // Add grouping separators
            if (sepIndex >= 0 && numStr.length() > groupingSize) {
                StringBuilder sb = new StringBuilder();
                int count = 0;
                for (int i = numStr.length() - 1; i >= 0; i--) {
                    if (count > 0 && count % groupingSize == 0) {
                        sb.insert(0, groupingSeparator);
                    }
                    sb.insert(0, numStr.charAt(i));
                    count++;
                }
                return sb.toString();
            }
            
            return numStr;
        }
        
        private String toWords(long value, String format) {
            if (value == 0) {
                return format.equals("W") ? "ZERO" : format.equals("w") ? "zero" : "Zero";
            }
            
            StringBuilder sb = new StringBuilder();
            buildWords(value, sb);
            return applyWordCase(sb.toString(), format);
        }
        
        private void buildWords(long value, StringBuilder sb) {
            String[] ones = {"", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
                            "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen",
                            "seventeen", "eighteen", "nineteen"};
            String[] tens = {"", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety"};
            
            if (value < 20) {
                sb.append(ones[(int) value]);
            } else if (value < 100) {
                sb.append(tens[(int) (value / 10)]);
                if (value % 10 > 0) {
                    sb.append('-');
                    sb.append(ones[(int) (value % 10)]);
                }
            } else if (value < 1000) {
                sb.append(ones[(int) (value / 100)]);
                sb.append(" hundred");
                if (value % 100 > 0) {
                    sb.append(' ');
                    buildWords(value % 100, sb);
                }
            } else if (value < 1000000) {
                buildWords(value / 1000, sb);
                sb.append(" thousand");
                if (value % 1000 > 0) {
                    sb.append(' ');
                    buildWords(value % 1000, sb);
                }
            } else {
                sb.append(Long.toString(value));
            }
        }
        
        private String applyWordCase(String word, String format) {
            if (format.equals("W")) {
                return word.toUpperCase();
            } else if (format.equals("Ww")) {
                // Capitalize first letter of each word
                StringBuilder sb = new StringBuilder();
                boolean capitalizeNext = true;
                for (char c : word.toCharArray()) {
                    if (Character.isWhitespace(c) || c == '-') {
                        capitalizeNext = true;
                        sb.append(c);
                    } else if (capitalizeNext) {
                        sb.append(Character.toUpperCase(c));
                        capitalizeNext = false;
                    } else {
                        sb.append(c);
                    }
                }
                return sb.toString();
            }
            return word;  // lowercase
        }
    };

    /**
     * Helper to extract numeric values from a sequence or node-set.
     *
     * @param value the XPath value to extract numbers from
     * @return list of numeric values
     */
    private static List<Double> getNumericValues(XPathValue value) {
        List<Double> result = new ArrayList<>();
        
        if (value == null) {
            return result;
        }
        
        if (value instanceof XPathSequence) {
            XPathSequence seq = (XPathSequence) value;
            for (XPathValue item : seq) {
                result.add(item.asNumber());
            }
        } else if (value instanceof XPathNodeSet) {
            XPathNodeSet nodeSet = (XPathNodeSet) value;
            for (XPathNode node : nodeSet) {
                String str = node.getStringValue().trim();
                try {
                    result.add(Double.parseDouble(str));
                } catch (NumberFormatException e) {
                    result.add(Double.NaN);
                }
            }
        } else {
            result.add(value.asNumber());
        }
        
        return result;
    }

    /**
     * Helper to extract values from a sequence or node-set.
     *
     * @param value the XPath value to extract from
     * @return list of XPath values
     */
    private static List<XPathValue> getValues(XPathValue value) {
        List<XPathValue> result = new ArrayList<>();
        
        if (value == null) {
            return result;
        }
        
        if (value instanceof XPathSequence) {
            XPathSequence seq = (XPathSequence) value;
            for (XPathValue item : seq) {
                result.add(item);
            }
        } else if (value instanceof XPathNodeSet) {
            XPathNodeSet nodeSet = (XPathNodeSet) value;
            for (XPathNode node : nodeSet) {
                result.add(XPathString.of(node.getStringValue()));
            }
        } else {
            result.add(value);
        }
        
        return result;
    }

    /**
     * Returns all number functions (XPath 1.0 and 2.0/3.0).
     *
     * @return array of all number function implementations
     */
    public static Function[] getAll() {
        return new Function[] { NUMBER, SUM, FLOOR, CEILING, ROUND, ABS, ROUND_HALF_TO_EVEN, MIN, MAX, AVG, FORMAT_INTEGER };
    }

}

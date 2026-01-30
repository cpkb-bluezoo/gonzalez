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

    // ========== XPath 2.0/3.0 Number Functions ==========
    
    /** abs(number) - absolute value (XPath 2.0) */
    public static final Function ABS = new Function() {
        @Override public String getName() { return "abs"; }
        @Override public int getMinArgs() { return 1; }
        @Override public int getMaxArgs() { return 1; }

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
    
    /** round-half-to-even(number, precision?) - banker's rounding (XPath 2.0) */
    public static final Function ROUND_HALF_TO_EVEN = new Function() {
        @Override public String getName() { return "round-half-to-even"; }
        @Override public int getMinArgs() { return 1; }
        @Override public int getMaxArgs() { return 2; }

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
    
    /** min(sequence) - minimum value (XPath 2.0) */
    public static final Function MIN = new Function() {
        @Override public String getName() { return "min"; }
        @Override public int getMinArgs() { return 1; }
        @Override public int getMaxArgs() { return 2; }  // Can have collation

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            List<Double> values = getNumericValues(args.get(0));
            if (values.isEmpty()) {
                return XPathSequence.EMPTY;
            }
            
            double min = Double.POSITIVE_INFINITY;
            for (Double v : values) {
                if (Double.isNaN(v)) {
                    return XPathNumber.NaN;
                }
                if (v < min) {
                    min = v;
                }
            }
            return XPathNumber.of(min);
        }
    };
    
    /** max(sequence) - maximum value (XPath 2.0) */
    public static final Function MAX = new Function() {
        @Override public String getName() { return "max"; }
        @Override public int getMinArgs() { return 1; }
        @Override public int getMaxArgs() { return 2; }  // Can have collation

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            List<Double> values = getNumericValues(args.get(0));
            if (values.isEmpty()) {
                return XPathSequence.EMPTY;
            }
            
            double max = Double.NEGATIVE_INFINITY;
            for (Double v : values) {
                if (Double.isNaN(v)) {
                    return XPathNumber.NaN;
                }
                if (v > max) {
                    max = v;
                }
            }
            return XPathNumber.of(max);
        }
    };
    
    /** avg(sequence) - average value (XPath 2.0) */
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
    
    /** format-integer(value, picture, language?) - format an integer (XPath 2.0) */
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
        
        private String toRoman(long value, boolean upperCase) {
            if (value <= 0 || value > 3999) {
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
        
        private String toWords(long value, String format) {
            if (value == 0) {
                return format.equals("W") ? "ZERO" : format.equals("w") ? "zero" : "Zero";
            }
            
            String[] ones = {"", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
                            "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen",
                            "seventeen", "eighteen", "nineteen"};
            String[] tens = {"", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety"};
            
            if (value < 20) {
                String result = ones[(int)value];
                return applyWordCase(result, format);
            } else if (value < 100) {
                String result = tens[(int)(value / 10)];
                if (value % 10 > 0) {
                    result += "-" + ones[(int)(value % 10)];
                }
                return applyWordCase(result, format);
            } else if (value < 1000) {
                String result = ones[(int)(value / 100)] + " hundred";
                if (value % 100 > 0) {
                    result += " " + toWords(value % 100, "w");
                }
                return applyWordCase(result, format);
            } else if (value < 1000000) {
                String result = toWords(value / 1000, "w") + " thousand";
                if (value % 1000 > 0) {
                    result += " " + toWords(value % 1000, "w");
                }
                return applyWordCase(result, format);
            } else {
                return Long.toString(value);  // Too large for simple words
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

    /** Returns all number functions. */
    public static Function[] getAll() {
        return new Function[] { NUMBER, SUM, FLOOR, CEILING, ROUND, ABS, ROUND_HALF_TO_EVEN, MIN, MAX, AVG, FORMAT_INTEGER };
    }

}

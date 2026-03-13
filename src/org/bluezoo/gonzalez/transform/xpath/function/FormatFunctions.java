/*
 * FormatFunctions.java
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

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.bluezoo.gonzalez.transform.compiler.CompiledStylesheet;
import org.bluezoo.gonzalez.transform.runtime.TransformContext;
import org.bluezoo.gonzalez.transform.xpath.XPathContext;
import org.bluezoo.gonzalez.transform.xpath.expr.XPathException;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNode;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNodeSet;
import org.bluezoo.gonzalez.transform.xpath.type.XPathSequence;
import org.bluezoo.gonzalez.transform.xpath.type.XPathString;
import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;

/**
 * XSLT format-number() and generate-id() function implementations.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
final class FormatFunctions {

    private FormatFunctions() {
    }

    static Function formatNumber() {
        return new FormatNumberFunction();
    }

    static Function generateId() {
        return new GenerateIdFunction();
    }

    /**
     * XSLT format-number() function.
     *
     * <p>Formats a number according to a format pattern string. Supports custom decimal formats
     * defined via xsl:decimal-format.
     *
     * <p>Signature: format-number(number, string, string?) → string
     *
     * @see <a href="https://www.w3.org/TR/xslt/#function-format-number">XSLT 1.0 format-number()</a>
     */
    private static class FormatNumberFunction implements Function {
        @Override
        public String getName() { return "format-number"; }

        @Override
        public int getMinArgs() { return 2; }

        @Override
        public int getMaxArgs() { return 3; }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            XPathValue numArg = args.size() > 0 ? args.get(0) : null;
            XPathValue patArg = args.size() > 1 ? args.get(1) : null;
            String formatName = args.size() > 2 ? args.get(2).asString() : null;

            double number = (numArg != null) ? numArg.asNumber() : Double.NaN;
            String pattern = (patArg != null) ? patArg.asString() : "#";

            // Resolve QName prefix in format name to expanded name
            if (formatName != null && formatName.contains(":")) {
                int colonIdx = formatName.indexOf(':');
                String prefix = formatName.substring(0, colonIdx);
                String localName = formatName.substring(colonIdx + 1);
                String nsUri = context.resolveNamespacePrefix(prefix);
                if (nsUri != null) {
                    formatName = "{" + nsUri + "}" + localName;
                }
            }

            // Get decimal format from stylesheet if available
            CompiledStylesheet.DecimalFormatInfo decFormat = null;
            if (context instanceof TransformContext) {
                TransformContext tc = (TransformContext) context;
                CompiledStylesheet stylesheet = tc.getStylesheet();
                if (stylesheet != null) {
                    decFormat = stylesheet.getDecimalFormat(formatName);
                    // XTDE1280: named decimal-format must be declared
                    if (formatName != null && decFormat == null) {
                        throw new XPathException("XTDE1280: Unknown decimal-format name: '" + formatName + "'");
                    }
                }
            }

            // Default format symbols (as code points, supporting non-BMP)
            int decimalSepCp = '.';
            int groupingSepCp = ',';
            int minusSignCp = '-';
            int percentCp = '%';
            int perMilleCp = '\u2030';
            int zeroDigitCp = '0';
            int digitCp = '#';
            int patternSepCp = ';';
            int exponentSepCp = 'e';
            String infinity = "Infinity";
            String nan = "NaN";

            if (decFormat != null) {
                decimalSepCp = decFormat.decimalSeparatorCp;
                groupingSepCp = decFormat.groupingSeparatorCp;
                minusSignCp = decFormat.minusSignCp;
                percentCp = decFormat.percentCp;
                perMilleCp = decFormat.perMilleCp;
                zeroDigitCp = decFormat.zeroDigitCp;
                digitCp = decFormat.digitCp;
                patternSepCp = decFormat.patternSeparatorCp;
                exponentSepCp = decFormat.exponentSeparatorCp;
                infinity = decFormat.infinity;
                nan = decFormat.nan;
            }

            // Handle special values
            if (Double.isNaN(number)) {
                return XPathString.of(nan);
            }
            if (Double.isInfinite(number)) {
                if (number < 0) {
                    String ms = new String(Character.toChars(minusSignCp));
                    return XPathString.of(ms + infinity);
                }
                return XPathString.of(infinity);
            }

            // When non-BMP symbols are used, translate the pattern to use
            // BMP placeholders, process with Java DecimalFormat, then
            // restore the non-BMP characters in the result.
            boolean hasNonBmp = decFormat != null && decFormat.hasNonBmpSymbols();

            // BMP working chars (may differ from actual code points when non-BMP)
            char decimalSep = hasNonBmp ? '.' : (char) decimalSepCp;
            char groupingSep = hasNonBmp ? ',' : (char) groupingSepCp;
            char minusSign = hasNonBmp ? '-' : (char) minusSignCp;
            char percent = hasNonBmp ? '%' : (char) percentCp;
            char perMille = hasNonBmp ? '\u2030' : (char) perMilleCp;
            char zeroDigit = hasNonBmp ? '0' : (char) zeroDigitCp;
            char digit = hasNonBmp ? '#' : (char) digitCp;
            char patternSep = hasNonBmp ? ';' : (char) patternSepCp;
            char exponentSep = hasNonBmp ? 'e' : (char) exponentSepCp;

            if (hasNonBmp) {
                pattern = translateNonBmpPattern(pattern,
                    decimalSepCp, groupingSepCp, percentCp, perMilleCp,
                    zeroDigitCp, digitCp, patternSepCp, exponentSepCp);
            }

            // Validate pattern before processing (FODF1310)
            validatePattern(pattern, decimalSep, groupingSep, percent, perMille,
                patternSep, digit, zeroDigit, exponentSep);

            // Split into positive and negative sub-patterns
            String activePattern;
            boolean hasExplicitNegativeSubpattern = pattern.indexOf(patternSep) >= 0;
            if (number < 0 && hasExplicitNegativeSubpattern) {
                int sepPos = pattern.indexOf(patternSep);
                activePattern = pattern.substring(sepPos + 1);
                number = Math.abs(number);
            } else if (number < 0) {
                activePattern = pattern;
            } else {
                if (hasExplicitNegativeSubpattern) {
                    int sepPos = pattern.indexOf(patternSep);
                    activePattern = pattern.substring(0, sepPos);
                } else {
                    activePattern = pattern;
                }
            }

            boolean hasPerMille = activePattern.indexOf(perMille) >= 0;
            boolean hasPercent = activePattern.indexOf(percent) >= 0;

            int[] groupingSizes = parseGroupingSizes(activePattern, decimalSep,
                groupingSep, digit, zeroDigit);

            boolean hasExponentSep = activePattern.indexOf(exponentSep) >= 0;

            String javaPattern = translatePattern(activePattern, decimalSep, groupingSep,
                minusSign, percent, perMille, zeroDigit, digit, patternSep, exponentSep);

            try {
                DecimalFormatSymbols symbols = new DecimalFormatSymbols();
                symbols.setDecimalSeparator(decimalSep);
                symbols.setGroupingSeparator(groupingSep);
                symbols.setPercent(percent);
                symbols.setPerMill(perMille);
                symbols.setZeroDigit(zeroDigit);
                symbols.setDigit(digit);
                symbols.setPatternSeparator(patternSep);
                symbols.setInfinity(infinity);
                symbols.setNaN(nan);

                DecimalFormat df = new DecimalFormat(javaPattern, symbols);
                df.setGroupingUsed(false);
                BigDecimal bd = BigDecimal.valueOf(number);
                String result = df.format(bd);

                if (!hasExplicitNegativeSubpattern && number < 0) {
                    if (minusSign != '-' && result.startsWith("-")) {
                        result = minusSign + result.substring(1);
                    }
                }

                if (hasPerMille && perMille != '\u2030') {
                    result = result.replace('\u2030', perMille);
                }
                if (hasPercent && percent != '%') {
                    result = result.replace('%', percent);
                }
                if (hasExponentSep && exponentSep != 'E') {
                    result = result.replace('E', exponentSep);
                }

                if (groupingSizes != null && !hasExponentSep) {
                    result = applyGroupingSeparators(result, groupingSizes,
                        groupingSep, decimalSep, zeroDigit);
                }

                // Restore non-BMP characters in the result
                if (hasNonBmp) {
                    result = restoreNonBmpResult(result,
                        decimalSepCp, groupingSepCp, minusSignCp,
                        percentCp, perMilleCp, zeroDigitCp, exponentSepCp);
                }

                return XPathString.of(result);
            } catch (IllegalArgumentException e) {
                throw new XPathException("FODF1310: Invalid format-number picture string: " + pattern +
                    " - " + e.getMessage());
            }
        }

        /**
         * Validates a format-number picture string according to XSLT/XPath rules.
         * Throws FODF1310 for invalid patterns.
         */
        private void validatePattern(String pattern, char decimalSep, char groupingSep,
                char percent, char perMille, char patternSep, char digit, char zeroDigit,
                char exponentSep) throws XPathException {
            // Split into positive and negative sub-patterns
            String[] subPatterns = splitPattern(pattern, patternSep);

            // Check for too many sub-patterns (only positive and negative allowed)
            if (subPatterns.length > 2) {
                throw new XPathException("FODF1310: Invalid picture string - too many sub-patterns (found " +
                    subPatterns.length + " pattern separators)");
            }

            // Validate each sub-pattern
            for (String subPattern : subPatterns) {
                validateSubPattern(subPattern, decimalSep, groupingSep, percent,
                    perMille, digit, zeroDigit, exponentSep);
            }
        }

        /**
         * Splits a pattern by the pattern separator, but not within quoted literals.
         */
        private String[] splitPattern(String pattern, char patternSep) {
            List<String> parts = new ArrayList<String>();
            StringBuilder current = new StringBuilder();
            boolean inQuote = false;

            for (int i = 0; i < pattern.length(); i++) {
                char c = pattern.charAt(i);
                if (c == '\'') {
                    inQuote = !inQuote;
                    current.append(c);
                } else if (c == patternSep && !inQuote) {
                    parts.add(current.toString());
                    current = new StringBuilder();
                } else {
                    current.append(c);
                }
            }
            parts.add(current.toString());
            return parts.toArray(new String[0]);
        }

        /**
         * Validates a single sub-pattern for format-number.
         */
        private void validateSubPattern(String subPattern, char decimalSep, char groupingSep,
                char percent, char perMille, char digit, char zeroDigit,
                char exponentSep) throws XPathException {
            int decimalCount = 0;
            int percentCount = 0;
            int perMilleCount = 0;
            boolean inQuote = false;

            // First pass: find the mantissa boundaries (first and last digit positions)
            int firstDigitPos = -1;
            int lastDigitPos = -1;
            for (int i = 0; i < subPattern.length(); i++) {
                char c = subPattern.charAt(i);
                if (c == '\'') {
                    inQuote = !inQuote;
                    continue;
                }
                if (inQuote) {
                    continue;
                }

                if (c == digit || c == zeroDigit
                        || isInZeroDigitFamily(c, zeroDigit)) {
                    if (firstDigitPos < 0) firstDigitPos = i;
                    lastDigitPos = i;
                }
            }

            // Second pass: validate the pattern
            inQuote = false;
            for (int i = 0; i < subPattern.length(); i++) {
                char c = subPattern.charAt(i);
                if (c == '\'') {
                    inQuote = !inQuote;
                    continue;
                }
                if (inQuote) {
                    continue; // Ignore characters in quoted literals
                }

                boolean inMantissa = (firstDigitPos >= 0 && i >= firstDigitPos && i <= lastDigitPos);
                boolean isDigitChar = (c == digit || c == zeroDigit
                        || isInZeroDigitFamily(c, zeroDigit));

                if (c == decimalSep) {
                    decimalCount++;
                    if (decimalCount > 1) {
                        throw new XPathException("FODF1310: Invalid picture string - " +
                            "multiple decimal separators in sub-pattern: " + subPattern);
                    }
                } else if (c == percent) {
                    percentCount++;
                    if (percentCount > 1) {
                        throw new XPathException("FODF1310: Invalid picture string - " +
                            "multiple percent signs in sub-pattern: " + subPattern);
                    }
                } else if (c == perMille) {
                    perMilleCount++;
                    if (perMilleCount > 1) {
                        throw new XPathException("FODF1310: Invalid picture string - " +
                            "multiple per-mille signs in sub-pattern: " + subPattern);
                    }
                } else if (inMantissa && !isDigitChar && c != groupingSep
                        && c != decimalSep && c != exponentSep) {
                    throw new XPathException("FODF1310: Invalid picture string - " +
                        "invalid character '" + c + "' in digit pattern: " + subPattern);
                }
            }

            // Check for both percent and per-mille
            if (percentCount > 0 && perMilleCount > 0) {
                throw new XPathException("FODF1310: Invalid picture string - " +
                    "cannot have both percent and per-mille in sub-pattern: " + subPattern);
            }

            // Must have at least one digit placeholder
            if (firstDigitPos < 0) {
                throw new XPathException("FODF1310: Invalid picture string - " +
                    "no digit placeholder in sub-pattern: " + subPattern);
            }

            // Grouping separator must not be adjacent to decimal separator
            for (int i = 0; i < subPattern.length() - 1; i++) {
                char c = subPattern.charAt(i);
                char next = subPattern.charAt(i + 1);
                if ((c == groupingSep && next == decimalSep)
                        || (c == decimalSep && next == groupingSep)) {
                    throw new XPathException("FODF1310",
                            "Invalid picture string - grouping separator "
                            + "adjacent to decimal separator: " + subPattern);
                }
            }
        }

        private String translatePattern(String pattern, char decimalSep, char groupingSep,
                char minusSign, char percent, char perMille, char zeroDigit, char digit,
                char patternSep, char exponentSep) {
            // Translate custom symbols to Java DecimalFormat standard symbols.
            // Grouping separators are stripped because we apply them manually
            // to support unequal grouping sizes (Java only supports uniform grouping).
            StringBuilder sb = new StringBuilder(pattern.length() + 8);
            for (int i = 0; i < pattern.length(); i++) {
                char c = pattern.charAt(i);
                if (c == decimalSep) {
                    sb.append('.');
                } else if (c == groupingSep) {
                    // Skip — grouping applied in post-processing
                    continue;
                } else if (c == exponentSep) {
                    sb.append('E');
                } else if (c == percent) {
                    sb.append('%');
                } else if (c == perMille) {
                    sb.append('\u2030');
                } else if (c == zeroDigit) {
                    sb.append('0');
                } else if (isInZeroDigitFamily(c, zeroDigit)) {
                    sb.append('0');
                } else if (c == digit) {
                    sb.append('#');
                } else if (c == patternSep) {
                    sb.append(';');
                } else if (isJavaPatternSpecial(c) && !isXsltFormatChar(c, decimalSep, groupingSep, percent, perMille, zeroDigit, digit, patternSep)) {
                    sb.append('\'');
                    sb.append(c);
                    sb.append('\'');
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }

        /**
         * Parses grouping sizes from the integer part of a picture string.
         * Returns null if no grouping separators are present.
         * The returned array contains [primarySize, secondarySize] where
         * secondarySize repeats for all remaining groups.
         */
        private int[] parseGroupingSizes(String pattern, char decimalSep,
                char groupingSep, char digit, char zeroDigit) {
            // Find the integer part (before decimal separator)
            int decPos = -1;
            for (int i = 0; i < pattern.length(); i++) {
                if (pattern.charAt(i) == decimalSep) {
                    decPos = i;
                    break;
                }
            }
            String intPart = (decPos >= 0)
                ? pattern.substring(0, decPos)
                : pattern;

            // Find grouping separator positions within the integer part,
            // measuring from the rightmost digit position
            List<Integer> sepPositions = new ArrayList<Integer>();
            int digitCount = 0;
            for (int i = intPart.length() - 1; i >= 0; i--) {
                char c = intPart.charAt(i);
                if (c == digit || c == zeroDigit || isInZeroDigitFamily(c, zeroDigit)) {
                    digitCount++;
                } else if (c == groupingSep) {
                    sepPositions.add(digitCount);
                }
            }

            if (sepPositions.isEmpty()) {
                return null;
            }

            int primary = sepPositions.get(0);
            if (primary <= 0) {
                return null;
            }

            if (sepPositions.size() == 1) {
                // Single separator: repeat at multiples of primary
                return new int[]{primary, primary};
            }

            // Check if positions are regular (evenly spaced at multiples of primary)
            boolean regular = true;
            for (int i = 0; i < sepPositions.size(); i++) {
                if (sepPositions.get(i) != primary * (i + 1)) {
                    regular = false;
                    break;
                }
            }

            if (regular) {
                return new int[]{primary, primary};
            }

            // Unequal grouping: return explicit positions only (negative secondary = no repeat)
            int[] result = new int[sepPositions.size() + 1];
            result[0] = -1;
            for (int i = 0; i < sepPositions.size(); i++) {
                result[i + 1] = sepPositions.get(i);
            }
            return result;
        }

        /**
         * Inserts grouping separators into the integer part of a formatted number.
         */
        private String applyGroupingSeparators(String formatted, int[] groupingSizes,
                char groupingSep, char decimalSep, char zeroDigit) {
            // Find where the integer part ends (at decimal separator or end)
            int decIdx = formatted.indexOf(decimalSep);

            // Find the start of digits (skip prefix like minus sign)
            int intStart = 0;
            for (int i = 0; i < formatted.length(); i++) {
                char c = formatted.charAt(i);
                if (c == zeroDigit || isInZeroDigitFamily(c, zeroDigit)
                        || (c >= '0' && c <= '9')) {
                    intStart = i;
                    break;
                }
            }
            int intEnd = (decIdx >= 0) ? decIdx : formatted.length();

            String prefix = formatted.substring(0, intStart);
            String intPart = formatted.substring(intStart, intEnd);
            String suffix = formatted.substring(intEnd);

            if (groupingSizes[0] == -1) {
                // Unequal grouping: use explicit positions only
                Set<Integer> positions = new HashSet<Integer>();
                for (int i = 1; i < groupingSizes.length; i++) {
                    positions.add(groupingSizes[i]);
                }
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < intPart.length(); i++) {
                    int posFromRight = intPart.length() - i;
                    if (positions.contains(posFromRight) && i > 0) {
                        sb.append(groupingSep);
                    }
                    sb.append(intPart.charAt(i));
                }
                return prefix + sb.toString() + suffix;
            }

            int primary = groupingSizes[0];
            int secondary = groupingSizes[1];

            if (intPart.length() <= primary) {
                return formatted;
            }

            StringBuilder sb = new StringBuilder();
            int pos = intPart.length();
            // First group (primary size)
            int groupStart = pos - primary;
            if (groupStart < 0) {
                groupStart = 0;
            }
            sb.insert(0, intPart.substring(groupStart, pos));
            pos = groupStart;
            // Remaining groups (secondary size)
            while (pos > 0) {
                groupStart = pos - secondary;
                if (groupStart < 0) {
                    groupStart = 0;
                }
                sb.insert(0, groupingSep);
                sb.insert(0, intPart.substring(groupStart, pos));
                pos = groupStart;
            }

            return prefix + sb.toString() + suffix;
        }

        /**
         * Translates non-BMP code points in a pattern string to BMP
         * placeholders so the pattern can be processed by char-based logic.
         */
        private String translateNonBmpPattern(String pattern,
                int decSepCp, int grpSepCp, int pctCp, int pmCp,
                int zeroCp, int digitCp, int patSepCp, int expSepCp) {
            StringBuilder sb = new StringBuilder();
            int len = pattern.length();
            int i = 0;
            while (i < len) {
                int cp = pattern.codePointAt(i);
                int advance = Character.charCount(cp);
                if (cp == decSepCp) {
                    sb.append('.');
                } else if (cp == grpSepCp) {
                    sb.append(',');
                } else if (cp == pctCp && Character.isSupplementaryCodePoint(cp)) {
                    sb.append('%');
                } else if (cp == pmCp && Character.isSupplementaryCodePoint(cp)) {
                    sb.append('\u2030');
                } else if (cp == patSepCp && Character.isSupplementaryCodePoint(cp)) {
                    sb.append(';');
                } else if (cp == expSepCp && Character.isSupplementaryCodePoint(cp)) {
                    sb.append('e');
                } else if (cp == zeroCp && Character.isSupplementaryCodePoint(cp)) {
                    sb.append('0');
                } else if (cp == digitCp && Character.isSupplementaryCodePoint(cp)) {
                    sb.append('#');
                } else if (Character.isSupplementaryCodePoint(zeroCp)) {
                    int offset = cp - zeroCp;
                    if (offset > 0 && offset <= 9) {
                        sb.append((char) ('0' + offset));
                    } else {
                        sb.appendCodePoint(cp);
                    }
                } else {
                    sb.appendCodePoint(cp);
                }
                i += advance;
            }
            return sb.toString();
        }

        /**
         * Restores non-BMP characters in the formatted result by replacing
         * BMP placeholders with their actual non-BMP strings.
         */
        private String restoreNonBmpResult(String result,
                int decSepCp, int grpSepCp, int minusCp,
                int pctCp, int pmCp, int zeroCp, int expSepCp) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < result.length(); i++) {
                char c = result.charAt(i);
                if (c == '.' && Character.isSupplementaryCodePoint(decSepCp)) {
                    sb.appendCodePoint(decSepCp);
                } else if (c == ',' && Character.isSupplementaryCodePoint(grpSepCp)) {
                    sb.appendCodePoint(grpSepCp);
                } else if (c == '-' && Character.isSupplementaryCodePoint(minusCp)) {
                    sb.appendCodePoint(minusCp);
                } else if (c == '%' && Character.isSupplementaryCodePoint(pctCp)) {
                    sb.appendCodePoint(pctCp);
                } else if (c == '\u2030' && Character.isSupplementaryCodePoint(pmCp)) {
                    sb.appendCodePoint(pmCp);
                } else if (Character.isSupplementaryCodePoint(zeroCp)
                        && c >= '0' && c <= '9') {
                    int offset = c - '0';
                    sb.appendCodePoint(zeroCp + offset);
                } else if (c == 'e' && Character.isSupplementaryCodePoint(expSepCp)) {
                    sb.appendCodePoint(expSepCp);
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }

        private boolean isInZeroDigitFamily(char c, char zeroDigit) {
            int offset = c - zeroDigit;
            return offset > 0 && offset <= 9;
        }

        private boolean isJavaPatternSpecial(char c) {
            return c == '0' || c == '#' || c == '.' || c == ',' ||
                   c == '%' || c == '\u2030' || c == ';' ||
                   c == '\u00A4' ||
                   (c >= '1' && c <= '9');
        }

        private boolean isXsltFormatChar(char c, char decimalSep, char groupingSep,
                char percent, char perMille, char zeroDigit, char digit, char patternSep) {
            if (c == decimalSep || c == groupingSep || c == percent || c == perMille ||
                c == zeroDigit || c == digit || c == patternSep) {
                return true;
            }
            int offset = c - zeroDigit;
            return offset > 0 && offset <= 9;
        }
    }

    /**
     * XSLT generate-id() function.
     *
     * <p>Generates a unique identifier for a node. The ID is based on the node's document order.
     *
     * <p>Signature: generate-id(node-set?) → string
     *
     * @see <a href="https://www.w3.org/TR/xslt/#function-generate-id">XSLT 1.0 generate-id()</a>
     */
    private static class GenerateIdFunction implements Function {
        @Override
        public String getName() { return "generate-id"; }

        @Override
        public int getMinArgs() { return 0; }

        @Override
        public int getMaxArgs() { return 1; }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            XPathNode node;
            if (args.isEmpty()) {
                node = context.getContextNode();
            } else {
                XPathValue arg = args.get(0);
                // Empty sequence returns ""
                if (arg.isSequence() && ((XPathSequence) arg).isEmpty()) {
                    return XPathString.of("");
                }
                if (!arg.isNodeSet()) {
                    throw new XPathException("generate-id() argument must be a node-set");
                }
                XPathNodeSet ns = arg.asNodeSet();
                if (ns.isEmpty()) {
                    return XPathString.of("");
                }
                node = ns.first();
            }

            if (node == null) {
                return XPathString.of("");
            }
            XPathNode root = node.getRoot();
            int docId = System.identityHashCode(root);
            String hex = Integer.toHexString(docId);
            return XPathString.of("d" + hex + "n" + node.getDocumentOrder());
        }
    }
}

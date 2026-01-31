/*
 * XSDTypeConverter.java
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

package org.bluezoo.gonzalez.schema.xsd;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.Period;
import java.time.Year;
import java.time.YearMonth;
import java.time.MonthDay;
import java.time.format.DateTimeParseException;
import java.util.Base64;

import javax.xml.namespace.QName;

/**
 * Converts XSD lexical values to Java objects.
 *
 * <p>This class provides conversion methods for all XSD Part 2 built-in
 * datatypes. Each method takes a lexical (string) representation and
 * returns the corresponding Java object.
 *
 * <p>The conversions follow the XSD specification for lexical-to-value
 * mappings, including whitespace handling:
 * <ul>
 *   <li>{@code preserve} - no whitespace normalization</li>
 *   <li>{@code replace} - all whitespace characters replaced with spaces</li>
 *   <li>{@code collapse} - whitespace collapsed to single spaces, trimmed</li>
 * </ul>
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class XSDTypeConverter {

    private XSDTypeConverter() {
        // Utility class
    }

    /**
     * Converts a lexical value to a Java object based on the XSD datatype.
     *
     * <p>This method performs lexical-to-value mapping according to XSD Part 2
     * specifications. It handles whitespace normalization based on the datatype's
     * whitespace facet, then converts the normalized string to the appropriate
     * Java type.
     *
     * <p>Supported conversions include:
     * <ul>
     *   <li>String types → {@link String}</li>
     *   <li>Numeric types → {@link java.math.BigDecimal}, {@link java.math.BigInteger},
     *       {@link Integer}, {@link Long}, etc.</li>
     *   <li>Boolean → {@link Boolean}</li>
     *   <li>Date/time types → {@link java.time.LocalDate}, {@link java.time.LocalDateTime},
     *       {@link java.time.OffsetDateTime}, etc.</li>
     *   <li>Binary types → {@code byte[]}</li>
     *   <li>URI types → {@link java.net.URI}</li>
     *   <li>QName types → {@link javax.xml.namespace.QName}</li>
     * </ul>
     *
     * <p>If conversion fails (e.g., invalid format), the original lexical value
     * is returned as a fallback. This allows the caller to handle conversion
     * errors gracefully.
     *
     * @param datatypeLocalName the XSD datatype local name (e.g., "integer", "date", "string")
     * @param lexicalValue the lexical representation of the value
     * @return the converted Java value, or the original lexical value if conversion fails
     */
    public static Object convert(String datatypeLocalName, String lexicalValue) {
        if (lexicalValue == null) {
            return null;
        }

        try {
            switch (datatypeLocalName) {
                // String types (no conversion needed)
                case "string":
                    return lexicalValue;
                case "normalizedString":
                    return normalizeReplace(lexicalValue);
                case "token":
                case "language":
                case "NMTOKEN":
                case "Name":
                case "NCName":
                case "ID":
                case "IDREF":
                case "ENTITY":
                    return normalizeCollapse(lexicalValue);
                case "NMTOKENS":
                case "IDREFS":
                case "ENTITIES":
                    return normalizeCollapse(lexicalValue).split("\\s+");

                // Boolean
                case "boolean":
                    return parseBoolean(lexicalValue);

                // Numeric types
                case "decimal":
                    return new BigDecimal(normalizeCollapse(lexicalValue));
                case "integer":
                    return new BigInteger(normalizeCollapse(lexicalValue));
                case "nonPositiveInteger":
                case "negativeInteger":
                case "nonNegativeInteger":
                case "positiveInteger":
                    return new BigInteger(normalizeCollapse(lexicalValue));
                case "long":
                    return Long.parseLong(normalizeCollapse(lexicalValue));
                case "int":
                    return Integer.parseInt(normalizeCollapse(lexicalValue));
                case "short":
                    return Short.parseShort(normalizeCollapse(lexicalValue));
                case "byte":
                    return Byte.parseByte(normalizeCollapse(lexicalValue));
                case "unsignedLong":
                    return new BigInteger(normalizeCollapse(lexicalValue));
                case "unsignedInt":
                    return Long.parseLong(normalizeCollapse(lexicalValue));
                case "unsignedShort":
                    return Integer.parseInt(normalizeCollapse(lexicalValue));
                case "unsignedByte":
                    return Short.parseShort(normalizeCollapse(lexicalValue));
                case "float":
                    return parseFloat(lexicalValue);
                case "double":
                    return parseDouble(lexicalValue);

                // Date/Time types
                case "dateTime":
                    return parseDateTime(lexicalValue);
                case "date":
                    return parseDate(lexicalValue);
                case "time":
                    return parseTime(lexicalValue);
                case "gYearMonth":
                    return parseYearMonth(lexicalValue);
                case "gYear":
                    return parseYear(lexicalValue);
                case "gMonthDay":
                    return parseMonthDay(lexicalValue);
                case "gDay":
                    return parseDay(lexicalValue);
                case "gMonth":
                    return parseMonth(lexicalValue);
                case "duration":
                    return parseDuration(lexicalValue);

                // Binary types
                case "base64Binary":
                    return Base64.getDecoder().decode(normalizeCollapse(lexicalValue));
                case "hexBinary":
                    return parseHexBinary(lexicalValue);

                // Other types
                case "anyURI":
                    return parseURI(lexicalValue);
                case "QName":
                case "NOTATION":
                    return parseQName(lexicalValue);

                default:
                    // Unknown type - return as string
                    return lexicalValue;
            }
        } catch (Exception e) {
            // Conversion failed - return original value
            return lexicalValue;
        }
    }

    /**
     * Whitespace normalization: replace (all whitespace → space).
     */
    private static String normalizeReplace(String value) {
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\t' || c == '\n' || c == '\r') {
                sb.append(' ');
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * Whitespace normalization: collapse (replace + merge spaces + trim).
     */
    private static String normalizeCollapse(String value) {
        StringBuilder sb = new StringBuilder(value.length());
        boolean inWhitespace = true; // Start true to trim leading
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                if (!inWhitespace) {
                    sb.append(' ');
                    inWhitespace = true;
                }
            } else {
                sb.append(c);
                inWhitespace = false;
            }
        }
        // Trim trailing space
        if (sb.length() > 0 && sb.charAt(sb.length() - 1) == ' ') {
            sb.setLength(sb.length() - 1);
        }
        return sb.toString();
    }

    /**
     * Parse xs:boolean (accepts "true", "false", "1", "0").
     */
    private static Boolean parseBoolean(String value) {
        value = normalizeCollapse(value);
        if ("true".equals(value) || "1".equals(value)) {
            return Boolean.TRUE;
        } else if ("false".equals(value) || "0".equals(value)) {
            return Boolean.FALSE;
        }
        throw new IllegalArgumentException("Invalid boolean: " + value);
    }

    /**
     * Parse xs:float (handles special values INF, -INF, NaN).
     */
    private static Float parseFloat(String value) {
        value = normalizeCollapse(value);
        if ("INF".equals(value)) {
            return Float.POSITIVE_INFINITY;
        } else if ("-INF".equals(value)) {
            return Float.NEGATIVE_INFINITY;
        } else if ("NaN".equals(value)) {
            return Float.NaN;
        }
        return Float.parseFloat(value);
    }

    /**
     * Parse xs:double (handles special values INF, -INF, NaN).
     */
    private static Double parseDouble(String value) {
        value = normalizeCollapse(value);
        if ("INF".equals(value)) {
            return Double.POSITIVE_INFINITY;
        } else if ("-INF".equals(value)) {
            return Double.NEGATIVE_INFINITY;
        } else if ("NaN".equals(value)) {
            return Double.NaN;
        }
        return Double.parseDouble(value);
    }

    /**
     * Parse xs:dateTime.
     */
    private static Object parseDateTime(String value) {
        value = normalizeCollapse(value);
        try {
            // Try with timezone first
            return OffsetDateTime.parse(value);
        } catch (DateTimeParseException e) {
            // Fall back to local date-time
            return LocalDateTime.parse(value);
        }
    }

    /**
     * Parse xs:date.
     */
    private static Object parseDate(String value) {
        value = normalizeCollapse(value);
        // XSD date can have timezone, but LocalDate doesn't support it
        // Strip timezone for simplicity (or use OffsetDate if needed)
        int tzIndex = value.indexOf('Z');
        if (tzIndex < 0) {
            tzIndex = value.indexOf('+');
        }
        if (tzIndex < 0) {
            tzIndex = value.lastIndexOf('-', value.length() - 6);
        }
        if (tzIndex > 0 && tzIndex > value.indexOf('-', 1)) {
            value = value.substring(0, tzIndex);
        }
        return LocalDate.parse(value);
    }

    /**
     * Parse xs:time.
     */
    private static Object parseTime(String value) {
        value = normalizeCollapse(value);
        try {
            return OffsetTime.parse(value);
        } catch (DateTimeParseException e) {
            return LocalTime.parse(value);
        }
    }

    /**
     * Parse xs:gYearMonth.
     */
    private static Object parseYearMonth(String value) {
        value = normalizeCollapse(value);
        // Strip timezone if present
        int tzIndex = value.indexOf('Z');
        if (tzIndex < 0) {
            tzIndex = value.indexOf('+');
        }
        if (tzIndex < 0) {
            int lastDash = value.lastIndexOf('-');
            if (lastDash > value.indexOf('-') + 1) {
                tzIndex = lastDash;
            }
        }
        if (tzIndex > 0) {
            value = value.substring(0, tzIndex);
        }
        return YearMonth.parse(value);
    }

    /**
     * Parse xs:gYear.
     */
    private static Object parseYear(String value) {
        value = normalizeCollapse(value);
        // Strip timezone if present
        int tzIndex = value.indexOf('Z');
        if (tzIndex < 0) {
            tzIndex = value.indexOf('+');
        }
        if (tzIndex < 0 && value.length() > 4) {
            int lastDash = value.lastIndexOf('-');
            if (lastDash > 0 && (value.charAt(0) != '-' || lastDash > 1)) {
                tzIndex = lastDash;
            }
        }
        if (tzIndex > 0) {
            value = value.substring(0, tzIndex);
        }
        return Year.parse(value);
    }

    /**
     * Parse xs:gMonthDay.
     */
    private static Object parseMonthDay(String value) {
        value = normalizeCollapse(value);
        // Format: --MM-DD, possibly with timezone
        int tzIndex = value.indexOf('Z');
        if (tzIndex < 0) {
            tzIndex = value.indexOf('+', 5);
        }
        if (tzIndex < 0 && value.length() > 7) {
            tzIndex = value.indexOf('-', 7);
        }
        if (tzIndex > 0) {
            value = value.substring(0, tzIndex);
        }
        return MonthDay.parse(value);
    }

    /**
     * Parse xs:gDay (---DD).
     */
    private static Integer parseDay(String value) {
        value = normalizeCollapse(value);
        // Format: ---DD
        if (value.startsWith("---")) {
            String dayPart = value.substring(3);
            int tzIndex = dayPart.indexOf('Z');
            if (tzIndex < 0) {
                tzIndex = dayPart.indexOf('+');
            }
            if (tzIndex < 0) {
                tzIndex = dayPart.indexOf('-');
            }
            if (tzIndex > 0) {
                dayPart = dayPart.substring(0, tzIndex);
            }
            return Integer.parseInt(dayPart);
        }
        throw new IllegalArgumentException("Invalid gDay: " + value);
    }

    /**
     * Parse xs:gMonth (--MM).
     */
    private static Integer parseMonth(String value) {
        value = normalizeCollapse(value);
        // Format: --MM
        if (value.startsWith("--")) {
            String monthPart = value.substring(2);
            int tzIndex = monthPart.indexOf('Z');
            if (tzIndex < 0) {
                tzIndex = monthPart.indexOf('+');
            }
            if (tzIndex < 0) {
                tzIndex = monthPart.indexOf('-');
            }
            if (tzIndex > 0) {
                monthPart = monthPart.substring(0, tzIndex);
            }
            return Integer.parseInt(monthPart);
        }
        throw new IllegalArgumentException("Invalid gMonth: " + value);
    }

    /**
     * Parse xs:duration.
     */
    private static Object parseDuration(String value) {
        value = normalizeCollapse(value);
        // XSD duration format: [-]PnYnMnDTnHnMnS
        // Java Duration only handles time, Period handles date
        // For simplicity, try Duration first (for time durations)
        try {
            // Duration.parse expects ISO 8601 format which is close to XSD
            return Duration.parse(value);
        } catch (DateTimeParseException e) {
            // Try Period for date-based durations
            try {
                return Period.parse(value);
            } catch (DateTimeParseException e2) {
                // Return as string
                return value;
            }
        }
    }

    /**
     * Parse xs:hexBinary.
     */
    private static byte[] parseHexBinary(String value) {
        value = normalizeCollapse(value);
        int len = value.length();
        if (len % 2 != 0) {
            throw new IllegalArgumentException("Odd-length hex string");
        }
        byte[] result = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            int high = Character.digit(value.charAt(i), 16);
            int low = Character.digit(value.charAt(i + 1), 16);
            if (high < 0 || low < 0) {
                throw new IllegalArgumentException("Invalid hex character");
            }
            result[i / 2] = (byte) ((high << 4) | low);
        }
        return result;
    }

    /**
     * Parse xs:anyURI.
     */
    private static URI parseURI(String value) throws URISyntaxException {
        value = normalizeCollapse(value);
        return new URI(value);
    }

    /**
     * Parse xs:QName (prefix:localName).
     */
    private static QName parseQName(String value) {
        value = normalizeCollapse(value);
        int colon = value.indexOf(':');
        if (colon > 0) {
            String prefix = value.substring(0, colon);
            String localPart = value.substring(colon + 1);
            // Namespace URI would need to be resolved from context
            // For now, return with empty namespace
            return new QName("", localPart, prefix);
        }
        return new QName(value);
    }
}

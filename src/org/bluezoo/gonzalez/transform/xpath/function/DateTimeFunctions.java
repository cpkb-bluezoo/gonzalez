/*
 * DateTimeFunctions.java
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
import org.bluezoo.gonzalez.transform.xpath.function.locale.DateTimeLocale;
import org.bluezoo.gonzalez.transform.xpath.type.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * XPath 2.0/3.0 date and time functions.
 *
 * <p>Implements:
 * <ul>
 *   <li>Component extraction: year-from-dateTime, year-from-date, month-from-dateTime, etc.</li>
 *   <li>Current date/time: current-dateTime, current-date, current-time</li>
 *   <li>Construction: dateTime (from date and time)</li>
 * </ul>
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class DateTimeFunctions {

    private DateTimeFunctions() {}

    /**
     * Returns all date/time functions (XPath 2.0/3.0).
     *
     * @return list of all date/time function implementations
     */
    public static List<Function> getAll() {
        List<Function> functions = new ArrayList<>();
        
        // Current date/time functions
        functions.add(CURRENT_DATE_TIME);
        functions.add(CURRENT_DATE);
        functions.add(CURRENT_TIME);
        
        // DateTime constructor
        functions.add(DATE_TIME);
        
        // Year extraction
        functions.add(YEAR_FROM_DATE_TIME);
        functions.add(YEAR_FROM_DATE);
        
        // Month extraction
        functions.add(MONTH_FROM_DATE_TIME);
        functions.add(MONTH_FROM_DATE);
        
        // Day extraction
        functions.add(DAY_FROM_DATE_TIME);
        functions.add(DAY_FROM_DATE);
        
        // Hour extraction
        functions.add(HOURS_FROM_DATE_TIME);
        functions.add(HOURS_FROM_TIME);
        
        // Minute extraction
        functions.add(MINUTES_FROM_DATE_TIME);
        functions.add(MINUTES_FROM_TIME);
        
        // Second extraction
        functions.add(SECONDS_FROM_DATE_TIME);
        functions.add(SECONDS_FROM_TIME);
        
        // Timezone extraction
        functions.add(TIMEZONE_FROM_DATE_TIME);
        functions.add(TIMEZONE_FROM_DATE);
        functions.add(TIMEZONE_FROM_TIME);
        
        // Duration component extraction
        functions.add(YEARS_FROM_DURATION);
        functions.add(MONTHS_FROM_DURATION);
        functions.add(DAYS_FROM_DURATION);
        functions.add(HOURS_FROM_DURATION);
        functions.add(MINUTES_FROM_DURATION);
        functions.add(SECONDS_FROM_DURATION);
        
        // Formatting functions
        functions.add(FORMAT_DATE_TIME);
        functions.add(FORMAT_DATE);
        functions.add(FORMAT_TIME);
        
        return functions;
    }

    // ========== Current date/time functions ==========
    
    /**
     * XPath 2.0 current-dateTime() function.
     * 
     * <p>Returns the current date and time as an xs:dateTime value.
     * 
     * <p>Signature: current-dateTime() → dateTime
     * 
     * @see <a href="https://www.w3.org/TR/xpath-functions-30/#func-current-dateTime">XPath 3.0 current-dateTime()</a>
     */
    public static final Function CURRENT_DATE_TIME = new Function() {
        @Override public String getName() { return "current-dateTime"; }
        @Override public int getMinArgs() { return 0; }
        @Override public int getMaxArgs() { return 0; }
        
        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            return XPathDateTime.now();
        }
    };
    
    /**
     * XPath 2.0 current-date() function.
     * 
     * <p>Returns the current date as an xs:date value.
     * 
     * <p>Signature: current-date() → date
     * 
     * @see <a href="https://www.w3.org/TR/xpath-functions-30/#func-current-date">XPath 3.0 current-date()</a>
     */
    public static final Function CURRENT_DATE = new Function() {
        @Override public String getName() { return "current-date"; }
        @Override public int getMinArgs() { return 0; }
        @Override public int getMaxArgs() { return 0; }
        
        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            return XPathDateTime.today();
        }
    };
    
    /**
     * XPath 2.0 current-time() function.
     * 
     * <p>Returns the current time as an xs:time value.
     * 
     * <p>Signature: current-time() → time
     * 
     * @see <a href="https://www.w3.org/TR/xpath-functions-30/#func-current-time">XPath 3.0 current-time()</a>
     */
    public static final Function CURRENT_TIME = new Function() {
        @Override public String getName() { return "current-time"; }
        @Override public int getMinArgs() { return 0; }
        @Override public int getMaxArgs() { return 0; }
        
        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            return XPathDateTime.currentTime();
        }
    };
    
    // ========== DateTime constructor ==========
    
    /**
     * XPath 2.0 dateTime() constructor function.
     * 
     * <p>Constructs an xs:dateTime from an xs:date and xs:time.
     * 
     * <p>Signature: dateTime(date, time) → dateTime?
     * 
     * @see <a href="https://www.w3.org/TR/xpath-functions-30/#func-dateTime">XPath 3.0 dateTime()</a>
     */
    public static final Function DATE_TIME = new Function() {
        @Override public String getName() { return "dateTime"; }
        @Override public int getMinArgs() { return 2; }
        @Override public int getMaxArgs() { return 2; }
        
        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            XPathValue arg0 = args.get(0);
            XPathValue arg1 = args.get(1);
            
            if (isEmpty(arg0) || isEmpty(arg1)) {
                return XPathSequence.EMPTY;
            }
            
            XPathDateTime date = toDate(arg0);
            XPathDateTime time = toTime(arg1);
            return XPathDateTime.dateTime(date, time);
        }
    };
    
    // ========== Year extraction ==========
    
    /**
     * XPath 2.0 year-from-dateTime() function.
     * 
     * <p>Extracts the year component from an xs:dateTime value.
     * 
     * <p>Signature: year-from-dateTime(dateTime?) → integer?
     * 
     * @see <a href="https://www.w3.org/TR/xpath-functions-30/#func-year-from-dateTime">XPath 3.0 year-from-dateTime()</a>
     */
    public static final Function YEAR_FROM_DATE_TIME = new Function() {
        @Override public String getName() { return "year-from-dateTime"; }
        @Override public int getMinArgs() { return 1; }
        @Override public int getMaxArgs() { return 1; }
        
        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            XPathValue arg = args.get(0);
            if (isEmpty(arg)) return XPathSequence.EMPTY;
            XPathDateTime dt = toDateTime(arg);
            Integer year = dt.getYear();
            return year != null ? XPathNumber.of(year) : XPathSequence.EMPTY;
        }
    };
    
    public static final Function YEAR_FROM_DATE = new Function() {
        @Override public String getName() { return "year-from-date"; }
        @Override public int getMinArgs() { return 1; }
        @Override public int getMaxArgs() { return 1; }
        
        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            XPathValue arg = args.get(0);
            if (isEmpty(arg)) return XPathSequence.EMPTY;
            XPathDateTime dt = toDate(arg);
            Integer year = dt.getYear();
            return year != null ? XPathNumber.of(year) : XPathSequence.EMPTY;
        }
    };
    
    // ========== Month extraction ==========
    
    public static final Function MONTH_FROM_DATE_TIME = new Function() {
        @Override public String getName() { return "month-from-dateTime"; }
        @Override public int getMinArgs() { return 1; }
        @Override public int getMaxArgs() { return 1; }
        
        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            XPathValue arg = args.get(0);
            if (isEmpty(arg)) return XPathSequence.EMPTY;
            XPathDateTime dt = toDateTime(arg);
            Integer month = dt.getMonth();
            return month != null ? XPathNumber.of(month) : XPathSequence.EMPTY;
        }
    };
    
    public static final Function MONTH_FROM_DATE = new Function() {
        @Override public String getName() { return "month-from-date"; }
        @Override public int getMinArgs() { return 1; }
        @Override public int getMaxArgs() { return 1; }
        
        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            XPathValue arg = args.get(0);
            if (isEmpty(arg)) return XPathSequence.EMPTY;
            XPathDateTime dt = toDate(arg);
            Integer month = dt.getMonth();
            return month != null ? XPathNumber.of(month) : XPathSequence.EMPTY;
        }
    };
    
    // ========== Day extraction ==========
    
    public static final Function DAY_FROM_DATE_TIME = new Function() {
        @Override public String getName() { return "day-from-dateTime"; }
        @Override public int getMinArgs() { return 1; }
        @Override public int getMaxArgs() { return 1; }
        
        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            XPathValue arg = args.get(0);
            if (isEmpty(arg)) return XPathSequence.EMPTY;
            XPathDateTime dt = toDateTime(arg);
            Integer day = dt.getDay();
            return day != null ? XPathNumber.of(day) : XPathSequence.EMPTY;
        }
    };
    
    public static final Function DAY_FROM_DATE = new Function() {
        @Override public String getName() { return "day-from-date"; }
        @Override public int getMinArgs() { return 1; }
        @Override public int getMaxArgs() { return 1; }
        
        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            XPathValue arg = args.get(0);
            if (isEmpty(arg)) return XPathSequence.EMPTY;
            XPathDateTime dt = toDate(arg);
            Integer day = dt.getDay();
            return day != null ? XPathNumber.of(day) : XPathSequence.EMPTY;
        }
    };
    
    // ========== Hours extraction ==========
    
    public static final Function HOURS_FROM_DATE_TIME = new Function() {
        @Override public String getName() { return "hours-from-dateTime"; }
        @Override public int getMinArgs() { return 1; }
        @Override public int getMaxArgs() { return 1; }
        
        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            XPathValue arg = args.get(0);
            if (isEmpty(arg)) return XPathSequence.EMPTY;
            XPathDateTime dt = toDateTime(arg);
            Integer hour = dt.getHour();
            return hour != null ? XPathNumber.of(hour) : XPathSequence.EMPTY;
        }
    };
    
    public static final Function HOURS_FROM_TIME = new Function() {
        @Override public String getName() { return "hours-from-time"; }
        @Override public int getMinArgs() { return 1; }
        @Override public int getMaxArgs() { return 1; }
        
        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            XPathValue arg = args.get(0);
            if (isEmpty(arg)) return XPathSequence.EMPTY;
            XPathDateTime dt = toTime(arg);
            Integer hour = dt.getHour();
            return hour != null ? XPathNumber.of(hour) : XPathSequence.EMPTY;
        }
    };
    
    // ========== Minutes extraction ==========
    
    public static final Function MINUTES_FROM_DATE_TIME = new Function() {
        @Override public String getName() { return "minutes-from-dateTime"; }
        @Override public int getMinArgs() { return 1; }
        @Override public int getMaxArgs() { return 1; }
        
        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            XPathValue arg = args.get(0);
            if (isEmpty(arg)) return XPathSequence.EMPTY;
            XPathDateTime dt = toDateTime(arg);
            Integer minute = dt.getMinute();
            return minute != null ? XPathNumber.of(minute) : XPathSequence.EMPTY;
        }
    };
    
    public static final Function MINUTES_FROM_TIME = new Function() {
        @Override public String getName() { return "minutes-from-time"; }
        @Override public int getMinArgs() { return 1; }
        @Override public int getMaxArgs() { return 1; }
        
        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            XPathValue arg = args.get(0);
            if (isEmpty(arg)) return XPathSequence.EMPTY;
            XPathDateTime dt = toTime(arg);
            Integer minute = dt.getMinute();
            return minute != null ? XPathNumber.of(minute) : XPathSequence.EMPTY;
        }
    };
    
    // ========== Seconds extraction ==========
    
    public static final Function SECONDS_FROM_DATE_TIME = new Function() {
        @Override public String getName() { return "seconds-from-dateTime"; }
        @Override public int getMinArgs() { return 1; }
        @Override public int getMaxArgs() { return 1; }
        
        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            XPathValue arg = args.get(0);
            if (isEmpty(arg)) return XPathSequence.EMPTY;
            XPathDateTime dt = toDateTime(arg);
            BigDecimal second = dt.getSecond();
            return second != null ? XPathNumber.of(second.doubleValue()) : XPathSequence.EMPTY;
        }
    };
    
    public static final Function SECONDS_FROM_TIME = new Function() {
        @Override public String getName() { return "seconds-from-time"; }
        @Override public int getMinArgs() { return 1; }
        @Override public int getMaxArgs() { return 1; }
        
        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            XPathValue arg = args.get(0);
            if (isEmpty(arg)) return XPathSequence.EMPTY;
            XPathDateTime dt = toTime(arg);
            BigDecimal second = dt.getSecond();
            return second != null ? XPathNumber.of(second.doubleValue()) : XPathSequence.EMPTY;
        }
    };
    
    // ========== Timezone extraction ==========
    
    public static final Function TIMEZONE_FROM_DATE_TIME = new Function() {
        @Override public String getName() { return "timezone-from-dateTime"; }
        @Override public int getMinArgs() { return 1; }
        @Override public int getMaxArgs() { return 1; }
        
        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            XPathValue arg = args.get(0);
            if (isEmpty(arg)) return XPathSequence.EMPTY;
            XPathDateTime dt = toDateTime(arg);
            XPathDateTime tz = dt.getTimezoneAsDuration();
            return tz != null ? tz : XPathSequence.EMPTY;
        }
    };
    
    public static final Function TIMEZONE_FROM_DATE = new Function() {
        @Override public String getName() { return "timezone-from-date"; }
        @Override public int getMinArgs() { return 1; }
        @Override public int getMaxArgs() { return 1; }
        
        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            XPathValue arg = args.get(0);
            if (isEmpty(arg)) return XPathSequence.EMPTY;
            XPathDateTime dt = toDate(arg);
            XPathDateTime tz = dt.getTimezoneAsDuration();
            return tz != null ? tz : XPathSequence.EMPTY;
        }
    };
    
    public static final Function TIMEZONE_FROM_TIME = new Function() {
        @Override public String getName() { return "timezone-from-time"; }
        @Override public int getMinArgs() { return 1; }
        @Override public int getMaxArgs() { return 1; }
        
        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            XPathValue arg = args.get(0);
            if (isEmpty(arg)) return XPathSequence.EMPTY;
            XPathDateTime dt = toTime(arg);
            XPathDateTime tz = dt.getTimezoneAsDuration();
            return tz != null ? tz : XPathSequence.EMPTY;
        }
    };
    
    // ========== Duration component extraction ==========
    
    public static final Function YEARS_FROM_DURATION = new Function() {
        @Override public String getName() { return "years-from-duration"; }
        @Override public int getMinArgs() { return 1; }
        @Override public int getMaxArgs() { return 1; }
        
        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            XPathValue arg = args.get(0);
            if (isEmpty(arg)) return XPathSequence.EMPTY;
            XPathDateTime dt = toDuration(arg);
            Integer years = dt.getDurationYears();
            int value = years != null ? years : 0;
            if (dt.isNegative()) value = -value;
            return XPathNumber.of(value);
        }
    };
    
    public static final Function MONTHS_FROM_DURATION = new Function() {
        @Override public String getName() { return "months-from-duration"; }
        @Override public int getMinArgs() { return 1; }
        @Override public int getMaxArgs() { return 1; }
        
        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            XPathValue arg = args.get(0);
            if (isEmpty(arg)) return XPathSequence.EMPTY;
            XPathDateTime dt = toDuration(arg);
            Integer months = dt.getDurationMonths();
            int value = months != null ? months : 0;
            if (dt.isNegative()) value = -value;
            return XPathNumber.of(value);
        }
    };
    
    public static final Function DAYS_FROM_DURATION = new Function() {
        @Override public String getName() { return "days-from-duration"; }
        @Override public int getMinArgs() { return 1; }
        @Override public int getMaxArgs() { return 1; }
        
        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            XPathValue arg = args.get(0);
            if (isEmpty(arg)) return XPathSequence.EMPTY;
            XPathDateTime dt = toDuration(arg);
            Integer days = dt.getDurationDays();
            int value = days != null ? days : 0;
            if (dt.isNegative()) value = -value;
            return XPathNumber.of(value);
        }
    };
    
    public static final Function HOURS_FROM_DURATION = new Function() {
        @Override public String getName() { return "hours-from-duration"; }
        @Override public int getMinArgs() { return 1; }
        @Override public int getMaxArgs() { return 1; }
        
        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            XPathValue arg = args.get(0);
            if (isEmpty(arg)) return XPathSequence.EMPTY;
            XPathDateTime dt = toDuration(arg);
            Integer hours = dt.getDurationHours();
            int value = hours != null ? hours : 0;
            if (dt.isNegative()) value = -value;
            return XPathNumber.of(value);
        }
    };
    
    public static final Function MINUTES_FROM_DURATION = new Function() {
        @Override public String getName() { return "minutes-from-duration"; }
        @Override public int getMinArgs() { return 1; }
        @Override public int getMaxArgs() { return 1; }
        
        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            XPathValue arg = args.get(0);
            if (isEmpty(arg)) return XPathSequence.EMPTY;
            XPathDateTime dt = toDuration(arg);
            Integer minutes = dt.getDurationMinutes();
            int value = minutes != null ? minutes : 0;
            if (dt.isNegative()) value = -value;
            return XPathNumber.of(value);
        }
    };
    
    public static final Function SECONDS_FROM_DURATION = new Function() {
        @Override public String getName() { return "seconds-from-duration"; }
        @Override public int getMinArgs() { return 1; }
        @Override public int getMaxArgs() { return 1; }
        
        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            XPathValue arg = args.get(0);
            if (isEmpty(arg)) return XPathSequence.EMPTY;
            XPathDateTime dt = toDuration(arg);
            BigDecimal seconds = dt.getDurationSeconds();
            double value = seconds != null ? seconds.doubleValue() : 0.0;
            if (dt.isNegative()) value = -value;
            return XPathNumber.of(value);
        }
    };
    
    // ========== Formatting functions ==========
    
    /**
     * XPath 3.0 format-dateTime() function.
     * 
     * <p>Formats a dateTime value according to a picture string and locale.
     * 
     * <p>Signature: format-dateTime(dateTime?, string, string?, string?, string?) → string
     * 
     * @see <a href="https://www.w3.org/TR/xpath-functions-30/#func-format-dateTime">XPath 3.0 format-dateTime()</a>
     */
    public static final Function FORMAT_DATE_TIME = new Function() {
        @Override public String getName() { return "format-dateTime"; }
        @Override public int getMinArgs() { return 2; }
        @Override public int getMaxArgs() { return 5; }
        
        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            XPathValue arg = args.get(0);
            if (isEmpty(arg)) return XPathString.EMPTY;
            XPathDateTime dt = toDateTime(arg);
            String picture = args.get(1).asString();
            String language = args.size() > 2 && !isEmpty(args.get(2)) ? args.get(2).asString() : "en";
            String calendar = args.size() > 3 && !isEmpty(args.get(3)) ? args.get(3).asString() : null;
            String place = args.size() > 4 && !isEmpty(args.get(4)) ? args.get(4).asString() : null;
            Locale locale = parseLocale(language, place);
            return XPathString.of(formatDateTime(dt, picture, true, true, locale));
        }
    };
    
    /**
     * XPath 3.0 format-date() function.
     * 
     * <p>Formats a date value according to a picture string and locale.
     * 
     * <p>Signature: format-date(date?, string, string?, string?, string?) → string
     * 
     * @see <a href="https://www.w3.org/TR/xpath-functions-30/#func-format-date">XPath 3.0 format-date()</a>
     */
    public static final Function FORMAT_DATE = new Function() {
        @Override public String getName() { return "format-date"; }
        @Override public int getMinArgs() { return 2; }
        @Override public int getMaxArgs() { return 5; }
        
        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            XPathValue arg = args.get(0);
            if (isEmpty(arg)) return XPathString.EMPTY;
            XPathDateTime dt = toDate(arg);
            String picture = args.get(1).asString();
            String language = args.size() > 2 && !isEmpty(args.get(2)) ? args.get(2).asString() : "en";
            String calendar = args.size() > 3 && !isEmpty(args.get(3)) ? args.get(3).asString() : null;
            String place = args.size() > 4 && !isEmpty(args.get(4)) ? args.get(4).asString() : null;
            Locale locale = parseLocale(language, place);
            return XPathString.of(formatDateTime(dt, picture, true, false, locale));
        }
    };
    
    /**
     * XPath 3.0 format-time() function.
     * 
     * <p>Formats a time value according to a picture string and locale.
     * 
     * <p>Signature: format-time(time?, string, string?, string?, string?) → string
     * 
     * @see <a href="https://www.w3.org/TR/xpath-functions-30/#func-format-time">XPath 3.0 format-time()</a>
     */
    public static final Function FORMAT_TIME = new Function() {
        @Override public String getName() { return "format-time"; }
        @Override public int getMinArgs() { return 2; }
        @Override public int getMaxArgs() { return 5; }
        
        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            XPathValue arg = args.get(0);
            if (isEmpty(arg)) return XPathString.EMPTY;
            XPathDateTime dt = toTime(arg);
            String picture = args.get(1).asString();
            String language = args.size() > 2 && !isEmpty(args.get(2)) ? args.get(2).asString() : "en";
            String calendar = args.size() > 3 && !isEmpty(args.get(3)) ? args.get(3).asString() : null;
            String place = args.size() > 4 && !isEmpty(args.get(4)) ? args.get(4).asString() : null;
            Locale locale = parseLocale(language, place);
            return XPathString.of(formatDateTime(dt, picture, false, true, locale));
        }
    };
    
    /**
     * Parses language and place into a Java Locale.
     *
     * @param language the language code (e.g., "en", "en-GB")
     * @param place the country/region code (optional)
     * @return the parsed Locale
     */
    private static Locale parseLocale(String language, String place) {
        if (language == null || language.isEmpty()) {
            language = "en";
        }
        // Handle language tags like "en-GB" or "en_GB"
        String[] parts = language.split("[-_]");
        if (parts.length >= 2) {
            return new Locale(parts[0], parts[1].toUpperCase());
        }
        if (place != null && !place.isEmpty()) {
            return new Locale(language, place.toUpperCase());
        }
        return new Locale(language);
    }
    
    /**
     * Formats a date/time according to a picture string.
     * Supports basic XSLT 2.0/3.0 picture formatting.
     *
     * @param dt the dateTime value to format
     * @param picture the format picture string
     * @param hasDate true if date components should be included
     * @param hasTime true if time components should be included
     * @param locale the locale for localization
     * @return the formatted string
     * @throws XPathException if the picture string is invalid
     */
    private static String formatDateTime(XPathDateTime dt, String picture, 
                                         boolean hasDate, boolean hasTime, Locale locale) throws XPathException {
        DateTimeLocale dtLocale = DateTimeLocale.forLocale(locale);
        StringBuilder result = new StringBuilder();
        int i = 0;
        while (i < picture.length()) {
            char c = picture.charAt(i);
            if (c == '[') {
                // Check for escaped [[ which produces literal [
                if (i + 1 < picture.length() && picture.charAt(i + 1) == '[') {
                    result.append('[');
                    i += 2;
                    continue;
                }
                // Find matching ]
                int end = picture.indexOf(']', i);
                if (end == -1) {
                    throw new XPathException("Unclosed '[' in format picture: " + picture);
                }
                String component = picture.substring(i + 1, end);
                result.append(formatComponent(dt, component, hasDate, hasTime, dtLocale));
                i = end + 1;
            } else if (c == ']') {
                // Literal ] is escaped as ]]
                if (i + 1 < picture.length() && picture.charAt(i + 1) == ']') {
                    result.append(']');
                    i += 2;
                } else {
                    throw new XPathException("Unmatched ']' in format picture: " + picture);
                }
            } else {
                result.append(c);
                i++;
            }
        }
        return result.toString();
    }
    
    /**
     * Formats a single component like Y, M, D, H, m, s, etc.
     * 
     * <p>Picture string format examples:
     * <ul>
     *   <li>[Y] = year in decimal</li>
     *   <li>[Y0001] = year padded to 4 digits</li>
     *   <li>[M01] = month padded to 2 digits</li>
     *   <li>[MNn] = month name, initial capital</li>
     *   <li>[MI] = month in Roman numerals</li>
     *   <li>[D] = day without padding</li>
     *   <li>[D01] = day padded to 2 digits</li>
     * </ul>
     *
     * @param dt the dateTime value
     * @param component the component specification (e.g., "Y", "M01", "MNn")
     * @param hasDate true if date components are available
     * @param hasTime true if time components are available
     * @param dtLocale the locale for localization
     * @return the formatted component string
     * @throws XPathException if the component specification is invalid
     */
    private static String formatComponent(XPathDateTime dt, String component,
                                          boolean hasDate, boolean hasTime,
                                          DateTimeLocale dtLocale) throws XPathException {
        if (component.isEmpty()) {
            return "";
        }
        
        // Parse component: first char is specifier, rest is presentation modifier
        char specifier = component.charAt(0);
        String modifier = component.length() > 1 ? component.substring(1).trim() : "";
        
        // Parse presentation format and width
        int minWidth = 1;
        int maxWidth = Integer.MAX_VALUE;
        String presentation = "1";  // Default: decimal
        boolean explicitWidth = false;  // Track if width was explicitly specified
        boolean ordinal = false;  // Track if ordinal suffix is requested
        
        if (!modifier.isEmpty()) {
            int commaPos = modifier.indexOf(',');
            String presentationPart;
            String widthPart = null;
            
            if (commaPos >= 0) {
                presentationPart = modifier.substring(0, commaPos);
                widthPart = modifier.substring(commaPos + 1);
            } else {
                presentationPart = modifier;
            }
            
            // Check for ordinal modifier at end of presentation
            if (presentationPart.endsWith("o") || presentationPart.endsWith("O")) {
                ordinal = true;
                presentationPart = presentationPart.substring(0, presentationPart.length() - 1);
                if (presentationPart.isEmpty()) {
                    presentationPart = "1";  // Default to decimal if just 'o'
                }
            }
            
            // Determine presentation type and width from presentation format
            // "01" means decimal with min width 2
            // "0001" means decimal with min width 4
            // "1" means decimal with min width 1 (default)
            // "1o" means decimal with ordinal suffix (1st, 2nd, 3rd, etc.)
            // "I" means Roman numerals
            // "i" means lowercase Roman
            // "Nn" means name with initial cap
            // "N" means uppercase name
            // "n" means lowercase name
            // "W" or "w" means words
            
            if (presentationPart.length() > 0) {
                char firstChar = presentationPart.charAt(0);
                
                if (firstChar == '0' || firstChar == '#') {
                    // Decimal format with width determined by pattern length
                    // "01" = width 2, "001" = width 3, "0001" = width 4
                    minWidth = presentationPart.length();
                    presentation = "1";
                    explicitWidth = true;
                } else if (Character.isDigit(firstChar)) {
                    // "1" means default decimal, width 1
                    presentation = "1";
                    // Could have trailing digits indicating width
                    minWidth = presentationPart.length();
                } else if (firstChar == 'I') {
                    presentation = "I";  // Roman numerals uppercase
                } else if (firstChar == 'i') {
                    presentation = "i";  // Roman numerals lowercase
                } else if (firstChar == 'W') {
                    presentation = presentationPart;  // Words (W, Ww, etc.)
                } else if (firstChar == 'w') {
                    presentation = presentationPart;  // Words lowercase
                } else if (firstChar == 'N' || firstChar == 'n') {
                    presentation = presentationPart;  // Name format (Nn, N, n)
                } else if (firstChar == 'A' || firstChar == 'a') {
                    presentation = presentationPart;  // Alphabetic
                } else {
                    presentation = presentationPart;
                }
            }
            
            // Parse explicit width from comma-separated width spec
            if (widthPart != null && !widthPart.isEmpty()) {
                int dashPos = widthPart.indexOf('-');
                if (dashPos > 0) {
                    try {
                        minWidth = Integer.parseInt(widthPart.substring(0, dashPos).trim());
                        String maxStr = widthPart.substring(dashPos + 1).trim();
                        if (!"*".equals(maxStr)) {
                            maxWidth = Integer.parseInt(maxStr);
                        }
                    } catch (NumberFormatException e) {
                        // Ignore invalid width
                    }
                } else {
                    try {
                        minWidth = Integer.parseInt(widthPart.trim());
                    } catch (NumberFormatException e) {
                        // Ignore
                    }
                }
            }
        }
        
        Integer value = null;
        String strValue = null;
        
        switch (specifier) {
            case 'Y':  // Year
                Integer yearVal = dt.getYear();
                if (yearVal != null) {
                    // For short year formats with explicit width (minWidth < 4), use modulo to get last N digits
                    // [Y01] = 2-digit year, [Y001] = 3-digit year
                    // [Y] with no explicit width = full year
                    // Always use absolute value - negative years are shown with BC era designation
                    if (explicitWidth && minWidth < 4 && minWidth > 0) {
                        int divisor = 1;
                        for (int j = 0; j < minWidth; j++) divisor *= 10;
                        value = Math.abs(yearVal) % divisor;
                    } else {
                        value = Math.abs(yearVal);
                    }
                }
                break;
            case 'M':  // Month
                value = dt.getMonth();
                if (presentation.startsWith("N") || presentation.startsWith("n")) {
                    // Pass width constraints to name selection
                    strValue = dtLocale.getMonthName(value, minWidth, maxWidth);
                    strValue = formatName(strValue, presentation);
                }
                break;
            case 'D':  // Day of month
                value = dt.getDay();
                break;
            case 'd':  // Day of year
                value = getDayOfYear(dt);
                break;
            case 'F':  // Day of week
                value = getDayOfWeek(dt);
                if (presentation.startsWith("N") || presentation.startsWith("n")) {
                    // Pass width constraints to name selection
                    strValue = dtLocale.getDayName(value, minWidth, maxWidth);
                    strValue = formatName(strValue, presentation);
                }
                break;
            case 'W':  // Week of year
                value = getWeekOfYear(dt);
                break;
            case 'w':  // Week of month
                value = getWeekOfMonth(dt);
                break;
            case 'H':  // Hour (24)
                value = dt.getHour();
                break;
            case 'h':  // Hour (12)
                Integer hour = dt.getHour();
                if (hour != null) {
                    value = hour % 12;
                    if (value == 0) {
                        value = 12;
                    }
                }
                break;
            case 'P':  // AM/PM
                Integer h = dt.getHour();
                if (h != null) {
                    // Default is lowercase; uppercase requires N presentation
                    boolean uppercase = presentation.equals("N");
                    strValue = dtLocale.getAmPm(h, uppercase);
                }
                break;
            case 'm':  // Minute
                value = dt.getMinute();
                // Default minimum width for minutes is 2
                if (minWidth == 1 && !explicitWidth) {
                    minWidth = 2;
                }
                break;
            case 's':  // Second
                BigDecimal sec = dt.getSecond();
                if (sec != null) {
                    value = sec.intValue();
                }
                // Default minimum width for seconds is 2
                if (minWidth == 1 && !explicitWidth) {
                    minWidth = 2;
                }
                break;
            case 'f':  // Fractional seconds
                BigDecimal sec2 = dt.getSecond();
                if (sec2 != null) {
                    BigDecimal frac = sec2.remainder(BigDecimal.ONE);
                    if (frac.compareTo(BigDecimal.ZERO) > 0) {
                        String fracStr = frac.toPlainString();
                        // Remove "0."
                        strValue = fracStr.substring(2);
                        // Pad or truncate to width
                        while (strValue.length() < minWidth) {
                            strValue = strValue + "0";
                        }
                        if (strValue.length() > maxWidth && maxWidth < Integer.MAX_VALUE) {
                            strValue = strValue.substring(0, maxWidth);
                        }
                    } else {
                        strValue = "";
                        for (int j = 0; j < minWidth; j++) strValue += "0";
                    }
                }
                break;
            case 'Z':  // Timezone
            case 'z':
                if (dt.getTimezone() != null) {
                    strValue = dt.getTimezone().getId();
                    if (strValue.equals("Z")) {
                        strValue = "+00:00";
                    }
                } else {
                    strValue = "";
                }
                break;
            case 'E':  // Era
                Integer year = dt.getYear();
                if (year != null) {
                    strValue = dtLocale.getEra(year);
                }
                break;
            case 'C':  // Calendar
                strValue = "ISO";
                break;
            default:
                // Unknown component - return as-is
                return "[" + component + "]";
        }
        
        if (strValue != null) {
            // Apply width constraints to string values (like names)
            return applyWidth(strValue, minWidth, maxWidth);
        }
        
        if (value == null) {
            return "";
        }
        
        // Format numeric value based on presentation
        return formatNumber(value, minWidth, maxWidth, presentation, ordinal, dtLocale);
    }
    
    /**
     * Apply width constraints to a string value.
     * If the string is longer than maxWidth, truncate it.
     * If the string is shorter than minWidth, pad with spaces.
     *
     * @param value the string to constrain
     * @param minWidth minimum width (pad if shorter)
     * @param maxWidth maximum width (truncate if longer)
     * @return the constrained string
     */
    private static String applyWidth(String value, int minWidth, int maxWidth) {
        if (value == null) {
            return "";
        }
        
        // Truncate to max width if necessary
        if (maxWidth < Integer.MAX_VALUE && value.length() > maxWidth) {
            value = value.substring(0, maxWidth);
        }
        
        // Pad to min width if necessary
        while (value.length() < minWidth) {
            value = value + " ";
        }
        
        return value;
    }
    
    /**
     * Formats a number according to presentation and width constraints.
     *
     * @param value the number to format
     * @param minWidth minimum width constraint
     * @param maxWidth maximum width constraint
     * @param presentation presentation format ("1"=decimal, "I"=Roman, "W"=words, etc.)
     * @param ordinal true to add ordinal suffix
     * @param dtLocale the locale for localization
     * @return the formatted number string
     */
    private static String formatNumber(int value, int minWidth, int maxWidth, String presentation, 
                                       boolean ordinal, DateTimeLocale dtLocale) {
        // Handle Roman numerals
        if ("I".equals(presentation)) {
            return toRoman(value, true);
        } else if ("i".equals(presentation)) {
            return toRoman(value, false);
        }
        
        // Handle alphabetic (a=1, b=2, ... z=26, aa=27, ...)
        if ("A".equals(presentation) || "a".equals(presentation)) {
            return toAlphabetic(value, "A".equals(presentation));
        }
        
        // Handle words
        if ("W".equals(presentation) || "w".equals(presentation) || "Ww".equals(presentation)) {
            // Get words using locale, apply ordinal conversion if needed, then case
            String words;
            if (ordinal) {
                words = dtLocale.toOrdinalWords(value);
            } else {
                words = dtLocale.toWords(value);
            }
            return formatWordCase(words, presentation, dtLocale);
        }
        
        // Default: decimal format
        String str = Integer.toString(Math.abs(value));
        // Pad with leading zeros
        while (str.length() < minWidth) {
            str = "0" + str;
        }
        // Handle negative
        if (value < 0) {
            str = "-" + str;
        }
        
        // Add ordinal suffix if requested
        if (ordinal) {
            str = str + dtLocale.getOrdinalSuffix(Math.abs(value));
        }
        
        return str;
    }
    
    private static final int[] ROMAN_VALUES = {1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};
    private static final String[] ROMAN_SYMBOLS = {"M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I"};
    
    private static String toRoman(int value, boolean uppercase) {
        if (value <= 0 || value > 3999) {
            return Integer.toString(value);  // Fallback for out-of-range
        }
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < ROMAN_VALUES.length; i++) {
            while (value >= ROMAN_VALUES[i]) {
                result.append(ROMAN_SYMBOLS[i]);
                value -= ROMAN_VALUES[i];
            }
        }
        return uppercase ? result.toString() : result.toString().toLowerCase();
    }
    
    private static String toAlphabetic(int value, boolean uppercase) {
        if (value <= 0) {
            return Integer.toString(value);
        }
        StringBuilder result = new StringBuilder();
        while (value > 0) {
            value--;  // 1-based to 0-based
            char c = (char) ('A' + (value % 26));
            result.insert(0, uppercase ? c : Character.toLowerCase(c));
            value /= 26;
        }
        return result.toString();
    }
    
    private static String formatWordCase(String s, String presentation, DateTimeLocale dtLocale) {
        if ("W".equals(presentation)) {
            return s.toUpperCase();
        } else if ("Ww".equals(presentation)) {
            // Title case: first letter of each significant word capitalized
            // Minor words like "and", "the", "of" stay lowercase (except at start)
            if (s.isEmpty()) return s;
            StringBuilder sb = new StringBuilder();
            StringBuilder currentWord = new StringBuilder();
            boolean isFirstWord = true;
            
            for (int i = 0; i <= s.length(); i++) {
                char c = i < s.length() ? s.charAt(i) : ' ';
                if (c == ' ' || c == '-' || i == s.length()) {
                    // Process current word
                    if (currentWord.length() > 0) {
                        String word = currentWord.toString();
                        if (isFirstWord || !dtLocale.isMinorWord(word)) {
                            // Capitalize first letter
                            sb.append(Character.toUpperCase(word.charAt(0)));
                            sb.append(word.substring(1));
                        } else {
                            // Keep lowercase
                            sb.append(word);
                        }
                        currentWord.setLength(0);
                        isFirstWord = false;
                    }
                    if (i < s.length()) {
                        sb.append(c);
                    }
                } else {
                    currentWord.append(c);
                }
            }
            return sb.toString();
        }
        return s;  // lowercase by default
    }
    
    private static String formatName(String name, String presentation) {
        if (presentation.equals("N")) {
            return name.toUpperCase();
        } else if (presentation.equals("n")) {
            return name.toLowerCase();
        } else if (presentation.equals("Nn")) {
            return name;  // Already capitalized
        }
        return name;
    }
    
    private static Integer getDayOfYear(XPathDateTime dt) {
        if (dt.getYear() == null || dt.getMonth() == null || dt.getDay() == null) return null;
        LocalDate date = LocalDate.of(dt.getYear(), dt.getMonth(), dt.getDay());
        return date.getDayOfYear();
    }
    
    private static Integer getDayOfWeek(XPathDateTime dt) {
        if (dt.getYear() == null || dt.getMonth() == null || dt.getDay() == null) return null;
        LocalDate date = LocalDate.of(dt.getYear(), dt.getMonth(), dt.getDay());
        // XPath: Sunday=1, Monday=2, ... Saturday=7
        // Java DayOfWeek: Monday=1, Tuesday=2, ... Sunday=7
        int dow = date.getDayOfWeek().getValue();  // Monday=1 through Sunday=7
        return dow == 7 ? 1 : dow + 1;  // Convert to Sunday=1 through Saturday=7
    }
    
    private static Integer getWeekOfYear(XPathDateTime dt) {
        if (dt.getYear() == null || dt.getMonth() == null || dt.getDay() == null) return null;
        LocalDate date = LocalDate.of(dt.getYear(), dt.getMonth(), dt.getDay());
        return date.get(WeekFields.ISO.weekOfYear());
    }
    
    private static Integer getWeekOfMonth(XPathDateTime dt) {
        if (dt.getYear() == null || dt.getMonth() == null || dt.getDay() == null) return null;
        LocalDate date = LocalDate.of(dt.getYear(), dt.getMonth(), dt.getDay());
        return date.get(WeekFields.ISO.weekOfMonth());
    }
    
    // ========== Helper methods ==========
    
    /**
     * Checks if a value is empty (null or empty sequence).
     *
     * @param value the value to check
     * @return true if empty
     */
    private static boolean isEmpty(XPathValue value) {
        return value == null || (value instanceof XPathSequence && ((XPathSequence)value).isEmpty());
    }
    
    /**
     * Converts an XPath value to an xs:dateTime.
     *
     * @param value the value to convert
     * @return the dateTime value
     * @throws XPathException if conversion fails
     */
    private static XPathDateTime toDateTime(XPathValue value) throws XPathException {
        if (value instanceof XPathDateTime) {
            XPathDateTime dt = (XPathDateTime) value;
            if (dt.getDateTimeType() == XPathDateTime.DateTimeType.DATE_TIME) {
                return dt;
            }
        }
        // Try to parse as dateTime
        return XPathDateTime.parseDateTime(value.asString());
    }
    
    /**
     * Converts an XPath value to an xs:date.
     *
     * @param value the value to convert
     * @return the date value
     * @throws XPathException if conversion fails
     */
    private static XPathDateTime toDate(XPathValue value) throws XPathException {
        if (value instanceof XPathDateTime) {
            XPathDateTime dt = (XPathDateTime) value;
            if (dt.getDateTimeType() == XPathDateTime.DateTimeType.DATE) {
                return dt;
            }
        }
        // Try to parse as date
        return XPathDateTime.parseDate(value.asString());
    }
    
    /**
     * Converts an XPath value to an xs:time.
     *
     * @param value the value to convert
     * @return the time value
     * @throws XPathException if conversion fails
     */
    private static XPathDateTime toTime(XPathValue value) throws XPathException {
        if (value instanceof XPathDateTime) {
            XPathDateTime dt = (XPathDateTime) value;
            if (dt.getDateTimeType() == XPathDateTime.DateTimeType.TIME) {
                return dt;
            }
        }
        // Try to parse as time
        return XPathDateTime.parseTime(value.asString());
    }
    
    /**
     * Converts an XPath value to an xs:duration.
     *
     * @param value the value to convert
     * @return the duration value
     * @throws XPathException if conversion fails
     */
    private static XPathDateTime toDuration(XPathValue value) throws XPathException {
        if (value instanceof XPathDateTime) {
            XPathDateTime dt = (XPathDateTime) value;
            XPathDateTime.DateTimeType type = dt.getDateTimeType();
            if (type == XPathDateTime.DateTimeType.DURATION ||
                type == XPathDateTime.DateTimeType.YEAR_MONTH_DURATION ||
                type == XPathDateTime.DateTimeType.DAY_TIME_DURATION) {
                return dt;
            }
        }
        // Try to parse as duration
        return XPathDateTime.parseDuration(value.asString());
    }
}

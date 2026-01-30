/*
 * XPathDateTime.java
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

package org.bluezoo.gonzalez.transform.xpath.type;

import org.bluezoo.gonzalez.transform.xpath.expr.XPathException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * XPath 2.0/3.1 date/time value.
 *
 * <p>Represents values of the following XML Schema types:
 * <ul>
 *   <li>xs:dateTime - a specific instant in time (date and time)</li>
 *   <li>xs:date - a calendar date</li>
 *   <li>xs:time - a time of day</li>
 *   <li>xs:duration - a duration of time</li>
 *   <li>xs:yearMonthDuration - a duration in years and months</li>
 *   <li>xs:dayTimeDuration - a duration in days, hours, minutes, seconds</li>
 * </ul>
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class XPathDateTime implements XPathValue, Comparable<XPathDateTime> {

    /** The type of date/time value. */
    public enum DateTimeType {
        DATE_TIME,
        DATE,
        TIME,
        DURATION,
        YEAR_MONTH_DURATION,
        DAY_TIME_DURATION,
        G_YEAR_MONTH,
        G_YEAR,
        G_MONTH_DAY,
        G_DAY,
        G_MONTH
    }

    // Date/time components (for DATE, DATE_TIME, TIME)
    private final Integer year;
    private final Integer month;
    private final Integer day;
    private final Integer hour;
    private final Integer minute;
    private final BigDecimal second;
    private final ZoneOffset timezone;
    
    // Duration components (for DURATION, YEAR_MONTH_DURATION, DAY_TIME_DURATION)
    private final boolean negative;
    private final Integer durationYears;
    private final Integer durationMonths;
    private final Integer durationDays;
    private final Integer durationHours;
    private final Integer durationMinutes;
    private final BigDecimal durationSeconds;
    
    private final DateTimeType type;
    private final String lexicalValue;  // Original lexical representation
    
    // Patterns for parsing
    private static final Pattern DATE_TIME_PATTERN = Pattern.compile(
        "(-?\\d{4,})-(\\d{2})-(\\d{2})T(\\d{2}):(\\d{2}):(\\d{2}(?:\\.\\d+)?)(Z|[+-]\\d{2}:\\d{2})?");
    private static final Pattern DATE_PATTERN = Pattern.compile(
        "(-?\\d{4,})-(\\d{2})-(\\d{2})(Z|[+-]\\d{2}:\\d{2})?");
    private static final Pattern TIME_PATTERN = Pattern.compile(
        "(\\d{2}):(\\d{2}):(\\d{2}(?:\\.\\d+)?)(Z|[+-]\\d{2}:\\d{2})?");
    private static final Pattern DURATION_PATTERN = Pattern.compile(
        "(-)?P(?:(\\d+)Y)?(?:(\\d+)M)?(?:(\\d+)D)?(?:T(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+(?:\\.\\d+)?)S)?)?");
    // Gregorian partial date patterns
    private static final Pattern G_YEAR_MONTH_PATTERN = Pattern.compile(
        "(-?\\d{4,})-(\\d{2})(Z|[+-]\\d{2}:\\d{2})?");
    private static final Pattern G_YEAR_PATTERN = Pattern.compile(
        "(-?\\d{4,})(Z|[+-]\\d{2}:\\d{2})?");
    private static final Pattern G_MONTH_DAY_PATTERN = Pattern.compile(
        "--(\\d{2})-(\\d{2})(Z|[+-]\\d{2}:\\d{2})?");
    private static final Pattern G_DAY_PATTERN = Pattern.compile(
        "---(\\d{2})(Z|[+-]\\d{2}:\\d{2})?");
    private static final Pattern G_MONTH_PATTERN = Pattern.compile(
        "--(\\d{2})(Z|[+-]\\d{2}:\\d{2})?");
    
    // Private constructor - use factory methods
    private XPathDateTime(DateTimeType type, Integer year, Integer month, Integer day,
                          Integer hour, Integer minute, BigDecimal second, ZoneOffset timezone,
                          boolean negative, Integer durationYears, Integer durationMonths,
                          Integer durationDays, Integer durationHours, Integer durationMinutes,
                          BigDecimal durationSeconds, String lexicalValue) {
        this.type = type;
        this.year = year;
        this.month = month;
        this.day = day;
        this.hour = hour;
        this.minute = minute;
        this.second = second;
        this.timezone = timezone;
        this.negative = negative;
        this.durationYears = durationYears;
        this.durationMonths = durationMonths;
        this.durationDays = durationDays;
        this.durationHours = durationHours;
        this.durationMinutes = durationMinutes;
        this.durationSeconds = durationSeconds;
        this.lexicalValue = lexicalValue;
    }
    
    // ========== Factory methods ==========
    
    /**
     * Parses an xs:dateTime value.
     *
     * @param value the lexical representation
     * @return the parsed dateTime value
     * @throws XPathException if the value is invalid
     */
    public static XPathDateTime parseDateTime(String value) throws XPathException {
        if (value == null || value.isEmpty()) {
            throw new XPathException("Invalid xs:dateTime: empty value");
        }
        value = value.trim();
        
        Matcher m = DATE_TIME_PATTERN.matcher(value);
        if (!m.matches()) {
            throw new XPathException("Invalid xs:dateTime: " + value);
        }
        
        try {
            int year = Integer.parseInt(m.group(1));
            int month = Integer.parseInt(m.group(2));
            int day = Integer.parseInt(m.group(3));
            int hour = Integer.parseInt(m.group(4));
            int minute = Integer.parseInt(m.group(5));
            BigDecimal second = new BigDecimal(m.group(6));
            ZoneOffset tz = parseTimezone(m.group(7));
            
            validateDateTime(year, month, day, hour, minute, second);
            
            return new XPathDateTime(DateTimeType.DATE_TIME, year, month, day,
                                     hour, minute, second, tz,
                                     false, null, null, null, null, null, null, value);
        } catch (NumberFormatException e) {
            throw new XPathException("Invalid xs:dateTime: " + value, e);
        }
    }
    
    /**
     * Parses an xs:date value.
     *
     * @param value the lexical representation
     * @return the parsed date value
     * @throws XPathException if the value is invalid
     */
    public static XPathDateTime parseDate(String value) throws XPathException {
        if (value == null || value.isEmpty()) {
            throw new XPathException("Invalid xs:date: empty value");
        }
        value = value.trim();
        
        Matcher m = DATE_PATTERN.matcher(value);
        if (!m.matches()) {
            throw new XPathException("Invalid xs:date: " + value);
        }
        
        try {
            int year = Integer.parseInt(m.group(1));
            int month = Integer.parseInt(m.group(2));
            int day = Integer.parseInt(m.group(3));
            ZoneOffset tz = parseTimezone(m.group(4));
            
            validateDate(year, month, day);
            
            return new XPathDateTime(DateTimeType.DATE, year, month, day,
                                     null, null, null, tz,
                                     false, null, null, null, null, null, null, value);
        } catch (NumberFormatException e) {
            throw new XPathException("Invalid xs:date: " + value, e);
        }
    }
    
    /**
     * Parses an xs:time value.
     *
     * @param value the lexical representation
     * @return the parsed time value
     * @throws XPathException if the value is invalid
     */
    public static XPathDateTime parseTime(String value) throws XPathException {
        if (value == null || value.isEmpty()) {
            throw new XPathException("Invalid xs:time: empty value");
        }
        value = value.trim();
        
        Matcher m = TIME_PATTERN.matcher(value);
        if (!m.matches()) {
            throw new XPathException("Invalid xs:time: " + value);
        }
        
        try {
            int hour = Integer.parseInt(m.group(1));
            int minute = Integer.parseInt(m.group(2));
            BigDecimal second = new BigDecimal(m.group(3));
            ZoneOffset tz = parseTimezone(m.group(4));
            
            validateTime(hour, minute, second);
            
            return new XPathDateTime(DateTimeType.TIME, null, null, null,
                                     hour, minute, second, tz,
                                     false, null, null, null, null, null, null, value);
        } catch (NumberFormatException e) {
            throw new XPathException("Invalid xs:time: " + value, e);
        }
    }
    
    /**
     * Parses an xs:duration value.
     *
     * @param value the lexical representation
     * @return the parsed duration value
     * @throws XPathException if the value is invalid
     */
    public static XPathDateTime parseDuration(String value) throws XPathException {
        if (value == null || value.isEmpty()) {
            throw new XPathException("Invalid xs:duration: empty value");
        }
        value = value.trim();
        
        Matcher m = DURATION_PATTERN.matcher(value);
        if (!m.matches()) {
            throw new XPathException("Invalid xs:duration: " + value);
        }
        
        boolean negative = m.group(1) != null;
        Integer years = m.group(2) != null ? Integer.parseInt(m.group(2)) : null;
        Integer months = m.group(3) != null ? Integer.parseInt(m.group(3)) : null;
        Integer days = m.group(4) != null ? Integer.parseInt(m.group(4)) : null;
        Integer hours = m.group(5) != null ? Integer.parseInt(m.group(5)) : null;
        Integer minutes = m.group(6) != null ? Integer.parseInt(m.group(6)) : null;
        BigDecimal seconds = m.group(7) != null ? new BigDecimal(m.group(7)) : null;
        
        // Must have at least one component
        if (years == null && months == null && days == null && 
            hours == null && minutes == null && seconds == null) {
            throw new XPathException("Invalid xs:duration: " + value);
        }
        
        return new XPathDateTime(DateTimeType.DURATION, null, null, null,
                                 null, null, null, null,
                                 negative, years, months, days, hours, minutes, seconds, value);
    }
    
    /**
     * Parses an xs:yearMonthDuration value.
     *
     * @param value the lexical representation
     * @return the parsed duration value
     * @throws XPathException if the value is invalid
     */
    public static XPathDateTime parseYearMonthDuration(String value) throws XPathException {
        XPathDateTime d = parseDuration(value);
        // Must only have year/month components
        if (d.durationDays != null || d.durationHours != null || 
            d.durationMinutes != null || d.durationSeconds != null) {
            throw new XPathException("Invalid xs:yearMonthDuration: " + value);
        }
        return new XPathDateTime(DateTimeType.YEAR_MONTH_DURATION, null, null, null,
                                 null, null, null, null,
                                 d.negative, d.durationYears, d.durationMonths, 
                                 null, null, null, null, value);
    }
    
    /**
     * Parses an xs:dayTimeDuration value.
     *
     * @param value the lexical representation
     * @return the parsed duration value
     * @throws XPathException if the value is invalid
     */
    public static XPathDateTime parseDayTimeDuration(String value) throws XPathException {
        XPathDateTime d = parseDuration(value);
        // Must only have day/time components
        if (d.durationYears != null || d.durationMonths != null) {
            throw new XPathException("Invalid xs:dayTimeDuration: " + value);
        }
        return new XPathDateTime(DateTimeType.DAY_TIME_DURATION, null, null, null,
                                 null, null, null, null,
                                 d.negative, null, null, d.durationDays, 
                                 d.durationHours, d.durationMinutes, d.durationSeconds, value);
    }
    
    /**
     * Parses an xs:gYearMonth value (e.g., "2024-03", "2024-03Z", "2024-03+05:00").
     *
     * @param value the lexical representation
     * @return the parsed gYearMonth value
     * @throws XPathException if the value is invalid
     */
    public static XPathDateTime parseGYearMonth(String value) throws XPathException {
        if (value == null || value.isEmpty()) {
            throw new XPathException("Invalid xs:gYearMonth: empty value");
        }
        value = value.trim();
        
        Matcher m = G_YEAR_MONTH_PATTERN.matcher(value);
        if (!m.matches()) {
            throw new XPathException("Invalid xs:gYearMonth: " + value);
        }
        
        try {
            int year = Integer.parseInt(m.group(1));
            int month = Integer.parseInt(m.group(2));
            ZoneOffset tz = parseTimezone(m.group(3));
            
            if (month < 1 || month > 12) {
                throw new XPathException("Invalid month in xs:gYearMonth: " + value);
            }
            
            return new XPathDateTime(DateTimeType.G_YEAR_MONTH, year, month, null,
                                     null, null, null, tz,
                                     false, null, null, null, null, null, null, value);
        } catch (NumberFormatException e) {
            throw new XPathException("Invalid xs:gYearMonth: " + value, e);
        }
    }
    
    /**
     * Parses an xs:gYear value (e.g., "2024", "2024Z", "-0044+01:00").
     *
     * @param value the lexical representation
     * @return the parsed gYear value
     * @throws XPathException if the value is invalid
     */
    public static XPathDateTime parseGYear(String value) throws XPathException {
        if (value == null || value.isEmpty()) {
            throw new XPathException("Invalid xs:gYear: empty value");
        }
        value = value.trim();
        
        Matcher m = G_YEAR_PATTERN.matcher(value);
        if (!m.matches()) {
            throw new XPathException("Invalid xs:gYear: " + value);
        }
        
        try {
            int year = Integer.parseInt(m.group(1));
            ZoneOffset tz = parseTimezone(m.group(2));
            
            return new XPathDateTime(DateTimeType.G_YEAR, year, null, null,
                                     null, null, null, tz,
                                     false, null, null, null, null, null, null, value);
        } catch (NumberFormatException e) {
            throw new XPathException("Invalid xs:gYear: " + value, e);
        }
    }
    
    /**
     * Parses an xs:gMonthDay value (e.g., "--12-25", "--12-25Z").
     *
     * @param value the lexical representation
     * @return the parsed gMonthDay value
     * @throws XPathException if the value is invalid
     */
    public static XPathDateTime parseGMonthDay(String value) throws XPathException {
        if (value == null || value.isEmpty()) {
            throw new XPathException("Invalid xs:gMonthDay: empty value");
        }
        value = value.trim();
        
        Matcher m = G_MONTH_DAY_PATTERN.matcher(value);
        if (!m.matches()) {
            throw new XPathException("Invalid xs:gMonthDay: " + value);
        }
        
        try {
            int month = Integer.parseInt(m.group(1));
            int day = Integer.parseInt(m.group(2));
            ZoneOffset tz = parseTimezone(m.group(3));
            
            if (month < 1 || month > 12) {
                throw new XPathException("Invalid month in xs:gMonthDay: " + value);
            }
            // Basic day validation (some months have fewer days but without year we allow up to 31)
            if (day < 1 || day > 31) {
                throw new XPathException("Invalid day in xs:gMonthDay: " + value);
            }
            
            return new XPathDateTime(DateTimeType.G_MONTH_DAY, null, month, day,
                                     null, null, null, tz,
                                     false, null, null, null, null, null, null, value);
        } catch (NumberFormatException e) {
            throw new XPathException("Invalid xs:gMonthDay: " + value, e);
        }
    }
    
    /**
     * Parses an xs:gDay value (e.g., "---25", "---25Z").
     *
     * @param value the lexical representation
     * @return the parsed gDay value
     * @throws XPathException if the value is invalid
     */
    public static XPathDateTime parseGDay(String value) throws XPathException {
        if (value == null || value.isEmpty()) {
            throw new XPathException("Invalid xs:gDay: empty value");
        }
        value = value.trim();
        
        Matcher m = G_DAY_PATTERN.matcher(value);
        if (!m.matches()) {
            throw new XPathException("Invalid xs:gDay: " + value);
        }
        
        try {
            int day = Integer.parseInt(m.group(1));
            ZoneOffset tz = parseTimezone(m.group(2));
            
            if (day < 1 || day > 31) {
                throw new XPathException("Invalid day in xs:gDay: " + value);
            }
            
            return new XPathDateTime(DateTimeType.G_DAY, null, null, day,
                                     null, null, null, tz,
                                     false, null, null, null, null, null, null, value);
        } catch (NumberFormatException e) {
            throw new XPathException("Invalid xs:gDay: " + value, e);
        }
    }
    
    /**
     * Parses an xs:gMonth value (e.g., "--12", "--12Z").
     *
     * @param value the lexical representation
     * @return the parsed gMonth value
     * @throws XPathException if the value is invalid
     */
    public static XPathDateTime parseGMonth(String value) throws XPathException {
        if (value == null || value.isEmpty()) {
            throw new XPathException("Invalid xs:gMonth: empty value");
        }
        value = value.trim();
        
        Matcher m = G_MONTH_PATTERN.matcher(value);
        if (!m.matches()) {
            throw new XPathException("Invalid xs:gMonth: " + value);
        }
        
        try {
            int month = Integer.parseInt(m.group(1));
            ZoneOffset tz = parseTimezone(m.group(2));
            
            if (month < 1 || month > 12) {
                throw new XPathException("Invalid month in xs:gMonth: " + value);
            }
            
            return new XPathDateTime(DateTimeType.G_MONTH, null, month, null,
                                     null, null, null, tz,
                                     false, null, null, null, null, null, null, value);
        } catch (NumberFormatException e) {
            throw new XPathException("Invalid xs:gMonth: " + value, e);
        }
    }
    
    /**
     * Creates a dateTime from a date and time.
     *
     * @param date the date component
     * @param time the time component
     * @return the combined dateTime
     * @throws XPathException if the values are incompatible
     */
    public static XPathDateTime dateTime(XPathDateTime date, XPathDateTime time) throws XPathException {
        if (date.type != DateTimeType.DATE) {
            throw new XPathException("First argument to dateTime must be xs:date");
        }
        if (time.type != DateTimeType.TIME) {
            throw new XPathException("Second argument to dateTime must be xs:time");
        }
        
        // Determine timezone - must be compatible
        ZoneOffset tz;
        if (date.timezone != null && time.timezone != null && !date.timezone.equals(time.timezone)) {
            throw new XPathException("Incompatible timezones in dateTime constructor");
        }
        tz = date.timezone != null ? date.timezone : time.timezone;
        
        String lexical = String.format("%04d-%02d-%02dT%s", 
            date.year, date.month, date.day, time.formatTimeComponent());
        
        return new XPathDateTime(DateTimeType.DATE_TIME, date.year, date.month, date.day,
                                 time.hour, time.minute, time.second, tz,
                                 false, null, null, null, null, null, null, lexical);
    }
    
    /**
     * Creates a dateTime for the current instant.
     *
     * @return the current dateTime
     */
    public static XPathDateTime now() {
        ZonedDateTime now = ZonedDateTime.now();
        String lexical = now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        return new XPathDateTime(DateTimeType.DATE_TIME, 
                                 now.getYear(), now.getMonthValue(), now.getDayOfMonth(),
                                 now.getHour(), now.getMinute(), 
                                 BigDecimal.valueOf(now.getSecond()).add(
                                     BigDecimal.valueOf(now.getNano(), 9)),
                                 now.getOffset(),
                                 false, null, null, null, null, null, null, lexical);
    }
    
    /**
     * Creates a date for the current day.
     *
     * @return the current date
     */
    public static XPathDateTime today() {
        ZonedDateTime now = ZonedDateTime.now();
        String lexical = now.toLocalDate() + now.getOffset().getId();
        return new XPathDateTime(DateTimeType.DATE, 
                                 now.getYear(), now.getMonthValue(), now.getDayOfMonth(),
                                 null, null, null, now.getOffset(),
                                 false, null, null, null, null, null, null, lexical);
    }
    
    /**
     * Creates a time for the current time of day.
     *
     * @return the current time
     */
    public static XPathDateTime currentTime() {
        ZonedDateTime now = ZonedDateTime.now();
        String lexical = now.toLocalTime() + now.getOffset().getId();
        return new XPathDateTime(DateTimeType.TIME, null, null, null,
                                 now.getHour(), now.getMinute(), 
                                 BigDecimal.valueOf(now.getSecond()).add(
                                     BigDecimal.valueOf(now.getNano(), 9)),
                                 now.getOffset(),
                                 false, null, null, null, null, null, null, lexical);
    }
    
    // ========== Accessor methods ==========
    
    public DateTimeType getDateTimeType() {
        return type;
    }
    
    public Integer getYear() {
        return year;
    }
    
    public Integer getMonth() {
        return month;
    }
    
    public Integer getDay() {
        return day;
    }
    
    public Integer getHour() {
        return hour;
    }
    
    public Integer getMinute() {
        return minute;
    }
    
    public BigDecimal getSecond() {
        return second;
    }
    
    public ZoneOffset getTimezone() {
        return timezone;
    }
    
    public boolean isNegative() {
        return negative;
    }
    
    public Integer getDurationYears() {
        return durationYears;
    }
    
    public Integer getDurationMonths() {
        return durationMonths;
    }
    
    public Integer getDurationDays() {
        return durationDays;
    }
    
    public Integer getDurationHours() {
        return durationHours;
    }
    
    public Integer getDurationMinutes() {
        return durationMinutes;
    }
    
    public BigDecimal getDurationSeconds() {
        return durationSeconds;
    }
    
    /**
     * Returns the timezone as a dayTimeDuration, or null if no timezone.
     */
    public XPathDateTime getTimezoneAsDuration() {
        if (timezone == null) {
            return null;
        }
        int totalSeconds = timezone.getTotalSeconds();
        boolean neg = totalSeconds < 0;
        if (neg) {
            totalSeconds = -totalSeconds;
        }
        int hours = totalSeconds / 3600;
        int minutes = (totalSeconds % 3600) / 60;
        int seconds = totalSeconds % 60;
        
        String lexical = (neg ? "-" : "") + "PT" + hours + "H" + minutes + "M" + seconds + "S";
        return new XPathDateTime(DateTimeType.DAY_TIME_DURATION, null, null, null,
                                 null, null, null, null,
                                 neg, null, null, 0, hours, minutes, BigDecimal.valueOf(seconds), lexical);
    }
    
    // ========== XPathValue implementation ==========
    
    @Override
    public Type getType() {
        return Type.ATOMIC;
    }
    
    @Override
    public String asString() {
        return lexicalValue;
    }
    
    @Override
    public double asNumber() {
        // Date/time values cannot be converted to numbers
        return Double.NaN;
    }
    
    @Override
    public boolean asBoolean() {
        // Non-empty date/time is truthy
        return true;
    }
    
    @Override
    public XPathNodeSet asNodeSet() {
        return null;
    }
    
    // ========== Comparison ==========
    
    @Override
    public int compareTo(XPathDateTime other) {
        if (this.type != other.type) {
            throw new IllegalArgumentException("Cannot compare " + type + " with " + other.type);
        }
        
        switch (type) {
            case DATE_TIME:
                return compareDateTime(other);
            case DATE:
                return compareDate(other);
            case TIME:
                return compareTime(other);
            case DURATION:
            case YEAR_MONTH_DURATION:
            case DAY_TIME_DURATION:
                return compareDuration(other);
            default:
                return 0;
        }
    }
    
    private int compareDateTime(XPathDateTime other) {
        int c = Integer.compare(year, other.year);
        if (c != 0) {
            return c;
        }
        c = Integer.compare(month, other.month);
        if (c != 0) {
            return c;
        }
        c = Integer.compare(day, other.day);
        if (c != 0) {
            return c;
        }
        c = Integer.compare(hour, other.hour);
        if (c != 0) {
            return c;
        }
        c = Integer.compare(minute, other.minute);
        if (c != 0) {
            return c;
        }
        return second.compareTo(other.second);
    }
    
    private int compareDate(XPathDateTime other) {
        int c = Integer.compare(year, other.year);
        if (c != 0) {
            return c;
        }
        c = Integer.compare(month, other.month);
        if (c != 0) {
            return c;
        }
        return Integer.compare(day, other.day);
    }
    
    private int compareTime(XPathDateTime other) {
        int c = Integer.compare(hour, other.hour);
        if (c != 0) {
            return c;
        }
        c = Integer.compare(minute, other.minute);
        if (c != 0) {
            return c;
        }
        return second.compareTo(other.second);
    }
    
    private int compareDuration(XPathDateTime other) {
        // Compare total months
        int thisMonths = (durationYears != null ? durationYears : 0) * 12 + 
                        (durationMonths != null ? durationMonths : 0);
        int otherMonths = (other.durationYears != null ? other.durationYears : 0) * 12 + 
                         (other.durationMonths != null ? other.durationMonths : 0);
        if (negative) {
            thisMonths = -thisMonths;
        }
        if (other.negative) {
            otherMonths = -otherMonths;
        }
        
        int c = Integer.compare(thisMonths, otherMonths);
        if (c != 0) {
            return c;
        }
        
        // Compare total seconds
        BigDecimal thisSeconds = getTotalSeconds();
        BigDecimal otherSeconds = other.getTotalSeconds();
        return thisSeconds.compareTo(otherSeconds);
    }
    
    private BigDecimal getTotalSeconds() {
        BigDecimal total = BigDecimal.ZERO;
        if (durationDays != null) {
            total = total.add(BigDecimal.valueOf(durationDays * 86400L));
        }
        if (durationHours != null) {
            total = total.add(BigDecimal.valueOf(durationHours * 3600L));
        }
        if (durationMinutes != null) {
            total = total.add(BigDecimal.valueOf(durationMinutes * 60L));
        }
        if (durationSeconds != null) {
            total = total.add(durationSeconds);
        }
        if (negative) {
            total = total.negate();
        }
        return total;
    }
    
    // ========== Helpers ==========
    
    private static ZoneOffset parseTimezone(String tz) {
        if (tz == null || tz.isEmpty()) {
            return null;
        }
        if ("Z".equals(tz)) {
            return ZoneOffset.UTC;
        }
        return ZoneOffset.of(tz);
    }
    
    private static void validateDate(int year, int month, int day) throws XPathException {
        if (month < 1 || month > 12) {
            throw new XPathException("Invalid month: " + month);
        }
        int maxDay = YearMonth.of(year, month).lengthOfMonth();
        if (day < 1 || day > maxDay) {
            throw new XPathException("Invalid day: " + day + " for month " + month);
        }
    }
    
    private static void validateTime(int hour, int minute, BigDecimal second) throws XPathException {
        if (hour < 0 || hour > 24) {
            throw new XPathException("Invalid hour: " + hour);
        }
        if (minute < 0 || minute > 59) {
            throw new XPathException("Invalid minute: " + minute);
        }
        if (second.compareTo(BigDecimal.ZERO) < 0 || second.compareTo(BigDecimal.valueOf(60)) >= 0) {
            throw new XPathException("Invalid second: " + second);
        }
        // hour 24 only valid with 00:00:00
        if (hour == 24 && (minute != 0 || second.compareTo(BigDecimal.ZERO) != 0)) {
            throw new XPathException("Invalid time: 24:00:00 must be exactly midnight");
        }
    }
    
    private static void validateDateTime(int year, int month, int day, 
                                         int hour, int minute, BigDecimal second) throws XPathException {
        validateDate(year, month, day);
        validateTime(hour, minute, second);
    }
    
    private String formatTimeComponent() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%02d:%02d:", hour, minute));
        if (second.scale() == 0) {
            sb.append(String.format("%02d", second.intValue()));
        } else {
            String s = second.toPlainString();
            if (second.compareTo(BigDecimal.TEN) < 0) {
                sb.append("0");
            }
            sb.append(s);
        }
        if (timezone != null) {
            sb.append(timezone.getId());
        }
        return sb.toString();
    }
    
    // ========== Arithmetic operations ==========
    
    /**
     * Adds a duration to this date/time value.
     * 
     * @param duration the duration to add
     * @return the resulting date/time
     * @throws XPathException if the operation is invalid
     */
    public XPathDateTime add(XPathDateTime duration) throws XPathException {
        if (!isDuration(duration.type)) {
            throw new XPathException("Can only add a duration to a date/time");
        }
        
        switch (type) {
            case DATE_TIME:
                return addToDateTime(duration);
            case DATE:
                return addToDate(duration);
            case TIME:
                return addToTime(duration);
            case DURATION:
            case YEAR_MONTH_DURATION:
            case DAY_TIME_DURATION:
                return addDurations(duration);
            default:
                throw new XPathException("Cannot add duration to " + type);
        }
    }
    
    /**
     * Subtracts a duration or another date/time from this value.
     * 
     * @param other the value to subtract (duration or same date/time type)
     * @return the result (date/time if subtracting duration, duration if subtracting date/time)
     * @throws XPathException if the operation is invalid
     */
    public XPathDateTime subtract(XPathDateTime other) throws XPathException {
        // Subtracting a duration from a date/time
        if (isDuration(other.type)) {
            return add(other.negate());
        }
        
        // Subtracting two durations
        if (isDuration(type) && isDuration(other.type)) {
            return addDurations(other.negate());
        }
        
        // Subtracting two date/times to get a duration
        if (type == other.type) {
            switch (type) {
                case DATE_TIME:
                    return subtractDateTimes(other);
                case DATE:
                    return subtractDates(other);
                case TIME:
                    return subtractTimes(other);
                default:
                    throw new XPathException("Cannot subtract " + type + " values");
            }
        }
        
        throw new XPathException("Cannot subtract " + other.type + " from " + type);
    }
    
    /**
     * Multiplies this duration by a number.
     * 
     * @param factor the multiplication factor
     * @return the resulting duration
     * @throws XPathException if this is not a duration
     */
    public XPathDateTime multiply(double factor) throws XPathException {
        if (!isDuration(type)) {
            throw new XPathException("Can only multiply durations");
        }
        
        if (Double.isNaN(factor) || Double.isInfinite(factor)) {
            throw new XPathException("Cannot multiply duration by " + factor);
        }
        
        // For yearMonthDuration, multiply the total months
        if (type == DateTimeType.YEAR_MONTH_DURATION) {
            int totalMonths = (durationYears != null ? durationYears : 0) * 12 + 
                             (durationMonths != null ? durationMonths : 0);
            if (negative) {
                totalMonths = -totalMonths;
            }
            
            double result = totalMonths * factor;
            long resultMonths = Math.round(result);
            boolean neg = resultMonths < 0;
            resultMonths = Math.abs(resultMonths);
            
            int years = (int) (resultMonths / 12);
            int months = (int) (resultMonths % 12);
            
            String lexical = formatYearMonthDuration(neg, years, months);
            return new XPathDateTime(DateTimeType.YEAR_MONTH_DURATION, null, null, null, null, null, null, null,
                                     neg, years > 0 ? years : null, months > 0 ? months : null, 
                                     null, null, null, null, lexical);
        }
        
        // For dayTimeDuration, multiply total seconds
        BigDecimal totalSeconds = getTotalSeconds();
        BigDecimal result = totalSeconds.multiply(BigDecimal.valueOf(factor));
        
        return durationFromSeconds(result, type);
    }
    
    /**
     * Divides this duration by a number.
     * 
     * @param divisor the divisor
     * @return the resulting duration
     * @throws XPathException if this is not a duration or divisor is zero
     */
    public XPathDateTime divide(double divisor) throws XPathException {
        if (!isDuration(type)) {
            throw new XPathException("Can only divide durations");
        }
        
        if (divisor == 0 || Double.isNaN(divisor)) {
            throw new XPathException("Cannot divide duration by " + divisor);
        }
        
        // For yearMonthDuration, divide the total months
        if (type == DateTimeType.YEAR_MONTH_DURATION) {
            int totalMonths = (durationYears != null ? durationYears : 0) * 12 + 
                             (durationMonths != null ? durationMonths : 0);
            if (negative) {
                totalMonths = -totalMonths;
            }
            
            double result = totalMonths / divisor;
            long resultMonths = Math.round(result);
            boolean neg = resultMonths < 0;
            resultMonths = Math.abs(resultMonths);
            
            int years = (int) (resultMonths / 12);
            int months = (int) (resultMonths % 12);
            
            String lexical = formatYearMonthDuration(neg, years, months);
            return new XPathDateTime(DateTimeType.YEAR_MONTH_DURATION, null, null, null, null, null, null, null,
                                     neg, years > 0 ? years : null, months > 0 ? months : null, 
                                     null, null, null, null, lexical);
        }
        
        // For dayTimeDuration, divide total seconds
        BigDecimal totalSeconds = getTotalSeconds();
        BigDecimal result = totalSeconds.divide(BigDecimal.valueOf(divisor), 10, RoundingMode.HALF_UP);
        
        return durationFromSeconds(result, type);
    }
    
    /**
     * Divides this duration by another duration.
     * 
     * @param other the divisor duration
     * @return the ratio as a decimal
     * @throws XPathException if either is not a duration
     */
    public BigDecimal divideByDuration(XPathDateTime other) throws XPathException {
        if (!isDuration(type) || !isDuration(other.type)) {
            throw new XPathException("Can only divide durations by durations");
        }
        
        BigDecimal thisSeconds = getTotalSeconds();
        BigDecimal otherSeconds = other.getTotalSeconds();
        
        if (otherSeconds.compareTo(BigDecimal.ZERO) == 0) {
            throw new XPathException("Cannot divide by zero duration");
        }
        
        return thisSeconds.divide(otherSeconds, 10, RoundingMode.HALF_UP);
    }
    
    private XPathDateTime addToDateTime(XPathDateTime duration) throws XPathException {
        // Start with current values
        int y = year;
        int mo = month;
        int d = day;
        int h = hour;
        int mi = minute;
        BigDecimal s = second;
        
        // Add year-month component
        if (duration.durationMonths != null || duration.durationYears != null) {
            int totalMonths = (duration.durationYears != null ? duration.durationYears : 0) * 12 +
                             (duration.durationMonths != null ? duration.durationMonths : 0);
            if (duration.negative) {
                totalMonths = -totalMonths;
            }
            
            mo += totalMonths;
            while (mo > 12) { y++; mo -= 12; }
            while (mo < 1) { y--; mo += 12; }
            
            // Adjust day if needed (e.g., Jan 31 + 1 month = Feb 28/29)
            int maxDay = YearMonth.of(y, mo).lengthOfMonth();
            if (d > maxDay) {
                d = maxDay;
            }
        }
        
        // Add day-time component
        if (duration.durationDays != null || duration.durationHours != null || 
            duration.durationMinutes != null || duration.durationSeconds != null) {
            
            BigDecimal totalSeconds = BigDecimal.ZERO;
            if (duration.durationDays != null) 
                totalSeconds = totalSeconds.add(BigDecimal.valueOf(duration.durationDays * 86400L));
            if (duration.durationHours != null) 
                totalSeconds = totalSeconds.add(BigDecimal.valueOf(duration.durationHours * 3600L));
            if (duration.durationMinutes != null) 
                totalSeconds = totalSeconds.add(BigDecimal.valueOf(duration.durationMinutes * 60L));
            if (duration.durationSeconds != null) 
                totalSeconds = totalSeconds.add(duration.durationSeconds);
            if (duration.negative) {
                totalSeconds = totalSeconds.negate();
            }
            
            // Convert current time to seconds and add
            BigDecimal currentSeconds = BigDecimal.valueOf(h * 3600L + mi * 60L).add(s);
            BigDecimal newSeconds = currentSeconds.add(totalSeconds);
            
            // Handle day overflow/underflow
            long extraDays = newSeconds.divideToIntegralValue(BigDecimal.valueOf(86400)).longValue();
            newSeconds = newSeconds.remainder(BigDecimal.valueOf(86400));
            if (newSeconds.compareTo(BigDecimal.ZERO) < 0) {
                newSeconds = newSeconds.add(BigDecimal.valueOf(86400));
                extraDays--;
            }
            
            // Convert back to h:m:s
            long secs = newSeconds.longValue();
            h = (int) (secs / 3600);
            secs %= 3600;
            mi = (int) (secs / 60);
            s = newSeconds.remainder(BigDecimal.valueOf(60));
            
            // Add extra days
            LocalDate date = LocalDate.of(y, mo, d).plusDays(extraDays);
            y = date.getYear();
            mo = date.getMonthValue();
            d = date.getDayOfMonth();
        }
        
        String lexical = formatDateTime(y, mo, d, h, mi, s, timezone);
        return new XPathDateTime(DateTimeType.DATE_TIME, y, mo, d, h, mi, s, timezone,
                                 false, null, null, null, null, null, null, lexical);
    }
    
    private XPathDateTime addToDate(XPathDateTime duration) throws XPathException {
        int y = year;
        int mo = month;
        int d = day;
        
        // Add year-month component
        if (duration.durationMonths != null || duration.durationYears != null) {
            int totalMonths = (duration.durationYears != null ? duration.durationYears : 0) * 12 +
                             (duration.durationMonths != null ? duration.durationMonths : 0);
            if (duration.negative) {
                totalMonths = -totalMonths;
            }
            
            mo += totalMonths;
            while (mo > 12) { y++; mo -= 12; }
            while (mo < 1) { y--; mo += 12; }
            
            int maxDay = YearMonth.of(y, mo).lengthOfMonth();
            if (d > maxDay) {
                d = maxDay;
            }
        }
        
        // Add day component
        if (duration.durationDays != null) {
            int days = duration.durationDays;
            if (duration.negative) {
                days = -days;
            }
            LocalDate date = LocalDate.of(y, mo, d).plusDays(days);
            y = date.getYear();
            mo = date.getMonthValue();
            d = date.getDayOfMonth();
        }
        
        String lexical = formatDate(y, mo, d, timezone);
        return new XPathDateTime(DateTimeType.DATE, y, mo, d, null, null, null, timezone,
                                 false, null, null, null, null, null, null, lexical);
    }
    
    private XPathDateTime addToTime(XPathDateTime duration) throws XPathException {
        if (duration.durationYears != null || duration.durationMonths != null) {
            throw new XPathException("Cannot add yearMonthDuration to time");
        }
        
        int h = hour;
        int mi = minute;
        BigDecimal s = second;
        
        BigDecimal totalSeconds = BigDecimal.ZERO;
        if (duration.durationDays != null) 
            totalSeconds = totalSeconds.add(BigDecimal.valueOf(duration.durationDays * 86400L));
        if (duration.durationHours != null) 
            totalSeconds = totalSeconds.add(BigDecimal.valueOf(duration.durationHours * 3600L));
        if (duration.durationMinutes != null) 
            totalSeconds = totalSeconds.add(BigDecimal.valueOf(duration.durationMinutes * 60L));
        if (duration.durationSeconds != null) {
            totalSeconds = totalSeconds.add(duration.durationSeconds);
        }
        if (duration.negative) {
            totalSeconds = totalSeconds.negate();
        }
        
        BigDecimal currentSeconds = BigDecimal.valueOf(h * 3600L + mi * 60L).add(s);
        BigDecimal newSeconds = currentSeconds.add(totalSeconds);
        
        // Wrap within 24 hours
        newSeconds = newSeconds.remainder(BigDecimal.valueOf(86400));
        if (newSeconds.compareTo(BigDecimal.ZERO) < 0) {
            newSeconds = newSeconds.add(BigDecimal.valueOf(86400));
        }
        
        long secs = newSeconds.longValue();
        h = (int) (secs / 3600);
        secs %= 3600;
        mi = (int) (secs / 60);
        s = newSeconds.remainder(BigDecimal.valueOf(60));
        
        String lexical = formatTime(h, mi, s, timezone);
        return new XPathDateTime(DateTimeType.TIME, null, null, null, h, mi, s, timezone,
                                 false, null, null, null, null, null, null, lexical);
    }
    
    private XPathDateTime addDurations(XPathDateTime other) throws XPathException {
        // For yearMonthDuration, only add year/month components
        if (type == DateTimeType.YEAR_MONTH_DURATION || other.type == DateTimeType.YEAR_MONTH_DURATION) {
            int months1 = (durationYears != null ? durationYears : 0) * 12 + 
                         (durationMonths != null ? durationMonths : 0);
            if (negative) {
                months1 = -months1;
            }
            
            int months2 = (other.durationYears != null ? other.durationYears : 0) * 12 + 
                         (other.durationMonths != null ? other.durationMonths : 0);
            if (other.negative) {
                months2 = -months2;
            }
            
            int totalMonths = months1 + months2;
            boolean neg = totalMonths < 0;
            totalMonths = Math.abs(totalMonths);
            
            int years = totalMonths / 12;
            int months = totalMonths % 12;
            
            String lexical = formatYearMonthDuration(neg, years, months);
            return new XPathDateTime(DateTimeType.YEAR_MONTH_DURATION, null, null, null, null, null, null, null,
                                     neg, years > 0 ? years : null, months > 0 ? months : null, 
                                     null, null, null, null, lexical);
        }
        
        // For dayTimeDuration, add as seconds
        BigDecimal total1 = getTotalSeconds();
        BigDecimal total2 = other.getTotalSeconds();
        BigDecimal result = total1.add(total2);
        
        return durationFromSeconds(result, DateTimeType.DAY_TIME_DURATION);
    }
    
    private XPathDateTime subtractDateTimes(XPathDateTime other) throws XPathException {
        // Convert both to seconds since epoch and subtract
        LocalDateTime dt1 = LocalDateTime.of(year, month, day, hour, minute, second.intValue());
        LocalDateTime dt2 = LocalDateTime.of(other.year, other.month, other.day, 
                                             other.hour, other.minute, other.second.intValue());
        
        Duration diff = Duration.between(dt2, dt1);
        
        // Add fractional seconds
        BigDecimal fracDiff = second.remainder(BigDecimal.ONE)
                                    .subtract(other.second.remainder(BigDecimal.ONE));
        
        BigDecimal totalSeconds = BigDecimal.valueOf(diff.getSeconds()).add(fracDiff);
        
        return durationFromSeconds(totalSeconds, DateTimeType.DAY_TIME_DURATION);
    }
    
    private XPathDateTime subtractDates(XPathDateTime other) throws XPathException {
        LocalDate d1 = LocalDate.of(year, month, day);
        LocalDate d2 = LocalDate.of(other.year, other.month, other.day);
        
        long days = ChronoUnit.DAYS.between(d2, d1);
        BigDecimal totalSeconds = BigDecimal.valueOf(days * 86400L);
        
        return durationFromSeconds(totalSeconds, DateTimeType.DAY_TIME_DURATION);
    }
    
    private XPathDateTime subtractTimes(XPathDateTime other) throws XPathException {
        BigDecimal secs1 = BigDecimal.valueOf(hour * 3600L + minute * 60L).add(second);
        BigDecimal secs2 = BigDecimal.valueOf(other.hour * 3600L + other.minute * 60L).add(other.second);
        
        return durationFromSeconds(secs1.subtract(secs2), DateTimeType.DAY_TIME_DURATION);
    }
    
    private XPathDateTime negate() throws XPathException {
        if (!isDuration(type)) {
            throw new XPathException("Can only negate durations");
        }
        
        return new XPathDateTime(type, null, null, null, null, null, null, null,
                                 !negative, durationYears, durationMonths, durationDays,
                                 durationHours, durationMinutes, durationSeconds, 
                                 (negative ? "" : "-") + lexicalValue.replaceFirst("^-?", ""));
    }
    
    private static XPathDateTime durationFromSeconds(BigDecimal totalSeconds, DateTimeType durationType) {
        boolean neg = totalSeconds.compareTo(BigDecimal.ZERO) < 0;
        totalSeconds = totalSeconds.abs();
        
        long secs = totalSeconds.longValue();
        BigDecimal frac = totalSeconds.remainder(BigDecimal.ONE);
        
        int days = (int) (secs / 86400);
        secs %= 86400;
        int hours = (int) (secs / 3600);
        secs %= 3600;
        int minutes = (int) (secs / 60);
        BigDecimal seconds = BigDecimal.valueOf(secs % 60).add(frac);
        
        String lexical = formatDayTimeDuration(neg, days, hours, minutes, seconds);
        return new XPathDateTime(durationType, null, null, null, null, null, null, null,
                                 neg, null, null, days > 0 ? days : null, 
                                 hours > 0 ? hours : null, minutes > 0 ? minutes : null, 
                                 seconds.compareTo(BigDecimal.ZERO) > 0 ? seconds : null, lexical);
    }
    
    private static boolean isDuration(DateTimeType type) {
        return type == DateTimeType.DURATION || 
               type == DateTimeType.YEAR_MONTH_DURATION || 
               type == DateTimeType.DAY_TIME_DURATION;
    }
    
    private static String formatDateTime(int y, int mo, int d, int h, int mi, BigDecimal s, ZoneOffset tz) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%04d-%02d-%02dT%02d:%02d:", y, mo, d, h, mi));
        if (s.scale() == 0 || s.remainder(BigDecimal.ONE).compareTo(BigDecimal.ZERO) == 0) {
            sb.append(String.format("%02d", s.intValue()));
        } else {
            if (s.compareTo(BigDecimal.TEN) < 0) sb.append("0");
            sb.append(s.stripTrailingZeros().toPlainString());
        }
        if (tz != null) {
            sb.append(tz.getId().equals("Z") ? "Z" : tz.getId());
        }
        return sb.toString();
    }
    
    private static String formatDate(int y, int mo, int d, ZoneOffset tz) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%04d-%02d-%02d", y, mo, d));
        if (tz != null) {
            sb.append(tz.getId().equals("Z") ? "Z" : tz.getId());
        }
        return sb.toString();
    }
    
    private static String formatTime(int h, int mi, BigDecimal s, ZoneOffset tz) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%02d:%02d:", h, mi));
        if (s.scale() == 0 || s.remainder(BigDecimal.ONE).compareTo(BigDecimal.ZERO) == 0) {
            sb.append(String.format("%02d", s.intValue()));
        } else {
            if (s.compareTo(BigDecimal.TEN) < 0) sb.append("0");
            sb.append(s.stripTrailingZeros().toPlainString());
        }
        if (tz != null) {
            sb.append(tz.getId().equals("Z") ? "Z" : tz.getId());
        }
        return sb.toString();
    }
    
    private static String formatYearMonthDuration(boolean neg, int years, int months) {
        StringBuilder sb = new StringBuilder();
        if (neg) {
            sb.append("-");
        }
        sb.append("P");
        if (years > 0) {
            sb.append(years);
            sb.append("Y");
        }
        if (months > 0) {
            sb.append(months);
            sb.append("M");
        }
        if (years == 0 && months == 0) {
            sb.append("0M");
        }
        return sb.toString();
    }
    
    private static String formatDayTimeDuration(boolean neg, int days, int hours, int minutes, BigDecimal seconds) {
        StringBuilder sb = new StringBuilder();
        if (neg) {
            sb.append("-");
        }
        sb.append("P");
        if (days > 0) {
            sb.append(days);
            sb.append("D");
        }
        if (hours > 0 || minutes > 0 || (seconds != null && seconds.compareTo(BigDecimal.ZERO) > 0)) {
            sb.append("T");
            if (hours > 0) {
                sb.append(hours);
                sb.append("H");
            }
            if (minutes > 0) {
                sb.append(minutes);
                sb.append("M");
            }
            if (seconds != null && seconds.compareTo(BigDecimal.ZERO) > 0) {
                if (seconds.scale() == 0 || seconds.remainder(BigDecimal.ONE).compareTo(BigDecimal.ZERO) == 0) {
                    sb.append(seconds.intValue());
                } else {
                    sb.append(seconds.stripTrailingZeros().toPlainString());
                }
                sb.append("S");
            }
        }
        if (sb.length() <= 2) {
            sb.append("T0S");  // Empty duration = PT0S
        }
        return sb.toString();
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof XPathDateTime)) {
            return false;
        }
        XPathDateTime other = (XPathDateTime) obj;
        return type == other.type && lexicalValue.equals(other.lexicalValue);
    }
    
    @Override
    public int hashCode() {
        return lexicalValue.hashCode();
    }
    
    @Override
    public String toString() {
        return type.name().toLowerCase() + "(" + lexicalValue + ")";
    }
}

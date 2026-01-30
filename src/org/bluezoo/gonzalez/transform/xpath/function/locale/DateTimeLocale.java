package org.bluezoo.gonzalez.transform.xpath.function.locale;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * Facade for locale-specific date/time formatting.
 * 
 * <p>This class provides access to localized strings for:
 * <ul>
 *   <li>Month names (full and abbreviated)</li>
 *   <li>Day names (full and abbreviated)</li>
 *   <li>AM/PM markers</li>
 *   <li>Era names (AD/BC)</li>
 *   <li>Ordinal suffixes (1st, 2nd, 3rd, etc.)</li>
 *   <li>Number words (one, two, three, etc.)</li>
 * </ul>
 * 
 * <p>Instances are cached per locale for efficiency. Use {@link #forLocale(Locale)}
 * to obtain an instance.
 */
public class DateTimeLocale {
    
    private static final Map<Locale, DateTimeLocale> cache = new ConcurrentHashMap<>();
    private static final String BUNDLE_NAME = 
        "org.bluezoo.gonzalez.transform.xpath.function.locale.DateTimeMessages";
    
    private final Locale locale;
    private final ResourceBundle bundle;
    private final NumberWordFormatter numberFormatter;
    private final Set<String> minorWords;
    
    /**
     * Returns a DateTimeLocale for the specified locale.
     * Instances are cached for efficiency.
     * 
     * @param locale the locale, or null for default
     * @return the DateTimeLocale instance
     */
    public static DateTimeLocale forLocale(Locale locale) {
        if (locale == null) {
            locale = Locale.ENGLISH;
        }
        DateTimeLocale result = cache.get(locale);
        if (result == null) {
            result = new DateTimeLocale(locale);
            cache.put(locale, result);
        }
        return result;
    }
    
    /**
     * Clears the locale cache. Primarily for testing.
     */
    public static void clearCache() {
        cache.clear();
    }
    
    private DateTimeLocale(Locale locale) {
        this.locale = locale;
        this.bundle = loadBundle(locale);
        this.numberFormatter = NumberWordFormatter.forLocale(locale, bundle);
        this.minorWords = loadMinorWords();
    }
    
    private ResourceBundle loadBundle(Locale locale) {
        try {
            return ResourceBundle.getBundle(BUNDLE_NAME, locale);
        } catch (MissingResourceException e) {
            // Fall back to English
            return ResourceBundle.getBundle(BUNDLE_NAME, Locale.ENGLISH);
        }
    }
    
    private Set<String> loadMinorWords() {
        Set<String> words = new HashSet<>();
        try {
            String minorWordList = bundle.getString("minor.words");
            words.addAll(Arrays.asList(minorWordList.split(",")));
        } catch (MissingResourceException e) {
            // Default minor words
            words.addAll(Arrays.asList("and", "or", "the", "a", "an", "of", "in", "on", "to", "for", "at", "by"));
        }
        return words;
    }
    
    /**
     * Returns the locale used by this instance.
     */
    public Locale getLocale() {
        return locale;
    }
    
    /**
     * Returns a month name, selecting full or abbreviated form based on width constraints.
     * 
     * @param month the month (1-12)
     * @param minWidth minimum width constraint
     * @param maxWidth maximum width constraint (Integer.MAX_VALUE for no limit)
     * @return the month name
     */
    public String getMonthName(int month, int minWidth, int maxWidth) {
        if (month < 1 || month > 12) {
            return "";
        }
        
        String fullName = getString("month." + month);
        String abbrev = getString("month." + month + ".abbr");
        
        return selectByWidth(fullName, abbrev, minWidth, maxWidth);
    }
    
    /**
     * Returns a day name, selecting full or abbreviated form based on width constraints.
     * 
     * @param day the day of week (1=Sunday, 7=Saturday per XPath spec)
     * @param minWidth minimum width constraint
     * @param maxWidth maximum width constraint (Integer.MAX_VALUE for no limit)
     * @return the day name
     */
    public String getDayName(int day, int minWidth, int maxWidth) {
        if (day < 1 || day > 7) {
            return "";
        }
        
        String fullName = getString("day." + day);
        
        // Try abbreviations of different lengths
        String bestAbbrev = null;
        for (int len = 3; len <= 5; len++) {
            String abbrev = getStringOrNull("day." + day + ".abbr." + len);
            if (abbrev != null) {
                if (bestAbbrev == null || 
                    (abbrev.length() <= maxWidth && abbrev.length() >= minWidth)) {
                    bestAbbrev = abbrev;
                }
            }
        }
        if (bestAbbrev == null) {
            bestAbbrev = fullName.substring(0, Math.min(3, fullName.length()));
        }
        
        return selectByWidth(fullName, bestAbbrev, minWidth, maxWidth);
    }
    
    /**
     * Selects between full and abbreviated form based on width constraints.
     */
    private String selectByWidth(String fullName, String abbrev, int minWidth, int maxWidth) {
        // If max width specified and full name too long, use abbreviation
        if (maxWidth < Integer.MAX_VALUE && fullName.length() > maxWidth) {
            return padOrTruncate(abbrev, minWidth, maxWidth);
        }
        // If min width specified and abbreviation fits better
        if (minWidth > 0 && minWidth == maxWidth) {
            // Exact width requested
            if (abbrev.length() == minWidth) {
                return abbrev;
            }
            if (fullName.length() == minWidth) {
                return fullName;
            }
            // Use whichever is closer
            if (Math.abs(abbrev.length() - minWidth) < Math.abs(fullName.length() - minWidth)) {
                return padOrTruncate(abbrev, minWidth, maxWidth);
            }
        }
        return fullName;
    }
    
    private String padOrTruncate(String s, int minWidth, int maxWidth) {
        if (s.length() > maxWidth && maxWidth < Integer.MAX_VALUE) {
            return s.substring(0, maxWidth);
        }
        while (s.length() < minWidth) {
            s = s + " ";
        }
        return s;
    }
    
    /**
     * Returns the AM or PM marker.
     * 
     * @param hour the hour (0-23)
     * @param uppercase true for uppercase (AM/PM), false for lowercase (am/pm)
     * @return the AM/PM marker
     */
    public String getAmPm(int hour, boolean uppercase) {
        boolean isAm = hour < 12;
        String key = uppercase ? (isAm ? "ampm.AM" : "ampm.PM") 
                               : (isAm ? "ampm.am" : "ampm.pm");
        return getString(key);
    }
    
    /**
     * Returns the era name for a year.
     * 
     * @param year the year (positive for AD, negative for BC)
     * @return "AD" or "BC" (localized)
     */
    public String getEra(int year) {
        return year > 0 ? getString("era.ad") : getString("era.bc");
    }
    
    /**
     * Returns the ordinal suffix for a number.
     * 
     * @param value the number
     * @return the ordinal suffix (e.g., "st", "nd", "rd", "th")
     */
    public String getOrdinalSuffix(int value) {
        int absValue = Math.abs(value);
        int lastTwo = absValue % 100;
        
        // Check for special cases (11, 12, 13)
        String special = getStringOrNull("ordinal.suffix." + lastTwo);
        if (special != null) {
            return special;
        }
        
        // Check by last digit
        int lastOne = absValue % 10;
        String suffix = getStringOrNull("ordinal.suffix." + lastOne);
        if (suffix != null) {
            return suffix;
        }
        
        // Default
        return getString("ordinal.suffix.default");
    }
    
    /**
     * Formats a number as words.
     * 
     * @param value the number to format
     * @return the number as words
     */
    public String toWords(int value) {
        return numberFormatter.format(value);
    }
    
    /**
     * Formats a number as ordinal words.
     * 
     * @param value the number to format
     * @return the number as ordinal words
     */
    public String toOrdinalWords(int value) {
        return numberFormatter.formatOrdinal(value);
    }
    
    /**
     * Checks if a word is a minor word (for title case formatting).
     * 
     * @param word the word to check (lowercase)
     * @return true if the word is a minor word
     */
    public boolean isMinorWord(String word) {
        return minorWords.contains(word.toLowerCase());
    }
    
    /**
     * Gets a string from the bundle, with fallback to key.
     */
    private String getString(String key) {
        try {
            return bundle.getString(key);
        } catch (MissingResourceException e) {
            return key;
        }
    }
    
    /**
     * Gets a string from the bundle, returning null if not found.
     */
    private String getStringOrNull(String key) {
        try {
            return bundle.getString(key);
        } catch (MissingResourceException e) {
            return null;
        }
    }
}

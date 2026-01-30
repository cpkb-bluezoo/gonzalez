package org.bluezoo.gonzalez.transform.xpath.function.locale;

import java.util.Locale;
import java.util.ResourceBundle;

/**
 * Interface for formatting integers as words in a locale-specific manner.
 * 
 * <p>Different languages have fundamentally different number word systems:
 * <ul>
 *   <li>English: twenty-one (base 10)</li>
 *   <li>French: quatre-vingt-dix (80+10=90, mixed base 20)</li>
 *   <li>German: einundzwanzig (one-and-twenty, reversed)</li>
 *   <li>Spanish: veintiuno (contracted forms)</li>
 *   <li>Italian: ventuno (elided forms)</li>
 * </ul>
 * 
 * <p>Each language implementation handles these differences algorithmically
 * while using localized word lookups from ResourceBundle.
 */
public interface NumberWordFormatter {
    
    /**
     * Formats an integer as words.
     * 
     * @param value the integer to format
     * @return the number as words (e.g., 21 → "twenty one")
     */
    String format(int value);
    
    /**
     * Formats an integer as ordinal words.
     * 
     * @param value the integer to format
     * @return the number as ordinal words (e.g., 21 → "twenty first")
     */
    String formatOrdinal(int value);
    
    /**
     * Creates a NumberWordFormatter for the given locale.
     * 
     * @param locale the locale to use
     * @param bundle the ResourceBundle containing localized words
     * @return an appropriate NumberWordFormatter implementation
     */
    static NumberWordFormatter forLocale(Locale locale, ResourceBundle bundle) {
        String lang = locale.getLanguage();
        switch (lang) {
            case "fr":
                return new FrenchNumberWords(bundle);
            case "de":
                return new GermanNumberWords(bundle);
            case "es":
                return new SpanishNumberWords(bundle);
            case "it":
                return new ItalianNumberWords(bundle);
            default:
                // English (and fallback) - use "and" by default (British style)
                // Only US explicitly excludes "and"
                boolean useAnd = !"US".equals(locale.getCountry());
                return new EnglishNumberWords(bundle, useAnd);
        }
    }
}

package org.bluezoo.gonzalez.transform.xpath.function.locale;

import java.util.ResourceBundle;

/**
 * English number word formatter.
 * 
 * <p>Supports both American English (no "and" after hundred) and
 * British English (uses "and" after hundred).
 * 
 * <p>Examples:
 * <ul>
 *   <li>21 → "twenty one"</li>
 *   <li>121 → "one hundred twenty one" (US) or "one hundred and twenty one" (GB)</li>
 *   <li>1000 → "one thousand"</li>
 *   <li>2001 → "two thousand and one" (GB) or "two thousand one" (US)</li>
 * </ul>
 */
public class EnglishNumberWords implements NumberWordFormatter {
    
    private final ResourceBundle bundle;
    private final boolean useAnd;
    
    /**
     * Creates an English number word formatter.
     * 
     * @param bundle the ResourceBundle containing number words
     * @param useAnd true to use "and" after hundred (British style)
     */
    public EnglishNumberWords(ResourceBundle bundle, boolean useAnd) {
        this.bundle = bundle;
        this.useAnd = useAnd;
    }
    
    @Override
    public String format(int value) {
        if (value == 0) {
            return getWord("number.0");
        }
        if (value < 0) {
            return getWord("word.minus") + " " + formatPositive(-value);
        }
        return formatPositive(value);
    }
    
    @Override
    public String formatOrdinal(int value) {
        String words = format(value);
        return toOrdinalWords(words);
    }
    
    private String formatPositive(int value) {
        if (value < 20) {
            return getWord("number." + value);
        }
        if (value < 100) {
            int tens = (value / 10) * 10;
            int ones = value % 10;
            if (ones == 0) {
                return getWord("number." + tens);
            }
            return getWord("number." + tens) + " " + getWord("number." + ones);
        }
        if (value < 1000) {
            int hundreds = value / 100;
            int remainder = value % 100;
            String hundredWord = getWord("number." + hundreds) + " " + getWord("word.hundred");
            if (remainder == 0) {
                return hundredWord;
            }
            String connector = useAnd ? " " + getWord("word.and") + " " : " ";
            return hundredWord + connector + formatPositive(remainder);
        }
        if (value < 1000000) {
            int thousands = value / 1000;
            int remainder = value % 1000;
            String thousandWord = formatPositive(thousands) + " " + getWord("word.thousand");
            if (remainder == 0) {
                return thousandWord;
            }
            // Use "and" before small remainders in British English
            String connector = (useAnd && remainder < 100) ? " " + getWord("word.and") + " " : " ";
            return thousandWord + connector + formatPositive(remainder);
        }
        if (value < 1000000000) {
            int millions = value / 1000000;
            int remainder = value % 1000000;
            String millionWord = formatPositive(millions) + " " + getWord("word.million");
            if (remainder == 0) {
                return millionWord;
            }
            String connector = (useAnd && remainder < 100) ? " " + getWord("word.and") + " " : " ";
            return millionWord + connector + formatPositive(remainder);
        }
        // Fallback for very large numbers
        return Integer.toString(value);
    }
    
    /**
     * Converts cardinal words to ordinal words.
     * Handles irregular forms and regular suffix transformations.
     */
    private String toOrdinalWords(String words) {
        // Check for irregular ordinals at the end
        if (words.endsWith(getWord("number.1"))) {
            return replaceEnd(words, getWord("number.1"), getOrdinalWord(1));
        }
        if (words.endsWith(getWord("number.2"))) {
            return replaceEnd(words, getWord("number.2"), getOrdinalWord(2));
        }
        if (words.endsWith(getWord("number.3"))) {
            return replaceEnd(words, getWord("number.3"), getOrdinalWord(3));
        }
        if (words.endsWith(getWord("number.5"))) {
            return replaceEnd(words, getWord("number.5"), getOrdinalWord(5));
        }
        if (words.endsWith(getWord("number.8"))) {
            return replaceEnd(words, getWord("number.8"), getOrdinalWord(8));
        }
        if (words.endsWith(getWord("number.9"))) {
            return replaceEnd(words, getWord("number.9"), getOrdinalWord(9));
        }
        if (words.endsWith(getWord("number.12"))) {
            return replaceEnd(words, getWord("number.12"), getOrdinalWord(12));
        }
        // Handle -ty → -tieth (e.g., twenty → twentieth)
        if (words.endsWith("ty")) {
            return words.substring(0, words.length() - 2) + getWord("ordinal.word.suffix.ty");
        }
        // Default: add "th"
        return words + getWord("ordinal.word.suffix.default");
    }
    
    private String getOrdinalWord(int n) {
        String key = "ordinal.word." + n;
        if (bundle.containsKey(key)) {
            return bundle.getString(key);
        }
        // Fallback to cardinal + th
        return getWord("number." + n) + getWord("ordinal.word.suffix.default");
    }
    
    private String replaceEnd(String s, String oldEnd, String newEnd) {
        return s.substring(0, s.length() - oldEnd.length()) + newEnd;
    }
    
    private String getWord(String key) {
        return bundle.getString(key);
    }
}

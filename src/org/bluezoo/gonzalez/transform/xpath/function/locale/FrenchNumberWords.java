package org.bluezoo.gonzalez.transform.xpath.function.locale;

import java.util.ResourceBundle;

/**
 * French number word formatter.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 * 
 * <p>French uses a mixed base-20 (vigesimal) system for 70-99:
 * <ul>
 *   <li>70 = soixante-dix (60+10)</li>
 *   <li>80 = quatre-vingts (4×20)</li>
 *   <li>90 = quatre-vingt-dix (4×20+10)</li>
 * </ul>
 * 
 * <p>Special rules:
 * <ul>
 *   <li>21, 31, 41, 51, 61, 71 use "et" (e.g., "vingt et un")</li>
 *   <li>80 has an "s" (quatre-vingts) except when followed by another number</li>
 * </ul>
 */
public class FrenchNumberWords implements NumberWordFormatter {
    
    private final ResourceBundle bundle;
    
    private static final String[] ONES = {
        "zéro", "un", "deux", "trois", "quatre", "cinq", "six", "sept", "huit", "neuf",
        "dix", "onze", "douze", "treize", "quatorze", "quinze", "seize", "dix-sept", "dix-huit", "dix-neuf"
    };
    
    private static final String[] TENS = {
        "", "", "vingt", "trente", "quarante", "cinquante", "soixante", "soixante", "quatre-vingt", "quatre-vingt"
    };
    
    private static final String[] ORDINALS = {
        "", "premier", "deuxième", "troisième", "quatrième", "cinquième",
        "sixième", "septième", "huitième", "neuvième", "dixième",
        "onzième", "douzième", "treizième", "quatorzième", "quinzième",
        "seizième", "dix-septième", "dix-huitième", "dix-neuvième", "vingtième"
    };
    
    public FrenchNumberWords(ResourceBundle bundle) {
        this.bundle = bundle;
    }
    
    @Override
    public String format(int value) {
        if (value == 0) {
            return ONES[0];
        }
        if (value < 0) {
            return "moins " + formatPositive(-value);
        }
        return formatPositive(value);
    }
    
    @Override
    public String formatOrdinal(int value) {
        if (value >= 1 && value <= 20) {
            return ORDINALS[value];
        }
        // For larger numbers, add "ième" suffix
        String cardinal = format(value);
        if (cardinal.endsWith("e")) {
            return cardinal.substring(0, cardinal.length() - 1) + "ième";
        }
        return cardinal + "ième";
    }
    
    private String formatPositive(int value) {
        if (value < 20) {
            return ONES[value];
        }
        if (value < 100) {
            return formatTens(value);
        }
        if (value < 1000) {
            int hundreds = value / 100;
            int remainder = value % 100;
            String h = hundreds == 1 ? "cent" : ONES[hundreds] + " cents";
            if (remainder > 0) {
                // Drop "s" from cents when followed by more
                h = hundreds == 1 ? "cent" : ONES[hundreds] + " cent";
                return h + " " + formatPositive(remainder);
            }
            return h;
        }
        if (value < 1000000) {
            int thousands = value / 1000;
            int remainder = value % 1000;
            String t = thousands == 1 ? "mille" : formatPositive(thousands) + " mille";
            if (remainder > 0) {
                return t + " " + formatPositive(remainder);
            }
            return t;
        }
        return Integer.toString(value);
    }
    
    private String formatTens(int value) {
        int tens = value / 10;
        int ones = value % 10;
        
        // Special case for 70-79 and 90-99 (vigesimal)
        if (tens == 7) {
            // 70-79: soixante-dix, soixante et onze, soixante-douze, etc.
            if (ones == 0) {
                return "soixante-dix";
            }
            if (ones == 1) {
                return "soixante et onze";
            }
            return "soixante-" + ONES[10 + ones];
        }
        if (tens == 9) {
            // 90-99: quatre-vingt-dix, quatre-vingt-onze, etc.
            return "quatre-vingt-" + ONES[10 + ones];
        }
        
        // Regular cases
        if (ones == 0) {
            return tens == 8 ? "quatre-vingts" : TENS[tens];
        }
        
        // "et" for 21, 31, 41, 51, 61
        if (ones == 1 && tens >= 2 && tens <= 6) {
            return TENS[tens] + " et un";
        }
        
        // 80+: no "s" on vingt when followed by number
        if (tens == 8) {
            return "quatre-vingt-" + ONES[ones];
        }
        
        return TENS[tens] + "-" + ONES[ones];
    }
}

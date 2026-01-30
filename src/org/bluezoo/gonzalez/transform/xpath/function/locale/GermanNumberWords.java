package org.bluezoo.gonzalez.transform.xpath.function.locale;

import java.util.ResourceBundle;

/**
 * German number word formatter.
 * 
 * <p>German reverses the order of tens and ones and joins them with "und":
 * <ul>
 *   <li>21 = einundzwanzig (one-and-twenty)</li>
 *   <li>45 = fünfundvierzig (five-and-forty)</li>
 * </ul>
 * 
 * <p>Compound numbers are written as single words (no spaces).
 */
public class GermanNumberWords implements NumberWordFormatter {
    
    private final ResourceBundle bundle;
    
    private static final String[] ONES = {
        "null", "eins", "zwei", "drei", "vier", "fünf", "sechs", "sieben", "acht", "neun",
        "zehn", "elf", "zwölf", "dreizehn", "vierzehn", "fünfzehn", "sechzehn", "siebzehn", "achtzehn", "neunzehn"
    };
    
    // Special form used in compounds (ein, not eins)
    private static final String[] ONES_COMPOUND = {
        "null", "ein", "zwei", "drei", "vier", "fünf", "sechs", "sieben", "acht", "neun",
        "zehn", "elf", "zwölf", "dreizehn", "vierzehn", "fünfzehn", "sechzehn", "siebzehn", "achtzehn", "neunzehn"
    };
    
    private static final String[] TENS = {
        "", "", "zwanzig", "dreißig", "vierzig", "fünfzig", "sechzig", "siebzig", "achtzig", "neunzig"
    };
    
    private static final String[] ORDINALS = {
        "", "erste", "zweite", "dritte", "vierte", "fünfte",
        "sechste", "siebte", "achte", "neunte", "zehnte",
        "elfte", "zwölfte", "dreizehnte", "vierzehnte", "fünfzehnte",
        "sechzehnte", "siebzehnte", "achtzehnte", "neunzehnte", "zwanzigste"
    };
    
    public GermanNumberWords(ResourceBundle bundle) {
        this.bundle = bundle;
    }
    
    @Override
    public String format(int value) {
        if (value == 0) {
            return ONES[0];
        }
        if (value < 0) {
            return "minus " + formatPositive(-value);
        }
        return formatPositive(value);
    }
    
    @Override
    public String formatOrdinal(int value) {
        if (value >= 1 && value <= 20) {
            return ORDINALS[value];
        }
        // For larger numbers, add "ste" suffix
        String cardinal = format(value);
        return cardinal + "ste";
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
            String h = ONES_COMPOUND[hundreds] + "hundert";
            if (remainder > 0) {
                return h + formatPositive(remainder);
            }
            return h;
        }
        if (value < 1000000) {
            int thousands = value / 1000;
            int remainder = value % 1000;
            String t;
            if (thousands == 1) {
                t = "eintausend";
            } else {
                t = formatPositive(thousands) + "tausend";
            }
            if (remainder > 0) {
                return t + formatPositive(remainder);
            }
            return t;
        }
        if (value < 1000000000) {
            int millions = value / 1000000;
            int remainder = value % 1000000;
            String m = millions == 1 ? "eine Million" : formatPositive(millions) + " Millionen";
            if (remainder > 0) {
                return m + " " + formatPositive(remainder);
            }
            return m;
        }
        return Integer.toString(value);
    }
    
    private String formatTens(int value) {
        int tens = value / 10;
        int ones = value % 10;
        
        if (ones == 0) {
            return TENS[tens];
        }
        
        // German reverses: einundzwanzig = one-and-twenty
        return ONES_COMPOUND[ones] + "und" + TENS[tens];
    }
}

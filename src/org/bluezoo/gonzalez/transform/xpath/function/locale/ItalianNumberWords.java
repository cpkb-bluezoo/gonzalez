package org.bluezoo.gonzalez.transform.xpath.function.locale;

import java.util.ResourceBundle;

/**
 * Italian number word formatter.
 * 
 * <p>Italian has elision rules:
 * <ul>
 *   <li>21 = ventuno (venti + uno, drop the "i")</li>
 *   <li>28 = ventotto (venti + otto, drop the "i")</li>
 *   <li>31 = trentuno (trenta + uno, drop the "a")</li>
 *   <li>38 = trentotto (trenta + otto, drop the "a")</li>
 * </ul>
 * 
 * <p>Numbers are written as single words without spaces.
 */
public class ItalianNumberWords implements NumberWordFormatter {
    
    private final ResourceBundle bundle;
    
    private static final String[] ONES = {
        "zero", "uno", "due", "tre", "quattro", "cinque", "sei", "sette", "otto", "nove",
        "dieci", "undici", "dodici", "tredici", "quattordici", "quindici",
        "sedici", "diciassette", "diciotto", "diciannove"
    };
    
    private static final String[] TENS = {
        "", "", "venti", "trenta", "quaranta", "cinquanta", "sessanta", "settanta", "ottanta", "novanta"
    };
    
    // Tens stems for elision (drop final vowel before uno/otto)
    private static final String[] TENS_STEM = {
        "", "", "vent", "trent", "quarant", "cinquant", "sessant", "settant", "ottant", "novant"
    };
    
    private static final String[] ORDINALS = {
        "", "primo", "secondo", "terzo", "quarto", "quinto",
        "sesto", "settimo", "ottavo", "nono", "decimo",
        "undicesimo", "dodicesimo", "tredicesimo", "quattordicesimo", "quindicesimo",
        "sedicesimo", "diciassettesimo", "diciottesimo", "diciannovesimo", "ventesimo"
    };
    
    public ItalianNumberWords(ResourceBundle bundle) {
        this.bundle = bundle;
    }
    
    @Override
    public String format(int value) {
        if (value == 0) {
            return ONES[0];
        }
        if (value < 0) {
            return "meno " + formatPositive(-value);
        }
        return formatPositive(value);
    }
    
    @Override
    public String formatOrdinal(int value) {
        if (value >= 1 && value <= 20) {
            return ORDINALS[value];
        }
        // For larger numbers, add "esimo" suffix
        String cardinal = format(value);
        // Drop final vowel if present
        if (cardinal.endsWith("a") || cardinal.endsWith("e") || cardinal.endsWith("i") || cardinal.endsWith("o")) {
            cardinal = cardinal.substring(0, cardinal.length() - 1);
        }
        return cardinal + "esimo";
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
            String h = hundreds == 1 ? "cento" : ONES[hundreds] + "cento";
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
                t = "mille";
            } else {
                t = formatPositive(thousands) + "mila";
            }
            if (remainder > 0) {
                return t + formatPositive(remainder);
            }
            return t;
        }
        if (value < 1000000000) {
            int millions = value / 1000000;
            int remainder = value % 1000000;
            String m = millions == 1 ? "un milione" : formatPositive(millions) + " milioni";
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
        
        // Elision: drop final vowel of tens before uno (1) or otto (8)
        if (ones == 1 || ones == 8) {
            return TENS_STEM[tens] + ONES[ones];
        }
        
        // tre gets an accent in compound: ventitré, trentatré
        if (ones == 3) {
            return TENS[tens] + "tré";
        }
        
        return TENS[tens] + ONES[ones];
    }
}

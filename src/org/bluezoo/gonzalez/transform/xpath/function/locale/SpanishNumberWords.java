package org.bluezoo.gonzalez.transform.xpath.function.locale;

import java.util.ResourceBundle;

/**
 * Spanish number word formatter.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 * 
 * <p>Spanish has several special rules:
 * <ul>
 *   <li>16-19 are contracted: dieciséis, diecisiete, dieciocho, diecinueve</li>
 *   <li>21-29 are contracted: veintiuno, veintidós, veintitrés, etc.</li>
 *   <li>31+ use "y" between tens and ones: treinta y uno</li>
 *   <li>100 is "cien" alone, but "ciento" when followed by more</li>
 * </ul>
 */
public class SpanishNumberWords implements NumberWordFormatter {
    
    private final ResourceBundle bundle;
    
    private static final String[] ONES = {
        "cero", "uno", "dos", "tres", "cuatro", "cinco", "seis", "siete", "ocho", "nueve",
        "diez", "once", "doce", "trece", "catorce", "quince",
        "dieciséis", "diecisiete", "dieciocho", "diecinueve"
    };
    
    private static final String[] TWENTIES = {
        "veinte", "veintiuno", "veintidós", "veintitrés", "veinticuatro",
        "veinticinco", "veintiséis", "veintisiete", "veintiocho", "veintinueve"
    };
    
    private static final String[] TENS = {
        "", "", "veinte", "treinta", "cuarenta", "cincuenta", "sesenta", "setenta", "ochenta", "noventa"
    };
    
    private static final String[] ORDINALS = {
        "", "primero", "segundo", "tercero", "cuarto", "quinto",
        "sexto", "séptimo", "octavo", "noveno", "décimo",
        "undécimo", "duodécimo", "decimotercero", "decimocuarto", "decimoquinto",
        "decimosexto", "decimoséptimo", "decimoctavo", "decimonoveno", "vigésimo"
    };
    
    public SpanishNumberWords(ResourceBundle bundle) {
        this.bundle = bundle;
    }
    
    @Override
    public String format(int value) {
        if (value == 0) {
            return ONES[0];
        }
        if (value < 0) {
            return "menos " + formatPositive(-value);
        }
        return formatPositive(value);
    }
    
    @Override
    public String formatOrdinal(int value) {
        if (value >= 1 && value <= 20) {
            return ORDINALS[value];
        }
        // For larger numbers, Spanish often uses cardinal + "avo" or specialized forms
        // Simplified: just return cardinal form with "º" concept
        return format(value) + "º";
    }
    
    private String formatPositive(int value) {
        if (value < 20) {
            return ONES[value];
        }
        if (value < 30) {
            return TWENTIES[value - 20];
        }
        if (value < 100) {
            int tens = value / 10;
            int ones = value % 10;
            if (ones == 0) {
                return TENS[tens];
            }
            return TENS[tens] + " y " + ONES[ones];
        }
        if (value < 1000) {
            int hundreds = value / 100;
            int remainder = value % 100;
            String h;
            if (hundreds == 1) {
                h = remainder == 0 ? "cien" : "ciento";
            } else if (hundreds == 5) {
                h = "quinientos";
            } else if (hundreds == 7) {
                h = "setecientos";
            } else if (hundreds == 9) {
                h = "novecientos";
            } else {
                h = ONES[hundreds] + "cientos";
            }
            if (remainder > 0) {
                return h + " " + formatPositive(remainder);
            }
            return h;
        }
        if (value < 1000000) {
            int thousands = value / 1000;
            int remainder = value % 1000;
            String t = thousands == 1 ? "mil" : formatPositive(thousands) + " mil";
            if (remainder > 0) {
                return t + " " + formatPositive(remainder);
            }
            return t;
        }
        if (value < 1000000000) {
            int millions = value / 1000000;
            int remainder = value % 1000000;
            String m = millions == 1 ? "un millón" : formatPositive(millions) + " millones";
            if (remainder > 0) {
                return m + " " + formatPositive(remainder);
            }
            return m;
        }
        return Integer.toString(value);
    }
}

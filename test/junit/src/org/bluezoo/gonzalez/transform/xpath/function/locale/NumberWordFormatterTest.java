/*
 * NumberWordFormatterTest.java
 * Copyright (C) 2025 Chris Burdess
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

package org.bluezoo.gonzalez.transform.xpath.function.locale;

import java.util.Locale;
import java.util.ResourceBundle;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link NumberWordFormatter} and locale-specific implementations.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public class NumberWordFormatterTest {

    private static final String BUNDLE_NAME =
        "org.bluezoo.gonzalez.transform.xpath.function.locale.DateTimeMessages";

    private NumberWordFormatter getFormatter(Locale locale) {
        ResourceBundle bundle = ResourceBundle.getBundle(BUNDLE_NAME, locale);
        return NumberWordFormatter.forLocale(locale, bundle);
    }

    // --- English ---

    @Test
    public void testEnglishZero() {
        NumberWordFormatter formatter = getFormatter(Locale.ENGLISH);
        String result = formatter.format(0);
        assertEquals("zero", result);
    }

    @Test
    public void testEnglishOne() {
        NumberWordFormatter formatter = getFormatter(Locale.ENGLISH);
        String result = formatter.format(1);
        assertEquals("one", result);
    }

    @Test
    public void testEnglishThirteen() {
        NumberWordFormatter formatter = getFormatter(Locale.ENGLISH);
        String result = formatter.format(13);
        assertEquals("thirteen", result);
    }

    @Test
    public void testEnglishTwentyOne() {
        NumberWordFormatter formatter = getFormatter(Locale.ENGLISH);
        String result = formatter.format(21);
        assertEquals("twenty one", result);
    }

    @Test
    public void testEnglishOneHundred() {
        NumberWordFormatter formatter = getFormatter(Locale.ENGLISH);
        String result = formatter.format(100);
        assertEquals("one hundred", result);
    }

    @Test
    public void testEnglishOneThousand() {
        NumberWordFormatter formatter = getFormatter(Locale.ENGLISH);
        String result = formatter.format(1000);
        assertEquals("one thousand", result);
    }

    @Test
    public void testEnglishOrdinalFirst() {
        NumberWordFormatter formatter = getFormatter(Locale.ENGLISH);
        String result = formatter.formatOrdinal(1);
        assertEquals("first", result);
    }

    @Test
    public void testEnglishOrdinalSecond() {
        NumberWordFormatter formatter = getFormatter(Locale.ENGLISH);
        String result = formatter.formatOrdinal(2);
        assertEquals("second", result);
    }

    @Test
    public void testEnglishOrdinalThird() {
        NumberWordFormatter formatter = getFormatter(Locale.ENGLISH);
        String result = formatter.formatOrdinal(3);
        assertEquals("third", result);
    }

    @Test
    public void testEnglishOrdinalTwentyFirst() {
        NumberWordFormatter formatter = getFormatter(Locale.ENGLISH);
        String result = formatter.formatOrdinal(21);
        assertEquals("twenty first", result);
    }

    @Test
    public void testEnglishBritishWithAnd() {
        NumberWordFormatter formatter = getFormatter(Locale.UK);
        String result = formatter.format(121);
        assertEquals("one hundred and twenty one", result);
    }

    @Test
    public void testEnglishAmericanWithoutAnd() {
        NumberWordFormatter formatter = getFormatter(Locale.US);
        String result = formatter.format(121);
        assertEquals("one hundred twenty one", result);
    }

    // --- French ---

    @Test
    public void testFrenchZero() {
        NumberWordFormatter formatter = getFormatter(Locale.FRENCH);
        String result = formatter.format(0);
        assertEquals("zéro", result);
    }

    @Test
    public void testFrenchOne() {
        NumberWordFormatter formatter = getFormatter(Locale.FRENCH);
        String result = formatter.format(1);
        assertEquals("un", result);
    }

    @Test
    public void testFrenchTwentyOne() {
        NumberWordFormatter formatter = getFormatter(Locale.FRENCH);
        String result = formatter.format(21);
        assertEquals("vingt et un", result);
    }

    @Test
    public void testFrenchSeventy() {
        NumberWordFormatter formatter = getFormatter(Locale.FRENCH);
        String result = formatter.format(70);
        assertEquals("soixante-dix", result);
    }

    @Test
    public void testFrenchEighty() {
        NumberWordFormatter formatter = getFormatter(Locale.FRENCH);
        String result = formatter.format(80);
        assertEquals("quatre-vingts", result);
    }

    @Test
    public void testFrenchNinety() {
        NumberWordFormatter formatter = getFormatter(Locale.FRENCH);
        String result = formatter.format(90);
        assertEquals("quatre-vingt-dix", result);
    }

    @Test
    public void testFrenchOrdinalFirst() {
        NumberWordFormatter formatter = getFormatter(Locale.FRENCH);
        String result = formatter.formatOrdinal(1);
        assertEquals("premier", result);
    }

    @Test
    public void testFrenchOrdinalSecond() {
        NumberWordFormatter formatter = getFormatter(Locale.FRENCH);
        String result = formatter.formatOrdinal(2);
        assertEquals("deuxième", result);
    }

    // --- German ---

    @Test
    public void testGermanZero() {
        NumberWordFormatter formatter = getFormatter(Locale.GERMAN);
        String result = formatter.format(0);
        assertEquals("null", result);
    }

    @Test
    public void testGermanOne() {
        NumberWordFormatter formatter = getFormatter(Locale.GERMAN);
        String result = formatter.format(1);
        assertEquals("eins", result);
    }

    @Test
    public void testGermanTwentyOne() {
        NumberWordFormatter formatter = getFormatter(Locale.GERMAN);
        String result = formatter.format(21);
        assertEquals("einundzwanzig", result);
    }

    @Test
    public void testGermanFortyFive() {
        NumberWordFormatter formatter = getFormatter(Locale.GERMAN);
        String result = formatter.format(45);
        assertEquals("fünfundvierzig", result);
    }

    @Test
    public void testGermanOneHundred() {
        NumberWordFormatter formatter = getFormatter(Locale.GERMAN);
        String result = formatter.format(100);
        assertEquals("einhundert", result);
    }

    @Test
    public void testGermanOrdinalFirst() {
        NumberWordFormatter formatter = getFormatter(Locale.GERMAN);
        String result = formatter.formatOrdinal(1);
        assertEquals("erste", result);
    }

    @Test
    public void testGermanOrdinalSecond() {
        NumberWordFormatter formatter = getFormatter(Locale.GERMAN);
        String result = formatter.formatOrdinal(2);
        assertEquals("zweite", result);
    }

    // --- Edge cases ---

    @Test
    public void testNegativeNumberEnglish() {
        NumberWordFormatter formatter = getFormatter(Locale.ENGLISH);
        String result = formatter.format(-5);
        assertEquals("minus five", result);
    }

    @Test
    public void testNegativeNumberFrench() {
        NumberWordFormatter formatter = getFormatter(Locale.FRENCH);
        String result = formatter.format(-10);
        assertEquals("moins dix", result);
    }

    @Test
    public void testNegativeNumberGerman() {
        NumberWordFormatter formatter = getFormatter(Locale.GERMAN);
        String result = formatter.format(-21);
        assertEquals("minus einundzwanzig", result);
    }

    @Test
    public void testVeryLargeNumberEnglish() {
        NumberWordFormatter formatter = getFormatter(Locale.ENGLISH);
        int value = 2000000000;
        String result = formatter.format(value);
        assertEquals(Integer.toString(value), result);
    }

    @Test
    public void testZeroOrdinal() {
        NumberWordFormatter formatter = getFormatter(Locale.ENGLISH);
        String result = formatter.formatOrdinal(0);
        assertEquals("zeroth", result);
    }

    @Test
    public void testOneMillionEnglish() {
        NumberWordFormatter formatter = getFormatter(Locale.ENGLISH);
        String result = formatter.format(1000000);
        assertEquals("one million", result);
    }
}

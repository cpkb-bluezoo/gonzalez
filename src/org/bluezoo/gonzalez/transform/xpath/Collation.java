/*
 * Gonzalez
 *
 * Copyright (c) 2025 Chris Burdess <dog@gnu.org>
 *
 * Gonzalez is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * Gonzalez is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Gonzalez.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.bluezoo.gonzalez.transform.xpath;

import java.text.Collator;
import java.util.Comparator;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.bluezoo.gonzalez.transform.xpath.expr.XPathException;

/**
 * Collation support for XSLT/XPath string comparison.
 *
 * <p>Supports W3C-defined collation URIs:
 * <ul>
 *   <li>{@code http://www.w3.org/2005/xpath-functions/collation/codepoint} - Unicode codepoint (default)</li>
 *   <li>{@code http://www.w3.org/2005/xpath-functions/collation/html-ascii-case-insensitive} - ASCII case-insensitive</li>
 *   <li>{@code http://www.w3.org/2013/collation/UCA?...} - Unicode Collation Algorithm with parameters</li>
 * </ul>
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public class Collation {

    /** Default collation URI - Unicode codepoint. */
    public static final String CODEPOINT_URI = 
        "http://www.w3.org/2005/xpath-functions/collation/codepoint";

    /** HTML ASCII case-insensitive collation URI. */
    public static final String HTML_ASCII_CASE_INSENSITIVE_URI = 
        "http://www.w3.org/2005/xpath-functions/collation/html-ascii-case-insensitive";

    /** UCA (Unicode Collation Algorithm) base URI. */
    public static final String UCA_BASE_URI = 
        "http://www.w3.org/2013/collation/UCA";

    /** Cache for collation instances. */
    private static final Map<String, Collation> cache = new ConcurrentHashMap<>();

    /** The comparator for this collation. */
    private final Comparator<String> comparator;

    /** The collation URI. */
    private final String uri;

    /** Private constructor. */
    private Collation(String uri, Comparator<String> comparator) {
        this.uri = uri;
        this.comparator = comparator;
    }

    /**
     * Returns whether the given URI is a recognized collation URI.
     *
     * @param uri the collation URI
     * @return true if recognized
     */
    public static boolean isRecognized(String uri) {
        if (uri == null || uri.isEmpty() || CODEPOINT_URI.equals(uri)) {
            return true;
        }
        if (HTML_ASCII_CASE_INSENSITIVE_URI.equals(uri)) {
            return true;
        }
        if (uri.startsWith(UCA_BASE_URI)) {
            return true;
        }
        return false;
    }
    
    /**
     * Gets a collation for the given URI.
     *
     * @param uri the collation URI, or null for default
     * @return the collation
     * @throws XPathException if the collation URI is not supported
     */
    public static Collation forUri(String uri) throws XPathException {
        if (uri == null || uri.isEmpty() || CODEPOINT_URI.equals(uri)) {
            return getCodepointCollation();
        }

        // Check cache first
        Collation cached = cache.get(uri);
        if (cached != null) {
            return cached;
        }

        // Parse and create collation
        Collation collation = createCollation(uri);
        cache.put(uri, collation);
        return collation;
    }

    /**
     * Gets the default (codepoint) collation.
     */
    public static Collation getCodepointCollation() {
        return CodepointCollation.INSTANCE;
    }

    /**
     * Creates a collation from a URI.
     */
    private static Collation createCollation(String uri) throws XPathException {
        // HTML ASCII case-insensitive
        if (HTML_ASCII_CASE_INSENSITIVE_URI.equals(uri)) {
            return new Collation(uri, HtmlAsciiCaseInsensitiveComparator.INSTANCE);
        }

        // UCA collation with parameters
        if (uri.startsWith(UCA_BASE_URI)) {
            return createUcaCollation(uri);
        }

        // Try to interpret as a locale-based collation (e.g., http://example.com/collation/en)
        // For now, fall back to codepoint for unknown URIs (lenient behavior)
        // Some processors throw FOCH0002 for unsupported collations, but we'll be lenient
        return new Collation(uri, String::compareTo);
    }

    /**
     * Creates a UCA collation from a URI with parameters.
     * 
     * <p>Parameters supported:
     * <ul>
     *   <li>{@code strength=primary|secondary|tertiary|quaternary|identical}</li>
     *   <li>{@code lang=xx} - language code</li>
     *   <li>{@code fallback=yes|no} - whether to fall back on unknown parameters</li>
     * </ul>
     */
    private static Collation createUcaCollation(String uri) throws XPathException {
        // Parse parameters from URI
        String params = uri.length() > UCA_BASE_URI.length() 
            ? uri.substring(UCA_BASE_URI.length()) : "";
        
        int strength = Collator.TERTIARY; // default
        Locale locale = Locale.ROOT;
        boolean fallback = true;

        if (params.startsWith("?")) {
            params = params.substring(1);
            String[] pairs = params.split(";|&");
            for (String pair : pairs) {
                int eq = pair.indexOf('=');
                if (eq > 0) {
                    String key = pair.substring(0, eq).trim().toLowerCase();
                    String value = pair.substring(eq + 1).trim().toLowerCase();
                    
                    switch (key) {
                        case "strength":
                            switch (value) {
                                case "primary":
                                case "1":
                                    strength = Collator.PRIMARY;
                                    break;
                                case "secondary":
                                case "2":
                                    strength = Collator.SECONDARY;
                                    break;
                                case "tertiary":
                                case "3":
                                    strength = Collator.TERTIARY;
                                    break;
                                case "quaternary":
                                case "4":
                                    // Java doesn't have QUATERNARY, use IDENTICAL
                                    strength = Collator.IDENTICAL;
                                    break;
                                case "identical":
                                case "5":
                                    strength = Collator.IDENTICAL;
                                    break;
                            }
                            break;
                        case "lang":
                            locale = Locale.forLanguageTag(value);
                            break;
                        case "fallback":
                            fallback = "yes".equals(value);
                            break;
                    }
                }
            }
        }

        // Create Java Collator
        final Collator collator = Collator.getInstance(locale);
        collator.setStrength(strength);

        return new Collation(uri, new CollatorComparator(collator));
    }

    /**
     * Compares two strings using this collation.
     *
     * @param a the first string
     * @param b the second string
     * @return negative if a &lt; b, zero if a = b, positive if a &gt; b
     */
    public int compare(String a, String b) {
        if (a == null && b == null) {
            return 0;
        }
        if (a == null) {
            return -1;
        }
        if (b == null) {
            return 1;
        }
        return comparator.compare(a, b);
    }

    /**
     * Tests if two strings are equal using this collation.
     *
     * @param a the first string
     * @param b the second string
     * @return true if equal according to this collation
     */
    public boolean equals(String a, String b) {
        return compare(a, b) == 0;
    }

    /**
     * Tests if a string starts with a given prefix according to this collation.
     *
     * @param str the string to test
     * @param prefix the prefix
     * @return true if str starts with prefix
     */
    public boolean startsWith(String str, String prefix) {
        if (prefix.isEmpty()) return true;
        if (str.length() < prefix.length()) return false;
        return compare(str.substring(0, prefix.length()), prefix) == 0;
    }

    /**
     * Tests if a string ends with a given suffix according to this collation.
     *
     * @param str the string to test
     * @param suffix the suffix
     * @return true if str ends with suffix
     */
    public boolean endsWith(String str, String suffix) {
        if (suffix.isEmpty()) return true;
        if (str.length() < suffix.length()) return false;
        return compare(str.substring(str.length() - suffix.length()), suffix) == 0;
    }

    /**
     * Tests if a string contains a given substring according to this collation.
     *
     * @param str the string to search in
     * @param substr the substring to search for
     * @return true if str contains substr
     */
    public boolean contains(String str, String substr) {
        if (substr.isEmpty()) return true;
        if (str.length() < substr.length()) return false;
        
        // Simple sliding window search
        for (int i = 0; i <= str.length() - substr.length(); i++) {
            if (compare(str.substring(i, i + substr.length()), substr) == 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Gets the comparator for this collation.
     */
    public Comparator<String> getComparator() {
        return comparator;
    }

    /**
     * Gets the URI for this collation.
     */
    public String getUri() {
        return uri;
    }

    @Override
    public String toString() {
        return uri;
    }

    // ========================================================================
    // Singleton collation instances
    // ========================================================================

    /** Codepoint collation (Unicode codepoint order). */
    private static final class CodepointCollation extends Collation {
        static final Collation INSTANCE = new CodepointCollation();
        
        private CodepointCollation() {
            super(CODEPOINT_URI, String::compareTo);
        }
    }

    /** Comparator that wraps a java.text.Collator. */
    private static final class CollatorComparator implements Comparator<String> {
        private final Collator collator;
        
        CollatorComparator(Collator collator) {
            this.collator = collator;
        }
        
        @Override
        public int compare(String a, String b) {
            return collator.compare(a, b);
        }
    }

    /** HTML ASCII case-insensitive comparator. */
    private static final class HtmlAsciiCaseInsensitiveComparator 
            implements Comparator<String> {
        static final HtmlAsciiCaseInsensitiveComparator INSTANCE = 
            new HtmlAsciiCaseInsensitiveComparator();

        @Override
        public int compare(String a, String b) {
            // Compare case-insensitively for ASCII letters only
            int len = Math.min(a.length(), b.length());
            for (int i = 0; i < len; i++) {
                char ca = a.charAt(i);
                char cb = b.charAt(i);
                
                // Convert ASCII letters to lowercase for comparison
                if (ca >= 'A' && ca <= 'Z') ca = (char) (ca + 32);
                if (cb >= 'A' && cb <= 'Z') cb = (char) (cb + 32);
                
                if (ca != cb) {
                    return ca - cb;
                }
            }
            // If all compared chars equal, shorter string comes first
            return a.length() - b.length();
        }
    }
}

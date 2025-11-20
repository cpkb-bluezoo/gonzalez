/*
 * CharClass.java
 * Copyright (C) 2025 Chris Burdess
 *
 * This file is part of Gonzalez, a streaming XML parser.
 * For more information please visit https://www.nongnu.org/gonzalez/
 *
 * Gonzalez is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Gonzalez is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Gonzalez.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.bluezoo.gonzalez;

/**
 * Character classification for XML tokenization.
 * <p>
 * CharClass provides a reduced character space for the tokenizer state machine,
 * mapping ~1.1M Unicode codepoints into ~25 meaningful classes. This enables
 * efficient state transitions based on character type rather than individual
 * character values.
 * <p>
 * Classification is context-aware: some characters (like ':') have different
 * meanings depending on the current State.
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
enum CharClass {
    
    /** '&lt;' - Less-than sign, starts tags and directives */
    LT,
    
    /** '&gt;' - Greater-than sign, ends tags */
    GT,
    
    /** '&amp;' - Ampersand, starts entity references */
    AMP,
    
    /** '\'' - Apostrophe, delimiter for attribute values and quoted strings */
    APOS,
    
    /** '"' - Quotation mark, delimiter for attribute values and quoted strings */
    QUOT,
    
    /** '!' - Exclamation mark, used in comments, CDATA, DOCTYPE, DTD declarations */
    BANG,
    
    /** '?' - Question mark, used in processing instructions and XML declaration */
    QUERY,
    
    /** '/' - Slash, used in end tags and empty element tags */
    SLASH,
    
    /** '=' - Equals sign, separates attribute names from values */
    EQ,
    
    /** ';' - Semicolon, terminates entity references */
    SEMICOLON,
    
    /** '%' - Percent sign, starts parameter entity references in DTD */
    PERCENT,
    
    /** '#' - Hash/pound sign, used in character references and DTD keywords */
    HASH,
    
    /** ':' - Colon, namespace separator (context-dependent) */
    COLON,
    
    /** 'a' - Letter a, used in predefined entity names (amp, apos) */
    LETTER_A,
    
    /** 'l' - Letter l, used in predefined entity name (lt) */
    LETTER_L,
    
    /** 'g' - Letter g, used in predefined entity name (gt) */
    LETTER_G,
    
    /** 'm' - Letter m, used in predefined entity name (amp) */
    LETTER_M,
    
    /** 'p' - Letter p, used in predefined entity names (amp, apos) */
    LETTER_P,
    
    /** 'o' - Letter o, used in predefined entity names (apos, quot) */
    LETTER_O,
    
    /** 's' - Letter s, used in predefined entity name (apos) */
    LETTER_S,
    
    /** 't' - Letter t, used in predefined entity names (lt, gt, quot) */
    LETTER_T,
    
    /** 'q' - Letter q, used in predefined entity name (quot) */
    LETTER_Q,
    
    /** 'u' - Letter u, used in predefined entity name (quot) */
    LETTER_U,
    
    /** 'x' - Letter x, used in hexadecimal character references (&#x...) */
    LETTER_X,
    
    /** '[' - Open bracket, used in CDATA sections and conditional sections */
    OPEN_BRACKET,
    
    /** ']' - Close bracket, ends CDATA sections and conditional sections */
    CLOSE_BRACKET,
    
    /** '(' - Open parenthesis, used in DTD content models */
    OPEN_PAREN,
    
    /** ')' - Close parenthesis, used in DTD content models */
    CLOSE_PAREN,
    
    /** '-' - Dash/hyphen, used in comments */
    DASH,
    
    /** '|' - Pipe, used in DTD content models (choice operator) */
    PIPE,
    
    /** ',' - Comma, used in DTD content models (sequence operator) */
    COMMA,
    
    /** '*' - Asterisk, occurrence indicator in DTD content models */
    STAR,
    
    /** '+' - Plus sign, occurrence indicator in DTD content models */
    PLUS,
    
    /** Whitespace: space, tab, line feed, carriage return */
    WHITESPACE,
    
    /** XML NameStartChar: letter, underscore, colon (except in namespace contexts) */
    NAME_START_CHAR,
    
    /** XML NameChar (but not NameStartChar): digit, dot, hyphen, etc. */
    NAME_CHAR,
    
    /** Decimal digit [0-9] */
    DIGIT,
    
    /** Hexadecimal digit [0-9A-Fa-f] */
    HEX_DIGIT,
    
    /** Legal XML character data not covered by above categories */
    CHAR_DATA,
    
    /** Illegal XML character (outside allowed Unicode ranges) */
    ILLEGAL;
    
    /**
     * Pre-computed character class lookup table for ASCII characters (0-127).
     * Provides O(1) classification for common characters.
     */
    private static final CharClass[] ASCII_LOOKUP = new CharClass[128];
    
    static {
        // Initialize all as ILLEGAL first
        for (int i = 0; i < 128; i++) {
            ASCII_LOOKUP[i] = ILLEGAL;
        }
        
        // Single-character mappings
        ASCII_LOOKUP['<'] = LT;
        ASCII_LOOKUP['>'] = GT;
        ASCII_LOOKUP['&'] = AMP;
        ASCII_LOOKUP['\''] = APOS;
        ASCII_LOOKUP['"'] = QUOT;
        ASCII_LOOKUP['!'] = BANG;
        ASCII_LOOKUP['?'] = QUERY;
        ASCII_LOOKUP['/'] = SLASH;
        ASCII_LOOKUP['='] = EQ;
        ASCII_LOOKUP[';'] = SEMICOLON;
        ASCII_LOOKUP['%'] = PERCENT;
        ASCII_LOOKUP['#'] = HASH;
        ASCII_LOOKUP[':'] = COLON;
        ASCII_LOOKUP['['] = OPEN_BRACKET;
        ASCII_LOOKUP[']'] = CLOSE_BRACKET;
        ASCII_LOOKUP['('] = OPEN_PAREN;
        ASCII_LOOKUP[')'] = CLOSE_PAREN;
        ASCII_LOOKUP['-'] = DASH;
        ASCII_LOOKUP['|'] = PIPE;
        ASCII_LOOKUP[','] = COMMA;
        ASCII_LOOKUP['*'] = STAR;
        ASCII_LOOKUP['+'] = PLUS;
        
        // Whitespace
        ASCII_LOOKUP[' '] = WHITESPACE;
        ASCII_LOOKUP['\t'] = WHITESPACE;
        ASCII_LOOKUP['\n'] = WHITESPACE;
        ASCII_LOOKUP['\r'] = WHITESPACE;
        
        // Digits (both DIGIT and HEX_DIGIT)
        for (char c = '0'; c <= '9'; c++) {
            ASCII_LOOKUP[c] = DIGIT;
        }
        
        // Hex digits A-F (already covered by NAME_START_CHAR for letters)
        // We'll handle this in classify() method
        
        // NAME_START_CHAR: A-Z, a-z, underscore
        for (char c = 'A'; c <= 'Z'; c++) {
            ASCII_LOOKUP[c] = NAME_START_CHAR;
        }
        for (char c = 'a'; c <= 'z'; c++) {
            ASCII_LOOKUP[c] = NAME_START_CHAR;
        }
        ASCII_LOOKUP['_'] = NAME_START_CHAR;
        
        // Period is NAME_CHAR but not NAME_START_CHAR
        ASCII_LOOKUP['.'] = NAME_CHAR;
        
        // Other printable ASCII as CHAR_DATA
        for (int i = 32; i < 127; i++) {
            if (ASCII_LOOKUP[i] == ILLEGAL) {
                ASCII_LOOKUP[i] = CHAR_DATA;
            }
        }
    }
    
    /**
     * Classifies a character into a CharClass based on the current State and MiniState.
     * <p>
     * Classification is context-aware: some characters have different meanings
     * in different states. For example, specific letters in predefined entity reference
     * contexts are classified specially to enable trie-based recognition.
     * 
     * @param c the character to classify
     * @param state the current tokenizer state
     * @param miniState the current tokenizer mini-state
     * @return the CharClass for this character in this context
     */
    static CharClass classify(char c, TokenizerState state, MiniState miniState) {
        // Fast path for ASCII
        if (c < 128) {
            CharClass base = ASCII_LOOKUP[c];
            
            // Handle hex digits (A-F, a-f) in character reference context
            if (base == NAME_START_CHAR && miniState == MiniState.ACCUMULATING_CHAR_REF_HEX) {
                if ((c >= 'A' && c <= 'F') || (c >= 'a' && c <= 'f')) {
                    return HEX_DIGIT;
                }
            }
            
            // Context-specific adjustments for special letter recognition
            // Used for detecting <?xml vs <?pi at document start
            if (base == NAME_START_CHAR && state == TokenizerState.BOM_READ) {
                if (miniState == MiniState.SEEN_LT_QUERY && (c == 'x' || c == 'X')) {
                    return LETTER_X;
                }
                if (miniState == MiniState.SEEN_LT_QUERY_X && (c == 'm' || c == 'M')) {
                    return LETTER_M;
                }
                if (miniState == MiniState.SEEN_LT_QUERY_XM && (c == 'l' || c == 'L')) {
                    return LETTER_L;
                }
            }
            
            if (base == COLON) {
                // Per XML 1.0 § 2.3, ':' is a NameStartChar
                // It's used for namespaces but is valid in any name context
                return NAME_START_CHAR;
            }
            
            return base;
        }
        
        // Unicode path (slower)
        return classifyUnicode(c);
    }
    
    /**
     * Returns true if the mini-state is in a context where we're recognizing predefined entities.
     */
    private static boolean isPredefinedEntityContext(MiniState miniState) {
        switch (miniState) {
            case SEEN_AMP:
            case SEEN_AMP_HASH:  // For 'x' in &#x...
            case SEEN_AMP_L:
            case SEEN_AMP_G:
            case SEEN_AMP_A:
            case SEEN_AMP_A_M:
            case SEEN_AMP_A_P:
            case SEEN_AMP_A_P_O:
            case SEEN_AMP_Q:
            case SEEN_AMP_Q_U:
            case SEEN_AMP_Q_U_O:
                return true;
            default:
                return false;
        }
    }
    
    /**
     * Returns true if the mini-state is accumulating character reference digits.
     */
    private static boolean isCharRefDigitContext(MiniState miniState) {
        return miniState == MiniState.ACCUMULATING_CHAR_REF_HEX;
    }
    
    /**
     * Classifies a Unicode character (code point >= 128).
     * 
     * @param c the character to classify
     * @return the CharClass for this character
     */
    private static CharClass classifyUnicode(char c) {
        // Check if legal XML character first
        if (!isLegalXMLChar(c)) {
            return ILLEGAL;
        }
        
        // Check if NameStartChar
        if (isNameStartChar(c)) {
            return NAME_START_CHAR;
        }
        
        // Check if NameChar (but not NameStartChar)
        if (isNameChar(c)) {
            return NAME_CHAR;
        }
        
        // Otherwise it's just character data
        return CHAR_DATA;
    }
    
    /**
     * Checks if a character is a legal XML character.
     * Legal chars: #x9 | #xA | #xD | [#x20-#xD7FF] | [#xE000-#xFFFD] | [#x10000-#x10FFFF]
     */
    private static boolean isLegalXMLChar(char c) {
        return (c == 0x9 || c == 0xA || c == 0xD ||
                (c >= 0x20 && c <= 0xD7FF) ||
                (c >= 0xE000 && c <= 0xFFFD));
    }
    
    /**
     * Checks if a character is an XML NameStartChar.
     * Excludes CombiningChar ranges which are only valid in NameChar.
     */
    private static boolean isNameStartChar(char c) {
        if (c == ':' || c == '_' ||
            (c >= 'A' && c <= 'Z') ||
            (c >= 'a' && c <= 'z')) {
            return true;
        }
        if (c < 0xC0) {
            return false;
        }
        return (c >= 0xC0 && c <= 0xD6) ||
               (c >= 0xD8 && c <= 0xF6) ||
               (c >= 0xF8 && c <= 0x2FF) ||
               (c >= 0x370 && c <= 0x37D) ||
               (c >= 0x37F && c <= 0x1FFF) ||
               (c >= 0x200C && c <= 0x200D) ||
               (c >= 0x2070 && c <= 0x218F) ||
               (c >= 0x2C00 && c <= 0x2FEF) ||
               (c >= 0x3001 && c <= 0x3098) ||  // Exclude 0x3099-0x309A (CombiningChar)
               (c >= 0x309B && c <= 0xD7FF) ||
               (c >= 0xF900 && c <= 0xFDCF) ||
               (c >= 0xFDF0 && c <= 0xFFFD);
    }
    
    /**
     * Checks if a character is an XML NameChar (but not necessarily NameStartChar).
     */
    private static boolean isNameChar(char c) {
        if (isNameStartChar(c)) {
            return true;
        }
        if (c == '-' || c == '.' || c == 0xB7) {
            return true;
        }
        return (c >= '0' && c <= '9') ||
               (c >= 0x0300 && c <= 0x036F) ||
               (c >= 0x203F && c <= 0x2040);
    }
}


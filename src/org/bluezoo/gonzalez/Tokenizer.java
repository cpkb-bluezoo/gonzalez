/*
 * Tokenizer.java
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

import java.nio.CharBuffer;
import java.util.Map;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

/**
 * Handles tokenization for buffers of XML characters using a state trie architecture.
 * <p>
 * This tokenizer uses a deterministic state machine with no backtracking. Character
 * classification reduces the Unicode space to ~25 character classes, and state
 * transitions are looked up in a pre-built trie structure.
 * <p>
 * The tokenizer maintains two levels of state:
 * <ul>
 * <li><b>State</b> - High-level parsing context (what we're parsing: content, attributes, etc.)</li>
 * <li><b>MiniState</b> - Fine-grained token recognition progress (where we are in recognizing a token)</li>
 * </ul>
 * <p>
 * When the input buffer is exhausted mid-token, the tokenizer preserves its State
 * and resets MiniState to READY. On the next receive() call, unconsumed characters
 * are prepended and reprocessed from the beginning, naturally reconstructing the
 * token recognition process.
 * <p>
 * Location information (line/column) is provided by the {@link ExternalEntityDecoder}
 * which acts as the Locator2 for external entities.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class Tokenizer {

    private static boolean debug = false;

    // ===== Configuration and Document Metadata =====
    
    /**
     * Reference to the ExternalEntityDecoder (for charset switching when XML declaration is parsed).
     * Null for internal entities.
     */
    private ExternalEntityDecoder externalEntityDecoder;

    // ===== State Machine =====
    
    /**
     * Current high-level parsing state.
     */
    private TokenizerState state = TokenizerState.INIT;
    
    /**
     * State to return to after exiting COMMENT or PI_TARGET/PI_DATA states.
     * Used to properly nest comments/PIs within DOCTYPE, DOCTYPE_INTERNAL, etc.
     */
    private TokenizerState returnState = null;
    
    /**
     * Current fine-grained token recognition state.
     */
    private MiniState miniState = MiniState.READY;
    
    /**
     * Transition table mapping (State, MiniState, CharClass) to Transition.
     * This is a reference to the pre-built static table in MiniStateTransitionBuilder.
     */
    private final Map<TokenizerState, Map<MiniState, Map<CharClass, MiniStateTransitionBuilder.Transition>>> transitionTable;
    
    // ===== XML Declaration Parsing State =====
    
    /**
     * Current attribute name being parsed in XML declaration.
     */
    private String xmlDeclAttributeName = null;
    
    /**
     * Bit flags for seen attributes: 1=version, 2=encoding, 4=standalone
     */
    private short xmlDeclSeenAttributes = 0;
    
    /**
     * Encoding value from XML declaration (to be applied after parsing complete).
     */
    private String xmlDeclEncoding = null;
    
    /**
     * Current quote character in XML declaration attribute value (' or ").
     */
    private char xmlDeclQuoteChar = '\0';
    
    /**
     * Flag indicating whitespace was seen (required between attributes in XML declaration).
     */
    private boolean xmlDeclSeenWhitespace = false;
    
    // ===== Buffers =====

    /**
     * Character underflow buffer for incomplete tokens.
     * Stored between receive() calls and prepended to incoming characters.
     */
    private CharBuffer charUnderflow;

    /**
     * Main character processing buffer.
     * Holds characters ready for tokenization.
     */
    private CharBuffer charBuffer;
    
    // ===== Position Tracking =====
    
    /**
     * Buffer position at the start of the current token being accumulated.
     */
    private int tokenStartPos;
    
    // ===== Token Consumer =====
    
    /**
     * The consumer that receives emitted tokens.
     */
    private final TokenConsumer consumer;
    
    /**
     * The locator providing position information.
     * Set by ExternalEntityDecoder when decoding external entities.
     * For internal entities, may be null or point to parent locator.
     */
    private Locator locator;
    
    /**
     * Whether the tokenizer has been closed.
     */
    private boolean closed;
    
    /**
     * Whether XML 1.1 character rules are enabled.
     * If true, allows extended character ranges per XML 1.1 specification.
     * If false (default), uses XML 1.0 character rules.
     */
    private boolean xml11 = false;
    
    
    // ===== Predefined Entity Replacement =====
    
    private static final int PREDEFINED_AMP = 0;
    private static final int PREDEFINED_LT = 1;
    private static final int PREDEFINED_GT = 2;
    private static final int PREDEFINED_APOS = 3;
    private static final int PREDEFINED_QUOT = 4;
    
    /**
     * Pre-expanded text for predefined entity references.
     * Indexed by entity name: amp=0, lt=1, gt=2, apos=3, quot=4
     */
    private static final CharBuffer PREDEFINED_ENTITY_TEXT = CharBuffer.wrap("&<>'\"").asReadOnlyBuffer();
    
    // ===== Constructors =====

    /**
     * Constructs a new Tokenizer.
     * @param consumer the TokenConsumer that will receive tokens
     */
    public Tokenizer(TokenConsumer consumer) {
        this.consumer = consumer;
        this.transitionTable = MiniStateTransitionBuilder.TRANSITION_TABLE;
        // Note: setLocator will be called by ExternalEntityDecoder or during initialization
    }

    // ===== Public API =====
    
    /**
     * Sets the locator for position tracking.
     * Called by ExternalEntityDecoder to provide accurate line/column information.
     * The locator is also passed to the token consumer.
     * 
     * @param locator the locator providing position information
     */
    public void setLocator(Locator locator) {
        this.locator = locator;
        if (locator != null) {
            consumer.setLocator(locator);
        }
    }
    
    /**
     * Sets the ExternalEntityDecoder reference.
     * This is needed so the tokenizer can call setCharset() on the decoder
     * when it parses the XML declaration's encoding attribute.
     * 
     * @param decoder the external entity decoder, or null for internal entities
     */
    public void setExternalEntityDecoder(ExternalEntityDecoder decoder) {
        this.externalEntityDecoder = decoder;
    }
    
    /**
     * Package-private method to set initial context for entity expansion.
     * Used when creating a tokenizer for inline entity expansion.
     * 
     * @param initialState the initial state (typically TokenizerState.CONTENT or TokenizerState.DOCTYPE_INTERNAL)
     */
    void setInitialContext(TokenizerState initialState) {
        this.state = initialState;
    }
    
    /**
     * Receives and processes a buffer of characters (for internal entity expansion).
     * This method is used when the character data has already been decoded.
     * 
     * @param data the character buffer to process
     * @throws SAXException if a parsing error occurs
     */
    public void receive(CharBuffer data) throws SAXException {
        if (closed) {
            throw new IllegalStateException("Tokenizer is closed");
        }
        
        // Initialize state on first receive
        if (state == TokenizerState.INIT) {
            state = TokenizerState.BOM_READ;
        }
        
        // For internal entity expansion, we skip BOM detection and decoding
        // Initialize buffers if needed
        if (charBuffer == null) {
            charBuffer = CharBuffer.allocate(4096);
            charUnderflow = CharBuffer.allocate(4096);
            charUnderflow.limit(0);
        }
        
        // Ensure charBuffer is large enough
        int neededCapacity = (charUnderflow != null ? charUnderflow.remaining() : 0) + data.remaining();
        if (charBuffer.capacity() < neededCapacity) {
            int newCapacity = Math.max(neededCapacity, 4096);
            CharBuffer newBuffer = CharBuffer.allocate(newCapacity);
            if (charBuffer.hasRemaining()) {
                charBuffer.flip();
                newBuffer.put(charBuffer);
            }
            charBuffer = newBuffer;
        }
        
        // Clear and prepend character underflow
        charBuffer.clear();
        if (charUnderflow != null && charUnderflow.hasRemaining()) {
            charBuffer.put(charUnderflow);
            charUnderflow.clear();
            charUnderflow.limit(0);
        }
        
        // Copy incoming data
        charBuffer.put(data);
        charBuffer.flip();
        
        // Tokenize (character data is already normalized by the entity value parser)
        tokenize();
        
        // Save character underflow
        if (charBuffer.hasRemaining()) {
            if (charUnderflow == null || charUnderflow.capacity() < charBuffer.remaining()) {
                charUnderflow = CharBuffer.allocate(Math.max(charBuffer.remaining() * 2, 4096));
            } else {
                charUnderflow.clear();
            }
            charUnderflow.put(charBuffer);
            charUnderflow.flip();
        }
    }
    
    /**
     * Closes the tokenizer and flushes any remaining tokens.
     * 
     * @throws SAXException if a parsing error occurs
     */
    public void close() throws SAXException {
        if (closed) {
            return;
        }
        closed = true;
        
        // Flush any greedy accumulation state
        if (miniState.isGreedyAccumulation() && charBuffer != null && tokenStartPos < charBuffer.position()) {
            int tokenLength = charBuffer.position() - tokenStartPos;
            emitTokenWindow(miniState.getTokenType(), tokenStartPos, tokenLength);
        }
    }
    
    /**
     * Resets the tokenizer to initial state.
     * 
     * @throws SAXException if an error occurs
     */
    public void reset() throws SAXException {
        state = TokenizerState.INIT;
        miniState = MiniState.READY;
        charUnderflow = null;
        charBuffer = null;
        closed = false;
        xmlDeclAttributeName = null;
        xmlDeclSeenAttributes = 0;
        xmlDeclEncoding = null;
        xmlDeclQuoteChar = '\0';
        returnState = null;
    }

    // ===== Additional Setter Methods =====
    
    /**
     * Sets whether XML 1.1 character rules should be used.
     * @param xml11 true to enable XML 1.1 rules, false for XML 1.0 rules
     */
    public void setXML11(boolean xml11) {
        this.xml11 = xml11;
    }
    
    /**
     * Returns whether XML 1.1 character rules are enabled.
     * @return true if XML 1.1 rules are enabled, false if XML 1.0 rules are used
     */
    public boolean isXML11() {
        return xml11;
    }
    

    /**
     * Checks if an entity name is a predefined entity and returns its index into PREDEFINED_ENTITY_TEXT.
     * Returns -1 if not a predefined entity.
     * 
     * @param nameStart start of entity name in charBuffer
     * @param nameEnd end of entity name in charBuffer
     * @return index into PREDEFINED_ENTITY_TEXT (0=&amp;, 1=&lt;, 2=>, 3=', 4=") or -1 if not predefined
     */
    private int getPredefinedEntityIndex(int nameStart, int nameEnd) {
        int nameLen = nameEnd - nameStart;
        if (nameLen == 2) {
            char c1 = charBuffer.get(nameStart);
            char c2 = charBuffer.get(nameStart + 1);
            if (c1 == 'l' && c2 == 't') {
                return PREDEFINED_LT;  // &lt; -> '<'
            } else if (c1 == 'g' && c2 == 't') {
                return PREDEFINED_GT;  // &gt; -> '>'
            }
        } else if (nameLen == 3) {
            char c1 = charBuffer.get(nameStart);
            char c2 = charBuffer.get(nameStart + 1);
            char c3 = charBuffer.get(nameStart + 2);
            if (c1 == 'a' && c2 == 'm' && c3 == 'p') {
                return PREDEFINED_AMP;  // &amp; -> '&'
            }
        } else if (nameLen == 4) {
            char c1 = charBuffer.get(nameStart);
            char c2 = charBuffer.get(nameStart + 1);
            char c3 = charBuffer.get(nameStart + 2);
            char c4 = charBuffer.get(nameStart + 3);
            if (c1 == 'a' && c2 == 'p' && c3 == 'o' && c4 == 's') {
                return PREDEFINED_APOS;  // &apos; -> '\''
            } else if (c1 == 'q' && c2 == 'u' && c3 == 'o' && c4 == 't') {
                return PREDEFINED_QUOT;  // &quot; -> '"'
            }
        }
        return -1;
    }
    
    /**
     * Checks if a NAME token in DOCTYPE context is actually a keyword.
     * Returns the keyword token type or null if it's just a name.
     * 
     * @param start start position of name in charBuffer
     * @param length length of name
     * @return Token.SYSTEM, Token.PUBLIC, or null if not a keyword
     */
    private Token checkDOCTYPEKeyword(int start, int length) {
        if (length == 6) {
            // Check for "SYSTEM"
            if (charBuffer.get(start) == 'S' &&
                charBuffer.get(start + 1) == 'Y' &&
                charBuffer.get(start + 2) == 'S' &&
                charBuffer.get(start + 3) == 'T' &&
                charBuffer.get(start + 4) == 'E' &&
                charBuffer.get(start + 5) == 'M') {
                return Token.SYSTEM;
            }
            // Check for "PUBLIC"
            if (charBuffer.get(start) == 'P' &&
                charBuffer.get(start + 1) == 'U' &&
                charBuffer.get(start + 2) == 'B' &&
                charBuffer.get(start + 3) == 'L' &&
                charBuffer.get(start + 4) == 'I' &&
                charBuffer.get(start + 5) == 'C') {
                return Token.PUBLIC;
            }
        }
        return null;
    }
    
    /**
     * Checks if a NAME in DOCTYPE_INTERNAL is a markup declaration keyword.
     * 
     * @param start start position in charBuffer
     * @param length length of the name
     * @return START_ELEMENTDECL, START_ATTLISTDECL, START_ENTITYDECL, START_NOTATIONDECL, or null
     */
    private Token checkMarkupDeclaration(int start, int length) {
        if (length == 7) {
            // Check for "ELEMENT"
            if (charBuffer.get(start) == 'E' &&
                charBuffer.get(start + 1) == 'L' &&
                charBuffer.get(start + 2) == 'E' &&
                charBuffer.get(start + 3) == 'M' &&
                charBuffer.get(start + 4) == 'E' &&
                charBuffer.get(start + 5) == 'N' &&
                charBuffer.get(start + 6) == 'T') {
                return Token.START_ELEMENTDECL;
            }
            // Check for "ATTLIST"
            if (charBuffer.get(start) == 'A' &&
                charBuffer.get(start + 1) == 'T' &&
                charBuffer.get(start + 2) == 'T' &&
                charBuffer.get(start + 3) == 'L' &&
                charBuffer.get(start + 4) == 'I' &&
                charBuffer.get(start + 5) == 'S' &&
                charBuffer.get(start + 6) == 'T') {
                return Token.START_ATTLISTDECL;
            }
        } else if (length == 6) {
            // Check for "ENTITY"
            if (charBuffer.get(start) == 'E' &&
                charBuffer.get(start + 1) == 'N' &&
                charBuffer.get(start + 2) == 'T' &&
                charBuffer.get(start + 3) == 'I' &&
                charBuffer.get(start + 4) == 'T' &&
                charBuffer.get(start + 5) == 'Y') {
                return Token.START_ENTITYDECL;
            }
        } else if (length == 8) {
            // Check for "NOTATION"
            if (charBuffer.get(start) == 'N' &&
                charBuffer.get(start + 1) == 'O' &&
                charBuffer.get(start + 2) == 'T' &&
                charBuffer.get(start + 3) == 'A' &&
                charBuffer.get(start + 4) == 'T' &&
                charBuffer.get(start + 5) == 'I' &&
                charBuffer.get(start + 6) == 'O' &&
                charBuffer.get(start + 7) == 'N') {
                return Token.START_NOTATIONDECL;
            }
        }
        return null;
    }
    
    /**
     * Checks if a NAME token is actually a DTD keyword and returns the appropriate token.
     * This method recognizes DTD keywords like EMPTY, ANY, REQUIRED, IMPLIED, etc.
     * Only called for NAME tokens in DOCTYPE contexts.
     * Returns the keyword token, or Token.NAME if not a keyword.
     */
    private Token checkDTDKeyword(int start, int length) {
        // Check by length first for efficiency
        switch (length) {
            case 2:
                // "ID"
                if (charBuffer.get(start) == 'I' &&
                    charBuffer.get(start + 1) == 'D') {
                    return Token.ID;
                }
                break;
                
            case 3:
                // "ANY"
                if (charBuffer.get(start) == 'A' &&
                    charBuffer.get(start + 1) == 'N' &&
                    charBuffer.get(start + 2) == 'Y') {
                    return Token.ANY;
                }
                break;
                
            case 5:
                // "EMPTY", "IDREF", "CDATA", "FIXED", "NDATA"
                char first = charBuffer.get(start);
                if (first == 'E') {
                    if (charBuffer.get(start + 1) == 'M' &&
                        charBuffer.get(start + 2) == 'P' &&
                        charBuffer.get(start + 3) == 'T' &&
                        charBuffer.get(start + 4) == 'Y') {
                        return Token.EMPTY;
                    }
                } else if (first == 'I') {
                    if (charBuffer.get(start + 1) == 'D' &&
                        charBuffer.get(start + 2) == 'R' &&
                        charBuffer.get(start + 3) == 'E' &&
                        charBuffer.get(start + 4) == 'F') {
                        return Token.IDREF;
                    }
                } else if (first == 'C') {
                    if (charBuffer.get(start + 1) == 'D' &&
                        charBuffer.get(start + 2) == 'A' &&
                        charBuffer.get(start + 3) == 'T' &&
                        charBuffer.get(start + 4) == 'A') {
                        return Token.CDATA_TYPE;
                    }
                } else if (first == 'F') {
                    if (charBuffer.get(start + 1) == 'I' &&
                        charBuffer.get(start + 2) == 'X' &&
                        charBuffer.get(start + 3) == 'E' &&
                        charBuffer.get(start + 4) == 'D') {
                        return Token.FIXED;
                    }
                } else if (first == 'N') {
                    if (charBuffer.get(start + 1) == 'D' &&
                        charBuffer.get(start + 2) == 'A' &&
                        charBuffer.get(start + 3) == 'T' &&
                        charBuffer.get(start + 4) == 'A') {
                        return Token.NDATA;
                    }
                }
                break;
                
            case 6:
                // "IDREFS", "ENTITY"
                first = charBuffer.get(start);
                if (first == 'I') {
                    if (charBuffer.get(start + 1) == 'D' &&
                        charBuffer.get(start + 2) == 'R' &&
                        charBuffer.get(start + 3) == 'E' &&
                        charBuffer.get(start + 4) == 'F' &&
                        charBuffer.get(start + 5) == 'S') {
                        return Token.IDREFS;
                    }
                } else if (first == 'E') {
                    if (charBuffer.get(start + 1) == 'N' &&
                        charBuffer.get(start + 2) == 'T' &&
                        charBuffer.get(start + 3) == 'I' &&
                        charBuffer.get(start + 4) == 'T' &&
                        charBuffer.get(start + 5) == 'Y') {
                        return Token.ENTITY;
                    }
                }
                break;
                
            case 7:
                // "NMTOKEN", "IMPLIED", "INCLUDE"
                first = charBuffer.get(start);
                if (first == 'N') {
                    if (charBuffer.get(start + 1) == 'M' &&
                        charBuffer.get(start + 2) == 'T' &&
                        charBuffer.get(start + 3) == 'O' &&
                        charBuffer.get(start + 4) == 'K' &&
                        charBuffer.get(start + 5) == 'E' &&
                        charBuffer.get(start + 6) == 'N') {
                        return Token.NMTOKEN;
                    }
                } else if (first == 'I') {
                    if (charBuffer.get(start + 1) == 'M' &&
                        charBuffer.get(start + 2) == 'P' &&
                        charBuffer.get(start + 3) == 'L' &&
                        charBuffer.get(start + 4) == 'I' &&
                        charBuffer.get(start + 5) == 'E' &&
                        charBuffer.get(start + 6) == 'D') {
                        return Token.IMPLIED;
                    } else if (charBuffer.get(start + 1) == 'N' &&
                               charBuffer.get(start + 2) == 'C' &&
                               charBuffer.get(start + 3) == 'L' &&
                               charBuffer.get(start + 4) == 'U' &&
                               charBuffer.get(start + 5) == 'D' &&
                               charBuffer.get(start + 6) == 'E') {
                        return Token.INCLUDE;
                    }
                }
                break;
                
            case 8:
                // "NMTOKENS", "REQUIRED", "ENTITIES", "NOTATION"
                first = charBuffer.get(start);
                if (first == 'N') {
                    if (charBuffer.get(start + 1) == 'M' &&
                        charBuffer.get(start + 2) == 'T' &&
                        charBuffer.get(start + 3) == 'O' &&
                        charBuffer.get(start + 4) == 'K' &&
                        charBuffer.get(start + 5) == 'E' &&
                        charBuffer.get(start + 6) == 'N' &&
                        charBuffer.get(start + 7) == 'S') {
                        return Token.NMTOKENS;
                    } else if (charBuffer.get(start + 1) == 'O' &&
                               charBuffer.get(start + 2) == 'T' &&
                               charBuffer.get(start + 3) == 'A' &&
                               charBuffer.get(start + 4) == 'T' &&
                               charBuffer.get(start + 5) == 'I' &&
                               charBuffer.get(start + 6) == 'O' &&
                               charBuffer.get(start + 7) == 'N') {
                        return Token.NOTATION;
                    }
                } else if (first == 'R') {
                    if (charBuffer.get(start + 1) == 'E' &&
                        charBuffer.get(start + 2) == 'Q' &&
                        charBuffer.get(start + 3) == 'U' &&
                        charBuffer.get(start + 4) == 'I' &&
                        charBuffer.get(start + 5) == 'R' &&
                        charBuffer.get(start + 6) == 'E' &&
                        charBuffer.get(start + 7) == 'D') {
                        return Token.REQUIRED;
                    }
                } else if (first == 'E') {
                    if (charBuffer.get(start + 1) == 'N' &&
                        charBuffer.get(start + 2) == 'T' &&
                        charBuffer.get(start + 3) == 'I' &&
                        charBuffer.get(start + 4) == 'T' &&
                        charBuffer.get(start + 5) == 'I' &&
                        charBuffer.get(start + 6) == 'E' &&
                        charBuffer.get(start + 7) == 'S') {
                        return Token.ENTITIES;
                    }
                }
                break;
        }
        
        // Not a keyword
        return Token.NAME;
    }
    
    /**
     * Checks if a code point is a legal XML character.
     * XML 1.0: Char ::= #x9 | #xA | #xD | [#x20-#xD7FF] | [#xE000-#xFFFD] | [#x10000-#x10FFFF]
     * 
     * @param codePoint the Unicode code point to check
     * @return true if the code point is legal in XML 1.0
     */
    private boolean isLegalXMLChar(int codePoint) {
        return (codePoint == 0x9 || codePoint == 0xA || codePoint == 0xD ||
                (codePoint >= 0x20 && codePoint <= 0xD7FF) ||
                (codePoint >= 0xE000 && codePoint <= 0xFFFD) ||
                (codePoint >= 0x10000 && codePoint <= 0x10FFFF));
    }
    
    /**
     * Emits an ENTITYREF token for a character reference (&#ddd; or &#xhhh;).
     * Resolves the code point and emits 1 or 2 characters (for supplementary code points).
     * 
     * @param refStart start position in charBuffer (at '&')
     * @param refEnd end position in charBuffer (after ';')
     * @param isHex true if hexadecimal, false if decimal
     */
    private void emitCharacterReference(int refStart, int refEnd, boolean isHex) throws SAXException {
        // Parse: &#ddd; or &#xhhh;
        int numStart = refStart + 2;  // Skip '&#'
        if (isHex) {
            numStart++;  // Skip 'x'
        }
        int numEnd = refEnd - 1;  // Skip ';'
        
        // Parse the code point
        int codePoint = 0;
        for (int i = numStart; i < numEnd; i++) {
            char c = charBuffer.get(i);
            int digit;
            if (isHex) {
                if (c >= '0' && c <= '9') digit = c - '0';
                else if (c >= 'a' && c <= 'f') digit = c - 'a' + 10;
                else if (c >= 'A' && c <= 'F') digit = c - 'A' + 10;
                else throw fatalError("Invalid hexadecimal character reference");
                codePoint = codePoint * 16 + digit;
            } else {
                if (c >= '0' && c <= '9') digit = c - '0';
                else throw fatalError("Invalid decimal character reference");
                codePoint = codePoint * 10 + digit;
            }
        }
        
        // Validate the code point is a legal XML character
        // XML 1.0: Char ::= #x9 | #xA | #xD | [#x20-#xD7FF] | [#xE000-#xFFFD] | [#x10000-#x10FFFF]
        if (!isLegalXMLChar(codePoint)) {
            throw fatalError(String.format("Character reference &#x%X; refers to an illegal XML character", codePoint));
        }
        
        // Validate the character is legal in the current context
        // Check for literal '<' in attribute values
        if ((state == TokenizerState.ATTR_VALUE_APOS || state == TokenizerState.ATTR_VALUE_QUOT) &&
            codePoint == '<') {
            throw fatalError("Literal '<' character (from character reference) not allowed in attribute values");
        }
        
        // Allocate CharBuffer and encode the code point
        char[] chars;
        if (codePoint <= 0xFFFF) {
            chars = new char[] { (char) codePoint };
        } else {
            // Supplementary character - encode as surrogate pair
            codePoint -= 0x10000;
            char high = (char) (0xD800 + (codePoint >> 10));
            char low = (char) (0xDC00 + (codePoint & 0x3FF));
            chars = new char[] { high, low };
        }
        
        CharBuffer window = CharBuffer.wrap(chars);
        consumer.receive(Token.ENTITYREF, window);
    }
    
    // ===== Core Tokenization Logic =====
    
    /**
     * Main tokenization loop using state trie architecture.
     */
    private void tokenize() throws SAXException {
        tokenStartPos = charBuffer.position();
        
        int pos = charBuffer.position();
        int limit = charBuffer.limit();
        
        while (pos < limit) {
            // Check if charset was switched - if so, restart tokenization from beginning of buffer
            
            
            char c = charBuffer.get(pos);
            
            // Classify character
            CharClass cc = CharClass.classify(c, state, miniState);
            if (debug) System.err.println("tokenize: c="+c+" cc="+cc);
            
            // Check for illegal characters
            if (cc == CharClass.ILLEGAL) {
                throw fatalError("Illegal XML character: 0x" + Integer.toHexString(c).toUpperCase());
            }
            
            // Special handling for greedy AND name accumulation states
            // Entity refs and char refs are NOT handled here - they go through the trie
            if (miniState.isGreedyAccumulation() ||
                miniState == MiniState.ACCUMULATING_NAME) {
                
                // Check if this character continues the accumulation
                boolean shouldContinue = false;
                if (miniState == MiniState.ACCUMULATING_NAME) {
                    shouldContinue = (cc == CharClass.NAME_START_CHAR || cc == CharClass.NAME_CHAR || 
                                     cc == CharClass.DIGIT || cc == CharClass.DASH || cc == CharClass.COLON);
                } else if (miniState.isGreedyAccumulation()) {
                    shouldContinue = !shouldStopAccumulating(cc, miniState);
                }
                
                if (shouldContinue) {
                    // Continue accumulating - update line/column and advance
                    if (c == '\n') {
                    } else {
                    }
                    pos++;
                    continue;
                } else {
                    // Stop accumulating - emit the accumulated token
                    // Special handling for XMLDECL: emit the closing quote too
                    if (state == TokenizerState.XMLDECL && miniState == MiniState.ACCUMULATING_CDATA &&
                        (cc == CharClass.APOS || cc == CharClass.QUOT)) {
                        // Emit the accumulated CDATA
                        int tokenLength = pos - tokenStartPos;
                        if (tokenLength > 0) {
                            emitTokenWindow(Token.CDATA, tokenStartPos, tokenLength);                        }
                        // Emit the closing quote
                        Token quoteToken = (cc == CharClass.APOS) ? Token.APOS : Token.QUOT;
                        emitTokenWindow(quoteToken, pos, 1);
                        // Advance past the quote and go to READY
                        pos++;
                        tokenStartPos = pos;
                        miniState = MiniState.READY;
                        continue;
                    } else {
                        // Normal case: emit accumulated token, then let trie handle delimiter
                        int tokenLength = pos - tokenStartPos;
                        if (tokenLength > 0) {
                            Token tokenType = miniState.getTokenType();
                            if (tokenType != null) {
                                emitTokenWindow(tokenType, tokenStartPos, tokenLength);                            }
                        }
                        
                        // Update tokenStartPos to start of delimiter, and reset miniState to READY
                        // The trie will then process the delimiter from READY state
                        tokenStartPos = pos;
                        miniState = MiniState.READY;
                        // Don't advance pos - reprocess delimiter through the trie from READY
                        continue;
                    }
                }
            }
            
            // Look up transition
            Map<MiniState, Map<CharClass, MiniStateTransitionBuilder.Transition>> stateMap = transitionTable.get(state);
            if (stateMap == null) {
                throw fatalError("No transitions defined for state: " + state);
            }
            
            Map<CharClass, MiniStateTransitionBuilder.Transition> miniStateMap = stateMap.get(miniState);
            if (miniStateMap == null) {
                throw fatalError("No transitions defined for " + state + ":" + miniState);
            }
            
            MiniStateTransitionBuilder.Transition transition = miniStateMap.get(cc);
            if (transition == null) {
                // Provide more specific error messages for common mistakes
                if (miniState == MiniState.SEEN_AMP) {
                    throw fatalError("Invalid entity reference: '&' must be followed by entity name or '#', not '" + c + "'");
                }
                throw fatalError("Unexpected character '" + c + "' (" + cc + ") in " + state + ":" + miniState);
            }
            if (debug) System.err.println("\tminiState="+miniState+" transition="+transition);
            
            // Special validation: hex character references must use lowercase 'x'
            if (miniState == MiniState.SEEN_AMP_HASH && transition.nextMiniState == MiniState.SEEN_AMP_HASH_X) {
                if (c != 'x') {
                    throw fatalError("Hexadecimal character references must use lowercase 'x', not '" + c + "'");
                }
            }
            
            // Handle sequence consumption or position advancement
            int posAfterChar = pos + 1;  // Position after consuming this character
            if (transition.sequenceToConsume != null) {
                // Temporarily set position for consumeSequence
                charBuffer.position(pos);
                if (!consumeSequence(transition.sequenceToConsume, pos)) {
                    // Not enough data - reset and underflow
                    charBuffer.position(tokenStartPos);
                    miniState = MiniState.READY;
                    return;
                }
                posAfterChar = charBuffer.position();  // Update after consuming sequence
            }
            
            // Emit token(s) if specified
            if (transition.tokensToEmit != null && !transition.tokensToEmit.isEmpty()) {
                // Determine if we should exclude the trigger character from the first token
                boolean excludeTrigger = (transition.nextMiniState == MiniState.ACCUMULATING_NAME ||
                                         transition.nextMiniState == MiniState.ACCUMULATING_ENTITY_NAME ||
                                         transition.nextMiniState == MiniState.ACCUMULATING_PARAM_ENTITY_NAME ||
                                         transition.nextMiniState == MiniState.ACCUMULATING_CHAR_REF_DEC ||
                                         transition.nextMiniState == MiniState.ACCUMULATING_CHAR_REF_HEX ||
                                         transition.nextMiniState.isGreedyAccumulation());
                
                for (int i = 0; i < transition.tokensToEmit.size(); i++) {
                    Token token = transition.tokensToEmit.get(i);
                    int tokenStart, tokenEnd;
                    
                    // Special handling for character references
                    if (miniState == MiniState.ACCUMULATING_CHAR_REF_DEC || 
                        miniState == MiniState.ACCUMULATING_CHAR_REF_HEX) {
                        // Emit ENTITYREF with resolved character(s)
                        boolean isHex = (miniState == MiniState.ACCUMULATING_CHAR_REF_HEX);
                        emitCharacterReference(tokenStartPos, posAfterChar, isHex);
                        // Character references count as 1 or 2 characters in output
                    }
                    // Special handling for markup declarations (ELEMENT, ATTLIST, ENTITY, NOTATION)
                    else if (token == Token.NAME && miniState == MiniState.ACCUMULATING_MARKUP_NAME) {
                        tokenStart = tokenStartPos;
                        tokenEnd = excludeTrigger ? pos : posAfterChar;
                        int tokenLength = tokenEnd - tokenStart;
                        if (tokenLength > 0) {
                            // Skip the '<!' prefix (2 characters) to get just the keyword
                            int keywordStart = tokenStart + 2;
                            int keywordLength = tokenLength - 2;
                            Token markupToken = checkMarkupDeclaration(keywordStart, keywordLength);
                            // Emit the entire token (including <!) but with the correct token type
                            emitTokenWindow(markupToken, tokenStart, tokenLength);                        }
                    }
                    // Special handling for general entity references
                    else if (token == Token.GENERALENTITYREF || token == Token.PARAMETERENTITYREF) {
                        // Extract just the entity name (without & and ; or % and ;)
                        tokenStart = tokenStartPos + 1;  // Skip '&' or '%'
                        tokenEnd = posAfterChar - 1;     // Skip ';'
                        int tokenLength = tokenEnd - tokenStart;
                        if (tokenLength > 0) {
                            // Check if it's a predefined entity (for GENERALENTITYREF only)
                            if (token == Token.GENERALENTITYREF) {
                                int predefinedIndex = getPredefinedEntityIndex(tokenStart, tokenEnd);
                                if (predefinedIndex >= 0) {
                                    // It's predefined - emit ENTITYREF with window into predefined text
                                    CharBuffer window = PREDEFINED_ENTITY_TEXT.duplicate();
                                    window.position(predefinedIndex);
                                    window.limit(predefinedIndex + 1);
                                    consumer.receive(Token.ENTITYREF, window);
                                } else {
                                    // General entity - emit name only
                                    emitTokenWindow(Token.GENERALENTITYREF, tokenStart, tokenLength);                                }
                            } else {
                                // Parameter entity - emit name only
                                emitTokenWindow(Token.PARAMETERENTITYREF, tokenStart, tokenLength);                            }
                        }
                    }
                    else if (i == 0) {
                        // First token
                        tokenStart = tokenStartPos;
                        tokenEnd = excludeTrigger ? pos : posAfterChar;
                        int tokenLength = tokenEnd - tokenStart;
                        if (tokenLength > 0) {
                            emitTokenWindow(token, tokenStart, tokenLength);                        }
                    } else {
                        // Subsequent tokens: emit the trigger character(s)
                        tokenStart = pos;
                        tokenEnd = posAfterChar;
                        int tokenLength = tokenEnd - tokenStart;
                        if (tokenLength > 0) {
                            emitTokenWindow(token, tokenStart, tokenLength);                        }
                    }
                }
                
                // Set tokenStartPos for next token
                // If transitioning to accumulating state, start at the trigger char (which will be accumulated)
                // EXCEPT in XMLDECL where quotes are emitted as tokens, not accumulated
                // Otherwise, start after everything we just emitted
                if (excludeTrigger && !(state == TokenizerState.XMLDECL && !transition.tokensToEmit.isEmpty())) {
                    tokenStartPos = pos;  // Trigger char will be first char of accumulated token
                } else {
                    tokenStartPos = posAfterChar;
                }
            }
            
            // Advance position
            pos = posAfterChar;
            
            // Change state if specified
            if (transition.stateToChangeTo != null) {
                TokenizerState newState = transition.stateToChangeTo;
                
                // Save return state when entering COMMENT, PI_TARGET, or PI_DATA
                // But don't overwrite if transitioning from PI_TARGET to PI_DATA (preserve original)
                if (newState == TokenizerState.COMMENT ||
                    newState == TokenizerState.PI_TARGET ||
                    (newState == TokenizerState.PI_DATA && state != TokenizerState.PI_TARGET)) {
                    returnState = state;
                }
                // Restore return state when exiting COMMENT/PI back to CONTENT
                else if (newState == TokenizerState.CONTENT &&
                         (state == TokenizerState.COMMENT ||
                          state == TokenizerState.PI_DATA)) {
                    if (returnState != null) {
                        newState = returnState;
                        returnState = null;
                    }
                }
                
                state = newState;
            }
            
            // Move to next mini-state
            miniState = transition.nextMiniState;
            
            // Update column if we haven't already (and not entering greedy accumulation)
            if (!miniState.isGreedyAccumulation() && 
                (transition.tokensToEmit == null || transition.tokensToEmit.isEmpty())) {
                if (c == '\n') {
                } else {
                }
            }
        }
        
        // Update charBuffer position to reflect how much we consumed
        charBuffer.position(pos);
        
        // Buffer exhausted - flush greedy tokens
        if (miniState.isGreedyAccumulation()) {
            int tokenLength = pos - tokenStartPos;
            if (tokenLength > 0) {
                emitTokenWindow(miniState.getTokenType(), tokenStartPos, tokenLength);            }
            miniState = MiniState.READY;
        } else {
            // For non-greedy states, reset miniState for next receive()
            // The unconsumed characters will be in underflow and reprocessed
            miniState = MiniState.READY;
        }
    }
    
    // ===== Helper Methods =====
    
    /**
     * Determines if accumulation should stop for the given character class.
     */
    private boolean shouldStopAccumulating(CharClass cc, MiniState miniState) {
        if (miniState == MiniState.ACCUMULATING_WHITESPACE) {
            return cc != CharClass.WHITESPACE;
        }
        
        if (miniState == MiniState.ACCUMULATING_CDATA) {
            // Context-dependent stop characters
            switch (state) {
                case XMLDECL:
                    // In XML declaration, stop at quotes (they delimit attribute values)
                    return cc == CharClass.APOS || cc == CharClass.QUOT;
                case CONTENT:
                    return cc == CharClass.LT || cc == CharClass.AMP || cc == CharClass.WHITESPACE;
                case ATTR_VALUE_APOS:
                case ATTR_VALUE_QUOT:
                    return cc == CharClass.LT || cc == CharClass.AMP || cc == CharClass.GT ||
                           cc == CharClass.APOS || cc == CharClass.QUOT;
                case DOCTYPE_QUOTED_APOS:
                case DOCTYPE_QUOTED_QUOT:
                case DOCTYPE_INTERNAL_QUOTED_APOS:
                case DOCTYPE_INTERNAL_QUOTED_QUOT:
                    return cc == CharClass.LT || cc == CharClass.AMP || cc == CharClass.PERCENT ||
                           cc == CharClass.APOS || cc == CharClass.QUOT;
                case COMMENT:
                    return cc == CharClass.DASH;
                case CDATA_SECTION:
                    return cc == CharClass.CLOSE_BRACKET;
                case PI_DATA:
                    return cc == CharClass.QUERY;
                default:
                    return true;  // Unknown context, stop immediately
            }
        }
        
        return false;
    }
    
    /**
     * Attempts to consume an exact character sequence.
     * Returns false if not enough data is available.
     */
    private boolean consumeSequence(String sequence, int startPos) throws SAXException {
        if (charBuffer.remaining() < sequence.length()) {
            // Not enough data
            charBuffer.position(startPos);
            return false;
        }
        
        // Verify exact match
        for (int i = 0; i < sequence.length(); i++) {
            if (charBuffer.get() != sequence.charAt(i)) {
                throw fatalError("Expected '" + sequence + "' but found mismatch");
            }
        }
        
        return true;
    }
    
    /**
     * Emits a token with a window into the character buffer.
     * For NAME tokens in DOCTYPE context, checks if they're keywords and converts them.
     * For NAME tokens in DOCTYPE_INTERNAL with ACCUMULATING_MARKUP_NAME, converts to markup declaration tokens.
     */
    private void emitTokenWindow(Token token, int start, int length) throws SAXException {
        // Check if this is a NAME token in DOCTYPE that should be a keyword
        if (token == Token.NAME && state == TokenizerState.DOCTYPE) {
            Token keywordToken = checkDOCTYPEKeyword(start, length);
            if (keywordToken != null) {
                token = keywordToken;
            }
        }
        
        // Check if this is a NAME token in DOCTYPE_INTERNAL that should be a DTD keyword
        // (except for markup declaration names which are handled separately below)
        if (token == Token.NAME && 
            (state == TokenizerState.DOCTYPE_INTERNAL ||
             state == TokenizerState.DOCTYPE_INTERNAL_QUOTED_QUOT ||
             state == TokenizerState.DOCTYPE_INTERNAL_QUOTED_APOS) &&
            miniState != MiniState.ACCUMULATING_MARKUP_NAME) {
            Token keywordToken = checkDTDKeyword(start, length);
            // checkDTDKeyword returns Token.NAME if not a keyword, so we don't need to check for null
            token = keywordToken;
        }
        
        // Check if this is a markup declaration name in DOCTYPE_INTERNAL
        if (token == Token.NAME && state == TokenizerState.DOCTYPE_INTERNAL && miniState == MiniState.ACCUMULATING_MARKUP_NAME) {
            Token markupToken = checkMarkupDeclaration(start, length);
            if (markupToken != null) {
                token = markupToken;
            }
        }
        
        // Check if this is a PI target name and validate it's not "xml" (case-insensitive)
        // Per XML 1.0 spec rule 17: PITarget names matching [Xx][Mm][Ll] are reserved
        if (token == Token.NAME && state == TokenizerState.PI_TARGET) {
            if (length == 3) {
                char c0 = charBuffer.get(start);
                char c1 = charBuffer.get(start + 1);
                char c2 = charBuffer.get(start + 2);
                if ((c0 == 'x' || c0 == 'X') && (c1 == 'm' || c1 == 'M') && (c2 == 'l' || c2 == 'L')) {
                    throw fatalError("Processing instruction target matching [Xx][Mm][Ll] is reserved");
                }
            }
        }
        
                        
        // Route tokens to appropriate consumer
        if (state == TokenizerState.XMLDECL || token == Token.START_XMLDECL) {
            // XML declaration tokens go to internal handler (including START_XMLDECL)
            // Save position if this is END_PI (end of XML declaration)
            if (token == Token.END_PI) {
            }
            
            if (token.hasAssociatedText()) {
                CharBuffer window = charBuffer.duplicate();
                window.position(start);
                window.limit(start + length);
                receiveXMLDeclToken(token, window);
            } else {
                receiveXMLDeclToken(token, null);
            }
        } else {
            // Normal tokens go to main consumer
            if (token.hasAssociatedText()) {
                CharBuffer window = charBuffer.duplicate();
                window.position(start);
                window.limit(start + length);
                consumer.receive(token, window);
            } else {
                consumer.receive(token, null);
            }
        }
        
            }
    
    /**
     * Creates a fatal error exception.
     */
    private SAXException fatalError(String message) throws SAXException {
        return consumer.fatalError(message);
    }
    
    // ===== XML Declaration Token Handler =====
    
    /**
     * Receives tokens from the XML declaration state instead of sending them to the main consumer.
     * Parses the attribute-value pairs (version, encoding, standalone) and validates them.
     */
    private void receiveXMLDeclToken(Token token, CharBuffer data) throws SAXException {
        switch (token) {
            case START_XMLDECL:
                // Beginning of XML declaration - reset state
                xmlDeclAttributeName = null;
                xmlDeclSeenAttributes = 0;
                xmlDeclEncoding = null;
                xmlDeclQuoteChar = '\0';
                xmlDeclSeenWhitespace = false;
                break;
                
            case S:
                // Whitespace - mark as seen
                xmlDeclSeenWhitespace = true;
                break;
                
            case NAME:
                // Attribute name - check for required whitespace
                if (xmlDeclSeenAttributes > 0 && !xmlDeclSeenWhitespace) {
                    // After the first attribute, whitespace is required before next attribute
                    throw fatalError("Whitespace is required between attributes in XML declaration");
                }
                xmlDeclSeenWhitespace = false;  // Reset for next attribute
                
                if (data != null) {
                    xmlDeclAttributeName = extractString(data);
                    validateXMLDeclAttributeName(xmlDeclAttributeName);
                }
                break;
                
            case EQ:
                // Equals sign - no action needed
                break;
                
            case APOS:
                // Track which quote character for value extraction
                if (xmlDeclQuoteChar == '\0') {
                    // Opening quote
                    xmlDeclQuoteChar = '\'';
                } else if (xmlDeclQuoteChar != '\'') {
                    // Closing quote doesn't match opening quote
                    throw fatalError("Mismatched quotes in XML declaration attribute value: expected " + xmlDeclQuoteChar + " but found '");
                } else {
                    // Matching closing quote - value will be in preceding CDATA
                    xmlDeclQuoteChar = '\0';
                }
                break;
                
            case QUOT:
                // Track which quote character for value extraction  
                if (xmlDeclQuoteChar == '\0') {
                    // Opening quote
                    xmlDeclQuoteChar = '"';
                } else if (xmlDeclQuoteChar != '"') {
                    // Closing quote doesn't match opening quote
                    throw fatalError("Mismatched quotes in XML declaration attribute value: expected " + xmlDeclQuoteChar + " but found \"");
                } else {
                    // Matching closing quote - value will be in preceding CDATA
                    xmlDeclQuoteChar = '\0';
                }
                break;
                
            case CDATA:
                // Attribute value
                if (data != null && xmlDeclAttributeName != null) {
                    String value = extractString(data);
                    storeXMLDeclAttributeValue(xmlDeclAttributeName, value);
                    xmlDeclAttributeName = null;
                }
                break;
                
            case END_PI:
                // End of XML declaration - apply charset if needed
                applyXMLDeclEncoding();
                break;
                
            default:
                throw fatalError("Unexpected token in XML declaration: " + token);
        }
    }
    
    /**
     * Validates that the attribute name is allowed in XML declarations and in correct order.
     */
    private void validateXMLDeclAttributeName(String name) throws SAXException {
        if ("version".equals(name)) {
            if (xmlDeclSeenAttributes != 0) {
                throw fatalError("'version' must be the first attribute in XML declaration");
            }
            xmlDeclSeenAttributes |= 1;
        } else if ("encoding".equals(name)) {
            if ((xmlDeclSeenAttributes & 1) == 0) {
                throw fatalError("'version' must come before 'encoding' in XML declaration");
            }
            if ((xmlDeclSeenAttributes & 4) != 0) {
                throw fatalError("'encoding' must come before 'standalone' in XML declaration");
            }
            xmlDeclSeenAttributes |= 2;
        } else if ("standalone".equals(name)) {
            // Text declarations (in external entities) MUST NOT have standalone attribute
            if (externalEntityDecoder != null) {
                throw fatalError(
                    "Text declaration in external entity must not have 'standalone' attribute");
            }
            if ((xmlDeclSeenAttributes & 1) == 0) {
                throw fatalError("'version' must come before 'standalone' in XML declaration");
            }
            xmlDeclSeenAttributes |= 4;
        } else {
            throw fatalError("Unknown attribute in XML declaration: " + name);
        }
    }
    
    /**
     * Stores an attribute value from the XML declaration.
     */
    private void storeXMLDeclAttributeValue(String name, String value) throws SAXException {
        if ("version".equals(name)) {
            if (!"1.0".equals(value) && !"1.1".equals(value)) {
                throw fatalError("Unsupported XML version: " + value);
            }
            // Store version in EED if present
            if (externalEntityDecoder != null) {
                externalEntityDecoder.setVersion(value);
            }
            // Update tokenizer's own XML 1.1 state for character validation
            if ("1.1".equals(value)) {
                setXML11(true);
            }
        } else if ("encoding".equals(name)) {
            // Save encoding for later application (after declaration is complete)
            xmlDeclEncoding = value;
        } else if ("standalone".equals(name)) {
            if ("yes".equals(value)) {
                if (externalEntityDecoder != null) {
                    externalEntityDecoder.setStandalone(true);
                }
            } else if ("no".equals(value)) {
                if (externalEntityDecoder != null) {
                    externalEntityDecoder.setStandalone(false);
                }
            } else {
                throw fatalError("Invalid standalone value: " + value + " (must be 'yes' or 'no')");
            }
        }
    }
    
    /**
     * Applies the encoding from the XML declaration,
     * delegating to the ExternalEntityDecoder if present.
     */
    private void applyXMLDeclEncoding() throws SAXException {
        // Validate required attributes
        if ((xmlDeclSeenAttributes & 1) == 0) {
            throw fatalError("XML declaration missing required 'version' attribute");
        }
        
        // If encoding was specified, apply it via the EED
        if (xmlDeclEncoding != null && externalEntityDecoder != null) {
            try {
                externalEntityDecoder.setCharset(xmlDeclEncoding);
            } catch (Exception e) {
                throw fatalError("Failed to set charset: " + e.getMessage());
            }
        }
        
        // Reset XML decl parsing state
        xmlDeclAttributeName = null;
        xmlDeclSeenAttributes = 0;
        xmlDeclEncoding = null;
        xmlDeclQuoteChar = '\0';
        xmlDeclSeenWhitespace = false;
    }
    
    /**
     * Extracts a string from a CharBuffer.
     */
    private String extractString(CharBuffer buffer) {
        StringBuilder sb = new StringBuilder(buffer.remaining());
        while (buffer.hasRemaining()) {
            sb.append(buffer.get());
        }
        return sb.toString();
    }
    
    /**
     * Saves remaining characters to underflow buffer.
     */
    private void saveCharUnderflow(CharBuffer buffer) {
        if (!buffer.hasRemaining()) {
            return;
        }
        
        if (charUnderflow == null || charUnderflow.capacity() < buffer.remaining()) {
            charUnderflow = CharBuffer.allocate(Math.max(buffer.remaining() * 2, 4096));
        } else {
            charUnderflow.clear();
        }
        
        charUnderflow.put(buffer);
        charUnderflow.flip();
    }
}


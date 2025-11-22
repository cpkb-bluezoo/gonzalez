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
    
    /**
     * Whether this tokenizer is processing XML 1.1 (vs. XML 1.0).
     * Affects character validity rules.
     */
    private boolean isXML11 = false;

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
     * State to transition to after parsing XML/text declaration.
     * For top-level documents: PROLOG_BEFORE_DOCTYPE
     * For external DTD subsets/parameter entities: DOCTYPE_INTERNAL
     * For external general entities: CONTENT
     */
    private TokenizerState postDeclState = TokenizerState.PROLOG_BEFORE_DOCTYPE;
    
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
    
    // ===== Conditional Section State =====
    
    /**
     * Pending conditional section type (INCLUDE or IGNORE) waiting for '[' to start the section.
     * Null if no conditional section is pending.
     */
    private Token pendingConditionalType = null;
    
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
    
    /**
     * Reusable buffer for character references (e.g., &#60; or &#x1F4A9;).
     * Sized for 2 chars to handle surrogate pairs.
     * Reused across all character reference emissions to avoid allocating a new char[] every time.
     */
    private final char[] charRefBuffer = new char[2];
    
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
    
    /**
     * Package-private constructor for creating tokenizers with a specific initial state.
     * Used when expanding internal or external entity references to ensure the tokenizer starts
     * in the correct context (e.g., CONTENT vs DOCTYPE_INTERNAL).
     * 
     * This constructor does NOT notify the consumer of the initial state because this is a
     * nested tokenizer for entity expansion, not the main document tokenizer.
     * 
     * @param consumer the token consumer
     * @param initialState the initial tokenizer state
     */
    Tokenizer(TokenConsumer consumer, TokenizerState initialState) {
        this.consumer = consumer;
        this.transitionTable = MiniStateTransitionBuilder.TRANSITION_TABLE;
        this.state = initialState;
        // Note: Do NOT call consumer.tokenizerState() - nested tokenizers don't update consumer state
        // Note: setLocator will be called by the consumer or ExternalEntityDecoder
    }
    
    /**
     * Package-private constructor for creating tokenizers with a specific initial state and XML version.
     * Used when expanding internal entity references to ensure the tokenizer starts
     * in the correct context and inherits the parent's XML version.
     * 
     * @param consumer the token consumer
     * @param initialState the initial tokenizer state
     * @param isXML11 whether to use XML 1.1 character rules
     */
    Tokenizer(TokenConsumer consumer, TokenizerState initialState, boolean isXML11) {
        this.consumer = consumer;
        this.transitionTable = MiniStateTransitionBuilder.TRANSITION_TABLE;
        this.state = initialState;
        this.isXML11 = isXML11;
        // Note: Do NOT call consumer.tokenizerState() - nested tokenizers don't update consumer state
        // Note: setLocator will be called by the consumer or ExternalEntityDecoder
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
     * Package-private method to set the state to transition to after parsing XML/text declaration.
     * For external entities, this should be set before feeding data to handle text declarations properly.
     * 
     * @param postDeclState the state to enter after optional text declaration (DOCTYPE_INTERNAL for DTD entities, CONTENT for general entities)
     */
    void setPostDeclarationState(TokenizerState postDeclState) {
        this.postDeclState = postDeclState;
    }
    
    /**
     * Sets whether this tokenizer should process XML 1.1 (vs. XML 1.0).
     * Called by ExternalEntityDecoder when XML/text declaration specifies version="1.1".
     * 
     * @param isXML11 true for XML 1.1, false for XML 1.0
     */
    public void setXML11(boolean isXML11) {
        this.isXML11 = isXML11;
    }
    
    /**
     * Returns whether XML 1.1 character rules are enabled.
     * @return true if XML 1.1 rules are enabled, false if XML 1.0 rules are used
     */
    public boolean isXML11() {
        return isXML11;
    }
    
    /**
     * Helper method to change state and notify the consumer.
     * Used internally to ensure the consumer tracks the tokenizer state.
     * 
     * @param newState the new tokenizer state
     */
    private void changeState(TokenizerState newState) {
        if (this.state != newState) {
            this.state = newState;
            consumer.tokenizerState(newState);
        }
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
            changeState(TokenizerState.BOM_READ);
        }
        
        // For internal entity expansion, we skip BOM detection and decoding
        // Initialize buffers if needed
        if (charBuffer == null) {
            charBuffer = CharBuffer.allocate(4096);
            charUnderflow = CharBuffer.allocate(4096);
            charUnderflow.limit(0);
        }
        
        // Ensure charBuffer is large enough
        // Only reallocate if significantly too small (< 50% of needed capacity)
        int neededCapacity = (charUnderflow != null ? charUnderflow.remaining() : 0) + data.remaining();
        if (charBuffer.capacity() < neededCapacity * 0.5) {
            int newCapacity = Math.max(neededCapacity * 2, 4096);
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
            // Only reallocate if significantly too small (< 50% of needed capacity)
            if (charUnderflow == null) {
                charUnderflow = CharBuffer.allocate(Math.max(charBuffer.remaining() * 2, 4096));
            } else if (charUnderflow.capacity() < charBuffer.remaining() * 0.5) {
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
        
        // Check for underflow - incomplete tokens waiting for more input
        if (charUnderflow != null && charUnderflow.hasRemaining()) {
            // There's unprocessed data in underflow - this means we have an incomplete token
            throw fatalError("Incomplete token at end of input (underflow has " + charUnderflow.remaining() + " chars)");
        }
        
        // Validate that we're in a valid end state
        // If we're in the middle of parsing something (like an entity reference after '&'),
        // that's a well-formedness error
        if (miniState != MiniState.READY) {
            throw fatalError("Incomplete token at end of input: " + miniState);
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
        isXML11 = false; // Reset to XML 1.0 mode
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
     * Checks if a code point is a legal XML character in a character reference.
     * 
     * XML 1.0: Char ::= #x9 | #xA | #xD | [#x20-#xD7FF] | [#xE000-#xFFFD] | [#x10000-#x10FFFF]
     * XML 1.1: Char ::= [#x1-#xD7FF] | [#xE000-#xFFFD] | [#x10000-#x10FFFF]
     * 
     * Note: In XML 1.1, character references can use the extended range including C0/C1 controls,
     * but these characters are restricted when appearing directly in the document.
     * 
     * @param codePoint the Unicode code point to check
     * @return true if the code point is legal in this XML version
     */
    private boolean isLegalXMLChar(int codePoint) {
        if (isXML11) {
            // XML 1.1: [#x1-#xD7FF] | [#xE000-#xFFFD] | [#x10000-#x10FFFF]
            // Allows C0/C1 controls in character references (0x01-0x1F, 0x7F-0x9F)
            return (codePoint >= 0x1 && codePoint <= 0xD7FF) ||
                   (codePoint >= 0xE000 && codePoint <= 0xFFFD) ||
                   (codePoint >= 0x10000 && codePoint <= 0x10FFFF);
        } else {
            // XML 1.0: #x9 | #xA | #xD | [#x20-#xD7FF] | [#xE000-#xFFFD] | [#x10000-#x10FFFF]
            return (codePoint == 0x9 || codePoint == 0xA || codePoint == 0xD ||
                    (codePoint >= 0x20 && codePoint <= 0xD7FF) ||
                    (codePoint >= 0xE000 && codePoint <= 0xFFFD) ||
                    (codePoint >= 0x10000 && codePoint <= 0x10FFFF));
        }
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
        
        // Encode the code point into the reusable buffer
        int length;
        if (codePoint <= 0xFFFF) {
            // BMP character - single char
            charRefBuffer[0] = (char) codePoint;
            length = 1;
        } else {
            // Supplementary character - encode as surrogate pair
            codePoint -= 0x10000;
            charRefBuffer[0] = (char) (0xD800 + (codePoint >> 10));  // high surrogate
            charRefBuffer[1] = (char) (0xDC00 + (codePoint & 0x3FF)); // low surrogate
            length = 2;
        }
        
        // Wrap the reusable buffer and emit
        // Note: CharBuffer.wrap() creates a lightweight wrapper, not a copy
        CharBuffer window = CharBuffer.wrap(charRefBuffer, 0, length);
        consumer.receive(Token.CHARENTITYREF, window);
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
            CharClass cc = CharClass.classify(c, state, miniState, isXML11);
            if (debug) System.err.println("tokenize: c="+c+" cc="+cc);
            
            // Check for illegal characters
            if (cc == CharClass.ILLEGAL) {
                throw fatalError("Illegal XML character: 0x" + Integer.toHexString(c).toUpperCase());
            }
            
            // Special handling: Auto-transition from INIT/BOM_READ to postDeclState when encountering
            // content that is NOT a text declaration. Text declarations MUST be at the very start.
            // This ensures:
            // 1. External entities without text decls start processing in the correct state
            // 2. After one text decl, subsequent <?xml...?> are treated as (illegal) PIs
            if ((state == TokenizerState.INIT || state == TokenizerState.BOM_READ)) {
                boolean shouldTransition = false;
                
                if (miniState == MiniState.READY) {
                    // In READY, if we see anything other than '<', it's content
                    if (cc != CharClass.LT && cc != CharClass.WHITESPACE) {
                        shouldTransition = true;
                    }
                } else if (miniState == MiniState.SEEN_LT) {
                    // After '<', if we don't see '?', it's not a text decl
                    if (cc != CharClass.QUERY) {
                        shouldTransition = true;
                    }
                }
                
                if (shouldTransition) {
                    changeState(postDeclState);
                    // Continue to process this character in the new state
                }
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
            
            // Look up transition using optimized flat array
            // This eliminates two levels of map lookups (MiniState → CharClass)
            MiniStateTransitionBuilder.Transition[] flatArray = MiniStateTransitionBuilder.FLAT_TRANSITION_TABLE.get(state);
            if (flatArray == null) {
                throw fatalError("No transitions defined for state: " + state);
            }
            
            int index = miniState.ordinal() * MiniStateTransitionBuilder.NUM_CHAR_CLASSES + cc.ordinal();
            MiniStateTransitionBuilder.Transition transition = flatArray[index];
            
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
            
            // Special validation: <?xm followed by non-'l' NAME_START_CHAR
            // This catches cases like <?XML, <?xmL, <?xmFoo where the PI target will start with "xm"
            // Check if it would form a case variant of "xml" (reserved PI target)
            // EXCEPT: At the start of external entities, <?xml is a text declaration (allowed)
            if (state == TokenizerState.BOM_READ) {
                boolean isExternalEntity = (externalEntityDecoder != null && externalEntityDecoder.isExternalEntity());
                
                // After <?xm, if we see NAME_START_CHAR that's not 'l', check if it's 'L'
                if (miniState == MiniState.SEEN_LT_QUERY_XM && cc == CharClass.NAME_START_CHAR) {
                    if (c == 'L') {
                        // This forms "xmL" which matches [Xx][Mm][Ll]
                        // If we're at the start of an external entity, allow it (text declaration)
                        // Otherwise it's a reserved PI target
                        if (!isExternalEntity) {
                            throw fatalError("Processing instruction target matching [Xx][Mm][Ll] is reserved");
                        }
                        // In external entity: let it proceed as text declaration
                    }
                }
                // After <?x, if we see NAME_START_CHAR that's not 'm', check if it forms reserved pattern
                if (miniState == MiniState.SEEN_LT_QUERY_X && cc == CharClass.NAME_START_CHAR) {
                    if (c == 'M') {
                        // Could be <?xM followed by l/L - need to check next char
                        // For now, we'll let it proceed and catch at SEEN_LT_QUERY_XM
                    } else {
                        // Not forming "xml" pattern, safe to continue as PI
                    }
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
                                    // It's predefined - emit PREDEFENTITYREF with window into predefined text
                                    CharBuffer window = PREDEFINED_ENTITY_TEXT.duplicate();
                                    window.position(predefinedIndex);
                                    window.limit(predefinedIndex + 1);
                                    consumer.receive(Token.PREDEFENTITYREF, window);
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
                
                // Special handling for exiting XMLDECL state (text/XML declaration end)
                // Use the configured postDeclState instead of hardcoded PROLOG_BEFORE_DOCTYPE
                if (state == TokenizerState.XMLDECL && newState == TokenizerState.PROLOG_BEFORE_DOCTYPE) {
                    newState = postDeclState;
                }
                
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
                
                changeState(newState);
            }
            
            // Check if we just emitted OPEN_BRACKET after INCLUDE/IGNORE keyword
            // If so, change to the appropriate conditional section state
            if (state == TokenizerState.CONDITIONAL_SECTION_KEYWORD && 
                pendingConditionalType != null &&
                transition.tokensToEmit != null) {
                // Check if OPEN_BRACKET was emitted
                for (Token emittedToken : transition.tokensToEmit) {
                    if (emittedToken == Token.OPEN_BRACKET) {
                        // Change to the appropriate conditional section state
                        TokenizerState newState;
                        if (pendingConditionalType == Token.INCLUDE) {
                            newState = TokenizerState.CONDITIONAL_SECTION_INCLUDE;
                        } else {
                            newState = TokenizerState.CONDITIONAL_SECTION_IGNORE;
                        }
                        changeState(newState);
                        pendingConditionalType = null;
                        break;
                    }
                }
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
        
        // Buffer exhausted - flush greedy tokens or handle incomplete tokens
        if (miniState.isGreedyAccumulation()) {
            int tokenLength = pos - tokenStartPos;
            if (tokenLength > 0) {
                emitTokenWindow(miniState.getTokenType(), tokenStartPos, tokenLength);            }
            miniState = MiniState.READY;
        } else if (miniState != MiniState.READY) {
            // We're in a non-greedy, non-READY state (e.g., SEEN_AMP waiting for entity name)
            // This means we have an incomplete token. Rewind the buffer to save it for next receive()
            charBuffer.position(tokenStartPos);
            // Reset miniState to READY so the incomplete token will be reprocessed from scratch
            // when more data arrives (from underflow)
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
                    // Stop on quotes (delimit the literal), AMP (entity ref), LT (illegal)
                    // Note: PERCENT is NOT a stop character - it's literal data in quoted PUBLIC/SYSTEM IDs
                    return cc == CharClass.LT || cc == CharClass.AMP ||
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
        // Check if this is a NAME token in DOCTYPE context that should be a keyword
        // This applies to both DOCTYPE (<!DOCTYPE doc SYSTEM...>) and DOCTYPE_INTERNAL (inside [...])
        if (token == Token.NAME && (state == TokenizerState.DOCTYPE || state == TokenizerState.DOCTYPE_INTERNAL)) {
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
        
        // Check if this is a conditional section keyword (INCLUDE or IGNORE)
        if (token == Token.NAME && state == TokenizerState.CONDITIONAL_SECTION_KEYWORD) {
            // Check for "INCLUDE" or "IGNORE"
            if (length == 7 && 
                charBuffer.get(start) == 'I' &&
                charBuffer.get(start + 1) == 'N' &&
                charBuffer.get(start + 2) == 'C' &&
                charBuffer.get(start + 3) == 'L' &&
                charBuffer.get(start + 4) == 'U' &&
                charBuffer.get(start + 5) == 'D' &&
                charBuffer.get(start + 6) == 'E') {
                token = Token.INCLUDE;
                pendingConditionalType = Token.INCLUDE;
            } else if (length == 6 &&
                charBuffer.get(start) == 'I' &&
                charBuffer.get(start + 1) == 'G' &&
                charBuffer.get(start + 2) == 'N' &&
                charBuffer.get(start + 3) == 'O' &&
                charBuffer.get(start + 4) == 'R' &&
                charBuffer.get(start + 5) == 'E') {
                token = Token.IGNORE;
                pendingConditionalType = Token.IGNORE;
            }
            // If not INCLUDE or IGNORE, it's an error - but we'll let the parser handle that
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
        // EXCEPT: Text declarations at the START of external entities (INIT/BOM_READ state) are allowed
        if (token == Token.NAME && state == TokenizerState.PI_TARGET) {
            if (length == 3) {
                char c0 = charBuffer.get(start);
                char c1 = charBuffer.get(start + 1);
                char c2 = charBuffer.get(start + 2);
                if ((c0 == 'x' || c0 == 'X') && (c1 == 'm' || c1 == 'M') && (c2 == 'l' || c2 == 'L')) {
                    // This is ALWAYS an error in PI_TARGET state because:
                    // 1. If we're at the start of an external entity (INIT/BOM_READ), <?xml would have been
                    //    recognized as START_XMLDECL, not as PI_TARGET
                    // 2. After the first text declaration, subsequent <?xml...?> are illegal PIs
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
                // Set window into charBuffer (no duplicate needed)
                int savedPosition = charBuffer.position();
                int savedLimit = charBuffer.limit();
                charBuffer.position(start);
                charBuffer.limit(start + length);
                receiveXMLDeclToken(token, charBuffer);
                charBuffer.position(savedPosition);
                charBuffer.limit(savedLimit);
            } else {
                receiveXMLDeclToken(token, null);
            }
        } else {
            // Normal tokens go to main consumer
            if (token.hasAssociatedText()) {
                // Set window into charBuffer (no duplicate needed - consumer won't modify position)
                int savedPosition = charBuffer.position();
                int savedLimit = charBuffer.limit();
                charBuffer.position(start);
                charBuffer.limit(start + length);
                consumer.receive(token, charBuffer);
                // Restore position/limit for tokenizer to continue
                charBuffer.position(savedPosition);
                charBuffer.limit(savedLimit);
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
                    xmlDeclAttributeName = data.toString();
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
                    String value = data.toString();
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
     * Different rules apply for XML declarations (document entity) vs text declarations (external entities):
     * - XML declaration: version required, encoding optional, standalone optional
     * - Text declaration: version optional, encoding required, standalone forbidden
     */
    private void validateXMLDeclAttributeName(String name) throws SAXException {
        boolean isTextDecl = (externalEntityDecoder != null && externalEntityDecoder.isExternalEntity());
        
        if ("version".equals(name)) {
            if (xmlDeclSeenAttributes != 0) {
                String declType = isTextDecl ? "text declaration" : "XML declaration";
                throw fatalError("'version' must be the first attribute in " + declType);
            }
            xmlDeclSeenAttributes |= 1;
        } else if ("encoding".equals(name)) {
            // In XML declarations, if encoding is present, version must come first
            // In text declarations, encoding can appear without version
            if (!isTextDecl && (xmlDeclSeenAttributes & 1) == 0) {
                throw fatalError("'version' must come before 'encoding' in XML declaration");
            }
            if ((xmlDeclSeenAttributes & 4) != 0) {
                String declType = isTextDecl ? "text declaration" : "XML declaration";
                throw fatalError("'encoding' must come before 'standalone' in " + declType);
            }
            xmlDeclSeenAttributes |= 2;
        } else if ("standalone".equals(name)) {
            // Text declarations (in external entities) MUST NOT have standalone attribute
            if (isTextDecl) {
                throw fatalError(
                    "Text declaration in external entity must not have 'standalone' attribute");
            }
            if ((xmlDeclSeenAttributes & 1) == 0) {
                throw fatalError("'version' must come before 'standalone' in XML declaration");
            }
            xmlDeclSeenAttributes |= 4;
        } else {
            String declType = isTextDecl ? "text declaration" : "XML declaration";
            throw fatalError("Unknown attribute in " + declType + ": " + name);
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
            boolean versionIsXML11 = "1.1".equals(value);
            setXML11(versionIsXML11);
            
            // Notify consumer so it can track version for entity expansion
            consumer.xmlVersion(versionIsXML11);
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
     * Also validates required attributes based on declaration type.
     */
    private void applyXMLDeclEncoding() throws SAXException {
        boolean isTextDecl = (externalEntityDecoder != null && externalEntityDecoder.isExternalEntity());
        
        // Validate required attributes
        // XML declaration: version required, encoding optional
        // Text declaration: version optional, encoding required
        if (!isTextDecl && (xmlDeclSeenAttributes & 1) == 0) {
            throw fatalError("XML declaration missing required 'version' attribute");
        }
        if (isTextDecl && (xmlDeclSeenAttributes & 2) == 0) {
            throw fatalError("Text declaration missing required 'encoding' attribute");
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
     * Saves remaining characters to underflow buffer.
     */
    private void saveCharUnderflow(CharBuffer buffer) {
        if (!buffer.hasRemaining()) {
            return;
        }
        
        // Only reallocate if significantly too small (< 50% of needed capacity)
        if (charUnderflow == null) {
            charUnderflow = CharBuffer.allocate(Math.max(buffer.remaining() * 2, 4096));
        } else if (charUnderflow.capacity() < buffer.remaining() * 0.5) {
            charUnderflow = CharBuffer.allocate(Math.max(buffer.remaining() * 2, 4096));
        } else {
            charUnderflow.clear();
        }
        
        charUnderflow.put(buffer);
        charUnderflow.flip();
    }
}


/*
 * XMLTokenizer.java
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

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.ext.Locator2;

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
 * This class is a SAX Locator2 providing line and column information for error reporting.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class XMLTokenizer implements Locator2 {

    /**
     * Pre-expanded text for predefined entity references.
     * Indexed by entity name: amp=0, lt=1, gt=2, apos=3, quot=4
     */
    private static final CharBuffer PREDEFINED_ENTITY_TEXT = CharBuffer.wrap("&<>'\"").asReadOnlyBuffer();
    
    // ===== Configuration and Document Metadata =====
    
    /**
     * The character set used to decode incoming bytes into characters.
     */
    private Charset charset = StandardCharsets.UTF_8;

    /**
     * The XML version declared in the document (default "1.0").
     */
    private String version = "1.0";

    /**
     * Whether this document is standalone.
     */
    private boolean standalone;
    
    /**
     * Whether this tokenizer is parsing an external entity.
     * External entities use text declarations (no standalone attribute).
     */
    private boolean isExternalEntity;

    // ===== State Machine =====
    
    /**
     * Current high-level parsing state.
     */
    private TokenizerState state = TokenizerState.INIT;
    
    /**
     * Current fine-grained token recognition state.
     */
    private MiniState miniState = MiniState.READY;
    
    /**
     * Transition table mapping (State, MiniState, CharClass) to Transition.
     * This is a reference to the pre-built static table in MiniStateTransitionBuilder.
     */
    private final Map<TokenizerState, Map<MiniState, Map<CharClass, MiniStateTransitionBuilder.Transition>>> transitionTable;
    
    // ===== Buffers =====
    
    /**
     * Byte underflow buffer for incomplete byte sequences.
     * Stored between receive() calls and prepended to next incoming data.
     */
    private ByteBuffer byteUnderflow;
    
    /**
     * Working buffer for combining underflow with incoming data.
     * Reused to avoid allocation on every receive() call.
     */
    private ByteBuffer byteWorkingBuffer;

    /**
     * Character underflow buffer for incomplete character sequences.
     * Stored between receive() calls and prepended to decoded characters.
     */
    private CharBuffer charUnderflow;

    /**
     * Main character processing buffer.
     * Holds decoded characters ready for tokenization.
     */
    private CharBuffer charBuffer;
    
    /**
     * Character decoder for the current charset.
     */
    private CharsetDecoder decoder;
    
    // ===== Position Tracking =====
    
    /**
     * Current line number (1-based).
     */
    private long lineNumber = 1;

    /**
     * Current column number (0-based, position after last emitted token).
     */
    private long columnNumber = 0;
    
    /**
     * Column number at the start of the current token being recognized.
     */
    private int tokenStartColumn;
    
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
     * System ID for error reporting.
     */
    private String systemId;
    
    /**
     * Public ID for error reporting.
     */
    private String publicId;
    
    /**
     * Whether the tokenizer has been closed.
     */
    private boolean closed;
    
    // ===== Initialization State Data =====
    
    /**
     * Internal state for init phase.
     */
    private enum InitState {
        INIT,
        BOM_READ,
        XMLDECL
    }
    
    private InitState initState = InitState.INIT;
    
    /**
     * BOM-detected charset (if any).
     */
    private Charset bomCharset;
    
    /**
     * Last character seen (for CRLF normalization across buffers).
     */
    private char lastCharSeen = '\0';
    
    // ===== Predefined Entity Replacement Buffers =====
    
    private static final CharBuffer PREDEFINED_LT = CharBuffer.wrap("<").asReadOnlyBuffer();
    private static final CharBuffer PREDEFINED_GT = CharBuffer.wrap(">").asReadOnlyBuffer();
    private static final CharBuffer PREDEFINED_AMP = CharBuffer.wrap("&").asReadOnlyBuffer();
    private static final CharBuffer PREDEFINED_APOS = CharBuffer.wrap("'").asReadOnlyBuffer();
    private static final CharBuffer PREDEFINED_QUOT = CharBuffer.wrap("\"").asReadOnlyBuffer();
    
    // ===== Constructors =====

    /**
     * Constructs a new XMLTokenizer with no publicId or systemId.
     * @param consumer the TokenConsumer that will receive tokens
     */
    public XMLTokenizer(TokenConsumer consumer) {
        this(consumer, null, null, false);
    }

    /**
     * Constructs a new XMLTokenizer for an external entity.
     * @param consumer the TokenConsumer that will receive tokens
     * @param publicId the public identifier of the entity, or null
     * @param systemId the system identifier of the entity, or null
     */
    public XMLTokenizer(TokenConsumer consumer, String publicId, String systemId) {
        this(consumer, publicId, systemId, false);
    }
    
    /**
     * Constructs a new XMLTokenizer.
     * @param consumer the TokenConsumer that will receive tokens
     * @param publicId the public identifier, or null
     * @param systemId the system identifier, or null
     * @param isExternalEntity true if parsing an external entity (text declaration)
     */
    public XMLTokenizer(TokenConsumer consumer, String publicId, String systemId, boolean isExternalEntity) {
        this.consumer = consumer;
        this.publicId = publicId;
        this.systemId = systemId;
        this.isExternalEntity = isExternalEntity;
        this.transitionTable = MiniStateTransitionBuilder.TRANSITION_TABLE;
        consumer.setLocator(this);
    }

    // ===== Public API =====
    
    /**
     * Package-private method to set initial context for entity expansion.
     * Used when creating a tokenizer for inline entity expansion.
     * 
     * @param initialState the initial state (typically TokenizerState.CONTENT or TokenizerState.DOCTYPE_INTERNAL)
     * @param parentLocator the parent locator for position inheritance
     */
    void setInitialContext(TokenizerState initialState, Locator2 parentLocator) {
        this.state = initialState;
        this.initState = InitState.XMLDECL;  // Skip BOM/XML declaration parsing
        
        if (parentLocator != null) {
            this.lineNumber = parentLocator.getLineNumber();
            this.columnNumber = parentLocator.getColumnNumber();
        }
    }
    
    /**
     * Receives and processes a buffer of bytes.
     * 
     * @param data the byte buffer to process
     * @throws SAXException if a parsing error occurs
     */
    public void receive(ByteBuffer data) throws SAXException {
        if (closed) {
            throw new IllegalStateException("Tokenizer is closed");
        }
        
        // Prepend byte underflow if present
        ByteBuffer combined = data;
        if (byteUnderflow != null && byteUnderflow.hasRemaining()) {
            combined = prependByteUnderflow(data);
        }
        
        // Process based on initialization state
        switch (initState) {
            case INIT:
                // Detect BOM
                if (!detectBOM(combined)) {
                    saveByteUnderflow(combined);
                    return;
                }
                initState = InitState.BOM_READ;
                // Fall through
                
            case BOM_READ:
                // Initialize decoder
                if (decoder == null) {
                    decoder = charset.newDecoder();
                    charBuffer = CharBuffer.allocate(Math.max(4096, combined.capacity() * 2));
                    charUnderflow = CharBuffer.allocate(4096);
                    charUnderflow.limit(0);
                }
                initState = InitState.XMLDECL;
                // Fall through
                
            case XMLDECL:
                // Parse XML/text declaration
                if (!parseXMLDeclaration(combined)) {
                    saveByteUnderflow(combined);
                    return;
                }
                state = TokenizerState.CONTENT;
                // Fall through to decode and tokenize
        }
        
        // Decode bytes to characters and tokenize
        decodeAndTokenize(combined);
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
            emitTokenWindow(miniState.getTokenType(), tokenStartPos, tokenLength, tokenStartColumn);
        }
    }
    
    /**
     * Resets the tokenizer to initial state.
     * 
     * @throws SAXException if an error occurs
     */
    public void reset() throws SAXException {
        initState = InitState.INIT;
        state = TokenizerState.INIT;
        miniState = MiniState.READY;
        charset = StandardCharsets.UTF_8;
        decoder = null;
        version = "1.0";
        standalone = false;
        bomCharset = null;
        lastCharSeen = '\0';
        lineNumber = 1;
        columnNumber = 0;
        byteUnderflow = null;
        byteWorkingBuffer = null;
        charUnderflow = null;
        charBuffer = null;
        closed = false;
    }

    // ===== Locator2 Implementation =====
    
    @Override
    public String getPublicId() {
        return publicId;
    }

    @Override
    public String getSystemId() {
        return systemId;
    }

    @Override
    public int getLineNumber() {
        return (int) lineNumber;
    }

    @Override
    public int getColumnNumber() {
        return (int) columnNumber;
    }

    @Override
    public String getXMLVersion() {
        return version;
    }

    @Override
    public String getEncoding() {
        return charset.name();
    }
    
    // ===== Additional Setter Methods =====
    
    /**
     * Sets the public identifier for error reporting.
     * 
     * @param publicId the public identifier, or null if not available
     */
    public void setPublicId(String publicId) {
        this.publicId = publicId;
    }
    
    /**
     * Sets the system identifier for error reporting.
     * 
     * @param systemId the system identifier, or null if not available
     */
    public void setSystemId(String systemId) {
        this.systemId = systemId;
    }
    
    /**
     * Returns whether the document declares itself as standalone.
     * 
     * @return true if the document is standalone, false otherwise
     */
    public boolean isStandalone() {
        return standalone;
    }
    

    /**
     * Checks if an entity name is a predefined entity and returns its index into PREDEFINED_ENTITY_TEXT.
     * Returns -1 if not a predefined entity.
     * 
     * @param nameStart start of entity name in charBuffer
     * @param nameEnd end of entity name in charBuffer
     * @return index into PREDEFINED_ENTITY_TEXT (0=&, 1=<, 2=>, 3=', 4=") or -1 if not predefined
     */
    private int getPredefinedEntityIndex(int nameStart, int nameEnd) {
        int nameLen = nameEnd - nameStart;
        if (nameLen == 2) {
            char c1 = charBuffer.get(nameStart);
            char c2 = charBuffer.get(nameStart + 1);
            if (c1 == 'l' && c2 == 't') {
                return 1;  // &lt; -> '<'
            } else if (c1 == 'g' && c2 == 't') {
                return 2;  // &gt; -> '>'
            }
        } else if (nameLen == 3) {
            char c1 = charBuffer.get(nameStart);
            char c2 = charBuffer.get(nameStart + 1);
            char c3 = charBuffer.get(nameStart + 2);
            if (c1 == 'a' && c2 == 'm' && c3 == 'p') {
                return 0;  // &amp; -> '&'
            }
        } else if (nameLen == 4) {
            char c1 = charBuffer.get(nameStart);
            char c2 = charBuffer.get(nameStart + 1);
            char c3 = charBuffer.get(nameStart + 2);
            char c4 = charBuffer.get(nameStart + 3);
            if (c1 == 'a' && c2 == 'p' && c3 == 'o' && c4 == 's') {
                return 3;  // &apos; -> '\''
            } else if (c1 == 'q' && c2 == 'u' && c3 == 'o' && c4 == 't') {
                return 4;  // &quot; -> '"'
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
     * Checks if an entity reference is a predefined entity.
     * 
     * @param nameStart start of entity name in charBuffer
     * @param nameEnd end of entity name in charBuffer
     * @return Token.ENTITYREF if predefined, Token.GENERALENTITYREF otherwise
     */
    private Token resolvePredefinedEntity(int nameStart, int nameEnd) {
        int nameLen = nameEnd - nameStart;
        if (nameLen == 3) {
            char c1 = charBuffer.get(nameStart);
            char c2 = charBuffer.get(nameStart + 1);
            char c3 = charBuffer.get(nameStart + 2);
            if (c1 == 'a' && c2 == 'm' && c3 == 'p') {
                return Token.ENTITYREF;  // &amp;
            }
        } else if (nameLen == 2) {
            char c1 = charBuffer.get(nameStart);
            char c2 = charBuffer.get(nameStart + 1);
            if (c1 == 'l' && c2 == 't') {
                return Token.ENTITYREF;  // &lt;
            } else if (c1 == 'g' && c2 == 't') {
                return Token.ENTITYREF;  // &gt;
            }
        } else if (nameLen == 4) {
            char c1 = charBuffer.get(nameStart);
            char c2 = charBuffer.get(nameStart + 1);
            char c3 = charBuffer.get(nameStart + 2);
            char c4 = charBuffer.get(nameStart + 3);
            if (c1 == 'a' && c2 == 'p' && c3 == 'o' && c4 == 's') {
                return Token.ENTITYREF;  // &apos;
            } else if (c1 == 'q' && c2 == 'u' && c3 == 'o' && c4 == 't') {
                return Token.ENTITYREF;  // &quot;
            }
        }
        return Token.GENERALENTITYREF;
    }
    
    /**
     * Emits an ENTITYREF token with a window into the predefined entity text.
     * 
     * @param nameStart start of entity name in charBuffer
     * @param nameEnd end of entity name in charBuffer
     * @param columnAtStart column number at the start of the entity reference
     */
    private void emitResolvedEntityRef(int nameStart, int nameEnd, long columnAtStart) throws SAXException {
        int nameLen = nameEnd - nameStart;
        int predefinedIndex = -1;
        
        if (nameLen == 3 && charBuffer.get(nameStart) == 'a') {
            predefinedIndex = 0;  // &amp; -> '&'
        } else if (nameLen == 2) {
            char c1 = charBuffer.get(nameStart);
            if (c1 == 'l') {
                predefinedIndex = 1;  // &lt; -> '<'
            } else if (c1 == 'g') {
                predefinedIndex = 2;  // &gt; -> '>'
            }
        } else if (nameLen == 4) {
            char c1 = charBuffer.get(nameStart);
            if (c1 == 'a') {
                predefinedIndex = 3;  // &apos; -> '\''
            } else if (c1 == 'q') {
                predefinedIndex = 4;  // &quot; -> '"'
            }
        }
        
        if (predefinedIndex >= 0) {
            // Create a window into the predefined entity text
            CharBuffer window = PREDEFINED_ENTITY_TEXT.duplicate();
            window.position(predefinedIndex);
            window.limit(predefinedIndex + 1);
            consumer.receive(Token.ENTITYREF, window);
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
     * Decodes bytes to characters and tokenizes them.
     */
    private void decodeAndTokenize(ByteBuffer buffer) throws SAXException {
        // Ensure charBuffer is large enough
        int neededCapacity = (charUnderflow != null ? charUnderflow.remaining() : 0) + buffer.remaining() * 2;
        if (charBuffer == null || charBuffer.capacity() < neededCapacity) {
            int newCapacity = Math.max(neededCapacity, 4096);
            CharBuffer newBuffer = CharBuffer.allocate(newCapacity);
            if (charBuffer != null && charBuffer.hasRemaining()) {
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
        
        // Decode bytes to characters
        CoderResult result = decoder.decode(buffer, charBuffer, false);
        charBuffer.flip();
        
        // Normalize line endings
        normalizeLineEndings(charBuffer);
        
        // Save byte underflow
        if (buffer.hasRemaining()) {
            saveByteUnderflow(buffer);
        } else if (byteUnderflow != null) {
            byteUnderflow.clear();
            byteUnderflow.limit(0);
        }
        
        // Tokenize
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
     * Main tokenization loop using state trie architecture.
     */
    private void tokenize() throws SAXException {
        tokenStartColumn = (int) columnNumber;
        tokenStartPos = charBuffer.position();
        
        int pos = charBuffer.position();
        int limit = charBuffer.limit();
        
        while (pos < limit) {
            char c = charBuffer.get(pos);
            
            // Classify character
            CharClass cc = CharClass.classify(c, state, miniState);
            
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
                    shouldContinue = (cc == CharClass.NAME_START_CHAR || cc == CharClass.NAME_CHAR || cc == CharClass.DIGIT);
                } else if (miniState.isGreedyAccumulation()) {
                    shouldContinue = !shouldStopAccumulating(cc, miniState);
                }
                
                if (shouldContinue) {
                    // Continue accumulating - update line/column and advance
                    if (c == '\n') {
                        lineNumber++;
                        columnNumber = 0;
                    } else {
                        columnNumber++;
                    }
                    pos++;
                    continue;
                } else {
                    // Stop accumulating - emit the accumulated token
                    // Then fall through to let the trie handle the delimiter
                    int tokenLength = pos - tokenStartPos;
                    if (tokenLength > 0) {
                        Token tokenType = miniState.getTokenType();
                        if (tokenType != null) {
                            emitTokenWindow(tokenType, tokenStartPos, tokenLength, tokenStartColumn);
                            columnNumber += tokenLength;
                        }
                    }
                    
                    // Update tokenStartPos to start of delimiter, and reset miniState to READY
                    // The trie will then process the delimiter from READY state
                    tokenStartColumn = (int) columnNumber;
                    tokenStartPos = pos;
                    miniState = MiniState.READY;
                    // Don't advance pos - reprocess delimiter through the trie from READY
                    continue;
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
                throw fatalError("Unexpected character '" + c + "' (" + cc + ") in " + state + ":" + miniState);
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
                        columnNumber++;  // Simplified - may be 2 for supplementary chars
                        tokenStartColumn = (int) columnNumber;
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
                                    columnNumber++;  // Always 1 character
                                } else {
                                    // General entity - emit name only
                                    emitTokenWindow(Token.GENERALENTITYREF, tokenStart, tokenLength, tokenStartColumn);
                                    columnNumber += tokenLength;
                                }
                            } else {
                                // Parameter entity - emit name only
                                emitTokenWindow(Token.PARAMETERENTITYREF, tokenStart, tokenLength, tokenStartColumn);
                                columnNumber += tokenLength;
                            }
                        }
                        tokenStartColumn = (int) columnNumber;
                    } else if (i == 0) {
                        // First token
                        tokenStart = tokenStartPos;
                        tokenEnd = excludeTrigger ? pos : posAfterChar;
                        int tokenLength = tokenEnd - tokenStart;
                        if (tokenLength > 0) {
                            emitTokenWindow(token, tokenStart, tokenLength, tokenStartColumn);
                            columnNumber += tokenLength;
                        }
                        tokenStartColumn = (int) columnNumber;
                    } else {
                        // Subsequent tokens: emit the trigger character(s)
                        tokenStart = pos;
                        tokenEnd = posAfterChar;
                        int tokenLength = tokenEnd - tokenStart;
                        if (tokenLength > 0) {
                            emitTokenWindow(token, tokenStart, tokenLength, tokenStartColumn);
                            columnNumber += tokenLength;
                        }
                        tokenStartColumn = (int) columnNumber;
                    }
                }
                
                // Set tokenStartPos for next token
                // If transitioning to accumulating state, start at the trigger char (which will be accumulated)
                // Otherwise, start after everything we just emitted
                if (excludeTrigger) {
                    tokenStartPos = pos;  // Trigger char will be first char of accumulated token
                } else {
                    tokenStartPos = posAfterChar;
                }
            }
            
            // Advance position
            pos = posAfterChar;
            
            // Change state if specified
            if (transition.stateToChangeTo != null) {
                state = transition.stateToChangeTo;
            }
            
            // Move to next mini-state
            miniState = transition.nextMiniState;
            
            // Update column if we haven't already (and not entering greedy accumulation)
            if (!miniState.isGreedyAccumulation() && 
                (transition.tokensToEmit == null || transition.tokensToEmit.isEmpty())) {
                if (c == '\n') {
                    lineNumber++;
                    columnNumber = 0;
                } else {
                    columnNumber++;
                }
            }
        }
        
        // Update charBuffer position to reflect how much we consumed
        charBuffer.position(pos);
        
        // Buffer exhausted - flush greedy tokens
        if (miniState.isGreedyAccumulation()) {
            int tokenLength = pos - tokenStartPos;
            if (tokenLength > 0) {
                emitTokenWindow(miniState.getTokenType(), tokenStartPos, tokenLength, tokenStartColumn);
                columnNumber += tokenLength;
            }
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
                case CONTENT:
                    return cc == CharClass.LT || cc == CharClass.AMP || cc == CharClass.WHITESPACE;
                case ATTR_VALUE_APOS:
                case ATTR_VALUE_QUOT:
                    return cc == CharClass.LT || cc == CharClass.AMP || cc == CharClass.GT ||
                           cc == CharClass.APOS || cc == CharClass.QUOT;
                case DOCTYPE_QUOTED_APOS:
                case DOCTYPE_QUOTED_QUOT:
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
    private void emitTokenWindow(Token token, int start, int length, int column) throws SAXException {
        // Check if this is a NAME token in DOCTYPE that should be a keyword
        if (token == Token.NAME && state == TokenizerState.DOCTYPE) {
            Token keywordToken = checkDOCTYPEKeyword(start, length);
            if (keywordToken != null) {
                token = keywordToken;
            }
        }
        
        // Check if this is a markup declaration name in DOCTYPE_INTERNAL
        if (token == Token.NAME && state == TokenizerState.DOCTYPE_INTERNAL && miniState == MiniState.ACCUMULATING_MARKUP_NAME) {
            Token markupToken = checkMarkupDeclaration(start, length);
            if (markupToken != null) {
                token = markupToken;
            }
        }
        
        CharBuffer window = charBuffer.duplicate();
        window.position(start);
        window.limit(start + length);
        
        // Update column tracking before emit (Locator contract: position AFTER event)
        long savedColumn = columnNumber;
        columnNumber = column + length;
        
        consumer.receive(token, window);
        
        // Restore for continued processing
        columnNumber = savedColumn;
    }
    
    /**
     * Creates a fatal error exception.
     */
    private SAXException fatalError(String message) throws SAXException {
        return consumer.fatalError(message);
    }
    
    // ===== Buffer Management =====
    
    /**
     * Prepends byte underflow to incoming data.
     */
    private ByteBuffer prependByteUnderflow(ByteBuffer data) {
        int totalSize = byteUnderflow.remaining() + data.remaining();
        
        if (byteWorkingBuffer == null || byteWorkingBuffer.capacity() < totalSize) {
            byteWorkingBuffer = ByteBuffer.allocate(Math.max(totalSize, 4096));
        } else {
            byteWorkingBuffer.clear();
        }
        
        byteWorkingBuffer.put(byteUnderflow);
        byteWorkingBuffer.put(data);
        byteWorkingBuffer.flip();
        
        byteUnderflow.clear();
        byteUnderflow.limit(0);
        
        return byteWorkingBuffer;
    }
    
    /**
     * Saves remaining bytes to underflow buffer.
     */
    private void saveByteUnderflow(ByteBuffer buffer) {
        if (!buffer.hasRemaining()) {
            if (byteUnderflow != null) {
                byteUnderflow.clear();
                byteUnderflow.limit(0);
            }
            return;
        }
        
        if (byteUnderflow == null || byteUnderflow.capacity() < buffer.remaining()) {
            byteUnderflow = ByteBuffer.allocate(Math.max(buffer.remaining() * 2, 1024));
        } else {
            byteUnderflow.clear();
        }
        
        byteUnderflow.put(buffer);
        byteUnderflow.flip();
    }
    
    // ===== Initialization Methods (BOM detection, XML declaration parsing) =====
    
    /**
     * Detects and processes a BOM (Byte Order Mark) if present.
     * 
     * @param buffer the buffer to read from
     * @return true if we can proceed, false if we need more data
     */
    private boolean detectBOM(ByteBuffer buffer) {
        // We need at least 2 bytes to detect UTF-16, 3 for UTF-8
        if (buffer.remaining() < 2) {
            return false; // Need more data
        }

        buffer.mark();
        int b1 = buffer.get() & 0xFF;
        int b2 = buffer.get() & 0xFF;

        // Check for UTF-16 BOMs
        if (b1 == 0xFE && b2 == 0xFF) {
            charset = StandardCharsets.UTF_16BE;
            bomCharset = charset;
            return true;
        } else if (b1 == 0xFF && b2 == 0xFE) {
            charset = StandardCharsets.UTF_16LE;
            bomCharset = charset;
            return true;
        }

        // Check for UTF-8 BOM (need 3 bytes)
        if (buffer.hasRemaining()) {
            int b3 = buffer.get() & 0xFF;
            if (b1 == 0xEF && b2 == 0xBB && b3 == 0xBF) {
                charset = StandardCharsets.UTF_8;
                bomCharset = charset;
                return true;
            }
            buffer.reset(); // No BOM, rewind
            return true;
        }

        // Only have 2 bytes, might be UTF-8 BOM
        buffer.reset();
        return false; // Need more data
    }
    
    /**
     * Parses the XML/Text declaration if present.
     * 
     * @param buffer the byte buffer to read from
     * @return true if we can proceed, false if we need more data
     * @throws SAXException if there's a charset mismatch or other error
     */
    private boolean parseXMLDeclaration(ByteBuffer buffer) throws SAXException {
        // Decode bytes into characters
        charBuffer.clear();
        int initialBytePos = buffer.position();
        
        CoderResult result = decoder.decode(buffer, charBuffer, false);
        charBuffer.flip();
        
        // Normalize line endings
        normalizeLineEndings(charBuffer);
        
        // Check if we have enough characters to decide
        if (charBuffer.remaining() < 5) {
            buffer.position(initialBytePos);
            return false;
        }
        
        // Check if it starts with "<?xml"
        charBuffer.mark();
        if (charBuffer.get() != '<' || charBuffer.get() != '?' ||
            charBuffer.get() != 'x' || charBuffer.get() != 'm' ||
            charBuffer.get() != 'l') {
            // No XML declaration - save for tokenization
            charBuffer.reset();
            // Save to underflow BEFORE updating location (which consumes the buffer)
            saveCharUnderflow(charBuffer);
            // Now update location tracking (this will consume the charUnderflow buffer)
            if (charUnderflow != null) {
                charUnderflow.mark();
                updateLocationFromChars(charUnderflow);
                charUnderflow.reset();
            }
            return true;
        }
        
        // Find the end "?>"
        boolean foundEnd = false;
        int searchLimit = Math.min(charBuffer.remaining(), 512);
        for (int i = 0; i < searchLimit - 1; i++) {
            if (charBuffer.get(charBuffer.position() + i) == '?' &&
                charBuffer.get(charBuffer.position() + i + 1) == '>') {
                foundEnd = true;
                charBuffer.position(charBuffer.position() + i + 2);
                break;
            }
        }
        
        if (!foundEnd) {
            if (charBuffer.remaining() >= 512) {
                charBuffer.reset();
                updateLocationFromChars(charBuffer);
                saveCharUnderflow(charBuffer);
                throw fatalError("XML declaration too long (>512 characters)");
            }
            buffer.position(initialBytePos);
            return false;
        }
        
        // Process declaration content (simplified - just skip for now)
        // Full implementation would parse version, encoding, standalone
        charBuffer.reset();
        updateLocationFromChars(charBuffer);
        
        // Save any remaining characters
        if (charBuffer.hasRemaining()) {
            saveCharUnderflow(charBuffer);
        }
        
        return true;
    }
    
    /**
     * Normalizes line endings (CRLF->LF, CR->LF) in-place.
     */
    private void normalizeLineEndings(CharBuffer buffer) {
        int readPos = buffer.position();
        int writePos = readPos;
        int limit = buffer.limit();
        
        // Handle straddle case: previous buffer ended with CR, this starts with LF
        if (lastCharSeen == '\r' && readPos < limit) {
            char firstChar = buffer.get(readPos);
            if (firstChar == '\n') {
                readPos++;
            }
        }
        
        while (readPos < limit) {
            char c = buffer.get(readPos);
            if (c == '\r') {
                // Check if next char is \n
                if (readPos + 1 < limit && buffer.get(readPos + 1) == '\n') {
                    // CRLF -> LF
                    buffer.put(writePos++, '\n');
                    readPos += 2;
                    lastCharSeen = '\n';
                } else {
                    // CR alone -> LF
                    buffer.put(writePos++, '\n');
                    readPos++;
                    lastCharSeen = '\r';
                }
            } else {
                if (writePos != readPos) {
                    buffer.put(writePos, c);
                }
                writePos++;
                readPos++;
                lastCharSeen = c;
            }
        }
        
        buffer.limit(writePos);
        buffer.position(buffer.position());
    }
    
    /**
     * Updates line and column numbers by consuming characters from the buffer.
     */
    private void updateLocationFromChars(CharBuffer buffer) {
        while (buffer.hasRemaining()) {
            char c = buffer.get();
            if (c == '\n') {
                lineNumber++;
                columnNumber = 0;
            } else {
                columnNumber++;
            }
        }
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

/*
 * ExternalEntityDecoder.java
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

package org.bluezoo.gonzalez;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.util.Objects;
import org.bluezoo.util.CompositeByteBuffer;
import org.xml.sax.SAXException;

/**
 * Decodes byte streams from external entities into character streams for tokenization.
 * 
 * <p>This class handles:
 * <ul>
 * <li>BOM (Byte Order Mark) detection</li>
 * <li>Charset decoding with underflow handling</li>
 * <li>Line-ending normalization</li>
 * <li>Location tracking (line/column numbers)</li>
 * </ul>
 * 
 * <p>Decoded character data is fed to an {@link Tokenizer} via its
 * {@code receive(CharBuffer)} method.
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class ExternalEntityDecoder {
    
    // ===== Configuration and Document Metadata =====
    
    /**
     * The character set used to decode incoming bytes into characters.
     */
    private Charset charset = StandardCharsets.UTF_8;
    
    // ===== Tokenizer Integration =====
    
    /**
     * Whether this decoder is for an external entity (vs. the document entity).
     * External entities have text declarations (no standalone attribute allowed),
     * while the document entity has an XML declaration (standalone attribute allowed).
     */
    private final boolean isExternalEntity;
    
    /**
     * Whether this entity is being processed as XML 1.1 (vs. XML 1.0).
     * Affects character validity rules and line normalization.
     * Set when XML/text declaration specifies version="1.1".
     */
    private boolean xml11 = false;
    
    // ===== Buffers =====
    
    /**
     * Composite byte buffer that manages underflow and incoming data.
     * Provides a unified view over both buffers for decoding.
     */
    private CompositeByteBuffer compositeByteBuffer;
    
    /**
     * Character decoder for the current charset.
     */
    private CharsetDecoder decoder;
    
    /**
     * Working buffer for decoded character data.
     * Reused to avoid allocation on every receive() call.
     */
    private CharBuffer charBuffer;
    
    /**
     * Tokenizer that consumes decoded characters.
     * Initialized in the correct post-declaration state before EED is used.
     */
    private final Tokenizer tokenizer;
    
    // ===== State =====
    
    /**
     * Decoder state for tracking processing phase.
     */
    private enum State {
        INIT,           // Initial state
        SEEN_BOM,       // BOM detected or no BOM, ready to check for XMLDecl/TextDecl
        CONTENT,        // Processing content with main tokenizer
        CLOSED          // Decoder has been closed, cannot receive more data
    }
    
    private State state = State.INIT;
    
    /**
     * XMLDecl or TextDecl parser (created when entering CHECK_DECL state).
     */
    private DeclParser declParser;

    /**
     * Byte position at start of document.
     */
    private int startDoc;
    
    /**
     * Byte position at start of XMLDecl/TextDecl.
     */
    private int startDecl;
    
    /**
     * The last character read in the decoded character buffer.
     * If the last character is CR, then a following LF will be consumed as
     * part of line-end normalization.
     */
    private char lastChar = '\u0000';
    
    // ===== Constructor =====
    
    /**
     * Creates a new external entity decoder.
     * 
     * @param tokenizer the tokenizer (already in correct post-declaration state) to receive decoded characters
     * @param publicId public identifier for this entity (may be null)
     * @param systemId system identifier for this entity (may be null)
     * @param isExternalEntity true if this is an external parsed entity, false for document entity
     * @param initialCharset the initial charset to use for decoding (before BOM or declaration), or null for UTF-8
     */
    public ExternalEntityDecoder(Tokenizer tokenizer, String publicId, String systemId, boolean isExternalEntity, Charset initialCharset) {
        this.tokenizer = Objects.requireNonNull(tokenizer);
        tokenizer.publicId = publicId;
        tokenizer.systemId = systemId;
        this.isExternalEntity = isExternalEntity;
        this.charset = (initialCharset != null) ? initialCharset : StandardCharsets.UTF_8;
        declParser = isExternalEntity ? new TextDeclParser() : new XMLDeclParser();
        compositeByteBuffer = new CompositeByteBuffer();
    }
    
    /**
     * Creates a new external entity decoder with UTF-8 as the initial charset.
     * 
     * @param tokenizer the tokenizer (already in correct post-declaration state) to receive decoded characters
     * @param publicId public identifier for this entity (may be null)
     * @param systemId system identifier for this entity (may be null)
     * @param isExternalEntity true if this is an external parsed entity, false for document entity
     */
    public ExternalEntityDecoder(Tokenizer tokenizer, String publicId, String systemId, boolean isExternalEntity) {
        this(tokenizer, publicId, systemId, isExternalEntity, null);
    }
    
    // ===== Accessors =====
    
    /**
     * Returns whether this decoder is for an external parsed entity.
     * 
     * @return true if this is an external entity, false if it's the document entity
     */
    public boolean isExternalEntity() {
        return isExternalEntity;
    }
    
    // ===== Public API =====
    
    /**
     * Receives a buffer of bytes to decode and tokenize.
     * 
     * @param data the byte buffer to process
     * @throws SAXException if a parsing error occurs
     */
    public void receive(ByteBuffer data) throws SAXException {
        if (state == State.CLOSED) {
            throw new IllegalStateException("Decoder is closed");
        }
        
        // Set up composite buffer with new data
        compositeByteBuffer.put(data);
        compositeByteBuffer.flip(); // ready to read
        
        boolean decoded = false;
        // Process based on state
        switch (state) {
            case INIT:
                // Detect BOM
                if (!parseBOM()) {
                    // Need more bytes to determine if there's a BOM
                    compositeByteBuffer.compact();
                    return;
                }
                // detectBOM has set state to SEEN_BOM
                // Fall through
                
            case SEEN_BOM:
                // Check for XMLDecl/TextDecl
                if (!parseDeclaration()) {
                    // Need more data
                    compositeByteBuffer.compact();
                    return;
                }
                // Declaration handled, composite buffer repositioned, state is now CONTENT
                // If charBuffer has decoded content, mark it as decoded so CONTENT doesn't re-decode
                // But if charBuffer is empty (e.g., MALFORMED with 0 chars), don't set decoded flag
                // so that CONTENT will decode the remaining bytes and catch any errors
                
                // charBuffer is currently in read mode:
                // - If declaration was found: position at end of declaration, limit at end of data
                // - If no declaration: position at 0 (parser reset to mark), limit at end of data
                // Convert to write mode for tokenizer: compact to move remaining data to start
                if (charBuffer.remaining() > 0) {
                    charBuffer.compact(); // Now in write mode with remaining data at start
                    decoded = true;
                } else {
                    // No remaining data after declaration (or no declaration and no data)
                    charBuffer.clear(); // Put in write mode with position=0
                    decoded = false;
                }
                // Fall through to process remaining content
                
            case CONTENT:
                // Decode and send decoded chars to Tokenizer
                if (!decoded) {
                    int bytesToDecode = compositeByteBuffer.remaining();
                    
                    // Check if charBuffer exists and has enough space for the decode
                    // charBuffer arrives in write mode with underflow at position 0, position at end of underflow
                    if (charBuffer == null) {
                        // First time - allocate fresh buffer
                        charBuffer = CharBuffer.allocate(Math.max(bytesToDecode, 4096));
                    } else {
                        int underflowSize = charBuffer.position(); // position points to end of underflow
                        int availableSpace = charBuffer.remaining(); // space after position
                        
                        // Decoded chars are never larger than input bytes, but we need some margin
                        if (availableSpace < bytesToDecode) {
                            // Need to reallocate - preserve underflow
                            int newCapacity = Math.max(underflowSize + bytesToDecode + 1024, 4096);
                            CharBuffer newCharBuffer = CharBuffer.allocate(newCapacity);
                            
                            // Copy underflow from old buffer
                            charBuffer.flip(); // switch to read mode to read underflow
                            newCharBuffer.put(charBuffer); // copy underflow
                            charBuffer = newCharBuffer;
                            // charBuffer is now in write mode with underflow copied, position at end of underflow
                        }
                        // else: enough space, charBuffer already in write mode ready for decode
                    }
                    
                    // Decode into charBuffer at current position (after any underflow)
                    CoderResult result = compositeByteBuffer.decode(decoder, charBuffer, false);
                    
                    // Check for decoding errors (malformed or unmappable)
                    if (result.isError()) {
                        if (result.isMalformed()) {
                            throw tokenizer.fatalError("Malformed byte sequence in encoding " + charset.name() + 
                                " (length: " + result.length() + ")");
                        } else if (result.isUnmappable()) {
                            throw tokenizer.fatalError("Unmappable byte sequence in encoding " + charset.name() + 
                                " (length: " + result.length() + ")");
                        }
                    }
                    
                    // Normalize line endings (charBuffer is in write mode, normalize from 0 to position)
                    normalizeLineEndings();
                    
                    // Flip to read mode before passing to tokenizer
                    charBuffer.flip();
                    
                    // Pass to tokenizer (buffer in read mode, tokenizer will consume as much as possible)
                    tokenizer.receive(charBuffer);
                    
                    // Compact to preserve any unconsumed data (underflow)
                    // After compact: underflow at position 0, position at end of underflow
                    charBuffer.compact();

                    // Save any byte underflow
                    compositeByteBuffer.compact();
                } else {
                    // Decoded data from declaration parsing - already in write mode after compact
                    // Flip to read mode before passing to tokenizer
                    charBuffer.flip();
                    
                    // Pass to tokenizer (buffer in read mode, tokenizer will consume as much as possible)
                    tokenizer.receive(charBuffer);
                    
                    // Compact to preserve any unconsumed data (underflow)
                    // After compact: underflow at position 0, position at end of underflow
                    charBuffer.compact();
                    
                    // Save any byte underflow from declaration parsing
                    compositeByteBuffer.compact();
                }
                break;
                
            case CLOSED:
                throw new IllegalStateException("Decoder is closed");
        }
    }
    
    /**
     * Closes the decoder and flushes any remaining data to the tokenizer.
     * 
     * @throws SAXException if a parsing error occurs
     */
    public void close() throws SAXException {
        if (state == State.CLOSED) {
            return;
        }
        
        // Close the tokenizer
        tokenizer.close();
        
        // Mark as closed
        state = State.CLOSED;
    }
    
    /**
     * Resets the decoder to initial state for reuse.
     * Clears all buffers and resets state to INIT.
     * Resets to UTF-8 encoding (call setInitialCharset if different encoding is needed).
     */
    public void reset() {
        state = State.INIT;
        charset = StandardCharsets.UTF_8;
        xml11 = false; // Reset to XML 1.0 mode
        decoder = null;
        compositeByteBuffer = new CompositeByteBuffer();
        charBuffer = null;
    }
    
    /**
     * Sets the initial charset for decoding (before BOM or XML/text declaration is processed).
     * This should be called after reset() and before receive() if the application knows
     * the encoding (e.g., from HTTP headers or InputSource.getEncoding()).
     * 
     * @param initialCharset the initial charset, or null for UTF-8
     */
    public void setInitialCharset(Charset initialCharset) {
        if (state != State.INIT) {
            throw new IllegalStateException("Cannot set initial charset after decoding has started");
        }
        this.charset = (initialCharset != null) ? initialCharset : StandardCharsets.UTF_8;
    }
    
    // ===== BOM Detection =====
    
    /**
     * Detects and consumes a BOM (Byte Order Mark) if present.
     * We support: UTF-16LE, UTF-16BE, UTF-8. UTF-32 is NOT required to be supported.
     *
     * @return true if BOM detection is complete (BOM found or definitely absent),
     *         false if more bytes are needed
     */
    private boolean parseBOM() {
        startDoc = compositeByteBuffer.position();
        int len = compositeByteBuffer.remaining();
        if (len < 2) {
            return false; // Need at least 2 bytes to detect BOM
        }
        
        int pos = compositeByteBuffer.position();
        int b0 = compositeByteBuffer.get(pos) & 0xFF;
        int b1 = compositeByteBuffer.get(pos + 1) & 0xFF;
        
        if (b0 == 0xFE && b1 == 0xFF) {
            // UTF-16 BE BOM: FE FF
            charset = StandardCharsets.UTF_16BE;
            tokenizer.encoding = charset.name();
            compositeByteBuffer.position(pos + 2); // position after BOM
        } else if (b0 == 0xFF && b1 == 0xFE) {
            // UTF-16 LE BOM: FF FE
            charset = StandardCharsets.UTF_16LE;
            tokenizer.encoding = charset.name();
            compositeByteBuffer.position(pos + 2); // position after BOM
        } else if (b0 == 0xEF && b1 == 0xBB) {
            if (len >= 3) {
                int b2 = compositeByteBuffer.get(pos + 2) & 0xFF;
                if (b2 == 0xBF) {
                    // UTF-8 BOM: EF BB BF
                    charset = StandardCharsets.UTF_8;
                    tokenizer.encoding = charset.name();
                    compositeByteBuffer.position(pos + 3); // position after BOM
                }
            } else {
                return false; // Need 3 bytes to rule out UTF-8 BOM
            }
        }
        
        startDecl = compositeByteBuffer.position();
        decoder = charset.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT);
        state = State.SEEN_BOM;
        return true;
    }
    
    
    /**
     * Checks for and handles XMLDecl/TextDecl using dedicated parsers.
     * Implements the repositioning algorithm for charset switching.
     * 
     * After this method completes successfully:
     * - Composite buffer is positioned after the declaration (or after BOM if no decl)
     * - Char buffer is positioned after the declaration
     * - Decoder is set to correct charset
     * - Ready to continue decoding/tokenizing
     * 
     * @return true if ready to proceed to CONTENT, false if need more data
     * @throws SAXException if parsing or repositioning fails
     */
    private boolean parseDeclaration() throws SAXException {
        int remaining = compositeByteBuffer.remaining();
        if (charBuffer == null || charBuffer.capacity() < remaining) {
            // Allocate charBuffer
            charBuffer = CharBuffer.allocate(Math.max(remaining, 1024));
        }
        charBuffer.clear();
        CoderResult result = compositeByteBuffer.decode(decoder, charBuffer, false);
        // Save the actual decode position (bytes consumed) before any repositioning
        int actualDecodePosition = compositeByteBuffer.position();
        charBuffer.flip(); // ready for reading
        
        // Note: We do NOT check for MALFORMED or UNMAPPABLE here, because:
        // 1. The decoder decodes everything up to the first malformed byte
        // 2. The XMLDecl/TextDecl must be ASCII-compatible, so it will decode successfully
        // 3. If there's a malformed byte after the declaration, we'll read the encoding
        //    from the declaration and re-decode with the correct charset
        // 4. If malformed bytes are at the start (0 characters decoded), we skip declaration
        //    parsing and proceed to CONTENT where the malformed check will catch the error
        // 5. Only in CONTENT state (after declaration processing) do we check for malformed bytes
        
        // Normalize line endings before parsing
        // This modifies buffer content but preserves position (which is 0 after flip)
        normalizeLineEndings();
        
        // Ensure position is at 0 after normalization (in case normalization modified it)
        charBuffer.position(0);
        
        // Parse the declaration
        // The DeclParser is responsible for buffer state/positioning:
        // - On FAILURE: resets buffer to mark position (start, position 0)
        // - On OK: leaves buffer position at end of declaration
        int declLength = 0;
        if (charBuffer.hasRemaining()) {
            switch (declParser.receive(charBuffer)) {
                case UNDERFLOW: // can't decide yet
                    //System.err.println("*** parseDeclaration: receive is UNDERFLOW");
                    return false;
                case FAILURE: // no valid declaration seen
                    // Parser has reset buffer to mark position (start, position 0)
                    //System.err.println("*** parseDeclaration: receive is FAILURE");
                    // declLength remains 0
                    break;
                case OK: // valid declaration parsed
                    // Parser has left buffer position at end of declaration
                    //System.err.println("*** parseDeclaration: receive is OK");
                    declLength = charBuffer.position();
                    break;
            }
        }
        
        // The DeclParser manages buffer position:
        // - On FAILURE: resets to mark (position 0) - buffer is ready for content
        // - On OK: leaves position at end of declaration - buffer is ready for content after declaration
        // No need to adjust position here - parser has already set it correctly

        // If we got here there was either a valid declaration or no declaration        
        String declEncoding = declParser.getEncoding();
        String declVersion = declParser.getVersion();
        Boolean declStandalone = declParser.getStandalone();
        //System.err.println("*** parseDeclaration: declEncoding="+declEncoding);
        //System.err.println("*** parseDeclaration: declVersion="+declVersion);
        //System.err.println("*** parseDeclaration: declStandalone="+declStandalone);

        if (declVersion != null) {
            // Version handling per XML spec:
            // - If this is the main document, set the processor mode based on declared version
            // - If this is an external entity in a 1.0 document, ignore entity version (process in 1.0 mode)
            // - If this is an external entity in a 1.1 document, respect entity version
            
            boolean entityXml11 = "1.1".equals(declVersion);
            
            if (!isExternalEntity) {
                // Main document - sets the processor mode and document version
                tokenizer.version = declVersion;
                tokenizer.documentVersion = declVersion;  // Document version is set once
                xml11 = entityXml11;
                tokenizer.xml11 = xml11;
                // Notify consumer of XML version
                tokenizer.notifyXmlVersion(xml11);
            } else {
                // External entity
                // Check version compatibility against the DOCUMENT version, not the current entity version
                boolean documentXml11 = "1.1".equals(tokenizer.documentVersion);
                
                if (!documentXml11) {
                    // Document is 1.0
                    // Per XML 1.1 spec section 4.3.4: "It is a fatal error if an entity explicitly
                    // included specifies a version that is not the same as or older than the version
                    // of the document entity."
                    if (entityXml11) {
                        throw tokenizer.fatalError(
                            "XML 1.0 document cannot include XML 1.1 entity (version " + declVersion + ")");
                    }
                    // Process entity in 1.0 mode
                    xml11 = false;
                } else {
                    // Document is 1.1 - entity can be processed in its declared mode
                    tokenizer.version = declVersion;
                    xml11 = entityXml11;
                    tokenizer.xml11 = xml11;
                    // Notify consumer of XML version change
                    tokenizer.notifyXmlVersion(xml11);
                }
            }
        }
        if (declStandalone != null) {
            tokenizer.standalone = declStandalone;
        }
        
        // Reposition composite buffer
        int endDecl = startDecl + declLength;
        if (charset == StandardCharsets.UTF_16LE || charset == StandardCharsets.UTF_16BE) {
            // each character corresponds to 2 bytes
            endDecl = startDecl + (declLength * 2);
        }
        compositeByteBuffer.position(endDecl);

        // Update positions for declaration
        updatePositions(startDecl - startDoc, 0, declLength);

        if (declEncoding != null) {
            tokenizer.encoding = declEncoding;
            
            // Check if charset changed
            Charset newCharset;
            try {
                newCharset = Charset.forName(declEncoding);
            } catch (IllegalCharsetNameException | UnsupportedCharsetException e) {
                throw tokenizer.fatalError("Invalid or unsupported encoding: " + declEncoding);
            }
            
            if (!newCharset.equals(charset)) {
                // Switch charset
                charset = newCharset;
                decoder = charset.newDecoder();
                
                // re-decode into charBuffer
                // compositeByteBuffer is already positioned at endDecl (after declaration)
                // so we decode only the content after the declaration
                charBuffer.clear();
                result = compositeByteBuffer.decode(decoder, charBuffer, false);
                charBuffer.flip(); // ready for reading
        
                // Normalize line endings
                normalizeLineEndings();
                
                // After re-decode and flip(), position is at 0, limit is at end of decoded content
                // This is correct - we've already skipped the declaration by positioning
                // compositeByteBuffer to endDecl before decoding
            } else {
                // No charset change - restore position to actual decode position
                // so that compact() will correctly save any byte underflow
                compositeByteBuffer.position(actualDecodePosition);
            }
        } else {
            // No encoding in declaration - restore position to actual decode position
            compositeByteBuffer.position(actualDecodePosition);
        }
        
        state = State.CONTENT;
        return true;
    }
    
    /**
     * Normalizes line endings in the character buffer according to XML spec.
     * 
     * XML 1.0: Converts CR and CR LF to LF (U+000A).
     * XML 1.1: Also converts NEL (U+0085) and LS (U+2028) to LF.
     * 
     * <p>Works with buffer in either read or write mode:
     * <ul>
     * <li>Read mode: normalizes from position to limit</li>
     * <li>Write mode: normalizes from 0 to position</li>
     * </ul>
     */
    private void normalizeLineEndings() {
        // Determine range based on buffer mode
        // Read mode: position < limit, normalize from position to limit
        // Write mode: position <= capacity, limit == capacity, normalize from 0 to position
        int start, end;
        if (charBuffer.limit() < charBuffer.capacity()) {
            // Read mode: position and limit are meaningful
            start = charBuffer.position();
            end = charBuffer.limit();
        } else {
            // Write mode: normalize from 0 to position
            start = 0;
            end = charBuffer.position();
        }
        
        int dataEnd = end;
        for (int i = start; i < dataEnd; i++) {
            char c = charBuffer.get(i);
            
            if (c == '\r') {
                // CR
                charBuffer.put(i, '\n');
            } else if (c == '\n') {
                if (lastChar == '\r') {
                    // CR LF: skip the LF by shifting remaining data left
                    for (int j = i + 1; j < dataEnd; j++) {
                        charBuffer.put(j - 1, charBuffer.get(j));
                    }
                    dataEnd--;
                    // Update limit or position depending on mode
                    if (charBuffer.limit() < charBuffer.capacity()) {
                        charBuffer.limit(dataEnd); // Read mode
                    } else {
                        charBuffer.position(dataEnd); // Write mode
                    }
                }
            } else if (xml11 && c == '\u0085') {
                // NEL (Next Line) -> LF (XML 1.1 only)
                charBuffer.put(i, '\n');
            } else if (xml11 && c == '\u2028') {
                // LS (Line Separator) -> LF (XML 1.1 only)
                charBuffer.put(i, '\n');
            }
            lastChar = charBuffer.get(i); // Get potentially modified character
        }
    }
    
    /**
     * Updates line and column numbers for the tokenizer
     * for the BOM and the XML/text declaration
     */
    private void updatePositions(int bomLength, int pos, int limit) {
        tokenizer.charPosition += bomLength;
        tokenizer.columnNumber += bomLength;

        for (int i = pos; i < limit; i++) {
            char c = charBuffer.get(i);
            tokenizer.charPosition++;
            
            if (c == '\n') {
                tokenizer.lineNumber++;
                tokenizer.columnNumber = 0;
            } else {
                tokenizer.columnNumber++;
            }
        }
    }

}


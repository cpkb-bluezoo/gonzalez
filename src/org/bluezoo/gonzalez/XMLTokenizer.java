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
import org.xml.sax.SAXException;
import org.xml.sax.ext.Locator2;

/**
 * Handles tokenization for buffers of XML characters.
 * <p>
 * This class is a SAX Locator2 so it will keep track of the current line
 * and column number. It handles the initial charset determination via
 * the BOM and/or XML (Text) declaration, then will switch to character
 * mode, using its internal byte buffer thereafter as an underflow in
 * case incoming bytes could not be completely decoded to characters.
 * <p>
 * During XML declaration parsing the version, charset, and standalone
 * status are determined.
 * <p>
 * In character mode the tokenizer will act as a stream of tokens and
 * push each token to the parser.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class XMLTokenizer implements Locator2 {

    /**
     * The character set which will be used to decode incoming bytes
     * into characters.
     */
    private Charset charset = StandardCharsets.UTF_8;

    /**
     * The version of XML that this document represents.
     */
    private String version = "1.0";

    /**
     * Whether this document is standalone.
     */
    private boolean standalone;

    /**
     * The internal byte underflow buffer.
     * This will be used in 2 phases:
     * <ol>
     * <li>Before the BOM/XML declaration is completely parsed, if
     * there is an underflow and we need to wait for more data</li>
     * <li>After switching to character mode, if there was an
     * underflow decoding data given to the character buffer, it
     * will be stored here until the next invocation of receive</li>
     * </ol>
     * This buffer will always be kept ready for a read between
     * operations.
     */
    private ByteBuffer byteUnderflow;

    /**
     * The internal character underflow buffer.
     * This will only be allocated once the charset is determined.
     * Its presence indicates that we are now in character mode.
     * Tokens will be extracted from this buffer and pushed to the
     * parser until no more are possible. Then the underflow, if any,
     * is relocated to the start of the character buffer.
     * <p>
     * This buffer will always be kept ready for a read between
     * operations.
     */
    private CharBuffer charUnderflow;

    /**
     * The main character buffer.
     * The contents of this buffer are transient, but we will reuse the same
     * allocated buffer object if possible. It is used for storing the
     * decoded input ready for processing into tokens.
     */
    private CharBuffer charBuffer;

    /**
     * The character decoder.
     * This will be allocated once the charset is determined.
     */
    private CharsetDecoder decoder;

    /**
     * Line number. 1-based.
     */
    private long lineNumber = 1;

    /**
     * Column number. 0-based.
     */
    private long columnNumber;

    /**
     * Public ID of the document.
     */
    private String publicId;

    /**
     * System ID of the document.
     */
    private String systemId;

    static enum State {
        INIT,           // Initial state, detecting BOM
        BOM_READ,       // BOM detected (or determined not present), ready for XML decl
        XMLDECL,        // Parsing XML declaration in special character mode
        CHARACTERS      // Normal tokenization mode
    }

    /**
     * Tokenizer context - tracks what syntactic context we're in.
     * This determines whether to emit NAME or CDATA tokens.
     */
    static enum TokenizerContext {
        CONTENT,            // Element content (between tags) - emit CDATA for text
        ELEMENT_NAME,       // After '<' or '</' - emit NAME for element name
        ELEMENT_ATTRS,      // After element name - emit NAME for attribute names
        ATTR_VALUE,         // Inside attribute value (quoted) - emit CDATA for text
        COMMENT,            // Inside comment - emit CDATA for text
        CDATA_SECTION,      // Inside CDATA section - emit CDATA for text
        PI_TARGET,          // After '<?' - emit NAME for PI target
        PI_DATA,            // Inside PI data - emit CDATA for text
        DOCTYPE,            // Inside DOCTYPE declaration - emit NAME for keywords, handle quoted strings
        DOCTYPE_INTERNAL    // Inside DOCTYPE internal subset - emit NAME for keywords/names
    }

    private State state = State.INIT;
    private boolean closed;
    private TokenizerContext context = TokenizerContext.CONTENT;
    private TokenizerContext prevContext = TokenizerContext.CONTENT; // Track previous context for returning from ATTR_VALUE
    private char attrQuoteChar = '\0'; // Current attribute quote character (for tracking attr values)
    
    /**
     * The token consumer that will receive tokens.
     */
    private final TokenConsumer consumer;
    
    /**
     * The charset determined by the BOM (if present).
     * This is used to validate against the encoding attribute in the XML declaration.
     */
    private Charset bomCharset;
    
    /**
     * Position in the byte buffer after the XML declaration.
     * Used when we need to switch charsets and re-decode.
     */
    private int postXMLDeclBytePosition;
    
    /**
     * Last character seen from the previous receive().
     * Used to handle CR-LF normalization that straddles buffer boundaries.
     * Value is '\0' if no character has been seen yet, otherwise the last character.
     * Specifically, if this is '\r' (CR), and the next buffer starts with '\n' (LF),
     * we skip the LF.
     */
    private char lastCharSeen = '\0';

    /**
     * Constructs a new XMLTokenizer with no publicId or systemId.
     * @param consumer the TokenConsumer that will receive tokens
     */
    public XMLTokenizer(TokenConsumer consumer) {
        this(consumer, null, null);
    }

    /**
     * Constructs a new XMLTokenizer with the specified publicId
     * and systemId.
     * These are metadata about the document that can describe how
     * it was resolved as an entity.
     * @param consumer the TokenConsumer that will receive tokens
     * @param publicId the public identifier of the document
     * @param systemId the system identifier of the document
     */
    public XMLTokenizer(TokenConsumer consumer, String publicId, String systemId) {
        this.consumer = consumer;
        this.publicId = publicId;
        this.systemId = systemId;
        this.consumer.setLocator(this);
    }

    /**
     * Receivs data.
     * Multiple invocations of this method may occur supplying the
     * underlying byte data of the raw document content.
     * @param data a byte buffer ready for reading the content from
     */
    public void receive(ByteBuffer data) throws SAXException {
        if (closed) {
            throw new IllegalStateException();
        }
        
        // Prepend byte underflow if it exists
        ByteBuffer combined = data;
        if (byteUnderflow != null && byteUnderflow.hasRemaining()) {
            combined = prependByteUnderflow(data);
        }
        
        switch (state) {
            case INIT:
                // Try to detect BOM
                if (!detectBOM(combined)) {
                    // Not enough data, save as underflow and return
                    saveByteUnderflow(combined);
                    return;
                }
                state = State.BOM_READ;
                // Fall through
            case BOM_READ:
                // Allocate decoder for the charset determined by BOM (or default UTF-8)
                if (decoder == null) {
                    decoder = charset.newDecoder();
                    charBuffer = CharBuffer.allocate(Math.max(4096, combined.capacity() * 2));
                    charUnderflow = CharBuffer.allocate(4096);
                    charUnderflow.limit(0); // Empty, ready for reading
                }
                state = State.XMLDECL;
                // Fall through
            case XMLDECL:
                // Parse XML declaration in special character mode
                // This handles line-end normalization and locator tracking
                // but doesn't emit tokens yet
                if (!parseXMLDeclaration(combined)) {
                    // Not enough data, save as underflow and return
                    saveByteUnderflow(combined);
                    return;
                }
                // XML declaration parsed (or determined not present)
                // Now switch to normal tokenization mode
                state = State.CHARACTERS;
                // Fall through to process any remaining bytes or charUnderflow
            case CHARACTERS:
                // Decode the data into the character buffer and tokenize
                // If there are no more bytes but we have charUnderflow, tokenize that
                if (!combined.hasRemaining() && charUnderflow != null && charUnderflow.hasRemaining()) {
                    // Just tokenize the charUnderflow without decoding more bytes
                    charBuffer.clear();
                    charBuffer.put(charUnderflow);
                    charUnderflow.clear();
                    charUnderflow.limit(0);
                    charBuffer.flip();
                    tokenize();
                } else {
                    // Decode and tokenize normally
                    decodeAndTokenize(combined);
                }
                break;
        }
    }

    /**
     * Prepends the byte underflow to the incoming data.
     * @param data the new data buffer
     * @return a combined buffer with underflow prepended to data
     */
    private ByteBuffer prependByteUnderflow(ByteBuffer data) {
        int totalSize = byteUnderflow.remaining() + data.remaining();
        ByteBuffer combined = ByteBuffer.allocate(totalSize);
        combined.put(byteUnderflow);
        combined.put(data);
        combined.flip(); // Ready for reading
        // Clear the underflow
        byteUnderflow.clear();
        byteUnderflow.limit(0);
        return combined;
    }

    /**
     * Saves remaining bytes in the buffer as underflow for next receive().
     * @param buffer the buffer to save from
     */
    private void saveByteUnderflow(ByteBuffer buffer) {
        if (!buffer.hasRemaining()) {
            return;
        }
        if (byteUnderflow == null) {
            byteUnderflow = ByteBuffer.allocate(Math.max(1024, buffer.remaining()));
        } else if (byteUnderflow.capacity() < buffer.remaining()) {
            // Expand the underflow buffer
            ByteBuffer newBuffer = ByteBuffer.allocate(buffer.remaining() * 2);
            byteUnderflow.flip();
            newBuffer.put(byteUnderflow);
            byteUnderflow = newBuffer;
        } else {
            // Reuse existing buffer
            byteUnderflow.clear();
        }
        byteUnderflow.put(buffer);
        byteUnderflow.flip(); // Ready for reading
    }
    
    /**
     * Detects and processes a BOM (Byte Order Mark) if present.
     * After detecting a BOM, the charset is determined and we can proceed
     * to character mode. Note that if a UTF-16 BOM is found, the XML
     * declaration (if present) will be encoded in UTF-16, not ASCII.
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
            bomCharset = charset; // Save BOM charset for validation
            return true; // BOM consumed, charset determined
        } else if (b1 == 0xFF && b2 == 0xFE) {
            charset = StandardCharsets.UTF_16LE;
            bomCharset = charset; // Save BOM charset for validation
            return true; // BOM consumed, charset determined
        }

        // Check for UTF-8 BOM (need 3 bytes)
        if (buffer.hasRemaining()) {
            int b3 = buffer.get() & 0xFF;
            if (b1 == 0xEF && b2 == 0xBB && b3 == 0xBF) {
                charset = StandardCharsets.UTF_8;
                bomCharset = charset; // Save BOM charset for validation
                return true; // BOM consumed, charset determined
            }
            buffer.reset(); // No BOM, rewind
            return true; // Can proceed with default charset (no BOM)
        }

        // Only have 2 bytes, might be UTF-8 BOM
        buffer.reset();
        return false; // Need more data
    }

    /**
     * Parses the XML/Text declaration if present.
     * This is done in CHARACTER MODE with special handling:
     * 
     * 1. No tokens are emitted to the parser yet
     * 2. Line-end normalization is performed (CRLF-&gt;LF, CR-&gt;LF)
     * 3. Line/column tracking is maintained for locator
     * 4. If BOM present and encoding differs, this is an error
     * 5. If no BOM and encoding specified, switch charset and re-decode
     * 
     * @param buffer the byte buffer to read from
     * @return true if we can proceed, false if we need more data
     * @throws SAXException if there's a charset mismatch or other error
     */
    private boolean parseXMLDeclaration(ByteBuffer buffer) throws SAXException {
        // Decode bytes into characters with line-end normalization
        charBuffer.clear();
        int initialBytePos = buffer.position();
        
        // Decode the bytes
        CoderResult result = decoder.decode(buffer, charBuffer, false);
        charBuffer.flip();
        
        // Normalize line endings (CRLF->LF, CR->LF) in-place
        normalizeLineEndings(charBuffer);
        
        // Check if we have enough characters to decide
        if (charBuffer.remaining() < 5) {
            // Not enough data decoded yet
            buffer.position(initialBytePos); // Rewind
            return false;
        }
        
        // Check if it starts with "<?xml"
        charBuffer.mark();
        if (charBuffer.get() != '<' || charBuffer.get() != '?' ||
            charBuffer.get() != 'x' || charBuffer.get() != 'm' ||
            charBuffer.get() != 'l') {
            // No XML declaration
            // Save what we decoded to charUnderflow for later tokenization
            charBuffer.reset();
            updateLocationFromChars(charBuffer); // Track line/column
            saveCharUnderflow(charBuffer);
            return true;
        }
        
        // We have "<?xml", now find the end "?>"
        boolean foundEnd = false;
        int searchLimit = Math.min(charBuffer.remaining(), 512);
        for (int i = 0; i < searchLimit - 1; i++) {
            if (charBuffer.get(charBuffer.position() + i) == '?' &&
                charBuffer.get(charBuffer.position() + i + 1) == '>') {
                foundEnd = true;
                // Position after the "?>"
                charBuffer.position(charBuffer.position() + i + 2);
                break;
            }
        }
        
        if (!foundEnd) {
            // Haven't found the end yet
            if (charBuffer.remaining() >= 512) {
                // Declaration is too long, something is wrong
                charBuffer.reset();
                updateLocationFromChars(charBuffer);
                saveCharUnderflow(charBuffer);
                throw new org.xml.sax.SAXParseException(
                    "XML declaration too long (>512 characters)", this);
            }
            // Need more data
            buffer.position(initialBytePos); // Rewind byte buffer
            return false;
        }
        
        // Save the byte position after the XML declaration
        postXMLDeclBytePosition = buffer.position();
        
        // Extract the declaration content (between "<?xml" and "?>")
        int declEnd = charBuffer.position();
        int originalLimit = charBuffer.limit(); // Save original limit
        charBuffer.reset(); // Back to start of "<?xml"
        
        // Skip past "<?xml" (5 characters)
        charBuffer.position(charBuffer.position() + 5);
        
        // Create a slice for just the declaration content
        int contentStart = charBuffer.position();
        int contentEnd = declEnd - 2; // Exclude "?>"
        charBuffer.limit(contentEnd);
        CharBuffer declContent = charBuffer.slice();
        
        // Restore buffer for line tracking of the declaration
        charBuffer.limit(declEnd);
        charBuffer.position(declEnd);
        
        // Update line/column from the entire declaration
        charBuffer.reset();
        charBuffer.position(declEnd);
        updateLocationFromChars(charBuffer);
        
        // Parse the declaration to extract version, encoding, standalone
        // The encoding will be handled by setCharset() called from xmlDeclReceive()
        // Pass the byte buffer so setCharset() can reset position if needed
        extractXMLDeclarationAttributes(declContent, buffer);
        
        // Restore original limit and position after declaration
        charBuffer.limit(originalLimit);
        charBuffer.position(declEnd);
        
        // Save any remaining decoded characters to charUnderflow
        if (charBuffer.hasRemaining()) {
            saveCharUnderflow(charBuffer);
        }
        
        return true;
    }
    
    /**
     * Sets the charset based on the encoding attribute in the XML declaration.
     * Handles three scenarios:
     * 1. BOM present + encoding differs = ERROR
     * 2. No BOM + encoding differs = SWITCH CHARSET
     * 3. BOM present + encoding matches = SUCCESS (continue)
     * 
     * This is called after the entire XML declaration has been parsed,
     * following the event-driven pattern.
     * 
     * @param encodingName the encoding name from the XML declaration
     * @param byteBuffer the byte buffer (for resetting position during charset switch)
     * @throws SAXException if there's a charset mismatch or unsupported encoding
     */
    private void setCharset(String encodingName, ByteBuffer byteBuffer) throws SAXException {
        // Try to resolve the declared charset
        // Per XML spec: charset names are case-insensitive and should match IANA names
        // Java's Charset.forName() already handles case-insensitivity
        Charset declaredCharset;
        try {
            declaredCharset = Charset.forName(encodingName);
        } catch (Exception e) {
            // Unsupported or invalid charset name
            throw new org.xml.sax.SAXParseException("Unsupported encoding in XML declaration: " + 
                encodingName + " (reason: " + e.getMessage() + ")", this, e);
        }
        
        // Case 1: BOM present + encoding differs = ERROR
        // Note: We need to check if charsets are compatible, not just equal
        // e.g., "UTF-16" is compatible with UTF-16LE/BE BOMs
        if (bomCharset != null && !isCharsetCompatible(bomCharset, declaredCharset)) {
            throw new org.xml.sax.SAXParseException("Encoding mismatch: BOM indicates " + 
                bomCharset.name() + " but XML declaration specifies " + 
                encodingName, this);
        }
        
        // Case 2: No BOM + encoding differs from current = SWITCH CHARSET
        if (bomCharset == null && !charset.equals(declaredCharset)) {
            // Switch to the declared charset
            charset = declaredCharset;
            decoder = charset.newDecoder();
            
            // Reset byte buffer to position after XML declaration
            // This allows re-decoding the remaining document with the correct charset
            if (byteBuffer != null) {
                byteBuffer.position(postXMLDeclBytePosition);
            }
            
            // Clear character buffers for re-decode
            charBuffer.clear();
            charUnderflow.clear();
            charUnderflow.limit(0);
            
            // Note: The actual re-decoding will happen when we fall through
            // to CHARACTERS state or on the next receive()
        }
        
        // Case 3: BOM present + encoding matches (or compatible) = SUCCESS
        // No action needed, continue with current charset
    }
    
    /**
     * Checks if two charsets are compatible.
     * This handles cases like UTF-16 being compatible with UTF-16LE/BE.
     * 
     * Per XML spec and Java behavior:
     * - "UTF-16" with BOM is compatible with UTF-16LE or UTF-16BE
     * - "UTF-16BE" requires UTF-16BE
     * - "UTF-16LE" requires UTF-16LE
     * - Other charsets must match exactly
     * 
     * @param bomCharset the charset detected from the BOM
     * @param declaredCharset the charset declared in the XML declaration
     * @return true if compatible, false otherwise
     */
    private boolean isCharsetCompatible(Charset bomCharset, Charset declaredCharset) {
        // Exact match is always compatible
        if (bomCharset.equals(declaredCharset)) {
            return true;
        }
        
        // Get normalized names for comparison
        String bomName = bomCharset.name().toLowerCase();
        String declName = declaredCharset.name().toLowerCase();
        
        // UTF-16 (generic) is compatible with UTF-16LE or UTF-16BE
        // since the BOM determines which one to use
        if (declName.equals("utf-16") || declName.equals("utf16")) {
            return bomName.equals("utf-16le") || bomName.equals("utf-16be") ||
                   bomName.equals("utf-16") || bomName.equals("utf16");
        }
        
        // Check if they're aliases of each other
        // Java's Charset handles many aliases (e.g., "UTF8" == "UTF-8")
        return bomCharset.aliases().contains(declName) ||
               declaredCharset.aliases().contains(bomName);
    }
    
    /**
     * Saves remaining characters in the buffer to charUnderflow.
     * @param buffer the buffer to save from
     */
    private void saveCharUnderflow(CharBuffer buffer) {
        if (!buffer.hasRemaining()) {
            return;
        }
        if (charUnderflow.capacity() < buffer.remaining()) {
            charUnderflow = CharBuffer.allocate(buffer.remaining() * 2);
        } else {
            charUnderflow.clear();
        }
        charUnderflow.put(buffer);
        charUnderflow.flip(); // Ready for reading
    }

    /**
     * Normalizes line endings in the character buffer.
     * Converts CRLF → LF and CR → LF as per XML spec section 2.11.
     * Modifies the buffer in-place and adjusts its limit.
     * 
     * This method handles the case where a CRLF straddles buffer boundaries:
     * if the previous receive() ended with CR, and this buffer starts with LF,
     * the LF is skipped.
     * 
     * @param buffer the buffer to normalize (must be in read mode)
     */
    private void normalizeLineEndings(CharBuffer buffer) {
        int readPos = buffer.position();
        int writePos = readPos;
        int limit = buffer.limit();
        
        // Handle straddle case: previous buffer ended with CR, this starts with LF
        if (lastCharSeen == '\r' && readPos < limit) {
            char firstChar = buffer.get(readPos);
            if (firstChar == '\n') {
                // Skip the LF (already converted the CR to LF in previous receive)
                readPos++;
            }
        }
        
        while (readPos < limit) {
            char c = buffer.get(readPos);
            if (c == '\r') {
                // Check if next char is \n
                if (readPos + 1 < limit && buffer.get(readPos + 1) == '\n') {
                    // CRLF -> LF: write LF, skip CR
                    buffer.put(writePos++, '\n');
                    readPos += 2; // Skip both CR and LF
                    lastCharSeen = '\n'; // Store LF as last seen
                } else {
                    // CR alone -> LF: convert to \n
                    buffer.put(writePos++, '\n');
                    readPos++;
                    lastCharSeen = '\r'; // Store CR (might be followed by LF in next buffer)
                }
            } else {
                // Normal character: write it
                if (writePos != readPos) {
                    buffer.put(writePos, c);
                }
                writePos++;
                readPos++;
                lastCharSeen = c;
            }
        }
        
        // Adjust buffer limit to reflect normalized content
        buffer.limit(writePos);
        buffer.position(buffer.position()); // Keep original position
    }
    
    /**
     * Updates line and column numbers by consuming characters from the buffer.
     * This is called during XML declaration parsing to track position.
     * 
     * @param buffer the buffer to read from (consumes from current position)
     */
    private void updateLocationFromChars(CharBuffer buffer) {
        int start = buffer.position();
        int end = buffer.limit();
        
        for (int i = start; i < end; i++) {
            char c = buffer.get(i);
            if (c == '\n') {
                lineNumber++;
                columnNumber = 0;
            } else {
                columnNumber++;
            }
        }
    }
    
    /**
     * Extracts attributes from the XML declaration using tokenization.
     * The buffer should be positioned after "<?xml" and limited to before "?>".
     * 
     * This performs well-formedness checking and proper XML tokenization
     * instead of simple string manipulation.
     * 
     * Uses event-driven pattern: when encoding attribute is found, setCharset()
     * is called directly to handle charset switching.
     * 
     * @param declBuffer the character buffer containing the XML declaration content
     * @param byteBuffer the byte buffer (for resetting position during charset switch)
     * @throws SAXException if the XML declaration is malformed
     */
    private void extractXMLDeclarationAttributes(CharBuffer declBuffer, ByteBuffer byteBuffer) throws SAXException {
        // Context object to hold mutable state
        XMLDeclContext ctx = new XMLDeclContext();
        ctx.byteBuffer = byteBuffer;
        ctx.state = XMLDeclState.EXPECT_WHITESPACE_OR_NAME;
        
        while (declBuffer.hasRemaining()) {
            char c = declBuffer.get();
            
            switch (ctx.state) {
                case EXPECT_WHITESPACE_OR_NAME:
                    if (isWhitespace(c)) {
                        // Skip whitespace
                    } else if (isNameStartChar(c)) {
                        // Start of attribute name
                        ctx.nameStart = declBuffer.position() - 1;
                        ctx.state = XMLDeclState.IN_NAME;
                    } else {
                        throw new org.xml.sax.SAXParseException("Expected attribute name in XML declaration", this);
                    }
                    break;
                    
                case IN_NAME:
                    if (isNameChar(c)) {
                        // Continue consuming name
                    } else if (isWhitespace(c) || c == '=') {
                        // End of name - extract it
                        int nameEnd = declBuffer.position() - 1;
                        ctx.currentName = extractSubstring(declBuffer, ctx.nameStart, nameEnd);
                        validateAndRegisterAttribute(ctx);
                        
                        if (c == '=') {
                            ctx.state = XMLDeclState.EXPECT_QUOTE;
                        } else {
                            ctx.state = XMLDeclState.EXPECT_EQ;
                        }
                    } else {
                        throw new org.xml.sax.SAXParseException("Invalid character in attribute name in XML declaration", this);
                    }
                    break;
                    
                case EXPECT_EQ:
                    if (isWhitespace(c)) {
                        // Skip whitespace before =
                    } else if (c == '=') {
                        ctx.state = XMLDeclState.EXPECT_QUOTE;
                    } else {
                        throw new org.xml.sax.SAXParseException("Expected '=' after attribute name in XML declaration", this);
                    }
                    break;
                    
                case EXPECT_QUOTE:
                    if (isWhitespace(c)) {
                        // Skip whitespace after =
                    } else if (c == '"' || c == '\'') {
                        ctx.quoteChar = c;
                        ctx.valueStart = declBuffer.position();
                        ctx.state = XMLDeclState.IN_VALUE;
                    } else {
                        throw new org.xml.sax.SAXParseException("Expected quote after '=' in XML declaration", this);
                    }
                    break;
                    
                case IN_VALUE:
                    if (c == ctx.quoteChar) {
                        // End of value - extract it
                        int valueEnd = declBuffer.position() - 1;
                        String value = extractSubstring(declBuffer, ctx.valueStart, valueEnd);
                        storeAttributeValue(ctx, value);
                        ctx.currentName = null;
                        ctx.state = XMLDeclState.EXPECT_WHITESPACE_OR_NAME;
                    }
                    // Otherwise continue consuming value
                    break;
            }
        }
        
        // Validate required attributes
        if ((ctx.seenAttributes & 1) == 0) {
            throw new org.xml.sax.SAXParseException("XML declaration missing required 'version' attribute", this);
        }
        
        // Now that the declaration is fully parsed, handle charset switching if needed
        if (ctx.encoding != null) {
            setCharset(ctx.encoding, ctx.byteBuffer);
        }
    }
    
    /**
     * Validates and registers an attribute name in the XML declaration context.
     */
    private void validateAndRegisterAttribute(XMLDeclContext ctx) throws SAXException {
        if ("version".equals(ctx.currentName)) {
            if (ctx.seenAttributes != 0) {
                throw new org.xml.sax.SAXParseException("'version' must be the first attribute in XML declaration", this);
            }
            ctx.seenAttributes |= 1;
        } else if ("encoding".equals(ctx.currentName)) {
            if ((ctx.seenAttributes & 1) == 0) {
                throw new org.xml.sax.SAXParseException("'version' must come before 'encoding' in XML declaration", this);
            }
            if ((ctx.seenAttributes & 4) != 0) {
                throw new org.xml.sax.SAXParseException("'encoding' must come before 'standalone' in XML declaration", this);
            }
            ctx.seenAttributes |= 2;
        } else if ("standalone".equals(ctx.currentName)) {
            if ((ctx.seenAttributes & 1) == 0) {
                throw new org.xml.sax.SAXParseException("'version' must come before 'standalone' in XML declaration", this);
            }
            ctx.seenAttributes |= 4;
        } else {
            throw new org.xml.sax.SAXParseException("Unknown attribute in XML declaration: " + ctx.currentName, this);
        }
    }
    
    /**
     * Stores an attribute value in the XML declaration context.
     */
    private void storeAttributeValue(XMLDeclContext ctx, String value) throws SAXException {
        if ("version".equals(ctx.currentName)) {
            version = value;
        } else if ("encoding".equals(ctx.currentName)) {
            ctx.encoding = value;
        } else if ("standalone".equals(ctx.currentName)) {
            if ("yes".equals(value)) {
                standalone = true;
            } else if ("no".equals(value)) {
                standalone = false;
            } else {
                throw new org.xml.sax.SAXParseException("Invalid value for 'standalone' attribute: " + value + 
                                     " (must be 'yes' or 'no')", this);
            }
        }
    }
    
    /**
     * Extracts a substring from the buffer between start and end positions.
     */
    private String extractSubstring(CharBuffer buffer, int start, int end) {
        char[] chars = new char[end - start];
        for (int i = 0; i < chars.length; i++) {
            chars[i] = buffer.get(start + i);
        }
        return new String(chars);
    }
    
    /**
     * States for XML declaration parsing.
     */
    private enum XMLDeclState {
        EXPECT_WHITESPACE_OR_NAME,
        IN_NAME,
        EXPECT_EQ,
        EXPECT_QUOTE,
        IN_VALUE
    }
    
    /**
     * Context for XML declaration parsing.
     * Holds mutable state during tokenization.
     */
    private static class XMLDeclContext {
        XMLDeclState state;
        String currentName = null;
        short seenAttributes = 0; // Bit flags: 1=version, 2=encoding, 4=standalone
        String encoding = null; // Store encoding for setCharset() call after parsing complete
        ByteBuffer byteBuffer = null; // For resetting position during charset switch
        int nameStart = 0; // Start position of current name
        int valueStart = 0; // Start position of current value
        char quoteChar = '\0'; // Current quote character
    }
    
    /**
     * Checks if a character is a valid XML name start character.
     * Simplified version for ASCII range.
     * 
     * @param c the character to check
     * @return true if valid name start character
     */
    private boolean isNameStartChar(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || 
               c == ':' || c == '_';
    }
    
    /**
     * Checks if a character is a valid XML name character.
     * Simplified version for ASCII range.
     * 
     * @param c the character to check
     * @return true if valid name character
     */
    private boolean isNameChar(char c) {
        return isNameStartChar(c) || (c >= '0' && c <= '9') || 
               c == '-' || c == '.';
    }

    /**
     * Decodes bytes to characters and tokenizes them.
     * @param buffer the byte buffer to decode
     */
    private void decodeAndTokenize(ByteBuffer buffer) throws SAXException {
        // Clear charBuffer for writing
        charBuffer.clear();
        
        // Prepend character underflow if it exists
        if (charUnderflow.hasRemaining()) {
            charBuffer.put(charUnderflow);
            charUnderflow.clear();
            charUnderflow.limit(0);
        }
        
        // Decode bytes into charBuffer
        CoderResult result = decoder.decode(buffer, charBuffer, false);
        charBuffer.flip(); // Ready for reading
        
        // Normalize line endings (CRLF->LF, CR->LF) in-place
        // This also handles the case where CRLF straddles buffer boundaries
        normalizeLineEndings(charBuffer);
        
        // Save any remaining bytes as underflow
        if (buffer.hasRemaining()) {
            saveByteUnderflow(buffer);
        } else if (byteUnderflow != null) {
            byteUnderflow.clear();
            byteUnderflow.limit(0);
        }
        
        // Tokenize the characters
        tokenize();
        
        // Save any remaining characters as underflow
        if (charBuffer.hasRemaining()) {
            if (charUnderflow.capacity() < charBuffer.remaining()) {
                charUnderflow = CharBuffer.allocate(charBuffer.remaining() * 2);
            } else {
                charUnderflow.clear();
            }
            charUnderflow.put(charBuffer);
            charUnderflow.flip();
        }
    }

    /**
     * Tokenizes the characters in charBuffer and emits tokens to the parser.
     * This is the main tokenization loop that processes characters and emits
     * tokens based on XML syntax rules.
     */
    private void tokenize() throws SAXException {
        while (charBuffer.hasRemaining()) {
            charBuffer.mark(); // Save position in case we need to backtrack
            char c = charBuffer.get();
            
            // Update line/column tracking
            if (c == '\n') {
                lineNumber++;
                columnNumber = 0;
            } else {
                columnNumber++;
            }
            
            switch (c) {
                case '<':
                    if (!tryEmitLtSequence()) {
                        // Not enough data, rewind and save to underflow
                        charBuffer.reset();
                        return;
                    }
                    break;
                    
                case '>':
                    // Could be '>' or part of '-->' or ']]>'
                    emitToken(Token.GT, null);
                    break;
                    
                case '&':
                    if (!tryEmitEntityRef()) {
                        // Not enough data, rewind and save to underflow
                        charBuffer.reset();
                        return;
                    }
                    break;
                    
                case '\'':
                    emitToken(Token.APOS, null);
                    break;
                    
                case '"':
                    emitToken(Token.QUOT, null);
                    break;
                    
                case ':':
                    emitToken(Token.COLON, null);
                    break;
                    
                case '!':
                    emitToken(Token.BANG, null);
                    break;
                    
                case '?':
                    // Could be '?' or '?>'
                    if (charBuffer.hasRemaining()) {
                        charBuffer.mark();
                        char next = charBuffer.get();
                        if (next == '>') {
                            updateColumn();
                            emitToken(Token.END_PI, null);
                        } else {
                            charBuffer.reset();
                            emitToken(Token.QUERY, null);
                        }
                    } else {
                        // Need more data to decide
                        charBuffer.reset();
                        return;
                    }
                    break;
                    
                case '=':
                    emitToken(Token.EQ, null);
                    break;
                    
                case '%':
                    // In DOCTYPE context, % could be start of parameter entity reference
                    if (context == TokenizerContext.DOCTYPE_INTERNAL || context == TokenizerContext.DOCTYPE) {
                        if (!tryEmitParameterEntityRef()) {
                            // Not enough data, rewind and save to underflow
                            charBuffer.reset();
                            return;
                        }
                    } else {
                        // Outside DOCTYPE, % is just a regular character
                        // Let it fall through to CDATA handling
                        charBuffer.reset();
                        if (!tryEmitCDATA()) {
                            charBuffer.reset();
                            return;
                        }
                    }
                    break;
                    
                case '#':
                    // Could be '#' or '#PCDATA', '#REQUIRED', '#IMPLIED', '#FIXED'
                    if (!tryEmitHashKeyword()) {
                        // Not enough data or not a keyword, rewind
                        charBuffer.reset();
                        return;
                    }
                    break;
                    
                case '|':
                    // Only emit as special token outside of ATTR_VALUE
                    if (context == TokenizerContext.ATTR_VALUE || 
                        context == TokenizerContext.CONTENT ||
                        context == TokenizerContext.COMMENT ||
                        context == TokenizerContext.CDATA_SECTION ||
                        context == TokenizerContext.PI_DATA) {
                        charBuffer.reset();
                        if (!tryEmitCDATA()) {
                            return;
                        }
                    } else {
                        emitToken(Token.PIPE, null);
                    }
                    break;
                    
                case '[':
                    // Only emit as special token outside of ATTR_VALUE
                    if (context == TokenizerContext.ATTR_VALUE || 
                        context == TokenizerContext.CONTENT ||
                        context == TokenizerContext.COMMENT ||
                        context == TokenizerContext.CDATA_SECTION ||
                        context == TokenizerContext.PI_DATA) {
                        charBuffer.reset();
                        if (!tryEmitCDATA()) {
                            return;
                        }
                    } else {
                        emitToken(Token.OPEN_BRACKET, null);
                    }
                    break;
                    
                case ']':
                    // In CDATA sections, ']' might be part of ']]>'
                    if (context == TokenizerContext.CDATA_SECTION) {
                        // Handle ]]> sequence in CDATA
                        if (charBuffer.hasRemaining()) {
                            charBuffer.mark();
                            char next = charBuffer.get();
                            if (next == ']' && charBuffer.hasRemaining()) {
                                char next2 = charBuffer.get();
                                if (next2 == '>') {
                                    updateColumn();
                                    updateColumn();
                                    emitToken(Token.END_CDATA, null);
                                    break;
                                }
                                charBuffer.reset();
                            } else {
                                charBuffer.reset();
                            }
                        }
                        // Not ]]>, treat as CDATA
                        charBuffer.reset();
                        if (!tryEmitCDATA()) {
                            return;
                        }
                    } else if (context == TokenizerContext.ATTR_VALUE || 
                               context == TokenizerContext.CONTENT ||
                               context == TokenizerContext.COMMENT ||
                               context == TokenizerContext.PI_DATA) {
                        // In these contexts, ']' is just regular text
                        charBuffer.reset();
                        if (!tryEmitCDATA()) {
                            return;
                        }
                    } else {
                        // In DOCTYPE contexts, emit as token
                        emitToken(Token.CLOSE_BRACKET, null);
                    }
                    break;
                    
                case '(':
                    // Only emit as special token outside of ATTR_VALUE
                    if (context == TokenizerContext.ATTR_VALUE || 
                        context == TokenizerContext.CONTENT ||
                        context == TokenizerContext.COMMENT ||
                        context == TokenizerContext.CDATA_SECTION ||
                        context == TokenizerContext.PI_DATA) {
                        charBuffer.reset();
                        if (!tryEmitCDATA()) {
                            return;
                        }
                    } else {
                        emitToken(Token.OPEN_PAREN, null);
                    }
                    break;
                    
                case ')':
                    // Only emit as special token outside of ATTR_VALUE
                    if (context == TokenizerContext.ATTR_VALUE || 
                        context == TokenizerContext.CONTENT ||
                        context == TokenizerContext.COMMENT ||
                        context == TokenizerContext.CDATA_SECTION ||
                        context == TokenizerContext.PI_DATA) {
                        charBuffer.reset();
                        if (!tryEmitCDATA()) {
                            return;
                        }
                    } else {
                        emitToken(Token.CLOSE_PAREN, null);
                    }
                    break;
                    
                case '*':
                    // Only emit as special token outside of ATTR_VALUE
                    if (context == TokenizerContext.ATTR_VALUE || 
                        context == TokenizerContext.CONTENT ||
                        context == TokenizerContext.COMMENT ||
                        context == TokenizerContext.CDATA_SECTION ||
                        context == TokenizerContext.PI_DATA) {
                        charBuffer.reset();
                        if (!tryEmitCDATA()) {
                            return;
                        }
                    } else {
                        emitToken(Token.STAR, null);
                    }
                    break;
                    
                case '+':
                    // Only emit as special token outside of ATTR_VALUE
                    if (context == TokenizerContext.ATTR_VALUE || 
                        context == TokenizerContext.CONTENT ||
                        context == TokenizerContext.COMMENT ||
                        context == TokenizerContext.CDATA_SECTION ||
                        context == TokenizerContext.PI_DATA) {
                        charBuffer.reset();
                        if (!tryEmitCDATA()) {
                            return;
                        }
                    } else {
                        emitToken(Token.PLUS, null);
                    }
                    break;
                    
                case ',':
                    // Only emit as special token outside of ATTR_VALUE
                    if (context == TokenizerContext.ATTR_VALUE || 
                        context == TokenizerContext.CONTENT ||
                        context == TokenizerContext.COMMENT ||
                        context == TokenizerContext.CDATA_SECTION ||
                        context == TokenizerContext.PI_DATA) {
                        charBuffer.reset();
                        if (!tryEmitCDATA()) {
                            return;
                        }
                    } else {
                        emitToken(Token.COMMA, null);
                    }
                    break;
                    
                case '/':
                    // Could be '/' or '/>'
                    if (charBuffer.hasRemaining()) {
                        charBuffer.mark();
                        char next = charBuffer.get();
                        if (next == '>') {
                            updateColumn();
                            emitToken(Token.END_EMPTY_ELEMENT, null);
                        } else {
                            charBuffer.reset();
                            // '/' is not a valid standalone token in XML, treat as CDATA
                            charBuffer.reset(); // Go back before '/'
                            if (!tryEmitCDATA()) {
                                return;
                            }
                        }
                    } else {
                        // Need more data to decide
                        charBuffer.reset();
                        return;
                    }
                    break;
                    
                case '-':
                    // Could be part of '-->'
                    if (charBuffer.hasRemaining()) {
                        charBuffer.mark();
                        char next = charBuffer.get();
                        if (next == '-') {
                            // '--'
                            if (charBuffer.hasRemaining()) {
                                char next2 = charBuffer.get();
                                if (next2 == '>') {
                                    updateColumn(); updateColumn();
                                    emitToken(Token.END_COMMENT, null);
                                } else {
                                    charBuffer.reset();
                                    // '--' not followed by '>', treat as CDATA
                                    if (!tryEmitCDATA()) {
                                        return;
                                    }
                                }
                            } else {
                                // Need more data
                                charBuffer.reset();
                                return;
                            }
                        } else {
                            charBuffer.reset();
                            // Single '-', treat as CDATA
                            if (!tryEmitCDATA()) {
                                return;
                            }
                        }
                    } else {
                        // Need more data
                        charBuffer.reset();
                        return;
                    }
                    break;
                    
                case ' ':
                case '\t':
                case '\n':
                case '\r': // Should already be normalized, but handle for safety
                    // Whitespace - collect all consecutive whitespace
                    charBuffer.reset(); // Go back to start of whitespace
                    if (!tryEmitWhitespace()) {
                        // Not enough data (shouldn't happen for whitespace, but be safe)
                        return;
                    }
                    break;
                    
                default:
                    // Context-aware token emission: NAME for identifiers, CDATA for text content
                    charBuffer.reset(); // Go back to start of content
                    
                    // Determine what to emit based on context
                    switch (context) {
                        case CONTENT:
                        case ATTR_VALUE:
                        case COMMENT:
                        case CDATA_SECTION:
                        case PI_DATA:
                            // In these contexts, everything is CDATA (text content)
                            if (!tryEmitCDATA()) {
                                return;
                            }
                            break;
                            
                        case ELEMENT_NAME:
                        case ELEMENT_ATTRS:
                        case PI_TARGET:
                        case DOCTYPE:
                        case DOCTYPE_INTERNAL:
                            // In these contexts, try NAME first (identifiers)
                            if (isNameStartChar(c)) {
                                if (!tryEmitName()) {
                                    return;
                                }
                            } else {
                                // Not a name start char, treat as CDATA
                                if (!tryEmitCDATA()) {
                                    return;
                                }
                            }
                            break;
                    }
                    break;
            }
        }
    }
    
    /**
     * Tries to emit a token that starts with '&lt;'.
     * Handles: &lt;/, &lt;?, &lt;!, &lt;!--, &lt;![CDATA[, &lt;!DOCTYPE, &lt;!ELEMENT, &lt;!ATTLIST, &lt;!ENTITY, &lt;!NOTATION, &lt;![
     * @return true if token was emitted, false if more data is needed
     */
    private boolean tryEmitLtSequence() throws SAXException {
        // We've already consumed '<'
        if (!charBuffer.hasRemaining()) {
            return false; // Need more data
        }
        
        charBuffer.mark();
        char c = charBuffer.get();
        updateColumn();
        
        if (c == '/') {
            // </
            emitToken(Token.START_END_ELEMENT, null);
            return true;
        } else if (c == '?') {
            // <? - could be <?xml or other PI
            if (!charBuffer.hasRemaining()) {
                return false; // Need more data
            }
            // Peek ahead for "xml"
            if (charBuffer.remaining() >= 3) {
                charBuffer.mark();
                if (charBuffer.get() == 'x' && charBuffer.get() == 'm' && charBuffer.get() == 'l') {
                    // Check if followed by whitespace or '?'
                    if (charBuffer.hasRemaining()) {
                        char next = charBuffer.get();
                        if (isWhitespace(next) || next == '?') {
                            // It's <?xml
                            charBuffer.reset(); // Back to after 'l'
                            charBuffer.position(charBuffer.position() + 3); // Skip 'xml'
                            updateColumn(); updateColumn(); updateColumn();
                            emitToken(Token.START_XMLDECL, null);
                            return true;
                        }
                    } else {
                        return false; // Need more data
                    }
                }
                charBuffer.reset(); // Back to after '?'
            } else {
                return false; // Need more data
            }
            // It's a regular PI
            emitToken(Token.START_PI, null);
            return true;
        } else if (c == '!') {
            // <! - could be <!--, <![CDATA[, <!DOCTYPE, etc.
            if (!charBuffer.hasRemaining()) {
                return false; // Need more data
            }
            charBuffer.mark();
            c = charBuffer.get();
            updateColumn();
            
            if (c == '-') {
                // <!- - check for <!--
                if (!charBuffer.hasRemaining()) {
                    return false;
                }
                c = charBuffer.get();
                updateColumn();
                if (c == '-') {
                    emitToken(Token.START_COMMENT, null);
                    return true;
                } else {
                    throw new org.xml.sax.SAXParseException("Invalid sequence: <!-" + c, this);
                }
            } else if (c == '[') {
                // <![ - could be <![CDATA[ or <![
                if (charBuffer.remaining() >= 6) {
                    charBuffer.mark();
                    if (charBuffer.get() == 'C' && charBuffer.get() == 'D' &&
                        charBuffer.get() == 'A' && charBuffer.get() == 'T' &&
                        charBuffer.get() == 'A' && charBuffer.get() == '[') {
                        updateColumn(); updateColumn(); updateColumn();
                        updateColumn(); updateColumn(); updateColumn();
                        emitToken(Token.START_CDATA, null);
                        return true;
                    }
                    charBuffer.reset(); // Back to after '['
                } else {
                    return false; // Need more data
                }
                emitToken(Token.START_CONDITIONAL, null);
                return true;
            } else if (isNameStartChar(c)) {
                // <!NAME - could be DOCTYPE, ELEMENT, ATTLIST, ENTITY, NOTATION
                charBuffer.reset(); // Back to before name char
                int nameStart = charBuffer.position();
                // Collect the name
                if (!tryConsumeNameChars()) {
                    return false; // Need more data
                }
                int nameEnd = charBuffer.position();
                
                // Extract the name
                charBuffer.position(nameStart);
                StringBuilder sb = new StringBuilder();
                while (charBuffer.position() < nameEnd) {
                    sb.append(charBuffer.get());
                    updateColumn();
                }
                String name = sb.toString();
                
                // Determine the token type
                switch (name) {
                    case "DOCTYPE":
                        emitToken(Token.START_DOCTYPE, null);
                        return true;
                    case "ELEMENT":
                        emitToken(Token.START_ELEMENTDECL, null);
                        return true;
                    case "ATTLIST":
                        emitToken(Token.START_ATTLISTDECL, null);
                        return true;
                    case "ENTITY":
                        emitToken(Token.START_ENTITYDECL, null);
                        return true;
                    case "NOTATION":
                        emitToken(Token.START_NOTATIONDECL, null);
                        return true;
                    default:
                        throw new org.xml.sax.SAXParseException("Unknown declaration: <!" + name, this);
                }
            } else {
                throw new org.xml.sax.SAXParseException("Invalid character after '<!': " + c, this);
            }
        } else {
            // Regular '<' followed by something else (probably a name)
            charBuffer.reset(); // Back to the character after '<'
            emitToken(Token.LT, null);
            return true;
        }
    }
    
    /**
     * Tries to emit an entity reference or the special sequences ]]&gt; or /&gt;.
     * @return true if token was emitted, false if more data is needed
     */
    private boolean tryEmitEntityRef() throws SAXException {
        // We've already consumed '&'
        if (!charBuffer.hasRemaining()) {
            return false; // Need more data
        }
        
        charBuffer.mark();
        int refStart = charBuffer.position();
        
        // Peek at first character to determine type
        char firstChar = charBuffer.get();
        boolean isCharRef = (firstChar == '#');
        charBuffer.position(refStart); // Reset to start
        
        // Look for the semicolon (with reasonable length limit)
        int charCount = 0;
        final int MAX_ENTITY_REF_LENGTH = 1024;
        
        while (charBuffer.hasRemaining()) {
            char c = charBuffer.get();
            charCount++;
            
            if (charCount > MAX_ENTITY_REF_LENGTH) {
                // Entity reference is too long - well-formedness error
                throw new org.xml.sax.SAXParseException(
                    "Entity reference exceeds maximum length of " + MAX_ENTITY_REF_LENGTH + " characters", this);
            }
            
            if (c == ';') {
                // Found end of entity reference
                int refEnd = charBuffer.position() - 1; // Before the ';'
                int savedPos = charBuffer.position();
                
                // Extract the entity reference content (after & and before ;)
                charBuffer.position(refStart);
                StringBuilder sb = new StringBuilder();
                while (charBuffer.position() < refEnd) {
                    sb.append(charBuffer.get());
                }
                String refContent = sb.toString();
                
                // Restore position
                charBuffer.position(savedPos);
                
                // Update column for the entire entity reference (&...;)
                updateColumn(); // for '&'
                for (int i = 0; i < refContent.length(); i++) {
                    updateColumn();
                }
                updateColumn(); // for ';'
                
                // Handle different types of references
                if (isCharRef) {
                    // Character reference: &#decimal; or &#xhex;
                    String replacement = expandCharacterReference(refContent);
                    if (replacement != null) {
                        CharBuffer refBuffer = CharBuffer.wrap(replacement);
                        emitToken(Token.ENTITYREF, refBuffer);
                        return true;
                    } else {
                        // Invalid character reference
                        throw new org.xml.sax.SAXParseException(
                            "Invalid character reference: &" + refContent + ";", this);
                    }
                } else {
                    // Named entity reference
                    String replacement = getPredefinedEntity(refContent);
                    if (replacement != null) {
                        // Predefined entity - emit with replacement text
                        CharBuffer refBuffer = CharBuffer.wrap(replacement);
                        emitToken(Token.ENTITYREF, refBuffer);
                        return true;
                    } else {
                        // General entity reference - emit entity name
                        CharBuffer nameBuffer = CharBuffer.wrap(refContent);
                        emitToken(Token.GENERALENTITYREF, nameBuffer);
                        return true;
                    }
                }
            } else if (!isNameChar(c) && c != '#' && c != 'x' && c != 'X') {
                // Invalid entity reference character - well-formedness error
                throw new org.xml.sax.SAXParseException(
                    "Invalid character in entity reference: '&" + c + "'", this);
            }
        }
        
        // Didn't find ';' - need more data
        charBuffer.reset();
        return false;
    }
    
    /**
     * Expands a character reference.
     * @param refContent the content after # (e.g., "38" or "x26")
     * @return the replacement character, or null if invalid
     */
    private String expandCharacterReference(String refContent) {
        if (refContent.length() < 2) {
            return null; // Just "#" is invalid
        }
        
        try {
            String numPart = refContent.substring(1); // Skip the '#'
            int codePoint;
            
            if (numPart.startsWith("x") || numPart.startsWith("X")) {
                // Hexadecimal
                codePoint = Integer.parseInt(numPart.substring(1), 16);
            } else {
                // Decimal
                codePoint = Integer.parseInt(numPart);
            }
            
            // Validate code point
            if (codePoint < 0 || codePoint > 0x10FFFF) {
                return null;
            }
            
            return new String(Character.toChars(codePoint));
        } catch (NumberFormatException e) {
            return null;
        }
    }
    
    /**
     * Gets predefined entity replacement text.
     * @param entityName the entity name
     * @return replacement text, or null if not a predefined entity
     */
    private String getPredefinedEntity(String entityName) {
        switch (entityName) {
            case "amp": return "&";
            case "lt": return "<";
            case "gt": return ">";
            case "apos": return "'";
            case "quot": return "\"";
            default: return null;
        }
    }
    
    /**
     * Tries to emit a parameter entity reference (%name;).
     * We've already consumed '%'.
     * @return true if token was emitted, false if more data is needed
     */
    private boolean tryEmitParameterEntityRef() throws SAXException {
        if (!charBuffer.hasRemaining()) {
            return false; // Need more data
        }
        
        charBuffer.mark();
        int refStart = charBuffer.position();
        
        // Peek at first character - if it's not a NameStartChar, this is just a PERCENT token
        char firstChar = charBuffer.get();
        if (!isNameStartChar(firstChar)) {
            // Not a parameter entity reference, just a PERCENT token
            charBuffer.reset();
            emitToken(Token.PERCENT, null);
            return true;
        }
        
        // Reset to start and look for the semicolon (with reasonable length limit)
        charBuffer.position(refStart);
        
        int charCount = 0;
        final int MAX_ENTITY_REF_LENGTH = 1024;
        
        while (charBuffer.hasRemaining()) {
            char c = charBuffer.get();
            charCount++;
            
            if (charCount > MAX_ENTITY_REF_LENGTH) {
                // Parameter entity reference is too long - well-formedness error
                throw new org.xml.sax.SAXParseException(
                    "Parameter entity reference exceeds maximum length of " + MAX_ENTITY_REF_LENGTH + " characters", this);
            }
            
            if (c == ';') {
                // Found end of parameter entity reference
                int refEnd = charBuffer.position() - 1; // Before the ';'
                int savedPos = charBuffer.position();
                
                // Extract the entity name (between % and ;)
                charBuffer.position(refStart);
                StringBuilder sb = new StringBuilder();
                while (charBuffer.position() < refEnd) {
                    sb.append(charBuffer.get());
                }
                String entityName = sb.toString();
                
                // Restore position
                charBuffer.position(savedPos);
                
                // Entity name should already be valid (we checked first char)
                // but double-check it's not empty
                if (entityName.isEmpty()) {
                    throw new org.xml.sax.SAXParseException(
                        "Empty parameter entity reference", this);
                }
                
                // Update column for the entire reference (%...;)
                updateColumn(); // for '%'
                for (int i = 0; i < entityName.length(); i++) {
                    updateColumn();
                }
                updateColumn(); // for ';'
                
                // Emit parameter entity reference with entity name
                CharBuffer nameBuffer = CharBuffer.wrap(entityName);
                emitToken(Token.PARAMETERENTITYREF, nameBuffer);
                return true;
            } else if (!isNameChar(c)) {
                // Invalid character in parameter entity name - well-formedness error
                throw new org.xml.sax.SAXParseException(
                    "Invalid character '" + c + "' in parameter entity reference", this);
            }
        }
        
        // Didn't find ';' - need more data
        charBuffer.reset();
        return false;
    }
    
    /**
     * Tries to emit a whitespace token (S production).
     * @return true if token was emitted, false if more data is needed
     */
    private boolean tryEmitWhitespace() throws SAXException {
        int start = charBuffer.position();
        int count = 0;
        
        while (charBuffer.hasRemaining()) {
            charBuffer.mark();
            char c = charBuffer.get();
            if (isWhitespace(c)) {
                count++;
                if (c == '\n') {
                    lineNumber++;
                    columnNumber = 0;
                } else {
                    columnNumber++;
                }
            } else {
                // Not whitespace, rewind
                charBuffer.reset();
                break;
            }
        }
        
        if (count > 0) {
            // Emit whitespace token
            int end = charBuffer.position();
            CharBuffer wsBuffer = charBuffer.duplicate();
            wsBuffer.position(start);
            wsBuffer.limit(end);
            emitToken(Token.S, wsBuffer);
            return true;
        }
        
        return false;
    }
    
    /**
     * Tries to emit a NAME token or a keyword token.
     * This method performs context-aware keyword recognition.
     * @return true if token was emitted, false if more data is needed
     */
    private boolean tryEmitName() throws SAXException {
        int start = charBuffer.position();
        
        // Must start with NameStartChar
        if (!charBuffer.hasRemaining()) {
            return false;
        }
        charBuffer.mark();
        char c = charBuffer.get();
        if (!isNameStartChar(c)) {
            charBuffer.reset();
            return false;
        }
        updateColumn();
        
        // Followed by NameChar*
        if (!tryConsumeNameChars()) {
            return false; // Need more data
        }
        
        // Extract the name
        int end = charBuffer.position();
        CharBuffer nameBuffer = charBuffer.duplicate();
        nameBuffer.position(start);
        nameBuffer.limit(end);
        
        // Convert to string for keyword matching
        StringBuilder sb = new StringBuilder();
        while (nameBuffer.hasRemaining()) {
            sb.append(nameBuffer.get());
        }
        String name = sb.toString();
        
        // Check if this is a keyword (context-aware)
        Token keyword = recognizeKeyword(name);
        if (keyword != null) {
            emitToken(keyword, null);
        } else {
            // Emit NAME token
            CharBuffer savedBuffer = charBuffer.duplicate();
            savedBuffer.position(start);
            savedBuffer.limit(end);
            emitToken(Token.NAME, savedBuffer);
        }
        return true;
    }
    
    /**
     * Recognizes XML keywords in a context-aware manner.
     * Returns the keyword token if the name is a keyword, null otherwise.
     * 
     * Note: For now, we'll emit keywords whenever we see them.
     * A more sophisticated approach would track parser state to determine
     * when keywords are valid vs. when they're just names.
     * 
     * @param name the name to check
     * @return the keyword token, or null if not a keyword
     */
    private Token recognizeKeyword(String name) {
        // DOCTYPE keywords
        switch (name) {
            case "SYSTEM": return Token.SYSTEM;
            case "PUBLIC": return Token.PUBLIC;
            case "NDATA": return Token.NDATA;
            
            // ELEMENT content model keywords
            case "EMPTY": return Token.EMPTY;
            case "ANY": return Token.ANY;
            
            // ATTLIST type keywords
            case "CDATA": return Token.CDATA_TYPE;
            case "ID": return Token.ID;
            case "IDREF": return Token.IDREF;
            case "IDREFS": return Token.IDREFS;
            case "ENTITY": return Token.ENTITY;
            case "ENTITIES": return Token.ENTITIES;
            case "NMTOKEN": return Token.NMTOKEN;
            case "NMTOKENS": return Token.NMTOKENS;
            case "NOTATION": return Token.NOTATION;
            
            // ATTLIST default value keywords (when not prefixed with #)
            case "REQUIRED": return Token.REQUIRED;
            case "IMPLIED": return Token.IMPLIED;
            case "FIXED": return Token.FIXED;
            
            // Conditional section keywords
            case "INCLUDE": return Token.INCLUDE;
            case "IGNORE": return Token.IGNORE;
            
            default: return null; // Not a keyword
        }
    }
    
    /**
     * Tries to emit a hash-prefixed keyword token (#PCDATA, #REQUIRED, #IMPLIED, #FIXED).
     * @return true if token was emitted, false if more data is needed
     */
    private boolean tryEmitHashKeyword() throws SAXException {
        // We've already consumed '#'
        if (!charBuffer.hasRemaining()) {
            return false; // Need more data
        }
        
        charBuffer.mark();
        int nameStart = charBuffer.position();
        
        // Try to read a name
        while (charBuffer.hasRemaining()) {
            char c = charBuffer.get();
            if (!isNameChar(c)) {
                charBuffer.reset();
                charBuffer.position(charBuffer.position() - 1); // Back to before non-name char
                break;
            }
        }
        
        int nameEnd = charBuffer.position();
        
        // Extract the name
        if (nameEnd > nameStart) {
            charBuffer.position(nameStart);
            StringBuilder sb = new StringBuilder();
            while (charBuffer.position() < nameEnd) {
                sb.append(charBuffer.get());
                updateColumn();
            }
            String keyword = sb.toString();
            
            // Check if it's a known hash keyword
            Token token = null;
            switch (keyword) {
                case "PCDATA": token = Token.PCDATA; break;
                case "REQUIRED": token = Token.REQUIRED; break;
                case "IMPLIED": token = Token.IMPLIED; break;
                case "FIXED": token = Token.FIXED; break;
            }
            
            if (token != null) {
                emitToken(token, null);
                return true;
            }
        }
        
        // Not a keyword, just emit HASH
        charBuffer.position(nameStart); // Back to after '#'
        emitToken(Token.HASH, null);
        return true;
    }
    
    /**
     * Consumes NameChar* from the buffer.
     * @return true if we can proceed, false if we need more data
     */
    /**
     * Tries to consume NameChar* characters.
     * Returns true if we've reached a definitive name boundary (non-NameChar).
     * Returns false if we need more data (buffer exhausted while still seeing NameChars).
     * This ensures we never emit a partial NAME token.
     */
    private boolean tryConsumeNameChars() {
        while (charBuffer.hasRemaining()) {
            charBuffer.mark();
            char c = charBuffer.get();
            if (isNameChar(c)) {
                updateColumn();
            } else {
                // Found non-NameChar - this is a name boundary
                charBuffer.reset();
                return true; // We can emit the name
            }
        }
        // Exhausted buffer while still in NameChars - need more data
        return false;
    }
    
    /**
     * Tries to emit CDATA token.
     * CDATA is any character that is not a special token character.
     * @return true if token was emitted, false if more data is needed
     */
    private boolean tryEmitCDATA() throws SAXException {
        int start = charBuffer.position();
        int count = 0;
        
        while (charBuffer.hasRemaining()) {
            charBuffer.mark();
            char c = charBuffer.get();
            
            // Stop at special XML characters based on context
            boolean stopHere = false;
            
            switch (context) {
                case CONTENT:
                    // In element content, stop at: < & (and whitespace is separate token)
                    stopHere = (c == '<' || c == '&' || isWhitespace(c));
                    break;
                    
                case ATTR_VALUE:
                    // In attribute values, stop at: < & > and the matching quote
                    stopHere = (c == '<' || c == '&' || c == '>' ||
                               (attrQuoteChar != '\0' && c == attrQuoteChar));
                    break;
                    
                case COMMENT:
                    // In comments, stop at: - (for -->)
                    stopHere = (c == '-');
                    break;
                    
                case CDATA_SECTION:
                    // In CDATA sections, stop at: ] (for ]]>)
                    stopHere = (c == ']');
                    break;
                    
                case PI_DATA:
                    // In PI data, stop at: ? (for ?>)
                    stopHere = (c == '?');
                    break;
                    
                default:
                    // Shouldn't reach here in CDATA-emitting context, but be safe
                    // Use old conservative logic: stop at any special char
                    stopHere = (c == '<' || c == '&' || c == '>' || c == '\'' || c == '"' ||
                               c == ':' || c == '!' || c == '?' || c == '=' || c == '%' ||
                               c == ';' || c == '#' || c == '|' || c == '[' || c == ']' ||
                               c == '(' || c == ')' || c == '*' || c == '+' || c == ',' ||
                               isWhitespace(c));
                    break;
            }
            
            if (stopHere) {
                charBuffer.reset();
                break;
            }
            
            count++;
            if (c == '\n') {
                lineNumber++;
                columnNumber = 0;
            } else {
                columnNumber++;
            }
        }
        
        if (count > 0) {
            // Emit CDATA token
            int end = charBuffer.position();
            CharBuffer savedBuffer = charBuffer.duplicate();
            savedBuffer.position(start);
            savedBuffer.limit(end);
            emitToken(Token.CDATA, savedBuffer);
            return true;
        }
        
        return false;
    }
    
    /**
     * Helper to check if a character is XML whitespace.
     */
    private boolean isWhitespace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r';
    }
    
    /**
     * Helper to update column number by 1.
     */
    private void updateColumn() {
        columnNumber++;
    }
    
    /**
     * Emits a token to the consumer.
     * @param token the token type
     * @param data the token data (CharBuffer in read mode, or null)
     */
    private void emitToken(Token token, CharBuffer data) throws SAXException {
        consumer.receive(token, data);
        updateContext(token);
    }
    
    /**
     * Updates the tokenizer context based on the token that was just emitted.
     * This tracks whether we're in element content, attributes, comments, etc.
     * @param token the token that was just emitted
     */
    private void updateContext(Token token) {
        switch (token) {
            // Start element or end element tag
            case LT:
                context = TokenizerContext.ELEMENT_NAME;
                break;
            case START_END_ELEMENT:
                context = TokenizerContext.ELEMENT_NAME;
                break;
                
            // After element name, we can have attributes or closing
            case NAME:
                if (context == TokenizerContext.ELEMENT_NAME) {
                    context = TokenizerContext.ELEMENT_ATTRS;
                } else if (context == TokenizerContext.PI_TARGET) {
                    context = TokenizerContext.PI_DATA;
                }
                // In other contexts, NAME doesn't change context
                break;
                
            // Quotes - handle both attribute values and DOCTYPE quoted strings
            case QUOT:
            case APOS:
                if (context == TokenizerContext.ELEMENT_ATTRS) {
                    // Starting attribute value in element
                    prevContext = context;
                    context = TokenizerContext.ATTR_VALUE;
                    attrQuoteChar = (token == Token.QUOT) ? '"' : '\'';
                } else if (context == TokenizerContext.DOCTYPE || context == TokenizerContext.DOCTYPE_INTERNAL) {
                    // Starting quoted string in DOCTYPE (publicId/systemId/entity value)
                    prevContext = context;
                    context = TokenizerContext.ATTR_VALUE;
                    attrQuoteChar = (token == Token.QUOT) ? '"' : '\'';
                } else if (context == TokenizerContext.ATTR_VALUE) {
                    // Check if this is the closing quote
                    char currentChar = (token == Token.QUOT) ? '"' : '\'';
                    if (currentChar == attrQuoteChar) {
                        // Closing the quoted string - return to previous context
                        context = prevContext;
                        prevContext = TokenizerContext.CONTENT; // Reset
                        attrQuoteChar = '\0';
                    }
                    // If it's not the matching quote, stay in ATTR_VALUE
                }
                break;
                
            // End of element tag
            case GT:
                if (context == TokenizerContext.ELEMENT_ATTRS || context == TokenizerContext.ELEMENT_NAME) {
                    context = TokenizerContext.CONTENT;
                } else if (context == TokenizerContext.DOCTYPE) {
                    context = TokenizerContext.CONTENT;
                }
                break;
            case END_EMPTY_ELEMENT:
                context = TokenizerContext.CONTENT;
                break;
                
            // Processing instruction
            case START_PI:
                context = TokenizerContext.PI_TARGET;
                break;
            case END_PI:
                context = TokenizerContext.CONTENT;
                break;
                
            // Comment
            case START_COMMENT:
                prevContext = context; // Save current context
                context = TokenizerContext.COMMENT;
                break;
            case END_COMMENT:
                context = prevContext; // Restore previous context
                break;
                
            // CDATA section
            case START_CDATA:
                prevContext = context; // Save current context
                context = TokenizerContext.CDATA_SECTION;
                break;
            case END_CDATA:
                context = prevContext; // Restore previous context
                break;
                
            // DOCTYPE
            case START_DOCTYPE:
                context = TokenizerContext.DOCTYPE;
                break;
            case OPEN_BRACKET: // Internal subset
                if (context == TokenizerContext.DOCTYPE) {
                    context = TokenizerContext.DOCTYPE_INTERNAL;
                }
                break;
            case CLOSE_BRACKET:
                if (context == TokenizerContext.DOCTYPE_INTERNAL) {
                    context = TokenizerContext.DOCTYPE; // Back to DOCTYPE declaration
                }
                break;
                
            default:
                // Most tokens don't change context
                break;
        }
    }

    /**
     * Notifies the tokenizer that all data has been received.
     * No more invocations of receive will occur after this method
     * has been called, and to do so is an error.
     */
    public void close() throws SAXException {
        if (closed) {
            throw new IllegalStateException();
        }
        closed = true;

        // Process any remaining bytes
        if (decoder != null && byteUnderflow != null && byteUnderflow.hasRemaining()) {
            // Decode byte underflow into charBuffer
            charBuffer.clear(); // ready for writing
            if (charUnderflow != null && charUnderflow.hasRemaining()) {
                // prepend charUnderflow
                charBuffer.put(charUnderflow);
                // clear underflow
                charUnderflow.clear();
                charUnderflow.limit(0);
            }
            CoderResult result = decoder.decode(byteUnderflow, charBuffer, true);
            charBuffer.flip();
            
            // Normalize line endings
            normalizeLineEndings(charBuffer);
            
            // Tokenize remaining chars
            tokenize();
        } else if (charUnderflow != null && charUnderflow.hasRemaining()) {
            // No byte underflow, but we have character underflow
            charBuffer.clear();
            charBuffer.put(charUnderflow);
            charBuffer.flip();
            
            // Normalize line endings
            normalizeLineEndings(charBuffer);
            
            // Tokenize remaining chars
            tokenize();
        }
        
        // Any remaining characters in charBuffer indicate incomplete tokens
        // This is an error condition for well-formed XML
        if (charBuffer.hasRemaining()) {
            throw new org.xml.sax.SAXParseException("Incomplete token at end of document", this);
        }
    }

    /**
     * Resets the tokenizer state to allow reuse for parsing another document.
     *
     * <p>This method clears all parsing state, allowing the same XMLTokenizer
     * instance to be reused for multiple documents. The consumer reference
     * is preserved, as are the publicId and systemId unless explicitly changed
     * via {@link #setPublicId(String)} or {@link #setSystemId(String)}.
     *
     * <p>This method resets:
     * <ul>
     *   <li>All buffer state (byte and character buffers)</li>
     *   <li>Parsing state (back to INIT)</li>
     *   <li>Line and column numbers (back to 1 and 0)</li>
     *   <li>Charset detection state</li>
     *   <li>Context tracking (back to CONTENT)</li>
     *   <li>Closed flag</li>
     * </ul>
     *
     * @throws SAXException if reset fails
     */
    public void reset() throws SAXException {
        // Reset state machine
        state = State.INIT;
        closed = false;
        context = TokenizerContext.CONTENT;
        prevContext = TokenizerContext.CONTENT;
        attrQuoteChar = '\0';
        
        // Reset position tracking
        lineNumber = 1;
        columnNumber = 0;
        lastCharSeen = '\0';
        
        // Reset charset detection
        charset = StandardCharsets.UTF_8;
        version = null;
        bomCharset = null;
        decoder = null;
        postXMLDeclBytePosition = 0;
        
        // Clear buffers
        byteUnderflow = null;
        charUnderflow = null;
        charBuffer = null;
    }

    /**
     * Sets the public identifier for the document.
     *
     * <p>The public identifier is used for error reporting and may be used
     * by entity resolvers. This should be set before parsing begins or
     * immediately after {@link #reset()}.
     *
     * @param publicId the public identifier, or null if not available
     */
    public void setPublicId(String publicId) {
        this.publicId = publicId;
    }

    /**
     * Sets the system identifier for the document.
     *
     * <p>The system identifier (typically a URL) is used for resolving
     * relative URIs and for error reporting. This should be set before
     * parsing begins or immediately after {@link #reset()}.
     *
     * @param systemId the system identifier, or null if not available
     */
    public void setSystemId(String systemId) {
        this.systemId = systemId;
    }

    /**
     * Reports whether the document declares itself as standalone.
     *
     * <p>This returns true if the XML declaration includes standalone="yes",
     * false if it includes standalone="no", and false if there is no
     * standalone declaration.
     *
     * <p>This corresponds to the SAX2 feature
     * {@code http://xml.org/sax/features/is-standalone}.
     *
     * @return true if standalone="yes", false otherwise
     */
    public boolean isStandalone() {
        return standalone;
    }

    // Locator2 interface implementation

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

}

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
public class ExternalEntityDecoder implements Locator2 {
    
    // ===== Configuration and Document Metadata =====
    
    /**
     * The character set used to decode incoming bytes into characters.
     */
    private Charset charset = StandardCharsets.UTF_8;
    
    /**
     * Charset detected from BOM, if any.
     */
    private Charset bomCharset = null;
    
    /**
     * The XML version declared in the document (default "1.0").
     */
    private String version = "1.0";
    
    /**
     * Whether this document is standalone.
     */
    private boolean standalone = false;
    
    /**
     * The document's encoding name (from XML declaration or BOM).
     */
    private String encoding = null;
    
    /**
     * Public identifier for this entity.
     */
    private String publicId;
    
    /**
     * System identifier (URI) for this entity.
     */
    private String systemId;
    
    // ===== Tokenizer Integration =====
    
    /**
     * The tokenizer that receives decoded character data.
     */
    private final Tokenizer tokenizer;
    
    /**
     * Whether this decoder is for an external entity (vs. the document entity).
     * External entities have text declarations (no standalone attribute allowed),
     * while the document entity has an XML declaration (standalone attribute allowed).
     */
    private final boolean isExternalEntity;
    
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
     * Current column number (0-based, position in current line).
     */
    private long columnNumber = 0;
    
    /**
     * Total character position in the stream.
     */
    private long charPosition = 0;
    
    // ===== State =====
    
    /**
     * Decoder state for tracking processing phase.
     */
    private enum State {
        INIT,           // Initial state, looking for BOM
        SEEN_BOM,       // BOM detected and consumed, ready to decode
        CONTENT,        // Processing content (no BOM or BOM already handled)
        CLOSED          // Decoder has been closed, cannot receive more data
    }
    
    private State state = State.INIT;
    
    /**
     * Reference to the current byte buffer being processed (for charset switching).
     */
    private ByteBuffer currentByteBuffer = null;
    
    // ===== Accessors =====
    
    /**
     * Returns whether this decoder is for an external parsed entity.
     * 
     * @return true if this is an external entity, false if it's the document entity
     */
    public boolean isExternalEntity() {
        return isExternalEntity;
    }
    
    // ===== Constructor =====
    
    /**
     * Creates a new external entity decoder.
     * 
     * @param tokenizer the tokenizer to receive decoded characters
     * @param publicId public identifier for this entity (may be null)
     * @param systemId system identifier for this entity (may be null)
     * @param isExternalEntity true if this is an external parsed entity, false for document entity
     */
    public ExternalEntityDecoder(Tokenizer tokenizer, String publicId, String systemId, boolean isExternalEntity) {
        this.tokenizer = tokenizer;
        this.publicId = publicId;
        this.systemId = systemId;
        this.isExternalEntity = isExternalEntity;
        // Wire the tokenizer to this decoder so it can call setCharset when XML decl is parsed
        this.tokenizer.setExternalEntityDecoder(this);
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
        return lineNumber > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) lineNumber;
    }
    
    @Override
    public int getColumnNumber() {
        return columnNumber > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) columnNumber;
    }
    
    @Override
    public String getXMLVersion() {
        return version;
    }
    
    @Override
    public String getEncoding() {
        return encoding;
    }
    
    /**
     * Sets the XML version.
     * 
     * @param version the XML version ("1.0" or "1.1")
     */
    public void setVersion(String version) {
        this.version = version;
    }
    
    /**
     * Sets the standalone flag.
     * 
     * @param standalone true if the document is standalone
     */
    public void setStandalone(boolean standalone) {
        this.standalone = standalone;
    }
    
    /**
     * Returns whether the document is standalone.
     * 
     * @return true if the document is standalone
     */
    public boolean isStandalone() {
        return standalone;
    }
    
    // ===== Public API =====
    
    /**
     * Receives a buffer of bytes to decode and tokenize.
     * 
     * @param buffer the byte buffer to process
     * @throws SAXException if a parsing error occurs
     */
    public void receive(ByteBuffer buffer) throws SAXException {
        if (state == State.CLOSED) {
            throw new IllegalStateException("Decoder is closed");
        }
        
        // Set this decoder as the locator for the tokenizer on first receive
        if (state == State.INIT) {
            tokenizer.setLocator(this);
        }
        
        // Combine with any underflow from previous receive
        ByteBuffer combined;
        if (byteUnderflow != null && byteUnderflow.hasRemaining()) {
            int totalSize = byteUnderflow.remaining() + buffer.remaining();
            if (byteWorkingBuffer == null || byteWorkingBuffer.capacity() < totalSize) {
                byteWorkingBuffer = ByteBuffer.allocate(Math.max(totalSize, 4096));
            }
            byteWorkingBuffer.clear();
            byteWorkingBuffer.put(byteUnderflow);
            byteWorkingBuffer.put(buffer);
            byteWorkingBuffer.flip();
            combined = byteWorkingBuffer;
            
            byteUnderflow.clear();
            byteUnderflow.limit(0);
        } else {
            combined = buffer;
        }
        
        // Process based on state
        switch (state) {
            case INIT:
                // Detect BOM
                if (!detectBOM(combined)) {
                    // Need more bytes to determine if there's a BOM
                    saveByteUnderflow(combined);
                    return;
                }
                // detectBOM has set state to SEEN_BOM or CONTENT
                // Fall through
                
            case SEEN_BOM:
            case CONTENT:
                // Initialize decoder if not already done
                if (decoder == null) {
                    decoder = charset.newDecoder();
                    charBuffer = CharBuffer.allocate(Math.max(4096, combined.capacity() * 2));
                }
                // Decode and tokenize
                decodeAndTokenize(combined);
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
     */
    public void reset() {
        state = State.INIT;
        charset = StandardCharsets.UTF_8;
        bomCharset = null;
        version = "1.0";
        standalone = false;
        encoding = null;
        lineNumber = 1;
        columnNumber = 0;
        charPosition = 0;
        decoder = null;
        charBuffer = null;
        byteUnderflow = null;
        byteWorkingBuffer = null;
        currentByteBuffer = null;
    }
    
    /**
     * Sets the encoding (called by tokenizer after parsing XML declaration).
     */
    void setEncoding(String encoding) {
        this.encoding = encoding;
    }
    
    /**
     * Sets the public identifier for this entity.
     * @param publicId the public identifier, or null
     */
    public void setPublicId(String publicId) {
        this.publicId = publicId;
    }
    
    /**
     * Sets the system identifier for this entity.
     * @param systemId the system identifier, or null
     */
    public void setSystemId(String systemId) {
        this.systemId = systemId;
    }
    
    /**
     * Gets the current charset.
     */
    Charset getCharset() {
        return charset;
    }
    
    /**
     * Sets the charset (for charset switching based on XML declaration).
     */
    /**
     * Sets the charset based on the encoding name from XML declaration.
     * 
     * @param encodingName the encoding name (e.g., "UTF-8", "ISO-8859-1")
     * @throws IllegalArgumentException if the encoding is not supported
     */
    public void setCharset(String encodingName) {
        this.charset = Charset.forName(encodingName);
        this.encoding = encodingName;
        // Recreate decoder with new charset
        this.decoder = this.charset.newDecoder();
    }
    
    /**
     * Gets the BOM-detected charset, if any.
     */
    Charset getBomCharset() {
        return bomCharset;
    }
    
    /**
     * Gets the current byte buffer (for charset switching).
     */
    ByteBuffer getCurrentByteBuffer() {
        return currentByteBuffer;
    }
    
    /**
     * Reinitializes the decoder with a new charset.
     */
    void reinitializeDecoder() {
        decoder = charset.newDecoder();
    }
    
    // ===== BOM Detection =====
    
    /**
     * Detects and consumes a BOM (Byte Order Mark) if present.
     * 
     * @return true if BOM detection is complete (BOM found or definitely absent),
     *         false if more bytes are needed
     */
    private boolean detectBOM(ByteBuffer buffer) {
        if (buffer.remaining() < 2) {
            return false; // Need at least 2 bytes
        }
        
        int b0 = buffer.get(buffer.position()) & 0xFF;
        int b1 = buffer.get(buffer.position() + 1) & 0xFF;
        
        // UTF-16 BE BOM: FE FF
        if (b0 == 0xFE && b1 == 0xFF) {
            charset = StandardCharsets.UTF_16BE;
            bomCharset = charset;
            encoding = charset.name();
            buffer.position(buffer.position() + 2);
            state = State.SEEN_BOM;
            return true;
        }
        
        // UTF-16 LE BOM: FF FE
        if (b0 == 0xFF && b1 == 0xFE) {
            // Could also be UTF-32 LE (FF FE 00 00), check if we have 4 bytes
            if (buffer.remaining() >= 4) {
                int b2 = buffer.get(buffer.position() + 2) & 0xFF;
                int b3 = buffer.get(buffer.position() + 3) & 0xFF;
                if (b2 == 0x00 && b3 == 0x00) {
                    // UTF-32 LE
                    try {
                        charset = Charset.forName("UTF-32LE");
                        bomCharset = charset;
                        encoding = charset.name();
                        buffer.position(buffer.position() + 4);
                        state = State.SEEN_BOM;
                        return true;
                    } catch (Exception e) {
                        // UTF-32LE not supported, fall through to UTF-16LE
                    }
                }
            }
            // UTF-16 LE
            charset = StandardCharsets.UTF_16LE;
            bomCharset = charset;
            encoding = charset.name();
            buffer.position(buffer.position() + 2);
            state = State.SEEN_BOM;
            return true;
        }
        
        // UTF-8 BOM: EF BB BF
        if (buffer.remaining() >= 3) {
            int b2 = buffer.get(buffer.position() + 2) & 0xFF;
            if (b0 == 0xEF && b1 == 0xBB && b2 == 0xBF) {
                charset = StandardCharsets.UTF_8;
                bomCharset = charset;
                encoding = charset.name();
                buffer.position(buffer.position() + 3);
                state = State.SEEN_BOM;
                return true;
            }
        } else {
            return false; // Need 3 bytes to rule out UTF-8 BOM
        }
        
        // UTF-32 BE BOM: 00 00 FE FF
        if (buffer.remaining() >= 4) {
            int b2 = buffer.get(buffer.position() + 2) & 0xFF;
            int b3 = buffer.get(buffer.position() + 3) & 0xFF;
            if (b0 == 0x00 && b1 == 0x00 && b2 == 0xFE && b3 == 0xFF) {
                try {
                    charset = Charset.forName("UTF-32BE");
                    bomCharset = charset;
                    encoding = charset.name();
                    buffer.position(buffer.position() + 4);
                    state = State.SEEN_BOM;
                    return true;
                } catch (Exception e) {
                    // UTF-32BE not supported, treat as no BOM
                }
            }
        }
        
        // No BOM detected - start processing content with default charset (UTF-8)
        state = State.CONTENT;
        return true;
    }
    
    /**
     * Saves remaining bytes to underflow buffer for next receive() call.
     */
    private void saveByteUnderflow(ByteBuffer buffer) {
        if (buffer.hasRemaining()) {
            if (byteUnderflow == null || byteUnderflow.capacity() < buffer.remaining()) {
                byteUnderflow = ByteBuffer.allocate(Math.max(buffer.remaining() * 2, 1024));
            } else {
                byteUnderflow.clear();
            }
            byteUnderflow.put(buffer);
            byteUnderflow.flip();
        }
    }
    
    // ===== Decoding and Line Normalization =====
    
    /**
     * Decodes bytes to characters, normalizes line endings, and passes to tokenizer.
     */
    private void decodeAndTokenize(ByteBuffer buffer) throws SAXException {
        // Save reference to current byte buffer (for potential charset switching)
        currentByteBuffer = buffer;
        
        // Ensure charBuffer is large enough
        int neededCapacity = buffer.remaining() * 2;
        if (charBuffer == null || charBuffer.capacity() < neededCapacity) {
            int newCapacity = Math.max(neededCapacity, 4096);
            charBuffer = CharBuffer.allocate(newCapacity);
        }
        
        // Clear buffer for decoding
        charBuffer.clear();
        
        // Decode bytes to characters
        CoderResult result = decoder.decode(buffer, charBuffer, false);
        charBuffer.flip();
        
        // Normalize line endings
        normalizeLineEndings(charBuffer);
        
        // Update position tracking
        updatePositions(charBuffer);
        
        // Pass to tokenizer
        tokenizer.receive(charBuffer);
        
        // Save byte underflow
        if (buffer.hasRemaining()) {
            saveByteUnderflow(buffer);
        } else if (byteUnderflow != null) {
            byteUnderflow.clear();
            byteUnderflow.limit(0);
        }
    }
    
    /**
     * Normalizes line endings in the character buffer according to XML spec.
     * Converts CR, CR LF, and NEL (U+0085) to LF (U+000A).
     */
    private void normalizeLineEndings(CharBuffer buffer) {
        int pos = buffer.position();
        int limit = buffer.limit();
        
        for (int i = pos; i < limit; i++) {
            char c = buffer.get(i);
            
            if (c == '\r') {
                // CR or CR LF -> LF
                buffer.put(i, '\n');
                if (i + 1 < limit && buffer.get(i + 1) == '\n') {
                    // CR LF: skip the LF by compacting
                    for (int j = i + 1; j < limit - 1; j++) {
                        buffer.put(j, buffer.get(j + 1));
                    }
                    limit--;
                    buffer.limit(limit);
                }
            } else if (c == '\u0085') {
                // NEL (Next Line) -> LF
                buffer.put(i, '\n');
            }
        }
    }
    
    /**
     * Updates line and column numbers based on characters in the buffer.
     */
    private void updatePositions(CharBuffer buffer) {
        int pos = buffer.position();
        int limit = buffer.limit();
        
        for (int i = pos; i < limit; i++) {
            char c = buffer.get(i);
            charPosition++;
            
            if (c == '\n') {
                lineNumber++;
                columnNumber = 0;
            } else {
                columnNumber++;
            }
        }
    }
}


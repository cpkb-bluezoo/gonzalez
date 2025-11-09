package org.bluezoo.gonzalez;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.StandardCharsets;
import org.xml.sax.ext.Locator2;

/**
 * Manages buffers for streaming XML parsing with 3-phase charset detection.
 * 
 * <p>This class encapsulates the buffer management strategy used by both
 * {@link GonzalezParser} and {@link org.bluezoo.gonzalez.dtd.DTDParser}.
 * It also implements {@link Locator2} to provide document location information.
 * 
 * <h2>Buffer Architecture</h2>
 * 
 * <p>The buffer management proceeds in three phases:
 * 
 * <ol>
 * <li><b>Byte Mode</b>: Initially, incoming bytes are buffered in {@code underflowBytes}
 *     until enough data arrives to detect the character encoding via BOM or XML/text
 *     declaration parsing.</li>
 * 
 * <li><b>Charset Detection</b>: Once sufficient bytes are available, the encoding is
 *     determined (defaulting to UTF-8 if not specified). The decoder is configured,
 *     and any remaining bytes in {@code underflowBytes} are decoded into {@code parseBuffer}.
 *     The {@code underflowBytes} buffer is then released.</li>
 * 
 * <li><b>Character Mode</b>: All subsequent incoming bytes are decoded directly into
 *     {@code parseBuffer}. If a parse operation cannot complete due to insufficient data
 *     (e.g., an incomplete element tag split across buffer boundaries), the unparsed
 *     characters are saved to {@code underflowChars} via {@link #moveToUnderflow()}.
 *     On the next {@link #receive(ByteBuffer)}, {@code underflowChars} is prepended to
 *     the newly decoded data in {@code parseBuffer}.</li>
 * </ol>
 * 
 * <h2>Thread Safety</h2>
 * 
 * <p>This class is <b>not</b> thread-safe. The caller is responsible for synchronization
 * if the buffer is accessed from multiple threads.
 * 
 * @see GonzalezParser
 * @see org.bluezoo.gonzalez.dtd.DTDParser
 */
public class XMLParserBuffer implements Locator2 {
    
    // Buffers
    private ByteBuffer underflowBytes;     // Undecoded bytes (phase 1: before charset detection)
    private CharBuffer underflowChars;     // Incomplete tokens from previous receive()
    private CharBuffer parseBuffer;        // Active parsing buffer (always in read mode)
    
    // Charset state
    private Charset charset;
    private CharsetDecoder decoder;
    private boolean charsetDetermined;
    
    // Locator state
    private String publicId;
    private String systemId;
    private String xmlVersion = "1.0";
    
    // Position tracking (for line/column calculation)
    private int line;
    private int column;
    private boolean lastCharWasCR;  // Track if last consumed char was CR (for CRLF handling)
    
    /**
     * Creates a new parser buffer with default initial charset (UTF-8).
     */
    public XMLParserBuffer() {
        this(StandardCharsets.UTF_8);
    }
    
    /**
     * Creates a new parser buffer with specified initial charset.
     * 
     * <p>The initial charset is used as a hint and may be overridden by BOM
     * or XML/text declaration detection.
     * 
     * @param initialCharset the initial charset hint (must not be null)
     */
    public XMLParserBuffer(Charset initialCharset) {
        if (initialCharset == null) {
            throw new IllegalArgumentException("initialCharset must not be null");
        }
        
        this.charset = initialCharset;
        this.decoder = initialCharset.newDecoder();
        this.underflowBytes = ByteBuffer.allocate(256);  // Small - just for BOM/declaration
        this.underflowChars = CharBuffer.allocate(1024);
        this.underflowChars.flip(); // Start empty in read mode
        this.parseBuffer = CharBuffer.allocate(2048);
        this.parseBuffer.flip(); // Start empty in read mode
        this.charsetDetermined = false;
        this.line = 1;
        this.column = 0;
        this.lastCharWasCR = false;
    }
    
    /**
     * Receives incoming byte data and prepares the parse buffer.
     * 
     * <p>This method handles charset detection, decoding, and prepending of underflow data.
     * After this method returns, {@link #getParseBuffer()} will contain the decoded characters
     * ready for parsing.
     * 
     * @param data the incoming byte data
     * @throws CharacterCodingException if character decoding fails
     */
    public void receive(ByteBuffer data) throws Exception {
        if (!charsetDetermined) {
            // Phase 1: Charset not yet determined - buffer bytes
            ensureByteCapacity(data.remaining());
            underflowBytes.put(data);
            underflowBytes.flip();
            
            // Try to detect charset from BOM or declaration
            if (underflowBytes.hasRemaining()) {
                detectCharset();
            }
            
            // Decode whatever bytes we have into parseBuffer
            if (underflowBytes != null && underflowBytes.hasRemaining()) {
                parseBuffer.clear();
                CoderResult result = decoder.decode(underflowBytes, parseBuffer, false);
                if (result.isError()) {
                    result.throwException();
                }
                parseBuffer.flip(); // Now in read mode
            } else {
                parseBuffer.clear();
                parseBuffer.flip(); // Empty
            }
            
            // Compact underflowBytes if not released
            if (underflowBytes != null) {
                underflowBytes.compact();
            }
            
            // Prepend underflow to parseBuffer
            prependUnderflow();
            
            // Release underflowBytes if charset is determined and all bytes decoded
            if (charsetDetermined && underflowBytes != null && !underflowBytes.hasRemaining()) {
                underflowBytes = null;
            }
        } else {
            // Phase 2: Charset determined - decode directly
            parseBuffer.clear();
            CoderResult result = decoder.decode(data, parseBuffer, false);
            if (result.isError()) {
                result.throwException();
            }
            parseBuffer.flip(); // Now in read mode
            
            // Prepend underflow to parseBuffer
            prependUnderflow();
        }
    }
    
    /**
     * Signals that no more data will arrive.
     * 
     * <p>This method processes any remaining data in the underflow buffers.
     * 
     * @throws Exception if final decoding fails
     */
    public void close() throws Exception {
        // If charset not yet determined, switch to char mode now
        if (!charsetDetermined) {
            charsetDetermined = true;
            underflowBytes = null;
        }
        
        // Process any remaining underflow
        if (underflowChars.hasRemaining()) {
            prependUnderflow();
        }
    }
    
    /**
     * Moves remaining parseBuffer data to underflow.
     * 
     * <p>Call this when a parse operation cannot complete due to insufficient data.
     * After this call, {@code parseBuffer} will be empty and {@code underflowChars}
     * will contain the saved data. On the next {@link #receive(ByteBuffer)}, the
     * underflow will be prepended to new data.
     */
    public void moveToUnderflow() {
        int remaining = parseBuffer.remaining();
        if (remaining > 0) {
            // Ensure underflowChars has capacity
            if (underflowChars.capacity() < remaining) {
                underflowChars = CharBuffer.allocate(remaining * 2);
            }
            underflowChars.clear();
            underflowChars.put(parseBuffer);
            underflowChars.flip(); // Ready to read
        }
        // Clear parseBuffer so parse loop exits
        parseBuffer.clear();
        parseBuffer.flip(); // Empty in read mode
    }
    
    /**
     * Returns the parse buffer for reading.
     * 
     * <p>The buffer is always in read mode (positioned for reading).
     * 
     * @return the parse buffer
     */
    public CharBuffer getParseBuffer() {
        return parseBuffer;
    }
    
    /**
     * Returns whether the parse buffer has remaining characters.
     * 
     * @return true if characters are available to parse
     */
    public boolean hasRemaining() {
        return parseBuffer.hasRemaining();
    }
    
    /**
     * Returns whether at least {@code count} characters are available.
     * 
     * @param count the number of characters needed
     * @return true if at least {@code count} characters are available
     */
    public boolean hasAvailable(int count) {
        return parseBuffer.remaining() >= count;
    }
    
    /**
     * Peeks at the current character without consuming it.
     * 
     * @return the current character, or 0 if no data available
     */
    public char peekChar() {
        if (!parseBuffer.hasRemaining()) {
            return 0;
        }
        return parseBuffer.get(parseBuffer.position());
    }
    
    /**
     * Peeks ahead {@code offset} characters without consuming.
     * 
     * @param offset the number of characters to look ahead
     * @return the character at the offset, or 0 if not available
     */
    public char peekChar(int offset) {
        int pos = parseBuffer.position() + offset;
        if (pos >= parseBuffer.limit()) {
            return 0;
        }
        return parseBuffer.get(pos);
    }
    
    /**
     * Consumes a single character from the buffer and updates position tracking.
     * 
     * <p>Handles line endings according to XML 1.0 spec section 2.11:
     * <ul>
     * <li>LF (U+000A) → new line</li>
     * <li>CR (U+000D) → new line</li>
     * <li>CRLF (U+000D U+000A) → single new line</li>
     * </ul>
     * 
     * @return the consumed character
     */
    public char consumeChar() {
        char ch = parseBuffer.get();
        
        // Check if this LF is the second half of a CRLF split across buffers
        if (lastCharWasCR && ch == '\n') {
            // Already counted the line for CR, just clear the flag
            lastCharWasCR = false;
            return ch;
        }
        
        // Clear the CR flag if we're consuming anything other than LF after CR
        lastCharWasCR = false;
        
        if (ch == '\n') {
            // LF - new line
            line++;
            column = 0;
        } else if (ch == '\r') {
            // CR - check if followed by LF (CRLF)
            if (parseBuffer.hasRemaining() && peekChar() == '\n') {
                // CRLF in same buffer - consume the LF as well
                parseBuffer.get();
            } else {
                // CR at end of buffer or followed by non-LF
                lastCharWasCR = true;
            }
            // Either CR alone or CRLF - both count as single line ending
            line++;
            column = 0;
        } else {
            column++;
        }
        
        return ch;
    }
    
    /**
     * Skips whitespace characters.
     */
    public void skipWhitespace() {
        while (parseBuffer.hasRemaining()) {
            char ch = peekChar();
            if (Character.isWhitespace(ch)) {
                consumeChar();
            } else {
                break;
            }
        }
    }
    
    /**
     * Checks if the parse buffer starts with the given keyword.
     * 
     * @param keyword the keyword to match
     * @return true if the buffer starts with the keyword
     */
    public boolean matchesKeyword(String keyword) {
        if (parseBuffer.remaining() < keyword.length()) {
            return false;
        }
        
        int pos = parseBuffer.position();
        for (int i = 0; i < keyword.length(); i++) {
            if (parseBuffer.get(pos + i) != keyword.charAt(i)) {
                return false;
            }
        }
        return true;
    }
    
    /**
     * Returns the determined character encoding.
     * 
     * @return the charset, or null if not yet determined
     */
    public Charset getCharset() {
        return charsetDetermined ? charset : null;
    }
    
    /**
     * Returns whether the charset has been determined.
     * 
     * @return true if charset is determined
     */
    public boolean isCharsetDetermined() {
        return charsetDetermined;
    }
    
    // Position tracking
    private int savedBufferPosition;
    private int savedLine;
    private int savedColumn;
    private boolean savedLastCharWasCR;
    
    /**
     * Saves the current parse position for potential backtracking.
     * Call this before attempting a parse operation that might need to be rewound.
     */
    public void savePosition() {
        savedBufferPosition = parseBuffer.position();
        savedLine = line;
        savedColumn = column;
        savedLastCharWasCR = lastCharWasCR;
    }
    
    /**
     * Restores the previously saved parse position.
     * Call this to rewind to the position saved by {@link #savePosition()}.
     */
    public void restorePosition() {
        parseBuffer.position(savedBufferPosition);
        line = savedLine;
        column = savedColumn;
        lastCharWasCR = savedLastCharWasCR;
    }
    
    // Locator2 interface implementation
    
    /**
     * Sets the public identifier for the current document location.
     * 
     * @param publicId the public identifier
     */
    public void setPublicId(String publicId) {
        this.publicId = publicId;
    }
    
    /**
     * Sets the system identifier for the current document location.
     * 
     * @param systemId the system identifier
     */
    public void setSystemId(String systemId) {
        this.systemId = systemId;
    }
    
    /**
     * Sets the XML version.
     * 
     * @param version the XML version string (e.g., "1.0" or "1.1")
     */
    public void setXMLVersion(String version) {
        this.xmlVersion = version;
    }
    
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
        return line;
    }
    
    @Override
    public int getColumnNumber() {
        return column;
    }
    
    @Override
    public String getXMLVersion() {
        return xmlVersion;
    }
    
    @Override
    public String getEncoding() {
        return (charset != null) ? charset.name() : null;
    }
    
    // Private helper methods
    
    private void ensureByteCapacity(int additional) {
        if (underflowBytes.remaining() < additional) {
            int newCapacity = underflowBytes.capacity() + additional;
            ByteBuffer newBuffer = ByteBuffer.allocate(newCapacity);
            underflowBytes.flip();
            newBuffer.put(underflowBytes);
            underflowBytes = newBuffer;
        }
    }
    
    private void prependUnderflow() {
        int underflowSize = underflowChars.remaining();
        if (underflowSize == 0) {
            return;
        }
        
        int parseBufferData = parseBuffer.remaining();
        int totalSize = underflowSize + parseBufferData;
        
        // Optimization: if everything fits in current parseBuffer capacity, reuse it
        if (totalSize <= parseBuffer.capacity()) {
            // 1. Save parseBuffer data
            char[] temp = new char[parseBufferData];
            parseBuffer.get(temp);
            
            // 2. Reset and write: underflow first, then parseBuffer data
            parseBuffer.clear();
            parseBuffer.put(underflowChars);
            parseBuffer.put(temp);
            parseBuffer.flip();
        } else {
            // Need larger buffer
            CharBuffer newBuffer = CharBuffer.allocate(totalSize * 2);
            newBuffer.put(underflowChars);
            newBuffer.put(parseBuffer);
            newBuffer.flip();
            parseBuffer = newBuffer;
        }
        
        // Empty the underflow since we've consumed it
        underflowChars.clear();
        underflowChars.limit(0); // Empty in read mode
    }
    
    /**
     * Detects the character encoding via BOM and/or XML/text declaration.
     * 
     * <p>External parsed entities can have their own encoding per XML spec section 4.3.3.
     * This method detects:
     * <ul>
     * <li>UTF-8/16/32 BOMs</li>
     * <li>XML/text declarations: {@code <?xml encoding="..."?>}</li>
     * <li>Defaults to initial charset if neither present</li>
     * </ul>
     */
    private void detectCharset() throws Exception {
        // Check for BOM
        if (underflowBytes.remaining() >= 2) {
            int b1 = underflowBytes.get(0) & 0xFF;
            int b2 = underflowBytes.get(1) & 0xFF;
            
            // UTF-16 BE BOM
            if (b1 == 0xFE && b2 == 0xFF) {
                setCharset(StandardCharsets.UTF_16BE);
                underflowBytes.position(underflowBytes.position() + 2); // Skip BOM
                charsetDetermined = true;
                return;
            }
            
            // UTF-16 LE BOM
            if (b1 == 0xFF && b2 == 0xFE) {
                // Could also be UTF-32 LE, check for 00 00
                if (underflowBytes.remaining() >= 4) {
                    int b3 = underflowBytes.get(2) & 0xFF;
                    int b4 = underflowBytes.get(3) & 0xFF;
                    if (b3 == 0x00 && b4 == 0x00) {
                        // UTF-32 LE
                        try {
                            setCharset(Charset.forName("UTF-32LE"));
                            underflowBytes.position(underflowBytes.position() + 4);
                            charsetDetermined = true;
                            return;
                        } catch (Exception e) {
                            // UTF-32 not supported, fall through
                        }
                    }
                }
                // UTF-16 LE
                setCharset(StandardCharsets.UTF_16LE);
                underflowBytes.position(underflowBytes.position() + 2);
                charsetDetermined = true;
                return;
            }
            
            // UTF-8 BOM
            if (underflowBytes.remaining() >= 3) {
                int b3 = underflowBytes.get(2) & 0xFF;
                if (b1 == 0xEF && b2 == 0xBB && b3 == 0xBF) {
                    setCharset(StandardCharsets.UTF_8);
                    underflowBytes.position(underflowBytes.position() + 3); // Skip BOM
                    charsetDetermined = true;
                    return;
                }
            }
        }
        
        // TODO: Parse XML/text declaration for encoding attribute
        // For now, just use the initial charset
        
        // No BOM found - use initial charset and switch to character mode
        charsetDetermined = true;
    }
    
    private void setCharset(Charset newCharset) {
        this.charset = newCharset;
        this.decoder = newCharset.newDecoder();
    }
}


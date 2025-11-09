/*
 * GonzalezParser.java
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
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import org.xml.sax.Attributes;
import org.xml.sax.ContentHandler;
import org.xml.sax.DTDHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.ext.DeclHandler;
import org.xml.sax.ext.LexicalHandler;
import org.xml.sax.helpers.DefaultHandler;
import org.xml.sax.helpers.NamespaceSupport;

/**
 * A streaming, non-blocking XML parser that produces SAX events.
 * 
 * <p>Gonzalez is a completely data-driven XML parser designed for non-blocking
 * I/O environments. Unlike traditional SAX parsers that pull data from an
 * InputSource, Gonzalez receives data pushed to it via {@link #receive(ByteBuffer)}.
 * This makes it ideal for integration with asynchronous I/O frameworks like
 * the Gumdrop HTTP client.
 * 
 * <p><strong>Key Features:</strong>
 * <ul>
 * <li>Non-blocking: Never reads from streams or blocks waiting for data</li>
 * <li>Data-driven: Processes whatever data is available, requests more when needed</li>
 * <li>Streaming: Memory-efficient, processes documents of any size</li>
 * <li>SAX-compatible: Generates standard SAX ContentHandler events</li>
 * <li>Entity resolution: Supports asynchronous external entity resolution</li>
 * <li>Lazy DTD: DTD parser loaded only when needed</li>
 * </ul>
 * 
 * <p><strong>Usage Example:</strong>
 * <pre>{@code
 * GonzalezParser parser = new GonzalezParser();
 * parser.setContentHandler(myContentHandler);
 * parser.setEntityResolver(myAsyncResolver);
 * 
 * // Feed data as it arrives (e.g., from HTTP response)
 * parser.receive(byteBuffer1);
 * parser.receive(byteBuffer2);
 * // ...
 * parser.close(); // Signal end of document
 * }</pre>
 * 
 * <p><strong>Threading:</strong> This parser is not thread-safe. All methods
 * must be called from the same thread, or appropriate synchronization must
 * be applied.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public class GonzalezParser {

    private enum ParseState {
        INITIAL,              // Before any content
        XML_DECLARATION,      // XML declaration
        PROLOG,               // Pre-root content (DOCTYPE, comments, PIs)
        ELEMENT_START,        // Parsing start tag
        ELEMENT_CONTENT,      // Inside element (text or nested elements)
        ELEMENT_END,          // Parsing end tag
        TEXT,                 // Text content
        CDATA,                // CDATA section
        COMMENT,              // Comment
        PI,                   // Processing instruction
        DOCTYPE,              // DOCTYPE declaration
        DTD_INTERNAL_SUBSET,  // Processing internal DTD subset
        DONE                  // Document complete
    }

    /**
     * Immutable holder for QName information to avoid re-parsing.
     * Stores the full qName string and the index of the colon separator.
     * If colonIndex is -1, there is no prefix.
     */
    private static class QName {
        final String qName;        // Full qualified name (prefix:local or just local)
        final int colonIndex;      // Index of ':' or -1 if no prefix

        QName(String qName, int colonIndex) {
            this.qName = qName;
            this.colonIndex = colonIndex;
        }

        String getPrefix() {
            return (colonIndex == -1) ? "" : qName.substring(0, colonIndex);
        }

        String getLocalName() {
            return (colonIndex == -1) ? qName : qName.substring(colonIndex + 1);
        }

        boolean hasPrefix() {
            return colonIndex != -1;
        }
    }

    // Configuration
    private ContentHandler contentHandler;
    private DTDHandler dtdHandler;
    private LexicalHandler lexicalHandler;
    private DeclHandler declHandler;
    private GonzalezLocator locator;
    private AsyncEntityResolverFactory entityResolverFactory;

    // DTD parser (lazy-loaded)
    private org.bluezoo.gonzalez.dtd.DTDParser dtdParser;

    // Namespace support
    private NamespaceSupport namespaceSupport;
    private boolean namespacePrefixes = false; // Whether to report xmlns attributes

    // State
    private ParseState state;
    private ByteBuffer parseBuffer;
    private CharBuffer charBuffer;
    private CharBuffer tokenBuffer;  // For buffering incomplete tokens across receive() calls
    private Charset charset;
    private CharsetDecoder decoder;
    private boolean charsetDetermined;
    private StringBuilder textAccumulator;

    // Entity resolution state
    private Deque<ParseContext> contextStack;
    private boolean inEntity;

    // Element stack for validation
    private Deque<String> elementStack;

    // Entity definitions
    private Map<String, String> generalEntities;

    // Document state
    private int line;
    private int column;
    private boolean lastCharWasCR; // Track if last consumed char was CR to handle CRLF splits
    private boolean documentStarted;
    private boolean documentEnded;
    private boolean standalone; // From XML declaration

    // Limits for security
    private static final int MAX_ENTITY_DEPTH = 20;
    private static final int MAX_ENTITY_SIZE = 10 * 1024 * 1024; // 10MB
    private static final int MAX_NAME_LENGTH = 8192; // 8KB for element/attribute names
    private static final int MAX_ATTRIBUTE_VALUE_LENGTH = 1024 * 1024; // 1MB for attribute values
    private int entityDepth;
    private long totalEntitySize;
    
    /**
     * Creates a new Gonzalez parser with unknown charset.
     * 
     * <p>The parser will detect the charset via BOM (if present), then by
     * parsing the XML declaration, defaulting to UTF-8 if neither is available.
     */
    public GonzalezParser() {
        this(null);
    }
    
    /**
     * Creates a new Gonzalez parser with the specified charset.
     * 
     * <p>If charset is null, the parser will auto-detect the encoding via BOM
     * testing and XML declaration parsing, defaulting to UTF-8. If charset is
     * specified, that encoding will be used and no auto-detection will occur.
     * 
     * @param charset the character encoding to use, or null for auto-detection
     */
    public GonzalezParser(Charset charset) {
        this.contentHandler = new DefaultHandler();
        this.locator = new GonzalezLocator();
        this.parseBuffer = ByteBuffer.allocate(8192);
        this.charBuffer = CharBuffer.allocate(4096);
        this.tokenBuffer = CharBuffer.allocate(256); // Start small, grows as needed
        this.tokenBuffer.flip(); // Start in read mode (empty)
        this.charset = charset;
        this.charsetDetermined = (charset != null);
        if (charset != null) {
            this.decoder = charset.newDecoder();
            this.locator.setEncoding(charset.name());
        }
        else {
            // Start with UTF-8 decoder, will be replaced after BOM/XML decl detection
            this.decoder = StandardCharsets.UTF_8.newDecoder();
        }
        this.textAccumulator = new StringBuilder();
        this.contextStack = new ArrayDeque<>();
        this.elementStack = new ArrayDeque<>();
        this.generalEntities = new HashMap<>();

        // Initialize predefined entities
        generalEntities.put("amp", "&");
        generalEntities.put("lt", "<");
        generalEntities.put("gt", ">");
        generalEntities.put("quot", "\"");
        generalEntities.put("apos", "'");

        this.state = ParseState.INITIAL;
        this.line = 1;
        this.column = 0;
        this.lastCharWasCR = false;
        this.entityDepth = 0;
        this.totalEntitySize = 0;

        // Initialize namespace support
        this.namespaceSupport = new NamespaceSupport();
    }
    
    /**
     * Sets the content handler to receive SAX events.
     * 
     * @param handler the content handler
     */
    public void setContentHandler(ContentHandler handler) {
        this.contentHandler = (handler != null) ? handler : new DefaultHandler();

        // Check if handler also implements other SAX interfaces
        if (handler instanceof LexicalHandler) {
            this.lexicalHandler = (LexicalHandler) handler;
        }
        else {
            this.lexicalHandler = null;
        }

        if (handler instanceof DTDHandler) {
            this.dtdHandler = (DTDHandler) handler;
        }
        else {
            this.dtdHandler = null;
        }

        if (handler instanceof DeclHandler) {
            this.declHandler = (DeclHandler) handler;
        }
        else {
            this.declHandler = null;
        }
    }
    
    /**
     * Returns the current content handler.
     * 
     * @return the content handler
     */
    public ContentHandler getContentHandler() {
        return contentHandler;
    }
    
    /**
     * Sets the lexical handler to receive lexical events (comments, CDATA, entities).
     * 
     * <p>This allows setting a separate lexical handler even if the content handler
     * doesn't implement LexicalHandler.
     * 
     * @param handler the lexical handler
     */
    public void setLexicalHandler(LexicalHandler handler) {
        this.lexicalHandler = handler;
    }
    
    /**
     * Returns the current lexical handler.
     * 
     * @return the lexical handler, or null if none set
     */
    public LexicalHandler getLexicalHandler() {
        return lexicalHandler;
    }
    
    /**
     * Sets the DTD handler to receive DTD events.
     * 
     * @param handler the DTD handler
     */
    public void setDTDHandler(DTDHandler handler) {
        this.dtdHandler = handler;
    }
    
    /**
     * Returns the current DTD handler.
     * 
     * @return the DTD handler, or null if none set
     */
    public DTDHandler getDTDHandler() {
        return dtdHandler;
    }
    
    /**
     * Sets the system identifier for the document being parsed.
     * 
     * <p>This is used by the Locator to report the document location.
     * Since GonzalezParser is data-driven and doesn't use InputSource,
     * the application must set this explicitly if needed.
     * 
     * @param systemId the system identifier (URI) of the document, or null
     */
    public void setSystemId(String systemId) {
        locator.setSystemId(systemId);
    }
    
    /**
     * Returns the system identifier of the document being parsed.
     * 
     * @return the system identifier, or null if not set
     */
    public String getSystemId() {
        return locator.getSystemId();
    }
    
    /**
     * Sets the public identifier for the document being parsed.
     * 
     * <p>This is used by the Locator to report the document location.
     * 
     * @param publicId the public identifier of the document, or null
     */
    public void setPublicId(String publicId) {
        locator.setPublicId(publicId);
    }
    
    /**
     * Returns the public identifier of the document being parsed.
     * 
     * @return the public identifier, or null if not set
     */
    public String getPublicId() {
        return locator.getPublicId();
    }
    
    /**
     * Defines a general entity for use in content and attribute values.
     * 
     * @param name the entity name (without &amp; and ;)
     * @param value the replacement text
     */
    public void defineEntity(String name, String value) {
        generalEntities.put(name, value);
    }
    
    /**
     * Sets the entity resolver factory for creating resolvers on demand.
     * 
     * <p>As the parser encounters external entity references, it calls the
     * factory to obtain an appropriate resolver for each entity's URL. This
     * allows the application to control entity resolution policy and create
     * HTTP clients with the correct host/port settings.
     * 
     * @param factory the entity resolver factory
     */
    public void setEntityResolverFactory(AsyncEntityResolverFactory factory) {
        this.entityResolverFactory = factory;
    }

    /**
     * Returns the current entity resolver factory.
     * 
     * @return the entity resolver factory, or null if none set
     */
    public AsyncEntityResolverFactory getEntityResolverFactory() {
        return entityResolverFactory;
    }
    
    /**
     * Receives and processes XML data.
     * 
     * <p>This method may be called multiple times to feed data incrementally
     * as it becomes available. The parser processes as much data as possible
     * and buffers any incomplete tokens.
     * 
     * <p>The provided ByteBuffer's position will be advanced as data is consumed.
     * The buffer is not modified otherwise and may be reused after this method
     * returns.
     * 
     * @param data the XML data to process
     * @throws SAXParseException if the XML is malformed or a parse error occurs
     */
    public void receive(ByteBuffer data)
            throws SAXParseException {
            if (documentEnded) {
                throw new SAXParseException("Data received after document end",
                        null, null, line, column);
            }
            // Start document on first receive
            if (!documentStarted) {
                try {
                    contentHandler.setDocumentLocator(locator);
                    contentHandler.startDocument();
                    documentStarted = true;
                }
                catch (SAXException e) {
                    throw toParseException(e);
                }
            }
            // Append to parse buffer
            ensureBufferCapacity(data.remaining());
            parseBuffer.put(data);

            // Process available data
            try {
                parse();
            }
            catch (SAXException e) {
                throw toParseException(e);
            }
    }
    
    /**
     * Signals that no more data will be provided.
     * 
     * <p>This method must be called exactly once after all data has been
     * provided via {@link #receive(ByteBuffer)}. It allows the parser to
     * finalize processing and generate an exception if the document is not
     * complete.
     * 
     * @throws SAXParseException if the document is incomplete or invalid
     */
    public void close() throws SAXParseException {
        if (documentEnded) {
            return; // Already closed
        }
        if (!documentStarted) {
            throw new SAXParseException("No data received", null, null, line, column);
        }
        // Flush any remaining buffered data
        try {
            // Ensure decoder is flushed
            parseBuffer.flip();
            if (parseBuffer.hasRemaining()) {
                decodeToCharBuffer(true); // Flush decoder
            }
            parseBuffer.compact();

            // Process any remaining characters (charBuffer uses position/limit for read mode)
            if (charBuffer.hasRemaining()) {
                parseCharacters();
            }

            // Check for incomplete tokens
            if (tokenBuffer.hasRemaining()) {
                throw new SAXParseException("Incomplete token at end of document",
                        null, null, line, column);
            }
            // Verify we're in a valid end state
            if (state != ParseState.DONE) {
                throw new SAXParseException("Incomplete document", 
                        null, null, line, column);
            }
            // documentEnded should already be true from parsing
            if (!documentEnded) {
                throw new SAXParseException("Document not properly ended",
                        null, null, line, column);
            }
        }
        catch (SAXException e) {
            throw toParseException(e);
        }
    }

    /**
     * Main parsing state machine.
     */
    private void parse() throws SAXException {
        parseBuffer.flip();

        // Detect charset on first parse if not already determined
        if (!charsetDetermined && parseBuffer.hasRemaining()) {
            detectCharset();
        }

        // Decode bytes to characters
        decodeToCharBuffer(false);

        parseBuffer.compact();

        // Parse characters (charBuffer already has correct position/limit from decode)
        parseCharacters();
        charBuffer.compact();
    }
    
    /**
     * Decodes bytes from parseBuffer to charBuffer.
     * 
     * @param endOfInput true if this is the final decode operation
     */
    private void decodeToCharBuffer(boolean endOfInput) throws SAXException {
        // Save position (where we're currently reading from)
        int readPos = charBuffer.position();
        // Move to end of buffered data (where we can write more)
        charBuffer.position(charBuffer.limit());
        charBuffer.limit(charBuffer.capacity());

        CoderResult result = decoder.decode(parseBuffer, charBuffer, endOfInput);

        // Flip back for reading: limit = current position (end of data), position = where we were reading
        int writeEnd = charBuffer.position();
        charBuffer.limit(writeEnd);
        charBuffer.position(readPos);

        if (result.isError()) {
            try {
                result.throwException();
            }
            catch (Exception e) {
                throw new SAXParseException("Character encoding error: " + e.getMessage(),
                        null, null, line, column, e);
            }
        }

        if (result.isOverflow()) {
            // CharBuffer full, need to expand it
            expandCharBuffer();
            // Retry decode with larger buffer
            decodeToCharBuffer(endOfInput);
        }

        // Underflow is normal - just need more input bytes
    }
    
    /**
     * Expands the character buffer capacity.
     * 
     * <p>The character buffer is used as a sliding window for parsing, not for
     * accumulating all character data. Character data is emitted incrementally
     * via characters() calls. This buffer only needs to be large enough to hold
     * the data being actively parsed (element names, attributes, etc.).
     */
    private void expandCharBuffer() {
        int newCapacity = charBuffer.capacity() * 2;
        if (newCapacity > 1024 * 1024) { // 1MB should be more than enough for parsing window
            throw new IllegalStateException("Character buffer size limit exceeded");
        }

        CharBuffer newBuffer = CharBuffer.allocate(newCapacity);
        // Save current position
        int pos = charBuffer.position();
        charBuffer.flip();
        newBuffer.put(charBuffer);
        // Restore position for reading, set limit to end of data
        newBuffer.flip();
        newBuffer.position(pos);
        charBuffer = newBuffer;
    }
    
    /**
     * Parses characters from charBuffer.
     */
    private void parseCharacters() throws SAXException {
        // If we have buffered token data, prepend it
        if (tokenBuffer.hasRemaining()) {
            prependTokenBuffer();
        }

        while (charBuffer.hasRemaining()) {
            switch (state) {
                case INITIAL:
                    parseInitial();
                    break;
                case XML_DECLARATION:
                    parseXMLDeclaration();
                    break;
                case PROLOG:
                    parseProlog();
                    break;
                case ELEMENT_START:
                    parseElementStart();
                    break;
                case ELEMENT_CONTENT:
                    parseElementContent();
                    break;
                case ELEMENT_END:
                    parseElementEnd();
                    break;
                case TEXT:
                    parseText();
                    break;
                case COMMENT:
                    parseComment();
                    break;
                case PI:
                    parsePI();
                    break;
                case CDATA:
                    parseCDATA();
                    break;
                case DOCTYPE:
                    parseDoctype();
                    break;
                case DTD_INTERNAL_SUBSET:
                    parseDTDInternalSubset();
                    break;
                case DONE:
                    // After endDocument, only whitespace allowed
                    if (!Character.isWhitespace(charBuffer.get(charBuffer.position()))) {
                        throw new SAXParseException(
                                "Content not allowed after document end",
                                null, null, line, column);
                    }
                    consumeChar(); // Skip whitespace
                    break;
                default:
                    // More states to be implemented
                    charBuffer.position(charBuffer.limit());
                    break;
            }
        }
    }
    
    /**
     * Prepends buffered token data to the current character buffer.
     */
    private void prependTokenBuffer() {
        // Create temporary buffer with token + current chars
        int tokenLen = tokenBuffer.remaining();
        int charLen = charBuffer.remaining();

        CharBuffer temp = CharBuffer.allocate(tokenLen + charLen);
        temp.put(tokenBuffer);
        temp.put(charBuffer);
        temp.flip();

        // Replace charBuffer content with combined data
        charBuffer.clear();
        charBuffer.put(temp);
        charBuffer.flip();

        // Clear token buffer
        tokenBuffer.clear();
        tokenBuffer.flip();
    }
    
    /**
     * Buffers remaining characters as an incomplete token.
     * Call this when you need more data to complete the current parse operation.
     * 
     * <p>This should only be used for tokens that must be complete before
     * processing (element names, attribute names, attribute values). Character
     * data should be emitted incrementally via characters() calls.
     * 
     * @param maxSize the maximum allowed size for this token type
     */
    private void bufferRemainingAsToken(int maxSize) throws SAXException {
        int remaining = charBuffer.remaining();
        if (remaining == 0) {
            return;
        }

        // Check size limit
        if (tokenBuffer.position() + remaining > maxSize) {
            throw new SAXParseException("Token size limit exceeded",
                    null, null, line, column);
        }

        // Ensure tokenBuffer has capacity
        if (tokenBuffer.capacity() < tokenBuffer.position() + remaining) {
            expandTokenBuffer(tokenBuffer.position() + remaining);
        }

        // Copy remaining chars to token buffer
        tokenBuffer.compact();
        tokenBuffer.put(charBuffer);
        tokenBuffer.flip();
    }
    
    /**
     * Expands the token buffer to the specified capacity.
     */
    private void expandTokenBuffer(int newCapacity) {
        if (newCapacity > MAX_ATTRIBUTE_VALUE_LENGTH) {
            throw new IllegalStateException("Token buffer size limit exceeded");
        }

        CharBuffer newBuffer = CharBuffer.allocate(newCapacity);
        newBuffer.put(tokenBuffer);
        newBuffer.flip();
        tokenBuffer = newBuffer;
    }
    
    /**
     * Checks if we have at least n characters available.
     * If not, buffers the remaining characters as a name token and returns false.
     * Use this for element/attribute names.
     */
    private boolean requireCharsForName(int n) throws SAXException {
        if (charBuffer.remaining() < n) {
            bufferRemainingAsToken(MAX_NAME_LENGTH);
            return false;
        }
        return true;
    }
    
    /**
     * Checks if we have at least n characters available.
     * If not, buffers the remaining characters as an attribute value and returns false.
     * Use this for attribute values which can be larger than names.
     */
    private boolean requireCharsForAttributeValue(int n) throws SAXException {
        if (charBuffer.remaining() < n) {
            bufferRemainingAsToken(MAX_ATTRIBUTE_VALUE_LENGTH);
            return false;
        }
        return true;
    }
    
    /**
     * Consumes a single character from the buffer and updates position tracking.
     * 
     * <p>Handles line endings according to XML 1.0 spec section 2.11:
     * <ul>
     *   <li>LF (U+000A) - line feed</li>
     *   <li>CR (U+000D) - carriage return</li>
     *   <li>CRLF (CR followed by LF) - treated as single line ending</li>
     * </ul>
     * 
     * <p>Handles CRLF split across receive() boundaries: if CR is at the end of
     * one buffer and LF arrives in the next, we track the CR state and skip the
     * line increment for the following LF.
     */
    private char consumeChar() {
        char ch = charBuffer.get();

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
            locator.advanceLine();
        }
        else if (ch == '\r') {
            // CR - check if followed by LF (CRLF)
            if (charBuffer.hasRemaining() && peekChar() == '\n') {
                // CRLF in same buffer - consume the LF as well
                charBuffer.get();
            }
            else {
                // CR at end of buffer or followed by non-LF
                // Mark that we saw CR so if next char is LF, we don't double-count
                lastCharWasCR = true;
            }
            // Either CR alone or CRLF - both count as single line ending
            line++;
            column = 0;
            locator.advanceLine();
        }
        else {
            column++;
            locator.advanceColumn(1);
        }

        return ch;
    }
    
    /**
     * Peeks at the current character without consuming it.
     */
    private char peekChar() {
        return charBuffer.get(charBuffer.position());
    }
    
    /**
     * Peeks ahead n characters without consuming.
     */
    private char peekChar(int offset) {
        int pos = charBuffer.position() + offset;
        if (pos >= charBuffer.limit()) {
            return 0;
        }
        return charBuffer.get(pos);
    }
    
    /**
     * Detects the character encoding via BOM and/or XML declaration.
     * 
     * <p>This follows the XML 1.0 specification algorithm:
     * <ol>
     * <li>Check for BOM (UTF-8, UTF-16LE, UTF-16BE, UTF-32LE, UTF-32BE)</li>
     * <li>If no BOM, attempt to parse XML declaration for encoding attribute</li>
     * <li>Default to UTF-8 if neither BOM nor declaration found</li>
     * </ol>
     */
    private void detectCharset() throws SAXException {
        int pos = parseBuffer.position();
        int remaining = parseBuffer.remaining();

        // Need at least 4 bytes for BOM detection
        if (remaining < 4) {
            return; // Wait for more data
        }

        // Read first 4 bytes for BOM detection
        byte b0 = parseBuffer.get(pos);
        byte b1 = parseBuffer.get(pos + 1);
        byte b2 = parseBuffer.get(pos + 2);
        byte b3 = parseBuffer.get(pos + 3);

        int bomLength = 0;
        Charset detectedCharset = null;

        // Check for BOM signatures
        if (b0 == (byte) 0xEF && b1 == (byte) 0xBB && b2 == (byte) 0xBF) {
            // UTF-8 BOM
            detectedCharset = StandardCharsets.UTF_8;
            bomLength = 3;
        }
        else if (b0 == (byte) 0xFE && b1 == (byte) 0xFF) {
            // UTF-16 BE BOM
            detectedCharset = StandardCharsets.UTF_16BE;
            bomLength = 2;
        }
        else if (b0 == (byte) 0xFF && b1 == (byte) 0xFE) {
            // Check if UTF-32 LE (0xFF 0xFE 0x00 0x00) or UTF-16 LE
            if (b2 == 0x00 && b3 == 0x00) {
                // UTF-32 LE BOM
                try {
                    detectedCharset = Charset.forName("UTF-32LE");
                    bomLength = 4;
                }
                catch (Exception e) {
                    // UTF-32 not supported, fall back to UTF-16LE
                    detectedCharset = StandardCharsets.UTF_16LE;
                    bomLength = 2;
                }
            }
            else {
                // UTF-16 LE BOM
                detectedCharset = StandardCharsets.UTF_16LE;
                bomLength = 2;
            }
        }
        else if (b0 == 0x00 && b1 == 0x00 && b2 == (byte) 0xFE && b3 == (byte) 0xFF) {
            // UTF-32 BE BOM
            try {
                detectedCharset = Charset.forName("UTF-32BE");
                bomLength = 4;
            }
            catch (Exception e) {
                // UTF-32 not supported, fall back to UTF-16BE
                detectedCharset = StandardCharsets.UTF_16BE;
                bomLength = 2;
            }
        }

        if (detectedCharset != null) {
            // BOM found, skip it and set charset
            parseBuffer.position(pos + bomLength);
            setCharset(detectedCharset);
            charsetDetermined = true;
            return;
        }

        // No BOM detected - check for XML declaration
        // The XML declaration must start with "<?xml" in ASCII-compatible encoding
        if (remaining >= 5 && b0 == '<' && b1 == '?' && b2 == 'x' && b3 == 'm') {
            // XML declaration present - will be parsed to get encoding
            // For now, start with UTF-8 (ASCII-compatible) to decode and parse it
            setCharset(StandardCharsets.UTF_8);
            charsetDetermined = false; // Will be finalized by parseXMLDeclaration
            return;
        }
        else if (remaining >= 4) {
            // Check for UTF-16 without BOM by looking at byte patterns
            if (b0 == 0 && b1 == '<' && b2 == 0 && b3 == '?') {
                // UTF-16BE without BOM
                setCharset(StandardCharsets.UTF_16BE);
                charsetDetermined = true;
                return;
            }
            else if (b0 == '<' && b1 == 0 && b2 == '?' && b3 == 0) {
                // UTF-16LE without BOM
                setCharset(StandardCharsets.UTF_16LE);
                charsetDetermined = true;
                return;
            }
        }

        // No BOM, no XML declaration - default to UTF-8
        setCharset(StandardCharsets.UTF_8);
        charsetDetermined = true;
    }
    
    /**
     * Sets the character encoding and creates a new decoder.
     */
    private void setCharset(Charset newCharset) {
        if (this.charset == null || !this.charset.equals(newCharset)) {
            this.charset = newCharset;
            this.decoder = newCharset.newDecoder();
            this.locator.setEncoding(newCharset.name());
        }
    }
    
    /**
     * Re-decodes any buffered content with a new charset.
     * 
     * <p>This is called when the XML declaration specifies an encoding
     * different from what was initially detected. We need to:
     * <ol>
     * <li>Save any unprocessed bytes from parseBuffer</li>
     * <li>Discard the current charBuffer (which was decoded with old charset)</li>
     * <li>Change to the new charset</li>
     * <li>Re-decode the saved bytes with the new charset</li>
     * </ol>
     * 
     * <p>Note: The XML declaration itself uses ASCII-compatible characters,
     * so it should decode the same way in most charsets. The re-decode is
     * primarily for any content that follows the XML declaration.
     */
    private void reDecodeWithNewCharset(Charset newCharset) throws SAXException {
        // Save the current position in parseBuffer (remaining bytes to decode)
        parseBuffer.flip(); // Prepare for reading
        int remainingBytes = parseBuffer.remaining();
        
        if (remainingBytes > 0 || charBuffer.hasRemaining()) {
            // We have undecoded bytes or decoded characters that need re-processing
            
            // Save the remaining bytes from parseBuffer
            byte[] savedBytes = new byte[remainingBytes];
            parseBuffer.get(savedBytes);
            
            // Discard current charBuffer content (it was decoded with old charset)
            // Any characters after the XML declaration need to be re-decoded
            charBuffer.clear();
            charBuffer.flip(); // Empty buffer
            
            // Change to new charset
            setCharset(newCharset);
            
            // Reset parseBuffer and put saved bytes back
            parseBuffer.clear();
            parseBuffer.put(savedBytes);
            parseBuffer.flip();
            
            // Re-decode with new charset
            try {
                decodeToCharBuffer();
            } catch (Exception e) {
                throw new SAXParseException("Error re-decoding with new charset: " + newCharset,
                        null, null, line, column, e);
            }
        } else {
            // No remaining content to re-decode, just change charset
            setCharset(newCharset);
        }
    }
    
    /**
     * Returns the detected or configured character encoding.
     * 
     * @return the charset being used for parsing
     */
    public Charset getCharset() {
        return charset;
    }
    
    private boolean isWhitespace(byte b) {
        return b == ' ' || b == '\t' || b == '\r' || b == '\n';
    }
    
    /**
     * Parse initial state (before XML declaration or root element).
     */
    private void parseInitial() throws SAXException {
        // Skip BOM if present (already handled in charset detection)
        // Skip whitespace before XML declaration
        while (charBuffer.hasRemaining()) {
            char ch = peekChar();
            if (Character.isWhitespace(ch)) {
                consumeChar();
            }
            else if (ch == '<') {
                state = ParseState.PROLOG;
                return;
            }
            else {
                throw new SAXParseException(
                        "Unexpected character before document start: '" + ch + "'",
                        null, null, line, column);
            }
        }
    }
    
    /**
     * Parse XML declaration: <?xml version="1.0" encoding="UTF-8"?>
     * 
     * <p>We've already consumed "<?xml" when we get here.
     * The XML declaration is optional, but if present must be first.
     * 
     * <p>This method extracts the version and encoding (if present) to
     * update the locator, then consumes the rest of the declaration.
     */
    private void parseXMLDeclaration() throws SAXException {
        // We've already consumed "<?", now consume "xml"
        consumeChar(); // 'x'
        consumeChar(); // 'm'
        consumeChar(); // 'l'

        // Parse attributes to extract version and encoding
        String version = null;
        String encoding = null;
        String standaloneStr = null;

        // Skip whitespace
        while (charBuffer.hasRemaining()) {
            char ch = peekChar();
            if (!Character.isWhitespace(ch)) {
                break;
            }
            consumeChar();
        }

        // Look for version, encoding, standalone attributes
        while (charBuffer.hasRemaining()) {
            char ch = peekChar();

            // Check for end of declaration
            if (ch == '?') {
                consumeChar();
                if (!charBuffer.hasRemaining()) {
                    return; // Need more data
                }
                if (peekChar() == '>') {
                    consumeChar();
                    // Update locator with version if found
                    if (version != null) {
                        locator.setXMLVersion(version);
                    }
                    
                    // Handle standalone attribute
                    if (standaloneStr != null) {
                        if ("yes".equals(standaloneStr)) {
                            standalone = true;
                        } else if ("no".equals(standaloneStr)) {
                            standalone = false;
                        } else {
                            throw new SAXParseException("Invalid standalone value: " + standaloneStr,
                                    null, null, line, column);
                        }
                    }
                    
                    // Set charset if encoding was specified
                    if (encoding != null) {
                        try {
                            Charset declaredCharset = Charset.forName(encoding);
                            // Only change charset if different from what we have
                            if (!declaredCharset.equals(charset)) {
                                // Re-decode any remaining content with the new charset
                                reDecodeWithNewCharset(declaredCharset);
                            }
                        } catch (Exception e) {
                            throw new SAXParseException("Unsupported encoding: " + encoding,
                                    null, null, line, column, e);
                        }
                    }
                    
                    // Mark charset as finalized
                    charsetDetermined = true;
                    
                    state = ParseState.PROLOG;
                    return;
                }
            }

            // Skip whitespace
            if (Character.isWhitespace(ch)) {
                consumeChar();
                continue;
            }

            // Parse attribute name
            String attrName = parseName();
            if (attrName == null) {
                return; // Need more data
            }

            // Skip whitespace before '='
            while (charBuffer.hasRemaining() && Character.isWhitespace(peekChar())) {
                consumeChar();
            }

            if (!charBuffer.hasRemaining()) {
                return; // Need more data
            }

            if (peekChar() != '=') {
                throw new SAXParseException("Expected '=' after attribute name in XML declaration",
                        null, null, line, column);
            }
            consumeChar(); // '='

            // Skip whitespace after '='
            while (charBuffer.hasRemaining() && Character.isWhitespace(peekChar())) {
                consumeChar();
            }

            if (!charBuffer.hasRemaining()) {
                return; // Need more data
            }

            // Parse attribute value
            char quote = peekChar();
            if (quote != '"' && quote != '\'') {
                throw new SAXParseException("Expected quote for attribute value in XML declaration",
                        null, null, line, column);
            }
            consumeChar(); // Opening quote

            StringBuilder value = new StringBuilder();
            while (charBuffer.hasRemaining()) {
                ch = peekChar();
                if (ch == quote) {
                    consumeChar(); // Closing quote
                    break;
                }
                value.append(ch);
                consumeChar();
            }

            // Store relevant attributes
            if ("version".equals(attrName)) {
                version = value.toString();
            }
            else if ("encoding".equals(attrName)) {
                encoding = value.toString();
            }
            else if ("standalone".equals(attrName)) {
                standaloneStr = value.toString();
            }
        }

        // Need more data
    }
    
    /**
     * Parse prolog (DOCTYPE, comments, PIs before root).
     */
    private void parseProlog() throws SAXException {
        // Skip whitespace
        while (charBuffer.hasRemaining() && Character.isWhitespace(peekChar())) {
            consumeChar();
        }

        if (!requireCharsForName(2)) { // Just checking for markup start
            return; // Need more data
        }
        if (peekChar() == '<') {
            char next = peekChar(1);
            if (next == '?') {
                // Could be XML declaration or PI - need to check target
                consumeChar(); // '<'
                consumeChar(); // '?'

                // Peek at the target name
                if (!requireCharsForName(3)) {
                    return;
                }

                // Check if it's "xml" (case-insensitive)
                if ((peekChar() == 'x' || peekChar() == 'X') &&
                        charBuffer.remaining() >= 3) {
                    char c1 = peekChar();
                    char c2 = peekChar(1);
                    char c3 = peekChar(2);
                    if ((c1 == 'x' || c1 == 'X') &&
                            (c2 == 'm' || c2 == 'M') &&
                            (c3 == 'l' || c3 == 'L')) {
                        // XML declaration
                        state = ParseState.XML_DECLARATION;
                        return;
                            }
                        }

                // It's a PI
                state = ParseState.PI;
            }
            else if (next == '!') {
                // DOCTYPE or comment
                if (!requireCharsForName(4)) {
                    return;
                }
                if (peekChar(2) == '-' && peekChar(3) == '-') {
                    consumeChar(); // '<'
                    consumeChar(); // '!'
                    consumeChar(); // '-'
                    consumeChar(); // '-'
                    state = ParseState.COMMENT;
                }
                else if (peekChar(2) == 'D') {
                    // DOCTYPE declaration - consume "<!DOCTYPE"
                    consumeChar(); // '<'
                    consumeChar(); // '!'
                    consumeChar(); // 'D'
                    consumeChar(); // 'O'
                    consumeChar(); // 'C'
                    consumeChar(); // 'T'
                    consumeChar(); // 'Y'
                    consumeChar(); // 'P'
                    consumeChar(); // 'E'
                    state = ParseState.DOCTYPE;
                }
                else {
                    throw new SAXParseException("Unexpected markup in prolog",
                            null, null, line, column);
                }
            }
            else {
                // Start of root element
                state = ParseState.ELEMENT_START;
            }
        }
        else {
            throw new SAXParseException("Expected '<'", null, null, line, column);
        }
    }
    
    /**
     * Parse element start tag: &lt;name attr="value"&gt; or &lt;name/&gt;
     */
    private void parseElementStart() throws SAXException {
        // Consume '<'
        if (!requireCharsForName(1)) {
            return;
        }
        if (peekChar() != '<') {
            throw new SAXParseException("Expected '<'", null, null, line, column);
        }
        consumeChar();

        // Parse element name
        QName elementQName = parseQName();
        if (elementQName == null) {
            return; // Need more data
        }

        String elementName = elementQName.qName;

        // Parse attributes
        GonzalezAttributes attrs = new GonzalezAttributes();
        boolean emptyElement = false;
        boolean hasAttribute = false;

        while (true) {
            // Skip whitespace and check if we have at least one space after first attribute
            boolean hasWhitespace = skipWhitespace();

            if (!requireCharsForName(1)) {
                return;
            }

            char ch = peekChar();
            if (ch == '>') {
                // End of start tag
                consumeChar();
                break;
            }
            else if (ch == '/') {
                // Empty element tag
                if (!requireCharsForName(2)) {
                    return;
                }
                consumeChar(); // '/'
                if (peekChar() != '>') {
                    throw new SAXParseException("Expected '>' after '/'",
                            null, null, line, column);
                }
                consumeChar(); // '>'
                emptyElement = true;
                break;
            }
            else if (isNameStartChar(ch)) {
                // After the first attribute, we must have whitespace before the next
                if (hasAttribute && !hasWhitespace) {
                    throw new SAXParseException("Whitespace required between attributes",
                            null, null, line, column);
                }
                
                // Parse attribute
                if (!parseAttribute(attrs)) {
                    return; // Need more data
                }
                hasAttribute = true;
            }
            else {
                throw new SAXParseException("Unexpected character in start tag: '" + ch + "'",
                        null, null, line, column);
            }
        }

        // Process namespaces
        namespaceSupport.pushContext();

        // Extract namespace declarations and regular attributes
        GonzalezAttributes finalAttrs = new GonzalezAttributes();
        for (int i = 0; i < attrs.getLength(); i++) {
            String qName = attrs.getQName(i);
            String value = attrs.getValue(i);

            if (qName.equals("xmlns")) {
                // Default namespace declaration
                namespaceSupport.declarePrefix("", value);
                contentHandler.startPrefixMapping("", value);

                if (namespacePrefixes) {
                    finalAttrs.addAttribute("", "", qName, "CDATA", value, true);
                }
            }
            else if (qName.startsWith("xmlns:")) {
                // Prefixed namespace declaration
                String prefix = qName.substring(6);
                namespaceSupport.declarePrefix(prefix, value);
                contentHandler.startPrefixMapping(prefix, value);

                if (namespacePrefixes) {
                    finalAttrs.addAttribute("", "", qName, "CDATA", value, true);
                }
            }
            else {
                // Regular attribute - resolve namespace using QName info
                // Parse QName from the string (we already validated it)
                int colonIdx = qName.indexOf(':');
                String prefix = (colonIdx >= 0) ? qName.substring(0, colonIdx) : "";
                String localName = (colonIdx >= 0) ? qName.substring(colonIdx + 1) : qName;
                String uri = "";

                if (colonIdx >= 0) {
                    uri = namespaceSupport.getURI(prefix);
                    if (uri == null) {
                        throw new SAXParseException("Undeclared namespace prefix: " + prefix,
                                null, null, line, column);
                    }
                }

                // Add attribute - DTD info will be applied later if DTD exists
                finalAttrs.addAttribute(uri, localName, qName, "CDATA", value, true);
            }
        }

        // Set DTD context for lazy lookups and apply defaults if DTD parser exists
        finalAttrs.setDTDContext(elementName, dtdParser);
        if (dtdParser != null) {
            dtdParser.applyAttributeDefaults(elementName, finalAttrs, namespaceSupport);
        }

        // Resolve element name namespace
        String prefix = elementQName.getPrefix();
        String localName = elementQName.getLocalName();
        String uri = "";

        if (elementQName.hasPrefix()) {
            uri = namespaceSupport.getURI(prefix);
            if (uri == null) {
                throw new SAXParseException("Undeclared namespace prefix: " + prefix,
                        null, null, line, column);
            }
        }
        else {
            // No prefix - use default namespace
            uri = namespaceSupport.getURI("");
            if (uri == null) {
                uri = "";
            }
        }

        // Push element onto stack
        elementStack.push(elementName);

        // Call startElement with namespace information
        contentHandler.startElement(uri, localName, elementName, finalAttrs);

        if (emptyElement) {
            // Immediately close empty element
            elementStack.pop();

            // Re-use the same namespace resolution from startElement
            contentHandler.endElement(uri, localName, elementName);

            // End prefix mappings
            Enumeration<String> prefixes = namespaceSupport.getDeclaredPrefixes();
            while (prefixes.hasMoreElements()) {
                String p = prefixes.nextElement();
                contentHandler.endPrefixMapping(p);
            }

            namespaceSupport.popContext();

            // After root element, we're done
            if (elementStack.isEmpty()) {
                contentHandler.endDocument();
                documentEnded = true;
                state = ParseState.DONE;
            }
            else {
                state = ParseState.ELEMENT_CONTENT;
            }
        }
        else {
            // Now inside element, expecting content
            state = ParseState.ELEMENT_CONTENT;
        }
    }
    
    /**
     * Parse element content (text, nested elements, etc.)
     */
    private void parseElementContent() throws SAXException {
        if (!requireCharsForName(1)) {
            return;
        }

        char ch = peekChar();
        if (ch == '<') {
            // Markup
            if (!requireCharsForName(2)) {
                return;
            }

            char next = peekChar(1);
            if (next == '/') {
                // End tag
                state = ParseState.ELEMENT_END;
            }
            else if (next == '!') {
                // Comment or CDATA
                if (!requireCharsForName(4)) {
                    return;
                }
                if (peekChar(2) == '-' && peekChar(3) == '-') {
                    consumeChar(); // '<'
                    consumeChar(); // '!'
                    consumeChar(); // '-'
                    consumeChar(); // '-'
                    state = ParseState.COMMENT;
                }
                else if (peekChar(2) == '[') {
                    state = ParseState.CDATA;
                }
                else {
                    throw new SAXParseException("Unexpected markup",
                            null, null, line, column);
                }
            }
            else if (next == '?') {
                // Processing instruction
                consumeChar(); // '<'
                consumeChar(); // '?'
                state = ParseState.PI;
            }
            else {
                // Nested element
                state = ParseState.ELEMENT_START;
            }
        }
        else {
            // Text content
            parseText();
        }
    }
    
    /**
     * Parse element end tag: &lt;/name&gt;
     */
    private void parseElementEnd() throws SAXException {
        // Consume '</'
        if (!requireCharsForName(2)) {
            return;
        }
        if (peekChar() != '<' || peekChar(1) != '/') {
            throw new SAXParseException("Expected '</'", null, null, line, column);
        }
        consumeChar(); // '<'
        consumeChar(); // '/'

        // Parse element name
        QName elementQName = parseQName();
        if (elementQName == null) {
            return; // Need more data
        }

        String elementName = elementQName.qName;

        // Skip whitespace before '>'
        skipWhitespace();

        if (!requireCharsForName(1)) {
            return;
        }
        if (peekChar() != '>') {
            throw new SAXParseException("Expected '>'", null, null, line, column);
        }
        consumeChar();

        // Validate element matches stack
        if (elementStack.isEmpty()) {
            throw new SAXParseException("Unexpected end tag: " + elementName,
                    null, null, line, column);
        }

        String expectedName = elementStack.pop();
        if (!elementName.equals(expectedName)) {
            throw new SAXParseException("Mismatched end tag: expected " + expectedName +
                    " but got " + elementName,
                    null, null, line, column);
        }

        // Resolve element name namespace
        String prefix = elementQName.getPrefix();
        String localName = elementQName.getLocalName();
        String uri = "";

        if (elementQName.hasPrefix()) {
            uri = namespaceSupport.getURI(prefix);
            if (uri == null) {
                throw new SAXParseException("Undeclared namespace prefix in end tag: " + prefix,
                        null, null, line, column);
            }
        }
        else {
            uri = namespaceSupport.getURI("");
            if (uri == null) {
                uri = "";
            }
        }

        // Call endElement
        contentHandler.endElement(uri, localName, elementName);

        // End prefix mappings
        Enumeration<String> prefixes = namespaceSupport.getDeclaredPrefixes();
        while (prefixes.hasMoreElements()) {
            String p = prefixes.nextElement();
            contentHandler.endPrefixMapping(p);
        }

        namespaceSupport.popContext();

        // After root element, we're done
        if (elementStack.isEmpty()) {
            contentHandler.endDocument();
            documentEnded = true;
            state = ParseState.DONE;
        }
        else {
            state = ParseState.ELEMENT_CONTENT;
        }
    }
    
    /**
     * Parses an XML name (element name, attribute name, etc.) and returns a QName.
     * Returns null if more data is needed.
     * 
     * <p>This method is QName-aware and tracks the colon position during parsing
     * to avoid re-scanning the string later.
     * 
     * @return the parsed QName, or null if more data needed
     */
    private QName parseQName() throws SAXException {
        if (!requireCharsForName(1)) {
            return null;
        }

        // Must start with NameStartChar
        char ch = peekChar();
        if (!isNameStartChar(ch)) {
            throw new SAXParseException("Invalid name start character: '" + ch + "'",
                    null, null, line, column);
        }

        int start = charBuffer.position();
        int colonIndex = -1;
        int length = 0;

        consumeChar();
        length++;

        // Continue with NameChars
        while (charBuffer.hasRemaining()) {
            ch = peekChar();
            if (isNameChar(ch)) {
                if (ch == ':' && colonIndex == -1) {
                    // First colon marks prefix separator
                    colonIndex = length;
                }
                consumeChar();
                length++;

                if (length > MAX_NAME_LENGTH) {
                    throw new SAXParseException("Name exceeds maximum length",
                            null, null, line, column);
                }
            }
            else {
                break;
            }
        }

        // Extract name
        char[] nameChars = new char[length];
        int savedPos = charBuffer.position();
        charBuffer.position(start);
        charBuffer.get(nameChars, 0, length);
        charBuffer.position(savedPos);

        String qName = new String(nameChars);
        return new QName(qName, colonIndex);
    }
    
    /**
     * Parses an XML name (legacy method that returns String).
     * Used for PI targets and other non-element/attribute names.
     * Returns null if more data is needed.
     */
    private String parseName() throws SAXException {
        QName qname = parseQName();
        if (qname == null) {
            return null;
        }
        return qname.qName;
    }
    
    /**
     * Parses an attribute: name="value" or name='value'
     * Returns false if more data is needed.
     */
    private boolean parseAttribute(GonzalezAttributes attrs) throws SAXException {
        // Parse attribute name as QName
        QName attrQName = parseQName();
        if (attrQName == null) {
            return false;
        }

        // Skip whitespace
        skipWhitespace();

        // Expect '='
        if (!requireCharsForName(1)) {
            return false;
        }
        if (peekChar() != '=') {
            throw new SAXParseException("Expected '=' after attribute name",
                    null, null, line, column);
        }
        consumeChar();

        // Skip whitespace
        skipWhitespace();

        // Parse attribute value
        String attrValue = parseAttributeValue();
        if (attrValue == null) {
            return false;
        }

        // Check for duplicate attribute
        for (int i = 0; i < attrs.getLength(); i++) {
            if (attrs.getQName(i).equals(attrQName.qName)) {
                throw new SAXParseException("Duplicate attribute: " + attrQName.qName,
                        null, null, line, column);
            }
        }

        // Add to attributes (specified=true since it's in the document)
        // Use QName components for proper namespace handling later
        attrs.addAttribute("", attrQName.qName, attrQName.qName, "CDATA", attrValue, true);

        return true;
    }
    
    /**
     * Parses an attribute value: "value" or 'value'
     * Returns null if more data is needed.
     */
    private String parseAttributeValue() throws SAXException {
        if (!requireCharsForAttributeValue(1)) {
            return null;
        }

        char quote = peekChar();
        if (quote != '"' && quote != '\'') {
            throw new SAXParseException("Expected quote", null, null, line, column);
        }
        consumeChar();

        int start = charBuffer.position();
        StringBuilder value = new StringBuilder();

        while (true) {
            if (!charBuffer.hasRemaining()) {
                // Buffer what we have so far
                if (charBuffer.position() > start) {
                    int len = charBuffer.position() - start;
                    char[] chars = new char[len];
                    int savedPos = charBuffer.position();
                    charBuffer.position(start);
                    charBuffer.get(chars, 0, len);
                    charBuffer.position(savedPos);
                    value.append(chars);
                }

                if (!requireCharsForAttributeValue(1)) {
                    return null;
                }
                start = charBuffer.position();
            }

            char ch = peekChar();
            if (ch == quote) {
                // End of value
                if (charBuffer.position() > start) {
                    int len = charBuffer.position() - start;
                    char[] chars = new char[len];
                    int savedPos = charBuffer.position();
                    charBuffer.position(start);
                    charBuffer.get(chars, 0, len);
                    charBuffer.position(savedPos);
                    value.append(chars);
                }
                consumeChar(); // closing quote
                break;
            }
            else if (ch == '<') {
                throw new SAXParseException("'<' not allowed in attribute value",
                        null, null, line, column);
            }
            else if (ch == '&') {
                // Entity reference in attribute value
                String replacement = parseEntityReference();
                if (replacement == null) {
                    return null; // Need more data
                }

                // Append what we had before the entity
                if (charBuffer.position() > start) {
                    int len = charBuffer.position() - start;
                    char[] chars = new char[len];
                    int savedPos = charBuffer.position();
                    charBuffer.position(start);
                    charBuffer.get(chars, 0, len);
                    charBuffer.position(savedPos);
                    value.append(chars);
                }

                // Append replacement text
                value.append(replacement);
                start = charBuffer.position();
            }
            else {
                consumeChar();
            }

            if (value.length() > MAX_ATTRIBUTE_VALUE_LENGTH) {
                throw new SAXParseException("Attribute value too long",
                        null, null, line, column);
            }
        }

        return value.toString();
    }
    
    /**
     * Skips whitespace characters.
     * Returns true if any whitespace was skipped.
     */
    private boolean skipWhitespace() {
        boolean skipped = false;
        while (charBuffer.hasRemaining() && Character.isWhitespace(peekChar())) {
            consumeChar();
            skipped = true;
        }
        return skipped;
    }
    
    /**
     * Checks if a character is a NameStartChar according to XML spec.
     */
    private boolean isNameStartChar(char ch) {
        return ch == ':' || ch == '_' ||
            (ch >= 'A' && ch <= 'Z') ||
            (ch >= 'a' && ch <= 'z') ||
            (ch >= 0xC0 && ch <= 0xD6) ||
            (ch >= 0xD8 && ch <= 0xF6) ||
            (ch >= 0xF8 && ch <= 0x2FF) ||
            (ch >= 0x370 && ch <= 0x37D) ||
            (ch >= 0x37F && ch <= 0x1FFF) ||
            (ch >= 0x200C && ch <= 0x200D) ||
            (ch >= 0x2070 && ch <= 0x218F) ||
            (ch >= 0x2C00 && ch <= 0x2FEF) ||
            (ch >= 0x3001 && ch <= 0xD7FF) ||
            (ch >= 0xF900 && ch <= 0xFDCF) ||
            (ch >= 0xFDF0 && ch <= 0xFFFD);
    }
    
    /**
     * Checks if a character is a NameChar according to XML spec.
     */
    private boolean isNameChar(char ch) {
        return isNameStartChar(ch) ||
            ch == '-' || ch == '.' ||
            (ch >= '0' && ch <= '9') ||
            ch == 0xB7 ||
            (ch >= 0x0300 && ch <= 0x036F) ||
            (ch >= 0x203F && ch <= 0x2040);
    }
    
    /**
     * Parse CDATA section: &lt;![CDATA[...]]&gt;
     */
    private void parseCDATA() throws SAXException {
        // We need to check if we have the full "<![CDATA[" prefix
        // In parseElementContent, we only checked for "<![", so consume the rest
        if (!requireCharsForName(7)) { // "<![CDATA[" = 9 chars, we already have 3 ("<![")
            return;
        }

        // Verify and consume "CDATA["
        if (peekChar() != '<' || peekChar(1) != '!' || peekChar(2) != '[' ||
                peekChar(3) != 'C' || peekChar(4) != 'D' || peekChar(5) != 'A' ||
                peekChar(6) != 'T' || peekChar(7) != 'A' || peekChar(8) != '[') {
            throw new SAXParseException("Expected '<![CDATA['", null, null, line, column);
                }

        consumeChar(); // '<'
        consumeChar(); // '!'
        consumeChar(); // '['
        consumeChar(); // 'C'
        consumeChar(); // 'D'
        consumeChar(); // 'A'
        consumeChar(); // 'T'
        consumeChar(); // 'A'
        consumeChar(); // '['

        // Report CDATA start
        if (lexicalHandler != null) {
            lexicalHandler.startCDATA();
        }

        // Find "]]>"
        int start = charBuffer.position();

        while (charBuffer.hasRemaining()) {
            char ch = peekChar();

            if (ch == ']') {
                // Check if we have at least "]]>" remaining
                if (charBuffer.remaining() < 3) {
                    return; // Need more data
                }

                if (peekChar(1) == ']' && peekChar(2) == '>') {
                    // Found end of CDATA
                    int end = charBuffer.position(); // Before "]]>"
                    int cdataLength = end - start;

                    if (cdataLength > 0) {
                        char[] cdataChars = new char[cdataLength];
                        int savedPos = charBuffer.position();
                        charBuffer.position(start);
                        charBuffer.get(cdataChars, 0, cdataLength);
                        charBuffer.position(savedPos);

                        // Report as character data
                        contentHandler.characters(cdataChars, 0, cdataLength);
                    }

                    // Consume "]]>"
                    consumeChar(); // ']'
                    consumeChar(); // ']'
                    consumeChar(); // '>'

                    // Report CDATA end
                    if (lexicalHandler != null) {
                        lexicalHandler.endCDATA();
                    }

                    // Return to element content
                    state = ParseState.ELEMENT_CONTENT;
                    return;
                }
            }

            consumeChar();
        }

        // Need more data
    }

    /**
     * Parse PI.
     */
    private void parsePI() throws SAXException {
        // We've already consumed "<?", now parse target and data

        // Skip leading whitespace
        skipWhitespace();

        if (!charBuffer.hasRemaining()) {
            return; // Need more data
        }

        // Parse target name
        if (!requireCharsForName(1)) {
            return;
        }

        String target = parseName();
        if (target == null) {
            return; // Need more data
        }

        // Validate target
        if (target.equalsIgnoreCase("xml")) {
            throw new SAXParseException("'xml' is reserved and cannot be used as PI target",
                    null, null, line, column);
        }

        // Skip whitespace between target and data
        skipWhitespace();

        // Parse data (everything until "?>")
        int start = charBuffer.position();
        StringBuilder dataBuilder = new StringBuilder();
        boolean questionMark = false;

        while (charBuffer.hasRemaining()) {
            char ch = peekChar();

            if (questionMark && ch == '>') {
                consumeChar(); // '>'

                // Extract data (excluding the trailing ?)
                int end = charBuffer.position() - 2; // Before "?>"
                if (end > start) {
                    char[] dataChars = new char[end - start];
                    int savedPos = charBuffer.position();
                    charBuffer.position(start);
                    charBuffer.get(dataChars, 0, dataChars.length);
                    charBuffer.position(savedPos);
                    dataBuilder.append(dataChars);
                }

                // Report to content handler
                String data = dataBuilder.toString().trim();
                contentHandler.processingInstruction(target, data);

                // Return to appropriate state
                if (elementStack.isEmpty()) {
                    state = ParseState.PROLOG;
                }
                else {
                    state = ParseState.ELEMENT_CONTENT;
                }
                return;
            }
            else if (ch == '?') {
                questionMark = true;
                consumeChar();
            }
            else {
                questionMark = false;
                consumeChar();
            }
        }

        // Need more data
    }
    
    /**
     * Parse comment.
     */
    private void parseComment() throws SAXException {
        // We've already consumed "<!--", now find "-->"
        int start = charBuffer.position();
        int dashCount = 0;

        while (charBuffer.hasRemaining()) {
            char ch = peekChar();

            if (ch == '-') {
                dashCount++;
                consumeChar();

                // Check for "-->" 
                if (dashCount >= 2) {
                    if (!charBuffer.hasRemaining()) {
                        return; // Need more data to check for '>'
                    }

                    if (peekChar() == '>') {
                        consumeChar(); // '>'

                        // Extract comment text (excluding the trailing --)
                        int end = charBuffer.position() - 3; // Before "-->"
                        int commentLength = end - start;

                        if (commentLength > 0) {
                            char[] commentChars = new char[commentLength];
                            int savedPos = charBuffer.position();
                            charBuffer.position(start);
                            charBuffer.get(commentChars, 0, commentLength);
                            charBuffer.position(savedPos);

                            // Report to LexicalHandler if available
                            if (lexicalHandler != null) {
                                lexicalHandler.comment(commentChars, 0, commentLength);
                            }
                        }
                        else if (lexicalHandler != null) {
                            // Empty comment
                            lexicalHandler.comment(new char[0], 0, 0);
                        }

                        // Return to appropriate state
                        if (elementStack.isEmpty()) {
                            state = ParseState.PROLOG;
                        }
                        else {
                            state = ParseState.ELEMENT_CONTENT;
                        }
                        return;
                    }
                    else if (peekChar() == '-') {
                        // Three consecutive dashes: "---" is not allowed
                        throw new SAXParseException("'--' not allowed in comment content",
                                null, null, line, column);
                    }
                    else {
                        // False alarm, continue
                        dashCount = 0;
                    }
                }
            }
            else {
                dashCount = 0;
                consumeChar();
            }
        }

        // Need more data
    }
    
    /**
     * Parse DOCTYPE declaration.
     * 
     * <p>Parses: {@code <!DOCTYPE root-element SYSTEM "uri" [internal-subset]>}
     * or {@code <!DOCTYPE root-element PUBLIC "publicId" "systemId" [internal-subset]>}
     * 
     * <p>If an internal subset is present, instantiates a DTDParser to process it.
     * External DTD subset parsing is not yet implemented.
     */
    private void parseDoctype() throws SAXException {
        // We've already consumed "<!DOCTYPE"
        // Require at least one whitespace character
        if (!charBuffer.hasRemaining()) {
            return; // Need more data
        }
        
        char ch = peekChar();
        if (!Character.isWhitespace(ch)) {
            throw new SAXParseException("Whitespace required after DOCTYPE",
                    null, null, line, column);
        }
        
        // Skip all whitespace
        skipWhitespace();
        if (!charBuffer.hasRemaining()) {
            return; // Need more data
        }

        // Parse root element name
        String rootElement = parseName();
        if (rootElement == null) {
            return; // Need more data
        }

        skipWhitespace();
        if (!charBuffer.hasRemaining()) {
            return; // Need more data
        }

        String publicId = null;
        String systemId = null;

        // Check for SYSTEM or PUBLIC
        char ch = peekChar();
        if (ch == 'S' || ch == 'P') {
            // Parse "SYSTEM" or "PUBLIC"
            String keyword = parseName();
            if (keyword == null) {
                return; // Need more data
            }

            if ("PUBLIC".equals(keyword)) {
                // PUBLIC "publicId" "systemId"
                skipWhitespace();
                publicId = parseQuotedString();
                if (publicId == null) {
                    return; // Need more data
                }

                skipWhitespace();
                systemId = parseQuotedString();
                if (systemId == null) {
                    return; // Need more data
                }
            } else if ("SYSTEM".equals(keyword)) {
                // SYSTEM "systemId"
                skipWhitespace();
                systemId = parseQuotedString();
                if (systemId == null) {
                    return; // Need more data
                }
            } else {
                throw new SAXParseException("Expected SYSTEM or PUBLIC in DOCTYPE",
                        null, null, line, column);
            }

            skipWhitespace();
            if (!charBuffer.hasRemaining()) {
                return; // Need more data
            }
        }

        // Check for internal subset
        ch = peekChar();
        if (ch == '[') {
            consumeChar(); // '['

            // Instantiate DTD parser to process internal subset
            dtdParser = new org.bluezoo.gonzalez.dtd.DTDParser(
                    dtdHandler,
                    declHandler,
                    lexicalHandler,
                    entityResolverFactory,
                    charset);

            // Start LexicalHandler DTD event
            if (lexicalHandler != null) {
                lexicalHandler.startDTD(rootElement, publicId, systemId);
            }

            // Process internal subset until we find ']'
            // The DTDParser will handle the content
            state = ParseState.DTD_INTERNAL_SUBSET;
            return;
        } else if (ch == '>') {
            consumeChar(); // '>'

            // No internal subset, just external (if any)
            // Start and end DTD event
            if (lexicalHandler != null) {
                lexicalHandler.startDTD(rootElement, publicId, systemId);
                lexicalHandler.endDTD();
            }

            // TODO: Process external subset if systemId is provided
            // For now, we just move on to the document content

            state = ParseState.PROLOG;
            return;
        }

        // Need more data
    }
    
    /**
     * Process internal DTD subset.
     * 
     * <p>Feeds character data to the DTD parser until we encounter the closing ']'.
     */
    private void parseDTDInternalSubset() throws SAXException {
        // Look for ']>' to end the internal subset
        while (charBuffer.hasRemaining()) {
            char ch = peekChar();
            
            if (ch == ']') {
                // Potential end of internal subset
                consumeChar();
                
                if (!charBuffer.hasRemaining()) {
                    // Need more data to check for '>'
                    return;
                }
                
                ch = peekChar();
                if (ch == '>') {
                    consumeChar(); // '>'
                    
                    // Close DTD parser
                    if (dtdParser != null) {
                        dtdParser.close();
                    }
                    
                    // End LexicalHandler DTD event
                    if (lexicalHandler != null) {
                        lexicalHandler.endDTD();
                    }
                    
                    state = ParseState.PROLOG;
                    return;
                }
                else {
                    // False alarm - ']' was part of DTD content
                    // The DTD parser will handle it
                    // We need to feed it the ']' we consumed
                    // For now, just continue feeding data
                }
            }
            
            // Feed data to DTD parser
            // Convert current charBuffer position to bytes for DTDParser
            // This is a bit awkward - we need to re-encode characters to bytes
            // A better approach would be to have DTDParser work with CharBuffer
            // But for now, let's feed it character by character
            
            // Actually, let's take a different approach: collect all DTD content
            // into a buffer and feed it to DTDParser in one go
            // But that requires knowing where it ends first...
            
            // Better approach: feed bytes directly from parseBuffer before they're
            // decoded. But we've already decoded them here.
            
            // Simplest approach for now: The DTD parser should also work with CharBuffer
            // But we defined it to work with ByteBuffer via EntityReceiver interface
            
            // Let's re-encode the character and feed it
            char c = consumeChar();
            try {
                ByteBuffer encoded = charset.encode(String.valueOf(c));
                dtdParser.receive(encoded);
            } catch (Exception e) {
                throw new SAXParseException("Error feeding DTD parser", null, null, line, column, e);
            }
        }
        
        // Need more data
    }
    
    /**
     * Parse a quoted string (single or double quotes).
     * 
     * @return the string content (without quotes), or null if more data needed
     */
    private String parseQuotedString() throws SAXException {
        if (!charBuffer.hasRemaining()) {
            return null;
        }
        
        char quote = peekChar();
        if (quote != '\'' && quote != '"') {
            throw new SAXParseException("Expected quoted string", null, null, line, column);
        }
        
        consumeChar(); // Opening quote
        
        StringBuilder value = new StringBuilder();
        while (charBuffer.hasRemaining()) {
            char ch = peekChar();
            if (ch == quote) {
                consumeChar(); // Closing quote
                return value.toString();
            }
            consumeChar();
            value.append(ch);
        }
        
        // Need more data
        return null;
    }
    
    /**
     * Parse text content.
     * 
     * <p>Character data is emitted incrementally via characters() calls as it
     * arrives. We don't buffer it all - just emit what we have and continue.
     */
    private void parseText() throws SAXException {
        // Find the next markup character (<, &, etc.)
        int start = charBuffer.position();
        while (charBuffer.hasRemaining()) {
            char ch = peekChar();
            if (ch == '<') {
                // End of text, start of markup
                if (charBuffer.position() > start) {
                    // Emit accumulated text
                    emitCharacters(start, charBuffer.position());
                }
                state = ParseState.ELEMENT_CONTENT;
                return;
            }
            else if (ch == '&') {
                // Entity reference - emit text before it, then handle entity
                if (charBuffer.position() > start) {
                    emitCharacters(start, charBuffer.position());
                }

                // Parse and expand entity reference
                String replacement = parseEntityReference();
                if (replacement == null) {
                    return; // Need more data
                }

                // Emit replacement text
                if (replacement.length() > 0) {
                    char[] replacementChars = replacement.toCharArray();
                    contentHandler.characters(replacementChars, 0, replacementChars.length);
                }

                start = charBuffer.position();
            }
            else {
                consumeChar();
            }
        }

        // Emit any remaining text (more may come in next receive())
        if (charBuffer.position() > start) {
            emitCharacters(start, charBuffer.position());
        }
    }
    
    /**
     * Parses an entity reference: &amp;name; or &amp;#number; or &amp;#xhex;
     * Returns the replacement text, or null if more data is needed.
     * 
     * <p>This handles:
     * <ul>
     * <li>Predefined entities: &amp;amp; &amp;lt; &amp;gt; &amp;quot; &amp;apos;</li>
     * <li>Character references: &amp;#65; &amp;#x41;</li>
     * <li>General entities from DTD</li>
     * </ul>
     */
    private String parseEntityReference() throws SAXException {
        // Require at least &x;
        if (!requireCharsForName(3)) {
            return null;
        }

        if (peekChar() != '&') {
            throw new SAXParseException("Expected '&'", null, null, line, column);
        }
        consumeChar(); // '&'

        char next = peekChar();
        if (next == '#') {
            // Character reference
            return parseCharacterReference();
        }
        else if (isNameStartChar(next)) {
            // Named entity reference
            return parseNamedEntityReference();
        }
        else {
            throw new SAXParseException("Invalid entity reference",
                    null, null, line, column);
        }
    }

    /**
     * Parses a character reference: &#65; or &#x41;
     */
    private String parseCharacterReference() throws SAXException {
        consumeChar(); // '#'

        if (!requireCharsForName(1)) {
            return null;
        }

        boolean hex = false;
        if (peekChar() == 'x' || peekChar() == 'X') {
            hex = true;
            consumeChar();
        }

        // Parse digits
        int start = charBuffer.position();
        while (charBuffer.hasRemaining()) {
            char ch = peekChar();
            if (ch == ';') {
                break;
            }
            else if (hex && ((ch >= '0' && ch <= '9') ||
                        (ch >= 'a' && ch <= 'f') ||
                        (ch >= 'A' && ch <= 'F'))) {
                consumeChar();
                        }
            else if (!hex && (ch >= '0' && ch <= '9')) {
                consumeChar();
            }
            else {
                throw new SAXParseException("Invalid character in character reference",
                        null, null, line, column);
            }
        }

        int end = charBuffer.position();
        if (end == start) {
            throw new SAXParseException("Empty character reference",
                    null, null, line, column);
        }

        // Extract number
        char[] digits = new char[end - start];
        int savedPos = charBuffer.position();
        charBuffer.position(start);
        charBuffer.get(digits, 0, digits.length);
        charBuffer.position(savedPos);

        if (!requireCharsForName(1)) {
            return null;
        }

        if (peekChar() != ';') {
            throw new SAXParseException("Expected ';' after character reference",
                    null, null, line, column);
        }
        consumeChar(); // ';'

        // Parse codepoint
        try {
            int codepoint;
            if (hex) {
                codepoint = Integer.parseInt(new String(digits), 16);
            }
            else {
                codepoint = Integer.parseInt(new String(digits));
            }

            // Validate codepoint
            if (!Character.isValidCodePoint(codepoint)) {
                throw new SAXParseException("Invalid Unicode codepoint: " + codepoint,
                        null, null, line, column);
            }

            // Convert to string
            if (Character.isBmpCodePoint(codepoint)) {
                return String.valueOf((char) codepoint);
            }
            else {
                // Supplementary character
                char[] chars = Character.toChars(codepoint);
                return new String(chars);
            }
        }
        catch (NumberFormatException e) {
            throw new SAXParseException("Invalid number in character reference",
                    null, null, line, column, e);
        }
    }
    
    /**
     * Parses a named entity reference: &name;
     */
    private String parseNamedEntityReference() throws SAXException {
        // Parse entity name
        int start = charBuffer.position();

        while (charBuffer.hasRemaining()) {
            char ch = peekChar();
            if (ch == ';') {
                break;
            }
            else if (isNameChar(ch)) {
                consumeChar();
            }
            else {
                throw new SAXParseException("Invalid character in entity name",
                        null, null, line, column);
            }
        }

        int end = charBuffer.position();
        if (end == start) {
            throw new SAXParseException("Empty entity name",
                    null, null, line, column);
        }

        // Extract name
        char[] nameChars = new char[end - start];
        int savedPos = charBuffer.position();
        charBuffer.position(start);
        charBuffer.get(nameChars, 0, nameChars.length);
        charBuffer.position(savedPos);

        String name = new String(nameChars);

        if (!requireCharsForName(1)) {
            return null;
        }

        if (peekChar() != ';') {
            throw new SAXParseException("Expected ';' after entity name",
                    null, null, line, column);
        }
        consumeChar(); // ';'

        // Look up entity
        String replacement = generalEntities.get(name);
        if (replacement == null) {
            // Undefined entity - this could be:
            // 1. A well-formedness error (strict)
            // 2. An external entity that needs resolution
            // For now, treat as error
            throw new SAXParseException("Undefined entity: &" + name + ";",
                    null, null, line, column);
        }

        // Note: startEntity/endEntity are for external parsed entities only,
        // not for internal/predefined entities. We'll use those when we
        // implement external entity resolution.

        return replacement;
    }
    
    /**
     * Emits characters to the content handler.
     * 
     * <p>If the DTD indicates the current element has element-only content
     * and the text is all whitespace, calls ignorableWhitespace() instead
     * of characters().
     */
    private void emitCharacters(int start, int end) throws SAXException {
        // Convert CharBuffer range to char array
        int len = end - start;
        char[] chars = new char[len];
        int savedPos = charBuffer.position();
        charBuffer.position(start);
        charBuffer.get(chars, 0, len);
        charBuffer.position(savedPos);

        // Check if we should call ignorableWhitespace()
        // This happens when:
        // 1. We have a DTD parser (DTD was processed)
        // 2. The current element has element-only content
        // 3. The text is all whitespace
        boolean isIgnorable = false;
        if (dtdParser != null && !elementStack.isEmpty()) {
            String currentElement = elementStack.peekLast(); // Top of stack (most recent element)
            if (dtdParser.hasElementOnlyContent(currentElement)) {
                // Check if all whitespace
                isIgnorable = true;
                for (int i = 0; i < len; i++) {
                    if (!Character.isWhitespace(chars[i])) {
                        isIgnorable = false;
                        break;
                    }
                }
            }
        }

        if (isIgnorable) {
            contentHandler.ignorableWhitespace(chars, 0, len);
        } else {
            contentHandler.characters(chars, 0, len);
        }
    }
    
    // Utility methods
    
    private void ensureBufferCapacity(int additional) {
        if (parseBuffer.remaining() < additional) {
            ByteBuffer newBuffer = ByteBuffer.allocate(
                    parseBuffer.capacity() + additional + 4096);
            parseBuffer.flip();
            newBuffer.put(parseBuffer);
            parseBuffer = newBuffer;
        }
    }
    
    private SAXParseException toParseException(SAXException e) {
        if (e instanceof SAXParseException) {
            return (SAXParseException) e;
        }
        return new SAXParseException(e.getMessage(), null, null, line, column, e);
    }
    
    /**
     * Parse context for entity resolution.
     */
    private static class ParseContext {
        ParseState state;
        int line;
        int column;
        String entityName;

        ParseContext(ParseState state, int line, int column, String entityName) {
            this.state = state;
            this.line = line;
            this.column = column;
            this.entityName = entityName;
        }
    }

}

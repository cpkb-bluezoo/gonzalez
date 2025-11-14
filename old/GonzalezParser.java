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
import java.nio.BufferUnderflowException;
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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.xml.sax.Attributes;
import org.xml.sax.ContentHandler;
import org.xml.sax.DTDHandler;
import org.xml.sax.ErrorHandler;
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
public class GonzalezParser implements EntityResolvingParser {

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
    private ErrorHandler errorHandler;
    private GonzalezLocator locator;
    private AsyncEntityResolverFactory entityResolverFactory;

    // DTD parser (lazy-loaded)
    private org.bluezoo.gonzalez.dtd.DTDParser dtdParser;

    // Namespace support
    private NamespaceSupport namespaceSupport;
    private boolean namespacePrefixes = false; // Whether to report xmlns attributes

    // State
    private ParseState state;
    
    // Buffer management (delegates to XMLParserBuffer which also serves as Locator2)
    private XMLParserBuffer buffer;
    
    private StringBuilder textAccumulator;

    // Entity resolution state
    private Deque<EntityReceiverImpl> entityReceiverStack;
    private boolean inEntityResolution;  // True when buffering during entity resolution

    // Entity timeout monitoring
    private ScheduledExecutorService entityTimeoutExecutor;
    private ScheduledFuture<?> timeoutMonitorTask;
    private long entityTimeoutMillis = 10_000; // 10 seconds default
    private volatile SAXException entityTimeoutException; // Set by monitor thread

    // Element stack for validation
    private Deque<String> elementStack;

    // Entity definitions
    private Map<String, String> generalEntities;

    // Document state
    private boolean documentStarted;
    private boolean documentEnded;
    private boolean standalone; // From XML declaration

    // Limits for security
    private static final int MAX_ENTITY_DEPTH = 20;
    private static final int MAX_ENTITY_SIZE = 10 * 1024 * 1024; // 10MB
    private static final int MAX_NAME_LENGTH = 8192; // 8KB for element/attribute names
    private static final int MAX_ATTRIBUTE_VALUE_LENGTH = 1024 * 1024; // 1MB for attribute values
    private static final int MAX_ELEMENT_SIZE = 1024 * 1024; // 1MB for entire element start tag
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
        
        // Initialize buffer (which also implements Locator2)
        this.buffer = new XMLParserBuffer(charset != null ? charset : StandardCharsets.UTF_8);
        
        this.textAccumulator = new StringBuilder();
        this.entityReceiverStack = new ArrayDeque<>();
        this.inEntityResolution = false;
        this.elementStack = new ArrayDeque<>();
        this.generalEntities = new HashMap<>();

        // Initialize predefined entities
        generalEntities.put("amp", "&");
        generalEntities.put("lt", "<");
        generalEntities.put("gt", ">");
        generalEntities.put("quot", "\"");
        generalEntities.put("apos", "'");

        this.state = ParseState.INITIAL;
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
        } else {
            this.lexicalHandler = null;
        }

        if (handler instanceof DTDHandler) {
            this.dtdHandler = (DTDHandler) handler;
        } else {
            this.dtdHandler = null;
        }

        if (handler instanceof DeclHandler) {
            this.declHandler = (DeclHandler) handler;
        } else {
            this.declHandler = null;
        }

        if (handler instanceof ErrorHandler) {
            this.errorHandler = (ErrorHandler) handler;
        } else {
            this.errorHandler = null;
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
     * Sets the error handler to receive error events.
     * 
     * <p>This allows setting a separate error handler even if the content handler
     * doesn't implement ErrorHandler.
     * 
     * @param handler the error handler
     */
    public void setErrorHandler(ErrorHandler handler) {
        this.errorHandler = handler;
    }

    /**
     * Returns the current error handler.
     * 
     * @return the error handler, or null if none set
     */
    public ErrorHandler getErrorHandler() {
        return errorHandler;
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
     * Sets the entity resolution timeout in milliseconds.
     * 
     * <p>This timeout applies to the period between receiving data chunks for
     * an external entity. If no data is received for an entity within this
     * timeout period, entity resolution fails with a fatal error.
     * 
     * <p>The timeout is measured from the last receive() call, not from the
     * start of entity resolution. This ensures that only network delays (not
     * processing time) are measured.
     * 
     * <p>Default: 10000ms (10 seconds)
     * 
     * @param timeoutMillis the timeout in milliseconds, or 0 to disable timeout
     */
    public void setEntityTimeout(long timeoutMillis) {
        if (timeoutMillis < 0) {
            throw new IllegalArgumentException("Timeout must be non-negative");
        }
        this.entityTimeoutMillis = timeoutMillis;
    }

    /**
     * Returns the entity resolution timeout in milliseconds.
     * 
     * @return the timeout in milliseconds, or 0 if disabled
     */
    public long getEntityTimeout() {
        return entityTimeoutMillis;
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
     * <p><strong>Thread Safety:</strong> This method is synchronized. While it's
     * safe to call from multiple threads, it's designed for single-threaded use.
     * External entity resolution may cause callbacks from HTTP client threads,
     * which are properly synchronized.
     * 
     * @param data the XML data to process
     * @throws SAXParseException if the XML is malformed or a parse error occurs
     */
    public synchronized void receive(ByteBuffer data) throws SAXParseException {
        if (documentEnded) {
            fatalError("Data received after document end");
        }

        // Check if entity timeout occurred
        checkEntityTimeout();

        // Start document on first receive
        if (!documentStarted) {
            try {
                contentHandler.setDocumentLocator(locator);
                contentHandler.startDocument();
                documentStarted = true;
            } catch (SAXException e) {
                throw toParseException(e);
            }
        }

        // If we're currently resolving an entity, decode and append to charUnderflow
        // but don't parse yet - wait for entity to complete
        if (!entityReceiverStack.isEmpty()) {
            try {
                // Decode bytes to characters and append to underflow
                int readPos = charUnderflow.position();
                charUnderflow.position(charUnderflow.limit());
                charUnderflow.limit(charUnderflow.capacity());
                decoder.decode(data, charUnderflow, false);
                int writeEnd = charUnderflow.position();
                charUnderflow.limit(writeEnd);
                charUnderflow.position(readPos);
                // Don't call parse() - we're buffering during entity resolution
            } catch (Exception e) {
                throw toParseException(new SAXException("Decoding error", e));
            }
            return;
        }

        // Normal processing
        try {
            if (!charsetDetermined) {
                // Charset not yet determined - buffer bytes until we can parse XML declaration
                ensureBufferCapacity(data.remaining());
                byteUnderflow.put(data);
                byteUnderflow.flip();

                // Try to detect charset and parse XML declaration
                if (byteUnderflow.hasRemaining()) {
                    detectCharset();
                }

                // Decode whatever bytes we have into parseBuffer
                if (byteUnderflow != null && byteUnderflow.hasRemaining()) {
                    // Decode directly into parseBuffer
                    parseBuffer.clear();
                    CoderResult result = decoder.decode(byteUnderflow, parseBuffer, false);
                    if (result.isError()) {
                        try {
                            result.throwException();
                        } catch (Exception e) {
                            fatalError("Character encoding error: " + e.getMessage(), e);
                        }
                    }
                    parseBuffer.flip(); // Now in read mode
                } else {
                    parseBuffer.clear();
                    parseBuffer.flip(); // Empty
                }

                // Compact byteUnderflow if not released
                if (byteUnderflow != null) {
                    byteUnderflow.compact();
                }

                // Prepend underflow to parseBuffer (optimization to avoid reallocation)
                prependUnderflow();

                // Release byteUnderflow if charset is determined and all bytes are decoded
                if (charsetDetermined && byteUnderflow != null && !byteUnderflow.hasRemaining()) {
                    byteUnderflow = null;
                }
            } else {
                // Charset determined - decode incoming data into parseBuffer

                // Decode directly into parseBuffer
                parseBuffer.clear();
                CoderResult result = decoder.decode(data, parseBuffer, false);
                if (result.isError()) {
                    result.throwException();
                }
                parseBuffer.flip(); // Now in read mode

                // Prepend underflow to parseBuffer (optimization to avoid reallocation)
                prependUnderflow();
            }

            // Parse from parseBuffer (always in read mode)
            parse();

            // Save any unparsed data as underflow for next receive()
            if (parseBuffer.hasRemaining()) {
                int remaining = parseBuffer.remaining();
                if (charUnderflow.capacity() < remaining) {
                    charUnderflow = CharBuffer.allocate(remaining * 2);
                }
                charUnderflow.clear();
                charUnderflow.put(parseBuffer);
                charUnderflow.flip(); // In read mode
            }

        } catch (SAXException e) {
            throw toParseException(e);
        } catch (Exception e) {
            throw toParseException(new SAXException("Parsing error", e));
        }
    }

    /**
     * Prepends charUnderflow to parseBuffer, optimizing to avoid reallocation.
     * 
     * <p>parseBuffer is in read mode (position=0 or N, limit=end of new data).
     * charUnderflow contains incomplete token from previous receive().
     * 
     * <p>After this call:
     * - parseBuffer contains: underflow + parseBuffer data, in read mode
     * - charUnderflow is empty
     */
    private void prependUnderflow() {
        int underflowSize = charUnderflow.remaining();
        if (underflowSize == 0) {
            // No underflow to prepend
            return;
        }

        int parseBufferData = parseBuffer.remaining();
        int totalSize = underflowSize + parseBufferData;

        // Optimization: if everything fits in current parseBuffer capacity, reuse it
        if (totalSize <= parseBuffer.capacity()) {
            // Move parseBuffer data to the right to make room for underflow at the start
            // 1. Read parseBuffer data into temp array
            char[] temp = new char[parseBufferData];
            parseBuffer.get(temp);

            // 2. Reset and write: underflow first, then parseBuffer data
            parseBuffer.clear();
            parseBuffer.put(charUnderflow);
            parseBuffer.put(temp, 0, parseBufferData);
            parseBuffer.flip(); // Ready to parse from position 0
        } else {
            // Need to reallocate - not enough capacity
            CharBuffer newBuffer = CharBuffer.allocate(totalSize * 2);
            newBuffer.put(charUnderflow);
            newBuffer.put(parseBuffer);
            newBuffer.flip();
            parseBuffer = newBuffer;
        }

        // Empty the underflow since we've consumed it
        charUnderflow.clear();
        charUnderflow.limit(0); // Empty in read mode
    }

    /**
     * Signals that no more data will be provided.
     * 
     * <p>This method must be called exactly once after all data has been
     * provided via {@link #receive(ByteBuffer)}. It allows the parser to
     * finalize processing and generate an exception if the document is not
     * complete.
     * 
     * <p><strong>Thread Safety:</strong> This method is synchronized and safe
     * to call from any thread.
     * 
     * @throws SAXParseException if the document is incomplete or invalid
     */
    public synchronized void close() throws SAXParseException {
        if (documentEnded) {
            return; // Already closed
        }
        if (!documentStarted) {
            fatalError("No data received");
        }
        // Flush any remaining buffered data
        try {
            // Process any remaining characters
            if (parseBuffer.hasRemaining()) {
                parse();
            }

            // Verify we're in a valid end state
            if (state != ParseState.DONE) {
                fatalError("Incomplete document");
            }
            // documentEnded should already be true from parsing
            if (!documentEnded) {
                fatalError("Document not properly ended");
            }
        } catch (SAXException e) {
            throw toParseException(e);
        } catch (Exception e) {
            throw toParseException(new SAXException("Error during close", e));
        }
    }

    /**
     * Main parsing state machine.
     * Dispatches to appropriate parse method based on current state.
     * parseBuffer must have data available and be in read mode.
     */
    private void parse() throws SAXException {
        // Process all available characters
        while (parseBuffer.hasRemaining()) {
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
                    if (!Character.isWhitespace(parseBuffer.get(parseBuffer.position()))) {
                        fatalError("Content not allowed after document end");
                    }
                    consumeChar(); // Skip whitespace
                    break;
                default:
                    // More states to be implemented
                    parseBuffer.position(parseBuffer.limit());
                    break;
            }
        }
    }

    /**
     * Decodes bytes from byteUnderflow to charUnderflow.
     * Maintains invariant: charUnderflow is always in read mode (before and after).
     * 
     * @param endOfInput true if this is the final decode operation
     */
    private void decodeToCharBuffer(boolean endOfInput) throws SAXException {
        // charUnderflow is in read mode (invariant)
        // Save read position and calculate where data ends
        int readPos = charUnderflow.position();
        int dataEnd = charUnderflow.limit();

        // Temporarily switch to write mode to append decoded data
        charUnderflow.position(dataEnd);
        charUnderflow.limit(charUnderflow.capacity());

        CoderResult result = decoder.decode(byteUnderflow, charUnderflow, endOfInput);

        // Immediately restore read mode (restore invariant)
        int writeEnd = charUnderflow.position();
        charUnderflow.limit(writeEnd);
        charUnderflow.position(readPos);

        if (result.isError()) {
            try {
                result.throwException();
            } catch (Exception e) {
                fatalError("Character encoding error: " + e.getMessage(), e);
            }
        }

        if (result.isOverflow()) {
            // CharBuffer full, need to expand it
            expandCharBuffer(); // Preserves read mode
                                // Retry decode with larger buffer
            decodeToCharBuffer(endOfInput);
        }

        // Underflow is normal - just need more input bytes

        // Release byteUnderflow if charset is determined and all bytes are decoded
        if (charsetDetermined && byteUnderflow != null && !byteUnderflow.hasRemaining()) {
            byteUnderflow = null;  // Release memory - we're in character mode now
        }
    }

    /**
     * Expands the character buffer capacity.
     * Maintains invariant: charUnderflow is always in read mode (before and after).
     */
    private void expandCharBuffer() {
        int newCapacity = charUnderflow.capacity() * 2;
        if (newCapacity > 1024 * 1024) { // 1MB should be more than enough for parsing window
            throw new IllegalStateException("Character buffer size limit exceeded");
        }

        // charUnderflow is in read mode (invariant)
        int readPos = charUnderflow.position();
        int dataEnd = charUnderflow.limit();

        CharBuffer newBuffer = CharBuffer.allocate(newCapacity);

        // Copy existing data (from 0 to dataEnd)
        charUnderflow.position(0);
        charUnderflow.limit(dataEnd);
        newBuffer.put(charUnderflow);

        // Set new buffer to read mode with same read position
        newBuffer.flip();
        newBuffer.position(readPos);

        charUnderflow = newBuffer;
    }

    /**
     * Prepends buffered token data to the current character buffer.
     */
    private void prependTokenBuffer() {
        // Create temporary buffer with token + current chars
        int tokenLen = charUnderflow.remaining();
        int charLen = charUnderflow.remaining();

        CharBuffer temp = CharBuffer.allocate(tokenLen + charLen);
        temp.put(charUnderflow);
        temp.put(charUnderflow);
        temp.flip();

        // Replace charUnderflow content with combined data
        charUnderflow.clear();
        charUnderflow.put(temp);
        charUnderflow.flip();

        // Clear token buffer
        charUnderflow.clear();
        charUnderflow.flip();
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
        int remaining = charUnderflow.remaining();
        if (remaining == 0) {
            return;
        }

        // Check size limit
        if (charUnderflow.position() + remaining > maxSize) {
            throw new SAXParseException("Token size limit exceeded", locator);
        }

        // Ensure charUnderflow has capacity
        if (charUnderflow.capacity() < charUnderflow.position() + remaining) {
            expandTokenBuffer(charUnderflow.position() + remaining);
        }

        // Copy remaining chars to token buffer
        charUnderflow.compact();
        charUnderflow.put(charUnderflow);
        charUnderflow.flip();
    }

    /**
     * Expands the token buffer to the specified capacity.
     */
    private void expandTokenBuffer(int newCapacity) {
        if (newCapacity > MAX_ATTRIBUTE_VALUE_LENGTH) {
            throw new IllegalStateException("Token buffer size limit exceeded");
        }

        CharBuffer newBuffer = CharBuffer.allocate(newCapacity);
        newBuffer.put(charUnderflow);
        newBuffer.flip();
        charUnderflow = newBuffer;
    }

    /**
     * Checks if we have at least n characters available.
     * Use this for element/attribute names.
     */
    private boolean requireCharsForName(int n) {
        return parseBuffer.remaining() >= n;
    }

    /**
     * Checks if we have at least n characters available.
     * Use this for attribute values which can be larger than names.
     */
    private boolean requireCharsForAttributeValue(int n) {
        return parseBuffer.remaining() >= n;
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
            locator.advanceLine();
        }
        else if (ch == '\r') {
            // CR - check if followed by LF (CRLF)
            if (parseBuffer.hasRemaining() && peekChar() == '\n') {
                // CRLF in same buffer - consume the LF as well
                parseBuffer.get();
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
        return parseBuffer.get(parseBuffer.position());
    }

    /**
     * Peeks ahead n characters without consuming.
     */
    private char peekChar(int offset) {
        int pos = parseBuffer.position() + offset;
        if (pos >= parseBuffer.limit()) {
            return 0;
        }
        return parseBuffer.get(pos);
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
        int pos = byteUnderflow.position();
        int remaining = byteUnderflow.remaining();

        // Need at least 4 bytes for BOM detection
        if (remaining < 4) {
            return; // Wait for more data
        }

        // Read first 4 bytes for BOM detection
        byte b0 = byteUnderflow.get(pos);
        byte b1 = byteUnderflow.get(pos + 1);
        byte b2 = byteUnderflow.get(pos + 2);
        byte b3 = byteUnderflow.get(pos + 3);

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
                } catch (Exception e) {
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
            } catch (Exception e) {
                // UTF-32 not supported, fall back to UTF-16BE
                detectedCharset = StandardCharsets.UTF_16BE;
                bomLength = 2;
            }
        }

        if (detectedCharset != null) {
            // BOM found, skip it and set charset
            byteUnderflow.position(pos + bomLength);
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
                // UTF-16BE without BOM - has XML declaration
                setCharset(StandardCharsets.UTF_16BE);
                charsetDetermined = false; // Will parse XML declaration
                return;
            }
            else if (b0 == '<' && b1 == 0 && b2 == '?' && b3 == 0) {
                // UTF-16LE without BOM - has XML declaration
                setCharset(StandardCharsets.UTF_16LE);
                charsetDetermined = false; // Will parse XML declaration
                return;
            }
        }

        // No BOM, no XML declaration - default to UTF-8
        setCharset(StandardCharsets.UTF_8);
        charsetDetermined = true;
        state = ParseState.PROLOG;  // Skip XML declaration parsing, go straight to prolog
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
     * <li>Save any unprocessed bytes from byteUnderflow</li>
     * <li>Discard the current charUnderflow (which was decoded with old charset)</li>
     * <li>Change to the new charset</li>
     * <li>Re-decode the saved bytes with the new charset</li>
     * </ol>
     * 
     * <p>Note: The XML declaration itself uses ASCII-compatible characters,
     * so it should decode the same way in most charsets. The re-decode is
     * primarily for any content that follows the XML declaration.
     */
    private void reDecodeWithNewCharset(Charset newCharset) throws SAXException {
        // Save the current position in byteUnderflow (remaining bytes to decode)
        byteUnderflow.flip(); // Prepare for reading
        int remainingBytes = byteUnderflow.remaining();

        if (remainingBytes > 0 || charUnderflow.hasRemaining()) {
            // We have undecoded bytes or decoded characters that need re-processing

            // Save the remaining bytes from byteUnderflow
            byte[] savedBytes = new byte[remainingBytes];
            byteUnderflow.get(savedBytes);

            // Discard current charUnderflow content (it was decoded with old charset)
            // Any characters after the XML declaration need to be re-decoded
            charUnderflow.clear();
            charUnderflow.flip(); // Empty buffer

            // Change to new charset
            setCharset(newCharset);

            // Reset byteUnderflow and put saved bytes back
            byteUnderflow.clear();
            byteUnderflow.put(savedBytes);
            byteUnderflow.flip();

            // Re-decode with new charset
            try {
                decodeToCharBuffer(false);
            } catch (Exception e) {
                throw new SAXParseException("Error re-decoding with new charset: " + newCharset,
                        locator, e);
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
        while (parseBuffer.hasRemaining()) {
            char ch = peekChar();
            if (Character.isWhitespace(ch)) {
                consumeChar();
            }
            else if (ch == '<') {
                state = ParseState.PROLOG;
                return;
            }
            else {
                fatalError("Unexpected character before document start: '" + ch + "'");
            }
        }
        // Need more data
        saveToUnderflow();
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
        while (parseBuffer.hasRemaining()) {
            char ch = peekChar();
            if (!Character.isWhitespace(ch)) {
                break;
            }
            consumeChar();
        }

        // Look for version, encoding, standalone attributes
        while (parseBuffer.hasRemaining()) {
            char ch = peekChar();

            // Check for end of declaration
            if (ch == '?') {
                consumeChar();
                if (!parseBuffer.hasRemaining()) {
                    saveToUnderflow();
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
                            fatalError("Invalid standalone value: " + standaloneStr);
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
                            fatalError("Unsupported encoding: " + encoding, e);
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
                saveToUnderflow();
                return; // Need more data
            }

            // Skip whitespace before '='
            while (parseBuffer.hasRemaining() && Character.isWhitespace(peekChar())) {
                consumeChar();
            }

            if (!parseBuffer.hasRemaining()) {
                saveToUnderflow();
                return; // Need more data
            }

            if (peekChar() != '=') {
                fatalError("Expected '=' after attribute name in XML declaration");
            }
            consumeChar(); // '='

            // Skip whitespace after '='
            while (parseBuffer.hasRemaining() && Character.isWhitespace(peekChar())) {
                consumeChar();
            }

            if (!parseBuffer.hasRemaining()) {
                saveToUnderflow();
                return; // Need more data
            }

            // Parse attribute value
            char quote = peekChar();
            if (quote != '"' && quote != '\'') {
                fatalError("Expected quote for attribute value in XML declaration");
            }
            consumeChar(); // Opening quote

            StringBuilder value = new StringBuilder();
            while (parseBuffer.hasRemaining()) {
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
        saveToUnderflow();
    }

    /**
     * Parse prolog (DOCTYPE, comments, PIs before root).
     */
    private void parseProlog() throws SAXException {
        SavedPosition startPos = savePosition();
        
        // Skip whitespace
        while (parseBuffer.hasRemaining() && Character.isWhitespace(peekChar())) {
            consumeChar();
        }

        if (!requireCharsForName(2)) { // Just checking for markup start
            restorePosition(startPos);
            saveToUnderflow();
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
                    restorePosition(startPos);
                    saveToUnderflow();
                    return;
                }

                // Check if it's "xml" (case-insensitive)
                if ((peekChar() == 'x' || peekChar() == 'X') &&
                        parseBuffer.remaining() >= 3) {
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
                    restorePosition(startPos);
                    saveToUnderflow();
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
                    fatalError("Unexpected markup in prolog");
                }
            }
            else {
                // Start of root element
                state = ParseState.ELEMENT_START;
            }
        }
        else {
            fatalError("Expected '<'");
        }
    }

    /**
     * Parse element start tag: &lt;name attr="value"&gt; or &lt;name/&gt;
     */
    private void parseElementStart() throws SAXException {
        // Save position at start - we'll rewind if incomplete
        SavedPosition startPos = savePosition();

        // Variables we'll need after parsing
        QName elementQName;
        GonzalezAttributes attrs = new GonzalezAttributes();
        boolean emptyElement = false;

        try {
            // Consume '<'
            if (!requireCharsForName(1)) {
                restorePosition(startPos);
                saveToUnderflow();
                return;
            }
            if (peekChar() != '<') {
                fatalError("Expected '<'");
            }
            consumeChar();

            // Parse element name
            elementQName = parseQName();
            if (elementQName == null) {
                // Need more data - rewind
                restorePosition(startPos);
                saveToUnderflow();
                return;
            }

            // Parse attributes
            boolean hasAttribute = false;

            while (true) {
                // Skip whitespace and check if we have at least one space after first attribute
                boolean hasWhitespace = skipWhitespace();

                if (!requireCharsForName(1)) {
                    // Need more data - rewind
                    restorePosition(startPos);
                    saveToUnderflow();
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
                        // Need more data - rewind
                        restorePosition(startPos);
                        saveToUnderflow();
                        return;
                    }
                    consumeChar(); // '/'
                    if (peekChar() != '>') {
                        fatalError("Expected '>' after '/'");
                    }
                    consumeChar(); // '>'
                    emptyElement = true;
                    break;
                }
                else if (isNameStartChar(ch)) {
                    // After the first attribute, we must have whitespace before the next
                    if (hasAttribute && !hasWhitespace) {
                        fatalError("Whitespace required between attributes");
                    }
                    
                    // Parse attribute
                    if (!parseAttribute(attrs)) {
                        // Need more data - rewind
                        restorePosition(startPos);
                        saveToUnderflow();
                        return;
                    }
                    hasAttribute = true;
                }
                else {
                    fatalError("Unexpected character in start tag: '" + ch + "'");
                }
            }

            // Successfully parsed the start tag
        } catch (BufferUnderflowException e) {
            // Ran out of data - rewind
            restorePosition(startPos);
            saveToUnderflow();
            return;
        }

        // If we get here, we successfully parsed the start tag
        // elementQName, attrs, and emptyElement are now available
        String elementName = elementQName.qName;

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
                        fatalError("Undeclared namespace prefix: " + prefix);
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
                fatalError("Undeclared namespace prefix: " + prefix);
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
            saveToUnderflow();
            return;
        }

        char ch = peekChar();
        if (ch == '<') {
            // Markup
            if (!requireCharsForName(2)) {
                saveToUnderflow();
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
                    saveToUnderflow();
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
                    fatalError("Unexpected markup");
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
        SavedPosition startPos = savePosition();
        
        // Consume '</'
        if (!requireCharsForName(2)) {
            restorePosition(startPos);
            saveToUnderflow();
            return;
        }
        if (peekChar() != '<' || peekChar(1) != '/') {
            fatalError("Expected '</'");
        }
        consumeChar(); // '<'
        consumeChar(); // '/'

        // Parse element name
        QName elementQName = parseQName();
        if (elementQName == null) {
            restorePosition(startPos);
            saveToUnderflow();
            return; // Need more data
        }

        String elementName = elementQName.qName;

        // Skip whitespace before '>'
        skipWhitespace();

        if (!requireCharsForName(1)) {
            restorePosition(startPos);
            saveToUnderflow();
            return;
        }
        if (peekChar() != '>') {
            fatalError("Expected '>'");
        }
        consumeChar();

        // Validate element matches stack
        if (elementStack.isEmpty()) {
            fatalError("Unexpected end tag: " + elementName);
        }

        String expectedName = elementStack.pop();
        if (!elementName.equals(expectedName)) {
            fatalError("Mismatched end tag: expected " + expectedName +
                    " but got " + elementName);
        }

        // Resolve element name namespace
        String prefix = elementQName.getPrefix();
        String localName = elementQName.getLocalName();
        String uri = "";

        if (elementQName.hasPrefix()) {
            uri = namespaceSupport.getURI(prefix);
            if (uri == null) {
                fatalError("Undeclared namespace prefix in end tag: " + prefix);
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

        // Mark the current position so we can reset if incomplete
        parseBuffer.mark();

        // Must start with NameStartChar
        char ch = parseBuffer.get();
        if (!isNameStartChar(ch)) {
            fatalError("Invalid name start character: '" + ch + "'");
        }

        int colonIndex = -1;
        int length = 1; // We already consumed the first character
        StringBuilder nameBuilder = new StringBuilder();
        nameBuilder.append(ch);

        // Continue consuming NameChars
        try {
            while (parseBuffer.hasRemaining()) {
                ch = parseBuffer.get(parseBuffer.position()); // Peek without consuming
                if (isNameChar(ch)) {
                    if (ch == ':' && colonIndex == -1) {
                        // First colon marks prefix separator
                        colonIndex = length;
                    }
                    parseBuffer.get(); // Now consume it
                    nameBuilder.append(ch);
                    length++;

                    if (length > MAX_NAME_LENGTH) {
                        fatalError("Name exceeds maximum length");
                    }
                }
                else {
                    // Found non-name character - name is complete
                    break;
                }
            }

            // If we exited the loop because buffer is empty, the name might be incomplete
            if (!parseBuffer.hasRemaining()) {
                // Reset and return null to get more data
                parseBuffer.reset();
                return null;
            }

            // Name is complete - now actually consume it with proper tracking
            parseBuffer.reset(); // Go back to start
            for (int i = 0; i < length; i++) {
                consumeChar(); // This updates line/column correctly
            }

            String qName = nameBuilder.toString();
            return new QName(qName, colonIndex);

        } catch (BufferUnderflowException e) {
            // Ran out of data while trying to read - reset and return null
            parseBuffer.reset();
            return null;
        }
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
            fatalError("Expected '=' after attribute name");
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
                fatalError("Duplicate attribute: " + attrQName.qName);
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
            fatalError("Expected quote");
        }
        consumeChar();

        int start = parseBuffer.position();
        StringBuilder value = new StringBuilder();

        while (true) {
            if (!parseBuffer.hasRemaining()) {
                // Buffer what we have so far
                if (parseBuffer.position() > start) {
                    int len = parseBuffer.position() - start;
                    char[] chars = new char[len];
                    int savedPos = parseBuffer.position();
                    parseBuffer.position(start);
                    parseBuffer.get(chars, 0, len);
                    parseBuffer.position(savedPos);
                    value.append(chars);
                }

                if (!requireCharsForAttributeValue(1)) {
                    return null;
                }
                start = parseBuffer.position();
            }

            char ch = peekChar();
            if (ch == quote) {
                // End of value
                if (parseBuffer.position() > start) {
                    int len = parseBuffer.position() - start;
                    char[] chars = new char[len];
                    int savedPos = parseBuffer.position();
                    parseBuffer.position(start);
                    parseBuffer.get(chars, 0, len);
                    parseBuffer.position(savedPos);
                    value.append(chars);
                }
                consumeChar(); // closing quote
                break;
            }
            else if (ch == '<') {
                fatalError("'<' not allowed in attribute value");
            }
            else if (ch == '&') {
                // Entity reference in attribute value
                String replacement = parseEntityReference();
                if (replacement == null) {
                    return null; // Need more data
                }

                // Append what we had before the entity
                if (parseBuffer.position() > start) {
                    int len = parseBuffer.position() - start;
                    char[] chars = new char[len];
                    int savedPos = parseBuffer.position();
                    parseBuffer.position(start);
                    parseBuffer.get(chars, 0, len);
                    parseBuffer.position(savedPos);
                    value.append(chars);
                }

                // Append replacement text
                value.append(replacement);
                start = parseBuffer.position();
            }
            else {
                consumeChar();
            }

            if (value.length() > MAX_ATTRIBUTE_VALUE_LENGTH) {
                fatalError("Attribute value too long");
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
        while (parseBuffer.hasRemaining() && Character.isWhitespace(peekChar())) {
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
            saveToUnderflow();
            return;
        }

        // Verify and consume "CDATA["
        if (peekChar() != '<' || peekChar(1) != '!' || peekChar(2) != '[' ||
                peekChar(3) != 'C' || peekChar(4) != 'D' || peekChar(5) != 'A' ||
                peekChar(6) != 'T' || peekChar(7) != 'A' || peekChar(8) != '[') {
            throw new SAXParseException("Expected '<![CDATA['", locator);
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
        int start = parseBuffer.position();

        while (parseBuffer.hasRemaining()) {
            char ch = peekChar();

            if (ch == ']') {
                // Check if we have at least "]]>" remaining
                if (parseBuffer.remaining() < 3) {
                    saveToUnderflow();
                    return; // Need more data
                }

                if (peekChar(1) == ']' && peekChar(2) == '>') {
                    // Found end of CDATA
                    int end = parseBuffer.position(); // Before "]]>"
                    int cdataLength = end - start;

                    if (cdataLength > 0) {
                        char[] cdataChars = new char[cdataLength];
                        int savedPos = parseBuffer.position();
                        parseBuffer.position(start);
                        parseBuffer.get(cdataChars, 0, cdataLength);
                        parseBuffer.position(savedPos);

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
        saveToUnderflow();
    }

    /**
     * Parse PI.
     */
    private void parsePI() throws SAXException {
        // We've already consumed "<?", now parse target and data

        // Skip leading whitespace
        skipWhitespace();

        if (!parseBuffer.hasRemaining()) {
            saveToUnderflow();
            return; // Need more data
        }

        // Parse target name
        if (!requireCharsForName(1)) {
            saveToUnderflow();
            return;
        }

        String target = parseName();
        if (target == null) {
            saveToUnderflow();
            return; // Need more data
        }

        // Validate target
        if (target.equalsIgnoreCase("xml")) {
            throw new SAXParseException("'xml' is reserved and cannot be used as PI target",
                    locator);
        }

        // Skip whitespace between target and data
        skipWhitespace();

        // Parse data (everything until "?>")
        int start = parseBuffer.position();
        StringBuilder dataBuilder = new StringBuilder();
        boolean questionMark = false;

        while (parseBuffer.hasRemaining()) {
            char ch = peekChar();

            if (questionMark && ch == '>') {
                consumeChar(); // '>'

                // Extract data (excluding the trailing ?)
                int end = parseBuffer.position() - 2; // Before "?>"
                if (end > start) {
                    char[] dataChars = new char[end - start];
                    int savedPos = parseBuffer.position();
                    parseBuffer.position(start);
                    parseBuffer.get(dataChars, 0, dataChars.length);
                    parseBuffer.position(savedPos);
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
        saveToUnderflow();
    }

    /**
     * Parse comment.
     */
    private void parseComment() throws SAXException {
        // We've already consumed "<!--", now find "-->"
        int start = parseBuffer.position();
        int dashCount = 0;

        while (parseBuffer.hasRemaining()) {
            char ch = peekChar();

            if (ch == '-') {
                dashCount++;
                consumeChar();

                // Check for "-->" 
                if (dashCount >= 2) {
                    if (!parseBuffer.hasRemaining()) {
                        saveToUnderflow();
                        return; // Need more data to check for '>'
                    }

                    if (peekChar() == '>') {
                        consumeChar(); // '>'

                        // Extract comment text (excluding the trailing --)
                        int end = parseBuffer.position() - 3; // Before "-->"
                        int commentLength = end - start;

                        if (commentLength > 0) {
                            char[] commentChars = new char[commentLength];
                            int savedPos = parseBuffer.position();
                            parseBuffer.position(start);
                            parseBuffer.get(commentChars, 0, commentLength);
                            parseBuffer.position(savedPos);

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
                                locator);
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
        saveToUnderflow();
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
        if (!parseBuffer.hasRemaining()) {
            saveToUnderflow();
            return; // Need more data
        }

        char ch = peekChar();
        if (!Character.isWhitespace(ch)) {
            throw new SAXParseException("Whitespace required after DOCTYPE",
                    locator);
        }

        // Skip all whitespace
        skipWhitespace();
        if (!parseBuffer.hasRemaining()) {
            saveToUnderflow();
            return; // Need more data
        }

        // Parse root element name
        String rootElement = parseName();
        if (rootElement == null) {
            saveToUnderflow();
            return; // Need more data
        }

        skipWhitespace();
        if (!parseBuffer.hasRemaining()) {
            saveToUnderflow();
            return; // Need more data
        }

        String publicId = null;
        String systemId = null;

        // Check for SYSTEM or PUBLIC
        ch = peekChar();
        if (ch == 'S' || ch == 'P') {
            // Parse "SYSTEM" or "PUBLIC"
            String keyword = parseName();
            if (keyword == null) {
                saveToUnderflow();
            return; // Need more data
            }

            if ("PUBLIC".equals(keyword)) {
                // PUBLIC "publicId" "systemId"
                skipWhitespace();
                publicId = parseQuotedString();
                if (publicId == null) {
                    saveToUnderflow();
            return; // Need more data
                }

                skipWhitespace();
                systemId = parseQuotedString();
                if (systemId == null) {
                    saveToUnderflow();
            return; // Need more data
                }
            } else if ("SYSTEM".equals(keyword)) {
                // SYSTEM "systemId"
                skipWhitespace();
                systemId = parseQuotedString();
                if (systemId == null) {
                    saveToUnderflow();
            return; // Need more data
                }
            } else {
                throw new SAXParseException("Expected SYSTEM or PUBLIC in DOCTYPE",
                        locator);
            }

            skipWhitespace();
            if (!parseBuffer.hasRemaining()) {
                saveToUnderflow();
            return; // Need more data
            }
        }

        // If user hasn't set publicId/systemId, use them from DOCTYPE
        if (publicId != null && locator.getPublicId() == null) {
            locator.setPublicId(publicId);
        }
        if (systemId != null && locator.getSystemId() == null) {
            locator.setSystemId(systemId);
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
        while (parseBuffer.hasRemaining()) {
            char ch = peekChar();

            if (ch == ']') {
                // Potential end of internal subset
                consumeChar();

                if (!parseBuffer.hasRemaining()) {
                    // Need more data to check for '>'
                    saveToUnderflow();
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
                    saveToUnderflow();
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
            // Convert current charUnderflow position to bytes for DTDParser
            // This is a bit awkward - we need to re-encode characters to bytes
            // A better approach would be to have DTDParser work with CharBuffer
            // But for now, let's feed it character by character

            // Actually, let's take a different approach: collect all DTD content
            // into a buffer and feed it to DTDParser in one go
            // But that requires knowing where it ends first...

            // Better approach: feed bytes directly from byteUnderflow before they're
            // decoded. But we've already decoded them here.

            // Simplest approach for now: The DTD parser should also work with CharBuffer
            // But we defined it to work with ByteBuffer via EntityReceiver interface

            // Let's re-encode the character and feed it
            char c = consumeChar();
            try {
                ByteBuffer encoded = charset.encode(String.valueOf(c));
                dtdParser.receive(encoded);
            } catch (Exception e) {
                throw new SAXParseException("Error feeding DTD parser", locator, e);
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
        if (!parseBuffer.hasRemaining()) {
            return null;
        }

        char quote = peekChar();
        if (quote != '\'' && quote != '"') {
            throw new SAXParseException("Expected quoted string", locator);
        }

        consumeChar(); // Opening quote

        StringBuilder value = new StringBuilder();
        while (parseBuffer.hasRemaining()) {
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
        int start = parseBuffer.position();
        while (parseBuffer.hasRemaining()) {
            char ch = peekChar();
            if (ch == '<') {
                // End of text, start of markup
                if (parseBuffer.position() > start) {
                    // Emit accumulated text
                    emitCharacters(start, parseBuffer.position());
                }
                state = ParseState.ELEMENT_CONTENT;
                return;
            }
            else if (ch == '&') {
                // Entity reference - emit text before it, then handle entity
                if (parseBuffer.position() > start) {
                    emitCharacters(start, parseBuffer.position());
                }

                // Parse and expand entity reference
                String replacement = parseEntityReference();
                if (replacement == null) {
                    // Need more data - save to underflow
                    saveToUnderflow();
                    return;
                }

                // Emit replacement text
                if (replacement.length() > 0) {
                    char[] replacementChars = replacement.toCharArray();
                    contentHandler.characters(replacementChars, 0, replacementChars.length);
                }

                start = parseBuffer.position();
            }
            else {
                consumeChar();
            }
        }

        // Emit any remaining text (more may come in next receive())
        if (parseBuffer.position() > start) {
            emitCharacters(start, parseBuffer.position());
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
            throw new SAXParseException("Expected '&'", locator);
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
                    locator);
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
        int start = parseBuffer.position();
        while (parseBuffer.hasRemaining()) {
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
                        locator);
            }
        }

        int end = parseBuffer.position();
        if (end == start) {
            throw new SAXParseException("Empty character reference",
                    locator);
        }

        // Extract number
        char[] digits = new char[end - start];
        int savedPos = parseBuffer.position();
        parseBuffer.position(start);
        parseBuffer.get(digits, 0, digits.length);
        parseBuffer.position(savedPos);

        if (!requireCharsForName(1)) {
            return null;
        }

        if (peekChar() != ';') {
            throw new SAXParseException("Expected ';' after character reference",
                    locator);
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
                        locator);
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
        } catch (NumberFormatException e) {
            throw new SAXParseException("Invalid number in character reference",
                    locator, e);
        }
    }

    /**
     * Parses a named entity reference: &name;
     */
    private String parseNamedEntityReference() throws SAXException {
        // Parse entity name
        int start = parseBuffer.position();

        while (parseBuffer.hasRemaining()) {
            char ch = peekChar();
            if (ch == ';') {
                break;
            }
            else if (isNameChar(ch)) {
                consumeChar();
            }
            else {
                throw new SAXParseException("Invalid character in entity name",
                        locator);
            }
        }

        int end = parseBuffer.position();
        if (end == start) {
            throw new SAXParseException("Empty entity name",
                    locator);
        }

        // Extract name
        char[] nameChars = new char[end - start];
        int savedPos = parseBuffer.position();
        parseBuffer.position(start);
        parseBuffer.get(nameChars, 0, nameChars.length);
        parseBuffer.position(savedPos);

        String name = new String(nameChars);

        if (!requireCharsForName(1)) {
            return null;
        }

        if (peekChar() != ';') {
            throw new SAXParseException("Expected ';' after entity name",
                    locator);
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
                    locator);
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
        int savedPos = parseBuffer.position();
        parseBuffer.position(start);
        parseBuffer.get(chars, 0, len);
        parseBuffer.position(savedPos);

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
        if (byteUnderflow.remaining() < additional) {
            ByteBuffer newBuffer = ByteBuffer.allocate(
                    byteUnderflow.capacity() + additional + 4096);
            byteUnderflow.flip();
            newBuffer.put(byteUnderflow);
            byteUnderflow = newBuffer;
        }
    }

    /**
     * Callback from EntityReceiver when entity resolution completes.
     * 
     * <p>Implements {@link EntityResolvingParser#onEntityResolutionComplete()}.
     * This method is called from HTTP client threads, so must be properly synchronized.
     * The synchronization is handled by the caller (EntityReceiverImpl.close()).
     * 
     * <p>When entity resolution completes, we simply resume parsing from parseBuffer.
     * All the buffered characters are already there, waiting to be processed.
     */
    @Override
    public void onEntityResolutionComplete() throws SAXException {
        // Just resume parsing - charUnderflow already contains buffered data
        parse();
    }

    /**
     * Starts the entity timeout monitoring thread if not already started.
     */
    private synchronized void startEntityTimeoutMonitoring() {
        if (entityTimeoutMillis == 0) {
            return; // Timeout disabled
        }

        if (entityTimeoutExecutor == null) {
            entityTimeoutExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "Gonzalez-Entity-Timeout-Monitor");
                t.setDaemon(true);  // Don't prevent JVM shutdown
                return t;
            });

            // Check for timeouts every second
            timeoutMonitorTask = entityTimeoutExecutor.scheduleAtFixedRate(
                    this::checkAllEntityTimeouts,
                    1000, 1000, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Stops the entity timeout monitoring thread.
     */
    private synchronized void stopEntityTimeoutMonitoring() {
        if (timeoutMonitorTask != null) {
            timeoutMonitorTask.cancel(false);
            timeoutMonitorTask = null;
        }

        if (entityTimeoutExecutor != null) {
            entityTimeoutExecutor.shutdown();
            try {
                if (!entityTimeoutExecutor.awaitTermination(1, TimeUnit.SECONDS)) {
                    entityTimeoutExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                entityTimeoutExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
            entityTimeoutExecutor = null;
        }
    }

    /**
     * Called by monitoring thread to check all active entities for timeout.
     * Runs in background thread, so must be thread-safe.
     */
    private void checkAllEntityTimeouts() {
        try {
            if (entityReceiverStack.isEmpty()) {
                return; // No active entities
            }

            long now = System.currentTimeMillis();

            // Check each active entity receiver
            // We only check the top of stack (most recent entity)
            // because that's the one that should be receiving data
            EntityReceiverImpl topReceiver = entityReceiverStack.peek();
            if (topReceiver != null) {
                long elapsed = now - topReceiver.getLastActivityTime();
                if (elapsed > entityTimeoutMillis) {
                    // Timeout occurred!
                    String systemId = topReceiver.systemId != null ? topReceiver.systemId : "unknown";
                    SAXException timeout = new SAXParseException(
                            String.format("Entity resolution timeout after %dms for: %s",
                                elapsed, systemId),
                            locator);

                    // Store exception to be thrown on next parsing activity
                    entityTimeoutException = timeout;
                }
            }
        } catch (Exception e) {
            // Don't let exceptions in monitor thread kill the thread
            // Just log and continue
            System.err.println("Error in entity timeout monitor: " + e.getMessage());
        }
    }

    /**
     * Checks if an entity timeout has occurred and throws if so.
     * Called before any parsing activity.
     */
    private void checkEntityTimeout() throws SAXParseException {
        SAXException timeout = entityTimeoutException;
        if (timeout != null) {
            // Clear the exception
            entityTimeoutException = null;

            // Clean up entity stack
            cleanupEntityStack();

            // Throw the timeout exception
            if (timeout instanceof SAXParseException) {
                throw (SAXParseException) timeout;
            } else {
                throw new SAXParseException(timeout.getMessage(), locator, timeout);
            }
        }
    }

    /**
     * Cleans up the entity receiver stack after a timeout or error.
     */
    private void cleanupEntityStack() {
        while (!entityReceiverStack.isEmpty()) {
            EntityReceiverImpl receiver = entityReceiverStack.pop();
            receiver.markClosed(); // Mark as closed without further processing
        }
        entityDepth = 0;
        // No need to clear entityBuffer - it's been removed
        stopEntityTimeoutMonitoring();
    }

    private SAXParseException toParseException(SAXException e) {
        if (e instanceof SAXParseException) {
            return (SAXParseException) e;
        }
        return new SAXParseException(e.getMessage(), locator, e);
    }

    /**
     * Reports a fatal error to the ErrorHandler and throws a SAXParseException.
     * 
     * <p>Fatal errors are non-recoverable violations of the XML specification
     * that prevent further parsing. Examples: malformed markup, mismatched tags,
     * invalid character encoding.
     * 
     * @param message the error message
     * @throws SAXParseException always thrown after notifying the error handler
     */
    private void fatalError(String message) throws SAXParseException {
        // Diagnostic output
        System.err.println("FATAL ERROR: " + message);
        System.err.println("  State: " + state);
        System.err.println("  Position: line " + locator.getLineNumber() + ", col " + locator.getColumnNumber());

        // Show charUnderflow content around current position
        if (charUnderflow != null && charUnderflow.hasRemaining()) {
            int pos = charUnderflow.position();
            int remaining = Math.min(charUnderflow.remaining(), 50);
            StringBuilder context = new StringBuilder();
            for (int i = 0; i < remaining; i++) {
                char ch = charUnderflow.get(pos + i);
                if (ch == '\n') context.append("\\n");
                else if (ch == '\r') context.append("\\r");
                else if (ch == '\t') context.append("\\t");
                else if (ch < 32 || ch > 126) context.append("?");
                else context.append(ch);
            }
            System.err.println("  charUnderflow (" + charUnderflow.remaining() + " chars): \"" + context + "\"");
        }

        SAXParseException e = new SAXParseException(message, locator);
        if (errorHandler != null) {
            try {
                errorHandler.fatalError(e);
            } catch (SAXException handlerException) {
                // Handler threw exception, wrap it
                throw new SAXParseException("Error handler threw exception: " + handlerException.getMessage(),
                        locator, handlerException);
            }
        }
        throw e;
    }

    /**
     * Reports a fatal error with a cause to the ErrorHandler and throws a SAXParseException.
     * 
     * @param message the error message
     * @param cause the underlying exception
     * @throws SAXParseException always thrown after notifying the error handler
     */
    private void fatalError(String message, Exception cause) throws SAXParseException {
        SAXParseException e = new SAXParseException(message, locator, cause);
        if (errorHandler != null) {
            try {
                errorHandler.fatalError(e);
            } catch (SAXException handlerException) {
                // Handler threw exception, wrap it
                throw new SAXParseException("Error handler threw exception: " + handlerException.getMessage(),
                        locator, handlerException);
            }
        }
        throw e;
    }

    /**
     * Reports a recoverable error to the ErrorHandler.
     * 
     * <p>Recoverable errors are violations that can potentially be recovered from,
     * allowing parsing to continue. Examples: validity constraint violations,
     * undefined entities (if lenient mode enabled).
     * 
     * <p>If the error handler throws an exception, parsing will stop. Otherwise,
     * parsing continues.
     * 
     * @param message the error message
     * @throws SAXException if the error handler throws an exception
     */
    private void error(String message) throws SAXException {
        SAXParseException e = new SAXParseException(message, locator);
        if (errorHandler != null) {
            errorHandler.error(e);
        }
    }

    /**
     * Reports a warning to the ErrorHandler.
     * 
     * <p>Warnings are informational messages about potential issues that don't
     * prevent parsing. Examples: use of deprecated features, unusual constructs.
     * 
     * @param message the warning message
     * @throws SAXException if the error handler throws an exception
     */
    private void warning(String message) throws SAXException {
        SAXParseException e = new SAXParseException(message, locator);
        if (errorHandler != null) {
            errorHandler.warning(e);
        }
    }

    /**
     * EntityReceiver implementation that wraps a child parser for entity content.
     * 
     * <p>When an external entity is encountered, this receiver:
     * <ul>
     * <li>Creates a new parser instance to parse the entity content</li>
     * <li>Forwards entity data to the wrapped parser</li>
     * <li>Detects circular entity references by checking the stack</li>
     * <li>Notifies parent parser when entity resolution completes</li>
     * </ul>
     * 
     * <p>The parent parser switches to buffering mode while this receiver is active,
     * and resumes normal processing when close() is called.
     */
    private class EntityReceiverImpl implements EntityReceiver {
        private final String publicId;
        private final String systemId;
        private final GonzalezParser wrappedParser;
        private volatile long lastActivityTime;
        private boolean closed;

        /**
         * Creates a new entity receiver for the specified external entity.
         * 
         * @param publicId the public identifier (may be null)
         * @param systemId the system identifier (URL)
         * @throws SAXException if circular reference detected or depth limit exceeded
         */
        EntityReceiverImpl(String publicId, String systemId) throws SAXException {
            this.publicId = publicId;
            this.systemId = systemId;
            this.closed = false;
            this.lastActivityTime = System.currentTimeMillis();

            // Check depth limit
            if (entityReceiverStack.size() >= MAX_ENTITY_DEPTH) {
                throw new SAXParseException("Entity nesting depth exceeds maximum: " + MAX_ENTITY_DEPTH,
                        locator);
            }

            // Check for circular references by systemId
            for (EntityReceiverImpl active : entityReceiverStack) {
                if (systemId != null && systemId.equals(active.systemId)) {
                    throw new SAXParseException("Circular entity reference detected: " + systemId,
                            locator);
                }
            }

            // Create a new parser instance to parse this entity's content
            this.wrappedParser = new GonzalezParser(getCharset());

            // Configure the wrapped parser with same handlers as parent
            wrappedParser.setContentHandler(getContentHandler());
            wrappedParser.setLexicalHandler(getLexicalHandler());
            wrappedParser.setDTDHandler(getDTDHandler());
            wrappedParser.setErrorHandler(getErrorHandler());
            wrappedParser.setEntityResolverFactory(getEntityResolverFactory());
            wrappedParser.setEntityTimeout(getEntityTimeout()); // Propagate timeout setting

            // Set identifiers for error reporting
            if (systemId != null) {
                wrappedParser.setSystemId(systemId);
            }
            if (publicId != null) {
                wrappedParser.setPublicId(publicId);
            }

            // Push onto stack BEFORE starting resolution
            entityReceiverStack.push(this);
            entityDepth++;

            // Start timeout monitoring if this is the first entity
            if (entityReceiverStack.size() == 1) {
                startEntityTimeoutMonitoring();
            }

            // Report entity start to lexical handler
            if (lexicalHandler != null) {
                try {
                    String entityName = systemId != null ? systemId : "[external]";
                    lexicalHandler.startEntity(entityName);
                } catch (SAXException e) {
                    // Continue even if handler fails
                }
            }
        }

        /**
         * Returns the last activity time for timeout monitoring.
         */
        long getLastActivityTime() {
            return lastActivityTime;
        }

        /**
         * Marks this receiver as closed without further processing.
         * Called during cleanup after timeout or error.
         */
        void markClosed() {
            this.closed = true;
        }

        @Override
        public void receive(ByteBuffer data) throws SAXException {
            if (closed) {
                throw new IllegalStateException("EntityReceiver already closed");
            }

            // Update activity time BEFORE processing
            // This ensures timeout measures gaps between data, not processing time
            lastActivityTime = System.currentTimeMillis();

            // Check entity size limit
            totalEntitySize += data.remaining();
            if (totalEntitySize > MAX_ENTITY_SIZE) {
                throw new SAXParseException("Entity size exceeds maximum: " + MAX_ENTITY_SIZE,
                        wrappedParser.locator);
            }

            try {
                // Forward data to wrapped parser
                // The wrapped parser will process it and may encounter more entities,
                // which will create nested EntityReceiverImpl instances
                wrappedParser.receive(data);
            } finally {
                // Update activity time AFTER processing
                // This ensures we reset the timeout after processing completes
                lastActivityTime = System.currentTimeMillis();
            }
        }

        @Override
        public void close() throws SAXException {
            if (closed) {
                return; // Already closed
            }
            closed = true;

            // Close the wrapped parser
            try {
                wrappedParser.close();
            } catch (SAXParseException e) {
                // Entity parsing failed - propagate error
                throw e;
            }

            // Report entity end to lexical handler
            if (lexicalHandler != null) {
                try {
                    String entityName = systemId != null ? systemId : "[external]";
                    lexicalHandler.endEntity(entityName);
                } catch (SAXException e) {
                    // Continue even if handler fails
                }
            }

            // CRITICAL: Synchronize on parent parser before modifying its state
            // This callback is invoked from HTTP client thread, but parent parser
            // might be receiving data from application thread
            synchronized(GonzalezParser.this) {
                // Pop from stack
                EntityReceiverImpl popped = entityReceiverStack.pop();
                if (popped != this) {
                    throw new IllegalStateException("Entity receiver stack corruption");
                }
                entityDepth--;

                // Stop timeout monitoring if no more entities
                if (entityReceiverStack.isEmpty()) {
                    stopEntityTimeoutMonitoring();
                }

                // Resume parent parser: process buffered main document data
                // This calls the interface method which routes to the correct implementation
                onEntityResolutionComplete();
            }
        }
    }

}

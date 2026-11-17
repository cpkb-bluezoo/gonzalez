/*
 * XMLParser.java
 * Copyright (C) 2026 Chris Burdess
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

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.Charset;
import org.xml.sax.EntityResolver;
import org.xml.sax.SAXException;

/**
 * The Gonzalez push parser: bytes in, {@link XMLHandler} events out.
 *
 * <p>This is the native entry point to the parser. It has no knowledge of
 * the SAX {@code XMLReader} contract: there are no feature or property
 * URIs, no {@code ContentHandler}, and no {@code InputSource}. It is
 * configured through ordinary typed setters and reports to exactly one
 * {@link XMLHandler}. {@link Parser} is the SAX face of the same engine: it
 * owns one of these, translates feature and property URIs into calls on
 * it, and installs a {@link SAXAdapter} as its handler.
 *
 * <p><b>Feeding the parser.</b> The caller owns the buffer. On return from
 * {@link #receive(ByteBuffer)} the buffer's position is the first byte the
 * parser has not consumed (at most the few bytes of an incomplete
 * multi-byte character), so the caller compacts and reads more:
 * <pre>
 * XMLParser parser = new XMLParser();
 * parser.setHandler(myHandler);
 * ByteBuffer buffer = ByteBuffer.allocate(8192);
 * while (channel.read(buffer) != -1) {
 *     buffer.flip();
 *     parser.receive(buffer);
 *     buffer.compact();
 * }
 * parser.close();
 * </pre>
 * {@link #parse(InputStream)} and {@link #parse(ReadableByteChannel)} are
 * blocking conveniences that run exactly this loop.
 *
 * <p><b>Configuration.</b> The settings are read when the first bytes of a
 * document arrive, and are kept by {@link #reset()}. A setting changed
 * while a document is being parsed applies to the next document. The
 * exceptions are the handler, namespace awareness and validation, which
 * shape the pipeline itself: changing one of those while a document is
 * being parsed is an {@link IllegalStateException}.
 *
 * <p>The defaults are the secure ones: external entities are not fetched,
 * no protocol is allowed for external DTD access, and entity expansion is
 * limited.
 *
 * <p><b>Limits.</b> What the parser has to remember while it reads is
 * bounded by limits on how deeply elements nest
 * ({@link #setMaxElementDepth}), how long a name is
 * ({@link #setMaxNameLength}) and how many attributes one element has
 * ({@link #setMaxAttributesPerElement}), alongside the limit on entity
 * expansion ({@link #setEntityExpansionLimit}). A document that exceeds
 * one is rejected with a fatal error at the point where it does so.
 * Each has a default suited to untrusted input, and zero or a negative
 * value removes it. Text, attribute values, comments and processing
 * instruction data need no limit: they are passed to the handler in
 * pieces and never held. White space inside tags is not held either.
 * The one value that is held is a namespace name, which the name length
 * limit also covers.
 *
 * <p>With these limits in place a document without a DTD is parsed in
 * constant memory. Besides the stack of open element names and the
 * attribute names of the current element, the parser holds only the data
 * it was last given and, at most, one name that had not ended when that
 * data did. A DTD is outside this guarantee: its declarations are read
 * whole and kept.
 *
 * <p><b>Memory.</b> What the parser allocates is yours to size. The buffer
 * you pass to {@link #receive(ByteBuffer)} is your own, and the buffer the
 * blocking {@code parse} methods read into is set by
 * {@link #setInputBufferSize}. Inside, the buffer that bytes are decoded
 * into ({@link #setDecodeBufferSize}), the scan buffer
 * ({@link #setScanBufferSize}) and the table of interned names
 * ({@link #setNameTableSize}) are allocated once, at the sizes set, and kept
 * by {@link #reset()}, so that a parser used for many documents allocates
 * none of them again. The defaults suit most uses; with the limits above,
 * they are the whole of what a document without a DTD costs.
 *
 * <p><b>Reuse.</b> One instance parses one document at a time. Call
 * {@link #reset()} after {@link #close()} (or after a failed parse) before
 * feeding the next document.
 *
 * <p>The handler interface and the entity resolver still use
 * {@code org.xml.sax} types ({@code SAXException}, {@code Locator},
 * {@code EntityResolver}); those are the only ones this class depends on.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see XMLHandler
 * @see Parser
 */
public final class XMLParser {

    /** The default size of the buffer {@link #parse(InputStream)} and
     *  {@link #parse(ReadableByteChannel)} read into. */
    private static final int DEFAULT_INPUT_BUFFER_SIZE = 32768;

    /** The smallest input buffer. */
    private static final int MIN_INPUT_BUFFER_SIZE = 16;

    /** The largest buffer the blocking {@code parse} methods will grow to
     *  before concluding that the parser is not going to consume its
     *  input - see {@link #makeRoom(ByteBuffer)}. */
    private static final int MAX_PARSE_BUFFER_SIZE = 1 << 20;

    // ===== Configuration, read by open() =====

    private XMLHandler handler;
    private EntityResolver entityResolver;
    private boolean namespaceAware = true;
    private boolean namespacePrefixes;
    private boolean xmlnsUris;
    private boolean validating;
    private boolean externalGeneralEntities;
    private boolean externalParameterEntities;
    private boolean disallowDoctypeDecl;
    private boolean resolveDTDURIs = true;
    private boolean lineNumbers;
    private String accessExternalDTD = "";
    private int entityExpansionLimit = ScannerSettings.DEFAULT_EXPANSION_LIMIT;
    private int maxElementDepth = ScannerSettings.DEFAULT_MAX_ELEMENT_DEPTH;
    private int maxNameLength = ScannerSettings.DEFAULT_MAX_NAME_LENGTH;
    private int scanBufferSize = ScannerStorage.DEFAULT_SCAN_BUFFER;
    private int decodeBufferSize = ScannerStorage.DEFAULT_DECODE_BUFFER;
    private int nameTableSize = ScannerStorage.DEFAULT_NAME_TABLE;
    private int inputBufferSize = DEFAULT_INPUT_BUFFER_SIZE;
    private int maxAttributesPerElement = ScannerSettings.DEFAULT_MAX_ATTRIBUTES_PER_ELEMENT;
    private String publicId;
    private String systemId;

    /** Encoding hint for the next document only - see
     *  {@link #setInitialCharset(Charset)}. */
    private Charset initialCharset;

    // ===== Per-document pipeline =====

    /** Non-null from the first {@link #receive(ByteBuffer)} (or
     *  {@link #close()}) of a document until {@link #reset()}.
     *  {@link Scanner} is single-document, so a new one is built each
     *  time. */
    private Scanner scanner;

    /** Decodes bytes to characters for {@link #scanner}. Non-null exactly
     *  when {@link #scanner} is. */
    private ExternalEntityDecoder decoder;

    /** Buffer for the blocking {@code parse} methods, kept across
     *  documents. */
    private ByteBuffer parseBuffer;

    /** The memory the scanner works in, kept from one document to the next. */
    private ScannerStorage storage;

    /** How many bytes the last {@link #receive(ByteBuffer)} left
     *  unconsumed in the caller's buffer. The next call must begin with
     *  them. */
    private int unconsumed;

    /** The first of those bytes, up to the length of this array, so that
     *  the next call can be checked against them. What is left is an
     *  incomplete character, so this is ordinarily all of them. */
    private final byte[] unconsumedBytes = new byte[8];

    /**
     * Creates a parser with the default (secure) configuration and no
     * handler.
     */
    public XMLParser() {
    }

    // ===== Handler and resolver =====

    /**
     * Returns the handler that receives this parser's events.
     *
     * @return the handler, or null if none has been set
     */
    public XMLHandler getHandler() {
        return handler;
    }

    /**
     * Sets the handler that receives this parser's events. A handler is
     * required: feeding the parser without one is an error.
     *
     * <p>When namespace processing is enabled the handler receives
     * namespace declarations as {@link XMLHandler#namespace} events and
     * not as attributes.
     *
     * @param handler the handler
     * @throws IllegalStateException if a document is being parsed and the
     *         handler is not the one already set
     */
    public void setHandler(XMLHandler handler) {
        if (scanner != null && handler != this.handler) {
            throw new IllegalStateException("Cannot change the handler while a document is being parsed");
        }
        this.handler = handler;
    }

    /**
     * Returns the resolver used for external entities.
     *
     * @return the resolver, or null if the default resolution is used
     */
    public EntityResolver getEntityResolver() {
        return entityResolver;
    }

    /**
     * Sets the resolver used for the external DTD subset and external
     * parsed entities. {@code EntityResolver2} is honoured. Whether a
     * resolved entity is actually fetched is still governed by
     * {@link #setExternalGeneralEntities},
     * {@link #setExternalParameterEntities} and
     * {@link #setAccessExternalDTD}.
     *
     * @param entityResolver the resolver, or null for default resolution
     */
    public void setEntityResolver(EntityResolver entityResolver) {
        this.entityResolver = entityResolver;
    }

    // ===== Namespaces =====

    /**
     * Returns whether namespace processing is enabled.
     *
     * @return true if namespace processing is enabled (the default)
     */
    public boolean isNamespaceAware() {
        return namespaceAware;
    }

    /**
     * Enables or disables namespace processing. When enabled, namespace
     * declarations are reported as {@link XMLHandler#namespace} events and
     * the namespace well-formedness constraints on names are enforced.
     * When disabled, {@code xmlns} attributes are ordinary attributes.
     *
     * @param namespaceAware whether to process namespaces
     * @throws IllegalStateException if a document is being parsed and the
     *         value differs from the current one
     */
    public void setNamespaceAware(boolean namespaceAware) {
        if (scanner != null && namespaceAware != this.namespaceAware) {
            throw new IllegalStateException(
                    "Cannot change namespace awareness while a document is being parsed");
        }
        this.namespaceAware = namespaceAware;
    }

    /**
     * Returns whether namespace declarations are also reported as
     * attributes.
     *
     * @return true if they are; false (the default) if not
     */
    public boolean isNamespacePrefixes() {
        return namespacePrefixes;
    }

    /**
     * Sets whether, with namespace processing enabled, each namespace
     * declaration is reported as an attribute as well as a
     * {@link XMLHandler#namespace} event.
     *
     * @param namespacePrefixes whether to report declarations as attributes
     */
    public void setNamespacePrefixes(boolean namespacePrefixes) {
        this.namespacePrefixes = namespacePrefixes;
    }

    /**
     * Returns whether namespace declaration attributes are treated as
     * being in the {@code http://www.w3.org/2000/xmlns/} namespace.
     *
     * @return true if they are; false (the default) if not
     */
    public boolean isXmlnsUris() {
        return xmlnsUris;
    }

    /**
     * Sets whether namespace declaration attributes are treated as being
     * in the {@code http://www.w3.org/2000/xmlns/} namespace, or in no
     * namespace.
     *
     * @param xmlnsUris whether declarations are in the xmlns namespace
     */
    public void setXmlnsUris(boolean xmlnsUris) {
        this.xmlnsUris = xmlnsUris;
    }

    // ===== Validation and DTD access =====

    /**
     * Returns whether DTD validation is enabled.
     *
     * @return true if validating; false (the default) if not
     */
    public boolean isValidating() {
        return validating;
    }

    /**
     * Enables or disables validation against the document's DTD.
     *
     * @param validating whether to validate
     * @throws IllegalStateException if a document is being parsed and the
     *         value differs from the current one
     */
    public void setValidating(boolean validating) {
        if (scanner != null && validating != this.validating) {
            throw new IllegalStateException("Cannot change validation while a document is being parsed");
        }
        this.validating = validating;
    }

    /**
     * Returns whether external general entities are fetched and included.
     *
     * @return true if they are; false (the default) if not
     */
    public boolean isExternalGeneralEntities() {
        return externalGeneralEntities;
    }

    /**
     * Sets whether external general entities are fetched and included.
     *
     * @param externalGeneralEntities whether to include them
     */
    public void setExternalGeneralEntities(boolean externalGeneralEntities) {
        this.externalGeneralEntities = externalGeneralEntities;
    }

    /**
     * Returns whether external parameter entities and the external DTD
     * subset are fetched and included.
     *
     * @return true if they are; false (the default) if not
     */
    public boolean isExternalParameterEntities() {
        return externalParameterEntities;
    }

    /**
     * Sets whether external parameter entities and the external DTD subset
     * are fetched and included.
     *
     * @param externalParameterEntities whether to include them
     */
    public void setExternalParameterEntities(boolean externalParameterEntities) {
        this.externalParameterEntities = externalParameterEntities;
    }

    /**
     * Returns whether a document containing a DOCTYPE declaration is
     * rejected.
     *
     * @return true if it is; false (the default) if not
     */
    public boolean isDisallowDoctypeDecl() {
        return disallowDoctypeDecl;
    }

    /**
     * Sets whether any document containing a DOCTYPE declaration is
     * rejected outright, before its declarations are processed. This is
     * the strongest defence against XXE and entity expansion attacks for
     * applications that do not need DTDs.
     *
     * @param disallowDoctypeDecl whether to reject DOCTYPE declarations
     */
    public void setDisallowDoctypeDecl(boolean disallowDoctypeDecl) {
        this.disallowDoctypeDecl = disallowDoctypeDecl;
    }

    /**
     * Returns whether system identifiers in DTD events are made absolute.
     *
     * @return true (the default) if they are
     */
    public boolean isResolveDTDURIs() {
        return resolveDTDURIs;
    }

    /**
     * Sets whether the system identifiers reported in DTD events are
     * resolved against the base URI before they are reported.
     *
     * @param resolveDTDURIs whether to resolve them
     */
    public void setResolveDTDURIs(boolean resolveDTDURIs) {
        this.resolveDTDURIs = resolveDTDURIs;
    }

    /**
     * Returns the protocols allowed for external DTD and entity access.
     *
     * @return the allow-list; the empty string (the default) means none
     */
    public String getAccessExternalDTD() {
        return accessExternalDTD;
    }

    /**
     * Sets the protocols allowed for external DTD and entity access, as a
     * comma-separated list ({@code "file,https"}). The empty string allows
     * none and {@code "all"} allows every protocol.
     *
     * @param accessExternalDTD the allow-list; null is the empty string
     */
    public void setAccessExternalDTD(String accessExternalDTD) {
        this.accessExternalDTD = (accessExternalDTD != null) ? accessExternalDTD : "";
    }

    /**
     * Returns the maximum number of entity expansions in one document.
     *
     * @return the limit; 64,000 by default
     */
    public int getEntityExpansionLimit() {
        return entityExpansionLimit;
    }

    /**
     * Sets the maximum number of entity expansions permitted in one
     * document. Zero means unlimited.
     *
     * @param entityExpansionLimit the limit, or zero for none
     */
    public void setEntityExpansionLimit(int entityExpansionLimit) {
        this.entityExpansionLimit = entityExpansionLimit;
    }

    // ===== Limits =====

    /**
     * Returns the maximum depth to which elements may nest.
     *
     * @return the limit; 1000 by default, zero or less for none
     */
    public int getMaxElementDepth() {
        return maxElementDepth;
    }

    /**
     * Sets the maximum number of elements that may be open at once: the
     * root element is at depth 1. The parser has to remember the name of
     * every open element to check that end tags match, and this bounds
     * that. Zero or a negative value means unlimited.
     *
     * @param maxElementDepth the limit, or zero for none
     */
    public void setMaxElementDepth(int maxElementDepth) {
        this.maxElementDepth = maxElementDepth;
    }

    /**
     * Returns the maximum length of a name.
     *
     * @return the limit in characters; 4096 by default, zero or less for
     *         none
     */
    public int getMaxNameLength() {
        return maxNameLength;
    }

    /**
     * Sets the maximum length, in characters, of an element name, an
     * attribute name, a processing instruction target or the name in an
     * entity reference, and of the digits of a character reference. A
     * name is read whole before it is reported, so this bounds what the
     * parser holds while a name is still arriving. Zero or a negative
     * value means unlimited.
     *
     * <p>With namespace processing enabled the same limit applies to the
     * namespace name in a namespace declaration. Unlike any other
     * attribute value it is reported whole, as one
     * {@link XMLHandler#namespace} event, and so has to be held.
     *
     * <p>Names inside a DTD are not covered.
     *
     * @param maxNameLength the limit in characters, or zero for none
     */
    public void setMaxNameLength(int maxNameLength) {
        this.maxNameLength = maxNameLength;
    }

    /**
     * Returns the maximum number of attributes of one element.
     *
     * @return the limit; 10,000 by default, zero or less for none
     */
    public int getMaxAttributesPerElement() {
        return maxAttributesPerElement;
    }

    /**
     * Sets the maximum number of attributes that may be specified on one
     * element, namespace declarations included. The parser remembers the
     * attribute names of the element it is reading to check that none is
     * repeated, and this bounds that. Attributes supplied as defaults by
     * a DTD do not count. Zero or a negative value means unlimited.
     *
     * @param maxAttributesPerElement the limit, or zero for none
     */
    public void setMaxAttributesPerElement(int maxAttributesPerElement) {
        this.maxAttributesPerElement = maxAttributesPerElement;
    }

    // ===== Memory =====

    private static int atLeast(String what, int value, int minimum) {
        if (value < minimum) {
            throw new IllegalArgumentException(what + " must be at least " + minimum + ": " + value);
        }
        return value;
    }

    /**
     * Returns the size of the scan buffer.
     *
     * @return the size in characters; 8192 by default
     */
    public int getScanBufferSize() {
        return scanBufferSize;
    }

    /**
     * Sets the size of the scan buffer: the characters the parser holds
     * while it reads, which is the data it was last given and any one name
     * or declaration still arriving when that data ended. It is allocated
     * once and kept from one document to the next. A document that needs
     * more (a name as long as the name length limit allows, or a large
     * DTD declaration) makes it grow for that document, and it is put back
     * to this size when the document is done. The buffer you pass to
     * {@link #receive(ByteBuffer)} is yours, and its size is your choice.
     *
     * <p>A size smaller than the usual makes no difference to what is
     * reported, only to how often the buffer has to grow.
     *
     * @param scanBufferSize the size in characters, at least 256
     * @throws IllegalArgumentException if it is smaller than that
     */
    public void setScanBufferSize(int scanBufferSize) {
        this.scanBufferSize = atLeast("The scan buffer size", scanBufferSize,
                ScannerStorage.MIN_SCAN_BUFFER);
    }

    /**
     * Returns the size of the buffer the decoder fills.
     *
     * @return the size in characters; 16,384 (32 KB) by default
     */
    public int getDecodeBufferSize() {
        return decodeBufferSize;
    }

    /**
     * Sets the size of the buffer that bytes are decoded into before they
     * are scanned. Input is decoded and scanned in pieces of this size. A
     * piece that fits in the processor's first-level cache is scanned from
     * there, which is why the default is not larger: 16,384 characters was
     * 3 to 5% faster than 32,768 on text that is mostly ASCII, and no slower
     * on any other. It is not smaller, because a construct that the scanner
     * has to have all of before it can go on, such as the internal subset of
     * a DOCTYPE, is tried again for each piece that does not end it, and at
     * 8,192 characters a document with a subset of some kilobytes was 9%
     * slower. A smaller buffer reports exactly the same, and is the one to
     * choose where memory is dear and many parsers are held at once. It is
     * allocated once and kept from one document to the next.
     *
     * @param decodeBufferSize the size in characters, at least 16
     * @throws IllegalArgumentException if it is smaller than that
     */
    public void setDecodeBufferSize(int decodeBufferSize) {
        this.decodeBufferSize = atLeast("The decode buffer size", decodeBufferSize,
                ScannerStorage.MIN_DECODE_BUFFER);
    }

    /**
     * Returns the size of the table of interned names.
     *
     * @return the number of entries; 512 by default
     */
    public int getNameTableSize() {
        return nameTableSize;
    }

    /**
     * Sets the size of the table in which element names, attribute names
     * and processing instruction targets are interned, so that a name used
     * again is the same {@code String} and costs no allocation. The size is
     * fixed, whatever the document: a document with more distinct names
     * than the table has room for is parsed correctly, the names it has no
     * room for being made afresh each time. It is rounded up to a power of
     * two, and a table of 512 suits documents with up to a few hundred
     * distinct names.
     *
     * @param nameTableSize the number of entries, at least 16
     * @throws IllegalArgumentException if it is smaller than that
     */
    public void setNameTableSize(int nameTableSize) {
        this.nameTableSize = atLeast("The name table size", nameTableSize,
                ScannerStorage.MIN_NAME_TABLE);
    }

    /**
     * Returns the size of the buffer that the blocking {@code parse}
     * methods read into.
     *
     * @return the size in bytes; 32,768 by default
     */
    public int getInputBufferSize() {
        return inputBufferSize;
    }

    /**
     * Sets the size of the buffer that {@link #parse(InputStream)} and
     * {@link #parse(ReadableByteChannel)} read into. It has no part in
     * {@link #receive(ByteBuffer)}, where the buffer is yours. It is kept
     * from one document to the next.
     *
     * @param inputBufferSize the size in bytes, at least 16
     * @throws IllegalArgumentException if it is smaller than that
     */
    public void setInputBufferSize(int inputBufferSize) {
        this.inputBufferSize = atLeast("The input buffer size", inputBufferSize,
                MIN_INPUT_BUFFER_SIZE);
    }

    /**
     * Returns whether the locator reports line and column numbers.
     *
     * @return true if it does; false (the default) if it reports -1
     */
    public boolean isLineNumbers() {
        return lineNumbers;
    }

    /**
     * Sets whether the {@link org.xml.sax.Locator} given to the handler
     * reports line and column numbers. They are off by default, because
     * working them out takes a pass over the characters of the document,
     * and the locator then reports -1 for both.
     *
     * <p>The position is that of the scan point when the event is
     * reported, so that of the end of a start tag and not its beginning.
     * Lines are counted from 1 after normalizing line endings, so that a
     * carriage return and line feed is one line break. The text of an
     * external entity has lines of its own; that of an internal entity is
     * located at its reference.
     *
     * @param lineNumbers whether to work out line and column numbers
     */
    public void setLineNumbers(boolean lineNumbers) {
        this.lineNumbers = lineNumbers;
    }

    // ===== Document identity =====

    /**
     * Returns the public identifier of the document.
     *
     * @return the public identifier, or null if not set
     */
    public String getPublicId() {
        return publicId;
    }

    /**
     * Sets the public identifier of the document, reported through the
     * locator.
     *
     * @param publicId the public identifier, or null
     */
    public void setPublicId(String publicId) {
        this.publicId = publicId;
    }

    /**
     * Returns the system identifier of the document.
     *
     * @return the system identifier, or null if not set
     */
    public String getSystemId() {
        return systemId;
    }

    /**
     * Sets the system identifier of the document: the base against which
     * relative system identifiers are resolved, also reported through the
     * locator.
     *
     * @param systemId the system identifier, or null
     */
    public void setSystemId(String systemId) {
        this.systemId = systemId;
    }

    /**
     * Supplies an encoding known from outside the document (a transport
     * header, for example) for the next document only. A byte order mark
     * or an encoding declaration in the document overrides it.
     *
     * @param charset the encoding hint, or null for none
     * @throws IllegalStateException if bytes of the current document have
     *         already been decoded
     */
    public void setInitialCharset(Charset charset) {
        if (decoder != null) {
            decoder.setInitialCharset(charset);
        } else {
            initialCharset = charset;
        }
    }

    // ===== Document state =====

    /**
     * Returns whether a document is being parsed: true from the first
     * {@link #receive(ByteBuffer)} or {@link #close()} until
     * {@link #reset()}.
     *
     * @return true if a document has been started and not reset
     */
    public boolean isParsing() {
        return scanner != null;
    }

    /**
     * Returns the standalone status the current document declared.
     *
     * @return true if the XML declaration said {@code standalone="yes"};
     *         false otherwise, or if no document is being parsed
     */
    public boolean isStandalone() {
        return scanner != null && scanner.isStandalone();
    }

    /**
     * Returns whether the current document has a DOCTYPE declaration.
     *
     * @return true once a DOCTYPE declaration has been fully read; false
     *         before that, or if no document is being parsed
     */
    public boolean hasDoctype() {
        return scanner != null && scanner.hasDoctype();
    }

    /**
     * Returns the capacity, in characters, of the buffer the scanner
     * holds unscanned input in. Exists for tests of the memory envelope.
     *
     * @return the capacity, or zero if no document is being parsed
     */
    int decodeBufferCapacity() {
        return (storage != null) ? storage.decodeBufferCapacity() : 0;
    }

    int nameTableCapacity() {
        return (storage != null) ? storage.names.capacity() : 0;
    }

    int scanBufferCapacity() {
        return (scanner != null) ? scanner.getBufferCapacity() : 0;
    }

    // ===== Parsing =====

    /**
     * Receives the next bytes of the document.
     *
     * <p>The buffer must be ready for reading. On return its position is
     * the first byte that was not consumed; the caller must present those
     * bytes again, followed by more data, in the next call (normally by
     * calling {@code compact()} and reading into the buffer). Events are
     * delivered to the handler during this call.
     *
     * <p>The parser consumes all it can: what it leaves is at most an
     * incomplete character or byte order mark, a few bytes, at any point
     * in the document including its XML declaration. A buffer of any
     * practical size therefore always has room for more. Checking that
     * it does is nonetheless the caller's job, since only the caller
     * knows its buffer: a caller that finds its buffer still full after
     * this method returns must give the parser a larger one, or give up,
     * and not call again with the same bytes.
     *
     * <p>A caller that clears or replaces its buffer between calls,
     * instead of compacting it, loses the bytes that were left. The
     * parser checks for this: if a call does not begin with the bytes
     * the previous one left unconsumed, it throws
     * {@link IllegalStateException}. That is a fault in the caller and
     * not in the document, so it is not reported as a parsing error.
     *
     * @param data the bytes to parse
     * @throws SAXException if the document is not well-formed, or the
     *         handler throws
     * @throws IllegalStateException if no handler has been set, the
     *         document has already been closed, or the bytes left
     *         unconsumed by the previous call are not at the start of
     *         this one
     */
    public void receive(ByteBuffer data) throws SAXException {
        if (decoder == null) {
            open();
        }
        if (unconsumed != 0) {
            checkPresentedAgain(data);
        }
        decoder.receive(data);
        int left = data.remaining();
        unconsumed = left;
        if (left != 0) {
            int position = data.position();
            int kept = Math.min(left, unconsumedBytes.length);
            for (int i = 0; i < kept; i++) {
                unconsumedBytes[i] = data.get(position + i);
            }
        }
    }

    /**
     * Checks that the buffer begins with the bytes the previous call left
     * unconsumed.
     */
    private void checkPresentedAgain(ByteBuffer data) {
        boolean presented = (data.remaining() >= unconsumed);
        if (presented) {
            int position = data.position();
            int kept = Math.min(unconsumed, unconsumedBytes.length);
            for (int i = 0; i < kept; i++) {
                if (data.get(position + i) != unconsumedBytes[i]) {
                    presented = false;
                    break;
                }
            }
        }
        if (!presented) {
            throw new IllegalStateException("The " + unconsumed + " unconsumed byte(s) left in the buffer by the"
                    + " previous receive() are not at the start of this one. compact() the buffer after each"
                    + " receive(), instead of clearing or replacing it, and read new data in after them.");
        }
    }

    /**
     * Signals the end of the document. Reports an error if the document
     * is incomplete: an unclosed element, a truncated multi-byte
     * character, or no document at all.
     *
     * @throws SAXException if the document is incomplete, or the handler
     *         throws
     * @throws IllegalStateException if no handler has been set
     */
    public void close() throws SAXException {
        if (decoder == null) {
            // No bytes at all: still a document, and not a well-formed one.
            open();
        }
        decoder.close();
    }

    /**
     * Discards all state of the current document so that another can be
     * parsed. The configuration, including the handler, is kept.
     */
    public void reset() {
        // Scanner is single-document: open() builds a new pair from the
        // configuration when the next document's first bytes arrive.
        scanner = null;
        decoder = null;
        initialCharset = null;
        unconsumed = 0;
        if (storage != null) {
            storage.trim();
        }
    }

    /**
     * Parses a whole document from a stream, blocking until it has been
     * read to its end. This is {@link #receive(ByteBuffer)} in a loop
     * followed by {@link #close()}. The stream is not closed.
     *
     * @param in the stream to read the document from
     * @throws IOException if the stream cannot be read
     * @throws SAXException if the document is not well-formed, or the
     *         handler throws
     * @throws IllegalArgumentException if {@code in} is null
     */
    public void parse(InputStream in) throws IOException, SAXException {
        if (in == null) {
            throw new IllegalArgumentException("InputStream cannot be null");
        }
        ByteBuffer buffer = parseBuffer;
        if (buffer == null || buffer.capacity() != inputBufferSize) {
            buffer = ByteBuffer.allocate(inputBufferSize);
            parseBuffer = buffer;
        } else {
            buffer.clear();
        }
        // The buffer stays in write mode between iterations: its position
        // is the end of any bytes the parser left unconsumed.
        while (true) {
            if (!buffer.hasRemaining()) {
                // The parser consumed nothing of a full buffer, so there
                // is no room to read into. Whether progress is being made
                // is for the caller of receive() to judge, and here that
                // is this loop.
                buffer = makeRoom(buffer);
                parseBuffer = buffer;
            }
            int bytesRead = in.read(buffer.array(), buffer.position(), buffer.remaining());
            if (bytesRead > 0) {
                buffer.position(buffer.position() + bytesRead);
            }
            if (buffer.position() > 0) {
                buffer.flip();
                receive(buffer);
                buffer.compact();
            }
            // Stop at end of stream even if bytes remain (an incomplete
            // trailing multi-byte character): no more will ever arrive,
            // and close() reports them as an error.
            if (bytesRead == -1) {
                break;
            }
        }
        close();
    }

    /**
     * Parses a whole document from a channel, blocking until it has been
     * read to its end. This is {@link #receive(ByteBuffer)} in a loop
     * followed by {@link #close()}. The channel is not closed.
     *
     * @param channel the channel to read the document from
     * @throws IOException if the channel cannot be read
     * @throws SAXException if the document is not well-formed, or the
     *         handler throws
     * @throws IllegalArgumentException if {@code channel} is null
     */
    public void parse(ReadableByteChannel channel) throws IOException, SAXException {
        if (channel == null) {
            throw new IllegalArgumentException("Channel cannot be null");
        }
        ByteBuffer buffer = parseBuffer;
        if (buffer == null || buffer.capacity() != inputBufferSize) {
            buffer = ByteBuffer.allocate(inputBufferSize);
            parseBuffer = buffer;
        } else {
            buffer.clear();
        }
        // As for parse(InputStream): stop at end of channel even if bytes
        // remain, and let close() report them.
        while (true) {
            if (!buffer.hasRemaining()) {
                // No room to read into: see parse(InputStream).
                buffer = makeRoom(buffer);
                parseBuffer = buffer;
            }
            int bytesRead = channel.read(buffer);
            if (buffer.position() > 0) {
                buffer.flip();
                receive(buffer);
                buffer.compact();
            }
            if (bytesRead == -1) {
                break;
            }
        }
        close();
    }

    /**
     * Escalates when the parser has consumed nothing of a full buffer:
     * returns a buffer of twice the capacity holding the same unconsumed
     * bytes, in write mode, so that the next call presents the parser
     * with more than it had. The parser leaves at most an incomplete
     * character unconsumed, so this is not expected to be needed; it is
     * what stands between a parser that fails to consume and a loop that
     * reads nothing for ever.
     *
     * @param buffer a full buffer in write mode
     * @return a larger buffer with the same content, in write mode
     * @throws SAXException if the buffer is already as large as it is
     *         allowed to become
     */
    static ByteBuffer makeRoom(ByteBuffer buffer) throws SAXException {
        int capacity = buffer.capacity();
        if (capacity >= MAX_PARSE_BUFFER_SIZE) {
            throw new SAXException("The parser made no progress on " + capacity + " bytes of input");
        }
        ByteBuffer bigger = ByteBuffer.allocate(capacity * 2);
        buffer.flip();
        bigger.put(buffer);
        return bigger;
    }

    /**
     * Builds the pipeline for one document from the current configuration:
     * decoder, scanner, and the namespace filter if namespaces are being
     * processed.
     */
    private void open() throws SAXException {
        XMLHandler target = handler;
        if (target == null) {
            throw new IllegalStateException("No XMLHandler has been set");
        }
        if (namespaceAware) {
            NamespaceFilter filter = new NamespaceFilter(target, false);
            filter.setNamespacePrefixes(namespacePrefixes);
            filter.setXmlnsUris(xmlnsUris);
            filter.setMaxNamespaceLength(maxNameLength);
            target = filter;
        }
        ScannerSettings settings = new ScannerSettings(externalGeneralEntities,
                externalParameterEntities, disallowDoctypeDecl, resolveDTDURIs,
                accessExternalDTD, entityExpansionLimit, maxElementDepth, maxNameLength,
                maxAttributesPerElement);
        if (storage == null || !storage.hasSizes(scanBufferSize, decodeBufferSize, nameTableSize)) {
            storage = new ScannerStorage(scanBufferSize, decodeBufferSize, nameTableSize);
        }
        Scanner newScanner = new Scanner(target, false, entityResolver, publicId, systemId, validating,
                namespaceAware, settings, true, storage);
        newScanner.setTrackLineNumbers(lineNumbers);
        ExternalEntityDecoder newDecoder = new ExternalEntityDecoder(newScanner, publicId, systemId, false);
        newDecoder.useCharBuffer(storage.decodeBuffer());
        if (initialCharset != null) {
            newDecoder.setInitialCharset(initialCharset);
            initialCharset = null;
        }
        scanner = newScanner;
        decoder = newDecoder;
    }

}

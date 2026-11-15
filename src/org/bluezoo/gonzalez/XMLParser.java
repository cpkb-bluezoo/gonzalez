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

    /** Buffer size for {@link #parse(InputStream)}. */
    private static final int STREAM_BUFFER_SIZE = 32768;

    /** Buffer size for {@link #parse(ReadableByteChannel)} when no larger
     *  buffer is already held. */
    private static final int CHANNEL_BUFFER_SIZE = 8192;

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
    private String accessExternalDTD = "";
    private int entityExpansionLimit = ScannerSettings.DEFAULT_EXPANSION_LIMIT;
    private int maxElementDepth = ScannerSettings.DEFAULT_MAX_ELEMENT_DEPTH;
    private int maxNameLength = ScannerSettings.DEFAULT_MAX_NAME_LENGTH;
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
     * @param data the bytes to parse
     * @throws SAXException if the document is not well-formed, or the
     *         handler throws
     * @throws IllegalStateException if no handler has been set, or the
     *         document has already been closed
     */
    public void receive(ByteBuffer data) throws SAXException {
        if (decoder == null) {
            open();
        }
        decoder.receive(data);
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
        if (buffer == null || buffer.capacity() < STREAM_BUFFER_SIZE) {
            buffer = ByteBuffer.allocate(STREAM_BUFFER_SIZE);
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
        if (buffer == null) {
            buffer = ByteBuffer.allocate(CHANNEL_BUFFER_SIZE);
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
        Scanner newScanner = new Scanner(target, false, entityResolver, publicId, systemId, validating,
                namespaceAware, settings, true);
        ExternalEntityDecoder newDecoder = new ExternalEntityDecoder(newScanner, publicId, systemId, false);
        if (initialCharset != null) {
            newDecoder.setInitialCharset(initialCharset);
            initialCharset = null;
        }
        scanner = newScanner;
        decoder = newDecoder;
    }

}

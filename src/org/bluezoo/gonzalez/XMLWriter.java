/*
 * XMLWriter.java
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

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
/**
 * Streaming XML serializer that writes XML to a {@link WritableByteChannel}.
 * <p>
 * The writer uses an internal buffer and automatically sends chunks to the
 * channel when the buffer fills beyond a threshold. It supports full namespace
 * handling, pretty-print indentation, and automatic empty element optimization
 * (emitting {@code <foo/>} instead of {@code <foo></foo>} when an element has
 * no content).
 * <p>
 * Elements are opened with one of the {@link #writeStartElement} overloads.
 * The start tag remains open until any non-attribute event is received
 * ({@link #writeCharacters}, {@link #writeEndElement}, {@link #writeComment},
 * etc.), allowing attributes and namespace declarations to be added after
 * opening the element. A {@link #writeEndElement} with no intervening content
 * produces the self-closing {@code />} form.
 * <p>
 * Configuration is set via setter methods that should be called before
 * writing begins:
 * <ul>
 *   <li>{@link #setIndentConfig(IndentConfig)} - optional indentation</li>
 *   <li>{@link #setCharset(Charset)} - output encoding (default UTF-8)</li>
 *   <li>{@link #setXml11(boolean)} - XML 1.1 output mode</li>
 *   <li>{@link #setStandalone(boolean)} - standalone DOCTYPE conversion mode</li>
 * </ul>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * FileChannel channel = FileChannel.open(path, WRITE, CREATE);
 * XMLWriter writer = new XMLWriter(channel);
 *
 * writer.writeStartElement("root");
 * writer.writeNamespace("ns", "http://example.com/ns");
 * writer.writeStartElement("ns", "child", "http://example.com/ns");
 * writer.writeAttribute("id", "1");
 * writer.writeCharacters("Hello World");
 * writer.writeEndElement();
 * writer.writeEndElement();
 * writer.close();
 * }</pre>
 *
 * <h2>Writing Content in Pieces</h2>
 * <p>
 * Character data may be written in any number of {@code writeCharacters}
 * calls. An attribute value, the text of a comment and the data of a
 * processing instruction can be written in pieces too, between a start
 * and an end call, so that neither the caller nor the writer ever holds
 * the whole of one:
 * <pre>{@code
 * writer.writeStartElement("item");
 * writer.writeStartAttribute("summary");
 * writer.writeAttributeContent(firstPart);
 * writer.writeAttributeContent(secondPart);
 * writer.writeEndAttribute();
 * }</pre>
 * {@link #writeStartComment()} and
 * {@link #writeStartProcessingInstruction(String)} work the same way.
 * These are the counterparts of the events of {@link XMLHandler}, which
 * reports each of them in pieces. The pieces may be any
 * {@link CharSequence}, are not retained, and may divide a surrogate
 * pair between them. Nothing else may be written until the end call has
 * been made.
 * <p>
 * Whatever is written, the buffer is sent to the channel when it fills
 * and does not grow with the content. It is replaced by a larger one only
 * for a single item, such as a name, that would not fit in it empty.
 *
 * <h2>DTD Output</h2>
 * <p>
 * The writer can serialize complete DOCTYPE declarations including element,
 * attribute, entity, and notation declarations via the {@code writeStartDTD},
 * {@code writeEndDTD}, and related methods.
 *
 * <h2>Standalone Conversion</h2>
 * <p>
 * When {@link #setStandalone(boolean) standalone} mode is enabled, the writer
 * omits external DOCTYPE identifiers and inlines all DTD declarations into
 * the internal subset, producing a self-contained document. When disabled
 * (the default), only internal subset declarations are written.
 *
 * <h2>Thread Safety</h2>
 * <p>
 * This class is NOT thread-safe. It is intended for use on a single thread.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class XMLWriter {

    private static final int DEFAULT_CAPACITY = 4096;
    private static final float SEND_THRESHOLD = 0.75f;

    /** The most bytes one code point takes in UTF-8. */
    private static final int MAX_UTF8_BYTES = 4;

    private final WritableByteChannel channel;
    private ByteBuffer buffer;
    private final int sendThreshold;

    // Configuration (set before writing begins)
    private IndentConfig indentConfig;
    private Charset charset;
    private boolean xml11;
    private boolean standalone;
    private byte[] pendingBOM;

    // Pre-encoded ASCII bytes for the current charset.
    // For UTF-8 each entry is a single byte; for UTF-16 two bytes; etc.
    private byte[][] encodedAscii;
    private int maxAsciiBytes;
    /** Whether the charset encodes each ASCII character as the one byte
     *  of the same value, as all but UTF-16, UTF-32 and EBCDIC do. */
    private boolean asciiCompatible;

    // Element stack for tracking open elements
    private final Deque<ElementInfo> elementStack = new ArrayDeque<ElementInfo>();

    // Namespace context: maps prefix -> URI at current scope
    private final Deque<Map<String, String>> namespaceStack = new ArrayDeque<Map<String, String>>();
    /** Shared empty scope; replaced with a HashMap on first declaration in the element. */
    private static final Map<String, String> EMPTY_NS_SCOPE = Collections.emptyMap();

    // Pending start tag that hasn't been closed yet (for empty element optimization)
    private boolean pendingStartTag = false;

    // Whether we've written any content since the start tag
    private boolean hasContent = false;

    // Whether we've written nested elements (for indentation of closing tag)
    private boolean hasNestedElements = false;

    // Track if we're at the document start (for indentation)
    private boolean atDocumentStart = true;

    // CDATA state
    private boolean inCDATA = false;

    /**
     * A high surrogate that ended one call's character data, kept back so
     * that it can be written as one character with the low surrogate that
     * begins the next call's. Zero when there is none.
     */
    private char pendingHighSurrogate;

    // What is being written in pieces, between a writeStart and a writeEnd
    private static final int OPEN_NONE = 0;
    private static final int OPEN_ATTRIBUTE = 1;
    private static final int OPEN_COMMENT = 2;
    private static final int OPEN_PI = 3;
    private int open = OPEN_NONE;

    // Kinds of content that is written as it is, with no escaping
    private static final int RAW_COMMENT = 0;
    private static final int RAW_PI = 1;
    private static final int RAW_CDATA = 2;

    /** The comment or processing instruction being written in pieces is
     *  one that is left out (it is in a part of the DTD that is not being
     *  written). */
    private boolean skipping;

    /** The comment so far ends with '-', or the processing instruction
     *  data so far with '?': what must not be followed by the character
     *  that would end the construct early. */
    private boolean endsWithDelimiterStart;

    /** Whether the processing instruction being written in pieces has any
     *  data yet, and so the space that separates it from the target. */
    private boolean piHasData;

    /** How many ']' the CDATA section so far ends with, up to two. */
    private int cdataBrackets;

    // For a character the charset has no special case for, in content
    // that cannot use a character reference in its place
    private CharsetEncoder replacingEncoder;
    private CharBuffer replacingIn;
    private ByteBuffer replacingOut;

    // DOCTYPE state
    private boolean inDTD = false;
    private boolean dtdInternalSubsetOpen = false;
    private boolean inExternalSubset = false;

    /**
     * Information about an open element.
     */
    private static class ElementInfo {
        final String qName;
        final boolean hadContent;
        final boolean hadNestedElements;

        ElementInfo(String qName, boolean hadContent, boolean hadNestedElements) {
            this.qName = qName;
            this.hadContent = hadContent;
            this.hadNestedElements = hadNestedElements;
        }
    }

    /**
     * Creates a new XML writer with default capacity (4KB) writing to an output stream.
     *
     * @param out the output stream to write to
     */
    public XMLWriter(OutputStream out) {
        this(new OutputStreamChannel(out), DEFAULT_CAPACITY);
    }

    /**
     * Creates a new XML writer writing to an output stream with the specified
     * indentation configuration.
     *
     * @param out the output stream to write to
     * @param indentConfig the indentation configuration, or null for no indentation
     */
    public XMLWriter(OutputStream out, IndentConfig indentConfig) {
        this(new OutputStreamChannel(out), DEFAULT_CAPACITY);
        this.indentConfig = indentConfig;
    }

    /**
     * Creates a new XML writer with default capacity (4KB).
     *
     * @param channel the channel to write to
     */
    public XMLWriter(WritableByteChannel channel) {
        this(channel, DEFAULT_CAPACITY);
    }

    /**
     * Creates a new XML writer with specified buffer capacity.
     *
     * @param channel the channel to write to
     * @param bufferCapacity initial buffer capacity in bytes
     */
    public XMLWriter(WritableByteChannel channel, int bufferCapacity) {
        this.channel = channel;
        this.buffer = ByteBuffer.allocate(bufferCapacity);
        this.sendThreshold = (int) (bufferCapacity * SEND_THRESHOLD);
        this.charset = StandardCharsets.UTF_8;
        initEncodedAscii();
        namespaceStack.push(EMPTY_NS_SCOPE);
    }

    /**
     * Creates a new XML writer with specified buffer capacity and indentation.
     *
     * @param channel the channel to write to
     * @param bufferCapacity initial buffer capacity in bytes
     * @param indentConfig the indentation configuration, or null for no indentation
     */
    public XMLWriter(WritableByteChannel channel, int bufferCapacity, IndentConfig indentConfig) {
        this(channel, bufferCapacity);
        this.indentConfig = indentConfig;
    }

    // ========== Configuration Setters ==========

    /**
     * Sets the indentation configuration.
     * Should be called before writing begins.
     *
     * @param indentConfig the indentation configuration, or null for no indentation
     * @since 1.1
     */
    public void setIndentConfig(IndentConfig indentConfig) {
        this.indentConfig = indentConfig;
    }

    private static final byte[] BOM_UTF16_BE = { (byte) 0xFE, (byte) 0xFF };
    private static final byte[] BOM_UTF16_LE = { (byte) 0xFF, (byte) 0xFE };
    private static final byte[] BOM_UTF32_BE = { (byte) 0x00, (byte) 0x00, (byte) 0xFE, (byte) 0xFF };
    private static final byte[] BOM_UTF32_LE = { (byte) 0xFF, (byte) 0xFE, (byte) 0x00, (byte) 0x00 };

    /**
     * Sets the output character encoding.
     * Should be called before writing begins.
     *
     * @param charset the character encoding, or null for UTF-8
     * @since 1.1
     */
    public void setCharset(Charset charset) {
        if (charset == null) {
            charset = StandardCharsets.UTF_8;
        }
        String csName = charset.name().toUpperCase();
        if ("UTF-16".equals(csName) || "UTF-16BE".equals(csName)) {
            this.charset = StandardCharsets.UTF_16BE;
            this.pendingBOM = BOM_UTF16_BE;
        } else if ("UTF-16LE".equals(csName)) {
            this.charset = StandardCharsets.UTF_16LE;
            this.pendingBOM = BOM_UTF16_LE;
        } else if ("UTF-32".equals(csName) || "UTF-32BE".equals(csName)) {
            this.charset = Charset.forName("UTF-32BE");
            this.pendingBOM = BOM_UTF32_BE;
        } else if ("UTF-32LE".equals(csName)) {
            this.charset = Charset.forName("UTF-32LE");
            this.pendingBOM = BOM_UTF32_LE;
        } else {
            this.charset = charset;
            this.pendingBOM = null;
        }
        replacingEncoder = null;
        initEncodedAscii();
    }

    /**
     * Sets XML 1.1 output mode. When enabled, CR characters and C1 control
     * characters are escaped as character references.
     * Should be called before writing begins.
     *
     * @param xml11 true for XML 1.1 mode
     * @since 1.1
     */
    public void setXml11(boolean xml11) {
        this.xml11 = xml11;
    }

    /**
     * Sets standalone DOCTYPE conversion mode. When enabled, external DOCTYPE
     * identifiers (SYSTEM/PUBLIC) are omitted and all DTD declarations are
     * inlined into the internal subset, producing a self-contained document.
     * Should be called before writing begins.
     *
     * @param standalone true for standalone conversion mode
     * @since 1.1
     */
    public void setStandalone(boolean standalone) {
        this.standalone = standalone;
    }

    // ========== Element Methods ==========

    /**
     * Opens a start element tag with only a local name (no namespace).
     *
     * <p>The start tag remains open (no closing {@code >}) until any
     * non-attribute event is received. Namespace declarations and attributes
     * can be added via {@link #writeNamespace}, {@link #writeDefaultNamespace},
     * and {@link #writeAttribute} while the tag is open.
     *
     * @param localName the element local name
     * @throws IOException if there is an error writing data
     */
    public void writeStartElement(String localName) throws IOException {
        closePendingStartTag(false);

        if (indentConfig != null && !atDocumentStart) {
            writeIndent();
        }
        atDocumentStart = false;

        writeRawString("<");
        writeRawString(localName);

        namespaceStack.push(EMPTY_NS_SCOPE);
        elementStack.push(new ElementInfo(localName, hasContent, hasNestedElements));

        pendingStartTag = true;
        hasContent = false;
        hasNestedElements = false;

        sendIfNeeded();
    }

    /**
     * Opens a start element tag with a namespace URI and local name.
     *
     * <p>The prefix is resolved from the current namespace context. If the
     * namespace URI is bound to a prefix, the element is written as
     * {@code <prefix:localName}; otherwise just {@code <localName}.
     *
     * @param namespaceURI the namespace URI, or empty string for no namespace
     * @param localName the element local name
     * @throws IOException if there is an error writing data
     */
    public void writeStartElement(String namespaceURI, String localName) throws IOException {
        closePendingStartTag(false);

        if (indentConfig != null && !atDocumentStart) {
            writeIndent();
        }
        atDocumentStart = false;

        String prefix = null;
        if (namespaceURI != null && !namespaceURI.isEmpty()) {
            prefix = getPrefix(namespaceURI);
        }

        String qName;
        if (prefix != null && !prefix.isEmpty()) {
            qName = prefix + ":" + localName;
        } else {
            qName = localName;
        }

        writeRawString("<");
        writeRawString(qName);

        namespaceStack.push(EMPTY_NS_SCOPE);
        elementStack.push(new ElementInfo(qName, hasContent, hasNestedElements));

        pendingStartTag = true;
        hasContent = false;
        hasNestedElements = false;

        sendIfNeeded();
    }

    /**
     * Opens a start element tag with an explicit prefix, local name, and
     * namespace URI.
     *
     * <p>If the prefix is non-empty, the element is written as
     * {@code <prefix:localName}; otherwise just {@code <localName}.
     * The prefix-to-URI binding is registered in the current namespace scope
     * so that {@link #getPrefix} can resolve it for descendant elements.
     *
     * <p><b>Note:</b> This method registers the binding internally but does
     * not write an {@code xmlns} declaration. Call {@link #writeNamespace}
     * or {@link #writeDefaultNamespace} explicitly if a declaration is needed.
     *
     * @param prefix the namespace prefix, or empty string for the default namespace
     * @param localName the element local name
     * @param namespaceURI the namespace URI
     * @throws IOException if there is an error writing data
     */
    public void writeStartElement(String prefix, String localName, String namespaceURI)
            throws IOException {
        closePendingStartTag(false);

        if (indentConfig != null && !atDocumentStart) {
            writeIndent();
        }
        atDocumentStart = false;

        String qName;
        if (prefix != null && !prefix.isEmpty()) {
            qName = prefix + ":" + localName;
        } else {
            qName = localName;
        }

        writeRawString("<");
        writeRawString(qName);

        namespaceStack.push(EMPTY_NS_SCOPE);
        elementStack.push(new ElementInfo(qName, hasContent, hasNestedElements));

        pendingStartTag = true;
        hasContent = false;
        hasNestedElements = false;

        sendIfNeeded();
    }

    /**
     * Closes the current element.
     *
     * <p>If the element has no content (i.e. {@link #writeEndElement} is called
     * immediately after a {@code writeStartElement}), the self-closing form
     * {@code <foo/>} is used. Otherwise the full {@code </foo>} closing tag is
     * written.
     *
     * @throws IOException if there is an error writing data, or no element is open
     */
    public void writeEndElement() throws IOException {
        checkNothingOpen();
        if (elementStack.isEmpty()) {
            throw new IOException("No open element to close");
        }

        ElementInfo element = elementStack.pop();
        namespaceStack.pop();

        if (pendingStartTag && !hasContent) {
            writeRawString("/>");
            pendingStartTag = false;
        } else {
            closePendingStartTag(false);

            if (indentConfig != null && hasNestedElements) {
                writeIndent();
            }

            writeRawString("</");
            writeRawString(element.qName);
            writeRawString(">");
        }

        hasContent = element.hadContent;
        hasNestedElements = element.hadNestedElements;
        hasContent = true;
        hasNestedElements = true;

        sendIfNeeded();
    }

    // ========== Attribute Methods ==========

    /**
     * Writes an attribute with a local name and value.
     *
     * <p>Must be called while a start tag is open (after a {@code writeStartElement}
     * and before any content).
     *
     * @param localName the attribute local name
     * @param value the attribute value
     * @throws IOException if there is an error writing data or no start tag is open
     */
    public void writeAttribute(String localName, String value) throws IOException {
        if (!pendingStartTag) {
            throw new IOException("writeAttribute() called outside of start element");
        }
        checkNothingOpen();
        writeAttributeOutput(localName, value);
        sendIfNeeded();
    }

    /**
     * Writes a namespaced attribute. The prefix is resolved from the current
     * namespace context.
     *
     * @param namespaceURI the attribute namespace URI
     * @param localName the attribute local name
     * @param value the attribute value
     * @throws IOException if there is an error writing data or no start tag is open
     */
    public void writeAttribute(String namespaceURI, String localName, String value)
            throws IOException {
        if (!pendingStartTag) {
            throw new IOException("writeAttribute() called outside of start element");
        }
        checkNothingOpen();

        String prefix = null;
        if (namespaceURI != null && !namespaceURI.isEmpty()) {
            prefix = getPrefix(namespaceURI);
        }

        String qName;
        if (prefix != null && !prefix.isEmpty()) {
            qName = prefix + ":" + localName;
        } else {
            qName = localName;
        }

        writeAttributeOutput(qName, value);
        sendIfNeeded();
    }

    /**
     * Writes a namespaced attribute with an explicit prefix.
     *
     * @param prefix the attribute namespace prefix, or empty string
     * @param localName the attribute local name
     * @param namespaceURI the attribute namespace URI
     * @param value the attribute value
     * @throws IOException if there is an error writing data or no start tag is open
     */
    public void writeAttribute(String prefix, String localName, String namespaceURI,
            String value) throws IOException {
        if (!pendingStartTag) {
            throw new IOException("writeAttribute() called outside of start element");
        }
        checkNothingOpen();

        String qName;
        if (prefix != null && !prefix.isEmpty()) {
            qName = prefix + ":" + localName;
        } else {
            qName = localName;
        }

        writeAttributeOutput(qName, value);
        sendIfNeeded();
    }

    /**
     * Starts an attribute whose value is supplied in pieces: writes the
     * name and the opening quote. Follow it with any number of
     * {@link #writeAttributeContent(CharSequence)} calls, then
     * {@link #writeEndAttribute()}.
     *
     * <p>This is the counterpart of {@link XMLHandler#startAttribute}, so
     * that a parser can feed a writer with neither holding the whole
     * value. Must be called while a start tag is open, like
     * {@link #writeAttribute(String, String)}.
     *
     * @param name the attribute name, with its prefix if it has one
     * @throws IOException if there is an error writing data or no start tag is open
     */
    public void writeStartAttribute(String name) throws IOException {
        if (!pendingStartTag) {
            throw new IOException("writeStartAttribute() called outside of start element");
        }
        checkNothingOpen();
        writeRawString(" ");
        writeRawString(name);
        writeRawString("=\"");
        open = OPEN_ATTRIBUTE;
    }

    /**
     * Writes the next characters of the value of an attribute started
     * with {@link #writeStartAttribute(String)}, escaping them as needed.
     * They are encoded and sent on as they are written, so the writer's
     * buffer does not grow with the length of the value. The content is
     * not retained, so the caller may reuse it as soon as this method
     * returns. A surrogate pair may be split across two calls.
     *
     * @param content the characters to write
     * @throws IOException if there is an error writing data or no
     *         attribute has been started
     */
    public void writeAttributeContent(CharSequence content) throws IOException {
        if (open != OPEN_ATTRIBUTE) {
            throw new IOException("writeAttributeContent() called without writeStartAttribute()");
        }
        writeEscapedAttributeValue(content);
        sendIfNeeded();
    }

    /**
     * Ends an attribute started with {@link #writeStartAttribute(String)}:
     * writes the closing quote.
     *
     * @throws IOException if there is an error writing data or no
     *         attribute has been started
     */
    public void writeEndAttribute() throws IOException {
        if (open != OPEN_ATTRIBUTE) {
            throw new IOException("writeEndAttribute() called without writeStartAttribute()");
        }
        endAttributeValue();
        writeRawString("\"");
        open = OPEN_NONE;
        sendIfNeeded();
    }

    // ========== Namespace Methods ==========

    /**
     * Writes a namespace declaration ({@code xmlns:prefix="uri"}).
     *
     * <p>Must be called while a start tag is open. Redundant declarations
     * (where the prefix is already bound to the same URI in an ancestor scope)
     * are suppressed.
     *
     * @param prefix the namespace prefix
     * @param namespaceURI the namespace URI
     * @throws IOException if there is an error writing data or no start tag is open
     */
    public void writeNamespace(String prefix, String namespaceURI) throws IOException {
        if (!pendingStartTag) {
            throw new IOException("writeNamespace() called outside of start element");
        }
        checkNothingOpen();
        if (prefix == null || prefix.isEmpty()) {
            writeDefaultNamespace(namespaceURI);
            return;
        }
        writeNamespaceDeclaration(prefix, namespaceURI);
        mutableNamespaceScope().put(prefix, namespaceURI != null ? namespaceURI : "");
    }

    /**
     * Writes a default namespace declaration ({@code xmlns="uri"}).
     *
     * <p>Must be called while a start tag is open. Redundant declarations
     * are suppressed.
     *
     * @param namespaceURI the namespace URI
     * @throws IOException if there is an error writing data or no start tag is open
     */
    public void writeDefaultNamespace(String namespaceURI) throws IOException {
        if (!pendingStartTag) {
            throw new IOException("writeDefaultNamespace() called outside of start element");
        }
        checkNothingOpen();
        writeNamespaceDeclaration("", namespaceURI);
        mutableNamespaceScope().put("", namespaceURI != null ? namespaceURI : "");
    }

    /**
     * Returns the current element's namespace scope map, allocating one if
     * the placeholder empty scope is still in place.
     */
    private Map<String, String> mutableNamespaceScope() {
        Map<String, String> scope = namespaceStack.peek();
        if (scope == EMPTY_NS_SCOPE || scope.isEmpty()) {
            namespaceStack.pop();
            scope = new HashMap<String, String>();
            namespaceStack.push(scope);
        }
        return scope;
    }

    // ========== Character Methods ==========

    /**
     * Writes character content from a string.
     *
     * <p>When inside a CDATA section (between {@link #writeStartCDATA()} and
     * {@link #writeEndCDATA()}), no escaping is performed. An {@code IOException}
     * is thrown if the text contains the CDATA end delimiter {@code ]]>} or
     * characters that are illegal in XML and cannot be escaped inside CDATA.
     *
     * @param text the character content
     * @throws IOException if there is an error writing data, or if CDATA content
     *         contains illegal sequences
     */
    public void writeCharacters(String text) throws IOException {
        writeCharacters((CharSequence) text);
    }

    /**
     * Writes character content from any sequence of characters, such as
     * the {@link CharBuffer} that {@link XMLHandler#characters} is given.
     * The content is not retained, so the caller may reuse it as soon as
     * this method returns.
     *
     * <p>Character content may be written in any number of calls, and a
     * surrogate pair may be split across two of them. Inside a CDATA
     * section no escaping is performed, as for
     * {@link #writeCharacters(String)}, and the end delimiter {@code ]]>}
     * is refused even when it is divided between calls.
     *
     * @param text the character content
     * @throws IOException if there is an error writing data, or if CDATA content
     *         contains illegal sequences
     */
    public void writeCharacters(CharSequence text) throws IOException {
        if (text == null || text.length() == 0) {
            return;
        }
        checkNothingOpen();
        closeStartTag(true);
        if (inCDATA) {
            writeRawContent(text, RAW_CDATA);
        } else {
            writeEscapedCharacters(text);
        }
        sendIfNeeded();
    }

    /**
     * Writes character content from a character array.
     *
     * <p>When inside a CDATA section (between {@link #writeStartCDATA()} and
     * {@link #writeEndCDATA()}), no escaping is performed. An {@code IOException}
     * is thrown if the text contains the CDATA end delimiter {@code ]]>} or
     * characters that are illegal in XML and cannot be escaped inside CDATA.
     *
     * @param ch the character array
     * @param start the start offset
     * @param length the number of characters
     * @throws IOException if there is an error writing data, or if CDATA content
     *         contains illegal sequences
     */
    public void writeCharacters(char[] ch, int start, int length) throws IOException {
        if (length == 0) {
            return;
        }
        checkNothingOpen();
        closeStartTag(true);
        if (inCDATA) {
            writeRawContent(CharBuffer.wrap(ch, start, length), RAW_CDATA);
        } else {
            writeEscapedCharacters(ch, start, length);
        }
        sendIfNeeded();
    }

    /**
     * Writes a complete CDATA section.
     *
     * @deprecated Use {@link #writeStartCDATA()}, {@link #writeCharacters(String)},
     *             {@link #writeEndCDATA()} instead for streaming CDATA output.
     * @param data the CDATA content
     * @throws IOException if there is an error writing data
     */
    @Deprecated
    public void writeCData(String data) throws IOException {
        writeStartCDATA();
        writeCharacters(data);
        writeEndCDATA();
    }

    /**
     * Opens a CDATA section.
     *
     * <p>Character content written between {@code writeStartCDATA} and
     * {@link #writeEndCDATA} is written without XML escaping. For writing
     * a complete CDATA section in one call, use {@link #writeCData(String)}.
     *
     * @throws IOException if there is an error writing data
     * @since 1.1
     */
    public void writeStartCDATA() throws IOException {
        closePendingStartTag(true);
        inCDATA = true;
        cdataBrackets = 0;
        writeRawString("<![CDATA[");
        sendIfNeeded();
    }

    /**
     * Closes a CDATA section previously opened with {@link #writeStartCDATA()}.
     *
     * @throws IOException if there is an error writing data
     * @since 1.1
     */
    public void writeEndCDATA() throws IOException {
        endCharacters();
        inCDATA = false;
        writeRawString("]]>");
        sendIfNeeded();
    }

    /**
     * Writes a comment.
     *
     * @param text the comment text
     * @throws IOException if there is an error writing data
     */
    public void writeComment(String text) throws IOException {
        writeStartComment();
        writeCommentContent(text);
        writeEndComment();
    }

    /**
     * Starts a comment whose text is supplied in pieces: writes the
     * opening {@code <!--}. Follow it with any number of
     * {@link #writeCommentContent(CharSequence)} calls, then
     * {@link #writeEndComment()}. This is the counterpart of
     * {@link XMLHandler#startComment()}.
     *
     * @throws IOException if there is an error writing data
     */
    public void writeStartComment() throws IOException {
        checkNothingOpen();
        skipping = inDTD && !shouldWriteDeclaration();
        if (!skipping) {
            closePendingStartTag(true);

            if (inDTD) {
                openInternalSubsetIfNeeded();
            }

            if (!inDTD) {
                // Inside the DTD the document has still not started: the
                // line the DTD ends with is the one the root goes on.
                if (indentConfig != null && !atDocumentStart) {
                    writeIndent();
                }
                atDocumentStart = false;
            }

            writeRawString("<!--");
            endsWithDelimiterStart = false;
        }
        open = OPEN_COMMENT;
    }

    /**
     * Writes the next characters of the text of a comment started with
     * {@link #writeStartComment()}. They are encoded and sent on as they
     * are written, so the writer's buffer does not grow with the length
     * of the comment, and are not retained. A surrogate pair may be split
     * across two calls.
     *
     * <p>A comment cannot contain {@code --} or end with {@code -}. As
     * with {@link #writeComment(String)}, a space is written wherever
     * that would otherwise happen, including where the two hyphens come
     * in different calls.
     *
     * @param content the characters to write
     * @throws IOException if there is an error writing data or no comment
     *         has been started
     */
    public void writeCommentContent(CharSequence content) throws IOException {
        if (open != OPEN_COMMENT) {
            throw new IOException("writeCommentContent() called without writeStartComment()");
        }
        if (skipping) {
            return;
        }
        writeRawContent(content, RAW_COMMENT);
        sendIfNeeded();
    }

    /**
     * Ends a comment started with {@link #writeStartComment()}: writes
     * the closing {@code -->}.
     *
     * @throws IOException if there is an error writing data or no comment
     *         has been started
     */
    public void writeEndComment() throws IOException {
        if (open != OPEN_COMMENT) {
            throw new IOException("writeEndComment() called without writeStartComment()");
        }
        open = OPEN_NONE;
        if (skipping) {
            skipping = false;
            return;
        }
        endRawContent(RAW_COMMENT);
        if (endsWithDelimiterStart) {
            writeRawString(" ");
        }
        writeRawString("-->");

        if (inDTD) {
            writeRawString("\n");
        }

        sendIfNeeded();
    }

    /**
     * Writes a processing instruction with no data.
     *
     * @param target the PI target
     * @throws IOException if there is an error writing data
     */
    public void writeProcessingInstruction(String target) throws IOException {
        writeProcessingInstruction(target, null);
    }

    /**
     * Writes a processing instruction with optional data.
     *
     * @param target the PI target
     * @param data the PI data, or null for no data
     * @throws IOException if there is an error writing data
     */
    public void writeProcessingInstruction(String target, String data) throws IOException {
        writeStartProcessingInstruction(target);
        if (data != null) {
            writeProcessingInstructionContent(data);
        }
        writeEndProcessingInstruction();
    }

    /**
     * Starts a processing instruction whose data is supplied in pieces:
     * writes the opening {@code <?} and the target. Follow it with any
     * number of {@link #writeProcessingInstructionContent(CharSequence)}
     * calls, then {@link #writeEndProcessingInstruction()}. This is the
     * counterpart of {@link XMLHandler#piTarget(String)}.
     *
     * @param target the PI target
     * @throws IOException if there is an error writing data
     */
    public void writeStartProcessingInstruction(String target) throws IOException {
        checkNothingOpen();
        skipping = inDTD && !shouldWriteDeclaration();
        if (!skipping) {
            closePendingStartTag(true);

            if (inDTD) {
                // Like a declaration or a comment, it can only go inside
                // the internal subset.
                openInternalSubsetIfNeeded();
            }

            if (!inDTD) {
                // Inside the DTD the document has still not started: the
                // line the DTD ends with is the one the root goes on.
                if (indentConfig != null && !atDocumentStart) {
                    writeIndent();
                }
                atDocumentStart = false;
            }

            writeRawString("<?");
            writeRawString(target);
            piHasData = false;
            endsWithDelimiterStart = false;
        }
        open = OPEN_PI;
    }

    /**
     * Writes the next characters of the data of a processing instruction
     * started with {@link #writeStartProcessingInstruction(String)}. They
     * are encoded and sent on as they are written, so the writer's buffer
     * does not grow with the length of the data, and are not retained. A
     * surrogate pair may be split across two calls.
     *
     * <p>The space that separates the data from the target is written
     * before the first character, so an instruction given no data has
     * none. The data cannot contain {@code ?>}. As with
     * {@link #writeProcessingInstruction(String, String)}, a space is
     * written between the two characters wherever they occur, including
     * where they come in different calls.
     *
     * @param content the characters to write
     * @throws IOException if there is an error writing data or no
     *         processing instruction has been started
     */
    public void writeProcessingInstructionContent(CharSequence content) throws IOException {
        if (open != OPEN_PI) {
            throw new IOException(
                    "writeProcessingInstructionContent() called without writeStartProcessingInstruction()");
        }
        if (skipping || content.length() == 0) {
            return;
        }
        if (!piHasData) {
            writeRawString(" ");
            piHasData = true;
        }
        writeRawContent(content, RAW_PI);
        sendIfNeeded();
    }

    /**
     * Ends a processing instruction started with
     * {@link #writeStartProcessingInstruction(String)}: writes the
     * closing {@code ?>}.
     *
     * @throws IOException if there is an error writing data or no
     *         processing instruction has been started
     */
    public void writeEndProcessingInstruction() throws IOException {
        if (open != OPEN_PI) {
            throw new IOException(
                    "writeEndProcessingInstruction() called without writeStartProcessingInstruction()");
        }
        open = OPEN_NONE;
        if (skipping) {
            skipping = false;
            return;
        }
        endRawContent(RAW_PI);
        writeRawString("?>");

        if (inDTD) {
            writeRawString("\n");
        }

        sendIfNeeded();
    }

    /**
     * Writes an entity reference ({@code &name;}).
     *
     * @param name the entity name
     * @throws IOException if there is an error writing data
     */
    public void writeEntityRef(String name) throws IOException {
        closePendingStartTag(true);
        writeRawString("&");
        writeRawString(name);
        writeRawString(";");
        sendIfNeeded();
    }

    /**
     * Writes raw content without XML escaping.
     * <p>
     * This method is used for XSLT's disable-output-escaping feature.
     * The text is written directly to the output without escaping
     * special characters like &lt;, &gt;, or &amp;.
     * <p>
     * <b>Warning:</b> Using this method can produce output that is not
     * well-formed XML.
     *
     * @param text the raw text to write without escaping
     * @throws IOException if there is an error writing data
     * @since 1.1
     */
    public void writeRaw(String text) throws IOException {
        if (text == null || text.isEmpty()) {
            return;
        }
        closePendingStartTag(true);
        writeRawString(text);
        sendIfNeeded();
    }

    // ========== DTD Methods ==========

    /**
     * Writes the start of a DOCTYPE declaration.
     *
     * <p>In {@linkplain #setStandalone(boolean) standalone} mode, external
     * identifiers (PUBLIC/SYSTEM) are omitted.
     *
     * @param name the document type name (root element name)
     * @param publicId the public identifier, or null
     * @param systemId the system identifier, or null
     * @throws IOException if there is an error writing data
     * @since 1.1
     */
    public void writeStartDTD(String name, String publicId, String systemId) throws IOException {
        inDTD = true;
        dtdInternalSubsetOpen = false;
        inExternalSubset = false;

        writeRawString("<!DOCTYPE ");
        writeRawString(name);

        if (!standalone) {
            if (publicId != null) {
                writeRawString(" PUBLIC ");
                writeIdLiteral(publicId);
                writeRawString(" ");
                writeIdLiteral(systemId != null ? systemId : "");
            } else if (systemId != null) {
                writeRawString(" SYSTEM ");
                writeIdLiteral(systemId);
            }
        }

        sendIfNeeded();
    }

    /**
     * Writes the end of a DOCTYPE declaration.
     *
     * @throws IOException if there is an error writing data
     * @since 1.1
     */
    public void writeEndDTD() throws IOException {
        if (dtdInternalSubsetOpen) {
            writeRawString("]>");
        } else {
            writeRawString(">");
        }
        if (indentConfig != null) {
            writeRawString("\n");
        }
        inDTD = false;
        dtdInternalSubsetOpen = false;
        inExternalSubset = false;
        sendIfNeeded();
    }

    /**
     * Writes an element declaration in the DTD internal subset.
     *
     * @param name the element name
     * @param model the content model
     * @throws IOException if there is an error writing data
     * @since 1.1
     */
    public void writeElementDecl(String name, String model) throws IOException {
        if (!shouldWriteDeclaration()) {
            return;
        }
        openInternalSubsetIfNeeded();

        writeRawString("  <!ELEMENT ");
        writeRawString(name);
        writeRawString(" ");
        writeRawString(model);
        writeRawString(">\n");
        sendIfNeeded();
    }

    /**
     * Writes an attribute declaration in the DTD internal subset.
     *
     * @param eName the element name
     * @param aName the attribute name
     * @param type the attribute type
     * @param mode the default declaration (#REQUIRED, #IMPLIED, #FIXED), or null
     * @param value the default value, or null
     * @throws IOException if there is an error writing data
     * @since 1.1
     */
    public void writeAttributeDecl(String eName, String aName, String type,
            String mode, String value) throws IOException {
        if (!shouldWriteDeclaration()) {
            return;
        }
        openInternalSubsetIfNeeded();

        writeRawString("  <!ATTLIST ");
        writeRawString(eName);
        writeRawString(" ");
        writeRawString(aName);
        writeRawString(" ");
        writeRawString(type);
        if (mode != null && !mode.isEmpty()) {
            writeRawString(" ");
            writeRawString(mode);
        }
        if (value != null) {
            writeRawString(" \"");
            writeEscapedAttributeValue(value);
            endAttributeValue();
            writeRawString("\"");
        }
        writeRawString(">\n");
        sendIfNeeded();
    }

    /**
     * Writes an internal entity declaration in the DTD internal subset.
     *
     * @param name the entity name (prefix with "%" for parameter entities)
     * @param value the entity replacement value
     * @throws IOException if there is an error writing data
     * @since 1.1
     */
    public void writeInternalEntityDecl(String name, String value) throws IOException {
        if (!shouldWriteDeclaration()) {
            return;
        }
        openInternalSubsetIfNeeded();

        if (name.startsWith("%")) {
            writeRawString("  <!ENTITY % ");
            writeRawString(name.substring(1));
        } else {
            writeRawString("  <!ENTITY ");
            writeRawString(name);
        }
        writeRawString(" \"");
        writeEscapedEntityValue(value);
        writeRawString("\">\n");
        sendIfNeeded();
    }

    /**
     * Writes an external entity declaration in the DTD internal subset.
     *
     * @param name the entity name (prefix with "%" for parameter entities)
     * @param publicId the public identifier, or null
     * @param systemId the system identifier
     * @throws IOException if there is an error writing data
     * @since 1.1
     */
    public void writeExternalEntityDecl(String name, String publicId, String systemId)
            throws IOException {
        if (!shouldWriteDeclaration()) {
            return;
        }
        openInternalSubsetIfNeeded();

        if (name.startsWith("%")) {
            writeRawString("  <!ENTITY % ");
            writeRawString(name.substring(1));
        } else {
            writeRawString("  <!ENTITY ");
            writeRawString(name);
        }
        if (publicId != null) {
            writeRawString(" PUBLIC ");
            writeIdLiteral(publicId);
            writeRawString(" ");
            writeIdLiteral(systemId);
        } else {
            writeRawString(" SYSTEM ");
            writeIdLiteral(systemId);
        }
        writeRawString(">\n");
        sendIfNeeded();
    }

    /**
     * Writes a notation declaration in the DTD internal subset.
     *
     * @param name the notation name
     * @param publicId the public identifier, or null
     * @param systemId the system identifier, or null
     * @throws IOException if there is an error writing data
     * @since 1.1
     */
    public void writeNotationDecl(String name, String publicId, String systemId)
            throws IOException {
        if (!shouldWriteDeclaration()) {
            return;
        }
        openInternalSubsetIfNeeded();

        writeRawString("  <!NOTATION ");
        writeRawString(name);
        if (publicId != null) {
            writeRawString(" PUBLIC ");
            writeIdLiteral(publicId);
            if (systemId != null) {
                writeRawString(" ");
                writeIdLiteral(systemId);
            }
        } else if (systemId != null) {
            writeRawString(" SYSTEM ");
            writeIdLiteral(systemId);
        }
        writeRawString(">\n");
        sendIfNeeded();
    }

    /**
     * Writes an unparsed entity declaration in the DTD internal subset.
     *
     * @param name the entity name
     * @param publicId the public identifier, or null
     * @param systemId the system identifier
     * @param notationName the notation name
     * @throws IOException if there is an error writing data
     * @since 1.1
     */
    public void writeUnparsedEntityDecl(String name, String publicId,
            String systemId, String notationName) throws IOException {
        if (!shouldWriteDeclaration()) {
            return;
        }
        openInternalSubsetIfNeeded();

        writeRawString("  <!ENTITY ");
        writeRawString(name);
        if (publicId != null) {
            writeRawString(" PUBLIC ");
            writeIdLiteral(publicId);
            writeRawString(" ");
            writeIdLiteral(systemId);
        } else {
            writeRawString(" SYSTEM ");
            writeIdLiteral(systemId);
        }
        writeRawString(" NDATA ");
        writeRawString(notationName);
        writeRawString(">\n");
        sendIfNeeded();
    }

    // ========== External Subset Tracking ==========

    /**
     * Signals entry into the external DTD subset.
     *
     * <p>DTD declarations written between {@code startExternalSubset} and
     * {@link #endExternalSubset} are considered part of the external subset.
     * In normal (non-standalone) mode these are suppressed; in
     * {@linkplain #setStandalone(boolean) standalone} mode they are inlined
     * into the internal subset.
     * @since 1.1
     */
    public void startExternalSubset() {
        inExternalSubset = true;
    }

    /**
     * Signals exit from the external DTD subset.
     * @since 1.1
     */
    public void endExternalSubset() {
        inExternalSubset = false;
    }

    // ========== Lifecycle ==========

    /**
     * Flushes any buffered data to the channel.
     *
     * @throws IOException if there is an error sending data
     */
    public void flush() throws IOException {
        // Not the end of the character data: more of it may follow, and
        // half a surrogate pair is not yet something that can be sent.
        // Nor is a start tag finished while one of its attributes is
        // still being written.
        if (open == OPEN_NONE) {
            closeStartTag(false);
        }
        if (buffer.position() > 0) {
            send();
        }
    }

    /**
     * Flushes and closes the writer.
     * <p>
     * After calling this method, the writer should not be used again.
     * Note: This does NOT close the underlying channel - the caller is
     * responsible for closing the channel.
     *
     * @throws IOException if there is an error flushing data
     */
    public void close() throws IOException {
        if (open == OPEN_NONE) {
            endCharacters();
        }
        flush();
    }

    /** The capacity of the buffer, for tests: it is not meant to grow
     *  with what is written. */
    int bufferCapacity() {
        return buffer.capacity();
    }

    // ========== Namespace Lookup ==========

    /**
     * Gets the prefix bound to a namespace URI, or null if not bound.
     *
     * @param namespaceURI the namespace URI
     * @return the prefix, or null if not bound
     */
    public String getPrefix(String namespaceURI) {
        if (namespaceURI == null || namespaceURI.isEmpty()) {
            return null;
        }
        if ("http://www.w3.org/XML/1998/namespace".equals(namespaceURI)) {
            return "xml";
        }
        for (Map<String, String> scope : namespaceStack) {
            for (Map.Entry<String, String> entry : scope.entrySet()) {
                if (namespaceURI.equals(entry.getValue())) {
                    return entry.getKey();
                }
            }
        }
        return null;
    }

    /**
     * Gets the namespace URI bound to a prefix, or null if not bound.
     *
     * @param prefix the namespace prefix
     * @return the namespace URI, or null if not bound
     */
    public String getNamespaceURI(String prefix) {
        if (prefix == null) {
            return null;
        }
        if ("xml".equals(prefix)) {
            return "http://www.w3.org/XML/1998/namespace";
        }
        for (Map<String, String> scope : namespaceStack) {
            String uri = scope.get(prefix);
            if (uri != null) {
                return uri;
            }
        }
        return null;
    }

    /**
     * Returns all currently in-scope namespace bindings (prefix to URI).
     * Bindings in inner scopes shadow outer scopes with the same prefix.
     * The implicit xml binding is not included.
     *
     * @return map of prefix to namespace URI for all in-scope bindings
     */
    public Map<String, String> getAllNamespaceBindings() {
        Map<String, String> result = new HashMap<String, String>();
        for (Map<String, String> scope : namespaceStack) {
            for (Map.Entry<String, String> entry : scope.entrySet()) {
                if (!result.containsKey(entry.getKey())) {
                    result.put(entry.getKey(), entry.getValue());
                }
            }
        }
        return result;
    }

    // ========== Internal Helper Methods ==========

    /**
     * Determines whether a DTD declaration event should be written.
     * In standalone mode, all declarations are written. In normal mode,
     * only declarations from the internal subset are written.
     */
    private boolean shouldWriteDeclaration() {
        if (!inDTD) {
            return false;
        }
        if (standalone) {
            return true;
        }
        return !inExternalSubset;
    }

    /**
     * Lazily opens the internal subset bracket in the DOCTYPE declaration.
     */
    private void openInternalSubsetIfNeeded() throws IOException {
        if (!dtdInternalSubsetOpen) {
            writeRawString(" [\n");
            dtdInternalSubsetOpen = true;
        }
    }

    /**
     * Looks up the current in-scope default namespace URI from the stack.
     *
     * @return the default namespace URI, or null if none declared
     */
    private String getCurrentDefaultNamespace() {
        for (Map<String, String> scope : namespaceStack) {
            String defaultNs = scope.get("");
            if (defaultNs != null) {
                return defaultNs;
            }
        }
        return null;
    }

    /**
     * Looks up the in-scope namespace URI for a given prefix from the stack.
     *
     * @param prefix the namespace prefix to look up
     * @return the namespace URI, or null if not declared in any scope
     */
    private String getNamespaceURIForPrefix(String prefix) {
        for (Map<String, String> scope : namespaceStack) {
            String nsUri = scope.get(prefix);
            if (nsUri != null) {
                return nsUri;
            }
        }
        return null;
    }

    /**
     * Writes a namespace declaration as an xmlns attribute.
     * Suppresses redundant declarations when the prefix is already bound
     * to the same URI in an ancestor scope.
     */
    private void writeNamespaceDeclaration(String prefix, String uri) throws IOException {
        if (prefix == null || prefix.isEmpty()) {
            String currentDefault = getCurrentDefaultNamespace();
            if (uri == null || uri.isEmpty()) {
                if (currentDefault == null || currentDefault.isEmpty()) {
                    return;
                }
            } else {
                if (uri.equals(currentDefault)) {
                    return;
                }
            }
            writeRawString(" xmlns=\"");
        } else {
            String effectiveUri = (uri != null) ? uri : "";
            String existingUri = getNamespaceURIForPrefix(prefix);
            if (existingUri != null && existingUri.equals(effectiveUri)) {
                return;
            }
            writeRawString(" xmlns:");
            writeRawString(prefix);
            writeRawString("=\"");
        }
        writeEscapedNamespaceURI(uri);
        writeRawString("\"");
    }

    /**
     * Writes an attribute name="value" pair.
     */
    private void writeAttributeOutput(String qName, String value) throws IOException {
        writeRawString(" ");
        writeRawString(qName);
        writeRawString("=\"");
        writeEscapedAttributeValue(value);
        endAttributeValue();
        writeRawString("\"");
    }

    /**
     * Ends whatever came before something that is not character data:
     * finishes the character data, then closes a pending start tag by
     * writing the closing '&gt;'.
     *
     * @param markContent if true, mark that the element has content
     */
    private void closePendingStartTag(boolean markContent) throws IOException {
        checkNothingOpen();
        endCharacters();
        closeStartTag(markContent);
    }

    /**
     * Refuses to write anything else in the middle of an attribute value,
     * comment or processing instruction that is being written in pieces.
     */
    private void checkNothingOpen() throws IOException {
        if (open != OPEN_NONE) {
            throw unfinished();
        }
    }

    private IOException unfinished() {
        String end;
        switch (open) {
            case OPEN_ATTRIBUTE:
                end = "writeEndAttribute()";
                break;
            case OPEN_COMMENT:
                end = "writeEndComment()";
                break;
            default:
                end = "writeEndProcessingInstruction()";
                break;
        }
        return new IOException(end + " has not been called for the content being written in pieces");
    }

    /**
     * Finishes the character data written so far. A high surrogate kept
     * back from the last call was not followed by its low surrogate, so
     * it is written as it would have been in the middle of a call.
     */
    private void endCharacters() throws IOException {
        if (pendingHighSurrogate != 0) {
            char high = pendingHighSurrogate;
            pendingHighSurrogate = 0;
            if (inCDATA) {
                writeRawContentCodePoint(high, RAW_CDATA);
            } else {
                writeEscapedCodePoint(high);
            }
        }
    }

    /**
     * Closes a pending start tag by writing the closing '&gt;'.
     *
     * @param markContent if true, mark that the element has content
     */
    private void closeStartTag(boolean markContent) throws IOException {
        if (pendingStartTag) {
            writeRawString(">");
            pendingStartTag = false;
        }
        if (markContent) {
            hasContent = true;
        }
    }

    /**
     * Writes an indentation newline and spaces/tabs.
     */
    private void writeIndent() throws IOException {
        int depth = elementStack.size();
        int indentSize = indentConfig.getIndentCount() * depth;
        byte[] nlBytes = encodedAscii['\n'];
        byte[] indentBytes = encodedAscii[indentConfig.getIndentChar()];
        ensureCapacity(nlBytes.length + indentSize * indentBytes.length);
        for (int n = 0; n < nlBytes.length; n++) {
            buffer.put(nlBytes[n]);
        }
        for (int i = 0; i < indentSize; i++) {
            for (int b = 0; b < indentBytes.length; b++) {
                buffer.put(indentBytes[b]);
            }
        }
    }

    /**
     * Builds the pre-encoded byte arrays for each ASCII code point (0-127)
     * in the current charset. Called once at construction and whenever
     * the charset is changed via {@link #setCharset}.
     */
    private void initEncodedAscii() {
        encodedAscii = new byte[128][];
        for (int i = 0; i < 128; i++) {
            char[] ch = { (char) i };
            String s = new String(ch);
            encodedAscii[i] = s.getBytes(charset);
        }
        maxAsciiBytes = encodedAscii['<'].length;
        asciiCompatible = true;
        for (int i = 0; i < 128; i++) {
            if (encodedAscii[i].length != 1 || encodedAscii[i][0] != i) {
                asciiCompatible = false;
                break;
            }
        }
    }

    /**
     * Whether a character is written as itself wherever it appears:
     * printable ASCII. What a particular kind of content treats specially
     * among these, the caller excludes.
     */
    private static boolean isPlainAscii(char c) {
        return c >= 0x20 && c < 0x7F;
    }

    /**
     * Writes a run of ASCII characters as they are, a buffer's worth at
     * a time.
     *
     * @param s the sequence the run is part of
     * @param from the index of its first character
     * @param to the index after its last
     */
    private void writePlainAscii(CharSequence s, int from, int to) throws IOException {
        if (!asciiCompatible) {
            while (from < to) {
                ensureCapacity(maxAsciiBytes);
                writeAscii(s.charAt(from++));
            }
            return;
        }
        while (from < to) {
            ensureCapacity(1);
            int position = buffer.position();
            int n = Math.min(to - from, buffer.remaining());
            byte[] bytes = buffer.array();
            int offset = buffer.arrayOffset() + position;
            for (int k = 0; k < n; k++) {
                bytes[offset + k] = (byte) s.charAt(from + k);
            }
            buffer.position(position + n);
            from += n;
        }
    }

    /**
     * Writes a single ASCII-range character (0-127) using the pre-encoded
     * byte sequence for the current charset. Caller must ensure sufficient
     * buffer capacity before calling.
     */
    private void writeAscii(int c) {
        byte[] encoded = encodedAscii[c];
        int len = encoded.length;
        for (int i = 0; i < len; i++) {
            buffer.put(encoded[i]);
        }
    }

    private void emitBOMIfNeeded() throws IOException {
        if (pendingBOM != null) {
            ensureCapacity(pendingBOM.length);
            buffer.put(pendingBOM);
            pendingBOM = null;
        }
    }

    /**
     * Writes a raw string as bytes without escaping, using the configured charset.
     */
    private void writeRawString(String s) throws IOException {
        emitBOMIfNeeded();
        int len = s.length();
        if (len == 0) {
            return;
        }
        // Fast path: ASCII under UTF-8 (common for markup delimiters and names)
        if (charset == StandardCharsets.UTF_8) {
            boolean ascii = true;
            for (int i = 0; i < len; i++) {
                if (s.charAt(i) > 0x7F) {
                    ascii = false;
                    break;
                }
            }
            if (ascii) {
                ensureCapacity(len);
                for (int i = 0; i < len; i++) {
                    buffer.put((byte) s.charAt(i));
                }
                return;
            }
        }
        byte[] bytes = s.getBytes(charset);
        ensureCapacity(bytes.length);
        buffer.put(bytes);
    }

    /**
     * Writes content that is not escaped: the text of a comment, the data
     * of a processing instruction or the content of a CDATA section. A
     * high surrogate that ends it is kept back for the next call, as for
     * character data.
     *
     * @param kind one of the RAW_ constants
     */
    private void writeRawContent(CharSequence s, int kind) throws IOException {
        int end = s.length();
        if (end == 0) {
            return;
        }
        int i = 0;
        if (pendingHighSurrogate != 0) {
            char high = pendingHighSurrogate;
            pendingHighSurrogate = 0;
            char c = s.charAt(0);
            if (Character.isLowSurrogate(c)) {
                writeRawContentCodePoint(Character.toCodePoint(high, c), kind);
                i++;
            } else {
                writeRawContentCodePoint(high, kind);
            }
        }
        if (i < end && Character.isHighSurrogate(s.charAt(end - 1))) {
            pendingHighSurrogate = s.charAt(end - 1);
            end--;
        }
        // The characters that could, with another, end the construct.
        char first;
        char second;
        switch (kind) {
            case RAW_COMMENT:
                first = '-';
                second = '-';
                break;
            case RAW_PI:
                first = '?';
                second = '>';
                break;
            default:
                first = ']';
                second = '>';
                break;
        }
        while (i < end) {
            char c = s.charAt(i);
            if (isPlainAscii(c) && c != first && c != second) {
                // Most content is a run of these, with nothing to check.
                int run = i + 1;
                while (run < end) {
                    c = s.charAt(run);
                    if (!isPlainAscii(c) || c == first || c == second) {
                        break;
                    }
                    run++;
                }
                writePlainAscii(s, i, run);
                i = run;
                endsWithDelimiterStart = false;
                cdataBrackets = 0;
                continue;
            }
            int codePoint = Character.codePointAt(s, i);
            i += Character.charCount(codePoint);
            writeRawContentCodePoint(codePoint, kind);
        }
    }

    /**
     * Finishes content written with {@link #writeRawContent}: a high
     * surrogate kept back was not followed by its low surrogate.
     */
    private void endRawContent(int kind) throws IOException {
        if (pendingHighSurrogate != 0) {
            char high = pendingHighSurrogate;
            pendingHighSurrogate = 0;
            writeRawContentCodePoint(high, kind);
        }
    }

    /**
     * Writes one code point of unescaped content, keeping it from ending
     * the construct it is in. Nothing can be done about that inside a
     * CDATA section, where it is an error; in a comment or a processing
     * instruction a space is written to keep the two characters of the
     * closing delimiter apart.
     */
    private void writeRawContentCodePoint(int codePoint, int kind) throws IOException {
        switch (kind) {
            case RAW_COMMENT:
                if (codePoint == '-') {
                    if (endsWithDelimiterStart) {
                        writeRawCodePoint(' ');
                    }
                    endsWithDelimiterStart = true;
                } else {
                    endsWithDelimiterStart = false;
                }
                break;
            case RAW_PI:
                if (codePoint == '>' && endsWithDelimiterStart) {
                    writeRawCodePoint(' ');
                }
                endsWithDelimiterStart = (codePoint == '?');
                break;
            default:
                checkCDATACodePoint(codePoint);
                break;
        }
        writeRawCodePoint(codePoint);
    }

    /**
     * Checks that a code point is legal as the next one in a CDATA
     * section: that it does not complete the forbidden "]]>" delimiter,
     * and is not a character that is illegal in XML (which cannot be
     * escaped inside CDATA).
     *
     * <p>In XML 1.0, the illegal characters are U+0000 and the C0 controls
     * U+0001-U+0008, U+000B, U+000C, U+000E-U+001F (everything below U+0020
     * except TAB, LF, CR). In XML 1.1, U+0000 is illegal, and the C0 controls
     * (except TAB, LF, CR) and C1 controls U+007F-U+009F are illegal because
     * they require character references which are not available in CDATA.
     */
    private void checkCDATACodePoint(int c) throws IOException {
        if (c == '>' && cdataBrackets == 2) {
            throw new IOException("CDATA section must not contain \"]]>\"");
        }
        if (c == ']') {
            if (cdataBrackets < 2) {
                cdataBrackets++;
            }
        } else {
            cdataBrackets = 0;
        }

        if (c == 0) {
            throw new IOException("CDATA section must not contain the null character (U+0000)");
        }

        if (c < 0x20 && c != '\t' && c != '\n' && c != '\r') {
            throw new IOException("CDATA section must not contain control character (U+"
                    + Integer.toHexString(c).toUpperCase() + ")");
        }

        if (xml11 && c >= 0x7F && c <= 0x9F) {
            throw new IOException("CDATA section must not contain C1 control character (U+00"
                    + Integer.toHexString(c).toUpperCase() + ")");
        }
    }

    /**
     * Writes one code point in the configured charset with no escaping.
     * A character reference would not be recognised where this is used,
     * so what the charset cannot represent is replaced as the charset's
     * own encoder replaces it, usually with a question mark.
     */
    private void writeRawCodePoint(int codePoint) throws IOException {
        if (codePoint < 0x80) {
            ensureCapacity(maxAsciiBytes);
            writeAscii(codePoint);
        } else if (charset == StandardCharsets.UTF_8 && (codePoint < 0xD800 || codePoint > 0xDFFF)) {
            ensureCapacity(MAX_UTF8_BYTES);
            writeUtf8CodePoint(codePoint);
        } else if (charset == StandardCharsets.ISO_8859_1 && codePoint <= 0xFF) {
            ensureCapacity(1);
            buffer.put((byte) codePoint);
        } else {
            writeReplacing(codePoint);
        }
    }

    /**
     * Writes one code point through the charset's encoder, which
     * substitutes its replacement for what it cannot encode.
     */
    private void writeReplacing(int codePoint) throws IOException {
        if (replacingEncoder == null) {
            replacingEncoder = charset.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE);
            replacingIn = CharBuffer.allocate(2);
            int most = (int) Math.ceil(2 * replacingEncoder.maxBytesPerChar())
                    + replacingEncoder.replacement().length;
            replacingOut = ByteBuffer.allocate(most + 8);
        }
        replacingIn.clear();
        if (codePoint >= 0x10000) {
            replacingIn.put(Character.highSurrogate(codePoint));
            replacingIn.put(Character.lowSurrogate(codePoint));
        } else {
            replacingIn.put((char) codePoint);
        }
        replacingIn.flip();
        replacingOut.clear();
        replacingEncoder.reset();
        replacingEncoder.encode(replacingIn, replacingOut, true);
        replacingEncoder.flush(replacingOut);
        replacingOut.flip();
        ensureCapacity(replacingOut.remaining());
        buffer.put(replacingOut);
    }

    /**
     * Writes a system or public identifier as a quoted literal. No
     * reference of any kind is recognised inside one, so a quotation mark
     * in the identifier cannot be escaped: the literal is delimited with
     * the other kind instead, which also keeps the identifier from ending
     * the literal early. One containing both kinds cannot be written at
     * all (and could not have been read from a document).
     */
    private void writeIdLiteral(String id) throws IOException {
        String quote = "\"";
        if (id.indexOf('"') >= 0) {
            if (id.indexOf('\'') >= 0) {
                throw new IOException("An identifier containing both kinds of quotation mark"
                        + " cannot be written: " + id);
            }
            quote = "'";
        }
        writeRawString(quote);
        writeRawString(id);
        writeRawString(quote);
    }

    /**
     * Writes character content with XML escaping (&lt;, &gt;, &amp;).
     */
    private void writeEscapedCharacters(char[] ch, int start, int length) throws IOException {
        int end = start + length;
        int i = start;
        if (pendingHighSurrogate != 0) {
            char high = pendingHighSurrogate;
            pendingHighSurrogate = 0;
            if (Character.isLowSurrogate(ch[i])) {
                writeEscapedCodePoint(Character.toCodePoint(high, ch[i]));
                i++;
            } else {
                writeEscapedCodePoint(high);
            }
        }
        if (i < end && Character.isHighSurrogate(ch[end - 1])) {
            // Its other half, if it has one, begins the next call.
            pendingHighSurrogate = ch[end - 1];
            end--;
        }
        while (i < end) {
            // Bounded by end: what follows it in the array is not ours.
            int codePoint = Character.codePointAt(ch, i, end);
            int charCount = Character.charCount(codePoint);
            writeEscapedCodePoint(codePoint);
            i += charCount;
        }
    }

    /**
     * Writes character content with XML escaping, without copying to a char[].
     */
    private void writeEscapedCharacters(CharSequence s) throws IOException {
        int end = s.length();
        int i = 0;
        if (pendingHighSurrogate != 0) {
            char high = pendingHighSurrogate;
            pendingHighSurrogate = 0;
            if (Character.isLowSurrogate(s.charAt(0))) {
                writeEscapedCodePoint(Character.toCodePoint(high, s.charAt(0)));
                i++;
            } else {
                writeEscapedCodePoint(high);
            }
        }
        if (i < end && Character.isHighSurrogate(s.charAt(end - 1))) {
            // Its other half, if it has one, begins the next call.
            pendingHighSurrogate = s.charAt(end - 1);
            end--;
        }
        while (i < end) {
            int codePoint = Character.codePointAt(s, i);
            int charCount = Character.charCount(codePoint);
            writeEscapedCodePoint(codePoint);
            i += charCount;
        }
    }

    private void writeEscapedCodePoint(int codePoint) throws IOException {
        if (codePoint == '<') {
            writeRawString("&lt;");
        } else if (codePoint == '>') {
            writeRawString("&gt;");
        } else if (codePoint == '&') {
            writeRawString("&amp;");
        } else if (codePoint < 0x20 && codePoint != '\t' && codePoint != '\n'
                   && (codePoint != '\r' || xml11)) {
            writeCharacterReference(codePoint);
        } else if (codePoint < 0x80) {
            ensureCapacity(maxAsciiBytes);
            writeAscii(codePoint);
        } else if (xml11 && codePoint >= 0x7F && codePoint <= 0x9F) {
            writeCharacterReference(codePoint);
        } else if (xml11 && codePoint == 0x2028) {
            writeCharacterReference(codePoint);
        } else {
            writeEncodedCodePoint(codePoint);
        }
    }

    /**
     * Writes an attribute value, or the next part of one, with XML
     * escaping (&lt;, &gt;, &amp;, &quot;). A high surrogate that ends it
     * is kept back for the next part, as for character data, so the value
     * must be finished with {@link #endAttributeValue()}.
     */
    private void writeEscapedAttributeValue(CharSequence s) throws IOException {
        int end = s.length();
        if (end == 0) {
            return;
        }
        int i = 0;
        if (pendingHighSurrogate != 0) {
            char high = pendingHighSurrogate;
            pendingHighSurrogate = 0;
            char c = s.charAt(0);
            if (Character.isLowSurrogate(c)) {
                writeEscapedAttributeCodePoint(Character.toCodePoint(high, c));
                i++;
            } else {
                writeEscapedAttributeCodePoint(high);
            }
        }
        if (i < end && Character.isHighSurrogate(s.charAt(end - 1))) {
            pendingHighSurrogate = s.charAt(end - 1);
            end--;
        }
        while (i < end) {
            char c = s.charAt(i);
            if (isPlainAscii(c) && c != '<' && c != '>' && c != '&' && c != '"') {
                // Most of a value is a run of these, written as they are.
                int run = i + 1;
                while (run < end) {
                    c = s.charAt(run);
                    if (!isPlainAscii(c) || c == '<' || c == '>' || c == '&' || c == '"') {
                        break;
                    }
                    run++;
                }
                writePlainAscii(s, i, run);
                i = run;
                continue;
            }
            int codePoint = Character.codePointAt(s, i);
            i += Character.charCount(codePoint);
            writeEscapedAttributeCodePoint(codePoint);
        }
    }

    /**
     * Finishes an attribute value: a high surrogate kept back was not
     * followed by its low surrogate.
     */
    private void endAttributeValue() throws IOException {
        if (pendingHighSurrogate != 0) {
            char high = pendingHighSurrogate;
            pendingHighSurrogate = 0;
            writeEscapedAttributeCodePoint(high);
        }
    }

    private void writeEscapedAttributeCodePoint(int codePoint) throws IOException {
        if (codePoint == '<') {
            writeRawString("&lt;");
        } else if (codePoint == '>') {
            writeRawString("&gt;");
        } else if (codePoint == '&') {
            writeRawString("&amp;");
        } else if (codePoint == '"') {
            writeRawString("&quot;");
        } else if (codePoint == '\t') {
            writeDecimalCharacterReference(codePoint);
        } else if (codePoint == '\n') {
            writeDecimalCharacterReference(codePoint);
        } else if (codePoint == '\r') {
            writeDecimalCharacterReference(codePoint);
        } else if (codePoint < 0x20) {
            writeCharacterReference(codePoint);
        } else if (codePoint < 0x80) {
            ensureCapacity(maxAsciiBytes);
            writeAscii(codePoint);
        } else if (xml11 && codePoint >= 0x7F && codePoint <= 0x9F) {
            writeCharacterReference(codePoint);
        } else if (xml11 && codePoint == 0x2028) {
            writeCharacterReference(codePoint);
        } else {
            writeEncodedCodePoint(codePoint);
        }
    }

    /**
     * Writes an entity's replacement text as the content of the literal
     * that declares it.
     *
     * <p>A character that would mean something in the literal is written
     * as a character reference, which is replaced when the declaration is
     * read and so puts the character itself back into the replacement
     * text: '&amp;' (which would begin a reference), '%' (a parameter
     * entity reference), '"' (the end of the literal) and a carriage
     * return (which would be read as a line feed). A general entity
     * reference such as {@code &amp;amp;} will not do, because those are
     * not replaced in a declaration but kept, as references, in the
     * replacement text.
     */
    private void writeEscapedEntityValue(String s) throws IOException {
        for (int i = 0; i < s.length(); ) {
            int codePoint = s.codePointAt(i);
            int charCount = Character.charCount(codePoint);

            if (codePoint == '&' || codePoint == '%' || codePoint == '"' || codePoint == '\r') {
                writeDecimalCharacterReference(codePoint);
            } else if (codePoint < 0x20 && codePoint != '\t' && codePoint != '\n') {
                writeCharacterReference(codePoint);
            } else if (codePoint < 0x80) {
                ensureCapacity(maxAsciiBytes);
                writeAscii(codePoint);
            } else {
                writeEncodedCodePoint(codePoint);
            }

            i += charCount;
        }
    }

    /**
     * Writes a namespace URI value with XML 1.1 escaping.
     * In XML 1.1 mode, all non-ASCII characters are written as numeric
     * character references.
     */
    private void writeEscapedNamespaceURI(String s) throws IOException {
        for (int i = 0; i < s.length(); ) {
            int codePoint = s.codePointAt(i);
            int charCount = Character.charCount(codePoint);

            if (codePoint == '<') {
                writeRawString("&lt;");
            } else if (codePoint == '>') {
                writeRawString("&gt;");
            } else if (codePoint == '&') {
                writeRawString("&amp;");
            } else if (codePoint == '"') {
                writeRawString("&quot;");
            } else if (codePoint < 0x20 && codePoint != '\t' && codePoint != '\n'
                       && codePoint != '\r') {
                writeCharacterReference(codePoint);
            } else if (codePoint < 0x80) {
                ensureCapacity(maxAsciiBytes);
                writeAscii(codePoint);
            } else if (xml11) {
                writeHexCharacterReference(codePoint);
            } else {
                writeEncodedCodePoint(codePoint);
            }

            i += charCount;
        }
    }

    /**
     * Writes a code point using the target charset encoding.
     * If the character cannot be encoded, uses a numeric character reference.
     */
    private void writeEncodedCodePoint(int codePoint) throws IOException {
        if (charset == StandardCharsets.UTF_8) {
            ensureCapacity(MAX_UTF8_BYTES);
            writeUtf8CodePoint(codePoint);
            return;
        }

        if (charset == StandardCharsets.ISO_8859_1) {
            if (codePoint <= 0xFF) {
                ensureCapacity(1);
                buffer.put((byte) codePoint);
            } else {
                writeCharacterReference(codePoint);
            }
            return;
        }

        if (charset == StandardCharsets.US_ASCII) {
            writeCharacterReference(codePoint);
            return;
        }

        CharBuffer cb = CharBuffer.wrap(Character.toChars(codePoint));
        CharsetEncoder encoder = charset.newEncoder();
        if (encoder.canEncode(cb)) {
            ByteBuffer encoded = encoder.encode(cb);
            ensureCapacity(encoded.remaining());
            buffer.put(encoded);
        } else {
            writeCharacterReference(codePoint);
        }
    }

    /**
     * Writes a character reference in the appropriate format.
     * XML 1.1 mode uses hexadecimal, XML 1.0 mode uses decimal.
     */
    private void writeCharacterReference(int codePoint) throws IOException {
        writeDecimalCharacterReference(codePoint);
    }

    private void writeDecimalCharacterReference(int codePoint) throws IOException {
        String decimal = Integer.toString(codePoint);
        String ref = "&#" + decimal + ";";
        writeRawString(ref);
    }

    private void writeHexCharacterReference(int codePoint) throws IOException {
        String hex = Integer.toHexString(codePoint);
        String hexUpper = hex.toUpperCase();
        String ref = "&#x" + hexUpper + ";";
        writeRawString(ref);
    }

    /**
     * Writes a code point as UTF-8 bytes. At most {@link #MAX_UTF8_BYTES}:
     * the caller guarantees that much room.
     */
    private void writeUtf8CodePoint(int codePoint) {
        if (codePoint < 0x80) {
            buffer.put((byte) codePoint);
        } else if (codePoint < 0x800) {
            buffer.put((byte) (0xC0 | (codePoint >> 6)));
            buffer.put((byte) (0x80 | (codePoint & 0x3F)));
        } else if (codePoint < 0x10000) {
            buffer.put((byte) (0xE0 | (codePoint >> 12)));
            buffer.put((byte) (0x80 | ((codePoint >> 6) & 0x3F)));
            buffer.put((byte) (0x80 | (codePoint & 0x3F)));
        } else {
            buffer.put((byte) (0xF0 | (codePoint >> 18)));
            buffer.put((byte) (0x80 | ((codePoint >> 12) & 0x3F)));
            buffer.put((byte) (0x80 | ((codePoint >> 6) & 0x3F)));
            buffer.put((byte) (0x80 | (codePoint & 0x3F)));
        }
    }

    /**
     * Makes sure there is room in the buffer for that many bytes. What is
     * already in it is sent on to make the room, so the buffer does not
     * grow with what is written; it is only replaced by a larger one for
     * a single item that would not fit in it empty, such as a long name.
     */
    private void ensureCapacity(int needed) throws IOException {
        if (buffer.remaining() < needed) {
            if (buffer.position() > 0) {
                send();
            }
            if (buffer.capacity() < needed) {
                buffer = ByteBuffer.allocate(Math.max(buffer.capacity() * 2, needed));
            }
        }
    }

    private void sendIfNeeded() throws IOException {
        if (buffer.position() >= sendThreshold) {
            send();
        }
    }

    private void send() throws IOException {
        buffer.flip();
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
        buffer.clear();
    }

    /**
     * Adapter that wraps an OutputStream as a WritableByteChannel.
     */
    static class OutputStreamChannel implements WritableByteChannel {

        private final OutputStream out;
        private boolean open = true;

        OutputStreamChannel(OutputStream out) {
            this.out = out;
        }

        @Override
        public int write(ByteBuffer src) throws IOException {
            if (!open) {
                throw new IOException("Channel is closed");
            }
            int written = src.remaining();
            if (src.hasArray()) {
                out.write(src.array(), src.arrayOffset() + src.position(), written);
                src.position(src.limit());
            } else {
                while (src.hasRemaining()) {
                    out.write(src.get());
                }
            }
            return written;
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void close() throws IOException {
            if (open) {
                open = false;
                out.close();
            }
        }
    }
}

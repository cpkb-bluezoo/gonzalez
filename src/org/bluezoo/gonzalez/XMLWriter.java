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
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
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

    private final WritableByteChannel channel;
    private ByteBuffer buffer;
    private final int sendThreshold;

    // Configuration (set before writing begins)
    private IndentConfig indentConfig;
    private Charset charset;
    private boolean xml11;
    private boolean standalone;

    // Element stack for tracking open elements
    private final Deque<ElementInfo> elementStack = new ArrayDeque<ElementInfo>();

    // Namespace context: maps prefix -> URI at current scope
    private final Deque<Map<String, String>> namespaceStack = new ArrayDeque<Map<String, String>>();

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
        namespaceStack.push(new HashMap<String, String>());
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

    /**
     * Sets the output character encoding.
     * Should be called before writing begins.
     *
     * @param charset the character encoding, or null for UTF-8
     * @since 1.1
     */
    public void setCharset(Charset charset) {
        this.charset = charset != null ? charset : StandardCharsets.UTF_8;
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

        ensureCapacity(1);
        buffer.put((byte) '<');
        writeRawString(localName);

        namespaceStack.push(new HashMap<String, String>());
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

        ensureCapacity(1);
        buffer.put((byte) '<');
        writeRawString(qName);

        namespaceStack.push(new HashMap<String, String>());
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

        ensureCapacity(1);
        buffer.put((byte) '<');
        writeRawString(qName);

        namespaceStack.push(new HashMap<String, String>());
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
        if (elementStack.isEmpty()) {
            throw new IOException("No open element to close");
        }

        ElementInfo element = elementStack.pop();
        namespaceStack.pop();

        if (pendingStartTag && !hasContent) {
            ensureCapacity(2);
            buffer.put((byte) '/');
            buffer.put((byte) '>');
            pendingStartTag = false;
        } else {
            closePendingStartTag(false);

            if (indentConfig != null && hasNestedElements) {
                writeIndent();
            }

            ensureCapacity(2);
            buffer.put((byte) '<');
            buffer.put((byte) '/');
            writeRawString(element.qName);
            ensureCapacity(1);
            buffer.put((byte) '>');
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

        String qName;
        if (prefix != null && !prefix.isEmpty()) {
            qName = prefix + ":" + localName;
        } else {
            qName = localName;
        }

        writeAttributeOutput(qName, value);
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
        if (prefix == null || prefix.isEmpty()) {
            writeDefaultNamespace(namespaceURI);
            return;
        }
        writeNamespaceDeclaration(prefix, namespaceURI);
        namespaceStack.peek().put(prefix, namespaceURI != null ? namespaceURI : "");
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
        writeNamespaceDeclaration("", namespaceURI);
        namespaceStack.peek().put("", namespaceURI != null ? namespaceURI : "");
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
        if (text == null || text.isEmpty()) {
            return;
        }
        char[] ch = text.toCharArray();
        writeCharacters(ch, 0, ch.length);
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
        closePendingStartTag(true);
        if (inCDATA) {
            validateCDATAContent(ch, start, length);
            writeRawChars(ch, start, length);
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
        ensureCapacity(9);
        buffer.put((byte) '<');
        buffer.put((byte) '!');
        buffer.put((byte) '[');
        buffer.put((byte) 'C');
        buffer.put((byte) 'D');
        buffer.put((byte) 'A');
        buffer.put((byte) 'T');
        buffer.put((byte) 'A');
        buffer.put((byte) '[');
        sendIfNeeded();
    }

    /**
     * Closes a CDATA section previously opened with {@link #writeStartCDATA()}.
     *
     * @throws IOException if there is an error writing data
     * @since 1.1
     */
    public void writeEndCDATA() throws IOException {
        inCDATA = false;
        ensureCapacity(3);
        buffer.put((byte) ']');
        buffer.put((byte) ']');
        buffer.put((byte) '>');
        sendIfNeeded();
    }

    /**
     * Writes a comment.
     *
     * @param text the comment text
     * @throws IOException if there is an error writing data
     */
    public void writeComment(String text) throws IOException {
        if (inDTD && !shouldWriteDeclaration()) {
            return;
        }

        closePendingStartTag(true);

        if (inDTD) {
            openInternalSubsetIfNeeded();
        }

        if (indentConfig != null && !atDocumentStart && !inDTD) {
            writeIndent();
        }
        atDocumentStart = false;

        ensureCapacity(4);
        buffer.put((byte) '<');
        buffer.put((byte) '!');
        buffer.put((byte) '-');
        buffer.put((byte) '-');
        char[] ch = text.toCharArray();
        writeCommentContent(ch, 0, ch.length);
        ensureCapacity(3);
        buffer.put((byte) '-');
        buffer.put((byte) '-');
        buffer.put((byte) '>');

        if (inDTD) {
            ensureCapacity(1);
            buffer.put((byte) '\n');
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
        closePendingStartTag(true);

        if (indentConfig != null && !atDocumentStart) {
            writeIndent();
        }
        atDocumentStart = false;

        ensureCapacity(2);
        buffer.put((byte) '<');
        buffer.put((byte) '?');
        writeRawString(target);
        if (data != null && !data.isEmpty()) {
            ensureCapacity(1);
            buffer.put((byte) ' ');
            writePIData(data);
        }
        ensureCapacity(2);
        buffer.put((byte) '?');
        buffer.put((byte) '>');

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
        ensureCapacity(2 + name.length());
        buffer.put((byte) '&');
        writeRawString(name);
        buffer.put((byte) ';');
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

        ensureCapacity(10);
        buffer.put((byte) '<');
        buffer.put((byte) '!');
        buffer.put((byte) 'D');
        buffer.put((byte) 'O');
        buffer.put((byte) 'C');
        buffer.put((byte) 'T');
        buffer.put((byte) 'Y');
        buffer.put((byte) 'P');
        buffer.put((byte) 'E');
        buffer.put((byte) ' ');
        writeRawString(name);

        if (!standalone) {
            if (publicId != null) {
                writeRawString(" PUBLIC \"");
                writeEscapedId(publicId);
                writeRawString("\" \"");
                if (systemId != null) {
                    writeEscapedId(systemId);
                }
                ensureCapacity(1);
                buffer.put((byte) '"');
            } else if (systemId != null) {
                writeRawString(" SYSTEM \"");
                writeEscapedId(systemId);
                ensureCapacity(1);
                buffer.put((byte) '"');
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
            ensureCapacity(2);
            buffer.put((byte) ']');
            buffer.put((byte) '>');
        } else {
            ensureCapacity(1);
            buffer.put((byte) '>');
        }
        if (indentConfig != null) {
            ensureCapacity(1);
            buffer.put((byte) '\n');
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
        ensureCapacity(1);
        buffer.put((byte) ' ');
        writeRawString(model);
        ensureCapacity(2);
        buffer.put((byte) '>');
        buffer.put((byte) '\n');
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
        ensureCapacity(1);
        buffer.put((byte) ' ');
        writeRawString(aName);
        ensureCapacity(1);
        buffer.put((byte) ' ');
        writeRawString(type);
        if (mode != null && !mode.isEmpty()) {
            ensureCapacity(1);
            buffer.put((byte) ' ');
            writeRawString(mode);
        }
        if (value != null) {
            writeRawString(" \"");
            writeEscapedAttributeValue(value);
            ensureCapacity(1);
            buffer.put((byte) '"');
        }
        ensureCapacity(2);
        buffer.put((byte) '>');
        buffer.put((byte) '\n');
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
        ensureCapacity(3);
        buffer.put((byte) '"');
        buffer.put((byte) '>');
        buffer.put((byte) '\n');
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
            writeRawString(" PUBLIC \"");
            writeEscapedId(publicId);
            writeRawString("\" \"");
            writeEscapedId(systemId);
            ensureCapacity(1);
            buffer.put((byte) '"');
        } else {
            writeRawString(" SYSTEM \"");
            writeEscapedId(systemId);
            ensureCapacity(1);
            buffer.put((byte) '"');
        }
        ensureCapacity(2);
        buffer.put((byte) '>');
        buffer.put((byte) '\n');
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
            writeRawString(" PUBLIC \"");
            writeEscapedId(publicId);
            ensureCapacity(1);
            buffer.put((byte) '"');
            if (systemId != null) {
                writeRawString(" \"");
                writeEscapedId(systemId);
                ensureCapacity(1);
                buffer.put((byte) '"');
            }
        } else if (systemId != null) {
            writeRawString(" SYSTEM \"");
            writeEscapedId(systemId);
            ensureCapacity(1);
            buffer.put((byte) '"');
        }
        ensureCapacity(2);
        buffer.put((byte) '>');
        buffer.put((byte) '\n');
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
            writeRawString(" PUBLIC \"");
            writeEscapedId(publicId);
            writeRawString("\" \"");
            writeEscapedId(systemId);
            ensureCapacity(1);
            buffer.put((byte) '"');
        } else {
            writeRawString(" SYSTEM \"");
            writeEscapedId(systemId);
            ensureCapacity(1);
            buffer.put((byte) '"');
        }
        writeRawString(" NDATA ");
        writeRawString(notationName);
        ensureCapacity(2);
        buffer.put((byte) '>');
        buffer.put((byte) '\n');
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
        closePendingStartTag(false);
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
        flush();
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
            ensureCapacity(2);
            buffer.put((byte) '=');
            buffer.put((byte) '"');
        }
        writeEscapedNamespaceURI(uri);
        ensureCapacity(1);
        buffer.put((byte) '"');
    }

    /**
     * Writes an attribute name="value" pair.
     */
    private void writeAttributeOutput(String qName, String value) throws IOException {
        ensureCapacity(1);
        buffer.put((byte) ' ');
        writeRawString(qName);
        ensureCapacity(2);
        buffer.put((byte) '=');
        buffer.put((byte) '"');
        writeEscapedAttributeValue(value);
        ensureCapacity(1);
        buffer.put((byte) '"');
    }

    /**
     * Closes a pending start tag by writing the closing '&gt;'.
     *
     * @param markContent if true, mark that the element has content
     */
    private void closePendingStartTag(boolean markContent) throws IOException {
        if (pendingStartTag) {
            ensureCapacity(1);
            buffer.put((byte) '>');
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
        ensureCapacity(1 + indentSize);
        buffer.put((byte) '\n');
        byte indentByte = (byte) indentConfig.getIndentChar();
        for (int i = 0; i < indentSize; i++) {
            buffer.put(indentByte);
        }
    }

    /**
     * Writes a raw string as bytes without escaping, using the configured charset.
     */
    private void writeRawString(String s) throws IOException {
        byte[] bytes = s.getBytes(charset);
        ensureCapacity(bytes.length);
        buffer.put(bytes);
    }

    /**
     * Validates that character content is legal inside a CDATA section.
     * Checks for the forbidden "]]>" delimiter and for characters that are
     * illegal in XML (which cannot be escaped inside CDATA).
     *
     * <p>In XML 1.0, the illegal characters are U+0000 and the C0 controls
     * U+0001-U+0008, U+000B, U+000C, U+000E-U+001F (everything below U+0020
     * except TAB, LF, CR). In XML 1.1, U+0000 is illegal, and the C0 controls
     * (except TAB, LF, CR) and C1 controls U+007F-U+009F are illegal because
     * they require character references which are not available in CDATA.
     */
    private void validateCDATAContent(char[] ch, int start, int length) throws IOException {
        int end = start + length;
        for (int i = start; i < end; i++) {
            char c = ch[i];

            if (c == ']' && i + 2 < end && ch[i + 1] == ']' && ch[i + 2] == '>') {
                throw new IOException("CDATA section must not contain \"]]>\"");
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
    }

    /**
     * Writes raw characters without escaping, using the configured charset.
     */
    private void writeRawChars(char[] ch, int start, int length) throws IOException {
        String s = new String(ch, start, length);
        byte[] bytes = s.getBytes(charset);
        ensureCapacity(bytes.length);
        buffer.put(bytes);
    }

    /**
     * Writes comment content, sanitizing "--" sequences and trailing "-"
     * to prevent comment injection (producing malformed output like "-->").
     */
    private void writeCommentContent(char[] ch, int start, int length) throws IOException {
        int end = start + length;
        int segStart = start;
        for (int i = start; i < end; i++) {
            if (ch[i] == '-' && i + 1 < end && ch[i + 1] == '-') {
                writeRawChars(ch, segStart, i - segStart + 1);
                writeRawString(" ");
                segStart = i + 1;
            }
        }
        writeRawChars(ch, segStart, end - segStart);
        if (length > 0 && ch[end - 1] == '-') {
            writeRawString(" ");
        }
    }

    /**
     * Writes processing instruction data, sanitizing "?>" sequences
     * to prevent PI injection (premature close of PI).
     */
    private void writePIData(String data) throws IOException {
        int len = data.length();
        int segStart = 0;
        for (int i = 0; i < len - 1; i++) {
            if (data.charAt(i) == '?' && data.charAt(i + 1) == '>') {
                writeRawString(data.substring(segStart, i + 1));
                writeRawString(" ");
                segStart = i + 1;
            }
        }
        writeRawString(data.substring(segStart));
    }

    /**
     * Writes a system/public identifier, escaping double quotes
     * to prevent identifier injection in DOCTYPE and entity declarations.
     */
    private void writeEscapedId(String id) throws IOException {
        int len = id.length();
        int segStart = 0;
        for (int i = 0; i < len; i++) {
            if (id.charAt(i) == '"') {
                writeRawString(id.substring(segStart, i));
                writeRawString("&quot;");
                segStart = i + 1;
            }
        }
        writeRawString(id.substring(segStart));
    }

    /**
     * Writes character content with XML escaping (&lt;, &gt;, &amp;).
     */
    private void writeEscapedCharacters(char[] ch, int start, int length) throws IOException {
        int end = start + length;
        for (int i = start; i < end; ) {
            int codePoint = Character.codePointAt(ch, i);
            int charCount = Character.charCount(codePoint);

            if (buffer.remaining() < 12) {
                growBuffer(buffer.capacity() * 2);
            }

            if (codePoint == '<') {
                buffer.put((byte) '&');
                buffer.put((byte) 'l');
                buffer.put((byte) 't');
                buffer.put((byte) ';');
            } else if (codePoint == '>') {
                buffer.put((byte) '&');
                buffer.put((byte) 'g');
                buffer.put((byte) 't');
                buffer.put((byte) ';');
            } else if (codePoint == '&') {
                buffer.put((byte) '&');
                buffer.put((byte) 'a');
                buffer.put((byte) 'm');
                buffer.put((byte) 'p');
                buffer.put((byte) ';');
            } else if (codePoint < 0x20 && codePoint != '\t' && codePoint != '\n'
                       && (codePoint != '\r' || xml11)) {
                writeCharacterReference(codePoint);
            } else if (codePoint < 0x80) {
                buffer.put((byte) codePoint);
            } else if (xml11 && codePoint >= 0x7F && codePoint <= 0x9F) {
                writeCharacterReference(codePoint);
            } else if (xml11 && codePoint == 0x2028) {
                writeCharacterReference(codePoint);
            } else {
                writeEncodedCodePoint(codePoint);
            }

            i += charCount;
        }
    }

    /**
     * Writes an attribute value with XML escaping (&lt;, &gt;, &amp;, &quot;).
     */
    private void writeEscapedAttributeValue(String s) throws IOException {
        for (int i = 0; i < s.length(); ) {
            int codePoint = s.codePointAt(i);
            int charCount = Character.charCount(codePoint);

            if (buffer.remaining() < 12) {
                growBuffer(buffer.capacity() * 2);
            }

            if (codePoint == '<') {
                buffer.put((byte) '&');
                buffer.put((byte) 'l');
                buffer.put((byte) 't');
                buffer.put((byte) ';');
            } else if (codePoint == '>') {
                buffer.put((byte) '&');
                buffer.put((byte) 'g');
                buffer.put((byte) 't');
                buffer.put((byte) ';');
            } else if (codePoint == '&') {
                buffer.put((byte) '&');
                buffer.put((byte) 'a');
                buffer.put((byte) 'm');
                buffer.put((byte) 'p');
                buffer.put((byte) ';');
            } else if (codePoint == '"') {
                buffer.put((byte) '&');
                buffer.put((byte) 'q');
                buffer.put((byte) 'u');
                buffer.put((byte) 'o');
                buffer.put((byte) 't');
                buffer.put((byte) ';');
            } else if (codePoint == '\t') {
                writeDecimalCharacterReference(codePoint);
            } else if (codePoint == '\n') {
                writeDecimalCharacterReference(codePoint);
            } else if (codePoint == '\r') {
                writeDecimalCharacterReference(codePoint);
            } else if (codePoint < 0x20) {
                writeCharacterReference(codePoint);
            } else if (codePoint < 0x80) {
                buffer.put((byte) codePoint);
            } else if (xml11 && codePoint >= 0x7F && codePoint <= 0x9F) {
                writeCharacterReference(codePoint);
            } else if (xml11 && codePoint == 0x2028) {
                writeCharacterReference(codePoint);
            } else {
                writeEncodedCodePoint(codePoint);
            }

            i += charCount;
        }
    }

    /**
     * Writes an entity replacement value with escaping for DTD internal subset.
     * Escapes &amp;, %, and " characters.
     */
    private void writeEscapedEntityValue(String s) throws IOException {
        for (int i = 0; i < s.length(); ) {
            int codePoint = s.codePointAt(i);
            int charCount = Character.charCount(codePoint);

            if (buffer.remaining() < 12) {
                growBuffer(buffer.capacity() * 2);
            }

            if (codePoint == '&') {
                buffer.put((byte) '&');
                buffer.put((byte) 'a');
                buffer.put((byte) 'm');
                buffer.put((byte) 'p');
                buffer.put((byte) ';');
            } else if (codePoint == '%') {
                buffer.put((byte) '&');
                buffer.put((byte) '#');
                buffer.put((byte) '3');
                buffer.put((byte) '7');
                buffer.put((byte) ';');
            } else if (codePoint == '"') {
                buffer.put((byte) '&');
                buffer.put((byte) 'q');
                buffer.put((byte) 'u');
                buffer.put((byte) 'o');
                buffer.put((byte) 't');
                buffer.put((byte) ';');
            } else if (codePoint < 0x20 && codePoint != '\t' && codePoint != '\n'
                       && codePoint != '\r') {
                writeCharacterReference(codePoint);
            } else if (codePoint < 0x80) {
                buffer.put((byte) codePoint);
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

            if (buffer.remaining() < 12) {
                growBuffer(buffer.capacity() * 2);
            }

            if (codePoint == '<') {
                buffer.put((byte) '&');
                buffer.put((byte) 'l');
                buffer.put((byte) 't');
                buffer.put((byte) ';');
            } else if (codePoint == '>') {
                buffer.put((byte) '&');
                buffer.put((byte) 'g');
                buffer.put((byte) 't');
                buffer.put((byte) ';');
            } else if (codePoint == '&') {
                buffer.put((byte) '&');
                buffer.put((byte) 'a');
                buffer.put((byte) 'm');
                buffer.put((byte) 'p');
                buffer.put((byte) ';');
            } else if (codePoint == '"') {
                buffer.put((byte) '&');
                buffer.put((byte) 'q');
                buffer.put((byte) 'u');
                buffer.put((byte) 'o');
                buffer.put((byte) 't');
                buffer.put((byte) ';');
            } else if (codePoint < 0x20 && codePoint != '\t' && codePoint != '\n'
                       && codePoint != '\r') {
                writeCharacterReference(codePoint);
            } else if (codePoint < 0x80) {
                buffer.put((byte) codePoint);
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
            writeUtf8CodePoint(codePoint);
            return;
        }

        if (charset == StandardCharsets.ISO_8859_1) {
            if (codePoint <= 0xFF) {
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
            while (encoded.hasRemaining()) {
                buffer.put(encoded.get());
            }
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
        ensureCapacity(12);
        buffer.put((byte) '&');
        buffer.put((byte) '#');
        String decimal = Integer.toString(codePoint);
        for (int i = 0; i < decimal.length(); i++) {
            buffer.put((byte) decimal.charAt(i));
        }
        buffer.put((byte) ';');
    }

    private void writeHexCharacterReference(int codePoint) throws IOException {
        ensureCapacity(12);
        buffer.put((byte) '&');
        buffer.put((byte) '#');
        buffer.put((byte) 'x');
        String hex = Integer.toHexString(codePoint).toUpperCase();
        for (int i = 0; i < hex.length(); i++) {
            buffer.put((byte) hex.charAt(i));
        }
        buffer.put((byte) ';');
    }

    /**
     * Writes a code point as UTF-8 bytes.
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

    private void ensureCapacity(int needed) {
        if (buffer.remaining() < needed) {
            growBuffer(Math.max(buffer.capacity() * 2, buffer.position() + needed));
        }
    }

    private void growBuffer(int newCapacity) {
        ByteBuffer newBuffer = ByteBuffer.allocate(newCapacity);
        buffer.flip();
        newBuffer.put(buffer);
        buffer = newBuffer;
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

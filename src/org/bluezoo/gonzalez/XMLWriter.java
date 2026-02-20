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
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.xml.sax.Attributes;
import org.xml.sax.ContentHandler;
import org.xml.sax.DTDHandler;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.ext.DeclHandler;
import org.xml.sax.ext.LexicalHandler;

/**
 * SAX2 event serializer that writes XML to a {@link WritableByteChannel}.
 * <p>
 * This class implements the SAX2 {@link ContentHandler}, {@link LexicalHandler},
 * {@link DTDHandler}, and {@link DeclHandler} interfaces, allowing it to be
 * wired directly into a SAX pipeline as an event sink. It writes the received
 * events as well-formed XML to the configured byte channel.
 * <p>
 * The writer uses an internal buffer and automatically sends chunks to the
 * channel when the buffer fills beyond a threshold. It supports full namespace
 * handling, pretty-print indentation, and automatic empty element optimization
 * (emitting {@code <foo/>} instead of {@code <foo></foo>} when an element has
 * no content).
 * <p>
 * Configuration is set via setter methods that must be called before
 * {@link #startDocument()}:
 * <ul>
 *   <li>{@link #setIndentConfig(IndentConfig)} - optional indentation</li>
 *   <li>{@link #setCharset(Charset)} - output encoding (default UTF-8)</li>
 *   <li>{@link #setXml11(boolean)} - XML 1.1 output mode</li>
 *   <li>{@link #setStandalone(boolean)} - standalone DOCTYPE conversion mode</li>
 * </ul>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * // Wire parser directly to writer
 * Parser parser = new Parser();
 * FileChannel channel = FileChannel.open(path, WRITE, CREATE);
 * XMLWriter writer = new XMLWriter(channel);
 *
 * parser.setContentHandler(writer);
 * parser.setProperty("http://xml.org/sax/properties/lexical-handler", writer);
 * parser.parse(inputSource);
 * writer.close();
 * }</pre>
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
public class XMLWriter
        implements ContentHandler, LexicalHandler, DTDHandler, DeclHandler {

    private static final int DEFAULT_CAPACITY = 4096;
    private static final float SEND_THRESHOLD = 0.75f;

    private final WritableByteChannel channel;
    private ByteBuffer buffer;
    private final int sendThreshold;

    // Configuration (set before startDocument)
    private IndentConfig indentConfig;
    private Charset charset;
    private boolean xml11;
    private boolean standalone;

    // Source locator for enriching error messages
    private Locator locator;

    // Element stack for tracking open elements
    private final Deque<ElementInfo> elementStack = new ArrayDeque<>();

    // Namespace context: maps prefix -> URI at current scope
    private final Deque<Map<String, String>> namespaceStack = new ArrayDeque<>();

    // Buffered prefix mappings to emit as xmlns attributes in the next startElement
    private final List<PrefixMapping> pendingPrefixMappings = new ArrayList<>();

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
     * A buffered prefix mapping.
     */
    private static class PrefixMapping {
        final String prefix;
        final String uri;

        PrefixMapping(String prefix, String uri) {
            this.prefix = prefix;
            this.uri = uri;
        }
    }

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

    // ========== Configuration Setters ==========

    /**
     * Sets the indentation configuration.
     * Must be called before {@link #startDocument()}.
     *
     * @param indentConfig the indentation configuration, or null for no indentation
     */
    public void setIndentConfig(IndentConfig indentConfig) {
        this.indentConfig = indentConfig;
    }

    /**
     * Sets the output character encoding.
     * Must be called before {@link #startDocument()}.
     *
     * @param charset the character encoding, or null for UTF-8
     */
    public void setCharset(Charset charset) {
        this.charset = charset != null ? charset : StandardCharsets.UTF_8;
    }

    /**
     * Sets XML 1.1 output mode. When enabled, CR characters and C1 control
     * characters are escaped as character references.
     * Must be called before {@link #startDocument()}.
     *
     * @param xml11 true for XML 1.1 mode
     */
    public void setXml11(boolean xml11) {
        this.xml11 = xml11;
    }

    /**
     * Sets standalone DOCTYPE conversion mode. When enabled, external DOCTYPE
     * identifiers (SYSTEM/PUBLIC) are omitted and all DTD declarations are
     * inlined into the internal subset, producing a self-contained document.
     * Must be called before {@link #startDocument()}.
     *
     * @param standalone true for standalone conversion mode
     */
    public void setStandalone(boolean standalone) {
        this.standalone = standalone;
    }

    // ========== ContentHandler ==========

    @Override
    public void setDocumentLocator(Locator locator) {
        this.locator = locator;
    }

    @Override
    public void startDocument() throws SAXException {
        // No output -- the XML declaration is only written if explicitly
        // requested via a processing instruction or configuration
    }

    @Override
    public void endDocument() throws SAXException {
        try {
            flush();
        } catch (IOException e) {
            throw wrapIOException(e);
        }
    }

    @Override
    public void startPrefixMapping(String prefix, String uri) throws SAXException {
        pendingPrefixMappings.add(new PrefixMapping(prefix, uri));
    }

    @Override
    public void endPrefixMapping(String prefix) throws SAXException {
        // Namespace scoping is handled by the element stack
    }

    @Override
    public void startElement(String uri, String localName, String qName, Attributes atts)
            throws SAXException {
        try {
            closePendingStartTag(false);

            if (indentConfig != null && !atDocumentStart) {
                writeIndent();
            }
            atDocumentStart = false;

            ensureCapacity(1);
            buffer.put((byte) '<');

            String effectiveQName = (qName != null && !qName.isEmpty()) ? qName : localName;
            writeRawString(effectiveQName);

            // Push new namespace scope
            namespaceStack.push(new HashMap<String, String>());

            // Write buffered namespace declarations
            for (int i = 0; i < pendingPrefixMappings.size(); i++) {
                PrefixMapping pm = pendingPrefixMappings.get(i);
                writeNamespaceDeclaration(pm.prefix, pm.uri);
                namespaceStack.peek().put(
                    pm.prefix != null ? pm.prefix : "", pm.uri);
            }
            pendingPrefixMappings.clear();

            // Write attributes
            if (atts != null) {
                int len = atts.getLength();
                for (int i = 0; i < len; i++) {
                    String attQName = atts.getQName(i);
                    if (attQName == null || attQName.isEmpty()) {
                        String attLocal = atts.getLocalName(i);
                        String attPrefix = "";
                        String attUri = atts.getURI(i);
                        if (attUri != null && !attUri.isEmpty()) {
                            attPrefix = getPrefix(attUri);
                            if (attPrefix == null) {
                                attPrefix = "";
                            }
                        }
                        if (attPrefix.isEmpty()) {
                            attQName = attLocal;
                        } else {
                            attQName = attPrefix + ":" + attLocal;
                        }
                    }
                    String attValue = atts.getValue(i);
                    writeAttributeOutput(attQName, attValue);
                }
            }

            elementStack.push(new ElementInfo(effectiveQName, hasContent, hasNestedElements));

            pendingStartTag = true;
            hasContent = false;
            hasNestedElements = false;

            sendIfNeeded();
        } catch (IOException e) {
            throw wrapIOException(e);
        }
    }

    @Override
    public void endElement(String uri, String localName, String qName) throws SAXException {
        try {
            if (elementStack.isEmpty()) {
                throw new SAXException("No open element to close");
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
            // Parent now has content (nested element)
            hasContent = true;
            hasNestedElements = true;

            sendIfNeeded();
        } catch (IOException e) {
            throw wrapIOException(e);
        }
    }

    @Override
    public void characters(char[] ch, int start, int length) throws SAXException {
        try {
            if (length == 0) {
                return;
            }
            closePendingStartTag(true);
            if (inCDATA) {
                writeRawChars(ch, start, length);
            } else {
                writeEscapedCharacters(ch, start, length);
            }
            sendIfNeeded();
        } catch (IOException e) {
            throw wrapIOException(e);
        }
    }

    @Override
    public void ignorableWhitespace(char[] ch, int start, int length) throws SAXException {
        characters(ch, start, length);
    }

    @Override
    public void processingInstruction(String target, String data) throws SAXException {
        try {
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
                writeRawString(data);
            }
            ensureCapacity(2);
            buffer.put((byte) '?');
            buffer.put((byte) '>');

            sendIfNeeded();
        } catch (IOException e) {
            throw wrapIOException(e);
        }
    }

    @Override
    public void skippedEntity(String name) throws SAXException {
        try {
            closePendingStartTag(true);
            ensureCapacity(2 + name.length());
            buffer.put((byte) '&');
            writeRawString(name);
            buffer.put((byte) ';');
            sendIfNeeded();
        } catch (IOException e) {
            throw wrapIOException(e);
        }
    }

    // ========== LexicalHandler ==========

    @Override
    public void comment(char[] ch, int start, int length) throws SAXException {
        try {
            // In DTD mode, apply internal/external subset filtering
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
            writeRawChars(ch, start, length);
            ensureCapacity(3);
            buffer.put((byte) '-');
            buffer.put((byte) '-');
            buffer.put((byte) '>');

            if (inDTD) {
                ensureCapacity(1);
                buffer.put((byte) '\n');
            }

            sendIfNeeded();
        } catch (IOException e) {
            throw wrapIOException(e);
        }
    }

    @Override
    public void startCDATA() throws SAXException {
        try {
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
        } catch (IOException e) {
            throw wrapIOException(e);
        }
    }

    @Override
    public void endCDATA() throws SAXException {
        try {
            inCDATA = false;
            ensureCapacity(3);
            buffer.put((byte) ']');
            buffer.put((byte) ']');
            buffer.put((byte) '>');
            sendIfNeeded();
        } catch (IOException e) {
            throw wrapIOException(e);
        }
    }

    @Override
    public void startDTD(String name, String publicId, String systemId) throws SAXException {
        try {
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
                    writeRawString(publicId);
                    writeRawString("\" \"");
                    if (systemId != null) {
                        writeRawString(systemId);
                    }
                    ensureCapacity(1);
                    buffer.put((byte) '"');
                } else if (systemId != null) {
                    writeRawString(" SYSTEM \"");
                    writeRawString(systemId);
                    ensureCapacity(1);
                    buffer.put((byte) '"');
                }
            }

            sendIfNeeded();
        } catch (IOException e) {
            throw wrapIOException(e);
        }
    }

    @Override
    public void endDTD() throws SAXException {
        try {
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
        } catch (IOException e) {
            throw wrapIOException(e);
        }
    }

    @Override
    public void startEntity(String name) throws SAXException {
        if ("[dtd]".equals(name)) {
            inExternalSubset = true;
        }
    }

    @Override
    public void endEntity(String name) throws SAXException {
        if ("[dtd]".equals(name)) {
            inExternalSubset = false;
        }
    }

    // ========== DTDHandler ==========

    @Override
    public void notationDecl(String name, String publicId, String systemId)
            throws SAXException {
        try {
            if (!shouldWriteDeclaration()) {
                return;
            }
            openInternalSubsetIfNeeded();

            writeRawString("  <!NOTATION ");
            writeRawString(name);
            if (publicId != null) {
                writeRawString(" PUBLIC \"");
                writeRawString(publicId);
                ensureCapacity(1);
                buffer.put((byte) '"');
                if (systemId != null) {
                    writeRawString(" \"");
                    writeRawString(systemId);
                    ensureCapacity(1);
                    buffer.put((byte) '"');
                }
            } else if (systemId != null) {
                writeRawString(" SYSTEM \"");
                writeRawString(systemId);
                ensureCapacity(1);
                buffer.put((byte) '"');
            }
            ensureCapacity(2);
            buffer.put((byte) '>');
            buffer.put((byte) '\n');
            sendIfNeeded();
        } catch (IOException e) {
            throw wrapIOException(e);
        }
    }

    @Override
    public void unparsedEntityDecl(String name, String publicId,
            String systemId, String notationName) throws SAXException {
        try {
            if (!shouldWriteDeclaration()) {
                return;
            }
            openInternalSubsetIfNeeded();

            writeRawString("  <!ENTITY ");
            writeRawString(name);
            if (publicId != null) {
                writeRawString(" PUBLIC \"");
                writeRawString(publicId);
                writeRawString("\" \"");
                writeRawString(systemId);
                ensureCapacity(1);
                buffer.put((byte) '"');
            } else {
                writeRawString(" SYSTEM \"");
                writeRawString(systemId);
                ensureCapacity(1);
                buffer.put((byte) '"');
            }
            writeRawString(" NDATA ");
            writeRawString(notationName);
            ensureCapacity(2);
            buffer.put((byte) '>');
            buffer.put((byte) '\n');
            sendIfNeeded();
        } catch (IOException e) {
            throw wrapIOException(e);
        }
    }

    // ========== DeclHandler ==========

    @Override
    public void elementDecl(String name, String model) throws SAXException {
        try {
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
        } catch (IOException e) {
            throw wrapIOException(e);
        }
    }

    @Override
    public void attributeDecl(String eName, String aName, String type,
            String mode, String value) throws SAXException {
        try {
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
        } catch (IOException e) {
            throw wrapIOException(e);
        }
    }

    @Override
    public void internalEntityDecl(String name, String value) throws SAXException {
        try {
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
        } catch (IOException e) {
            throw wrapIOException(e);
        }
    }

    @Override
    public void externalEntityDecl(String name, String publicId, String systemId)
            throws SAXException {
        try {
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
                writeRawString(publicId);
                writeRawString("\" \"");
                writeRawString(systemId);
                ensureCapacity(1);
                buffer.put((byte) '"');
            } else {
                writeRawString(" SYSTEM \"");
                writeRawString(systemId);
                ensureCapacity(1);
                buffer.put((byte) '"');
            }
            ensureCapacity(2);
            buffer.put((byte) '>');
            buffer.put((byte) '\n');
            sendIfNeeded();
        } catch (IOException e) {
            throw wrapIOException(e);
        }
    }

    // ========== Non-SAX Extension Methods ==========

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
     * @throws SAXException if there is an error writing data
     */
    public void writeRaw(String text) throws SAXException {
        try {
            if (text == null || text.isEmpty()) {
                return;
            }
            closePendingStartTag(true);
            writeRawString(text);
            sendIfNeeded();
        } catch (IOException e) {
            throw wrapIOException(e);
        }
    }

    /**
     * Flushes any buffered data to the channel.
     *
     * @throws IOException if there is an error sending data
     */
    public void flush() throws IOException {
        try {
            closePendingStartTag(false);
        } catch (IOException e) {
            throw e;
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
        for (Map<String, String> scope : namespaceStack) {
            for (Map.Entry<String, String> entry : scope.entrySet()) {
                if (namespaceURI.equals(entry.getValue())) {
                    return entry.getKey();
                }
            }
        }
        return null;
    }

    // ========== Internal Helper Methods ==========

    /**
     * Wraps an IOException as a SAXException, including locator information
     * if available to indicate how far the writer progressed.
     */
    private SAXException wrapIOException(IOException e) {
        if (locator != null) {
            String msg = e.getMessage() + " [at line " + locator.getLineNumber()
                + ", column " + locator.getColumnNumber() + "]";
            return new SAXException(msg, e);
        }
        return new SAXException(e);
    }

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
     * Writes a namespace declaration as an xmlns attribute.
     */
    private void writeNamespaceDeclaration(String prefix, String uri) throws IOException {
        if (prefix == null || prefix.isEmpty()) {
            writeRawString(" xmlns=\"");
        } else {
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
     * Writes raw characters without escaping, using the configured charset.
     */
    private void writeRawChars(char[] ch, int start, int length) throws IOException {
        String s = new String(ch, start, length);
        byte[] bytes = s.getBytes(charset);
        ensureCapacity(bytes.length);
        buffer.put(bytes);
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
                buffer.put((byte) '\t');
            } else if (codePoint == '\n') {
                buffer.put((byte) ' ');
            } else if (codePoint == '\r') {
                buffer.put((byte) ' ');
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
                writeCharacterReference(codePoint);
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
        if (xml11) {
            writeHexCharacterReference(codePoint);
        } else {
            writeDecimalCharacterReference(codePoint);
        }
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

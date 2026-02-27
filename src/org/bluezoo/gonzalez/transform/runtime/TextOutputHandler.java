/*
 * TextOutputHandler.java
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

package org.bluezoo.gonzalez.transform.runtime;

import org.xml.sax.Attributes;
import org.xml.sax.ContentHandler;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Output handler for XSLT text output method.
 *
 * <p>The text output method outputs only the text content of the result tree,
 * without any markup. All element tags, attributes, comments, and processing
 * instructions are ignored - only character data is written to the output.
 *
 * <p>This implementation uses NIO channels for efficient byte-oriented output,
 * with internal buffering to minimize channel writes.
 *
 * <p>Per XSLT 1.0 specification section 16.3:
 * <ul>
 *   <li>No XML declaration is output</li>
 *   <li>No escaping is performed</li>
 *   <li>Only text nodes are output</li>
 * </ul>
 *
 * <h2>Example</h2>
 * <pre>{@code
 * // Using with FileChannel (most efficient)
 * try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
 *     TextOutputHandler handler = new TextOutputHandler(channel, "UTF-8");
 *     handler.startDocument();
 *     handler.characters("Hello, World!");
 *     handler.endDocument();
 * }
 * 
 * // Using with OutputStream
 * TextOutputHandler handler = new TextOutputHandler(outputStream, "UTF-8");
 * }</pre>
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class TextOutputHandler implements OutputHandler, ContentHandler {

    private static final int BUFFER_SIZE = 4096;

    private final WritableByteChannel channel;
    private final Charset charset;
    private final CharsetEncoder encoder;
    private ByteBuffer buffer;
    
    // XSLT 2.0 atomic value spacing state
    private boolean atomicValuePending = false;
    private boolean inAttributeContent = false;
    private boolean contentReceived = false;
    private boolean claimedByResultDocument = false;

    /**
     * Creates a text output handler writing to a byte channel.
     *
     * <p>This is the native NIO constructor. For file: outputs, passing a
     * {@link java.nio.channels.FileChannel} provides optimal performance.
     *
     * @param channel the output channel
     * @param encoding the character encoding (e.g., "UTF-8"), or null for UTF-8
     */
    public TextOutputHandler(WritableByteChannel channel, String encoding) {
        this.channel = channel;
        this.charset = encoding != null ? Charset.forName(encoding) : StandardCharsets.UTF_8;
        this.encoder = charset.newEncoder();
        this.buffer = ByteBuffer.allocate(BUFFER_SIZE);
    }

    /**
     * Creates a text output handler writing to an output stream.
     *
     * <p>The output stream is wrapped in a channel internally. For file-based
     * output, consider using the channel constructor with a FileChannel for
     * better performance.
     *
     * @param outputStream the output stream
     * @param encoding the character encoding (e.g., "UTF-8"), or null for UTF-8
     */
    public TextOutputHandler(OutputStream outputStream, String encoding) {
        this(Channels.newChannel(outputStream), encoding);
    }

    /**
     * Writes text to the buffer, flushing to channel if needed.
     */
    private void write(String text) throws SAXException {
        if (text == null || text.isEmpty()) {
            return;
        }
        
        try {
            // Encode text to bytes
            byte[] bytes = text.getBytes(charset);
            
            // Write to buffer, flushing as needed
            int offset = 0;
            while (offset < bytes.length) {
                int remaining = buffer.remaining();
                int toCopy = Math.min(remaining, bytes.length - offset);
                
                buffer.put(bytes, offset, toCopy);
                offset += toCopy;
                
                // Flush buffer if full
                if (!buffer.hasRemaining()) {
                    flushBuffer();
                }
            }
        } catch (IOException e) {
            throw new SAXException("Error writing output", e);
        }
    }

    /**
     * Flushes the internal buffer to the channel.
     */
    private void flushBuffer() throws IOException {
        if (buffer.position() > 0) {
            buffer.flip();
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            buffer.clear();
        }
    }

    @Override
    public void startDocument() throws SAXException {
        // Text method: no XML declaration
    }

    @Override
    public void endDocument() throws SAXException {
        try {
            flushBuffer();
        } catch (IOException e) {
            throw new SAXException("Error flushing output", e);
        }
    }

    @Override
    public void startElement(String namespaceURI, String localName, String qName) throws SAXException {
        contentReceived = true;
    }

    @Override
    public void endElement(String namespaceURI, String localName, String qName) throws SAXException {
        // Text method: no markup output
    }

    @Override
    public void attribute(String namespaceURI, String localName, String qName, String value) 
            throws SAXException {
        // Text method: no markup output
    }

    @Override
    public void namespace(String prefix, String uri) throws SAXException {
        // Text method: no markup output
    }

    @Override
    public void characters(String text) throws SAXException {
        if (claimedByResultDocument) {
            throw new SAXException("XTDE1490: Cannot write to the principal output URI " +
                "because it has been claimed by xsl:result-document");
        }
        contentReceived = true;
        write(text);
    }

    @Override
    public void charactersRaw(String text) throws SAXException {
        // Same as characters() for text output - no escaping either way
        write(text);
    }

    @Override
    public void comment(String text) throws SAXException {
        // Text method: no comments output
    }

    @Override
    public void processingInstruction(String target, String data) throws SAXException {
        // Text method: no PIs output
    }

    @Override
    public void flush() throws SAXException {
        try {
            flushBuffer();
        } catch (IOException e) {
            throw new SAXException("Error flushing output", e);
        }
    }

    // ========== ContentHandler Implementation ==========
    // Note: endElement and processingInstruction are shared with OutputHandler

    @Override
    public void setDocumentLocator(Locator locator) {
        // Not used
    }

    @Override
    public void startPrefixMapping(String prefix, String uri) throws SAXException {
        // Text method: no namespace output
    }

    @Override
    public void endPrefixMapping(String prefix) throws SAXException {
        // Text method: no namespace output
    }

    @Override
    public void startElement(String uri, String localName, String qName, Attributes atts) 
            throws SAXException {
        // Text method: no markup output
    }

    @Override
    public void characters(char[] ch, int start, int length) throws SAXException {
        write(new String(ch, start, length));
    }

    @Override
    public void ignorableWhitespace(char[] ch, int start, int length) throws SAXException {
        write(new String(ch, start, length));
    }

    @Override
    public void skippedEntity(String name) throws SAXException {
        // Not used
    }
    
    @Override
    public boolean isAtomicValuePending() {
        return atomicValuePending;
    }
    
    @Override
    public void setAtomicValuePending(boolean pending) {
        this.atomicValuePending = pending;
    }
    
    @Override
    public boolean isInAttributeContent() {
        return inAttributeContent;
    }
    
    @Override
    public void setInAttributeContent(boolean inAttributeContent) {
        this.inAttributeContent = inAttributeContent;
    }
    
    @Override
    public void atomicValue(org.bluezoo.gonzalez.transform.xpath.type.XPathValue value) 
            throws SAXException {
        if (value != null) {
            // In text output, add space between adjacent atomic values (XSLT 2.0+)
            // But NOT in attribute content
            if (atomicValuePending && !inAttributeContent) {
                characters(" ");
            }
            characters(value.asString());
            atomicValuePending = true;
        }
    }

    @Override
    public boolean hasReceivedContent() {
        return contentReceived;
    }

    @Override
    public void markClaimedByResultDocument() {
        claimedByResultDocument = true;
    }

    @Override
    public boolean isClaimedByResultDocument() {
        return claimedByResultDocument;
    }

}

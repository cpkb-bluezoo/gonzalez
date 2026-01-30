/*
 * HTMLOutputHandler.java
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
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Output handler for XSLT HTML output method.
 *
 * <p>The HTML output method serializes the result tree as HTML, following
 * HTML-specific rules that differ from XML serialization.
 *
 * <p>This implementation uses NIO channels for efficient byte-oriented output,
 * with internal buffering to minimize channel writes.
 *
 * <h2>HTML-Specific Rules (XSLT 1.0 Section 16.2)</h2>
 * <ul>
 *   <li><b>Void elements</b> - Elements like {@code <br>}, {@code <hr>}, {@code <img>}
 *       are output without end tags (not self-closing)</li>
 *   <li><b>Boolean attributes</b> - Attributes like {@code checked}, {@code selected}
 *       are output as just the attribute name when their value equals the name</li>
 *   <li><b>Script/style content</b> - Content inside {@code <script>} and {@code <style>}
 *       elements is not escaped</li>
 *   <li><b>No XML declaration</b> - HTML output never includes an XML declaration</li>
 *   <li><b>Case insensitivity</b> - Element and attribute names are compared case-insensitively</li>
 * </ul>
 *
 * <h2>Example</h2>
 * <pre>{@code
 * // Using with FileChannel (most efficient)
 * try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
 *     HTMLOutputHandler handler = new HTMLOutputHandler(channel, "UTF-8", true);
 *     handler.startDocument();
 *     handler.startElement("", "html", "html");
 *     // ... build HTML document ...
 *     handler.endDocument();
 * }
 * }</pre>
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class HTMLOutputHandler implements OutputHandler, ContentHandler {

    private static final int BUFFER_SIZE = 4096;

    private final WritableByteChannel channel;
    private final Charset charset;
    private final String encoding;
    private final boolean indent;
    private ByteBuffer buffer;

    // Pending element state for deferred attribute output
    private String pendingQName;
    private final List<String[]> pendingAttributes = new ArrayList<>();  // [qName, value]
    private boolean inStartTag = false;
    private int depth = 0;
    
    // Track if we're inside a raw text element (script/style)
    private final Deque<String> elementStack = new ArrayDeque<>();
    private boolean inRawTextElement = false;
    
    // Track meta charset insertion per XSLT 1.0 spec section 16.2
    private boolean inHead = false;
    private boolean metaCharsetEmitted = false;

    /**
     * HTML void elements - these have no end tag.
     * Per HTML5 specification.
     */
    private static final Set<String> VOID_ELEMENTS = new HashSet<>(Arrays.asList(
        "area", "base", "br", "col", "embed", "hr", "img", "input",
        "link", "meta", "param", "source", "track", "wbr"
    ));

    /**
     * HTML raw text elements - content is not escaped.
     */
    private static final Set<String> RAW_TEXT_ELEMENTS = new HashSet<>(Arrays.asList(
        "script", "style"
    ));

    /**
     * HTML boolean attributes - output just the name if value equals name.
     */
    private static final Set<String> BOOLEAN_ATTRIBUTES = new HashSet<>(Arrays.asList(
        "async", "autofocus", "autoplay", "checked", "controls", "default",
        "defer", "disabled", "formnovalidate", "hidden", "ismap", "loop",
        "multiple", "muted", "nomodule", "novalidate", "open", "playsinline",
        "readonly", "required", "reversed", "selected", "allowfullscreen",
        "compact", "declare", "nohref", "noresize", "noshade", "nowrap"
    ));

    /**
     * Creates an HTML output handler writing to a byte channel.
     *
     * <p>This is the native NIO constructor. For file: outputs, passing a
     * {@link java.nio.channels.FileChannel} provides optimal performance.
     *
     * @param channel the output channel
     * @param encoding the character encoding (e.g., "UTF-8"), or null for UTF-8
     * @param indent whether to indent output
     */
    public HTMLOutputHandler(WritableByteChannel channel, String encoding, boolean indent) {
        this.channel = channel;
        this.encoding = encoding != null ? encoding : "UTF-8";
        this.charset = Charset.forName(this.encoding);
        this.indent = indent;
        this.buffer = ByteBuffer.allocate(BUFFER_SIZE);
    }

    /**
     * Creates an HTML output handler writing to an output stream.
     *
     * <p>The output stream is wrapped in a channel internally. For file-based
     * output, consider using the channel constructor with a FileChannel for
     * better performance.
     *
     * @param outputStream the output stream
     * @param encoding the character encoding (e.g., "UTF-8"), or null for UTF-8
     * @param indent whether to indent output
     */
    public HTMLOutputHandler(OutputStream outputStream, String encoding, boolean indent) {
        this(Channels.newChannel(outputStream), encoding, indent);
    }

    /**
     * Writes text to the buffer, flushing to channel if needed.
     */
    private void write(String text) throws SAXException {
        if (text == null || text.isEmpty()) {
            return;
        }
        
        try {
            byte[] bytes = text.getBytes(charset);
            int offset = 0;
            while (offset < bytes.length) {
                int remaining = buffer.remaining();
                int toCopy = Math.min(remaining, bytes.length - offset);
                
                buffer.put(bytes, offset, toCopy);
                offset += toCopy;
                
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

    private void flushStartTag() throws SAXException {
        if (inStartTag) {
            // Check if this is a <meta> with charset - if so, mark as emitted
            if (pendingQName != null && pendingQName.toLowerCase().equals("meta")) {
                for (String[] attr : pendingAttributes) {
                    String attrLower = attr[0].toLowerCase();
                    // Check for <meta charset="..."> or <meta http-equiv="Content-Type" ...>
                    if ("charset".equals(attrLower) || 
                        ("http-equiv".equals(attrLower) && 
                         attr[1].toLowerCase().contains("content-type"))) {
                        metaCharsetEmitted = true;
                        break;
                    }
                }
            }
            
            // Output pending attributes
            for (String[] attr : pendingAttributes) {
                String attrName = attr[0];
                String attrValue = attr[1];
                String attrLower = attrName.toLowerCase();
                
                // Boolean attributes: output just the name if value equals name or is empty
                if (BOOLEAN_ATTRIBUTES.contains(attrLower) && 
                    (attrValue.isEmpty() || attrLower.equals(attrValue.toLowerCase()))) {
                    write(" " + attrName);
                } else {
                    write(" " + attrName + "=\"" + escapeAttr(attrValue) + "\"");
                }
            }
            pendingAttributes.clear();
            
            write(">");
            
            // Per XSLT 1.0 section 16.2: After <head> start tag, emit meta charset
            // if not already provided by the stylesheet
            if (inHead && !metaCharsetEmitted && pendingQName != null && 
                pendingQName.toLowerCase().equals("head")) {
                if (indent) {
                    write("\n  ");
                }
                write("<meta charset=\"" + encoding + "\">");
                metaCharsetEmitted = true;
            }
            
            inStartTag = false;
        }
    }

    private String escapeAttr(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&': sb.append("&amp;"); break;
                case '<': sb.append("&lt;"); break;
                case '"': sb.append("&quot;"); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }

    private String escapeText(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&': sb.append("&amp;"); break;
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }

    @Override
    public void startDocument() throws SAXException {
        // HTML output: no XML declaration
    }

    @Override
    public void endDocument() throws SAXException {
        flushStartTag();
        try {
            flushBuffer();
        } catch (IOException e) {
            throw new SAXException("Error flushing output", e);
        }
    }

    @Override
    public void startElement(String namespaceURI, String localName, String qName) throws SAXException {
        flushStartTag();
        
        if (indent && depth > 0) {
            write("\n");
            for (int i = 0; i < depth; i++) write("  ");
        }
        
        write("<" + qName);
        pendingQName = qName;
        inStartTag = true;
        
        String elemLower = (localName != null && !localName.isEmpty() ? localName : qName).toLowerCase();
        elementStack.push(elemLower);
        depth++;
        
        // Track raw text elements
        if (RAW_TEXT_ELEMENTS.contains(elemLower)) {
            inRawTextElement = true;
        }
        
        // Track entering <head> for meta charset insertion
        if ("head".equals(elemLower)) {
            inHead = true;
        }
    }

    @Override
    public void endElement(String namespaceURI, String localName, String qName) throws SAXException {
        depth--;
        String elemLower = (localName != null && !localName.isEmpty() ? localName : qName).toLowerCase();
        boolean isVoid = VOID_ELEMENTS.contains(elemLower);
        
        // Exit raw text element tracking
        if (RAW_TEXT_ELEMENTS.contains(elemLower)) {
            inRawTextElement = false;
        }
        
        // Exit head element tracking
        if ("head".equals(elemLower)) {
            inHead = false;
        }
        
        if (inStartTag) {
            // Check if this is a <meta> with charset
            if ("meta".equals(elemLower)) {
                for (String[] attr : pendingAttributes) {
                    String attrLower = attr[0].toLowerCase();
                    if ("charset".equals(attrLower) || 
                        ("http-equiv".equals(attrLower) && 
                         attr[1].toLowerCase().contains("content-type"))) {
                        metaCharsetEmitted = true;
                        break;
                    }
                }
            }
            
            // If closing an empty <head>, emit meta charset first
            if ("head".equals(elemLower) && !metaCharsetEmitted) {
                // Output pending attributes first
                for (String[] attr : pendingAttributes) {
                    String attrName = attr[0];
                    String attrValue = attr[1];
                    String attrLower = attrName.toLowerCase();
                    
                    if (BOOLEAN_ATTRIBUTES.contains(attrLower) && 
                        (attrValue.isEmpty() || attrLower.equals(attrValue.toLowerCase()))) {
                        write(" " + attrName);
                    } else {
                        write(" " + attrName + "=\"" + escapeAttr(attrValue) + "\"");
                    }
                }
                pendingAttributes.clear();
                
                // Output: ><meta charset="..."></head>
                write(">");
                if (indent) {
                    write("\n  ");
                }
                write("<meta charset=\"" + encoding + "\">");
                metaCharsetEmitted = true;
                if (indent) {
                    write("\n");
                }
                write("</" + qName + ">");
                inStartTag = false;
                if (!elementStack.isEmpty()) {
                    elementStack.pop();
                }
                return;
            }
            
            // Flush attributes
            for (String[] attr : pendingAttributes) {
                String attrName = attr[0];
                String attrValue = attr[1];
                String attrLower = attrName.toLowerCase();
                
                if (BOOLEAN_ATTRIBUTES.contains(attrLower) && 
                    (attrValue.isEmpty() || attrLower.equals(attrValue.toLowerCase()))) {
                    write(" " + attrName);
                } else {
                    write(" " + attrName + "=\"" + escapeAttr(attrValue) + "\"");
                }
            }
            pendingAttributes.clear();
            
            if (isVoid) {
                // Void element: just close the start tag, no end tag
                write(">");
            } else {
                // Non-void empty element: output both tags
                write("></" + qName + ">");
            }
            inStartTag = false;
        } else {
            // Non-empty element
            if (!isVoid) {
                if (indent) {
                    write("\n");
                    for (int i = 0; i < depth; i++) write("  ");
                }
                write("</" + qName + ">");
            }
            // Void elements with content: don't output end tag (shouldn't happen but handle gracefully)
        }
        
        if (!elementStack.isEmpty()) {
            elementStack.pop();
        }
    }

    @Override
    public void attribute(String namespaceURI, String localName, String qName, String value) 
            throws SAXException {
        if (!inStartTag) {
            throw new SAXException("attribute() called outside element start");
        }
        
        // Check for duplicate attribute
        String attrLower = qName.toLowerCase();
        for (int i = 0; i < pendingAttributes.size(); i++) {
            if (pendingAttributes.get(i)[0].toLowerCase().equals(attrLower)) {
                // Update existing
                pendingAttributes.set(i, new String[]{qName, value});
                return;
            }
        }
        
        pendingAttributes.add(new String[]{qName, value});
    }

    @Override
    public void namespace(String prefix, String uri) throws SAXException {
        // HTML output typically doesn't use namespace declarations
        // but we support them if explicitly added
        if (!inStartTag) {
            return;
        }
        
        if (prefix == null || prefix.isEmpty()) {
            pendingAttributes.add(new String[]{"xmlns", uri});
        } else {
            pendingAttributes.add(new String[]{"xmlns:" + prefix, uri});
        }
    }

    @Override
    public void characters(String text) throws SAXException {
        flushStartTag();
        
        if (inRawTextElement) {
            // Script/style content: no escaping
            write(text);
        } else {
            write(escapeText(text));
        }
    }

    @Override
    public void charactersRaw(String text) throws SAXException {
        flushStartTag();
        write(text);  // No escaping
    }

    @Override
    public void comment(String text) throws SAXException {
        flushStartTag();
        write("<!--" + text + "-->");
    }

    @Override
    public void processingInstruction(String target, String data) throws SAXException {
        flushStartTag();
        // HTML allows PIs but they're uncommon
        if (data != null && !data.isEmpty()) {
            write("<?" + target + " " + data + ">");
        } else {
            write("<?" + target + ">");
        }
    }

    @Override
    public void flush() throws SAXException {
        flushStartTag();
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
        // HTML typically doesn't use namespaces, but support if needed
        namespace(prefix, uri);
    }

    @Override
    public void endPrefixMapping(String prefix) throws SAXException {
        // Not used
    }

    @Override
    public void startElement(String uri, String localName, String qName, Attributes atts) 
            throws SAXException {
        startElement(uri, localName, qName);
        
        // Copy attributes
        for (int i = 0; i < atts.getLength(); i++) {
            attribute(atts.getURI(i), atts.getLocalName(i), atts.getQName(i), atts.getValue(i));
        }
    }

    @Override
    public void characters(char[] ch, int start, int length) throws SAXException {
        characters(new String(ch, start, length));
    }

    @Override
    public void ignorableWhitespace(char[] ch, int start, int length) throws SAXException {
        characters(new String(ch, start, length));
    }

    @Override
    public void skippedEntity(String name) throws SAXException {
        // Not used
    }

}

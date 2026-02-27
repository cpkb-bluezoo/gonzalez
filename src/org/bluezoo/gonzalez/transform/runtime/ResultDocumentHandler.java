/*
 * ResultDocumentHandler.java
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

import org.bluezoo.gonzalez.transform.compiler.OutputProperties;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.Charset;

/**
 * Output handler for secondary result documents.
 *
 * <p>This handler writes output to a secondary destination as specified
 * by xsl:result-document. It supports the same output methods as the
 * principal output handler (xml, html, text).
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class ResultDocumentHandler implements OutputHandler {

    private final Writer writer;
    private final OutputProperties outputProperties;
    private boolean documentStarted;
    private int depth;
    private java.util.Map<Integer, String> characterMappings;  // Unicode code points

    /**
     * Creates a new result document handler.
     *
     * @param outputStream the output stream
     * @param properties the output properties
     */
    public ResultDocumentHandler(OutputStream outputStream, OutputProperties properties) {
        this.outputProperties = properties != null ? properties : new OutputProperties();
        
        String encoding = outputProperties.getEncoding();
        if (encoding == null) {
            encoding = "UTF-8";
        }
        
        this.writer = new OutputStreamWriter(outputStream, Charset.forName(encoding));
        this.documentStarted = false;
        this.depth = 0;
    }

    /**
     * Creates a new result document handler with a writer.
     *
     * @param writer the writer
     * @param properties the output properties
     */
    public ResultDocumentHandler(Writer writer, OutputProperties properties) {
        this.writer = writer;
        this.outputProperties = properties != null ? properties : new OutputProperties();
        this.documentStarted = false;
        this.depth = 0;
    }

    // Pending element for deferred attribute output
    private String pendingElementUri;
    private String pendingElementLocalName;
    private String pendingElementQName;
    private StringBuilder pendingAttributes;
    private boolean inElement;

    @Override
    public void startDocument() throws SAXException {
        documentStarted = true;
        depth = 0;
        inElement = false;
        
        // Output XML declaration if needed
        String encoding = outputProperties.getEncoding();
        if (encoding == null) {
            encoding = "UTF-8";
        }
        
        // Check if we should omit XML declaration
        if (!outputProperties.isOmitXmlDeclaration()) {
            try {
                String version = outputProperties.getVersion();
                if (version == null) {
                    version = "1.0";
                }
                writer.write("<?xml version=\"" + version + "\" encoding=\"" + encoding + "\"?>\n");
            } catch (IOException e) {
                throw new SAXException("Error writing XML declaration", e);
            }
        }
    }

    @Override
    public void endDocument() throws SAXException {
        documentStarted = false;
        flushPendingElement();
        try {
            writer.flush();
        } catch (IOException e) {
            throw new SAXException("Error flushing output", e);
        }
    }

    @Override
    public void startElement(String uri, String localName, String qName) throws SAXException {
        flushPendingElement();
        
        // Store pending element
        pendingElementUri = uri;
        pendingElementLocalName = localName;
        pendingElementQName = qName;
        pendingAttributes = new StringBuilder();
        inElement = true;
    }

    @Override
    public void endElement(String uri, String localName, String qName) throws SAXException {
        flushPendingElement();
        
        try {
            depth--;
            writeIndent();
            writer.write("</");
            writer.write(qName != null && !qName.isEmpty() ? qName : localName);
            writer.write('>');
        } catch (IOException e) {
            throw new SAXException("Error writing end element", e);
        }
    }

    @Override
    public void attribute(String uri, String localName, String qName, String value) 
            throws SAXException {
        if (!inElement) {
            throw new SAXException("attribute() called outside of element");
        }
        
        String attrName = qName != null && !qName.isEmpty() ? qName : localName;
        pendingAttributes.append(' ');
        pendingAttributes.append(attrName);
        pendingAttributes.append("=\"");
        appendEscaped(pendingAttributes, value, true);
        pendingAttributes.append('"');
    }

    @Override
    public void namespace(String prefix, String uri) throws SAXException {
        if (!inElement) {
            throw new SAXException("namespace() called outside of element");
        }
        
        if (prefix == null || prefix.isEmpty()) {
            if (uri == null || uri.isEmpty()) {
                return;
            }
            pendingAttributes.append(" xmlns=\"");
            pendingAttributes.append(uri);
            pendingAttributes.append('"');
        } else {
            pendingAttributes.append(" xmlns:");
            pendingAttributes.append(prefix);
            pendingAttributes.append("=\"");
            pendingAttributes.append(uri);
            pendingAttributes.append('"');
        }
    }

    @Override
    public void characters(String text) throws SAXException {
        flushPendingElement();
        
        try {
            if (characterMappings == null || characterMappings.isEmpty()) {
                writeEscaped(text, false);
            } else {
                writeCharactersWithMapping(text);
            }
        } catch (IOException e) {
            throw new SAXException("Error writing characters", e);
        }
    }
    
    /**
     * Writes characters with character mapping applied.
     * Characters with mappings are written raw (unescaped), others are escaped.
     */
    private void writeCharactersWithMapping(String text) throws IOException {
        StringBuilder normalChars = null;
        
        int i = 0;
        while (i < text.length()) {
            int codePoint = text.codePointAt(i);
            String replacement = characterMappings.get(codePoint);
            
            if (replacement != null) {
                // Flush any accumulated normal characters
                if (normalChars != null && normalChars.length() > 0) {
                    writeEscaped(normalChars.toString(), false);
                    normalChars.setLength(0);
                }
                // Write replacement raw (unescaped) - per XSLT spec
                writer.write(replacement);
            } else {
                if (normalChars == null) {
                    normalChars = new StringBuilder();
                }
                normalChars.appendCodePoint(codePoint);
            }
            i += Character.charCount(codePoint);
        }
        
        // Flush any remaining normal characters
        if (normalChars != null && normalChars.length() > 0) {
            writeEscaped(normalChars.toString(), false);
        }
    }

    @Override
    public void charactersRaw(String text) throws SAXException {
        flushPendingElement();
        
        try {
            writer.write(text);
        } catch (IOException e) {
            throw new SAXException("Error writing raw characters", e);
        }
    }

    @Override
    public void processingInstruction(String target, String data) throws SAXException {
        flushPendingElement();
        
        try {
            writeIndent();
            writer.write("<?");
            writer.write(target);
            if (data != null && !data.isEmpty()) {
                writer.write(' ');
                writer.write(data);
            }
            writer.write("?>");
        } catch (IOException e) {
            throw new SAXException("Error writing processing instruction", e);
        }
    }

    @Override
    public void comment(String text) throws SAXException {
        flushPendingElement();
        
        try {
            writeIndent();
            writer.write("<!--");
            writer.write(text);
            writer.write("-->");
        } catch (IOException e) {
            throw new SAXException("Error writing comment", e);
        }
    }

    @Override
    public void flush() throws SAXException {
        flushPendingElement();
        
        try {
            writer.flush();
        } catch (IOException e) {
            throw new SAXException("Error flushing output", e);
        }
    }

    /**
     * Flushes the pending element start tag.
     */
    private void flushPendingElement() throws SAXException {
        if (inElement) {
            try {
                writeIndent();
                writer.write('<');
                String name = pendingElementQName != null && !pendingElementQName.isEmpty() 
                    ? pendingElementQName : pendingElementLocalName;
                writer.write(name);
                writer.write(pendingAttributes.toString());
                writer.write('>');
                depth++;
                inElement = false;
            } catch (IOException e) {
                throw new SAXException("Error writing start element", e);
            }
        }
    }

    /**
     * Writes indentation if enabled.
     */
    private void writeIndent() throws IOException {
        if (outputProperties.isIndent() && depth > 0) {
            writer.write('\n');
            for (int i = 0; i < depth; i++) {
                writer.write("  ");
            }
        }
    }

    /**
     * Writes text with XML escaping.
     */
    private void writeEscaped(String text, boolean isAttribute) throws IOException {
        if (text == null) {
            return;
        }
        
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '<':
                    writer.write("&lt;");
                    break;
                case '>':
                    writer.write("&gt;");
                    break;
                case '&':
                    writer.write("&amp;");
                    break;
                case '"':
                    if (isAttribute) {
                        writer.write("&quot;");
                    } else {
                        writer.write(c);
                    }
                    break;
                default:
                    writer.write(c);
            }
        }
    }

    /**
     * Appends text with XML escaping to a StringBuilder.
     */
    private void appendEscaped(StringBuilder sb, String text, boolean isAttribute) {
        if (text == null) {
            return;
        }
        
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '<':
                    sb.append("&lt;");
                    break;
                case '>':
                    sb.append("&gt;");
                    break;
                case '&':
                    sb.append("&amp;");
                    break;
                case '"':
                    if (isAttribute) {
                        sb.append("&quot;");
                    } else {
                        sb.append(c);
                    }
                    break;
                default:
                    sb.append(c);
            }
        }
    }

    /**
     * Returns the output properties for this result document.
     *
     * @return the output properties
     */
    public OutputProperties getOutputProperties() {
        return outputProperties;
    }

    /**
     * Sets the character mappings for XSLT 2.0+ character mapping during serialization.
     *
     * @param mappings the character-to-string mappings, or null to disable
     */
    public void setCharacterMappings(java.util.Map<Integer, String> mappings) {
        this.characterMappings = mappings;
    }

}

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
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.Charset;

/**
 * Output handler for XSLT text output method.
 *
 * <p>The text output method outputs only the text content of the result tree,
 * without any markup. All element tags, attributes, comments, and processing
 * instructions are ignored - only character data is written to the output.
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
 * TextOutputHandler handler = new TextOutputHandler(outputStream, "UTF-8");
 * handler.startDocument();
 * handler.startElement("", "root", "root");
 * handler.characters("Hello, World!");
 * handler.endElement("", "root", "root");
 * handler.endDocument();
 * // Output: "Hello, World!"
 * }</pre>
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class TextOutputHandler implements OutputHandler, ContentHandler {

    private final Writer writer;

    /**
     * Creates a text output handler writing to an output stream.
     *
     * @param outputStream the output stream
     * @param encoding the character encoding (e.g., "UTF-8")
     */
    public TextOutputHandler(OutputStream outputStream, String encoding) {
        Charset charset = Charset.forName(encoding != null ? encoding : "UTF-8");
        this.writer = new OutputStreamWriter(outputStream, charset);
    }

    /**
     * Creates a text output handler writing to a writer.
     *
     * @param writer the writer
     */
    public TextOutputHandler(Writer writer) {
        this.writer = writer;
    }

    @Override
    public void startDocument() throws SAXException {
        // Text method: no XML declaration
    }

    @Override
    public void endDocument() throws SAXException {
        try {
            writer.flush();
        } catch (IOException e) {
            throw new SAXException(e);
        }
    }

    @Override
    public void startElement(String namespaceURI, String localName, String qName) throws SAXException {
        // Text method: no markup output
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
        // Text method: output text without escaping
        try {
            writer.write(text);
        } catch (IOException e) {
            throw new SAXException(e);
        }
    }

    @Override
    public void charactersRaw(String text) throws SAXException {
        // Same as characters() for text output - no escaping either way
        characters(text);
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
            writer.flush();
        } catch (IOException e) {
            throw new SAXException(e);
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

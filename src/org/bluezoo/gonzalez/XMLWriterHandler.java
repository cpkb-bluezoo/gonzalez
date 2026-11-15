/*
 * XMLWriterHandler.java
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
import java.nio.CharBuffer;

import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

/**
 * An {@link XMLHandler} that writes the document it is told about to an
 * {@link XMLWriter}. Given to an {@link XMLParser}, it turns the two into
 * a pipeline from bytes to bytes:
 *
 * <pre>{@code
 * XMLWriter writer = new XMLWriter(out);
 * XMLParser parser = new XMLParser();
 * parser.setHandler(new XMLWriterHandler(writer));
 * parser.parse(in);
 * }</pre>
 *
 * <p>Every event is passed to the writer as it arrives and nothing is
 * kept. Character data, attribute values, comments and the data of
 * processing instructions reach the writer in the pieces the parser
 * reports them in, so a document is copied in the same constant memory
 * it is parsed in, however large any part of it is. The same applies when
 * the parser is fed with {@link XMLParser#receive}: what has been
 * received so far is written, to within the writer's buffer.
 *
 * <p>What is written is the document as the parser reports it, which is
 * not always character for character what was read:
 * <ul>
 * <li>Entity references are written as what they stand for, and attribute
 * values as normalised.</li>
 * <li>Attributes that take their value from a default in the DTD are
 * written out.</li>
 * <li>The XML declaration is not reported, so none is written; set the
 * writer's charset and other options before parsing.</li>
 * <li>A DTD's internal subset is written from its declarations, not
 * copied.</li>
 * <li>When the parser is namespace aware, namespace declarations go
 * through {@link XMLWriter#writeNamespace}, which leaves out one that
 * repeats a binding already in scope. When it is not, they are attributes
 * like any other and are written as they stand.</li>
 * </ul>
 *
 * <p>An {@link IOException} from the writer stops the parse: it is thrown
 * as the cause of a {@link SAXException}. The writer is flushed at the
 * end of the document; neither it nor its channel is closed.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see XMLParser#setHandler
 */
public class XMLWriterHandler implements XMLHandler {

    private final XMLWriter writer;
    private Locator locator;

    /** Whether namespace declarations are arriving as {@link #namespace}
     *  events, in which case the same declaration reported again as an
     *  attribute is not written a second time. */
    private boolean namespaceEvents;

    /** The attribute being reported is one that is not written. */
    private boolean skippingAttribute;

    /**
     * Creates a handler that writes to the given writer.
     *
     * @param writer the writer, configured as the output should be
     */
    public XMLWriterHandler(XMLWriter writer) {
        if (writer == null) {
            throw new NullPointerException("writer");
        }
        this.writer = writer;
    }

    /**
     * Returns the writer this handler writes to.
     *
     * @return the writer
     */
    public XMLWriter getWriter() {
        return writer;
    }

    @Override
    public void setLocator(Locator locator) {
        this.locator = locator;
    }

    /**
     * Does nothing: the version of the output is the writer's own
     * setting, made by whoever created it.
     */
    @Override
    public void setXml11(boolean xml11) {
    }

    @Override
    public void startDocument() throws SAXException {
        namespaceEvents = false;
        skippingAttribute = false;
    }

    /**
     * Sends on whatever the writer still holds.
     */
    @Override
    public void endDocument() throws SAXException {
        try {
            writer.flush();
        } catch (IOException e) {
            throw failed(e);
        }
    }

    @Override
    public void startElement(String qName) throws SAXException {
        try {
            writer.writeStartElement(qName);
        } catch (IOException e) {
            throw failed(e);
        }
    }

    @Override
    public void namespace(String prefix, String uri) throws SAXException {
        namespaceEvents = true;
        try {
            if (prefix == null || prefix.isEmpty()) {
                writer.writeDefaultNamespace(uri);
            } else {
                writer.writeNamespace(prefix, uri);
            }
        } catch (IOException e) {
            throw failed(e);
        }
    }

    @Override
    public void startAttribute(String name, String type, boolean declared, boolean specified)
            throws SAXException {
        // A namespace-aware parser asked to report declarations as
        // attributes too reports each one both ways.
        skippingAttribute = namespaceEvents && (name.equals("xmlns") || name.startsWith("xmlns:"));
        if (skippingAttribute) {
            return;
        }
        try {
            writer.writeStartAttribute(name);
        } catch (IOException e) {
            throw failed(e);
        }
    }

    @Override
    public void attributeValueContent(CharBuffer value, boolean end) throws SAXException {
        if (skippingAttribute) {
            return;
        }
        try {
            writer.writeAttributeContent(value);
            if (end) {
                writer.writeEndAttribute();
            }
        } catch (IOException e) {
            throw failed(e);
        }
    }

    @Override
    public void endAttributes() throws SAXException {
        // The writer ends the start tag itself, when it sees what follows.
    }

    /**
     * Writes the character data, including white space the DTD makes
     * ignorable: it is part of the document all the same.
     */
    @Override
    public void characters(CharBuffer text, boolean ignorable, boolean end) throws SAXException {
        try {
            writer.writeCharacters(text);
        } catch (IOException e) {
            throw failed(e);
        }
    }

    @Override
    public void endElement() throws SAXException {
        try {
            writer.writeEndElement();
        } catch (IOException e) {
            throw failed(e);
        }
    }

    @Override
    public void startComment() throws SAXException {
        try {
            writer.writeStartComment();
        } catch (IOException e) {
            throw failed(e);
        }
    }

    @Override
    public void commentData(CharBuffer text, boolean end) throws SAXException {
        try {
            writer.writeCommentContent(text);
            if (end) {
                writer.writeEndComment();
            }
        } catch (IOException e) {
            throw failed(e);
        }
    }

    @Override
    public void startCDATA() throws SAXException {
        try {
            writer.writeStartCDATA();
        } catch (IOException e) {
            throw failed(e);
        }
    }

    @Override
    public void endCDATA() throws SAXException {
        try {
            writer.writeEndCDATA();
        } catch (IOException e) {
            throw failed(e);
        }
    }

    @Override
    public void startDTD(String name, String publicId, String systemId) throws SAXException {
        try {
            writer.writeStartDTD(name, publicId, systemId);
        } catch (IOException e) {
            throw failed(e);
        }
    }

    @Override
    public void endDTD() throws SAXException {
        try {
            writer.writeEndDTD();
        } catch (IOException e) {
            throw failed(e);
        }
    }

    /**
     * Notes the start of the external DTD subset, whose declarations the
     * writer treats differently. The start of any other entity needs
     * nothing: its content is written as it is reported.
     */
    @Override
    public void startEntity(String name) throws SAXException {
        if ("[dtd]".equals(name)) {
            writer.startExternalSubset();
        }
    }

    @Override
    public void endEntity(String name) throws SAXException {
        if ("[dtd]".equals(name)) {
            writer.endExternalSubset();
        }
    }

    @Override
    public void notationDecl(String name, String publicId, String systemId) throws SAXException {
        try {
            writer.writeNotationDecl(name, publicId, systemId);
        } catch (IOException e) {
            throw failed(e);
        }
    }

    @Override
    public void unparsedEntityDecl(String name, String publicId, String systemId, String notationName)
            throws SAXException {
        try {
            writer.writeUnparsedEntityDecl(name, publicId, systemId, notationName);
        } catch (IOException e) {
            throw failed(e);
        }
    }

    @Override
    public void elementDecl(String name, String model) throws SAXException {
        try {
            writer.writeElementDecl(name, model);
        } catch (IOException e) {
            throw failed(e);
        }
    }

    @Override
    public void attributeDecl(String eName, String aName, String type, String mode, String value)
            throws SAXException {
        try {
            writer.writeAttributeDecl(eName, aName, type, mode, value);
        } catch (IOException e) {
            throw failed(e);
        }
    }

    @Override
    public void internalEntityDecl(String name, String value) throws SAXException {
        try {
            writer.writeInternalEntityDecl(name, value);
        } catch (IOException e) {
            throw failed(e);
        }
    }

    @Override
    public void externalEntityDecl(String name, String publicId, String systemId) throws SAXException {
        try {
            writer.writeExternalEntityDecl(name, publicId, systemId);
        } catch (IOException e) {
            throw failed(e);
        }
    }

    /**
     * Writes a reference to a general entity that was not expanded. A
     * parameter entity or the external DTD subset has no such reference
     * to write.
     */
    @Override
    public void skippedEntity(String name) throws SAXException {
        if (name.startsWith("%") || "[dtd]".equals(name)) {
            return;
        }
        try {
            writer.writeEntityRef(name);
        } catch (IOException e) {
            throw failed(e);
        }
    }

    @Override
    public void piTarget(String target) throws SAXException {
        try {
            writer.writeStartProcessingInstruction(target);
        } catch (IOException e) {
            throw failed(e);
        }
    }

    @Override
    public void piData(CharBuffer data, boolean end) throws SAXException {
        try {
            writer.writeProcessingInstructionContent(data);
            if (end) {
                writer.writeEndProcessingInstruction();
            }
        } catch (IOException e) {
            throw failed(e);
        }
    }

    @Override
    public void saveBuffers() throws SAXException {
        // Nothing is held: every buffer is written before its event returns.
    }

    @Override
    public SAXException fatalError(String message) throws SAXException {
        return new SAXParseException(message, locator);
    }

    /**
     * Does nothing: a document that is not valid can still be written.
     */
    @Override
    public void error(String message) throws SAXException {
    }

    private SAXException failed(IOException e) {
        return new SAXException(e);
    }

}

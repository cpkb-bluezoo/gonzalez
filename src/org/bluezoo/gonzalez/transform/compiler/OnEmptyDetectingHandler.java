/*
 * OnEmptyDetectingHandler.java
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

package org.bluezoo.gonzalez.transform.compiler;

import org.xml.sax.SAXException;

import org.bluezoo.gonzalez.transform.runtime.OutputHandler;
import org.bluezoo.gonzalez.transform.runtime.SAXEventBuffer;

/**
 * OutputHandler that detects whether any content has been produced.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
class OnEmptyDetectingHandler implements OutputHandler {
    private final OutputHandler parent;
    private final SAXEventBuffer buffer;
    private final CompilerBufferOutputHandler bufferHandler;
    private boolean hasContent = false;

    OnEmptyDetectingHandler(OutputHandler parent, SAXEventBuffer buffer) {
        this.parent = parent;
        this.buffer = buffer;
        this.bufferHandler = new CompilerBufferOutputHandler(buffer);
    }

    boolean hasContent() {
        return hasContent || !buffer.isEmpty();
    }

    @Override
    public void startDocument() throws SAXException {
        bufferHandler.startDocument();
    }

    @Override
    public void endDocument() throws SAXException {
        bufferHandler.endDocument();
    }

    @Override
    public void startElement(String namespaceURI, String localName, String qName) throws SAXException {
        hasContent = true;
        bufferHandler.startElement(namespaceURI, localName, qName);
    }

    @Override
    public void endElement(String namespaceURI, String localName, String qName) throws SAXException {
        bufferHandler.endElement(namespaceURI, localName, qName);
    }

    @Override
    public void attribute(String namespaceURI, String localName, String qName, String value) throws SAXException {
        // Attributes go to the parent element (they're part of the containing element's output)
        // and count as content for on-empty purposes
        hasContent = true;
        parent.attribute(namespaceURI, localName, qName, value);
    }

    @Override
    public void namespace(String prefix, String uri) throws SAXException {
        // Namespace declarations go to the parent element and count as content
        hasContent = true;
        parent.namespace(prefix, uri);
    }

    @Override
    public void characters(String text) throws SAXException {
        if (text != null && !text.isEmpty()) {
            hasContent = true;
            bufferHandler.characters(text);
        }
    }

    @Override
    public void charactersRaw(String text) throws SAXException {
        if (text != null && !text.isEmpty()) {
            hasContent = true;
            bufferHandler.charactersRaw(text);
        }
    }

    @Override
    public void comment(String text) throws SAXException {
        hasContent = true;
        bufferHandler.comment(text);
    }

    @Override
    public void processingInstruction(String target, String data) throws SAXException {
        hasContent = true;
        bufferHandler.processingInstruction(target, data);
    }

    @Override
    public void flush() throws SAXException {
        bufferHandler.flush();
    }

    @Override
    public void setElementType(String namespaceURI, String localName) throws SAXException {
        // Type annotations for attributes go to parent
        parent.setElementType(namespaceURI, localName);
    }

    @Override
    public void setAttributeType(String namespaceURI, String localName) throws SAXException {
        parent.setAttributeType(namespaceURI, localName);
    }

    @Override
    public void setAtomicValuePending(boolean pending) throws SAXException {
        bufferHandler.setAtomicValuePending(pending);
    }

    @Override
    public boolean isAtomicValuePending() {
        return bufferHandler.isAtomicValuePending();
    }

    @Override
    public void setInAttributeContent(boolean inAttributeContent) throws SAXException {
        bufferHandler.setInAttributeContent(inAttributeContent);
    }

    @Override
    public boolean isInAttributeContent() {
        return bufferHandler.isInAttributeContent();
    }
}

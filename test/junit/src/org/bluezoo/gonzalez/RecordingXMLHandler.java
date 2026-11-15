/*
 * RecordingXMLHandler.java
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
 */

package org.bluezoo.gonzalez;

import java.nio.CharBuffer;
import java.util.ArrayList;
import java.util.List;

import org.xml.sax.Locator;
import org.xml.sax.SAXException;

/**
 * Records native {@link XMLHandler} events as strings, the counterpart of
 * {@link RecordingSaxHandler} for the native path.
 *
 * <p>How content is divided into calls depends on how the input was
 * chunked, so every streamed value (character data, attribute values,
 * comments, processing instruction data) is coalesced before it is
 * recorded. Two parses of the same document therefore record the same
 * list however the bytes arrived.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class RecordingXMLHandler implements XMLHandler {

    private final List<String> events = new ArrayList<String>();
    private final StringBuilder text = new StringBuilder();
    private boolean textPending;
    private final StringBuilder value = new StringBuilder();
    private String attributeName;
    private String piTarget;

    /** Returns the events recorded so far. */
    public List<String> getEvents() {
        flushText();
        return events;
    }

    /** Forgets everything recorded, ready for another document. */
    public void clear() {
        events.clear();
        text.setLength(0);
        textPending = false;
        value.setLength(0);
    }

    private void flushText() {
        if (textPending) {
            events.add("characters:" + text);
            text.setLength(0);
            textPending = false;
        }
    }

    private void record(String event) {
        flushText();
        events.add(event);
    }

    @Override
    public void setLocator(Locator locator) {
    }

    @Override
    public void setXml11(boolean xml11) {
    }

    @Override
    public void startDocument() {
        record("startDocument");
    }

    @Override
    public void endDocument() {
        record("endDocument");
    }

    @Override
    public void startElement(String qName) {
        record("startElement:" + qName);
    }

    @Override
    public void namespace(String prefix, String uri) {
        record("namespace:" + prefix + "=" + uri);
    }

    @Override
    public void startAttribute(String name, String type, boolean declared, boolean specified) {
        flushText();
        attributeName = name;
        value.setLength(0);
    }

    @Override
    public void attributeValueContent(CharBuffer chunk, boolean end) {
        value.append(chunk);
        if (end) {
            events.add("attribute:" + attributeName + "=" + value);
            value.setLength(0);
        }
    }

    @Override
    public void endAttributes() {
        record("endAttributes");
    }

    @Override
    public void characters(CharBuffer chunk, boolean ignorable, boolean end) {
        text.append(chunk);
        textPending = true;
    }

    @Override
    public void endElement() {
        record("endElement");
    }

    @Override
    public void startComment() {
        flushText();
        value.setLength(0);
    }

    @Override
    public void commentData(CharBuffer chunk, boolean end) {
        value.append(chunk);
        if (end) {
            events.add("comment:" + value);
            value.setLength(0);
        }
    }

    @Override
    public void startCDATA() {
        record("startCDATA");
    }

    @Override
    public void endCDATA() {
        record("endCDATA");
    }

    @Override
    public void startDTD(String name, String publicId, String systemId) {
        record("startDTD:" + name);
    }

    @Override
    public void endDTD() {
        record("endDTD");
    }

    @Override
    public void startEntity(String name) {
        record("startEntity:" + name);
    }

    @Override
    public void endEntity(String name) {
        record("endEntity:" + name);
    }

    @Override
    public void notationDecl(String name, String publicId, String systemId) {
        record("notationDecl:" + name);
    }

    @Override
    public void unparsedEntityDecl(String name, String publicId, String systemId, String notationName) {
        record("unparsedEntityDecl:" + name);
    }

    @Override
    public void skippedEntity(String name) {
        record("skippedEntity:" + name);
    }

    @Override
    public void piTarget(String target) {
        flushText();
        piTarget = target;
        value.setLength(0);
    }

    @Override
    public void piData(CharBuffer chunk, boolean end) {
        value.append(chunk);
        if (end) {
            events.add("pi:" + piTarget + "=" + value);
            value.setLength(0);
        }
    }

    @Override
    public void saveBuffers() {
    }

    @Override
    public SAXException fatalError(String message) {
        return new SAXException(message);
    }

    @Override
    public void error(String message) {
        record("error:" + message);
    }

}

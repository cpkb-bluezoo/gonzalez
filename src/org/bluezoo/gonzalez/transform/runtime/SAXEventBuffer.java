/*
 * SAXEventBuffer.java
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

import org.xml.sax.*;
import org.xml.sax.helpers.AttributesImpl;

import java.util.ArrayList;
import java.util.List;

/**
 * Buffer for capturing and replaying SAX events.
 *
 * <p>This buffer stores a sequence of SAX events that can be replayed
 * multiple times. It's used for:
 * <ul>
 *   <li>Building result tree fragments from template content</li>
 *   <li>Buffering subtrees for non-streamable operations</li>
 *   <li>Implementing xsl:variable with content</li>
 * </ul>
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class SAXEventBuffer implements ContentHandler {

    /**
     * Base class for stored events.
     */
    private abstract static class Event {
        abstract void replay(ContentHandler handler) throws SAXException;
    }

    private static class StartDocument extends Event {
        @Override void replay(ContentHandler h) throws SAXException { h.startDocument(); }
    }

    private static class EndDocument extends Event {
        @Override void replay(ContentHandler h) throws SAXException { h.endDocument(); }
    }

    private static class StartPrefixMapping extends Event {
        final String prefix, uri;
        StartPrefixMapping(String prefix, String uri) {
            this.prefix = prefix;
            this.uri = uri;
        }
        @Override void replay(ContentHandler h) throws SAXException {
            h.startPrefixMapping(prefix, uri);
        }
    }

    private static class EndPrefixMapping extends Event {
        final String prefix;
        EndPrefixMapping(String prefix) { this.prefix = prefix; }
        @Override void replay(ContentHandler h) throws SAXException {
            h.endPrefixMapping(prefix);
        }
    }

    private static class StartElement extends Event {
        final String uri, localName, qName;
        final Attributes atts;
        StartElement(String uri, String localName, String qName, Attributes atts) {
            this.uri = uri;
            this.localName = localName;
            this.qName = qName;
            this.atts = new AttributesImpl(atts);
        }
        @Override void replay(ContentHandler h) throws SAXException {
            h.startElement(uri, localName, qName, atts);
        }
    }

    private static class EndElement extends Event {
        final String uri, localName, qName;
        EndElement(String uri, String localName, String qName) {
            this.uri = uri;
            this.localName = localName;
            this.qName = qName;
        }
        @Override void replay(ContentHandler h) throws SAXException {
            h.endElement(uri, localName, qName);
        }
    }

    private static class Characters extends Event {
        final char[] ch;
        final int start, length;
        Characters(char[] ch, int start, int length) {
            this.ch = new char[length];
            System.arraycopy(ch, start, this.ch, 0, length);
            this.start = 0;
            this.length = length;
        }
        @Override void replay(ContentHandler h) throws SAXException {
            h.characters(ch, start, length);
        }
    }

    private static class IgnorableWhitespace extends Event {
        final char[] ch;
        final int start, length;
        IgnorableWhitespace(char[] ch, int start, int length) {
            this.ch = new char[length];
            System.arraycopy(ch, start, this.ch, 0, length);
            this.start = 0;
            this.length = length;
        }
        @Override void replay(ContentHandler h) throws SAXException {
            h.ignorableWhitespace(ch, start, length);
        }
    }

    private static class ProcessingInstruction extends Event {
        final String target, data;
        ProcessingInstruction(String target, String data) {
            this.target = target;
            this.data = data;
        }
        @Override void replay(ContentHandler h) throws SAXException {
            h.processingInstruction(target, data);
        }
    }

    private static class SkippedEntity extends Event {
        final String name;
        SkippedEntity(String name) { this.name = name; }
        @Override void replay(ContentHandler h) throws SAXException {
            h.skippedEntity(name);
        }
    }

    private final List<Event> events = new ArrayList<>();
    private boolean recording = true;

    /**
     * Creates a new event buffer.
     */
    public SAXEventBuffer() {
    }

    /**
     * Returns true if the buffer is empty.
     *
     * @return true if no events stored
     */
    public boolean isEmpty() {
        return events.isEmpty();
    }

    /**
     * Returns the number of events stored.
     *
     * @return the event count
     */
    public int size() {
        return events.size();
    }

    /**
     * Clears all stored events.
     */
    public void clear() {
        events.clear();
    }

    /**
     * Stops recording and prepares for replay.
     */
    public void stopRecording() {
        recording = false;
    }

    /**
     * Replays all stored events to a content handler.
     *
     * @param handler the target handler
     * @throws SAXException if replay fails
     */
    public void replay(ContentHandler handler) throws SAXException {
        for (Event event : events) {
            event.replay(handler);
        }
    }

    /**
     * Replays events without startDocument/endDocument.
     *
     * @param handler the target handler
     * @throws SAXException if replay fails
     */
    public void replayContent(ContentHandler handler) throws SAXException {
        for (Event event : events) {
            if (!(event instanceof StartDocument) && !(event instanceof EndDocument)) {
                event.replay(handler);
            }
        }
    }

    // ContentHandler implementation

    @Override
    public void setDocumentLocator(Locator locator) {
        // Locator is not stored
    }

    @Override
    public void startDocument() throws SAXException {
        if (recording) events.add(new StartDocument());
    }

    @Override
    public void endDocument() throws SAXException {
        if (recording) events.add(new EndDocument());
    }

    @Override
    public void startPrefixMapping(String prefix, String uri) throws SAXException {
        if (recording) events.add(new StartPrefixMapping(prefix, uri));
    }

    @Override
    public void endPrefixMapping(String prefix) throws SAXException {
        if (recording) events.add(new EndPrefixMapping(prefix));
    }

    @Override
    public void startElement(String uri, String localName, String qName, Attributes atts) 
            throws SAXException {
        if (recording) events.add(new StartElement(uri, localName, qName, atts));
    }

    @Override
    public void endElement(String uri, String localName, String qName) throws SAXException {
        if (recording) events.add(new EndElement(uri, localName, qName));
    }

    @Override
    public void characters(char[] ch, int start, int length) throws SAXException {
        if (recording) events.add(new Characters(ch, start, length));
    }

    @Override
    public void ignorableWhitespace(char[] ch, int start, int length) throws SAXException {
        if (recording) events.add(new IgnorableWhitespace(ch, start, length));
    }

    @Override
    public void processingInstruction(String target, String data) throws SAXException {
        if (recording) events.add(new ProcessingInstruction(target, data));
    }

    @Override
    public void skippedEntity(String name) throws SAXException {
        if (recording) events.add(new SkippedEntity(name));
    }

    /**
     * Returns the text content of all character events concatenated.
     *
     * @return the text content
     */
    public String getTextContent() {
        StringBuilder sb = new StringBuilder();
        for (Event event : events) {
            if (event instanceof Characters) {
                Characters c = (Characters) event;
                sb.append(c.ch, c.start, c.length);
            }
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return getTextContent();
    }

}

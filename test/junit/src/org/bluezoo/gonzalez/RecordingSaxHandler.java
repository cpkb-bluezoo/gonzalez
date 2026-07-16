/*
 * RecordingSaxHandler.java
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

import java.util.ArrayList;
import java.util.List;

import org.xml.sax.Attributes;
import org.xml.sax.ContentHandler;
import org.xml.sax.DTDHandler;
import org.xml.sax.ErrorHandler;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.ext.DeclHandler;
import org.xml.sax.ext.LexicalHandler;

/**
 * A handler that records every SAX event it receives into a canonical,
 * comparable list of strings.
 * <p>
 * This is the comparison primitive used by the differential chunk-fuzzing
 * harness: the same document is parsed twice (e.g. once in a single buffer,
 * once split into small chunks, or once through the legacy tokenizer and once
 * through the byte-native tokenizer) and the two recorded event lists are
 * compared for equality.
 * <p>
 * SAX explicitly permits character data to be delivered as one chunk or as
 * several consecutive chunks (see {@link ContentHandler#characters}), and a
 * streaming parser fed small buffers will legitimately call
 * {@code characters()} more often than one fed a whole document at once. To
 * keep the recorded event list independent of this allowed variation,
 * consecutive calls of the same character-data-bearing event kind (with no
 * other event in between) are coalesced into a single recorded entry.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class RecordingSaxHandler implements ContentHandler, LexicalHandler, DTDHandler, DeclHandler, ErrorHandler {

    private final List<String> events = new ArrayList<String>();
    private String pendingKind;
    private StringBuilder pendingText;

    /**
     * Returns the recorded event list. Any pending coalesced character data
     * is flushed first.
     *
     * @return the recorded events, in delivery order
     */
    List<String> getEvents() {
        flush();
        return events;
    }

    private void flush() {
        if (pendingKind != null) {
            events.add(pendingKind + ":" + pendingText.toString());
            pendingKind = null;
            pendingText = null;
        }
    }

    private void recordCharData(String kind, char[] ch, int start, int length) {
        String text = new String(ch, start, length);
        if (kind.equals(pendingKind)) {
            pendingText.append(text);
        } else {
            flush();
            pendingKind = kind;
            pendingText = new StringBuilder(text);
        }
    }

    private void record(String entry) {
        flush();
        events.add(entry);
    }

    // ContentHandler

    @Override
    public void setDocumentLocator(Locator locator) {
        // Locator identity/position at call time is not meaningful to record.
    }

    @Override
    public void startDocument() throws SAXException {
        record("startDocument()");
    }

    @Override
    public void endDocument() throws SAXException {
        record("endDocument()");
    }

    @Override
    public void startPrefixMapping(String prefix, String uri) throws SAXException {
        record("startPrefixMapping(" + prefix + "," + uri + ")");
    }

    @Override
    public void endPrefixMapping(String prefix) throws SAXException {
        record("endPrefixMapping(" + prefix + ")");
    }

    @Override
    public void startElement(String uri, String localName, String qName, Attributes atts) throws SAXException {
        StringBuilder buf = new StringBuilder();
        buf.append("startElement(");
        buf.append(uri);
        buf.append(",");
        buf.append(localName);
        buf.append(",");
        buf.append(qName);
        buf.append(",[");
        int len = atts.getLength();
        for (int i = 0; i < len; i++) {
            if (i > 0) {
                buf.append(";");
            }
            buf.append(atts.getURI(i));
            buf.append(" ");
            buf.append(atts.getLocalName(i));
            buf.append("=");
            buf.append(atts.getValue(i));
            buf.append("(");
            buf.append(atts.getType(i));
            buf.append(")");
        }
        buf.append("])");
        record(buf.toString());
    }

    @Override
    public void endElement(String uri, String localName, String qName) throws SAXException {
        record("endElement(" + uri + "," + localName + "," + qName + ")");
    }

    @Override
    public void characters(char[] ch, int start, int length) throws SAXException {
        recordCharData("characters", ch, start, length);
    }

    @Override
    public void ignorableWhitespace(char[] ch, int start, int length) throws SAXException {
        recordCharData("ignorableWhitespace", ch, start, length);
    }

    @Override
    public void processingInstruction(String target, String data) throws SAXException {
        record("processingInstruction(" + target + "," + data + ")");
    }

    @Override
    public void skippedEntity(String name) throws SAXException {
        record("skippedEntity(" + name + ")");
    }

    // LexicalHandler

    @Override
    public void comment(char[] ch, int start, int length) throws SAXException {
        recordCharData("comment", ch, start, length);
    }

    @Override
    public void startDTD(String name, String publicId, String systemId) throws SAXException {
        record("startDTD(" + name + "," + publicId + "," + systemId + ")");
    }

    @Override
    public void endDTD() throws SAXException {
        record("endDTD()");
    }

    @Override
    public void startEntity(String name) throws SAXException {
        record("startEntity(" + name + ")");
    }

    @Override
    public void endEntity(String name) throws SAXException {
        record("endEntity(" + name + ")");
    }

    @Override
    public void startCDATA() throws SAXException {
        record("startCDATA()");
    }

    @Override
    public void endCDATA() throws SAXException {
        record("endCDATA()");
    }

    // DTDHandler

    @Override
    public void notationDecl(String name, String publicId, String systemId) throws SAXException {
        record("notationDecl(" + name + "," + publicId + "," + systemId + ")");
    }

    @Override
    public void unparsedEntityDecl(String name, String publicId, String systemId, String notationName)
            throws SAXException {
        record("unparsedEntityDecl(" + name + "," + publicId + "," + systemId + "," + notationName + ")");
    }

    // DeclHandler

    @Override
    public void elementDecl(String name, String model) throws SAXException {
        record("elementDecl(" + name + "," + model + ")");
    }

    @Override
    public void attributeDecl(String eName, String aName, String type, String mode, String value)
            throws SAXException {
        record("attributeDecl(" + eName + "," + aName + "," + type + "," + mode + "," + value + ")");
    }

    @Override
    public void internalEntityDecl(String name, String value) throws SAXException {
        record("internalEntityDecl(" + name + "," + value + ")");
    }

    @Override
    public void externalEntityDecl(String name, String publicId, String systemId) throws SAXException {
        record("externalEntityDecl(" + name + "," + publicId + "," + systemId + ")");
    }

    // ErrorHandler

    @Override
    public void warning(SAXParseException e) throws SAXException {
        record("warning(" + e.getLineNumber() + "," + e.getColumnNumber() + "," + e.getMessage() + ")");
    }

    @Override
    public void error(SAXParseException e) throws SAXException {
        record("error(" + e.getLineNumber() + "," + e.getColumnNumber() + "," + e.getMessage() + ")");
    }

    @Override
    public void fatalError(SAXParseException e) throws SAXException {
        record("fatalError(" + e.getLineNumber() + "," + e.getColumnNumber() + "," + e.getMessage() + ")");
        throw e;
    }
}

/*
 * ContentParserTest.java
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

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import org.junit.Test;
import org.xml.sax.Attributes;
import org.xml.sax.ContentHandler;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;

import static org.junit.Assert.*;

/**
 * Unit tests for Tokenizer and ContentParser integration.
 * Feeds XML strings through Tokenizer to ContentParser and verifies
 * ContentHandler events fire correctly.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ContentParserTest {

    @Test
    public void testSimpleDocument() throws SAXException {
        String xml = "<?xml version=\"1.0\"?><root/>";

        TestContentHandler handler = new TestContentHandler();
        ContentParser parser = new ContentParser();
        parser.setContentHandler(handler);

        Tokenizer tokenizer = new Tokenizer("test1.xml", parser, TokenizerState.PROLOG_BEFORE_DOCTYPE, false);
        ExternalEntityDecoder decoder = new ExternalEntityDecoder(tokenizer, null, null, false);
        decoder.receive(ByteBuffer.wrap(xml.getBytes(StandardCharsets.UTF_8)));
        decoder.close();
        parser.close();

        handler.verify();
    }

    @Test
    public void testDocumentWithAttributes() throws SAXException {
        String xml = "<?xml version=\"1.0\"?><root id=\"123\" name=\"test\"/>";

        TestContentHandler handler = new TestContentHandler();
        ContentParser parser = new ContentParser();
        parser.setContentHandler(handler);

        Tokenizer tokenizer = new Tokenizer("test2.xml", parser, TokenizerState.PROLOG_BEFORE_DOCTYPE, false);
        ExternalEntityDecoder decoder = new ExternalEntityDecoder(tokenizer, null, null, false);
        decoder.receive(ByteBuffer.wrap(xml.getBytes(StandardCharsets.UTF_8)));
        decoder.close();
        parser.close();

        handler.verify();
    }

    @Test
    public void testDocumentWithCharacterData() throws SAXException {
        String xml = "<?xml version=\"1.0\"?><root>Hello, World!</root>";

        TestContentHandler handler = new TestContentHandler();
        ContentParser parser = new ContentParser();
        parser.setContentHandler(handler);

        Tokenizer tokenizer = new Tokenizer("test3.xml", parser, TokenizerState.PROLOG_BEFORE_DOCTYPE, false);
        ExternalEntityDecoder decoder = new ExternalEntityDecoder(tokenizer, null, null, false);
        decoder.receive(ByteBuffer.wrap(xml.getBytes(StandardCharsets.UTF_8)));
        decoder.close();
        parser.close();

        handler.verify();
    }

    @Test
    public void testDocumentWithCDATA() throws SAXException {
        String xml = "<?xml version=\"1.0\"?><root><![CDATA[<special>content</special>]]></root>";

        TestContentHandler handler = new TestContentHandler();
        ContentParser parser = new ContentParser();
        parser.setContentHandler(handler);

        Tokenizer tokenizer = new Tokenizer("test4.xml", parser, TokenizerState.PROLOG_BEFORE_DOCTYPE, false);
        ExternalEntityDecoder decoder = new ExternalEntityDecoder(tokenizer, null, null, false);
        decoder.receive(ByteBuffer.wrap(xml.getBytes(StandardCharsets.UTF_8)));
        decoder.close();
        parser.close();

        handler.verify();
    }

    @Test
    public void testDocumentWithCommentsAndPIs() throws SAXException {
        String xml = "<?xml version=\"1.0\"?><root/><!-- Comment --><?target data?>";

        TestContentHandler handler = new TestContentHandler();
        ContentParser parser = new ContentParser();
        parser.setContentHandler(handler);

        Tokenizer tokenizer = new Tokenizer("test5.xml", parser, TokenizerState.PROLOG_BEFORE_DOCTYPE, false);
        ExternalEntityDecoder decoder = new ExternalEntityDecoder(tokenizer, null, null, false);
        decoder.receive(ByteBuffer.wrap(xml.getBytes(StandardCharsets.UTF_8)));
        decoder.close();
        parser.close();

        handler.verify();
    }

    /**
     * ContentHandler that tracks basic state for verification.
     */
    private static class TestContentHandler implements ContentHandler {
        private boolean startDocumentCalled = false;
        private boolean endDocumentCalled = false;
        private int elementDepth = 0;

        @Override
        public void setDocumentLocator(Locator locator) {
        }

        @Override
        public void startDocument() throws SAXException {
            startDocumentCalled = true;
        }

        @Override
        public void endDocument() throws SAXException {
            endDocumentCalled = true;
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes atts)
                throws SAXException {
            elementDepth++;
        }

        @Override
        public void endElement(String uri, String localName, String qName) throws SAXException {
            elementDepth--;
        }

        @Override
        public void characters(char[] ch, int start, int length) throws SAXException {
        }

        @Override
        public void ignorableWhitespace(char[] ch, int start, int length) throws SAXException {
        }

        @Override
        public void processingInstruction(String target, String data) throws SAXException {
        }

        @Override
        public void skippedEntity(String name) throws SAXException {
        }

        @Override
        public void startPrefixMapping(String prefix, String uri) throws SAXException {
        }

        @Override
        public void endPrefixMapping(String prefix) throws SAXException {
        }

        void verify() throws SAXException {
            assertTrue("startDocument() was not called", startDocumentCalled);
            assertTrue("endDocument() was not called", endDocumentCalled);
            assertEquals("Element depth mismatch", 0, elementDepth);
        }
    }
}

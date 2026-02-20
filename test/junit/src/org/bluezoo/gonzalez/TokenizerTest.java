/*
 * TokenizerTest.java
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
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import static org.junit.Assert.*;

/**
 * Unit tests for the {@link Tokenizer}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class TokenizerTest {

    // --- Basic tokenization (from SimpleTokenizerTest) ---

    @Test
    public void testSimpleDocument() throws SAXException {
        MockTokenConsumer consumer = new MockTokenConsumer();
        String xml = "<?xml version=\"1.0\"?><root/>";
        tokenize(xml, consumer);

        assertFalse("Tokens should be emitted", consumer.getEvents().isEmpty());
    }

    @Test
    public void testDocumentWithAttributes() throws SAXException {
        MockTokenConsumer consumer = new MockTokenConsumer();
        String xml = "<?xml version=\"1.0\"?><root id=\"123\" name=\"test\"/>";
        tokenize(xml, consumer);

        assertFalse("Tokens should be emitted", consumer.getEvents().isEmpty());
    }

    @Test
    public void testDocumentWithCharacterData() throws SAXException {
        MockTokenConsumer consumer = new MockTokenConsumer();
        String xml = "<?xml version=\"1.0\"?><root>Hello, World!</root>";
        tokenize(xml, consumer);

        assertFalse("Tokens should be emitted", consumer.getEvents().isEmpty());
    }

    // --- No XML declaration (from MinimalTokenizerTest) ---

    @Test
    public void testNoXmlDeclaration() throws SAXException {
        MockTokenConsumer consumer = new MockTokenConsumer();
        String xml = "<root/>";
        tokenize(xml, consumer);

        assertFalse("Tokens should be emitted", consumer.getEvents().isEmpty());
    }

    // --- Various constructs (from TestNewTokenizer) ---

    @Test
    public void testSimpleElement() throws SAXException {
        assertTokenizes("<root/>");
    }

    @Test
    public void testElementWithTextContent() throws SAXException {
        assertTokenizes("<root>Hello World</root>");
    }

    @Test
    public void testElementWithMixedQuoteAttributes() throws SAXException {
        assertTokenizes("<root id=\"123\" name='test'/>");
    }

    @Test
    public void testNestedElements() throws SAXException {
        assertTokenizes("<root><child>text</child></root>");
    }

    @Test
    public void testEntityReference() throws SAXException {
        assertTokenizes("<root>Hello &amp; goodbye</root>");
    }

    @Test
    public void testComment() throws SAXException {
        assertTokenizes("<root><!-- This is a comment --></root>");
    }

    @Test
    public void testCDATASection() throws SAXException {
        assertTokenizes("<root><![CDATA[<xml>&test;</xml>]]></root>");
    }

    @Test
    public void testProcessingInstruction() throws SAXException {
        assertTokenizes("<root><?target data?></root>");
    }

    @Test
    public void testSmallChunkBuffer() throws SAXException {
        CountingConsumer consumer = new CountingConsumer();
        Tokenizer tokenizer = new Tokenizer(null, consumer,
                TokenizerState.PROLOG_BEFORE_DOCTYPE, false);
        ExternalEntityDecoder decoder = new ExternalEntityDecoder(
                tokenizer, null, "test.xml", false);

        byte[] bytes = "<root><child>text</child></root>"
                .getBytes(StandardCharsets.UTF_8);
        int chunkSize = 3;
        for (int i = 0; i < bytes.length; i += chunkSize) {
            int len = Math.min(chunkSize, bytes.length - i);
            ByteBuffer buf = ByteBuffer.wrap(bytes, i, len);
            decoder.receive(buf);
        }
        decoder.close();

        assertTrue("Tokens should be emitted with small-chunk feed",
                consumer.getTokenCount() > 0);
    }

    // --- XML declaration with encoding (from TestXMLDeclWithEncoding) ---

    @Test
    public void testXmlDeclWithEncoding() throws SAXException {
        RecordingConsumer consumer = new RecordingConsumer();
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<root/>";
        tokenize(xml, consumer);

        assertFalse("Tokens should be emitted", consumer.tokens.isEmpty());
    }

    // --- Entity reference tokens (from EntityRefTokenTest) ---

    @Test
    public void testGeneralEntityRefToken() throws SAXException {
        RecordingConsumer consumer = new RecordingConsumer();
        String xml = "<root attr='value &myent; more'>&another;</root>";
        tokenize(xml, consumer);

        int count = 0;
        for (int i = 0; i < consumer.tokens.size(); i++) {
            if (consumer.tokens.get(i) == Token.GENERALENTITYREF) {
                count++;
            }
        }
        assertEquals("Should emit 2 GENERALENTITYREF tokens", 2, count);
    }

    @Test
    public void testParameterEntityRefToken() throws SAXException {
        RecordingConsumer consumer = new RecordingConsumer();
        String xml = "<!DOCTYPE root [\n"
                + "  <!ENTITY % param1 \"replacement\">\n"
                + "  %param1;\n"
                + "]>\n"
                + "<root/>";
        tokenize(xml, consumer);

        int count = 0;
        for (int i = 0; i < consumer.tokens.size(); i++) {
            if (consumer.tokens.get(i) == Token.PARAMETERENTITYREF) {
                count++;
            }
        }
        assertEquals("Should emit 1 PARAMETERENTITYREF token", 1, count);
    }

    @Test
    public void testCharacterRefToken() throws SAXException {
        RecordingConsumer consumer = new RecordingConsumer();
        String xml = "<root>&#65; &#x42;</root>";
        tokenize(xml, consumer);

        int charRefCount = 0;
        int generalRefCount = 0;
        for (int i = 0; i < consumer.tokens.size(); i++) {
            Token t = consumer.tokens.get(i);
            if (t == Token.CHARENTITYREF) {
                charRefCount++;
            } else if (t == Token.GENERALENTITYREF) {
                generalRefCount++;
            }
        }
        assertEquals("Should emit 2 CHARENTITYREF tokens", 2, charRefCount);
        assertEquals("Character refs should not emit GENERALENTITYREF",
                0, generalRefCount);
    }

    @Test
    public void testPredefinedEntityRefToken() throws SAXException {
        RecordingConsumer consumer = new RecordingConsumer();
        String xml = "<root>&amp; &lt; &gt; &apos; &quot;</root>";
        tokenize(xml, consumer);

        int predefCount = 0;
        int generalRefCount = 0;
        List<String> replacements = new ArrayList<String>();

        for (int i = 0; i < consumer.tokens.size(); i++) {
            Token t = consumer.tokens.get(i);
            if (t == Token.PREDEFENTITYREF) {
                replacements.add(consumer.data.get(i));
                predefCount++;
            } else if (t == Token.GENERALENTITYREF) {
                generalRefCount++;
            }
        }
        assertEquals("Should emit 5 PREDEFENTITYREF tokens", 5, predefCount);
        assertEquals("Predefined entities should not emit GENERALENTITYREF",
                0, generalRefCount);

        String expected = "& < > ' \"";
        StringBuilder actual = new StringBuilder();
        for (int i = 0; i < replacements.size(); i++) {
            if (i > 0) {
                actual.append(" ");
            }
            actual.append(replacements.get(i));
        }
        assertEquals("Replacement text should match", expected, actual.toString());
    }

    // --- Helpers ---

    private void tokenize(String xml, TokenConsumer consumer)
            throws SAXException {
        Tokenizer tokenizer = new Tokenizer(null, consumer,
                TokenizerState.PROLOG_BEFORE_DOCTYPE, false);
        ExternalEntityDecoder decoder = new ExternalEntityDecoder(
                tokenizer, null, "test.xml", false);
        ByteBuffer buf = ByteBuffer.wrap(xml.getBytes(StandardCharsets.UTF_8));
        decoder.receive(buf);
        decoder.close();
    }

    private void assertTokenizes(String xml) throws SAXException {
        CountingConsumer consumer = new CountingConsumer();
        tokenize(xml, consumer);
        assertTrue("Tokens should be emitted for: " + xml,
                consumer.getTokenCount() > 0);
    }

    // --- Inner test helpers ---

    /**
     * Minimal consumer that counts tokens.
     */
    static class CountingConsumer implements TokenConsumer {
        private int tokenCount = 0;

        @Override
        public void setLocator(Locator locator) {
        }

        @Override
        public void tokenizerState(TokenizerState state) {
        }

        @Override
        public void xmlVersion(boolean isXML11) {
        }

        @Override
        public void receive(Token token, CharBuffer data) throws SAXException {
            tokenCount++;
        }

        @Override
        public SAXException fatalError(String message) throws SAXException {
            throw new SAXParseException(message, null);
        }

        public int getTokenCount() {
            return tokenCount;
        }
    }

    /**
     * Consumer that records all token types and data strings.
     */
    static class RecordingConsumer implements TokenConsumer {
        final List<Token> tokens = new ArrayList<Token>();
        final List<String> data = new ArrayList<String>();

        @Override
        public void setLocator(Locator locator) {
        }

        @Override
        public void tokenizerState(TokenizerState state) {
        }

        @Override
        public void xmlVersion(boolean isXML11) {
        }

        @Override
        public void receive(Token token, CharBuffer charData)
                throws SAXException {
            tokens.add(token);
            if (charData != null) {
                StringBuilder sb = new StringBuilder();
                while (charData.hasRemaining()) {
                    sb.append(charData.get());
                }
                data.add(sb.toString());
            } else {
                data.add(null);
            }
        }

        @Override
        public SAXException fatalError(String message) throws SAXException {
            throw new SAXParseException(message, null);
        }
    }
}

/*
 * ParserBasicTest.java
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

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;
import org.xml.sax.Attributes;
import org.xml.sax.ContentHandler;
import org.xml.sax.InputSource;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.ext.LexicalHandler;
import org.xml.sax.helpers.DefaultHandler;

import static org.junit.Assert.*;

/**
 * Basic tests for Parser: DOCTYPE, comments, and processing instructions.
 * Converted from SimpleCommentTest, SimpleDOCTYPETest, ContentParserCommentPITest,
 * MultipleCommentsTest, and MultiChunkCommentTest.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ParserBasicTest {

    private void parse(String xml, ContentHandler handler, LexicalHandler lexicalHandler) throws Exception {
        Parser parser = new Parser();
        parser.setContentHandler(handler);
        if (lexicalHandler != null) {
            parser.setProperty("http://xml.org/sax/properties/lexical-handler", lexicalHandler);
        }
        ByteArrayInputStream bais = new ByteArrayInputStream(xml.getBytes("UTF-8"));
        InputSource source = new InputSource(bais);
        parser.parse(source);
    }

    // --- From SimpleCommentTest: comment after DOCTYPE name ---

    @Test
    public void testCommentAfterDoctypeName() throws Exception {
        String xml = "<?xml version='1.0'?>\n"
                + "<!DOCTYPE root <!-- comment after name --> >\n"
                + "<root/>";

        RecordingLexicalHandler handler = new RecordingLexicalHandler();
        parse(xml, handler, handler);

        assertEquals("Expected 1 comment", 1, handler.comments.size());
        String expected = " comment after name ";
        String actual = handler.comments.get(0);
        assertEquals("Comment content mismatch", expected, actual);
    }

    // --- From SimpleDOCTYPETest: basic DOCTYPE parsing ---

    @Test
    public void testDoctypeWithoutExternalId() throws Exception {
        String xml = "<!DOCTYPE root><root/>";

        RecordingLexicalHandler handler = new RecordingLexicalHandler();
        parse(xml, handler, handler);

        assertTrue("Parse should complete", handler.endDocumentCalled);
    }

    @Test
    public void testDoctypeWithSystemId() throws Exception {
        String xml = "<!DOCTYPE root SYSTEM \"http://example.com/dtd.dtd\"><root/>";

        Parser parser = new Parser();
        RecordingLexicalHandler handler = new RecordingLexicalHandler();
        parser.setContentHandler(handler);
        parser.setProperty("http://xml.org/sax/properties/lexical-handler", handler);
        parser.setFeature("http://xml.org/sax/features/external-parameter-entities", false);

        ByteArrayInputStream bais = new ByteArrayInputStream(xml.getBytes("UTF-8"));
        InputSource source = new InputSource(bais);
        parser.parse(source);

        assertTrue("Parse should complete", handler.endDocumentCalled);
    }

    // --- From ContentParserCommentPITest: comments and PIs in prolog, content, epilog ---

    @Test
    public void testCommentsAndPIsInPrologContentEpilog() throws Exception {
        String xml = "<?xml version='1.0'?>\n"
                + "<!-- Prolog comment -->\n"
                + "<?xml-stylesheet type=\"text/xsl\" href=\"style.xsl\"?>\n"
                + "<root>\n"
                + "  <!-- Element content comment -->\n"
                + "  <?target data?>\n"
                + "  <child>Text</child>\n"
                + "</root>\n"
                + "<!-- Epilog comment -->\n"
                + "<?epilog-pi test?>";

        RecordingLexicalHandler handler = new RecordingLexicalHandler();
        parse(xml, handler, handler);

        assertEquals("Expected 3 comments", 3, handler.comments.size());
        assertEquals("Expected 3 PIs", 3, handler.pis.size());

        String prologComment = handler.comments.get(0);
        assertEquals("Prolog comment mismatch", " Prolog comment ", prologComment);

        String contentComment = handler.comments.get(1);
        assertEquals("Element content comment mismatch", " Element content comment ", contentComment);

        String epilogComment = handler.comments.get(2);
        assertEquals("Epilog comment mismatch", " Epilog comment ", epilogComment);

        PIPair pi0 = handler.pis.get(0);
        assertEquals("Prolog PI target mismatch", "xml-stylesheet", pi0.target);

        PIPair pi1 = handler.pis.get(1);
        assertEquals("Element content PI target mismatch", "target", pi1.target);

        PIPair pi2 = handler.pis.get(2);
        assertEquals("Epilog PI target mismatch", "epilog-pi", pi2.target);
    }

    // --- From MultipleCommentsTest: multiple comments in DTD ---

    @Test
    public void testMultipleCommentsInDtd() throws Exception {
        String xml = "<?xml version='1.0'?>\n"
                + "<!DOCTYPE root <!-- comment 1 --> [\n"
                + "  <!-- comment 2 -->\n"
                + "] <!-- comment 3 --> >\n"
                + "<root/>";

        RecordingLexicalHandler handler = new RecordingLexicalHandler();
        parse(xml, handler, handler);

        assertEquals("Expected 3 comments", 3, handler.comments.size());
    }

    // --- From MultiChunkCommentTest: comment accumulation across buffer chunks ---

    @Test
    public void testMultiChunkCommentAccumulation() throws Exception {
        String xml = "<!-- This is a long comment that should be accumulated properly --><root/>";

        RecordingLexicalHandler handler = new RecordingLexicalHandler();
        ContentParser xmlParser = new ContentParser();
        Tokenizer tokenizer = new Tokenizer("test.xml", xmlParser, TokenizerState.PROLOG_BEFORE_DOCTYPE, false);
        ExternalEntityDecoder decoder = new ExternalEntityDecoder(tokenizer, null, null, false);
        xmlParser.setContentHandler(handler);
        xmlParser.setLexicalHandler(handler);

        byte[] data = xml.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < data.length; i += 20) {
            int len = Math.min(20, data.length - i);
            ByteBuffer buffer = ByteBuffer.wrap(data, i, len);
            decoder.receive(buffer);
        }
        decoder.close();
        xmlParser.close();

        assertEquals("Expected 1 comment", 1, handler.comments.size());
        String expected = " This is a long comment that should be accumulated properly ";
        String actual = handler.comments.get(0);
        assertEquals("Comment content mismatch", expected, actual);
    }

    // --- Shared LexicalHandler that records comments and PIs ---

    private static class PIPair {
        String target;
        String data;

        PIPair(String target, String data) {
            this.target = target;
            this.data = data;
        }
    }

    private static class RecordingLexicalHandler extends DefaultHandler implements LexicalHandler {
        final List<String> comments = new ArrayList<String>();
        final List<PIPair> pis = new ArrayList<PIPair>();
        boolean endDocumentCalled = false;

        @Override
        public void endDocument() throws SAXException {
            endDocumentCalled = true;
        }

        @Override
        public void processingInstruction(String target, String data) throws SAXException {
            pis.add(new PIPair(target, data));
        }

        @Override
        public void comment(char[] ch, int start, int length) throws SAXException {
            String text = new String(ch, start, length);
            comments.add(text);
        }

        @Override
        public void startDTD(String name, String publicId, String systemId) throws SAXException {
        }

        @Override
        public void endDTD() throws SAXException {
        }

        @Override
        public void startEntity(String name) throws SAXException {
        }

        @Override
        public void endEntity(String name) throws SAXException {
        }

        @Override
        public void startCDATA() throws SAXException {
        }

        @Override
        public void endCDATA() throws SAXException {
        }
    }
}

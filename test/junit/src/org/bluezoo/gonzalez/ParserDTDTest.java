/*
 * ParserDTDTest.java
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
import java.io.File;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;
import org.xml.sax.Attributes;
import org.xml.sax.ContentHandler;
import org.xml.sax.DTDHandler;
import org.xml.sax.InputSource;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.ext.DeclHandler;
import org.xml.sax.ext.LexicalHandler;

import static org.junit.Assert.*;

/**
 * JUnit tests for DTD-related parsing: comments, PIs, declarations, ATTLIST, NOTATION.
 *
 * @author Chris Burdess
 */
public class ParserDTDTest {

    private static final String DTD_PARSER_PROPERTY = "http://www.nongnu.org/gonzalez/properties/dtd-parser";

    private Parser createParser(ContentHandler contentHandler, LexicalHandler lexicalHandler,
            DeclHandler declHandler, DTDHandler dtdHandler) throws SAXException {
        Parser parser = new Parser();
        parser.setContentHandler(contentHandler);
        if (lexicalHandler != null) {
            parser.setProperty("http://xml.org/sax/properties/lexical-handler", lexicalHandler);
        }
        if (declHandler != null) {
            parser.setProperty("http://xml.org/sax/properties/declaration-handler", declHandler);
        }
        if (dtdHandler != null) {
            parser.setDTDHandler(dtdHandler);
        }
        return parser;
    }

    // --- DTD Comment Tests (from DTDCommentTest) ---

    @Test
    public void testSimpleDTDComment() throws Exception {
        String xml = "<?xml version='1.0'?>\n" +
                    "<!DOCTYPE root [\n" +
                    "  <!-- This is a comment -->\n" +
                    "  <!ELEMENT root (#PCDATA)>\n" +
                    "]>\n" +
                    "<root/>";

        CommentCaptureHandler handler = new CommentCaptureHandler();
        Parser parser = createParser(handler, handler, null, null);
        parser.parse(new InputSource(new ByteArrayInputStream(xml.getBytes("UTF-8"))));

        assertEquals("Expected 1 comment", 1, handler.comments.size());
        String comment = handler.comments.get(0);
        assertEquals("Wrong comment text", " This is a comment ", comment);
    }

    @Test
    public void testMultipleDTDComments() throws Exception {
        String xml = "<?xml version='1.0'?>\n" +
                    "<!DOCTYPE root [\n" +
                    "  <!-- First comment -->\n" +
                    "  <!ELEMENT root (child)>\n" +
                    "  <!-- Second comment -->\n" +
                    "  <!ELEMENT child (#PCDATA)>\n" +
                    "  <!-- Third comment -->\n" +
                    "]>\n" +
                    "<root><child>Text</child></root>";

        CommentCaptureHandler handler = new CommentCaptureHandler();
        Parser parser = createParser(handler, handler, null, null);
        parser.parse(new InputSource(new ByteArrayInputStream(xml.getBytes("UTF-8"))));

        assertEquals("Expected 3 comments", 3, handler.comments.size());
        assertEquals("First comment", " First comment ", handler.comments.get(0));
        assertEquals("Second comment", " Second comment ", handler.comments.get(1));
        assertEquals("Third comment", " Third comment ", handler.comments.get(2));
    }

    @Test
    public void testLongDTDComment() throws Exception {
        StringBuilder longCommentText = new StringBuilder(" This is a very long comment that contains lots of text ");
        for (int i = 0; i < 50; i++) {
            longCommentText.append("and more text ");
        }

        String xml = "<?xml version='1.0'?>\n" +
                    "<!DOCTYPE root [\n" +
                    "  <!--" + longCommentText + "-->\n" +
                    "  <!ELEMENT root (#PCDATA)>\n" +
                    "]>\n" +
                    "<root/>";

        CommentCaptureHandler handler = new CommentCaptureHandler();
        Parser parser = createParser(handler, handler, null, null);
        parser.parse(new InputSource(new ByteArrayInputStream(xml.getBytes("UTF-8"))));

        assertEquals("Expected 1 comment", 1, handler.comments.size());
        String comment = handler.comments.get(0);
        assertEquals("Comment text mismatch", longCommentText.toString(), comment);
    }

    // --- DTD PI Tests (from DTDPITest) ---

    @Test
    public void testDTDProcessingInstruction() throws Exception {
        String xml = "<?xml version='1.0'?>\n" +
                    "<!DOCTYPE root [\n" +
                    "  <?xml-stylesheet type=\"text/xsl\" href=\"style.xsl\"?>\n" +
                    "]>\n" +
                    "<root/>";

        PICaptureHandler handler = new PICaptureHandler();
        Parser parser = createParser(handler, null, null, null);
        parser.parse(new InputSource(new ByteArrayInputStream(xml.getBytes("UTF-8"))));

        assertEquals("Expected 1 PI", 1, handler.pis.size());
        PI pi = handler.pis.get(0);
        assertEquals("xml-stylesheet", pi.target);
        assertTrue("PI data should contain text/xsl", pi.data.contains("text/xsl"));
        assertTrue("PI data should contain style.xsl", pi.data.contains("style.xsl"));
    }

    // --- DTD Declarations Tests (from DTDDeclarationsTest) ---

    @Test
    public void testElementDeclarations() throws Exception {
        String xml = "<!DOCTYPE root [\n" +
                     "  <!ELEMENT root (title, body)>\n" +
                     "  <!ELEMENT title (#PCDATA)>\n" +
                     "  <!ELEMENT body (para+)>\n" +
                     "  <!ELEMENT para (#PCDATA)>\n" +
                     "]>\n" +
                     "<root><title>Test</title><body><para>Hello</para></body></root>";

        ContentHandler contentHandler = new MinimalContentHandler();
        LexicalHandler lexicalHandler = new MinimalLexicalHandler();
        Parser parser = createParser(contentHandler, lexicalHandler, null, null);
        parser.parse(new InputSource(new ByteArrayInputStream(xml.getBytes())));

        DTDParser dtdParser = (DTDParser) parser.getProperty(DTD_PARSER_PROPERTY);
        assertNotNull("DTDParser not available", dtdParser);

        ElementDeclaration rootDecl = dtdParser.getElementDeclaration("root");
        assertNotNull("root element declaration not found", rootDecl);

        ElementDeclaration titleDecl = dtdParser.getElementDeclaration("title");
        assertNotNull("title element declaration not found", titleDecl);
        assertEquals("title should be MIXED", ElementDeclaration.ContentType.MIXED, titleDecl.contentType);

        ElementDeclaration bodyDecl = dtdParser.getElementDeclaration("body");
        assertNotNull("body element declaration not found", bodyDecl);
    }

    @Test
    public void testAttributeDeclarations() throws Exception {
        String xml = "<!DOCTYPE doc [\n" +
                     "  <!ELEMENT doc EMPTY>\n" +
                     "  <!ATTLIST doc\n" +
                     "    id ID #REQUIRED\n" +
                     "    type CDATA #IMPLIED\n" +
                     "    version CDATA \"1.0\">\n" +
                     "]>\n" +
                     "<doc id=\"test\" type=\"sample\"/>";

        ContentHandler contentHandler = new MinimalContentHandler();
        LexicalHandler lexicalHandler = new MinimalLexicalHandler();
        Parser parser = createParser(contentHandler, lexicalHandler, null, null);
        parser.parse(new InputSource(new ByteArrayInputStream(xml.getBytes())));

        DTDParser dtdParser = (DTDParser) parser.getProperty(DTD_PARSER_PROPERTY);
        assertNotNull("DTDParser not available", dtdParser);

        AttributeDeclaration idAttr = dtdParser.getAttributeDeclaration("doc", "id");
        assertNotNull("id attribute declaration not found", idAttr);
        assertEquals("ID", idAttr.type);

        AttributeDeclaration typeAttr = dtdParser.getAttributeDeclaration("doc", "type");
        assertNotNull("type attribute declaration not found", typeAttr);
        assertEquals("CDATA", typeAttr.type);

        AttributeDeclaration versionAttr = dtdParser.getAttributeDeclaration("doc", "version");
        assertNotNull("version attribute declaration not found", versionAttr);
        assertNotNull("version default value", versionAttr.defaultValue);
        assertEquals("version default size", 1, versionAttr.defaultValue.size());
        String defaultValueStr = versionAttr.defaultValue.get(0).toString();
        assertEquals("1.0", defaultValueStr);
    }

    @Test
    public void testMixedDeclarations() throws Exception {
        String xml = "<!DOCTYPE book [\n" +
                     "  <!ELEMENT book (chapter+)>\n" +
                     "  <!ATTLIST book title CDATA #REQUIRED>\n" +
                     "  <!ELEMENT chapter (#PCDATA)>\n" +
                     "  <!ATTLIST chapter number NMTOKEN #REQUIRED>\n" +
                     "]>\n" +
                     "<book title=\"My Book\"><chapter number=\"1\">Content</chapter></book>";

        ContentHandler contentHandler = new MinimalContentHandler();
        LexicalHandler lexicalHandler = new MinimalLexicalHandler();
        Parser parser = createParser(contentHandler, lexicalHandler, null, null);
        parser.parse(new InputSource(new ByteArrayInputStream(xml.getBytes())));

        DTDParser dtdParser = (DTDParser) parser.getProperty(DTD_PARSER_PROPERTY);
        assertNotNull("DTDParser not available", dtdParser);

        assertNotNull("book element not found", dtdParser.getElementDeclaration("book"));
        assertNotNull("book/@title not found", dtdParser.getAttributeDeclaration("book", "title"));
        assertNotNull("chapter element not found", dtdParser.getElementDeclaration("chapter"));
        assertNotNull("chapter/@number not found", dtdParser.getAttributeDeclaration("chapter", "number"));
    }

    @Test
    public void testExternalDTDDeclarations() throws Exception {
        String dtdContent = "<!ELEMENT article (title, author, body)>\n" +
                            "<!ELEMENT title (#PCDATA)>\n" +
                            "<!ELEMENT author (#PCDATA)>\n" +
                            "<!ELEMENT body (#PCDATA)>\n" +
                            "<!ATTLIST article lang NMTOKEN #IMPLIED>\n" +
                            "<!ATTLIST article status CDATA \"draft\">\n";
        File dtdFile = new File("test-article.dtd").getAbsoluteFile();
        FileWriter dtdWriter = new FileWriter(dtdFile);
        try {
            dtdWriter.write(dtdContent);
        } finally {
            dtdWriter.close();
        }

        String xml = "<!DOCTYPE article SYSTEM \"test-article.dtd\">\n" +
                     "<article lang=\"en\" status=\"draft\">" +
                     "<title>Title</title>" +
                     "<author>Author</author>" +
                     "<body>Body</body>" +
                     "</article>";
        File xmlFile = new File("test-article.xml").getAbsoluteFile();
        FileWriter xmlWriter = new FileWriter(xmlFile);
        try {
            xmlWriter.write(xml);
        } finally {
            xmlWriter.close();
        }

        try {
            ContentHandler contentHandler = new MinimalContentHandler();
            LexicalHandler lexicalHandler = new MinimalLexicalHandler();
            Parser parser = createParser(contentHandler, lexicalHandler, null, null);
            parser.setFeature("http://xml.org/sax/features/external-parameter-entities", true);
            parser.setProperty("http://javax.xml.XMLConstants/property/accessExternalDTD", "file");
            parser.parse(xmlFile.toURI().toString());

            DTDParser dtdParser = (DTDParser) parser.getProperty(DTD_PARSER_PROPERTY);
            assertNotNull("DTDParser not available", dtdParser);

            ElementDeclaration articleDecl = dtdParser.getElementDeclaration("article");
            assertNotNull("article element not found", articleDecl);

            AttributeDeclaration langAttr = dtdParser.getAttributeDeclaration("article", "lang");
            assertNotNull("article/@lang not found", langAttr);

            AttributeDeclaration statusAttr = dtdParser.getAttributeDeclaration("article", "status");
            assertNotNull("article/@status not found", statusAttr);
        } finally {
            dtdFile.delete();
            xmlFile.delete();
        }
    }

    // --- Quick ATTLIST Tests (from QuickATTLISTTest) ---

    @Test
    public void testQuickATTLISTParsing() throws Exception {
        String xml = "<!DOCTYPE doc [\n" +
                     "  <!ELEMENT doc EMPTY>\n" +
                     "  <!ATTLIST doc\n" +
                     "    id ID #REQUIRED\n" +
                     "    type CDATA #IMPLIED>\n" +
                     "]>\n" +
                     "<doc id=\"test\" type=\"sample\"/>";

        Parser parser = new Parser();
        parser.setContentHandler(new MinimalContentHandler());
        parser.setProperty("http://xml.org/sax/properties/lexical-handler", new MinimalLexicalHandler());
        parser.parse(new InputSource(new ByteArrayInputStream(xml.getBytes())));

        DTDParser dtdParser = (DTDParser) parser.getProperty(DTD_PARSER_PROPERTY);
        assertNotNull("DTDParser not available", dtdParser);

        AttributeDeclaration idAttr = dtdParser.getAttributeDeclaration("doc", "id");
        assertNotNull("id attribute not found", idAttr);
        assertEquals("ID", idAttr.type);

        AttributeDeclaration typeAttr = dtdParser.getAttributeDeclaration("doc", "type");
        assertNotNull("type attribute not found", typeAttr);
        assertEquals("CDATA", typeAttr.type);
    }

    // --- NOTATION Declaration Tests (from NotationDeclTest) ---

    @Test
    public void testSystemNotation() throws Exception {
        String xml = "<?xml version='1.0'?>\n" +
                     "<!DOCTYPE root [\n" +
                     "  <!NOTATION gif SYSTEM \"image-gif\">\n" +
                     "]>\n" +
                     "<root/>";

        NotationCaptureHandler handler = new NotationCaptureHandler();
        Parser parser = createParser(new MinimalContentHandler(), null, null, handler);
        parser.parse(new InputSource(new ByteArrayInputStream(xml.getBytes("UTF-8"))));

        assertEquals("Expected 1 notation", 1, handler.notations.size());
        ExternalID extID = handler.notations.get("gif");
        assertNotNull("Notation 'gif' not found", extID);
        assertNull("Expected null publicId for SYSTEM", extID.publicId);
        assertEquals("image-gif", extID.systemId);
    }

    @Test
    public void testPublicNotationWithSystemId() throws Exception {
        String xml = "<?xml version='1.0'?>\n" +
                     "<!DOCTYPE root [\n" +
                     "  <!NOTATION jpeg PUBLIC \"IDGNOTATIONJPEG\" \"jpeg.dtd\">\n" +
                     "]>\n" +
                     "<root/>";

        NotationCaptureHandler handler = new NotationCaptureHandler();
        Parser parser = createParser(new MinimalContentHandler(), null, null, handler);
        parser.parse(new InputSource(new ByteArrayInputStream(xml.getBytes("UTF-8"))));

        ExternalID extID = handler.notations.get("jpeg");
        assertNotNull("Notation 'jpeg' not found", extID);
        assertEquals("IDGNOTATIONJPEG", extID.publicId);
        assertEquals("jpeg.dtd", extID.systemId);
    }

    @Test
    public void testPublicNotationWithoutSystemId() throws Exception {
        String xml = "<?xml version='1.0'?>\n" +
                     "<!DOCTYPE root [\n" +
                     "  <!NOTATION html PUBLIC \"W3CDTDHTML401EN\">\n" +
                     "]>\n" +
                     "<root/>";

        NotationCaptureHandler handler = new NotationCaptureHandler();
        Parser parser = createParser(new MinimalContentHandler(), null, null, handler);
        parser.parse(new InputSource(new ByteArrayInputStream(xml.getBytes("UTF-8"))));

        ExternalID extID = handler.notations.get("html");
        assertNotNull("Notation 'html' not found", extID);
        assertEquals("W3CDTDHTML401EN", extID.publicId);
        assertNull("Expected null systemId", extID.systemId);
    }

    @Test
    public void testMultipleNotations() throws Exception {
        String xml = "<?xml version='1.0'?>\n" +
                     "<!DOCTYPE root [\n" +
                     "  <!NOTATION gif SYSTEM \"image-gif\">\n" +
                     "  <!NOTATION jpeg SYSTEM \"image-jpeg\">\n" +
                     "  <!NOTATION png SYSTEM \"image-png\">\n" +
                     "]>\n" +
                     "<root/>";

        NotationCaptureHandler handler = new NotationCaptureHandler();
        Parser parser = createParser(new MinimalContentHandler(), null, null, handler);
        parser.parse(new InputSource(new ByteArrayInputStream(xml.getBytes("UTF-8"))));

        assertEquals("Expected 3 notations", 3, handler.notations.size());

        String[] names = {"gif", "jpeg", "png"};
        String[] systemIds = {"image-gif", "image-jpeg", "image-png"};

        for (int i = 0; i < names.length; i++) {
            ExternalID extID = handler.notations.get(names[i]);
            assertNotNull("Notation '" + names[i] + "' not found", extID);
            assertEquals("Notation " + names[i] + " systemId", systemIds[i], extID.systemId);
        }
    }

    // --- Inner handler classes ---

    private static class PI {
        String target;
        String data;

        PI(String target, String data) {
            this.target = target;
            this.data = data;
        }
    }

    private static class CommentCaptureHandler implements ContentHandler, LexicalHandler {
        List<String> comments = new ArrayList<String>();

        @Override
        public void comment(char[] ch, int start, int length) {
            comments.add(new String(ch, start, length));
        }

        @Override
        public void startDTD(String name, String publicId, String systemId) {
        }

        @Override
        public void endDTD() {
        }

        @Override
        public void startEntity(String name) {
        }

        @Override
        public void endEntity(String name) {
        }

        @Override
        public void startCDATA() {
        }

        @Override
        public void endCDATA() {
        }

        @Override
        public void setDocumentLocator(Locator locator) {
        }

        @Override
        public void startDocument() {
        }

        @Override
        public void endDocument() {
        }

        @Override
        public void startPrefixMapping(String prefix, String uri) {
        }

        @Override
        public void endPrefixMapping(String prefix) {
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes atts) {
        }

        @Override
        public void endElement(String uri, String localName, String qName) {
        }

        @Override
        public void characters(char[] ch, int start, int length) {
        }

        @Override
        public void ignorableWhitespace(char[] ch, int start, int length) {
        }

        @Override
        public void processingInstruction(String target, String data) {
        }

        @Override
        public void skippedEntity(String name) {
        }
    }

    private static class PICaptureHandler implements ContentHandler {
        List<PI> pis = new ArrayList<PI>();

        @Override
        public void processingInstruction(String target, String data) {
            pis.add(new PI(target, data));
        }

        @Override
        public void setDocumentLocator(Locator locator) {
        }

        @Override
        public void startDocument() {
        }

        @Override
        public void endDocument() {
        }

        @Override
        public void startPrefixMapping(String prefix, String uri) {
        }

        @Override
        public void endPrefixMapping(String prefix) {
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes atts) {
        }

        @Override
        public void endElement(String uri, String localName, String qName) {
        }

        @Override
        public void characters(char[] ch, int start, int length) {
        }

        @Override
        public void ignorableWhitespace(char[] ch, int start, int length) {
        }

        @Override
        public void skippedEntity(String name) {
        }
    }

    private static class NotationCaptureHandler implements DTDHandler {
        Map<String, ExternalID> notations = new HashMap<String, ExternalID>();

        @Override
        public void notationDecl(String name, String publicId, String systemId) {
            notations.put(name, new ExternalID(publicId, systemId));
        }

        @Override
        public void unparsedEntityDecl(String name, String publicId, String systemId, String notationName) {
        }
    }

    private static class MinimalContentHandler implements ContentHandler {
        @Override
        public void setDocumentLocator(Locator locator) {
        }

        @Override
        public void startDocument() {
        }

        @Override
        public void endDocument() {
        }

        @Override
        public void startPrefixMapping(String prefix, String uri) {
        }

        @Override
        public void endPrefixMapping(String prefix) {
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes atts) {
        }

        @Override
        public void endElement(String uri, String localName, String qName) {
        }

        @Override
        public void characters(char[] ch, int start, int length) {
        }

        @Override
        public void ignorableWhitespace(char[] ch, int start, int length) {
        }

        @Override
        public void processingInstruction(String target, String data) {
        }

        @Override
        public void skippedEntity(String name) {
        }
    }

    private static class MinimalLexicalHandler implements LexicalHandler {
        @Override
        public void startDTD(String name, String publicId, String systemId) {
        }

        @Override
        public void endDTD() {
        }

        @Override
        public void startEntity(String name) {
        }

        @Override
        public void endEntity(String name) {
        }

        @Override
        public void startCDATA() {
        }

        @Override
        public void endCDATA() {
        }

        @Override
        public void comment(char[] ch, int start, int length) {
        }
    }
}

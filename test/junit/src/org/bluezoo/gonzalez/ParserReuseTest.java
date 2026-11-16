/*
 * ParserReuseTest.java
 * Copyright (C) 2026 Chris Burdess
 */
package org.bluezoo.gonzalez;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXParseException;
import org.xml.sax.ext.DefaultHandler2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * A parser used for one document after another keeps the memory its scanner
 * works in. Nothing of one document may show in the next: each must be
 * reported exactly as a parser made for it alone would report it, whatever
 * came before it, including a document that was not well-formed and one
 * that made the buffers grow.
 */
public class ParserReuseTest {

    /** Everything the parser reports, as one line each, text coalesced. */
    private static final class Log extends DefaultHandler2 {
        final List<String> events = new ArrayList<String>();
        private final StringBuilder text = new StringBuilder();

        private void flush() {
            if (text.length() > 0) {
                events.add("text:" + text);
                text.setLength(0);
            }
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes atts) {
            flush();
            StringBuilder sb = new StringBuilder("start:").append(uri).append('|').append(localName)
                    .append('|').append(qName);
            for (int i = 0; i < atts.getLength(); i++) {
                sb.append(' ').append(atts.getQName(i)).append('=').append(atts.getValue(i));
            }
            events.add(sb.toString());
        }

        @Override
        public void endElement(String uri, String localName, String qName) {
            flush();
            events.add("end:" + qName);
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            text.append(ch, start, length);
        }

        @Override
        public void comment(char[] ch, int start, int length) {
            flush();
            events.add("comment:" + new String(ch, start, length));
        }

        @Override
        public void processingInstruction(String target, String data) {
            flush();
            events.add("pi:" + target + " " + data);
        }

        @Override
        public void startCDATA() {
            flush();
            events.add("cdata");
        }

        @Override
        public void startPrefixMapping(String prefix, String uri) {
            flush();
            events.add("ns:" + prefix + "=" + uri);
        }
    }

    private static String letters(int length) {
        char[] chars = new char[length];
        for (int i = 0; i < length; i++) {
            chars[i] = (char) ('a' + (i % 26));
        }
        return new String(chars);
    }

    private static String nested(int depth) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < depth; i++) {
            sb.append("<d").append(i % 7).append('>');
        }
        for (int i = depth - 1; i >= 0; i--) {
            sb.append("</d").append(i % 7).append('>');
        }
        return sb.toString();
    }

    /** Well-formed documents, unlike one another in what they make the scanner hold. */
    private static List<String> documents() {
        List<String> docs = new ArrayList<String>();
        docs.add("<a/>");
        docs.add("<?xml version=\"1.0\"?><r xmlns=\"urn:x\" xmlns:p=\"urn:p\"><p:i p:k=\"v\" j=\"w\">t</p:i></r>");
        docs.add("<!DOCTYPE r [<!ENTITY e \"entity text\"><!ENTITY f \"&e; again\">]>"
                + "<r a=\"&f;\">&e; and &f;<!-- c --><?t d?><![CDATA[<raw>]]></r>");
        docs.add("<r><" + letters(4000) + " " + letters(3999) + "=\"x\">y</" + letters(4000) + "></r>");
        docs.add("<r>" + nested(900) + "</r>");
        StringBuilder many = new StringBuilder("<catalog>");
        for (int i = 0; i < 300; i++) {
            many.append("<item").append(i).append(" id=\"").append(i).append("\" k").append(i)
                    .append("=\"v\">").append(letters(i)).append("</item").append(i).append('>');
        }
        docs.add(many.append("</catalog>").toString());
        docs.add("<r>" + letters(200000) + "</r>");
        // A declaration large enough to grow the scan buffer past what is kept.
        docs.add("<!DOCTYPE r [<!ENTITY big \"" + letters(150000) + "\">]><r>&big;</r>");
        docs.add("<r a=\"\u00e9\u3042\">\u00e9\u3042\ud83d\ude00</r>");
        docs.add("<a><b/><c/><b/><c/><b/><c/></a>");
        return docs;
    }

    private static List<String> malformed() {
        List<String> docs = new ArrayList<String>();
        docs.add("<r><unclosed></r>");
        docs.add("<!DOCTYPE r [<!ENTITY e \"x\">]><r>&e;&undefined;</r>");
        docs.add("<r a=\"1\" a=\"2\"/>");
        docs.add("<r>" + letters(50000) + "<");
        docs.add("<!DOCTYPE r [<!ENTITY big \"" + letters(100000) + "\">]><r>&big;</oops>");
        return docs;
    }

    private static byte[] bytes(String xml) {
        return xml.getBytes(StandardCharsets.UTF_8);
    }

    private static List<String> parse(Parser parser, String xml) throws Exception {
        Log log = new Log();
        parser.setContentHandler(log);
        parser.setProperty("http://xml.org/sax/properties/lexical-handler", log);
        parser.parse(new InputSource(new ByteArrayInputStream(bytes(xml))));
        return log.events;
    }

    private static Parser newParser() throws Exception {
        Parser parser = new Parser();
        parser.setFeature("http://xml.org/sax/features/namespaces", true);
        return parser;
    }

    private static List<String> fresh(String xml) throws Exception {
        return parse(newParser(), xml);
    }

    @Test
    public void everyDocumentIsReportedAsIfParsedAlone() throws Exception {
        List<String> docs = documents();
        Parser reused = newParser();
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < docs.size(); i++) {
                reused.reset();
                assertEquals("pass " + pass + " document " + i,
                        fresh(docs.get(i)), parse(reused, docs.get(i)));
            }
        }
    }

    @Test
    public void inTheOppositeOrder() throws Exception {
        List<String> docs = documents();
        Parser reused = newParser();
        for (int i = docs.size() - 1; i >= 0; i--) {
            reused.reset();
            assertEquals("document " + i, fresh(docs.get(i)), parse(reused, docs.get(i)));
        }
    }

    @Test
    public void aDocumentAfterOneThatWasNotWellFormed() throws Exception {
        List<String> good = documents();
        List<String> bad = malformed();
        Parser reused = newParser();
        for (int round = 0; round < 2; round++) {
            for (int i = 0; i < bad.size(); i++) {
                reused.reset();
                try {
                    parse(reused, bad.get(i));
                    fail("malformed document " + i + " was accepted");
                } catch (SAXParseException expected) {
                    // The scanner stopped wherever it stopped.
                }
                for (int j = 0; j < good.size(); j++) {
                    reused.reset();
                    assertEquals("after malformed " + i + ", document " + j,
                            fresh(good.get(j)), parse(reused, good.get(j)));
                }
            }
        }
    }

    @Test
    public void whatGrewPastTheOrdinaryIsNotKept() throws Exception {
        XMLParser parser = new XMLParser();
        parser.setHandler(new NullHandler());
        byte[] huge = bytes("<!DOCTYPE r [<!ENTITY big \"" + letters(150000) + "\">]><r>&big;</r>");
        parser.receive(java.nio.ByteBuffer.wrap(huge));
        parser.close();
        assertTrue("the large document should have grown the scan buffer",
                parser.scanBufferCapacity() > 65536);
        parser.reset();

        parser.receive(java.nio.ByteBuffer.wrap(bytes("<a/>")));
        parser.close();
        assertEquals(8192, parser.scanBufferCapacity());
    }

    @Test
    public void theBufferIsTheSameFromOneDocumentToTheNext() throws Exception {
        XMLParser parser = new XMLParser();
        parser.setHandler(new NullHandler());
        parser.receive(java.nio.ByteBuffer.wrap(bytes("<a/>")));
        parser.close();
        int first = parser.scanBufferCapacity();
        parser.reset();
        parser.receive(java.nio.ByteBuffer.wrap(bytes("<b><c/></b>")));
        parser.close();
        assertEquals(first, parser.scanBufferCapacity());
    }

    /** Takes in everything and reports nothing. */
    private static final class NullHandler implements XMLHandler {
        @Override public void setLocator(org.xml.sax.Locator locator) { }
        @Override public void setXml11(boolean xml11) { }
        @Override public void startDocument() { }
        @Override public void endDocument() { }
        @Override public void startElement(String qName) { }
        @Override public void namespace(String prefix, String uri) { }
        @Override public void startAttribute(String name, String type, boolean declared,
                boolean specified) { }
        @Override public void attributeValueContent(java.nio.CharBuffer value, boolean end) { }
        @Override public void endAttributes() { }
        @Override public void characters(java.nio.CharBuffer text, boolean ignorable, boolean end) { }
        @Override public void endElement() { }
        @Override public void startComment() { }
        @Override public void commentData(java.nio.CharBuffer text, boolean end) { }
        @Override public void startCDATA() { }
        @Override public void endCDATA() { }
        @Override public void startDTD(String name, String publicId, String systemId) { }
        @Override public void endDTD() { }
        @Override public void startEntity(String name) { }
        @Override public void endEntity(String name) { }
        @Override public void notationDecl(String name, String publicId, String systemId) { }
        @Override public void unparsedEntityDecl(String name, String publicId, String systemId,
                String notationName) { }
        @Override public void piTarget(String target) { }
        @Override public void piData(java.nio.CharBuffer data, boolean end) { }
        @Override public void saveBuffers() { }
        @Override public void error(String message) { }
        @Override public org.xml.sax.SAXException fatalError(String message) {
            return new org.xml.sax.SAXException(message);
        }
    }
}

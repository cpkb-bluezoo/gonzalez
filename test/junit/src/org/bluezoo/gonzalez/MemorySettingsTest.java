/*
 * MemorySettingsTest.java
 * Copyright (C) 2026 Chris Burdess
 */
package org.bluezoo.gonzalez;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import org.junit.Test;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXNotRecognizedException;
import org.xml.sax.SAXNotSupportedException;
import org.xml.sax.helpers.DefaultHandler;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/**
 * What the parser allocates is set by the caller: the size of each buffer is
 * a setting, with a default, and it makes no difference to what is reported.
 */
public class MemorySettingsTest {

    /** Everything reported, as one string. */
    private static final class Record extends DefaultHandler {
        final StringBuilder out = new StringBuilder();

        @Override
        public void startElement(String uri, String localName, String qName, Attributes atts) {
            out.append('<').append(qName);
            for (int i = 0; i < atts.getLength(); i++) {
                out.append(' ').append(atts.getQName(i)).append('=').append(atts.getValue(i));
            }
            out.append('>');
        }

        @Override
        public void endElement(String uri, String localName, String qName) {
            out.append("</").append(qName).append('>');
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            out.append(ch, start, length);
        }
    }

    private static String letters(int length) {
        char[] chars = new char[length];
        for (int i = 0; i < length; i++) {
            chars[i] = (char) ('a' + (i % 26));
        }
        return new String(chars);
    }

    /** A document with many names, long text, multibyte text and astral characters. */
    private static String document() {
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><catalog>");
        for (int i = 0; i < 400; i++) {
            sb.append("<item").append(i).append(" id=\"").append(i).append("\" name").append(i)
                    .append("=\"v\u00e9\">").append(letters(i * 3)).append("\u3042\ud83d\ude00\u00e9")
                    .append("</item").append(i).append('>');
        }
        sb.append("<long>").append(letters(100000)).append("\ud83d\ude00</long>");
        sb.append("<").append(letters(3000)).append("/>");
        return sb.append("</catalog>").toString();
    }

    private static byte[] bytes(String xml) {
        return xml.getBytes(StandardCharsets.UTF_8);
    }

    private static String reported(Parser parser, byte[] xml) throws Exception {
        Record record = new Record();
        parser.setContentHandler(record);
        parser.parse(new InputSource(new ByteArrayInputStream(xml)));
        return record.out.toString();
    }

    private static Parser parserWith(String property, int value) throws Exception {
        Parser parser = new Parser();
        parser.setFeature("http://xml.org/sax/features/namespaces", true);
        parser.setProperty("http://www.nongnu.org/gonzalez/properties/" + property, Integer.valueOf(value));
        return parser;
    }

    private static final String DEFAULT_REPORT;

    static {
        try {
            Parser parser = new Parser();
            parser.setFeature("http://xml.org/sax/features/namespaces", true);
            DEFAULT_REPORT = reported(parser, bytes(document()));
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @Test
    public void theDefaultsAreTheDocumentedOnes() {
        XMLParser parser = new XMLParser();
        assertEquals(8192, parser.getScanBufferSize());
        assertEquals(32768, parser.getDecodeBufferSize());
        assertEquals(512, parser.getNameTableSize());
        assertEquals(32768, parser.getInputBufferSize());
    }

    @Test
    public void theSmallestDecodeBufferReportsTheSame() throws Exception {
        assertEquals(DEFAULT_REPORT, reported(parserWith("decode-buffer-size", 16), bytes(document())));
    }

    @Test
    public void aSmallDecodeBufferWithTheDocumentArrivingInPieces() throws Exception {
        for (int size : new int[] { 16, 17, 31, 100 }) {
            Record record = new Record();
            Parser pushed = parserWith("decode-buffer-size", size);
            pushed.setContentHandler(record);
            byte[] xml = bytes(document());
            ByteBuffer buffer = ByteBuffer.allocate(1031);
            int offset = 0;
            while (offset < xml.length) {
                int n = Math.min(1013, xml.length - offset);
                buffer.put(xml, offset, n);
                offset += n;
                buffer.flip();
                pushed.receive(buffer);
                buffer.compact();
            }
            pushed.close();
            assertEquals("decode buffer " + size, DEFAULT_REPORT, record.out.toString());
        }
    }

    @Test
    public void theSmallestScanBufferReportsTheSame() throws Exception {
        assertEquals(DEFAULT_REPORT, reported(parserWith("scan-buffer-size", 256), bytes(document())));
    }

    @Test
    public void theSmallestNameTableReportsTheSame() throws Exception {
        assertEquals(DEFAULT_REPORT, reported(parserWith("name-table-size", 16), bytes(document())));
    }

    @Test
    public void theSmallestInputBufferReportsTheSame() throws Exception {
        assertEquals(DEFAULT_REPORT, reported(parserWith("input-buffer-size", 16), bytes(document())));
    }

    @Test
    public void largeBuffersReportTheSame() throws Exception {
        Parser parser = parserWith("decode-buffer-size", 1 << 20);
        parser.setProperty("http://www.nongnu.org/gonzalez/properties/scan-buffer-size", Integer.valueOf(1 << 20));
        parser.setProperty("http://www.nongnu.org/gonzalez/properties/input-buffer-size", Integer.valueOf(1 << 20));
        parser.setProperty("http://www.nongnu.org/gonzalez/properties/name-table-size", Integer.valueOf(1 << 14));
        assertEquals(DEFAULT_REPORT, reported(parser, bytes(document())));
    }

    @Test
    public void theSizesAreTheOnesAsked() throws Exception {
        XMLParser parser = new XMLParser();
        parser.setHandler(new NoHandler());
        parser.setScanBufferSize(1024);
        parser.setDecodeBufferSize(300);
        parser.setNameTableSize(100);
        parser.receive(ByteBuffer.wrap(bytes("<a b=\"c\"/>")));
        parser.close();
        assertEquals(1024, parser.scanBufferCapacity());
        assertEquals(300, parser.decodeBufferCapacity());
        // Rounded up to a power of two.
        assertEquals(128, parser.nameTableCapacity());
    }

    @Test
    public void whatGrewIsPutBackToTheSizeAsked() throws Exception {
        XMLParser parser = new XMLParser();
        parser.setHandler(new NoHandler());
        parser.setScanBufferSize(512);
        byte[] big = bytes("<!DOCTYPE r [<!ENTITY big \"" + letters(100000) + "\">]><r>&big;</r>");
        parser.receive(ByteBuffer.wrap(big));
        parser.close();
        if (parser.scanBufferCapacity() <= 512) {
            fail("the large declaration should have made the scan buffer grow");
        }
        parser.reset();
        parser.receive(ByteBuffer.wrap(bytes("<a/>")));
        parser.close();
        assertEquals(512, parser.scanBufferCapacity());
    }

    @Test
    public void aSizeChangedBetweenDocumentsTakesEffectOnTheNext() throws Exception {
        XMLParser parser = new XMLParser();
        parser.setHandler(new NoHandler());
        parser.setDecodeBufferSize(200);
        parser.receive(ByteBuffer.wrap(bytes("<a/>")));
        parser.close();
        assertEquals(200, parser.decodeBufferCapacity());
        parser.reset();
        parser.setDecodeBufferSize(5000);
        parser.receive(ByteBuffer.wrap(bytes("<a/>")));
        parser.close();
        assertEquals(5000, parser.decodeBufferCapacity());
    }

    @Test
    public void aSizeTooSmallIsRefused() {
        XMLParser parser = new XMLParser();
        try {
            parser.setScanBufferSize(255);
            fail();
        } catch (IllegalArgumentException expected) {
            // refused
        }
        try {
            parser.setDecodeBufferSize(15);
            fail();
        } catch (IllegalArgumentException expected) {
            // refused
        }
        try {
            parser.setNameTableSize(0);
            fail();
        } catch (IllegalArgumentException expected) {
            // refused
        }
        try {
            parser.setInputBufferSize(-1);
            fail();
        } catch (IllegalArgumentException expected) {
            // refused
        }
        assertEquals(8192, parser.getScanBufferSize());
    }

    @Test
    public void thePropertiesReadBackAndRefuseWhatIsTooSmall() throws Exception {
        Parser parser = new Parser();
        String prefix = "http://www.nongnu.org/gonzalez/properties/";
        parser.setProperty(prefix + "decode-buffer-size", "4096");
        assertEquals(Integer.valueOf(4096), parser.getProperty(prefix + "decode-buffer-size"));
        assertEquals(Integer.valueOf(8192), parser.getProperty(prefix + "scan-buffer-size"));
        assertEquals(Integer.valueOf(512), parser.getProperty(prefix + "name-table-size"));
        assertEquals(Integer.valueOf(32768), parser.getProperty(prefix + "input-buffer-size"));
        try {
            parser.setProperty(prefix + "scan-buffer-size", Integer.valueOf(10));
            fail();
        } catch (SAXNotSupportedException expected) {
            // refused
        }
        try {
            parser.setProperty(prefix + "no-such-thing", Integer.valueOf(10));
            fail();
        } catch (SAXNotRecognizedException expected) {
            // not ours
        }
    }

    /** Takes in everything and reports nothing. */
    private static final class NoHandler implements XMLHandler {
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

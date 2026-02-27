/*
 * ParserLifecycleTest.java
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

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.xml.sax.Attributes;
import org.xml.sax.ContentHandler;
import org.xml.sax.EntityResolver;
import org.xml.sax.InputSource;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.SAXNotRecognizedException;
import org.xml.sax.SAXNotSupportedException;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileWriter;

import static org.junit.Assert.*;

/**
 * JUnit tests for Parser lifecycle: reuse, feature flags, SAX2 compliance,
 * and parse(systemId) with EntityResolver.
 *
 * @author Chris Burdess
 */
public class ParserLifecycleTest {

    private File tempFile;

    @Before
    public void setUp() throws Exception {
        tempFile = null;
    }

    @After
    public void tearDown() {
        if (tempFile != null && tempFile.exists()) {
            tempFile.delete();
        }
    }

    // ========== Parser Reuse (from ParserReuseTest) ==========

    @Test
    public void testParserReuseMultipleDocuments() throws Exception {
        Parser parser = new Parser();
        TestHandler handler = new TestHandler();
        parser.setContentHandler(handler);

        String xml1 = "<?xml version=\"1.0\"?><doc1><item>first</item></doc1>";
        ByteArrayInputStream stream1 = new ByteArrayInputStream(xml1.getBytes("UTF-8"));
        InputSource source1 = new InputSource(stream1);
        source1.setSystemId("http://example.com/doc1.xml");
        parser.parse(source1);

        assertEquals("First parse root elements", 1, handler.rootElementCount);
        assertEquals("First parse total elements", 2, handler.totalElementCount);

        parser.reset();
        handler.reset();

        String xml2 = "<?xml version=\"1.0\"?><doc2><a><b>second</b></a></doc2>";
        ByteArrayInputStream stream2 = new ByteArrayInputStream(xml2.getBytes("UTF-8"));
        InputSource source2 = new InputSource(stream2);
        source2.setSystemId("http://example.com/doc2.xml");
        parser.parse(source2);

        assertEquals("Second parse root elements", 1, handler.rootElementCount);
        assertEquals("Second parse total elements", 3, handler.totalElementCount);

        parser.reset();
        handler.reset();

        String xml3 = "<?xml version=\"1.0\"?><doc3/>";
        ByteArrayInputStream stream3 = new ByteArrayInputStream(xml3.getBytes("UTF-8"));
        InputSource source3 = new InputSource(stream3);
        source3.setSystemId("http://example.com/doc3.xml");
        parser.parse(source3);

        assertEquals("Third parse root elements", 1, handler.rootElementCount);
        assertEquals("Third parse total elements", 1, handler.totalElementCount);
    }

    // ========== Feature Flags (from FeatureFlagsTest) ==========

    @Test
    public void testMutableFeatures() throws Exception {
        Parser parser = new Parser();

        boolean namespaces = parser.getFeature("http://xml.org/sax/features/namespaces");
        assertTrue("namespaces should default to true", namespaces);

        parser.setFeature("http://xml.org/sax/features/namespaces", false);
        namespaces = parser.getFeature("http://xml.org/sax/features/namespaces");
        assertFalse("namespaces should be false after setting", namespaces);

        boolean namespacePrefixes = parser.getFeature("http://xml.org/sax/features/namespace-prefixes");
        assertFalse("namespace-prefixes should default to false", namespacePrefixes);

        parser.setFeature("http://xml.org/sax/features/namespace-prefixes", true);
        namespacePrefixes = parser.getFeature("http://xml.org/sax/features/namespace-prefixes");
        assertTrue("namespace-prefixes should be true after setting", namespacePrefixes);

        boolean validation = parser.getFeature("http://xml.org/sax/features/validation");
        assertFalse("validation should default to false", validation);

        parser.setFeature("http://xml.org/sax/features/validation", true);
        validation = parser.getFeature("http://xml.org/sax/features/validation");
        assertTrue("validation should be true after setting", validation);

        boolean externalGeneral = parser.getFeature("http://xml.org/sax/features/external-general-entities");
        assertFalse("external-general-entities should default to false (secure)", externalGeneral);

        boolean externalParameter = parser.getFeature("http://xml.org/sax/features/external-parameter-entities");
        assertFalse("external-parameter-entities should default to false (secure)", externalParameter);

        boolean resolveDTDURIs = parser.getFeature("http://xml.org/sax/features/resolve-dtd-uris");
        assertTrue("resolve-dtd-uris should default to true", resolveDTDURIs);

        boolean stringInterning = parser.getFeature("http://xml.org/sax/features/string-interning");
        assertTrue("string-interning should default to true", stringInterning);

        parser.setFeature("http://xml.org/sax/features/string-interning", false);
        stringInterning = parser.getFeature("http://xml.org/sax/features/string-interning");
        assertFalse("string-interning should be false after setting", stringInterning);

        boolean xml11 = parser.getFeature("http://xml.org/sax/features/xml-1.1");
        assertTrue("xml-1.1 is supported (read-only)", xml11);
    }

    @Test
    public void testReadOnlyFeatures() throws Exception {
        Parser parser = new Parser();

        boolean useAttributes2 = parser.getFeature("http://xml.org/sax/features/use-attributes2");
        assertTrue("use-attributes2 should be true", useAttributes2);

        try {
            parser.setFeature("http://xml.org/sax/features/use-attributes2", false);
            fail("Setting use-attributes2 should throw SAXNotSupportedException");
        } catch (SAXNotSupportedException e) {
        }

        boolean useLocator2 = parser.getFeature("http://xml.org/sax/features/use-locator2");
        assertTrue("use-locator2 should be true", useLocator2);

        boolean useEntityResolver2 = parser.getFeature("http://xml.org/sax/features/use-entity-resolver2");
        assertTrue("use-entity-resolver2 should be true", useEntityResolver2);

        boolean lexicalHandler = parser.getFeature("http://xml.org/sax/features/lexical-handler");
        assertTrue("lexical-handler should be true", lexicalHandler);

        boolean parameterEntities = parser.getFeature("http://xml.org/sax/features/parameter-entities");
        assertTrue("parameter-entities should be true", parameterEntities);

        boolean xmlnsURIs = parser.getFeature("http://xml.org/sax/features/xmlns-uris");
        assertFalse("xmlns-uris should be false", xmlnsURIs);
    }

    @Test
    public void testUnsupportedFeatures() throws Exception {
        Parser parser = new Parser();

        boolean unicodeNorm = parser.getFeature("http://xml.org/sax/features/unicode-normalization-checking");
        assertFalse("unicode-normalization-checking should be false", unicodeNorm);

        try {
            parser.setFeature("http://xml.org/sax/features/unicode-normalization-checking", true);
            fail("Enabling unicode-normalization-checking should throw SAXNotSupportedException");
        } catch (SAXNotSupportedException e) {
        }

        parser.setFeature("http://xml.org/sax/features/unicode-normalization-checking", false);

        try {
            parser.getFeature("http://example.com/nonexistent-feature");
            fail("Getting nonexistent feature should throw SAXNotRecognizedException");
        } catch (SAXNotRecognizedException e) {
        }
    }

    @Test
    public void testFeaturePersistenceAfterReset() throws Exception {
        Parser parser = new Parser();

        parser.setFeature("http://xml.org/sax/features/namespaces", false);
        parser.setFeature("http://xml.org/sax/features/namespace-prefixes", true);
        parser.setFeature("http://xml.org/sax/features/validation", true);

        parser.reset();

        boolean namespaces = parser.getFeature("http://xml.org/sax/features/namespaces");
        assertFalse("namespaces should remain false after reset", namespaces);

        boolean namespacePrefixes = parser.getFeature("http://xml.org/sax/features/namespace-prefixes");
        assertTrue("namespace-prefixes should remain true after reset", namespacePrefixes);

        boolean validation = parser.getFeature("http://xml.org/sax/features/validation");
        assertTrue("validation should remain true after reset", validation);
    }

    // ========== SAX2 Parser (from SAX2ParserTest) ==========

    @Test
    public void testParseWithInputSource() throws Exception {
        String xml = "<?xml version=\"1.0\"?><root id=\"123\"><child>text</child></root>";
        ByteArrayInputStream inputStream = new ByteArrayInputStream(xml.getBytes("UTF-8"));
        InputSource inputSource = new InputSource(inputStream);
        inputSource.setSystemId("http://example.com/test.xml");

        TestHandler handler = new TestHandler();
        Parser parser = new Parser();
        parser.setContentHandler(handler);

        parser.parse(inputSource);

        handler.verify();
    }

    @Test
    public void testDirectBufferAPI() throws Exception {
        String xml = "<?xml version=\"1.0\"?><root><child>data</child></root>";

        TestHandler handler = new TestHandler();
        Parser parser = new Parser();
        parser.setContentHandler(handler);
        parser.setSystemId("http://example.com/test2.xml");

        byte[] bytes = xml.getBytes("UTF-8");
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(32);
        int offset = 0;

        while (true) {
            int bytesToCopy = Math.min(buffer.remaining(), bytes.length - offset);
            if (bytesToCopy > 0) {
                buffer.put(bytes, offset, bytesToCopy);
                offset += bytesToCopy;
            }

            if (buffer.position() > 0) {
                buffer.flip();
                parser.receive(buffer);
                buffer.compact();
            }

            if (offset >= bytes.length && buffer.position() == 0) {
                break;
            }
        }

        parser.close();

        handler.verify();
    }

    // ========== Parse SystemId (from ParseSystemIdTest) ==========

    @Test
    public void testParseSystemIdWithResolver() throws Exception {
        Parser parser = new Parser();
        TestHandler handler = new TestHandler();
        parser.setContentHandler(handler);

        parser.setEntityResolver(new EntityResolver() {
            @Override
            public InputSource resolveEntity(String publicId, String systemId) throws SAXException {
                String xml = "<?xml version=\"1.0\"?><document><title>Test</title></document>";
                ByteArrayInputStream stream = new ByteArrayInputStream(xml.getBytes());
                InputSource source = new InputSource(stream);
                source.setSystemId(systemId);
                return source;
            }
        });

        parser.parse("http://example.com/test.xml");

        assertEquals("Expected 2 elements", 2, handler.elementCount);
    }

    @Test
    public void testParseSystemIdWithTempFile() throws Exception {
        tempFile = File.createTempFile("gonzalez-test", ".xml");
        FileWriter writer = new FileWriter(tempFile);
        writer.write("<?xml version=\"1.0\"?><doc><item>test</item></doc>");
        writer.close();

        Parser parser = new Parser();
        TestHandler handler = new TestHandler();
        parser.setContentHandler(handler);

        String systemId = tempFile.toURI().toString();
        parser.parse(systemId);

        assertEquals("Expected 2 elements", 2, handler.elementCount);
    }

    // ========== Helper classes ==========

    static class TestHandler implements ContentHandler {
        private int elementDepth = 0;
        private int rootElementCount = 0;
        private int totalElementCount = 0;
        private boolean startDocumentCalled = false;
        private boolean endDocumentCalled = false;
        int elementCount = 0;

        void reset() {
            elementDepth = 0;
            rootElementCount = 0;
            totalElementCount = 0;
            startDocumentCalled = false;
            endDocumentCalled = false;
            elementCount = 0;
        }

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
            if (elementDepth != 0) {
                throw new SAXException("Element depth mismatch at endDocument: " + elementDepth);
            }
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes atts) throws SAXException {
            elementDepth++;
            totalElementCount++;
            elementCount++;
            if (elementDepth == 1) {
                rootElementCount++;
            }
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
            if (!startDocumentCalled) {
                throw new SAXException("startDocument() was not called");
            }
            if (!endDocumentCalled) {
                throw new SAXException("endDocument() was not called");
            }
            if (elementDepth != 0) {
                throw new SAXException("Element depth mismatch: " + elementDepth);
            }
            if (totalElementCount == 0 && elementCount == 0) {
                throw new SAXException("No elements were parsed");
            }
        }
    }
}

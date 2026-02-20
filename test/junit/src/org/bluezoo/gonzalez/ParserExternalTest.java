/*
 * ParserExternalTest.java
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
import org.xml.sax.SAXException;
import org.xml.sax.ext.LexicalHandler;
import org.xml.sax.helpers.DefaultHandler;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

import static org.junit.Assert.*;

/**
 * JUnit tests for external DTD loading behavior.
 * Uses temp files for self-contained tests.
 *
 * @author Chris Burdess
 */
public class ParserExternalTest {

    private File dtdFile;
    private File xmlFile;

    @Before
    public void setUp() throws Exception {
        dtdFile = null;
        xmlFile = null;
    }

    @After
    public void tearDown() {
        if (dtdFile != null && dtdFile.exists()) {
            dtdFile.delete();
        }
        if (xmlFile != null && xmlFile.exists()) {
            xmlFile.delete();
        }
    }

    @Test
    public void testExternalDTDSubset() throws Exception {
        String dtdContent = "<!ELEMENT root (title)>\n<!ELEMENT title (#PCDATA)>\n";
        dtdFile = File.createTempFile("gonzalez-external", ".dtd");
        writeFile(dtdFile, dtdContent);

        String dtdName = dtdFile.getName();
        String xml = "<!DOCTYPE root SYSTEM \"" + dtdName + "\">\n" +
                     "<root><title>Test</title></root>";
        xmlFile = new File(dtdFile.getParent(), "gonzalez-doc1.xml");
        writeFile(xmlFile, xml);

        TestHandler handler = new TestHandler();
        Parser parser = new Parser();
        parser.setContentHandler(handler);
        parser.setProperty("http://xml.org/sax/properties/lexical-handler", handler);
        parser.setFeature("http://xml.org/sax/features/external-parameter-entities", true);

        String systemId = xmlFile.toURI().toString();
        parser.parse(systemId);

        assertTrue("DTD subset should be resolved", handler.dtdStartCalled);
    }

    @Test
    public void testExternalDTDWithInternalSubset() throws Exception {
        String dtdContent = "<!ELEMENT root (title)>\n";
        dtdFile = File.createTempFile("gonzalez-external2", ".dtd");
        writeFile(dtdFile, dtdContent);

        String dtdName = dtdFile.getName();
        String xml = "<!DOCTYPE root SYSTEM \"" + dtdName + "\" [\n" +
                     "  <!ELEMENT title (#PCDATA)>\n" +
                     "]>\n" +
                     "<root><title>Test</title></root>";
        xmlFile = new File(dtdFile.getParent(), "gonzalez-doc2.xml");
        writeFile(xmlFile, xml);

        TestHandler handler = new TestHandler();
        Parser parser = new Parser();
        parser.setContentHandler(handler);
        parser.setProperty("http://xml.org/sax/properties/lexical-handler", handler);
        parser.setFeature("http://xml.org/sax/features/external-parameter-entities", true);

        String systemId = xmlFile.toURI().toString();
        parser.parse(systemId);

        assertTrue("External and internal DTD should be processed", handler.dtdStartCalled);
    }

    @Test
    public void testExternalDTDDisabled() throws Exception {
        String dtdContent = "<!ELEMENT root (title)>\n<!ELEMENT title (#PCDATA)>\n";
        dtdFile = File.createTempFile("gonzalez-external3", ".dtd");
        writeFile(dtdFile, dtdContent);

        String dtdName = dtdFile.getName();
        String xml = "<!DOCTYPE root SYSTEM \"" + dtdName + "\">\n" +
                     "<root><title>Test</title></root>";
        xmlFile = new File(dtdFile.getParent(), "gonzalez-doc3.xml");
        writeFile(xmlFile, xml);

        TestHandler handler = new TestHandler();
        Parser parser = new Parser();
        parser.setContentHandler(handler);
        parser.setProperty("http://xml.org/sax/properties/lexical-handler", handler);
        parser.setFeature("http://xml.org/sax/features/external-parameter-entities", false);

        String systemId = xmlFile.toURI().toString();
        parser.parse(systemId);
    }

    private void writeFile(File file, String content) throws IOException {
        FileWriter writer = new FileWriter(file);
        try {
            writer.write(content);
        } finally {
            writer.close();
        }
    }

    private static class TestHandler extends DefaultHandler implements LexicalHandler {
        boolean dtdStartCalled = false;
        boolean dtdEndCalled = false;

        @Override
        public void startElement(String uri, String localName, String qName, Attributes attributes) throws SAXException {
        }

        @Override
        public void endElement(String uri, String localName, String qName) throws SAXException {
        }

        @Override
        public void characters(char[] ch, int start, int length) throws SAXException {
        }

        @Override
        public void startDTD(String name, String publicId, String systemId) throws SAXException {
            dtdStartCalled = true;
        }

        @Override
        public void endDTD() throws SAXException {
            dtdEndCalled = true;
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

        @Override
        public void comment(char[] ch, int start, int length) throws SAXException {
        }
    }
}

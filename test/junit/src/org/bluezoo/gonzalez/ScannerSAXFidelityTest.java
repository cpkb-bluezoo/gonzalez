/*
 * ScannerSAXFidelityTest.java
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
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.ext.Attributes2;
import org.xml.sax.ext.DeclHandler;
import org.xml.sax.ext.Locator2;
import org.xml.sax.helpers.DefaultHandler;

import static org.junit.Assert.*;

/** SAX event and metadata fidelity checks specific to the Scanner pipeline. */
public class ScannerSAXFidelityTest {

    private static final String NAMESPACES = "http://xml.org/sax/features/namespaces";
    private static final String NAMESPACE_PREFIXES = "http://xml.org/sax/features/namespace-prefixes";
    private static final String XMLNS_URIS = "http://xml.org/sax/features/xmlns-uris";
    private static final String DECL_HANDLER = "http://xml.org/sax/properties/declaration-handler";

    private static Parser parser() {
        return new Parser();
    }

    private static void parse(Parser parser, String xml) throws Exception {
        parser.parse(new InputSource(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    public void testNamespaceFeatureMatrix() throws Exception {
        String xml = "<root xmlns='urn:default' xmlns:p='urn:p' p:a='v'/>";
        for (boolean namespaces : new boolean[] { false, true }) {
            for (boolean prefixes : new boolean[] { false, true }) {
                for (boolean xmlnsUris : new boolean[] { false, true }) {
                    NamespaceCapture capture = new NamespaceCapture();
                    Parser parser = parser();
                    parser.setFeature(NAMESPACES, namespaces);
                    parser.setFeature(NAMESPACE_PREFIXES, prefixes);
                    parser.setFeature(XMLNS_URIS, xmlnsUris);
                    parser.setContentHandler(capture);
                    parse(parser, xml);

                    int expectedXmlns = namespaces ? (prefixes ? 2 : 0) : 2;
                    assertEquals(label(namespaces, prefixes, xmlnsUris), expectedXmlns, capture.xmlnsCount);
                    assertEquals(label(namespaces, prefixes, xmlnsUris), namespaces ? 2 : 0,
                            capture.prefixMappings);
                    if (expectedXmlns > 0) {
                        String expectedUri = namespaces && xmlnsUris
                                ? NamespaceScopeTracker.XMLNS_NAMESPACE_URI : "";
                        assertEquals(label(namespaces, prefixes, xmlnsUris), expectedUri, capture.defaultXmlnsUri);
                        assertEquals(label(namespaces, prefixes, xmlnsUris), expectedUri, capture.prefixedXmlnsUri);
                        assertEquals(label(namespaces, prefixes, xmlnsUris),
                                namespaces && xmlnsUris ? "p" : "xmlns:p", capture.prefixedXmlnsLocalName);
                    }
                    assertEquals(label(namespaces, prefixes, xmlnsUris), namespaces ? "urn:p" : "",
                            capture.regularAttributeUri);
                }
            }
        }
    }

    private static String label(boolean namespaces, boolean prefixes, boolean xmlnsUris) {
        return "namespaces=" + namespaces + ", prefixes=" + prefixes + ", xmlnsUris=" + xmlnsUris;
    }

    @Test
    public void testDeclarationHandlerEvents() throws Exception {
        String xml = "<!DOCTYPE root ["
                + "<!ELEMENT root (child|other)>"
                + "<!ELEMENT child EMPTY>"
                + "<!ELEMENT other ANY>"
                + "<!ATTLIST root kind (a|b) #FIXED 'a'>"
                + "<!ENTITY internal 'value'>"
                + "<!ENTITY % parameter 'replacement'>"
                + "<!ENTITY external SYSTEM 'external.xml'>"
                + "<!NOTATION bin SYSTEM 'bin'>"
                + "<!ENTITY unparsed SYSTEM 'data.bin' NDATA bin>"
                + "]><root><child/></root>";
        DeclarationCapture capture = new DeclarationCapture();
        Parser parser = parser();
        parser.setContentHandler(new DefaultHandler());
        parser.setProperty(DECL_HANDLER, capture);
        parse(parser, xml);

        assertTrue(capture.events.contains("element:root:(child | other)"));
        assertTrue(capture.events.contains("attribute:root:kind:(a|b):#FIXED:a"));
        assertTrue(capture.events.contains("internal:internal:value"));
        assertTrue(capture.events.contains("internal:%parameter:replacement"));
        assertTrue(capture.events.contains("external:external:null:external.xml"));
        assertFalse(capture.toString(), capture.containsEntity("unparsed"));
    }

    @Test
    public void testAttributes2DeclaredAndSpecified() throws Exception {
        String xml = "<!DOCTYPE root ["
                + "<!ELEMENT root EMPTY>"
                + "<!ATTLIST root explicit CDATA #IMPLIED defaulted CDATA 'd'>"
                + "]><root explicit='e' undeclared='u'/>";
        AttributeCapture capture = new AttributeCapture();
        Parser parser = parser();
        parser.setContentHandler(capture);
        parse(parser, xml);

        assertTrue(capture.declared("explicit"));
        assertTrue(capture.specified("explicit"));
        assertFalse(capture.declared("undeclared"));
        assertTrue(capture.specified("undeclared"));
        assertTrue(capture.declared("defaulted"));
        assertFalse(capture.specified("defaulted"));
    }

    @Test
    public void testLocator2InstalledBeforeDocumentAndUpdated() throws Exception {
        LocatorCapture capture = new LocatorCapture();
        Parser parser = parser();
        parser.setContentHandler(capture);
        InputSource input = new InputSource(new ByteArrayInputStream(
                "<?xml version='1.1' encoding='UTF-8'?><root/>".getBytes(StandardCharsets.UTF_8)));
        input.setPublicId("public");
        input.setSystemId("urn:test");
        parser.parse(input);

        assertTrue(capture.locatorBeforeStartDocument);
        assertTrue(capture.locator instanceof Locator2);
        Locator2 locator = (Locator2) capture.locator;
        assertEquals("public", locator.getPublicId());
        assertEquals("urn:test", locator.getSystemId());
        assertEquals("1.1", locator.getXMLVersion());
        assertEquals("UTF-8", locator.getEncoding());
    }

    private static final class NamespaceCapture extends DefaultHandler {
        int xmlnsCount;
        int prefixMappings;
        String defaultXmlnsUri;
        String prefixedXmlnsUri;
        String prefixedXmlnsLocalName;
        String regularAttributeUri;

        @Override
        public void startPrefixMapping(String prefix, String uri) {
            prefixMappings++;
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes attributes) {
            for (int i = 0; i < attributes.getLength(); i++) {
                String aqName = attributes.getQName(i);
                if ("xmlns".equals(aqName)) {
                    xmlnsCount++;
                    defaultXmlnsUri = attributes.getURI(i);
                } else if ("xmlns:p".equals(aqName)) {
                    xmlnsCount++;
                    prefixedXmlnsUri = attributes.getURI(i);
                    prefixedXmlnsLocalName = attributes.getLocalName(i);
                } else if ("p:a".equals(aqName)) {
                    regularAttributeUri = attributes.getURI(i);
                }
            }
        }
    }

    private static final class DeclarationCapture implements DeclHandler {
        final List<String> events = new ArrayList<String>();

        @Override
        public void elementDecl(String name, String model) {
            events.add("element:" + name + ":" + model);
        }

        @Override
        public void attributeDecl(String eName, String aName, String type, String mode, String value) {
            events.add("attribute:" + eName + ":" + aName + ":" + type + ":" + mode + ":" + value);
        }

        @Override
        public void internalEntityDecl(String name, String value) {
            events.add("internal:" + name + ":" + value);
        }

        @Override
        public void externalEntityDecl(String name, String publicId, String systemId) {
            events.add("external:" + name + ":" + publicId + ":" + systemId);
        }

        boolean containsEntity(String name) {
            for (String event : events) {
                if (event.startsWith("internal:" + name + ":") || event.startsWith("external:" + name + ":")) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public String toString() {
            return events.toString();
        }
    }

    private static final class AttributeCapture extends DefaultHandler {
        Attributes2 attributes;

        @Override
        public void startElement(String uri, String localName, String qName, Attributes attributes) {
            this.attributes = (Attributes2) attributes;
        }

        boolean declared(String name) {
            return attributes.isDeclared(name);
        }

        boolean specified(String name) {
            return attributes.isSpecified(name);
        }
    }

    private static final class LocatorCapture extends DefaultHandler {
        Locator locator;
        boolean locatorBeforeStartDocument;

        @Override
        public void setDocumentLocator(Locator locator) {
            this.locator = locator;
        }

        @Override
        public void startDocument() throws SAXException {
            locatorBeforeStartDocument = locator != null;
        }
    }
}

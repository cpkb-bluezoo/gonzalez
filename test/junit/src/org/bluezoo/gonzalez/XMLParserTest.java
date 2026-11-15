/*
 * XMLParserTest.java
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
 */

package org.bluezoo.gonzalez;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;
import org.xml.sax.EntityResolver;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Tests {@link XMLParser}, the native parser, used on its own: no SAX
 * feature or property URI appears anywhere in this class. Also checks
 * that {@link Parser}, the SAX wrapper, produces exactly what the native
 * parser does when given the same settings.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class XMLParserTest {

    /** Exercises every simple construct: declaration, comment, namespace
     *  declarations, attributes with a reference, text with a reference,
     *  CDATA, a processing instruction and an empty element. */
    private static final String DOCUMENT =
            "<?xml version=\"1.0\"?>\n"
            + "<!-- lead -->\n"
            + "<root xmlns=\"urn:d\" xmlns:p=\"urn:p\" a=\"1\" p:b=\"two &amp; three\">\n"
            + "  <p:child>text &lt; more</p:child>\n"
            + "  <![CDATA[raw <stuff>]]>\n"
            + "  <?target some data?>\n"
            + "  <empty/>\n"
            + "</root>\n";

    // ===== Events =====

    @Test
    public void testNamespaceAwareEvents() throws Exception {
        RecordingXMLHandler handler = new RecordingXMLHandler();
        XMLParser parser = new XMLParser();
        parser.setHandler(handler);
        feed(parser, bytes("<r xmlns:p=\"urn:p\" p:a=\"1\"><!--c--><p:e>t</p:e></r>"), 4096);
        parser.close();

        List<String> expected = Arrays.asList(
                "startDocument",
                "startElement:r",
                "namespace:p=urn:p",
                "attribute:p:a=1",
                "endAttributes",
                "comment:c",
                "startElement:p:e",
                "endAttributes",
                "characters:t",
                "endElement",
                "endElement",
                "endDocument");
        assertEquals(expected, handler.getEvents());
    }

    @Test
    public void testNamespaceUnawareReportsDeclarationsAsAttributes() throws Exception {
        RecordingXMLHandler handler = new RecordingXMLHandler();
        XMLParser parser = new XMLParser();
        parser.setHandler(handler);
        parser.setNamespaceAware(false);
        feed(parser, bytes("<r xmlns:p=\"urn:p\" p:a=\"1\"/>"), 4096);
        parser.close();

        List<String> expected = Arrays.asList(
                "startDocument",
                "startElement:r",
                "attribute:xmlns:p=urn:p",
                "attribute:p:a=1",
                "endAttributes",
                "endElement",
                "endDocument");
        assertEquals(expected, handler.getEvents());
    }

    @Test
    public void testNamespacePrefixesReportsDeclarationsBothWays() throws Exception {
        RecordingXMLHandler handler = new RecordingXMLHandler();
        XMLParser parser = new XMLParser();
        parser.setHandler(handler);
        parser.setNamespacePrefixes(true);
        feed(parser, bytes("<r xmlns:p=\"urn:p\"/>"), 4096);
        parser.close();

        List<String> events = handler.getEvents();
        assertTrue("Expected a namespace event in " + events, events.contains("namespace:p=urn:p"));
        assertTrue("Expected an xmlns attribute in " + events, events.contains("attribute:xmlns:p=urn:p"));
    }

    @Test
    public void testSameEventsAtEveryChunkSize() throws Exception {
        byte[] bytes = bytes(DOCUMENT);
        List<String> whole = parse(bytes, bytes.length, true);
        assertTrue("Expected a non-trivial event list, was " + whole, whole.size() > 20);
        for (int chunk = 1; chunk < bytes.length; chunk++) {
            assertEquals("chunk size " + chunk, whole, parse(bytes, chunk, true));
        }
    }

    @Test
    public void testBlockingParseMatchesPush() throws Exception {
        byte[] bytes = bytes(DOCUMENT);
        List<String> pushed = parse(bytes, 7, true);

        RecordingXMLHandler handler = new RecordingXMLHandler();
        XMLParser parser = new XMLParser();
        parser.setHandler(handler);
        parser.parse(new ByteArrayInputStream(bytes));
        assertEquals(pushed, new ArrayList<String>(handler.getEvents()));

        handler.clear();
        parser.reset();
        parser.parse(Channels.newChannel(new ByteArrayInputStream(bytes)));
        assertEquals(pushed, handler.getEvents());
    }

    // ===== The SAX wrapper is the same engine =====

    @Test
    public void testParserNativePathMatchesXMLParser() throws Exception {
        byte[] bytes = bytes(DOCUMENT);
        boolean[] modes = { true, false };
        for (int i = 0; i < modes.length; i++) {
            boolean namespaces = modes[i];
            RecordingXMLHandler handler = new RecordingXMLHandler();
            Parser parser = new Parser();
            parser.setFeature("http://xml.org/sax/features/namespaces", namespaces);
            parser.setXMLHandler(handler);
            parser.receive(ByteBuffer.wrap(bytes));
            parser.close();
            assertEquals("namespaces=" + namespaces, parse(bytes, bytes.length, namespaces),
                    handler.getEvents());
        }
    }

    @Test
    public void testInputSourceEncodingStillReachesTheDecoder() throws Exception {
        // No byte order mark and no declaration: only the InputSource says
        // that this is UTF-16LE.
        InputSource source = new InputSource(
                new ByteArrayInputStream("<r>hi</r>".getBytes(StandardCharsets.UTF_16LE)));
        source.setEncoding("UTF-16LE");
        RecordingSaxHandler sink = new RecordingSaxHandler();
        Parser parser = new Parser();
        parser.setContentHandler(sink);
        parser.parse(source);
        assertTrue("Expected the text to be decoded, was " + sink.getEvents(),
                sink.getEvents().contains("characters:hi"));
    }

    // ===== Life cycle =====

    @Test
    public void testResetKeepsConfigurationAndHandler() throws Exception {
        RecordingXMLHandler handler = new RecordingXMLHandler();
        XMLParser parser = new XMLParser();
        parser.setHandler(handler);
        parser.setNamespaceAware(false);
        parser.setSystemId("urn:doc");

        assertFalse(parser.isParsing());
        feed(parser, bytes("<a xmlns=\"urn:x\"/>"), 4096);
        assertTrue(parser.isParsing());
        parser.close();
        List<String> first = new ArrayList<String>(handler.getEvents());

        parser.reset();
        handler.clear();
        assertFalse(parser.isParsing());
        assertSame(handler, parser.getHandler());
        assertFalse(parser.isNamespaceAware());
        assertEquals("urn:doc", parser.getSystemId());

        feed(parser, bytes("<a xmlns=\"urn:x\"/>"), 4096);
        parser.close();
        assertEquals(first, handler.getEvents());
        assertTrue("Expected xmlns as an attribute in " + first, first.contains("attribute:xmlns=urn:x"));
    }

    @Test
    public void testNoHandlerIsRejected() throws Exception {
        XMLParser parser = new XMLParser();
        assertNull(parser.getHandler());
        try {
            parser.receive(ByteBuffer.wrap(bytes("<a/>")));
            fail("Expected IllegalStateException");
        } catch (IllegalStateException e) {
            // expected
        }
    }

    @Test
    public void testPipelineSettingsCannotChangeDuringADocument() throws Exception {
        RecordingXMLHandler handler = new RecordingXMLHandler();
        XMLParser parser = new XMLParser();
        parser.setHandler(handler);
        parser.receive(ByteBuffer.wrap(bytes("<a>")));

        // Setting the value already in force is harmless.
        parser.setHandler(handler);
        parser.setNamespaceAware(true);
        parser.setValidating(false);

        try {
            parser.setHandler(new RecordingXMLHandler());
            fail("Expected IllegalStateException for the handler");
        } catch (IllegalStateException e) {
            // expected
        }
        try {
            parser.setNamespaceAware(false);
            fail("Expected IllegalStateException for namespace awareness");
        } catch (IllegalStateException e) {
            // expected
        }
        try {
            parser.setValidating(true);
            fail("Expected IllegalStateException for validation");
        } catch (IllegalStateException e) {
            // expected
        }

        // The refused changes left the document in progress untouched.
        parser.receive(ByteBuffer.wrap(bytes("</a>")));
        parser.close();
        assertEquals(Arrays.asList("startDocument", "startElement:a", "endAttributes", "endElement",
                "endDocument"), handler.getEvents());

        parser.reset();
        parser.setNamespaceAware(false);
        parser.setValidating(true);
    }

    @Test
    public void testCloseWithoutDataIsNotWellFormed() throws Exception {
        XMLParser parser = new XMLParser();
        parser.setHandler(new RecordingXMLHandler());
        try {
            parser.close();
            fail("Expected SAXException: an empty document is not well-formed");
        } catch (SAXException e) {
            // expected
        }
    }

    @Test
    public void testUnclosedElementIsReportedByClose() throws Exception {
        XMLParser parser = new XMLParser();
        parser.setHandler(new RecordingXMLHandler());
        parser.receive(ByteBuffer.wrap(bytes("<a><b>")));
        try {
            parser.close();
            fail("Expected SAXException for an unclosed element");
        } catch (SAXException e) {
            // expected
        }
    }

    @Test
    public void testDocumentState() throws Exception {
        XMLParser parser = new XMLParser();
        parser.setHandler(new RecordingXMLHandler());
        assertFalse(parser.isStandalone());
        assertFalse(parser.hasDoctype());

        feed(parser, bytes("<?xml version=\"1.0\" standalone=\"yes\"?><!DOCTYPE r [<!ELEMENT r EMPTY>]><r/>"),
                4096);
        parser.close();
        assertTrue(parser.isStandalone());
        assertTrue(parser.hasDoctype());

        parser.reset();
        assertFalse(parser.isStandalone());
        assertFalse(parser.hasDoctype());
    }

    @Test
    public void testInitialCharsetAppliesToOneDocument() throws Exception {
        RecordingXMLHandler handler = new RecordingXMLHandler();
        XMLParser parser = new XMLParser();
        parser.setHandler(handler);

        parser.setInitialCharset(StandardCharsets.UTF_16LE);
        feed(parser, "<r>hi</r>".getBytes(StandardCharsets.UTF_16LE), 4096);
        parser.close();
        assertTrue("Expected the text to be decoded, was " + handler.getEvents(),
                handler.getEvents().contains("characters:hi"));

        // The hint was for that document only.
        parser.reset();
        handler.clear();
        feed(parser, bytes("<r>hi</r>"), 4096);
        parser.close();
        assertTrue("Expected UTF-8 by default, was " + handler.getEvents(),
                handler.getEvents().contains("characters:hi"));
    }

    // ===== Typed security settings =====

    @Test
    public void testSecureDefaults() {
        XMLParser parser = new XMLParser();
        assertTrue(parser.isNamespaceAware());
        assertFalse(parser.isNamespacePrefixes());
        assertFalse(parser.isXmlnsUris());
        assertFalse(parser.isValidating());
        assertFalse(parser.isExternalGeneralEntities());
        assertFalse(parser.isExternalParameterEntities());
        assertFalse(parser.isDisallowDoctypeDecl());
        assertTrue(parser.isResolveDTDURIs());
        assertEquals("", parser.getAccessExternalDTD());
        assertEquals(64000, parser.getEntityExpansionLimit());
    }

    @Test
    public void testDefaultsMatchTheSaxWrapper() throws Exception {
        XMLParser parser = new XMLParser();
        Parser reader = new Parser();
        assertEquals(reader.getFeature("http://xml.org/sax/features/namespaces"), parser.isNamespaceAware());
        assertEquals(reader.getFeature("http://xml.org/sax/features/namespace-prefixes"),
                parser.isNamespacePrefixes());
        assertEquals(reader.getFeature("http://xml.org/sax/features/xmlns-uris"), parser.isXmlnsUris());
        assertEquals(reader.getFeature("http://xml.org/sax/features/validation"), parser.isValidating());
        assertEquals(reader.getFeature("http://xml.org/sax/features/external-general-entities"),
                parser.isExternalGeneralEntities());
        assertEquals(reader.getFeature("http://xml.org/sax/features/external-parameter-entities"),
                parser.isExternalParameterEntities());
        assertEquals(reader.getFeature("http://apache.org/xml/features/disallow-doctype-decl"),
                parser.isDisallowDoctypeDecl());
        assertEquals(reader.getFeature("http://xml.org/sax/features/resolve-dtd-uris"),
                parser.isResolveDTDURIs());
        assertEquals(reader.getProperty("http://javax.xml.XMLConstants/property/accessExternalDTD"),
                parser.getAccessExternalDTD());
        assertEquals(reader.getProperty("http://www.nongnu.org/gonzalez/properties/entity-expansion-limit"),
                Integer.valueOf(parser.getEntityExpansionLimit()));
    }

    @Test
    public void testDisallowDoctypeDecl() throws Exception {
        XMLParser parser = new XMLParser();
        parser.setHandler(new RecordingXMLHandler());
        parser.setDisallowDoctypeDecl(true);
        try {
            feed(parser, bytes("<!DOCTYPE r [<!ENTITY e \"x\">]><r>&e;</r>"), 4096);
            parser.close();
            fail("Expected SAXException for the DOCTYPE declaration");
        } catch (SAXException e) {
            // expected
        }
    }

    @Test
    public void testEntityExpansionLimit() throws Exception {
        String xml = "<!DOCTYPE r [<!ENTITY e \"x\">]><r>&e;&e;&e;&e;&e;</r>";

        RecordingXMLHandler handler = new RecordingXMLHandler();
        XMLParser parser = new XMLParser();
        parser.setHandler(handler);
        feed(parser, bytes(xml), 4096);
        parser.close();
        assertTrue("Expected the expanded text in " + handler.getEvents(),
                handler.getEvents().contains("endDocument"));

        parser.reset();
        parser.setEntityExpansionLimit(3);
        try {
            feed(parser, bytes(xml), 4096);
            parser.close();
            fail("Expected SAXException once the limit of 3 was passed");
        } catch (SAXException e) {
            assertTrue("Was: " + e.getMessage(), e.getMessage().contains("Entity expansion limit"));
        }
    }

    @Test
    public void testExternalEntityNotFetchedByDefault() throws Exception {
        CountingResolver resolver = new CountingResolver("<x/>");
        RecordingXMLHandler handler = new RecordingXMLHandler();
        XMLParser parser = new XMLParser();
        parser.setHandler(handler);
        parser.setEntityResolver(resolver);
        parser.setSystemId("urn:test:doc");
        feed(parser, bytes("<!DOCTYPE r [<!ENTITY e SYSTEM \"urn:test:e\">]><r>&e;</r>"), 4096);
        parser.close();

        assertEquals("The resolver must not be asked for the entity", 0, resolver.calls);
        assertFalse("The entity must not be included: " + handler.getEvents(),
                handler.getEvents().contains("startElement:x"));
    }

    @Test
    public void testExternalEntityFetchedWhenEnabled() throws Exception {
        CountingResolver resolver = new CountingResolver("<x/>");
        RecordingXMLHandler handler = new RecordingXMLHandler();
        XMLParser parser = new XMLParser();
        parser.setHandler(handler);
        parser.setEntityResolver(resolver);
        parser.setSystemId("urn:test:doc");
        parser.setExternalGeneralEntities(true);
        parser.setAccessExternalDTD("all");
        feed(parser, bytes("<!DOCTYPE r [<!ENTITY e SYSTEM \"urn:test:e\">]><r>&e;</r>"), 4096);
        parser.close();

        assertEquals(1, resolver.calls);
        assertTrue("The entity must be included: " + handler.getEvents(),
                handler.getEvents().contains("startElement:x"));
    }

    // ===== Helpers =====

    private static byte[] bytes(String xml) {
        return xml.getBytes(StandardCharsets.UTF_8);
    }

    private static List<String> parse(byte[] bytes, int chunk, boolean namespaces) throws SAXException {
        RecordingXMLHandler handler = new RecordingXMLHandler();
        XMLParser parser = new XMLParser();
        parser.setHandler(handler);
        parser.setNamespaceAware(namespaces);
        feed(parser, bytes, chunk);
        parser.close();
        return handler.getEvents();
    }

    /** Feeds {@code bytes} in pieces of at most {@code chunk} bytes, keeping
     *  to the read/flip/receive/compact contract. The parser consumes
     *  nothing of an XML declaration until it has all of it, so the buffer
     *  has room for one as well as the next piece. */
    private static void feed(XMLParser parser, byte[] bytes, int chunk) throws SAXException {
        ByteBuffer buffer = ByteBuffer.allocate(chunk + 64);
        int offset = 0;
        while (offset < bytes.length) {
            int n = Math.min(chunk, bytes.length - offset);
            buffer.put(bytes, offset, n);
            offset += n;
            buffer.flip();
            parser.receive(buffer);
            buffer.compact();
        }
    }

    /** Resolves every entity to the same content and counts the requests. */
    private static class CountingResolver implements EntityResolver {

        private final String content;
        int calls;

        CountingResolver(String content) {
            this.content = content;
        }

        @Override
        public InputSource resolveEntity(String publicId, String systemId) {
            calls++;
            InputSource source = new InputSource(new ByteArrayInputStream(bytes(content)));
            source.setSystemId(systemId);
            return source;
        }
    }

}

/*
 * AttributeUniquenessTest.java
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
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Gonzalez.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.bluezoo.gonzalez;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.Test;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * WFC "Unique Att Spec" (XML 1.0 Section 3.1): an attribute name must not
 * appear more than once in the same start tag.
 * <p>
 * The constraint has to hold for every document and every consumer. These
 * tests pin down the two ways it used not to:
 * <ul>
 * <li>The check compared names by reference, which is only correct while
 * every name is interned. The name pool is deliberately bounded, so a name
 * that does not fit is a fresh {@code String} on each occurrence and two
 * occurrences of it never compared equal.</li>
 * <li>The check lived in the SAX adapter, so a consumer of the native
 * {@link XMLHandler} events got no check at all, and neither did a repeated
 * namespace declaration in namespace-aware mode, which never reaches the
 * adapter as an attribute.</li>
 * </ul>
 * The same reference comparison decided whether a DTD-declared default
 * should be injected, so a specified attribute whose name was outside the
 * pool also received its default as a second attribute.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class AttributeUniquenessTest {

    /**
     * Four two-character names ending in the same character fill one probe
     * window of the name pool as it hashed when this test was written, so
     * the fifth such name ({@code ex}) is not interned.
     */
    private static final String POOL_OVERFLOW_PREFIX = "<r ax=\"\" bx=\"\" cx=\"\" dx=\"\" ";

    // ===== SAX path =====

    @Test
    public void testDuplicateOutsideNamePoolRejectedNamespaceAware() throws Exception {
        expectDuplicateSax(POOL_OVERFLOW_PREFIX + "ex=\"1\" ex=\"2\"/>", true);
    }

    @Test
    public void testDuplicateOutsideNamePoolRejectedNamespaceUnaware() throws Exception {
        expectDuplicateSax(POOL_OVERFLOW_PREFIX + "ex=\"1\" ex=\"2\"/>", false);
    }

    @Test
    public void testDuplicateOutsideNamePoolRejectedOnLaterElement() throws Exception {
        expectDuplicateSax("<r><a ax=\"\"/><b bx=\"\"/><c cx=\"\"/><d dx=\"\"/><e ex=\"1\" ex=\"2\"/></r>", true);
    }

    /**
     * Independent of how the pool happens to hash: once a document has used
     * far more distinct names than the pool has room for, a new name cannot
     * be interned whatever the hash function is.
     */
    @Test
    public void testDuplicateRejectedAfterLargeVocabulary() throws Exception {
        String filler = largeVocabulary(6000);
        List<String> accepted = new ArrayList<String>();
        for (int trial = 0; trial < 40; trial++) {
            String name = "dup" + trial + "attribute";
            String xml = "<r>" + filler + "<z " + name + "=\"1\" " + name + "=\"2\"/></r>";
            Parser parser = new Parser();
            parser.setContentHandler(new DefaultHandler());
            try {
                parser.parse(new InputSource(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))));
                accepted.add(name);
            } catch (SAXException e) {
                assertTrue("Unexpected error: " + e.getMessage(), e.getMessage().contains("Duplicate attribute"));
            }
        }
        assertTrue("Duplicate attribute accepted for: " + accepted, accepted.isEmpty());
    }

    /**
     * A namespace declaration is an attribute too. In namespace-aware mode
     * it is rerouted to {@link XMLHandler#namespace} before the SAX adapter
     * sees it, so a check made there never covered it.
     */
    @Test
    public void testDuplicateNamespaceDeclarationRejected() throws Exception {
        expectDuplicateSax("<a xmlns:p=\"urn:1\" xmlns:p=\"urn:2\"/>", true);
        expectDuplicateSax("<a xmlns:p=\"urn:1\" xmlns:p=\"urn:2\"/>", false);
        expectDuplicateNative("<a xmlns:p=\"urn:1\" xmlns:p=\"urn:2\"/>", true, 8192);
    }

    @Test
    public void testDuplicateDefaultNamespaceDeclarationRejected() throws Exception {
        expectDuplicateSax("<a xmlns=\"urn:1\" xmlns=\"urn:2\"/>", true);
        expectDuplicateSax("<a xmlns=\"urn:1\" xmlns=\"urn:2\"/>", false);
        expectDuplicateNative("<a xmlns=\"urn:1\" xmlns=\"urn:2\"/>", true, 8192);
    }

    @Test
    public void testDistinctNamesOutsideNamePoolAccepted() throws Exception {
        AttributeCollector collector = new AttributeCollector("r");
        Parser parser = new Parser();
        parser.setContentHandler(collector);
        String xml = POOL_OVERFLOW_PREFIX + "ex=\"1\" fx=\"2\" gx=\"3\"/>";
        parser.parse(new InputSource(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))));
        assertEquals("[ax=, bx=, cx=, dx=, ex=1, fx=2, gx=3]", collector.attributes.toString());
    }

    /**
     * A specified attribute must suppress its declared default even when its
     * name is outside the pool.
     */
    @Test
    public void testDefaultNotInjectedForSpecifiedAttributeOutsideNamePool() throws Exception {
        String xml = "<!DOCTYPE r [<!ATTLIST r ax CDATA #IMPLIED bx CDATA #IMPLIED cx CDATA #IMPLIED "
                + "dx CDATA #IMPLIED ex CDATA \"default\">]>"
                + POOL_OVERFLOW_PREFIX + "ex=\"specified\"/>";
        AttributeCollector collector = new AttributeCollector("r");
        Parser parser = new Parser();
        parser.setContentHandler(collector);
        parser.parse(new InputSource(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))));
        assertEquals("[ax=, bx=, cx=, dx=, ex=specified]", collector.attributes.toString());
    }

    @Test
    public void testDefaultStillInjectedForUnspecifiedAttributeOutsideNamePool() throws Exception {
        String xml = "<!DOCTYPE r [<!ATTLIST r ax CDATA #IMPLIED bx CDATA #IMPLIED cx CDATA #IMPLIED "
                + "dx CDATA #IMPLIED ex CDATA \"default\">]>"
                + POOL_OVERFLOW_PREFIX + "/>";
        AttributeCollector collector = new AttributeCollector("r");
        Parser parser = new Parser();
        parser.setContentHandler(collector);
        parser.parse(new InputSource(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))));
        assertEquals("[ax=, bx=, cx=, dx=, ex=default]", collector.attributes.toString());
    }

    // ===== Native XMLHandler path =====

    @Test
    public void testDuplicateRejectedOnNativeHandlerPath() throws Exception {
        expectDuplicateNative("<a x=\"1\" x=\"2\"/>", true, 8192);
        expectDuplicateNative("<a x=\"1\" x=\"2\"/>", false, 8192);
    }

    @Test
    public void testDuplicateWithOtherAttributesBetweenRejectedOnNativeHandlerPath() throws Exception {
        expectDuplicateNative("<a x=\"1\" y=\"2\" z=\"3\" x=\"4\"/>", false, 8192);
    }

    @Test
    public void testDuplicateOutsideNamePoolRejectedOnNativeHandlerPath() throws Exception {
        expectDuplicateNative(POOL_OVERFLOW_PREFIX + "ex=\"1\" ex=\"2\"/>", true, 8192);
        expectDuplicateNative(POOL_OVERFLOW_PREFIX + "ex=\"1\" ex=\"2\"/>", false, 8192);
    }

    @Test
    public void testDuplicateRejectedOnNativeHandlerPathAtEveryChunkSize() throws Exception {
        String xml = "<a first = \"1\"  second='2' first\n=\n'3'/>";
        for (int chunk = 1; chunk <= xml.length(); chunk++) {
            expectDuplicateNative(xml, false, chunk);
        }
    }

    /**
     * An attribute's name, '=' and opening quote are rescanned from the
     * attribute's start when input runs out part way through them. A name
     * must not be counted as seen until it is committed, or the retry
     * reports the attribute as a duplicate of itself.
     */
    @Test
    public void testNoFalseDuplicateWhenAttributeIsRescannedAtChunkBoundary() throws Exception {
        String xml = "<a first   =   \"1\"  second\t=\n'2'   third=\"3\"   ><b first='4'/></a>";
        for (int chunk = 1; chunk <= xml.length(); chunk++) {
            NativeCollector collector = new NativeCollector();
            parseNative(xml, false, chunk, collector);
            assertEquals("chunk size " + chunk, "[first=1, second=2, third=3, first=4]",
                    collector.attributes.toString());
        }
    }

    @Test
    public void testNoFalseDuplicateWhenAttributeIsRescannedThroughSaxPath() throws Exception {
        String xml = "<a first   =   \"1\"  second\t=\n'2'   third=\"3\"   />";
        byte[] bytes = xml.getBytes(StandardCharsets.UTF_8);
        for (int chunk = 1; chunk <= bytes.length; chunk++) {
            AttributeCollector collector = new AttributeCollector("a");
            Parser parser = new Parser();
            parser.setContentHandler(collector);
            feed(parser, bytes, chunk);
            parser.close();
            assertEquals("chunk size " + chunk, "[first=1, second=2, third=3]", collector.attributes.toString());
        }
    }

    // ===== Helpers =====

    private static void expectDuplicateSax(String xml, boolean namespaces) throws Exception {
        Parser parser = new Parser();
        parser.setContentHandler(new DefaultHandler());
        parser.setFeature("http://xml.org/sax/features/namespaces", namespaces);
        try {
            parser.parse(new InputSource(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))));
            fail("Expected a duplicate attribute error for: " + xml);
        } catch (SAXException e) {
            assertTrue("Error message should mention 'Duplicate attribute', was: " + e.getMessage(),
                    e.getMessage().contains("Duplicate attribute"));
        }
    }

    private static void expectDuplicateNative(String xml, boolean namespaces, int chunk) throws Exception {
        try {
            parseNative(xml, namespaces, chunk, new NativeCollector());
            fail("Expected a duplicate attribute error (chunk size " + chunk + ") for: " + xml);
        } catch (SAXException e) {
            assertTrue("Error message should mention 'Duplicate attribute', was: " + e.getMessage(),
                    e.getMessage().contains("Duplicate attribute"));
        }
    }

    private static void parseNative(String xml, boolean namespaces, int chunk, XMLHandler handler)
            throws Exception {
        Parser parser = new Parser();
        parser.setFeature("http://xml.org/sax/features/namespaces", namespaces);
        parser.setXMLHandler(handler);
        feed(parser, xml.getBytes(StandardCharsets.UTF_8), chunk);
        parser.close();
    }

    /** Feeds {@code bytes} in pieces of at most {@code chunk} bytes, keeping
     *  to the read/flip/receive/compact contract. */
    private static void feed(Parser parser, byte[] bytes, int chunk) throws SAXException {
        ByteBuffer buffer = ByteBuffer.allocate(chunk + 8);
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

    /** Many distinct, well-spread element names: far more than the name
     *  pool can hold. */
    private static String largeVocabulary(int count) {
        Random random = new Random(20261114L);
        StringBuilder sb = new StringBuilder(count * 16);
        for (int i = 0; i < count; i++) {
            sb.append('<');
            int len = 1 + random.nextInt(14);
            for (int j = 0; j < len; j++) {
                sb.append((char) ('a' + random.nextInt(26)));
            }
            // A numeric suffix keeps every name distinct.
            sb.append(i).append("/>");
        }
        return sb.toString();
    }

    /** Collects {@code name=value} for the attributes of one element type. */
    private static class AttributeCollector extends DefaultHandler {

        final List<String> attributes = new ArrayList<String>();
        private final String elementName;

        AttributeCollector(String elementName) {
            this.elementName = elementName;
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes atts) {
            if (!elementName.equals(qName)) {
                return;
            }
            for (int i = 0; i < atts.getLength(); i++) {
                attributes.add(atts.getQName(i) + "=" + atts.getValue(i));
            }
        }
    }

    /** Collects {@code name=value} for every attribute, from native events. */
    private static class NativeCollector implements XMLHandler {

        final List<String> attributes = new ArrayList<String>();
        private String name;
        private final StringBuilder value = new StringBuilder();

        @Override
        public void setLocator(Locator locator) {
        }

        @Override
        public void setXml11(boolean xml11) {
        }

        @Override
        public void startDocument() {
        }

        @Override
        public void endDocument() {
        }

        @Override
        public void startElement(String qName) {
        }

        @Override
        public void namespace(String prefix, String uri) {
        }

        @Override
        public void startAttribute(String attrName, String type, boolean declared, boolean specified) {
            name = attrName;
            value.setLength(0);
        }

        @Override
        public void attributeValueContent(CharBuffer chunk, boolean end) {
            value.append(chunk);
            if (end) {
                attributes.add(name + "=" + value);
            }
        }

        @Override
        public void endAttributes() {
        }

        @Override
        public void characters(CharBuffer text, boolean ignorable, boolean end) {
        }

        @Override
        public void endElement() {
        }

        @Override
        public void startComment() {
        }

        @Override
        public void commentData(CharBuffer text, boolean end) {
        }

        @Override
        public void startCDATA() {
        }

        @Override
        public void endCDATA() {
        }

        @Override
        public void startDTD(String dtdName, String publicId, String systemId) {
        }

        @Override
        public void endDTD() {
        }

        @Override
        public void startEntity(String entityName) {
        }

        @Override
        public void endEntity(String entityName) {
        }

        @Override
        public void notationDecl(String notationName, String publicId, String systemId) {
        }

        @Override
        public void unparsedEntityDecl(String entityName, String publicId, String systemId,
                String notationName) {
        }

        @Override
        public void piTarget(String target) {
        }

        @Override
        public void piData(CharBuffer data, boolean end) {
        }

        @Override
        public void saveBuffers() {
        }

        @Override
        public SAXException fatalError(String message) {
            return new SAXException(message);
        }

        @Override
        public void error(String message) {
        }
    }

}

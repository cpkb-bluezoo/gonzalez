/*
 * ParserNamespaceTest.java
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

import org.junit.Test;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.helpers.DefaultHandler;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Unit tests for namespace handling through the Parser (public API)
 * and NamespaceScopeTracker (internal).
 *
 * @author Chris Burdess
 */
public class ParserNamespaceTest {

    // ========== NamespaceTest (Parser-based) ==========

    @Test
    public void testBasicNamespace() throws Exception {
        String xml =
            "<?xml version='1.0'?>\n" +
            "<doc:root xmlns:doc='http://example.com/doc'>\n" +
            "  <doc:child>text</doc:child>\n" +
            "</doc:root>";

        RecordingHandler handler = new RecordingHandler();
        Parser parser = new Parser();
        parser.setContentHandler(handler);
        parser.setFeature("http://xml.org/sax/features/namespaces", true);
        ByteArrayInputStream bais = new ByteArrayInputStream(xml.getBytes("UTF-8"));
        parser.parse(new InputSource(bais));

        assertTrue("Expected prefix mapping for 'doc'",
            handler.prefixMappings.contains("doc=http://example.com/doc"));
        assertTrue("Root element missing namespace URI",
            handler.elements.get(0).contains("http://example.com/doc"));
        assertTrue("Root element missing local name",
            handler.elements.get(0).contains("root"));
    }

    @Test
    public void testMultiplePrefixes() throws Exception {
        String xml =
            "<?xml version='1.0'?>\n" +
            "<root xmlns:a='http://example.com/a' xmlns:b='http://example.com/b'>\n" +
            "  <a:foo/>\n" +
            "  <b:bar/>\n" +
            "</root>";

        RecordingHandler handler = new RecordingHandler();
        Parser parser = new Parser();
        parser.setContentHandler(handler);
        parser.setFeature("http://xml.org/sax/features/namespaces", true);
        ByteArrayInputStream bais = new ByteArrayInputStream(xml.getBytes("UTF-8"));
        parser.parse(new InputSource(bais));

        assertTrue("Missing prefix 'a'", handler.prefixMappings.contains("a=http://example.com/a"));
        assertTrue("Missing prefix 'b'", handler.prefixMappings.contains("b=http://example.com/b"));
    }

    @Test
    public void testDefaultNamespace() throws Exception {
        String xml =
            "<?xml version='1.0'?>\n" +
            "<root xmlns='http://example.com/default'>\n" +
            "  <child>text</child>\n" +
            "</root>";

        RecordingHandler handler = new RecordingHandler();
        Parser parser = new Parser();
        parser.setContentHandler(handler);
        parser.setFeature("http://xml.org/sax/features/namespaces", true);
        ByteArrayInputStream bais = new ByteArrayInputStream(xml.getBytes("UTF-8"));
        parser.parse(new InputSource(bais));

        assertTrue("Missing default namespace mapping",
            handler.prefixMappings.contains("=http://example.com/default"));
        assertTrue("Root not in default namespace",
            handler.elements.get(0).contains("http://example.com/default"));
    }

    @Test
    public void testAttributeNamespaces() throws Exception {
        String xml =
            "<?xml version='1.0'?>\n" +
            "<root xmlns:ns='http://example.com/ns' ns:attr='value'/>";

        RecordingHandler handler = new RecordingHandler();
        Parser parser = new Parser();
        parser.setContentHandler(handler);
        parser.setFeature("http://xml.org/sax/features/namespaces", true);
        ByteArrayInputStream bais = new ByteArrayInputStream(xml.getBytes("UTF-8"));
        parser.parse(new InputSource(bais));

        assertTrue("Prefixed attribute missing namespace",
            handler.attributes.get(0).contains("http://example.com/ns"));
    }

    @Test
    public void testNamespacePrefixesFlag() throws Exception {
        String xml =
            "<?xml version='1.0'?>\n" +
            "<root xmlns='http://example.com/ns'/>";

        RecordingHandler handler = new RecordingHandler();
        Parser parser = new Parser();
        parser.setContentHandler(handler);
        parser.setFeature("http://xml.org/sax/features/namespaces", true);
        parser.setFeature("http://xml.org/sax/features/namespace-prefixes", true);
        ByteArrayInputStream bais = new ByteArrayInputStream(xml.getBytes("UTF-8"));
        parser.parse(new InputSource(bais));

        boolean hasXmlns = false;
        for (int i = 0; i < handler.attributes.size(); i++) {
            String attr = handler.attributes.get(i);
            if (attr.contains("xmlns")) {
                hasXmlns = true;
                break;
            }
        }
        assertTrue("xmlns attribute missing when namespace-prefixes=true", hasXmlns);
    }

    @Test
    public void testNonNamespaceAware() throws Exception {
        String xml =
            "<?xml version='1.0'?>\n" +
            "<ns:root xmlns:ns='http://example.com/ns'/>";

        RecordingHandler handler = new RecordingHandler();
        Parser parser = new Parser();
        parser.setContentHandler(handler);
        parser.setFeature("http://xml.org/sax/features/namespaces", false);
        ByteArrayInputStream bais = new ByteArrayInputStream(xml.getBytes("UTF-8"));
        parser.parse(new InputSource(bais));

        assertTrue("Prefix mappings called when namespaces disabled",
            handler.prefixMappings.isEmpty());
        assertTrue("Element name not raw qName",
            handler.elements.get(0).contains("ns:root"));
    }

    // ========== NamespaceScopeTrackerTest (internal class) ==========

    @Test
    public void testPreBoundPrefixes() throws Exception {
        NamespaceScopeTracker tracker = new NamespaceScopeTracker();

        String xmlURI = tracker.getURI("xml");
        String xmlnsURI = tracker.getURI("xmlns");

        assertEquals(NamespaceScopeTracker.XML_NAMESPACE_URI, xmlURI);
        assertEquals(NamespaceScopeTracker.XMLNS_NAMESPACE_URI, xmlnsURI);
    }

    @Test
    public void testBasicPrefixBinding() throws Exception {
        NamespaceScopeTracker tracker = new NamespaceScopeTracker();

        tracker.pushContext();
        tracker.declarePrefix("foo", "http://example.com/foo");

        String uri = tracker.getURI("foo");
        assertEquals("http://example.com/foo", uri);
    }

    @Test
    public void testDefaultNamespaceInTracker() throws Exception {
        NamespaceScopeTracker tracker = new NamespaceScopeTracker();

        String uri1 = tracker.getURI("");
        assertNull(uri1);

        tracker.pushContext();
        tracker.declarePrefix("", "http://example.com/default");

        String uri2 = tracker.getURI("");
        assertEquals("http://example.com/default", uri2);
    }

    @Test
    public void testScopeNesting() throws Exception {
        NamespaceScopeTracker tracker = new NamespaceScopeTracker();

        tracker.pushContext();
        tracker.declarePrefix("a", "http://example.com/a");
        String uri1 = tracker.getURI("a");

        tracker.pushContext();
        tracker.declarePrefix("b", "http://example.com/b");
        String uri2 = tracker.getURI("a");
        String uri3 = tracker.getURI("b");

        assertEquals(uri1, uri2);
        assertEquals("http://example.com/b", uri3);

        tracker.popContext();
        String uri4 = tracker.getURI("a");
        String uri5 = tracker.getURI("b");

        assertEquals(uri1, uri4);
        assertNull(uri5);
    }

    @Test
    public void testPrefixRedeclaration() throws Exception {
        NamespaceScopeTracker tracker = new NamespaceScopeTracker();

        tracker.pushContext();
        tracker.declarePrefix("ns", "http://example.com/outer");
        String uri1 = tracker.getURI("ns");

        tracker.pushContext();
        tracker.declarePrefix("ns", "http://example.com/inner");
        String uri2 = tracker.getURI("ns");

        assertFalse(uri1.equals(uri2));

        tracker.popContext();
        String uri3 = tracker.getURI("ns");
        assertEquals(uri1, uri3);
    }

    @Test
    public void testProcessName() throws Exception {
        NamespaceScopeTracker tracker = new NamespaceScopeTracker();
        QNamePool pool = new QNamePool();

        tracker.pushContext();
        tracker.declarePrefix("foo", "http://example.com/foo");
        tracker.declarePrefix("", "http://example.com/default");

        QName parts1 = tracker.processName("foo:bar", false, pool);
        assertEquals("http://example.com/foo", parts1.getURI());
        assertEquals("bar", parts1.getLocalName());
        assertEquals("foo:bar", parts1.getQName());
        pool.returnToPool(parts1);

        QName parts2 = tracker.processName("baz", false, pool);
        assertEquals("http://example.com/default", parts2.getURI());
        assertEquals("baz", parts2.getLocalName());
        assertEquals("baz", parts2.getQName());
        pool.returnToPool(parts2);

        QName parts3 = tracker.processName("attr", true, pool);
        assertEquals("", parts3.getURI());
        assertEquals("attr", parts3.getLocalName());
        assertEquals("attr", parts3.getQName());
        pool.returnToPool(parts3);

        QName parts4 = tracker.processName("foo:attr", true, pool);
        assertEquals("http://example.com/foo", parts4.getURI());
        assertEquals("attr", parts4.getLocalName());
        assertEquals("foo:attr", parts4.getQName());
        pool.returnToPool(parts4);
    }

    @Test
    public void testGetPrefixes() throws Exception {
        NamespaceScopeTracker tracker = new NamespaceScopeTracker();

        tracker.pushContext();
        tracker.declarePrefix("a", "http://example.com/same");
        tracker.declarePrefix("b", "http://example.com/same");
        tracker.declarePrefix("c", "http://example.com/different");

        List<String> prefixes = new ArrayList<String>();
        Iterator<String> iter = tracker.getPrefixes("http://example.com/same");
        while (iter.hasNext()) {
            prefixes.add(iter.next());
        }

        assertTrue(prefixes.contains("a"));
        assertTrue(prefixes.contains("b"));
        assertFalse(prefixes.contains("c"));
    }

    /**
     * Recording ContentHandler that captures startPrefixMapping, endPrefixMapping,
     * startElement, and endElement events.
     */
    private static class RecordingHandler extends DefaultHandler {
        List<String> prefixMappings = new ArrayList<String>();
        List<String> elements = new ArrayList<String>();
        List<String> attributes = new ArrayList<String>();

        @Override
        public void startPrefixMapping(String prefix, String uri) {
            prefixMappings.add(prefix + "=" + uri);
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes atts) {
            String elem = "{" + uri + "}" + localName + " [qName=" + qName + "]";
            elements.add(elem);

            for (int i = 0; i < atts.getLength(); i++) {
                String attrUri = atts.getURI(i);
                String attrLocal = atts.getLocalName(i);
                String attrQName = atts.getQName(i);
                String attr = "{" + attrUri + "}" + attrLocal + " [qName=" + attrQName + "]";
                attributes.add(attr);
            }
        }
    }
}

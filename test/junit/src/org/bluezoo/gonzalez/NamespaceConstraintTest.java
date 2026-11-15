/*
 * NamespaceConstraintTest.java
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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * A document that breaks the constraints of Namespaces in XML is not
 * namespace-well-formed, and a namespace-aware parse rejects it whichever
 * interface the events are delivered through: the constraints are
 * enforced before the events reach an {@link XMLHandler}, not by the
 * adapter that turns them into SAX.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class NamespaceConstraintTest {

    /** Each document that is not namespace-well-formed, with part of the
     *  error it is rejected with. */
    private static final String[][] ILLEGAL = {
        // A prefix must be bound (Namespaces 1.0 section 5, "Prefix Declared").
        { "<a:r/>", "Unbound namespace prefix: a" },
        { "<r a:b=\"1\"/>", "Unbound namespace prefix: a" },
        { "<r><s a:b=\"1\"/></r>", "Unbound namespace prefix: a" },
        { "<r xmlns:b=\"urn:b\" a:c=\"1\"/>", "Unbound namespace prefix: a" },
        // A binding ends with the element that made it.
        { "<r><a:s xmlns:a=\"urn:a\"/><a:t/></r>", "Unbound namespace prefix: a" },
        { "<r><s xmlns:a=\"urn:a\"><t/></s><u a:b=\"1\"/></r>", "Unbound namespace prefix: a" },
        // Including for a name that was legal while it lasted.
        { "<r><s xmlns:a=\"urn:a\"><a:t/></s><a:t/></r>", "Unbound namespace prefix: a" },
        { "<r><s xmlns:a=\"urn:a\" a:b=\"1\"/><s a:b=\"1\"/></r>", "Unbound namespace prefix: a" },
        // XML 1.1 lets a prefix be unbound again.
        { "<?xml version=\"1.1\"?><r xmlns:a=\"urn:a\"><a:s xmlns:a=\"\"/></r>", "Unbound namespace prefix: a" },
        { "<?xml version=\"1.1\"?><r xmlns:a=\"urn:a\"><s xmlns:a=\"\" a:b=\"1\"/></r>",
            "Unbound namespace prefix: a" },
        // No two attributes with the same expanded name (section 6.3).
        { "<r xmlns:a=\"urn:x\" xmlns:b=\"urn:x\" a:c=\"1\" b:c=\"2\"/>",
            "Duplicate attribute by expanded name: {urn:x}c (qName: b:c)" },
        { "<r xmlns:a=\"urn:x\"><s xmlns:b=\"urn:x\" b:c=\"1\" d=\"2\" a:c=\"3\"/></r>",
            "Duplicate attribute by expanded name: {urn:x}c (qName: a:c)" },
        { "<r a:c=\"1\" b:c=\"2\" xmlns:a=\"urn:x\" xmlns:b=\"urn:x\"/>",
            "Duplicate attribute by expanded name: {urn:x}c (qName: b:c)" },
        // The same two attributes, legal until a prefix is bound afresh.
        { "<r xmlns:a=\"urn:x\" xmlns:b=\"urn:y\"><s a:c=\"1\" b:c=\"2\"/>"
            + "<t xmlns:b=\"urn:x\" a:c=\"1\" b:c=\"2\"/></r>",
            "Duplicate attribute by expanded name: {urn:x}c (qName: b:c)" },
        // An element name is a QName (section 4).
        { "<a:b:c xmlns:a=\"urn:a\"/>", "QNames may contain at most one colon" },
        { "<:r/>", "QName cannot start with colon" },
        { "<r:/>", "QName cannot end with colon" },
        { "<r:  xmlns:r=\"urn:r\"/>", "QName cannot end with colon" },
        { "<xmlns:r/>", "'xmlns:' prefix cannot be used in element names" },
        { "<xmlns/>", "'xmlns' cannot be used as element name" },
        // And so is an attribute name.
        { "<r xmlns:a=\"urn:a\" a:b:c=\"1\"/>", "QNames may contain at most one colon" },
        { "<r :a=\"1\"/>", "QName cannot start with colon" },
        { "<r xmlns:a=\"urn:a\" a:=\"1\"/>", "QName cannot end with colon" },
        // A default supplied by the DTD is an attribute like any other.
        { "<!DOCTYPE r [<!ATTLIST r a:b CDATA \"x\">]><r/>", "Unbound namespace prefix: a" },
    };

    private static final String[] LEGAL = {
        "<r/>",
        "<a:r xmlns:a=\"urn:a\"/>",
        // Declared later in the same tag than it is used.
        "<a:r a:b=\"1\" xmlns:a=\"urn:a\"/>",
        "<r xmlns=\"urn:d\"><s xmlns=\"\"><t/></s></r>",
        // Bound afresh by a descendant, and back again after it.
        "<a:r xmlns:a=\"urn:a\"><a:s xmlns:a=\"urn:other\" a:b=\"1\"><a:t/></a:s><a:u a:c=\"2\"/></a:r>",
        // The xml prefix needs no declaration.
        "<r xml:lang=\"en\" xml:space=\"preserve\"><xml:s/></r>",
        // The same local name in two namespaces, and in none.
        "<r xmlns:a=\"urn:a\" xmlns:b=\"urn:b\" a:c=\"1\" b:c=\"2\" c=\"3\"/>",
        "<r xmlns:a=\"urn:a\" xmlns:b=\"urn:b\" a:c=\"1\" a:d=\"2\" b:d=\"3\" b:e=\"4\"/>",
        // An unprefixed attribute is in no namespace, whatever the default is.
        "<r xmlns=\"urn:d\" xmlns:a=\"urn:d\" c=\"1\" a:c=\"2\"/>",
        // And legal again once the element that bound it afresh has ended.
        "<r xmlns:a=\"urn:x\" xmlns:b=\"urn:y\"><t xmlns:b=\"urn:x\" a:d=\"1\" b:c=\"2\"/>"
            + "<s a:c=\"1\" b:c=\"2\"/></r>",
        "<!DOCTYPE r [<!ATTLIST r xmlns:a CDATA \"urn:a\" a:b CDATA \"x\">]><r/>",
        "<?xml version=\"1.1\"?><r xmlns:a=\"urn:a\"><s xmlns:a=\"\"><t xmlns:a=\"urn:again\" a:b=\"1\"/></s></r>",
    };

    @Test
    public void testIllegalDocumentsAreRejectedNatively() throws Exception {
        for (int i = 0; i < ILLEGAL.length; i++) {
            String message = nativeError(ILLEGAL[i][0], 0);
            assertTrue("Expected '" + ILLEGAL[i][1] + "' for " + ILLEGAL[i][0] + ", was: " + message,
                    message != null && message.contains(ILLEGAL[i][1]));
        }
    }

    @Test
    public void testSameErrorThroughSax() throws Exception {
        for (int i = 0; i < ILLEGAL.length; i++) {
            assertEquals(ILLEGAL[i][0], nativeError(ILLEGAL[i][0], 0), saxError(ILLEGAL[i][0]));
        }
    }

    @Test
    public void testSameErrorHoweverTheInputIsDivided() throws Exception {
        for (int i = 0; i < ILLEGAL.length; i++) {
            String whole = nativeError(ILLEGAL[i][0], 0);
            for (int chunk = 1; chunk <= 3; chunk++) {
                assertEquals(ILLEGAL[i][0] + " in pieces of " + chunk, whole, nativeError(ILLEGAL[i][0], chunk));
            }
        }
    }

    @Test
    public void testLegalDocumentsAreAccepted() throws Exception {
        for (int i = 0; i < LEGAL.length; i++) {
            assertEquals(LEGAL[i], null, nativeError(LEGAL[i], 0));
            assertEquals(LEGAL[i], null, nativeError(LEGAL[i], 1));
            assertEquals(LEGAL[i], null, saxError(LEGAL[i]));
        }
    }

    /** Without namespace processing a colon is a name character like any
     *  other, and none of this applies. */
    @Test
    public void testNothingIsCheckedWithoutNamespaceProcessing() throws Exception {
        for (int i = 0; i < ILLEGAL.length; i++) {
            if (ILLEGAL[i][0].contains("xmlns:a=\"\"")) {
                continue;
            }
            XMLParser parser = new XMLParser();
            parser.setNamespaceAware(false);
            parser.setHandler(new RecordingXMLHandler());
            parser.parse(new ByteArrayInputStream(ILLEGAL[i][0].getBytes(StandardCharsets.UTF_8)));
        }
    }

    /** The events before the error are those of a document that was
     *  fine so far: nothing of the offending element's content follows. */
    @Test
    public void testNothingIsReportedAfterTheError() throws Exception {
        RecordingXMLHandler handler = new RecordingXMLHandler();
        XMLParser parser = new XMLParser();
        parser.setHandler(handler);
        try {
            parser.parse(new ByteArrayInputStream(
                    "<r><s a:b=\"1\" c=\"2\">text</s></r>".getBytes(StandardCharsets.UTF_8)));
            fail("Expected SAXException");
        } catch (SAXException e) {
            assertTrue("Was: " + e.getMessage(), e.getMessage().contains("Unbound namespace prefix: a"));
        }
        String events = handler.getEvents().toString();
        assertTrue("Was: " + events, events.startsWith("[startDocument, startElement:r, endAttributes, startElement:s"));
        assertTrue("Was: " + events, !events.contains("characters:text"));
        // The start tag was never completed.
        assertEquals("Was: " + events, events.indexOf("endAttributes"), events.lastIndexOf("endAttributes"));
    }

    /** More bindings, attributes and depth than the filter starts with
     *  room for. */
    @Test
    public void testManyBindingsAttributesAndLevels() throws Exception {
        StringBuilder xml = new StringBuilder();
        int levels = 100;
        for (int level = 0; level < levels; level++) {
            xml.append("<p").append(level).append(":e");
            for (int k = 0; k <= level; k++) {
                // Each level binds one more prefix and uses all of them.
                if (k == level) {
                    xml.append(" xmlns:p").append(k).append("=\"urn:p").append(k).append('"');
                }
                xml.append(" p").append(k).append(":a=\"").append(k).append('"');
            }
            xml.append('>');
        }
        for (int level = levels - 1; level >= 0; level--) {
            xml.append("</p").append(level).append(":e>");
        }
        assertEquals(null, nativeError(xml.toString(), 0));
        assertEquals(null, saxError(xml.toString()));
        // One of them bound to the same name as another makes two of the
        // innermost element's attributes the same.
        String clash = xml.toString().replace("xmlns:p99=\"urn:p99\"", "xmlns:p99=\"urn:p17\"");
        String message = nativeError(clash, 0);
        assertTrue("Was: " + message, message != null
                && message.contains("Duplicate attribute by expanded name: {urn:p17}a (qName: p99:a)"));
        assertEquals(message, saxError(clash));
    }

    /**
     * With more prefixes in scope than are searched one by one, a prefix
     * bound afresh by a descendant still means what the descendant says
     * for as long as the descendant lasts, and what it meant before
     * after that.
     */
    @Test
    public void testBindingAfreshAmongManyBindings() throws Exception {
        StringBuilder root = new StringBuilder("<r");
        for (int k = 0; k < 40; k++) {
            root.append(" xmlns:p").append(k).append("=\"urn:p").append(k).append('"');
        }
        root.append('>');
        String[][] cases = {
            // p5 and q are one namespace inside s.
            { "<s xmlns:p5=\"urn:other\" xmlns:q=\"urn:other\" p5:a=\"1\" q:a=\"2\"/>",
                "Duplicate attribute by expanded name: {urn:other}a (qName: q:a)" },
            // And q is gone after it.
            { "<s xmlns:p5=\"urn:other\" xmlns:q=\"urn:other\"/><t p5:a=\"1\" q:a=\"2\"/>",
                "Unbound namespace prefix: q" },
            // p5 is itself again after s, so no longer the same as q.
            { "<s xmlns:p5=\"urn:other\"><p5:u/></s><t xmlns:q=\"urn:other\" p5:a=\"1\" q:a=\"2\"/>", null },
            { "<s xmlns:p5=\"urn:other\"><p5:u/></s><t xmlns:q=\"urn:p5\" p5:a=\"1\" q:a=\"2\"/>",
                "Duplicate attribute by expanded name: {urn:p5}a (qName: q:a)" },
            { "<p0:s p39:a=\"1\"><p20:t xmlns:p20=\"urn:z\"/><p20:t/></p0:s>", null },
        };
        for (int i = 0; i < cases.length; i++) {
            String xml = root + cases[i][0] + "</r>";
            String message = nativeError(xml, 0);
            if (cases[i][1] == null) {
                assertEquals(cases[i][0], null, message);
            } else {
                assertTrue("Expected '" + cases[i][1] + "' for " + cases[i][0] + ", was: " + message,
                        message != null && message.contains(cases[i][1]));
            }
            assertEquals(cases[i][0], message, saxError(xml));
            assertEquals(cases[i][0], message, nativeError(xml, 7));
        }
    }

    /** More distinct names than the filter remembers at once, each used
     *  while its prefix is bound and again after it no longer is. */
    @Test
    public void testManyDistinctNames() throws Exception {
        StringBuilder bound = new StringBuilder("<r><s xmlns:p=\"urn:p\">");
        for (int i = 0; i < 2000; i++) {
            bound.append("<p:e").append(i).append(" p:a").append(i).append("=\"1\"/>");
        }
        bound.append("</s>");
        assertEquals(null, nativeError(bound + "</r>", 0));
        for (int i = 0; i < 2000; i += 97) {
            String message = nativeError(bound + "<p:e" + i + "/></r>", 0);
            assertTrue("Was: " + message, message != null && message.contains("Unbound namespace prefix: p"));
            message = nativeError(bound + "<t p:a" + i + "=\"1\"/></r>", 0);
            assertTrue("Was: " + message, message != null && message.contains("Unbound namespace prefix: p"));
        }
    }

    // ===== Helpers =====

    /** Parses a document through the native interface, whole or in
     *  pieces of the given size, and returns the message of the error it
     *  is rejected with, or null if it is accepted. */
    private static String nativeError(String xml, int chunk) {
        byte[] bytes = xml.getBytes(StandardCharsets.UTF_8);
        if (chunk == 0) {
            chunk = bytes.length;
        }
        XMLParser parser = new XMLParser();
        parser.setHandler(new RecordingXMLHandler());
        try {
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
            parser.close();
        } catch (SAXException e) {
            return String.valueOf(e.getMessage());
        }
        return null;
    }

    private static String saxError(String xml) throws Exception {
        final List<String> elements = new ArrayList<String>();
        Parser parser = new Parser();
        parser.setContentHandler(new DefaultHandler());
        try {
            parser.parse(new InputSource(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))));
        } catch (SAXException e) {
            return String.valueOf(e.getMessage());
        }
        return null;
    }

}

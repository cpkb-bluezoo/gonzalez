/*
 * ParserLimitsTest.java
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

import org.junit.Test;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXNotRecognizedException;
import org.xml.sax.SAXNotSupportedException;
import org.xml.sax.helpers.DefaultHandler;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Tests the limits that bound what the parser must remember: element
 * depth, name length and attributes per element. Each is tested at the
 * limit (accepted) and one beyond it (rejected), through the typed
 * settings of {@link XMLParser} and through the JDK property names that
 * {@link Parser} maps onto them.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ParserLimitsTest {

    private static final String DEPTH = "Element depth limit";
    private static final String NAME = "Name length limit";
    private static final String ATTRIBUTES = "Attribute count limit";

    // ===== Defaults =====

    @Test
    public void testDefaults() throws Exception {
        XMLParser parser = new XMLParser();
        assertEquals(1000, parser.getMaxElementDepth());
        assertEquals(4096, parser.getMaxNameLength());
        assertEquals(10000, parser.getMaxAttributesPerElement());

        Parser reader = new Parser();
        assertEquals(Integer.valueOf(1000), reader.getProperty("jdk.xml.maxElementDepth"));
        assertEquals(Integer.valueOf(4096), reader.getProperty("jdk.xml.maxXMLNameLimit"));
        assertEquals(Integer.valueOf(10000), reader.getProperty("jdk.xml.elementAttributeLimit"));
        assertEquals(Integer.valueOf(64000), reader.getProperty("jdk.xml.entityExpansionLimit"));
    }

    @Test
    public void testDefaultDepth() throws Exception {
        accept(new XMLParser(), nested(1000));
        reject(new XMLParser(), nested(1001), DEPTH);
    }

    @Test
    public void testDefaultNameLength() throws Exception {
        accept(new XMLParser(), "<" + name(4096) + "/>");
        reject(new XMLParser(), "<" + name(4097) + "/>", NAME);
    }

    @Test
    public void testDefaultAttributeCount() throws Exception {
        accept(new XMLParser(), withAttributes(10000));
        reject(new XMLParser(), withAttributes(10001), ATTRIBUTES);
    }

    // ===== Element depth =====

    @Test
    public void testElementDepth() throws Exception {
        XMLParser parser = new XMLParser();
        parser.setMaxElementDepth(3);
        accept(parser, "<a><b><c/></b></a>");
        parser.reset();
        accept(parser, "<a><b><c/><c/></b><b><c>text</c></b></a>");
        parser.reset();
        reject(parser, "<a><b><c><d/></c></b></a>", DEPTH);
        parser.reset();
        reject(parser, "<a><b><c><d>", DEPTH);
    }

    @Test
    public void testElementDepthUnlimited() throws Exception {
        XMLParser parser = new XMLParser();
        parser.setMaxElementDepth(0);
        accept(parser, nested(5000));
        parser.reset();
        parser.setMaxElementDepth(-1);
        accept(parser, nested(5000));
    }

    @Test
    public void testElementDepthCountsElementsFromEntities() throws Exception {
        XMLParser parser = new XMLParser();
        parser.setMaxElementDepth(2);
        reject(parser, "<!DOCTYPE a [<!ENTITY e \"<c/>\">]><a><b>&e;</b></a>", DEPTH);
        parser.reset();
        accept(parser, "<!DOCTYPE a [<!ENTITY e \"<b/>\">]><a>&e;</a>");
    }

    // ===== Name length =====

    @Test
    public void testNameLengthAppliesToEveryKindOfName() throws Exception {
        String ok = name(8);
        String tooLong = name(9);
        String[] templates = {
            "<N/>",
            "<N>text</N>",
            "<r N=\"v\"/>",
            "<r a=\"1\" N=\"v\"/>",
            "<r><?N data?></r>",
            "<?N?><r/>",
        };
        for (int i = 0; i < templates.length; i++) {
            XMLParser parser = new XMLParser();
            parser.setMaxNameLength(8);
            accept(parser, templates[i].replace("N", ok));
            parser.reset();
            reject(parser, templates[i].replace("N", tooLong), NAME);
        }
    }

    @Test
    public void testNameLengthAppliesToReferences() throws Exception {
        XMLParser parser = new XMLParser();
        parser.setMaxNameLength(8);
        String dtd = "<!DOCTYPE r [<!ENTITY " + name(8) + " \"x\">]>";
        accept(parser, dtd + "<r a=\"&" + name(8) + ";\">&" + name(8) + ";</r>");
        parser.reset();
        reject(parser, "<r>&" + name(9) + ";</r>", NAME);
        parser.reset();
        reject(parser, "<r a=\"&" + name(9) + ";\"/>", NAME);

        // Leading zeros are legal in a character reference, to any number.
        parser.reset();
        accept(parser, "<r>&#00000065;&#x00000041;</r>");
        parser.reset();
        reject(parser, "<r>&#000000065;</r>", "Character reference: name length limit");
        parser.reset();
        reject(parser, "<r a=\"&#x000000041;\"/>", "Character reference: name length limit");
    }

    @Test
    public void testEndTagNameIsLimitedToo() throws Exception {
        XMLParser parser = new XMLParser();
        parser.setMaxNameLength(8);
        reject(parser, "<r></" + name(9) + ">", NAME);
    }

    /**
     * The point of the limit: a name that never ends must be rejected
     * while it is still arriving, not held until it does. Each prefix is
     * followed by name characters only, with no terminator, and the error
     * must come from receive() and not have to wait for close().
     */
    @Test
    public void testUnterminatedNameIsRejectedWhileArriving() throws Exception {
        String[] prefixes = { "<", "<r ", "<r a=\"1\" ", "<r></", "<r><?", "<r>&", "<r a=\"&" };
        for (int i = 0; i < prefixes.length; i++) {
            XMLParser parser = new XMLParser();
            parser.setHandler(new RecordingXMLHandler());
            String message = null;
            try {
                ByteBuffer buffer = ByteBuffer.allocate(1024);
                send(parser, buffer, prefixes[i].getBytes(StandardCharsets.UTF_8));
                byte[] more = name(400).getBytes(StandardCharsets.UTF_8);
                // Well past the default limit of 4096, in pieces.
                for (int n = 0; n < 20; n++) {
                    send(parser, buffer, more);
                }
            } catch (SAXException e) {
                message = e.getMessage();
            }
            assertTrue("After " + prefixes[i] + " expected the name limit from receive(), was: " + message,
                    message != null && message.contains(NAME));
        }
    }

    @Test
    public void testUnterminatedCharacterReferenceIsRejectedWhileArriving() throws Exception {
        XMLParser parser = new XMLParser();
        parser.setHandler(new RecordingXMLHandler());
        String message = null;
        try {
            ByteBuffer buffer = ByteBuffer.allocate(1024);
            send(parser, buffer, "<r>&#".getBytes(StandardCharsets.UTF_8));
            byte[] zeros = new byte[400];
            java.util.Arrays.fill(zeros, (byte) '0');
            for (int n = 0; n < 20; n++) {
                send(parser, buffer, zeros);
            }
        } catch (SAXException e) {
            message = e.getMessage();
        }
        assertTrue("Was: " + message, message != null && message.contains("Character reference: name length limit"));
    }

    @Test
    public void testNameLengthUnlimited() throws Exception {
        XMLParser parser = new XMLParser();
        parser.setMaxNameLength(0);
        accept(parser, "<" + name(20000) + " " + name(20000) + "=\"v\"/>");
    }

    // ===== Attributes per element =====

    @Test
    public void testAttributeCount() throws Exception {
        XMLParser parser = new XMLParser();
        parser.setMaxAttributesPerElement(3);
        accept(parser, "<r a=\"1\" b=\"2\" c=\"3\"><s a=\"1\" b=\"2\" c=\"3\"/></r>");
        parser.reset();
        reject(parser, "<r a=\"1\" b=\"2\" c=\"3\" d=\"4\"/>", ATTRIBUTES);
        parser.reset();
        reject(parser, "<r><s a=\"1\" b=\"2\" c=\"3\" d=\"4\"/></r>", ATTRIBUTES);
    }

    @Test
    public void testAttributeCountIncludesNamespaceDeclarations() throws Exception {
        XMLParser parser = new XMLParser();
        parser.setMaxAttributesPerElement(3);
        accept(parser, "<r xmlns=\"urn:d\" xmlns:p=\"urn:p\" p:a=\"1\"/>");
        parser.reset();
        reject(parser, "<r xmlns=\"urn:d\" xmlns:p=\"urn:p\" p:a=\"1\" b=\"2\"/>", ATTRIBUTES);
    }

    @Test
    public void testAttributeCountExcludesDefaults() throws Exception {
        XMLParser parser = new XMLParser();
        parser.setMaxAttributesPerElement(1);
        accept(parser, "<!DOCTYPE r [<!ATTLIST r x CDATA \"1\" y CDATA \"2\" z CDATA \"3\">]><r a=\"1\"/>");
    }

    @Test
    public void testAttributeCountUnlimited() throws Exception {
        XMLParser parser = new XMLParser();
        parser.setMaxAttributesPerElement(0);
        accept(parser, withAttributes(20000));
    }

    // ===== Life cycle =====

    @Test
    public void testLimitsSurviveReset() throws Exception {
        XMLParser parser = new XMLParser();
        parser.setMaxElementDepth(2);
        parser.setMaxNameLength(4);
        parser.setMaxAttributesPerElement(1);
        reject(parser, "<a><b><c/></b></a>", DEPTH);
        parser.reset();
        assertEquals(2, parser.getMaxElementDepth());
        assertEquals(4, parser.getMaxNameLength());
        assertEquals(1, parser.getMaxAttributesPerElement());
        reject(parser, "<abcde/>", NAME);
        parser.reset();
        reject(parser, "<a b=\"1\" c=\"2\"/>", ATTRIBUTES);
        parser.reset();
        accept(parser, "<a b=\"1\"><abcd/></a>");
    }

    // ===== The JDK property names on the SAX wrapper =====

    @Test
    public void testJdkPropertyNames() throws Exception {
        String[][] cases = {
            { "jdk.xml.maxElementDepth", "<a><b><c/></b></a>", DEPTH },
            { "jdk.xml.maxXMLNameLimit", "<abc/>", NAME },
            { "jdk.xml.elementAttributeLimit", "<a b=\"1\" c=\"2\" d=\"3\"/>", ATTRIBUTES },
            { "jdk.xml.entityExpansionLimit", "<!DOCTYPE a [<!ENTITY e \"x\">]><a>&e;&e;&e;</a>",
                "Entity expansion limit" },
        };
        String legacy = "http://www.oracle.com/xml/jaxp/properties/";
        for (int i = 0; i < cases.length; i++) {
            String name = cases[i][0];
            String xml = cases[i][1];
            // Every document is fine by default, and exceeds a limit of 2.
            saxAccept(new Parser(), xml);

            Parser byInteger = new Parser();
            byInteger.setProperty(name, Integer.valueOf(2));
            assertEquals(name, Integer.valueOf(2), byInteger.getProperty(name));
            saxReject(byInteger, xml, cases[i][2]);

            Parser byString = new Parser();
            byString.setProperty(name, "2");
            assertEquals(name, Integer.valueOf(2), byString.getProperty(name));
            saxReject(byString, xml, cases[i][2]);

            Parser byLegacyName = new Parser();
            byLegacyName.setProperty(legacy + name.substring("jdk.xml.".length()), Integer.valueOf(2));
            assertEquals(name, Integer.valueOf(2), byLegacyName.getProperty(name));
            saxReject(byLegacyName, xml, cases[i][2]);

            Parser unlimited = new Parser();
            unlimited.setProperty(name, Integer.valueOf(0));
            saxAccept(unlimited, xml);
        }
    }

    @Test
    public void testJdkAndGonzalezExpansionPropertiesAreOneSetting() throws Exception {
        Parser parser = new Parser();
        parser.setProperty("jdk.xml.entityExpansionLimit", Integer.valueOf(7));
        assertEquals(Integer.valueOf(7),
                parser.getProperty("http://www.nongnu.org/gonzalez/properties/entity-expansion-limit"));
    }

    @Test
    public void testBadLimitValuesAreNotSupported() throws Exception {
        Parser parser = new Parser();
        Object[] bad = { "many", "", null, Boolean.TRUE, Double.valueOf(1.5) };
        for (int i = 0; i < bad.length; i++) {
            try {
                parser.setProperty("jdk.xml.maxElementDepth", bad[i]);
                fail("Expected SAXNotSupportedException for " + bad[i]);
            } catch (SAXNotSupportedException e) {
                // expected
            }
        }
        assertEquals(Integer.valueOf(1000), parser.getProperty("jdk.xml.maxElementDepth"));
    }

    @Test
    public void testOtherJdkPropertiesAreNotRecognised() throws Exception {
        Parser parser = new Parser();
        try {
            parser.setProperty("jdk.xml.totalEntitySizeLimit", Integer.valueOf(1));
            fail("Expected SAXNotRecognizedException");
        } catch (SAXNotRecognizedException e) {
            // expected
        }
        try {
            parser.getProperty("jdk.xml.totalEntitySizeLimit");
            fail("Expected SAXNotRecognizedException");
        } catch (SAXNotRecognizedException e) {
            // expected
        }
    }

    // ===== Helpers =====

    private static String name(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append((char) ('a' + (i % 26)));
        }
        return sb.toString();
    }

    /** Gives the parser the next bytes through the caller's buffer,
     *  keeping whatever it leaves unconsumed for the next call. */
    private static void send(XMLParser parser, ByteBuffer buffer, byte[] bytes) throws SAXException {
        buffer.put(bytes);
        buffer.flip();
        parser.receive(buffer);
        buffer.compact();
    }

    /** {@code depth} elements, one inside the next. */
    private static String nested(int depth) {
        StringBuilder sb = new StringBuilder(depth * 7);
        for (int i = 0; i < depth; i++) {
            sb.append("<e>");
        }
        for (int i = 0; i < depth; i++) {
            sb.append("</e>");
        }
        return sb.toString();
    }

    /** One element with {@code count} distinct attributes. */
    private static String withAttributes(int count) {
        StringBuilder sb = new StringBuilder(count * 12);
        sb.append("<r");
        for (int i = 0; i < count; i++) {
            sb.append(" a").append(i).append("=\"\"");
        }
        sb.append("/>");
        return sb.toString();
    }

    private static void accept(XMLParser parser, String xml) throws Exception {
        RecordingXMLHandler handler = new RecordingXMLHandler();
        parser.setHandler(handler);
        parser.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        assertTrue(handler.getEvents().contains("endDocument"));
    }

    private static void reject(XMLParser parser, String xml, String expected) throws Exception {
        parser.setHandler(new RecordingXMLHandler());
        try {
            parser.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        } catch (SAXException e) {
            assertTrue("Expected '" + expected + "', was: " + e.getMessage(),
                    e.getMessage() != null && e.getMessage().contains(expected));
            return;
        }
        fail("Expected '" + expected + "' for a document of " + xml.length() + " characters");
    }

    private static void saxAccept(Parser parser, String xml) throws Exception {
        parser.setContentHandler(new DefaultHandler());
        parser.parse(new InputSource(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))));
    }

    private static void saxReject(Parser parser, String xml, String expected) throws Exception {
        parser.setContentHandler(new DefaultHandler());
        try {
            parser.parse(new InputSource(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))));
        } catch (SAXException e) {
            assertTrue("Expected '" + expected + "', was: " + e.getMessage(),
                    e.getMessage() != null && e.getMessage().contains(expected));
            return;
        }
        fail("Expected '" + expected + "' for: " + xml);
    }

}

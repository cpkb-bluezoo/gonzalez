/*
 * AttributeValueDeliveryTest.java
 * Copyright (C) 2026 Chris Burdess
 */
package org.bluezoo.gonzalez;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.helpers.DefaultHandler;

import static org.junit.Assert.assertEquals;

/**
 * An attribute value is delivered to the SAX adapter as a range of the
 * scanner's buffer, and to any other handler as a CharBuffer. The two routes
 * must give the same value, however the value is divided, and a handler that
 * is not the adapter must still see every chunk.
 */
public class AttributeValueDeliveryTest {

    private static InputStream stream(String xml, int chunk) {
        InputStream in = new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8));
        return chunk > 0 ? new ChunkedInputStream(in, chunk) : in;
    }

    /** Through the SAX adapter: "element@name=value" for each attribute. */
    private static List<String> viaSax(String xml, int chunk) throws Exception {
        final List<String> out = new ArrayList<String>();
        Parser parser = new Parser();
        parser.setFeature("http://xml.org/sax/features/namespaces", true);
        parser.setFeature("http://xml.org/sax/features/namespace-prefixes", true);
        parser.setContentHandler(new DefaultHandler() {
            @Override
            public void startElement(String uri, String localName, String qName, Attributes a) {
                for (int i = 0; i < a.getLength(); i++) {
                    if (a.getQName(i).startsWith("xmlns")) {
                        continue; // a declaration: the native route reports it as a namespace
                    }
                    out.add(qName + "@" + a.getQName(i) + "=" + a.getValue(i));
                }
            }
        });
        parser.parse(new InputSource(stream(xml, chunk)));
        return out;
    }

    /** Through a handler of our own, which is not the adapter: the CharBuffer route. */
    private static List<String> viaHandler(String xml, int chunk) throws Exception {
        RecordingXMLHandler handler = new RecordingXMLHandler();
        XMLParser parser = new XMLParser();
        parser.setNamespaceAware(true);
        parser.setHandler(handler);
        parser.parse(stream(xml, chunk));
        List<String> out = new ArrayList<String>();
        String element = null;
        for (String event : handler.getEvents()) {
            if (event.startsWith("startElement:")) {
                element = event.substring("startElement:".length());
            } else if (event.startsWith("attribute:")) {
                out.add(element + "@" + event.substring("attribute:".length()));
            }
        }
        return out;
    }

    private static final int[] CHUNKS = { 0, 1, 2, 3, 5, 7, 11, 64 };

    private static void assertRoutesAgree(String xml, String expected) throws Exception {
        for (int chunk : CHUNKS) {
            assertEquals("sax, chunk " + chunk, expected, viaSax(xml, chunk).toString());
            assertEquals("handler, chunk " + chunk, expected, viaHandler(xml, chunk).toString());
        }
    }

    @Test
    public void plainValuesInOneChunk() throws Exception {
        assertRoutesAgree("<r a='1' b=\"two\" c='three words'/>",
                "[r@a=1, r@b=two, r@c=three words]");
    }

    @Test
    public void emptyValues() throws Exception {
        assertRoutesAgree("<r a='' b=\"\"><e c=''/></r>", "[r@a=, r@b=, e@c=]");
    }

    @Test
    public void valuesDividedByEntityAndCharacterReferences() throws Exception {
        assertRoutesAgree("<r a='x&amp;y' b='&lt;&gt;&quot;&apos;' c='&#65;&#x42;C' d='&amp;'/>",
                "[r@a=x&y, r@b=<>\"', r@c=ABC, r@d=&]");
    }

    @Test
    public void whiteSpaceInAValueIsNormalizedToSpaces() throws Exception {
        assertRoutesAgree("<r a='x\ty\nz' b='  p  q  '/>", "[r@a=x y z, r@b=  p  q  ]");
    }

    @Test
    public void namespaceDeclarationsAreNotAttributes() throws Exception {
        assertRoutesAgree("<r xmlns='d' xmlns:p='u' p:a='1' b='2'><p:e p:c='3'/></r>",
                "[r@p:a=1, r@b=2, p:e@p:c=3]");
    }

    @Test
    public void aLongValueAcrossManyChunks() throws Exception {
        StringBuilder v = new StringBuilder();
        for (int i = 0; i < 500; i++) {
            v.append("word").append(i).append(' ');
        }
        assertRoutesAgree("<r a='" + v + "' b='after'/>", "[r@a=" + v + ", r@b=after]");
    }

    @Test
    public void aTypedAttributeNormalizedByTheDtdStillWorks() throws Exception {
        String xml = "<!DOCTYPE r [<!ELEMENT r EMPTY><!ATTLIST r t NMTOKENS #IMPLIED c CDATA #IMPLIED>]>"
                + "<r t='  a   b  ' c='  keep   this  '/>";
        for (int chunk : CHUNKS) {
            assertEquals("chunk " + chunk, "[r@t=a b, r@c=  keep   this  ]",
                    viaSax(xml, chunk).toString());
        }
    }
}

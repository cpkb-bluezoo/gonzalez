/*
 * SAXElementNameResolutionTest.java
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
import org.xml.sax.helpers.DefaultHandler;

import static org.junit.Assert.assertEquals;

/**
 * An element name resolves to a namespace by the bindings in scope where it
 * is used, so the same name must resolve differently as they change, whatever
 * the adapter remembers of earlier elements.
 */
public class SAXElementNameResolutionTest {

    /** Returns "uri|local" for each start tag, in document order. */
    private static List<String> resolve(String xml) throws Exception {
        final List<String> names = new ArrayList<String>();
        Parser parser = new Parser();
        parser.setFeature("http://xml.org/sax/features/namespaces", true);
        parser.setContentHandler(new DefaultHandler() {
            @Override
            public void startElement(String uri, String localName, String qName,
                    Attributes atts) {
                names.add(uri + "|" + localName);
            }
        });
        parser.parse(new InputSource(new ByteArrayInputStream(
                xml.getBytes(StandardCharsets.UTF_8))));
        return names;
    }

    @Test
    public void prefixRebindingInNestedAndSiblingElements() throws Exception {
        String xml = "<r xmlns:a='u0'><a:x xmlns:a='u1'><a:x xmlns:a='u2'><a:x/></a:x>"
            + "<a:x/></a:x><a:x/><a:x xmlns:a='u3'/><a:x/></r>";
        assertEquals("[|r, u1|x, u2|x, u2|x, u1|x, u0|x, u3|x, u0|x]",
                resolve(xml).toString());
    }

    @Test
    public void defaultNamespaceChangesAndIsRestored() throws Exception {
        String xml = "<r><x/><y xmlns='d1'><x/><z xmlns='d2'><x/></z><x/></y><x/></r>";
        assertEquals("[|r, |x, d1|y, d1|x, d2|z, d2|x, d1|x, |x]",
                resolve(xml).toString());
    }

    @Test
    public void defaultNamespaceCanBeUndeclared() throws Exception {
        String xml = "<r xmlns='d'><x/><y xmlns=''><x/></y><x/></r>";
        assertEquals("[d|r, d|x, |y, |x, d|x]", resolve(xml).toString());
    }

    @Test
    public void sameQualifiedNameDifferentPrefixBindingAcrossSiblings() throws Exception {
        String xml = "<r><p:e xmlns:p='one'/><p:e xmlns:p='two'/><p:e xmlns:p='one'/></r>";
        assertEquals("[|r, one|e, two|e, one|e]", resolve(xml).toString());
    }

    @Test
    public void manyDistinctNamesStillResolveCorrectly() throws Exception {
        StringBuilder xml = new StringBuilder("<r xmlns='d'>");
        StringBuilder expected = new StringBuilder("[d|r");
        for (int round = 0; round < 3; round++) {
            for (int i = 0; i < 200; i++) {
                xml.append("<n").append(i).append("/>");
                expected.append(", d|n").append(i);
            }
        }
        xml.append("</r>");
        expected.append("]");
        assertEquals(expected.toString(), resolve(xml.toString()).toString());
    }
}

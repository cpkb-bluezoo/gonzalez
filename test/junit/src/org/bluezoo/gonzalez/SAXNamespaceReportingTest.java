/*
 * SAXNamespaceReportingTest.java
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

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.helpers.DefaultHandler;

import static org.junit.Assert.assertEquals;

/**
 * What the SAX interface reports of namespaces, and of attributes: the
 * names the namespace filter settles and the SAX adapter passes on, which
 * declarations are reported as an element starts and ends, and the values
 * of attributes whether or not the scanner can report one in a single piece.
 */
public class SAXNamespaceReportingTest {

    private static class Recorder extends DefaultHandler {
        final List<String> events = new ArrayList<String>();
        private final StringBuilder text = new StringBuilder();

        private void flushText() {
            if (text.length() > 0) {
                events.add("text(" + text + ")");
                text.setLength(0);
            }
        }

        @Override
        public void startPrefixMapping(String prefix, String uri) {
            flushText();
            events.add("startPrefixMapping(" + prefix + "," + uri + ")");
        }

        @Override
        public void endPrefixMapping(String prefix) {
            flushText();
            events.add("endPrefixMapping(" + prefix + ")");
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes atts) {
            flushText();
            StringBuilder b = new StringBuilder();
            b.append("start(").append(uri).append("|").append(localName).append("|").append(qName);
            for (int i = 0; i < atts.getLength(); i++) {
                b.append(" ").append(atts.getURI(i)).append("|").append(atts.getLocalName(i))
                        .append("|").append(atts.getQName(i)).append("=").append(atts.getValue(i));
            }
            events.add(b.append(")").toString());
        }

        @Override
        public void endElement(String uri, String localName, String qName) {
            flushText();
            events.add("end(" + uri + "|" + localName + "|" + qName + ")");
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            text.append(ch, start, length);
        }
    }

    private static List<String> parse(String xml) throws Exception {
        return parse(xml, false);
    }

    private static List<String> parse(String xml, boolean namespacePrefixes) throws Exception {
        Parser parser = new Parser();
        parser.setFeature("http://xml.org/sax/features/namespaces", true);
        parser.setFeature("http://xml.org/sax/features/namespace-prefixes", namespacePrefixes);
        Recorder recorder = new Recorder();
        parser.setContentHandler(recorder);
        parser.parse(new InputSource(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))));
        return recorder.events;
    }

    @Test
    public void declarationsAreReportedInOrderAndEndedInReverse() throws Exception {
        assertEquals(Arrays.asList(
                "startPrefixMapping(,d)",
                "startPrefixMapping(a,ua)",
                "startPrefixMapping(b,ub)",
                "start(d|r|r)",
                "end(d|r|r)",
                "endPrefixMapping(b)",
                "endPrefixMapping(a)",
                "endPrefixMapping()"),
                parse("<r xmlns='d' xmlns:a='ua' xmlns:b='ub'/>"));
    }

    @Test
    public void anElementThatDeclaresNothingReportsNoMappings() throws Exception {
        assertEquals(Arrays.asList(
                "startPrefixMapping(,d)",
                "start(d|r|r)",
                "start(d|x|x)",
                "end(d|x|x)",
                "end(d|r|r)",
                "endPrefixMapping()"),
                parse("<r xmlns='d'><x/></r>"));
    }

    @Test
    public void aDefaultNamespaceIsHiddenAndRestoredAndCanBeUndeclared() throws Exception {
        assertEquals(Arrays.asList(
                "startPrefixMapping(,d1)",
                "start(d1|a|a)",
                "startPrefixMapping(,)",
                "start(|b|b)",
                "start(|c|c)",
                "end(|c|c)",
                "end(|b|b)",
                "endPrefixMapping()",
                "start(d1|d|d)",
                "end(d1|d|d)",
                "end(d1|a|a)",
                "endPrefixMapping()"),
                parse("<a xmlns='d1'><b xmlns=''><c/></b><d/></a>"));
    }

    @Test
    public void aPrefixedElementAndAttributesTakeTheirNamespacesWhateverTheOrder() throws Exception {
        // The attributes name prefixes that are declared later in the same
        // start tag, and two prefixes share a namespace.
        assertEquals(Arrays.asList(
                "startPrefixMapping(p,up)",
                "startPrefixMapping(q,up)",
                "startPrefixMapping(s,us)",
                "start(up|r|p:r up|a|q:a=1 us|b|s:b=2 |c|c=3)",
                "end(up|r|p:r)",
                "endPrefixMapping(s)",
                "endPrefixMapping(q)",
                "endPrefixMapping(p)"),
                parse("<p:r q:a='1' s:b='2' c='3' xmlns:p='up' xmlns:q='up' xmlns:s='us'/>"));
    }

    @Test
    public void aPrefixRebindingAppliesToItsOwnElementAndIsRestored() throws Exception {
        assertEquals(Arrays.asList(
                "startPrefixMapping(p,u1)",
                "start(u1|r|p:r)",
                "startPrefixMapping(p,u2)",
                "start(u2|x|p:x u2|k|p:k=v)",
                "end(u2|x|p:x)",
                "endPrefixMapping(p)",
                "start(u1|y|p:y u1|k|p:k=w)",
                "end(u1|y|p:y)",
                "end(u1|r|p:r)",
                "endPrefixMapping(p)"),
                parse("<p:r xmlns:p='u1'><p:x xmlns:p='u2' p:k='v'/><p:y p:k='w'/></p:r>"));
    }

    @Test
    public void thePredeclaredXmlPrefixNeedsNoDeclaration() throws Exception {
        assertEquals(Arrays.asList(
                "start(|r|r http://www.w3.org/XML/1998/namespace|lang|xml:lang=en)",
                "end(|r|r)"),
                parse("<r xml:lang='en'/>"));
    }

    @Test
    public void manyDeclarationsIncludingADefaultOne() throws Exception {
        // More than the filter keeps in its arrays before it builds a map.
        StringBuilder xml = new StringBuilder("<r xmlns='dflt'");
        for (int i = 0; i < 50; i++) {
            xml.append(" xmlns:p").append(i).append("='u").append(i).append("'");
        }
        xml.append("><p7:a p49:k='v'><b xmlns='inner'><p0:c/></b><d/></p7:a></r>");
        List<String> events = parse(xml.toString());
        assertEquals("startPrefixMapping(,dflt)", events.get(0));
        assertEquals("startPrefixMapping(p49,u49)", events.get(50));
        List<String> rest = events.subList(51, events.size());
        assertEquals(Arrays.asList(
                "start(dflt|r|r)",
                "start(u7|a|p7:a u49|k|p49:k=v)",
                "startPrefixMapping(,inner)",
                "start(inner|b|b)",
                "start(u0|c|p0:c)",
                "end(u0|c|p0:c)",
                "end(inner|b|b)",
                "endPrefixMapping()",
                "start(dflt|d|d)",
                "end(dflt|d|d)",
                "end(u7|a|p7:a)",
                "end(dflt|r|r)"),
                rest.subList(0, 12));
        assertEquals("endPrefixMapping(p49)", rest.get(12));
        assertEquals("endPrefixMapping()", rest.get(rest.size() - 1));
    }

    @Test
    public void theDeclarationsAreAlsoAttributesWhenPrefixesAreWanted() throws Exception {
        List<String> events = parse("<r xmlns='d' xmlns:p='up' p:k='1'/>", true);
        assertEquals("startPrefixMapping(,d)", events.get(0));
        assertEquals("startPrefixMapping(p,up)", events.get(1));
        String start = events.get(2);
        assertEquals(true, start.startsWith("start(d|r|r "));
        assertEquals(true, start.contains("xmlns=d"));
        assertEquals(true, start.contains("xmlns:p=up"));
        assertEquals(true, start.contains("p|k|p:k=1"));
    }

    // ===== Attributes, whether or not they arrive in one piece =====

    private static final String ATTRIBUTE_DOCUMENT = "<r a=\"1\" b='two' c=\"\" d=\"x&amp;y\""
            + " e=\"line1&#10;line2\" f=\"tab\tin\" g=\"q'q\" h='q\"q' i=\"na\u00efve\""
            + " j=\"one\ntwo\r\nthree\" xml:space=\"preserve\">"
            + "<s k=\"&lt;&gt;\"/><t l='long " + repeat("0123456789", 300) + "'/></r>";

    private static String repeat(String s, int n) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) {
            b.append(s);
        }
        return b.toString();
    }

    @Test
    public void attributeValuesAreTheSameWhateverTheScannerDoesToReportThem() throws Exception {
        List<String> events = parse(ATTRIBUTE_DOCUMENT);
        assertEquals("start(|r|r |a|a=1 |b|b=two |c|c= |d|d=x&y |e|e=line1\nline2 |f|f=tab in |g|g=q'q"
                + " |h|h=q\"q |i|i=na\u00efve |j|j=one two three"
                + " http://www.w3.org/XML/1998/namespace|space|xml:space=preserve)",
                events.get(0));
        assertEquals("start(|s|s |k|k=<>)", events.get(1));
        assertEquals("start(|t|t |l|l=long " + repeat("0123456789", 300) + ")", events.get(3));
    }

    @Test
    public void attributesAreTheSameWhereverTheInputIsSplit() throws Exception {
        ChunkFuzzer.assertChunkInvariant("attributes", ATTRIBUTE_DOCUMENT.getBytes(StandardCharsets.UTF_8));
        ChunkFuzzer.assertChunkInvariant("namespaces",
                ("<p:r xmlns:p='up' xmlns='d' q:a='1' xmlns:q='uq' b=\"2\"><x xmlns='' p:c='3'/></p:r>")
                        .getBytes(StandardCharsets.UTF_8));
    }
}

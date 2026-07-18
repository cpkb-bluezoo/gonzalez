/*
 * SAXAdapterTest.java
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

import java.nio.CharBuffer;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * M0 verification: since no scanner exists yet to drive {@link SAXAdapter}
 * through {@link XMLHandler}, these tests hand-construct representative
 * XMLHandler call sequences (as a future scanner would emit them) and assert
 * on the resulting SAX event stream via {@link RecordingSaxHandler} - the
 * same comparison primitive later milestones use for differential testing
 * against the current parser.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class SAXAdapterTest {

    private static CharBuffer cb(String s) {
        return CharBuffer.wrap(s);
    }

    @Test
    public void testSimpleElementWithAttributeAndText_namespaceUnaware() throws Exception {
        SAXAdapter adapter = new SAXAdapter(false);
        RecordingSaxHandler sink = new RecordingSaxHandler();
        adapter.setContentHandler(sink);

        adapter.startDocument();
        adapter.startElement("root");
        adapter.attribute("attr", cb("val"));
        adapter.endAttributes();
        adapter.characters(cb("text"));
        adapter.endElement();
        adapter.endDocument();

        List<String> expected = Arrays.asList(
                "startDocument()",
                "startElement(,root,root,[ attr=val(CDATA)])",
                "characters:text",
                "endElement(,root,root)",
                "endDocument()");
        assertEquals(expected, sink.getEvents());
    }

    @Test
    public void testNestedAndEmptyElements_namespaceUnaware() throws Exception {
        SAXAdapter adapter = new SAXAdapter(false);
        RecordingSaxHandler sink = new RecordingSaxHandler();
        adapter.setContentHandler(sink);

        // <root><child/><sibling>x</sibling></root>
        adapter.startDocument();
        adapter.startElement("root");
        adapter.endAttributes();
        adapter.startElement("child");
        adapter.endAttributes();
        adapter.endElement();
        adapter.startElement("sibling");
        adapter.endAttributes();
        adapter.characters(cb("x"));
        adapter.endElement();
        adapter.endElement();
        adapter.endDocument();

        List<String> expected = Arrays.asList(
                "startDocument()",
                "startElement(,root,root,[])",
                "startElement(,child,child,[])",
                "endElement(,child,child)",
                "startElement(,sibling,sibling,[])",
                "characters:x",
                "endElement(,sibling,sibling)",
                "endElement(,root,root)",
                "endDocument()");
        assertEquals(expected, sink.getEvents());
    }

    @Test
    public void testXmlnsReportedAsPlainAttribute_namespaceUnaware() throws Exception {
        // In namespace-unaware mode, xmlns genuinely is just an attribute and
        // must be reported as one (no namespace() event, no resolution).
        SAXAdapter adapter = new SAXAdapter(false);
        RecordingSaxHandler sink = new RecordingSaxHandler();
        adapter.setContentHandler(sink);

        // <root xmlns="urn:x" attr="v"/>
        adapter.startDocument();
        adapter.startElement("root");
        adapter.attribute("xmlns", cb("urn:x"));
        adapter.attribute("attr", cb("v"));
        adapter.endAttributes();
        adapter.endElement();
        adapter.endDocument();

        List<String> expected = Arrays.asList(
                "startDocument()",
                "startElement(,root,root,[ xmlns=urn:x(CDATA); attr=v(CDATA)])",
                "endElement(,root,root)",
                "endDocument()");
        assertEquals(expected, sink.getEvents());
    }

    @Test
    public void testDefaultAndPrefixedNamespace_attributeOrderIndependence() throws Exception {
        // <root xmlns="urn:default" xmlns:p="urn:p" p:attr="v1" attr2="v2">
        //   <p:child/>
        // </root>
        //
        // The prefixed attribute is reported (via attribute()) BEFORE its
        // governing xmlns:p declaration (via namespace()) - this must still
        // resolve correctly, matching XML's rule that attribute order within
        // a start tag doesn't affect namespace resolution, and exercising
        // the same resolve-at-endAttributes()-time design as
        // ContentParser.fireStartElement/SAXAttributes.resolveAttributeNamespaces.
        SAXAdapter adapter = new SAXAdapter(true);
        RecordingSaxHandler sink = new RecordingSaxHandler();
        adapter.setContentHandler(sink);

        adapter.startDocument();
        adapter.startElement("root");
        adapter.namespace("", "urn:default");
        adapter.attribute("p:attr", cb("v1"));
        adapter.namespace("p", "urn:p");
        adapter.attribute("attr2", cb("v2"));
        adapter.endAttributes();

        adapter.startElement("p:child");
        adapter.endAttributes();
        adapter.endElement();

        adapter.endElement();
        adapter.endDocument();

        List<String> expected = Arrays.asList(
                "startDocument()",
                "startPrefixMapping(,urn:default)",
                "startPrefixMapping(p,urn:p)",
                "startElement(urn:default,root,root,[urn:p attr=v1(CDATA); attr2=v2(CDATA)])",
                "startElement(urn:p,child,p:child,[])",
                "endElement(urn:p,child,p:child)",
                "endElement(urn:default,root,root)",
                "endPrefixMapping(p)",
                "endPrefixMapping()",
                "endDocument()");
        assertEquals(expected, sink.getEvents());
    }

    @Test
    public void testCommentAndProcessingInstruction() throws Exception {
        SAXAdapter adapter = new SAXAdapter(false);
        RecordingSaxHandler sink = new RecordingSaxHandler();
        adapter.setContentHandler(sink);
        adapter.setLexicalHandler(sink);

        adapter.startDocument();
        adapter.comment(cb(" a comment "));
        adapter.processingInstruction("target", cb("pi data"));
        adapter.startElement("root");
        adapter.endAttributes();
        adapter.endElement();
        adapter.endDocument();

        List<String> expected = Arrays.asList(
                "startDocument()",
                "comment: a comment ",
                "processingInstruction(target,pi data)",
                "startElement(,root,root,[])",
                "endElement(,root,root)",
                "endDocument()");
        assertEquals(expected, sink.getEvents());
    }

    @Test
    public void testProcessingInstructionWithNullData() throws Exception {
        SAXAdapter adapter = new SAXAdapter(false);
        RecordingSaxHandler sink = new RecordingSaxHandler();
        adapter.setContentHandler(sink);

        adapter.startDocument();
        adapter.processingInstruction("target", null);
        adapter.endDocument();

        List<String> expected = Arrays.asList(
                "startDocument()",
                "processingInstruction(target,)",
                "endDocument()");
        assertEquals(expected, sink.getEvents());
    }

    @Test
    public void testDuplicateAttributeDetection_namespaceUnaware() throws Exception {
        SAXAdapter adapter = new SAXAdapter(false);
        RecordingSaxHandler sink = new RecordingSaxHandler();
        adapter.setContentHandler(sink);

        adapter.startDocument();
        adapter.startElement("root");
        adapter.attribute("attr", cb("v1"));
        try {
            adapter.attribute("attr", cb("v2"));
            org.junit.Assert.fail("expected SAXException for duplicate attribute");
        } catch (org.xml.sax.SAXException e) {
            // expected - NamespaceException from SAXAttributes.addAttribute,
            // wrapped via XMLHandler.fatalError()
        }
    }

}

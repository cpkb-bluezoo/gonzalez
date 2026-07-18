/*
 * NamespaceFilterTest.java
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
import org.xml.sax.SAXException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/**
 * M3 verification for {@link NamespaceFilter}: hand-constructed
 * {@link XMLHandler} call sequences (as {@link Scanner} would emit them,
 * always reporting {@code xmlns} as a plain attribute) driven through the
 * filter into {@code new SAXAdapter(true)}, asserting on the resulting SAX
 * event stream via {@link RecordingSaxHandler} - the same pattern
 * {@code SAXAdapterTest} uses for M0.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class NamespaceFilterTest {

    private static CharBuffer cb(String s) {
        return CharBuffer.wrap(s);
    }

    private static NamespaceFilter newFilter(RecordingSaxHandler sink, boolean xml11) {
        SAXAdapter adapter = new SAXAdapter(true);
        adapter.setContentHandler(sink);
        adapter.setLexicalHandler(sink);
        return new NamespaceFilter(adapter, xml11);
    }

    /** Single-chunk startAttribute()/attributeValueContent(value, true) pair. */
    private static void attr(NamespaceFilter filter, String name, String value) throws Exception {
        filter.startAttribute(name, "CDATA");
        filter.attributeValueContent(cb(value), true);
    }

    @Test
    public void testDefaultNamespaceDeclaration() throws Exception {
        // <root xmlns="urn:x"/>
        RecordingSaxHandler sink = new RecordingSaxHandler();
        NamespaceFilter filter = newFilter(sink, false);

        filter.startDocument();
        filter.startElement("root");
        attr(filter, "xmlns", "urn:x");
        filter.endAttributes();
        filter.endElement();
        filter.endDocument();

        assertEquals(Arrays.asList(
                "startDocument()",
                "startPrefixMapping(,urn:x)",
                "startElement(urn:x,root,root,[])",
                "endElement(urn:x,root,root)",
                "endPrefixMapping()",
                "endDocument()"), sink.getEvents());
    }

    @Test
    public void testPrefixedNamespaceDeclarationAndAttribute() throws Exception {
        // <p:root xmlns:p="urn:p" p:attr="v" plain="w"/>
        RecordingSaxHandler sink = new RecordingSaxHandler();
        NamespaceFilter filter = newFilter(sink, false);

        filter.startDocument();
        filter.startElement("p:root");
        attr(filter, "xmlns:p", "urn:p");
        attr(filter, "p:attr", "v");
        attr(filter, "plain", "w");
        filter.endAttributes();
        filter.endElement();
        filter.endDocument();

        assertEquals(Arrays.asList(
                "startDocument()",
                "startPrefixMapping(p,urn:p)",
                "startElement(urn:p,root,p:root,[urn:p attr=v(CDATA); plain=w(CDATA)])",
                "endElement(urn:p,root,p:root)",
                "endPrefixMapping(p)",
                "endDocument()"), sink.getEvents());
    }

    @Test
    public void testMultiChunkNamespaceValue() throws Exception {
        // xmlns split across two attributeValueContent() calls, mirroring
        // how a Scanner-emitted value that straddles a receive() boundary
        // would arrive.
        RecordingSaxHandler sink = new RecordingSaxHandler();
        NamespaceFilter filter = newFilter(sink, false);

        filter.startDocument();
        filter.startElement("root");
        filter.startAttribute("xmlns", "CDATA");
        filter.attributeValueContent(cb("urn:pa"), false);
        filter.attributeValueContent(cb("rt"), true);
        filter.endAttributes();
        filter.endElement();
        filter.endDocument();

        assertEquals(Arrays.asList(
                "startDocument()",
                "startPrefixMapping(,urn:part)",
                "startElement(urn:part,root,root,[])",
                "endElement(urn:part,root,root)",
                "endPrefixMapping()",
                "endDocument()"), sink.getEvents());
    }

    @Test
    public void testEmptyClosingChunkForNamespaceValue() throws Exception {
        // HTTP/2-DATA-frame-style empty closing event, same as
        // SAXAdapterTest.testEmptyClosingChunkAfterOpenRun but for a
        // namespace declaration's value.
        RecordingSaxHandler sink = new RecordingSaxHandler();
        NamespaceFilter filter = newFilter(sink, false);

        filter.startDocument();
        filter.startElement("root");
        filter.startAttribute("xmlns", "CDATA");
        filter.attributeValueContent(cb("urn:x"), false);
        filter.attributeValueContent(CharBuffer.wrap(new char[0]), true);
        filter.endAttributes();
        filter.endElement();
        filter.endDocument();

        assertEquals(Arrays.asList(
                "startDocument()",
                "startPrefixMapping(,urn:x)",
                "startElement(urn:x,root,root,[])",
                "endElement(urn:x,root,root)",
                "endPrefixMapping()",
                "endDocument()"), sink.getEvents());
    }

    @Test
    public void testNonNamespaceAttributePassesThroughUnbuffered() throws Exception {
        // A plain attribute must be forwarded straight to the delegate as
        // its own startAttribute()/attributeValueContent() calls, not routed
        // through the filter's namespace-value buffering at all.
        RecordingSaxHandler sink = new RecordingSaxHandler();
        NamespaceFilter filter = newFilter(sink, false);

        filter.startDocument();
        filter.startElement("root");
        attr(filter, "a", "1");
        filter.endAttributes();
        filter.characters(cb("x"), true);
        filter.endElement();
        filter.endDocument();

        assertEquals(Arrays.asList(
                "startDocument()",
                "startElement(,root,root,[ a=1(CDATA)])",
                "characters:x",
                "endElement(,root,root)",
                "endDocument()"), sink.getEvents());
    }

    private static void expectFatal(NamespaceFilter filter, String attrName, String value)
            throws Exception {
        filter.startDocument();
        filter.startElement("root");
        try {
            attr(filter, attrName, value);
            fail("expected a fatal error for " + attrName + "=\"" + value + "\"");
        } catch (SAXException e) {
            // expected
        }
    }

    @Test
    public void testCannotBindDefaultNamespaceToXmlNamespaceURI() throws Exception {
        NamespaceFilter filter = newFilter(new RecordingSaxHandler(), false);
        expectFatal(filter, "xmlns", "http://www.w3.org/XML/1998/namespace");
    }

    @Test
    public void testCannotBindDefaultNamespaceToXmlnsNamespaceURI() throws Exception {
        NamespaceFilter filter = newFilter(new RecordingSaxHandler(), false);
        expectFatal(filter, "xmlns", "http://www.w3.org/2000/xmlns/");
    }

    @Test
    public void testEmptyPrefixAfterXmlnsColon() throws Exception {
        NamespaceFilter filter = newFilter(new RecordingSaxHandler(), false);
        expectFatal(filter, "xmlns:", "urn:x");
    }

    @Test
    public void testPrefixUnbindingRejectedInXml10() throws Exception {
        NamespaceFilter filter = newFilter(new RecordingSaxHandler(), false);
        expectFatal(filter, "xmlns:p", "");
    }

    @Test
    public void testPrefixUnbindingAllowedInXml11() throws Exception {
        RecordingSaxHandler sink = new RecordingSaxHandler();
        NamespaceFilter filter = newFilter(sink, true);
        filter.startDocument();
        filter.startElement("root");
        attr(filter, "xmlns:p", "");
        filter.endAttributes();
        filter.endElement();
        filter.endDocument();
        // No exception - the declaration is accepted (an empty-URI binding).
        assertEquals(Arrays.asList(
                "startDocument()",
                "startPrefixMapping(p,)",
                "startElement(,root,root,[])",
                "endElement(,root,root)",
                "endPrefixMapping(p)",
                "endDocument()"), sink.getEvents());
    }

    @Test
    public void testXmlPrefixMustBindToCorrectURI() throws Exception {
        NamespaceFilter filter = newFilter(new RecordingSaxHandler(), false);
        expectFatal(filter, "xmlns:xml", "urn:wrong");
    }

    @Test
    public void testXmlPrefixCanBindToCorrectURI() throws Exception {
        // Re-declaring the pre-bound xml prefix to its own correct URI is
        // accepted (not an NSC violation) - it is still a fresh declaration
        // at this element's own scope, so it does fire start/endPrefixMapping,
        // just no fatalError.
        RecordingSaxHandler sink = new RecordingSaxHandler();
        NamespaceFilter filter = newFilter(sink, false);
        filter.startDocument();
        filter.startElement("root");
        attr(filter, "xmlns:xml", "http://www.w3.org/XML/1998/namespace");
        filter.endAttributes();
        filter.endElement();
        filter.endDocument();
        assertEquals(Arrays.asList(
                "startDocument()",
                "startPrefixMapping(xml,http://www.w3.org/XML/1998/namespace)",
                "startElement(,root,root,[])",
                "endElement(,root,root)",
                "endPrefixMapping(xml)",
                "endDocument()"), sink.getEvents());
    }

    @Test
    public void testCannotDeclareXmlnsPrefix() throws Exception {
        NamespaceFilter filter = newFilter(new RecordingSaxHandler(), false);
        expectFatal(filter, "xmlns:xmlns", "urn:x");
    }

    @Test
    public void testCannotBindPrefixToReservedXmlNamespaceURI() throws Exception {
        NamespaceFilter filter = newFilter(new RecordingSaxHandler(), false);
        expectFatal(filter, "xmlns:foo", "http://www.w3.org/XML/1998/namespace");
    }

    @Test
    public void testCannotBindPrefixToReservedXmlnsNamespaceURI() throws Exception {
        NamespaceFilter filter = newFilter(new RecordingSaxHandler(), false);
        expectFatal(filter, "xmlns:foo", "http://www.w3.org/2000/xmlns/");
    }

    @Test
    public void testSaveBuffersPassesThrough() throws Exception {
        // Purely a passthrough - must not disturb in-progress buffering.
        RecordingSaxHandler sink = new RecordingSaxHandler();
        NamespaceFilter filter = newFilter(sink, false);
        filter.startDocument();
        filter.startElement("root");
        filter.startAttribute("xmlns", "CDATA");
        filter.attributeValueContent(cb("urn:x"), false);
        filter.saveBuffers();
        filter.attributeValueContent(cb("y"), true);
        filter.endAttributes();
        filter.endElement();
        filter.endDocument();
        assertEquals(Arrays.asList(
                "startDocument()",
                "startPrefixMapping(,urn:xy)",
                "startElement(urn:xy,root,root,[])",
                "endElement(urn:xy,root,root)",
                "endPrefixMapping()",
                "endDocument()"), sink.getEvents());
    }

}

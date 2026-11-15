/*
 * XMLWriterLineEndTest.java
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
import java.io.ByteArrayOutputStream;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * A parser turns every line ending into a line feed, and the white space
 * in an attribute value into spaces, before anything else sees them (XML
 * 2.11 and 3.3.3). A carriage return in a document's content, or a tab
 * or line feed in an attribute value, can therefore only have come from
 * a character reference, and only stays what it is if it is written as
 * one.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class XMLWriterLineEndTest {

    @Test
    public void testCarriageReturnInTextIsAReference() throws Exception {
        for (int xml11 = 0; xml11 < 2; xml11++) {
            for (int form = 0; form < 3; form++) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                XMLWriter writer = new XMLWriter(out);
                writer.setXml11(xml11 == 1);
                writer.writeStartElement("r");
                String text = "a\rb\r\nc\nd\te";
                if (form == 0) {
                    writer.writeCharacters(text);
                } else if (form == 1) {
                    writer.writeCharacters(text.toCharArray(), 0, text.length());
                } else {
                    writer.writeCharacters(CharBuffer.wrap(text));
                }
                writer.writeEndElement();
                writer.close();
                // A line feed and a tab are themselves in text.
                assertEquals("form " + form + (xml11 == 1 ? " in XML 1.1" : " in XML 1.0"),
                        "<r>a&#13;b&#13;\nc\nd\te</r>", utf8(out));
            }
        }
    }

    @Test
    public void testWhitespaceInANamespaceNameIsAReference() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        XMLWriter writer = new XMLWriter(out);
        writer.writeStartElement("r");
        writer.writeNamespace("p", "urn:a\tb\nc\rd e");
        writer.writeDefaultNamespace("urn:\td");
        writer.writeEndElement();
        writer.close();
        // As in any other attribute value, where they are already written so.
        assertEquals("<r xmlns:p=\"urn:a&#9;b&#10;c&#13;d e\" xmlns=\"urn:&#9;d\"/>", utf8(out));
    }

    @Test
    public void testCarriageReturnIsReadBack() throws Exception {
        String[] documents = {
            "<r>a&#13;b</r>",
            "<r>a&#13;&#10;b\n&#13;</r>",
            "<r a=\"x&#13;y&#10;z&#9;.\">t</r>",
            "<!DOCTYPE r [<!ENTITY e \"cr&#13;lf&#10;tab&#9;nl\nend\">]><r a=\"&e;\">&e;</r>",
        };
        for (int d = 0; d < documents.length; d++) {
            String written = copy(documents[d], true);
            assertEquals("written as " + written, events(documents[d], true), events(written, true));
            assertEquals("copied again", written, copy(written, true));
        }
    }

    @Test
    public void testWhitespaceInANamespaceNameIsReadBack() throws Exception {
        String xml = "<p:r xmlns:p=\"urn:a&#9;b&#10;c&#13;d e\" xmlns=\"urn:&#13;d\"><s/></p:r>";
        for (int aware = 0; aware < 2; aware++) {
            String written = copy(xml, aware == 1);
            assertEquals("written as " + written, events(xml, true), events(written, true));
            assertEquals("written as " + written, events(xml, false), events(written, false));
        }
    }

    // ===== Helpers =====

    private static String utf8(ByteArrayOutputStream out) {
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String copy(String xml, boolean namespaceAware) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        XMLParser parser = new XMLParser();
        parser.setNamespaceAware(namespaceAware);
        parser.setHandler(new XMLWriterHandler(new XMLWriter(out)));
        parser.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        return utf8(out);
    }

    /** The events of a document, without the boundaries of its entities
     *  (a copy has what each reference stood for in its place) or the
     *  errors that only advise against an unusual namespace name. */
    private static List<String> events(String xml, boolean namespaceAware) throws Exception {
        RecordingXMLHandler handler = new RecordingXMLHandler();
        XMLParser parser = new XMLParser();
        parser.setNamespaceAware(namespaceAware);
        parser.setHandler(handler);
        parser.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        List<String> events = new ArrayList<String>();
        List<String> recorded = handler.getEvents();
        for (int i = 0; i < recorded.size(); i++) {
            String event = recorded.get(i);
            if (event.startsWith("startEntity:") || event.startsWith("endEntity:") || event.startsWith("error:")) {
                continue;
            }
            int last = events.size() - 1;
            if (event.startsWith("characters:") && last >= 0 && events.get(last).startsWith("characters:")) {
                events.set(last, events.get(last) + event.substring("characters:".length()));
            } else {
                events.add(event);
            }
        }
        return events;
    }

}

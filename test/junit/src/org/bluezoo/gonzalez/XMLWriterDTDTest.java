/*
 * XMLWriterDTDTest.java
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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;
import org.xml.sax.InputSource;

import static org.junit.Assert.assertEquals;

/**
 * What the writer makes of a document type declaration is itself a
 * document type declaration: it can be read back, and says what the one
 * that was written said.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class XMLWriterDTDTest {

    // ===== Processing instructions =====

    @Test
    public void testProcessingInstructionOpensTheInternalSubset() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        XMLWriter writer = new XMLWriter(out);
        writer.writeStartDTD("r", null, null);
        writer.writeProcessingInstruction("p", "d");
        writer.writeEndDTD();
        writer.writeStartElement("r");
        writer.writeEndElement();
        writer.close();
        assertEquals("<!DOCTYPE r [\n<?p d?>\n]><r/>", utf8(out));
    }

    @Test
    public void testProcessingInstructionAmongDeclarations() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        XMLWriter writer = new XMLWriter(out);
        writer.writeStartDTD("r", null, null);
        writer.writeElementDecl("r", "ANY");
        writer.writeProcessingInstruction("one");
        writer.writeComment(" c ");
        writer.writeStartProcessingInstruction("two");
        writer.writeProcessingInstructionContent("in ");
        writer.writeProcessingInstructionContent("pieces");
        writer.writeEndProcessingInstruction();
        writer.writeEndDTD();
        writer.writeStartElement("r");
        writer.writeEndElement();
        writer.close();
        assertEquals("<!DOCTYPE r [\n  <!ELEMENT r ANY>\n<?one?>\n<!-- c -->\n<?two in pieces?>\n]><r/>",
                utf8(out));
    }

    /** A processing instruction is not indented inside the DTD, as a
     *  comment is not: the indentation is for elements. Nor does either
     *  count as the start of the document, which would put a blank line
     *  between the DTD and the root element. */
    @Test
    public void testIndentingWithAProcessingInstructionOrCommentInTheSubset() throws Exception {
        for (int kind = 0; kind < 2; kind++) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            XMLWriter writer = new XMLWriter(out);
            writer.setIndentConfig(IndentConfig.spaces2());
            writer.writeStartDTD("r", null, null);
            writer.writeElementDecl("r", "ANY");
            if (kind == 0) {
                writer.writeProcessingInstruction("p", "d");
            } else {
                writer.writeComment("c");
            }
            writer.writeEndDTD();
            writer.writeStartElement("r");
            writer.writeStartElement("s");
            writer.writeProcessingInstruction("q", "e");
            writer.writeEndElement();
            writer.writeEndElement();
            writer.close();
            String inSubset = (kind == 0) ? "<?p d?>" : "<!--c-->";
            assertEquals("<!DOCTYPE r [\n  <!ELEMENT r ANY>\n" + inSubset + "\n]>\n<r>\n  <s>\n    <?q e?></s>\n</r>",
                    utf8(out));
        }
    }

    /** One in the external subset is left out with the rest of it, and
     *  written with the rest of it when the subset is being brought in. */
    @Test
    public void testProcessingInstructionInTheExternalSubset() throws Exception {
        for (int standalone = 0; standalone < 2; standalone++) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            XMLWriter writer = new XMLWriter(out);
            writer.setStandalone(standalone == 1);
            writer.writeStartDTD("r", null, "r.dtd");
            writer.writeProcessingInstruction("internal", "i");
            writer.startExternalSubset();
            writer.writeProcessingInstruction("external", "e");
            writer.writeStartProcessingInstruction("pieces");
            writer.writeProcessingInstructionContent("x");
            writer.writeEndProcessingInstruction();
            writer.endExternalSubset();
            writer.writeEndDTD();
            writer.writeStartElement("r");
            writer.writeEndElement();
            writer.close();
            if (standalone == 1) {
                assertEquals("<!DOCTYPE r [\n<?internal i?>\n<?external e?>\n<?pieces x?>\n]><r/>", utf8(out));
            } else {
                assertEquals("<!DOCTYPE r SYSTEM \"r.dtd\" [\n<?internal i?>\n]><r/>", utf8(out));
            }
        }
    }

    @Test
    public void testProcessingInstructionInTheSubsetIsReadBack() throws Exception {
        String[] documents = {
            "<!DOCTYPE r [<?pi x?>]><r/>",
            "<!DOCTYPE r [<!ELEMENT r ANY><?pi x?><!-- c --><?other?>]><r/>",
        };
        for (int d = 0; d < documents.length; d++) {
            // Through the native handler and through the SAX adapter.
            String nativeCopy = copy(documents[d]);
            assertEquals("written as " + nativeCopy, events(documents[d]), events(nativeCopy));
            String saxCopy = copyThroughSax(documents[d]);
            assertEquals("written as " + saxCopy, events(documents[d]), events(saxCopy));
        }
    }

    // ===== Entity values =====

    /**
     * An entity's value is its replacement text. In the literal that
     * declares it, a character reference is replaced when the declaration
     * is read but a general entity reference is left for when the entity
     * is used, so only the first puts a character into the value without
     * that character meaning what it otherwise would there.
     */
    @Test
    public void testEntityValueUsesCharacterReferences() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        XMLWriter writer = new XMLWriter(out);
        writer.writeStartDTD("r", null, null);
        writer.writeInternalEntityDecl("e", "a &amp; b \"q\" 'r' 100% x&f;y <z/> cr\rlf\ntab\t.");
        writer.writeInternalEntityDecl("%p", "<!ELEMENT s ANY> %q; \"");
        writer.writeEndDTD();
        writer.writeStartElement("r");
        writer.writeEndElement();
        writer.close();
        assertEquals("<!DOCTYPE r [\n"
                + "  <!ENTITY e \"a &#38;amp; b &#34;q&#34; 'r' 100&#37; x&#38;f;y <z/> cr&#13;lf\ntab\t.\">\n"
                + "  <!ENTITY % p \"<!ELEMENT s ANY> &#37;q; &#34;\">\n"
                + "]><r/>", utf8(out));
    }

    @Test
    public void testEntityValueIsReadBack() throws Exception {
        String[] declarations = {
            "<!ENTITY e \"plain\">",
            "<!ENTITY e \"a &amp; b\">",
            "<!ENTITY e \"a &#38;amp; b\">",
            "<!ENTITY e \"say &quot;hi&quot; &#34;there&#34; 'x'\">",
            "<!ENTITY e 'say \"hi\"'>",
            "<!ENTITY e \"100&#37; &#x25;\">",
            "<!ENTITY f \"F\"><!ENTITY e \"x&f;y\">",
            "<!ENTITY e \"&lt;not a tag&gt; &amp;amp;\">",
            "<!ENTITY e \"caf" + (char) 0xE9 + " &#x1F600;\">",
        };
        for (int d = 0; d < declarations.length; d++) {
            String xml = "<!DOCTYPE r [" + declarations[d] + "]><r a=\"&e;\">&e;</r>";
            String written = copy(xml);
            assertEquals("declared by " + written, declared(xml), declared(written));
            assertEquals("written as " + written, events(xml), events(written));
            assertEquals("copied again", written, copy(written));
        }
    }

    /** A carriage return can only be in a value by a character
     *  reference, and has to be written as one to stay there: on its own
     *  it would be read as the end of a line. */
    @Test
    public void testCarriageReturnInAnEntityValueIsReadBack() throws Exception {
        String xml = "<!DOCTYPE r [<!ENTITY e \"cr&#13;lf&#10;tab&#9;nl\nend\">]><r/>";
        String written = copy(xml);
        assertEquals("[entity e = [cr\rlf\ntab\tnl\nend]]", declared(xml).toString());
        assertEquals("declared by " + written, declared(xml), declared(written));
    }

    // ===== Identifiers =====

    /**
     * No reference of any kind is recognised in a system or public
     * identifier, so a quotation mark in one can only be written by
     * delimiting the literal with the other kind.
     */
    @Test
    public void testIdentifierWithAQuotationMark() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        XMLWriter writer = new XMLWriter(out);
        writer.writeStartDTD("r", "-//P's//EN", "a\"b.dtd");
        writer.writeNotationDecl("n", null, "plain");
        writer.writeNotationDecl("o", null, "it's");
        writer.writeNotationDecl("p", "P's", "say \"x\"");
        writer.writeExternalEntityDecl("e", null, "e\"1\".ent");
        writer.writeUnparsedEntityDecl("u", null, "u\"1\".bin", "n");
        writer.writeEndDTD();
        writer.writeStartElement("r");
        writer.writeEndElement();
        writer.close();
        assertEquals("<!DOCTYPE r PUBLIC \"-//P's//EN\" 'a\"b.dtd' [\n"
                + "  <!NOTATION n SYSTEM \"plain\">\n"
                + "  <!NOTATION o SYSTEM \"it's\">\n"
                + "  <!NOTATION p PUBLIC \"P's\" 'say \"x\"'>\n"
                + "  <!ENTITY e SYSTEM 'e\"1\".ent'>\n"
                + "  <!ENTITY u SYSTEM 'u\"1\".bin' NDATA n>\n"
                + "]><r/>", utf8(out));
    }

    @Test
    public void testIdentifierWithBothQuotationMarksCannotBeWritten() throws Exception {
        XMLWriter writer = new XMLWriter(new ByteArrayOutputStream());
        writer.writeStartDTD("r", null, null);
        try {
            writer.writeNotationDecl("n", null, "it's \"x\"");
            org.junit.Assert.fail("Expected IOException");
        } catch (java.io.IOException e) {
            org.junit.Assert.assertTrue("Was: " + e.getMessage(), e.getMessage().contains("quotation mark"));
        }
    }

    @Test
    public void testIdentifierWithAQuotationMarkIsReadBack() throws Exception {
        String xml = "<!DOCTYPE r [<!NOTATION n SYSTEM 'say \"x\"'><!NOTATION o SYSTEM \"it's\">"
                + "<!NOTATION p PUBLIC \"P's\">]><r/>";
        String written = copy(xml);
        assertEquals("declared by " + written, declared(xml), declared(written));
    }

    // ===== Helpers =====

    private static String utf8(ByteArrayOutputStream out) {
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String copy(String xml) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        XMLParser parser = new XMLParser();
        parser.setHandler(new XMLWriterHandler(new XMLWriter(out)));
        parser.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        return utf8(out);
    }

    private static String copyThroughSax(String xml) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Parser.XMLWriterSAXAdapter adapter = new Parser.XMLWriterSAXAdapter(new XMLWriter(out));
        Parser parser = new Parser();
        parser.setContentHandler(adapter);
        parser.setProperty("http://xml.org/sax/properties/lexical-handler", adapter);
        parser.setProperty("http://xml.org/sax/properties/declaration-handler", adapter);
        parser.setDTDHandler(adapter);
        parser.parse(new InputSource(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))));
        return utf8(out);
    }

    /** The events of a document, without the boundaries of its entities:
     *  a copy has what each reference stood for in its place. */
    private static List<String> events(String xml) throws Exception {
        RecordingXMLHandler handler = new RecordingXMLHandler();
        XMLParser parser = new XMLParser();
        parser.setHandler(handler);
        parser.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        List<String> events = new ArrayList<String>();
        List<String> recorded = handler.getEvents();
        for (int i = 0; i < recorded.size(); i++) {
            String event = recorded.get(i);
            if (event.startsWith("startEntity:") || event.startsWith("endEntity:")) {
                continue;
            }
            // Text either side of a boundary is one run of text without it.
            int last = events.size() - 1;
            if (event.startsWith("characters:") && last >= 0 && events.get(last).startsWith("characters:")) {
                events.set(last, events.get(last) + event.substring("characters:".length()));
            } else {
                events.add(event);
            }
        }
        return events;
    }

    /** What a document's DTD declares: each entity with its value, and
     *  each notation with its identifiers. */
    private static List<String> declared(String xml) throws Exception {
        final List<String> declared = new ArrayList<String>();
        XMLParser parser = new XMLParser();
        parser.setHandler(new RecordingXMLHandler() {
            @Override
            public void internalEntityDecl(String name, String value) {
                declared.add("entity " + name + " = [" + value + "]");
            }

            @Override
            public void notationDecl(String name, String publicId, String systemId) {
                declared.add("notation " + name + " public [" + publicId + "] system [" + systemId + "]");
            }
        });
        parser.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        return declared;
    }

}

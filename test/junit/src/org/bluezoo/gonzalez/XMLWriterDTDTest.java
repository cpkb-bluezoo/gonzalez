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

    private static List<String> events(String xml) throws Exception {
        RecordingXMLHandler handler = new RecordingXMLHandler();
        XMLParser parser = new XMLParser();
        parser.setHandler(handler);
        parser.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        return new ArrayList<String>(handler.getEvents());
    }

}

/*
 * LineNumberTest.java
 * Copyright (C) 2026 Chris Burdess
 */
package org.bluezoo.gonzalez;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.Locator;
import org.xml.sax.helpers.DefaultHandler;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The line and column numbers the scanner reports through its locator. The
 * feature is off unless asked for, because counting costs a pass over the
 * input.
 */
public class LineNumberTest {

    private static final String FEATURE =
        "http://bluezoo.org/gonzalez/features/line-numbers";

    /** Records "name@line:column" for each start tag. */
    private static final class Recorder extends DefaultHandler {
        final List<String> starts = new ArrayList<String>();
        Locator locator;

        @Override
        public void setDocumentLocator(Locator locator) {
            this.locator = locator;
        }

        @Override
        public void startElement(String uri, String localName, String qName,
                Attributes atts) {
            starts.add(qName + "@" + locator.getLineNumber() + ":"
                    + locator.getColumnNumber());
        }

        /** The names and lines only, "a@1 b@2". */
        String lines() {
            StringBuilder sb = new StringBuilder();
            for (String s : starts) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(s.substring(0, s.indexOf(':')));
            }
            return sb.toString();
        }
    }

    private static Recorder parse(String xml, boolean track, int chunk)
            throws Exception {
        Parser parser = new Parser();
        if (track) {
            parser.setFeature(FEATURE, true);
        }
        Recorder recorder = new Recorder();
        parser.setContentHandler(recorder);
        InputStream in = new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8));
        if (chunk > 0) {
            in = new ChunkedInputStream(in, chunk);
        }
        InputSource source = new InputSource(in);
        source.setSystemId("file:/doc.xml");
        parser.parse(source);
        return recorder;
    }

    @Test
    public void offByDefault() throws Exception {
        Recorder r = parse("<a>\n<b/>\n</a>", false, 0);
        assertEquals("a@-1 b@-1", r.lines());
        assertTrue(r.starts.get(0), r.starts.get(0).endsWith(":-1"));
    }

    @Test
    public void featureCanBeReadBack() throws Exception {
        Parser parser = new Parser();
        assertEquals(false, parser.getFeature(FEATURE));
        parser.setFeature(FEATURE, true);
        assertEquals(true, parser.getFeature(FEATURE));
    }

    @Test
    public void linesOfStartTags() throws Exception {
        String xml = "<?xml version='1.0'?>\n"
            + "<a>\n"
            + "  <b/>\n"
            + "\n"
            + "  <c>text\n"
            + "    <d/></c>\n"
            + "</a>";
        assertEquals("a@2 b@3 c@5 d@6", parse(xml, true, 0).lines());
    }

    @Test
    public void linesBeforeAndWithinTheXmlDeclarationAreCounted() throws Exception {
        assertEquals("a@4", parse("<?xml version='1.0'?>\n\n\n<a/>", true, 0).lines());
        assertEquals("a@3", parse("<?xml version='1.0'\n  encoding='UTF-8'?>\n<a/>", true, 0).lines());
        assertEquals("a@3", parse("<?xml version='1.0'?>\n<!-- c -->\n<a/>", true, 0).lines());
        assertEquals("a@5", parse("<?xml version='1.0'\n\n encoding='UTF-8'?><!--\n-->\n<a/>", true, 1).lines());
    }

    @Test
    public void columnsAreOneBased() throws Exception {
        Recorder r = parse("<a>\n  <b/></a>", true, 0);
        // The position is somewhere within the tag (after it, at the latest).
        int column = Integer.parseInt(r.starts.get(1).substring(r.starts.get(1).indexOf(':') + 1));
        assertTrue("column " + column, column >= 3 && column <= 8);
    }

    @Test
    public void lineBreaksAreCountedOnceWhateverTheirForm() throws Exception {
        assertEquals("a@1 b@2 c@3 d@4 e@5",
                parse("<a>\r\n<b/>\r<c/>\n<d/>\r\n<e/></a>", true, 0).lines());
    }

    @Test
    public void multiLineStartTagIsLocatedWithinItself() throws Exception {
        Recorder r = parse("<a\n  x='1'\n  y='2'>\n<b/></a>", true, 0);
        String a = r.starts.get(0);
        int line = Integer.parseInt(a.substring(a.indexOf('@') + 1, a.indexOf(':')));
        assertTrue(a, line >= 1 && line <= 3);
        assertEquals("b@4", r.starts.get(1).substring(0, r.starts.get(1).indexOf(':')));
    }

    @Test
    public void sameWhereverTheInputIsDivided() throws Exception {
        StringBuilder xml = new StringBuilder("<?xml version='1.0'?>\n<root>\n");
        for (int i = 0; i < 60; i++) {
            xml.append("  <item n='").append(i).append("'>\n    <sub/>\n  </item>\n");
        }
        xml.append("</root>");
        String whole = parse(xml.toString(), true, 0).starts.toString();
        for (int chunk : new int[] { 1, 2, 3, 5, 7, 16, 100 }) {
            assertEquals("chunk " + chunk, whole,
                    parse(xml.toString(), true, chunk).starts.toString());
        }
    }

    @Test
    public void internalEntityIsLocatedAtItsReference() throws Exception {
        String xml = "<!DOCTYPE d [<!ENTITY e '<x/>'>]>\n"
            + "<d>\n"
            + "&e;<y/>\n"
            + "</d>";
        assertEquals("d@2 x@3 y@3", parse(xml, true, 0).lines());
    }

    @Test
    public void externalEntityHasLinesOfItsOwn() throws Exception {
        File dir = File.createTempFile("lines", "");
        dir.delete();
        dir.mkdirs();
        dir.deleteOnExit();
        File part = new File(dir, "part.xml");
        write(part, "<?xml version='1.0'?>\n<p>\n\n  <q/>\n</p>");
        String xml = "<!DOCTYPE d [<!ENTITY e SYSTEM 'part.xml'>]>\n"
            + "<d>\n"
            + "&e;<y/>\n"
            + "</d>";
        File main = new File(dir, "main.xml");
        write(main, xml);

        Parser parser = new Parser();
        parser.setFeature(FEATURE, true);
        parser.setFeature("http://xml.org/sax/features/external-general-entities", true);
        parser.setProperty("http://javax.xml.XMLConstants/property/accessExternalDTD", "file");
        Recorder recorder = new Recorder();
        parser.setContentHandler(recorder);
        parser.parse(new InputSource(main.toURI().toString()));

        // p and q are on lines 2 and 4 of part.xml; y is back on line 3 of main.xml
        assertEquals("d@2 p@2 q@4 y@3", recorder.lines());
    }

    private static void write(File file, String content) throws Exception {
        FileOutputStream out = new FileOutputStream(file);
        try {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        } finally {
            out.close();
        }
        file.deleteOnExit();
    }
}

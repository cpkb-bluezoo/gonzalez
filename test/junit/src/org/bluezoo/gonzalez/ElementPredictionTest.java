/*
 * ElementPredictionTest.java
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
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/**
 * The scanner expects a start tag's name to be the one that followed the
 * previous start tag's last time. However often that is wrong, or right,
 * the elements reported must be the ones in the document.
 */
public class ElementPredictionTest {

    private static List<String> parse(String xml, int chunk) throws Exception {
        final List<String> out = new ArrayList<String>();
        Parser parser = new Parser();
        parser.setFeature("http://xml.org/sax/features/namespaces", false);
        parser.setContentHandler(new DefaultHandler() {
            @Override
            public void startElement(String uri, String localName, String qName, Attributes a) {
                out.add("<" + qName);
            }

            @Override
            public void endElement(String uri, String localName, String qName) {
                out.add(">" + qName);
            }
        });
        java.io.InputStream in = new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8));
        if (chunk > 0) {
            in = new ChunkedInputStream(in, chunk);
        }
        parser.parse(new InputSource(in));
        return out;
    }

    private static final int[] CHUNKS = { 0, 1, 2, 3, 5, 7, 11 };

    private static void assertSame(String xml, String expected) throws Exception {
        for (int chunk : CHUNKS) {
            assertEquals("chunk " + chunk, expected, parse(xml, chunk).toString());
        }
    }

    @Test
    public void repeatedStructureIsReportedExactly() throws Exception {
        assertSame("<r><b><t/><a/></b><b><t/><a/></b><b><t/><a/></b></r>",
                "[<r, <b, <t, >t, <a, >a, >b, <b, <t, >t, <a, >a, >b, <b, <t, >t, <a, >a, >b, >r]");
    }

    @Test
    public void changedStructureIsReportedExactly() throws Exception {
        assertSame("<r><b><t/><a/></b><b><a/><t/></b><b><t/></b><b><x/><t/><a/></b></r>",
                "[<r, <b, <t, >t, <a, >a, >b, <b, <a, >a, <t, >t, >b, <b, <t, >t, >b, "
                + "<b, <x, >x, <t, >t, <a, >a, >b, >r]");
    }

    @Test
    public void aNameThatExtendsOrShortensThePredictedOneIsAnotherName() throws Exception {
        assertSame("<r><item/><item/><items/><item/><ite/><item/><item2/><item/></r>",
                "[<r, <item, >item, <item, >item, <items, >items, <item, >item, <ite, >ite, "
                + "<item, >item, <item2, >item2, <item, >item, >r]");
    }

    @Test
    public void aPredictedNameFollowedByNameCharactersIsNotMistaken() throws Exception {
        assertSame("<r><a/><b/><a/><b-c/><a/><b.c/><a/><b_/><a/><b:c/><a/><b/></r>",
                "[<r, <a, >a, <b, >b, <a, >a, <b-c, >b-c, <a, >a, <b.c, >b.c, <a, >a, <b_, >b_, "
                + "<a, >a, <b:c, >b:c, <a, >a, <b, >b, >r]");
    }

    @Test
    public void startTagsWithAttributesWhitespaceAndNonAscii() throws Exception {
        assertSame("<r><\u00e9t\u00e9 a='1'/><\u00e9t\u00e9 a='1' /><\u00e9t\u00e8/><\u00e9t\u00e9\n/></r>",
                "[<r, <\u00e9t\u00e9, >\u00e9t\u00e9, <\u00e9t\u00e9, >\u00e9t\u00e9, <\u00e9t\u00e8, >\u00e9t\u00e8, "
                + "<\u00e9t\u00e9, >\u00e9t\u00e9, >r]");
    }

    @Test
    public void aMismatchedEndTagIsStillAnErrorWhateverWasPredicted() throws Exception {
        for (int chunk : CHUNKS) {
            try {
                parse("<r><a><b/></a><a><b/></x></r>", chunk);
                fail("expected a mismatch error, chunk " + chunk);
            } catch (SAXParseException e) {
                // fatal
            }
        }
    }

    @Test
    public void aNameThatStartsWithADigitIsStillRejected() throws Exception {
        for (int chunk : CHUNKS) {
            try {
                parse("<r><a/><a/><9a/></r>", chunk);
                fail("expected an error, chunk " + chunk);
            } catch (SAXParseException e) {
                // fatal
            }
        }
    }

    @Test
    public void aSecondRootIsStillRejected() throws Exception {
        for (int chunk : CHUNKS) {
            try {
                parse("<r><a/></r><r/>", chunk);
                fail("expected an error, chunk " + chunk);
            } catch (SAXParseException e) {
                // fatal
            }
        }
    }
}

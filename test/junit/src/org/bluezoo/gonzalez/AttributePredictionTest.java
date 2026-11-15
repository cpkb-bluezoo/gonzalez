/*
 * AttributePredictionTest.java
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
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The scanner expects an attribute name to be the one that followed the
 * previous name last time. However often that is wrong, or right, what is
 * reported must be what is in the document.
 */
public class AttributePredictionTest {

    private static List<String> parse(String xml, int chunk) throws Exception {
        final List<String> out = new ArrayList<String>();
        Parser parser = new Parser();
        parser.setFeature("http://xml.org/sax/features/namespaces", false);
        parser.setFeature("http://xml.org/sax/features/namespace-prefixes", true);
        parser.setContentHandler(new DefaultHandler() {
            @Override
            public void startElement(String uri, String localName, String qName, Attributes a) {
                StringBuilder sb = new StringBuilder(qName).append('[');
                for (int i = 0; i < a.getLength(); i++) {
                    if (i > 0) {
                        sb.append(' ');
                    }
                    sb.append(a.getQName(i)).append('=').append(a.getValue(i));
                }
                out.add(sb.append(']').toString());
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
    public void repeatedOrderIsReportedExactly() throws Exception {
        assertSame("<r><e id='1' code='a' x='9'/><e id='2' code='b' x='8'/>"
                + "<e id='3' code='c' x='7'/></r>",
                "[r[], e[id=1 code=a x=9], e[id=2 code=b x=8], e[id=3 code=c x=7]]");
    }

    @Test
    public void changedAndPartialOrderIsReportedExactly() throws Exception {
        assertSame("<r><e id='1' code='a' x='9'/><e code='b' id='2'/><e x='1' id='3' code='c'/>"
                + "<e id='4'/><e id='5' x='6'/></r>",
                "[r[], e[id=1 code=a x=9], e[code=b id=2], e[x=1 id=3 code=c], e[id=4], e[id=5 x=6]]");
    }

    @Test
    public void aNameThatExtendsOrShortensThePredictedOneIsAnotherName() throws Exception {
        assertSame("<r><e id='1' idx='2'/><e id='1' i='2'/><e id='1' idx='2'/>"
                + "<e id='1' id2='3'/><e id='1' idx='2'/></r>",
                "[r[], e[id=1 idx=2], e[id=1 i=2], e[id=1 idx=2], e[id=1 id2=3], e[id=1 idx=2]]");
    }

    @Test
    public void aPredictedNameFollowedByNameCharactersIsNotMistaken() throws Exception {
        assertSame("<r><e a='1' b='2'/><e a='1' b-c='2'/><e a='1' b.c='2'/><e a='1' b_='2'/>"
                + "<e a='1' b:c='2'/><e a='1' b='2'/></r>",
                "[r[], e[a=1 b=2], e[a=1 b-c=2], e[a=1 b.c=2], e[a=1 b_=2], e[a=1 b:c=2], e[a=1 b=2]]");
    }

    @Test
    public void whitespaceAroundTheEqualsSignAfterAPredictedName() throws Exception {
        assertSame("<r><e id='1' code='a'/><e id = '2' code\n=\t'b'/><e id='3'  code  =  'c'/></r>",
                "[r[], e[id=1 code=a], e[id=2 code=b], e[id=3 code=c]]");
    }

    @Test
    public void nonAsciiAndPrefixedNames() throws Exception {
        assertSame("<r xmlns:p='u'><e p:a='1' \u00e9t\u00e9='2'/><e p:a='1' \u00e9t\u00e9='2'/>"
                + "<e p:a='1' \u00e9t\u00e8='2'/></r>",
                "[r[xmlns:p=u], e[p:a=1 \u00e9t\u00e9=2], e[p:a=1 \u00e9t\u00e9=2], e[p:a=1 \u00e9t\u00e8=2]]");
    }

    @Test
    public void aRepeatedAttributeIsStillRejectedWhateverWasPredicted() throws Exception {
        String ok = "<e id='1' code='a'/>";
        for (int chunk : CHUNKS) {
            try {
                parse("<r>" + ok + ok + "<e id='1' id='2'/></r>", chunk);
                fail("expected a duplicate attribute error, chunk " + chunk);
            } catch (SAXParseException e) {
                assertTrue(e.getMessage(), e.getMessage().contains("Duplicate attribute"));
            }
        }
    }

    @Test
    public void aPredictedNameWithNoValueIsStillAnError() throws Exception {
        String ok = "<e id='1' code='a'/>";
        for (int chunk : CHUNKS) {
            try {
                parse("<r>" + ok + ok + "<e id='1' code/></r>", chunk);
                fail("expected an error, chunk " + chunk);
            } catch (SAXParseException e) {
                // fatal, as without any prediction
            }
        }
    }

    @Test
    public void aNameThatStartsWithADigitIsStillRejected() throws Exception {
        for (int chunk : CHUNKS) {
            try {
                parse("<r><e id='1' code='a'/><e id='1' 9code='a'/></r>", chunk);
                fail("expected an error, chunk " + chunk);
            } catch (SAXParseException e) {
                // fatal
            }
        }
    }
}

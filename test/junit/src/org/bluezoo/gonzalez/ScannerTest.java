/*
 * ScannerTest.java
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
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;
import org.xml.sax.InputSource;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * M1 verification for {@link Scanner}. Two kinds of check:
 * <ul>
 * <li>Hand-crafted cases exercising specific productions (entities, comments,
 * PI, CDATA) directly.</li>
 * <li>Differential testing against the current parser on the real benchmark
 * corpus (none of which use DOCTYPE or general entities, both out of scope
 * for M1 - see Scanner's class Javadoc), at a range of chunk sizes. This
 * doubles as the suspend/resume ("chunk-fuzzing") proof the milestone plan
 * calls for: a passing result at every chunk size means the coarse resumable
 * mode never double-emits or drops a sub-token at a buffer-straddling split.
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ScannerTest {

    private static final int[] CHUNK_SIZES = {0, 1, 2, 3, 5, 7, 11, 17, 23, 97};

    private static List<String> runScanner(char[] chars, int chunkSize) throws Exception {
        RecordingSaxHandler sink = new RecordingSaxHandler();
        SAXAdapter adapter = new SAXAdapter(false);
        adapter.setContentHandler(sink);
        adapter.setLexicalHandler(sink);
        Scanner scanner = new Scanner(adapter);
        if (chunkSize <= 0) {
            scanner.receive(CharBuffer.wrap(chars));
        } else {
            int off = 0;
            while (off < chars.length) {
                int len = Math.min(chunkSize, chars.length - off);
                scanner.receive(CharBuffer.wrap(chars, off, len));
                off += len;
            }
        }
        scanner.close();
        return sink.getEvents();
    }

    private static List<String> runCurrentParser(byte[] bytes) throws Exception {
        RecordingSaxHandler sink = new RecordingSaxHandler();
        Parser parser = new Parser();
        parser.setFeature("http://xml.org/sax/features/namespaces", false);
        parser.setContentHandler(sink);
        parser.setProperty("http://xml.org/sax/properties/lexical-handler", sink);
        parser.parse(new InputSource(new ByteArrayInputStream(bytes)));
        return sink.getEvents();
    }

    /** Strips a leading XML declaration - out of Scanner's scope (see class Javadoc). */
    private static char[] decodeAndStripDecl(byte[] bytes) {
        char[] all = new String(bytes, StandardCharsets.UTF_8).toCharArray();
        int start = 0;
        if (all.length > 5 && all[0] == '<' && all[1] == '?'
                && all[2] == 'x' && all[3] == 'm' && all[4] == 'l') {
            int j = 5;
            while (j + 1 < all.length && !(all[j] == '?' && all[j + 1] == '>')) {
                j++;
            }
            start = j + 2;
            while (start < all.length && (all[start] == '\n' || all[start] == '\r')) {
                start++;
            }
        }
        char[] result = new char[all.length - start];
        System.arraycopy(all, start, result, 0, result.length);
        return result;
    }

    /**
     * M1 does not emit startCDATA()/endCDATA() boundary markers (deferred
     * per XMLHandler's incremental lexical-event-set decision), so the
     * reference recording - which does have them, via LexicalHandler - splits
     * a run of "whitespace, CDATA content, whitespace" into three separate
     * coalesced characters() entries where the scanner produces one merged
     * entry. This reproduces what the reference list would have looked like
     * without those markers: drop them, then re-coalesce the now-adjacent
     * characters() entries exactly as RecordingSaxHandler itself would have.
     * This validates CDATA *content* correctness without asserting on a
     * feature M1 doesn't claim to have.
     */
    private static List<String> stripCDATABoundariesAndRecoalesce(List<String> events) {
        java.util.ArrayList<String> result = new java.util.ArrayList<String>();
        StringBuilder pendingChars = null;
        for (String event : events) {
            if (event.equals("startCDATA()") || event.equals("endCDATA()")) {
                continue;
            }
            if (event.startsWith("characters:")) {
                String text = event.substring("characters:".length());
                if (pendingChars == null) {
                    pendingChars = new StringBuilder(text);
                } else {
                    pendingChars.append(text);
                }
            } else {
                if (pendingChars != null) {
                    result.add("characters:" + pendingChars);
                    pendingChars = null;
                }
                result.add(event);
            }
        }
        if (pendingChars != null) {
            result.add("characters:" + pendingChars);
        }
        return result;
    }

    private void assertDifferential(String resourcePath) throws Exception {
        byte[] bytes = Files.readAllBytes(Paths.get(resourcePath));
        List<String> reference = stripCDATABoundariesAndRecoalesce(runCurrentParser(bytes));
        assertTrue("reference recording should not be trivially empty", reference.size() > 5);

        char[] chars = decodeAndStripDecl(bytes);
        for (int chunkSize : CHUNK_SIZES) {
            List<String> actual = runScanner(chars, chunkSize);
            assertEquals(resourcePath + " chunk size " + chunkSize, reference, actual);
        }
    }

    @Test
    public void testDifferential_plain() throws Exception {
        assertDifferential("benchmark/resources/large.xml");
    }

    @Test
    public void testDifferential_attrs() throws Exception {
        assertDifferential("benchmark/resources/attrs-large.xml");
    }

    @Test
    public void testDifferential_markup() throws Exception {
        // Comments, non-xmldecl PIs, CDATA-adjacent constructs.
        assertDifferential("benchmark/resources/markup-large.xml");
    }

    @Test
    public void testDifferential_whitespace() throws Exception {
        assertDifferential("benchmark/resources/whitespace-large.xml");
    }

    @Test
    public void testDifferential_multibyte() throws Exception {
        // Non-ASCII element/attribute names and content.
        assertDifferential("benchmark/resources/multibyte-large.xml");
    }

    @Test
    public void testDifferential_namespacedButUnawareMode() throws Exception {
        // xmlns-heavy document, parsed in namespace-UNAWARE mode on both
        // sides - xmlns/prefixed names must be reported as plain attributes
        // and opaque qNames on both, with no resolution.
        assertDifferential("benchmark/resources/large-ns.xml");
    }

    // ===== Hand-crafted production coverage =====

    @Test
    public void testCharacterReferencesDecimalAndHex() throws Exception {
        String xml = "<root>&#65;&#x42;&#x1F600;</root>";
        List<String> events = runScanner(xml.toCharArray(), 0);
        assertEquals(Arrays.asList(
                "startDocument()",
                "startElement(,root,root,[])",
                "characters:AB\uD83D\uDE00",
                "endElement(,root,root)",
                "endDocument()"), events);
    }

    @Test
    public void testPredefinedEntitiesInTextAndAttributeValue() throws Exception {
        String xml = "<root a=\"x &amp; y\">1 &lt; 2 &amp;&amp; 3 &gt; 0</root>";
        List<String> events = runScanner(xml.toCharArray(), 0);
        assertEquals(Arrays.asList(
                "startDocument()",
                "startElement(,root,root,[ a=x & y(CDATA)])",
                "characters:1 < 2 && 3 > 0",
                "endElement(,root,root)",
                "endDocument()"), events);
    }

    @Test
    public void testEmptyElement() throws Exception {
        String xml = "<root><child a=\"1\"/><other/></root>";
        List<String> events = runScanner(xml.toCharArray(), 0);
        assertEquals(Arrays.asList(
                "startDocument()",
                "startElement(,root,root,[])",
                "startElement(,child,child,[ a=1(CDATA)])",
                "endElement(,child,child)",
                "startElement(,other,other,[])",
                "endElement(,other,other)",
                "endElement(,root,root)",
                "endDocument()"), events);
    }

    @Test
    public void testCommentAndPIAndCDATA() throws Exception {
        String xml = "<!-- a comment --><?target pi data?><root><![CDATA[<raw> & text]]></root>";
        List<String> events = runScanner(xml.toCharArray(), 0);
        assertEquals(Arrays.asList(
                "startDocument()",
                "comment: a comment ",
                "processingInstruction(target,pi data)",
                "startElement(,root,root,[])",
                "characters:<raw> & text",
                "endElement(,root,root)",
                "endDocument()"), events);
    }

    @Test
    public void testMismatchedEndTagIsFatal() throws Exception {
        String xml = "<root><child></wrong></root>";
        try {
            runScanner(xml.toCharArray(), 0);
            org.junit.Assert.fail("expected a fatal error for mismatched end tag");
        } catch (org.xml.sax.SAXException e) {
            assertTrue(e.getMessage().contains("Mismatched end tag"));
        }
    }

    @Test
    public void testUnsupportedGeneralEntityIsFatal() throws Exception {
        String xml = "<root>&customEntity;</root>";
        try {
            runScanner(xml.toCharArray(), 0);
            org.junit.Assert.fail("expected a fatal error for a general entity reference");
        } catch (org.xml.sax.SAXException e) {
            assertTrue(e.getMessage().contains("General entity references"));
        }
    }

    @Test
    public void testUnclosedElementAtCloseIsFatal() throws Exception {
        String xml = "<root><child>";
        try {
            List<String> events = runScanner(xml.toCharArray(), 0);
            // (unreachable in practice, but keep the compiler happy about events)
            org.junit.Assert.fail("expected a fatal error for an unclosed element, got: " + events);
        } catch (org.xml.sax.SAXException e) {
            assertTrue(e.getMessage().contains("unexpectedly"));
        }
    }

    // ===== Regression: entity reference split across a receive() boundary =====
    //
    // A real bug, found while reworking scanContent() to stream with an
    // explicit end flag: with the entity incomplete right at the end of
    // currently-available data, scanContent() rewound pos to the unconsumed
    // '&' and returned - but pos was still < limit (the '&' itself remained
    // buffered), so the caller's "pos >= limit means done" inference never
    // held, and scan() looped calling scanContent() again with identical
    // state, forever. None of the differential corpus files contain any
    // entity references at all, and the hand-crafted entity tests above all
    // use one-shot delivery (chunkSize 0), so this path had zero coverage
    // until now. chunkSize=1 delivers one character at a time, exercising
    // every possible split point within each entity reference - including
    // the exact "split right after '&', nothing else buffered yet" case
    // that triggers the bug. @Test(timeout=...) turns a real hang into a
    // fast, clear failure instead of stalling the whole suite.

    @Test(timeout = 5000)
    public void testPredefinedEntitySplitAcrossReceiveBoundary_content() throws Exception {
        String xml = "<root>1 &amp; 2 &lt; 3</root>";
        List<String> events = runScanner(xml.toCharArray(), 1);
        assertEquals(Arrays.asList(
                "startDocument()",
                "startElement(,root,root,[])",
                "characters:1 & 2 < 3",
                "endElement(,root,root)",
                "endDocument()"), events);
    }

    @Test(timeout = 5000)
    public void testNumericCharacterReferenceSplitAcrossReceiveBoundary_content() throws Exception {
        String xml = "<root>&#65;&#x1F600;</root>";
        List<String> events = runScanner(xml.toCharArray(), 1);
        assertEquals(Arrays.asList(
                "startDocument()",
                "startElement(,root,root,[])",
                "characters:A\uD83D\uDE00",
                "endElement(,root,root)",
                "endDocument()"), events);
    }

    @Test(timeout = 5000)
    public void testEntitySplitAcrossReceiveBoundary_attributeValue() throws Exception {
        String xml = "<root a=\"x &amp; y &#65; z\"/>";
        List<String> events = runScanner(xml.toCharArray(), 1);
        assertEquals(Arrays.asList(
                "startDocument()",
                "startElement(,root,root,[ a=x & y A z(CDATA)])",
                "endElement(,root,root)",
                "endDocument()"), events);
    }

}

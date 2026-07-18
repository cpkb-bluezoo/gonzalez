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

    // ===== M3: namespace-aware differential (Scanner -> NamespaceFilter -> SAXAdapter(true)) =====

    private static List<String> runScannerNamespaceAware(char[] chars, int chunkSize) throws Exception {
        RecordingSaxHandler sink = new RecordingSaxHandler();
        SAXAdapter adapter = new SAXAdapter(true);
        adapter.setContentHandler(sink);
        adapter.setLexicalHandler(sink);
        NamespaceFilter filter = new NamespaceFilter(adapter, false);
        Scanner scanner = new Scanner(filter);
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

    private static List<String> runCurrentParserNamespaceAware(byte[] bytes) throws Exception {
        RecordingSaxHandler sink = new RecordingSaxHandler();
        Parser parser = new Parser();
        parser.setFeature("http://xml.org/sax/features/namespaces", true);
        parser.setContentHandler(sink);
        parser.setProperty("http://xml.org/sax/properties/lexical-handler", sink);
        parser.parse(new InputSource(new ByteArrayInputStream(bytes)));
        return sink.getEvents();
    }

    @Test
    public void testDifferential_namespaceAwareMode() throws Exception {
        // Same corpus as the unaware-mode test above, this time resolved:
        // xmlns declarations become namespace()/startPrefixMapping events,
        // not plain attributes, and prefixed names resolve to uri/localName
        // on both sides.
        byte[] bytes = Files.readAllBytes(Paths.get("benchmark/resources/large-ns.xml"));
        List<String> reference = stripCDATABoundariesAndRecoalesce(runCurrentParserNamespaceAware(bytes));
        assertTrue("reference recording should not be trivially empty", reference.size() > 5);

        char[] chars = decodeAndStripDecl(bytes);
        for (int chunkSize : CHUNK_SIZES) {
            List<String> actual = runScannerNamespaceAware(chars, chunkSize);
            assertEquals("chunk size " + chunkSize, reference, actual);
        }
    }

    // ===== M4: DOCTYPE / internal general entities differential =====
    //
    // None of the benchmark corpus files use DOCTYPE, so this is a
    // hand-written sample rather than a corpus file. The old parser reports
    // machinery this milestone deliberately does not yet (see Scanner's
    // class Javadoc "M4" section and XMLHandler's incremental lexical/DTD
    // boundary event set decision): startDTD()/endDTD() bracketing the
    // internal subset, and startEntity()/endEntity() bracketing each
    // internal-entity expansion. Both are normalized away below, exactly
    // like M1 normalized away CDATA boundary markers - this validates
    // *content* correctness (including recursive/nested entity expansion)
    // without asserting on lexical boundary events this milestone doesn't
    // claim to fire. One more normalization is needed: this Scanner fires
    // startDocument() at construction, before any DOCTYPE scanning, whereas
    // the old parser fires the internal subset's comment()/processingInstruction()
    // events *before* startDocument() - a startDocument-vs-DTD-scan
    // ordering difference, not a content one, so those events are moved to
    // just after startDocument() in the reference before comparing.

    private static List<String> normalizeM4Reference(List<String> events) {
        java.util.ArrayList<String> filtered = new java.util.ArrayList<String>();
        for (String e : events) {
            if (e.startsWith("startDTD(") || e.equals("endDTD()")
                    || e.startsWith("startEntity(") || e.startsWith("endEntity(")) {
                continue;
            }
            filtered.add(e);
        }
        int docIdx = filtered.indexOf("startDocument()");
        if (docIdx > 0) {
            java.util.ArrayList<String> reordered = new java.util.ArrayList<String>();
            reordered.add("startDocument()");
            reordered.addAll(filtered.subList(0, docIdx));
            reordered.addAll(filtered.subList(docIdx + 1, filtered.size()));
            filtered = reordered;
        }
        return stripCDATABoundariesAndRecoalesce(filtered);
    }

    private static final String M4_SAMPLE_XML = "<!DOCTYPE root [\n"
            + "<!-- a comment in the internal subset -->\n"
            + "<!ATTLIST root id CDATA #IMPLIED>\n"
            // No PI data here deliberately: the old parser's DTDParser-level PI
            // handling keeps the target/data separator whitespace as part of
            // the data (unlike its own top-level PI handling, and unlike this
            // Scanner's single scanPI() used consistently in both contexts) -
            // a pre-existing old-parser inconsistency between contexts, not
            // something M4 needs to replicate; sidestepped by not exercising
            // PI data here.
            + "<?subset-pi?>\n"
            + "<!ENTITY a \"Alice\">\n"
            + "<!ENTITY b \"&a; and Bob\">\n"
            + "<!ENTITY markup \"<child>nested</child>\">\n"
            + "]>\n"
            + "<root id=\"&b;\">\n"
            + "Hello &a;, meet &b;. 1 &amp; 2\n"
            + "&markup;\n"
            + "<child attr=\"&a; &#65; end\">text</child>\n"
            + "</root>\n";

    @Test
    public void testDifferential_internalGeneralEntities() throws Exception {
        byte[] bytes = M4_SAMPLE_XML.getBytes(StandardCharsets.UTF_8);
        List<String> reference = normalizeM4Reference(runCurrentParser(bytes));
        assertTrue("reference recording should not be trivially empty", reference.size() > 5);

        char[] chars = M4_SAMPLE_XML.toCharArray();
        for (int chunkSize : CHUNK_SIZES) {
            List<String> actual = runScanner(chars, chunkSize);
            assertEquals("chunk size " + chunkSize, reference, actual);
        }
    }

    @Test(timeout = 5000)
    public void testDoctypeAndEntitiesSplitAcrossReceiveBoundary_chunkSize1() throws Exception {
        // The DOCTYPE/internal-subset scanning added in M4 is entirely new
        // atomic-retry-on-underflow logic (see Scanner's "M4" section) -
        // unlike M1's constructs, it had no prior chunk-fuzzing coverage at
        // all. chunkSize=1 delivers one character at a time, exercising
        // every possible split point through the DOCTYPE keyword, the
        // internal subset's declarations, and the entity references
        // themselves.
        List<String> reference = normalizeM4Reference(
                runCurrentParser(M4_SAMPLE_XML.getBytes(StandardCharsets.UTF_8)));
        List<String> actual = runScanner(M4_SAMPLE_XML.toCharArray(), 1);
        assertEquals(reference, actual);
    }

    // ===== M4: hand-crafted entity coverage =====

    private static List<String> runScannerWithDoctype(String xml) throws Exception {
        return runScanner(xml.toCharArray(), 0);
    }

    @Test
    public void testSimpleInternalEntityInContent() throws Exception {
        String xml = "<!DOCTYPE root [<!ENTITY foo \"bar\">]><root>&foo;</root>";
        assertEquals(Arrays.asList(
                "startDocument()",
                "startElement(,root,root,[])",
                "characters:bar",
                "endElement(,root,root)",
                "endDocument()"), runScannerWithDoctype(xml));
    }

    @Test
    public void testEntityValueCharRefExpandedAtDeclarationTime() throws Exception {
        String xml = "<!DOCTYPE root [<!ENTITY foo \"a &#65; b\">]><root>&foo;</root>";
        assertEquals(Arrays.asList(
                "startDocument()",
                "startElement(,root,root,[])",
                "characters:a A b",
                "endElement(,root,root)",
                "endDocument()"), runScannerWithDoctype(xml));
    }

    @Test
    public void testEntityContainingMarkupProducesElementEvents() throws Exception {
        String xml = "<!DOCTYPE root [<!ENTITY foo \"<b>bold</b>\">]><root>&foo;</root>";
        assertEquals(Arrays.asList(
                "startDocument()",
                "startElement(,root,root,[])",
                "startElement(,b,b,[])",
                "characters:bold",
                "endElement(,b,b)",
                "endElement(,root,root)",
                "endDocument()"), runScannerWithDoctype(xml));
    }

    @Test
    public void testNestedEntityReferenceResolvesRecursively() throws Exception {
        String xml = "<!DOCTYPE root [<!ENTITY a \"x\"><!ENTITY b \"&a; y\">]><root>&b;</root>";
        assertEquals(Arrays.asList(
                "startDocument()",
                "startElement(,root,root,[])",
                "characters:x y",
                "endElement(,root,root)",
                "endDocument()"), runScannerWithDoctype(xml));
    }

    @Test
    public void testEntityInAttributeValue() throws Exception {
        String xml = "<!DOCTYPE root [<!ENTITY foo \"bar\">]><root a=\"x &foo; y\"/>";
        assertEquals(Arrays.asList(
                "startDocument()",
                "startElement(,root,root,[ a=x bar y(CDATA)])",
                "endElement(,root,root)",
                "endDocument()"), runScannerWithDoctype(xml));
    }

    @Test
    public void testEntityInAttributeValueRejectsLiteralLessThan() throws Exception {
        String xml = "<!DOCTYPE root [<!ENTITY foo \"<\">]><root a=\"&foo;\"/>";
        try {
            runScannerWithDoctype(xml);
            org.junit.Assert.fail("expected a fatal error for '<' via entity expansion in an attribute value");
        } catch (org.xml.sax.SAXException e) {
            assertTrue(e.getMessage().contains("not allowed in an attribute value"));
        }
    }

    @Test
    public void testSelfReferentialEntityIsFatal() throws Exception {
        String xml = "<!DOCTYPE root [<!ENTITY foo \"&foo;\">]><root>&foo;</root>";
        try {
            runScannerWithDoctype(xml);
            org.junit.Assert.fail("expected a fatal error for a self-referential entity");
        } catch (org.xml.sax.SAXException e) {
            assertTrue(e.getMessage().contains("Recursive"));
        }
    }

    @Test
    public void testMutuallyRecursiveEntitiesFatal() throws Exception {
        String xml = "<!DOCTYPE root [<!ENTITY a \"&b;\"><!ENTITY b \"&a;\">]><root>&a;</root>";
        try {
            runScannerWithDoctype(xml);
            org.junit.Assert.fail("expected a fatal error for mutually recursive entities");
        } catch (org.xml.sax.SAXException e) {
            assertTrue(e.getMessage().contains("Recursive"));
        }
    }

    @Test
    public void testExternalEntityReferenceIsFatal() throws Exception {
        String xml = "<!DOCTYPE root [<!ENTITY foo SYSTEM \"foo.ent\">]><root>&foo;</root>";
        try {
            runScannerWithDoctype(xml);
            org.junit.Assert.fail("expected a fatal error for an external entity reference");
        } catch (org.xml.sax.SAXException e) {
            assertTrue(e.getMessage().contains("External entities are not supported"));
        }
    }

    @Test
    public void testUndeclaredEntityWithDoctypePresentIsFatal() throws Exception {
        String xml = "<!DOCTYPE root [<!ENTITY foo \"bar\">]><root>&other;</root>";
        try {
            runScannerWithDoctype(xml);
            org.junit.Assert.fail("expected a fatal error for an undeclared entity reference");
        } catch (org.xml.sax.SAXException e) {
            assertTrue(e.getMessage().contains("was not declared"));
        }
    }

    @Test
    public void testParameterEntityDeclarationRejected() throws Exception {
        String xml = "<!DOCTYPE root [<!ENTITY % pe \"x\">]><root/>";
        try {
            runScannerWithDoctype(xml);
            org.junit.Assert.fail("expected a fatal error for a parameter entity declaration");
        } catch (org.xml.sax.SAXException e) {
            assertTrue(e.getMessage().contains("Parameter entity"));
        }
    }

    @Test
    public void testParameterEntityReferenceInSubsetRejected() throws Exception {
        String xml = "<!DOCTYPE root [%pe;]><root/>";
        try {
            runScannerWithDoctype(xml);
            org.junit.Assert.fail("expected a fatal error for a parameter entity reference");
        } catch (org.xml.sax.SAXException e) {
            assertTrue(e.getMessage().contains("Parameter entity"));
        }
    }

    @Test
    public void testDuplicateEntityDeclarationFirstWins() throws Exception {
        String xml = "<!DOCTYPE root [<!ENTITY foo \"first\"><!ENTITY foo \"second\">]><root>&foo;</root>";
        assertEquals(Arrays.asList(
                "startDocument()",
                "startElement(,root,root,[])",
                "characters:first",
                "endElement(,root,root)",
                "endDocument()"), runScannerWithDoctype(xml));
    }

    @Test
    public void testDoctypeMustPrecedeRootElement() throws Exception {
        String xml = "<root/><!DOCTYPE root []>";
        try {
            runScannerWithDoctype(xml);
            org.junit.Assert.fail("expected a fatal error for a DOCTYPE after the root element");
        } catch (org.xml.sax.SAXException e) {
            assertTrue(e.getMessage().contains("must precede the root element"));
        }
    }

    @Test
    public void testOnlyOneDoctypeAllowed() throws Exception {
        String xml = "<!DOCTYPE root []><!DOCTYPE root []><root/>";
        try {
            runScannerWithDoctype(xml);
            org.junit.Assert.fail("expected a fatal error for a second DOCTYPE declaration");
        } catch (org.xml.sax.SAXException e) {
            assertTrue(e.getMessage().contains("Only one DOCTYPE"));
        }
    }

    @Test
    public void testRootElementNameMustMatchDoctypeName() throws Exception {
        String xml = "<!DOCTYPE other []><root/>";
        try {
            runScannerWithDoctype(xml);
            org.junit.Assert.fail("expected a fatal error for a root element name mismatch");
        } catch (org.xml.sax.SAXException e) {
            assertTrue(e.getMessage().contains("does not match DOCTYPE name"));
        }
    }

    @Test
    public void testDoctypeWithExternalIdAndInternalSubsetIsSkippedNotFetched() throws Exception {
        String xml = "<!DOCTYPE root SYSTEM \"nonexistent.dtd\" [<!ENTITY foo \"bar\">]>"
                + "<root>&foo;</root>";
        assertEquals(Arrays.asList(
                "startDocument()",
                "startElement(,root,root,[])",
                "characters:bar",
                "endElement(,root,root)",
                "endDocument()"), runScannerWithDoctype(xml));
    }

    @Test
    public void testAttlistDefaultValueContainingGreaterThanInsideQuotes() throws Exception {
        // '>' inside the quoted default value must not terminate the
        // <!ATTLIST...> declaration early (M4-era concern); now that M5
        // actually parses ATTLIST (not just skips it), the declared default
        // is also expected to be injected since "note" is left unspecified.
        String xml = "<!DOCTYPE root [<!ELEMENT root (#PCDATA)>"
                + "<!ATTLIST root note CDATA \"a > b\">"
                + "<!ENTITY foo \"bar\">]><root>&foo;</root>";
        assertEquals(Arrays.asList(
                "startDocument()",
                "startElement(,root,root,[ note=a > b(CDATA)])",
                "characters:bar",
                "endElement(,root,root)",
                "endDocument()"), runScannerWithDoctype(xml));
    }

    @Test
    public void testDoctypeWithNoInternalSubset() throws Exception {
        String xml = "<!DOCTYPE root><root>text</root>";
        assertEquals(Arrays.asList(
                "startDocument()",
                "startElement(,root,root,[])",
                "characters:text",
                "endElement(,root,root)",
                "endDocument()"), runScannerWithDoctype(xml));
    }

    // ===== M5: attribute defaulting, type-aware normalisation, ignorableWhitespace =====

    private static final String M5_SAMPLE_XML = "<!DOCTYPE root [\n"
            + "<!ELEMENT root (child)*>\n"
            + "<!ATTLIST root id CDATA \"r1\">\n"
            + "<!ELEMENT child (#PCDATA)>\n"
            + "<!ATTLIST child token NMTOKEN #IMPLIED>\n"
            + "]>\n"
            + "<root>\n"
            + "  <child token=\"  a\tb  \">hello</child>\n"
            + "  <child>world</child>\n"
            + "</root>\n";

    @Test
    public void testDifferential_attributeDefaultingNormalizationAndIgnorableWhitespace() throws Exception {
        byte[] bytes = M5_SAMPLE_XML.getBytes(StandardCharsets.UTF_8);
        List<String> reference = normalizeM4Reference(runCurrentParser(bytes));
        assertTrue("reference recording should not be trivially empty", reference.size() > 5);

        char[] chars = M5_SAMPLE_XML.toCharArray();
        for (int chunkSize : CHUNK_SIZES) {
            List<String> actual = runScanner(chars, chunkSize);
            assertEquals("chunk size " + chunkSize, reference, actual);
        }
    }

    @Test
    public void testAttributeDefaultInjectedWhenNotSpecified() throws Exception {
        String xml = "<!DOCTYPE root [<!ATTLIST root id CDATA \"r1\">]><root/>";
        assertEquals(Arrays.asList(
                "startDocument()",
                "startElement(,root,root,[ id=r1(CDATA)])",
                "endElement(,root,root)",
                "endDocument()"), runScannerWithDoctype(xml));
    }

    @Test
    public void testAttributeDefaultNotInjectedWhenSpecified() throws Exception {
        String xml = "<!DOCTYPE root [<!ATTLIST root id CDATA \"r1\">]><root id=\"specified\"/>";
        assertEquals(Arrays.asList(
                "startDocument()",
                "startElement(,root,root,[ id=specified(CDATA)])",
                "endElement(,root,root)",
                "endDocument()"), runScannerWithDoctype(xml));
    }

    @Test
    public void testFixedAttributeDefaultInjectedWhenAbsent() throws Exception {
        String xml = "<!DOCTYPE root [<!ATTLIST root ver CDATA #FIXED \"1.0\">]><root/>";
        assertEquals(Arrays.asList(
                "startDocument()",
                "startElement(,root,root,[ ver=1.0(CDATA)])",
                "endElement(,root,root)",
                "endDocument()"), runScannerWithDoctype(xml));
    }

    @Test
    public void testRequiredAndImpliedAttributesHaveNoDefaultToInject() throws Exception {
        String xml = "<!DOCTYPE root [<!ATTLIST root a CDATA #REQUIRED b CDATA #IMPLIED>]><root/>";
        assertEquals(Arrays.asList(
                "startDocument()",
                "startElement(,root,root,[])",
                "endElement(,root,root)",
                "endDocument()"), runScannerWithDoctype(xml));
    }

    @Test
    public void testAttributeDefaultValueResolvesForwardReferencedEntity() throws Exception {
        // The default references &greeting;, declared LATER in the same
        // internal subset - resolvable because defaults are only resolved
        // once the whole subset (and therefore every entity) is known.
        String xml = "<!DOCTYPE root [<!ATTLIST root msg CDATA \"&greeting; world\">"
                + "<!ENTITY greeting \"hello\">]><root/>";
        assertEquals(Arrays.asList(
                "startDocument()",
                "startElement(,root,root,[ msg=hello world(CDATA)])",
                "endElement(,root,root)",
                "endDocument()"), runScannerWithDoctype(xml));
    }

    @Test
    public void testNonCdataAttributeValueIsCollapsed() throws Exception {
        String xml = "<!DOCTYPE root [<!ATTLIST root token NMTOKEN #IMPLIED>]>"
                + "<root token=\"  a\tb  \"/>";
        assertEquals(Arrays.asList(
                "startDocument()",
                "startElement(,root,root,[ token=a b(NMTOKEN)])",
                "endElement(,root,root)",
                "endDocument()"), runScannerWithDoctype(xml));
    }

    @Test
    public void testCdataAttributeValueIsNotCollapsed() throws Exception {
        String xml = "<!DOCTYPE root [<!ATTLIST root note CDATA #IMPLIED>]>"
                + "<root note=\"  a  b  \"/>";
        assertEquals(Arrays.asList(
                "startDocument()",
                "startElement(,root,root,[ note=  a  b  (CDATA)])",
                "endElement(,root,root)",
                "endDocument()"), runScannerWithDoctype(xml));
    }

    @Test
    public void testDeclaredAttributeTypeReportedForSpecifiedValue() throws Exception {
        // SAX Attributes.getType() must reflect the ATTLIST-declared type,
        // not always "CDATA" (a bug present from M0 through M5, fixed as a
        // follow-up: SAXAdapter previously hardcoded "CDATA" regardless of
        // what the DTD declared - see ASYNC-PIPELINE.md's M5 section).
        String xml = "<!DOCTYPE root [<!ATTLIST root id ID #IMPLIED>]><root id=\"x1\"/>";
        assertEquals(Arrays.asList(
                "startDocument()",
                "startElement(,root,root,[ id=x1(ID)])",
                "endElement(,root,root)",
                "endDocument()"), runScannerWithDoctype(xml));
    }

    @Test
    public void testDeclaredAttributeTypeReportedForDefaultedValue() throws Exception {
        // The type must also be correct for an injected default - a
        // separate code path (applyAttributeDefaults) from a specified
        // value's (scanAttributesAndTagEnd).
        String xml = "<!DOCTYPE root [<!ATTLIST root kind NMTOKEN \"a\">]><root/>";
        assertEquals(Arrays.asList(
                "startDocument()",
                "startElement(,root,root,[ kind=a(NMTOKEN)])",
                "endElement(,root,root)",
                "endDocument()"), runScannerWithDoctype(xml));
    }

    @Test
    public void testEnumerationAttributeTypeReportedAsEnumeration() throws Exception {
        // A bare enumeration "(a|b)" has no keyword of its own - SAX (and
        // the old parser's AttListDeclParser) both report the literal type
        // name "ENUMERATION" for it.
        String xml = "<!DOCTYPE root [<!ATTLIST root kind (a|b) \"a\">]><root/>";
        assertEquals(Arrays.asList(
                "startDocument()",
                "startElement(,root,root,[ kind=a(ENUMERATION)])",
                "endElement(,root,root)",
                "endDocument()"), runScannerWithDoctype(xml));
    }

    @Test
    public void testNotationAttributeTypeReportedAsNotation() throws Exception {
        String xml = "<!DOCTYPE root [<!ATTLIST root kind NOTATION (a|b) \"a\">]><root/>";
        assertEquals(Arrays.asList(
                "startDocument()",
                "startElement(,root,root,[ kind=a(NOTATION)])",
                "endElement(,root,root)",
                "endDocument()"), runScannerWithDoctype(xml));
    }

    @Test
    public void testUndeclaredAttributeTypeDefaultsToCdata() throws Exception {
        // No ATTLIST for "extra" at all - SAX convention: "CDATA".
        String xml = "<!DOCTYPE root [<!ATTLIST root id ID #IMPLIED>]><root extra=\"v\"/>";
        assertEquals(Arrays.asList(
                "startDocument()",
                "startElement(,root,root,[ extra=v(CDATA)])",
                "endElement(,root,root)",
                "endDocument()"), runScannerWithDoctype(xml));
    }

    @Test
    public void testUnconditionalAttributeWhitespaceNormalizationAppliesWithoutDtd() throws Exception {
        String xml = "<root note=\"a\tb\nc\"/>";
        assertEquals(Arrays.asList(
                "startDocument()",
                "startElement(,root,root,[ note=a b c(CDATA)])",
                "endElement(,root,root)",
                "endDocument()"), runScannerWithDoctype(xml));
    }

    @Test
    public void testIgnorableWhitespaceForElementOnlyContent() throws Exception {
        String xml = "<!DOCTYPE root [<!ELEMENT root (child)*><!ELEMENT child EMPTY>]>"
                + "<root>  <child/>  <child/>  </root>";
        assertEquals(Arrays.asList(
                "startDocument()",
                "startElement(,root,root,[])",
                "ignorableWhitespace:  ",
                "startElement(,child,child,[])",
                "endElement(,child,child)",
                "ignorableWhitespace:  ",
                "startElement(,child,child,[])",
                "endElement(,child,child)",
                "ignorableWhitespace:  ",
                "endElement(,root,root)",
                "endDocument()"), runScannerWithDoctype(xml));
    }

    @Test
    public void testWhitespaceIsCharactersForMixedContent() throws Exception {
        String xml = "<!DOCTYPE root [<!ELEMENT root (#PCDATA|child)*><!ELEMENT child EMPTY>]>"
                + "<root>  <child/>  </root>";
        assertEquals(Arrays.asList(
                "startDocument()",
                "startElement(,root,root,[])",
                "characters:  ",
                "startElement(,child,child,[])",
                "endElement(,child,child)",
                "characters:  ",
                "endElement(,root,root)",
                "endDocument()"), runScannerWithDoctype(xml));
    }

    @Test
    public void testWhitespaceIsCharactersWithNoElementDeclaration() throws Exception {
        String xml = "<!DOCTYPE root [<!ENTITY foo \"bar\">]><root>  <child/>  </root>";
        assertEquals(Arrays.asList(
                "startDocument()",
                "startElement(,root,root,[])",
                "characters:  ",
                "startElement(,child,child,[])",
                "endElement(,child,child)",
                "characters:  ",
                "endElement(,root,root)",
                "endDocument()"), runScannerWithDoctype(xml));
    }

    @Test
    public void testNonWhitespaceContentInElementOnlyContentStillCharacters() throws Exception {
        // Leading/trailing whitespace around non-whitespace content, even
        // within an element-only-content element, still splits into
        // separate homogeneous runs: ignorableWhitespace() for the
        // whitespace, ordinary characters() for the non-whitespace between -
        // matching the spec's per-run (not per-inter-tag-text) granularity
        // for "which characters constitute whitespace in element content".
        String xml = "<!DOCTYPE root [<!ELEMENT root (child)*>]><root>  text  <child/></root>";
        assertEquals(Arrays.asList(
                "startDocument()",
                "startElement(,root,root,[])",
                "ignorableWhitespace:  ",
                "characters:text",
                "ignorableWhitespace:  ",
                "startElement(,child,child,[])",
                "endElement(,child,child)",
                "endElement(,root,root)",
                "endDocument()"), runScannerWithDoctype(xml));
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
    public void testUndeclaredGeneralEntityIsFatal() throws Exception {
        // M4: a general entity reference is no longer unconditionally
        // unsupported - but referencing one that was never declared (no
        // DOCTYPE at all here) is still a fatal WFC violation.
        String xml = "<root>&customEntity;</root>";
        try {
            runScanner(xml.toCharArray(), 0);
            org.junit.Assert.fail("expected a fatal error for an undeclared general entity reference");
        } catch (org.xml.sax.SAXException e) {
            assertTrue(e.getMessage().contains("was not declared"));
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

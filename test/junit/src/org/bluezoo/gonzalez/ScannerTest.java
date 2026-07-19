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

import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Verification for {@link Scanner}. Two kinds of check:
 * <ul>
 * <li>Hand-crafted cases exercising specific productions (entities, comments,
 * PI, CDATA) directly.</li>
 * <li>Chunk-size self-consistency on the real benchmark corpus: compare
 * Scanner event lists across chunk sizes. A passing result at every chunk
 * size means the coarse resumable mode never double-emits or drops a
 * sub-token at a buffer-straddling split.
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


    private void assertChunkConsistency(String resourcePath) throws Exception {
        byte[] bytes = Files.readAllBytes(Paths.get(resourcePath));
        char[] chars = decodeAndStripDecl(bytes);
        List<String> reference = runScanner(chars, 0);
        assertTrue("reference recording should not be trivially empty", reference.size() > 5);

        for (int chunkSize : CHUNK_SIZES) {
            if (chunkSize == 0) {
                continue;
            }
            List<String> actual = runScanner(chars, chunkSize);
            assertEquals(resourcePath + " chunk size " + chunkSize, reference, actual);
        }
    }

    @Test
    public void testDifferential_plain() throws Exception {
        assertChunkConsistency("benchmark/resources/large.xml");
    }

    @Test
    public void testDifferential_attrs() throws Exception {
        assertChunkConsistency("benchmark/resources/attrs-large.xml");
    }

    @Test
    public void testDifferential_markup() throws Exception {
        // Comments, non-xmldecl PIs, CDATA-adjacent constructs.
        assertChunkConsistency("benchmark/resources/markup-large.xml");
    }

    @Test
    public void testDifferential_whitespace() throws Exception {
        assertChunkConsistency("benchmark/resources/whitespace-large.xml");
    }

    @Test
    public void testDifferential_multibyte() throws Exception {
        // Non-ASCII element/attribute names and content.
        assertChunkConsistency("benchmark/resources/multibyte-large.xml");
    }

    @Test
    public void testDifferential_namespacedButUnawareMode() throws Exception {
        // xmlns-heavy document, parsed in namespace-UNAWARE mode on both
        // sides - xmlns/prefixed names must be reported as plain attributes
        // and opaque qNames on both, with no resolution.
        assertChunkConsistency("benchmark/resources/large-ns.xml");
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


    @Test
    public void testDifferential_namespaceAwareMode() throws Exception {
        // Same corpus as the unaware-mode test above, this time resolved:
        // xmlns declarations become namespace()/startPrefixMapping events,
        // not plain attributes, and prefixed names resolve to uri/localName.
        byte[] bytes = Files.readAllBytes(Paths.get("benchmark/resources/large-ns.xml"));
        char[] chars = decodeAndStripDecl(bytes);
        List<String> reference = runScannerNamespaceAware(chars, 0);
        assertTrue("reference recording should not be trivially empty", reference.size() > 5);

        for (int chunkSize : CHUNK_SIZES) {
            if (chunkSize == 0) {
                continue;
            }
            List<String> actual = runScannerNamespaceAware(chars, chunkSize);
            assertEquals("chunk size " + chunkSize, reference, actual);
        }
    }

    // ===== DOCTYPE / internal general entities chunk consistency =====
    //
    // None of the benchmark corpus files use DOCTYPE, so this is a
    // hand-written sample rather than a corpus file.

    private static final String M4_SAMPLE_XML = "<!DOCTYPE root [\n"
            + "<!-- a comment in the internal subset -->\n"
            + "<!ATTLIST root id CDATA #IMPLIED>\n"
            // No PI data here deliberately - keep the subset PI target-only.
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
        char[] chars = M4_SAMPLE_XML.toCharArray();
        List<String> reference = runScanner(chars, 0);
        assertTrue("reference recording should not be trivially empty", reference.size() > 5);

        for (int chunkSize : CHUNK_SIZES) {
            if (chunkSize == 0) {
                continue;
            }
            List<String> actual = runScanner(chars, chunkSize);
            assertEquals("chunk size " + chunkSize, reference, actual);
        }
    }

    @Test(timeout = 5000)
    public void testDoctypeAndEntitiesSplitAcrossReceiveBoundary_chunkSize1() throws Exception {
        // chunkSize=1 delivers one character at a time, exercising every
        // possible split point through the DOCTYPE keyword, the internal
        // subset's declarations, and the entity references themselves.
        List<String> reference = runScanner(M4_SAMPLE_XML.toCharArray(), 0);
        List<String> actual = runScanner(M4_SAMPLE_XML.toCharArray(), 1);
        assertEquals(reference, actual);
    }

    // ===== M4: hand-crafted entity coverage =====

    private static List<String> runScannerWithDoctype(String xml) throws Exception {
        return runScanner(xml.toCharArray(), 0);
    }

    // ===== M6: real name-character classes + character-reference legality =====

    private static List<String> runScannerXml11(String xml) throws Exception {
        RecordingSaxHandler sink = new RecordingSaxHandler();
        SAXAdapter adapter = new SAXAdapter(false);
        adapter.setContentHandler(sink);
        adapter.setLexicalHandler(sink);
        Scanner scanner = new Scanner(adapter, true);
        scanner.receive(CharBuffer.wrap(xml.toCharArray()));
        scanner.close();
        return sink.getEvents();
    }

    @Test
    public void testLegalUnicodeNameCharacterAccepted() throws Exception {
        // Greek lambda (U+03BB), within the 0x370-0x1FFF NameStartChar range.
        String xml = "<\u03bb\u03bb/>";
        assertEquals(Arrays.asList(
                "startDocument()",
                "startElement(,\u03bb\u03bb,\u03bb\u03bb,[])",
                "endElement(,\u03bb\u03bb,\u03bb\u03bb)",
                "endDocument()"), runScannerWithDoctype(xml));
    }

    @Test
    public void testIllegalUnicodeCharacterRejectedInElementName() throws Exception {
        // U+2018 (LEFT SINGLE QUOTATION MARK) falls in the gap between the
        // 0x200C-0x200D and 0x2070-0x218F NameStartChar ranges - the old
        // "any non-ASCII is legal" approximation would have wrongly
        // accepted it; real NameStartChar classification rejects it.
        String xml = "<a\u2018b/>";
        try {
            runScannerWithDoctype(xml);
            org.junit.Assert.fail("expected a fatal error for an illegal Unicode name character");
        } catch (org.xml.sax.SAXException e) {
            // expected - falls through to "Expected '=' after attribute name"
            // or a malformed-tag error, since the name scan stops right at
            // the illegal character; the specific message isn't the point.
        }
    }

    @Test
    public void testNumericCharacterReferenceControlCharRejectedInXml10() throws Exception {
        String xml = "<root>&#8;</root>";
        try {
            runScannerWithDoctype(xml);
            org.junit.Assert.fail("expected a fatal error for an illegal control character reference in XML 1.0");
        } catch (org.xml.sax.SAXException e) {
            assertTrue(e.getMessage().contains("out of range"));
        }
    }

    @Test
    public void testNumericCharacterReferenceZeroAlwaysRejected() throws Exception {
        // Illegal in both XML 1.0 and 1.1 - the one control code point XML
        // 1.1's char-ref leniency does not extend to.
        String xml = "<root>&#0;</root>";
        try {
            runScannerXml11(xml);
            org.junit.Assert.fail("expected a fatal error for &#0; even in XML 1.1");
        } catch (org.xml.sax.SAXException e) {
            assertTrue(e.getMessage().contains("out of range"));
        }
    }

    @Test
    public void testNumericCharacterReferenceControlCharAllowedInXml11() throws Exception {
        // XML 1.1 permits referencing (not literally writing) C0/C1 control
        // characters - the same &#8; that testNumericCharacterReferenceControlCharRejectedInXml10
        // rejects under the default (XML 1.0) constructor is accepted here.
        String xml = "<root>&#8;</root>";
        assertEquals(Arrays.asList(
                "startDocument()",
                "startElement(,root,root,[])",
                "characters:" + (char) 8,
                "endElement(,root,root)",
                "endDocument()"), runScannerXml11(xml));
    }

    @Test
    public void testSimpleInternalEntityInContent() throws Exception {
        String xml = "<!DOCTYPE root [<!ENTITY foo \"bar\">]><root>&foo;</root>";
        assertEquals(Arrays.asList(
                "startDocument()",
                "startDTD(root,null,null)",
                "endDTD()",
                "startElement(,root,root,[])",
                "startEntity(foo)",
                "characters:bar",
                "endEntity(foo)",
                "endElement(,root,root)",
                "endDocument()"), runScannerWithDoctype(xml));
    }

    @Test
    public void testEntityValueCharRefExpandedAtDeclarationTime() throws Exception {
        String xml = "<!DOCTYPE root [<!ENTITY foo \"a &#65; b\">]><root>&foo;</root>";
        assertEquals(Arrays.asList(
                "startDocument()",
                "startDTD(root,null,null)",
                "endDTD()",
                "startElement(,root,root,[])",
                "startEntity(foo)",
                "characters:a A b",
                "endEntity(foo)",
                "endElement(,root,root)",
                "endDocument()"), runScannerWithDoctype(xml));
    }

    @Test
    public void testEntityContainingMarkupProducesElementEvents() throws Exception {
        String xml = "<!DOCTYPE root [<!ENTITY foo \"<b>bold</b>\">]><root>&foo;</root>";
        assertEquals(Arrays.asList(
                "startDocument()",
                "startDTD(root,null,null)",
                "endDTD()",
                "startElement(,root,root,[])",
                "startEntity(foo)",
                "startElement(,b,b,[])",
                "characters:bold",
                "endElement(,b,b)",
                "endEntity(foo)",
                "endElement(,root,root)",
                "endDocument()"), runScannerWithDoctype(xml));
    }

    @Test
    public void testNestedEntityReferenceResolvesRecursively() throws Exception {
        String xml = "<!DOCTYPE root [<!ENTITY a \"x\"><!ENTITY b \"&a; y\">]><root>&b;</root>";
        assertEquals(Arrays.asList(
                "startDocument()",
                "startDTD(root,null,null)",
                "endDTD()",
                "startElement(,root,root,[])",
                "startEntity(b)",
                "startEntity(a)",
                "characters:x",
                "endEntity(a)",
                "characters: y",
                "endEntity(b)",
                "endElement(,root,root)",
                "endDocument()"), runScannerWithDoctype(xml));
    }

    @Test
    public void testEntityInAttributeValue() throws Exception {
        String xml = "<!DOCTYPE root [<!ENTITY foo \"bar\">]><root a=\"x &foo; y\"/>";
        assertEquals(Arrays.asList(
                "startDocument()",
                "startDTD(root,null,null)",
                "endDTD()",
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
    public void testExternalEntityReferenceInAttributeValueIsFatal() throws Exception {
        // WFC "No External Entity References": unlike in content (fetched -
        // see testExternalGeneralEntityIsFetchedAndExpandedInContent below),
        // referencing an external entity from an attribute value is always
        // fatal, regardless of whether it could be fetched.
        String xml = "<!DOCTYPE root [<!ENTITY foo SYSTEM \"foo.ent\">]><root a=\"&foo;\"/>";
        try {
            runScannerWithDoctype(xml);
            org.junit.Assert.fail("expected a fatal error for an external entity reference in an attribute value");
        } catch (org.xml.sax.SAXException e) {
            assertTrue(e.getMessage().contains("may not be referenced in an attribute value"));
        }
    }

    @Test
    public void testExternalGeneralEntityIsFetchedAndExpandedInContent() throws Exception {
        // test/junit/resources/external-entity.ent contains the literal text
        // "external entity content" (no markup) - resolved relative to the
        // JVM's working directory, since this test's Scanner has no
        // baseSystemId (matching runScannerWithDoctype's plain constructor).
        String xml = "<!DOCTYPE root [<!ENTITY foo SYSTEM \"test/junit/resources/external-entity.ent\">]>"
                + "<root>&foo;</root>";
        assertEquals(Arrays.asList(
                "startDocument()",
                "startDTD(root,null,null)",
                "endDTD()",
                "startElement(,root,root,[])",
                "startEntity(foo)",
                "characters:external entity content",
                "endEntity(foo)",
                "endElement(,root,root)",
                "endDocument()"), runScannerWithDoctype(xml));
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
    public void testParameterEntityDeclarationAlone() throws Exception {
        // A parameter entity declaration with no reference to it is legal
        // and simply has no observable effect.
        String xml = "<!DOCTYPE root [<!ENTITY % pe \"x\">]><root/>";
        assertEquals(Arrays.asList(
                "startDocument()",
                "startDTD(root,null,null)",
                "endDTD()",
                "startElement(,root,root,[])",
                "endElement(,root,root)",
                "endDocument()"), runScannerWithDoctype(xml));
    }

    @Test
    public void testParameterEntityReferenceInInternalSubsetExpandsToDeclarations() throws Exception {
        String xml = "<!DOCTYPE root [<!ENTITY % pe \"<!ENTITY foo 'bar'>\">%pe;]><root>&foo;</root>";
        assertEquals(Arrays.asList(
                "startDocument()",
                "startDTD(root,null,null)",
                "endDTD()",
                "startElement(,root,root,[])",
                "startEntity(foo)",
                "characters:bar",
                "endEntity(foo)",
                "endElement(,root,root)",
                "endDocument()"), runScannerWithDoctype(xml));
    }

    @Test
    public void testUndeclaredParameterEntityReferenceIsFatal() throws Exception {
        String xml = "<!DOCTYPE root [%pe;]><root/>";
        try {
            runScannerWithDoctype(xml);
            org.junit.Assert.fail("expected a fatal error for an undeclared parameter entity reference");
        } catch (org.xml.sax.SAXException e) {
            assertTrue(e.getMessage().contains("Parameter entity"));
            assertTrue(e.getMessage().contains("was not declared"));
        }
    }

    @Test
    public void testDuplicateEntityDeclarationFirstWins() throws Exception {
        String xml = "<!DOCTYPE root [<!ENTITY foo \"first\"><!ENTITY foo \"second\">]><root>&foo;</root>";
        assertEquals(Arrays.asList(
                "startDocument()",
                "startDTD(root,null,null)",
                "endDTD()",
                "startElement(,root,root,[])",
                "startEntity(foo)",
                "characters:first",
                "endEntity(foo)",
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
    public void testRootElementNameMismatchIsNotFatal() throws Exception {
        // VC "Root Element Type" (XML 1.0 3.2), not a WFC - a non-validating
        // processor must not reject this (found via xmlconf Conformance
        // hardening; M4 had incorrectly enforced it as fatal).
        String xml = "<!DOCTYPE other []><root/>";
        assertEquals(Arrays.asList(
                "startDocument()",
                "startDTD(other,null,null)",
                "endDTD()",
                "startElement(,root,root,[])",
                "endElement(,root,root)",
                "endDocument()"), runScannerWithDoctype(xml));
    }

    @Test
    public void testDoctypeWithUnresolvableExternalIdIsFatal() throws Exception {
        // An external subset that genuinely can't be fetched is a fatal
        // error (task #17: external DTD/entity fetching), not silently
        // ignored - unlike M4, where this was deliberately never attempted
        // at all. The internal subset's own declarations are still fully
        // usable up to that point (not tested here specifically, since the
        // fetch failure aborts the parse regardless - see
        // testDoctypeWithExternalIdAndInternalSubsetBothContribute below for
        // the successful-fetch case demonstrating internal-wins-over-
        // external precedence).
        String xml = "<!DOCTYPE root SYSTEM \"nonexistent.dtd\" [<!ENTITY foo \"bar\">]>"
                + "<root>&foo;</root>";
        try {
            runScannerWithDoctype(xml);
            org.junit.Assert.fail("expected a fatal error for an unresolvable external DTD subset");
        } catch (org.xml.sax.SAXException e) {
            assertTrue(e.getMessage().contains("Failed to fetch"));
        }
    }

    @Test
    public void testDoctypeWithExternalIdAndInternalSubsetBothContribute() throws Exception {
        // test/junit/resources/external-subset.dtd declares &extfoo; -
        // fetched and merged alongside the internal subset's own &foo;,
        // demonstrating both internal and external subset entities are
        // usable together (and, via testDuplicateEntityDeclarationFirstWins-
        // style "first wins" semantics already covered elsewhere, that
        // internal declarations would win over a same-named external one,
        // per XML 4.2 - not separately exercised here since the two
        // fixtures don't declare overlapping names).
        String xml = "<!DOCTYPE root SYSTEM \"test/junit/resources/external-subset.dtd\" "
                + "[<!ENTITY foo \"internal value\">]>"
                + "<root>&foo; / &extfoo;</root>";
        assertEquals(Arrays.asList(
                "startDocument()",
                "startDTD(root,null,test/junit/resources/external-subset.dtd)",
                "startEntity([dtd])",
                "endEntity([dtd])",
                "endDTD()",
                "startElement(,root,root,[])",
                "startEntity(foo)",
                "characters:internal value",
                "endEntity(foo)",
                "characters: / ",
                "startEntity(extfoo)",
                "characters:value from external subset",
                "endEntity(extfoo)",
                "endElement(,root,root)",
                "endDocument()"), runScannerWithDoctype(xml));
    }

    @Test
    public void testExternalSubsetConditionalSectionsAndParameterEntity() throws Exception {
        // test/junit/resources/param-entity-conditional.dtd exercises both
        // features newly built for task #18: an INCLUDE section whose
        // content is a parameter entity reference expanding to a full
        // <!ELEMENT> declaration (mirroring the common real-world DTD idiom
        // seen in xmlconf's ibm/valid/P31 test), and an IGNORE section
        // containing text that is not valid markup at all - proving it is
        // genuinely skipped unparsed, not merely tolerated.
        String xml = "<!DOCTYPE root SYSTEM \"test/junit/resources/param-entity-conditional.dtd\">"
                + "<root>hello</root>";
        assertEquals(Arrays.asList(
                "startDocument()",
                "startDTD(root,null,test/junit/resources/param-entity-conditional.dtd)",
                "startEntity([dtd])",
                "endEntity([dtd])",
                "endDTD()",
                "startElement(,root,root,[])",
                "characters:hello",
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
                "startDTD(root,null,null)",
                "endDTD()",
                "startElement(,root,root,[ note=a > b(CDATA)])",
                "startEntity(foo)",
                "characters:bar",
                "endEntity(foo)",
                "endElement(,root,root)",
                "endDocument()"), runScannerWithDoctype(xml));
    }

    @Test
    public void testDoctypeWithNoInternalSubset() throws Exception {
        String xml = "<!DOCTYPE root><root>text</root>";
        assertEquals(Arrays.asList(
                "startDocument()",
                "startDTD(root,null,null)",
                "endDTD()",
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
        char[] chars = M5_SAMPLE_XML.toCharArray();
        List<String> reference = runScanner(chars, 0);
        assertTrue("reference recording should not be trivially empty", reference.size() > 5);

        for (int chunkSize : CHUNK_SIZES) {
            if (chunkSize == 0) {
                continue;
            }
            List<String> actual = runScanner(chars, chunkSize);
            assertEquals("chunk size " + chunkSize, reference, actual);
        }
    }

    @Test
    public void testAttributeDefaultInjectedWhenNotSpecified() throws Exception {
        String xml = "<!DOCTYPE root [<!ATTLIST root id CDATA \"r1\">]><root/>";
        assertEquals(Arrays.asList(
                "startDocument()",
                "startDTD(root,null,null)",
                "endDTD()",
                "startElement(,root,root,[ id=r1(CDATA)])",
                "endElement(,root,root)",
                "endDocument()"), runScannerWithDoctype(xml));
    }

    @Test
    public void testAttributeDefaultNotInjectedWhenSpecified() throws Exception {
        String xml = "<!DOCTYPE root [<!ATTLIST root id CDATA \"r1\">]><root id=\"specified\"/>";
        assertEquals(Arrays.asList(
                "startDocument()",
                "startDTD(root,null,null)",
                "endDTD()",
                "startElement(,root,root,[ id=specified(CDATA)])",
                "endElement(,root,root)",
                "endDocument()"), runScannerWithDoctype(xml));
    }

    @Test
    public void testFixedAttributeDefaultInjectedWhenAbsent() throws Exception {
        String xml = "<!DOCTYPE root [<!ATTLIST root ver CDATA #FIXED \"1.0\">]><root/>";
        assertEquals(Arrays.asList(
                "startDocument()",
                "startDTD(root,null,null)",
                "endDTD()",
                "startElement(,root,root,[ ver=1.0(CDATA)])",
                "endElement(,root,root)",
                "endDocument()"), runScannerWithDoctype(xml));
    }

    @Test
    public void testRequiredAndImpliedAttributesHaveNoDefaultToInject() throws Exception {
        String xml = "<!DOCTYPE root [<!ATTLIST root a CDATA #REQUIRED b CDATA #IMPLIED>]><root/>";
        assertEquals(Arrays.asList(
                "startDocument()",
                "startDTD(root,null,null)",
                "endDTD()",
                "startElement(,root,root,[])",
                "endElement(,root,root)",
                "endDocument()"), runScannerWithDoctype(xml));
    }

    @Test
    public void testAttributeDefaultValueRejectsForwardReferencedEntity() throws Exception {
        // WFC "Entity Declared" (Section 4.1): "the declaration of a general
        // entity must precede any reference to it which appears in a
        // default value in an attribute-list declaration" - unlike a
        // reference in content (always resolved only once the whole DTD is
        // known, so declaration order relative to the reference never
        // actually matters there), this is a special case that is checked
        // eagerly, at declaration time, so &greeting; here - declared LATER
        // in the same internal subset - must be rejected.
        String xml = "<!DOCTYPE root [<!ATTLIST root msg CDATA \"&greeting; world\">"
                + "<!ENTITY greeting \"hello\">]><root/>";
        try {
            runScannerWithDoctype(xml);
            org.junit.Assert.fail("expected a fatal error for an entity referenced in an ATTLIST default "
                    + "before its own declaration");
        } catch (org.xml.sax.SAXException e) {
            assertTrue(e.getMessage().contains("must be declared before"));
        }
    }

    @Test
    public void testAttributeDefaultValueResolvesBackwardReferencedEntity() throws Exception {
        // The mirror image of the above: &greeting; is declared BEFORE the
        // <!ATTLIST> that references it, so this remains legal.
        String xml = "<!DOCTYPE root [<!ENTITY greeting \"hello\">"
                + "<!ATTLIST root msg CDATA \"&greeting; world\">]><root/>";
        assertEquals(Arrays.asList(
                "startDocument()",
                "startDTD(root,null,null)",
                "endDTD()",
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
                "startDTD(root,null,null)",
                "endDTD()",
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
                "startDTD(root,null,null)",
                "endDTD()",
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
                "startDTD(root,null,null)",
                "endDTD()",
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
                "startDTD(root,null,null)",
                "endDTD()",
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
                "startDTD(root,null,null)",
                "endDTD()",
                "startElement(,root,root,[ kind=a(ENUMERATION)])",
                "endElement(,root,root)",
                "endDocument()"), runScannerWithDoctype(xml));
    }

    @Test
    public void testNotationAttributeTypeReportedAsNotation() throws Exception {
        String xml = "<!DOCTYPE root [<!ATTLIST root kind NOTATION (a|b) \"a\">]><root/>";
        assertEquals(Arrays.asList(
                "startDocument()",
                "startDTD(root,null,null)",
                "endDTD()",
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
                "startDTD(root,null,null)",
                "endDTD()",
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
                "startDTD(root,null,null)",
                "endDTD()",
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
                "startDTD(root,null,null)",
                "endDTD()",
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
                "startDTD(root,null,null)",
                "endDTD()",
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
                "startDTD(root,null,null)",
                "endDTD()",
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
                "startCDATA()",
                "characters:<raw> & text",
                "endCDATA()",
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

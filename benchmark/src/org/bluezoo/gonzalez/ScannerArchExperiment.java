/*
 * ScannerArchExperiment.java
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
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * Architecture probe: production {@link Scanner} vs. a hand-written specialized
 * hot-path scanner, both consuming the SAME already-decoded char[] and emitting
 * comparable event work into a Blackhole.
 * <p>
 * The Scanner contestant ({@link #scanScanner}) is the real, unmodified
 * {@link Scanner} driven with a no-op {@link XMLHandler} - the genuine
 * production cost.
 * <p>
 * The specialized contestant ({@link #scanSpecialized}) is a bare hot-path
 * scanner for element/attribute/text productions only: no entity expansion, no
 * location tracking, no line-ending normalization. It is therefore an
 * <em>optimistic upper bound</em> for what a further specialization could
 * achieve relative to the production Scanner.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 8, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 15, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(value = 3, jvmArgs = {"-Xms2G", "-Xmx2G"})
public class ScannerArchExperiment {

    @Param({"plain", "attrs"})
    private String docType;

    private char[] chars;

    // Event-kind tags for the specialized sink checksum (arbitrary distinct
    // constants, chosen so the accumulated sum reflects both event counts and
    // name/value/text lengths - prevents dead-code elimination and gives a
    // stable per-input signature).
    private static final long START = 1;
    private static final long ATTR = 3;
    private static final long ENDATTRS = 5;
    private static final long EMPTY = 7;
    private static final long CHARS = 11;
    private static final long END = 13;

    @Setup
    public void setup() throws Exception {
        String file;
        if ("attrs".equals(docType)) {
            file = "benchmark/resources/attrs-large.xml";
        } else {
            file = "benchmark/resources/large.xml";
        }
        byte[] bytes = Files.readAllBytes(Paths.get(file));
        // Decode once, up front - both contestants measure scan+emit only, not
        // byte->char decoding (which we already established is dominated by
        // unbeatable JDK SIMD intrinsics and is not the subject of this probe).
        char[] all = new String(bytes, StandardCharsets.UTF_8).toCharArray();

        // Strip a leading XML declaration: in the real pipeline the XMLDecl is
        // consumed by the decoder/bootstrap before Scanner ever sees document
        // content. Both contestants get the identical post-decl content.
        int start = 0;
        if (all.length > 5 && all[0] == '<' && all[1] == '?'
                && all[2] == 'x' && all[3] == 'm' && all[4] == 'l') {
            int j = 5;
            while (j + 1 < all.length && !(all[j] == '?' && all[j + 1] == '>')) {
                j++;
            }
            start = j + 2;
        }
        chars = new char[all.length - start];
        System.arraycopy(all, start, chars, 0, chars.length);

        // Cross-check that the specialized scanner actually traverses the whole
        // input and produces a non-trivial signature (guards against it silently
        // bailing early and "winning" by doing nothing).
        long sig = scanSpecializedInto(chars);
        long scannerSig = scanScannerInto(chars);
        System.out.println("setup: docType=" + docType + " chars=" + chars.length
                + " specializedSig=" + sig + " scannerSig=" + scannerSig);
    }

    // ===== Production contestant: Scanner =====

    /** Counts SAX events with length-weighted work comparable to the specialized sink. */
    private static final class CountingHandler extends DefaultHandler {
        long sum;

        @Override
        public void startElement(String uri, String localName, String qName,
                org.xml.sax.Attributes atts) {
            sum += START + qName.length();
            for (int i = 0; i < atts.getLength(); i++) {
                sum += ATTR + atts.getQName(i).length() + atts.getValue(i).length();
            }
            sum += ENDATTRS;
        }

        @Override
        public void endElement(String uri, String localName, String qName) {
            sum += END + qName.length();
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            sum += CHARS + length;
        }
    }

    private long scanScannerInto(char[] c) throws SAXException {
        CountingHandler handler = new CountingHandler();
        SAXAdapter adapter = new SAXAdapter(false);
        adapter.setContentHandler(handler);
        Scanner scanner = new Scanner(adapter);
        scanner.receive(CharBuffer.wrap(c));
        scanner.close();
        return handler.sum;
    }

    @Benchmark
    public void scanScanner(Blackhole bh) throws SAXException {
        bh.consume(scanScannerInto(chars));
    }

    // ===== Specialized contestant: bare hot-path scanner =====

    private static boolean isWs(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r';
    }

    private static boolean isNameChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9')
                || c == '_' || c == '-' || c == '.' || c == ':'
                || c > 127;
    }

    /**
     * Bare hot-path scanner: element start/empty/end tags, attributes, and text
     * content, emitting a length-weighted event signature. Prolog/PI/comment/
     * CDATA/doctype are crudely skipped (they are a negligible fraction of the
     * hot inputs used here). No entity expansion, location tracking, or
     * normalization - this is the optimistic ceiling, not a correct parser.
     */
    private long scanSpecializedInto(char[] c) {
        long sum = 0;
        int i = 0;
        int n = c.length;
        while (i < n) {
            char ch = c[i];
            if (ch == '<') {
                if (i + 1 < n && c[i + 1] == '?') {
                    // PI / xml decl: skip to ?>
                    i += 2;
                    while (i + 1 < n && !(c[i] == '?' && c[i + 1] == '>')) {
                        i++;
                    }
                    i += 2;
                    continue;
                }
                if (i + 1 < n && c[i + 1] == '!') {
                    if (i + 3 < n && c[i + 2] == '-' && c[i + 3] == '-') {
                        // comment: skip to -->
                        i += 4;
                        while (i + 2 < n && !(c[i] == '-' && c[i + 1] == '-' && c[i + 2] == '>')) {
                            i++;
                        }
                        i += 3;
                        continue;
                    }
                    // CDATA / doctype: crude skip to '>'
                    i += 2;
                    while (i < n && c[i] != '>') {
                        i++;
                    }
                    i++;
                    continue;
                }
                if (i + 1 < n && c[i + 1] == '/') {
                    // end element
                    i += 2;
                    int nameStart = i;
                    while (i < n && isNameChar(c[i])) {
                        i++;
                    }
                    sum += END + (i - nameStart);
                    while (i < n && c[i] != '>') {
                        i++;
                    }
                    i++;
                    continue;
                }
                // start element
                i++;
                int nameStart = i;
                while (i < n && isNameChar(c[i])) {
                    i++;
                }
                sum += START + (i - nameStart);
                // attributes
                while (true) {
                    while (i < n && isWs(c[i])) {
                        i++;
                    }
                    if (i >= n) {
                        break;
                    }
                    char d = c[i];
                    if (d == '>') {
                        i++;
                        sum += ENDATTRS;
                        break;
                    }
                    if (d == '/') {
                        i++;
                        if (i < n && c[i] == '>') {
                            i++;
                        }
                        sum += ENDATTRS + EMPTY;
                        break;
                    }
                    // attribute name
                    int anStart = i;
                    while (i < n && isNameChar(c[i])) {
                        i++;
                    }
                    int anLen = i - anStart;
                    while (i < n && isWs(c[i])) {
                        i++;
                    }
                    if (i < n && c[i] == '=') {
                        i++;
                    }
                    while (i < n && isWs(c[i])) {
                        i++;
                    }
                    char q = (i < n) ? c[i] : '"';
                    i++;
                    int avStart = i;
                    while (i < n && c[i] != q) {
                        i++;
                    }
                    int avLen = i - avStart;
                    i++;
                    sum += ATTR + anLen + avLen;
                }
                continue;
            }
            // text content
            int textStart = i;
            while (i < n && c[i] != '<') {
                i++;
            }
            sum += CHARS + (i - textStart);
        }
        return sum;
    }

    @Benchmark
    public void scanSpecialized(Blackhole bh) {
        bh.consume(scanSpecializedInto(chars));
    }
}

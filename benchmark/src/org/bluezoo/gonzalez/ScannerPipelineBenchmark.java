/*
 * ScannerPipelineBenchmark.java
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
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import org.openjdk.jmh.annotations.*;
import org.xml.sax.helpers.DefaultHandler;

/**
 * M1 perf gate: the trustworthy, realistic figure this milestone requires
 * before further investment, superseding {@link ScannerArchExperiment}'s
 * deliberately optimistic ceiling (which handicapped itself in its own
 * favour - no entity handling, no real handler, no full correctness).
 * <p>
 * Mirrors {@code XMLParserBenchmark}'s exact setup (same corpus, same JVM
 * args, same warmup/measurement schedule) so the three contestants are
 * comparable: the current parser's full byte-to-SAX pipeline, JDK Xerces,
 * and {@link Scanner}+{@link SAXAdapter}'s full byte-to-SAX pipeline (decode
 * included, inside the timed method, exactly as the other two do it).
 * <p>
 * Restricted to the five docTypes {@link Scanner} already handles correctly
 * per the M1 differential tests (plain/attrs/whitespace/markup/multibyte) -
 * "namespaced" is excluded because namespace resolution is M3's milestone,
 * not M1's; comparing it now would not be comparing the same thing.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(value = 2, jvmArgs = {"-Xms2G", "-Xmx2G"})
public class ScannerPipelineBenchmark {

    @Param({"plain", "attrs", "whitespace", "markup", "multibyte"})
    private String docType;

    private SAXParserFactory jdkFactory;
    private DefaultHandler emptyHandler;

    private byte[] largeBytes;
    /** largeBytes with the leading XML declaration stripped, for the Scanner
     *  pipeline (XMLDecl bootstrap is out of scope for M1 - reused as-is
     *  elsewhere in the real pipeline, so excluded here too rather than
     *  penalizing Scanner for a component it doesn't own). The declaration
     *  is a small constant-size prefix (tens of bytes against a ~1MB
     *  document); excluding it does not meaningfully bias the comparison. */
    private byte[] largeBytesNoDecl;

    private static final String JDK_XERCES_FACTORY =
        "com.sun.org.apache.xerces.internal.jaxp.SAXParserFactoryImpl";

    @Setup
    public void setup() throws Exception {
        jdkFactory = SAXParserFactory.newInstance(JDK_XERCES_FACTORY, null);
        jdkFactory.setNamespaceAware(false);
        jdkFactory.setValidating(false);

        emptyHandler = new DefaultHandler();

        Path largeFile;
        switch (docType) {
            case "attrs":
                largeFile = Paths.get("benchmark/resources/attrs-large.xml");
                break;
            case "whitespace":
                largeFile = Paths.get("benchmark/resources/whitespace-large.xml");
                break;
            case "markup":
                largeFile = Paths.get("benchmark/resources/markup-large.xml");
                break;
            case "multibyte":
                largeFile = Paths.get("benchmark/resources/multibyte-large.xml");
                break;
            default:
                largeFile = Paths.get("benchmark/resources/large.xml");
                break;
        }
        if (!Files.exists(largeFile)) {
            throw new FileNotFoundException("Large test file not found: " + largeFile);
        }
        largeBytes = Files.readAllBytes(largeFile);
        largeBytesNoDecl = stripXmlDecl(largeBytes);

        System.out.println("ScannerPipelineBenchmark setup complete:");
        System.out.println("  Document type: " + docType);
        System.out.println("  Large file: " + largeFile + " (" + largeBytes.length + " bytes)");
    }

    private static byte[] stripXmlDecl(byte[] bytes) {
        String prefix = new String(bytes, 0, Math.min(6, bytes.length), StandardCharsets.US_ASCII);
        if (!prefix.startsWith("<?xml")) {
            return bytes;
        }
        int i = 5;
        while (i + 1 < bytes.length && !(bytes[i] == '?' && bytes[i + 1] == '>')) {
            i++;
        }
        int start = i + 2;
        while (start < bytes.length && (bytes[start] == '\n' || bytes[start] == '\r')) {
            start++;
        }
        byte[] result = new byte[bytes.length - start];
        System.arraycopy(bytes, start, result, 0, result.length);
        return result;
    }

    @Benchmark
    public void largeFile_JDK() throws Exception {
        SAXParser parser = jdkFactory.newSAXParser();
        InputStream is = new ByteArrayInputStream(largeBytes);
        parser.parse(is, emptyHandler);
        is.close();
    }

    @Benchmark
    public void largeFile_Gonzalez() throws Exception {
        Parser parser = new Parser();
        parser.setFeature("http://xml.org/sax/features/namespaces", false);
        parser.setContentHandler(emptyHandler);
        ByteBuffer buffer = ByteBuffer.wrap(largeBytes);
        parser.receive(buffer);
        parser.close();
    }

    @Benchmark
    public void largeFile_ScannerPipeline() throws Exception {
        SAXAdapter adapter = new SAXAdapter(false);
        adapter.setContentHandler(emptyHandler);
        Scanner scanner = new Scanner(adapter);

        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder();
        CharBuffer chars = CharBuffer.allocate(largeBytesNoDecl.length + 1);
        decoder.decode(ByteBuffer.wrap(largeBytesNoDecl), chars, true);
        decoder.flush(chars);
        chars.flip();

        scanner.receive(chars);
        scanner.close();
    }

}

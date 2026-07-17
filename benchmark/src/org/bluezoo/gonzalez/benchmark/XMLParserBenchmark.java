/*
 * XMLParserBenchmark.java
 * Copyright (C) 2025 Chris Burdess
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
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Gonzalez.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.bluezoo.gonzalez.benchmark;

import org.bluezoo.gonzalez.Parser;
import org.openjdk.jmh.annotations.*;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

/**
 * JMH benchmark comparing Gonzalez (ByteBuffer API) with JDK Xerces.
 *
 * <p>Tests several document shapes with namespace-aware processing on or off,
 * for both small and large files:
 * <ul>
 * <li>{@code plain} / {@code namespaced} - general-purpose documents (original corpus)</li>
 * <li>{@code attrs} - attribute-heavy elements, minimal text content</li>
 * <li>{@code whitespace} - deeply indented, pretty-printed documents</li>
 * <li>{@code markup} - comment/PI/CDATA-section heavy documents</li>
 * <li>{@code multibyte} - UTF-8 multibyte content (CJK, Greek, emoji surrogate pairs)</li>
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(value = 2, jvmArgs = {"-Xms2G", "-Xmx2G"})
public class XMLParserBenchmark {

    @Param({"plain", "namespaced", "attrs", "whitespace", "markup", "multibyte"})
    private String docType;

    @Param({"true", "false"})
    private boolean namespaceAware;

    private SAXParserFactory jdkFactory;
    private DefaultHandler emptyHandler;

    private byte[] smallBytes;
    private byte[] largeBytes;

    private Parser reusableParser;

    private static final String JDK_XERCES_FACTORY =
        "com.sun.org.apache.xerces.internal.jaxp.SAXParserFactoryImpl";

    @Setup
    public void setup() throws Exception {
        jdkFactory = SAXParserFactory.newInstance(JDK_XERCES_FACTORY, null);
        jdkFactory.setNamespaceAware(namespaceAware);
        jdkFactory.setValidating(false);

        emptyHandler = new DefaultHandler();

        Path smallFile;
        Path largeFile;
        switch (docType) {
            case "namespaced":
                smallFile = Paths.get("benchmark/resources/small-ns.xml");
                largeFile = Paths.get("benchmark/resources/large-ns.xml");
                break;
            case "attrs":
                smallFile = Paths.get("benchmark/resources/attrs-small.xml");
                largeFile = Paths.get("benchmark/resources/attrs-large.xml");
                break;
            case "whitespace":
                smallFile = Paths.get("benchmark/resources/whitespace-small.xml");
                largeFile = Paths.get("benchmark/resources/whitespace-large.xml");
                break;
            case "markup":
                smallFile = Paths.get("benchmark/resources/markup-small.xml");
                largeFile = Paths.get("benchmark/resources/markup-large.xml");
                break;
            case "multibyte":
                smallFile = Paths.get("benchmark/resources/multibyte-small.xml");
                largeFile = Paths.get("benchmark/resources/multibyte-large.xml");
                break;
            default:
                smallFile = Paths.get("benchmark/resources/small.xml");
                largeFile = Paths.get("benchmark/resources/large.xml");
                break;
        }

        if (!Files.exists(smallFile)) {
            throw new FileNotFoundException("Small test file not found: " + smallFile);
        }
        if (!Files.exists(largeFile)) {
            throw new FileNotFoundException("Large test file not found: " + largeFile);
        }

        smallBytes = Files.readAllBytes(smallFile);
        largeBytes = Files.readAllBytes(largeFile);

        System.out.println("Benchmark setup complete:");
        System.out.println("  Document type:    " + docType);
        System.out.println("  Namespace-aware:  " + namespaceAware);
        System.out.println("  JDK SAX factory:  " + jdkFactory.getClass().getName());
        System.out.println("  Small file: " + smallFile + " (" + smallBytes.length + " bytes)");
        System.out.println("  Large file: " + largeFile + " (" + largeBytes.length + " bytes)");
    }

    // ===== Small File =====

    @Benchmark
    public void smallFile_JDK() throws Exception {
        SAXParser parser = jdkFactory.newSAXParser();
        InputStream is = new ByteArrayInputStream(smallBytes);
        parser.parse(is, emptyHandler);
        is.close();
    }

    @Benchmark
    public void smallFile_Gonzalez() throws Exception {
        Parser parser = new Parser();
        parser.setFeature("http://xml.org/sax/features/namespaces", namespaceAware);
        parser.setContentHandler(emptyHandler);

        ByteBuffer buffer = ByteBuffer.wrap(smallBytes);
        parser.receive(buffer);
        parser.close();
    }

    // ===== Large File =====

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
        parser.setFeature("http://xml.org/sax/features/namespaces", namespaceAware);
        parser.setContentHandler(emptyHandler);

        ByteBuffer buffer = ByteBuffer.wrap(largeBytes);
        parser.receive(buffer);
        parser.close();
    }

    /**
     * Chunk size matching Xerces's XMLEntityManager.DEFAULT_BUFFER_SIZE, so
     * largeFile_Gonzalez_Chunked below reads the input stream-style, the way
     * largeFile_JDK effectively does internally (XMLEntityManager reads its
     * ByteArrayInputStream in 8192-byte calls regardless of the stream's
     * backing store) - unlike largeFile_Gonzalez above, which hands Gonzalez
     * the entire document in a single receive() call.
     */
    private static final int XERCES_CHUNK_SIZE = 8192;

    @Benchmark
    public void largeFile_Gonzalez_Chunked() throws Exception {
        Parser parser = new Parser();
        parser.setFeature("http://xml.org/sax/features/namespaces", namespaceAware);
        parser.setContentHandler(emptyHandler);

        int offset = 0;
        while (offset < largeBytes.length) {
            int len = Math.min(XERCES_CHUNK_SIZE, largeBytes.length - offset);
            ByteBuffer chunk = ByteBuffer.wrap(largeBytes, offset, len);
            parser.receive(chunk);
            offset += len;
        }
        parser.close();
    }

    @Benchmark
    public void largeFile_Gonzalez_Reuse() throws Exception {
        if (reusableParser == null) {
            reusableParser = new Parser();
            reusableParser.setFeature("http://xml.org/sax/features/namespaces", namespaceAware);
            reusableParser.setContentHandler(emptyHandler);
        } else {
            reusableParser.reset();
        }

        ByteBuffer buffer = ByteBuffer.wrap(largeBytes);
        reusableParser.receive(buffer);
        reusableParser.close();
    }
}

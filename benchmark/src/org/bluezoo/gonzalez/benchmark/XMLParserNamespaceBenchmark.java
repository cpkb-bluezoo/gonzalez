/*
 * XMLParserNamespaceBenchmark.java
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
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

/**
 * JMH benchmark comparing namespace-aware vs non-namespace-aware parsing
 * for both Gonzalez and Java SAX parsers.
 * 
 * This tests the hypothesis that namespace-aware parsing adds significant overhead.
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(value = 2, jvmArgs = {"-Xms2G", "-Xmx2G"})
public class XMLParserNamespaceBenchmark {

    private SAXParserFactory saxParserFactoryNS;
    private SAXParserFactory saxParserFactoryNoNS;
    private DefaultHandler emptyHandler;
    
    private Path smallFile;
    private Path largeFile;
    
    private byte[] smallBytes;
    private byte[] largeBytes;

    @Setup
    public void setup() throws Exception {
        // Initialize SAX parser factories
        saxParserFactoryNS = SAXParserFactory.newInstance();
        saxParserFactoryNS.setNamespaceAware(true);
        saxParserFactoryNS.setValidating(false);
        
        saxParserFactoryNoNS = SAXParserFactory.newInstance();
        saxParserFactoryNoNS.setNamespaceAware(false);
        saxParserFactoryNoNS.setValidating(false);
        
        // Empty handler for SAX parsing
        emptyHandler = new DefaultHandler();
        
        // Locate test files
        smallFile = Paths.get("benchmark/resources/small.xml");
        largeFile = Paths.get("benchmark/resources/large.xml");
        
        if (!Files.exists(smallFile)) {
            throw new FileNotFoundException("Small test file not found: " + smallFile);
        }
        if (!Files.exists(largeFile)) {
            throw new FileNotFoundException("Large test file not found: " + largeFile);
        }
        
        // Pre-load file contents into byte arrays
        smallBytes = Files.readAllBytes(smallFile);
        largeBytes = Files.readAllBytes(largeFile);
        
        System.out.println("Namespace benchmark setup complete:");
        System.out.println("  Small file: " + smallFile + " (" + smallBytes.length + " bytes)");
        System.out.println("  Large file: " + largeFile + " (" + largeBytes.length + " bytes)");
    }

    // ===== Small File Benchmarks =====

    @Benchmark
    public void smallFile_JavaSAX_NamespaceAware() throws Exception {
        SAXParser parser = saxParserFactoryNS.newSAXParser();
        try (InputStream is = new ByteArrayInputStream(smallBytes)) {
            parser.parse(is, emptyHandler);
        }
    }

    @Benchmark
    public void smallFile_JavaSAX_NoNamespace() throws Exception {
        SAXParser parser = saxParserFactoryNoNS.newSAXParser();
        try (InputStream is = new ByteArrayInputStream(smallBytes)) {
            parser.parse(is, emptyHandler);
        }
    }

    @Benchmark
    public void smallFile_Gonzalez_NamespaceAware() throws Exception {
        Parser parser = new Parser();
        parser.setFeature("http://xml.org/sax/features/namespaces", true);
        parser.setContentHandler(emptyHandler);
        
        ByteBuffer buffer = ByteBuffer.wrap(smallBytes);
        parser.receive(buffer);
        parser.close();
    }

    @Benchmark
    public void smallFile_Gonzalez_NoNamespace() throws Exception {
        Parser parser = new Parser();
        parser.setFeature("http://xml.org/sax/features/namespaces", false);
        parser.setContentHandler(emptyHandler);
        
        ByteBuffer buffer = ByteBuffer.wrap(smallBytes);
        parser.receive(buffer);
        parser.close();
    }

    // ===== Large File Benchmarks =====

    @Benchmark
    public void largeFile_JavaSAX_NamespaceAware() throws Exception {
        SAXParser parser = saxParserFactoryNS.newSAXParser();
        try (InputStream is = new ByteArrayInputStream(largeBytes)) {
            parser.parse(is, emptyHandler);
        }
    }

    @Benchmark
    public void largeFile_JavaSAX_NoNamespace() throws Exception {
        SAXParser parser = saxParserFactoryNoNS.newSAXParser();
        try (InputStream is = new ByteArrayInputStream(largeBytes)) {
            parser.parse(is, emptyHandler);
        }
    }

    @Benchmark
    public void largeFile_Gonzalez_NamespaceAware() throws Exception {
        Parser parser = new Parser();
        parser.setFeature("http://xml.org/sax/features/namespaces", true);
        parser.setContentHandler(emptyHandler);
        
        ByteBuffer buffer = ByteBuffer.wrap(largeBytes);
        parser.receive(buffer);
        parser.close();
    }

    @Benchmark
    public void largeFile_Gonzalez_NoNamespace() throws Exception {
        Parser parser = new Parser();
        parser.setFeature("http://xml.org/sax/features/namespaces", false);
        parser.setContentHandler(emptyHandler);
        
        ByteBuffer buffer = ByteBuffer.wrap(largeBytes);
        parser.receive(buffer);
        parser.close();
    }
}


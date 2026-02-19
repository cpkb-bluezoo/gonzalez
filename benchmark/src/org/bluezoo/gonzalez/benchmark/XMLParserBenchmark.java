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
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.TimeUnit;

/**
 * JMH benchmark comparing Gonzalez parser performance with default Java SAX parser.
 * 
 * Tests both small (~1K) and large (>100K) XML documents without DOCTYPE declarations.
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

    private SAXParserFactory saxParserFactory;
    private DefaultHandler emptyHandler;
    
    private Path smallFile;
    private Path largeFile;
    
    private byte[] smallBytes;
    private byte[] largeBytes;

    private static final String JDK_XERCES_FACTORY =
        "com.sun.org.apache.xerces.internal.jaxp.SAXParserFactoryImpl";

    @Setup
    public void setup() throws Exception {
        // Explicitly use JDK Xerces to avoid SPI hijacking by other SAX
        // implementations (e.g., BFO SAX) on the classpath
        saxParserFactory = SAXParserFactory.newInstance(JDK_XERCES_FACTORY, null);
        saxParserFactory.setNamespaceAware(false);
        saxParserFactory.setValidating(false);
        
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
        
        // Pre-load file contents into byte arrays for Gonzalez benchmarks
        smallBytes = Files.readAllBytes(smallFile);
        largeBytes = Files.readAllBytes(largeFile);
        
        System.out.println("Benchmark setup complete:");
        System.out.println("  Small file: " + smallFile + " (" + smallBytes.length + " bytes)");
        System.out.println("  Large file: " + largeFile + " (" + largeBytes.length + " bytes)");
    }

    // ===== Small File Benchmarks =====

    @Benchmark
    public void smallFile_JavaSAX() throws Exception {
        SAXParser parser = saxParserFactory.newSAXParser();
        try (InputStream is = Files.newInputStream(smallFile)) {
            parser.parse(is, emptyHandler);
        }
    }

    @Benchmark
    public void smallFile_GonzalezByteBuffer() throws Exception {
        Parser parser = new Parser();
        parser.setFeature("http://xml.org/sax/features/namespaces", false);
        parser.setContentHandler(emptyHandler);
        
        ByteBuffer buffer = ByteBuffer.wrap(smallBytes);
        parser.receive(buffer);
        parser.close();
    }

    @Benchmark
    public void smallFile_GonzalezFileChannel() throws Exception {
        Parser parser = new Parser();
        parser.setFeature("http://xml.org/sax/features/namespaces", false);
        parser.setContentHandler(emptyHandler);
        
        try (FileChannel channel = FileChannel.open(smallFile, StandardOpenOption.READ)) {
            ByteBuffer buffer = ByteBuffer.allocate(8192);
            while (channel.read(buffer) != -1) {
                buffer.flip();
                parser.receive(buffer);
                buffer.clear();
            }
            parser.close();
        }
    }

    // ===== Large File Benchmarks =====

    @Benchmark
    public void largeFile_JavaSAX() throws Exception {
        SAXParser parser = saxParserFactory.newSAXParser();
        try (InputStream is = Files.newInputStream(largeFile)) {
            parser.parse(is, emptyHandler);
        }
    }

    @Benchmark
    public void largeFile_JavaSAX_Buffered8K() throws Exception {
        SAXParser parser = saxParserFactory.newSAXParser();
        try (InputStream is = new BufferedInputStream(Files.newInputStream(largeFile), 8192)) {
            parser.parse(is, emptyHandler);
        }
    }

    @Benchmark
    public void largeFile_GonzalezByteBuffer() throws Exception {
        Parser parser = new Parser();
        parser.setFeature("http://xml.org/sax/features/namespaces", false);
        parser.setContentHandler(emptyHandler);
        
        ByteBuffer buffer = ByteBuffer.wrap(largeBytes);
        parser.receive(buffer);
        parser.close();
    }

    // Reusable parser instance for reset benchmark
    private Parser reusableParser;
    
    @Benchmark
    public void largeFile_GonzalezByteBuffer_Reuse() throws Exception {
        if (reusableParser == null) {
            reusableParser = new Parser();
            reusableParser.setFeature("http://xml.org/sax/features/namespaces", false);
            reusableParser.setContentHandler(emptyHandler);
        } else {
            reusableParser.reset();
        }
        
        ByteBuffer buffer = ByteBuffer.wrap(largeBytes);
        reusableParser.receive(buffer);
        reusableParser.close();
    }

    @Benchmark
    public void largeFile_GonzalezFileChannel() throws Exception {
        Parser parser = new Parser();
        parser.setFeature("http://xml.org/sax/features/namespaces", false);
        parser.setContentHandler(emptyHandler);
        
        try (FileChannel channel = FileChannel.open(largeFile, StandardOpenOption.READ)) {
            ByteBuffer buffer = ByteBuffer.allocate(8192);
            while (channel.read(buffer) != -1) {
                buffer.flip();
                parser.receive(buffer);
                buffer.clear();
            }
            parser.close();
        }
    }
}


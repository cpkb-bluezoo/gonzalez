/*
 * XMLParserBenchmarkNoIntern.java
 * Copyright (C) 2025 Chris Burdess
 *
 * This file is part of Gonzalez, a streaming XML parser.
 */

package org.bluezoo.gonzalez.benchmark;

import org.bluezoo.gonzalez.Parser;
import org.openjdk.jmh.annotations.*;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.TimeUnit;

/**
 * JMH benchmark with string interning disabled for fair comparison.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(value = 2, jvmArgs = {"-Xms2G", "-Xmx2G"})
public class XMLParserBenchmarkNoIntern {

    private SAXParserFactory saxParserFactory;
    private DefaultHandler emptyHandler;
    
    private Path smallFile;
    private Path largeFile;
    
    private byte[] smallBytes;
    private byte[] largeBytes;

    @Setup
    public void setup() throws Exception {
        // Initialize SAX parser factory (default - string interning behavior varies by implementation)
        saxParserFactory = SAXParserFactory.newInstance();
        saxParserFactory.setNamespaceAware(false);
        saxParserFactory.setValidating(false);
        // Note: string-interning feature is not supported by default Java SAX parser
        
        emptyHandler = new DefaultHandler();
        
        smallFile = Paths.get("benchmark/resources/small.xml");
        largeFile = Paths.get("benchmark/resources/large.xml");
        
        smallBytes = Files.readAllBytes(smallFile);
        largeBytes = Files.readAllBytes(largeFile);
        
        System.out.println("Benchmark setup (Gonzalez with NO string interning):");
        System.out.println("  Small file: " + smallFile + " (" + smallBytes.length + " bytes)");
        System.out.println("  Large file: " + largeFile + " (" + largeBytes.length + " bytes)");
    }

    @Benchmark
    public void smallFile_JavaSAX_NoIntern() throws Exception {
        SAXParser parser = saxParserFactory.newSAXParser();
        try (var is = Files.newInputStream(smallFile)) {
            parser.parse(is, emptyHandler);
        }
    }

    @Benchmark
    public void smallFile_Gonzalez_NoIntern() throws Exception {
        Parser parser = new Parser();
        parser.setFeature("http://xml.org/sax/features/string-interning", false);
        parser.setContentHandler(emptyHandler);
        
        ByteBuffer buffer = ByteBuffer.wrap(smallBytes);
        parser.receive(buffer);
        parser.close();
    }

    @Benchmark
    public void largeFile_JavaSAX_NoIntern() throws Exception {
        SAXParser parser = saxParserFactory.newSAXParser();
        try (var is = Files.newInputStream(largeFile)) {
            parser.parse(is, emptyHandler);
        }
    }

    @Benchmark
    public void largeFile_Gonzalez_NoIntern() throws Exception {
        Parser parser = new Parser();
        parser.setFeature("http://xml.org/sax/features/string-interning", false);
        parser.setContentHandler(emptyHandler);
        
        ByteBuffer buffer = ByteBuffer.wrap(largeBytes);
        parser.receive(buffer);
        parser.close();
    }
}


/*
 * Errata2eConformanceTest.java
 * Copyright (C) 2025 Chris Burdess
 *
 * This file is part of Gonzalez, a streaming XML parser.
 *
 * Gonzalez is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.bluezoo.gonzalez;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.bluezoo.gonzalez.helpers.TestContentHandler;
import org.bluezoo.gonzalez.helpers.TestEntityResolverFactory;
import org.junit.AfterClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

import static org.junit.Assert.*;

/**
 * W3C XML Conformance Test for eduni/errata-2e test suite.
 * 
 * <p>Tests Richard Tobin's XML 1.0 2nd edition errata test suite.
 * This is a focused test on one specific subdirectory to establish
 * the pattern before expanding to other test suites.
 */
@RunWith(Parameterized.class)
public class Errata2eConformanceTest {
    
    private static final File XMLCONF_DIR = new File("xmlconf");
    private static final File TEST_DIR = new File(XMLCONF_DIR, "eduni/errata-2e");
    private static final File INDEX_FILE = new File(TEST_DIR, "errata2e.xml");
    private static final File OUTPUT_DIR = new File("test/output");
    private static final File REPORT_FILE = new File(OUTPUT_DIR, "errata2e-report.txt");
    
    private static List<TestCase> allTests;
    private static List<TestResult> results = new ArrayList<>();
    
    private final TestCase testCase;
    
    public Errata2eConformanceTest(TestCase testCase) {
        this.testCase = testCase;
    }
    
    @Parameters(name = "{0}")
    public static Collection<Object[]> getTestCases() throws Exception {
        // Parse the errata2e.xml index file
        allTests = parseIndexFile(INDEX_FILE, TEST_DIR);
        
        // Convert to JUnit parameter format
        List<Object[]> params = new ArrayList<>();
        for (TestCase test : allTests) {
            params.add(new Object[] { test });
        }
        
        System.out.println("Loaded " + allTests.size() + " tests from eduni/errata-2e");
        
        // Ensure output directory exists
        OUTPUT_DIR.mkdirs();
        
        return params;
    }
    
    /**
     * Parses an index XML file to extract test cases using Gonzalez parser.
     */
    private static List<TestCase> parseIndexFile(File indexFile, File baseDir) throws Exception {
        List<TestCase> tests = new ArrayList<>();
        
        GonzalezParser parser = new GonzalezParser();
        
        // Handler to extract TEST elements
        DefaultHandler handler = new DefaultHandler() {
            private StringBuilder currentText = new StringBuilder();
            private String currentId;
            private String currentType;
            private String currentUri;
            private String currentSections;
            private String currentEntities;
            private String currentVersion;
            private String currentOutput;
            
            @Override
            public void startElement(String uri, String localName, String qName, Attributes attributes) {
                currentText.setLength(0);  // Reset for new element
                
                if ("TEST".equals(qName)) {
                    currentId = attributes.getValue("ID");
                    currentType = attributes.getValue("TYPE");
                    currentUri = attributes.getValue("URI");
                    currentSections = attributes.getValue("SECTIONS");
                    currentEntities = attributes.getValue("ENTITIES");
                    currentVersion = attributes.getValue("VERSION");
                    currentOutput = attributes.getValue("OUTPUT");
                }
            }
            
            @Override
            public void characters(char[] ch, int start, int length) {
                currentText.append(ch, start, length);
            }
            
            @Override
            public void endElement(String uri, String localName, String qName) {
                if ("TEST".equals(qName)) {
                    // Create test case with description from text content
                    if (currentId != null && currentType != null && currentUri != null) {
                        File testFile = new File(baseDir, currentUri);
                        String description = currentText.toString().trim();
                        TestCase test = new TestCase(currentId, parseType(currentType), testFile, 
                                currentSections, currentEntities, currentVersion, currentOutput, description);
                        tests.add(test);
                    }
                    
                    // Reset for next test
                    currentId = null;
                    currentType = null;
                    currentUri = null;
                    currentSections = null;
                    currentEntities = null;
                    currentVersion = null;
                    currentOutput = null;
                }
            }
        };
        
        parser.setContentHandler(handler);
        parser.setSystemId(indexFile.toURI().toString());
        
        // Read and parse the index file
        try (FileInputStream fis = new FileInputStream(indexFile);
             FileChannel channel = fis.getChannel()) {
            
            ByteBuffer buffer = ByteBuffer.allocate(8192);
            while (channel.read(buffer) >= 0) {
                buffer.flip();
                if (buffer.hasRemaining()) {
                    parser.receive(buffer);
                }
                buffer.clear();
            }
            
            parser.close();
        }
        
        return tests;
    }
    
    private static TestCase.Type parseType(String typeStr) {
        switch (typeStr) {
            case "valid":
                return TestCase.Type.VALID;
            case "invalid":
                return TestCase.Type.INVALID;
            case "not-wf":
                return TestCase.Type.NOT_WF;
            case "error":
                return TestCase.Type.ERROR;
            default:
                throw new IllegalArgumentException("Unknown test type: " + typeStr);
        }
    }
    
    @Test
    public void testConformance() {
        TestResult result = runTest(testCase);
        synchronized (results) {
            results.add(result);
        }
        
        // Assert based on expected outcome
        if (!result.passed) {
            fail(String.format("Test %s failed: %s\nTest: %s\nFile: %s",
                    testCase.id, result.message, testCase.description, testCase.file));
        }
    }
    
    private TestResult runTest(TestCase test) {
        if (!test.file.exists()) {
            return new TestResult(test, false, "Test file not found: " + test.file);
        }
        
        try {
            GonzalezParser parser = new GonzalezParser();
            TestContentHandler handler = new TestContentHandler();
            
            parser.setContentHandler(handler);
            parser.setSystemId(test.file.toURI().toString());
            
            // Set up entity resolver for local files
            parser.setEntityResolverFactory(new TestEntityResolverFactory(XMLCONF_DIR));
            
            // Read and parse the test file
            try (FileInputStream fis = new FileInputStream(test.file);
                 FileChannel channel = fis.getChannel()) {
                
                ByteBuffer buffer = ByteBuffer.allocate(8192);
                while (channel.read(buffer) >= 0) {
                    buffer.flip();
                    if (buffer.hasRemaining()) {
                        parser.receive(buffer);
                    }
                    buffer.clear();
                }
                
                parser.close();
            }
            
            // Parser accepted the document
            return evaluateResult(test, true, null);
            
        } catch (SAXParseException e) {
            // Parser rejected with parse exception
            return evaluateResult(test, false, e);
            
        } catch (IOException e) {
            // I/O error reading test file
            return new TestResult(test, false, "I/O error: " + e.getMessage());
            
        } catch (Exception e) {
            // Unexpected exception
            return new TestResult(test, false, "Unexpected exception: " + 
                    e.getClass().getName() + ": " + e.getMessage());
        }
    }
    
    private TestResult evaluateResult(TestCase test, boolean parserAccepted, Exception exception) {
        switch (test.type) {
            case VALID:
                // Parser should accept valid documents
                if (parserAccepted) {
                    return new TestResult(test, true, "Correctly accepted valid document");
                } else {
                    String msg = exception != null ? exception.getMessage() : "unknown";
                    return new TestResult(test, false, "Incorrectly rejected valid document: " + msg);
                }
                
            case INVALID:
                // Non-validating parser may accept (well-formed but DTD-invalid)
                // We accept either outcome for now
                return new TestResult(test, true, 
                        parserAccepted ? "Non-validating parser accepted" : "Rejected invalid document");
                
            case NOT_WF:
                // Parser must reject not-well-formed documents
                if (!parserAccepted) {
                    return new TestResult(test, true, "Correctly rejected not-well-formed document");
                } else {
                    return new TestResult(test, false, "Incorrectly accepted not-well-formed document");
                }
                
            case ERROR:
                // Parser should report error
                if (!parserAccepted) {
                    return new TestResult(test, true, "Correctly reported error");
                } else {
                    return new TestResult(test, false, "Did not report error");
                }
                
            default:
                return new TestResult(test, false, "Unknown test type: " + test.type);
        }
    }
    
    @AfterClass
    public static void generateReport() throws IOException {
        // Calculate statistics
        int total = results.size();
        int passed = 0;
        int failed = 0;
        
        int[] passedByType = new int[TestCase.Type.values().length];
        int[] totalByType = new int[TestCase.Type.values().length];
        
        for (TestResult result : results) {
            int typeIndex = result.test.type.ordinal();
            totalByType[typeIndex]++;
            
            if (result.passed) {
                passed++;
                passedByType[typeIndex]++;
            } else {
                failed++;
            }
        }
        
        // Write report
        try (PrintWriter out = new PrintWriter(new FileWriter(REPORT_FILE))) {
            out.println("W3C XML Conformance Test Report");
            out.println("Test Suite: eduni/errata-2e (Richard Tobin's XML 1.0 2nd edition errata)");
            out.println("================================================================");
            out.println();
            out.println("Total Tests: " + total);
            out.println("Passed: " + passed);
            out.println("Failed: " + failed);
            out.println("Success Rate: " + (total > 0 ? String.format("%.1f%%", 100.0 * passed / total) : "N/A"));
            out.println();
            out.println("Results by Test Type:");
            out.println("---------------------");
            
            for (TestCase.Type type : TestCase.Type.values()) {
                int idx = type.ordinal();
                int typeTotal = totalByType[idx];
                int typePassed = passedByType[idx];
                if (typeTotal > 0) {
                    double typeRate = 100.0 * typePassed / typeTotal;
                    out.println(String.format("%-10s: %3d/%3d (%.1f%%)", 
                            type, typePassed, typeTotal, typeRate));
                }
            }
            
            // List failures
            if (failed > 0) {
                out.println();
                out.println("Failed Tests:");
                out.println("-------------");
                for (TestResult result : results) {
                    if (!result.passed) {
                        out.println(result.test.id + " [" + result.test.type + "] " + result.test.sections);
                        out.println("  " + result.test.description);
                        out.println("  File: " + result.test.file.getName());
                        out.println("  " + result.message);
                        out.println();
                    }
                }
            }
            
            // List what's being tested
            out.println();
            out.println("Test Coverage:");
            out.println("--------------");
            for (TestResult result : results) {
                out.println(String.format("%s [%s] %-8s: %s", 
                        result.passed ? "PASS" : "FAIL",
                        result.test.sections,
                        result.test.type,
                        result.test.description));
            }
        }
        
        System.out.println("\nConformance report written to: " + REPORT_FILE);
        System.out.println("Success rate: " + (total > 0 ? String.format("%.1f%%", 100.0 * passed / total) : "N/A"));
    }
    
    /**
     * Test case model for a single test.
     */
    public static class TestCase {
        public enum Type {
            VALID, INVALID, NOT_WF, ERROR
        }
        
        final String id;
        final Type type;
        final File file;
        final String sections;
        final String entities;
        final String version;
        final String output;
        final String description;
        
        TestCase(String id, Type type, File file, String sections, String entities,
                String version, String output, String description) {
            this.id = id;
            this.type = type;
            this.file = file;
            this.sections = sections;
            this.entities = entities;
            this.version = version;
            this.output = output;
            this.description = description;
        }
        
        TestCase withDescription(String description) {
            return new TestCase(id, type, file, sections, entities, version, output, description);
        }
        
        @Override
        public String toString() {
            return String.format("%s [%s] %s", id, type, description);
        }
    }
    
    /**
     * Result of running a single test.
     */
    private static class TestResult {
        final TestCase test;
        final boolean passed;
        final String message;
        
        TestResult(TestCase test, boolean passed, String message) {
            this.test = test;
            this.passed = passed;
            this.message = message;
        }
    }
}


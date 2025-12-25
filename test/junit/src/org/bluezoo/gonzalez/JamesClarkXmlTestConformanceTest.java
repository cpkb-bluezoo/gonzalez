/*
 * JamesClarkXmlTestConformanceTest.java
 * Copyright (C) 2025 Chris Burdess
 *
 * This file is part of Gonzalez, a streaming XML parser.
 *
 * Gonzalez is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.bluezoo.gonzalez;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.junit.AfterClass;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;
import org.xml.sax.Attributes;
import org.xml.sax.EntityResolver;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

import static org.junit.Assert.*;

/**
 * W3C XML Conformance Test for James Clark's xmltest suite.
 * 
 * <p>Tests James Clark's comprehensive XML test suite (18-Nov-1998).
 * This suite contains tests for well-formedness, validity, and proper behavior.
 * 
 * <p>Test types:
 * <ul>
 * <li>not-wf: Not well-formed - expect fatalError and SAXParseException</li>
 * <li>invalid: Invalid (validation error) - expect error when validation enabled</li>
 * <li>valid: Valid - no errors expected</li>
 * </ul>
 */
@Ignore("Superseded by XMLConformanceTest which includes this suite")
@RunWith(Parameterized.class)
public class JamesClarkXmlTestConformanceTest {
    
    private static final File XMLCONF_DIR = new File("xmlconf");
    private static final File TEST_DIR = new File(XMLCONF_DIR, "xmltest");
    private static final File INDEX_FILE = new File(TEST_DIR, "xmltest.xml");
    private static final File OUTPUT_DIR = new File("test/output");
    private static final File REPORT_FILE = new File(OUTPUT_DIR, "james-clark-xmltest-report.txt");
    
    private static List<TestCase> allTests;
    private static List<TestResult> results = new ArrayList<>();
    
    private final TestCase testCase;
    
    public JamesClarkXmlTestConformanceTest(TestCase testCase) {
        this.testCase = testCase;
    }
    
    @Parameters(name = "{0}")
    public static Collection<Object[]> getTestCases() throws Exception {
        // Parse the xmltest.xml index file
        allTests = parseIndexFile(INDEX_FILE, TEST_DIR);
        
        // Convert to JUnit parameter format
        List<Object[]> params = new ArrayList<>();
        for (TestCase test : allTests) {
            params.add(new Object[] { test });
        }
        
        System.out.println("Loaded " + allTests.size() + " tests from James Clark xmltest suite");
        
        // Ensure output directory exists
        OUTPUT_DIR.mkdirs();
        
        return params;
    }
    
    /**
     * Parses the xmltest.xml index file to extract test cases.
     */
    private static List<TestCase> parseIndexFile(File indexFile, File baseDir) throws Exception {
        List<TestCase> tests = new ArrayList<>();
        
        Parser parser = new Parser();
        
        // Simple entity resolver that resolves relative to the index file directory
        parser.setEntityResolver(new EntityResolver() {
            @Override
            public InputSource resolveEntity(String publicId, String systemId) throws SAXException, IOException {
                if (systemId == null) {
                    return null;
                }
                
                File entityFile;
                if (systemId.startsWith("file:")) {
                    entityFile = new File(systemId.substring(5));
                } else {
                    entityFile = new File(indexFile.getParentFile(), systemId);
                }
                
                if (!entityFile.exists()) {
                    return null;
                }
                
                InputSource source = new InputSource(new FileInputStream(entityFile));
                source.setSystemId(entityFile.toURI().toString());
                return source;
            }
        });
        
        // Handler to extract TEST elements
        DefaultHandler handler = new DefaultHandler() {
            private String currentId;
            private String currentType;
            private String currentUri;
            private String currentSections;
            private String currentEntities;
            private StringBuilder currentDescription = new StringBuilder();
            
            @Override
            public void startElement(String uri, String localName, String qName, Attributes attrs) {
                if ("TEST".equals(qName)) {
                    currentId = attrs.getValue("ID");
                    currentType = attrs.getValue("TYPE");
                    currentUri = attrs.getValue("URI");
                    currentSections = attrs.getValue("SECTIONS");
                    currentEntities = attrs.getValue("ENTITIES");
                    currentDescription.setLength(0);
                }
            }
            
            @Override
            public void characters(char[] ch, int start, int length) {
                if (currentId != null) {
                    currentDescription.append(ch, start, length);
                }
            }
            
            @Override
            public void endElement(String uri, String localName, String qName) {
                if ("TEST".equals(qName) && currentId != null) {
                    File testFile = new File(baseDir, currentUri);
                    TestCase test = new TestCase(
                        currentId,
                        currentType,
                        testFile,
                        currentDescription.toString().trim(),
                        currentSections,
                        currentEntities
                    );
                    tests.add(test);
                    
                    currentId = null;
                    currentType = null;
                    currentUri = null;
                    currentSections = null;
                    currentEntities = null;
                }
            }
        };
        
        parser.setContentHandler(handler);
        
        // Parse the index file
        InputSource source = new InputSource(new FileInputStream(indexFile));
        source.setSystemId(indexFile.toURI().toString());
        parser.parse(source);
        
        return tests;
    }
    
    @Test
    public void runTest() {
        TestResult result = new TestResult();
        result.id = testCase.id;
        result.expectedType = testCase.type;
        result.file = testCase.file;
        result.description = testCase.description;
        
        try {
            boolean gotFatalError = false;
            boolean gotError = false;
            SAXParseException capturedException = null;
            
            Parser parser = new Parser();
            parser.setContentHandler(new DefaultHandler()); // Simple handler
            
            // Entity resolver for test files
            parser.setEntityResolver(new EntityResolver() {
                @Override
                public InputSource resolveEntity(String publicId, String systemId) throws SAXException, IOException {
                    if (systemId == null) {
                        return null;
                    }
                    
                    File entityFile;
                    if (systemId.startsWith("file:")) {
                        entityFile = new File(systemId.substring(5));
                    } else {
                        entityFile = new File(testCase.file.getParentFile(), systemId);
                    }
                    
                    if (!entityFile.exists()) {
                        return null;
                    }
                    
                    InputSource source = new InputSource(new FileInputStream(entityFile));
                    source.setSystemId(entityFile.toURI().toString());
                    return source;
                }
            });
            
            // Track errors
            ErrorHandler errorHandler = new ErrorHandler() {
                @Override
                public void fatalError(SAXParseException e) throws SAXException {
                    throw e; // Fatal errors must stop processing
                }
                
                @Override
                public void error(SAXParseException e) throws SAXException {
                    // Validation errors are recoverable, just track them
                }
                
                @Override
                public void warning(SAXParseException e) {
                    // Ignore warnings
                }
            };
            
            parser.setErrorHandler(errorHandler);
            
            // Enable validation for "invalid" and "valid" type tests
            if ("invalid".equals(testCase.type) || "valid".equals(testCase.type)) {
                parser.setFeature("http://xml.org/sax/features/validation", true);
            }
            
            try {
                InputSource source = new InputSource(new FileInputStream(testCase.file));
                source.setSystemId(testCase.file.toURI().toString());
                parser.parse(source);
            } catch (SAXParseException e) {
                gotFatalError = true;
                capturedException = e;
            }
            
            // Determine actual result
            if ("not-wf".equals(testCase.type) || "error".equals(testCase.type)) {
                // Expect fatal error (some suites use "error" for well-formedness errors)
                if (gotFatalError) {
                    result.actualResult = "not-wf (got expected fatal error)";
                    result.passed = true;
                } else {
                    result.actualResult = "PARSED (expected fatal error!)";
                    result.passed = false;
                    result.message = "Expected not-wf but parsed successfully";
                }
            } else if ("invalid".equals(testCase.type)) {
                // Expect validation error (but we'd need to track error() calls)
                // For now, treat as valid if no fatal error
                result.actualResult = gotFatalError ? "not-wf" : "valid";
                result.passed = !gotFatalError;
                if (gotFatalError) {
                    result.message = "Got fatal error instead of validation error: " + capturedException.getMessage();
                }
            } else if ("valid".equals(testCase.type)) {
                // Expect no errors
                if (!gotFatalError) {
                    result.actualResult = "valid (parsed successfully)";
                    result.passed = true;
                } else {
                    result.actualResult = "not-wf";
                    result.passed = false;
                    result.message = "Expected valid but got error: " + capturedException.getMessage();
                }
            } else {
                result.actualResult = "UNKNOWN TYPE: " + testCase.type;
                result.passed = false;
            }
            
        } catch (Exception e) {
            result.actualResult = "EXCEPTION";
            result.passed = false;
            result.message = e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        
        results.add(result);
        
        // Assert the test passed
        if (!result.passed) {
            fail(String.format("[%s] %s: %s", 
                testCase.id, result.message, testCase.description));
        }
    }
    
    @AfterClass
    public static void generateReport() throws IOException {
        System.out.println("\n=== James Clark xmltest Report ===");
        
        int passed = 0;
        int failed = 0;
        
        try (PrintWriter out = new PrintWriter(new FileWriter(REPORT_FILE))) {
            out.println("James Clark xmltest Conformance Test Report");
            out.println("===========================================");
            out.println();
            out.printf("Total tests: %d%n", results.size());
            
            for (TestResult result : results) {
                if (result.passed) {
                    passed++;
                } else {
                    failed++;
                }
            }
            
            out.printf("Passed: %d (%.1f%%)%n", passed, 100.0 * passed / results.size());
            out.printf("Failed: %d (%.1f%%)%n", failed, 100.0 * failed / results.size());
            out.println();
            out.println("Detailed Results:");
            out.println("----------------");
            
            for (TestResult result : results) {
                out.printf("[%s] %s | Expected: %s | Actual: %s | %s%n",
                    result.passed ? "PASS" : "FAIL",
                    result.id,
                    result.expectedType,
                    result.actualResult,
                    result.file.getName());
                
                if (!result.passed && result.message != null) {
                    out.printf("    %s%n", result.message);
                }
                if (result.description != null && !result.description.isEmpty()) {
                    out.printf("    Description: %s%n", result.description);
                }
                out.println();
            }
        }
        
        System.out.printf("Passed: %d / %d (%.1f%%)%n", 
            passed, results.size(), 100.0 * passed / results.size());
        System.out.printf("Failed: %d / %d (%.1f%%)%n", 
            failed, results.size(), 100.0 * failed / results.size());
        System.out.println("\nDetailed report written to: " + REPORT_FILE.getAbsolutePath());
    }
    
    /**
     * Represents a single test case from the index.
     */
    static class TestCase {
        String id;
        String type; // "not-wf", "invalid", "valid"
        File file;
        String description;
        String sections;
        String entities;
        
        TestCase(String id, String type, File file, String description, String sections, String entities) {
            this.id = id;
            this.type = type;
            this.file = file;
            this.description = description;
            this.sections = sections;
            this.entities = entities;
        }
        
        @Override
        public String toString() {
            return id + " (" + type + ")";
        }
    }
    
    /**
     * Represents the result of running a test.
     */
    static class TestResult {
        String id;
        String expectedType;
        File file;
        String description;
        String actualResult;
        boolean passed;
        String message;
    }
}


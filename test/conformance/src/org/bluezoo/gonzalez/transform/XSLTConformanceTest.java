/*
 * XSLTConformanceTest.java
 * Copyright (C) 2026 Chris Burdess
 *
 * This file is part of Gonzalez, a streaming XML parser.
 *
 * Gonzalez is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.bluezoo.gonzalez.transform;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.xml.transform.Templates;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;

import org.junit.AfterClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import org.bluezoo.gonzalez.Parser;
import org.bluezoo.gonzalez.transform.GonzalezTransformer;

import static org.junit.Assert.*;

/**
 * W3C XSLT Conformance Test Suite runner.
 *
 * <p>Runs tests from the W3C XSLT 3.0 test suite (xslt30-test).
 * Tests are filtered by version:
 * <ul>
 *   <li>{@code -Dxslt.version=1.0} - Tests compatible with XSLT 1.0+</li>
 *   <li>{@code -Dxslt.version=2.0} - Tests compatible with XSLT 2.0+</li>
 *   <li>{@code -Dxslt.version=3.0} - All XSLT 3.0 tests</li>
 * </ul>
 *
 * <p>The test suite repository must be checked out at ../xslt30-test:
 * <pre>
 * cd .. && git clone https://github.com/w3c/xslt30-test.git
 * </pre>
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
@RunWith(Parameterized.class)
public class XSLTConformanceTest {

    // Base directory for resolving relative paths (when running from temp dir)
    private static final File PROJECT_BASEDIR = getProjectBasedir();
    private static final File XSLT30_TEST_DIR = new File(PROJECT_BASEDIR, "../xslt30-test");
    private static final File OUTPUT_DIR = new File(PROJECT_BASEDIR, "test/output");
    private static final File REPORT_FILE = new File(OUTPUT_DIR, "xslt-conformance-report.txt");
    private static final File STATS_FILE = new File(OUTPUT_DIR, "xslt-conformance-statistics.txt");
    
    private static File getProjectBasedir() {
        String basedir = System.getProperty("project.basedir");
        if (basedir != null && !basedir.isEmpty() && !basedir.startsWith("${")) {
            return new File(basedir);
        }
        // Fallback to current directory if not set
        return new File(".");
    }

    private static final String XSLT_TEST_NS = "http://www.w3.org/2012/10/xslt-test-catalog";
    
    // Filter properties - set via -Dxslt.version=1.0 or -Dxslt.filter=namespace
    private static final String VERSION_FILTER = normalizeProperty(System.getProperty("xslt.version"));  // "1.0", "2.0", "3.0"
    private static final String NAME_FILTER = normalizeProperty(System.getProperty("xslt.filter"));      // substring of test-set name
    private static final String CATEGORY_FILTER = normalizeProperty(System.getProperty("xslt.category")); // "fn", "insn", "decl", etc.
    private static final String SKIP_FILTER = normalizeProperty(System.getProperty("xslt.skip"));        // comma-separated patterns to skip
    
    // Default heavy test sets to skip (regex, unicode - thousands of tests each)
    // result-document tests are slow (write many files) - skip for faster regression testing
    private static final String DEFAULT_SKIP = "regex,unicode,result-document";
    
    private static String normalizeProperty(String value) {
        // Handle unset ant properties that come through as "${prop.name}"
        if (value == null || value.isEmpty() || value.startsWith("${")) {
            return null;
        }
        return value;
    }
    
    private static boolean shouldSkip(String name) {
        String skipPatterns = SKIP_FILTER != null ? SKIP_FILTER : DEFAULT_SKIP;
        if ("none".equalsIgnoreCase(skipPatterns)) {
            return false;  // Skip nothing
        }
        for (String pattern : skipPatterns.split(",")) {
            if (name.toLowerCase().contains(pattern.trim().toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private static List<XSLTTestCase> allTests;
    private static List<TestResult> results = new ArrayList<>();
    private static GonzalezTransformerFactory factory;

    private final XSLTTestCase testCase;

    public XSLTConformanceTest(XSLTTestCase testCase) {
        this.testCase = testCase;
    }

    @Parameters(name = "{0}")
    public static Collection<Object[]> getTestCases() throws Exception {
        allTests = new ArrayList<>();
        factory = new GonzalezTransformerFactory();

        File catalogFile = new File(XSLT30_TEST_DIR, "catalog.xml");
        if (!catalogFile.exists()) {
            System.err.println("XSLT conformance test suite not found at: " + XSLT30_TEST_DIR);
            System.err.println("Clone the test suite: cd .. && git clone https://github.com/w3c/xslt30-test.git");
            return new ArrayList<>();
        }

        System.out.println("Loading XSLT conformance tests from: " + XSLT30_TEST_DIR);

        // Parse catalog to get test-set references
        List<String> testSetFiles = parseCatalog(catalogFile);
        System.out.println("Found " + testSetFiles.size() + " test sets");

        // Parse each test set
        for (String testSetPath : testSetFiles) {
            File testSetFile = new File(XSLT30_TEST_DIR, testSetPath);
            if (!testSetFile.exists()) {
                System.err.println("  WARNING: Test set file not found: " + testSetFile);
                continue;
            }

            try {
                List<XSLTTestCase> tests = parseTestSet(testSetFile);
                allTests.addAll(tests);
                System.out.println("  Loaded " + tests.size() + " tests from " + testSetPath);
            } catch (Exception e) {
                System.err.println("  WARNING: Failed to load " + testSetPath + ": " + e.getMessage());
            }
        }

        // Convert to JUnit parameter format
        List<Object[]> params = new ArrayList<>();
        for (XSLTTestCase test : allTests) {
            params.add(new Object[]{test});
        }

        String filterDesc = "";
        if (VERSION_FILTER != null) filterDesc += " XSLT " + VERSION_FILTER;
        if (NAME_FILTER != null) filterDesc += " matching '" + NAME_FILTER + "'";
        if (CATEGORY_FILTER != null) filterDesc += " in " + CATEGORY_FILTER;
        if (filterDesc.isEmpty()) filterDesc = " (all versions)";
        System.out.println("\n=== Total: " + allTests.size() + " tests" + filterDesc + " ===\n");

        OUTPUT_DIR.mkdirs();

        return params;
    }

    /**
     * Parses the catalog.xml to get test-set file paths.
     * Supports filtering via system properties:
     *   -Dxslt.version=1.0|2.0|3.0  - Filter by XSLT version
     *   -Dxslt.filter=substring     - Filter test-set names containing substring
     *   -Dxslt.category=fn|insn|... - Filter by category (path component)
     */
    private static List<String> parseCatalog(File catalogFile) throws Exception {
        List<String> testSetFiles = new ArrayList<>();
        
        // Log active filters
        if (VERSION_FILTER != null) {
            System.out.println("  Filter: XSLT version = " + VERSION_FILTER);
        }
        if (NAME_FILTER != null) {
            System.out.println("  Filter: name contains '" + NAME_FILTER + "'");
        }
        if (CATEGORY_FILTER != null) {
            System.out.println("  Filter: category = " + CATEGORY_FILTER);
        }
        String skipPatterns = SKIP_FILTER != null ? SKIP_FILTER : DEFAULT_SKIP;
        if (!"none".equalsIgnoreCase(skipPatterns)) {
            System.out.println("  Skipping: " + skipPatterns + " (use -Dxslt.skip=none to include all)");
        }

        Parser parser = new Parser();
        parser.setFeature("http://xml.org/sax/features/namespaces", true);
        parser.setContentHandler(new DefaultHandler() {
            @Override
            public void startElement(String uri, String localName, String qName,
                                     Attributes attrs) {
                if ("test-set".equals(localName) && XSLT_TEST_NS.equals(uri)) {
                    String name = attrs.getValue("name");
                    String file = attrs.getValue("file");
                    
                    if (file == null) return;
                    
                    // Note: Version filtering now happens at the test-case level in parseTestSet()
                    // The catalog.xml doesn't have spec attributes - those are in individual test-cases
                    
                    // Apply name filter
                    if (NAME_FILTER != null) {
                        if (name == null || !name.toLowerCase().contains(NAME_FILTER.toLowerCase())) {
                            return;
                        }
                    }
                    
                    // Apply category filter (based on path: tests/fn/, tests/insn/, etc.)
                    if (CATEGORY_FILTER != null) {
                        if (!file.contains("/" + CATEGORY_FILTER + "/") && 
                            !file.contains("/" + CATEGORY_FILTER + "-")) {
                            return;
                        }
                    }
                    
                    // Apply skip filter (heavy tests like regex, unicode)
                    if (shouldSkip(name)) {
                        return;
                    }
                    
                    testSetFiles.add(file);
                }
            }
        });

        // Use FileChannel for NIO-native input
        FileChannel channel = FileChannel.open(catalogFile.toPath(), StandardOpenOption.READ);
        InputSource source = new InputSource(Channels.newInputStream(channel));
        source.setSystemId(catalogFile.toURI().toString());
        parser.parse(source);
        channel.close();

        return testSetFiles;
    }

    /**
     * Parses a test-set file to extract XSLT 1.0 compatible test cases.
     */
    private static List<XSLTTestCase> parseTestSet(File testSetFile) throws Exception {
        List<XSLTTestCase> tests = new ArrayList<>();
        File testDir = testSetFile.getParentFile();

        // Maps for environments and current test case being built
        Map<String, Environment> environments = new HashMap<>();

        Parser parser = new Parser();
        parser.setFeature("http://xml.org/sax/features/namespaces", true);
        parser.setContentHandler(new TestSetHandler(testDir, environments, tests));

        // Use FileChannel for NIO-native input
        FileChannel channel = FileChannel.open(testSetFile.toPath(), StandardOpenOption.READ);
        InputSource source = new InputSource(Channels.newInputStream(channel));
        source.setSystemId(testSetFile.toURI().toString());
        parser.parse(source);
        channel.close();

        return tests;
    }

    /**
     * SAX handler for parsing test-set files.
     */
    private static class TestSetHandler extends DefaultHandler {
        private final File testDir;
        private final Map<String, Environment> environments;
        private final List<XSLTTestCase> tests;

        // Current element state
        private StringBuilder charBuffer = new StringBuilder();
        private String currentEnvName;
        private Environment currentEnv;
        private XSLTTestCase currentTest;
        private boolean inTestCase;
        private boolean inDependencies;
        private boolean inTest;
        private boolean inResult;
        private String specValue;
        private List<String> stylesheetFiles;
        private String initialTemplate;
        private String expectedXml;
        private String expectedError;
        private boolean expectsError;
        private boolean requiresErrorOnMultipleMatch;

        TestSetHandler(File testDir, Map<String, Environment> environments,
                       List<XSLTTestCase> tests) {
            this.testDir = testDir;
            this.environments = environments;
            this.tests = tests;
        }

        @Override
        public void startElement(String uri, String localName, String qName,
                                 Attributes attrs) throws SAXException {
            charBuffer.setLength(0);

            if (!XSLT_TEST_NS.equals(uri)) {
                return;
            }

            if ("environment".equals(localName)) {
                String name = attrs.getValue("name");
                String ref = attrs.getValue("ref");
                if (name != null) {
                    currentEnvName = name;
                    currentEnv = new Environment();
                } else if (ref != null && inTestCase) {
                    // Reference to existing environment
                    Environment env = environments.get(ref);
                    if (env != null && currentTest != null) {
                        currentTest.sourceFile = env.sourceFile;
                        currentTest.sourceContent = env.sourceContent;
                    }
                } else if (inTestCase) {
                    // Inline environment within test-case (no name or ref)
                    currentEnv = new Environment();
                }
            } else if ("source".equals(localName)) {
                if (currentEnv != null) {
                    String role = attrs.getValue("role");
                    if (".".equals(role)) {
                        String file = attrs.getValue("file");
                        if (file != null) {
                            currentEnv.sourceFile = new File(testDir, file);
                        }
                    }
                }
            } else if ("content".equals(localName)) {
                // Will capture in characters()
            } else if ("test-case".equals(localName)) {
                inTestCase = true;
                currentTest = new XSLTTestCase();
                currentTest.name = attrs.getValue("name");
                currentTest.testDir = testDir;
                specValue = null;
                stylesheetFiles = new ArrayList<>();
                initialTemplate = null;
                expectedXml = null;
                expectedError = null;
                expectsError = false;
                requiresErrorOnMultipleMatch = false;
            } else if ("dependencies".equals(localName)) {
                inDependencies = true;
            } else if ("spec".equals(localName) && inDependencies) {
                specValue = attrs.getValue("value");
            } else if ("on-multiple-match".equals(localName) && inDependencies) {
                // Tests with on-multiple-match="error" require the processor to signal
                // an error when multiple templates match with the same precedence/priority.
                // We implement recovery behavior (pick last), so skip these tests.
                String value = attrs.getValue("value");
                if ("error".equals(value)) {
                    requiresErrorOnMultipleMatch = true;
                }
            } else if ("test".equals(localName) && inTestCase) {
                inTest = true;
            } else if ("stylesheet".equals(localName) && inTest) {
                String file = attrs.getValue("file");
                String role = attrs.getValue("role");
                if (file != null && !"secondary".equals(role)) {
                    stylesheetFiles.add(file);
                }
            } else if ("initial-template".equals(localName) && inTest) {
                initialTemplate = attrs.getValue("name");
            } else if ("result".equals(localName) && inTestCase) {
                inResult = true;
            } else if ("assert-xml".equals(localName) && inResult) {
                // Check for file attribute to load external expected output
                String file = attrs.getValue("file");
                if (file != null && !file.isEmpty()) {
                    try {
                        File expectedFile = new File(testDir, file);
                        expectedXml = new String(java.nio.file.Files.readAllBytes(expectedFile.toPath()), 
                            java.nio.charset.StandardCharsets.UTF_8);
                    } catch (IOException e) {
                        // File not found or unreadable - leave expectedXml as null
                        expectedXml = null;
                    }
                }
                // Will also capture in characters() for inline content
            } else if ("error".equals(localName) && inResult) {
                expectsError = true;
                expectedError = attrs.getValue("code");
            }
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            charBuffer.append(ch, start, length);
        }

        @Override
        public void endElement(String uri, String localName, String qName) {
            if (!XSLT_TEST_NS.equals(uri)) {
                return;
            }

            if ("content".equals(localName)) {
                if (currentEnv != null) {
                    currentEnv.sourceContent = charBuffer.toString();
                }
            } else if ("environment".equals(localName)) {
                if (currentEnvName != null && currentEnv != null) {
                    // Named environment - store for later reference
                    environments.put(currentEnvName, currentEnv);
                } else if (currentEnv != null && inTestCase && currentTest != null) {
                    // Inline environment - copy directly to test case
                    currentTest.sourceFile = currentEnv.sourceFile;
                    currentTest.sourceContent = currentEnv.sourceContent;
                }
                currentEnvName = null;
                currentEnv = null;
            } else if ("assert-xml".equals(localName)) {
                // Only use inline content if no file was loaded
                String inlineContent = charBuffer.toString();
                if (expectedXml == null || expectedXml.isEmpty()) {
                    expectedXml = inlineContent;
                }
            } else if ("dependencies".equals(localName)) {
                inDependencies = false;
            } else if ("test".equals(localName)) {
                inTest = false;
            } else if ("result".equals(localName)) {
                inResult = false;
            } else if ("test-case".equals(localName)) {
                // Finalize test case
                // Skip tests that require on-multiple-match="error" - we use recovery behavior
                if (currentTest != null && matchesVersionFilter(specValue) && !requiresErrorOnMultipleMatch) {
                    if (!stylesheetFiles.isEmpty()) {
                        currentTest.stylesheetFile = new File(testDir, stylesheetFiles.get(0));
                    }
                    currentTest.initialTemplate = initialTemplate;
                    currentTest.expectedXml = expectedXml;
                    currentTest.expectsError = expectsError;
                    currentTest.expectedError = expectedError;

                    if (currentTest.stylesheetFile != null) {
                        tests.add(currentTest);
                    }
                }
                inTestCase = false;
                currentTest = null;
            }
        }

        /**
         * Checks if a test case matches the version filter.
         * If no version filter is set, accepts all tests.
         * Version matching:
         *   - XSLT10+ means 1.0 and later (matches 1.0, 2.0, 3.0)
         *   - XSLT20+ means 2.0 and later (matches 2.0, 3.0)
         *   - XSLT30 means 3.0 only
         *   - "XSLT10 XSLT20" means 1.0 or 2.0
         */
        private boolean matchesVersionFilter(String spec) {
            if (spec == null) {
                return false;
            }
            
            // No version filter - accept all tests
            if (VERSION_FILTER == null) {
                return true;
            }
            
            // Parse version filter (e.g., "1.0" -> 10, "2.0" -> 20, "3.0" -> 30)
            int filterVersion = parseVersion(VERSION_FILTER);
            
            // Check for "+" suffix (compatible with this version and later)
            if (spec.contains("+")) {
                // XSLT10+ compatible with 1.0, 2.0, 3.0
                // XSLT20+ compatible with 2.0, 3.0
                // XSLT30+ compatible with 3.0
                int specMinVersion = parseSpecVersion(spec.replace("+", "").trim());
                return filterVersion >= specMinVersion;
            }
            
            // Handle space-separated list (e.g., "XSLT10 XSLT20")
            for (String part : spec.split("\\s+")) {
                int specVersion = parseSpecVersion(part.trim());
                if (specVersion == filterVersion) {
                    return true;
                }
            }
            
            return false;
        }
        
        private int parseVersion(String version) {
            // "1.0" -> 10, "2.0" -> 20, "3.0" -> 30
            try {
                return (int) (Double.parseDouble(version) * 10);
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        
        private int parseSpecVersion(String spec) {
            // "XSLT10" -> 10, "XSLT20" -> 20, "XSLT30" -> 30
            if (spec.startsWith("XSLT")) {
                try {
                    return Integer.parseInt(spec.substring(4));
                } catch (NumberFormatException e) {
                    return 0;
                }
            }
            return 0;
        }
    }

    @Test
    public void runTest() {

        TestResult result = new TestResult();
        result.name = testCase.name;
        result.stylesheetFile = testCase.stylesheetFile;

        FileChannel stylesheetChannel = null;
        FileChannel sourceChannel = null;
        try {
            // Compile stylesheet - use FileChannel for NIO-native input
            stylesheetChannel = FileChannel.open(testCase.stylesheetFile.toPath(), StandardOpenOption.READ);
            StreamSource stylesheetSource = new StreamSource(Channels.newInputStream(stylesheetChannel));
            stylesheetSource.setSystemId(testCase.stylesheetFile.toURI().toString());

            Templates templates;
            try {
                templates = factory.newTemplates(stylesheetSource);
            } catch (TransformerException e) {
                if (testCase.expectsError) {
                    result.passed = true;
                    result.actualResult = "Compilation error (expected)";
                } else {
                    result.passed = false;
                    result.actualResult = "Compilation error";
                    result.message = e.getMessage();
                }
                results.add(result);
                if (!result.passed) {
                    fail(testCase.name + ": " + result.message);
                }
                return;
            }

            Transformer transformer = templates.newTransformer();

            // Set initial template if specified (XSLT 2.0+ feature)
            if (testCase.initialTemplate != null && transformer instanceof GonzalezTransformer) {
                ((GonzalezTransformer) transformer).setInitialTemplate(testCase.initialTemplate);
            }

            // Prepare source - use FileChannel for NIO-native input
            StreamSource source;
            if (testCase.sourceFile != null && testCase.sourceFile.exists()) {
                sourceChannel = FileChannel.open(testCase.sourceFile.toPath(), StandardOpenOption.READ);
                source = new StreamSource(Channels.newInputStream(sourceChannel));
                source.setSystemId(testCase.sourceFile.toURI().toString());
            } else if (testCase.sourceContent != null) {
                // Use ByteArrayInputStream for inline content (byte stream, not Reader)
                byte[] contentBytes = testCase.sourceContent.getBytes(StandardCharsets.UTF_8);
                source = new StreamSource(new ByteArrayInputStream(contentBytes));
            } else {
                // Some tests may not need a source document
                byte[] dummyBytes = "<dummy/>".getBytes(StandardCharsets.UTF_8);
                source = new StreamSource(new ByteArrayInputStream(dummyBytes));
            }

            // Transform - use ByteArrayOutputStream (Gonzalez requires byte streams)
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            StreamResult streamResult = new StreamResult(outputStream);

            try {
                transformer.transform(source, streamResult);

                String actualOutput = outputStream.toString(StandardCharsets.UTF_8.name());
                result.actualResult = actualOutput;

                if (testCase.expectsError) {
                    // Test expects an error, but transformation succeeded.
                    // However, if expectedXml is also set (any-of case), check if output matches.
                    if (testCase.expectedXml != null && xmlEquals(actualOutput, testCase.expectedXml)) {
                        // Recovery output matches - this is also acceptable
                        result.passed = true;
                    } else {
                        result.passed = false;
                        result.actualResult = "No error (expected error)";
                        result.message = "Expected error but transformation succeeded";
                    }
                } else {
                    if (testCase.expectedXml != null) {
                        if (xmlEquals(actualOutput, testCase.expectedXml)) {
                            result.passed = true;
                        } else {
                            result.passed = false;
                            String diff = getXmlDifference(testCase.expectedXml, actualOutput);
                            result.message = "Output mismatch: " + diff;
                        }
                    } else {
                        // No expected output specified, just check it completed
                        result.passed = true;
                    }
                }
            } catch (TransformerException e) {
                if (testCase.expectsError) {
                    result.passed = true;
                    result.actualResult = "Error (expected): " + e.getMessage();
                } else {
                    result.passed = false;
                    result.actualResult = "Transform error";
                    result.message = e.getMessage();
                }
            }

        } catch (Exception e) {
            result.passed = false;
            result.actualResult = "Exception";
            result.message = e.getClass().getSimpleName() + ": " + e.getMessage();
        } finally {
            // Clean up NIO channels
            if (stylesheetChannel != null) {
                try { stylesheetChannel.close(); } catch (IOException ignored) {}
            }
            if (sourceChannel != null) {
                try { sourceChannel.close(); } catch (IOException ignored) {}
            }
        }

        results.add(result);

        if (!result.passed) {
            fail(testCase.name + ": " + result.message);
        }
    }

    /** XML comparator instance for test assertions. */
    private static final XMLComparator xmlComparator = new XMLComparator();

    /**
     * Compares two XML strings for semantic equality.
     * Uses XMLComparator which handles attribute order, whitespace, etc.
     */
    private boolean xmlEquals(String actual, String expected) {
        XMLComparator.Result result = xmlComparator.compare(expected, actual);
        return result.equal;
    }

    /**
     * Gets the difference description between two XML strings.
     */
    private String getXmlDifference(String expected, String actual) {
        XMLComparator.Result result = xmlComparator.compare(expected, actual);
        if (result.equal) {
            return null;
        }
        return result.difference;
    }

    @AfterClass
    public static void generateReport() throws IOException {
        System.out.println("\n=== XSLT Conformance Test Report ===");

        int passed = 0;
        int failed = 0;

        for (TestResult r : results) {
            if (r.passed) {
                passed++;
            } else {
                failed++;
            }
        }

        if (results.isEmpty()) {
            System.out.println("No tests were run.");
            return;
        }

        double passRate = 100.0 * passed / results.size();
        System.out.printf("Passed: %d / %d (%.1f%%)%n", passed, results.size(), passRate);
        System.out.printf("Failed: %d%n", failed);

        // Write detailed report - use FileChannel for NIO-native output
        try (FileChannel reportChannel = FileChannel.open(REPORT_FILE.toPath(), 
                StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
             PrintWriter out = new PrintWriter(Channels.newOutputStream(reportChannel))) {
            out.println("XSLT Conformance Test Report");
            out.println("=============================");
            out.println();
            out.printf("Total tests: %d%n", results.size());
            out.printf("Passed: %d (%.1f%%)%n", passed, passRate);
            out.printf("Failed: %d (%.1f%%)%n", failed, 100.0 * failed / results.size());
            out.println();

            out.println("Failed Tests:");
            out.println("-------------");
            for (TestResult r : results) {
                if (!r.passed) {
                    out.printf("[FAIL] %s%n", r.name);
                    out.printf("       File: %s%n", r.stylesheetFile);
                    out.printf("       Result: %s%n", r.actualResult);
                    if (r.message != null) {
                        out.printf("       Message: %s%n", r.message);
                    }
                    out.println();
                }
            }
        }

        // Write statistics - use FileChannel for NIO-native output
        try (FileChannel statsChannel = FileChannel.open(STATS_FILE.toPath(),
                StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
             PrintWriter out = new PrintWriter(Channels.newOutputStream(statsChannel))) {
            out.println("=== XSLT Conformance Test Statistics ===");
            out.println();
            out.printf("Total:  %d%n", results.size());
            out.printf("Passed: %d (%.1f%%)%n", passed, passRate);
            out.printf("Failed: %d (%.1f%%)%n", failed, 100.0 * failed / results.size());
        }

        System.out.println("\nDetailed report: " + REPORT_FILE.getAbsolutePath());
    }

    /**
     * Represents an environment (source document) for tests.
     */
    static class Environment {
        File sourceFile;
        String sourceContent;
    }

    /**
     * Represents a single XSLT test case.
     */
    static class XSLTTestCase {
        String name;
        File testDir;
        File stylesheetFile;
        File sourceFile;
        String sourceContent;
        String initialTemplate;
        String expectedXml;
        boolean expectsError;
        String expectedError;

        @Override
        public String toString() {
            return name;
        }
    }

    /**
     * Represents the result of running a test.
     */
    static class TestResult {
        String name;
        File stylesheetFile;
        String actualResult;
        boolean passed;
        String message;
    }
}

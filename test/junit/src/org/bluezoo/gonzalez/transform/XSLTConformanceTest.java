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
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
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

import static org.junit.Assert.*;

/**
 * W3C XSLT Conformance Test Suite runner.
 *
 * <p>Runs XSLT 1.0 compatible tests extracted from the W3C XSLT 3.0 test suite.
 * Tests are filtered to include only those with:
 * <ul>
 *   <li>{@code <spec value="XSLT10+"/>} - Compatible with XSLT 1.0 and later</li>
 *   <li>{@code <spec value="XSLT10"/>} - XSLT 1.0 only</li>
 *   <li>{@code <spec value="XSLT10 XSLT20"/>} - XSLT 1.0 and 2.0</li>
 * </ul>
 *
 * <p>The test suite must be extracted first using:
 * <pre>
 * ./tools/extract-xslt10-tests.sh /path/to/xslt30-test
 * </pre>
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
@RunWith(Parameterized.class)
public class XSLTConformanceTest {

    private static final File XSLTCONF_DIR = new File("xsltconf");
    private static final File OUTPUT_DIR = new File("test/output");
    private static final File REPORT_FILE = new File(OUTPUT_DIR, "xslt-conformance-report.txt");
    private static final File STATS_FILE = new File(OUTPUT_DIR, "xslt-conformance-statistics.txt");

    private static final String XSLT_TEST_NS = "http://www.w3.org/2012/10/xslt-test-catalog";

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

        File catalogFile = new File(XSLTCONF_DIR, "catalog.xml");
        if (!catalogFile.exists()) {
            System.err.println("XSLT conformance test suite not found at: " + XSLTCONF_DIR);
            System.err.println("Run: ./tools/extract-xslt10-tests.sh /path/to/xslt30-test");
            return new ArrayList<>();
        }

        System.out.println("Loading XSLT conformance tests from: " + XSLTCONF_DIR);

        // Parse catalog to get test-set references
        List<String> testSetFiles = parseCatalog(catalogFile);
        System.out.println("Found " + testSetFiles.size() + " test sets");

        // Parse each test set
        for (String testSetPath : testSetFiles) {
            File testSetFile = new File(XSLTCONF_DIR, testSetPath);
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

        System.out.println("\n=== Total: " + allTests.size() + " XSLT 1.0 tests ===\n");

        OUTPUT_DIR.mkdirs();

        return params;
    }

    /**
     * Parses the catalog.xml to get test-set file paths.
     */
    private static List<String> parseCatalog(File catalogFile) throws Exception {
        List<String> testSetFiles = new ArrayList<>();

        Parser parser = new Parser();
        parser.setFeature("http://xml.org/sax/features/namespaces", true);
        parser.setContentHandler(new DefaultHandler() {
            @Override
            public void startElement(String uri, String localName, String qName,
                                     Attributes attrs) {
                if ("test-set".equals(localName) && XSLT_TEST_NS.equals(uri)) {
                    String file = attrs.getValue("file");
                    if (file != null) {
                        testSetFiles.add(file);
                    }
                }
            }
        });

        InputSource source = new InputSource(new FileInputStream(catalogFile));
        source.setSystemId(catalogFile.toURI().toString());
        parser.parse(source);

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

        InputSource source = new InputSource(new FileInputStream(testSetFile));
        source.setSystemId(testSetFile.toURI().toString());
        parser.parse(source);

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
            } else if ("dependencies".equals(localName)) {
                inDependencies = true;
            } else if ("spec".equals(localName) && inDependencies) {
                specValue = attrs.getValue("value");
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
                // Will capture in characters()
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
                    environments.put(currentEnvName, currentEnv);
                }
                currentEnvName = null;
                currentEnv = null;
            } else if ("assert-xml".equals(localName)) {
                expectedXml = charBuffer.toString();
            } else if ("dependencies".equals(localName)) {
                inDependencies = false;
            } else if ("test".equals(localName)) {
                inTest = false;
            } else if ("result".equals(localName)) {
                inResult = false;
            } else if ("test-case".equals(localName)) {
                // Finalize test case
                if (currentTest != null && isXslt10Compatible(specValue)) {
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

        private boolean isXslt10Compatible(String spec) {
            if (spec == null) {
                return false;
            }
            return spec.equals("XSLT10+") ||
                   spec.equals("XSLT10") ||
                   spec.equals("XSLT10 XSLT20");
        }
    }

    @Test
    public void runTest() {
        System.out.println("[TEST] " + testCase.name + " - " + testCase.stylesheetFile.getName());

        TestResult result = new TestResult();
        result.name = testCase.name;
        result.stylesheetFile = testCase.stylesheetFile;

        try {
            // Compile stylesheet
            StreamSource stylesheetSource = new StreamSource(
                new FileInputStream(testCase.stylesheetFile));
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

            // Prepare source
            StreamSource source;
            if (testCase.sourceFile != null && testCase.sourceFile.exists()) {
                source = new StreamSource(new FileInputStream(testCase.sourceFile));
                source.setSystemId(testCase.sourceFile.toURI().toString());
            } else if (testCase.sourceContent != null) {
                // Use ByteArrayInputStream since Gonzalez parser requires byte streams
                byte[] contentBytes = testCase.sourceContent.getBytes(StandardCharsets.UTF_8);
                source = new StreamSource(new ByteArrayInputStream(contentBytes));
            } else {
                // Some tests may not need a source document
                byte[] dummyBytes = "<dummy/>".getBytes(StandardCharsets.UTF_8);
                source = new StreamSource(new ByteArrayInputStream(dummyBytes));
            }

            // Transform
            StringWriter outputWriter = new StringWriter();
            StreamResult streamResult = new StreamResult(outputWriter);

            try {
                transformer.transform(source, streamResult);

                if (testCase.expectsError) {
                    result.passed = false;
                    result.actualResult = "No error (expected error)";
                    result.message = "Expected error but transformation succeeded";
                } else {
                    String actualOutput = outputWriter.toString();
                    result.actualResult = actualOutput;

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

        // Write detailed report
        try (PrintWriter out = new PrintWriter(new FileWriter(REPORT_FILE))) {
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

        // Write statistics
        try (PrintWriter out = new PrintWriter(new FileWriter(STATS_FILE))) {
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

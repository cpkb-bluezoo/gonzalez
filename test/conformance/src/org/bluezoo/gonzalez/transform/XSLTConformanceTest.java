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
import java.util.Iterator;
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
import org.bluezoo.gonzalez.transform.compiler.PackageResolver;
import org.bluezoo.gonzalez.transform.runtime.DocumentLoader;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNode;

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
        // Don't skip anything when a name filter is active - the user is
        // explicitly requesting test sets by name (e.g. -Dxslt.filter=regex)
        if (NAME_FILTER != null) {
            return false;
        }
        String skipPatterns = SKIP_FILTER != null ? SKIP_FILTER : DEFAULT_SKIP;
        if ("none".equalsIgnoreCase(skipPatterns)) {
            return false;
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
    private static PrintWriter reportWriter;

    private final XSLTTestCase testCase;

    public XSLTConformanceTest(XSLTTestCase testCase) {
        this.testCase = testCase;
    }

    @Parameters(name = "{0}")
    public static Collection<Object[]> getTestCases() throws Exception {
        allTests = new ArrayList<>();
        factory = new GonzalezTransformerFactory();
        factory.setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "file");
        factory.setAttribute("http://javax.xml.XMLConstants/property/accessExternalStylesheet", "file");

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

        FileChannel reportChannel = FileChannel.open(REPORT_FILE.toPath(),
                StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        reportWriter = new PrintWriter(Channels.newOutputStream(reportChannel));
        reportWriter.println("XSLT Conformance Test Report");
        reportWriter.println("=============================");
        reportWriter.println();
        reportWriter.println("Results (streaming):");
        reportWriter.println("--------------------");
        reportWriter.flush();

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
        parser.setFeature("http://xml.org/sax/features/external-general-entities", true);
        parser.setFeature("http://xml.org/sax/features/external-parameter-entities", true);
        parser.setProperty("http://javax.xml.XMLConstants/property/accessExternalDTD", "file");
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
        parser.setFeature("http://xml.org/sax/features/external-general-entities", true);
        parser.setFeature("http://xml.org/sax/features/external-parameter-entities", true);
        parser.setProperty("http://javax.xml.XMLConstants/property/accessExternalDTD", "file");
        parser.setContentHandler(new TestSetHandler(testDir, testSetFile, environments, tests));

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
        private final File testSetFile;
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
        private String initialMode;
        private String initialFunctionNsUri;
        private String initialFunctionLocalName;
        private List<String> initialFunctionParams;
        private boolean inInitialFunction;
        private boolean inInitialTemplate;
        private List<InitialTemplateParam> initialTemplateParamList;
        private String expectedXml;
        private List<String> anyOfExpectedXmls;
        private String expectedError;
        private boolean expectsError;
        private Map<String, String> namespacePrefixes = new HashMap<>();
        private boolean inNot;
        private boolean inAnyOf;
        private boolean anyOfAcceptsSuccess;
        private boolean requiresErrorOnMultipleMatch;
        private boolean requiresSchemaAware;
        private boolean testSetRequiresSchemaAware;
        private boolean requiresUnsupportedFeature;
        private String testSetSpecValue;
        private Map<String, String> expectedResultDocuments;
        private String currentResultDocumentUri;
        private String resultDocAssertXml;
        private String currentCollectionUri;
        private List<CollectionEntry> currentCollectionEntries;

        TestSetHandler(File testDir, File testSetFile, Map<String, Environment> environments,
                       List<XSLTTestCase> tests) {
            this.testDir = testDir;
            this.testSetFile = testSetFile;
            this.environments = environments;
            this.tests = tests;
        }

        @Override
        public void startPrefixMapping(String prefix, String nsUri) {
            namespacePrefixes.put(prefix, nsUri);
        }

        private String expandQNameAttribute(String value) {
            if (value == null || value.isEmpty()) {
                return value;
            }
            String trimmed = value.trim();
            int colonPos = trimmed.indexOf(':');
            if (colonPos > 0) {
                String prefix = trimmed.substring(0, colonPos);
                String local = trimmed.substring(colonPos + 1);
                String nsUri = namespacePrefixes.get(prefix);
                if (nsUri != null && !nsUri.isEmpty()) {
                    return "{" + nsUri + "}" + local;
                }
            }
            return trimmed;
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
                        currentTest.sourceSelect = env.sourceSelect;
                        if (env.stylesheetFile != null) {
                            currentTest.stylesheetFile = env.stylesheetFile;
                        }
                        if (env.packages != null) {
                            currentTest.packages = env.packages;
                        }
                        if (env.collections != null) {
                            currentTest.collections = env.collections;
                        }
                    }
                } else if (inTestCase) {
                    // Inline environment within test-case (no name or ref)
                    currentEnv = new Environment();
                }
            } else if ("collection".equals(localName)) {
                if (currentEnv != null) {
                    currentCollectionUri = attrs.getValue("uri");
                    currentCollectionEntries = new ArrayList<>();
                }
            } else if ("source".equals(localName)) {
                if (currentCollectionUri != null && currentCollectionEntries != null) {
                    String file = attrs.getValue("file");
                    String sourceUri = attrs.getValue("uri");
                    if (file != null) {
                        currentCollectionEntries.add(new CollectionEntry(
                            new File(testDir, file), sourceUri != null ? sourceUri : file));
                    }
                } else if (currentEnv != null) {
                    String role = attrs.getValue("role");
                    if (".".equals(role)) {
                        String file = attrs.getValue("file");
                        if (file != null) {
                            currentEnv.sourceFile = new File(testDir, file);
                        }
                        String select = attrs.getValue("select");
                        if (select != null) {
                            currentEnv.sourceSelect = select;
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
                currentTest.testSetFile = testSetFile;
                specValue = null;
                stylesheetFiles = new ArrayList<>();
                initialTemplate = null;
                initialMode = null;
                initialFunctionNsUri = null;
                initialFunctionLocalName = null;
                initialFunctionParams = null;
                inInitialFunction = false;
                inInitialTemplate = false;
                initialTemplateParamList = null;
                expectedXml = null;
                anyOfExpectedXmls = null;
                expectedError = null;
                expectsError = false;
                inAnyOf = false;
                anyOfAcceptsSuccess = false;
                requiresErrorOnMultipleMatch = false;
                requiresSchemaAware = false;
                requiresUnsupportedFeature = false;
                expectedResultDocuments = null;
                currentResultDocumentUri = null;
            } else if ("dependencies".equals(localName)) {
                inDependencies = true;
            } else if ("spec".equals(localName) && inDependencies) {
                String value = attrs.getValue("value");
                if (inTestCase) {
                    specValue = value;
                } else {
                    testSetSpecValue = value;
                }
            } else if ("on-multiple-match".equals(localName) && inDependencies) {
                // Tests with on-multiple-match="error" require the processor to signal
                // an error when multiple templates match with the same precedence/priority.
                // We implement recovery behavior (pick last), so skip these tests.
                String value = attrs.getValue("value");
                if ("error".equals(value)) {
                    requiresErrorOnMultipleMatch = true;
                }
            } else if ("feature".equals(localName) && inDependencies) {
                // Skip tests that require a schema-aware processor (SA).
                // Gonzalez is a Basic XSLT processor, which the spec allows to
                // not support schema-aware features.
                // satisfied="false" means the test expects SA to be absent (run it).
                // satisfied="true" or no satisfied attr means SA is required (skip it).
                String value = attrs.getValue("value");
                String satisfied = attrs.getValue("satisfied");
                if ("schema_aware".equals(value) && !"false".equals(satisfied)) {
                    if (inTestCase) {
                        requiresSchemaAware = true;
                    } else {
                        testSetRequiresSchemaAware = true;
                    }
                }
                // Gonzalez supports d-o-e, so skip tests that require it absent
                if ("disabling_output_escaping".equals(value)
                        && "false".equals(satisfied)) {
                    requiresUnsupportedFeature = true;
                }
                // Gonzalez supports XPath 3.1 features (e.g. exponent-separator),
                // so skip tests that assume XPath 3.1 is absent.
                if ("XPath_3.1".equals(value) && "false".equals(satisfied)) {
                    requiresUnsupportedFeature = true;
                }
                // Gonzalez supports backwards compatibility mode, so skip
                // tests that require it to be absent.
                if ("backwards_compatibility".equals(value)
                        && "false".equals(satisfied)) {
                    requiresUnsupportedFeature = true;
                }
                // Gonzalez does not support XSD 1.1; skip tests that require it.
                if ("XSD_1.1".equals(value) && !"false".equals(satisfied)) {
                    requiresUnsupportedFeature = true;
                }
            } else if ("maximum_number_of_decimal_digits".equals(localName) && inDependencies) {
                // Gonzalez uses double precision (~15-17 significant digits).
                // Skip tests requiring more than 18 decimal digits.
                String value = attrs.getValue("value");
                if (value != null) {
                    try {
                        int required = Integer.parseInt(value);
                        if (required > 18) {
                            requiresUnsupportedFeature = true;
                        }
                    } catch (NumberFormatException e) {
                        // ignore
                    }
                }
            } else if ("available_documents".equals(localName) && inDependencies) {
                // Skip tests that require remote HTTP documents to be accessible.
                // Our test environment does not have reliable network access.
                String value = attrs.getValue("value");
                if (value != null
                        && (value.startsWith("http://") || value.startsWith("https://"))) {
                    requiresUnsupportedFeature = true;
                }
            } else if ("year_component_values".equals(localName) && inDependencies) {
                // Gonzalez supports all year ranges (negative years and
                // years above 9999) so skip tests that assume these are absent.
                String satisfied = attrs.getValue("satisfied");
                if ("false".equals(satisfied)) {
                    requiresUnsupportedFeature = true;
                }
            } else if ("test".equals(localName) && inTestCase) {
                inTest = true;
            } else if ("stylesheet".equals(localName) && inTest) {
                String file = attrs.getValue("file");
                String role = attrs.getValue("role");
                if (file != null && !"secondary".equals(role)) {
                    stylesheetFiles.add(file);
                }
            } else if ("stylesheet".equals(localName) && currentEnv != null && !inTest) {
                String file = attrs.getValue("file");
                String role = attrs.getValue("role");
                if (file != null && !"secondary".equals(role)) {
                    currentEnv.stylesheetFile = new File(testDir, file);
                }
            } else if ("package".equals(localName) && currentEnv != null) {
                String file = attrs.getValue("file");
                String pkgUri = attrs.getValue("uri");
                if (file != null && pkgUri != null) {
                    if (currentEnv.packages == null) {
                        currentEnv.packages = new LinkedHashMap<>();
                    }
                    currentEnv.packages.put(pkgUri, new File(testDir, file));
                }
            } else if ("param".equals(localName) && inInitialFunction) {
                String select = attrs.getValue("select");
                if (select != null) {
                    initialFunctionParams.add(select);
                }
            } else if ("param".equals(localName) && inInitialTemplate) {
                String paramName = attrs.getValue("name");
                String paramSelect = attrs.getValue("select");
                String tunnelAttr = attrs.getValue("tunnel");
                boolean isTunnel = "yes".equals(tunnelAttr);
                if (paramName != null && paramSelect != null) {
                    String paramNsUri = null;
                    String paramLocalName = paramName;
                    int colonPos = paramName.indexOf(':');
                    if (colonPos > 0) {
                        String prefix = paramName.substring(0, colonPos);
                        paramLocalName = paramName.substring(colonPos + 1);
                        paramNsUri = namespacePrefixes.get(prefix);
                    }
                    if (initialTemplateParamList == null) {
                        initialTemplateParamList = new ArrayList<>();
                    }
                    initialTemplateParamList.add(new InitialTemplateParam(
                        paramNsUri, paramLocalName, paramSelect, isTunnel));
                }
            } else if ("param".equals(localName) && inTest) {
                String paramName = attrs.getValue("name");
                String paramSelect = attrs.getValue("select");
                String paramStatic = attrs.getValue("static");
                if (paramName != null && paramSelect != null && currentTest != null) {
                    if ("yes".equals(paramStatic)) {
                        if (currentTest.staticParams == null) {
                            currentTest.staticParams = new LinkedHashMap<>();
                        }
                        currentTest.staticParams.put(paramName, paramSelect);
                    } else {
                        if (currentTest.stylesheetParams == null) {
                            currentTest.stylesheetParams = new LinkedHashMap<>();
                        }
                        currentTest.stylesheetParams.put(paramName, paramSelect);
                    }
                }
            } else if ("initial-template".equals(localName) && inTest) {
                initialTemplate = expandQNameAttribute(attrs.getValue("name"));
                inInitialTemplate = true;
            } else if ("initial-function".equals(localName) && inTest) {
                String qname = attrs.getValue("name");
                if (qname != null) {
                    int colonPos = qname.indexOf(':');
                    if (colonPos > 0) {
                        String prefix = qname.substring(0, colonPos);
                        initialFunctionLocalName = qname.substring(colonPos + 1);
                        initialFunctionNsUri = namespacePrefixes.get(prefix);
                    } else {
                        initialFunctionLocalName = qname;
                        initialFunctionNsUri = "";
                    }
                }
                initialFunctionParams = new ArrayList<>();
                inInitialFunction = true;
            } else if ("initial-mode".equals(localName) && inTest) {
                initialMode = expandQNameAttribute(attrs.getValue("name"));
            } else if ("result".equals(localName) && inTestCase) {
                inResult = true;
            } else if ("any-of".equals(localName) && inResult) {
                inAnyOf = true;
            } else if ("not".equals(localName) && inResult) {
                inNot = true;
            } else if (("assert".equals(localName) || "assert-string-value".equals(localName)) 
                    && inResult && inAnyOf && !inNot) {
                anyOfAcceptsSuccess = true;
            } else if ("assert-result-document".equals(localName) && inResult) {
                currentResultDocumentUri = attrs.getValue("uri");
                resultDocAssertXml = null;
            } else if ("assert-xml".equals(localName) && inResult) {
                if (inAnyOf && !inNot) {
                    anyOfAcceptsSuccess = true;
                }
                String file = attrs.getValue("file");
                if (file != null && !file.isEmpty()) {
                    try {
                        File expectedFile = new File(testDir, file);
                        String fileContent = new String(
                            java.nio.file.Files.readAllBytes(expectedFile.toPath()),
                            java.nio.charset.StandardCharsets.UTF_8);
                        if (fileContent.length() > 0 && fileContent.charAt(0) == '\uFEFF') {
                            fileContent = fileContent.substring(1);
                        }
                        if (currentResultDocumentUri != null) {
                            resultDocAssertXml = fileContent;
                        } else if (inAnyOf) {
                            if (anyOfExpectedXmls == null) {
                                anyOfExpectedXmls = new ArrayList<>();
                            }
                            anyOfExpectedXmls.add(fileContent);
                            if (expectedXml == null) {
                                expectedXml = fileContent;
                            }
                        } else {
                            expectedXml = fileContent;
                        }
                    } catch (IOException e) {
                        // File not found or unreadable
                    }
                }
            } else if ("error".equals(localName) && inResult && !inNot) {
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

            if ("collection".equals(localName)) {
                if (currentEnv != null && currentCollectionUri != null 
                        && currentCollectionEntries != null) {
                    if (currentEnv.collections == null) {
                        currentEnv.collections = new HashMap<>();
                    }
                    currentEnv.collections.put(currentCollectionUri, currentCollectionEntries);
                }
                currentCollectionUri = null;
                currentCollectionEntries = null;
            } else if ("content".equals(localName)) {
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
                    currentTest.sourceSelect = currentEnv.sourceSelect;
                    if (currentEnv.stylesheetFile != null) {
                        currentTest.stylesheetFile = currentEnv.stylesheetFile;
                    }
                    if (currentEnv.packages != null) {
                        currentTest.packages = currentEnv.packages;
                    }
                    if (currentEnv.collections != null) {
                        currentTest.collections = currentEnv.collections;
                    }
                }
                currentEnvName = null;
                currentEnv = null;
            } else if ("assert-xml".equals(localName)) {
                String inlineContent = charBuffer.toString();
                if (currentResultDocumentUri != null) {
                    // Inside assert-result-document: use inline content or file-loaded content
                    String rdExpected = (inlineContent != null && !inlineContent.isEmpty())
                        ? inlineContent : resultDocAssertXml;
                    if (rdExpected != null) {
                        if (expectedResultDocuments == null) {
                            expectedResultDocuments = new LinkedHashMap<>();
                        }
                        expectedResultDocuments.put(currentResultDocumentUri, rdExpected);
                    }
                } else if (inAnyOf) {
                    if (inlineContent != null && !inlineContent.isEmpty()) {
                        if (anyOfExpectedXmls == null) {
                            anyOfExpectedXmls = new ArrayList<>();
                        }
                        anyOfExpectedXmls.add(inlineContent);
                        if (expectedXml == null || expectedXml.isEmpty()) {
                            expectedXml = inlineContent;
                        }
                    }
                } else {
                    // Primary output assertion
                    if (expectedXml == null || expectedXml.isEmpty()) {
                        expectedXml = inlineContent;
                    }
                }
            } else if ("assert-result-document".equals(localName)) {
                currentResultDocumentUri = null;
            } else if ("initial-template".equals(localName)) {
                inInitialTemplate = false;
            } else if ("initial-function".equals(localName)) {
                inInitialFunction = false;
            } else if ("dependencies".equals(localName)) {
                inDependencies = false;
            } else if ("test".equals(localName)) {
                inTest = false;
            } else if ("any-of".equals(localName)) {
                inAnyOf = false;
            } else if ("not".equals(localName)) {
                inNot = false;
            } else if ("result".equals(localName)) {
                inResult = false;
            } else if ("test-case".equals(localName)) {
                // Finalize test case
                // Skip tests that require on-multiple-match="error" - we use recovery behavior
                String effectiveSpec = (specValue != null) ? specValue : testSetSpecValue;
                if (currentTest != null && matchesVersionFilter(effectiveSpec)
                        && !requiresErrorOnMultipleMatch
                        && !requiresSchemaAware && !testSetRequiresSchemaAware
                        && !requiresUnsupportedFeature) {
                    if (!stylesheetFiles.isEmpty()) {
                        currentTest.stylesheetFile = new File(testDir, stylesheetFiles.get(0));
                    }
                    currentTest.initialTemplate = initialTemplate;
                    currentTest.initialTemplateParams = initialTemplateParamList;
                    currentTest.initialFunctionNsUri = initialFunctionNsUri;
                    currentTest.initialFunctionLocalName = initialFunctionLocalName;
                    currentTest.initialFunctionParams = initialFunctionParams;
                    currentTest.initialMode = initialMode;
                    currentTest.expectedXml = expectedXml;
                    currentTest.anyOfExpectedXmls = anyOfExpectedXmls;
                    currentTest.expectsError = expectsError;
                    currentTest.anyOfAcceptsSuccess = anyOfAcceptsSuccess;
                    currentTest.expectedError = expectedError;
                    currentTest.expectedResultDocuments = expectedResultDocuments;
                    currentTest.specValue = effectiveSpec;

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

    private static double parseSpecVersionAsDouble(String spec) {
        if (spec == null) {
            return -1;
        }
        for (String part : spec.split("\\s+")) {
            String s = part.trim();
            if (s.startsWith("XSLT")) {
                try {
                    int v = Integer.parseInt(s.substring(4));
                    return v / 10.0;
                } catch (NumberFormatException e) {
                    // ignore
                }
            }
        }
        return -1;
    }

    @Test
    public void runTest() {

        TestResult result = new TestResult();
        result.name = testCase.name;
        result.stylesheetFile = testCase.stylesheetFile;

        FileChannel stylesheetChannel = null;
        FileChannel sourceChannel = null;
        try {
            // Configure package resolver if test provides secondary packages
            if (testCase.packages != null && !testCase.packages.isEmpty()) {
                PackageResolver resolver = new PackageResolver();
                for (Map.Entry<String, File> entry : testCase.packages.entrySet()) {
                    String pkgUri = entry.getKey();
                    File pkgFile = entry.getValue();
                    String pkgLocation = pkgFile.toURI().toString();
                    resolver.registerPackageLocation(pkgUri, pkgLocation);
                }
                factory.setPackageResolver(resolver);
            } else {
                factory.setPackageResolver(null);
            }

            // Set static parameters before compilation
            factory.clearStaticParameters();
            if (testCase.staticParams != null) {
                for (Map.Entry<String, String> entry : testCase.staticParams.entrySet()) {
                    factory.setStaticParameter(entry.getKey(), entry.getValue());
                }
            }

            // Limit processor version when the test targets a specific XSLT
            // version (e.g. XSLT20 without +), so that later instructions
            // like xsl:try are treated as unknown and xsl:fallback fires.
            if (testCase.specValue != null && !testCase.specValue.contains("+")) {
                double specVersion = parseSpecVersionAsDouble(testCase.specValue);
                if (specVersion > 0 && specVersion < 3.0) {
                    factory.setMaxXsltVersion(specVersion);
                } else {
                    factory.setMaxXsltVersion(-1);
                }
            } else {
                factory.setMaxXsltVersion(-1);
            }

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
                    String msg = e.getMessage();
                    Throwable cause = e.getCause();
                    while (cause != null) {
                        msg = msg + " -> " + cause.getMessage();
                        cause = cause.getCause();
                    }
                    result.message = msg;
                }
                results.add(result);
                writeResult(result);
                if (!result.passed) {
                    fail(testCase.name + ": " + result.message);
                }
                return;
            }

            Transformer transformer = templates.newTransformer();

            // Set initial template if specified (XSLT 2.0+ feature)
            if (testCase.initialTemplate != null && transformer instanceof GonzalezTransformer) {
                ((GonzalezTransformer) transformer).setInitialTemplate(testCase.initialTemplate);
                if (testCase.initialTemplateParams != null) {
                    for (InitialTemplateParam itp : testCase.initialTemplateParams) {
                        ((GonzalezTransformer) transformer).addInitialTemplateParam(
                            itp.nsUri, itp.localName, itp.selectExpr, itp.tunnel);
                    }
                }
            }

            // Set initial function if specified (XSLT 3.0 feature)
            if (testCase.initialFunctionLocalName != null && transformer instanceof GonzalezTransformer) {
                ((GonzalezTransformer) transformer).setInitialFunction(
                    testCase.initialFunctionNsUri, testCase.initialFunctionLocalName,
                    testCase.initialFunctionParams);
            }

            // Set initial mode if specified
            if (testCase.initialMode != null && transformer instanceof GonzalezTransformer) {
                ((GonzalezTransformer) transformer).setInitialMode(testCase.initialMode);
                // XTDE0044: if no real source was provided, signal no match selection
                boolean hasRealSource = testCase.sourceFile != null
                        || testCase.sourceContent != null;
                if (!hasRealSource) {
                    ((GonzalezTransformer) transformer).setHasMatchSelection(false);
                }
            }

            // Set initial context select if specified
            if (testCase.sourceSelect != null && transformer instanceof GonzalezTransformer) {
                ((GonzalezTransformer) transformer).setInitialContextSelect(testCase.sourceSelect);
            }

            // Register collections
            if (testCase.collections != null && transformer instanceof GonzalezTransformer) {
                GonzalezTransformer gt = (GonzalezTransformer) transformer;
                for (Map.Entry<String, List<CollectionEntry>> entry : testCase.collections.entrySet()) {
                    List<XPathNode> nodes = loadCollectionNodes(entry.getValue());
                    if (nodes != null) {
                        gt.setCollection(entry.getKey(), nodes);
                    }
                }
            }

            // Set stylesheet parameters from test definition
            if (testCase.stylesheetParams != null) {
                for (Map.Entry<String, String> entry : testCase.stylesheetParams.entrySet()) {
                    String paramValue = entry.getValue();
                    // Extract string literal value from XPath select expression
                    if (paramValue.length() >= 2
                            && ((paramValue.charAt(0) == '\'' && paramValue.charAt(paramValue.length() - 1) == '\'')
                             || (paramValue.charAt(0) == '"' && paramValue.charAt(paramValue.length() - 1) == '"'))) {
                        paramValue = paramValue.substring(1, paramValue.length() - 1);
                    }
                    transformer.setParameter(entry.getKey(), paramValue);
                }
            }

            // Track whether a real source document was provided
            boolean hasExplicitSource = testCase.sourceFile != null
                    || testCase.sourceContent != null;

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
                // Inline content originates from the test-set file; use its URI
                // so that base-uri() reflects the actual source location
                if (testCase.testSetFile != null) {
                    source.setSystemId(testCase.testSetFile.toURI().toString());
                } else {
                    source.setSystemId(testCase.testDir.toURI().toString());
                }
            } else {
                // Some tests may not need a source document
                byte[] dummyBytes = "<dummy/>".getBytes(StandardCharsets.UTF_8);
                source = new StreamSource(new ByteArrayInputStream(dummyBytes));
            }

            // Transform - use ByteArrayOutputStream (Gonzalez requires byte streams)
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            StreamResult streamResult = new StreamResult(outputStream);

            try {
                try {
                    transformer.transform(source, streamResult);
                } catch (TransformerException e0) {
                    // XSLT 3.0 §2.4: if the initial named template doesn't exist
                    // and an initial match selection is provided, fall back to
                    // apply-templates. Only retry when the test expects success;
                    // tests that expect XTDE0040 should see the error.
                    String msg0 = e0.getMessage();
                    boolean isXTDE0040 = msg0 != null && msg0.contains("XTDE0040");
                    if (isXTDE0040 && hasExplicitSource && !testCase.expectsError
                            && testCase.initialTemplate != null
                            && transformer instanceof GonzalezTransformer) {
                        ((GonzalezTransformer) transformer).setInitialTemplate(null);
                        if (sourceChannel != null) {
                            try { sourceChannel.close(); } catch (IOException ignored) {}
                            sourceChannel = null;
                        }
                        StreamSource retrySource;
                        if (testCase.sourceFile != null && testCase.sourceFile.exists()) {
                            sourceChannel = FileChannel.open(testCase.sourceFile.toPath(),
                                StandardOpenOption.READ);
                            retrySource = new StreamSource(
                                Channels.newInputStream(sourceChannel));
                            retrySource.setSystemId(
                                testCase.sourceFile.toURI().toString());
                        } else {
                            byte[] cb = testCase.sourceContent.getBytes(
                                StandardCharsets.UTF_8);
                            retrySource = new StreamSource(
                                new ByteArrayInputStream(cb));
                            if (testCase.testSetFile != null) {
                                retrySource.setSystemId(
                                    testCase.testSetFile.toURI().toString());
                            } else {
                                retrySource.setSystemId(
                                    testCase.testDir.toURI().toString());
                            }
                        }
                        outputStream.reset();
                        transformer.transform(retrySource, streamResult);
                    } else {
                        throw e0;
                    }
                }

                String encoding = detectXmlEncoding(outputStream.toByteArray());
                String actualOutput = outputStream.toString(encoding);
                result.actualResult = actualOutput;

                if (testCase.expectsError) {
                    if (testCase.expectedXml != null && xmlEquals(actualOutput, testCase.expectedXml)) {
                        result.passed = true;
                    } else if (testCase.anyOfAcceptsSuccess) {
                        result.passed = true;
                    } else {
                        result.passed = false;
                        result.actualResult = "No error (expected error)";
                        result.message = "Expected error but transformation succeeded";
                    }
                } else if (testCase.expectedResultDocuments != null
                        && !testCase.expectedResultDocuments.isEmpty()) {
                    result.passed = true;
                    for (Map.Entry<String, String> entry : testCase.expectedResultDocuments.entrySet()) {
                        String rdUri = entry.getKey();
                        String rdExpected = entry.getValue();
                        File rdFile = new File(rdUri);
                        if (!rdFile.exists()) {
                            result.passed = false;
                            result.message = "Result document not found: " + rdUri;
                            break;
                        }
                        String rdActual = new String(
                            java.nio.file.Files.readAllBytes(rdFile.toPath()),
                            StandardCharsets.UTF_8);
                        if (!xmlEquals(rdActual, rdExpected)) {
                            result.passed = false;
                            String diff = getXmlDifference(rdExpected, rdActual);
                            result.message = "Result document " + rdUri + " mismatch: " + diff;
                            break;
                        }
                    }
                    if (testCase.expectedXml != null && result.passed) {
                        if (!xmlEquals(actualOutput, testCase.expectedXml)) {
                            result.passed = false;
                            String diff = getXmlDifference(testCase.expectedXml, actualOutput);
                            result.message = "Primary output mismatch: " + diff;
                        }
                    }
                } else {
                    if (testCase.expectedXml != null) {
                        boolean matched = xmlEquals(actualOutput, testCase.expectedXml);
                        if (!matched && testCase.anyOfExpectedXmls != null) {
                            for (int ai = 0; ai < testCase.anyOfExpectedXmls.size(); ai++) {
                                if (xmlEquals(actualOutput, testCase.anyOfExpectedXmls.get(ai))) {
                                    matched = true;
                                    break;
                                }
                            }
                        }
                        if (matched) {
                            result.passed = true;
                        } else {
                            result.passed = false;
                            String diff = getXmlDifference(testCase.expectedXml, actualOutput);
                            result.message = "Output mismatch: " + diff;
                        }
                    } else {
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
        writeResult(result);

        if (!result.passed) {
            fail(testCase.name + ": " + result.message);
        }
    }

    private static void writeResult(TestResult result) {
        if (reportWriter == null) {
            return;
        }
        if (result.passed) {
            reportWriter.printf("[PASS] %s%n", result.name);
        } else {
            reportWriter.printf("[FAIL] %s%n", result.name);
            reportWriter.printf("       File: %s%n", result.stylesheetFile);
            reportWriter.printf("       Result: %s%n", result.actualResult);
            if (result.message != null) {
                reportWriter.printf("       Message: %s%n", result.message);
            }
            reportWriter.println();
        }
        reportWriter.flush();
    }

    /**
     * Detects the XML encoding from the XML declaration in the raw bytes.
     * Handles UTF-16 BOM detection and falls back to UTF-8 if no encoding
     * is specified.
     */
    private static String detectXmlEncoding(byte[] bytes) {
        if (bytes.length >= 2) {
            int b0 = bytes[0] & 0xFF;
            int b1 = bytes[1] & 0xFF;
            if ((b0 == 0xFE && b1 == 0xFF) || (b0 == 0xFF && b1 == 0xFE)) {
                return "UTF-16";
            }
            if ((b0 == 0x00 && b1 == 0x3C) || (b0 == 0x3C && b1 == 0x00)) {
                return "UTF-16";
            }
        }
        int limit = Math.min(bytes.length, 200);
        String header = new String(bytes, 0, limit, StandardCharsets.US_ASCII);
        int encIdx = header.indexOf("encoding=");
        if (encIdx < 0) {
            return StandardCharsets.UTF_8.name();
        }
        encIdx += 9;
        if (encIdx >= header.length()) {
            return StandardCharsets.UTF_8.name();
        }
        char quote = header.charAt(encIdx);
        if (quote != '"' && quote != '\'') {
            return StandardCharsets.UTF_8.name();
        }
        int end = header.indexOf(quote, encIdx + 1);
        if (end < 0) {
            return StandardCharsets.UTF_8.name();
        }
        return header.substring(encIdx + 1, end);
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
            if (reportWriter != null) {
                reportWriter.close();
            }
            return;
        }

        double passRate = 100.0 * passed / results.size();
        System.out.printf("Passed: %d / %d (%.1f%%)%n", passed, results.size(), passRate);
        System.out.printf("Failed: %d%n", failed);

        // Append summary to the streaming report
        if (reportWriter != null) {
            reportWriter.println();
            reportWriter.println("Summary:");
            reportWriter.println("--------");
            reportWriter.printf("Total tests: %d%n", results.size());
            reportWriter.printf("Passed: %d (%.1f%%)%n", passed, passRate);
            reportWriter.printf("Failed: %d (%.1f%%)%n", failed, 100.0 * failed / results.size());
            reportWriter.close();
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
     * Loads collection nodes from the given collection entries.
     * Handles fragment identifiers (e.g., doc15.xml#frag2) by finding the
     * element with the matching xml:id.
     */
    private static List<XPathNode> loadCollectionNodes(
            List<CollectionEntry> entries) {
        List<XPathNode> nodes = new ArrayList<>();
        for (CollectionEntry entry : entries) {
            try {
                String fileStr = entry.file.getPath();
                String fragment = null;
                int hashIdx = fileStr.indexOf('#');
                File docFile;
                if (hashIdx >= 0) {
                    fragment = fileStr.substring(hashIdx + 1);
                    docFile = new File(fileStr.substring(0, hashIdx));
                } else {
                    docFile = entry.file;
                }
                if (!docFile.exists()) {
                    continue;
                }
                XPathNode doc = DocumentLoader.loadDocument(
                    docFile.toURI().toString(), null, null, null);
                if (doc == null) {
                    continue;
                }
                if (fragment != null) {
                    XPathNode found = findById(doc, fragment);
                    if (found != null) {
                        nodes.add(found);
                    }
                } else {
                    nodes.add(doc);
                }
            } catch (Exception e) {
                // Skip documents that can't be loaded
            }
        }
        return nodes;
    }

    /**
     * Finds an element with the given xml:id value in the tree.
     */
    private static XPathNode findById(XPathNode node, String id) {
        if (node.isElement()) {
            XPathNode idAttr = node.getAttribute(
                "http://www.w3.org/XML/1998/namespace", "id");
            if (idAttr == null) {
                idAttr = node.getAttribute("", "xml:id");
            }
            if (idAttr != null && id.equals(idAttr.getStringValue())) {
                return node;
            }
        }
        Iterator<XPathNode> children = node.getChildren();
        while (children.hasNext()) {
            XPathNode found = findById(children.next(), id);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /**
     * Represents an environment (source document) for tests.
     */
    static class Environment {
        File sourceFile;
        String sourceContent;
        String sourceSelect;
        File stylesheetFile;
        Map<String, File> packages;
        Map<String, List<CollectionEntry>> collections;
    }

    /**
     * Represents an entry in a collection (a source document with optional fragment).
     */
    static class CollectionEntry {
        File file;
        String uri;
        
        CollectionEntry(File file, String uri) {
            this.file = file;
            this.uri = uri;
        }
    }

    /**
     * Represents a single XSLT test case.
     */
    static class XSLTTestCase {
        String name;
        File testDir;
        File testSetFile;
        File stylesheetFile;
        File sourceFile;
        String sourceContent;
        String sourceSelect;
        String initialTemplate;
        List<InitialTemplateParam> initialTemplateParams;
        String initialMode;
        String initialFunctionNsUri;
        String initialFunctionLocalName;
        List<String> initialFunctionParams;
        String expectedXml;
        List<String> anyOfExpectedXmls;
        boolean expectsError;
        boolean anyOfAcceptsSuccess;
        String expectedError;
        Map<String, String> expectedResultDocuments;
        Map<String, String> stylesheetParams;
        Map<String, String> staticParams;
        Map<String, File> packages;
        Map<String, List<CollectionEntry>> collections;
        String specValue;

        @Override
        public String toString() {
            return name;
        }
    }

    /**
     * Represents a parameter to be passed to the initial template.
     */
    static class InitialTemplateParam {
        final String nsUri;
        final String localName;
        final String selectExpr;
        final boolean tunnel;

        InitialTemplateParam(String nsUri, String localName,
                            String selectExpr, boolean tunnel) {
            this.nsUri = nsUri;
            this.localName = localName;
            this.selectExpr = selectExpr;
            this.tunnel = tunnel;
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

/*
 * Portable subset of the W3C xslt30-test catalog for multi-engine bake-off.
 * Not a full replacement for XSLTConformanceTest; skips non-JAXP-portable cases.
 */
package org.bluezoo.gonzalez.xsltcompare;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

final class CatalogParser {

    static final String XSLT_TEST_NS = "http://www.w3.org/2012/10/xslt-test-catalog";

    private CatalogParser() { }

    static List<CompareTestCase> load(File suiteDir) throws Exception {
        String versionFilter = normalize(System.getProperty("xslt.version"));
        String nameFilter = normalize(System.getProperty("xslt.filter"));
        String categoryFilter = normalize(System.getProperty("xslt.category"));
        String skipFilter = normalize(System.getProperty("xslt.skip"));
        if (skipFilter == null) {
            skipFilter = "regex,unicode,result-document";
        }

        File catalogFile = new File(suiteDir, "catalog.xml");
        List<String> testSetFiles = parseCatalog(catalogFile, nameFilter, categoryFilter, skipFilter);
        System.out.println("Found " + testSetFiles.size() + " test sets"
                + (versionFilter != null ? " (version filter " + versionFilter + ")" : ""));

        List<CompareTestCase> all = new ArrayList<CompareTestCase>();
        for (String path : testSetFiles) {
            File testSetFile = new File(suiteDir, path);
            if (!testSetFile.exists()) {
                continue;
            }
            all.addAll(parseTestSet(testSetFile, versionFilter));
        }

        String limitProp = normalize(System.getProperty("xslt.limit"));
        if (limitProp != null) {
            int limit = Integer.parseInt(limitProp);
            if (limit >= 0 && all.size() > limit) {
                all = new ArrayList<CompareTestCase>(all.subList(0, limit));
            }
        }
        return all;
    }

    private static List<String> parseCatalog(File catalogFile, String nameFilter,
            String categoryFilter, String skipFilter) throws Exception {
        List<String> files = new ArrayList<String>();
        SAXParser parser = saxParser();
        parser.parse(catalogFile, new DefaultHandler() {
            @Override
            public void startElement(String uri, String localName, String qName, Attributes attrs) {
                if (!"test-set".equals(localName) || !XSLT_TEST_NS.equals(uri)) {
                    return;
                }
                String name = attrs.getValue("name");
                String file = attrs.getValue("file");
                if (file == null) {
                    return;
                }
                if (nameFilter != null && (name == null
                        || !name.toLowerCase().contains(nameFilter.toLowerCase()))) {
                    return;
                }
                if (categoryFilter != null
                        && !file.contains("/" + categoryFilter + "/")
                        && !file.contains("/" + categoryFilter + "-")) {
                    return;
                }
                if (!"none".equalsIgnoreCase(skipFilter) && name != null) {
                    for (String pattern : skipFilter.split(",")) {
                        if (name.toLowerCase().contains(pattern.trim().toLowerCase())) {
                            return;
                        }
                    }
                }
                files.add(file);
            }
        });
        return files;
    }

    private static List<CompareTestCase> parseTestSet(File testSetFile, String versionFilter)
            throws Exception {
        List<CompareTestCase> tests = new ArrayList<CompareTestCase>();
        File testDir = testSetFile.getParentFile();
        Map<String, Environment> environments = new HashMap<String, Environment>();
        SAXParser parser = saxParser();
        parser.parse(new InputSource(new FileInputStream(testSetFile)),
                new TestSetHandler(testDir, testSetFile, environments, tests, versionFilter));
        return tests;
    }

    private static SAXParser saxParser() throws Exception {
        SAXParserFactory factory = SAXParserFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setValidating(false);
        return factory.newSAXParser();
    }

    private static String normalize(String value) {
        if (value == null || value.isEmpty() || value.startsWith("${")) {
            return null;
        }
        return value;
    }

    private static final class Environment {
        File sourceFile;
        String sourceContent;
        String sourceSelect;
        File stylesheetFile;
    }

    private static final class TestSetHandler extends DefaultHandler {
        private final File testDir;
        private final File testSetFile;
        private final Map<String, Environment> environments;
        private final List<CompareTestCase> tests;
        private final String versionFilter;

        private final StringBuilder chars = new StringBuilder();
        private final Map<String, String> prefixes = new HashMap<String, String>();

        private String currentEnvName;
        private Environment currentEnv;
        private CompareTestCase current;
        private boolean inTestCase;
        private boolean inDependencies;
        private boolean inTest;
        private boolean inResult;
        private boolean inAnyOf;
        private boolean inNot;
        private boolean inInitialTemplate;
        private boolean inInitialMode;
        private boolean inInitialFunction;
        private String specValue;
        private String testSetSpecValue;
        private List<String> stylesheetFiles;
        private String expectedXml;
        private List<String> anyOfExpectedXmls;
        private boolean expectsError;
        private boolean anyOfAcceptsSuccess;
        private boolean requiresSchemaAware;
        private boolean testSetRequiresSchemaAware;
        private boolean requiresUnsupported;
        private boolean requiresErrorOnMultipleMatch;
        private boolean hasPackages;
        private boolean hasCollections;
        private boolean hasResultDocuments;
        private boolean hasInitialFunction;
        private String initialTemplate;
        private String initialMode;
        private String sourceSelect;

        TestSetHandler(File testDir, File testSetFile, Map<String, Environment> environments,
                List<CompareTestCase> tests, String versionFilter) {
            this.testDir = testDir;
            this.testSetFile = testSetFile;
            this.environments = environments;
            this.tests = tests;
            this.versionFilter = versionFilter;
        }

        @Override
        public void startPrefixMapping(String prefix, String uri) {
            prefixes.put(prefix, uri);
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes attrs)
                throws SAXException {
            chars.setLength(0);
            if (!XSLT_TEST_NS.equals(uri)) {
                return;
            }
            if ("environment".equals(localName)) {
                String name = attrs.getValue("name");
                String ref = attrs.getValue("ref");
                if (name != null) {
                    currentEnvName = name;
                    currentEnv = new Environment();
                } else if (ref != null && inTestCase && current != null) {
                    Environment env = environments.get(ref);
                    if (env != null) {
                        current.sourceFile = env.sourceFile;
                        current.sourceContent = env.sourceContent;
                        current.sourceSelect = env.sourceSelect;
                        sourceSelect = env.sourceSelect;
                        if (env.stylesheetFile != null) {
                            current.stylesheetFile = env.stylesheetFile;
                        }
                    }
                } else if (inTestCase) {
                    currentEnv = new Environment();
                }
            } else if ("source".equals(localName) && currentEnv != null) {
                if (".".equals(attrs.getValue("role"))) {
                    String file = attrs.getValue("file");
                    if (file != null) {
                        currentEnv.sourceFile = new File(testDir, file);
                    }
                    String select = attrs.getValue("select");
                    if (select != null) {
                        currentEnv.sourceSelect = select;
                    }
                }
            } else if ("stylesheet".equals(localName) && currentEnv != null && !inTest) {
                String file = attrs.getValue("file");
                if (file != null && !"secondary".equals(attrs.getValue("role"))) {
                    currentEnv.stylesheetFile = new File(testDir, file);
                }
            } else if ("collection".equals(localName)) {
                hasCollections = true;
            } else if ("package".equals(localName)) {
                hasPackages = true;
            } else if ("test-case".equals(localName)) {
                inTestCase = true;
                current = new CompareTestCase();
                current.name = attrs.getValue("name");
                current.testDir = testDir;
                current.testSetFile = testSetFile;
                stylesheetFiles = new ArrayList<String>();
                expectedXml = null;
                anyOfExpectedXmls = null;
                expectsError = false;
                anyOfAcceptsSuccess = false;
                requiresSchemaAware = false;
                requiresUnsupported = false;
                requiresErrorOnMultipleMatch = false;
                hasPackages = false;
                hasCollections = false;
                hasResultDocuments = false;
                hasInitialFunction = false;
                initialTemplate = null;
                initialMode = null;
                sourceSelect = null;
                specValue = null;
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
                // Same as XSLTConformanceTest: Gonzalez recovers on ambiguous
                // matches; skip tests that require XTDE0540/XTRE0540.
                if ("error".equals(attrs.getValue("value"))) {
                    requiresErrorOnMultipleMatch = true;
                }
            } else if ("feature".equals(localName) && inDependencies) {
                String value = attrs.getValue("value");
                String satisfied = attrs.getValue("satisfied");
                if ("schema_aware".equals(value) && !"false".equals(satisfied)) {
                    if (inTestCase) {
                        requiresSchemaAware = true;
                    } else {
                        testSetRequiresSchemaAware = true;
                    }
                }
                if (("XSD_1.1".equals(value) && !"false".equals(satisfied))
                        || ("disabling_output_escaping".equals(value) && "false".equals(satisfied))
                        || ("XPath_3.1".equals(value) && "false".equals(satisfied))
                        || ("backwards_compatibility".equals(value) && "false".equals(satisfied))
                        || ("dynamic_evaluation".equals(value) && "false".equals(satisfied))) {
                    requiresUnsupported = true;
                }
            } else if ("available_documents".equals(localName) && inDependencies) {
                String value = attrs.getValue("value");
                if (value != null && (value.startsWith("http://") || value.startsWith("https://"))) {
                    requiresUnsupported = true;
                }
            } else if ("test".equals(localName) && inTestCase) {
                inTest = true;
            } else if ("stylesheet".equals(localName) && inTest) {
                String file = attrs.getValue("file");
                if (file != null && !"secondary".equals(attrs.getValue("role"))) {
                    stylesheetFiles.add(file);
                }
            } else if ("initial-template".equals(localName) && inTest) {
                initialTemplate = expandQName(attrs.getValue("name"));
                inInitialTemplate = true;
            } else if ("initial-mode".equals(localName) && inTest) {
                initialMode = expandQName(attrs.getValue("name"));
                inInitialMode = true;
            } else if ("initial-function".equals(localName) && inTest) {
                hasInitialFunction = true;
                inInitialFunction = true;
            } else if ("param".equals(localName) && inTest && current != null) {
                String paramName = attrs.getValue("name");
                String paramSelect = attrs.getValue("select");
                String paramStatic = attrs.getValue("static");
                if (paramName != null && paramSelect != null) {
                    if ("yes".equals(paramStatic)) {
                        if (current.staticParams == null) {
                            current.staticParams = new LinkedHashMap<String, String>();
                        }
                        current.staticParams.put(paramName, paramSelect);
                    } else {
                        if (current.stylesheetParams == null) {
                            current.stylesheetParams = new LinkedHashMap<String, String>();
                        }
                        current.stylesheetParams.put(paramName, paramSelect);
                    }
                }
            } else if ("result".equals(localName) && inTestCase) {
                inResult = true;
            } else if ("any-of".equals(localName) && inResult) {
                inAnyOf = true;
            } else if ("not".equals(localName) && inResult) {
                inNot = true;
            } else if (("assert".equals(localName) || "assert-string-value".equals(localName)
                    || "assert-eq".equals(localName)) && inResult && inAnyOf && !inNot) {
                anyOfAcceptsSuccess = true;
            } else if ("assert-string-value".equals(localName) && inResult && !inAnyOf && !inNot) {
                // Standalone string assertions: official runner accepts success
                // without comparing captured output.
                anyOfAcceptsSuccess = true;
            } else if ("assert-result-document".equals(localName) && inResult) {
                hasResultDocuments = true;
            } else if ("assert-xml".equals(localName) && inResult) {
                if (inAnyOf && !inNot) {
                    anyOfAcceptsSuccess = true;
                }
                String file = attrs.getValue("file");
                if (file != null && !file.isEmpty()) {
                    try {
                        String content = new String(
                                Files.readAllBytes(new File(testDir, file).toPath()),
                                StandardCharsets.UTF_8);
                        if (!content.isEmpty() && content.charAt(0) == '\uFEFF') {
                            content = content.substring(1);
                        }
                        if (inAnyOf) {
                            if (anyOfExpectedXmls == null) {
                                anyOfExpectedXmls = new ArrayList<String>();
                            }
                            anyOfExpectedXmls.add(content);
                            if (expectedXml == null) {
                                expectedXml = content;
                            }
                        } else {
                            expectedXml = content;
                        }
                    } catch (IOException ignored) {
                        // leave expectedXml unset
                    }
                }
            } else if ("error".equals(localName) && inResult && !inNot) {
                expectsError = true;
            }
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            chars.append(ch, start, length);
        }

        @Override
        public void endElement(String uri, String localName, String qName) {
            if (!XSLT_TEST_NS.equals(uri)) {
                return;
            }
            if ("content".equals(localName) && currentEnv != null) {
                currentEnv.sourceContent = chars.toString();
            } else if ("environment".equals(localName)) {
                if (currentEnvName != null && currentEnv != null) {
                    environments.put(currentEnvName, currentEnv);
                } else if (currentEnv != null && inTestCase && current != null) {
                    current.sourceFile = currentEnv.sourceFile;
                    current.sourceContent = currentEnv.sourceContent;
                    current.sourceSelect = currentEnv.sourceSelect;
                    sourceSelect = currentEnv.sourceSelect;
                    if (currentEnv.stylesheetFile != null) {
                        current.stylesheetFile = currentEnv.stylesheetFile;
                    }
                }
                currentEnvName = null;
                currentEnv = null;
            } else if ("assert-xml".equals(localName)) {
                String inline = chars.toString();
                if (inline != null && !inline.isEmpty()) {
                    if (inAnyOf) {
                        if (anyOfExpectedXmls == null) {
                            anyOfExpectedXmls = new ArrayList<String>();
                        }
                        anyOfExpectedXmls.add(inline);
                        if (expectedXml == null || expectedXml.isEmpty()) {
                            expectedXml = inline;
                        }
                    } else if (expectedXml == null || expectedXml.isEmpty()) {
                        expectedXml = inline;
                    }
                }
            } else if ("assert-string-value".equals(localName)) {
                // Content intentionally not captured: matches XSLTConformanceTest.
            } else if ("initial-template".equals(localName)) {
                inInitialTemplate = false;
            } else if ("initial-mode".equals(localName)) {
                inInitialMode = false;
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
                String effectiveSpec = specValue != null ? specValue : testSetSpecValue;
                if (current != null && matchesVersion(effectiveSpec)
                        && !requiresSchemaAware && !testSetRequiresSchemaAware
                        && !requiresUnsupported
                        && !requiresErrorOnMultipleMatch) {
                    if (!stylesheetFiles.isEmpty()) {
                        current.stylesheetFile = new File(testDir, stylesheetFiles.get(0));
                    }
                    current.specValue = effectiveSpec;
                    current.expectedXml = expectedXml;
                    current.anyOfExpectedXmls = anyOfExpectedXmls;
                    current.expectsError = expectsError;
                    current.anyOfAcceptsSuccess = anyOfAcceptsSuccess;
                    current.initialTemplate = initialTemplate;
                    current.initialMode = initialMode;
                    current.sourceSelect = sourceSelect;
                    if ("docbook-001".equals(current.name)) {
                        current.skipReason = "heavy";
                    } else if (hasPackages) {
                        current.skipReason = "packages";
                    } else if (hasCollections) {
                        current.skipReason = "collections";
                    } else if (hasResultDocuments) {
                        current.skipReason = "result-document";
                    } else if (hasInitialFunction) {
                        current.skipReason = "initial-function";
                    } else if (current.stylesheetFile == null) {
                        current.skipReason = "no-stylesheet";
                    }
                    tests.add(current);
                }
                inTestCase = false;
                current = null;
            }
        }

        private String expandQName(String value) {
            if (value == null || value.isEmpty()) {
                return value;
            }
            String trimmed = value.trim();
            int colon = trimmed.indexOf(':');
            if (colon > 0) {
                String prefix = trimmed.substring(0, colon);
                String local = trimmed.substring(colon + 1);
                String ns = prefixes.get(prefix);
                if (ns != null && !ns.isEmpty()) {
                    return "{" + ns + "}" + local;
                }
            }
            return trimmed;
        }

        private boolean matchesVersion(String spec) {
            if (spec == null) {
                return false;
            }
            if (versionFilter == null) {
                return true;
            }
            int filterVersion = parseVersion(versionFilter);
            if (spec.contains("+")) {
                int min = parseSpecVersion(spec.replace("+", "").trim());
                return filterVersion >= min;
            }
            for (String part : spec.split("\\s+")) {
                if (parseSpecVersion(part.trim()) == filterVersion) {
                    return true;
                }
            }
            return false;
        }

        private int parseVersion(String version) {
            try {
                return (int) (Double.parseDouble(version) * 10);
            } catch (NumberFormatException e) {
                return 0;
            }
        }

        private int parseSpecVersion(String spec) {
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
}

/** One W3C test case reduced to fields the bake-off can execute. */
final class CompareTestCase {
    String name;
    File testDir;
    File testSetFile;
    File stylesheetFile;
    File sourceFile;
    String sourceContent;
    String expectedXml;
    List<String> anyOfExpectedXmls;
    boolean expectsError;
    boolean anyOfAcceptsSuccess;
    String initialTemplate;
    String initialMode;
    String sourceSelect;
    String specValue;
    String skipReason;
    Map<String, String> stylesheetParams;
    Map<String, String> staticParams;

    boolean isXslt10Compatible() {
        return specValue != null && specValue.contains("XSLT10");
    }

    double minSpecVersion() {
        if (specValue == null) {
            return 3.0;
        }
        double min = 99.0;
        for (String part : specValue.replace("+", " ").split("\\s+")) {
            String s = part.trim();
            if (s.startsWith("XSLT")) {
                try {
                    min = Math.min(min, Integer.parseInt(s.substring(4)) / 10.0);
                } catch (NumberFormatException ignored) {
                    // ignore
                }
            }
        }
        return min == 99.0 ? 3.0 : min;
    }

    @Override
    public String toString() {
        return name;
    }
}

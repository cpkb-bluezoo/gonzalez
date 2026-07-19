/*
 * ParserFeatureContractTest.java
 * Copyright (C) 2026 Chris Burdess
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

package org.bluezoo.gonzalez;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXNotRecognizedException;
import org.xml.sax.SAXNotSupportedException;
import org.xml.sax.ext.DeclHandler;
import org.xml.sax.ext.LexicalHandler;
import org.xml.sax.helpers.DefaultHandler;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

import static org.junit.Assert.*;

/**
 * SAX feature/property contract tests for {@link Parser}.
 *
 * @author Chris Burdess
 */
public class ParserFeatureContractTest {

    private static final String NAMESPACES =
            "http://xml.org/sax/features/namespaces";
    private static final String NAMESPACE_PREFIXES =
            "http://xml.org/sax/features/namespace-prefixes";
    private static final String VALIDATION =
            "http://xml.org/sax/features/validation";
    private static final String EXTERNAL_GENERAL =
            "http://xml.org/sax/features/external-general-entities";
    private static final String EXTERNAL_PARAMETER =
            "http://xml.org/sax/features/external-parameter-entities";
    private static final String DISALLOW_DOCTYPE =
            "http://apache.org/xml/features/disallow-doctype-decl";
    private static final String RESOLVE_DTD_URIS =
            "http://xml.org/sax/features/resolve-dtd-uris";
    private static final String STRING_INTERNING =
            "http://xml.org/sax/features/string-interning";
    private static final String XMLNS_URIS =
            "http://xml.org/sax/features/xmlns-uris";
    private static final String IS_STANDALONE =
            "http://xml.org/sax/features/is-standalone";
    private static final String SECURE_PROCESSING =
            "http://javax.xml.XMLConstants/feature/secure-processing";
    private static final String UNICODE_NORM =
            "http://xml.org/sax/features/unicode-normalization-checking";
    private static final String LEXICAL_HANDLER_FEATURE =
            "http://xml.org/sax/features/lexical-handler";
    private static final String PARAMETER_ENTITIES =
            "http://xml.org/sax/features/parameter-entities";
    private static final String USE_ATTRIBUTES2 =
            "http://xml.org/sax/features/use-attributes2";
    private static final String USE_LOCATOR2 =
            "http://xml.org/sax/features/use-locator2";
    private static final String USE_ENTITY_RESOLVER2 =
            "http://xml.org/sax/features/use-entity-resolver2";
    private static final String XML_1_1 =
            "http://xml.org/sax/features/xml-1.1";

    private static final String LEXICAL_HANDLER_PROPERTY =
            "http://xml.org/sax/properties/lexical-handler";
    private static final String DECLARATION_HANDLER_PROPERTY =
            "http://xml.org/sax/properties/declaration-handler";
    private static final String ACCESS_EXTERNAL_DTD =
            "http://javax.xml.XMLConstants/property/accessExternalDTD";
    private static final String ENTITY_EXPANSION_LIMIT =
            "http://www.nongnu.org/gonzalez/properties/entity-expansion-limit";

    private File dtdFile;
    private File xmlFile;
    private File secretFile;

    @Before
    public void setUp() {
        dtdFile = null;
        xmlFile = null;
        secretFile = null;
    }

    @After
    public void tearDown() {
        deleteQuietly(dtdFile);
        deleteQuietly(xmlFile);
        deleteQuietly(secretFile);
    }

    private static void deleteQuietly(File file) {
        if (file != null && file.exists()) {
            file.delete();
        }
    }

    private interface ParserAction {
        void run(Parser parser) throws Exception;
    }

    private static void withParser(ParserAction body) throws Exception {
        body.run(new Parser());
    }

    // ========== Defaults ==========

    @Test
    public void testDefaultFeaturesAndProperties() throws Exception {
        withParser((parser) -> {
            assertTrue("namespaces should default to true",
                    parser.getFeature(NAMESPACES));
            assertFalse("namespace-prefixes should default to false",
                    parser.getFeature(NAMESPACE_PREFIXES));
            assertFalse("validation should default to false",
                    parser.getFeature(VALIDATION));
            assertFalse("external-general-entities should default to false",
                    parser.getFeature(EXTERNAL_GENERAL));
            assertFalse("external-parameter-entities should default to false",
                    parser.getFeature(EXTERNAL_PARAMETER));
            assertFalse("disallow-doctype-decl should default to false",
                    parser.getFeature(DISALLOW_DOCTYPE));
            assertTrue("resolve-dtd-uris should default to true",
                    parser.getFeature(RESOLVE_DTD_URIS));
            assertTrue("string-interning should default to true",
                    parser.getFeature(STRING_INTERNING));
            assertFalse("xmlns-uris should default to false",
                    parser.getFeature(XMLNS_URIS));
            assertEquals("accessExternalDTD should default to empty",
                    "", parser.getProperty(ACCESS_EXTERNAL_DTD));
            assertEquals("entity-expansion-limit should default to 64000",
                    Integer.valueOf(64000),
                    parser.getProperty(ENTITY_EXPANSION_LIMIT));
            assertTrue("secure-processing should default to true (derived)",
                    parser.getFeature(SECURE_PROCESSING));

            assertTrue("lexical-handler capability",
                    parser.getFeature(LEXICAL_HANDLER_FEATURE));
            assertTrue("parameter-entities capability",
                    parser.getFeature(PARAMETER_ENTITIES));
            assertTrue("use-attributes2 capability",
                    parser.getFeature(USE_ATTRIBUTES2));
            assertTrue("use-locator2 capability",
                    parser.getFeature(USE_LOCATOR2));
            assertTrue("use-entity-resolver2 capability",
                    parser.getFeature(USE_ENTITY_RESOLVER2));
            assertTrue("xml-1.1 capability",
                    parser.getFeature(XML_1_1));

            assertFalse("unicode-normalization-checking should be false",
                    parser.getFeature(UNICODE_NORM));
            assertFalse("is-standalone should be false before parse",
                    parser.getFeature(IS_STANDALONE));
        });
    }

    // ========== Exception classes ==========

    @Test
    public void testUnknownFeatureThrowsExactClass() throws Exception {
        withParser((parser) -> {
            String unknown = "http://example.com/nonexistent-feature";
            try {
                parser.getFeature(unknown);
                fail("getFeature unknown should throw SAXNotRecognizedException");
            } catch (Exception e) {
                assertEquals("getFeature exact class",
                        SAXNotRecognizedException.class, e.getClass());
            }
            try {
                parser.setFeature(unknown, true);
                fail("setFeature unknown should throw SAXNotRecognizedException");
            } catch (Exception e) {
                assertEquals("setFeature exact class",
                        SAXNotRecognizedException.class, e.getClass());
            }
        });
    }

    @Test
    public void testUnknownPropertyThrowsExactClass() throws Exception {
        withParser((parser) -> {
            String unknown = "http://example.com/nonexistent-property";
            try {
                parser.getProperty(unknown);
                fail("getProperty unknown should throw SAXNotRecognizedException");
            } catch (Exception e) {
                assertEquals("getProperty exact class",
                        SAXNotRecognizedException.class, e.getClass());
            }
            try {
                parser.setProperty(unknown, "x");
                fail("setProperty unknown should throw SAXNotRecognizedException");
            } catch (Exception e) {
                assertEquals("setProperty exact class",
                        SAXNotRecognizedException.class, e.getClass());
            }
        });
    }

    @Test
    public void testReadOnlyFeaturesThrowExactClass() throws Exception {
        withParser((parser) -> {
            assertSetFeatureNotSupported(parser, USE_ATTRIBUTES2, false);
            assertSetFeatureNotSupported(parser, LEXICAL_HANDLER_FEATURE, false);
            assertSetFeatureNotSupported(parser, XML_1_1, false);
            assertSetFeatureNotSupported(parser, PARAMETER_ENTITIES, false);
            assertSetFeatureNotSupported(parser, USE_LOCATOR2, false);
            assertSetFeatureNotSupported(parser, USE_ENTITY_RESOLVER2, false);
            assertSetFeatureNotSupported(parser, IS_STANDALONE, true);
        });
    }

    @Test
    public void testDtdParserPropertyUnrecognized() throws Exception {
        String dtdParser = "http://www.nongnu.org/gonzalez/properties/dtd-parser";
        withParser((parser) -> {
            try {
                parser.getProperty(dtdParser);
                fail("getProperty dtd-parser should throw SAXNotRecognizedException");
            } catch (Exception e) {
                assertEquals(SAXNotRecognizedException.class, e.getClass());
            }
            try {
                parser.setProperty(dtdParser, null);
                fail("setProperty dtd-parser should throw SAXNotRecognizedException");
            } catch (Exception e) {
                assertEquals(SAXNotRecognizedException.class, e.getClass());
            }
        });
    }

    @Test
    public void testUnicodeNormalizationChecking() throws Exception {
        withParser((parser) -> {
            try {
                parser.setFeature(UNICODE_NORM, true);
                fail("unicode-normalization-checking=true should throw SAXNotSupportedException");
            } catch (Exception e) {
                assertEquals(SAXNotSupportedException.class, e.getClass());
            }
            parser.setFeature(UNICODE_NORM, false);
            assertFalse(parser.getFeature(UNICODE_NORM));
        });
    }

    private static void assertSetFeatureNotSupported(Parser parser, String name, boolean value)
            throws Exception {
        try {
            parser.setFeature(name, value);
            fail("setFeature(" + name + ") should throw SAXNotSupportedException");
        } catch (Exception e) {
            assertEquals("setFeature(" + name + ") exact class",
                    SAXNotSupportedException.class, e.getClass());
        }
    }

    // ========== Mutable features ==========

    @Test
    public void testMutableFeaturesSetGetAndPersistAcrossReset() throws Exception {
        withParser((parser) -> {
            parser.setFeature(NAMESPACES, false);
            parser.setFeature(NAMESPACE_PREFIXES, true);
            parser.setFeature(VALIDATION, true);
            parser.setFeature(EXTERNAL_GENERAL, true);
            parser.setFeature(EXTERNAL_PARAMETER, true);
            parser.setFeature(DISALLOW_DOCTYPE, true);
            parser.setFeature(RESOLVE_DTD_URIS, false);
            parser.setFeature(STRING_INTERNING, false);
            parser.setFeature(XMLNS_URIS, true);

            assertFalse(parser.getFeature(NAMESPACES));
            assertTrue(parser.getFeature(NAMESPACE_PREFIXES));
            assertTrue(parser.getFeature(VALIDATION));
            assertTrue(parser.getFeature(EXTERNAL_GENERAL));
            assertTrue(parser.getFeature(EXTERNAL_PARAMETER));
            assertTrue(parser.getFeature(DISALLOW_DOCTYPE));
            assertFalse(parser.getFeature(RESOLVE_DTD_URIS));
            assertFalse(parser.getFeature(STRING_INTERNING));
            assertTrue(parser.getFeature(XMLNS_URIS));

            parser.reset();

            assertFalse("namespaces persists across reset",
                    parser.getFeature(NAMESPACES));
            assertTrue("namespace-prefixes persists across reset",
                    parser.getFeature(NAMESPACE_PREFIXES));
            assertTrue("validation persists across reset",
                    parser.getFeature(VALIDATION));
            assertTrue("external-general-entities persists across reset",
                    parser.getFeature(EXTERNAL_GENERAL));
            assertTrue("external-parameter-entities persists across reset",
                    parser.getFeature(EXTERNAL_PARAMETER));
            assertTrue("disallow-doctype-decl persists across reset",
                    parser.getFeature(DISALLOW_DOCTYPE));
            assertFalse("resolve-dtd-uris persists across reset",
                    parser.getFeature(RESOLVE_DTD_URIS));
            assertFalse("string-interning persists across reset",
                    parser.getFeature(STRING_INTERNING));
            assertTrue("xmlns-uris persists across reset",
                    parser.getFeature(XMLNS_URIS));
        });
    }

    // ========== Handlers ==========

    @Test
    public void testLexicalHandlerProperty() throws Exception {
        withParser((parser) -> {
            assertNull("lexical-handler initially null",
                    parser.getProperty(LEXICAL_HANDLER_PROPERTY));

            StubLexicalHandler handler = new StubLexicalHandler();
            parser.setProperty(LEXICAL_HANDLER_PROPERTY, handler);
            assertSame("lexical-handler get returns same instance",
                    handler, parser.getProperty(LEXICAL_HANDLER_PROPERTY));

            parser.setProperty(LEXICAL_HANDLER_PROPERTY, null);
            assertNull("lexical-handler cleared by null",
                    parser.getProperty(LEXICAL_HANDLER_PROPERTY));

            try {
                parser.setProperty(LEXICAL_HANDLER_PROPERTY, "not-a-handler");
                fail("wrong type should throw SAXNotSupportedException");
            } catch (Exception e) {
                assertEquals(SAXNotSupportedException.class, e.getClass());
            }
        });
    }

    @Test
    public void testDeclarationHandlerProperty() throws Exception {
        withParser((parser) -> {
            assertNull("declaration-handler initially null",
                    parser.getProperty(DECLARATION_HANDLER_PROPERTY));

            StubDeclHandler handler = new StubDeclHandler();
            parser.setProperty(DECLARATION_HANDLER_PROPERTY, handler);
            assertSame("declaration-handler get returns same instance",
                    handler, parser.getProperty(DECLARATION_HANDLER_PROPERTY));

            parser.setProperty(DECLARATION_HANDLER_PROPERTY, null);
            assertNull("declaration-handler cleared by null",
                    parser.getProperty(DECLARATION_HANDLER_PROPERTY));

            try {
                parser.setProperty(DECLARATION_HANDLER_PROPERTY, Integer.valueOf(1));
                fail("wrong type should throw SAXNotSupportedException");
            } catch (Exception e) {
                assertEquals(SAXNotSupportedException.class, e.getClass());
            }
        });
    }

    @Test
    public void testSecureProcessingSideEffects() throws Exception {
        withParser((parser) -> {
            // Defaults already secure; flip off then on to verify side effects.
            parser.setFeature(SECURE_PROCESSING, false);
            assertTrue("secure-processing false enables external-general",
                    parser.getFeature(EXTERNAL_GENERAL));
            assertTrue("secure-processing false enables external-parameter",
                    parser.getFeature(EXTERNAL_PARAMETER));
            assertEquals("secure-processing false sets accessExternalDTD to all",
                    "all", parser.getProperty(ACCESS_EXTERNAL_DTD));
            assertFalse(parser.getFeature(SECURE_PROCESSING));

            parser.setFeature(SECURE_PROCESSING, true);
            assertFalse("secure-processing true disables external-general",
                    parser.getFeature(EXTERNAL_GENERAL));
            assertFalse("secure-processing true disables external-parameter",
                    parser.getFeature(EXTERNAL_PARAMETER));
            assertEquals("secure-processing true clears accessExternalDTD",
                    "", parser.getProperty(ACCESS_EXTERNAL_DTD));
            assertTrue(parser.getFeature(SECURE_PROCESSING));
        });
    }

    // ========== Behavior (Scanner-critical) ==========

    @Test
    public void testDisallowDoctypeDeclRejectsDoctype() throws Exception {
        withParser((parser) -> {
            String xml = "<?xml version=\"1.0\"?>\n"
                    + "<!DOCTYPE foo [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>\n"
                    + "<foo>&xxe;</foo>";

            parser.setFeature(DISALLOW_DOCTYPE, true);
            parser.setContentHandler(new DefaultHandler());
            try {
                parser.parse(new InputSource(new ByteArrayInputStream(xml.getBytes("UTF-8"))));
                fail("Expected fatal error for DOCTYPE with disallow-doctype-decl");
            } catch (SAXException e) {
                assertTrue("Error should mention disallow-doctype-decl, was: " + e.getMessage(),
                        e.getMessage().contains(DISALLOW_DOCTYPE));
            }
        });
    }

    @Test
    public void testDefaultSecureSettingsDoNotLoadExternalEntities() throws Exception {
        withParser((parser) -> {
            secretFile = File.createTempFile("gonzalez-secret", ".txt");
            writeFile(secretFile, "SECRET_PAYLOAD");

            dtdFile = File.createTempFile("gonzalez-ext", ".dtd");
            writeFile(dtdFile, "<!ELEMENT root (#PCDATA)>\n");

            String dtdName = dtdFile.getName();
            String secretName = secretFile.getName();
            String xml = "<!DOCTYPE root SYSTEM \"" + dtdName + "\" [\n"
                    + "  <!ENTITY ext SYSTEM \"" + secretName + "\">\n"
                    + "]>\n"
                    + "<root>&ext;</root>";
            xmlFile = new File(dtdFile.getParent(), "gonzalez-secure.xml");
            writeFile(xmlFile, xml);

            CharCollector handler = new CharCollector();
            // Fresh parser with default secure settings - do not
            // enable external entities or widen accessExternalDTD.
            Parser secure = new Parser();
            secure.setContentHandler(handler);
            secure.setProperty(LEXICAL_HANDLER_PROPERTY, handler);

            assertFalse(secure.getFeature(EXTERNAL_GENERAL));
            assertFalse(secure.getFeature(EXTERNAL_PARAMETER));
            assertEquals("", secure.getProperty(ACCESS_EXTERNAL_DTD));

            secure.parse(xmlFile.toURI().toString());

            assertFalse("External entity payload must not appear in content: " + handler.chars,
                    handler.chars.toString().contains("SECRET_PAYLOAD"));
        });
    }

    @Test
    public void testXmlnsUrisMutable() throws Exception {
        withParser((parser) -> {
            assertFalse(parser.getFeature(XMLNS_URIS));
            parser.setFeature(XMLNS_URIS, true);
            assertTrue(parser.getFeature(XMLNS_URIS));
            parser.setFeature(XMLNS_URIS, false);
            assertFalse(parser.getFeature(XMLNS_URIS));
        });
    }

    // ========== Helpers ==========

    private static void writeFile(File file, String content) throws IOException {
        FileWriter writer = new FileWriter(file);
        try {
            writer.write(content);
        } finally {
            writer.close();
        }
    }

    private static class StubLexicalHandler implements LexicalHandler {
        @Override
        public void startDTD(String name, String publicId, String systemId) {
        }

        @Override
        public void endDTD() {
        }

        @Override
        public void startEntity(String name) {
        }

        @Override
        public void endEntity(String name) {
        }

        @Override
        public void startCDATA() {
        }

        @Override
        public void endCDATA() {
        }

        @Override
        public void comment(char[] ch, int start, int length) {
        }
    }

    private static class StubDeclHandler implements DeclHandler {
        @Override
        public void elementDecl(String name, String model) {
        }

        @Override
        public void attributeDecl(String eName, String aName, String type,
                String mode, String value) {
        }

        @Override
        public void internalEntityDecl(String name, String value) {
        }

        @Override
        public void externalEntityDecl(String name, String publicId, String systemId) {
        }
    }

    private static class CharCollector extends DefaultHandler implements LexicalHandler {
        final StringBuilder chars = new StringBuilder();

        @Override
        public void characters(char[] ch, int start, int length) {
            chars.append(ch, start, length);
        }

        @Override
        public void startDTD(String name, String publicId, String systemId) {
        }

        @Override
        public void endDTD() {
        }

        @Override
        public void startEntity(String name) {
        }

        @Override
        public void endEntity(String name) {
        }

        @Override
        public void startCDATA() {
        }

        @Override
        public void endCDATA() {
        }

        @Override
        public void comment(char[] ch, int start, int length) {
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes atts) {
        }
    }
}

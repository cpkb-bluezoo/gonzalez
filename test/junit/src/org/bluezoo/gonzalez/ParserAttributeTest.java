/*
 * ParserAttributeTest.java
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

package org.bluezoo.gonzalez;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;
import org.xml.sax.Attributes;
import org.xml.sax.ContentHandler;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.ext.Attributes2;
import org.xml.sax.helpers.DefaultHandler;

import static org.junit.Assert.*;

/**
 * JUnit tests for attribute handling: normalization, validation, default values.
 *
 * @author Chris Burdess
 */
public class ParserAttributeTest {

    private static final String DTD_PARSER_PROPERTY = "http://www.nongnu.org/gonzalez/properties/dtd-parser";

    static class ValidationErrorCollector implements ErrorHandler {
        List<SAXParseException> errors = new ArrayList<SAXParseException>();

        @Override
        public void error(SAXParseException e) {
            errors.add(e);
        }

        @Override
        public void warning(SAXParseException e) {
        }

        @Override
        public void fatalError(SAXParseException e) throws SAXException {
            throw e;
        }
    }

    static ValidationErrorCollector parseWithValidation(String xml) throws Exception {
        Parser parser = new Parser();
        parser.setContentHandler(new DefaultHandler());
        ValidationErrorCollector errors = new ValidationErrorCollector();
        parser.setErrorHandler(errors);
        parser.setFeature("http://xml.org/sax/features/validation", true);
        ByteArrayInputStream bais = new ByteArrayInputStream(xml.getBytes("UTF-8"));
        parser.parse(new InputSource(bais));
        return errors;
    }

    // --- Attribute Normalization Tests (from AttributeNormalizationTest) ---

    @Test
    public void testWhitespaceNormalizationCDATA() throws Exception {
        String xml = "<?xml version='1.0'?>\n" +
                     "<root attr='value\twith\ttabs and\nnewlines'></root>";

        AttributeCaptureHandler handler = new AttributeCaptureHandler();
        Parser parser = new Parser();
        parser.setContentHandler(handler);
        parser.parse(new InputSource(new ByteArrayInputStream(xml.getBytes("UTF-8"))));

        String expected = "value with tabs and newlines";
        String actual = handler.attributes.get("attr");
        assertEquals(expected, actual);
    }

    @Test
    public void testNonCDATANormalization() throws Exception {
        String xml = "<?xml version='1.0'?>\n" +
                     "<!DOCTYPE root [\n" +
                     "  <!ELEMENT root EMPTY>\n" +
                     "  <!ATTLIST root\n" +
                     "    id ID #REQUIRED\n" +
                     "    type NMTOKEN #IMPLIED\n" +
                     "  >\n" +
                     "]>\n" +
                     "<root id='  my-id  ' type='  one   two  '></root>";

        AttributeCaptureHandler handler = new AttributeCaptureHandler();
        Parser parser = new Parser();
        parser.setContentHandler(handler);
        parser.parse(new InputSource(new ByteArrayInputStream(xml.getBytes("UTF-8"))));

        String expectedId = "my-id";
        String expectedType = "one two";
        String actualId = handler.attributes.get("id");
        String actualType = handler.attributes.get("type");
        assertEquals(expectedId, actualId);
        assertEquals(expectedType, actualType);
    }

    @Test
    public void testEntityReferencesInAttributeValues() throws Exception {
        String xml = "<?xml version='1.0'?>\n" +
                     "<root attr='value&amp;with&lt;entities&gt;'></root>";

        AttributeCaptureHandler handler = new AttributeCaptureHandler();
        Parser parser = new Parser();
        parser.setContentHandler(handler);
        parser.parse(new InputSource(new ByteArrayInputStream(xml.getBytes("UTF-8"))));

        String expected = "value&with<entities>";
        String actual = handler.attributes.get("attr");
        assertEquals(expected, actual);
    }

    @Test
    public void testMixedWhitespaceNormalization() throws Exception {
        String xml = "<?xml version='1.0'?>\n" +
                     "<!DOCTYPE root [\n" +
                     "  <!ELEMENT root EMPTY>\n" +
                     "  <!ATTLIST root\n" +
                     "    tokens NMTOKENS #IMPLIED\n" +
                     "  >\n" +
                     "]>\n" +
                     "<root tokens=' \t\na\tb\nc \t'></root>";

        AttributeCaptureHandler handler = new AttributeCaptureHandler();
        Parser parser = new Parser();
        parser.setContentHandler(handler);
        parser.parse(new InputSource(new ByteArrayInputStream(xml.getBytes("UTF-8"))));

        String expected = "a b c";
        String actual = handler.attributes.get("tokens");
        assertEquals(expected, actual);
    }

    // --- Attribute Normalization Edge Cases (from AttributeNormalizationEdgeCasesTest) ---

    @Test
    public void testEmptyAttributeValues() throws Exception {
        String xml = "<?xml version='1.0'?>\n" +
                     "<root attr1='' attr2=\"\"></root>";

        AttributeCaptureHandler handler = new AttributeCaptureHandler();
        Parser parser = new Parser();
        parser.setContentHandler(handler);
        parser.parse(new InputSource(new ByteArrayInputStream(xml.getBytes("UTF-8"))));

        String attr1 = handler.attributes.get("attr1");
        String attr2 = handler.attributes.get("attr2");
        assertEquals("", attr1);
        assertEquals("", attr2);
    }

    @Test
    public void testOnlyWhitespaceAttributeValues() throws Exception {
        String xml = "<?xml version='1.0'?>\n" +
                     "<!DOCTYPE root [\n" +
                     "  <!ELEMENT root EMPTY>\n" +
                     "  <!ATTLIST root\n" +
                     "    cdata CDATA #IMPLIED\n" +
                     "    token NMTOKEN #IMPLIED\n" +
                     "  >\n" +
                     "]>\n" +
                     "<root cdata='   \t\n  ' token='  \t  '></root>";

        AttributeCaptureHandler handler = new AttributeCaptureHandler();
        Parser parser = new Parser();
        parser.setContentHandler(handler);
        parser.parse(new InputSource(new ByteArrayInputStream(xml.getBytes("UTF-8"))));

        String cdata = handler.attributes.get("cdata");
        String token = handler.attributes.get("token");

        assertTrue("cdata should have at least 6 chars", cdata.length() >= 6);
        for (int i = 0; i < cdata.length(); i++) {
            assertEquals("cdata char at " + i, ' ', cdata.charAt(i));
        }
        assertEquals("token should be empty after trim", "", token);
    }

    @Test
    public void testConsecutiveEntityReferences() throws Exception {
        String xml = "<?xml version='1.0'?>\n" +
                     "<root attr='&lt;&gt;&amp;&quot;&apos;'></root>";

        AttributeCaptureHandler handler = new AttributeCaptureHandler();
        Parser parser = new Parser();
        parser.setContentHandler(handler);
        parser.parse(new InputSource(new ByteArrayInputStream(xml.getBytes("UTF-8"))));

        String expected = "<>&\"'";
        String actual = handler.attributes.get("attr");
        assertEquals(expected, actual);
    }

    @Test
    public void testNoDTDAllCDATA() throws Exception {
        String xml = "<?xml version='1.0'?>\n" +
                     "<root id='  my-id  ' tokens='  a   b  '></root>";

        AttributeCaptureHandler handler = new AttributeCaptureHandler();
        Parser parser = new Parser();
        parser.setContentHandler(handler);
        parser.parse(new InputSource(new ByteArrayInputStream(xml.getBytes("UTF-8"))));

        String expectedId = "  my-id  ";
        String expectedTokens = "  a   b  ";
        String actualId = handler.attributes.get("id");
        String actualTokens = handler.attributes.get("tokens");
        assertEquals(expectedId, actualId);
        assertEquals(expectedTokens, actualTokens);
    }

    @Test
    public void testMultipleAttributesPerElement() throws Exception {
        String xml = "<?xml version='1.0'?>\n" +
                     "<!DOCTYPE root [\n" +
                     "  <!ELEMENT root EMPTY>\n" +
                     "  <!ATTLIST root\n" +
                     "    a CDATA #IMPLIED\n" +
                     "    b ID #IMPLIED\n" +
                     "    c NMTOKEN #IMPLIED\n" +
                     "    d CDATA #IMPLIED\n" +
                     "  >\n" +
                     "]>\n" +
                     "<root a='  v1  ' b='  v2  ' c='  v3  ' d='v4'></root>";

        AttributeCaptureHandler handler = new AttributeCaptureHandler();
        Parser parser = new Parser();
        parser.setContentHandler(handler);
        parser.parse(new InputSource(new ByteArrayInputStream(xml.getBytes("UTF-8"))));

        String a = handler.attributes.get("a");
        String b = handler.attributes.get("b");
        String c = handler.attributes.get("c");
        String d = handler.attributes.get("d");
        assertEquals("  v1  ", a);
        assertEquals("v2", b);
        assertEquals("v3", c);
        assertEquals("v4", d);
    }

    // --- Attribute Validation Tests (from AttributeValidationTest) ---

    @Test
    public void testRequiredAttributePresent() throws Exception {
        String xml = "<?xml version='1.0'?>\n" +
            "<!DOCTYPE root [\n" +
            "  <!ELEMENT root EMPTY>\n" +
            "  <!ATTLIST root\n" +
            "    id CDATA #REQUIRED>\n" +
            "]>\n" +
            "<root id='test'/>";

        Parser parser = new Parser();
        parser.setContentHandler(new MinimalContentHandler());
        parser.setFeature("http://xml.org/sax/features/validation", true);
        parser.parse(new InputSource(new ByteArrayInputStream(xml.getBytes("UTF-8"))));
    }

    @Test
    public void testRequiredAttributeMissing() throws Exception {
        String xml = "<?xml version='1.0'?>\n" +
            "<!DOCTYPE root [\n" +
            "  <!ELEMENT root EMPTY>\n" +
            "  <!ATTLIST root\n" +
            "    id CDATA #REQUIRED>\n" +
            "]>\n" +
            "<root/>";

        ValidationErrorCollector errors = parseWithValidation(xml);
        assertFalse("Missing #REQUIRED attribute should produce error",
            errors.errors.isEmpty());
        String message = errors.errors.get(0).getMessage();
        assertTrue("Expected 'Required attribute' in message",
            message.contains("Required attribute"));
    }

    @Test
    public void testUniqueIDsAccepted() throws Exception {
        String xml = "<?xml version='1.0'?>\n" +
            "<!DOCTYPE root [\n" +
            "  <!ELEMENT root (a,b)>\n" +
            "  <!ELEMENT a EMPTY>\n" +
            "  <!ELEMENT b EMPTY>\n" +
            "  <!ATTLIST a id ID #REQUIRED>\n" +
            "  <!ATTLIST b id ID #REQUIRED>\n" +
            "]>\n" +
            "<root><a id='first'/><b id='second'/></root>";

        Parser parser = new Parser();
        parser.setContentHandler(new MinimalContentHandler());
        parser.setFeature("http://xml.org/sax/features/validation", true);
        parser.parse(new InputSource(new ByteArrayInputStream(xml.getBytes("UTF-8"))));
    }

    @Test
    public void testDuplicateIDRejected() throws Exception {
        String xml = "<?xml version='1.0'?>\n" +
            "<!DOCTYPE root [\n" +
            "  <!ELEMENT root (a,b)>\n" +
            "  <!ELEMENT a EMPTY>\n" +
            "  <!ELEMENT b EMPTY>\n" +
            "  <!ATTLIST a id ID #REQUIRED>\n" +
            "  <!ATTLIST b id ID #REQUIRED>\n" +
            "]>\n" +
            "<root><a id='same'/><b id='same'/></root>";

        ValidationErrorCollector errors = parseWithValidation(xml);
        assertFalse("Duplicate ID should produce error",
            errors.errors.isEmpty());
        String message = errors.errors.get(0).getMessage();
        assertTrue("Expected 'already declared' in message",
            message.contains("already declared"));
    }

    @Test
    public void testValidIDREFAccepted() throws Exception {
        String xml = "<?xml version='1.0'?>\n" +
            "<!DOCTYPE root [\n" +
            "  <!ELEMENT root (a,b)>\n" +
            "  <!ELEMENT a EMPTY>\n" +
            "  <!ELEMENT b EMPTY>\n" +
            "  <!ATTLIST a id ID #REQUIRED>\n" +
            "  <!ATTLIST b ref IDREF #REQUIRED>\n" +
            "]>\n" +
            "<root><a id='target'/><b ref='target'/></root>";

        Parser parser = new Parser();
        parser.setContentHandler(new MinimalContentHandler());
        parser.setFeature("http://xml.org/sax/features/validation", true);
        parser.parse(new InputSource(new ByteArrayInputStream(xml.getBytes("UTF-8"))));
    }

    @Test
    public void testInvalidIDREFRejected() throws Exception {
        String xml = "<?xml version='1.0'?>\n" +
            "<!DOCTYPE root [\n" +
            "  <!ELEMENT root (a)>\n" +
            "  <!ELEMENT a EMPTY>\n" +
            "  <!ATTLIST a ref IDREF #REQUIRED>\n" +
            "]>\n" +
            "<root><a ref='nonexistent'/></root>";

        ValidationErrorCollector errors = parseWithValidation(xml);
        assertFalse("IDREF to undeclared ID should produce error",
            errors.errors.isEmpty());
        String message = errors.errors.get(0).getMessage();
        assertTrue("Expected 'undeclared ID' in message",
            message.contains("undeclared ID"));
    }

    @Test
    public void testValidNMTOKENAccepted() throws Exception {
        String xml = "<?xml version='1.0'?>\n" +
            "<!DOCTYPE root [\n" +
            "  <!ELEMENT root EMPTY>\n" +
            "  <!ATTLIST root\n" +
            "    token NMTOKEN #REQUIRED>\n" +
            "]>\n" +
            "<root token='valid-token_123'/>";

        Parser parser = new Parser();
        parser.setContentHandler(new MinimalContentHandler());
        parser.setFeature("http://xml.org/sax/features/validation", true);
        parser.parse(new InputSource(new ByteArrayInputStream(xml.getBytes("UTF-8"))));
    }

    @Test
    public void testInvalidNMTOKENRejected() throws Exception {
        String xml = "<?xml version='1.0'?>\n" +
            "<!DOCTYPE root [\n" +
            "  <!ELEMENT root EMPTY>\n" +
            "  <!ATTLIST root\n" +
            "    token NMTOKEN #REQUIRED>\n" +
            "]>\n" +
            "<root token='invalid token'/>";

        ValidationErrorCollector errors = parseWithValidation(xml);
        assertFalse("Invalid NMTOKEN should produce error",
            errors.errors.isEmpty());
        String message = errors.errors.get(0).getMessage();
        assertTrue("Expected 'not a valid NMTOKEN' in message",
            message.contains("not a valid NMTOKEN"));
    }

    // --- Default Attribute Value Tests (from DefaultAttributeValueTest) ---

    @Test
    public void testSimpleDefaultValue() throws Exception {
        String xml = "<?xml version='1.0'?>\n" +
                    "<!DOCTYPE root [\n" +
                    "  <!ATTLIST root\n" +
                    "    attr CDATA \"default value\"\n" +
                    "  >\n" +
                    "]>\n" +
                    "<root/>";

        SingleAttributeCapture handler = new SingleAttributeCapture();
        Parser parser = new Parser();
        parser.setContentHandler(handler);
        parser.parse(new InputSource(new ByteArrayInputStream(xml.getBytes("UTF-8"))));

        assertNotNull("Default value not applied", handler.attrValue);
        assertEquals("default value", handler.attrValue);
        assertFalse("Default value should have specified=false", handler.attrSpecified);
    }

    @Test
    public void testDefaultWithEntityRef() throws Exception {
        String xml = "<?xml version='1.0'?>\n" +
                    "<!DOCTYPE root [\n" +
                    "  <!ENTITY copy \"Copyright 2025\">\n" +
                    "  <!ATTLIST root\n" +
                    "    attr CDATA \"&copy;\"\n" +
                    "  >\n" +
                    "]>\n" +
                    "<root/>";

        SingleAttributeCapture handler = new SingleAttributeCapture();
        Parser parser = new Parser();
        parser.setContentHandler(handler);
        parser.parse(new InputSource(new ByteArrayInputStream(xml.getBytes("UTF-8"))));

        assertNotNull("Default value not applied", handler.attrValue);
        assertEquals("Copyright 2025", handler.attrValue);
        assertFalse("Default value should have specified=false", handler.attrSpecified);
    }

    @Test
    public void testFixedValue() throws Exception {
        String xml = "<?xml version='1.0'?>\n" +
                    "<!DOCTYPE root [\n" +
                    "  <!ATTLIST root\n" +
                    "    version CDATA #FIXED \"1.0\"\n" +
                    "  >\n" +
                    "]>\n" +
                    "<root/>";

        SingleAttributeCapture handler = new SingleAttributeCapture();
        Parser parser = new Parser();
        parser.setContentHandler(handler);
        parser.parse(new InputSource(new ByteArrayInputStream(xml.getBytes("UTF-8"))));

        assertNotNull("Fixed value not applied", handler.attrValue);
        assertEquals("1.0", handler.attrValue);
        assertFalse("Fixed value should have specified=false", handler.attrSpecified);
    }

    @Test
    public void testFixedValueMismatch() throws Exception {
        String xml = "<?xml version='1.0'?>\n" +
                    "<!DOCTYPE root [\n" +
                    "  <!ELEMENT root EMPTY>\n" +
                    "  <!ATTLIST root\n" +
                    "    version CDATA #FIXED \"1.0\"\n" +
                    "  >\n" +
                    "]>\n" +
                    "<root version='2.0'/>";

        ValidationErrorCollector errors = parseWithValidation(xml);
        assertFalse("#FIXED value mismatch should produce error",
            errors.errors.isEmpty());
        String message = errors.errors.get(0).getMessage();
        assertTrue("Expected '#FIXED' in error message",
            message.contains("#FIXED"));
    }

    @Test
    public void testMultipleDefaults() throws Exception {
        String xml = "<?xml version='1.0'?>\n" +
                    "<!DOCTYPE root [\n" +
                    "  <!ATTLIST root\n" +
                    "    a CDATA \"valueA\"\n" +
                    "    b CDATA \"valueB\"\n" +
                    "    c CDATA \"valueC\"\n" +
                    "  >\n" +
                    "]>\n" +
                    "<root/>";

        MultiAttributeCapture handler = new MultiAttributeCapture();
        Parser parser = new Parser();
        parser.setContentHandler(handler);
        parser.parse(new InputSource(new ByteArrayInputStream(xml.getBytes("UTF-8"))));

        assertEquals("Expected 3 attributes", 3, handler.attrCount);
        assertEquals("valueA", handler.getAttrValue("a"));
        assertEquals("valueB", handler.getAttrValue("b"));
        assertEquals("valueC", handler.getAttrValue("c"));
    }

    @Test
    public void testSpecifiedFlag() throws Exception {
        String xml = "<?xml version='1.0'?>\n" +
                    "<!DOCTYPE root [\n" +
                    "  <!ATTLIST root\n" +
                    "    specified CDATA \"default1\"\n" +
                    "    defaulted CDATA \"default2\"\n" +
                    "  >\n" +
                    "]>\n" +
                    "<root specified='value1'/>";

        Attributes2Capture handler = new Attributes2Capture();
        Parser parser = new Parser();
        parser.setContentHandler(handler);
        parser.parse(new InputSource(new ByteArrayInputStream(xml.getBytes("UTF-8"))));

        assertEquals("Expected 2 attributes", 2, handler.attrs.getLength());

        int specIdx = handler.attrs.getIndex("specified");
        int defIdx = handler.attrs.getIndex("defaulted");

        assertTrue("Attribute 'specified' should be specified=true", handler.attrs.isSpecified(specIdx));
        assertFalse("Attribute 'defaulted' should be specified=false", handler.attrs.isSpecified(defIdx));
    }

    // --- ATTLIST Default Value Parsing (from AttlistDefaultValueTest) ---

    @Test
    public void testSimpleDefaultValueParsing() throws Exception {
        String xml = "<?xml version='1.0'?>\n" +
                    "<!DOCTYPE root [\n" +
                    "  <!ATTLIST root\n" +
                    "    attr CDATA \"default value\"\n" +
                    "  >\n" +
                    "]>\n" +
                    "<root/>";

        Parser parser = new Parser();
        parser.setContentHandler(new MinimalContentHandler());
        parser.setProperty("http://xml.org/sax/properties/lexical-handler", new MinimalLexicalHandler());
        parser.parse(new InputSource(new ByteArrayInputStream(xml.getBytes("UTF-8"))));

        DTDParser dtdParser = (DTDParser) parser.getProperty(DTD_PARSER_PROPERTY);
        assertNotNull("DTDParser not available", dtdParser);

        AttributeDeclaration attrDecl = dtdParser.getAttributeDeclaration("root", "attr");
        assertNotNull("Attribute declaration for 'attr' not found", attrDecl);
        assertNotNull("Default value is null", attrDecl.defaultValue);
        assertEquals("Expected 1 part", 1, attrDecl.defaultValue.size());
        assertTrue("Part 0 should be String", attrDecl.defaultValue.get(0) instanceof String);
        assertEquals("default value", attrDecl.defaultValue.get(0));
    }

    @Test
    public void testDefaultWithEntityRefParsing() throws Exception {
        String xml = "<?xml version='1.0'?>\n" +
                    "<!DOCTYPE root [\n" +
                    "  <!ENTITY copy \"Copyright 2025\">\n" +
                    "  <!ATTLIST root\n" +
                    "    attr CDATA \"before &copy; after\"\n" +
                    "  >\n" +
                    "]>\n" +
                    "<root/>";

        Parser parser = new Parser();
        parser.setContentHandler(new MinimalContentHandler());
        parser.setProperty("http://xml.org/sax/properties/lexical-handler", new MinimalLexicalHandler());
        parser.parse(new InputSource(new ByteArrayInputStream(xml.getBytes("UTF-8"))));

        DTDParser dtdParser = (DTDParser) parser.getProperty(DTD_PARSER_PROPERTY);
        assertNotNull("DTDParser not available", dtdParser);

        AttributeDeclaration attrDecl = dtdParser.getAttributeDeclaration("root", "attr");
        assertNotNull("Attribute declaration for 'attr' not found", attrDecl);
        assertNotNull("Default value is null", attrDecl.defaultValue);
        assertEquals("Expected 3 parts", 3, attrDecl.defaultValue.size());

        Object part0 = attrDecl.defaultValue.get(0);
        Object part1 = attrDecl.defaultValue.get(1);
        Object part2 = attrDecl.defaultValue.get(2);

        assertTrue("Part 0 should be 'before '", part0 instanceof String && "before ".equals(part0));
        assertTrue("Part 1 should be GeneralEntityReference", part1 instanceof GeneralEntityReference);
        assertEquals("copy", ((GeneralEntityReference) part1).name);
        assertTrue("Part 2 should be ' after'", part2 instanceof String && " after".equals(part2));
    }

    @Test
    public void testFixedValueWithEntityRefParsing() throws Exception {
        String xml = "<?xml version='1.0'?>\n" +
                    "<!DOCTYPE root [\n" +
                    "  <!ENTITY version \"1.0\">\n" +
                    "  <!ATTLIST root\n" +
                    "    ver CDATA #FIXED \"v&version;\"\n" +
                    "  >\n" +
                    "]>\n" +
                    "<root/>";

        Parser parser = new Parser();
        parser.setContentHandler(new MinimalContentHandler());
        parser.setProperty("http://xml.org/sax/properties/lexical-handler", new MinimalLexicalHandler());
        parser.parse(new InputSource(new ByteArrayInputStream(xml.getBytes("UTF-8"))));

        DTDParser dtdParser = (DTDParser) parser.getProperty(DTD_PARSER_PROPERTY);
        assertNotNull("DTDParser not available", dtdParser);

        AttributeDeclaration attrDecl = dtdParser.getAttributeDeclaration("root", "ver");
        assertNotNull("Attribute declaration for 'ver' not found", attrDecl);
        assertEquals(Token.FIXED, attrDecl.mode);
        assertNotNull("Default value is null", attrDecl.defaultValue);
        assertEquals("Expected 2 parts", 2, attrDecl.defaultValue.size());

        Object part0 = attrDecl.defaultValue.get(0);
        Object part1 = attrDecl.defaultValue.get(1);

        assertTrue("Part 0 should be 'v'", part0 instanceof String && "v".equals(part0));
        assertTrue("Part 1 should be GeneralEntityReference", part1 instanceof GeneralEntityReference);
        assertEquals("version", ((GeneralEntityReference) part1).name);
    }

    // --- Inner handler classes ---

    private static class AttributeCaptureHandler implements ContentHandler {
        Map<String, String> attributes = new HashMap<String, String>();

        @Override
        public void startElement(String uri, String localName, String qName, Attributes attrs) {
            for (int i = 0; i < attrs.getLength(); i++) {
                attributes.put(attrs.getQName(i), attrs.getValue(i));
            }
        }

        @Override
        public void setDocumentLocator(Locator locator) {
        }

        @Override
        public void startDocument() {
        }

        @Override
        public void endDocument() {
        }

        @Override
        public void startPrefixMapping(String prefix, String uri) {
        }

        @Override
        public void endPrefixMapping(String prefix) {
        }

        @Override
        public void endElement(String uri, String localName, String qName) {
        }

        @Override
        public void characters(char[] ch, int start, int length) {
        }

        @Override
        public void ignorableWhitespace(char[] ch, int start, int length) {
        }

        @Override
        public void processingInstruction(String target, String data) {
        }

        @Override
        public void skippedEntity(String name) {
        }
    }

    private static class SingleAttributeCapture implements ContentHandler {
        String attrValue;
        boolean attrSpecified;
        String attrName;

        @Override
        public void startElement(String uri, String localName, String qName, Attributes atts) {
            if (atts.getLength() > 0) {
                attrName = atts.getQName(0);
                attrValue = atts.getValue(0);
                if (atts instanceof Attributes2) {
                    attrSpecified = ((Attributes2) atts).isSpecified(0);
                }
            }
        }

        @Override
        public void setDocumentLocator(Locator locator) {
        }

        @Override
        public void startDocument() {
        }

        @Override
        public void endDocument() {
        }

        @Override
        public void startPrefixMapping(String prefix, String uri) {
        }

        @Override
        public void endPrefixMapping(String prefix) {
        }

        @Override
        public void endElement(String uri, String localName, String qName) {
        }

        @Override
        public void characters(char[] ch, int start, int length) {
        }

        @Override
        public void ignorableWhitespace(char[] ch, int start, int length) {
        }

        @Override
        public void processingInstruction(String target, String data) {
        }

        @Override
        public void skippedEntity(String name) {
        }
    }

    private static class MultiAttributeCapture implements ContentHandler {
        int attrCount;
        Attributes attrs;

        String getAttrValue(String name) {
            if (attrs != null) {
                return attrs.getValue(name);
            }
            return null;
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes atts) {
            this.attrs = atts;
            this.attrCount = atts.getLength();
        }

        @Override
        public void setDocumentLocator(Locator locator) {
        }

        @Override
        public void startDocument() {
        }

        @Override
        public void endDocument() {
        }

        @Override
        public void startPrefixMapping(String prefix, String uri) {
        }

        @Override
        public void endPrefixMapping(String prefix) {
        }

        @Override
        public void endElement(String uri, String localName, String qName) {
        }

        @Override
        public void characters(char[] ch, int start, int length) {
        }

        @Override
        public void ignorableWhitespace(char[] ch, int start, int length) {
        }

        @Override
        public void processingInstruction(String target, String data) {
        }

        @Override
        public void skippedEntity(String name) {
        }
    }

    private static class Attributes2Capture implements ContentHandler {
        Attributes2 attrs;

        @Override
        public void startElement(String uri, String localName, String qName, Attributes atts) {
            this.attrs = (Attributes2) atts;
        }

        @Override
        public void setDocumentLocator(Locator locator) {
        }

        @Override
        public void startDocument() {
        }

        @Override
        public void endDocument() {
        }

        @Override
        public void startPrefixMapping(String prefix, String uri) {
        }

        @Override
        public void endPrefixMapping(String prefix) {
        }

        @Override
        public void endElement(String uri, String localName, String qName) {
        }

        @Override
        public void characters(char[] ch, int start, int length) {
        }

        @Override
        public void ignorableWhitespace(char[] ch, int start, int length) {
        }

        @Override
        public void processingInstruction(String target, String data) {
        }

        @Override
        public void skippedEntity(String name) {
        }
    }

    private static class MinimalContentHandler implements ContentHandler {
        @Override
        public void setDocumentLocator(Locator locator) {
        }

        @Override
        public void startDocument() {
        }

        @Override
        public void endDocument() {
        }

        @Override
        public void startPrefixMapping(String prefix, String uri) {
        }

        @Override
        public void endPrefixMapping(String prefix) {
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes atts) {
        }

        @Override
        public void endElement(String uri, String localName, String qName) {
        }

        @Override
        public void characters(char[] ch, int start, int length) {
        }

        @Override
        public void ignorableWhitespace(char[] ch, int start, int length) {
        }

        @Override
        public void processingInstruction(String target, String data) {
        }

        @Override
        public void skippedEntity(String name) {
        }
    }

    private static class MinimalLexicalHandler implements org.xml.sax.ext.LexicalHandler {
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
}

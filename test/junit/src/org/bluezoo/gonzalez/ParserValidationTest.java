/*
 * ParserValidationTest.java
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

import org.junit.Test;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * JUnit tests for DTD content model validation, enumeration and notation
 * attribute validation, and validation error recoverability.
 *
 * @author Chris Burdess
 */
public class ParserValidationTest {

    /**
     * ErrorHandler that collects validation errors instead of throwing.
     */
    static class ErrorCollector implements ErrorHandler {
        List<SAXParseException> errors = new ArrayList<SAXParseException>();
        List<SAXParseException> warnings = new ArrayList<SAXParseException>();
        List<SAXParseException> fatalErrors = new ArrayList<SAXParseException>();

        @Override
        public void error(SAXParseException e) {
            errors.add(e);
        }

        @Override
        public void warning(SAXParseException e) {
            warnings.add(e);
        }

        @Override
        public void fatalError(SAXParseException e) throws SAXException {
            fatalErrors.add(e);
            throw e;
        }

        void reset() {
            errors.clear();
            warnings.clear();
            fatalErrors.clear();
        }
    }

    static ErrorCollector parseWithValidation(String xml) throws Exception {
        Parser parser = new Parser();
        parser.setContentHandler(new DefaultHandler());

        ErrorCollector errorCollector = new ErrorCollector();
        parser.setErrorHandler(errorCollector);
        parser.setFeature("http://xml.org/sax/features/validation", true);

        ByteArrayInputStream bais = new ByteArrayInputStream(xml.getBytes("UTF-8"));
        InputSource source = new InputSource(bais);
        parser.parse(source);

        return errorCollector;
    }

    // ========== Content Model Validation (from ContentModelValidationTest) ==========

    @Test
    public void testEMPTYContentValid() throws Exception {
        String validXml =
            "<?xml version='1.0'?>\n" +
            "<!DOCTYPE root [\n" +
            "  <!ELEMENT root EMPTY>\n" +
            "]>\n" +
            "<root/>";

        ErrorCollector errors = parseWithValidation(validXml);
        assertTrue("Valid empty element should not produce errors",
            errors.errors.isEmpty());
    }

    @Test
    public void testEMPTYContentInvalidText() throws Exception {
        String invalidXml =
            "<?xml version='1.0'?>\n" +
            "<!DOCTYPE root [\n" +
            "  <!ELEMENT root EMPTY>\n" +
            "]>\n" +
            "<root>text</root>";

        ErrorCollector errors = parseWithValidation(invalidXml);
        assertFalse("Should reject text in EMPTY element", errors.errors.isEmpty());
        String errorMsg = errors.errors.get(0).getMessage();
        assertTrue("Error should mention EMPTY and text: " + errorMsg,
            errorMsg.contains("EMPTY") && errorMsg.contains("text"));
    }

    @Test
    public void testANYContent() throws Exception {
        String xml =
            "<?xml version='1.0'?>\n" +
            "<!DOCTYPE root [\n" +
            "  <!ELEMENT root ANY>\n" +
            "  <!ELEMENT child ANY>\n" +
            "]>\n" +
            "<root>text<child>more text</child></root>";

        ErrorCollector errors = parseWithValidation(xml);
        assertTrue("ANY content model should accept any content",
            errors.errors.isEmpty());
    }

    @Test
    public void testMixedContentValid() throws Exception {
        String validXml =
            "<?xml version='1.0'?>\n" +
            "<!DOCTYPE root [\n" +
            "  <!ELEMENT root (#PCDATA|a|b)*>\n" +
            "  <!ELEMENT a EMPTY>\n" +
            "  <!ELEMENT b EMPTY>\n" +
            "]>\n" +
            "<root>text<a/>more<b/>text</root>";

        parseWithValidation(validXml);
    }

    @Test
    public void testMixedContentInvalidDisallowedElement() throws Exception {
        String invalidXml =
            "<?xml version='1.0'?>\n" +
            "<!DOCTYPE root [\n" +
            "  <!ELEMENT root (#PCDATA|a)*>\n" +
            "  <!ELEMENT a EMPTY>\n" +
            "  <!ELEMENT c EMPTY>\n" +
            "]>\n" +
            "<root>text<c/></root>";

        ErrorCollector errors = parseWithValidation(invalidXml);
        assertFalse("Disallowed element in mixed content should be rejected",
            errors.errors.isEmpty());
    }

    @Test
    public void testSequenceContentValid() throws Exception {
        String validXml =
            "<?xml version='1.0'?>\n" +
            "<!DOCTYPE root [\n" +
            "  <!ELEMENT root (a,b,c)>\n" +
            "  <!ELEMENT a EMPTY>\n" +
            "  <!ELEMENT b EMPTY>\n" +
            "  <!ELEMENT c EMPTY>\n" +
            "]>\n" +
            "<root><a/><b/><c/></root>";

        parseWithValidation(validXml);
    }

    @Test
    public void testSequenceContentInvalidWrongOrder() throws Exception {
        String invalidXml =
            "<?xml version='1.0'?>\n" +
            "<!DOCTYPE root [\n" +
            "  <!ELEMENT root (a,b,c)>\n" +
            "  <!ELEMENT a EMPTY>\n" +
            "  <!ELEMENT b EMPTY>\n" +
            "  <!ELEMENT c EMPTY>\n" +
            "]>\n" +
            "<root><a/><c/><b/></root>";

        ErrorCollector errors = parseWithValidation(invalidXml);
        assertFalse("Wrong sequence order should be rejected", errors.errors.isEmpty());
    }

    @Test
    public void testChoiceContentFirst() throws Exception {
        String validXml =
            "<?xml version='1.0'?>\n" +
            "<!DOCTYPE root [\n" +
            "  <!ELEMENT root (a|b|c)>\n" +
            "  <!ELEMENT a EMPTY>\n" +
            "  <!ELEMENT b EMPTY>\n" +
            "  <!ELEMENT c EMPTY>\n" +
            "]>\n" +
            "<root><a/></root>";

        parseWithValidation(validXml);
    }

    @Test
    public void testChoiceContentSecond() throws Exception {
        String validXml =
            "<?xml version='1.0'?>\n" +
            "<!DOCTYPE root [\n" +
            "  <!ELEMENT root (a|b|c)>\n" +
            "  <!ELEMENT a EMPTY>\n" +
            "  <!ELEMENT b EMPTY>\n" +
            "  <!ELEMENT c EMPTY>\n" +
            "]>\n" +
            "<root><b/></root>";

        parseWithValidation(validXml);
    }

    @Test
    public void testOccurrenceOptionalPresent() throws Exception {
        String validXml =
            "<?xml version='1.0'?>\n" +
            "<!DOCTYPE root [\n" +
            "  <!ELEMENT root (a?)>\n" +
            "  <!ELEMENT a EMPTY>\n" +
            "]>\n" +
            "<root><a/></root>";

        parseWithValidation(validXml);
    }

    @Test
    public void testOccurrenceOptionalAbsent() throws Exception {
        String validXml =
            "<?xml version='1.0'?>\n" +
            "<!DOCTYPE root [\n" +
            "  <!ELEMENT root (a?)>\n" +
            "  <!ELEMENT a EMPTY>\n" +
            "]>\n" +
            "<root></root>";

        parseWithValidation(validXml);
    }

    @Test
    public void testOccurrenceZeroOrMore() throws Exception {
        String validXml =
            "<?xml version='1.0'?>\n" +
            "<!DOCTYPE root [\n" +
            "  <!ELEMENT root (a*)>\n" +
            "  <!ELEMENT a EMPTY>\n" +
            "]>\n" +
            "<root><a/><a/><a/></root>";

        parseWithValidation(validXml);
    }

    @Test
    public void testOccurrenceOneOrMore() throws Exception {
        String validXml =
            "<?xml version='1.0'?>\n" +
            "<!DOCTYPE root [\n" +
            "  <!ELEMENT root (a+)>\n" +
            "  <!ELEMENT a EMPTY>\n" +
            "]>\n" +
            "<root><a/><a/></root>";

        parseWithValidation(validXml);
    }

    @Test
    public void testOccurrenceOneOrMoreWithZero() throws Exception {
        String invalidXml =
            "<?xml version='1.0'?>\n" +
            "<!DOCTYPE root [\n" +
            "  <!ELEMENT root (a+)>\n" +
            "  <!ELEMENT a EMPTY>\n" +
            "]>\n" +
            "<root></root>";

        ErrorCollector errors = parseWithValidation(invalidXml);
        assertFalse("One-or-more with zero occurrences should be rejected",
            errors.errors.isEmpty());
    }

    // ========== Enumeration and Notation (from EnumerationNotationValidationTest) ==========

    @Test
    public void testEnumerationValid() throws Exception {
        String xml =
            "<?xml version='1.0'?>\n" +
            "<!DOCTYPE root [\n" +
            "  <!ELEMENT root EMPTY>\n" +
            "  <!ATTLIST root\n" +
            "    status (draft|review|final) #REQUIRED>\n" +
            "]>\n" +
            "<root status='draft'/>";

        ErrorCollector errors = parseWithValidation(xml);
        assertTrue("Valid enumeration should not produce errors",
            errors.errors.isEmpty());
    }

    @Test
    public void testEnumerationInvalid() throws Exception {
        String xml =
            "<?xml version='1.0'?>\n" +
            "<!DOCTYPE root [\n" +
            "  <!ELEMENT root EMPTY>\n" +
            "  <!ATTLIST root\n" +
            "    status (draft|review|final) #REQUIRED>\n" +
            "]>\n" +
            "<root status='published'/>";

        ErrorCollector errors = parseWithValidation(xml);
        assertFalse("Invalid enumeration should be rejected", errors.errors.isEmpty());
        String errorMsg = errors.errors.get(0).getMessage();
        assertTrue("Error should mention enumeration: " + errorMsg,
            errorMsg.contains("not in enumeration"));
    }

    @Test
    public void testNotationValid() throws Exception {
        String xml =
            "<?xml version='1.0'?>\n" +
            "<!DOCTYPE root [\n" +
            "  <!NOTATION gif SYSTEM \"image/gif\">\n" +
            "  <!NOTATION jpeg SYSTEM \"image/jpeg\">\n" +
            "  <!ELEMENT root EMPTY>\n" +
            "  <!ATTLIST root\n" +
            "    format NOTATION (gif|jpeg) #REQUIRED>\n" +
            "]>\n" +
            "<root format='gif'/>";

        ErrorCollector errors = parseWithValidation(xml);
        assertTrue("Valid NOTATION should not produce errors",
            errors.errors.isEmpty());
    }

    @Test
    public void testNotationInvalidUndeclared() throws Exception {
        String xml =
            "<?xml version='1.0'?>\n" +
            "<!DOCTYPE root [\n" +
            "  <!NOTATION gif SYSTEM \"image/gif\">\n" +
            "  <!ELEMENT root EMPTY>\n" +
            "  <!ATTLIST root\n" +
            "    format NOTATION (gif|jpeg) #REQUIRED>\n" +
            "]>\n" +
            "<root format='jpeg'/>";

        ErrorCollector errors = parseWithValidation(xml);
        assertFalse("Undeclared NOTATION should be rejected", errors.errors.isEmpty());
        String errorMsg = errors.errors.get(0).getMessage();
        assertTrue("Error should mention not declared: " + errorMsg,
            errorMsg.contains("not declared"));
    }

    @Test
    public void testNotationWithEnumerationValid() throws Exception {
        String xml =
            "<?xml version='1.0'?>\n" +
            "<!DOCTYPE root [\n" +
            "  <!NOTATION gif SYSTEM \"image/gif\">\n" +
            "  <!NOTATION jpeg SYSTEM \"image/jpeg\">\n" +
            "  <!NOTATION png SYSTEM \"image/png\">\n" +
            "  <!ELEMENT root EMPTY>\n" +
            "  <!ATTLIST root\n" +
            "    format NOTATION (gif|jpeg) #REQUIRED>\n" +
            "]>\n" +
            "<root format='jpeg'/>";

        ErrorCollector errors = parseWithValidation(xml);
        assertTrue("Valid NOTATION in enumeration should not produce errors",
            errors.errors.isEmpty());
    }

    @Test
    public void testNotationWithEnumerationInvalid() throws Exception {
        String xml =
            "<?xml version='1.0'?>\n" +
            "<!DOCTYPE root [\n" +
            "  <!NOTATION gif SYSTEM \"image/gif\">\n" +
            "  <!NOTATION jpeg SYSTEM \"image/jpeg\">\n" +
            "  <!NOTATION png SYSTEM \"image/png\">\n" +
            "  <!ELEMENT root EMPTY>\n" +
            "  <!ATTLIST root\n" +
            "    format NOTATION (gif|jpeg) #REQUIRED>\n" +
            "]>\n" +
            "<root format='png'/>";

        ErrorCollector errors = parseWithValidation(xml);
        assertFalse("NOTATION not in enumeration should be rejected",
            errors.errors.isEmpty());
        String errorMsg = errors.errors.get(0).getMessage();
        assertTrue("Error should mention enumeration: " + errorMsg,
            errorMsg.contains("not in declared enumeration"));
    }

    // ========== Validation Recoverability (from ValidationRecoverabilityTest) ==========

    @Test
    public void testValidationErrorsRecoverable() throws Exception {
        String xml =
            "<?xml version='1.0'?>\n" +
            "<!DOCTYPE root [\n" +
            "  <!ELEMENT root (a,b)>\n" +
            "  <!ELEMENT a EMPTY>\n" +
            "  <!ELEMENT b EMPTY>\n" +
            "  <!ATTLIST a id ID #REQUIRED>\n" +
            "  <!ATTLIST b id ID #REQUIRED ref IDREF #IMPLIED>\n" +
            "]>\n" +
            "<root>\n" +
            "  <a/>\n" +
            "  <a id='x1'>text</a>\n" +
            "  <b id='x1'/>\n" +
            "  <b id='x2' ref='invalid'/>\n" +
            "</root>";

        Parser parser = new Parser();
        parser.setContentHandler(new DefaultHandler());

        ErrorCollector errorCollector = new ErrorCollector();
        parser.setErrorHandler(errorCollector);
        parser.setFeature("http://xml.org/sax/features/validation", true);

        ByteArrayInputStream bais = new ByteArrayInputStream(xml.getBytes("UTF-8"));
        InputSource source = new InputSource(bais);
        parser.parse(source);

        assertTrue("Parsing should complete despite validation errors",
            errorCollector.errors.size() >= 4);
    }
}

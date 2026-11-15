/*
 * ValidityConstraintTest.java
 * Copyright (C) 2026 Chris Burdess
 */
package org.bluezoo.gonzalez;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;
import org.xml.sax.InputSource;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Validity constraints checked when the parser validates.
 */
public class ValidityConstraintTest {

    /** Parses with validation on and returns the validity errors reported. */
    private static List<String> validate(String xml) throws Exception {
        final List<String> errors = new ArrayList<String>();
        Parser parser = new Parser();
        parser.setFeature("http://xml.org/sax/features/validation", true);
        parser.setContentHandler(new DefaultHandler());
        parser.setErrorHandler(new DefaultHandler() {
            @Override
            public void error(SAXParseException e) {
                errors.add(e.getMessage());
            }
        });
        parser.parse(new InputSource(new ByteArrayInputStream(
                xml.getBytes(StandardCharsets.UTF_8))));
        return errors;
    }

    private static void assertValid(String xml) throws Exception {
        assertEquals(new ArrayList<String>(), validate(xml));
    }

    private static void assertInvalid(String xml, String mention) throws Exception {
        List<String> errors = validate(xml);
        assertTrue("expected a validity error mentioning " + mention + ", got " + errors,
                errors.size() > 0 && errors.get(0).contains(mention));
    }

    // ---- Attribute Value Type: the attribute must have been declared -----

    @Test
    public void xmlSpaceMustBeDeclared() throws Exception {
        assertInvalid("<!DOCTYPE root [<!ELEMENT root EMPTY>]><root xml:space='preserve'/>",
                "xml:space");
    }

    @Test
    public void xmlLangMustBeDeclared() throws Exception {
        assertInvalid("<!DOCTYPE root [<!ELEMENT root EMPTY>]><root xml:lang='en'/>",
                "xml:lang");
    }

    @Test
    public void anyAttributeOnAnElementWithNoAttlistMustBeDeclared() throws Exception {
        assertInvalid("<!DOCTYPE root [<!ELEMENT root EMPTY>]><root a='b'/>", "\"a\"");
    }

    @Test
    public void declaredXmlSpaceAndXmlLangAreValid() throws Exception {
        assertValid("<!DOCTYPE root [<!ELEMENT root EMPTY>"
                + "<!ATTLIST root xml:space (default|preserve) #IMPLIED xml:lang CDATA #IMPLIED>]>"
                + "<root xml:space='preserve' xml:lang='en'/>");
    }

    @Test
    public void anElementWithNoAttributesNeedsNoAttlist() throws Exception {
        assertValid("<!DOCTYPE root [<!ELEMENT root EMPTY>]><root/>");
    }

    // ---- Element Valid: a CDATA section is not white space ----------------

    private static final String ELEMENT_CONTENT_DTD =
        "<!DOCTYPE foo [<!ELEMENT foo (a+)><!ENTITY empty ''><!ENTITY space ' '>"
        + "<!ELEMENT a EMPTY>]>";

    @Test
    public void emptyCdataSectionInElementContentIsInvalid() throws Exception {
        assertInvalid(ELEMENT_CONTENT_DTD + "<foo><a/><![CDATA[]]><a/></foo>", "CDATA");
    }

    @Test
    public void whiteSpaceCdataSectionInElementContentIsInvalid() throws Exception {
        assertInvalid(ELEMENT_CONTENT_DTD + "<foo><a/><![CDATA[ \n ]]><a/></foo>", "CDATA");
    }

    @Test
    public void whiteSpaceAndEntitiesInElementContentAreValid() throws Exception {
        assertValid(ELEMENT_CONTENT_DTD + "<foo>\n&empty;\n<a/>\n&space;<a/>\n</foo>");
    }

    @Test
    public void cdataSectionInMixedAndAnyContentIsValid() throws Exception {
        assertValid("<!DOCTYPE m [<!ELEMENT m (#PCDATA|b)*><!ELEMENT b ANY>]>"
                + "<m><![CDATA[text]]><b><![CDATA[ ]]></b></m>");
    }
}

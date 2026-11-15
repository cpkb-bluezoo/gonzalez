/*
 * ApplyTemplatesItemParamTest.java
 * Copyright (C) 2026 Chris Burdess
 */
package org.bluezoo.gonzalez.transform;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import javax.xml.transform.Transformer;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * When xsl:apply-templates selects an item that is not a node (a map, an
 * array or an atomic value), the template it reaches must still have its
 * declared parameters bound: a value supplied by xsl:with-param, or else
 * the default.
 */
public class ApplyTemplatesItemParamTest {

    private static final String XSL =
        "<xsl:stylesheet version='3.0'"
        + " xmlns:xsl='http://www.w3.org/1999/XSL/Transform'"
        + " xmlns:map='http://www.w3.org/2005/xpath-functions/map'>"
        + "<xsl:output method='text'/>";

    @Test
    public void mapTemplateDefaultReadsFromTheMap() throws Exception {
        String stylesheet = XSL
            + "<xsl:template match='/'>"
            + "<xsl:apply-templates select=\"map{'a':'x','b':'y'}\"/>"
            + "</xsl:template>"
            + "<xsl:template match='.[. instance of map(*)]'>"
            + "<xsl:param name='a' select='?a'/>"
            + "<xsl:param name='b' select='?b'/>"
            + "<xsl:value-of select='$a, $b' separator=','/>"
            + "</xsl:template>"
            + "</xsl:stylesheet>";
        assertEquals("x,y", transform(stylesheet));
    }

    @Test
    public void absentMapKeyGivesEmptySequence() throws Exception {
        String stylesheet = XSL
            + "<xsl:template match='/'>"
            + "<xsl:apply-templates select=\"map{'a':'x'}\"/>"
            + "</xsl:template>"
            + "<xsl:template match='.[. instance of map(*)]'>"
            + "<xsl:param name='a' select='?a'/>"
            + "<xsl:param name='b' as='xs:string?' xmlns:xs='http://www.w3.org/2001/XMLSchema'"
            + " select='?b'/>"
            + "<xsl:value-of select=\"$a || '|' || count($b)\"/>"
            + "</xsl:template>"
            + "</xsl:stylesheet>";
        assertEquals("x|0", transform(stylesheet));
    }

    @Test
    public void withParamOverridesDefault() throws Exception {
        String stylesheet = XSL
            + "<xsl:template match='/'>"
            + "<xsl:apply-templates select=\"map{'a':'x'}\">"
            + "<xsl:with-param name='a' select=\"'given'\"/>"
            + "</xsl:apply-templates>"
            + "</xsl:template>"
            + "<xsl:template match='.[. instance of map(*)]'>"
            + "<xsl:param name='a' select='?a'/>"
            + "<xsl:value-of select='$a'/>"
            + "</xsl:template>"
            + "</xsl:stylesheet>";
        assertEquals("given", transform(stylesheet));
    }

    @Test
    public void atomicValueTemplateGetsDefault() throws Exception {
        String stylesheet = XSL
            + "<xsl:template match='/'>"
            + "<xsl:apply-templates select='42'/>"
            + "</xsl:template>"
            + "<xsl:template match='.[. instance of xs:integer]' xmlns:xs='http://www.w3.org/2001/XMLSchema'>"
            + "<xsl:param name='p' select=\"'dflt'\"/>"
            + "<xsl:value-of select=\"string(.) || ':' || $p\"/>"
            + "</xsl:template>"
            + "</xsl:stylesheet>";
        assertEquals("42:dflt", transform(stylesheet));
    }

    @Test
    public void sequenceOfAtomicValuesGetsDefaults() throws Exception {
        String stylesheet = XSL
            + "<xsl:template match='/'>"
            + "<xsl:apply-templates select='(1, 2)'/>"
            + "</xsl:template>"
            + "<xsl:template match='.[. instance of xs:integer]' xmlns:xs='http://www.w3.org/2001/XMLSchema'>"
            + "<xsl:param name='p' select=\"'d'\"/>"
            + "<xsl:value-of select=\"string(.) || ':' || $p || ';'\"/>"
            + "</xsl:template>"
            + "</xsl:stylesheet>";
        assertEquals("1:d;2:d;", transform(stylesheet));
    }

    @Test
    public void requiredParamMissingIsAnError() throws Exception {
        String stylesheet = XSL
            + "<xsl:template match='/'>"
            + "<xsl:apply-templates select=\"map{'a':'x'}\"/>"
            + "</xsl:template>"
            + "<xsl:template match='.[. instance of map(*)]'>"
            + "<xsl:param name='needed' required='yes'/>"
            + "<xsl:value-of select='$needed'/>"
            + "</xsl:template>"
            + "</xsl:stylesheet>";
        try {
            transform(stylesheet);
            org.junit.Assert.fail("expected XTDE0700");
        } catch (Exception e) {
            String message = String.valueOf(e.getMessage());
            Throwable cause = e.getCause();
            while (cause != null) {
                message += " " + cause.getMessage();
                cause = cause.getCause();
            }
            org.junit.Assert.assertTrue(message, message.contains("XTDE0700"));
        }
    }

    private static String transform(String stylesheet) throws Exception {
        GonzalezTransformerFactory factory = new GonzalezTransformerFactory();
        Transformer transformer = factory.newTransformer(
                new StreamSource(new ByteArrayInputStream(
                        stylesheet.getBytes(StandardCharsets.UTF_8))));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        transformer.transform(
                new StreamSource(new ByteArrayInputStream("<x/>".getBytes(StandardCharsets.UTF_8))),
                new StreamResult(output));
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }
}

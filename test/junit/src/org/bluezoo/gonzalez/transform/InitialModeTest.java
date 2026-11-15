/*
 * InitialModeTest.java
 * Copyright (C) 2026 Chris Burdess
 */
package org.bluezoo.gonzalez.transform;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * An initial mode must be one the stylesheet mentions: by a template, by
 * an xsl:mode declaration, or as its default mode.
 */
public class InitialModeTest {

    private static final String PACKAGE_HEAD =
        "<xsl:package version='3.0' package-version='1.0.0'"
        + " xmlns:xsl='http://www.w3.org/1999/XSL/Transform'"
        + " declared-modes='no' default-mode='a'>"
        + "<xsl:output method='text'/>";

    /** The default mode names a mode that nothing else mentions. */
    @Test
    public void defaultModeIsAcceptedEvenWhenNothingElseMentionsIt()
            throws Exception {
        String stylesheet = PACKAGE_HEAD
            + "<xsl:mode/>"
            + "<xsl:template match='/' mode='#unnamed'>unnamed</xsl:template>"
            + "</xsl:package>";
        assertEquals("", transform(stylesheet, "a"));
    }

    @Test
    public void defaultModeWithTemplatesRunsThem() throws Exception {
        String stylesheet = PACKAGE_HEAD
            + "<xsl:template match='/'>in-a</xsl:template>"
            + "</xsl:package>";
        assertEquals("in-a", transform(stylesheet, "a"));
    }

    /** A mode that is not the default and is not mentioned is still an error. */
    @Test
    public void unmentionedModeIsStillAnError() throws Exception {
        String stylesheet = PACKAGE_HEAD
            + "<xsl:template match='/' mode='#unnamed'>unnamed</xsl:template>"
            + "</xsl:package>";
        try {
            transform(stylesheet, "zzz");
            fail("expected XTDE0045");
        } catch (Exception e) {
            String message = String.valueOf(e.getMessage());
            for (Throwable c = e.getCause(); c != null; c = c.getCause()) {
                message += " " + c.getMessage();
            }
            assertTrue(message, message.contains("XTDE0045"));
        }
    }

    private static String transform(String stylesheet, String mode)
            throws Exception {
        GonzalezTransformerFactory factory = new GonzalezTransformerFactory();
        GonzalezTransformer transformer = (GonzalezTransformer)
            factory.newTransformer(new StreamSource(new ByteArrayInputStream(
                stylesheet.getBytes(StandardCharsets.UTF_8))));
        transformer.setInitialMode(mode);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        transformer.transform(
            new StreamSource(new ByteArrayInputStream(
                "<x/>".getBytes(StandardCharsets.UTF_8))),
            new StreamResult(output));
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }
}

/*
 * NativeStylesheetCompileTest.java
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

public class NativeStylesheetCompileTest {

    @Test
    public void compilesNamespacedStylesheetViaNativeHandler() throws Exception {
        String stylesheet =
            "<xsl:stylesheet version='1.0'"
            + " xmlns:xsl='http://www.w3.org/1999/XSL/Transform'"
            + " xmlns:p='urn:test'>"
            + "<xsl:output method='text'/>"
            + "<xsl:template match='/p:root'>"
            + "<xsl:value-of select='p:item/@p:value'/>"
            + "</xsl:template>"
            + "</xsl:stylesheet>";
        String source =
            "<p:root xmlns:p='urn:test'><p:item p:value='ok'/></p:root>";

        assertEquals("ok", transform(stylesheet, source));
    }

    @Test
    public void evaluatesUseWhenDuringNativeCompile() throws Exception {
        String stylesheet =
            "<xsl:stylesheet version='2.0'"
            + " xmlns:xsl='http://www.w3.org/1999/XSL/Transform'>"
            + "<xsl:output method='text'/>"
            + "<xsl:template match='/' use-when='false()'>"
            + "<xsl:text>no</xsl:text>"
            + "</xsl:template>"
            + "<xsl:template match='/'>"
            + "<xsl:text>yes</xsl:text>"
            + "</xsl:template>"
            + "</xsl:stylesheet>";

        assertEquals("yes", transform(stylesheet, "<root/>"));
    }

    @Test
    public void compilesDefaultNamespaceBindings() throws Exception {
        String stylesheet =
            "<xsl:stylesheet version='1.0'"
            + " xmlns:xsl='http://www.w3.org/1999/XSL/Transform'"
            + " xmlns='urn:def'>"
            + "<xsl:output method='text'/>"
            + "<xsl:template match='/'>"
            + "<xsl:value-of select='/*[local-name()=\"root\"]/*[local-name()=\"item\"]'/>"
            + "</xsl:template>"
            + "</xsl:stylesheet>";
        String source = "<root xmlns='urn:def'><item>v</item></root>";

        assertEquals("v", transform(stylesheet, source));
    }

    private static String transform(String stylesheet, String source)
            throws Exception {
        GonzalezTransformerFactory factory = new GonzalezTransformerFactory();
        Transformer transformer = factory.newTransformer(
                new StreamSource(new ByteArrayInputStream(
                        stylesheet.getBytes(StandardCharsets.UTF_8))));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        transformer.transform(
                new StreamSource(new ByteArrayInputStream(
                        source.getBytes(StandardCharsets.UTF_8))),
                new StreamResult(output));
        return output.toString(StandardCharsets.UTF_8.name());
    }
}

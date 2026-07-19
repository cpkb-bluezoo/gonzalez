/*
 * NativeTransformInputTest.java
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

public class NativeTransformInputTest {

    @Test
    public void transformsNamespacedSourceWithoutSaxInputAdapter()
            throws Exception {
        String stylesheet =
            "<xsl:stylesheet version='1.0'"
            + " xmlns:xsl='http://www.w3.org/1999/XSL/Transform'"
            + " xmlns:p='urn:test'>"
            + "<xsl:output method='text'/>"
            + "<xsl:template match='/'>"
            + "<xsl:value-of select='/p:root/p:item/@p:value'/>"
            + "</xsl:template>"
            + "</xsl:stylesheet>";
        String source =
            "<p:root xmlns:p='urn:test'><p:item p:value='ok'/></p:root>";

        assertEquals("ok", transform(stylesheet, source));
    }

    @Test
    public void carriesDtdIdTypeInlineOnNativeAttributes() throws Exception {
        String stylesheet =
            "<xsl:stylesheet version='1.0'"
            + " xmlns:xsl='http://www.w3.org/1999/XSL/Transform'>"
            + "<xsl:output method='text'/>"
            + "<xsl:template match='/'>"
            + "<xsl:value-of select=\"id('target')/@id\"/>"
            + "</xsl:template>"
            + "</xsl:stylesheet>";
        String source =
            "<!DOCTYPE root [<!ATTLIST item id ID #REQUIRED>]>"
            + "<root><item id='target'/></root>";

        assertEquals("target", transform(stylesheet, source));
    }

    @Test
    public void preservesNativeCommentAndProcessingInstructionData()
            throws Exception {
        String stylesheet =
            "<xsl:stylesheet version='1.0'"
            + " xmlns:xsl='http://www.w3.org/1999/XSL/Transform'>"
            + "<xsl:output method='text'/>"
            + "<xsl:template match='/'>"
            + "<xsl:value-of select='/root/comment()'/>"
            + "<xsl:text>|</xsl:text>"
            + "<xsl:value-of select='/root/processing-instruction()'/>"
            + "</xsl:template>"
            + "</xsl:stylesheet>";

        assertEquals("comment|data",
                transform(stylesheet, "<root><!--comment--><?target data?></root>"));
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

/*
 * NativeEmbeddedStylesheetTest.java
 * Copyright (C) 2026 Chris Burdess
 */
package org.bluezoo.gonzalez.transform;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

import javax.xml.transform.Transformer;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;

public class NativeEmbeddedStylesheetTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void compilesEmbeddedStylesheetViaFragmentIdentifier() throws Exception {
        File wrapper = folder.newFile("wrapper.xml");
        String wrapperXml =
            "<doc>"
            + "<xsl:stylesheet id='ss' version='1.0'"
            + " xmlns:xsl='http://www.w3.org/1999/XSL/Transform'>"
            + "<xsl:output method='text'/>"
            + "<xsl:template match='/'>"
            + "<xsl:text>embedded</xsl:text>"
            + "</xsl:template>"
            + "</xsl:stylesheet>"
            + "<other/>"
            + "</doc>";
        try (FileOutputStream out = new FileOutputStream(wrapper)) {
            out.write(wrapperXml.getBytes(StandardCharsets.UTF_8));
        }

        File main = folder.newFile("main.xsl");
        String mainXml =
            "<xsl:stylesheet version='1.0'"
            + " xmlns:xsl='http://www.w3.org/1999/XSL/Transform'>"
            + "<xsl:import href='" + wrapper.toURI() + "#ss'/>"
            + "<xsl:output method='text'/>"
            + "</xsl:stylesheet>";
        try (FileOutputStream out = new FileOutputStream(main)) {
            out.write(mainXml.getBytes(StandardCharsets.UTF_8));
        }

        GonzalezTransformerFactory factory = new GonzalezTransformerFactory();
        factory.setAttribute(
                "http://javax.xml.XMLConstants/property/accessExternalStylesheet",
                "all");
        Transformer transformer = factory.newTransformer(
                new StreamSource(main));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        transformer.transform(
                new StreamSource(new ByteArrayInputStream(
                        "<root/>".getBytes(StandardCharsets.UTF_8))),
                new StreamResult(output));
        assertEquals("embedded", output.toString(StandardCharsets.UTF_8.name()));
    }
}

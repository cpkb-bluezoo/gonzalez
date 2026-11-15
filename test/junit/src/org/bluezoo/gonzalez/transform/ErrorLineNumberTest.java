/*
 * ErrorLineNumberTest.java
 * Copyright (C) 2026 Chris Burdess
 */
package org.bluezoo.gonzalez.transform;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;

import javax.xml.transform.Transformer;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * The err:line-number that xsl:catch sees is the line of the stylesheet
 * instruction that raised the error.
 */
public class ErrorLineNumberTest {

    @Test
    public void lineOfTheInstructionThatRaisedTheError() throws Exception {
        File dir = File.createTempFile("errline", "");
        dir.delete();
        dir.mkdirs();
        dir.deleteOnExit();
        String href = new File(dir, "twice.out").toURI().toString();

        // xsl:result-document is on line 7. Its second use of the same URI is XTDE1490.
        String stylesheet =
            "<xsl:stylesheet version='3.0'\n"
            + "    xmlns:xsl='http://www.w3.org/1999/XSL/Transform'\n"
            + "    xmlns:err='http://www.w3.org/2005/xqt-errors'>\n"
            + "  <xsl:output method='text'/>\n"
            + "  <xsl:template name='main'>\n"
            + "    <xsl:try><xsl:for-each select='1 to 2'>\n"
            + "        <xsl:result-document href='" + href + "'><x/></xsl:result-document>\n"
            + "      </xsl:for-each>\n"
            + "      <xsl:catch errors='*'>\n"
            + "        <xsl:value-of select=\"$err:code || ' line ' || $err:line-number\"/>\n"
            + "      </xsl:catch>\n"
            + "    </xsl:try>\n"
            + "  </xsl:template>\n"
            + "</xsl:stylesheet>";

        GonzalezTransformerFactory factory = new GonzalezTransformerFactory();
        GonzalezTransformer transformer = (GonzalezTransformer) factory.newTransformer(
            new StreamSource(new ByteArrayInputStream(
                stylesheet.getBytes(StandardCharsets.UTF_8))));
        transformer.setInitialTemplate("main");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        transformer.transform(
            new StreamSource(new ByteArrayInputStream(
                "<x/>".getBytes(StandardCharsets.UTF_8))),
            new StreamResult(output));
        assertEquals("err:XTDE1490 line 7",
            new String(output.toByteArray(), StandardCharsets.UTF_8).trim());
    }
}

/*
 * ExternalEntityBaseUriTest.java
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

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * The base URI of a node that comes from an external parsed entity is the
 * system identifier of that entity, not of the document that refers to it.
 */
public class ExternalEntityBaseUriTest {

    /** Names the file part of base-uri() of the node each path selects. */
    private static final String STYLESHEET =
        "<xsl:stylesheet version='2.0'"
        + " xmlns:xsl='http://www.w3.org/1999/XSL/Transform'>"
        + "<xsl:output method='text'/>"
        + "<xsl:template match='/'>"
        + "<xsl:apply-templates select='test'/>"
        + "</xsl:template>"
        + "<xsl:template match='test'>"
        + "<xsl:value-of select=\"tokenize(base-uri(.), '/')[last()]\"/>"
        + "<xsl:text>|</xsl:text>"
        + "<xsl:value-of select=\"tokenize(base-uri(*[1]), '/')[last()]\"/>"
        + "<xsl:text>|</xsl:text>"
        + "<xsl:value-of select=\"tokenize(base-uri(*[1]/*), '/')[last()]\"/>"
        + "<xsl:text>|</xsl:text>"
        + "<xsl:value-of select=\"tokenize(base-uri(processing-instruction()), '/')[last()]\"/>"
        + "<xsl:text>|</xsl:text>"
        + "<xsl:value-of select=\"tokenize(base-uri(*[last()]), '/')[last()]\"/>"
        + "</xsl:template>"
        + "</xsl:stylesheet>";

    @Test
    public void elementAndInstructionTakeTheirEntityBase() throws Exception {
        File dir = tempDir();
        File sub = new File(dir, "level1");
        sub.mkdirs();
        write(new File(sub, "element.xml"),
                "<?xml version='1.0' encoding='UTF-8'?>\n<ext attr='x'><s>z</s></ext>");
        write(new File(sub, "pi.xml"),
                "<?xml version='1.0' encoding='UTF-8'?>\n<?pi data?>");
        File main = new File(dir, "main.xml");
        write(main,
                "<?xml version='1.0'?>\n"
                + "<!DOCTYPE test [\n"
                + "  <!ELEMENT test ANY>\n"
                + "  <!ENTITY extElement SYSTEM 'level1/element.xml'>\n"
                + "  <!ENTITY extPI SYSTEM 'level1/pi.xml'>\n"
                + "]>\n"
                + "<test>&extElement;&extPI;<after/></test>");

        // document | entity element | its child | entity PI | element after the entities
        assertEquals("main.xml|element.xml|element.xml|pi.xml|main.xml",
                transform(main));
    }

    /**
     * A relative system identifier in an entity declaration is relative to
     * the file that holds the declaration, here an external DTD subset in
     * another directory, not to the document that refers to the entity.
     */
    @Test
    public void entityDeclaredInExternalSubsetResolvesAgainstTheSubset()
            throws Exception {
        File dir = tempDir();
        File sub = new File(dir, "x");
        sub.mkdirs();
        write(new File(sub, "ent.dtd"),
                "<!ENTITY child SYSTEM 'part.xml'>");
        write(new File(sub, "part.xml"), "<inner><deep/></inner>");
        File main = new File(dir, "main.xml");
        write(main,
                "<?xml version='1.0'?>\n"
                + "<!DOCTYPE test SYSTEM 'x/ent.dtd'>\n"
                + "<test>&child;</test>");

        // document | entity element | its child | no PI | last child of test
        assertEquals("main.xml|part.xml|part.xml||part.xml", transform(main));
    }

    /**
     * An entity declared in the internal subset is relative to the document.
     */
    @Test
    public void entityDeclaredInInternalSubsetResolvesAgainstTheDocument()
            throws Exception {
        File dir = tempDir();
        write(new File(dir, "part.xml"), "<inner><deep/></inner>");
        File main = new File(dir, "main.xml");
        write(main,
                "<?xml version='1.0'?>\n"
                + "<!DOCTYPE test [<!ENTITY child SYSTEM 'part.xml'>]>\n"
                + "<test>&child;</test>");

        assertEquals("main.xml|part.xml|part.xml||part.xml", transform(main));
    }

    @Test
    public void nodeOutsideAnyEntityKeepsTheDocumentBase() throws Exception {
        File dir = tempDir();
        File main = new File(dir, "plain.xml");
        write(main, "<test><child><g/></child></test>");

        assertEquals("plain.xml|plain.xml|plain.xml||plain.xml", transform(main));
    }

    private static File tempDir() throws Exception {
        File dir = File.createTempFile("entbase", "");
        dir.delete();
        dir.mkdirs();
        dir.deleteOnExit();
        return dir;
    }

    private static void write(File file, String content) throws Exception {
        FileOutputStream out = new FileOutputStream(file);
        try {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        } finally {
            out.close();
        }
        file.deleteOnExit();
    }

    private static String transform(File source) throws Exception {
        GonzalezTransformerFactory factory = new GonzalezTransformerFactory();
        factory.setAttribute(
                "http://javax.xml.XMLConstants/property/accessExternalDTD", "file");
        Transformer transformer = factory.newTransformer(
                new StreamSource(new ByteArrayInputStream(
                        STYLESHEET.getBytes(StandardCharsets.UTF_8))));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        transformer.transform(new StreamSource(source), new StreamResult(output));
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }
}

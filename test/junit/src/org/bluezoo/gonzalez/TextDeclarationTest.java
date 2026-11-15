/*
 * TextDeclarationTest.java
 * Copyright (C) 2026 Chris Burdess
 */
package org.bluezoo.gonzalez;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

import org.junit.Test;
import org.xml.sax.InputSource;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * XML 4.3.1: {@code TextDecl ::= '<?xml' VersionInfo? EncodingDecl S? '?>'}.
 * The text declaration that may begin an external entity or an external
 * DTD subset must therefore give an encoding, unlike the XML declaration
 * of the document, which need not.
 */
public class TextDeclarationTest {

    private static final String WITH_ENCODING = "<?xml version='1.0' encoding='UTF-8'?>";
    private static final String ENCODING_ONLY = "<?xml encoding='UTF-8'?>";
    private static final String VERSION_ONLY = "<?xml version='1.0'?>";

    /** Parses main.xml in a fresh directory holding the given files. */
    private static void parse(String main, String... nameAndContent) throws Exception {
        File dir = File.createTempFile("textdecl", "");
        dir.delete();
        dir.mkdirs();
        dir.deleteOnExit();
        for (int i = 0; i < nameAndContent.length; i += 2) {
            write(new File(dir, nameAndContent[i]), nameAndContent[i + 1]);
        }
        File file = new File(dir, "main.xml");
        write(file, main);
        Parser parser = new Parser();
        parser.setFeature("http://xml.org/sax/features/external-general-entities", true);
        parser.setFeature("http://xml.org/sax/features/external-parameter-entities", true);
        parser.setProperty("http://javax.xml.XMLConstants/property/accessExternalDTD", "file");
        parser.setContentHandler(new DefaultHandler());
        parser.parse(new InputSource(file.toURI().toString()));
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

    private static void assertNotWellFormed(String main, String... files) throws Exception {
        try {
            parse(main, files);
            fail("expected a fatal error");
        } catch (SAXParseException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("encoding"));
        }
    }

    private static final String DOC_WITH_ENTITY =
        "<!DOCTYPE d [<!ENTITY e SYSTEM 'part.ent'>]><d>&e;</d>";
    private static final String DOC_WITH_SUBSET =
        "<!DOCTYPE d SYSTEM 'sub.dtd'><d/>";
    private static final String DOC_WITH_PE =
        "<!DOCTYPE d [<!ENTITY % p SYSTEM 'p.ent'> %p;]><d/>";

    @Test
    public void generalEntityWithoutEncodingIsNotWellFormed() throws Exception {
        assertNotWellFormed(DOC_WITH_ENTITY, "part.ent", VERSION_ONLY + "<x/>");
    }

    @Test
    public void externalSubsetWithoutEncodingIsNotWellFormed() throws Exception {
        assertNotWellFormed(DOC_WITH_SUBSET, "sub.dtd", VERSION_ONLY + "<!ELEMENT d EMPTY>");
    }

    @Test
    public void externalParameterEntityWithoutEncodingIsNotWellFormed() throws Exception {
        assertNotWellFormed(DOC_WITH_PE, "p.ent", VERSION_ONLY + "<!ENTITY a 'b'>");
    }

    @Test
    public void entityWithEncodingIsAccepted() throws Exception {
        parse(DOC_WITH_ENTITY, "part.ent", WITH_ENCODING + "<x/>");
        parse(DOC_WITH_ENTITY, "part.ent", ENCODING_ONLY + "<x/>");
    }

    @Test
    public void externalSubsetWithEncodingIsAccepted() throws Exception {
        parse(DOC_WITH_SUBSET, "sub.dtd", WITH_ENCODING + "<!ELEMENT d EMPTY>");
        parse(DOC_WITH_SUBSET, "sub.dtd", ENCODING_ONLY + "<!ELEMENT d EMPTY>");
    }

    @Test
    public void externalParameterEntityWithEncodingIsAccepted() throws Exception {
        parse(DOC_WITH_PE, "p.ent", WITH_ENCODING + "<!ENTITY a 'b'>");
    }

    @Test
    public void noTextDeclarationAtAllIsAccepted() throws Exception {
        parse(DOC_WITH_ENTITY, "part.ent", "<x/>");
        parse(DOC_WITH_SUBSET, "sub.dtd", "<!ELEMENT d EMPTY>");
        parse(DOC_WITH_PE, "p.ent", "<!ENTITY a 'b'>");
    }

    /** The document's own XML declaration may leave the encoding out. */
    @Test
    public void documentDeclarationNeedsNoEncoding() throws Exception {
        parse(VERSION_ONLY + "<d/>");
        assertEquals(1, 1);
    }
}

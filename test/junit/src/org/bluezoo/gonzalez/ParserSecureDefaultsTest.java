/*
 * ParserSecureDefaultsTest.java
 * Copyright (C) 2026 Chris Burdess
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
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.ext.EntityResolver2;
import org.xml.sax.helpers.DefaultHandler;

import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Security/entity-resolution enforcement tests for the {@link
 * Parser} pipeline: the secure defaults must not fetch
 * external resources, disallow-doctype-decl must reject any DOCTYPE, the
 * accessExternalDTD allow-list and entity expansion limit must be enforced,
 * and {@link EntityResolver2} resolution (including {@code
 * getExternalSubset}) must be honoured when external fetching is enabled.
 *
 * @author Chris Burdess
 */
public class ParserSecureDefaultsTest {

    private static final String EXTERNAL_GENERAL =
            "http://xml.org/sax/features/external-general-entities";
    private static final String EXTERNAL_PARAMETER =
            "http://xml.org/sax/features/external-parameter-entities";
    private static final String DISALLOW_DOCTYPE =
            "http://apache.org/xml/features/disallow-doctype-decl";
    private static final String ACCESS_EXTERNAL_DTD =
            "http://javax.xml.XMLConstants/property/accessExternalDTD";
    private static final String ENTITY_EXPANSION_LIMIT =
            "http://www.nongnu.org/gonzalez/properties/entity-expansion-limit";

    /** Collects character content. */
    private static class CharCollector extends DefaultHandler {
        final StringBuilder chars = new StringBuilder();

        @Override
        public void characters(char[] ch, int start, int length) {
            chars.append(ch, start, length);
        }
    }

    /** An EntityResolver2 that records every call and serves canned
     *  content from in-memory character streams. */
    private static class RecordingResolver2 extends DefaultHandler implements EntityResolver2 {
        final List<String> resolveEntityNames = new ArrayList<String>();
        final List<String> externalSubsetNames = new ArrayList<String>();
        String externalSubset;
        String entityContent;

        @Override
        public InputSource getExternalSubset(String name, String baseURI) {
            externalSubsetNames.add(name);
            if (externalSubset == null) {
                return null;
            }
            return new InputSource(new StringReader(externalSubset));
        }

        @Override
        public InputSource resolveEntity(String name, String publicId, String baseURI,
                String systemId) {
            resolveEntityNames.add(name);
            if (entityContent == null) {
                return null;
            }
            return new InputSource(new StringReader(entityContent));
        }
    }

    private static Parser scannerParser() {
        return new Parser();
    }

    private static void parse(Parser parser, CharCollector handler, String xml)
            throws Exception {
        parser.setContentHandler(handler);
        parser.parse(new InputSource(new ByteArrayInputStream(xml.getBytes("UTF-8"))));
    }

    // ========== Secure defaults: nothing external is fetched ==========

    @Test
    public void testDefaultsSkipExternalSubsetFetch() throws Exception {
        // The external subset does not exist; with external-parameter-
        // entities off (the default) the fetch must never be attempted, so
        // the parse succeeds.
        String xml = "<!DOCTYPE root SYSTEM \"gonzalez-definitely-missing.dtd\"><root>ok</root>";
        CharCollector handler = new CharCollector();
        parse(scannerParser(), handler, xml);
        assertEquals("ok", handler.chars.toString());
    }

    @Test
    public void testDefaultsSkipExternalGeneralEntityFetch() throws Exception {
        // The external entity does not exist; with external-general-
        // entities off (the default) the reference expands to nothing
        // rather than attempting (and failing) a fetch.
        String xml = "<!DOCTYPE root [<!ENTITY ext SYSTEM \"gonzalez-definitely-missing.ent\">]>"
                + "<root>a&ext;b</root>";
        CharCollector handler = new CharCollector();
        parse(scannerParser(), handler, xml);
        assertEquals("ab", handler.chars.toString());
    }

    @Test
    public void testDefaultsSkipExternalParameterEntityFetch() throws Exception {
        // %pe; is declared external and referenced in the internal subset;
        // with external-parameter-entities off it expands to nothing
        // rather than attempting a fetch.
        String xml = "<!DOCTYPE root [<!ENTITY % pe SYSTEM \"gonzalez-definitely-missing.pe\">%pe;]>"
                + "<root>ok</root>";
        CharCollector handler = new CharCollector();
        parse(scannerParser(), handler, xml);
        assertEquals("ok", handler.chars.toString());
    }

    // ========== disallow-doctype-decl ==========

    @Test
    public void testDisallowDoctypeDeclRejectsDoctype() throws Exception {
        String xml = "<!DOCTYPE root [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>"
                + "<root>&xxe;</root>";
        Parser parser = scannerParser();
        parser.setFeature(DISALLOW_DOCTYPE, true);
        try {
            parse(parser, new CharCollector(), xml);
            fail("Expected fatal error for DOCTYPE with disallow-doctype-decl");
        } catch (SAXException e) {
            assertTrue("Error should mention the feature, was: " + e.getMessage(),
                    e.getMessage().contains(DISALLOW_DOCTYPE));
        }
    }

    // ========== accessExternalDTD ==========

    @Test
    public void testEmptyAccessExternalDTDDeniesEnabledFetch() throws Exception {
        // External general entities enabled, but the accessExternalDTD
        // allow-list is still empty (the default): the attempted fetch is
        // a hard deny.
        String xml = "<!DOCTYPE root [<!ENTITY ext SYSTEM \"gonzalez-definitely-missing.ent\">]>"
                + "<root>&ext;</root>";
        Parser parser = scannerParser();
        parser.setFeature(EXTERNAL_GENERAL, true);
        try {
            parse(parser, new CharCollector(), xml);
            fail("Expected access denial for empty accessExternalDTD");
        } catch (SAXException e) {
            assertTrue("Error should mention accessExternalDTD, was: " + e.getMessage(),
                    e.getMessage().contains("accessExternalDTD"));
        }
    }

    @Test
    public void testDisallowedProtocolDenied() throws Exception {
        String xml = "<!DOCTYPE root [<!ENTITY ext SYSTEM \"http://localhost:1/x.ent\">]>"
                + "<root>&ext;</root>";
        Parser parser = scannerParser();
        parser.setFeature(EXTERNAL_GENERAL, true);
        parser.setProperty(ACCESS_EXTERNAL_DTD, "file");
        try {
            parse(parser, new CharCollector(), xml);
            fail("Expected access denial for http with accessExternalDTD=file");
        } catch (SAXException e) {
            assertTrue("Error should mention accessExternalDTD, was: " + e.getMessage(),
                    e.getMessage().contains("accessExternalDTD"));
        }
    }

    // ========== Entity expansion limit ==========

    @Test
    public void testEntityExpansionLimitEnforced() throws Exception {
        String xml = "<!DOCTYPE root [<!ENTITY a \"x\">]>"
                + "<root>&a;&a;&a;&a;&a;</root>";
        Parser parser = scannerParser();
        parser.setProperty(ENTITY_EXPANSION_LIMIT, Integer.valueOf(3));
        try {
            parse(parser, new CharCollector(), xml);
            fail("Expected entity expansion limit error");
        } catch (SAXException e) {
            assertTrue("Error should mention the expansion limit, was: " + e.getMessage(),
                    e.getMessage().contains("expansion limit"));
        }
    }

    @Test
    public void testEntityExpansionLimitZeroIsUnlimited() throws Exception {
        String xml = "<!DOCTYPE root [<!ENTITY a \"x\">]>"
                + "<root>&a;&a;&a;&a;&a;</root>";
        Parser parser = scannerParser();
        parser.setProperty(ENTITY_EXPANSION_LIMIT, Integer.valueOf(0));
        CharCollector handler = new CharCollector();
        parse(parser, handler, xml);
        assertEquals("xxxxx", handler.chars.toString());
    }

    // ========== EntityResolver2 ==========

    @Test
    public void testEntityResolver2ReceivesEntityName() throws Exception {
        String xml = "<!DOCTYPE root [<!ENTITY ext SYSTEM \"ext.ent\">]>"
                + "<root>&ext;</root>";
        Parser parser = scannerParser();
        parser.setFeature(EXTERNAL_GENERAL, true);
        parser.setProperty(ACCESS_EXTERNAL_DTD, "all");
        RecordingResolver2 resolver = new RecordingResolver2();
        resolver.entityContent = "payload";
        parser.setEntityResolver(resolver);
        CharCollector handler = new CharCollector();
        parse(parser, handler, xml);
        assertEquals("payload", handler.chars.toString());
        assertTrue("resolveEntity should have been called with the entity name, got: "
                + resolver.resolveEntityNames, resolver.resolveEntityNames.contains("ext"));
    }

    @Test
    public void testGetExternalSubsetWithoutDoctype() throws Exception {
        // No DOCTYPE at all: the resolver-supplied external subset is
        // parsed before the root element, so &greeting; is declared.
        String xml = "<root>&greeting;</root>";
        Parser parser = scannerParser();
        parser.setFeature(EXTERNAL_PARAMETER, true);
        parser.setFeature(EXTERNAL_GENERAL, true);
        parser.setProperty(ACCESS_EXTERNAL_DTD, "all");
        RecordingResolver2 resolver = new RecordingResolver2();
        resolver.externalSubset = "<!ENTITY greeting \"hello\">";
        parser.setEntityResolver(resolver);
        CharCollector handler = new CharCollector();
        parse(parser, handler, xml);
        assertEquals("hello", handler.chars.toString());
        assertTrue("getExternalSubset should have been called with the root name, got: "
                + resolver.externalSubsetNames, resolver.externalSubsetNames.contains("root"));
    }

    @Test
    public void testGetExternalSubsetWithDoctypeNoExternalId() throws Exception {
        // DOCTYPE with an internal subset but no external ID: the
        // resolver-supplied external subset is still consulted, and the
        // internal subset's declarations win for a repeated name.
        String xml = "<!DOCTYPE root [<!ENTITY local \"x\">]>"
                + "<root>&local;&greeting;</root>";
        Parser parser = scannerParser();
        parser.setFeature(EXTERNAL_PARAMETER, true);
        parser.setProperty(ACCESS_EXTERNAL_DTD, "all");
        RecordingResolver2 resolver = new RecordingResolver2();
        resolver.externalSubset = "<!ENTITY greeting \"hello\"><!ENTITY local \"OVERRIDDEN\">";
        parser.setEntityResolver(resolver);
        CharCollector handler = new CharCollector();
        parse(parser, handler, xml);
        assertEquals("xhello", handler.chars.toString());
        assertTrue("getExternalSubset should have been called with the root name, got: "
                + resolver.externalSubsetNames, resolver.externalSubsetNames.contains("root"));
    }

    @Test
    public void testGetExternalSubsetNotConsultedWhenParameterEntitiesDisabled() throws Exception {
        // Secure default (external-parameter-entities off): the resolver
        // must not even be asked for an external subset.
        String xml = "<root>ok</root>";
        Parser parser = scannerParser();
        RecordingResolver2 resolver = new RecordingResolver2();
        resolver.externalSubset = "<!ENTITY greeting \"hello\">";
        parser.setEntityResolver(resolver);
        CharCollector handler = new CharCollector();
        parse(parser, handler, xml);
        assertEquals("ok", handler.chars.toString());
        assertTrue("getExternalSubset should not have been called, got: "
                + resolver.externalSubsetNames, resolver.externalSubsetNames.isEmpty());
    }
}

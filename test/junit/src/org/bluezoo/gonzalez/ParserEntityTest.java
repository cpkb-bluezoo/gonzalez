/*
 * ParserEntityTest.java
 * Copyright (C) 2025 Chris Burdess
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
import org.xml.sax.ContentHandler;
import org.xml.sax.InputSource;
import org.xml.sax.Locator;
import org.xml.sax.SAXParseException;

import java.io.ByteArrayInputStream;

import static org.junit.Assert.*;

/**
 * Unit tests for entity reference handling through the Parser.
 *
 * @author Chris Burdess
 */
public class ParserEntityTest {

    private static final String DTD_PARSER_PROPERTY = "http://www.nongnu.org/gonzalez/properties/dtd-parser";

    private void parse(String xml, ContentHandler handler) throws Exception {
        Parser parser = new Parser();
        parser.setContentHandler(handler);
        ByteArrayInputStream bais = new ByteArrayInputStream(xml.getBytes("UTF-8"));
        InputSource source = new InputSource(bais);
        parser.parse(source);
    }

    private DTDParser getDTDParser(Parser parser) throws Exception {
        Object prop = parser.getProperty(DTD_PARSER_PROPERTY);
        return (DTDParser) prop;
    }

    // ========== EntityRefInContentTest ==========

    @Test
    public void testSimpleEntityRefInContent() throws Exception {
        String xml = "<?xml version='1.0'?>\n" +
                    "<!DOCTYPE root [\n" +
                    "  <!ENTITY copy \"Copyright 2025\">\n" +
                    "]>\n" +
                    "<root>&copy;</root>";

        ContentCapture handler = new ContentCapture();
        parse(xml, handler);

        String content = handler.getContent();
        assertEquals("Copyright 2025", content);
    }

    @Test
    public void testEntityWithNestedRefInContent() throws Exception {
        String xml = "<?xml version='1.0'?>\n" +
                    "<!DOCTYPE root [\n" +
                    "  <!ENTITY inner \"INNER\">\n" +
                    "  <!ENTITY outer \"before &inner; after\">\n" +
                    "]>\n" +
                    "<root>&outer;</root>";

        ContentCapture handler = new ContentCapture();
        parse(xml, handler);

        String content = handler.getContent();
        assertEquals("before INNER after", content);
    }

    @Test
    public void testMultipleEntitiesInContent() throws Exception {
        String xml = "<?xml version='1.0'?>\n" +
                    "<!DOCTYPE root [\n" +
                    "  <!ENTITY first \"FIRST\">\n" +
                    "  <!ENTITY second \"SECOND\">\n" +
                    "  <!ENTITY third \"THIRD\">\n" +
                    "]>\n" +
                    "<root>&first; &second; &third;</root>";

        ContentCapture handler = new ContentCapture();
        parse(xml, handler);

        String content = handler.getContent();
        assertEquals("FIRST SECOND THIRD", content);
    }

    @Test(expected = SAXParseException.class)
    public void testCircularReferenceInContent() throws Exception {
        String xml = "<?xml version='1.0'?>\n" +
                    "<!DOCTYPE root [\n" +
                    "  <!ENTITY a \"before &b; after\">\n" +
                    "  <!ENTITY b \"before &a; after\">\n" +
                    "]>\n" +
                    "<root>&a;</root>";

        ContentCapture handler = new ContentCapture();
        parse(xml, handler);
    }

    @Test(expected = SAXParseException.class)
    public void testUndefinedEntityInContent() throws Exception {
        String xml = "<?xml version='1.0'?>\n" +
                    "<!DOCTYPE root []>\n" +
                    "<root>&undefined;</root>";

        ContentCapture handler = new ContentCapture();
        parse(xml, handler);
    }

    @Test(expected = SAXParseException.class)
    public void testUnparsedEntityForbiddenInContent() throws Exception {
        String xml = "<?xml version='1.0'?>\n" +
                    "<!DOCTYPE root [\n" +
                    "  <!NOTATION gif SYSTEM \"image/gif\">\n" +
                    "  <!ENTITY logo SYSTEM \"logo.gif\" NDATA gif>\n" +
                    "]>\n" +
                    "<root>&logo;</root>";

        ContentCapture handler = new ContentCapture();
        parse(xml, handler);
    }

    @Test
    public void testExternalEntitySkippedWhenDisabled() throws Exception {
        String xml = "<?xml version='1.0'?>\n" +
                    "<!DOCTYPE root [\n" +
                    "  <!ENTITY external SYSTEM \"external.xml\">\n" +
                    "]>\n" +
                    "<root>&external;</root>";

        ContentCapture handler = new ContentCapture();
        parse(xml, handler);
        // External entities are disabled by default, so &external; is silently skipped
    }

    // ========== EntityRefInAttributeTest ==========

    @Test
    public void testSimpleEntityRefInAttribute() throws Exception {
        String xml = "<?xml version='1.0'?>\n" +
                    "<!DOCTYPE root [\n" +
                    "  <!ENTITY copy \"Copyright 2025\">\n" +
                    "]>\n" +
                    "<root attr='&copy;'/>";

        AttributeCapture handler = new AttributeCapture();
        parse(xml, handler);

        String value = handler.getValue("attr");
        assertNotNull(value);
        assertEquals("Copyright 2025", value);
    }

    @Test
    public void testEntityWithNestedRefInAttribute() throws Exception {
        String xml = "<?xml version='1.0'?>\n" +
                    "<!DOCTYPE root [\n" +
                    "  <!ENTITY inner \"INNER\">\n" +
                    "  <!ENTITY outer \"before &inner; after\">\n" +
                    "]>\n" +
                    "<root attr='[&outer;]'/>";

        AttributeCapture handler = new AttributeCapture();
        parse(xml, handler);

        String value = handler.getValue("attr");
        assertNotNull(value);
        assertEquals("[before INNER after]", value);
    }

    @Test(expected = SAXParseException.class)
    public void testCircularReferenceInAttribute() throws Exception {
        String xml = "<?xml version='1.0'?>\n" +
                    "<!DOCTYPE root [\n" +
                    "  <!ENTITY a \"before &b; after\">\n" +
                    "  <!ENTITY b \"before &a; after\">\n" +
                    "]>\n" +
                    "<root attr='&a;'/>";

        AttributeCapture handler = new AttributeCapture();
        parse(xml, handler);
    }

    @Test(expected = SAXParseException.class)
    public void testUndefinedEntityInAttribute() throws Exception {
        String xml = "<?xml version='1.0'?>\n" +
                    "<!DOCTYPE root []>\n" +
                    "<root attr='&undefined;'/>";

        AttributeCapture handler = new AttributeCapture();
        parse(xml, handler);
    }

    @Test(expected = SAXParseException.class)
    public void testExternalEntityForbiddenInAttribute() throws Exception {
        String xml = "<?xml version='1.0'?>\n" +
                    "<!DOCTYPE root [\n" +
                    "  <!ENTITY external SYSTEM \"external.xml\">\n" +
                    "]>\n" +
                    "<root attr='&external;'/>";

        AttributeCapture handler = new AttributeCapture();
        parse(xml, handler);
    }

    @Test(expected = SAXParseException.class)
    public void testUnparsedEntityForbiddenInAttribute() throws Exception {
        String xml = "<?xml version='1.0'?>\n" +
                    "<!DOCTYPE root [\n" +
                    "  <!NOTATION gif SYSTEM \"image/gif\">\n" +
                    "  <!ENTITY logo SYSTEM \"logo.gif\" NDATA gif>\n" +
                    "]>\n" +
                    "<root attr='&logo;'/>";

        AttributeCapture handler = new AttributeCapture();
        parse(xml, handler);
    }

    // ========== EntityAndNormalizationTest ==========

    @Test
    public void testEntityWithWhitespace() throws Exception {
        String xml = "<?xml version='1.0'?>\n" +
                    "<!DOCTYPE root [\n" +
                    "  <!ENTITY text \"value\twith\ttabs\">\n" +
                    "]>\n" +
                    "<root attr='before &text; after'/>";

        AttributeCapture handler = new AttributeCapture();
        parse(xml, handler);

        String value = handler.getValue("attr");
        assertNotNull(value);
        assertEquals("before value with tabs after", value);
    }

    @Test
    public void testEntityInNonCDATAAttribute() throws Exception {
        String xml = "<?xml version='1.0'?>\n" +
                    "<!DOCTYPE root [\n" +
                    "  <!ENTITY myid \"abc123\">\n" +
                    "  <!ATTLIST root id ID #REQUIRED>\n" +
                    "]>\n" +
                    "<root id='  &myid;  '/>\n";

        AttributeCapture handler = new AttributeCapture();
        parse(xml, handler);

        String value = handler.getValue("id");
        assertNotNull(value);
        assertEquals("abc123", value);
    }

    @Test
    public void testMultipleEntitiesWithNormalization() throws Exception {
        String xml = "<?xml version='1.0'?>\n" +
                    "<!DOCTYPE root [\n" +
                    "  <!ATTLIST root\n" +
                    "    tokens NMTOKENS #IMPLIED\n" +
                    "  >\n" +
                    "  <!ENTITY a \"tok1\">\n" +
                    "  <!ENTITY b \"tok2\">\n" +
                    "  <!ENTITY c \"tok3\">\n" +
                    "]>\n" +
                    "<root tokens='  &a;   &b;  &c;  '/>";

        AttributeCapture handler = new AttributeCapture();
        parse(xml, handler);

        String value = handler.getValue("tokens");
        assertNotNull(value);
        assertEquals("tok1 tok2 tok3", value);
    }

    // ========== EntityDeclTest ==========

    @Test
    public void testInternalGeneralEntity() throws Exception {
        String xml = "<?xml version='1.0'?>\n" +
                    "<!DOCTYPE root [\n" +
                    "  <!ENTITY copy \"Copyright 2025\">\n" +
                    "]>\n" +
                    "<root/>";

        Parser parser = new Parser();
        ByteArrayInputStream in = new ByteArrayInputStream(xml.getBytes("UTF-8"));
        parser.parse(new InputSource(in));

        DTDParser dtdParser = getDTDParser(parser);
        EntityDeclaration entity = dtdParser.getGeneralEntity("copy");
        assertNotNull(entity);
        assertFalse(entity.isParameter);
        assertTrue(entity.isInternal());
        assertNotNull(entity.replacementText);
        assertEquals(1, entity.replacementText.size());
        assertEquals("Copyright 2025", entity.replacementText.get(0));
    }

    @Test
    public void testEntityWithReferences() throws Exception {
        String xml = "<?xml version='1.0'?>\n" +
                    "<!DOCTYPE root [\n" +
                    "  <!ENTITY combined \"before &middle; after\">\n" +
                    "]>\n" +
                    "<root/>";

        Parser parser = new Parser();
        ByteArrayInputStream in = new ByteArrayInputStream(xml.getBytes("UTF-8"));
        parser.parse(new InputSource(in));

        DTDParser dtdParser = getDTDParser(parser);
        EntityDeclaration entity = dtdParser.getGeneralEntity("combined");
        assertNotNull(entity);
        assertEquals(3, entity.replacementText.size());

        Object part0 = entity.replacementText.get(0);
        Object part1 = entity.replacementText.get(1);
        Object part2 = entity.replacementText.get(2);

        assertTrue(part0 instanceof String);
        assertEquals("before ", part0);
        assertTrue(part1 instanceof GeneralEntityReference);
        GeneralEntityReference ref1 = (GeneralEntityReference) part1;
        assertEquals("middle", ref1.name);
        assertTrue(part2 instanceof String);
        assertEquals(" after", part2);
    }

    @Test
    public void testExternalParsedEntity() throws Exception {
        String xml = "<?xml version='1.0'?>\n" +
                    "<!DOCTYPE root [\n" +
                    "  <!ENTITY chapter SYSTEM \"chapter1.xml\">\n" +
                    "]>\n" +
                    "<root/>";

        Parser parser = new Parser();
        ByteArrayInputStream in = new ByteArrayInputStream(xml.getBytes("UTF-8"));
        parser.parse(new InputSource(in));

        DTDParser dtdParser = getDTDParser(parser);
        EntityDeclaration entity = dtdParser.getGeneralEntity("chapter");
        assertNotNull(entity);
        assertFalse(entity.isParameter);
        assertTrue(entity.isExternal());
        assertTrue(entity.isParsed());
        assertFalse(entity.isUnparsed());
        assertNotNull(entity.externalID);
        assertTrue(entity.externalID.systemId.contains("chapter1"));
    }

    @Test
    public void testExternalUnparsedEntity() throws Exception {
        String xml = "<?xml version='1.0'?>\n" +
                    "<!DOCTYPE root [\n" +
                    "  <!NOTATION gif SYSTEM \"image-gif\">\n" +
                    "  <!ENTITY logo SYSTEM \"logo.gif\" NDATA gif>\n" +
                    "]>\n" +
                    "<root/>";

        Parser parser = new Parser();
        ByteArrayInputStream in = new ByteArrayInputStream(xml.getBytes("UTF-8"));
        parser.parse(new InputSource(in));

        DTDParser dtdParser = getDTDParser(parser);
        EntityDeclaration entity = dtdParser.getGeneralEntity("logo");
        assertNotNull(entity);
        assertFalse(entity.isParameter);
        assertTrue(entity.isExternal());
        assertFalse(entity.isParsed());
        assertTrue(entity.isUnparsed());
        assertEquals("gif", entity.notationName);
    }

    @Test
    public void testParameterEntity() throws Exception {
        String xml = "<?xml version='1.0'?>\n" +
                    "<!DOCTYPE root [\n" +
                    "  <!ENTITY % common \"value\">\n" +
                    "]>\n" +
                    "<root/>";

        Parser parser = new Parser();
        ByteArrayInputStream in = new ByteArrayInputStream(xml.getBytes("UTF-8"));
        parser.parse(new InputSource(in));

        DTDParser dtdParser = getDTDParser(parser);
        EntityDeclaration entity = dtdParser.getParameterEntity("common");
        assertNotNull(entity);
        assertTrue(entity.isParameter);
        assertTrue(entity.isInternal());
        assertEquals(1, entity.replacementText.size());
        assertEquals("value", entity.replacementText.get(0));
    }

    // ========== Helper classes ==========

    /**
     * ContentHandler that captures character content.
     */
    private static class ContentCapture implements ContentHandler {
        private StringBuilder content = new StringBuilder();

        String getContent() {
            return content.toString();
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            content.append(ch, start, length);
        }

        @Override
        public void setDocumentLocator(Locator locator) {
        }

        @Override
        public void startDocument() {
        }

        @Override
        public void endDocument() {
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes atts) {
        }

        @Override
        public void endElement(String uri, String localName, String qName) {
        }

        @Override
        public void ignorableWhitespace(char[] ch, int start, int length) {
        }

        @Override
        public void processingInstruction(String target, String data) {
        }

        @Override
        public void startPrefixMapping(String prefix, String uri) {
        }

        @Override
        public void endPrefixMapping(String prefix) {
        }

        @Override
        public void skippedEntity(String name) {
        }
    }

    /**
     * ContentHandler that captures attribute values by name.
     */
    private static class AttributeCapture implements ContentHandler {
        private String attributeValue;

        String getValue(String name) {
            return attributeValue;
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes atts) {
            if (atts.getLength() > 0) {
                attributeValue = atts.getValue(0);
            }
        }

        @Override
        public void setDocumentLocator(Locator locator) {
        }

        @Override
        public void startDocument() {
        }

        @Override
        public void endDocument() {
        }

        @Override
        public void endElement(String uri, String localName, String qName) {
        }

        @Override
        public void characters(char[] ch, int start, int length) {
        }

        @Override
        public void ignorableWhitespace(char[] ch, int start, int length) {
        }

        @Override
        public void processingInstruction(String target, String data) {
        }

        @Override
        public void startPrefixMapping(String prefix, String uri) {
        }

        @Override
        public void endPrefixMapping(String prefix) {
        }

        @Override
        public void skippedEntity(String name) {
        }
    }
}

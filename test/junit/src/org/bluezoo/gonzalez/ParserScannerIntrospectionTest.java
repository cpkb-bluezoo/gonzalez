/*
 * ParserScannerIntrospectionTest.java
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

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.bluezoo.gonzalez.schema.ValidationSource;
import org.junit.Test;
import org.xml.sax.Attributes;
import org.xml.sax.DTDHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.ext.DeclHandler;
import org.xml.sax.ext.LexicalHandler;
import org.xml.sax.helpers.DefaultHandler;

import static org.junit.Assert.*;

/**
 * Introspection checks for Parser: DeclHandler / DTDHandler / LexicalHandler
 * declaration events, PSVI {@code getValidationSource()}/
 * {@code getDTDAttributeType()}, and DOCTYPE identifier reporting.
 *
 * @author Chris Burdess
 */
public class ParserScannerIntrospectionTest {

    private static final String DECL_HANDLER =
            "http://xml.org/sax/properties/declaration-handler";
    private static final String LEXICAL_HANDLER =
            "http://xml.org/sax/properties/lexical-handler";

    private static Parser parser() {
        return new Parser();
    }

    private static void parse(Parser parser, String xml) throws Exception {
        parser.parse(new InputSource(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))));
    }

    private static final String DTD_XML =
            "<!DOCTYPE root ["
            + "<!ELEMENT root (child)*>"
            + "<!ELEMENT child EMPTY>"
            + "<!ATTLIST root"
            + "  id ID #REQUIRED"
            + "  kind (a|b) #FIXED 'a'"
            + "  note NMTOKEN #IMPLIED"
            + "  label CDATA 'default label'>"
            + "<!ENTITY copy 'Copyright 2026'>"
            + "<!ENTITY combined 'before &copy; after'>"
            + "<!ENTITY chapter SYSTEM 'chapter1.xml'>"
            + "<!ENTITY % common 'value'>"
            + "<!NOTATION gif PUBLIC '-//GIF//EN' 'gif-viewer'>"
            + "<!ENTITY logo SYSTEM 'logo.gif' NDATA gif>"
            + "]><root id='r1'><child/></root>";

    @Test
    public void testElementDeclarationsViaDeclHandler() throws Exception {
        DeclCapture decls = new DeclCapture();
        Parser parser = parser();
        parser.setContentHandler(new DefaultHandler());
        parser.setProperty(DECL_HANDLER, decls);
        parse(parser, DTD_XML);

        assertTrue(decls.elementDecls.containsKey("root"));
        assertEquals("(child)*", decls.elementDecls.get("root"));
        assertEquals("EMPTY", decls.elementDecls.get("child"));
        assertFalse(decls.elementDecls.containsKey("undeclared"));
    }

    @Test
    public void testAttributeDeclarationsViaDeclHandler() throws Exception {
        DeclCapture decls = new DeclCapture();
        Parser parser = parser();
        parser.setContentHandler(new DefaultHandler());
        parser.setProperty(DECL_HANDLER, decls);
        parse(parser, DTD_XML);

        AttrDecl id = decls.attrDecls.get("root/id");
        assertNotNull(id);
        assertEquals("ID", id.type);
        assertEquals("#REQUIRED", id.mode);
        assertNull(id.value);

        AttrDecl kind = decls.attrDecls.get("root/kind");
        assertNotNull(kind);
        assertEquals("(a|b)", kind.type);
        assertEquals("#FIXED", kind.mode);
        assertEquals("a", kind.value);

        AttrDecl note = decls.attrDecls.get("root/note");
        assertNotNull(note);
        assertEquals("NMTOKEN", note.type);
        assertEquals("#IMPLIED", note.mode);
        assertNull(note.value);

        AttrDecl label = decls.attrDecls.get("root/label");
        assertNotNull(label);
        assertEquals("CDATA", label.type);
        assertNull(label.mode);
        assertEquals("default label", label.value);
    }

    @Test
    public void testDefaultValueEntityReferencesExpandedOnElement() throws Exception {
        String xml = "<!DOCTYPE root ["
                + "<!ENTITY copy 'Copyright 2026'>"
                + "<!ELEMENT root EMPTY>"
                + "<!ATTLIST root attr CDATA 'before &copy; after'>"
                + "]><root/>";
        DeclCapture decls = new DeclCapture();
        final String[] seen = new String[1];
        Parser parser = parser();
        parser.setContentHandler(new DefaultHandler() {
            @Override
            public void startElement(String uri, String localName, String qName, Attributes atts) {
                seen[0] = atts.getValue("attr");
            }
        });
        parser.setProperty(DECL_HANDLER, decls);
        parse(parser, xml);

        AttrDecl attr = decls.attrDecls.get("root/attr");
        assertNotNull(attr);
        assertEquals("before &copy; after", attr.value);
        assertEquals("before Copyright 2026 after", seen[0]);
    }

    @Test
    public void testGeneralEntitiesViaDeclHandler() throws Exception {
        DeclCapture decls = new DeclCapture();
        Parser parser = parser();
        parser.setContentHandler(new DefaultHandler());
        parser.setProperty(DECL_HANDLER, decls);
        parse(parser, DTD_XML);

        assertEquals("Copyright 2026", decls.internalEntities.get("copy"));
        assertEquals("before &copy; after", decls.internalEntities.get("combined"));
        assertEquals("chapter1.xml", decls.externalEntities.get("chapter").systemId);
        assertNull(decls.internalEntities.get("undeclared"));
    }

    @Test
    public void testParameterEntitiesViaDeclHandler() throws Exception {
        DeclCapture decls = new DeclCapture();
        Parser parser = parser();
        parser.setContentHandler(new DefaultHandler());
        parser.setProperty(DECL_HANDLER, decls);
        parse(parser, DTD_XML);

        assertEquals("value", decls.internalEntities.get("%common"));
    }

    @Test
    public void testNotationsViaDtdHandler() throws Exception {
        NotationCapture notations = new NotationCapture();
        UnparsedCapture unparsed = new UnparsedCapture();
        Parser parser = parser();
        parser.setContentHandler(new DefaultHandler());
        parser.setDTDHandler(new CombinedDtdHandler(notations, unparsed));
        parse(parser, DTD_XML);

        assertTrue(notations.names.contains("gif"));
        assertEquals("-//GIF//EN", notations.publicIds.get("gif"));
        assertEquals("gif-viewer", notations.systemIds.get("gif"));
        assertEquals("gif", unparsed.notationNames.get("logo"));
    }

    @Test
    public void testDoctypeExternalIdViaLexicalHandler() throws Exception {
        String xml = "<!DOCTYPE root PUBLIC '-//Test//EN' 'root.dtd' [<!ELEMENT root EMPTY>]><root/>";
        DoctypeCapture lexical = new DoctypeCapture();
        Parser parser = parser();
        parser.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        parser.setContentHandler(new DefaultHandler());
        parser.setProperty(LEXICAL_HANDLER, lexical);
        parse(parser, xml);

        assertEquals("root", lexical.name);
        assertEquals("-//Test//EN", lexical.publicId);
        assertEquals("root.dtd", lexical.systemId);
    }

    @Test
    public void testValidationSource() throws Exception {
        Parser parser = parser();
        assertEquals(ValidationSource.NONE, parser.getValidationSource());

        List<ValidationSource> during = new ArrayList<ValidationSource>();
        parser.setContentHandler(new DefaultHandler() {
            @Override
            public void startElement(String uri, String localName, String qName, Attributes atts)
                    throws SAXException {
                during.add(parser.getValidationSource());
            }
        });
        parse(parser, DTD_XML);

        assertEquals(ValidationSource.DTD, parser.getValidationSource());
        assertFalse(during.isEmpty());
        assertEquals(ValidationSource.DTD, during.get(0));
    }

    @Test
    public void testValidationSourceNoneWithoutDoctype() throws Exception {
        Parser parser = parser();
        parser.setContentHandler(new DefaultHandler());
        parse(parser, "<root attr='v'/>");
        assertEquals(ValidationSource.NONE, parser.getValidationSource());
    }

    @Test
    public void testDtdAttributeTypesDuringStartElement() throws Exception {
        Parser parser = parser();
        List<String> types = new ArrayList<String>();
        parser.setContentHandler(new DefaultHandler() {
            @Override
            public void startElement(String uri, String localName, String qName, Attributes atts)
                    throws SAXException {
                if ("root".equals(qName)) {
                    for (int i = 0; i < atts.getLength(); i++) {
                        types.add(atts.getQName(i) + "=" + parser.getDTDAttributeType(i));
                    }
                    types.add("outOfRange=" + parser.getDTDAttributeType(atts.getLength()));
                }
            }
        });
        parse(parser, DTD_XML);

        assertTrue(types.toString(), types.contains("id=ID"));
        assertTrue(types.toString(), types.contains("kind=ENUMERATION"));
        assertTrue(types.toString(), types.contains("label=CDATA"));
        assertTrue(types.toString(), types.contains("outOfRange=CDATA"));
    }

    @Test
    public void testUndeclaredAttributeTypeIsCdata() throws Exception {
        String xml = "<!DOCTYPE root [<!ELEMENT root EMPTY>]><root undeclared='v'/>";
        Parser parser = parser();
        List<String> types = new ArrayList<String>();
        parser.setContentHandler(new DefaultHandler() {
            @Override
            public void startElement(String uri, String localName, String qName, Attributes atts)
                    throws SAXException {
                for (int i = 0; i < atts.getLength(); i++) {
                    types.add(parser.getDTDAttributeType(i));
                }
            }
        });
        parse(parser, xml);
        assertEquals(1, types.size());
        assertEquals("CDATA", types.get(0));
    }

    @Test
    public void testValidationSourceResetWithParser() throws Exception {
        Parser parser = parser();
        parser.setContentHandler(new DefaultHandler());
        parse(parser, DTD_XML);
        assertEquals(ValidationSource.DTD, parser.getValidationSource());

        parser.reset();
        assertEquals(ValidationSource.NONE, parser.getValidationSource());

        parser.setContentHandler(new DefaultHandler());
        parse(parser, "<other/>");
        assertEquals(ValidationSource.NONE, parser.getValidationSource());
    }

    private static final class AttrDecl {
        final String type;
        final String mode;
        final String value;

        AttrDecl(String type, String mode, String value) {
            this.type = type;
            this.mode = mode;
            this.value = value;
        }
    }

    private static final class ExtEntity {
        final String publicId;
        final String systemId;

        ExtEntity(String publicId, String systemId) {
            this.publicId = publicId;
            this.systemId = systemId;
        }
    }

    private static class DeclCapture implements DeclHandler {
        final Map<String, String> elementDecls = new HashMap<String, String>();
        final Map<String, AttrDecl> attrDecls = new HashMap<String, AttrDecl>();
        final Map<String, String> internalEntities = new HashMap<String, String>();
        final Map<String, ExtEntity> externalEntities = new HashMap<String, ExtEntity>();

        @Override
        public void elementDecl(String name, String model) {
            elementDecls.put(name, model);
        }

        @Override
        public void attributeDecl(String eName, String aName, String type, String mode, String value) {
            attrDecls.put(eName + "/" + aName, new AttrDecl(type, mode, value));
        }

        @Override
        public void internalEntityDecl(String name, String value) {
            internalEntities.put(name, value);
        }

        @Override
        public void externalEntityDecl(String name, String publicId, String systemId) {
            externalEntities.put(name, new ExtEntity(publicId, systemId));
        }
    }

    private static class NotationCapture {
        final List<String> names = new ArrayList<String>();
        final Map<String, String> publicIds = new HashMap<String, String>();
        final Map<String, String> systemIds = new HashMap<String, String>();
    }

    private static class UnparsedCapture {
        final Map<String, String> notationNames = new HashMap<String, String>();
    }

    private static class CombinedDtdHandler implements DTDHandler {
        private final NotationCapture notations;
        private final UnparsedCapture unparsed;

        CombinedDtdHandler(NotationCapture notations, UnparsedCapture unparsed) {
            this.notations = notations;
            this.unparsed = unparsed;
        }

        @Override
        public void notationDecl(String name, String publicId, String systemId) {
            notations.names.add(name);
            notations.publicIds.put(name, publicId);
            notations.systemIds.put(name, systemId);
        }

        @Override
        public void unparsedEntityDecl(String name, String publicId, String systemId, String notationName) {
            unparsed.notationNames.put(name, notationName);
        }
    }

    private static class DoctypeCapture implements LexicalHandler {
        String name;
        String publicId;
        String systemId;

        @Override
        public void startDTD(String name, String publicId, String systemId) {
            this.name = name;
            this.publicId = publicId;
            this.systemId = systemId;
        }

        @Override
        public void endDTD() {
        }

        @Override
        public void startEntity(String name) {
        }

        @Override
        public void endEntity(String name) {
        }

        @Override
        public void startCDATA() {
        }

        @Override
        public void endCDATA() {
        }

        @Override
        public void comment(char[] ch, int start, int length) {
        }
    }
}

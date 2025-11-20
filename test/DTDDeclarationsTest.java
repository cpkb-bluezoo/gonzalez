import org.bluezoo.gonzalez.Parser;
import org.bluezoo.gonzalez.DTDParser;
import org.bluezoo.gonzalez.ElementDeclaration;
import org.bluezoo.gonzalez.AttributeDeclaration;
import org.xml.sax.*;
import org.xml.sax.helpers.DefaultHandler;
import java.io.*;

/**
 * Test for DTD element and attribute declarations.
 * Tests that DTDParser correctly parses and stores declarations.
 */
public class DTDDeclarationsTest {

    public static void main(String[] args) {
        System.out.println("=== DTD Declarations Test ===\n");
        
        try {
            test1_ElementDeclarations();
            test2_AttributeDeclarations();
            test3_MixedDeclarations();
            test4_ExternalDTDDeclarations();
            
            System.out.println("\n=== All DTD declaration tests passed! ===");
        } catch (Exception e) {
            System.err.println("TEST FAILED: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
    
    /**
     * Test 1: Element declarations
     */
    private static void test1_ElementDeclarations() throws Exception {
        System.out.println("Test 1: Element declarations");
        
        String xml = "<!DOCTYPE root [\n" +
                     "  <!ELEMENT root (title, body)>\n" +
                     "  <!ELEMENT title (#PCDATA)>\n" +
                     "  <!ELEMENT body (para+)>\n" +
                     "  <!ELEMENT para (#PCDATA)>\n" +
                     "]>\n" +
                     "<root><title>Test</title><body><para>Hello</para></body></root>";
        
        TestHandler handler = new TestHandler();
        Parser parser = new Parser();
        parser.setContentHandler(handler);
        parser.setProperty("http://xml.org/sax/properties/lexical-handler", handler);
        
        InputSource source = new InputSource(new ByteArrayInputStream(xml.getBytes()));
        parser.parse(source);
        
        // Verify declarations were captured
        DTDParser dtdParser = (DTDParser) parser.getProperty("http://www.nongnu.org/gonzalez/properties/dtd-parser");
        if (dtdParser == null) {
            throw new Exception("DTDParser not available");
        }
        
        // Check element declarations
        ElementDeclaration rootDecl = dtdParser.getElementDeclaration("root");
        if (rootDecl == null) {
            throw new Exception("root element declaration not found");
        }
        System.out.println("  root: " + rootDecl.contentType);
        
        ElementDeclaration titleDecl = dtdParser.getElementDeclaration("title");
        if (titleDecl == null) {
            throw new Exception("title element declaration not found");
        }
        if (titleDecl.contentType != ElementDeclaration.ContentType.MIXED) {
            throw new Exception("title element declaration incorrect: got " + titleDecl.contentType + " expected MIXED");
        }
        System.out.println("  title: " + titleDecl.contentType);
        
        ElementDeclaration bodyDecl = dtdParser.getElementDeclaration("body");
        if (bodyDecl == null) {
            throw new Exception("body element declaration not found");
        }
        System.out.println("  body: " + bodyDecl.contentType);
        
        System.out.println("  ✓ Passed\n");
    }
    
    /**
     * Test 2: Attribute declarations
     */
    private static void test2_AttributeDeclarations() throws Exception {
        System.out.println("Test 2: Attribute declarations");
        
        String xml = "<!DOCTYPE doc [\n" +
                     "  <!ELEMENT doc EMPTY>\n" +
                     "  <!ATTLIST doc\n" +
                     "    id ID #REQUIRED\n" +
                     "    type CDATA #IMPLIED\n" +
                     "    version CDATA \"1.0\">\n" +
                     "]>\n" +
                     "<doc id=\"test\" type=\"sample\"/>";
        
        TestHandler handler = new TestHandler();
        Parser parser = new Parser();
        parser.setContentHandler(handler);
        parser.setProperty("http://xml.org/sax/properties/lexical-handler", handler);
        
        InputSource source = new InputSource(new ByteArrayInputStream(xml.getBytes()));
        parser.parse(source);
        
        // Verify attribute declarations
        DTDParser dtdParser = (DTDParser) parser.getProperty("http://www.nongnu.org/gonzalez/properties/dtd-parser");
        if (dtdParser == null) {
            throw new Exception("DTDParser not available");
        }
        
        AttributeDeclaration idAttr = dtdParser.getAttributeDeclaration("doc", "id");
        if (idAttr == null) {
            throw new Exception("id attribute declaration not found");
        }
        if (!idAttr.type.equals("ID")) {
            throw new Exception("id attribute declaration incorrect: got type=" + idAttr.type);
        }
        System.out.println("  doc/@id: type=" + idAttr.type + ", mode=" + idAttr.mode);
        
        AttributeDeclaration typeAttr = dtdParser.getAttributeDeclaration("doc", "type");
        if (typeAttr == null || !typeAttr.type.equals("CDATA")) {
            throw new Exception("type attribute declaration incorrect");
        }
        System.out.println("  doc/@type: type=" + typeAttr.type + ", mode=" + typeAttr.mode);
        
        AttributeDeclaration versionAttr = dtdParser.getAttributeDeclaration("doc", "version");
        if (versionAttr == null || versionAttr.defaultValue == null || versionAttr.defaultValue.size() != 1) {
            throw new Exception("version attribute declaration incorrect: missing or wrong size");
        }
        String defaultValueStr = versionAttr.defaultValue.get(0).toString();
        if (!defaultValueStr.equals("1.0")) {
            throw new Exception("version attribute declaration incorrect: expected '1.0', got '" + defaultValueStr + "'");
        }
        System.out.println("  doc/@version: type=" + versionAttr.type + ", default=" + defaultValueStr);
        
        System.out.println("  ✓ Passed\n");
    }
    
    /**
     * Test 3: Mixed element and attribute declarations
     */
    private static void test3_MixedDeclarations() throws Exception {
        System.out.println("Test 3: Mixed declarations");
        
        String xml = "<!DOCTYPE book [\n" +
                     "  <!ELEMENT book (chapter+)>\n" +
                     "  <!ATTLIST book title CDATA #REQUIRED>\n" +
                     "  <!ELEMENT chapter (#PCDATA)>\n" +
                     "  <!ATTLIST chapter number NMTOKEN #REQUIRED>\n" +
                     "]>\n" +
                     "<book title=\"My Book\"><chapter number=\"1\">Content</chapter></book>";
        
        TestHandler handler = new TestHandler();
        Parser parser = new Parser();
        parser.setContentHandler(handler);
        parser.setProperty("http://xml.org/sax/properties/lexical-handler", handler);
        
        InputSource source = new InputSource(new ByteArrayInputStream(xml.getBytes()));
        parser.parse(source);
        
        DTDParser dtdParser = (DTDParser) parser.getProperty("http://www.nongnu.org/gonzalez/properties/dtd-parser");
        if (dtdParser == null) {
            throw new Exception("DTDParser not available");
        }
        
        // Check we have both elements and attributes
        if (dtdParser.getElementDeclaration("book") == null) {
            throw new Exception("book element not found");
        }
        if (dtdParser.getAttributeDeclaration("book", "title") == null) {
            throw new Exception("book/@title not found");
        }
        if (dtdParser.getElementDeclaration("chapter") == null) {
            throw new Exception("chapter element not found");
        }
        if (dtdParser.getAttributeDeclaration("chapter", "number") == null) {
            throw new Exception("chapter/@number not found");
        }
        
        System.out.println("  All declarations found");
        System.out.println("  ✓ Passed\n");
    }
    
    /**
     * Test 4: External DTD with declarations
     */
    private static void test4_ExternalDTDDeclarations() throws Exception {
        System.out.println("Test 4: External DTD declarations");
        
        // Create external DTD file
        String dtdContent = "<!ELEMENT article (title, author, body)>\n" +
                            "<!ELEMENT title (#PCDATA)>\n" +
                            "<!ELEMENT author (#PCDATA)>\n" +
                            "<!ELEMENT body (#PCDATA)>\n" +
                            "<!ATTLIST article lang NMTOKEN #IMPLIED>\n" +
                            "<!ATTLIST article status CDATA \"draft\">\n";
        File dtdFile = new File("test-article.dtd").getAbsoluteFile();
        try (FileWriter writer = new FileWriter(dtdFile)) {
            writer.write(dtdContent);
        }
        
        // Create XML document
        String xml = "<!DOCTYPE article SYSTEM \"test-article.dtd\">\n" +
                     "<article lang=\"en\" status=\"draft\">" +
                     "<title>Title</title>" +
                     "<author>Author</author>" +
                     "<body>Body</body>" +
                     "</article>";
        File xmlFile = new File("test-article.xml").getAbsoluteFile();
        try (FileWriter writer = new FileWriter(xmlFile)) {
            writer.write(xml);
        }
        
        TestHandler handler = new TestHandler();
        Parser parser = new Parser();
        parser.setContentHandler(handler);
        parser.setProperty("http://xml.org/sax/properties/lexical-handler", handler);
        parser.setFeature("http://xml.org/sax/features/external-parameter-entities", true);
        
        parser.parse(xmlFile.toURI().toString());
        
        // Verify declarations from external DTD
        DTDParser dtdParser = (DTDParser) parser.getProperty("http://www.nongnu.org/gonzalez/properties/dtd-parser");
        if (dtdParser == null) {
            throw new Exception("DTDParser not available");
        }
        
        ElementDeclaration articleDecl = dtdParser.getElementDeclaration("article");
        if (articleDecl == null) {
            throw new Exception("article element not found");
        }
        System.out.println("  article: " + articleDecl.contentType);
        
        AttributeDeclaration langAttr = dtdParser.getAttributeDeclaration("article", "lang");
        if (langAttr == null) {
            throw new Exception("article/@lang not found");
        }
        System.out.println("  article/@lang: " + langAttr.type);
        
        AttributeDeclaration statusAttr = dtdParser.getAttributeDeclaration("article", "status");
        if (statusAttr == null) {
            throw new Exception("article/@status not found");
        }
        System.out.println("  article/@status: default=" + statusAttr.defaultValue);
        
        // Clean up
        dtdFile.delete();
        xmlFile.delete();
        
        System.out.println("  ✓ Passed\n");
    }
    
    /**
     * Test SAX handler that captures DTD events.
     */
    private static class TestHandler extends DefaultHandler implements org.xml.sax.ext.LexicalHandler {
        
        @Override
        public void startDTD(String name, String publicId, String systemId) throws SAXException {
            System.out.println("  startDTD: name=" + name);
        }
        
        @Override
        public void endDTD() throws SAXException {
            System.out.println("  endDTD()");
        }
        
        @Override
        public void startDocument() throws SAXException {
            System.out.println("  startDocument()");
        }
        
        @Override
        public void endDocument() throws SAXException {
            System.out.println("  endDocument()");
        }
        
        @Override
        public void startElement(String uri, String localName, String qName, Attributes attributes) throws SAXException {
            System.out.println("  startElement: " + qName);
        }
        
        @Override
        public void endElement(String uri, String localName, String qName) throws SAXException {
            System.out.println("  endElement: " + qName);
        }
        
        @Override
        public void characters(char[] ch, int start, int length) throws SAXException {
            String text = new String(ch, start, length).trim();
            if (!text.isEmpty()) {
                System.out.println("  characters: \"" + text + "\"");
            }
        }
        
        @Override
        public void startEntity(String name) throws SAXException {}
        
        @Override
        public void endEntity(String name) throws SAXException {}
        
        @Override
        public void startCDATA() throws SAXException {}
        
        @Override
        public void endCDATA() throws SAXException {}
        
        @Override
        public void comment(char[] ch, int start, int length) throws SAXException {}
    }
}


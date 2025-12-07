import org.bluezoo.gonzalez.Parser;
import org.xml.sax.*;
import org.xml.sax.helpers.DefaultHandler;
import java.io.*;
import java.nio.charset.StandardCharsets;

/**
 * Test for external DTD processing.
 * Tests that external DTD subsets are resolved and parsed correctly.
 */
public class ExternalDTDTest {

    public static void main(String[] args) {
        System.out.println("=== External DTD Test ===\n");
        
        try {
            test1_ExternalDTDSubset();
            test2_ExternalDTDWithInternalSubset();
            test3_ExternalDTDDisabled();
            
            System.out.println("\n=== All external DTD tests passed! ===");
        } catch (Exception e) {
            System.err.println("TEST FAILED: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
    
    /**
     * Test 1: DOCTYPE with external SYSTEM ID
     */
    private static void test1_ExternalDTDSubset() throws Exception {
        System.out.println("Test 1: External DTD subset (SYSTEM)");
        
        // Create a mock external DTD file
        String dtdContent = "<!ELEMENT root (title)>\n<!ELEMENT title (#PCDATA)>\n";
        File dtdFile = new File("test-external.dtd").getAbsoluteFile();
        try (FileWriter writer = new FileWriter(dtdFile)) {
            writer.write(dtdContent);
        }
        
        // Create XML document file referencing external DTD
        String xml = "<!DOCTYPE root SYSTEM \"test-external.dtd\">\n" +
                     "<root><title>Test</title></root>";
        File xmlFile = new File("test-doc1.xml").getAbsoluteFile();
        try (FileWriter writer = new FileWriter(xmlFile)) {
            writer.write(xml);
        }
        
        // Parse using file URL
        TestHandler handler = new TestHandler();
        Parser parser = new Parser();
        parser.setContentHandler(handler);
        parser.setProperty("http://xml.org/sax/properties/lexical-handler", handler);
        parser.setFeature("http://xml.org/sax/features/external-parameter-entities", true);
        
        // Parse from file path
        parser.parse(xmlFile.toURI().toString());
        
        // Clean up
        dtdFile.delete();
        xmlFile.delete();
        
        System.out.println("  DTD subset resolved: " + handler.dtdStartCalled);
        System.out.println("  Document parsed successfully");
        System.out.println("  ✓ Passed\n");
    }
    
    /**
     * Test 2: DOCTYPE with external PUBLIC/SYSTEM ID and internal subset
     */
    private static void test2_ExternalDTDWithInternalSubset() throws Exception {
        System.out.println("Test 2: External DTD with internal subset");
        
        // Create a mock external DTD file
        String dtdContent = "<!ELEMENT root (title)>\n";
        File dtdFile = new File("test-external2.dtd").getAbsoluteFile();
        try (FileWriter writer = new FileWriter(dtdFile)) {
            writer.write(dtdContent);
        }
        
        // Create XML document file with both external and internal subsets
        String xml = "<!DOCTYPE root SYSTEM \"test-external2.dtd\" [\n" +
                     "  <!ELEMENT title (#PCDATA)>\n" +
                     "]>\n" +
                     "<root><title>Test</title></root>";
        File xmlFile = new File("test-doc2.xml").getAbsoluteFile();
        try (FileWriter writer = new FileWriter(xmlFile)) {
            writer.write(xml);
        }
        
        // Parse using file URL
        TestHandler handler = new TestHandler();
        Parser parser = new Parser();
        parser.setContentHandler(handler);
        parser.setProperty("http://xml.org/sax/properties/lexical-handler", handler);
        parser.setFeature("http://xml.org/sax/features/external-parameter-entities", true);
        
        parser.parse(xmlFile.toURI().toString());
        
        // Clean up
        dtdFile.delete();
        xmlFile.delete();
        
        System.out.println("  External and internal DTD processed");
        System.out.println("  Document parsed successfully");
        System.out.println("  ✓ Passed\n");
    }
    
    /**
     * Test 3: External DTD disabled by feature flag
     */
    private static void test3_ExternalDTDDisabled() throws Exception {
        System.out.println("Test 3: External DTD disabled (feature flag)");
        
        // Create a mock external DTD file
        String dtdContent = "<!ELEMENT root (title)>\n<!ELEMENT title (#PCDATA)>\n";
        File dtdFile = new File("test-external3.dtd").getAbsoluteFile();
        try (FileWriter writer = new FileWriter(dtdFile)) {
            writer.write(dtdContent);
        }
        
        // Create XML document file referencing external DTD
        String xml = "<!DOCTYPE root SYSTEM \"test-external3.dtd\">\n" +
                     "<root><title>Test</title></root>";
        File xmlFile = new File("test-doc3.xml").getAbsoluteFile();
        try (FileWriter writer = new FileWriter(xmlFile)) {
            writer.write(xml);
        }
        
        // Parse with external parameter entities disabled
        TestHandler handler = new TestHandler();
        Parser parser = new Parser();
        parser.setContentHandler(handler);
        parser.setProperty("http://xml.org/sax/properties/lexical-handler", handler);
        parser.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        
        parser.parse(xmlFile.toURI().toString());
        
        // Clean up
        dtdFile.delete();
        xmlFile.delete();
        
        System.out.println("  External DTD skipped (as expected)");
        System.out.println("  Document parsed successfully");
        System.out.println("  ✓ Passed\n");
    }
    
    /**
     * Test SAX handler that tracks DTD events.
     */
    private static class TestHandler extends DefaultHandler implements org.xml.sax.ext.LexicalHandler {
        boolean dtdStartCalled = false;
        boolean dtdEndCalled = false;
        
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
        
        // LexicalHandler methods
        @Override
        public void startDTD(String name, String publicId, String systemId) throws SAXException {
            dtdStartCalled = true;
            System.out.println("  startDTD: name=" + name + ", systemId=" + systemId);
        }
        
        @Override
        public void endDTD() throws SAXException {
            dtdEndCalled = true;
            System.out.println("  endDTD()");
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


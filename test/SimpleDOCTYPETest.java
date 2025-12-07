import org.bluezoo.gonzalez.Parser;
import org.xml.sax.*;
import org.xml.sax.helpers.DefaultHandler;
import java.io.*;

/**
 * Simple test to verify basic DOCTYPE parsing works.
 */
public class SimpleDOCTYPETest {

    public static void main(String[] args) {
        System.out.println("=== Simple DOCTYPE Test ===\n");
        
        try {
            // Test 1: DOCTYPE without external ID
            String xml1 = "<!DOCTYPE root><root/>";
            test(xml1, "Test 1: DOCTYPE without external ID");
            
            // Test 2: DOCTYPE with SYSTEM ID (but external entities disabled)
            String xml2 = "<!DOCTYPE root SYSTEM \"http://example.com/dtd.dtd\"><root/>";
            test(xml2, "Test 2: DOCTYPE with SYSTEM ID (entities disabled)");
            
            System.out.println("\n=== All tests passed! ===");
        } catch (Exception e) {
            System.err.println("TEST FAILED: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
    
    private static void test(String xml, String description) throws Exception {
        System.out.println(description);
        
        TestHandler handler = new TestHandler();
        Parser parser = new Parser();
        parser.setContentHandler(handler);
        parser.setProperty("http://xml.org/sax/properties/lexical-handler", handler);
        parser.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        
        InputSource source = new InputSource(new ByteArrayInputStream(xml.getBytes()));
        parser.parse(source);
        
        System.out.println("  ✓ Passed\n");
    }
    
    private static class TestHandler extends DefaultHandler implements org.xml.sax.ext.LexicalHandler {
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
        public void startDTD(String name, String publicId, String systemId) throws SAXException {
            System.out.println("  startDTD: name=" + name + ", systemId=" + systemId);
        }
        
        @Override
        public void endDTD() throws SAXException {
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


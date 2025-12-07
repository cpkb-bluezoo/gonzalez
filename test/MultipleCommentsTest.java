import org.bluezoo.gonzalez.Parser;
import org.xml.sax.*;
import org.xml.sax.ext.LexicalHandler;
import org.xml.sax.helpers.DefaultHandler;
import java.io.*;
import java.util.*;

/**
 * Test multiple comments in different DTD locations.
 */
public class MultipleCommentsTest {
    
    public static void main(String[] args) {
        try {
            System.out.println("=== Multiple Comments Test ===\n");
            
            // Comments in different locations
            String xml = "<?xml version='1.0'?>\n" +
                        "<!DOCTYPE root <!-- comment 1 --> [\n" +
                        "  <!-- comment 2 -->\n" +
                        "] <!-- comment 3 --> >\n" +
                        "<root/>";
            
            TestHandler handler = new TestHandler();
            Parser parser = new Parser();
            parser.setContentHandler(handler);
            parser.setProperty("http://xml.org/sax/properties/lexical-handler", handler);
            
            parser.parse(new InputSource(new ByteArrayInputStream(xml.getBytes("UTF-8"))));
            
            System.out.println("Comments found: " + handler.comments.size());
            for (int i = 0; i < handler.comments.size(); i++) {
                System.out.println("  Comment " + (i+1) + ": '" + handler.comments.get(i) + "'");
            }
            
            if (handler.comments.size() == 3) {
                System.out.println("\n✓ Test passed! All 3 comments captured.");
            } else {
                throw new Exception("Expected 3 comments, got: " + handler.comments.size());
            }
            
        } catch (Exception e) {
            System.out.println("TEST FAILED: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
    
    static class TestHandler extends DefaultHandler implements LexicalHandler {
        List<String> comments = new ArrayList<>();
        
        @Override
        public void comment(char[] ch, int start, int length) {
            comments.add(new String(ch, start, length));
        }
        
        @Override
        public void startDTD(String name, String publicId, String systemId) {}
        
        @Override
        public void endDTD() {}
        
        @Override
        public void startEntity(String name) {}
        
        @Override
        public void endEntity(String name) {}
        
        @Override
        public void startCDATA() {}
        
        @Override
        public void endCDATA() {}
    }
}


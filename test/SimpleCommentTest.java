import org.bluezoo.gonzalez.Parser;
import org.xml.sax.*;
import org.xml.sax.ext.LexicalHandler;
import org.xml.sax.helpers.DefaultHandler;
import java.io.*;
import java.util.*;

/**
 * Simple test for DTD comment parsing - after DOCTYPE name
 */
public class SimpleCommentTest {
    
    public static void main(String[] args) {
        try {
            System.out.println("=== Simple DTD Comment Test ===\n");
            
            // Comment after DOCTYPE name (doesn't require internal subset parsing)
            String xml = "<?xml version='1.0'?>\n" +
                        "<!DOCTYPE root <!-- comment after name --> >\n" +
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
            
            if (handler.comments.size() == 1 && " comment after name ".equals(handler.comments.get(0))) {
                System.out.println("\n✓ Test passed!");
            } else {
                throw new Exception("Comment not captured correctly");
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
            String text = new String(ch, start, length);
            System.out.println("comment() called: '" + text + "'");
            comments.add(text);
        }
        
        @Override
        public void startDTD(String name, String publicId, String systemId) {
            System.out.println("startDTD: " + name);
        }
        
        @Override
        public void endDTD() {
            System.out.println("endDTD");
        }
        
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


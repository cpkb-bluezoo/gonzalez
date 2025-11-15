import org.bluezoo.gonzalez.Parser;
import org.xml.sax.*;
import org.xml.sax.ext.LexicalHandler;
import org.xml.sax.helpers.DefaultHandler;
import java.io.*;
import java.util.*;

/**
 * Test DTD comment parsing with multiple CDATA chunks.
 */
public class DTDCommentTest {
    
    public static void main(String[] args) {
        try {
            System.out.println("=== DTD Comment Test ===\n");
            
            test1_SimpleComment();
            test2_MultipleComments();
            test3_LongComment();
            
            System.out.println("\n✓ All tests passed!");
            
        } catch (Exception e) {
            System.out.println("TEST FAILED: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
    
    private static void test1_SimpleComment() throws Exception {
        System.out.println("Test 1: Simple DTD comment");
        
        String xml = "<?xml version='1.0'?>\n" +
                    "<!DOCTYPE root [\n" +
                    "  <!-- This is a comment -->\n" +
                    "  <!ELEMENT root (#PCDATA)>\n" +
                    "]>\n" +
                    "<root/>";
        
        TestHandler handler = new TestHandler();
        Parser parser = new Parser();
        parser.setContentHandler(handler);
        parser.setProperty("http://xml.org/sax/properties/lexical-handler", handler);
        
        parser.parse(new InputSource(new ByteArrayInputStream(xml.getBytes("UTF-8"))));
        
        if (handler.comments.size() != 1) {
            throw new Exception("Expected 1 comment, got: " + handler.comments.size());
        }
        
        String comment = handler.comments.get(0);
        if (!" This is a comment ".equals(comment)) {
            throw new Exception("Wrong comment text: '" + comment + "'");
        }
        
        System.out.println("  Comment: '" + comment + "'");
        System.out.println("  ✓ Passed\n");
    }
    
    private static void test2_MultipleComments() throws Exception {
        System.out.println("Test 2: Multiple DTD comments");
        
        String xml = "<?xml version='1.0'?>\n" +
                    "<!DOCTYPE root [\n" +
                    "  <!-- First comment -->\n" +
                    "  <!ELEMENT root (child)>\n" +
                    "  <!-- Second comment -->\n" +
                    "  <!ELEMENT child (#PCDATA)>\n" +
                    "  <!-- Third comment -->\n" +
                    "]>\n" +
                    "<root><child>Text</child></root>";
        
        TestHandler handler = new TestHandler();
        Parser parser = new Parser();
        parser.setContentHandler(handler);
        parser.setProperty("http://xml.org/sax/properties/lexical-handler", handler);
        
        parser.parse(new InputSource(new ByteArrayInputStream(xml.getBytes("UTF-8"))));
        
        if (handler.comments.size() != 3) {
            throw new Exception("Expected 3 comments, got: " + handler.comments.size());
        }
        
        System.out.println("  Comment 1: '" + handler.comments.get(0) + "'");
        System.out.println("  Comment 2: '" + handler.comments.get(1) + "'");
        System.out.println("  Comment 3: '" + handler.comments.get(2) + "'");
        System.out.println("  ✓ Passed\n");
    }
    
    private static void test3_LongComment() throws Exception {
        System.out.println("Test 3: Long DTD comment (tests multi-chunk accumulation)");
        
        // Create a long comment that might be split across multiple CDATA tokens
        StringBuilder longCommentText = new StringBuilder(" This is a very long comment that contains lots of text ");
        for (int i = 0; i < 50; i++) {
            longCommentText.append("and more text ");
        }
        
        String xml = "<?xml version='1.0'?>\n" +
                    "<!DOCTYPE root [\n" +
                    "  <!--" + longCommentText + "-->\n" +
                    "  <!ELEMENT root (#PCDATA)>\n" +
                    "]>\n" +
                    "<root/>";
        
        TestHandler handler = new TestHandler();
        Parser parser = new Parser();
        parser.setContentHandler(handler);
        parser.setProperty("http://xml.org/sax/properties/lexical-handler", handler);
        
        parser.parse(new InputSource(new ByteArrayInputStream(xml.getBytes("UTF-8"))));
        
        if (handler.comments.size() != 1) {
            throw new Exception("Expected 1 comment, got: " + handler.comments.size());
        }
        
        String comment = handler.comments.get(0);
        if (!comment.equals(longCommentText.toString())) {
            throw new Exception("Comment text mismatch. Expected length: " + 
                longCommentText.length() + ", got: " + comment.length());
        }
        
        System.out.println("  Comment length: " + comment.length() + " chars");
        System.out.println("  Comment start: '" + comment.substring(0, Math.min(50, comment.length())) + "...'");
        System.out.println("  ✓ Passed\n");
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


import org.bluezoo.gonzalez.Parser;
import org.xml.sax.*;
import org.xml.sax.ext.LexicalHandler;
import org.xml.sax.helpers.DefaultHandler;
import java.io.*;
import java.util.*;

/**
 * Comprehensive test for comment and PI parsing in ContentParser.
 * Tests PIs and comments in prolog, element content, and epilog.
 */
public class ContentParserCommentPITest {
    
    public static void main(String[] args) {
        try {
            System.out.println("=== ContentParser Comment & PI Test ===\n");
            
            String xml = "<?xml version='1.0'?>\n" +
                        "<!-- Prolog comment -->\n" +
                        "<?xml-stylesheet type=\"text/xsl\" href=\"style.xsl\"?>\n" +
                        "<root>\n" +
                        "  <!-- Element content comment -->\n" +
                        "  <?target data?>\n" +
                        "  <child>Text</child>\n" +
                        "</root>\n" +
                        "<!-- Epilog comment -->\n" +
                        "<?epilog-pi test?>";
            
            TestHandler handler = new TestHandler();
            Parser parser = new Parser();
            parser.setContentHandler(handler);
            parser.setProperty("http://xml.org/sax/properties/lexical-handler", handler);
            
            parser.parse(new InputSource(new ByteArrayInputStream(xml.getBytes("UTF-8"))));
            
            System.out.println("Comments found: " + handler.comments.size());
            for (int i = 0; i < handler.comments.size(); i++) {
                System.out.println("  Comment " + (i+1) + ": '" + handler.comments.get(i) + "'");
            }
            
            System.out.println("\nPIs found: " + handler.pis.size());
            for (int i = 0; i < handler.pis.size(); i++) {
                PI pi = handler.pis.get(i);
                System.out.println("  PI " + (i+1) + ": target='" + pi.target + "', data='" + pi.data + "'");
            }
            
            // Verify counts
            if (handler.comments.size() != 3) {
                throw new Exception("Expected 3 comments, got: " + handler.comments.size());
            }
            
            if (handler.pis.size() != 3) {
                throw new Exception("Expected 3 PIs, got: " + handler.pis.size());
            }
            
            // Verify content
            if (!" Prolog comment ".equals(handler.comments.get(0))) {
                throw new Exception("Prolog comment mismatch");
            }
            
            if (!" Element content comment ".equals(handler.comments.get(1))) {
                throw new Exception("Element content comment mismatch");
            }
            
            if (!" Epilog comment ".equals(handler.comments.get(2))) {
                throw new Exception("Epilog comment mismatch");
            }
            
            if (!"xml-stylesheet".equals(handler.pis.get(0).target)) {
                throw new Exception("Prolog PI target mismatch");
            }
            
            if (!"target".equals(handler.pis.get(1).target)) {
                throw new Exception("Element content PI target mismatch");
            }
            
            if (!"epilog-pi".equals(handler.pis.get(2).target)) {
                throw new Exception("Epilog PI target mismatch");
            }
            
            System.out.println("\n✓ All tests passed!");
            
        } catch (Exception e) {
            System.out.println("\nTEST FAILED: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
    
    static class PI {
        String target;
        String data;
        PI(String target, String data) {
            this.target = target;
            this.data = data;
        }
    }
    
    static class TestHandler extends DefaultHandler implements LexicalHandler {
        List<String> comments = new ArrayList<>();
        List<PI> pis = new ArrayList<>();
        
        @Override
        public void processingInstruction(String target, String data) {
            pis.add(new PI(target, data));
        }
        
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


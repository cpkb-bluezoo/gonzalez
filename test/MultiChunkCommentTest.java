import org.bluezoo.gonzalez.*;
import org.xml.sax.*;
import org.xml.sax.ext.LexicalHandler;
import org.xml.sax.helpers.DefaultHandler;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.*;

/**
 * Test that comment text is accumulated across multiple CDATA chunks.
 * Simulates asynchronous parsing by feeding data in small chunks.
 */
public class MultiChunkCommentTest {
    
    public static void main(String[] args) {
        try {
            System.out.println("=== Multi-Chunk Comment Test ===\n");
            
            String xml = "<?xml version='1.0'?>\n" +
                        "<!DOCTYPE root <!-- This is a long comment that should be accumulated properly --> >\n" +
                        "<root/>";
            
            TestHandler handler = new TestHandler();
            ContentParser xmlParser = new ContentParser();
            Tokenizer tokenizer = new Tokenizer(xmlParser);
            xmlParser.setContentHandler(handler);
            xmlParser.setLexicalHandler(handler);
            
            // Feed data in small chunks to force multiple CDATA tokens
            byte[] data = xml.getBytes("UTF-8");
            for (int i = 0; i < data.length; i += 20) {
                int len = Math.min(20, data.length - i);
                ByteBuffer buffer = ByteBuffer.wrap(data, i, len);
                tokenizer.receive(buffer);
            }
            tokenizer.close();
            
            System.out.println("Comments found: " + handler.comments.size());
            for (int i = 0; i < handler.comments.size(); i++) {
                System.out.println("  Comment " + (i+1) + ": '" + handler.comments.get(i) + "'");
            }
            
            if (handler.comments.size() == 1) {
                String expected = " This is a long comment that should be accumulated properly ";
                String actual = handler.comments.get(0);
                if (expected.equals(actual)) {
                    System.out.println("\n✓ Test passed! Comment correctly accumulated across chunks.");
                } else {
                    throw new Exception("Comment mismatch.\nExpected: '" + expected + "'\nActual: '" + actual + "'");
                }
            } else {
                throw new Exception("Expected 1 comment, got: " + handler.comments.size());
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


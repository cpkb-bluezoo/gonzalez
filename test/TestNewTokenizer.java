import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import org.bluezoo.gonzalez.*;

public class TestNewTokenizer {
    
    public static void main(String[] args) throws Exception {
        System.out.println("Testing new state trie tokenizer...\n");
        
        // Test 1: Simple element
        System.out.println("Test 1: Simple element");
        testXML("<root/>");
        
        // Test 2: Element with text content
        System.out.println("\nTest 2: Element with text content");
        testXML("<root>Hello World</root>");
        
        // Test 3: Element with attributes
        System.out.println("\nTest 3: Element with attributes");
        testXML("<root id=\"123\" name='test'/>");
        
        // Test 4: Nested elements
        System.out.println("\nTest 4: Nested elements");
        testXML("<root><child>text</child></root>");
        
        // Test 5: Entity reference
        System.out.println("\nTest 5: Entity reference");
        testXML("<root>Hello &amp; goodbye</root>");
        
        // Test 6: Comment
        System.out.println("\nTest 6: Comment");
        testXML("<root><!-- This is a comment --></root>");
        
        // Test 7: CDATA section
        System.out.println("\nTest 7: CDATA section");
        testXML("<root><![CDATA[<xml>&test;</xml>]]></root>");
        
        // Test 8: Processing instruction
        System.out.println("\nTest 8: Processing instruction");
        testXML("<root><?target data?></root>");
        
        // Test 9: Single-byte buffer (the problematic case)
        System.out.println("\nTest 9: Single-byte buffer");
        testXMLSingleByte("<root/>");
        
        System.out.println("\n=== All tests passed! ===");
    }
    
    static void testXML(String xml) throws Exception {
        LoggingConsumer consumer = new LoggingConsumer();
        Tokenizer tokenizer = new Tokenizer(consumer);
        
        byte[] bytes = xml.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        
        tokenizer.receive(buffer);
        tokenizer.close();  // Flush any remaining tokens
        
        System.out.println("Input: " + xml);
        System.out.println("Tokens: " + consumer.getTokenCount());
        System.out.println("Success!");
    }
    
    static void testXMLSingleByte(String xml) throws Exception {
        LoggingConsumer consumer = new LoggingConsumer();
        Tokenizer tokenizer = new Tokenizer(consumer);
        
        byte[] bytes = xml.getBytes(StandardCharsets.UTF_8);
        
        // Feed one byte at a time
        for (byte b : bytes) {
            ByteBuffer buffer = ByteBuffer.wrap(new byte[] { b });
            tokenizer.receive(buffer);
        }
        tokenizer.close();  // Flush any remaining tokens
        
        System.out.println("Input: " + xml);
        System.out.println("Tokens: " + consumer.getTokenCount());
        System.out.println("Success!");
    }
    
    static class LoggingConsumer implements TokenConsumer {
        private int tokenCount = 0;
        
        public void setLocator(org.xml.sax.Locator locator) {
            // Store if needed for error reporting
        }
        
        public void receive(Token token, CharBuffer data) {
            tokenCount++;
            // Uncomment for debug output:
            // System.out.println("  " + token + ": " + (data != null ? "\"" + data.toString() + "\"" : "null"));
        }
        
        public org.xml.sax.SAXException fatalError(String message) throws org.xml.sax.SAXException {
            org.xml.sax.SAXException ex = new org.xml.sax.SAXException(message);
            throw ex;
        }
        
        public int getTokenCount() {
            return tokenCount;
        }
    }
}


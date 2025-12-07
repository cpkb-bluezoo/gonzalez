package org.bluezoo.gonzalez;

import java.nio.ByteBuffer;
import java.util.*;
import org.xml.sax.SAXException;

/**
 * Compares token output from old vs new tokenizer for the same input.
 */
public class CompareTokenizers {
    
    public static void main(String[] args) throws Exception {
        // Test case 1: Simple comment in DOCTYPE
        System.out.println("=== Test 1: Comment in DOCTYPE ===\n");
        String xml1 = "<!DOCTYPE root <!-- comment --> >\n<root/>";
        compareTokenizers(xml1);
        
        // Test case 2: DOCTYPE with internal subset
        System.out.println("\n=== Test 2: DOCTYPE with internal subset ===\n");
        String xml2 = "<!DOCTYPE root [\n" +
                     "  <!ENTITY % param1 \"replacement\">\n" +
                     "  %param1;\n" +
                     "]>\n" +
                     "<root/>";
        compareTokenizers(xml2);
        
        // Test case 3: Simple document
        System.out.println("\n=== Test 3: Simple document ===\n");
        String xml3 = "<?xml version='1.0'?>\n<root/>";
        compareTokenizers(xml3);
    }
    
    static void compareTokenizers(String xml) throws Exception {
        // Tokenize with OLD tokenizer
        MockConsumer oldConsumer = new MockConsumer();
        OldTokenizer oldTokenizer = new OldTokenizer(oldConsumer);
        ByteBuffer oldBuf = ByteBuffer.wrap(xml.getBytes("UTF-8"));
        oldTokenizer.receive(oldBuf);
        oldTokenizer.close();
        
        // Tokenize with NEW tokenizer
        MockConsumer newConsumer = new MockConsumer();
        Tokenizer newTokenizer = new Tokenizer(newConsumer);
        ByteBuffer newBuf = ByteBuffer.wrap(xml.getBytes("UTF-8"));
        newTokenizer.receive(newBuf);
        newTokenizer.close();
        
        // Compare
        System.out.println("OLD Tokenizer (" + oldConsumer.tokens.size() + " tokens):");
        for (int i = 0; i < oldConsumer.tokens.size(); i++) {
            Token t = oldConsumer.tokens.get(i);
            String data = oldConsumer.data.get(i);
            System.out.println("  " + i + ": " + t + (data != null ? ": \"" + escape(data) + "\"" : ""));
        }
        
        System.out.println("\nNEW Tokenizer (" + newConsumer.tokens.size() + " tokens):");
        for (int i = 0; i < newConsumer.tokens.size(); i++) {
            Token t = newConsumer.tokens.get(i);
            String data = newConsumer.data.get(i);
            System.out.println("  " + i + ": " + t + (data != null ? ": \"" + escape(data) + "\"" : ""));
        }
        
        // Find differences
        System.out.println("\nDifferences:");
        int maxLen = Math.max(oldConsumer.tokens.size(), newConsumer.tokens.size());
        boolean foundDiff = false;
        for (int i = 0; i < maxLen; i++) {
            Token oldToken = i < oldConsumer.tokens.size() ? oldConsumer.tokens.get(i) : null;
            Token newToken = i < newConsumer.tokens.size() ? newConsumer.tokens.get(i) : null;
            String oldData = i < oldConsumer.data.size() ? oldConsumer.data.get(i) : null;
            String newData = i < newConsumer.data.size() ? newConsumer.data.get(i) : null;
            
            if (oldToken != newToken || !Objects.equals(oldData, newData)) {
                foundDiff = true;
                System.out.println("  Position " + i + ":");
                System.out.println("    OLD: " + oldToken + (oldData != null ? ": \"" + escape(oldData) + "\"" : ""));
                System.out.println("    NEW: " + newToken + (newData != null ? ": \"" + escape(newData) + "\"" : ""));
            }
        }
        
        if (!foundDiff) {
            System.out.println("  ✓ No differences found!");
        }
    }
    
    static String escape(String s) {
        if (s == null) return null;
        return s.replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
    
    static class MockConsumer implements TokenConsumer {
        List<Token> tokens = new ArrayList<>();
        List<String> data = new ArrayList<>();
        
        @Override
        public void setLocator(org.xml.sax.Locator locator) {}
        
        @Override
        public org.xml.sax.SAXParseException fatalError(String message) throws org.xml.sax.SAXException {
            System.err.println("FATAL ERROR: " + message);
            throw new org.xml.sax.SAXParseException(message, null);
        }
        
        @Override
        public void receive(Token token, java.nio.CharBuffer data) throws org.xml.sax.SAXException {
            tokens.add(token);
            if (data != null) {
                StringBuilder sb = new StringBuilder();
                while (data.hasRemaining()) {
                    sb.append(data.get());
                }
                this.data.add(sb.toString());
            } else {
                this.data.add(null);
            }
        }
    }
}


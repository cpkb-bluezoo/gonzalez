package org.bluezoo.gonzalez;

import java.nio.ByteBuffer;
import java.util.*;
import org.xml.sax.SAXException;

public class TestXMLDeclWithEncoding {
    public static void main(String[] args) throws Exception {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<root/>";
        
        System.out.println("Testing: " + xml);
        
        // Tokenize with NEW tokenizer
        MockConsumer consumer = new MockConsumer();
        Tokenizer tokenizer = new Tokenizer(consumer);
        ByteBuffer buf = ByteBuffer.wrap(xml.getBytes("UTF-8"));
        tokenizer.receive(buf);
        tokenizer.close();
        
        System.out.println("\nTokens emitted (" + consumer.tokens.size() + "):");
        for (int i = 0; i < consumer.tokens.size(); i++) {
            Token t = consumer.tokens.get(i);
            String data = consumer.data.get(i);
            System.out.println("  " + i + ": " + t + (data != null ? ": \"" + data.replace("\n", "\\n") + "\"" : ""));
        }
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



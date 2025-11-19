import org.bluezoo.gonzalez.*;
import java.io.*;
import java.nio.CharBuffer;

public class DebugXMLDeclTokens {
    public static void main(String[] args) throws Exception {
        String xml = "<?xml version=\"1.0\"?><root/>";
        
        System.out.println("Parsing: " + xml);
        
        MockTokenConsumer consumer = new MockTokenConsumer();
        Tokenizer tokenizer = new Tokenizer(consumer, null, null);
        
        byte[] bytes = xml.getBytes("UTF-8");
        ByteArrayInputStream in = new ByteArrayInputStream(bytes);
        
        // Use Parser's receive method to properly initialize
        Parser parser = new Parser();
        parser.parse(new org.xml.sax.InputSource(new ByteArrayInputStream(xml.getBytes("UTF-8"))));
        
        System.out.println("\nTokens emitted:");
        for (MockTokenConsumer.TokenRecord rec : consumer.getTokens()) {
            String text = (rec.data != null && rec.data.length() > 0) ? " '" + rec.data + "'" : "";
            System.out.println("  " + rec.token + text);
        }
    }
    
    static class MockTokenConsumer implements TokenConsumer {
        private java.util.List<TokenRecord> tokens = new java.util.ArrayList<>();
        
        static class TokenRecord {
            Token token;
            String data;
            TokenRecord(Token t, String d) { token = t; data = d; }
        }
        
        public java.util.List<TokenRecord> getTokens() { return tokens; }
        
        @Override
        public void setLocator(org.xml.sax.Locator locator) {}
        
        @Override
        public void receive(Token token, CharBuffer data) throws org.xml.sax.SAXException {
            String dataStr = null;
            if (data != null) {
                StringBuilder sb = new StringBuilder();
                while (data.hasRemaining()) {
                    sb.append(data.get());
                }
                dataStr = sb.toString();
            }
            tokens.add(new TokenRecord(token, dataStr));
        }
        
        @Override
        public org.xml.sax.SAXParseException fatalError(String message) {
            return new org.xml.sax.SAXParseException(message, null);
        }
    }
}



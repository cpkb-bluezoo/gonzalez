import org.bluezoo.gonzalez.*;
import java.nio.ByteBuffer;
import java.util.*;

public class DebugCommentInDOCTYPE {
    public static void main(String[] args) throws Exception {
        String xml = "<!DOCTYPE root <!-- comment --> >";
        
        MockConsumer mock = new MockConsumer();
        Tokenizer tokenizer = new Tokenizer(mock);
        
        ByteBuffer buf = ByteBuffer.wrap(xml.getBytes("UTF-8"));
        tokenizer.receive(buf);
        tokenizer.close();
        
        System.out.println("Tokens received:");
        for (int i = 0; i < mock.tokens.size(); i++) {
            Token t = mock.tokens.get(i);
            String data = mock.data.get(i);
            System.out.println("  " + t + (data != null ? ": \"" + data + "\"" : ""));
        }
    }
    
    static class MockConsumer implements TokenConsumer {
        List<Token> tokens = new ArrayList<>();
        List<String> data = new ArrayList<>();
        
        @Override
        public void setLocator(org.xml.sax.Locator locator) {}
        
        @Override
        public org.xml.sax.SAXParseException fatalError(String message) throws org.xml.sax.SAXException {
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


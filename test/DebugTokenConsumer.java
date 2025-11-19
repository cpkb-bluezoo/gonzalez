package org.bluezoo.gonzalez;

import java.nio.CharBuffer;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.Locator;

/**
 * A debugging token consumer that forwards tokens to another consumer
 * while printing them to System.out.
 * 
 * Usage: Insert between Tokenizer and ContentParser to see all tokens.
 */
public class DebugTokenConsumer implements TokenConsumer {
    
    private final TokenConsumer delegate;
    private int tokenCount = 0;
    
    public DebugTokenConsumer(TokenConsumer delegate) {
        this.delegate = delegate;
    }
    
    @Override
    public void setLocator(Locator locator) {
        delegate.setLocator(locator);
    }
    
    @Override
    public void receive(Token token, CharBuffer data) throws SAXException {
        tokenCount++;
        
        // Extract data as string WITHOUT modifying the buffer
        String dataStr = null;
        if (data != null && data.hasRemaining()) {
            // Save position
            int savedPos = data.position();
            int savedLimit = data.limit();
            
            // Read the data
            StringBuilder sb = new StringBuilder();
            while (data.hasRemaining()) {
                sb.append(data.get());
            }
            dataStr = sb.toString();
            
            // Restore position and limit for delegate
            data.limit(savedLimit);
            data.position(savedPos);
        }
        
        // Print debug info
        String text = (dataStr != null && !dataStr.isEmpty()) ? " \"" + escape(dataStr) + "\"" : "";
        System.out.println(String.format("[%4d] %-20s%s", tokenCount, token, text));
        
        // Forward to delegate
        delegate.receive(token, data);
    }
    
    @Override
    public SAXException fatalError(String message) throws SAXException {
        System.out.println("[FATAL ERROR] " + message);
        return delegate.fatalError(message);
    }
    
    /**
     * Escape special characters for display.
     */
    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}


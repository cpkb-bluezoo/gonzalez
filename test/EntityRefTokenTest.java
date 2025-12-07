import org.bluezoo.gonzalez.Tokenizer;
import org.bluezoo.gonzalez.Token;
import org.bluezoo.gonzalez.TokenConsumer;
import org.xml.sax.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.util.*;

/**
 * Tests that the tokenizer emits GENERALENTITYREF and PARAMETERENTITYREF tokens.
 */
public class EntityRefTokenTest {
    
    public static void main(String[] args) throws Exception {
        System.out.println("=== Entity Reference Token Test ===\n");
        
        // Test 1: General entity references
        testGeneralEntityRef();
        
        // Test 2: Parameter entity references
        testParameterEntityRef();
        
        // Test 3: Character references
        testCharacterRef();
        
        // Test 4: Predefined entities
        testPredefinedEntities();
        
        System.out.println("\n✓ All tests passed!");
    }
    
    /**
     * Test that general entity references emit GENERALENTITYREF token.
     */
    private static void testGeneralEntityRef() throws Exception {
        System.out.println("Test 1: General entity references");
        
        String xml = "<root attr='value &myent; more'>&another;</root>";
        
        MockTokenConsumer mock = new MockTokenConsumer();
        Tokenizer tokenizer = new Tokenizer(mock);
        
        ByteBuffer buf = ByteBuffer.wrap(xml.getBytes("UTF-8"));
        tokenizer.receive(buf);
        tokenizer.close();
        
        // Verify we received GENERALENTITYREF tokens
        int count = 0;
        for (int i = 0; i < mock.tokens.size(); i++) {
            if (mock.tokens.get(i) == Token.GENERALENTITYREF) {
                String name = mock.data.get(i);
                System.out.println("  Found GENERALENTITYREF: &" + name + ";");
                count++;
            }
        }
        
        if (count != 2) {
            throw new AssertionError("Expected 2 GENERALENTITYREF tokens, got: " + count);
        }
        
        System.out.println("  ✓ GENERALENTITYREF tokens emitted correctly");
    }
    
    /**
     * Test that parameter entity references emit PARAMETERENTITYREF token.
     */
    private static void testParameterEntityRef() throws Exception {
        System.out.println("\nTest 2: Parameter entity references");
        
        String xml = "<!DOCTYPE root [\n" +
                     "  <!ENTITY % param1 \"replacement\">\n" +
                     "  %param1;\n" +
                     "]>\n" +
                     "<root/>";
        
        MockTokenConsumer mock = new MockTokenConsumer();
        Tokenizer tokenizer = new Tokenizer(mock);
        
        try {
            ByteBuffer buf = ByteBuffer.wrap(xml.getBytes("UTF-8"));
            tokenizer.receive(buf);
            tokenizer.close();
        } catch (Exception e) {
            System.out.println("  Error during tokenization: " + e.getMessage());
            e.printStackTrace();
            throw e;
        }
        
        // Debug: print all tokens
        System.out.println("  All tokens:");
        for (int i = 0; i < mock.tokens.size(); i++) {
            System.out.println("    " + mock.tokens.get(i) + ": " + mock.data.get(i));
        }
        
        // Verify we received PARAMETERENTITYREF tokens
        int count = 0;
        for (int i = 0; i < mock.tokens.size(); i++) {
            if (mock.tokens.get(i) == Token.PARAMETERENTITYREF) {
                String name = mock.data.get(i);
                System.out.println("  Found PARAMETERENTITYREF: %" + name + ";");
                count++;
            }
        }
        
        if (count != 1) {
            throw new AssertionError("Expected 1 PARAMETERENTITYREF token, got: " + count);
        }
        
        System.out.println("  ✓ PARAMETERENTITYREF tokens emitted correctly");
    }
    
    /**
     * Test that character references emit ENTITYREF token with replacement.
     */
    private static void testCharacterRef() throws Exception {
        System.out.println("\nTest 3: Character references");
        
        String xml = "<root>&#65; &#x42; &#x1F600;</root>";
        
        MockTokenConsumer mock = new MockTokenConsumer();
        Tokenizer tokenizer = new Tokenizer(mock);
        
        ByteBuffer buf = ByteBuffer.wrap(xml.getBytes("UTF-8"));
        tokenizer.receive(buf);
        tokenizer.close();
        
        // Verify we received ENTITYREF tokens (not GENERALENTITYREF)
        int entityRefCount = 0;
        int generalRefCount = 0;
        for (int i = 0; i < mock.tokens.size(); i++) {
            if (mock.tokens.get(i) == Token.ENTITYREF) {
                String replacement = mock.data.get(i);
                System.out.println("  Found ENTITYREF with replacement: '" + replacement + "'");
                entityRefCount++;
            } else if (mock.tokens.get(i) == Token.GENERALENTITYREF) {
                generalRefCount++;
            }
        }
        
        if (entityRefCount != 3) {
            throw new AssertionError("Expected 3 ENTITYREF tokens (char refs), got: " + entityRefCount);
        }
        if (generalRefCount > 0) {
            throw new AssertionError("Character references should not emit GENERALENTITYREF, found: " + generalRefCount);
        }
        
        System.out.println("  ✓ Character references emit ENTITYREF correctly");
    }
    
    /**
     * Test that predefined entities emit ENTITYREF token with replacement.
     */
    private static void testPredefinedEntities() throws Exception {
        System.out.println("\nTest 4: Predefined entities");
        
        String xml = "<root>&amp; &lt; &gt; &apos; &quot;</root>";
        
        MockTokenConsumer mock = new MockTokenConsumer();
        Tokenizer tokenizer = new Tokenizer(mock);
        
        ByteBuffer buf = ByteBuffer.wrap(xml.getBytes("UTF-8"));
        tokenizer.receive(buf);
        tokenizer.close();
        
        // Verify we received ENTITYREF tokens (not GENERALENTITYREF)
        int entityRefCount = 0;
        int generalRefCount = 0;
        List<String> replacements = new ArrayList<>();
        
        for (int i = 0; i < mock.tokens.size(); i++) {
            if (mock.tokens.get(i) == Token.ENTITYREF) {
                String replacement = mock.data.get(i);
                replacements.add(replacement);
                entityRefCount++;
            } else if (mock.tokens.get(i) == Token.GENERALENTITYREF) {
                generalRefCount++;
            }
        }
        
        if (entityRefCount != 5) {
            throw new AssertionError("Expected 5 ENTITYREF tokens (predefined entities), got: " + entityRefCount);
        }
        if (generalRefCount > 0) {
            throw new AssertionError("Predefined entities should not emit GENERALENTITYREF, found: " + generalRefCount);
        }
        
        // Verify replacements
        String expected = "& < > ' \"";
        String actual = String.join(" ", replacements);
        if (!expected.equals(actual)) {
            throw new AssertionError("Expected replacements: '" + expected + "', got: '" + actual + "'");
        }
        
        System.out.println("  ✓ Predefined entities emit ENTITYREF with correct replacements");
    }
    
    /**
     * Mock consumer that captures all tokens.
     */
    private static class MockTokenConsumer implements TokenConsumer {
        List<Token> tokens = new ArrayList<>();
        List<String> data = new ArrayList<>();
        
        @Override
        public void setLocator(Locator locator) {
        }
        
        @Override
        public SAXParseException fatalError(String message) throws SAXException {
            SAXParseException ex = new SAXParseException(message, null);
            throw ex;
        }
        
        @Override
        public void receive(Token token, CharBuffer data) throws SAXException {
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

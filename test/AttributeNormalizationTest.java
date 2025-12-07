import org.bluezoo.gonzalez.Parser;
import org.xml.sax.*;
import org.xml.sax.helpers.DefaultHandler;
import java.io.*;
import java.util.*;

/**
 * Tests attribute value normalization according to XML spec section 3.3.3.
 */
public class AttributeNormalizationTest {
    
    public static void main(String[] args) throws Exception {
        System.out.println("Testing attribute value normalization...\n");
        
        // Test 1: Basic whitespace normalization (CDATA type)
        testWhitespaceNormalization();
        
        // Test 2: Non-CDATA type (should trim and collapse)
        testNonCDATANormalization();
        
        // Test 3: Entity references in attribute values
        testEntityReferences();
        
        // Test 4: Mixed whitespace types
        testMixedWhitespace();
        
        System.out.println("\n=== All tests passed! ===");
    }
    
    /**
     * Test that whitespace characters in CDATA attributes are replaced with space.
     */
    private static void testWhitespaceNormalization() throws Exception {
        System.out.println("Test 1: Whitespace normalization (CDATA)");
        
        String xml = "<?xml version='1.0'?>\n" +
                     "<root attr='value\twith\ttabs and\nnewlines'></root>";
        
        TestHandler handler = new TestHandler();
        Parser parser = new Parser();
        parser.setContentHandler(handler);
        
        ByteArrayInputStream bais = new ByteArrayInputStream(xml.getBytes("UTF-8"));
        parser.parse(new InputSource(bais));
        
        // Expected: tabs and newlines replaced with spaces
        String expected = "value with tabs and newlines";
        String actual = handler.attributes.get("attr");
        
        if (!expected.equals(actual)) {
            throw new AssertionError("Expected: '" + expected + "', got: '" + actual + "'");
        }
        
        System.out.println("  ✓ Whitespace replaced with spaces in CDATA attribute");
    }
    
    /**
     * Test that non-CDATA attributes have spaces collapsed and trimmed.
     */
    private static void testNonCDATANormalization() throws Exception {
        System.out.println("\nTest 2: Non-CDATA normalization (with DTD)");
        
        String xml = "<?xml version='1.0'?>\n" +
                     "<!DOCTYPE root [\n" +
                     "  <!ELEMENT root EMPTY>\n" +
                     "  <!ATTLIST root\n" +
                     "    id ID #REQUIRED\n" +
                     "    type NMTOKEN #IMPLIED\n" +
                     "  >\n" +
                     "]>\n" +
                     "<root id='  my-id  ' type='  one   two  '></root>";
        
        TestHandler handler = new TestHandler();
        Parser parser = new Parser();
        parser.setContentHandler(handler);
        
        ByteArrayInputStream bais = new ByteArrayInputStream(xml.getBytes("UTF-8"));
        parser.parse(new InputSource(bais));
        
        // Expected: leading/trailing spaces trimmed, multiple spaces collapsed
        String expectedId = "my-id";
        String expectedType = "one two";
        
        String actualId = handler.attributes.get("id");
        String actualType = handler.attributes.get("type");
        
        if (!expectedId.equals(actualId)) {
            throw new AssertionError("ID: Expected: '" + expectedId + "', got: '" + actualId + "'");
        }
        if (!expectedType.equals(actualType)) {
            throw new AssertionError("Type: Expected: '" + expectedType + "', got: '" + actualType + "'");
        }
        
        System.out.println("  ✓ Non-CDATA attributes trimmed and collapsed");
    }
    
    /**
     * Test that entity references in attribute values are expanded.
     */
    private static void testEntityReferences() throws Exception {
        System.out.println("\nTest 3: Entity references in attribute values");
        
        String xml = "<?xml version='1.0'?>\n" +
                     "<root attr='value&amp;with&lt;entities&gt;'></root>";
        
        TestHandler handler = new TestHandler();
        Parser parser = new Parser();
        parser.setContentHandler(handler);
        
        ByteArrayInputStream bais = new ByteArrayInputStream(xml.getBytes("UTF-8"));
        parser.parse(new InputSource(bais));
        
        // Expected: entities expanded
        String expected = "value&with<entities>";
        String actual = handler.attributes.get("attr");
        
        if (!expected.equals(actual)) {
            throw new AssertionError("Expected: '" + expected + "', got: '" + actual + "'");
        }
        
        System.out.println("  ✓ Entity references expanded correctly");
    }
    
    /**
     * Test mixed whitespace: spaces, tabs, newlines.
     */
    private static void testMixedWhitespace() throws Exception {
        System.out.println("\nTest 4: Mixed whitespace types");
        
        String xml = "<?xml version='1.0'?>\n" +
                     "<!DOCTYPE root [\n" +
                     "  <!ELEMENT root EMPTY>\n" +
                     "  <!ATTLIST root\n" +
                     "    tokens NMTOKENS #IMPLIED\n" +
                     "  >\n" +
                     "]>\n" +
                     "<root tokens=' \t\na\tb\nc \t'></root>";
        
        TestHandler handler = new TestHandler();
        Parser parser = new Parser();
        parser.setContentHandler(handler);
        
        ByteArrayInputStream bais = new ByteArrayInputStream(xml.getBytes("UTF-8"));
        parser.parse(new InputSource(bais));
        
        // Expected: all whitespace normalized, then trimmed and collapsed
        String expected = "a b c";
        String actual = handler.attributes.get("tokens");
        
        if (!expected.equals(actual)) {
            throw new AssertionError("Expected: '" + expected + "', got: '" + actual + "'");
        }
        
        System.out.println("  ✓ Mixed whitespace normalized correctly");
    }
    
    /**
     * Simple handler that captures attributes.
     */
    private static class TestHandler extends DefaultHandler {
        Map<String, String> attributes = new HashMap<>();
        
        @Override
        public void startElement(String uri, String localName, String qName, Attributes attrs) {
            for (int i = 0; i < attrs.getLength(); i++) {
                attributes.put(attrs.getQName(i), attrs.getValue(i));
            }
        }
    }
}


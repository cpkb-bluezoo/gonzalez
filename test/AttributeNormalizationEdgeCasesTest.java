import org.bluezoo.gonzalez.Parser;
import org.xml.sax.*;
import org.xml.sax.helpers.DefaultHandler;
import java.io.*;
import java.util.*;

/**
 * Edge case tests for attribute value normalization.
 */
public class AttributeNormalizationEdgeCasesTest {
    
    public static void main(String[] args) throws Exception {
        System.out.println("Testing attribute normalization edge cases...\n");
        
        // Test 1: Empty attribute values
        testEmptyAttributes();
        
        // Test 2: Only whitespace
        testOnlyWhitespace();
        
        // Test 3: Consecutive entity references
        testConsecutiveEntities();
        
        // Test 4: No DTD (all CDATA)
        testNoDTD();
        
        // Test 5: Multiple attributes on same element
        testMultipleAttributes();
        
        System.out.println("\n=== All edge case tests passed! ===");
    }
    
    /**
     * Test empty attribute values (should remain empty).
     */
    private static void testEmptyAttributes() throws Exception {
        System.out.println("Test 1: Empty attribute values");
        
        String xml = "<?xml version='1.0'?>\n" +
                     "<root attr1='' attr2=\"\"></root>";
        
        TestHandler handler = new TestHandler();
        Parser parser = new Parser();
        parser.setContentHandler(handler);
        
        ByteArrayInputStream bais = new ByteArrayInputStream(xml.getBytes("UTF-8"));
        parser.parse(new InputSource(bais));
        
        String attr1 = handler.attributes.get("attr1");
        String attr2 = handler.attributes.get("attr2");
        
        if (!"".equals(attr1)) {
            throw new AssertionError("attr1: Expected empty string, got: '" + attr1 + "'");
        }
        if (!"".equals(attr2)) {
            throw new AssertionError("attr2: Expected empty string, got: '" + attr2 + "'");
        }
        
        System.out.println("  ✓ Empty attributes remain empty");
    }
    
    /**
     * Test attributes with only whitespace.
     */
    private static void testOnlyWhitespace() throws Exception {
        System.out.println("\nTest 2: Only whitespace");
        
        String xml = "<?xml version='1.0'?>\n" +
                     "<!DOCTYPE root [\n" +
                     "  <!ELEMENT root EMPTY>\n" +
                     "  <!ATTLIST root\n" +
                     "    cdata CDATA #IMPLIED\n" +
                     "    token NMTOKEN #IMPLIED\n" +
                     "  >\n" +
                     "]>\n" +
                     "<root cdata='   \t\n  ' token='  \t  '></root>";
        
        TestHandler handler = new TestHandler();
        Parser parser = new Parser();
        parser.setContentHandler(handler);
        
        ByteArrayInputStream bais = new ByteArrayInputStream(xml.getBytes("UTF-8"));
        parser.parse(new InputSource(bais));
        
        String cdata = handler.attributes.get("cdata");
        String token = handler.attributes.get("token");
        
        // CDATA: whitespace replaced with spaces, but not trimmed
        // Line-end already normalized by tokenizer, so we expect spaces for all whitespace
        if (cdata.length() < 6) {
            throw new AssertionError("cdata: Expected at least 6 spaces, got: '" + cdata + 
                "' (" + cdata.length() + " chars)");
        }
        // Check all chars are spaces
        for (int i = 0; i < cdata.length(); i++) {
            if (cdata.charAt(i) != ' ') {
                throw new AssertionError("cdata: Expected all spaces, found: '" + cdata.charAt(i) + "' at position " + i);
            }
        }
        
        // NMTOKEN: should be empty after trim
        if (!"".equals(token)) {
            throw new AssertionError("token: Expected empty string, got: '" + token + "'");
        }
        
        System.out.println("  ✓ Whitespace-only values handled correctly");
    }
    
    /**
     * Test consecutive entity references without spaces.
     */
    private static void testConsecutiveEntities() throws Exception {
        System.out.println("\nTest 3: Consecutive entity references");
        
        String xml = "<?xml version='1.0'?>\n" +
                     "<root attr='&lt;&gt;&amp;&quot;&apos;'></root>";
        
        TestHandler handler = new TestHandler();
        Parser parser = new Parser();
        parser.setContentHandler(handler);
        
        ByteArrayInputStream bais = new ByteArrayInputStream(xml.getBytes("UTF-8"));
        parser.parse(new InputSource(bais));
        
        String expected = "<>&\"'";
        String actual = handler.attributes.get("attr");
        
        if (!expected.equals(actual)) {
            throw new AssertionError("Expected: '" + expected + "', got: '" + actual + "'");
        }
        
        System.out.println("  ✓ Consecutive entities expanded correctly");
    }
    
    /**
     * Test without DTD (all attributes treated as CDATA).
     */
    private static void testNoDTD() throws Exception {
        System.out.println("\nTest 4: No DTD (default CDATA)");
        
        String xml = "<?xml version='1.0'?>\n" +
                     "<root id='  my-id  ' tokens='  a   b  '></root>";
        
        TestHandler handler = new TestHandler();
        Parser parser = new Parser();
        parser.setContentHandler(handler);
        
        ByteArrayInputStream bais = new ByteArrayInputStream(xml.getBytes("UTF-8"));
        parser.parse(new InputSource(bais));
        
        // Without DTD, all attributes are CDATA (whitespace replaced, but NOT trimmed/collapsed)
        String expectedId = "  my-id  ";
        String expectedTokens = "  a   b  ";
        
        String actualId = handler.attributes.get("id");
        String actualTokens = handler.attributes.get("tokens");
        
        if (!expectedId.equals(actualId)) {
            throw new AssertionError("id: Expected: '" + expectedId + "', got: '" + actualId + "'");
        }
        if (!expectedTokens.equals(actualTokens)) {
            throw new AssertionError("tokens: Expected: '" + expectedTokens + "', got: '" + actualTokens + "'");
        }
        
        System.out.println("  ✓ No DTD: attributes treated as CDATA");
    }
    
    /**
     * Test multiple attributes on the same element.
     */
    private static void testMultipleAttributes() throws Exception {
        System.out.println("\nTest 5: Multiple attributes per element");
        
        String xml = "<?xml version='1.0'?>\n" +
                     "<!DOCTYPE root [\n" +
                     "  <!ELEMENT root EMPTY>\n" +
                     "  <!ATTLIST root\n" +
                     "    a CDATA #IMPLIED\n" +
                     "    b ID #IMPLIED\n" +
                     "    c NMTOKEN #IMPLIED\n" +
                     "    d CDATA #IMPLIED\n" +
                     "  >\n" +
                     "]>\n" +
                     "<root a='  v1  ' b='  v2  ' c='  v3  ' d='v4'></root>";
        
        TestHandler handler = new TestHandler();
        Parser parser = new Parser();
        parser.setContentHandler(handler);
        
        ByteArrayInputStream bais = new ByteArrayInputStream(xml.getBytes("UTF-8"));
        parser.parse(new InputSource(bais));
        
        String a = handler.attributes.get("a");
        String b = handler.attributes.get("b");
        String c = handler.attributes.get("c");
        String d = handler.attributes.get("d");
        
        if (!"  v1  ".equals(a)) {
            throw new AssertionError("a (CDATA): Expected '  v1  ', got: '" + a + "'");
        }
        if (!"v2".equals(b)) {
            throw new AssertionError("b (ID): Expected 'v2', got: '" + b + "'");
        }
        if (!"v3".equals(c)) {
            throw new AssertionError("c (NMTOKEN): Expected 'v3', got: '" + c + "'");
        }
        if (!"v4".equals(d)) {
            throw new AssertionError("d (CDATA): Expected 'v4', got: '" + d + "'");
        }
        
        System.out.println("  ✓ Multiple attributes normalized correctly");
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


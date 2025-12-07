/*
 * Test combined entity expansion and attribute normalization.
 */

import java.io.ByteArrayInputStream;
import org.bluezoo.gonzalez.Parser;
import org.xml.sax.Attributes;
import org.xml.sax.ContentHandler;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;

/**
 * Tests that entity expansion and attribute normalization work together correctly.
 */
public class EntityAndNormalizationTest {
    
    public static void main(String[] args) throws Exception {
        System.out.println("Testing entity expansion with attribute normalization...\n");
        test1_EntityWithWhitespace();
        test2_EntityInNonCDATAAttribute();
        test3_MultipleEntitiesWithNormalization();
        System.out.println("\n✓ All tests passed!");
    }
    
    /**
     * Test 1: Entity expansion with whitespace normalization (CDATA attribute).
     */
    static void test1_EntityWithWhitespace() throws Exception {
        System.out.println("Test 1: Entity with whitespace in CDATA attribute");
        String xml = "<?xml version='1.0'?>\n" +
                    "<!DOCTYPE root [\n" +
                    "  <!ENTITY text \"value\twith\ttabs\">\n" +
                    "]>\n" +
                    "<root attr='before &text; after'/>";
        
        AttributeCapture handler = new AttributeCapture();
        Parser parser = new Parser();
        parser.setContentHandler(handler);
        
        ByteArrayInputStream in = new ByteArrayInputStream(xml.getBytes("UTF-8"));
        parser.parse(new org.xml.sax.InputSource(in));
        
        String value = handler.getValue("attr");
        assert value != null : "Attribute 'attr' not found";
        // Tabs in entity value should be normalized to spaces
        assert value.equals("before value with tabs after") : 
            "Expected 'before value with tabs after', got: '" + value + "'";
        
        System.out.println("  attr='" + value + "'");
        System.out.println("  ✓ Entity with whitespace normalized correctly\n");
    }
    
    /**
     * Test 2: Entity expansion in non-CDATA attribute with trimming/collapsing.
     */
    static void test2_EntityInNonCDATAAttribute() throws Exception {
        System.out.println("Test 2: Entity in non-CDATA attribute (ID type)");
        String xml = "<?xml version='1.0'?>\n" +
                    "<!DOCTYPE root [\n" +
                    "  <!ENTITY myid \"abc123\">\n" +
                    "  <!ATTLIST root id ID #REQUIRED>\n" +
                    "]>\n" +
                    "<root id='  &myid;  '/>\n";
        
        AttributeCapture handler = new AttributeCapture();
        Parser parser = new Parser();
        parser.setContentHandler(handler);
        
        ByteArrayInputStream in = new ByteArrayInputStream(xml.getBytes("UTF-8"));
        parser.parse(new org.xml.sax.InputSource(in));
        
        String value = handler.getValue("id");
        assert value != null : "Attribute 'id' not found";
        // Leading/trailing spaces should be trimmed for ID type
        assert value.equals("abc123") : "Expected 'abc123', got: '" + value + "'";
        
        System.out.println("  id='" + value + "'");
        System.out.println("  ✓ Entity in ID attribute trimmed correctly\n");
    }
    
    /**
     * Test 3: Multiple entities with complex whitespace and normalization.
     */
    static void test3_MultipleEntitiesWithNormalization() throws Exception {
        System.out.println("Test 3: Multiple entities with complex normalization");
        String xml = "<?xml version='1.0'?>\n" +
                    "<!DOCTYPE root [\n" +
                    "  <!ATTLIST root\n" +
                    "    tokens NMTOKENS #IMPLIED\n" +
                    "  >\n" +
                    "  <!ENTITY a \"tok1\">\n" +
                    "  <!ENTITY b \"tok2\">\n" +
                    "  <!ENTITY c \"tok3\">\n" +
                    "]>\n" +
                    "<root tokens='  &a;   &b;  &c;  '/>";
        
        AttributeCapture handler = new AttributeCapture();
        Parser parser = new Parser();
        parser.setContentHandler(handler);
        
        ByteArrayInputStream in = new ByteArrayInputStream(xml.getBytes("UTF-8"));
        parser.parse(new org.xml.sax.InputSource(in));
        
        String value = handler.getValue("tokens");
        assert value != null : "Attribute 'tokens' not found";
        // Multiple spaces should collapse to single space for NMTOKENS
        assert value.equals("tok1 tok2 tok3") : "Expected 'tok1 tok2 tok3', got: '" + value + "'";
        
        System.out.println("  tokens='" + value + "'");
        System.out.println("  ✓ Multiple entities normalized correctly\n");
    }
    
    /**
     * Helper ContentHandler that captures attribute values.
     */
    static class AttributeCapture implements ContentHandler {
        private String attributeValue;
        
        public String getValue(String name) {
            return attributeValue;
        }
        
        @Override
        public void startElement(String uri, String localName, String qName, Attributes atts) {
            if (atts.getLength() > 0) {
                attributeValue = atts.getValue(0);
            }
        }
        
        // Unused ContentHandler methods
        @Override public void setDocumentLocator(Locator locator) {}
        @Override public void startDocument() {}
        @Override public void endDocument() {}
        @Override public void endElement(String uri, String localName, String qName) {}
        @Override public void characters(char[] ch, int start, int length) {}
        @Override public void ignorableWhitespace(char[] ch, int start, int length) {}
        @Override public void processingInstruction(String target, String data) {}
        @Override public void startPrefixMapping(String prefix, String uri) {}
        @Override public void endPrefixMapping(String prefix) {}
        @Override public void skippedEntity(String name) {}
    }
}


/*
 * Test general entity references in attribute values.
 */

import java.io.ByteArrayInputStream;
import org.bluezoo.gonzalez.Parser;
import org.xml.sax.Attributes;
import org.xml.sax.ContentHandler;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

/**
 * Tests general entity reference expansion in attribute values.
 */
public class EntityRefInAttributeTest {
    
    public static void main(String[] args) throws Exception {
        System.out.println("Testing general entity references in attribute values...\n");
        test1_SimpleEntityRef();
        test2_EntityWithNestedRef();
        test3_CircularReference();
        test4_UndefinedEntity();
        test5_ExternalEntityForbidden();
        test6_UnparsedEntityForbidden();
        System.out.println("\n✓ All tests passed!");
    }
    
    /**
     * Test 1: Simple entity reference in attribute value.
     */
    static void test1_SimpleEntityRef() throws Exception {
        System.out.println("Test 1: Simple entity reference in attribute value");
        String xml = "<?xml version='1.0'?>\n" +
                    "<!DOCTYPE root [\n" +
                    "  <!ENTITY copy \"Copyright 2025\">\n" +
                    "]>\n" +
                    "<root attr='&copy;'/>";
        
        AttributeCapture handler = new AttributeCapture();
        Parser parser = new Parser();
        parser.setContentHandler(handler);
        
        ByteArrayInputStream in = new ByteArrayInputStream(xml.getBytes("UTF-8"));
        parser.parse(new org.xml.sax.InputSource(in));
        
        String value = handler.getValue("attr");
        assert value != null : "Attribute 'attr' not found";
        assert value.equals("Copyright 2025") : "Expected 'Copyright 2025', got: '" + value + "'";
        
        System.out.println("  attr='" + value + "'");
        System.out.println("  ✓ Simple entity reference expanded correctly\n");
    }
    
    /**
     * Test 2: Entity with nested entity references.
     */
    static void test2_EntityWithNestedRef() throws Exception {
        System.out.println("Test 2: Entity with nested entity references");
        String xml = "<?xml version='1.0'?>\n" +
                    "<!DOCTYPE root [\n" +
                    "  <!ENTITY inner \"INNER\">\n" +
                    "  <!ENTITY outer \"before &inner; after\">\n" +
                    "]>\n" +
                    "<root attr='[&outer;]'/>";
        
        AttributeCapture handler = new AttributeCapture();
        Parser parser = new Parser();
        parser.setContentHandler(handler);
        
        ByteArrayInputStream in = new ByteArrayInputStream(xml.getBytes("UTF-8"));
        parser.parse(new org.xml.sax.InputSource(in));
        
        String value = handler.getValue("attr");
        assert value != null : "Attribute 'attr' not found";
        assert value.equals("[before INNER after]") : "Expected '[before INNER after]', got: '" + value + "'";
        
        System.out.println("  attr='" + value + "'");
        System.out.println("  ✓ Nested entity references expanded correctly\n");
    }
    
    /**
     * Test 3: Circular entity reference should cause error.
     */
    static void test3_CircularReference() throws Exception {
        System.out.println("Test 3: Circular entity reference detection");
        String xml = "<?xml version='1.0'?>\n" +
                    "<!DOCTYPE root [\n" +
                    "  <!ENTITY a \"before &b; after\">\n" +
                    "  <!ENTITY b \"before &a; after\">\n" +
                    "]>\n" +
                    "<root attr='&a;'/>";
        
        AttributeCapture handler = new AttributeCapture();
        Parser parser = new Parser();
        parser.setContentHandler(handler);
        
        try {
            ByteArrayInputStream in = new ByteArrayInputStream(xml.getBytes("UTF-8"));
            parser.parse(new org.xml.sax.InputSource(in));
            throw new AssertionError("Expected SAXParseException for circular reference");
        } catch (SAXParseException e) {
            assert e.getMessage().contains("Circular") : "Expected 'Circular' in error message, got: " + e.getMessage();
            System.out.println("  ✓ Circular reference detected: " + e.getMessage() + "\n");
        }
    }
    
    /**
     * Test 4: Undefined entity should cause error.
     */
    static void test4_UndefinedEntity() throws Exception {
        System.out.println("Test 4: Undefined entity reference");
        String xml = "<?xml version='1.0'?>\n" +
                    "<!DOCTYPE root []>\n" +
                    "<root attr='&undefined;'/>";
        
        AttributeCapture handler = new AttributeCapture();
        Parser parser = new Parser();
        parser.setContentHandler(handler);
        
        try {
            ByteArrayInputStream in = new ByteArrayInputStream(xml.getBytes("UTF-8"));
            parser.parse(new org.xml.sax.InputSource(in));
            throw new AssertionError("Expected SAXParseException for undefined entity");
        } catch (SAXParseException e) {
            assert e.getMessage().contains("not declared") : "Expected 'not declared' in error message, got: " + e.getMessage();
            System.out.println("  ✓ Undefined entity detected: " + e.getMessage() + "\n");
        }
    }
    
    /**
     * Test 5: External entity reference in attribute value should cause error.
     */
    static void test5_ExternalEntityForbidden() throws Exception {
        System.out.println("Test 5: External entity forbidden in attribute value");
        String xml = "<?xml version='1.0'?>\n" +
                    "<!DOCTYPE root [\n" +
                    "  <!ENTITY external SYSTEM \"external.xml\">\n" +
                    "]>\n" +
                    "<root attr='&external;'/>";
        
        AttributeCapture handler = new AttributeCapture();
        Parser parser = new Parser();
        parser.setContentHandler(handler);
        
        try {
            ByteArrayInputStream in = new ByteArrayInputStream(xml.getBytes("UTF-8"));
            parser.parse(new org.xml.sax.InputSource(in));
            throw new AssertionError("Expected SAXParseException for external entity in attribute");
        } catch (SAXParseException e) {
            assert e.getMessage().contains("External") && e.getMessage().contains("forbidden") : 
                "Expected 'External' and 'forbidden' in error message, got: " + e.getMessage();
            System.out.println("  ✓ External entity rejected: " + e.getMessage() + "\n");
        }
    }
    
    /**
     * Test 6: Unparsed entity reference in attribute value should cause error.
     */
    static void test6_UnparsedEntityForbidden() throws Exception {
        System.out.println("Test 6: Unparsed entity forbidden in attribute value");
        String xml = "<?xml version='1.0'?>\n" +
                    "<!DOCTYPE root [\n" +
                    "  <!NOTATION gif SYSTEM \"image/gif\">\n" +
                    "  <!ENTITY logo SYSTEM \"logo.gif\" NDATA gif>\n" +
                    "]>\n" +
                    "<root attr='&logo;'/>";
        
        AttributeCapture handler = new AttributeCapture();
        Parser parser = new Parser();
        parser.setContentHandler(handler);
        
        try {
            ByteArrayInputStream in = new ByteArrayInputStream(xml.getBytes("UTF-8"));
            parser.parse(new org.xml.sax.InputSource(in));
            throw new AssertionError("Expected SAXParseException for unparsed entity in attribute");
        } catch (SAXParseException e) {
            assert e.getMessage().contains("Unparsed") && e.getMessage().contains("forbidden") : 
                "Expected 'Unparsed' and 'forbidden' in error message, got: " + e.getMessage();
            System.out.println("  ✓ Unparsed entity rejected: " + e.getMessage() + "\n");
        }
    }
    
    /**
     * Helper ContentHandler that captures attribute values.
     */
    static class AttributeCapture implements ContentHandler {
        private String attributeName;
        private String attributeValue;
        
        public String getValue(String name) {
            return attributeValue;
        }
        
        @Override
        public void startElement(String uri, String localName, String qName, Attributes atts) {
            if (atts.getLength() > 0) {
                attributeName = atts.getQName(0);
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


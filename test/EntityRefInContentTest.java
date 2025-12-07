/*
 * Test general entity references in element content.
 */

import java.io.ByteArrayInputStream;
import org.bluezoo.gonzalez.Parser;
import org.xml.sax.ContentHandler;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

/**
 * Tests general entity reference expansion in element content.
 */
public class EntityRefInContentTest {
    
    public static void main(String[] args) throws Exception {
        System.out.println("Testing general entity references in element content...\n");
        test1_SimpleEntityRef();
        test2_EntityWithNestedRef();
        test3_MultipleEntities();
        test4_CircularReference();
        test5_UndefinedEntity();
        test6_UnparsedEntityForbidden();
        test7_ExternalEntityNotYetImplemented();
        System.out.println("\n✓ All tests passed!");
    }
    
    /**
     * Test 1: Simple entity reference in content.
     */
    static void test1_SimpleEntityRef() throws Exception {
        System.out.println("Test 1: Simple entity reference in content");
        String xml = "<?xml version='1.0'?>\n" +
                    "<!DOCTYPE root [\n" +
                    "  <!ENTITY copy \"Copyright 2025\">\n" +
                    "]>\n" +
                    "<root>&copy;</root>";
        
        ContentCapture handler = new ContentCapture();
        Parser parser = new Parser();
        parser.setContentHandler(handler);
        
        ByteArrayInputStream in = new ByteArrayInputStream(xml.getBytes("UTF-8"));
        parser.parse(new org.xml.sax.InputSource(in));
        
        String content = handler.getContent();
        assert content.equals("Copyright 2025") : "Expected 'Copyright 2025', got: '" + content + "'";
        
        System.out.println("  Content: '" + content + "'");
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
                    "<root>&outer;</root>";
        
        ContentCapture handler = new ContentCapture();
        Parser parser = new Parser();
        parser.setContentHandler(handler);
        
        ByteArrayInputStream in = new ByteArrayInputStream(xml.getBytes("UTF-8"));
        parser.parse(new org.xml.sax.InputSource(in));
        
        String content = handler.getContent();
        assert content.equals("before INNER after") : "Expected 'before INNER after', got: '" + content + "'";
        
        System.out.println("  Content: '" + content + "'");
        System.out.println("  ✓ Nested entity references expanded correctly\n");
    }
    
    /**
     * Test 3: Multiple entities in content.
     */
    static void test3_MultipleEntities() throws Exception {
        System.out.println("Test 3: Multiple entities in content");
        String xml = "<?xml version='1.0'?>\n" +
                    "<!DOCTYPE root [\n" +
                    "  <!ENTITY first \"FIRST\">\n" +
                    "  <!ENTITY second \"SECOND\">\n" +
                    "  <!ENTITY third \"THIRD\">\n" +
                    "]>\n" +
                    "<root>&first; &second; &third;</root>";
        
        ContentCapture handler = new ContentCapture();
        Parser parser = new Parser();
        parser.setContentHandler(handler);
        
        ByteArrayInputStream in = new ByteArrayInputStream(xml.getBytes("UTF-8"));
        parser.parse(new org.xml.sax.InputSource(in));
        
        String content = handler.getContent();
        assert content.equals("FIRST SECOND THIRD") : "Expected 'FIRST SECOND THIRD', got: '" + content + "'";
        
        System.out.println("  Content: '" + content + "'");
        System.out.println("  ✓ Multiple entity references expanded correctly\n");
    }
    
    /**
     * Test 4: Circular entity reference should cause error.
     */
    static void test4_CircularReference() throws Exception {
        System.out.println("Test 4: Circular entity reference detection");
        String xml = "<?xml version='1.0'?>\n" +
                    "<!DOCTYPE root [\n" +
                    "  <!ENTITY a \"before &b; after\">\n" +
                    "  <!ENTITY b \"before &a; after\">\n" +
                    "]>\n" +
                    "<root>&a;</root>";
        
        ContentCapture handler = new ContentCapture();
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
     * Test 5: Undefined entity should cause error.
     */
    static void test5_UndefinedEntity() throws Exception {
        System.out.println("Test 5: Undefined entity reference");
        String xml = "<?xml version='1.0'?>\n" +
                    "<!DOCTYPE root []>\n" +
                    "<root>&undefined;</root>";
        
        ContentCapture handler = new ContentCapture();
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
     * Test 6: Unparsed entity reference in content should cause error.
     */
    static void test6_UnparsedEntityForbidden() throws Exception {
        System.out.println("Test 6: Unparsed entity forbidden in content");
        String xml = "<?xml version='1.0'?>\n" +
                    "<!DOCTYPE root [\n" +
                    "  <!NOTATION gif SYSTEM \"image/gif\">\n" +
                    "  <!ENTITY logo SYSTEM \"logo.gif\" NDATA gif>\n" +
                    "]>\n" +
                    "<root>&logo;</root>";
        
        ContentCapture handler = new ContentCapture();
        Parser parser = new Parser();
        parser.setContentHandler(handler);
        
        try {
            ByteArrayInputStream in = new ByteArrayInputStream(xml.getBytes("UTF-8"));
            parser.parse(new org.xml.sax.InputSource(in));
            throw new AssertionError("Expected SAXParseException for unparsed entity in content");
        } catch (SAXParseException e) {
            assert e.getMessage().contains("Unparsed") && e.getMessage().contains("forbidden") : 
                "Expected 'Unparsed' and 'forbidden' in error message, got: " + e.getMessage();
            System.out.println("  ✓ Unparsed entity rejected: " + e.getMessage() + "\n");
        }
    }
    
    /**
     * Test 7: External entity reference should report not yet implemented.
     */
    static void test7_ExternalEntityNotYetImplemented() throws Exception {
        System.out.println("Test 7: External entity requires async resolution");
        String xml = "<?xml version='1.0'?>\n" +
                    "<!DOCTYPE root [\n" +
                    "  <!ENTITY external SYSTEM \"external.xml\">\n" +
                    "]>\n" +
                    "<root>&external;</root>";
        
        ContentCapture handler = new ContentCapture();
        Parser parser = new Parser();
        parser.setContentHandler(handler);
        
        try {
            ByteArrayInputStream in = new ByteArrayInputStream(xml.getBytes("UTF-8"));
            parser.parse(new org.xml.sax.InputSource(in));
            throw new AssertionError("Expected SAXParseException for external entity (not yet implemented)");
        } catch (SAXParseException e) {
            assert e.getMessage().contains("async resolution") && e.getMessage().contains("not yet implemented") : 
                "Expected 'async resolution' and 'not yet implemented' in error message, got: " + e.getMessage();
            System.out.println("  ✓ External entity correctly identified as requiring async: " + e.getMessage() + "\n");
        }
    }
    
    /**
     * Helper ContentHandler that captures character content.
     */
    static class ContentCapture implements ContentHandler {
        private StringBuilder content = new StringBuilder();
        
        public String getContent() {
            return content.toString();
        }
        
        @Override
        public void characters(char[] ch, int start, int length) {
            content.append(ch, start, length);
        }
        
        // Unused ContentHandler methods
        @Override public void setDocumentLocator(Locator locator) {}
        @Override public void startDocument() {}
        @Override public void endDocument() {}
        @Override public void startElement(String uri, String localName, String qName, org.xml.sax.Attributes atts) {}
        @Override public void endElement(String uri, String localName, String qName) {}
        @Override public void ignorableWhitespace(char[] ch, int start, int length) {}
        @Override public void processingInstruction(String target, String data) {}
        @Override public void startPrefixMapping(String prefix, String uri) {}
        @Override public void endPrefixMapping(String prefix) {}
        @Override public void skippedEntity(String name) {}
    }
}


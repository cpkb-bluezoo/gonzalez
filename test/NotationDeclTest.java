import org.bluezoo.gonzalez.Parser;
import org.bluezoo.gonzalez.ExternalID;
import org.xml.sax.*;
import org.xml.sax.helpers.DefaultHandler;
import java.io.*;
import java.util.*;

/**
 * Tests NOTATION declaration parsing.
 */
public class NotationDeclTest {
    
    public static void main(String[] args) throws Exception {
        System.out.println("=== Notation Declaration Test ===\n");
        
        // Test 1: SYSTEM notation
        testSystemNotation();
        
        // Test 2: PUBLIC notation (with system ID)
        testPublicNotationWithSystemId();
        
        // Test 3: PUBLIC notation (without system ID)
        testPublicNotationWithoutSystemId();
        
        // Test 4: Multiple notations
        testMultipleNotations();
        
        System.out.println("\n✓ All tests passed!");
    }
    
    /**
     * Test SYSTEM notation.
     */
    private static void testSystemNotation() throws Exception {
        System.out.println("Test 1: SYSTEM notation");
        
        String xml = "<?xml version='1.0'?>\n" +
                     "<!DOCTYPE root [\n" +
                     "  <!NOTATION gif SYSTEM \"image-gif\">\n" +
                     "]>\n" +
                     "<root/>";
        
        TestHandler handler = new TestHandler();
        Parser parser = new Parser();
        parser.setDTDHandler(handler);
        
        ByteArrayInputStream bais = new ByteArrayInputStream(xml.getBytes("UTF-8"));
        parser.parse(new InputSource(bais));
        
        // Check notation was reported
        if (handler.notations.size() != 1) {
            throw new AssertionError("Expected 1 notation, got: " + handler.notations.size());
        }
        
        ExternalID extID = handler.notations.get("gif");
        if (extID == null) {
            throw new AssertionError("Notation 'gif' not found");
        }
        if (extID.publicId != null) {
            throw new AssertionError("Expected null publicId for SYSTEM, got: " + extID.publicId);
        }
        if (!"image-gif".equals(extID.systemId)) {
            throw new AssertionError("Expected systemId='image-gif', got: " + extID.systemId);
        }
        
        System.out.println("  ✓ SYSTEM notation parsed correctly");
    }
    
    /**
     * Test PUBLIC notation with system ID.
     */
    private static void testPublicNotationWithSystemId() throws Exception {
        System.out.println("\nTest 2: PUBLIC notation (with system ID)");
        
        String xml = "<?xml version='1.0'?>\n" +
                     "<!DOCTYPE root [\n" +
                     "  <!NOTATION jpeg PUBLIC \"IDGNOTATIONJPEG\" \"jpeg.dtd\">\n" +
                     "]>\n" +
                     "<root/>";
        
        TestHandler handler = new TestHandler();
        Parser parser = new Parser();
        parser.setDTDHandler(handler);
        
        ByteArrayInputStream bais = new ByteArrayInputStream(xml.getBytes("UTF-8"));
        parser.parse(new InputSource(bais));
        
        // Check notation
        ExternalID extID = handler.notations.get("jpeg");
        if (extID == null) {
            throw new AssertionError("Notation 'jpeg' not found");
        }
        if (!"IDGNOTATIONJPEG".equals(extID.publicId)) {
            throw new AssertionError("Expected publicId='IDGNOTATIONJPEG', got: " + extID.publicId);
        }
        if (!"jpeg.dtd".equals(extID.systemId)) {
            throw new AssertionError("Expected systemId='jpeg.dtd', got: " + extID.systemId);
        }
        
        System.out.println("  ✓ PUBLIC notation (with system ID) parsed correctly");
    }
    
    /**
     * Test PUBLIC notation without system ID.
     */
    private static void testPublicNotationWithoutSystemId() throws Exception {
        System.out.println("\nTest 3: PUBLIC notation (without system ID)");
        
        String xml = "<?xml version='1.0'?>\n" +
                     "<!DOCTYPE root [\n" +
                     "  <!NOTATION html PUBLIC \"W3CDTDHTML401EN\">\n" +
                     "]>\n" +
                     "<root/>";
        
        TestHandler handler = new TestHandler();
        Parser parser = new Parser();
        parser.setDTDHandler(handler);
        
        ByteArrayInputStream bais = new ByteArrayInputStream(xml.getBytes("UTF-8"));
        parser.parse(new InputSource(bais));
        
        // Check notation
        ExternalID extID = handler.notations.get("html");
        if (extID == null) {
            throw new AssertionError("Notation 'html' not found");
        }
        if (!"W3CDTDHTML401EN".equals(extID.publicId)) {
            throw new AssertionError("Expected publicId='W3CDTDHTML401EN', got: " + extID.publicId);
        }
        if (extID.systemId != null) {
            throw new AssertionError("Expected null systemId, got: " + extID.systemId);
        }
        
        System.out.println("  ✓ PUBLIC notation (without system ID) parsed correctly");
    }
    
    /**
     * Test multiple notations.
     */
    private static void testMultipleNotations() throws Exception {
        System.out.println("\nTest 4: Multiple notations");
        
        String xml = "<?xml version='1.0'?>\n" +
                     "<!DOCTYPE root [\n" +
                     "  <!NOTATION gif SYSTEM \"image-gif\">\n" +
                     "  <!NOTATION jpeg SYSTEM \"image-jpeg\">\n" +
                     "  <!NOTATION png SYSTEM \"image-png\">\n" +
                     "]>\n" +
                     "<root/>";
        
        TestHandler handler = new TestHandler();
        Parser parser = new Parser();
        parser.setDTDHandler(handler);
        
        ByteArrayInputStream bais = new ByteArrayInputStream(xml.getBytes("UTF-8"));
        parser.parse(new InputSource(bais));
        
        // Check all notations
        if (handler.notations.size() != 3) {
            throw new AssertionError("Expected 3 notations, got: " + handler.notations.size());
        }
        
        String[] names = {"gif", "jpeg", "png"};
        String[] systemIds = {"image-gif", "image-jpeg", "image-png"};
        
        for (int i = 0; i < names.length; i++) {
            ExternalID extID = handler.notations.get(names[i]);
            if (extID == null) {
                throw new AssertionError("Notation '" + names[i] + "' not found");
            }
            if (!systemIds[i].equals(extID.systemId)) {
                throw new AssertionError("Notation '" + names[i] + "': expected systemId='" + 
                    systemIds[i] + "', got: " + extID.systemId);
            }
        }
        
        System.out.println("  ✓ Multiple notations parsed correctly");
    }
    
    /**
     * Handler that captures notation declarations.
     */
    private static class TestHandler extends DefaultHandler implements DTDHandler {
        Map<String, ExternalID> notations = new HashMap<>();
        
        @Override
        public void notationDecl(String name, String publicId, String systemId) {
            notations.put(name, new ExternalID(publicId, systemId));
            System.out.println("  notationDecl: name=" + name + 
                ", publicId=" + (publicId != null ? "'" + publicId + "'" : "null") +
                ", systemId=" + (systemId != null ? "'" + systemId + "'" : "null"));
        }
        
        @Override
        public void unparsedEntityDecl(String name, String publicId, String systemId, String notationName) {
            // Not used in this test
        }
    }
}


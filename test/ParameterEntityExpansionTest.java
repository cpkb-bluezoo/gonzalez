/*
 * Test parameter entity expansion in entity values.
 */

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import org.bluezoo.gonzalez.DTDParser;
import org.bluezoo.gonzalez.ContentParser;
import org.bluezoo.gonzalez.Tokenizer;
import org.bluezoo.gonzalez.EntityDeclaration;
import org.bluezoo.gonzalez.EntityExpansionHelper;
import org.bluezoo.gonzalez.EntityExpansionContext;

/**
 * Tests parameter entity expansion in entity values.
 */
public class ParameterEntityExpansionTest {
    
    public static void main(String[] args) throws Exception {
        System.out.println("Testing parameter entity expansion...\n");
        test1_SimpleParameterEntityInEntityValue();
        test2_NestedParameterEntities();
        test3_CircularParameterEntityReference();
        test4_UndefinedParameterEntity();
        test5_MixedGeneralAndParameterEntities();
        System.out.println("\n✓ All tests passed!");
    }
    
    /**
     * Test 1: Simple parameter entity reference in entity value.
     */
    static void test1_SimpleParameterEntityInEntityValue() throws Exception {
        System.out.println("Test 1: Simple parameter entity in entity value");
        String xml = "<?xml version='1.0'?>\n" +
                    "<!DOCTYPE root [\n" +
                    "  <!ENTITY % common \"CDATA\">\n" +
                    "  <!ENTITY % combined \"type is %common;\">\n" +
                    "]>\n" +
                    "<root/>";
        
        // Parse the document
        ContentParser xmlParser = new ContentParser();
        Tokenizer tokenizer = new Tokenizer(xmlParser);
        
        ByteArrayInputStream in = new ByteArrayInputStream(xml.getBytes("UTF-8"));
        byte[] buffer = new byte[1024];
        int bytesRead;
        while ((bytesRead = in.read(buffer)) != -1) {
            tokenizer.receive(ByteBuffer.wrap(buffer, 0, bytesRead));
        }
        tokenizer.close();
        
        // Get the DTDParser
        DTDParser dtdParser = xmlParser.getDTDParser();
        if (dtdParser == null) {
            throw new AssertionError("DTDParser is null");
        }
        
        // Get the combined parameter entity
        EntityDeclaration combined = dtdParser.getParameterEntity("combined");
        if (combined == null) {
            throw new AssertionError("Parameter entity 'combined' not found");
        }
        
        // Expand it
        EntityExpansionHelper helper = new EntityExpansionHelper(dtdParser, tokenizer);
        String expanded = helper.expandEntityValue(combined.replacementText, EntityExpansionContext.ENTITY_VALUE);
        
        String expected = "type is CDATA";
        if (!expanded.equals(expected)) {
            throw new AssertionError("Expected: '" + expected + "', got: '" + expanded + "'");
        }
        
        System.out.println("  Expanded value: '" + expanded + "'");
        System.out.println("  ✓ Simple parameter entity expanded correctly\n");
    }
    
    /**
     * Test 2: Nested parameter entity references.
     */
    static void test2_NestedParameterEntities() throws Exception {
        System.out.println("Test 2: Nested parameter entities");
        String xml = "<?xml version='1.0'?>\n" +
                    "<!DOCTYPE root [\n" +
                    "  <!ENTITY % inner \"INNER\">\n" +
                    "  <!ENTITY % middle \"[%inner;]\">\n" +
                    "  <!ENTITY % outer \"before %middle; after\">\n" +
                    "]>\n" +
                    "<root/>";
        
        ContentParser xmlParser = new ContentParser();
        Tokenizer tokenizer = new Tokenizer(xmlParser);
        
        ByteArrayInputStream in = new ByteArrayInputStream(xml.getBytes("UTF-8"));
        byte[] buffer = new byte[1024];
        int bytesRead;
        while ((bytesRead = in.read(buffer)) != -1) {
            tokenizer.receive(ByteBuffer.wrap(buffer, 0, bytesRead));
        }
        tokenizer.close();
        
        DTDParser dtdParser = xmlParser.getDTDParser();
        EntityDeclaration outer = dtdParser.getParameterEntity("outer");
        
        EntityExpansionHelper helper = new EntityExpansionHelper(dtdParser, tokenizer);
        String expanded = helper.expandEntityValue(outer.replacementText, EntityExpansionContext.ENTITY_VALUE);
        
        String expected = "before [INNER] after";
        if (!expanded.equals(expected)) {
            throw new AssertionError("Expected: '" + expected + "', got: '" + expanded + "'");
        }
        
        System.out.println("  Expanded value: '" + expanded + "'");
        System.out.println("  ✓ Nested parameter entities expanded correctly\n");
    }
    
    /**
     * Test 3: Circular parameter entity reference detection.
     */
    static void test3_CircularParameterEntityReference() throws Exception {
        System.out.println("Test 3: Circular parameter entity reference");
        String xml = "<?xml version='1.0'?>\n" +
                    "<!DOCTYPE root [\n" +
                    "  <!ENTITY % a \"%b;\">\n" +
                    "  <!ENTITY % b \"%a;\">\n" +
                    "]>\n" +
                    "<root/>";
        
        ContentParser xmlParser = new ContentParser();
        Tokenizer tokenizer = new Tokenizer(xmlParser);
        
        try {
            ByteArrayInputStream in = new ByteArrayInputStream(xml.getBytes("UTF-8"));
            byte[] buffer = new byte[1024];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                tokenizer.receive(ByteBuffer.wrap(buffer, 0, bytesRead));
            }
            tokenizer.close();
            
            throw new AssertionError("Expected circular reference error");
        } catch (org.xml.sax.SAXParseException e) {
            if (!e.getMessage().contains("Circular")) {
                throw new AssertionError("Expected 'Circular' in error message, got: " + e.getMessage());
            }
            System.out.println("  ✓ Circular reference detected: " + e.getMessage() + "\n");
        }
    }
    
    /**
     * Test 4: Undefined parameter entity.
     */
    static void test4_UndefinedParameterEntity() throws Exception {
        System.out.println("Test 4: Undefined parameter entity");
        String xml = "<?xml version='1.0'?>\n" +
                    "<!DOCTYPE root [\n" +
                    "  <!ENTITY % test \"%undefined;\">\n" +
                    "]>\n" +
                    "<root/>";
        
        ContentParser xmlParser = new ContentParser();
        Tokenizer tokenizer = new Tokenizer(xmlParser);
        
        try {
            ByteArrayInputStream in = new ByteArrayInputStream(xml.getBytes("UTF-8"));
            byte[] buffer = new byte[1024];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                tokenizer.receive(ByteBuffer.wrap(buffer, 0, bytesRead));
            }
            tokenizer.close();
            
            throw new AssertionError("Expected undefined entity error");
        } catch (org.xml.sax.SAXParseException e) {
            if (!e.getMessage().contains("Undefined")) {
                throw new AssertionError("Expected 'Undefined' in error message, got: " + e.getMessage());
            }
            System.out.println("  ✓ Undefined entity detected: " + e.getMessage() + "\n");
        }
    }
    
    /**
     * Test 5: Mixed general and parameter entity references.
     */
    static void test5_MixedGeneralAndParameterEntities() throws Exception {
        System.out.println("Test 5: Mixed general and parameter entities");
        String xml = "<?xml version='1.0'?>\n" +
                    "<!DOCTYPE root [\n" +
                    "  <!ENTITY % param \"PARAM\">\n" +
                    "  <!ENTITY general \"GENERAL\">\n" +
                    "  <!ENTITY mixed \"[%param;] and [&general;]\">\n" +
                    "]>\n" +
                    "<root/>";
        
        ContentParser xmlParser = new ContentParser();
        Tokenizer tokenizer = new Tokenizer(xmlParser);
        
        ByteArrayInputStream in = new ByteArrayInputStream(xml.getBytes("UTF-8"));
        byte[] buffer = new byte[1024];
        int bytesRead;
        while ((bytesRead = in.read(buffer)) != -1) {
            tokenizer.receive(ByteBuffer.wrap(buffer, 0, bytesRead));
        }
        tokenizer.close();
        
        DTDParser dtdParser = xmlParser.getDTDParser();
        EntityDeclaration mixed = dtdParser.getGeneralEntity("mixed");
        
        EntityExpansionHelper helper = new EntityExpansionHelper(dtdParser, tokenizer);
        String expanded = helper.expandEntityValue(mixed.replacementText, EntityExpansionContext.ENTITY_VALUE);
        
        String expected = "[PARAM] and [GENERAL]";
        if (!expanded.equals(expected)) {
            throw new AssertionError("Expected: '" + expected + "', got: '" + expanded + "'");
        }
        
        System.out.println("  Expanded value: '" + expanded + "'");
        System.out.println("  ✓ Mixed general and parameter entities expanded correctly\n");
    }
}


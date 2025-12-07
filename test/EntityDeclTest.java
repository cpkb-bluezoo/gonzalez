import org.bluezoo.gonzalez.*;
import java.io.*;

public class EntityDeclTest {
    
    public static void main(String[] args) throws Exception {
        System.out.println("Testing Entity Declaration Parsing\n");
        
        // Test 1: Internal general entity
        test1_InternalGeneralEntity();
        
        // Test 2: Internal general entity with entity references
        test2_EntityWithReferences();
        
        // Test 3: External parsed entity
        test3_ExternalParsedEntity();
        
        // Test 4: External unparsed entity
        test4_ExternalUnparsedEntity();
        
        // Test 5: Parameter entity
        test5_ParameterEntity();
        
        System.out.println("\n✓ All tests passed!");
    }
    
    static DTDParser getDTDParser(Parser parser) {
        // Access the ContentParser's DTDParser through reflection or direct access
        try {
            java.lang.reflect.Field xmlParserField = Parser.class.getDeclaredField("xmlParser");
            xmlParserField.setAccessible(true);
            ContentParser xmlParser = (ContentParser) xmlParserField.get(parser);
            return xmlParser.getDTDParser();
        } catch (Exception e) {
            throw new RuntimeException("Cannot access DTDParser", e);
        }
    }
    
    static void test1_InternalGeneralEntity() throws Exception {
        System.out.println("Test 1: Internal general entity");
        String xml = "<?xml version='1.0'?>\n" +
                    "<!DOCTYPE root [\n" +
                    "  <!ENTITY copy \"Copyright 2025\">\n" +
                    "]>\n" +
                    "<root/>";
        
        Parser parser = new Parser();
        ByteArrayInputStream in = new ByteArrayInputStream(xml.getBytes("UTF-8"));
        parser.parse(new org.xml.sax.InputSource(in));
        
        DTDParser dtdParser = getDTDParser(parser);
        EntityDeclaration entity = dtdParser.getGeneralEntity("copy");
        assert entity != null : "Entity 'copy' not found";
        assert !entity.isParameter : "Should not be parameter entity";
        assert entity.isInternal() : "Should be internal";
        assert entity.replacementText != null : "Should have replacement text";
        assert entity.replacementText.size() == 1 : "Should have 1 part";
        assert entity.replacementText.get(0).equals("Copyright 2025") : "Wrong replacement text: " + entity.replacementText.get(0);
        
        System.out.println("  ✓ Internal general entity parsed correctly");
    }
    
    static void test2_EntityWithReferences() throws Exception {
        System.out.println("\nTest 2: Entity with entity references");
        String xml = "<?xml version='1.0'?>\n" +
                    "<!DOCTYPE root [\n" +
                    "  <!ENTITY combined \"before &middle; after\">\n" +
                    "]>\n" +
                    "<root/>";
        
        Parser parser = new Parser();
        ByteArrayInputStream in = new ByteArrayInputStream(xml.getBytes("UTF-8"));
        parser.parse(new org.xml.sax.InputSource(in));
        
        DTDParser dtdParser = getDTDParser(parser);
        EntityDeclaration entity = dtdParser.getGeneralEntity("combined");
        assert entity != null : "Entity 'combined' not found";
        assert entity.replacementText.size() == 3 : "Should have 3 parts, got: " + entity.replacementText.size();
        
        Object part0 = entity.replacementText.get(0);
        Object part1 = entity.replacementText.get(1);
        Object part2 = entity.replacementText.get(2);
        
        assert part0 instanceof String && part0.equals("before ") : "Part 0 should be 'before '";
        assert part1 instanceof GeneralEntityReference : "Part 1 should be GeneralEntityReference";
        assert ((GeneralEntityReference)part1).name.equals("middle") : "Part 1 should reference 'middle'";
        assert part2 instanceof String && part2.equals(" after") : "Part 2 should be ' after'";
        
        System.out.println("  Entity value: " + entity.replacementText);
        System.out.println("  ✓ Entity with references parsed correctly");
    }
    
    static void test3_ExternalParsedEntity() throws Exception {
        System.out.println("\nTest 3: External parsed entity");
        String xml = "<?xml version='1.0'?>\n" +
                    "<!DOCTYPE root [\n" +
                    "  <!ENTITY chapter SYSTEM \"chapter1.xml\">\n" +
                    "]>\n" +
                    "<root/>";
        
        Parser parser = new Parser();
        ByteArrayInputStream in = new ByteArrayInputStream(xml.getBytes("UTF-8"));
        parser.parse(new org.xml.sax.InputSource(in));
        
        DTDParser dtdParser = getDTDParser(parser);
        EntityDeclaration entity = dtdParser.getGeneralEntity("chapter");
        assert entity != null : "Entity 'chapter' not found";
        assert !entity.isParameter : "Should not be parameter entity";
        assert entity.isExternal() : "Should be external";
        assert entity.isParsed() : "Should be parsed entity";
        assert !entity.isUnparsed() : "Should not be unparsed";
        assert entity.externalID != null : "Should have external ID";
        assert entity.externalID.systemId.contains("chapter1") : "Wrong system ID: " + entity.externalID.systemId;
        
        System.out.println("  ✓ External parsed entity parsed correctly");
    }
    
    static void test4_ExternalUnparsedEntity() throws Exception {
        System.out.println("\nTest 4: External unparsed entity");
        String xml = "<?xml version='1.0'?>\n" +
                    "<!DOCTYPE root [\n" +
                    "  <!NOTATION gif SYSTEM \"image-gif\">\n" +
                    "  <!ENTITY logo SYSTEM \"logo.gif\" NDATA gif>\n" +
                    "]>\n" +
                    "<root/>";
        
        Parser parser = new Parser();
        ByteArrayInputStream in = new ByteArrayInputStream(xml.getBytes("UTF-8"));
        parser.parse(new org.xml.sax.InputSource(in));
        
        DTDParser dtdParser = getDTDParser(parser);
        EntityDeclaration entity = dtdParser.getGeneralEntity("logo");
        assert entity != null : "Entity 'logo' not found";
        assert !entity.isParameter : "Should not be parameter entity";
        assert entity.isExternal() : "Should be external";
        assert !entity.isParsed() : "Should not be parsed entity";
        assert entity.isUnparsed() : "Should be unparsed";
        assert entity.notationName.equals("gif") : "Wrong notation name: " + entity.notationName;
        
        System.out.println("  ✓ External unparsed entity parsed correctly");
    }
    
    static void test5_ParameterEntity() throws Exception {
        System.out.println("\nTest 5: Parameter entity");
        String xml = "<?xml version='1.0'?>\n" +
                    "<!DOCTYPE root [\n" +
                    "  <!ENTITY % common \"value\">\n" +
                    "]>\n" +
                    "<root/>";
        
        Parser parser = new Parser();
        ByteArrayInputStream in = new ByteArrayInputStream(xml.getBytes("UTF-8"));
        parser.parse(new org.xml.sax.InputSource(in));
        
        DTDParser dtdParser = getDTDParser(parser);
        EntityDeclaration entity = dtdParser.getParameterEntity("common");
        assert entity != null : "Parameter entity 'common' not found";
        assert entity.isParameter : "Should be parameter entity";
        assert entity.isInternal() : "Should be internal";
        assert entity.replacementText.size() == 1 : "Should have 1 part";
        assert entity.replacementText.get(0).equals("value") : "Wrong value";
        
        System.out.println("  ✓ Parameter entity parsed correctly");
    }
}


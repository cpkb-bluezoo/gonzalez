/*
 * Test ATTLIST default value parsing with entity references.
 */

import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import org.bluezoo.gonzalez.AttributeDeclaration;
import org.bluezoo.gonzalez.DTDParser;
import org.bluezoo.gonzalez.GeneralEntityReference;
import org.bluezoo.gonzalez.Parser;

/**
 * Tests ATTLIST default value parsing with List<Object> structure.
 */
public class AttlistDefaultValueTest {
    
    public static void main(String[] args) throws Exception {
        System.out.println("Testing ATTLIST default value parsing...\n");
        test1_SimpleDefaultValue();
        test2_DefaultWithEntityRef();
        test3_FixedValueWithEntityRef();
        System.out.println("\n✓ All tests passed!");
    }
    
    /**
     * Test 1: Simple default value with no entity references.
     */
    static void test1_SimpleDefaultValue() throws Exception {
        System.out.println("Test 1: Simple default value");
        String xml = "<?xml version='1.0'?>\n" +
                    "<!DOCTYPE root [\n" +
                    "  <!ATTLIST root\n" +
                    "    attr CDATA \"default value\"\n" +
                    "  >\n" +
                    "]>\n" +
                    "<root/>";
        
        Parser parser = new Parser();
        ByteArrayInputStream in = new ByteArrayInputStream(xml.getBytes("UTF-8"));
        parser.parse(new org.xml.sax.InputSource(in));
        
        DTDParser dtdParser = getDTDParser(parser);
        AttributeDeclaration attrDecl = dtdParser.getAttributeDeclaration("root", "attr");
        assert attrDecl != null : "Attribute declaration for 'attr' not found";
        assert attrDecl.defaultValue != null : "Default value is null";
        assert attrDecl.defaultValue.size() == 1 : "Expected 1 part, got: " + attrDecl.defaultValue.size();
        assert attrDecl.defaultValue.get(0) instanceof String : "Part 0 should be String";
        assert attrDecl.defaultValue.get(0).equals("default value") : "Expected 'default value', got: " + attrDecl.defaultValue.get(0);
        
        System.out.println("  Default value: " + attrDecl.defaultValue);
        System.out.println("  ✓ Simple default value parsed correctly\n");
    }
    
    /**
     * Test 2: Default value with entity reference.
     */
    static void test2_DefaultWithEntityRef() throws Exception {
        System.out.println("Test 2: Default value with entity reference");
        String xml = "<?xml version='1.0'?>\n" +
                    "<!DOCTYPE root [\n" +
                    "  <!ENTITY copy \"Copyright 2025\">\n" +
                    "  <!ATTLIST root\n" +
                    "    attr CDATA \"before &copy; after\"\n" +
                    "  >\n" +
                    "]>\n" +
                    "<root/>";
        
        Parser parser = new Parser();
        ByteArrayInputStream in = new ByteArrayInputStream(xml.getBytes("UTF-8"));
        parser.parse(new org.xml.sax.InputSource(in));
        
        DTDParser dtdParser = getDTDParser(parser);
        AttributeDeclaration attrDecl = dtdParser.getAttributeDeclaration("root", "attr");
        assert attrDecl != null : "Attribute declaration for 'attr' not found";
        assert attrDecl.defaultValue != null : "Default value is null";
        assert attrDecl.defaultValue.size() == 3 : "Expected 3 parts, got: " + attrDecl.defaultValue.size();
        
        Object part0 = attrDecl.defaultValue.get(0);
        Object part1 = attrDecl.defaultValue.get(1);
        Object part2 = attrDecl.defaultValue.get(2);
        
        assert part0 instanceof String && part0.equals("before ") : "Part 0 should be 'before '";
        assert part1 instanceof GeneralEntityReference : "Part 1 should be GeneralEntityReference";
        assert ((GeneralEntityReference)part1).name.equals("copy") : "Part 1 should reference 'copy'";
        assert part2 instanceof String && part2.equals(" after") : "Part 2 should be ' after'";
        
        System.out.println("  Default value: " + attrDecl.defaultValue);
        System.out.println("  ✓ Default value with entity reference parsed correctly\n");
    }
    
    /**
     * Test 3: #FIXED value with entity reference.
     */
    static void test3_FixedValueWithEntityRef() throws Exception {
        System.out.println("Test 3: #FIXED value with entity reference");
        String xml = "<?xml version='1.0'?>\n" +
                    "<!DOCTYPE root [\n" +
                    "  <!ENTITY version \"1.0\">\n" +
                    "  <!ATTLIST root\n" +
                    "    ver CDATA #FIXED \"v&version;\"\n" +
                    "  >\n" +
                    "]>\n" +
                    "<root/>";
        
        Parser parser = new Parser();
        ByteArrayInputStream in = new ByteArrayInputStream(xml.getBytes("UTF-8"));
        parser.parse(new org.xml.sax.InputSource(in));
        
        DTDParser dtdParser = getDTDParser(parser);
        AttributeDeclaration attrDecl = dtdParser.getAttributeDeclaration("root", "ver");
        assert attrDecl != null : "Attribute declaration for 'ver' not found";
        assert attrDecl.mode.equals("#FIXED") : "Expected mode #FIXED";
        assert attrDecl.defaultValue != null : "Default value is null";
        assert attrDecl.defaultValue.size() == 2 : "Expected 2 parts, got: " + attrDecl.defaultValue.size();
        
        Object part0 = attrDecl.defaultValue.get(0);
        Object part1 = attrDecl.defaultValue.get(1);
        
        assert part0 instanceof String && part0.equals("v") : "Part 0 should be 'v'";
        assert part1 instanceof GeneralEntityReference : "Part 1 should be GeneralEntityReference";
        assert ((GeneralEntityReference)part1).name.equals("version") : "Part 1 should reference 'version'";
        
        System.out.println("  Fixed value: " + attrDecl.defaultValue);
        System.out.println("  ✓ #FIXED value with entity reference parsed correctly\n");
    }
    
    /**
     * Helper to get DTDParser from Parser via reflection.
     */
    static DTDParser getDTDParser(Parser parser) throws Exception {
        Field xmlParserField = Parser.class.getDeclaredField("xmlParser");
        xmlParserField.setAccessible(true);
        Object xmlParser = xmlParserField.get(parser);
        
        Field dtdParserField = xmlParser.getClass().getDeclaredField("dtdParser");
        dtdParserField.setAccessible(true);
        return (DTDParser) dtdParserField.get(xmlParser);
    }
}


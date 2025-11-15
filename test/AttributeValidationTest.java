import org.bluezoo.gonzalez.*;
import org.xml.sax.*;
import org.xml.sax.helpers.*;
import java.io.*;

/**
 * Tests DTD attribute validation.
 */
public class AttributeValidationTest {
    
    public static void main(String[] args) throws Exception {
        System.out.println("Testing attribute validation...\n");
        
        test1_RequiredAttribute();
        test2_IDAttribute();
        test3_IDREFAttribute();
        test4_NMTOKENAttribute();
        
        System.out.println("\n✓ All tests passed!");
    }
    
    static void test1_RequiredAttribute() throws Exception {
        System.out.println("Test 1: #REQUIRED attributes");
        
        // Valid: required attribute present
        String validXml =
            "<?xml version='1.0'?>\n" +
            "<!DOCTYPE root [\n" +
            "  <!ELEMENT root EMPTY>\n" +
            "  <!ATTLIST root\n" +
            "    id CDATA #REQUIRED>\n" +
            "]>\n" +
            "<root id='test'/>";
        
        parseWithValidation(validXml);
        System.out.println("  ✓ Required attribute present accepted");
        
        // Invalid: required attribute missing
        String invalidXml =
            "<?xml version='1.0'?>\n" +
            "<!DOCTYPE root [\n" +
            "  <!ELEMENT root EMPTY>\n" +
            "  <!ATTLIST root\n" +
            "    id CDATA #REQUIRED>\n" +
            "]>\n" +
            "<root/>";
        
        try {
            parseWithValidation(invalidXml);
            throw new Exception("Should reject missing required attribute");
        } catch (SAXException e) {
            if (e.getMessage().contains("Required attribute")) {
                System.out.println("  ✓ Missing required attribute rejected");
            } else {
                throw e;
            }
        }
    }
    
    static void test2_IDAttribute() throws Exception {
        System.out.println("\nTest 2: ID attributes");
        
        // Valid: unique IDs
        String validXml =
            "<?xml version='1.0'?>\n" +
            "<!DOCTYPE root [\n" +
            "  <!ELEMENT root (a,b)>\n" +
            "  <!ELEMENT a EMPTY>\n" +
            "  <!ELEMENT b EMPTY>\n" +
            "  <!ATTLIST a id ID #REQUIRED>\n" +
            "  <!ATTLIST b id ID #REQUIRED>\n" +
            "]>\n" +
            "<root><a id='first'/><b id='second'/></root>";
        
        parseWithValidation(validXml);
        System.out.println("  ✓ Unique IDs accepted");
        
        // Invalid: duplicate ID
        String invalidXml =
            "<?xml version='1.0'?>\n" +
            "<!DOCTYPE root [\n" +
            "  <!ELEMENT root (a,b)>\n" +
            "  <!ELEMENT a EMPTY>\n" +
            "  <!ELEMENT b EMPTY>\n" +
            "  <!ATTLIST a id ID #REQUIRED>\n" +
            "  <!ATTLIST b id ID #REQUIRED>\n" +
            "]>\n" +
            "<root><a id='same'/><b id='same'/></root>";
        
        try {
            parseWithValidation(invalidXml);
            throw new Exception("Should reject duplicate ID");
        } catch (SAXException e) {
            if (e.getMessage().contains("already declared")) {
                System.out.println("  ✓ Duplicate ID rejected");
            } else {
                throw e;
            }
        }
    }
    
    static void test3_IDREFAttribute() throws Exception {
        System.out.println("\nTest 3: IDREF attributes");
        
        // Valid: IDREF points to declared ID
        String validXml =
            "<?xml version='1.0'?>\n" +
            "<!DOCTYPE root [\n" +
            "  <!ELEMENT root (a,b)>\n" +
            "  <!ELEMENT a EMPTY>\n" +
            "  <!ELEMENT b EMPTY>\n" +
            "  <!ATTLIST a id ID #REQUIRED>\n" +
            "  <!ATTLIST b ref IDREF #REQUIRED>\n" +
            "]>\n" +
            "<root><a id='target'/><b ref='target'/></root>";
        
        parseWithValidation(validXml);
        System.out.println("  ✓ Valid IDREF accepted");
        
        // Invalid: IDREF points to undeclared ID
        String invalidXml =
            "<?xml version='1.0'?>\n" +
            "<!DOCTYPE root [\n" +
            "  <!ELEMENT root (a)>\n" +
            "  <!ELEMENT a EMPTY>\n" +
            "  <!ATTLIST a ref IDREF #REQUIRED>\n" +
            "]>\n" +
            "<root><a ref='nonexistent'/></root>";
        
        try {
            parseWithValidation(invalidXml);
            throw new Exception("Should reject IDREF to undeclared ID");
        } catch (SAXException e) {
            if (e.getMessage().contains("undeclared ID")) {
                System.out.println("  ✓ Invalid IDREF rejected");
            } else {
                throw e;
            }
        }
    }
    
    static void test4_NMTOKENAttribute() throws Exception {
        System.out.println("\nTest 4: NMTOKEN attributes");
        
        // Valid: valid NMTOKEN
        String validXml =
            "<?xml version='1.0'?>\n" +
            "<!DOCTYPE root [\n" +
            "  <!ELEMENT root EMPTY>\n" +
            "  <!ATTLIST root\n" +
            "    token NMTOKEN #REQUIRED>\n" +
            "]>\n" +
            "<root token='valid-token_123'/>";
        
        parseWithValidation(validXml);
        System.out.println("  ✓ Valid NMTOKEN accepted");
        
        // Invalid: NMTOKEN with space
        String invalidXml =
            "<?xml version='1.0'?>\n" +
            "<!DOCTYPE root [\n" +
            "  <!ELEMENT root EMPTY>\n" +
            "  <!ATTLIST root\n" +
            "    token NMTOKEN #REQUIRED>\n" +
            "]>\n" +
            "<root token='invalid token'/>";
        
        try {
            parseWithValidation(invalidXml);
            throw new Exception("Should reject NMTOKEN with space");
        } catch (SAXException e) {
            if (e.getMessage().contains("not a valid NMTOKEN")) {
                System.out.println("  ✓ Invalid NMTOKEN rejected");
            } else {
                throw e;
            }
        }
    }
    
    static void parseWithValidation(String xml) throws Exception {
        org.bluezoo.gonzalez.Parser parser = new org.bluezoo.gonzalez.Parser();
        parser.setContentHandler(new DefaultHandler());
        parser.setFeature("http://xml.org/sax/features/validation", true);
        
        ByteArrayInputStream bais = new ByteArrayInputStream(xml.getBytes("UTF-8"));
        parser.parse(new InputSource(bais));
    }
}


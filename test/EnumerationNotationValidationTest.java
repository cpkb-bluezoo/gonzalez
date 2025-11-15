import org.bluezoo.gonzalez.*;
import org.xml.sax.*;
import org.xml.sax.helpers.*;
import java.io.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Tests enumeration and NOTATION attribute validation.
 */
public class EnumerationNotationValidationTest {
    
    /**
     * ErrorHandler that collects validation errors.
     */
    static class ErrorCollector implements ErrorHandler {
        List<SAXParseException> errors = new ArrayList<>();
        
        public void error(SAXParseException e) {
            errors.add(e);
        }
        
        public void warning(SAXParseException e) {
        }
        
        public void fatalError(SAXParseException e) throws SAXException {
            throw e;
        }
    }
    
    public static void main(String[] args) throws Exception {
        System.out.println("Testing enumeration and NOTATION validation...\n");
        
        test1_EnumerationValid();
        test2_EnumerationInvalid();
        test3_NotationValid();
        test4_NotationInvalid();
        test5_NotationWithEnumerationValid();
        test6_NotationWithEnumerationInvalid();
        
        System.out.println("\n✓ All tests passed!");
    }
    
    static void test1_EnumerationValid() throws Exception {
        System.out.println("Test 1: Valid enumeration attribute");
        
        String xml =
            "<?xml version='1.0'?>\n" +
            "<!DOCTYPE root [\n" +
            "  <!ELEMENT root EMPTY>\n" +
            "  <!ATTLIST root\n" +
            "    status (draft|review|final) #REQUIRED>\n" +
            "]>\n" +
            "<root status='draft'/>";
        
        ErrorCollector errors = parseWithValidation(xml);
        if (!errors.errors.isEmpty()) {
            throw new Exception("Valid enumeration rejected: " + errors.errors.get(0).getMessage());
        }
        System.out.println("  ✓ Valid enumeration value accepted");
    }
    
    static void test2_EnumerationInvalid() throws Exception {
        System.out.println("\nTest 2: Invalid enumeration attribute");
        
        String xml =
            "<?xml version='1.0'?>\n" +
            "<!DOCTYPE root [\n" +
            "  <!ELEMENT root EMPTY>\n" +
            "  <!ATTLIST root\n" +
            "    status (draft|review|final) #REQUIRED>\n" +
            "]>\n" +
            "<root status='published'/>";
        
        ErrorCollector errors = parseWithValidation(xml);
        if (errors.errors.isEmpty()) {
            throw new Exception("Invalid enumeration value should be rejected");
        }
        String errorMsg = errors.errors.get(0).getMessage();
        if (errorMsg.contains("not in enumeration")) {
            System.out.println("  ✓ Invalid enumeration value rejected: " + errorMsg);
        } else {
            throw new Exception("Wrong error: " + errorMsg);
        }
    }
    
    static void test3_NotationValid() throws Exception {
        System.out.println("\nTest 3: Valid NOTATION attribute");
        
        String xml =
            "<?xml version='1.0'?>\n" +
            "<!DOCTYPE root [\n" +
            "  <!NOTATION gif SYSTEM \"image/gif\">\n" +
            "  <!NOTATION jpeg SYSTEM \"image/jpeg\">\n" +
            "  <!ELEMENT root EMPTY>\n" +
            "  <!ATTLIST root\n" +
            "    format NOTATION (gif|jpeg) #REQUIRED>\n" +
            "]>\n" +
            "<root format='gif'/>";
        
        ErrorCollector errors = parseWithValidation(xml);
        if (!errors.errors.isEmpty()) {
            throw new Exception("Valid NOTATION rejected: " + errors.errors.get(0).getMessage());
        }
        System.out.println("  ✓ Valid NOTATION value accepted");
    }
    
    static void test4_NotationInvalid() throws Exception {
        System.out.println("\nTest 4: Invalid NOTATION attribute (not declared)");
        
        String xml =
            "<?xml version='1.0'?>\n" +
            "<!DOCTYPE root [\n" +
            "  <!NOTATION gif SYSTEM \"image/gif\">\n" +
            "  <!ELEMENT root EMPTY>\n" +
            "  <!ATTLIST root\n" +
            "    format NOTATION (gif|jpeg) #REQUIRED>\n" +
            "]>\n" +
            "<root format='jpeg'/>";
        
        ErrorCollector errors = parseWithValidation(xml);
        if (errors.errors.isEmpty()) {
            throw new Exception("Undeclared NOTATION should be rejected");
        }
        String errorMsg = errors.errors.get(0).getMessage();
        if (errorMsg.contains("not declared")) {
            System.out.println("  ✓ Undeclared NOTATION rejected: " + errorMsg);
        } else {
            throw new Exception("Wrong error: " + errorMsg);
        }
    }
    
    static void test5_NotationWithEnumerationValid() throws Exception {
        System.out.println("\nTest 5: NOTATION with enumeration (valid)");
        
        String xml =
            "<?xml version='1.0'?>\n" +
            "<!DOCTYPE root [\n" +
            "  <!NOTATION gif SYSTEM \"image/gif\">\n" +
            "  <!NOTATION jpeg SYSTEM \"image/jpeg\">\n" +
            "  <!NOTATION png SYSTEM \"image/png\">\n" +
            "  <!ELEMENT root EMPTY>\n" +
            "  <!ATTLIST root\n" +
            "    format NOTATION (gif|jpeg) #REQUIRED>\n" +
            "]>\n" +
            "<root format='jpeg'/>";
        
        ErrorCollector errors = parseWithValidation(xml);
        if (!errors.errors.isEmpty()) {
            throw new Exception("Valid NOTATION in enumeration rejected: " + errors.errors.get(0).getMessage());
        }
        System.out.println("  ✓ NOTATION in enumeration accepted");
    }
    
    static void test6_NotationWithEnumerationInvalid() throws Exception {
        System.out.println("\nTest 6: NOTATION with enumeration (invalid - not in list)");
        
        String xml =
            "<?xml version='1.0'?>\n" +
            "<!DOCTYPE root [\n" +
            "  <!NOTATION gif SYSTEM \"image/gif\">\n" +
            "  <!NOTATION jpeg SYSTEM \"image/jpeg\">\n" +
            "  <!NOTATION png SYSTEM \"image/png\">\n" +
            "  <!ELEMENT root EMPTY>\n" +
            "  <!ATTLIST root\n" +
            "    format NOTATION (gif|jpeg) #REQUIRED>\n" +
            "]>\n" +
            "<root format='png'/>";
        
        ErrorCollector errors = parseWithValidation(xml);
        if (errors.errors.isEmpty()) {
            throw new Exception("NOTATION not in enumeration should be rejected");
        }
        String errorMsg = errors.errors.get(0).getMessage();
        if (errorMsg.contains("not in declared enumeration")) {
            System.out.println("  ✓ NOTATION not in enumeration rejected: " + errorMsg);
        } else {
            throw new Exception("Wrong error: " + errorMsg);
        }
    }
    
    static ErrorCollector parseWithValidation(String xml) throws Exception {
        org.bluezoo.gonzalez.Parser parser = new org.bluezoo.gonzalez.Parser();
        parser.setContentHandler(new DefaultHandler());
        
        ErrorCollector errorCollector = new ErrorCollector();
        parser.setErrorHandler(errorCollector);
        parser.setFeature("http://xml.org/sax/features/validation", true);
        
        ByteArrayInputStream bais = new ByteArrayInputStream(xml.getBytes("UTF-8"));
        parser.parse(new InputSource(bais));
        
        return errorCollector;
    }
}


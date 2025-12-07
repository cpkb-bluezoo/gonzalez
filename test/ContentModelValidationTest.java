import org.bluezoo.gonzalez.*;
import org.xml.sax.*;
import org.xml.sax.helpers.*;
import java.io.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Tests DTD content model validation.
 */
public class ContentModelValidationTest {
    
    /**
     * ErrorHandler that collects validation errors instead of throwing.
     */
    static class ErrorCollector implements ErrorHandler {
        List<SAXParseException> errors = new ArrayList<>();
        List<SAXParseException> warnings = new ArrayList<>();
        List<SAXParseException> fatalErrors = new ArrayList<>();
        
        public void error(SAXParseException e) {
            errors.add(e);
        }
        
        public void warning(SAXParseException e) {
            warnings.add(e);
        }
        
        public void fatalError(SAXParseException e) throws SAXException {
            fatalErrors.add(e);
            throw e; // Fatal errors should stop processing
        }
        
        public void reset() {
            errors.clear();
            warnings.clear();
            fatalErrors.clear();
        }
    }
    
    public static void main(String[] args) throws Exception {
        System.out.println("Testing content model validation...\n");
        
        test1_EMPTYContent();
        test2_ANYContent();
        test3_MixedContent();
        test4_SequenceContent();
        test5_ChoiceContent();
        test6_OccurrenceIndicators();
        
        System.out.println("\n✓ All tests passed!");
    }
    
    static void test1_EMPTYContent() throws Exception {
        System.out.println("Test 1: EMPTY content model");
        
        // Valid: empty element
        String validXml =
            "<?xml version='1.0'?>\n" +
            "<!DOCTYPE root [\n" +
            "  <!ELEMENT root EMPTY>\n" +
            "]>\n" +
            "<root/>";
        
        ErrorCollector errors = parseWithValidation(validXml);
        if (!errors.errors.isEmpty()) {
            throw new Exception("Valid empty element rejected: " + errors.errors.get(0).getMessage());
        }
        System.out.println("  ✓ Valid empty element accepted");
        
        // Invalid: EMPTY with text
        String invalidXml =
            "<?xml version='1.0'?>\n" +
            "<!DOCTYPE root [\n" +
            "  <!ELEMENT root EMPTY>\n" +
            "]>\n" +
            "<root>text</root>";
        
        errors = parseWithValidation(invalidXml);
        if (errors.errors.isEmpty()) {
            throw new Exception("Should reject text in EMPTY element");
        }
        String errorMsg = errors.errors.get(0).getMessage();
        if (errorMsg.contains("EMPTY") && errorMsg.contains("text")) {
            System.out.println("  ✓ Text in EMPTY element rejected: " + errorMsg);
        } else {
            throw new Exception("Wrong error message: " + errorMsg);
        }
    }
    
    static void test2_ANYContent() throws Exception {
        System.out.println("\nTest 2: ANY content model");
        
        String xml =
            "<?xml version='1.0'?>\n" +
            "<!DOCTYPE root [\n" +
            "  <!ELEMENT root ANY>\n" +
            "  <!ELEMENT child ANY>\n" +
            "]>\n" +
            "<root>text<child>more text</child></root>";
        
        ErrorCollector errors = parseWithValidation(xml);
        if (!errors.errors.isEmpty()) {
            throw new Exception("ANY content rejected: " + errors.errors.get(0).getMessage());
        }
        System.out.println("  ✓ ANY content model accepts any content");
    }
    
    static void test3_MixedContent() throws Exception {
        System.out.println("\nTest 3: Mixed content model");
        
        // Valid: allowed elements
        String validXml =
            "<?xml version='1.0'?>\n" +
            "<!DOCTYPE root [\n" +
            "  <!ELEMENT root (#PCDATA|a|b)*>\n" +
            "  <!ELEMENT a EMPTY>\n" +
            "  <!ELEMENT b EMPTY>\n" +
            "]>\n" +
            "<root>text<a/>more<b/>text</root>";
        
        parseWithValidation(validXml);
        System.out.println("  ✓ Valid mixed content accepted");
        
        // Invalid: disallowed element
        String invalidXml =
            "<?xml version='1.0'?>\n" +
            "<!DOCTYPE root [\n" +
            "  <!ELEMENT root (#PCDATA|a)*>\n" +
            "  <!ELEMENT a EMPTY>\n" +
            "  <!ELEMENT c EMPTY>\n" +
            "]>\n" +
            "<root>text<c/></root>";
        
        try {
            parseWithValidation(invalidXml);
            throw new Exception("Should reject disallowed element in mixed content");
        } catch (SAXException e) {
            if (e.getMessage().contains("not allowed")) {
                System.out.println("  ✓ Disallowed element in mixed content rejected");
            } else {
                throw e;
            }
        }
    }
    
    static void test4_SequenceContent() throws Exception {
        System.out.println("\nTest 4: Sequence content model (a, b, c)");
        
        // Valid: correct sequence
        String validXml =
            "<?xml version='1.0'?>\n" +
            "<!DOCTYPE root [\n" +
            "  <!ELEMENT root (a,b,c)>\n" +
            "  <!ELEMENT a EMPTY>\n" +
            "  <!ELEMENT b EMPTY>\n" +
            "  <!ELEMENT c EMPTY>\n" +
            "]>\n" +
            "<root><a/><b/><c/></root>";
        
        parseWithValidation(validXml);
        System.out.println("  ✓ Valid sequence accepted");
        
        // Invalid: wrong order
        String invalidXml =
            "<?xml version='1.0'?>\n" +
            "<!DOCTYPE root [\n" +
            "  <!ELEMENT root (a,b,c)>\n" +
            "  <!ELEMENT a EMPTY>\n" +
            "  <!ELEMENT b EMPTY>\n" +
            "  <!ELEMENT c EMPTY>\n" +
            "]>\n" +
            "<root><a/><c/><b/></root>";
        
        try {
            parseWithValidation(invalidXml);
            throw new Exception("Should reject wrong sequence order");
        } catch (SAXException e) {
            if (e.getMessage().contains("Expected") || e.getMessage().contains("does not match")) {
                System.out.println("  ✓ Wrong sequence order rejected");
            } else {
                throw e;
            }
        }
    }
    
    static void test5_ChoiceContent() throws Exception {
        System.out.println("\nTest 5: Choice content model (a | b | c)");
        
        // Valid: first choice
        String validXml1 =
            "<?xml version='1.0'?>\n" +
            "<!DOCTYPE root [\n" +
            "  <!ELEMENT root (a|b|c)>\n" +
            "  <!ELEMENT a EMPTY>\n" +
            "  <!ELEMENT b EMPTY>\n" +
            "  <!ELEMENT c EMPTY>\n" +
            "]>\n" +
            "<root><a/></root>";
        
        parseWithValidation(validXml1);
        System.out.println("  ✓ Valid choice (first) accepted");
        
        // Valid: second choice
        String validXml2 =
            "<?xml version='1.0'?>\n" +
            "<!DOCTYPE root [\n" +
            "  <!ELEMENT root (a|b|c)>\n" +
            "  <!ELEMENT a EMPTY>\n" +
            "  <!ELEMENT b EMPTY>\n" +
            "  <!ELEMENT c EMPTY>\n" +
            "]>\n" +
            "<root><b/></root>";
        
        parseWithValidation(validXml2);
        System.out.println("  ✓ Valid choice (second) accepted");
    }
    
    static void test6_OccurrenceIndicators() throws Exception {
        System.out.println("\nTest 6: Occurrence indicators (?, *, +)");
        
        // Valid: optional present
        String validXml1 =
            "<?xml version='1.0'?>\n" +
            "<!DOCTYPE root [\n" +
            "  <!ELEMENT root (a?)>\n" +
            "  <!ELEMENT a EMPTY>\n" +
            "]>\n" +
            "<root><a/></root>";
        
        parseWithValidation(validXml1);
        System.out.println("  ✓ Optional element present accepted");
        
        // Valid: optional absent
        String validXml2 =
            "<?xml version='1.0'?>\n" +
            "<!DOCTYPE root [\n" +
            "  <!ELEMENT root (a?)>\n" +
            "  <!ELEMENT a EMPTY>\n" +
            "]>\n" +
            "<root></root>";
        
        parseWithValidation(validXml2);
        System.out.println("  ✓ Optional element absent accepted");
        
        // Valid: zero-or-more (multiple)
        String validXml3 =
            "<?xml version='1.0'?>\n" +
            "<!DOCTYPE root [\n" +
            "  <!ELEMENT root (a*)>\n" +
            "  <!ELEMENT a EMPTY>\n" +
            "]>\n" +
            "<root><a/><a/><a/></root>";
        
        parseWithValidation(validXml3);
        System.out.println("  ✓ Zero-or-more (multiple) accepted");
        
        // Valid: one-or-more
        String validXml4 =
            "<?xml version='1.0'?>\n" +
            "<!DOCTYPE root [\n" +
            "  <!ELEMENT root (a+)>\n" +
            "  <!ELEMENT a EMPTY>\n" +
            "]>\n" +
            "<root><a/><a/></root>";
        
        parseWithValidation(validXml4);
        System.out.println("  ✓ One-or-more accepted");
        
        // Invalid: one-or-more with zero
        String invalidXml =
            "<?xml version='1.0'?>\n" +
            "<!DOCTYPE root [\n" +
            "  <!ELEMENT root (a+)>\n" +
            "  <!ELEMENT a EMPTY>\n" +
            "]>\n" +
            "<root></root>";
        
        try {
            parseWithValidation(invalidXml);
            throw new Exception("Should reject one-or-more with zero occurrences");
        } catch (SAXException e) {
            if (e.getMessage().contains("Expected") || e.getMessage().contains("does not match")) {
                System.out.println("  ✓ One-or-more with zero rejected");
            } else {
                throw e;
            }
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


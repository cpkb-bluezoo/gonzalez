import org.bluezoo.gonzalez.*;
import org.xml.sax.*;
import org.xml.sax.helpers.*;
import java.io.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Tests that validation errors are recoverable and don't stop processing.
 */
public class ValidationRecoverabilityTest {
    
    /**
     * ErrorHandler that collects validation errors.
     */
    static class ErrorCollector implements ErrorHandler {
        List<SAXParseException> errors = new ArrayList<>();
        
        public void error(SAXParseException e) {
            errors.add(e);
            System.out.println("    [ERROR] " + e.getMessage());
        }
        
        public void warning(SAXParseException e) {
            System.out.println("    [WARNING] " + e.getMessage());
        }
        
        public void fatalError(SAXParseException e) throws SAXException {
            System.out.println("    [FATAL] " + e.getMessage());
            throw e; // Fatal errors should stop processing
        }
    }
    
    public static void main(String[] args) throws Exception {
        System.out.println("Testing validation error recoverability...\n");
        
        // Document with multiple validation errors
        String xml =
            "<?xml version='1.0'?>\n" +
            "<!DOCTYPE root [\n" +
            "  <!ELEMENT root (a,b)>\n" +
            "  <!ELEMENT a EMPTY>\n" +
            "  <!ELEMENT b EMPTY>\n" +
            "  <!ATTLIST a id ID #REQUIRED>\n" +
            "  <!ATTLIST b id ID #REQUIRED ref IDREF #IMPLIED>\n" +
            "]>\n" +
            "<root>\n" +
            "  <a/>                      <!-- ERROR: missing required attribute 'id' -->\n" +
            "  <a id='x1'>text</a>       <!-- ERROR: EMPTY element contains text -->\n" +
            "  <b id='x1'/>              <!-- ERROR: duplicate ID 'x1' -->\n" +
            "  <b id='x2' ref='invalid'/><!-- ERROR: IDREF to undeclared ID -->\n" +
            "</root>";
        
        org.bluezoo.gonzalez.Parser parser = new org.bluezoo.gonzalez.Parser();
        parser.setContentHandler(new DefaultHandler() {
            @Override
            public void startElement(String uri, String localName, String qName, Attributes atts) {
                System.out.println("  Parsed element: <" + qName + ">");
            }
        });
        
        ErrorCollector errorCollector = new ErrorCollector();
        parser.setErrorHandler(errorCollector);
        parser.setFeature("http://xml.org/sax/features/validation", true);
        
        System.out.println("Parsing document with validation errors...\n");
        
        ByteArrayInputStream bais = new ByteArrayInputStream(xml.getBytes("UTF-8"));
        parser.parse(new InputSource(bais));
        
        System.out.println("\n✓ Parsing completed despite validation errors!");
        System.out.println("  Total validation errors collected: " + errorCollector.errors.size());
        
        // We expect at least 4 errors (could be more due to cascading errors)
        if (errorCollector.errors.size() < 4) {
            throw new Exception("Expected at least 4 validation errors, got " + errorCollector.errors.size());
        }
        
        System.out.println("\n✓ All validation errors were reported and processing continued!");
        System.out.println("  This demonstrates that validation errors are recoverable!");
    }
}


import org.bluezoo.gonzalez.*;
import java.io.*;

/**
 * Debug tool to see tokens produced when parsing circular entity references.
 */
public class DebugCircularEntity {
    public static void main(String[] args) throws Exception {
        String xml = "<!DOCTYPE doc [\n" +
                     "<!ENTITY e1 \"&e2;\">\n" +
                     "<!ENTITY e2 \"&e3;\">\n" +
                     "<!ENTITY e3 \"&e1;\">\n" +
                     "]>\n" +
                     "<doc>&e1;</doc>";
        
        System.err.println("=== Parsing circular entity test ===");
        System.err.println(xml);
        System.err.println("=== Tokens: ===");
        
        // Create parser
        Parser parser = new Parser();
        
        // Wrap with debug consumer
        ContentParser xmlParser = (ContentParser) parser.getContentHandler();
        DTDParser dtdParser = parser.getDTDParser();
        
        // Create Tokenizer with debug wrapper
        DebugTokenConsumer debugConsumer = new DebugTokenConsumer(xmlParser);
        Tokenizer tokenizer = new Tokenizer(debugConsumer, xmlParser, dtdParser);
        
        // Parse
        ByteArrayInputStream in = new ByteArrayInputStream(xml.getBytes("UTF-8"));
        org.xml.sax.InputSource source = new org.xml.sax.InputSource(in);
        
        try {
            parser.parse(source);
            System.err.println("\n=== Parsing completed successfully (should have failed!) ===");
        } catch (Exception e) {
            System.err.println("\n=== Parsing failed as expected: ===");
            System.err.println(e.getMessage());
        }
    }
}


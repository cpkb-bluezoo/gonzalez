/*
 * Test DTDParser attribute declarations lookup.
 */

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import org.bluezoo.gonzalez.Parser;
import org.bluezoo.gonzalez.DTDParser;
import org.bluezoo.gonzalez.XMLParser;
import org.bluezoo.gonzalez.XMLTokenizer;
import org.bluezoo.gonzalez.AttributeDeclaration;
import java.util.Map;

public class DebugDTDLookupTest {
    
    public static void main(String[] args) throws Exception {
        System.out.println("Debug: Testing DTDParser attribute lookup...\n");
        
        String xml = "<?xml version='1.0'?>\n" +
                    "<!DOCTYPE root [\n" +
                    "  <!ATTLIST root\n" +
                    "    myattr CDATA \"mydefault\"\n" +
                    "  >\n" +
                    "]>\n" +
                    "<root/>";
        
        // Create parser components
        XMLParser xmlParser = new XMLParser();
        XMLTokenizer tokenizer = new XMLTokenizer(xmlParser);
        
        // Parse the document
        ByteArrayInputStream in = new ByteArrayInputStream(xml.getBytes("UTF-8"));
        byte[] buffer = new byte[1024];
        int bytesRead;
        while ((bytesRead = in.read(buffer)) != -1) {
            tokenizer.receive(ByteBuffer.wrap(buffer, 0, bytesRead));
        }
        tokenizer.close();
        
        // Get the DTDParser from XMLParser
        DTDParser dtdParser = xmlParser.getDTDParser();
        
        if (dtdParser == null) {
            System.out.println("ERROR: DTDParser is null!");
            return;
        }
        
        System.out.println("DTDParser retrieved successfully");
        
        // Try to get attribute declarations for "root"
        Map<String, AttributeDeclaration> attrDecls = dtdParser.getAttributeDeclarations("root");
        
        System.out.println("Attribute declarations for 'root': " + (attrDecls == null ? "null" : attrDecls.size() + " declarations"));
        
        if (attrDecls != null) {
            for (Map.Entry<String, AttributeDeclaration> entry : attrDecls.entrySet()) {
                System.out.println("  Attribute: " + entry.getKey() + " = " + entry.getValue());
            }
        }
    }
}


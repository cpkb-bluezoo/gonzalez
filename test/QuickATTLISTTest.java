import org.bluezoo.gonzalez.*;
import org.xml.sax.*;
import org.xml.sax.helpers.DefaultHandler;
import java.io.*;

public class QuickATTLISTTest {
    public static void main(String[] args) throws Exception {
        String xml = "<!DOCTYPE doc [\n" +
                     "  <!ELEMENT doc EMPTY>\n" +
                     "  <!ATTLIST doc\n" +
                     "    id ID #REQUIRED\n" +
                     "    type CDATA #IMPLIED>\n" +
                     "]>\n" +
                     "<doc id=\"test\" type=\"sample\"/>";
        
        org.bluezoo.gonzalez.Parser parser = new org.bluezoo.gonzalez.Parser();
        parser.setContentHandler(new DefaultHandler());
        parser.setProperty("http://xml.org/sax/properties/lexical-handler", new org.xml.sax.ext.LexicalHandler() {
            public void startDTD(String name, String publicId, String systemId) {
                System.out.println("startDTD");
            }
            public void endDTD() {
                System.out.println("endDTD");
            }
            public void startEntity(String name) {}
            public void endEntity(String name) {}
            public void startCDATA() {}
            public void endCDATA() {}
            public void comment(char[] ch, int start, int length) {}
        });
        
        InputSource source = new InputSource(new ByteArrayInputStream(xml.getBytes()));
        parser.parse(source);
        
        DTDParser dtdParser = (DTDParser) parser.getProperty("http://www.nongnu.org/gonzalez/properties/dtd-parser");
        
        AttributeDeclaration idAttr = dtdParser.getAttributeDeclaration("doc", "id");
        System.out.println("id attribute: " + (idAttr != null ? ("type=" + idAttr.type) : "NOT FOUND"));
        
        AttributeDeclaration typeAttr = dtdParser.getAttributeDeclaration("doc", "type");
        System.out.println("type attribute: " + (typeAttr != null ? ("type=" + typeAttr.type) : "NOT FOUND"));
        
        if (idAttr != null && idAttr.type.equals("ID") && 
            typeAttr != null && typeAttr.type.equals("CDATA")) {
            System.out.println("✓ Test passed!");
        } else {
            System.out.println("✗ Test failed!");
            System.exit(1);
        }
    }
}


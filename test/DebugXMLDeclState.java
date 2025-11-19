import org.bluezoo.gonzalez.*;
import java.io.*;

public class DebugXMLDeclState {
    public static void main(String[] args) throws Exception {
        String xml = "<?xml version=\"1.0\"?><root>content</root>";
        
        System.out.println("Parsing: " + xml);
        Parser parser = new Parser();
        try {
            parser.parse(new org.xml.sax.InputSource(new ByteArrayInputStream(xml.getBytes("UTF-8"))));
            System.out.println("✓ Parse successful");
        } catch (Exception e) {
            System.out.println("✗ Parse failed: " + e.getMessage());
            e.printStackTrace();
        }
    }
}



import org.bluezoo.gonzalez.*;
import java.io.*;

public class DebugEncodingAttr {
    public static void main(String[] args) throws Exception {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<root>test</root>";
        
        System.out.println("Parsing: " + xml.substring(0, Math.min(60, xml.length())) + "...");
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



import org.bluezoo.gonzalez.Parser;
import org.xml.sax.*;
import org.xml.sax.helpers.DefaultHandler;
import java.io.*;
import java.util.*;

/**
 * Test DTD processing instruction parsing.
 */
public class DTDPITest {
    
    public static void main(String[] args) {
        try {
            System.out.println("=== DTD PI Test ===\n");
            
            // PI in internal subset
            String xml = "<?xml version='1.0'?>\n" +
                        "<!DOCTYPE root [\n" +
                        "  <?xml-stylesheet type=\"text/xsl\" href=\"style.xsl\"?>\n" +
                        "]>\n" +
                        "<root/>";
            
            TestHandler handler = new TestHandler();
            Parser parser = new Parser();
            parser.setContentHandler(handler);
            
            parser.parse(new InputSource(new ByteArrayInputStream(xml.getBytes("UTF-8"))));
            
            System.out.println("PIs found: " + handler.pis.size());
            for (int i = 0; i < handler.pis.size(); i++) {
                PI pi = handler.pis.get(i);
                System.out.println("  PI " + (i+1) + ": target='" + pi.target + "', data='" + pi.data + "'");
            }
            
            if (handler.pis.size() == 1) {
                PI pi = handler.pis.get(0);
                if ("xml-stylesheet".equals(pi.target) && 
                    pi.data.contains("text/xsl") && pi.data.contains("style.xsl")) {
                    System.out.println("\n✓ Test passed!");
                    return;
                }
            }
            
            throw new Exception("PI not captured correctly");
            
        } catch (Exception e) {
            System.out.println("TEST FAILED: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
    
    static class PI {
        String target;
        String data;
        PI(String target, String data) {
            this.target = target;
            this.data = data;
        }
    }
    
    static class TestHandler extends DefaultHandler {
        List<PI> pis = new ArrayList<>();
        
        @Override
        public void processingInstruction(String target, String data) {
            System.out.println("processingInstruction() called: target='" + target + "', data='" + data + "'");
            pis.add(new PI(target, data));
        }
    }
}


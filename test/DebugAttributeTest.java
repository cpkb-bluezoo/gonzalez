import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.bluezoo.gonzalez.XMLTokenizer;
import org.bluezoo.gonzalez.MockTokenConsumer;
import org.xml.sax.SAXException;

/**
 * Debug test for attributes.
 */
public class DebugAttributeTest {

    public static void main(String[] args) {
        try {
            MockTokenConsumer consumer = new MockTokenConsumer();
            XMLTokenizer tokenizer = new XMLTokenizer(consumer, null, "test.xml");
            
            String xml = "<?xml version=\"1.0\"?><root id=\"123\" name=\"test\"/>";
            System.out.println("Input: " + xml);
            System.out.println();
            
            tokenizer.receive(ByteBuffer.wrap(xml.getBytes(StandardCharsets.UTF_8)));
            
            System.out.println("\nTokens before close():");
            consumer.printEvents();
            
            System.out.println("\nCalling close()...");
            tokenizer.close();
            
            System.out.println("\nTokens after close():");
            consumer.printEvents();
            
            System.out.println("\n✓ Success");
        } catch (Exception e) {
            System.err.println("\nERROR:");
            e.printStackTrace();
            System.exit(1);
        }
    }
}


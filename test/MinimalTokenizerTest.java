import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.bluezoo.gonzalez.Tokenizer;
import org.bluezoo.gonzalez.MockTokenConsumer;
import org.xml.sax.SAXException;

/**
 * Minimal tokenizer test - no XML declaration.
 */
public class MinimalTokenizerTest {

    public static void main(String[] args) {
        System.out.println("=== Minimal Tokenizer Test (No XML Declaration) ===\n");

        try {
            MockTokenConsumer consumer = new MockTokenConsumer();
            Tokenizer tokenizer = new Tokenizer(consumer, null, "test.xml");
            
            // Just send the simplest possible XML - no declaration
            String xml = "<root/>";
            System.out.println("Input: " + xml);
            tokenizer.receive(ByteBuffer.wrap(xml.getBytes(StandardCharsets.UTF_8)));
            tokenizer.close();
            
            consumer.printEvents();
            
            if (consumer.getEvents().isEmpty()) {
                System.err.println("ERROR: No tokens were emitted!");
                System.exit(1);
            }
            
            System.out.println("\n✓ Success - " + consumer.getEvents().size() + " tokens emitted");
        } catch (Exception e) {
            System.err.println("\nERROR: Test failed with exception");
            e.printStackTrace();
            System.exit(1);
        }
    }
}


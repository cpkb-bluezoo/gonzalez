import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.bluezoo.gonzalez.Tokenizer;
import org.bluezoo.gonzalez.MockTokenConsumer;
import org.xml.sax.SAXException;

/**
 * Simple manual test for Tokenizer using MockTokenConsumer.
 * This tests the tokenizer in isolation without the parser.
 */
public class SimpleTokenizerTest {

    public static void main(String[] args) {
        System.out.println("=== Starting Simple Tokenizer Test ===\n");

        try {
            // Test 1: Simple document
            testSimpleDocument();

            // Test 2: Document with attributes
            testDocumentWithAttributes();

            // Test 3: Document with character data
            testDocumentWithCharacterData();

            System.out.println("\n=== All tokenizer tests passed! ===");
        } catch (Exception e) {
            System.err.println("\n=== Test failed with exception ===");
            e.printStackTrace();
            System.exit(1);
        }
    }

    static void testSimpleDocument() throws SAXException {
        System.out.println("Test 1: Simple document - <?xml version=\"1.0\"?><root/>");
        
        MockTokenConsumer consumer = new MockTokenConsumer();
        Tokenizer tokenizer = new Tokenizer(consumer, null, "test1.xml");
        
        String xml = "<?xml version=\"1.0\"?><root/>";
        tokenizer.receive(ByteBuffer.wrap(xml.getBytes(StandardCharsets.UTF_8)));
        tokenizer.close();
        
        consumer.printEvents();
        
        // Verify we got expected tokens
        if (consumer.getEvents().isEmpty()) {
            throw new SAXException("No tokens were emitted!");
        }
        
        System.out.println("  ✓ Passed (" + consumer.getEvents().size() + " tokens)\n");
    }

    static void testDocumentWithAttributes() throws SAXException {
        System.out.println("Test 2: Document with attributes - <root id=\"123\" name=\"test\"/>");
        
        MockTokenConsumer consumer = new MockTokenConsumer();
        Tokenizer tokenizer = new Tokenizer(consumer, null, "test2.xml");
        
        String xml = "<?xml version=\"1.0\"?><root id=\"123\" name=\"test\"/>";
        tokenizer.receive(ByteBuffer.wrap(xml.getBytes(StandardCharsets.UTF_8)));
        tokenizer.close();
        
        consumer.printEvents();
        
        // Verify we got expected tokens
        if (consumer.getEvents().isEmpty()) {
            throw new SAXException("No tokens were emitted!");
        }
        
        System.out.println("  ✓ Passed (" + consumer.getEvents().size() + " tokens)\n");
    }

    static void testDocumentWithCharacterData() throws SAXException {
        System.out.println("Test 3: Document with character data - <root>Hello</root>");
        
        MockTokenConsumer consumer = new MockTokenConsumer();
        Tokenizer tokenizer = new Tokenizer(consumer, null, "test3.xml");
        
        String xml = "<?xml version=\"1.0\"?><root>Hello, World!</root>";
        tokenizer.receive(ByteBuffer.wrap(xml.getBytes(StandardCharsets.UTF_8)));
        tokenizer.close();
        
        consumer.printEvents();
        
        // Verify we got expected tokens
        if (consumer.getEvents().isEmpty()) {
            throw new SAXException("No tokens were emitted!");
        }
        
        System.out.println("  ✓ Passed (" + consumer.getEvents().size() + " tokens)\n");
    }
}


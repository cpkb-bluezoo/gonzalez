import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.bluezoo.gonzalez.Tokenizer;
import org.bluezoo.gonzalez.ContentParser;
import org.xml.sax.ContentHandler;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.Attributes;

/**
 * Simple manual test for Tokenizer and ContentParser integration.
 */
public class SimpleContentParserTest {

    public static void main(String[] args) {
        System.out.println("=== Starting Simple XML Parser Test ===\n");

        try {
            // Test 1: Simple document
            testSimpleDocument();

            // Test 2: Document with attributes
            testDocumentWithAttributes();

            // Test 3: Document with character data
            testDocumentWithCharacterData();

            // Test 4: Document with CDATA
            testDocumentWithCDATA();

            // Test 5: Document with comments and PIs
            testDocumentWithCommentsAndPIs();

            System.out.println("\n=== All tests passed! ===");
        } catch (Exception e) {
            System.err.println("\n=== Test failed with exception ===");
            e.printStackTrace();
            System.exit(1);
        }
    }

    static void testSimpleDocument() throws SAXException {
        System.out.println("Test 1: Simple document");
        String xml = "<?xml version=\"1.0\"?><root/>";
        
        TestContentHandler handler = new TestContentHandler();
        ContentParser parser = new ContentParser();
        parser.setContentHandler(handler);
        
        System.out.println("  Creating tokenizer and sending data...");
        Tokenizer tokenizer = new Tokenizer(parser, null, "test1.xml");
        tokenizer.receive(ByteBuffer.wrap(xml.getBytes(StandardCharsets.UTF_8)));
        System.out.println("  Closing tokenizer...");
        tokenizer.close();
        
        handler.verify();
        System.out.println("  ✓ Passed\n");
    }

    static void testDocumentWithAttributes() throws SAXException {
        System.out.println("Test 2: Document with attributes");
        String xml = "<?xml version=\"1.0\"?><root id=\"123\" name=\"test\"/>";
        
        TestContentHandler handler = new TestContentHandler();
        ContentParser parser = new ContentParser();
        parser.setContentHandler(handler);
        
        Tokenizer tokenizer = new Tokenizer(parser, null, "test2.xml");
        tokenizer.receive(ByteBuffer.wrap(xml.getBytes(StandardCharsets.UTF_8)));
        tokenizer.close();
        
        handler.verify();
        System.out.println("  ✓ Passed\n");
    }

    static void testDocumentWithCharacterData() throws SAXException {
        System.out.println("Test 3: Document with character data");
        String xml = "<?xml version=\"1.0\"?><root>Hello, World!</root>";
        
        TestContentHandler handler = new TestContentHandler();
        ContentParser parser = new ContentParser();
        parser.setContentHandler(handler);
        
        Tokenizer tokenizer = new Tokenizer(parser, null, "test3.xml");
        tokenizer.receive(ByteBuffer.wrap(xml.getBytes(StandardCharsets.UTF_8)));
        tokenizer.close();
        
        handler.verify();
        System.out.println("  ✓ Passed\n");
    }

    static void testDocumentWithCDATA() throws SAXException {
        System.out.println("Test 4: Document with CDATA");
        String xml = "<?xml version=\"1.0\"?><root><![CDATA[<special>content</special>]]></root>";
        
        TestContentHandler handler = new TestContentHandler();
        ContentParser parser = new ContentParser();
        parser.setContentHandler(handler);
        
        Tokenizer tokenizer = new Tokenizer(parser, null, "test4.xml");
        tokenizer.receive(ByteBuffer.wrap(xml.getBytes(StandardCharsets.UTF_8)));
        tokenizer.close();
        
        handler.verify();
        System.out.println("  ✓ Passed\n");
    }

    static void testDocumentWithCommentsAndPIs() throws SAXException {
        System.out.println("Test 5: Document with comments and PIs");
        String xml = "<?xml version=\"1.0\"?><!-- Comment --><?target data?><root/>";
        
        TestContentHandler handler = new TestContentHandler();
        ContentParser parser = new ContentParser();
        parser.setContentHandler(handler);
        
        Tokenizer tokenizer = new Tokenizer(parser, null, "test5.xml");
        tokenizer.receive(ByteBuffer.wrap(xml.getBytes(StandardCharsets.UTF_8)));
        tokenizer.close();
        
        handler.verify();
        System.out.println("  ✓ Passed\n");
    }

    /**
     * Simple ContentHandler that prints events and tracks basic state.
     */
    static class TestContentHandler implements ContentHandler {
        private boolean startDocumentCalled = false;
        private boolean endDocumentCalled = false;
        private int elementDepth = 0;

        @Override
        public void setDocumentLocator(Locator locator) {
            System.out.println("  setDocumentLocator: line=" + locator.getLineNumber() + 
                             ", col=" + locator.getColumnNumber());
        }

        @Override
        public void startDocument() throws SAXException {
            startDocumentCalled = true;
            System.out.println("  startDocument()");
        }

        @Override
        public void endDocument() throws SAXException {
            endDocumentCalled = true;
            System.out.println("  endDocument()");
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes atts) 
                throws SAXException {
            elementDepth++;
            System.out.print("  startElement: qName=" + qName);
            if (atts.getLength() > 0) {
                System.out.print(", attributes=[");
                for (int i = 0; i < atts.getLength(); i++) {
                    if (i > 0) System.out.print(", ");
                    System.out.print(atts.getQName(i) + "=\"" + atts.getValue(i) + "\"");
                }
                System.out.print("]");
            }
            System.out.println();
        }

        @Override
        public void endElement(String uri, String localName, String qName) throws SAXException {
            elementDepth--;
            System.out.println("  endElement: qName=" + qName);
        }

        @Override
        public void characters(char[] ch, int start, int length) throws SAXException {
            String text = new String(ch, start, length);
            if (!text.trim().isEmpty()) {
                System.out.println("  characters: \"" + text + "\"");
            }
        }

        @Override
        public void ignorableWhitespace(char[] ch, int start, int length) throws SAXException {
            // Ignore
        }

        @Override
        public void processingInstruction(String target, String data) throws SAXException {
            System.out.println("  processingInstruction: target=" + target + 
                             (data != null ? ", data=" + data : ""));
        }

        @Override
        public void skippedEntity(String name) throws SAXException {
            System.out.println("  skippedEntity: " + name);
        }

        @Override
        public void startPrefixMapping(String prefix, String uri) throws SAXException {
            // Not yet implemented
        }

        @Override
        public void endPrefixMapping(String prefix) throws SAXException {
            // Not yet implemented
        }

        void verify() throws SAXException {
            if (!startDocumentCalled) {
                throw new SAXException("startDocument() was not called");
            }
            if (!endDocumentCalled) {
                throw new SAXException("endDocument() was not called");
            }
            if (elementDepth != 0) {
                throw new SAXException("Element depth mismatch: " + elementDepth);
            }
        }
    }
}


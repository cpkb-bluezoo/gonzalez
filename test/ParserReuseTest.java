import java.io.ByteArrayInputStream;
import org.bluezoo.gonzalez.Parser;
import org.xml.sax.Attributes;
import org.xml.sax.ContentHandler;
import org.xml.sax.InputSource;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;

/**
 * Test for Parser.reset() functionality - reusing the same parser instance
 * for multiple documents.
 */
public class ParserReuseTest {

    public static void main(String[] args) {
        System.out.println("=== Parser Reuse Test ===\n");

        try {
            testParserReuse();
            System.out.println("\n=== Parser reuse test passed! ===");
        } catch (Exception e) {
            System.err.println("\n=== Test failed with exception ===");
            e.printStackTrace();
            System.exit(1);
        }
    }

    static void testParserReuse() throws Exception {
        System.out.println("Testing Parser reuse with reset()");
        
        // Create parser once
        Parser parser = new Parser();
        TestHandler handler = new TestHandler();
        parser.setContentHandler(handler);
        
        // Parse first document
        System.out.println("\n--- Parsing first document ---");
        String xml1 = "<?xml version=\"1.0\"?><doc1><item>first</item></doc1>";
        ByteArrayInputStream stream1 = new ByteArrayInputStream(xml1.getBytes("UTF-8"));
        InputSource source1 = new InputSource(stream1);
        source1.setSystemId("http://example.com/doc1.xml");
        parser.parse(source1);
        
        // Verify first parse
        if (handler.rootElementCount != 1 || handler.totalElementCount != 2) {
            throw new SAXException("First parse failed: root=" + handler.rootElementCount + 
                                   ", total=" + handler.totalElementCount);
        }
        System.out.println("✓ First document parsed successfully");
        System.out.println("  Root elements: " + handler.rootElementCount);
        System.out.println("  Total elements: " + handler.totalElementCount);
        
        // Reset parser
        System.out.println("\n--- Resetting parser ---");
        parser.reset();
        handler.reset();
        System.out.println("✓ Parser reset complete");
        
        // Parse second document
        System.out.println("\n--- Parsing second document ---");
        String xml2 = "<?xml version=\"1.0\"?><doc2><a><b>second</b></a></doc2>";
        ByteArrayInputStream stream2 = new ByteArrayInputStream(xml2.getBytes("UTF-8"));
        InputSource source2 = new InputSource(stream2);
        source2.setSystemId("http://example.com/doc2.xml");
        parser.parse(source2);
        
        // Verify second parse
        if (handler.rootElementCount != 1 || handler.totalElementCount != 3) {
            throw new SAXException("Second parse failed: root=" + handler.rootElementCount + 
                                   ", total=" + handler.totalElementCount);
        }
        System.out.println("✓ Second document parsed successfully");
        System.out.println("  Root elements: " + handler.rootElementCount);
        System.out.println("  Total elements: " + handler.totalElementCount);
        
        // Reset and parse third document
        System.out.println("\n--- Parsing third document (after second reset) ---");
        parser.reset();
        handler.reset();
        
        String xml3 = "<?xml version=\"1.0\"?><doc3/>";
        ByteArrayInputStream stream3 = new ByteArrayInputStream(xml3.getBytes("UTF-8"));
        InputSource source3 = new InputSource(stream3);
        source3.setSystemId("http://example.com/doc3.xml");
        parser.parse(source3);
        
        // Verify third parse (empty root element)
        if (handler.rootElementCount != 1 || handler.totalElementCount != 1) {
            throw new SAXException("Third parse failed: root=" + handler.rootElementCount + 
                                   ", total=" + handler.totalElementCount);
        }
        System.out.println("✓ Third document parsed successfully");
        System.out.println("  Root elements: " + handler.rootElementCount);
        System.out.println("  Total elements: " + handler.totalElementCount);
    }

    static class TestHandler implements ContentHandler {
        private int elementDepth = 0;
        private int rootElementCount = 0;
        private int totalElementCount = 0;
        private boolean startDocumentCalled = false;
        private boolean endDocumentCalled = false;

        void reset() {
            elementDepth = 0;
            rootElementCount = 0;
            totalElementCount = 0;
            startDocumentCalled = false;
            endDocumentCalled = false;
        }

        @Override
        public void setDocumentLocator(Locator locator) {
            System.out.println("  setDocumentLocator: systemId=" + locator.getSystemId());
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
            if (elementDepth != 0) {
                throw new SAXException("Element depth mismatch at endDocument: " + elementDepth);
            }
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes atts) 
                throws SAXException {
            elementDepth++;
            totalElementCount++;
            if (elementDepth == 1) {
                rootElementCount++;
            }
            System.out.println("  startElement: " + qName + " (depth=" + elementDepth + ")");
        }

        @Override
        public void endElement(String uri, String localName, String qName) throws SAXException {
            System.out.println("  endElement: " + qName + " (depth=" + elementDepth + ")");
            elementDepth--;
        }

        @Override
        public void characters(char[] ch, int start, int length) throws SAXException {
            String text = new String(ch, start, length).trim();
            if (!text.isEmpty()) {
                System.out.println("  characters: \"" + text + "\"");
            }
        }

        @Override
        public void ignorableWhitespace(char[] ch, int start, int length) throws SAXException {
        }

        @Override
        public void processingInstruction(String target, String data) throws SAXException {
        }

        @Override
        public void skippedEntity(String name) throws SAXException {
        }

        @Override
        public void startPrefixMapping(String prefix, String uri) throws SAXException {
        }

        @Override
        public void endPrefixMapping(String prefix) throws SAXException {
        }
    }
}


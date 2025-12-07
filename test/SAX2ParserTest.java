import java.io.ByteArrayInputStream;
import org.bluezoo.gonzalez.Parser;
import org.xml.sax.Attributes;
import org.xml.sax.ContentHandler;
import org.xml.sax.InputSource;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;

/**
 * Test for the Parser class (SAX2 XMLReader implementation).
 */
public class SAX2ParserTest {

    public static void main(String[] args) {
        System.out.println("=== SAX2 Parser Test ===\n");

        try {
            // Test 1: Parse via InputSource
            testParseWithInputSource();

            // Test 2: Direct buffer API
            testDirectBufferAPI();

            System.out.println("\n=== All SAX2 Parser tests passed! ===");
        } catch (Exception e) {
            System.err.println("\n=== Test failed with exception ===");
            e.printStackTrace();
            System.exit(1);
        }
    }

    static void testParseWithInputSource() throws Exception {
        System.out.println("Test 1: Parse via InputSource (standard SAX2 API)");
        
        String xml = "<?xml version=\"1.0\"?><root id=\"123\"><child>text</child></root>";
        ByteArrayInputStream inputStream = new ByteArrayInputStream(xml.getBytes("UTF-8"));
        InputSource inputSource = new InputSource(inputStream);
        inputSource.setSystemId("http://example.com/test.xml");
        
        TestHandler handler = new TestHandler();
        Parser parser = new Parser();
        parser.setContentHandler(handler);
        
        parser.parse(inputSource);
        
        handler.verify();
        System.out.println("  ✓ Passed\n");
    }

    static void testDirectBufferAPI() throws Exception {
        System.out.println("Test 2: Direct buffer API (advanced usage)");
        
        String xml = "<?xml version=\"1.0\"?><root><child>data</child></root>";
        
        TestHandler handler = new TestHandler();
        Parser parser = new Parser();
        parser.setContentHandler(handler);
        parser.setSystemId("http://example.com/test2.xml");
        
        // Feed data in chunks using direct API
        byte[] bytes = xml.getBytes("UTF-8");
        
        // Split at a safe boundary (after "><" between root and child)
        int splitPoint = 35; // After "<root><"
        
        // First chunk
        byte[] chunk1 = new byte[splitPoint];
        System.arraycopy(bytes, 0, chunk1, 0, splitPoint);
        java.nio.ByteBuffer buffer1 = java.nio.ByteBuffer.wrap(chunk1);
        parser.receive(buffer1);
        
        // Second chunk
        byte[] chunk2 = new byte[bytes.length - splitPoint];
        System.arraycopy(bytes, splitPoint, chunk2, 0, bytes.length - splitPoint);
        java.nio.ByteBuffer buffer2 = java.nio.ByteBuffer.wrap(chunk2);
        parser.receive(buffer2);
        
        // Close to signal end
        parser.close();
        
        handler.verify();
        System.out.println("  ✓ Passed\n");
    }

    static class TestHandler implements ContentHandler {
        private boolean startDocumentCalled = false;
        private boolean endDocumentCalled = false;
        private int elementDepth = 0;
        private int startElementCount = 0;

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
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes atts) 
                throws SAXException {
            elementDepth++;
            startElementCount++;
            System.out.print("  startElement: " + qName);
            if (atts.getLength() > 0) {
                System.out.print(" [");
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
            System.out.println("  endElement: " + qName);
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
            System.out.println("  processingInstruction: " + target);
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
            if (startElementCount == 0) {
                throw new SAXException("No elements were parsed");
            }
        }
    }
}


package org.bluezoo.gonzalez;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;

import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;

import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.ext.LexicalHandler;
import org.xml.sax.helpers.DefaultHandler;

/**
 * Parses one document with one very large construct, in the heap the JVM was
 * started with, and says whether the parser got to the end of it.
 *
 *   java -Xmx32m ... org.bluezoo.gonzalez.MemoryProbe &lt;mode&gt; &lt;scenario&gt; &lt;megabytes&gt;
 *
 * The document is made up as it is read, so none of it is held by the
 * harness. What is asked is whether a parser that is given a construct larger
 * than its heap can get through it; the answer depends on whether the parser
 * is able to deliver that construct in pieces, which is the point.
 *
 * mode: xmlhandler | sax | jdk | aalto-sax | aalto-stax
 * scenario: text | cdata | attribute | comment | elements
 *
 * Prints one line: "ok &lt;ms&gt;", "out-of-memory", "timeout" or "error &lt;message&gt;".
 */
public class MemoryProbe {

    public static void main(String[] args) throws Exception {
        final String mode = args[0];
        String scenario = args[1];
        long bytes = Long.parseLong(args[2]) * 1024L * 1024L;

        // A parser that is thrashing its way to an OutOfMemoryError can take
        // a long time to get there.
        Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(120_000);
            } catch (InterruptedException e) {
                return;
            }
            System.out.println("timeout");
            Runtime.getRuntime().halt(0);
        });
        watchdog.setDaemon(true);
        watchdog.start();

        long start = System.nanoTime();
        try {
            parse(mode, new Generator(scenario, bytes));
            System.out.println("ok " + (System.nanoTime() - start) / 1_000_000 + " ms");
        } catch (OutOfMemoryError e) {
            System.out.println("out-of-memory");
        } catch (Throwable e) {
            Throwable c = e;
            while (c.getCause() != null) {
                c = c.getCause();
            }
            if (c instanceof OutOfMemoryError) {
                System.out.println("out-of-memory");
            } else {
                System.out.println("error " + c);
            }
        }
        System.exit(0);
    }

    private static void parse(String mode, InputStream in) throws Exception {
        switch (mode) {
            case "xmlhandler": {
                XMLParser parser = new XMLParser();
                parser.setNamespaceAware(true);
                parser.setHandler(new CountingHandler());
                parser.parse(in);
                break;
            }
            case "sax":
                parseSax(new Parser(), in);
                break;
            case "jdk": {
                SAXParserFactory f = SAXParserFactory.newInstance();
                f.setNamespaceAware(true);
                parseSax(f.newSAXParser().getXMLReader(), in);
                break;
            }
            case "aalto-sax": {
                SAXParserFactory f = new com.fasterxml.aalto.sax.SAXParserFactoryImpl();
                f.setNamespaceAware(true);
                SAXParser p = f.newSAXParser();
                SaxCounter h = new SaxCounter();
                try {
                    p.setProperty("http://xml.org/sax/properties/lexical-handler", h);
                } catch (SAXException e) {
                    // comments are not reported to it; nothing to be done
                }
                p.parse(in, h);
                break;
            }
            case "aalto-stax": {
                XMLInputFactory f = new com.fasterxml.aalto.stax.InputFactoryImpl();
                XMLStreamReader r = f.createXMLStreamReader(in);
                long sink = 0;
                while (r.hasNext()) {
                    int event = r.next();
                    if (event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA
                            || event == XMLStreamConstants.COMMENT) {
                        sink += r.getTextLength();
                    } else if (event == XMLStreamConstants.START_ELEMENT) {
                        for (int i = 0, n = r.getAttributeCount(); i < n; i++) {
                            sink += r.getAttributeValue(i).length();
                        }
                    }
                }
                r.close();
                if (sink < 0) {
                    System.out.println(sink);
                }
                break;
            }
            default:
                throw new IllegalArgumentException("unknown mode " + mode);
        }
    }

    private static void parseSax(XMLReader reader, InputStream in) throws Exception {
        SaxCounter h = new SaxCounter();
        reader.setContentHandler(h);
        try {
            reader.setProperty("http://xml.org/sax/properties/lexical-handler", h);
        } catch (SAXException e) {
            // comments are not reported to it; nothing to be done
        }
        reader.parse(new org.xml.sax.InputSource(in));
    }

    /** A SAX handler that reads everything it is given (the lengths, at least). */
    private static final class SaxCounter extends DefaultHandler implements LexicalHandler {
        long sink;

        @Override
        public void startElement(String uri, String localName, String qName,
                org.xml.sax.Attributes atts) {
            for (int i = 0, n = atts.getLength(); i < n; i++) {
                sink += atts.getValue(i).length();
            }
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            sink += length;
        }

        @Override public void comment(char[] ch, int start, int length) {
            sink += length;
        }
        @Override public void startDTD(String name, String publicId, String systemId) { }
        @Override public void endDTD() { }
        @Override public void startEntity(String name) { }
        @Override public void endEntity(String name) { }
        @Override public void startCDATA() { }
        @Override public void endCDATA() { }
    }

    /** A native handler that looks at everything it is given. */
    private static final class CountingHandler implements XMLHandler {
        long sink;

        @Override public void setLocator(Locator locator) { }
        @Override public void setXml11(boolean xml11) { }
        @Override public void startDocument() { }
        @Override public void endDocument() { }
        @Override public void startElement(String qName) { }
        @Override public void namespace(String prefix, String uri) { }
        @Override public void startAttribute(String name, String type, boolean declared, boolean specified) { }
        @Override public void attributeValueContent(CharBuffer value, boolean end) { sink += value.remaining(); }
        @Override public void endAttributes() { }
        @Override public void characters(CharBuffer text, boolean ignorable, boolean end) { sink += text.remaining(); }
        @Override public void endElement() { }
        @Override public void startComment() { }
        @Override public void commentData(CharBuffer text, boolean end) { sink += text.remaining(); }
        @Override public void startCDATA() { }
        @Override public void endCDATA() { }
        @Override public void startDTD(String name, String publicId, String systemId) { }
        @Override public void endDTD() { }
        @Override public void startEntity(String name) { }
        @Override public void endEntity(String name) { }
        @Override public void notationDecl(String name, String publicId, String systemId) { }
        @Override public void unparsedEntityDecl(String name, String publicId, String systemId, String notationName) { }
        @Override public void piTarget(String target) { }
        @Override public void piData(CharBuffer data, boolean end) { sink += data.remaining(); }
        @Override public void saveBuffers() { }
        @Override public SAXException fatalError(String message) { return new SAXException(message); }
        @Override public void error(String message) { }
    }

    /** A document made up as it is read: a head, a body of one repeated byte (or record), a tail. */
    private static final class Generator extends InputStream {
        private final byte[] head;
        private final byte[] body;
        private final byte[] tail;
        private final long bodyLength;
        private long produced;
        private int headAt;
        private int tailAt;

        Generator(String scenario, long bytes) {
            switch (scenario) {
                case "text":
                    head = "<r>".getBytes();
                    body = new byte[] { 'a' };
                    tail = "</r>".getBytes();
                    break;
                case "cdata":
                    head = "<r><![CDATA[".getBytes();
                    body = new byte[] { 'a' };
                    tail = "]]></r>".getBytes();
                    break;
                case "attribute":
                    head = "<r a='".getBytes();
                    body = new byte[] { 'a' };
                    tail = "'/>".getBytes();
                    break;
                case "comment":
                    head = "<r><!--".getBytes();
                    body = new byte[] { 'a' };
                    tail = "--></r>".getBytes();
                    break;
                case "elements":
                    head = "<r>".getBytes();
                    body = "<e a=\"1\" b=\"two\">some text</e>\n".getBytes();
                    tail = "</r>".getBytes();
                    break;
                default:
                    throw new IllegalArgumentException("unknown scenario " + scenario);
            }
            // a whole number of records, so that the last is not cut short
            this.bodyLength = bytes - bytes % body.length;
        }

        @Override
        public int read() {
            byte[] one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : one[0] & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) {
            int n = 0;
            while (n < len && headAt < head.length) {
                b[off + n++] = head[headAt++];
            }
            while (n < len && produced < bodyLength) {
                int k = body.length;
                if (k == 1) {
                    int run = (int) Math.min(len - n, bodyLength - produced);
                    java.util.Arrays.fill(b, off + n, off + n + run, body[0]);
                    n += run;
                    produced += run;
                } else {
                    b[off + n++] = body[(int) (produced % k)];
                    produced++;
                }
            }
            while (n < len && produced >= bodyLength && tailAt < tail.length) {
                b[off + n++] = tail[tailAt++];
            }
            return n == 0 ? -1 : n;
        }
    }
}

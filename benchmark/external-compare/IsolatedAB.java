package org.bluezoo.gonzalez;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;

import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamReader;

import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * Fresh-JVM harness: one corpus, one pipeline per JVM, so numbers are not
 * distorted by shared-JVM effects. Also convenient under JFR.
 *
 *   java ... org.bluezoo.gonzalez.IsolatedAB &lt;corpus&gt; &lt;mode&gt;
 *
 * corpus: plain|attrs|whitespace|markup|multibyte|utf16|euc-jp|xhtml-dtd
 *
 * mode:
 *   xmlhandler           Gonzalez XMLParser with an empty XMLHandler, defaults
 *   xmlhandler-nolimits  the same with the structural limits switched off
 *   sax                  Gonzalez Parser (SAX) with an empty DefaultHandler
 *   jdk                  the JDK's bundled SAX parser
 *   aalto-sax            aalto-xml SAX
 *   aalto-stax           aalto-xml StAX cursor
 *   sax-touch            Gonzalez Parser (SAX), the handler reading every
 *                        attribute name and value and the length of all text
 *   aalto-sax-touch      aalto-xml SAX, the same handler
 *
 * The "touch" modes matter because a handler that ignores its attributes
 * lets a parser that decodes them lazily skip the work, which no real
 * handler does.
 *
 * Every mode is namespace-aware. Run through run-isolated.sh, which needs
 * the aalto-xml and stax2-api jars (AALTO_JAR, STAX2_JAR) on the classpath.
 */
public class IsolatedAB {

    private static final XMLHandler EMPTY = new EmptyHandler();
    private static final DefaultHandler EMPTY_SAX = new DefaultHandler();

    /** Reads everything a handler could: all attribute names and values, all text. */
    private static final class TouchHandler extends DefaultHandler {
        long sink;

        @Override
        public void startElement(String uri, String localName, String qName,
                org.xml.sax.Attributes atts) {
            sink += localName.length() + qName.length() + uri.length();
            for (int i = 0, n = atts.getLength(); i < n; i++) {
                sink += atts.getQName(i).length() + atts.getValue(i).length();
            }
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            sink += length + ch[start];
        }
    }

    public static void main(String[] args) throws Exception {
        byte[] bytes = corpus(args[0]);
        String mode = args[1];
        double mb = bytes.length / (1024.0 * 1024.0);
        int warmup = Integer.getInteger("warmup", 800);
        int measured = Integer.getInteger("measured", 500);
        Parse parse = parser(mode, bytes);
        for (int i = 0; i < warmup; i++) {
            parse.run();
        }
        long total = 0;
        for (int i = 0; i < measured; i++) {
            long t = System.nanoTime();
            parse.run();
            total += System.nanoTime() - t;
        }
        double avgMs = (total / (double) measured) / 1_000_000.0;
        System.out.printf("%-12s %-20s %8.3f ms %8.1f MB/s%n",
                args[0], mode, avgMs, mb / (avgMs / 1000.0));
    }

    private static byte[] corpus(String name) throws Exception {
        switch (name) {
            case "plain": return Files.readAllBytes(Paths.get("benchmark/resources/large.xml"));
            case "utf16": return BenchmarkCorpora.japaneseUtf16();
            case "euc-jp": return BenchmarkCorpora.japaneseEucJp();
            case "xhtml-dtd": return BenchmarkCorpora.xhtmlInternalSubset();
            default: return Files.readAllBytes(Paths.get("benchmark/resources/" + name + "-large.xml"));
        }
    }

    private interface Parse {
        void run() throws Exception;
    }

    private static Parse parser(String mode, final byte[] bytes) throws Exception {
        switch (mode) {
            case "xmlhandler":
                return () -> xmlHandler(bytes, true);
            case "xmlhandler-nolimits":
                return () -> xmlHandler(bytes, false);
            case "sax":
                return () -> {
                    org.xml.sax.XMLReader reader = new Parser();
                    reader.setFeature("http://xml.org/sax/features/namespaces", true);
                    reader.setContentHandler(EMPTY_SAX);
                    reader.parse(new org.xml.sax.InputSource(new ByteArrayInputStream(bytes)));
                };
            case "sax-touch": {
                final TouchHandler h = new TouchHandler();
                return () -> {
                    org.xml.sax.XMLReader reader = new Parser();
                    reader.setFeature("http://xml.org/sax/features/namespaces", true);
                    reader.setContentHandler(h);
                    reader.parse(new org.xml.sax.InputSource(new ByteArrayInputStream(bytes)));
                };
            }
            case "aalto-sax-touch": {
                final SAXParserFactory f = new com.fasterxml.aalto.sax.SAXParserFactoryImpl();
                f.setNamespaceAware(true);
                final TouchHandler h = new TouchHandler();
                return () -> {
                    SAXParser p = f.newSAXParser();
                    p.parse(new ByteArrayInputStream(bytes), h);
                };
            }
            case "jdk": {
                final SAXParserFactory f = SAXParserFactory.newInstance();
                f.setNamespaceAware(true);
                return () -> {
                    SAXParser p = f.newSAXParser();
                    p.parse(new ByteArrayInputStream(bytes), EMPTY_SAX);
                };
            }
            case "aalto-sax": {
                final SAXParserFactory f = new com.fasterxml.aalto.sax.SAXParserFactoryImpl();
                f.setNamespaceAware(true);
                return () -> {
                    SAXParser p = f.newSAXParser();
                    p.parse(new ByteArrayInputStream(bytes), EMPTY_SAX);
                };
            }
            case "aalto-stax": {
                final XMLInputFactory f = new com.fasterxml.aalto.stax.InputFactoryImpl();
                return () -> {
                    XMLStreamReader r = f.createXMLStreamReader(new ByteArrayInputStream(bytes));
                    while (r.hasNext()) {
                        r.next();
                    }
                    r.close();
                };
            }
            default:
                throw new IllegalArgumentException("unknown mode " + mode);
        }
    }

    private static void xmlHandler(byte[] bytes, boolean limits) throws Exception {
        XMLParser parser = new XMLParser();
        parser.setNamespaceAware(true);
        parser.setHandler(EMPTY);
        if (!limits) {
            parser.setMaxElementDepth(0);
            parser.setMaxNameLength(0);
            parser.setMaxAttributesPerElement(0);
        }
        parser.receive(ByteBuffer.wrap(bytes));
        parser.close();
    }

    private static final class EmptyHandler implements XMLHandler {
        @Override public void setLocator(Locator locator) { }
        @Override public void setXml11(boolean xml11) { }
        @Override public void startDocument() { }
        @Override public void endDocument() { }
        @Override public void startElement(String qName) { }
        @Override public void namespace(String prefix, String uri) { }
        @Override public void startAttribute(String name, String type, boolean declared, boolean specified) { }
        @Override public void attributeValueContent(CharBuffer value, boolean end) { }
        @Override public void endAttributes() { }
        @Override public void characters(CharBuffer text, boolean ignorable, boolean end) { }
        @Override public void endElement() { }
        @Override public void startComment() { }
        @Override public void commentData(CharBuffer text, boolean end) { }
        @Override public void startCDATA() { }
        @Override public void endCDATA() { }
        @Override public void startDTD(String name, String publicId, String systemId) { }
        @Override public void endDTD() { }
        @Override public void startEntity(String name) { }
        @Override public void endEntity(String name) { }
        @Override public void notationDecl(String name, String publicId, String systemId) { }
        @Override public void unparsedEntityDecl(String name, String publicId, String systemId, String notationName) { }
        @Override public void piTarget(String target) { }
        @Override public void piData(CharBuffer data, boolean end) { }
        @Override public void saveBuffers() { }
        @Override public SAXException fatalError(String message) { return new SAXException(message); }
        @Override public void error(String message) { }
    }
}

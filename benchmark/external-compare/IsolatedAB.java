package org.bluezoo.gonzalez;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * corpus: plain|attrs|whitespace|markup|multibyte|accent|utf16|euc-jp|xhtml-dtd
 *         french and german (a novel each, which ant download-corpora puts in
 *         benchmark/corpora, or that FRENCH_XML and GERMAN_XML name); and, made from the plain
 *         corpus or generated, to see how kinds of text with non-ASCII
 *         characters in it are parsed: latin (a French-like accent on every
 *         e), latin-light (on every second e), odd-word (English with a
 *         foreign word now and then), emoji (casual English) and mathml
 *         (many symbols among much markup)
 *
 * mode:
 *   xmlhandler           Gonzalez XMLParser with an empty XMLHandler, defaults
 *   xmlhandler-nolimits  the same with the structural limits switched off
 *   sax                  Gonzalez Parser (SAX) with an empty DefaultHandler
 *   jdk                  the JDK's bundled SAX parser
 *   xmlhandler-reuse     as xmlhandler, one XMLParser reset after each document
 *   sax-touch-reuse      as sax-touch, one Parser reset after each document
 *   aalto-sax-touch-reuse  as aalto-sax-touch, one SAXParser used for every document
 *   aalto-sax            aalto-xml SAX
 *   aalto-stax           aalto-xml StAX cursor, only stepping it (reads nothing, so it does less
 *                        than xmlhandler, which has already decoded all it hands over)
 *   xmlhandler-sum       Gonzalez XMLParser whose handler reads every character and name
 *   aalto-stax-sum       aalto-xml StAX cursor, the loop reading the same: native against native
 *   sax-touch            Gonzalez Parser (SAX), the handler reading every
 *                        attribute name and value and the length of all text
 *   jdk-touch            the JDK's SAX parser, the same handler
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
        if ("size".equals(mode)) {
            // Not a parser: the size of the corpus in bytes, for the tables.
            System.out.println(bytes.length);
            return;
        }
        double mb = bytes.length / (1024.0 * 1024.0);
        // A document of a few kilobytes is parsed in microseconds, so many
        // more parses are needed to warm up and to measure it.
        boolean small = bytes.length < 16384;
        int warmup = Integer.getInteger("warmup", small ? 100000 : 800);
        int measured = Integer.getInteger("measured", small ? 200000 : 500);
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
        System.out.printf("%-12s %-20s %10.4f ms %8.1f MB/s%n",
                args[0], mode, avgMs, mb / (avgMs / 1000.0));
    }

    /**
     * The plain corpus with an e-acute (two bytes of UTF-8) after one space in
     * every 300 bytes or so: text that is nearly all ASCII, as most is that is
     * not in a language that is not, with the odd accented letter, dash or
     * quotation mark in it.
     */
    private static byte[] accented(byte[] plain) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(plain.length + plain.length / 100);
        for (int i = 0; i < plain.length; i++) {
            out.write(plain[i]);
            if (i % 300 == 299 && plain[i] == ' ') {
                out.write(0xC3);
                out.write(0xA9);
            }
        }
        return out.toByteArray();
    }

    /**
     * The plain corpus with its text changed as another kind of text is: only
     * what is between tags is touched, so that markup stays well-formed.
     *
     * @param every a change is made at every {@code every}th place where one
     *        can be (a letter e, or a space), counting the text only
     * @param letterE true to put {@code sequence} in place of the letter e
     *        (as an accented e is in French), false to put it after a space
     */
    private static byte[] withNonAscii(byte[] plain, int every, int[] sequence, boolean letterE) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(plain.length + plain.length / 8);
        boolean inTag = false;
        int places = 0;
        for (int i = 0; i < plain.length; i++) {
            byte b = plain[i];
            if (b == '<') {
                inTag = true;
            }
            boolean place = !inTag && (letterE ? b == 'e' : b == ' ');
            if (place && ++places % every == 0) {
                if (!letterE) {
                    out.write(b);
                }
                for (int x : sequence) {
                    out.write(x);
                }
            } else {
                out.write(b);
            }
            if (b == '>') {
                inTag = false;
            }
        }
        return out.toByteArray();
    }

    /**
     * Mathematical markup: a few thousand small expressions of identifiers
     * (Latin and Greek), numbers and operators (some of them the symbols of
     * Unicode's mathematical blocks, three bytes each), in the nesting that
     * MathML has, so that there is a good deal of markup for each character
     * of text and a non-ASCII symbol every few elements.
     */
    private static byte[] mathml() throws Exception {
        String[] identifiers = { "x", "y", "n", "\u03b1", "\u03b2", "\u03c0", "\u03b8", "a", "\u03bb" };
        String[] operators = { "+", "=", "\u2212", "\u00d7", "\u2062", "\u2211", "\u222b", "\u2264", "\u2192", "(", ")" };
        java.util.Random random = new java.util.Random(7);
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<doc>\n");
        while (sb.length() < 1200000) {
            sb.append("  <math xmlns=\"http://www.w3.org/1998/Math/MathML\"><mrow>\n");
            int terms = 4 + random.nextInt(8);
            for (int t = 0; t < terms; t++) {
                switch (random.nextInt(5)) {
                    case 0:
                        sb.append("    <mi>").append(identifiers[random.nextInt(identifiers.length)]).append("</mi>\n");
                        break;
                    case 1:
                        sb.append("    <mo>").append(operators[random.nextInt(operators.length)]).append("</mo>\n");
                        break;
                    case 2:
                        sb.append("    <mn>").append(random.nextInt(100)).append("</mn>\n");
                        break;
                    case 3:
                        sb.append("    <msup><mi>").append(identifiers[random.nextInt(identifiers.length)])
                            .append("</mi><mn>").append(2 + random.nextInt(8)).append("</mn></msup>\n");
                        break;
                    default:
                        sb.append("    <mfrac><mrow><mi>").append(identifiers[random.nextInt(identifiers.length)])
                            .append("</mi><mo>").append(operators[random.nextInt(operators.length)])
                            .append("</mo><mn>1</mn></mrow><mrow><mi>")
                            .append(identifiers[random.nextInt(identifiers.length)]).append("</mi></mrow></mfrac>\n");
                        break;
                }
            }
            sb.append("  </mrow></math>\n");
        }
        sb.append("</doc>\n");
        return sb.toString().getBytes("UTF-8");
    }

    /**
     * A corpus of real text, which is not kept with the code: the path in the
     * environment variable if it is set, and otherwise the document that
     * {@code ant download-corpora} puts in {@code benchmark/corpora}.
     */
    private static String realDocument(String variable, String downloaded) {
        String path = System.getenv(variable);
        if (path != null && !path.isEmpty()) {
            return path;
        }
        if (Files.exists(Paths.get(downloaded))) {
            return downloaded;
        }
        throw new IllegalStateException("Run ant download-corpora, or set " + variable
                + " to the path of an XML document");
    }

    private static byte[] corpus(String name) throws Exception {
        switch (name) {
            case "latin":
                return withNonAscii(Files.readAllBytes(Paths.get("benchmark/resources/large.xml")), 1,
                        new int[] { 0xC3, 0xA9 }, true);
            case "latin-light":
                return withNonAscii(Files.readAllBytes(Paths.get("benchmark/resources/large.xml")), 2,
                        new int[] { 0xC3, 0xA9 }, true);
            case "odd-word":
                return withNonAscii(Files.readAllBytes(Paths.get("benchmark/resources/large.xml")), 250,
                        new int[] { 0xC3, 0xA9 }, false);
            case "emoji":
                return withNonAscii(Files.readAllBytes(Paths.get("benchmark/resources/large.xml")), 60,
                        new int[] { 0xF0, 0x9F, 0x98, 0x80 }, false);
            case "mathml": return mathml();
            case "french": return Files.readAllBytes(Paths.get(realDocument("FRENCH_XML", "benchmark/corpora/FRA03601_Proust.xml")));
            case "german": return Files.readAllBytes(Paths.get(realDocument("GERMAN_XML", "benchmark/corpora/DEU037.xml")));
            case "plain": return Files.readAllBytes(Paths.get("benchmark/resources/large.xml"));
            case "accent": return accented(Files.readAllBytes(Paths.get("benchmark/resources/large.xml")));
            case "utf16": return BenchmarkCorpora.japaneseUtf16();
            case "euc-jp": return BenchmarkCorpora.japaneseEucJp();
            case "xhtml-dtd": return BenchmarkCorpora.xhtmlInternalSubset();
            case "tiny": return "<a/>".getBytes("UTF-8");
            case "small": case "small-ns": case "books":
                return Files.readAllBytes(Paths.get("benchmark/resources/" + name + ".xml"));
            default:
                Path large = Paths.get("benchmark/resources/" + name + "-large.xml");
                return Files.readAllBytes(Files.exists(large) ? large
                        : Paths.get("benchmark/resources/" + name + ".xml"));
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
            case "xmlhandler-reuse": {
                final XMLParser parser = new XMLParser();
                parser.setNamespaceAware(true);
                parser.setHandler(EMPTY);
                return () -> {
                    parser.receive(ByteBuffer.wrap(bytes));
                    parser.close();
                    parser.reset();
                };
            }
            case "sax-touch-reuse": {
                final TouchHandler h = new TouchHandler();
                final Parser reader = new Parser();
                reader.setFeature("http://xml.org/sax/features/namespaces", true);
                reader.setContentHandler(h);
                return () -> {
                    reader.parse(new org.xml.sax.InputSource(new ByteArrayInputStream(bytes)));
                    reader.reset();
                };
            }
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
                    Integer decodeBuffer = Integer.getInteger("decodebuf");
                    if (decodeBuffer != null) {
                        reader.setProperty("http://www.nongnu.org/gonzalez/properties/decode-buffer-size",
                                decodeBuffer);
                    }
                    Integer inputBuffer = Integer.getInteger("inputbuf");
                    if (inputBuffer != null) {
                        reader.setProperty("http://www.nongnu.org/gonzalez/properties/input-buffer-size",
                                inputBuffer);
                    }
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
            case "aalto-sax-touch-reuse": {
                final SAXParserFactory f = new com.fasterxml.aalto.sax.SAXParserFactoryImpl();
                f.setNamespaceAware(true);
                final TouchHandler h = new TouchHandler();
                final SAXParser p = f.newSAXParser();
                // aalto-xml's SAXParser does not support reset(); it is simply
                // used again.
                return () -> p.parse(new ByteArrayInputStream(bytes), h);
            }
            case "jdk": {
                final SAXParserFactory f = SAXParserFactory.newInstance();
                f.setNamespaceAware(true);
                return () -> {
                    SAXParser p = f.newSAXParser();
                    p.parse(new ByteArrayInputStream(bytes), EMPTY_SAX);
                };
            }
            case "jdk-touch": {
                final SAXParserFactory f = SAXParserFactory.newInstance();
                f.setNamespaceAware(true);
                final TouchHandler h = new TouchHandler();
                return () -> {
                    SAXParser p = f.newSAXParser();
                    p.parse(new ByteArrayInputStream(bytes), h);
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
            case "xmlhandler-sum": {
                final SumHandler sum = new SumHandler();
                return () -> xmlHandler(bytes, true, sum);
            }
            case "aalto-stax-sum": {
                final XMLInputFactory f = new com.fasterxml.aalto.stax.InputFactoryImpl();
                return () -> aaltoStaxSum(f, bytes);
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
        xmlHandler(bytes, limits, EMPTY);
    }

    private static void xmlHandler(byte[] bytes, boolean limits, XMLHandler handler) throws Exception {
        XMLParser parser = new XMLParser();
        parser.setNamespaceAware(true);
        parser.setHandler(handler);
        Integer decodeBuffer = Integer.getInteger("decodebuf");
        if (decodeBuffer != null) {
            parser.setDecodeBufferSize(decodeBuffer);
        }
        if (!limits) {
            parser.setMaxElementDepth(0);
            parser.setMaxNameLength(0);
            parser.setMaxAttributesPerElement(0);
        }
        parser.receive(ByteBuffer.wrap(bytes));
        parser.close();
    }

    /**
     * What a consumer of the native interface does with what it is given at
     * the least: reads every character of text, comments, processing
     * instruction data and attribute values, and the length of every name,
     * and keeps the sum so that none of it is left undone.
     */
    private static final class SumHandler extends EmptyHandler {
        long sink;

        private void sum(CharBuffer text) {
            if (text.hasArray()) {
                char[] array = text.array();
                int end = text.arrayOffset() + text.limit();
                long total = 0;
                for (int i = text.arrayOffset() + text.position(); i < end; i++) {
                    total += array[i];
                }
                sink += total;
            } else {
                for (int i = text.position(); i < text.limit(); i++) {
                    sink += text.get(i);
                }
            }
        }

        @Override public void startElement(String qName) { sink += qName.length(); }
        @Override public void namespace(String prefix, String uri) { sink += prefix.length() + uri.length(); }
        @Override public void startAttribute(String name, String type, boolean declared, boolean specified) {
            sink += name.length();
        }
        @Override public void attributeValueContent(CharBuffer value, boolean end) { sum(value); }
        @Override public void characters(CharBuffer text, boolean ignorable, boolean end) { sum(text); }
        @Override public void commentData(CharBuffer text, boolean end) { sum(text); }
        @Override public void piTarget(String target) { sink += target.length(); }
        @Override public void piData(CharBuffer data, boolean end) { sum(data); }
    }

    private static long staxSink;
    private static final boolean ATTRIBUTE_LENGTH_ONLY = Boolean.getBoolean("attrlength");

    /** The same consumer work through Aalto's StAX cursor, its native interface. */
    private static void aaltoStaxSum(XMLInputFactory factory, byte[] bytes) throws Exception {
        XMLStreamReader r = factory.createXMLStreamReader(new ByteArrayInputStream(bytes));
        long sink = 0;
        while (r.hasNext()) {
            switch (r.next()) {
                case javax.xml.stream.XMLStreamConstants.START_ELEMENT:
                    sink += r.getLocalName().length();
                    for (int i = 0, n = r.getAttributeCount(); i < n; i++) {
                        sink += r.getAttributeLocalName(i).length();
                        String value = r.getAttributeValue(i);
                        if (ATTRIBUTE_LENGTH_ONLY) {
                            // Bounds the cost of reading a String with charAt, which a
                            // consumer of Aalto's attribute values may not need to do.
                            sink += value.length();
                            continue;
                        }
                        for (int j = 0, m = value.length(); j < m; j++) {
                            sink += value.charAt(j);
                        }
                    }
                    for (int i = 0, n = r.getNamespaceCount(); i < n; i++) {
                        String prefix = r.getNamespacePrefix(i);
                        sink += (prefix == null ? 0 : prefix.length()) + r.getNamespaceURI(i).length();
                    }
                    break;
                case javax.xml.stream.XMLStreamConstants.CHARACTERS:
                case javax.xml.stream.XMLStreamConstants.CDATA:
                case javax.xml.stream.XMLStreamConstants.SPACE:
                case javax.xml.stream.XMLStreamConstants.COMMENT: {
                    char[] text = r.getTextCharacters();
                    int start = r.getTextStart();
                    int end = start + r.getTextLength();
                    for (int i = start; i < end; i++) {
                        sink += text[i];
                    }
                    break;
                }
                case javax.xml.stream.XMLStreamConstants.PROCESSING_INSTRUCTION:
                    sink += r.getPITarget().length();
                    String data = r.getPIData();
                    for (int j = 0, m = data.length(); j < m; j++) {
                        sink += data.charAt(j);
                    }
                    break;
                default:
                    break;
            }
        }
        r.close();
        staxSink += sink;
    }

    private static class EmptyHandler implements XMLHandler {
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

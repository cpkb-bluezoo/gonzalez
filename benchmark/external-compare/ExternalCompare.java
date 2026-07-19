/*
 * ExternalCompare.java
 *
 * Standalone (non-JMH, non-ant-managed) throughput comparison of Gonzalez's
 * raw namespace-aware XMLHandler path and SAXAdapter path against the JDK's
 * bundled Xerces and an externally supplied aalto-xml, over the file-backed
 * benchmark/resources corpus plus generated encoding and DTD cases. See
 * benchmark/external-compare/run.sh for how this is compiled/run -
 * deliberately kept out of build.xml (no new project dependency). The script
 * reads the aalto-xml and stax2-api jar paths from environment variables and
 * uses the JDK's own default JAXP provider.
 *
 * In package org.bluezoo.gonzalez only to reach the package-private Scanner
 * and XMLHandler APIs - not part of the gonzalez source tree itself (lives
 * under benchmark/external-compare, compiled ad hoc).
 */
package org.bluezoo.gonzalez;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamReader;

import org.xml.sax.InputSource;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

public class ExternalCompare {

    private static final int WARMUP_ITERATIONS = 20;
    private static final int MEASURED_ITERATIONS = 30;

    private static final DefaultHandler EMPTY_HANDLER = new DefaultHandler();
    private static final XMLHandler EMPTY_XML_HANDLER = new EmptyXMLHandler();

    public static void main(String[] args) throws Exception {
        Map<String, byte[]> corpus = new LinkedHashMap<String, byte[]>();
        corpus.put("plain", Files.readAllBytes(Paths.get("benchmark/resources/large.xml")));
        corpus.put("attrs", Files.readAllBytes(Paths.get("benchmark/resources/attrs-large.xml")));
        corpus.put("whitespace", Files.readAllBytes(Paths.get("benchmark/resources/whitespace-large.xml")));
        corpus.put("markup", Files.readAllBytes(Paths.get("benchmark/resources/markup-large.xml")));
        corpus.put("multibyte", Files.readAllBytes(Paths.get("benchmark/resources/multibyte-large.xml")));
        corpus.put("utf16", BenchmarkCorpora.japaneseUtf16());
        corpus.put("euc-jp", BenchmarkCorpora.japaneseEucJp());
        corpus.put("xhtml-dtd", BenchmarkCorpora.xhtmlInternalSubset());

        // Namespace-aware=true across the board (aalto's SAX wrapper does not
        // implement non-namespace-aware mode at all, and namespace-aware is
        // the SAX2/StAX default anyway) so all contestants are doing
        // the same amount of work.
        SAXParserFactory jdkFactory = SAXParserFactory.newInstance();
        jdkFactory.setNamespaceAware(true);
        jdkFactory.setValidating(false);
        System.out.println("JDK default SAXParserFactory: " + jdkFactory.getClass().getName());

        SAXParserFactory aaltoFactory = new com.fasterxml.aalto.sax.SAXParserFactoryImpl();
        aaltoFactory.setNamespaceAware(true);
        System.out.println("aalto-xml SAXParserFactory: " + aaltoFactory.getClass().getName());

        XMLInputFactory aaltoStaxFactory = new com.fasterxml.aalto.stax.InputFactoryImpl();
        System.out.println();

        System.out.printf("%-12s %-24s %10s %10s%n", "docType", "parser", "avg ms", "MB/s");
        System.out.println("---------------------------------------------------------------");

        for (Map.Entry<String, byte[]> entry : corpus.entrySet()) {
            String docType = entry.getKey();
            byte[] bytes = entry.getValue();
            double mb = bytes.length / (1024.0 * 1024.0);

            time(docType, "gonzalez-xmlhandler", bytes, mb, () -> {
                XMLHandler handler = new NamespaceFilter(EMPTY_XML_HANDLER, false);
                ScannerSettings settings = new ScannerSettings(
                        false, false, false, true, "", ScannerSettings.DEFAULT_EXPANSION_LIMIT);
                Scanner scanner = new Scanner(handler, false, null, null, null,
                        false, true, settings, true);
                ExternalEntityDecoder decoder = new ExternalEntityDecoder(scanner, null, null, false);
                decoder.receive(ByteBuffer.wrap(bytes));
                decoder.close();
            });

            time(docType, "gonzalez-sax", bytes, mb, () -> {
                XMLReader reader = new Parser();
                reader.setFeature("http://xml.org/sax/features/namespaces", true);
                reader.setContentHandler(EMPTY_HANDLER);
                reader.parse(new InputSource(new ByteArrayInputStream(bytes)));
            });

            time(docType, "jdk-xerces", bytes, mb, () -> {
                SAXParser parser = jdkFactory.newSAXParser();
                parser.parse(new ByteArrayInputStream(bytes), EMPTY_HANDLER);
            });

            time(docType, "aalto-sax", bytes, mb, () -> {
                SAXParser parser = aaltoFactory.newSAXParser();
                parser.parse(new ByteArrayInputStream(bytes), EMPTY_HANDLER);
            });

            time(docType, "aalto-stax-cursor", bytes, mb, () -> {
                XMLStreamReader reader = aaltoStaxFactory.createXMLStreamReader(new ByteArrayInputStream(bytes));
                while (reader.hasNext()) {
                    reader.next();
                }
                reader.close();
            });

            System.out.println();
        }
    }

    private interface ParseTask {
        void run() throws Exception;
    }

    private static void time(String docType, String label, byte[] bytes, double mb, ParseTask task)
            throws Exception {
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            task.run();
        }
        long totalNanos = 0;
        for (int i = 0; i < MEASURED_ITERATIONS; i++) {
            long start = System.nanoTime();
            task.run();
            totalNanos += System.nanoTime() - start;
        }
        double avgMs = (totalNanos / (double) MEASURED_ITERATIONS) / 1_000_000.0;
        double mbPerSec = mb / (avgMs / 1000.0);
        System.out.printf("%-12s %-24s %10.3f %10.1f%n", docType, label, avgMs, mbPerSec);
    }

    private static final class EmptyXMLHandler implements XMLHandler {
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

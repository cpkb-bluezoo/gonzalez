/*
 * ExternalCompare.java
 *
 * Standalone (non-JMH, non-ant-managed) throughput comparison of Gonzalez's
 * two pipelines against the JDK's bundled Xerces and a locally-built
 * aalto-xml, over the same benchmark/resources corpus ScannerPipelineBenchmark
 * uses. See benchmark/external-compare/run.sh for how this is compiled/run -
 * deliberately kept out of build.xml (no new project dependency), pointed at
 * a locally-built ~/github/aalto-xml jar and the JDK's own default JAXP
 * provider instead of downloading anything.
 *
 * In package org.bluezoo.gonzalez only to reach the package-private
 * Parser/Scanner classes - not part of the gonzalez source tree itself
 * (lives under benchmark/external-compare, compiled ad hoc).
 */
package org.bluezoo.gonzalez;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamReader;

import org.xml.sax.InputSource;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

public class ExternalCompare {

    private static final int WARMUP_ITERATIONS = 20;
    private static final int MEASURED_ITERATIONS = 30;

    private static final DefaultHandler EMPTY_HANDLER = new DefaultHandler();

    public static void main(String[] args) throws Exception {
        Map<String, Path> corpus = new LinkedHashMap<String, Path>();
        corpus.put("plain", Paths.get("benchmark/resources/large.xml"));
        corpus.put("attrs", Paths.get("benchmark/resources/attrs-large.xml"));
        corpus.put("whitespace", Paths.get("benchmark/resources/whitespace-large.xml"));
        corpus.put("markup", Paths.get("benchmark/resources/markup-large.xml"));
        corpus.put("multibyte", Paths.get("benchmark/resources/multibyte-large.xml"));

        // Namespace-aware=true across the board (aalto's SAX wrapper does not
        // implement non-namespace-aware mode at all, and namespace-aware is
        // the SAX2/StAX default anyway) so all five contestants are doing
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

        for (Map.Entry<String, Path> entry : corpus.entrySet()) {
            String docType = entry.getKey();
            byte[] bytes = Files.readAllBytes(entry.getValue());
            double mb = bytes.length / (1024.0 * 1024.0);

            time(docType, "gonzalez-legacy", bytes, mb, () -> {
                Parser parser = new Parser();
                parser.setFeature("http://xml.org/sax/features/namespaces", true);
                parser.setContentHandler(EMPTY_HANDLER);
                parser.receive(ByteBuffer.wrap(bytes));
                parser.close();
            });

            time(docType, "gonzalez-scanner", bytes, mb, () -> {
                XMLReader reader = new Parser(Parser.Pipeline.SCANNER);
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

}

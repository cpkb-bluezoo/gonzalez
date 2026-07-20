/*
 * Standalone (non-JMH, non-ant-managed) XSLT bake-off: Gonzalez vs JDK JAXP
 * default (Xalan) vs Saxon-HE. See run-perf.sh. Deliberately kept out of the
 * product compile classpath.
 */
package org.bluezoo.gonzalez.xsltcompare;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import javax.xml.transform.Templates;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;

/**
 * Side-by-side compile + transform timing for a fixed XSLT corpus.
 */
public class XsltPerfCompare {

    private static final int WARMUP_ITERATIONS = 20;
    private static final int MEASURED_ITERATIONS = 30;

    private static final String GONZALEZ_FACTORY =
            "org.bluezoo.gonzalez.transform.GonzalezTransformerFactory";
    private static final String SAXON_FACTORY =
            "net.sf.saxon.TransformerFactoryImpl";

    public static void main(String[] args) throws Exception {
        Path resources = Paths.get("benchmark/xslt-compare/resources");
        Path booksXml = Paths.get("benchmark/resources/books.xml");
        byte[] booksBytes = Files.readAllBytes(booksXml);
        byte[] largeBytes = generateLargeDocument(5000);
        byte[] veryLargeBytes = generateLargeDocument(50000);

        List<Case> cases = new ArrayList<Case>();
        cases.add(new Case("identity-1.0", "1.0",
                Files.readAllBytes(resources.resolve("identity-1.0.xsl")), booksBytes));
        cases.add(new Case("books-1.0", "1.0",
                Files.readAllBytes(resources.resolve("books-1.0.xsl")), booksBytes));
        cases.add(new Case("many-templates-1.0", "1.0",
                Files.readAllBytes(resources.resolve("many-templates-1.0.xsl")), largeBytes));
        cases.add(new Case("streaming-3.0", "3.0",
                Files.readAllBytes(resources.resolve("streaming-3.0.xsl")), largeBytes));
        cases.add(new Case("free-ranging-3.0", "3.0",
                Files.readAllBytes(resources.resolve("free-ranging-3.0.xsl")), largeBytes));
        cases.add(new Case("relative-meta-3.0", "3.0",
                Files.readAllBytes(resources.resolve("relative-meta-3.0.xsl")), largeBytes));
        cases.add(new Case("variable-meta-3.0", "3.0",
                Files.readAllBytes(resources.resolve("variable-meta-3.0.xsl")), largeBytes));
        cases.add(new Case("streaming-large-3.0", "3.0",
                Files.readAllBytes(resources.resolve("streaming-3.0.xsl")), veryLargeBytes));

        Engine gonzalez = engine("gonzalez", GONZALEZ_FACTORY);
        Engine jdk = jdkEngine();
        Engine saxon = engine("saxon-he", SAXON_FACTORY);

        Path outDir = Paths.get("benchmark/xslt-compare/out");
        Files.createDirectories(outDir);
        Path summaryPath = outDir.resolve("perf-summary.txt");
        PrintWriter summary = new PrintWriter(Files.newBufferedWriter(summaryPath, StandardCharsets.UTF_8));

        String header = String.format("%-22s %-12s %12s %12s %10s %12s",
                "case", "engine", "compile ms", "transform ms", "MB/s", "heap delta");
        String rule = "------------------------------------------------------------------------------------------";

        System.out.println("gonzalez factory: " + gonzalez.factory.getClass().getName());
        System.out.println("jdk factory:      " + jdk.factory.getClass().getName());
        System.out.println("saxon factory:    " + saxon.factory.getClass().getName());
        System.out.println();
        System.out.println(header);
        System.out.println(rule);
        summary.println("XSLT bake-off performance summary");
        summary.println("warmup=" + WARMUP_ITERATIONS + " measured=" + MEASURED_ITERATIONS);
        summary.println();
        summary.println(header);
        summary.println(rule);

        for (Case c : cases) {
            writeResult(summary, runCase(c, gonzalez));
            if ("1.0".equals(c.minVersion)) {
                writeResult(summary, runCase(c, jdk));
            } else {
                String na = String.format("%-22s %-12s %12s %12s %10s %12s",
                        c.name, jdk.name, "n/a", "n/a", "n/a", "n/a");
                System.out.println(na);
                summary.println(na);
            }
            writeResult(summary, runCase(c, saxon));
            System.out.println();
            summary.println();
        }
        summary.close();
        System.out.println("Wrote " + summaryPath);
    }

    private static void writeResult(PrintWriter summary, String line) {
        System.out.println(line);
        summary.println(line);
    }

    private static Engine engine(String name, String factoryClass) throws Exception {
        TransformerFactory factory = TransformerFactory.newInstance(factoryClass, null);
        return new Engine(name, factory);
    }

    private static Engine jdkEngine() throws Exception {
        ClassLoader empty = new ClassLoader(null) { };
        TransformerFactory factory = TransformerFactory.newInstance(
                "com.sun.org.apache.xalan.internal.xsltc.trax.TransformerFactoryImpl",
                empty);
        return new Engine("jdk", factory);
    }

    private static String runCase(Case c, Engine engine) throws Exception {
        Runtime rt = Runtime.getRuntime();
        System.gc();
        long heapBefore = rt.totalMemory() - rt.freeMemory();

        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            compile(engine.factory, c.stylesheet);
        }
        long compileNanos = 0;
        Templates templates = null;
        for (int i = 0; i < MEASURED_ITERATIONS; i++) {
            long start = System.nanoTime();
            templates = compile(engine.factory, c.stylesheet);
            compileNanos += System.nanoTime() - start;
        }
        double compileMs = (compileNanos / (double) MEASURED_ITERATIONS) / 1_000_000.0;

        final Templates compiled = templates;
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            transform(compiled, c.source);
        }
        long transformNanos = 0;
        for (int i = 0; i < MEASURED_ITERATIONS; i++) {
            long start = System.nanoTime();
            transform(compiled, c.source);
            transformNanos += System.nanoTime() - start;
        }
        double transformMs = (transformNanos / (double) MEASURED_ITERATIONS) / 1_000_000.0;
        double mb = c.source.length / (1024.0 * 1024.0);
        double mbPerSec = mb / (transformMs / 1000.0);

        System.gc();
        long heapAfter = rt.totalMemory() - rt.freeMemory();
        long heapDelta = Math.max(0L, heapAfter - heapBefore);

        return String.format("%-22s %-12s %12.3f %12.3f %10.1f %10.1f KB",
                c.name, engine.name, compileMs, transformMs, mbPerSec, heapDelta / 1024.0);
    }

    private static Templates compile(TransformerFactory factory, byte[] stylesheet)
            throws Exception {
        StreamSource src = new StreamSource(new ByteArrayInputStream(stylesheet));
        return factory.newTemplates(src);
    }

    private static byte[] transform(Templates templates, byte[] source) throws Exception {
        Transformer transformer = templates.newTransformer();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        transformer.transform(
                new StreamSource(new ByteArrayInputStream(source)),
                new StreamResult(out));
        return out.toByteArray();
    }

    private static byte[] generateLargeDocument(int itemCount) throws IOException {
        StringBuilder sb = new StringBuilder(itemCount * 48);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<root><meta generated=\"bench\"/>");
        for (int i = 0; i < itemCount; i++) {
            sb.append("<item id=\"").append(i).append("\">value-").append(i).append("</item>");
        }
        sb.append("</root>");
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static final class Case {
        final String name;
        final String minVersion;
        final byte[] stylesheet;
        final byte[] source;

        Case(String name, String minVersion, byte[] stylesheet, byte[] source) {
            this.name = name;
            this.minVersion = minVersion;
            this.stylesheet = stylesheet;
            this.source = source;
        }
    }

    private static final class Engine {
        final String name;
        final TransformerFactory factory;

        Engine(String name, TransformerFactory factory) {
            this.name = name;
            this.factory = factory;
        }
    }
}

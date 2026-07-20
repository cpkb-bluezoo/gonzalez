/*
 * XsltHotPathBenchmark.java
 * Copyright (C) 2026 Chris Burdess
 *
 * JMH microbenchmarks for XSLT hot paths (LocationPath, AVT, for-each).
 */
package org.bluezoo.gonzalez.benchmark;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import javax.xml.transform.Templates;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * JMH benchmarks for XSLT transform hot paths used by the bake-off corpus.
 *
 * <p>Run via: {@code ant benchmark -Dbenchmark.class=org.bluezoo.gonzalez.benchmark.XsltHotPathBenchmark}
 * or {@code java -jar ... org.openjdk.jmh.Main org.bluezoo.gonzalez.benchmark.XsltHotPathBenchmark}.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(value = 1, jvmArgs = {"-Xms512m", "-Xmx512m"})
public class XsltHotPathBenchmark {

    @Param({"free-ranging", "relative-meta", "variable-meta", "streaming"})
    private String style;

    private Templates templates;
    private byte[] source;

    @Setup
    public void setup() throws Exception {
        source = generateDocument(2000);
        String xsl;
        switch (style) {
            case "relative-meta":
                xsl = ""
                    + "<xsl:stylesheet version='3.0' xmlns:xsl='http://www.w3.org/1999/XSL/Transform'>"
                    + "<xsl:output method='xml' indent='no'/>"
                    + "<xsl:template match='/'><out>"
                    + "<xsl:for-each select='root/item'>"
                    + "<row id='{@id}' stamp='{../meta/@generated}'>"
                    + "<xsl:value-of select='.'/></row>"
                    + "</xsl:for-each></out></xsl:template></xsl:stylesheet>";
                break;
            case "variable-meta":
                xsl = ""
                    + "<xsl:stylesheet version='3.0' xmlns:xsl='http://www.w3.org/1999/XSL/Transform'>"
                    + "<xsl:output method='xml' indent='no'/>"
                    + "<xsl:template match='/'>"
                    + "<xsl:variable name='stamp' select='/root/meta/@generated'/>"
                    + "<out><xsl:for-each select='root/item'>"
                    + "<row id='{@id}' stamp='{$stamp}'>"
                    + "<xsl:value-of select='.'/></row>"
                    + "</xsl:for-each></out></xsl:template></xsl:stylesheet>";
                break;
            case "streaming":
                xsl = ""
                    + "<xsl:stylesheet version='3.0' xmlns:xsl='http://www.w3.org/1999/XSL/Transform'>"
                    + "<xsl:mode streamable='yes' on-no-match='shallow-skip'/>"
                    + "<xsl:output method='xml' indent='no'/>"
                    + "<xsl:template match='/'><summary>"
                    + "<count><xsl:value-of select='count(root/item)'/></count>"
                    + "</summary></xsl:template></xsl:stylesheet>";
                break;
            case "free-ranging":
            default:
                xsl = ""
                    + "<xsl:stylesheet version='3.0' xmlns:xsl='http://www.w3.org/1999/XSL/Transform'>"
                    + "<xsl:output method='xml' indent='no'/>"
                    + "<xsl:template match='/'><out>"
                    + "<xsl:for-each select='root/item'>"
                    + "<row id='{@id}' stamp='{/root/meta/@generated}'>"
                    + "<xsl:value-of select='.'/></row>"
                    + "</xsl:for-each></out></xsl:template></xsl:stylesheet>";
                break;
        }
        TransformerFactory factory = TransformerFactory.newInstance(
                "org.bluezoo.gonzalez.transform.GonzalezTransformerFactory", null);
        templates = factory.newTemplates(new StreamSource(
                new ByteArrayInputStream(xsl.getBytes(StandardCharsets.UTF_8))));
    }

    @Benchmark
    public int transform() throws Exception {
        Transformer transformer = templates.newTransformer();
        ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
        transformer.transform(
                new StreamSource(new ByteArrayInputStream(source)),
                new StreamResult(out));
        return out.size();
    }

    private static byte[] generateDocument(int itemCount) {
        StringBuilder sb = new StringBuilder(itemCount * 40);
        sb.append("<root><meta generated='bench'/>");
        for (int i = 0; i < itemCount; i++) {
            sb.append("<item id='").append(i).append("'>v").append(i).append("</item>");
        }
        sb.append("</root>");
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }
}

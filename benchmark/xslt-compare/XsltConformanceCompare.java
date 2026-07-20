/*
 * Multi-engine W3C xslt30-test bake-off reporter.
 * Requires XSLT30_TEST_DIR. See run-conformance.sh.
 */
package org.bluezoo.gonzalez.xsltcompare;

import java.io.File;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class XsltConformanceCompare {

    public static void main(String[] args) throws Exception {
        String suitePath = System.getenv("XSLT30_TEST_DIR");
        if (suitePath == null || suitePath.isEmpty()) {
            System.err.println("XSLT30_TEST_DIR is not set; set it to the xslt30-test checkout.");
            System.exit(1);
        }
        File suiteDir = new File(suitePath).getAbsoluteFile();
        File catalog = new File(suiteDir, "catalog.xml");
        if (!catalog.exists()) {
            System.err.println("catalog.xml not found at " + catalog);
            System.exit(1);
        }

        System.out.println("Loading tests from " + suiteDir);
        List<CompareTestCase> tests = CatalogParser.load(suiteDir);
        System.out.println("Loaded " + tests.size() + " test cases");

        EngineAdapter[] engines = new EngineAdapter[] {
                new GonzalezAdapter(),
                new JdkAdapter(),
                new SaxonAdapter()
        };

        Map<String, int[]> counts = new LinkedHashMap<String, int[]>();
        for (EngineAdapter engine : engines) {
            // pass, fail, error, skip
            counts.put(engine.name(), new int[4]);
        }

        File outDir = new File("benchmark/xslt-compare/out");
        outDir.mkdirs();
        File matrixFile = new File(outDir, "conformance-matrix.txt");
        File summaryFile = new File(outDir, "conformance-summary.txt");
        File disagreeFile = new File(outDir, "conformance-disagreements.txt");

        PrintWriter matrix = new PrintWriter(matrixFile, StandardCharsets.UTF_8.name());
        PrintWriter disagree = new PrintWriter(disagreeFile, StandardCharsets.UTF_8.name());
        matrix.println("testId\tgonzalez\tjdk\tsaxon\tdetail");

        int index = 0;
        for (CompareTestCase test : tests) {
            index++;
            if (index % 200 == 0) {
                System.out.println("  ... " + index + "/" + tests.size());
            }
            Map<String, EngineResult> row = new LinkedHashMap<String, EngineResult>();
            for (EngineAdapter engine : engines) {
                EngineResult result = engine.run(test);
                row.put(engine.name(), result);
                int[] c = counts.get(engine.name());
                switch (result.status) {
                    case PASS: c[0]++; break;
                    case FAIL: c[1]++; break;
                    case ERROR: c[2]++; break;
                    case SKIP: c[3]++; break;
                }
            }
            matrix.printf("%s\t%s\t%s\t%s\t%s%n",
                    test.name,
                    status(row.get("gonzalez")),
                    status(row.get("jdk")),
                    status(row.get("saxon")),
                    firstDetail(row));

            EngineResult g = row.get("gonzalez");
            EngineResult s = row.get("saxon");
            if (g.status == Status.PASS && s.status == Status.PASS
                    && g.output != null && s.output != null
                    && !XmlCompare.equals(g.output, s.output)) {
                disagree.println(test.name + "\tgonzalez-vs-saxon output disagree");
            }
            EngineResult j = row.get("jdk");
            if (g.status == Status.PASS && j.status == Status.PASS
                    && g.output != null && j.output != null
                    && !XmlCompare.equals(g.output, j.output)) {
                disagree.println(test.name + "\tgonzalez-vs-jdk output disagree");
            }
        }
        matrix.close();
        disagree.close();

        PrintWriter summary = new PrintWriter(summaryFile, StandardCharsets.UTF_8.name());
        summary.println("XSLT bake-off conformance summary");
        summary.println("suite=" + suiteDir);
        summary.println("tests=" + tests.size());
        summary.println();
        summary.printf("%-12s %8s %8s %8s %8s %8s%n",
                "engine", "pass", "fail", "error", "skip", "rate");
        System.out.println();
        System.out.printf("%-12s %8s %8s %8s %8s %8s%n",
                "engine", "pass", "fail", "error", "skip", "rate");
        for (EngineAdapter engine : engines) {
            int[] c = counts.get(engine.name());
            int attempted = c[0] + c[1] + c[2];
            String rate = attempted == 0 ? "n/a"
                    : String.format("%.1f%%", (100.0 * c[0]) / attempted);
            String line = String.format("%-12s %8d %8d %8d %8d %8s",
                    engine.name(), c[0], c[1], c[2], c[3], rate);
            summary.println(line);
            System.out.println(line);
        }
        summary.println();
        summary.println("matrix=" + matrixFile.getPath());
        summary.println("disagreements=" + disagreeFile.getPath());
        summary.close();

        System.out.println();
        System.out.println("Wrote " + summaryFile.getPath());
        System.out.println("Wrote " + matrixFile.getPath());
        System.out.println("Wrote " + disagreeFile.getPath());
    }

    private static String status(EngineResult result) {
        if (result == null) {
            return "?";
        }
        if (result.status == Status.SKIP && result.detail != null) {
            return "SKIP(" + result.detail + ")";
        }
        return result.status.name();
    }

    private static String firstDetail(Map<String, EngineResult> row) {
        List<String> parts = new ArrayList<String>();
        for (Map.Entry<String, EngineResult> e : row.entrySet()) {
            if (e.getValue().detail != null
                    && (e.getValue().status == Status.FAIL || e.getValue().status == Status.ERROR)) {
                parts.add(e.getKey() + ":" + e.getValue().detail.replace('\t', ' '));
            }
        }
        return parts.isEmpty() ? "" : String.join(" | ", parts);
    }
}

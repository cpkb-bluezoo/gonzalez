/*
 * ChunkingInvariantTest.java
 * Copyright (C) 2026 Chris Burdess
 *
 * This file is part of Gonzalez, a streaming XML parser.
 * For more information please visit https://www.nongnu.org/gonzalez/
 *
 * Gonzalez is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Gonzalez is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Gonzalez.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.bluezoo.gonzalez;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import static org.junit.Assert.fail;

/**
 * Applies {@link ChunkFuzzer} across the JMH benchmark document corpus and a
 * sample of the xmlconf test suite's standalone documents, establishing that
 * today's single-pipeline parser is chunk-invariant. This is valuable on its
 * own, and from milestone M2 onward the same corpus and the same harness are
 * reused to diff the legacy tokenizer against the byte-native tokenizer.
 * <p>
 * Documents larger than {@link #LARGE_FILE_THRESHOLD} are checked with
 * {@link ChunkFuzzer#LARGE_FILE_CHUNK_SIZES} instead of the full byte-at-a-time
 * sweep, since chunking a megabyte-sized file one byte at a time adds test
 * time without exercising any boundary condition that coarser chunk sizes
 * don't already reach.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ChunkingInvariantTest {

    private static final int LARGE_FILE_THRESHOLD = 65536;

    private static final File BENCHMARK_DIR = new File("benchmark/resources");

    private static final File[] XMLCONF_SAMPLE_DIRS = {
        new File("xmlconf/xmltest/valid/sa"),
        new File("xmlconf/xmltest/not-wf/sa"),
    };

    @Test
    public void testBenchmarkCorpusIsChunkInvariant() throws IOException {
        File[] files = BENCHMARK_DIR.listFiles(new XmlFilenameFilter());
        assertNotNullOrEmpty(BENCHMARK_DIR, files);
        List<String> failures = new ArrayList<String>();
        for (int i = 0; i < files.length; i++) {
            checkFile(files[i], failures);
        }
        reportFailures(failures);
    }

    @Test
    public void testXmlconfStandaloneSampleIsChunkInvariant() throws IOException {
        List<String> failures = new ArrayList<String>();
        for (int d = 0; d < XMLCONF_SAMPLE_DIRS.length; d++) {
            File dir = XMLCONF_SAMPLE_DIRS[d];
            File[] files = dir.listFiles(new XmlFilenameFilter());
            assertNotNullOrEmpty(dir, files);
            for (int i = 0; i < files.length; i++) {
                checkFile(files[i], failures);
            }
        }
        reportFailures(failures);
    }

    private void checkFile(File file, List<String> failures) throws IOException {
        byte[] data = readFully(file);
        try {
            if (data.length > LARGE_FILE_THRESHOLD) {
                ChunkFuzzer.assertChunkInvariant(file.getPath(), data, ChunkFuzzer.LARGE_FILE_CHUNK_SIZES);
            } else {
                ChunkFuzzer.assertChunkInvariant(file.getPath(), data);
            }
        } catch (AssertionError e) {
            failures.add(e.getMessage());
        }
    }

    private void reportFailures(List<String> failures) {
        if (failures.isEmpty()) {
            return;
        }
        StringBuilder buf = new StringBuilder();
        buf.append(failures.size());
        buf.append(" document(s) were not chunk-invariant:\n");
        for (int i = 0; i < failures.size(); i++) {
            buf.append(failures.get(i));
            buf.append("\n");
        }
        fail(buf.toString());
    }

    private void assertNotNullOrEmpty(File dir, File[] files) {
        if (files == null || files.length == 0) {
            fail("No XML test documents found under " + dir.getPath()
                    + " - check the working directory the test is run from");
        }
    }

    private byte[] readFully(File file) throws IOException {
        byte[] buf = new byte[(int) file.length()];
        InputStream in = new FileInputStream(file);
        try {
            int total = 0;
            while (total < buf.length) {
                int n = in.read(buf, total, buf.length - total);
                if (n < 0) {
                    break;
                }
                total += n;
            }
        } finally {
            in.close();
        }
        return buf;
    }

    private static class XmlFilenameFilter implements java.io.FilenameFilter {
        @Override
        public boolean accept(File dir, String name) {
            return name.endsWith(".xml");
        }
    }
}

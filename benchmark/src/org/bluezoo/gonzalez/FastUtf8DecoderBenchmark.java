/*
 * FastUtf8DecoderBenchmark.java
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

import org.openjdk.jmh.annotations.*;

import java.io.FileNotFoundException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

/**
 * M1 gate benchmark (see FAST-UTF8-DECODER.md): {@link FastUtf8CharsetDecoder}
 * against the JDK's own UTF-8 {@code CharsetDecoder}, decode-only - no
 * tokenizing, no SAX {@code Parser} involved. Deliberately lives in package
 * {@code org.bluezoo.gonzalez} (unlike the other benchmark classes, in
 * {@code org.bluezoo.gonzalez.benchmark}) purely to reach the package-private
 * {@code FastUtf8CharsetDecoder} class directly.
 * <p>
 * Both decoders are plain {@code CharsetDecoder}s, so the exact same harness
 * drives either one identically - this is the whole point of Decision 1 in
 * FAST-UTF8-DECODER.md.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(value = 2, jvmArgs = {"-Xms2G", "-Xmx2G"})
public class FastUtf8DecoderBenchmark {

    @Param({"plain", "namespaced", "attrs", "whitespace", "markup", "multibyte"})
    private String docType;

    private byte[] largeBytes;
    private CharBuffer outputBuffer;

    @Setup
    public void setup() throws Exception {
        Path largeFile;
        switch (docType) {
            case "namespaced":
                largeFile = Paths.get("benchmark/resources/large-ns.xml");
                break;
            case "attrs":
                largeFile = Paths.get("benchmark/resources/attrs-large.xml");
                break;
            case "whitespace":
                largeFile = Paths.get("benchmark/resources/whitespace-large.xml");
                break;
            case "markup":
                largeFile = Paths.get("benchmark/resources/markup-large.xml");
                break;
            case "multibyte":
                largeFile = Paths.get("benchmark/resources/multibyte-large.xml");
                break;
            default:
                largeFile = Paths.get("benchmark/resources/large.xml");
                break;
        }
        if (!Files.exists(largeFile)) {
            throw new FileNotFoundException("Large test file not found: " + largeFile);
        }
        largeBytes = Files.readAllBytes(largeFile);

        // UTF-8 byte count is always >= the resulting char count (1:1 for
        // ASCII, fewer chars than bytes for every multi-byte sequence), so
        // this capacity never overflows for a whole-buffer decode.
        outputBuffer = CharBuffer.allocate(largeBytes.length + 16);

        System.out.println("Benchmark setup complete:");
        System.out.println("  Document type: " + docType);
        System.out.println("  Large file bytes: " + largeBytes.length);
    }

    @Benchmark
    public int decodeFast() {
        return decodeWhole(new FastUtf8CharsetDecoder());
    }

    @Benchmark
    public int decodeJdk() {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        return decodeWhole(decoder);
    }

    private int decodeWhole(CharsetDecoder decoder) {
        ByteBuffer in = ByteBuffer.wrap(largeBytes);
        outputBuffer.clear();
        CoderResult result = decoder.decode(in, outputBuffer, true);
        if (result.isError()) {
            throw new IllegalStateException("Unexpected coder error: " + result);
        }
        decoder.flush(outputBuffer);
        return outputBuffer.position();
    }

}

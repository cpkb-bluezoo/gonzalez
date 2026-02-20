/*
 * AllocationProfiler.java
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

package org.bluezoo.gonzalez.benchmark;

import org.bluezoo.gonzalez.Parser;
import org.xml.sax.helpers.DefaultHandler;

import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Standalone allocation profiler using ThreadMXBean.getThreadAllocatedBytes().
 * Measures exact bytes allocated per parse for both new-parser and reuse cases.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class AllocationProfiler {

    private static final com.sun.management.ThreadMXBean THREAD_MX =
        (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

    private static long getAllocatedBytes() {
        return THREAD_MX.getThreadAllocatedBytes(Thread.currentThread().threadId());
    }

    public static void main(String[] args) throws Exception {
        Path largeFile = Paths.get("benchmark/resources/large.xml");
        byte[] largeBytes = Files.readAllBytes(largeFile);
        DefaultHandler emptyHandler = new DefaultHandler();

        System.out.println("File: " + largeFile + " (" + largeBytes.length + " bytes)");
        System.out.println();

        // Warmup (trigger class loading, JIT)
        for (int i = 0; i < 20; i++) {
            Parser p = new Parser();
            p.setFeature("http://xml.org/sax/features/namespaces", false);
            p.setContentHandler(emptyHandler);
            p.receive(ByteBuffer.wrap(largeBytes));
            p.close();
        }

        // === Test 1: New parser each time ===
        long[] newParserAllocs = new long[10];
        for (int i = 0; i < newParserAllocs.length; i++) {
            long before = getAllocatedBytes();
            Parser p = new Parser();
            p.setFeature("http://xml.org/sax/features/namespaces", false);
            p.setContentHandler(emptyHandler);
            p.receive(ByteBuffer.wrap(largeBytes));
            p.close();
            newParserAllocs[i] = getAllocatedBytes() - before;
        }

        long newParserAvg = 0;
        for (long v : newParserAllocs) {
            newParserAvg += v;
        }
        newParserAvg /= newParserAllocs.length;
        System.out.println("=== New Parser (avg of " + newParserAllocs.length + ") ===");
        System.out.println("  Allocated: " + newParserAvg + " B/op (" + (newParserAvg / 1024) + " KB)");
        System.out.println();

        // === Test 2: Parser reuse ===
        Parser reusable = new Parser();
        reusable.setFeature("http://xml.org/sax/features/namespaces", false);
        reusable.setContentHandler(emptyHandler);
        // Prime it
        reusable.receive(ByteBuffer.wrap(largeBytes));
        reusable.close();

        long[] reuseAllocs = new long[10];
        for (int i = 0; i < reuseAllocs.length; i++) {
            reusable.reset();
            long before = getAllocatedBytes();
            reusable.receive(ByteBuffer.wrap(largeBytes));
            reusable.close();
            reuseAllocs[i] = getAllocatedBytes() - before;
        }

        long reuseAvg = 0;
        for (long v : reuseAllocs) {
            reuseAvg += v;
        }
        reuseAvg /= reuseAllocs.length;
        System.out.println("=== Parser Reuse (avg of " + reuseAllocs.length + ") ===");
        System.out.println("  Allocated: " + reuseAvg + " B/op (" + (reuseAvg / 1024) + " KB)");
        System.out.println();

        // === Test 3: Break down new parser allocation ===
        System.out.println("=== Allocation Breakdown (new parser) ===");

        // Just constructor
        long before = getAllocatedBytes();
        Parser p = new Parser();
        p.setFeature("http://xml.org/sax/features/namespaces", false);
        p.setContentHandler(emptyHandler);
        long constructorAlloc = getAllocatedBytes() - before;
        System.out.println("  Parser construction:  " + constructorAlloc + " B (" + (constructorAlloc / 1024) + " KB)");

        // Just parsing
        before = getAllocatedBytes();
        p.receive(ByteBuffer.wrap(largeBytes));
        p.close();
        long parseAlloc = getAllocatedBytes() - before;
        System.out.println("  Parsing (receive+close): " + parseAlloc + " B (" + (parseAlloc / 1024) + " KB)");
        System.out.println("  Total:                " + (constructorAlloc + parseAlloc) + " B");
        System.out.println();

        // === Test 4: Break down reuse allocation ===
        System.out.println("=== Allocation Breakdown (reuse, primed) ===");

        reusable.reset();
        before = getAllocatedBytes();
        long resetEnd = getAllocatedBytes();
        System.out.println("  reset():              " + (resetEnd - before) + " B");

        before = getAllocatedBytes();
        reusable.receive(ByteBuffer.wrap(largeBytes));
        reusable.close();
        long reuseParseAlloc = getAllocatedBytes() - before;
        System.out.println("  Parsing (receive+close): " + reuseParseAlloc + " B (" + (reuseParseAlloc / 1024) + " KB)");
        System.out.println();

        // === Test 5: Null handler (pure tokenizer + ContentParser overhead, no SAX callbacks) ===
        Parser nullHandler = new Parser();
        nullHandler.setFeature("http://xml.org/sax/features/namespaces", false);
        // No setContentHandler - leave it null
        // Prime
        nullHandler.receive(ByteBuffer.wrap(largeBytes));
        nullHandler.close();
        nullHandler.reset();
        // Prime again
        nullHandler.receive(ByteBuffer.wrap(largeBytes));
        nullHandler.close();
        nullHandler.reset();

        long[] nullAllocs = new long[10];
        for (int i = 0; i < nullAllocs.length; i++) {
            nullHandler.reset();
            before = getAllocatedBytes();
            nullHandler.receive(ByteBuffer.wrap(largeBytes));
            nullHandler.close();
            nullAllocs[i] = getAllocatedBytes() - before;
        }

        long nullAvg = 0;
        for (long v : nullAllocs) {
            nullAvg += v;
        }
        nullAvg /= nullAllocs.length;
        System.out.println("=== Null ContentHandler (reuse, avg of " + nullAllocs.length + ") ===");
        System.out.println("  Allocated: " + nullAvg + " B/op (" + (nullAvg / 1024) + " KB)");
        System.out.println("  SAX overhead: " + (reuseAvg - nullAvg) + " B/op (" + ((reuseAvg - nullAvg) / 1024) + " KB)");
        System.out.println();

        // === Test 6: With namespaces enabled (standard SAX usage) ===
        Parser nsParser = new Parser();
        nsParser.setFeature("http://xml.org/sax/features/namespaces", true);
        nsParser.setContentHandler(emptyHandler);
        // Prime
        nsParser.receive(ByteBuffer.wrap(largeBytes));
        nsParser.close();
        nsParser.reset();
        nsParser.receive(ByteBuffer.wrap(largeBytes));
        nsParser.close();
        nsParser.reset();

        long[] nsAllocs = new long[10];
        for (int i = 0; i < nsAllocs.length; i++) {
            nsParser.reset();
            before = getAllocatedBytes();
            nsParser.receive(ByteBuffer.wrap(largeBytes));
            nsParser.close();
            nsAllocs[i] = getAllocatedBytes() - before;
        }

        long nsAvg = 0;
        for (long v : nsAllocs) {
            nsAvg += v;
        }
        nsAvg /= nsAllocs.length;
        System.out.println("=== Namespaces Enabled (reuse, avg of " + nsAllocs.length + ") ===");
        System.out.println("  Allocated: " + nsAvg + " B/op (" + (nsAvg / 1024) + " KB)");
        System.out.println();

        // === Test 7: Show per-iteration detail for reuse to check stability ===
        System.out.println("=== Per-iteration (reuse, no namespaces) ===");
        for (int i = 0; i < reuseAllocs.length; i++) {
            System.out.println("  Iter " + i + ": " + reuseAllocs[i] + " B");
        }
        System.out.println();

        System.out.println("=== Per-iteration (null handler) ===");
        for (int i = 0; i < nullAllocs.length; i++) {
            System.out.println("  Iter " + i + ": " + nullAllocs[i] + " B");
        }
    }
}

/*
 * ChunkFuzzer.java
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

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Random;

import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

/**
 * Differential, chunk-fuzzing test harness.
 * <p>
 * Parses the same document bytes multiple times through {@link Parser},
 * varying only how the input stream happens to split the bytes across reads,
 * and asserts that the recorded SAX event sequence (see {@link
 * RecordingSaxHandler}) is identical regardless of chunking. This exercises
 * exactly the class of bug that ordinary test suites miss: buffer-boundary
 * handling inside the tokenizer and {@code ExternalEntityDecoder}, where a
 * multi-byte/multi-character construct straddling a chunk boundary can be
 * mishandled even though the same document parses correctly when delivered
 * whole.
 * <p>
 * The same harness is reused from M2 onward to diff the legacy tokenizer
 * against the byte-native tokenizer on identical input.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class ChunkFuzzer {

    /** Chunk sizes applied to every document, in addition to a whole-buffer reference parse. */
    static final int[] STANDARD_CHUNK_SIZES = {1, 2, 3, 7, 17};

    /** Coarser chunk sizes for large documents, where byte-at-a-time chunking would be too slow. */
    static final int[] LARGE_FILE_CHUNK_SIZES = {4096, 8191};

    /** Fixed seed so random-split runs are reproducible across test executions. */
    static final long RANDOM_SPLIT_SEED = 20260716L;

    private ChunkFuzzer() {
    }

    /**
     * Parses the given document, delivered through an input stream capped at
     * {@code chunkSize} bytes per read (or unthrottled if {@code chunkSize}
     * is 0), and returns a canonical outcome: the recorded event list on
     * success, or the exception class name and message on failure.
     *
     * @param data the document bytes
     * @param chunkSize the maximum bytes per underlying read, or 0 for no cap
     * @return the recorded events, or a description of the thrown exception
     */
    static Object parse(byte[] data, int chunkSize) {
        InputStream in = new ByteArrayInputStream(data);
        if (chunkSize > 0) {
            in = new ChunkedInputStream(in, chunkSize);
        }
        return doParse(in);
    }

    /**
     * Parses the given document, delivered through an input stream whose
     * per-read length is drawn randomly (1-32 bytes) from a source seeded
     * with {@code seed}.
     *
     * @param data the document bytes
     * @param seed the random seed controlling the chunk boundaries
     * @return the recorded events, or a description of the thrown exception
     */
    static Object parseWithRandomSplits(byte[] data, long seed) {
        InputStream in = new ChunkedInputStream(new ByteArrayInputStream(data), new Random(seed));
        return doParse(in);
    }

    private static Object doParse(InputStream in) {
        try {
            RecordingSaxHandler handler = new RecordingSaxHandler();
            Parser parser = new Parser();
            parser.setContentHandler(handler);
            parser.setDTDHandler(handler);
            parser.setErrorHandler(handler);
            parser.setProperty("http://xml.org/sax/properties/lexical-handler", handler);
            parser.setProperty("http://xml.org/sax/properties/declaration-handler", handler);
            parser.parse(new InputSource(in));
            return handler.getEvents();
        } catch (SAXException e) {
            return outcome(e);
        } catch (Exception e) {
            return outcome(e);
        }
    }

    private static String outcome(Exception e) {
        return e.getClass().getName() + ":" + normalizeMessage(e.getMessage());
    }

    /**
     * Strips the trailing diagnostic "context: '...'" substring some tokenizer
     * error messages include. Its length is bounded by how much lookahead had
     * been buffered when the mismatch was detected, so it can legitimately be
     * shorter for a byte-at-a-time parse than for a whole-buffer one even
     * though the error itself - type, position, expected/found characters -
     * is identical; comparing it verbatim would produce a false divergence.
     */
    private static String normalizeMessage(String message) {
        if (message == null) {
            return null;
        }
        int idx = message.indexOf(", context: '");
        if (idx < 0) {
            return message;
        }
        return message.substring(0, idx);
    }

    /**
     * Asserts that parsing the given document is invariant to chunking: a
     * whole-buffer reference parse is compared against parses using each of
     * {@link #STANDARD_CHUNK_SIZES} and one random-split parse.
     *
     * @param label a short label identifying the document, used in failure messages
     * @param data the document bytes
     */
    static void assertChunkInvariant(String label, byte[] data) {
        assertChunkInvariant(label, data, STANDARD_CHUNK_SIZES);
    }

    /**
     * Asserts that parsing the given document is invariant to chunking, using
     * an explicit set of chunk sizes instead of {@link #STANDARD_CHUNK_SIZES}.
     * Useful for large documents, where byte-at-a-time chunking would make
     * the test prohibitively slow without adding meaningful coverage beyond
     * what coarser chunk sizes already exercise.
     *
     * @param label a short label identifying the document, used in failure messages
     * @param data the document bytes
     * @param chunkSizes the chunk sizes to check against the whole-buffer reference
     */
    static void assertChunkInvariant(String label, byte[] data, int[] chunkSizes) {
        Object reference = parse(data, 0);
        for (int i = 0; i < chunkSizes.length; i++) {
            int chunkSize = chunkSizes[i];
            Object result = parse(data, chunkSize);
            if (!reference.equals(result)) {
                throw new AssertionError(label + ": chunk size " + chunkSize + " diverged from whole-buffer parse\n"
                        + "reference=" + reference + "\n"
                        + "actual=" + result);
            }
        }
        Object randomResult = parseWithRandomSplits(data, RANDOM_SPLIT_SEED);
        if (!reference.equals(randomResult)) {
            throw new AssertionError(label + ": random-split chunking diverged from whole-buffer parse\n"
                    + "reference=" + reference + "\n"
                    + "actual=" + randomResult);
        }
    }

}

/*
 * ChunkedInputStream.java
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

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Random;

/**
 * An input stream that caps every underlying read at a small number of
 * bytes, so that a parser fed through it sees the same document delivered in
 * many short reads instead of one large one.
 * <p>
 * Used by the chunk-fuzzing harness to verify that parsing is invariant to
 * how the input happens to be chunked by the underlying stream. With a fixed
 * chunk size every read returns at most that many bytes; with a {@link
 * Random} supplied instead, each read returns a random length between 1 and
 * 32 bytes, so buffer-boundary placement varies from run to run for a given
 * seed.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class ChunkedInputStream extends FilterInputStream {

    private static final int RANDOM_MAX_CHUNK = 32;

    private final int fixedChunkSize;
    private final Random random;

    /**
     * Creates a stream that returns at most {@code fixedChunkSize} bytes per
     * read.
     *
     * @param in the underlying stream
     * @param fixedChunkSize the maximum number of bytes to return per read,
     *                       must be at least 1
     */
    ChunkedInputStream(InputStream in, int fixedChunkSize) {
        super(in);
        this.fixedChunkSize = fixedChunkSize;
        this.random = null;
    }

    /**
     * Creates a stream that returns a random number of bytes (between 1 and
     * 32) per read, drawn from the given source.
     *
     * @param in the underlying stream
     * @param random the source of per-read chunk sizes
     */
    ChunkedInputStream(InputStream in, Random random) {
        super(in);
        this.fixedChunkSize = 0;
        this.random = random;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        int cap = (random != null) ? (1 + random.nextInt(RANDOM_MAX_CHUNK)) : fixedChunkSize;
        int actual = Math.min(len, cap);
        return super.read(b, off, actual);
    }

}

/*
 * ScannerStorage.java
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
 */

package org.bluezoo.gonzalez;

import java.nio.CharBuffer;

/**
 * The memory a {@link Scanner} works in that does not depend on the
 * document: its scan buffer, its table of interned names, its element stack,
 * its name predictions and the buffer the decoder fills. Its size is set by
 * the {@link XMLParser}'s settings, not by what is parsed, and it is
 * allocated once: an {@code XMLParser} keeps it from one document to the
 * next, so that a parser used for many documents allocates none of it after
 * the first.
 * <p>
 * Nothing here records anything about a document. The scan buffer and the
 * element stack are written before they are read; the interned names are
 * the same whichever document they came from; and a prediction is only ever
 * a guess that is checked against the characters before it is believed. The
 * scanner's own state is not kept: each document gets a new {@code Scanner},
 * so that nothing of the last one can be left in it.
 * <p>
 * The scan buffer and the element stack grow if a document needs it (a long
 * name, a large declaration, deep nesting), and {@link #trim()} puts them
 * back to the size that was asked for when the document is done, so that one
 * unusual document does not decide what is held for the rest.
 */
final class ScannerStorage {

    /** The scan buffer's default size, in characters. */
    static final int DEFAULT_SCAN_BUFFER = 8192;

    /** The decoder's character buffer's default size, in characters. */
    static final int DEFAULT_DECODE_BUFFER = 32768;

    /** The name table's default size, in entries. */
    static final int DEFAULT_NAME_TABLE = 512;

    /** The smallest scan buffer: room for a start tag's worth of the usual. */
    static final int MIN_SCAN_BUFFER = 256;

    /** The smallest decoder buffer: room for any character and a little more. */
    static final int MIN_DECODE_BUFFER = 16;

    /** The smallest name table. */
    static final int MIN_NAME_TABLE = 16;

    final int scanBufferSize;
    final int decodeBufferSize;
    final int nameTableSize;

    /** The scan buffer; the scanner replaces it here when it has to grow it. */
    char[] buf;

    /** The interned names. */
    final PackedName names;

    /** The stack of open element names; replaced here when it has to grow. */
    String[] elementNames = new String[Scanner.INITIAL_ELEMENT_DEPTH];

    final String[] elementPredictKeys = new String[Scanner.ELEMENT_PREDICT_SIZE];
    final String[] elementPredictNames = new String[Scanner.ELEMENT_PREDICT_SIZE];
    final String[] attrPredictKeys = new String[Scanner.ATTR_PREDICT_SIZE];
    final String[] attrPredictNames = new String[Scanner.ATTR_PREDICT_SIZE];

    private CharBuffer decodeBuffer;

    ScannerStorage() {
        this(DEFAULT_SCAN_BUFFER, DEFAULT_DECODE_BUFFER, DEFAULT_NAME_TABLE);
    }

    ScannerStorage(int scanBufferSize, int decodeBufferSize, int nameTableSize) {
        this.scanBufferSize = scanBufferSize;
        this.decodeBufferSize = decodeBufferSize;
        this.nameTableSize = nameTableSize;
        this.buf = new char[scanBufferSize];
        this.names = new PackedName(nameTableSize);
    }

    /** True if this was made for these sizes. */
    boolean hasSizes(int scanBufferSize, int decodeBufferSize, int nameTableSize) {
        return this.scanBufferSize == scanBufferSize
                && this.decodeBufferSize == decodeBufferSize
                && this.nameTableSize == nameTableSize;
    }

    /** The buffer the decoder fills, cleared: the same one each time. */
    CharBuffer decodeBuffer() {
        if (decodeBuffer == null) {
            decodeBuffer = CharBuffer.allocate(decodeBufferSize);
        } else {
            decodeBuffer.clear();
        }
        return decodeBuffer;
    }

    /** The decoder's buffer's size, or zero before it is first wanted. */
    int decodeBufferCapacity() {
        return decodeBuffer == null ? 0 : decodeBuffer.capacity();
    }

    /**
     * Called between documents: puts back to the size asked for anything
     * that grew beyond it, to be grown again only if the next document
     * needs it.
     */
    void trim() {
        if (buf.length > scanBufferSize) {
            buf = new char[scanBufferSize];
        }
        if (elementNames.length > Scanner.INITIAL_ELEMENT_DEPTH) {
            elementNames = new String[Scanner.INITIAL_ELEMENT_DEPTH];
        }
    }
}

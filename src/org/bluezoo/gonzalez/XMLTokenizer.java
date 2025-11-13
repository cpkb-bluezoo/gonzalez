/*
 * XMLTokenizer.java
 * Copyright (C) 2025 Chris Burdess
 *
 * This file is part of Gonzalez, a streaming XML parser.
 * For more information please visit https://www.nongnu.org/gonzalez/
 *
 * Gonzalez is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Gonzalez is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Gonzalez.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.bluezoo.gonzalez;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.xml.sax.SAXException;
import org.xml.sax.ext.Locator2;

/**
 * Handles tokenization for buffers of XML characters.
 * <p>
 * This class is a SAX Locator2 so it will keep track of the current line
 * and column number. It handles the initial charset determination via
 * the BOM and/or XML (Text) declaration, then will switch to character
 * mode, using its internal byte buffer thereafter as an underflow in
 * case incoming bytes could not be completely decoded to characters.
 * <p>
 * During XML declaration parsing the version, charset, and standalone
 * status are determined.
 * <p>
 * In character mode the tokenizer will act as a stream of tokens and
 * push each token to the parser.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class XMLTokenizer implements Locator2 {

    /**
     * The character set which will be used to decode incoming bytes
     * into characters.
     */
    private Charset charset = StandardCharsets.UTF_8;

    /**
     * The version of XML that this document represents.
     */
    private String version = "1.0";

    /**
     * Whether this document is standalone.
     */
    private boolean standalone;

    /**
     * The internal byte buffer.
     * This will be used in 2 phases:
     * <ol>
     * <li>Before the BOM/XML declaration is completely parsed, if
     * there is an underflow and we need to wait for more data</li>
     * <li>After switching to character mode, if there was an
     * underflow decoding data given to the character buffer, it
     * will be stored here until the next invocation of receive</li>
     * </ol>
     * This buffer will always be kept ready for a read between
     * operations.
     */
    private ByteBuffer byteBuffer;

    /**
     * The internal character buffer.
     * This will only be allocated once the charset is determined.
     * Its presence indicates that we are now in character mode.
     * Tokens will be extracted from this buffer and pushed to the
     * parser until no more are possible. Then the underflow, if any,
     * is relocated to the start of the buffer.
     * <p>
     * This buffer will always be kept ready for a read between
     * operations.
     */
    private CharBuffer charBuffer;

    /**
     * The character decoder.
     * This will be allocated once the charset is determined.
     */
    private CharsetDecoder decoder;

    /**
     * Line number. 1-based.
     */
    private long lineNumber = 1;

    /**
     * Column number. 0-based.
     */
    private long columnNumber;

    /**
     * Public ID of the document.
     */
    private final String publicId;

    /**
     * System ID of the document.
     */
    private final String systemId;

    static enum State {
        INIT, BOM_READ, CHARACTERS
    }

    private State state = State.INIT;
    private boolean closed;

    /**
     * Constructs a new XMLTokenizer with no publicId or systemId.
     */
    public XMLTokenizer() {
        this(null, null);
    }

    /**
     * Constructs a new XMLTokenizer with the specified publicId
     * and systemId.
     * These are metadata about the document that can describe how
     * it was resolved as an entity.
     * @param publicId the public identifier of the document
     * @param systemId the system identifier of the document
     */
    public XMLTokenizer(String publicId, String systemId) {
        this.publicId = publicId;
        this.systemId = systemId;
    }

    /**
     * Receivs data.
     * Multiple invocations of this method may occur supplying the
     * underlying byte data of the raw document content.
     * @param data a byte buffer ready for reading the content from
     */
    public void receive(ByteBuffer data) throws SAXException {
        if (closed) {
            throw new IllegalStateException();
        }
        
        switch (state) {
            case INIT:
                // If there is enough data to read the BOM, do so,
                // otherwise store the underflow in the byte buffer
                // and return.
                // TODO
                state = State.BOM_READ;
                // Fall through
            case BOM_READ:
                // If there is enough data to read the XML declaration,
                // do so, otherwise store the underflow in the byte
                // buffer and return.
                // TODO
                charBuffer = CharBuffer.allocate(Math.max(4096, data.capacity()));
                decoder = charset.newDecoder();
                state = State.CHARACTERS;
                // Fall through
            case CHARACTERS:
                // Prepend the byte underflow to the data if the
                // underflow exists.
                // Decode the data into the character buffer.
                // If this operation returned an underflow, store
                // unconsumed bytes back into the byte underflow.
                // TODO 2.11 End-of-Line Handling
                // TODO
        }
    }

    /**
     * Notifies the tokenizer that all data has been received.
     * No more invocations of receive will occur after this method
     * has been called, and to do so is an error.
     */
    public void close() throws SAXException {
        if (closed) {
            throw new IllegalStateException();
        }
        closed = true;

        // Process any remaining bytes
        if (decoder != null && byteBuffer != null) {
            // TODO Ensure there is capacity in the charBuffer
            // Decode byte underflow into charBuffer
            charBuffer.flip(); // ready for writing
            CoderResult result = decoder.decode(byteBuffer, charBuffer, true);
            charBuffer.flip();
            // Process charBuffer
            parse();
        }
    }

}

/*
 * ExternalEntityDecoder.java
 * Copyright (C) 2025 Chris Burdess
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

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.util.Objects;
import org.xml.sax.SAXException;

/**
 * Decodes byte streams from external entities into character streams for scanning.
 * 
 * <p>This class handles:
 * <ul>
 * <li>BOM (Byte Order Mark) detection</li>
 * <li>XML/text declaration parsing (directly from bytes - no decode needed)</li>
 * <li>Charset decoding with underflow handling</li>
 * <li>Line-ending normalization</li>
 * </ul>
 * 
 * <p>Decoded character data is fed to a {@link Scanner} via {@link
 * Scanner#receive(CharBuffer)}.
 * 
 * <h3>Zero-Copy Declaration Parsing</h3>
 * <p>XML/text declarations only contain 7-bit ASCII characters. This class
 * parses declarations directly from the ByteBuffer without creating a CharsetDecoder,
 * using the byte encoding scheme (UTF-8/UTF-16LE/UTF-16BE) detected from the BOM.
 * The CharsetDecoder is only created after the declaration is parsed and the
 * final encoding is known.
 * 
 * <h3>Buffer Contract</h3>
 * <p>The caller is responsible for proper buffer management following the standard
 * NIO pattern: read, flip, receive, compact. On entry to {@code receive()}, the
 * buffer must be in read mode (position at start of data, limit at end). On exit,
 * the buffer's position will point to the first unconsumed byte (which may be
 * part of an incomplete multi-byte character sequence). The caller must compact
 * the buffer before reading more data.
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class ExternalEntityDecoder {
    
    // ===== Configuration and Document Metadata =====
    
    /**
     * The character set used to decode incoming bytes into characters.
     * Only set after declaration parsing is complete.
     */
    private Charset charset;
    
    /**
     * Previous charset from last parse, kept for decoder reuse.
     */
    private Charset previousCharset;

    /**
     * True when the resolved charset treats bytes below 0x80 as plain ASCII
     * (UTF-8, US-ASCII, ISO-8859-1), so a decoded U+000D can only come from
     * a literal 0x0D byte - lets decodeAndTokenize() prove a chunk CR-free
     * with one byte-level scan and skip normalizeLineEndings()'s per-char
     * scan. Set once per document/entity in {@link #setupCharsetDecoder}.
     */
    private boolean asciiFastPathEligible;
    
    /**
     * The BOM detected at start of document.
     * Determines byte encoding for declaration parsing.
     */
    private BOM bom = BOM.NONE;
    
    // ===== Scanner Integration =====
    
    /**
     * Whether this decoder is for an external entity (vs. the document entity).
     * External entities have text declarations (no standalone attribute allowed),
     * while the document entity has an XML declaration (standalone attribute allowed).
     */
    private final boolean isExternalEntity;
    
    /**
     * Whether this entity is being processed as XML 1.1 (vs. XML 1.0).
     * Affects character validity rules and line normalization.
     * Set when XML/text declaration specifies version="1.1".
     */
    private boolean xml11 = false;
    
    // ===== Buffers =====
    
    /**
     * Character decoder for the current charset.
     * Only created after declaration parsing is complete.
     */
    private CharsetDecoder decoder;

    /**
     * Working buffer for decoded character data.
     * Reused to avoid allocation on every receive() call.
     * Only allocated when actual decoding begins (after declaration).
     */
    private CharBuffer charBuffer;

    /**
     * The scanner that consumes decoded characters.
     */
    private final Scanner scanner;

    /**
     * True when the most recent decodeAndTokenize() call left undecoded bytes
     * behind because they form an incomplete trailing multi-byte sequence
     * (CharsetDecoder returned UNDERFLOW with input still remaining). Checked
     * in {@link #close()}: if still true when the caller signals there is no
     * more data coming, the stream ended mid-character, which is reported as
     * a fatal error rather than left to hang - decodeAndTokenize()'s loop
     * cannot make progress on the same incomplete bytes no matter how many
     * more times it's called, so it deliberately does not keep retrying.
     */
    private boolean hasPendingIncompleteBytes = false;

    // ===== State =====
    
    /**
     * Decoder state for tracking processing phase.
     */
    private enum State {
        INIT,           // Initial state, checking for BOM
        SEEN_BOM,       // BOM detected (or no BOM), ready to check for XMLDecl/TextDecl
        CONTENT,        // Processing content with main scanner
        CLOSED          // Decoder has been closed, cannot receive more data
    }
    
    private State state = State.INIT;
    
    
    /**
     * XMLDecl or TextDecl parser.
     */
    private final DeclParser declParser;
    
    /**
     * Byte position at start of document (after BOM, if any).
     */
    private int startDecl;
    
    /**
     * The last character read for line-ending normalization.
     */
    private char lastChar = '\u0000';
    
    // ===== Constructor =====
    
    /**
     * Creates a new external entity decoder.
     *
     * @param scanner the {@link Scanner} to receive decoded characters
     * @param publicId public identifier for this entity (may be null)
     * @param systemId system identifier for this entity (may be null)
     * @param isExternalEntity true if this is an external parsed entity, false for document entity
     */
    public ExternalEntityDecoder(Scanner scanner, String publicId, String systemId,
            boolean isExternalEntity) {
        this.scanner = Objects.requireNonNull(scanner);
        this.isExternalEntity = isExternalEntity;
        declParser = isExternalEntity ? new TextDeclParser() : new XMLDeclParser();
    }
    
    // ===== Accessors =====
    
    /**
     * Returns whether this decoder is for an external parsed entity.
     */
    public boolean isExternalEntity() {
        return isExternalEntity;
    }
    
    // ===== Public API =====
    
    /**
     * Receives a buffer of bytes to decode and tokenize.
     * 
     * <p>The buffer must be in read mode on entry. On return, the buffer's position
     * will be set to the first unconsumed byte.
     * 
     * @param data the byte buffer to process (position will be updated)
     * @throws SAXException if a parsing error occurs
     */
    public void receive(ByteBuffer data) throws SAXException {
        if (state == State.CLOSED) {
            throw new IllegalStateException("Decoder is closed");
        }
        
        if (!data.hasRemaining()) {
            return;
        }
        
        // Process based on state
        switch (state) {
            case INIT:
                // Detect BOM
                if (!parseBOM(data)) {
                    return; // Need more bytes
                }
                // Fall through to SEEN_BOM
                
            case SEEN_BOM:
                // Parse XMLDecl/TextDecl directly from bytes
                if (!parseDeclaration(data)) {
                    return; // Need more data
                }
                // Declaration parsed (or not present), now in CONTENT state
                // Fall through to CONTENT
                
            case CONTENT:
                // Decode and send to tokenizer
                decodeAndTokenize(data);
                break;
                
            case CLOSED:
                throw new IllegalStateException("Decoder is closed");
        }
    }
    
    /**
     * Closes the decoder and flushes any remaining data to the tokenizer.
     *
     * @throws SAXException if the input ended with an incomplete trailing
     *         multi-byte character sequence
     */
    public void close() throws SAXException {
        if (state == State.CLOSED) {
            return;
        }
        if (hasPendingIncompleteBytes) {
            // The caller has signaled there is no more data, but the last
            // decode attempt still had undecoded bytes forming part of a
            // multi-byte character - the stream ended mid-character.
            throw scanner.fatalError(
                "Unexpected end of input: incomplete byte sequence in encoding "
                + (charset != null ? charset.name() : "unknown") + " at end of stream");
        }
        scanner.close();
        state = State.CLOSED;
    }
    
    /**
     * Resets the decoder to its initial state for reuse.
     * Preserves allocated buffers to avoid reallocation.
     */
    public void reset() {
        state = State.INIT;
        // Keep previous charset/decoder for reuse if next document uses same encoding
        previousCharset = charset;
        charset = null;
        bom = BOM.NONE;
        xml11 = false;
        if (decoder != null) {
            decoder.reset();
        }
        // Keep charBuffer allocated - just clear it
        if (charBuffer != null) {
            charBuffer.clear();
        }
        lastChar = '\u0000';
    }
    
    /**
     * Sets the initial charset hint (e.g., from HTTP headers).
     * Must be called before receive() if the charset is known externally.
     */
    public void setInitialCharset(Charset initialCharset) {
        if (state != State.INIT) {
            throw new IllegalStateException("Cannot set initial charset after decoding has started");
        }
        if (initialCharset != null) {
            String name = initialCharset.name();
            if (name.equals("UTF-16LE") || initialCharset.equals(StandardCharsets.UTF_16LE)) {
                bom = BOM.UTF16LE;
            } else if (name.equals("UTF-16BE") || initialCharset.equals(StandardCharsets.UTF_16BE)) {
                bom = BOM.UTF16BE;
            } else if (name.equals("UTF-32LE")) {
                bom = BOM.UTF32LE;
            } else if (name.equals("UTF-32BE")) {
                bom = BOM.UTF32BE;
            } else {
                bom = BOM.NONE;
            }
        }
    }
    
    // ===== BOM Detection =====
    
    /**
     * Detects and consumes a BOM if present, setting the byte encoding accordingly.
     *
     * @return true if BOM detection is complete, false if more data is needed
     */
    private boolean parseBOM(ByteBuffer data) throws SAXException {
        int startPos = data.position();

        if (data.remaining() < 2) {
            return false;
        }

        int b0 = data.get() & 0xFF;
        int b1 = data.get() & 0xFF;

        if (b0 == 0xFE && b1 == 0xFF) {
            bom = BOM.UTF16BE;
            startDecl = data.position();
        } else if (b0 == 0xFF && b1 == 0xFE) {
            if (data.remaining() < 2) {
                data.position(startPos);
                return false;
            }
            int b2 = data.get() & 0xFF;
            int b3 = data.get() & 0xFF;
            if (b2 == 0x00 && b3 == 0x00) {
                bom = BOM.UTF32LE;
            } else {
                bom = BOM.UTF16LE;
                data.position(startPos + 2);
            }
            startDecl = data.position();
        } else if (b0 == 0x00 && b1 == 0x00) {
            if (data.remaining() < 2) {
                data.position(startPos);
                return false;
            }
            int b2 = data.get() & 0xFF;
            int b3 = data.get() & 0xFF;
            if (b2 == 0xFE && b3 == 0xFF) {
                bom = BOM.UTF32BE;
                startDecl = data.position();
            } else {
                data.position(startPos);
                startDecl = startPos;
            }
        } else if (b0 == 0xEF && b1 == 0xBB) {
            if (!data.hasRemaining()) {
                data.position(startPos);
                return false;
            }
            int b2 = data.get() & 0xFF;
            if (b2 == 0xBF) {
                bom = BOM.UTF8;
                startDecl = data.position();
            } else {
                data.position(startPos);
                startDecl = startPos;
            }
        } else {
            data.position(startPos);
            startDecl = startPos;
        }

        declParser.setBOM(bom);

        state = State.SEEN_BOM;
        return true;
    }
    
    // ===== Declaration Parsing =====
    
    /**
     * Parses the XML/text declaration directly from bytes.
     * 
     * @return true if declaration parsing is complete, false if more data is needed
     */
    private boolean parseDeclaration(ByteBuffer data) throws SAXException {
        int savedPos = data.position();
        
        try {
            ReadResult result = declParser.receive(data);
            
            switch (result) {
                case UNDERFLOW:
                    // Need more data, position already restored by declParser
                    data.position(savedPos);
                    return false;
                    
                case FAILURE:
                    // No declaration present, position restored by declParser
                    // Use default charset (UTF-8 or BOM-indicated charset)
                    setupCharsetDecoder(null);
                    state = State.CONTENT;
                    return true;
                    
                case OK:
                    // Declaration parsed successfully
                    String declEncoding = declParser.getEncoding();
                    String declVersion = declParser.getVersion();
                    Boolean declStandalone = declParser.getStandalone();
                    
                    // Handle version
                    if (declVersion != null) {
                        boolean entityXml11 = "1.1".equals(declVersion);

                        if (!isExternalEntity) {
                            // Main document - sets the processor mode
                            xml11 = entityXml11;
                            scanner.setXml11(xml11);
                        } else {
                            // External entity TextDecl version - Scanner
                            // fetches external entities itself today, so this
                            // branch is unused by the production Parser path;
                            // still apply the TextDecl's version to the scanner.
                            xml11 = entityXml11;
                            scanner.setXml11(xml11);
                        }
                    }

                    // Handle standalone (document entity only - a TextDecl's
                    // grammar has no SDDecl production at all, so declStandalone
                    // is always null when isExternalEntity is true)
                    if (declStandalone != null) {
                        scanner.setStandalone(declStandalone.booleanValue());
                    }

                    // Setup charset decoder with declared encoding
                    setupCharsetDecoder(declEncoding);


                    state = State.CONTENT;
                    return true;
            }
        } catch (IllegalArgumentException e) {
            // Non-ASCII byte in declaration
            throw scanner.fatalError(e.getMessage());
        }
        
        return false;
    }
    
    /**
     * Sets up the CharsetDecoder based on the declared encoding.
     * Validates BOM/encoding compatibility.
     */
    private void setupCharsetDecoder(String declEncoding) throws SAXException {
        if (declEncoding != null) {
            // Declared encoding specified
            try {
                charset = Charset.forName(declEncoding);
            } catch (IllegalCharsetNameException | UnsupportedCharsetException e) {
                throw scanner.fatalError("Invalid or unsupported encoding: " + declEncoding);
            }
            
            // Validate BOM/encoding compatibility (only if BOM was present)
            if (bom.requiresCharsetValidation()) {
                validateBOMEncodingCompatibility(declEncoding);

                // A BOM was already detected and consumed by parseBOM(), so it will
                // never reach the CharsetDecoder. A generic "UTF-16"/"UTF-32" declared
                // encoding (no explicit LE/BE suffix) is Java's BOM-sensing charset,
                // which defaults to big-endian when it doesn't see a BOM of its own -
                // silently misdecoding a little-endian document even though Gonzalez
                // already correctly determined the byte order from the BOM. Trust the
                // already-detected BOM in that case instead of re-resolving the generic
                // name.
                String normalized = declEncoding.toUpperCase().replace("-", "").replace("_", "");
                if (!normalized.endsWith("LE") && !normalized.endsWith("BE")) {
                    charset = bom.defaultCharset;
                }
            }

        } else {
            // No declared encoding - use BOM-indicated charset or default to UTF-8
            charset = bom.defaultCharset;
        }
        scanner.setEncoding(charset.name());
        
        // Reuse existing decoder if charset unchanged, otherwise create new one
        if (decoder == null || !charset.equals(previousCharset)) {
            decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        } else {
            decoder.reset();
        }

        // Bytes below 0x80 mean the same thing (identical code point) in each of
        // these charsets, so the ASCII fast path below can widen them directly
        // without going through the CharsetDecoder state machine at all.
        asciiFastPathEligible = charset.equals(StandardCharsets.UTF_8)
            || charset.equals(StandardCharsets.US_ASCII)
            || charset.equals(StandardCharsets.ISO_8859_1);
    }
    
    /**
     * Validates that the declared encoding is compatible with the BOM.
     */
    private void validateBOMEncodingCompatibility(String declEncoding) throws SAXException {
        String normalized = declEncoding.toUpperCase().replace("-", "").replace("_", "");

        switch (bom) {
            case UTF16BE:
                if (!normalized.contains("UTF16")) {
                    throw scanner.fatalError(
                        "Encoding '" + declEncoding + "' is incompatible with UTF-16 BE BOM");
                }
                break;

            case UTF16LE:
                if (!normalized.contains("UTF16")) {
                    throw scanner.fatalError(
                        "Encoding '" + declEncoding + "' is incompatible with UTF-16 LE BOM");
                }
                break;

            case UTF32BE:
                if (!normalized.contains("UTF32")) {
                    throw scanner.fatalError(
                        "Encoding '" + declEncoding + "' is incompatible with UTF-32 BE BOM");
                }
                break;

            case UTF32LE:
                if (!normalized.contains("UTF32")) {
                    throw scanner.fatalError(
                        "Encoding '" + declEncoding + "' is incompatible with UTF-32 LE BOM");
                }
                break;

            case UTF8:
                if (normalized.startsWith("UTF16") || normalized.startsWith("UTF32")) {
                    throw scanner.fatalError(
                        "Encoding '" + declEncoding + "' is incompatible with UTF-8 BOM");
                }
                break;

            case NONE:
                break;
        }
    }
    
    // ===== Content Decoding =====
    
    /**
     * Maximum CharBuffer size. Processing is done incrementally to avoid
     * allocating a buffer sized for the entire input.
     */
    private static final int MAX_CHAR_BUFFER = 32768;
    
    /**
     * Decodes bytes to characters and sends to tokenizer.
     * Processes incrementally to avoid large buffer allocations.
     */
    private void decodeAndTokenize(ByteBuffer data) throws SAXException {
        if (!data.hasRemaining()) {
            return;
        }
        
        // Allocate CharBuffer if needed (capped at MAX_CHAR_BUFFER)
        if (charBuffer == null) {
            charBuffer = CharBuffer.allocate(MAX_CHAR_BUFFER);
        }

        // One pass over the raw bytes up front to learn whether this call's
        // input can produce any '\r' at all: for the ASCII-transparent
        // charsets (UTF-8/US-ASCII/ISO-8859-1) a decoded U+000D can only
        // come from a literal 0x0D byte (UTF-8 continuation bytes are all
        // >= 0x80), so if none is present, normalizeLineEndings() can skip
        // its own per-chunk scan of every decoded char entirely. XML 1.1
        // additionally normalizes NEL/LS, whose encoded bytes are ordinary
        // continuation-byte values that would false-positive constantly on
        // multibyte text, so the XML 1.1 case keeps the per-chunk char scan.
        // The SWAR long-stride scan reads 8 bytes per step, making this
        // cheaper than the per-char scan it replaces even when it doesn't
        // hit; absolute getLong() works identically for heap and direct
        // buffers and never disturbs the buffer's position.
        boolean crFree = !xml11 && asciiFastPathEligible && !containsCarriageReturnByte(data);

        // Process in chunks - decode, tokenize, compact, repeat. No manual
        // ASCII fast path here: the JDK's own decoders (UTF-8 in
        // particular) bulk-decode ASCII runs via the intrinsified,
        // vectorized JavaLangAccess.decodeASCII, which measured
        // consistently faster than a scalar byte-by-byte widening loop in
        // front of it.
        while (data.hasRemaining()) {
            // Decode into charBuffer (from current position to limit)
            CoderResult result = decoder.decode(data, charBuffer, false);

            // Check for decoding errors
            if (result.isError()) {
                if (result.isMalformed()) {
                    throw scanner.fatalError("Malformed byte sequence in encoding " + charset.name() +
                        " (length: " + result.length() + ")");
                } else if (result.isUnmappable()) {
                    throw scanner.fatalError("Unmappable byte sequence in encoding " + charset.name() +
                        " (length: " + result.length() + ")");
                }
            }

            // Normalize line endings
            normalizeLineEndings(crFree);

            // Flip to read mode before passing to tokenizer
            charBuffer.flip();
            
            // Pass to tokenizer
            scanner.receive(charBuffer);
            
            // Compact to preserve any unconsumed data (underflow)
            charBuffer.compact();

            // OVERFLOW: charBuffer was full - loop again to decode more into the
            // freed space. UNDERFLOW: decoder cannot make progress on the
            // remaining input without more bytes - stop now rather than looping
            // forever on the same undecodable trailing sequence (data.hasRemaining()
            // can legitimately still be true here: an incomplete multi-byte
            // sequence at the end of this chunk, which the caller may complete
            // with a later receive() call, or which close() will report as a
            // truncation error if no more data ever arrives).
            if (result.isUnderflow()) {
                break;
            }
        }

        hasPendingIncompleteBytes = data.hasRemaining();
    }

    /**
     * True if any byte in {@code data}'s remaining range is 0x0D (CR).
     * Long-stride SWAR scan - 8 bytes per step via the classic
     * zero-byte-in-a-word test - with a plain byte loop for the tail.
     * Reads via absolute accessors only; {@code data}'s position and limit
     * are untouched. Byte order is irrelevant: the test only asks whether
     * any of the 8 lanes is zero after XORing with the CR pattern.
     */
    private static boolean containsCarriageReturnByte(ByteBuffer data) {
        int i = data.position();
        int limit = data.limit();
        for (int longEnd = limit - 7; i < longEnd; i += 8) {
            long v = data.getLong(i) ^ 0x0D0D0D0D0D0D0D0DL;
            if (((v - 0x0101010101010101L) & ~v & 0x8080808080808080L) != 0L) {
                return true;
            }
        }
        for (; i < limit; i++) {
            if (data.get(i) == 0x0D) {
                return true;
            }
        }
        return false;
    }

    /**
     * Normalizes line endings in the character buffer according to XML spec.
     * 
     * <p>XML line ending normalization rules:
     * <ul>
     *   <li>CR (\r) alone -> LF (\n)</li>
     *   <li>CR LF (\r\n) -> LF (\n) - the LF is removed</li>
     *   <li>LF (\n) alone -> LF (unchanged)</li>
     *   <li>XML 1.1 only: NEL (\u0085) -> LF</li>
     *   <li>XML 1.1 only: LS (\u2028) -> LF</li>
     * </ul>
     * 
     * <p>This implementation uses a single-pass O(n) algorithm with separate
     * read and write positions, avoiding the O(n^2) cost of shifting data
     * for each CRLF encountered.
     *
     * @param crFree true when the caller has already proven (by byte-level
     *         scan - see {@link #containsCarriageReturnByte}) that no '\r'
     *         can be present in this chunk and XML 1.1's NEL/LS rules do
     *         not apply, so the per-char scan below is skipped. {@link
     *         #lastChar} still matters: a '\r' that ended the previous
     *         receive() call must swallow a leading '\n' in this one.
     */
    private void normalizeLineEndings(boolean crFree) {
        int end = charBuffer.position();
        if (end == 0) {
            return;
        }

        char[] array = charBuffer.array();

        if (crFree && lastChar != '\r') {
            lastChar = array[end - 1];
            return;
        }

        // Fast scan: check if normalization is needed at all
        boolean needsNormalization = (lastChar == '\r');
        if (!needsNormalization) {
            for (int i = 0; i < end; i++) {
                char c = array[i];
                if (c == '\r') {
                    needsNormalization = true;
                    break;
                }
                if (xml11 && (c == '\u0085' || c == '\u2028')) {
                    needsNormalization = true;
                    break;
                }
            }
        }

        if (!needsNormalization) {
            lastChar = array[end - 1];
            return;
        }

        int writePos = 0;

        for (int readPos = 0; readPos < end; readPos++) {
            char c = array[readPos];

            if (c == '\r') {
                array[writePos++] = '\n';
            } else if (c == '\n' && lastChar == '\r') {
                // CR LF pair: skip the LF (CR was already converted)
            } else if (xml11 && (c == '\u0085' || c == '\u2028')) {
                array[writePos++] = '\n';
            } else {
                array[writePos++] = c;
            }
            lastChar = c;
        }

        charBuffer.position(writePos);
    }

}

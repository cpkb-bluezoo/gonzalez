/*
 * DeclParser.java
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

/**
 * Parser for the XML declaration of a document entity and the text
 * declaration of an external parsed entity. It extracts the version,
 * encoding and standalone information before the main scanner starts.
 *
 * <pre>
 * XMLDecl  ::= '&lt;?xml' VersionInfo EncodingDecl? SDDecl? S? '?&gt;'
 * TextDecl ::= '&lt;?xml' VersionInfo? EncodingDecl S? '?&gt;'
 * </pre>
 *
 * <p>The parser operates directly on the ByteBuffer, reading 7-bit ASCII
 * characters according to the byte encoding scheme the {@link BOM}
 * indicates. A declaration contains only such characters, so no
 * CharsetDecoder is needed until it has been read and the encoding is
 * known.
 *
 * <p><b>It is resumable.</b> {@link #receive(ByteBuffer)} consumes every
 * complete character it is given and keeps its place between calls, so
 * the caller's buffer never has to hold the whole declaration and the
 * amount left unconsumed is at most one incomplete character. The memory
 * it uses is bounded too: whitespace is not kept, and an attribute value
 * is kept only up to {@link #MAX_VALUE_LENGTH} characters.
 *
 * <p>The input may turn out not to be a declaration at all: a processing
 * instruction such as {@code <?xml-stylesheet ...?>}, any other markup,
 * or something malformed. That is {@link ReadResult#FAILURE}, and the
 * bytes belong to the scanner. Those consumed by this call are handed
 * back by restoring the buffer's position. Those consumed by earlier
 * calls are gone from the caller's buffer, so {@link #getReplay()} gives
 * the characters to pass to the scanner in their place.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class DeclParser {

    /**
     * The most characters of an attribute value that are kept. A version
     * number may legitimately be longer ({@code 1.} and any number of
     * digits), in which case the rest is checked and discarded; a longer
     * encoding name is reported by {@link #isEncodingTooLong()}.
     */
    static final int MAX_VALUE_LENGTH = 256;

    private static final String PROLOG = "<?xml";

    private static final int VERSION = 0;
    private static final int ENCODING = 1;
    private static final int STANDALONE = 2;
    private static final String[] NAMES = { "version", "encoding", "standalone" };

    // ===== States =====

    /** Matching {@link #PROLOG}; {@link #matched} characters seen. */
    private static final int S_PROLOG = 0;
    /** After {@link #PROLOG}: the whitespace that makes it a declaration. */
    private static final int S_SPACE = 1;
    /** Between attributes: whitespace, then an attribute name or '?'. */
    private static final int S_BEFORE_NAME = 2;
    /** Matching the name of {@link #attribute}. */
    private static final int S_NAME = 3;
    /** After a name: optional whitespace, then '='. */
    private static final int S_BEFORE_EQ = 4;
    /** After '=': optional whitespace, then the opening quote. */
    private static final int S_BEFORE_QUOTE = 5;
    /** In a value, up to the closing {@link #quote}. */
    private static final int S_VALUE = 6;
    /** After '?': the closing '&gt;'. */
    private static final int S_END = 7;

    /** True for a text declaration, false for an XML declaration. */
    private final boolean textDecl;

    /** The BOM detected at start of document (determines byte encoding) */
    private BOM bom = BOM.NONE;

    private int state = S_PROLOG;

    /** Characters matched so far of {@link #PROLOG} or an attribute name. */
    private int matched;

    /** The attribute being read: {@link #VERSION}, {@link #ENCODING} or
     *  {@link #STANDALONE}. */
    private int attribute;

    /** One more than the last attribute completed; zero if none. The
     *  attributes come in a fixed order, so this is all that is needed to
     *  know which may follow. */
    private int stage;

    /** Whether whitespace has been seen since the last attribute. */
    private boolean sawSpace;

    /** The quote character that opened the value being read. */
    private int quote;

    /** True once whitespace has followed {@link #PROLOG}. From here on
     *  the input is either a declaration or an error, because a
     *  processing instruction may not have the target {@code xml}. */
    private boolean committed;

    /** The whitespace character that followed {@link #PROLOG}. */
    private char committedSpace;

    /** The value being read, up to {@link #MAX_VALUE_LENGTH} characters. */
    private final StringBuilder value = new StringBuilder();

    private final String[] values = new String[NAMES.length];
    private boolean encodingTooLong;

    /** Line breaks within the declaration so far; see {@link #getLineBreaks()}. */
    private int lineBreaks;
    private int previousChar;

    /** See {@link #getReplay()}. */
    private String replay = "";
    private boolean replayRejected;

    /**
     * Creates a declaration parser.
     *
     * @param textDecl true to parse a text declaration (external parsed
     *        entity), false to parse an XML declaration (document entity)
     */
    DeclParser(boolean textDecl) {
        this.textDecl = textDecl;
    }

    /**
     * Sets the BOM for reading characters.
     * Must be called before receive() based on BOM detection.
     *
     * @param bom the detected BOM
     */
    void setBOM(BOM bom) {
        this.bom = bom;
    }

    /**
     * Returns this parser to its initial state for another entity.
     */
    void reset() {
        state = S_PROLOG;
        matched = 0;
        stage = 0;
        sawSpace = false;
        committed = false;
        value.setLength(0);
        for (int i = 0; i < values.length; i++) {
            values[i] = null;
        }
        encodingTooLong = false;
        lineBreaks = 0;
        previousChar = 0;
        replay = "";
        replayRejected = false;
    }

    /**
     * Returns the number of line breaks inside the declaration (a carriage
     * return, a line feed, or both together, being one), which are not
     * passed on to the scanner but still count as lines of the document.
     * Meaningful once {@link #receive} has returned {@link ReadResult#OK}.
     */
    int getLineBreaks() {
        return lineBreaks;
    }

    /**
     * Parses as much of the declaration as the buffer holds. Call again
     * with more data while the result is {@link ReadResult#UNDERFLOW}.
     *
     * @param data the bytes to parse, continuing from where the previous
     *        call stopped
     * @return {@link ReadResult#OK} if the declaration is complete, with
     *         the buffer positioned after it;
     *         {@link ReadResult#FAILURE} if this is not a declaration,
     *         with the buffer's position as it was on entry (see
     *         {@link #getReplay()} for anything consumed before that);
     *         {@link ReadResult#UNDERFLOW} if more data is needed, with
     *         every complete character consumed
     */
    ReadResult receive(ByteBuffer data) {
        int startPos = data.position();
        // What earlier calls consumed, should this one have to give it back.
        boolean carriedCommitted = committed;
        int carriedProlog = (state == S_PROLOG) ? matched : PROLOG.length();

        while (true) {
            int c;
            try {
                c = bom.nextChar(data);
            } catch (IllegalArgumentException e) {
                // Not 7-bit ASCII, so not part of a declaration.
                return fail(data, startPos, carriedCommitted, carriedProlog);
            }
            if (c == -1) {
                return ReadResult.UNDERFLOW;
            }
            if (c == '\r' || (c == '\n' && previousChar != '\r')) {
                lineBreaks++;
            }
            previousChar = c;
            switch (state) {
                case S_PROLOG:
                    if (c != PROLOG.charAt(matched)) {
                        return fail(data, startPos, carriedCommitted, carriedProlog);
                    }
                    matched++;
                    if (matched == PROLOG.length()) {
                        state = S_SPACE;
                    }
                    break;

                case S_SPACE:
                    if (!isWhitespace(c)) {
                        // A longer target, such as xml-stylesheet.
                        return fail(data, startPos, carriedCommitted, carriedProlog);
                    }
                    committed = true;
                    committedSpace = (char) c;
                    sawSpace = true;
                    state = S_BEFORE_NAME;
                    break;

                case S_BEFORE_NAME:
                    if (isWhitespace(c)) {
                        sawSpace = true;
                    } else if (c == '?') {
                        if (!mayEnd()) {
                            return fail(data, startPos, carriedCommitted, carriedProlog);
                        }
                        state = S_END;
                    } else {
                        int next = sawSpace ? attributeStartingWith(c) : -1;
                        if (next < 0) {
                            return fail(data, startPos, carriedCommitted, carriedProlog);
                        }
                        attribute = next;
                        matched = 1;
                        state = S_NAME;
                    }
                    break;

                case S_NAME:
                    String name = NAMES[attribute];
                    if (c != name.charAt(matched)) {
                        return fail(data, startPos, carriedCommitted, carriedProlog);
                    }
                    matched++;
                    if (matched == name.length()) {
                        state = S_BEFORE_EQ;
                    }
                    break;

                case S_BEFORE_EQ:
                    if (isWhitespace(c)) {
                        break;
                    }
                    if (c != '=') {
                        return fail(data, startPos, carriedCommitted, carriedProlog);
                    }
                    state = S_BEFORE_QUOTE;
                    break;

                case S_BEFORE_QUOTE:
                    if (isWhitespace(c)) {
                        break;
                    }
                    if (c != '"' && c != '\'') {
                        return fail(data, startPos, carriedCommitted, carriedProlog);
                    }
                    quote = c;
                    value.setLength(0);
                    state = S_VALUE;
                    break;

                case S_VALUE:
                    if (c == quote) {
                        values[attribute] = value.toString();
                        stage = attribute + 1;
                        sawSpace = false;
                        state = S_BEFORE_NAME;
                    } else if (isInvalidInDeclValue(c)) {
                        // Mismatched quotes (version='1.0"), whitespace,
                        // or markup characters.
                        return fail(data, startPos, carriedCommitted, carriedProlog);
                    } else if (value.length() < MAX_VALUE_LENGTH) {
                        value.append((char) c);
                    } else if (attribute == VERSION) {
                        // Only digits can follow this many characters of
                        // a version number, and they change nothing.
                        if (c < '0' || c > '9') {
                            return fail(data, startPos, carriedCommitted, carriedProlog);
                        }
                    } else if (attribute == ENCODING) {
                        encodingTooLong = true;
                    } else {
                        // standalone is "yes" or "no".
                        return fail(data, startPos, carriedCommitted, carriedProlog);
                    }
                    break;

                case S_END:
                    if (c != '>' || !isValid()) {
                        return fail(data, startPos, carriedCommitted, carriedProlog);
                    }
                    return ReadResult.OK;

                default:
                    throw new IllegalStateException("Unknown state: " + state);
            }
        }
    }

    /**
     * Gives back what this call consumed, and works out what must stand
     * in for anything earlier calls consumed.
     */
    private ReadResult fail(ByteBuffer data, int startPos, boolean carriedCommitted, int carriedProlog) {
        data.position(startPos);
        if (carriedCommitted) {
            // The scanner rejects the reserved target as soon as it has
            // seen it, which is exactly the error this input deserves, and
            // it needs nothing beyond the whitespace to do so.
            replay = PROLOG + committedSpace;
            replayRejected = true;
        } else {
            replay = PROLOG.substring(0, carriedProlog);
            replayRejected = false;
        }
        return ReadResult.FAILURE;
    }

    /**
     * After {@link ReadResult#FAILURE}: the characters that earlier calls
     * to {@link #receive(ByteBuffer)} consumed and that the scanner must
     * now be given, ahead of the bytes still in the buffer. Empty if the
     * call that failed was the first to consume anything, which is the
     * usual case.
     *
     * @return the characters to pass on; never null
     */
    String getReplay() {
        return replay;
    }

    /**
     * After {@link ReadResult#FAILURE}: whether {@link #getReplay()} is
     * the start of a malformed declaration, which the scanner will
     * reject as soon as it sees it. If so the replay is all the scanner
     * needs, and is not followed by everything else that was consumed.
     *
     * @return true if the replay is necessarily an error
     */
    boolean isReplayRejected() {
        return replayRejected;
    }

    /** Whether the declaration may end here: after the last attribute it
     *  requires. */
    private boolean mayEnd() {
        return textDecl ? (stage == ENCODING + 1) : (stage >= VERSION + 1);
    }

    /**
     * Returns the attribute that may start here with the given character,
     * or -1 if none may. The attributes have distinct initials and a
     * fixed order, so the first character decides.
     */
    private int attributeStartingWith(int c) {
        switch (c) {
            case 'v':
                // version comes first, when it comes at all
                return (stage == 0) ? VERSION : -1;
            case 'e':
                // encoding follows version, which a text declaration may omit
                return (stage == VERSION + 1 || (textDecl && stage == 0)) ? ENCODING : -1;
            case 's':
                // a text declaration has no standalone
                return (!textDecl && (stage == VERSION + 1 || stage == ENCODING + 1)) ? STANDALONE : -1;
            default:
                return -1;
        }
    }

    /** Checks the values of a complete declaration. */
    private boolean isValid() {
        String version = values[VERSION];
        if (version != null) {
            // [0-9]+\.[0-9]+, and only 1.x versions are XML versions
            if (!isValidVersionNumber(version) || !version.startsWith("1.")) {
                return false;
            }
        }
        // XML 1.0 section 2.9: exactly "yes" or "no"
        String standalone = values[STANDALONE];
        return standalone == null || standalone.equals("yes") || standalone.equals("no");
    }

    /**
     * Checks if a character is invalid inside an XML/text declaration attribute value.
     * Declaration attribute values (version, encoding, standalone) cannot contain
     * quotes, whitespace or markup delimiters.
     */
    private static boolean isInvalidInDeclValue(int c) {
        return c == '"' || c == '\'' || isWhitespace(c) || c == '<' || c == '>' || c == '?';
    }

    /**
     * Checks if a character is XML whitespace.
     */
    private static boolean isWhitespace(int c) {
        return c == ' ' || c == '\t' || c == '\r' || c == '\n';
    }

    /**
     * Returns the character encoding specified in the declaration. If
     * {@link #isEncodingTooLong()}, this is only the start of it.
     */
    String getEncoding() {
        return values[ENCODING];
    }

    /**
     * Returns whether the encoding name was longer than
     * {@link #MAX_VALUE_LENGTH} characters, which no supported encoding's
     * name is.
     */
    boolean isEncodingTooLong() {
        return encodingTooLong;
    }

    /**
     * Returns the XML version specified in the declaration. A version
     * longer than {@link #MAX_VALUE_LENGTH} characters is cut short there;
     * what was cut is known to be digits.
     */
    String getVersion() {
        return values[VERSION];
    }

    /**
     * Returns the standalone flag specified in the declaration.
     */
    Boolean getStandalone() {
        String val = values[STANDALONE];
        return (val == null) ? null : Boolean.valueOf("yes".equals(val));
    }

    /**
     * Validates that a version number matches the production: [0-9]+\.[0-9]+
     * Examples: "1.0", "1.1", "2.0" are valid; "1", "1.", "1.0a", "_#1.0" are invalid.
     *
     * @param version the version string to validate
     * @return true if the version matches the required pattern
     */
    static boolean isValidVersionNumber(String version) {
        if (version == null || version.isEmpty()) {
            return false;
        }

        int dotPos = version.indexOf('.');
        if (dotPos <= 0 || dotPos >= version.length() - 1) {
            return false; // No dot, or dot at start/end
        }

        // Check that all characters before dot are digits
        for (int i = 0; i < dotPos; i++) {
            char c = version.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }

        // Check that all characters after dot are digits
        for (int i = dotPos + 1; i < version.length(); i++) {
            char c = version.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }

        return true;
    }

}

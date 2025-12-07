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

import java.nio.CharBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * Base class for parsers that handle XML declarations (XMLDecl) and text declarations (TextDecl).
 * These parsers extract version, encoding, and standalone information before the main tokenizer starts.
 * 
 * <p>The parser operates directly on the character buffer provided by ExternalEntityDecoder,
 * using mark/reset to handle failures gracefully. The buffer position after a successful parse
 * indicates the end of the declaration.
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
abstract class DeclParser {
    
    /** Map of attribute names to values extracted from the declaration */
    protected Map<String,String> attributes = new HashMap<>();

    /**
     * Parses the declaration from incoming character data.
     * Can be called multiple times if more data is needed.
     * 
     * <p>The parser should use {@link CharBuffer#mark()} at the start and
     * {@link CharBuffer#reset()} on failure to restore the buffer position.
     * On success, the buffer position should be left at the end of the declaration.
     * 
     * @param data the character buffer containing data to parse (positioned at start of potential declaration)
     * @return {@link ReadResult#OK} if declaration was successfully parsed,
     *         {@link ReadResult#FAILURE} if no declaration present,
     *         {@link ReadResult#UNDERFLOW} if more data is needed
     */
    abstract ReadResult receive(CharBuffer data);
    
    /**
     * Try to read the specified chars.
     * @param test the characters to read
     */
    protected ReadResult tryRead(CharBuffer data, String test) {
        // The caller uses mark/reset, so we can consume characters here
        // If we return FAILURE or UNDERFLOW, the caller will reset to the mark
        for (int i = 0; i < test.length(); i++) {
            char c = test.charAt(i);
            if (!data.hasRemaining()) {
                return ReadResult.UNDERFLOW;
            }
            if (data.get() != c) {
                return ReadResult.FAILURE;
            }
        }
        return ReadResult.OK;
    }

    /**
     * Read an entire attribute in an XMLDecl or TextDecl.
     * Saves position at start and restores it on failure (since mark/reset is already used in receive()).
     * @param attributeName the name of the attribute we expect
     */
    protected ReadResult tryReadAttribute(CharBuffer data, String attributeName) {
        // Save position at start of attribute attempt
        int savedPos = data.position();
        ReadResult ret = requireWhitespace(data);
        switch (ret) {
            case UNDERFLOW:
                return ret; // Can't restore on UNDERFLOW - need more data
            case FAILURE:
                data.position(savedPos); // No whitespace - restore position
                return ret;
        }
        ret = tryRead(data, attributeName);
        switch (ret) {
            case UNDERFLOW:
                return ret; // Can't restore on UNDERFLOW
            case FAILURE:
                data.position(savedPos); // Attribute name doesn't match - restore position
                return ret;
        }
        ignoreWhitespace(data);
        ret = tryRead(data, "=");
        switch (ret) {
            case UNDERFLOW:
                return ret;
            case FAILURE:
                data.position(savedPos); // No '=' - restore position
                return ret;
        }
        ignoreWhitespace(data);
        if (!data.hasRemaining()) {
            return ReadResult.UNDERFLOW;
        }
        char quoteChar = data.get();
        if (quoteChar != '"' && quoteChar != '\'') {
            data.position(savedPos); // No quote - restore position
            return ReadResult.FAILURE;
        }
        StringBuilder buf = new StringBuilder();
        while (data.hasRemaining()) {
            char c = data.get();
            if (c == quoteChar) {
                // end of attribute value
                attributes.put(attributeName, buf.toString());
                return ReadResult.OK;
            } else {
                buf.append(c);
            }
        }
        return ReadResult.UNDERFLOW;
    }

    /**
     * Read as much whitespace as possible.
     * Must read at least some whitespace.
     */
    protected ReadResult requireWhitespace(CharBuffer data) {
        if (!data.hasRemaining()) {
            return ReadResult.UNDERFLOW;
        }
        // Peek at first character
        char c = data.get(data.position());
        if (CharClass.classify(c, null, null, false) != CharClass.WHITESPACE) {
            return ReadResult.FAILURE;
        }
        data.get(); // consume first whitespace
        // Consume as much whitespace as possible
        while (data.hasRemaining()) {
            c = data.get(data.position()); // peek
            if (CharClass.classify(c, null, null, false) != CharClass.WHITESPACE) {
                return ReadResult.OK;
            }
            data.get(); // consume
        }
        return ReadResult.OK;
    }

    /**
     * Ignore any whitespace.
     */
    protected void ignoreWhitespace(CharBuffer data) {
        while (data.hasRemaining()) {
            char c = data.get(data.position()); // peek
            if (CharClass.classify(c, null, null, false) != CharClass.WHITESPACE) {
                // end of whitespace
                return;
            }
            data.get(); // consume
        }
    }
    
    /**
     * Returns the character encoding specified in the declaration.
     */
    public String getEncoding() {
        return attributes.get("encoding");
    }
    
    /**
     * Returns the XML version specified in the declaration.
     */
    public String getVersion() {
        return attributes.get("version");
    }
    
    /**
     * Returns the standalone flag specified in the declaration.
     */
    public Boolean getStandalone() {
        String val = attributes.get("standalone");
        return (val == null) ? null : Boolean.valueOf("yes".equals(val));
    }
    
    /**
     * Validates that a version number matches the production: [0-9]+\.[0-9]+
     * Examples: "1.0", "1.1", "2.0" are valid; "1", "1.", "1.0a", "_#1.0" are invalid.
     * 
     * @param version the version string to validate
     * @return true if the version matches the required pattern
     */
    protected static boolean isValidVersionNumber(String version) {
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


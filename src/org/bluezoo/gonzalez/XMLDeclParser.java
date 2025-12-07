/*
 * XMLDeclParser.java
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

/**
 * Specialized parser for XML declarations (document entity).
 * 
 * <p>Grammar: XMLDecl ::= '<?xml' VersionInfo EncodingDecl? SDDecl? S? '?>'
 * 
 * <p>This parser:
 * <ul>
 * <li>Detects presence of XMLDecl</li>
 * <li>Extracts version, encoding, and standalone attributes</li>
 * <li>Operates directly on the character buffer from ExternalEntityDecoder</li>
 * <li>Uses mark/reset to handle failures gracefully</li>
 * </ul>
 * 
 * <p>The version attribute is required. Encoding and standalone are optional.
 * On success, the buffer position is left at the end of the declaration (after "?>").
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class XMLDeclParser extends DeclParser {

    @Override
    public ReadResult receive(CharBuffer data) {
        attributes.clear();

        // Parse
        data.mark();
        switch (tryRead(data, "<?xml")) {
            case FAILURE:
                data.reset();
                return ReadResult.FAILURE;
            case UNDERFLOW:
                return ReadResult.UNDERFLOW;
        }
        switch (tryReadAttribute(data, "version")) {
            case FAILURE:
                data.reset();
                return ReadResult.FAILURE;
            case UNDERFLOW:
                return ReadResult.UNDERFLOW;
        }
        switch (tryReadAttribute(data, "encoding")) {
            case FAILURE:
                // encoding is OPTIONAL
                break;
            case UNDERFLOW:
                return ReadResult.UNDERFLOW;
        }
        switch (tryReadAttribute(data, "standalone")) {
            case FAILURE:
                // standalone is OPTIONAL
                break;
            case UNDERFLOW:
                return ReadResult.UNDERFLOW;
        }
        ignoreWhitespace(data);
        switch (tryRead(data, "?>")) {
            case FAILURE:
                data.reset();
                return ReadResult.FAILURE;
            case UNDERFLOW:
                return ReadResult.UNDERFLOW;
        }
        String version = getVersion();
        if (version == null) {
            // version is required
            data.reset();
            return ReadResult.FAILURE;
        }
        // Validate version matches pattern: [0-9]+\.[0-9]+
        if (!isValidVersionNumber(version)) {
            data.reset();
            return ReadResult.FAILURE; // Invalid version format
        }
        // Validate version is 1.x (XML versions must be 1.0, 1.1, etc.)
        if (!version.startsWith("1.")) {
            data.reset();
            return ReadResult.FAILURE; // Only 1.x versions are valid XML versions
        }
        
        // Validate standalone attribute value if present
        // XML 1.0 Section 2.9: standalone must be exactly "yes" or "no" (case-sensitive)
        String standalone = attributes.get("standalone");
        if (standalone != null && !standalone.equals("yes") && !standalone.equals("no")) {
            data.reset();
            return ReadResult.FAILURE; // Invalid standalone value (must be "yes" or "no")
        }
        
        return ReadResult.OK;
    }

}
 
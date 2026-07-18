/*
 * XmlDeclUtil.java
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

import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;

import org.xml.sax.SAXException;

/**
 * Byte-to-char decoding (BOM detection, then declared-encoding sniffing) and
 * XML/text declaration handling, shared between {@link ScannerXMLReader}
 * (the main document) and {@link Scanner}'s external entity/DTD subset
 * fetching (see {@link Scanner}'s "external entity/DTD fetching" section) -
 * factored out once the same logic was needed in both places. This is a
 * simplified, one-shot (not streaming) version of what {@code
 * ExternalEntityDecoder} does for the old pipeline; see {@code
 * ScannerXMLReader}'s class Javadoc for why a separate, simpler
 * implementation was written rather than reusing that class directly.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class XmlDeclUtil {

    private XmlDeclUtil() {
    }

    /**
     * Decodes {@code bytes} to characters: BOM detection first (UTF-8,
     * UTF-16 BE/LE, UTF-32 BE/LE), then - if no BOM - the {@code encoding}
     * pseudo-attribute sniffed directly from the XML/text declaration's own
     * bytes (always plain ASCII by spec, regardless of the document's actual
     * encoding, so a raw Latin-1-style byte-to-char widening is always safe
     * for just that sniff), then {@code encodingHint} (e.g. an {@code
     * InputSource}'s own encoding, or an HTTP Content-Type charset), then
     * UTF-8 as the final default - the same precedence order {@code
     * ExternalEntityDecoder} uses for the old pipeline.
     */
    static char[] decodeBytes(byte[] bytes, String encodingHint) {
        Charset charset;
        int bomLength;

        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB
                && (bytes[2] & 0xFF) == 0xBF) {
            charset = StandardCharsets.UTF_8;
            bomLength = 3;
        } else if (bytes.length >= 4 && bytes[0] == 0x00 && bytes[1] == 0x00 && (bytes[2] & 0xFF) == 0xFE
                && (bytes[3] & 0xFF) == 0xFF) {
            charset = charsetOrNull("UTF-32BE");
            bomLength = 4;
        } else if (bytes.length >= 4 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xFE
                && bytes[2] == 0x00 && bytes[3] == 0x00) {
            charset = charsetOrNull("UTF-32LE");
            bomLength = 4;
        } else if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFE && (bytes[1] & 0xFF) == 0xFF) {
            charset = StandardCharsets.UTF_16BE;
            bomLength = 2;
        } else if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xFE) {
            charset = StandardCharsets.UTF_16LE;
            bomLength = 2;
        } else {
            charset = null;
            bomLength = 0;
        }

        if (charset == null) {
            String declared = sniffDeclaredEncoding(bytes);
            charset = declared != null ? charsetOrNull(declared) : null;
            if (charset == null && encodingHint != null) {
                charset = charsetOrNull(encodingHint);
            }
            if (charset == null) {
                charset = StandardCharsets.UTF_8;
            }
        }

        return new String(bytes, bomLength, bytes.length - bomLength, charset).toCharArray();
    }

    private static Charset charsetOrNull(String name) {
        try {
            return Charset.forName(name);
        } catch (IllegalCharsetNameException | UnsupportedCharsetException e) {
            return null;
        }
    }

    /**
     * Extracts the {@code encoding="..."} value from a leading {@code
     * <?xml ... ?>} declaration, reading the first ~200 bytes as raw
     * Latin-1 (safe for this purpose only - see {@link #decodeBytes}'s
     * Javadoc). Returns null if there is no declaration, or it has no
     * {@code encoding} pseudo-attribute.
     */
    private static String sniffDeclaredEncoding(byte[] bytes) {
        int limit = Math.min(bytes.length, 200);
        StringBuilder sb = new StringBuilder(limit);
        for (int i = 0; i < limit; i++) {
            sb.append((char) (bytes[i] & 0xFF));
        }
        String prefix = sb.toString();
        if (!prefix.startsWith("<?xml")) {
            return null;
        }
        int declEnd = prefix.indexOf("?>");
        String decl = declEnd < 0 ? prefix : prefix.substring(0, declEnd);
        int idx = decl.indexOf("encoding");
        if (idx < 0) {
            return null;
        }
        int eq = decl.indexOf('=', idx);
        if (eq < 0) {
            return null;
        }
        int p = eq + 1;
        while (p < decl.length() && Character.isWhitespace(decl.charAt(p))) {
            p++;
        }
        if (p >= decl.length()) {
            return null;
        }
        char quote = decl.charAt(p);
        if (quote != '"' && quote != '\'') {
            return null;
        }
        p++;
        int end = decl.indexOf(quote, p);
        return end < 0 ? null : decl.substring(p, end);
    }

    /** True if a leading {@code <?xml ...?>}/{@code <?xml ...?>} (text)
     *  declaration declares {@code version="1.1"} (or {@code '1.1'}). */
    static boolean declaresXml11(char[] chars) {
        int limit = Math.min(chars.length, 200);
        String prefix = new String(chars, 0, limit);
        return prefix.contains("version=\"1.1\"") || prefix.contains("version='1.1'");
    }

    /**
     * Strips a leading {@code <?xml ...?>} declaration (an XML declaration
     * on the main document, or a text declaration on an external entity/DTD
     * subset - both share the same {@code '<?xml' ... '?>'} shape for this
     * purpose) - {@link Scanner} does not parse it itself (see its class
     * Javadoc "M6" section: this is a separate, earlier pipeline concern).
     * Throws if a declaration-looking prefix ({@code "<?xml"}) never finds
     * its closing {@code "?>"} - a real, if malformed, not-wf document, not
     * something to silently treat as having no declaration at all.
     */
    static char[] stripXmlDeclaration(char[] all) throws SAXException {
        int start = 0;
        if (all.length > 5 && all[0] == '<' && all[1] == '?' && all[2] == 'x' && all[3] == 'm' && all[4] == 'l') {
            int j = 5;
            while (j + 1 < all.length && !(all[j] == '?' && all[j + 1] == '>')) {
                j++;
            }
            if (j + 1 >= all.length) {
                throw new SAXException("Malformed XML declaration: missing closing \"?>\"");
            }
            start = j + 2;
            while (start < all.length && (all[start] == '\n' || all[start] == '\r')) {
                start++;
            }
        }
        char[] result = new char[all.length - start];
        System.arraycopy(all, start, result, 0, result.length);
        return result;
    }

}

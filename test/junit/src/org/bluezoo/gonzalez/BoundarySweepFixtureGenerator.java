/*
 * BoundarySweepFixtureGenerator.java
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

import java.nio.charset.StandardCharsets;

/**
 * Generates small XML documents that embed a single multi-byte UTF-8 code
 * point in every syntactic context where the tokenizer's greedy-accumulation
 * scanning could straddle its bytes across a chunk boundary: an element name,
 * an attribute name, an attribute value, text content, a comment, a CDATA
 * section, and processing instruction data.
 * <p>
 * The generated documents are meant to be fed to {@link ChunkFuzzer} at chunk
 * sizes that land on every possible byte offset within the multi-byte
 * sequence (chunk size 1 alone already guarantees this, since each of the
 * sequence's bytes then arrives in its own delivery). This exercises exactly
 * the class of bug the byte-native tokenizer rewrite is most at risk of
 * introducing, since UTF-8 self-synchronization is the property its whole
 * design leans on (see BYTE-TOKENIZER.md).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class BoundarySweepFixtureGenerator {

    /** A 2-byte UTF-8 character: LATIN SMALL LETTER E WITH ACUTE (U+00E9). */
    static final int TWO_BYTE_CHAR = 0x00E9;

    /** A 3-byte UTF-8 character: CJK Unified Ideograph "middle" (U+4E2D). */
    static final int THREE_BYTE_CHAR = 0x4E2D;

    /** A 4-byte UTF-8 (supplementary plane) character: CJK Unified Ideograph-20000. */
    static final int FOUR_BYTE_CHAR = 0x20000;

    private BoundarySweepFixtureGenerator() {
    }

    /**
     * Generates a document embedding the given code point in each of the six
     * boundary-sensitive contexts described in the class comment.
     *
     * @param codePoint the Unicode code point to embed; must be a valid XML
     *                  NameChar for the element/attribute-name placements
     * @return the generated document, encoded as UTF-8
     */
    static byte[] generate(int codePoint) {
        char[] chars = Character.toChars(codePoint);
        String ch = new String(chars);
        String name = "n" + ch + "ame";

        StringBuilder doc = new StringBuilder();
        doc.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        doc.append("<!--pre").append(ch).append("post-->\n");
        doc.append("<?target pre").append(ch).append("post?>\n");
        doc.append("<").append(name);
        doc.append(" a").append(ch).append("ttr=\"attrval\"");
        doc.append(" plain=\"pre").append(ch).append("post\"");
        doc.append(">");
        doc.append("textpre").append(ch).append("textpost");
        doc.append("<![CDATA[cdatapre").append(ch).append("cdatapost]]>");
        doc.append("</").append(name).append(">\n");

        return doc.toString().getBytes(StandardCharsets.UTF_8);
    }

}

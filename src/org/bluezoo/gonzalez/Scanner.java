/*
 * Scanner.java
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

import java.nio.CharBuffer;
import java.util.ArrayList;
import java.util.Arrays;

import org.xml.sax.SAXException;

/**
 * M1 hot-path scanner: hand-written, specialized recognition of the common
 * productions (element start/end tags, attributes, text content, char and
 * predefined entity references) plus comments/PI/CDATA, emitting directly
 * into {@link XMLHandler} - no intermediate token stream. See
 * ASYNC-PIPELINE.md for the design rationale.
 * <p>
 * <b>Deliberately out of scope for this milestone</b> (by design, not
 * oversight - see the milestone list): DOCTYPE/DTD, general entity
 * references, namespace resolution (xmlns is reported via
 * {@link XMLHandler#attribute}, matching namespace-unaware behaviour - a
 * later milestone adds the stateless filter that reroutes it to
 * {@link XMLHandler#namespace}), and location tracking. Duplicate-attribute
 * detection is not performed here either - it is enforced downstream by
 * whatever consumes these events (for the current milestone, {@link
 * SAXAdapter}'s reuse of {@link SAXAttributes}), a deliberate division of
 * responsibility rather than a gap.
 * <p>
 * <b>Suspend/resume model.</b> {@link #receive(CharBuffer)} appends into an
 * internal, self-compacting buffer and scans as much as it can. Every
 * production is written straight-line, "assume it all fits"; the only
 * suspend-awareness is a bounds check at each point more data could be
 * needed. Comments/PI/CDATA/end-tags/the element name itself are each a
 * single atomic emit: on running out of data mid-construct, scanning simply
 * rewinds to that construct's start and the whole thing is retried from
 * scratch once more data arrives - nothing has been emitted yet, so this is
 * always safe. The one construct that emits multiple events before
 * completing - a start tag's attribute list - carries the coarse resumable
 * flag {@link #inStartTag}: once {@link XMLHandler#startElement} has fired,
 * the attribute-scanning loop itself rewinds only to the start of whichever
 * individual attribute was in progress, never re-entering already-emitted
 * ones.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class Scanner {

    private static final int INITIAL_CAPACITY = 8192;
    private static final int INITIAL_SCRATCH_CAPACITY = 256;

    private final XMLHandler handler;

    private char[] buf;
    private int pos;
    private int limit;

    /** Coarse resumable mode: startElement has fired, resume the attribute loop. */
    private boolean inStartTag;

    /** WFC "Element Type Match" stack. String-based for M1; PackedName-handle
     *  based interning is M2's optimisation, layered on top of this working
     *  scanner rather than built in from the start. */
    private final ArrayList<String> elementStack = new ArrayList<String>();

    /** Reusable growable buffer for attribute values containing entity references. */
    private char[] valueScratch;
    private int valueScratchLen;

    /** Reusable scratch for one decoded entity reference (1 char, or 2 for a surrogate pair). */
    private final char[] entityScratch = new char[2];

    Scanner(XMLHandler handler) throws SAXException {
        this.handler = handler;
        this.buf = new char[INITIAL_CAPACITY];
        this.valueScratch = new char[INITIAL_SCRATCH_CAPACITY];
        handler.startDocument();
    }

    /**
     * Feeds more decoded character data to the scanner. May be called
     * multiple times; an incomplete construct at the end of one call is
     * resumed (or, for atomic constructs, retried from its start) on the
     * next.
     */
    void receive(CharBuffer data) throws SAXException {
        append(data);
        scan();
    }

    /**
     * Signals end of input. Reports a fatal error if the document ends
     * mid-construct or with unclosed elements.
     */
    void close() throws SAXException {
        if (inStartTag || !elementStack.isEmpty()) {
            throw handler.fatalError("Document ended unexpectedly (unclosed element or tag)");
        }
        handler.endDocument();
    }

    private void append(CharBuffer data) {
        int needed = data.remaining();
        if (pos > 0) {
            int remaining = limit - pos;
            if (remaining > 0) {
                System.arraycopy(buf, pos, buf, 0, remaining);
            }
            limit = remaining;
            pos = 0;
        }
        if (limit + needed > buf.length) {
            int newCap = buf.length * 2;
            while (newCap < limit + needed) {
                newCap *= 2;
            }
            buf = Arrays.copyOf(buf, newCap);
        }
        data.get(buf, limit, needed);
        limit += needed;
    }

    private static boolean isWs(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r';
    }

    // Permissive ASCII-plus-non-ASCII check, not the exact XML NameStartChar/
    // NameChar Unicode ranges - sufficient for M1's architecture/perf probe;
    // exact legality is deferred to conformance hardening.
    private static boolean isNameChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                || c == '_' || c == '-' || c == '.' || c == ':' || c > 127;
    }

    // ===== Main loop =====

    private void scan() throws SAXException {
        while (true) {
            if (inStartTag) {
                if (!scanAttributesAndTagEnd()) {
                    return;
                }
                inStartTag = false;
                continue;
            }
            if (pos >= limit) {
                return;
            }
            if (buf[pos] == '<') {
                if (!scanMarkup()) {
                    return;
                }
            } else {
                scanContent();
                if (pos >= limit) {
                    return;
                }
            }
        }
    }

    // ===== Content (text) =====

    /**
     * Scans text content up to the next '&lt;' (left for the main loop to
     * dispatch) or until the buffer is exhausted. Literal runs are emitted
     * as a zero-copy view directly into the scan buffer; entity references
     * are decoded through {@link #entityScratch}. Never fails - an
     * incomplete entity reference at the end of the buffer is rewound to its
     * '&amp;' and left for the next call, with everything before it already
     * safely emitted.
     * <p>
     * Nothing is emitted while {@link #elementStack} is empty (prolog or
     * epilog, per XML's {@code Misc*} production - only whitespace, comments
     * and PIs are legal there, and even whitespace is not reported as
     * {@link XMLHandler#characters} content by SAX-conforming parsers). The
     * text is still scanned and consumed, just not delivered.
     */
    private void scanContent() throws SAXException {
        boolean insideDocument = !elementStack.isEmpty();
        while (pos < limit) {
            int runStart = pos;
            while (pos < limit && buf[pos] != '<' && buf[pos] != '&') {
                pos++;
            }
            if (insideDocument && pos > runStart) {
                handler.characters(CharBuffer.wrap(buf, runStart, pos - runStart));
            }
            if (pos >= limit || buf[pos] == '<') {
                return;
            }
            // buf[pos] == '&'
            int ampPos = pos;
            int decoded = decodeEntityRef();
            if (decoded < 0) {
                pos = ampPos;
                return;
            }
            if (insideDocument) {
                handler.characters(CharBuffer.wrap(entityScratch, 0, decoded));
            }
        }
    }

    /**
     * Attempts to decode one entity/character reference starting at
     * {@code buf[pos]} (which must be '&amp;'). On success, advances
     * {@code pos} past the trailing ';', writes the decoded character(s)
     * into {@link #entityScratch}, and returns the count written (1, or 2
     * for a surrogate pair). Returns -1 if there is not yet enough buffered
     * data to be sure; {@code pos} is left unchanged and the caller must
     * rewind further back to the start of whatever construct is being
     * scanned and wait for more data.
     */
    private int decodeEntityRef() throws SAXException {
        int p = pos + 1;
        if (p >= limit) {
            return -1;
        }
        if (buf[p] == '#') {
            p++;
            boolean hex = false;
            if (p < limit && (buf[p] == 'x' || buf[p] == 'X')) {
                hex = true;
                p++;
            }
            int digitsStart = p;
            while (p < limit && buf[p] != ';') {
                char d = buf[p];
                boolean ok = hex
                        ? ((d >= '0' && d <= '9') || (d >= 'a' && d <= 'f') || (d >= 'A' && d <= 'F'))
                        : (d >= '0' && d <= '9');
                if (!ok) {
                    throw handler.fatalError("Malformed character reference");
                }
                p++;
            }
            if (p >= limit) {
                return -1;
            }
            if (p == digitsStart) {
                throw handler.fatalError("Empty character reference");
            }
            int codePoint;
            try {
                codePoint = Integer.parseInt(new String(buf, digitsStart, p - digitsStart), hex ? 16 : 10);
            } catch (NumberFormatException e) {
                throw handler.fatalError("Malformed character reference");
            }
            if (codePoint < 0 || codePoint > 0x10FFFF) {
                throw handler.fatalError("Character reference out of range: " + codePoint);
            }
            pos = p + 1;
            if (codePoint < 0x10000) {
                entityScratch[0] = (char) codePoint;
                return 1;
            }
            int cp = codePoint - 0x10000;
            entityScratch[0] = (char) (0xD800 + (cp >> 10));
            entityScratch[1] = (char) (0xDC00 + (cp & 0x3FF));
            return 2;
        }

        int nameStart = p;
        while (p < limit && isNameChar(buf[p])) {
            p++;
        }
        if (p >= limit) {
            return -1;
        }
        if (p == nameStart || buf[p] != ';') {
            throw handler.fatalError("Malformed entity reference");
        }
        int len = p - nameStart;
        char decoded;
        if (len == 3 && buf[nameStart] == 'a' && buf[nameStart + 1] == 'm' && buf[nameStart + 2] == 'p') {
            decoded = '&';
        } else if (len == 2 && buf[nameStart] == 'l' && buf[nameStart + 1] == 't') {
            decoded = '<';
        } else if (len == 2 && buf[nameStart] == 'g' && buf[nameStart + 1] == 't') {
            decoded = '>';
        } else if (len == 4 && buf[nameStart] == 'a' && buf[nameStart + 1] == 'p'
                && buf[nameStart + 2] == 'o' && buf[nameStart + 3] == 's') {
            decoded = '\'';
        } else if (len == 4 && buf[nameStart] == 'q' && buf[nameStart + 1] == 'u'
                && buf[nameStart + 2] == 'o' && buf[nameStart + 3] == 't') {
            decoded = '"';
        } else {
            throw handler.fatalError("General entity references are not supported in this milestone: &"
                    + new String(buf, nameStart, len) + ";");
        }
        pos = p + 1;
        entityScratch[0] = decoded;
        return 1;
    }

    // ===== Markup dispatch =====

    private boolean scanMarkup() throws SAXException {
        int tagStart = pos;
        int p = tagStart + 1;
        if (p >= limit) {
            return false;
        }
        char c = buf[p];
        if (c == '/') {
            return scanEndTag(tagStart);
        } else if (c == '!') {
            return scanBangMarkup(tagStart);
        } else if (c == '?') {
            return scanPI(tagStart);
        } else {
            return scanStartTag(tagStart);
        }
    }

    // ===== Start tag (the coarse-resume production) =====

    private boolean scanStartTag(int tagStart) throws SAXException {
        int p = tagStart + 1;
        int nameStart = p;
        while (p < limit && isNameChar(buf[p])) {
            p++;
        }
        if (p >= limit) {
            pos = tagStart;
            return false;
        }
        if (p == nameStart) {
            throw handler.fatalError("Malformed start tag");
        }
        String qName = new String(buf, nameStart, p - nameStart);
        pos = p;
        elementStack.add(qName);
        handler.startElement(qName);
        inStartTag = true;
        return true;
    }

    /**
     * Scans attributes and the tag terminator ('&gt;' or '/&gt;'). Called
     * both immediately after {@link #scanStartTag} (fresh) and again by the
     * main loop whenever {@link #inStartTag} is still true at the start of a
     * {@link #receive(CharBuffer)} call (resumed). Each loop iteration is
     * itself atomic: on underflow anywhere within one attribute, {@code pos}
     * rewinds to that attribute's start (not the tag start), so
     * already-emitted attributes are never re-emitted.
     */
    private boolean scanAttributesAndTagEnd() throws SAXException {
        while (true) {
            int attrStart = pos;
            while (pos < limit && isWs(buf[pos])) {
                pos++;
            }
            if (pos >= limit) {
                pos = attrStart;
                return false;
            }
            char c = buf[pos];
            if (c == '>') {
                pos++;
                handler.endAttributes();
                return true;
            }
            if (c == '/') {
                if (pos + 1 >= limit) {
                    pos = attrStart;
                    return false;
                }
                if (buf[pos + 1] != '>') {
                    throw handler.fatalError("Malformed start tag");
                }
                pos += 2;
                elementStack.remove(elementStack.size() - 1);
                handler.endAttributes();
                handler.endElement();
                return true;
            }

            int nameStart = pos;
            while (pos < limit && isNameChar(buf[pos])) {
                pos++;
            }
            if (pos >= limit) {
                pos = attrStart;
                return false;
            }
            if (pos == nameStart) {
                throw handler.fatalError("Malformed start tag");
            }
            String attrName = new String(buf, nameStart, pos - nameStart);

            while (pos < limit && isWs(buf[pos])) {
                pos++;
            }
            if (pos >= limit) {
                pos = attrStart;
                return false;
            }
            if (buf[pos] != '=') {
                throw handler.fatalError("Expected '=' after attribute name");
            }
            pos++;

            while (pos < limit && isWs(buf[pos])) {
                pos++;
            }
            if (pos >= limit) {
                pos = attrStart;
                return false;
            }
            char quote = buf[pos];
            if (quote != '"' && quote != '\'') {
                throw handler.fatalError("Expected quoted attribute value");
            }
            pos++;

            CharBuffer value = scanAttributeValue(quote);
            if (value == null) {
                pos = attrStart;
                return false;
            }
            handler.attribute(attrName, value);
        }
    }

    /**
     * Scans an attribute value up to (and consuming) the matching quote.
     * Returns a zero-copy view directly into the scan buffer if the value
     * contains no entity references (the common case); falls back to
     * accumulating into {@link #valueScratch} only once an entity is
     * actually found. Returns null on underflow - the caller discards
     * everything scanned so far for this attribute and rewinds to its start.
     */
    private CharBuffer scanAttributeValue(char quote) throws SAXException {
        int valueStart = pos;
        boolean usingScratch = false;
        int copyFrom = valueStart;
        while (true) {
            while (pos < limit && buf[pos] != quote && buf[pos] != '&' && buf[pos] != '<') {
                pos++;
            }
            if (pos >= limit) {
                return null;
            }
            if (buf[pos] == '<') {
                throw handler.fatalError("'<' is not allowed in an attribute value");
            }
            if (buf[pos] == quote) {
                if (!usingScratch) {
                    CharBuffer result = CharBuffer.wrap(buf, valueStart, pos - valueStart);
                    pos++;
                    return result;
                }
                appendToScratch(buf, copyFrom, pos - copyFrom);
                pos++;
                return CharBuffer.wrap(valueScratch, 0, valueScratchLen);
            }
            // buf[pos] == '&'
            if (!usingScratch) {
                usingScratch = true;
                valueScratchLen = 0;
            }
            appendToScratch(buf, copyFrom, pos - copyFrom);
            int n = decodeEntityRef();
            if (n < 0) {
                return null;
            }
            appendToScratch(entityScratch, 0, n);
            copyFrom = pos;
        }
    }

    private void appendToScratch(char[] src, int off, int len) {
        if (len == 0) {
            return;
        }
        int needed = valueScratchLen + len;
        if (needed > valueScratch.length) {
            int newCap = valueScratch.length * 2;
            while (newCap < needed) {
                newCap *= 2;
            }
            valueScratch = Arrays.copyOf(valueScratch, newCap);
        }
        System.arraycopy(src, off, valueScratch, valueScratchLen, len);
        valueScratchLen += len;
    }

    // ===== End tag =====

    private boolean scanEndTag(int tagStart) throws SAXException {
        int p = tagStart + 2;
        int nameStart = p;
        while (p < limit && isNameChar(buf[p])) {
            p++;
        }
        if (p >= limit) {
            pos = tagStart;
            return false;
        }
        if (p == nameStart) {
            throw handler.fatalError("Malformed end tag");
        }
        int nameEnd = p;
        while (p < limit && isWs(buf[p])) {
            p++;
        }
        if (p >= limit) {
            pos = tagStart;
            return false;
        }
        if (buf[p] != '>') {
            throw handler.fatalError("Malformed end tag");
        }
        p++;

        String name = new String(buf, nameStart, nameEnd - nameStart);
        if (elementStack.isEmpty()) {
            throw handler.fatalError("End tag without matching start tag: " + name);
        }
        String expected = elementStack.remove(elementStack.size() - 1);
        if (!expected.equals(name)) {
            throw handler.fatalError("Mismatched end tag: expected </" + expected + "> but found </" + name + ">");
        }

        pos = p;
        handler.endElement();
        return true;
    }

    // ===== Comment / CDATA (disambiguated by the byte after "<!") =====

    private boolean scanBangMarkup(int tagStart) throws SAXException {
        int p = tagStart + 2;
        if (p >= limit) {
            pos = tagStart;
            return false;
        }
        if (buf[p] == '-') {
            return scanComment(tagStart);
        } else if (buf[p] == '[') {
            return scanCDATA(tagStart);
        } else {
            throw handler.fatalError("DOCTYPE and other markup declarations are not yet supported in this milestone");
        }
    }

    private boolean scanComment(int tagStart) throws SAXException {
        if (tagStart + 4 > limit) {
            pos = tagStart;
            return false;
        }
        if (buf[tagStart + 3] != '-') {
            throw handler.fatalError("Malformed markup declaration");
        }
        int contentStart = tagStart + 4;
        int p = contentStart;
        while (true) {
            while (p < limit && buf[p] != '-') {
                p++;
            }
            if (p >= limit) {
                pos = tagStart;
                return false;
            }
            if (p + 2 >= limit) {
                pos = tagStart;
                return false;
            }
            if (buf[p + 1] == '-') {
                if (buf[p + 2] == '>') {
                    handler.comment(CharBuffer.wrap(buf, contentStart, p - contentStart));
                    pos = p + 3;
                    return true;
                }
                throw handler.fatalError("'--' is not allowed inside a comment");
            }
            p++;
        }
    }

    private static final char[] CDATA_MARKER = "<![CDATA[".toCharArray();

    private boolean scanCDATA(int tagStart) throws SAXException {
        int matchLen = Math.min(CDATA_MARKER.length, limit - tagStart);
        for (int i = 2; i < matchLen; i++) {
            if (buf[tagStart + i] != CDATA_MARKER[i]) {
                throw handler.fatalError("Malformed markup declaration");
            }
        }
        if (tagStart + CDATA_MARKER.length > limit) {
            pos = tagStart;
            return false;
        }
        int contentStart = tagStart + CDATA_MARKER.length;
        int p = contentStart;
        while (true) {
            while (p < limit && buf[p] != ']') {
                p++;
            }
            if (p >= limit) {
                pos = tagStart;
                return false;
            }
            if (p + 2 >= limit) {
                pos = tagStart;
                return false;
            }
            if (buf[p + 1] == ']' && buf[p + 2] == '>') {
                if (p > contentStart) {
                    handler.characters(CharBuffer.wrap(buf, contentStart, p - contentStart));
                }
                pos = p + 3;
                return true;
            }
            p++;
        }
    }

    // ===== Processing instruction =====

    private boolean scanPI(int tagStart) throws SAXException {
        int p = tagStart + 2;
        int targetStart = p;
        while (p < limit && isNameChar(buf[p])) {
            p++;
        }
        if (p >= limit) {
            pos = tagStart;
            return false;
        }
        if (p == targetStart) {
            throw handler.fatalError("Malformed processing instruction");
        }
        String target = new String(buf, targetStart, p - targetStart);
        if (target.length() == 3
                && (target.charAt(0) == 'x' || target.charAt(0) == 'X')
                && (target.charAt(1) == 'm' || target.charAt(1) == 'M')
                && (target.charAt(2) == 'l' || target.charAt(2) == 'L')) {
            throw handler.fatalError("Processing instruction target matching [Xx][Mm][Ll] is reserved");
        }
        if (p < limit && isWs(buf[p])) {
            p++;
        }
        int dataStart = p;
        while (true) {
            while (p < limit && buf[p] != '?') {
                p++;
            }
            if (p >= limit) {
                pos = tagStart;
                return false;
            }
            if (p + 1 >= limit) {
                pos = tagStart;
                return false;
            }
            if (buf[p + 1] == '>') {
                CharBuffer data = (p > dataStart) ? CharBuffer.wrap(buf, dataStart, p - dataStart) : null;
                handler.processingInstruction(target, data);
                pos = p + 2;
                return true;
            }
            p++;
        }
    }

}

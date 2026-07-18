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
 * references, namespace resolution (xmlns is reported as a regular
 * attribute, matching namespace-unaware behaviour - a later milestone adds
 * the stateless filter that reroutes it to {@link XMLHandler#namespace}),
 * and location tracking. Duplicate-attribute detection is not performed
 * here either - it is enforced downstream by whatever consumes these events
 * (for the current milestone, {@link SAXAdapter}'s reuse of
 * {@link SAXAttributes}), a deliberate division of responsibility rather
 * than a gap. Line-ending normalisation is likewise not this class's
 * concern - it belongs at the byte-to-char decode stage (ExternalEntityDecoder),
 * done once right at the start of the pipeline, not re-derived here.
 * <p>
 * <b>Zero allocation.</b> This class's job is to identify contiguous runs
 * and push them downstream as fast as possible - it never assembles a
 * complete value into a buffer of its own before emitting. Literal runs are
 * always zero-copy views directly into the scan buffer. Entity references
 * are backed either by static, invariant, shared buffers (predefined
 * entities - their content never changes, so no copying protocol is
 * needed) or by a single small buffer allocated once per Scanner instance
 * (numeric character references - see {@link #decodeEntityRef()} and
 * {@link XMLHandler#saveBuffers()}). See {@link XMLHandler}'s class Javadoc
 * for the full streaming/{@code end}-flag/{@code saveBuffers()} design this
 * implements.
 * <p>
 * <b>Suspend/resume model.</b> {@link #receive(CharBuffer)} appends into an
 * internal, self-compacting buffer and scans as much as it can. Every
 * production is written straight-line, "assume it all fits"; the only
 * suspend-awareness is a bounds check at each point more data could be
 * needed. Comments/PI/CDATA/end-tags/the element name itself are each a
 * single atomic emit: on running out of data mid-construct, scanning simply
 * rewinds to that construct's start and the whole thing is retried from
 * scratch once more data arrives - nothing has been emitted yet, so this is
 * always safe. Content and attribute-value scanning are different: since
 * they stream multiple events per logical run (see above), once any event
 * has been emitted for a run there is no rewinding past it - {@link
 * #scanContent()} and {@link #scanAttributeValueStreaming()} both return
 * {@code boolean} (true = made confirmed progress / caller should continue;
 * false = blocked on currently-available data, caller must stop and wait)
 * and are simply re-entered, continuing from the current {@code pos}, on
 * the next {@link #receive(CharBuffer)} call. The one construct that emits
 * multiple *events of a different kind* before completing - a start tag's
 * attribute list - carries the coarse resumable flag {@link #inStartTag}
 * (and, when specifically mid-value, {@link #inAttributeValue}): once
 * {@link XMLHandler#startElement} has fired, the attribute-scanning loop
 * itself resumes at exactly the sub-step it left off at, never re-entering
 * already-emitted attributes or already-emitted value chunks.
 * <p>
 * <b>Name interning (M2).</b> Element/attribute names are interned via
 * {@link #namePool} ({@link PackedName}). A first version reused the
 * existing {@link InternedStringPool} directly - measured as a strong net
 * win overall (attrs +20%, multibyte +17%, both heavy-repeated-name-vocabulary
 * docTypes) but with a real, reproduced ~5% regression on whitespace-heavy
 * documents (large text content, comparatively little repeated name
 * vocabulary, where interning's per-call cost isn't recouped as often and
 * competes against an already-cheap TLAB-allocated String). {@link
 * PackedName} replaces the per-character hash-then-compare loop with
 * quad-packed primitive comparison - see its class Javadoc for the design.
 * The WFC element-type-match stack ({@link #elementStack}) stores the
 * interned qName rather than a separate packed handle: since both the
 * pushed start-tag name and the compared end-tag name go through the same
 * pool, a correctly-matched tag pair is the same canonical String instance,
 * and {@code String.equals()}'s own reference-equality fast path already
 * makes that comparison O(1) in the common case.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class Scanner {

    private static final int INITIAL_CAPACITY = 8192;

    private final XMLHandler handler;

    private char[] buf;
    private int pos;
    private int limit;

    /** Coarse resumable mode: startElement has fired, resume the attribute loop. */
    private boolean inStartTag;

    /** Finer resumable mode within the attribute loop: startAttribute() has
     *  fired for the current attribute and its value is being streamed;
     *  resume {@link #scanAttributeValueStreaming()} directly rather than
     *  re-entering the attribute loop from its top. Valid (and meaningful)
     *  only while true; {@link #pendingQuote} and {@link #attrValueRunOpen}
     *  are its associated state. */
    private boolean inAttributeValue;
    private char pendingQuote;
    private boolean attrValueRunOpen;

    /** True if the most recent characters() emission for the run currently
     *  in progress had end=false and has not yet been closed with end=true -
     *  see {@link #scanContent()}. */
    private boolean contentRunOpen;

    /** WFC "Element Type Match" stack of interned qNames. */
    private final ArrayList<String> elementStack = new ArrayList<String>();

    /**
     * Interns element/attribute names: M1 called {@code new String(...)} for
     * every single occurrence, with no caching at all - for a repeated name
     * (element/attribute vocabulary is typically small and reused constantly
     * throughout a document) that is a fresh allocation every single time.
     * See class Javadoc for the choice of {@link PackedName} over
     * {@link InternedStringPool}.
     */
    private final PackedName namePool = new PackedName();

    // ===== Entity reference buffers (see class Javadoc: "Zero allocation") =====

    private static CharBuffer predefinedBuffer(char c) {
        CharBuffer cb = CharBuffer.allocate(1);
        cb.put(c);
        cb.flip();
        return cb.asReadOnlyBuffer();
    }

    private static final CharBuffer PREDEFINED_AMP = predefinedBuffer('&');
    private static final CharBuffer PREDEFINED_LT = predefinedBuffer('<');
    private static final CharBuffer PREDEFINED_GT = predefinedBuffer('>');
    private static final CharBuffer PREDEFINED_APOS = predefinedBuffer('\'');
    private static final CharBuffer PREDEFINED_QUOT = predefinedBuffer('"');

    /** Shared empty buffer for the HTTP/2-DATA-frame-style empty closing
     *  event (see XMLHandler's class Javadoc) - always empty, so, like the
     *  predefined-entity buffers, safe to share without any copying protocol. */
    private static final CharBuffer EMPTY_BUFFER = CharBuffer.wrap(new char[0]).asReadOnlyBuffer();

    /** Backing array for numeric character reference decoding - one tiny
     *  (2-char, for surrogate pairs) array allocated once per Scanner
     *  instance (per parser), reused for every numeric reference in the
     *  document. {@link #numericRefBuffer} wraps it once; only its
     *  position/limit are adjusted per use. {@link XMLHandler#saveBuffers()}
     *  fires immediately before each write into this array - see
     *  {@link #decodeEntityRef()}. */
    private final char[] numericRefArray = new char[2];
    private final CharBuffer numericRefBuffer = CharBuffer.wrap(numericRefArray);

    Scanner(XMLHandler handler) throws SAXException {
        this.handler = handler;
        this.buf = new char[INITIAL_CAPACITY];
        handler.startDocument();
    }

    /**
     * Feeds more decoded character data to the scanner. May be called
     * multiple times; an incomplete construct at the end of one call is
     * resumed (or, for atomic constructs, retried from its start) on the
     * next. Fires {@link XMLHandler#saveBuffers()} once, after all
     * currently-available data has been scanned, since the scan buffer may
     * be compacted/reused before the next call - see XMLHandler's class
     * Javadoc.
     */
    void receive(CharBuffer data) throws SAXException {
        append(data);
        scan();
        handler.saveBuffers();
    }

    /**
     * Signals end of input. Reports a fatal error if the document ends
     * mid-construct or with unclosed elements.
     */
    void close() throws SAXException {
        if (inStartTag || inAttributeValue || !elementStack.isEmpty()) {
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

    /** Allocation-free comparison of a buffer range against a String, used for
     *  the WFC end-tag check (see {@link #scanEndTag}). */
    private static boolean rangeEquals(char[] buf, int start, int len, String s) {
        if (s.length() != len) {
            return false;
        }
        for (int i = 0; i < len; i++) {
            if (buf[start + i] != s.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    // ===== Main loop =====

    private void scan() throws SAXException {
        while (true) {
            if (inAttributeValue) {
                if (!scanAttributeValueStreaming()) {
                    return;
                }
                inAttributeValue = false;
                continue;
            }
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
                if (!scanContent()) {
                    return;
                }
            }
        }
    }

    // ===== Content (text) =====

    /**
     * Scans and streams text content up to the next '&lt;' or until the
     * buffer is exhausted, emitting each contiguous run (or decoded entity
     * reference) as its own {@link XMLHandler#characters} call with an
     * explicit end flag - see {@link XMLHandler}'s class Javadoc for the
     * streaming/end-flag/zero-allocation design this implements.
     * <p>
     * Returns true if it stopped because it found '&lt;' (confirmed end of
     * this run - the main loop should dispatch to markup next); false if it
     * could not make further progress with currently-available data
     * (genuinely out of data, or blocked on an incomplete entity reference
     * right at the buffer's end). The caller must not simply loop again in
     * the false case - nothing about a blocked state changes without new
     * input, and doing so would spin forever (this is, in fact, a real bug
     * this exact method had before it returned a status the caller could
     * act on: an entity reference incomplete at a buffer boundary left
     * {@code pos < limit} sitting on the unconsumed '&amp;', which the
     * previous void-returning/{@code pos >= limit}-inferring version could
     * not distinguish from "nothing left to do").
     * <p>
     * Nothing is emitted while {@link #elementStack} is empty (prolog or
     * epilog, per XML's {@code Misc*} production - only whitespace, comments
     * and PIs are legal there, and even whitespace is not reported as
     * {@link XMLHandler#characters} content by SAX-conforming parsers). The
     * text is still scanned and consumed, just not delivered.
     */
    private boolean scanContent() throws SAXException {
        boolean insideDocument = !elementStack.isEmpty();
        while (true) {
            int runStart = pos;
            while (pos < limit && buf[pos] != '<' && buf[pos] != '&') {
                pos++;
            }
            if (pos >= limit) {
                if (insideDocument && pos > runStart) {
                    handler.characters(CharBuffer.wrap(buf, runStart, pos - runStart), false);
                    contentRunOpen = true;
                }
                return false;
            }
            if (buf[pos] == '<') {
                if (insideDocument) {
                    if (pos > runStart) {
                        handler.characters(CharBuffer.wrap(buf, runStart, pos - runStart), true);
                        contentRunOpen = false;
                    } else if (contentRunOpen) {
                        handler.characters(EMPTY_BUFFER, true);
                        contentRunOpen = false;
                    }
                }
                return true;
            }
            // buf[pos] == '&'
            if (insideDocument && pos > runStart) {
                handler.characters(CharBuffer.wrap(buf, runStart, pos - runStart), false);
                contentRunOpen = true;
            }
            int ampPos = pos;
            CharBuffer decoded = decodeEntityRef();
            if (decoded == null) {
                pos = ampPos;
                return false;
            }
            boolean atMarkup = (pos < limit && buf[pos] == '<');
            if (insideDocument) {
                handler.characters(decoded, atMarkup);
                contentRunOpen = !atMarkup;
            }
            if (atMarkup) {
                return true;
            }
            // loop continues, scanning the next run after the entity
        }
    }

    /**
     * Attempts to decode one entity/character reference starting at
     * {@code buf[pos]} (which must be '&amp;'). On success, advances
     * {@code pos} past the trailing ';' and returns the CharBuffer to emit:
     * <ul>
     * <li>for one of the five predefined entities, the corresponding static,
     * invariant, shared buffer (rewound before return) - its content never
     * changes, so it is always safe to reference, no matter how long a
     * consumer holds onto it;</li>
     * <li>for a numeric character reference, {@link #numericRefBuffer}
     * (repositioned before return) - reused across every numeric reference
     * in the document, so {@link XMLHandler#saveBuffers()} fires
     * immediately before repositioning it, telling any consumer holding an
     * unflushed reference from a prior numeric-reference event that it must
     * copy that data now.</li>
     * </ul>
     * Returns null if there is not yet enough buffered data to be sure;
     * {@code pos} is left unchanged and the caller must rewind further back
     * to the start of whatever construct is being scanned and wait for more
     * data.
     */
    private CharBuffer decodeEntityRef() throws SAXException {
        int p = pos + 1;
        if (p >= limit) {
            return null;
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
                return null;
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
            handler.saveBuffers();
            numericRefBuffer.clear();
            if (codePoint < 0x10000) {
                numericRefArray[0] = (char) codePoint;
                numericRefBuffer.limit(1);
            } else {
                int cp = codePoint - 0x10000;
                numericRefArray[0] = (char) (0xD800 + (cp >> 10));
                numericRefArray[1] = (char) (0xDC00 + (cp & 0x3FF));
                numericRefBuffer.limit(2);
            }
            return numericRefBuffer;
        }

        int nameStart = p;
        while (p < limit && isNameChar(buf[p])) {
            p++;
        }
        if (p >= limit) {
            return null;
        }
        if (p == nameStart || buf[p] != ';') {
            throw handler.fatalError("Malformed entity reference");
        }
        int len = p - nameStart;
        CharBuffer predefined;
        if (len == 3 && buf[nameStart] == 'a' && buf[nameStart + 1] == 'm' && buf[nameStart + 2] == 'p') {
            predefined = PREDEFINED_AMP;
        } else if (len == 2 && buf[nameStart] == 'l' && buf[nameStart + 1] == 't') {
            predefined = PREDEFINED_LT;
        } else if (len == 2 && buf[nameStart] == 'g' && buf[nameStart + 1] == 't') {
            predefined = PREDEFINED_GT;
        } else if (len == 4 && buf[nameStart] == 'a' && buf[nameStart + 1] == 'p'
                && buf[nameStart + 2] == 'o' && buf[nameStart + 3] == 's') {
            predefined = PREDEFINED_APOS;
        } else if (len == 4 && buf[nameStart] == 'q' && buf[nameStart + 1] == 'u'
                && buf[nameStart + 2] == 'o' && buf[nameStart + 3] == 't') {
            predefined = PREDEFINED_QUOT;
        } else {
            throw handler.fatalError("General entity references are not supported in this milestone: &"
                    + new String(buf, nameStart, len) + ";");
        }
        pos = p + 1;
        predefined.rewind();
        return predefined;
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
        String qName = namePool.intern(CharBuffer.wrap(buf, nameStart, p - nameStart));
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
     * {@link #receive(CharBuffer)} call (resumed). Whitespace/name/'='/quote
     * scanning for one attribute is atomic: on underflow there, {@code pos}
     * rewinds to that attribute's start (nothing emitted yet for it, safe).
     * Once {@link XMLHandler#startAttribute} has fired, though, this method
     * is committed - {@link #scanAttributeValueStreaming()} handles its own
     * resumption via {@link #inAttributeValue} instead of rewinding, since
     * value content may already have been emitted.
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
            String attrName = namePool.intern(CharBuffer.wrap(buf, nameStart, pos - nameStart));

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

            handler.startAttribute(attrName);
            pendingQuote = quote;
            attrValueRunOpen = false;
            if (!scanAttributeValueStreaming()) {
                inAttributeValue = true;
                return false;
            }
        }
    }

    /**
     * Streams the current attribute's value as a sequence of
     * {@link XMLHandler#attributeValueContent} calls terminated by
     * {@link #pendingQuote} - structurally identical to {@link
     * #scanContent()} (same run-scan/emit/entity-decode shape, same
     * end-flag semantics, same zero-allocation approach), just with a
     * different terminator and event. Called both immediately after the
     * opening quote is consumed (fresh) and again whenever {@link
     * #inAttributeValue} is still true at the start of a {@link
     * #receive(CharBuffer)} call (resumed) - safe either way since it
     * always continues from the current {@code pos}.
     * <p>
     * Returns true once the closing quote is found (this attribute's value
     * is complete); false on underflow, having already emitted whatever was
     * available - the caller must not rewind past this point (see
     * {@link #scanAttributesAndTagEnd()}).
     */
    private boolean scanAttributeValueStreaming() throws SAXException {
        char quote = pendingQuote;
        while (true) {
            int runStart = pos;
            while (pos < limit && buf[pos] != quote && buf[pos] != '&' && buf[pos] != '<') {
                pos++;
            }
            if (pos >= limit) {
                if (pos > runStart) {
                    handler.attributeValueContent(CharBuffer.wrap(buf, runStart, pos - runStart), false);
                    attrValueRunOpen = true;
                }
                return false;
            }
            if (buf[pos] == '<') {
                throw handler.fatalError("'<' is not allowed in an attribute value");
            }
            if (buf[pos] == quote) {
                if (pos > runStart) {
                    handler.attributeValueContent(CharBuffer.wrap(buf, runStart, pos - runStart), true);
                } else if (attrValueRunOpen) {
                    handler.attributeValueContent(EMPTY_BUFFER, true);
                }
                attrValueRunOpen = false;
                pos++;
                return true;
            }
            // buf[pos] == '&'
            if (pos > runStart) {
                handler.attributeValueContent(CharBuffer.wrap(buf, runStart, pos - runStart), false);
                attrValueRunOpen = true;
            }
            int ampPos = pos;
            CharBuffer decoded = decodeEntityRef();
            if (decoded == null) {
                pos = ampPos;
                return false;
            }
            boolean atQuote = (pos < limit && buf[pos] == quote);
            handler.attributeValueContent(decoded, atQuote);
            if (atQuote) {
                attrValueRunOpen = false;
                pos++;
                return true;
            }
            attrValueRunOpen = true;
            // loop continues, scanning the next run after the entity
        }
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

        // The end tag's name is never exposed through XMLHandler - endElement()
        // takes no arguments (see class Javadoc) - so it is purely internal WFC
        // bookkeeping and does not need to become a String at all unless there
        // is actually a mismatch to report.
        int nameLen = nameEnd - nameStart;
        if (elementStack.isEmpty()) {
            throw handler.fatalError("End tag without matching start tag: " + new String(buf, nameStart, nameLen));
        }
        String expected = elementStack.remove(elementStack.size() - 1);
        if (!rangeEquals(buf, nameStart, nameLen, expected)) {
            throw handler.fatalError("Mismatched end tag: expected </" + expected + "> but found </"
                    + new String(buf, nameStart, nameLen) + ">");
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
                    handler.characters(CharBuffer.wrap(buf, contentStart, p - contentStart), true);
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
        String target = namePool.intern(CharBuffer.wrap(buf, targetStart, p - targetStart));
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

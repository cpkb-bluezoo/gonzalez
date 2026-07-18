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
import java.util.HashMap;
import java.util.HashSet;

import org.xml.sax.SAXException;

/**
 * M1 hot-path scanner: hand-written, specialized recognition of the common
 * productions (element start/end tags, attributes, text content, char and
 * predefined entity references) plus comments/PI/CDATA, emitting directly
 * into {@link XMLHandler} - no intermediate token stream. See
 * ASYNC-PIPELINE.md for the design rationale.
 * <p>
 * <b>Deliberately out of scope</b> (by design, not oversight - see the
 * milestone list): namespace resolution (xmlns is reported as a regular
 * attribute, matching namespace-unaware behaviour - {@link NamespaceFilter}
 * reroutes it to {@link XMLHandler#namespace} for a namespace-aware
 * pipeline), and location tracking. Duplicate-attribute detection is not
 * performed here either - it is enforced downstream by whatever consumes
 * these events (for the current milestone, {@link SAXAdapter}'s reuse of
 * {@link SAXAttributes}), a deliberate division of responsibility rather
 * than a gap. Line-ending normalisation is likewise not this class's
 * concern - it belongs at the byte-to-char decode stage (ExternalEntityDecoder),
 * done once right at the start of the pipeline, not re-derived here.
 * <p>
 * <b>DOCTYPE / internal general entities (M4).</b> {@code <!DOCTYPE ...>} is
 * recognised, including an optional external ID (syntactically consumed,
 * never fetched) and an optional internal subset. Within the internal
 * subset, {@code <!ENTITY Name "value">} (general, internal, quoted-literal
 * only) is fully parsed and expanded lazily at first reference;
 * {@code <!ELEMENT>}/{@code <!ATTLIST>}/{@code <!NOTATION>} declarations are
 * recognised and skipped syntactically without being interpreted (DTD-driven
 * defaulting/validation is a later milestone), so real-world documents that
 * mix entity declarations with element/attribute declarations still work.
 * Parameter entities and external (SYSTEM/PUBLIC) general entities are
 * recognised syntactically but rejected with a clear "not supported" fatal
 * error on reference, rather than silently mishandled. See the "M4" section
 * near the bottom of this class for the implementation, and
 * {@link #expandGeneralEntityInContent}/{@link
 * #expandGeneralEntityInAttributeValue} in particular for how entity
 * expansion reuses this class's own suspend/resume machinery instead of
 * needing new machinery: an entity's replacement text is always fully
 * buffered in memory by the time it can be referenced, so "expanding" it is
 * just swapping {@link #buf}/{@link #pos}/{@link #limit} to a private array
 * and recursively calling {@link #scan()} (for content, which may contain
 * markup) or walking it directly (for attribute values, which never do).
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

    // ===== M4: DOCTYPE / internal general entities (cold path) =====

    /** True once the first (root) start tag has been seen - used both to
     *  reject a DOCTYPE appearing after it and to know when to stop
     *  expecting one. */
    private boolean rootStarted;
    private boolean doctypeSeen;
    private String doctypeName;

    /** Coarse resumable mode, mirroring {@link #inStartTag}: true while
     *  scanning the internal subset of a DOCTYPE declaration (see {@link
     *  #scanDoctypeSubset()}) - {@link #scan()}'s main loop re-enters that
     *  method directly on resume, rather than re-parsing from "<!DOCTYPE". */
    private boolean inDoctype;
    /** True once the internal subset's closing ']' has been consumed and
     *  only the trailing {@code S? '>'} remains - see {@link
     *  #scanDoctypeSubset()}. Needed because that trailing check can itself
     *  underflow and be resumed; without this flag, a resumed call would
     *  re-enter the declaration loop and misinterpret the leftover '>' as
     *  the start of a new (malformed) declaration. */
    private boolean doctypeSubsetClosed;
    private String doctypeNamePending;
    private HashMap<String, String> doctypePendingEntities;
    private HashSet<String> doctypePendingExternalNames;

    /** Internal general entities declared in the DOCTYPE's internal subset:
     *  name -> raw replacement text. Character references in the text are
     *  already expanded to literal characters at declaration time; predefined
     *  entity references and nested general entity references are kept as
     *  literal {@code &name;} text and resolved lazily, at first use, by
     *  simply re-scanning this stored text through the same machinery used
     *  for top-level content/attribute-value scanning (see
     *  {@link #expandGeneralEntityInContent} / {@link
     *  #expandGeneralEntityInAttributeValue}). Fully populated before any
     *  reference can occur, since the internal subset always finishes
     *  parsing before the root element (and therefore before any content or
     *  attribute value that could reference an entity) begins - this also
     *  means forward references within the subset (one entity's declared
     *  value naming another entity declared later) resolve correctly without
     *  needing the old architecture's lazy/list-of-tokens representation. */
    private final HashMap<String, String> generalEntities = new HashMap<String, String>();

    /** Names declared via an external ID (SYSTEM/PUBLIC) rather than a quoted
     *  literal - recognised syntactically so a later reference produces a
     *  clear "not supported" error rather than "not declared", but never
     *  fetched or expanded (out of scope for this milestone). */
    private final HashSet<String> externalEntityNames = new HashSet<String>();

    /** Names of entities currently being expanded, used for the "No
     *  Recursion" WFC (self- or mutually-referential entities). A linear
     *  scan is fine - this is never more than a few names deep in practice. */
    private final ArrayList<String> entityExpansionStack = new ArrayList<String>();

    private static final int MAX_ENTITY_EXPANSIONS = 100_000;
    private int entityExpansionCount;

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
        if (inStartTag || inAttributeValue || inDoctype || !elementStack.isEmpty()) {
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
            if (inDoctype) {
                if (!scanDoctypeSubset()) {
                    return;
                }
                inDoctype = false;
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
            // buf[pos] == '&'. The pending "runStart..ampPos" text (if any)
            // is confirmed, real buffered data regardless of how the entity
            // itself resolves, so it must be emitted (or accounted for via
            // contentRunOpen) unconditionally, before even attempting to
            // decode the entity - not after. A prior version checked
            // decodeEntityRef()'s status first and only emitted this text in
            // the two "resolved" branches; when decodeEntityRef needed more
            // data, pos was rewound to ampPos (past this text) having never
            // emitted it, silently losing it on the next receive() call's
            // compaction. Found via the M4 chunk-fuzzing differential test.
            int ampPos = pos;
            if (insideDocument && ampPos > runStart) {
                handler.characters(CharBuffer.wrap(buf, runStart, ampPos - runStart), false);
                contentRunOpen = true;
            }
            int refStatus = decodeEntityRef();
            if (refStatus == REF_NEED_MORE) {
                pos = ampPos;
                return false;
            }
            if (refStatus == REF_GENERAL_ENTITY) {
                // A general entity reference is a hard run boundary - unlike
                // char/predefined refs (always plain text, safely coalesced
                // into the surrounding run), its replacement text may itself
                // contain markup, so whatever text preceded it must be
                // closed off definitively (end=true) before the entity's
                // own, entirely separate events fire.
                if (insideDocument && contentRunOpen) {
                    handler.characters(EMPTY_BUFFER, true);
                }
                contentRunOpen = false;
                expandGeneralEntityInContent(pendingEntityName);
                continue;
            }
            CharBuffer decoded = lastDecodedRef;
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

    private static final int REF_NEED_MORE = 0;
    private static final int REF_DECODED = 1;
    private static final int REF_GENERAL_ENTITY = 2;

    /** Set by {@link #decodeEntityRef()} when it returns {@link #REF_DECODED} -
     *  the CharBuffer to emit. */
    private CharBuffer lastDecodedRef;

    /** Set by {@link #decodeEntityRef()} when it returns {@link
     *  #REF_GENERAL_ENTITY} - the (not predefined) entity name found, with
     *  {@code pos} already advanced past the whole {@code &name;}. */
    private String pendingEntityName;

    /**
     * Attempts to decode one entity/character reference starting at
     * {@code buf[pos]} (which must be '&amp;'). Returns one of:
     * <ul>
     * <li>{@link #REF_NEED_MORE} - not yet enough buffered data to be sure;
     * {@code pos} is left unchanged and the caller must rewind further back
     * to the start of whatever construct is being scanned and wait for more
     * data;</li>
     * <li>{@link #REF_DECODED} - {@code pos} advanced past the trailing ';',
     * {@link #lastDecodedRef} set to the CharBuffer to emit: for one of the
     * five predefined entities, the corresponding static, invariant, shared
     * buffer (rewound before return) - its content never changes, so it is
     * always safe to reference, no matter how long a consumer holds onto it;
     * for a numeric character reference, {@link #numericRefBuffer}
     * (repositioned before return) - reused across every numeric reference in
     * the document, so {@link XMLHandler#saveBuffers()} fires immediately
     * before repositioning it;</li>
     * <li>{@link #REF_GENERAL_ENTITY} - {@code pos} advanced past the
     * trailing ';', {@link #pendingEntityName} set to the referenced name;
     * the caller is responsible for expanding it (see {@link
     * #expandGeneralEntityInContent} / {@link
     * #expandGeneralEntityInAttributeValue}) - unlike the other two cases,
     * this is not "just a buffer to emit", since the replacement text may
     * itself require further scanning (in content, it may contain markup).</li>
     * </ul>
     */
    private int decodeEntityRef() throws SAXException {
        int p = pos + 1;
        if (p >= limit) {
            return REF_NEED_MORE;
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
                return REF_NEED_MORE;
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
            lastDecodedRef = numericRefBuffer;
            return REF_DECODED;
        }

        int nameStart = p;
        while (p < limit && isNameChar(buf[p])) {
            p++;
        }
        if (p >= limit) {
            return REF_NEED_MORE;
        }
        if (p == nameStart || buf[p] != ';') {
            throw handler.fatalError("Malformed entity reference");
        }
        int len = p - nameStart;
        CharBuffer predefined = matchPredefined(buf, nameStart, len);
        if (predefined == null) {
            pendingEntityName = new String(buf, nameStart, len);
            pos = p + 1;
            return REF_GENERAL_ENTITY;
        }
        pos = p + 1;
        predefined.rewind();
        lastDecodedRef = predefined;
        return REF_DECODED;
    }

    /** Matches a name against the five predefined entity names, returning the
     *  corresponding static buffer or null. Shared between {@link
     *  #decodeEntityRef()} (scanning {@link #buf}) and {@link
     *  #expandGeneralEntityInAttributeValue} (scanning a standalone
     *  {@code char[]}) - hence the explicit array/range parameters rather
     *  than reading {@link #buf} directly. */
    private static CharBuffer matchPredefined(char[] arr, int start, int len) {
        if (len == 3 && arr[start] == 'a' && arr[start + 1] == 'm' && arr[start + 2] == 'p') {
            return PREDEFINED_AMP;
        } else if (len == 2 && arr[start] == 'l' && arr[start + 1] == 't') {
            return PREDEFINED_LT;
        } else if (len == 2 && arr[start] == 'g' && arr[start + 1] == 't') {
            return PREDEFINED_GT;
        } else if (len == 4 && arr[start] == 'a' && arr[start + 1] == 'p'
                && arr[start + 2] == 'o' && arr[start + 3] == 's') {
            return PREDEFINED_APOS;
        } else if (len == 4 && arr[start] == 'q' && arr[start + 1] == 'u'
                && arr[start + 2] == 'o' && arr[start + 3] == 't') {
            return PREDEFINED_QUOT;
        }
        return null;
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
        if (!rootStarted) {
            if (doctypeSeen && !qName.equals(doctypeName)) {
                throw handler.fatalError("Document root element \"" + qName
                        + "\" does not match DOCTYPE name \"" + doctypeName + "\"");
            }
            rootStarted = true;
        }
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
            // buf[pos] == '&'. See the matching comment in scanContent() -
            // the pending "runStart..ampPos" text must be emitted
            // unconditionally, before attempting to decode the entity, or a
            // decodeEntityRef() that needs more data silently loses it on
            // rewind.
            int ampPos = pos;
            if (ampPos > runStart) {
                handler.attributeValueContent(CharBuffer.wrap(buf, runStart, ampPos - runStart), false);
                attrValueRunOpen = true;
            }
            int refStatus = decodeEntityRef();
            if (refStatus == REF_NEED_MORE) {
                pos = ampPos;
                return false;
            }
            CharBuffer decoded;
            if (refStatus == REF_GENERAL_ENTITY) {
                // Attribute values are always flat character data (no
                // markup, ever), so - unlike in content - a general entity's
                // expansion is simply more text to fold into the same
                // streamed value: fully flatten it (recursively, in case it
                // references further entities) into one String first, since
                // computing it requires no scanning of buf/pos/limit at all.
                decoded = CharBuffer.wrap(expandGeneralEntityInAttributeValue(pendingEntityName));
            } else {
                decoded = lastDecodedRef;
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
        } else if (buf[p] == 'D') {
            return scanDoctype(tagStart);
        } else {
            throw handler.fatalError("Malformed markup declaration");
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

    // ===== M4: DOCTYPE / internal general entities (cold path) =====
    //
    // Scope, deliberately narrow (see class Javadoc): the internal subset's
    // <!ENTITY Name "value"> declarations (general, internal, quoted-literal
    // only) are fully parsed; <!ELEMENT>/<!ATTLIST>/<!NOTATION> declarations
    // are recognised and skipped syntactically (their content is opaque to
    // this milestone - DTD-driven defaulting/validation is M5's job), so
    // documents that mix entity declarations with element/attribute
    // declarations (common in the wild) still work. Parameter entities and
    // external (SYSTEM/PUBLIC) general entities are recognised but rejected
    // with a clear "not supported" error rather than silently misbehaving.
    //
    // The whole DOCTYPE declaration (keyword through the closing '>',
    // including the entire internal subset) is one atomic construct for
    // underflow purposes, exactly like every other atomic construct in this
    // class (comments, PIs, CDATA, start tags): on running out of buffered
    // data anywhere inside it, pos rewinds to the '<' of "<!DOCTYPE" and the
    // whole thing is retried from scratch once more data arrives. Nothing is
    // committed to generalEntities/externalEntityNames until the entire
    // declaration has been scanned successfully in one pass (collected into
    // local maps first) - see scanDoctype.

    private static final int KW_MATCH = 0;
    private static final int KW_NO_MATCH = 1;
    private static final int KW_NEED_MORE = 2;

    private static final char[] DOCTYPE_MARKER = "<!DOCTYPE".toCharArray();
    private static final char[] SYSTEM_MARKER = "SYSTEM".toCharArray();
    private static final char[] PUBLIC_MARKER = "PUBLIC".toCharArray();
    private static final char[] NDATA_MARKER = "NDATA".toCharArray();
    private static final char[] ENTITY_MARKER = "<!ENTITY".toCharArray();

    /** Matches {@code marker} against {@code buf} starting at {@code p}. */
    private int matchKeyword(int p, char[] marker) {
        int matchLen = Math.min(marker.length, limit - p);
        for (int i = 0; i < matchLen; i++) {
            if (buf[p + i] != marker[i]) {
                return KW_NO_MATCH;
            }
        }
        if (p + marker.length > limit) {
            return KW_NEED_MORE;
        }
        return KW_MATCH;
    }

    private int skipOptionalWhitespace(int p) {
        while (p < limit && isWs(buf[p])) {
            p++;
        }
        return p;
    }

    /** Returns the position past a quoted literal starting at {@code p}, or
     *  -1 if underflow. Throws if {@code buf[p]} is not a quote. */
    private int findQuotedLiteralEnd(int p) throws SAXException {
        if (p >= limit) {
            return -1;
        }
        char q = buf[p];
        if (q != '"' && q != '\'') {
            throw handler.fatalError("Expected quoted literal");
        }
        p++;
        while (true) {
            if (p >= limit) {
                return -1;
            }
            if (buf[p] == q) {
                return p + 1;
            }
            p++;
        }
    }

    /** Skips a {@code SYSTEM "..."} or {@code PUBLIC "..." "..."} external
     *  ID starting at {@code p}. Returns the position past it, or -1 if
     *  underflow. The literals themselves are discarded - nothing is ever
     *  fetched (out of scope for this milestone). */
    private int skipExternalId(int p) throws SAXException {
        boolean isPublic;
        int m = matchKeyword(p, SYSTEM_MARKER);
        if (m == KW_NEED_MORE) {
            return -1;
        }
        if (m == KW_MATCH) {
            isPublic = false;
            p += SYSTEM_MARKER.length;
        } else {
            m = matchKeyword(p, PUBLIC_MARKER);
            if (m == KW_NEED_MORE) {
                return -1;
            }
            if (m != KW_MATCH) {
                throw handler.fatalError("Malformed external ID");
            }
            isPublic = true;
            p += PUBLIC_MARKER.length;
        }
        int ws = p;
        p = skipOptionalWhitespace(p);
        if (p >= limit) {
            return -1;
        }
        if (p == ws) {
            throw handler.fatalError("Malformed external ID");
        }

        if (isPublic) {
            int r = findQuotedLiteralEnd(p);
            if (r < 0) {
                return -1;
            }
            p = r;
            int ws2 = p;
            p = skipOptionalWhitespace(p);
            if (p >= limit) {
                return -1;
            }
            if (p == ws2) {
                throw handler.fatalError("Malformed external ID");
            }
        }
        return findQuotedLiteralEnd(p);
    }

    /** Skips a {@code <!ELEMENT ...>}/{@code <!ATTLIST ...>}/{@code
     *  <!NOTATION ...>} declaration starting at {@code p} (the '&lt;')
     *  without interpreting its content - just finds the matching '>',
     *  treating quoted sections (default attribute values, etc.) as opaque
     *  so an embedded '>' inside one does not terminate early. Returns the
     *  position past the '>', or -1 if underflow. */
    private int skipMarkupDeclaration(int p) throws SAXException {
        p += 2; // skip "<!"
        while (true) {
            if (p >= limit) {
                return -1;
            }
            char c = buf[p];
            if (c == '"' || c == '\'') {
                int r = findQuotedLiteralEnd(p);
                if (r < 0) {
                    return -1;
                }
                p = r;
                continue;
            }
            if (c == '>') {
                return p + 1;
            }
            p++;
        }
    }

    /** Decodes one character reference {@code &#...;}/{@code &#x...;}
     *  starting at {@code buf[q]} into {@code sb}, for use while scanning an
     *  EntityValue at declaration time (always a fully-buffered, one-shot
     *  parse of the current construct - see {@link #scanEntityDeclaration}).
     *  Returns the position past the trailing ';', or -1 if underflow. */
    private int decodeCharRefInto(StringBuilder sb, int q) throws SAXException {
        int p = q + 2; // skip "&#"
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
        sb.appendCodePoint(codePoint);
        return p + 1;
    }

    /**
     * Parses one {@code <!ENTITY ...>} declaration, {@code p} positioned
     * just past the "<!ENTITY" keyword. On success, records the declaration
     * into {@code pendingEntities}/{@code pendingExternalNames} (first
     * declaration wins for a repeated name, per XML 4.2) and returns the
     * position past the closing '>'; returns -1 on underflow. A parameter
     * entity declaration ('%' right after "<!ENTITY") is rejected outright -
     * see class Javadoc for scope.
     */
    private int scanEntityDeclaration(int p, HashMap<String, String> pendingEntities,
            HashSet<String> pendingExternalNames) throws SAXException {
        int ws = p;
        p = skipOptionalWhitespace(p);
        if (p >= limit) {
            return -1;
        }
        if (p == ws) {
            throw handler.fatalError("Malformed entity declaration");
        }

        if (buf[p] == '%') {
            throw handler.fatalError("Parameter entity declarations are not supported in this milestone");
        }

        int nameStart = p;
        while (p < limit && isNameChar(buf[p])) {
            p++;
        }
        if (p >= limit) {
            return -1;
        }
        if (p == nameStart) {
            throw handler.fatalError("Malformed entity declaration");
        }
        String name = new String(buf, nameStart, p - nameStart);

        int ws2 = p;
        p = skipOptionalWhitespace(p);
        if (p >= limit) {
            return -1;
        }
        if (p == ws2) {
            throw handler.fatalError("Malformed entity declaration");
        }

        if (buf[p] == '"' || buf[p] == '\'') {
            char quote = buf[p];
            StringBuilder sb = new StringBuilder();
            int q = p + 1;
            while (true) {
                if (q >= limit) {
                    return -1;
                }
                char c = buf[q];
                if (c == quote) {
                    break;
                }
                if (c == '&' && q + 1 < limit && buf[q + 1] == '#') {
                    int r = decodeCharRefInto(sb, q);
                    if (r < 0) {
                        return -1;
                    }
                    q = r;
                    continue;
                }
                if (c == '&' && q + 1 >= limit) {
                    // Could be the start of "&#..." straddling the buffer
                    // boundary - can't yet tell, must wait for more data.
                    return -1;
                }
                sb.append(c);
                q++;
            }
            p = q + 1; // past closing quote

            p = skipOptionalWhitespace(p);
            if (p >= limit) {
                return -1;
            }
            if (buf[p] != '>') {
                throw handler.fatalError("Malformed entity declaration");
            }
            p++;

            if (!pendingEntities.containsKey(name) && !generalEntities.containsKey(name)) {
                pendingEntities.put(name, sb.toString());
            }
            return p;
        }

        // External entity (SYSTEM or PUBLIC): recognised syntactically and
        // recorded by name only - not fetched, not expandable (see class
        // Javadoc).
        int r = skipExternalId(p);
        if (r < 0) {
            return -1;
        }
        p = r;

        p = skipOptionalWhitespace(p);
        if (p >= limit) {
            return -1;
        }
        if (buf[p] != '>') {
            int m = matchKeyword(p, NDATA_MARKER);
            if (m == KW_NEED_MORE) {
                return -1;
            }
            if (m != KW_MATCH) {
                throw handler.fatalError("Malformed entity declaration");
            }
            p += NDATA_MARKER.length;
            int wsN = p;
            p = skipOptionalWhitespace(p);
            if (p >= limit) {
                return -1;
            }
            if (p == wsN) {
                throw handler.fatalError("Malformed entity declaration");
            }
            int ndataNameStart = p;
            while (p < limit && isNameChar(buf[p])) {
                p++;
            }
            if (p >= limit) {
                return -1;
            }
            if (p == ndataNameStart) {
                throw handler.fatalError("Malformed entity declaration");
            }
            p = skipOptionalWhitespace(p);
            if (p >= limit) {
                return -1;
            }
            if (buf[p] != '>') {
                throw handler.fatalError("Malformed entity declaration");
            }
        }
        p++;

        if (!pendingEntities.containsKey(name) && !generalEntities.containsKey(name)) {
            pendingExternalNames.add(name);
        }
        return p;
    }

    /**
     * Parses {@code <!DOCTYPE ... >}, including an optional external ID
     * (recognised, not fetched) and an optional internal subset, as one
     * atomic construct (see class Javadoc). Must appear before the root
     * element and at most once.
     */
    /**
     * Parses {@code "<!DOCTYPE" Name (S ExternalID)? S?} - everything up to
     * (but not including) an internal subset or the closing '&gt;', all of
     * which is side-effect-free (no handler calls), so - unlike the internal
     * subset itself, see {@link #scanDoctypeSubset()} - this part is safe to
     * treat as one atomic retry-whole-on-underflow construct exactly like
     * every other atomic construct in this class.
     */
    private boolean scanDoctype(int tagStart) throws SAXException {
        int km = matchKeyword(tagStart, DOCTYPE_MARKER);
        if (km == KW_NEED_MORE) {
            pos = tagStart;
            return false;
        }
        if (km != KW_MATCH) {
            throw handler.fatalError("Malformed markup declaration");
        }
        if (rootStarted) {
            throw handler.fatalError("DOCTYPE declaration must precede the root element");
        }
        if (doctypeSeen) {
            throw handler.fatalError("Only one DOCTYPE declaration is allowed");
        }

        int p = tagStart + DOCTYPE_MARKER.length;
        int ws = p;
        p = skipOptionalWhitespace(p);
        if (p >= limit) {
            pos = tagStart;
            return false;
        }
        if (p == ws) {
            throw handler.fatalError("Malformed DOCTYPE declaration");
        }

        int nameStart = p;
        while (p < limit && isNameChar(buf[p])) {
            p++;
        }
        if (p >= limit) {
            pos = tagStart;
            return false;
        }
        if (p == nameStart) {
            throw handler.fatalError("Malformed DOCTYPE declaration");
        }
        String name = new String(buf, nameStart, p - nameStart);

        p = skipOptionalWhitespace(p);
        if (p >= limit) {
            pos = tagStart;
            return false;
        }

        if (buf[p] == 'S' || buf[p] == 'P') {
            int r = skipExternalId(p);
            if (r < 0) {
                pos = tagStart;
                return false;
            }
            p = r;
            p = skipOptionalWhitespace(p);
            if (p >= limit) {
                pos = tagStart;
                return false;
            }
        }

        if (buf[p] == '[') {
            // From here on, the internal subset may call scanComment()/
            // scanPI() - real, irreversible handler.comment()/
            // processingInstruction() calls - so this can no longer be an
            // atomic whole-retry construct: a later underflow deeper in the
            // subset must not cause an already-fired comment/PI to fire
            // again. Hand off to the properly resumable scanDoctypeSubset(),
            // which - like scanAttributesAndTagEnd()/inStartTag - tracks its
            // own progress via pos and the doctypeXxx fields rather than
            // rewinding to tagStart.
            doctypeNamePending = name;
            doctypePendingEntities = new HashMap<String, String>();
            doctypePendingExternalNames = new HashSet<String>();
            pos = p + 1;
            inDoctype = true;
            if (!scanDoctypeSubset()) {
                return false;
            }
            inDoctype = false;
            return true;
        }

        // No internal subset: S? '>' remains side-effect-free, atomic.
        if (buf[p] != '>') {
            throw handler.fatalError("Malformed DOCTYPE declaration");
        }
        p++;
        doctypeSeen = true;
        doctypeName = name;
        pos = p;
        return true;
    }

    /**
     * Resumable scan of the internal subset's declarations, from just past
     * the opening '[' up to and including the closing '&gt;'. Called both
     * fresh (from {@link #scanDoctype}, right after seeing '[') and again by
     * {@link #scan()}'s main loop whenever {@link #inDoctype} is still true
     * at the start of a {@link #receive(CharBuffer)} call. Unlike every
     * other atomic construct in this class, this cannot simply rewind to a
     * fixed start point on underflow, because {@code <!--comment-->} and
     * {@code <?PI?>} declarations within the subset are not atomic
     * themselves from this method's point of view - they call
     * {@link #scanComment}/{@link #scanPI}, which fire real {@link
     * XMLHandler#comment}/{@link XMLHandler#processingInstruction} events as
     * a side effect. Instead, {@link #pos} only ever advances past a
     * declaration once it has been fully, successfully processed (comment/PI
     * emitted, entity recorded, or other declaration skipped) - exactly the
     * same "confirmed progress, resume from here, never rewind past it"
     * discipline {@link #scanContent()}/{@link
     * #scanAttributeValueStreaming()} use for the same reason. {@link
     * #doctypePendingEntities}/{@link #doctypePendingExternalNames} (promoted
     * to fields rather than locals, precisely so a resumed call picks up
     * where the previous one left off) are only merged into {@link
     * #generalEntities}/{@link #externalEntityNames} once the whole subset -
     * and the declaration's closing '&gt;' - has been confirmed.
     */
    private boolean scanDoctypeSubset() throws SAXException {
        if (!doctypeSubsetClosed) {
            while (true) {
                pos = skipOptionalWhitespace(pos);
                if (pos >= limit) {
                    return false;
                }
                char c = buf[pos];
                if (c == ']') {
                    pos++;
                    doctypeSubsetClosed = true;
                    break;
                }
                if (c == '%') {
                    throw handler.fatalError("Parameter entity references are not supported in this milestone");
                }
                if (c != '<') {
                    throw handler.fatalError("Malformed internal DTD subset");
                }
                if (pos + 1 >= limit) {
                    return false;
                }
                char c2 = buf[pos + 1];
                if (c2 == '?') {
                    if (!scanPI(pos)) {
                        return false;
                    }
                } else if (c2 == '!') {
                    // "<!" - could be a comment ("<!--"), <!ENTITY>, or
                    // another declaration to skip - the disambiguating char
                    // is the one AFTER "<!" (buf[pos+2]), matching
                    // scanBangMarkup's own dispatch convention.
                    if (pos + 2 >= limit) {
                        return false;
                    }
                    if (buf[pos + 2] == '-') {
                        if (!scanComment(pos)) {
                            return false;
                        }
                    } else {
                        int em = matchKeyword(pos, ENTITY_MARKER);
                        if (em == KW_NEED_MORE) {
                            return false;
                        }
                        if (em == KW_MATCH) {
                            int r = scanEntityDeclaration(pos + ENTITY_MARKER.length, doctypePendingEntities,
                                    doctypePendingExternalNames);
                            if (r < 0) {
                                return false;
                            }
                            pos = r;
                        } else {
                            int r = skipMarkupDeclaration(pos);
                            if (r < 0) {
                                return false;
                            }
                            pos = r;
                        }
                    }
                } else {
                    throw handler.fatalError("Malformed internal DTD subset");
                }
            }
        }

        // doctypeSubsetClosed is true here (either just now, or from a prior
        // call that got this far and then underflowed waiting for '>') -
        // this must not re-enter the declaration loop above, or a leftover
        // '>' sitting alone in a freshly-compacted buf (after a receive()
        // boundary landing exactly between ']' and '>') would be
        // misinterpreted as the start of a new, malformed declaration - a
        // real bug caught via chunkSize=1 testing (see
        // testDoctypeAndEntitiesSplitAcrossReceiveBoundary_chunkSize1).
        pos = skipOptionalWhitespace(pos);
        if (pos >= limit) {
            return false;
        }
        if (buf[pos] != '>') {
            throw handler.fatalError("Malformed DOCTYPE declaration");
        }
        pos++;

        generalEntities.putAll(doctypePendingEntities);
        externalEntityNames.addAll(doctypePendingExternalNames);
        doctypeSeen = true;
        doctypeName = doctypeNamePending;
        doctypePendingEntities = null;
        doctypePendingExternalNames = null;
        doctypeNamePending = null;
        doctypeSubsetClosed = false;
        return true;
    }

    /** Validates that {@code name} may be referenced now: declared (as a
     *  general, non-external entity), not currently being expanded (WFC "No
     *  Recursion"), and within the total-expansion-count guard (a simple
     *  mitigation for entity-expansion amplification, aka "billion laughs" -
     *  not a substitute for a real resource budget, but enough for this
     *  milestone's scope). */
    private void checkEntityReferenceable(String name) throws SAXException {
        boolean isGeneral = generalEntities.containsKey(name);
        boolean isExternal = externalEntityNames.contains(name);
        if (!isGeneral && !isExternal) {
            throw handler.fatalError("Entity \"" + name + "\" was not declared");
        }
        if (isExternal) {
            throw handler.fatalError("External entities are not supported in this milestone: &" + name + ";");
        }
        if (entityExpansionStack.contains(name)) {
            throw handler.fatalError("Recursive entity reference: &" + name + ";");
        }
        if (++entityExpansionCount > MAX_ENTITY_EXPANSIONS) {
            throw handler.fatalError("Entity expansion limit exceeded");
        }
    }

    /**
     * Expands a general entity reference encountered in content. The entity's
     * replacement text is always fully in memory (collected while parsing
     * the internal subset, which always completes before the root element -
     * and therefore before any reference - can occur), so this never needs
     * genuine suspend/resume: {@link #buf}/{@link #pos}/{@link #limit} are
     * simply swapped to a private array holding the replacement text, {@link
     * #scan()} is re-entered exactly as the top-level driver does, and the
     * outer state is restored once the swapped-in buffer is fully drained.
     * {@link #elementStack} is deliberately <em>not</em> swapped - it is
     * shared across the boundary so an element opened inside the entity's
     * replacement text (entities used in content may contain markup, not
     * just character data) is matched by its end tag within that same
     * replacement text, and the depth check below enforces exactly that
     * ("entity boundaries must nest within element boundaries").
     * <p>
     * Because the swapped-in buffer will never receive more data, a
     * {@code scan()} that returns leaving {@code pos < limit} (any
     * sub-production still rewound at its own start, or {@link #inStartTag}/
     * {@link #inAttributeValue} still true) unambiguously means the
     * replacement text is not well-formed - there is no "wait for more data"
     * case here, unlike every other use of these fields.
     */
    private void expandGeneralEntityInContent(String name) throws SAXException {
        checkEntityReferenceable(name);
        String replacement = generalEntities.get(name);

        entityExpansionStack.add(name);
        char[] savedBuf = buf;
        int savedPos = pos;
        int savedLimit = limit;
        boolean savedContentRunOpen = contentRunOpen;
        int stackDepthAtEntry = elementStack.size();

        buf = replacement.toCharArray();
        pos = 0;
        limit = buf.length;
        contentRunOpen = false;
        try {
            scan();
            if (pos != limit || inStartTag || inAttributeValue) {
                throw handler.fatalError("Entity \"" + name + "\" replacement text is not well-formed");
            }
            if (elementStack.size() != stackDepthAtEntry) {
                throw handler.fatalError("Entity \"" + name + "\" replacement text is not well-formed: "
                        + "element boundaries must nest within entity boundaries");
            }
            if (contentRunOpen) {
                // The replacement text's final characters() run ended
                // exactly at the entity's own boundary (not a receive()
                // underflow - there is no more data coming for this buffer,
                // ever) - close it out, mirroring the same empty-closing-
                // event pattern scanContent() uses at a markup boundary.
                handler.characters(EMPTY_BUFFER, true);
            }
        } finally {
            buf = savedBuf;
            pos = savedPos;
            limit = savedLimit;
            contentRunOpen = savedContentRunOpen;
            entityExpansionStack.remove(entityExpansionStack.size() - 1);
        }
    }

    /**
     * Expands a general entity reference encountered in an attribute value
     * into flat text (recursively, in case the replacement itself references
     * further entities) - attribute values never contain markup, so unlike
     * {@link #expandGeneralEntityInContent} this never needs to re-enter
     * {@link #scan()}; it is a direct character-by-character walk of the
     * (already fully-buffered) replacement text. A literal '&lt;' anywhere in
     * the expansion is a WFC violation ("No {@literal <} in Attribute
     * Values"), checked here since it would not otherwise be caught (this
     * text never passes through {@link #scanAttributeValueStreaming()}'s own
     * checks). Numeric character references never appear in a stored
     * entity's replacement text - they are already resolved to literal
     * characters at declaration time (see {@link #scanEntityDeclaration}) -
     * so only predefined and nested general entity references need handling
     * here.
     */
    private String expandGeneralEntityInAttributeValue(String name) throws SAXException {
        checkEntityReferenceable(name);
        String replacement = generalEntities.get(name);
        entityExpansionStack.add(name);
        try {
            char[] rbuf = replacement.toCharArray();
            int rlen = rbuf.length;
            StringBuilder sb = new StringBuilder(rlen);
            int q = 0;
            while (q < rlen) {
                char c = rbuf[q];
                if (c == '<') {
                    throw handler.fatalError(
                            "'<' is not allowed in an attribute value (via entity \"" + name + "\")");
                }
                if (c != '&') {
                    sb.append(c);
                    q++;
                    continue;
                }
                int nameStart = q + 1;
                int p = nameStart;
                while (p < rlen && isNameChar(rbuf[p])) {
                    p++;
                }
                if (p >= rlen || rbuf[p] != ';') {
                    throw handler.fatalError("Malformed entity reference in entity \"" + name + "\"");
                }
                CharBuffer predef = matchPredefined(rbuf, nameStart, p - nameStart);
                if (predef != null) {
                    predef.rewind();
                    sb.append(predef);
                } else {
                    String refName = new String(rbuf, nameStart, p - nameStart);
                    sb.append(expandGeneralEntityInAttributeValue(refName));
                }
                q = p + 1;
            }
            return sb.toString();
        } finally {
            entityExpansionStack.remove(entityExpansionStack.size() - 1);
        }
    }

}

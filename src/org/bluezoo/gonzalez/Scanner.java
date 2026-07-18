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

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.CharBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import org.xml.sax.EntityResolver;
import org.xml.sax.InputSource;
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
 * <b>DTD defaulting/normalisation/ignorableWhitespace (M5)</b> and
 * <b>real name-character classes and character-reference legality
 * (M6)</b>. M5 added {@link DTDModel}-driven attribute defaulting, type-aware
 * attribute value normalisation, and content-type-driven {@link
 * XMLHandler#ignorableWhitespace}, deliberately scoped to just those three
 * (full content-model conformance checking and VC validity checks were
 * surfaced as a much larger, separate tier and deferred - see
 * ASYNC-PIPELINE.md's M5 section). M6 replaced the crude "any non-ASCII
 * character is a legal name character" approximation M1-M5 used with the
 * real Unicode {@link #isNameChar}/{@link #isNameStartChar} ranges (XML 1.0
 * 5th edition's simplified production, identical in XML 1.1), and added
 * real legality checking for numeric character references ({@link
 * #isLegalCharRefCodePoint}, XML-1.1-aware via the {@link #xml11}
 * constructor flag). Both M6 changes were chosen specifically because they
 * add no new hot-path cost: NameChar/NameStartChar checking replaces an
 * existing per-character check in each of this class's ~14 name-scanning
 * call sites rather than adding a new one, and character-reference
 * legality is already cold-path (only reached once {@code &#} has been
 * seen). Three further "M6" items from the milestone's original
 * description - literal-character restricted-char rejection in content/
 * attribute values, the {@code "]]>"} WFC in content, and first-character-
 * must-be-NameStartChar (not just NameChar) enforcement - were deliberately
 * <b>not</b> done this milestone: each would need a genuinely new check
 * inside the hot content/attribute-value scanning loops (not just a more
 * accurate version of an existing one), and this session is currently under
 * a standing "no benchmarking until further along" instruction, so their
 * performance cost can't be measured and verified acceptable right now.
 * Flagged explicitly for the Conformance hardening phase rather than
 * silently skipped. {@code standalone} declaration semantics and NEL/LS
 * line-ending normalisation remain out of scope for the same reasons as
 * before (VC-territory per M5's precedent; ExternalEntityDecoder's job,
 * respectively).
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
class Scanner implements ByteDecoderTarget {

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

    /** M5: true while the attribute currently being scanned is declared
     *  with a non-CDATA type, meaning its value needs the type-dependent
     *  collapse normalisation (XML 3.3.3) applied - see {@link
     *  #emitAttributeValueContent}. Materializing the value defeats the
     *  zero-copy streaming path, but only for attributes an ATTLIST
     *  actually declares non-CDATA; the common CDATA/no-DTD case is
     *  untouched. {@link #normalizeBuilder} is a single reused field, not a
     *  pool, since only one attribute is ever being scanned at a time. */
    private boolean normalizingCurrentAttribute;
    private final StringBuilder normalizeBuilder = new StringBuilder();

    /** True if the most recent characters() emission for the run currently
     *  in progress had end=false and has not yet been closed with end=true -
     *  see {@link #scanContent()}. */
    private boolean contentRunOpen;

    /** WFC "no ']]>' in content, except to mark the end of a CDATA section":
     *  count of consecutive literal ']' characters just scanned in {@link
     *  #scanContent()}'s current literal run, so a ']' immediately followed
     *  by ']' then '>' - even split across a receive() boundary - is caught.
     *  Reset to 0 at every run boundary (markup, entity reference) - see
     *  {@link #scanContent()} - since the WFC only applies to a
     *  <em>literal</em>, contiguous occurrence in the source, not one
     *  assembled from unrelated adjacent runs or via entity expansion
     *  (writing {@code &gt;} instead of a literal '&gt;' right after "]]"
     *  is the spec-sanctioned way to avoid this WFC, and correctly never
     *  triggers it here, since decoded entity text never touches this
     *  counter). */
    private int contentBracketRun;

    /** WFC "Element Type Match" stack of interned qNames. */
    private final ArrayList<String> elementStack = new ArrayList<String>();

    /** M5: names of attributes specified on the start tag currently being
     *  scanned, reset once per {@link #scanStartTag} - lets {@link
     *  #applyAttributeDefaults} know which declared attributes were left
     *  unspecified and need a default injected. A small reference-equality
     *  linear scan rather than a {@code HashSet}: attribute counts per
     *  element are typically tiny (1-20), where a hash table's bucket/
     *  boxing overhead loses to a plain array scan - and every name that
     *  ever reaches this array is guaranteed {@link #namePool}-interned
     *  (both start-tag attribute names, in {@link
     *  #scanAttributesAndTagEnd}, and ATTLIST-declared names, in {@link
     *  #scanAttlistDeclaration}), so {@code ==} is a correct, not just
     *  faster, replacement for {@code equals()}. */
    private String[] seenAttributeNames = new String[8];
    private int seenAttributeNameCount;

    private void recordSeenAttributeName(String name) {
        if (seenAttributeNameCount == seenAttributeNames.length) {
            seenAttributeNames = Arrays.copyOf(seenAttributeNames, seenAttributeNames.length * 2);
        }
        seenAttributeNames[seenAttributeNameCount++] = name;
    }

    private boolean wasAttributeSeen(String name) {
        for (int i = 0; i < seenAttributeNameCount; i++) {
            if (seenAttributeNames[i] == name) {
                return true;
            }
        }
        return false;
    }

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
    /** True once the root element has closed - epilog WFCs ({@code Misc*}:
     *  only Comment/PI/whitespace allowed, WFC "One root element" rejects a
     *  second start tag) apply once this is true, exactly as they apply to
     *  the prolog (before {@link #rootStarted}) - both are "outside the
     *  document element" for this purpose, distinguished only for the
     *  "second root element" check. */
    private boolean rootEnded;
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
    private HashMap<String, String[]> doctypePendingExternalNames;
    private HashMap<String, String> doctypePendingParamEntities;
    private HashMap<String, String[]> doctypePendingParamExternalNames;

    /** The DOCTYPE's own external ID, if any, captured in {@link
     *  #scanDoctype} and consumed once the whole declaration - internal
     *  subset included, if present - has committed, by both of {@link
     *  #scanDoctype}'s and {@link #scanDoctypeSubset}'s completion paths.
     *  Null (the common case) means no external subset to fetch. */
    private String doctypeExternalPublicId;
    private String doctypeExternalSystemId;

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

    /** Entities declared via an external ID (SYSTEM/PUBLIC) rather than a
     *  quoted literal, keyed by name, value {@code {publicId, systemId}}
     *  (publicId may be null) - fetched lazily, at first reference in
     *  content (see {@link #expandGeneralEntityInContent}), matching how
     *  internal entities are already only resolved lazily. Referencing one
     *  from an <em>attribute value</em> is instead always a fatal WFC
     *  violation ("No External Entity References") - never fetched there,
     *  regardless of this map. */
    private final HashMap<String, String[]> externalEntityNames = new HashMap<String, String[]>();

    /** Parameter entities ({@code <!ENTITY % name "...">}), keyed by name -
     *  the same shape as {@link #generalEntities}/{@link #externalEntityNames}
     *  but a separate namespace (XML 4.1: general and parameter entity names
     *  are in distinct spaces, so the same name may denote both). Unlike
     *  general entities, a parameter entity reference ({@code %name;}) is
     *  resolved immediately, at the point it is encountered while scanning
     *  DTD declarations - never lazily - since its replacement text stands in
     *  for a sequence of complete declarations, not character data; see
     *  {@link #expandParameterEntityReference}. */
    private final HashMap<String, String> parameterEntities = new HashMap<String, String>();

    /** External parameter entities, keyed by name, value {@code {publicId,
     *  systemId}} - fetched (not lazily, since a reference always needs its
     *  replacement text right away to keep parsing) the same way an external
     *  general entity is, via {@link #fetchExternalResource}. */
    private final HashMap<String, String[]> parameterEntityExternalIds = new HashMap<String, String[]>();

    /** Names of parameter entities currently being expanded - the parameter-
     *  entity analogue of {@link #entityExpansionStack}, kept separate since
     *  general and parameter entity names occupy different namespaces. */
    private final ArrayList<String> parameterEntityExpansionStack = new ArrayList<String>();

    /** Names of entities currently being expanded, used for the "No
     *  Recursion" WFC (self- or mutually-referential entities). A linear
     *  scan is fine - this is never more than a few names deep in practice. */
    private final ArrayList<String> entityExpansionStack = new ArrayList<String>();

    private static final int MAX_ENTITY_EXPANSIONS = 100_000;
    private int entityExpansionCount;

    /** M6: XML 1.1 vs 1.0 mode, affecting only numeric character reference
     *  legality (see {@link #decodeEntityRef()}) - see class Javadoc "M6"
     *  section for why nothing else in this class needs it. Either supplied
     *  by the caller up front (every direct/test construction - the common
     *  case when the version is already known), or defaulted at
     *  construction and updated exactly once via {@link #setXml11} - the
     *  {@link ByteDecoderTarget} interface method {@link
     *  ExternalEntityDecoder} calls once it has resolved the XML/text
     *  declaration's version directly from the raw bytes, strictly before
     *  any real document content reaches this Scanner (the same timing
     *  {@code ExternalEntityDecoder} already uses for {@code Tokenizer};
     *  see {@code Parser.Pipeline}). Not final for that reason - {@link
     *  #setXml11} keeps {@link #contentStopTable}/{@link
     *  #quotAttrStopTable}/{@link #aposAttrStopTable} in sync whenever it
     *  changes. */
    private boolean xml11;

    /** Resolves external entity/DTD identifiers to actual bytes - see
     *  "external entity/DTD fetching" below. Null (the common case, and
     *  every caller before this capability existed) means "no external
     *  fetching capability at all": an external SYSTEM/PUBLIC identifier
     *  will still be recognised syntactically, but referencing it is
     *  rejected the same way an unresolvable one would be. */
    private final EntityResolver entityResolver;

    /** The document's own system identifier, for resolving a relative
     *  SYSTEM identifier against - see {@link #resolveAndOpen}. Null if
     *  unknown (e.g. parsing from an in-memory string with no URI at all). */
    private final String baseSystemId;

    /** Convenience constructor for XML 1.0 with no external fetching
     *  capability (the common case, and every caller before this
     *  milestone). */
    Scanner(XMLHandler handler) throws SAXException {
        this(handler, false);
    }

    /** Convenience constructor with no external fetching capability. */
    Scanner(XMLHandler handler, boolean xml11) throws SAXException {
        this(handler, xml11, null, null);
    }

    Scanner(XMLHandler handler, boolean xml11, EntityResolver entityResolver, String baseSystemId)
            throws SAXException {
        this.handler = handler;
        this.xml11 = xml11;
        this.entityResolver = entityResolver;
        this.baseSystemId = baseSystemId;
        this.buf = new char[INITIAL_CAPACITY];
        this.contentStopTable = xml11 ? CONTENT_STOP_XML11 : CONTENT_STOP_XML10;
        this.quotAttrStopTable = xml11 ? QUOT_ATTR_STOP_XML11 : QUOT_ATTR_STOP_XML10;
        this.aposAttrStopTable = xml11 ? APOS_ATTR_STOP_XML11 : APOS_ATTR_STOP_XML10;
        handler.startDocument();
    }

    /** Resolved from {@link #xml11} at construction, and re-resolved by
     *  {@link #setXml11} if it changes - see the {@code CONTENT_STOP_XML10}/
     *  {@code XML11} family's Javadoc. */
    private boolean[] contentStopTable;
    private boolean[] quotAttrStopTable;
    private boolean[] aposAttrStopTable;

    /**
     * Feeds more decoded character data to the scanner. May be called
     * multiple times; an incomplete construct at the end of one call is
     * resumed (or, for atomic constructs, retried from its start) on the
     * next. Fires {@link XMLHandler#saveBuffers()} once, after all
     * currently-available data has been scanned, since the scan buffer may
     * be compacted/reused before the next call - see XMLHandler's class
     * Javadoc.
     */
    @Override
    public void receive(CharBuffer data) throws SAXException {
        append(data);
        scan();
        handler.saveBuffers();
    }

    /**
     * Signals end of input. Reports a fatal error if the document ends
     * mid-construct or with unclosed elements.
     */
    @Override
    public void close() throws SAXException {
        if (inStartTag || inAttributeValue || inDoctype || !elementStack.isEmpty()) {
            throw handler.fatalError("Document ended unexpectedly (unclosed element or tag)");
        }
        if (!rootStarted) {
            throw handler.fatalError("Document must contain a root element");
        }
        handler.endDocument();
    }

    @Override
    public SAXException fatalError(String message) throws SAXException {
        return handler.fatalError(message);
    }

    /** {@link ByteDecoderTarget#setXml11} - see {@link #xml11}'s own
     *  Javadoc for the timing guarantee this relies on (called strictly
     *  before any real document content, so the mid-flight state this
     *  mutates - the three derived lookup tables - is never observed in an
     *  inconsistent state by a concurrently-in-progress scan). */
    @Override
    public void setXml11(boolean xml11) {
        this.xml11 = xml11;
        this.contentStopTable = xml11 ? CONTENT_STOP_XML11 : CONTENT_STOP_XML10;
        this.quotAttrStopTable = xml11 ? QUOT_ATTR_STOP_XML11 : QUOT_ATTR_STOP_XML10;
        this.aposAttrStopTable = xml11 ? APOS_ATTR_STOP_XML11 : APOS_ATTR_STOP_XML10;
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

    /** Legal-{@code Char} check for a LITERAL content/attribute-value
     *  character. XML 1.0's {@code Char} production excludes all C0/C1
     *  controls outside tab/CR/LF entirely; XML 1.1's does too for a
     *  <em>literal</em> appearance - it only relaxes restricted characters
     *  for character <em>references</em> (see {@link #isLegalCharRefCodePoint}
     *  - a deliberately more permissive, separate check for a different
     *  question). Line-ending characters (CR in particular) are assumed
     *  already normalized upstream (ExternalEntityDecoder's job, per this
     *  class's established boundary), so this only needs to reject, not
     *  additionally normalize, anything it sees. */
    private boolean isLegalLiteralChar(char c) {
        return xml11 ? isLegalLiteralCharXml11(c) : isLegalLiteralCharXml10(c);
    }

    private static boolean isLegalLiteralCharXml10(char c) {
        return c == 0x9 || c == 0xA || c == 0xD || (c >= 0x20 && c <= 0xD7FF)
                || (c >= 0xE000 && c <= 0xFFFD) || (c >= 0xD800 && c <= 0xDFFF);
    }

    private static boolean isLegalLiteralCharXml11(char c) {
        if (c >= 0x1 && c <= 0xD7FF) {
            return !((c >= 0x7F && c <= 0x84) || (c >= 0x86 && c <= 0x9F));
        }
        return (c >= 0xE000 && c <= 0xFFFD) || (c >= 0xD800 && c <= 0xDFFF);
    }

    /**
     * Precomputed per-character stop tables for the hot bulk-scan loops in
     * {@link #scanContent()} and {@link #scanAttributeValueStreaming()},
     * mirroring the technique just proven on {@code Tokenizer}'s own bulk
     * scans (see commit "Replace per-character branch chains with lookup
     * tables in Tokenizer bulk scans"): profiling showed the naive
     * comparison-chain-plus-legality-check run once per character across
     * every text/attribute-value run as a dominant cost relative to Xerces's
     * single bitmask-table lookup. One table per (construct, XML version)
     * combination - selected once per {@link Scanner} instance (XML version
     * never changes mid-document) rather than re-branched on {@link #xml11}
     * per character.
     * <p>
     * {@link #CONTENT_STOP_XML10}/{@link #CONTENT_STOP_XML11}: true for
     * '&lt;', '&amp;', ']' (needs {@link #contentBracketRun} tracking), '>'
     * (only sometimes illegal - depends on {@link #contentBracketRun} - so
     * conservatively always routed to the slow path rather than baked into a
     * table that can't see runtime state), or any character {@link
     * #isLegalLiteralChar} rejects.
     * <p>
     * {@link #QUOT_ATTR_STOP_XML10}/{@code _XML11} and {@link
     * #APOS_ATTR_STOP_XML10}/{@code _XML11}: true for the run's own quote
     * character, '&amp;', '&lt;', tab/LF/CR (needs in-place normalisation to
     * a space - XML 3.3.3), or any illegal character.
     */
    private static final boolean[] CONTENT_STOP_XML10 = new boolean[0x10000];
    private static final boolean[] CONTENT_STOP_XML11 = new boolean[0x10000];
    private static final boolean[] QUOT_ATTR_STOP_XML10 = new boolean[0x10000];
    private static final boolean[] QUOT_ATTR_STOP_XML11 = new boolean[0x10000];
    private static final boolean[] APOS_ATTR_STOP_XML10 = new boolean[0x10000];
    private static final boolean[] APOS_ATTR_STOP_XML11 = new boolean[0x10000];

    static {
        for (int i = 0; i < 0x10000; i++) {
            char c = (char) i;
            boolean legal10 = isLegalLiteralCharXml10(c);
            boolean legal11 = isLegalLiteralCharXml11(c);
            boolean contentStructural = (c == '<' || c == '&' || c == ']' || c == '>');
            CONTENT_STOP_XML10[i] = contentStructural || !legal10;
            CONTENT_STOP_XML11[i] = contentStructural || !legal11;
            boolean needsSubst = (c == '\t' || c == '\n' || c == '\r');
            boolean quotStructural = (c == '"' || c == '&' || c == '<' || needsSubst);
            boolean aposStructural = (c == '\'' || c == '&' || c == '<' || needsSubst);
            QUOT_ATTR_STOP_XML10[i] = quotStructural || !legal10;
            QUOT_ATTR_STOP_XML11[i] = quotStructural || !legal11;
            APOS_ATTR_STOP_XML10[i] = aposStructural || !legal10;
            APOS_ATTR_STOP_XML11[i] = aposStructural || !legal11;
        }
    }

    private SAXException illegalCharError(char c) throws SAXException {
        return handler.fatalError(
                "Character U+" + String.format("%04X", (int) c) + " is not a legal XML character");
    }

    /** Combined per-character check for {@link #scanContent()}'s literal
     *  runs: legal-{@code Char} rejection, then {@link #contentBracketRun}
     *  tracking for the "]]&gt;" WFC - see their respective Javadocs. One
     *  call per character keeps this to a single branch-cluster in the hot
     *  loop rather than two separate passes. */
    private void checkContentChar(char c) throws SAXException {
        if (!isLegalLiteralChar(c)) {
            throw illegalCharError(c);
        }
        if (c == ']') {
            contentBracketRun++;
        } else if (c == '>' && contentBracketRun >= 2) {
            throw handler.fatalError("\"]]>\" is not allowed in content, except to mark the end of a CDATA section");
        } else {
            contentBracketRun = 0;
        }
    }

    /**
     * Fast-path equivalent of a {@code while (pos < limit && buf[pos] != '<'
     * && buf[pos] != '&') { checkContentChar(buf[pos]); pos++; }} loop, used
     * by {@link #scanContent()}'s common (no DTD, or a mixed/ANY-content
     * element) case: bulk-skips runs of ordinary characters via a single
     * {@link #contentStopTable} lookup per character instead of {@link
     * #checkContentChar}'s comparison chain, only falling back to per-
     * character handling for the characters the table actually flags
     * (']'/'>' - {@link #contentBracketRun} tracking for the "]]&gt;" WFC -
     * or an illegal character; '&lt;'/'&amp;' end the run, matching {@link
     * #checkContentChar}'s loop condition exactly). Mutates {@link #pos} to
     * just past the last character consumed - either at '&lt;'/'&amp;', or
     * at {@link #limit} if the buffer ran out first.
     */
    private void scanContentRunFast() throws SAXException {
        while (true) {
            int before = pos;
            while (pos < limit && !contentStopTable[buf[pos]]) {
                pos++;
            }
            if (pos > before) {
                // Every character just bulk-skipped is, by construction of
                // contentStopTable, guaranteed not ']' - so any in-progress
                // "]]" run is broken, exactly as checkContentChar's own
                // trailing "else { contentBracketRun = 0; }" branch would
                // have done for the last such character.
                contentBracketRun = 0;
            }
            if (pos >= limit) {
                return;
            }
            char c = buf[pos];
            if (c == '<' || c == '&') {
                return;
            }
            if (c == ']') {
                contentBracketRun++;
                pos++;
                continue;
            }
            if (c == '>') {
                if (contentBracketRun >= 2) {
                    throw handler.fatalError(
                            "\"]]>\" is not allowed in content, except to mark the end of a CDATA section");
                }
                contentBracketRun = 0;
                pos++;
                continue;
            }
            // Only remaining reason contentStopTable[c] could be true.
            throw illegalCharError(c);
        }
    }

    /**
     * XML NameChar (M6): {@code NameStartChar | "-" | "." | [0-9] | #xB7 |
     * [#x0300-#x036F] | [#x203F-#x2040]} - the modern, simplified production
     * from XML 1.0 5th edition / XML 1.1 (identical in both - see {@link
     * #isNameStartChar}). Replaces M1-M5's cruder "any non-ASCII is legal"
     * approximation with the real Unicode ranges, at zero additional
     * hot-path cost: every one of this method's ~14 call sites already
     * calls it once per character in a name-scanning loop, so making the
     * check itself more accurate doesn't add a new check anywhere, only
     * corrects an existing one. {@code CharClass.java} (the old parser's
     * classifier) additionally accepts a much larger legacy table of
     * pre-5th-edition {@code CombiningChar}/{@code Extender} ranges beyond
     * what the current spec text requires; not ported here - see class
     * Javadoc "M6" section for the full list of what's deliberately still
     * deferred to the Conformance hardening phase.
     */
    private static boolean isNameChar(char c) {
        return NAME_CHAR_TABLE[c];
    }

    private static boolean isNameCharSlow(char c) {
        if (isNameStartCharSlow(c)) {
            return true;
        }
        return c == '-' || c == '.' || (c >= '0' && c <= '9')
                || c == 0xB7
                || (c >= 0x0300 && c <= 0x036F)
                || (c >= 0x203F && c <= 0x2040);
    }

    /**
     * XML NameStartChar: {@code ":" | [A-Z] | "_" | [a-z] | [#xC0-#xD6] |
     * [#xD8-#xF6] | [#xF8-#x2FF] | [#x370-#x37D] | [#x37F-#x1FFF] |
     * [#x200C-#x200D] | [#x2070-#x218F] | [#x2C00-#x2FEF] | [#x3001-#xD7FF]
     * | [#xF900-#xFDCF] | [#xFDF0-#xFFFD] | [#x10000-#xEFFFF]} - identical
     * in XML 1.0 5th edition and XML 1.1. Astral-plane characters
     * (U+10000-U+EFFFF) are represented as UTF-16 surrogate pairs; both
     * halves are accepted individually here (this class scans {@code char},
     * not code points), matching every other per-{@code char} scan in this
     * file.
     */
    private static boolean isNameStartChar(char c) {
        return NAME_START_CHAR_TABLE[c];
    }

    private static boolean isNameStartCharSlow(char c) {
        if (c == ':' || c == '_' || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')) {
            return true;
        }
        if (c < 0xC0) {
            return false;
        }
        if (c <= 0x2FF) {
            return (c >= 0xC0 && c <= 0xD6) || (c >= 0xD8 && c <= 0xF6) || c >= 0xF8;
        }
        if (c >= 0x370 && c <= 0x1FFF) {
            return c != 0x37E;
        }
        if (c >= 0x200C && c <= 0x200D) {
            return true;
        }
        if (c >= 0x2070 && c <= 0x218F) {
            return true;
        }
        if (c >= 0x2C00 && c <= 0x2FEF) {
            return true;
        }
        if (c >= 0x3001 && c <= 0xD7FF) {
            return true;
        }
        if (c >= 0xF900 && c <= 0xFDCF) {
            return true;
        }
        if (c >= 0xFDF0 && c <= 0xFFFD) {
            return true;
        }
        // Surrogate pairs (U+10000-U+EFFFF): accept both halves, matching
        // the char-at-a-time scanning model used throughout this class.
        return c >= 0xD800 && c <= 0xDFFF;
    }

    /** Precomputed per-character lookup tables for {@link #isNameChar}/
     *  {@link #isNameStartChar} - the same technique as {@link
     *  #CONTENT_STOP_XML10} and friends, applied here because profiling the
     *  {@code attrs} benchmark doc type (many short attribute names per
     *  element) showed {@code isNameStartChar}'s branch chain - called once
     *  per character by {@code isNameChar}'s name-scanning loops, which run
     *  for every element/attribute/entity/PI name in the document - as a
     *  dominant cost, comparable to the content/attribute-value runs
     *  {@code CONTENT_STOP}/{@code QUOT_ATTR_STOP}/{@code APOS_ATTR_STOP}
     *  already address. No XML 1.0 vs 1.1 split needed - name character
     *  rules don't depend on the XML version. */
    private static final boolean[] NAME_START_CHAR_TABLE = new boolean[0x10000];
    private static final boolean[] NAME_CHAR_TABLE = new boolean[0x10000];

    static {
        for (int i = 0; i < 0x10000; i++) {
            char c = (char) i;
            NAME_START_CHAR_TABLE[i] = isNameStartCharSlow(c);
        }
        for (int i = 0; i < 0x10000; i++) {
            NAME_CHAR_TABLE[i] = isNameCharSlow((char) i);
        }
    }

    /** Throws if {@code buf[nameStart]} is not a legal NameStartChar - call
     *  immediately after any name-scanning loop confirms the name is
     *  non-empty (so {@code buf[nameStart]} is safe to read). {@code
     *  isNameChar} alone (used by the scanning loop itself) is not enough:
     *  a Name's first character has a narrower legal set than its
     *  continuation characters (e.g. digits/hyphen/period are legal
     *  NameChars but not legal first characters) - found missing via
     *  xmlconf Conformance hardening (multiple not-wf tests specifically
     *  target a name starting with a digit). Cold path everywhere this is
     *  called (comments/PI/entity-ref/DOCTYPE-adjacent names, or - for
     *  element/attribute names - only once per name, not per character), so
     *  this carries none of the hot-path cost concern that motivated
     *  deferring it during M6 itself. */
    private void checkNameStartChar(int nameStart) throws SAXException {
        if (!isNameStartChar(buf[nameStart])) {
            throw handler.fatalError("Names must begin with a legal NameStartChar");
        }
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
        // M5: an element declared with element-only content (no #PCDATA at
        // all) reports whitespace-only text via ignorableWhitespace()
        // instead of characters() - see emitContentRun(). Determined once
        // per call (a single map lookup), not per character.
        boolean elementOnlyContent = insideDocument && isCurrentElementContentElementOnly();
        while (true) {
            int runStart = pos;
            if (elementOnlyContent && pos < limit && buf[pos] != '<' && buf[pos] != '&') {
                // Homogeneous-run scanning: also stop at a whitespace/non-
                // whitespace transition, so every run this loop identifies
                // is either all-whitespace or all-non-whitespace, and the
                // ignorableWhitespace()-vs-characters() choice never needs
                // to be revisited once made (it can't be: a run spanning a
                // receive() boundary could otherwise start as all-whitespace
                // within one call and only later, in a subsequent chunk, be
                // revealed to contain non-whitespace content - by which
                // point the whitespace-only prefix may already have gone out
                // via ignorableWhitespace()). This costs one extra branch per
                // character, but only when a DTD has actually declared the
                // current element element-only - the common (no DTD, or
                // mixed/ANY content) case is untouched below.
                boolean ws0 = isWs(buf[pos]);
                while (pos < limit && buf[pos] != '<' && buf[pos] != '&' && isWs(buf[pos]) == ws0) {
                    checkContentChar(buf[pos]);
                    pos++;
                }
            } else {
                scanContentRunFast();
            }
            boolean runIsWhitespace = elementOnlyContent && pos > runStart && isWs(buf[runStart]);
            if (!insideDocument && pos > runStart) {
                // Misc* (prolog/epilog): only whitespace, Comment, and PI
                // are legal - non-whitespace character data is a WFC
                // violation ("document element" / no text before or after
                // the root element). Entity/character references are
                // rejected unconditionally just below, at the '&' check -
                // Misc allows none of them either, whitespace or not.
                for (int i = runStart; i < pos; i++) {
                    if (!isWs(buf[i])) {
                        throw handler.fatalError("Only whitespace, comments, and processing instructions "
                                + "are allowed " + (rootEnded ? "after the root element" : "before the root element"));
                    }
                }
            }
            if (pos >= limit) {
                if (insideDocument && pos > runStart) {
                    emitContentRun(CharBuffer.wrap(buf, runStart, pos - runStart), false, runIsWhitespace);
                    contentRunOpen = true;
                    contentRunIsWhitespace = runIsWhitespace;
                }
                return false;
            }
            if (buf[pos] == '<') {
                if (insideDocument) {
                    if (pos > runStart) {
                        emitContentRun(CharBuffer.wrap(buf, runStart, pos - runStart), true, runIsWhitespace);
                        contentRunOpen = false;
                    } else if (contentRunOpen) {
                        emitContentRun(EMPTY_BUFFER, true, contentRunIsWhitespace);
                        contentRunOpen = false;
                    }
                }
                contentBracketRun = 0;
                return true;
            }
            if (buf[pos] != '&') {
                // Stopped at a whitespace/non-whitespace transition
                // (elementOnlyContent's homogeneous-run scanning only) -
                // not markup, not an entity reference. This is a fully
                // confirmed, complete sub-run (nothing about it depends on
                // more data arriving), so it closes with end=true, exactly
                // like the '<' case above - then the outer loop simply
                // starts a fresh run right here for whatever comes next.
                if (insideDocument && pos > runStart) {
                    emitContentRun(CharBuffer.wrap(buf, runStart, pos - runStart), true, runIsWhitespace);
                    contentRunOpen = false;
                }
                continue;
            }
            if (!insideDocument) {
                // Misc* (prolog/epilog) allows no entity or character
                // reference at all, whitespace-valued or not.
                throw handler.fatalError("Entity and character references are only allowed "
                        + "within the document element");
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
                emitContentRun(CharBuffer.wrap(buf, runStart, ampPos - runStart), false, runIsWhitespace);
                contentRunOpen = true;
                contentRunIsWhitespace = runIsWhitespace;
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
                    emitContentRun(EMPTY_BUFFER, true, contentRunIsWhitespace);
                }
                contentRunOpen = false;
                contentBracketRun = 0;
                expandGeneralEntityInContent(pendingEntityName);
                continue;
            }
            CharBuffer decoded = lastDecodedRef;
            // A predefined/numeric character reference is also not literal
            // source text (see contentBracketRun's Javadoc) - reset so it
            // can't combine with adjacent literal ']'/'>' characters.
            contentBracketRun = 0;
            boolean atMarkup = (pos < limit && buf[pos] == '<');
            if (insideDocument) {
                // Entity-produced content is never routed through
                // ignorableWhitespace(), even if it happens to be all
                // whitespace - a deliberate M5 scope simplification (see
                // class Javadoc), not an oversight.
                handler.characters(decoded, atMarkup);
                contentRunOpen = !atMarkup;
                contentRunIsWhitespace = false;
            }
            if (atMarkup) {
                return true;
            }
            // loop continues, scanning the next run after the entity
        }
    }

    /** True if the current (innermost open) element's declared content type
     *  is element-only ({@link DTDModel.ContentType#ELEMENT} - no {@code
     *  #PCDATA} at all), the only case where whitespace-only text is
     *  reported via {@link XMLHandler#ignorableWhitespace} rather than
     *  {@link XMLHandler#characters}. False for {@code MIXED}/{@code ANY}/
     *  {@code EMPTY} content types and for an element with no {@code
     *  <!ELEMENT>} declaration at all (including the common no-DTD case). */
    private boolean isCurrentElementContentElementOnly() {
        String currentElement = elementStack.get(elementStack.size() - 1);
        return dtdModel.getContentType(currentElement) == DTDModel.ContentType.ELEMENT;
    }

    /** True if the most recently opened run - tracked via {@link
     *  #contentRunOpen} - was routed through {@link
     *  XMLHandler#ignorableWhitespace} rather than {@link
     *  XMLHandler#characters}, so a later empty-closing-frame call (see
     *  {@link #scanContent()}) closes it out via the same event type it was
     *  opened with. */
    private boolean contentRunIsWhitespace;

    private void emitContentRun(CharBuffer text, boolean end, boolean isWhitespace) throws SAXException {
        if (isWhitespace) {
            handler.ignorableWhitespace(text, end);
        } else {
            handler.characters(text, end);
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
            if (!isLegalCharRefCodePoint(codePoint, xml11)) {
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
        checkNameStartChar(nameStart);
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
        checkNameStartChar(nameStart);
        if (rootEnded) {
            // WFC "unique document element" (only one root element is
            // allowed) - a second start tag once the first has fully closed.
            throw handler.fatalError("A document may contain only one root element");
        }
        String qName = namePool.intern(CharBuffer.wrap(buf, nameStart, p - nameStart));
        if (!rootStarted) {
            // The root element's name matching the DOCTYPE's Name is VC
            // "Root Element Type" (XML 1.0 SS3.2), not a WFC - a non-
            // validating processor must not reject a mismatch here. Found
            // via xmlconf Conformance hardening: this had been incorrectly
            // enforced as fatal since M4. Real enforcement belongs with
            // validation support (a recoverable error, not fatalError).
            rootStarted = true;
        }
        pos = p;
        elementStack.add(qName);
        seenAttributeNameCount = 0;
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
                applyAttributeDefaults(elementStack.get(elementStack.size() - 1));
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
                applyAttributeDefaults(elementStack.get(elementStack.size() - 1));
                elementStack.remove(elementStack.size() - 1);
                rootEnded = elementStack.isEmpty();
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
            checkNameStartChar(nameStart);
            String attrName = namePool.intern(CharBuffer.wrap(buf, nameStart, pos - nameStart));
            recordSeenAttributeName(attrName);

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

            String attrType = lookupAttributeType(elementStack.get(elementStack.size() - 1), attrName);
            handler.startAttribute(attrName, attrType);
            pendingQuote = quote;
            attrValueRunOpen = false;
            normalizingCurrentAttribute = !"CDATA".equals(attrType);
            if (normalizingCurrentAttribute) {
                normalizeBuilder.setLength(0);
            }
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
        // Resolved once for the whole attribute value (the quote character
        // and XML version are both fixed for its duration) - see the
        // CONTENT_STOP_XML10/XML11 family's Javadoc for why this table-
        // lookup replaces the naive per-character comparison chain.
        boolean[] attrStopTable = (quote == '"') ? quotAttrStopTable : aposAttrStopTable;
        while (true) {
            int runStart = pos;
            // Unconditional attribute-value normalisation (XML 3.3.3): a
            // literal tab/CR/LF is replaced with a single space, always -
            // not conditional on a DTD being present (that's the type-
            // dependent non-CDATA collapse below, applied separately). Safe
            // to substitute in place: this range is buf's own scratch
            // storage, about to be emitted and then either consumed or
            // copied per saveBuffers()'s contract - nothing reads the
            // original raw bytes at this position again. The substitution
            // itself doesn't end the run (scanning resumes in the same run
            // right after it), so bulk-skipping resumes after each one.
            while (true) {
                while (pos < limit && !attrStopTable[buf[pos]]) {
                    pos++;
                }
                if (pos >= limit) {
                    break;
                }
                char c = buf[pos];
                if (c == '\t' || c == '\n' || c == '\r') {
                    buf[pos] = ' ';
                    pos++;
                    continue;
                }
                break;
            }
            if (pos >= limit) {
                if (pos > runStart) {
                    emitAttributeValueContent(CharBuffer.wrap(buf, runStart, pos - runStart), false);
                    attrValueRunOpen = true;
                }
                return false;
            }
            if (buf[pos] == '<') {
                throw handler.fatalError("'<' is not allowed in an attribute value");
            }
            if (buf[pos] != quote && buf[pos] != '&') {
                // Only remaining reason attrStopTable flagged this character.
                throw illegalCharError(buf[pos]);
            }
            if (buf[pos] == quote) {
                if (pos > runStart) {
                    emitAttributeValueContent(CharBuffer.wrap(buf, runStart, pos - runStart), true);
                } else if (attrValueRunOpen) {
                    emitAttributeValueContent(EMPTY_BUFFER, true);
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
                emitAttributeValueContent(CharBuffer.wrap(buf, runStart, ampPos - runStart), false);
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
            emitAttributeValueContent(decoded, atQuote);
            if (atQuote) {
                attrValueRunOpen = false;
                pos++;
                return true;
            }
            attrValueRunOpen = true;
            // loop continues, scanning the next run after the entity
        }
    }

    /** Returns the declared type of {@code attrName} on {@code elementName}
     *  (the literal ATTLIST keyword text, or {@code "ENUMERATION"} for a
     *  bare enumeration - see {@link DTDModel.AttDef#type}), or {@code
     *  "CDATA"} - the SAX2 convention for "no declaration was read for this
     *  attribute" - if there is no ATTLIST/DTD at all, or the element has
     *  one but this attribute isn't in it. Used both for {@link
     *  XMLHandler#startAttribute}'s type parameter and (via {@code
     *  !"CDATA".equals(...)}) to decide whether the type-dependent collapse
     *  normalisation (XML 3.3.3) applies - a single map lookup either way,
     *  no allocation. */
    private String lookupAttributeType(String elementName, String attrName) {
        Map<String, DTDModel.AttDef> declared = dtdModel.getAttributes(elementName);
        if (declared == null) {
            return "CDATA";
        }
        DTDModel.AttDef def = declared.get(attrName);
        return def == null ? "CDATA" : def.type;
    }

    /**
     * Dispatches an attribute value chunk either straight to {@link
     * XMLHandler#attributeValueContent} (the common case - zero-copy,
     * streamed as-is) or, when {@link #normalizingCurrentAttribute} is true,
     * into {@link #normalizeBuilder} instead, flushing the fully collapsed
     * result as a single call once {@code end} is reached. Type-dependent
     * collapse (XML 3.3.3) trims leading/trailing whitespace and folds
     * interior whitespace runs to one space each - distinct from, and
     * applied on top of, the unconditional literal-tab/CR/LF-to-space
     * substitution {@link #scanAttributeValueStreaming} already did while
     * scanning (so by this point every whitespace char still present is
     * already a plain space).
     */
    private void emitAttributeValueContent(CharBuffer chunk, boolean end) throws SAXException {
        if (!normalizingCurrentAttribute) {
            handler.attributeValueContent(chunk, end);
            return;
        }
        normalizeBuilder.append(chunk);
        if (end) {
            String collapsed = collapseWhitespace(normalizeBuilder);
            normalizingCurrentAttribute = false;
            handler.attributeValueContent(CharBuffer.wrap(collapsed), true);
        }
    }

    /** XML 3.3.3's type-dependent attribute value normalisation: trim
     *  leading/trailing whitespace, collapse interior whitespace runs to a
     *  single space each. Operates on already-tab/CR/LF-normalized text (see
     *  {@link #scanAttributeValueStreaming}), so only plain spaces need
     *  collapsing here, but checking {@link #isWs} generically costs nothing
     *  extra and stays correct regardless of call order. */
    private static String collapseWhitespace(CharSequence s) {
        int len = s.length();
        StringBuilder sb = new StringBuilder(len);
        boolean pendingSpace = false;
        for (int i = 0; i < len; i++) {
            char c = s.charAt(i);
            if (isWs(c)) {
                if (sb.length() > 0) {
                    pendingSpace = true;
                }
            } else {
                if (pendingSpace) {
                    sb.append(' ');
                    pendingSpace = false;
                }
                sb.append(c);
            }
        }
        return sb.toString();
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
        checkNameStartChar(nameStart);
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
        rootEnded = elementStack.isEmpty();

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
        if (elementStack.isEmpty()) {
            // CDATA sections are markup that can only appear within element
            // content - never in the prolog or epilog (Misc* allows only
            // Comment/PI/whitespace).
            throw handler.fatalError(
                    "CDATA sections are only allowed within the document element");
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
        checkNameStartChar(targetStart);
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
    private static final char[] ATTLIST_MARKER = "<!ATTLIST".toCharArray();
    private static final char[] ELEMENT_MARKER = "<!ELEMENT".toCharArray();
    private static final char[] EMPTY_MARKER = "EMPTY".toCharArray();
    private static final char[] ANY_MARKER = "ANY".toCharArray();
    private static final char[] PCDATA_MARKER = "#PCDATA".toCharArray();
    private static final char[] REQUIRED_MARKER = "#REQUIRED".toCharArray();
    private static final char[] IMPLIED_MARKER = "#IMPLIED".toCharArray();
    private static final char[] FIXED_MARKER = "#FIXED".toCharArray();
    private static final char[] INCLUDE_MARKER = "INCLUDE".toCharArray();
    private static final char[] IGNORE_MARKER = "IGNORE".toCharArray();

    /** M5: element content types and attribute defaults/types declared in
     *  the internal DTD subset - see class Javadoc "M5" section. */
    private final DTDModel dtdModel = new DTDModel();

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

    /** Set by {@link #skipExternalId} on success: the captured PUBLIC
     *  literal (null if the external ID was SYSTEM-only) and SYSTEM literal,
     *  used by external entity/DTD fetching. */
    private String lastExternalIdPublicId;
    private String lastExternalIdSystemId;

    /** Skips a {@code SYSTEM "..."} or {@code PUBLIC "..." "..."} external
     *  ID starting at {@code p}, capturing the literal(s) into {@link
     *  #lastExternalIdPublicId}/{@link #lastExternalIdSystemId}. Returns the
     *  position past it, or -1 if underflow. */
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

        lastExternalIdPublicId = null;
        lastExternalIdSystemId = null;

        if (isPublic) {
            int litStart = p + 1;
            int r = findQuotedLiteralEnd(p);
            if (r < 0) {
                return -1;
            }
            lastExternalIdPublicId = new String(buf, litStart, r - 1 - litStart);
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
        int sysLitStart = p + 1;
        int sysEnd = findQuotedLiteralEnd(p);
        if (sysEnd < 0) {
            return -1;
        }
        lastExternalIdSystemId = new String(buf, sysLitStart, sysEnd - 1 - sysLitStart);
        return sysEnd;
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
        if (!isLegalCharRefCodePoint(codePoint, xml11)) {
            throw handler.fatalError("Character reference out of range: " + codePoint);
        }
        sb.appendCodePoint(codePoint);
        return p + 1;
    }

    /**
     * Legal code point for a numeric character reference ({@code &#NN;}/
     * {@code &#xNN;}), matching the old parser's {@code
     * Tokenizer.isLegalXMLChar(int)} exactly. Deliberately more permissive
     * than literal-character legality would be (not enforced in this
     * milestone - see class Javadoc "M6" section): XML 1.1 allows
     * referencing C0/C1 control characters via character reference even
     * though they may not appear literally.
     */
    private static boolean isLegalCharRefCodePoint(int codePoint, boolean xml11) {
        if (xml11) {
            return (codePoint >= 0x1 && codePoint <= 0xD7FF)
                    || (codePoint >= 0xE000 && codePoint <= 0xFFFD)
                    || (codePoint >= 0x10000 && codePoint <= 0x10FFFF);
        }
        return codePoint == 0x9 || codePoint == 0xA || codePoint == 0xD
                || (codePoint >= 0x20 && codePoint <= 0xD7FF)
                || (codePoint >= 0xE000 && codePoint <= 0xFFFD)
                || (codePoint >= 0x10000 && codePoint <= 0x10FFFF);
    }

    /**
     * Parses a quoted literal value (an EntityValue or AttValue - the two
     * share the same character-reference-expands-now / everything-else-
     * literal rule) starting at {@code buf[p]} (the opening quote), appending
     * the decoded text to {@code sb}. Shared by {@link #scanEntityDeclaration}
     * and {@link #scanAttlistDeclaration}. Returns the position past the
     * closing quote, or -1 on underflow.
     */
    private int scanQuotedLiteralWithCharRefs(int p, StringBuilder sb) throws SAXException {
        char quote = buf[p];
        int q = p + 1;
        while (true) {
            if (q >= limit) {
                return -1;
            }
            char c = buf[q];
            if (c == quote) {
                return q + 1;
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
    }

    /**
     * Parses one {@code <!ENTITY ...>} declaration, {@code p} positioned
     * just past the "<!ENTITY" keyword. On success, records the declaration
     * into {@code pendingEntities}/{@code pendingExternalNames} - or, for a
     * parameter entity declaration ('%' right after "<!ENTITY"), into {@code
     * pendingParamEntities}/{@code pendingParamExternalNames} instead (first
     * declaration wins for a repeated name, per XML 4.2, tracked separately
     * per namespace) - and returns the position past the closing '>';
     * returns -1 on underflow.
     */
    private int scanEntityDeclaration(int p, HashMap<String, String> pendingEntities,
            HashMap<String, String[]> pendingExternalNames, HashMap<String, String> pendingParamEntities,
            HashMap<String, String[]> pendingParamExternalNames) throws SAXException {
        int ws = p;
        p = skipOptionalWhitespace(p);
        if (p >= limit) {
            return -1;
        }
        if (p == ws) {
            throw handler.fatalError("Malformed entity declaration");
        }

        boolean isParam = false;
        if (buf[p] == '%') {
            isParam = true;
            int ws3 = p + 1;
            p = skipOptionalWhitespace(p + 1);
            if (p >= limit) {
                return -1;
            }
            if (p == ws3) {
                throw handler.fatalError("Malformed parameter entity declaration");
            }
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
        checkNameStartChar(nameStart);
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
            StringBuilder sb = new StringBuilder();
            int q = scanQuotedLiteralWithCharRefs(p, sb);
            if (q < 0) {
                return -1;
            }
            p = q;

            p = skipOptionalWhitespace(p);
            if (p >= limit) {
                return -1;
            }
            if (buf[p] != '>') {
                throw handler.fatalError("Malformed entity declaration");
            }
            p++;

            if (isParam) {
                if (!pendingParamEntities.containsKey(name) && !parameterEntities.containsKey(name)) {
                    pendingParamEntities.put(name, sb.toString());
                }
            } else if (!pendingEntities.containsKey(name) && !generalEntities.containsKey(name)) {
                pendingEntities.put(name, sb.toString());
            }
            return p;
        }

        // External entity (SYSTEM or PUBLIC): recorded with its identifiers
        // for lazy fetching at first content reference (see
        // expandGeneralEntityInContent) - captured into locals immediately,
        // since lastExternalIdPublicId/SystemId are overwritten by any
        // subsequent skipExternalId call (there is none within this method,
        // but the fields are shared scanner-wide state, not scoped to this
        // call, so capturing promptly is the safe habit).
        int r = skipExternalId(p);
        if (r < 0) {
            return -1;
        }
        String extPublicId = lastExternalIdPublicId;
        String extSystemId = lastExternalIdSystemId;
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
            checkNameStartChar(ndataNameStart);
            p = skipOptionalWhitespace(p);
            if (p >= limit) {
                return -1;
            }
            if (buf[p] != '>') {
                throw handler.fatalError("Malformed entity declaration");
            }
        }
        p++;

        if (isParam) {
            if (!pendingParamEntities.containsKey(name) && !parameterEntities.containsKey(name)) {
                pendingParamExternalNames.put(name, new String[] { extPublicId, extSystemId });
            }
        } else if (!pendingEntities.containsKey(name) && !generalEntities.containsKey(name)) {
            pendingExternalNames.put(name, new String[] { extPublicId, extSystemId });
        }
        return p;
    }

    /** Skips a flat (non-nested) parenthesised list - a {@code NOTATION}
     *  type's name list or an enumerated attribute type's Nmtoken list -
     *  starting at {@code p} (the '('). Neither ever nests further parens,
     *  unlike an element content model, so this is a simpler scan than
     *  {@link #scanElementDeclaration}'s. Returns the position past the
     *  closing ')', or -1 on underflow. */
    private int skipParenList(int p) throws SAXException {
        p++;
        while (true) {
            if (p >= limit) {
                return -1;
            }
            if (buf[p] == ')') {
                return p + 1;
            }
            p++;
        }
    }

    /**
     * Parses one {@code <!ELEMENT ...>} declaration, {@code p} positioned
     * just past the "<!ELEMENT" keyword, recording only its top-level {@link
     * DTDModel.ContentType} - not the full content-model tree (out of scope
     * for M5's defaulting/normalisation-only goal; see class Javadoc). The
     * content model's parenthesised structure (if any) still has to be
     * scanned in full, paren-depth-aware, purely to find where the
     * declaration ends - {@code (a, (b|c)+, d*)} nests arbitrarily, unlike
     * an ATTLIST's flat enumeration/NOTATION lists. Returns the position
     * past the closing '>', or -1 on underflow.
     */
    private int scanElementDeclaration(int p) throws SAXException {
        int ws = p;
        p = skipOptionalWhitespace(p);
        if (p >= limit) {
            return -1;
        }
        if (p == ws) {
            throw handler.fatalError("Malformed element declaration");
        }

        int nameStart = p;
        while (p < limit && isNameChar(buf[p])) {
            p++;
        }
        if (p >= limit) {
            return -1;
        }
        if (p == nameStart) {
            throw handler.fatalError("Malformed element declaration");
        }
        checkNameStartChar(nameStart);
        String name = new String(buf, nameStart, p - nameStart);

        int ws2 = p;
        p = skipOptionalWhitespace(p);
        if (p >= limit) {
            return -1;
        }
        if (p == ws2) {
            throw handler.fatalError("Malformed element declaration");
        }

        DTDModel.ContentType type;
        int em = matchKeyword(p, EMPTY_MARKER);
        if (em == KW_NEED_MORE) {
            return -1;
        }
        if (em == KW_MATCH) {
            type = DTDModel.ContentType.EMPTY;
            p += EMPTY_MARKER.length;
        } else {
            int am = matchKeyword(p, ANY_MARKER);
            if (am == KW_NEED_MORE) {
                return -1;
            }
            if (am == KW_MATCH) {
                type = DTDModel.ContentType.ANY;
                p += ANY_MARKER.length;
            } else {
                if (p >= limit) {
                    return -1;
                }
                if (buf[p] != '(') {
                    throw handler.fatalError("Malformed element declaration");
                }
                int afterParen = skipOptionalWhitespace(p + 1);
                if (afterParen >= limit) {
                    return -1;
                }
                int pm = matchKeyword(afterParen, PCDATA_MARKER);
                if (pm == KW_NEED_MORE) {
                    return -1;
                }
                type = (pm == KW_MATCH) ? DTDModel.ContentType.MIXED : DTDModel.ContentType.ELEMENT;

                int depth = 0;
                while (true) {
                    if (p >= limit) {
                        return -1;
                    }
                    char c = buf[p];
                    p++;
                    if (c == '(') {
                        depth++;
                    } else if (c == ')') {
                        depth--;
                        if (depth == 0) {
                            break;
                        }
                    }
                }
                if (p >= limit) {
                    return -1;
                }
                if (buf[p] == '?' || buf[p] == '*' || buf[p] == '+') {
                    p++;
                }
            }
        }

        p = skipOptionalWhitespace(p);
        if (p >= limit) {
            return -1;
        }
        if (buf[p] != '>') {
            throw handler.fatalError("Malformed element declaration");
        }
        p++;

        dtdModel.declareContentType(name, type);
        return p;
    }

    /**
     * Parses one {@code <!ATTLIST ...>} declaration (an element name
     * followed by zero or more AttDefs), {@code p} positioned just past the
     * "<!ATTLIST" keyword. For each AttDef, only records whether the type is
     * {@code CDATA} or not (the specific non-CDATA type, and any
     * enumeration/{@code NOTATION} value list, is recognised syntactically
     * to skip past correctly but not retained - checking that a value is a
     * legal member of it is a VC check, out of scope for M5) and the
     * resolved default (raw at this point - see {@link
     * #scanDoctypeSubset()}'s finishing step for entity resolution), or null
     * for {@code #REQUIRED}/{@code #IMPLIED}. Returns the position past the
     * declaration's closing '>', or -1 on underflow.
     */
    private int scanAttlistDeclaration(int p) throws SAXException {
        int ws = p;
        p = skipOptionalWhitespace(p);
        if (p >= limit) {
            return -1;
        }
        if (p == ws) {
            throw handler.fatalError("Malformed attribute-list declaration");
        }

        int nameStart = p;
        while (p < limit && isNameChar(buf[p])) {
            p++;
        }
        if (p >= limit) {
            return -1;
        }
        if (p == nameStart) {
            throw handler.fatalError("Malformed attribute-list declaration");
        }
        checkNameStartChar(nameStart);
        String elementName = new String(buf, nameStart, p - nameStart);

        while (true) {
            int ws2 = p;
            p = skipOptionalWhitespace(p);
            if (p >= limit) {
                return -1;
            }
            if (buf[p] == '>') {
                p++;
                return p;
            }
            if (p == ws2) {
                throw handler.fatalError("Malformed attribute-list declaration");
            }

            int attrNameStart = p;
            while (p < limit && isNameChar(buf[p])) {
                p++;
            }
            if (p >= limit) {
                return -1;
            }
            if (p == attrNameStart) {
                throw handler.fatalError("Malformed attribute-list declaration");
            }
            checkNameStartChar(attrNameStart);
            // Interned via the same namePool as attribute names encountered
            // during actual start-tag scanning (see scanAttributesAndTagEnd),
            // so applyAttributeDefaults()'s wasAttributeSeen() check - a
            // reference-equality linear scan, not a HashSet - works
            // correctly against DTDModel's stored declaration names too.
            String attrName = namePool.intern(CharBuffer.wrap(buf, attrNameStart, p - attrNameStart));

            int ws3 = p;
            p = skipOptionalWhitespace(p);
            if (p >= limit) {
                return -1;
            }
            if (p == ws3) {
                throw handler.fatalError("Malformed attribute-list declaration");
            }

            String type;
            if (buf[p] == '(') {
                // A bare enumeration - SAX/the old parser's AttListDeclParser
                // both report this as the literal type name "ENUMERATION",
                // not the list of allowed values (which isn't retained here
                // anyway - see class Javadoc).
                type = "ENUMERATION";
                int r = skipParenList(p);
                if (r < 0) {
                    return -1;
                }
                p = r;
            } else {
                int typeStart = p;
                while (p < limit && isNameChar(buf[p])) {
                    p++;
                }
                if (p >= limit) {
                    return -1;
                }
                if (p == typeStart) {
                    throw handler.fatalError("Malformed attribute-list declaration");
                }
                // The literal keyword text itself is the SAX type string
                // ("CDATA", "ID", "IDREF", "IDREFS", "ENTITY", "ENTITIES",
                // "NMTOKEN", "NMTOKENS", or "NOTATION") - not separately
                // validated as one of those (VC/WFC keyword-legality
                // checking is out of scope for M5).
                type = new String(buf, typeStart, p - typeStart);
                if ("NOTATION".equals(type)) {
                    int ws3b = p;
                    p = skipOptionalWhitespace(p);
                    if (p >= limit) {
                        return -1;
                    }
                    if (p == ws3b) {
                        throw handler.fatalError("Malformed attribute-list declaration");
                    }
                    if (buf[p] != '(') {
                        throw handler.fatalError("Malformed attribute-list declaration");
                    }
                    int r = skipParenList(p);
                    if (r < 0) {
                        return -1;
                    }
                    p = r;
                }
            }

            int ws4 = p;
            p = skipOptionalWhitespace(p);
            if (p >= limit) {
                return -1;
            }
            if (p == ws4) {
                throw handler.fatalError("Malformed attribute-list declaration");
            }

            String rawDefault;
            if (buf[p] == '#') {
                int rm = matchKeyword(p, REQUIRED_MARKER);
                if (rm == KW_NEED_MORE) {
                    return -1;
                }
                if (rm == KW_MATCH) {
                    p += REQUIRED_MARKER.length;
                    rawDefault = null;
                } else {
                    int im = matchKeyword(p, IMPLIED_MARKER);
                    if (im == KW_NEED_MORE) {
                        return -1;
                    }
                    if (im == KW_MATCH) {
                        p += IMPLIED_MARKER.length;
                        rawDefault = null;
                    } else {
                        int fm = matchKeyword(p, FIXED_MARKER);
                        if (fm == KW_NEED_MORE) {
                            return -1;
                        }
                        if (fm != KW_MATCH) {
                            throw handler.fatalError("Malformed attribute-list declaration");
                        }
                        p += FIXED_MARKER.length;
                        int ws5 = p;
                        p = skipOptionalWhitespace(p);
                        if (p >= limit) {
                            return -1;
                        }
                        if (p == ws5) {
                            throw handler.fatalError("Malformed attribute-list declaration");
                        }
                        if (buf[p] != '"' && buf[p] != '\'') {
                            throw handler.fatalError("Malformed attribute-list declaration");
                        }
                        StringBuilder sb = new StringBuilder();
                        int r = scanQuotedLiteralWithCharRefs(p, sb);
                        if (r < 0) {
                            return -1;
                        }
                        p = r;
                        rawDefault = sb.toString();
                    }
                }
            } else if (buf[p] == '"' || buf[p] == '\'') {
                StringBuilder sb = new StringBuilder();
                int r = scanQuotedLiteralWithCharRefs(p, sb);
                if (r < 0) {
                    return -1;
                }
                p = r;
                rawDefault = sb.toString();
            } else {
                throw handler.fatalError("Malformed attribute-list declaration");
            }

            dtdModel.declareAttribute(elementName, attrName, type, rawDefault);
            // loop continues: another AttDef, or S? '>' to end the declaration
        }
    }

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
        checkNameStartChar(nameStart);
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
            // Captured into instance fields, not locals: the DOCTYPE
            // declaration's completion may be arbitrarily far away across
            // receive() boundaries once the (possibly-resumable)
            // scanDoctypeSubset() path below takes over.
            doctypeExternalPublicId = lastExternalIdPublicId;
            doctypeExternalSystemId = lastExternalIdSystemId;
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
            doctypePendingExternalNames = new HashMap<String, String[]>();
            doctypePendingParamEntities = new HashMap<String, String>();
            doctypePendingParamExternalNames = new HashMap<String, String[]>();
            pos = p + 1;
            inDoctype = true;
            if (!scanDoctypeSubset()) {
                return false;
            }
            inDoctype = false;
            return true;
        }

        // No internal subset: S? '>' remains side-effect-free, atomic - but
        // an external subset may still follow, fetched only now that the
        // declaration itself has fully, atomically committed (matching this
        // whole method's atomic-retry-on-underflow contract: nothing
        // external is ever fetched speculatively, only once we're sure).
        if (buf[p] != '>') {
            throw handler.fatalError("Malformed DOCTYPE declaration");
        }
        p++;
        pos = p;
        finishDoctypeExternalSubset(name);
        doctypeSeen = true;
        doctypeName = name;
        return true;
    }

    /** Shared by both of {@link #scanDoctype}'s/{@link #scanDoctypeSubset}'s
     *  completion paths: fetches and parses the DOCTYPE's external subset,
     *  if {@link #skipExternalId} captured one, then resolves ATTLIST
     *  default values against the now-fully-known entity table (which may
     *  have just grown from the external subset, in addition to whatever
     *  the internal subset, if any, already contributed). */
    private void finishDoctypeExternalSubset(String rootName) throws SAXException {
        if (doctypeExternalSystemId != null) {
            char[] chars = fetchExternalResource("the external DTD subset for \"" + rootName + "\"",
                    doctypeExternalPublicId, doctypeExternalSystemId);
            parseExternalSubset(chars);
        }
        doctypeExternalPublicId = null;
        doctypeExternalSystemId = null;
        resolveAttlistDefaultsAgainstEntities();
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
                    // A parameter entity reference here stands for a
                    // sequence of complete declarations (WFC "PEs in
                    // Internal Subset"), so - unlike every other construct
                    // in this method - it cannot be dispatched until the
                    // whole "%name;" is confirmed present; only then is it
                    // safe to hand off to expandParameterEntityReference(),
                    // whose own nested parse (over the already fully-
                    // buffered replacement text) never itself underflows.
                    int q = pos + 1;
                    while (q < limit && isNameChar(buf[q])) {
                        q++;
                    }
                    if (q >= limit) {
                        return false;
                    }
                    if (buf[q] != ';') {
                        throw handler.fatalError("Malformed parameter entity reference");
                    }
                    expandParameterEntityReference(doctypePendingEntities, doctypePendingExternalNames,
                            doctypePendingParamEntities, doctypePendingParamExternalNames);
                    continue;
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
                                    doctypePendingExternalNames, doctypePendingParamEntities,
                                    doctypePendingParamExternalNames);
                            if (r < 0) {
                                return false;
                            }
                            pos = r;
                        } else {
                            int am = matchKeyword(pos, ATTLIST_MARKER);
                            if (am == KW_NEED_MORE) {
                                return false;
                            }
                            if (am == KW_MATCH) {
                                int r = scanAttlistDeclaration(pos + ATTLIST_MARKER.length);
                                if (r < 0) {
                                    return false;
                                }
                                pos = r;
                            } else {
                                int elm = matchKeyword(pos, ELEMENT_MARKER);
                                if (elm == KW_NEED_MORE) {
                                    return false;
                                }
                                if (elm == KW_MATCH) {
                                    int r = scanElementDeclaration(pos + ELEMENT_MARKER.length);
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
        externalEntityNames.putAll(doctypePendingExternalNames);
        parameterEntities.putAll(doctypePendingParamEntities);
        parameterEntityExternalIds.putAll(doctypePendingParamExternalNames);
        finishDoctypeExternalSubset(doctypeNamePending);
        doctypeSeen = true;
        doctypeName = doctypeNamePending;
        doctypePendingEntities = null;
        doctypePendingExternalNames = null;
        doctypePendingParamEntities = null;
        doctypePendingParamExternalNames = null;
        doctypeNamePending = null;
        doctypeSubsetClosed = false;
        return true;
    }

    /** Resolves entity references embedded in every {@code <!ATTLIST>}
     *  default value declared so far, now that the whole internal subset -
     *  and therefore every general entity - is known (see {@link
     *  #resolveAttlistDefaultValue}). Called once, from this method's own
     *  finishing step. A no-op (single empty-map check per element) for the
     *  common case of no ATTLIST declarations at all. */
    private void resolveAttlistDefaultsAgainstEntities() throws SAXException {
        for (LinkedHashMap<String, DTDModel.AttDef> attrs : dtdModel.allAttlists().values()) {
            for (DTDModel.AttDef def : attrs.values()) {
                if (def.defaultValue != null) {
                    def.defaultValue = resolveAttlistDefaultValue(def.defaultValue);
                }
            }
        }
    }

    /** Validates that {@code name} may be referenced now: declared (as a
     *  general, non-external entity), not currently being expanded (WFC "No
     *  Recursion"), and within the total-expansion-count guard (a simple
     *  mitigation for entity-expansion amplification, aka "billion laughs" -
     *  not a substitute for a real resource budget, but enough for this
     *  milestone's scope). */
    /** @param allowExternal true for a content-context reference (external
     *         entities are fetched there - see {@link
     *         #expandGeneralEntityInContent}); false for an attribute-value
     *         reference, where WFC "No External Entity References" makes
     *         referencing one always fatal, regardless of fetchability. */
    private void checkEntityReferenceable(String name, boolean allowExternal) throws SAXException {
        boolean isGeneral = generalEntities.containsKey(name);
        boolean isExternal = externalEntityNames.containsKey(name);
        if (!isGeneral && !isExternal) {
            throw handler.fatalError("Entity \"" + name + "\" was not declared");
        }
        if (isExternal && !allowExternal) {
            throw handler.fatalError(
                    "External entity \"" + name + "\" may not be referenced in an attribute value");
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
        checkEntityReferenceable(name, true);
        char[] replacementChars;
        String[] externalIds = externalEntityNames.get(name);
        if (externalIds != null) {
            // External general entity: fetched lazily, right here, at first
            // reference - matching how internal entities are also only
            // resolved lazily. A fetched entity's content may begin with an
            // optional text declaration (same '<?xml ...?>' shape as the
            // main document's XML declaration, but version is optional and
            // it's never followed by 'standalone' - close enough to reuse
            // the same strip logic; Scanner does not parse declarations
            // itself either way, per its established boundary).
            replacementChars = XmlDeclUtil.stripXmlDeclaration(fetchExternalEntity(name, externalIds[0], externalIds[1]));
        } else {
            replacementChars = generalEntities.get(name).toCharArray();
        }

        entityExpansionStack.add(name);
        char[] savedBuf = buf;
        int savedPos = pos;
        int savedLimit = limit;
        boolean savedContentRunOpen = contentRunOpen;
        int stackDepthAtEntry = elementStack.size();

        buf = replacementChars;
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
        checkEntityReferenceable(name, false);
        String replacement = generalEntities.get(name);
        entityExpansionStack.add(name);
        try {
            char[] rbuf = replacement.toCharArray();
            return resolveAttributeText(rbuf, "entity \"" + name + "\"");
        } finally {
            entityExpansionStack.remove(entityExpansionStack.size() - 1);
        }
    }

    /**
     * Walks {@code text} left to right, expanding predefined and nested
     * general entity references and rejecting a literal '&lt;' (WFC "No
     * {@literal <} in Attribute Values") - the shared core of {@link
     * #expandGeneralEntityInAttributeValue} and (via {@link
     * #resolveAttlistDefaultValue}) ATTLIST default-value resolution. Both
     * contexts already have their text fully materialized (an entity's
     * replacement text; a default value literal), so this never needs to
     * signal underflow - it always fully resolves in one pass.
     *
     * @param context used only in error messages, to say where a WFC
     *                 violation was found (an entity name, or "an attribute
     *                 default value")
     */
    private String resolveAttributeText(char[] text, String context) throws SAXException {
        int len = text.length;
        StringBuilder sb = new StringBuilder(len);
        int q = 0;
        while (q < len) {
            char c = text[q];
            if (c == '<') {
                throw handler.fatalError("'<' is not allowed in an attribute value (via " + context + ")");
            }
            if (c != '&') {
                sb.append(c);
                q++;
                continue;
            }
            int nameStart = q + 1;
            int p = nameStart;
            while (p < len && isNameChar(text[p])) {
                p++;
            }
            if (p >= len || text[p] != ';') {
                throw handler.fatalError("Malformed entity reference in " + context);
            }
            CharBuffer predef = matchPredefined(text, nameStart, p - nameStart);
            if (predef != null) {
                predef.rewind();
                sb.append(predef);
            } else {
                String refName = new String(text, nameStart, p - nameStart);
                sb.append(expandGeneralEntityInAttributeValue(refName));
            }
            q = p + 1;
        }
        return sb.toString();
    }

    /**
     * Resolves entity references embedded in an {@code <!ATTLIST>} default
     * value's raw literal text (predefined and general entity references
     * kept literal at declaration time, same as an {@code <!ENTITY>}'s own
     * value - see {@link #scanAttlistDeclaration}). Called once per default,
     * from {@link #scanDoctypeSubset}'s finishing step, after the whole
     * internal subset - and therefore every entity - has been parsed, since
     * a default may reference an entity declared later in the same subset.
     */
    private String resolveAttlistDefaultValue(String raw) throws SAXException {
        return resolveAttributeText(raw.toCharArray(), "an attribute default value");
    }

    // ===== External entity/DTD fetching =====
    //
    // Both the DOCTYPE's own external subset and an external general
    // entity's replacement text are fetched the same way: resolve
    // publicId/systemId via entityResolver if set, falling back to
    // resolving systemId against baseSystemId and opening it directly
    // (matching the old parser's own ContentParser.processExternalEntity
    // fallback); decode the fetched bytes with the same BOM/declared-
    // encoding detection ScannerXMLReader uses for the main document
    // (XmlDeclUtil, factored out once needed in both places). Both call
    // sites strip a leading declaration themselves afterward (Scanner does
    // not parse declarations - see class Javadoc "M6" section).

    /** Fetches and decodes {@code publicId}/{@code systemId} - the shared
     *  core of external entity and external DTD subset fetching. {@code
     *  what} is used only in error messages. */
    private char[] fetchExternalResource(String what, String publicId, String systemId) throws SAXException {
        try {
            InputSource resolved = null;
            if (entityResolver != null) {
                resolved = entityResolver.resolveEntity(publicId, systemId);
            }
            InputStream in;
            String encodingHint;
            if (resolved != null && resolved.getByteStream() != null) {
                in = resolved.getByteStream();
                encodingHint = resolved.getEncoding();
            } else {
                String resolvedSystemId = resolveSystemId(systemId);
                if (resolvedSystemId == null) {
                    throw handler.fatalError("Cannot resolve " + what + ": no system identifier");
                }
                in = openStream(resolvedSystemId);
                encodingHint = null;
            }
            byte[] bytes = readAllExternalBytes(in);
            return XmlDeclUtil.decodeBytes(bytes, encodingHint);
        } catch (IOException e) {
            throw handler.fatalError("Failed to fetch " + what + " (" + systemId + "): " + e.getMessage());
        }
    }

    private char[] fetchExternalEntity(String name, String publicId, String systemId) throws SAXException {
        return fetchExternalResource("entity \"" + name + "\"", publicId, systemId);
    }

    private String resolveSystemId(String systemId) {
        if (systemId == null || baseSystemId == null) {
            return systemId;
        }
        try {
            return new URI(baseSystemId).resolve(systemId).toString();
        } catch (URISyntaxException | IllegalArgumentException e) {
            return systemId;
        }
    }

    private static InputStream openStream(String resolvedSystemId) throws IOException {
        try {
            return new URL(resolvedSystemId).openStream();
        } catch (MalformedURLException e) {
            return new FileInputStream(resolvedSystemId);
        }
    }

    private static byte[] readAllExternalBytes(InputStream in) throws IOException {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            while ((n = in.read(chunk)) != -1) {
                out.write(chunk, 0, n);
            }
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    /**
     * Parses a fetched external DTD subset's declarations - the same
     * markupdecl content an internal subset's {@code '['...']'} holds, but
     * unbracketed (terminated by end of the fetched content, not by ']')
     * and, since the whole thing is already fully in memory with nothing
     * more ever arriving, a straight-line one-shot parse rather than a
     * resumable one (no {@code doctypeSubsetClosed}-style state needed -
     * see {@link #scanDoctypeSubset()} for why that resumability exists at
     * all, and why it doesn't apply here). Declarations are merged into
     * {@link #generalEntities}/{@link #externalEntityNames}/{@link
     * #dtdModel} with the same first-wins semantics those already use, so
     * calling this <em>after</em> the internal subset (if any) has already
     * committed its own declarations - see the two call sites in {@link
     * #scanDoctype}/{@link #scanDoctypeSubset} - naturally gives internal
     * declarations precedence over external ones, matching XML 4.2's rule
     * for entities (and applied here, for simplicity, uniformly to
     * element/attribute declarations too).
     */
    private void parseExternalSubset(char[] rawChars) throws SAXException {
        char[] chars = XmlDeclUtil.stripXmlDeclaration(rawChars);
        HashMap<String, String> pendingEntities = new HashMap<String, String>();
        HashMap<String, String[]> pendingExternalNames = new HashMap<String, String[]>();
        HashMap<String, String> pendingParamEntities = new HashMap<String, String>();
        HashMap<String, String[]> pendingParamExternalNames = new HashMap<String, String[]>();

        char[] savedBuf = buf;
        int savedPos = pos;
        int savedLimit = limit;
        buf = chars;
        pos = 0;
        limit = chars.length;
        try {
            parseMarkupDeclSeq(false, pendingEntities, pendingExternalNames, pendingParamEntities,
                    pendingParamExternalNames);
            for (Map.Entry<String, String> entry : pendingEntities.entrySet()) {
                if (!generalEntities.containsKey(entry.getKey())
                        && !externalEntityNames.containsKey(entry.getKey())) {
                    generalEntities.put(entry.getKey(), entry.getValue());
                }
            }
            for (Map.Entry<String, String[]> entry : pendingExternalNames.entrySet()) {
                if (!generalEntities.containsKey(entry.getKey())
                        && !externalEntityNames.containsKey(entry.getKey())) {
                    externalEntityNames.put(entry.getKey(), entry.getValue());
                }
            }
            for (Map.Entry<String, String> entry : pendingParamEntities.entrySet()) {
                if (!parameterEntities.containsKey(entry.getKey())
                        && !parameterEntityExternalIds.containsKey(entry.getKey())) {
                    parameterEntities.put(entry.getKey(), entry.getValue());
                }
            }
            for (Map.Entry<String, String[]> entry : pendingParamExternalNames.entrySet()) {
                if (!parameterEntities.containsKey(entry.getKey())
                        && !parameterEntityExternalIds.containsKey(entry.getKey())) {
                    parameterEntityExternalIds.put(entry.getKey(), entry.getValue());
                }
            }
        } finally {
            buf = savedBuf;
            pos = savedPos;
            limit = savedLimit;
        }
    }

    /**
     * One-shot parse of a sequence of markup declarations (the {@code
     * extSubsetDecl} grammar: {@code (markupdecl | conditionalSect |
     * DeclSep)*}), operating on the currently-swapped {@link #buf}/{@link
     * #pos}/{@link #limit} - the caller owns the swap (and its restoration).
     * Three callers share this: {@link #parseExternalSubset}'s own top-level
     * content; {@link #expandParameterEntityReference}'s nested parse of a
     * parameter entity's replacement text (both from internal-subset and
     * external-subset context - by the time a parameter entity reference is
     * expanded, its replacement text is always already fully known, exactly
     * like a general entity's is by the time {@link
     * #expandGeneralEntityInContent} swaps it in, so this never itself needs
     * to signal underflow); and {@link #parseConditionalSection}'s parse of
     * an INCLUDE section's content, which is itself an {@code extSubsetDecl}
     * per the grammar, just terminated by its own {@code "]]>"} rather than
     * end of buffer.
     *
     * @param stopAtSectionEnd true when parsing an INCLUDE section's content:
     *         stop at (and consume) a {@code "]]>"} rather than requiring
     *         end-of-buffer
     */
    private void parseMarkupDeclSeq(boolean stopAtSectionEnd, HashMap<String, String> pendingEntities,
            HashMap<String, String[]> pendingExternalNames, HashMap<String, String> pendingParamEntities,
            HashMap<String, String[]> pendingParamExternalNames) throws SAXException {
        while (true) {
            pos = skipOptionalWhitespace(pos);
            if (stopAtSectionEnd && pos + 2 < limit && buf[pos] == ']' && buf[pos + 1] == ']'
                    && buf[pos + 2] == '>') {
                pos += 3;
                return;
            }
            if (pos >= limit) {
                if (stopAtSectionEnd) {
                    throw handler.fatalError("Unterminated INCLUDE conditional section");
                }
                return;
            }
            char c = buf[pos];
            if (c == '%') {
                expandParameterEntityReference(pendingEntities, pendingExternalNames, pendingParamEntities,
                        pendingParamExternalNames);
                continue;
            }
            if (c != '<' || pos + 1 >= limit) {
                throw handler.fatalError("Malformed markup declaration");
            }
            char c2 = buf[pos + 1];
            if (c2 == '?') {
                if (!scanPI(pos)) {
                    throw handler.fatalError("Malformed processing instruction");
                }
            } else if (c2 == '!') {
                if (pos + 2 >= limit) {
                    throw handler.fatalError("Malformed markup declaration");
                }
                if (buf[pos + 2] == '-') {
                    if (!scanComment(pos)) {
                        throw handler.fatalError("Malformed comment");
                    }
                } else if (buf[pos + 2] == '[') {
                    parseConditionalSection(pendingEntities, pendingExternalNames, pendingParamEntities,
                            pendingParamExternalNames);
                } else {
                    int em = matchKeyword(pos, ENTITY_MARKER);
                    if (em == KW_MATCH) {
                        int r = scanEntityDeclaration(pos + ENTITY_MARKER.length, pendingEntities,
                                pendingExternalNames, pendingParamEntities, pendingParamExternalNames);
                        if (r < 0) {
                            throw handler.fatalError("Malformed entity declaration");
                        }
                        pos = r;
                    } else {
                        int am = matchKeyword(pos, ATTLIST_MARKER);
                        if (am == KW_MATCH) {
                            int r = scanAttlistDeclaration(pos + ATTLIST_MARKER.length);
                            if (r < 0) {
                                throw handler.fatalError("Malformed attribute-list declaration");
                            }
                            pos = r;
                        } else {
                            int elm = matchKeyword(pos, ELEMENT_MARKER);
                            if (elm == KW_MATCH) {
                                int r = scanElementDeclaration(pos + ELEMENT_MARKER.length);
                                if (r < 0) {
                                    throw handler.fatalError("Malformed element declaration");
                                }
                                pos = r;
                            } else {
                                int r = skipMarkupDeclaration(pos);
                                if (r < 0) {
                                    throw handler.fatalError("Malformed markup declaration");
                                }
                                pos = r;
                            }
                        }
                    }
                }
            } else {
                throw handler.fatalError("Malformed markup declaration");
            }
        }
    }

    /**
     * Parses one {@code conditionalSect} ({@code <![ INCLUDE [ ... ]]>} or
     * {@code <![ IGNORE [ ... ]]>}), {@code pos} positioned at the leading
     * '<'. An INCLUDE section's content is itself a nested {@code
     * extSubsetDecl} sequence, parsed via a recursive {@link
     * #parseMarkupDeclSeq} call that stops at its own matching {@code "]]>"}
     * rather than end-of-buffer; an IGNORE section's content is skipped
     * without being parsed at all, via {@link #skipIgnoredSection}. Mutates
     * {@link #pos} to just past the section's own closing {@code "]]>"}.
     */
    private void parseConditionalSection(HashMap<String, String> pendingEntities,
            HashMap<String, String[]> pendingExternalNames, HashMap<String, String> pendingParamEntities,
            HashMap<String, String[]> pendingParamExternalNames) throws SAXException {
        pos += 3; // "<!["
        pos = skipOptionalWhitespace(pos);
        int im = matchKeyword(pos, INCLUDE_MARKER);
        boolean include;
        if (im == KW_MATCH) {
            include = true;
            pos += INCLUDE_MARKER.length;
        } else {
            int gm = matchKeyword(pos, IGNORE_MARKER);
            if (gm != KW_MATCH) {
                throw handler.fatalError("Malformed conditional section: expected INCLUDE or IGNORE");
            }
            include = false;
            pos += IGNORE_MARKER.length;
        }
        pos = skipOptionalWhitespace(pos);
        if (pos >= limit || buf[pos] != '[') {
            throw handler.fatalError("Malformed conditional section");
        }
        pos++;
        if (include) {
            parseMarkupDeclSeq(true, pendingEntities, pendingExternalNames, pendingParamEntities,
                    pendingParamExternalNames);
        } else {
            skipIgnoredSection();
        }
    }

    /**
     * Skips an IGNORE conditional section's content from {@link #pos} (just
     * past its opening '[') to just past its closing {@code "]]>"}, without
     * parsing any of it - bracket-nesting-aware per the {@code
     * ignoreSectContents} grammar, so a nested {@code "<!["..."]]>"} pair
     * inside the ignored content doesn't terminate the outer section early.
     */
    private void skipIgnoredSection() throws SAXException {
        int depth = 1;
        while (depth > 0) {
            if (pos + 2 < limit && buf[pos] == '<' && buf[pos + 1] == '!' && buf[pos + 2] == '[') {
                depth++;
                pos += 3;
            } else if (pos + 2 < limit && buf[pos] == ']' && buf[pos + 1] == ']' && buf[pos + 2] == '>') {
                depth--;
                pos += 3;
            } else if (pos < limit) {
                pos++;
            } else {
                throw handler.fatalError("Unterminated IGNORE conditional section");
            }
        }
    }

    /**
     * Expands one {@code %name;} parameter entity reference, {@link #pos}
     * positioned at the '%'. The referenced parameter entity's replacement
     * text - a literal value captured at declaration time, or freshly
     * fetched via {@link #fetchExternalResource} if declared external,
     * exactly like an external general entity is fetched lazily at first
     * content reference (see {@link #expandGeneralEntityInContent}) - is
     * parsed as its own nested {@link #parseMarkupDeclSeq} sequence, so any
     * declarations it contains contribute to the same {@code pending*} maps
     * the caller is accumulating into (WFC "PEs in Internal Subset" is
     * enforced by construction: this is only ever reached at a declaration-
     * separator position, never mid-declaration). {@link #pos} ends up just
     * past the reference's trailing ';' in the outer buffer.
     */
    private void expandParameterEntityReference(HashMap<String, String> pendingEntities,
            HashMap<String, String[]> pendingExternalNames, HashMap<String, String> pendingParamEntities,
            HashMap<String, String[]> pendingParamExternalNames) throws SAXException {
        int nameStart = pos + 1;
        int q = nameStart;
        while (q < limit && isNameChar(buf[q])) {
            q++;
        }
        if (q >= limit || buf[q] != ';') {
            throw handler.fatalError("Malformed parameter entity reference");
        }
        if (q == nameStart) {
            throw handler.fatalError("Malformed parameter entity reference");
        }
        checkNameStartChar(nameStart);
        String name = new String(buf, nameStart, q - nameStart);
        int resumeAt = q + 1;

        char[] replacementChars = resolveParameterEntityReplacement(name, pendingParamEntities,
                pendingParamExternalNames);

        if (parameterEntityExpansionStack.contains(name)) {
            throw handler.fatalError("Recursive parameter entity reference: %" + name + ";");
        }
        if (++entityExpansionCount > MAX_ENTITY_EXPANSIONS) {
            throw handler.fatalError("Entity expansion limit exceeded");
        }
        parameterEntityExpansionStack.add(name);
        char[] savedBuf = buf;
        int savedLimit = limit;
        buf = replacementChars;
        pos = 0;
        limit = buf.length;
        try {
            parseMarkupDeclSeq(false, pendingEntities, pendingExternalNames, pendingParamEntities,
                    pendingParamExternalNames);
            if (pos != limit) {
                throw handler.fatalError("Parameter entity \"" + name + "\" replacement text is not well-formed");
            }
        } finally {
            buf = savedBuf;
            limit = savedLimit;
            parameterEntityExpansionStack.remove(parameterEntityExpansionStack.size() - 1);
        }
        pos = resumeAt;
    }

    /** Resolves {@code name}'s replacement text for {@link
     *  #expandParameterEntityReference}: an already-declared literal value
     *  (checked first among {@code pendingParamEntities} - declared so far in
     *  the subset currently being parsed - then the scanner-wide {@link
     *  #parameterEntities}, for a name declared in an earlier-parsed subset),
     *  or a freshly-fetched external one. */
    private char[] resolveParameterEntityReplacement(String name, HashMap<String, String> pendingParamEntities,
            HashMap<String, String[]> pendingParamExternalNames) throws SAXException {
        String literal = pendingParamEntities.get(name);
        if (literal == null) {
            literal = parameterEntities.get(name);
        }
        if (literal != null) {
            return literal.toCharArray();
        }
        String[] externalIds = pendingParamExternalNames.get(name);
        if (externalIds == null) {
            externalIds = parameterEntityExternalIds.get(name);
        }
        if (externalIds == null) {
            throw handler.fatalError("Parameter entity \"%" + name + ";\" was not declared");
        }
        char[] fetched = fetchExternalResource("parameter entity \"" + name + "\"", externalIds[0], externalIds[1]);
        return XmlDeclUtil.stripXmlDeclaration(fetched);
    }

    /**
     * Injects synthetic {@link XMLHandler#startAttribute}/{@link
     * XMLHandler#attributeValueContent} events for every {@code <!ATTLIST>}-
     * declared attribute of {@code elementName} that was not specified on
     * this start tag and has a default value to inject ({@code #FIXED} or a
     * literal default - not {@code #REQUIRED}/{@code #IMPLIED}, which have
     * none). Called from {@link #scanAttributesAndTagEnd} immediately before
     * {@link XMLHandler#endAttributes()}. A no-op (single map lookup) for
     * the common case of an element with no ATTLIST declaration at all.
     */
    private void applyAttributeDefaults(String elementName) throws SAXException {
        Map<String, DTDModel.AttDef> declared = dtdModel.getAttributes(elementName);
        if (declared == null) {
            return;
        }
        for (Map.Entry<String, DTDModel.AttDef> entry : declared.entrySet()) {
            String name = entry.getKey();
            DTDModel.AttDef def = entry.getValue();
            if (def.defaultValue == null || wasAttributeSeen(name)) {
                continue;
            }
            handler.startAttribute(name, def.type);
            handler.attributeValueContent(CharBuffer.wrap(def.defaultValue), true);
        }
    }

}

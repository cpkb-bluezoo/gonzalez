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
import java.io.CharArrayWriter;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.CharBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.xml.sax.EntityResolver;
import org.xml.sax.InputSource;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.ext.EntityResolver2;
import org.xml.sax.ext.Locator2;

/**
 * Hot-path XML scanner: hand-written, specialized recognition of element
 * start/end tags, attributes, text content, character and entity
 * references, comments, PIs, CDATA sections, and DOCTYPE declarations
 * (internal and external subsets), emitting directly into {@link
 * XMLHandler} - no intermediate token stream.
 * <p>
 * <b>Division of responsibility with the rest of the pipeline.</b> Byte-to-
 * char decoding and line-ending normalisation happen upstream, in {@link
 * ExternalEntityDecoder} - by the time a character reaches this class it
 * is already decoded and normalised. Namespace resolution happens
 * downstream: this class always reports {@code xmlns}/{@code xmlns:prefix}
 * as a regular attribute (matching namespace-unaware behaviour); {@link
 * NamespaceFilter}, inserted in front of a namespace-aware consumer,
 * reroutes it to {@link XMLHandler#namespace} instead. Duplicate-attribute
 * detection (WFC "Unique Att Spec") is likewise enforced downstream, by
 * whatever consumes these events (see {@link SAXAdapter}'s reference-
 * equality scan against {@link #namePool}-interned names) - this class does
 * not need to track per-element attribute names itself for that purpose, so
 * it doesn't.
 * <p>
 * <b>DOCTYPE, entities, and DTD-driven behaviour.</b> {@code <!DOCTYPE ...>}
 * is recognised, including an optional external ID and an optional internal
 * subset; an external subset, if the DOCTYPE names one, is fetched (via an
 * {@link EntityResolver} if set, otherwise resolved against {@link
 * #baseSystemId} and opened directly) and parsed the same way once the
 * internal subset (if any) has committed, giving internal declarations
 * precedence for a repeated name. Within either subset, {@code <!ENTITY
 * Name "value">} (general, quoted-literal) is parsed and expanded lazily at
 * first reference in content or attribute values; an external general
 * entity ({@code SYSTEM}/{@code PUBLIC}) is fetched the same lazy way.
 * Parameter entities ({@code <!ENTITY % Name ...>} and {@code %Name;}
 * references) are supported both between declarations and, in an external
 * subset, inside conditional sections ({@code <![INCLUDE[...]]>}/
 * {@code <![IGNORE[...]]>}) - see {@link #expandParameterEntityReference}/
 * {@link #parseMarkupDeclSeq}. {@code <!ELEMENT>} and {@code <!ATTLIST>}
 * declarations are parsed into {@link DTDModel}, which drives attribute
 * defaulting, type-aware attribute value normalisation, content-type-driven
 * ignorable-whitespace determination (see {@link XMLHandler#characters}),
 * and - only when
 * {@link #validationEnabled} - full content-model and attribute validity
 * constraint (VC) checking (content-model tree parsing: {@link
 * #parseContentModelGroup}; per-element validation: {@link
 * #pushElementValidator}/{@link #popAndValidateElement}; attribute VCs:
 * {@link #checkAttributeValueVCs}/{@link #checkAttlistDeclarationVCs}). A
 * VC violation is reported via {@link XMLHandler#error} (recoverable) -
 * never {@link XMLHandler#fatalError} - since an invalid document may still
 * be well-formed. See {@link #expandGeneralEntityInContent}/{@link
 * #expandGeneralEntityInAttributeValue} for how entity expansion reuses
 * this class's own suspend/resume machinery instead of needing new
 * machinery: an entity's replacement text is always fully buffered in
 * memory by the time it can be referenced, so "expanding" it is just
 * swapping {@link #buf}/{@link #pos}/{@link #limit} to a private array and
 * recursively calling {@link #scan()} (for content, which may contain
 * markup) or walking it directly (for attribute values, which never do).
 * Standalone-document validity constraints and ENTITY/ENTITIES values
 * against declared unparsed entities are enforced when validation is
 * enabled.
 * <p>
 * <b>Zero allocation.</b> This class's job is to identify contiguous runs
 * and push them downstream as fast as possible - it never assembles a
 * complete value into a buffer of its own before emitting (validation is
 * the one deliberate exception: a VC check inherently needs the whole
 * value, so the non-CDATA-normalisation path that already materializes one
 * is reused rather than adding a second). Literal runs are otherwise always
 * zero-copy views directly into the scan buffer. Entity references are
 * backed either by static, invariant, shared buffers (predefined entities -
 * their content never changes, so no copying protocol is needed) or by a
 * single small buffer allocated once per Scanner instance (numeric
 * character references - see {@link #decodeEntityRef()} and {@link
 * XMLHandler#saveBuffers()}). See {@link XMLHandler}'s class Javadoc for
 * the full streaming/{@code end}-flag/{@code saveBuffers()} design this
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
 * <b>Name interning.</b> Element/attribute names are interned via {@link
 * #namePool} ({@link PackedName}), which packs up to 12 characters per
 * candidate into primitive {@code long}s for quad-packed comparison instead
 * of a per-character hash-then-compare loop - see its own class Javadoc for
 * the design. The WFC element-type-match stack ({@link #elementStack})
 * stores the interned qName rather than a separate packed handle: since
 * both the pushed start-tag name and the compared end-tag name go through
 * the same pool, a correctly-matched tag pair is the same canonical String
 * instance, and {@code String.equals()}'s own reference-equality fast path
 * already makes that comparison O(1) in the common case.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class Scanner implements Locator2 {

    private static final int INITIAL_CAPACITY = 8192;

    private final XMLHandler handler;
    private final String locatorPublicId;
    private final String locatorSystemId;
    private String encoding;
    private boolean documentStarted;
    private final boolean deferDocumentStartUntilEncoding;

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

    /** True while the attribute currently being scanned needs its value
     *  materialized (accumulated into {@link #normalizeBuilder}) rather than
     *  streamed straight through to {@link XMLHandler#attributeValueContent}
     *  as-is - see {@link #emitAttributeValueContent}. True whenever {@link
     *  #collapseCurrentAttrValue} is (the value itself needs rewriting, so
     *  streaming the original chunks would be wrong regardless of
     *  validation), and also whenever {@link #validationEnabled} (even a
     *  CDATA attribute's - otherwise-untouched - value must still be seen
     *  whole at least once, for VC "Fixed Attribute Default" and friends via
     *  {@link #checkAttributeValueVCs}, which a CDATA type is not otherwise
     *  exempt from). Materializing the value defeats the zero-copy streaming
     *  path, but only when validation is actually enabled or the ATTLIST
     *  actually declares this attribute non-CDATA; the common (no
     *  validation, or CDATA/no-DTD) case is untouched. {@link
     *  #normalizeBuilder} is a single reused field, not a pool, since only
     *  one attribute is ever being scanned at a time. */
    private boolean normalizingCurrentAttribute;
    /** True while the attribute currently being scanned is declared with a
     *  non-CDATA type, meaning its value needs the type-dependent collapse
     *  normalisation (XML 3.3.3) actually applied (not just materialized) -
     *  see {@link #normalizingCurrentAttribute} for the broader condition
     *  under which the value is merely accumulated. */
    private boolean collapseCurrentAttrValue;
    private final StringBuilder normalizeBuilder = new StringBuilder();

    /** The attribute currently being scanned's declaring element/name/type,
     *  remembered here (not just as locals in {@link
     *  #scanAttributesAndTagEnd}) because {@link #emitAttributeValueContent}
     *  needs them at the value's completion point to run VC checks, and that
     *  completion may happen on a resumed call (via {@link
     *  #inAttributeValue}) well after the locals that first knew them have
     *  gone out of scope. Only meaningful while {@link
     *  #normalizingCurrentAttribute} is true. */
    private String currentAttrElementName;
    private String currentAttrName;
    private String currentAttrType;

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

    /** Content-model validator stack, parallel to {@link #elementStack}
     *  ({@code validatorStack.get(i)} validates {@code
     *  elementStack.get(i)}'s content) - null until first needed, so the
     *  overwhelmingly common (validation disabled) case pays nothing.
     *  Reuses {@link ContentModelValidator}/{@link ElementDeclaration}
     *  unchanged from the old tokenizer-based pipeline; see {@link
     *  DTDModel}'s own Javadoc
     *  for why there was nothing to port, only to call. */
    private ArrayList<ContentModelValidator> validatorStack;

    /** Pushes a new validator for {@code qName} (just-opened element),
     *  first recording {@code qName} as a child of whatever validator was
     *  already on top (its parent) - both steps only when {@link
     *  #validationEnabled}, called from {@link #scanStartTag} before {@code
     *  qName} is pushed onto {@link #elementStack}. An element with no
     *  {@code <!ELEMENT>} declaration at all is a VC "Element Valid"
     *  violation, reported once here, then treated as {@code ANY} so
     *  scanning continues without cascading further errors for its
     *  (unconstrained) descendants. */
    private void pushElementValidator(String qName) throws SAXException {
        if (validatorStack == null) {
            validatorStack = new ArrayList<ContentModelValidator>();
        }
        if (!validatorStack.isEmpty()) {
            String error = validatorStack.get(validatorStack.size() - 1).addChildElement(qName);
            if (error != null) {
                handler.error(error);
            }
        }
        ElementDeclaration decl = dtdModel.getElementDeclaration(qName);
        if (decl == null) {
            handler.error("Validity Constraint: Element Valid (Section 3.1). Element \"" + qName
                    + "\" is not declared in the DTD.");
            decl = new ElementDeclaration();
            decl.name = qName;
            decl.contentType = ElementDeclaration.ContentType.ANY;
        }
        validatorStack.add(new ContentModelValidator(decl));
    }

    /** Pops and validates the completed element's content - called from
     *  {@link #scanEndTag}/the self-closing branch of {@link
     *  #scanAttributesAndTagEnd}, only when {@link #validationEnabled}. */
    private void popAndValidateElement() throws SAXException {
        ContentModelValidator v = validatorStack.remove(validatorStack.size() - 1);
        String error = v.validate();
        if (error != null) {
            handler.error(error);
        }
    }

    /** Records a run of character data against the innermost open
     *  validator, if {@link #validationEnabled} and at least one element is
     *  open (never true in the prolog/epilog, where content is restricted
     *  to whitespace by a WFC {@link #scanContent} already enforces
     *  unconditionally, independent of validation). */
    private void recordTextForValidation(String text, boolean isWhitespaceOnly) throws SAXException {
        if (validatorStack != null && !validatorStack.isEmpty()) {
            String error = validatorStack.get(validatorStack.size() - 1).addTextContent(text, isWhitespaceOnly);
            if (error != null) {
                handler.error(error);
            }
        }
    }

    /** Names of attributes specified on the start tag currently being
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
     * Interns element/attribute names, avoiding a fresh {@code new
     * String(...)} allocation for every single occurrence of a repeated
     * name - element/attribute vocabulary is typically small and reused
     * constantly throughout a document. See class Javadoc for the choice of
     * {@link PackedName} over {@link InternedStringPool}.
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

    // ===== DOCTYPE / entities (cold path) =====

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

    /** An application-supplied external subset for a DOCTYPE that declared
     *  no external ID of its own - obtained from {@link
     *  EntityResolver2#getExternalSubset} in {@link #scanDoctype} (see
     *  {@link #requestExternalSubset}) and consumed, in place of a fetch,
     *  by {@link #finishDoctypeExternalSubset}. */
    private InputSource doctypeExternalSubsetSource;
    private String doctypeExternalSystemId;

    /** Durable copies of the DOCTYPE declaration's own declared external ID,
     *  captured alongside {@link #doctypeExternalPublicId}/{@link
     *  #doctypeExternalSystemId} in {@link #scanDoctype} but never cleared
     *  by {@link #finishDoctypeExternalSubset}. Unlike that
     *  pair, these are never overwritten by an {@link
     *  EntityResolver2#getExternalSubset}-supplied source's identifiers:
     *  they record only what the document itself declared. */
    private String doctypePublicId;
    private String doctypeSystemId;

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

    /** {@link #elementStack} depth at entry to each currently-active general
     *  entity expansion in content (see {@link
     *  #expandGeneralEntityInContent}), one entry per {@link
     *  #entityExpansionStack} entry. An end tag encountered while inside an
     *  entity's replacement text must not close an element that was opened
     *  <em>before</em> that entity started - i.e. {@link #elementStack}'s
     *  size must never drop to or below the innermost active entity's own
     *  floor - checked in {@link #scanEndTag}. This is a stronger,
     *  continuously-enforced condition than merely comparing {@link
     *  #elementStack}'s size at entry and exit (which alone would miss the
     *  classic {@code <!ENTITY e "</foo><foo>">} counter-example: the entity
     *  closes an outer element and re-opens a same-named one, so the net
     *  depth change is zero even though a boundary was still crossed). */
    private final ArrayList<Integer> entityStackFloors = new ArrayList<Integer>();

    private int entityExpansionCount;

    /** Counts one entity expansion (general or parameter) against {@link
     *  ScannerSettings#entityExpansionLimit}, which - matching {@link
     *  EntityStack#setExpansionLimit}'s contract for the old pipeline - is
     *  unlimited when zero or negative. */
    private void checkEntityExpansionLimit() throws SAXException {
        entityExpansionCount++;
        int expansionLimit = settings.entityExpansionLimit;
        if (expansionLimit > 0 && entityExpansionCount > expansionLimit) {
            throw handler.fatalError("Entity expansion limit (" + expansionLimit + ") exceeded");
        }
    }

    /** True while the text currently being scanned for markup declarations
     *  originates from the external DTD subset, or from an external
     *  parameter entity's own replacement text (nested arbitrarily deep -
     *  set around {@link #parseExternalSubset} and, within {@link
     *  #expandParameterEntityReference}, only for an external entity's own
     *  nested {@link #parseMarkupDeclSeq} call; an internal parameter
     *  entity's expansion leaves it exactly as inherited from the caller).
     *  False for the internal subset's own literal source text. Read by
     *  {@link #splicePEReferenceAt} to decide between WFC "PEs in Internal
     *  Subset" (a parameter entity reference is simply not allowed within a
     *  markup declaration's own syntax there) and actually expanding the
     *  reference (allowed everywhere else, per that WFC's own parenthetical
     *  exception). */
    private boolean parsingExternalContent;

    /** XML 1.1 vs 1.0 mode, affecting only numeric character reference
     *  legality (see {@link #decodeEntityRef()}) and the lookup tables
     *  content/attribute-value scanning use (see {@link
     *  #CONTENT_STOP_XML10}). Either supplied by the caller up front (every
     *  direct/test construction, when the version is already known), or
     *  defaulted at construction and updated exactly once via {@link
     *  #setXml11} - which {@link ExternalEntityDecoder} calls once it has
     *  resolved the XML/text declaration's version directly from the raw
     *  bytes, strictly before any real document content reaches this
     *  Scanner. Not final for that reason - {@link #setXml11} keeps {@link
     *  #contentStopTable}/{@link #quotAttrStopTable}/{@link
     *  #aposAttrStopTable} in sync whenever it changes. */
    private boolean xml11;

    /** Resolves external entity/DTD identifiers to actual bytes - see
     *  "external entity/DTD fetching" below. Null means no external
     *  fetching capability at all: an external SYSTEM/PUBLIC identifier
     *  will still be recognised syntactically, but referencing it is
     *  rejected the same way an unresolvable one would be. */
    private final EntityResolver entityResolver;

    /** The <em>current</em> base URI for resolving a relative SYSTEM
     *  identifier against - see {@link #resolveSystemId}. Initially the
     *  document's own system identifier (null if unknown, e.g. parsing from
     *  an in-memory string with no URI at all), but saved/temporarily
     *  changed/restored while parsing an external DTD subset or external
     *  parameter entity's own replacement text (see {@link
     *  #finishDoctypeExternalSubset}/{@link #expandParameterEntityReference})
     *  to that resource's own resolved location, so a relative SYSTEM
     *  identifier declared within it - e.g. an external parameter entity
     *  declared inside another one, fetched from a different directory -
     *  resolves against <em>its own</em> location, not the original
     *  document's. Every external parameter entity's own declaration
     *  additionally captures this field's value at the moment it is parsed
     *  (see {@link #scanEntityDeclaration}'s external-PE branch), since it
     *  may not actually be *expanded* until much later, indirectly, from a
     *  completely different lexical context (an internal parameter entity
     *  that merely names it, itself expanded from yet another context) -
     *  {@link #resolveParameterEntityReplacement} resolves against that
     *  captured, per-entity value, never this field's value at expansion
     *  time. */
    private String baseSystemId;

    /** True if DTD validity constraint (VC) checking is enabled. When
     *  false (the default), no content-model tree is built and no {@link
     *  XMLHandler#error} call is ever made. A VC violation is always
     *  reported via {@link XMLHandler#error} (recoverable), never
     *  {@link XMLHandler#fatalError} - an invalid document may still be
     *  well-formed. */
    private final boolean validationEnabled;

    /** True when namespace processing is active downstream (a {@link
     *  NamespaceFilter} sits between this class and the ultimate consumer -
     *  see {@code Parser#ensureScannerReady}). Scanner itself stays
     *  namespace-<em>unaware</em> either way (it always reports {@code
     *  xmlns} as a plain attribute, per {@link NamespaceFilter}'s own class
     *  Javadoc), but a handful of WFCs from Namespaces in XML - a colon is
     *  forbidden in a PI target, entity name, or notation name, none of
     *  which are ever exposed to {@link NamespaceFilter} as their own event
     *  (unlike element/attribute qNames) - can only be checked here, at the
     *  point Scanner itself parses that name. */
    private final boolean namespaceAware;

    /** Security/entity configuration this scanner was constructed with -
     *  see {@link ScannerSettings}. Enforced for external-entity fetch
     *  gating, DOCTYPE rejection, and expansion limiting; {@link
     *  ScannerSettings#PERMISSIVE} preserves current behaviour for every
     *  caller not going through {@code Parser}. */
    private final ScannerSettings settings;

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

    /** Convenience constructor with no DTD validation. */
    Scanner(XMLHandler handler, boolean xml11, EntityResolver entityResolver, String baseSystemId)
            throws SAXException {
        this(handler, xml11, entityResolver, baseSystemId, false);
    }

    /** Convenience constructor with namespace processing assumed off (see
     *  {@link #namespaceAware}) - every caller before this milestone. */
    Scanner(XMLHandler handler, boolean xml11, EntityResolver entityResolver, String baseSystemId,
            boolean validationEnabled) throws SAXException {
        this(handler, xml11, entityResolver, baseSystemId, validationEnabled, false);
    }

    /** Convenience constructor with permissive {@link ScannerSettings} -
     *  every direct/test caller before this milestone. */
    Scanner(XMLHandler handler, boolean xml11, EntityResolver entityResolver, String baseSystemId,
            boolean validationEnabled, boolean namespaceAware) throws SAXException {
        this(handler, xml11, entityResolver, baseSystemId, validationEnabled, namespaceAware,
                ScannerSettings.PERMISSIVE);
    }

    Scanner(XMLHandler handler, boolean xml11, EntityResolver entityResolver, String baseSystemId,
            boolean validationEnabled, boolean namespaceAware, ScannerSettings settings)
            throws SAXException {
        this(handler, xml11, entityResolver, null, baseSystemId, validationEnabled, namespaceAware, settings);
    }

    Scanner(XMLHandler handler, boolean xml11, EntityResolver entityResolver, String publicId, String baseSystemId,
            boolean validationEnabled, boolean namespaceAware, ScannerSettings settings)
            throws SAXException {
        this(handler, xml11, entityResolver, publicId, baseSystemId, validationEnabled, namespaceAware, settings, false);
    }

    Scanner(XMLHandler handler, boolean xml11, EntityResolver entityResolver, String publicId, String baseSystemId,
            boolean validationEnabled, boolean namespaceAware, ScannerSettings settings,
            boolean deferDocumentStartUntilEncoding) throws SAXException {
        this.handler = handler;
        this.locatorPublicId = publicId;
        this.locatorSystemId = baseSystemId;
        this.xml11 = xml11;
        this.entityResolver = entityResolver;
        this.baseSystemId = baseSystemId;
        this.validationEnabled = validationEnabled;
        this.namespaceAware = namespaceAware;
        this.settings = settings;
        this.deferDocumentStartUntilEncoding = deferDocumentStartUntilEncoding;
        this.buf = new char[INITIAL_CAPACITY];
        this.contentStopTable = xml11 ? CONTENT_STOP_XML11 : CONTENT_STOP_XML10;
        this.quotAttrStopTable = xml11 ? QUOT_ATTR_STOP_XML11 : QUOT_ATTR_STOP_XML10;
        this.aposAttrStopTable = xml11 ? APOS_ATTR_STOP_XML11 : APOS_ATTR_STOP_XML10;
        this.legalLiteralTable = xml11 ? LEGAL_LITERAL_XML11 : LEGAL_LITERAL_XML10;
        if (!deferDocumentStartUntilEncoding) {
            startDocument();
        }
    }

    @Override
    public String getPublicId() {
        return locatorPublicId;
    }

    @Override
    public String getSystemId() {
        return locatorSystemId;
    }

    @Override
    public int getLineNumber() {
        return -1;
    }

    @Override
    public int getColumnNumber() {
        return -1;
    }

    @Override
    public String getXMLVersion() {
        return xml11 ? "1.1" : "1.0";
    }

    @Override
    public String getEncoding() {
        return encoding;
    }

    public void setEncoding(String encoding) throws SAXException {
        this.encoding = encoding;
        if (deferDocumentStartUntilEncoding && !documentStarted) {
            startDocument();
        }
    }

    private void startDocument() throws SAXException {
        handler.setLocator(this);
        handler.startDocument();
        documentStarted = true;
    }

    /** Resolved from {@link #xml11} at construction, and re-resolved by
     *  {@link #setXml11} if it changes - see the {@code CONTENT_STOP_XML10}/
     *  {@code XML11} family's Javadoc. */
    private boolean[] contentStopTable;
    private boolean[] quotAttrStopTable;
    private boolean[] aposAttrStopTable;
    private boolean[] legalLiteralTable;

    /**
     * Feeds more decoded character data to the scanner. May be called
     * multiple times; an incomplete construct at the end of one call is
     * resumed (or, for atomic constructs, retried from its start) on the
     * next. Fires {@link XMLHandler#saveBuffers()} once, after all
     * currently-available data has been scanned, since the scan buffer may
     * be compacted/reused before the next call - see XMLHandler's class
     * Javadoc.
     */
    public void receive(CharBuffer data) throws SAXException {
        append(data);
        scan();
        handler.saveBuffers();
    }

    /**
     * Signals end of input. Reports a fatal error if the document ends
     * mid-construct or with unclosed elements.
     */
    public void close() throws SAXException {
        if (!documentStarted) {
            startDocument();
        }
        if (inStartTag || inAttributeValue || inDoctype || !elementStack.isEmpty()) {
            throw handler.fatalError("Document ended unexpectedly (unclosed element or tag)");
        }
        if (!rootStarted) {
            throw handler.fatalError("Document must contain a root element");
        }
        checkEntityValuesDoNotReferenceUnparsedEntities();
        if (validationEnabled) {
            checkPendingIdrefs();
            checkUnparsedEntityNotationsDeclared();
            checkAttlistNotationNamesDeclared();
        }
        handler.endDocument();
    }

    /** WFC "Parsed Entity" (Section 4.1): "An entity reference must not
     *  contain the name of an unparsed entity" - unconditional (not gated
     *  on {@link #validationEnabled}, unlike the VCs alongside it, since
     *  this is a well-formedness constraint) and fatal. A general entity's
     *  own {@code EntityValue} keeps any {@code &name;} reference it
     *  contains literal/unexpanded at declaration time (XML 4.5's
     *  "Bypassed" construct - see {@link #scanQuotedLiteralWithCharRefs}),
     *  so this can only be checked once the whole DTD - and therefore every
     *  entity's unparsed-or-not status (an NDATA annotation may be declared
     *  anywhere, including after the entity that references it) - is known,
     *  deferred to here alongside this class's other whole-DTD checks. Only
     *  the entity's own declared value is scanned - a reference occurring
     *  when that entity is later used in content is instead handled by
     *  {@link #checkEntityReferenceable} rejecting the unparsed entity's own
     *  name directly (it is never in {@link #generalEntities} to begin
     *  with - only {@link #externalEntityNames} - so a direct {@code
     *  &unparsed;} in content already fails there). */
    private void checkEntityValuesDoNotReferenceUnparsedEntities() throws SAXException {
        for (Map.Entry<String, String> entry : generalEntities.entrySet()) {
            String value = entry.getValue();
            int len = value.length();
            int q = 0;
            while (q < len) {
                char c = value.charAt(q);
                if (c != '&') {
                    q++;
                    continue;
                }
                int nameStart = q + 1;
                int p = nameStart;
                while (p < len && isNameChar(value.charAt(p))) {
                    p++;
                }
                if (p >= len || value.charAt(p) != ';') {
                    q++;
                    continue;
                }
                if (matchPredefined(value.toCharArray(), nameStart, p - nameStart) == null) {
                    String refName = value.substring(nameStart, p);
                    String[] ids = externalEntityNames.get(refName);
                    if (ids != null && ids[2] != null) {
                        throw handler.fatalError("Well-Formedness Constraint: Parsed Entity (Section 4.1). "
                                + "Entity \"" + entry.getKey() + "\" references unparsed entity \"" + refName
                                + "\" - an entity reference must not name an unparsed entity.");
                    }
                }
                q = p + 1;
            }
        }
    }

    /** VC "Notation Declared" (Section 4.2.2): every {@code NDATA}
     *  annotation's notation name must match a declared {@code
     *  <!NOTATION>} - checked once the whole DTD (internal and external
     *  subset) is known, the same deferred-to-the-end timing {@link
     *  #checkPendingIdrefs} uses, since the notation may legally be
     *  declared anywhere in the DTD, not necessarily before the entity
     *  that names it. */
    private void checkUnparsedEntityNotationsDeclared() throws SAXException {
        for (Map.Entry<String, String[]> entry : externalEntityNames.entrySet()) {
            String notationName = entry.getValue()[2];
            if (notationName != null && !declaredNotations.contains(notationName)) {
                handler.error("Validity Constraint: Notation Declared (Section 4.2.2). Entity \"" + entry.getKey()
                        + "\" names undeclared notation \"" + notationName + "\".");
            }
        }
    }

    /** VC "Notation Attributes" (Section 3.3.1): every name in a {@code
     *  NOTATION}-typed attribute's own enumerated value list must itself be
     *  a declared {@code <!NOTATION>} - not just the value actually used on
     *  an instance (that narrower check is {@link #checkEnumerationMembership}'s
     *  own job, at attribute-value-scan time). Deferred to here, alongside
     *  {@link #checkUnparsedEntityNotationsDeclared}, for the same reason: a
     *  notation may legally be declared anywhere in the DTD, including after
     *  the {@code <!ATTLIST>} that names it. */
    private void checkAttlistNotationNamesDeclared() throws SAXException {
        for (Map.Entry<String, LinkedHashMap<String, DTDModel.AttDef>> elementEntry : dtdModel.allAttlists()
                .entrySet()) {
            for (Map.Entry<String, DTDModel.AttDef> attrEntry : elementEntry.getValue().entrySet()) {
                DTDModel.AttDef def = attrEntry.getValue();
                if (!"NOTATION".equals(def.type) || def.enumeration == null) {
                    continue;
                }
                for (String notationName : def.enumeration) {
                    if (!declaredNotations.contains(notationName)) {
                        handler.error("Validity Constraint: Notation Attributes (Section 3.3.1). Attribute \""
                                + attrEntry.getKey() + "\" of element \"" + elementEntry.getKey()
                                + "\" names undeclared notation \"" + notationName + "\".");
                    }
                }
            }
        }
    }

    public SAXException fatalError(String message) throws SAXException {
        return handler.fatalError(message);
    }

    /** Called by {@link ExternalEntityDecoder} once the XML/text
     *  declaration's version has been resolved from the raw bytes - see
     *  {@link #xml11}'s own Javadoc for the timing guarantee this relies on
     *  (called strictly before any real document content, so the mid-flight
     *  state this mutates - the three derived lookup tables - is never
     *  observed in an inconsistent state by a concurrently-in-progress
     *  scan). */
    public void setXml11(boolean xml11) {
        this.xml11 = xml11;
        this.contentStopTable = xml11 ? CONTENT_STOP_XML11 : CONTENT_STOP_XML10;
        this.quotAttrStopTable = xml11 ? QUOT_ATTR_STOP_XML11 : QUOT_ATTR_STOP_XML10;
        this.aposAttrStopTable = xml11 ? APOS_ATTR_STOP_XML11 : APOS_ATTR_STOP_XML10;
        this.legalLiteralTable = xml11 ? LEGAL_LITERAL_XML11 : LEGAL_LITERAL_XML10;
        handler.setXml11(xml11);
    }

    /** True only for an explicit {@code standalone="yes"} XML declaration.
     *  Gates VC "Standalone Document Declaration" (Section 2.9) and WFC
     *  "Entity Declared"'s standalone-specific clause (Section 4.1); see
     *  {@link #declaredExternally} for how a declaration's own
     *  external-vs-internal provenance is tracked to support those checks.
     *  Called by {@link ExternalEntityDecoder} for the document entity only
     *  (a {@code TextDecl} has no {@code SDDecl} production). {@code
     *  standalone} is true only for an explicit {@code standalone="yes"} -
     *  absent or {@code "no"} are indistinguishable to every consumer of
     *  this flag, so there is no three-state distinction to preserve. */
    private boolean standalone;

    public void setStandalone(boolean standalone) {
        this.standalone = standalone;
    }

    /** Read by {@code Parser.getFeature} for the SAX {@code is-standalone}
     *  feature. */
    boolean isStandalone() {
        return standalone;
    }

    // ===== Introspection accessors (after the DOCTYPE has committed -
    // see hasDoctype()) =====

    /** True once a DOCTYPE declaration (or a synthesized external subset -
     *  see {@link #synthesizeExternalSubsetBeforeRoot}) has fully committed.
     *  Read by {@code Parser.getValidationSource()}. */
    boolean hasDoctype() {
        return doctypeSeen;
    }

    String getDoctypeName() {
        return doctypeName;
    }

    /** The DOCTYPE declaration's own declared public identifier, or null. */
    String getDoctypePublicId() {
        return doctypePublicId;
    }

    /** The DOCTYPE declaration's own declared system identifier, or null. */
    String getDoctypeSystemId() {
        return doctypeSystemId;
    }

    DTDModel getDTDModel() {
        return dtdModel;
    }

    /** Internal general entities: name -> raw replacement text (nested
     *  general/predefined references still literal {@code &name;} text -
     *  see the field's own Javadoc). */
    Map<String, String> getGeneralEntities() {
        return generalEntities;
    }

    /** External general entities: name -> {@code {publicId, systemId,
     *  ndataNotationName-or-null}}. */
    Map<String, String[]> getExternalEntityNames() {
        return externalEntityNames;
    }

    /** Internal parameter entities: name -> replacement text. */
    Map<String, String> getParameterEntities() {
        return parameterEntities;
    }

    /** External parameter entities: name -> {@code {publicId, systemId,
     *  declarationBaseSystemId}}. */
    Map<String, String[]> getParameterEntityExternalIds() {
        return parameterEntityExternalIds;
    }

    /** Declared notations: name -> external identifier. */
    Map<String, ExternalID> getNotationExternalIds() {
        return notationExternalIds;
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
            return !isRestrictedCharXml11(c);
        }
        return (c >= 0xE000 && c <= 0xFFFD) || (c >= 0xD800 && c <= 0xDFFF);
    }

    /** XML 1.1 Section 2.2 {@code RestrictedChar}: {@code [#x1-#x8] |
     *  [#xB-#xC] | [#xE-#x1F] | [#x7F-#x84] | [#x86-#x9F]} - legal
     *  {@code Char}-wise (so still accepted by {@link
     *  #isLegalCharRefCodePoint}), but may only appear via character
     *  reference, never as a literal character - except within content
     *  that came from an entity whose own declared value already resolved
     *  one via character reference (see {@link #restrictedCharEntities}). */
    private static boolean isRestrictedCharXml11(char c) {
        return (c >= 0x1 && c <= 0x8) || (c >= 0xB && c <= 0xC) || (c >= 0xE && c <= 0x1F)
                || (c >= 0x7F && c <= 0x84) || (c >= 0x86 && c <= 0x9F);
    }

    /** Set by {@link #scanQuotedLiteralWithCharRefs} - whether the
     *  EntityValue literal it just scanned contained a character reference
     *  that resolved to an XML 1.1 RestrictedChar. Read by {@link
     *  #scanEntityDeclaration} to populate {@link #restrictedCharEntities}. */
    private boolean lastLiteralContainedRestrictedChar;

    /** Names of general entities (XML 1.1 only) whose declared value
     *  contained a character reference resolving to a RestrictedChar (see
     *  {@link #isRestrictedCharXml11}) - mirrors the old pipeline's {@code
     *  EntityDeclaration.containsRestrictedCharFromCharRef}. Such a
     *  character is legal Char-wise but may not appear as a literal,
     *  un-escaped byte in content; however, once it has been legitimately
     *  produced by resolving a character reference in the entity's own
     *  declaration, XML 1.1 test suite rmt-054 establishes that the
     *  resulting entity replacement text remains valid content even though,
     *  by the time {@link #expandGeneralEntityInContent} re-scans it, the
     *  character is indistinguishable from one written raw - so instead
     *  this per-entity flag, set at declaration time, is consulted at
     *  re-scan time via {@link #allowRestrictedCharInContent} to suppress
     *  the rejection for that entity's replacement text specifically. */
    private final HashSet<String> restrictedCharEntities = new HashSet<String>();

    /** Names of general entities (literal or external) whose {@code
     *  <!ENTITY>} declaration itself was parsed while {@link
     *  #parsingExternalContent} was true (the external DTD subset, or an
     *  external parameter entity's own replacement text) - read by {@link
     *  #checkEntityReferenceable} for WFC "Entity Declared" (Section 4.1)'s
     *  standalone-specific clause: a {@code standalone="yes"} document may
     *  not reference an entity declared in external markup. */
    private final HashSet<String> externallyDeclaredGeneralEntities = new HashSet<String>();

    /** True while {@link #scanContent}/{@link #scanContentRunFast} are
     *  scanning a general entity's replacement text whose declared value is
     *  in {@link #restrictedCharEntities} - see that field's Javadoc. Saved
     *  and restored around each {@link #expandGeneralEntityInContent} call,
     *  like {@link #parsingExternalContent}. */
    private boolean allowRestrictedCharInContent;

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

    /** Plain {@link #isLegalLiteralChar} as a table, for the comment/PI/
     *  CDATA terminator scans ({@link #scanCommentData}, {@link
     *  #scanPIData}, {@link #scanCDATAContent}), which fold the legality
     *  check into their own terminator loop - one table read per character
     *  in the same pass - rather than re-walking the accumulated span with
     *  a per-character method call the way the old {@code
     *  checkLiteralCharSpan} second pass did (re-checked from the span's
     *  start after every false-alarm terminator character, quadratic on
     *  hyphen/bracket/question-mark-dense data). */
    private static final boolean[] LEGAL_LITERAL_XML10 = new boolean[0x10000];
    private static final boolean[] LEGAL_LITERAL_XML11 = new boolean[0x10000];

    static {
        for (int i = 0; i < 0x10000; i++) {
            char c = (char) i;
            boolean legal10 = isLegalLiteralCharXml10(c);
            boolean legal11 = isLegalLiteralCharXml11(c);
            LEGAL_LITERAL_XML10[i] = legal10;
            LEGAL_LITERAL_XML11[i] = legal11;
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
        if (!isLegalLiteralChar(c) && !(allowRestrictedCharInContent && xml11 && isRestrictedCharXml11(c))) {
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
            if (allowRestrictedCharInContent && xml11 && isRestrictedCharXml11(c)) {
                contentBracketRun = 0;
                pos++;
                continue;
            }
            throw illegalCharError(c);
        }
    }

    /**
     * XML NameChar: {@code NameStartChar | "-" | "." | [0-9] | #xB7 |
     * [#x0300-#x036F] | [#x203F-#x2040]} - the modern, simplified production
     * from XML 1.0 5th edition / XML 1.1 (identical in both - see {@link
     * #isNameStartChar}), the real Unicode ranges rather than a cruder "any
     * non-ASCII is legal" approximation. {@code CharClass.java} (the old
     * tokenizer-based pipeline's classifier) additionally accepts a much
     * larger legacy table of pre-5th-edition {@code CombiningChar}/{@code
     * Extender} ranges beyond what the current spec text requires; not
     * ported here, since it isn't required.
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
        // Surrogate pairs (U+10000-U+EFFFF): accept both halves individually,
        // matching the char-at-a-time scanning model used throughout this
        // class - except that a high surrogate alone already determines
        // which plane the pair falls in, regardless of the paired low
        // surrogate, so #xDB80-#xDBFF (which can only pair to form
        // #xF0000-#x10FFFF - beyond the legal NameStartChar astral range,
        // which stops at #xEFFFF) must be rejected here, not just accepted
        // and left for a codepoint-level check that this class never does.
        if (c >= 0xD800 && c <= 0xDBFF) {
            return c <= 0xDB7F;
        }
        return c >= 0xDC00 && c <= 0xDFFF;
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
     *  element/attribute names - only once per name, not per character). */
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
            if (inPI) {
                if (!scanPIData()) {
                    return;
                }
                inPI = false;
                continue;
            }
            if (inComment) {
                if (!scanCommentData()) {
                    return;
                }
                inComment = false;
                continue;
            }
            if (inCDATA) {
                if (!scanCDATAContent()) {
                    return;
                }
                inCDATA = false;
                continue;
            }
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
        // An element declared with element-only content (no #PCDATA at
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
            if (runIsWhitespace && standalone && validationEnabled && isCurrentElementDeclaredExternally()) {
                String currentElement = elementStack.get(elementStack.size() - 1);
                handler.error("Validity Constraint: Standalone Document Declaration (Section 2.9). "
                        + "Document has standalone=\"yes\" but external DTD subset declares element \""
                        + currentElement + "\" with element-only content, "
                        + "and white space occurs directly within its content.");
            }
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
            // compaction. Found via chunk-fuzzing differential testing
            // (feeding the same document through in many different chunk
            // sizes and comparing output).
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
                // Errata E15: a reference is itself content, regardless of
                // what it expands to (even nothing) - see
                // checkNotEmptyElementContent's own Javadoc.
                checkNotEmptyElementContent("an entity reference");
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
                // whitespace - deliberately, not an oversight: it did not
                // come from the document's own literal source text, so the
                // "declared element-only content, so whitespace is
                // insignificant" determination doesn't apply the same way.
                // For the same reason, a
                // character/numeric reference's decoded character always
                // counts as real (non-whitespace-only) character data for
                // validation too, regardless of which character it actually
                // decoded to - matching VC "No Character Data"'s own intent
                // (a reference expanding to character data is what's
                // disallowed in element-only content, not specifically
                // non-whitespace data).
                if (validationEnabled) {
                    recordTextForValidation(decoded.toString(), false);
                }
                handler.characters(decoded, false, atMarkup);
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
     *  is element-only ({@link ElementDeclaration.ContentType#ELEMENT} - no
     *  {@code #PCDATA} at all), the only case where whitespace-only text is
     *  reported via {@link XMLHandler#characters} with {@code ignorable=true}
     *  rather than {@code ignorable=false}. False for {@code MIXED}/{@code
     *  ANY}/{@code EMPTY} content types and for an element with no {@code
     *  <!ELEMENT>} declaration at all (including the common no-DTD case). */
    private boolean isCurrentElementContentElementOnly() {
        String currentElement = elementStack.get(elementStack.size() - 1);
        return dtdModel.getContentType(currentElement) == ElementDeclaration.ContentType.ELEMENT;
    }

    /** True if the current (innermost open) element's {@code <!ELEMENT>}
     *  declaration was itself parsed from external markup (see {@link
     *  ElementDeclaration#fromExternalSubset}) - used only for VC
     *  "Standalone Document Declaration" (Section 2.9), alongside {@link
     *  #isCurrentElementContentElementOnly}. */
    private boolean isCurrentElementDeclaredExternally() {
        String currentElement = elementStack.get(elementStack.size() - 1);
        ElementDeclaration decl = dtdModel.getElementDeclaration(currentElement);
        return decl != null && decl.fromExternalSubset;
    }

    /** True if the most recently opened run - tracked via {@link
     *  #contentRunOpen} - was reported via {@link XMLHandler#characters}
     *  with {@code ignorable=true}, so a later empty-closing-frame call (see
     *  {@link #scanContent()}) closes it out with the same flag it was
     *  opened with. */
    private boolean contentRunIsWhitespace;

    private void emitContentRun(CharBuffer text, boolean end, boolean isWhitespace) throws SAXException {
        if (validationEnabled) {
            // A chunked run may call this - and so recordTextForValidation -
            // more than once; harmless beyond a possible duplicate error
            // report for one logical violation, since text.toString() is
            // only ever a small allocation on this already-validating-only
            // path (never on the hot no-validation path).
            recordTextForValidation(text.toString(), isWhitespace);
        }
        handler.characters(text, isWhitespace, end);
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
            if (p < limit && buf[p] == 'x') {
                // Production 66: CharRef ::= '&#' [0-9]+ ';' | '&#x'
                // [0-9a-fA-F]+ ';' - only a lowercase 'x' introduces the hex
                // form; the referenced digits themselves may be either case.
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
            if (p + 1 >= limit) {
                return false;
            }
            if (buf[p + 1] == '-') {
                checkNotEmptyElementContent("a comment");
            }
            return scanBangMarkup(tagStart);
        } else if (c == '?') {
            checkNotEmptyElementContent("a processing instruction");
            return scanPI(tagStart);
        } else {
            return scanStartTag(tagStart);
        }
    }

    /** Errata E15 (XML 1.0 Second Edition, Section 3): an element declared
     *  {@code EMPTY} must have no content whatsoever - not just no
     *  characters or child elements (already caught by {@link
     *  ContentModelValidator#addTextContent}/{@link
     *  ContentModelValidator#addChildElement}), but no comment or
     *  processing instruction either, even though either would otherwise be
     *  legal anywhere in content regardless of the declared content model.
     *  Checked here, in {@link #scanMarkup} - the sole call site reached
     *  only from within {@link #scanContent} (never from DOCTYPE subset
     *  parsing, which calls {@link #scanComment}/{@link #scanPI} directly),
     *  so {@link #elementStack} being non-empty here unambiguously means
     *  "inside element content." */
    private void checkNotEmptyElementContent(String what) throws SAXException {
        if (validationEnabled && !elementStack.isEmpty()
                && dtdModel.getContentType(elementStack.get(elementStack.size() - 1))
                        == ElementDeclaration.ContentType.EMPTY) {
            handler.error("Validity Constraint: Element Valid (Section 3.1). Element \""
                    + elementStack.get(elementStack.size() - 1) + "\" is declared EMPTY but contains " + what + ".");
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
        String qName = namePool.internRange(buf, nameStart, p - nameStart);
        if (!rootStarted) {
            // A document with no DOCTYPE at all may still be given an
            // external subset by an EntityResolver2 - asked for here, at
            // the last point it can still be reported before the root
            // element, once the root's name (getExternalSubset's argument)
            // is known. This point is already committed (no underflow
            // rewind can re-reach it - see below), so the synthesized
            // startDTD/.../endDTD fires exactly once.
            synthesizeExternalSubsetBeforeRoot(qName);
            // The root element's name matching the DOCTYPE's Name is VC
            // "Root Element Type" (XML 1.0 SS3.2), not a WFC - a non-
            // validating processor must not reject a mismatch here, so this
            // is a recoverable error() call, not fatalError().
            rootStarted = true;
            if (validationEnabled && doctypeName != null && !doctypeName.equals(qName)) {
                handler.error("Validity Constraint: Root Element Type (Section 3.2). Document root element \""
                        + qName + "\" does not match DOCTYPE name \"" + doctypeName + "\".");
            }
        }
        if (validationEnabled) {
            pushElementValidator(qName);
        }
        pos = p;
        elementStack.add(qName);
        seenAttributeNameCount = 0;
        handler.startElement(qName);
        inStartTag = true;
        return true;
    }

    /** {@link EntityResolver2#getExternalSubset} integration for a document
     *  with no DOCTYPE declaration at all: called from {@link #scanStartTag}
     *  once the root element's name is known but before its {@link
     *  XMLHandler#startElement} fires. If the resolver supplies a subset, a
     *  full {@link XMLHandler#startDTD}/{@code "[dtd]"} parse/{@link
     *  XMLHandler#endDTD} sequence is synthesized right here, as if the
     *  document had carried an equivalent DOCTYPE - the same reporting
     *  {@link EntityResolver2}'s own contract describes. Skipped whenever a
     *  real DOCTYPE was seen (that path asks via {@link
     *  #requestExternalSubset} instead), when external-parameter-entities
     *  is off, when disallow-doctype-decl would have rejected a real
     *  DOCTYPE, or when the resolver isn't an {@link EntityResolver2}. */
    private void synthesizeExternalSubsetBeforeRoot(String rootQName) throws SAXException {
        if (doctypeSeen || settings.disallowDoctypeDecl || !settings.externalParameterEntities
                || !(entityResolver instanceof EntityResolver2)) {
            return;
        }
        String what = "the external DTD subset for \"" + rootQName + "\"";
        InputSource source;
        try {
            source = resolutionHelper().getExternalSubset(rootQName);
        } catch (IOException e) {
            throw handler.fatalError("Failed to resolve " + what + ": " + e.getMessage());
        }
        if (source == null) {
            return;
        }
        handler.startDTD(rootQName, source.getPublicId(), source.getSystemId());
        handler.startEntity("[dtd]");
        char[] chars;
        try {
            chars = readExternalSource(source, what, source.getSystemId());
            checkVersionCompatibility(chars, what);
        } catch (IOException e) {
            throw handler.fatalError("Failed to fetch " + what + " (" + source.getSystemId() + "): "
                    + e.getMessage());
        }
        String savedBaseSystemId = baseSystemId;
        baseSystemId = lastResolvedSystemId;
        try {
            parseExternalSubset(chars);
        } finally {
            baseSystemId = savedBaseSystemId;
        }
        handler.endEntity("[dtd]");
        resolveAttlistDefaultsAgainstEntities();
        if (validationEnabled) {
            checkAttlistDefaultsLegal();
        }
        handler.endDTD();
        doctypeSeen = true;
        doctypeName = rootQName;
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
        // Fixed for the duration of this start tag (including resumed
        // calls - the element is only popped by this method itself), so
        // resolved once rather than per attribute: the element's name and
        // its declared-attribute map (null in the common no-DTD case).
        String currentElementName = elementStack.get(elementStack.size() - 1);
        Map<String, DTDModel.AttDef> declaredAttrs = dtdModel.getAttributes(currentElementName);
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
            if (pos == attrStart && isNameChar(c)) {
                // STag ::= '<' Name (S Attribute)* S? '>' - S is mandatory
                // before every Attribute, not just the first. This can only
                // be reached from the second attribute onward: on the very
                // first iteration, attrStart is wherever scanStartTag's own
                // greedy name-char scan stopped, which by construction is
                // never itself a name character.
                throw handler.fatalError("White space is required between attributes");
            }
            if (c == '>') {
                pos++;
                applyAttributeDefaults(currentElementName);
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
                applyAttributeDefaults(currentElementName);
                if (validationEnabled) {
                    popAndValidateElement();
                }
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
            String attrName = namePool.internRange(buf, nameStart, pos - nameStart);
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

            DTDModel.AttDef attrDef = declaredAttrs == null ? null : declaredAttrs.get(attrName);
            String attrType = attrDef == null ? "CDATA" : attrDef.type;
            if (validationEnabled && attrDef == null && declaredAttrs != null) {
                handler.error("Validity Constraint: Attribute Value Type (Section 3.3.1). Attribute \"" + attrName
                        + "\" is not declared for element \"" + currentElementName + "\".");
            }
            handler.startAttribute(attrName, attrType, attrDef != null, true);
            pendingQuote = quote;
            attrValueRunOpen = false;
            collapseCurrentAttrValue = !"CDATA".equals(attrType);
            boolean checkXmlSpace = "xml:space".equals(attrName);
            normalizingCurrentAttribute = collapseCurrentAttrValue || validationEnabled || checkXmlSpace;
            if (normalizingCurrentAttribute) {
                normalizeBuilder.setLength(0);
                if (validationEnabled || checkXmlSpace) {
                    currentAttrElementName = currentElementName;
                    currentAttrName = attrName;
                    currentAttrType = attrType;
                }
                if (validationEnabled) {
                    if (standalone && collapseCurrentAttrValue) {
                        if (attrDef != null && attrDef.declaredExternally) {
                            handler.error("Validity Constraint: Standalone Document Declaration (Section 2.9). "
                                    + "Document has standalone=\"yes\" but external markup declares attribute \""
                                    + attrName + "\" of element \"" + currentElementName + "\" with type \""
                                    + attrType + "\", which normalizes this specified value differently.");
                        }
                    }
                }
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
                } else {
                    // Fires at least once even for a value that is empty
                    // from the very start (attrValueRunOpen still false, no
                    // prior chunk ever emitted) - a consumer such as {@link
                    // NamespaceFilter} depends on attributeValueContent
                    // actually being called to notice the attribute at all,
                    // not just on the final accumulated value being correct.
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
            String value = collapseCurrentAttrValue ? collapseWhitespace(normalizeBuilder) : normalizeBuilder.toString();
            normalizingCurrentAttribute = false;
            if (validationEnabled) {
                checkAttributeValueVCs(currentAttrElementName, currentAttrName, currentAttrType, value);
            }
            if ("xml:space".equals(currentAttrName) && !"default".equals(value) && !"preserve".equals(value)) {
                // XML 2.10: xml:space's own value is constrained by the XML
                // Recommendation itself, independent of any DTD declaration
                // (and so checked unconditionally, not gated on
                // validationEnabled the way DTD-driven VCs are).
                handler.error("The \"xml:space\" attribute's value must be \"default\" or \"preserve\", not \""
                        + value + "\".");
            }
            handler.attributeValueContent(CharBuffer.wrap(value), true);
        }
    }

    /** XML 3.3.3's type-dependent attribute value normalisation: trim
     *  leading/trailing space, collapse interior runs of space to a single
     *  space each - deliberately just {@code ' '} (#x20), not {@link
     *  #isWs} generically, per the spec text's own precise wording
     *  ("replacing sequences of space (#x20) characters by a single space
     *  (#x20) character"). A literal tab/CR/LF in the source was already
     *  turned into a space before this ever runs (see {@link
     *  #scanAttributeValueStreaming}), but a character reference
     *  ({@code &#9;} and the like - errata E20) is decoded straight to its
     *  own codepoint and never passes through that substitution, so a tab
     *  reaching this method can only mean one did - and it must survive
     *  uncollapsed, breaking Nmtoken-format validity exactly as a raw,
     *  unescaped tab embedded in an NMTOKEN value should, rather than being
     *  silently swallowed into a space as if it were unremarkable
     *  whitespace. */
    private static String collapseWhitespace(CharSequence s) {
        int len = s.length();
        StringBuilder sb = new StringBuilder(len);
        boolean pendingSpace = false;
        for (int i = 0; i < len; i++) {
            char c = s.charAt(i);
            if (c == ' ') {
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
        // Fast path for the overwhelmingly common well-formed case: the end
        // tag names the innermost open element - whose name is already known
        // and already validated as a legal Name at start-tag time - followed
        // immediately by '>'. One direct comparison against the expected
        // name replaces the general path's two passes over the name (the
        // isNameChar() table walk plus the rangeEquals() WFC check), which
        // profiling on the multibyte corpus showed as a measurable cost
        // (~6%). Any deviation - mismatch, whitespace before '>', buffer
        // underflow, empty stack, or an entity-boundary WFC concern - falls
        // through to the general path below, unchanged, for correct error
        // reporting and resumption; the fast path itself never consumes
        // anything unless it fully succeeds.
        int stackSize = elementStack.size();
        if (stackSize > 0) {
            String expected = elementStack.get(stackSize - 1);
            int afterName = nameStart + expected.length();
            if (afterName < limit && buf[afterName] == '>'
                    && rangeEquals(buf, nameStart, expected.length(), expected)
                    && (entityStackFloors.isEmpty()
                            || stackSize > entityStackFloors.get(entityStackFloors.size() - 1))) {
                elementStack.remove(stackSize - 1);
                if (validationEnabled) {
                    popAndValidateElement();
                }
                rootEnded = elementStack.isEmpty();
                pos = afterName + 1;
                handler.endElement();
                return true;
            }
        }
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
        if (!entityStackFloors.isEmpty()
                && elementStack.size() <= entityStackFloors.get(entityStackFloors.size() - 1)) {
            throw handler.fatalError("End tag </" + new String(buf, nameStart, nameLen)
                    + "> in an entity's replacement text must not close an element that was opened "
                    + "outside that entity (element boundaries must nest within entity boundaries)");
        }
        String expected = elementStack.remove(elementStack.size() - 1);
        if (!rangeEquals(buf, nameStart, nameLen, expected)) {
            throw handler.fatalError("Mismatched end tag: expected </" + expected + "> but found </"
                    + new String(buf, nameStart, nameLen) + ">");
        }
        if (validationEnabled) {
            popAndValidateElement();
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

    /** Coarse resumable mode, mirroring {@link #inPI}: {@link
     *  XMLHandler#startComment} has fired for the comment currently being
     *  scanned and its text is being streamed via {@link
     *  #scanCommentData()}; a caller that can re-enter mid-construct ({@link
     *  #scan()}'s main loop, and {@link #scanDoctypeSubset()} for a comment
     *  nested in a DOCTYPE's internal subset - a comment, like a PI, is part
     *  of {@code markupdecl}) must check this and resume {@link
     *  #scanCommentData()} directly. Set only when {@link
     *  #scanCommentData()} itself returns false; cleared by whichever
     *  caller successfully resumes it. */
    private boolean inComment;

    private boolean scanComment(int tagStart) throws SAXException {
        if (tagStart + 4 > limit) {
            pos = tagStart;
            return false;
        }
        if (buf[tagStart + 3] != '-') {
            throw handler.fatalError("Malformed markup declaration");
        }
        handler.startComment();
        pos = tagStart + 4;
        if (!scanCommentData()) {
            inComment = true;
            return false;
        }
        return true;
    }

    /** Streams a comment's text, {@link #pos} positioned just past its
     *  opening {@code "<!--"}. Resumable like {@link #scanPIData()}: on
     *  underflow, whatever has been confirmed so far is emitted via {@link
     *  XMLHandler#commentData} with {@code end=false} and {@link #pos}
     *  stays exactly where scanning stopped (never rewound). Reports
     *  exactly one or more {@link XMLHandler#commentData} calls, the last
     *  of which has {@code end=true} - see {@link XMLHandler#startComment}'s
     *  Javadoc for why, even for an empty comment ({@code <!---->}).
     */
    private boolean scanCommentData() throws SAXException {
        // p is a local lookahead cursor, distinct from pos - see
        // scanPIData's identical pattern and Javadoc for why a lone '-'
        // that turns out not to start "-->" must not reset the pending
        // span's start.
        int p = pos;
        // Legal-Char rejection is folded into the terminator scan itself -
        // one legalLiteralTable read per character in the same pass, instead
        // of a separate re-walk of the pending span (which would re-check
        // from the span's start after every literal '-' that turns out not
        // to begin "-->").
        boolean[] legal = legalLiteralTable;
        while (true) {
            while (p < limit && buf[p] != '-') {
                if (!legal[buf[p]]) {
                    throw illegalCharError(buf[p]);
                }
                p++;
            }
            if (p >= limit || p + 2 >= limit) {
                if (p > pos) {
                    handler.commentData(CharBuffer.wrap(buf, pos, p - pos), false);
                    pos = p;
                }
                return false;
            }
            if (buf[p + 1] == '-') {
                if (buf[p + 2] == '>') {
                    handler.commentData(CharBuffer.wrap(buf, pos, p - pos), true);
                    pos = p + 3;
                    return true;
                }
                throw handler.fatalError("'--' is not allowed inside a comment");
            }
            p++;
        }
    }

    private static final char[] CDATA_MARKER = "<![CDATA[".toCharArray();

    /** Coarse resumable mode, mirroring {@link #inPI}: {@link
     *  XMLHandler#startCDATA} has fired for the CDATA section currently
     *  being scanned and its content is being streamed via {@link
     *  #scanCDATAContent()}; {@link #scan()}'s main loop checks this and
     *  resumes {@link #scanCDATAContent()} directly rather than
     *  re-dispatching from {@link #pos}. Unlike {@link #inPI}, never needs
     *  checking inside {@link #scanDoctypeSubset()} too - a CDATA section can
     *  only appear within element content, never inside a DOCTYPE's internal
     *  subset. Set only when {@link #scanCDATAContent()} itself returns
     *  false; cleared by whichever caller successfully resumes it. */
    private boolean inCDATA;

    /** True if the CDATA section currently being scanned has already had a
     *  {@link XMLHandler#characters} chunk reported with {@code end=false} -
     *  mirrors {@link #contentRunOpen}/{@link #attrValueRunOpen}, needed so
     *  a final, otherwise-empty call to {@link #scanCDATAContent()} (the
     *  closing {@code "]]>"} found with no new characters since the last
     *  chunk) still emits the matching empty {@code end=true} closing frame
     *  rather than silently dropping it. */
    private boolean cdataRunOpen;

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
        handler.startCDATA();
        pos = tagStart + CDATA_MARKER.length;
        cdataRunOpen = false;
        if (!scanCDATAContent()) {
            inCDATA = true;
            return false;
        }
        return true;
    }

    /** Streams a CDATA section's content, {@link #pos} positioned just past
     *  its opening {@code "<![CDATA["}. Resumable like {@link
     *  #scanAttributeValueStreaming()}: on underflow, whatever has been
     *  confirmed so far is emitted via {@link XMLHandler#characters} (never
     *  {@code ignorable} - see {@link #checkContentChar}'s own reasoning for
     *  why entity-produced/non-literal-source-text content stays out of
     *  {@code ignorableWhitespace} treatment, which applies here for the
     *  same underlying reason: this class doesn't track whether the
     *  enclosing element is element-only content for CDATA the way {@link
     *  #scanContent} does for ordinary text) with {@code end=false}, and
     *  {@link #pos} stays exactly where scanning stopped (never rewound), so
     *  a later resumed call picks up right there. */
    private boolean scanCDATAContent() throws SAXException {
        // p is a local lookahead cursor, distinct from pos - see
        // scanPIData's identical pattern and Javadoc for why a ']' (or
        // "]]") that turns out not to start "]]>" must not reset the
        // pending span's start.
        int p = pos;
        // Legality folded into the terminator scan - see scanCommentData.
        boolean[] legal = legalLiteralTable;
        while (true) {
            while (p < limit && buf[p] != ']') {
                if (!legal[buf[p]]) {
                    throw illegalCharError(buf[p]);
                }
                p++;
            }
            if (p >= limit || p + 2 >= limit) {
                if (p > pos) {
                    emitCDATAChunk(pos, p, false);
                    cdataRunOpen = true;
                    pos = p;
                }
                return false;
            }
            if (buf[p + 1] == ']' && buf[p + 2] == '>') {
                if (p > pos || cdataRunOpen) {
                    emitCDATAChunk(pos, p, true);
                }
                cdataRunOpen = false;
                pos = p + 3;
                handler.endCDATA();
                return true;
            }
            // buf[p] == ']' but not followed by ']>' - just data, keep
            // scanning past it (matching scanContentRunFast's own bulk-skip
            // discipline for the analogous "]"-in-content case).
            p++;
        }
    }

    private void emitCDATAChunk(int start, int end, boolean isEnd) throws SAXException {
        if (end > start && validationEnabled) {
            boolean allWs = true;
            for (int i = start; i < end && allWs; i++) {
                allWs = isWs(buf[i]);
            }
            recordTextForValidation(new String(buf, start, end - start), allWs);
        }
        handler.characters(CharBuffer.wrap(buf, start, end - start), false, isEnd);
    }

    // ===== Processing instruction =====

    /** Coarse resumable mode, mirroring {@link #inAttributeValue}: {@link
     *  XMLHandler#piTarget} has fired for the PI currently being scanned and
     *  its data is being streamed via {@link #scanPIData()}; a caller that
     *  can re-enter mid-construct (this class's own {@link #scan()} main
     *  loop, and {@link #scanDoctypeSubset()} for a PI nested in a DOCTYPE's
     *  internal subset) must check this and resume {@link #scanPIData()}
     *  directly rather than re-dispatching from {@link #pos} as if it were
     *  the start of a fresh construct. Set only when {@link #scanPIData()}
     *  itself returns false (needs more data) - never touched by {@link
     *  #scanPIData()}; cleared by whichever caller successfully resumes it. */
    private boolean inPI;

    /**
     * Parses a {@code <?target ...?>} processing instruction's target,
     * {@code tagStart} positioned at the '&lt;'. The target name is atomic
     * (on underflow, {@link #pos} rewinds to {@code tagStart} and nothing is
     * reported yet) - but once {@link XMLHandler#piTarget} has fired, this
     * is committed: {@link #scanPIData()} handles its own resumption via
     * {@link #inPI} instead of rewinding, since data chunks may already have
     * been streamed out. Returns true once the PI's closing {@code "?>"} has
     * been consumed, false if more data is needed (with {@link #inPI} left
     * true in that case, once the target itself is past).
     */
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
        String target = namePool.internRange(buf, targetStart, p - targetStart);
        if (target.length() == 3
                && (target.charAt(0) == 'x' || target.charAt(0) == 'X')
                && (target.charAt(1) == 'm' || target.charAt(1) == 'M')
                && (target.charAt(2) == 'l' || target.charAt(2) == 'L')) {
            throw handler.fatalError("Processing instruction target matching [Xx][Mm][Ll] is reserved");
        }
        if (namespaceAware && target.indexOf(':') >= 0) {
            // Namespaces in XML 1.0 Section 6: a PITarget is not itself a
            // QName, but a colon is still forbidden in it under namespace
            // processing.
            throw handler.fatalError("Processing instruction target \"" + target
                    + "\" must not contain a colon (Namespaces in XML, Section 6)");
        }
        if (p >= limit) {
            pos = tagStart;
            return false;
        }
        if (isWs(buf[p])) {
            p++;
        } else if (buf[p] != '?') {
            // Production 16: PI ::= '<?' PITarget (S (Char* - (Char* '?>'
            // Char*)))? '?>' - S is required before any data; "?" here means
            // there is no data at all (straight to the closing "?>"), the
            // production's optional second group not present.
            throw handler.fatalError(
                    "White space is required between a processing instruction's target and its data");
        }
        handler.piTarget(target);
        pos = p;
        if (!scanPIData()) {
            inPI = true;
            return false;
        }
        return true;
    }

    /**
     * Streams a processing instruction's data, {@link #pos} positioned just
     * past its target (and separating whitespace, if any). Resumable like
     * {@link #scanAttributeValueStreaming()}: on underflow, whatever has
     * been confirmed so far is emitted via {@link XMLHandler#piData} with
     * {@code end=false} and {@link #pos} stays exactly where scanning
     * stopped (never rewound), so a later resumed call picks up right there.
     * Reports exactly one or more {@link XMLHandler#piData} calls, the last
     * of which has {@code end=true} - unlike {@link
     * #scanAttributeValueStreaming()}'s attributeValueContent (which may
     * fire zero times for an empty value), a data-less PI ({@code
     * <?target?>}) still gets exactly one {@code piData} call with an empty
     * buffer, since {@code piData}'s {@code end=true} is this construct's
     * only completion signal - there is no separate "end of PI" event.
     */
    private boolean scanPIData() throws SAXException {
        // p is a local lookahead cursor, distinct from pos: a '?' not
        // actually followed by '>' (just a literal '?' in the data, legal
        // and common) is not a chunk boundary, so it must not reset the
        // start of the pending span the way scanContentRunFast's per-
        // character bulk-skip legitimately does for its own, unrelated
        // reasons - only p advances past it; pos (and so the span about to
        // be emitted) stays put until data is actually confirmed and
        // handed to piData. Found via testDifferential_markup - a real
        // literal '?' inside PI data upstream of the true "?>" silently
        // dropped everything from the previous chunk boundary up to and
        // including that '?' when pos was (wrongly) advanced past it here
        // without first emitting.
        int p = pos;
        // Legality folded into the terminator scan - see scanCommentData.
        boolean[] legal = legalLiteralTable;
        while (true) {
            while (p < limit && buf[p] != '?') {
                if (!legal[buf[p]]) {
                    throw illegalCharError(buf[p]);
                }
                p++;
            }
            if (p >= limit || p + 1 >= limit) {
                if (p > pos) {
                    handler.piData(CharBuffer.wrap(buf, pos, p - pos), false);
                    pos = p;
                }
                return false;
            }
            if (buf[p + 1] == '>') {
                handler.piData(CharBuffer.wrap(buf, pos, p - pos), true);
                pos = p + 2;
                return true;
            }
            p++;
        }
    }

    // ===== DOCTYPE / entities (cold path) =====
    //
    // Scope, deliberately narrow (see class Javadoc): the internal subset's
    // <!ENTITY Name "value"> declarations (general, internal, quoted-literal
    // only) are fully parsed; <!ELEMENT>/<!ATTLIST> declarations are parsed
    // into DTDModel (content model, attribute types/defaults/VCs), and
    // <!NOTATION> declarations are recognised and skipped syntactically, so
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
    private static final char[] NOTATION_MARKER = "<!NOTATION".toCharArray();

    /** Element content types/models and attribute defaults/types/VCs
     *  declared in the internal DTD subset. */
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

    /** XML 4.2.2's {@code PubidChar}: {@code #x20 | #xD | #xA | [a-zA-Z0-9] |
     *  [-'()+,./:=?;!*#@$_%]} - deliberately far more restrictive than a
     *  general literal's {@link #isLegalLiteralChar} (no {@code '<'},
     *  {@code '>'}, {@code '['}, {@code '&'}, backslash, tab, or most other
     *  punctuation). */
    private static boolean isPubidChar(char c) {
        if (c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9') {
            return true;
        }
        switch (c) {
            case ' ':
            case '\r':
            case '\n':
            case '-':
            case '\'':
            case '(':
            case ')':
            case '+':
            case ',':
            case '.':
            case '/':
            case ':':
            case '=':
            case '?':
            case ';':
            case '!':
            case '*':
            case '#':
            case '@':
            case '$':
            case '_':
            case '%':
                return true;
            default:
                return false;
        }
    }

    /** Validates an already-extracted public identifier literal against
     *  {@link #isPubidChar} - shared by {@link #skipExternalId} and {@link
     *  #scanNotationDeclaration}, the two places a {@code PubidLiteral} is
     *  captured. */
    private void checkPubidLiteral(String publicId) throws SAXException {
        for (int i = 0; i < publicId.length(); i++) {
            if (!isPubidChar(publicId.charAt(i))) {
                throw handler.fatalError(
                        "Character U+" + String.format("%04X", (int) publicId.charAt(i))
                                + " is not allowed in a public identifier");
            }
        }
    }

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
            checkPubidLiteral(lastExternalIdPublicId);
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
        checkSystemLiteralNoFragment(lastExternalIdSystemId);
        return sysEnd;
    }

    /** XML 4.2.2/2.3: a system identifier is a URI reference and "may not
     *  contain a fragment identifier" - unlike a {@code PubidChar}
     *  restriction (see {@link #checkPubidLiteral}), this isn't part of the
     *  {@code SystemLiteral} grammar itself (any character but the quote is
     *  syntactically legal), so a violation is reported as a recoverable
     *  error rather than rejected as malformed syntax. */
    private void checkSystemLiteralNoFragment(String systemId) throws SAXException {
        if (systemId.indexOf('#') >= 0) {
            handler.error("A system identifier may not contain a URI fragment: \"" + systemId + "\"");
        }
    }

    /** {@code <!NOTATION>} declarations seen so far, by name - only ever
     *  used for this "first declaration wins" dedup check ({@link
     *  #handler}'s {@link XMLHandler#notationDecl} is the actual record of
     *  what was declared; nothing else in this class looks a notation name
     *  back up). Notations are reported immediately as encountered, unlike
     *  entity declarations (see {@link #scanEntityDeclaration}), since
     *  reporting one is a self-contained, irreversible side effect with
     *  nothing further to resolve against the rest of the DTD - the same
     *  "commit once this declaration alone is confirmed complete" discipline
     *  {@link #scanComment}/{@link #scanPI} already use mid-subset. */
    private final HashSet<String> declaredNotations = new HashSet<String>();

    /** External identifiers of the winning ("first declaration wins")
     *  {@code <!NOTATION>} declaration for each name in {@link
     *  #declaredNotations} - kept for post-DOCTYPE introspection; nothing
     *  on the scanning path itself ever looks a notation's identifiers
     *  back up. */
    private final HashMap<String, ExternalID> notationExternalIds = new HashMap<String, ExternalID>();

    /**
     * Parses one {@code <!NOTATION ...>} declaration ({@code Name S
     * (ExternalID | PublicID)}), {@code p} positioned just past the
     * "<!NOTATION" keyword. Called only after the caller has already ruled
     * out {@code <!ENTITY}/{@code <!ATTLIST}/{@code <!ELEMENT}/a comment, so
     * this is the only remaining {@code markupdecl} production. Unlike
     * {@link #skipExternalId} (shared by {@code <!ENTITY>}/the DOCTYPE's own
     * external subset, both of which always require a system identifier),
     * a notation's external identifier may be a bare {@code PublicID} - a
     * public identifier with no system identifier at all - so this parses
     * its own {@code (ExternalID | PublicID)} rather than reusing that
     * method. Reports {@link XMLHandler#notationDecl} immediately once
     * confirmed (first declaration wins for a repeated name, tracked via
     * {@link #declaredNotations}). Returns the position past the
     * declaration's closing '>', or -1 on underflow.
     */
    private int scanNotationDeclaration(int p) throws SAXException {
        int ws = p;
        p = skipOptionalWhitespace(p);
        if (p >= limit) {
            return -1;
        }
        if (p == ws) {
            throw handler.fatalError("Malformed notation declaration");
        }

        int nameStart = p;
        while (p < limit && isNameChar(buf[p])) {
            p++;
        }
        if (p >= limit) {
            return -1;
        }
        if (p == nameStart) {
            throw handler.fatalError("Malformed notation declaration");
        }
        checkNameStartChar(nameStart);
        String name = new String(buf, nameStart, p - nameStart);
        checkNoColonInNamespaceMode(name, "Notation");

        int ws2 = p;
        p = skipOptionalWhitespace(p);
        if (p >= limit) {
            return -1;
        }
        if (p == ws2) {
            throw handler.fatalError("Malformed notation declaration");
        }

        boolean isPublic;
        int sm = matchKeyword(p, SYSTEM_MARKER);
        if (sm == KW_NEED_MORE) {
            return -1;
        }
        if (sm == KW_MATCH) {
            isPublic = false;
            p += SYSTEM_MARKER.length;
        } else {
            int pm = matchKeyword(p, PUBLIC_MARKER);
            if (pm == KW_NEED_MORE) {
                return -1;
            }
            if (pm != KW_MATCH) {
                throw handler.fatalError("Malformed notation declaration");
            }
            isPublic = true;
            p += PUBLIC_MARKER.length;
        }
        int ws3 = p;
        p = skipOptionalWhitespace(p);
        if (p >= limit) {
            return -1;
        }
        if (p == ws3) {
            throw handler.fatalError("Malformed notation declaration");
        }
        int r = findQuotedLiteralEnd(p);
        if (r < 0) {
            return -1;
        }
        String publicId = isPublic ? new String(buf, p + 1, r - 1 - (p + 1)) : null;
        String systemId = isPublic ? null : new String(buf, p + 1, r - 1 - (p + 1));
        if (isPublic) {
            checkPubidLiteral(publicId);
        }
        p = r;

        if (isPublic) {
            // PublicID (Name S 'PUBLIC' S PubidLiteral) has no trailing
            // SystemLiteral; the fuller ExternalID form ('PUBLIC' S
            // PubidLiteral S SystemLiteral) does - ambiguous until the next
            // non-whitespace character is actually seen.
            int afterWs = skipOptionalWhitespace(p);
            if (afterWs >= limit) {
                return -1;
            }
            if (buf[afterWs] == '"' || buf[afterWs] == '\'') {
                int r2 = findQuotedLiteralEnd(afterWs);
                if (r2 < 0) {
                    return -1;
                }
                systemId = new String(buf, afterWs + 1, r2 - 1 - (afterWs + 1));
                p = r2;
            }
        }

        p = skipOptionalWhitespace(p);
        if (p >= limit) {
            return -1;
        }
        if (buf[p] != '>') {
            throw handler.fatalError("Malformed notation declaration");
        }
        p++;

        if (systemId != null) {
            checkSystemLiteralNoFragment(systemId);
        }
        if (declaredNotations.add(name)) {
            notationExternalIds.put(name, new ExternalID(publicId, systemId));
            handler.notationDecl(name, publicId, systemId);
        }
        return p;
    }

    /** Decodes one character reference {@code &#...;}/{@code &#x...;}
     *  starting at {@code buf[q]} into {@code sb}, for use while scanning an
     *  EntityValue at declaration time (always a fully-buffered, one-shot
     *  parse of the current construct - see {@link #scanEntityDeclaration}).
     *  Returns the position past the trailing ';', or -1 if underflow. */
    private int decodeCharRefInto(StringBuilder sb, int q) throws SAXException {
        int p = q + 2; // skip "&#"
        boolean hex = false;
        if (p < limit && buf[p] == 'x') {
            // See decodeEntityRef's identical check: only lowercase 'x'.
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
        lastCharRefCodePoint = codePoint;
        sb.appendCodePoint(codePoint);
        return p + 1;
    }

    /** Set by {@link #decodeCharRefInto} - the codepoint it just resolved.
     *  Read by {@link #scanQuotedLiteralWithCharRefs} to track whether an
     *  EntityValue's character references produced an XML 1.1 RestrictedChar
     *  (see {@link #restrictedCharEntities}). */
    private int lastCharRefCodePoint;

    /**
     * Legal code point for a numeric character reference ({@code &#NN;}/
     * {@code &#xNN;}), matching the old parser's {@code
     * Tokenizer.isLegalXMLChar(int)} exactly. Deliberately more permissive
     * than literal-character legality (which this scanner does not check):
     * XML 1.1 allows referencing C0/C1 control characters via character
     * reference even though they may not appear literally.
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
     * and {@link #scanAttlistDeclaration}. A general entity reference
     * ({@code &name;}) is validated (the name itself must be legal) but not
     * expanded here - it is appended to {@code sb} as its own literal
     * {@code &name;} text, to be resolved later, once the whole entity table
     * is known ({@link #resolveAttlistDefaultValue}) or when the containing
     * entity's own replacement text is eventually scanned as content. {@code
     * isEntityValue} selects between the two productions' differing
     * treatment of a literal {@code '%'}: {@code EntityValue}'s grammar
     * (XML 4.1 [9]) excludes a bare {@code '%'} entirely (it must begin a
     * parameter entity reference, {@code %name;} - immediately expanded in
     * place per XML 4.4.5 "Included in Literal", via {@link
     * #resolveParameterEntityReferenceAt}, so a value built up out of
     * further parameter entities - {@code <!ENTITY % e2 "%e1;%e1;">} and
     * the like, nested arbitrarily - resolves to one flat string exactly
     * as if it had been typed that way); {@code AttValue}'s grammar (XML
     * 3.1 [10]) has no such
     * restriction - a literal {@code '%'} (for example {@code "100%"}) is
     * ordinary character data. {@code pendingParamEntities}/{@code
     * pendingParamExternalNames} - see {@link #scanAttlistDeclaration}'s
     * identical parameters; only ever consulted when {@code isEntityValue}.
     * Returns the position past the closing quote, or -1 on underflow.
     */
    private int scanQuotedLiteralWithCharRefs(int p, StringBuilder sb, boolean isEntityValue,
            HashMap<String, String> pendingParamEntities, HashMap<String, String[]> pendingParamExternalNames)
            throws SAXException {
        char quote = buf[p];
        int q = p + 1;
        lastLiteralContainedRestrictedChar = false;
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
                if (isEntityValue && xml11 && lastCharRefCodePoint <= 0xFFFF
                        && isRestrictedCharXml11((char) lastCharRefCodePoint)) {
                    lastLiteralContainedRestrictedChar = true;
                }
                q = r;
                continue;
            }
            if (c == '&' && q + 1 >= limit) {
                // Could be the start of "&#..." straddling the buffer
                // boundary - can't yet tell, must wait for more data.
                return -1;
            }
            if (c == '&') {
                int r = scanReferenceNameLiteral(q, sb, '&');
                if (r < 0) {
                    return -1;
                }
                q = r;
                continue;
            }
            if (isEntityValue && c == '%') {
                if (q + 1 >= limit) {
                    return -1;
                }
                // WFC "PEs in Internal Subset" (checked inside
                // resolveParameterEntityReferenceAt) applies here exactly
                // as it does to a reference at a declaration's own token
                // boundaries (see splicePEReferenceAt) - an EntityValue is
                // itself the value of an <!ENTITY> declaration, so a %pe;
                // inside one, in the internal subset, is still "within a
                // markup declaration" there. Unlike splicePEReferenceAt's
                // "Included as PE" (XML 4.4.8), this is "Included in
                // Literal" (XML 4.4.5): the resolved text - already itself
                // fully resolved, with no PE reference of its own left
                // un-expanded, per resolveParameterEntityReplacement's own
                // contract - is appended directly to sb, with no padding
                // and never touching buf at all, so a quote character
                // within it can never be mistaken for this literal's own
                // closing quote the way splicing it into buf could.
                char[] replacementChars = resolveParameterEntityReferenceAt(q, pendingParamEntities,
                        pendingParamExternalNames);
                if (replacementChars == null) {
                    return -1;
                }
                sb.append(replacementChars);
                q = lastPEReferenceEnd;
                continue;
            }
            if (!isLegalLiteralChar(c)) {
                throw illegalCharError(c);
            }
            sb.append(c);
            q++;
        }
    }

    /** Validates a {@code Name} immediately follows {@code buf[q]} (the
     *  {@code marker} character - {@code '&'} for a {@code Reference}'s
     *  {@code EntityRef}, {@code '%'} for a {@code PEReference}), terminated
     *  by {@code ';'}, and appends the whole {@code marker + Name + ';'} span
     *  to {@code sb} literally (unexpanded - see {@link
     *  #scanQuotedLiteralWithCharRefs}). Returns the position past the
     *  trailing {@code ';'}, or -1 on underflow. */
    private int scanReferenceNameLiteral(int q, StringBuilder sb, char marker) throws SAXException {
        int nameStart = q + 1;
        int r = nameStart;
        while (r < limit && isNameChar(buf[r])) {
            r++;
        }
        if (r >= limit) {
            return -1;
        }
        if (r == nameStart || buf[r] != ';') {
            throw handler.fatalError(
                    "A literal '" + marker + "' must begin a " + (marker == '%' ? "parameter entity" : "entity")
                            + " reference (" + marker + "Name;)");
        }
        checkNameStartChar(nameStart);
        sb.append(buf, q, r + 1 - q);
        return r + 1;
    }

    /** Fires {@link XMLHandler#unparsedEntityDecl} for every entry in {@code
     *  externalNames} that was declared with an NDATA annotation (a non-null
     *  third array element - see {@link #scanEntityDeclaration}) - called at
     *  each of this class's two "the whole DTD's entities are now finally
     *  known" merge points ({@link #scanDoctypeSubset}'s own completion, and
     *  {@link #parseExternalSubset}'s), right where each entry is confirmed
     *  to be the winning ("first declaration wins") declaration for its
     *  name, unlike {@link XMLHandler#notationDecl}-reporting {@link
     *  #scanNotationDeclaration}, which reports immediately as encountered
     *  since it has no analogous merge step to wait for. */
    private void reportUnparsedEntities(Map<String, String[]> externalNames) throws SAXException {
        for (Map.Entry<String, String[]> entry : externalNames.entrySet()) {
            String[] ids = entry.getValue();
            if (ids[2] != null) {
                handler.unparsedEntityDecl(entry.getKey(), ids[0], ids[1], ids[2]);
            }
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
        sawSpliceSinceDeclarationStart = false;
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
        checkNoColonInNamespaceMode(name, "Entity");

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
            int q = scanQuotedLiteralWithCharRefs(p, sb, true, pendingParamEntities, pendingParamExternalNames);
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
            checkNotFromPESplice(p, "an <!ENTITY> declaration");
            p++;

            if (isParam) {
                if (!pendingParamEntities.containsKey(name) && !pendingParamExternalNames.containsKey(name)
                        && !parameterEntities.containsKey(name) && !parameterEntityExternalIds.containsKey(name)) {
                    String value = sb.toString();
                    pendingParamEntities.put(name, value);
                    handler.internalEntityDecl("%" + name, value);
                }
            } else if (!pendingEntities.containsKey(name) && !pendingExternalNames.containsKey(name)
                    && !generalEntities.containsKey(name) && !externalEntityNames.containsKey(name)) {
                String value = sb.toString();
                pendingEntities.put(name, value);
                handler.internalEntityDecl(name, value);
                if (lastLiteralContainedRestrictedChar) {
                    restrictedCharEntities.add(name);
                }
                if (parsingExternalContent) {
                    externallyDeclaredGeneralEntities.add(name);
                }
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

        int wsBeforeNdata = p;
        p = skipOptionalWhitespace(p);
        if (p >= limit) {
            return -1;
        }
        String ndataName = null;
        if (buf[p] != '>') {
            int m = matchKeyword(p, NDATA_MARKER);
            if (m == KW_NEED_MORE) {
                return -1;
            }
            if (m != KW_MATCH) {
                throw handler.fatalError("Malformed entity declaration");
            }
            if (p == wsBeforeNdata) {
                // NDataDecl ::= S 'NDATA' S Name - the leading S is
                // mandatory, not optional; skipOptionalWhitespace above
                // would have accepted "SYSTEM \"x\"NDATA foo" (zero
                // whitespace) just as happily as one with it.
                throw handler.fatalError("White space is required before \"NDATA\"");
            }
            if (isParam) {
                // PEDef ::= EntityValue | ExternalID (production 74) - NDATA
                // is not part of a parameter entity's grammar at all, unlike
                // a general entity's EntityDef, which explicitly allows it.
                throw handler.fatalError("Parameter entities may not have an NDATA annotation");
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
            ndataName = new String(buf, ndataNameStart, p - ndataNameStart);
            p = skipOptionalWhitespace(p);
            if (p >= limit) {
                return -1;
            }
            if (buf[p] != '>') {
                throw handler.fatalError("Malformed entity declaration");
            }
        }
        checkNotFromPESplice(p, "an <!ENTITY> declaration");
        p++;

        if (isParam) {
            if (!pendingParamEntities.containsKey(name) && !pendingParamExternalNames.containsKey(name)
                    && !parameterEntities.containsKey(name) && !parameterEntityExternalIds.containsKey(name)) {
                // The third element is baseSystemId's own value right now -
                // this declaration's own location - captured because this
                // entity may not actually be expanded until much later, from
                // a completely different lexical context (see baseSystemId's
                // own Javadoc); resolveParameterEntityReplacement resolves
                // extSystemId against this captured value, never whatever
                // baseSystemId happens to be at expansion time.
                pendingParamExternalNames.put(name, new String[] { extPublicId, extSystemId, baseSystemId });
                handler.externalEntityDecl("%" + name, extPublicId, extSystemId);
            }
        } else if (!pendingEntities.containsKey(name) && !pendingExternalNames.containsKey(name)
                && !generalEntities.containsKey(name) && !externalEntityNames.containsKey(name)) {
            // The optional third element is the NDATA-declared notation name
            // (null for an ordinary, parsed external entity) - see {@link
            // #unparsedEntityDecl} firing at this map's eventual merge point.
            pendingExternalNames.put(name, new String[] { extPublicId, extSystemId, ndataName });
            if (ndataName == null) {
                handler.externalEntityDecl(name, extPublicId, extSystemId);
            }
            if (parsingExternalContent) {
                externallyDeclaredGeneralEntities.add(name);
            }
        }
        return p;
    }

    /** Set by {@link #scanEnumerationList} - the parsed values, in
     *  declaration order. Only assigned once that method has fully
     *  succeeded (never on a retried-after-underflow partial attempt),
     *  matching every other "commit only at the very end" construct in this
     *  class. */
    private List<String> lastEnumerationValues;

    /**
     * Parses a flat (non-nested) parenthesised Nmtoken/Name list - a
     * {@code NOTATION} type's Name list, or a bare enumerated attribute
     * type's Nmtoken list - starting at {@code buf[p]} (the '('), capturing
     * the actual values into {@link #lastEnumerationValues} for VC
     * "Enumeration"/"Notation Attributes" membership checking. Neither list
     * ever nests further parens, unlike an element content model, so this is
     * a simpler scan than {@link #scanElementDeclaration}'s. {@code
     * requireNameStartChar} selects between the two productions' differing
     * first-character rule - true for a NOTATION list ({@code Name}, so a
     * leading digit/hyphen/etc. is illegal), false for a bare enumeration
     * ({@code Nmtoken}, which has no such restriction). Returns the position
     * past the closing ')', or -1 on underflow.
     */
    private int scanEnumerationList(int p, boolean requireNameStartChar) throws SAXException {
        ArrayList<String> values = new ArrayList<String>();
        int q = p + 1;
        while (true) {
            q = skipOptionalWhitespace(q);
            if (q >= limit) {
                return -1;
            }
            int tokenStart = q;
            while (q < limit && isNameChar(buf[q])) {
                q++;
            }
            if (q >= limit) {
                return -1;
            }
            if (q == tokenStart) {
                throw handler.fatalError("Malformed enumeration");
            }
            if (requireNameStartChar) {
                checkNameStartChar(tokenStart);
            }
            String token = new String(buf, tokenStart, q - tokenStart);
            if (validationEnabled && values.contains(token)) {
                handler.error("Validity Constraint: No Duplicate Types (Section 3.3.1). \"" + token
                        + "\" appears more than once in this attribute's enumerated type.");
            }
            values.add(token);
            q = skipOptionalWhitespace(q);
            if (q >= limit) {
                return -1;
            }
            if (buf[q] == '|') {
                q++;
                continue;
            }
            if (buf[q] == ')') {
                q++;
                break;
            }
            throw handler.fatalError("Malformed enumeration");
        }
        lastEnumerationValues = values;
        return q;
    }

    /**
     * Parses one {@code <!ELEMENT ...>} declaration, {@code p} positioned
     * just past the "<!ELEMENT" keyword. The content model's parenthesised
     * structure (if any) is scanned in full, paren-depth-aware, to find
     * where the declaration ends - {@code (a, (b|c)+, d*)} nests
     * arbitrarily, unlike an ATTLIST's flat enumeration/NOTATION lists -
     * and, only when {@link #validationEnabled} (VC checking has a real
     * cost only worth paying when asked for), the same already-fully-
     * buffered span (this depth-matching scan having already confirmed it
     * never underflows) is re-parsed into a real {@link
     * ElementDeclaration.ContentModel} tree by {@link #parseContentModelGroup}.
     * Returns the position past the closing '>', or -1 on underflow. {@code
     * pendingParamEntities}/{@code pendingParamExternalNames} - see {@link
     * #scanAttlistDeclaration}'s identical parameters.
     */
    private int scanElementDeclaration(int p, HashMap<String, String> pendingParamEntities,
            HashMap<String, String[]> pendingParamExternalNames) throws SAXException {
        sawSpliceSinceDeclarationStart = false;
        int ws = p;
        p = skipWhitespaceInDeclaration(p, pendingParamEntities, pendingParamExternalNames);
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
        p = skipWhitespaceInDeclaration(p, pendingParamEntities, pendingParamExternalNames, validationEnabled);
        if (p >= limit) {
            return -1;
        }
        if (p == ws2) {
            throw handler.fatalError("Malformed element declaration");
        }

        ElementDeclaration.ContentType type;
        ElementDeclaration.ContentModel model = null;
        int em = matchKeyword(p, EMPTY_MARKER);
        if (em == KW_NEED_MORE) {
            return -1;
        }
        if (em == KW_MATCH) {
            type = ElementDeclaration.ContentType.EMPTY;
            p += EMPTY_MARKER.length;
        } else {
            int am = matchKeyword(p, ANY_MARKER);
            if (am == KW_NEED_MORE) {
                return -1;
            }
            if (am == KW_MATCH) {
                type = ElementDeclaration.ContentType.ANY;
                p += ANY_MARKER.length;
            } else {
                if (p >= limit) {
                    return -1;
                }
                if (buf[p] != '(') {
                    throw handler.fatalError("Malformed element declaration");
                }
                int modelStart = p;
                int afterParen = skipOptionalWhitespace(p + 1);
                if (afterParen >= limit) {
                    return -1;
                }
                int pm = matchKeyword(afterParen, PCDATA_MARKER);
                if (pm == KW_NEED_MORE) {
                    return -1;
                }
                type = (pm == KW_MATCH) ? ElementDeclaration.ContentType.MIXED
                        : ElementDeclaration.ContentType.ELEMENT;

                int depth = 0;
                while (true) {
                    if (p >= limit) {
                        return -1;
                    }
                    char c = buf[p];
                    if (c == '%') {
                        // Splice (or, in the internal subset, reject) right
                        // here rather than after this depth-matching scan
                        // completes: a parameter entity's own replacement
                        // text may itself contain parens that need to
                        // participate in this very depth count. VC "Proper
                        // Group/PE Nesting" (checked via checkParenBalance,
                        // when validationEnabled) catches the case this
                        // depth count alone cannot: splicing already
                        // flattens away PE boundaries, so an individually
                        // unbalanced replacement text can still yield an
                        // overall-balanced (and therefore otherwise
                        // undetected) result once combined with a sibling
                        // PE's own contribution.
                        p = splicePEReferenceAt(p, pendingParamEntities, pendingParamExternalNames,
                                validationEnabled);
                        if (p >= limit) {
                            return -1;
                        }
                        continue;
                    }
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
                // Parsed unconditionally, not just when validating: a
                // malformed content model (wrong connectors, PCDATA not
                // first, a nested group inside Mixed content, a missing
                // "*" on a Mixed content group with element names, ...) is
                // a well-formedness problem in the declaration's own
                // syntax, not a validity constraint - every processor must
                // reject it, not just a validating one.
                cmPos = modelStart;
                cmEnd = p;
                model = parseContentModelGroup(true);
            }
        }

        p = skipWhitespaceInDeclaration(p, pendingParamEntities, pendingParamExternalNames);
        if (p >= limit) {
            return -1;
        }
        if (buf[p] != '>') {
            throw handler.fatalError("Malformed element declaration");
        }
        checkNotFromPESplice(p, "an <!ELEMENT> declaration");
        p++;

        if (validationEnabled && dtdModel.getElementDeclaration(name) != null) {
            handler.error("Validity Constraint: Unique Element Type Declaration (Section 3.2). "
                    + "Element \"" + name + "\" is already declared.");
        }
        ElementDeclaration decl = new ElementDeclaration();
        decl.name = name;
        decl.contentType = type;
        decl.contentModel = model;
        decl.fromExternalSubset = parsingExternalContent;
        if (dtdModel.declareElement(name, decl)) {
            handler.elementDecl(name, model == null ? type.name() : model.toString());
        }
        return p;
    }

    // ===== Content-model tree parsing (validation only) =====
    //
    // Only ever called on a span the caller (scanElementDeclaration) has
    // already confirmed is fully buffered (its own paren-depth-matching
    // scan reached the closing ')' without underflowing) - so, unlike
    // every other parse* method in this class, cmPos/cmEnd need no
    // underflow/resumability handling at all: a plain recursive-descent
    // parse over already-available characters. Kept as its own cursor pair
    // rather than reusing pos/limit, since this parse happens synchronously
    // nested inside scanElementDeclaration's own use of those fields.

    private int cmPos;
    private int cmEnd;

    private int skipCmWhitespace(int p) {
        while (p < cmEnd && isWs(buf[p])) {
            p++;
        }
        return p;
    }

    /** Reads a trailing {@code '?'}/{@code '*'}/{@code '+'} occurrence
     *  indicator at {@link #cmPos}, if present, consuming it. */
    private ElementDeclaration.ContentModel.Occurrence readCmOccurrence() {
        if (cmPos < cmEnd) {
            char c = buf[cmPos];
            if (c == '?') {
                cmPos++;
                return ElementDeclaration.ContentModel.Occurrence.OPTIONAL;
            }
            if (c == '*') {
                cmPos++;
                return ElementDeclaration.ContentModel.Occurrence.ZERO_OR_MORE;
            }
            if (c == '+') {
                cmPos++;
                return ElementDeclaration.ContentModel.Occurrence.ONE_OR_MORE;
            }
        }
        return ElementDeclaration.ContentModel.Occurrence.ONCE;
    }

    /** Parses one particle (cp): either a bare element name, or a nested
     *  parenthesised group, each with its own optional occurrence
     *  indicator. {@link #cmPos} positioned at the particle's first
     *  character on entry, and left just past whatever it consumed on
     *  return - the same "shared cursor threaded through recursive calls"
     *  discipline {@link #pos} uses for the rest of this class, {@link
     *  #cmEnd} playing {@link #limit}'s role (a fixed bound, not itself
     *  advanced). */
    private ElementDeclaration.ContentModel parseContentModelParticle() throws SAXException {
        cmPos = skipCmWhitespace(cmPos);
        if (cmPos < cmEnd && buf[cmPos] == '(') {
            // false: a cp's own nested group is always element content
            // (choice/seq of further cp) - Mixed content (#PCDATA...) is
            // legal only as an ELEMENT declaration's own outermost group,
            // never nested inside another one (cp ::= (Name | choice |
            // seq) ...; Mixed is not itself a cp production).
            return parseContentModelGroup(false);
        }
        int nameStart = cmPos;
        while (cmPos < cmEnd && isNameChar(buf[cmPos])) {
            cmPos++;
        }
        if (cmPos == nameStart) {
            throw handler.fatalError("Malformed content model");
        }
        checkNameStartChar(nameStart);
        String elementName = new String(buf, nameStart, cmPos - nameStart);
        ElementDeclaration.ContentModel.Occurrence occ = readCmOccurrence();
        return new ElementDeclaration.ContentModel(ElementDeclaration.ContentModel.NodeType.ELEMENT, elementName,
                occ);
    }

    /**
     * Parses one parenthesised group - either mixed content ({@code
     * (#PCDATA|a|b)*} or {@code (#PCDATA)}, only legal when {@code
     * allowMixed}) or element content (a sequence {@code (a, b, c)} or
     * choice {@code (a | b | c)} of particles, possibly nested) - {@link
     * #cmPos} positioned at the opening '(' on entry (whether this is the
     * outermost call from {@link #scanElementDeclaration}, which passes
     * {@code allowMixed=true}, or a recursive one from {@link
     * #parseContentModelParticle} for a nested group, which always passes
     * {@code allowMixed=false} - {@code Mixed} is only a legal top-level
     * production for an {@code <!ELEMENT>} declaration, never a legal
     * {@code cp} within another group), left just past this group's own
     * closing ')' plus any trailing occurrence indicator on return. {@link
     * #cmEnd} is fixed for the whole recursive-descent parse (harmless for
     * an inner group to see a bound that extends past its own closing
     * paren - it only ever reads up to whichever comes first, its own
     * matching ')' or {@link #cmEnd}).
     */
    private ElementDeclaration.ContentModel parseContentModelGroup(boolean allowMixed) throws SAXException {
        cmPos++; // consume '('
        {
            cmPos = skipCmWhitespace(cmPos);
            int pm = matchKeyword(cmPos, PCDATA_MARKER);
            if (pm == KW_MATCH && !allowMixed) {
                throw handler.fatalError(
                        "\"#PCDATA\" is only legal in an element declaration's own outermost content model "
                                + "group, not nested inside another group");
            }
            if (pm == KW_MATCH) {
                cmPos += PCDATA_MARKER.length;
                ArrayList<ElementDeclaration.ContentModel> children =
                        new ArrayList<ElementDeclaration.ContentModel>();
                children.add(new ElementDeclaration.ContentModel(ElementDeclaration.ContentModel.NodeType.PCDATA,
                        (String) null, ElementDeclaration.ContentModel.Occurrence.ONCE));
                cmPos = skipCmWhitespace(cmPos);
                while (cmPos < cmEnd && buf[cmPos] == '|') {
                    cmPos++;
                    cmPos = skipCmWhitespace(cmPos);
                    int nameStart = cmPos;
                    while (cmPos < cmEnd && isNameChar(buf[cmPos])) {
                        cmPos++;
                    }
                    if (cmPos == nameStart) {
                        throw handler.fatalError("Malformed content model");
                    }
                    checkNameStartChar(nameStart);
                    String mixedName = new String(buf, nameStart, cmPos - nameStart);
                    if (validationEnabled) {
                        for (int i = 1; i < children.size(); i++) {
                            if (mixedName.equals(children.get(i).elementName)) {
                                handler.error("Validity Constraint: No Duplicate Types (Section 3.3.1). \""
                                        + mixedName + "\" appears more than once in this mixed-content declaration.");
                                break;
                            }
                        }
                    }
                    children.add(new ElementDeclaration.ContentModel(ElementDeclaration.ContentModel.NodeType.ELEMENT,
                            mixedName, ElementDeclaration.ContentModel.Occurrence.ONCE));
                    cmPos = skipCmWhitespace(cmPos);
                }
                if (cmPos >= cmEnd || buf[cmPos] != ')') {
                    throw handler.fatalError("Malformed content model");
                }
                cmPos++;
                // Mixed ::= '(' S? '#PCDATA' (S? '|' S? Name)* S? ')*'
                //         | '(' S? '#PCDATA' S? ')'
                // A group with element names must end in a literal "*" (not
                // "?"/"+", and not merely absent); bare "(#PCDATA)" may
                // have either no occurrence indicator or a "*" (accepted
                // widely as a degenerate case of the first production with
                // zero names), but never "?"/"+".
                ElementDeclaration.ContentModel.Occurrence occ;
                boolean hasElementNames = children.size() > 1;
                if (hasElementNames) {
                    if (cmPos >= cmEnd || buf[cmPos] != '*') {
                        throw handler.fatalError(
                                "A mixed-content declaration with element names must end with \")*\"");
                    }
                    cmPos++;
                    occ = ElementDeclaration.ContentModel.Occurrence.ZERO_OR_MORE;
                } else if (cmPos < cmEnd && (buf[cmPos] == '?' || buf[cmPos] == '+')) {
                    throw handler.fatalError(
                            "\"(#PCDATA)\" may not be followed by a \"" + buf[cmPos] + "\" occurrence indicator");
                } else if (cmPos < cmEnd && buf[cmPos] == '*') {
                    cmPos++;
                    occ = ElementDeclaration.ContentModel.Occurrence.ZERO_OR_MORE;
                } else {
                    occ = ElementDeclaration.ContentModel.Occurrence.ONCE;
                }
                return new ElementDeclaration.ContentModel(ElementDeclaration.ContentModel.NodeType.CHOICE, children,
                        occ);
            }

            ArrayList<ElementDeclaration.ContentModel> children = new ArrayList<ElementDeclaration.ContentModel>();
            children.add(parseContentModelParticle());
            cmPos = skipCmWhitespace(cmPos);
            char separator = 0;
            while (cmPos < cmEnd && (buf[cmPos] == ',' || buf[cmPos] == '|')) {
                char sep = buf[cmPos];
                if (separator == 0) {
                    separator = sep;
                } else if (separator != sep) {
                    throw handler.fatalError(
                            "Cannot mix ',' and '|' within the same content model group");
                }
                cmPos++;
                cmPos = skipCmWhitespace(cmPos);
                children.add(parseContentModelParticle());
                cmPos = skipCmWhitespace(cmPos);
            }
            if (cmPos >= cmEnd || buf[cmPos] != ')') {
                throw handler.fatalError("Malformed content model");
            }
            cmPos++;
            ElementDeclaration.ContentModel.Occurrence groupOcc = readCmOccurrence();
            ElementDeclaration.ContentModel.NodeType groupType = (separator == '|')
                    ? ElementDeclaration.ContentModel.NodeType.CHOICE
                    : ElementDeclaration.ContentModel.NodeType.SEQUENCE;
            if (groupType == ElementDeclaration.ContentModel.NodeType.CHOICE) {
                // Errata E34: the same VC "No Duplicate Types" (Section
                // 3.3.1) already enforced above for Mixed content's own
                // choice list applies equally to an element-content choice
                // group like "(a|a)" - ambiguous/redundant either way.
                // Nested groups (children with no elementName of their own)
                // are not compared - only sibling element-name particles.
                // Unconditional (not gated on validationEnabled): xmlconf's
                // own rmt-e2e-34 exercises this specifically as an "error"-
                // category test, which the harness runs with validation
                // off - this is checkable from the declaration's own local
                // syntax alone, with no need to cross-reference anything
                // elsewhere in the DTD, so every processor can and does
                // catch it regardless of validation mode.
                for (int i = 0; i < children.size(); i++) {
                    ElementDeclaration.ContentModel child = children.get(i);
                    if (child.type != ElementDeclaration.ContentModel.NodeType.ELEMENT) {
                        continue;
                    }
                    for (int j = 0; j < i; j++) {
                        ElementDeclaration.ContentModel other = children.get(j);
                        if (other.type == ElementDeclaration.ContentModel.NodeType.ELEMENT
                                && child.elementName.equals(other.elementName)) {
                            handler.error("Validity Constraint: No Duplicate Types (Section 3.3.1). \""
                                    + child.elementName + "\" appears more than once in this choice group.");
                            break;
                        }
                    }
                }
            }
            return new ElementDeclaration.ContentModel(groupType, children, groupOcc);
        }
    }

    /**
     * Declaration-time attribute VCs, checked (only when {@link
     * #validationEnabled}) just before {@link #scanAttlistDeclaration}
     * commits a new AttDef: VC "ID Attribute Default" (Section 3.3.1) - an
     * {@code ID}-typed attribute must be {@code #IMPLIED} or {@code
     * #REQUIRED}, never {@code #FIXED} or a plain literal default, since an
     * ID's whole purpose (uniquely identifying its element) is defeated by
     * a shared default value - and VC "One ID per Element Type"/"One
     * Notation Per Element Type" (Section 3.3.1) - an element type may not
     * declare more than one attribute of either type.
     */
    private void checkAttlistDeclarationVCs(String elementName, String attrName, String type, DTDModel.Mode mode)
            throws SAXException {
        if ("ID".equals(type)) {
            if (mode != DTDModel.Mode.REQUIRED && mode != DTDModel.Mode.IMPLIED) {
                handler.error("Validity Constraint: ID Attribute Default (Section 3.3.1). "
                        + "ID attribute \"" + attrName + "\" on element \"" + elementName
                        + "\" must be declared #IMPLIED or #REQUIRED.");
            }
            if (dtdModel.hasAttributeOfType(elementName, "ID", attrName)) {
                handler.error("Validity Constraint: One ID per Element Type (Section 3.3.1). "
                        + "Element \"" + elementName + "\" already has an ID attribute declared.");
            }
        } else if ("NOTATION".equals(type) && dtdModel.hasAttributeOfType(elementName, "NOTATION", attrName)) {
            handler.error("Validity Constraint: One Notation Per Element Type (Section 3.3.1). "
                    + "Element \"" + elementName + "\" already has a NOTATION attribute declared.");
        }
    }

    /**
     * Parses one {@code <!ATTLIST ...>} declaration (an element name
     * followed by zero or more AttDefs), {@code p} positioned just past the
     * "<!ATTLIST" keyword. The declared type, default mode ({@code
     * #REQUIRED}/{@code #IMPLIED}/{@code #FIXED}/plain), and any
     * enumeration/{@code NOTATION} value list are all retained, plus the
     * resolved default itself (raw at this point - see {@link
     * #scanDoctypeSubset()}'s finishing step for entity resolution), or null
     * for {@code #REQUIRED}/{@code #IMPLIED}. Returns the position past the
     * declaration's closing '>', or -1 on underflow. {@code
     * pendingParamEntities}/{@code pendingParamExternalNames} are threaded
     * through purely so a parameter entity reference appearing anywhere
     * within this declaration's own syntax can be resolved - see {@link
     * #skipWhitespaceInDeclaration}; ignored entirely unless {@link
     * #parsingExternalContent} (a reference is never actually reachable
     * from the internal subset - {@link #splicePEReferenceAt} rejects it
     * outright there).
     */
    private int scanAttlistDeclaration(int p, HashMap<String, String> pendingEntities,
            HashMap<String, String[]> pendingExternalNames, HashMap<String, String> pendingParamEntities,
            HashMap<String, String[]> pendingParamExternalNames) throws SAXException {
        sawSpliceSinceDeclarationStart = false;
        int ws = p;
        p = skipWhitespaceInDeclaration(p, pendingParamEntities, pendingParamExternalNames);
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
            p = skipWhitespaceInDeclaration(p, pendingParamEntities, pendingParamExternalNames);
            if (p >= limit) {
                return -1;
            }
            if (buf[p] == '>') {
                checkNotFromPESplice(p, "an <!ATTLIST> declaration");
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
            String attrName = namePool.internRange(buf, attrNameStart, p - attrNameStart);

            int ws3 = p;
            p = skipWhitespaceInDeclaration(p, pendingParamEntities, pendingParamExternalNames);
            if (p >= limit) {
                return -1;
            }
            if (p == ws3) {
                throw handler.fatalError("Malformed attribute-list declaration");
            }

            String type;
            List<String> enumeration = null;
            if (buf[p] == '(') {
                // A bare enumeration - SAX/the old parser's AttListDeclParser
                // both report this as the literal type name "ENUMERATION",
                // not the list of allowed values.
                type = "ENUMERATION";
                int r = scanEnumerationList(p, false);
                if (r < 0) {
                    return -1;
                }
                p = r;
                enumeration = lastEnumerationValues;
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
                // "NMTOKEN", "NMTOKENS", or "NOTATION") once confirmed
                // against that fixed keyword set (AttType, XML 1.0 SS3.3) -
                // rejecting anything else, including a wrong-case spelling
                // like "cdata" or "Id", is a well-formedness check, not a
                // validity constraint: it applies whether or not validation
                // is enabled.
                type = new String(buf, typeStart, p - typeStart);
                if (!"CDATA".equals(type) && !"ID".equals(type) && !"IDREF".equals(type)
                        && !"IDREFS".equals(type) && !"ENTITY".equals(type) && !"ENTITIES".equals(type)
                        && !"NMTOKEN".equals(type) && !"NMTOKENS".equals(type) && !"NOTATION".equals(type)) {
                    throw handler.fatalError("Unrecognised attribute type \"" + type + "\"");
                }
                if ("NOTATION".equals(type)) {
                    int ws3b = p;
                    p = skipWhitespaceInDeclaration(p, pendingParamEntities, pendingParamExternalNames);
                    if (p >= limit) {
                        return -1;
                    }
                    if (p == ws3b) {
                        throw handler.fatalError("Malformed attribute-list declaration");
                    }
                    if (buf[p] != '(') {
                        throw handler.fatalError("Malformed attribute-list declaration");
                    }
                    int r = scanEnumerationList(p, true);
                    if (r < 0) {
                        return -1;
                    }
                    p = r;
                    enumeration = lastEnumerationValues;
                }
            }

            int ws4 = p;
            p = skipWhitespaceInDeclaration(p, pendingParamEntities, pendingParamExternalNames);
            if (p >= limit) {
                return -1;
            }
            if (p == ws4) {
                throw handler.fatalError("Malformed attribute-list declaration");
            }

            String rawDefault;
            DTDModel.Mode mode;
            if (buf[p] == '#') {
                int rm = matchKeyword(p, REQUIRED_MARKER);
                if (rm == KW_NEED_MORE) {
                    return -1;
                }
                if (rm == KW_MATCH) {
                    p += REQUIRED_MARKER.length;
                    rawDefault = null;
                    mode = DTDModel.Mode.REQUIRED;
                } else {
                    int im = matchKeyword(p, IMPLIED_MARKER);
                    if (im == KW_NEED_MORE) {
                        return -1;
                    }
                    if (im == KW_MATCH) {
                        p += IMPLIED_MARKER.length;
                        rawDefault = null;
                        mode = DTDModel.Mode.IMPLIED;
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
                        p = skipWhitespaceInDeclaration(p, pendingParamEntities, pendingParamExternalNames);
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
                        int r = scanQuotedLiteralWithCharRefs(p, sb, false, pendingParamEntities, pendingParamExternalNames);
                        if (r < 0) {
                            return -1;
                        }
                        p = r;
                        rawDefault = sb.toString();
                        mode = DTDModel.Mode.FIXED;
                    }
                }
            } else if (buf[p] == '"' || buf[p] == '\'') {
                StringBuilder sb = new StringBuilder();
                int r = scanQuotedLiteralWithCharRefs(p, sb, false, pendingParamEntities, pendingParamExternalNames);
                if (r < 0) {
                    return -1;
                }
                p = r;
                rawDefault = sb.toString();
                mode = DTDModel.Mode.NONE;
            } else {
                throw handler.fatalError("Malformed attribute-list declaration");
            }

            if (rawDefault != null) {
                checkAttlistDefaultEntitiesDeclared(rawDefault, pendingEntities, pendingExternalNames);
            }
            if (validationEnabled) {
                checkAttlistDeclarationVCs(elementName, attrName, type, mode);
            }
            if (dtdModel.declareAttribute(elementName, attrName, type, mode, rawDefault, enumeration,
                    parsingExternalContent)) {
                handler.attributeDecl(elementName, attrName, formatAttributeDeclType(type, enumeration),
                        formatAttributeDeclMode(mode), rawDefault);
            }
            // loop continues: another AttDef, or S? '>' to end the declaration
        }
    }

    private static String formatAttributeDeclType(String type, List<String> enumeration) {
        if (enumeration == null || enumeration.isEmpty()) {
            return type;
        }
        StringBuilder sb = new StringBuilder();
        if ("NOTATION".equals(type)) {
            sb.append("NOTATION ");
        }
        sb.append('(');
        for (int i = 0; i < enumeration.size(); i++) {
            if (i > 0) {
                sb.append('|');
            }
            sb.append(enumeration.get(i));
        }
        return sb.append(')').toString();
    }

    private static String formatAttributeDeclMode(DTDModel.Mode mode) {
        switch (mode) {
            case REQUIRED:
                return "#REQUIRED";
            case IMPLIED:
                return "#IMPLIED";
            case FIXED:
                return "#FIXED";
            default:
                return null;
        }
    }

    /**
     * Parses {@code "<!DOCTYPE" Name (S ExternalID)? S?} - everything up to
     * (but not including) an internal subset or the closing '&gt;'. That
     * prefix alone is side-effect-free (no handler calls), so - unlike the
     * internal subset itself, see {@link #scanDoctypeSubset()} - it is safe
     * to treat as one atomic retry-whole-on-underflow construct exactly like
     * every other atomic construct in this class; {@link
     * XMLHandler#startDTD}/{@link XMLHandler#endDTD} only fire once this
     * method has otherwise fully committed (either branch below), never
     * speculatively.
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
        // Reject any DOCTYPE outright when disallow-doctype-decl is set,
        // before any DTD processing (parameter entities, external subset
        // fetch, etc.) begins - matching ContentParser.
        if (settings.disallowDoctypeDecl) {
            throw handler.fatalError(
                    "DOCTYPE is disallowed when the feature "
                    + "\"http://apache.org/xml/features/disallow-doctype-decl\" is set to true");
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
            doctypePublicId = lastExternalIdPublicId;
            doctypeSystemId = lastExternalIdSystemId;
            p = r;
            p = skipOptionalWhitespace(p);
            if (p >= limit) {
                pos = tagStart;
                return false;
            }
        }

        if (buf[p] == '[') {
            // From here on, the internal subset may call scanComment()/
            // scanPI() - real, irreversible handler.startComment()/
            // commentData()/piTarget()/piData() calls - so this can no
            // longer be an atomic whole-retry construct: a later underflow
            // deeper in the
            // subset must not cause an already-fired comment/PI to fire
            // again. Hand off to the properly resumable scanDoctypeSubset(),
            // which - like scanAttributesAndTagEnd()/inStartTag - tracks its
            // own progress via pos and the doctypeXxx fields rather than
            // rewinding to tagStart. handler.startDTD() fires right here,
            // exactly once (this branch is never re-entered on a resumed
            // call - a later call finds inDoctype already true and goes
            // straight to scanDoctypeSubset() via scan()'s main loop
            // instead), so every notationDecl/unparsedEntityDecl the
            // internal (and, later, external) subset produces is correctly
            // bracketed between it and the matching handler.endDTD() at
            // scanDoctypeSubset()'s own completion.
            requestExternalSubset(name);
            handler.startDTD(name, doctypeExternalPublicId, doctypeExternalSystemId);
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
        requestExternalSubset(name);
        handler.startDTD(name, doctypeExternalPublicId, doctypeExternalSystemId);
        finishDoctypeExternalSubset(name);
        handler.endDTD();
        doctypeSeen = true;
        doctypeName = name;
        return true;
    }

    /** {@link EntityResolver2#getExternalSubset} integration for a DOCTYPE
     *  that declares no external ID of its own: called from {@link
     *  #scanDoctype}'s two committed branches, right before {@link
     *  XMLHandler#startDTD} fires (and therefore, per that method's
     *  EntityResolver2 contract, before any internal subset data is
     *  reported), so a subset the application supplies is reported exactly
     *  as if the document had declared it - startDTD carries the {@link
     *  InputSource}'s own IDs, and the source itself is parsed where the
     *  declared external subset otherwise would be, by {@link
     *  #finishDoctypeExternalSubset}, after the internal subset (if any)
     *  has committed. Not consulted when the DOCTYPE already has an
     *  external ID, or when external-parameter-entities is off (the
     *  supplied subset would be skipped exactly like a declared one, so
     *  don't ask for it at all). */
    private void requestExternalSubset(String rootName) throws SAXException {
        if (doctypeExternalSystemId != null || doctypeExternalPublicId != null
                || !settings.externalParameterEntities
                || !(entityResolver instanceof EntityResolver2)) {
            return;
        }
        InputSource source;
        try {
            source = resolutionHelper().getExternalSubset(rootName);
        } catch (IOException e) {
            throw handler.fatalError("Failed to resolve the external DTD subset for \"" + rootName
                    + "\": " + e.getMessage());
        }
        if (source != null) {
            doctypeExternalSubsetSource = source;
            doctypeExternalPublicId = source.getPublicId();
            doctypeExternalSystemId = source.getSystemId();
        }
    }

    /** Shared by both of {@link #scanDoctype}'s/{@link #scanDoctypeSubset}'s
     *  completion paths: fetches and parses the DOCTYPE's external subset,
     *  if {@link #skipExternalId} captured one, then resolves ATTLIST
     *  default values against the now-fully-known entity table (which may
     *  have just grown from the external subset, in addition to whatever
     *  the internal subset, if any, already contributed). The fetch+parse
     *  is bracketed by {@link XMLHandler#startEntity}/{@link
     *  XMLHandler#endEntity} using the well-known {@code "[dtd]"}
     *  pseudo-name {@code LexicalHandler} itself documents for exactly this
     *  purpose - nested inside the {@link XMLHandler#startDTD}/{@link
     *  XMLHandler#endDTD} pair both callers fire around this method. */
    private void finishDoctypeExternalSubset(String rootName) throws SAXException {
        if (doctypeExternalSystemId != null || doctypeExternalSubsetSource != null) {
            // The "[dtd]" bracket fires even when external-parameter-
            // entities is off and the fetch itself is skipped - matching
            // the old pipeline, where DTDParser fires the pair around a
            // processExternalEntity call that returns without fetching.
            handler.startEntity("[dtd]");
            if (settings.externalParameterEntities) {
                char[] chars;
                String what = "the external DTD subset for \"" + rootName + "\"";
                if (doctypeExternalSubsetSource != null) {
                    // Supplied by EntityResolver2.getExternalSubset for a
                    // DOCTYPE with no external ID - see scanDoctype.
                    try {
                        chars = readExternalSource(doctypeExternalSubsetSource, what,
                                doctypeExternalSystemId);
                        checkVersionCompatibility(chars, what);
                    } catch (IOException e) {
                        throw handler.fatalError("Failed to fetch " + what + " ("
                                + doctypeExternalSystemId + "): " + e.getMessage());
                    }
                } else {
                    chars = fetchExternalResource("[dtd]", what,
                            doctypeExternalPublicId, doctypeExternalSystemId);
                }
                // See baseSystemId's own Javadoc: for the duration of parsing
                // the external subset, the current base becomes its own
                // resolved location, so a parameter entity declared directly
                // within it (not via another parameter entity) captures the
                // right declaration-time base.
                String savedBaseSystemId = baseSystemId;
                baseSystemId = lastResolvedSystemId;
                try {
                    parseExternalSubset(chars);
                } finally {
                    baseSystemId = savedBaseSystemId;
                }
            }
            handler.endEntity("[dtd]");
        }
        doctypeExternalPublicId = null;
        doctypeExternalSystemId = null;
        doctypeExternalSubsetSource = null;
        resolveAttlistDefaultsAgainstEntities();
        if (validationEnabled) {
            checkAttlistDefaultsLegal();
        }
    }

    /**
     * Resumable scan of the internal subset's declarations, from just past
     * the opening '[' up to and including the closing '&gt;'. Called both
     * fresh (from {@link #scanDoctype}, right after seeing '[') and again by
     * {@link #scan()}'s main loop whenever {@link #inDoctype} is still true
     * at the start of a {@link #receive(CharBuffer)} call. Unlike every
     * other atomic construct in this class, this cannot simply rewind to a
     * fixed start point on underflow: both {@code <!--comment-->} and {@code
     * <?PI?>} fire a real event (once {@link XMLHandler#startComment}/{@link
     * XMLHandler#piTarget} has fired) as a side effect, and, for a text/data
     * span too large for one buffer, are themselves resumable via {@link
     * #scanComment}/{@link #scanCommentData} and {@link #scanPI}/{@link
     * #scanPIData} respectively - this method checks {@link #inComment}/
     * {@link #inPI} at the top of its own loop for exactly that reason.
     * Instead, {@link #pos} only ever advances past a declaration once it
     * has been fully, successfully processed (comment/PI emitted, entity
     * recorded, or other declaration skipped) - the same "confirmed
     * progress, resume from here, never rewind past it"
     * never rewind past it" discipline {@link #scanContent()}/{@link
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
                if (inPI) {
                    // A PI nested in the internal subset (markupdecl includes
                    // PI) suspended mid-data on a prior call - resume it
                    // directly, exactly like scan()'s own main loop does,
                    // rather than falling through to skipOptionalWhitespace/
                    // buf[pos] below, which would misinterpret pos sitting
                    // in the middle of the PI's own data as the start of a
                    // fresh declaration.
                    if (!scanPIData()) {
                        return false;
                    }
                    inPI = false;
                    continue;
                }
                if (inComment) {
                    // A comment nested in the internal subset, suspended
                    // mid-text on a prior call - same reasoning as the inPI
                    // check just above.
                    if (!scanCommentData()) {
                        return false;
                    }
                    inComment = false;
                    continue;
                }
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
                                int r = scanAttlistDeclaration(pos + ATTLIST_MARKER.length, doctypePendingEntities,
                                        doctypePendingExternalNames, doctypePendingParamEntities,
                                        doctypePendingParamExternalNames);
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
                                    int r = scanElementDeclaration(pos + ELEMENT_MARKER.length,
                                            doctypePendingParamEntities, doctypePendingParamExternalNames);
                                    if (r < 0) {
                                        return false;
                                    }
                                    pos = r;
                                } else {
                                    int nm = matchKeyword(pos, NOTATION_MARKER);
                                    if (nm == KW_NEED_MORE) {
                                        return false;
                                    }
                                    if (nm != KW_MATCH) {
                                        throw handler.fatalError(
                                                "Expected an element, attribute-list, entity, or notation declaration");
                                    }
                                    int r = scanNotationDeclaration(pos + NOTATION_MARKER.length);
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
        reportUnparsedEntities(doctypePendingExternalNames);
        parameterEntities.putAll(doctypePendingParamEntities);
        parameterEntityExternalIds.putAll(doctypePendingParamExternalNames);
        finishDoctypeExternalSubset(doctypeNamePending);
        handler.endDTD();
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

    /** Errata E13: true once a parameter entity reference has been
     *  recognized anywhere in the *internal* DTD subset (set by {@link
     *  #expandParameterEntityReference}) - read by {@link
     *  #checkEntityReferenceable} to decide whether an undeclared general
     *  entity reference is a fatal well-formedness error (false) or a
     *  merely recoverable validity error (true). */
    private boolean sawInternalSubsetParameterEntityReference;

    /** Validates that {@code name} may be referenced now: declared (as a
     *  general, non-external entity), not currently being expanded (WFC "No
     *  Recursion"), and within the total-expansion-count guard (a simple
     *  mitigation for entity-expansion amplification, aka "billion laughs" -
     *  not a substitute for a real resource budget, but enough for this
     *  milestone's scope). Returns true if {@code name} is actually usable
     *  (declared); false only for the errata E13 case just above - an
     *  undeclared entity that this document's own internal-subset PE usage
     *  downgrades to non-fatal - which the caller must treat as if {@code
     *  name} had empty replacement text, having already had the VC
     *  reported here. */
    /** @param allowExternal true for a content-context reference (external
     *         entities are fetched there - see {@link
     *         #expandGeneralEntityInContent}); false for an attribute-value
     *         reference, where WFC "No External Entity References" makes
     *         referencing one always fatal, regardless of fetchability. */
    private boolean checkEntityReferenceable(String name, boolean allowExternal) throws SAXException {
        boolean isGeneral = generalEntities.containsKey(name);
        boolean isExternal = externalEntityNames.containsKey(name);
        if (!isGeneral && !isExternal) {
            if (sawInternalSubsetParameterEntityReference) {
                handler.error("Validity Constraint: Entity Declared (Section 4.1). Entity \"" + name
                        + "\" was not declared (a parameter entity reference elsewhere in the internal "
                        + "DTD subset downgrades this from a well-formedness error to a validity error).");
                return false;
            }
            throw handler.fatalError("Entity \"" + name + "\" was not declared");
        }
        if (isExternal && !allowExternal) {
            throw handler.fatalError(
                    "External entity \"" + name + "\" may not be referenced in an attribute value");
        }
        if (isExternal && externalEntityNames.get(name)[2] != null) {
            throw handler.fatalError("Well-Formedness Constraint: Parsed Entity (Section 4.1). "
                    + "Entity reference \"&" + name + ";\" names an unparsed entity.");
        }
        if (standalone && externallyDeclaredGeneralEntities.contains(name)) {
            throw handler.fatalError("Well-Formedness Constraint: Entity Declared (Section 4.1). "
                    + "Document has standalone=\"yes\" but entity \"" + name
                    + "\" is declared in external markup, and is referenced.");
        }
        if (entityExpansionStack.contains(name)) {
            throw handler.fatalError("Recursive entity reference: &" + name + ";");
        }
        checkEntityExpansionLimit();
        return true;
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
        if (!checkEntityReferenceable(name, true)) {
            // Errata E13: undeclared, but downgraded to a non-fatal VC by
            // this document's own internal-subset PE usage - nothing
            // actually declared for it, so there is no replacement text to
            // report; treat the reference as producing nothing at all.
            return;
        }
        char[] replacementChars;
        String[] externalIds = externalEntityNames.get(name);
        // Fired here, not any earlier - mirrors the old pipeline's own
        // placement: after the reference is confirmed referenceable (a
        // rejected reference never gets a start/endEntity pair at all), but
        // before either fetching (external) or converting to a re-scannable
        // string (internal) its replacement text - see XMLHandler#startEntity's
        // "never fired for... an attribute value" carve-out, which is why
        // this class's other entity-expansion method (used in attribute
        // values) has no matching call.
        handler.startEntity(name);
        if (externalIds != null) {
            // External general entity: fetched lazily, right here, at first
            // reference - matching how internal entities are also only
            // resolved lazily. When external-general-entities is off
            // (secure default), skip the fetch silently like ContentParser.
            if (!settings.externalGeneralEntities) {
                handler.endEntity(name);
                return;
            }
            // A fetched entity's content may begin with an optional text
            // declaration (same '<?xml ...?>' shape as the main document's
            // XML declaration, but version is optional and it's never
            // followed by 'standalone' - close enough to reuse the same
            // strip logic; Scanner does not parse declarations itself either
            // way, per its established boundary).
            replacementChars = XmlDeclUtil.stripXmlDeclaration(
                    fetchExternalEntity(name, externalIds[0], externalIds[1]), handler);
        } else {
            replacementChars = generalEntities.get(name).toCharArray();
        }

        entityExpansionStack.add(name);
        char[] savedBuf = buf;
        int savedPos = pos;
        int savedLimit = limit;
        boolean savedContentRunOpen = contentRunOpen;
        boolean savedAllowRestrictedCharInContent = allowRestrictedCharInContent;
        int stackDepthAtEntry = elementStack.size();

        buf = replacementChars;
        pos = 0;
        limit = buf.length;
        contentRunOpen = false;
        allowRestrictedCharInContent = restrictedCharEntities.contains(name);
        entityStackFloors.add(stackDepthAtEntry);
        try {
            scan();
            if (pos != limit || inStartTag || inAttributeValue || inPI || inComment || inCDATA || inDoctype) {
                throw handler.fatalError("Entity \"" + name + "\" replacement text is not well-formed");
            }
            if (elementStack.size() != stackDepthAtEntry) {
                throw handler.fatalError("Well-Formedness Constraint: Parsed Entity (Section 4.1). Entity \""
                        + name + "\" replacement text is not well-formed: "
                        + "element boundaries must nest within entity boundaries");
            }
            if (contentRunOpen) {
                // The replacement text's final characters()/ignorableWhitespace()
                // run ended exactly at the entity's own boundary (not a
                // receive() underflow - there is no more data coming for this
                // buffer, ever) - close it out via the same helper (and so
                // the same contentRunIsWhitespace-routing) scanContent() uses
                // for its own empty-closing-event pattern at a markup
                // boundary.
                emitContentRun(EMPTY_BUFFER, true, contentRunIsWhitespace);
            }
            handler.endEntity(name);
        } finally {
            buf = savedBuf;
            pos = savedPos;
            limit = savedLimit;
            contentRunOpen = savedContentRunOpen;
            allowRestrictedCharInContent = savedAllowRestrictedCharInContent;
            entityExpansionStack.remove(entityExpansionStack.size() - 1);
            entityStackFloors.remove(entityStackFloors.size() - 1);
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
        if (!checkEntityReferenceable(name, false)) {
            // Errata E13 - see expandGeneralEntityInContent's identical carve-out.
            return "";
        }
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
            if (isWs(c)) {
                sb.append(' ');
                q++;
                continue;
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
     * internal subset has been parsed - safe to defer this far because
     * {@link #checkAttlistDefaultEntitiesDeclared} already rejected, back at
     * declaration time, any reference this text could have made to an
     * entity not yet declared, so every name still here is guaranteed
     * resolvable by now.
     */
    private String resolveAttlistDefaultValue(String raw) throws SAXException {
        return resolveAttributeText(raw.toCharArray(), "an attribute default value");
    }

    /** WFC "Entity Declared" (Section 4.1)'s own special case: unlike a
     *  general entity reference in content (always resolved only after the
     *  whole DTD is known, so declaration order relative to the reference
     *  never actually matters), the spec explicitly requires that "the
     *  declaration of a general entity must precede any reference to it
     *  which appears in a default value in an attribute-list declaration" -
     *  checked here, immediately, against only what has been declared so
     *  far ({@code pendingEntities}/{@code pendingExternalNames} - the
     *  subset currently being parsed - falling back to the scanner-wide
     *  maps for a name declared in an earlier-parsed subset), rather than
     *  deferred to {@link #resolveAttlistDefaultValue}'s later, whole-
     *  subset-known pass. Predefined entities are always fine, at any
     *  point, and never reach this check (matching {@link
     *  #resolveAttributeText}'s own precedence). */
    private void checkAttlistDefaultEntitiesDeclared(String raw, HashMap<String, String> pendingEntities,
            HashMap<String, String[]> pendingExternalNames) throws SAXException {
        char[] text = raw.toCharArray();
        int len = text.length;
        int q = 0;
        while (q < len) {
            if (text[q] != '&') {
                q++;
                continue;
            }
            int nameStart = q + 1;
            int p = nameStart;
            while (p < len && isNameChar(text[p])) {
                p++;
            }
            if (p >= len || text[p] != ';') {
                // Malformed - already rejected by scanQuotedLiteralWithCharRefs's
                // own scanReferenceNameLiteral when this text was first scanned.
                q++;
                continue;
            }
            if (matchPredefined(text, nameStart, p - nameStart) == null) {
                String name = new String(text, nameStart, p - nameStart);
                if (!pendingEntities.containsKey(name) && !pendingExternalNames.containsKey(name)
                        && !generalEntities.containsKey(name) && !externalEntityNames.containsKey(name)) {
                    throw handler.fatalError("Well-Formedness Constraint: Entity Declared (Section 4.1). "
                            + "Entity \"" + name + "\" referenced in an attribute default value must be "
                            + "declared before the <!ATTLIST> declaration that references it.");
                }
            }
            q = p + 1;
        }
    }

    // ===== External entity/DTD fetching =====
    //
    // Both the DOCTYPE's own external subset and an external general
    // entity's replacement text are fetched the same way: resolve
    // publicId/systemId via entityResolver if set, falling back to
    // resolving systemId against baseSystemId and opening it directly
    // (matching the old parser's own ContentParser.processExternalEntity
    // fallback); decode the fetched bytes with the same BOM/declared-
    // encoding detection used for the main document, but as a one-shot,
    // fully-buffered decode rather than a streaming one (see XmlDeclUtil).
    // Both call sites strip a leading declaration themselves afterward -
    // Scanner never parses a declaration itself; see XmlDeclUtil.stripXmlDeclaration.

    /** A {@link Locator} view of {@link #baseSystemId} (which changes while
     *  parsing an external subset or external parameter entity), so {@link
     *  EntityResolutionHelper} - written against the old pipeline's
     *  Locator-carrying {@code ContentParser} - always sees the current
     *  base URI. */
    private final Locator baseSystemIdLocator = new Locator() {
        public String getPublicId() {
            return null;
        }
        public String getSystemId() {
            return baseSystemId;
        }
        public int getLineNumber() {
            return -1;
        }
        public int getColumnNumber() {
            return -1;
        }
    };

    /** Builds the {@link EntityResolutionHelper} used for every external
     *  fetch: {@link EntityResolver2#resolveEntity(String, String, String,
     *  String)} (with the entity name - {@code "%name"} for a parameter
     *  entity, {@code "[dtd]"} for the external subset) when the resolver
     *  supports it, plain SAX1 {@link EntityResolver#resolveEntity} as the
     *  fallback, relative-URI resolution against {@link #baseSystemId}
     *  gated on {@link ScannerSettings#resolveDTDURIs}. Constructed on
     *  demand (it is stateless apart from the fields it wraps). */
    private EntityResolutionHelper resolutionHelper() {
        return new EntityResolutionHelper(entityResolver, baseSystemIdLocator, settings.resolveDTDURIs);
    }

    /** Fetches and decodes {@code publicId}/{@code systemId} - the shared
     *  core of external entity and external DTD subset fetching. {@code
     *  name} identifies the entity for {@link EntityResolver2}-style
     *  resolution ({@code "name"}, {@code "%name"} or {@code "[dtd]"});
     *  {@code what} is used only in error messages. */
    private char[] fetchExternalResource(String name, String what, String publicId, String systemId)
            throws SAXException {
        // Defense-in-depth: JAXP accessExternalDTD gate (empty = none).
        // Feature flags that disable external loading skip this method
        // entirely; reaching here with an empty allow-list is a hard deny.
        // The protocol is checked on the resolved absolute URI where
        // resolve-dtd-uris permits resolving one, so a relative systemId
        // inherits (and is checked against) its base's protocol.
        if (systemId != null) {
            String checkId = settings.resolveDTDURIs ? resolveSystemId(systemId) : systemId;
            if (checkId == null) {
                checkId = systemId;
            }
            if (settings.accessExternalDTD.isEmpty()) {
                throw handler.fatalError(
                        "Access to external entity denied by accessExternalDTD property "
                        + "(no protocols allowed): " + checkId);
            }
            if (!DefaultEntityResolver.isProtocolAllowed(checkId, settings.accessExternalDTD)) {
                throw handler.fatalError(
                        "Access to external entity denied by accessExternalDTD property: " + checkId);
            }
        }
        try {
            InputSource resolved = null;
            if (entityResolver != null) {
                resolved = resolutionHelper().resolveEntity(name, publicId, systemId);
            }
            if (resolved == null) {
                // No resolver, or it declined: open the systemId directly,
                // resolved against the current base only when
                // resolve-dtd-uris says to.
                String resolvedSystemId = settings.resolveDTDURIs ? resolveSystemId(systemId) : systemId;
                if (resolvedSystemId == null) {
                    throw handler.fatalError("Cannot resolve " + what + ": no system identifier");
                }
                resolved = new InputSource(resolvedSystemId);
            }
            char[] chars = readExternalSource(resolved, what, systemId);
            checkVersionCompatibility(chars, what);
            return chars;
        } catch (IOException e) {
            throw handler.fatalError("Failed to fetch " + what + " (" + systemId + "): " + e.getMessage());
        }
    }

    /** Drains {@code source} to a char array - byte stream (decoded with
     *  the same BOM/declared-encoding detection as the main document),
     *  character stream, or, failing both, a fresh stream opened from its
     *  system identifier. Sets {@link #lastResolvedSystemId} to the
     *  location actually read, falling back to {@code originalSystemId}
     *  resolved against the current base for a stream-only source that
     *  carries no location of its own. */
    private char[] readExternalSource(InputSource source, String what, String originalSystemId)
            throws SAXException, IOException {
        String sourceSystemId = source.getSystemId() != null ? source.getSystemId()
                : resolveSystemId(originalSystemId);
        InputStream in = source.getByteStream();
        if (in != null) {
            lastResolvedSystemId = sourceSystemId;
            return XmlDeclUtil.decodeBytes(readAllExternalBytes(in), source.getEncoding());
        }
        Reader reader = source.getCharacterStream();
        if (reader != null) {
            lastResolvedSystemId = sourceSystemId;
            return readAllExternalChars(reader);
        }
        if (sourceSystemId == null) {
            throw handler.fatalError("Cannot resolve " + what + ": no system identifier");
        }
        lastResolvedSystemId = sourceSystemId;
        return XmlDeclUtil.decodeBytes(readAllExternalBytes(openStream(sourceSystemId)), source.getEncoding());
    }

    /** Set by {@link #fetchExternalResource} - the fully-resolved (absolute,
     *  where possible) system identifier the fetch actually used. Read by
     *  {@link #finishDoctypeExternalSubset}/{@link
     *  #expandParameterEntityReference} to become the new {@link
     *  #baseSystemId} for the duration of parsing what was just fetched. */
    private String lastResolvedSystemId;

    /** XML 1.1 Section 4.3.4: a document may not reference an external
     *  entity or DTD subset whose own leading declaration declares a
     *  version number incompatible with the referring document's - a 1.0
     *  document may not pull in anything declaring a version other than
     *  1.0, and (since 1.1 is the highest version currently defined) a 1.1
     *  document may not pull in anything declaring a version other than 1.0
     *  or 1.1. Checked once, here, at the single choke point every external
     *  fetch (general entity, parameter entity, external DTD subset) already
     *  passes through - this also naturally covers indirect references
     *  (an entity fetched from within another entity's own replacement
     *  text), since the comparison is always against this field's fixed,
     *  document-wide {@link #xml11} value, never the immediate caller's. */
    private void checkVersionCompatibility(char[] chars, String what) throws SAXException {
        String v = XmlDeclUtil.extractVersionNum(chars);
        if (v == null || v.equals("1.0") || (xml11 && v.equals("1.1"))) {
            return;
        }
        throw handler.fatalError("XML 1.1 Section 4.3.4: " + what + " declares version \"" + v
                + "\", which is not compatible with this document's own version (" + (xml11 ? "1.1" : "1.0") + ")");
    }

    private char[] fetchExternalEntity(String name, String publicId, String systemId) throws SAXException {
        return fetchExternalResource(name, "entity \"" + name + "\"", publicId, systemId);
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

    private static char[] readAllExternalChars(Reader reader) throws IOException {
        try {
            CharArrayWriter out = new CharArrayWriter();
            char[] chunk = new char[8192];
            int n;
            while ((n = reader.read(chunk)) != -1) {
                out.write(chunk, 0, n);
            }
            return out.toCharArray();
        } finally {
            reader.close();
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
        char[] chars = XmlDeclUtil.stripXmlDeclaration(rawChars, handler);
        HashMap<String, String> pendingEntities = new HashMap<String, String>();
        HashMap<String, String[]> pendingExternalNames = new HashMap<String, String[]>();
        HashMap<String, String> pendingParamEntities = new HashMap<String, String>();
        HashMap<String, String[]> pendingParamExternalNames = new HashMap<String, String[]>();

        char[] savedBuf = buf;
        int savedPos = pos;
        int savedLimit = limit;
        boolean savedParsingExternalContent = parsingExternalContent;
        buf = chars;
        pos = 0;
        limit = chars.length;
        parsingExternalContent = true;
        try {
            parseMarkupDeclSeq(false, false, pendingEntities, pendingExternalNames, pendingParamEntities,
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
                    if (entry.getValue()[2] != null) {
                        handler.unparsedEntityDecl(entry.getKey(), entry.getValue()[0], entry.getValue()[1],
                                entry.getValue()[2]);
                    }
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
            parsingExternalContent = savedParsingExternalContent;
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
     * @param sectionOpenerFromSplice only meaningful when {@code
     *         stopAtSectionEnd} - whether the conditional section's own
     *         opening delimiter came from a parameter entity splice (see
     *         {@link #parseConditionalSection}'s own capture of it) - VC
     *         "Proper Conditional Section/PE Nesting" requires the closing
     *         {@code "]]>"} found below to agree.
     */
    private void parseMarkupDeclSeq(boolean stopAtSectionEnd, boolean sectionOpenerFromSplice,
            HashMap<String, String> pendingEntities, HashMap<String, String[]> pendingExternalNames,
            HashMap<String, String> pendingParamEntities, HashMap<String, String[]> pendingParamExternalNames)
            throws SAXException {
        while (true) {
            pos = skipOptionalWhitespace(pos);
            if (stopAtSectionEnd && pos + 2 < limit && buf[pos] == ']' && buf[pos + 1] == ']'
                    && buf[pos + 2] == '>') {
                if ((pos < lastSpliceEnd) != sectionOpenerFromSplice) {
                    handler.error("Validity Constraint: Proper Conditional Section/PE Nesting (Section 3.4). "
                            + "A conditional section's opening and closing delimiters must be contained in "
                            + "the same parameter entity replacement text (or both be literal).");
                }
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
                            int r = scanAttlistDeclaration(pos + ATTLIST_MARKER.length, pendingEntities,
                                    pendingExternalNames, pendingParamEntities, pendingParamExternalNames);
                            if (r < 0) {
                                throw handler.fatalError("Malformed attribute-list declaration");
                            }
                            pos = r;
                        } else {
                            int elm = matchKeyword(pos, ELEMENT_MARKER);
                            if (elm == KW_MATCH) {
                                int r = scanElementDeclaration(pos + ELEMENT_MARKER.length, pendingParamEntities,
                                        pendingParamExternalNames);
                                if (r < 0) {
                                    throw handler.fatalError("Malformed element declaration");
                                }
                                pos = r;
                            } else {
                                int nm = matchKeyword(pos, NOTATION_MARKER);
                                if (nm != KW_MATCH) {
                                    throw handler.fatalError(
                                            "Expected an element, attribute-list, entity, or notation declaration");
                                }
                                int r = scanNotationDeclaration(pos + NOTATION_MARKER.length);
                                if (r < 0) {
                                    throw handler.fatalError("Malformed notation declaration");
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
        // The conditionalSect keyword itself may be provided by a
        // parameter entity reference (a common real-world idiom: "<![
        // %pe; [ ... ]]>" with pe declared as literally "INCLUDE" or
        // "IGNORE") - PE-aware for exactly the same reason every markup
        // declaration's own whitespace-skip points are.
        pos = skipWhitespaceInDeclaration(pos, pendingParamEntities, pendingParamExternalNames);
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
        // VC "Proper Conditional Section/PE Nesting" (Section 3.4):
        // unlike a markup declaration's own opening delimiter (always
        // literal, by construction - see checkNotFromPESplice's own
        // Javadoc), a conditional section's own "INCLUDE["/"IGNORE["
        // opener may itself come from a parameter entity (a common real-
        // world idiom, see the comment above) - so this end needs an
        // explicit before/after comparison instead of assuming one side
        // is always literal. Captured here, at the '[' itself, and
        // compared against the closing "]]>" in parseMarkupDeclSeq's own
        // stopAtSectionEnd branch.
        boolean openerFromSplice = pos < lastSpliceEnd;
        pos++;
        if (include) {
            parseMarkupDeclSeq(true, openerFromSplice, pendingEntities, pendingExternalNames, pendingParamEntities,
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

        if (!parsingExternalContent) {
            // Errata E13 / WFC "Entity Declared" (Section 4.1): a parameter
            // entity reference occurring anywhere in the *internal* DTD
            // subset - even one wholly unrelated to any particular general
            // entity - is enough to downgrade every undeclared-general-
            // entity reference in this document from a fatal well-
            // formedness error to a merely recoverable validity error (a
            // non-validating processor is not required to have read
            // whatever the PE's own replacement text might have declared).
            sawInternalSubsetParameterEntityReference = true;
        }
        char[] replacementChars = resolveParameterEntityReplacement(name, pendingParamEntities,
                pendingParamExternalNames);

        if (parameterEntityExpansionStack.contains(name)) {
            throw handler.fatalError("Recursive parameter entity reference: %" + name + ";");
        }
        checkEntityExpansionLimit();
        parameterEntityExpansionStack.add(name);
        char[] savedBuf = buf;
        int savedLimit = limit;
        boolean savedParsingExternalContent = parsingExternalContent;
        String savedBaseSystemId = baseSystemId;
        buf = replacementChars;
        pos = 0;
        limit = buf.length;
        // WFC "PEs in Internal Subset"'s own exception: a reference that
        // occurs *in an external parameter entity* is exempt even when the
        // reference itself was recognized while parsing the internal
        // subset - only an *internal* parameter entity's expansion stays
        // subject to the surrounding context's own parsingExternalContent
        // (inherited unchanged, whichever way it already was).
        if (lastParamEntityWasExternal) {
            parsingExternalContent = true;
            // baseSystemId's own Javadoc: for the duration of parsing this
            // external entity's own replacement text, the current base
            // becomes ITS resolved location - so any further external
            // parameter entity declared within it captures the right
            // declaration-time base of its own (see scanEntityDeclaration's
            // external-PE branch), regardless of how indirectly (via an
            // internal parameter entity merely naming it) this one was
            // eventually expanded.
            baseSystemId = lastResolvedSystemId;
        }
        try {
            parseMarkupDeclSeq(false, false, pendingEntities, pendingExternalNames, pendingParamEntities,
                    pendingParamExternalNames);
            if (pos != limit) {
                throw handler.fatalError("Parameter entity \"" + name + "\" replacement text is not well-formed");
            }
        } finally {
            buf = savedBuf;
            limit = savedLimit;
            parsingExternalContent = savedParsingExternalContent;
            baseSystemId = savedBaseSystemId;
            parameterEntityExpansionStack.remove(parameterEntityExpansionStack.size() - 1);
        }
        pos = resumeAt;
    }

    /** Set by {@link #resolveParameterEntityReplacement} - whether the
     *  value it just returned came from a freshly-fetched external resource
     *  (true) or an already-declared literal value (false). Read by {@link
     *  #expandParameterEntityReference} to decide whether its own nested
     *  {@link #parseMarkupDeclSeq} call should run with {@link
     *  #parsingExternalContent} set, per WFC "PEs in Internal Subset"'s own
     *  "does not apply to references in external parameter entities"
     *  exception. */
    private boolean lastParamEntityWasExternal;

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
            lastParamEntityWasExternal = false;
            return literal.toCharArray();
        }
        String[] externalIds = pendingParamExternalNames.get(name);
        if (externalIds == null) {
            externalIds = parameterEntityExternalIds.get(name);
        }
        if (externalIds == null) {
            throw handler.fatalError("Parameter entity \"%" + name + ";\" was not declared");
        }
        if (!settings.externalParameterEntities) {
            // Secure default: do not fetch external parameter entities.
            lastParamEntityWasExternal = true;
            return new char[0];
        }
        lastParamEntityWasExternal = true;
        // Resolve externalIds[1] against this entity's own declaration-time
        // base (externalIds[2]), never baseSystemId's current value here -
        // see baseSystemId's own Javadoc for why they can differ.
        String savedBaseSystemId = baseSystemId;
        if (externalIds[2] != null) {
            baseSystemId = externalIds[2];
        }
        char[] fetched;
        try {
            fetched = fetchExternalResource("%" + name, "parameter entity \"" + name + "\"",
                    externalIds[0], externalIds[1]);
        } finally {
            baseSystemId = savedBaseSystemId;
        }
        return XmlDeclUtil.stripXmlDeclaration(fetched, handler);
    }

    // ===== Parameter entity references inside a single markup declaration =====
    //
    // A PE reference BETWEEN declarations (handled above by {@link
    // #expandParameterEntityReference}) is comparatively easy: its
    // replacement text is a self-contained sequence of complete
    // declarations, fully drained via a nested, ordinary recursive {@link
    // #parseMarkupDeclSeq} call before control returns to the outer one. A
    // PE reference INSIDE a declaration's own syntax - possibly providing
    // only a fragment of a token, or several tokens at once, anywhere from
    // right after the declaration's own keyword onward - cannot be handled
    // that way, because there is no self-contained "declaration" to
    // recursively parse; the declaration's own syntax continues right on
    // from wherever the reference happened to fall, mid-token or not, and
    // that continuation has to come from whichever buffer holds it next
    // (the entity's replacement text, then back to the original source).
    //
    // Since a declaration is always parsed from an already-fully-buffered
    // span (this class's "atomic construct" discipline - see {@link
    // #scanAttlistDeclaration} and friends), the whole entity's replacement
    // text can simply be spliced into {@link #buf} in place of the "%name;"
    // text, in memory, via {@link #spliceIntoBuf} - after which every
    // existing character-by-character scanning loop in this class continues
    // reading buf[pos]/buf[p] exactly as before, completely unaware that
    // anything happened; no new "which buffer am I in" bookkeeping is
    // needed anywhere else. A further nested PE reference inside the
    // spliced text is handled by the very same mechanism, with no explicit
    // recursion: splicing leaves the scan position sitting at the start of
    // the newly-inserted text, so the caller's own loop re-examines it and
    // finds - and splices - the nested reference on its own.

    /**
     * Splices {@code replacement} into {@link #buf} in place of {@code
     * buf[start, end)}, growing {@link #buf} (and adjusting {@link #limit})
     * if needed. Returns {@code start} - the position the replacement's own
     * first character now occupies - purely as a convenience for callers
     * that want to thread it back into their own cursor variable; {@link
     * #pos} itself is not touched here (some callers splice ahead of {@link
     * #pos}, at a local cursor of their own).
     */
    private int spliceIntoBuf(int start, int end, String replacement) {
        int oldSpan = end - start;
        int newSpan = replacement.length();
        int delta = newSpan - oldSpan;
        if (delta > 0 && limit + delta > buf.length) {
            buf = Arrays.copyOf(buf, Math.max(buf.length * 2, limit + delta));
        }
        System.arraycopy(buf, end, buf, start + newSpan, limit - end);
        replacement.getChars(0, newSpan, buf, start);
        limit += delta;
        // See lastSpliceEnd's own Javadoc.
        lastSpliceEnd = start + newSpan;
        sawSpliceSinceDeclarationStart = true;
        return start;
    }

    /**
     * Resolves and splices the parameter entity reference at {@code
     * buf[p]} (which must be {@code '%'}) into {@link #buf}, enlarged by a
     * leading and trailing space per XML 4.4.8's "Included as PE" rule. A
     * bare {@code '%'} is never legal, literal content anywhere within
     * markup declaration syntax (only within a quoted literal - handled by
     * {@link #scanQuotedLiteralWithCharRefs}'s own use of {@link
     * #resolveParameterEntityReferenceAt} instead, whose "Included in
     * Literal" rule is different: no padding, and the resolved text is
     * appended straight to a {@code StringBuilder} rather than spliced
     * into {@code buf}), so this either splices successfully or throws -
     * the sole non-throwing exception is genuine underflow (the
     * reference's own {@code Name}/{@code ';'} is not yet fully buffered),
     * signalled by returning {@link #limit} so every existing {@code "if (p
     * >= limit) return -1;"} check downstream already handles it correctly
     * with no changes of its own. Throws WFC "PEs in Internal Subset"
     * outright unless {@link #parsingExternalContent}.
     */
    private int splicePEReferenceAt(int p, HashMap<String, String> pendingParamEntities,
            HashMap<String, String[]> pendingParamExternalNames) throws SAXException {
        return splicePEReferenceAt(p, pendingParamEntities, pendingParamExternalNames, false);
    }

    /** {@code checkParenBalance}: true only for the element-content-model
     *  paren-depth-counting call site - see {@link #checkPEReplacementParenBalance}. */
    private int splicePEReferenceAt(int p, HashMap<String, String> pendingParamEntities,
            HashMap<String, String[]> pendingParamExternalNames, boolean checkParenBalance) throws SAXException {
        char[] replacementChars = resolveParameterEntityReferenceAt(p, pendingParamEntities,
                pendingParamExternalNames);
        if (replacementChars == null) {
            return limit;
        }
        if (checkParenBalance) {
            checkPEReplacementParenBalance(replacementChars);
        }
        String replacement = " " + new String(replacementChars) + " ";
        return spliceIntoBuf(p, lastPEReferenceEnd, replacement);
    }

    /** VC "Proper Group/PE Nesting" (Section 3.2.1): "if either of the
     *  opening or closing parentheses of a choice, seq, or Mixed construct
     *  is contained in the replacement text for a parameter entity, both
     *  must be contained in the same replacement text" - i.e. a single PE's
     *  own replacement text must be internally paren-balanced (and never
     *  dip below zero, ruling out a stray close before its own open within
     *  the same text), since {@link #splicePEReferenceAt}'s in-place
     *  splicing otherwise flattens PE boundaries away entirely (the caller's
     *  own paren-depth count, resumed transparently after the splice,
     *  cannot tell a legitimately-nested "(" from one that only balances
     *  because a sibling PE's replacement text happened to supply the
     *  matching ")"). Checked once, against the replacement text alone, at
     *  the single call site (the element-content-model span-finding loop in
     *  {@link #scanElementDeclaration}) where an unbalanced splice could
     *  silently produce a syntactically-valid-looking but VC-violating
     *  content model. */
    private void checkPEReplacementParenBalance(char[] replacementChars) throws SAXException {
        int depth = 0;
        for (char c : replacementChars) {
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth < 0) {
                    break;
                }
            }
        }
        if (depth != 0) {
            handler.error("Validity Constraint: Proper Group/PE Nesting (Section 3.2.1). "
                    + "A parameter entity's replacement text must contain both parentheses of any "
                    + "choice/seq/Mixed group it contributes one of.");
        }
    }

    /** The absolute end position (exclusive) of the most recent {@link
     *  #spliceIntoBuf} call's own inserted text - i.e. everything in {@code
     *  [start, lastSpliceEnd)} right after that splice came from a
     *  parameter entity's replacement text (plus the "Included as PE"
     *  padding spaces), not literal source. Stays valid (monotonically
     *  increasing positions, never retroactively invalidated) as long as no
     *  further splice happens - each subsequent splice only ever occurs at
     *  or after the current parse position, which by construction is
     *  already {@code >=} wherever the previous splice ended, so it never
     *  shifts anything at or before that point. Combined with {@link
     *  #sawSpliceSinceDeclarationStart} (which guards against a stale value
     *  surviving from an unrelated, already-finished declaration - {@link
     *  #buf} is a self-compacting ring, per this class's own Javadoc, so
     *  raw positions alone are not reliably comparable across a
     *  compaction) by {@link #checkNotFromPESplice}. */
    private int lastSpliceEnd = -1;

    /** Reset to false at the start of each markup declaration
     *  (scanEntityDeclaration/scanElementDeclaration/scanAttlistDeclaration/
     *  scanNotationDeclaration) and each conditional section
     *  (parseConditionalSection), set true by {@link #spliceIntoBuf} - see
     *  {@link #lastSpliceEnd}'s own Javadoc for why both together, not
     *  {@link #lastSpliceEnd} alone, are needed to safely detect "did the
     *  construct currently being parsed splice in a parameter entity
     *  anywhere within itself." */
    private boolean sawSpliceSinceDeclarationStart;

    /** VC "Proper Declaration/PE Nesting" (Section 3.2.1, generalized by VC
     *  "Proper Conditional Section/PE Nesting" for {@code <![...]]>} too):
     *  "if either the first or last character of a markup declaration
     *  (respectively, of the {@code <![}/{@code ]}/{@code ]]>} delimiters
     *  of a conditional section) is contained in the replacement text for a
     *  parameter entity, both must be." The construct's own opening
     *  delimiter is - by construction of how {@link #scanMarkup}/{@link
     *  #parseConditionalSection} dispatch (only ever reached via a literal
     *  keyword match in {@link #buf}, never itself produced by a splice) -
     *  always literal, so this need only check the <em>closing</em>
     *  delimiter's own last character, found at {@code p}: if a splice
     *  happened anywhere within this construct's own parse and {@code p}
     *  still falls inside that splice's own contributed span, the closing
     *  delimiter came from a parameter entity while the opening one did
     *  not - straddling the boundary. */
    private void checkNotFromPESplice(int p, String what) throws SAXException {
        if (sawSpliceSinceDeclarationStart && p < lastSpliceEnd) {
            handler.error("Validity Constraint: Proper Declaration/PE Nesting (Section 3.2.1). "
                    + "The closing delimiter of " + what
                    + " must be contained in the same parameter entity replacement text as its opening "
                    + "delimiter (here, the opening delimiter is literal, not from a parameter entity).");
        }
    }

    /** Set by {@link #resolveParameterEntityReferenceAt} - the position
     *  past the resolved reference's own trailing {@code ';'}, for a
     *  caller that needs to advance its own cursor past it without
     *  re-deriving that position itself. */
    private int lastPEReferenceEnd;

    /**
     * Validates the parameter entity reference at {@code buf[p]} (which
     * must be {@code '%'}) and resolves it - without touching {@link #buf}
     * at all, unlike {@link #splicePEReferenceAt} - leaving {@link
     * #lastPEReferenceEnd} pointing past its trailing {@code ';'}. Returns
     * the resolved replacement text, or {@code null} on genuine underflow
     * (the reference's own {@code Name}/{@code ';'} not yet fully
     * buffered) - {@link #resolveParameterEntityReplacement} itself never
     * returns {@code null}, so this is an unambiguous signal. Throws WFC
     * "PEs in Internal Subset" outright unless {@link
     * #parsingExternalContent}, same as {@link #splicePEReferenceAt}.
     */
    private char[] resolveParameterEntityReferenceAt(int p, HashMap<String, String> pendingParamEntities,
            HashMap<String, String[]> pendingParamExternalNames) throws SAXException {
        int nameStart = p + 1;
        int q = nameStart;
        while (q < limit && isNameChar(buf[q])) {
            q++;
        }
        if (q >= limit) {
            return null;
        }
        if (q == nameStart || buf[q] != ';') {
            throw handler.fatalError("Malformed parameter entity reference");
        }
        checkNameStartChar(nameStart);
        if (!parsingExternalContent) {
            throw handler.fatalError("Well-Formedness Constraint: PEs in Internal Subset (Section 2.8). "
                    + "A parameter entity reference may not occur within a markup declaration "
                    + "in the internal DTD subset.");
        }
        String name = new String(buf, nameStart, q - nameStart);
        char[] replacementChars = resolveParameterEntityReplacement(name, pendingParamEntities,
                pendingParamExternalNames);
        checkEntityExpansionLimit();
        lastPEReferenceEnd = q + 1;
        return replacementChars;
    }

    /** {@link #skipOptionalWhitespace}, plus - only when {@link
     *  #parsingExternalContent} - transparently splicing (see {@link
     *  #splicePEReferenceAt}) any parameter entity reference found at a
     *  token boundary, then continuing to skip whitespace/further
     *  references from there. Used by {@link #scanAttlistDeclaration}/
     *  {@link #scanElementDeclaration}/{@link #scanEntityDeclaration}/
     *  {@link #scanNotationDeclaration} in place of the plain {@link
     *  #skipOptionalWhitespace} everywhere they currently call it, so a PE
     *  reference sitting at any "S required/optional here" point in their
     *  own grammar - including one glued directly onto a keyword with no
     *  real whitespace at all, since the replacement text's own mandatory
     *  leading space satisfies the "S required" check right after it is
     *  spliced in - is handled with no other change to their logic. */
    private int skipWhitespaceInDeclaration(int p, HashMap<String, String> pendingParamEntities,
            HashMap<String, String[]> pendingParamExternalNames) throws SAXException {
        return skipWhitespaceInDeclaration(p, pendingParamEntities, pendingParamExternalNames, false);
    }

    /** {@code checkParenBalance}: true only for {@link
     *  #scanElementDeclaration}'s own two calls (right before a content
     *  model is expected to begin) - see {@link
     *  #checkPEReplacementParenBalance}. A PE reference can supply a content
     *  model's opening parenthesis itself (not just parens found once
     *  already inside one - the other, more common case {@link
     *  #scanElementDeclaration}'s content-model span-finding loop already
     *  covers with its own {@code checkParenBalance} splice), so this call
     *  site needs the same check too. */
    private int skipWhitespaceInDeclaration(int p, HashMap<String, String> pendingParamEntities,
            HashMap<String, String[]> pendingParamExternalNames, boolean checkParenBalance) throws SAXException {
        while (true) {
            p = skipOptionalWhitespace(p);
            if (p >= limit || buf[p] != '%') {
                return p;
            }
            p = splicePEReferenceAt(p, pendingParamEntities, pendingParamExternalNames, checkParenBalance);
            if (p >= limit) {
                return p;
            }
        }
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
            if (wasAttributeSeen(name)) {
                continue;
            }
            if (validationEnabled && def.mode == DTDModel.Mode.REQUIRED) {
                handler.error("Validity Constraint: Required Attribute (Section 3.3.2). Attribute \"" + name
                        + "\" is required on element \"" + elementName + "\" but was not specified.");
            }
            if (def.defaultValue == null) {
                continue;
            }
            if (validationEnabled && standalone && def.declaredExternally) {
                handler.error("Validity Constraint: Standalone Document Declaration (Section 2.9). "
                        + "Document has standalone=\"yes\" but attribute \"" + name + "\" of element \""
                        + elementName + "\" has a default value declared in external markup, "
                        + "and was not specified.");
            }
            handler.startAttribute(name, def.type, true, false);
            handler.attributeValueContent(CharBuffer.wrap(def.defaultValue), true);
        }
    }

    // ===== Runtime attribute VC checks =====
    //
    // Reached only for non-CDATA types (see emitAttributeValueContent),
    // only when validationEnabled, with the attribute's full final value
    // already in hand (normalizeBuilder's collapse already materializes it
    // for exactly these types - nothing extra to buffer).

    /** ID uniqueness tracking (VC "ID", Section 3.3.1) - every ID-typed
     *  attribute value seen, document-wide. Lazily allocated (validation-
     *  only). */
    private HashSet<String> declaredIds;

    /** IDREF/IDREFS values seen, document-wide, checked against {@link
     *  #declaredIds} once the whole document is known (VC "IDREF", Section
     *  3.3.1 - forward references are legal, so this can't be checked
     *  incrementally). Lazily allocated. */
    private ArrayList<String> pendingIdrefs;

    private void checkAttributeValueVCs(String elementName, String attrName, String type, String value)
            throws SAXException {
        switch (type) {
            case "ID":
                checkNameProduction(attrName, value, "ID");
                checkNoColon(attrName, value, "ID");
                if (declaredIds == null) {
                    declaredIds = new HashSet<String>();
                }
                if (!declaredIds.add(value)) {
                    handler.error("Validity Constraint: ID (Section 3.3.1). ID value \"" + value
                            + "\" appears more than once in the document.");
                }
                break;
            case "IDREF":
                checkNameProduction(attrName, value, "IDREF");
                checkNoColon(attrName, value, "IDREF");
                recordPendingIdref(value);
                break;
            case "IDREFS": {
                List<String> tokens = splitTokens(value);
                if (tokens.isEmpty()) {
                    reportBadAttributeValueFormat(attrName, value, "IDREFS", "IDREFS");
                }
                for (String token : tokens) {
                    checkNameProduction(attrName, token, "IDREFS");
                    checkNoColon(attrName, token, "IDREFS");
                    recordPendingIdref(token);
                }
                break;
            }
            case "NMTOKEN":
                checkNmtokenProduction(attrName, value, "NMTOKEN");
                break;
            case "NMTOKENS": {
                List<String> tokens = splitTokens(value);
                if (tokens.isEmpty()) {
                    reportBadAttributeValueFormat(attrName, value, "NMTOKENS", "NMTOKENS");
                }
                for (String token : tokens) {
                    checkNmtokenProduction(attrName, token, "NMTOKENS");
                }
                break;
            }
            case "ENUMERATION":
            case "NOTATION":
                checkEnumerationMembership(elementName, attrName, type, value);
                break;
            case "ENTITY":
                checkNameProduction(attrName, value, "ENTITY");
                checkUnparsedEntityName(attrName, value);
                break;
            case "ENTITIES":
                for (String token : splitTokens(value)) {
                    checkNameProduction(attrName, token, "ENTITIES");
                    checkUnparsedEntityName(attrName, token);
                }
                break;
            default:
                // CDATA (and anything else unrecognized): no type-specific
                // value-format VC applies, but checkFixedValue below still
                // does - a #FIXED default is legal on any type, including
                // CDATA.
                break;
        }
        checkFixedValue(elementName, attrName, value);
    }

    /** VC "Entity Name" (Section 3.3.1): an {@code ENTITY}/{@code ENTITIES}-
     *  typed attribute's value(s) must each name a declared <em>unparsed</em>
     *  entity - a general entity declared with an {@code NDATA} annotation
     *  (see {@link #externalEntityNames}'s third array element, populated
     *  from {@link #scanEntityDeclaration}'s own {@code ndataName} capture).
     *  A general entity that exists but is ordinary (parsed, or internal)
     *  fails this the same as one that doesn't exist at all - only the
     *  unparsed ones are legal {@code ENTITY}/{@code ENTITIES} values. */
    private void checkUnparsedEntityName(String attrName, String value) throws SAXException {
        String[] ids = externalEntityNames.get(value);
        if (ids == null || ids[2] == null) {
            handler.error("Validity Constraint: Entity Name (Section 3.3.1). Value \"" + value + "\" of attribute \""
                    + attrName + "\" does not name a declared unparsed entity.");
        }
    }

    private void recordPendingIdref(String value) {
        if (pendingIdrefs == null) {
            pendingIdrefs = new ArrayList<String>();
        }
        pendingIdrefs.add(value);
    }

    /** Splits an already-collapsed (single-space-separated, trimmed) IDREFS/
     *  NMTOKENS value into its individual tokens. */
    private static List<String> splitTokens(String value) {
        if (value.isEmpty()) {
            return java.util.Collections.emptyList();
        }
        ArrayList<String> tokens = new ArrayList<String>();
        int start = 0;
        for (int i = 0; i <= value.length(); i++) {
            if (i == value.length() || value.charAt(i) == ' ') {
                if (i > start) {
                    tokens.add(value.substring(start, i));
                }
                start = i + 1;
            }
        }
        return tokens;
    }

    private static boolean matchesNameProduction(String value) {
        if (value.isEmpty() || !isNameStartCharSlow(value.charAt(0))) {
            return false;
        }
        for (int i = 1; i < value.length(); i++) {
            if (!isNameCharSlow(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean matchesNmtokenProduction(String value) {
        if (value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (!isNameCharSlow(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private void checkNameProduction(String attrName, String value, String typeLabel) throws SAXException {
        if (!matchesNameProduction(value)) {
            reportBadAttributeValueFormat(attrName, value, typeLabel, "Name");
        }
    }

    private void checkNmtokenProduction(String attrName, String value, String typeLabel) throws SAXException {
        if (!matchesNmtokenProduction(value)) {
            reportBadAttributeValueFormat(attrName, value, typeLabel, "Nmtoken");
        }
    }

    /** Namespaces in XML restricts ID/IDREF values (each a {@code Name})
     *  from containing a colon, mirroring the old pipeline's {@code
     *  AttributeValidator.validateId}/{@code validateIdref} - applied
     *  unconditionally (whenever validation is on), not gated on namespace
     *  processing actually being enabled, matching that established
     *  behavior exactly. */
    private void checkNoColon(String attrName, String value, String typeLabel) throws SAXException {
        if (value.indexOf(':') >= 0) {
            handler.error("Validity Constraint: " + typeLabel + " Attribute (Section 3.3.1). Value \"" + value
                    + "\" of attribute \"" + attrName + "\" must not contain a colon (Namespaces in XML).");
        }
    }

    /** WFC (Namespaces in XML): an entity or notation name must not contain
     *  a colon when namespace processing is enabled. Called from
     *  both {@link #scanEntityDeclaration} (general and parameter alike,
     *  matching that method's own two identical call sites) and {@link
     *  #scanNotationDeclaration}. Unlike {@link #checkNoColon}, this is a
     *  fatal error, not a recoverable one. */
    private void checkNoColonInNamespaceMode(String name, String what) throws SAXException {
        if (namespaceAware && name.indexOf(':') >= 0) {
            throw handler.fatalError(
                    "WFC (Namespaces in XML): " + what + " name \"" + name + "\" must not contain a colon");
        }
    }

    private void reportBadAttributeValueFormat(String attrName, String value, String typeLabel, String production)
            throws SAXException {
        handler.error("Validity Constraint: Attribute Value Type (Section 3.3.1). Value \"" + value + "\" of "
                + typeLabel + " attribute \"" + attrName + "\" does not match the " + production + " production.");
    }

    private void checkEnumerationMembership(String elementName, String attrName, String type, String value)
            throws SAXException {
        DTDModel.AttDef def = attributeDefOf(elementName, attrName);
        if (def == null || def.enumeration == null || def.enumeration.contains(value)) {
            return;
        }
        handler.error("Validity Constraint: " + ("NOTATION".equals(type) ? "Notation Attributes" : "Enumeration")
                + " (Section 3.3.1). Value \"" + value + "\" of attribute \"" + attrName
                + "\" is not one of the declared values for element \"" + elementName + "\".");
    }

    /** VC "Attribute Default Legal" (Section 3.3.2): a declared default
     *  value must itself meet its own declared type's lexical constraints -
     *  checked once per {@code <!ATTLIST>} declaration, independent of
     *  whether any element instance ever actually uses the default (unlike
     *  {@link #checkAttributeValueVCs}, which checks a value actually
     *  written into the document). Called from {@link
     *  #resolveAttlistDefaultsAgainstEntities}'s caller once every default
     *  has been entity-resolved. ID-typed attributes are excluded: VC "ID
     *  Attribute Default" (checked at declaration time by {@link
     *  #checkAttlistDeclarationVCs}) already forbids them from having a
     *  default at all. */
    private void checkAttlistDefaultsLegal() throws SAXException {
        for (Map.Entry<String, LinkedHashMap<String, DTDModel.AttDef>> elemEntry : dtdModel.allAttlists()
                .entrySet()) {
            String elementName = elemEntry.getKey();
            for (Map.Entry<String, DTDModel.AttDef> attrEntry : elemEntry.getValue().entrySet()) {
                String attrName = attrEntry.getKey();
                DTDModel.AttDef def = attrEntry.getValue();
                if (def.defaultValue == null) {
                    continue;
                }
                checkAttributeDefaultLegal(elementName, attrName, def);
            }
        }
    }

    private void checkAttributeDefaultLegal(String elementName, String attrName, DTDModel.AttDef def)
            throws SAXException {
        String value = def.defaultValue;
        switch (def.type) {
            case "IDREF":
            case "ENTITY":
                if (!matchesNameProduction(value)) {
                    reportBadDefaultFormat(attrName, value, "Name");
                }
                break;
            case "IDREFS":
            case "ENTITIES":
                for (String token : splitTokens(value)) {
                    if (!matchesNameProduction(token)) {
                        reportBadDefaultFormat(attrName, value, "Names");
                    }
                }
                break;
            case "NMTOKEN":
                if (!matchesNmtokenProduction(value)) {
                    reportBadDefaultFormat(attrName, value, "Nmtoken");
                }
                break;
            case "NMTOKENS":
                for (String token : splitTokens(value)) {
                    if (!matchesNmtokenProduction(token)) {
                        reportBadDefaultFormat(attrName, value, "Nmtokens");
                    }
                }
                break;
            case "ENUMERATION":
            case "NOTATION":
                if (def.enumeration != null && !def.enumeration.contains(value)) {
                    handler.error("Validity Constraint: Attribute Default Legal (Section 3.3.2). Default value \""
                            + value + "\" of attribute \"" + attrName
                            + "\" is not one of its declared enumerated values.");
                }
                break;
            default:
                // CDATA/ID have no further lexical constraint here.
                break;
        }
    }

    private void reportBadDefaultFormat(String attrName, String value, String production) throws SAXException {
        handler.error("Validity Constraint: Attribute Default Legal (Section 3.3.2). Default value \"" + value
                + "\" of attribute \"" + attrName + "\" does not match the " + production + " production.");
    }

    private void checkFixedValue(String elementName, String attrName, String value) throws SAXException {
        DTDModel.AttDef def = attributeDefOf(elementName, attrName);
        if (def != null && def.mode == DTDModel.Mode.FIXED && !value.equals(def.defaultValue)) {
            handler.error("Validity Constraint: Fixed Attribute Default (Section 3.3.2). Value \"" + value
                    + "\" of attribute \"" + attrName + "\" does not match its declared #FIXED value \""
                    + def.defaultValue + "\".");
        }
    }

    private DTDModel.AttDef attributeDefOf(String elementName, String attrName) {
        Map<String, DTDModel.AttDef> attrs = dtdModel.getAttributes(elementName);
        return attrs == null ? null : attrs.get(attrName);
    }

    /** Checked from {@link #close()} once the whole document is known - VC
     *  "IDREF" (Section 3.3.1): every IDREF/IDREFS value must match the
     *  value of some ID attribute somewhere in the document (forward
     *  references are legal, so this can only be checked at the end). */
    private void checkPendingIdrefs() throws SAXException {
        if (pendingIdrefs == null) {
            return;
        }
        for (String value : pendingIdrefs) {
            if (declaredIds == null || !declaredIds.contains(value)) {
                handler.error("Validity Constraint: IDREF (Section 3.3.1). IDREF value \"" + value
                        + "\" does not match the value of any ID attribute in the document.");
            }
        }
    }

}

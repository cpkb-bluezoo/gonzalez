/*
 * XMLHandler.java
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
import org.xml.sax.Locator;
import org.xml.sax.SAXException;

/**
 * Internal structural event vocabulary that {@link Scanner} emits, adapted
 * to standard SAX2 by {@link SAXAdapter} (optionally preceded by {@link
 * NamespaceFilter}).
 * <p>
 * This deliberately differs from {@link org.xml.sax.ContentHandler} in ways
 * that let it stay cheap for consumers that don't need namespace resolution
 * or attribute random access:
 * <ul>
 * <li>{@link #startElement(String)} takes the raw qName only; namespace
 * resolution is not forced on every consumer.</li>
 * <li>Attribute values arrive as a stream of {@link #attributeValueContent}
 * calls bracketed by {@link #startAttribute(String, String)}, rather than as a single
 * {@code Attributes} collection - no "little DOM" of attribute state is built
 * unless a consumer chooses to buffer one. See below for why this is
 * streamed rather than assembled by the scanner.</li>
 * <li>{@link #endAttributes()} is explicit, fired for every element
 * (including empty elements) immediately after the last attribute (or
 * immediately after {@link #startElement(String)} if there are none). This
 * gives every downstream stage - namespace resolution, DTD attribute
 * defaulting - a single, unambiguous "attributes are complete" signal
 * instead of requiring each to infer it.</li>
 * <li>{@code xmlns}/{@code xmlns:prefix} is always reported as a regular
 * attribute first (it genuinely is one in namespace-unaware processing); a
 * namespace-aware pipeline stage reroutes it to {@link #namespace(String,
 * String)} instead. There is no corresponding end event - scope closes
 * implicitly on the next {@link #endElement()} at the same depth.</li>
 * <li>{@link #endElement()} takes no arguments. Well-formedness element-type
 * matching is still mandatory and happens internally to whatever component
 * emits these events, using a lightweight name representation - it does not
 * require exposing a resolved identity through this interface. A consumer
 * that needs to know which element is ending (for example to fire SAX's
 * {@code endElement(uri, localName, qName)}) maintains its own stack keyed
 * off the {@link #startElement(String)}/{@link #endAttributes()} pair it
 * already received, popped 1:1 per {@link #endElement()} call.</li>
 * <li>{@link #piData} arrives as a stream bracketed by {@link #piTarget},
 * the same shape {@link #attributeValueContent}/{@link #startAttribute} use
 * - a processing instruction's data is scanned as a single opaque run, so it
 * gets the same treatment as an attribute value rather than being buffered
 * whole before reporting (see below).</li>
 * <li>Text content ({@link #attributeValueContent}, {@link #characters},
 * {@link #piData}, {@link #comment(CharBuffer)}) is delivered as {@link
 * CharBuffer} throughout, converted to {@code char[]}/offset/length only at
 * the eventual SAX boundary, so native consumers can work with buffers
 * directly.</li>
 * </ul>
 * <p>
 * <b>Streaming content, attribute values, and PI data, with an explicit
 * "end" flag.</b> The scanner's job is to identify contiguous runs and push
 * them downstream as fast as possible - it never assembles a complete
 * value/run into its own buffer before emitting (no allocation for that
 * purpose at all). A single logical run (text between two tags, one
 * attribute's value, or one processing instruction's data) may therefore
 * arrive as one call or several, exactly as SAX's own {@code characters()}
 * already tolerates being split. {@link #characters(CharBuffer, boolean,
 * boolean)}, {@link #attributeValueContent}, and {@link #piData} all carry
 * an explicit {@code end} flag - true on the call that completes the run -
 * rather than requiring a downstream consumer to infer completion from the
 * next event's type. This matters because it lets a consumer that needs to
 * coalesce chunks into one value (a DOM builder, the SAX adapter assembling
 * an {@code Attributes} value) take a zero-allocation fast path in the
 * common case: if the very first call for a run already has {@code
 * end=true}, there is nothing to accumulate - it can be handed straight
 * through. Only the rarer multi-chunk case (a run interrupted by an entity
 * reference, or split across a {@link #saveBuffers()} boundary) needs to
 * buffer. The scanner can only assert {@code end=true} once it has actually
 * confirmed completion (seen the terminator - the closing quote, markup's
 * {@code '<'}, or a PI's {@code "?>"}) within currently-available data; if a
 * {@code receive()} call runs out of buffer before the terminator is
 * visible, the last chunk goes out with {@code end=false}, and if that
 * chunk later turns out to have in fact been the last one, the scanner
 * follows up with an empty {@code end=true} call purely to close out the
 * sequence (mirroring an HTTP/2 DATA frame's empty closing frame with
 * END_STREAM set) - a rare edge case that costs one extra call only when a
 * buffer boundary happens to land exactly on the terminator. {@link
 * #piData} alone always fires at least this one closing call even when a PI
 * has no data at all, since - unlike {@link #attributeValueContent}, which
 * has {@link #endAttributes()} as an unambiguous terminator regardless of
 * how many value chunks preceded it - a PI has no separate "end" event of
 * its own to fall back on.
 * <p>
 * <b>Entity references and {@link #saveBuffers()}.</b> Predefined entities
 * ({@code &amp;}, {@code &lt;}, {@code &gt;}, {@code &apos;}, {@code &quot;})
 * decode to an invariant single character, so they are backed by static,
 * shared, read-only buffers that never change and are always safe to
 * reference - no signal needed. Numeric character references
 * ({@code &#65;}, {@code &#x1F600;}) decode to a value that varies per
 * occurrence, so they reuse one small buffer allocated once per parser
 * instance; {@link #saveBuffers()} fires immediately before each write into
 * it, telling any consumer holding an unflushed reference from a prior
 * numeric-reference event that it must copy the data now, before this event,
 * because the storage is about to change. {@link #saveBuffers()} also fires
 * once at the end of processing each chunk given to the driver, before its
 * scan buffer is compacted/reused for the next chunk - the same signal,
 * different trigger: "anything you are holding a reference to from any
 * prior event, copy it now." A consumer that never defers copying (always
 * acts on a buffer synchronously within the call that delivered it) can
 * ignore this event entirely.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
interface XMLHandler {

    /**
     * Sets the document locator for reporting position information.
     * Called before any other event, mirroring {@link TokenConsumer#setLocator}.
     *
     * @param locator the document locator
     */
    void setLocator(Locator locator);

    /**
     * Signals the start of the document.
     */
    void startDocument() throws SAXException;

    /**
     * Signals the end of the document.
     */
    void endDocument() throws SAXException;

    /**
     * Signals the start of an element. Followed by zero or more
     * {@link #namespace(String, String)}/{@link #startAttribute(String, String)}
     * (each followed by one or more {@link #attributeValueContent}) calls,
     * then exactly one {@link #endAttributes()} call.
     *
     * @param qName the element's raw qualified name (may contain a prefix)
     */
    void startElement(String qName) throws SAXException;

    /**
     * Reports a namespace declaration ({@code xmlns} or {@code xmlns:prefix}
     * on the element currently being started). Only fired by namespace-aware
     * pipeline stages; in namespace-unaware processing the same declaration
     * arrives via {@link #startAttribute}/{@link #attributeValueContent}
     * instead. There is no corresponding end event - the scope this declares
     * closes implicitly on the next matching {@link #endElement()}.
     *
     * @param prefix the declared prefix (empty string for the default namespace)
     * @param uri the namespace URI (empty string to undeclare)
     */
    void namespace(String prefix, String uri) throws SAXException;

    /**
     * Signals the start of a single attribute on the element currently being
     * started. Followed by one or more {@link #attributeValueContent} calls
     * for its value, the last of which has {@code end=true}.
     * {@code xmlns}/{@code xmlns:prefix} attributes are reported here too
     * (see {@link #namespace(String, String)}).
     *
     * @param name the attribute's raw name (may contain a prefix)
     * @param type the attribute's declared type - the SAX2 convention
     *             ({@code "CDATA"}, {@code "ID"}, {@code "IDREF"}, {@code
     *             "IDREFS"}, {@code "ENTITY"}, {@code "ENTITIES"}, {@code
     *             "NMTOKEN"}, {@code "NMTOKENS"}, {@code "NOTATION"}, or
     *             {@code "ENUMERATION"} for a bare enumeration); always
     *             {@code "CDATA"} if there is no DTD, or none declares this
     *             attribute
     */
    void startAttribute(String name, String type) throws SAXException;

    /**
     * Reports a chunk of the current attribute's value (see class Javadoc
     * for the streaming/{@code end} semantics).
     *
     * @param value the value chunk; valid only for the duration of this call
     *              unless retained per {@link #saveBuffers()}'s contract
     * @param end true if this chunk completes the attribute's value
     */
    void attributeValueContent(CharBuffer value, boolean end) throws SAXException;

    /**
     * Signals that all attributes (and namespace declarations) for the
     * element currently being started have been reported. Always fired
     * exactly once per {@link #startElement(String)}, even when there were
     * no attributes.
     */
    void endAttributes() throws SAXException;

    /**
     * Reports a chunk of character data (see class Javadoc for the
     * streaming/{@code end} semantics).
     *
     * @param text the character data chunk; valid only for the duration of
     *             this call unless retained per {@link #saveBuffers()}'s contract
     * @param ignorable true if this is whitespace-only character data that a
     *             DTD's content model declares insignificant (an element
     *             declared with element-only content, {@code <!ELEMENT foo
     *             (bar,baz)>} - not mixed or {@code ANY} content), to be
     *             routed to SAX's {@code ignorableWhitespace} rather than
     *             {@code characters} - see {@link SAXAdapter}. Only ever true
     *             when an internal DTD subset has declared the current
     *             element's content type; a document with no DTD, or one
     *             whose current element allows mixed/{@code ANY} content,
     *             always reports whitespace with {@code ignorable=false}
     *             instead (content coming from a general entity reference is
     *             never reported as ignorable, even if whitespace-only, to
     *             keep that determination simple). Fixed for the whole run:
     *             a caller must not change it between chunks of the same run
     *             (the run's whitespace-only-ness is determined once, before
     *             the first chunk is reported - see {@link Scanner}).
     * @param end true if this chunk completes the current run of character data
     */
    void characters(CharBuffer text, boolean ignorable, boolean end) throws SAXException;

    /**
     * Signals the end of the most recently started, still-open element.
     */
    void endElement() throws SAXException;

    /**
     * Reports a comment.
     *
     * @param text the comment text; valid only for the duration of this call
     */
    void comment(CharBuffer text) throws SAXException;

    /**
     * Signals the start of a processing instruction, reporting its target.
     * Followed by one or more {@link #piData} calls for its data (mirroring
     * {@link #startAttribute}/{@link #attributeValueContent}'s streaming
     * shape - a PI's data, like an attribute value, is scanned as a single
     * opaque run with no internal markup) - unlike an attribute value,
     * though, at least one {@code piData} call always follows, even for a
     * data-less PI ({@code <?target?>}), since there is no separate "end of
     * PI" event; {@code piData}'s own {@code end=true} is the only signal
     * that this processing instruction is complete.
     *
     * @param target the PI target
     */
    void piTarget(String target) throws SAXException;

    /**
     * Reports a chunk of the current processing instruction's data (see
     * {@link #piTarget}).
     *
     * @param data the data chunk; valid only for the duration of this call
     *             unless retained per {@link #saveBuffers()}'s contract
     * @param end true if this chunk completes the PI's data
     */
    void piData(CharBuffer data, boolean end) throws SAXException;

    /**
     * Signals that any buffer-backed data delivered by a prior event and not
     * yet copied must be copied now - see class Javadoc for when and why
     * this fires. A consumer that never defers copying may ignore it.
     */
    void saveBuffers() throws SAXException;

    /**
     * Reports a fatal error, mirroring {@link TokenConsumer#fatalError}.
     *
     * @param message the error message
     * @return the SAXException to throw
     */
    SAXException fatalError(String message) throws SAXException;

    /**
     * Reports a recoverable validity constraint (VC) violation - unlike
     * {@link #fatalError}, this does not stop parsing; the caller continues
     * immediately after reporting. Mirrors {@code ContentParser.
     * reportValidationError}'s SAX contract: routes to {@code
     * ErrorHandler.error(SAXParseException)}, not {@code fatalError}, since
     * a VC violation (unlike a WFC violation) means the document is not
     * valid but may still be well-formed. Only ever called when validation
     * is actually enabled - see {@code Scanner}'s own validation-enabled
     * flag.
     *
     * @param message the error message
     */
    void error(String message) throws SAXException;

}

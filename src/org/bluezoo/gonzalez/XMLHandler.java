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
 * Internal structural event vocabulary for the async scanning pipeline.
 * See ASYNC-PIPELINE.md for the design rationale.
 * <p>
 * This deliberately differs from {@link org.xml.sax.ContentHandler} in ways
 * that let it stay cheap for consumers that don't need namespace resolution
 * or attribute random access:
 * <ul>
 * <li>{@link #startElement(String)} takes the raw qName only; namespace
 * resolution is not forced on every consumer.</li>
 * <li>Attribute values arrive as a stream of {@link #attributeValueContent}
 * calls bracketed by {@link #startAttribute(String)}, rather than as a single
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
 * <li>Text content ({@link #attributeValueContent}, {@link #characters},
 * {@link #comment(CharBuffer)}) is delivered as {@link CharBuffer}
 * throughout, converted to {@code char[]}/offset/length only at the eventual
 * SAX boundary, so native consumers can work with buffers directly.</li>
 * </ul>
 * <p>
 * <b>Streaming content and attribute values, with an explicit "end" flag.</b>
 * The scanner's job is to identify contiguous runs and push them downstream
 * as fast as possible - it never assembles a complete value/run into its own
 * buffer before emitting (no allocation for that purpose at all). A single
 * logical run (text between two tags, or one attribute's value) may
 * therefore arrive as one call or several, exactly as SAX's own
 * {@code characters()} already tolerates being split. Both
 * {@link #characters(CharBuffer, boolean)} and {@link #attributeValueContent}
 * carry an explicit {@code end} flag - true on the call that completes the
 * run - rather than requiring a downstream consumer to infer completion from
 * the next event's type. This matters because it lets a consumer that needs
 * to coalesce chunks into one value (a DOM builder, the SAX adapter
 * assembling an {@code Attributes} value) take a zero-allocation fast path
 * in the common case: if the very first call for a run already has
 * {@code end=true}, there is nothing to accumulate - it can be handed
 * straight through. Only the rarer multi-chunk case (a run interrupted by an
 * entity reference, or split across a {@link #saveBuffers()} boundary) needs
 * to buffer. The scanner can only assert {@code end=true} once it has
 * actually confirmed completion (seen the terminator - the closing quote, or
 * markup's {@code '<'}) within currently-available data; if a
 * {@code receive()} call runs out of buffer before the terminator is
 * visible, the last chunk goes out with {@code end=false}, and if that
 * chunk later turns out to have in fact been the last one, the scanner
 * follows up with an empty {@code end=true} call purely to close out the
 * sequence (mirroring an HTTP/2 DATA frame's empty closing frame with
 * END_STREAM set) - a rare edge case that costs one extra call only when a
 * buffer boundary happens to land exactly on the terminator.
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
     * {@link #namespace(String, String)}/{@link #startAttribute(String)}
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
     */
    void startAttribute(String name) throws SAXException;

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
     * @param end true if this chunk completes the current run of character data
     */
    void characters(CharBuffer text, boolean end) throws SAXException;

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
     * Reports a processing instruction.
     *
     * @param target the PI target
     * @param data the PI data; valid only for the duration of this call, or
     *             null if there was none
     */
    void processingInstruction(String target, CharBuffer data) throws SAXException;

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

}

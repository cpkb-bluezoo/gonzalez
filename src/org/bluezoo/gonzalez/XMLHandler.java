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
 * <li>Attributes arrive one at a time via {@link #attribute(String, CharBuffer)}
 * rather than as a single {@code Attributes} collection - no "little DOM" of
 * attribute state is built unless a consumer chooses to buffer one.</li>
 * <li>{@link #endAttributes()} is explicit, fired for every element
 * (including empty elements) immediately after the last attribute (or
 * immediately after {@link #startElement(String)} if there are none). This
 * gives every downstream stage - namespace resolution, DTD attribute
 * defaulting - a single, unambiguous "attributes are complete" signal
 * instead of requiring each to infer it.</li>
 * <li>{@code xmlns}/{@code xmlns:prefix} is always reported via
 * {@link #attribute(String, CharBuffer)} first (it genuinely is an attribute
 * in namespace-unaware processing); a namespace-aware pipeline stage reroutes
 * it to {@link #namespace(String, String)} instead. There is no
 * corresponding "end namespace" event - scope closes implicitly on the next
 * {@link #endElement()} at the same depth.</li>
 * <li>{@link #endElement()} takes no arguments. Well-formedness element-type
 * matching is still mandatory and happens internally to whatever component
 * emits these events, using a lightweight name representation - it does not
 * require exposing a resolved identity through this interface. A consumer
 * that needs to know which element is ending (for example to fire SAX's
 * {@code endElement(uri, localName, qName)}) maintains its own stack keyed
 * off the {@link #startElement(String)}/{@link #endAttributes()} pair it
 * already received, popped 1:1 per {@link #endElement()} call.</li>
 * <li>Text content ({@link #attribute(String, CharBuffer)} values,
 * {@link #characters(CharBuffer)}, {@link #comment(CharBuffer)}) is delivered
 * as {@link CharBuffer} throughout, converted to {@code char[]}/offset/length
 * only at the eventual SAX boundary, so native consumers can work with
 * buffers directly.</li>
 * </ul>
 * <p>
 * The event set is intentionally minimal for now; further lexical/DTD
 * boundary events (CDATA/DTD boundaries, skipped entities, ignorable
 * whitespace) are added incrementally as later milestones require them,
 * with a gap audit against SAX's full event surface performed once during
 * conformance hardening rather than pinned up front.
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
     * {@link #attribute(String, CharBuffer)} and/or {@link #namespace(String, String)}
     * calls, then exactly one {@link #endAttributes()} call.
     *
     * @param qName the element's raw qualified name (may contain a prefix)
     */
    void startElement(String qName) throws SAXException;

    /**
     * Reports a namespace declaration ({@code xmlns} or {@code xmlns:prefix}
     * on the element currently being started). Only fired by namespace-aware
     * pipeline stages; in namespace-unaware processing the same declaration
     * arrives via {@link #attribute(String, CharBuffer)} instead. There is no
     * corresponding end event - the scope this declares closes implicitly on
     * the next matching {@link #endElement()}.
     *
     * @param prefix the declared prefix (empty string for the default namespace)
     * @param uri the namespace URI (empty string to undeclare)
     */
    void namespace(String prefix, String uri) throws SAXException;

    /**
     * Reports a single attribute on the element currently being started.
     * {@code xmlns}/{@code xmlns:prefix} attributes are reported here too
     * (see {@link #namespace(String, String)}).
     *
     * @param name the attribute's raw name (may contain a prefix)
     * @param value the attribute value; valid only for the duration of this
     *              call, as with {@link TokenConsumer#receive}
     */
    void attribute(String name, CharBuffer value) throws SAXException;

    /**
     * Signals that all attributes (and namespace declarations) for the
     * element currently being started have been reported. Always fired
     * exactly once per {@link #startElement(String)}, even when there were
     * no attributes.
     */
    void endAttributes() throws SAXException;

    /**
     * Reports character data.
     *
     * @param text the character data; valid only for the duration of this call
     */
    void characters(CharBuffer text) throws SAXException;

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
     * Reports a fatal error, mirroring {@link TokenConsumer#fatalError}.
     *
     * @param message the error message
     * @return the SAXException to throw
     */
    SAXException fatalError(String message) throws SAXException;

}

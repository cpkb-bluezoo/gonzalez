/*
 * NamespaceFilter.java
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
 * Stateless {@link XMLHandler} filter that reroutes {@code xmlns}/
 * {@code xmlns:prefix} attributes to {@link XMLHandler#namespace(String,
 * String)} declarations on a delegate handler, and passes every other event
 * straight through unchanged. {@link Scanner} always reports {@code xmlns}
 * as a plain attribute (matching namespace-unaware behaviour), so a
 * namespace-aware consumer (such as {@code new SAXAdapter(true)}) needs
 * this filter in front of it to translate; a namespace-unaware pipeline
 * simply omits this filter and feeds the scanner's output straight to the
 * consumer - namespace filtering costs nothing when not wanted.
 * <p>
 * "Stateless" here means no buffering of unrelated events and no cross-
 * element state (that lives in the delegate's own {@code NamespaceScopeTracker}
 * / element stack, as it already does for the namespace-aware
 * {@link SAXAdapter}). This filter only ever buffers the value of the one
 * {@code xmlns}/{@code xmlns:prefix} attribute currently being streamed
 * in, exactly as {@link SAXAdapter} itself buffers a multi-chunk attribute
 * value - the first chunk with {@code end=true} needs no buffering at all.
 * <p>
 * Namespace Constraint validation (reserved URI bindings, the empty-prefix
 * rule, the {@code xml}/{@code xmlns} prefix rules, and the XML 1.1-only
 * prefix-unbinding rule) happens here, mirroring {@code ContentParser
 * .processNamespaceAttribute} in the old tokenizer-based pipeline - this
 * filter is the component that recognises and interprets a namespace
 * declaration, so it is the natural place to reject an invalid one, exactly
 * as it is the natural place to translate a valid one. The downstream
 * {@link #namespace(String, String)} consumer can therefore keep trusting
 * that what it receives is already valid (it already does today -
 * {@code NamespaceScopeTracker.declarePrefix} performs no NSC validation of
 * its own). The one check the old pipeline does here that this filter does
 * <em>not</em> replicate is the non-fatal "namespace name should be an
 * absolute URI" advisory - a recoverable warning, not a WFC/NSC fatal
 * error, and not yet wired to {@link XMLHandler#error}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class NamespaceFilter implements XMLHandler {

    private final XMLHandler delegate;
    private final boolean xml11;

    // Set by startAttribute(), consulted by attributeValueContent() for the
    // duration of the current attribute only.
    private String currentAttrName;
    private boolean currentIsNamespaceDecl;
    private boolean valueFirstChunk;
    private final StringBuilder valueBuilder = new StringBuilder();

    /**
     * Creates a new filter.
     *
     * @param delegate the namespace-aware downstream handler
     * @param xml11 whether the document is being processed under XML 1.1
     *              rules (permits {@code xmlns:prefix=""} prefix-unbinding)
     */
    NamespaceFilter(XMLHandler delegate, boolean xml11) {
        this.delegate = delegate;
        this.xml11 = xml11;
    }

    @Override
    public void setLocator(Locator locator) {
        delegate.setLocator(locator);
    }

    @Override
    public void startDocument() throws SAXException {
        delegate.startDocument();
    }

    @Override
    public void endDocument() throws SAXException {
        delegate.endDocument();
    }

    @Override
    public void startElement(String qName) throws SAXException {
        delegate.startElement(qName);
    }

    @Override
    public void namespace(String prefix, String uri) throws SAXException {
        // Scanner never emits namespace() itself (see class Javadoc) - this
        // filter is the only thing that should ever call it on the delegate.
        throw new IllegalStateException(
                "namespace() received by NamespaceFilter - upstream should only emit plain attributes");
    }

    @Override
    public void startAttribute(String name, String type) throws SAXException {
        currentAttrName = name;
        currentIsNamespaceDecl = "xmlns".equals(name) || name.startsWith("xmlns:");
        if (currentIsNamespaceDecl) {
            valueFirstChunk = true;
        } else {
            delegate.startAttribute(name, type);
        }
    }

    @Override
    public void attributeValueContent(CharBuffer value, boolean end) throws SAXException {
        if (!currentIsNamespaceDecl) {
            delegate.attributeValueContent(value, end);
            return;
        }
        if (valueFirstChunk && end) {
            declareNamespace(currentAttrName, value.toString());
            return;
        }
        if (valueFirstChunk) {
            valueBuilder.setLength(0);
            valueFirstChunk = false;
        }
        valueBuilder.append(value);
        if (end) {
            declareNamespace(currentAttrName, valueBuilder.toString());
        }
    }

    private void declareNamespace(String attrName, String uri) throws SAXException {
        if ("xmlns".equals(attrName)) {
            if (NamespaceScopeTracker.XML_NAMESPACE_URI.equals(uri)) {
                throw delegate.fatalError(
                        "Cannot bind default namespace to reserved XML namespace URI");
            }
            if (NamespaceScopeTracker.XMLNS_NAMESPACE_URI.equals(uri)) {
                throw delegate.fatalError(
                        "Cannot bind default namespace to reserved xmlns namespace URI");
            }
            delegate.namespace("", uri);
            return;
        }

        String prefix = attrName.substring(6); // skip "xmlns:"
        if (prefix.isEmpty()) {
            throw delegate.fatalError("Namespace prefix must not be empty after xmlns:");
        }
        if (uri.isEmpty() && !xml11) {
            throw delegate.fatalError(
                    "Prefix unbinding (xmlns:" + prefix + "=\"\") is only allowed in XML 1.1");
        }
        if ("xml".equals(prefix) && !NamespaceScopeTracker.XML_NAMESPACE_URI.equals(uri)) {
            throw delegate.fatalError(
                    "Cannot bind 'xml' prefix to namespace other than "
                            + NamespaceScopeTracker.XML_NAMESPACE_URI);
        }
        if ("xmlns".equals(prefix)) {
            throw delegate.fatalError("Cannot declare 'xmlns' prefix");
        }
        if (NamespaceScopeTracker.XML_NAMESPACE_URI.equals(uri) && !"xml".equals(prefix)) {
            throw delegate.fatalError(
                    "Cannot bind prefix '" + prefix + "' to reserved XML namespace URI");
        }
        if (NamespaceScopeTracker.XMLNS_NAMESPACE_URI.equals(uri)) {
            throw delegate.fatalError(
                    "Cannot bind prefix '" + prefix + "' to reserved xmlns namespace URI");
        }
        delegate.namespace(prefix, uri);
    }

    @Override
    public void endAttributes() throws SAXException {
        delegate.endAttributes();
    }

    @Override
    public void characters(CharBuffer text, boolean ignorable, boolean end) throws SAXException {
        delegate.characters(text, ignorable, end);
    }

    @Override
    public void endElement() throws SAXException {
        delegate.endElement();
    }

    @Override
    public void comment(CharBuffer text) throws SAXException {
        delegate.comment(text);
    }

    @Override
    public void piTarget(String target) throws SAXException {
        delegate.piTarget(target);
    }

    @Override
    public void piData(CharBuffer data, boolean end) throws SAXException {
        delegate.piData(data, end);
    }

    @Override
    public void saveBuffers() throws SAXException {
        delegate.saveBuffers();
    }

    @Override
    public SAXException fatalError(String message) throws SAXException {
        return delegate.fatalError(message);
    }

    @Override
    public void error(String message) throws SAXException {
        delegate.error(message);
    }

}

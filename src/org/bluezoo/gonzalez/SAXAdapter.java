/*
 * SAXAdapter.java
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
import java.util.Iterator;
import java.util.Map;

import org.xml.sax.ContentHandler;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.ext.LexicalHandler;

/**
 * Adapts the internal {@link XMLHandler} event vocabulary to standard SAX2
 * {@link ContentHandler}/{@link LexicalHandler}.
 * <p>
 * This is the conformance anchor for the async pipeline (see
 * ASYNC-PIPELINE.md): differential testing compares its output against the
 * current parser's SAX event stream. It is also the only place namespace
 * resolution (raw qName -&gt; uri/localName, {@code Attributes} assembly)
 * happens - the event source (scanner, or a stateless upstream namespace
 * filter) never resolves names itself.
 * <p>
 * Attributes are buffered from {@link #startElement(String)} to
 * {@link #endAttributes()} using the same pooled {@link SAXAttributes} /
 * {@link QNamePool} / {@link NamespaceScopeTracker} machinery the current
 * parser uses, following the same resolve-at-end-of-tag pattern as
 * {@code ContentParser.fireStartElement} (an xmlns declaration appearing
 * after a prefixed attribute in the same start tag must still resolve that
 * attribute correctly). Since {@link XMLHandler#endElement()} carries no
 * name, this class maintains its own element stack of resolved
 * uri/localName/qName so it can supply SAX's {@code endElement(uri,
 * localName, qName)} - the event source is not required to remember this
 * itself.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class SAXAdapter implements XMLHandler {

    private ContentHandler contentHandler;
    private LexicalHandler lexicalHandler;

    private final boolean namespaceAware;
    private final NamespaceScopeTracker namespaceTracker;
    private final QNamePool qnamePool;
    private final SAXAttributes attributes;

    // Element stack. qName is always pushed; uri/localName are only
    // meaningful (and only pushed) in namespace-aware mode, so their depth
    // can differ in bookkeeping detail from qNameStack's but always matches
    // it in count.
    private final ArrayList<String> qNameStack = new ArrayList<String>();
    private final ArrayList<String> uriStack = new ArrayList<String>();
    private final ArrayList<String> localNameStack = new ArrayList<String>();

    // Reused across endElement() calls to avoid allocating a new list every
    // time just to reverse iteration order for endPrefixMapping.
    private final ArrayList<String> endedPrefixesBuffer = new ArrayList<String>();

    /**
     * Creates a new adapter.
     *
     * @param namespaceAware whether to resolve namespaces (process
     *        {@link #namespace(String, String)} events, fire
     *        startPrefixMapping/endPrefixMapping, resolve attribute/element
     *        uri and localName) or pass qNames through unresolved with an
     *        empty URI, matching SAX's own namespaceAware feature
     */
    SAXAdapter(boolean namespaceAware) {
        this.namespaceAware = namespaceAware;
        this.namespaceTracker = namespaceAware ? new NamespaceScopeTracker() : null;
        // SAXAttributes.addAttribute() always checks out a QName - it tracks
        // qName duplicates via QName equality regardless of namespace
        // awareness (WFC "Unique Att Spec" applies to the raw qName either
        // way) - so the pool is needed even when namespaceAware is false.
        this.qnamePool = new QNamePool();
        this.attributes = new SAXAttributes();
        attributes.setQNamePool(qnamePool);
    }

    void setContentHandler(ContentHandler handler) {
        this.contentHandler = handler;
    }

    void setLexicalHandler(LexicalHandler handler) {
        this.lexicalHandler = handler;
    }

    @Override
    public void setLocator(Locator locator) {
        if (contentHandler != null) {
            contentHandler.setDocumentLocator(locator);
        }
    }

    @Override
    public void startDocument() throws SAXException {
        if (contentHandler != null) {
            contentHandler.startDocument();
        }
    }

    @Override
    public void endDocument() throws SAXException {
        if (contentHandler != null) {
            contentHandler.endDocument();
        }
    }

    @Override
    public void startElement(String qName) throws SAXException {
        if (namespaceTracker != null) {
            namespaceTracker.pushContext();
        }
        attributes.clear();
        qNameStack.add(qName);
    }

    @Override
    public void namespace(String prefix, String uri) throws SAXException {
        if (namespaceTracker == null) {
            // Should not happen: a namespace-unaware pipeline configuration
            // has no stage that emits namespace() in the first place.
            throw new IllegalStateException(
                    "namespace() event received but this adapter is not namespace-aware");
        }
        namespaceTracker.declarePrefix(prefix, uri);
    }

    @Override
    public void attribute(String name, CharBuffer value) throws SAXException {
        // Materialized immediately: the CharBuffer is only valid for the
        // duration of this call, but the value must survive until
        // endAttributes() fires the SAX event.
        String valueStr = value.toString();
        try {
            // uri="" and localName=name for now, exactly as
            // ContentParser.handleAttributeValue does - resolved in
            // resolveAttributeNamespaces() below once all xmlns declarations
            // for this element are known, regardless of attribute order.
            attributes.addAttribute("", name, name, "CDATA", valueStr, true);
        } catch (NamespaceException e) {
            throw fatalError(e.getMessage());
        }
    }

    @Override
    public void endAttributes() throws SAXException {
        String qName = qNameStack.get(qNameStack.size() - 1);

        if (namespaceTracker != null) {
            Iterator<Map.Entry<String, String>> declarations = namespaceTracker.getCurrentScopeDeclarations();
            while (declarations.hasNext()) {
                Map.Entry<String, String> entry = declarations.next();
                if (contentHandler != null) {
                    contentHandler.startPrefixMapping(entry.getKey(), entry.getValue());
                }
            }

            try {
                attributes.resolveAttributeNamespaces(namespaceTracker);
            } catch (NamespaceException e) {
                throw fatalError(e.getMessage());
            }

            QName elementQName;
            try {
                elementQName = namespaceTracker.processName(qName, false, qnamePool);
            } catch (NamespaceException e) {
                throw fatalError(e.getMessage());
            }
            String uri = elementQName.getURI();
            String localName = elementQName.getLocalName();
            qnamePool.returnToPool(elementQName);

            uriStack.add(uri);
            localNameStack.add(localName);

            if (contentHandler != null) {
                contentHandler.startElement(uri, localName, qName, attributes);
            }
        } else {
            if (contentHandler != null) {
                contentHandler.startElement("", qName, qName, attributes);
            }
        }
    }

    @Override
    public void characters(CharBuffer text) throws SAXException {
        if (contentHandler == null) {
            return;
        }
        if (text.hasArray()) {
            contentHandler.characters(text.array(), text.arrayOffset() + text.position(), text.remaining());
        } else {
            char[] chars = new char[text.remaining()];
            text.get(chars);
            contentHandler.characters(chars, 0, chars.length);
        }
    }

    @Override
    public void endElement() throws SAXException {
        int top = qNameStack.size() - 1;
        String qName = qNameStack.remove(top);

        String uri = "";
        String localName = qName;
        if (namespaceTracker != null) {
            int nsTop = uriStack.size() - 1;
            uri = uriStack.remove(nsTop);
            localName = localNameStack.remove(nsTop);
        }

        if (contentHandler != null) {
            contentHandler.endElement(uri, localName, qName);
        }

        if (namespaceTracker != null) {
            endedPrefixesBuffer.clear();
            Iterator<Map.Entry<String, String>> declarations = namespaceTracker.getCurrentScopeDeclarations();
            while (declarations.hasNext()) {
                endedPrefixesBuffer.add(declarations.next().getKey());
            }
            if (contentHandler != null) {
                // Reverse order, matching ContentParser.fireStartElement/fireEndElement.
                for (int i = endedPrefixesBuffer.size() - 1; i >= 0; i--) {
                    contentHandler.endPrefixMapping(endedPrefixesBuffer.get(i));
                }
            }
            namespaceTracker.popContext();
        }
    }

    @Override
    public void comment(CharBuffer text) throws SAXException {
        if (lexicalHandler == null) {
            return;
        }
        if (text.hasArray()) {
            lexicalHandler.comment(text.array(), text.arrayOffset() + text.position(), text.remaining());
        } else {
            char[] chars = new char[text.remaining()];
            text.get(chars);
            lexicalHandler.comment(chars, 0, chars.length);
        }
    }

    @Override
    public void processingInstruction(String target, CharBuffer data) throws SAXException {
        if (contentHandler == null) {
            return;
        }
        contentHandler.processingInstruction(target, (data == null) ? "" : data.toString());
    }

    @Override
    public SAXException fatalError(String message) throws SAXException {
        return new SAXException(message);
    }

}

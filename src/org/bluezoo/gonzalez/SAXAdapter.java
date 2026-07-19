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
import org.xml.sax.DTDHandler;
import org.xml.sax.ErrorHandler;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.ext.Attributes2;
import org.xml.sax.ext.LexicalHandler;

/**
 * Adapts the internal {@link XMLHandler} event vocabulary to standard SAX2
 * {@link ContentHandler}/{@link LexicalHandler}.
 * <p>
 * This is the point where {@link Scanner}'s (or, if namespace-aware, {@link
 * NamespaceFilter}'s) internal event stream becomes a real SAX {@code
 * ContentHandler} call sequence, and the only place namespace resolution
 * (raw qName -&gt; uri/localName, {@code Attributes} assembly) happens - the
 * event source (scanner, or the stateless namespace filter in front of it)
 * never resolves names itself.
 * <p>
 * Attributes are buffered from {@link #startElement(String)} to
 * {@link #endAttributes()} into this class's own small pooled {@link Attr}
 * list, and this class implements {@link Attributes2} directly rather than
 * delegating to a separate object (unlike {@code ContentParser}, which
 * shares {@link SAXAttributes} - a richer implementation with lazy DTD-
 * backed {@code getType()}/{@code isDeclared()} lookups this pipeline
 * doesn't need, since {@link Scanner} already resolves an attribute's type
 * before {@link #startAttribute} ever sees it). {@link #startElement(String)}
 * clearing the list back to zero-length is genuinely {@code O(1)} - unlike
 * {@code SAXAttributes.clear()}, there is no per-attribute pool (a
 * {@link QNamePool} entry, a {@code StringBuilder}) to return, since {@link
 * Attr} doesn't use one; see {@link Attr}'s own Javadoc. Duplicate-attribute
 * detection ("WFC Unique Att Spec") is a reference-equality linear scan
 * rather than a hash-based check, since {@code Scanner.namePool} guarantees
 * every attribute name reaching {@link #startAttribute} is already interned
 * - the same technique {@code Scanner.wasAttributeSeen} uses for its own,
 * analogous check.
 * <p>
 * Namespace resolution follows the same resolve-at-end-of-tag pattern as
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
class SAXAdapter implements XMLHandler, Attributes2 {

    /**
     * One buffered attribute. Pooled and reused across {@link
     * #startElement(String)} calls exactly like {@code SAXAttributes}'s own
     * {@code Attribute} holder - the list only ever grows (never shrinks)
     * to the high-water mark of attributes-per-element seen so far - but
     * unlike that class's holder, has no {@link QNamePool} entry of its own
     * to check out/return: {@link #uri}/{@link #localName}/{@link #qName}
     * are plain fields, resolved in place by {@link
     * #resolveAttributeNamespaces}, needing no pooled wrapper object at all.
     */
    private static final class Attr {
        String uri = "";
        String localName;
        String qName;
        String type;
        String value;
        boolean specified;
    }

    private ContentHandler contentHandler;
    private LexicalHandler lexicalHandler;
    private DTDHandler dtdHandler;
    private ErrorHandler errorHandler;
    private String publicId;
    private String systemId;

    private final boolean namespaceAware;
    private final NamespaceScopeTracker namespaceTracker;
    private final QNamePool qnamePool;

    // This element's buffered attributes - see Attr's own Javadoc for the
    // pooling/duplicate-detection design. attrCount is the "used" length;
    // attrPool.size() is the high-water mark (>= attrCount always).
    private final ArrayList<Attr> attrPool = new ArrayList<Attr>();
    private int attrCount;
    private boolean hasPrefixedAttributes;

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

    // Current attribute being assembled from startAttribute()/
    // attributeValueContent() calls. attributeValueFirstChunk is true only
    // until the first attributeValueContent() call for the current
    // attribute; combined with that call's own end flag, it identifies the
    // common single-chunk case, which is handed straight to addAttribute()
    // with no accumulation at all. attributeValueBuilder is only touched in
    // the rarer multi-chunk case, and is a single reused field (not a pool)
    // since attributes are only ever assembled one at a time, never nested
    // or concurrent.
    private String currentAttributeName;
    private String currentAttributeType;
    private boolean attributeValueFirstChunk;
    private final StringBuilder attributeValueBuilder = new StringBuilder();

    // Current processing instruction being assembled from piTarget()/
    // piData() calls - the same first-chunk-fast-path/StringBuilder-only-
    // for-the-rarer-multi-chunk-case shape as the attribute value fields
    // just above, needed here because SAX's own ContentHandler.
    // processingInstruction(String, String) has no streaming form of its
    // own to forward chunks to directly.
    private String currentPITarget;
    private boolean piDataFirstChunk;
    private final StringBuilder piDataBuilder = new StringBuilder();

    // Current comment being assembled from startComment()/commentData()
    // calls - the same shape as the PI fields just above and for the same
    // reason: LexicalHandler.comment(char[], int, int) has no streaming
    // form of its own.
    private boolean commentDataFirstChunk;
    private final StringBuilder commentDataBuilder = new StringBuilder();

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
        // Only the element's own name is resolved via a pooled QName (see
        // endAttributes()) - attributes no longer need one at all, per
        // Attr's own Javadoc.
        this.qnamePool = new QNamePool();
    }

    void setContentHandler(ContentHandler handler) {
        this.contentHandler = handler;
    }

    void setLexicalHandler(LexicalHandler handler) {
        this.lexicalHandler = handler;
    }

    void setDTDHandler(DTDHandler handler) {
        this.dtdHandler = handler;
    }

    void setErrorHandler(ErrorHandler handler) {
        this.errorHandler = handler;
    }

    void setPublicId(String publicId) {
        this.publicId = publicId;
    }

    void setSystemId(String systemId) {
        this.systemId = systemId;
    }

    @Override
    public void setLocator(Locator locator) {
        if (contentHandler != null) {
            contentHandler.setDocumentLocator(locator);
        }
    }

    @Override
    public void setXml11(boolean xml11) {
        // No XML-version-dependent behavior of its own - see XMLHandler#setXml11's
        // no-op carve-out; this event exists for consumers like NamespaceFilter.
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
        // O(1): no per-attribute pool to return anything to - see Attr's
        // own Javadoc.
        attrCount = 0;
        hasPrefixedAttributes = false;
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
    public void startAttribute(String name, String type) throws SAXException {
        currentAttributeName = name;
        currentAttributeType = type;
        attributeValueFirstChunk = true;
    }

    @Override
    public void attributeValueContent(CharBuffer value, boolean end) throws SAXException {
        if (attributeValueFirstChunk && end) {
            // Common case: exactly one chunk for this attribute - hand it
            // straight through, no accumulation, no StringBuilder touched at all.
            addCurrentAttribute(value.toString());
            return;
        }
        if (attributeValueFirstChunk) {
            attributeValueBuilder.setLength(0);
            attributeValueFirstChunk = false;
        }
        // CharBuffer implements CharSequence via the absolute (non-position-
        // mutating) get(int) internally, so this does not disturb value's
        // position - safe even though Scanner still owns this buffer.
        attributeValueBuilder.append(value);
        if (end) {
            addCurrentAttribute(attributeValueBuilder.toString());
        }
    }

    /**
     * Buffers the current attribute (name/type already captured by {@link
     * #startAttribute}) into {@link #attrPool}. {@code uri=""} and {@code
     * localName=qName} for now, exactly as {@code ContentParser.
     * handleAttributeValue} does - resolved in {@link
     * #resolveAttributeNamespaces} below once all xmlns declarations for
     * this element are known, regardless of attribute order.
     * <p>
     * Duplicate-name detection (WFC "Unique Att Spec") is a reference-
     * equality scan against the qNames buffered so far: safe, not just
     * fast, because {@code currentAttributeName} is always {@code
     * Scanner.namePool}-interned by the time it reaches {@link
     * #startAttribute} (same content always means the same {@code String}
     * instance). This subsumes what {@code SAXAttributes.addAttribute}'s
     * own (more expensive) "duplicate by expanded name" scan checked at
     * add-time too: before any attribute is namespace-resolved, {@code uri}
     * is {@code ""} and {@code localName} equals {@code qName} for every
     * attribute, so "duplicate by expanded name" and "duplicate by qName"
     * are the same check at this point - only {@link
     * #resolveAttributeNamespaces}'s post-resolution scan below catches a
     * genuine same-namespace-different-prefix duplicate.
     */
    private void addCurrentAttribute(String valueStr) throws SAXException {
        String qName = currentAttributeName;
        for (int i = 0; i < attrCount; i++) {
            if (attrPool.get(i).qName == qName) {
                throw fatalError("Duplicate attribute: " + qName);
            }
        }
        Attr attr;
        if (attrCount < attrPool.size()) {
            attr = attrPool.get(attrCount);
        } else {
            attr = new Attr();
            attrPool.add(attr);
        }
        attr.uri = "";
        attr.localName = qName;
        attr.qName = qName;
        attr.type = currentAttributeType;
        attr.value = valueStr;
        attr.specified = true;
        attrCount++;
        if (qName.indexOf(':') >= 0) {
            hasPrefixedAttributes = true;
        }
    }

    /**
     * Resolves any prefixed attributes' {@code uri}/{@code localName} now
     * that every xmlns declaration on this element is known - ported from
     * {@code SAXAttributes.resolveAttributeNamespaces}, operating on {@link
     * #attrPool} instead. A no-op (single boolean check) unless this
     * element actually has a prefixed attribute.
     */
    private void resolveAttributeNamespaces(NamespaceScopeTracker tracker) throws SAXException {
        if (!hasPrefixedAttributes) {
            return;
        }
        for (int i = 0; i < attrCount; i++) {
            Attr attr = attrPool.get(i);
            String qName = attr.qName;
            int colonPos = qName.indexOf(':');
            if (colonPos > 0 && attr.uri.isEmpty()) {
                if (qName.startsWith("xmlns:")) {
                    continue;
                }
                String prefix = qName.substring(0, colonPos);
                String localName = qName.substring(colonPos + 1);
                String uri = tracker.getURI(prefix);
                if (uri == null) {
                    throw fatalError("Unbound namespace prefix: " + prefix);
                }
                attr.uri = uri;
                attr.localName = localName;
                for (int j = 0; j < attrCount; j++) {
                    if (j == i) {
                        continue;
                    }
                    Attr other = attrPool.get(j);
                    if (other.localName.equals(localName) && other.uri.equals(uri)) {
                        throw fatalError("Duplicate attribute by expanded name: {" + uri + "}" + localName
                                + " (qName: " + qName + ")");
                    }
                }
            }
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

            resolveAttributeNamespaces(namespaceTracker);

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
                contentHandler.startElement(uri, localName, qName, this);
            }
        } else {
            if (contentHandler != null) {
                contentHandler.startElement("", qName, qName, this);
            }
        }
    }

    @Override
    public void characters(CharBuffer text, boolean ignorable, boolean end) throws SAXException {
        // end is unused here: SAX's own characters()/ignorableWhitespace()
        // already tolerate being split across calls exactly like this event
        // does, so there is nothing to coalesce - each chunk passes straight
        // through as its own call, synchronously, within this method.
        if (contentHandler == null) {
            return;
        }
        if (text.hasArray()) {
            char[] array = text.array();
            int offset = text.arrayOffset() + text.position();
            int length = text.remaining();
            if (ignorable) {
                contentHandler.ignorableWhitespace(array, offset, length);
            } else {
                contentHandler.characters(array, offset, length);
            }
        } else {
            char[] chars = new char[text.remaining()];
            text.get(chars);
            if (ignorable) {
                contentHandler.ignorableWhitespace(chars, 0, chars.length);
            } else {
                contentHandler.characters(chars, 0, chars.length);
            }
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
    public void startComment() throws SAXException {
        commentDataFirstChunk = true;
    }

    @Override
    public void commentData(CharBuffer text, boolean end) throws SAXException {
        if (lexicalHandler == null) {
            return;
        }
        if (commentDataFirstChunk && end) {
            // Common case: exactly one chunk - hand it straight through, not
            // even a String allocation (unlike piData's own fast path,
            // LexicalHandler.comment takes char[]/offset/length directly).
            if (text.hasArray()) {
                lexicalHandler.comment(text.array(), text.arrayOffset() + text.position(), text.remaining());
            } else {
                char[] chars = new char[text.remaining()];
                text.get(chars);
                lexicalHandler.comment(chars, 0, chars.length);
            }
            return;
        }
        if (commentDataFirstChunk) {
            commentDataBuilder.setLength(0);
            commentDataFirstChunk = false;
        }
        commentDataBuilder.append(text);
        if (end) {
            int len = commentDataBuilder.length();
            char[] chars = new char[len];
            commentDataBuilder.getChars(0, len, chars, 0);
            lexicalHandler.comment(chars, 0, len);
        }
    }

    @Override
    public void startCDATA() throws SAXException {
        if (lexicalHandler != null) {
            lexicalHandler.startCDATA();
        }
    }

    @Override
    public void endCDATA() throws SAXException {
        if (lexicalHandler != null) {
            lexicalHandler.endCDATA();
        }
    }

    @Override
    public void startDTD(String name, String publicId, String systemId) throws SAXException {
        if (lexicalHandler != null) {
            lexicalHandler.startDTD(name, publicId, systemId);
        }
    }

    @Override
    public void endDTD() throws SAXException {
        if (lexicalHandler != null) {
            lexicalHandler.endDTD();
        }
    }

    @Override
    public void startEntity(String name) throws SAXException {
        if (lexicalHandler != null) {
            lexicalHandler.startEntity(name);
        }
    }

    @Override
    public void endEntity(String name) throws SAXException {
        if (lexicalHandler != null) {
            lexicalHandler.endEntity(name);
        }
    }

    @Override
    public void notationDecl(String name, String publicId, String systemId) throws SAXException {
        if (dtdHandler != null) {
            dtdHandler.notationDecl(name, publicId, systemId);
        }
    }

    @Override
    public void unparsedEntityDecl(String name, String publicId, String systemId, String notationName)
            throws SAXException {
        if (dtdHandler != null) {
            dtdHandler.unparsedEntityDecl(name, publicId, systemId, notationName);
        }
    }

    @Override
    public void piTarget(String target) throws SAXException {
        currentPITarget = target;
        piDataFirstChunk = true;
    }

    @Override
    public void piData(CharBuffer data, boolean end) throws SAXException {
        if (contentHandler == null) {
            return;
        }
        if (piDataFirstChunk && end) {
            // Common case: exactly one chunk (or none at all - a zero-length
            // CharBuffer for a data-less PI) - hand it straight through, no
            // accumulation, no StringBuilder touched at all.
            contentHandler.processingInstruction(currentPITarget, data.toString());
            return;
        }
        if (piDataFirstChunk) {
            piDataBuilder.setLength(0);
            piDataFirstChunk = false;
        }
        piDataBuilder.append(data);
        if (end) {
            contentHandler.processingInstruction(currentPITarget, piDataBuilder.toString());
        }
    }

    @Override
    public void saveBuffers() throws SAXException {
        // No-op: this adapter never defers copying. characters() passes
        // through to the wrapped SAX handler synchronously within the call
        // that delivers them (matching SAX's own contract), and
        // attributeValueContent's/piData's/commentData's multi-chunk case
        // each copy into their own StringBuilder immediately via
        // StringBuilder.append(CharBuffer) rather than holding a reference.
        // Nothing here is ever left unflushed across events, so there is
        // nothing to save.
    }

    /**
     * Builds a proper {@link SAXParseException} (not a plain {@link
     * SAXException}) and notifies {@link #errorHandler} if one is set -
     * mirroring {@code ContentParser.fatalError(String)}'s exact shape
     * (build exception, call {@code errorHandler.fatalError(exception)},
     * return it) so callers/harnesses that rely on either signal (a
     * genuinely-typed {@code SAXParseException} propagating out of {@code
     * parse()}, or the standard SAX {@code ErrorHandler.fatalError}
     * callback firing) see the same behaviour regardless of which pipeline
     * is active. Line/column are always -1 ("unknown") - {@link Scanner}
     * does not track position yet, a known, documented gap.
     */
    @Override
    public SAXException fatalError(String message) throws SAXException {
        SAXParseException exception = new SAXParseException(message, publicId, systemId, -1, -1);
        if (errorHandler != null) {
            errorHandler.fatalError(exception);
        }
        return exception;
    }

    /**
     * Reports a recoverable VC violation via {@link #errorHandler}'s {@code
     * error(SAXParseException)} - mirrors {@code
     * ContentParser.reportValidationError}'s exact shape (build exception,
     * call {@code errorHandler.error(exception)}, return normally - never
     * thrown). A no-op if no error handler is set, matching SAX's own
     * "errors are only reported if someone is listening" convention.
     */
    @Override
    public void error(String message) throws SAXException {
        if (errorHandler != null) {
            errorHandler.error(new SAXParseException(message, publicId, systemId, -1, -1));
        }
    }

    // ===== Attributes / Attributes2 =====
    //
    // Implemented directly against attrPool/attrCount (see Attr's own
    // Javadoc) rather than delegating to SAXAttributes - this pipeline has
    // no DTD-backed lazy getType()/isDeclared() to offer (Scanner already
    // resolves an attribute's type before startAttribute ever sees it, and
    // has no attribute-declaration introspection wired here), so there is
    // nothing that class's extra machinery would add.

    private int findIndexByQName(String qName) {
        for (int i = 0; i < attrCount; i++) {
            if (attrPool.get(i).qName.equals(qName)) {
                return i;
            }
        }
        return -1;
    }

    private int findIndexByExpandedName(String uri, String localName) {
        for (int i = 0; i < attrCount; i++) {
            Attr attr = attrPool.get(i);
            if (attr.localName.equals(localName) && attr.uri.equals(uri)) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public int getLength() {
        return attrCount;
    }

    @Override
    public String getURI(int index) {
        return (index < 0 || index >= attrCount) ? null : attrPool.get(index).uri;
    }

    @Override
    public String getLocalName(int index) {
        return (index < 0 || index >= attrCount) ? null : attrPool.get(index).localName;
    }

    @Override
    public String getQName(int index) {
        return (index < 0 || index >= attrCount) ? null : attrPool.get(index).qName;
    }

    @Override
    public String getType(int index) {
        return (index < 0 || index >= attrCount) ? null : attrPool.get(index).type;
    }

    @Override
    public String getValue(int index) {
        return (index < 0 || index >= attrCount) ? null : attrPool.get(index).value;
    }

    @Override
    public int getIndex(String uri, String localName) {
        return findIndexByExpandedName(uri, localName);
    }

    @Override
    public int getIndex(String qName) {
        return findIndexByQName(qName);
    }

    @Override
    public String getType(String uri, String localName) {
        int i = findIndexByExpandedName(uri, localName);
        return i < 0 ? null : attrPool.get(i).type;
    }

    @Override
    public String getType(String qName) {
        int i = findIndexByQName(qName);
        return i < 0 ? null : attrPool.get(i).type;
    }

    @Override
    public String getValue(String uri, String localName) {
        int i = findIndexByExpandedName(uri, localName);
        return i < 0 ? null : attrPool.get(i).value;
    }

    @Override
    public String getValue(String qName) {
        int i = findIndexByQName(qName);
        return i < 0 ? null : attrPool.get(i).value;
    }

    // Attributes2 - no DTD-declaration introspection wired here yet (see
    // this section's header comment), so isDeclared() is always false
    // rather than performing a lazy DTD lookup; isSpecified() is always
    // true, matching this pipeline's existing behaviour (every attribute
    // reaching addCurrentAttribute, defaulted or explicit, is recorded the
    // same way - see Scanner.applyAttributeDefaults).

    @Override
    public boolean isDeclared(int index) {
        if (index < 0 || index >= attrCount) {
            throw new ArrayIndexOutOfBoundsException(index);
        }
        return false;
    }

    @Override
    public boolean isDeclared(String qName) {
        if (findIndexByQName(qName) < 0) {
            throw new IllegalArgumentException("Unknown attribute: " + qName);
        }
        return false;
    }

    @Override
    public boolean isDeclared(String uri, String localName) {
        if (findIndexByExpandedName(uri, localName) < 0) {
            throw new IllegalArgumentException("Unknown attribute: {" + uri + "}" + localName);
        }
        return false;
    }

    @Override
    public boolean isSpecified(int index) {
        if (index < 0 || index >= attrCount) {
            throw new ArrayIndexOutOfBoundsException(index);
        }
        return attrPool.get(index).specified;
    }

    @Override
    public boolean isSpecified(String qName) {
        int i = findIndexByQName(qName);
        if (i < 0) {
            throw new IllegalArgumentException("Unknown attribute: " + qName);
        }
        return attrPool.get(i).specified;
    }

    @Override
    public boolean isSpecified(String uri, String localName) {
        int i = findIndexByExpandedName(uri, localName);
        if (i < 0) {
            throw new IllegalArgumentException("Unknown attribute: {" + uri + "}" + localName);
        }
        return attrPool.get(i).specified;
    }

}

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
import java.util.Arrays;

import org.xml.sax.ContentHandler;
import org.xml.sax.DTDHandler;
import org.xml.sax.ErrorHandler;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.ext.Attributes2;
import org.xml.sax.ext.DeclHandler;
import org.xml.sax.ext.LexicalHandler;

/**
 * Adapts the internal {@link XMLHandler} event vocabulary to standard SAX2
 * {@link ContentHandler}/{@link LexicalHandler}.
 * <p>
 * This is the point where {@link Scanner}'s (or, if namespace-aware, {@link
 * NamespaceFilter}'s) internal event stream becomes a real SAX {@code
 * ContentHandler} call sequence, and where the {@code Attributes} are
 * assembled. What names mean in namespace terms is not settled here: the
 * {@link NamespaceFilter} in front of a namespace-aware adapter keeps the
 * declarations, checks the names, and hands this the namespace and local name
 * of each element ({@link #endAttributes(String, String)}) and of each
 * prefixed attribute ({@link #resolvedAttribute}), and is asked for what an
 * element declares when {@code startPrefixMapping} and {@code
 * endPrefixMapping} are to be reported. The adapter keeps none of that
 * itself, so the work is done once, whichever handler is behind the filter.
 * <p>
 * Attributes are buffered from {@link #startElement(String)} to the end of
 * the start tag into this class's own small pooled {@link Attr} list, and
 * this class implements {@link Attributes2} directly rather than delegating
 * to a separate object (unlike {@code ContentParser}, which shares {@link
 * SAXAttributes} - a richer implementation with lazy DTD-backed {@code
 * getType()}/{@code isDeclared()} lookups this pipeline doesn't need, since
 * {@link Scanner} already resolves an attribute's type before it is reported
 * here). {@link #startElement(String)} clearing the list back to zero-length
 * is genuinely {@code O(1)}: there is no per-attribute pool to return
 * anything to; see {@link Attr}'s own Javadoc. Duplicate-attribute detection
 * by raw name (WFC "Unique Att Spec") is not this class's job: {@link
 * Scanner} enforces it before an attribute is ever reported, for every
 * consumer of its events, and the namespace-level form (same expanded name
 * through different prefixes) is checked by the filter.
 * <p>
 * The scanner reports this class text, and attributes whose values it has
 * whole, as ranges of its own buffer ({@link CharArrayHandler}), so that
 * neither costs an object before the string, if there is one, that SAX needs.
 * <p>
 * Since {@link XMLHandler#endElement()} carries no name, this class maintains
 * its own element stack of qName/uri/localName so it can supply SAX's {@code
 * endElement(uri, localName, qName)} - the event source is not required to
 * remember this itself.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class SAXAdapter implements XMLHandler, CharArrayHandler, Attributes2 {

    /**
     * One buffered attribute. Pooled and reused across {@link
     * #startElement(String)} calls exactly like {@code SAXAttributes}'s own
     * {@code Attribute} holder - the list only ever grows (never shrinks)
     * to the high-water mark of attributes-per-element seen so far - but
     * unlike that class's holder, has no pooled name object of its own:
     * {@link #uri}/{@link #localName}/{@link #qName} are plain fields, given
     * what the namespace filter settled by {@link #resolvedAttribute},
     * needing no pooled wrapper object at all.
     */
    private static final class Attr {
        String uri = "";
        String localName;
        String qName;
        String type;
        String value;
        boolean declared;
        boolean specified;
    }

    private ContentHandler contentHandler;
    private LexicalHandler lexicalHandler;
    private DeclHandler declHandler;
    private DTDHandler dtdHandler;
    private ErrorHandler errorHandler;
    private String publicId;
    private String systemId;

    private final boolean namespaceAware;
    /** The {@link NamespaceFilter} in front of this adapter, which settles
     *  what names mean and what is declared, and which this asks for the
     *  declarations. Null unless namespace aware. */
    private NamespaceFilter namespaces;

    // This element's buffered attributes - see Attr's own Javadoc for the
    // pooling design. attrCount is the "used" length; the slots of attrs
    // that are not null are the high-water mark (>= attrCount always). The
    // attribute being assembled is attrs[attrCount], not counted until its
    // value is complete.
    private Attr[] attrs = new Attr[16];
    private int attrCount;

    // Where the next attribute that the filter reports as resolved is looked
    // for: they are reported in the order the attributes came, so one is
    // never looked for before the last found.
    private int resolveCursor;

    // The elements that are open, innermost last: the name as written, the
    // namespace it is in, and its local name if that is not the name (null
    // when it is, as it is for every name with no prefix). The latter two
    // are set at the end of the start tag, in namespace-aware mode only, and
    // only when they differ from what is already there: most elements are in
    // the namespace of the last at their depth, and a reference stored into
    // an array that has been around a while is not free under G1.
    private String[] qNameStack = new String[32];
    private String[] uriStack = new String[32];
    private String[] localNameStack = new String[32];
    private int elementDepth;

    // Current attribute being assembled from startAttribute()/
    // attributeValueContent() calls. attributeValueFirstChunk is true only
    // until the first attributeValueContent() call for the current
    // attribute; combined with that call's own end flag, it identifies the
    // common single-chunk case, which is handed straight to addAttribute()
    // with no accumulation at all. attributeValueBuilder is only touched in
    // the rarer multi-chunk case, and is a single reused field (not a pool)
    // since attributes are only ever assembled one at a time, never nested
    // or concurrent.
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
    private boolean xmlnsUris;

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
    }

    /**
     * Sets the filter that makes the pipeline namespace aware. It decides
     * what every name means and keeps what is declared, and this takes the
     * answers from it.
     */
    void setNamespaces(NamespaceFilter namespaces) {
        this.namespaces = namespaces;
    }

    void setContentHandler(ContentHandler handler) {
        this.contentHandler = handler;
    }

    void setLexicalHandler(LexicalHandler handler) {
        this.lexicalHandler = handler;
    }

    void setDeclHandler(DeclHandler handler) {
        this.declHandler = handler;
    }

    DeclHandler getDeclHandler() {
        return declHandler;
    }

    void setXmlnsUris(boolean xmlnsUris) {
        this.xmlnsUris = xmlnsUris;
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
        elementDepth = 0;
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
        // O(1): no per-attribute pool to return anything to - see Attr's
        // own Javadoc.
        attrCount = 0;
        resolveCursor = 0;
        int depth = elementDepth;
        if (depth == qNameStack.length) {
            int capacity = depth * 2;
            qNameStack = Arrays.copyOf(qNameStack, capacity);
            uriStack = Arrays.copyOf(uriStack, capacity);
            localNameStack = Arrays.copyOf(localNameStack, capacity);
        }
        qNameStack[depth] = qName;
        elementDepth = depth + 1;
    }

    @Override
    public void namespace(String prefix, String uri) throws SAXException {
        if (!namespaceAware) {
            // Should not happen: a namespace-unaware pipeline configuration
            // has no stage that emits namespace() in the first place.
            throw new IllegalStateException(
                    "namespace() event received but this adapter is not namespace-aware");
        }
        // Nothing to do: the filter that reports it has kept it, and is
        // asked for what an element declares when that is wanted.
    }

    @Override
    public void startAttribute(String name, String type) throws SAXException {
        startAttribute(name, type, false, true);
    }

    @Override
    public void startAttribute(String name, String type, boolean declared, boolean specified)
            throws SAXException {
        int index = attrCount;
        if (index == attrs.length) {
            attrs = Arrays.copyOf(attrs, index * 2);
        }
        Attr attr = attrs[index];
        if (attr == null) {
            attr = new Attr();
            attrs[index] = attr;
        }
        // A slot is mostly given what it held for the same place in the last
        // element, and a reference stored costs a write barrier, which is
        // not small under G1 for an object that has been around for a while;
        // so a reference is stored only when it differs.
        if (attr.uri != "") {
            attr.uri = "";
        }
        if (attr.localName != name) {
            attr.localName = name;
        }
        if (attr.qName != name) {
            attr.qName = name;
        }
        if (attr.type != type) {
            attr.type = type;
        }
        attr.declared = declared;
        attr.specified = specified;
        attributeValueFirstChunk = true;
    }

    @Override
    public boolean takesCharArrays() {
        return true;
    }

    @Override
    public void characters(char[] chars, int start, int length, boolean ignorable, boolean end)
            throws SAXException {
        ContentHandler handler = contentHandler;
        if (handler == null) {
            return;
        }
        if (ignorable) {
            handler.ignorableWhitespace(chars, start, length);
        } else {
            handler.characters(chars, start, length);
        }
    }

    @Override
    public void attribute(String name, String type, boolean declared, boolean specified,
            char[] chars, int start, int length) {
        int index = attrCount;
        if (index == attrs.length) {
            attrs = Arrays.copyOf(attrs, index * 2);
        }
        Attr attr = attrs[index];
        if (attr == null) {
            attr = new Attr();
            attrs[index] = attr;
        }
        // As in startAttribute, which this is that and the value together.
        if (attr.uri != "") {
            attr.uri = "";
        }
        if (attr.localName != name) {
            attr.localName = name;
        }
        if (attr.qName != name) {
            attr.qName = name;
        }
        if (attr.type != type) {
            attr.type = type;
        }
        attr.declared = declared;
        attr.specified = specified;
        attr.value = new String(chars, start, length);
        if (namespaceAware && xmlnsUris && ("xmlns".equals(name) || name.startsWith("xmlns:"))) {
            attr.uri = NamespaceScopeTracker.XMLNS_NAMESPACE_URI;
            int colon = name.indexOf(':');
            attr.localName = colon < 0 ? "xmlns" : name.substring(colon + 1);
        }
        attrCount = index + 1;
    }

    @Override
    public void attributeValueChars(char[] chars, int start, int length, boolean end)
            throws SAXException {
        if (attributeValueFirstChunk && end) {
            // Common case, as in attributeValueContent: one chunk, no builder.
            addCurrentAttribute(new String(chars, start, length));
            return;
        }
        if (attributeValueFirstChunk) {
            attributeValueBuilder.setLength(0);
            attributeValueFirstChunk = false;
        }
        attributeValueBuilder.append(chars, start, length);
        if (end) {
            addCurrentAttribute(attributeValueBuilder.toString());
        }
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
     * #startAttribute}) into {@link #attrs}. {@code uri=""} and {@code
     * localName=qName} for now, as for an attribute with no prefix, which
     * is all of them that are not given a namespace by {@link
     * #resolvedAttribute} when the start tag ends, once all xmlns
     * declarations for this element are known, regardless of attribute
     * order.
     * <p>
     * No duplicate-qName check is made here: {@link Scanner} has already
     * rejected a repeated raw name (WFC "Unique Att Spec") before {@link
     * #startAttribute} was called, and {@link NamespaceFilter} rejects two
     * attributes that differ in prefix but not in namespace.
     */
    private void addCurrentAttribute(String valueStr) {
        // The attribute that startAttribute made ready, which is the next one.
        Attr attr = attrs[attrCount];
        attr.value = valueStr;
        String qName = attr.qName;
        if (namespaceAware && xmlnsUris
                && ("xmlns".equals(qName) || qName.startsWith("xmlns:"))) {
            attr.uri = NamespaceScopeTracker.XMLNS_NAMESPACE_URI;
            int colon = qName.indexOf(':');
            attr.localName = colon < 0 ? "xmlns" : qName.substring(colon + 1);
        }
        attrCount++;
    }

    /**
     * Takes what the filter has settled about a prefixed attribute: the
     * namespace its prefix is bound to and its local name. Attributes
     * with no prefix are in no namespace and are their own local name, as
     * {@link #startAttribute} leaves them, so this is only for the others,
     * which come in the order the attributes did.
     * <p>
     * Whether the names are legal - each prefix bound, no two attributes
     * with the same expanded name - was decided by the filter before this
     * was asked, whatever handler is behind it.
     */
    void resolvedAttribute(String qName, String uri, String localName) {
        int n = attrCount;
        for (int i = resolveCursor; i < n; i++) {
            Attr attr = attrs[i];
            if (attr.qName == qName) {
                attr.uri = uri;
                attr.localName = localName;
                resolveCursor = i + 1;
                return;
            }
        }
        // Not where expected, which is not expected: look through them all.
        for (int i = 0; i < n; i++) {
            Attr attr = attrs[i];
            if (attr.qName.equals(qName)) {
                attr.uri = uri;
                attr.localName = localName;
                resolveCursor = i + 1;
                return;
            }
        }
    }

    @Override
    public void endAttributes() throws SAXException {
        if (namespaceAware) {
            // The filter, which every namespace-aware pipeline has in front
            // of this, does not come this way.
            throw new IllegalStateException(
                    "A namespace-aware adapter takes the end of a start tag from its NamespaceFilter");
        }
        if (contentHandler != null) {
            String qName = qNameStack[elementDepth - 1];
            contentHandler.startElement("", qName, qName, this);
        }
    }

    /**
     * The end of the start tag of a namespace-aware element, with what the
     * filter has settled of its name.
     *
     * @param uri the namespace the element is in
     * @param localName its local name, or null if that is its name as
     *        written, which it is if that has no prefix
     */
    void endAttributes(String uri, String localName) throws SAXException {
        int top = elementDepth - 1;
        if (uriStack[top] != uri) {
            uriStack[top] = uri;
        }
        if (localNameStack[top] != localName) {
            localNameStack[top] = localName;
        }
        ContentHandler handler = contentHandler;
        if (handler != null) {
            NamespaceFilter filter = namespaces;
            for (int i = 0, n = filter.declarationCount(); i < n; i++) {
                handler.startPrefixMapping(filter.declarationPrefix(i), filter.declarationURI(i));
            }
            String qName = qNameStack[top];
            handler.startElement(uri, localName == null ? qName : localName, qName, this);
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
        int top = --elementDepth;
        String qName = qNameStack[top];

        String uri = "";
        String localName = qName;
        if (namespaceAware) {
            uri = uriStack[top];
            localName = localNameStack[top];
            if (localName == null) {
                localName = qName;
            }
        }

        ContentHandler handler = contentHandler;
        if (handler != null) {
            handler.endElement(uri, localName, qName);
            if (namespaceAware) {
                // Reverse order, matching ContentParser.fireStartElement/fireEndElement.
                NamespaceFilter filter = namespaces;
                for (int i = filter.declarationCount() - 1; i >= 0; i--) {
                    handler.endPrefixMapping(filter.declarationPrefix(i));
                }
            }
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
    public void elementDecl(String name, String model) throws SAXException {
        if (declHandler != null) {
            declHandler.elementDecl(name, model);
        }
    }

    @Override
    public void attributeDecl(String eName, String aName, String type, String mode, String value)
            throws SAXException {
        if (declHandler != null) {
            declHandler.attributeDecl(eName, aName, type, mode, value);
        }
    }

    @Override
    public void internalEntityDecl(String name, String value) throws SAXException {
        if (declHandler != null) {
            declHandler.internalEntityDecl(name, value);
        }
    }

    @Override
    public void externalEntityDecl(String name, String publicId, String systemId) throws SAXException {
        if (declHandler != null) {
            declHandler.externalEntityDecl(name, publicId, systemId);
        }
    }

    @Override
    public void skippedEntity(String name) throws SAXException {
        if (contentHandler != null) {
            contentHandler.skippedEntity(name);
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
    // Implemented directly against attrs/attrCount (see Attr's own
    // Javadoc) rather than delegating to SAXAttributes - this pipeline has
    // no DTD-backed lazy getType()/isDeclared() to offer (Scanner already
    // resolves an attribute's type before startAttribute ever sees it, and
    // has no attribute-declaration introspection wired here), so there is
    // nothing that class's extra machinery would add.

    private int findIndexByQName(String qName) {
        for (int i = 0; i < attrCount; i++) {
            if (attrs[i].qName.equals(qName)) {
                return i;
            }
        }
        return -1;
    }

    private int findIndexByExpandedName(String uri, String localName) {
        for (int i = 0; i < attrCount; i++) {
            Attr attr = attrs[i];
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
        return (index < 0 || index >= attrCount) ? null : attrs[index].uri;
    }

    @Override
    public String getLocalName(int index) {
        return (index < 0 || index >= attrCount) ? null : attrs[index].localName;
    }

    @Override
    public String getQName(int index) {
        return (index < 0 || index >= attrCount) ? null : attrs[index].qName;
    }

    @Override
    public String getType(int index) {
        return (index < 0 || index >= attrCount) ? null : attrs[index].type;
    }

    @Override
    public String getValue(int index) {
        return (index < 0 || index >= attrCount) ? null : attrs[index].value;
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
        return i < 0 ? null : attrs[i].type;
    }

    @Override
    public String getType(String qName) {
        int i = findIndexByQName(qName);
        return i < 0 ? null : attrs[i].type;
    }

    @Override
    public String getValue(String uri, String localName) {
        int i = findIndexByExpandedName(uri, localName);
        return i < 0 ? null : attrs[i].value;
    }

    @Override
    public String getValue(String qName) {
        int i = findIndexByQName(qName);
        return i < 0 ? null : attrs[i].value;
    }

    @Override
    public boolean isDeclared(int index) {
        if (index < 0 || index >= attrCount) {
            throw new ArrayIndexOutOfBoundsException(index);
        }
        return attrs[index].declared;
    }

    @Override
    public boolean isDeclared(String qName) {
        int i = findIndexByQName(qName);
        if (i < 0) {
            throw new IllegalArgumentException("Unknown attribute: " + qName);
        }
        return attrs[i].declared;
    }

    @Override
    public boolean isDeclared(String uri, String localName) {
        int i = findIndexByExpandedName(uri, localName);
        if (i < 0) {
            throw new IllegalArgumentException("Unknown attribute: {" + uri + "}" + localName);
        }
        return attrs[i].declared;
    }

    @Override
    public boolean isSpecified(int index) {
        if (index < 0 || index >= attrCount) {
            throw new ArrayIndexOutOfBoundsException(index);
        }
        return attrs[index].specified;
    }

    @Override
    public boolean isSpecified(String qName) {
        int i = findIndexByQName(qName);
        if (i < 0) {
            throw new IllegalArgumentException("Unknown attribute: " + qName);
        }
        return attrs[i].specified;
    }

    @Override
    public boolean isSpecified(String uri, String localName) {
        int i = findIndexByExpandedName(uri, localName);
        if (i < 0) {
            throw new IllegalArgumentException("Unknown attribute: {" + uri + "}" + localName);
        }
        return attrs[i].specified;
    }

}

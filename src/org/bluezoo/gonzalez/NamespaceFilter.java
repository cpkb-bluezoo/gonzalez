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
import java.util.Arrays;
import java.util.HashMap;

import org.xml.sax.Locator;
import org.xml.sax.SAXException;

/**
 * {@link XMLHandler} filter that makes a parse namespace aware. It reroutes
 * {@code xmlns}/{@code xmlns:prefix} attributes to {@link
 * XMLHandler#namespace(String, String)} declarations on a delegate handler,
 * enforces the constraints of Namespaces in XML on declarations and on
 * element and attribute names (see below), and passes every other event
 * straight through unchanged. {@link Scanner} always reports {@code xmlns}
 * as a plain attribute (matching namespace-unaware behaviour), so a
 * namespace-aware consumer (such as {@code new SAXAdapter(true)}) needs
 * this filter in front of it to translate; a namespace-unaware pipeline
 * simply omits this filter and feeds the scanner's output straight to the
 * consumer - namespace filtering costs nothing when not wanted.
 * <p>
 * No event is buffered: each is passed on as it arrives. The only content
 * held is the value of the one
 * {@code xmlns}/{@code xmlns:prefix} attribute currently being streamed
 * in, exactly as {@link SAXAdapter} itself buffers a multi-chunk attribute
 * value - the first chunk with {@code end=true} needs no buffering at all.
 * A namespace name is reported whole, so this is the one attribute value
 * the native pipeline has to hold, and {@link #setMaxNamespaceLength}
 * bounds it.
 * <p>
 * Namespace Constraint validation (reserved URI bindings, the empty-prefix
 * rule, the {@code xml}/{@code xmlns} prefix rules, and the XML 1.1-only
 * prefix-unbinding rule) happens here, mirroring {@code ContentParser
 * .processNamespaceAttribute} in the old tokenizer-based pipeline - this
 * filter is the component that recognises and interprets a namespace
 * declaration, so it is the natural place to reject an invalid one, exactly
 * as it is the natural place to translate a valid one. The downstream
 * {@link #namespace(String, String)} consumer can therefore keep trusting
 * that what it receives is already valid, including the non-fatal "namespace name should be an absolute
 * URI" advisory ({@link #validateNamespaceURI}) - a recoverable warning via
 * {@link XMLHandler#error}, not a WFC/NSC fatal error.
 * <p>
 * <b>Names.</b> The constraints on element and attribute names are
 * enforced here too, so that a document which is not namespace-well-formed
 * is rejected whatever consumes the events - a native {@link XMLHandler}
 * as much as {@link SAXAdapter}, which resolves names for its own purposes
 * but is not what decides whether they are legal. Every element and
 * attribute name must be a QName (Namespaces in XML 1.0, sections 3 and
 * 4: at most one colon, with a name on each side of it), a prefix must be
 * bound by a declaration in scope (section 5, "Prefix Declared"), and no
 * two attributes of an element may have the same expanded name (section
 * 6.3). The first is checked as each name is reported. The other two wait
 * for {@link #endAttributes()}, since a prefix may be declared later in
 * the start tag than it is used; by then the attributes concerned have
 * already been passed on, which is in the nature of a streaming interface.
 * <p>
 * For this the filter keeps the namespaces bound in each open element, the
 * default one among them, and the names of the prefixed attributes of the
 * element being started. What it finds out about a name it remembers, so a
 * name is examined once and recognised after that, and one with no prefix
 * needs nothing more.
 * <p>
 * <b>What names mean.</b> Since the filter knows what is bound, it is also
 * the place that says what a name stands for, and a {@link SAXAdapter} behind
 * it is told, rather than keeping the bindings again itself: the namespace
 * and local name of the element when its start tag ends (the default
 * namespace, for a name with no prefix, which costs no lookup), the same for
 * each prefixed attribute, and what the element declares, when it is
 * asked ({@link #declarationCount}). A handler that is not an adapter gets
 * {@link XMLHandler#namespace} events and works it out for itself.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class NamespaceFilter implements XMLHandler, CharArrayHandler {

    private final XMLHandler delegate;
    /** The delegate as a {@link CharArrayHandler}, or null if it is not one. */
    private final CharArrayHandler delegateChars;
    /** The delegate as a {@link SAXAdapter}, or null if it is not one. Such a
     *  delegate is told what each element and prefixed attribute means in
     *  namespace terms, which is settled here, and it can ask for what is
     *  declared, rather than keeping it all again itself. */
    private final SAXAdapter saxAdapter;
    private boolean xml11;
    private boolean namespacePrefixes;
    private boolean xmlnsUris;

    // Set by startAttribute(), consulted by attributeValueContent() for the
    // duration of the current attribute only.
    private String currentAttrName;
    private String currentAttrType;
    private boolean currentAttrDeclared;
    private boolean currentAttrSpecified;
    private boolean currentIsNamespaceDecl;
    private boolean valueFirstChunk;
    private final StringBuilder valueBuilder = new StringBuilder();

    /** The longest namespace name accepted, in characters. */
    private int maxNamespaceLength = Integer.MAX_VALUE;

    // ===== Bindings in scope =====
    //
    // The declarations of every open element, innermost last, the default
    // namespace (the empty prefix) among them. It has no bearing on whether
    // a name is legal, but it is what a name with no prefix means, and
    // keeping it with the rest makes one record of what an element declares.
    // A prefix is looked up by searching
    // them backwards, which for the handful of bindings a document
    // usually has is quicker than anything cleverer and allocates
    // nothing. Past MAP_THRESHOLD of them a map takes over, kept in step
    // as bindings come and go, so that a document cannot make every
    // lookup as slow as it likes by declaring thousands of prefixes.

    private static final int MAP_THRESHOLD = 32;

    /** The namespace of names with no prefix: what the innermost
     *  {@code xmlns} in scope says, or empty. Elements with no prefix are in
     *  it; attributes with none are in no namespace. */
    private String defaultNamespace = "";

    private String[] declaredPrefixes = new String[8];
    private String[] declaredURIs = new String[8];
    private int declaredCount;

    // Two attributes can only have the same expanded name without having
    // the same name if two prefixes are bound to one namespace, which few
    // documents do. Each declaration is marked if its namespace was
    // already declared by another still open, and attributes are only
    // compared while there is such a declaration. The mark errs on the
    // side of comparing: it is set for a binding that has since been
    // hidden, and without looking once there are too many to look through.
    private boolean[] declaredAgain = new boolean[8];
    private int declaredAgainCount;

    /** While {@link #mapInUse}: what each prefix is bound to now. */
    private HashMap<String, String> boundMap;
    private boolean mapInUse;
    /** While {@link #mapInUse}: for each declaration, what its prefix was
     *  bound to before, to put back when its element ends. */
    private String[] previousURIs = new String[8];

    /** How many elements are open, counting the one being started. */
    private int depth;

    // Most elements declare nothing, so a scope is only recorded for one
    // that does: its depth, and how many declarations there were before.
    private int[] scopeDepths = new int[8];
    private int[] scopeStarts = new int[8];
    private int scopeCount;
    /** The depth of the innermost element with a declaration, or zero. */
    private int innermostScopeDepth;

    // ===== What is known about each name =====
    //
    // The scanner interns names, so the same name is nearly always the
    // same String. This remembers, by identity, what kind of name each
    // one in use is, so that a name is only examined character by
    // character the first time it is seen; and, for one with a prefix,
    // the namespace the prefix is bound to, which holds until the
    // bindings next change (the generation it was recorded for).

    private static final int CACHE_SIZE = 256;

    /** A name with no colon in it. Any kind that is not negative is the
     *  index of the colon in a name that has a prefix. */
    private static final int KIND_UNPREFIXED = -1;
    /** {@code xmlns} or {@code xmlns:prefix}: as an attribute, a
     *  declaration; as an element name, illegal. */
    private static final int KIND_DECLARATION = -2;

    private final String[] cacheNames = new String[CACHE_SIZE];
    private final int[] cacheKinds = new int[CACHE_SIZE];
    private final String[] cacheURIs = new String[CACHE_SIZE];
    /** For a name with a prefix, the part after the colon. */
    private final String[] cacheLocals = new String[CACHE_SIZE];
    private final int[] cacheGenerations = new int[CACHE_SIZE];
    /** Never zero, which marks an entry with no namespace recorded. */
    private int generation = 1;

    // ===== The element being started =====

    /** Whether its name or any of its attributes' has a prefix: if not,
     *  there is nothing to check when its start tag ends. */
    private boolean prefixedNameSeen;

    /** Its name, if that has a prefix (otherwise null), with the index
     *  of the colon and where the name is remembered. */
    private String elementName;
    private int elementColon;
    private int elementSlot;

    /** Those of its attributes that have a prefix, in the order reported,
     *  each with the index of its colon. Emptied once they have been
     *  checked, at the end of the start tag. */
    private String[] prefixedNames = new String[8];
    private int[] prefixedColons = new int[8];
    private int[] prefixedSlots = new int[8];
    private int prefixedCount;

    /** Scratch for {@link #checkPrefixedAttributes}: the namespace of each
     *  of {@link #prefixedNames}, and a hash of its expanded name. */
    private String[] prefixedURIs = new String[8];
    private int[] prefixedHashes = new int[8];

    /**
     * Creates a new filter.
     *
     * @param delegate the namespace-aware downstream handler
     * @param xml11 whether the document is being processed under XML 1.1
     *              rules (permits {@code xmlns:prefix=""} prefix-unbinding)
     */
    NamespaceFilter(XMLHandler delegate, boolean xml11) {
        this.delegate = delegate;
        this.delegateChars = (delegate instanceof CharArrayHandler)
                ? (CharArrayHandler) delegate : null;
        this.saxAdapter = (delegate instanceof SAXAdapter) ? (SAXAdapter) delegate : null;
        if (saxAdapter != null) {
            saxAdapter.setNamespaces(this);
        }
        this.xml11 = xml11;
    }

    void setNamespacePrefixes(boolean namespacePrefixes) {
        this.namespacePrefixes = namespacePrefixes;
    }

    /**
     * Limits the length of a namespace name. A declaration whose value is
     * longer is a fatal error, raised as soon as the value has grown past
     * the limit and not when it ends.
     *
     * @param maxNamespaceLength the limit in characters; zero or less for
     *        none
     */
    void setMaxNamespaceLength(int maxNamespaceLength) {
        this.maxNamespaceLength = (maxNamespaceLength > 0) ? maxNamespaceLength : Integer.MAX_VALUE;
    }

    void setXmlnsUris(boolean xmlnsUris) {
        this.xmlnsUris = xmlnsUris;
        if (saxAdapter != null) {
            saxAdapter.setXmlnsUris(xmlnsUris);
        }
    }

    @Override
    public void setLocator(Locator locator) {
        delegate.setLocator(locator);
    }

    /** Updates {@link #xml11} from the actual declared document version
     *  (see {@code Scanner}'s constructor-time value passed to this class's
     *  own constructor, which is only ever the initial default - this is
     *  what keeps it current once the real {@code XMLDecl} is parsed), and
     *  relays it on downstream. */
    @Override
    public void setXml11(boolean xml11) {
        this.xml11 = xml11;
        delegate.setXml11(xml11);
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
        depth++;
        int slot = qName.hashCode() & (CACHE_SIZE - 1);
        int kind = (cacheNames[slot] == qName) ? cacheKinds[slot] : examine(qName, slot);
        if (kind != KIND_UNPREFIXED) {
            if (kind == KIND_DECLARATION) {
                throw illegalElementName(qName);
            }
            elementName = qName;
            elementColon = kind;
            elementSlot = slot;
            prefixedNameSeen = true;
        }
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
        startAttribute(name, type, false, true);
    }

    @Override
    public void startAttribute(String name, String type, boolean declared, boolean specified)
            throws SAXException {
        int slot = name.hashCode() & (CACHE_SIZE - 1);
        int kind = (cacheNames[slot] == name) ? cacheKinds[slot] : examine(name, slot);
        currentIsNamespaceDecl = (kind == KIND_DECLARATION);
        if (currentIsNamespaceDecl) {
            // Only a declaration reads these back, when its value is complete,
            // so no other attribute pays for storing them (a reference stored
            // in an object that is long-lived is not free under G1).
            currentAttrName = name;
            currentAttrType = type;
            currentAttrDeclared = declared;
            currentAttrSpecified = specified;
            valueFirstChunk = true;
        } else {
            if (kind >= 0) {
                notePrefixedAttribute(name, kind, slot);
            }
            delegate.startAttribute(name, type, declared, specified);
        }
    }

    @Override
    public boolean takesCharArrays() {
        return delegateChars != null && delegateChars.takesCharArrays();
    }

    @Override
    public void characters(char[] chars, int start, int length, boolean ignorable, boolean end)
            throws SAXException {
        delegateChars.characters(chars, start, length, ignorable, end);
    }

    @Override
    public void attribute(String name, String type, boolean declared, boolean specified,
            char[] chars, int start, int length) throws SAXException {
        int slot = name.hashCode() & (CACHE_SIZE - 1);
        int kind = (cacheNames[slot] == name) ? cacheKinds[slot] : examine(name, slot);
        if (kind == KIND_DECLARATION) {
            if (length > maxNamespaceLength) {
                throw namespaceTooLong();
            }
            currentAttrName = name;
            currentAttrType = type;
            currentAttrDeclared = declared;
            currentAttrSpecified = specified;
            declareNamespace(name, new String(chars, start, length));
            return;
        }
        if (kind >= 0) {
            notePrefixedAttribute(name, kind, slot);
        }
        if (delegateChars != null) {
            delegateChars.attribute(name, type, declared, specified, chars, start, length);
        } else {
            delegate.startAttribute(name, type, declared, specified);
            delegate.attributeValueContent(CharBuffer.wrap(chars, start, length), true);
        }
    }

    @Override
    public void attributeValueChars(char[] chars, int start, int length, boolean end)
            throws SAXException {
        if (!currentIsNamespaceDecl && delegateChars != null) {
            delegateChars.attributeValueChars(chars, start, length, end);
        } else {
            attributeValueContent(CharBuffer.wrap(chars, start, length), end);
        }
    }

    @Override
    public void attributeValueContent(CharBuffer value, boolean end) throws SAXException {
        if (!currentIsNamespaceDecl) {
            delegate.attributeValueContent(value, end);
            return;
        }
        if (valueFirstChunk && end) {
            if (value.remaining() > maxNamespaceLength) {
                throw namespaceTooLong();
            }
            declareNamespace(currentAttrName, value.toString());
            return;
        }
        if (valueFirstChunk) {
            valueBuilder.setLength(0);
            valueFirstChunk = false;
        }
        if (value.remaining() > maxNamespaceLength - valueBuilder.length()) {
            throw namespaceTooLong();
        }
        valueBuilder.append(value);
        if (end) {
            declareNamespace(currentAttrName, valueBuilder.toString());
        }
    }

    private SAXException namespaceTooLong() throws SAXException {
        return delegate.fatalError("Namespace name length limit (" + maxNamespaceLength + ") exceeded");
    }

    private void declareNamespace(String attrName, String uri) throws SAXException {
        String prefix;
        if ("xmlns".equals(attrName)) {
            if (NamespaceScopeTracker.XML_NAMESPACE_URI.equals(uri)) {
                throw delegate.fatalError(
                        "Cannot bind default namespace to reserved XML namespace URI");
            }
            if (NamespaceScopeTracker.XMLNS_NAMESPACE_URI.equals(uri)) {
                throw delegate.fatalError(
                        "Cannot bind default namespace to reserved xmlns namespace URI");
            }
            validateNamespaceURI(uri);
            prefix = "";
            bind(prefix, uri);
        } else {
            prefix = attrName.substring(6); // skip "xmlns:"
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
            validateNamespaceURI(uri);
            bind(prefix, uri);
        }
        delegate.namespace(prefix, uri);
        if (namespacePrefixes) {
            delegate.startAttribute(attrName, currentAttrType, currentAttrDeclared, currentAttrSpecified);
            delegate.attributeValueContent(CharBuffer.wrap(uri), true);
        }
    }

    /**
     * Validates a namespace name per Namespaces in XML, mirroring {@code
     * ContentParser.validateNamespaceURI} in the old tokenizer-based
     * pipeline exactly (both checks are recoverable {@link
     * XMLHandler#error}s, not fatal - Namespaces 1.0 Third Edition merely
     * deprecates a relative reference as a namespace name, and notes that
     * non-ASCII IRIs are not universally interoperable, rather than
     * outlawing either outright). An empty {@code uri} (prefix unbinding)
     * is never validated - there is no namespace name to judge.
     */
    private void validateNamespaceURI(String uri) throws SAXException {
        if (uri.isEmpty()) {
            return;
        }
        if (!xml11 && !isAsciiOnly(uri)) {
            delegate.error("Namespace name '" + uri + "' is an IRI, not a URI (Namespaces in XML 1.0 \u00a72)");
        }
        if (!isAbsoluteURI(uri)) {
            delegate.error("Namespace name '" + uri
                    + "' is not an absolute URI (Namespaces in XML 1.0 \u00a72, deprecated)");
        }
    }

    /** Per RFC 3986, an absolute URI has a scheme ({@code [a-zA-Z]
     *  [a-zA-Z0-9+.-]*}) followed by {@code ':'}. */
    private static boolean isAbsoluteURI(String uri) {
        int colonIndex = uri.indexOf(':');
        if (colonIndex <= 0) {
            return false;
        }
        char first = uri.charAt(0);
        if (!((first >= 'a' && first <= 'z') || (first >= 'A' && first <= 'Z'))) {
            return false;
        }
        for (int i = 1; i < colonIndex; i++) {
            char c = uri.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '+'
                    || c == '-' || c == '.')) {
                return false;
            }
        }
        return true;
    }

    private static boolean isAsciiOnly(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) > 0x7F) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void endAttributes() throws SAXException {
        if (saxAdapter == null) {
            if (prefixedNameSeen) {
                checkPrefixedNames();
            }
            delegate.endAttributes();
            return;
        }
        // An element with no prefix is in the default namespace, and is its
        // own local name, which the adapter has: nothing is looked up.
        String uri = defaultNamespace;
        String localName = null;
        if (prefixedNameSeen) {
            String name = elementName;
            int colon = elementColon;
            int slot = elementSlot;
            checkPrefixedNames();
            if (name != null) {
                uri = namespaceOf(name, colon, slot);
                localName = cacheLocals[slot];
            }
        }
        saxAdapter.endAttributes(uri, localName);
    }

    // ===== What the adapter asks =====

    /**
     * The number of declarations the element being started or ended makes.
     * Meaningful to the delegate while it is told that the start tag has
     * ended, and while it is told that the element has.
     */
    int declarationCount() {
        return (innermostScopeDepth == depth) ? declaredCount - scopeStarts[scopeCount - 1] : 0;
    }

    /** The prefix of one of the declarations of {@link #declarationCount}, in the order made. */
    String declarationPrefix(int index) {
        return declaredPrefixes[scopeStarts[scopeCount - 1] + index];
    }

    /** The namespace of one of the declarations of {@link #declarationCount}, in the order made. */
    String declarationURI(int index) {
        return declaredURIs[scopeStarts[scopeCount - 1] + index];
    }

    /**
     * Checks the names of the element being started that have a prefix,
     * now that every declaration of its start tag is known.
     */
    private void checkPrefixedNames() throws SAXException {
        prefixedNameSeen = false;
        if (prefixedCount != 0) {
            checkPrefixedAttributes();
            prefixedCount = 0;
        }
        String name = elementName;
        if (name != null) {
            elementName = null;
            if (namespaceOf(name, elementColon, elementSlot) == null) {
                throw unboundPrefix(name, elementColon);
            }
        }
    }

    // ===== Names =====

    /**
     * Works out the kind of a name not seen before, or not lately, and
     * remembers it in the given slot: {@link #KIND_UNPREFIXED}, {@link
     * #KIND_DECLARATION}, or the index of the colon in a name with a
     * prefix. A name that is not a QName at all (Namespaces in XML 1.0,
     * section 4) is rejected.
     */
    private int examine(String name, int slot) throws SAXException {
        int kind;
        if ("xmlns".equals(name) || name.startsWith("xmlns:")) {
            kind = KIND_DECLARATION;
        } else {
            kind = name.indexOf(':');
            if (kind >= 0) {
                checkPrefixedQName(name, kind);
                cacheLocals[slot] = name.substring(kind + 1);
            }
        }
        cacheNames[slot] = name;
        cacheKinds[slot] = kind;
        cacheGenerations[slot] = 0;
        return kind;
    }

    /**
     * Checks that a name with a colon in it is a QName: one colon, with
     * a name on each side of it.
     */
    private void checkPrefixedQName(String name, int colon) throws SAXException {
        if (name.indexOf(':', colon + 1) >= 0) {
            throw delegate.fatalError("Illegal QName '" + name + "': QNames may contain at most one colon");
        }
        if (colon == 0) {
            throw delegate.fatalError("Illegal QName '" + name + "': QName cannot start with colon");
        }
        if (colon == name.length() - 1) {
            throw delegate.fatalError("Illegal QName '" + name + "': QName cannot end with colon");
        }
    }

    /** The error for an element named {@code xmlns} or given that prefix,
     *  which is reserved for declarations (section 3). */
    private SAXException illegalElementName(String name) throws SAXException {
        if ("xmlns".equals(name)) {
            return delegate.fatalError("Illegal QName: 'xmlns' cannot be used as element name");
        }
        checkPrefixedQName(name, 5);
        return delegate.fatalError("Illegal QName '" + name + "': 'xmlns:' prefix cannot be used in element names");
    }

    private void notePrefixedAttribute(String name, int colon, int slot) {
        int count = prefixedCount;
        if (count == prefixedNames.length) {
            prefixedNames = Arrays.copyOf(prefixedNames, count * 2);
            prefixedColons = Arrays.copyOf(prefixedColons, count * 2);
            prefixedSlots = Arrays.copyOf(prefixedSlots, count * 2);
            prefixedURIs = Arrays.copyOf(prefixedURIs, count * 2);
            prefixedHashes = Arrays.copyOf(prefixedHashes, count * 2);
        }
        prefixedNames[count] = name;
        prefixedColons[count] = colon;
        prefixedSlots[count] = slot;
        prefixedCount = count + 1;
        prefixedNameSeen = true;
    }

    /** The most prefixed attributes that are compared with one another
     *  directly; more than this are compared by hash first. */
    private static final int FEW_ATTRIBUTES = 8;

    /**
     * Checks the prefixed attributes of the element being started: that
     * each prefix is bound, and that no two of them have the same local
     * name in the same namespace. An attribute with no prefix is in no
     * namespace, so it can only repeat another by having the same name,
     * which the scanner has already ruled out.
     */
    private void checkPrefixedAttributes() throws SAXException {
        int count = prefixedCount;
        if (count == 1 || declaredAgainCount == 0) {
            // Nothing to compare: no two prefixes share a namespace.
            for (int i = 0; i < count; i++) {
                String uri = namespaceOf(prefixedNames[i], prefixedColons[i], prefixedSlots[i]);
                if (uri == null) {
                    throw unboundPrefix(prefixedNames[i], prefixedColons[i]);
                }
                if (saxAdapter != null) {
                    saxAdapter.resolvedAttribute(prefixedNames[i], uri, cacheLocals[prefixedSlots[i]]);
                }
            }
            return;
        }
        // With many, a hash of each expanded name is compared first, so
        // that however alike the names are made, two are only compared
        // character by character when they are very likely the same.
        boolean hashed = count > FEW_ATTRIBUTES;
        for (int i = 0; i < count; i++) {
            String name = prefixedNames[i];
            int colon = prefixedColons[i];
            String uri = namespaceOf(name, colon, prefixedSlots[i]);
            if (uri == null) {
                throw unboundPrefix(name, colon);
            }
            prefixedURIs[i] = uri;
            if (saxAdapter != null) {
                saxAdapter.resolvedAttribute(name, uri, cacheLocals[prefixedSlots[i]]);
            }
            int hash = 0;
            if (hashed) {
                hash = uri.hashCode();
                for (int k = colon + 1; k < name.length(); k++) {
                    hash = 31 * hash + name.charAt(k);
                }
                prefixedHashes[i] = hash;
            }
            for (int j = 0; j < i; j++) {
                String other = prefixedURIs[j];
                // A String's own hash is kept once computed, so this
                // settles nearly every pair without comparing anything.
                if ((hashed ? prefixedHashes[j] == hash : other.hashCode() == uri.hashCode())
                        && uri.equals(other)
                        && sameLocalName(name, colon, prefixedNames[j], prefixedColons[j])) {
                    throw delegate.fatalError("Duplicate attribute by expanded name: {" + uri + "}"
                            + name.substring(colon + 1) + " (qName: " + name + ")");
                }
            }
        }
    }

    private static boolean sameLocalName(String a, int aColon, String b, int bColon) {
        int length = a.length() - aColon - 1;
        return length == b.length() - bColon - 1 && a.regionMatches(aColon + 1, b, bColon + 1, length);
    }

    private SAXException unboundPrefix(String name, int colon) throws SAXException {
        return delegate.fatalError("Unbound namespace prefix: " + name.substring(0, colon));
    }

    /**
     * Returns the namespace that the prefix of a name is bound to, or
     * null if it is not bound.
     *
     * @param name a qualified name with a prefix
     * @param colon the index of the colon in it
     * @param slot where the name is remembered, if it still is
     */
    private String namespaceOf(String name, int colon, int slot) {
        if (cacheNames[slot] == name && cacheGenerations[slot] == generation) {
            return cacheURIs[slot];
        }
        String uri = lookUp(name, colon);
        if (uri != null) {
            if (cacheNames[slot] != name) {
                cacheLocals[slot] = name.substring(colon + 1);
            }
            cacheNames[slot] = name;
            cacheKinds[slot] = colon;
            cacheURIs[slot] = uri;
            cacheGenerations[slot] = generation;
        }
        return uri;
    }

    private String lookUp(String name, int colon) {
        if (colon == 3 && name.startsWith("xml")) {
            // Bound without being declared (section 3), and a declaration
            // can only bind it to the same name.
            return NamespaceScopeTracker.XML_NAMESPACE_URI;
        }
        String uri = null;
        if (mapInUse) {
            uri = boundMap.get(name.substring(0, colon));
        } else {
            for (int i = declaredCount - 1; i >= 0; i--) {
                String prefix = declaredPrefixes[i];
                if (prefix.length() == colon && name.startsWith(prefix)) {
                    uri = declaredURIs[i];
                    break;
                }
            }
        }
        // Empty is how XML 1.1 takes a binding away again.
        return (uri == null || uri.isEmpty()) ? null : uri;
    }

    /** Binds a prefix for the element being started. */
    private void bind(String prefix, String uri) {
        if (innermostScopeDepth != depth) {
            // The first declaration of this element.
            if (scopeCount == scopeDepths.length) {
                scopeDepths = Arrays.copyOf(scopeDepths, scopeCount * 2);
                scopeStarts = Arrays.copyOf(scopeStarts, scopeCount * 2);
            }
            scopeDepths[scopeCount] = depth;
            scopeStarts[scopeCount] = declaredCount;
            scopeCount++;
            innermostScopeDepth = depth;
        }
        int count = declaredCount;
        if (count == declaredPrefixes.length) {
            declaredPrefixes = Arrays.copyOf(declaredPrefixes, count * 2);
            declaredURIs = Arrays.copyOf(declaredURIs, count * 2);
            declaredAgain = Arrays.copyOf(declaredAgain, count * 2);
            previousURIs = Arrays.copyOf(previousURIs, count * 2);
        }
        boolean again = (count >= MAP_THRESHOLD);
        if (!again && !prefix.isEmpty()) {
            int hash = uri.hashCode();
            for (int i = 0; i < count; i++) {
                String other = declaredURIs[i];
                if (other.hashCode() == hash && other.equals(uri)) {
                    again = true;
                    break;
                }
            }
        }
        declaredAgain[count] = again;
        if (again) {
            declaredAgainCount++;
        }
        declaredPrefixes[count] = prefix;
        declaredURIs[count] = uri;
        declaredCount = count + 1;
        if (prefix.isEmpty()) {
            defaultNamespace = uri;
        }
        if (mapInUse) {
            previousURIs[count] = boundMap.put(prefix, uri);
        } else if (declaredCount > MAP_THRESHOLD) {
            if (boundMap == null) {
                boundMap = new HashMap<String, String>();
            }
            for (int i = 0; i < declaredCount; i++) {
                previousURIs[i] = boundMap.put(declaredPrefixes[i], declaredURIs[i]);
            }
            mapInUse = true;
        }
        bindingsChanged();
    }

    /** Takes away the bindings made by the element now ending. */
    private void endScope() {
        int start = scopeStarts[--scopeCount];
        innermostScopeDepth = (scopeCount == 0) ? 0 : scopeDepths[scopeCount - 1];
        int count = declaredCount;
        boolean defaultEnded = false;
        while (count > start) {
            count--;
            if (declaredPrefixes[count].isEmpty()) {
                defaultEnded = true;
            }
            if (mapInUse) {
                String previous = previousURIs[count];
                if (previous == null) {
                    boundMap.remove(declaredPrefixes[count]);
                } else {
                    boundMap.put(declaredPrefixes[count], previous);
                }
                previousURIs[count] = null;
            }
            if (declaredAgain[count]) {
                declaredAgainCount--;
            }
            declaredPrefixes[count] = null;
            declaredURIs[count] = null;
        }
        declaredCount = start;
        if (defaultEnded) {
            // The default namespace the enclosing elements left.
            String outer = "";
            for (int i = start - 1; i >= 0; i--) {
                if (declaredPrefixes[i].isEmpty()) {
                    outer = declaredURIs[i];
                    break;
                }
            }
            defaultNamespace = outer;
        }
        if (mapInUse && start <= MAP_THRESHOLD) {
            boundMap.clear();
            mapInUse = false;
        }
        bindingsChanged();
    }

    /** Makes every namespace remembered by {@link #namespaceOf} out of date. */
    private void bindingsChanged() {
        if (++generation == 0) {
            // Come round again: an entry from the last time this number
            // was in use must not be taken for a current one.
            Arrays.fill(cacheGenerations, 0);
            generation = 1;
        }
    }

    @Override
    public void characters(CharBuffer text, boolean ignorable, boolean end) throws SAXException {
        delegate.characters(text, ignorable, end);
    }

    @Override
    public void endElement() throws SAXException {
        delegate.endElement();
        if (innermostScopeDepth == depth) {
            endScope();
        }
        depth--;
    }

    @Override
    public void startComment() throws SAXException {
        delegate.startComment();
    }

    @Override
    public void commentData(CharBuffer text, boolean end) throws SAXException {
        delegate.commentData(text, end);
    }

    @Override
    public void startCDATA() throws SAXException {
        delegate.startCDATA();
    }

    @Override
    public void endCDATA() throws SAXException {
        delegate.endCDATA();
    }

    @Override
    public void startDTD(String name, String publicId, String systemId) throws SAXException {
        delegate.startDTD(name, publicId, systemId);
    }

    @Override
    public void endDTD() throws SAXException {
        delegate.endDTD();
    }

    @Override
    public void startEntity(String name) throws SAXException {
        delegate.startEntity(name);
    }

    @Override
    public void endEntity(String name) throws SAXException {
        delegate.endEntity(name);
    }

    @Override
    public void notationDecl(String name, String publicId, String systemId) throws SAXException {
        delegate.notationDecl(name, publicId, systemId);
    }

    @Override
    public void unparsedEntityDecl(String name, String publicId, String systemId, String notationName)
            throws SAXException {
        delegate.unparsedEntityDecl(name, publicId, systemId, notationName);
    }

    @Override
    public void elementDecl(String name, String model) throws SAXException {
        delegate.elementDecl(name, model);
    }

    @Override
    public void attributeDecl(String eName, String aName, String type, String mode, String value)
            throws SAXException {
        delegate.attributeDecl(eName, aName, type, mode, value);
    }

    @Override
    public void internalEntityDecl(String name, String value) throws SAXException {
        delegate.internalEntityDecl(name, value);
    }

    @Override
    public void externalEntityDecl(String name, String publicId, String systemId) throws SAXException {
        delegate.externalEntityDecl(name, publicId, systemId);
    }

    @Override
    public void skippedEntity(String name) throws SAXException {
        delegate.skippedEntity(name);
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

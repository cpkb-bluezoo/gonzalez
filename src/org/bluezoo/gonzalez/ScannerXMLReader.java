/*
 * ScannerXMLReader.java
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
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.CharBuffer;

import org.xml.sax.ContentHandler;
import org.xml.sax.DTDHandler;
import org.xml.sax.EntityResolver;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXNotRecognizedException;
import org.xml.sax.SAXParseException;
import org.xml.sax.XMLReader;
import org.xml.sax.ext.LexicalHandler;

/**
 * Exposes the async scanning pipeline ({@link Scanner} -> {@link
 * NamespaceFilter} (if namespace-aware) -> {@link SAXAdapter}) as a
 * standard SAX2 {@link XMLReader}, so existing SAX-based test harnesses -
 * in particular the W3C xmlconf conformance suite driver,
 * {@code XMLConformanceTest} - can run against the new pipeline by simply
 * constructing this class instead of {@link Parser}, with no other change.
 * This is the Conformance hardening phase's entry point (see
 * ASYNC-PIPELINE.md); it is deliberately separate from {@link Parser} (the
 * still-default, production entry point) rather than a modification to it,
 * so the old pipeline's own 100% conformance baseline stays completely
 * unaffected while the new pipeline is brought up to parity.
 * <p>
 * Unlike {@link Parser}, this class does not stream bytes incrementally -
 * {@link #parse(InputSource)} reads the entire input into memory, decodes
 * it (BOM detection, then the declared {@code encoding} sniffed directly
 * from the XML/text declaration's own guaranteed-ASCII bytes, matching the
 * approach {@code ExternalEntityDecoder} uses for the old pipeline - see
 * that class for the fuller BOM/declaration state machine this is a
 * simplified, one-shot version of), and hands the whole decoded document to
 * {@link Scanner} in a single {@link Scanner#receive(CharBuffer)} call. This
 * is a test-harness-appropriate simplification, not a Scanner limitation -
 * Scanner itself is fully chunk-size-agnostic.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class ScannerXMLReader implements XMLReader {

    private static final String FEATURE_NAMESPACES = "http://xml.org/sax/features/namespaces";
    private static final String FEATURE_NAMESPACE_PREFIXES = "http://xml.org/sax/features/namespace-prefixes";
    private static final String FEATURE_EXTERNAL_GENERAL_ENTITIES =
            "http://xml.org/sax/features/external-general-entities";
    private static final String FEATURE_EXTERNAL_PARAMETER_ENTITIES =
            "http://xml.org/sax/features/external-parameter-entities";
    private static final String FEATURE_VALIDATION = "http://xml.org/sax/features/validation";
    private static final String PROPERTY_LEXICAL_HANDLER = "http://xml.org/sax/properties/lexical-handler";

    private ContentHandler contentHandler;
    private DTDHandler dtdHandler;
    private ErrorHandler errorHandler;
    private EntityResolver entityResolver;
    private LexicalHandler lexicalHandler;

    // SAX2 default feature values.
    private boolean namespaces = true;
    private boolean namespacePrefixes = false;
    private boolean externalGeneralEntities = true;
    private boolean externalParameterEntities = true;
    private boolean validation = false;

    @Override
    public boolean getFeature(String name) throws SAXNotRecognizedException {
        if (FEATURE_NAMESPACES.equals(name)) {
            return namespaces;
        }
        if (FEATURE_NAMESPACE_PREFIXES.equals(name)) {
            return namespacePrefixes;
        }
        if (FEATURE_EXTERNAL_GENERAL_ENTITIES.equals(name)) {
            return externalGeneralEntities;
        }
        if (FEATURE_EXTERNAL_PARAMETER_ENTITIES.equals(name)) {
            return externalParameterEntities;
        }
        if (FEATURE_VALIDATION.equals(name)) {
            return validation;
        }
        throw new SAXNotRecognizedException(name);
    }

    @Override
    public void setFeature(String name, boolean value) throws SAXNotRecognizedException {
        if (FEATURE_NAMESPACES.equals(name)) {
            namespaces = value;
        } else if (FEATURE_NAMESPACE_PREFIXES.equals(name)) {
            namespacePrefixes = value;
        } else if (FEATURE_EXTERNAL_GENERAL_ENTITIES.equals(name)) {
            externalGeneralEntities = value;
        } else if (FEATURE_EXTERNAL_PARAMETER_ENTITIES.equals(name)) {
            externalParameterEntities = value;
        } else if (FEATURE_VALIDATION.equals(name)) {
            validation = value;
        } else {
            throw new SAXNotRecognizedException(name);
        }
    }

    @Override
    public Object getProperty(String name) throws SAXNotRecognizedException {
        if (PROPERTY_LEXICAL_HANDLER.equals(name)) {
            return lexicalHandler;
        }
        throw new SAXNotRecognizedException(name);
    }

    @Override
    public void setProperty(String name, Object value) {
        if (PROPERTY_LEXICAL_HANDLER.equals(name)) {
            lexicalHandler = (LexicalHandler) value;
            return;
        }
        // Lenient rather than throwing SAXNotRecognizedException for anything
        // else (e.g. "http://javax.xml.XMLConstants/property/accessExternalDTD",
        // which test harnesses commonly set defensively but which isn't a
        // core SAX2 property): a harness that sets a property it considers
        // optional/best-effort shouldn't be broken by a strict parser that
        // doesn't happen to support it.
    }

    @Override
    public void setEntityResolver(EntityResolver resolver) {
        this.entityResolver = resolver;
    }

    @Override
    public EntityResolver getEntityResolver() {
        return entityResolver;
    }

    @Override
    public void setDTDHandler(DTDHandler handler) {
        this.dtdHandler = handler;
    }

    @Override
    public DTDHandler getDTDHandler() {
        return dtdHandler;
    }

    @Override
    public void setContentHandler(ContentHandler handler) {
        this.contentHandler = handler;
    }

    @Override
    public ContentHandler getContentHandler() {
        return contentHandler;
    }

    @Override
    public void setErrorHandler(ErrorHandler handler) {
        this.errorHandler = handler;
    }

    @Override
    public ErrorHandler getErrorHandler() {
        return errorHandler;
    }

    @Override
    public void parse(String systemId) throws IOException, SAXException {
        parse(new InputSource(systemId));
    }

    @Override
    public void parse(InputSource input) throws IOException, SAXException {
        if (input == null) {
            throw new IllegalArgumentException("InputSource cannot be null");
        }
        byte[] bytes = readAllBytes(input);
        char[] rawChars = XmlDeclUtil.decodeBytes(bytes, input.getEncoding());
        boolean xml11 = XmlDeclUtil.declaresXml11(rawChars);

        try {
            char[] chars = XmlDeclUtil.stripXmlDeclaration(rawChars);

            SAXAdapter adapter = new SAXAdapter(namespaces);
            adapter.setContentHandler(contentHandler);
            adapter.setLexicalHandler(lexicalHandler);
            XMLHandler target = namespaces ? new NamespaceFilter(adapter, xml11) : adapter;

            Scanner scanner = new Scanner(target, xml11, entityResolver, input.getSystemId());
            scanner.receive(CharBuffer.wrap(chars));
            scanner.close();
        } catch (SAXException e) {
            SAXParseException spe = toSAXParseException(e, input.getPublicId(), input.getSystemId());
            if (errorHandler != null) {
                errorHandler.fatalError(spe);
            }
            throw spe;
        }
    }

    private static SAXParseException toSAXParseException(SAXException e, String publicId, String systemId) {
        if (e instanceof SAXParseException) {
            return (SAXParseException) e;
        }
        // Scanner does not track line/column position yet (a known,
        // documented gap - see ASYNC-PIPELINE.md); -1/-1 is the standard SAX
        // convention for "position unknown", not a bug in this translation.
        return new SAXParseException(e.getMessage(), publicId, systemId, -1, -1, e);
    }

    private static byte[] readAllBytes(InputSource input) throws IOException, SAXException {
        InputStream in = input.getByteStream();
        boolean opened = false;
        if (in == null) {
            String systemId = input.getSystemId();
            if (systemId == null) {
                throw new SAXException("InputSource must have a byte stream or a system ID");
            }
            in = new URL(systemId).openStream();
            opened = true;
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } finally {
            if (opened) {
                in.close();
            }
        }
    }

}

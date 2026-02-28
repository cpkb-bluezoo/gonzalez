/*
 * GonzalezTransformerHandler.java
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

package org.bluezoo.gonzalez.transform;

import org.bluezoo.gonzalez.transform.compiler.CompiledStylesheet;
import org.bluezoo.gonzalez.transform.runtime.OutputHandler;
import org.xml.sax.*;
import org.xml.sax.ext.LexicalHandler;

import javax.xml.transform.Result;
import javax.xml.transform.Transformer;
import javax.xml.transform.sax.SAXResult;
import javax.xml.transform.sax.TransformerHandler;
import javax.xml.transform.stream.StreamResult;

import java.util.HashMap;
import java.util.Map;

/**
 * SAX TransformerHandler implementation for push-based XSLT pipelines.
 *
 * <p>This handler receives SAX events from a source (such as the Gonzalez
 * parser) and transforms them through an XSLT stylesheet, sending the
 * result to a {@link SAXResult} or {@link StreamResult}.
 *
 * <p>The delegate {@link GonzalezTransformHandler} is created lazily in
 * {@link #startDocument()} once both the templates and result are
 * available.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public class GonzalezTransformerHandler implements TransformerHandler, LexicalHandler {

    /** The compiled templates, or null for identity transform. */
    private final GonzalezTemplates templates;

    /** The transformer instance, exposes parameters and output properties. */
    private final GonzalezTransformer transformer;

    /** The result target for transformation output. */
    private Result result;

    /** The system identifier for the source document. */
    private String systemId;

    /**
     * The delegate that performs the actual transformation.
     * Created in startDocument() once the result is available.
     */
    private GonzalezTransformHandler delegate;

    /**
     * Creates a transformer handler.
     *
     * @param templates the templates (null for identity transform)
     */
    public GonzalezTransformerHandler(GonzalezTemplates templates) {
        this.templates = templates;
        CompiledStylesheet stylesheet =
            templates != null ? templates.getStylesheet() : null;
        this.transformer = new GonzalezTransformer(stylesheet);
    }

    /**
     * Sets the result target for the transformation output.
     *
     * @param result the result target
     * @throws IllegalArgumentException if the result is null
     */
    @Override
    public void setResult(Result result) throws IllegalArgumentException {
        if (result == null) {
            throw new IllegalArgumentException("Result must not be null");
        }
        this.result = result;
    }

    @Override
    public void setSystemId(String systemID) {
        this.systemId = systemID;
    }

    @Override
    public String getSystemId() {
        return systemId;
    }

    @Override
    public Transformer getTransformer() {
        return transformer;
    }

    // -- ContentHandler --

    @Override
    public void setDocumentLocator(Locator locator) {
        ensureDelegate();
        delegate.setDocumentLocator(locator);
    }

    @Override
    public void startDocument() throws SAXException {
        ensureDelegate();
        delegate.startDocument();
    }

    @Override
    public void endDocument() throws SAXException {
        delegate.endDocument();
    }

    @Override
    public void startPrefixMapping(String prefix, String uri)
            throws SAXException {
        delegate.startPrefixMapping(prefix, uri);
    }

    @Override
    public void endPrefixMapping(String prefix) throws SAXException {
        delegate.endPrefixMapping(prefix);
    }

    @Override
    public void startElement(String uri, String localName, String qName,
                             Attributes atts) throws SAXException {
        delegate.startElement(uri, localName, qName, atts);
    }

    @Override
    public void endElement(String uri, String localName, String qName)
            throws SAXException {
        delegate.endElement(uri, localName, qName);
    }

    @Override
    public void characters(char[] ch, int start, int length)
            throws SAXException {
        delegate.characters(ch, start, length);
    }

    @Override
    public void ignorableWhitespace(char[] ch, int start, int length)
            throws SAXException {
        delegate.ignorableWhitespace(ch, start, length);
    }

    @Override
    public void processingInstruction(String target, String data)
            throws SAXException {
        delegate.processingInstruction(target, data);
    }

    @Override
    public void skippedEntity(String name) throws SAXException {
        // Not relevant for XSLT processing
    }

    // -- DTDHandler --

    @Override
    public void notationDecl(String name, String publicId, String systemId)
            throws SAXException {
        // Not relevant for XSLT processing
    }

    @Override
    public void unparsedEntityDecl(String name, String publicId,
                                   String systemId, String notationName)
            throws SAXException {
        // Not relevant for XSLT processing
    }

    // -- LexicalHandler --

    @Override
    public void startDTD(String name, String publicId, String systemId)
            throws SAXException {
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
    public void startCDATA() throws SAXException {
        delegate.startCDATA();
    }

    @Override
    public void endCDATA() throws SAXException {
        delegate.endCDATA();
    }

    @Override
    public void comment(char[] ch, int start, int length)
            throws SAXException {
        delegate.comment(ch, start, length);
    }

    // -- Internal --

    /**
     * Creates the delegate transform handler if it has not been created yet.
     * Requires that {@link #setResult(Result)} has been called.
     *
     * @throws SAXException if the result has not been set or is unsupported
     */
    private void ensureDelegate() {
        if (delegate != null) {
            return;
        }

        if (result == null) {
            throw new IllegalStateException(
                "setResult() must be called before SAX events are sent");
        }

        OutputHandler outputHandler;
        try {
            outputHandler = createOutputHandler(result);
        } catch (Exception e) {
            throw new IllegalStateException(
                "Failed to create output handler: " + e.getMessage(), e);
        }

        CompiledStylesheet stylesheet =
            templates != null ? templates.getStylesheet() : null;

        Map<String, Object> parameters = new HashMap<String, Object>();
        delegate = new GonzalezTransformHandler(
            stylesheet, parameters, outputHandler);
    }

    /**
     * Creates an appropriate OutputHandler for the given result target.
     * Supports SAXResult (event forwarding) and StreamResult (serialization).
     */
    private OutputHandler createOutputHandler(Result target)
            throws Exception {
        if (target instanceof SAXResult) {
            ContentHandler ch = ((SAXResult) target).getHandler();
            return new GonzalezTransformHandler.ContentHandlerOutputAdapter(ch);
        }
        if (target instanceof StreamResult) {
            return transformer.createOutputHandler(target);
        }
        throw new IllegalArgumentException(
            "Unsupported result type: " + target.getClass().getName());
    }

}

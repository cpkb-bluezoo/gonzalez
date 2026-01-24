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

import org.xml.sax.*;
import org.xml.sax.ext.LexicalHandler;

import javax.xml.transform.Result;
import javax.xml.transform.Transformer;
import javax.xml.transform.sax.TransformerHandler;

/**
 * SAX TransformerHandler implementation.
 *
 * <p>This handler receives SAX events from a source document and transforms
 * them through an XSLT stylesheet to a result.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public class GonzalezTransformerHandler implements TransformerHandler, LexicalHandler {

    private final GonzalezTemplates templates;
    private final GonzalezTransformer transformer;
    private Result result;
    private String systemId;

    /**
     * Creates a transformer handler.
     *
     * @param templates the templates (null for identity transform)
     */
    public GonzalezTransformerHandler(GonzalezTemplates templates) {
        this.templates = templates;
        this.transformer = new GonzalezTransformer(
            templates != null ? templates.getStylesheet() : null);
    }

    @Override
    public void setResult(Result result) throws IllegalArgumentException {
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

    // ContentHandler implementation (delegates to internal handler)

    @Override
    public void setDocumentLocator(Locator locator) {
        // Store locator for error reporting
    }

    @Override
    public void startDocument() throws SAXException {
        // Begin transformation
    }

    @Override
    public void endDocument() throws SAXException {
        // Complete transformation
    }

    @Override
    public void startPrefixMapping(String prefix, String uri) throws SAXException {
        // Track namespace
    }

    @Override
    public void endPrefixMapping(String prefix) throws SAXException {
        // Namespace out of scope
    }

    @Override
    public void startElement(String uri, String localName, String qName, Attributes atts) 
            throws SAXException {
        // Process element start
    }

    @Override
    public void endElement(String uri, String localName, String qName) throws SAXException {
        // Process element end
    }

    @Override
    public void characters(char[] ch, int start, int length) throws SAXException {
        // Process text
    }

    @Override
    public void ignorableWhitespace(char[] ch, int start, int length) throws SAXException {
        // Process whitespace
    }

    @Override
    public void processingInstruction(String target, String data) throws SAXException {
        // Process PI
    }

    @Override
    public void skippedEntity(String name) throws SAXException {
        // Entity skipped
    }

    // DTDHandler implementation

    @Override
    public void notationDecl(String name, String publicId, String systemId) throws SAXException {
        // Notation declaration - typically not needed for XSLT
    }

    @Override
    public void unparsedEntityDecl(String name, String publicId, String systemId, 
                                   String notationName) throws SAXException {
        // Unparsed entity declaration - typically not needed for XSLT
    }

    // LexicalHandler implementation

    @Override
    public void startDTD(String name, String publicId, String systemId) throws SAXException {
    }

    @Override
    public void endDTD() throws SAXException {
    }

    @Override
    public void startEntity(String name) throws SAXException {
    }

    @Override
    public void endEntity(String name) throws SAXException {
    }

    @Override
    public void startCDATA() throws SAXException {
    }

    @Override
    public void endCDATA() throws SAXException {
    }

    @Override
    public void comment(char[] ch, int start, int length) throws SAXException {
        // Process comment
    }

}

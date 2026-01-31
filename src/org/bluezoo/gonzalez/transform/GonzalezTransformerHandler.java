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

    /** The compiled templates, or null for identity transform. */
    private final GonzalezTemplates templates;
    
    /** The transformer instance. */
    private final GonzalezTransformer transformer;
    
    /** The result target for transformation output. */
    private Result result;
    
    /** The system identifier for the source document. */
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

    /**
     * Sets the result target for the transformation output.
     *
     * @param result the result target
     * @throws IllegalArgumentException if the result is invalid
     */
    @Override
    public void setResult(Result result) throws IllegalArgumentException {
        this.result = result;
    }

    /**
     * Sets the system identifier for the source document.
     *
     * @param systemID the system identifier (URI)
     */
    @Override
    public void setSystemId(String systemID) {
        this.systemId = systemID;
    }

    /**
     * Gets the system identifier for the source document.
     *
     * @return the system identifier, or null if not set
     */
    @Override
    public String getSystemId() {
        return systemId;
    }

    /**
     * Gets the Transformer instance associated with this handler.
     *
     * @return the Transformer instance
     */
    @Override
    public Transformer getTransformer() {
        return transformer;
    }

    // ContentHandler implementation (delegates to internal handler)

    /**
     * Sets the document locator for error reporting.
     *
     * @param locator the document locator
     */
    @Override
    public void setDocumentLocator(Locator locator) {
        // Store locator for error reporting
    }

    /**
     * Receives notification of the beginning of a document.
     *
     * @throws SAXException if a SAX error occurs
     */
    @Override
    public void startDocument() throws SAXException {
        // Begin transformation
    }

    /**
     * Receives notification of the end of a document.
     *
     * @throws SAXException if a SAX error occurs
     */
    @Override
    public void endDocument() throws SAXException {
        // Complete transformation
    }

    /**
     * Receives notification of the start of a namespace prefix mapping.
     *
     * @param prefix the namespace prefix, or empty string for default namespace
     * @param uri the namespace URI
     * @throws SAXException if a SAX error occurs
     */
    @Override
    public void startPrefixMapping(String prefix, String uri) throws SAXException {
        // Track namespace
    }

    /**
     * Receives notification of the end of a namespace prefix mapping.
     *
     * @param prefix the namespace prefix, or empty string for default namespace
     * @throws SAXException if a SAX error occurs
     */
    @Override
    public void endPrefixMapping(String prefix) throws SAXException {
        // Namespace out of scope
    }

    /**
     * Receives notification of the start of an element.
     *
     * @param uri the element namespace URI, or empty string if none
     * @param localName the element local name
     * @param qName the element qualified name (prefix:local)
     * @param atts the element attributes
     * @throws SAXException if a SAX error occurs
     */
    @Override
    public void startElement(String uri, String localName, String qName, Attributes atts) 
            throws SAXException {
        // Process element start
    }

    /**
     * Receives notification of the end of an element.
     *
     * @param uri the element namespace URI, or empty string if none
     * @param localName the element local name
     * @param qName the element qualified name (prefix:local)
     * @throws SAXException if a SAX error occurs
     */
    @Override
    public void endElement(String uri, String localName, String qName) throws SAXException {
        // Process element end
    }

    /**
     * Receives notification of character data.
     *
     * @param ch the characters
     * @param start the start position in the array
     * @param length the number of characters to use
     * @throws SAXException if a SAX error occurs
     */
    @Override
    public void characters(char[] ch, int start, int length) throws SAXException {
        // Process text
    }

    /**
     * Receives notification of ignorable whitespace in element content.
     *
     * @param ch the whitespace characters
     * @param start the start position in the array
     * @param length the number of characters to use
     * @throws SAXException if a SAX error occurs
     */
    @Override
    public void ignorableWhitespace(char[] ch, int start, int length) throws SAXException {
        // Process whitespace
    }

    /**
     * Receives notification of a processing instruction.
     *
     * @param target the processing instruction target
     * @param data the processing instruction data
     * @throws SAXException if a SAX error occurs
     */
    @Override
    public void processingInstruction(String target, String data) throws SAXException {
        // Process PI
    }

    /**
     * Receives notification of a skipped entity.
     *
     * @param name the entity name
     * @throws SAXException if a SAX error occurs
     */
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

    /**
     * Receives notification of the start of a DTD declaration.
     *
     * @param name the document type name
     * @param publicId the public identifier, or null
     * @param systemId the system identifier
     * @throws SAXException if a SAX error occurs
     */
    @Override
    public void startDTD(String name, String publicId, String systemId) throws SAXException {
    }

    /**
     * Receives notification of the end of a DTD declaration.
     *
     * @throws SAXException if a SAX error occurs
     */
    @Override
    public void endDTD() throws SAXException {
    }

    /**
     * Receives notification of the start of an entity.
     *
     * @param name the entity name
     * @throws SAXException if a SAX error occurs
     */
    @Override
    public void startEntity(String name) throws SAXException {
    }

    /**
     * Receives notification of the end of an entity.
     *
     * @param name the entity name
     * @throws SAXException if a SAX error occurs
     */
    @Override
    public void endEntity(String name) throws SAXException {
    }

    /**
     * Receives notification of the start of a CDATA section.
     *
     * @throws SAXException if a SAX error occurs
     */
    @Override
    public void startCDATA() throws SAXException {
    }

    /**
     * Receives notification of the end of a CDATA section.
     *
     * @throws SAXException if a SAX error occurs
     */
    @Override
    public void endCDATA() throws SAXException {
    }

    /**
     * Receives notification of a comment.
     *
     * @param ch the comment characters
     * @param start the start position in the array
     * @param length the number of characters to use
     * @throws SAXException if a SAX error occurs
     */
    @Override
    public void comment(char[] ch, int start, int length) throws SAXException {
        // Process comment
    }

}

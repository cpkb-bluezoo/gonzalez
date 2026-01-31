/*
 * GonzalezTemplatesHandler.java
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

import javax.xml.transform.Templates;
import javax.xml.transform.sax.TemplatesHandler;

import org.xml.sax.Attributes;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;

import org.bluezoo.gonzalez.transform.compiler.StylesheetCompiler;
import org.bluezoo.gonzalez.transform.compiler.StylesheetResolver;

/**
 * SAX handler that builds Templates from stylesheet SAX events.
 *
 * <p>This allows stylesheets to be compiled from any SAX source, enabling
 * integration with the Gonzalez push parser.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public class GonzalezTemplatesHandler implements TemplatesHandler {

    /** The parent transformer factory. */
    private final GonzalezTransformerFactory factory;
    
    /** The stylesheet compiler, created lazily when systemId is known. */
    private StylesheetCompiler compiler;
    
    /** The system identifier for the stylesheet being compiled. */
    private String systemId;
    
    /** The compiled templates, created when getTemplates() is called. */
    private GonzalezTemplates templates;

    /**
     * Creates a templates handler.
     *
     * @param factory the parent factory
     */
    public GonzalezTemplatesHandler(GonzalezTransformerFactory factory) {
        this.factory = factory;
        // Compiler created lazily when systemId is known
    }

    /**
     * Ensures the compiler is created with current settings.
     */
    private StylesheetCompiler getCompiler() {
        if (compiler == null) {
            StylesheetResolver resolver = new StylesheetResolver(factory.getURIResolver());
            compiler = new StylesheetCompiler(resolver, systemId);
        }
        return compiler;
    }

    /**
     * Gets the compiled Templates object.
     *
     * <p>This method compiles the stylesheet from the SAX events that have
     * been received. It should be called after all SAX events have been
     * processed (after endDocument()).
     *
     * @return the compiled Templates object
     * @throws RuntimeException if stylesheet compilation fails
     */
    @Override
    public Templates getTemplates() {
        if (templates == null) {
            try {
                templates = new GonzalezTemplates(getCompiler().getCompiledStylesheet());
            } catch (javax.xml.transform.TransformerConfigurationException e) {
                throw new RuntimeException("Stylesheet validation failed: " + e.getMessage(), e);
            }
        }
        return templates;
    }

    /**
     * Sets the system identifier for the stylesheet being compiled.
     *
     * <p>This is used as the base URI for resolving relative references
     * in the stylesheet (imports, includes, etc.).
     *
     * @param systemID the system identifier (URI)
     */
    @Override
    public void setSystemId(String systemID) {
        this.systemId = systemID;
        // Reset compiler so it gets recreated with new systemId
        this.compiler = null;
    }

    /**
     * Gets the system identifier for the stylesheet being compiled.
     *
     * @return the system identifier, or null if not set
     */
    @Override
    public String getSystemId() {
        return systemId;
    }

    // Delegate all ContentHandler methods to compiler

    /**
     * Sets the document locator for error reporting.
     *
     * @param locator the document locator
     */
    @Override
    public void setDocumentLocator(Locator locator) {
        getCompiler().setDocumentLocator(locator);
    }

    /**
     * Receives notification of the beginning of a document.
     *
     * @throws SAXException if a SAX error occurs
     */
    @Override
    public void startDocument() throws SAXException {
        templates = null; // Reset
        getCompiler().startDocument();
    }

    /**
     * Receives notification of the end of a document.
     *
     * @throws SAXException if a SAX error occurs
     */
    @Override
    public void endDocument() throws SAXException {
        getCompiler().endDocument();
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
        getCompiler().startPrefixMapping(prefix, uri);
    }

    /**
     * Receives notification of the end of a namespace prefix mapping.
     *
     * @param prefix the namespace prefix, or empty string for default namespace
     * @throws SAXException if a SAX error occurs
     */
    @Override
    public void endPrefixMapping(String prefix) throws SAXException {
        getCompiler().endPrefixMapping(prefix);
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
        getCompiler().startElement(uri, localName, qName, atts);
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
        getCompiler().endElement(uri, localName, qName);
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
        getCompiler().characters(ch, start, length);
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
        getCompiler().ignorableWhitespace(ch, start, length);
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
        getCompiler().processingInstruction(target, data);
    }

    /**
     * Receives notification of a skipped entity.
     *
     * @param name the entity name
     * @throws SAXException if a SAX error occurs
     */
    @Override
    public void skippedEntity(String name) throws SAXException {
        getCompiler().skippedEntity(name);
    }

}

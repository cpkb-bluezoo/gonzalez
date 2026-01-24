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

    private final GonzalezTransformerFactory factory;
    private StylesheetCompiler compiler;
    private String systemId;
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

    @Override
    public Templates getTemplates() {
        if (templates == null) {
            templates = new GonzalezTemplates(getCompiler().getCompiledStylesheet());
        }
        return templates;
    }

    @Override
    public void setSystemId(String systemID) {
        this.systemId = systemID;
        // Reset compiler so it gets recreated with new systemId
        this.compiler = null;
    }

    @Override
    public String getSystemId() {
        return systemId;
    }

    // Delegate all ContentHandler methods to compiler

    @Override
    public void setDocumentLocator(Locator locator) {
        getCompiler().setDocumentLocator(locator);
    }

    @Override
    public void startDocument() throws SAXException {
        templates = null; // Reset
        getCompiler().startDocument();
    }

    @Override
    public void endDocument() throws SAXException {
        getCompiler().endDocument();
    }

    @Override
    public void startPrefixMapping(String prefix, String uri) throws SAXException {
        getCompiler().startPrefixMapping(prefix, uri);
    }

    @Override
    public void endPrefixMapping(String prefix) throws SAXException {
        getCompiler().endPrefixMapping(prefix);
    }

    @Override
    public void startElement(String uri, String localName, String qName, Attributes atts) 
            throws SAXException {
        getCompiler().startElement(uri, localName, qName, atts);
    }

    @Override
    public void endElement(String uri, String localName, String qName) throws SAXException {
        getCompiler().endElement(uri, localName, qName);
    }

    @Override
    public void characters(char[] ch, int start, int length) throws SAXException {
        getCompiler().characters(ch, start, length);
    }

    @Override
    public void ignorableWhitespace(char[] ch, int start, int length) throws SAXException {
        getCompiler().ignorableWhitespace(ch, start, length);
    }

    @Override
    public void processingInstruction(String target, String data) throws SAXException {
        getCompiler().processingInstruction(target, data);
    }

    @Override
    public void skippedEntity(String name) throws SAXException {
        getCompiler().skippedEntity(name);
    }

}

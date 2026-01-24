/*
 * OutputHandler.java
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

package org.bluezoo.gonzalez.transform.runtime;

import org.xml.sax.SAXException;

/**
 * Handler for transformation output events.
 *
 * <p>This interface extends SAX ContentHandler concepts with XSLT-specific
 * features like deferred attribute output, namespace management, and
 * disable-output-escaping support.
 *
 * <p>The output handler maintains state to support:
 * <ul>
 *   <li>Deferred element start (attributes can be added after startElement)</li>
 *   <li>Automatic namespace declaration</li>
 *   <li>Output method-specific escaping (xml, html, text)</li>
 *   <li>Result tree fragment building</li>
 * </ul>
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public interface OutputHandler {

    /**
     * Starts a document.
     *
     * @throws SAXException if an error occurs
     */
    void startDocument() throws SAXException;

    /**
     * Ends a document.
     *
     * @throws SAXException if an error occurs
     */
    void endDocument() throws SAXException;

    /**
     * Starts an element.
     *
     * <p>The element start tag is not immediately written - it is deferred
     * until content is added, allowing attributes to be added afterward.
     *
     * @param namespaceURI the namespace URI
     * @param localName the local name
     * @param qName the qualified name
     * @throws SAXException if an error occurs
     */
    void startElement(String namespaceURI, String localName, String qName) throws SAXException;

    /**
     * Ends an element.
     *
     * @param namespaceURI the namespace URI
     * @param localName the local name
     * @param qName the qualified name
     * @throws SAXException if an error occurs
     */
    void endElement(String namespaceURI, String localName, String qName) throws SAXException;

    /**
     * Adds an attribute to the current element.
     *
     * <p>Must be called after startElement and before any content.
     *
     * @param namespaceURI the namespace URI
     * @param localName the local name
     * @param qName the qualified name
     * @param value the attribute value
     * @throws SAXException if an error occurs or not in element start
     */
    void attribute(String namespaceURI, String localName, String qName, String value) 
        throws SAXException;

    /**
     * Adds a namespace declaration.
     *
     * <p>Must be called after startElement and before any content.
     *
     * @param prefix the namespace prefix (empty for default namespace)
     * @param uri the namespace URI
     * @throws SAXException if an error occurs
     */
    void namespace(String prefix, String uri) throws SAXException;

    /**
     * Outputs character content with normal escaping.
     *
     * @param text the text content
     * @throws SAXException if an error occurs
     */
    void characters(String text) throws SAXException;

    /**
     * Outputs character content with escaping disabled.
     *
     * <p>Used for disable-output-escaping="yes".
     *
     * @param text the raw text content
     * @throws SAXException if an error occurs
     */
    void charactersRaw(String text) throws SAXException;

    /**
     * Outputs a comment.
     *
     * @param text the comment text
     * @throws SAXException if an error occurs
     */
    void comment(String text) throws SAXException;

    /**
     * Outputs a processing instruction.
     *
     * @param target the PI target
     * @param data the PI data
     * @throws SAXException if an error occurs
     */
    void processingInstruction(String target, String data) throws SAXException;

    /**
     * Flushes any pending output.
     *
     * @throws SAXException if an error occurs
     */
    void flush() throws SAXException;

}

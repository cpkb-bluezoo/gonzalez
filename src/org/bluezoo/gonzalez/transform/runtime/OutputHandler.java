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

    /**
     * Marks a boundary between sequence items.
     * 
     * <p>In sequence construction mode (variables with as="item()*"), 
     * this signals that the current item is complete and the next
     * item should be separate. For normal output, this is a no-op.
     *
     * @throws SAXException if an error occurs
     */
    default void itemBoundary() throws SAXException {
        // Default implementation does nothing - only meaningful for sequence builders
    }
    
    /**
     * Returns true if the last output was an atomic value requiring space separation.
     * 
     * <p>XSLT 2.0 spec: adjacent atomic values in element content are space-separated.
     * This flag tracks whether the last output was an atomic value to determine if
     * a space separator should be inserted before the next atomic value.
     *
     * @return true if an atomic value is pending (requires space before next atomic value)
     */
    default boolean isAtomicValuePending() {
        return false;  // Default for non-XSLT-2.0 handlers
    }

    /**
     * Sets whether an atomic value was just output (for space separation tracking).
     * 
     * <p>Call with true after outputting an atomic value, false after outputting
     * an element, node, or result tree fragment.
     *
     * @param pending true if an atomic value was just output
     * @throws SAXException if an error occurs
     */
    default void setAtomicValuePending(boolean pending) throws SAXException {
        // Default no-op for handlers that don't track atomic value spacing
    }

    /**
     * Returns true if currently in attribute content mode where atomic values
     * are NOT space-separated.
     * 
     * <p>XSLT 2.0 spec: atomic values are space-separated in element content but
     * NOT in attribute content.
     *
     * @return true if in attribute content mode
     */
    default boolean isInAttributeContent() {
        return false;
    }

    /**
     * Sets whether we're in attribute content mode.
     * 
     * <p>Set to true when entering attribute value construction (xsl:attribute),
     * false when leaving.
     *
     * @param inAttributeContent true if entering attribute content mode
     * @throws SAXException if an error occurs
     */
    default void setInAttributeContent(boolean inAttributeContent) throws SAXException {
        // Default no-op for handlers that don't track attribute content mode
    }

    /**
     * Sets the type annotation for the current element.
     *
     * <p>Must be called after startElement and before any content.
     * This is used for schema-aware processing with xsl:type.
     *
     * @param namespaceURI the type namespace URI
     * @param localName the type local name
     * @throws SAXException if an error occurs
     */
    default void setElementType(String namespaceURI, String localName) throws SAXException {
        // Default implementation does nothing - subclasses override as needed
    }

    /**
     * Sets the type annotation for an attribute being added.
     *
     * <p>Should be called immediately after the attribute() call.
     *
     * @param namespaceURI the type namespace URI
     * @param localName the type local name
     * @throws SAXException if an error occurs
     */
    default void setAttributeType(String namespaceURI, String localName) throws SAXException {
        // Default implementation does nothing - subclasses override as needed
    }

    /**
     * Sets the validation mode for the current output context.
     *
     * <p>The validation mode controls how schema validation is applied and
     * whether type annotations are preserved, created, or removed:
     * <ul>
     *   <li>STRICT - Perform strict validation (must have schema declaration)</li>
     *   <li>LAX - Validate if schema declaration exists, skip otherwise</li>
     *   <li>PRESERVE - Keep existing type annotations from source</li>
     *   <li>STRIP - Remove all type annotations (xs:untyped/xs:untypedAtomic)</li>
     * </ul>
     *
     * <p>This is used by xsl:copy, xsl:copy-of, xsl:element, xsl:result-document, etc.
     *
     * @param mode the validation mode, or null for default
     * @throws SAXException if an error occurs
     */
    default void setValidationMode(org.bluezoo.gonzalez.transform.ValidationMode mode) 
        throws SAXException {
        // Default implementation does nothing - subclasses override as needed
    }

    /**
     * Gets the current validation mode.
     *
     * @return the current validation mode, or null if not set
     */
    default org.bluezoo.gonzalez.transform.ValidationMode getValidationMode() {
        return null; // Default: no validation mode set
    }

    /**
     * Adds an atomic value directly to the output.
     *
     * <p>This method is used for XSLT 2.0+ sequence construction, where
     * atomic values (numbers, strings, booleans, dates, etc.) need to be
     * preserved as typed values rather than converted to strings.
     *
     * <p>The default implementation converts the value to a string and
     * outputs it as characters.
     *
     * @param value the atomic value to add
     * @throws SAXException if an error occurs
     */
    default void atomicValue(org.bluezoo.gonzalez.transform.xpath.type.XPathValue value) 
        throws SAXException {
        // Default: convert to string (no spacing between adjacent values)
        if (value != null) {
            characters(value.asString());
        }
    }

    /**
     * Returns true if this handler has received any content (elements, text, etc.).
     * Used by xsl:result-document to detect XTDE1490 conflicts with implicit output.
     *
     * @return true if content has been written to this handler
     */
    default boolean hasReceivedContent() {
        return false;
    }

    /**
     * Marks this handler's URI as claimed by xsl:result-document.
     * Subsequent implicit writes should raise XTDE1490.
     */
    default void markClaimedByResultDocument() {
    }

    /**
     * Returns true if this handler has been claimed by xsl:result-document.
     */
    default boolean isClaimedByResultDocument() {
        return false;
    }

}

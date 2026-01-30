/*
 * PSVIProvider.java
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

package org.bluezoo.gonzalez.schema;

/**
 * Provides access to Post-Schema-Validation Infoset (PSVI) during SAX parsing.
 *
 * <p>This interface follows the Xerces PSVIProvider pattern, allowing a
 * {@link org.xml.sax.ContentHandler} to query schema validation information
 * during {@code startElement} and {@code endElement} callbacks.
 *
 * <p><b>Important:</b> The methods on this interface are only valid during
 * the corresponding SAX callback. Calling them at other times produces
 * undefined results.
 *
 * <p>Usage pattern:
 * <pre>
 * class MyHandler extends DefaultHandler {
 *     private final PSVIProvider psviProvider;
 *
 *     public MyHandler(PSVIProvider provider) {
 *         this.psviProvider = provider;
 *     }
 *
 *     {@literal @}Override
 *     public void startElement(String uri, String localName,
 *                              String qName, Attributes atts) {
 *         // Query PSVI during callback
 *         Validity validity = psviProvider.getValidity();
 *         TypedValue elementType = psviProvider.getElementTypedValue();
 *
 *         for (int i = 0; i &lt; atts.getLength(); i++) {
 *             TypedValue attrType = psviProvider.getAttributeTypedValue(i);
 *             String dtdType = psviProvider.getDTDAttributeType(i);
 *         }
 *     }
 * }
 * </pre>
 *
 * <p>To obtain a PSVIProvider, cast the XMLReader to PSVIProvider if it
 * implements this interface, or check for it as a SAX property:
 * <pre>
 * XMLReader reader = ...;
 * PSVIProvider provider = null;
 * if (reader instanceof PSVIProvider) {
 *     provider = (PSVIProvider) reader;
 * }
 * </pre>
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 * @see TypedValue
 * @see Validity
 * @see ValidationSource
 */
public interface PSVIProvider {

    // ===== Validation Status =====

    /**
     * Returns the validation status of the current element.
     *
     * <p>This reflects the aggregate validation status from all active
     * validators. If multiple validators are in use, the most restrictive
     * status is returned (INVALID takes precedence over VALID).
     *
     * <p>Only valid during {@code startElement} and {@code endElement} callbacks.
     *
     * @return the validation status, never null
     */
    Validity getValidity();

    /**
     * Returns the primary validation source for the current element.
     *
     * <p>If multiple validators are in use, returns the one that provided
     * the most specific type information (XSD over DTD over NONE).
     *
     * <p>Only valid during {@code startElement} and {@code endElement} callbacks.
     *
     * @return the validation source, never null
     */
    ValidationSource getValidationSource();

    // ===== DTD Type Information =====

    /**
     * Returns the DTD attribute type for the specified attribute.
     *
     * <p>Returns the declared type from the DTD, if available:
     * "ID", "IDREF", "IDREFS", "NMTOKEN", "NMTOKENS", "ENTITY", "ENTITIES",
     * "NOTATION", "CDATA", or an enumerated type "(a|b|c)".
     *
     * <p>This is equivalent to {@link org.xml.sax.Attributes#getType(int)}
     * but guaranteed to return DTD-declared types when available.
     *
     * <p>Only valid during {@code startElement} callback.
     *
     * @param attrIndex the attribute index (0-based)
     * @return the DTD attribute type, or "CDATA" if not declared
     */
    String getDTDAttributeType(int attrIndex);

    // ===== Simple Type Information =====

    /**
     * Returns the typed value for the current element's text content.
     *
     * <p>For elements with simple content (XSD simple type or complex type
     * with simple content), returns the typed value of the text content.
     *
     * <p>Only valid during {@code endElement} callback (after content is known).
     *
     * @return the typed value, or null if the element has no simple type
     *         or schema validation is not in effect
     */
    TypedValue getElementTypedValue();

    /**
     * Returns the typed value for the specified attribute.
     *
     * <p>Returns the typed value based on the schema-declared attribute type.
     *
     * <p>Only valid during {@code startElement} callback.
     *
     * @param attrIndex the attribute index (0-based)
     * @return the typed value, or null if the attribute has no schema type
     *         or schema validation is not in effect
     */
    TypedValue getAttributeTypedValue(int attrIndex);

    // ===== XSD-Specific Information =====

    /**
     * Returns the XSD type definition for the current element.
     *
     * <p>This provides access to full XSD type information including:
     * <ul>
     *   <li>Simple types with facets</li>
     *   <li>Complex types with content models</li>
     *   <li>Type derivation information</li>
     * </ul>
     *
     * <p>Only valid during {@code startElement} and {@code endElement} callbacks.
     *
     * @return the XSD type definition, or null if XSD validation is not in
     *         effect or no type is declared for this element
     */
    Object getXSDTypeDefinition();

    /**
     * Returns whether the current element is nil (xsi:nil="true").
     *
     * <p>In XSD, a nillable element can have xsi:nil="true" to indicate
     * that it has no value, even if the type normally requires content.
     *
     * <p>Only valid during {@code startElement} callback.
     *
     * @return true if xsi:nil="true", false otherwise, or null if
     *         XSD validation is not in effect
     */
    Boolean isNil();
}

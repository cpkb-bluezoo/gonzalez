/*
 * TypedValue.java
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
 * Represents a typed value from schema validation.
 *
 * <p>This interface provides access to type information for simple types,
 * which are common across schema languages that use XSD Part 2 datatypes
 * (XML Schema, Relax NG with XSD datatype library).
 *
 * <p>The datatype is identified by a namespace URI and local name. For XSD
 * Part 2 datatypes, the namespace is {@code http://www.w3.org/2001/XMLSchema}
 * and the local name is the type name (e.g., "integer", "date", "string").
 *
 * <p>Example usage:
 * <pre>
 * TypedValue value = psviProvider.getAttributeTypedValue(0);
 * if (value != null) {
 *     String typeName = value.getDatatypeLocalName();  // "integer"
 *     Object typed = value.getTypedValue();            // Integer(42)
 *     String lexical = value.getLexicalValue();        // "42"
 * }
 * </pre>
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public interface TypedValue {

    /**
     * XML Schema namespace URI for XSD Part 2 datatypes.
     */
    String XSD_NAMESPACE = "http://www.w3.org/2001/XMLSchema";

    /**
     * Returns the namespace URI of the datatype.
     *
     * <p>For XSD Part 2 datatypes (used by both XML Schema and Relax NG),
     * this is {@link #XSD_NAMESPACE}.
     *
     * @return the datatype namespace URI, never null
     */
    String getDatatypeURI();

    /**
     * Returns the local name of the datatype.
     *
     * <p>For XSD Part 2 datatypes, this is the type name such as:
     * "string", "integer", "decimal", "date", "dateTime", "boolean",
     * "ID", "IDREF", "NMTOKEN", etc.
     *
     * @return the datatype local name, never null
     */
    String getDatatypeLocalName();

    /**
     * Returns the typed Java value.
     *
     * <p>The returned type depends on the datatype:
     * <ul>
     *   <li>{@code xs:string} → {@link String}</li>
     *   <li>{@code xs:integer} → {@link java.math.BigInteger}</li>
     *   <li>{@code xs:int} → {@link Integer}</li>
     *   <li>{@code xs:long} → {@link Long}</li>
     *   <li>{@code xs:decimal} → {@link java.math.BigDecimal}</li>
     *   <li>{@code xs:float} → {@link Float}</li>
     *   <li>{@code xs:double} → {@link Double}</li>
     *   <li>{@code xs:boolean} → {@link Boolean}</li>
     *   <li>{@code xs:date} → {@link java.time.LocalDate}</li>
     *   <li>{@code xs:time} → {@link java.time.LocalTime}</li>
     *   <li>{@code xs:dateTime} → {@link java.time.LocalDateTime} or {@link java.time.OffsetDateTime}</li>
     *   <li>{@code xs:duration} → {@link java.time.Duration} or {@link java.time.Period}</li>
     *   <li>{@code xs:base64Binary} → {@code byte[]}</li>
     *   <li>{@code xs:hexBinary} → {@code byte[]}</li>
     *   <li>{@code xs:anyURI} → {@link java.net.URI}</li>
     *   <li>{@code xs:QName} → {@link javax.xml.namespace.QName}</li>
     * </ul>
     *
     * <p>For types derived by restriction, the base type's Java class is used.
     * For list types, a {@link java.util.List} is returned.
     *
     * @return the typed value, or null if the value could not be converted
     */
    Object getTypedValue();

    /**
     * Returns the original lexical representation of the value.
     *
     * <p>This is the normalized string value as it appeared in the document
     * after whitespace normalization according to the datatype.
     *
     * @return the lexical value, never null
     */
    String getLexicalValue();

    /**
     * Returns the validation source that provided this type information.
     *
     * @return the validation source, never null
     */
    ValidationSource getValidationSource();
}

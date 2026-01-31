/*
 * XSDTypedValue.java
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

package org.bluezoo.gonzalez.schema.xsd;

import org.bluezoo.gonzalez.schema.TypedValue;
import org.bluezoo.gonzalez.schema.ValidationSource;

/**
 * Implementation of {@link TypedValue} for XSD simple types.
 *
 * <p>This class represents a typed value from XSD validation, including
 * the datatype information and the converted Java value.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public class XSDTypedValue implements TypedValue {

    /** The XSD datatype local name (e.g., "integer", "date"). */
    private final String datatypeLocalName;
    
    /** The converted Java value (may be null if conversion failed). */
    private final Object typedValue;
    
    /** The original lexical representation (normalized). */
    private final String lexicalValue;

    /**
     * Creates an XSD typed value.
     *
     * @param datatypeLocalName the XSD datatype local name (e.g., "integer", "date", "string")
     * @param typedValue the converted Java value (may be null if conversion failed)
     * @param lexicalValue the original lexical representation, never null
     */
    public XSDTypedValue(String datatypeLocalName, Object typedValue, String lexicalValue) {
        this.datatypeLocalName = datatypeLocalName;
        this.typedValue = typedValue;
        this.lexicalValue = lexicalValue;
    }

    /**
     * Returns the datatype namespace URI.
     *
     * <p>For XSD types, this is always the XML Schema namespace URI.
     *
     * @return {@link TypedValue#XSD_NAMESPACE}
     */
    @Override
    public String getDatatypeURI() {
        return XSD_NAMESPACE;
    }

    /**
     * Returns the datatype local name.
     *
     * <p>This is the XSD Part 2 datatype name, such as "integer", "date",
     * "string", "boolean", etc.
     *
     * @return the datatype local name, never null
     */
    @Override
    public String getDatatypeLocalName() {
        return datatypeLocalName;
    }

    /**
     * Returns the typed Java value.
     *
     * <p>The Java type depends on the XSD datatype. See
     * {@link TypedValue#getTypedValue()} for the mapping.
     *
     * @return the converted Java value, or null if conversion failed
     */
    @Override
    public Object getTypedValue() {
        return typedValue;
    }

    /**
     * Returns the original lexical representation.
     *
     * <p>This is the normalized string value as it appeared in the document,
     * after whitespace normalization according to the datatype's whitespace facet.
     *
     * @return the lexical value, never null
     */
    @Override
    public String getLexicalValue() {
        return lexicalValue;
    }

    /**
     * Returns the validation source.
     *
     * <p>For XSDTypedValue, this is always XSD.
     *
     * @return {@link ValidationSource#XSD}
     */
    @Override
    public ValidationSource getValidationSource() {
        return ValidationSource.XSD;
    }

    @Override
    public String toString() {
        return "XSDTypedValue{" +
                "type=xs:" + datatypeLocalName +
                ", value=" + typedValue +
                ", lexical='" + lexicalValue + '\'' +
                '}';
    }
}

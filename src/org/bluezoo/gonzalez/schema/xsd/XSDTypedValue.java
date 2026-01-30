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

    private final String datatypeLocalName;
    private final Object typedValue;
    private final String lexicalValue;

    /**
     * Creates an XSD typed value.
     *
     * @param datatypeLocalName the XSD datatype local name (e.g., "integer")
     * @param typedValue the converted Java value
     * @param lexicalValue the original lexical representation
     */
    public XSDTypedValue(String datatypeLocalName, Object typedValue, String lexicalValue) {
        this.datatypeLocalName = datatypeLocalName;
        this.typedValue = typedValue;
        this.lexicalValue = lexicalValue;
    }

    @Override
    public String getDatatypeURI() {
        return XSD_NAMESPACE;
    }

    @Override
    public String getDatatypeLocalName() {
        return datatypeLocalName;
    }

    @Override
    public Object getTypedValue() {
        return typedValue;
    }

    @Override
    public String getLexicalValue() {
        return lexicalValue;
    }

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

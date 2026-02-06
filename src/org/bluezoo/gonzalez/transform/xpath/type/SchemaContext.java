/*
 * SchemaContext.java
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

package org.bluezoo.gonzalez.transform.xpath.type;

import org.bluezoo.gonzalez.schema.xsd.XSDSchema;

/**
 * Provides access to schema information during XPath type checking.
 *
 * <p>This interface enables type checking operations like {@code instance of}
 * and {@code treat as} to access schema information for:
 * <ul>
 *   <li>Substitution group membership checking</li>
 *   <li>Schema type hierarchy navigation</li>
 *   <li>Element and attribute declaration lookup</li>
 * </ul>
 *
 * <p>Implementations typically delegate to {@link org.bluezoo.gonzalez.transform.compiler.CompiledStylesheet}
 * to access schemas imported via {@code xsl:import-schema}.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public interface SchemaContext {
    
    /**
     * Gets a schema by namespace URI.
     *
     * <p>This method returns schemas that were imported via {@code xsl:import-schema}
     * in the current stylesheet.
     *
     * @param namespaceURI the schema target namespace URI, or null/empty for no-namespace schemas
     * @return the schema for this namespace, or null if not found
     */
    XSDSchema getSchema(String namespaceURI);
    
    /**
     * A null schema context that provides no schema information.
     *
     * <p>This is used when no schema context is available, such as in
     * standalone XPath evaluation outside of an XSLT transformation.
     */
    SchemaContext NONE = new SchemaContext() {
        @Override
        public XSDSchema getSchema(String namespaceURI) {
            return null;
        }
    };
}

/*
 * ExtensionFunction.java
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

package org.bluezoo.gonzalez.transform.extension;

import org.bluezoo.gonzalez.transform.runtime.TransformContext;
import org.bluezoo.gonzalez.transform.xpath.expr.XPathException;
import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;

import java.util.List;

/**
 * Interface for XSLT extension functions.
 *
 * <p>Extension functions provide a way to extend XSLT with custom functionality
 * implemented in Java. They can be called from XPath expressions within a
 * stylesheet.
 *
 * <p>Extension functions are identified by namespace URI and local name.
 * The namespace should be a URI that you control to avoid conflicts.
 *
 * <p>Example stylesheet usage:
 * <pre>{@code
 * <xsl:stylesheet xmlns:ext="http://example.com/extensions">
 *   <xsl:template match="/">
 *     <result><xsl:value-of select="ext:my-function($input)"/></result>
 *   </xsl:template>
 * </xsl:stylesheet>
 * }</pre>
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public interface ExtensionFunction {

    /**
     * Returns the namespace URI for this function.
     *
     * @return the namespace URI
     */
    String getNamespaceURI();

    /**
     * Returns the local name of this function.
     *
     * @return the function name
     */
    String getLocalName();

    /**
     * Returns the minimum number of arguments.
     *
     * @return minimum argument count
     */
    int getMinArgs();

    /**
     * Returns the maximum number of arguments.
     * Return Integer.MAX_VALUE for unlimited.
     *
     * @return maximum argument count
     */
    int getMaxArgs();

    /**
     * Invokes the function with the given arguments.
     *
     * @param args the evaluated arguments
     * @param context the transformation context
     * @return the function result
     * @throws XPathException if evaluation fails
     */
    XPathValue invoke(List<XPathValue> args, TransformContext context) throws XPathException;

}

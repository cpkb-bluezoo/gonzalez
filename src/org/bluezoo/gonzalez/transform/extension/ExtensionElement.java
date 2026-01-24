/*
 * ExtensionElement.java
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

import org.bluezoo.gonzalez.transform.ast.SequenceNode;
import org.bluezoo.gonzalez.transform.ast.XSLTNode;
import org.bluezoo.gonzalez.transform.runtime.OutputHandler;
import org.bluezoo.gonzalez.transform.runtime.TransformContext;
import org.xml.sax.SAXException;

import java.util.Map;

/**
 * Interface for XSLT extension elements.
 *
 * <p>Extension elements provide a way to add custom instruction elements
 * to XSLT stylesheets. They behave like built-in XSLT instructions but
 * are implemented in Java.
 *
 * <p>Extension elements are identified by namespace URI and local name.
 * They can contain a mix of XSLT instructions and literal content.
 *
 * <p>Example stylesheet usage:
 * <pre>{@code
 * <xsl:stylesheet xmlns:ext="http://example.com/extensions"
 *                 extension-element-prefixes="ext">
 *   <xsl:template match="/">
 *     <ext:my-element param="value">
 *       <content/>
 *     </ext:my-element>
 *   </xsl:template>
 * </xsl:stylesheet>
 * }</pre>
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public interface ExtensionElement {

    /**
     * Returns the namespace URI for this element.
     *
     * @return the namespace URI
     */
    String getNamespaceURI();

    /**
     * Returns the local name of this element.
     *
     * @return the element name
     */
    String getLocalName();

    /**
     * Compiles the extension element.
     *
     * <p>This is called at stylesheet compile time to create an executable
     * node from the extension element and its attributes/content.
     *
     * @param attributes the element's attributes
     * @param content the compiled content (child nodes)
     * @return an executable XSLT node
     */
    XSLTNode compile(Map<String, String> attributes, SequenceNode content);

}

/*
 * XPathNodeWithBaseURI.java
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

/**
 * Extension interface for XPathNode implementations that support base URI.
 *
 * <p>Nodes that implement this interface can provide their base URI directly,
 * rather than having it computed from xml:base attributes in ancestors.
 * This is used for nodes loaded via doc()/document() and for RTF nodes.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public interface XPathNodeWithBaseURI extends XPathNode {

    /**
     * Returns the base URI of this node.
     *
     * @return the base URI, or null if not set
     */
    String getBaseURI();

    /**
     * Returns the document URI of this node.
     *
     * <p>Only document (root) nodes have a document URI. For other node types,
     * this should return null.
     *
     * @return the document URI, or null if not a document node or no URI is set
     */
    default String getDocumentURI() {
        return null;
    }
}

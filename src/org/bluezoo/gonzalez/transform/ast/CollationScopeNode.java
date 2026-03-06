/*
 * CollationScopeNode.java
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

package org.bluezoo.gonzalez.transform.ast;

import org.bluezoo.gonzalez.transform.runtime.BasicTransformContext;
import org.bluezoo.gonzalez.transform.runtime.OutputHandler;
import org.bluezoo.gonzalez.transform.runtime.TransformContext;
import org.xml.sax.SAXException;

/**
 * Sets the default collation for the scope of its child node.
 *
 * <p>This wraps an XSLT node body and overrides the default collation
 * on the transform context before executing it. It implements the
 * element-level {@code default-collation} attribute from XSLT 2.0+.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class CollationScopeNode implements XSLTNode {

    private final String collationUri;
    private final XSLTNode body;

    /**
     * Creates a collation scope node.
     *
     * @param collationUri the collation URI to use as the default
     * @param body the body to execute within this collation scope
     */
    public CollationScopeNode(String collationUri, XSLTNode body) {
        this.collationUri = collationUri;
        this.body = body;
    }

    /**
     * Returns the wrapped body node.
     */
    public XSLTNode getBody() {
        return body;
    }

    /**
     * Returns the collation URI.
     */
    public String getCollationUri() {
        return collationUri;
    }

    @Override
    public void execute(TransformContext context, OutputHandler output) throws SAXException {
        if (context instanceof BasicTransformContext) {
            BasicTransformContext btc = (BasicTransformContext) context;
            String previous = btc.getDefaultCollation();
            btc.setDefaultCollation(collationUri);
            try {
                body.execute(context, output);
            } finally {
                btc.setDefaultCollation(previous);
            }
        } else {
            body.execute(context, output);
        }
    }
}

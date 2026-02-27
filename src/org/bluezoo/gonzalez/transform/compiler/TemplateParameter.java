/*
 * TemplateParameter.java
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

package org.bluezoo.gonzalez.transform.compiler;

import org.bluezoo.gonzalez.transform.ast.SequenceNode;
import org.bluezoo.gonzalez.transform.xpath.XPathExpression;

/**
 * A template parameter (xsl:param).
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class TemplateParameter {

    private final String namespaceURI;
    private final String localName;
    private final String expandedName; // Clark notation: {uri}localname or just localname
    private final XPathExpression selectExpr;
    private final SequenceNode defaultContent;
    private final boolean tunnel; // XSLT 2.0: whether this is a tunnel parameter
    private final boolean required; // XSLT 2.0: whether required="yes"

    /**
     * Creates a template parameter without namespace.
     *
     * @param localName the parameter local name
     * @param selectExpr the select expression (may be null)
     * @param defaultContent the default content (may be null)
     */
    public TemplateParameter(String localName, XPathExpression selectExpr, SequenceNode defaultContent) {
        this(null, localName, selectExpr, defaultContent, false, false);
    }

    /**
     * Creates a template parameter with namespace.
     *
     * @param namespaceURI the namespace URI (may be null)
     * @param localName the parameter local name
     * @param selectExpr the select expression (may be null)
     * @param defaultContent the default content (may be null)
     */
    public TemplateParameter(String namespaceURI, String localName, XPathExpression selectExpr, SequenceNode defaultContent) {
        this(namespaceURI, localName, selectExpr, defaultContent, false, false);
    }

    /**
     * Creates a template parameter with namespace and tunnel flag.
     *
     * @param namespaceURI the namespace URI (may be null)
     * @param localName the parameter local name
     * @param selectExpr the select expression (may be null)
     * @param defaultContent the default content (may be null)
     * @param tunnel whether this is a tunnel parameter (XSLT 2.0)
     */
    public TemplateParameter(String namespaceURI, String localName, XPathExpression selectExpr, 
                            SequenceNode defaultContent, boolean tunnel) {
        this(namespaceURI, localName, selectExpr, defaultContent, tunnel, false);
    }

    /**
     * Creates a template parameter with all options.
     *
     * @param namespaceURI the namespace URI (may be null)
     * @param localName the parameter local name
     * @param selectExpr the select expression (may be null)
     * @param defaultContent the default content (may be null)
     * @param tunnel whether this is a tunnel parameter (XSLT 2.0)
     * @param required whether this parameter is required (XSLT 2.0)
     */
    public TemplateParameter(String namespaceURI, String localName, XPathExpression selectExpr, 
                            SequenceNode defaultContent, boolean tunnel, boolean required) {
        this.namespaceURI = namespaceURI;
        this.localName = localName;
        this.expandedName = makeExpandedName(namespaceURI, localName);
        this.selectExpr = selectExpr;
        this.defaultContent = defaultContent;
        this.tunnel = tunnel;
        this.required = required;
    }

    /**
     * Returns the parameter namespace URI.
     *
     * @return the namespace URI, or null if no namespace
     */
    public String getNamespaceURI() {
        return namespaceURI;
    }

    /**
     * Returns the parameter local name.
     *
     * @return the local name
     */
    public String getLocalName() {
        return localName;
    }

    /**
     * Returns the parameter name in expanded form (Clark notation).
     * This is used for parameter matching.
     *
     * @return the expanded name ({uri}localname or just localname)
     */
    public String getName() {
        return expandedName;
    }

    /**
     * Creates an expanded name in Clark notation.
     */
    private static String makeExpandedName(String namespaceURI, String localName) {
        if (namespaceURI == null || namespaceURI.isEmpty()) {
            return localName;
        }
        return "{" + namespaceURI + "}" + localName;
    }

    /**
     * Returns the select expression.
     *
     * @return the expression, or null if content-specified
     */
    public XPathExpression getSelectExpr() {
        return selectExpr;
    }

    /**
     * Returns the default content.
     *
     * @return the content, or null if select-specified
     */
    public SequenceNode getDefaultContent() {
        return defaultContent;
    }

    /**
     * Returns true if this parameter has a default value.
     *
     * @return true if has default
     */
    public boolean hasDefault() {
        return selectExpr != null || (defaultContent != null && !defaultContent.isEmpty());
    }

    /**
     * Returns true if this is a tunnel parameter (XSLT 2.0).
     * Tunnel parameters are passed automatically through intermediate templates.
     *
     * @return true if this is a tunnel parameter
     */
    public boolean isTunnel() {
        return tunnel;
    }

    /**
     * Returns true if this parameter is required (XSLT 2.0).
     * A required parameter must be supplied by the calling instruction.
     *
     * @return true if this parameter is required
     */
    public boolean isRequired() {
        return required;
    }

    @Override
    public String toString() {
        return "param " + expandedName + (tunnel ? " (tunnel)" : "");
    }

}

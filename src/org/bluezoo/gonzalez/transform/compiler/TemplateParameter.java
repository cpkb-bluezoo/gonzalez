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

import org.xml.sax.SAXException;

import org.bluezoo.gonzalez.transform.ast.SequenceNode;
import org.bluezoo.gonzalez.transform.ast.XSLTNode;
import org.bluezoo.gonzalez.transform.runtime.BufferOutputHandler;
import org.bluezoo.gonzalez.transform.runtime.SAXEventBuffer;
import org.bluezoo.gonzalez.transform.runtime.TransformContext;
import org.bluezoo.gonzalez.transform.xpath.XPathExpression;
import org.bluezoo.gonzalez.transform.xpath.expr.XPathException;
import org.bluezoo.gonzalez.transform.xpath.type.SchemaContext;
import org.bluezoo.gonzalez.transform.xpath.type.SequenceType;
import org.bluezoo.gonzalez.transform.xpath.type.XPathAnyURI;
import org.bluezoo.gonzalez.transform.xpath.type.XPathBoolean;
import org.bluezoo.gonzalez.transform.xpath.type.XPathDateTime;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNumber;
import org.bluezoo.gonzalez.transform.xpath.type.XPathResultTreeFragment;
import org.bluezoo.gonzalez.transform.xpath.type.XPathSequence;
import org.bluezoo.gonzalez.transform.xpath.type.XPathString;
import org.bluezoo.gonzalez.transform.xpath.type.XPathUntypedAtomic;
import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;

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
    private final String asType; // XSLT 2.0+: declared type from 'as' attribute

    /**
     * Creates a template parameter without namespace.
     *
     * @param localName the parameter local name
     * @param selectExpr the select expression (may be null)
     * @param defaultContent the default content (may be null)
     */
    public TemplateParameter(String localName, XPathExpression selectExpr, SequenceNode defaultContent) {
        this(null, localName, selectExpr, defaultContent, false, false, null);
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
        this(namespaceURI, localName, selectExpr, defaultContent, false, false, null);
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
        this(namespaceURI, localName, selectExpr, defaultContent, tunnel, false, null);
    }

    /**
     * Creates a template parameter with all options except asType.
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
        this(namespaceURI, localName, selectExpr, defaultContent, tunnel, required, null);
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
     * @param asType the declared type from 'as' attribute (may be null)
     */
    public TemplateParameter(String namespaceURI, String localName, XPathExpression selectExpr, 
                            SequenceNode defaultContent, boolean tunnel, boolean required, String asType) {
        this.namespaceURI = namespaceURI;
        this.localName = localName;
        this.expandedName = makeExpandedName(namespaceURI, localName);
        this.selectExpr = selectExpr;
        this.defaultContent = defaultContent;
        this.tunnel = tunnel;
        this.required = required;
        this.asType = asType;
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
        if (required) {
            return true;
        }
        // XTDE0610: if no select and no content, default is empty sequence.
        // If empty sequence doesn't match 'as' type, param is effectively required.
        if (asType != null && selectExpr == null && defaultContent == null) {
            SequenceType parsedType = SequenceType.parse(asType, null);
            if (parsedType != null) {
                SequenceType.Occurrence occ = parsedType.getOccurrence();
                if (occ == SequenceType.Occurrence.ONE
                    || occ == SequenceType.Occurrence.ONE_OR_MORE) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Returns the declared type from the 'as' attribute (XSLT 2.0+).
     *
     * @return the type declaration string, or null if not specified
     */
    public String getAsType() {
        return asType;
    }

    /**
     * Evaluates the default content of this parameter.
     * When the {@code as} type indicates a non-node item type (item(), map(*),
     * array(*), function(*)), uses a sequence builder to preserve maps and
     * arrays. Otherwise, wraps content in a result tree fragment.
     *
     * @param context the transform context
     * @return the evaluated default value
     * @throws SAXException if an error occurs during evaluation
     */
    public XPathValue evaluateDefaultContent(TransformContext context)
            throws SAXException {
        if (defaultContent == null) {
            return new XPathString("");
        }
        if (isNonNodeItemType()) {
            SequenceBuilderOutputHandler seqBuilder =
                new SequenceBuilderOutputHandler(context.getStaticBaseURI());
            java.util.List<XSLTNode> children = defaultContent.getChildren();
            if (children != null) {
                for (int i = 0; i < children.size(); i++) {
                    children.get(i).execute(context, seqBuilder);
                    seqBuilder.markItemBoundary();
                }
            }
            XPathValue value = seqBuilder.getSequence();
            if (value instanceof XPathSequence) {
                XPathSequence seq = (XPathSequence) value;
                if (seq.size() == 1) {
                    return seq.iterator().next();
                }
            }
            return value;
        }
        SAXEventBuffer buffer = new SAXEventBuffer();
        BufferOutputHandler bufOutput = new BufferOutputHandler(buffer);
        defaultContent.execute(context, bufOutput);
        return new XPathResultTreeFragment(buffer);
    }

    private boolean isNonNodeItemType() {
        if (asType == null) {
            return false;
        }
        String type = asType.trim();
        return type.equals("item()") || type.startsWith("item()")
            || type.startsWith("map(") || type.startsWith("array(")
            || type.startsWith("function(");
    }

    /**
     * Coerces a default value to match this parameter's declared 'as' type.
     * If the value is an RTF and the declared type is atomic, atomizes and
     * casts the string content to the appropriate typed value.
     *
     * @param value the default value to coerce
     * @return the coerced value, or the original value if no coercion needed
     * @throws XPathException if the cast fails
     */
    public XPathValue coerceDefaultValue(XPathValue value) throws XPathException {
        if (asType == null || value == null) {
            return value;
        }
        if (!(value instanceof XPathResultTreeFragment)) {
            return value;
        }
        SequenceType parsedType = SequenceType.parse(asType, null);
        if (parsedType == null) {
            return value;
        }
        if (parsedType.getItemKind() != SequenceType.ItemKind.ATOMIC) {
            return value;
        }
        String targetLocal = parsedType.getLocalName();
        if (targetLocal == null) {
            return value;
        }
        String textContent = value.asString();
        return castStringToAtomicType(textContent, targetLocal);
    }
    
    public static XPathValue castStringToAtomicType(String textContent, 
                                                     String targetLocal) throws XPathException {
        switch (targetLocal) {
            case "untypedAtomic":
                return new XPathUntypedAtomic(textContent);
            case "string":
            case "normalizedString":
            case "token":
            case "language":
            case "NMTOKEN":
            case "Name":
            case "NCName":
            case "ID":
            case "IDREF":
            case "ENTITY":
                return new XPathString(textContent);
            case "double":
                return new XPathNumber(XPathNumber.parseXPathDouble(textContent), false, true);
            case "float":
                return new XPathNumber(XPathNumber.parseXPathFloat(textContent), true);
            case "decimal":
            case "integer":
            case "int":
            case "long":
            case "short":
            case "byte":
            case "nonNegativeInteger":
            case "nonPositiveInteger":
            case "positiveInteger":
            case "negativeInteger":
            case "unsignedInt":
            case "unsignedLong":
            case "unsignedShort":
            case "unsignedByte":
                return new XPathNumber(Double.parseDouble(textContent));
            case "boolean":
                return XPathBoolean.of("true".equals(textContent) || "1".equals(textContent));
            case "date":
                return XPathDateTime.parseDate(textContent);
            case "dateTime":
                return XPathDateTime.parseDateTime(textContent);
            case "time":
                return XPathDateTime.parseTime(textContent);
            case "duration":
            case "dayTimeDuration":
            case "yearMonthDuration":
                return XPathDateTime.parseDuration(textContent);
            case "gYear":
                return XPathDateTime.parseGYear(textContent);
            case "gYearMonth":
                return XPathDateTime.parseGYearMonth(textContent);
            case "gMonth":
                return XPathDateTime.parseGMonth(textContent);
            case "gMonthDay":
                return XPathDateTime.parseGMonthDay(textContent);
            case "gDay":
                return XPathDateTime.parseGDay(textContent);
            case "anyURI":
                return new XPathAnyURI(textContent);
            default:
                return new XPathUntypedAtomic(textContent);
        }
    }
    
    /**
     * Validates a supplied value against this parameter's declared type.
     * Throws XTTE0590 if the value cannot be converted to the required type.
     *
     * @param value the supplied value
     * @param errorCode error code to use (XTTE0590 for supplied, XTTE0600 for default)
     * @return the coerced value if conversion was needed, otherwise the original
     * @throws XPathException if the value doesn't match the declared type
     */
    public XPathValue validateValue(XPathValue value, String errorCode) throws XPathException {
        if (asType == null || value == null) {
            return value;
        }
        SequenceType parsedType = SequenceType.parse(asType, null);
        if (parsedType == null) {
            return value;
        }
        if (parsedType.matches(value, SchemaContext.NONE)) {
            return value;
        }
        // Try coercion from untypedAtomic
        if (value instanceof XPathUntypedAtomic) {
            if (parsedType.getItemKind() == SequenceType.ItemKind.ATOMIC) {
                String targetLocal = parsedType.getLocalName();
                if (targetLocal != null) {
                    String textContent = value.asString();
                    return castStringToAtomicType(textContent, targetLocal);
                }
            }
        }
        // Try coercion from RTF to atomic (atomize then cast)
        if (value instanceof XPathResultTreeFragment) {
            if (parsedType.getItemKind() == SequenceType.ItemKind.ATOMIC) {
                String targetLocal = parsedType.getLocalName();
                if (targetLocal != null) {
                    String textContent = value.asString();
                    return castStringToAtomicType(textContent, targetLocal);
                }
            }
            // Try extracting children for node() or element() target types
            if (parsedType.getItemKind() == SequenceType.ItemKind.ELEMENT ||
                parsedType.getItemKind() == SequenceType.ItemKind.NODE) {
                org.bluezoo.gonzalez.transform.xpath.type.XPathNodeSet rtfNodes =
                    ((XPathResultTreeFragment) value).asNodeSet();
                if (rtfNodes != null && !rtfNodes.isEmpty()) {
                    org.bluezoo.gonzalez.transform.xpath.type.XPathNode root = rtfNodes.iterator().next();
                    java.util.List<org.bluezoo.gonzalez.transform.xpath.type.XPathNode> children =
                        new java.util.ArrayList<>();
                    java.util.Iterator<org.bluezoo.gonzalez.transform.xpath.type.XPathNode> it =
                        root.getChildren();
                    while (it.hasNext()) {
                        children.add(it.next());
                    }
                    if (!children.isEmpty()) {
                        org.bluezoo.gonzalez.transform.xpath.type.XPathNodeSet nodeSet =
                            new org.bluezoo.gonzalez.transform.xpath.type.XPathNodeSet(children);
                        if (parsedType.matches(nodeSet, SchemaContext.NONE)) {
                            return nodeSet;
                        }
                    }
                }
            }
        }
        // Try coercion from NodeSet to atomic (atomize first node then cast)
        if (value.isNodeSet()) {
            if (parsedType.getItemKind() == SequenceType.ItemKind.ATOMIC) {
                String targetLocal = parsedType.getLocalName();
                if (targetLocal != null) {
                    String textContent = value.asString();
                    return castStringToAtomicType(textContent, targetLocal);
                }
            }
        }
        throw new XPathException(errorCode + ": Parameter $" + localName +
            ": required type is " + asType +
            ", but supplied value (" + value.getClass().getSimpleName() + ") does not match");
    }

    @Override
    public String toString() {
        return "param " + expandedName + (tunnel ? " (tunnel)" : "");
    }

}

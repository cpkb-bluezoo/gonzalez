/*
 * NodeTest.java
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

import org.bluezoo.gonzalez.QName;
import org.bluezoo.gonzalez.schema.xsd.XSDSimpleType;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNode;

/**
 * A parsed node test that can efficiently match against XPath nodes.
 *
 * <p>Instances are created once via {@link #parse(String)} and reused for
 * every match, avoiding repeated string parsing at match time.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
interface NodeTest {

    static final String XSD_NAMESPACE = "http://www.w3.org/2001/XMLSchema";

    /**
     * Tests whether the given node matches this node test.
     *
     * @param node the node to test
     * @return true if the node matches
     */
    boolean matches(XPathNode node);

    /**
     * Parses a name test string into a structured NodeTest.
     * The string should already have namespace prefixes resolved to Clark
     * notation by {@code resolvePatternNamespaces}.
     *
     * @param nameTest the name test string
     * @return a NodeTest that performs the equivalent match
     */
    static NodeTest parse(String nameTest) {
        // Singletons for common kind tests
        if ("node()".equals(nameTest)) {
            return AnyNodeTest.INSTANCE;
        }
        if ("text()".equals(nameTest)) {
            return TextTest.INSTANCE;
        }
        if ("comment()".equals(nameTest)) {
            return CommentTest.INSTANCE;
        }
        if ("*".equals(nameTest)) {
            return new ElementTest(null, null, null);
        }
        if ("@*".equals(nameTest)) {
            return new AttributeTest(null, null, null);
        }

        // processing-instruction() or processing-instruction('target')
        if (nameTest.startsWith("processing-instruction(") && nameTest.endsWith(")")) {
            String inner = nameTest.substring("processing-instruction(".length(),
                nameTest.length() - 1).trim();
            if (inner.isEmpty()) {
                return PITest.ANY;
            }
            String target = inner;
            if ((inner.startsWith("'") && inner.endsWith("'")) ||
                (inner.startsWith("\"") && inner.endsWith("\""))) {
                target = inner.substring(1, inner.length() - 1);
            }
            // XTSE0340: PI target name must be an NCName (no colons)
            if (target.indexOf(':') >= 0) {
                throw new IllegalArgumentException(
                    "XTSE0340: processing-instruction name must not contain a colon: " + target);
            }
            return new PITest(target);
        }

        // element() kind test
        if (nameTest.startsWith("element(") && nameTest.endsWith(")")) {
            return parseElementKindTest(nameTest);
        }

        // attribute() kind test (without @ prefix)
        if (nameTest.startsWith("attribute(") && nameTest.endsWith(")")) {
            return parseAttributeKindTest(nameTest);
        }

        // @ prefix -> attribute axis
        if (nameTest.startsWith("@")) {
            return parseAttributeNameTest(nameTest.substring(1));
        }

        // Clark notation: {uri}localname or {uri}*
        if (nameTest.startsWith("{")) {
            return parseClarkElement(nameTest);
        }

        // *:localname - any namespace, specific local name (XSLT 2.0)
        if (nameTest.startsWith("*:")) {
            String localPart = nameTest.substring(2);
            return new ElementTest(null, localPart, null);
        }

        // prefix:localname (legacy - shouldn't occur after resolution)
        int colon = nameTest.indexOf(':');
        if (colon > 0) {
            String localPart = nameTest.substring(colon + 1);
            return new ElementTest(null, localPart, null);
        }

        // Simple unprefixed local name - element in NO namespace
        return new ElementTest("", nameTest, null);
    }

    /**
     * Parses an attribute name test (the part after the @ prefix).
     */
    static NodeTest parseAttributeNameTest(String attrTest) {
        if ("*".equals(attrTest)) {
            return new AttributeTest(null, null, null);
        }
        if ("node()".equals(attrTest)) {
            return new AttributeTest(null, null, null);
        }

        // attribute() kind test on attribute axis
        if (attrTest.startsWith("attribute(") && attrTest.endsWith(")")) {
            return parseAttributeKindTest(attrTest);
        }

        // Other kind tests on attribute axis can never match
        if (attrTest.startsWith("element(") || attrTest.equals("text()") ||
            attrTest.equals("comment()") || attrTest.startsWith("processing-instruction(")) {
            return NeverMatchTest.INSTANCE;
        }

        // Clark notation: {uri}localname or {uri}*
        if (attrTest.startsWith("{")) {
            return parseClarkAttribute(attrTest);
        }

        // *:localname - any namespace, specific local name
        if (attrTest.startsWith("*:")) {
            String localPart = attrTest.substring(2);
            return new AttributeTest(null, localPart, null);
        }

        // prefix:localname or prefix:*
        int colon = attrTest.indexOf(':');
        if (colon > 0) {
            String localPart = attrTest.substring(colon + 1);
            if ("*".equals(localPart)) {
                return new AttributeTest(null, null, null);
            }
            return new AttributeTest(null, localPart, null);
        }

        // Simple @localname - attribute in NO namespace
        return new AttributeTest("", attrTest, null);
    }

    /**
     * Parses an element() kind test into an ElementTest.
     */
    static ElementTest parseElementKindTest(String nameTest) {
        String inner = nameTest.substring(8, nameTest.length() - 1).trim();
        if (inner.isEmpty() || "*".equals(inner)) {
            return new ElementTest(null, null, null);
        }
        int commaIdx = inner.indexOf(',');
        String nameStr = commaIdx > 0 ? inner.substring(0, commaIdx).trim() : inner;
        String typeStr = commaIdx > 0 ? inner.substring(commaIdx + 1).trim() : null;

        String nsUri = null;
        String localName = null;
        if (!"*".equals(nameStr)) {
            QName resolved = parseResolvedName(nameStr);
            nsUri = resolved.getURI();
            localName = resolved.getLocalName();
        }

        QName type = null;
        if (typeStr != null) {
            type = parseResolvedName(typeStr);
        }
        return new ElementTest(nsUri, localName, type);
    }

    /**
     * Parses an attribute() kind test into an AttributeTest.
     */
    static AttributeTest parseAttributeKindTest(String nameTest) {
        String inner = nameTest.substring(10, nameTest.length() - 1).trim();
        if (inner.isEmpty() || "*".equals(inner)) {
            return new AttributeTest(null, null, null);
        }
        int commaIdx = inner.indexOf(',');
        String nameStr = commaIdx > 0 ? inner.substring(0, commaIdx).trim() : inner;
        String typeStr = commaIdx > 0 ? inner.substring(commaIdx + 1).trim() : null;

        String nsUri = null;
        String localName = null;
        if (!"*".equals(nameStr)) {
            QName resolved = parseResolvedName(nameStr);
            nsUri = resolved.getURI();
            localName = resolved.getLocalName();
        }

        QName type = null;
        if (typeStr != null) {
            type = parseResolvedName(typeStr);
        }
        return new AttributeTest(nsUri, localName, type);
    }

    /**
     * Parses Clark notation ({uri}local or {uri}*) into an ElementTest.
     */
    static ElementTest parseClarkElement(String nameTest) {
        int closeBrace = nameTest.indexOf('}');
        if (closeBrace > 1) {
            String uri = nameTest.substring(1, closeBrace);
            String local = nameTest.substring(closeBrace + 1);
            if ("*".equals(local)) {
                return new ElementTest(uri, null, null);
            }
            return new ElementTest(uri, local, null);
        }
        return new ElementTest("", nameTest, null);
    }

    /**
     * Parses Clark notation ({uri}local or {uri}*) into an AttributeTest.
     */
    static AttributeTest parseClarkAttribute(String attrTest) {
        int closeBrace = attrTest.indexOf('}');
        if (closeBrace > 1) {
            String uri = attrTest.substring(1, closeBrace);
            String local = attrTest.substring(closeBrace + 1);
            if ("*".equals(uri)) {
                uri = null;
            }
            if ("*".equals(local)) {
                return new AttributeTest(uri, null, null);
            }
            return new AttributeTest(uri, local, null);
        }
        return new AttributeTest("", attrTest, null);
    }

    /**
     * Parses a resolved name (Clark notation {uri}local or plain local name)
     * into a QName. Names should already be resolved by resolvePatternNamespaces.
     *
     * @param resolved the resolved name string
     * @return a QName with namespace URI and local name
     */
    static QName parseResolvedName(String resolved) {
        if (resolved.startsWith("{")) {
            int closeBrace = resolved.indexOf('}');
            if (closeBrace > 1) {
                String uri = resolved.substring(1, closeBrace);
                String local = resolved.substring(closeBrace + 1);
                return new QName(uri, local, resolved);
            }
        }
        return new QName("", resolved, resolved);
    }

    /**
     * Checks if a node's type annotation matches (or derives from) the specified type.
     *
     * @param node the node to check
     * @param type the resolved type as a QName (namespace URI + local name)
     * @return true if the node's type matches or derives from the specified type
     */
    static boolean matchesTypeConstraint(XPathNode node, QName type) {
        String typeNs = type.getURI();
        String typeLocal = type.getLocalName();

        if (XSD_NAMESPACE.equals(typeNs)) {
            if ("untyped".equals(typeLocal) || "untypedAtomic".equals(typeLocal)) {
                if (!node.hasTypeAnnotation()) {
                    return true;
                }
            }
        }

        if (!node.hasTypeAnnotation()) {
            return false;
        }

        String nodeTypeLocal = node.getTypeLocalName();

        XSDSimpleType nodeType = XSDSimpleType.getBuiltInType(nodeTypeLocal);
        if (nodeType != null) {
            return nodeType.isDerivedFrom(typeNs, typeLocal);
        }

        return typeLocal.equals(nodeTypeLocal);
    }
}

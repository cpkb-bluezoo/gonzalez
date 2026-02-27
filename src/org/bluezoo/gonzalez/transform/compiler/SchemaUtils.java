/*
 * SchemaUtils.java
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

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.bluezoo.gonzalez.schema.xsd.XSDComplexType;
import org.bluezoo.gonzalez.schema.xsd.XSDElement;
import org.bluezoo.gonzalez.schema.xsd.XSDParticle;
import org.bluezoo.gonzalez.schema.xsd.XSDSchema;
import org.bluezoo.gonzalez.schema.xsd.XSDSimpleType;
import org.bluezoo.gonzalez.schema.xsd.XSDType;
import org.bluezoo.gonzalez.transform.runtime.TransformContext;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNode;

/**
 * Schema-related utilities for pattern matching, specifically for
 * element-with-id() which needs to check xs:ID type annotations.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
final class SchemaUtils {

    private SchemaUtils() {
    }

    /**
     * Checks if a node has a child element with xs:ID type annotation and
     * matching value.
     */
    static boolean nodeHasIdTypedChild(XPathNode node, String[] ids,
                                       TransformContext context) {
        if (!node.isElement()) {
            return false;
        }

        Iterator<XPathNode> children = node.getChildren();
        while (children.hasNext()) {
            XPathNode child = children.next();
            if (!child.isElement()) {
                continue;
            }

            if (child.hasTypeAnnotation()) {
                String typeLocal = child.getTypeLocalName();
                String typeNs = child.getTypeNamespaceURI();

                if (context.isIdDerivedType(typeNs, typeLocal)) {
                    String childValue = child.getStringValue().trim();
                    for (int i = 0; i < ids.length; i++) {
                        if (ids[i].equals(childValue)) {
                            return true;
                        }
                    }
                }
                continue;
            }

            boolean isIdTypedBySchema = isElementIdTypedBySchema(
                node.getNamespaceURI(), node.getLocalName(),
                child.getNamespaceURI(), child.getLocalName(),
                context);

            if (isIdTypedBySchema) {
                String childValue = child.getStringValue().trim();
                for (int i = 0; i < ids.length; i++) {
                    if (ids[i].equals(childValue)) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    /**
     * Checks if a child element is declared with xs:ID type in the imported
     * schemas.
     */
    static boolean isElementIdTypedBySchema(String parentNs,
                                            String parentLocalName,
                                            String childNs,
                                            String childLocalName,
                                            TransformContext context) {
        CompiledStylesheet stylesheet = context.getStylesheet();
        if (stylesheet == null) {
            return false;
        }

        Map<String, XSDSchema> schemas = stylesheet.getImportedSchemas();
        if (schemas == null || schemas.isEmpty()) {
            return false;
        }

        XSDSchema schema = schemas.get(parentNs != null ? parentNs : "");
        if (schema == null) {
            return false;
        }

        XSDElement parentDecl = schema.resolveElement(parentNs,
                                                       parentLocalName);
        if (parentDecl == null) {
            parentDecl = findLocalElementDeclaration(schema, parentNs,
                                                      parentLocalName);
        }

        if (parentDecl == null) {
            return false;
        }

        XSDType parentType = parentDecl.getType();
        if (!(parentType instanceof XSDComplexType)) {
            return false;
        }

        XSDComplexType ct = (XSDComplexType) parentType;
        XSDElement childDecl = ct.getChildElement(childNs, childLocalName);
        if (childDecl == null) {
            return false;
        }

        XSDType childType = childDecl.getType();
        if (childType instanceof XSDSimpleType) {
            return ((XSDSimpleType) childType).isDerivedFromId();
        }

        return false;
    }

    private static XSDElement findLocalElementDeclaration(XSDSchema schema,
                                                           String ns,
                                                           String localName) {
        for (XSDElement globalElem : schema.getElements().values()) {
            XSDElement found = findLocalElementInType(globalElem, ns,
                                                       localName);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static XSDElement findLocalElementInType(XSDElement elem,
                                                      String ns,
                                                      String localName) {
        XSDType type = elem.getType();
        if (!(type instanceof XSDComplexType)) {
            return null;
        }

        XSDComplexType ct = (XSDComplexType) type;
        XSDElement child = ct.getChildElement(ns, localName);
        if (child != null) {
            return child;
        }

        for (XSDElement childElem : getChildElements(ct)) {
            XSDElement found = findLocalElementInType(childElem, ns,
                                                       localName);
            if (found != null) {
                return found;
            }
        }

        return null;
    }

    private static List<XSDElement> getChildElements(XSDComplexType ct) {
        List<XSDElement> elements = new ArrayList<>();
        for (XSDParticle particle : ct.getParticles()) {
            collectElements(particle, elements);
        }
        return elements;
    }

    private static void collectElements(XSDParticle particle,
                                        List<XSDElement> elements) {
        if (particle.getElement() != null) {
            elements.add(particle.getElement());
        }
        for (XSDParticle child : particle.getChildren()) {
            collectElements(child, elements);
        }
    }
}

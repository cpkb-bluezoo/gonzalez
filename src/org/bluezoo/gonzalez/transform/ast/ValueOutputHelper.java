/*
 * ValueOutputHelper.java
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

import java.util.Iterator;

import org.xml.sax.SAXException;

import org.bluezoo.gonzalez.transform.runtime.OutputHandler;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNode;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNodeSet;
import org.bluezoo.gonzalez.transform.xpath.type.XPathResultTreeFragment;
import org.bluezoo.gonzalez.transform.xpath.type.XPathSequence;
import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;

/**
 * Shared helper for outputting XPath values to an OutputHandler,
 * preserving node structure (elements, attributes, etc.) rather than
 * flattening to string.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
final class ValueOutputHelper {

    private ValueOutputHelper() {
    }

    static void outputValue(XPathValue value, OutputHandler output) throws SAXException {
        if (value instanceof XPathResultTreeFragment) {
            ((XPathResultTreeFragment) value).replayToOutput(output);
        } else if (value instanceof XPathSequence) {
            XPathSequence seq = (XPathSequence) value;
            for (XPathValue item : seq) {
                outputValue(item, output);
            }
        } else if (value instanceof XPathNodeSet) {
            XPathNodeSet nodeSet = (XPathNodeSet) value;
            for (XPathNode node : nodeSet.getNodes()) {
                outputNode(node, output);
            }
        } else if (value instanceof XPathNode) {
            outputNode((XPathNode) value, output);
        } else {
            String str = value.asString();
            if (!str.isEmpty()) {
                output.characters(str);
            }
        }
    }

    static void outputNode(XPathNode node, OutputHandler output) throws SAXException {
        switch (node.getNodeType()) {
            case ELEMENT:
                String uri = node.getNamespaceURI() != null ? node.getNamespaceURI() : "";
                String localName = node.getLocalName();
                String prefix = node.getPrefix();
                String qName = prefix != null ? prefix + ":" + localName : localName;
                
                output.startElement(uri, localName, qName);
                
                Iterator<XPathNode> namespaces = node.getNamespaces();
                while (namespaces.hasNext()) {
                    XPathNode ns = namespaces.next();
                    String nsPrefix = ns.getLocalName();
                    String nsUri = ns.getStringValue();
                    if (!"xml".equals(nsPrefix) && nsUri != null) {
                        output.namespace(nsPrefix, nsUri);
                    }
                }
                
                Iterator<XPathNode> attrs = node.getAttributes();
                while (attrs.hasNext()) {
                    XPathNode attr = attrs.next();
                    String attrUri = attr.getNamespaceURI() != null ? attr.getNamespaceURI() : "";
                    String attrLocal = attr.getLocalName();
                    String attrPrefix = attr.getPrefix();
                    String attrQName = attrPrefix != null ? attrPrefix + ":" + attrLocal : attrLocal;
                    output.attribute(attrUri, attrLocal, attrQName, attr.getStringValue());
                }
                
                Iterator<XPathNode> children = node.getChildren();
                while (children.hasNext()) {
                    outputNode(children.next(), output);
                }
                
                output.endElement(uri, localName, qName);
                break;
                
            case TEXT:
                output.characters(node.getStringValue());
                break;
                
            case COMMENT:
                output.comment(node.getStringValue());
                break;
                
            case PROCESSING_INSTRUCTION:
                output.processingInstruction(node.getLocalName(), node.getStringValue());
                break;
                
            case ROOT:
                Iterator<XPathNode> rootChildren = node.getChildren();
                while (rootChildren.hasNext()) {
                    outputNode(rootChildren.next(), output);
                }
                break;
                
            case ATTRIBUTE:
                String atUri = node.getNamespaceURI() != null ? node.getNamespaceURI() : "";
                String atLocal = node.getLocalName();
                String atPrefix = node.getPrefix();
                String atQName = atPrefix != null ? atPrefix + ":" + atLocal : atLocal;
                output.attribute(atUri, atLocal, atQName, node.getStringValue());
                break;
                
            default:
                break;
        }
    }
}

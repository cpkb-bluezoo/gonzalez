/*
 * SequenceOutputNode.java
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
import org.bluezoo.gonzalez.transform.runtime.TransformContext;
import org.bluezoo.gonzalez.transform.xpath.XPathExpression;
import org.bluezoo.gonzalez.transform.xpath.expr.XPathException;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNode;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNodeSet;
import org.bluezoo.gonzalez.transform.xpath.type.XPathResultTreeFragment;
import org.bluezoo.gonzalez.transform.xpath.type.XPathSequence;
import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;

/**
 * SequenceOutputNode XSLT instruction.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public class SequenceOutputNode extends XSLTInstruction {
    private final XPathExpression selectExpr;
    public SequenceOutputNode(XPathExpression selectExpr) { this.selectExpr = selectExpr; }
    @Override public String getInstructionName() { return "sequence"; }
    @Override public void execute(TransformContext context, 
                                  OutputHandler output) throws SAXException {
        try {
            XPathValue result = selectExpr.evaluate(context);
            
            if (result == null) {
                return;
            }
            
            if (result instanceof XPathResultTreeFragment) {
                // Result tree fragment - replay the buffered events
                XPathResultTreeFragment rtf = (XPathResultTreeFragment) result;
                rtf.replayToOutput(output);
                output.setAtomicValuePending(false);
            } else if (result instanceof XPathSequence) {
                // XPath 2.0+ sequence - output each item separately
                XPathSequence seq = (XPathSequence) result;
                boolean first = true;
                for (XPathValue item : seq) {
                    if (!first) {
                        // Signal item boundary for sequence builders
                        output.itemBoundary();
                    }
                    outputSequenceItem(item, output, first);
                    first = false;
                }
            } else if (result instanceof XPathNodeSet) {
                // For node-sets, output nodes (similar to copy-of for simplicity)
                XPathNodeSet nodeSet = (XPathNodeSet) result;
                boolean first = true;
                for (XPathNode node : nodeSet.getNodes()) {
                    if (!first) {
                        output.itemBoundary();
                    }
                    outputNode(node, output);
                    first = false;
                }
                output.setAtomicValuePending(false);
            } else {
                // For atomic values, use atomicValue() which handles spacing
                // Adjacent atomic values are separated by a single space (but NOT in attribute content)
                output.atomicValue(result);
            }
        } catch (XPathException e) {
            throw new SAXException("Error in xsl:sequence", e);
        }
    }
    
    private void outputSequenceItem(XPathValue item, OutputHandler output, boolean first) throws SAXException {
        if (item instanceof XPathNodeSet) {
            XPathNodeSet nodeSet = (XPathNodeSet) item;
            for (XPathNode node : nodeSet.getNodes()) {
                outputNode(node, output);
            }
            output.setAtomicValuePending(false);
        } else if (item instanceof XPathResultTreeFragment) {
            ((XPathResultTreeFragment) item).replayToOutput(output);
            output.setAtomicValuePending(false);
        } else {
            // Atomic value - use atomicValue() which handles spacing
            output.atomicValue(item);
        }
    }
    
    private void outputNode(XPathNode node, OutputHandler output) throws SAXException {
        // For xsl:sequence, we output nodes directly
        // This is a simplified implementation - full XSLT 2.0 would handle
        // document nodes, attribute nodes, etc. differently
        switch (node.getNodeType()) {
            case ELEMENT:
                String uri = node.getNamespaceURI() != null ? node.getNamespaceURI() : "";
                String localName = node.getLocalName();
                String prefix = node.getPrefix();
                String qName = prefix != null ? prefix + ":" + localName : localName;
                
                output.startElement(uri, localName, qName);
                
                // Copy namespace declarations
                Iterator<XPathNode> namespaces = node.getNamespaces();
                while (namespaces.hasNext()) {
                    XPathNode ns = namespaces.next();
                    String nsPrefix = ns.getLocalName();
                    String nsUri = ns.getStringValue();
                    if (!"xml".equals(nsPrefix) && nsUri != null) {
                        output.namespace(nsPrefix, nsUri);
                    }
                }
                
                // Copy attributes
                Iterator<XPathNode> attrs = node.getAttributes();
                while (attrs.hasNext()) {
                    XPathNode attr = attrs.next();
                    String attrUri = attr.getNamespaceURI() != null ? attr.getNamespaceURI() : "";
                    String attrLocal = attr.getLocalName();
                    String attrPrefix = attr.getPrefix();
                    String attrQName = attrPrefix != null ? attrPrefix + ":" + attrLocal : attrLocal;
                    output.attribute(attrUri, attrLocal, attrQName, attr.getStringValue());
                }
                
                // Copy children
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
                // Ignore namespace nodes
        }
    }
}

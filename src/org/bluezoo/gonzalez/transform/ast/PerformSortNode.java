/*
 * PerformSortNode.java
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

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.xml.sax.SAXException;

import org.bluezoo.gonzalez.transform.compiler.SortSpec;
import org.bluezoo.gonzalez.transform.runtime.BufferOutputHandler;
import org.bluezoo.gonzalez.transform.runtime.OutputHandler;
import org.bluezoo.gonzalez.transform.runtime.SAXEventBuffer;
import org.bluezoo.gonzalez.transform.runtime.TransformContext;
import org.bluezoo.gonzalez.transform.xpath.XPathExpression;
import org.bluezoo.gonzalez.transform.xpath.expr.XPathException;
import org.bluezoo.gonzalez.transform.xpath.type.NodeType;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNode;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNodeSet;
import org.bluezoo.gonzalez.transform.xpath.type.XPathResultTreeFragment;
import org.bluezoo.gonzalez.transform.xpath.type.XPathSequence;
import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;

/**
 * PerformSortNode XSLT instruction.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public class PerformSortNode extends XSLTInstruction {
    private final XPathExpression selectExpr;
    private final List<SortSpec> sorts;
    private final XSLTNode content;
    
    public PerformSortNode(XPathExpression selectExpr, List<SortSpec> sorts, XSLTNode content) {
        this.selectExpr = selectExpr;
        this.sorts = sorts;
        this.content = content;
    }
    
    @Override public String getInstructionName() { return "perform-sort"; }
    
    @Override public void execute(TransformContext context, 
                                  OutputHandler output) throws SAXException {
        try {
            // Get the sequence to sort
            List<XPathNode> nodes = new ArrayList<>();
            
            if (selectExpr != null) {
                // Sort the selected sequence
                XPathValue result = selectExpr.evaluate(context);
                if (result != null) {
                    collectNodes(result, nodes);
                }
            } else if (content != null) {
                // Content generates the sequence - evaluate and collect nodes
                SAXEventBuffer buffer = new SAXEventBuffer();
                BufferOutputHandler bufferOutput = new BufferOutputHandler(buffer);
                content.execute(context, bufferOutput);
                
                // Convert buffer to RTF and collect its children
                XPathResultTreeFragment rtf = new XPathResultTreeFragment(buffer);
                collectNodes(rtf, nodes);
            }
            
            if (nodes.isEmpty()) {
                return;
            }
            
            // Apply sorting
            ForEachNode.sortNodesStatic(nodes, sorts, context);
            
            // Output the sorted sequence
            boolean first = true;
            for (XPathNode node : nodes) {
                if (!first) {
                    output.itemBoundary();
                }
                first = false;
                deepCopyNode(node, output);
            }
        } catch (XPathException e) {
            throw new SAXException("Error in xsl:perform-sort", e);
        }
    }
    
    private void collectNodes(XPathValue result, List<XPathNode> nodes) {
        if (result instanceof XPathNodeSet) {
            for (XPathNode node : ((XPathNodeSet) result).getNodes()) {
                nodes.add(node);
            }
        } else if (result instanceof XPathSequence) {
            for (XPathValue item : ((XPathSequence) result).getItems()) {
                collectNodes(item, nodes);
            }
        } else if (result instanceof XPathNode) {
            nodes.add((XPathNode) result);
        } else if (result instanceof XPathResultTreeFragment) {
            XPathResultTreeFragment rtf = (XPathResultTreeFragment) result;
            if (rtf.isNodeSet()) {
                XPathNodeSet ns = rtf.asNodeSet();
                for (XPathNode node : ns.getNodes()) {
                    nodes.add(node);
                }
            }
        }
        // Atomic values are skipped
    }
    
    /**
     * Deep copies a node to the output handler (simplified version for perform-sort).
     */
    private void deepCopyNode(XPathNode node, OutputHandler output) throws SAXException {
        NodeType nodeType = node.getNodeType();
        if (nodeType == NodeType.ELEMENT) {
            String uri = node.getNamespaceURI() != null ? node.getNamespaceURI() : "";
            String localName = node.getLocalName();
            String prefix = node.getPrefix();
            String qName = prefix != null && !prefix.isEmpty() ? prefix + ":" + localName : localName;
            
            output.startElement(uri, localName, qName);
            
            // Copy namespace declarations
            if (prefix == null || prefix.isEmpty()) {
                output.namespace("", uri);
            }
            Iterator<XPathNode> namespaces = node.getNamespaces();
            while (namespaces.hasNext()) {
                XPathNode ns = namespaces.next();
                String nsPrefix = ns.getLocalName();
                String nsUri = ns.getStringValue();
                if (!"xml".equals(nsPrefix) && nsUri != null) {
                    output.namespace(nsPrefix != null ? nsPrefix : "", nsUri);
                }
            }
            
            // Copy attributes
            Iterator<XPathNode> attributes = node.getAttributes();
            while (attributes.hasNext()) {
                XPathNode attr = attributes.next();
                String attrUri = attr.getNamespaceURI();
                String attrLocal = attr.getLocalName();
                String attrPrefix = attr.getPrefix();
                String attrQName = attrPrefix != null && !attrPrefix.isEmpty() 
                    ? attrPrefix + ":" + attrLocal : attrLocal;
                output.attribute(attrUri != null ? attrUri : "", attrLocal, attrQName, attr.getStringValue());
            }
            
            // Copy children
            Iterator<XPathNode> children = node.getChildren();
            while (children.hasNext()) {
                deepCopyNode(children.next(), output);
            }
            
            output.endElement(uri, localName, qName);
        } else if (nodeType == NodeType.TEXT) {
            output.characters(node.getStringValue());
        } else if (nodeType == NodeType.COMMENT) {
            output.comment(node.getStringValue());
        } else if (nodeType == NodeType.PROCESSING_INSTRUCTION) {
            output.processingInstruction(node.getLocalName(), node.getStringValue());
        } else if (nodeType == NodeType.ROOT) {
            // Copy children of document node
            Iterator<XPathNode> docChildren = node.getChildren();
            while (docChildren.hasNext()) {
                deepCopyNode(docChildren.next(), output);
            }
        }
        // Ignore other node types (ATTRIBUTE, NAMESPACE)
    }
}

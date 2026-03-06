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

import org.bluezoo.gonzalez.transform.compiler.ExpressionHolder;
import org.bluezoo.gonzalez.transform.compiler.SequenceBuilderOutputHandler;
import org.bluezoo.gonzalez.transform.compiler.SortSpec;
import org.bluezoo.gonzalez.transform.runtime.OutputHandler;
import org.bluezoo.gonzalez.transform.runtime.TransformContext;
import org.bluezoo.gonzalez.transform.xpath.XPathExpression;
import org.bluezoo.gonzalez.transform.xpath.expr.XPathException;
import org.bluezoo.gonzalez.transform.xpath.type.NodeType;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNode;
import org.bluezoo.gonzalez.transform.xpath.type.XPathString;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNodeSet;
import org.bluezoo.gonzalez.transform.xpath.type.XPathResultTreeFragment;
import org.bluezoo.gonzalez.transform.xpath.type.XPathSequence;
import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;

/**
 * PerformSortNode XSLT instruction.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public class PerformSortNode extends XSLTInstruction implements ExpressionHolder {
    private final XPathExpression selectExpr;
    private final List<SortSpec> sorts;
    private final XSLTNode content;
    
    public PerformSortNode(XPathExpression selectExpr, List<SortSpec> sorts, XSLTNode content) {
        this.selectExpr = selectExpr;
        this.sorts = sorts;
        this.content = content;
    }
    
    @Override public String getInstructionName() { return "perform-sort"; }

    @Override
    public List<XPathExpression> getExpressions() {
        List<XPathExpression> exprs = new ArrayList<XPathExpression>();
        if (selectExpr != null) {
            exprs.add(selectExpr);
        }
        return exprs;
    }
    
    @Override public void execute(TransformContext context, 
                                  OutputHandler output) throws SAXException {
        try {
            // Get the sequence to sort
            List<XPathValue> items = new ArrayList<>();
            
            if (selectExpr != null) {
                XPathValue result = selectExpr.evaluate(context);
                if (result != null) {
                    collectItems(result, items);
                }
            } else if (content != null) {
                SequenceBuilderOutputHandler seqBuilder = new SequenceBuilderOutputHandler();
                content.execute(context, seqBuilder);
                XPathValue seqResult = seqBuilder.getSequence();
                if (seqResult != null) {
                    collectItems(seqResult, items);
                }
            }
            
            if (items.isEmpty()) {
                return;
            }
            
            // Check if all items are nodes (wrapped in XPathNodeSet)
            boolean allNodes = true;
            for (XPathValue item : items) {
                if (!(item instanceof XPathNodeSet)) {
                    allNodes = false;
                    break;
                }
            }
            
            if (allNodes) {
                List<XPathNode> nodes = new ArrayList<>();
                for (XPathValue item : items) {
                    for (XPathNode node : ((XPathNodeSet) item).getNodes()) {
                        nodes.add(node);
                    }
                }
                ForEachNode.sortNodesStatic(nodes, sorts, context);
                
                for (XPathNode node : nodes) {
                    deepCopyNode(node, output);
                }
            } else {
                // Sequence contains atomic values — use sequence sort
                ForEachNode.sortSequence(items, sorts, context);
                
                for (XPathValue item : items) {
                    if (item instanceof XPathNodeSet) {
                        XPathNodeSet ns = (XPathNodeSet) item;
                        XPathNode node = ns.first();
                        if (node != null) {
                            deepCopyNode(node, output);
                        }
                    } else {
                        output.atomicValue(item);
                    }
                }
            }
        } catch (XPathException e) {
            throw new SAXException("Error in xsl:perform-sort", e);
        }
    }
    
    private XPathValue wrapNode(XPathNode node) {
        List<XPathNode> list = new ArrayList<>();
        list.add(node);
        return new XPathNodeSet(list);
    }

    private void collectItems(XPathValue result, List<XPathValue> items) {
        if (result instanceof XPathNodeSet) {
            for (XPathNode node : ((XPathNodeSet) result).getNodes()) {
                items.add(wrapNode(node));
            }
        } else if (result instanceof XPathSequence) {
            for (XPathValue item : ((XPathSequence) result).getItems()) {
                collectItems(item, items);
            }
        } else if (result instanceof XPathResultTreeFragment) {
            XPathResultTreeFragment rtf = (XPathResultTreeFragment) result;
            if (rtf.isNodeSet()) {
                XPathNodeSet ns = rtf.asNodeSet();
                for (XPathNode node : ns.getNodes()) {
                    if (node.getNodeType() == NodeType.ROOT) {
                        Iterator<XPathNode> children = node.getChildren();
                        while (children.hasNext()) {
                            items.add(wrapNode(children.next()));
                        }
                    } else {
                        items.add(wrapNode(node));
                    }
                }
            }
        } else if (result instanceof XPathNode) {
            items.add(wrapNode((XPathNode) result));
        } else {
            // Atomic values (strings, numbers, etc.)
            items.add(result);
        }
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
        } else if (nodeType == NodeType.ATTRIBUTE) {
            String attrUri = node.getNamespaceURI();
            String attrLocal = node.getLocalName();
            String attrPrefix = node.getPrefix();
            String attrQName = attrPrefix != null && !attrPrefix.isEmpty()
                ? attrPrefix + ":" + attrLocal : attrLocal;
            output.attribute(attrUri != null ? attrUri : "", attrLocal, attrQName,
                             node.getStringValue());
        } else if (nodeType == NodeType.NAMESPACE) {
            output.atomicValue(new XPathString(node.getStringValue()));
        } else if (nodeType == NodeType.ROOT) {
            // Copy children of document node
            Iterator<XPathNode> docChildren = node.getChildren();
            while (docChildren.hasNext()) {
                deepCopyNode(docChildren.next(), output);
            }
        }
    }
}

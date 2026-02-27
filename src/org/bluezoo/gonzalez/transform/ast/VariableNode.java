/*
 * VariableNode.java
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

import java.net.URI;
import java.util.Collections;
import java.util.Iterator;

import org.xml.sax.SAXException;

import org.bluezoo.gonzalez.transform.ErrorHandlingMode;
import org.bluezoo.gonzalez.transform.compiler.SequenceBuilderOutputHandler;
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
import org.bluezoo.gonzalez.transform.xpath.type.XPathString;
import org.bluezoo.gonzalez.transform.xpath.type.SequenceType;
import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;

/**
 * VariableNode XSLT instruction.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public class VariableNode extends XSLTInstruction {
    private final String namespaceURI;
    private final String localName;
    private final XPathExpression selectExpr;
    private final SequenceNode content;
    private final String asType; // XSLT 2.0 type annotation
    private final SequenceType parsedAsType; // Pre-parsed SequenceType for runtime checking
    
    public VariableNode(String namespaceURI, String localName, XPathExpression selectExpr, 
                SequenceNode content, String asType) {
        this.namespaceURI = namespaceURI;
        this.localName = localName;
        this.selectExpr = selectExpr;
        this.content = content;
        this.asType = asType;
        this.parsedAsType = SequenceType.parse(asType, null);
    }
    
    @Override public String getInstructionName() { return "variable"; }
    
    /**
     * Checks if the as type indicates a sequence type (contains *, +, or ?).
     */
    private boolean isSequenceType() {
        if (asType == null) {
            return false;
        }
        // Sequence types: item()*, item()+, element()*, xs:string*, etc.
        return asType.contains("*") || asType.contains("+") || asType.contains("?");
    }
    
    /**
     * Checks if the as type indicates a single node type.
     * For single node types, we should return the node directly, not wrapped in RTF.
     */
    private boolean isSingleNodeType() {
        if (asType == null) {
            return false;
        }
        String type = asType.trim();
        // Single node types: element(), element(name), node(), attribute(), etc.
        // But NOT sequence types like element()*
        if (type.contains("*") || type.contains("+") || type.contains("?")) {
            return false;
        }
        return type.startsWith("element(") || type.startsWith("node(") || 
               type.startsWith("attribute(") || type.startsWith("document-node(") ||
               type.startsWith("text(") || type.startsWith("comment(") ||
               type.startsWith("processing-instruction(");
    }

    /**
     * Checks if the as type indicates a non-node item that requires
     * sequence construction (e.g., item(), map(*), function(*)).
     */
    private boolean isItemType() {
        if (asType == null) {
            return false;
        }
        String type = asType.trim();
        return type.equals("item()") || type.startsWith("map(") || type.startsWith("array(")
            || type.startsWith("function(");
    }
    
    @Override
    public void execute(TransformContext context, 
                       OutputHandler output) throws SAXException {
        try {
            XPathValue value;
            if (selectExpr != null) {
                value = selectExpr.evaluate(context);
            } else if (content != null) {
                if (isItemType()) {
                    value = executeSequenceConstructor(context);
                    if (value instanceof XPathSequence) {
                        XPathSequence seq = (XPathSequence) value;
                        if (seq.size() == 1) {
                            value = seq.iterator().next();
                        }
                    }
                } else if (isSequenceType()) {
                    // XSLT 2.0+ sequence construction mode
                    // Execute content and collect items into a sequence
                    value = executeSequenceConstructor(context);
                } else if (isSingleNodeType()) {
                    // XSLT 2.0+ single node type (e.g., as="element()")
                    // Execute content and extract the single node
                    value = executeSequenceConstructor(context);
                    // Extract single node from the sequence
                    if (value instanceof XPathSequence) {
                        XPathSequence seq = (XPathSequence) value;
                        if (seq.size() == 1) {
                            value = seq.iterator().next();
                        }
                    }
                    // For single node types, extract the actual node from the node set
                    // For element() type specifically, we look for element children
                    // For general node() type, we accept any child node type
                    if (value instanceof XPathNodeSet) {
                        XPathNodeSet ns = (XPathNodeSet) value;
                        Iterator<XPathNode> iter = ns.iterator();
                        if (iter.hasNext()) {
                            XPathNode node = iter.next();
                            // If this is a document/RTF root, get its first child node
                            if (node.getNodeType() == NodeType.ROOT) {
                                Iterator<XPathNode> children = node.getChildren();
                                boolean isElementType = asType != null && asType.startsWith("element(");
                                while (children.hasNext()) {
                                    XPathNode child = children.next();
                                    // For element() type, only match elements
                                    // For node() type, accept any node type
                                    if (isElementType) {
                                        if (child.isElement()) {
                                            value = new XPathNodeSet(Collections.singletonList(child));
                                            break;
                                        }
                                    } else {
                                        // For node() and other types, accept first child
                                        value = new XPathNodeSet(Collections.singletonList(child));
                                        break;
                                    }
                                }
                            } else if (ns.size() == 1) {
                                // Single node already
                                value = ns;
                            }
                        }
                    }
                } else {
                    // XSLT 1.0 style: Execute content to build result tree fragment
                    SAXEventBuffer buffer = 
                        new SAXEventBuffer();
                    OutputHandler bufferHandler = 
                        new BufferOutputHandler(buffer);
                    content.execute(context, bufferHandler);
                    // Store as RTF so xsl:copy-of can access the tree structure
                    // Use the instruction's static base URI (from xml:base) for the RTF
                    String rtfBaseUri = (staticBaseURI != null) ? staticBaseURI : context.getStaticBaseURI();
                    value = new XPathResultTreeFragment(buffer, rtfBaseUri);
                }
            } else {
                value = XPathString.of("");
            }
            
            // XTTE0570: Check value against declared 'as' type.
            // Only check when using select expression (content construction
            // creates the right node types by definition) and only for atomic
            // types where we can reliably determine mismatches.
            if (parsedAsType != null && selectExpr != null) {
                if (parsedAsType.getItemKind() == SequenceType.ItemKind.ATOMIC) {
                    if (!parsedAsType.matches(value)) {
                        throw new XPathException("XTTE0570: Variable $" + localName +
                            ": required type is " + asType +
                            ", supplied value does not match");
                    }
                }
            }
            
            context.getVariableScope().bind(namespaceURI, localName, value);
        } catch (XPathException e) {
            throw new SAXException("Error evaluating variable " + localName, e);
        }
    }
    
    /**
     * Executes the content in sequence construction mode, collecting items
     * into an XPathSequence rather than building a result tree fragment.
     * 
     * <p>In sequence construction, each instruction produces separate items.
     * We mark item boundaries between instructions to ensure text nodes
     * from different instructions don't get merged.
     */
    private XPathValue executeSequenceConstructor(TransformContext context) throws SAXException {
        SequenceBuilderOutputHandler seqBuilder = new SequenceBuilderOutputHandler();
        // Execute each child instruction with item boundaries
        if (content.getChildren() != null) {
            for (XSLTNode child : content.getChildren()) {
                child.execute(context, seqBuilder);
                // Mark boundary between instructions to prevent text merging
                seqBuilder.markItemBoundary();
            }
        }
        return seqBuilder.getSequence();
    }
}

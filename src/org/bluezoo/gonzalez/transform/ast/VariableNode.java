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
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import org.xml.sax.SAXException;

import org.bluezoo.gonzalez.transform.ErrorHandlingMode;
import org.bluezoo.gonzalez.transform.compiler.ExpressionHolder;
import org.bluezoo.gonzalez.transform.compiler.TemplateParameter;
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
import org.bluezoo.gonzalez.transform.xpath.type.XPathBoolean;
import org.bluezoo.gonzalez.transform.xpath.type.XPathDateTime;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNumber;
import org.bluezoo.gonzalez.transform.xpath.type.XPathString;
import org.bluezoo.gonzalez.transform.xpath.type.XPathUntypedAtomic;
import org.bluezoo.gonzalez.transform.xpath.type.SchemaContext;
import org.bluezoo.gonzalez.transform.xpath.type.SequenceType;
import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;

/**
 * VariableNode XSLT instruction.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public class VariableNode extends XSLTInstruction implements ExpressionHolder {
    private final String namespaceURI;
    private final String localName;
    private final XPathExpression selectExpr;
    private final SequenceNode content;
    private final String asType; // XSLT 2.0 type annotation
    private final SequenceType parsedAsType; // Pre-parsed SequenceType for runtime checking
    
    public VariableNode(String namespaceURI, String localName, XPathExpression selectExpr, 
                SequenceNode content, String asType) {
        this(namespaceURI, localName, selectExpr, content, asType, null);
    }

    public VariableNode(String namespaceURI, String localName, XPathExpression selectExpr, 
                SequenceNode content, String asType, SequenceType parsedAsType) {
        this.namespaceURI = namespaceURI;
        this.localName = localName;
        this.selectExpr = selectExpr;
        this.content = content;
        this.asType = asType;
        this.parsedAsType = parsedAsType != null ? parsedAsType : SequenceType.parse(asType, null);
    }
    
    @Override public String getInstructionName() { return "variable"; }
    public String getName() { return localName; }
    public SequenceNode getContent() { return content; }

    @Override
    public List<XPathExpression> getExpressions() {
        List<XPathExpression> exprs = new ArrayList<XPathExpression>();
        if (selectExpr != null) {
            exprs.add(selectExpr);
        }
        return exprs;
    }

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
            
            // XTTE0570: Check value against declared 'as' type
            if (parsedAsType != null && value != null) {
                boolean shouldValidate = true;
                SequenceType.ItemKind kind = parsedAsType.getItemKind();
                if (kind == SequenceType.ItemKind.ATOMIC && containsAtomizableNodes(value)) {
                    String targetLocal = parsedAsType.getLocalName();
                    value = atomizeNodesToType(value, targetLocal);
                    shouldValidate = false;
                } else if (kind == SequenceType.ItemKind.ATOMIC && containsTextNodes(value)) {
                    // Sequence of text nodes from fork/sequence buffering - atomize each item
                    String targetLocal = parsedAsType.getLocalName();
                    value = atomizeTextNodeSequence(value, targetLocal);
                    shouldValidate = false;
                } else if (kind == SequenceType.ItemKind.DOCUMENT_NODE) {
                    // RTFs are document-node equivalents but don't implement XPathNode,
                    // so strict matching fails. Skip for document-node types.
                    shouldValidate = false;
                }
                if (shouldValidate && !parsedAsType.matches(value, SchemaContext.NONE)) {
                    if (value instanceof XPathUntypedAtomic) {
                        value = coerceUntypedAtomic((XPathUntypedAtomic) value, parsedAsType);
                    } else if (kind == SequenceType.ItemKind.ATOMIC &&
                               "string".equals(parsedAsType.getLocalName()) &&
                               value instanceof org.bluezoo.gonzalez.transform.xpath.type.XPathAnyURI) {
                        value = org.bluezoo.gonzalez.transform.xpath.type.XPathString.of(value.asString());
                    } else {
                        throw new XPathException("XTTE0570: Variable $" + localName +
                            ": required type is " + asType +
                            ", supplied value does not match");
                    }
                }
                // Promote numeric values to the declared type so that
                // instance-of checks reflect the declared type correctly
                if (kind == SequenceType.ItemKind.ATOMIC && value instanceof XPathNumber) {
                    value = promoteNumericType((XPathNumber) value, parsedAsType.getLocalName());
                }
            }
            
            context.getVariableScope().bind(namespaceURI, localName, value);
        } catch (XPathException e) {
            // Per spec, circular references are only errors when the variable
            // is actually used. Bind a deferred-error sentinel so execution
            // continues; the error will surface only if the variable is accessed.
            if (containsCircularReference(e)) {
                context.getVariableScope().bind(namespaceURI, localName, 
                    new DeferredErrorValue("XTDE0640: Circular reference in variable: $" + localName));
                return;
            }
            throw new SAXException("Error evaluating variable " + localName, e);
        } catch (SAXException e) {
            if (containsCircularReference(e)) {
                context.getVariableScope().bind(namespaceURI, localName, 
                    new DeferredErrorValue("XTDE0640: Circular reference in variable: $" + localName));
                return;
            }
            throw e;
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
    /**
     * Returns true if the value contains only text nodes (SequenceTextItem instances),
     * which are produced when fork branch output is buffered and replayed as characters.
     */
    private static boolean containsTextNodes(XPathValue value) {
        if (value instanceof XPathNode) {
            return ((XPathNode) value).getNodeType() == NodeType.TEXT;
        }
        if (value instanceof XPathSequence) {
            boolean hasTextNode = false;
            for (XPathValue item : (XPathSequence) value) {
                boolean isText = (item instanceof XPathNode)
                    && ((XPathNode) item).getNodeType() == NodeType.TEXT;
                boolean isAtomic = !(item instanceof XPathNode)
                    && !(item instanceof XPathNodeSet)
                    && !(item instanceof XPathResultTreeFragment);
                if (!isText && !isAtomic) {
                    return false;
                }
                if (isText) {
                    hasTextNode = true;
                }
            }
            return hasTextNode;
        }
        return false;
    }

    /**
     * Atomizes a value containing nodes (elements, attributes, RTFs) to the
     * target atomic type. For sequences or node sets with multiple items,
     * each item is atomized individually rather than concatenating all text.
     * Per XSLT function conversion rules, xs:untypedAtomic values are also
     * cast to the target type.
     */
    private static XPathValue atomizeNodesToType(XPathValue value,
                                                  String targetLocal) throws XPathException {
        if (value instanceof XPathSequence) {
            List<XPathValue> atomized = new ArrayList<XPathValue>();
            for (XPathValue item : (XPathSequence) value) {
                atomized.add(atomizeSingleItem(item, targetLocal));
            }
            if (atomized.size() == 1) {
                return atomized.get(0);
            }
            return new XPathSequence(atomized);
        }
        if (value.isNodeSet()) {
            XPathNodeSet ns = value.asNodeSet();
            List<XPathNode> nodes = ns.getNodes();
            if (nodes.size() > 1) {
                List<XPathValue> atomized = new ArrayList<XPathValue>();
                for (XPathNode node : nodes) {
                    String text = node.getStringValue();
                    atomized.add(castStringToAtomicType(text, targetLocal));
                }
                return new XPathSequence(atomized);
            }
        }
        return atomizeSingleItem(value, targetLocal);
    }

    /**
     * Atomizes or casts a single item to the target atomic type.
     * Nodes are atomized to string and cast. xs:untypedAtomic values are
     * cast to the target type. Other atomic values are preserved as-is.
     */
    private static XPathValue atomizeSingleItem(XPathValue item,
                                                 String targetLocal) throws XPathException {
        if (item instanceof XPathNode || item.isNodeSet()
                || item instanceof XPathResultTreeFragment) {
            String text = item.asString();
            return castStringToAtomicType(text, targetLocal);
        }
        if (item instanceof XPathUntypedAtomic) {
            String text = item.asString();
            return castStringToAtomicType(text, targetLocal);
        }
        return item;
    }

    /**
     * Atomizes a sequence of text nodes, converting each to the target atomic type.
     * This is used when fork branch output was buffered as characters and needs
     * to be reconstituted as a sequence of atomic values.
     */
    private static XPathValue atomizeTextNodeSequence(XPathValue value,
                                                       String targetLocal) throws XPathException {
        if (value instanceof XPathNode) {
            String text = ((XPathNode) value).getStringValue();
            return castStringToAtomicType(text, targetLocal);
        }
        if (value instanceof XPathSequence) {
            List<XPathValue> items = new ArrayList<XPathValue>();
            for (XPathValue item : (XPathSequence) value) {
                if (item instanceof XPathNode) {
                    String text = ((XPathNode) item).getStringValue();
                    items.add(castStringToAtomicType(text, targetLocal));
                } else {
                    items.add(item);
                }
            }
            if (items.size() == 1) {
                return items.get(0);
            }
            return new XPathSequence(items);
        }
        return value;
    }

    /**
     * Returns true if the value contains element or attribute nodes that would
     * need atomization before being compared to an atomic type.
     * Text nodes are excluded since their string value is already the atomic value.
     */
    private static boolean containsAtomizableNodes(XPathValue value) {
        if (value instanceof XPathResultTreeFragment) {
            return true;
        }
        if (value instanceof XPathNode) {
            NodeType nt = ((XPathNode) value).getNodeType();
            return nt == NodeType.ELEMENT || nt == NodeType.ATTRIBUTE;
        }
        if (value.isNodeSet()) {
            XPathNodeSet ns = value.asNodeSet();
            for (XPathNode node : ns.getNodes()) {
                NodeType nt = node.getNodeType();
                if (nt == NodeType.ELEMENT || nt == NodeType.ATTRIBUTE) {
                    return true;
                }
            }
            return false;
        }
        if (value instanceof XPathSequence) {
            for (XPathValue item : (XPathSequence) value) {
                if (containsAtomizableNodes(item)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Returns true if the value contains document nodes or RTFs.
     * Used to skip strict XTTE0570 validation for document-node() types
     * since the runtime representation may be an RTF rather than an XPathNode.
     */
    private static boolean containsDocumentNodes(XPathValue value) {
        if (value instanceof XPathResultTreeFragment) {
            return true;
        }
        if (value instanceof XPathNode) {
            return ((XPathNode) value).getNodeType() == NodeType.ROOT;
        }
        if (value instanceof XPathSequence) {
            for (XPathValue item : (XPathSequence) value) {
                if (containsDocumentNodes(item)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Coerces an xs:untypedAtomic value to the target atomic type per XSLT
     * function conversion rules (XSLT 2.0 §5.4.3). xs:untypedAtomic is
     * promotable to xs:string and castable to most other atomic types.
     */
    private static XPathValue castStringToAtomicType(String textContent, 
                                                      String targetLocal) throws XPathException {
        if (targetLocal == null) {
            return new XPathUntypedAtomic(textContent);
        }
        return TemplateParameter.castStringToAtomicType(textContent, targetLocal);
    }
    
    private static XPathValue coerceUntypedAtomic(XPathUntypedAtomic value,
                                                   SequenceType targetType) {
        String targetLocal = targetType.getLocalName();
        if (targetLocal == null || "anyAtomicType".equals(targetLocal)) {
            return value;
        }
        if ("string".equals(targetLocal) || "normalizedString".equals(targetLocal)
                || "token".equals(targetLocal) || "language".equals(targetLocal)
                || "NMTOKEN".equals(targetLocal) || "Name".equals(targetLocal)
                || "NCName".equals(targetLocal) || "ID".equals(targetLocal)
                || "IDREF".equals(targetLocal) || "ENTITY".equals(targetLocal)) {
            return XPathString.of(value.asString());
        }
        // For other atomic types, return as-is and let downstream handle it
        return value;
    }

    /**
     * Promotes a numeric value to the declared target type so that the
     * internal XPathNumber flags match the declared xs:type.
     */
    private static XPathValue promoteNumericType(XPathNumber number, String targetLocal) {
        if (targetLocal == null) {
            return number;
        }
        switch (targetLocal) {
            case "double":
                if (!number.isExplicitDouble()) {
                    return XPathNumber.ofExplicitDouble(number.asNumber());
                }
                break;
            case "float":
                if (!number.isFloat()) {
                    return new XPathNumber(number.asNumber(), true);
                }
                break;
        }
        return number;
    }

    private XPathValue executeSequenceConstructor(TransformContext context) throws SAXException {
        String baseUri = (staticBaseURI != null) ? staticBaseURI : context.getStaticBaseURI();
        SequenceBuilderOutputHandler seqBuilder = new SequenceBuilderOutputHandler(baseUri);
        if (content.getChildren() != null) {
            for (XSLTNode child : content.getChildren()) {
                child.execute(context, seqBuilder);
                seqBuilder.markItemBoundary();
            }
        }
        return seqBuilder.getSequence();
    }

    private static boolean containsCircularReference(Throwable t) {
        while (t != null) {
            String msg = t.getMessage();
            if (msg != null && msg.contains("XTDE0640")) {
                return true;
            }
            t = t.getCause();
        }
        return false;
    }

    /**
     * Sentinel value bound to a variable whose evaluation hit a circular
     * reference. Any attempt to read the value raises the deferred error.
     * If the variable is never accessed, the error is silently ignored.
     */
    private static class DeferredErrorValue implements XPathValue {
        private final String message;

        DeferredErrorValue(String message) {
            this.message = message;
        }

        private RuntimeException error() {
            return new RuntimeException(message);
        }

        @Override
        public Type getType() {
            return Type.STRING;
        }

        @Override
        public String asString() {
            throw error();
        }

        @Override
        public double asNumber() {
            throw error();
        }

        @Override
        public boolean asBoolean() {
            throw error();
        }

        @Override
        public XPathNodeSet asNodeSet() {
            throw error();
        }
    }
}

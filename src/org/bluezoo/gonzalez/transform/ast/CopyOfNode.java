/*
 * CopyOfNode.java
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
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import org.xml.sax.SAXException;

import org.bluezoo.gonzalez.transform.ValidationMode;
import org.bluezoo.gonzalez.transform.compiler.CompiledStylesheet;
import org.bluezoo.gonzalez.transform.compiler.ExpressionHolder;
import org.bluezoo.gonzalez.transform.compiler.AttributeValueTemplate;
import org.bluezoo.gonzalez.transform.compiler.ModeDeclaration;
import org.bluezoo.gonzalez.transform.compiler.SequenceBuilderOutputHandler;
import org.bluezoo.gonzalez.transform.runtime.OutputHandler;
import org.bluezoo.gonzalez.transform.runtime.OutputHandlerUtils;
import org.bluezoo.gonzalez.transform.runtime.TransformContext;
import org.bluezoo.gonzalez.transform.xpath.XPathExpression;
import org.bluezoo.gonzalez.transform.xpath.expr.XPathException;
import org.bluezoo.gonzalez.transform.xpath.function.SequenceFunctions;
import org.bluezoo.gonzalez.transform.xpath.type.XPathArray;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNode;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNodeSet;
import org.bluezoo.gonzalez.transform.xpath.type.XPathResultTreeFragment;
import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;

/**
 * CopyOfNode XSLT instruction.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public class CopyOfNode extends XSLTInstruction implements ExpressionHolder {
    private final XPathExpression selectExpr;
    private final String typeNamespaceURI;
    private final String typeLocalName;
    private final ValidationMode validation;
    private final boolean copyNamespaces;          // Static value (when no AVT)
    private final AttributeValueTemplate copyNamespacesAvt;  // AVT (XSLT 3.0 shadow attribute)
    private final boolean copyAccumulators;
    
    public CopyOfNode(XPathExpression selectExpr) { 
        this(selectExpr, null, null, null, true, null, false);
    }
    
    public CopyOfNode(XPathExpression selectExpr, String typeNamespaceURI, String typeLocalName,
               ValidationMode validation, boolean copyNamespaces) {
        this(selectExpr, typeNamespaceURI, typeLocalName, validation, copyNamespaces, null, false);
    }
    
    public CopyOfNode(XPathExpression selectExpr, String typeNamespaceURI, String typeLocalName,
               ValidationMode validation, boolean copyNamespaces, AttributeValueTemplate copyNamespacesAvt) {
        this(selectExpr, typeNamespaceURI, typeLocalName, validation, copyNamespaces, copyNamespacesAvt, false);
    }
    
    public CopyOfNode(XPathExpression selectExpr, String typeNamespaceURI, String typeLocalName,
               ValidationMode validation, boolean copyNamespaces, AttributeValueTemplate copyNamespacesAvt,
               boolean copyAccumulators) {
        this.selectExpr = selectExpr;
        this.typeNamespaceURI = typeNamespaceURI;
        this.typeLocalName = typeLocalName;
        this.validation = validation;
        this.copyNamespaces = copyNamespaces;
        this.copyNamespacesAvt = copyNamespacesAvt;
        this.copyAccumulators = copyAccumulators;
    }
    
    @Override public String getInstructionName() { return "copy-of"; }

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
            // Use instruction's static base URI if set (for xml:base support and doc(''))
            TransformContext evalContext = (staticBaseURI != null) 
                ? context.withStaticBaseURI(staticBaseURI) 
                : context;
                
            XPathValue result = selectExpr.evaluate(evalContext);
            
            if (result == null) {
                // Variable not found or expression returned null - output nothing
                return;
            }
            
            // Determine effective validation mode
            ValidationMode effectiveValidation = validation;
            if (effectiveValidation == null) {
                effectiveValidation = context.getStylesheet().getDefaultValidation();
            }
            
            // Determine effective copy-namespaces value (evaluate AVT if present)
            boolean effectiveCopyNamespaces = copyNamespaces;
            if (copyNamespacesAvt != null) {
                String copyNsStr = copyNamespacesAvt.evaluate(evalContext).trim();
                effectiveCopyNamespaces = "yes".equals(copyNsStr) || "true".equals(copyNsStr) || "1".equals(copyNsStr);
            }
            
            // XTDE3362: in XSLT 3.0, copy-accumulators="yes" requires that
            // accumulators are applicable to the source document. Without an
            // xsl:mode declaration for the initial mode, accumulators are not
            // applicable. When xsl:mode IS declared, the default for
            // use-accumulators is #all.
            if (copyAccumulators) {
                CompiledStylesheet stylesheet = context.getStylesheet();
                if (stylesheet != null && stylesheet.getVersion() >= 3.0
                        && !stylesheet.getAccumulators().isEmpty()) {
                    ModeDeclaration modeDecl = stylesheet.getModeDeclaration(null);
                    if (modeDecl == null) {
                        throw new SAXException(
                            "XTDE3362: copy-accumulators is set to yes"
                            + " but no accumulators are applicable to"
                            + " the source document (no xsl:mode"
                            + " declaration for the initial mode)");
                    }
                }
            }

            if (result instanceof XPathResultTreeFragment) {
                XPathResultTreeFragment rtf = (XPathResultTreeFragment) result;
                if (output instanceof SequenceBuilderOutputHandler) {
                    // Deep copy: replay all events into a new buffer to create
                    // a new RTF with new node identity
                    org.bluezoo.gonzalez.transform.runtime.SAXEventBuffer copyBuffer =
                        new org.bluezoo.gonzalez.transform.runtime.SAXEventBuffer();
                    rtf.getBuffer().replay(copyBuffer);
                    XPathResultTreeFragment copy =
                        new XPathResultTreeFragment(copyBuffer);
                    ((SequenceBuilderOutputHandler) output).addItem(copy);
                } else {
                    boolean includeTypes = effectiveValidation != ValidationMode.STRIP;
                    rtf.replayToOutput(output, includeTypes);
                }
            } else if (result instanceof XPathNodeSet) {
                XPathNodeSet nodeSet = (XPathNodeSet) result;
                if (copyAccumulators && output instanceof SequenceBuilderOutputHandler) {
                    SequenceBuilderOutputHandler seqOut = (SequenceBuilderOutputHandler) output;
                    for (XPathNode node : nodeSet.getNodes()) {
                        XPathNode copied = SequenceFunctions.createCopiedNode(node);
                        List<XPathNode> singleNode = new ArrayList<XPathNode>();
                        singleNode.add(copied);
                        seqOut.addItem(new XPathNodeSet(singleNode));
                    }
                } else {
                    for (XPathNode node : nodeSet.getNodes()) {
                        deepCopyNode(node, output, effectiveValidation, effectiveCopyNamespaces, 0);
                    }
                }
            } else if (result instanceof XPathArray) {
                // XPath 3.1 array - iterate over members and copy each
                XPathArray arr = (XPathArray) result;
                boolean needSpace = false;
                for (XPathValue member : arr.members()) {
                    needSpace = copySequenceItem(member, output, effectiveValidation,
                        effectiveCopyNamespaces, needSpace);
                }
            } else if (result.isSequence()) {
                // Handle XPath 2.0 sequences - iterate over items
                java.util.Iterator<XPathValue> iter = result.sequenceIterator();
                boolean needSpace = false;
                while (iter.hasNext()) {
                    needSpace = copySequenceItem(iter.next(), output,
                        effectiveValidation, effectiveCopyNamespaces, needSpace);
                }
            } else if (result instanceof XPathNode) {
                // Single node value (e.g. attribute from xsl:function)
                deepCopyNode((XPathNode) result, output, effectiveValidation,
                    effectiveCopyNamespaces, 0);
            } else if (result.isNodeSet()) {
                XPathNodeSet nodeSet = result.asNodeSet();
                for (XPathNode node : nodeSet.getNodes()) {
                    deepCopyNode(node, output, effectiveValidation,
                        effectiveCopyNamespaces, 0);
                }
            } else {
                // For atomic values, use atomicValue() for proper
                // space separation between adjacent values (XSLT 2.0+)
                output.atomicValue(result);
            }
        } catch (XPathException e) {
            throw new SAXException("Error in xsl:copy-of", e);
        }
    }
    
    /**
     * Copies a single sequence/array item to the output.
     * Handles RTFs, node sets, arrays, and atomic values.
     *
     * @return true if a space separator is needed before the next atomic value
     */
    private boolean copySequenceItem(XPathValue item, OutputHandler output,
            ValidationMode effectiveValidation, boolean effectiveCopyNamespaces,
            boolean needSpace) throws SAXException {
        if (item instanceof XPathResultTreeFragment) {
            XPathResultTreeFragment rtf = (XPathResultTreeFragment) item;
            if (output instanceof SequenceBuilderOutputHandler) {
                ((SequenceBuilderOutputHandler) output).addItem(rtf);
            } else {
                boolean includeTypes = effectiveValidation != ValidationMode.STRIP;
                rtf.replayToOutput(output, includeTypes);
            }
            return false;
        } else if (item instanceof XPathArray) {
            XPathArray arr = (XPathArray) item;
            boolean ns = needSpace;
            for (XPathValue member : arr.members()) {
                ns = copySequenceItem(member, output, effectiveValidation,
                    effectiveCopyNamespaces, ns);
            }
            return ns;
        } else if (item instanceof XPathNodeSet) {
            XPathNodeSet nodeSet = (XPathNodeSet) item;
            for (XPathNode node : nodeSet.getNodes()) {
                deepCopyNode(node, output, effectiveValidation, effectiveCopyNamespaces, 0);
            }
            return false;
        } else if (item instanceof XPathNode) {
            deepCopyNode((XPathNode) item, output, effectiveValidation, effectiveCopyNamespaces, 0);
            return false;
        } else if (item.isNodeSet()) {
            XPathNodeSet nodeSet = item.asNodeSet();
            for (XPathNode node : nodeSet.getNodes()) {
                deepCopyNode(node, output, effectiveValidation, effectiveCopyNamespaces, 0);
            }
            return false;
        } else if (item.isSequence()) {
            Iterator<XPathValue> iter = item.sequenceIterator();
            boolean ns = needSpace;
            while (iter.hasNext()) {
                ns = copySequenceItem(iter.next(), output, effectiveValidation,
                    effectiveCopyNamespaces, ns);
            }
            return ns;
        } else {
            output.atomicValue(item);
            return true;
        }
    }

    /**
     * Deep copies a node to the output.
     * @param node the node to copy
     * @param output the output handler
     * @param effectiveValidation validation mode
     * @param effectiveCopyNamespaces whether to copy namespace declarations
     * @param depth 0 for directly selected nodes, >0 for children of copied nodes
     */
    private void deepCopyNode(XPathNode node, OutputHandler output, 
                              ValidationMode effectiveValidation, boolean effectiveCopyNamespaces, int depth) throws SAXException {
        switch (node.getNodeType()) {
            case ELEMENT:
                String uri = node.getNamespaceURI() != null ? node.getNamespaceURI() : "";
                String localName = node.getLocalName();
                String prefix = node.getPrefix();
                String qName = prefix != null ? prefix + ":" + localName : localName;
                
                output.startElement(uri, localName, qName);
                
                // Set type annotation based on validation mode
                if (typeLocalName != null) {
                    // Explicit type attribute
                    output.setElementType(typeNamespaceURI, typeLocalName);
                } else if (effectiveValidation == ValidationMode.PRESERVE) {
                    // Preserve mode - copy type annotation from source
                    String srcTypeNs = node.getTypeNamespaceURI();
                    String srcTypeLocal = node.getTypeLocalName();
                    if (srcTypeLocal != null) {
                        output.setElementType(srcTypeNs, srcTypeLocal);
                    }
                } else if (effectiveValidation == ValidationMode.STRICT || 
                           effectiveValidation == ValidationMode.LAX) {
                    // Note: For copy-of, we would need the context to do runtime validation
                    // For now, preserve source type annotation if available
                    String srcTypeNs = node.getTypeNamespaceURI();
                    String srcTypeLocal = node.getTypeLocalName();
                    if (srcTypeLocal != null) {
                        output.setElementType(srcTypeNs, srcTypeLocal);
                    }
                }
                // STRIP mode - don't set any type annotation
                
                // Always emit the namespace declaration for the element's own
                // prefix, even when copy-namespaces="no". Without it the
                // serialized output would be ill-formed.
                if (prefix != null && !prefix.isEmpty()) {
                    output.namespace(prefix, uri);
                } else {
                    output.namespace("", uri);
                }
                
                // Copy namespace declarations (if copy-namespaces="yes")
                if (effectiveCopyNamespaces) {
                    boolean hasDefaultNs = false;
                    Iterator<XPathNode> namespaces = 
                        node.getNamespaces();
                    while (namespaces.hasNext()) {
                        XPathNode ns = namespaces.next();
                        String nsPrefix = ns.getLocalName();
                        String nsUri = ns.getStringValue();
                        if ("xml".equals(nsPrefix)) {
                            continue;
                        }
                        
                        if (nsPrefix == null || nsPrefix.isEmpty()) {
                            hasDefaultNs = true;
                            if (prefix != null && !prefix.isEmpty()) {
                                if (nsUri != null && !nsUri.isEmpty()) {
                                    output.namespace("", nsUri);
                                } else if (depth > 0) {
                                    output.namespace("", "");
                                }
                            }
                        } else {
                            if (nsUri != null && !nsUri.isEmpty()) {
                                output.namespace(nsPrefix, nsUri);
                            } else if (prefix == null || prefix.isEmpty()) {
                                output.namespace(nsPrefix, "");
                            }
                        }
                    }
                    // If no default namespace node found and this is a prefixed element
                    // inside a tree copy, emit xmlns="" to undeclare any inherited default
                    // namespace. XMLWriter will suppress it if redundant.
                    if (!hasDefaultNs && depth > 0 && prefix != null && !prefix.isEmpty()) {
                        output.namespace("", "");
                    }
                }
                
                // Copy attributes
                Iterator<XPathNode> attrs = 
                    node.getAttributes();
                while (attrs.hasNext()) {
                    XPathNode attr = attrs.next();
                    String attrUri = attr.getNamespaceURI() != null ? attr.getNamespaceURI() : "";
                    String attrLocal = attr.getLocalName();
                    String attrPrefix = attr.getPrefix();
                    if (attrPrefix == null 
                            && "http://www.w3.org/XML/1998/namespace".equals(attrUri)) {
                        attrPrefix = "xml";
                    }
                    String attrQName = attrPrefix != null ? attrPrefix + ":" + attrLocal : attrLocal;
                    output.attribute(attrUri, attrLocal, attrQName, attr.getStringValue());
                }
                
                // Copy children (depth+1 to indicate we're inside a tree copy)
                Iterator<XPathNode> children = 
                    node.getChildren();
                while (children.hasNext()) {
                    deepCopyNode(children.next(), output, effectiveValidation, effectiveCopyNamespaces, depth + 1);
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
                if (output instanceof SequenceBuilderOutputHandler) {
                    // Buffer children into an RTF to preserve document node identity
                    org.bluezoo.gonzalez.transform.runtime.SAXEventBuffer docBuf =
                        new org.bluezoo.gonzalez.transform.runtime.SAXEventBuffer();
                    org.bluezoo.gonzalez.transform.runtime.BufferOutputHandler bufOut =
                        new org.bluezoo.gonzalez.transform.runtime.BufferOutputHandler(docBuf);
                    Iterator<XPathNode> docChildren = node.getChildren();
                    while (docChildren.hasNext()) {
                        deepCopyNode(docChildren.next(), bufOut, effectiveValidation, effectiveCopyNamespaces, depth + 1);
                    }
                    ((SequenceBuilderOutputHandler) output).addItem(
                        new XPathResultTreeFragment(docBuf));
                } else {
                    Iterator<XPathNode> rootChildren = node.getChildren();
                    while (rootChildren.hasNext()) {
                        deepCopyNode(rootChildren.next(), output, effectiveValidation, effectiveCopyNamespaces, depth + 1);
                    }
                }
                break;
                
            case ATTRIBUTE:
                // Copy attribute node (e.g., from xsl:copy-of select="@*")
                String atUri = node.getNamespaceURI() != null ? node.getNamespaceURI() : "";
                String atLocal = node.getLocalName();
                String atPrefix = node.getPrefix();
                String atQName = atPrefix != null ? atPrefix + ":" + atLocal : atLocal;
                if (atPrefix != null && !atPrefix.isEmpty() && !atUri.isEmpty()) {
                    output.namespace(atPrefix, atUri);
                }
                output.attribute(atUri, atLocal, atQName, node.getStringValue());
                break;
                
            case NAMESPACE:
                // Copy namespace node as namespace declaration on current result element
                String nsPrefix = node.getLocalName();
                if (nsPrefix == null) {
                    nsPrefix = "";
                }
                String nsUri = node.getStringValue();
                if (nsUri == null) {
                    nsUri = "";
                }
                if (!"xml".equals(nsPrefix)) {
                    output.namespace(nsPrefix, nsUri);
                }
                break;
                
            default:
                break;
        }
    }

}

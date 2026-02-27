/*
 * CopyNode.java
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
import java.util.Iterator;
import java.util.Set;
import java.util.StringTokenizer;

import org.xml.sax.SAXException;

import org.bluezoo.gonzalez.transform.ValidationMode;
import org.bluezoo.gonzalez.transform.compiler.AttributeSet;
import org.bluezoo.gonzalez.transform.compiler.AttributeValueTemplate;
import org.bluezoo.gonzalez.transform.compiler.CompiledStylesheet;
import org.bluezoo.gonzalez.transform.runtime.OutputHandler;
import org.bluezoo.gonzalez.transform.runtime.RuntimeSchemaValidator;
import org.bluezoo.gonzalez.transform.runtime.TransformContext;
import org.bluezoo.gonzalez.transform.xpath.XPathExpression;
import org.bluezoo.gonzalez.transform.xpath.expr.XPathException;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNode;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNodeSet;
import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;

/**
 * CopyNode XSLT instruction.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public class CopyNode extends XSLTInstruction {
    private final XPathExpression selectExpr;          // XSLT 3.0 - select items to copy (null = context item)
    private final String useAttrSets;
    private final SequenceNode content;
    private final String typeNamespaceURI;
    private final String typeLocalName;
    private final ValidationMode validation;
    private final boolean inheritNamespaces;           // Static value (when no AVT)
    private final AttributeValueTemplate inheritNamespacesAvt;  // AVT (XSLT 3.0 shadow attribute)
    private final boolean copyNamespaces;              // XSLT 2.0 - copy namespace nodes (default: true)
    private final AttributeValueTemplate copyNamespacesAvt;   // AVT (XSLT 3.0 shadow attribute)
    private final XSLTNode onEmptyNode;                // XSLT 3.0 - content when copy produces empty result
    
    public CopyNode(String useAttrSets, SequenceNode content) {
        this(null, useAttrSets, content, null, null, null, true, null, true, null, null);
    }
    
    public CopyNode(String useAttrSets, SequenceNode content, 
            String typeNamespaceURI, String typeLocalName, ValidationMode validation) {
        this(null, useAttrSets, content, typeNamespaceURI, typeLocalName, validation, true, null, true, null, null);
    }
    
    public CopyNode(String useAttrSets, SequenceNode content, 
            String typeNamespaceURI, String typeLocalName, ValidationMode validation,
            boolean inheritNamespaces, AttributeValueTemplate inheritNamespacesAvt) {
        this(null, useAttrSets, content, typeNamespaceURI, typeLocalName, validation, 
             inheritNamespaces, inheritNamespacesAvt, true, null, null);
    }
    
    public CopyNode(String useAttrSets, SequenceNode content, 
            String typeNamespaceURI, String typeLocalName, ValidationMode validation,
            boolean inheritNamespaces, AttributeValueTemplate inheritNamespacesAvt,
            boolean copyNamespaces, AttributeValueTemplate copyNamespacesAvt) {
        this(null, useAttrSets, content, typeNamespaceURI, typeLocalName, validation,
             inheritNamespaces, inheritNamespacesAvt, copyNamespaces, copyNamespacesAvt, null);
    }
    
    public CopyNode(XPathExpression selectExpr, String useAttrSets, SequenceNode content, 
            String typeNamespaceURI, String typeLocalName, ValidationMode validation,
            boolean inheritNamespaces, AttributeValueTemplate inheritNamespacesAvt,
            boolean copyNamespaces, AttributeValueTemplate copyNamespacesAvt, XSLTNode onEmptyNode) {
        this.selectExpr = selectExpr;
        this.useAttrSets = useAttrSets;
        this.content = content;
        this.typeNamespaceURI = typeNamespaceURI;
        this.typeLocalName = typeLocalName;
        this.validation = validation;
        this.inheritNamespaces = inheritNamespaces;
        this.inheritNamespacesAvt = inheritNamespacesAvt;
        this.copyNamespaces = copyNamespaces;
        this.copyNamespacesAvt = copyNamespacesAvt;
        this.onEmptyNode = onEmptyNode;
    }
    
    @Override public String getInstructionName() { return "copy"; }
    @Override public void execute(TransformContext context, 
                                  OutputHandler output) throws SAXException {
        // XSLT 3.0: if select attribute is present, copy selected items
        // Otherwise, copy the context item (XSLT 1.0/2.0 behavior)
        XPathNode node;
        if (selectExpr != null) {
            try {
                XPathValue result = selectExpr.evaluate(context);
                if (result == null || (result.isNodeSet() && ((XPathNodeSet) result).isEmpty())) {
                    // Empty result - execute on-empty if present
                    if (onEmptyNode != null) {
                        onEmptyNode.execute(context, output);
                    }
                    return;
                }
                if (result.isNodeSet()) {
                    // Copy each node in the node-set
                    for (XPathNode n : ((XPathNodeSet) result).getNodes()) {
                        executeCopyForNode(n, context, output);
                    }
                    return;
                } else if (result instanceof XPathNode) {
                    node = (XPathNode) result;
                } else {
                    // Atomic value - output as text
                    output.characters(result.asString());
                    return;
                }
            } catch (XPathException e) {
                throw new SAXException("Error evaluating xsl:copy select", e);
            }
        } else {
            node = context.getContextNode();
        }
        
        if (node == null) {
            return;
        }
        
        executeCopyForNode(node, context, output);
    }
    
    private void executeCopyForNode(XPathNode node, TransformContext context, 
                                   OutputHandler output) throws SAXException {
        
        // Determine effective validation mode
        ValidationMode effectiveValidation = validation;
        if (effectiveValidation == null) {
            effectiveValidation = context.getStylesheet().getDefaultValidation();
        }
        
        // Determine effective inherit-namespaces value (evaluate AVT if present)
        boolean effectiveInheritNamespaces = inheritNamespaces;
        if (inheritNamespacesAvt != null) {
            try {
                String inheritNsStr = inheritNamespacesAvt.evaluate(context).trim();
                effectiveInheritNamespaces = "yes".equals(inheritNsStr) || "true".equals(inheritNsStr) || "1".equals(inheritNsStr);
            } catch (XPathException e) {
                throw new SAXException("Error evaluating inherit-namespaces AVT", e);
            }
        }
        
        // Determine effective copy-namespaces value (evaluate AVT if present)
        boolean effectiveCopyNamespaces = copyNamespaces;
        if (copyNamespacesAvt != null) {
            try {
                String copyNsStr = copyNamespacesAvt.evaluate(context).trim();
                effectiveCopyNamespaces = "yes".equals(copyNsStr) || "true".equals(copyNsStr) || "1".equals(copyNsStr);
            } catch (XPathException e) {
                throw new SAXException("Error evaluating copy-namespaces AVT", e);
            }
        }
        
        switch (node.getNodeType()) {
            case ELEMENT:
                String uri = node.getNamespaceURI() != null ? node.getNamespaceURI() : "";
                String localName = node.getLocalName();
                String prefix = node.getPrefix();
                String qName = prefix != null ? prefix + ":" + localName : localName;
                
                output.startElement(uri, localName, qName);
                
                // Get validator for potential use
                RuntimeSchemaValidator validator = context.getRuntimeValidator();
                
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
                    // Use runtime schema validation to derive type
                    if (validator != null) {
                        try {
                            RuntimeSchemaValidator.ValidationResult valResult =
                                validator.startElement(uri, localName, effectiveValidation);
                            if (valResult.hasTypeAnnotation()) {
                                output.setElementType(valResult.getTypeNamespaceURI(),
                                                      valResult.getTypeLocalName());
                            }
                        } catch (XPathException e) {
                            throw new SAXException("Validation error in xsl:copy", e);
                        }
                    }
                }
                // STRIP mode - don't set any type annotation
                
                // Copy namespace declarations from source element (if copy-namespaces="yes")
                // Per XSLT spec, namespace undeclarations (xmlns="") should NOT be copied
                // because the output tree follows different namespace inheritance rules
                if (effectiveCopyNamespaces) {
                    Iterator<XPathNode> namespaces = 
                        node.getNamespaces();
                    while (namespaces.hasNext()) {
                        XPathNode ns = namespaces.next();
                        String nsPrefix = ns.getLocalName();
                        String nsUri = ns.getStringValue();
                        // Skip xml namespace and namespace undeclarations
                        if (!"xml".equals(nsPrefix) && (nsUri != null && !nsUri.isEmpty())) {
                            output.namespace(nsPrefix, nsUri);
                        }
                    }
                }
                
                // Apply use-attribute-sets if specified
                if (useAttrSets != null && !useAttrSets.isEmpty()) {
                    CompiledStylesheet stylesheet = context.getStylesheet();
                    StringTokenizer st = new StringTokenizer(useAttrSets);
                    while (st.hasMoreTokens()) {
                        String setName = st.nextToken();
                        AttributeSet attrSet = stylesheet.getAttributeSet(setName);
                        if (attrSet != null) {
                            attrSet.apply(context, output);
                        }
                    }
                }
                
                // Reset atomic separator for fresh content context
                output.setAtomicValuePending(false);
                output.setInAttributeContent(false);
                if (content != null) {
                    content.execute(context, output);
                }
                
                // Complete validation after content execution
                if ((effectiveValidation == ValidationMode.STRICT ||
                     effectiveValidation == ValidationMode.LAX)) {
                    if (validator != null) {
                        try {
                            RuntimeSchemaValidator.ValidationResult endResult = validator.endElement();
                            if (!endResult.isValid() && effectiveValidation == ValidationMode.STRICT) {
                                throw new SAXException(endResult.getErrorCode() + ": " + 
                                                     endResult.getErrorMessage());
                            }
                        } catch (XPathException e) {
                            throw new SAXException("Content model validation error in xsl:copy", e);
                        }
                    }
                }
                
                output.endElement(uri, localName, qName);
                break;
                
            case TEXT:
                output.characters(node.getStringValue());
                break;
                
            case ATTRIBUTE:
                String attrUri = node.getNamespaceURI() != null ? node.getNamespaceURI() : "";
                String attrLocal = node.getLocalName();
                String attrPrefix = node.getPrefix();
                String attrQName = attrPrefix != null ? attrPrefix + ":" + attrLocal : attrLocal;
                output.attribute(attrUri, attrLocal, attrQName, node.getStringValue());
                break;
                
            case COMMENT:
                output.comment(node.getStringValue());
                break;
                
            case PROCESSING_INSTRUCTION:
                output.processingInstruction(node.getLocalName(), node.getStringValue());
                break;
                
            case ROOT:
                // Copy content only
                if (content != null) {
                    content.execute(context, output);
                }
                break;
                
            case NAMESPACE:
                // Copy namespace node - outputs a namespace declaration
                // localName is the prefix, stringValue is the URI
                String nsPrefix = node.getLocalName();
                String nsUri = node.getStringValue();
                // Skip xml namespace (it's implicit)
                if (!"xml".equals(nsPrefix)) {
                    output.namespace(nsPrefix != null ? nsPrefix : "", nsUri);
                }
                // Namespace nodes have no content to process
                break;
                
            default:
                // Unknown node type - just process content
                if (content != null) {
                    content.execute(context, output);
                }
        }
    }
}

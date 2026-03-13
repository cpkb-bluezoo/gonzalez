/*
 * AttributeNode.java
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
import java.util.Map;
import java.util.Set;

import org.xml.sax.SAXException;

import org.bluezoo.gonzalez.schema.xsd.XSDSimpleType;
import org.bluezoo.gonzalez.transform.ValidationMode;
import org.bluezoo.gonzalez.transform.compiler.AttributeValueTemplate;
import org.bluezoo.gonzalez.transform.compiler.ExpressionHolder;
import org.bluezoo.gonzalez.transform.runtime.BufferOutputHandler;
import org.bluezoo.gonzalez.transform.runtime.OutputHandler;
import org.bluezoo.gonzalez.transform.runtime.OutputHandlerUtils;
import org.bluezoo.gonzalez.transform.runtime.RuntimeSchemaValidator;
import org.bluezoo.gonzalez.transform.runtime.SAXEventBuffer;
import org.bluezoo.gonzalez.transform.runtime.TransformContext;
import org.bluezoo.gonzalez.transform.xpath.XPathExpression;
import org.bluezoo.gonzalez.transform.xpath.expr.DynamicPartialItem;
import org.bluezoo.gonzalez.transform.xpath.expr.InlineFunctionItem;
import org.bluezoo.gonzalez.transform.xpath.expr.PartialFunctionItem;
import org.bluezoo.gonzalez.transform.xpath.expr.XPathException;
import org.bluezoo.gonzalez.transform.xpath.type.XPathArray;
import org.bluezoo.gonzalez.transform.xpath.type.XPathFunctionItem;
import org.bluezoo.gonzalez.transform.xpath.type.XPathMap;
import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;
import org.bluezoo.gonzalez.transform.compiler.StylesheetCompiler;

/**
 * AttributeNode XSLT instruction.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public class AttributeNode extends XSLTInstruction implements ExpressionHolder {
    private static final String XSD_NAMESPACE = "http://www.w3.org/2001/XMLSchema";
    private final AttributeValueTemplate nameAvt;
    private final AttributeValueTemplate nsAvt;
    private final XPathExpression selectExpr;
    private final AttributeValueTemplate separatorAvt;
    private final SequenceNode content;
    private final Map<String, String> namespaceBindings;
    private final String typeNamespaceURI;
    private final String typeLocalName;
    private final ValidationMode validation;
    
    public AttributeNode(AttributeValueTemplate nameAvt, AttributeValueTemplate nsAvt, 
                 SequenceNode content, Map<String, String> namespaceBindings,
                 String typeNamespaceURI, String typeLocalName) {
        this(nameAvt, nsAvt, null, (AttributeValueTemplate) null, content, namespaceBindings,
            typeNamespaceURI, typeLocalName, null);
    }
    
    public AttributeNode(AttributeValueTemplate nameAvt, AttributeValueTemplate nsAvt, 
                 SequenceNode content, Map<String, String> namespaceBindings,
                 String typeNamespaceURI, String typeLocalName, ValidationMode validation) {
        this(nameAvt, nsAvt, null, (AttributeValueTemplate) null, content, namespaceBindings,
            typeNamespaceURI, typeLocalName, validation);
    }
    
    public AttributeNode(AttributeValueTemplate nameAvt, AttributeValueTemplate nsAvt,
                 XPathExpression selectExpr, AttributeValueTemplate separatorAvt,
                 SequenceNode content, Map<String, String> namespaceBindings,
                 String typeNamespaceURI, String typeLocalName, ValidationMode validation) {
        this.nameAvt = nameAvt;
        this.nsAvt = nsAvt;
        this.selectExpr = selectExpr;
        this.separatorAvt = separatorAvt;
        this.content = content;
        this.namespaceBindings = namespaceBindings;
        this.typeNamespaceURI = typeNamespaceURI;
        this.typeLocalName = typeLocalName;
        this.validation = validation;
    }
    
    @Override public String getInstructionName() { return "attribute"; }
    public SequenceNode getContent() { return content; }

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
            // Apply static base URI if set (for static-base-uri() function)
            TransformContext evalContext = context;
            if (staticBaseURI != null) {
                evalContext = context.withStaticBaseURI(staticBaseURI);
            }
            
            String name = nameAvt.evaluate(evalContext);
            String namespace = nsAvt != null ? nsAvt.evaluate(evalContext) : null;
            
            // XTDE0850: name must be a valid lexical QName
            if (!ElementNode.isValidQName(name)) {
                throw new SAXException("XTDE0850: '" + name +
                    "' is not a valid QName for xsl:attribute");
            }
            // XTDE0855: name must not be "xmlns"
            if ("xmlns".equals(name)) {
                throw new SAXException("XTDE0855: xsl:attribute name must not be 'xmlns'");
            }
            // XTDE0865: namespace must be a valid URI
            if (namespace != null && ElementNode.hasInvalidURIChars(namespace)) {
                throw new SAXException("XTDE0865: '" + namespace +
                    "' is not a valid namespace URI for xsl:attribute");
            }
            
            // Get attribute value
            String value = "";
            if (selectExpr != null) {
                // XSLT 2.0+: select attribute takes precedence over content
                XPathValue result = selectExpr.evaluate(evalContext);
                String sep;
                if (separatorAvt != null) {
                    try {
                        sep = separatorAvt.evaluate(evalContext);
                    } catch (XPathException e) {
                        throw new SAXException("Error evaluating separator: " + e.getMessage(), e);
                    }
                } else {
                    sep = " ";
                }
                StringBuilder sb = new StringBuilder();
                Iterator<XPathValue> iter = result.sequenceIterator();
                boolean first = true;
                while (iter.hasNext()) {
                    XPathValue item = iter.next();
                    if (isFunctionItem(item)) {
                        throw new SAXException("FOTY0013: An attribute value " +
                            "cannot contain a function item");
                    }
                    if (!first) {
                        sb.append(sep);
                    }
                    sb.append(item.asString());
                    first = false;
                }
                value = sb.toString();
            } else if (content != null) {
                SAXEventBuffer buffer = 
                    new SAXEventBuffer();
                boolean savedAttrMode = output.isInAttributeContent();
                boolean savedPending = output.isAtomicValuePending();
                BufferOutputHandler bufferHandler = new BufferOutputHandler(buffer);
                bufferHandler.setAtomicValuePending(false);
                if (separatorAvt != null) {
                    String sep;
                    try {
                        sep = separatorAvt.evaluate(evalContext);
                    } catch (XPathException e) {
                        throw new SAXException("Error evaluating separator: " + e.getMessage(), e);
                    }
                    bufferHandler.setCustomItemSeparator(sep);
                } else {
                    bufferHandler.setInAttributeContent(true);
                }
                content.execute(context, bufferHandler);
                output.setInAttributeContent(savedAttrMode);
                output.setAtomicValuePending(savedPending);
                value = buffer.getTextContent();
            }
            
            // Parse qName
            String localName = name;
            String prefix = null;
            int colon = name.indexOf(':');
            if (colon > 0) {
                prefix = name.substring(0, colon);
                localName = name.substring(colon + 1);
            }
            
            // Determine namespace:
            // 1. If namespace attribute is present, use it
            // 2. Otherwise, if prefixed, look up prefix
            // Note: Unprefixed attributes are NOT in the default namespace
            if (namespace == null && prefix != null) {
                // The xml prefix is always bound per XML spec
                if ("xml".equals(prefix)) {
                    namespace = "http://www.w3.org/XML/1998/namespace";
                } else {
                    namespace = namespaceBindings.get(prefix);
                    if (namespace == null) {
                        namespace = context.resolveNamespacePrefix(prefix);
                    }
                    // XTDE0860: prefix must be declared when no namespace attribute
                    if (namespace == null) {
                        throw new SAXException("XTDE0860: Namespace prefix '" + prefix +
                            "' is not declared in xsl:attribute name '" + name + "'");
                    }
                }
            }
            if (namespace == null) {
                namespace = "";
            }
            
            // If namespace is explicitly empty, strip the prefix
            if (namespace.isEmpty() && prefix != null) {
                prefix = null;
            }
            
            // Reject the xmlns namespace
            if ("http://www.w3.org/2000/xmlns/".equals(namespace)) {
                throw new SAXException("XTDE0865: The xmlns namespace URI is not permitted for xsl:attribute");
            }
            
            // Build qName - if we have a namespace but no prefix, generate one
            // Special handling for xmlns prefix: per XSLT spec 11.7.1, the xmlns
            // prefix may be used if namespace attribute is present, but is not required.
            // Since xmlns is reserved in XML, we generate an alternative prefix.
            String qName = (prefix != null) ? name : localName;
            if (!namespace.isEmpty()) {
                boolean needNewPrefix = (prefix == null || prefix.isEmpty() || "xmlns".equals(prefix));
                // When namespace attribute is explicitly set, check if the prefix
                // from the name attribute is already bound to a different namespace.
                // Per XSLT 2.0 spec 11.9.1, the prefix must be changed if it
                // does not correspond to the target namespace.
                if (!needNewPrefix && nsAvt != null && prefix != null) {
                    String boundUri = namespaceBindings.get(prefix);
                    if (boundUri == null) {
                        boundUri = context.resolveNamespacePrefix(prefix);
                    }
                    if (boundUri != null && !boundUri.equals(namespace)) {
                        needNewPrefix = true;
                    }
                }
                if (needNewPrefix) {
                    prefix = StylesheetCompiler.findOrGeneratePrefix(namespace, namespaceBindings);
                    qName = OutputHandlerUtils.buildQName(prefix, localName);
                }
                output.elementPrefixNamespace(prefix, namespace);
            }
            
            // Convert value to canonical form if type annotation is specified
            if (typeLocalName != null && XSD_NAMESPACE.equals(typeNamespaceURI)) {
                value = StylesheetCompiler.toCanonicalLexical(typeLocalName, value);
            }
            
            output.attribute(namespace, localName, qName, value);
            
            // Determine effective validation mode
            ValidationMode effectiveValidation = validation;
            if (effectiveValidation == null) {
                effectiveValidation = context.getStylesheet().getDefaultValidation();
            }
            
            // Set type annotation based on validation mode
            if (typeLocalName != null) {
                // Explicit type attribute - validate value against the type
                // First check for built-in XSD types, then imported types
                XSDSimpleType xsdType = null;
                boolean isBuiltInType = false;
                if (XSD_NAMESPACE.equals(typeNamespaceURI)) {
                    xsdType = XSDSimpleType.getBuiltInType(typeLocalName);
                    isBuiltInType = (xsdType != null);
                }
                if (xsdType == null) {
                    xsdType = context.getStylesheet()
                        .getImportedSimpleType(typeNamespaceURI, typeLocalName);
                }
                if (xsdType != null) {
                    String validationError = xsdType.validate(value);
                    if (validationError != null) {
                        throw new XPathException("XTTE0590: Invalid value for type " + 
                            typeLocalName + ": " + validationError);
                    }
                }
                // Per XSLT 2.0 spec 2.12.1: For built-in types without full schema
                // validation, the type annotation is xs:untypedAtomic.
                // For user-defined types from imported schemas, we preserve the 
                // declared type annotation (per spec 11.1.2.1).
                if (isBuiltInType && effectiveValidation != ValidationMode.PRESERVE) {
                    output.setAttributeType(XSD_NAMESPACE, "untypedAtomic");
                } else {
                    // User-defined type from imported schema, or preserve mode
                    output.setAttributeType(typeNamespaceURI, typeLocalName);
                }
            } else if (effectiveValidation == ValidationMode.STRICT || 
                       effectiveValidation == ValidationMode.LAX) {
                // Use runtime schema validation to derive type
                RuntimeSchemaValidator validator = context.getRuntimeValidator();
                if (validator != null) {
                    RuntimeSchemaValidator.ValidationResult valResult =
                        validator.validateStandaloneAttribute(namespace, localName, 
                                                               value, effectiveValidation);
                    if (valResult.hasTypeAnnotation()) {
                        output.setAttributeType(valResult.getTypeNamespaceURI(),
                                                valResult.getTypeLocalName());
                    }
                }
            } else if (effectiveValidation == ValidationMode.STRIP) {
                // Strip mode - don't set any type annotation
            }
            // PRESERVE mode when constructing new attributes is a no-op
        } catch (XPathException e) {
            throw new SAXException("Error in xsl:attribute", e);
        }
    }

    private static boolean isFunctionItem(XPathValue value) {
        return value instanceof XPathFunctionItem
            || value instanceof InlineFunctionItem
            || value instanceof PartialFunctionItem
            || value instanceof DynamicPartialItem
            || value instanceof XPathMap
            || value instanceof XPathArray;
    }
}

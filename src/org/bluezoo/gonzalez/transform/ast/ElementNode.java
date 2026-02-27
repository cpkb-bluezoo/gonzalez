/*
 * ElementNode.java
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

import java.util.Map;
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
import org.bluezoo.gonzalez.transform.xpath.expr.XPathException;

/**
 * ElementNode XSLT instruction.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public class ElementNode extends XSLTInstruction {
    private static final String XSD_NAMESPACE = "http://www.w3.org/2001/XMLSchema";
    private final AttributeValueTemplate nameAvt;
    private final AttributeValueTemplate nsAvt;
    private final String useAttrSets;
    private final SequenceNode content;
    private final String defaultNamespace;  // Default namespace from xsl:element context
    private final Map<String, String> namespaceBindings;  // All namespace bindings
    private final String typeNamespaceURI;
    private final String typeLocalName;
    private final ValidationMode validation;  // null means use stylesheet default
    private final XSLTNode onEmptyNode;      // XSLT 3.0 xsl:on-empty child
    private final XSLTNode onNonEmptyNode;   // XSLT 3.0 xsl:on-non-empty child
    
    public ElementNode(AttributeValueTemplate nameAvt, AttributeValueTemplate nsAvt, 
               String useAttrSets, SequenceNode content,
               String defaultNamespace, Map<String, String> namespaceBindings) {
        this(nameAvt, nsAvt, useAttrSets, content, defaultNamespace, namespaceBindings, 
             null, null, null, null, null);
    }
    
    public ElementNode(AttributeValueTemplate nameAvt, AttributeValueTemplate nsAvt, 
               String useAttrSets, SequenceNode content,
               String defaultNamespace, Map<String, String> namespaceBindings,
               String typeNamespaceURI, String typeLocalName) {
        this(nameAvt, nsAvt, useAttrSets, content, defaultNamespace, namespaceBindings, 
             typeNamespaceURI, typeLocalName, null, null, null);
    }
    
    public ElementNode(AttributeValueTemplate nameAvt, AttributeValueTemplate nsAvt, 
               String useAttrSets, SequenceNode content,
               String defaultNamespace, Map<String, String> namespaceBindings,
               String typeNamespaceURI, String typeLocalName, ValidationMode validation) {
        this(nameAvt, nsAvt, useAttrSets, content, defaultNamespace, namespaceBindings,
             typeNamespaceURI, typeLocalName, validation, null, null);
    }
    
    public ElementNode(AttributeValueTemplate nameAvt, AttributeValueTemplate nsAvt, 
               String useAttrSets, SequenceNode content,
               String defaultNamespace, Map<String, String> namespaceBindings,
               String typeNamespaceURI, String typeLocalName, ValidationMode validation,
               XSLTNode onEmptyNode, XSLTNode onNonEmptyNode) {
        this.nameAvt = nameAvt;
        this.nsAvt = nsAvt;
        this.useAttrSets = useAttrSets;
        this.content = content;
        this.defaultNamespace = defaultNamespace;
        this.namespaceBindings = namespaceBindings;
        this.typeNamespaceURI = typeNamespaceURI;
        this.typeLocalName = typeLocalName;
        this.validation = validation;
        this.onEmptyNode = onEmptyNode;
        this.onNonEmptyNode = onNonEmptyNode;
    }
    
    @Override public String getInstructionName() { return "element"; }
    @Override public void execute(TransformContext context, 
                                  OutputHandler output) throws SAXException {
        try {
            String name = nameAvt.evaluate(context).trim();
            String namespace = nsAvt != null ? nsAvt.evaluate(context).trim() : null;
            
            // XTDE0820: name must be a valid lexical QName
            if (!isValidQName(name)) {
                throw new SAXException("XTDE0820: '" + name +
                    "' is not a valid QName for xsl:element");
            }
            
            // XTDE0835: namespace must be a valid URI (reject obvious invalids)
            if (namespace != null && hasInvalidURIChars(namespace)) {
                throw new SAXException("XTDE0835: '" + namespace +
                    "' is not a valid namespace URI for xsl:element");
            }
            
            // Parse qName
            String localName = name;
            String prefix = null;
            int colon = name.indexOf(':');
            if (colon > 0) {
                prefix = name.substring(0, colon);
                localName = name.substring(colon + 1);
            }
            
            // Determine namespace per XSLT 1.0 section 7.1.2:
            // 1. If namespace attribute is present, use it
            // 2. Otherwise, expand using namespace declarations in scope on xsl:element
            if (namespace == null) {
                if (prefix != null && !prefix.isEmpty()) {
                    namespace = namespaceBindings.get(prefix);
                    if (namespace == null) {
                        namespace = context.resolveNamespacePrefix(prefix);
                    }
                    // XTDE0830: prefix must be declared when no namespace attribute
                    if (namespace == null) {
                        throw new SAXException("XTDE0830: Namespace prefix '" + prefix +
                            "' is not declared in xsl:element name '" + name + "'");
                    }
                } else {
                    namespace = defaultNamespace != null ? defaultNamespace : "";
                }
            }
            if (namespace == null) {
                namespace = "";
            }
            
            String qName = prefix != null ? prefix + ":" + localName : localName;
            output.startElement(namespace, localName, qName);
            
            // Determine effective validation mode
            ValidationMode effectiveValidation = validation;
            if (effectiveValidation == null) {
                // Use stylesheet default
                effectiveValidation = context.getStylesheet().getDefaultValidation();
            }
            
            // Set type annotation based on validation mode
            if (typeLocalName != null) {
                // With validation="preserve" and explicit type= attribute, set the
                // declared type annotation. Otherwise, use xs:untyped (the default
                // for constructed elements without schema validation).
                if (effectiveValidation == ValidationMode.PRESERVE) {
                    output.setElementType(typeNamespaceURI, typeLocalName);
                } else {
                    output.setElementType(XSD_NAMESPACE, "untyped");
                }
                // TODO: Implement element value validation against the specified type
            }
            
            // Get validator for potential use in validation
            RuntimeSchemaValidator validator = context.getRuntimeValidator();
            
            if (effectiveValidation == ValidationMode.STRICT || 
                       effectiveValidation == ValidationMode.LAX) {
                // Use runtime schema validation to derive type
                if (validator != null) {
                    RuntimeSchemaValidator.ValidationResult valResult =
                        validator.startElement(namespace, localName, effectiveValidation);
                    // Note: Full content model validation happens after content execution
                    if (valResult.hasTypeAnnotation()) {
                        output.setElementType(valResult.getTypeNamespaceURI(),
                                              valResult.getTypeLocalName());
                    }
                }
            } else if (effectiveValidation == ValidationMode.STRIP) {
                // Strip mode - don't set any type annotation (no-op since we haven't set one)
            }
            // PRESERVE mode when constructing new elements is a no-op
            
            // Declare the namespace for this element
            // For unprefixed elements, declare default namespace (even if empty)
            // For prefixed elements, declare the prefix binding
            if (prefix == null || prefix.isEmpty()) {
                output.namespace("", namespace);
            } else {
                output.namespace(prefix, namespace);
            }
            
            // Apply attribute sets if specified
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
            
            // Execute content (reset atomic separator since we're starting fresh content)
            output.setAtomicValuePending(false);
            output.setInAttributeContent(false);
            
            // Execute content with on-empty/on-non-empty support (XSLT 3.0)
            // SequenceNode handles the two-phase conditional execution if needed
            if (content != null) {
                content.executeWithOnEmptySupport(context, output, content.hasOnEmptyOrOnNonEmpty());
            }
            
            // Complete validation after content execution
            if ((effectiveValidation == ValidationMode.STRICT ||
                 effectiveValidation == ValidationMode.LAX) && 
                validator != null) {
                try {
                    RuntimeSchemaValidator.ValidationResult endResult = validator.endElement();
                    if (!endResult.isValid() && effectiveValidation == ValidationMode.STRICT) {
                        throw new SAXException(endResult.getErrorCode() + ": " + 
                                             endResult.getErrorMessage());
                    }
                } catch (XPathException e) {
                    throw new SAXException("Content model validation error", e);
                }
            }
            
            output.endElement(namespace, localName, qName);
        } catch (XPathException e) {
            throw new SAXException("Error in xsl:element", e);
        }
    }

    /**
     * Validates that a string is a lexical QName (NCName or NCName:NCName).
     * Used for runtime validation of computed element/attribute names.
     */
    static boolean isValidQName(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        int colon = name.indexOf(':');
        if (colon < 0) {
            return isValidNCName(name);
        }
        if (colon == 0 || colon == name.length() - 1) {
            return false;
        }
        String prefix = name.substring(0, colon);
        String local = name.substring(colon + 1);
        if (local.indexOf(':') >= 0) {
            return false;
        }
        return isValidNCName(prefix) && isValidNCName(local);
    }

    /**
     * Validates that a string is a valid NCName (XML non-colonized name).
     */
    static boolean isValidNCName(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        char first = name.charAt(0);
        if (!isNameStartChar(first)) {
            return false;
        }
        for (int i = 1; i < name.length(); i++) {
            if (!isNameChar(name.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isNameStartChar(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
            || c == '_'
            || (c >= 0xC0 && c <= 0xD6)
            || (c >= 0xD8 && c <= 0xF6)
            || (c >= 0xF8 && c <= 0x2FF)
            || (c >= 0x370 && c <= 0x37D)
            || (c >= 0x37F && c <= 0x1FFF)
            || (c >= 0x200C && c <= 0x200D)
            || (c >= 0x2070 && c <= 0x218F)
            || (c >= 0x2C00 && c <= 0x2FEF)
            || (c >= 0x3001 && c <= 0xD7FF)
            || (c >= 0xF900 && c <= 0xFDCF)
            || (c >= 0xFDF0 && c <= 0xFFFD);
    }

    private static boolean isNameChar(char c) {
        return isNameStartChar(c)
            || c == '-' || c == '.'
            || (c >= '0' && c <= '9')
            || c == 0xB7
            || (c >= 0x0300 && c <= 0x036F)
            || (c >= 0x203F && c <= 0x2040);
    }

    /**
     * Checks if a URI contains obviously invalid characters or patterns.
     * Rejects URIs with multiple '#' (fragment delimiters), leading '#',
     * and characters forbidden in URI references.
     */
    static boolean hasInvalidURIChars(String uri) {
        if (uri.isEmpty()) {
            return false;
        }
        // Leading # is never valid in a URI reference
        if (uri.charAt(0) == '#') {
            return true;
        }
        boolean seenFragment = false;
        for (int i = 0; i < uri.length(); i++) {
            char c = uri.charAt(i);
            if (c == '#') {
                if (seenFragment) {
                    return true;
                }
                seenFragment = true;
            }
            if (c == ' ' || c == '{' || c == '}' || c == '<' || c == '>'
                    || c == '"' || c == '|' || c == '\\' || c == '^'
                    || c == '`') {
                return true;
            }
        }
        return false;
    }
}

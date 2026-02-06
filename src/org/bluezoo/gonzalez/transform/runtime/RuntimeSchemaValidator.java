/*
 * RuntimeSchemaValidator.java
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

package org.bluezoo.gonzalez.transform.runtime;

import org.bluezoo.gonzalez.schema.xsd.XSDAttribute;
import org.bluezoo.gonzalez.schema.xsd.XSDComplexType;
import org.bluezoo.gonzalez.schema.xsd.XSDContentModelValidator;
import org.bluezoo.gonzalez.schema.xsd.XSDElement;
import org.bluezoo.gonzalez.schema.xsd.XSDSchema;
import org.bluezoo.gonzalez.schema.xsd.XSDSimpleType;
import org.bluezoo.gonzalez.schema.xsd.XSDType;
import org.bluezoo.gonzalez.transform.compiler.CompiledStylesheet;
import org.bluezoo.gonzalez.transform.ValidationMode;
import org.bluezoo.gonzalez.transform.xpath.expr.XPathException;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * Runtime schema validation service for XSLT transformations.
 *
 * <p>This service coordinates schema validation during transformation,
 * supporting validation="strict" and validation="lax" modes on
 * xsl:element, xsl:copy, xsl:copy-of, and literal result elements.
 *
 * <p>Features:
 * <ul>
 *   <li>Schema lookup by namespace from imported schemas</li>
 *   <li>Element and attribute declaration resolution</li>
 *   <li>Content model validation via {@link XSDContentModelValidator}</li>
 *   <li>Simple type validation for text content</li>
 *   <li>Type annotation derivation from schema</li>
 *   <li>Proper XSLT error code reporting (XTTE, XPTY)</li>
 * </ul>
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public class RuntimeSchemaValidator {
    
    /**
     * Result of element validation, including type annotation if successful.
     */
    public static class ValidationResult {
        private final boolean valid;
        private final String typeNamespaceURI;
        private final String typeLocalName;
        private final String errorCode;
        private final String errorMessage;
        
        private ValidationResult(boolean valid, String typeNs, String typeLocal,
                                  String errorCode, String errorMessage) {
            this.valid = valid;
            this.typeNamespaceURI = typeNs;
            this.typeLocalName = typeLocal;
            this.errorCode = errorCode;
            this.errorMessage = errorMessage;
        }
        
        /**
         * Creates a valid result with type annotation.
         *
         * @param typeNs the type namespace URI
         * @param typeLocal the type local name
         * @return a valid validation result
         */
        public static ValidationResult valid(String typeNs, String typeLocal) {
            return new ValidationResult(true, typeNs, typeLocal, null, null);
        }
        
        /**
         * Creates a valid result without type annotation.
         *
         * @return a valid validation result with no type
         */
        public static ValidationResult validUntyped() {
            return new ValidationResult(true, null, null, null, null);
        }
        
        /**
         * Creates an error result.
         *
         * @param errorCode the XSLT error code (e.g., "XTTE0505")
         * @param message the error message
         * @return an error validation result
         */
        public static ValidationResult error(String errorCode, String message) {
            return new ValidationResult(false, null, null, errorCode, message);
        }
        
        /**
         * Returns true if validation succeeded.
         *
         * @return true if valid
         */
        public boolean isValid() {
            return valid;
        }
        
        /**
         * Returns the type annotation namespace URI.
         *
         * @return the namespace URI, or null if no type annotation
         */
        public String getTypeNamespaceURI() {
            return typeNamespaceURI;
        }
        
        /**
         * Returns the type annotation local name.
         *
         * @return the local name, or null if no type annotation
         */
        public String getTypeLocalName() {
            return typeLocalName;
        }
        
        /**
         * Returns true if this result includes a type annotation.
         *
         * @return true if a type annotation is present
         */
        public boolean hasTypeAnnotation() {
            return typeLocalName != null;
        }
        
        /**
         * Returns the error code if validation failed.
         *
         * @return the XSLT error code, or null if validation succeeded
         */
        public String getErrorCode() {
            return errorCode;
        }
        
        /**
         * Returns the error message if validation failed.
         *
         * @return the error message, or null if validation succeeded
         */
        public String getErrorMessage() {
            return errorMessage;
        }
    }
    
    /**
     * State for an element being validated.
     */
    private static class ElementState {
        final String namespaceURI;
        final String localName;
        final ValidationMode mode;
        final XSDElement declaration;
        final XSDContentModelValidator contentValidator;
        StringBuilder textContent;
        boolean hasChildElements;
        
        ElementState(String namespaceURI, String localName, ValidationMode mode,
                     XSDElement declaration) {
            this.namespaceURI = namespaceURI;
            this.localName = localName;
            this.mode = mode;
            this.declaration = declaration;
            this.contentValidator = new XSDContentModelValidator();
            this.textContent = new StringBuilder();
            this.hasChildElements = false;
            
            // Start content model validation if we have a complex type
            if (declaration != null && declaration.getType() instanceof XSDComplexType) {
                XSDComplexType ct = (XSDComplexType) declaration.getType();
                contentValidator.startValidation(ct);
            }
        }
    }
    
    private final CompiledStylesheet stylesheet;
    private final Map<String, XSDSchema> schemaCache;
    private final Deque<ElementState> elementStack;
    
    /**
     * Creates a runtime validator for the given stylesheet.
     *
     * @param stylesheet the compiled stylesheet with imported schemas
     */
    public RuntimeSchemaValidator(CompiledStylesheet stylesheet) {
        this.stylesheet = stylesheet;
        this.schemaCache = new HashMap<>();
        this.elementStack = new ArrayDeque<>();
        
        // Pre-populate cache with imported schemas
        if (stylesheet != null) {
            Map<String, XSDSchema> imported = stylesheet.getImportedSchemas();
            if (imported != null) {
                for (Map.Entry<String, XSDSchema> entry : imported.entrySet()) {
                    schemaCache.put(entry.getKey(), entry.getValue());
                }
            }
        }
    }
    
    /**
     * Starts validation for an element being constructed.
     *
     * <p>Call this when starting to construct an element. Then call
     * {@link #validateAttribute} for each attribute, {@link #addChildElement}
     * for each child element, and {@link #addTextContent} for text content.
     * Finally call {@link #endElement} to complete validation.
     *
     * @param namespaceURI the element namespace URI (may be null or empty)
     * @param localName the element local name
     * @param mode the validation mode (STRICT, LAX, PRESERVE, STRIP)
     * @return validation result; if invalid, element construction should fail
     * @throws XPathException if strict validation fails
     */
    public ValidationResult startElement(String namespaceURI, String localName,
                                          ValidationMode mode) throws XPathException {
        // Normalize namespace
        if (namespaceURI != null && namespaceURI.isEmpty()) {
            namespaceURI = null;
        }
        
        // For PRESERVE and STRIP, no validation needed
        if (mode == ValidationMode.PRESERVE || mode == ValidationMode.STRIP) {
            elementStack.push(new ElementState(namespaceURI, localName, mode, null));
            return ValidationResult.validUntyped();
        }
        
        // Find element declaration - first try global, then local from parent context
        XSDElement decl = null;
        
        // Try global element declaration first
        XSDSchema schema = getSchema(namespaceURI);
        if (schema != null) {
            decl = schema.resolveElement(namespaceURI, localName);
        }
        
        // If not found globally, try local element from parent's content model
        if (decl == null && !elementStack.isEmpty()) {
            ElementState parent = elementStack.peek();
            if (parent.declaration != null && parent.declaration.getType() instanceof XSDComplexType) {
                XSDComplexType parentType = (XSDComplexType) parent.declaration.getType();
                decl = parentType.getChildElement(namespaceURI, localName);
            }
        }
        
        // Handle based on mode
        if (mode == ValidationMode.STRICT) {
            if (decl == null) {
                String msg = "No declaration found for element {" + 
                    (namespaceURI != null ? namespaceURI : "") + "}" + localName;
                throw new XPathException("XTTE0505: " + msg);
            }
        } else if (mode == ValidationMode.LAX) {
            // LAX: No error if no declaration found
            if (decl == null) {
                elementStack.push(new ElementState(namespaceURI, localName, mode, null));
                return ValidationResult.validUntyped();
            }
        }
        
        // Push state for content validation
        elementStack.push(new ElementState(namespaceURI, localName, mode, decl));
        
        // Return type annotation from element declaration immediately
        // (Full content validation will happen at endElement if needed)
        if (decl != null && decl.getType() != null) {
            XSDType type = decl.getType();
            return ValidationResult.valid(type.getNamespaceURI(), type.getName());
        }
        
        return ValidationResult.validUntyped();
    }
    
    /**
     * Validates an attribute on the current element.
     *
     * @param namespaceURI the attribute namespace URI (may be null)
     * @param localName the attribute local name
     * @param value the attribute value
     * @return validation result
     * @throws XPathException if strict validation fails
     */
    public ValidationResult validateAttribute(String namespaceURI, String localName,
                                               String value) throws XPathException {
        if (elementStack.isEmpty()) {
            return ValidationResult.validUntyped();
        }
        
        ElementState state = elementStack.peek();
        
        // No declaration means no validation
        if (state.declaration == null) {
            return ValidationResult.validUntyped();
        }
        
        XSDType elementType = state.declaration.getType();
        if (!(elementType instanceof XSDComplexType)) {
            // Simple type elements can't have attributes
            if (state.mode == ValidationMode.STRICT) {
                throw new XPathException("XTTE0590: Attributes not allowed on simple type element");
            }
            return ValidationResult.validUntyped();
        }
        
        XSDComplexType ct = (XSDComplexType) elementType;
        XSDAttribute attrDecl = ct.getAttribute(localName);
        
        if (attrDecl == null) {
            // Check for prohibited or missing declaration
            if (state.mode == ValidationMode.STRICT) {
                throw new XPathException("XTTE0590: No declaration for attribute " + localName +
                    " on element " + state.localName);
            }
            return ValidationResult.validUntyped();
        }
        
        // Validate attribute value against its type
        XSDType attrType = attrDecl.getType();
        if (attrType instanceof XSDSimpleType) {
            XSDSimpleType simpleType = (XSDSimpleType) attrType;
            String error = simpleType.validate(value);
            if (error != null) {
                if (state.mode == ValidationMode.STRICT) {
                    throw new XPathException("XTTE0590: Invalid value for attribute " +
                        localName + ": " + error);
                }
                return ValidationResult.error("XTTE0590", error);
            }
            
            // Return type annotation
            String typeNs = attrType.getNamespaceURI();
            String typeLocal = attrType.getName();
            return ValidationResult.valid(typeNs, typeLocal);
        }
        
        return ValidationResult.validUntyped();
    }
    
    /**
     * Records a child element being added to the current element.
     *
     * @param namespaceURI the child element namespace
     * @param localName the child element local name
     * @return validation result
     * @throws XPathException if content model validation fails
     */
    public ValidationResult addChildElement(String namespaceURI, String localName)
            throws XPathException {
        if (elementStack.isEmpty()) {
            return ValidationResult.validUntyped();
        }
        
        ElementState state = elementStack.peek();
        state.hasChildElements = true;
        
        // No declaration means no validation
        if (state.declaration == null) {
            return ValidationResult.validUntyped();
        }
        
        XSDType elementType = state.declaration.getType();
        if (!(elementType instanceof XSDComplexType)) {
            // Simple type elements can't have child elements
            if (state.mode == ValidationMode.STRICT) {
                throw new XPathException("XTTE0520: Child elements not allowed on simple type element " +
                    state.localName);
            }
            return ValidationResult.validUntyped();
        }
        
        // Validate child against content model
        XSDContentModelValidator.ValidationResult cmResult = 
            state.contentValidator.validateElement(namespaceURI, localName);
        
        if (!cmResult.isValid()) {
            if (state.mode == ValidationMode.STRICT) {
                throw new XPathException(cmResult.getErrorCode() + ": " + cmResult.getErrorMessage());
            }
            return ValidationResult.error(cmResult.getErrorCode(), cmResult.getErrorMessage());
        }
        
        // Get child's type annotation
        XSDElement childDecl = cmResult.getMatchedElement();
        if (childDecl != null && childDecl.getType() != null) {
            XSDType childType = childDecl.getType();
            return ValidationResult.valid(childType.getNamespaceURI(), childType.getName());
        }
        
        return ValidationResult.validUntyped();
    }
    
    /**
     * Records text content being added to the current element.
     *
     * @param text the text content
     */
    public void addTextContent(String text) {
        if (elementStack.isEmpty()) {
            return;
        }
        
        ElementState state = elementStack.peek();
        state.textContent.append(text);
    }
    
    /**
     * Completes validation for the current element.
     *
     * <p>This validates:
     * <ul>
     *   <li>All required child elements are present (content model)</li>
     *   <li>Text content matches simple type (if applicable)</li>
     * </ul>
     *
     * @return validation result with type annotation if successful
     * @throws XPathException if validation fails
     */
    public ValidationResult endElement() throws XPathException {
        if (elementStack.isEmpty()) {
            return ValidationResult.validUntyped();
        }
        
        ElementState state = elementStack.pop();
        
        // No declaration means no validation
        if (state.declaration == null) {
            return ValidationResult.validUntyped();
        }
        
        XSDType elementType = state.declaration.getType();
        
        // Complete content model validation
        if (elementType instanceof XSDComplexType) {
            XSDComplexType ct = (XSDComplexType) elementType;
            
            // Check content model completeness
            XSDContentModelValidator.ValidationResult cmResult = 
                state.contentValidator.endValidation();
            
            if (!cmResult.isValid()) {
                if (state.mode == ValidationMode.STRICT) {
                    throw new XPathException(cmResult.getErrorCode() + ": " + cmResult.getErrorMessage());
                }
                return ValidationResult.error(cmResult.getErrorCode(), cmResult.getErrorMessage());
            }
            
            // Validate simple content if applicable
            if (ct.getContentType() == XSDComplexType.ContentType.SIMPLE) {
                XSDSimpleType simpleContent = ct.getSimpleContentType();
                if (simpleContent != null) {
                    String text = state.textContent.toString();
                    String error = simpleContent.validate(text);
                    if (error != null) {
                        if (state.mode == ValidationMode.STRICT) {
                            throw new XPathException("XTTE0540: Invalid content for element " +
                                state.localName + ": " + error);
                        }
                        return ValidationResult.error("XTTE0540", error);
                    }
                }
            }
            
            // Return type annotation
            return ValidationResult.valid(ct.getNamespaceURI(), ct.getName());
        }
        
        // Simple type element - validate text content
        if (elementType instanceof XSDSimpleType) {
            XSDSimpleType simpleType = (XSDSimpleType) elementType;
            
            // Child elements not allowed
            if (state.hasChildElements) {
                if (state.mode == ValidationMode.STRICT) {
                    throw new XPathException("XTTE0520: Child elements not allowed on simple type element " +
                        state.localName);
                }
                return ValidationResult.error("XTTE0520", "Child elements not allowed");
            }
            
            // Validate text content
            String text = state.textContent.toString();
            String error = simpleType.validate(text);
            if (error != null) {
                if (state.mode == ValidationMode.STRICT) {
                    throw new XPathException("XTTE0540: Invalid content for element " +
                        state.localName + ": " + error);
                }
                return ValidationResult.error("XTTE0540", error);
            }
            
            return ValidationResult.valid(simpleType.getNamespaceURI(), simpleType.getName());
        }
        
        return ValidationResult.validUntyped();
    }
    
    /**
     * Validates a standalone attribute (for xsl:attribute with validation).
     *
     * @param namespaceURI the attribute namespace
     * @param localName the attribute local name
     * @param value the attribute value
     * @param mode the validation mode
     * @return validation result
     * @throws XPathException if strict validation fails
     */
    public ValidationResult validateStandaloneAttribute(String namespaceURI, String localName,
                                                         String value, ValidationMode mode)
            throws XPathException {
        // Normalize namespace
        if (namespaceURI != null && namespaceURI.isEmpty()) {
            namespaceURI = null;
        }
        
        // For PRESERVE and STRIP, no validation needed
        if (mode == ValidationMode.PRESERVE || mode == ValidationMode.STRIP) {
            return ValidationResult.validUntyped();
        }
        
        // Find schema for this namespace
        XSDSchema schema = getSchema(namespaceURI);
        if (schema == null) {
            if (mode == ValidationMode.STRICT) {
                throw new XPathException("XTTE0505: No schema for namespace " + namespaceURI);
            }
            return ValidationResult.validUntyped();
        }
        
        // Find global attribute declaration
        XSDAttribute attrDecl = schema.getAttribute(localName);
        if (attrDecl == null) {
            if (mode == ValidationMode.STRICT) {
                throw new XPathException("XTTE0505: No declaration for attribute " + localName);
            }
            return ValidationResult.validUntyped();
        }
        
        // Validate value
        XSDType attrType = attrDecl.getType();
        if (attrType instanceof XSDSimpleType) {
            XSDSimpleType simpleType = (XSDSimpleType) attrType;
            String error = simpleType.validate(value);
            if (error != null) {
                if (mode == ValidationMode.STRICT) {
                    throw new XPathException("XTTE0590: Invalid attribute value: " + error);
                }
                return ValidationResult.error("XTTE0590", error);
            }
            return ValidationResult.valid(simpleType.getNamespaceURI(), simpleType.getName());
        }
        
        return ValidationResult.validUntyped();
    }
    
    /**
     * Gets a schema for the given namespace, using cache.
     *
     * @param namespaceURI the namespace URI (null for no namespace)
     * @return the schema, or null if not found
     */
    private XSDSchema getSchema(String namespaceURI) {
        String key = namespaceURI != null ? namespaceURI : "";
        return schemaCache.get(key);
    }
    
    /**
     * Checks if a schema is available for the given namespace.
     *
     * @param namespaceURI the namespace to check
     * @return true if a schema is imported for this namespace
     */
    public boolean hasSchema(String namespaceURI) {
        return getSchema(namespaceURI) != null;
    }
    
    /**
     * Resets the validator state for reuse.
     */
    public void reset() {
        elementStack.clear();
    }
}

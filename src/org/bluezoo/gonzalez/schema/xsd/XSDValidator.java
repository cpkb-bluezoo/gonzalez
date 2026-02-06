/*
 * XSDValidator.java
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

package org.bluezoo.gonzalez.schema.xsd;

import org.bluezoo.gonzalez.schema.PSVIProvider;
import org.bluezoo.gonzalez.schema.TypedValue;
import org.bluezoo.gonzalez.schema.ValidationSource;
import org.bluezoo.gonzalez.schema.Validity;
import org.xml.sax.*;
import org.xml.sax.helpers.XMLFilterImpl;

import java.util.*;

/**
 * XSD validating SAX filter that implements {@link PSVIProvider}.
 *
 * <p>This filter validates XML documents against an XSD schema during SAX
 * parsing, providing type information through the PSVIProvider interface.
 *
 * <p>Usage:
 * <pre>
 * XSDSchema schema = new XSDSchemaParser().parse(schemaInput);
 * XSDValidator validator = new XSDValidator(schema);
 * validator.setParent(xmlReader);
 * validator.setContentHandler(myHandler);
 * validator.parse(documentInput);
 * </pre>
 *
 * <p>During SAX callbacks, cast the validator to PSVIProvider to access
 * type information:
 * <pre>
 * public void startElement(...) {
 *     PSVIProvider psvi = (PSVIProvider) validator;
 *     TypedValue attrValue = psvi.getAttributeTypedValue(0);
 * }
 * </pre>
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public class XSDValidator extends XMLFilterImpl implements PSVIProvider {
    
    private final XSDSchema schema;
    private ErrorHandler errorHandler;
    
    // Validation state
    private final Deque<ElementContext> elementStack = new ArrayDeque<>();
    private Validity currentValidity = Validity.VALID;
    private final List<String> validationErrors = new ArrayList<>();
    
    // Current element PSVI
    private XSDElement currentElementDecl;
    private XSDType currentElementType;
    private Attributes currentAttributes;
    private XSDAttribute[] currentAttributeDecls;
    private TypedValue[] currentAttributeValues;
    private Boolean currentNil;
    
    // Text content accumulator
    private StringBuilder textContent;
    
    // Attribute tracking (reused for performance)
    private final Set<String> presentAttrs = new HashSet<>();
    
    // Element resolution cache for performance
    private final Map<String, XSDElement> elementCache = new HashMap<>();
    
    // ID tracking for uniqueness
    private final Set<String> declaredIds = new HashSet<>();
    private final List<String> pendingIdrefs = new ArrayList<>();
    
    /**
     * Element validation context.
     */
    private static class ElementContext {
        final XSDElement declaration;
        final XSDType type;
        final StringBuilder content = new StringBuilder();
        final List<String> childElements = new ArrayList<>();
        boolean hasCharData = false;
        
        ElementContext(XSDElement decl, XSDType type) {
            this.declaration = decl;
            this.type = type;
        }
    }
    
    /**
     * Creates a validator for the given schema.
     *
     * <p>The validator must be configured with a parent XMLReader using
     * {@link #setParent(XMLReader)} before parsing.
     *
     * @param schema the XSD schema to validate against
     */
    public XSDValidator(XSDSchema schema) {
        this.schema = schema;
    }
    
    /**
     * Creates a validator with a parent XMLReader.
     *
     * <p>This constructor sets up the validator with both a parent reader
     * and schema, ready for immediate use.
     *
     * @param parent the parent XMLReader that will parse the document
     * @param schema the XSD schema to validate against
     */
    public XSDValidator(XMLReader parent, XSDSchema schema) {
        super(parent);
        this.schema = schema;
    }
    
    /**
     * Returns the schema being used for validation.
     *
     * @return the XSD schema, never null
     */
    public XSDSchema getSchema() {
        return schema;
    }
    
    @Override
    public void setErrorHandler(ErrorHandler handler) {
        this.errorHandler = handler;
        super.setErrorHandler(handler);
    }
    
    // ========================================================================
    // SAX ContentHandler
    // ========================================================================
    
    @Override
    public void startDocument() throws SAXException {
        elementStack.clear();
        currentValidity = Validity.VALID;
        validationErrors.clear();
        declaredIds.clear();
        pendingIdrefs.clear();
        textContent = null;
        
        super.startDocument();
    }
    
    @Override
    public void endDocument() throws SAXException {
        // Validate IDREF references
        for (String idref : pendingIdrefs) {
            if (!declaredIds.contains(idref)) {
                reportError("IDREF '" + idref + "' does not match any ID");
            }
        }
        
        super.endDocument();
    }
    
    @Override
    public void startElement(String uri, String localName, String qName, Attributes atts) 
            throws SAXException {
        
        // Flush text content from previous element
        if (textContent != null && !elementStack.isEmpty()) {
            ElementContext parent = elementStack.peek();
            parent.content.append(textContent);
            if (textContent.toString().trim().length() > 0) {
                parent.hasCharData = true;
            }
        }
        if (textContent == null) {
            textContent = new StringBuilder();
        } else {
            textContent.setLength(0);
        }
        
        // Look up element declaration
        currentElementDecl = resolveElementDeclaration(uri, localName);
        currentElementType = null;
        currentNil = null;
        
        if (currentElementDecl != null) {
            currentElementType = currentElementDecl.getType();
        }
        
        // Check xsi:type override
        String xsiType = atts.getValue(XSDSchema.XSI_NAMESPACE, "type");
        if (xsiType != null) {
            XSDType overrideType = resolveXsiType(xsiType);
            if (overrideType != null) {
                currentElementType = overrideType;
            }
        }
        
        // Check xsi:nil
        String xsiNil = atts.getValue(XSDSchema.XSI_NAMESPACE, "nil");
        if ("true".equals(xsiNil)) {
            if (currentElementDecl != null && currentElementDecl.isNillable()) {
                currentNil = Boolean.TRUE;
            } else {
                reportError("Element " + qName + " is not nillable");
                currentNil = Boolean.FALSE;
            }
        }
        
        // Validate element in current context
        if (!elementStack.isEmpty()) {
            ElementContext parent = elementStack.peek();
            parent.childElements.add(localName);
            
            // Check if element is allowed
            if (parent.type instanceof XSDComplexType) {
                XSDComplexType ct = (XSDComplexType) parent.type;
                if (!ct.allowsElement(uri, localName)) {
                    // Check if it's simple/empty content
                    if (ct.getContentType() == XSDComplexType.ContentType.SIMPLE ||
                        ct.getContentType() == XSDComplexType.ContentType.EMPTY) {
                        reportError("Element " + qName + " not allowed in " + 
                            ct.getContentType() + " content");
                    }
                }
                
                // Get local element declaration from parent type
                if (currentElementDecl == null) {
                    currentElementDecl = ct.getChildElement(uri, localName);
                    if (currentElementDecl != null) {
                        currentElementType = currentElementDecl.getType();
                    }
                }
            }
        } else {
            // Root element - must match a global element
            if (currentElementDecl == null) {
                reportError("Unknown root element: " + qName);
            }
        }
        
        // Process attributes
        currentAttributes = atts;
        currentAttributeDecls = new XSDAttribute[atts.getLength()];
        currentAttributeValues = new TypedValue[atts.getLength()];
        
        validateAttributes(atts);
        
        // Push element context
        elementStack.push(new ElementContext(currentElementDecl, currentElementType));
        
        super.startElement(uri, localName, qName, atts);
    }
    
    @Override
    public void endElement(String uri, String localName, String qName) throws SAXException {
        // Collect final text content
        if (textContent != null && !elementStack.isEmpty()) {
            ElementContext ctx = elementStack.peek();
            ctx.content.append(textContent);
            if (textContent.toString().trim().length() > 0) {
                ctx.hasCharData = true;
            }
        }
        textContent = null;
        
        // Pop and validate element content
        if (!elementStack.isEmpty()) {
            ElementContext ctx = elementStack.pop();
            
            // Validate content if nilled
            if (Boolean.TRUE.equals(currentNil)) {
                if (ctx.hasCharData || !ctx.childElements.isEmpty()) {
                    reportError("Nilled element " + qName + " must be empty");
                }
            }
            
            // Validate text content against type
            if (ctx.declaration != null && ctx.content.length() > 0) {
                String content = ctx.content.toString();
                String error = ctx.declaration.validateContent(content);
                if (error != null) {
                    reportError(error);
                }
            }
        }
        
        super.endElement(uri, localName, qName);
        
        // Clear current element state
        currentElementDecl = null;
        currentElementType = null;
        currentAttributes = null;
        currentAttributeDecls = null;
        currentAttributeValues = null;
        currentNil = null;
    }
    
    @Override
    public void characters(char[] ch, int start, int length) throws SAXException {
        if (textContent != null) {
            textContent.append(ch, start, length);
        }
        super.characters(ch, start, length);
    }
    
    // ========================================================================
    // Validation helpers
    // ========================================================================
    
    private XSDElement resolveElementDeclaration(String namespaceURI, String localName) {
        String key = makeElementKey(namespaceURI, localName);
        XSDElement cached = elementCache.get(key);
        if (cached != null) {
            return cached;
        }
        XSDElement elem = schema.resolveElement(namespaceURI, localName);
        if (elem != null) {
            elementCache.put(key, elem);
        }
        return elem;
    }
    
    private static String makeElementKey(String namespaceURI, String localName) {
        if (namespaceURI == null || namespaceURI.isEmpty()) {
            return localName;
        }
        return "{" + namespaceURI + "}" + localName;
    }
    
    private XSDType resolveXsiType(String typeName) {
        String localName = XSDUtils.extractLocalName(typeName);
        return schema.getType(localName);
    }
    
    private void validateAttributes(Attributes atts) throws SAXException {
        // Get expected attributes from type
        Map<String, XSDAttribute> expectedAttrs = Collections.emptyMap();
        if (currentElementType instanceof XSDComplexType) {
            expectedAttrs = ((XSDComplexType) currentElementType).getAttributes();
        }
        
        // Track which required attributes are present
        presentAttrs.clear();
        
        for (int i = 0; i < atts.getLength(); i++) {
            String attrUri = atts.getURI(i);
            String attrLocal = atts.getLocalName(i);
            String attrValue = atts.getValue(i);
            
            // Skip xsi: attributes
            if (XSDSchema.XSI_NAMESPACE.equals(attrUri)) {
                continue;
            }
            
            presentAttrs.add(attrLocal);
            
            // Find attribute declaration
            XSDAttribute attrDecl = expectedAttrs.get(attrLocal);
            currentAttributeDecls[i] = attrDecl;
            
            if (attrDecl != null) {
                // Validate attribute value
                String error = attrDecl.validate(attrValue);
                if (error != null) {
                    reportError(error);
                }
                
                // Create typed value
                if (attrDecl.getType() != null) {
                    currentAttributeValues[i] = createTypedValue(
                        attrDecl.getType(), attrValue);
                    
                    // Track IDs and IDREFs
                    if (attrDecl.isIdAttribute()) {
                        if (!declaredIds.add(attrValue)) {
                            reportError("Duplicate ID value: " + attrValue);
                        }
                    }
                    if ("IDREF".equals(attrDecl.getType().getName())) {
                        pendingIdrefs.add(attrValue);
                    }
                    if ("IDREFS".equals(attrDecl.getType().getName())) {
                        for (String ref : attrValue.split("\\s+")) {
                            pendingIdrefs.add(ref);
                        }
                    }
                }
            }
        }
        
        // Check for missing required attributes
        for (XSDAttribute expected : expectedAttrs.values()) {
            if (expected.isRequired() && !presentAttrs.contains(expected.getName())) {
                reportError("Required attribute missing: " + expected.getName());
            }
        }
    }
    
    private TypedValue createTypedValue(XSDSimpleType type, String lexicalValue) {
        Object typedValue = XSDTypeConverter.convert(type.getName(), lexicalValue);
        return new XSDTypedValue(type.getName(), typedValue, lexicalValue);
    }
    
    private void reportError(String message) throws SAXException {
        currentValidity = Validity.INVALID;
        validationErrors.add(message);
        
        if (errorHandler != null) {
            errorHandler.error(new SAXParseException(message, null));
        }
    }
    
    // ========================================================================
    // PSVIProvider implementation
    // ========================================================================
    
    @Override
    public Validity getValidity() {
        return currentValidity;
    }
    
    @Override
    public ValidationSource getValidationSource() {
        return ValidationSource.XSD;
    }
    
    @Override
    public String getDTDAttributeType(int attrIndex) {
        // Map XSD types to DTD-style types for compatibility
        if (currentAttributeDecls != null && attrIndex >= 0 && 
            attrIndex < currentAttributeDecls.length) {
            XSDAttribute decl = currentAttributeDecls[attrIndex];
            if (decl != null && decl.getType() != null) {
                String typeName = decl.getType().getName();
                // Map XSD types to DTD equivalents
                switch (typeName) {
                    case "ID": return "ID";
                    case "IDREF": return "IDREF";
                    case "IDREFS": return "IDREFS";
                    case "NMTOKEN": return "NMTOKEN";
                    case "NMTOKENS": return "NMTOKENS";
                    case "ENTITY": return "ENTITY";
                    case "ENTITIES": return "ENTITIES";
                    default: return "CDATA";
                }
            }
        }
        return "CDATA";
    }
    
    @Override
    public TypedValue getElementTypedValue() {
        // Element typed value is available after endElement
        // During startElement, we don't have the content yet
        if (!elementStack.isEmpty()) {
            ElementContext ctx = elementStack.peek();
            if (ctx.type instanceof XSDSimpleType) {
                String content = ctx.content.toString();
                return createTypedValue((XSDSimpleType) ctx.type, content);
            }
            if (ctx.type instanceof XSDComplexType) {
                XSDComplexType ct = (XSDComplexType) ctx.type;
                if (ct.getSimpleContentType() != null) {
                    String content = ctx.content.toString();
                    return createTypedValue(ct.getSimpleContentType(), content);
                }
            }
        }
        return null;
    }
    
    @Override
    public TypedValue getAttributeTypedValue(int attrIndex) {
        if (currentAttributeValues != null && attrIndex >= 0 && 
            attrIndex < currentAttributeValues.length) {
            return currentAttributeValues[attrIndex];
        }
        return null;
    }
    
    @Override
    public Object getXSDTypeDefinition() {
        return currentElementType;
    }
    
    @Override
    public Boolean isNil() {
        return currentNil;
    }
    
    /**
     * Returns the list of validation errors encountered during parsing.
     *
     * <p>The list contains all validation errors that occurred, in the order
     * they were encountered. Errors are also reported through the SAX
     * {@link ErrorHandler} if one is set.
     *
     * @return an unmodifiable list of error messages
     */
    public List<String> getValidationErrors() {
        return Collections.unmodifiableList(validationErrors);
    }
    
    /**
     * Returns true if the document validated successfully.
     *
     * <p>This method returns true only if no validation errors were encountered.
     * It should be checked after parsing completes (after {@code endDocument}
     * callback).
     *
     * @return true if validation succeeded, false if any errors occurred
     */
    public boolean isValid() {
        return currentValidity == Validity.VALID;
    }
}

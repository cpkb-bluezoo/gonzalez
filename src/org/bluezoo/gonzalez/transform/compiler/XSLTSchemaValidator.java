/*
 * XSLTSchemaValidator.java
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

package org.bluezoo.gonzalez.transform.compiler;

import org.bluezoo.gonzalez.schema.xsd.XSDAttribute;
import org.bluezoo.gonzalez.schema.xsd.XSDComplexType;
import org.bluezoo.gonzalez.schema.xsd.XSDElement;
import org.bluezoo.gonzalez.schema.xsd.XSDSchema;
import org.bluezoo.gonzalez.schema.xsd.XSDSchemaParser;
import org.bluezoo.gonzalez.schema.xsd.XSDSimpleType;
import org.bluezoo.gonzalez.schema.xsd.XSDType;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validates XSLT elements and attributes using the official W3C XSLT schema.
 *
 * <p>This class provides static error detection for:
 * <ul>
 *   <li>XTSE0090: Unknown attribute on XSLT element</li>
 *   <li>XTSE0020: Invalid attribute value</li>
 * </ul>
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public class XSLTSchemaValidator {

    private static final String XSLT_NS = "http://www.w3.org/1999/XSL/Transform";
    
    /** Singleton instance for XSLT 2.0 validation. */
    private static volatile XSLTSchemaValidator instance20;
    
    /** Singleton instance for XSLT 3.0 validation. */
    private static volatile XSLTSchemaValidator instance30;
    
    private final XSDSchema schema;
    
    // Regex for QName validation (NCName or NCName:NCName)
    private static final Pattern NCNAME_PATTERN = Pattern.compile(
        "[_A-Za-z\\u00C0-\\u00D6\\u00D8-\\u00F6\\u00F8-\\u02FF\\u0370-\\u037D\\u037F-\\u1FFF" +
        "\\u200C-\\u200D\\u2070-\\u218F\\u2C00-\\u2FEF\\u3001-\\uD7FF\\uF900-\\uFDCF\\uFDF0-\\uFFFD]" +
        "[_A-Za-z0-9\\u00B7\\u00C0-\\u00D6\\u00D8-\\u00F6\\u00F8-\\u02FF\\u0300-\\u037D\\u037F-\\u1FFF" +
        "\\u200C-\\u200D\\u203F-\\u2040\\u2070-\\u218F\\u2C00-\\u2FEF\\u3001-\\uD7FF\\uF900-\\uFDCF" +
        "\\uFDF0-\\uFFFD.-]*");
    
    private static final Pattern QNAME_PATTERN = Pattern.compile(
        NCNAME_PATTERN.pattern() + "(:" + NCNAME_PATTERN.pattern() + ")?");
    
    /**
     * Creates a validator with the given schema.
     */
    private XSLTSchemaValidator(XSDSchema schema) {
        this.schema = schema;
    }
    
    /**
     * Gets the XSLT 2.0 schema validator (lazy loading).
     */
    public static XSLTSchemaValidator getInstance20() {
        if (instance20 == null) {
            synchronized (XSLTSchemaValidator.class) {
                if (instance20 == null) {
                    try {
                        XSDSchema schema = loadSchema("/META-INF/xslt20.xsd");
                        instance20 = new XSLTSchemaValidator(schema);
                    } catch (Exception e) {
                        // Schema not available - return null validator
                        instance20 = new XSLTSchemaValidator(null);
                    }
                }
            }
        }
        return instance20;
    }
    
    /**
     * Gets the XSLT 3.0 schema validator (lazy loading).
     */
    public static XSLTSchemaValidator getInstance30() {
        if (instance30 == null) {
            synchronized (XSLTSchemaValidator.class) {
                if (instance30 == null) {
                    try {
                        XSDSchema schema = loadSchema("/META-INF/xslt30.xsd");
                        instance30 = new XSLTSchemaValidator(schema);
                    } catch (Exception e) {
                        // Schema not available - return null validator  
                        instance30 = new XSLTSchemaValidator(null);
                    }
                }
            }
        }
        return instance30;
    }
    
    private static XSDSchema loadSchema(String resourcePath) throws SAXException, IOException {
        try (InputStream is = XSLTSchemaValidator.class.getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new IOException("XSLT schema not found: " + resourcePath);
            }
            XSDSchemaParser parser = new XSDSchemaParser();
            return parser.parseStream(is);
        }
    }
    
    /**
     * Validates that an attribute is allowed on the given XSLT element.
     * Uses the same validation logic as {@link org.bluezoo.gonzalez.schema.xsd.XSDValidator}.
     *
     * @param elementName the local name of the XSLT element
     * @param attributeName the attribute name to validate
     * @throws SAXException XTSE0090 if the attribute is not allowed
     */
    public void validateAttribute(String elementName, String attributeName) throws SAXException {
        if (schema == null) {
            return; // No schema available, skip validation
        }
        
        XSDElement element = schema.getElement(elementName);
        if (element == null) {
            return; // Unknown element - handled elsewhere
        }
        
        XSDType type = element.getType();
        if (!(type instanceof XSDComplexType)) {
            return; // Simple type elements have no attributes
        }
        
        XSDComplexType complexType = (XSDComplexType) type;
        Map<String, XSDAttribute> attrs = complexType.getAttributes();
        
        if (!attrs.containsKey(attributeName)) {
            throw new SAXException("XTSE0090: Unknown attribute '" + attributeName + 
                "' on xsl:" + elementName);
        }
    }
    
    /**
     * Validates an attribute value against its declared type.
     * Uses the same validation logic as {@link org.bluezoo.gonzalez.schema.xsd.XSDValidator}.
     *
     * @param elementName the local name of the XSLT element
     * @param attributeName the attribute name
     * @param value the attribute value to validate
     * @throws SAXException XTSE0020 if the value is invalid
     */
    public void validateAttributeValue(String elementName, String attributeName, String value) 
            throws SAXException {
        if (schema == null || value == null) {
            return;
        }
        
        XSDElement element = schema.getElement(elementName);
        if (element == null) {
            return;
        }
        
        XSDType type = element.getType();
        if (!(type instanceof XSDComplexType)) {
            return;
        }
        
        XSDComplexType complexType = (XSDComplexType) type;
        XSDAttribute attr = complexType.getAttribute(attributeName);
        if (attr == null) {
            return; // Unknown attribute - handled by validateAttribute
        }
        
        // Use the attribute's own validate method (same as XSDValidator does)
        String error = attr.validate(value);
        if (error != null) {
            throw new SAXException("XTSE0020: " + error + " for attribute '" + 
                attributeName + "' on xsl:" + elementName);
        }
    }
    
    /**
     * Validates that a QName attribute value is a valid QName.
     *
     * @param attributeName the attribute name (for error message)
     * @param value the value to validate
     * @throws SAXException XTSE0020 if not a valid QName
     */
    public static void validateQName(String attributeName, String value) throws SAXException {
        if (value == null || value.isEmpty()) {
            return;
        }
        
        // Handle EQName syntax: Q{uri}localname (XSLT 3.0)
        if (value.startsWith("Q{")) {
            int endBrace = value.indexOf('}');
            if (endBrace > 0 && endBrace < value.length() - 1) {
                String localPart = value.substring(endBrace + 1);
                if (!isNCName(localPart)) {
                    throw new SAXException("XTSE0020: Invalid EQName '" + value + 
                        "' for attribute '" + attributeName + "'");
                }
                return;
            }
            throw new SAXException("XTSE0020: Invalid EQName '" + value + 
                "' for attribute '" + attributeName + "'");
        }
        
        // Standard QName: NCName or NCName:NCName
        if (!QNAME_PATTERN.matcher(value).matches()) {
            throw new SAXException("XTSE0020: Invalid QName '" + value + 
                "' for attribute '" + attributeName + "'");
        }
    }
    
    /**
     * Checks if a string is a valid NCName.
     */
    public static boolean isNCName(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        return NCNAME_PATTERN.matcher(s).matches();
    }
    
    /**
     * Checks if a string is a valid QName.
     */
    public static boolean isQName(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        // Handle EQName
        if (s.startsWith("Q{")) {
            int endBrace = s.indexOf('}');
            if (endBrace > 0 && endBrace < s.length() - 1) {
                return isNCName(s.substring(endBrace + 1));
            }
            return false;
        }
        return QNAME_PATTERN.matcher(s).matches();
    }
    
    
    /**
     * Returns the set of allowed attributes for an element.
     *
     * @param elementName the local name of the XSLT element
     * @return the set of allowed attribute names, or null if element not found
     */
    public Set<String> getAllowedAttributes(String elementName) {
        if (schema == null) {
            return null;
        }
        
        XSDElement element = schema.getElement(elementName);
        if (element == null) {
            return null;
        }
        
        XSDType type = element.getType();
        if (!(type instanceof XSDComplexType)) {
            return null;
        }
        
        return ((XSDComplexType) type).getAttributes().keySet();
    }
}

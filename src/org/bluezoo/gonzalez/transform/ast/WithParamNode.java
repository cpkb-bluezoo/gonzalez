/*
 * WithParamNode.java
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

import org.xml.sax.SAXException;

import org.bluezoo.gonzalez.transform.runtime.BufferOutputHandler;
import org.bluezoo.gonzalez.transform.runtime.OutputHandler;
import org.bluezoo.gonzalez.transform.runtime.SAXEventBuffer;
import org.bluezoo.gonzalez.transform.runtime.TransformContext;
import org.bluezoo.gonzalez.transform.xpath.XPathExpression;
import org.bluezoo.gonzalez.transform.xpath.expr.XPathException;
import org.bluezoo.gonzalez.transform.xpath.type.SequenceType;
import org.bluezoo.gonzalez.transform.xpath.type.XPathBoolean;
import org.bluezoo.gonzalez.transform.xpath.type.XPathDateTime;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNumber;
import org.bluezoo.gonzalez.transform.xpath.type.XPathResultTreeFragment;
import org.bluezoo.gonzalez.transform.xpath.type.XPathString;
import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;

/**
 * WithParamNode XSLT instruction.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public class WithParamNode extends XSLTInstruction {
    private final String namespaceURI;
    private final String localName;
    private final String expandedName; // Clark notation: {uri}localname or just localname
    private final XPathExpression selectExpr;
    private final SequenceNode content;
    private final boolean tunnel; // XSLT 2.0: whether this is a tunnel parameter
    private final String asType; // XSLT 2.0+: declared type
    public WithParamNode(String namespaceURI, String localName, XPathExpression selectExpr, SequenceNode content) {
        this(namespaceURI, localName, selectExpr, content, false, null);
    }
    public WithParamNode(String namespaceURI, String localName, XPathExpression selectExpr, SequenceNode content, boolean tunnel) {
        this(namespaceURI, localName, selectExpr, content, tunnel, null);
    }
    public WithParamNode(String namespaceURI, String localName, XPathExpression selectExpr, SequenceNode content, boolean tunnel, String asType) {
        this.namespaceURI = namespaceURI;
        this.localName = localName;
        this.expandedName = makeExpandedName(namespaceURI, localName);
        this.selectExpr = selectExpr;
        this.content = content;
        this.tunnel = tunnel;
        this.asType = asType;
    }
    private static String makeExpandedName(String namespaceURI, String localName) {
        if (namespaceURI == null || namespaceURI.isEmpty()) {
            return localName;
        }
        return "{" + namespaceURI + "}" + localName;
    }
    public String getNamespaceURI() { return namespaceURI; }
    public String getLocalName() { return localName; }
    public String getName() { return expandedName; }
    public XPathExpression getSelectExpr() { return selectExpr; }
    public SequenceNode getContent() { return content; }
    public boolean isTunnel() { return tunnel; }
    public String getAsType() { return asType; }
    @Override public String getInstructionName() { return "with-param"; }
    @Override public void execute(TransformContext context, 
                                  OutputHandler output) throws SAXException {
        // With-param nodes are handled by call-template/apply-templates
    }
    
    public XPathValue evaluate(
            TransformContext context) 
            throws XPathException, SAXException {
        XPathValue value;
        if (selectExpr != null) {
            value = selectExpr.evaluate(context);
        } else if (content != null) {
            SAXEventBuffer buffer = new SAXEventBuffer();
            content.execute(context, new BufferOutputHandler(buffer));
            value = new XPathResultTreeFragment(buffer);
        } else {
            value = XPathString.of("");
        }
        
        // Apply type coercion and validation for 'as' attribute (XSLT 2.0+)
        if (asType != null && value != null && context.isStrictTypeChecking()) {
            try {
                // Parse the expected type to check if we can handle it
                SequenceType expectedType = SequenceType.parse(asType, null);
                
                // Only attempt coercion and validation for atomic single-value types
                // Skip node types and sequences for now (require more complex handling)
                if (expectedType != null && expectedType.getItemKind() == SequenceType.ItemKind.ATOMIC &&
                    expectedType.getOccurrence() == SequenceType.Occurrence.ONE) {
                    
                    // Try to coerce the value to the target type
                    value = coerceParameterValue(value, asType);
                    
                    // Then validate that it matches
                    if (!expectedType.matches(value)) {
                        throw new XPathException("XTTE0590: Parameter value does not match declared type '" + 
                            asType + "'. Got: " + (value != null ? value.getType() : "null"));
                    }
                }
            } catch (XPathException e) {
                // Re-wrap with context about parameter
                throw new XPathException("XTTE0590: " + e.getMessage());
            }
        }
        
        return value;
    }
    
    /**
     * Coerces a parameter value to match the declared type.
     * Similar to function return type coercion.
     */
    private XPathValue coerceParameterValue(XPathValue value, String targetType) throws XPathException {
        if (value == null || targetType == null) {
            return value;
        }
        
        // Parse the target type
        SequenceType expectedType = SequenceType.parse(targetType, null);
        if (expectedType == null) {
            return value;
        }
        
        // If it's an atomic type, try string-to-atomic conversion
        if (expectedType.getItemKind() == SequenceType.ItemKind.ATOMIC) {
            String typeLocalName = expectedType.getLocalName();
            String typeNsUri = expectedType.getNamespaceURI();
            
            if (typeNsUri != null && typeNsUri.equals(SequenceType.XS_NAMESPACE)) {
                // Get the string value and try to convert
                String stringValue = value.asString();
                try {
                    switch (typeLocalName) {
                        case "double":
                        case "float":
                            return new XPathNumber(Double.parseDouble(stringValue));
                        case "decimal":
                        case "integer":
                        case "int":
                        case "long":
                        case "short":
                            return new XPathNumber(Double.parseDouble(stringValue));
                        case "boolean":
                            return XPathBoolean.of("true".equals(stringValue) || "1".equals(stringValue));
                        case "string":
                            return new XPathString(stringValue);
                        case "date":
                            return XPathDateTime.parseDate(stringValue);
                        case "dateTime":
                            return XPathDateTime.parseDateTime(stringValue);
                        case "time":
                            return XPathDateTime.parseTime(stringValue);
                        case "duration":
                        case "dayTimeDuration":
                        case "yearMonthDuration":
                            return XPathDateTime.parseDuration(stringValue);
                        // Add more types as needed
                        default:
                            // For unknown types, return as-is
                            return value;
                    }
                } catch (NumberFormatException e) {
                    return value;
                } catch (XPathException e) {
                    return value;
                }
            }
        }
        
        return value;
    }
}

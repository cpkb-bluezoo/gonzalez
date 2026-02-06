/*
 * XSDSchemaParser.java
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

import org.xml.sax.*;
import org.xml.sax.helpers.DefaultHandler;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.*;

/**
 * Parses XSD schema documents into an {@link XSDSchema} model.
 *
 * <p>This is a minimal implementation that supports:
 * <ul>
 *   <li>Global element and attribute declarations</li>
 *   <li>Simple and complex type definitions</li>
 *   <li>Sequence, choice, and all model groups</li>
 *   <li>Built-in type references</li>
 *   <li>Basic restriction and extension</li>
 * </ul>
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public class XSDSchemaParser extends DefaultHandler {
    
    private static final String XSD_NS = XSDSchema.XSD_NAMESPACE;
    
    private XSDSchema schema;
    private final Deque<Object> stack = new ArrayDeque<>();
    private Deque<XSDParticle> particleStack = new ArrayDeque<>();
    // Stack of saved particle stacks, used when entering nested complex types
    private final Deque<Deque<XSDParticle>> savedParticleStacks = new ArrayDeque<>();
    private StringBuilder textContent;
    
    // For resolving type references after parsing
    private final List<Runnable> pendingTypeResolutions = new ArrayList<>();
    
    /**
     * Parses an XSD schema from a URI.
     *
     * <p>Static convenience method for one-off parsing. This method handles
     * file: URIs, http: URIs, and local file paths.
     *
     * @param uri the URI of the XSD document (file path, file: URI, or http: URI)
     * @return the parsed schema, never null
     * @throws SAXException if parsing fails due to XML errors
     * @throws IOException if an I/O error occurs reading the schema
     */
    public static XSDSchema parse(String uri) throws SAXException, IOException {
        XSDSchemaParser parser = new XSDSchemaParser();
        
        // Open the URI as a byte stream (required by Gonzalez parser)
        InputStream inputStream;
        if (uri.startsWith("file:")) {
            // Handle file: URIs
            try {
                URI fileUri = new URI(uri);
                inputStream = new FileInputStream(new File(fileUri));
            } catch (URISyntaxException e) {
                throw new IOException("Invalid URI: " + uri, e);
            }
        } else if (uri.contains("://")) {
            // Handle other URIs (http, etc.)
            inputStream = new URL(uri).openStream();
        } else {
            // Assume it's a file path
            inputStream = new FileInputStream(uri);
        }
        
        try {
            InputSource source = new InputSource(inputStream);
            source.setSystemId(uri);
            return parser.parse(source);
        } finally {
            inputStream.close();
        }
    }
    
    /**
     * Parses an XSD schema from an input stream.
     *
     * @param input the input stream containing the XSD document
     * @return the parsed schema
     * @throws SAXException if parsing fails
     * @throws IOException if an I/O error occurs
     */
    public XSDSchema parseStream(InputStream input) throws SAXException, IOException {
        return parse(new InputSource(input));
    }
    
    /**
     * Parses an XSD schema from an input source.
     *
     * @param source the input source
     * @return the parsed schema
     * @throws SAXException if parsing fails
     * @throws IOException if an I/O error occurs
     */
    public XSDSchema parse(InputSource source) throws SAXException, IOException {
        try {
            // Use Gonzalez parser if available
            XMLReader reader;
            try {
                Class<?> parserClass = Class.forName("org.bluezoo.gonzalez.Parser");
                reader = (XMLReader) parserClass.getDeclaredConstructor().newInstance();
            } catch (Exception e) {
                reader = org.xml.sax.helpers.XMLReaderFactory.createXMLReader();
            }
            
            reader.setContentHandler(this);
            reader.parse(source);
            
            // Resolve pending type references
            for (Runnable resolver : pendingTypeResolutions) {
                resolver.run();
            }
            
            return schema;
        } finally {
            stack.clear();
            particleStack.clear();
            savedParticleStacks.clear();
            pendingTypeResolutions.clear();
        }
    }
    
    /**
     * Finalizes the schema after inline SAX events have been processed.
     * This resolves pending type references and returns the parsed schema.
     * 
     * @return the parsed schema, or null if no schema element was processed
     */
    public XSDSchema finalizeParsing() {
        // Resolve pending type references
        for (Runnable resolver : pendingTypeResolutions) {
            resolver.run();
        }
        return schema;
    }
    
    /**
     * Returns the current schema being built.
     * Use finalizeParsing() to get the completed schema.
     */
    public XSDSchema getCurrentSchema() {
        return schema;
    }
    
    @Override
    public void startElement(String uri, String localName, String qName, Attributes atts) 
            throws SAXException {
        
        if (!XSD_NS.equals(uri)) {
            return; // Ignore non-XSD elements
        }
        
        switch (localName) {
            case "schema":
                handleSchema(atts);
                break;
            case "element":
                handleElement(atts);
                break;
            case "attribute":
                handleAttribute(atts);
                break;
            case "complexType":
                handleComplexType(atts);
                break;
            case "simpleType":
                handleSimpleType(atts);
                break;
            case "sequence":
                handleSequence(atts);
                break;
            case "choice":
                handleChoice(atts);
                break;
            case "all":
                handleAll(atts);
                break;
            case "any":
                handleAny(atts);
                break;
            case "restriction":
                handleRestriction(atts);
                break;
            case "extension":
                handleExtension(atts);
                break;
            case "simpleContent":
            case "complexContent":
                // Just markers - push them on stack
                stack.push(localName);
                break;
            case "enumeration":
                handleEnumeration(atts);
                break;
            case "minLength":
            case "maxLength":
            case "length":
            case "pattern":
            case "minInclusive":
            case "maxInclusive":
            case "minExclusive":
            case "maxExclusive":
            case "totalDigits":
            case "fractionDigits":
            case "whiteSpace":
                handleFacet(localName, atts);
                break;
            case "annotation":
            case "documentation":
            case "appinfo":
                // Ignore documentation
                break;
            default:
                // Unknown element - ignore
                break;
        }
        
        textContent = new StringBuilder();
    }
    
    @Override
    public void endElement(String uri, String localName, String qName) throws SAXException {
        if (!XSD_NS.equals(uri)) {
            return;
        }
        
        switch (localName) {
            case "schema":
                // Done
                break;
            case "element":
                endElement();
                break;
            case "attribute":
                endAttribute();
                break;
            case "complexType":
                endComplexType();
                break;
            case "simpleType":
                endSimpleType();
                break;
            case "sequence":
            case "choice":
            case "all":
                endModelGroup();
                break;
            case "simpleContent":
            case "complexContent":
                if (!stack.isEmpty() && stack.peek() instanceof String) {
                    stack.pop();
                }
                break;
            case "restriction":
            case "extension":
                // Handled by parent end
                break;
        }
        
        textContent = null;
    }
    
    @Override
    public void characters(char[] ch, int start, int length) throws SAXException {
        if (textContent != null) {
            textContent.append(ch, start, length);
        }
    }
    
    // === Handler methods ===
    
    private void handleSchema(Attributes atts) {
        String targetNs = atts.getValue("targetNamespace");
        schema = new XSDSchema(targetNs);
        
        String elementForm = atts.getValue("elementFormDefault");
        if (elementForm != null) {
            schema.setElementFormDefault(elementForm);
        }
        
        String attrForm = atts.getValue("attributeFormDefault");
        if (attrForm != null) {
            schema.setAttributeFormDefault(attrForm);
        }
        
        stack.push(schema);
    }
    
    private void handleElement(Attributes atts) {
        String name = atts.getValue("name");
        String ref = atts.getValue("ref");
        
        if (ref != null) {
            // Element reference - resolve later
            // For now, create placeholder
            name = XSDUtils.extractLocalName(ref);
        }
        
        String ns = schema.getTargetNamespace();
        XSDElement element = new XSDElement(name, ns);
        
        // Type reference
        String typeName = atts.getValue("type");
        if (typeName != null) {
            element.setTypeName(typeName);
            scheduleTypeResolution(element, typeName);
        }
        
        // Occurrence
        String minOccurs = atts.getValue("minOccurs");
        if (minOccurs != null) {
            element.setMinOccurs(Integer.parseInt(minOccurs));
        }
        String maxOccurs = atts.getValue("maxOccurs");
        if (maxOccurs != null) {
            element.setMaxOccurs("unbounded".equals(maxOccurs) ? -1 : Integer.parseInt(maxOccurs));
        }
        
        // Other attributes
        String defaultVal = atts.getValue("default");
        if (defaultVal != null) {
            element.setDefaultValue(defaultVal);
        }
        
        String fixedVal = atts.getValue("fixed");
        if (fixedVal != null) {
            element.setFixedValue(fixedVal);
        }
        
        String nillable = atts.getValue("nillable");
        if ("true".equals(nillable)) element.setNillable(true);
        
        String form = atts.getValue("form");
        if (form != null) {
            element.setForm(form);
        }
        
        stack.push(element);
        
        // If we're inside a model group, add to particle stack
        if (!particleStack.isEmpty()) {
            particleStack.peek().addChild(XSDParticle.element(element));
        }
    }
    
    private void endElement() {
        if (stack.isEmpty()) return;
        
        Object top = stack.pop();
        if (!(top instanceof XSDElement)) return;
        
        XSDElement element = (XSDElement) top;
        
        // Add to appropriate container
        if (!stack.isEmpty()) {
            Object parent = stack.peek();
            if (parent instanceof XSDSchema) {
                // Global element
                schema.addElement(element);
            }
            // Local elements are already added to particles
        } else {
            schema.addElement(element);
        }
    }
    
    private void handleAttribute(Attributes atts) {
        String name = atts.getValue("name");
        String ref = atts.getValue("ref");
        
        if (ref != null) {
            name = XSDUtils.extractLocalName(ref);
        }
        
        XSDAttribute attribute = new XSDAttribute(name, null);
        
        // Type reference
        String typeName = atts.getValue("type");
        if (typeName != null) {
            attribute.setTypeName(typeName);
            scheduleAttributeTypeResolution(attribute, typeName);
        }
        
        // Use
        String use = atts.getValue("use");
        if ("required".equals(use)) {
            attribute.setUse(XSDAttribute.Use.REQUIRED);
        } else if ("prohibited".equals(use)) {
            attribute.setUse(XSDAttribute.Use.PROHIBITED);
        }
        
        // Default/fixed
        String defaultVal = atts.getValue("default");
        if (defaultVal != null) {
            attribute.setDefaultValue(defaultVal);
        }
        
        String fixedVal = atts.getValue("fixed");
        if (fixedVal != null) {
            attribute.setFixedValue(fixedVal);
        }
        
        stack.push(attribute);
    }
    
    private void endAttribute() {
        if (stack.isEmpty()) return;
        
        Object top = stack.pop();
        if (!(top instanceof XSDAttribute)) return;
        
        XSDAttribute attribute = (XSDAttribute) top;
        
        // Add to appropriate container - look past string markers (complexContent, etc.)
        for (Object item : stack) {
            if (item instanceof XSDComplexType) {
                ((XSDComplexType) item).addAttribute(attribute);
                return;
            } else if (item instanceof XSDSchema) {
                schema.addAttribute(attribute);
                return;
            }
        }
    }
    
    private void handleComplexType(Attributes atts) {
        String name = atts.getValue("name");
        String ns = name != null ? schema.getTargetNamespace() : null;
        
        XSDComplexType type = new XSDComplexType(name, ns);
        
        String mixed = atts.getValue("mixed");
        if ("true".equals(mixed)) {
            type.setMixed(true);
        }
        
        // Save current particle stack and start fresh for this complex type's content model
        // This is needed for nested elements that have their own complex types (e.g., 
        // <xs:element name="row"><xs:complexType>...</xs:complexType></xs:element>)
        savedParticleStacks.push(particleStack);
        particleStack = new ArrayDeque<>();
        
        if (Boolean.getBoolean("debug.schema")) {
            System.err.println("DEBUG SCHEMA: handleComplexType name=" + name + ", saved " + savedParticleStacks.size() + " stacks");
        }
        
        stack.push(type);
    }
    
    private void endComplexType() {
        if (stack.isEmpty()) return;
        
        Object top = stack.pop();
        if (!(top instanceof XSDComplexType)) return;
        
        XSDComplexType type = (XSDComplexType) top;
        
        if (Boolean.getBoolean("debug.schema")) {
            System.err.println("DEBUG SCHEMA: endComplexType name=" + type.getName() + ", particles=" + type.getParticles().size());
        }
        
        // Restore the saved particle stack
        if (!savedParticleStacks.isEmpty()) {
            particleStack = savedParticleStacks.pop();
        }
        
        // Check if this is a named type or anonymous
        if (type.getName() != null) {
            schema.addType(type.getName(), type);
        }
        
        // If parent is an element, set as its type
        if (!stack.isEmpty()) {
            Object parent = stack.peek();
            if (parent instanceof XSDElement) {
                if (Boolean.getBoolean("debug.schema")) {
                    System.err.println("DEBUG SCHEMA: Setting type " + type + " on element " + ((XSDElement)parent).getName());
                }
                ((XSDElement) parent).setType(type);
            }
        }
    }
    
    private void handleSimpleType(Attributes atts) {
        String name = atts.getValue("name");
        String ns = name != null ? schema.getTargetNamespace() : null;
        
        XSDSimpleType type = new XSDSimpleType(name, ns, null);
        stack.push(type);
    }
    
    private void endSimpleType() {
        if (stack.isEmpty()) return;
        
        Object top = stack.pop();
        if (!(top instanceof XSDSimpleType)) return;
        
        XSDSimpleType type = (XSDSimpleType) top;
        
        // Check if this is a named type
        if (type.getName() != null) {
            schema.addType(type.getName(), type);
        }
        
        // If parent is an element or attribute, set as its type
        if (!stack.isEmpty()) {
            Object parent = stack.peek();
            if (parent instanceof XSDElement) {
                ((XSDElement) parent).setType(type);
            } else if (parent instanceof XSDAttribute) {
                ((XSDAttribute) parent).setType(type);
            } else if (parent instanceof XSDComplexType) {
                // Simple content
                ((XSDComplexType) parent).setSimpleContentType(type);
            }
        }
    }
    
    private void handleSequence(Attributes atts) {
        XSDParticle particle = XSDParticle.sequence();
        setParticleOccurrence(particle, atts);
        pushParticle(particle);
    }
    
    private void handleChoice(Attributes atts) {
        XSDParticle particle = XSDParticle.choice();
        setParticleOccurrence(particle, atts);
        pushParticle(particle);
    }
    
    private void handleAll(Attributes atts) {
        XSDParticle particle = XSDParticle.all();
        setParticleOccurrence(particle, atts);
        pushParticle(particle);
    }
    
    private void handleAny(Attributes atts) {
        String namespace = atts.getValue("namespace");
        String processContents = atts.getValue("processContents");
        XSDParticle particle = XSDParticle.any(namespace, processContents);
        setParticleOccurrence(particle, atts);
        
        if (!particleStack.isEmpty()) {
            particleStack.peek().addChild(particle);
        }
    }
    
    private void pushParticle(XSDParticle particle) {
        if (!particleStack.isEmpty()) {
            particleStack.peek().addChild(particle);
        }
        particleStack.push(particle);
    }
    
    private void endModelGroup() {
        if (particleStack.isEmpty()) return;
        
        XSDParticle particle = particleStack.pop();
        
        // If this is the top-level model group, add to complex type
        // Find the most recently pushed (topmost) complex type on the stack
        if (particleStack.isEmpty()) {
            for (Object item : stack) {
                if (item instanceof XSDComplexType) {
                    XSDComplexType ct = (XSDComplexType) item;
                    ct.addParticle(particle);
                    // Debug
                    if (Boolean.getBoolean("debug.schema")) {
                        System.err.println("DEBUG SCHEMA: Added particle " + particle + " to " + ct.getName() + ", now has " + ct.getParticles().size() + " particles");
                    }
                    break;
                }
            }
        }
    }
    
    private void setParticleOccurrence(XSDParticle particle, Attributes atts) {
        String minOccurs = atts.getValue("minOccurs");
        if (minOccurs != null) {
            particle.setMinOccurs(Integer.parseInt(minOccurs));
        }
        String maxOccurs = atts.getValue("maxOccurs");
        if (maxOccurs != null) {
            particle.setMaxOccurs("unbounded".equals(maxOccurs) ? -1 : Integer.parseInt(maxOccurs));
        }
    }
    
    private void handleRestriction(Attributes atts) {
        String base = atts.getValue("base");
        if (base == null) {
            return;
        }
        
        // Find the type being restricted
        Object top = stack.peek();
        if (top instanceof XSDSimpleType) {
            scheduleSimpleTypeBaseResolution((XSDSimpleType) top, base);
        } else if (top instanceof XSDComplexType) {
            scheduleComplexTypeBaseResolution((XSDComplexType) top, base);
        }
    }
    
    private void handleExtension(Attributes atts) {
        String base = atts.getValue("base");
        if (base == null) {
            return;
        }
        
        Object top = stack.peek();
        if (top instanceof XSDComplexType) {
            scheduleComplexTypeBaseResolution((XSDComplexType) top, base);
        }
    }
    
    private void handleEnumeration(Attributes atts) {
        String value = atts.getValue("value");
        if (value == null) {
            return;
        }
        
        // Find parent simple type
        for (Object item : stack) {
            if (item instanceof XSDSimpleType) {
                ((XSDSimpleType) item).addEnumeration(value);
                break;
            }
        }
    }
    
    private void handleFacet(String facetName, Attributes atts) {
        String value = atts.getValue("value");
        if (value == null) {
            return;
        }
        
        // Find parent simple type
        for (Object item : stack) {
            if (item instanceof XSDSimpleType) {
                XSDSimpleType type = (XSDSimpleType) item;
                switch (facetName) {
                    case "minLength":
                        type.setMinLength(Integer.parseInt(value));
                        break;
                    case "maxLength":
                        type.setMaxLength(Integer.parseInt(value));
                        break;
                    case "length":
                        type.setLength(Integer.parseInt(value));
                        break;
                    case "pattern":
                        type.setPattern(value);
                        break;
                    case "minInclusive":
                        type.setMinInclusive(value);
                        break;
                    case "maxInclusive":
                        type.setMaxInclusive(value);
                        break;
                    case "minExclusive":
                        type.setMinExclusive(value);
                        break;
                    case "maxExclusive":
                        type.setMaxExclusive(value);
                        break;
                    case "totalDigits":
                        type.setTotalDigits(Integer.parseInt(value));
                        break;
                    case "fractionDigits":
                        type.setFractionDigits(Integer.parseInt(value));
                        break;
                    case "whiteSpace":
                        type.setWhitespace(XSDSimpleType.WhitespaceHandling.valueOf(value.toUpperCase()));
                        break;
                }
                break;
            }
        }
    }
    
    // === Type resolution ===
    
    private void scheduleTypeResolution(final XSDElement element, final String typeName) {
        pendingTypeResolutions.add(new Runnable() {
            @Override
            public void run() {
                XSDType type = resolveType(typeName);
                if (type != null) {
                    element.setType(type);
                }
            }
        });
    }
    
    private void scheduleAttributeTypeResolution(final XSDAttribute attribute, final String typeName) {
        pendingTypeResolutions.add(new Runnable() {
            @Override
            public void run() {
                XSDType type = resolveType(typeName);
                if (type instanceof XSDSimpleType) {
                    attribute.setType((XSDSimpleType) type);
                }
            }
        });
    }
    
    private void scheduleSimpleTypeBaseResolution(final XSDSimpleType type, final String baseName) {
        pendingTypeResolutions.add(new Runnable() {
            @Override
            public void run() {
                XSDType base = resolveType(baseName);
                if (base instanceof XSDSimpleType) {
                    type.setBaseType((XSDSimpleType) base);
                }
            }
        });
    }
    
    private void scheduleComplexTypeBaseResolution(final XSDComplexType type, final String baseName) {
        pendingTypeResolutions.add(new Runnable() {
            @Override
            public void run() {
                XSDType base = resolveType(baseName);
                if (base != null) {
                    type.setBaseType(base);
                }
            }
        });
    }
    
    private XSDType resolveType(String typeName) {
        String localName = XSDUtils.extractLocalName(typeName);
        String prefix = XSDUtils.extractPrefix(typeName);
        
        // Check if it's an XSD built-in type
        if (prefix == null || "xs".equals(prefix) || "xsd".equals(prefix)) {
            XSDSimpleType builtIn = XSDSimpleType.getBuiltInType(localName);
            if (builtIn != null) {
                return builtIn;
            }
        }
        
        // Check schema-defined types
        return schema.getType(localName);
    }
    
}

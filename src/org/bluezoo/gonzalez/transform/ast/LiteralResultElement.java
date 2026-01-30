/*
 * LiteralResultElement.java
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

import org.bluezoo.gonzalez.transform.compiler.AttributeSet;
import org.bluezoo.gonzalez.transform.compiler.AttributeValueTemplate;
import org.bluezoo.gonzalez.transform.compiler.CompiledStylesheet;
import org.bluezoo.gonzalez.transform.compiler.StylesheetCompiler;
import org.bluezoo.gonzalez.transform.runtime.OutputHandler;
import org.bluezoo.gonzalez.transform.runtime.TransformContext;
import org.bluezoo.gonzalez.transform.xpath.expr.XPathException;
import org.xml.sax.SAXException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A literal result element in an XSLT stylesheet.
 *
 * <p>Literal result elements are elements that are not in the XSLT namespace.
 * They are copied to the output with their attributes (after evaluating any
 * attribute value templates).
 *
 * <p>Example stylesheet content:
 * <pre>{@code
 * <html>
 *   <body>
 *     <h1><xsl:value-of select="title"/></h1>
 *   </body>
 * </html>
 * }</pre>
 *
 * <p>In this example, {@code <html>}, {@code <body>}, and {@code <h1>} are
 * literal result elements.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class LiteralResultElement implements XSLTNode {

    private final String namespaceURI;
    private final String localName;
    private final String prefix;
    private final Map<String, AttributeValueTemplate> attributes;
    private final Map<String, String> namespaceDeclarations;  // Explicit declarations to output
    private final Map<String, String> namespaceContext;       // Full namespace bindings for lookups
    private final List<String> useAttributeSets;
    private final SequenceNode content;
    private final StreamingCapability streamingCapability;
    
    // Type annotation (from xsl:type attribute)
    private final String typeNamespaceURI;
    private final String typeLocalName;

    /**
     * Creates a literal result element.
     *
     * @param namespaceURI the namespace URI (may be null)
     * @param localName the local name
     * @param prefix the namespace prefix (may be null)
     * @param attributes map of attribute name to AVT
     * @param namespaceDeclarations explicit namespace declarations to output
     * @param content the element content
     */
    public LiteralResultElement(String namespaceURI, String localName, String prefix,
                                Map<String, AttributeValueTemplate> attributes,
                                Map<String, String> namespaceDeclarations,
                                SequenceNode content) {
        this(namespaceURI, localName, prefix, attributes, namespaceDeclarations, 
             null, null, null, null, content);
    }

    /**
     * Creates a literal result element with attribute sets.
     *
     * @param namespaceURI the namespace URI (may be null)
     * @param localName the local name
     * @param prefix the namespace prefix (may be null)
     * @param attributes map of attribute name to AVT
     * @param namespaceDeclarations explicit namespace declarations to output
     * @param useAttributeSets list of attribute set names to apply
     * @param content the element content
     */
    public LiteralResultElement(String namespaceURI, String localName, String prefix,
                                Map<String, AttributeValueTemplate> attributes,
                                Map<String, String> namespaceDeclarations,
                                List<String> useAttributeSets,
                                SequenceNode content) {
        this(namespaceURI, localName, prefix, attributes, namespaceDeclarations, 
             null, useAttributeSets, null, null, content);
    }

    /**
     * Creates a literal result element with type annotation.
     *
     * @param namespaceURI the namespace URI (may be null)
     * @param localName the local name
     * @param prefix the namespace prefix (may be null)
     * @param attributes map of attribute name to AVT
     * @param namespaceDeclarations explicit namespace declarations to output
     * @param useAttributeSets list of attribute set names to apply
     * @param typeNamespaceURI the type annotation namespace URI (may be null)
     * @param typeLocalName the type annotation local name (may be null)
     * @param content the element content
     */
    public LiteralResultElement(String namespaceURI, String localName, String prefix,
                                Map<String, AttributeValueTemplate> attributes,
                                Map<String, String> namespaceDeclarations,
                                List<String> useAttributeSets,
                                String typeNamespaceURI,
                                String typeLocalName,
                                SequenceNode content) {
        this(namespaceURI, localName, prefix, attributes, namespaceDeclarations, 
             null, useAttributeSets, typeNamespaceURI, typeLocalName, content);
    }

    /**
     * Creates a literal result element with full namespace context.
     *
     * @param namespaceURI the namespace URI (may be null)
     * @param localName the local name
     * @param prefix the namespace prefix (may be null)
     * @param attributes map of attribute name to AVT
     * @param namespaceDeclarations explicit namespace declarations to output
     * @param namespaceContext full namespace bindings for attribute URI lookup
     * @param useAttributeSets list of attribute set names to apply
     * @param content the element content
     */
    public LiteralResultElement(String namespaceURI, String localName, String prefix,
                                Map<String, AttributeValueTemplate> attributes,
                                Map<String, String> namespaceDeclarations,
                                Map<String, String> namespaceContext,
                                List<String> useAttributeSets,
                                SequenceNode content) {
        this(namespaceURI, localName, prefix, attributes, namespaceDeclarations, 
             namespaceContext, useAttributeSets, null, null, content);
    }

    /**
     * Creates a literal result element with all options.
     *
     * @param namespaceURI the namespace URI (may be null)
     * @param localName the local name
     * @param prefix the namespace prefix (may be null)
     * @param attributes map of attribute name to AVT
     * @param namespaceDeclarations explicit namespace declarations to output
     * @param namespaceContext full namespace bindings for attribute URI lookup
     * @param useAttributeSets list of attribute set names to apply
     * @param typeNamespaceURI the type annotation namespace URI (may be null)
     * @param typeLocalName the type annotation local name (may be null)
     * @param content the element content
     */
    public LiteralResultElement(String namespaceURI, String localName, String prefix,
                                Map<String, AttributeValueTemplate> attributes,
                                Map<String, String> namespaceDeclarations,
                                Map<String, String> namespaceContext,
                                List<String> useAttributeSets,
                                String typeNamespaceURI,
                                String typeLocalName,
                                SequenceNode content) {
        this.namespaceURI = namespaceURI != null ? namespaceURI : "";
        this.localName = localName;
        this.prefix = prefix;
        this.attributes = attributes != null ? 
            Collections.unmodifiableMap(new LinkedHashMap<>(attributes)) : 
            Collections.emptyMap();
        this.namespaceDeclarations = namespaceDeclarations != null ?
            Collections.unmodifiableMap(new LinkedHashMap<>(namespaceDeclarations)) :
            Collections.emptyMap();
        // Use namespaceContext if provided, otherwise fall back to namespaceDeclarations
        this.namespaceContext = namespaceContext != null ?
            Collections.unmodifiableMap(new LinkedHashMap<>(namespaceContext)) :
            this.namespaceDeclarations;
        this.useAttributeSets = useAttributeSets != null ?
            Collections.unmodifiableList(new ArrayList<>(useAttributeSets)) :
            Collections.emptyList();
        this.typeNamespaceURI = typeNamespaceURI;
        this.typeLocalName = typeLocalName;
        this.content = content != null ? content : SequenceNode.EMPTY;
        this.streamingCapability = computeStreamingCapability();
    }

    @Override
    public void execute(TransformContext context, OutputHandler output) throws SAXException {
        CompiledStylesheet stylesheet = context.getStylesheet();
        
        // Apply namespace alias to element if needed
        // Alias is looked up by the element's namespace URI
        String outputUri = namespaceURI != null ? namespaceURI : "";
        String outputPrefix = prefix;
        
        CompiledStylesheet.NamespaceAlias elementAlias = stylesheet.getNamespaceAlias(outputUri);
        if (elementAlias != null) {
            outputUri = elementAlias.resultUri;
            outputPrefix = elementAlias.resultPrefix;
        }
        
        // Build qualified name
        String qName;
        if (outputPrefix != null && !outputPrefix.isEmpty()) {
            qName = outputPrefix + ":" + localName;
        } else {
            qName = localName;
        }

        // Start element
        output.startElement(outputUri, localName, qName);

        // Set type annotation if present (from xsl:type)
        if (typeLocalName != null) {
            output.setElementType(typeNamespaceURI, typeLocalName);
        }

        // Output namespace declarations (with aliases applied)
        // Track which result namespace URIs we've declared
        Set<String> declaredResultUris = new HashSet<String>();
        boolean declaredDefaultNs = false;
        
        for (Map.Entry<String, String> ns : namespaceDeclarations.entrySet()) {
            String nsPrefix = ns.getKey();
            String nsUri = ns.getValue();
            
            // For namespace undeclarations (xmlns=""), only output if the element 
            // itself is in no namespace (needs to override parent's default namespace)
            if (nsUri == null || nsUri.isEmpty()) {
                // Only output default namespace undeclaration if this element is in no namespace
                if (nsPrefix.isEmpty() && outputUri.isEmpty()) {
                    output.namespace("", "");
                    declaredDefaultNs = true;
                }
                continue;
            }
            
            // Track if we're declaring a default namespace
            if (nsPrefix.isEmpty()) {
                declaredDefaultNs = true;
            }
            
            // Check if this namespace should be aliased
            CompiledStylesheet.NamespaceAlias nsAlias = stylesheet.getNamespaceAlias(nsUri);
            if (nsAlias != null) {
                // Don't output the stylesheet namespace declaration
                // Instead, we'll output the result namespace if needed
                if (!declaredResultUris.contains(nsAlias.resultUri)) {
                    output.namespace(nsAlias.resultPrefix, nsAlias.resultUri);
                    declaredResultUris.add(nsAlias.resultUri);
                }
            } else {
                // No alias - output as-is
                output.namespace(nsPrefix, nsUri);
            }
        }
        
        // For elements in no namespace, ensure we emit xmlns="" to override any 
        // inherited default namespace. The output handler will skip redundant declarations.
        if (outputUri.isEmpty() && !declaredDefaultNs) {
            output.namespace("", "");
        }
        
        // Ensure the element's result namespace is declared if aliased
        if (elementAlias != null && !declaredResultUris.contains(elementAlias.resultUri)) {
            output.namespace(elementAlias.resultPrefix, elementAlias.resultUri);
        }

        // Apply attribute sets first (can be overridden by explicit attributes)
        if (!useAttributeSets.isEmpty()) {
            for (String setName : useAttributeSets) {
                AttributeSet attrSet = stylesheet.getAttributeSet(setName);
                if (attrSet != null) {
                    attrSet.apply(context, output);
                }
            }
        }

        // Evaluate and output explicit attributes (override attribute set values)
        for (Map.Entry<String, AttributeValueTemplate> attr : attributes.entrySet()) {
            String name = attr.getKey();
            String value;
            try {
                value = attr.getValue().evaluate(context);
            } catch (XPathException e) {
                throw new SAXException("Error evaluating attribute value template: " + e.getMessage(), e);
            }
            
            // Determine attribute namespace and apply alias if needed
            String attrUri = "";
            String attrLocalName = name;
            String attrQName = name;
            
            int colonPos = name.indexOf(':');
            if (colonPos > 0) {
                String attrPrefix = name.substring(0, colonPos);
                attrLocalName = name.substring(colonPos + 1);
                
                // Look up the namespace URI for this prefix (use full context)
                attrUri = namespaceContext.getOrDefault(attrPrefix, "");
                
                // Check for alias
                CompiledStylesheet.NamespaceAlias attrAlias = stylesheet.getNamespaceAlias(attrUri);
                if (attrAlias != null) {
                    attrUri = attrAlias.resultUri;
                    if (attrAlias.resultPrefix != null && !attrAlias.resultPrefix.isEmpty()) {
                        attrQName = attrAlias.resultPrefix + ":" + attrLocalName;
                    } else {
                        attrQName = attrLocalName;
                    }
                }
            }
            
            output.attribute(attrUri, attrLocalName, attrQName, value);
        }

        // Execute content (reset atomic separator since we're starting fresh content)
        StylesheetCompiler.resetAtomicSeparator();
        content.execute(context, output);

        // End element
        output.endElement(outputUri, localName, qName);
    }

    @Override
    public StreamingCapability getStreamingCapability() {
        return streamingCapability;
    }

    private StreamingCapability computeStreamingCapability() {
        // Check AVTs for streaming capability
        StreamingCapability result = content.getStreamingCapability();
        for (AttributeValueTemplate avt : attributes.values()) {
            StreamingCapability avtCap = avt.getStreamingCapability();
            if (avtCap.ordinal() > result.ordinal()) {
                result = avtCap;
            }
        }
        return result;
    }

    /**
     * Returns the namespace URI.
     *
     * @return the namespace URI (may be empty)
     */
    public String getNamespaceURI() {
        return namespaceURI;
    }

    /**
     * Returns the local name.
     *
     * @return the local name
     */
    public String getLocalName() {
        return localName;
    }

    /**
     * Returns the prefix.
     *
     * @return the prefix, or null
     */
    public String getPrefix() {
        return prefix;
    }

    /**
     * Returns the attributes.
     *
     * @return map of attribute name to AVT (immutable)
     */
    public Map<String, AttributeValueTemplate> getAttributes() {
        return attributes;
    }

    /**
     * Returns the content.
     *
     * @return the content sequence
     */
    public SequenceNode getContent() {
        return content;
    }

    /**
     * Returns the type annotation namespace URI.
     *
     * @return the type namespace URI, or null if not annotated
     */
    public String getTypeNamespaceURI() {
        return typeNamespaceURI;
    }

    /**
     * Returns the type annotation local name.
     *
     * @return the type local name, or null if not annotated
     */
    public String getTypeLocalName() {
        return typeLocalName;
    }

    /**
     * Returns true if this element has a type annotation.
     *
     * @return true if type annotated
     */
    public boolean hasTypeAnnotation() {
        return typeLocalName != null;
    }

    @Override
    public String toString() {
        String qName = prefix != null && !prefix.isEmpty() ? prefix + ":" + localName : localName;
        return "LiteralResultElement[" + qName + "]";
    }

}

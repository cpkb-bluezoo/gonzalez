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

import org.bluezoo.gonzalez.transform.compiler.AttributeValueTemplate;
import org.bluezoo.gonzalez.transform.runtime.OutputHandler;
import org.bluezoo.gonzalez.transform.runtime.TransformContext;
import org.bluezoo.gonzalez.transform.xpath.expr.XPathException;
import org.xml.sax.SAXException;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    private final Map<String, String> namespaceDeclarations;
    private final SequenceNode content;
    private final StreamingCapability streamingCapability;

    /**
     * Creates a literal result element.
     *
     * @param namespaceURI the namespace URI (may be null)
     * @param localName the local name
     * @param prefix the namespace prefix (may be null)
     * @param attributes map of attribute name to AVT
     * @param namespaceDeclarations namespace declarations to output
     * @param content the element content
     */
    public LiteralResultElement(String namespaceURI, String localName, String prefix,
                                Map<String, AttributeValueTemplate> attributes,
                                Map<String, String> namespaceDeclarations,
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
        this.content = content != null ? content : SequenceNode.EMPTY;
        this.streamingCapability = computeStreamingCapability();
    }

    @Override
    public void execute(TransformContext context, OutputHandler output) throws SAXException {
        // Build qualified name
        String qName;
        if (prefix != null && !prefix.isEmpty()) {
            qName = prefix + ":" + localName;
        } else {
            qName = localName;
        }

        // Start element
        output.startElement(namespaceURI, localName, qName);

        // Output namespace declarations
        for (Map.Entry<String, String> ns : namespaceDeclarations.entrySet()) {
            output.namespace(ns.getKey(), ns.getValue());
        }

        // Evaluate and output attributes
        for (Map.Entry<String, AttributeValueTemplate> attr : attributes.entrySet()) {
            String name = attr.getKey();
            String value;
            try {
                value = attr.getValue().evaluate(context);
            } catch (XPathException e) {
                throw new SAXException("Error evaluating attribute value template: " + e.getMessage(), e);
            }
            output.attribute("", name, name, value);
        }

        // Execute content
        content.execute(context, output);

        // End element
        output.endElement(namespaceURI, localName, qName);
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

    @Override
    public String toString() {
        String qName = prefix != null && !prefix.isEmpty() ? prefix + ":" + localName : localName;
        return "LiteralResultElement[" + qName + "]";
    }

}

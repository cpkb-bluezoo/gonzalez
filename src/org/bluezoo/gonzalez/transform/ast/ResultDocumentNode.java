/*
 * ResultDocumentNode.java
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
import org.bluezoo.gonzalez.transform.compiler.OutputProperties;
import org.bluezoo.gonzalez.transform.compiler.StylesheetCompiler.ValidationMode;
import org.bluezoo.gonzalez.transform.runtime.OutputHandler;
import org.bluezoo.gonzalez.transform.runtime.TransformContext;
import org.bluezoo.gonzalez.transform.runtime.ResultDocumentHandler;
import org.bluezoo.gonzalez.transform.xpath.expr.XPathException;
import org.xml.sax.SAXException;

import java.io.OutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URI;

/**
 * XSLT 2.0 xsl:result-document instruction.
 *
 * <p>The xsl:result-document instruction creates a secondary output document.
 * This enables generating multiple output files from a single transformation.
 *
 * <p>Example:
 * <pre>
 * &lt;xsl:result-document href="chapter{position()}.html"&gt;
 *   &lt;html&gt;
 *     &lt;head&gt;&lt;title&gt;Chapter &lt;xsl:value-of select="position()"/&gt;&lt;/title&gt;&lt;/head&gt;
 *     &lt;body&gt;
 *       &lt;xsl:apply-templates/&gt;
 *     &lt;/body&gt;
 *   &lt;/html&gt;
 * &lt;/xsl:result-document&gt;
 * </pre>
 *
 * <p>Attributes:
 * <ul>
 *   <li><b>href</b> - URI of the output document (AVT)</li>
 *   <li><b>format</b> - Named output format (references xsl:output)</li>
 *   <li><b>method</b> - Output method (xml, html, text)</li>
 *   <li><b>encoding</b> - Character encoding</li>
 *   <li><b>indent</b> - Whether to indent output</li>
 * </ul>
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class ResultDocumentNode implements XSLTNode {

    private final AttributeValueTemplate hrefAvt;
    private final String format;
    private final String method;
    private final String encoding;
    private final String indent;
    private final XSLTNode content;
    private final String typeNamespaceURI;
    private final String typeLocalName;
    private final ValidationMode validation;

    /**
     * Creates a new result-document instruction.
     *
     * @param hrefAvt the href AVT (may be null for principal output)
     * @param format the named format (may be null)
     * @param method the output method (may be null)
     * @param encoding the encoding (may be null)
     * @param indent indent setting (may be null)
     * @param content the content to output
     */
    public ResultDocumentNode(AttributeValueTemplate hrefAvt, String format,
                               String method, String encoding, String indent,
                               XSLTNode content) {
        this(hrefAvt, format, method, encoding, indent, content, null, null, null);
    }

    /**
     * Creates a new result-document instruction with validation.
     *
     * @param hrefAvt the href AVT (may be null for principal output)
     * @param format the named format (may be null)
     * @param method the output method (may be null)
     * @param encoding the encoding (may be null)
     * @param indent indent setting (may be null)
     * @param content the content to output
     * @param typeNamespaceURI the type annotation namespace (may be null)
     * @param typeLocalName the type annotation local name (may be null)
     * @param validation the validation mode (may be null for default)
     */
    public ResultDocumentNode(AttributeValueTemplate hrefAvt, String format,
                               String method, String encoding, String indent,
                               XSLTNode content, String typeNamespaceURI,
                               String typeLocalName, ValidationMode validation) {
        this.hrefAvt = hrefAvt;
        this.format = format;
        this.method = method;
        this.encoding = encoding;
        this.indent = indent;
        this.content = content;
        this.typeNamespaceURI = typeNamespaceURI;
        this.typeLocalName = typeLocalName;
        this.validation = validation;
    }

    @Override
    public void execute(TransformContext context, OutputHandler output) throws SAXException {
        // Evaluate href AVT to get output URI
        String href = null;
        if (hrefAvt != null) {
            try {
                href = hrefAvt.evaluate(context);
            } catch (XPathException e) {
                throw new SAXException("Error evaluating href AVT: " + e.getMessage(), e);
            }
        }

        // Determine output properties
        OutputProperties props = determineOutputProperties(context);

        // Create output handler for secondary document
        OutputHandler secondaryOutput;
        OutputStream outputStream = null;
        boolean usingPrincipalOutput = false;
        
        try {
            if (href != null && !href.isEmpty()) {
                // Create file output for secondary document
                URI uri = resolveHref(href, context);
                outputStream = new FileOutputStream(uri.getPath());
                secondaryOutput = new ResultDocumentHandler(outputStream, props);
            } else {
                // No href - use principal output (don't start new document)
                secondaryOutput = output;
                usingPrincipalOutput = true;
            }

            // Only start a new document for secondary outputs
            if (!usingPrincipalOutput) {
                secondaryOutput.startDocument();
            }

            // Execute content
            if (content != null) {
                content.execute(context, secondaryOutput);
            }

            // Only end document for secondary outputs
            if (!usingPrincipalOutput) {
                secondaryOutput.endDocument();
            }

        } catch (IOException e) {
            throw new SAXException("Error creating result document: " + href, e);
        } finally {
            // Close output stream if we created one
            if (outputStream != null) {
                try {
                    outputStream.close();
                } catch (IOException e) {
                    // Ignore close errors
                }
            }
        }
    }

    /**
     * Resolves the href relative to the base URI.
     */
    private URI resolveHref(String href, TransformContext context) {
        // For now, treat as absolute URI
        // TODO: Resolve relative to output base URI
        return URI.create(href);
    }

    /**
     * Determines output properties from format reference or inline attributes.
     */
    private OutputProperties determineOutputProperties(TransformContext context) {
        OutputProperties props = new OutputProperties();

        // Start with default properties from stylesheet
        OutputProperties stylesheetProps = context.getStylesheet().getOutputProperties();
        if (stylesheetProps != null) {
            props.merge(stylesheetProps);
        }

        // Override with inline attributes
        if (method != null) {
            props.setMethod(method);
        }
        if (encoding != null) {
            props.setEncoding(encoding);
        }
        if (indent != null) {
            props.setIndent("yes".equals(indent));
        }

        return props;
    }

    /**
     * Returns the href AVT.
     */
    public AttributeValueTemplate getHrefAvt() {
        return hrefAvt;
    }

    /**
     * Returns the format name.
     */
    public String getFormat() {
        return format;
    }

    /**
     * Returns the content.
     */
    public XSLTNode getContent() {
        return content;
    }

    @Override
    public StreamingCapability getStreamingCapability() {
        // Result document content may or may not be streamable
        if (content != null) {
            return content.getStreamingCapability();
        }
        return StreamingCapability.FULL;
    }

    @Override
    public String toString() {
        return "ResultDocumentNode[href=" + hrefAvt + "]";
    }

}

/*
 * SourceDocumentNode.java
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

import org.bluezoo.gonzalez.Parser;
import org.bluezoo.gonzalez.transform.compiler.AttributeValueTemplate;
import org.bluezoo.gonzalez.transform.compiler.CompiledStylesheet;
import org.bluezoo.gonzalez.transform.runtime.AccumulatorManager;
import org.bluezoo.gonzalez.transform.runtime.BasicTransformContext;
import org.bluezoo.gonzalez.transform.runtime.DocumentLoader;
import org.bluezoo.gonzalez.transform.runtime.OutputHandler;
import org.bluezoo.gonzalez.transform.runtime.StreamingContext;
import org.bluezoo.gonzalez.transform.runtime.StreamingTransformHandler;
import org.bluezoo.gonzalez.transform.runtime.TransformContext;
import org.bluezoo.gonzalez.transform.xpath.expr.XPathException;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNode;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.List;

/**
 * XSLT 3.0 xsl:source-document instruction.
 *
 * <p>The xsl:source-document instruction provides access to a secondary input document,
 * supporting both streaming and non-streaming modes. It is similar to the document()
 * function but with additional capabilities.
 *
 * <h3>Streaming-First Philosophy</h3>
 * <p>Gonzalez defaults to <b>streaming mode</b> for xsl:source-document, which:
 * <ul>
 *   <li>Processes documents with minimal memory overhead</li>
 *   <li>Handles arbitrarily large documents efficiently</li>
 *   <li>Aligns with Gonzalez's streaming-first architecture</li>
 * </ul>
 *
 * <p>To use non-streaming mode (builds in-memory tree), explicitly set streamable="no".
 * Non-streaming mode is required when your XSLT code needs:
 * <ul>
 *   <li>Random access navigation (parent::, preceding::, preceding-sibling:: axes)</li>
 *   <li>Multiple passes over the same data</li>
 *   <li>Position-dependent predicates like [position() &lt; last()]</li>
 * </ul>
 *
 * <h3>Example usage</h3>
 * <pre>
 * &lt;!-- Streaming (default) - memory efficient --&gt;
 * &lt;xsl:source-document href="large-file.xml"&gt;
 *   &lt;xsl:apply-templates select="//item"/&gt;
 * &lt;/xsl:source-document&gt;
 *
 * &lt;!-- Non-streaming - full tree access --&gt;
 * &lt;xsl:source-document href="data.xml" streamable="no"&gt;
 *   &lt;xsl:copy-of select="//item[position() &lt; ../count]"/&gt;
 * &lt;/xsl:source-document&gt;
 * </pre>
 *
 * <p>Within xsl:source-document:
 * <ul>
 *   <li>The context item is the document node of the loaded document</li>
 *   <li>position() returns 1, last() returns 1</li>
 *   <li>In streaming mode, only streamable constructs may be used</li>
 * </ul>
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 * @see StreamNode
 * @see DocumentLoader
 */
public final class SourceDocumentNode implements XSLTNode {

    private final AttributeValueTemplate hrefAvt;
    private final boolean streamable;
    private final String validation;  // "strict", "lax", "preserve", "strip", or null
    private final String useAccumulators;
    private final XSLTNode body;

    /**
     * Creates a new source-document instruction.
     *
     * @param hrefAvt the URI expression (as AVT, evaluated at runtime)
     * @param streamable whether to use streaming mode
     * @param validation the validation mode string
     * @param useAccumulators which accumulators to apply
     * @param body the body to execute
     */
    public SourceDocumentNode(AttributeValueTemplate hrefAvt, boolean streamable,
                              String validation, String useAccumulators, XSLTNode body) {
        this.hrefAvt = hrefAvt;
        this.streamable = streamable;
        this.validation = validation;
        this.useAccumulators = useAccumulators;
        this.body = body;
    }

    @Override
    public void execute(TransformContext context, OutputHandler output) throws SAXException {
        // Evaluate the AVT to get the actual href
        String href;
        try {
            href = hrefAvt.evaluate(context);
        } catch (XPathException e) {
            throw new SAXException("Error evaluating href AVT: " + e.getMessage(), e);
        }
        
        if (href == null || href.isEmpty()) {
            throw new SAXException("FODC0002: Empty href in xsl:source-document");
        }

        // Resolve href relative to static base URI
        String resolvedHref = resolveHref(href, context);

        if (streamable) {
            executeStreaming(resolvedHref, context, output);
        } else {
            executeNonStreaming(resolvedHref, context, output);
        }
    }

    /**
     * Executes in streaming mode - document is processed as it is parsed.
     */
    private void executeStreaming(String href, TransformContext context, OutputHandler output) 
            throws SAXException {
        if (body == null) {
            return; // Empty body - nothing to do
        }

        try {
            // Create streaming context
            StreamingContext streamCtx = new StreamingContext(
                context.getStylesheet(),
                context.getVariableScope(),
                context
            );

            // Wire accumulators for streaming
            if (context.getStylesheet() != null
                    && !context.getStylesheet().getAccumulators().isEmpty()) {
                AccumulatorManager mgr = new AccumulatorManager(
                    context.getStylesheet(), context);
                streamCtx.setAccumulatorManager(mgr);
            }

            // Create streaming handler that executes the body
            StreamingTransformHandler handler = new StreamingTransformHandler(
                streamCtx, body, output
            );

            // Parse the external document in streaming mode
            Parser parser = new Parser();
            parser.setContentHandler(handler);
            
            URL url = new URL(href);
            try (InputStream in = url.openStream()) {
                InputSource inputSource = new InputSource(in);
                inputSource.setSystemId(href);
                parser.parse(inputSource);
            }

        } catch (IOException e) {
            throw new SAXException("FODC0002: Error reading document: " + href, e);
        }
    }

    /**
     * Executes in non-streaming mode - document is fully loaded into memory.
     */
    private void executeNonStreaming(String href, TransformContext context, OutputHandler output) 
            throws SAXException {
        // Get strip-space/preserve-space rules from stylesheet
        List<String> stripSpace = null;
        List<String> preserveSpace = null;
        CompiledStylesheet stylesheet = context.getStylesheet();
        if (stylesheet != null) {
            stripSpace = stylesheet.getStripSpaceElements();
            preserveSpace = stylesheet.getPreserveSpaceElements();
        }
        
        // Load the document using the shared DocumentLoader
        XPathNode documentNode = DocumentLoader.loadDocumentOrThrow(href, null, stripSpace, preserveSpace);

        // Execute the body with the document as context
        if (body != null) {
            // Create new context with document node, position=1, size=1
            TransformContext docContext;
            if (context instanceof BasicTransformContext) {
                docContext = ((BasicTransformContext) context)
                    .withContextNode(documentNode)
                    .withPositionAndSize(1, 1);
            } else {
                docContext = context.withContextNode(documentNode)
                    .withPositionAndSize(1, 1);
            }
            
            body.execute(docContext, output);
        }
    }

    /**
     * Resolves the href relative to the static base URI.
     */
    private String resolveHref(String href, TransformContext context) throws SAXException {
        // Get the static base URI from the context
        String baseUri = context.getStaticBaseURI();
        if (baseUri == null) {
            CompiledStylesheet stylesheet = context.getStylesheet();
            if (stylesheet != null) {
                baseUri = stylesheet.getBaseURI();
            }
        }
        
        return DocumentLoader.resolveUri(href, baseUri);
    }

    @Override
    public StreamingCapability getStreamingCapability() {
        // The instruction itself is streamable
        return streamable ? StreamingCapability.FULL : StreamingCapability.NONE;
    }

    @Override
    public String toString() {
        return "SourceDocumentNode[href=" + hrefAvt + ", streamable=" + streamable + "]";
    }
}

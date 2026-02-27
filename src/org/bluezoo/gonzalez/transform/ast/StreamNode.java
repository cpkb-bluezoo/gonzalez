/*
 * StreamNode.java
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
import org.bluezoo.gonzalez.transform.runtime.AccumulatorManager;
import org.bluezoo.gonzalez.transform.runtime.OutputHandler;
import org.bluezoo.gonzalez.transform.runtime.StreamingContext;
import org.bluezoo.gonzalez.transform.runtime.StreamingTransformHandler;
import org.bluezoo.gonzalez.transform.runtime.TransformContext;
import org.bluezoo.gonzalez.transform.runtime.BasicTransformContext;
import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;

/**
 * XSLT 3.0 xsl:stream instruction.
 *
 * <p>The xsl:stream instruction is the explicit streaming entry point in XSLT 3.0.
 * It reads an external document and processes it in streaming mode, ensuring that
 * only streamable constructs are used within its body.
 *
 * <p>Example usage:
 * <pre>
 * &lt;xsl:stream href="large-file.xml"&gt;
 *   &lt;xsl:apply-templates mode="streaming"/&gt;
 * &lt;/xsl:stream&gt;
 * </pre>
 *
 * <p>Within xsl:stream:
 * <ul>
 *   <li>The context item is the document node of the streamed document</li>
 *   <li>Only streamable constructs may be used</li>
 *   <li>Accumulators can be used for state management</li>
 *   <li>The body is executed as the document is parsed</li>
 * </ul>
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class StreamNode implements XSLTNode {

    private final String href;
    private final XSLTNode body;

    /**
     * Creates a new stream instruction.
     *
     * @param href the URI of the document to stream
     * @param body the body to execute during streaming
     */
    public StreamNode(String href, XSLTNode body) {
        this.href = href;
        this.body = body;
    }

    /**
     * Returns the href attribute.
     *
     * @return the document URI
     */
    public String getHref() {
        return href;
    }

    /**
     * Returns the body content.
     *
     * @return the body node
     */
    public XSLTNode getBody() {
        return body;
    }

    @Override
    public void execute(TransformContext context, OutputHandler output) throws SAXException {
        if (href == null || href.isEmpty()) {
            throw new SAXException("xsl:stream requires href attribute");
        }

        // Resolve href relative to stylesheet base URI
        String resolvedHref = resolveHref(href, context);

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
            
            InputSource inputSource = createInputSource(resolvedHref);
            parser.parse(inputSource);

        } catch (IOException e) {
            throw new SAXException("Error streaming document: " + resolvedHref, e);
        }
    }

    /**
     * Resolves the href relative to the current context.
     */
    private String resolveHref(String href, TransformContext context) {
        // For now, return the href as-is
        // TODO: Resolve relative to stylesheet base URI
        return href;
    }

    /**
     * Creates an InputSource for the given URI.
     */
    private InputSource createInputSource(String uri) throws IOException {
        URL url = new URL(uri);
        InputStream is = url.openStream();
        InputSource source = new InputSource(is);
        source.setSystemId(uri);
        return source;
    }

    @Override
    public StreamingCapability getStreamingCapability() {
        // xsl:stream itself is fully streamable
        // The body must also be streamable, which is validated at compile time
        return StreamingCapability.FULL;
    }

    @Override
    public String toString() {
        return "StreamNode[href=" + href + "]";
    }

}

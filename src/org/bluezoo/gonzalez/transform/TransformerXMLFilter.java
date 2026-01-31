/*
 * TransformerXMLFilter.java
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

package org.bluezoo.gonzalez.transform;

import java.io.IOException;

import org.xml.sax.*;
import org.xml.sax.helpers.XMLFilterImpl;

import javax.xml.transform.sax.SAXResult;
import javax.xml.transform.sax.TransformerHandler;

/**
 * XMLFilter that performs XSLT transformation.
 *
 * <p>This filter can be chained with other SAX components to build
 * transformation pipelines.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public class TransformerXMLFilter extends XMLFilterImpl {

    /** The transformer handler that performs the transformation. */
    private final TransformerHandler handler;

    /**
     * Creates a filter wrapping a transformer handler.
     *
     * @param handler the transformer handler
     */
    public TransformerXMLFilter(TransformerHandler handler) {
        this.handler = handler;
    }

    /**
     * Sets the content handler that receives the transformation output.
     *
     * <p>This also configures the transformer handler to output to the
     * specified content handler.
     *
     * @param handler the content handler to receive transformation output
     */
    @Override
    public void setContentHandler(ContentHandler handler) {
        super.setContentHandler(handler);
        // Set the transformer's result to output to our content handler
        this.handler.setResult(new SAXResult(handler));
    }

    /**
     * Parses an input source through the transformation pipeline.
     *
     * @param input the input source to parse
     * @throws SAXException if a SAX parsing or transformation error occurs
     * @throws IOException if an I/O error occurs
     */
    @Override
    public void parse(InputSource input) throws SAXException, IOException {
        // Set up the transformation pipeline
        XMLReader parent = getParent();
        if (parent != null) {
            parent.setContentHandler(handler);
            parent.parse(input);
        }
    }

    /**
     * Parses an input source identified by system ID through the transformation pipeline.
     *
     * @param systemId the system identifier (URI) of the input source
     * @throws SAXException if a SAX parsing or transformation error occurs
     * @throws IOException if an I/O error occurs
     */
    @Override
    public void parse(String systemId) throws SAXException, IOException {
        parse(new InputSource(systemId));
    }

}

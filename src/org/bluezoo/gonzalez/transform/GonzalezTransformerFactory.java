/*
 * GonzalezTransformerFactory.java
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
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;

import javax.xml.transform.ErrorListener;
import javax.xml.transform.Source;
import javax.xml.transform.Templates;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.URIResolver;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.sax.SAXSource;
import javax.xml.transform.sax.SAXTransformerFactory;
import javax.xml.transform.sax.TemplatesHandler;
import javax.xml.transform.sax.TransformerHandler;
import javax.xml.transform.stream.StreamSource;

import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.XMLReaderFactory;

import org.bluezoo.gonzalez.transform.compiler.CompiledStylesheet;
import org.bluezoo.gonzalez.transform.compiler.StylesheetCompiler;
import org.bluezoo.gonzalez.transform.compiler.StylesheetResolver;

/**
 * JAXP TransformerFactory implementation for Gonzalez XSLT.
 *
 * <p>This factory creates Transformer and Templates objects from XSLT
 * stylesheet sources. It supports:
 * <ul>
 *   <li>StreamSource, SAXSource for stylesheet input</li>
 *   <li>SAX-based transformation pipeline</li>
 *   <li>TemplatesHandler for receiving stylesheet as SAX events</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>
 * TransformerFactory factory = new GonzalezTransformerFactory();
 * Transformer transformer = factory.newTransformer(stylesheetSource);
 * transformer.transform(inputSource, resultTarget);
 * </pre>
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public class GonzalezTransformerFactory extends SAXTransformerFactory {

    private final Map<String, Object> attributes = new HashMap<>();
    private URIResolver uriResolver;
    private ErrorListener errorListener;

    /**
     * Creates a new transformer factory.
     */
    public GonzalezTransformerFactory() {
    }

    @Override
    public Transformer newTransformer(Source source) throws TransformerConfigurationException {
        if (source == null) {
            // Identity transform
            return new GonzalezTransformer(null);
        }
        
        Templates templates = newTemplates(source);
        return templates.newTransformer();
    }

    @Override
    public Transformer newTransformer() throws TransformerConfigurationException {
        // Identity transform
        return new GonzalezTransformer(null);
    }

    @Override
    public Templates newTemplates(Source source) throws TransformerConfigurationException {
        try {
            CompiledStylesheet stylesheet = compileStylesheet(source);
            return new GonzalezTemplates(stylesheet);
        } catch (SAXException | IOException e) {
            throw new TransformerConfigurationException("Failed to compile stylesheet", e);
        }
    }

    private CompiledStylesheet compileStylesheet(Source source) throws SAXException, IOException {
        // Get the base URI from the source
        String baseUri = source.getSystemId();
        
        // Create resolver for imports/includes
        StylesheetResolver resolver = new StylesheetResolver(uriResolver);
        
        // Create compiler with resolver and base URI
        StylesheetCompiler compiler = new StylesheetCompiler(resolver, baseUri);
        
        if (source instanceof StreamSource) {
            StreamSource ss = (StreamSource) source;
            XMLReader reader = createXMLReader();
            reader.setContentHandler(compiler);
            
            InputSource inputSource = new InputSource();
            if (ss.getInputStream() != null) {
                inputSource.setByteStream(ss.getInputStream());
            } else if (ss.getSystemId() != null) {
                // Open stream from systemId
                InputStream byteStream = openStream(ss.getSystemId());
                inputSource.setByteStream(byteStream);
            } else if (ss.getReader() != null) {
                // Gonzalez parser requires byte streams for encoding detection
                // TODO: Consider adding Reader support via ExternalEntityDecoder
                throw new SAXException("StreamSource with Reader not supported - use InputStream or SystemId");
            } else {
                throw new SAXException("StreamSource has no InputStream or SystemId");
            }
            inputSource.setSystemId(ss.getSystemId());
            reader.parse(inputSource);
            
        } else if (source instanceof SAXSource) {
            SAXSource saxSource = (SAXSource) source;
            XMLReader reader = saxSource.getXMLReader();
            if (reader == null) {
                reader = createXMLReader();
            }
            reader.setContentHandler(compiler);
            
            InputSource inputSource = saxSource.getInputSource();
            if (inputSource == null) {
                inputSource = new InputSource();
            }
            if (inputSource.getSystemId() == null && baseUri != null) {
                inputSource.setSystemId(baseUri);
            }
            reader.parse(inputSource);
            
        } else if (source instanceof DOMSource) {
            throw new SAXException("DOMSource not yet supported - use StreamSource or SAXSource");
            
        } else {
            throw new SAXException("Unsupported source type: " + source.getClass().getName());
        }
        
        try {
            return compiler.getCompiledStylesheet();
        } catch (TransformerConfigurationException e) {
            throw new SAXException(e.getMessage(), e);
        }
    }

    /**
     * Opens a ReadableByteChannel from a URI string.
     *
     * <p>For file: URLs, this opens a FileChannel directly for optimal
     * performance. For other protocols, this wraps the URL's input stream.
     *
     * @param uri the URI to open
     * @return a ReadableByteChannel for the resource
     * @throws IOException if the resource cannot be opened
     */
    private ReadableByteChannel openChannel(String uri) throws IOException {
        if (uri.startsWith("file:")) {
            // Use FileChannel for file: URLs (most efficient)
            try {
                Path path = Paths.get(URI.create(uri));
                return FileChannel.open(path, StandardOpenOption.READ);
            } catch (Exception e) {
                // Fall back to URL handling for unusual file: URL formats
            }
        }
        // For other protocols, wrap URL stream in a channel
        URL url = new URL(uri);
        return Channels.newChannel(url.openStream());
    }

    /**
     * Opens an InputStream from a URI string, backed by NIO channels.
     *
     * <p>This method provides an InputStream interface while using FileChannel
     * internally for file: URLs, enabling efficient I/O.
     *
     * @param uri the URI to open
     * @return an InputStream for the resource
     * @throws IOException if the resource cannot be opened
     */
    private InputStream openStream(String uri) throws IOException {
        return Channels.newInputStream(openChannel(uri));
    }

    private XMLReader createXMLReader() throws SAXException {
        XMLReader reader;
        try {
            // Try to use Gonzalez parser if available
            Class<?> parserClass = Class.forName("org.bluezoo.gonzalez.Parser");
            reader = (XMLReader) parserClass.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            // Fall back to default
            reader = XMLReaderFactory.createXMLReader();
        }
        
        // Enable namespace processing - required for XSLT compilation
        reader.setFeature("http://xml.org/sax/features/namespaces", true);
        reader.setFeature("http://xml.org/sax/features/namespace-prefixes", false);
        
        return reader;
    }

    @Override
    public Source getAssociatedStylesheet(Source source, String media, String title, String charset) 
            throws TransformerConfigurationException {
        // TODO: Implement xml-stylesheet PI processing
        return null;
    }

    @Override
    public void setURIResolver(URIResolver resolver) {
        this.uriResolver = resolver;
    }

    @Override
    public URIResolver getURIResolver() {
        return uriResolver;
    }

    @Override
    public void setFeature(String name, boolean value) throws TransformerConfigurationException {
        attributes.put(name, value);
    }

    @Override
    public boolean getFeature(String name) {
        Object value = attributes.get(name);
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        
        // Standard features
        if (SAXTransformerFactory.FEATURE.equals(name)) {
            return true; // We support SAXTransformerFactory
        }
        if (SAXTransformerFactory.FEATURE_XMLFILTER.equals(name)) {
            return true; // We support XMLFilter
        }
        
        return false;
    }

    @Override
    public void setAttribute(String name, Object value) throws IllegalArgumentException {
        attributes.put(name, value);
    }

    @Override
    public Object getAttribute(String name) throws IllegalArgumentException {
        return attributes.get(name);
    }

    @Override
    public void setErrorListener(ErrorListener listener) throws IllegalArgumentException {
        this.errorListener = listener;
    }

    @Override
    public ErrorListener getErrorListener() {
        return errorListener;
    }

    // SAXTransformerFactory methods

    @Override
    public TransformerHandler newTransformerHandler(Source src) throws TransformerConfigurationException {
        Templates templates = newTemplates(src);
        return newTransformerHandler(templates);
    }

    @Override
    public TransformerHandler newTransformerHandler(Templates templates) throws TransformerConfigurationException {
        if (!(templates instanceof GonzalezTemplates)) {
            throw new TransformerConfigurationException("Templates not from this factory");
        }
        return new GonzalezTransformerHandler((GonzalezTemplates) templates);
    }

    @Override
    public TransformerHandler newTransformerHandler() throws TransformerConfigurationException {
        return new GonzalezTransformerHandler(null);
    }

    @Override
    public TemplatesHandler newTemplatesHandler() throws TransformerConfigurationException {
        return new GonzalezTemplatesHandler(this);
    }

    @Override
    public org.xml.sax.XMLFilter newXMLFilter(Source src) throws TransformerConfigurationException {
        Templates templates = newTemplates(src);
        return newXMLFilter(templates);
    }

    @Override
    public org.xml.sax.XMLFilter newXMLFilter(Templates templates) throws TransformerConfigurationException {
        TransformerHandler handler = newTransformerHandler(templates);
        return new TransformerXMLFilter(handler);
    }

}

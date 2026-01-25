/*
 * GonzalezTransformer.java
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

import org.bluezoo.gonzalez.transform.compiler.CompiledStylesheet;
import org.bluezoo.gonzalez.transform.compiler.OutputProperties;
import org.bluezoo.gonzalez.transform.runtime.HTMLOutputHandler;
import org.bluezoo.gonzalez.transform.runtime.SAXOutputHandler;
import org.bluezoo.gonzalez.transform.runtime.TextOutputHandler;
import org.bluezoo.gonzalez.transform.runtime.XMLWriterOutputHandler;
import org.xml.sax.*;
import org.xml.sax.helpers.XMLReaderFactory;

import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.sax.SAXResult;
import javax.xml.transform.sax.SAXSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;
import java.io.*;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * JAXP Transformer implementation for Gonzalez XSLT.
 *
 * <p>This transformer processes XML input through an XSLT stylesheet to
 * produce output. It supports:
 * <ul>
 *   <li>StreamSource, SAXSource, DOMSource for input</li>
 *   <li>StreamResult, SAXResult for output</li>
 *   <li>Parameters and output properties</li>
 * </ul>
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public class GonzalezTransformer extends Transformer {

    private final CompiledStylesheet stylesheet;
    private final Map<String, Object> parameters = new HashMap<>();
    private Properties outputProperties;
    private URIResolver uriResolver;
    private ErrorListener errorListener;

    /**
     * Creates a transformer with a stylesheet (or null for identity transform).
     *
     * @param stylesheet the compiled stylesheet, or null
     */
    public GonzalezTransformer(CompiledStylesheet stylesheet) {
        this.stylesheet = stylesheet;
        this.outputProperties = new Properties();
        
        if (stylesheet != null) {
            // Copy output properties from stylesheet
            OutputProperties op = stylesheet.getOutputProperties();
            outputProperties.setProperty("method", op.getMethod().name().toLowerCase());
            outputProperties.setProperty("encoding", op.getEncoding());
            outputProperties.setProperty("indent", op.isIndent() ? "yes" : "no");
        }
    }

    @Override
    public void transform(Source xmlSource, Result outputTarget) throws TransformerException {
        try {
            if (stylesheet == null) {
                // Identity transform
                performIdentityTransform(xmlSource, outputTarget);
            } else {
                // XSLT transform
                performTransform(xmlSource, outputTarget);
            }
        } catch (SAXException | IOException e) {
            throw new TransformerException(e);
        }
    }

    private void performIdentityTransform(Source source, Result result) 
            throws SAXException, IOException, TransformerException {
        // Use SAX to copy input to output
        XMLReader reader = getXMLReader(source);
        ContentHandler handler = getOutputHandler(result);
        
        reader.setContentHandler(handler);
        
        InputSource inputSource = getInputSource(source);
        reader.parse(inputSource);
    }

    private void performTransform(Source source, Result result) 
            throws SAXException, IOException, TransformerException {
        // Create the transformation handler
        ContentHandler outputHandler = getOutputHandler(result);
        
        // Create the transformation pipeline
        GonzalezTransformHandler transformHandler = 
            new GonzalezTransformHandler(stylesheet, parameters, outputHandler, errorListener);
        
        // Parse input through the transform
        XMLReader reader = getXMLReader(source);
        reader.setContentHandler(transformHandler);
        
        // Set up LexicalHandler to receive comment events
        try {
            reader.setProperty("http://xml.org/sax/properties/lexical-handler", transformHandler);
        } catch (SAXException e) {
            // LexicalHandler not supported - comments won't be available
        }
        
        InputSource inputSource = getInputSource(source);
        reader.parse(inputSource);
    }

    private XMLReader getXMLReader(Source source) throws SAXException {
        if (source instanceof SAXSource) {
            SAXSource ss = (SAXSource) source;
            if (ss.getXMLReader() != null) {
                return ss.getXMLReader();
            }
        }
        
        try {
            // Try Gonzalez parser
            Class<?> parserClass = Class.forName("org.bluezoo.gonzalez.Parser");
            return (XMLReader) parserClass.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            return XMLReaderFactory.createXMLReader();
        }
    }

    private InputSource getInputSource(Source source) throws TransformerException {
        if (source instanceof StreamSource) {
            StreamSource ss = (StreamSource) source;
            InputSource is = new InputSource();
            
            if (ss.getInputStream() != null) {
                is.setByteStream(ss.getInputStream());
            } else if (ss.getSystemId() != null) {
                // Open stream from systemId
                try {
                    is.setByteStream(openStream(ss.getSystemId()));
                } catch (IOException e) {
                    throw new TransformerException("Failed to open: " + ss.getSystemId(), e);
                }
            } else if (ss.getReader() != null) {
                // Gonzalez parser requires byte streams for encoding detection
                // TODO: Consider adding Reader support via ExternalEntityDecoder
                throw new TransformerException("StreamSource with Reader not supported - use InputStream or SystemId");
            } else {
                throw new TransformerException("StreamSource has no InputStream or SystemId");
            }
            is.setSystemId(ss.getSystemId());
            return is;
        }
        
        if (source instanceof SAXSource) {
            SAXSource saxSource = (SAXSource) source;
            InputSource is = saxSource.getInputSource();
            // Verify byte stream is available for Gonzalez parser
            if (is != null && is.getByteStream() == null) {
                if (is.getCharacterStream() != null) {
                    // TODO: Consider adding Reader support via ExternalEntityDecoder
                    throw new TransformerException("InputSource with Reader not supported - use byte stream");
                }
                // Try to open from systemId
                if (is.getSystemId() != null) {
                    try {
                        is.setByteStream(openStream(is.getSystemId()));
                    } catch (IOException e) {
                        throw new TransformerException("Failed to open: " + is.getSystemId(), e);
                    }
                }
            }
            return is;
        }
        
        if (source instanceof DOMSource) {
            throw new TransformerException("DOMSource not yet supported");
        }
        
        throw new TransformerException("Unsupported source: " + source.getClass());
    }

    /**
     * Opens an input stream from a URI string.
     */
    private InputStream openStream(String uri) throws IOException {
        if (uri.startsWith("file:")) {
            String path = uri.substring(5);
            while (path.startsWith("//")) {
                path = path.substring(1);
            }
            if (path.length() > 2 && path.charAt(0) == '/' && path.charAt(2) == ':') {
                path = path.substring(1);
            }
            return new FileInputStream(path);
        } else {
            java.net.URL url = new java.net.URL(uri);
            return url.openStream();
        }
    }

    private ContentHandler getOutputHandler(Result result) throws TransformerException, IOException {
        if (result instanceof SAXResult) {
            // SAX result: forward events directly to the ContentHandler
            return ((SAXResult) result).getHandler();
        }
        
        if (result instanceof StreamResult) {
            StreamResult sr = (StreamResult) result;
            String method = outputProperties.getProperty("method", "xml").toLowerCase();
            String encoding = outputProperties.getProperty("encoding", "UTF-8");
            boolean indent = "yes".equals(outputProperties.getProperty("indent"));
            
            // Get output target
            OutputStream outputStream = null;
            Writer writer = null;
            
            if (sr.getWriter() != null) {
                writer = sr.getWriter();
            } else if (sr.getOutputStream() != null) {
                outputStream = sr.getOutputStream();
            } else if (sr.getSystemId() != null) {
                String systemId = sr.getSystemId();
                File file = new File(systemId.startsWith("file:") ? 
                    systemId.substring(5) : systemId);
                outputStream = new FileOutputStream(file);
            } else {
                throw new TransformerException("StreamResult has no output target");
            }
            
            // Select handler based on output method
            switch (method) {
                case "text":
                    // Text output: only text content, no markup
                    if (writer != null) {
                        return new TextOutputHandler(writer);
                    } else {
                        return new TextOutputHandler(outputStream, encoding);
                    }
                    
                case "html":
                    // HTML output: HTML-specific serialization rules
                    if (writer != null) {
                        return new HTMLOutputHandler(writer, encoding, indent);
                    } else {
                        return new HTMLOutputHandler(outputStream, encoding, indent);
                    }
                    
                case "xml":
                case "xhtml":
                default:
                    // XML/XHTML output: use XMLWriter for streams, SAXOutputHandler for writers
                    if (outputStream != null) {
                        // Use XMLWriter for optimal XML serialization
                        OutputProperties props = stylesheet != null ? 
                            stylesheet.getOutputProperties() : new OutputProperties();
                        return new XMLWriterOutputHandler(outputStream, props);
                    } else {
                        // Writer provided - use SAXOutputHandler
                        return new SAXOutputHandler(writer, outputProperties);
                    }
            }
        }
        
        throw new TransformerException("Unsupported result: " + result.getClass());
    }

    @Override
    public void setParameter(String name, Object value) {
        parameters.put(name, value);
    }

    @Override
    public Object getParameter(String name) {
        return parameters.get(name);
    }

    @Override
    public void clearParameters() {
        parameters.clear();
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
    public void setOutputProperties(Properties oformat) throws IllegalArgumentException {
        this.outputProperties = new Properties();
        if (oformat != null) {
            this.outputProperties.putAll(oformat);
        }
    }

    @Override
    public Properties getOutputProperties() {
        return new Properties(outputProperties);
    }

    @Override
    public void setOutputProperty(String name, String value) throws IllegalArgumentException {
        outputProperties.setProperty(name, value);
    }

    @Override
    public String getOutputProperty(String name) throws IllegalArgumentException {
        return outputProperties.getProperty(name);
    }

    @Override
    public void setErrorListener(ErrorListener listener) throws IllegalArgumentException {
        this.errorListener = listener;
    }

    @Override
    public ErrorListener getErrorListener() {
        return errorListener;
    }

}

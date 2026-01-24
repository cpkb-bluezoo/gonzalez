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
import org.bluezoo.gonzalez.transform.runtime.SAXOutputHandler;
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
            new GonzalezTransformHandler(stylesheet, parameters, outputHandler);
        
        // Parse input through the transform
        XMLReader reader = getXMLReader(source);
        reader.setContentHandler(transformHandler);
        
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
            InputSource is;
            if (ss.getInputStream() != null) {
                is = new InputSource(ss.getInputStream());
            } else if (ss.getReader() != null) {
                is = new InputSource(ss.getReader());
            } else {
                is = new InputSource(ss.getSystemId());
            }
            is.setSystemId(ss.getSystemId());
            return is;
        }
        
        if (source instanceof SAXSource) {
            return ((SAXSource) source).getInputSource();
        }
        
        if (source instanceof DOMSource) {
            throw new TransformerException("DOMSource not yet supported");
        }
        
        throw new TransformerException("Unsupported source: " + source.getClass());
    }

    private ContentHandler getOutputHandler(Result result) throws TransformerException, IOException {
        if (result instanceof SAXResult) {
            return ((SAXResult) result).getHandler();
        }
        
        if (result instanceof StreamResult) {
            StreamResult sr = (StreamResult) result;
            Writer writer;
            if (sr.getWriter() != null) {
                writer = sr.getWriter();
            } else if (sr.getOutputStream() != null) {
                String encoding = outputProperties.getProperty("encoding", "UTF-8");
                writer = new OutputStreamWriter(sr.getOutputStream(), encoding);
            } else if (sr.getSystemId() != null) {
                String systemId = sr.getSystemId();
                File file = new File(systemId.startsWith("file:") ? 
                    systemId.substring(5) : systemId);
                String encoding = outputProperties.getProperty("encoding", "UTF-8");
                writer = new OutputStreamWriter(new FileOutputStream(file), encoding);
            } else {
                throw new TransformerException("StreamResult has no output target");
            }
            
            return new SAXOutputHandler(writer, outputProperties);
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

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

import org.bluezoo.gonzalez.schema.PSVIProvider;
import org.bluezoo.gonzalez.transform.compiler.CompiledStylesheet;
import org.bluezoo.gonzalez.transform.compiler.OutputProperties;
import org.bluezoo.gonzalez.transform.runtime.HTMLOutputHandler;
import org.bluezoo.gonzalez.transform.runtime.OutputHandler;
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
import java.net.URI;
import java.net.URL;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
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

    /** The compiled XSLT stylesheet, or null for identity transform. */
    private final CompiledStylesheet stylesheet;
    
    /** Transformation parameters keyed by name. */
    private final Map<String, Object> parameters = new HashMap<>();
    
    /** Output properties for the transformation. */
    private Properties outputProperties;
    
    /** URI resolver for resolving external resources. */
    private URIResolver uriResolver;
    
    /** Error listener for reporting transformation errors. */
    private ErrorListener errorListener;
    
    /** Initial template name for XSLT 2.0+ initial-template support. */
    private String initialTemplate;
    private String initialMode;

    /** XPath expression to select the initial context node from the source. */
    private String initialContextSelect;

    /** Allowed protocols for external DTD access. */
    private String accessExternalDTD = "";

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

    /**
     * Transforms the source XML document to the result target using the
     * configured stylesheet (or performs an identity transform if no stylesheet).
     *
     * @param xmlSource the source XML document to transform
     * @param outputTarget the target for the transformation output
     * @throws TransformerException if an error occurs during transformation
     */
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
        XMLReader reader = getXMLReader(source);
        ContentHandler handler;
        if (result instanceof SAXResult) {
            handler = ((SAXResult) result).getHandler();
        } else {
            OutputHandler oh = getStreamOutputHandler(result);
            handler = new OutputHandlerSAXAdapter(oh);
        }
        
        reader.setContentHandler(handler);
        
        InputSource inputSource = getInputSource(source);
        reader.parse(inputSource);
    }

    private void performTransform(Source source, Result result) 
            throws SAXException, IOException, TransformerException {
        OutputHandler outputHandler;
        if (result instanceof SAXResult) {
            ContentHandler ch = ((SAXResult) result).getHandler();
            outputHandler = new GonzalezTransformHandler.ContentHandlerOutputAdapter(ch);
        } else {
            outputHandler = getStreamOutputHandler(result);
        }
        
        // Create the transformation pipeline
        GonzalezTransformHandler transformHandler = 
            new GonzalezTransformHandler(stylesheet, parameters, outputHandler, errorListener);
        
        // Set initial template if specified (XSLT 2.0+ feature)
        if (initialTemplate != null) {
            transformHandler.setInitialTemplate(initialTemplate);
        }
        
        // Set initial mode if specified
        if (initialMode != null) {
            transformHandler.setInitialMode(initialMode);
        }
        
        // Set initial context select if specified
        if (initialContextSelect != null) {
            transformHandler.setInitialContextSelect(initialContextSelect);
        }
        
        // Parse input through the transform
        XMLReader reader = getXMLReader(source);
        reader.setContentHandler(transformHandler);
        
        // Set up PSVIProvider for type information (DTD/XSD types)
        if (reader instanceof PSVIProvider) {
            transformHandler.setPSVIProvider((PSVIProvider) reader);
        }
        
        // Set up LexicalHandler to receive comment events
        try {
            reader.setProperty("http://xml.org/sax/properties/lexical-handler", transformHandler);
        } catch (SAXException e) {
            // LexicalHandler not supported - comments won't be available
        }
        
        InputSource inputSource = getInputSource(source);
        reader.parse(inputSource);
    }

    /**
     * Sets the allowed protocols for external DTD access.
     *
     * @param accessExternalDTD comma-separated list of protocols, "all", or ""
     */
    void setAccessExternalDTD(String accessExternalDTD) {
        this.accessExternalDTD = accessExternalDTD != null ? accessExternalDTD : "";
    }

    private XMLReader getXMLReader(Source source) throws SAXException {
        if (source instanceof SAXSource) {
            SAXSource ss = (SAXSource) source;
            if (ss.getXMLReader() != null) {
                return ss.getXMLReader();
            }
        }
        
        XMLReader reader;
        try {
            // Try Gonzalez parser
            Class<?> parserClass = Class.forName("org.bluezoo.gonzalez.Parser");
            reader = (XMLReader) parserClass.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            reader = XMLReaderFactory.createXMLReader();
        }

        // Configure entity processing based on accessExternalDTD setting
        boolean allowDTD = accessExternalDTD != null && !accessExternalDTD.isEmpty();
        try {
            reader.setFeature("http://xml.org/sax/features/external-general-entities", allowDTD);
            reader.setFeature("http://xml.org/sax/features/external-parameter-entities", allowDTD);
        } catch (SAXException e) {
            // Platform parser may not support these features
        }
        if (allowDTD) {
            try {
                reader.setProperty("http://javax.xml.XMLConstants/property/accessExternalDTD",
                    accessExternalDTD);
            } catch (SAXException e) {
                // Parser may not support this property
            }
        }

        return reader;
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

    private OutputHandler getStreamOutputHandler(Result result) throws TransformerException, IOException {
        if (result instanceof StreamResult) {
            StreamResult sr = (StreamResult) result;
            String method = outputProperties.getProperty("method", "xml").toLowerCase();
            String encoding = outputProperties.getProperty("encoding", "UTF-8");
            boolean indent = "yes".equals(outputProperties.getProperty("indent"));
            
            // Gonzalez only supports byte streams for output (not character streams)
            // This ensures proper encoding handling and enables NIO optimizations
            if (sr.getWriter() != null) {
                throw new TransformerException(
                    "StreamResult with Writer not supported - use OutputStream or SystemId");
            }
            
            // Determine output channel/stream
            // Priority: FileChannel (most efficient) > OutputStream > FileOutputStream from systemId
            WritableByteChannel channel = null;
            OutputStream outputStream = null;
            
            if (sr.getOutputStream() != null) {
                // OutputStream provided - wrap in channel
                outputStream = sr.getOutputStream();
                channel = Channels.newChannel(outputStream);
            } else if (sr.getSystemId() != null) {
                String systemId = sr.getSystemId();
                
                // For file: URLs, use FileChannel directly (most efficient)
                if (systemId.startsWith("file:")) {
                    try {
                        Path path = Paths.get(URI.create(systemId));
                        channel = FileChannel.open(path,
                            StandardOpenOption.WRITE,
                            StandardOpenOption.CREATE,
                            StandardOpenOption.TRUNCATE_EXISTING);
                    } catch (Exception e) {
                        // Fall back to FileOutputStream for unusual file: URL formats
                        File file = new File(systemId.substring(5));
                        outputStream = new FileOutputStream(file);
                        channel = Channels.newChannel(outputStream);
                    }
                } else {
                    // Non-file URL - use FileOutputStream
                    File file = new File(systemId);
                    outputStream = new FileOutputStream(file);
                    channel = Channels.newChannel(outputStream);
                }
            } else {
                throw new TransformerException("StreamResult has no output target");
            }
            
            // Select handler based on output method
            // All handlers now use WritableByteChannel for NIO-native output
            switch (method) {
                case "text":
                    // Text output: only text content, no markup
                    return new TextOutputHandler(channel, encoding);
                    
                case "html":
                    // HTML output: HTML-specific serialization rules
                    return new HTMLOutputHandler(channel, encoding, indent);
                    
                case "xml":
                case "xhtml":
                default:
                    // XML/XHTML output: use XMLWriter for optimal serialization
                    OutputProperties props = stylesheet != null ? 
                        stylesheet.getOutputProperties() : new OutputProperties();
                    XMLWriterOutputHandler xmlHandler = new XMLWriterOutputHandler(channel, props);
                    
                    // Set up character mappings if specified
                    if (stylesheet != null && !props.getUseCharacterMaps().isEmpty()) {
                        java.util.Map<Integer, String> mappings = new java.util.HashMap<>();
                        java.util.Set<String> visited = new java.util.HashSet<>();
                        for (String mapName : props.getUseCharacterMaps()) {
                            collectCharacterMappings(mapName, mappings, visited);
                        }
                        if (!mappings.isEmpty()) {
                            xmlHandler.setCharacterMappings(mappings);
                        }
                    }
                    return xmlHandler;
            }
        }
        
        throw new TransformerException("Unsupported result: " + result.getClass());
    }

    /**
     * Sets a transformation parameter.
     *
     * @param name the parameter name
     * @param value the parameter value
     */
    @Override
    public void setParameter(String name, Object value) {
        parameters.put(name, value);
    }

    /**
     * Gets a transformation parameter value.
     *
     * @param name the parameter name
     * @return the parameter value, or null if not set
     */
    @Override
    public Object getParameter(String name) {
        return parameters.get(name);
    }

    /**
     * Clears all transformation parameters.
     */
    @Override
    public void clearParameters() {
        parameters.clear();
    }
    
    /**
     * Recursively collects character mappings from a character map and its referenced maps.
     *
     * @param mapName the name of the character map
     * @param mappings the map to populate with character-to-string mappings
     * @param visited the set of already visited map names (to avoid circular references)
     */
    private void collectCharacterMappings(String mapName, java.util.Map<Integer, String> mappings, 
            java.util.Set<String> visited) {
        if (mapName == null || visited.contains(mapName)) {
            return;  // Avoid null and circular references
        }
        visited.add(mapName);
        
        CompiledStylesheet.CharacterMap charMap = stylesheet.getCharacterMap(mapName);
        if (charMap == null) {
            return;  // Unknown character map - ignore
        }
        
        // First, process referenced character maps (lower precedence)
        for (String refName : charMap.getUseCharacterMaps()) {
            collectCharacterMappings(refName, mappings, visited);
        }
        
        // Then add this map's mappings (higher precedence - overwrites)
        mappings.putAll(charMap.getMappings());
    }

    /**
     * Sets the URI resolver for resolving external resources during transformation.
     *
     * @param resolver the URI resolver, or null to use the default resolver
     */
    @Override
    public void setURIResolver(URIResolver resolver) {
        this.uriResolver = resolver;
    }

    /**
     * Gets the URI resolver.
     *
     * @return the URI resolver, or null if not set
     */
    @Override
    public URIResolver getURIResolver() {
        return uriResolver;
    }

    /**
     * Sets the output properties for the transformation.
     *
     * @param oformat the output properties, or null to clear all properties
     * @throws IllegalArgumentException if any property value is invalid
     */
    @Override
    public void setOutputProperties(Properties oformat) throws IllegalArgumentException {
        this.outputProperties = new Properties();
        if (oformat != null) {
            this.outputProperties.putAll(oformat);
        }
    }

    /**
     * Gets a copy of the output properties.
     *
     * @return a copy of the output properties
     */
    @Override
    public Properties getOutputProperties() {
        return new Properties(outputProperties);
    }

    /**
     * Sets a single output property.
     *
     * @param name the property name (e.g., "method", "encoding", "indent")
     * @param value the property value
     * @throws IllegalArgumentException if the property name or value is invalid
     */
    @Override
    public void setOutputProperty(String name, String value) throws IllegalArgumentException {
        outputProperties.setProperty(name, value);
    }

    /**
     * Gets an output property value.
     *
     * @param name the property name
     * @return the property value, or null if not set
     * @throws IllegalArgumentException if the property name is invalid
     */
    @Override
    public String getOutputProperty(String name) throws IllegalArgumentException {
        return outputProperties.getProperty(name);
    }

    /**
     * Sets the error listener for reporting transformation errors and warnings.
     *
     * @param listener the error listener, or null to use the default listener
     * @throws IllegalArgumentException if the listener is invalid
     */
    @Override
    public void setErrorListener(ErrorListener listener) throws IllegalArgumentException {
        this.errorListener = listener;
    }

    /**
     * Gets the error listener.
     *
     * @return the error listener, or null if not set
     */
    @Override
    public ErrorListener getErrorListener() {
        return errorListener;
    }

    /**
     * Sets the initial template name for XSLT 2.0+ initial-template support.
     * If set, the transformation will start by calling this named template
     * instead of applying templates to the document root.
     *
     * @param name the name of the initial template to call
     */
    public void setInitialTemplate(String name) {
        this.initialTemplate = name;
    }

    /**
     * Returns the initial template name.
     *
     * @return the initial template name, or null
     */
    public String getInitialTemplate() {
        return initialTemplate;
    }

    /**
     * Sets the initial mode for XSLT 2.0+ support.
     * If set, the initial apply-templates uses this mode instead of the default.
     *
     * @param mode the initial mode name
     */
    public void setInitialMode(String mode) {
        this.initialMode = mode;
    }

    /**
     * Returns the initial mode name.
     *
     * @return the initial mode name, or null
     */
    public String getInitialMode() {
        return initialMode;
    }

    /**
     * Sets an XPath expression to select the initial context node from the
     * source document. When set, the transformation uses the selected node
     * instead of the document root as the initial context item.
     *
     * @param xpath the XPath expression
     */
    public void setInitialContextSelect(String xpath) {
        this.initialContextSelect = xpath;
    }

    /**
     * Command-line entry point for running XSLT transformations.
     * Usage: java org.bluezoo.gonzalez.transform.GonzalezTransformer <stylesheet.xsl> <input.xml> [<output>] [-it <template>]
     *
     * @param args command-line arguments: stylesheet, input, optional output file, optional initial template
     */
    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("Usage: java org.bluezoo.gonzalez.transform.GonzalezTransformer <stylesheet.xsl> <input.xml> [<output>] [-it <template>]");
            System.err.println("  stylesheet.xsl - Path to XSLT stylesheet");
            System.err.println("  input.xml      - Path to input XML file (or '-' for stdin)");
            System.err.println("  output         - Optional output file (defaults to stdout)");
            System.err.println("  -it <template> - Optional initial template name (XSLT 2.0+)");
            System.exit(1);
        }

        String stylesheetPath = args[0];
        String inputPath = args[1];
        String outputPath = null;
        String initialTemplate = null;
        
        // Parse remaining args
        for (int i = 2; i < args.length; i++) {
            if ("-it".equals(args[i]) && i + 1 < args.length) {
                initialTemplate = args[++i];
            } else if (outputPath == null) {
                outputPath = args[i];
            }
        }

        try {
            // Create transformer factory and compile stylesheet
            GonzalezTransformerFactory factory = new GonzalezTransformerFactory();
            
            // Load stylesheet
            File stylesheetFile = new File(stylesheetPath);
            if (!stylesheetFile.exists()) {
                System.err.println("Error: Stylesheet file not found: " + stylesheetPath);
                System.exit(1);
            }
            
            StreamSource stylesheetSource = new StreamSource(stylesheetFile);
            Templates templates = factory.newTemplates(stylesheetSource);
            Transformer transformer = templates.newTransformer();
            
            // Set initial template if specified
            if (initialTemplate != null && transformer instanceof GonzalezTransformer) {
                ((GonzalezTransformer) transformer).setInitialTemplate(initialTemplate);
            }
            
            // Load input source
            Source inputSource;
            if ("-".equals(inputPath)) {
                inputSource = new StreamSource(System.in);
            } else {
                File inputFile = new File(inputPath);
                if (!inputFile.exists()) {
                    System.err.println("Error: Input file not found: " + inputPath);
                    System.exit(1);
                }
                inputSource = new StreamSource(inputFile);
            }
            
            // Prepare output
            Result outputResult;
            if (outputPath != null) {
                outputResult = new StreamResult(new File(outputPath));
            } else {
                outputResult = new StreamResult(System.out);
            }
            
            // Perform transformation
            transformer.transform(inputSource, outputResult);
            
        } catch (TransformerException e) {
            System.err.println("Transformation error: " + e.getMessage());
            if (e.getCause() != null) {
                System.err.println("Caused by: " + e.getCause().getMessage());
                e.getCause().printStackTrace(System.err);
            } else {
                e.printStackTrace(System.err);
            }
            System.exit(1);
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace(System.err);
            System.exit(1);
        }
    }

    /**
     * Adapter that wraps an OutputHandler as a SAX ContentHandler.
     * Used by the identity transform path to pipe SAX events from a reader
     * to an OutputHandler without going through the XSLT engine.
     */
    private static class OutputHandlerSAXAdapter implements ContentHandler {

        private final OutputHandler output;
        private final java.util.List<String[]> pendingPrefixMappings =
            new java.util.ArrayList<String[]>();

        OutputHandlerSAXAdapter(OutputHandler output) {
            this.output = output;
        }

        @Override
        public void setDocumentLocator(Locator locator) {
        }

        @Override
        public void startDocument() throws SAXException {
            output.startDocument();
        }

        @Override
        public void endDocument() throws SAXException {
            output.endDocument();
        }

        @Override
        public void startPrefixMapping(String prefix, String uri) {
            pendingPrefixMappings.add(new String[] { prefix, uri });
        }

        @Override
        public void endPrefixMapping(String prefix) {
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes atts)
                throws SAXException {
            String effectiveUri = (uri != null) ? uri : "";
            String effectiveQName = (qName != null && !qName.isEmpty()) ? qName : localName;
            output.startElement(effectiveUri, localName, effectiveQName);
            for (int i = 0; i < pendingPrefixMappings.size(); i++) {
                String[] pm = pendingPrefixMappings.get(i);
                output.namespace(pm[0], pm[1]);
            }
            pendingPrefixMappings.clear();
            int len = atts.getLength();
            for (int i = 0; i < len; i++) {
                output.attribute(atts.getURI(i), atts.getLocalName(i), atts.getQName(i),
                    atts.getValue(i));
            }
        }

        @Override
        public void endElement(String uri, String localName, String qName) throws SAXException {
            String effectiveUri = (uri != null) ? uri : "";
            String effectiveQName = (qName != null && !qName.isEmpty()) ? qName : localName;
            output.endElement(effectiveUri, localName, effectiveQName);
        }

        @Override
        public void characters(char[] ch, int start, int length) throws SAXException {
            output.characters(new String(ch, start, length));
        }

        @Override
        public void ignorableWhitespace(char[] ch, int start, int length) throws SAXException {
            output.characters(new String(ch, start, length));
        }

        @Override
        public void processingInstruction(String target, String data) throws SAXException {
            output.processingInstruction(target, data);
        }

        @Override
        public void skippedEntity(String name) {
        }
    }

}

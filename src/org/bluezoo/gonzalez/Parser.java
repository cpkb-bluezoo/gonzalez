/*
 * Parser.java
 * Copyright (C) 2025 Chris Burdess
 *
 * This file is part of Gonzalez, a streaming XML parser.
 * For more information please visit https://www.nongnu.org/gonzalez/
 *
 * Gonzalez is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Gonzalez is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Gonzalez.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.bluezoo.gonzalez;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import org.xml.sax.ContentHandler;
import org.xml.sax.DTDHandler;
import org.xml.sax.EntityResolver;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXNotRecognizedException;
import org.xml.sax.SAXNotSupportedException;
import org.xml.sax.XMLReader;
import org.xml.sax.ext.LexicalHandler;

/**
 * SAX2 XMLReader implementation for Gonzalez streaming XML parser.
 *
 * <p>This class provides a standard SAX2 interface to the Gonzalez parser,
 * allowing it to be used with existing SAX2 frameworks and applications.
 * Internally, it uses an {@link XMLTokenizer} and {@link XMLParser} pipeline
 * to perform the parsing.
 *
 * <p>This class is a pure proxy - it maintains no parsing state itself,
 * delegating all operations to the tokenizer and parser. This allows the
 * same Parser instance to be reused for multiple documents by calling
 * {@link #reset()} between parses.
 *
 * <p>The parser supports:
 * <ul>
 *   <li>Standard SAX2 handlers: ContentHandler, DTDHandler, ErrorHandler</li>
 *   <li>SAX2 extension handlers: LexicalHandler</li>
 *   <li>Streaming parsing from InputStream</li>
 *   <li>Low-level byte buffer API for advanced use cases</li>
 *   <li>Automatic charset detection (BOM and XML declaration)</li>
 *   <li>Line-end normalization</li>
 *   <li>Parser reuse via {@link #reset()}</li>
 * </ul>
 *
 * <p><b>Basic Usage:</b>
 * <pre>
 * XMLReader reader = new Parser();
 * reader.setContentHandler(myContentHandler);
 * reader.parse(new InputSource(inputStream));
 * </pre>
 *
 * <p><b>Reusing Parser for Multiple Documents:</b>
 * <pre>
 * Parser parser = new Parser();
 * parser.setContentHandler(myContentHandler);
 * 
 * // Parse first document
 * parser.parse(new InputSource(stream1));
 * 
 * // Reset for next document
 * parser.reset();
 * 
 * // Parse second document
 * parser.parse(new InputSource(stream2));
 * </pre>
 *
 * <p><b>Advanced Usage (Direct Buffer API):</b>
 * <pre>
 * Parser parser = new Parser();
 * parser.setContentHandler(myContentHandler);
 * parser.setSystemId("http://example.com/doc.xml");
 * 
 * // Feed data in chunks
 * ByteBuffer buffer = ByteBuffer.wrap(data);
 * parser.receive(buffer);
 * 
 * // Signal end of document
 * parser.close();
 * </pre>
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public class Parser implements XMLReader {

    /**
     * The tokenizer that converts bytes to tokens.
     * Created once and reused for all parses.
     */
    private final XMLTokenizer tokenizer;
    
    /**
     * The parser that converts tokens to SAX events.
     * Created once and reused for all parses.
     */
    private final XMLParser xmlParser;

    /**
     * Creates a new Parser instance.
     * The internal tokenizer and parser are created and can be reused
     * for multiple documents via {@link #reset()}.
     */
    public Parser() {
        this.xmlParser = new XMLParser();
        this.tokenizer = new XMLTokenizer(xmlParser, null, null);
    }

    // ========================================================================
    // XMLReader Interface Implementation
    // ========================================================================

    @Override
    public void parse(InputSource input) throws IOException, SAXException {
        if (input == null) {
            throw new IllegalArgumentException("InputSource cannot be null");
        }

        // Set identifiers from InputSource if available
        if (input.getPublicId() != null) {
            setPublicId(input.getPublicId());
        }
        if (input.getSystemId() != null) {
            setSystemId(input.getSystemId());
        }

        // Get the input stream
        InputStream inputStream = input.getByteStream();
        if (inputStream == null) {
            // Try character stream - would need conversion (not implemented yet)
            throw new SAXException("InputSource must have a byte stream");
        }

        // Bridge pattern: read from InputStream and feed to tokenizer
        byte[] buffer = new byte[4096];
        int bytesRead;
        
        while ((bytesRead = inputStream.read(buffer)) != -1) {
            // Wrap the data in a ByteBuffer
            ByteBuffer byteBuffer = ByteBuffer.wrap(buffer, 0, bytesRead);
            
            // Feed to receive()
            receive(byteBuffer);
        }
        
        // Signal end of document
        close();
    }

    @Override
    public void parse(String systemId) throws IOException, SAXException {
        if (systemId == null) {
            throw new IllegalArgumentException("System ID cannot be null");
        }
        
        // Try to use EntityResolver to resolve the systemId
        EntityResolver resolver = xmlParser.getEntityResolver();
        if (resolver != null) {
            InputSource source = resolver.resolveEntity(null, systemId);
            if (source != null) {
                // EntityResolver provided an InputSource, use it
                parse(source);
                return;
            }
        }
        
        // No user-specified EntityResolver or it returned null
        // Use default entity resolver
        try {
            DefaultEntityResolver defaultResolver = new DefaultEntityResolver();
            InputSource source = defaultResolver.resolveEntity(null, systemId);
            if (source != null) {
                parse(source);
                return;
            }
        } catch (SAXException e) {
            // Default resolver failed, throw the exception
            throw e;
        }
        
        // This should not happen (default resolver should always return something)
        throw new SAXException("Could not resolve system ID: " + systemId);
    }

    @Override
    public ContentHandler getContentHandler() {
        return xmlParser.getContentHandler();
    }

    @Override
    public void setContentHandler(ContentHandler handler) {
        xmlParser.setContentHandler(handler);
    }

    @Override
    public DTDHandler getDTDHandler() {
        return xmlParser.getDTDHandler();
    }

    @Override
    public void setDTDHandler(DTDHandler handler) {
        xmlParser.setDTDHandler(handler);
    }

    @Override
    public ErrorHandler getErrorHandler() {
        return xmlParser.getErrorHandler();
    }

    @Override
    public void setErrorHandler(ErrorHandler handler) {
        xmlParser.setErrorHandler(handler);
    }

    @Override
    public EntityResolver getEntityResolver() {
        return xmlParser.getEntityResolver();
    }

    @Override
    public void setEntityResolver(EntityResolver resolver) {
        xmlParser.setEntityResolver(resolver);
    }

    @Override
    public boolean getFeature(String name) throws SAXNotRecognizedException, SAXNotSupportedException {
        if (name == null) {
            throw new NullPointerException("Feature name cannot be null");
        }
        
        switch (name) {
            // Delegate is-standalone to tokenizer (it owns XML declaration info)
            case "http://xml.org/sax/features/is-standalone":
                return tokenizer.isStandalone();
                
            // Mutable features (delegate to XMLParser)
            case "http://xml.org/sax/features/namespaces":
                return xmlParser.getNamespacesEnabled();
                
            case "http://xml.org/sax/features/namespace-prefixes":
                return xmlParser.getNamespacePrefixesEnabled();
                
            case "http://xml.org/sax/features/validation":
                return xmlParser.getValidationEnabled();
                
            case "http://xml.org/sax/features/external-general-entities":
                return xmlParser.getExternalGeneralEntitiesEnabled();
                
            case "http://xml.org/sax/features/external-parameter-entities":
                return xmlParser.getExternalParameterEntitiesEnabled();
                
            case "http://xml.org/sax/features/resolve-dtd-uris":
                return xmlParser.getResolveDTDURIsEnabled();
                
            case "http://xml.org/sax/features/string-interning":
                return xmlParser.getStringInterning();
                
            // Read-only features (report capabilities)
            case "http://xml.org/sax/features/lexical-handler":
                return true; // LexicalHandler interface is supported
                
            case "http://xml.org/sax/features/parameter-entities":
                return true; // Parameter entity events are reported
                
            case "http://xml.org/sax/features/use-attributes2":
                return true; // SAXAttributes implements Attributes2
                
            case "http://xml.org/sax/features/use-locator2":
                return true; // XMLTokenizer implements Locator2
                
            case "http://xml.org/sax/features/use-entity-resolver2":
                return true; // EntityResolver2 is supported
                
            case "http://xml.org/sax/features/xmlns-uris":
                return false; // xmlns attributes have no special namespace URI
                
            // Unsupported features
            case "http://xml.org/sax/features/unicode-normalization-checking":
                return false; // Not supported (XML 1.1 feature)
                
            case "http://xml.org/sax/features/xml-1.1":
                return false; // Not supported (XML 1.0 only)
                
            default:
                throw new SAXNotRecognizedException("Feature not recognized: " + name);
        }
    }

    @Override
    public void setFeature(String name, boolean value) throws SAXNotRecognizedException, SAXNotSupportedException {
        if (name == null) {
            throw new NullPointerException("Feature name cannot be null");
        }
        
        switch (name) {
            // Mutable features (delegate to XMLParser)
            case "http://xml.org/sax/features/namespaces":
                xmlParser.setNamespacesEnabled(value);
                break;
                
            case "http://xml.org/sax/features/namespace-prefixes":
                xmlParser.setNamespacePrefixesEnabled(value);
                break;
                
            case "http://xml.org/sax/features/validation":
                xmlParser.setValidationEnabled(value);
                break;
                
            case "http://xml.org/sax/features/external-general-entities":
                xmlParser.setExternalGeneralEntitiesEnabled(value);
                break;
                
            case "http://xml.org/sax/features/external-parameter-entities":
                xmlParser.setExternalParameterEntitiesEnabled(value);
                break;
                
            case "http://xml.org/sax/features/resolve-dtd-uris":
                xmlParser.setResolveDTDURIsEnabled(value);
                break;
                
            case "http://xml.org/sax/features/string-interning":
                xmlParser.setStringInterning(value);
                break;
                
            // Read-only features (throw exception if trying to change)
            case "http://xml.org/sax/features/is-standalone":
            case "http://xml.org/sax/features/lexical-handler":
            case "http://xml.org/sax/features/parameter-entities":
            case "http://xml.org/sax/features/use-attributes2":
            case "http://xml.org/sax/features/use-locator2":
            case "http://xml.org/sax/features/use-entity-resolver2":
            case "http://xml.org/sax/features/xmlns-uris":
                throw new SAXNotSupportedException(
                    "Feature is read-only: " + name + " (current value: " + getFeature(name) + ")");
                
            // Unsupported features
            case "http://xml.org/sax/features/unicode-normalization-checking":
            case "http://xml.org/sax/features/xml-1.1":
                if (value) {
                    throw new SAXNotSupportedException("Feature not supported: " + name);
                }
                // Allow setting to false (no-op, already false)
                break;
                
            default:
                throw new SAXNotRecognizedException("Feature not recognized: " + name);
        }
    }

    @Override
    public Object getProperty(String name) throws SAXNotRecognizedException, SAXNotSupportedException {
        // SAX2 extension properties
        if ("http://xml.org/sax/properties/lexical-handler".equals(name)) {
            return xmlParser.getLexicalHandler();
        }
        // Gonzalez-specific properties
        if ("http://www.nongnu.org/gonzalez/properties/dtd-parser".equals(name)) {
            return xmlParser.getDTDParser();
        }
        throw new SAXNotRecognizedException("Property not recognized: " + name);
    }

    @Override
    public void setProperty(String name, Object value) throws SAXNotRecognizedException, SAXNotSupportedException {
        // SAX2 extension properties
        if ("http://xml.org/sax/properties/lexical-handler".equals(name)) {
            if (value instanceof LexicalHandler) {
                xmlParser.setLexicalHandler((LexicalHandler) value);
            } else {
                throw new SAXNotSupportedException("Value must be a LexicalHandler");
            }
        } else {
            throw new SAXNotRecognizedException("Property not recognized: " + name);
        }
    }

    // ========================================================================
    // Parser Reuse
    // ========================================================================

    /**
     * Resets the parser state to allow reuse for parsing another document.
     *
     * <p>This method clears all parsing state from the tokenizer and XML parser,
     * allowing the same Parser instance to be reused for multiple documents.
     * Handler references (ContentHandler, DTDHandler, etc.) are preserved.
     *
     * <p><b>Usage:</b>
     * <pre>
     * Parser parser = new Parser();
     * parser.setContentHandler(handler);
     *
     * // Parse first document
     * parser.parse(new InputSource(stream1));
     *
     * // Reset for reuse
     * parser.reset();
     *
     * // Parse second document with same handlers
     * parser.parse(new InputSource(stream2));
     * </pre>
     *
     * <p><b>Note:</b> You should call this method after a parse completes
     * and before starting a new parse. It is not necessary to call reset()
     * before the first parse.
     *
     * @throws SAXException if reset fails
     */
    public void reset() throws SAXException {
        tokenizer.reset();
        xmlParser.reset();
    }

    // ========================================================================
    // Direct Tokenizer API (Advanced Usage)
    // ========================================================================

    /**
     * Sets the public identifier for the document.
     *
     * <p>The public identifier is used for error reporting and may be used
     * by entity resolvers. This delegates directly to the underlying tokenizer.
     *
     * <p>If parsing via {@link #parse(InputSource)}, the public ID from the
     * InputSource takes precedence over this setting.
     *
     * @param publicId the public identifier, or null if not available
     */
    public void setPublicId(String publicId) {
        tokenizer.setPublicId(publicId);
    }

    /**
     * Sets the system identifier for the document.
     *
     * <p>The system identifier (typically a URL) is used for resolving
     * relative URIs and for error reporting. This delegates directly to
     * the underlying tokenizer.
     *
     * <p>If parsing via {@link #parse(InputSource)}, the system ID from the
     * InputSource takes precedence over this setting.
     *
     * @param systemId the system identifier, or null if not available
     */
    public void setSystemId(String systemId) {
        tokenizer.setSystemId(systemId);
    }

    /**
     * Receives raw byte data for parsing.
     *
     * <p>This is an advanced API that allows streaming data to the parser
     * in chunks without using an InputStream. The data buffer should be
     * prepared for reading (position set to start of data, limit set to
     * end of data). This delegates directly to the underlying tokenizer.
     *
     * <p>Multiple invocations of this method may occur to supply the
     * complete document content. The parser will handle buffer underflow
     * conditions automatically, requesting more data as needed.
     *
     * <p><b>Usage:</b>
     * <pre>
     * Parser parser = new Parser();
     * parser.setContentHandler(handler);
     * parser.setSystemId("http://example.com/doc.xml");
     *
     * ByteBuffer data1 = ByteBuffer.wrap(bytes1);
     * parser.receive(data1);
     *
     * ByteBuffer data2 = ByteBuffer.wrap(bytes2);
     * parser.receive(data2);
     *
     * parser.close(); // Signal end of document
     * </pre>
     *
     * <p><b>Important:</b> You must call {@link #close()} after the last
     * receive to signal end of document and allow the parser to finish
     * processing.
     *
     * @param data a byte buffer ready for reading (position at start, limit at end)
     * @throws SAXException if a parsing error occurs
     * @throws IllegalStateException if called after {@link #close()}
     */
    public void receive(ByteBuffer data) throws SAXException {
        tokenizer.receive(data);
    }

    /**
     * Signals that all data has been received and completes parsing.
     *
     * <p>This method must be called after the last {@link #receive} call
     * to signal the end of the document. The parser will process any
     * remaining buffered data and generate final SAX events (such as
     * endDocument). This delegates directly to the underlying tokenizer.
     *
     * <p>After this method is called, you can call {@link #reset()} to
     * prepare the parser for reuse with another document.
     *
     * <p>If there is incomplete data (e.g., an unclosed element), this
     * method will throw a SAXException.
     *
     * @throws SAXException if there is incomplete or invalid data
     * @throws IllegalStateException if called without prior {@link #receive}
     */
    public void close() throws SAXException {
        tokenizer.close();
    }

    /**
     * Gets the public identifier from the tokenizer.
     *
     * @return the public identifier, or null if not set
     */
    public String getPublicId() {
        return tokenizer.getPublicId();
    }

    /**
     * Gets the system identifier from the tokenizer.
     *
     * @return the system identifier, or null if not set
     */
    public String getSystemId() {
        return tokenizer.getSystemId();
    }
}


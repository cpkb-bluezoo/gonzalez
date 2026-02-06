/*
 * StylesheetResolver.java
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

package org.bluezoo.gonzalez.transform.compiler;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.HashSet;
import java.util.Set;

import javax.xml.transform.Source;
import javax.xml.transform.TransformerException;
import javax.xml.transform.URIResolver;
import javax.xml.transform.stream.StreamSource;

import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.XMLReaderFactory;

/**
 * Resolves and compiles external stylesheets for xsl:import and xsl:include.
 *
 * <p>This class handles the resolution of external stylesheet references,
 * supporting both absolute and relative URIs. It integrates with the JAXP
 * {@link URIResolver} mechanism for custom resolution strategies.
 *
 * <p>The resolver tracks which stylesheets are currently being loaded (on the
 * call stack) to detect circular imports/includes. Note that importing the same
 * stylesheet multiple times (e.g., from different locations) is valid in XSLT
 * and is allowed - each import gets a different import precedence.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public class StylesheetResolver {

    private final URIResolver uriResolver;
    private final Set<String> loadedStylesheets;
    
    // Global precedence counter - increments each time an import is processed
    // Shared across the entire import tree via the precedenceCounter array
    private final int[] precedenceCounter;
    
    // Global template declaration counter - increments for each template
    // Shared across the entire import tree to ensure unique declaration indices
    private final int[] templateCounter;

    /**
     * Creates a new stylesheet resolver with no custom URI resolver.
     */
    public StylesheetResolver() {
        this(null);
    }

    /**
     * Creates a new stylesheet resolver with the given URI resolver.
     *
     * @param uriResolver the JAXP URI resolver, or null to use default resolution
     */
    public StylesheetResolver(URIResolver uriResolver) {
        this.uriResolver = uriResolver;
        this.loadedStylesheets = new HashSet<>();
        this.precedenceCounter = new int[] { 0 };  // Shared mutable counter
        this.templateCounter = new int[] { 0 };    // Shared mutable counter
    }
    
    /**
     * Private constructor for child resolvers that share state.
     */
    private StylesheetResolver(URIResolver uriResolver, Set<String> loadedStylesheets, 
                               int[] precedenceCounter, int[] templateCounter) {
        this.uriResolver = uriResolver;
        this.loadedStylesheets = loadedStylesheets;
        this.precedenceCounter = precedenceCounter;
        this.templateCounter = templateCounter;
    }

    /**
     * Creates a child resolver that shares the loaded stylesheet tracking,
     * precedence counter, and template counter. Used when compiling imported/included stylesheets.
     *
     * @return a new resolver sharing the loaded set and counters
     */
    StylesheetResolver createChild() {
        return new StylesheetResolver(uriResolver, loadedStylesheets, precedenceCounter, templateCounter);
    }
    
    /**
     * Gets the next import precedence value and increments the counter.
     * Called when an imported stylesheet's templates are about to be created.
     *
     * @return the next precedence value
     */
    int nextPrecedence() {
        return precedenceCounter[0]++;
    }
    
    /**
     * Gets the current precedence value without incrementing.
     *
     * @return the current precedence value
     */
    int currentPrecedence() {
        return precedenceCounter[0];
    }
    
    /**
     * Gets the next template declaration index and increments the counter.
     * This ensures unique declaration indices across all included/imported stylesheets.
     *
     * @return the next template index
     */
    int nextTemplateIndex() {
        return templateCounter[0]++;
    }

    /**
     * Marks a stylesheet URI as currently being loaded.
     * Used for circular reference detection.
     *
     * @param uri the resolved URI
     */
    void markLoading(String uri) {
        if (uri != null) {
            loadedStylesheets.add(normalizeUri(uri));
        }
    }

    /**
     * Unmarks a stylesheet URI as no longer being loaded.
     * Called after stylesheet parsing is complete.
     *
     * @param uri the resolved URI
     */
    void unmarkLoading(String uri) {
        if (uri != null) {
            loadedStylesheets.remove(normalizeUri(uri));
        }
    }

    /**
     * Checks if a stylesheet is currently being loaded (on the call stack).
     * This is used to detect circular imports/includes.
     *
     * @param uri the URI to check
     * @return true if currently being loaded
     */
    boolean isLoading(String uri) {
        if (uri == null) {
            return false;
        }
        return loadedStylesheets.contains(normalizeUri(uri));
    }

    /**
     * Resolves and compiles an external stylesheet.
     *
     * @param href the href attribute from xsl:import or xsl:include
     * @param baseUri the base URI of the importing stylesheet
     * @param isImport true for xsl:import, false for xsl:include
     * @param importPrecedence the import precedence to use
     * @return a compiled stylesheet, or null if resolution fails
     * @throws SAXException if parsing fails
     * @throws IOException if I/O fails
     */
    public CompiledStylesheet resolve(String href, String baseUri, 
            boolean isImport, int importPrecedence) throws SAXException, IOException {
        
        String resolvedUri = resolveUri(href, baseUri);
        
        // Check for circular reference - only throw if currently being loaded (on the call stack)
        // It's valid to import the same stylesheet multiple times from different places
        if (isLoading(resolvedUri)) {
            throw new SAXException("Circular stylesheet reference detected: " + resolvedUri);
        }
        
        // Mark as loading before parsing to prevent infinite loops
        markLoading(resolvedUri);
        
        try {
            // Get the input source
            InputSource inputSource = getInputSource(href, baseUri, resolvedUri);
            if (inputSource == null) {
                throw new SAXException("Unable to resolve stylesheet: " + href);
            }
            
            // Create compiler for the external stylesheet
            StylesheetResolver childResolver = createChild();
            StylesheetCompiler compiler = new StylesheetCompiler(childResolver, resolvedUri, importPrecedence);
            
            // Parse the stylesheet
            XMLReader reader = createXMLReader();
            reader.setContentHandler(compiler);
            reader.parse(inputSource);
            
            try {
                return compiler.getCompiledStylesheet();
            } catch (javax.xml.transform.TransformerConfigurationException e) {
                throw new SAXException(e.getMessage(), e);
            }
        } finally {
            // Unmark as loading - the stylesheet is fully parsed now
            // This allows it to be imported again from a different place
            unmarkLoading(resolvedUri);
        }
    }

    /**
     * Resolves a URI reference against a base URI.
     *
     * @param href the href to resolve
     * @param baseUri the base URI
     * @return the resolved URI string
     */
    String resolveUri(String href, String baseUri) {
        if (href == null || href.isEmpty()) {
            return baseUri;
        }
        
        // Check if href is already absolute
        try {
            URI hrefUri = new URI(href);
            if (hrefUri.isAbsolute()) {
                return href;
            }
        } catch (URISyntaxException e) {
            // Not a valid URI, try to resolve against base
        }
        
        // Resolve against base
        if (baseUri != null && !baseUri.isEmpty()) {
            try {
                URI base = new URI(baseUri);
                URI resolved = base.resolve(href);
                return resolved.toString();
            } catch (URISyntaxException e) {
                // Fall back to simple concatenation
                if (baseUri.endsWith("/")) {
                    return baseUri + href;
                }
                int lastSlash = baseUri.lastIndexOf('/');
                if (lastSlash >= 0) {
                    return baseUri.substring(0, lastSlash + 1) + href;
                }
            }
        }
        
        return href;
    }

    /**
     * Gets an InputSource for the resolved URI.
     */
    private InputSource getInputSource(String href, String baseUri, String resolvedUri) 
            throws SAXException, IOException {
        
        // Try custom URI resolver first
        if (uriResolver != null) {
            try {
                Source source = uriResolver.resolve(href, baseUri);
                if (source != null) {
                    return sourceToInputSource(source);
                }
            } catch (TransformerException e) {
                throw new SAXException("URIResolver failed: " + e.getMessage(), e);
            }
        }
        
        // Default resolution - open the stream for the Gonzalez parser
        InputSource inputSource = new InputSource(resolvedUri);
        inputSource.setSystemId(resolvedUri);
        
        // Open the byte stream since Gonzalez parser requires it
        InputStream byteStream = null;
        try {
            URL url = new URL(resolvedUri);
            byteStream = url.openStream();
        } catch (Exception e) {
            // URL open failed, try as a file path
            String filePath = resolvedUri;
            // Strip file: prefix if present
            if (filePath.startsWith("file:")) {
                filePath = filePath.substring(5);
                // Handle file:/// on Unix
                if (filePath.startsWith("//")) {
                    filePath = filePath.substring(2);
                }
            }
            File file = new File(filePath);
            if (file.exists()) {
                byteStream = new FileInputStream(file);
            }
        }
        
        if (byteStream != null) {
            inputSource.setByteStream(byteStream);
        }
        
        return inputSource;
    }

    /**
     * Converts a JAXP Source to a SAX InputSource.
     */
    private InputSource sourceToInputSource(Source source) throws IOException {
        if (source instanceof StreamSource) {
            StreamSource ss = (StreamSource) source;
            InputSource is = new InputSource();
            is.setSystemId(ss.getSystemId());
            is.setPublicId(ss.getPublicId());
            if (ss.getInputStream() != null) {
                is.setByteStream(ss.getInputStream());
            } else if (ss.getReader() != null) {
                is.setCharacterStream(ss.getReader());
            } else if (ss.getSystemId() != null) {
                // Open the stream
                try {
                    URL url = new URL(ss.getSystemId());
                    is.setByteStream(url.openStream());
                } catch (MalformedURLException e) {
                    File file = new File(ss.getSystemId());
                    if (file.exists()) {
                        is.setByteStream(new FileInputStream(file));
                    }
                }
            }
            return is;
        }
        
        // For other source types, try to open by system ID
        InputSource is = new InputSource();
        String systemId = source.getSystemId();
        is.setSystemId(systemId);
        if (systemId != null) {
            try {
                URL url = new URL(systemId);
                is.setByteStream(url.openStream());
            } catch (MalformedURLException e) {
                File file = new File(systemId);
                if (file.exists()) {
                    is.setByteStream(new FileInputStream(file));
                }
            }
        }
        return is;
    }

    /**
     * Creates an XMLReader for parsing stylesheets.
     */
    private XMLReader createXMLReader() throws SAXException {
        try {
            // Try to use Gonzalez parser if available
            Class<?> parserClass = Class.forName("org.bluezoo.gonzalez.Parser");
            return (XMLReader) parserClass.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            // Fall back to default
            return XMLReaderFactory.createXMLReader();
        }
    }

    /**
     * Normalizes a URI for comparison purposes.
     */
    private String normalizeUri(String uri) {
        if (uri == null) {
            return "";
        }
        // Remove fragment identifier
        int hashIndex = uri.indexOf('#');
        if (hashIndex >= 0) {
            uri = uri.substring(0, hashIndex);
        }
        return uri;
    }

}

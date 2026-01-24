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

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
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
 * <p>The resolver tracks which stylesheets have been loaded to detect and
 * prevent circular imports/includes.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public class StylesheetResolver {

    private final URIResolver uriResolver;
    private final Set<String> loadedStylesheets;

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
    }

    /**
     * Creates a child resolver that shares the loaded stylesheet tracking.
     * Used when compiling imported/included stylesheets.
     *
     * @return a new resolver sharing the loaded set
     */
    StylesheetResolver createChild() {
        StylesheetResolver child = new StylesheetResolver(uriResolver);
        child.loadedStylesheets.addAll(this.loadedStylesheets);
        return child;
    }

    /**
     * Marks a stylesheet URI as loaded.
     *
     * @param uri the resolved URI
     */
    void markLoaded(String uri) {
        if (uri != null) {
            loadedStylesheets.add(normalizeUri(uri));
        }
    }

    /**
     * Checks if a stylesheet has already been loaded.
     *
     * @param uri the URI to check
     * @return true if already loaded
     */
    boolean isLoaded(String uri) {
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
        
        // Check for circular reference
        if (isLoaded(resolvedUri)) {
            throw new SAXException("Circular stylesheet reference detected: " + resolvedUri);
        }
        
        // Mark as loaded before parsing to prevent infinite loops
        markLoaded(resolvedUri);
        
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
        
        return compiler.getCompiledStylesheet();
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
        
        // Default resolution
        InputSource inputSource = new InputSource(resolvedUri);
        inputSource.setSystemId(resolvedUri);
        return inputSource;
    }

    /**
     * Converts a JAXP Source to a SAX InputSource.
     */
    private InputSource sourceToInputSource(Source source) {
        if (source instanceof StreamSource) {
            StreamSource ss = (StreamSource) source;
            InputSource is = new InputSource();
            is.setSystemId(ss.getSystemId());
            is.setPublicId(ss.getPublicId());
            if (ss.getInputStream() != null) {
                is.setByteStream(ss.getInputStream());
            }
            if (ss.getReader() != null) {
                is.setCharacterStream(ss.getReader());
            }
            return is;
        }
        
        // For other source types, just use the system ID
        InputSource is = new InputSource();
        is.setSystemId(source.getSystemId());
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

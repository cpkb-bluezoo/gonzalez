/*
 * Parser.java
 * Copyright (C) 2025 Chris Burdess
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

package org.bluezoo.gonzalez;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.UnsupportedCharsetException;
import org.bluezoo.gonzalez.schema.PSVIProvider;
import org.bluezoo.gonzalez.schema.TypedValue;
import org.bluezoo.gonzalez.schema.Validity;
import org.bluezoo.gonzalez.schema.ValidationSource;
import org.xml.sax.Attributes;
import org.xml.sax.ContentHandler;
import org.xml.sax.DTDHandler;
import org.xml.sax.EntityResolver;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.SAXNotRecognizedException;
import org.xml.sax.SAXNotSupportedException;
import org.xml.sax.XMLReader;
import org.xml.sax.ext.DeclHandler;
import org.xml.sax.ext.LexicalHandler;

/**
 * Gonzalez streaming XML parser.
 *
 * <p>This class is the SAX2 face of the Gonzalez parser. As an XMLReader,
 * it allows the parser to be used with existing SAX2 frameworks and
 * applications.
 * It is a wrapper: the parsing itself is done by an {@link XMLParser},
 * which knows nothing of SAX. This class translates SAX feature and
 * property URIs into that parser's typed configuration, resolves an
 * {@code InputSource} to a byte stream, and installs a {@link SAXAdapter}
 * to turn the native {@link XMLHandler} events into ContentHandler,
 * LexicalHandler, DeclHandler and DTDHandler callbacks.
 *
 * <p>An application that does not need SAX can use {@link XMLParser}
 * directly.
 *
 * <p>The same Parser instance to be reused for multiple documents by calling
 * {@link #reset()} between parses.
 *
 * <p>The parser supports:
 * <ul>
 *   <li>Standard SAX2 handlers: ContentHandler, DTDHandler, ErrorHandler</li>
 *   <li>SAX2 extension handlers: LexicalHandler</li>
 *   <li>Low-level byte buffer API for streaming</li>
 *   <li>Automatic charset detection (BOM and XML declaration)</li>
 *   <li>Line-end normalization</li>
 *   <li>Legacy blocking parsing from InputStream</li>
 *   <li>Parser reuse via {@link #reset()}</li>
 *   <li>Standard SAX2 Locator2 interface for reporting</li>
 * </ul>
 *
 * <p><b>Streaming usage:</b>
 * <pre>
 * Parser parser = new Parser();
 * parser.setContentHandler(myContentHandler);
 * parser.setSystemId("http://example.com/doc.xml");
 * 
 * // Feed data as it arrives
 * ByteBuffer buffer = ByteBuffer.allocate(8192);
 * while (channel.read(buffer) != -1) {
 *     buffer.flip();
 *     parser.receive(buffer);
 *     buffer.compact(); // keep any bytes not yet consumed
 * }
 * 
 * // Signal end of document
 * parser.close();
 * </pre>
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public class Parser implements XMLReader, PSVIProvider {

    /**
     * The native parser this reader wraps. It holds every setting that is
     * not SAX-specific, so that there is one copy of each.
     */
    private final XMLParser core = new XMLParser();

    // ===== SAX-side configuration: the handlers the adapter reports to. =====
    private ContentHandler contentHandler;
    private DTDHandler dtdHandler;
    private ErrorHandler errorHandler;
    private LexicalHandler lexicalHandler;
    private DeclHandler declHandler;

    /** The native handler chosen with {@link #setXMLHandler}, or null for
     *  the SAX path. */
    private XMLHandler xmlHandler;

    /** The SAX string-interning feature. Reported back as set; names are
     *  interned either way. */
    private boolean stringInterning = true;

    /** True once {@link #prepare()} has run for the document currently
     *  being received, until {@link #reset()}. */
    private boolean started;

    /** The adapter feeding {@link #contentHandler} and friends - kept so a
     *  handler set or changed after {@link #prepare()} has run still
     *  reaches the live pipeline. Non-null only on the SAX path, and only
     *  while {@link #started}. */
    private SAXAdapter adapter;

    /**
     * Creates a new Parser instance. The pipeline is built lazily, once
     * per document, when parsing begins - see {@link #prepare()}.
     */
    public Parser() {
    }

    /**
     * Chooses the handler for the document about to be received, if that
     * has not already been done for it: the native handler if one was set
     * with {@link #setXMLHandler}, otherwise a new {@link SAXAdapter}
     * (single-document, like the pipeline it feeds) over the SAX handlers.
     * A no-op once done. Called from {@link #receive(ByteBuffer)},
     * {@link #close()} and the {@code parse} methods.
     */
    private void prepare() {
        if (started) {
            return;
        }
        XMLHandler target = xmlHandler;
        if (target == null) {
            SAXAdapter newAdapter = new SAXAdapter(core.isNamespaceAware());
            newAdapter.setContentHandler(contentHandler);
            newAdapter.setLexicalHandler(lexicalHandler);
            newAdapter.setDeclHandler(declHandler);
            newAdapter.setDTDHandler(dtdHandler);
            newAdapter.setErrorHandler(errorHandler);
            newAdapter.setXmlnsUris(core.isXmlnsUris());
            newAdapter.setPublicId(core.getPublicId());
            newAdapter.setSystemId(core.getSystemId());
            adapter = newAdapter;
            target = newAdapter;
        }
        core.setHandler(target);
        started = true;
    }

    // ========================================================================
    // XMLReader Interface Implementation
    // ========================================================================

    /**
     * Parses an XML document from an InputSource.
     *
     * <p>This is the standard SAX2 parsing method. It reads data from the
     * InputSource's byte stream in chunks and feeds them to the streaming
     * parser. This method blocks until the entire document has been parsed.
     *
     * <p>For non-blocking parsing, use the {@link #receive(ByteBuffer)} and
     * {@link #close()} methods instead.
     *
     * <p>If the InputSource specifies a public ID, system ID, or encoding,
     * these will be used by the parser. The encoding can be overridden by
     * a BOM or XML declaration in the document itself.
     *
     * @param input the InputSource containing the document to parse
     * @throws IOException if an I/O error occurs reading from the stream
     * @throws SAXException if a parsing error occurs
     * @throws IllegalArgumentException if input is null
     */
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
        
        // The adapter needs the identifiers just set above.
        prepare();

        // Set initial encoding from InputSource if specified.
        if (input.getEncoding() != null) {
            try {
                Charset charset = Charset.forName(input.getEncoding());
                core.setInitialCharset(charset);
            } catch (IllegalCharsetNameException | UnsupportedCharsetException e) {
                // Invalid encoding name - ignore and use default (UTF-8)
                // The XML declaration will override if needed
            }
        }

        // Get the input stream
        InputStream inputStream = input.getByteStream();
        // Track whether we opened the stream ourselves so we can close it.
        boolean closeInputStream = false;
        if (inputStream == null) {
            // No byte stream supplied. A character stream would need charset
            // conversion, which the byte-oriented decoder does not do, so it
            // is still unsupported. But a system ID alone is a valid SAX
            // InputSource: resolve it to a byte stream, matching the behaviour
            // of parse(String) and standard SAX XMLReaders.
            if (input.getCharacterStream() != null) {
                throw new SAXException(
                        "InputSource character streams are not supported; "
                        + "provide a byte stream or system ID");
            }
            String resolveId = input.getSystemId();
            if (resolveId == null) {
                throw new SAXException(
                        "InputSource must have a byte stream or system ID");
            }
            InputSource resolved = null;
            EntityResolver resolver = getEntityResolver();
            if (resolver != null) {
                resolved = resolver.resolveEntity(input.getPublicId(), resolveId);
            }
            if (resolved == null || resolved.getByteStream() == null) {
                resolved = new DefaultEntityResolver()
                        .resolveEntity(input.getPublicId(), resolveId);
            }
            if (resolved == null || resolved.getByteStream() == null) {
                throw new SAXException(
                        "Could not open system ID: " + resolveId);
            }
            inputStream = resolved.getByteStream();
            closeInputStream = true;
        }

        // The native parser pumps the stream and signals end of document.
        try {
            core.parse(inputStream);
        } finally {
            // Only close streams we opened ourselves from a system ID; streams
            // supplied by the caller remain the caller's responsibility.
            if (closeInputStream) {
                try {
                    inputStream.close();
                } catch (IOException ignored) {
                    // Nothing useful to do if closing the resolved stream fails.
                }
            }
        }
    }

    /**
     * Parses an XML document identified by a system ID (URI).
     *
     * <p>This method resolves the system ID to an InputSource using the
     * configured {@link EntityResolver}, or a default resolver if none
     * is set. The resolved InputSource is then parsed using
     * {@link #parse(InputSource)}.
     *
     * <p>The system ID is typically a URL (http:, https:, file:) or a
     * relative path that will be resolved against the current working
     * directory.
     *
     * @param systemId the system identifier (URI) of the document to parse
     * @throws IOException if an I/O error occurs
     * @throws SAXException if a parsing error occurs or the URI cannot be resolved
     * @throws IllegalArgumentException if systemId is null
     */
    @Override
    public void parse(String systemId) throws IOException, SAXException {
        if (systemId == null) {
            throw new IllegalArgumentException("System ID cannot be null");
        }
        
        // Try to use EntityResolver to resolve the systemId
        EntityResolver resolver = getEntityResolver();
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

    // ========================================================================
    // NIO Channel-Based Parsing (Native API)
    // ========================================================================

    /**
     * Parses an XML document from a ReadableByteChannel.
     *
     * <p>This is the native NIO parsing method. It reads data from the channel
     * in chunks and feeds them to the streaming parser. This method blocks
     * until the entire document has been parsed.
     *
     * <p>This is the most efficient way to parse from files when using
     * {@link java.nio.channels.FileChannel}, as it avoids the overhead of
     * InputStream bridging.
     *
     * <p><b>Example with FileChannel:</b>
     * <pre>
     * Parser parser = new Parser();
     * parser.setContentHandler(myHandler);
     * try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
     *     parser.setSystemId(path.toUri().toString());
     *     parser.parse(channel);
     * }
     * </pre>
     *
     * <p><b>Note:</b> The channel is not closed by this method. The caller
     * is responsible for closing the channel after parsing completes.
     *
     * @param channel the channel to read from
     * @throws IOException if an I/O error occurs reading from the channel
     * @throws SAXException if a parsing error occurs
     * @throws IllegalArgumentException if channel is null
     */
    public void parse(ReadableByteChannel channel) throws IOException, SAXException {
        if (channel == null) {
            throw new IllegalArgumentException("Channel cannot be null");
        }

        prepare();
        core.parse(channel);
    }

    /**
     * Returns the current content handler.
     *
     * @return the current ContentHandler, or null if none has been set
     * @see #setContentHandler(ContentHandler)
     */
    @Override
    public ContentHandler getContentHandler() {
        return contentHandler;
    }

    /**
     * Sets the content handler to receive document events.
     *
     * <p>The content handler receives callbacks for document structure events
     * such as {@code startDocument}, {@code startElement}, {@code characters},
     * {@code endElement}, and {@code endDocument}.
     *
     * @param handler the content handler, or null to remove the current handler
     * @see ContentHandler
     */
    @Override
    public void setContentHandler(ContentHandler handler) {
        contentHandler = handler;
        if (adapter != null) {
            adapter.setContentHandler(handler);
        }
    }

    /**
     * Returns the Gonzalez-native event handler, if one is configured.
     *
     * @return the native handler, or {@code null} when events are adapted to SAX
     */
    public XMLHandler getXMLHandler() {
        return xmlHandler;
    }

    /**
     * Selects Gonzalez's native event path for subsequent parses.
     *
     * <p>When non-null, parse events are sent directly to this handler
     * (through the namespace filter when namespaces are enabled) and no
     * {@link SAXAdapter} is created. SAX content, DTD, lexical, declaration,
     * and error handlers are therefore not called; the native handler receives
     * the corresponding events and owns native error reporting. Passing
     * {@code null} restores the standard SAX path.
     *
     * <p>This setting, like namespace awareness and validation, cannot be
     * changed after parsing a document has started. It is retained by
     * {@link #reset()} for parser reuse.
     *
     * @param handler the native handler, or {@code null} to use SAX
     * @throws SAXNotSupportedException if parsing has already started
     */
    public void setXMLHandler(XMLHandler handler) throws SAXNotSupportedException {
        if (started && handler != xmlHandler) {
            throw new SAXNotSupportedException(
                    "Cannot change XMLHandler once parsing has started");
        }
        xmlHandler = handler;
    }

    /**
     * Returns the current DTD handler.
     *
     * @return the current DTDHandler, or null if none has been set
     * @see #setDTDHandler(DTDHandler)
     */
    @Override
    public DTDHandler getDTDHandler() {
        return dtdHandler;
    }

    /**
     * Sets the DTD handler to receive DTD events.
     *
     * <p>The DTD handler receives callbacks for notation declarations and
     * unparsed entity declarations from the DTD.
     *
     * @param handler the DTD handler, or null to remove the current handler
     * @see DTDHandler
     */
    @Override
    public void setDTDHandler(DTDHandler handler) {
        dtdHandler = handler;
        if (adapter != null) {
            adapter.setDTDHandler(handler);
        }
    }

    /**
     * Returns the current error handler.
     *
     * @return the current ErrorHandler, or null if none has been set
     * @see #setErrorHandler(ErrorHandler)
     */
    @Override
    public ErrorHandler getErrorHandler() {
        return errorHandler;
    }

    /**
     * Sets the error handler to receive parsing errors.
     *
     * <p>The error handler receives callbacks for warnings, recoverable errors,
     * and fatal errors. If no error handler is set, fatal errors will still
     * throw SAXException.
     *
     * @param handler the error handler, or null to remove the current handler
     * @see ErrorHandler
     */
    @Override
    public void setErrorHandler(ErrorHandler handler) {
        errorHandler = handler;
        if (adapter != null) {
            adapter.setErrorHandler(handler);
        }
    }

    /**
     * Returns the current entity resolver.
     *
     * @return the current EntityResolver, or null if none has been set
     * @see #setEntityResolver(EntityResolver)
     */
    @Override
    public EntityResolver getEntityResolver() {
        return core.getEntityResolver();
    }

    /**
     * Sets the entity resolver for resolving external entities.
     *
     * <p>The entity resolver is called to resolve external entities referenced
     * in the document, including the external DTD subset and external parsed
     * entities. The resolver can provide alternative input sources or redirect
     * resolution to different locations.
     *
     * <p>Gonzalez also supports {@link org.xml.sax.ext.EntityResolver2} for
     * enhanced resolution capabilities.
     *
     * @param resolver the entity resolver, or null to use the default resolver
     * @see EntityResolver
     * @see org.xml.sax.ext.EntityResolver2
     */
    @Override
    public void setEntityResolver(EntityResolver resolver) {
        core.setEntityResolver(resolver);
    }

    /**
     * Returns the value of a parser feature.
     *
     * <p>Gonzalez supports the following SAX2 features:
     *
     * <table class="striped">
     * <caption>Supported SAX2 features</caption>
     * <thead>
     * <tr><th>Feature URI</th><th>Default</th><th>Description</th></tr>
     * </thead>
     * <tbody>
     * <tr><td>{@code http://xml.org/sax/features/namespaces}</td>
     *     <td>true</td><td>Perform namespace processing</td></tr>
     * <tr><td>{@code http://xml.org/sax/features/namespace-prefixes}</td>
     *     <td>false</td><td>Report xmlns attributes</td></tr>
     * <tr><td>{@code http://xml.org/sax/features/validation}</td>
     *     <td>false</td><td>Validate against DTD</td></tr>
     * <tr><td>{@code http://xml.org/sax/features/external-general-entities}</td>
     *     <td>false</td><td>Include external general entities</td></tr>
     * <tr><td>{@code http://xml.org/sax/features/external-parameter-entities}</td>
     *     <td>false</td><td>Include external parameter entities</td></tr>
     * <tr><td>{@code http://apache.org/xml/features/disallow-doctype-decl}</td>
     *     <td>false</td><td>Reject any document containing a DOCTYPE declaration
     *     (defense against XXE and entity-expansion attacks)</td></tr>
     * <tr><td>{@code http://xml.org/sax/features/string-interning}</td>
     *     <td>true</td><td>Intern element/attribute names</td></tr>
     * <tr><td>{@code http://xml.org/sax/features/is-standalone}</td>
     *     <td>false</td><td>(Read-only) Document standalone status</td></tr>
     * <tr><td>{@code http://xml.org/sax/features/xml-1.1}</td>
     *     <td>true</td><td>(Read-only) XML 1.1 support</td></tr>
     * </tbody>
     * </table>
     *
     * @param name the feature URI
     * @return the current value of the feature (true or false)
     * @throws SAXNotRecognizedException if the feature is not recognized
     * @throws SAXNotSupportedException if the feature cannot be read
     * @throws NullPointerException if name is null
     */
    @Override
    public boolean getFeature(String name) throws SAXNotRecognizedException, SAXNotSupportedException {
        if (name == null) {
            throw new NullPointerException("Feature name cannot be null");
        }
        switch (name) {
            case "http://xml.org/sax/features/is-standalone":
                return core.isStandalone();

            case "http://xml.org/sax/features/namespaces":
                return core.isNamespaceAware();

            case "http://xml.org/sax/features/namespace-prefixes":
                return core.isNamespacePrefixes();

            case "http://xml.org/sax/features/validation":
                return core.isValidating();

            case "http://xml.org/sax/features/external-general-entities":
                return core.isExternalGeneralEntities();

            case "http://xml.org/sax/features/external-parameter-entities":
                return core.isExternalParameterEntities();

            case "http://apache.org/xml/features/disallow-doctype-decl":
                return core.isDisallowDoctypeDecl();

            case "http://xml.org/sax/features/resolve-dtd-uris":
                return core.isResolveDTDURIs();

            case "http://xml.org/sax/features/string-interning":
                return stringInterning;

            case "http://xml.org/sax/features/xmlns-uris":
                return core.isXmlnsUris();

            // Read-only features (report capabilities)
            case "http://xml.org/sax/features/lexical-handler":
            case "http://xml.org/sax/features/parameter-entities":
            case "http://xml.org/sax/features/use-attributes2":
            case "http://xml.org/sax/features/use-locator2":
            case "http://xml.org/sax/features/use-entity-resolver2":
            case "http://xml.org/sax/features/xml-1.1":
                return true;

            // JAXP secure processing
            case "http://javax.xml.XMLConstants/feature/secure-processing":
                return !core.isExternalGeneralEntities() && !core.isExternalParameterEntities();

            // Unsupported features
            case "http://xml.org/sax/features/unicode-normalization-checking":
                return false;

            default:
                throw new SAXNotRecognizedException("Feature not recognized: " + name);
        }
    }

    /**
     * Sets the value of a parser feature.
     *
     * <p>See {@link #getFeature(String)} for the list of supported features.
     * Some features are read-only and cannot be changed.
     *
     * <p>Features should be set before parsing begins. Setting features
     * during parsing may have undefined behavior.
     *
     * @param name the feature URI
     * @param value the new value for the feature
     * @throws SAXNotRecognizedException if the feature is not recognized
     * @throws SAXNotSupportedException if the feature cannot be set to the
     *         specified value (e.g., read-only features)
     * @throws NullPointerException if name is null
     */
    @Override
    public void setFeature(String name, boolean value) throws SAXNotRecognizedException, SAXNotSupportedException {
        if (name == null) {
            throw new NullPointerException("Feature name cannot be null");
        }
        switch (name) {
            case "http://xml.org/sax/features/namespaces":
                if (started && value != core.isNamespaceAware()) {
                    throw new SAXNotSupportedException(
                            "Cannot change " + name + " once parsing has started");
                }
                core.setNamespaceAware(value);
                break;

            case "http://xml.org/sax/features/namespace-prefixes":
                core.setNamespacePrefixes(value);
                break;

            case "http://xml.org/sax/features/validation":
                if (started && value != core.isValidating()) {
                    throw new SAXNotSupportedException(
                            "Cannot change " + name + " once parsing has started");
                }
                core.setValidating(value);
                break;

            case "http://xml.org/sax/features/external-general-entities":
                core.setExternalGeneralEntities(value);
                break;

            case "http://xml.org/sax/features/external-parameter-entities":
                core.setExternalParameterEntities(value);
                break;

            case "http://apache.org/xml/features/disallow-doctype-decl":
                core.setDisallowDoctypeDecl(value);
                break;

            case "http://xml.org/sax/features/resolve-dtd-uris":
                core.setResolveDTDURIs(value);
                break;

            case "http://xml.org/sax/features/string-interning":
                stringInterning = value;
                break;

            case "http://xml.org/sax/features/xmlns-uris":
                core.setXmlnsUris(value);
                break;

            // Read-only features (throw exception if trying to change)
            case "http://xml.org/sax/features/is-standalone":
            case "http://xml.org/sax/features/lexical-handler":
            case "http://xml.org/sax/features/parameter-entities":
            case "http://xml.org/sax/features/use-attributes2":
            case "http://xml.org/sax/features/use-locator2":
            case "http://xml.org/sax/features/use-entity-resolver2":
            case "http://xml.org/sax/features/xml-1.1":
                throw new SAXNotSupportedException(
                    "Feature is read-only: " + name + " (current value: " + getFeature(name) + ")");

            // JAXP secure processing
            case "http://javax.xml.XMLConstants/feature/secure-processing":
                // One SAX feature, three native settings.
                core.setExternalGeneralEntities(!value);
                core.setExternalParameterEntities(!value);
                core.setAccessExternalDTD(value ? "" : "all");
                break;

            // Unsupported features
            case "http://xml.org/sax/features/unicode-normalization-checking":
                if (value) {
                    throw new SAXNotSupportedException("Feature not supported: " + name);
                }
                // Allow setting to false (no-op, already false)
                break;

            default:
                throw new SAXNotRecognizedException("Feature not recognized: " + name);
        }
    }

    /**
     * Returns the value of a parser property.
     *
     * <p>Gonzalez supports the following properties:
     *
     * <table class="striped">
     * <caption>Supported properties</caption>
     * <thead>
     * <tr><th>Property URI</th><th>Type</th><th>Description</th></tr>
     * </thead>
     * <tbody>
     * <tr><td>{@code http://xml.org/sax/properties/lexical-handler}</td>
     *     <td>{@link org.xml.sax.ext.LexicalHandler}</td>
     *     <td>Handler for lexical events (comments, CDATA, entity boundaries)</td></tr>
     * <tr><td>{@code http://xml.org/sax/properties/declaration-handler}</td>
     *     <td>{@link org.xml.sax.ext.DeclHandler}</td>
     *     <td>Handler for DTD declaration events (element, attribute, entity declarations)</td></tr>
     * <tr><td>{@code http://javax.xml.XMLConstants/property/accessExternalDTD}</td>
     *     <td>{@link String}</td>
     *     <td>Comma-separated allow-list of protocols for external DTD and entity
     *     access; empty by default (no external access)</td></tr>
     * <tr><td>{@code http://www.nongnu.org/gonzalez/properties/entity-expansion-limit}</td>
     *     <td>{@link Integer}</td>
     *     <td>Maximum entity expansions per document; 64,000 by default and
     *     zero for unlimited</td></tr>
     * <tr><td>{@code jdk.xml.entityExpansionLimit}</td>
     *     <td>{@link Integer}</td>
     *     <td>The same limit under its JDK name</td></tr>
     * <tr><td>{@code jdk.xml.maxElementDepth}</td>
     *     <td>{@link Integer}</td>
     *     <td>Maximum element nesting depth; 1000 by default</td></tr>
     * <tr><td>{@code jdk.xml.maxXMLNameLimit}</td>
     *     <td>{@link Integer}</td>
     *     <td>Maximum length of a name in characters; 4096 by default</td></tr>
     * <tr><td>{@code jdk.xml.elementAttributeLimit}</td>
     *     <td>{@link Integer}</td>
     *     <td>Maximum number of attributes of one element; 10,000 by
     *     default</td></tr>
     * </tbody>
     * </table>
     *
     * <p>The four {@code jdk.xml} limits are the JDK's own property names
     * for the limits of {@link XMLParser}. Each is also recognised under
     * its older name, {@code http://www.oracle.com/xml/jaxp/properties/}
     * followed by the part after {@code jdk.xml.}. They may be set with an
     * Integer or with a String holding a decimal number, zero or less
     * means unlimited, and they are read back as an Integer. The defaults
     * are Gonzalez's and not those of the JDK in use, and the
     * {@code jdk.xml} system properties are not consulted.
     *
     * @param name the property URI
     * @return the current value of the property
     * @throws SAXNotRecognizedException if the property is not recognized
     * @throws SAXNotSupportedException if the property cannot be read
     */
    @Override
    public Object getProperty(String name) throws SAXNotRecognizedException, SAXNotSupportedException {
        if ("http://xml.org/sax/properties/lexical-handler".equals(name)) {
            return lexicalHandler;
        }
        if ("http://xml.org/sax/properties/declaration-handler".equals(name)) {
            return declHandler;
        }
        if ("http://javax.xml.XMLConstants/property/accessExternalDTD".equals(name)) {
            return core.getAccessExternalDTD();
        }
        if ("http://www.nongnu.org/gonzalez/properties/entity-expansion-limit".equals(name)) {
            return core.getEntityExpansionLimit();
        }
        switch (limitNamed(name)) {
            case LIMIT_ENTITY_EXPANSION:
                return core.getEntityExpansionLimit();
            case LIMIT_ELEMENT_DEPTH:
                return core.getMaxElementDepth();
            case LIMIT_NAME_LENGTH:
                return core.getMaxNameLength();
            case LIMIT_ATTRIBUTES:
                return core.getMaxAttributesPerElement();
            default:
                throw new SAXNotRecognizedException("Property not recognized: " + name);
        }
    }

    // The limits of XMLParser that the JDK has a property name for.
    private static final int LIMIT_NONE = -1;
    private static final int LIMIT_ENTITY_EXPANSION = 0;
    private static final int LIMIT_ELEMENT_DEPTH = 1;
    private static final int LIMIT_NAME_LENGTH = 2;
    private static final int LIMIT_ATTRIBUTES = 3;

    private static final String JDK_LIMIT_PREFIX = "jdk.xml.";
    private static final String LEGACY_LIMIT_PREFIX = "http://www.oracle.com/xml/jaxp/properties/";

    /**
     * Identifies a JDK limit property, under its current or its older
     * name.
     *
     * @return one of the {@code LIMIT_} constants; {@link #LIMIT_NONE} if
     *         the name is not that of a limit this parser has
     */
    private static int limitNamed(String name) {
        String limit;
        if (name == null) {
            return LIMIT_NONE;
        } else if (name.startsWith(JDK_LIMIT_PREFIX)) {
            limit = name.substring(JDK_LIMIT_PREFIX.length());
        } else if (name.startsWith(LEGACY_LIMIT_PREFIX)) {
            limit = name.substring(LEGACY_LIMIT_PREFIX.length());
        } else {
            return LIMIT_NONE;
        }
        switch (limit) {
            case "entityExpansionLimit":
                return LIMIT_ENTITY_EXPANSION;
            case "maxElementDepth":
                return LIMIT_ELEMENT_DEPTH;
            case "maxXMLNameLimit":
                return LIMIT_NAME_LENGTH;
            case "elementAttributeLimit":
                return LIMIT_ATTRIBUTES;
            default:
                return LIMIT_NONE;
        }
    }

    /**
     * Reads the value given for a limit property: an Integer, or a String
     * holding a decimal number as JAXP callers commonly pass.
     */
    private static int limitValue(String name, Object value) throws SAXNotSupportedException {
        if (value instanceof Integer) {
            return ((Integer) value).intValue();
        }
        if (value instanceof String) {
            try {
                return Integer.parseInt(((String) value).trim());
            } catch (NumberFormatException e) {
                throw new SAXNotSupportedException("Value of " + name + " is not a number: " + value);
            }
        }
        throw new SAXNotSupportedException("Value of " + name + " must be an Integer or a numeric String");
    }

    /**
     * Sets the value of a parser property.
     *
     * <p>See {@link #getProperty(String)} for the list of supported properties.
     *
     * @param name the property URI
     * @param value the new value for the property
     * @throws SAXNotRecognizedException if the property is not recognized
     * @throws SAXNotSupportedException if the property cannot be set to the
     *         specified value
     */
    @Override
    public void setProperty(String name, Object value) throws SAXNotRecognizedException, SAXNotSupportedException {
        if ("http://xml.org/sax/properties/lexical-handler".equals(name)) {
            if (value == null || value instanceof LexicalHandler) {
                lexicalHandler = (LexicalHandler) value;
                if (adapter != null) {
                    adapter.setLexicalHandler(lexicalHandler);
                }
                return;
            }
            throw new SAXNotSupportedException("Value must be a LexicalHandler");
        }
        if ("http://xml.org/sax/properties/declaration-handler".equals(name)) {
            if (value == null || value instanceof DeclHandler) {
                declHandler = (DeclHandler) value;
                if (adapter != null) {
                    adapter.setDeclHandler(declHandler);
                }
                return;
            }
            throw new SAXNotSupportedException("Value must be a DeclHandler");
        }
        if ("http://javax.xml.XMLConstants/property/accessExternalDTD".equals(name)) {
            if (value instanceof String) {
                core.setAccessExternalDTD((String) value);
                return;
            }
            throw new SAXNotSupportedException("Value must be a String");
        }
        if ("http://www.nongnu.org/gonzalez/properties/entity-expansion-limit".equals(name)) {
            if (value instanceof Integer) {
                core.setEntityExpansionLimit((Integer) value);
                return;
            }
            throw new SAXNotSupportedException("Value must be an Integer");
        }
        int limit = limitNamed(name);
        if (limit == LIMIT_NONE) {
            throw new SAXNotRecognizedException("Property not recognized: " + name);
        }
        int limitValue = limitValue(name, value);
        switch (limit) {
            case LIMIT_ENTITY_EXPANSION:
                core.setEntityExpansionLimit(limitValue);
                break;
            case LIMIT_ELEMENT_DEPTH:
                core.setMaxElementDepth(limitValue);
                break;
            case LIMIT_NAME_LENGTH:
                core.setMaxNameLength(limitValue);
                break;
            default:
                core.setMaxAttributesPerElement(limitValue);
                break;
        }
    }

    // ========================================================================
    // Parser Reuse
    // ========================================================================

    /**
     * Resets the parser state to allow reuse for parsing another document.
     *
     * <p>This method clears all parsing state,
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
        // The adapter is single-document, like the pipeline inside the
        // native parser: prepare() makes a new one from the handlers still
        // held here when the next document starts.
        core.reset();
        adapter = null;
        started = false;
    }

    // ========================================================================
    // Streaming API (Advanced Usage)
    // ========================================================================

    /**
     * Sets the public identifier for the document.
     *
     * <p>The public identifier is used for error reporting and may be used
     * by entity resolvers.
     *
     * <p>If parsing via {@link #parse(InputSource)}, the public ID from the
     * InputSource takes precedence over this setting.
     *
     * @param publicId the public identifier, or null if not available
     */
    public void setPublicId(String publicId) {
        core.setPublicId(publicId);
    }

    /**
     * Sets the system identifier for the document.
     *
     * <p>The system identifier (typically a URL) is used for resolving
     * relative URIs and for error reporting.
     *
     * <p>If parsing via {@link #parse(InputSource)}, the system ID from the
     * InputSource takes precedence over this setting.
     *
     * @param systemId the system identifier, or null if not available
     */
    public void setSystemId(String systemId) {
        core.setSystemId(systemId);
    }

    /**
     * Receives raw byte data for parsing.
     *
     * <p>This is an advanced API that allows streaming data to the parser
     * in chunks without using an InputStream. The data buffer should be
     * prepared for reading (position set to start of data, limit set to
     * end of data). This delegates to {@link XMLParser#receive(ByteBuffer)}.
     *
     * <p>Multiple invocations of this method may occur to supply the
     * complete document content. On return the buffer's position is the
     * first byte the parser has not consumed. That is at most the few
     * bytes of an incomplete character, and the next call must begin with
     * them: compact the buffer and read more data in after them. Clearing
     * the buffer, or passing a different one holding only the new data,
     * loses those bytes, and the next call then throws
     * {@link IllegalStateException}.
     *
     * <p><b>Usage:</b>
     * <pre>
     * Parser parser = new Parser();
     * parser.setContentHandler(handler);
     * parser.setSystemId("http://example.com/doc.xml");
     *
     * ByteBuffer buffer = ByteBuffer.allocate(8192);
     * while (channel.read(buffer) != -1) {
     *     buffer.flip();
     *     parser.receive(buffer);
     *     buffer.compact(); // keep any bytes not yet consumed
     * }
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
     * @throws IllegalStateException if called after {@link #close()}, or
     *         if the bytes the previous call left unconsumed are not at
     *         the start of this buffer
     */
    public void receive(ByteBuffer data) throws SAXException {
        if (!started) {
            prepare();
        }
        core.receive(data);
    }

    /**
     * Signals that all data has been received and completes parsing.
     *
     * <p>This method must be called after the last {@link #receive} call
     * to signal the end of the document. The parser will process any
     * remaining buffered data and generate final SAX events (such as
     * endDocument).
     *
     * <p>After this method is called, you can call {@link #reset()} to
     * prepare the parser for reuse with another document.
     *
     * <p>If there is incomplete data (e.g., an unclosed element, or no
     * data at all), this method will throw a SAXException.
     *
     * @throws SAXException if there is incomplete or invalid data
     */
    public void close() throws SAXException {
        if (!started) {
            prepare();
        }
        // Validates that parsing is complete (no unclosed constructs).
        core.close();
    }

    /**
     * Gets the public identifier of the document.
     *
     * @return the public identifier, or null if not set
     */
    public String getPublicId() {
        return core.getPublicId();
    }

    /**
     * Gets the system identifier of the document.
     *
     * @return the system identifier, or null if not set
     */
    public String getSystemId() {
        return core.getSystemId();
    }

    // ========================================================================
    // PSVIProvider Implementation
    // ========================================================================
    
    /**
     * Returns the validation status of the current element.
     *
     * <p>Currently returns {@link Validity#NOT_KNOWN} since Gonzalez does not
     * perform full schema validation. DTD validation (when enabled) validates
     * content models but does not set element-level validity status.
     *
     * @return the validity status, always {@code NOT_KNOWN} currently
     */
    @Override
    public Validity getValidity() {
        // Full validity tracking requires schema validation
        // DTD validation validates constraints but doesn't set validity per-element
        return Validity.NOT_KNOWN;
    }
    
    /**
     * Returns the schema language used for validation.
     *
     * <p>Returns {@link ValidationSource#DTD} if a DOCTYPE declaration was
     * encountered (regardless of whether validation is enabled), otherwise
     * returns {@link ValidationSource#NONE}.
     *
     * @return the validation source
     */
    @Override
    public ValidationSource getValidationSource() {
        return core.hasDoctype()
            ? ValidationSource.DTD
            : ValidationSource.NONE;
    }
    
    /**
     * Returns the DTD-declared type for an attribute.
     *
     * <p>This method can be called during {@code startElement} callbacks to
     * retrieve the DTD type (ID, IDREF, IDREFS, NMTOKEN, NMTOKENS, ENTITY,
     * ENTITIES, NOTATION, CDATA, or enumeration) for an attribute.
     *
     * <p>If no DTD was declared or the attribute is not declared in the DTD,
     * returns "CDATA" as per SAX specification.
     *
     * @param attrIndex the attribute index (0-based)
     * @return the attribute type string, or "CDATA" if not declared
     */
    @Override
    public String getDTDAttributeType(int attrIndex) {
        // The adapter implements Attributes2 over the element currently
        // being reported (see SAXAdapter's class Javadoc).
        if (adapter != null && attrIndex >= 0 && attrIndex < adapter.getLength()) {
            return adapter.getType(attrIndex);
        }
        return "CDATA";
    }
    
    /**
     * Returns the typed value of the current element.
     *
     * <p>Currently returns {@code null} since DTD does not provide element
     * type information. This would be populated when XSD validation is active.
     *
     * @return the typed value, or null if not available
     */
    @Override
    public TypedValue getElementTypedValue() {
        // DTD does not provide element type information
        // XSD implementation would populate this
        return null;
    }
    
    /**
     * Returns the typed value of an attribute.
     *
     * <p>Currently returns {@code null}. Future XSD implementation would
     * convert attribute values to their typed representations based on
     * schema type declarations.
     *
     * @param attrIndex the attribute index (0-based)
     * @return the typed value, or null if not available
     */
    @Override
    public TypedValue getAttributeTypedValue(int attrIndex) {
        // DTD types don't map cleanly to typed values
        // XSD implementation would populate this
        return null;
    }
    
    /**
     * Returns the XSD type definition for the current element.
     *
     * <p>Currently returns {@code null} since XSD validation is not yet
     * implemented. When XSD validation is active, this would return the
     * Xerces XSTypeDefinition object.
     *
     * @return the XSD type definition, or null if not available
     */
    @Override
    public Object getXSDTypeDefinition() {
        // XSD validation not implemented yet
        return null;
    }
    
    /**
     * Returns whether the current element has xsi:nil="true".
     *
     * <p>Currently returns {@code null} since XSD validation is not yet
     * implemented. When XSD validation is active, this would return
     * {@code true} or {@code false} based on the xsi:nil attribute.
     *
     * @return true if nilled, false if not nilled, null if unknown
     */
    @Override
    public Boolean isNil() {
        // xsi:nil handling requires XSD validation
        return null;
    }
    
    /**
     * Command-line entry point for parsing and pretty-printing XML.
     *
     * <p>By default, parses each XML file and writes indented output to
     * stdout using {@link XMLWriter} with 2-space indentation. This acts
     * as both a well-formedness check and a pretty-printer.
     *
     * <p>With the {@code -v} flag, dumps all SAX events with locator
     * information instead, useful for debugging parser behavior.
     *
     * <p>Usage: {@code java org.bluezoo.gonzalez.Parser [-v] file1.xml [file2.xml ...]}
     *
     * @param args optional -v flag followed by paths to XML files to parse
     * @throws Exception if parsing fails
     */
    public static void main(String[] args) throws Exception {
        boolean verbose = false;
        int fileStart = 0;
        if (args.length > 0 && "-v".equals(args[0])) {
            verbose = true;
            fileStart = 1;
        }
        if (fileStart >= args.length) {
            System.err.println("Usage: java org.bluezoo.gonzalez.Parser [-v] file1.xml [file2.xml ...]");
            System.exit(1);
        }
        Parser parser = new Parser();
        if (verbose) {
            parser.setContentHandler(new VerboseHandler());
        } else {
            WritableByteChannel out = Channels.newChannel(System.out);
            XMLWriter writer = new XMLWriter(out);
            writer.setIndentConfig(IndentConfig.spaces2());
            XMLWriterSAXAdapter adapter = new XMLWriterSAXAdapter(writer);
            parser.setContentHandler(adapter);
            parser.setProperty("http://xml.org/sax/properties/lexical-handler", adapter);
            parser.setProperty("http://xml.org/sax/properties/declaration-handler", adapter);
            parser.setDTDHandler(adapter);
        }
        for (int i = fileStart; i < args.length; i++) {
            File file = new File(args[i]);
            try (InputStream in = new FileInputStream(file)) {
                InputSource src = new InputSource(in);
                src.setSystemId(file.toURI().toString());
                parser.parse(src);
            }
        }
    }

    /**
     * SAX event handler that prints every event with locator information.
     * Used by the {@code -v} command-line flag for debugging.
     */
    static class VerboseHandler extends org.xml.sax.helpers.DefaultHandler {

        private Locator locator;

        @Override
        public void setDocumentLocator(Locator locator) {
            this.locator = locator;
            printEvent("setDocumentLocator",
                    "systemId=" + locator.getSystemId()
                    + ", publicId=" + locator.getPublicId());
        }

        @Override
        public void startDocument() throws SAXException {
            printEvent("startDocument", "");
        }

        @Override
        public void endDocument() throws SAXException {
            printEvent("endDocument", "");
        }

        @Override
        public void startElement(String uri, String localName, String qName,
                                 Attributes attributes) throws SAXException {
            StringBuilder attrs = new StringBuilder();
            for (int i = 0; i < attributes.getLength(); i++) {
                if (i > 0) {
                    attrs.append(", ");
                }
                attrs.append(attributes.getQName(i));
                attrs.append("=\"");
                attrs.append(attributes.getValue(i));
                attrs.append("\"");
            }
            String detail = qName;
            if (attrs.length() > 0) {
                detail = qName + " [" + attrs + "]";
            }
            printEvent("startElement", detail);
        }

        @Override
        public void endElement(String uri, String localName, String qName)
                throws SAXException {
            printEvent("endElement", qName);
        }

        @Override
        public void characters(char[] ch, int start, int length)
                throws SAXException {
            String text = new String(ch, start, length);
            String display = escapeWhitespace(text);
            if (display.length() > 60) {
                display = display.substring(0, 60) + "...";
            }
            printEvent("characters", "\"" + display + "\"");
        }

        @Override
        public void ignorableWhitespace(char[] ch, int start, int length)
                throws SAXException {
            String text = new String(ch, start, length);
            String display = escapeWhitespace(text);
            printEvent("ignorableWhitespace", "\"" + display + "\"");
        }

        @Override
        public void processingInstruction(String target, String data)
                throws SAXException {
            printEvent("processingInstruction", target + " \"" + data + "\"");
        }

        @Override
        public void skippedEntity(String name) throws SAXException {
            printEvent("skippedEntity", name);
        }

        @Override
        public void startPrefixMapping(String prefix, String uri)
                throws SAXException {
            printEvent("startPrefixMapping", prefix + " -> " + uri);
        }

        @Override
        public void endPrefixMapping(String prefix) throws SAXException {
            printEvent("endPrefixMapping", prefix);
        }

        private static String escapeWhitespace(String text) {
            StringBuilder sb = new StringBuilder(text.length());
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '\n') {
                    sb.append("\\n");
                } else if (c == '\r') {
                    sb.append("\\r");
                } else if (c == '\t') {
                    sb.append("\\t");
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }

        private void printEvent(String eventName, String details) {
            String location = "";
            if (locator != null) {
                location = String.format("[%4d:%-3d] ",
                        locator.getLineNumber(), locator.getColumnNumber());
            }
            System.out.println(location + eventName + ": " + details);
            System.out.flush();
        }
    }

    /**
     * SAX adapter that delegates SAX events to XMLWriter's write* API.
     * Used by the CLI tool for pretty-printing XML files.
     */
    static class XMLWriterSAXAdapter extends org.xml.sax.helpers.DefaultHandler
            implements org.xml.sax.ext.LexicalHandler, DTDHandler,
                       org.xml.sax.ext.DeclHandler {

        private final XMLWriter writer;
        private final java.util.List<String[]> pendingPrefixMappings =
            new java.util.ArrayList<String[]>();

        XMLWriterSAXAdapter(XMLWriter writer) {
            this.writer = writer;
        }

        @Override
        public void setDocumentLocator(Locator locator) {
        }

        @Override
        public void endDocument() throws SAXException {
            try {
                writer.close();
            } catch (IOException e) {
                throw new SAXException(e);
            }
        }

        @Override
        public void startPrefixMapping(String prefix, String uri) {
            pendingPrefixMappings.add(new String[] { prefix, uri });
        }

        @Override
        public void startElement(String uri, String localName, String qName,
                Attributes atts) throws SAXException {
            try {
                String prefix = extractPrefix(qName);
                if (prefix != null && !prefix.isEmpty()) {
                    writer.writeStartElement(prefix, localName,
                        uri != null ? uri : "");
                } else if (uri != null && !uri.isEmpty()) {
                    writer.writeStartElement("", localName, uri);
                } else {
                    writer.writeStartElement(localName);
                }
                for (int i = 0; i < pendingPrefixMappings.size(); i++) {
                    String[] pm = pendingPrefixMappings.get(i);
                    if (pm[0].isEmpty()) {
                        writer.writeDefaultNamespace(pm[1]);
                    } else {
                        writer.writeNamespace(pm[0], pm[1]);
                    }
                }
                pendingPrefixMappings.clear();
                int len = atts.getLength();
                for (int i = 0; i < len; i++) {
                    String attrQName = atts.getQName(i);
                    if (attrQName != null && !attrQName.isEmpty()) {
                        writer.writeAttribute(attrQName, atts.getValue(i));
                    } else {
                        writer.writeAttribute(atts.getLocalName(i), atts.getValue(i));
                    }
                }
            } catch (IOException e) {
                throw new SAXException(e);
            }
        }

        @Override
        public void endElement(String uri, String localName, String qName)
                throws SAXException {
            try {
                writer.writeEndElement();
            } catch (IOException e) {
                throw new SAXException(e);
            }
        }

        @Override
        public void characters(char[] ch, int start, int length)
                throws SAXException {
            try {
                writer.writeCharacters(ch, start, length);
            } catch (IOException e) {
                throw new SAXException(e);
            }
        }

        @Override
        public void processingInstruction(String target, String data)
                throws SAXException {
            try {
                writer.writeProcessingInstruction(target, data);
            } catch (IOException e) {
                throw new SAXException(e);
            }
        }

        @Override
        public void skippedEntity(String name) throws SAXException {
            try {
                writer.writeEntityRef(name);
            } catch (IOException e) {
                throw new SAXException(e);
            }
        }

        // LexicalHandler

        @Override
        public void comment(char[] ch, int start, int length) throws SAXException {
            try {
                writer.writeComment(new String(ch, start, length));
            } catch (IOException e) {
                throw new SAXException(e);
            }
        }

        @Override
        public void startCDATA() throws SAXException {
            try {
                writer.writeStartCDATA();
            } catch (IOException e) {
                throw new SAXException(e);
            }
        }

        @Override
        public void endCDATA() throws SAXException {
            try {
                writer.writeEndCDATA();
            } catch (IOException e) {
                throw new SAXException(e);
            }
        }

        @Override
        public void startDTD(String name, String publicId, String systemId)
                throws SAXException {
            try {
                writer.writeStartDTD(name, publicId, systemId);
            } catch (IOException e) {
                throw new SAXException(e);
            }
        }

        @Override
        public void endDTD() throws SAXException {
            try {
                writer.writeEndDTD();
            } catch (IOException e) {
                throw new SAXException(e);
            }
        }

        @Override
        public void startEntity(String name) {
            if ("[dtd]".equals(name)) {
                writer.startExternalSubset();
            }
        }

        @Override
        public void endEntity(String name) {
            if ("[dtd]".equals(name)) {
                writer.endExternalSubset();
            }
        }

        // DTDHandler

        @Override
        public void notationDecl(String name, String publicId, String systemId)
                throws SAXException {
            try {
                writer.writeNotationDecl(name, publicId, systemId);
            } catch (IOException e) {
                throw new SAXException(e);
            }
        }

        @Override
        public void unparsedEntityDecl(String name, String publicId,
                String systemId, String notationName) throws SAXException {
            try {
                writer.writeUnparsedEntityDecl(name, publicId, systemId, notationName);
            } catch (IOException e) {
                throw new SAXException(e);
            }
        }

        // DeclHandler

        @Override
        public void elementDecl(String name, String model) throws SAXException {
            try {
                writer.writeElementDecl(name, model);
            } catch (IOException e) {
                throw new SAXException(e);
            }
        }

        @Override
        public void attributeDecl(String eName, String aName, String type,
                String mode, String value) throws SAXException {
            try {
                writer.writeAttributeDecl(eName, aName, type, mode, value);
            } catch (IOException e) {
                throw new SAXException(e);
            }
        }

        @Override
        public void internalEntityDecl(String name, String value)
                throws SAXException {
            try {
                writer.writeInternalEntityDecl(name, value);
            } catch (IOException e) {
                throw new SAXException(e);
            }
        }

        @Override
        public void externalEntityDecl(String name, String publicId,
                String systemId) throws SAXException {
            try {
                writer.writeExternalEntityDecl(name, publicId, systemId);
            } catch (IOException e) {
                throw new SAXException(e);
            }
        }

        private static String extractPrefix(String qName) {
            if (qName == null) {
                return null;
            }
            int colon = qName.indexOf(':');
            if (colon > 0) {
                return qName.substring(0, colon);
            }
            return null;
        }
    }

}


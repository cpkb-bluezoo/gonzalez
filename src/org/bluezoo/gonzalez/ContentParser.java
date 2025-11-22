/*
 * ContentParser.java
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
import java.nio.CharBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import org.xml.sax.ContentHandler;
import org.xml.sax.DTDHandler;
import org.xml.sax.EntityResolver;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.ext.LexicalHandler;

/**
 * XML parser.
 * This is a consumer of a stream of tokens.
 * Each event informs the parser of a token type and the content of the
 * token.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ContentParser implements TokenConsumer {

    enum State {
        INIT,                       // Initial state, expecting prolog or root element
        PROLOG,                     // In prolog, before root element
        ELEMENT_START,              // After LT, expecting element name
        ELEMENT_NAME,               // Read element name, expecting S/GT/END_EMPTY_ELEMENT/COLON
        ELEMENT_ATTRS,              // In element, expecting attributes or GT/END_EMPTY_ELEMENT
        ATTRIBUTE_NAME,             // Read attribute name, expecting EQ
        ATTRIBUTE_EQ,               // Read EQ, expecting quote
        ATTRIBUTE_VALUE_START,      // Read quote, expecting attribute value
        ATTRIBUTE_VALUE,            // Reading attribute value
        ELEMENT_CONTENT,            // Inside element, expecting content or end tag
        END_ELEMENT_START,          // After START_END_ELEMENT (</), expecting name
        END_ELEMENT_NAME,           // Read end tag name, expecting GT
        PI_TARGET,                  // After START_PI, expecting PI target
        PI_CONTENT,                 // After PI target, expecting PI content or END_PI
        COMMENT,                    // After START_COMMENT, expecting comment content
        CDATA_SECTION,              // After START_CDATA, expecting CDATA content
        AFTER_ROOT                  // After root element closed, expecting only whitespace/comments/PIs
    }

    private State state = State.INIT;
    private Locator locator;
    
    // Current tokenizer state (updated via tokenizerState callback)
    private TokenizerState currentTokenizerState = TokenizerState.CONTENT;
    
    // Entity expansion depth - tracks how deeply nested we are in entity expansions
    // Used to enforce WFC: Parsed Entity (element nesting must respect entity boundaries)
    private int entityExpansionDepth = 0;

    // Feature flags (set from Parser)
    private boolean namespacesEnabled = true;              // SAX2 default
    private boolean namespacePrefixesEnabled = false;      // SAX2 default
    private boolean validationEnabled = false;             // Off by default
    private boolean externalGeneralEntitiesEnabled = true; // On by default
    private boolean externalParameterEntitiesEnabled = true; // On by default
    private boolean resolveDTDURIsEnabled = true;          // On by default
    private boolean stringInterning = true;                // On by default - intern strings passed to handlers
    
    /**
     * SAX content handler for receiving document events.
     */
    private ContentHandler contentHandler;
    
    /**
     * SAX DTD handler for receiving DTD events.
     */
    private DTDHandler dtdHandler;
    
    /**
     * SAX lexical handler for receiving lexical events.
     */
    private LexicalHandler lexicalHandler;
    
    /**
     * SAX error handler for error reporting.
     */
    private ErrorHandler errorHandler;
    
    /**
     * SAX entity resolver for resolving external entities.
     */
    private EntityResolver entityResolver;
    
    /**
     * Helper for entity resolution (created lazily when needed).
     */
    private EntityResolutionHelper entityResolutionHelper;
    
    /**
     * Default entity resolver (created lazily when needed).
     * Used when no user-specified resolver is set.
     */
    private DefaultEntityResolver defaultEntityResolver;
    
    /**
     * Depth of external entity processing.
     * 0 = main document
     * >0 = inside external entity (DTD subset or general entity)
     * Used to reject DOCTYPE declarations in external entities.
     */
    private int externalEntityDepth = 0;
    
    /**
     * Package-private accessor to check if we're currently processing an external entity.
     * Used by DTDParser to enforce that conditional sections are only allowed in external DTD subsets.
     */
    boolean isProcessingExternalEntity() {
        return externalEntityDepth > 0;
    }
    
    /**
     * Lazily-constructed DTD parser for processing DOCTYPE declarations.
     * Only allocated when a DOCTYPE is encountered.
     */
    private DTDParser dtdParser;
    
    /**
     * Attributes for the current element.
     */
    private SAXAttributes attributes;
    
    /**
     * Namespace scope tracker for managing prefix-URI bindings.
     * Only used when namespacesEnabled is true.
     */
    private NamespaceScopeTracker namespaceTracker;
    
    /**
     * Flag tracking whether namespace context was pushed for current element.
     * Used to avoid double-pushing when attributes are present.
     */
    private boolean namespaceContextPushed;
    
    /**
     * Current element name being processed.
     */
    private String currentElementName;
    
    /**
     * Current attribute name being processed.
     */
    private String currentAttributeName;
    
    /**
     * Track if whitespace was seen after previous attribute value.
     * Required for well-formedness: attributes must be separated by whitespace.
     */
    private boolean sawWhitespaceAfterAttributeValue;
    
    /**
     * Current attribute value being accumulated.
     */
    private StringBuilder currentAttributeValue;
    
    /**
     * Current attribute quote character (QUOT or APOS).
     */
    private Token currentAttributeQuote;
    
    /**
     * Current PI target.
     */
    private String currentPITarget;
    
    /**
     * Current PI data being accumulated.
     */
    private StringBuilder currentPIData;
    
    /**
     * Current comment text being accumulated.
     */
    private StringBuilder currentCommentText;
    
    /**
     * Current CDATA text being accumulated.
     */
    private StringBuilder currentCDATAText;
    
    /**
     * Whether startDocument has been called.
     */
    private boolean documentStarted;
    
    /**
     * Element depth (0 = outside root, 1 = root element, 2+ = nested).
     */
    private int elementDepth;
    
    /**
     * Stack of element validation contexts.
     * Used for both well-formedness checking (end tag matching) and validation (content model).
     * Each context contains the element name and its validator (if validation is enabled).
     */
    private java.util.Deque<ElementValidationContext> elementStack;
    
    /**
     * Attribute validator for validation mode (only used when validationEnabled).
     */
    private AttributeValidator attributeValidator;

    @Override
    public void setLocator(Locator locator) {
        this.locator = locator;
    }

    /**
     * Gets the locator for this parser.
     * Package-private to allow DTDParser access.
     * 
     * @return the locator, or null if not set
     */
    Locator getLocator() {
        return locator;
    }

    /**
     * Sets the content handler for receiving document events.
     * @param handler the content handler
     */
    /**
     * Closes the parser and validates that parsing is complete with no unclosed constructs.
     * Called after all input has been processed.
     * 
     * @throws SAXException if there are unclosed constructs
     */
    public void close() throws SAXException {
        // Check for unclosed constructs
        switch (state) {
            case CDATA_SECTION:
 throw fatalError("Unclosed CDATA section at end of document");
                
            case COMMENT:
 throw fatalError("Unclosed comment at end of document");
                
            case PI_TARGET:
            case PI_CONTENT:
 throw fatalError("Unclosed processing instruction at end of document");
                
            case ELEMENT_START:
            case ELEMENT_NAME:
            case ELEMENT_ATTRS:
            case ATTRIBUTE_NAME:
            case ATTRIBUTE_EQ:
            case ATTRIBUTE_VALUE_START:
            case ATTRIBUTE_VALUE:
            case END_ELEMENT_START:
            case END_ELEMENT_NAME:
 throw fatalError("Unclosed element at end of document");
                
            case ELEMENT_CONTENT:
                // Check if we're still inside an element (elementDepth > 0)
                if (elementDepth > 0) {
 throw fatalError("Unclosed element at end of document (depth=" + elementDepth + ")");
                }
                break;
                
            case AFTER_ROOT:
                // This is fine - we've closed the root element
                break;
                
            case INIT:
            case PROLOG:
 throw fatalError("No root element found in document");
                
            default:
                // Other states might indicate incomplete parsing
                break;
        }
    }
    
    public void setContentHandler(ContentHandler handler) {
        this.contentHandler = handler;
    }

    /**
     * Gets the content handler.
     * @return the content handler, or null if not set
     */
    public ContentHandler getContentHandler() {
        return contentHandler;
    }

    /**
     * Sets the DTD handler for receiving DTD events.
     * @param handler the DTD handler
     */
    public void setDTDHandler(DTDHandler handler) {
        this.dtdHandler = handler;
    }

    /**
     * Gets the DTD handler.
     * @return the DTD handler, or null if not set
     */
    public DTDHandler getDTDHandler() {
        return dtdHandler;
    }

    /**
     * Gets the DTD parser used for processing DOCTYPE declarations.
     * This is only available after a DOCTYPE has been encountered during parsing.
     * @return the DTDParser, or null if no DOCTYPE has been processed
     */
    public DTDParser getDTDParser() {
        return dtdParser;
    }

    /**
     * Sets the lexical handler for receiving lexical events.
     * @param handler the lexical handler
     */
    public void setLexicalHandler(LexicalHandler handler) {
        this.lexicalHandler = handler;
    }

    /**
     * Gets the lexical handler.
     * @return the lexical handler, or null if not set
     */
    public LexicalHandler getLexicalHandler() {
        return lexicalHandler;
    }

    /**
     * Sets the error handler for error reporting.
     * @param handler the error handler
     */
    public void setErrorHandler(ErrorHandler handler) {
        this.errorHandler = handler;
    }

    /**
     * Gets the error handler.
     * @return the error handler, or null if not set
     */
    public ErrorHandler getErrorHandler() {
        return errorHandler;
    }

    /**
     * Sets the entity resolver for resolving external entities.
     * @param resolver the entity resolver
     */
    public void setEntityResolver(EntityResolver resolver) {
        this.entityResolver = resolver;
        // Clear the helper so it will be recreated with new resolver
        this.entityResolutionHelper = null;
    }

    /**
     * Gets the entity resolver.
     * @return the entity resolver, or null if not set
     */
    public EntityResolver getEntityResolver() {
        return entityResolver;
    }

    // ========================================================================
    // Feature Flag Getters/Setters (simple booleans, no URIs)
    // ========================================================================

    public boolean getNamespacesEnabled() {
        return namespacesEnabled;
    }

    public void setNamespacesEnabled(boolean enabled) {
        this.namespacesEnabled = enabled;
        // Initialize namespace tracker if enabling namespaces
        if (enabled && namespaceTracker == null) {
            namespaceTracker = new NamespaceScopeTracker();
        }
    }

    public boolean getNamespacePrefixesEnabled() {
        return namespacePrefixesEnabled;
    }

    public void setNamespacePrefixesEnabled(boolean enabled) {
        this.namespacePrefixesEnabled = enabled;
    }

    public boolean getValidationEnabled() {
        return validationEnabled;
    }

    public void setValidationEnabled(boolean enabled) {
        this.validationEnabled = enabled;
    }

    public boolean getExternalGeneralEntitiesEnabled() {
        return externalGeneralEntitiesEnabled;
    }

    public void setExternalGeneralEntitiesEnabled(boolean enabled) {
        this.externalGeneralEntitiesEnabled = enabled;
    }

    public boolean getExternalParameterEntitiesEnabled() {
        return externalParameterEntitiesEnabled;
    }

    public void setExternalParameterEntitiesEnabled(boolean enabled) {
        this.externalParameterEntitiesEnabled = enabled;
    }

    public boolean getResolveDTDURIsEnabled() {
        return resolveDTDURIsEnabled;
    }

    public void setResolveDTDURIsEnabled(boolean enabled) {
        this.resolveDTDURIsEnabled = enabled;
        // Clear entity resolution helper so it will be recreated with new setting
        if (entityResolutionHelper != null) {
            entityResolutionHelper = null;
        }
    }

    public boolean getStringInterning() {
        return stringInterning;
    }

    public void setStringInterning(boolean enabled) {
        this.stringInterning = enabled;
    }

    // ========================================================================
    // State Management
    // ========================================================================

    /**
     * Gets the default entity resolver, creating it lazily if needed.
     *
     * <p>The default resolver uses the systemId from the main document
     * as the base URL for resolving relative entity references. If the
     * main document has no systemId, the current directory is used.
     *
     * @return the default entity resolver
     */
    private DefaultEntityResolver getDefaultEntityResolver() {
        if (defaultEntityResolver == null) {
            // Get base URL from locator (main document's systemId)
            String baseSystemId = (locator != null) ? locator.getSystemId() : null;
            
            if (baseSystemId != null) {
                try {
                    java.net.URL baseURL = new java.net.URL(baseSystemId);
                    defaultEntityResolver = new DefaultEntityResolver(baseURL);
                } catch (java.net.MalformedURLException e) {
                    // Not a valid URL, use default (current directory)
                    defaultEntityResolver = new DefaultEntityResolver();
                }
            } else {
                // No base systemId, use current directory
                defaultEntityResolver = new DefaultEntityResolver();
            }
        }
        return defaultEntityResolver;
    }

    /**
     * Gets the entity resolution helper, creating it lazily if needed.
     *
     * <p>If a user-specified EntityResolver is set, it is used.
     * Otherwise, the default entity resolver is used.
     *
     * @return the entity resolution helper
     */
    private EntityResolutionHelper getEntityResolutionHelper() {
        if (entityResolutionHelper == null) {
            // Use user resolver if set, otherwise use default
            EntityResolver resolver = (entityResolver != null) 
                ? entityResolver 
                : getDefaultEntityResolver();
            
            entityResolutionHelper = new EntityResolutionHelper(
                resolver, locator, resolveDTDURIsEnabled);
        }
        return entityResolutionHelper;
    }

    /**
     * Processes an external entity by resolving it and parsing its content.
     *
     * <p>This method:
     * <ol>
     *   <li>Checks if external entities are enabled (general or parameter)</li>
     *   <li>Resolves the entity using EntityResolver/EntityResolver2</li>
     *   <li>Creates a nested Tokenizer for the entity stream</li>
     *   <li>Feeds the entity's InputStream to the tokenizer</li>
     *   <li>Returns control when entity is exhausted</li>
     * </ol>
     *
     * <p>The nested tokenizer sends tokens back to this parser's receive() method,
     * but with the entity's systemId and line/column positions in the Locator.
     *
     * <p>Includes recursion protection to prevent infinite entity loops.
     *
     * <p>This method is package-private to allow DTDParser to process external
     * DTD subsets.
     *
     * <p>Note: For DTD external subsets, this checks externalParameterEntitiesEnabled.
     * For general entity references in content, it checks externalGeneralEntitiesEnabled.
     *
     * @param name the entity name (for EntityResolver2), or null for DTD external subset
     * @param publicId the public identifier (may be null)
     * @param systemId the system identifier (may be null)
     * @throws SAXException if entity resolution or parsing fails
     * @throws IOException if an I/O error occurs reading the entity
     */
    void processExternalEntity(String name, String publicId, String systemId)
            throws SAXException, IOException {
        
        // Determine if this is a DTD external subset (name matches DOCTYPE name)
        // or a general entity reference (name is entity name)
        boolean isDTDSubset = (dtdParser != null && name != null && 
                               name.equals(dtdParser.getDoctypeName()));
        
        // Check appropriate feature flag
        if (isDTDSubset) {
            if (!externalParameterEntitiesEnabled) {
                // Skip DTD external subset
                return;
            }
        } else {
            if (!externalGeneralEntitiesEnabled) {
                // Skip general entity reference
                return;
            }
        }
        
        // Get entity resolution helper (always available, uses default if needed)
        EntityResolutionHelper helper = getEntityResolutionHelper();
        
        // Resolve entity
        InputSource source = helper.resolveEntity(name, publicId, systemId);
        if (source == null) {
            // Resolver returned null, use default resolution (skip for now)
            return;
        }
        
        // Get resolved system ID for recursion check
        String resolvedSystemId = source.getSystemId();
        if (resolvedSystemId == null) {
            resolvedSystemId = systemId; // Fallback to original
        }
        
        // Get current XML version from DTDParser's entity stack
        // If no DTD parser, assume XML 1.0
        boolean currentVersion = false;
        if (dtdParser != null && !dtdParser.entityStack.isEmpty()) {
            currentVersion = dtdParser.entityStack.peek().isXML11;
        }
        
        // Check for name-based recursion if this is a named entity (not DTD subset)
        if (name != null && dtdParser != null) {
            boolean isParameterEntity = isDTDSubset;
            for (EntityStackEntry ctx : dtdParser.entityStack) {
                // Only check named entities - DTD subsets have null entityName
                if (ctx.entityName != null && 
                    ctx.isParameterEntity == isParameterEntity && 
                    name.equals(ctx.entityName)) {
                    throw fatalError("Recursive entity reference: " + 
                        (isParameterEntity ? "%" : "&") + name + ";");
                }
            }
        }
        
        // Push context onto DTDParser's stack (for all external tokenization contexts)
        // This includes: DTD subsets (name=doctypeName), general entities (name=entityName)
        boolean isParameterEntity = isDTDSubset;
        EntityStackEntry entry = new EntityStackEntry(
            name, publicId, systemId, isParameterEntity, currentVersion, entityExpansionDepth);
        if (dtdParser != null) {
            dtdParser.entityStack.push(entry);
        }
        
        try {
            // Create nested tokenizer and decoder for external entity
            // Determine the initial state for the entity:
            // - External DTD subsets should start in DOCTYPE_INTERNAL (to parse DTD declarations)
            // - General entities should start in CONTENT (to parse element content)
            TokenizerState initialState = isDTDSubset ? TokenizerState.DOCTYPE_INTERNAL : TokenizerState.CONTENT;
            
            Tokenizer entityTokenizer = new Tokenizer(this);
            entityTokenizer.setInitialContext(initialState);
            entityTokenizer.setXML11(currentVersion); // Inherit parent's XML version
            
            ExternalEntityDecoder entityDecoder = new ExternalEntityDecoder(
                entityTokenizer,
                source.getPublicId(),
                resolvedSystemId,
                true // External entity
            );
            // Set XML version on decoder to match tokenizer
            entityDecoder.setVersion(currentVersion ? "1.1" : "1.0");
            
            // Get input stream from source
            InputStream inputStream = source.getByteStream();
            if (inputStream == null) {
                // TODO: Handle Reader (character stream)
 throw fatalError("Entity InputSource must have a byte stream");
            }
            
            // Feed entity data to decoder (same logic as Parser.parse())
            try {
                byte[] buffer = new byte[4096];
                int bytesRead;
                
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    ByteBuffer byteBuffer = ByteBuffer.wrap(buffer, 0, bytesRead);
                    entityDecoder.receive(byteBuffer);
                }
                
                // Signal end of entity
                entityDecoder.close();
                
                // If we were processing a DTD external subset, validate that all structures are closed
                if (dtdParser != null) {
                    dtdParser.validateExternalEntityClosed();
                }
                
            } finally {
                inputStream.close();
            }
            
            // Entity processing complete, continue with main stream
            
        } finally {
            // Pop context from stack
            if (dtdParser != null && !dtdParser.entityStack.isEmpty() && 
                dtdParser.entityStack.peek() == entry) {
                dtdParser.entityStack.pop();
            }
        }
    }

    /**
     * Resets the parser state to allow reuse for parsing another document.
     *
     * <p>This method clears all parsing state, allowing the same ContentParser
     * instance to be reused for multiple documents. Handler references
     * (ContentHandler, DTDHandler, etc.) are preserved.
     *
     * <p>This method resets:
     * <ul>
     *   <li>Parser state (back to INIT)</li>
     *   <li>Element depth (back to 0)</li>
     *   <li>Document started flag</li>
     *   <li>Current element/attribute/PI/comment/CDATA buffers</li>
     *   <li>DTD parser (null, allowing GC)</li>
     *   <li>Attributes (null, allowing GC)</li>
     *   <li>Entity resolution helper (null, will be recreated)</li>
     *   <li>Default entity resolver (null, will be recreated)</li>
     *   <li>Entity resolution stack (cleared)</li>
     * </ul>
     *
     * @throws SAXException if reset fails
     */
    public void reset() throws SAXException {
        // Reset state machine
        state = State.INIT;
        documentStarted = false;
        elementDepth = 0;
        
        // Clear DTD parser (allow GC)
        dtdParser = null;
        
        // Clear entity resolution helper (will be recreated if needed)
        entityResolutionHelper = null;
        
        // Clear default entity resolver (will be recreated with new base URL)
        defaultEntityResolver = null;
        
        // Clear entity resolution stack
        
        // Clear working state
        currentElementName = null;
        currentAttributeName = null;
        currentAttributeValue = null;
        currentAttributeQuote = null;
        currentPITarget = null;
        currentPIData = null;
        currentCommentText = null;
        currentCDATAText = null;
        attributes = null;
        
        // Clear validation stacks
        if (elementStack != null) {
            elementStack.clear();
        }
        if (attributeValidator != null) {
            attributeValidator.reset();
        }
        
        // Reset namespace tracker if namespaces enabled
        if (namespacesEnabled && namespaceTracker != null) {
            namespaceTracker.reset();
        }
    }

    @Override
    public void receive(Token token, CharBuffer data) throws SAXException {
        // DOCTYPE declarations are only allowed in the main document,
        // not in external entities. Check before delegating to DTDParser.
        if (token == Token.START_DOCTYPE && externalEntityDepth > 0) {
            throw fatalError(
                "DOCTYPE declaration not allowed in external entity " +
                "(must appear only in main document)");
        }
        
        // Check if we should delegate to DTDParser
        if (dtdParser != null && dtdParser.canReceive(token)) {
            dtdParser.receive(token, data);
            return;
        }
        
        // Handle token based on current state
        switch (state) {
            case INIT:
            case PROLOG:
                handleProlog(token, data);
                break;
                
            case ELEMENT_START:
                handleElementStart(token, data);
                break;
                
            case ELEMENT_NAME:
                handleElementName(token, data);
                break;
                
            case ELEMENT_ATTRS:
                handleElementAttrs(token, data);
                break;
                
            case ATTRIBUTE_NAME:
                handleAttributeName(token, data);
                break;
                
            case ATTRIBUTE_EQ:
                handleAttributeEq(token, data);
                break;
                
            case ATTRIBUTE_VALUE_START:
                handleAttributeValueStart(token, data);
                break;
                
            case ATTRIBUTE_VALUE:
                handleAttributeValue(token, data);
                break;
                
            case ELEMENT_CONTENT:
                handleElementContent(token, data);
                break;
                
            case END_ELEMENT_START:
                handleEndElementStart(token, data);
                break;
                
            case END_ELEMENT_NAME:
                handleEndElementName(token, data);
                break;
                
            case PI_TARGET:
                handlePITarget(token, data);
                break;
                
            case PI_CONTENT:
                handlePIContent(token, data);
                break;
                
            case COMMENT:
                handleComment(token, data);
                break;
                
            case CDATA_SECTION:
                handleCDATASection(token, data);
                break;
                
            case AFTER_ROOT:
                handleAfterRoot(token, data);
                break;
        }
    }
    
    /**
     * Handles tokens in the prolog (before root element).
     */
    private void handleProlog(Token token, CharBuffer data) throws SAXException {
        switch (token) {
            case S:
                // Whitespace in prolog, ignore
                break;
                
            case START_DOCTYPE:
                // Lazily construct DTDParser
                dtdParser = new DTDParser(this);
                dtdParser.setLocator(locator);
                dtdParser.setContentHandler(contentHandler);
                dtdParser.setDTDHandler(dtdHandler);
                dtdParser.setLexicalHandler(lexicalHandler);
                dtdParser.setErrorHandler(errorHandler);
                break;
                
            case START_PI:
                // Processing instruction in prolog
                currentPIData = new StringBuilder();
                state = State.PI_TARGET;
                break;
                
            case START_COMMENT:
                // Comment in prolog
                currentCommentText = new StringBuilder();
                state = State.COMMENT;
                break;
                
            case LT:
                // Start of root element
                if (!documentStarted && contentHandler != null) {
                    contentHandler.setDocumentLocator(locator);
                    contentHandler.startDocument();
                    documentStarted = true;
                }
                
                // Initialize element stack for well-formedness and validation
                if (elementStack == null) {
                    elementStack = new java.util.ArrayDeque<>();
                }
                
                state = State.ELEMENT_START;
                break;
                
            default:
 throw fatalError("Unexpected token in prolog: " + token);
        }
    }
    
    /**
     * Handles tokens after LT (expecting element name).
     */
    private void handleElementStart(Token token, CharBuffer data) throws SAXException {
        if (token == Token.NAME) {
            currentElementName = extractString(data);
            elementDepth++;
            
            // Push element onto stack for well-formedness and validation
            // (validator will be null if validation is disabled)
            if (elementStack != null) {
                elementStack.addLast(new ElementValidationContext(currentElementName, null, entityExpansionDepth));
            }

            // Record this element as a child of its parent (for validation)
            if (validationEnabled && dtdParser != null && elementDepth > 1) {
                // We're inside another element, record this as a child
                recordChildElement(currentElementName);
            }
            
            state = State.ELEMENT_NAME;
        } else {
 throw fatalError("Expected element name after '<', got: " + token);
        }
    }
    
    /**
     * Handles tokens after element name.
     */
    private void handleElementName(Token token, CharBuffer data) throws SAXException {
        switch (token) {
            case S:
                // Whitespace before attributes or GT
                // Initialize attributes object
                if (attributes == null) {
                    attributes = new SAXAttributes();
                } else {
                    attributes.clear();
                }
                // Push namespace context for this element (before parsing attributes)
                if (namespacesEnabled && namespaceTracker != null) {
                    namespaceTracker.pushContext();
                    namespaceContextPushed = true;
                }
                sawWhitespaceAfterAttributeValue = true; // First attribute doesn't need whitespace
                state = State.ELEMENT_ATTRS;
                break;
                
            case GT:
                // End of start tag (no attributes)
                // Create empty attributes
                if (attributes == null) {
                    attributes = new SAXAttributes();
                } else {
                    attributes.clear();
                }
                // Set DTD context for attribute type lookup
                attributes.setDTDContext(currentElementName, dtdParser);
                // Fire startElement (handles namespaces, defaults, etc.)
                fireStartElement(currentElementName, false);
                state = State.ELEMENT_CONTENT;
                break;
                
            case END_EMPTY_ELEMENT:
                // Empty element (no attributes)
                // Create empty attributes
                if (attributes == null) {
                    attributes = new SAXAttributes();
                } else {
                    attributes.clear();
                }
                // Set DTD context for attribute type lookup
                attributes.setDTDContext(currentElementName, dtdParser);
                // Fire startElement and endElement (handles namespaces, defaults, etc.)
                fireStartElement(currentElementName, true);
                // Pop element name from stack (empty element closes immediately)
                if (elementStack != null && !elementStack.isEmpty()) {
                    elementStack.removeLast();
                }
                elementDepth--;
                if (elementDepth == 0) {
                    state = State.AFTER_ROOT;
                    
                    // Validate IDREFs if validation enabled
                    if (validationEnabled && attributeValidator != null) {
                        String error = attributeValidator.validateIdrefs();
                        if (error != null) {
                            reportValidationError(error);
                        }
                    }
                    
                    if (contentHandler != null) {
                        contentHandler.endDocument();
                    }
                } else {
                    state = State.ELEMENT_CONTENT;
                }
                break;
                
            default:
 throw fatalError("Unexpected token after element name: " + token);
        }
    }
    
    /**
     * Handles tokens in element attributes section.
     */
    private void handleElementAttrs(Token token, CharBuffer data) throws SAXException {
        switch (token) {
            case S:
                // Whitespace between attributes - mark it
                sawWhitespaceAfterAttributeValue = true;
                break;
                
            case NAME:
                // Attribute name - must have whitespace before it (except for first attribute)
                if (attributes.getLength() > 0 && !sawWhitespaceAfterAttributeValue) {
                    throw fatalError("Whitespace required between attributes");
                }
                currentAttributeName = extractString(data);
                sawWhitespaceAfterAttributeValue = false; // Reset for next attribute
                state = State.ATTRIBUTE_NAME;
                break;
                
            case COLON:
                // Colon as attribute name (valid in XML 1.0, though discouraged by XML Namespaces)
                if (attributes.getLength() > 0 && !sawWhitespaceAfterAttributeValue) {
                    throw fatalError("Whitespace required between attributes");
                }
                currentAttributeName = ":";
                sawWhitespaceAfterAttributeValue = false; // Reset for next attribute
                state = State.ATTRIBUTE_NAME;
                break;
                
            case GT:
                // End of start tag
                // Set DTD context for attribute type lookup
                attributes.setDTDContext(currentElementName, dtdParser);
                // Fire startElement (handles namespaces, defaults, etc.)
                fireStartElement(currentElementName, false);
                state = State.ELEMENT_CONTENT;
                break;
                
            case END_EMPTY_ELEMENT:
                // Empty element
                // Set DTD context for attribute type lookup
                attributes.setDTDContext(currentElementName, dtdParser);
                // Fire startElement and endElement (handles namespaces, defaults, etc.)
                fireStartElement(currentElementName, true);
                // Pop element name from stack (empty element closes immediately)
                if (elementStack != null && !elementStack.isEmpty()) {
                    elementStack.removeLast();
                }
                elementDepth--;
                if (elementDepth == 0) {
                    state = State.AFTER_ROOT;
                    
                    // Validate IDREFs if validation enabled
                    if (validationEnabled && attributeValidator != null) {
                        String error = attributeValidator.validateIdrefs();
                        if (error != null) {
                            reportValidationError(error);
                        }
                    }
                    
                    if (contentHandler != null) {
                        contentHandler.endDocument();
                    }
                } else {
                    state = State.ELEMENT_CONTENT;
                }
                break;
                
            default:
 throw fatalError("Unexpected token in element attributes: " + token);
        }
    }
    
    /**
     * Handles tokens after attribute name.
     */
    private void handleAttributeName(Token token, CharBuffer data) throws SAXException {
        switch (token) {
            case S:
                // Whitespace before =, ignore
                break;
                
            case EQ:
                // Equals sign
                state = State.ATTRIBUTE_EQ;
                break;
                
            default:
 throw fatalError("Expected '=' after attribute name, got: " + token);
        }
    }
    
    /**
     * Handles tokens after attribute equals sign.
     */
    private void handleAttributeEq(Token token, CharBuffer data) throws SAXException {
        switch (token) {
            case S:
                // Whitespace after =, ignore
                break;
                
            case QUOT:
            case APOS:
                // Start of attribute value
                currentAttributeQuote = token;
                currentAttributeValue = new StringBuilder();
                state = State.ATTRIBUTE_VALUE_START;
                break;
                
            default:
 throw fatalError("Expected quote after '=', got: " + token);
        }
    }
    
    /**
     * Handles tokens at start of attribute value.
     */
    private void handleAttributeValueStart(Token token, CharBuffer data) throws SAXException {
        handleAttributeValue(token, data);
    }
    
    /**
     * Handles tokens inside attribute value.
     */
    private void handleAttributeValue(Token token, CharBuffer data) throws SAXException {
        if (token == currentAttributeQuote) {
            // End of attribute value - apply normalization
            String rawValue = currentAttributeValue.toString();
            String normalizedValue = normalizeAttributeValue(rawValue, currentElementName, currentAttributeName);
            
            // Check if this is a namespace declaration (xmlns or xmlns:prefix)
            boolean isNamespaceDecl = false;
            if (namespacesEnabled) {
                isNamespaceDecl = processNamespaceAttribute(currentAttributeName, normalizedValue);
            }
            
            // Add attribute to list (unless it's xmlns and namespace-prefixes is false)
            if (!isNamespaceDecl || namespacePrefixesEnabled) {
                // Process attribute name for namespace if enabled
                String uri = "";
                String localName = currentAttributeName;
                
                if (namespacesEnabled && namespaceTracker != null) {
                    String[] attrParts = namespaceTracker.processName(currentAttributeName, true);
                    uri = attrParts[0];
                    localName = attrParts[1];
                }
                
                try {
                    attributes.addAttribute(uri, localName, currentAttributeName, 
                                                  "CDATA", normalizedValue, true);
                } catch (IllegalArgumentException e) {
                    // Duplicate attribute - well-formedness error
 throw fatalError(e.getMessage());
                }
            }
            
            currentAttributeValue.setLength(0); // Reset for next attribute
            state = State.ELEMENT_ATTRS;
        } else if (token == Token.CDATA) {
            // Attribute value text
            currentAttributeValue.append(extractString(data));
        } else if (token == Token.S) {
            // Whitespace in attribute value
            currentAttributeValue.append(extractString(data));
        } else if (token == Token.ENTITYREF) {
            // Predefined entity or character reference in attribute value (already expanded)
            currentAttributeValue.append(extractString(data));
        } else if (token == Token.GENERALENTITYREF) {
            // General entity reference: &name;
            String entityName = extractString(data);
            String expandedValue = expandGeneralEntityInAttributeValue(entityName);
            currentAttributeValue.append(expandedValue);
        } else {
 throw fatalError("Unexpected token in attribute value: " + token);
        }
    }
    
    /**
     * Handles tokens in element content.
     */
    private void handleElementContent(Token token, CharBuffer data) throws SAXException {
        switch (token) {
            case CDATA:
                // Character data
                String text = extractString(data);
                
                // Check for forbidden ]]> sequence
                if (text.contains("]]>")) {
 throw fatalError("The character sequence ']]>' must not appear in content");
                }
                
                // Record for validation
                if (validationEnabled && dtdParser != null) {
                    recordTextContent(text);
                }
                
                if (contentHandler != null) {
                    contentHandler.characters(text.toCharArray(), 0, text.length());
                }
                break;
                
            case ENTITYREF:
                // Predefined entity or character reference (already expanded)
                String expandedText = extractString(data);
                
                // Record for validation
                if (validationEnabled && dtdParser != null) {
                    recordTextContent(expandedText);
                }
                
                if (contentHandler != null) {
                    contentHandler.characters(expandedText.toCharArray(), 0, expandedText.length());
                }
                break;
                
            case GENERALENTITYREF:
                // General entity reference: &name;
                String entityName = extractString(data);
                expandGeneralEntityInContent(entityName);
                break;
                
            case LT:
                // Start of nested element
                // Record child for parent's validation (current element in stack)
                // Note: child name will be determined in handleElementStart
                state = State.ELEMENT_START;
                break;
                
            case START_END_ELEMENT:
                // End tag
                state = State.END_ELEMENT_START;
                break;
                
            case START_PI:
                // Processing instruction
                currentPIData = new StringBuilder();
                state = State.PI_TARGET;
                break;
                
            case START_COMMENT:
                // Comment
                currentCommentText = new StringBuilder();
                state = State.COMMENT;
                break;
                
            case START_CDATA:
                // CDATA section
                currentCDATAText = new StringBuilder();
                state = State.CDATA_SECTION;
                if (lexicalHandler != null) {
                    lexicalHandler.startCDATA();
                }
                break;
                
            case S:
                // Whitespace
                String whitespace = extractString(data);
                
                // Record for validation (whitespace is treated specially)
                if (validationEnabled && dtdParser != null) {
                    recordTextContent(whitespace);
                }
                
                if (contentHandler != null) {
                    contentHandler.characters(whitespace.toCharArray(), 0, whitespace.length());
                }
                break;
                
            default:
 throw fatalError("Unexpected token in element content: " + token);
        }
    }
    
    /**
     * Handles tokens after START_END_ELEMENT (&lt;/). 
     */
    private void handleEndElementStart(Token token, CharBuffer data) throws SAXException {
        if (token == Token.NAME) {
            currentElementName = extractString(data);
            state = State.END_ELEMENT_NAME;
        } else {
 throw fatalError("Expected element name after '</', got: " + token);
        }
    }
    
    /**
     * Handles tokens after end element name.
     */
    private void handleEndElementName(Token token, CharBuffer data) throws SAXException {
        switch (token) {
            case S:
                // Whitespace before >, ignore
                break;
                
            case GT:
                // End of end tag
                // Validate end tag name matches start tag (well-formedness constraint)
                // Note: element stack is maintained for well-formedness checking
                if (elementStack != null && !elementStack.isEmpty()) {
                    String expectedName = elementStack.peekLast().elementName;
                    if (!currentElementName.equals(expectedName)) {
throw fatalError("End tag </" + currentElementName + "> does not match start tag <" + expectedName + ">");
                    }
                    // Pop the element from stack
                    elementStack.removeLast();
                }

                // Fire endElement (handles namespaces, etc.)
                fireEndElement(currentElementName);
                elementDepth--;
                if (elementDepth == 0) {
                    state = State.AFTER_ROOT;
                    
                    // Validate IDREFs if validation enabled
                    if (validationEnabled && attributeValidator != null) {
                        String error = attributeValidator.validateIdrefs();
                        if (error != null) {
                            reportValidationError(error);
                        }
                    }
                    
                    if (contentHandler != null) {
                        contentHandler.endDocument();
                    }
                } else {
                    state = State.ELEMENT_CONTENT;
                }
                break;
                
            default:
 throw fatalError("Expected '>' after end element name, got: " + token);
        }
    }
    
    /**
     * Handles PI target (after START_PI).
     */
    private void handlePITarget(Token token, CharBuffer data) throws SAXException {
        if (token == Token.NAME) {
            currentPITarget = extractString(data);
            state = State.PI_CONTENT;
        } else {
 throw fatalError("Expected PI target after '<?', got: " + token);
        }
    }
    
    /**
     * Handles PI content.
     */
    private void handlePIContent(Token token, CharBuffer data) throws SAXException {
        switch (token) {
            case S:
            case CDATA:
                // PI data (including whitespace)
                if (data != null) {
                    currentPIData.append(extractString(data));
                }
                break;
                
            case END_PI:
                // End of PI
                if (contentHandler != null) {
                    contentHandler.processingInstruction(currentPITarget, currentPIData.toString());
                }
                // Return to appropriate state
                if (elementDepth > 0) {
                    state = State.ELEMENT_CONTENT;
                } else if (documentStarted) {
                    state = State.AFTER_ROOT;
                } else {
                    state = State.PROLOG;
                }
                break;
                
            default:
 throw fatalError("Unexpected token in processing instruction: " + token);
        }
    }
    
    /**
     * Handles comment content.
     */
    private void handleComment(Token token, CharBuffer data) throws SAXException {
        switch (token) {
            case CDATA:
            case S:
                // Comment text
                if (data != null) {
                    currentCommentText.append(extractString(data));
                }
                break;
                
            case END_COMMENT:
                // End of comment
                if (lexicalHandler != null) {
                    String text = currentCommentText.toString();
                    lexicalHandler.comment(text.toCharArray(), 0, text.length());
                }
                // Return to appropriate state
                if (elementDepth > 0) {
                    state = State.ELEMENT_CONTENT;
                } else if (documentStarted) {
                    state = State.AFTER_ROOT;
                } else {
                    state = State.PROLOG;
                }
                break;
                
            default:
 throw fatalError("Unexpected token in comment: " + token);
        }
    }
    
    /**
     * Handles CDATA section content.
     */
    private void handleCDATASection(Token token, CharBuffer data) throws SAXException {
        switch (token) {
            case CDATA:
            case S:
                // CDATA text
                if (data != null) {
                    String text = extractString(data);
                    currentCDATAText.append(text);
                    if (contentHandler != null) {
                        contentHandler.characters(text.toCharArray(), 0, text.length());
                    }
                }
                break;
                
            case END_CDATA:
                // End of CDATA section
                if (lexicalHandler != null) {
                    lexicalHandler.endCDATA();
                }
                state = State.ELEMENT_CONTENT;
                break;
                
            default:
 throw fatalError("Unexpected token in CDATA section: " + token);
        }
    }
    
    /**
     * Handles tokens after root element closed.
     */
    private void handleAfterRoot(Token token, CharBuffer data) throws SAXException {
        switch (token) {
            case S:
                // Whitespace after root, ignore
                break;
                
            case START_PI:
                // PI after root
                currentPIData = new StringBuilder();
                state = State.PI_TARGET;
                break;
                
            case START_COMMENT:
                // Comment after root
                currentCommentText = new StringBuilder();
                state = State.COMMENT;
                break;
                
            default:
 throw fatalError("Unexpected content after root element: " + token);
        }
    }
    
    /**
     * Extracts a string from a CharBuffer.
     * @param buffer the buffer containing the string
     * @return the extracted string, or empty string if buffer is null
     */
    private String extractString(CharBuffer buffer) {
        if (buffer == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        while (buffer.hasRemaining()) {
            sb.append(buffer.get());
        }
        return sb.toString();
    }
    
    /**
     * Expands a general entity reference in an attribute value.
     * Uses the context-aware EntityExpansionHelper.
     * 
     * @param entityName the name of the entity to expand
     * @return the expanded entity value
     * @throws SAXParseException if the entity is not found, is external,
     *         or if circular references are detected
     */
    private String expandGeneralEntityInAttributeValue(String entityName) throws SAXException {
        // Check if we have a DTD parser (document must have DOCTYPE)
        if (dtdParser == null) {
            throw fatalError(
                "General entity reference '&" + entityName + ";' used in attribute value but no DTD present",
                null);
        }
        
        String expandedValue = dtdParser.entityStack.expandGeneralEntity(entityName, EntityExpansionContext.ATTRIBUTE_VALUE);
        
        // WFC: No < in Attribute Values
        // WFC: No External Entity References (handled by EntityExpansionHelper)
        // Additionally, validate that the expanded text doesn't contain unescaped & or <
        // This catches cases where character references like &#38; expand to literal & or <
        if (expandedValue != null) {
            for (int i = 0; i < expandedValue.length(); i++) {
                char c = expandedValue.charAt(i);
                if (c == '<') {
                    throw fatalError("Entity '&" + entityName + ";' expands to text containing '<', which is forbidden in attribute values (WFC: No < in Attribute Values)");
                }
                if (c == '&') {
                    throw fatalError("Entity '&" + entityName + ";' expands to text containing unescaped '&', which is forbidden in attribute values");
                }
            }
        }
        
        return expandedValue;
    }
    
    /**
     * Expands a general entity reference in element content.
     * Uses the context-aware EntityExpansionHelper.
     * 
     * <p>For internal entities, the expanded text is sent to the content handler
     * as character data. For external entities, async resolution would be triggered
     * (not yet implemented - currently throws an error).
     * 
     * @param entityName the name of the entity to expand
     * @throws SAXException if the entity is not found, is unparsed,
     *         or if circular references are detected
     */
    private void expandGeneralEntityInContent(String entityName) throws SAXException {
        // Check if we have a DTD parser (document must have DOCTYPE)
        if (dtdParser == null) {
            throw fatalError(
                "General entity reference '&" + entityName + ";' used but no DTD present",
                null);
        }
        
        String expandedValue = dtdParser.entityStack.expandGeneralEntity(entityName, EntityExpansionContext.CONTENT);
        
        if (expandedValue == null) {
            // External entity - use blocking I/O to resolve and parse it
            // Same mechanism as external DTD subset
            EntityDeclaration entity = dtdParser.getGeneralEntity(entityName);
            if (entity != null && entity.isExternal() && entity.externalID != null) {
                try {
                    processExternalEntity(
                        entityName,
                        entity.externalID.publicId,
                        entity.externalID.systemId
                    );
                } catch (IOException e) {
                    throw fatalError(
                        "Failed to resolve external entity '&" + entityName + ";': " + e.getMessage(),
                        e
                    );
                }
            }
            // If entity was resolved, processExternalEntity() will have sent tokens
            // and triggered content handler callbacks. Nothing more to do here.
            return;
        }
        
        // Internal entity - re-tokenize the expanded value
        // This ensures markup in the entity value (like <?xml...?>) is properly recognized
        // The tokenizer will start in the current tokenizer state (tracked via tokenizerState callback)
        if (!expandedValue.isEmpty()) {
            retokenizeInternalEntity(entityName, expandedValue);
        }
    }
    
    /**
     * Re-tokenizes an internal entity's expanded value.
     * This is necessary to properly handle markup in entity values.
     * Uses the current tokenizer state to ensure the entity is expanded in the
     * correct context (e.g., CONTENT vs DOCTYPE_INTERNAL).
     * 
     * Enforces WFC: Parsed Entity - the entity replacement text must not cause
     * unbalanced elements (element start/end tags must match within the entity).
     * 
     * @param entityName the entity name (for error reporting)
     * @param expandedValue the fully expanded entity value
     * @throws SAXException if re-tokenization fails or entity causes unbalanced markup
     */
    private void retokenizeInternalEntity(String entityName, String expandedValue) 
            throws SAXException {
        // TODO: Implement proper character reference bypass per XML 1.0 § 4.4.7
        // For now, always retokenize to validate structure
        // This causes test 088 to fail but makes tests 103, 116-120, 182 pass
        
        // Entity does NOT contain character references - any markup delimiters
        // are literal in the entity value and should be recognized as markup.
        // Re-tokenize to ensure proper handling and validation.
        
        // Increment entity expansion depth
        // This marks any elements opened during expansion so we can verify they're closed
        entityExpansionDepth++;
        
        // Get current XML version from DTDParser's entity stack
        // If no DTD parser, assume XML 1.0
        boolean currentVersion = false;
        if (dtdParser != null && !dtdParser.entityStack.isEmpty()) {
            currentVersion = dtdParser.entityStack.peek().isXML11;
        }
        
        // Check for recursion - has this entity name already been expanded?
        if (dtdParser != null) {
            for (EntityStackEntry ctx : dtdParser.entityStack) {
                if (!ctx.isParameterEntity && entityName.equals(ctx.entityName)) {
                    throw fatalError("Recursive entity reference: &" + entityName + ";");
                }
            }
        }
        
        // Push entity context onto DTDParser's stack
        EntityStackEntry entry = new EntityStackEntry(
            entityName, false /* general entity */, currentVersion, entityExpansionDepth);
        if (dtdParser != null) {
            dtdParser.entityStack.push(entry);
        }
        
        try {
            // Create tokenizer for the expanded value with the current tokenizer state and XML version
            Tokenizer entityTokenizer = new Tokenizer(this, currentTokenizerState, currentVersion);
            
            // Set the locator to provide position information
            entityTokenizer.setLocator(locator);
            
            // Feed the expanded value through the tokenizer as characters
            // (entity values are already decoded strings, no need for byte encoding)
            java.nio.CharBuffer buffer = java.nio.CharBuffer.wrap(expandedValue);
            entityTokenizer.receive(buffer);
            entityTokenizer.close();
            
            // Verify no elements opened at this depth remain on the stack
            // This enforces WFC: Parsed Entity - elements opened within entity must be closed within entity
            if (elementStack != null) {
                for (ElementValidationContext ctx : elementStack) {
                    if (ctx.entityExpansionDepth >= entityExpansionDepth) {
                        throw fatalError(
                            "Entity '&" + entityName + ";' creates unbalanced markup: " +
                            "element '" + ctx.elementName + "' opened within entity but not closed " +
                            "(WFC: Parsed Entity)"
                        );
                    }
                }
            }
        } finally {
            // Pop entity context to restore parent's state
            if (dtdParser != null && !dtdParser.entityStack.isEmpty() && 
                dtdParser.entityStack.peek() == entry) {
                dtdParser.entityStack.pop();
            }
            // Always decrement depth, even if expansion failed
            entityExpansionDepth--;
        }
    }
    
    /**
     * Fires startElement event to content handler, either namespace-aware or not.
     * Handles namespace context push, prefix mappings, and name processing.
     * 
     * @param elementName the raw element name (qName)
     * @param isEmpty true if this is an empty element (will fire endElement immediately)
     * @throws SAXException if processing fails
     */
    private void fireStartElement(String elementName, boolean isEmpty) throws SAXException {
        if (contentHandler == null) {
            return;
        }
        
        // Push namespace context if not already pushed (happens when element has attributes)
        if (namespacesEnabled && namespaceTracker != null && !namespaceContextPushed) {
            namespaceTracker.pushContext();
        }
        namespaceContextPushed = false; // Reset for next element
        
        // Apply default attribute values from DTD
        applyDefaultAttributeValues(elementName);
        
        // Validate attributes if validation enabled
        if (validationEnabled && dtdParser != null) {
            if (attributeValidator == null) {
                attributeValidator = new AttributeValidator(dtdParser);
            }
            String location = "line " + locator.getLineNumber() + ", col " + locator.getColumnNumber();
            String error = attributeValidator.validateAttributes(elementName, attributes, location);
            if (error != null) {
                reportValidationError(error);
            }
        }
        
        // Push content model validator if validation enabled
        if (validationEnabled && dtdParser != null) {
            pushElementValidator(elementName);
        }
        
        if (namespacesEnabled && namespaceTracker != null) {
            // Namespace-aware mode
            
            // Fire startPrefixMapping for all namespace declarations at this level
            Iterator<Map.Entry<String, String>> declarations = 
                namespaceTracker.getCurrentScopeDeclarations();
            while (declarations.hasNext()) {
                Map.Entry<String, String> entry = declarations.next();
                String prefix = entry.getKey();
                String uri = entry.getValue();
                contentHandler.startPrefixMapping(prefix, uri);
            }
            
            // Process element name
            String[] elementParts = namespaceTracker.processName(elementName, false);
            String namespaceURI = elementParts[0];
            String localName = elementParts[1];
            String qName = elementParts[2];
            
            // Fire startElement with namespace info
            contentHandler.startElement(namespaceURI, localName, qName, attributes);
            
            // If empty element, fire endElement immediately
            if (isEmpty) {
                // Validate empty element content
                if (validationEnabled && dtdParser != null) {
                    popElementValidator();
                }
                
                contentHandler.endElement(namespaceURI, localName, qName);
                
                // Fire endPrefixMapping in reverse order
                ArrayList<String> prefixes = new ArrayList<>();
                Iterator<Map.Entry<String, String>> endDecl = 
                    namespaceTracker.getCurrentScopeDeclarations();
                while (endDecl.hasNext()) {
                    prefixes.add(endDecl.next().getKey());
                }
                for (int i = prefixes.size() - 1; i >= 0; i--) {
                    contentHandler.endPrefixMapping(prefixes.get(i));
                }
                
                // Pop namespace context
                namespaceTracker.popContext();
            }
        } else {
            // Non-namespace-aware mode
            contentHandler.startElement("", elementName, elementName, attributes);
            
            if (isEmpty) {
                // Validate empty element content
                if (validationEnabled && dtdParser != null) {
                    popElementValidator();
                }
                
                contentHandler.endElement("", elementName, elementName);
            }
        }
    }
    
    /**
     * Fires endElement event to content handler, either namespace-aware or not.
     * Handles prefix unmappings and namespace context pop.
     * 
     * @param elementName the raw element name (qName)
     * @throws SAXException if processing fails
     */
    private void fireEndElement(String elementName) throws SAXException {
        // Validate content model before closing element
        if (validationEnabled && dtdParser != null) {
            popElementValidator();
        }
        
        if (contentHandler == null) {
            return;
        }
        
        if (namespacesEnabled && namespaceTracker != null) {
            // Namespace-aware mode
            
            // Process element name
            String[] elementParts = namespaceTracker.processName(elementName, false);
            String namespaceURI = elementParts[0];
            String localName = elementParts[1];
            String qName = elementParts[2];
            
            // Fire endElement
            contentHandler.endElement(namespaceURI, localName, qName);
            
            // Fire endPrefixMapping for all declarations at this level (in reverse order)
            ArrayList<String> prefixes = new ArrayList<>();
            Iterator<Map.Entry<String, String>> declarations = 
                namespaceTracker.getCurrentScopeDeclarations();
            while (declarations.hasNext()) {
                prefixes.add(declarations.next().getKey());
            }
            for (int i = prefixes.size() - 1; i >= 0; i--) {
                contentHandler.endPrefixMapping(prefixes.get(i));
            }
            
            // Pop namespace context
            namespaceTracker.popContext();
        } else {
            // Non-namespace-aware mode
            contentHandler.endElement("", elementName, elementName);
        }
    }
    
    /**
     * Applies default attribute values from the DTD to the current element.
     * Called before startElement() to ensure all defaults are applied.
     * 
     * <p>For each attribute declared in the DTD for this element:
     * <ul>
     * <li>If attribute has default value and wasn't specified: expand and add with specified=false
     * <li>If attribute is #FIXED and was specified: verify value matches fixed value
     * <li>If attribute is #FIXED and wasn't specified: expand and add with specified=false
     * </ul>
     * 
     * @param elementName the name of the element
     * @throws SAXException if entity expansion fails or fixed value doesn't match
     */
    private void applyDefaultAttributeValues(String elementName) throws SAXException {
        // Only apply defaults if we have a DTD
        if (dtdParser == null) {
            return;
        }
        
        // Get attribute declarations for this element
        java.util.Map<String, AttributeDeclaration> attrDecls = dtdParser.getAttributeDeclarations(elementName);
        if (attrDecls == null || attrDecls.isEmpty()) {
            return;
        }
        
        // Process each declared attribute
        for (AttributeDeclaration decl : attrDecls.values()) {
            // Check if attribute was specified in document
            int index = attributes.getIndex(decl.name);
            boolean specified = (index >= 0);
            
            if (decl.mode == Token.FIXED) {
                // #FIXED attribute
                if (decl.defaultValue != null) {
                    // Expand the fixed value
                    String fixedValue = dtdParser.entityStack.expandEntityValue(decl.defaultValue, EntityExpansionContext.ATTRIBUTE_VALUE);
                    
                    if (specified) {
                        // Verify specified value matches fixed value
                        String specifiedValue = attributes.getValue(index);
                        if (!fixedValue.equals(specifiedValue)) {
 throw fatalError(
                                "Attribute '" + decl.name + "' has #FIXED value '" + fixedValue + 
                                "' but document specifies '" + specifiedValue + "'");
                        }
                    } else {
                        // Apply fixed value
                        String normalizedValue = normalizeAttributeValue(fixedValue, elementName, decl.name);
                        attributes.addAttribute("", decl.name, decl.name, decl.type, normalizedValue, false);
                    }
                }
            } else if (!specified && decl.defaultValue != null) {
                // Attribute not specified and has default value (not #REQUIRED, not #IMPLIED)
                // Expand entity references in default value
                String expandedValue = dtdParser.entityStack.expandEntityValue(decl.defaultValue, EntityExpansionContext.ATTRIBUTE_VALUE);
                
                // Apply normalization
                String normalizedValue = normalizeAttributeValue(expandedValue, elementName, decl.name);
                
                // Add attribute with specified=false
                attributes.addAttribute("", decl.name, decl.name, decl.type, normalizedValue, false);
            }
            // Note: #REQUIRED and #IMPLIED don't get default values applied
        }
    }
    
    /**
     * Processes a namespace declaration attribute (xmlns or xmlns:prefix).
     * 
     * <p>Per XML Namespaces 1.0:
     * <ul>
     * <li>xmlns="uri" declares default namespace</li>
     * <li>xmlns:prefix="uri" declares prefixed namespace</li>
     * <li>xmlns="" undeclares default namespace</li>
     * </ul>
     * 
     * @param attrName the attribute name (e.g., "xmlns" or "xmlns:foo")
     * @param attrValue the namespace URI
     * @return true if this is a namespace declaration attribute
     * @throws SAXException if namespace declaration is invalid
     */
    private boolean processNamespaceAttribute(String attrName, String attrValue) throws SAXException {
        if (attrName == null || attrValue == null || namespaceTracker == null) {
            return false;
        }
        
        if ("xmlns".equals(attrName)) {
            // Default namespace declaration: xmlns="uri"
            namespaceTracker.declarePrefix("", attrValue);
            return true;
        } else if (attrName.startsWith("xmlns:")) {
            // Prefixed namespace declaration: xmlns:prefix="uri"
            String prefix = attrName.substring(6); // Skip "xmlns:"
            
            // Validate prefix not empty
            if (prefix.isEmpty()) {
 throw fatalError(
                    "Namespace prefix must not be empty after xmlns:");
            }
            
            // Per spec: cannot bind/unbind xml or xmlns prefixes
            if ("xml".equals(prefix) || "xmlns".equals(prefix)) {
                // xml prefix must be bound to XML namespace
                if ("xml".equals(prefix) && !NamespaceScopeTracker.XML_NAMESPACE_URI.equals(attrValue)) {
 throw fatalError(
                        "Cannot bind 'xml' prefix to namespace other than " + 
                        NamespaceScopeTracker.XML_NAMESPACE_URI);
                }
                // xmlns prefix cannot be declared
                if ("xmlns".equals(prefix)) {
 throw fatalError(
                        "Cannot declare 'xmlns' prefix");
                }
            }
            
            // Declare the prefix
            namespaceTracker.declarePrefix(prefix, attrValue);
            return true;
        }
        
        return false;
    }
    
    /**
     * Normalizes an attribute value according to XML specification section 3.3.3.
     * 
     * <p>Normalization process:
     * <ol>
     * <li>Line breaks have already been normalized to #xA (handled by Tokenizer)</li>
     * <li>Replace whitespace characters (#x20, #xA, #x9) with single space (#x20)</li>
     * <li>Entity and character references have already been expanded (handled during accumulation)</li>
     * <li>If attribute type is not CDATA: trim leading/trailing spaces and collapse space sequences</li>
     * </ol>
     * 
     * @param value the raw attribute value (after entity/char ref expansion)
     * @param elementName the name of the element containing this attribute
     * @param attributeName the name of the attribute
     * @return the normalized attribute value
     */
    private String normalizeAttributeValue(String value, String elementName, String attributeName) throws SAXException {
        if (value == null || value.isEmpty()) {
            return value;
        }
        
        // Step 1: Replace whitespace characters with space (#x20)
        StringBuilder normalized = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\n' || c == '\t' || c == '\r') {
                // Replace #xA, #x9, #xD with space
                normalized.append(' ');
            } else {
                normalized.append(c);
            }
        }
        
        // Step 2: Query DTD for attribute type
        String attributeType = "CDATA"; // Default if no DTD
        if (dtdParser != null) {
            AttributeDeclaration attrDecl = dtdParser.getAttributeDeclaration(elementName, attributeName);
            if (attrDecl != null && attrDecl.type != null) {
                attributeType = attrDecl.type;
            }
        }
        
        // Step 3: If not CDATA, trim and collapse spaces
        if (!"CDATA".equals(attributeType)) {
            String result = normalized.toString().trim();
            // Replace sequences of spaces with single space
            result = result.replaceAll(" +", " ");
            return result;
        }
        
        return normalized.toString();
    }
    
    /**
     * Pushes a content model validator for an element (validation mode).
     * 
     * @param elementName the element name
     * @throws SAXException if processing fails
     */
    private void pushElementValidator(String elementName) throws SAXException {
        if (elementStack == null || elementStack.isEmpty()) {
            return; // Should not happen
        }
        
        // Get element declaration from DTD
        ElementDeclaration elementDecl = dtdParser.getElementDeclaration(elementName);
        if (elementDecl == null) {
            // Element not declared in DTD - validation error (recoverable)
            reportValidationError("Element '" + elementName + "' not declared in DTD");
            // Create a dummy validator with ANY content model to continue processing
            elementDecl = new ElementDeclaration();
            elementDecl.name = elementName;
            elementDecl.contentType = ElementDeclaration.ContentType.ANY;
        }
        
        // Create validator and replace the context on the stack
        ContentModelValidator validator = new ContentModelValidator(elementDecl);
        elementStack.removeLast(); // Remove the context with null validator
        elementStack.addLast(new ElementValidationContext(elementName, validator, entityExpansionDepth));
    }
    
    /**
     * Pops and validates the content model validator for current element.
     * 
     * @throws SAXException if processing fails
     */
    private void popElementValidator() throws SAXException {
        if (elementStack == null || elementStack.isEmpty()) {
            return;
        }
        
        // Get current context (peek, don't pop yet)
        ElementValidationContext context = elementStack.peekLast();
        
        // Validate that content is complete (if validator exists)
        if (context.validator != null) {
            String error = context.validator.validate();
            if (error != null) {
                reportValidationError(error);
            }
        }
        
        // Pop from stack (this is done in the end tag handling already, so skip here)
        // The elementStack.removeLast() is called in handleEndElementName
    }
    
    /**
     * Records a child element for content model validation.
     * 
     * @param childElementName the name of the child element
     * @throws SAXException if processing fails
     */
    private void recordChildElement(String childElementName) throws SAXException {
        if (elementStack == null || elementStack.isEmpty()) {
            return;
        }
        
        // Get parent context (current element)
        ElementValidationContext context = elementStack.peekLast();
        if (context.validator == null) {
            return; // No validator (validation disabled or no DTD)
        }
        
        // Record child
        String error = context.validator.addChildElement(childElementName);
        if (error != null) {
            reportValidationError(error);
        }
    }
    
    /**
     * Records text content for content model validation.
     * 
     * @param text the text content
     * @throws SAXException if processing fails
     */
    private void recordTextContent(String text) throws SAXException {
        if (elementStack == null || elementStack.isEmpty()) {
            return;
        }
        
        // Get current context
        ElementValidationContext context = elementStack.peekLast();
        if (context.validator == null) {
            return; // No validator (validation disabled or no DTD)
        }
        
        // Check if text is whitespace-only
        boolean isWhitespaceOnly = text.trim().isEmpty();
        
        // Record text
        String error = context.validator.addTextContent(text, isWhitespaceOnly);
        if (error != null) {
            reportValidationError(error);
        }
    }
    
    /**
     * Reports a validation error via the ErrorHandler (if set).
     * Validation errors are recoverable and do not interrupt processing.
     * 
     * @param message the error message
     * @throws SAXException if the error handler throws
     */
    private void reportValidationError(String message) throws SAXException {
        if (errorHandler != null) {
            errorHandler.error(new SAXParseException(message, locator));
        }
    }
    
    /**
     * Reports a fatal well-formedness error.
     * Calls the ErrorHandler's fatalError method if set, then returns the exception
     * for the caller to throw.
     * This implements the TokenConsumer interface to allow the tokenizer to report
     * tokenizer-level fatal errors.
     * 
     * @param message the error message
     * @return the SAXException to throw
     * @throws SAXException if the ErrorHandler itself throws
     */
    public SAXException fatalError(String message) throws SAXException {
        SAXParseException exception = new SAXParseException(message, locator);
        if (errorHandler != null) {
            errorHandler.fatalError(exception);
        }
        return exception;
    }
    
    @Override
    public void tokenizerState(TokenizerState state) {
        // Track the tokenizer's current state for entity expansion
        this.currentTokenizerState = state;
    }
    
    @Override
    public void xmlVersion(boolean isXML11) {
        // Update the XML version at the current entity expansion level
        // This is called when a tokenizer parses an XML/text declaration
        // Delegate to DTDParser's unified entity stack (if DTD is initialized)
        // Otherwise update a document-level entity context
        if (dtdParser != null && !dtdParser.entityStack.isEmpty()) {
            dtdParser.entityStack.peek().isXML11 = isXML11;
        }
        // Note: If no DTD parser exists yet, the main document's version will be set
        // when the DTD parser is lazily initialized in handleDoctype()
    }
    
    /**
     * Called by DTDParser when DOCTYPE parsing is complete.
     * Updates currentTokenizerState to CONTENT so that subsequent entity expansions
     * use the correct context.
     */
    void dtdComplete() {
        this.currentTokenizerState = TokenizerState.CONTENT;
    }
    
    /**
     * Reports a fatal well-formedness error with a cause.
     * Calls the ErrorHandler's fatalError method if set, then returns the exception
     * for the caller to throw.
     * 
     * @param message the error message
     * @param cause the underlying exception that caused this error
     * @return the SAXException to throw
     * @throws SAXException if the ErrorHandler itself throws
     */    
    private SAXException fatalError(String message, Exception cause) throws SAXException {
        SAXParseException exception = new SAXParseException(message, locator, cause);
        if (errorHandler != null) {
            errorHandler.fatalError(exception);
        }
        return exception;
    }

}

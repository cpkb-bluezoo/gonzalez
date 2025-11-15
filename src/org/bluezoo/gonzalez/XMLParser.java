/*
 * XMLParser.java
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
import java.util.HashSet;
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
public class XMLParser implements TokenConsumer {

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
     * Stack for tracking entity resolution to prevent infinite recursion.
     * Contains system IDs of entities currently being resolved.
     */
    private Set<String> entityResolutionStack;
    
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
     * Current element name being processed.
     */
    private String currentElementName;
    
    /**
     * Current attribute name being processed.
     */
    private String currentAttributeName;
    
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

    @Override
    public void setLocator(Locator locator) {
        this.locator = locator;
    }

    /**
     * Sets the content handler for receiving document events.
     * @param handler the content handler
     */
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
     *   <li>Creates a nested XMLTokenizer for the entity stream</li>
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
        
        // Check for recursive entity reference
        if (entityResolutionStack == null) {
            entityResolutionStack = new HashSet<>();
        }
        
        if (entityResolutionStack.contains(resolvedSystemId)) {
            throw new SAXParseException("Recursive entity reference detected: " + resolvedSystemId, locator);
        }
        
        // Add to stack
        entityResolutionStack.add(resolvedSystemId);
        
        try {
            // Create nested tokenizer for entity
            XMLTokenizer entityTokenizer = new XMLTokenizer(
                this,  // Send tokens back to this parser
                source.getPublicId(),
                resolvedSystemId
            );
            
            // Get input stream from source
            InputStream inputStream = source.getByteStream();
            if (inputStream == null) {
                // TODO: Handle Reader (character stream)
                throw new SAXParseException("Entity InputSource must have a byte stream", locator);
            }
            
            // Feed entity data to tokenizer (same logic as Parser.parse())
            try {
                byte[] buffer = new byte[4096];
                int bytesRead;
                
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    ByteBuffer byteBuffer = ByteBuffer.wrap(buffer, 0, bytesRead);
                    entityTokenizer.receive(byteBuffer);
                }
                
                // Signal end of entity
                entityTokenizer.close();
                
            } finally {
                inputStream.close();
            }
            
            // Entity processing complete, continue with main stream
            
        } finally {
            // Remove from stack
            entityResolutionStack.remove(resolvedSystemId);
        }
    }

    /**
     * Resets the parser state to allow reuse for parsing another document.
     *
     * <p>This method clears all parsing state, allowing the same XMLParser
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
        if (entityResolutionStack != null) {
            entityResolutionStack.clear();
        }
        
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
    }

    @Override
    public void receive(Token token, CharBuffer data) throws SAXException {
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
                state = State.ELEMENT_START;
                break;
                
            default:
                throw new SAXParseException("Unexpected token in prolog: " + token, locator);
        }
    }
    
    /**
     * Handles tokens after LT (expecting element name).
     */
    private void handleElementStart(Token token, CharBuffer data) throws SAXException {
        if (token == Token.NAME) {
            currentElementName = extractString(data);
            elementDepth++;
            state = State.ELEMENT_NAME;
        } else {
            throw new SAXParseException("Expected element name after '<', got: " + token, locator);
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
                state = State.ELEMENT_ATTRS;
                break;
                
            case GT:
                // End of start tag (no attributes)
                if (contentHandler != null) {
                    // Create empty attributes
                    if (attributes == null) {
                        attributes = new SAXAttributes();
                    } else {
                        attributes.clear();
                    }
                    // Set DTD context for attribute type lookup
                    attributes.setDTDContext(currentElementName, dtdParser);
                    // Apply default attribute values from DTD
                    applyDefaultAttributeValues(currentElementName);
                    contentHandler.startElement("", currentElementName, currentElementName, attributes);
                }
                state = State.ELEMENT_CONTENT;
                break;
                
            case END_EMPTY_ELEMENT:
                // Empty element (no attributes)
                if (contentHandler != null) {
                    // Create empty attributes
                    if (attributes == null) {
                        attributes = new SAXAttributes();
                    } else {
                        attributes.clear();
                    }
                    // Set DTD context for attribute type lookup
                    attributes.setDTDContext(currentElementName, dtdParser);
                    // Apply default attribute values from DTD
                    applyDefaultAttributeValues(currentElementName);
                    contentHandler.startElement("", currentElementName, currentElementName, attributes);
                    contentHandler.endElement("", currentElementName, currentElementName);
                }
                elementDepth--;
                if (elementDepth == 0) {
                    state = State.AFTER_ROOT;
                    if (contentHandler != null) {
                        contentHandler.endDocument();
                    }
                } else {
                    state = State.ELEMENT_CONTENT;
                }
                break;
                
            default:
                throw new SAXParseException("Unexpected token after element name: " + token, locator);
        }
    }
    
    /**
     * Handles tokens in element attributes section.
     */
    private void handleElementAttrs(Token token, CharBuffer data) throws SAXException {
        switch (token) {
            case S:
                // Whitespace, ignore
                break;
                
            case NAME:
                // Attribute name
                currentAttributeName = extractString(data);
                state = State.ATTRIBUTE_NAME;
                break;
                
            case GT:
                // End of start tag
                if (contentHandler != null) {
                    // Set DTD context for attribute type lookup
                    attributes.setDTDContext(currentElementName, dtdParser);
                    // Apply default attribute values from DTD
                    applyDefaultAttributeValues(currentElementName);
                    contentHandler.startElement("", currentElementName, currentElementName, attributes);
                }
                state = State.ELEMENT_CONTENT;
                break;
                
            case END_EMPTY_ELEMENT:
                // Empty element
                if (contentHandler != null) {
                    // Set DTD context for attribute type lookup
                    attributes.setDTDContext(currentElementName, dtdParser);
                    // Apply default attribute values from DTD
                    applyDefaultAttributeValues(currentElementName);
                    contentHandler.startElement("", currentElementName, currentElementName, attributes);
                    contentHandler.endElement("", currentElementName, currentElementName);
                }
                elementDepth--;
                if (elementDepth == 0) {
                    state = State.AFTER_ROOT;
                    if (contentHandler != null) {
                        contentHandler.endDocument();
                    }
                } else {
                    state = State.ELEMENT_CONTENT;
                }
                break;
                
            default:
                throw new SAXParseException("Unexpected token in element attributes: " + token, locator);
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
                throw new SAXParseException("Expected '=' after attribute name, got: " + token, locator);
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
                throw new SAXParseException("Expected quote after '=', got: " + token, locator);
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
            
            // Add attribute to list
            attributes.addAttribute("", currentAttributeName, currentAttributeName, 
                                          "CDATA", normalizedValue, true);
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
            throw new SAXParseException("Unexpected token in attribute value: " + token, locator);
        }
    }
    
    /**
     * Handles tokens in element content.
     */
    private void handleElementContent(Token token, CharBuffer data) throws SAXException {
        switch (token) {
            case CDATA:
                // Character data
                if (contentHandler != null) {
                    String text = extractString(data);
                    contentHandler.characters(text.toCharArray(), 0, text.length());
                }
                break;
                
            case ENTITYREF:
                // Predefined entity or character reference (already expanded)
                if (contentHandler != null) {
                    String text = extractString(data);
                    contentHandler.characters(text.toCharArray(), 0, text.length());
                }
                break;
                
            case GENERALENTITYREF:
                // General entity reference: &name;
                String entityName = extractString(data);
                expandGeneralEntityInContent(entityName);
                break;
                
            case LT:
                // Start of nested element
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
                if (contentHandler != null) {
                    String text = extractString(data);
                    contentHandler.characters(text.toCharArray(), 0, text.length());
                }
                break;
                
            default:
                throw new SAXParseException("Unexpected token in element content: " + token, locator);
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
            throw new SAXParseException("Expected element name after '</', got: " + token, locator);
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
                if (contentHandler != null) {
                    contentHandler.endElement("", currentElementName, currentElementName);
                }
                elementDepth--;
                if (elementDepth == 0) {
                    state = State.AFTER_ROOT;
                    if (contentHandler != null) {
                        contentHandler.endDocument();
                    }
                } else {
                    state = State.ELEMENT_CONTENT;
                }
                break;
                
            default:
                throw new SAXParseException("Expected '>' after end element name, got: " + token, locator);
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
            throw new SAXParseException("Expected PI target after '<?', got: " + token, locator);
        }
    }
    
    /**
     * Handles PI content.
     */
    private void handlePIContent(Token token, CharBuffer data) throws SAXException {
        switch (token) {
            case S:
            case CDATA:
                // PI data
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
                // Other tokens are part of PI data
                if (data != null) {
                    currentPIData.append(extractString(data));
                }
                break;
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
                // Other tokens might be part of comment (e.g., hyphens)
                break;
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
                // Other tokens might appear in CDATA (they're literal)
                break;
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
                throw new SAXParseException("Unexpected content after root element: " + token, locator);
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
        EntityExpansionHelper helper = new EntityExpansionHelper(dtdParser, locator);
        return helper.expandGeneralEntity(entityName, EntityExpansionContext.ATTRIBUTE_VALUE);
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
        EntityExpansionHelper helper = new EntityExpansionHelper(dtdParser, locator);
        String expandedValue = helper.expandGeneralEntity(entityName, EntityExpansionContext.CONTENT);
        
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
                    throw new SAXParseException(
                        "Failed to resolve external entity '&" + entityName + ";': " + e.getMessage(),
                        locator,
                        e
                    );
                }
            }
            // If entity was resolved, processExternalEntity() will have sent tokens
            // and triggered content handler callbacks. Nothing more to do here.
            return;
        }
        
        // Internal entity - send expanded text to content handler
        if (contentHandler != null && !expandedValue.isEmpty()) {
            contentHandler.characters(expandedValue.toCharArray(), 0, expandedValue.length());
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
                    EntityExpansionHelper helper = new EntityExpansionHelper(dtdParser, locator);
                    String fixedValue = helper.expandEntityValue(decl.defaultValue, EntityExpansionContext.ATTRIBUTE_VALUE);
                    
                    if (specified) {
                        // Verify specified value matches fixed value
                        String specifiedValue = attributes.getValue(index);
                        if (!fixedValue.equals(specifiedValue)) {
                            throw new SAXParseException(
                                "Attribute '" + decl.name + "' has #FIXED value '" + fixedValue + 
                                "' but document specifies '" + specifiedValue + "'",
                                locator);
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
                EntityExpansionHelper helper = new EntityExpansionHelper(dtdParser, locator);
                String expandedValue = helper.expandEntityValue(decl.defaultValue, EntityExpansionContext.ATTRIBUTE_VALUE);
                
                // Apply normalization
                String normalizedValue = normalizeAttributeValue(expandedValue, elementName, decl.name);
                
                // Add attribute with specified=false
                attributes.addAttribute("", decl.name, decl.name, decl.type, normalizedValue, false);
            }
            // Note: #REQUIRED and #IMPLIED don't get default values applied
        }
    }
    
    /**
     * Normalizes an attribute value according to XML specification section 3.3.3.
     * 
     * <p>Normalization process:
     * <ol>
     * <li>Line breaks have already been normalized to #xA (handled by XMLTokenizer)</li>
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

}

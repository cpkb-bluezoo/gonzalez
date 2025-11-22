/*
 * DTDParser.java
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
import java.nio.CharBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.xml.sax.ContentHandler;
import org.xml.sax.DTDHandler;
import org.xml.sax.ErrorHandler;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.ext.LexicalHandler;

/**
 * DTD parser.
 * <p>
 * This is a consumer of tokens within a DOCTYPE declaration. It is lazily
 * constructed by the ContentParser when a DOCTYPE token is encountered, and
 * receives tokens until the final GT of the doctypedecl production is
 * detected.
 * <p>
 * The DTDParser builds internal structures for element declarations, attribute
 * list declarations, entity declarations, and notation declarations. These
 * structures are only created when a DOCTYPE is present, avoiding memory
 * allocation for the majority of documents that don't include a DTD.
 * <p>
 * The parser delegates to SAX2 handlers (DTDHandler, LexicalHandler) to
 * report DTD events as they are encountered.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DTDParser implements TokenConsumer {

    /**
     * Parsing states within the DOCTYPE declaration.
     */
    enum State {
        INITIAL,                // Just started, expecting DOCTYPE name
        AFTER_NAME,             // Read name, expecting SYSTEM/PUBLIC/[/GT
        AFTER_SYSTEM_PUBLIC,    // Read SYSTEM/PUBLIC, expecting quoted string
        AFTER_PUBLIC_ID,        // Read public ID, expecting system ID
        AFTER_EXTERNAL_ID,      // Read external ID, expecting [/GT
        IN_INTERNAL_SUBSET,     // Inside [ ... ], processing declarations
        AFTER_INTERNAL_SUBSET,  // Read ], expecting GT
        IN_ELEMENTDECL,         // Parsing &lt;!ELEMENT declaration
        IN_ATTLISTDECL,         // Parsing &lt;!ATTLIST declaration
        IN_ENTITYDECL,          // Parsing &lt;!ENTITY declaration
        IN_NOTATIONDECL,        // Parsing &lt;!NOTATION declaration
        IN_COMMENT,             // Parsing comment (<!-- ... -->)
        IN_PI,                  // Parsing processing instruction (<? ... ?>)
        IN_CONDITIONAL,         // Parsing conditional section (<![)
        IN_CONDITIONAL_INCLUDE, // Inside INCLUDE conditional section
        IN_CONDITIONAL_IGNORE,  // Inside IGNORE conditional section (skip content)
        DONE                    // Read final GT, done processing
    }

    private State state = State.INITIAL;
    
    /**
     * Package-private accessor for the current DTD parser state.
     * Used by ContentParser to determine if external entities should be tokenized as DTD content.
     */
    State getState() {
        return state;
    }
    private State savedState = State.IN_INTERNAL_SUBSET; // For returning after declarations/comments/PIs
    
    // Current tokenizer state (updated via tokenizerState callback)
    private TokenizerState currentTokenizerState = TokenizerState.DOCTYPE_INTERNAL;
    
    /**
     * Entity stack - tracks all entity expansion entries and provides expansion logic.
     * Unified stack for both general and parameter entities, used for:
     * - Detecting infinite recursion (by name and systemId)
     * - Tracking XML version across entity boundaries
     * - Validating element nesting (WFC: Parsed Entity)
     * - Context-aware entity value expansion
     * 
     * The bottom of the stack is always the document entity.
     * Package-private to allow ContentParser access.
     */
    final EntityStack entityStack;
    
    
    private Locator locator;
    private ContentHandler contentHandler;
    private DTDHandler dtdHandler;
    private LexicalHandler lexicalHandler;
    private ErrorHandler errorHandler;
    
    /**
     * Reference to the parent ContentParser for processing external entities.
     */
    private final ContentParser xmlParser;

    /**
     * The DOCTYPE name (root element name).
     */
    private String doctypeName;

    /**
     * External ID for the external DTD subset (if present).
     */
    private ExternalID doctypeExternalID;
    
    /**
     * Track whether we saw SYSTEM or PUBLIC keyword.
     */
    private boolean sawPublicKeyword;
    
    /**
     * Track whether required whitespace was seen after public ID.
     * Used to enforce whitespace between public and system IDs.
     */
    private boolean sawWhitespaceAfterPublicId;

    /**
     * Element declarations: element name → ElementDeclaration.
     * Uses HashMap for O(1) lookup during validation.
     * Keys are interned strings.
     */
    private Map<String, ElementDeclaration> elementDecls;

    /**
     * Attribute declarations: element name → (attribute name → AttributeDeclaration).
     * Two-level map structure for efficient lookup by element and attribute.
     * Uses HashMap as DTDs are typically small (dozens to hundreds of declarations)
     * and O(1) lookup is more important than sorted iteration.
     * All keys are interned strings for fast comparison.
     */
    private Map<String, Map<String, AttributeDeclaration>> attributeDecls;
    
    /**
     * Entity declarations: entity name → EntityDeclaration.
     * Stores general entities (referenced as &amp;name;).
     * Keys are interned strings.
     */
    private Map<String, EntityDeclaration> entities;
    
    /**
     * Parameter entity declarations: entity name → EntityDeclaration.
     * Stores parameter entities (referenced as %name; in DTD).
     * Keys are interned strings.
     */
    private Map<String, EntityDeclaration> parameterEntities;
    
    /**
     * Notation declarations: notation name → ExternalID.
     * Keys are interned strings.
     */
    private Map<String, ExternalID> notations;

    /**
     * Depth tracking for nested structures (e.g., conditional sections).
     */
    private int nestingDepth = 0;
    
    /**
     * Sub-states for parsing conditional sections.
     */
    private enum ConditionalSectionState {
        EXPECT_KEYWORD,      // After <![, expecting INCLUDE/IGNORE or %
        AFTER_KEYWORD,       // After keyword, expecting whitespace
        EXPECT_OPEN_BRACKET, // After whitespace, expecting [
    }
    
    private ConditionalSectionState conditionalState;
    private int conditionalDepth = 0; // Track nesting depth of conditional sections
    private boolean conditionalIsInclude; // true for INCLUDE, false for IGNORE
    
    /**
     * Current element declaration parser (null when not parsing &lt;!ELEMENT).
     * Created on START_ELEMENTDECL, parses until GT is encountered.
     */
    private ElementDeclParser elementDeclParser;
    
    /**
     * Current attribute list declaration parser (null when not parsing &lt;!ATTLIST).
     * Created on START_ATTLISTDECL, parses until GT is encountered.
     */
    private AttListDeclParser attListDeclParser;
    
    /**
     * Current notation declaration parser (null when not parsing &lt;!NOTATION).
     * Created on START_NOTATIONDECL, parses until GT is encountered.
     */
    private NotationDeclParser notationDeclParser;
    
    /**
     * Current entity declaration parser (null when not parsing &lt;!ENTITY).
     * Created on START_ENTITYDECL, parses until GT is encountered.
     */
    private EntityDeclParser entityDeclParser;
    
    /**
     * Comment text accumulator.
     * Accumulates CDATA chunks for comments (asynchronous parsing).
     * Created on START_COMMENT, emitted on END_COMMENT.
     */
    private StringBuilder commentBuilder;
    
    /**
     * Processing instruction accumulators.
     * PI target captured as single NAME token, PI data accumulated across CDATA chunks.
     * Created on START_PI, emitted on END_PI.
     */
    private String piTarget;
    private StringBuilder piDataBuilder;
    
    /**
     * Current notation declaration being parsed.
     * 
     * <p>currentNotationName: The notation name
     * <p>currentNotationExternalID: The external ID being built (SYSTEM or PUBLIC)
     * <p>sawPublicInNotation: Track if we saw PUBLIC keyword (vs SYSTEM)
     * 
     * <p>When GT is encountered, the notation is added to the notations map.
     */

    /**
     * Constructs a new DTDParser.
     * @param xmlParser the parent ContentParser for processing external entities
     */
    public DTDParser(ContentParser xmlParser) {
        this.xmlParser = xmlParser;
        // Initialize entity stack (includes document entity by default)
        // Use a locator accessor method from ContentParser
        this.entityStack = new EntityStack(this, xmlParser.getLocator());
    }

    @Override
    public void setLocator(Locator locator) {
        this.locator = locator;
    }

    /**
     * Sets the DTD handler for receiving DTD events.
     * @param handler the DTD handler
     */
    public void setContentHandler(ContentHandler handler) {
        this.contentHandler = handler;
    }
    
    public void setDTDHandler(DTDHandler handler) {
        this.dtdHandler = handler;
    }

    /**
     * Sets the lexical handler for receiving lexical events.
     * @param handler the lexical handler
     */
    public void setLexicalHandler(LexicalHandler handler) {
        this.lexicalHandler = handler;
    }

    /**
     * Sets the error handler for reporting errors.
     * @param handler the error handler
     */
    public void setErrorHandler(ErrorHandler handler) {
        this.errorHandler = handler;
    }
    
    /**
     * Reports a fatal error through the error handler and returns the exception.
     * Implements TokenConsumer interface.
     * 
     * @param message the error message
     * @return the SAXException to throw
     * @throws SAXException if the ErrorHandler itself throws
     */
    @Override
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
        // Update the XML version at the current entity expansion level (top of stack)
        // This is called when a tokenizer parses an XML/text declaration
        entityStack.xmlVersion(isXML11);
    }

    /**
     * Checks if this parser can receive more tokens.
     * <p>
     * This method is called by the ContentParser before delegating each token.
     * It returns true while the DTDParser is still processing tokens inside
     * the doctypedecl production, and false when the final GT is detected.
     * <p>
     * When this returns false, the ContentParser knows to stop delegating tokens
     * to the DTDParser and resume normal parsing.
     *
     * @param token the token that would be received
     * @return true if this parser can receive the token, false otherwise
     */
    public boolean canReceive(Token token) {
        return state != State.DONE;
    }
    
    /**
     * Validates a public ID according to XML spec.
     * Public IDs may only contain: space, CR, LF, letters, digits,
     * and the punctuation: - ' () + , . / : = ? ; ! * # @ $ _ %
     * Package-private to allow access from NotationDeclParser.
     * 
     * @param publicId the public ID to validate
     * @throws SAXException if the public ID contains illegal characters
     */
    void validatePublicId(String publicId) throws SAXException {
        for (int i = 0; i < publicId.length(); i++) {
            char c = publicId.charAt(i);
            boolean valid = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ||
                           (c >= '0' && c <= '9') || c == ' ' || c == '\r' || c == '\n' ||
                           c == '-' || c == '\'' || c == '(' || c == ')' || c == '+' ||
                           c == ',' || c == '.' || c == '/' || c == ':' || c == '=' ||
                           c == '?' || c == ';' || c == '!' || c == '*' || c == '#' ||
                           c == '@' || c == '$' || c == '_' || c == '%';
            if (!valid) {
                throw new SAXParseException("Illegal character in public ID: '" + c + 
                    "' (0x" + Integer.toHexString(c) + ")", locator);
            }
        }
    }

    @Override
    public void receive(Token token, CharBuffer data) throws SAXException {
        if (state == State.DONE) {
            throw new IllegalStateException("DTDParser has finished processing");
        }

        switch (state) {
            case INITIAL:
                handleInitial(token, data);
                break;

            case AFTER_NAME:
                handleAfterName(token, data);
                break;

            case AFTER_SYSTEM_PUBLIC:
                handleAfterSystemPublic(token, data);
                break;

            case AFTER_PUBLIC_ID:
                handleAfterPublicId(token, data);
                break;

            case AFTER_EXTERNAL_ID:
                handleAfterExternalId(token, data);
                break;

            case IN_INTERNAL_SUBSET:
                handleInInternalSubset(token, data);
                break;

            case AFTER_INTERNAL_SUBSET:
                handleAfterInternalSubset(token, data);
                break;
                
            case IN_ELEMENTDECL:
                // Handle parameter entity expansion before delegating
                if (token == Token.PARAMETERENTITYREF) {
                    String refName = extractString(data);
                    expandParameterEntityInline(refName);
                } else {
                    handleInElementDecl(token, data);
                }
                break;
                
            case IN_ATTLISTDECL:
                // Handle parameter entity expansion before delegating
                if (token == Token.PARAMETERENTITYREF) {
                    String refName = extractString(data);
                    expandParameterEntityInline(refName);
                } else if (attListDeclParser != null && attListDeclParser.handleToken(token, data)) {
                    // ATTLIST declaration complete - return to saved state
                    attListDeclParser = null;
                    state = savedState;
                }
                break;
                
            case IN_ENTITYDECL:
                // Handle parameter entity expansion before delegating
                if (token == Token.PARAMETERENTITYREF) {
                    String refName = extractString(data);
                    expandParameterEntityInline(refName);
                } else if (entityDeclParser != null && entityDeclParser.handleToken(token, data)) {
                    // ENTITY declaration complete - return to saved state
                    entityDeclParser = null;
                    state = savedState;
                }
                break;
            
            case IN_NOTATIONDECL:
                // Handle parameter entity expansion before delegating
                if (token == Token.PARAMETERENTITYREF) {
                    String refName = extractString(data);
                    expandParameterEntityInline(refName);
                } else if (notationDeclParser != null && notationDeclParser.handleToken(token, data)) {
                    // NOTATION declaration complete - return to saved state
                    notationDeclParser = null;
                    state = savedState;
                }
                break;
                
            case IN_COMMENT:
                handleInComment(token, data);
                break;
                
            case IN_PI:
                handleInPI(token, data);
                break;
                
            case IN_CONDITIONAL:
                handleInConditional(token, data);
                break;
                
            case IN_CONDITIONAL_INCLUDE:
                handleInConditionalInclude(token, data);
                break;
                
            case IN_CONDITIONAL_IGNORE:
                handleInConditionalIgnore(token, data);
                break;
        }
    }

    /**
     * Handles tokens in the INITIAL state (expecting DOCTYPE name).
     */
    private void handleInitial(Token token, CharBuffer data) throws SAXException {
        switch (token) {
            case S:
                // Whitespace before name, ignore
                break;

            case NAME:
                // This is the DOCTYPE name (root element name)
                doctypeName = extractString(data);
                changeState(State.AFTER_NAME);
                break;

            default:
                throw new SAXParseException("Expected name after &lt;!DOCTYPE, got: " + token, locator);
        }
    }

    /**
     * Handles tokens after reading the DOCTYPE name.
     */
    private void handleAfterName(Token token, CharBuffer data) throws SAXException {
        switch (token) {
            case S:
                // Whitespace, ignore
                break;

            case START_COMMENT:
                // Comment - just enter comment state (savedState already contains current state)
                commentBuilder = new StringBuilder();
                state = State.IN_COMMENT;
                break;

            case SYSTEM:
                // SYSTEM external ID
                sawPublicKeyword = false;
                sawWhitespaceAfterPublicId = false; // Reset for new external ID
                changeState(State.AFTER_SYSTEM_PUBLIC);
                break;

            case PUBLIC:
                // PUBLIC external ID
                sawPublicKeyword = true;
                sawWhitespaceAfterPublicId = false; // Reset for new external ID
                changeState(State.AFTER_SYSTEM_PUBLIC);
                break;

            case OPEN_BRACKET:
                // Internal subset starts
                changeState(State.IN_INTERNAL_SUBSET);
                nestingDepth = 1;
                // Report start of DTD
                if (lexicalHandler != null) {
                    String publicId = doctypeExternalID != null ? doctypeExternalID.publicId : null;
                    String systemId = doctypeExternalID != null ? doctypeExternalID.systemId : null;
                    lexicalHandler.startDTD(doctypeName, publicId, systemId);
                }
                break;

            case GT:
                // End of DOCTYPE (no external ID, no internal subset)
                changeState(State.DONE);
                // Expand parameter entities in entity values (post-processing)
                expandParameterEntitiesInEntityValues();
                // Report start and end of DTD
                if (lexicalHandler != null) {
                    String publicId = doctypeExternalID != null ? doctypeExternalID.publicId : null;
                    String systemId = doctypeExternalID != null ? doctypeExternalID.systemId : null;
                    lexicalHandler.startDTD(doctypeName, publicId, systemId);
                    lexicalHandler.endDTD();
                }
                break;

            default:
                throw new SAXParseException("Unexpected token after DOCTYPE name: " + token, locator);
        }
    }

    /**
     * Handles tokens after SYSTEM or PUBLIC keyword.
     */
    private void handleAfterSystemPublic(Token token, CharBuffer data) throws SAXException {
        switch (token) {
            case S:
                // Whitespace, ignore
                break;

            case START_COMMENT:
                // Comment - just enter comment state (savedState already contains current state)
                commentBuilder = new StringBuilder();
                state = State.IN_COMMENT;
                break;

            case QUOT:
            case APOS:
                // Start of quoted string, ignore quote
                break;

            case CDATA:
                // This is the ID string
                if (sawPublicKeyword) {
                    // PUBLIC keyword - first string is publicId, second is systemId
                    if (doctypeExternalID == null) {
                        doctypeExternalID = new ExternalID();
                    }
                    if (doctypeExternalID.publicId == null) {
                        String publicId = extractString(data);
                        validatePublicId(publicId);
                        doctypeExternalID.publicId = publicId;
                        changeState(State.AFTER_PUBLIC_ID);
                    } else {
                        // Second string after PUBLIC
                        doctypeExternalID.systemId = extractString(data);
                        changeState(State.AFTER_EXTERNAL_ID);
                    }
                } else {
                    // SYSTEM keyword - only one string, and it's the systemId
                    if (doctypeExternalID == null) {
                        doctypeExternalID = new ExternalID();
                    }
                    doctypeExternalID.systemId = extractString(data);
                    changeState(State.AFTER_EXTERNAL_ID);
                }
                break;

            default:
                throw new SAXParseException("Expected quoted string for external ID, got: " + token, locator);
        }
    }

    /**
     * Handles tokens after reading the public ID.
     */
    private void handleAfterPublicId(Token token, CharBuffer data) throws SAXException {
        switch (token) {
            case S:
                // Whitespace after public ID - required before system ID
                sawWhitespaceAfterPublicId = true;
                break;

            case START_COMMENT:
                // Comment - just enter comment state (savedState already contains current state)
                commentBuilder = new StringBuilder();
                state = State.IN_COMMENT;
                break;

            case QUOT:
            case APOS:
                // Quote opening next string - must have whitespace first
                if (!sawWhitespaceAfterPublicId) {
                    throw new SAXParseException("Expected whitespace between public ID and system ID", locator);
                }
                // Opening quote for system ID, ignore
                break;

            case CDATA:
                // This is the system ID - must have seen whitespace and quote first
                if (!sawWhitespaceAfterPublicId) {
                    throw new SAXParseException("Expected whitespace between public ID and system ID", locator);
                }
                if (doctypeExternalID == null) {
                    doctypeExternalID = new ExternalID();
                }
                doctypeExternalID.systemId = extractString(data);
                changeState(State.AFTER_EXTERNAL_ID);
                break;

            default:
                throw new SAXParseException("Expected system ID after public ID, got: " + token, locator);
        }
    }

    /**
     * Handles tokens after reading the external ID.
     */
    private void handleAfterExternalId(Token token, CharBuffer data) throws SAXException {
        switch (token) {
            case S:
                // Whitespace, ignore
                break;

            case QUOT:
            case APOS:
                // Quote closing system ID, ignore
                break;

            case START_COMMENT:
                // Comment - just enter comment state (savedState already contains current state)
                commentBuilder = new StringBuilder();
                state = State.IN_COMMENT;
                break;

            case OPEN_BRACKET:
                // Internal subset starts (after external ID)
                // Report start of DTD
                if (lexicalHandler != null) {
                    String publicId = doctypeExternalID != null ? doctypeExternalID.publicId : null;
                    String systemId = doctypeExternalID != null ? doctypeExternalID.systemId : null;
                    lexicalHandler.startDTD(doctypeName, publicId, systemId);
                }
                // Enter internal subset state FIRST (so external DTD tokens are processed correctly)
                changeState(State.IN_INTERNAL_SUBSET);
                nestingDepth = 1;
                // Now process external DTD subset (tokens processed as internal subset)
                processExternalDTDSubset();
                // State remains IN_INTERNAL_SUBSET to process actual [ ... ] content
                break;

            case GT:
                // End of DOCTYPE (external ID, no internal subset)
                // Expand parameter entities in entity values (post-processing)
                expandParameterEntitiesInEntityValues();
                // Report start of DTD
                if (lexicalHandler != null) {
                    String publicId = doctypeExternalID != null ? doctypeExternalID.publicId : null;
                    String systemId = doctypeExternalID != null ? doctypeExternalID.systemId : null;
                    lexicalHandler.startDTD(doctypeName, publicId, systemId);
                }
                // Enter internal subset state (even though there's no [ ... ])
                // This allows external DTD tokens to be processed as internal subset
                changeState(State.IN_INTERNAL_SUBSET);
                nestingDepth = 0; // No bracket nesting
                // Process external DTD subset (tokens processed as internal subset)
                processExternalDTDSubset();
                // External DTD processing complete, now we're done
                changeState(State.DONE);
                // Report end of DTD
                if (lexicalHandler != null) {
                    lexicalHandler.endDTD();
                }
                break;

            default:
                throw new SAXParseException("Unexpected token after external ID: " + token, locator);
        }
    }

    /**
     * Handles tokens within the internal subset (between [ and ]).
     */
    private void handleInInternalSubset(Token token, CharBuffer data) throws SAXException {
        switch (token) {
            case CLOSE_BRACKET:
                // End of internal subset
                // However, if we see CLOSE_BRACKET and conditionalDepth > 0, it means
                // we have an improperly terminated conditional section (should be ]]> not ]>)
                if (conditionalDepth > 0) {
                    throw new SAXParseException(
                        "Conditional section not properly terminated (expected ']]>' but got ']>')",
                        locator);
                }
                nestingDepth--;
                if (nestingDepth == 0) {
                    changeState(State.AFTER_INTERNAL_SUBSET);
                }
                break;

            case OPEN_BRACKET:
                // Nested bracket (e.g., in conditional sections)
                nestingDepth++;
                break;

            case START_ELEMENTDECL:
                // Start parsing element declaration - create dedicated parser
                savedState = state;
                state = State.IN_ELEMENTDECL;
                elementDeclParser = new ElementDeclParser(this, locator);
                break;

            case START_ATTLISTDECL:
                // Start parsing attribute list declaration - instantiate parser
                savedState = state;
                state = State.IN_ATTLISTDECL;
                attListDeclParser = new AttListDeclParser(this, locator);
                break;

            case START_ENTITYDECL:
                // Start parsing entity declaration - instantiate parser
                savedState = state;
                state = State.IN_ENTITYDECL;
                entityDeclParser = new EntityDeclParser(this, locator, savedState);
                break;

            case START_NOTATIONDECL:
                // Start parsing notation declaration - instantiate parser
                savedState = state;
                state = State.IN_NOTATIONDECL;
                notationDeclParser = new NotationDeclParser(this, locator);
                break;

            case START_COMMENT:
                // Comment - just enter comment state (savedState already contains current state)
                commentBuilder = new StringBuilder();
                state = State.IN_COMMENT;
                break;

            case START_PI:
                // Processing instruction - just enter PI state (savedState already contains current state)
                piTarget = null;
                piDataBuilder = new StringBuilder();
                state = State.IN_PI;
                break;

            case START_CONDITIONAL:
                // Conditional sections are ONLY allowed in external DTD subset
                // Check if we're processing an external entity
                if (!xmlParser.isProcessingExternalEntity()) {
                    throw new SAXParseException(
                        "Conditional sections are not allowed in internal DTD subset", locator);
                }
                // Enter conditional section parsing
                conditionalState = ConditionalSectionState.EXPECT_KEYWORD;
                savedState = state; // Save current state to return to after conditional section
                changeState(State.IN_CONDITIONAL);
                break;

            case PARAMETERENTITYREF:
                // XXX: This assumes parameter entities are declared BEFORE they are referenced.
                // XXX: If the XML spec allows forward references (PE declared after use),
                // XXX: this code will fail and we'll need a two-pass approach or deferred expansion.
                // XXX: Mark this location if we encounter such a test case.
                
                // Parameter entity expansion in DTD markup
                // Direct parameter entity references in DTD declarations (not in entity values)
                // Example: <!ELEMENT doc %content-model;>
                // This requires inline expansion during DTD parsing.
                String refName = extractString(data);
                expandParameterEntityInline(refName);
                break;
                    
            case START_XMLDECL:
                // XML declarations are not allowed in DTD
                throw new SAXParseException("XML declaration is not allowed in DOCTYPE internal subset", locator);
                
            case START_CDATA:
                // CDATA sections are not allowed in DTD
                throw new SAXParseException("CDATA sections are not allowed in DOCTYPE internal subset", locator);

            default:
                // Other tokens within declarations, ignore for now
                break;
        }
    }

    /**
     * Handles tokens after the internal subset closing bracket.
     */
    private void handleAfterInternalSubset(Token token, CharBuffer data) throws SAXException {
        switch (token) {
            case S:
                // Whitespace, ignore
                break;

            case START_COMMENT:
                // Comment - just enter comment state (savedState already contains current state)
                commentBuilder = new StringBuilder();
                state = State.IN_COMMENT;
                break;

            case GT:
                // End of DOCTYPE
                changeState(State.DONE);
                // Expand parameter entities in entity values (post-processing)
                expandParameterEntitiesInEntityValues();
                // Report end of DTD
                if (lexicalHandler != null) {
                    lexicalHandler.endDTD();
                }
                break;

            default:
                throw new SAXParseException("Expected GT after internal subset, got: " + token, locator);
        }
    }

    /**
     * Handles tokens within an &lt;!ELEMENT declaration.
     * Uses a sub-state machine to enforce well-formedness constraints.
     * Builds the ContentModel tree incrementally using a stack.
     */
    private void handleInElementDecl(Token token, CharBuffer data) throws SAXException {
        // Delegate to ElementDeclParser
        if (elementDeclParser != null) {
            boolean complete = elementDeclParser.receive(token, data);
            if (complete) {
                // Declaration complete, return to saved state
                state = savedState;
                elementDeclParser = null;
            }
        } else {
            throw new SAXParseException("No element declaration parser active", locator);
        }
    }
    
    /**
     * Handles tokens within a comment (&lt;!-- ... --&gt;).
     * Accumulates CDATA chunks until END_COMMENT is encountered.
     * Comments can appear in various locations within the DTD.
     */
    private void handleInComment(Token token, CharBuffer data) throws SAXException {
        switch (token) {
            case CDATA:
            case S:
                // Accumulate comment text (may receive multiple chunks)
                if (data != null) {
                    commentBuilder.append(extractString(data));
                }
                break;
                
            case END_COMMENT:
                // End of comment - emit event and return to saved state
                if (lexicalHandler != null) {
                    String text = commentBuilder.toString();
                    lexicalHandler.comment(text.toCharArray(), 0, text.length());
                }
                commentBuilder = null;
                state = savedState;
                break;
                
            default:
                // Other tokens might be part of comment content
                // (tokenizer should emit everything as CDATA within comments)
                break;
        }
    }
    
    /**
     * Handles tokens within a processing instruction (&lt;? ... ?&gt;).
     * First token must be NAME (PI target), followed by optional data (CDATA chunks), then END_PI.
     * PIs can appear in various locations within the DTD.
     */
    private void handleInPI(Token token, CharBuffer data) throws SAXException {
        switch (token) {
            case NAME:
                // First token should be the PI target
                if (piTarget == null) {
                    piTarget = extractString(data);
                } else {
                    throw new SAXParseException("Unexpected NAME token in PI data", locator);
                }
                break;
                
            case S:
            case CDATA:
                // Accumulate PI data (may receive multiple chunks)
                if (data != null) {
                    piDataBuilder.append(extractString(data));
                }
                break;
                
            case END_PI:
                // End of PI - emit event and return to saved state
                if (piTarget == null) {
                    throw new SAXParseException("Processing instruction missing target", locator);
                }
                if (contentHandler != null) {
                    contentHandler.processingInstruction(piTarget, piDataBuilder.toString());
                }
                piTarget = null;
                piDataBuilder = null;
                state = savedState;
                break;
                
            default:
                // Unexpected token in PI
                throw new SAXParseException("Unexpected token in processing instruction: " + token, locator);
        }
    }
    
    /**
     * Handles tokens in the IN_CONDITIONAL state (after &lt;![, expecting keyword).
     */
    private void handleInConditional(Token token, CharBuffer data) throws SAXException {
        switch (conditionalState) {
            case EXPECT_KEYWORD:
                switch (token) {
                    case S:
                        // Skip whitespace before keyword
                        break;
                        
                    case INCLUDE:
                        // INCLUDE section - will process markup normally
                        conditionalIsInclude = true;
                        conditionalState = ConditionalSectionState.AFTER_KEYWORD;
                        break;
                        
                    case IGNORE:
                        // IGNORE section - will skip all content
                        conditionalIsInclude = false;
                        conditionalState = ConditionalSectionState.AFTER_KEYWORD;
                        break;
                        
                    case PARAMETERENTITYREF:
                        // Parameter entity reference for keyword (e.g., <![ %draft; [...)
                        // Expand inline to get INCLUDE or IGNORE
                        String refName = extractString(data);
                        expandParameterEntityInline(refName);
                        // After expansion, we should receive INCLUDE or IGNORE token
                        break;
                        
                    default:
                        throw new SAXParseException(
                            "Expected INCLUDE, IGNORE, or parameter entity reference in conditional section, got: " + token,
                            locator);
                }
                break;
                
            case AFTER_KEYWORD:
                switch (token) {
                    case S:
                        // Skip whitespace after keyword
                        conditionalState = ConditionalSectionState.EXPECT_OPEN_BRACKET;
                        break;
                        
                    case OPEN_BRACKET:
                        // '[' directly after keyword (whitespace is optional per XML spec)
                        // Start of conditional section content
                        conditionalDepth++;
                        // Transition to INCLUDE or IGNORE mode based on keyword
                        if (conditionalIsInclude) {
                            changeState(State.IN_CONDITIONAL_INCLUDE);
                        } else {
                            changeState(State.IN_CONDITIONAL_IGNORE);
                        }
                        break;
                        
                    default:
                        throw new SAXParseException(
                            "Expected whitespace or '[' after INCLUDE/IGNORE keyword, got: " + token,
                            locator);
                }
                break;
                
            case EXPECT_OPEN_BRACKET:
                switch (token) {
                    case S:
                        // Skip additional whitespace
                        break;
                        
                    case OPEN_BRACKET:
                        // Start of conditional section content
                        conditionalDepth++;
                        // Transition to INCLUDE or IGNORE mode based on keyword
                        if (conditionalIsInclude) {
                            changeState(State.IN_CONDITIONAL_INCLUDE);
                        } else {
                            changeState(State.IN_CONDITIONAL_IGNORE);
                        }
                        break;
                        
                    default:
                        throw new SAXParseException(
                            "Expected '[' after INCLUDE/IGNORE keyword, got: " + token,
                            locator);
                }
                break;
        }
    }
    
    /**
     * Handles tokens in the IN_CONDITIONAL_INCLUDE state (processing markup in INCLUDE section).
     */
    private void handleInConditionalInclude(Token token, CharBuffer data) throws SAXException {
        switch (token) {
            case START_CONDITIONAL:
                // Nested conditional section
                conditionalDepth++;
                // Recursively handle this nested conditional section
                conditionalState = ConditionalSectionState.EXPECT_KEYWORD;
                savedState = State.IN_CONDITIONAL_INCLUDE; // Return here after nested section
                changeState(State.IN_CONDITIONAL);
                break;
                
            case END_CONDITIONAL:
                // End of conditional section - ]]>
                conditionalDepth--;
                if (conditionalDepth == 0) {
                    // End of outermost INCLUDE section, return to saved state
                    state = savedState;
                } else {
                    // End of nested INCLUDE section, stay in INCLUDE mode
                    // (no state change needed)
                }
                break;
                
            default:
                // All other tokens are processed as normal DTD markup
                // Delegate to handleInInternalSubset
                handleInInternalSubset(token, data);
                break;
        }
    }
    
    /**
     * Handles tokens in the IN_CONDITIONAL_IGNORE state (skipping content in IGNORE section).
     */
    private void handleInConditionalIgnore(Token token, CharBuffer data) throws SAXException {
        switch (token) {
            case START_CONDITIONAL:
                // Nested conditional section - increment depth but keep ignoring
                conditionalDepth++;
                break;
                
            case END_CONDITIONAL:
                // End of conditional section - ]]>
                conditionalDepth--;
                if (conditionalDepth == 0) {
                    // End of outermost IGNORE section, return to saved state
                    state = savedState;
                }
                // Otherwise stay in IGNORE mode for nested sections
                break;
                
            default:
                // Ignore all other tokens (don't process them)
                break;
        }
    }
    
    /**
     * Helper method to save the current attribute being parsed.
     * Called when we detect a new attribute starting or when GT is encountered.
     * Adds the attribute to currentAttlistMap keyed by attribute name.
     */
    /**
     * Helper method to save the current notation declaration.
     * Adds the notation to the notations map keyed by notation name.
     * Also reports to DTDHandler if present.
     */
    /**
     * Handles tokens while parsing an &lt;!ENTITY declaration.
     * Uses a state machine to track position within the declaration.
     */
    /**
     * Helper method to save the entire ATTLIST declaration.
     * Called when GT is encountered at the end of the ATTLIST.
     * Merges currentAttlistMap into attributeDecls keyed by element name.
     */
    /**
     * Extracts a string from a CharBuffer.
     * @param buffer the buffer containing the string
     * @return the extracted string
     */
    private String extractString(CharBuffer buffer) {
        if (buffer == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        while (buffer.hasRemaining()) {
            sb.append(buffer.get());
        }
        return sb.toString();
    }
    
    /**
     * Changes the parser state and saves the new state.
     * This ensures savedState always contains the current state for returning from comments.
     * Use this instead of direct state assignment (except for START_COMMENT).
     * 
     * @param newState the new state to transition to
     */
    private void changeState(State newState) {
        savedState = state = newState;
        
        // If DTD parsing is complete, notify ContentParser to switch to CONTENT state
        if (newState == State.DONE && xmlParser != null) {
            xmlParser.dtdComplete();
        }
    }

    /**
     * Gets the DOCTYPE name (root element name).
     * @return the DOCTYPE name, or null if not yet parsed
     */
    public String getDoctypeName() {
        return doctypeName;
    }

    /**
     * Gets the external ID for the external DTD subset.
     * @return the ExternalID, or null if not specified
     */
    public ExternalID getDoctypeExternalID() {
        return doctypeExternalID;
    }

    /**
     * Gets the public identifier for the external DTD subset.
     * @return the public ID, or null if not specified
     */
    public String getPublicId() {
        return doctypeExternalID != null ? doctypeExternalID.publicId : null;
    }

    /**
     * Gets the system identifier for the external DTD subset.
     * @return the system ID, or null if not specified
     */
    public String getSystemId() {
        return doctypeExternalID != null ? doctypeExternalID.systemId : null;
    }

    /**
     * Gets the element declaration for a given element name.
     *
     * @param elementName the element name
     * @return the ElementDeclaration, or null if not declared
     */
    public ElementDeclaration getElementDeclaration(String elementName) {
        return elementDecls != null ? elementDecls.get(elementName) : null;
    }

    /**
     * Gets the attribute declaration for a specific attribute on an element.
     *
     * @param elementName the element name
     * @param attributeName the attribute name
     * @return the AttributeDeclaration, or null if not declared
     */
    public AttributeDeclaration getAttributeDeclaration(String elementName, String attributeName) {
        if (attributeDecls == null) {
            return null;
        }
        // Intern keys for fast comparison
        elementName = elementName.intern();
        attributeName = attributeName.intern();
        
        Map<String, AttributeDeclaration> elementAttrs = attributeDecls.get(elementName);
        if (elementAttrs == null) {
            return null;
        }
        return elementAttrs.get(attributeName);
    }
    
    /**
     * Gets all attribute declarations for a specific element.
     * @param elementName the element name
     * @return Map of attribute name → AttributeDeclaration, or null if no attributes declared
     */
    public Map<String, AttributeDeclaration> getAttributeDeclarations(String elementName) {
        if (attributeDecls == null) {
            return null;
        }
        return attributeDecls.get(elementName.intern());
    }
    
    /**
     * Gets the external ID for a notation.
     * @param notationName the notation name
     * @return the ExternalID, or null if not declared
     */
    public ExternalID getNotation(String notationName) {
        if (notations == null) {
            return null;
        }
        return notations.get(notationName.intern());
    }
    
    /**
     * Returns the set of declared notation names.
     * 
     * @return set of notation names, or null if no notations declared
     */
    public java.util.Set<String> getNotationNames() {
        if (notations == null) {
            return null;
        }
        return notations.keySet();
    }
    
    /**
     * Retrieves a general entity declaration by name.
     * 
     * @param entityName the entity name
     * @return the EntityDeclaration, or null if not found
     */
    public EntityDeclaration getGeneralEntity(String entityName) {
        if (entities == null) {
            return null;
        }
        return entities.get(entityName.intern());
    }
    
    /**
     * Retrieves a parameter entity declaration by name.
     * 
     * @param entityName the entity name
     * @return the EntityDeclaration, or null if not found
     */
    public EntityDeclaration getParameterEntity(String entityName) {
        if (parameterEntities == null) {
            return null;
        }
        return parameterEntities.get(entityName.intern());
    }

    /**
     * Stores an element declaration.
     * Called internally when parsing &lt;!ELEMENT declarations.
     * Package-private to allow access from ElementDeclParser.
     *
     * @param decl the element declaration to store
     */
    void addElementDeclaration(ElementDeclaration decl) {
        if (elementDecls == null) {
            elementDecls = new HashMap<>();
        }
        // Intern element name for fast comparison
        elementDecls.put(decl.name.intern(), decl);
    }

    /**
     * Stores attribute declarations for an element.
     * Called internally when parsing &lt;!ATTLIST declarations.
     * Package-private to allow access from AttListDeclParser.
     *
     * @param elementName the element name these attributes belong to
     * @param attributeMap the map of attribute declarations keyed by attribute name
     */
    void addAttributeDeclarations(String elementName, Map<String, AttributeDeclaration> attributeMap) {
        if (attributeMap == null || attributeMap.isEmpty()) {
            return;
        }
        
        // Ensure attributeDecls map exists
        if (attributeDecls == null) {
            attributeDecls = new HashMap<>();
        }
        
        // Get or create the attribute map for this element
        String internedElementName = elementName.intern();
        Map<String, AttributeDeclaration> elementAttrs = attributeDecls.get(internedElementName);
        if (elementAttrs == null) {
            // First ATTLIST for this element - use the provided map directly
            attributeDecls.put(internedElementName, attributeMap);
        } else {
            // Merge with existing attributes for this element
            elementAttrs.putAll(attributeMap);
        }
    }

    /**
     * Stores a notation declaration.
     * Called internally when parsing &lt;!NOTATION declarations.
     * Package-private to allow access from NotationDeclParser.
     *
     * @param notationName the notation name
     * @param externalID the external ID for the notation
     * @throws SAXException if reporting to DTDHandler fails
     */
    void addNotationDeclaration(String notationName, ExternalID externalID) throws SAXException {
        if (notationName == null || externalID == null) {
            return;
        }
        
        // Ensure notations map exists (lazy initialization)
        if (notations == null) {
            notations = new HashMap<>();
        }
        
        // Add to notations map keyed by notation name (interned)
        notations.put(notationName.intern(), externalID);
        
        // Report to DTDHandler if present
        if (dtdHandler != null) {
            String publicId = externalID.publicId;
            String systemId = externalID.systemId;
            dtdHandler.notationDecl(notationName, publicId, systemId);
        }
    }

    /**
     * Stores an entity declaration.
     * Called internally when parsing &lt;!ENTITY declarations.
     * Package-private to allow access from EntityDeclParser.
     *
     * @param entity the entity declaration to store
     * @throws SAXException if reporting to DTDHandler fails
     */
    void addEntityDeclaration(EntityDeclaration entity) throws SAXException {
        if (entity == null || entity.name == null) {
            return;
        }
        
        if (entity.isParameter) {
            // Parameter entity
            if (parameterEntities == null) {
                parameterEntities = new HashMap<>();
            }
            // Per XML 1.0 § 4.2: first declaration is binding
            String internedName = entity.name.intern();
            if (!parameterEntities.containsKey(internedName)) {
                parameterEntities.put(internedName, entity);
            }
        } else {
            // General entity
            if (entities == null) {
                entities = new HashMap<>();
            }
            // Per XML 1.0 § 4.2: first declaration is binding
            String internedName = entity.name.intern();
            if (!entities.containsKey(internedName)) {
                entities.put(internedName, entity);
            }
            
            // Report unparsed entity to DTDHandler if applicable
            if (entity.isUnparsed() && dtdHandler != null) {
                dtdHandler.unparsedEntityDecl(
                    entity.name,
                    entity.externalID.publicId,
                    entity.externalID.systemId,
                    entity.notationName
                );
            }
        }
    }

    /**
     * Processes the external DTD subset by resolving and parsing it.
     *
     * <p>This method is called when an external ID (SYSTEM or PUBLIC) is
     * present in the DOCTYPE declaration. It uses the parent ContentParser's
     * processExternalEntity method to resolve and parse the external DTD.
     *
     * <p>The external DTD subset is processed **as if it were an internal subset**.
     * The tokens from the external DTD are fed back through the ContentParser to this
     * DTDParser, which processes them as markup declarations. The only difference
     * from a true internal subset is that there are no surrounding [ and ] brackets.
     *
     * <p>Processing order:
     * <ul>
     *   <li>If no internal subset (&lt;!DOCTYPE root SYSTEM "file.dtd"&gt;):
     *       External DTD processed, then DOCTYPE ends (&gt;)</li>
     *   <li>If internal subset present (&lt;!DOCTYPE root SYSTEM "file.dtd" [ ... ]&gt;):
     *       External DTD processed first, then internal subset [ ... ]</li>
     * </ul>
     *
     * <p>If external parameter entities are disabled or if resolution fails,
     * this method returns silently without error.
     *
     * @throws SAXException if DTD processing fails
     */
    private void processExternalDTDSubset() throws SAXException {
        // Only process if we have an external ID
        if (doctypeExternalID == null) {
            return;
        }
        
        String publicId = doctypeExternalID.publicId;
        String systemId = doctypeExternalID.systemId;
        
        // Must have at least one ID
        if (systemId == null && publicId == null) {
            return;
        }
        
        try {
            // Use ContentParser's processExternalEntity to resolve and parse
            // the external DTD subset. This will create a nested Tokenizer
            // that sends tokens back through ContentParser to this DTDParser.
            // The DTDParser is in IN_INTERNAL_SUBSET state, so it processes
            // the tokens as markup declarations.
            xmlParser.processExternalEntity(doctypeName, publicId, systemId);
        } catch (IOException e) {
            // I/O error resolving external DTD
            // Report as SAX error if we have an error handler
            if (errorHandler != null) {
                errorHandler.fatalError(new org.xml.sax.SAXParseException(
                    "Failed to resolve external DTD subset: " + systemId, locator, e));
            }
            throw new SAXParseException("Failed to resolve external DTD subset: " + systemId, locator, e);
        }
    }
    
    /**
     * Post-processes all entity declarations to expand parameter entity references.
     * This must be called after the DOCTYPE declaration is complete, because
     * parameter entities can have forward references (an entity can reference
     * a parameter entity declared later in the DTD).
     * 
     * <p>This method iterates through all general and parameter entity declarations
     * and expands any ParameterEntityReference objects in their replacementText.
     * 
     * @throws SAXException if parameter entity expansion fails (undefined entity,
     *         circular reference, etc.)
     */
    private void expandParameterEntitiesInEntityValues() throws SAXException {
        // Expand parameter entities in general entity values
        if (entities != null) {
            for (EntityDeclaration entity : entities.values()) {
                if (entity.replacementText != null && !entity.isExternal()) {
                    entity.replacementText = expandParameterReferencesInList(entity.replacementText);
                }
            }
        }
        
        // Expand parameter entities in parameter entity values
        if (parameterEntities != null) {
            for (EntityDeclaration entity : parameterEntities.values()) {
                if (entity.replacementText != null && !entity.isExternal()) {
                    entity.replacementText = expandParameterReferencesInList(entity.replacementText);
                }
            }
        }
        
        // Note: We also need to expand parameter entities in attribute default values
        if (attributeDecls != null) {
            for (Map<String, AttributeDeclaration> elementAttrs : attributeDecls.values()) {
                for (AttributeDeclaration attr : elementAttrs.values()) {
                    if (attr.defaultValue != null) {
                        attr.defaultValue = expandParameterReferencesInList(attr.defaultValue);
                    }
                }
            }
        }
    }
    
    /**
     * Expands all ParameterEntityReference objects in a list to their expanded values.
     * 
     * @param list the list containing String and ParameterEntityReference/GeneralEntityReference objects
     * @return a new list with ParameterEntityReference objects replaced by their expanded content
     * @throws SAXException if parameter entity expansion fails
     */
    private List<Object> expandParameterReferencesInList(List<Object> list) throws SAXException {
        if (list == null || list.isEmpty()) {
            return list;
        }
        
        // Check if there are any parameter entity references
        boolean hasParamRefs = false;
        for (Object part : list) {
            if (part instanceof ParameterEntityReference) {
                hasParamRefs = true;
                break;
            }
        }
        
        if (!hasParamRefs) {
            return list; // No parameter entities to expand
        }
        
        // Expand parameter entity references
        List<Object> result = new ArrayList<>();
        
        for (Object part : list) {
            if (part instanceof ParameterEntityReference) {
                // Expand the parameter entity reference
                ParameterEntityReference ref = (ParameterEntityReference) part;
                String expanded = entityStack.expandParameterEntity(ref.name, EntityExpansionContext.ENTITY_VALUE);
                
                if (expanded == null) {
                    throw new SAXParseException(
                        "External parameter entity reference in entity value requires async resolution: %" + ref.name + ";",
                        locator);
                }
                
                // The expanded value is a string - add it to the result
                // Note: The expanded value might itself contain general entity references,
                // which will remain as GeneralEntityReference objects for deferred expansion
                if (!expanded.isEmpty()) {
                    result.add(expanded);
                }
            } else {
                // Keep strings and general entity references as-is
                result.add(part);
            }
        }
        
        return result;
    }
    
    /**
     * Expands a parameter entity reference inline in DTD markup.
     * Creates a tokenizer for the entity's replacement text and feeds tokens
     * back through this DTD parser.
     * Package-private to allow access from declaration parsers.
     * 
     * XXX: Assumes parameter entity is already declared (no forward references).
     * 
     * @param entityName the parameter entity name (without % and ;)
     * @throws SAXException if expansion fails
     */
    void expandParameterEntityInline(String entityName) throws SAXException {
        // Use EntityStack to get the expanded value (handles recursion checking)
        String expandedValue = entityStack.expandParameterEntity(entityName, EntityExpansionContext.DTD);
        
        // If null, it's an external entity requiring async resolution
        if (expandedValue == null) {
            // External parameter entity - resolve and process it
            EntityDeclaration entity = getParameterEntity(entityName);
            if (entity != null && entity.externalID != null) {
                try {
                    xmlParser.processExternalEntity(
                        entityName, 
                        entity.externalID.publicId, 
                        entity.externalID.systemId);
                } catch (IOException e) {
                    throw new SAXParseException(
                        "Failed to resolve external parameter entity %" + entityName + ";",
                        locator, e);
                }
            }
            return;
        }
        
        // If empty, nothing to tokenize
        if (expandedValue.isEmpty()) {
            return;
        }
        
        // For inline DTD expansion, we need to tokenize the expanded value
        // Create entity context for this tokenization
        EntityStackEntry parentEntry = entityStack.peek();
        boolean currentVersion = parentEntry != null ? parentEntry.isXML11 : false;
        
        // Push entity context onto stack for tokenization
        EntityStackEntry entry = new EntityStackEntry(
            entityName, true /* parameter entity */, currentVersion, 0 /* element depth N/A for DTD */);
        entityStack.push(entry);
        
        // Create a tokenizer for the replacement text and feed tokens through DTD parser
        // Use the current tokenizer state to ensure proper context
        try {
            // Create a tokenizer with the current tokenizer state and XML version
            Tokenizer tokenizer = new Tokenizer(this, currentTokenizerState, currentVersion);
            
            // Set the locator for position information
            tokenizer.setLocator(locator);
            
            // Feed the replacement text through the tokenizer as characters
            // (internal entity values are already decoded strings)
            java.nio.CharBuffer buffer = java.nio.CharBuffer.wrap(expandedValue);
            tokenizer.receive(buffer);
            tokenizer.close();
            
        } catch (SAXException e) {
            throw new SAXParseException(
                "Error expanding parameter entity %" + entityName + ";",
                locator, e);
        } finally {
            // Pop entity context to restore parent's state
            if (!entityStack.isEmpty() && entityStack.peek() == entry) {
                entityStack.pop();
            }
        }
    }
    
    /**
     * Validates that all structures (especially conditional sections) are properly closed
     * when an external entity finishes processing.
     * 
     * Called by ContentParser after an external entity has been fully processed.
     */
    void validateExternalEntityClosed() throws SAXParseException {
        // Check if we have unclosed conditional sections
        if (conditionalDepth > 0) {
            throw new SAXParseException(
                "Conditional section not properly terminated (missing ']]>' at end of external entity)",
                locator);
        }
        
        // Check if we're in the middle of parsing a conditional section
        if (state == State.IN_CONDITIONAL || 
            state == State.IN_CONDITIONAL_INCLUDE || 
            state == State.IN_CONDITIONAL_IGNORE) {
            throw new SAXParseException(
                "Conditional section not properly terminated (external entity ended mid-section)",
                locator);
        }
    }

}


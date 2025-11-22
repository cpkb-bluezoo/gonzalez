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
    
    /**
     * Sub-states for parsing &lt;!ELEMENT declarations.
     * Tracks position within the element declaration to enforce well-formedness.
     */
    private enum ElementDeclState {
        EXPECT_NAME,           // After &lt;!ELEMENT, expecting element name
        AFTER_NAME,            // After element name, expecting whitespace
        EXPECT_CONTENTSPEC,    // After whitespace, expecting EMPTY|ANY|(
        IN_CONTENT_MODEL,      // Inside ( ... ), building content model
        AFTER_CONTENTSPEC,     // After content spec, expecting whitespace or &gt;
        EXPECT_GT              // Expecting &gt; to close declaration
    }
    
    private ElementDeclState elementDeclState;
    private int contentModelDepth = 0; // Track parenthesis nesting in content models
    
    /**
     * Sub-states for parsing &lt;!ATTLIST declarations.
     * Tracks position within the attribute list declaration to enforce well-formedness.
     */
    private enum AttListDeclState {
        EXPECT_ELEMENT_NAME,    // After &lt;!ATTLIST, expecting element name
        AFTER_ELEMENT_NAME,     // After element name, expecting whitespace
        EXPECT_ATTR_OR_GT,      // Expecting attribute name or &gt;
        AFTER_ATTR_NAME,        // After attribute name, expecting whitespace
        EXPECT_ATTR_TYPE,       // After whitespace, expecting type
        AFTER_ATTR_TYPE,        // After type, expecting whitespace
        EXPECT_DEFAULT_DECL,    // After whitespace, expecting #REQUIRED|#IMPLIED|#FIXED|value
        AFTER_HASH,             // After #, expecting REQUIRED|IMPLIED|FIXED keyword
        AFTER_FIXED,            // After #FIXED, expecting whitespace
        EXPECT_DEFAULT_VALUE,   // After whitespace following #FIXED, expecting quoted value
        AFTER_DEFAULT_VALUE,    // After CDATA value, expecting closing quote
        IN_NOTATION_ENUM        // Inside NOTATION enumeration (name1|name2...)
    }
    
    private AttListDeclState attListDeclState;
    private boolean sawWhitespaceAfterAttrType; // Track whitespace before enumeration
    
    /**
     * Sub-states for parsing &lt;!NOTATION declarations.
     * Tracks position within the notation declaration to enforce well-formedness.
     */
    private enum NotationDeclState {
        EXPECT_NAME,           // After &lt;!NOTATION, expecting notation name
        AFTER_NAME,            // After notation name, expecting whitespace
        EXPECT_EXTERNAL_ID,    // After whitespace, expecting SYSTEM or PUBLIC
        EXPECT_SYSTEM_ID,      // After SYSTEM, expecting quoted system ID
        EXPECT_PUBLIC_ID,      // After PUBLIC, expecting quoted public ID
        AFTER_PUBLIC_ID,       // After public ID, expecting optional system ID or &gt;
        EXPECT_GT              // Expecting &gt; to close declaration
    }
    
    private NotationDeclState notationDeclState;
    private String currentNotationName;
    private ExternalID currentNotationExternalID;
    private boolean sawPublicInNotation;
    
    /**
     * Sub-states for parsing &lt;!ENTITY declarations.
     */
    private enum EntityDeclState {
        EXPECT_PERCENT_OR_NAME,  // After &lt;!ENTITY, expecting optional % or entity name
        EXPECT_NAME,             // After %, expecting parameter entity name
        AFTER_NAME,              // After entity name, expecting whitespace
        EXPECT_VALUE_OR_ID,      // After whitespace, expecting quoted value, SYSTEM, or PUBLIC
        IN_ENTITY_VALUE,         // Accumulating entity value between quotes
        AFTER_ENTITY_VALUE,      // After closing quote of entity value
        EXPECT_SYSTEM_ID,        // After SYSTEM, expecting quoted system ID
        EXPECT_PUBLIC_ID,        // After PUBLIC, expecting quoted public ID
        AFTER_PUBLIC_ID,         // After public ID, expecting system ID
        AFTER_EXTERNAL_ID,       // After external ID, expecting NDATA or &gt;
        EXPECT_NDATA_NAME,       // After NDATA, expecting notation name
        EXPECT_GT                // Expecting &gt; to close declaration
    }
    
    private EntityDeclState entityDeclState;
    private boolean sawWhitespaceAfterEntityPublicId; // Track whitespace requirement in entity decls
    private boolean sawWhitespaceAfterSystemId; // Track whitespace before NDATA
    private boolean sawWhitespaceAfterEntityKeyword;  // Track whitespace after <!ENTITY keyword
    private boolean sawClosingQuoteOfPublicId; // Track if we've seen the closing quote of public ID
    private EntityDeclaration currentEntity;
    private List<Object> entityValueBuilder;  // Accumulates String and GeneralEntityReference
    private StringBuilder entityValueTextBuilder;  // Accumulates current text segment
    private char entityValueQuote;           // The opening quote character (' or ")
    
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
     * Current element declaration being parsed.
     * Created on START_ELEMENTDECL, properties set during parsing, added to map on GT.
     * Uses a stack to build nested content models as tokens arrive.
     */
    private ElementDeclaration currentElementDecl;
    private Deque<ElementDeclaration.ContentModel> contentModelStack;
    private boolean sawWhitespaceInContentModel; // Track whitespace before occurrence indicators
    
    /**
     * Current attribute list declaration being parsed.
     * 
     * <p>currentAttlistElement: The element name these attributes belong to
     * <p>currentAttlistMap: Map of attribute name → AttributeDeclaration being built
     * <p>currentAttributeDecl: The current attribute being parsed (added to map when complete)
     * <p>defaultValueBuilder: Accumulates CDATA chunks for default values (asynchronous parsing)
     * 
     * <p>When an attribute is complete, it's added to currentAttlistMap keyed by its name.
     * When GT is encountered, currentAttlistMap is merged into attributeDecls keyed by currentAttlistElement.
     */
    private String currentAttlistElement;
    private Map<String, AttributeDeclaration> currentAttlistMap;
    private AttributeDeclaration currentAttributeDecl;
    private List<Object> defaultValueBuilder;  // List of String and GeneralEntityReference
    private StringBuilder defaultValueTextBuilder;  // For accumulating text segments
    private List<String> enumerationBuilder;  // For accumulating enumeration values
    
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
     * 
     * @param publicId the public ID to validate
     * @throws SAXException if the public ID contains illegal characters
     */
    private void validatePublicId(String publicId) throws SAXException {
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
                handleInElementDecl(token, data);
                break;
                
            case IN_ATTLISTDECL:
                handleInAttlistDecl(token, data);
                break;
                
            case IN_ENTITYDECL:
                handleInEntityDecl(token, data);
                break;
            
            case IN_NOTATIONDECL:
                handleInNotationDecl(token, data);
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
                // Start parsing element declaration - save current state
                savedState = state;
                state = State.IN_ELEMENTDECL;
                elementDeclState = ElementDeclState.EXPECT_NAME;
                currentElementDecl = new ElementDeclaration();
                contentModelStack = new ArrayDeque<>();
                contentModelDepth = 0;
                break;

            case START_ATTLISTDECL:
                // Start parsing attribute list declaration - save current state
                savedState = state;
                state = State.IN_ATTLISTDECL;
                attListDeclState = AttListDeclState.EXPECT_ELEMENT_NAME;
                currentAttlistElement = null;
                currentAttlistMap = new HashMap<>();
                currentAttributeDecl = null;
                break;

            case START_ENTITYDECL:
                // Start parsing entity declaration - save current state
                savedState = state;
                state = State.IN_ENTITYDECL;
                entityDeclState = EntityDeclState.EXPECT_PERCENT_OR_NAME;
                currentEntity = new EntityDeclaration();
                entityValueBuilder = null;
                entityValueTextBuilder = null;
                entityValueQuote = '\0';
                sawWhitespaceAfterEntityKeyword = false; // Reset whitespace tracking
                break;

            case START_NOTATIONDECL:
                // Start parsing notation declaration - save current state
                savedState = state;
                state = State.IN_NOTATIONDECL;
                notationDeclState = NotationDeclState.EXPECT_NAME;
                currentNotationName = null;
                currentNotationExternalID = new ExternalID();
                sawPublicInNotation = false;
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
        switch (elementDeclState) {
            case EXPECT_NAME:
                // Must see NAME token for element name (whitespace allowed first)
                if (token == Token.NAME) {
                    currentElementDecl.name = extractString(data);
                    elementDeclState = ElementDeclState.AFTER_NAME;
                } else if (token == Token.S) {
                    // Skip whitespace before element name
                } else {
                    throw new SAXParseException("Expected element name after &lt;!ELEMENT, got: " + token, locator);
                }
                break;
                
            case AFTER_NAME:
                // Must see whitespace after element name
                if (token == Token.S) {
                    elementDeclState = ElementDeclState.EXPECT_CONTENTSPEC;
                } else {
                    throw new SAXParseException("Expected whitespace after element name in &lt;!ELEMENT, got: " + token, locator);
                }
                break;
                
            case EXPECT_CONTENTSPEC:
                // Expecting EMPTY | ANY | content model
                switch (token) {
                    case S:
                        // Skip extra whitespace
                        break;
                        
                    case EMPTY:
                        currentElementDecl.contentType = ElementDeclaration.ContentType.EMPTY;
                        elementDeclState = ElementDeclState.AFTER_CONTENTSPEC;
                        break;
                        
                    case ANY:
                        currentElementDecl.contentType = ElementDeclaration.ContentType.ANY;
                        elementDeclState = ElementDeclState.AFTER_CONTENTSPEC;
                        break;
                        
                    case OPEN_PAREN:
                        // Start content model group
                        ElementDeclaration.ContentModel group = new ElementDeclaration.ContentModel();
                        // Don't know if it's SEQUENCE or CHOICE yet - will determine from first separator
                        contentModelStack.push(group);
                        contentModelDepth = 1;
                        elementDeclState = ElementDeclState.IN_CONTENT_MODEL;
                        break;
                        
                    case PARAMETERENTITYREF:
                        // XXX: This assumes parameter entities are declared BEFORE they are referenced.
                        // XXX: If the XML spec allows forward references (PE declared after use),
                        // XXX: this code will fail and we'll need a two-pass approach or deferred expansion.
                        // XXX: Mark this location if we encounter such a test case.
                        
                        // Parameter entity expansion in element content specification
                        // Example: <!ELEMENT doc %content-model;>
                        String refName = extractString(data);
                        expandParameterEntityInline(refName);
                        // After expansion, we expect to be in AFTER_CONTENTSPEC state
                        // (the expanded content should complete the content specification)
                        // For now, stay in EXPECT_CONTENTSPEC to process the expanded tokens
                        break;
                        
                    default:
                        throw new SAXParseException("Expected content specification (EMPTY, ANY, or '(') in &lt;!ELEMENT, got: " + token, locator);
                }
                break;
                
            case IN_CONTENT_MODEL:
                // Inside ( ... ), building content model
                switch (token) {
                    case S:
                        // Whitespace in content model - track it for occurrence indicator validation
                        sawWhitespaceInContentModel = true;
                        break;
                        
                    case NAME:
                        String nameStr = extractString(data);
                        // Handle PCDATA special case (tokenizer may emit as NAME)
                        if (nameStr.equals("PCDATA")) {
                            // Create #PCDATA leaf node
                            ElementDeclaration.ContentModel leaf = new ElementDeclaration.ContentModel();
                            leaf.type = ElementDeclaration.ContentModel.NodeType.PCDATA;
                            leaf.occurrence = ElementDeclaration.ContentModel.Occurrence.ONCE;
                            contentModelStack.peek().addChild(leaf);
                        } else {
                            // Create element name leaf node
                            ElementDeclaration.ContentModel leaf = new ElementDeclaration.ContentModel();
                            leaf.type = ElementDeclaration.ContentModel.NodeType.ELEMENT;
                            leaf.elementName = nameStr;
                            leaf.occurrence = ElementDeclaration.ContentModel.Occurrence.ONCE;
                            contentModelStack.peek().addChild(leaf);
                        }
                        // Reset whitespace flag after adding element
                        sawWhitespaceInContentModel = false;
                        break;
                        
                    case PCDATA:
                        // Create #PCDATA leaf node
                        ElementDeclaration.ContentModel pcdataLeaf = new ElementDeclaration.ContentModel();
                        pcdataLeaf.type = ElementDeclaration.ContentModel.NodeType.PCDATA;
                        pcdataLeaf.occurrence = ElementDeclaration.ContentModel.Occurrence.ONCE;
                        contentModelStack.peek().addChild(pcdataLeaf);
                        // Reset whitespace flag after adding PCDATA
                        sawWhitespaceInContentModel = false;
                        break;
                        
                    case OPEN_PAREN:
                        // Nested group
                        ElementDeclaration.ContentModel nestedGroup = new ElementDeclaration.ContentModel();
                        contentModelStack.push(nestedGroup);
                        contentModelDepth++;
                        break;
                        
                    case CLOSE_PAREN:
                        contentModelDepth--;
                        if (contentModelDepth == 0) {
                            // Exited the top-level content model
                            ElementDeclaration.ContentModel root = contentModelStack.pop();
                            
                            // Validate that content model is not empty
                            if (root.children == null || root.children.isEmpty()) {
                                throw new SAXParseException("Empty content model () is not allowed in <!ELEMENT", locator);
                            }
                            
                            // Set default type if not already set (single element case like "(a)")
                            if (root.type == null) {
                                // Single child or no separator - treat as sequence
                                root.type = ElementDeclaration.ContentModel.NodeType.SEQUENCE;
                            }
                            
                            currentElementDecl.contentModel = root;
                            
                            // Determine content type (validation happens later in saveElementDeclaration)
                            if (containsPCDATA(root)) {
                                currentElementDecl.contentType = ElementDeclaration.ContentType.MIXED;
                            } else {
                            currentElementDecl.contentType = ElementDeclaration.ContentType.ELEMENT;
                        }
                        
                        // Reset whitespace flag after closing paren
                        sawWhitespaceInContentModel = false;
                        elementDeclState = ElementDeclState.AFTER_CONTENTSPEC;
                    } else if (contentModelDepth < 0) {
                        throw new SAXParseException("Unmatched closing parenthesis in &lt;!ELEMENT content model", locator);
                    } else {
                        // Finished a nested group - pop it and add to parent
                        ElementDeclaration.ContentModel completed = contentModelStack.pop();
                        
                        // Set default type if not already set (single element case)
                        if (completed.type == null) {
                            completed.type = ElementDeclaration.ContentModel.NodeType.SEQUENCE;
                        }
                        
                        contentModelStack.peek().addChild(completed);
                        // Reset whitespace flag after closing paren
                        sawWhitespaceInContentModel = false;
                    }
                    break;

                    case PIPE:
                        // Set group type to CHOICE if not already set
                        ElementDeclaration.ContentModel current = contentModelStack.peek();
                        if (current.type == null) {
                            current.type = ElementDeclaration.ContentModel.NodeType.CHOICE;
                        } else if (current.type != ElementDeclaration.ContentModel.NodeType.CHOICE) {
                            throw new SAXParseException("Cannot mix ',' and '|' at same level in content model", locator);
                        }
                        break;
                        
                    case COMMA:
                        // Set group type to SEQUENCE if not already set
                        ElementDeclaration.ContentModel currentSeq = contentModelStack.peek();
                        if (currentSeq.type == null) {
                            currentSeq.type = ElementDeclaration.ContentModel.NodeType.SEQUENCE;
                        } else if (currentSeq.type != ElementDeclaration.ContentModel.NodeType.SEQUENCE) {
                            throw new SAXParseException("Cannot mix ',' and '|' at same level in content model", locator);
                        }
                        break;
                        
                    case QUERY:
                    case PLUS:
                    case STAR:
                        // Occurrence indicator must immediately follow element or ) with no whitespace
                        if (sawWhitespaceInContentModel) {
                            throw new SAXParseException(
                                "Whitespace not allowed before occurrence indicator in content model", locator);
                        }
                        
                        // Apply occurrence indicator to last child added
                        ElementDeclaration.ContentModel parent = contentModelStack.peek();
                        if (parent.children != null && !parent.children.isEmpty()) {
                            ElementDeclaration.ContentModel lastChild = parent.children.get(parent.children.size() - 1);
                            
                            // Check for double occurrence indicator (e.g., *?)
                            if (lastChild.occurrence != ElementDeclaration.ContentModel.Occurrence.ONCE) {
                                throw new SAXParseException(
                                    "Multiple occurrence indicators not allowed in content model", locator);
                            }
                            
                            lastChild.occurrence = token == Token.QUERY ? ElementDeclaration.ContentModel.Occurrence.OPTIONAL :
                                                  token == Token.PLUS ? ElementDeclaration.ContentModel.Occurrence.ONE_OR_MORE :
                                                  ElementDeclaration.ContentModel.Occurrence.ZERO_OR_MORE;
                        }
                        break;
                        
                    case HASH:
                        // Hash by itself (followed by PCDATA token or NAME("PCDATA"))
                        // Just ignore - will be handled by PCDATA or NAME token
                        break;
                        
                    default:
                        throw new SAXParseException("Unexpected token in &lt;!ELEMENT content model: " + token, locator);
                }
                break;
                
            case AFTER_CONTENTSPEC:
                // After content spec, expecting optional whitespace then GT
                switch (token) {
                    case S:
                        // Optional whitespace before GT
                        elementDeclState = ElementDeclState.EXPECT_GT;
                        break;
                        
                    case GT:
                        // Save and return
                        saveElementDeclaration();
                        state = savedState;
                        break;
                        
                    case QUERY:
                    case PLUS:
                    case STAR:
                        // Occurrence indicator after content spec - apply to root
                        if (currentElementDecl.contentModel != null) {
                            currentElementDecl.contentModel.occurrence = 
                                token == Token.QUERY ? ElementDeclaration.ContentModel.Occurrence.OPTIONAL :
                                token == Token.PLUS ? ElementDeclaration.ContentModel.Occurrence.ONE_OR_MORE :
                                ElementDeclaration.ContentModel.Occurrence.ZERO_OR_MORE;
                        }
                        // Stay in AFTER_CONTENTSPEC to allow optional whitespace before GT
                        break;
                        
                    default:
                        throw new SAXParseException("Expected '&gt;' to close &lt;!ELEMENT declaration, got: " + token, locator);
                }
                break;
                
            case EXPECT_GT:
                // Must see GT to close declaration
                if (token == Token.GT) {
                    saveElementDeclaration();
                    state = savedState;
                } else if (token == Token.S) {
                    // Allow extra whitespace
                } else {
                    throw new SAXParseException("Expected '&gt;' to close &lt;!ELEMENT declaration, got: " + token, locator);
                }
                break;
        }
    }
    
    /**
     * Helper to check if a content model contains #PCDATA.
     */
    private boolean containsPCDATA(ElementDeclaration.ContentModel model) {
        if (model.type == ElementDeclaration.ContentModel.NodeType.PCDATA) {
            return true;
        }
        if (model.children != null) {
            for (ElementDeclaration.ContentModel child : model.children) {
                if (containsPCDATA(child)) {
                    return true;
                }
            }
        }
        return false;
    }
    
    /**
     * Validates mixed content constraints per XML spec:
     * 1. If content model contains #PCDATA, it must be a CHOICE group at top level (or just #PCDATA alone)
     * 2. #PCDATA must be first in the choice
     * 3. The occurrence indicator on #PCDATA must be * or none (not + or ?)
     * 4. No nested groups allowed in mixed content
     */
    private void validateMixedContent(ElementDeclaration.ContentModel model) throws SAXException {
        // Special case: (#PCDATA) alone is valid - could be SEQUENCE with single PCDATA child
        if (model.children != null && model.children.size() == 1) {
            ElementDeclaration.ContentModel child = model.children.get(0);
            if (child.type == ElementDeclaration.ContentModel.NodeType.PCDATA) {
                // This is (#PCDATA) which is valid
                // Check occurrence on the PCDATA itself
                if (child.occurrence != ElementDeclaration.ContentModel.Occurrence.ONCE &&
                    child.occurrence != ElementDeclaration.ContentModel.Occurrence.ZERO_OR_MORE) {
                    throw new SAXParseException(
                        "Mixed content with #PCDATA can only have * or no occurrence indicator", locator);
                }
                // Check occurrence on the group
                if (model.occurrence != ElementDeclaration.ContentModel.Occurrence.ONCE &&
                    model.occurrence != ElementDeclaration.ContentModel.Occurrence.ZERO_OR_MORE) {
                    throw new SAXParseException(
                        "Mixed content can only have * or no occurrence indicator", locator);
                }
                return; // Valid
            }
            // If single child that is a group, check for unnecessary nesting
            if (child.type == ElementDeclaration.ContentModel.NodeType.CHOICE ||
                child.type == ElementDeclaration.ContentModel.NodeType.SEQUENCE) {
                if (child.children != null && child.children.size() == 1 &&
                    child.children.get(0).type == ElementDeclaration.ContentModel.NodeType.PCDATA) {
                    // This is ((#PCDATA)) - unnecessary nesting
                    throw new SAXParseException(
                        "Unnecessary nested parentheses in mixed content", locator);
                }
            }
        }
        
        // If the model itself is just PCDATA (not wrapped), check occurrence
        if (model.type == ElementDeclaration.ContentModel.NodeType.PCDATA) {
            if (model.occurrence != ElementDeclaration.ContentModel.Occurrence.ONCE &&
                model.occurrence != ElementDeclaration.ContentModel.Occurrence.ZERO_OR_MORE) {
                throw new SAXParseException(
                    "Mixed content with #PCDATA can only have * or no occurrence indicator", locator);
            }
            return; // Valid
        }
        
        // Mixed content with multiple elements must be a CHOICE at top level
        if (model.type != ElementDeclaration.ContentModel.NodeType.CHOICE) {
            // Only error if there are multiple children or if first child is not PCDATA alone
            if (model.children != null && model.children.size() > 1) {
                throw new SAXParseException(
                    "Mixed content must use | (choice), not , (sequence)", locator);
            }
        }
        
        // If CHOICE, #PCDATA must be first child
        if (model.type == ElementDeclaration.ContentModel.NodeType.CHOICE) {
            if (model.children != null && !model.children.isEmpty()) {
                ElementDeclaration.ContentModel first = model.children.get(0);
                if (first.type != ElementDeclaration.ContentModel.NodeType.PCDATA) {
                    throw new SAXParseException(
                        "#PCDATA must be first in mixed content choice", locator);
                }
            }
            
            // Check for nested groups (not allowed in mixed content)
            if (model.children != null) {
                for (ElementDeclaration.ContentModel child : model.children) {
                    if (child.type == ElementDeclaration.ContentModel.NodeType.CHOICE ||
                        child.type == ElementDeclaration.ContentModel.NodeType.SEQUENCE) {
                        throw new SAXParseException(
                            "Nested groups not allowed in mixed content", locator);
                    }
                    // Element names in mixed content cannot have occurrence indicators
                    if (child.type == ElementDeclaration.ContentModel.NodeType.ELEMENT &&
                        child.occurrence != ElementDeclaration.ContentModel.Occurrence.ONCE) {
                        throw new SAXParseException(
                            "Element names in mixed content cannot have occurrence indicators", locator);
                    }
                }
            }
            
            // Occurrence indicator on choice must be * or none
            if (model.occurrence != ElementDeclaration.ContentModel.Occurrence.ONCE &&
                model.occurrence != ElementDeclaration.ContentModel.Occurrence.ZERO_OR_MORE) {
                throw new SAXParseException(
                    "Mixed content can only have * or no occurrence indicator", locator);
            }
        }
    }
    
    /**
     * Helper method to save the current element declaration.
     * Called when the &lt;!ELEMENT declaration is complete (GT encountered).
     */
    private void saveElementDeclaration() throws SAXException {
        if (currentElementDecl.name == null) {
            throw new SAXParseException("No element name in &lt;!ELEMENT declaration", locator);
        }
        
        if (currentElementDecl.contentType == null) {
            throw new SAXParseException("No content specification in &lt;!ELEMENT declaration", locator);
        }
        
        // Validate mixed content constraints (after occurrence indicators have been applied)
        if (currentElementDecl.contentType == ElementDeclaration.ContentType.MIXED &&
            currentElementDecl.contentModel != null) {
            validateMixedContent(currentElementDecl.contentModel);
        }
        
        // Add to map with interned key
        addElementDeclaration(currentElementDecl);
    }

    /**
     * Handles tokens within an &lt;!ATTLIST declaration.
     * Uses a sub-state machine to enforce well-formedness constraints.
     */
    private void handleInAttlistDecl(Token token, CharBuffer data) throws SAXException {
        switch (attListDeclState) {
            case EXPECT_ELEMENT_NAME:
                // Must see NAME for element name (whitespace allowed first)
                switch (token) {
                    case S:
                        // Skip whitespace before element name
                        break;
                        
                    case NAME:
                        currentAttlistElement = extractString(data);
                        attListDeclState = AttListDeclState.AFTER_ELEMENT_NAME;
                        break;
                        
                    default:
                        throw new SAXParseException("Expected element name after &lt;!ATTLIST, got: " + token, locator);
                }
                break;
                
            case AFTER_ELEMENT_NAME:
                // Must see whitespace after element name
                switch (token) {
                    case S:
                        attListDeclState = AttListDeclState.EXPECT_ATTR_OR_GT;
                        break;
                        
                    default:
                        throw new SAXParseException("Expected whitespace after element name in &lt;!ATTLIST, got: " + token, locator);
                }
                break;
                
            case EXPECT_ATTR_OR_GT:
                // Expecting attribute name or GT to close
                switch (token) {
                    case S:
                        // Skip extra whitespace
                        break;
                        
                    case NAME:
                        // Start new attribute
                        currentAttributeDecl = new AttributeDeclaration();
                        currentAttributeDecl.name = extractString(data);
                        attListDeclState = AttListDeclState.AFTER_ATTR_NAME;
                        break;
                        
                    case COLON:
                        // Colon is a valid XML 1.0 name (though discouraged by XML Namespaces)
                        currentAttributeDecl = new AttributeDeclaration();
                        currentAttributeDecl.name = ":";
                        attListDeclState = AttListDeclState.AFTER_ATTR_NAME;
                        break;
                        
                    case GT:
                        // End of ATTLIST
                        saveCurrentAttlist();
                        state = savedState;
                        break;
                        
                    default:
                        throw new SAXParseException("Expected attribute name or '&gt;' in &lt;!ATTLIST, got: " + token, locator);
                }
                break;
                
            case AFTER_ATTR_NAME:
                // Must see whitespace after attribute name
                switch (token) {
                    case S:
                        sawWhitespaceAfterAttrType = false; // Reset for new attribute
                        attListDeclState = AttListDeclState.EXPECT_ATTR_TYPE;
                        break;
                        
                    default:
                        throw new SAXParseException("Expected whitespace after attribute name in &lt;!ATTLIST, got: " + token, locator);
                }
                break;
                
            case EXPECT_ATTR_TYPE:
                // Expecting attribute type
                switch (token) {
                    case S:
                        // Skip extra whitespace
                        break;
                        
                    case CDATA_TYPE:
                        currentAttributeDecl.type = "CDATA";
                        attListDeclState = AttListDeclState.AFTER_ATTR_TYPE;
                        break;
                        
                    case ID:
                        currentAttributeDecl.type = "ID";
                        attListDeclState = AttListDeclState.AFTER_ATTR_TYPE;
                        break;
                        
                    case IDREF:
                        currentAttributeDecl.type = "IDREF";
                        attListDeclState = AttListDeclState.AFTER_ATTR_TYPE;
                        break;
                        
                    case IDREFS:
                        currentAttributeDecl.type = "IDREFS";
                        attListDeclState = AttListDeclState.AFTER_ATTR_TYPE;
                        break;
                        
                    case ENTITY:
                        currentAttributeDecl.type = "ENTITY";
                        attListDeclState = AttListDeclState.AFTER_ATTR_TYPE;
                        break;
                        
                    case ENTITIES:
                        currentAttributeDecl.type = "ENTITIES";
                        attListDeclState = AttListDeclState.AFTER_ATTR_TYPE;
                        break;
                        
                    case NMTOKEN:
                        currentAttributeDecl.type = "NMTOKEN";
                        attListDeclState = AttListDeclState.AFTER_ATTR_TYPE;
                        break;
                        
                    case NMTOKENS:
                        currentAttributeDecl.type = "NMTOKENS";
                        attListDeclState = AttListDeclState.AFTER_ATTR_TYPE;
                        break;
                        
                    case NOTATION:
                        currentAttributeDecl.type = "NOTATION";
                        // NOTATION type may be followed by (name1|name2|...)
                        attListDeclState = AttListDeclState.AFTER_ATTR_TYPE;
                        break;
                        
                    case OPEN_PAREN:
                        // Enumeration starting - collect values
                        currentAttributeDecl.type = "ENUMERATION";
                        enumerationBuilder = new ArrayList<>();
                        attListDeclState = AttListDeclState.AFTER_ATTR_TYPE;
                        break;
                        
                    default:
                        throw new SAXParseException("Invalid attribute type in <!ATTLIST: " + 
                            (data != null ? extractString(data) : token.toString()), locator);
                }
                break;
                
            case AFTER_ATTR_TYPE:
                // Must see whitespace after type (or part of enumeration)
                switch (token) {
                    case S:
                        // Whitespace after type means we're done with enumeration (if any)
                        sawWhitespaceAfterAttrType = true;
                        if (enumerationBuilder != null) {
                            currentAttributeDecl.enumeration = enumerationBuilder;
                            enumerationBuilder = null;
                        }
                        attListDeclState = AttListDeclState.EXPECT_DEFAULT_DECL;
                        break;
                        
                    case OPEN_PAREN:
                        // Start of enumeration (for NOTATION or after OPEN_PAREN in EXPECT_ATTR_TYPE)
                        // For NOTATION type, require whitespace before enumeration
                        if ("NOTATION".equals(currentAttributeDecl.type) && !sawWhitespaceAfterAttrType) {
                            throw new SAXParseException("Expected whitespace after NOTATION keyword in <!ATTLIST", locator);
                        }
                        if (enumerationBuilder == null) {
                            enumerationBuilder = new ArrayList<>();
                        }
                        // Stay in AFTER_ATTR_TYPE to collect values
                        break;
                        
                    case CLOSE_PAREN:
                        // End of enumeration
                        if (enumerationBuilder != null) {
                            currentAttributeDecl.enumeration = enumerationBuilder;
                            enumerationBuilder = null;
                        }
                        // Stay in AFTER_ATTR_TYPE waiting for whitespace
                        break;
                        
                    case PIPE:
                        // Separator in enumeration - stay in AFTER_ATTR_TYPE
                        break;
                        
                    case NAME:
                        // Part of enumeration - collect it
                        if (enumerationBuilder != null) {
                            enumerationBuilder.add(extractString(data));
                        }
                        // Stay in AFTER_ATTR_TYPE
                        break;
                        
                    default:
                        throw new SAXParseException("Expected whitespace after attribute type in &lt;!ATTLIST, got: " + token, locator);
                }
                break;
                
            case EXPECT_DEFAULT_DECL:
                // Expecting #REQUIRED | #IMPLIED | #FIXED | default value
                // Or OPEN_PAREN for NOTATION enumeration
                switch (token) {
                    case S:
                        // Skip extra whitespace
                        break;
                        
                    case OPEN_PAREN:
                        // Start of NOTATION enumeration
                        enumerationBuilder = new ArrayList<>();
                        attListDeclState = AttListDeclState.IN_NOTATION_ENUM;
                        break;
                        
                    case HASH:
                        // Hash before keyword - transition to AFTER_HASH to expect keyword
                        attListDeclState = AttListDeclState.AFTER_HASH;
                        break;
                        
                    case REQUIRED:
                        // #REQUIRED without hash (tokenizer combined them)
                        currentAttributeDecl.mode = Token.REQUIRED;
                        saveCurrentAttribute();
                        attListDeclState = AttListDeclState.EXPECT_ATTR_OR_GT;
                        break;
                        
                    case IMPLIED:
                        // #IMPLIED without hash (tokenizer combined them)
                        currentAttributeDecl.mode = Token.IMPLIED;
                        saveCurrentAttribute();
                        attListDeclState = AttListDeclState.EXPECT_ATTR_OR_GT;
                        break;
                        
                    case FIXED:
                        // #FIXED without hash (tokenizer combined them)
                        currentAttributeDecl.mode = Token.FIXED;
                        attListDeclState = AttListDeclState.AFTER_FIXED;
                        break;
                        
                    case QUOT:
                    case APOS:
                        // Default value starting (no #FIXED) - initialize accumulators
                        defaultValueBuilder = new ArrayList<>();
                        defaultValueTextBuilder = new StringBuilder();
                        attListDeclState = AttListDeclState.AFTER_DEFAULT_VALUE;
                        break;
                        
                    case CDATA:
                        // Default value chunk - accumulate (may receive multiple CDATA tokens)
                        if (defaultValueBuilder == null) {
                            defaultValueBuilder = new ArrayList<>();
                            defaultValueTextBuilder = new StringBuilder();
                        }
                        defaultValueTextBuilder.append(extractString(data));
                        attListDeclState = AttListDeclState.AFTER_DEFAULT_VALUE;
                        break;
                        
                    default:
                        throw new SAXParseException("Expected default declaration (#REQUIRED, #IMPLIED, #FIXED, or value) in &lt;!ATTLIST, got: " + token, locator);
                }
                break;
                
            case AFTER_HASH:
                // After #, must see REQUIRED | IMPLIED | FIXED keyword
                switch (token) {
                    case REQUIRED:
                        currentAttributeDecl.mode = Token.REQUIRED;
                        saveCurrentAttribute();
                        attListDeclState = AttListDeclState.EXPECT_ATTR_OR_GT;
                        break;
                        
                    case IMPLIED:
                        currentAttributeDecl.mode = Token.IMPLIED;
                        saveCurrentAttribute();
                        attListDeclState = AttListDeclState.EXPECT_ATTR_OR_GT;
                        break;
                        
                    case FIXED:
                        currentAttributeDecl.mode = Token.FIXED;
                        attListDeclState = AttListDeclState.AFTER_FIXED;
                        break;
                        
                    default:
                        throw new SAXParseException("Expected REQUIRED, IMPLIED, or FIXED after # in <!ATTLIST, got: " + token, locator);
                }
                break;
                
            case AFTER_FIXED:
                // Must see whitespace after #FIXED
                switch (token) {
                    case S:
                        attListDeclState = AttListDeclState.EXPECT_DEFAULT_VALUE;
                        break;
                        
                    default:
                        throw new SAXParseException("Expected whitespace after #FIXED in &lt;!ATTLIST, got: " + token, locator);
                }
                break;
                
            case EXPECT_DEFAULT_VALUE:
                // Expecting quoted default value after #FIXED
                switch (token) {
                    case S:
                        // Skip extra whitespace
                        break;
                        
                    case QUOT:
                    case APOS:
                        // Quote starting value - initialize accumulators
                        defaultValueBuilder = new ArrayList<>();
                        defaultValueTextBuilder = new StringBuilder();
                        attListDeclState = AttListDeclState.AFTER_DEFAULT_VALUE;
                        break;
                        
                    case CDATA:
                        // Fixed default value chunk - accumulate (may receive multiple CDATA tokens)
                        if (defaultValueBuilder == null) {
                            defaultValueBuilder = new ArrayList<>();
                            defaultValueTextBuilder = new StringBuilder();
                        }
                        defaultValueTextBuilder.append(extractString(data));
                        attListDeclState = AttListDeclState.AFTER_DEFAULT_VALUE;
                        break;
                        
                    default:
                        throw new SAXParseException("Expected quoted value after #FIXED in &lt;!ATTLIST, got: " + token, locator);
                }
                break;
                
            case AFTER_DEFAULT_VALUE:
                // After default value CDATA, expecting more CDATA chunks, entity refs, or closing quote
                switch (token) {
                    case CDATA:
                    case S:
                        // Additional chunk of default value - accumulate
                        defaultValueTextBuilder.append(extractString(data));
                        break;
                        
                    case ENTITYREF:
                        // Predefined entity or character reference (already expanded)
                        defaultValueTextBuilder.append(extractString(data));
                        break;
                        
                    case GENERALENTITYREF:
                        // General entity reference - flush text and add reference
                        if (defaultValueTextBuilder.length() > 0) {
                            defaultValueBuilder.add(defaultValueTextBuilder.toString());
                            defaultValueTextBuilder.setLength(0);
                        }
                        String entityName = extractString(data);
                        // WFC: Entity Declared - entity must be declared before use in attribute default
                        if (getGeneralEntity(entityName) == null) {
                            throw new SAXParseException(
                                "Entity '" + entityName + "' must be declared before use in attribute default value " +
                                "(WFC: Entity Declared)", locator);
                        }
                        defaultValueBuilder.add(new GeneralEntityReference(entityName));
                        break;
                        
                    case PARAMETERENTITYREF:
                        // Parameter entity reference in default value - flush text and add reference
                        if (defaultValueTextBuilder.length() > 0) {
                            defaultValueBuilder.add(defaultValueTextBuilder.toString());
                            defaultValueTextBuilder.setLength(0);
                        }
                        String paramEntityName = extractString(data);
                        // Parameter entities are allowed in default values
                        defaultValueBuilder.add(new ParameterEntityReference(paramEntityName));
                        break;
                        
                    case QUOT:
                    case APOS:
                        // Closing quote - finalize default value, save attribute, and return to expecting next attribute
                        // Flush final text segment
                        if (defaultValueTextBuilder.length() > 0) {
                            defaultValueBuilder.add(defaultValueTextBuilder.toString());
                        }
                        // Set default value on attribute
                        currentAttributeDecl.defaultValue = defaultValueBuilder;
                        defaultValueBuilder = null;
                        defaultValueTextBuilder = null;
                        saveCurrentAttribute();
                        attListDeclState = AttListDeclState.EXPECT_ATTR_OR_GT;
                        break;
                        
                    default:
                        throw new SAXParseException("Expected closing quote after default value in &lt;!ATTLIST, got: " + token, locator);
                }
                break;
                
            case IN_NOTATION_ENUM:
                // Inside NOTATION enumeration: (name1|name2|name3)
                switch (token) {
                    case NAME:
                        // Collect notation name
                        if (enumerationBuilder != null) {
                            enumerationBuilder.add(extractString(data));
                        }
                        break;
                        
                    case PIPE:
                        // Separator between notation names
                        break;
                        
                    case CLOSE_PAREN:
                        // End of notation enumeration
                        if (enumerationBuilder != null) {
                            currentAttributeDecl.enumeration = enumerationBuilder;
                            enumerationBuilder = null;
                        }
                        // Back to expecting default declaration
                        attListDeclState = AttListDeclState.EXPECT_DEFAULT_DECL;
                        break;
                        
                    case S:
                        // Whitespace in enumeration, ignore
                        break;
                        
                    default:
                        throw new SAXParseException("Expected notation name, |, or ) in NOTATION enumeration, got: " + token, locator);
                }
                break;
                
            default:
                throw new SAXParseException("Invalid ATTLIST parser state: " + attListDeclState, locator);
        }
    }
    
    /**
     * Handles tokens within a &lt;!NOTATION declaration.
     * Parses notation name and external ID (SYSTEM or PUBLIC).
     * 
     * <p>Grammar: &lt;!NOTATION Name S (ExternalID | PublicID) S? &gt;
     * <p>ExternalID: SYSTEM S SystemLiteral | PUBLIC S PubidLiteral S SystemLiteral
     * <p>PublicID: PUBLIC S PubidLiteral
     */
    private void handleInNotationDecl(Token token, CharBuffer data) throws SAXException {
        switch (notationDeclState) {
            case EXPECT_NAME:
                // After &lt;!NOTATION, expecting notation name
                switch (token) {
                    case S:
                        // Skip whitespace before name
                        break;
                        
                    case NAME:
                        // Notation name
                        currentNotationName = extractString(data);
                        notationDeclState = NotationDeclState.AFTER_NAME;
                        break;
                        
                    default:
                        throw new SAXParseException("Expected notation name after &lt;!NOTATION, got: " + token, locator);
                }
                break;
                
            case AFTER_NAME:
                // After notation name, expecting whitespace
                switch (token) {
                    case S:
                        // Whitespace between name and external ID
                        notationDeclState = NotationDeclState.EXPECT_EXTERNAL_ID;
                        break;
                        
                    default:
                        throw new SAXParseException("Expected whitespace after notation name, got: " + token, locator);
                }
                break;
                
            case EXPECT_EXTERNAL_ID:
                // After whitespace, expecting SYSTEM or PUBLIC
                switch (token) {
                    case S:
                        // Skip additional whitespace
                        break;
                        
                    case SYSTEM:
                        // SYSTEM external ID
                        sawPublicInNotation = false;
                        notationDeclState = NotationDeclState.EXPECT_SYSTEM_ID;
                        break;
                        
                    case PUBLIC:
                        // PUBLIC external ID (or PublicID)
                        sawPublicInNotation = true;
                        notationDeclState = NotationDeclState.EXPECT_PUBLIC_ID;
                        break;
                        
                    default:
                        throw new SAXParseException("Expected SYSTEM or PUBLIC after notation name, got: " + token, locator);
                }
                break;
                
            case EXPECT_SYSTEM_ID:
                // After SYSTEM, expecting quoted system ID
                switch (token) {
                    case S:
                        // Whitespace between SYSTEM and quoted string
                        break;
                        
                    case QUOT:
                    case APOS:
                        // Opening quote, ignore
                        break;
                        
                    case CDATA:
                    case NAME:
                        // System ID (tokenizer may emit NAME or CDATA in DOCTYPE context)
                        currentNotationExternalID.systemId = extractString(data);
                        notationDeclState = NotationDeclState.EXPECT_GT;
                        break;
                        
                    default:
                        throw new SAXParseException("Expected quoted system ID after SYSTEM, got: " + token, locator);
                }
                break;
                
            case EXPECT_PUBLIC_ID:
                // After PUBLIC, expecting quoted public ID
                switch (token) {
                    case S:
                        // Whitespace between PUBLIC and quoted string
                        break;
                        
                    case QUOT:
                    case APOS:
                        // Opening quote, ignore
                        break;
                        
                    case CDATA:
                    case NAME:
                        // Public ID (tokenizer may emit NAME or CDATA in DOCTYPE context)
                        String publicId = extractString(data);
                        validatePublicId(publicId);
                        currentNotationExternalID.publicId = publicId;
                        notationDeclState = NotationDeclState.AFTER_PUBLIC_ID;
                        break;
                        
                    default:
                        throw new SAXParseException("Expected quoted public ID after PUBLIC, got: " + token, locator);
                }
                break;
                
            case AFTER_PUBLIC_ID:
                // After public ID, expecting optional system ID or &gt;
                switch (token) {
                    case S:
                        // Whitespace, ignore
                        break;
                        
                    case QUOT:
                    case APOS:
                        // Quote (closing previous or opening next)
                        break;
                        
                    case CDATA:
                    case NAME:
                        // Optional system ID after PUBLIC (tokenizer may emit NAME or CDATA)
                        currentNotationExternalID.systemId = extractString(data);
                        notationDeclState = NotationDeclState.EXPECT_GT;
                        break;
                        
                    case GT:
                        // End of declaration (PUBLIC without system ID)
                        saveCurrentNotation();
                        state = savedState;
                        break;
                        
                    default:
                        throw new SAXParseException("Expected system ID or &gt; after public ID, got: " + token, locator);
                }
                break;
                
            case EXPECT_GT:
                // Expecting &gt; to close declaration
                switch (token) {
                    case S:
                        // Whitespace before &gt;, ignore
                        break;
                        
                    case QUOT:
                    case APOS:
                        // Closing quote, ignore
                        break;
                        
                    case CDATA:
                    case NAME:
                        // Additional text (e.g., continuation of system ID if split by tokenizer)
                        // Append to existing system ID if present
                        if (currentNotationExternalID.systemId != null) {
                            currentNotationExternalID.systemId += extractString(data);
                        }
                        break;
                        
                    case GT:
                        // End of declaration
                        saveCurrentNotation();
                        state = savedState;
                        break;
                        
                    default:
                        throw new SAXParseException("Expected &gt; to close &lt;!NOTATION declaration, got: " + token, locator);
                }
                break;
                
            default:
                throw new SAXParseException("Invalid NOTATION parser state: " + notationDeclState, locator);
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
    private void saveCurrentAttribute() {
        if (currentAttributeDecl != null && currentAttributeDecl.name != null) {
            // Set default type if not specified
            if (currentAttributeDecl.type == null) {
                currentAttributeDecl.type = "CDATA";
            }
            // Add to current ATTLIST map keyed by attribute name
            currentAttlistMap.put(currentAttributeDecl.name.intern(), currentAttributeDecl);
            currentAttributeDecl = null;
        }
    }
    
    /**
     * Helper method to save the current notation declaration.
     * Adds the notation to the notations map keyed by notation name.
     * Also reports to DTDHandler if present.
     */
    private void saveCurrentNotation() throws SAXException {
        if (currentNotationName != null && currentNotationExternalID != null) {
            // Ensure notations map exists (lazy initialization)
            if (notations == null) {
                notations = new HashMap<>();
            }
            
            // Add to notations map keyed by notation name (interned)
            notations.put(currentNotationName.intern(), currentNotationExternalID);
            
            // Report to DTDHandler if present
            if (dtdHandler != null) {
                String publicId = currentNotationExternalID.publicId;
                String systemId = currentNotationExternalID.systemId;
                dtdHandler.notationDecl(currentNotationName, publicId, systemId);
            }
            
            // Clear current notation
            currentNotationName = null;
            currentNotationExternalID = null;
        }
    }
    
    /**
     * Handles tokens while parsing an &lt;!ENTITY declaration.
     * Uses a state machine to track position within the declaration.
     */
    private void handleInEntityDecl(Token token, CharBuffer data) throws SAXException {
        switch (entityDeclState) {
            case EXPECT_PERCENT_OR_NAME:
                // After <!ENTITY, expecting optional % or entity name
                switch (token) {
                    case S:
                        // Whitespace after <!ENTITY keyword is required
                        sawWhitespaceAfterEntityKeyword = true;
                        break;
                    case PERCENT:
                        // Parameter entity - must have whitespace before %
                        if (!sawWhitespaceAfterEntityKeyword) {
                            throw new SAXParseException(
                                "Expected whitespace after <!ENTITY keyword", locator);
                        }
                        currentEntity.isParameter = true;
                        entityDeclState = EntityDeclState.EXPECT_NAME;
                        break;
                    case NAME:
                        // General entity name - must have whitespace before name
                        if (!sawWhitespaceAfterEntityKeyword) {
                            throw new SAXParseException(
                                "Expected whitespace after <!ENTITY keyword", locator);
                        }
                        currentEntity.isParameter = false;
                        currentEntity.name = extractString(data);
                        entityDeclState = EntityDeclState.AFTER_NAME;
                        break;
                    default:
                        throw new SAXParseException("Expected entity name or '%' in <!ENTITY, got: " + token, locator);
                }
                break;
                
            case EXPECT_NAME:
                // After %, expecting parameter entity name
                switch (token) {
                    case S:
                        // Skip whitespace
                        break;
                    case NAME:
                        currentEntity.name = extractString(data);
                        entityDeclState = EntityDeclState.AFTER_NAME;
                        break;
                    default:
                        throw new SAXParseException("Expected parameter entity name after '%' in <!ENTITY, got: " + token, locator);
                }
                break;
                
            case AFTER_NAME:
                // After entity name, expecting whitespace
                if (token == Token.S) {
                    entityDeclState = EntityDeclState.EXPECT_VALUE_OR_ID;
                } else {
                    throw new SAXParseException("Expected whitespace after entity name in <!ENTITY, got: " + token, locator);
                }
                break;
                
            case EXPECT_VALUE_OR_ID:
                // After whitespace, expecting quoted value, SYSTEM, or PUBLIC
                switch (token) {
                    case S:
                        // Skip additional whitespace
                        break;
                    case QUOT:
                    case APOS:
                        // Start of entity value
                        entityValueQuote = (token == Token.QUOT) ? '"' : '\'';
                        entityValueBuilder = new ArrayList<>();
                        entityValueTextBuilder = new StringBuilder();
                        entityDeclState = EntityDeclState.IN_ENTITY_VALUE;
                        break;
                    case SYSTEM:
                        // External entity with system ID
                        currentEntity.externalID = new ExternalID();
                        sawWhitespaceAfterSystemId = false; // Reset for new external ID
                        entityDeclState = EntityDeclState.EXPECT_SYSTEM_ID;
                        break;
                    case PUBLIC:
                        // External entity with public ID
                        currentEntity.externalID = new ExternalID();
                        sawWhitespaceAfterEntityPublicId = false; // Reset for new external ID
                        sawClosingQuoteOfPublicId = false; // Reset for new public ID
                        entityDeclState = EntityDeclState.EXPECT_PUBLIC_ID;
                        break;
                    default:
                        throw new SAXParseException("Expected quoted value, SYSTEM, or PUBLIC in <!ENTITY, got: " + token, locator);
                }
                break;
                
            case IN_ENTITY_VALUE:
                // Accumulating entity value between quotes
                switch (token) {
                    case CDATA:
                    case S:
                        // Accumulate text
                        entityValueTextBuilder.append(extractString(data));
                        break;
                    case ENTITYREF:
                        // Predefined entity or character reference (already expanded)
                        // Mark that this entity contains character/predefined references
                        // Per XML 1.0 § 4.4.8, markup delimiters from references are bypassed
                        currentEntity.containsCharacterReferences = true;
                        entityValueTextBuilder.append(extractString(data));
                        break;
                    case GENERALENTITYREF:
                        // General entity reference - flush text and add reference
                        if (entityValueTextBuilder.length() > 0) {
                            entityValueBuilder.add(entityValueTextBuilder.toString());
                            entityValueTextBuilder.setLength(0);
                        }
                        String entityName = extractString(data);
                        entityValueBuilder.add(new GeneralEntityReference(entityName));
                        break;
                    case PARAMETERENTITYREF:
                        // Parameter entity reference in entity value
                        // WFC: PEs in Internal Subset - parameter entity references are NOT allowed
                        // within markup declarations in the internal subset (including entity values)
                        // This restriction only applies to the internal subset, not external subset
                        if (savedState == State.IN_INTERNAL_SUBSET) {
                            throw new SAXParseException(
                                "Parameter entity references are not allowed within entity values " +
                                "in the internal subset (WFC: PEs in Internal Subset)", locator);
                        }
                        
                        // In external subset, parameter entities are allowed in entity values
                        // Expand the parameter entity inline
                        String paramEntityName = extractString(data);
                        // XXX: For now, just store the reference - proper expansion would require
                        // XXX: retokenizing the expanded value
                        // XXX: This is a limitation that should be addressed when implementing
                        // XXX: full parameter entity expansion in entity values
                        if (entityValueTextBuilder.length() > 0) {
                            entityValueBuilder.add(entityValueTextBuilder.toString());
                            entityValueTextBuilder.setLength(0);
                        }
                        entityValueBuilder.add(new ParameterEntityReference(paramEntityName));
                        break;
                        
                    case QUOT:
                    case APOS:
                        // Check if this is the closing quote
                        char quoteChar = (token == Token.QUOT) ? '"' : '\'';
                        if (quoteChar == entityValueQuote) {
                            // Flush final text segment
                            if (entityValueTextBuilder.length() > 0) {
                                entityValueBuilder.add(entityValueTextBuilder.toString());
                            }
                            // Set replacement text on entity
                            currentEntity.replacementText = entityValueBuilder;
                            entityDeclState = EntityDeclState.AFTER_ENTITY_VALUE;
                        } else {
                            // Wrong quote, treat as text
                            entityValueTextBuilder.append(quoteChar);
                        }
                        break;
                    default:
                        throw new SAXParseException("Unexpected token in entity value: " + token, locator);
                }
                break;
                
            case AFTER_ENTITY_VALUE:
                // After closing quote, expecting > or whitespace
                switch (token) {
                    case S:
                        // Skip whitespace
                        break;
                    case GT:
                        // End of entity declaration
                        saveCurrentEntity();
                        state = savedState;
                        break;
                    default:
                        throw new SAXParseException("Expected '>' after entity value in <!ENTITY, got: " + token, locator);
                }
                break;
                
            case EXPECT_SYSTEM_ID:
                // After SYSTEM, expecting quoted system ID
                switch (token) {
                    case S:
                        // Skip whitespace
                        break;
                    case QUOT:
                    case APOS:
                        // Opening quote for system ID - next will be CDATA
                        break;
                    case CDATA:
                    case NAME:
                        // Workaround for tokenizer issues: accumulate system ID
                        if (currentEntity.externalID.systemId == null) {
                            currentEntity.externalID.systemId = extractString(data);
                        } else {
                            currentEntity.externalID.systemId += extractString(data);
                        }
                        // Transition to AFTER_EXTERNAL_ID after system ID is read
                        entityDeclState = EntityDeclState.AFTER_EXTERNAL_ID;
                        break;
                    case GT:
                        // End of entity declaration (external parsed entity)
                        saveCurrentEntity();
                        state = savedState;
                        break;
                    default:
                        throw new SAXParseException("Expected quoted system ID after SYSTEM in <!ENTITY, got: " + token, locator);
                }
                break;
                
            case EXPECT_PUBLIC_ID:
                // After PUBLIC, expecting quoted public ID
                switch (token) {
                    case S:
                        // Skip whitespace
                        break;
                    case QUOT:
                    case APOS:
                        // Opening quote for public ID - next will be CDATA
                        break;
                    case CDATA:
                    case NAME:
                        // Workaround for tokenizer issues
                        if (currentEntity.externalID.publicId == null) {
                            currentEntity.externalID.publicId = extractString(data);
                        } else {
                            currentEntity.externalID.publicId += extractString(data);
                        }
                        // Validate public ID when complete (on transition to AFTER_PUBLIC_ID)
                        validatePublicId(currentEntity.externalID.publicId);
                        entityDeclState = EntityDeclState.AFTER_PUBLIC_ID;
                        break;
                    default:
                        throw new SAXParseException("Expected quoted public ID after PUBLIC in <!ENTITY, got: " + token, locator);
                }
                break;
                
            case AFTER_PUBLIC_ID:
                // After public ID, expecting closing quote, then system ID
                switch (token) {
                    case S:
                        // Whitespace after public ID - required before system ID (after closing quote)
                        sawWhitespaceAfterEntityPublicId = true;
                        break;
                    case QUOT:
                    case APOS:
                        if (!sawClosingQuoteOfPublicId) {
                            // First quote is the closing quote of public ID - just skip it
                            sawClosingQuoteOfPublicId = true;
                        } else {
                            // Second quote is opening quote of system ID - must have whitespace first
                            if (!sawWhitespaceAfterEntityPublicId) {
                                throw new SAXParseException("Expected whitespace between public ID and system ID in <!ENTITY", locator);
                            }
                        }
                        break;
                    case CDATA:
                    case NAME:
                        // System ID following public ID - must have whitespace first (after closing quote)
                        if (!sawWhitespaceAfterEntityPublicId) {
                            throw new SAXParseException("Expected whitespace between public ID and system ID in <!ENTITY", locator);
                        }
                        if (currentEntity.externalID.systemId == null) {
                            currentEntity.externalID.systemId = extractString(data);
                        } else {
                            currentEntity.externalID.systemId += extractString(data);
                        }
                        entityDeclState = EntityDeclState.AFTER_EXTERNAL_ID;
                        break;
                    default:
                        throw new SAXParseException("Expected quoted system ID after public ID in <!ENTITY, got: " + token, locator);
                }
                break;
                
            case AFTER_EXTERNAL_ID:
                // After external ID, expecting NDATA or >
                switch (token) {
                    case S:
                        // Whitespace after external ID - required before NDATA
                        sawWhitespaceAfterSystemId = true;
                        break;
                    case QUOT:
                    case APOS:
                        // Closing quote after system ID - skip
                        break;
                    case CDATA:
                    case NAME:
                        // Tokenizer splitting system ID, accumulate
                        currentEntity.externalID.systemId += extractString(data);
                        break;
                    case GT:
                        // End of entity declaration (external parsed entity)
                        saveCurrentEntity();
                        state = savedState;
                        break;
                    case NDATA:
                        // Unparsed entity - must have whitespace first
                        if (!sawWhitespaceAfterSystemId) {
                            throw new SAXParseException("Expected whitespace before NDATA in <!ENTITY", locator);
                        }
                        // Parameter entities cannot have NDATA (they are always parsed)
                        if (currentEntity.isParameter) {
                            throw new SAXParseException("Parameter entities cannot have NDATA annotation", locator);
                        }
                        entityDeclState = EntityDeclState.EXPECT_NDATA_NAME;
                        break;
                    default:
                        throw new SAXParseException("Expected '>' or NDATA after external ID in <!ENTITY, got: " + token, locator);
                }
                break;
                
            case EXPECT_NDATA_NAME:
                // After NDATA, expecting notation name
                switch (token) {
                    case S:
                        // Skip whitespace
                        break;
                    case NAME:
                        currentEntity.notationName = extractString(data);
                        entityDeclState = EntityDeclState.EXPECT_GT;
                        break;
                    default:
                        throw new SAXParseException("Expected notation name after NDATA in <!ENTITY, got: " + token, locator);
                }
                break;
                
            case EXPECT_GT:
                // Expecting > to close declaration
                switch (token) {
                    case S:
                        // Skip whitespace
                        break;
                    case GT:
                        // End of entity declaration
                        saveCurrentEntity();
                        state = savedState;
                        break;
                    default:
                        throw new SAXParseException("Expected '>' to close <!ENTITY declaration, got: " + token, locator);
                }
                break;
        }
    }
    
    /**
     * Saves the current entity declaration to the appropriate map.
     */
    private void saveCurrentEntity() {
        if (currentEntity != null && currentEntity.name != null) {
            if (currentEntity.isParameter) {
                // Parameter entity
                if (parameterEntities == null) {
                    parameterEntities = new HashMap<>();
                }
                // Per XML 1.0 § 4.2: first declaration is binding
                String internedName = currentEntity.name.intern();
                if (!parameterEntities.containsKey(internedName)) {
                    parameterEntities.put(internedName, currentEntity);
                }
            } else {
                // General entity
                if (entities == null) {
                    entities = new HashMap<>();
                }
                // Per XML 1.0 § 4.2: first declaration is binding
                String internedName = currentEntity.name.intern();
                if (!entities.containsKey(internedName)) {
                    entities.put(internedName, currentEntity);
                }
                
                // Report unparsed entity to DTDHandler if applicable
                if (currentEntity.isUnparsed() && dtdHandler != null) {
                    try {
                        dtdHandler.unparsedEntityDecl(
                            currentEntity.name,
                            currentEntity.externalID.publicId,
                            currentEntity.externalID.systemId,
                            currentEntity.notationName
                        );
                    } catch (SAXException e) {
                        // DTDHandler threw an exception
                        // We can't throw from here, so log or ignore
                    }
                }
            }
            
            // Clear current entity
            currentEntity = null;
        }
    }
    
    /**
     * Helper method to save the entire ATTLIST declaration.
     * Called when GT is encountered at the end of the ATTLIST.
     * Merges currentAttlistMap into attributeDecls keyed by element name.
     */
    private void saveCurrentAttlist() {
        if (currentAttlistElement != null && !currentAttlistMap.isEmpty()) {
            // Ensure attributeDecls map exists
            if (attributeDecls == null) {
                attributeDecls = new HashMap<>();
            }
            // Get or create the attribute map for this element
            String internedElementName = currentAttlistElement.intern();
            Map<String, AttributeDeclaration> elementAttrs = attributeDecls.get(internedElementName);
            if (elementAttrs == null) {
                // First ATTLIST for this element - use our map directly
                attributeDecls.put(internedElementName, currentAttlistMap);
            } else {
                // Merge with existing attributes for this element
                elementAttrs.putAll(currentAttlistMap);
            }
        }
    }

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
     *
     * @param decl the element declaration to store
     */
    private void addElementDeclaration(ElementDeclaration decl) {
        if (elementDecls == null) {
            elementDecls = new HashMap<>();
        }
        // Intern element name for fast comparison
        elementDecls.put(decl.name.intern(), decl);
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
     *       External DTD processed, then DOCTYPE ends (GT)</li>
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
     * 
     * XXX: Assumes parameter entity is already declared (no forward references).
     * 
     * @param entityName the parameter entity name (without % and ;)
     * @throws SAXException if expansion fails
     */
    private void expandParameterEntityInline(String entityName) throws SAXException {
        // Look up the parameter entity
        EntityDeclaration entity = getParameterEntity(entityName);
        
        if (entity == null) {
            throw new SAXParseException(
                "Parameter entity %" + entityName + "; not declared " +
                "(XXX: or declared after use - forward references not yet supported)",
                locator);
        }
        
        // Check if it's an external entity
        if (entity.externalID != null) {
            // External parameter entity - resolve and process it
            // This works the same as external DTD subset processing
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
            return;
        }
        
        // Internal parameter entity - expand its replacement text
        if (entity.replacementText == null || entity.replacementText.isEmpty()) {
            // Empty entity - nothing to expand
            return;
        }
        
        // Expand any nested parameter entities in the replacement text
        StringBuilder expanded = new StringBuilder();
        
        for (Object part : entity.replacementText) {
            if (part instanceof String) {
                expanded.append((String) part);
            } else if (part instanceof ParameterEntityReference) {
                ParameterEntityReference ref = (ParameterEntityReference) part;
                String refExpanded = entityStack.expandParameterEntity(ref.name, EntityExpansionContext.DTD);
                if (refExpanded != null) {
                    expanded.append(refExpanded);
                }
            } else if (part instanceof GeneralEntityReference) {
                // General entity references in parameter entity values stay as literal text
                GeneralEntityReference ref = (GeneralEntityReference) part;
                expanded.append("&").append(ref.name).append(";");
            }
        }
        
        String replacementText = expanded.toString();
        if (replacementText.isEmpty()) {
            return; // Nothing to tokenize
        }
        
        // Create entity context for this expansion
        EntityStackEntry parentEntry = entityStack.peek();
        boolean currentVersion = parentEntry != null ? parentEntry.isXML11 : false;
        
        // Check for recursion - has this entity name already been expanded?
        for (EntityStackEntry ctx : entityStack) {
            if (ctx.isParameterEntity && entityName.equals(ctx.entityName)) {
                throw new SAXParseException(
                    "Recursive parameter entity reference: %" + entityName + ";",
                    locator);
            }
        }
        
        // Push entity context onto stack
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
            java.nio.CharBuffer buffer = java.nio.CharBuffer.wrap(replacementText);
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


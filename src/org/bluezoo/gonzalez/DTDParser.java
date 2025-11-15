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
import java.util.HashMap;
import java.util.Map;
import org.xml.sax.DTDHandler;
import org.xml.sax.ErrorHandler;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.ext.LexicalHandler;

/**
 * DTD parser.
 * <p>
 * This is a consumer of tokens within a DOCTYPE declaration. It is lazily
 * constructed by the XMLParser when a DOCTYPE token is encountered, and
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
    private enum State {
        INITIAL,                // Just started, expecting DOCTYPE name
        AFTER_NAME,             // Read name, expecting SYSTEM/PUBLIC/[/GT
        AFTER_SYSTEM_PUBLIC,    // Read SYSTEM/PUBLIC, expecting quoted string
        AFTER_PUBLIC_ID,        // Read public ID, expecting system ID
        AFTER_EXTERNAL_ID,      // Read external ID, expecting [/GT
        IN_INTERNAL_SUBSET,     // Inside [ ... ], processing declarations
        AFTER_INTERNAL_SUBSET,  // Read ], expecting GT
        IN_ELEMENTDECL,         // Parsing &lt;!ELEMENT declaration
        IN_ATTLISTDECL,         // Parsing &lt;!ATTLIST declaration
        DONE                    // Read final GT, done processing
    }

    private State state = State.INITIAL;
    private State savedState = State.IN_INTERNAL_SUBSET; // For returning after declarations
    private Locator locator;
    private DTDHandler dtdHandler;
    private LexicalHandler lexicalHandler;
    private ErrorHandler errorHandler;
    
    /**
     * Reference to the parent XMLParser for processing external entities.
     */
    private final XMLParser xmlParser;

    /**
     * The DOCTYPE name (root element name).
     */
    private String doctypeName;

    /**
     * The public identifier for the external DTD subset.
     */
    private String publicId;

    /**
     * The system identifier for the external DTD subset.
     */
    private String systemId;
    
    /**
     * Track whether we saw SYSTEM or PUBLIC keyword.
     */
    private boolean sawPublicKeyword;

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
     * Depth tracking for nested structures (e.g., conditional sections).
     */
    private int nestingDepth = 0;
    
    /**
     * Current element declaration being parsed.
     */
    private String currentElementName;
    private StringBuilder currentContentModel;
    
    /**
     * Current attribute list declaration being parsed.
     */
    private String currentAttlistElement;
    private String currentAttributeName;
    private String currentAttributeType;
    private String currentAttributeMode;
    private String currentAttributeValue;

    /**
     * Constructs a new DTDParser.
     * @param xmlParser the parent XMLParser for processing external entities
     */
    public DTDParser(XMLParser xmlParser) {
        this.xmlParser = xmlParser;
    }

    @Override
    public void setLocator(Locator locator) {
        this.locator = locator;
    }

    /**
     * Sets the DTD handler for receiving DTD events.
     * @param handler the DTD handler
     */
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
     * Checks if this parser can receive more tokens.
     * <p>
     * This method is called by the XMLParser before delegating each token.
     * It returns true while the DTDParser is still processing tokens inside
     * the doctypedecl production, and false when the final GT is detected.
     * <p>
     * When this returns false, the XMLParser knows to stop delegating tokens
     * to the DTDParser and resume normal parsing.
     *
     * @param token the token that would be received
     * @return true if this parser can receive the token, false otherwise
     */
    public boolean canReceive(Token token) {
        return state != State.DONE;
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
                state = State.AFTER_NAME;
                break;

            default:
                throw new SAXException("Expected name after &lt;!DOCTYPE, got: " + token);
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

            case SYSTEM:
                // SYSTEM external ID
                sawPublicKeyword = false;
                state = State.AFTER_SYSTEM_PUBLIC;
                break;

            case PUBLIC:
                // PUBLIC external ID
                sawPublicKeyword = true;
                state = State.AFTER_SYSTEM_PUBLIC;
                break;

            case OPEN_BRACKET:
                // Internal subset starts
                state = State.IN_INTERNAL_SUBSET;
                nestingDepth = 1;
                // Report start of DTD
                if (lexicalHandler != null) {
                    lexicalHandler.startDTD(doctypeName, publicId, systemId);
                }
                break;

            case GT:
                // End of DOCTYPE (no external ID, no internal subset)
                state = State.DONE;
                // Report start and end of DTD
                if (lexicalHandler != null) {
                    lexicalHandler.startDTD(doctypeName, publicId, systemId);
                    lexicalHandler.endDTD();
                }
                break;

            default:
                throw new SAXException("Unexpected token after DOCTYPE name: " + token);
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

            case QUOT:
            case APOS:
                // Start of quoted string, ignore quote
                break;

            case CDATA:
                // This is the ID string
                if (sawPublicKeyword) {
                    // PUBLIC keyword - first string is publicId, second is systemId
                    if (publicId == null) {
                        publicId = extractString(data);
                        state = State.AFTER_PUBLIC_ID;
                    } else {
                        // Second string after PUBLIC
                        systemId = extractString(data);
                        state = State.AFTER_EXTERNAL_ID;
                    }
                } else {
                    // SYSTEM keyword - only one string, and it's the systemId
                    systemId = extractString(data);
                    state = State.AFTER_EXTERNAL_ID;
                }
                break;

            default:
                throw new SAXException("Expected quoted string for external ID, got: " + token);
        }
    }

    /**
     * Handles tokens after reading the public ID.
     */
    private void handleAfterPublicId(Token token, CharBuffer data) throws SAXException {
        switch (token) {
            case S:
                // Whitespace, ignore
                break;

            case QUOT:
            case APOS:
                // Quote closing previous string or opening next, ignore
                break;

            case CDATA:
                // This is the system ID
                systemId = extractString(data);
                state = State.AFTER_EXTERNAL_ID;
                break;

            default:
                throw new SAXException("Expected system ID after public ID, got: " + token);
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

            case OPEN_BRACKET:
                // Internal subset starts (after external ID)
                // Report start of DTD
                if (lexicalHandler != null) {
                    lexicalHandler.startDTD(doctypeName, publicId, systemId);
                }
                // Enter internal subset state FIRST (so external DTD tokens are processed correctly)
                state = State.IN_INTERNAL_SUBSET;
                nestingDepth = 1;
                // Now process external DTD subset (tokens processed as internal subset)
                processExternalDTDSubset();
                // State remains IN_INTERNAL_SUBSET to process actual [ ... ] content
                break;

            case GT:
                // End of DOCTYPE (external ID, no internal subset)
                // Report start of DTD
                if (lexicalHandler != null) {
                    lexicalHandler.startDTD(doctypeName, publicId, systemId);
                }
                // Enter internal subset state (even though there's no [ ... ])
                // This allows external DTD tokens to be processed as internal subset
                state = State.IN_INTERNAL_SUBSET;
                nestingDepth = 0; // No bracket nesting
                // Process external DTD subset (tokens processed as internal subset)
                processExternalDTDSubset();
                // External DTD processing complete, now we're done
                state = State.DONE;
                // Report end of DTD
                if (lexicalHandler != null) {
                    lexicalHandler.endDTD();
                }
                break;

            default:
                throw new SAXException("Unexpected token after external ID: " + token);
        }
    }

    /**
     * Handles tokens within the internal subset (between [ and ]).
     */
    private void handleInInternalSubset(Token token, CharBuffer data) throws SAXException {
        switch (token) {
            case CLOSE_BRACKET:
                // End of internal subset
                nestingDepth--;
                if (nestingDepth == 0) {
                    state = State.AFTER_INTERNAL_SUBSET;
                }
                break;

            case OPEN_BRACKET:
                // Nested bracket (e.g., in conditional sections)
                nestingDepth++;
                break;

            case START_ELEMENTDECL:
                // Start parsing element declaration
                savedState = state;
                state = State.IN_ELEMENTDECL;
                currentElementName = null;
                currentContentModel = new StringBuilder();
                break;

            case START_ATTLISTDECL:
                // Start parsing attribute list declaration
                savedState = state;
                state = State.IN_ATTLISTDECL;
                currentAttlistElement = null;
                currentAttributeName = null;
                currentAttributeType = null;
                currentAttributeMode = null;
                currentAttributeValue = null;
                break;

            case START_ENTITYDECL:
                // TODO: Handle entity declarations
                break;

            case START_NOTATIONDECL:
                // TODO: Handle notation declarations
                break;

            case START_COMMENT:
                // TODO: Handle comments
                break;

            case START_PI:
                // TODO: Handle processing instructions
                break;

            case START_CONDITIONAL:
                // TODO: Handle conditional sections
                break;

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

            case GT:
                // End of DOCTYPE
                state = State.DONE;
                // Report end of DTD
                if (lexicalHandler != null) {
                    lexicalHandler.endDTD();
                }
                break;

            default:
                throw new SAXException("Expected GT after internal subset, got: " + token);
        }
    }

    /**
     * Handles tokens within an &lt;!ELEMENT declaration.
     */
    private void handleInElementDecl(Token token, CharBuffer data) throws SAXException {
        switch (token) {
            case S:
                // Whitespace, ignore
                break;
                
            case NAME:
                if (currentElementName == null) {
                    // This is the element name
                    currentElementName = extractString(data);
                } else {
                    // This is part of the content model
                    String nameStr = extractString(data);
                    if (currentContentModel.length() > 0) {
                        currentContentModel.append(' ');
                    }
                    // Check if this is PCDATA (tokenizer may emit as NAME in some contexts)
                    if (nameStr.equals("PCDATA")) {
                        currentContentModel.append("#PCDATA");
                    } else {
                        currentContentModel.append(nameStr);
                    }
                }
                break;
                
            case OPEN_PAREN:
            case CLOSE_PAREN:
            case STAR:
            case PLUS:
            case QUERY:
            case PIPE:
            case COMMA:
                // Content model punctuation (but not HASH - that's part of #PCDATA)
                currentContentModel.append(token == Token.OPEN_PAREN ? '(' :
                                          token == Token.CLOSE_PAREN ? ')' :
                                          token == Token.STAR ? '*' :
                                          token == Token.PLUS ? '+' :
                                          token == Token.QUERY ? '?' :
                                          token == Token.PIPE ? '|' :
                                          ',');
                break;
                
            case EMPTY:
                currentContentModel.append("EMPTY");
                break;
                
            case ANY:
                currentContentModel.append("ANY");
                break;
                
            case PCDATA:
                currentContentModel.append("#PCDATA");
                break;
                
            case GT:
                // End of element declaration
                if (currentElementName != null) {
                    // Determine content type from model string
                    String modelStr = currentContentModel.toString().trim();
                    ElementDeclaration.ContentType contentType;
                    
                    if (modelStr.equals("EMPTY")) {
                        contentType = ElementDeclaration.ContentType.EMPTY;
                    } else if (modelStr.equals("ANY")) {
                        contentType = ElementDeclaration.ContentType.ANY;
                    } else if (modelStr.indexOf("#PCDATA") >= 0) {
                        // Mixed content: contains #PCDATA anywhere
                        contentType = ElementDeclaration.ContentType.MIXED;
                    } else {
                        // Element content: structured content model
                        contentType = ElementDeclaration.ContentType.ELEMENT;
                    }
                    
                    // For now, create simple declaration (no content model tree parsing)
                    // TODO: Parse content model into tree structure
                    ElementDeclaration decl = new ElementDeclaration(currentElementName, contentType);
                    addElementDeclaration(decl);
                }
                // Return to saved state
                state = savedState;
                break;
                
            default:
                // Other tokens, ignore
                break;
        }
    }

    /**
     * Handles tokens within an &lt;!ATTLIST declaration.
     */
    private void handleInAttlistDecl(Token token, CharBuffer data) throws SAXException {
        switch (token) {
            case S:
                // Whitespace, ignore
                break;
                
            case NAME:
                String nameStr = extractString(data);
                
                if (currentAttlistElement == null) {
                    // This is the element name
                    currentAttlistElement = nameStr;
                } else if (currentAttributeName == null) {
                    // This is the first attribute name
                    currentAttributeName = nameStr;
                } else if (currentAttributeType == null) {
                    // This is the attribute type
                    // Check if this looks like a type keyword
                    switch (nameStr) {
                        case "CDATA":
                        case "ID":
                        case "IDREF":
                        case "IDREFS":
                        case "ENTITY":
                        case "ENTITIES":
                        case "NMTOKEN":
                        case "NMTOKENS":
                        case "NOTATION":
                            currentAttributeType = nameStr;
                            break;
                        default:
                            // Enumerated type or other
                            currentAttributeType = nameStr;
                            break;
                    }
                } else if (currentAttributeMode != null || currentAttributeValue != null) {
                    // We have a complete attribute (name, type, and mode/value)
                    // This NAME must be a new attribute
                    saveCurrentAttribute();
                    // Start new attribute
                    currentAttributeName = nameStr;
                    currentAttributeType = null;
                    currentAttributeMode = null;
                    currentAttributeValue = null;
                }
                // If we have name and type but no mode/value yet, wait for more tokens
                break;
                
            case CDATA_TYPE:
                currentAttributeType = "CDATA";
                break;
                
            case ID:
                currentAttributeType = "ID";
                break;
                
            case IDREF:
                currentAttributeType = "IDREF";
                break;
                
            case IDREFS:
                currentAttributeType = "IDREFS";
                break;
                
            case ENTITY:
                currentAttributeType = "ENTITY";
                break;
                
            case ENTITIES:
                currentAttributeType = "ENTITIES";
                break;
                
            case NMTOKEN:
                currentAttributeType = "NMTOKEN";
                break;
                
            case NMTOKENS:
                currentAttributeType = "NMTOKENS";
                break;
                
            case NOTATION:
                currentAttributeType = "NOTATION";
                break;
                
            case REQUIRED:
                currentAttributeMode = "#REQUIRED";
                // After mode, next NAME is a new attribute
                break;
                
            case IMPLIED:
                currentAttributeMode = "#IMPLIED";
                // After mode, next NAME is a new attribute
                break;
                
            case FIXED:
                currentAttributeMode = "#FIXED";
                // After FIXED, we expect a default value
                break;
                
            case QUOT:
            case APOS:
                // Quote, ignore (value will come in CDATA)
                break;
                
            case CDATA:
                // This is the default value
                if (currentAttributeValue == null) {
                    currentAttributeValue = extractString(data);
                }
                // After default value, next NAME is a new attribute
                break;
                
            case GT:
                // End of ATTLIST - save any pending attribute
                saveCurrentAttribute();
                // Return to saved state
                state = savedState;
                break;
                
            default:
                // Other tokens, ignore
                break;
        }
    }
    
    /**
     * Helper method to save the current attribute being parsed.
     * Called when we detect a new attribute starting or when GT is encountered.
     */
    private void saveCurrentAttribute() {
        if (currentAttlistElement != null && currentAttributeName != null) {
            // Create and store attribute declaration
            AttributeDeclaration decl = new AttributeDeclaration(
                currentAttributeName,
                currentAttributeType != null ? currentAttributeType : "CDATA",
                currentAttributeMode,
                currentAttributeValue
            );
            addAttributeDeclaration(currentAttlistElement, decl);
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
     * Gets the DOCTYPE name (root element name).
     * @return the DOCTYPE name, or null if not yet parsed
     */
    public String getDoctypeName() {
        return doctypeName;
    }

    /**
     * Gets the public identifier for the external DTD subset.
     * @return the public ID, or null if not specified
     */
    public String getPublicId() {
        return publicId;
    }

    /**
     * Gets the system identifier for the external DTD subset.
     * @return the system ID, or null if not specified
     */
    public String getSystemId() {
        return systemId;
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
     * Stores an attribute declaration.
     * Called internally when parsing &lt;!ATTLIST declarations.
     *
     * @param elementName the element this attribute belongs to
     * @param decl the attribute declaration to store
     */
    private void addAttributeDeclaration(String elementName, AttributeDeclaration decl) {
        if (attributeDecls == null) {
            attributeDecls = new HashMap<>();
        }
        // Intern keys for fast comparison
        elementName = elementName.intern();
        String attrName = decl.name.intern();
        
        // Get or create the attribute map for this element
        Map<String, AttributeDeclaration> elementAttrs = attributeDecls.get(elementName);
        if (elementAttrs == null) {
            elementAttrs = new HashMap<>();
            attributeDecls.put(elementName, elementAttrs);
        }
        
        // Store the attribute declaration
        elementAttrs.put(attrName, decl);
    }

    /**
     * Processes the external DTD subset by resolving and parsing it.
     *
     * <p>This method is called when an external ID (SYSTEM or PUBLIC) is
     * present in the DOCTYPE declaration. It uses the parent XMLParser's
     * processExternalEntity method to resolve and parse the external DTD.
     *
     * <p>The external DTD subset is processed **as if it were an internal subset**.
     * The tokens from the external DTD are fed back through the XMLParser to this
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
        // Only process if we have a systemId or publicId
        if (systemId == null && publicId == null) {
            return;
        }
        
        try {
            // Use XMLParser's processExternalEntity to resolve and parse
            // the external DTD subset. This will create a nested XMLTokenizer
            // that sends tokens back through XMLParser to this DTDParser.
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
            throw new SAXException("Failed to resolve external DTD subset: " + systemId, e);
        }
    }

}

